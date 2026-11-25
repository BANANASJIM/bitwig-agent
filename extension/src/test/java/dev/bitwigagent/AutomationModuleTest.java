package dev.bitwigagent;

import com.bitwig.extension.controller.api.*;
import com.bitwig.extension.callback.*;
import com.google.gson.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;

/** Validation, lane planning, safety and quarantine around a simulated native automation adapter; not native binding evidence. */
public final class AutomationModuleTest {
    static final String ID = "11111111-1111-1111-1111-111111111111";
    static final String EMPTY = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    static int checks;

    public static void main(String[] args) {
        validation();
        lanePlanning();
        readNeedsNoStoppedTransport();
        writeChecksSafetyAndPassesThePlan();
        gridTargetNeedsAnObservedDevice();
        unknownWriteQuarantinesWritesButNotReads();
        System.out.println("AutomationModuleTest: " + checks + " assertions (simulated adapter only)");
    }

    static void validation() {
        AutomationModule.validateParams("automation.points.get", get("volume"));
        AutomationModule.validateParams("automation.points.get", gridGet(""));
        JsonObject ranged = get("pan"); ranged.add("range", range(4, 4));
        AutomationModule.validateParams("automation.points.get", ranged);
        bad("automation.points.get", get("mute"));
        JsonObject extraField = get("volume"); extraField.getAsJsonObject("target").addProperty("deviceIndex", 0);
        bad("automation.points.get", extraField);
        JsonObject longId = gridGet("p".repeat(1025)); bad("automation.points.get", longId);
        JsonObject badModule = gridGet("X"); badModule.getAsJsonObject("target").addProperty("moduleId", "nope"); bad("automation.points.get", badModule);
        JsonObject reversed = get("pan"); reversed.add("range", range(5, 4)); bad("automation.points.get", reversed);
        AutomationModule.validateParams("automation.points.set", set(points(point(0, 0.25, "linear"), point(4, 1, "hold"))));
        AutomationModule.validateParams("automation.points.set", set(new JsonArray()));
        bad("automation.points.set", set(points(point(4, 0.25, "linear"), point(0, 1, "hold"))));
        bad("automation.points.set", set(points(point(0, 0.25, "linear"), point(0, 1, "hold"))));
        bad("automation.points.set", set(points(point(0, 1.5, "linear"))));
        bad("automation.points.set", set(points(point(0, 0.5, "bezier"))));
        JsonObject curve = point(0, 0.5, "hold"); curve.addProperty("curve", 0.2); bad("automation.points.set", set(points(curve)));
        JsonObject outside = set(points(point(8, 0.5, "hold"))); outside.add("range", range(0, 4)); bad("automation.points.set", outside);
        JsonObject hash = set(new JsonArray()); hash.addProperty("expectedHash", EMPTY.toUpperCase()); bad("automation.points.set", hash);
        JsonObject count = set(new JsonArray()); count.addProperty("expectedCount", 65537); bad("automation.points.set", count);
        JsonArray many = new JsonArray(); for (int i = 0; i <= 1024; i++) many.add(point(i, 0.5, "hold"));
        bad("automation.points.set", set(many));
    }

    static void lanePlanning() {
        check(AutomationModule.hash(List.of()).equals(EMPTY), "empty lane hash is SHA-256 of nothing");
        AutomationModule.Point a = p(0, 0.2, true), b = p(8, 0.4, false), c = p(16, 0.6, true);
        List<AutomationModule.Point> lane = List.of(a, b, c);
        check(!AutomationModule.hash(lane).equals(AutomationModule.hash(List.of(a, p(8, 0.4, true), c))), "interpolation changes the hash");
        check(!AutomationModule.hash(lane).equals(AutomationModule.hash(List.of(a, b, new AutomationModule.Point(16, 0.6, true, 0.1)))), "curve changes the hash");
        List<AutomationModule.Point> replaced = AutomationModule.desired(lane, new double[]{8, 12}, List.of(p(9, 1, false)));
        check(replaced.size() == 3 && replaced.get(0) == a && replaced.get(1).beat == 9 && replaced.get(2) == c, "inclusive range replace keeps points outside it");
        check(AutomationModule.desired(lane, null, List.of()).isEmpty(), "whole-lane replace with no points clears the lane");
        check(AutomationModule.same(lane, AutomationModule.desired(lane, new double[]{8, 8}, List.of(p(8, 0.4 + 1e-12, false)))), "an equal replacement is already matched");
        AutomationModule.requireExpected(lane, 3, AutomationModule.hash(lane));
        try { AutomationModule.requireExpected(lane, 2, AutomationModule.hash(lane)); throw new AssertionError("expected stale_target"); }
        catch (RpcException e) { check(e.code.equals("stale_target") && e.outcome.equals("not_applied"), "count mismatch is stale_target before dispatch"); }
        try { AutomationModule.verify(lane, List.of(a, p(8, 0.41, false), c)); throw new AssertionError("expected mismatch"); }
        catch (RpcException e) { check(e.code.equals("automation_verification_failed") && e.outcome.equals("unknown"), "readback mismatch is unknown"); }
        AutomationModule.verify(lane, List.of(a, p(8, 0.4 + 1e-12, false), c));
        List<AutomationModule.Point> big = new ArrayList<>();
        for (int i = 0; i < 1500; i++) big.add(p(i, 0.5, true));
        JsonObject summary = AutomationModule.lane(ID, AutomationModule.Target.parse(get("volume")), true, big, new double[]{10, 20});
        check(summary.get("count").getAsInt() == 1500 && summary.get("pointsInRange").getAsInt() == 11
                && summary.getAsJsonArray("points").size() == 11 && !summary.get("truncated").getAsBoolean(), "range filters listed points, count covers the lane");
        JsonObject whole = AutomationModule.lane(ID, AutomationModule.Target.parse(get("volume")), true, big, null);
        check(whole.getAsJsonArray("points").size() == AutomationModule.MAX_READ && whole.get("truncated").getAsBoolean(), "listing is bounded");
    }

    static void readNeedsNoStoppedTransport() {
        Fixture f = new Fixture(true);
        JsonObject result = f.call("automation.points.get", get("volume"));
        check(result.get("status").getAsString().equals("observed") && f.reads == 1 && f.writes == 0, "get reads through the adapter while playing");
        check(f.lastTarget.kind.equals("volume") && f.lastRange == null, "target and absent range reach the adapter");
    }

    static void writeChecksSafetyAndPassesThePlan() {
        Fixture playing = new Fixture(true);
        expect("unsafe_state", playing.start("automation.points.set", set(points(point(0, 0.5, "hold")))));
        check(playing.calls == 0, "playing transport blocks automation writes before native access");
        Fixture f = new Fixture(false);
        JsonObject p = set(points(point(2, 0.25, "linear"), point(3, 0.75, "hold"))); p.add("range", range(2, 6)); p.addProperty("expectedCount", 4);
        f.call("automation.points.set", p);
        check(f.writes == 1 && f.lastPoints.size() == 2 && f.lastCount == 4 && f.lastHash.equals(EMPTY), "points, count and hash reach the adapter");
        check(f.lastPoints.get(0).linear && !f.lastPoints.get(1).linear && f.lastPoints.get(1).value == 0.75 && f.lastPoints.get(1).curve == 0, "point fields keep beat/value/interpolation; curve is 0");
        check(f.lastRange[0] == 2 && f.lastRange[1] == 6, "range passes through unchanged");
    }

    static void gridTargetNeedsAnObservedDevice() {
        Fixture f = new Fixture(false);
        HostOperation op = f.start("automation.points.get", gridGet("CUTOFF")); f.drain();
        try { op.result.join(); throw new AssertionError("expected refusal"); }
        catch (CompletionException e) { check(((RpcException) e.getCause()).outcome.equals("not_applied"), "unobserved device is refused"); }
        check(f.calls == 0, "grid target is refused before native access without an observed device");
    }

    static void unknownWriteQuarantinesWritesButNotReads() {
        Fixture f = new Fixture(false); f.failAfterWrite = true;
        HostOperation failed = f.start("automation.points.set", set(points(point(0, 0.5, "hold")))); f.drain();
        check(failed.dispatched, "failure happened after dispatch");
        try { failed.result.join(); throw new AssertionError("expected failure"); }
        catch (CompletionException e) { check(((RpcException) e.getCause()).outcome.equals("unknown"), "post-dispatch failure is unknown"); }
        expect("unresolved_mutation", f.start("automation.points.set", set(new JsonArray())));
        check(f.call("automation.points.get", get("pan")).get("status").getAsString().equals("observed"), "reads stay available");
        check(!f.module.unresolved().isEmpty(), "unresolved write is listed for extension.restart");
        Fixture before = new Fixture(false); before.failBeforeWrite = true;
        HostOperation rejected = before.start("automation.points.set", set(new JsonArray())); before.drain();
        expect("stale_target", rejected);
        check(!rejected.dispatched && before.module.unresolved().isEmpty(), "pre-dispatch refusal does not quarantine");
    }

    static AutomationModule.Point p(double beat, double value, boolean linear) { return new AutomationModule.Point(beat, value, linear, 0); }
    static JsonObject target(String kind) { JsonObject t = new JsonObject(); t.addProperty("kind", kind); return t; }
    static JsonObject get(String kind) { JsonObject p = new JsonObject(); p.addProperty("trackId", ID); p.add("target", target(kind)); return p; }
    static JsonObject gridGet(String parameterId) {
        JsonObject p = get("grid"), t = p.getAsJsonObject("target");
        t.addProperty("deviceIndex", 0); t.addProperty("expectedDeviceName", "Poly Grid");
        t.addProperty("moduleId", "22222222-2222-2222-2222-222222222222"); t.addProperty("parameterId", parameterId);
        return p;
    }
    static JsonObject set(JsonArray points) {
        JsonObject p = get("volume"); p.addProperty("expectedCount", 0); p.addProperty("expectedHash", EMPTY); p.add("points", points); return p;
    }
    static JsonObject range(double start, double end) { JsonObject r = new JsonObject(); r.addProperty("startBeat", start); r.addProperty("endBeat", end); return r; }
    static JsonObject point(double beat, double value, String interpolation) {
        JsonObject n = new JsonObject(); n.addProperty("beat", beat); n.addProperty("value", value); n.addProperty("interpolation", interpolation); return n;
    }
    static JsonArray points(JsonObject... points) { JsonArray a = new JsonArray(); for (JsonObject n : points) a.add(n); return a; }
    static void bad(String m, JsonObject p) {
        try { AutomationModule.validateParams(m, p); throw new AssertionError("accepted invalid " + p); }
        catch (RpcException e) { check(e.code.equals("invalid_params"), "invalid params: " + p); }
    }
    static void check(boolean x, String text) { checks++; if (!x) throw new AssertionError(text); }
    static void expect(String code, HostOperation op) {
        try { op.result.join(); throw new AssertionError("expected " + code); }
        catch (CompletionException e) {
            check(e.getCause() instanceof RpcException && ((RpcException) e.getCause()).code.equals(code), "error " + code + ": " + e.getCause());
        }
    }

    static final class Fixture {
        final ArrayDeque<Runnable> queue = new ArrayDeque<>();
        final AutomationModule module;
        final boolean playing;
        boolean failAfterWrite, failBeforeWrite;
        int writes, calls, reads, lastCount;
        String lastHash; double[] lastRange; List<AutomationModule.Point> lastPoints; AutomationModule.Target lastTarget;

        Fixture(boolean playing) {
            this.playing = playing;
            Node host = new Node(ControllerHost.class, "host");
            HostServices services = new HostServices((ControllerHost) host.proxy);
            module = new AutomationModule(services, new AutomationModule.Access() {
                public void execute(Runnable task) { queue.add(task); }
                public JsonObject get(Object track, String id, AutomationModule.Target target, double[] range, Runnable check) {
                    calls++; reads++; lastTarget = target; lastRange = range; check.run(); return HostServices.observed();
                }
                public JsonObject set(Object track, String id, AutomationModule.Target target, int count, String hash,
                                      List<AutomationModule.Point> points, double[] range, Runnable check, Runnable before) {
                    calls++; check.run(); lastTarget = target; lastCount = count; lastHash = hash; lastPoints = points; lastRange = range;
                    if (failBeforeWrite) throw HostServices.failure("stale_target", "simulated hash mismatch");
                    before.run(); writes++;
                    if (failAfterWrite) throw new RpcException("automation_verification_failed", "simulated readback mismatch", "unknown");
                    return HostServices.observed();
                }
            });
        }
        HostOperation start(String m, JsonObject p) {
            HostOperation op = new HostOperation(System.nanoTime() + TimeUnit.SECONDS.toNanos(5));
            try { module.validate(m, p); module.dispatch(op, m, p); } catch (RuntimeException e) { op.fail(e); }
            return op;
        }
        void drain() { while (!queue.isEmpty()) queue.remove().run(); }
        JsonObject call(String m, JsonObject p) { HostOperation op = start(m, p); drain(); return op.result.join(); }

        final class Node implements InvocationHandler {
            final Object proxy; final String path; final Map<String, Node> children = new HashMap<>();
            Node(Class<?> type, String path) { this.path = path; proxy = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, this); }
            Object val(Class<?> type) {
                if (type == boolean.class || type == Boolean.class) return path.endsWith("exists") || (path.endsWith("isPlaying") && playing);
                if (type == String.class) return path.endsWith("channelId") ? ID : "";
                if (type == int.class || type == Integer.class) return path.endsWith("itemCount") ? 1 : 0;
                if (type == double.class || type == Double.class) return 0d;
                return null;
            }
            public Object invoke(Object proxy, Method m, Object[] args) {
                String n = m.getName();
                if (n.equals("toString")) return path;
                if (n.equals("hashCode")) return System.identityHashCode(proxy);
                if (n.equals("equals")) return proxy == args[0];
                if (n.equals("scheduleTask")) { queue.add((Runnable) args[0]); return null; }
                if (n.equals("addValueObserver")) {
                    Object cb = args[0];
                    if (cb instanceof BooleanValueChangedCallback) ((BooleanValueChangedCallback) cb).valueChanged((Boolean) val(boolean.class));
                    else if (cb instanceof StringValueChangedCallback) ((StringValueChangedCallback) cb).valueChanged((String) val(String.class));
                    else if (cb instanceof IntegerValueChangedCallback) ((IntegerValueChangedCallback) cb).valueChanged((Integer) val(int.class));
                    return null;
                }
                Class<?> returns = m.getReturnType();
                if (n.equals("getItemAt")) {
                    if (path.endsWith("createTrackBank")) returns = Track.class;
                    else if (path.endsWith("createDeviceBank")) returns = Device.class;
                    else if (path.endsWith("createLayerBank")) returns = DeviceLayer.class;
                    else if (path.endsWith("clipLauncherSlotBank")) returns = ClipLauncherSlot.class;
                }
                if (returns.isInterface()) {
                    final Class<?> childType = returns;
                    String key = n + (n.equals("getItemAt") ? args[0] : "");
                    return children.computeIfAbsent(key, k -> new Node(childType, path + "/" + key)).proxy;
                }
                return val(returns);
            }
        }
    }
}
