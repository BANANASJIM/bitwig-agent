package dev.bitwigagent.launcher;

import static dev.bitwigagent.RpcAssert.*;
import static dev.bitwigagent.TestJson.*;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Bitwig's clip commands on the launcher fixture; consumer tests, never native-host evidence. */
final class ClipCommandsTest {
    /** Clips named Riff in the given scenes, read natively, with the given kind. */
    private static LauncherFake clips(ClipCommands.Kind kind, int... scenes) {
        LauncherFake f = LauncherFake.withNativeNotes();
        for (int scene : scenes) {
            f.slots[scene].content = true;
            f.slots[scene].name = "Riff";
            f.put(scene, 0, 0, 60, 100, 1);
            f.put(scene, 0, 1, 64, 90, 1);
        }
        f.commands.kind = kind;
        return f;
    }

    private static JsonObject transform(LauncherFake f, String op, int... scenes) {
        JsonArray targets = new JsonArray();
        for (int scene : scenes) targets.add(f.clip(scene));
        return f.call("clips.transform", params("targets", targets, "op", op));
    }

    private static List<String> strings(JsonArray array) {
        List<String> out = new java.util.ArrayList<>();
        for (JsonElement e : array) out.add(e.getAsString());
        return out;
    }

    @Test
    void aNoteOpIsDispatchedUnderLauncherFocusAndVerifiedByTheChangedNotes() {
        LauncherFake f = clips(ClipCommands.Kind.NOTES, 0);
        f.onAction = id -> f.put(0, 0, 3, 67, 80, 1);
        JsonObject entry =
                transform(f, "reverse", 0).getAsJsonArray("entries").get(0).getAsJsonObject();
        assertEquals("applied", entry.get("outcome").getAsString());
        assertEquals("native_readback", entry.get("verification").getAsString());
        assertEquals(List.of("reverse"), f.actions);
        assertEquals(1, f.commands.focusRequests);
        assertEquals(0, f.selected);
        assertEquals(2, entry.getAsJsonObject("compared").get("notesBefore").getAsInt());
        assertEquals(3, entry.getAsJsonObject("compared").get("notesAfter").getAsInt());
        assertEquals("reverse", entry.get("op").getAsString());
        assertEquals("replaced", entry.get("selectionSideEffect").getAsString());
        assertEquals(f.clip(0), entry.get("ref"));
    }

    @Test
    void unchangedNotesAreUnknownAndHoldTheSlot() {
        LauncherFake f = clips(ClipCommands.Kind.NOTES, 0);
        JsonObject entry =
                transform(f, "legato", 0).getAsJsonArray("entries").get(0).getAsJsonObject();
        assertEquals("unknown", entry.get("outcome").getAsString());
        assertEquals("not_observed", entry.get("reason").getAsString());
        assertTrue(entry.has("next"));
        assertEquals(List.of("legato"), f.actions);
        assertRpcError(
                "unresolved",
                f.startAndDrain("clips.transform", params("targets", arrayOf(f.clip(0)), "op", "legato")));
    }

    @Test
    void focusThatDoesNotReachTheLauncherSendsNothingAndStopsTheBatch() {
        LauncherFake f = clips(ClipCommands.Kind.AUDIO, 0, 1);
        f.commands.focusable = false;
        JsonObject out = transform(f, "normalize", 0, 1);
        assertEquals("not_applied", out.get("outcome").getAsString());
        JsonArray entries = out.getAsJsonArray("entries");
        assertEquals("not_ready", entries.get(0).getAsJsonObject().get("reason").getAsString());
        assertEquals(
                "not_reached", entries.get(1).getAsJsonObject().get("reason").getAsString());
        assertEquals(List.of(), f.actions);
    }

    @Test
    void anUnverifiableOpOnSeveralClipsIsSentOncePerClip() {
        LauncherFake f = clips(ClipCommands.Kind.AUDIO, 0, 1, 2);
        JsonObject out = transform(f, "normalize", 0, 1, 2);
        assertEquals("sent", out.get("outcome").getAsString());
        for (JsonElement entry : out.getAsJsonArray("entries")) {
            assertEquals("sent", entry.getAsJsonObject().get("outcome").getAsString());
            assertFalse(entry.getAsJsonObject().has("verification"));
        }
        assertEquals(List.of("normalize", "normalize", "normalize"), f.actions);
        assertEquals(2, f.selected);
    }

    @Test
    void aClipOfAKindTheOpDoesNotTakeIsRefusedBeforeAnythingIsSent() {
        LauncherFake f = clips(ClipCommands.Kind.AUDIO, 0);
        JsonObject entry =
                transform(f, "legato", 0).getAsJsonArray("entries").get(0).getAsJsonObject();
        assertEquals("not_applied", entry.get("outcome").getAsString());
        assertEquals("unsupported_scope", entry.get("reason").getAsString());
        f.commands.kind = ClipCommands.Kind.NOTES;
        assertRpcError("unsupported_scope", f.startAndDrain("clips.gain", params("target", f.clip(0), "deltaDb", 3)));
        assertEquals(List.of(), f.actions);
        assertEquals(0, f.commands.focusRequests);
    }

    @Test
    void duplicateIsVerifiedByTheNewClipInTheSlotBelow() {
        for (boolean alias : new boolean[] {false, true}) {
            LauncherFake f = clips(ClipCommands.Kind.NOTES, 0);
            f.onAction = id -> {
                f.slots[1].name = "Riff";
                f.observeContent(1, true);
            };
            JsonObject p = params("target", f.clip(0));
            if (alias) p.addProperty("alias", true);
            JsonObject out = f.call("clips.duplicate", p);
            assertEquals("applied", out.get("outcome").getAsString());
            assertEquals("api_readback", out.get("verification").getAsString());
            assertEquals(arrayOf(f.clip(1)), out.get("created"));
            assertEquals(List.of(alias ? "Duplicate Reference" : "Duplicate"), f.actions);
        }
    }

    @Test
    void aDuplicateThatNeverAppearsIsUnknown() {
        LauncherFake f = clips(ClipCommands.Kind.AUDIO, 0);
        JsonObject out = f.call("clips.duplicate", params("target", f.clip(0)));
        assertEquals("unknown", out.get("outcome").getAsString());
        assertEquals("not_observed", out.get("reason").getAsString());
    }

    @Test
    void sliceIsVerifiedByTheNewTrack() {
        LauncherFake f = clips(ClipCommands.Kind.AUDIO, 0);
        f.onAction = id -> f.trackCount = 2;
        JsonObject out = f.call("clips.slice", params("target", f.clip(0), "to", "slicedSampler"));
        assertEquals("applied", out.get("outcome").getAsString());
        assertEquals("api_readback", out.get("verification").getAsString());
        assertEquals(dev.bitwigagent.core.Refs.track("", "Track"), out.get("track"));
        assertEquals(List.of("slice_to_sliced_sampler_track"), f.actions);
    }

    @Test
    void stretchAndGainAreSentAsBitwigsCommands() {
        LauncherFake f = clips(ClipCommands.Kind.AUDIO, 0);
        JsonObject out = f.call("clips.stretch", params("target", f.clip(0), "tempo", "project"));
        assertEquals("sent", out.get("outcome").getAsString());
        assertEquals(List.of("stretch_to_project_tempo"), strings(out.getAsJsonArray("actions")));
        out = f.call("clips.gain", params("target", f.clip(0), "deltaDb", 13));
        assertEquals("sent", out.get("outcome").getAsString());
        assertEquals(
                List.of("increase_gain_6db", "increase_gain_6db", "increase_gain_1db"),
                strings(out.getAsJsonArray("actions")));
        out = f.call("clips.gain", params("target", f.clip(0), "deltaDb", -7));
        assertEquals(List.of("reduce_gain_6db", "reduce_gain_1db"), strings(out.getAsJsonArray("actions")));
        out = f.call("clips.stretch", params("target", f.clip(0), "tempo", "detected"));
        assertEquals(List.of("stretch_to_analyzed_tempo"), strings(out.getAsJsonArray("actions")));
        assertRpcError("invalid_params", f.start("clips.gain", params("target", f.clip(0), "deltaDb", 0)));
        assertRpcError("invalid_params", f.start("clips.gain", params("target", f.clip(0), "deltaDb", 25)));
    }

    private static JsonArray arrayOf(JsonElement... items) {
        JsonArray out = new JsonArray();
        for (JsonElement item : items) out.add(item);
        return out;
    }
}
