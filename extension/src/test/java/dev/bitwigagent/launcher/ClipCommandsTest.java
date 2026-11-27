package dev.bitwigagent.launcher;

import static dev.bitwigagent.RpcAssert.*;
import static dev.bitwigagent.TestJson.*;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import dev.bitwigagent.core.Refs;
import dev.bitwigagent.core.RpcException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Bitwig's clip commands on the launcher fixture; consumer tests, never native-host evidence. */
final class ClipCommandsTest {
    /** Clips named Riff in the given scenes, read natively, with the given kind; nothing selected yet. */
    private static LauncherFake clips(ClipCommands.Kind kind, int... scenes) {
        LauncherFake f = LauncherFake.withNativeNotes();
        for (int scene : scenes) {
            f.slots[scene].content = true;
            f.slots[scene].name = "Riff";
            f.put(scene, 0, 0, 60, 100, 1);
            f.put(scene, 0, 1, 64, 90, 1);
        }
        f.commands.kind = kind;
        f.selected = -1;
        return f;
    }

    private static JsonArray targets(LauncherFake f, int... scenes) {
        JsonArray out = new JsonArray();
        for (int scene : scenes) out.add(f.clip(scene));
        return out;
    }

    private static JsonObject transform(LauncherFake f, String op, int... scenes) {
        return f.call("clips.transform", params("targets", targets(f, scenes), "op", op));
    }

    private static JsonObject entry(JsonObject out, int i) {
        return out.getAsJsonArray("entries").get(i).getAsJsonObject();
    }

    private static List<String> strings(JsonArray array) {
        List<String> out = new ArrayList<>();
        for (JsonElement e : array) out.add(e.getAsString());
        return out;
    }

    private static List<Integer> selections(LauncherFake f) {
        List<Integer> out = new ArrayList<>();
        f.onSelect = () -> out.add(f.selected);
        return out;
    }

    @Test
    void aNoteOpIsDispatchedUnderLauncherFocusAndVerifiedByTheChangedNotes() {
        LauncherFake f = clips(ClipCommands.Kind.NOTES, 3);
        List<Integer> selected = selections(f);
        f.onAction = id -> f.put(3, 0, 3, 67, 80, 1);
        JsonObject entry = entry(transform(f, "reverse", 3), 0);
        assertEquals("applied", entry.get("outcome").getAsString());
        assertEquals("native_readback", entry.get("verification").getAsString());
        assertEquals(List.of("reverse"), f.actions);
        assertEquals(List.of(3), selected, "the target is selected; with no previous cursor clip nothing is put back");
        assertEquals(2, entry.getAsJsonObject("compared").get("notesBefore").getAsInt());
        assertEquals(3, entry.getAsJsonObject("compared").get("notesAfter").getAsInt());
        assertEquals("reverse", entry.get("op").getAsString());
        assertEquals("replaced", entry.get("selectionSideEffect").getAsString());
        assertEquals("restored", entry.get("focusSideEffect").getAsString());
        assertEquals("device panel", f.commands.focus);
        assertEquals(2, f.commands.focusRequests, "a pre-flight before selecting, the re-check before invoking");
        assertEquals(f.clip(3), entry.get("ref"));
    }

    @Test
    void thePreviousCursorClipAndFocusArePutBack() {
        LauncherFake f = clips(ClipCommands.Kind.AUDIO, 2, 3);
        f.slots[2].name = "Other";
        f.selected = 2;
        List<Integer> selected = selections(f);
        JsonObject out = f.call("clips.gain", params("target", f.clip(3), "deltaDb", 1));
        assertEquals("sent", out.get("outcome").getAsString());
        assertEquals(List.of(3, 2), selected);
        assertEquals("restored", out.get("selectionSideEffect").getAsString());
        assertEquals("restored", out.get("focusSideEffect").getAsString());
        f.commands.refocusable = false;
        out = f.call("clips.gain", params("target", f.clip(3), "deltaDb", 1));
        assertEquals("launcher", out.get("focusSideEffect").getAsString());
    }

    @Test
    void unchangedNotesAreUnknownAndHoldTheSlot() {
        LauncherFake f = clips(ClipCommands.Kind.NOTES, 0);
        JsonObject entry = entry(transform(f, "legato", 0), 0);
        assertEquals("unknown", entry.get("outcome").getAsString());
        assertEquals("not_observed", entry.get("reason").getAsString());
        assertTrue(entry.has("next"));
        assertEquals("replaced", entry.get("selectionSideEffect").getAsString());
        assertEquals(List.of("legato"), f.actions);
        assertRpcError(
                "unresolved", f.startAndDrain("clips.transform", params("targets", targets(f, 0), "op", "legato")));
    }

    @Test
    void aLauncherFocusCannotReachIsRefusedBeforeAnythingIsSelected() {
        LauncherFake f = clips(ClipCommands.Kind.AUDIO, 2, 3);
        f.commands.focusable = false;
        assertRpcError("not_ready", f.startAndDrain("clips.gain", params("target", f.clip(2), "deltaDb", 6)));
        JsonObject out = transform(f, "normalize", 2, 3);
        assertEquals("not_applied", out.get("outcome").getAsString());
        assertEquals("not_ready", entry(out, 0).get("reason").getAsString());
        assertEquals("not_reached", entry(out, 1).get("reason").getAsString());
        assertEquals(List.of(), f.actions);
        assertEquals(-1, f.selected);
    }

    @Test
    void aFailureAfterTheSelectionStillReportsWhatWasPutBack() {
        LauncherFake f = clips(ClipCommands.Kind.AUDIO, 2);
        f.onSelect = () -> f.commands.focusable = false;
        JsonObject out = f.call("clips.gain", params("target", f.clip(2), "deltaDb", 6));
        assertEquals("not_applied", out.get("outcome").getAsString());
        assertEquals("not_ready", out.get("reason").getAsString());
        assertEquals("replaced", out.get("selectionSideEffect").getAsString());
        assertEquals("restored", out.get("focusSideEffect").getAsString());
        assertEquals(List.of(), f.actions);
    }

    @Test
    void anUnverifiableOpOnSeveralClipsIsSentOncePerClip() {
        LauncherFake f = clips(ClipCommands.Kind.AUDIO, 1, 2, 3);
        List<Integer> selected = selections(f);
        JsonObject out = transform(f, "normalize", 1, 2, 3);
        assertEquals("sent", out.get("outcome").getAsString());
        for (JsonElement entry : out.getAsJsonArray("entries")) {
            assertEquals("sent", entry.getAsJsonObject().get("outcome").getAsString());
            assertFalse(entry.getAsJsonObject().has("verification"));
        }
        assertEquals(List.of("normalize", "normalize", "normalize"), f.actions);
        assertEquals(List.of(1, 2, 3), selected);
    }

    @Test
    void eachEntryOfABatchPutsBackTheUsersOwnCursorClip() {
        LauncherFake f = clips(ClipCommands.Kind.AUDIO, 0, 1, 2);
        f.slots[0].name = "Other";
        f.selected = 0;
        List<Integer> selected = selections(f);
        JsonObject out = transform(f, "normalize", 1, 2);
        assertEquals(List.of(1, 0, 2, 0), selected);
        assertEquals("restored", entry(out, 1).get("selectionSideEffect").getAsString());
        assertEquals("restored", entry(out, 1).get("focusSideEffect").getAsString());
    }

    @Test
    void everyClipKindIsCheckedBeforeAnyIsWritten() {
        LauncherFake f = clips(ClipCommands.Kind.AUDIO, 1, 2);
        f.commands.kinds.put(2, ClipCommands.Kind.NOTES);
        RpcException refused = assertRpcError(
                "unsupported_scope",
                f.startAndDrain("clips.transform", params("targets", targets(f, 1, 2), "op", "normalize")));
        assertEquals(1, refused.entries.size());
        assertEquals(1, refused.entries.get(0).getAsJsonObject().get("index").getAsInt());
        assertEquals(List.of(), f.actions);
        assertEquals(-1, f.selected);
        assertEquals(0, f.commands.focusRequests);
    }

    @Test
    void aClipOfAKindTheCommandDoesNotTakeIsRefusedBeforeAnythingIsSelected() {
        LauncherFake f = clips(ClipCommands.Kind.NOTES, 1);
        assertRpcError("unsupported_scope", f.startAndDrain("clips.gain", params("target", f.clip(1), "deltaDb", 3)));
        f.commands.kind = null;
        assertRpcError("unsupported_scope", f.startAndDrain("clips.gain", params("target", f.clip(1), "deltaDb", 3)));
        assertEquals(List.of(), f.actions);
        assertEquals(-1, f.selected);
        assertEquals(0, f.commands.focusRequests);
    }

    @Test
    void duplicateIsVerifiedByTheNewClipOfTheSameName() {
        for (boolean alias : new boolean[] {false, true}) {
            LauncherFake f = clips(ClipCommands.Kind.NOTES, 4);
            f.onAction = id -> {
                f.slots[5].name = "Riff";
                f.observeContent(5, true);
            };
            JsonObject p = params("target", f.clip(4));
            if (alias) p.addProperty("alias", true);
            JsonObject out = f.call("clips.duplicate", p);
            assertEquals("applied", out.get("outcome").getAsString());
            assertEquals("api_readback", out.get("verification").getAsString());
            JsonArray created = new JsonArray();
            created.add(f.clip(5));
            assertEquals(created, out.get("created"));
            assertEquals(List.of(alias ? "Duplicate Reference" : "Duplicate"), f.actions);
        }
    }

    @Test
    void aNewClipOfAnotherNameIsNotTheDuplicate() {
        LauncherFake f = clips(ClipCommands.Kind.AUDIO, 0);
        f.onAction = id -> {
            f.slots[1].name = "Other";
            f.observeContent(1, true);
        };
        JsonObject out = f.call("clips.duplicate", params("target", f.clip(0)));
        assertEquals("unknown", out.get("outcome").getAsString());
        assertEquals("not_observed", out.get("reason").getAsString());
    }

    @Test
    void aDuplicateInTheLastRowOfTheWindowIsRefused() {
        LauncherFake f = clips(ClipCommands.Kind.NOTES, 15);
        assertRpcError("outside_window", f.startAndDrain("clips.duplicate", params("target", f.clip(15))));
        assertEquals(List.of(), f.actions);
        assertEquals(-1, f.selected);
    }

    @Test
    void sliceIsVerifiedByTheNewTrackAfterTheSource() {
        LauncherFake f = clips(ClipCommands.Kind.AUDIO, 0);
        f.onAction = id -> f.trackCount = 2;
        JsonObject out = f.call("clips.slice", params("target", f.clip(0), "to", "slicedSampler"));
        assertEquals("applied", out.get("outcome").getAsString());
        assertEquals("api_readback", out.get("verification").getAsString());
        assertEquals(Refs.track("", "Track"), out.get("track"));
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
}
