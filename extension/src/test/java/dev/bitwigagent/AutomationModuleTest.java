package dev.bitwigagent;

import static dev.bitwigagent.RpcAssert.*;
import static org.junit.jupiter.api.Assertions.*;

import com.bitwig.extension.callback.*;
import com.bitwig.extension.controller.api.*;
import com.google.gson.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

/** Validation, lane planning, safety and quarantine around a simulated native automation adapter; not native binding evidence. */
public final class AutomationModuleTest {
    static final String ID = "11111111-1111-1111-1111-111111111111";
    static final String EMPTY = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    @Test
    void validation() {
        AutomationModule.validateParams("automation.points.get", get("volume"));
        AutomationModule.validateParams("automation.points.get", gridGet(""));
        JsonObject ranged = get("pan");
        ranged.add("range", range(4, 4));
        AutomationModule.validateParams("automation.points.get", ranged);
        bad("automation.points.get", get("mute"));
        JsonObject extraField = get("volume");
        extraField.getAsJsonObject("target").addProperty("deviceIndex", 0);
        bad("automation.points.get", extraField);
        JsonObject longId = gridGet("p".repeat(1025));
        bad("automation.points.get", longId);
        JsonObject badModule = gridGet("X");
        badModule.getAsJsonObject("target").addProperty("moduleId", "nope");
        bad("automation.points.get", badModule);
        JsonObject reversed = get("pan");
        reversed.add("range", range(5, 4));
        bad("automation.points.get", reversed);
        AutomationModule.validateParams(
                "automation.points.set", set(points(point(0, 0.25, "linear"), point(4, 1, "hold"))));
        AutomationModule.validateParams("automation.points.set", set(new JsonArray()));
        bad("automation.points.set", set(points(point(4, 0.25, "linear"), point(0, 1, "hold"))));
        bad("automation.points.set", set(points(point(0, 0.25, "linear"), point(0, 1, "hold"))));
        bad("automation.points.set", set(points(point(0, 1.5, "linear"))));
        bad("automation.points.set", set(points(point(0, 0.5, "bezier"))));
        JsonObject curve = point(0, 0.5, "hold");
        curve.addProperty("curve", 0.2);
        bad("automation.points.set", set(points(curve)));
        JsonObject outside = set(points(point(8, 0.5, "hold")));
        outside.add("range", range(0, 4));
        bad("automation.points.set", outside);
        JsonObject hash = set(new JsonArray());
        hash.addProperty("expectedHash", EMPTY.toUpperCase());
        bad("automation.points.set", hash);
        JsonObject count = set(new JsonArray());
        count.addProperty("expectedCount", 65537);
        bad("automation.points.set", count);
        JsonArray many = new JsonArray();
        for (int i = 0; i <= 1024; i++) many.add(point(i, 0.5, "hold"));
        bad("automation.points.set", set(many));
    }

    @Test
    void lanePlanning() {
        assertTrue(AutomationModule.hash(List.of()).equals(EMPTY), "empty lane hash is SHA-256 of nothing");
        AutomationModule.Point a = p(0, 0.2, true), b = p(8, 0.4, false), c = p(16, 0.6, true);
        List<AutomationModule.Point> lane = List.of(a, b, c);
        assertTrue(
                !AutomationModule.hash(lane).equals(AutomationModule.hash(List.of(a, p(8, 0.4, true), c))),
                "interpolation changes the hash");
        assertTrue(
                !AutomationModule.hash(lane)
                        .equals(AutomationModule.hash(List.of(a, b, new AutomationModule.Point(16, 0.6, true, 0.1)))),
                "curve changes the hash");
        List<AutomationModule.Point> replaced =
                AutomationModule.desired(lane, new double[] {8, 12}, List.of(p(9, 1, false)));
        assertTrue(
                replaced.size() == 3 && replaced.get(0) == a && replaced.get(1).beat == 9 && replaced.get(2) == c,
                "inclusive range replace keeps points outside it");
        assertTrue(
                AutomationModule.desired(lane, null, List.of()).isEmpty(),
                "whole-lane replace with no points clears the lane");
        assertTrue(
                AutomationModule.same(
                        lane, AutomationModule.desired(lane, new double[] {8, 8}, List.of(p(8, 0.4 + 1e-12, false)))),
                "an equal replacement is already matched");
        AutomationModule.requireExpected(lane, 3, AutomationModule.hash(lane));
        {
            RpcException e = assertThrows(
                    RpcException.class, () -> AutomationModule.requireExpected(lane, 2, AutomationModule.hash(lane)));
            assertTrue(
                    e.reason.wire().equals("stale_target") && !e.unknown,
                    "count mismatch is stale_target before dispatch");
        }
        {
            RpcException e = assertThrows(
                    RpcException.class, () -> AutomationModule.verify(lane, List.of(a, p(8, 0.41, false), c)));
            assertTrue(e.reason.wire().equals("readback_mismatch") && e.unknown, "readback mismatch is unknown");
        }
        AutomationModule.verify(lane, List.of(a, p(8, 0.4 + 1e-12, false), c));
        List<AutomationModule.Point> big = new ArrayList<>();
        for (int i = 0; i < 1500; i++) big.add(p(i, 0.5, true));
        JsonObject summary = AutomationModule.lane(
                new JsonObject(), ID, AutomationModule.Target.parse(get("volume")), true, big, new double[] {10, 20});
        assertTrue(
                summary.get("count").getAsInt() == 1500
                        && summary.get("pointsInRange").getAsInt() == 11
                        && summary.getAsJsonArray("points").size() == 11
                        && !summary.get("truncated").getAsBoolean(),
                "range filters listed points, count covers the lane");
        JsonObject whole = AutomationModule.lane(
                new JsonObject(), ID, AutomationModule.Target.parse(get("volume")), true, big, null);
        assertTrue(
                whole.getAsJsonArray("points").size() == AutomationModule.MAX_READ
                        && whole.get("truncated").getAsBoolean(),
                "listing is bounded");
    }

    @Test
    void unknownMethodAndUngatedHostAreRefused() {
        assertRpcError(
                "unknown_method", () -> AutomationModule.validateParams("automation.points.clear", get("volume")));
        HostFixture host = new HostFixture();
        host.tracks[0].child("channelId").emit(ID);
        HostOperation op = host.start(host.services.automation(null), "automation.points.get", get("volume"));
        assertRpcError("host_mismatch", op);
    }

    @Test
    void readNeedsNoStoppedTransport() {
        Fixture f = new Fixture(true);
        JsonObject result = f.call("automation.points.get", get("volume"));
        assertTrue(
                !result.has("outcome") && f.reads == 1 && f.writes == 0, "get reads through the adapter while playing");
        assertTrue(
                f.lastTarget.kind.equals("volume") && f.lastRange == null, "target and absent range reach the adapter");
    }

    @Test
    void writeChecksSafetyAndPassesThePlan() {
        Fixture playing = new Fixture(true);
        assertRpcError("unsafe_state", playing.start("automation.points.set", set(points(point(0, 0.5, "hold")))));
        assertTrue(playing.calls == 0, "playing transport blocks automation writes before native access");
        Fixture f = new Fixture(false);
        JsonObject p = set(points(point(2, 0.25, "linear"), point(3, 0.75, "hold")));
        p.add("range", range(2, 6));
        p.addProperty("expectedCount", 4);
        f.call("automation.points.set", p);
        assertTrue(
                f.writes == 1 && f.lastPoints.size() == 2 && f.lastCount == 4 && f.lastHash.equals(EMPTY),
                "points, count and hash reach the adapter");
        assertTrue(
                f.lastPoints.get(0).linear
                        && !f.lastPoints.get(1).linear
                        && f.lastPoints.get(1).value == 0.75
                        && f.lastPoints.get(1).curve == 0,
                "point fields keep beat/value/interpolation; curve is 0");
        assertTrue(f.lastRange[0] == 2 && f.lastRange[1] == 6, "range passes through unchanged");
    }

    @Test
    void gridTargetNeedsAnObservedDevice() {
        Fixture f = new Fixture(false);
        HostOperation op = f.start("automation.points.get", gridGet("CUTOFF"));
        f.drain();
        assertTrue(!rejection(op).unknown, "unobserved device is refused");
        assertTrue(f.calls == 0, "grid target is refused before native access without an observed device");
    }

    @Test
    void pendingOperationIsBusy() {
        Fixture f = new Fixture(false);
        HostOperation first = f.start("automation.points.set", set(points(point(0, 0.5, "hold"))));
        assertTrue(
                f.host.services.holds.list().size() == 1
                        && !f.host.services.holds.list().get(0).ackable,
                "a running native operation is an in-flight hold");
        assertRpcError("busy", f.start("automation.points.get", get("pan")));
        f.drain();
        assertTrue(
                assertOutcome("applied", first) != null
                        && f.host.services.holds.list().isEmpty(),
                "the settled operation releases its hold");
    }

    @Test
    void unknownWriteHoldsWritesButNotReadsUntilAck() {
        Fixture f = new Fixture(false);
        f.failAfterWrite = true;
        HostOperation failed = f.start("automation.points.set", set(points(point(0, 0.5, "hold"))));
        f.drain();
        assertTrue(failed.dispatched(), "failure happened after dispatch");
        assertUnknown("readback_mismatch", failed);
        String next = assertRpcError("unresolved", f.start("automation.points.set", set(new JsonArray()))).next;
        assertTrue(
                next.contains("automation.points.get") && next.contains("ack automation.quarantine"),
                "the refusal says what to inspect and what to ack");
        assertTrue(!f.call("automation.points.get", get("pan")).has("outcome"), "reads stay available");
        List<Holds.View> holds = f.host.services.holds.list();
        assertTrue(
                holds.size() == 1
                        && holds.get(0).ackable
                        && holds.get(0).description.contains(ID),
                "the unknown write is an unresolved hold");
        f.host.services.holds.ack("automation.quarantine");
        f.failAfterWrite = false;
        HostOperation after = f.start("automation.points.set", set(new JsonArray()));
        f.drain();
        assertOutcome("applied", after);
        Fixture before = new Fixture(false);
        before.failBeforeWrite = true;
        HostOperation rejected = before.start("automation.points.set", set(new JsonArray()));
        before.drain();
        assertRpcError("stale_target", rejected);
        assertTrue(
                !rejected.dispatched() && before.host.services.holds.list().isEmpty(),
                "pre-dispatch refusal holds nothing");
    }

    static AutomationModule.Point p(double beat, double value, boolean linear) {
        return new AutomationModule.Point(beat, value, linear, 0);
    }

    static JsonObject target(String kind) {
        JsonObject t = new JsonObject();
        t.addProperty("kind", kind);
        return t;
    }

    static JsonObject get(String kind) {
        JsonObject p = new JsonObject();
        p.addProperty("trackId", ID);
        p.add("target", target(kind));
        return p;
    }

    static JsonObject gridGet(String parameterId) {
        JsonObject p = get("grid"), t = p.getAsJsonObject("target");
        t.addProperty("deviceIndex", 0);
        t.addProperty("expectedDeviceName", "Poly Grid");
        t.addProperty("moduleId", "22222222-2222-2222-2222-222222222222");
        t.addProperty("parameterId", parameterId);
        return p;
    }

    static JsonObject set(JsonArray points) {
        JsonObject p = get("volume");
        p.addProperty("expectedCount", 0);
        p.addProperty("expectedHash", EMPTY);
        p.add("points", points);
        return p;
    }

    static JsonObject range(double start, double end) {
        JsonObject r = new JsonObject();
        r.addProperty("startBeat", start);
        r.addProperty("endBeat", end);
        return r;
    }

    static JsonObject point(double beat, double value, String interpolation) {
        JsonObject n = new JsonObject();
        n.addProperty("beat", beat);
        n.addProperty("value", value);
        n.addProperty("interpolation", interpolation);
        return n;
    }

    static JsonArray points(JsonObject... points) {
        JsonArray a = new JsonArray();
        for (JsonObject n : points) a.add(n);
        return a;
    }

    static void bad(String m, JsonObject p) {
        assertRpcError("invalid_params", () -> AutomationModule.validateParams(m, p));
    }

    static final class Fixture {
        final HostFixture host = new HostFixture();
        final ScheduledTasks queue = host.tasks;
        final AutomationModule module;
        final boolean playing;
        boolean failAfterWrite, failBeforeWrite;
        int writes, calls, reads, lastCount;
        String lastHash;
        double[] lastRange;
        List<AutomationModule.Point> lastPoints;
        AutomationModule.Target lastTarget;

        Fixture(boolean playing) {
            this.playing = playing;
            host.tracks[0].child("channelId").emit(ID);
            host.transport.child("isPlaying").emit(playing);
            module = host.services.automation(new AutomationModule.Access() {
                public void execute(Runnable task) {
                    queue.add(task);
                }

                public JsonObject get(
                        Object track, String id, AutomationModule.Target target, double[] range, Runnable check) {
                    calls++;
                    reads++;
                    lastTarget = target;
                    lastRange = range;
                    check.run();
                    return new JsonObject();
                }

                public JsonObject set(
                        Object track,
                        String id,
                        AutomationModule.Target target,
                        int count,
                        String hash,
                        List<AutomationModule.Point> points,
                        double[] range,
                        Runnable check,
                        Runnable before) {
                    calls++;
                    check.run();
                    lastTarget = target;
                    lastCount = count;
                    lastHash = hash;
                    lastPoints = points;
                    lastRange = range;
                    if (failBeforeWrite) throw Errors.notApplied(Reason.STALE_TARGET, "simulated hash mismatch");
                    before.run();
                    writes++;
                    if (failAfterWrite) throw Errors.unknown(Reason.READBACK_MISMATCH, "simulated readback mismatch");
                    return Results.outcome(Outcome.APPLIED);
                }
            });
        }

        HostOperation start(String m, JsonObject p) {
            return host.start(module, m, p);
        }

        void drain() {
            host.drain();
        }

        JsonObject call(String m, JsonObject p) {
            HostOperation op = start(m, p);
            drain();
            return op.result.join();
        }
    }
}
