package dev.bitwigagent;

import com.google.gson.*;
import java.util.*;
import java.util.concurrent.CompletionException;

/** Validation, safety, window and quarantine around a simulated native routing adapter; not native binding evidence. */
public final class RoutingModuleTest {
    static final String A = TransportMixFixture.A, B = TransportMixFixture.B, C = TransportMixFixture.C;
    static int checks;

    public static void main(String[] args) {
        validation();
        helpers();
        trackNamesAnnotateEntriesWithoutChangingThem();
        getIsReadOnlyAndAllowsPlayingTransport();
        setNeedsStoppedTransportAndObservedTargets();
        unknownWriteQuarantinesWritesButNotReads();
        preDispatchRefusalDoesNotQuarantine();
        sidechainUsesTheObservedRootDevice();
        System.out.println("RoutingModuleTest: " + checks + " assertions (simulated adapter only)");
    }

    static void validation() {
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

    static void helpers() {
        check(
                RoutingModule.sourceKinds(RoutingModule.trackSourceGroups("AUDIO"))
                        .equals(List.of("none", "pre_fader", "post_fader")),
                "audio track: pre/post fader");
        check(
                RoutingModule.sourceKinds(RoutingModule.trackSourceGroups("INSTRUMENT"))
                        .equals(List.of("none", "track_notes", "all_ins", "computer_keyboard", "onscreen_keyboard")),
                "instrument track: note sources");
        check(
                RoutingModule.sourceKinds(RoutingModule.trackSourceGroups("HYBRID"))
                                .size()
                        == 7,
                "hybrid track: every v1 source");
        check(
                RoutingModule.sourceKinds(List.of("HARDWARE_AUDIO_SOURCES")).equals(List.of("none")),
                "hardware audio is deferred");
        check(RoutingModule.trackSourceGroups("GROUP").isEmpty(), "unknown native kind admits nothing");
        JsonObject observed = json("{\"kind\":\"pre_fader\",\"trackId\":\"" + B + "\",\"name\":\"Pre\"}");
        check(
                RoutingModule.sameEntry(
                        json("{\"kind\":\"pre_fader\",\"trackId\":\"" + B.toUpperCase(Locale.ROOT) + "\"}"), observed),
                "CAS ignores UUID case and display name");
        check(
                !RoutingModule.sameEntry(json("{\"kind\":\"post_fader\",\"trackId\":\"" + B + "\"}"), observed),
                "CAS compares kind");
        check(
                !RoutingModule.sameEntry(json("{\"kind\":\"pre_fader\",\"trackId\":\"" + C + "\"}"), observed),
                "CAS compares trackId");
        JsonObject other = json("{\"kind\":\"other\",\"name\":\"Out 1\",\"nativeType\":\"x\"}");
        check(
                RoutingModule.sameEntry(json("{\"kind\":\"other\",\"name\":\"Out 1\"}"), other),
                "other compares exact name");
        check(
                !RoutingModule.sameEntry(json("{\"kind\":\"other\",\"name\":\"Out 2\"}"), other),
                "other with another name differs");
        check(
                RoutingModule.sameEntry(json("{\"kind\":\"other\",\"name\":\"Out 1\",\"nativeType\":\"x\"}"), other),
                "other copied verbatim matches");
        check(
                !RoutingModule.sameEntry(json("{\"kind\":\"other\",\"name\":\"Out 1\",\"nativeType\":\"y\"}"), other),
                "same name on another native type differs");
        check(
                RoutingModule.sameEntry(json("{\"kind\":\"none\"}"), json("{\"kind\":\"none\",\"name\":null}")),
                "none matches none");
    }

    static void getIsReadOnlyAndAllowsPlayingTransport() {
        Fixture f = new Fixture();
        f.host.transport.child("isPlaying").emit(true);
        JsonObject result = f.call("tracks.routing.get", json("{\"trackId\":\"" + A + "\",\"includeChoices\":true}"));
        check(
                result.get("status").getAsString().equals("observed") && f.adapter.reads == 1 && f.adapter.writes == 0,
                "get reads without writing");
        check(
                f.adapter.lastChoices && f.adapter.lastSelf.id.equals(A) && f.adapter.lastBank.size() == 2,
                "get passes the whole observed bank and includeChoices");
        check(
                f.adapter.lastBank.get(1).id.equals(B)
                        && f.adapter.lastBank.get(1).type.equals("Audio"),
                "bank rows carry public UUID and type");
    }

    static void setNeedsStoppedTransportAndObservedTargets() {
        Fixture playing = new Fixture();
        playing.host.transport.child("isPlaying").emit(true);
        expect("unsafe_state", playing.start("tracks.routing.set", outputTo(B)));
        check(playing.adapter.calls == 0, "playing transport blocks routing writes before native access");
        Fixture outside = new Fixture();
        expect("outside_window", outside.start("tracks.routing.set", outputTo(C)));
        check(outside.adapter.calls == 0, "a destination outside the bank window is refused before native access");
        Fixture f = new Fixture();
        JsonObject result = f.call("tracks.routing.set", outputTo(B));
        check(
                f.adapter.writes == 1 && result.get("status").getAsString().equals("applied"),
                "stopped transport admits the write");
        check(f.module.unresolved().isEmpty(), "an observed write clears the pending/quarantine state");
    }

    static void unknownWriteQuarantinesWritesButNotReads() {
        Fixture f = new Fixture();
        f.adapter.unknown = true;
        JsonObject result = f.call("tracks.routing.set", outputTo(B));
        check(result.get("completion").getAsString().equals("unknown"), "unknown readback is returned, not hidden");
        check(!f.module.unresolved().isEmpty(), "unknown readback quarantines routing writes");
        expect("unresolved_mutation", f.start("tracks.routing.set", outputTo(B)));
        check(
                f.call("tracks.routing.get", json("{\"trackId\":\"" + A + "\"}"))
                        .get("status")
                        .getAsString()
                        .equals("observed"),
                "reads stay available");
        Fixture thrown = new Fixture();
        thrown.adapter.failAfterWrite = true;
        HostOperation failed = thrown.start("tracks.routing.set", outputTo(B));
        thrown.drain();
        check(failed.dispatched, "failure happened after dispatch");
        try {
            failed.result.join();
            throw new AssertionError("expected failure");
        } catch (CompletionException e) {
            check(((RpcException) e.getCause()).outcome.equals("unknown"), "post-dispatch failure is unknown");
        }
        expect("unresolved_mutation", thrown.start("devices.sidechain.set", sidechain()));
    }

    static void preDispatchRefusalDoesNotQuarantine() {
        Fixture f = new Fixture();
        f.adapter.refuse = "kind_mismatch";
        HostOperation op = f.start("tracks.routing.set", outputTo(B));
        f.drain();
        expect("kind_mismatch", op);
        check(
                !op.dispatched && f.module.unresolved().isEmpty(),
                "pre-dispatch refusal is not_applied and does not quarantine");
        try {
            op.result.join();
        } catch (CompletionException e) {
            check(((RpcException) e.getCause()).outcome.equals("not_applied"), "refusal outcome not_applied");
        }
    }

    static void sidechainUsesTheObservedRootDevice() {
        Fixture f = new Fixture();
        f.observeDevice("Compressor");
        JsonObject get = json("{\"trackId\":\"" + A + "\",\"deviceIndex\":0,\"expectedDeviceName\":\"Compressor\"}");
        f.call("devices.sidechain.get", get);
        check(
                f.adapter.lastDevice != null && f.adapter.reads == 1,
                "sidechain get passes the observed root device proxy");
        JsonObject wrong = json("{\"trackId\":\"" + A + "\",\"deviceIndex\":0,\"expectedDeviceName\":\"Gate\"}");
        expect("stale_target", f.start("devices.sidechain.get", wrong));
        JsonObject p = sidechain();
        p.addProperty("expectedDeviceName", "Compressor");
        f.call("devices.sidechain.set", p);
        check(f.adapter.writes == 1, "sidechain set reaches the adapter with a stopped transport");
        Fixture missing = new Fixture();
        expect("stale_target", missing.start("devices.sidechain.get", get));
        check(missing.adapter.calls == 0, "an unobserved device is refused before native access");
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
        try {
            RoutingModule.validateParams(m, p);
            checks++;
        } catch (RpcException e) {
            throw new AssertionError("rejected valid " + p + ": " + e.getMessage());
        }
    }

    static void bad(String m, JsonObject p) {
        try {
            RoutingModule.validateParams(m, p);
            throw new AssertionError("accepted invalid " + p);
        } catch (RpcException e) {
            check(e.code.equals("invalid_params"), "invalid params: " + p);
        }
    }

    static void trackNamesAnnotateEntriesWithoutChangingThem() {
        JsonObject result = JsonParser.parseString(
                        "{\"output\":{\"entry\":{\"kind\":\"track_audio_input\",\"trackId\":\"m\"},\"choices\":["
                                + "{\"entry\":{\"kind\":\"post_fader\",\"trackId\":\"a\"},\"name\":\"Post-Fader\"},{\"entry\":{\"kind\":\"none\"},\"name\":null},"
                                + "{\"entry\":{\"kind\":\"track_notes\",\"trackId\":\"gone\"},\"name\":\"\"}]},"
                                + "\"components\":[{\"source\":{\"kind\":\"pre_fader\",\"trackId\":\"a\"}}]}")
                .getAsJsonObject();
        RoutingModule.annotateTrackNames(result, Map.of("m", "Master", "a", "Drums"));
        JsonObject output = result.getAsJsonObject("output");
        JsonArray choices = output.getAsJsonArray("choices");
        check(output.get("trackName").getAsString().equals("Master"), "current entry gets its track name");
        check(
                choices.get(0).getAsJsonObject().get("trackName").getAsString().equals("Drums"),
                "choice row gets the track name");
        check(
                !choices.get(0).getAsJsonObject().getAsJsonObject("entry").has("trackName"),
                "entry stays verbatim for expected*");
        check(
                !choices.get(1).getAsJsonObject().has("trackName")
                        && !choices.get(2).getAsJsonObject().has("trackName"),
                "no name for none or unknown tracks");
        check(
                result.getAsJsonArray("components")
                        .get(0)
                        .getAsJsonObject()
                        .get("sourceTrackName")
                        .getAsString()
                        .equals("Drums"),
                "sidechain source named");
    }

    static void check(boolean x, String text) {
        checks++;
        if (!x) throw new AssertionError(text);
    }

    static void expect(String code, HostOperation op) {
        try {
            op.result.join();
            throw new AssertionError("expected " + code);
        } catch (CompletionException e) {
            check(
                    e.getCause() instanceof RpcException && ((RpcException) e.getCause()).code.equals(code),
                    "error " + code + ": " + e.getCause());
        }
    }

    static final class Fixture {
        final TransportMixFixture host = new TransportMixFixture();
        final Adapter adapter = new Adapter();
        final RoutingModule module = new RoutingModule(host.services, adapter);

        void observeDevice(String name) {
            TransportMixFixture.Node devices = host.tracks[0].child("createDeviceBank"), device = devices.item(0);
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
        String refuse;
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
            return HostServices.observed();
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
            return HostServices.observed();
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
            if (refuse != null) throw HostServices.failure(refuse, "simulated refusal");
            before.run();
            writes++;
            if (failAfterWrite) throw new RpcException("routing_unavailable", "simulated native failure", "unknown");
            JsonObject out = HostServices.observed();
            out.addProperty("status", unknown ? "unknown" : "applied");
            out.addProperty("completion", unknown ? "unknown" : "observed");
            return out;
        }
    }
}
