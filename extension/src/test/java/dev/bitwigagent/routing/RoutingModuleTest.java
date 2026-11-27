package dev.bitwigagent.routing;

import static dev.bitwigagent.RpcAssert.*;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import dev.bitwigagent.FakeHost;
import dev.bitwigagent.HostBridge;
import dev.bitwigagent.HostFixture;
import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.HostOperation;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.core.Refs;
import dev.bitwigagent.core.RpcException;
import dev.bitwigagent.host.DeviceSources;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Validation, safety, window and holds around a simulated native routing adapter; not native binding evidence. */
public final class RoutingModuleTest {
    static final String A = HostFixture.A, B = HostFixture.B, C = HostFixture.C;

    @Test
    void validation() {
        ok("tracks.routing.get", target());
        ok("tracks.routing.get", with(target(), "\"includeChoices\":true"));
        bad("tracks.routing.get", with(target(), "\"includeChoices\":1"));
        bad("tracks.routing.get", json("{\"track\":" + HostFixture.track(A) + "}"));
        bad("tracks.routing.get", json("{\"target\":{\"kind\":\"track\",\"id\":\"nope\",\"name\":\"x\"}}"));
        ok(
                "tracks.routing.set",
                set(
                        "{\"kind\":\"track_audio_input\",\"track\":" + HostFixture.track(B) + "}",
                        "{\"kind\":\"none\"}",
                        null,
                        null));
        ok("tracks.routing.set", set("{\"kind\":\"none\"}", null, null, null));
        ok(
                "tracks.routing.set",
                set(
                        null,
                        null,
                        "{\"kind\":\"pre_fader\",\"track\":" + HostFixture.track(B) + "}",
                        "{\"kind\":\"other\",\"name\":\"In 1\"}"));
        ok(
                "tracks.routing.set",
                set(
                        "{\"kind\":\"none\"}",
                        "{\"kind\":\"track_note_input\",\"track\":" + HostFixture.track(B) + "}",
                        "{\"kind\":\"all_ins\"}",
                        "{\"kind\":\"none\"}"));
        bad("tracks.routing.set", target());
        bad("tracks.routing.set", set("{\"kind\":\"none\"}", "{\"kind\":\"none\"}", null, null, "expectedOutput"));
        bad(
                "tracks.routing.set",
                set(
                        "{\"kind\":\"pre_fader\",\"track\":" + HostFixture.track(B) + "}",
                        "{\"kind\":\"none\"}",
                        null,
                        null));
        bad(
                "tracks.routing.set",
                set(
                        null,
                        null,
                        "{\"kind\":\"track_audio_input\",\"track\":" + HostFixture.track(B) + "}",
                        "{\"kind\":\"none\"}"));
        bad("tracks.routing.set", set("{\"kind\":\"track_audio_input\"}", "{\"kind\":\"none\"}", null, null));
        bad(
                "tracks.routing.set",
                set("{\"kind\":\"none\",\"track\":" + HostFixture.track(B) + "}", "{\"kind\":\"none\"}", null, null));
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
                set(null, null, "{\"kind\":\"post_fader\",\"trackId\":\"" + B + "\"}", "{\"kind\":\"none\"}"));
        ok("devices.sidechain.set", sidechain());
        JsonObject modulator = sidechain();
        modulator.getAsJsonObject("target").addProperty("modulatorId", C);
        ok("devices.sidechain.set", modulator);
        modulator.getAsJsonObject("target").addProperty("modulatorId", "mod");
        bad("devices.sidechain.set", modulator);
        JsonObject module = sidechain();
        module.getAsJsonObject("target").addProperty("moduleId", C);
        ok("devices.sidechain.set", module);
        JsonObject index = sidechain();
        index.getAsJsonObject("target").addProperty("index", -1);
        bad("devices.sidechain.set", index);
        JsonObject output = sidechain();
        output.add("source", json("{\"kind\":\"track_audio_input\",\"track\":" + HostFixture.track(B) + "}"));
        bad("devices.sidechain.set", output);
        JsonObject noExpected = sidechain();
        noExpected.remove("expect");
        ok("devices.sidechain.set", noExpected);
        JsonObject legacy = sidechain();
        legacy.addProperty("componentIndex", 0);
        bad("devices.sidechain.set", legacy);
        ok("devices.sidechain.get", json("{\"target\":" + DEVICE + ",\"includeChoices\":false}"));
        bad("devices.sidechain.get", json("{\"track\":" + HostFixture.track(A) + ",\"deviceIndex\":0}"));
        bad(
                "devices.sidechain.get",
                json("{\"target\":{\"kind\":\"device\",\"chain\":{\"kind\":\"chain\",\"device\":" + DEVICE
                        + ",\"id\":\"" + C + "\",\"name\":\"Pad\"},\"index\":0,\"name\":\"Tool\"}}"));
    }

    @Test
    void expectNeedsTheFieldItComparesToBeWritten() {
        JsonObject p = target();
        p.add("output", json("{\"kind\":\"none\"}"));
        p.add("expect", json("{\"input\":{\"kind\":\"none\"}}"));
        assertRpcError("invalid_params", () -> validated("tracks.routing.set", p));
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
        JsonObject observed = json("{\"kind\":\"pre_fader\",\"track\":" + HostFixture.track(B) + ",\"name\":\"Pre\"}");
        assertTrue(
                RoutingModule.sameEntry(
                        json("{\"kind\":\"pre_fader\",\"track\":" + HostFixture.track(B.toUpperCase(Locale.ROOT))
                                + "}"),
                        observed),
                "CAS ignores UUID case and display name");
        assertTrue(
                !RoutingModule.sameEntry(
                        json("{\"kind\":\"post_fader\",\"track\":" + HostFixture.track(B) + "}"), observed),
                "CAS compares kind");
        assertTrue(
                !RoutingModule.sameEntry(
                        json("{\"kind\":\"pre_fader\",\"track\":" + HostFixture.track(C) + "}"), observed),
                "CAS compares the track id");
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
        JsonObject result = f.call("tracks.routing.get", with(target(), "\"includeChoices\":true"));
        assertEquals(HostFixture.track(A), result.getAsJsonObject("ref"), "the result names the track by ref");
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
        Fixture renamed = new Fixture();
        renamed.host.tracks[0].child("name").emit("Lead");
        RpcException stale = assertRpcError("target_changed", renamed.start("tracks.routing.set", outputTo(B)));
        assertEquals(Refs.track(A, "Lead"), stale.current, "a renamed target is stale with the ref found now");
        assertEquals(0, renamed.adapter.calls);
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
        assertTrue(!f.call("tracks.routing.get", target()).has("outcome"), "reads stay available");
        Fixture thrown = new Fixture();
        thrown.adapter.failAfterWrite = true;
        HostOperation failed = thrown.start("tracks.routing.set", outputTo(B));
        thrown.drain();
        assertTrue(failed.dispatched(), "failure happened after dispatch");
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
                !op.dispatched() && f.host.services.holds.list().isEmpty(),
                "pre-dispatch refusal is not_applied and does not quarantine");
        assertTrue(!rejection(op).unknown, "refusal is an error: nothing changed");
    }

    @Test
    void sidechainUsesTheDeviceRef() {
        Fixture f = new Fixture();
        f.observeDevice("Compressor");
        JsonObject get = json("{\"target\":" + DEVICE + "}");
        f.call("devices.sidechain.get", get);
        assertTrue(
                f.adapter.lastDevice != null && f.adapter.reads == 1,
                "sidechain get passes the observed root device proxy");
        String hint = f.call("devices.sidechain.get", get).get("hint").getAsString();
        assertTrue(
                hint.contains("Compressor+") && hint.contains("Dynamics"),
                "an empty component list names stock devices");
        JsonObject wrong = json("{\"target\":" + Refs.device(HostFixture.track(A), 0, "Gate") + "}");
        RpcException changed = assertRpcError("target_changed", f.start("devices.sidechain.get", wrong));
        assertEquals(Refs.device(HostFixture.track(A), 0, "Compressor"), changed.current);
        JsonObject p = sidechain();
        JsonObject result = f.call("devices.sidechain.set", p);
        assertTrue(f.adapter.writes == 1, "sidechain set reaches the adapter");
        assertEquals(p.getAsJsonObject("target"), result.getAsJsonObject("ref"), "the written component's ref");
        f.host.tracks[0].child("name").emit("Lead");
        f.call("devices.sidechain.get", get);
        assertEquals(3, f.adapter.reads, "a device ref survives its track's rename");
        Fixture missing = new Fixture();
        assertRpcError("target_not_found", missing.start("devices.sidechain.get", get));
        assertTrue(missing.adapter.calls == 0, "an unobserved device is refused before native access");
    }

    @Test
    void sidechainSetMayRunWhilePlayingButNotWhileRecording() {
        Fixture playing = new Fixture();
        playing.observeDevice("Compressor");
        playing.host.transport.child("isPlaying").emit(true);
        playing.call("devices.sidechain.set", sidechain());
        assertEquals(1, playing.adapter.writes, "a playing transport admits a sidechain write");
        Fixture recording = new Fixture();
        recording.observeDevice("Compressor");
        recording.host.transport.child("isArrangerRecordEnabled").emit(true);
        assertRpcError("unsafe_state", recording.start("devices.sidechain.set", sidechain()));
        assertEquals(0, recording.adapter.calls, "recording blocks a sidechain write before native access");
    }

    static JsonObject outputTo(String id) {
        return set(
                "{\"kind\":\"track_audio_input\",\"track\":" + HostFixture.track(id) + "}",
                "{\"kind\":\"none\"}",
                null,
                null);
    }

    static final JsonObject DEVICE_REF = Refs.device(HostFixture.track(A), 0, "Compressor");
    static final String DEVICE = DEVICE_REF.toString();

    static JsonObject target() {
        return HostFixture.targeting(A);
    }

    static JsonObject with(JsonObject p, String members) {
        json("{" + members + "}").entrySet().forEach(entry -> p.add(entry.getKey(), entry.getValue()));
        return p;
    }

    static JsonObject set(String output, String expectedOutput, String input, String expectedInput) {
        return set(output, expectedOutput, input, expectedInput, "expect");
    }

    /** {@code expectKey} "expect" nests the expected entries; anything else is a spelling the schema must refuse. */
    static JsonObject set(String output, String expectedOutput, String input, String expectedInput, String expectKey) {
        JsonObject p = target();
        if (output != null) p.add("output", json(output));
        if (input != null) p.add("input", json(input));
        JsonObject expect = new JsonObject();
        if (expectedOutput != null) expect.add("output", json(expectedOutput));
        if (expectedInput != null) expect.add("input", json(expectedInput));
        if (expectKey.equals("expect")) {
            if (expect.size() > 0) p.add("expect", expect);
        } else if (expectedOutput != null) p.add(expectKey, json(expectedOutput));
        return p;
    }

    static JsonObject sidechain() {
        return json("{\"target\":{\"kind\":\"sidechain\",\"device\":" + DEVICE
                + ",\"index\":0,\"id\":\"SIDECHAIN\"},\"source\":{\"kind\":\"pre_fader\",\"track\":"
                + HostFixture.track(B)
                + "},\"expect\":{\"source\":{\"kind\":\"none\"}}}");
    }

    static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    static void ok(String m, JsonObject p) {
        assertDoesNotThrow(() -> validated(m, p), "rejected valid " + p);
    }

    static void bad(String m, JsonObject p) {
        assertRpcError("invalid_params", () -> validated(m, p));
    }

    private static void validated(String m, JsonObject p) {
        RoutingModule.validateParams(m, HostBridge.checkSchema(HostFixture.SPECS, m, p));
    }

    @Test
    void entryTrackRefsGetTheirPublicNames() {
        JsonObject result = JsonParser.parseString(
                        "{\"output\":{\"entry\":{\"kind\":\"track_audio_input\",\"track\":{\"kind\":\"track\",\"id\":\"m\"}},\"choices\":["
                                + "{\"entry\":{\"kind\":\"track_notes\",\"track\":{\"kind\":\"track\",\"id\":\"gone\"}},\"name\":\"\"}]},"
                                + "\"components\":[{\"source\":{\"kind\":\"pre_fader\",\"track\":{\"kind\":\"track\",\"id\":\"a\"}}}]}")
                .getAsJsonObject();
        RoutingModule.nameTracks(result, Map.of("m", "Master", "a", "Drums"));
        JsonObject output = result.getAsJsonObject("output");
        assertEquals(
                json("{\"kind\":\"track\",\"id\":\"m\",\"name\":\"Master\"}"),
                output.getAsJsonObject("entry").getAsJsonObject("track"),
                "the current entry's track is a full track ref");
        assertTrue(
                !output.getAsJsonArray("choices")
                        .get(0)
                        .getAsJsonObject()
                        .getAsJsonObject("entry")
                        .getAsJsonObject("track")
                        .has("name"),
                "no name for a track outside the window");
        assertEquals(
                "Drums",
                result.getAsJsonArray("components")
                        .get(0)
                        .getAsJsonObject()
                        .getAsJsonObject("source")
                        .getAsJsonObject("track")
                        .get("name")
                        .getAsString(),
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
                DeviceSources.Source device, List<RoutingModule.TrackRef> bank, boolean choices, Runnable check) {
            calls++;
            reads++;
            check.run();
            lastDevice = device;
            JsonObject out = new JsonObject();
            out.add("components", new JsonArray());
            return out;
        }

        public JsonObject sidechainSet(
                DeviceSources.Source device,
                List<RoutingModule.TrackRef> bank,
                JsonObject p,
                Runnable check,
                Runnable before) {
            lastDevice = device;
            JsonObject out = write(check, before);
            JsonObject component = new JsonObject();
            component.add("ref", p.getAsJsonObject("target"));
            out.add("component", component);
            return out;
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
    void unboundNativeAccessIsRefused() {
        HostFixture host = new HostFixture();
        RoutingModule module = host.services.routing(HostFixture.SPECS, null);
        HostOperation op = host.submit(module, "tracks.routing.get", target());
        host.next();
        assertRpcError("host_mismatch", op);
    }
}
