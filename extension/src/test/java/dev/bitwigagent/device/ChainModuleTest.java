package dev.bitwigagent.device;

import static dev.bitwigagent.RpcAssert.*;
import static dev.bitwigagent.device.DeviceFixture.*;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.HostOperation;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.core.Refs;
import dev.bitwigagent.core.Results;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/** chains.set and chains.insert, and the mixer strips chains.list reads, on the simulated native document. */
public final class ChainModuleTest {
    /** Comp, then a layer device Rack with a Pad and a Lead chain. */
    private static final class Fixture {
        final DeviceFixture f = new DeviceFixture();
        final DeviceFixture.Rack rack;
        final ChainModule module;

        Fixture() {
            f.top.seed("Comp", "Rack");
            rack = f.rack(1);
            rack.layer(0, LAYER_A, "Pad", "Delay");
            rack.layer(1, LAYER_B, "Lead");
            f.start();
            module = f.services.chainEdits();
        }

        HostOperation submit(String method, JsonObject params) {
            return f.submit(module, method, params, f.host.tasks.after(5000));
        }

        JsonObject call(String method, JsonObject params) {
            return f.finish(submit(method, params));
        }

        HostOperation fails(String method, JsonObject params) {
            return f.finishOrError(submit(method, params));
        }

        JsonObject set(int chain, Object... pairs) {
            return call("chains.set", with(params(pairs), "target", rack.chainRef(chain)));
        }

        FakeChains.Chain chain(int index) {
            return rack.chains.get(index);
        }

        JsonObject list() {
            return f.finish(f.submit(f.chains(), "chains.list", params("target", rack.ref())));
        }
    }

    private static JsonObject with(JsonObject params, String key, JsonElement value) {
        params.add(key, value);
        return params;
    }

    private static JsonObject field(JsonObject result, String name) {
        return result.getAsJsonObject("fields").getAsJsonObject(name);
    }

    private static void decibels(FakeChains.Param param) {
        param.display = v -> v <= 0 ? "-inf dB" : String.format(Locale.ROOT, "%.1f dB", 60 * v - 54);
    }

    // ---------------------------------------------------------------- chains.list strips

    @Test
    void listingReadsEachChainsStripAndOnASelectorWhichChainIsActive() {
        Fixture x = new Fixture();
        x.chain(1).solo = true;
        JsonObject row = x.list().getAsJsonArray("chains").get(1).getAsJsonObject();
        assertEquals(.7, row.getAsJsonObject("volume").get("value").getAsDouble());
        assertEquals("50.0 %", row.getAsJsonObject("pan").get("display").getAsString());
        assertFalse(row.get("mute").getAsBoolean());
        assertTrue(row.get("solo").getAsBoolean());
        assertFalse(row.has("active"), "only a Selector's chains say whether they are active");
        x.rack.node.selector(1);
        JsonArray rows = x.list().getAsJsonArray("chains");
        assertFalse(rows.get(0).getAsJsonObject().get("active").getAsBoolean());
        assertTrue(rows.get(1).getAsJsonObject().get("active").getAsBoolean());
    }

    @Test
    void aHostWithoutTheLayerBindingStillListsChainsAndSaysWhyStripsAreMissing() {
        Fixture x = new Fixture();
        x.f.natives.layersBroken = Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "no layer keys");
        JsonObject listed = x.list();
        assertEquals("no layer keys", listed.get("stripsUnavailable").getAsString());
        JsonObject row = listed.getAsJsonArray("chains").get(0).getAsJsonObject();
        assertEquals(1, row.get("deviceCount").getAsInt());
        assertFalse(row.has("volume"));
        x.f.natives.layersBroken = new IllegalStateException("binding failed");
        assertRpcError(
                "native_unavailable",
                x.f.finishOrError(x.f.submit(x.f.chains(), "chains.list", params("target", x.rack.ref()))));
    }

    // ---------------------------------------------------------------- chains.set

    @Test
    void fieldsAreComparedWrittenAndReadBackInOneDocumentPass() {
        Fixture x = new Fixture();
        int before = x.f.natives.executions;
        JsonObject result = x.set(0, "volume", .5, "pan", .5, "mute", true, "solo", true);
        assertEquals(1, x.f.natives.executions - before, "one document task for every field");
        assertEquals("applied", result.get("outcome").getAsString());
        assertEquals("native_readback", result.get("verification").getAsString());
        assertEquals("simulated", result.get("nativeVersion").getAsString());
        assertEquals(x.rack.chainRef(0), result.get("ref"));
        assertEquals("applied", field(result, "volume").get("outcome").getAsString());
        assertEquals("already_matched", field(result, "pan").get("outcome").getAsString());
        assertEquals("applied", field(result, "solo").get("outcome").getAsString());
        assertEquals(.5, x.chain(0).volume.value, 1e-4);
        assertEquals(1, x.chain(0).mute.value);
        assertEquals(0, x.chain(0).pan.writes, "an unchanged field is not written");
        assertTrue(x.chain(0).solo);
        assertFalse(x.chain(1).solo, "solo leaves the other chains alone");
        assertTrue(Results.leafRuleHolds(result));
        JsonObject off = x.set(0, "mute", false, "solo", false);
        assertEquals(0, x.chain(0).mute.value);
        assertFalse(x.chain(0).solo);
        assertEquals("applied", off.get("outcome").getAsString());
    }

    @Test
    void aStripAlreadyAtTheValuesIsAlreadyMatchedWithNothingWritten() {
        Fixture x = new Fixture();
        JsonObject result = x.set(1, "volume", .7, "mute", false);
        assertEquals("already_matched", result.get("outcome").getAsString());
        assertEquals("native_readback", result.get("verification").getAsString());
        assertEquals(0, x.chain(1).volume.writes);
        assertFalse(result.has("nativeVersion"));
    }

    @Test
    void expectComparesTheCurrentValuesFirst() {
        Fixture x = new Fixture();
        assertRpcError(
                "target_changed",
                x.fails(
                        "chains.set",
                        params("target", x.rack.chainRef(0), "mute", true, "expect", params("volume", .2))));
        assertEquals(0, x.chain(0).mute.value, "a mismatch writes nothing");
        JsonObject result = x.call(
                "chains.set", params("target", x.rack.chainRef(0), "mute", true, "expect", params("mute", false)));
        assertEquals("applied", result.get("outcome").getAsString());
    }

    @Test
    void activeMakesTheChainItsSelectorsActiveOneAndNeedsASelector() {
        Fixture x = new Fixture();
        assertRpcError(
                "unsupported_scope", x.fails("chains.set", params("target", x.rack.chainRef(1), "active", true)));
        x.rack.node.selector(0);
        JsonObject result = x.set(1, "active", true);
        assertEquals(1, x.rack.node.active);
        assertEquals("applied", field(result, "active").get("outcome").getAsString());
        assertEquals("already_matched", x.set(1, "active", true).get("outcome").getAsString());
    }

    @Test
    void aChainWithoutAMixerOrAParameterTheHostLocksIsRefusedBeforeAnyWrite() {
        Fixture x = new Fixture();
        x.chain(0).mixer = false;
        assertRpcError("unsupported_scope", x.fails("chains.set", params("target", x.rack.chainRef(0), "mute", true)));
        x.chain(1).volume.writable = false;
        assertRpcError(
                "invalid_target",
                x.fails("chains.set", params("target", x.rack.chainRef(1), "mute", true, "volume", .1)));
        assertEquals(0, x.chain(1).mute.value, "the refusal comes before the mute is written");
    }

    @Test
    void aWriteTheHostDoesNotCarryOutIsUnknownReadbackMismatch() {
        Fixture x = new Fixture();
        x.f.natives.ignoreSwitches = true;
        HostOperation op = x.fails("chains.set", params("target", x.rack.chainRef(0), "solo", true, "mute", true));
        JsonObject result = assertUnknown("readback_mismatch", op);
        assertEquals("unknown", field(result, "solo").get("outcome").getAsString());
        assertFalse(field(result, "solo").get("observed").getAsBoolean());
        assertEquals("applied", field(result, "mute").get("outcome").getAsString());
    }

    @Test
    void aStaleChainRefWritesNothing() {
        Fixture x = new Fixture();
        JsonObject renamed = Refs.chain(x.rack.ref(), LAYER_A, "Old");
        assertRpcError("target_changed", x.fails("chains.set", params("target", renamed, "mute", true)));
        assertEquals(0, x.chain(0).mute.writes);
    }

    @Test
    void aDisplayVolumeIsSolvedOnTheHostsOwnTextThenWrittenWithTheOtherFields() {
        Fixture x = new Fixture();
        decibels(x.chain(0).volume);
        JsonObject result = x.set(0, "volume", params("display", "-6 dB"), "mute", true);
        assertEquals("applied", result.get("outcome").getAsString());
        assertEquals(
                "-6.0 dB",
                field(result, "volume")
                        .getAsJsonObject("observed")
                        .get("display")
                        .getAsString());
        assertEquals("applied", field(result, "volume").get("outcome").getAsString());
        assertTrue(result.getAsJsonObject("displayWrite")
                .getAsJsonObject("volume")
                .get("matched")
                .getAsBoolean());
        assertEquals(1, x.chain(0).mute.value);
        JsonObject again = x.set(0, "volume", params("display", "-6 dB"));
        assertEquals("already_matched", again.get("outcome").getAsString());
    }

    @Test
    void aDisplayThatCannotBeReachedPutsTheFieldsSolvedBeforeItBack() {
        Fixture x = new Fixture();
        decibels(x.chain(0).volume);
        x.chain(0).pan.display = v -> "centre";
        JsonObject result = x.f.finish(x.submit(
                "chains.set",
                params(
                        "target",
                        x.rack.chainRef(0),
                        "volume",
                        params("display", "-6 dB"),
                        "pan",
                        params("display", "-25 %"))));
        assertEquals("not_applied", result.get("outcome").getAsString());
        assertEquals(.7, x.chain(0).volume.value, 1e-9, "the solved volume is put back");
        assertTrue(result.getAsJsonObject("displayWrite").has("volume"));
        assertRpcError(
                "unsupported_unit",
                x.fails("chains.set", params("target", x.rack.chainRef(0), "volume", params("display", "loud"))));
    }

    @Test
    void expectIsComparedBeforeTheFirstDisplayProbe() {
        Fixture x = new Fixture();
        decibels(x.chain(0).volume);
        assertRpcError(
                "target_changed",
                x.fails(
                        "chains.set",
                        params(
                                "target",
                                x.rack.chainRef(0),
                                "volume",
                                params("display", "-6 dB"),
                                "expect",
                                params("solo", true))));
        assertEquals(0, x.chain(0).volume.writes, "no probe is written");
    }

    @Test
    void anUnreachableFirstDisplayChangesNothing() {
        Fixture x = new Fixture();
        decibels(x.chain(0).volume);
        JsonObject result = x.f.finish(
                x.submit("chains.set", params("target", x.rack.chainRef(0), "volume", params("display", "+30 dB"))));
        assertEquals("not_applied", result.get("outcome").getAsString());
        assertEquals(.7, x.chain(0).volume.value, 1e-9);
    }

    // ---------------------------------------------------------------- chains.insert

    @Test
    void aNewLayerIsAppendedAndProvenByTheChainsBeforeAndAfter() {
        Fixture x = new Fixture();
        JsonObject result = x.call("chains.insert", params("to", x.rack.ref()));
        assertEquals("applied", result.get("outcome").getAsString());
        assertEquals("native_readback", result.get("verification").getAsString());
        assertEquals(2, result.get("beforeCount").getAsInt());
        assertEquals(3, result.get("afterCount").getAsInt());
        FakeChains.Chain added = x.rack.node.chains.get(2);
        assertEquals(Refs.chain(x.rack.ref(), added.id.toString(), "Layer 3"), result.get("ref"));
        assertEquals(x.rack.ref(), result.get("parent"));
        assertEquals(1, x.f.natives.newChains);
        assertTrue(Results.leafRuleHolds(result));
        JsonObject strip = x.call("chains.set", params("target", result.get("ref"), "mute", true));
        assertEquals("applied", strip.get("outcome").getAsString(), "the new ref addresses the new chain at once");
    }

    @Test
    void aChainThatAppearsAfterSomePollsIsStillProven() {
        Fixture x = new Fixture();
        x.f.natives.insertDelay = 2;
        HostOperation first = x.submit("chains.insert", params("to", x.rack.ref()));
        assertRpcError("busy", x.fails("chains.insert", params("to", x.rack.ref())));
        assertEquals("applied", x.f.finish(first).get("outcome").getAsString());
        assertEquals(1, x.f.natives.newChains);
    }

    @Test
    void aDrumMachinePadGetsAnEmptyChainOnceAndThenMatches() {
        Fixture x = new Fixture();
        x.rack.node.drum();
        assertRpcError("invalid_params", x.fails("chains.insert", params("to", x.rack.ref())));
        JsonObject created = x.call("chains.insert", params("to", x.rack.ref(), "pad", 36));
        assertEquals("applied", created.get("outcome").getAsString());
        assertEquals(36, created.get("pad").getAsInt());
        JsonObject again = x.call("chains.insert", params("to", x.rack.ref(), "pad", 36));
        assertEquals("already_matched", again.get("outcome").getAsString());
        assertEquals(created.get("ref"), again.get("ref"));
        assertEquals(1, x.f.natives.newChains);
    }

    @Test
    void aDeviceWithoutAContainerOrAPadOnALayerIsRefused() {
        Fixture x = new Fixture();
        assertRpcError("invalid_params", x.fails("chains.insert", params("to", x.rack.ref(), "pad", 36)));
        assertRpcError("unsupported_scope", x.fails("chains.insert", params("to", x.f.device(0, "Comp"))));
        assertEquals(0, x.f.natives.newChains);
    }

    @Test
    void aChainTheHostNeverAddsIsNotObservedAndAnotherChangeIsAMismatch() {
        Fixture x = new Fixture();
        x.f.natives.ignoreNewChains = true;
        JsonObject missing = x.f.finish(x.submit("chains.insert", params("to", x.rack.ref())));
        assertEquals("unknown", missing.get("outcome").getAsString());
        assertEquals("not_observed", missing.get("reason").getAsString());
        assertTrue(missing.get("ref").isJsonNull());
        x.f.natives.ignoreNewChains = false;
        x.f.natives.onEdit = () -> x.rack.node.chains.add(0, x.rack.node.chains.remove(1));
        JsonObject reordered = x.f.finish(x.submit("chains.insert", params("to", x.rack.ref())));
        assertEquals("readback_mismatch", reordered.get("reason").getAsString());
    }
}
