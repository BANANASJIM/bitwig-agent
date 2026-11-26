package dev.bitwigagent;

import static dev.bitwigagent.RpcAssert.*;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Validation, safety, window and holds around a simulated native routing adapter; not native binding evidence. */
public final class RoutingModuleTest {
    static final String A = HostFixture.A, B = HostFixture.B, C = HostFixture.C;

    @Test
    void validation() {
        ok("tracks.routing.get", json("{\"trackId\":\"" + A + "\"}"));
        ok("tracks.routing.get", json("{\"trackId\":\"" + A + "\",\"includeChoices\":true}"));
        bad("tracks.routing.get", json("{\"trackId\":\"" + A + "\",\"includeChoices\":1}"));
        bad("tracks.routing.get", json("{\"trackId\":\"nope\"}"));
        ok(
                "tracks.routing.set",
                set("{\"kind\":\"track_audio_input\",\"trackId\":\"" + B + "\"}", "{\"kind\":\"none\"}", null, null));
        ok(
                "tracks.routing.set",
                set(
                        null,
                        null,
                        "{\"kind\":\"pre_fader\",\"trackId\":\"" + B + "\"}",
                        "{\"kind\":\"other\",\"name\":\"In 1\"}"));
        ok(
                "tracks.routing.set",
                set(
                        "{\"kind\":\"none\"}",
                        "{\"kind\":\"track_note_input\",\"trackId\":\"" + B + "\"}",
                        "{\"kind\":\"all_ins\"}",
                        "{\"kind\":\"none\"}"));
        bad("tracks.routing.set", json("{\"trackId\":\"" + A + "\"}"));
        bad("tracks.routing.set", set("{\"kind\":\"none\"}", null, null, null));
        bad("tracks.routing.set", set(null, "{\"kind\":\"none\"}", null, null));
        bad(
                "tracks.routing.set",
                set("{\"kind\":\"pre_fader\",\"trackId\":\"" + B + "\"}", "{\"kind\":\"none\"}", null, null));
        bad(
                "tracks.routing.set",
                set(null, null, "{\"kind\":\"track_audio_input\",\"trackId\":\"" + B + "\"}", "{\"kind\":\"none\"}"));
        bad("tracks.routing.set", set("{\"kind\":\"track_audio_input\"}", "{\"kind\":\"none\"}", null, null));
        bad(
                "tracks.routing.set",
                set("{\"kind\":\"none\",\"trackId\":\"" + B + "\"}", "{\"kind\":\"none\"}", null, null));
        bad("tracks.routing.set", set("{\"kind\":\"other\",\"name\":\"Out\"}", "{\"kind\":\"none\"}", null, null));
        bad("tracks.routing.set", set("{\"kind\":\"none\"}", "{\"kind\":\"other\"}", null, null));
        bad("tracks.routing.set", set("{\"kind\":\"none\"}", "{\"kind\":\"none\",\"name\":\"x\"}", null, null));
        ok("tracks.routing.set", set("{\"kind\":\"none\"}", "{\"kind\":\"other\",\"name\":\"\"}", null, null));
        ok(
                "tracks.routing.set",
                set("{\"kind\":\"none\"}", "{\"kind\":\"other\",\"name\":\"Out\",\"nativeType\":\"fe\"}", null, null));
        bad("tracks.routing.set", set("{\"kind\":\"none\"}", "{\"kind\":\"none\",\"nativeType\":\"fe\"}", null, null));
        bad("tracks.routing.set", set("{\"kind\":\"none\"}", "{\"kind\":\"other\",\"name\":1}", null, null));
        bad("tracks.routing.set", set("{\"kind\":\"none\"}", "{\"kind\":\"none\",\"extra\":1}", null, null));
        bad("tracks.routing.set", set(null, null, "{\"kind\":\"hardware\"}", "{\"kind\":\"none\"}"));
        bad(
                "tracks.routing.set",
                set(null, null, "{\"kind\":\"post_fader\",\"trackId\":\"x\"}", "{\"kind\":\"none\"}"));
        JsonObject side = sidechain();
        ok("devices.sidechain.set", side);
        JsonObject both = sidechain();
        both.addProperty("componentId", "SIDECHAIN");
        bad("devices.sidechain.set", both);
        JsonObject neither = sidechain();
        neither.remove("componentIndex");
        neither.remove("expectedComponentId");
        bad("devices.sidechain.set", neither);
        JsonObject byId = sidechain();
        byId.remove("componentIndex");
        byId.remove("expectedComponentId");
        byId.addProperty("componentId", "SIDECHAIN");
        ok("devices.sidechain.set", byId);
        JsonObject idWithExpected = sidechain();
        idWithExpected.remove("componentIndex");
        idWithExpected.addProperty("componentId", "SIDECHAIN");
        bad("devices.sidechain.set", idWithExpected);
        JsonObject indexOnly = sidechain();
        indexOnly.remove("expectedComponentId");
        bad("devices.sidechain.set", indexOnly);
        JsonObject modulator = sidechain();
        modulator.addProperty("expectedModulatorId", C);
        ok("devices.sidechain.set", modulator);
        modulator.addProperty("expectedModulatorId", "mod");
        bad("devices.sidechain.set", modulator);
        JsonObject module = sidechain();
        module.addProperty("expectedModuleId", C);
        ok("devices.sidechain.set", module);
        module.addProperty("expectedModuleId", "module");
        bad("devices.sidechain.set", module);
        JsonObject index = sidechain();
        index.addProperty("componentIndex", 256);
        bad("devices.sidechain.set", index);
        JsonObject device = sidechain();
        device.addProperty("deviceIndex", 16);
        bad("devices.sidechain.set", device);
        JsonObject output = sidechain();
        output.add("source", json("{\"kind\":\"track_audio_input\",\"trackId\":\"" + B + "\"}"));
        bad("devices.sidechain.set", output);
        JsonObject noExpected = sidechain();
        noExpected.remove("expectedSource");
        bad("devices.sidechain.set", noExpected);
        ok(
                "devices.sidechain.get",
                json("{\"trackId\":\"" + A
                        + "\",\"deviceIndex\":0,\"expectedDeviceName\":\"Tool\",\"includeChoices\":false}"));
        bad("devices.sidechain.get", json("{\"trackId\":\"" + A + "\",\"deviceIndex\":0}"));
    }

    @Test
    void helpers() {
        assertTrue(
                RoutingModule.sourceKinds(RoutingModule.trackSourceGroups("AUDIO"))
                        .equals(List.of("none", "pre_fader", "post_fader")),
                "audio track: pre/post fader");
        assertTrue(
                RoutingModule.sourceKinds(RoutingModule.trackSourceGroups("INSTRUMENT"))
                        .equals(List.of("none", "track_notes", "all_ins", "computer_keyboard", "onscreen_keyboard")),
                "instrument track: note sources");
        assertTrue(
                RoutingModule.sourceKinds(RoutingModule.trackSourceGroups("HYBRID"))
                                .size()
                        == 7,
                "hybrid track: every v1 source");
        assertTrue(
                RoutingModule.sourceKinds(List.of("HARDWARE_AUDIO_SOURCES")).equals(List.of("none")),
                "hardware audio is deferred");
        assertTrue(RoutingModule.trackSourceGroups("GROUP").isEmpty(), "unknown native kind admits nothing");
        JsonObject observed = json("{\"kind\":\"pre_fader\",\"trackId\":\"" + B + "\",\"name\":\"Pre\"}");
        assertTrue(
                RoutingModule.sameEntry(
                        json("{\"kind\":\"pre_fader\",\"trackId\":\"" + B.toUpperCase(Locale.ROOT) + "\"}"), observed),
                "CAS ignores UUID case and display name");
        assertTrue(
                !RoutingModule.sameEntry(json("{\"kind\":\"post_fader\",\"trackId\":\"" + B + "\"}"), observed),
                "CAS compares kind");
        assertTrue(
                !RoutingModule.sameEntry(json("{\"kind\":\"pre_fader\",\"trackId\":\"" + C + "\"}"), observed),
                "CAS compares trackId");
        JsonObject other = json("{\"kind\":\"other\",\"name\":\"Out 1\",\"nativeType\":\"x\"}");
        assertTrue(
                RoutingModule.sameEntry(json("{\"kind\":\"other\",\"name\":\"Out 1\"}"), other),
                "other compares exact name");
        assertTrue(
                !RoutingModule.sameEntry(json("{\"kind\":\"other\",\"name\":\"Out 2\"}"), other),
                "other with another name differs");
        assertTrue(
                RoutingModule.sameEntry(json("{\"kind\":\"other\",\"name\":\"Out 1\",\"nativeType\":\"x\"}"), other),
                "other copied verbatim matches");
        assertTrue(
                !RoutingModule.sameEntry(json("{\"kind\":\"other\",\"name\":\"Out 1\",\"nativeType\":\"y\"}"), other),
                "same name on another native type differs");
        assertTrue(
                RoutingModule.sameEntry(json("{\"kind\":\"none\"}"), json("{\"kind\":\"none\",\"name\":null}")),
                "none matches none");
    }

    @Test
    void getIsReadOnlyAndAllowsPlayingTransport() {
        Fixture f = new Fixture();
        f.host.transport.child("isPlaying").emit(true);
        JsonObject result = f.call("tracks.routing.get", json("{\"trackId\":\"" + A + "\",\"includeChoices\":true}"));
        assertTrue(
                !result.has("outcome") && f.adapter.reads == 1 && f.adapter.writes == 0, "get reads without writing");
        assertTrue(
                f.adapter.lastChoices && f.adapter.lastSelf.id.equals(A) && f.adapter.lastBank.size() == 2,
                "get passes the whole observed bank and includeChoices");
        assertTrue(
                f.adapter.lastBank.get(1).id.equals(B)
                        && f.adapter.lastBank.get(1).type.equals("Audio"),
                "bank rows carry public UUID and type");
    }

    @Test
    void setNeedsStoppedTransportAndObservedTargets() {
        Fixture playing = new Fixture();
        playing.host.transport.child("isPlaying").emit(true);
        assertRpcError("unsafe_state", playing.start("tracks.routing.set", outputTo(B)));
        assertTrue(playing.adapter.calls == 0, "playing transport blocks routing writes before native access");
        Fixture outside = new Fixture();
        assertRpcError("outside_window", outside.start("tracks.routing.set", outputTo(C)));
        assertTrue(outside.adapter.calls == 0, "a destination outside the bank window is refused before native access");
        Fixture f = new Fixture();
        JsonObject result = f.call("tracks.routing.set", outputTo(B));
        assertTrue(
                f.adapter.writes == 1 && result.get("outcome").getAsString().equals("applied"),
                "stopped transport admits the write");
        assertTrue(f.host.services.holds.list().isEmpty(), "an observed write clears the pending/quarantine state");
    }

    @Test
    void unknownWriteQuarantinesWritesButNotReads() {
        Fixture f = new Fixture();
        f.adapter.unknown = true;
        JsonObject result = f.call("tracks.routing.set", outputTo(B));
        assertTrue(result.get("outcome").getAsString().equals("unknown"), "unknown readback is returned, not hidden");
        assertTrue(!f.host.services.holds.list().isEmpty(), "unknown readback quarantines routing writes");
        assertRpcError("unresolved", f.start("tracks.routing.set", outputTo(B)));
        assertTrue(
                !f.call("tracks.routing.get", json("{\"trackId\":\"" + A + "\"}"))
                        .has("outcome"),
                "reads stay available");
        Fixture thrown = new Fixture();
        thrown.adapter.failAfterWrite = true;
        HostOperation failed = thrown.start("tracks.routing.set", outputTo(B));
        thrown.drain();
        assertTrue(failed.dispatched, "failure happened after dispatch");
        assertUnknown("native_unavailable", failed);
        assertRpcError("unresolved", thrown.start("devices.sidechain.set", sidechain()));
    }

    @Test
    void preDispatchRefusalDoesNotQuarantine() {
        Fixture f = new Fixture();
        f.adapter.refuse = Reason.KIND_MISMATCH;
        HostOperation op = f.start("tracks.routing.set", outputTo(B));
        f.drain();
        assertRpcError("kind_mismatch", op);
        assertTrue(
                !op.dispatched && f.host.services.holds.list().isEmpty(),
                "pre-dispatch refusal is not_applied and does not quarantine");
        assertTrue(!rejection(op).unknown, "refusal is an error: nothing changed");
    }

    @Test
    void sidechainUsesTheObservedRootDevice() {
        Fixture f = new Fixture();
        f.observeDevice("Compressor");
        JsonObject get = json("{\"trackId\":\"" + A + "\",\"deviceIndex\":0,\"expectedDeviceName\":\"Compressor\"}");
        f.call("devices.sidechain.get", get);
        assertTrue(
                f.adapter.lastDevice != null && f.adapter.reads == 1,
                "sidechain get passes the observed root device proxy");
        JsonObject wrong = json("{\"trackId\":\"" + A + "\",\"deviceIndex\":0,\"expectedDeviceName\":\"Gate\"}");
        assertRpcError("stale_target", f.start("devices.sidechain.get", wrong));
        JsonObject p = sidechain();
        p.addProperty("expectedDeviceName", "Compressor");
        f.call("devices.sidechain.set", p);
        assertTrue(f.adapter.writes == 1, "sidechain set reaches the adapter with a stopped transport");
        Fixture missing = new Fixture();
        assertRpcError("stale_target", missing.start("devices.sidechain.get", get));
        assertTrue(missing.adapter.calls == 0, "an unobserved device is refused before native access");
    }

    static JsonObject outputTo(String id) {
        return set("{\"kind\":\"track_audio_input\",\"trackId\":\"" + id + "\"}", "{\"kind\":\"none\"}", null, null);
    }

    static JsonObject set(String output, String expectedOutput, String input, String expectedInput) {
        JsonObject p = json("{\"trackId\":\"" + A + "\"}");
        if (output != null) p.add("output", json(output));
        if (expectedOutput != null) p.add("expectedOutput", json(expectedOutput));
        if (input != null) p.add("input", json(input));
        if (expectedInput != null) p.add("expectedInput", json(expectedInput));
        return p;
    }

    static JsonObject sidechain() {
        return json("{\"trackId\":\"" + A
                + "\",\"deviceIndex\":0,\"expectedDeviceName\":\"Tool\",\"componentIndex\":0,\"expectedComponentId\":\"SIDECHAIN\","
                + "\"source\":{\"kind\":\"pre_fader\",\"trackId\":\"" + B
                + "\"},\"expectedSource\":{\"kind\":\"none\"}}");
    }

    static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    static void ok(String m, JsonObject p) {
        assertDoesNotThrow(() -> RoutingModule.validateParams(m, p), "rejected valid " + p);
    }

    static void bad(String m, JsonObject p) {
        assertRpcError("invalid_params", () -> RoutingModule.validateParams(m, p));
    }

    @Test
    void trackNamesAnnotateEntriesWithoutChangingThem() {
        JsonObject result = JsonParser.parseString(
                        "{\"output\":{\"entry\":{\"kind\":\"track_audio_input\",\"trackId\":\"m\"},\"choices\":["
                                + "{\"entry\":{\"kind\":\"post_fader\",\"trackId\":\"a\"},\"name\":\"Post-Fader\"},{\"entry\":{\"kind\":\"none\"},\"name\":null},"
                                + "{\"entry\":{\"kind\":\"track_notes\",\"trackId\":\"gone\"},\"name\":\"\"}]},"
                                + "\"components\":[{\"source\":{\"kind\":\"pre_fader\",\"trackId\":\"a\"}}]}")
                .getAsJsonObject();
        RoutingModule.annotateTrackNames(result, Map.of("m", "Master", "a", "Drums"));
        JsonObject output = result.getAsJsonObject("output");
        JsonArray choices = output.getAsJsonArray("choices");
        assertTrue(output.get("trackName").getAsString().equals("Master"), "current entry gets its track name");
        assertTrue(
                choices.get(0).getAsJsonObject().get("trackName").getAsString().equals("Drums"),
                "choice row gets the track name");
        assertTrue(
                !choices.get(0).getAsJsonObject().getAsJsonObject("entry").has("trackName"),
                "entry stays verbatim for expected*");
        assertTrue(
                !choices.get(1).getAsJsonObject().has("trackName")
                        && !choices.get(2).getAsJsonObject().has("trackName"),
                "no name for none or unknown tracks");
        assertTrue(
                result.getAsJsonArray("components")
                        .get(0)
                        .getAsJsonObject()
                        .get("sourceTrackName")
                        .getAsString()
                        .equals("Drums"),
                "sidechain source named");
    }

    static final class Fixture {
        final HostFixture host = new HostFixture();
        final Adapter adapter = new Adapter();
        final RoutingModule module = host.services.routing(HostFixture.SPECS, adapter);

        void observeDevice(String name) {
            FakeHost devices = host.tracks[0].child("createDeviceBank"), device = devices.item(0);
            device.child("exists").emit(true);
            device.child("name").emit(name);
            device.child("position").emit(0);
            device.child("deviceType").emit("AudioEffect");
            devices.child("itemCount").emit(1);
        }

        HostOperation start(String m, JsonObject p) {
            HostOperation op = host.submit(module, m, p);
            host.next();
            return op;
        }

        void drain() {
            while (!adapter.tasks.isEmpty() || !host.tasks.isEmpty()) {
                if (!adapter.tasks.isEmpty()) adapter.tasks.removeFirst().run();
                else host.next();
            }
        }

        JsonObject call(String m, JsonObject p) {
            HostOperation op = start(m, p);
            drain();
            return op.result.join();
        }
    }

    static final class Adapter implements RoutingModule.Access {
        final Deque<Runnable> tasks = new ArrayDeque<>();
        int calls, reads, writes;
        boolean unknown, failAfterWrite, lastChoices;
        Reason refuse;
        RoutingModule.TrackRef lastSelf;
        List<RoutingModule.TrackRef> lastBank;
        Object lastDevice;

        public void execute(Runnable work) {
            tasks.add(work);
        }

        public JsonObject trackGet(
                RoutingModule.TrackRef self, List<RoutingModule.TrackRef> bank, boolean choices, Runnable check) {
            calls++;
            reads++;
            check.run();
            lastSelf = self;
            lastBank = bank;
            lastChoices = choices;
            return new JsonObject();
        }

        public JsonObject trackSet(
                RoutingModule.TrackRef self,
                List<RoutingModule.TrackRef> bank,
                JsonObject p,
                Runnable check,
                Runnable before) {
            lastSelf = self;
            lastBank = bank;
            return write(check, before);
        }

        public JsonObject sidechainGet(
                Object device, String id, List<RoutingModule.TrackRef> bank, boolean choices, Runnable check) {
            calls++;
            reads++;
            check.run();
            lastDevice = device;
            return new JsonObject();
        }

        public JsonObject sidechainSet(
                Object device,
                String id,
                List<RoutingModule.TrackRef> bank,
                JsonObject p,
                Runnable check,
                Runnable before) {
            lastDevice = device;
            return write(check, before);
        }

        JsonObject write(Runnable check, Runnable before) {
            calls++;
            check.run();
            if (refuse != null) throw Errors.notApplied(refuse, "simulated refusal");
            before.run();
            writes++;
            if (failAfterWrite) throw Errors.unknown(Reason.NATIVE_UNAVAILABLE, "simulated native failure");
            JsonObject out = new JsonObject();
            out.addProperty("outcome", unknown ? "unknown" : "applied");
            return out;
        }
    }

    @Test
    void unknownMethodAndUnboundNativeAccessAreRefused() {
        assertRpcError("unknown_method", () -> RoutingModule.validateParams("tracks.routing.bogus", new JsonObject()));
        HostFixture host = new HostFixture();
        RoutingModule module = host.services.routing(HostFixture.SPECS, null);
        HostOperation op = host.submit(module, "tracks.routing.get", json("{\"trackId\":\"" + A + "\"}"));
        host.next();
        assertRpcError("host_mismatch", op);
    }
}
