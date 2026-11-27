package dev.bitwigagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.bitwigagent.core.HostModule;
import dev.bitwigagent.core.HostOperation;
import dev.bitwigagent.core.Refs;
import dev.bitwigagent.core.Results;
import dev.bitwigagent.launcher.LauncherFake;
import dev.bitwigagent.project.FakeKeySignature;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * The result contract on the fixture layer: an applied example of every host write, run on the simulated host,
 * satisfies the leaf rule (every applied leaf names its verification). A new write fails here until it gets an
 * example or is listed as missing one.
 */
final class ResultContractTest {
    /** One write on the fixture that applies, after {@code setup} prepares the simulated host. */
    private static final class Example {
        final Function<TestServices, HostModule> module;
        final Consumer<HostFixture> setup;
        final String params;

        Example(Function<TestServices, HostModule> module, String params) {
            this(module, f -> {}, params);
        }

        Example(Function<TestServices, HostModule> module, Consumer<HostFixture> setup, String params) {
            this.module = module;
            this.setup = setup;
            this.params = params;
        }
    }

    private static final String TRACK_B = "\"target\":" + HostFixture.track(HostFixture.B);

    private static final Function<TestServices, HostModule> LIFECYCLE = s -> s.trackLifecycle(s.sharedMix(), null);

    private static final Map<String, Example> EXAMPLES = Map.of(
            "transport.set", new Example(TestServices::transportModule, "{\"tempo\":133}"),
            "project.key.set",
                    new Example(s -> s.keySignature(new FakeKeySignature()), "{\"root\":\"D\",\"scale\":\"Minor\"}"),
            "tracks.set", new Example(TestServices::mix, "{" + TRACK_B + ",\"name\":\"Renamed\",\"mute\":true}"),
            "tracks.sends.set",
                    new Example(
                            TestServices::mix,
                            ResultContractTest::sendable,
                            "{\"target\":" + Refs.send(HostFixture.track(HostFixture.A), 0, "Reverb")
                                    + ",\"enabled\":false}"),
            "tracks.insert",
                    new Example(
                            LIFECYCLE,
                            f -> f.create = factory -> f.addTrack(2, HostFixture.C, "Audio"),
                            "{\"type\":\"audio\",\"name\":\"Created\"}"),
            "tracks.delete",
                    new Example(
                            LIFECYCLE,
                            f -> f.delete = slot -> f.removeTrack(slot, 1),
                            "{\"target\":" + HostFixture.track(HostFixture.B) + "}"));

    /**
     * Host writes with no fixture example here yet, to fill: each is still held to the leaf rule wherever a module
     * test completes one (HostOperation asserts it under -ea), but nothing here proves an applied example exists.
     */
    private static final Set<String> MISSING = Set.of(
            "ack",
            "actions.invoke",
            "automation.points.set",
            "clips.copy",
            "clips.delete",
            "clips.import",
            "clips.launch",
            "clips.move",
            "clips.quantize",
            "clips.set",
            "clips.transform",
            "clips.transpose",
            "devices.delete",
            "devices.insert",
            "devices.move",
            "devices.remote.define",
            "devices.remote.delete",
            "devices.select",
            "devices.set",
            "devices.sidechain.set",
            "export.audio",
            "grid.connections.set",
            "grid.modules.delete",
            "grid.modules.insert",
            "grid.modules.move",
            "grid.modules.set",
            "grid.parameters.set",
            "grid.voicing.set",
            "launcher.stop",
            "markers.delete",
            "markers.insert",
            "markers.set",
            "modulators.delete",
            "modulators.insert",
            "modulators.map",
            "modulators.unmap",
            "notes.get",
            "parameters.set",
            "project.activateEngine",
            "project.save",
            "project.saveAs",
            "scenes.delete",
            "scenes.duplicate",
            "scenes.insert",
            "scenes.launch",
            "scenes.set",
            "tracks.bounce",
            "tracks.group",
            "tracks.routing.set");

    /**
     * Launcher note writes, run on the launcher fixture with the native note binding: params name the clip at scene 0
     * (an existing clip named Riff for notes.set, an empty slot for clips.insert).
     */
    private static final Map<String, Function<LauncherFake, JsonObject>> LAUNCHER = Map.of(
            "notes.set",
                    f -> HostFixture.json("{\"target\":" + f.clip(0)
                            + ",\"notes\":[{\"beat\":0,\"pitch\":60,\"velocity\":90,\"duration\":1}]}"),
            "clips.insert",
                    f -> HostFixture.json("{\"to\":" + f.slot(0) + ",\"loop\":{\"length\":4},\"name\":\"Riff\","
                            + "\"notes\":[{\"beat\":0,\"pitch\":60,\"velocity\":90,\"duration\":1}]}"));

    /** Host writes whose fixture example answers {@code sent}: nothing is read back, so nothing to verify. */
    private static final Map<String, Example> SENT =
            Map.of("tracks.select", new Example(TestServices::mix, "{" + TRACK_B + "}"));

    /** Track A's first send feeds the effect track Reverb and reads its enabled state back. */
    private static void sendable(HostFixture f) {
        f.addTrack(2, HostFixture.C, "Effect");
        f.tracks[2].child("name").data = "Reverb";
        f.tracks[0].child("sendBank").item(0).child("isEnabled").data = true;
    }

    private static JsonObject run(String method, Example example) {
        HostFixture f = new HostFixture();
        example.setup.accept(f);
        HostOperation op = f.submit(example.module.apply(f.services), method, HostFixture.json(example.params));
        while (!op.result.isDone()) {
            assertFalse(f.tasks.isEmpty(), method + " waits with nothing scheduled");
            f.next();
        }
        return op.result.join();
    }

    private static boolean applies(JsonElement node) {
        if (node.isJsonArray()) {
            for (JsonElement child : node.getAsJsonArray()) if (applies(child)) return true;
            return false;
        }
        if (!node.isJsonObject()) return false;
        JsonObject object = node.getAsJsonObject();
        if (object.has("outcome") && object.get("outcome").getAsString().equals("applied")) return true;
        for (JsonElement child : object.asMap().values()) if (applies(child)) return true;
        return false;
    }

    @Test
    void everyHostWriteHasAnExampleOrIsListedAsMissingOne() {
        Set<String> writes = new TreeSet<>();
        for (String method : HostFixture.SPECS.methods())
            if (!HostFixture.SPECS.get(method).readOnly) writes.add(method);
        Set<String> listed = new TreeSet<>(EXAMPLES.keySet());
        listed.addAll(SENT.keySet());
        listed.addAll(LAUNCHER.keySet());
        listed.addAll(MISSING);
        assertEquals(writes, listed);
        assertEquals(
                EXAMPLES.size() + SENT.size() + LAUNCHER.size() + MISSING.size(),
                listed.size(),
                "each write is listed once");
    }

    @Test
    void everyAppliedExampleNamesItsVerificationOnEveryAppliedLeaf() {
        for (Map.Entry<String, Example> example : EXAMPLES.entrySet()) {
            JsonObject result = run(example.getKey(), example.getValue());
            assertTrue(applies(result), example.getKey() + " example does not apply: " + result);
            assertTrue(Results.leafRuleHolds(result), example.getKey() + ": " + result);
        }
        for (Map.Entry<String, Function<LauncherFake, JsonObject>> example : LAUNCHER.entrySet()) {
            LauncherFake f = LauncherFake.withNativeNotes();
            if (example.getKey().equals("notes.set")) {
                f.slots[0].content = true;
                f.slots[0].name = "Riff";
            }
            JsonObject result = f.call(example.getKey(), example.getValue().apply(f));
            assertTrue(applies(result), example.getKey() + " example does not apply: " + result);
            assertTrue(Results.leafRuleHolds(result), example.getKey() + ": " + result);
        }
        for (Map.Entry<String, Example> sent : SENT.entrySet()) {
            JsonObject result = run(sent.getKey(), sent.getValue());
            assertEquals("sent", result.get("outcome").getAsString(), sent.getKey());
            assertFalse(result.has("verification"), sent.getKey());
        }
    }
}
