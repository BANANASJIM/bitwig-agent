package dev.bitwigagent;

import static dev.bitwigagent.DeviceFixture.*;
import static dev.bitwigagent.RpcAssert.*;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import dev.bitwigagent.core.Refs;
import java.util.*;
import org.junit.jupiter.api.Test;

/** devices.insert/delete/move against chains that render the host's observer traffic; never native evidence. */
public final class DeviceChainModuleTest {
    private static final String NATIVE = "11111111-2222-4333-8444-555555555555";

    private static JsonObject insert(JsonObject parent, String kind, String id) {
        return params("parent", parent, "kind", kind, "id", id);
    }

    private static JsonObject insertAt(JsonObject parent, Object position) {
        JsonObject p = insert(parent, "bitwig", NATIVE);
        p.add(
                "position",
                position instanceof JsonElement ? (JsonElement) position : new JsonPrimitive((String) position));
        return p;
    }

    private static JsonObject before(JsonObject ref) {
        return params("before", ref);
    }

    private static JsonObject after(JsonObject ref) {
        return params("after", ref);
    }

    private static JsonObject file(JsonObject parent, String path) {
        return params("parent", parent, "kind", "file", "path", path);
    }

    private static JsonObject delete(JsonObject ref) {
        return params("target", ref);
    }

    private static JsonObject move(JsonObject ref, Object position) {
        return params(
                "target",
                ref,
                "to",
                params(
                        "position",
                        position instanceof JsonElement
                                ? (JsonElement) position
                                : new JsonPrimitive((String) position)));
    }

    private static JsonObject item(JsonObject result, String array, int index) {
        return result.getAsJsonArray(array).get(index).getAsJsonObject();
    }

    private static DeviceFixture abc() {
        return DeviceFixture.chain("A", "B", "C");
    }

    private static DeviceFixture racks(DeviceFixture.Rack[] out, String... padDevices) {
        DeviceFixture f = new DeviceFixture();
        f.top.seed("Comp", "Rack");
        DeviceFixture.Rack rack = f.rack(1);
        rack.layer(0, LAYER_A, "Pad", padDevices);
        rack.layer(1, LAYER_B, "Lead", "Chorus");
        f.start();
        out[0] = rack;
        return f;
    }

    @Test
    void invalidShapesRejectBeforeSchedulingOrMutating() {
        DeviceFixture f = DeviceFixture.twins();
        DeviceChainModule module = f.chains();
        List<JsonObject> bad = new ArrayList<>();
        bad.add(insert(f.trackRef, "bitwig", "1-1-1-1-1"));
        bad.add(insert(f.trackRef, "vst2", "2147483648"));
        bad.add(insert(f.trackRef, "vst2", "1.5"));
        bad.add(insert(f.trackRef, "vst3", "1234"));
        bad.add(insert(f.trackRef, "clap", "plugin id"));
        bad.add(insert(f.trackRef, "shell", "echo hi"));
        for (String path : List.of("relative.bwpreset", "/tmp/../bad.bwpreset", "/tmp/bad\nfile.bwpreset"))
            bad.add(file(f.trackRef, path));
        JsonObject both = file(f.trackRef, TestPaths.absolute("/tmp/device.bwpreset"));
        both.addProperty("id", NATIVE);
        bad.add(both);
        bad.add(insert(f.twin(0), "bitwig", NATIVE));
        bad.add(insertAt(f.trackRef, "sideways"));
        bad.add(insertAt(f.trackRef, new JsonObject()));
        JsonObject twoAnchors = before(f.twin(0));
        twoAnchors.add("after", f.twin(1));
        bad.add(insertAt(f.trackRef, twoAnchors));
        JsonObject counted = insert(f.trackRef, "bitwig", NATIVE);
        counted.addProperty("expectedDeviceCount", 2);
        bad.add(counted);
        for (JsonObject p : bad) assertRpcError("invalid_params", f.submit(module, "devices.insert", p));
        assertRpcError("invalid_params", f.submit(module, "devices.delete", delete(f.trackRef)));
        assertRpcError("invalid_params", f.submit(module, "devices.move", params("target", f.twin(0))));
        assertRpcError("invalid_params", f.submit(module, "chains.list", params("parent", f.trackRef)));
        assertTrue(f.host.tasks.isEmpty() && f.top.insertions == 0 && f.top.deletions == 0, "nothing was scheduled");
        assertInvalidParams(() -> DeviceChainModule.validateInsertion(insert(f.trackRef, "shell", "echo hi")));
        assertInvalidParams(() -> DeviceChainModule.validateInsertion(params("kind", "bitwig")));
        assertInvalidParams(
                () -> DeviceChainModule.validateInsertion(params("kind", "file", "path", "/a/b", "id", NATIVE)));
    }

    @Test
    void anInsertionAppendsAndReportsTheObservedDelta() {
        DeviceFixture f = DeviceFixture.twins();
        DeviceChainModule module = f.chains();
        JsonObject result = f.call(module, "devices.insert", insert(f.trackRef, "bitwig", NATIVE));
        assertEquals("applied", result.get("outcome").getAsString());
        assertEquals(1, f.top.insertions);
        assertEquals(
                UUID.fromString(NATIVE), f.top.lastArgument, "the native id reaches the public insertion API once");
        assertEquals(2, result.get("beforeCount").getAsInt());
        assertEquals(3, result.get("afterCount").getAsInt());
        assertEquals(2, result.getAsJsonObject("inserted").get("deviceIndex").getAsInt(), "the new final slot");
        assertEquals(f.device(2, "Inserted"), result.getAsJsonObject("ref"));
        HostFixture.REF_SCHEMA.apply(result.getAsJsonObject("ref"));
        assertEquals(
                "observed_count_increment_and_unchanged_prefix",
                result.get("verification").getAsString());
        assertEquals(2, result.getAsJsonArray("before").size());
        assertEquals(3, result.getAsJsonArray("after").size());
        assertFalse(result.get("requestedIdentifierVerified").getAsBoolean(), "name and type are not proof of the id");
        assertEquals(f.trackRef, result.getAsJsonObject("parent"));

        DeviceFixture delayed = DeviceFixture.twins();
        delayed.top.insertMode = "countOnly";
        HostOperation pending =
                delayed.submit(delayed.chains(), "devices.insert", insert(delayed.trackRef, "bitwig", NATIVE));
        delayed.host.next();
        assertFalse(pending.result.isDone(), "count and existence alone cannot relabel an empty slot as the device");
        delayed.devices[2].child("name").emit("Fresh inserted name");
        delayed.host.next();
        assertEquals(
                "Fresh inserted name",
                pending.result.join().getAsJsonObject("inserted").get("name").getAsString());
    }

    @Test
    void everyKindPassesItsExactArgumentToItsOwnInsertionMethod() {
        String[][] cases = {
            {"bitwig", NATIVE, "insertBitwigDevice"},
            {"vst2", "-123456", "insertVST2Device"},
            {"vst3", "00112233445566778899AABBCCDDEEFF", "insertVST3Device"},
            {"clap", "org.example.effect", "insertCLAPDevice"}
        };
        for (String[] item : cases) {
            DeviceFixture f = DeviceFixture.twins();
            f.call(f.chains(), "devices.insert", insert(f.trackRef, item[0], item[1]));
            assertEquals(item[2], f.top.lastInsertion, "public insertion method for " + item[0]);
            assertEquals(
                    item[1], f.top.lastArgument.toString(), "the identifier is passed without guessing or expansion");
        }
        DeviceFixture f = DeviceFixture.twins();
        String literal = TestPaths.absolute("/tmp/Device $(not-a-command).bwpreset");
        f.call(f.chains(), "devices.insert", file(f.trackRef, literal));
        assertEquals("insertFile", f.top.lastInsertion);
        assertEquals(literal, f.top.lastArgument, "a file path is literal host API input, never a shell command");
    }

    @Test
    void ambiguousOrUnobservedInsertionsStayUnknownAndAreNeverRetried() {
        for (String mode : List.of("double", "prefix")) {
            DeviceFixture f = DeviceFixture.twins();
            f.top.insertMode = mode;
            JsonObject result = f.call(f.chains(), "devices.insert", insert(f.trackRef, "bitwig", NATIVE));
            assertEquals("unknown", result.get("outcome").getAsString());
            assertEquals("readback_mismatch", result.get("reason").getAsString(), "concurrent chain edits: " + mode);
            assertTrue(result.get("inserted").isJsonNull(), "an ambiguous result never labels a device as inserted");
            assertFalse(result.has("ref"));
            assertEquals(1, f.top.insertions);
        }
        DeviceFixture f = DeviceFixture.twins();
        f.top.insertMode = "none";
        HostOperation op =
                f.run(f.chains(), "devices.insert", insert(f.trackRef, "bitwig", NATIVE), f.host.tasks.after(20));
        JsonObject result = assertUnknown("not_observed", op);
        assertTrue(result.get("inserted").isJsonNull());
        assertEquals(1, f.top.insertions, "a no-op is reported honestly and never retried");

        DeviceFixture shifted = DeviceFixture.twins();
        shifted.bank.child("scrollPosition").emit(1);
        assertRpcError(
                "not_ready",
                shifted.run(shifted.chains(), "devices.insert", insert(shifted.trackRef, "bitwig", NATIVE)));
        assertEquals(
                0, shifted.top.insertions, "a scrolled window cannot silently append without observing the prefix");
    }

    @Test
    void positionedInsertionShiftsTheSuffix() {
        DeviceFixture f = abc();
        JsonObject result = f.call(f.chains(), "devices.insert", insertAt(f.trackRef, before(f.device(1, "B"))));
        assertEquals(List.of("before:1:insertBitwigDevice"), f.top.points, "before anchors on the named device only");
        assertEquals(List.of("A", "Inserted", "B", "C"), f.top.names());
        assertEquals(1, result.getAsJsonObject("inserted").get("deviceIndex").getAsInt());
        assertEquals(f.device(1, "Inserted"), result.getAsJsonObject("ref"));
        assertEquals(
                "observed_count_increment_shifted_suffix",
                result.get("verification").getAsString());
        assertEquals("applied", result.get("outcome").getAsString());
        assertEquals(4, result.getAsJsonArray("after").size(), "the whole shifted chain is reported");

        for (Object[] c : new Object[][] {{"start", "start:-1", 0}, {"after", "after:2", 3}}) {
            DeviceFixture g = abc();
            Object position = c[0].equals("start") ? "start" : after(g.device(2, "C"));
            JsonObject r = g.call(g.chains(), "devices.insert", insertAt(g.trackRef, position));
            assertEquals(1, g.top.points.size());
            assertTrue(g.top.points.get(0).startsWith(c[1] + ":insertBitwigDevice"), g.top.points.toString());
            assertEquals(c[2], r.getAsJsonObject("inserted").get("deviceIndex").getAsInt(), c[0] + " final slot");
        }

        DeviceFixture end = DeviceFixture.chain("A", "B");
        JsonObject explicit = end.call(end.chains(), "devices.insert", insertAt(end.trackRef, "end"));
        assertEquals(
                "observed_count_increment_and_unchanged_prefix",
                explicit.get("verification").getAsString());
        assertEquals(1, end.top.insertions);
        assertTrue(end.top.points.isEmpty(), "an explicit end is the end-of-chain insertion point");

        DeviceFixture guards = abc();
        RpcException renamed = assertRpcError(
                "target_changed",
                guards.run(
                        guards.chains(), "devices.insert", insertAt(guards.trackRef, before(guards.device(1, "A")))));
        assertEquals(guards.device(1, "B"), renamed.current, "an anchor is compared by name");
        assertRpcError(
                "target_not_found",
                guards.run(guards.chains(), "devices.insert", insertAt(guards.trackRef, after(guards.device(3, "D")))));
        assertTrue(guards.top.points.isEmpty(), "a wrong anchor never reaches the host");

        DeviceFixture none = DeviceFixture.chain("A", "B");
        none.top.insertMode = "none";
        HostOperation missed =
                none.run(none.chains(), "devices.insert", insertAt(none.trackRef, "start"), none.host.tasks.after(20));
        JsonObject unknown = assertUnknown("not_observed", missed);
        assertTrue(unknown.get("inserted").isJsonNull());
        assertEquals(1, none.top.points.size(), "an unobserved positioned insert is not retried");
    }

    @Test
    void deletionComparesTheNameAndObservesTheShift() {
        DeviceFixture f = abc();
        JsonObject result = f.call(f.chains(), "devices.delete", delete(f.device(1, "B")));
        assertEquals(1, f.top.deletions);
        assertEquals(List.of("A", "C"), f.top.names(), "the delete removes exactly the named device");
        assertEquals("applied", result.get("outcome").getAsString());
        assertEquals(
                "observed_count_decrement_and_shifted_suffix",
                result.get("verification").getAsString());
        assertEquals(3, result.get("beforeCount").getAsInt());
        assertEquals(2, result.get("afterCount").getAsInt());
        assertEquals("B", result.getAsJsonObject("deleted").get("name").getAsString());
        assertEquals(1, result.getAsJsonObject("deleted").get("deviceIndex").getAsInt());
        assertEquals(2, result.getAsJsonArray("after").size());
        RpcException stale =
                assertRpcError("target_changed", f.run(f.chains(), "devices.delete", delete(f.device(1, "B"))));
        assertEquals(f.device(1, "C"), stale.current);
        assertRpcError("target_not_found", f.run(f.chains(), "devices.delete", delete(f.device(5, "B"))));
        assertEquals(1, f.top.deletions, "a stale name or an absent index never deletes");

        DeviceFixture last = DeviceFixture.chain("A", "B");
        assertEquals(
                "applied",
                last.call(last.chains(), "devices.delete", delete(last.device(1, "B")))
                        .get("outcome")
                        .getAsString());
        assertEquals(List.of("A"), last.top.names(), "deleting the last device observes the empty tail");

        DeviceFixture wide = DeviceFixture.chain("A", "B");
        wide.bank.child("itemCount").emit(17);
        assertRpcError("outside_window", wide.run(wide.chains(), "devices.delete", delete(wide.device(0, "A"))));
        assertEquals(0, wide.top.deletions, "a chain beyond the 16-device window is never edited blind");

        DeviceFixture unsafe = DeviceFixture.chain("A", "B");
        unsafe.host.transport.child("isArrangerRecordEnabled").emit(true);
        assertRpcError("unsafe_state", unsafe.run(unsafe.chains(), "devices.delete", delete(unsafe.device(0, "A"))));
        assertEquals(0, unsafe.top.deletions, "recording blocks deletion");

        DeviceFixture silent = DeviceFixture.chain("A", "B");
        silent.top.deleteMode = "none";
        HostOperation op = silent.run(
                silent.chains(), "devices.delete", delete(silent.device(0, "A")), silent.host.tasks.after(20));
        assertUnknown("not_observed", op);
        assertEquals(1, silent.top.deletions, "an unobserved deletion is reported and never retried");

        DeviceFixture changed = abc();
        changed.top.deleteMode = "prefix";
        assertEquals(
                "readback_mismatch",
                changed.call(changed.chains(), "devices.delete", delete(changed.device(2, "C")))
                        .get("reason")
                        .getAsString(),
                "a changed prefix during deletion is ambiguous");
    }

    @Test
    void aMoveObservesTheExpectedArrangement() {
        DeviceFixture f = DeviceFixture.chain("A", "B", "C", "D");
        JsonObject result = f.call(f.chains(), "devices.move", move(f.device(0, "A"), after(f.device(2, "C"))));
        assertEquals(List.of("after:2:moveDevices"), f.top.points, "moving later lands after the anchor");
        assertEquals(List.of("B", "C", "A", "D"), f.top.names());
        assertEquals("observed_expected_arrangement", result.get("verification").getAsString());
        assertEquals(2, result.getAsJsonObject("moved").get("toIndex").getAsInt());
        assertEquals(0, result.getAsJsonObject("moved").get("deviceIndex").getAsInt());
        assertEquals(4, result.get("beforeCount").getAsInt());
        assertEquals(4, result.get("afterCount").getAsInt());
        assertEquals(f.device(2, "A"), result.getAsJsonObject("ref"), "the ref is the device's new location");
        HostFixture.REF_SCHEMA.apply(result.getAsJsonObject("ref"));

        f.call(f.chains(), "devices.move", move(f.device(3, "D"), before(f.device(1, "C"))));
        assertEquals("before:1:moveDevices", f.top.points.get(1), "moving earlier lands before the anchor");
        assertEquals(List.of("B", "D", "C", "A"), f.top.names());
        f.call(f.chains(), "devices.move", move(f.device(0, "B"), "end"));
        assertEquals(List.of("D", "C", "A", "B"), f.top.names());
        assertEquals("after:3:moveDevices", f.top.points.get(2), "the end is after the last device");
        f.call(f.chains(), "devices.move", move(f.device(3, "B"), "start"));
        assertEquals(List.of("B", "D", "C", "A"), f.top.names());
        assertEquals("before:0:moveDevices", f.top.points.get(3));

        JsonObject same = f.call(f.chains(), "devices.move", move(f.device(0, "B"), "start"));
        assertEquals("already_matched", same.get("outcome").getAsString());
        assertEquals(f.device(0, "B"), same.getAsJsonObject("ref"));
        assertEquals(
                "already_matched",
                f.call(f.chains(), "devices.move", move(f.device(0, "B"), before(f.device(1, "D"))))
                        .get("outcome")
                        .getAsString(),
                "just before its successor is where it already is");
        RpcException wrongName = assertRpcError(
                "target_changed", f.run(f.chains(), "devices.move", move(f.device(0, "Z"), after(f.device(1, "D")))));
        assertEquals(f.device(0, "B"), wrongName.current);
        assertRpcError(
                "target_not_found", f.run(f.chains(), "devices.move", move(f.device(0, "B"), after(f.device(4, "X")))));
        assertEquals(4, f.top.points.size(), "rejected and already-matched moves never reach the host");
    }

    @Test
    void identicalNeighboursCannotProveAMove() {
        DeviceFixture twins = DeviceFixture.twins();
        JsonObject r = twins.call(twins.chains(), "devices.move", move(twins.twin(0), after(twins.twin(1))));
        assertEquals("unknown", r.get("outcome").getAsString());
        assertEquals("readback_mismatch", r.get("reason").getAsString());
        assertTrue(r.has("hint"));
        assertEquals(1, twins.top.points.size());

        DeviceFixture silent = abc();
        silent.top.moveMode = "none";
        HostOperation op = silent.run(
                silent.chains(),
                "devices.move",
                move(silent.device(0, "A"), after(silent.device(1, "B"))),
                silent.host.tasks.after(20));
        assertUnknown("not_observed", op);
        assertEquals(1, silent.top.points.size(), "an unobserved move is reported and not retried");
    }

    @Test
    void movesAreRefusedWhileRecordingOrBeyondTheWindowOrAcrossParents() {
        DeviceFixture unsafe = abc();
        unsafe.host.transport.child("isArrangerRecordEnabled").emit(true);
        assertRpcError("unsafe_state", unsafe.run(unsafe.chains(), "devices.move", move(unsafe.device(0, "A"), "end")));
        assertTrue(unsafe.top.points.isEmpty());

        DeviceFixture wide = abc();
        wide.bank.child("itemCount").emit(17);
        assertRpcError("outside_window", wide.run(wide.chains(), "devices.move", move(wide.device(0, "A"), "end")));

        DeviceFixture f = abc();
        JsonObject elsewhere = move(f.device(0, "A"), "end");
        elsewhere.getAsJsonObject("to").add("parent", Refs.track(HostFixture.B, "Track 1"));
        assertRpcError("unsupported_scope", f.run(f.chains(), "devices.move", elsewhere));
        JsonObject own = move(f.device(0, "A"), "end");
        own.getAsJsonObject("to").add("parent", f.trackRef);
        assertEquals(
                "applied",
                f.call(f.chains(), "devices.move", own).get("outcome").getAsString());
        assertEquals(List.of("B", "C", "A"), f.top.names());
    }

    @Test
    void onlyOneChainEditAwaitsObservationsAtATime() {
        DeviceFixture f = abc();
        f.top.insertMode = "none";
        HostOperation first = f.submit(f.chains(), "devices.insert", insert(f.trackRef, "bitwig", NATIVE));
        f.host.next();
        assertFalse(first.result.isDone());
        assertRpcError("busy", f.run(f.chains(), "devices.delete", delete(f.device(0, "A"))));
        assertEquals(0, f.top.deletions);
    }

    @Test
    void aChainEditsItsOwnTwoDeviceWindow() {
        DeviceFixture.Rack[] out = new DeviceFixture.Rack[1];
        DeviceFixture f = racks(out, "Delay", "Reverb");
        DeviceFixture.Rack rack = out[0];
        DeviceFixture.Chain pad = rack.chains.get(0), lead = rack.chains.get(1);
        JsonObject lone = f.call(f.chains(), "devices.insert", insert(rack.chainRef(1), "clap", "org.example.fx"));
        assertEquals(rack.chainRef(1), lone.getAsJsonObject("parent"));
        assertEquals(1, lone.getAsJsonObject("inserted").get("deviceIndex").getAsInt());
        assertEquals(Refs.device(rack.chainRef(1), 1, "Inserted"), lone.getAsJsonObject("ref"));
        HostFixture.REF_SCHEMA.apply(lone.getAsJsonObject("ref"));
        assertEquals(
                "observed_count_increment_and_unchanged_prefix",
                lone.get("verification").getAsString());
        assertEquals(2, lone.get("afterCount").getAsInt());
        assertEquals("insertCLAPDevice", lead.lastInsertion);
        assertEquals(0, pad.insertions, "the other chain is untouched");

        JsonObject start = f.call(f.chains(), "devices.insert", insertAt(rack.chainRef(0), "start"));
        assertEquals(List.of("start:-1:insertBitwigDevice"), pad.points);
        assertEquals(List.of("Inserted", "Delay", "Reverb"), pad.names());
        assertEquals(0, start.getAsJsonObject("inserted").get("deviceIndex").getAsInt());
        assertEquals(
                "observed_count_increment_shifted_suffix",
                start.get("verification").getAsString());
        assertEquals(3, start.get("afterCount").getAsInt(), "the count says how many there are beyond the window");
        assertEquals(2, start.getAsJsonArray("after").size(), "the window shows the first two");

        JsonObject beyond = f.call(f.chains(), "devices.insert", insertAt(rack.chainRef(0), "end"));
        assertEquals("applied", beyond.get("outcome").getAsString());
        assertEquals(
                "observed_count_increment_beyond_window",
                beyond.get("verification").getAsString());
        assertTrue(beyond.get("inserted").isJsonNull(), "a device beyond the window cannot be named");
        assertFalse(beyond.has("ref"));
        assertEquals(4, beyond.get("afterCount").getAsInt());
    }

    @Test
    void aChainDeviceIsDeletedAndMovedByItsRefAndNeverToAnotherParent() {
        DeviceFixture.Rack[] out = new DeviceFixture.Rack[1];
        DeviceFixture f = racks(out, "Delay", "Reverb");
        DeviceFixture.Rack rack = out[0];
        DeviceFixture.Chain pad = rack.chains.get(0);
        JsonObject moved = f.call(f.chains(), "devices.move", move(rack.deviceRef(0, 1), before(rack.deviceRef(0, 0))));
        assertEquals(List.of("before:0:moveDevices"), pad.points);
        assertEquals(List.of("Reverb", "Delay"), pad.names());
        assertEquals("observed_expected_arrangement", moved.get("verification").getAsString());
        assertEquals(Refs.device(rack.chainRef(0), 0, "Reverb"), moved.getAsJsonObject("ref"));
        assertEquals(
                "already_matched",
                f.call(f.chains(), "devices.move", move(Refs.device(rack.chainRef(0), 0, "Reverb"), "start"))
                        .get("outcome")
                        .getAsString());
        assertRpcError(
                "unsupported_scope",
                f.run(
                        f.chains(),
                        "devices.move",
                        params(
                                "target",
                                Refs.device(rack.chainRef(0), 0, "Reverb"),
                                "to",
                                params("parent", f.trackRef, "position", "end"))));
        assertRpcError(
                "unsupported_scope",
                f.run(
                        f.chains(),
                        "devices.move",
                        params(
                                "target",
                                f.device(0, "Comp"),
                                "to",
                                params("parent", rack.chainRef(0), "position", "end"))));

        JsonObject deleted = f.call(f.chains(), "devices.delete", delete(Refs.device(rack.chainRef(0), 0, "Reverb")));
        assertEquals("applied", deleted.get("outcome").getAsString());
        assertEquals("Reverb", deleted.getAsJsonObject("deleted").get("name").getAsString());
        assertEquals(1, deleted.get("afterCount").getAsInt());
        assertEquals(List.of("Delay"), pad.names());
        assertEquals(
                "observed_count_decrement_and_shifted_suffix",
                deleted.get("verification").getAsString());
    }

    @Test
    void aChainLongerThanItsWindowCannotBeMoved() {
        DeviceFixture.Rack[] out = new DeviceFixture.Rack[1];
        DeviceFixture f = racks(out, "Delay", "Reverb", "Chorus");
        DeviceFixture.Rack rack = out[0];
        assertRpcError("outside_window", f.run(f.chains(), "devices.move", move(rack.deviceRef(0, 0), "end")));
        assertTrue(rack.chains.get(0).points.isEmpty());
    }

    @Test
    void aDeviceInAChainLongerThanItsWindowIsDeletedAndTheCountShrinks() {
        String[][] cases = {{"first", "0", "Delay", "Reverb,Chorus"}, {"last in window", "1", "Reverb", "Delay,Chorus"}
        };
        for (String[] c : cases) {
            DeviceFixture.Rack[] out = new DeviceFixture.Rack[1];
            DeviceFixture f = racks(out, "Delay", "Reverb", "Chorus");
            DeviceFixture.Rack rack = out[0];
            JsonObject deleted =
                    f.call(f.chains(), "devices.delete", delete(rack.deviceRef(0, Integer.parseInt(c[1]))));
            assertEquals("applied", deleted.get("outcome").getAsString(), c[0]);
            assertEquals(
                    "observed_count_decrement_and_shifted_suffix",
                    deleted.get("verification").getAsString(),
                    c[0]);
            assertEquals(3, deleted.get("beforeCount").getAsInt(), c[0]);
            assertEquals(2, deleted.get("afterCount").getAsInt(), c[0]);
            assertEquals(c[2], deleted.getAsJsonObject("deleted").get("name").getAsString(), c[0]);
            assertEquals(Arrays.asList(c[3].split(",")), rack.chains.get(0).names(), c[0]);
            assertEquals(1, rack.chains.get(0).deletions, c[0]);
        }
    }

    @Test
    void anAnchorMustBeADeviceOfTheParentBeingEdited() {
        DeviceFixture.Rack[] out = new DeviceFixture.Rack[1];
        DeviceFixture f = racks(out, "Delay", "Reverb");
        DeviceFixture.Rack rack = out[0];
        assertRpcError(
                "invalid_params",
                f.run(f.chains(), "devices.insert", insertAt(f.trackRef, before(rack.deviceRef(0, 0)))));
        assertRpcError(
                "invalid_params",
                f.run(f.chains(), "devices.insert", insertAt(rack.chainRef(1), after(rack.deviceRef(0, 0)))));
        assertRpcError(
                "invalid_params",
                f.run(f.chains(), "devices.insert", insertAt(rack.chainRef(0), before(f.device(0, "Comp")))));
        assertRpcError(
                "invalid_params",
                f.run(f.chains(), "devices.move", move(rack.deviceRef(0, 0), after(rack.deviceRef(1, 0)))));
        assertEquals(0, rack.chains.get(0).insertions + rack.chains.get(1).insertions + f.top.insertions);
    }

    @Test
    void aChainEditWhoseContextChangesAfterDispatchIsUnknown() {
        DeviceFixture.Rack[] out = new DeviceFixture.Rack[1];
        DeviceFixture f = racks(out, "Delay");
        DeviceFixture.Rack rack = out[0];
        rack.chains.get(0).insertMode = "none";
        HostOperation op = f.submit(f.chains(), "devices.insert", insert(rack.chainRef(0), "bitwig", NATIVE));
        f.host.next();
        assertFalse(op.result.isDone());
        f.devices[1].child("name").emit("Renamed Rack");
        f.host.next();
        assertUnknown("target_changed", op);
        assertEquals(1, rack.chains.get(0).insertions, "the changed context never replays the edit");

        DeviceFixture top = abc();
        top.top.insertMode = "none";
        HostOperation rooted = top.submit(top.chains(), "devices.insert", insert(top.trackRef, "bitwig", NATIVE));
        top.host.next();
        assertFalse(rooted.result.isDone());
        top.bank.child("scrollPosition").emit(1);
        top.host.next();
        assertUnknown("target_changed", rooted);
        assertEquals(1, top.top.insertions);
    }

    /** The host reorders the two layers: each keeps its UUID, name and devices but changes slot. */
    private static void swapLayers(DeviceFixture.Rack rack) {
        FakeHost first = rack.layerNode(0), second = rack.layerNode(1);
        List<String[]> padDevices = new ArrayList<>(rack.chains.get(0).entries);
        List<String[]> leadDevices = new ArrayList<>(rack.chains.get(1).entries);
        first.child("channelId").emit(LAYER_B);
        second.child("channelId").emit(LAYER_A);
        first.child("name").emit("Lead");
        second.child("name").emit("Pad");
        rack.chains.get(0).render(leadDevices);
        rack.chains.get(1).render(padDevices);
    }

    @Test
    void aChainRefIsLocatedByItsLayerUuidSoEditsFollowAReorderedLayer() {
        DeviceFixture.Rack[] out = new DeviceFixture.Rack[1];
        DeviceFixture f = racks(out, "Delay", "Reverb");
        DeviceFixture.Rack rack = out[0];
        JsonObject pad = rack.chainRef(0);
        swapLayers(rack);
        JsonObject result = f.call(f.chains(), "devices.insert", insert(pad, "clap", "org.example.fx"));
        assertEquals(1, rack.chains.get(1).insertions, "the edit went to the layer that now holds the Pad chain");
        assertEquals(0, rack.chains.get(0).insertions);
        assertEquals(pad, result.getAsJsonObject("parent"));
        assertEquals("applied", result.get("outcome").getAsString());
        assertEquals(3, result.get("afterCount").getAsInt());
    }

    @Test
    void aDeviceOfAReboundChainIsUnavailableNotStale() {
        DeviceFixture.Rack[] out = new DeviceFixture.Rack[1];
        DeviceFixture f = racks(out, "Delay", "Reverb");
        DeviceFixture.Rack rack = out[0];
        JsonObject delay = rack.deviceRef(0, 0);
        swapLayers(rack);
        RpcException unavailable = assertRpcError("not_ready", f.run(f.chains(), "devices.delete", delete(delay)));
        assertTrue(
                unavailable.getMessage().contains("extension reload"),
                "reading again cannot help: " + unavailable.getMessage());
        assertRpcError("not_ready", f.run(f.chains(), "devices.move", move(delay, "end")));
        assertRpcError("not_ready", f.run(f.chains(), "devices.insert", insertAt(rack.chainRef(0), before(delay))));
        assertEquals(0, rack.chains.get(0).deletions + rack.chains.get(1).deletions);
        JsonObject listed = f.call(f.chains(), "chains.list", params("parent", rack.ref()));
        for (JsonElement chain : listed.getAsJsonArray("chains")) {
            JsonObject row = chain.getAsJsonObject();
            assertTrue(row.has("ref"), "the chain itself stays addressable");
            assertEquals("ready", row.get("readiness").getAsString());
        }
        assertFalse(
                item(listed, "chains", 1).get("devicesAvailable").getAsBoolean(),
                "the Pad chain's devices are rebound");
        JsonObject devices = f.call(f.list(), "devices.list", params("parent", rack.chainRef(0)));
        for (JsonElement device : devices.getAsJsonArray("devices"))
            assertFalse(device.getAsJsonObject().get("available").getAsBoolean());
    }

    @Test
    void aChainWhoseLayerIdIsAmbiguousIsNotEdited() {
        DeviceFixture.Rack[] out = new DeviceFixture.Rack[1];
        DeviceFixture f = racks(out, "Delay");
        DeviceFixture.Rack rack = out[0];
        rack.layerNode(1).child("channelId").emit(LAYER_A);
        assertRpcError("not_ready", f.run(f.chains(), "devices.insert", insert(rack.chainRef(0), "bitwig", NATIVE)));
        assertEquals(0, rack.chains.get(0).insertions + rack.chains.get(1).insertions);
    }
}
