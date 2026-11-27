package dev.bitwigagent.launcher;

import static dev.bitwigagent.RpcAssert.*;
import static dev.bitwigagent.TestJson.*;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import dev.bitwigagent.core.HostOperation;
import dev.bitwigagent.core.RpcException;
import dev.bitwigagent.host.LauncherStructureLock;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The one-Undo-step native note path against the in-memory fixture; never native-host evidence. */
public final class NativeNoteWriteTest {

    private static JsonObject set(double beat, int pitch, int velocity, double duration) {
        return obj("{\"op\":\"set\",\"beat\":" + beat + ",\"pitch\":" + pitch + ",\"velocity\":" + velocity
                + ",\"duration\":" + duration + "}");
    }

    private static JsonObject clear(double beat, int pitch) {
        return obj("{\"op\":\"clear\",\"beat\":" + beat + ",\"pitch\":" + pitch + "}");
    }

    private static LauncherFake riff(int length) {
        LauncherFake f = LauncherFake.withNativeNotes();
        f.slots[0].content = true;
        f.slots[0].name = "Riff";
        f.slots[0].length = length;
        return f;
    }

    private static JsonObject edit(LauncherFake f, JsonObject... edits) {
        JsonArray list = new JsonArray();
        for (JsonObject e : edits) list.add(e);
        return params("target", f.clip(0), "edits", list);
    }

    private static JsonObject eight(LauncherFake f) {
        return edit(
                f,
                set(0, 60, 100, .5),
                set(1, 62, 90, .25),
                set(2, 64, 80, .75),
                set(3, 65, 70, .225),
                set(4, 67, 110, 1),
                set(5.5, 69, 60, .5),
                set(7, 71, 127, .125),
                set(9, 72, 1, 2));
    }

    @Test
    void severalEditsAreOneNativeTransactionReadBackThroughTheGrid() {
        LauncherFake f = riff(16);
        JsonObject out = f.call("notes.set", eight(f));
        assertEquals("applied", out.get("outcome").getAsString());
        assertEquals("one_step", out.get("undo").getAsString());
        assertEquals(8, out.getAsJsonObject("summary").get("verified").getAsInt());
        assertEquals(1, f.nativeNotes.transactions, "one transaction for every note");
        assertEquals(0, f.writes, "no public per-note setter");
        assertEquals(.225, f.notes.get(LauncherFake.key(0, 0, 24, 65)).duration);
        assertFalse(f.services.selection.held(), "the cursor lease ends with the call");
        JsonObject again = f.call("notes.set", eight(f));
        assertEquals("already_matched", again.get("outcome").getAsString());
        assertEquals("none", again.get("undo").getAsString(), "a no-op opens no transaction");
        assertEquals(1, f.nativeNotes.transactions);
    }

    @Test
    void clearsChangesAndNewNotesShareTheTransaction() {
        LauncherFake f = riff(16);
        f.noteStorageStepSize = 1.0;
        f.put(0, 0, 0, 48, 100, 1);
        f.put(0, 0, 2, 50, 90, 1);
        JsonObject out = f.call("notes.set", edit(f, clear(0, 48), set(2, 50, 40, 1), set(3, 52, 100, .5)));
        assertEquals("applied", out.get("outcome").getAsString());
        assertEquals(1, f.nativeNotes.transactions);
        assertFalse(f.notes.containsKey(LauncherFake.key(0, 0, 0, 48)));
        assertEquals(40, f.notes.get(LauncherFake.key(0, 0, 2, 50)).velocity);
        assertEquals(3, out.getAsJsonObject("summary").get("dispatched").getAsInt());
    }

    @Test
    void aRefusedCellChangesAndSelectsNothing() {
        LauncherFake f = riff(16);
        f.noteStorageStepSize = .25;
        f.put(0, 0, 0, 60, 100, 4); // sustains over beat 1
        int selected = f.selected;
        HostOperation op = f.startAndDrain("notes.set", edit(f, set(2, 61, 90, 1), set(1, 60, 90, .5)));
        RpcException error = assertRpcError("overlap", op);
        assertTrue(error.getMessage().contains("sustained"), error.getMessage());
        assertFalse(op.dispatched(), "an error means nothing changed");
        assertEquals(0, f.nativeNotes.transactions);
        assertEquals(selected, f.selected, "nothing selected");
        assertFalse(f.notes.containsKey(LauncherFake.key(0, 0, 2, 61)), "all or nothing");
        f.put(0, 0, 12, 62, 100, 1); // an onset at beat 3, inside a 2-beat note from beat 2
        assertRpcError("overlap", f.startAndDrain("notes.set", edit(f, set(2, 62, 90, 2))));
        assertRpcError("invalid_params", f.startAndDrain("notes.set", edit(f, set(15, 63, 90, 2))));
    }

    @Test
    void aWriteThatFailsInsideTheTransactionIsUnknown() {
        LauncherFake f = riff(16);
        f.nativeNotes.failAfterDispatch = true;
        HostOperation op = f.startAndDrain("notes.set", eight(f));
        assertTrue(op.dispatched());
        assertEquals("unknown", op.result.join().get("outcome").getAsString());
    }

    @Test
    void aWriteThatDoesNotReadBackIsUnknown() {
        LauncherFake f = riff(16);
        f.nativeNotes.drop = true;
        JsonObject out = f.call("notes.set", eight(f));
        assertEquals("unknown", out.get("outcome").getAsString());
        assertEquals("readback_mismatch", out.get("reason").getAsString());
        assertEquals(8, out.getAsJsonObject("summary").get("unverified").getAsInt());
    }

    @Test
    void aMovedTargetIsRefusedBeforeTheWrite() {
        LauncherFake f = riff(16);
        f.nativeNotes.rename = "Other";
        HostOperation op = f.startAndDrain("notes.set", eight(f));
        assertRpcError("target_changed", op);
        assertFalse(op.dispatched());
    }

    /** 160 notes over 64 beats on a 1/16-note grid: two readback windows, one write. */
    private static JsonObject sixteenBars(LauncherFake f) {
        JsonArray list = new JsonArray();
        for (int i = 0; i < 160; i++) list.add(set(Math.floor(i * 1.6) / 4, 36 + i % 24, 60 + i % 60, .2));
        return params("target", f.clip(0), "edits", list);
    }

    @Test
    void windowsOnlyReadBackWhatOneTransactionWrote() {
        LauncherFake f = riff(64);
        JsonObject p = sixteenBars(f);
        assertEquals(
                2, NoteGrid.plan(p, p.getAsJsonArray("edits"), "edits", null).size());
        JsonObject out = f.call("notes.set", p);
        assertEquals("applied", out.get("outcome").getAsString());
        assertEquals("one_step", out.get("undo").getAsString());
        assertEquals(1, f.nativeNotes.transactions);
        JsonArray windows = out.getAsJsonArray("windows");
        assertEquals(2, windows.size());
        assertFalse(windows.get(1).getAsJsonObject().has("undo"), "undo appears once");
        assertEquals(160, out.getAsJsonObject("summary").get("verified").getAsInt());
    }

    @Test
    void everyWrittenWindowIsReadBackEvenAfterOneFails() {
        LauncherFake f = riff(64);
        f.nativeNotes.drop = true;
        JsonObject out = f.call("notes.set", sixteenBars(f));
        assertEquals("unknown", out.get("outcome").getAsString());
        JsonArray windows = out.getAsJsonArray("windows");
        assertEquals("unknown", windows.get(0).getAsJsonObject().get("outcome").getAsString());
        assertEquals("unknown", windows.get(1).getAsJsonObject().get("outcome").getAsString());
        assertFalse(out.has("notReached"), "every window was written");
        assertEquals(160, out.getAsJsonObject("summary").get("dispatched").getAsInt());
    }

    @Test
    void windowsLeftUnreadAtTheDeadlineAreUnknownNotUnreached() {
        LauncherFake f = riff(64);
        HostOperation op = f.start(new HostOperation(f.tasks.after(500)), "notes.set", sixteenBars(f));
        f.drain(op);
        JsonObject out = op.result.join();
        JsonObject second = out.getAsJsonArray("windows").get(1).getAsJsonObject();
        assertEquals("unknown", second.get("outcome").getAsString());
        assertEquals("not_observed", second.get("reason").getAsString());
        assertEquals(0, out.getAsJsonObject("summary").get("notReached").getAsInt());
        assertFalse(out.has("notReached"));
    }

    @Test
    void aWindowLostAfterTheWriteIsCountedAsWritten() {
        LauncherFake f = riff(64);
        f.onSelect = () -> f.trackId = "22222222-2222-2222-2222-222222222222";
        JsonObject out = f.call("notes.set", sixteenBars(f));
        assertEquals("unknown", out.get("outcome").getAsString());
        assertEquals(1, f.nativeNotes.transactions);
        JsonObject first = out.getAsJsonArray("windows").get(0).getAsJsonObject();
        assertEquals("unknown", first.get("outcome").getAsString());
        assertEquals(80, first.getAsJsonObject("summary").get("dispatched").getAsInt(), "still counted as written");
    }

    @Test
    void aTargetLostBeforeTheReadbackIsUnknown() {
        LauncherFake f = riff(16);
        f.onSelect = () -> f.trackId = "22222222-2222-2222-2222-222222222222";
        HostOperation op = f.startAndDrain("notes.set", eight(f));
        assertTrue(op.dispatched());
        assertEquals("unknown", op.result.join().get("outcome").getAsString());
        assertEquals(1, f.nativeNotes.transactions);
    }

    @Test
    void aClipInsertWithNotesIsOneNativeStep() {
        LauncherFake f = LauncherFake.withNativeNotes();
        JsonArray notes = new JsonArray();
        notes.add(obj("{\"beat\":0,\"pitch\":60,\"velocity\":100,\"duration\":0.5}"));
        notes.add(obj("{\"beat\":1,\"pitch\":64,\"velocity\":90,\"duration\":0.5}"));
        JsonObject out = f.call(
                "clips.insert", params("to", f.slot(0), "loop", obj("{\"length\":8}"), "name", "Lead", "notes", notes));
        assertEquals("applied", out.get("outcome").getAsString());
        assertEquals("one_step", out.get("undo").getAsString());
        assertEquals("one_step", out.getAsJsonObject("noteWrite").get("undo").getAsString());
        assertEquals(1, f.nativeNotes.creates);
        assertEquals(0, f.creates + f.nameWrites + f.stopWrites + f.writes, "no public setter");
        assertEquals(8.0, f.slots[0].playStop);
        assertFalse(f.services.holds.held(LauncherStructureLock.MUTATION), "the create is resolved");
    }

    @Test
    void aClipInsertRefusedNativelyIsAnError() {
        LauncherFake f = LauncherFake.withNativeNotes();
        f.nativeNotes.occupied = true;
        HostOperation op =
                f.startAndDrain("clips.insert", params("to", f.slot(0), "loop", obj("{\"length\":4}"), "name", "Lead"));
        assertRpcError("target_exists", op);
        assertFalse(op.dispatched());
    }

    @Test
    void anUnobservedNativeCreateIsUnknownWithItsNotesUnread() {
        LauncherFake f = LauncherFake.withNativeNotes();
        f.emitCreateContent = false;
        JsonArray notes = new JsonArray();
        notes.add(obj("{\"beat\":0,\"pitch\":60,\"velocity\":100,\"duration\":0.5}"));
        JsonObject out = f.call(
                "clips.insert", params("to", f.slot(0), "loop", obj("{\"length\":4}"), "name", "Lead", "notes", notes));
        assertEquals("unknown", out.get("outcome").getAsString());
        assertEquals("not_read_back", out.get("noteWrite").getAsString());
        assertEquals("one_step", out.get("undo").getAsString());
    }

    @Test
    void aNativeCreateWhoseNameDoesNotReadBackIsUnknown() {
        LauncherFake f = LauncherFake.withNativeNotes();
        f.applyName = false;
        JsonArray notes = new JsonArray();
        notes.add(obj("{\"beat\":0,\"pitch\":60,\"velocity\":100,\"duration\":0.5}"));
        HostOperation op = f.startAndDrain(
                "clips.insert", params("to", f.slot(0), "loop", obj("{\"length\":4}"), "name", "Lead", "notes", notes));
        assertTrue(op.dispatched());
        JsonObject out = op.result.join();
        assertEquals("unknown", out.get("outcome").getAsString());
        assertEquals("not_read_back", out.get("noteWrite").getAsString());
    }

    @Test
    void theNativePlanKeepsThePublicPreflightRules() {
        NoteGrid.Window w = new NoteGrid.Window(0, BigDecimal.ONE, 0, 8, 0, 128, true);
        NoteGrid.Edit set = new NoteGrid.Edit(0, 2, 60, 100, 1, 1, true),
                clear = new NoteGrid.Edit(1, 3, 61, 0, 0, 1, false);
        List<NoteGrid.Edit> edits = List.of(set, clear);
        List<NoteGrid.Window> windows = List.of(w, w);
        NativeNotes.Observed empty = new NativeNotes.Observed(0, 0, 0, false, false);
        NativeNotes.Observed matching = new NativeNotes.Observed(1, 100 / 127.0, 1 + 4e-7, false, false);
        assertArrayEquals(
                new boolean[] {false, false},
                NoteGrid.nativePlan(edits, windows, 0, 8, List.of(matching, empty)),
                "a matching onset and an empty clear need no write");
        assertTrue(set.alreadyMatched && clear.alreadyMatched);
        NativeNotes.Observed twice = new NativeNotes.Observed(2, 100 / 127.0, 1, false, false);
        assertArrayEquals(
                new boolean[] {true, true}, NoteGrid.nativePlan(edits, windows, 0, 8, List.of(twice, matching)));
        NativeNotes.Observed sustained = new NativeNotes.Observed(0, 0, 0, true, false);
        assertRpcError("overlap", () -> NoteGrid.nativePlan(edits, windows, 0, 8, List.of(empty, sustained)));
        NativeNotes.Observed collides = new NativeNotes.Observed(0, 0, 0, false, true);
        assertRpcError("overlap", () -> NoteGrid.nativePlan(edits, windows, 0, 8, List.of(collides, empty)));
        assertRpcError("invalid_params", () -> NoteGrid.nativePlan(edits, windows, 2.5, 8, List.of(empty, empty)));
        assertRpcError("invalid_params", () -> NoteGrid.nativePlan(edits, windows, 0, 2.5, List.of(empty, empty)));
        assertEquals("none", NativeNotes.undo(true, 0));
        assertEquals("per_note", NativeNotes.undo(false, 3));
    }

    @Test
    void cellsCarryTheAbsoluteStepOfTheirWindow() {
        NoteGrid.Window w = new NoteGrid.Window(3, new BigDecimal("0.25"), 128, 16, 0, 128, true);
        NativeNotes.Cell cell = NoteGrid.cells(List.of(new NoteGrid.Edit(0, 5, 60, 90, .5, 2, true)), List.of(w))
                .get(0);
        assertEquals(133, cell.step);
        assertEquals(.25, cell.stepBeats);
        assertEquals(3, cell.channel);
        assertEquals(2, cell.cells);
    }
}
