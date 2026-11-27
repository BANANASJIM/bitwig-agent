package dev.bitwigagent.automation;

import static dev.bitwigagent.RpcAssert.*;
import static org.junit.jupiter.api.Assertions.*;

import com.bitwig.extension.callback.*;
import com.bitwig.extension.controller.api.*;
import com.google.gson.*;
import dev.bitwigagent.FakeHost;
import dev.bitwigagent.HostBridge;
import dev.bitwigagent.HostFixture;
import dev.bitwigagent.ScheduledTasks;
import dev.bitwigagent.core.Holds;
import dev.bitwigagent.core.HostOperation;
import dev.bitwigagent.core.Refs;
import dev.bitwigagent.core.RpcException;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

/** Validation, lane planning, safety and quarantine around a simulated native automation adapter; not native binding evidence. */
public final class AutomationModuleTest {
    static final String ID = "11111111-1111-1111-1111-111111111111";
    static final String MODULE = "22222222-2222-2222-2222-222222222222";
    static final JsonObject TRACK = Refs.track(ID, "Track 0");
    static final String EMPTY = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    @Test
    void validation() {
        accepted("automation.points.get", get("volume"));
        accepted("automation.points.get", gridGet("CUTOFF"));
        JsonObject ranged = get("pan");
        ranged.add("range", range(4, 4));
        accepted("automation.points.get", ranged);
        JsonObject mute = get("volume");
        mute.getAsJsonObject("target").addProperty("id", "mute");
        bad("automation.points.get", mute);
        JsonObject deviceOwned = get("volume");
        deviceOwned.add("target", Refs.parameter(Refs.device(TRACK, 0, "EQ+"), "gain", "Gain"));
        accepted("automation.points.get", deviceOwned);
        accepted("automation.points.get", nestedGet());
        JsonObject device = Refs.device(TRACK, 0, "Poly Grid");
        for (JsonObject owner :
                new JsonObject[] {Refs.modulator(device, MODULE, "LFO"), Refs.remotePage(device, 0, "Main")}) {
            JsonObject other = get("volume");
            other.add("target", Refs.parameter(owner, "0", null));
            bad("automation.points.get", other);
        }
        JsonObject legacy = get("volume");
        legacy.addProperty("trackId", ID);
        bad("automation.points.get", legacy);
        JsonObject reversed = get("pan");
        reversed.add("range", range(5, 4));
        bad("automation.points.get", reversed);
        accepted("automation.points.set", set(points(point(0, 0.25, "linear"), point(4, 1, "hold"))));
        accepted("automation.points.set", set(new JsonArray()));
        JsonObject omitted = point(0, 0.5, "linear");
        omitted.remove("interpolation");
        accepted("automation.points.set", set(points(omitted)));
        JsonObject all = set(points(point(8, 0.5, "hold")));
        all.addProperty("range", "all");
        accepted("automation.points.set", all);
        JsonObject everything = set(new JsonArray());
        everything.addProperty("range", "everything");
        bad("automation.points.set", everything);
        JsonObject snapped = set(points(point(1.0000001, 0.5, "hold")));
        snapped.add("range", range(1.0000001, 2));
        accepted("automation.points.set", snapped);
        assertEquals(
                1.0,
                AutomationModule.points(snapped).get(0).beat,
                "a beat inside range snaps inside the snapped range");
        JsonObject snappedStep = set(points(point(1, 0.5, "hold"), point(1.0000003, 0.7, "hold")));
        accepted("automation.points.set", snappedStep);
        List<AutomationPlan.Request> step = AutomationModule.points(snappedStep);
        assertTrue(
                step.get(0).beat == step.get(1).beat && step.get(0).ordinal == 0 && step.get(1).ordinal == 1,
                "two beats that snap together are a step, in array order");
        bad(
                "automation.points.set",
                set(points(point(1, 0.5, "hold"), point(1.0000003, 0.5, "hold"), point(1, 0.2, "hold"))));
        assertEquals(
                "a beat holds at most 2 points (a step)",
                assertRpcError(
                                "invalid_params",
                                () -> AutomationModule.points(set(
                                        points(point(2, 0.1, "hold"), point(2, 0.2, "hold"), point(2, 0.3, "hold")))))
                        .getMessage());
        assertEquals(
                0,
                AutomationModule.points(set(points(point(1, 0.5, "hold"), point(2, 0.5, "hold"))))
                        .get(1)
                        .ordinal,
                "a new beat starts again");
        JsonObject allGet = get("pan");
        allGet.addProperty("range", "all");
        bad("automation.points.get", allGet);
        bad("automation.points.set", set(points(point(4, 0.25, "linear"), point(0, 1, "hold"))));
        accepted("automation.points.set", set(points(point(0, 0.25, "linear"), point(0, 1, "hold"))));
        bad("automation.points.set", set(points(point(0, 1.5, "linear"))));
        bad("automation.points.set", set(points(point(0, 0.5, "bezier"))));
        JsonObject curve = point(0, 0.5, "hold");
        curve.addProperty("curve", -1);
        accepted("automation.points.set", set(points(curve)));
        assertEquals(-1.0, AutomationModule.points(set(points(curve))).get(0).curve);
        curve.addProperty("curve", 1.5);
        bad("automation.points.set", set(points(curve)));
        JsonObject display = point(0, 0, "hold");
        display.add("value", HostFixture.json("{\"display\": \"-6 dB\"}"));
        accepted("automation.points.set", set(points(display)));
        AutomationPlan.Request shown =
                AutomationModule.points(set(points(display))).get(0);
        assertTrue(
                shown.value == null && shown.display.equals("-6 dB") && shown.curve == null,
                "a display value stays text");
        display.add("value", HostFixture.json("{\"label\": \"Off\"}"));
        bad("automation.points.set", set(points(display)));
        JsonObject outside = set(points(point(8, 0.5, "hold")));
        outside.add("range", range(0, 4));
        bad("automation.points.set", outside);
        JsonObject hash = set(new JsonArray());
        hash.getAsJsonObject("expect").addProperty("pointsHash", EMPTY.toUpperCase());
        bad("automation.points.set", hash);
        JsonObject emptyExpect = set(new JsonArray());
        emptyExpect.add("expect", new JsonObject());
        bad("automation.points.set", emptyExpect);
        JsonObject count = set(new JsonArray());
        count.addProperty("expectedCount", 0);
        bad("automation.points.set", count);
        JsonArray many = new JsonArray();
        for (int i = 0; i <= 1024; i++) many.add(point(i, 0.5, "hold"));
        bad("automation.points.set", set(many));
    }

    @Test
    void remoteControlAndModulatorRefsAreRefusedInJavaToo() {
        JsonObject device = Refs.device(TRACK, 0, "Poly Grid");
        RpcException remote = assertThrows(
                RpcException.class,
                () -> AutomationModule.kind(Refs.parameter(Refs.remotePage(device, 0, "Main"), "0", "Cutoff")));
        assertTrue(
                remote.reason.wire().equals("unsupported_scope")
                        && "parameters.list".equals(remote.next)
                        && !remote.unknown,
                remote.getMessage());
        RpcException modulator = assertThrows(
                RpcException.class,
                () -> AutomationModule.kind(Refs.parameter(Refs.modulator(device, MODULE, "LFO"), "RATE", null)));
        assertTrue(modulator.reason.wire().equals("unsupported_scope") && modulator.next == null);
        Fixture f = new Fixture(false);
        JsonObject p = new JsonObject();
        p.add("target", Refs.parameter(Refs.remotePage(device, 0, "Main"), "0", null));
        HostOperation op = new HostOperation(f.host.tasks.after(5000));
        assertThrows(RpcException.class, () -> f.module.dispatch(op, "automation.points.get", p));
        assertEquals(0, f.lane.reads, "refused before any resolution or native access");
    }

    @Test
    void curveIsRangeCheckedInJava() {
        JsonObject curve = point(0, 0.5, "hold");
        curve.addProperty("curve", 1.5);
        assertRpcError("invalid_params", () -> AutomationModule.points(set(points(curve))));
        curve.addProperty("curve", Double.NaN);
        assertRpcError("invalid_params", () -> AutomationModule.points(set(points(curve))));
    }

    @Test
    void laneReport() {
        List<AutomationLane.Point> big = new ArrayList<>();
        for (int i = 0; i < 1500; i++) big.add(new AutomationLane.Point(i, 0.5, true, 0.25));
        AutomationLane.Observed lane = AutomationFake.observed(true, AutomationFake.natives(big), List.of());
        JsonObject summary = AutomationLane.json(new JsonObject(), lane, new double[] {10, 20});
        assertTrue(
                summary.getAsJsonObject("lane").get("count").getAsInt() == 1500
                        && summary.get("pointsInRange").getAsInt() == 11
                        && summary.getAsJsonArray("points").size() == 11
                        && !summary.get("truncated").getAsBoolean(),
                "range filters listed points, count covers the lane");
        assertEquals(
                HostFixture.json(
                        "{\"beat\": 10.0, \"value\": 0.5, \"display\": \"-27.0 dB\", \"interpolation\": \"linear\", \"curve\": 0.25}"),
                summary.getAsJsonArray("points").get(0),
                "a point carries its display and curve");
        assertEquals(
                "all",
                AutomationLane.json(new JsonObject(), lane, AutomationLane.ALL)
                        .get("range")
                        .getAsString(),
                "range all echoes as all");
        JsonObject whole = AutomationLane.json(new JsonObject(), lane, null);
        assertTrue(
                whole.getAsJsonArray("points").size() == AutomationLane.MAX_READ
                        && whole.get("truncated").getAsBoolean(),
                "listing is bounded");
    }

    @Test
    void ungatedHostIsRefused() {
        HostFixture host = new HostFixture();
        host.tracks[0].child("channelId").emit(ID);
        HostOperation op = host.start(host.services.automation(null), "automation.points.get", get("volume"));
        assertRpcError("host_mismatch", op);
    }

    @Test
    void aStaleTrackOwnerIsRefusedBeforeNativeAccess() {
        Fixture f = new Fixture(false);
        f.host.tracks[0].child("name").emit("Lead");
        f.call("automation.points.get", get("volume"));
        assertEquals(1, f.lane.reads, "as an ancestor the track name is not compared");
        f.host.tracks[0].child("channelId").emit("33333333-3333-3333-3333-333333333333");
        HostOperation gone = f.start("automation.points.get", get("volume"));
        f.drain();
        assertRpcError("target_not_found", gone);
        assertEquals(1, f.lane.reads, "no native access for a track that is not there");
    }

    @Test
    void readNeedsNoStoppedTransport() {
        Fixture f = new Fixture(true);
        JsonObject result = f.call("automation.points.get", get("volume"));
        assertTrue(
                !result.has("outcome") && f.lane.reads == 1 && f.lane.writes == 0,
                "get reads through the adapter while playing");
        assertTrue(
                f.lane.lastTarget.kind == AutomationModule.Target.Kind.VOLUME
                        && f.lane.lastTarget.device == null
                        && result.getAsJsonObject("lane").get("count").getAsInt() == 0
                        && result.has("ref"),
                "the target reaches the adapter and the lane comes back with the ref");
        f.call("automation.points.get", get("pan"));
        assertEquals(AutomationModule.Target.Kind.PAN, f.lane.lastTarget.kind);
    }

    @Test
    void writeChecksSafetyAndRunsThePlanOnTheLane() {
        Fixture playing = new Fixture(true);
        assertRpcError("unsafe_state", playing.start("automation.points.set", set(points(point(0, 0.5, "hold")))));
        assertTrue(playing.lane.edits == 0, "playing transport blocks automation writes before native access");
        Fixture f = new Fixture(false);
        JsonObject p = set(points(point(2, 0.25, "linear"), point(3, 0.75, "hold")));
        p.add("range", range(2, 6));
        JsonObject created = f.call("automation.points.set", p);
        assertTrue(
                created.get("outcome").getAsString().equals("applied")
                        && created.get("verification").getAsString().equals("native_readback")
                        && created.get("laneCreated").getAsBoolean()
                        && created.get("nativeUndo").getAsBoolean()
                        && created.getAsJsonObject("previous")
                                .get("pointsHash")
                                .getAsString()
                                .equals(EMPTY)
                        && created.getAsJsonObject("summary").equals(AutomationPlanTest.summary(2, 0, 0, 0))
                        && created.getAsJsonObject("lane").get("count").getAsInt() == 2,
                created.toString());
        p.remove("expect");
        JsonObject again = f.call("automation.points.set", p);
        assertTrue(
                again.get("outcome").getAsString().equals("already_matched")
                        && !again.get("nativeUndo").getAsBoolean()
                        && !again.get("laneCreated").getAsBoolean()
                        && f.lane.writes == 1,
                "a replay writes nothing: " + again);
        JsonObject curved = point(3, 0.75, "hold");
        curved.addProperty("curve", 0.5);
        JsonObject bend = set(points(curved));
        bend.remove("expect");
        JsonObject bent = f.call("automation.points.set", bend);
        assertEquals(
                0.5,
                bent.getAsJsonArray("points")
                        .get(1)
                        .getAsJsonObject()
                        .get("curve")
                        .getAsDouble());
        assertEquals(AutomationPlanTest.summary(0, 1, 0, 0), bent.getAsJsonObject("summary"));
        JsonObject display = point(4, 0, "linear");
        display.add("value", HostFixture.json("{\"display\": \"-6 dB\"}"));
        JsonObject shown = set(points(display));
        shown.remove("expect");
        JsonObject written = f.call("automation.points.set", shown);
        assertEquals(List.of("-6 dB"), f.lane.lastDisplays, "the requested displays reach the adapter");
        assertEquals(
                "-6.0 dB",
                written.getAsJsonArray("points")
                        .get(2)
                        .getAsJsonObject()
                        .get("display")
                        .getAsString());
        display.add("value", HostFixture.json("{\"display\": \"800 Hz\"}"));
        HostOperation refused = f.start("automation.points.set", shown);
        f.drain();
        assertRpcError("unsupported_unit", refused);
        assertTrue(!refused.dispatched() && f.host.services.holds.list().isEmpty(), "a refused display writes nothing");
        JsonObject all = set(new JsonArray());
        all.remove("expect");
        all.addProperty("range", "all");
        JsonObject cleared = f.call("automation.points.set", all);
        assertTrue(
                cleared.getAsJsonObject("summary").get("removed").getAsInt() == 3 && f.lane.lane.isEmpty(),
                "range all with no points clears the lane");
    }

    @Test
    void aDeviceParameterInsideAChainIsATargetOnItsRootDevice() {
        Fixture f = new Fixture(false);
        f.observeDevice("Poly Grid");
        JsonObject result = f.call("automation.points.get", nestedGet());
        AutomationModule.Target target = f.lane.lastTarget;
        assertTrue(
                target.kind == AutomationModule.Target.Kind.DEVICE
                        && target.device.ref().get("name").getAsString().equals("Poly Grid")
                        && target.parameterId().equals("CONTENTS/PID1")
                        && result.has("lane"),
                "the parameter ref and its root device reach the adapter");
        JsonObject grid = f.call("automation.points.get", gridGet("CUTOFF"));
        assertTrue(
                f.lane.lastTarget.kind == AutomationModule.Target.Kind.GRID
                        && f.lane.lastTarget.moduleId().toString().equals(MODULE)
                        && grid.has("lane"),
                "a Grid module parameter names its module");
    }

    @Test
    void gridTargetNeedsAnObservedDevice() {
        Fixture f = new Fixture(false);
        HostOperation op = f.start("automation.points.get", gridGet("CUTOFF"));
        f.drain();
        assertTrue(!rejection(op).unknown, "unobserved device is refused");
        assertTrue(f.lane.reads == 0, "grid target is refused before native access without an observed device");
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
        f.lane.afterWrite = lane -> lane.clear();
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
        f.lane.afterWrite = lane -> {};
        HostOperation after = f.start("automation.points.set", set(points(point(0, 0.5, "hold"))));
        f.drain();
        assertOutcome("applied", after);
        Fixture before = new Fixture(false);
        before.lane.exists = true;
        before.lane.lane = AutomationFake.natives(List.of(new AutomationLane.Point(0, 0.5, true, 0)));
        HostOperation rejected = before.start("automation.points.set", set(new JsonArray()));
        before.drain();
        assertRpcError("target_changed", rejected);
        assertTrue(
                !rejected.dispatched() && before.host.services.holds.list().isEmpty(),
                "pre-dispatch refusal holds nothing");
    }

    static JsonObject get(String kind) {
        JsonObject p = new JsonObject();
        p.add("target", Refs.parameter(TRACK, kind, null));
        return p;
    }

    static JsonObject gridGet(String parameterId) {
        JsonObject p = new JsonObject();
        JsonObject module = Refs.gridModule(Refs.device(TRACK, 0, "Poly Grid"), MODULE, "F: lp");
        p.add("target", Refs.parameter(module, parameterId, null));
        return p;
    }

    static JsonObject nestedGet() {
        JsonObject chain = Refs.chain(Refs.device(TRACK, 0, "Poly Grid"), MODULE, "Layer 1");
        JsonObject p = new JsonObject();
        p.add("target", Refs.parameter(Refs.device(chain, 0, "Filter"), "CONTENTS/PID1", "Frequency"));
        return p;
    }

    static JsonObject set(JsonArray points) {
        JsonObject p = get("volume");
        JsonObject expect = new JsonObject();
        expect.addProperty("pointsHash", EMPTY);
        p.add("expect", expect);
        p.add("points", points);
        return p;
    }

    static JsonObject range(double start, double end) {
        JsonObject r = new JsonObject();
        r.addProperty("start", start);
        r.addProperty("end", end);
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
        assertRpcError("invalid_params", () -> accepted(m, p));
    }

    static void accepted(String m, JsonObject p) {
        AutomationModule.validateParams(m, HostBridge.checkSchema(HostFixture.SPECS, m, p));
    }

    static final class Fixture {
        final HostFixture host = new HostFixture();
        final ScheduledTasks queue = host.tasks;
        final AutomationFake lane = new AutomationFake(queue::add);
        final AutomationModule module;

        Fixture(boolean playing) {
            host.tracks[0].child("channelId").emit(ID);
            host.transport.child("isPlaying").emit(playing);
            module = host.services.automation(lane);
        }

        void observeDevice(String name) {
            FakeHost bank = host.tracks[0].child("createDeviceBank");
            FakeHost device = bank.item(0);
            device.child("exists").emit(true);
            device.child("position").emit(0);
            device.child("deviceType").emit("Instrument");
            bank.child("itemCount").emit(1);
            device.child("name").emit(name);
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
