package dev.bitwigagent;

import com.bitwig.extension.controller.api.*;
import com.bitwig.extension.callback.*;
import com.google.gson.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;

/** Validation, safety and quarantine around a simulated native arranger adapter; not native binding evidence. */
public final class ArrangerModuleTest {
    static final String ID = "11111111-1111-1111-1111-111111111111";
    static int checks;

    public static void main(String[] args) {
        validation();
        listIsReadOnlyAndNeedsNoStoppedTransport();
        createChecksTrackTypeSafetyAndPassesNotes();
        copyChecksTheObservedLauncherSlot();
        editPassesOnlyItsPayload();
        unknownWriteQuarantinesWritesButNotReads();
        System.out.println("ArrangerModuleTest: " + checks + " assertions (simulated adapter only)");
    }

    static void validation() {
        ArrangerModule.validateParams("arranger.clips.list", obj("trackId", ID));
        bad("arranger.clips.list", obj("trackId", "not-a-uuid"));
        bad("arranger.clips.list", obj("trackId", ID, "extra", 1));
        JsonObject create = create(0, 16);
        ArrangerModule.validateParams("arranger.clips.create", create);
        for (String[] field : new String[][]{{"startBeats", "-1"}, {"startBeats", "4194304.5"}, {"lengthBeats", "0"}, {"lengthBeats", "65537"}}) {
            JsonObject b = create(0, 16); b.addProperty(field[0], Double.parseDouble(field[1])); bad("arranger.clips.create", b);
        }
        JsonObject named = create(0, 16); named.addProperty("name", ""); bad("arranger.clips.create", named);
        JsonObject channelOnly = create(0, 16); channelOnly.addProperty("channel", 1); bad("arranger.clips.create", channelOnly);
        JsonObject notes = create(0, 4); notes.add("notes", noteArray(note(0, 60, 100, 1), note(1, 60, 100, 1)));
        ArrangerModule.validateParams("arranger.clips.create", notes);
        check(ArrangerModule.notes(notes, 4).size() == 2, "two adjacent same-pitch notes are valid");
        JsonObject overlap = create(0, 4); overlap.add("notes", noteArray(note(0, 60, 100, 2), note(1, 60, 100, 1)));
        bad("arranger.clips.create", overlap);
        JsonObject outside = create(0, 4); outside.add("notes", noteArray(note(3.5, 60, 100, 1)));
        bad("arranger.clips.create", outside);
        JsonObject offGrid = create(0, 4); offGrid.add("notes", noteArray(note(0.001, 60, 100, 1)));
        bad("arranger.clips.create", offGrid);
        JsonObject withOp = create(0, 4); JsonObject n = note(0, 60, 100, 1); n.addProperty("op", "set"); withOp.add("notes", noteArray(n));
        bad("arranger.clips.create", withOp);
        ArrangerModule.validateParams("arranger.clips.copyFromLauncher", copy());
        JsonObject slot = copy(); slot.addProperty("slotIndex", 16); bad("arranger.clips.copyFromLauncher", slot);
        JsonObject edit = edit("move"); edit.addProperty("startBeats", 8);
        ArrangerModule.validateParams("arranger.clips.edit", edit);
        JsonObject emptyName = edit("delete"); emptyName.addProperty("expectedName", "");
        ArrangerModule.validateParams("arranger.clips.edit", emptyName);
        bad("arranger.clips.edit", edit("move"));
        JsonObject extra = edit("delete"); extra.addProperty("startBeats", 1); bad("arranger.clips.edit", extra);
        JsonObject op = edit("split"); bad("arranger.clips.edit", op);
        JsonObject rename = edit("rename"); rename.addProperty("name", ""); bad("arranger.clips.edit", rename);
    }

    static void listIsReadOnlyAndNeedsNoStoppedTransport() {
        Fixture f = new Fixture(true, "Audio");
        JsonObject result = f.call("arranger.clips.list", obj("trackId", ID));
        check(result.get("status").getAsString().equals("observed") && f.lists == 1 && f.writes == 0, "list reads through the adapter without writing");
    }

    static void createChecksTrackTypeSafetyAndPassesNotes() {
        Fixture audio = new Fixture(false, "Audio");
        expect("unsupported_scope", audio.start("arranger.clips.create", create(0, 4)));
        check(audio.calls == 0, "note clip on an audio track is refused before native access");
        Fixture playing = new Fixture(true, "Instrument");
        expect("unsafe_state", playing.start("arranger.clips.create", create(0, 4)));
        check(playing.calls == 0, "playing transport blocks arranger writes");
        Fixture f = new Fixture(false, "Instrument");
        JsonObject p = create(8, 4); p.add("notes", noteArray(note(0, 60, 100, 1), note(2, 64, 90, 0.5))); p.addProperty("channel", 2);
        f.call("arranger.clips.create", p);
        check(f.writes == 1 && f.lastNotes.size() == 2 && f.lastChannel == 2, "notes and channel reach the adapter");
        check(f.lastNotes.get(1)[1] == 64 && f.lastNotes.get(1)[2] == 90 && f.lastNotes.get(1)[3] == 0.5, "note fields keep beat/pitch/velocity/duration order");
        check(f.lastStart == 8 && f.lastLength == 4, "start and length pass through unchanged");
    }

    static void copyChecksTheObservedLauncherSlot() {
        Fixture f = new Fixture(false, "Audio");
        f.call("arranger.clips.copyFromLauncher", copy());
        check(f.writes == 1 && f.lastSlot != null, "copy passes the launcher slot proxy");
        Fixture wrong = new Fixture(false, "Audio");
        JsonObject b = copy(); b.addProperty("expectedClipName", "Chorus");
        expect("stale_target", wrong.start("arranger.clips.copyFromLauncher", b));
        check(wrong.calls == 0, "a different observed clip name is refused before native access");
        Fixture empty = new Fixture(false, "Audio"); empty.slotContent = false;
        expect("stale_target", empty.start("arranger.clips.copyFromLauncher", copy()));
    }

    static void editPassesOnlyItsPayload() {
        Fixture f = new Fixture(false, "Instrument");
        JsonObject p = edit("resize"); p.addProperty("lengthBeats", 12);
        f.call("arranger.clips.edit", p);
        check(f.lastOp.equals("resize") && f.lastArgs.keySet().equals(Set.of("lengthBeats")), "edit forwards op and exactly its payload");
    }

    static void unknownWriteQuarantinesWritesButNotReads() {
        Fixture f = new Fixture(false, "Instrument"); f.failAfterWrite = true;
        HostOperation failed = f.start("arranger.clips.create", create(0, 4)); f.drain();
        check(failed.dispatched, "failure happened after dispatch");
        try { failed.result.join(); throw new AssertionError("expected failure"); }
        catch (CompletionException e) { check(((RpcException) e.getCause()).outcome.equals("unknown"), "post-dispatch failure is unknown"); }
        expect("unresolved_mutation", f.start("arranger.clips.create", create(8, 4)));
        check(f.call("arranger.clips.list", obj("trackId", ID)).get("status").getAsString().equals("observed"), "reads stay available");
        check(!f.module.unresolved().isEmpty(), "unresolved write is listed for extension.restart");
        Fixture before = new Fixture(false, "Instrument"); before.failBeforeWrite = true;
        HostOperation rejected = before.start("arranger.clips.create", create(0, 4)); before.drain();
        expect("arranger_overlap", rejected);
        check(!rejected.dispatched && before.module.unresolved().isEmpty(), "pre-dispatch refusal does not quarantine");
    }

    static JsonObject create(double start, double length) {
        JsonObject p = obj("trackId", ID); p.addProperty("startBeats", start); p.addProperty("lengthBeats", length); p.addProperty("name", "Verse"); return p;
    }
    static JsonObject copy() {
        JsonObject p = obj("trackId", ID); p.addProperty("slotIndex", 0); p.addProperty("expectedClipName", "Verse"); p.addProperty("startBeats", 0); return p;
    }
    static JsonObject edit(String op) {
        JsonObject p = obj("trackId", ID); p.addProperty("clipIndex", 0); p.addProperty("expectedStartBeats", 0); p.addProperty("expectedName", "Verse"); p.addProperty("op", op); return p;
    }
    static JsonObject note(double beat, int pitch, int velocity, double duration) {
        JsonObject n = new JsonObject(); n.addProperty("beat", beat); n.addProperty("pitch", pitch); n.addProperty("velocity", velocity); n.addProperty("durationBeats", duration); return n;
    }
    static JsonArray noteArray(JsonObject... notes) { JsonArray a = new JsonArray(); for (JsonObject n : notes) a.add(n); return a; }
    static JsonObject obj(Object... kv) {
        JsonObject p = new JsonObject();
        for (int i = 0; i < kv.length; i += 2) {
            if (kv[i + 1] instanceof Number) p.addProperty((String) kv[i], (Number) kv[i + 1]); else p.addProperty((String) kv[i], (String) kv[i + 1]);
        }
        return p;
    }
    static void bad(String m, JsonObject p) {
        try { ArrangerModule.validateParams(m, p); throw new AssertionError("accepted invalid " + p); }
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
        final ArrangerModule module;
        final boolean playing;
        final String trackType;
        boolean slotContent = true, failAfterWrite, failBeforeWrite;
        int writes, calls, lists;
        List<double[]> lastNotes; int lastChannel; double lastStart, lastLength; Object lastSlot; String lastOp; JsonObject lastArgs;

        Fixture(boolean playing, String trackType) {
            this.playing = playing; this.trackType = trackType;
            Node host = new Node(ControllerHost.class, "host");
            HostServices services = new HostServices((ControllerHost) host.proxy);
            module = new ArrangerModule(services, new ArrangerModule.Access() {
                public void execute(Runnable task) { queue.add(task); }
                public JsonObject list(Object track, String id, Runnable check) { lists++; check.run(); return HostServices.observed(); }
                public JsonObject create(Object track, String id, double start, double length, String name, List<double[]> notes, int channel, Runnable check, Runnable before) {
                    lastNotes = notes; lastChannel = channel; lastStart = start; lastLength = length; return write(check, before);
                }
                public JsonObject copyFromLauncher(Object track, Object slot, String id, String expected, double start, Runnable check, Runnable before) {
                    lastSlot = slot; return write(check, before);
                }
                public JsonObject edit(Object track, String id, int index, double start, String name, String op, JsonObject args, Runnable check, Runnable before) {
                    lastOp = op; lastArgs = args; return write(check, before);
                }
            });
        }
        JsonObject write(Runnable check, Runnable before) {
            calls++; check.run();
            if (failBeforeWrite) throw HostServices.failure("arranger_overlap", "simulated overlap");
            before.run(); writes++;
            if (failAfterWrite) throw new RpcException("arranger_verification_failed", "simulated readback mismatch", "unknown");
            return HostServices.observed();
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
                if (type == boolean.class || type == Boolean.class)
                    return path.endsWith("exists") || (path.endsWith("hasContent") && slotContent) || (path.endsWith("isPlaying") && playing);
                if (type == String.class)
                    return path.endsWith("channelId") ? ID : path.endsWith("trackType") ? trackType : path.endsWith("name") ? "Verse" : "";
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
