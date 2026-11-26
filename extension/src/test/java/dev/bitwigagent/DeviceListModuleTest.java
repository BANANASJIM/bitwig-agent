package dev.bitwigagent;

import static dev.bitwigagent.DeviceFixture.*;
import static dev.bitwigagent.RpcAssert.*;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import dev.bitwigagent.core.Refs;
import java.util.*;
import org.junit.jupiter.api.Test;

/** devices.list and chains.list against a simulated host: refs, readiness, uniqueness and windows. */
public final class DeviceListModuleTest {
    private static DeviceFixture racks(DeviceFixture.Rack[] out) {
        DeviceFixture f = new DeviceFixture();
        f.top.seed("Comp", "Rack");
        DeviceFixture.Rack rack = f.rack(1);
        rack.layer(0, LAYER_A, "Pad", "Delay", "Reverb");
        rack.layer(1, LAYER_B, "Lead", "Chorus");
        f.start();
        out[0] = rack;
        return f;
    }

    private static JsonObject list(DeviceFixture f, JsonObject parent) {
        return f.call(f.list(), "devices.list", params("parent", parent));
    }

    private static JsonObject chains(DeviceFixture f, JsonObject parent) {
        return f.call(f.chains(), "chains.list", params("parent", parent));
    }

    private static JsonObject row(JsonObject result, String array, int index) {
        return result.getAsJsonArray(array).get(index).getAsJsonObject();
    }

    @Test
    void aTrackListsItsRootDevicesWithRefsThatLocateThem() {
        DeviceFixture f = DeviceFixture.twins();
        JsonObject result = list(f, f.trackRef);
        assertEquals(f.trackRef, result.getAsJsonObject("parent"));
        assertEquals("top_level", result.get("scope").getAsString());
        assertEquals(2, result.getAsJsonArray("devices").size());
        for (int i = 0; i < 2; i++) {
            JsonObject item = row(result, "devices", i);
            assertEquals(f.twin(i), item.getAsJsonObject("ref"), "same-name devices are told apart by index");
            HostFixture.REF_SCHEMA.apply(item.getAsJsonObject("ref"));
            assertEquals("Twin", item.get("name").getAsString());
            assertEquals("audio-effect", item.get("type").getAsString());
            assertEquals(
                    item.getAsJsonObject("ref"),
                    f.services.sources.device(f.twin(i), true).ref());
        }
        JsonObject window = result.getAsJsonObject("window");
        assertEquals(0, window.get("offset").getAsInt());
        assertEquals(16, window.get("capacity").getAsInt());
        assertEquals(2, window.get("total").getAsInt());
        assertTrue(window.get("complete").getAsBoolean());
    }

    @Test
    void theTrackIsAnAncestorNotACompareTargetAndMustExist() {
        DeviceFixture f = DeviceFixture.twins();
        f.host.tracks[0].child("name").emit("Lead");
        JsonObject result = list(f, f.trackRef);
        assertEquals(Refs.track(HostFixture.A, "Lead"), result.getAsJsonObject("parent"));
        assertEquals(
                Refs.device(Refs.track(HostFixture.A, "Lead"), 0, "Twin"),
                row(result, "devices", 0).getAsJsonObject("ref"),
                "rows carry the track as it is named now");
        assertEquals(
                "Twin", f.services.sources.device(f.twin(0), true).name, "a device ref survives its track's rename");
        RpcException gone = assertRpcError(
                "target_not_found",
                f.run(f.list(), "devices.list", params("parent", Refs.track(HostFixture.C, "Gone"))));
        assertEquals("tracks.list", gone.next);
    }

    @Test
    void parentsOfTheWrongShapeAreRejectedBeforeScheduling() {
        DeviceFixture f = DeviceFixture.twins();
        assertRpcError("invalid_params", f.submit(f.list(), "devices.list", params("parent", f.twin(0))));
        assertRpcError("invalid_params", f.submit(f.list(), "devices.list", new JsonObject()));
        assertRpcError("invalid_params", f.submit(f.chains(), "chains.list", params("parent", f.trackRef)));
        assertTrue(f.host.tasks.isEmpty());
    }

    @Test
    void absentSlotsAreNotListedAndTheWindowSaysHowManyThereAre() {
        DeviceFixture f = DeviceFixture.twins();
        f.bank.child("itemCount").emit(17);
        JsonObject wide = list(f, f.trackRef).getAsJsonObject("window");
        assertEquals(17, wide.get("total").getAsInt());
        assertEquals(16, wide.get("capacity").getAsInt());
        assertFalse(wide.get("complete").getAsBoolean(), "more devices than the window is never complete");
        assertEquals(2, list(f, f.trackRef).getAsJsonArray("devices").size(), "only present devices have rows");
        f.bank.child("itemCount").emit(2);
        f.top.render(Collections.singletonList(new String[] {"Twin", "audio-effect"}));
        JsonObject one = list(f, f.trackRef);
        assertEquals(1, one.getAsJsonArray("devices").size());
        assertEquals(1, one.getAsJsonObject("window").get("total").getAsInt());
    }

    @Test
    void aChainListsItsDevicesWithRefsUnderTheChain() {
        DeviceFixture.Rack[] rack = new DeviceFixture.Rack[1];
        DeviceFixture f = racks(rack);
        JsonObject root = list(f, f.trackRef);
        assertEquals(List.of("Comp", "Rack"), names(root));
        JsonObject result = list(f, rack[0].chainRef(0));
        assertEquals(rack[0].chainRef(0), result.getAsJsonObject("parent"));
        assertEquals("chain", result.get("scope").getAsString());
        assertEquals(List.of("Delay", "Reverb"), names(result));
        for (int i = 0; i < 2; i++) {
            JsonObject ref = row(result, "devices", i).getAsJsonObject("ref");
            assertEquals(rack[0].deviceRef(0, i), ref);
            HostFixture.REF_SCHEMA.apply(ref);
            assertEquals(ref, f.services.sources.device(ref, true).ref());
        }
        JsonObject window = result.getAsJsonObject("window");
        assertEquals(2, window.get("total").getAsInt());
        assertEquals(2, window.get("capacity").getAsInt());
        assertTrue(window.get("complete").getAsBoolean());
        assertEquals("ready", window.get("readiness").getAsString());
        assertEquals(List.of("Chorus"), names(list(f, rack[0].chainRef(1))));
    }

    @Test
    void aChainShowsOnlyItsFirstTwoDevicesAndCountsTheRest() {
        DeviceFixture f = new DeviceFixture();
        f.top.seed("Rack");
        DeviceFixture.Rack rack = f.rack(0);
        rack.layer(0, LAYER_A, "Pad", "Delay", "Reverb", "Chorus");
        f.start();
        JsonObject result = list(f, rack.chainRef(0));
        assertEquals(List.of("Delay", "Reverb"), names(result));
        JsonObject window = result.getAsJsonObject("window");
        assertEquals(3, window.get("total").getAsInt());
        assertFalse(window.get("complete").getAsBoolean());
        JsonObject listed =
                chains(f, rack.ref()).getAsJsonArray("chains").get(0).getAsJsonObject();
        assertEquals(3, listed.get("deviceCount").getAsInt(), "the chain count is the total, not the window");
    }

    @Test
    void aChainRefIsTheLayerUuidSoItsParentsAreNamesNotLocations() {
        DeviceFixture.Rack[] rack = new DeviceFixture.Rack[1];
        DeviceFixture f = racks(rack);
        JsonObject stale = Refs.chain(rack[0].ref(), LAYER_A, "Old Pad");
        JsonObject result = list(f, stale);
        assertEquals(rack[0].chainRef(0), result.getAsJsonObject("parent"), "the chain's current name is reported");
        f.top.slots[1].child("name").emit("Drum Rack");
        RpcException renamed = assertRpcError(
                "target_changed", f.run(f.list(), "devices.list", params("parent", rack[0].chainRef(0))));
        assertEquals(f.device(1, "Drum Rack"), renamed.current, "a chain under a renamed device is another chain");
        RpcException other = assertRpcError(
                "target_not_found",
                f.run(
                        f.list(),
                        "devices.list",
                        params("parent", Refs.chain(f.device(1, "Drum Rack"), LAYER_C, "Pad"))));
        assertEquals("chains.list", other.next);
    }

    @Test
    void aChainRefSurvivesTheHostReorderingItsLayers() {
        DeviceFixture.Rack[] rack = new DeviceFixture.Rack[1];
        DeviceFixture f = racks(rack);
        JsonObject pad = rack[0].chainRef(0), lead = rack[0].chainRef(1);
        reorder(rack[0]);
        JsonObject listed = chains(f, rack[0].ref());
        assertEquals(lead, row(listed, "chains", 0).getAsJsonObject("ref"), "slot 0 now holds the Lead chain");
        assertEquals(pad, row(listed, "chains", 1).getAsJsonObject("ref"));
        assertEquals(List.of("Delay", "Reverb"), names(list(f, pad)), "the ref still lists the Pad chain's devices");
        assertEquals(List.of("Chorus"), names(list(f, lead)));
        assertEquals(LAYER_A, f.services.sources.chain(pad, true).id);
    }

    private static void reorder(DeviceFixture.Rack rack) {
        FakeHost first = rack.layerNode(0), second = rack.layerNode(1);
        List<String[]> pad = new ArrayList<>(rack.chains.get(0).entries),
                lead = new ArrayList<>(rack.chains.get(1).entries);
        first.child("channelId").emit(LAYER_B);
        second.child("channelId").emit(LAYER_A);
        first.child("name").emit("Lead");
        second.child("name").emit("Pad");
        rack.chains.get(0).render(lead);
        rack.chains.get(1).render(pad);
    }

    @Test
    void chainsListNamesEveryChainWithItsReadiness() {
        DeviceFixture.Rack[] rack = new DeviceFixture.Rack[1];
        DeviceFixture f = racks(rack);
        JsonObject result = chains(f, rack[0].ref());
        assertEquals(rack[0].ref(), result.getAsJsonObject("parent"));
        assertEquals("one_chain_hop", result.get("scope").getAsString());
        assertEquals(2, result.getAsJsonObject("window").get("total").getAsInt());
        assertEquals(2, result.getAsJsonArray("chains").size());
        for (int i = 0; i < 2; i++) {
            JsonObject chain = row(result, "chains", i);
            assertEquals(rack[0].chainRef(i), chain.getAsJsonObject("ref"));
            HostFixture.REF_SCHEMA.apply(chain.getAsJsonObject("ref"));
            assertEquals(i, chain.get("channelIndex").getAsInt());
            assertEquals("ready", chain.get("readiness").getAsString());
            assertEquals(i == 0 ? 2 : 1, chain.get("deviceCount").getAsInt());
            assertEquals(i == 0 ? "Pad" : "Lead", chain.get("name").getAsString());
            assertEquals(
                    chain.get("deviceCount").getAsInt(),
                    chain.getAsJsonObject("window").get("total").getAsInt());
        }
        rack[0].layerNode(1).child("exists").emit(false);
        JsonObject absent = chains(f, rack[0].ref());
        assertEquals("absent", row(absent, "chains", 1).get("readiness").getAsString());
        assertEquals("ready", row(absent, "chains", 0).get("readiness").getAsString());
    }

    @Test
    void aChainWithoutAUniqueCanonicalUuidHasNoRefAndIsNotReady() {
        DeviceFixture.Rack[] rack = new DeviceFixture.Rack[1];
        DeviceFixture f = racks(rack);
        rack[0].layerNode(1).child("channelId").emit(LAYER_A);
        JsonObject duplicated = chains(f, rack[0].ref());
        for (int i = 0; i < 2; i++) {
            assertFalse(row(duplicated, "chains", i).has("ref"), "a duplicated layer id names no chain");
            assertEquals(
                    "not_ready", row(duplicated, "chains", i).get("readiness").getAsString());
        }
        assertRpcError("not_ready", f.run(f.list(), "devices.list", params("parent", rack[0].chainRef(0))));
        rack[0].layerNode(1).child("channelId").emit("not-a-uuid");
        JsonObject malformed = chains(f, rack[0].ref());
        assertFalse(row(malformed, "chains", 1).has("ref"));
        assertFalse(row(malformed, "chains", 0).has("ref"), "an unreadable sibling makes every id uncertain");
        rack[0].layerNode(1).child("channelId").emit(LAYER_B);
        JsonObject restored = chains(f, rack[0].ref());
        assertEquals(rack[0].chainRef(1), row(restored, "chains", 1).getAsJsonObject("ref"));
        assertEquals("ready", row(restored, "chains", 1).get("readiness").getAsString());
    }

    @Test
    void chainsBelongToARootLayerDeviceOnly() {
        DeviceFixture.Rack[] rack = new DeviceFixture.Rack[1];
        DeviceFixture f = racks(rack);
        assertRpcError("unsupported_scope", f.run(f.chains(), "chains.list", params("parent", f.device(0, "Comp"))));
        assertRpcError(
                "unsupported_scope", f.run(f.chains(), "chains.list", params("parent", rack[0].deviceRef(0, 0))));
        assertRpcError(
                "unsupported_scope",
                f.run(
                        f.list(),
                        "devices.list",
                        params("parent", Refs.chain(rack[0].deviceRef(0, 0), LAYER_C, "Nested"))));
        assertRpcError("target_not_found", f.run(f.chains(), "chains.list", params("parent", f.device(5, "Missing"))));
    }

    @Test
    void windowsThatAreNotObservedAtOffsetZeroAreNotReady() {
        DeviceFixture.Rack[] rack = new DeviceFixture.Rack[1];
        DeviceFixture f = racks(rack);
        rack[0].layers.child("scrollPosition").emit(1);
        assertRpcError("not_ready", f.run(f.chains(), "chains.list", params("parent", rack[0].ref())));
        assertRpcError("not_ready", f.run(f.list(), "devices.list", params("parent", rack[0].chainRef(0))));
        rack[0].layers.child("scrollPosition").emit(0);
        assertEquals(2, chains(f, rack[0].ref()).getAsJsonArray("chains").size());
        rack[0].layerNode(0).child("createDeviceBank").child("exists").emit(false);
        assertRpcError("not_ready", f.run(f.list(), "devices.list", params("parent", rack[0].chainRef(0))));
        JsonObject listed = chains(f, rack[0].ref());
        assertEquals("not_ready", row(listed, "chains", 0).get("readiness").getAsString());
        assertEquals("ready", row(listed, "chains", 1).get("readiness").getAsString());
        assertEquals(
                List.of("Chorus"),
                names(list(f, rack[0].chainRef(1))),
                "another chain stays listable while one is not ready");
    }

    private static List<String> names(JsonObject result) {
        List<String> names = new ArrayList<>();
        for (JsonElement item : result.getAsJsonArray("devices"))
            names.add(item.getAsJsonObject().get("name").getAsString());
        return names;
    }
}
