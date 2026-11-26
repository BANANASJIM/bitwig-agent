package dev.bitwigagent;

import static dev.bitwigagent.DeviceFixture.*;
import static dev.bitwigagent.RpcAssert.*;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import dev.bitwigagent.core.Refs;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Walking from a verified root device to chains and the devices in them, and proving edits by object identity. */
public final class NativeChainsTest {
    private static final String LAYER_D = "00000000-0000-4000-8000-0000000000dd";

    /** Comp, then a rack with a Pad chain (Delay, a nested layer device Split, Reverb) and a Lead chain (Chorus). */
    private static final class Fixture {
        final DeviceFixture f = new DeviceFixture();
        final DeviceFixture.Rack rack;
        final FakeChains.Node split = FakeChains.device("Split").layered();
        final JsonObject splitRef, inner;

        Fixture() {
            f.top.seed("Comp", "Rack");
            rack = f.rack(1);
            rack.layer(0, LAYER_A, "Pad", "Delay", "Reverb");
            rack.layer(1, LAYER_B, "Lead", "Chorus");
            rack.chains.get(0).devices.add(1, split);
            split.chain(LAYER_D, "Low", FakeChains.device("Bitcrush"), FakeChains.device("Tool"));
            f.start();
            splitRef = rack.deviceRef(0, 1);
            inner = Refs.chain(splitRef, LAYER_D, "Low");
        }

        JsonObject run(JsonObject ref, java.util.function.Function<NativeChains.Walk, JsonObject> work) {
            HostOperation op = new HostOperation(Long.MAX_VALUE);
            f.services.scheduler.schedule(op, () -> f.services.chains.run(op, ref, work), 0);
            return f.finish(op);
        }

        HostOperation start(JsonObject ref, java.util.function.Function<NativeChains.Walk, JsonObject> work) {
            HostOperation op = new HostOperation(Long.MAX_VALUE);
            f.services.scheduler.schedule(op, () -> f.services.chains.run(op, ref, work), 0);
            while (!op.result.isDone()) f.host.next();
            return op;
        }

        JsonObject call(HostModule module, String method, JsonObject params) {
            return f.finish(f.submit(module, method, params));
        }
    }

    private static JsonObject name(String value) {
        JsonObject result = new JsonObject();
        result.addProperty("name", value);
        return result;
    }

    @Test
    void aChainUnderADeviceInsideAChainIsWalkedTheSameWay() {
        Fixture x = new Fixture();
        JsonObject tool = Refs.device(x.inner, 1, "Tool");
        JsonObject found = x.run(tool, walk -> name(walk.device(tool, true).name));
        assertEquals("Tool", found.get("name").getAsString());
        HostFixture.REF_SCHEMA.apply(tool);
        JsonObject listed = x.call(x.f.list(), "devices.list", params("parent", x.inner));
        assertEquals(
                tool, listed.getAsJsonArray("devices").get(1).getAsJsonObject().get("ref"));
        JsonObject params = x.call(x.f.parameters(), "parameters.list", params("parent", tool));
        assertEquals(tool, params.getAsJsonObject("parent"));
        x.split.name = "Splitter";
        RpcException moved =
                assertRpcError("target_changed", x.start(tool, walk -> name(walk.device(tool, false).name)));
        assertEquals(
                Refs.device(x.rack.chainRef(0), 1, "Splitter"),
                moved.current,
                "a renamed ancestor device is another device: its whole subtree is re-read");
    }

    @Test
    void aChainNameCountsOnlyWhenTheChainIsTheTarget() {
        Fixture x = new Fixture();
        JsonObject renamed = Refs.chain(x.rack.ref(), LAYER_A, "Old Pad");
        assertEquals(
                "Pad",
                x.run(
                                renamed,
                                walk -> name(walk.chain(renamed, false)
                                        .ref
                                        .get("name")
                                        .getAsString()))
                        .get("name")
                        .getAsString());
        RpcException target = assertRpcError(
                "target_changed",
                x.start(renamed, walk -> name(walk.chain(renamed, true).ref.toString())));
        assertEquals(x.rack.chainRef(0), target.current);
    }

    @Test
    void aDeviceUnderAnotherRootIsNotPartOfThisWalk() {
        Fixture x = new Fixture();
        JsonObject delay = x.rack.deviceRef(0, 0);
        JsonObject elsewhere = Refs.device(Refs.chain(x.f.device(0, "Comp"), LAYER_A, "Pad"), 0, "Delay");
        assertRpcError("invalid_params", x.start(delay, walk -> name(walk.device(elsewhere, false).name)));
        assertRpcError(
                "invalid_params",
                x.f.finishOrError(x.f.submit(
                        x.f.chains(),
                        "devices.insert",
                        params(
                                "parent",
                                x.rack.chainRef(0),
                                "kind",
                                "bitwig",
                                "id",
                                "11111111-2222-4333-8444-555555555555",
                                "position",
                                params("before", elsewhere)))));
    }

    @Test
    void anEditThatTheChainDoesNotShowAsAskedIsUnknownAndNeverRetried() {
        Fixture x = new Fixture();
        x.f.natives.onEdit = () -> x.rack.chains.get(0).devices.add(FakeChains.device("Intruder"));
        JsonObject deleted = x.call(x.f.chains(), "devices.delete", params("target", x.rack.deviceRef(0, 0)));
        assertEquals("unknown", deleted.get("outcome").getAsString());
        assertEquals("readback_mismatch", deleted.get("reason").getAsString());
        assertEquals("Delay", deleted.getAsJsonObject("deleted").get("name").getAsString());
        JsonObject moved = x.call(
                x.f.chains(),
                "devices.move",
                params("target", x.rack.deviceRef(0, 0), "to", params("position", "end")));
        assertEquals("readback_mismatch", moved.get("reason").getAsString());
        assertFalse(moved.has("ref"));
        x.f.natives.onEdit = () -> x.rack.chains.get(1).devices.add(0, FakeChains.device("Intruder"));
        JsonObject inserted = x.call(
                x.f.chains(),
                "devices.insert",
                params("parent", x.rack.chainRef(1), "kind", "clap", "id", "org.example.fx"));
        assertEquals("unknown", inserted.get("outcome").getAsString());
        assertEquals("readback_mismatch", inserted.get("reason").getAsString());
        assertTrue(inserted.get("inserted").isJsonNull());
    }

    @Test
    void aDeleteOrMoveTheHostNeverCarriesOutIsUnknownNotObserved() {
        Fixture x = new Fixture();
        x.f.natives.ignoreEdits = true;
        HostOperation delete = x.f.submit(
                x.f.chains(), "devices.delete", params("target", x.rack.deviceRef(0, 0)), x.f.host.tasks.after(5000));
        JsonObject deleted = x.f.finish(delete);
        assertEquals("not_observed", deleted.get("reason").getAsString());
        assertEquals(3, deleted.get("afterCount").getAsInt());
        HostOperation move = x.f.submit(
                x.f.chains(),
                "devices.move",
                params("target", x.rack.deviceRef(0, 0), "to", params("position", "end")),
                x.f.host.tasks.after(5000));
        JsonObject moved = x.f.finish(move);
        assertEquals("not_observed", moved.get("reason").getAsString());
        assertEquals(0, moved.getAsJsonObject("moved").get("deviceIndex").getAsInt(), "moved names the device");
    }

    @Test
    void deletingAChainsOnlyDeviceLeavesTheEmptyChainListed() {
        Fixture x = new Fixture();
        JsonObject deleted = x.call(x.f.chains(), "devices.delete", params("target", x.rack.deviceRef(1, 0)));
        assertEquals("applied", deleted.get("outcome").getAsString(), "a pad keeps its chain once emptied");
        assertEquals(0, deleted.get("afterCount").getAsInt());
        JsonObject listed = x.call(x.f.chains(), "chains.list", params("parent", x.rack.ref()));
        assertEquals(
                0,
                listed.getAsJsonArray("chains")
                        .get(1)
                        .getAsJsonObject()
                        .get("deviceCount")
                        .getAsInt());
    }

    @Test
    void aBrokenBindingIsNativeUnavailableBeforeDispatchAndUnknownAfter() {
        Fixture x = new Fixture();
        x.f.natives.broken = new IllegalStateException("descriptor drift");
        HostOperation read = x.f.submit(x.f.list(), "devices.list", params("parent", x.rack.chainRef(0)));
        assertRpcError("native_unavailable", x.f.finishOrError(read));
        x.f.natives.broken = null;
        HostOperation op = new HostOperation(Long.MAX_VALUE);
        x.f.services.scheduler.schedule(
                op,
                () -> x.f.services.chains.run(op, x.rack.chainRef(0), walk -> {
                    walk.chain(x.rack.chainRef(0), false);
                    walk.dispatch();
                    throw new IllegalStateException("after the native call");
                }),
                0);
        JsonObject unknown = x.f.finish(op);
        assertEquals("unknown", unknown.get("outcome").getAsString());
        assertEquals("native_unavailable", unknown.get("reason").getAsString());
    }

    @Test
    void aDisplayWriteWhoseParameterIsReplacedMidwayIsUnknown() {
        Fixture x = new Fixture();
        FakeChains.Node delay = x.rack.chains.get(0).devices.get(0);
        FakeChains.Param freq = delay.param("FREQ", "Frequency", .25);
        int[] shown = {0};
        freq.display = v -> {
            if (++shown[0] == 3) delay.param("FREQ", "Frequency", v);
            return String.format(Locale.ROOT, "%.0f Hz", 20 * Math.pow(1000, v));
        };
        JsonObject set = params("target", Refs.parameter(x.rack.deviceRef(0, 0), "FREQ", null));
        set.add("value", JsonParser.parseString("{\"display\":\"800 Hz\"}"));
        JsonObject result = x.call(x.f.parameters(), "parameters.set", set);
        assertEquals("unknown", result.get("outcome").getAsString());
        assertEquals("target_changed", result.get("reason").getAsString());
        JsonObject loud = params("target", Refs.parameter(x.rack.deviceRef(0, 0), "FREQ", null));
        loud.add("value", JsonParser.parseString("{\"display\":\"loud\"}"));
        assertRpcError("unsupported_unit", x.f.finishOrError(x.f.submit(x.f.parameters(), "parameters.set", loud)));
    }
}
