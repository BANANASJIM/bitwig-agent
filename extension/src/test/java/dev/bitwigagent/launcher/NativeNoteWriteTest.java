package dev.bitwigagent.launcher;

import static dev.bitwigagent.RpcAssert.*;
import static dev.bitwigagent.TestJson.*;
import static dev.bitwigagent.launcher.NoteModuleTest.*;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import dev.bitwigagent.core.HostOperation;
import dev.bitwigagent.host.LauncherStructureLock;
import dev.bitwigagent.rpc.RpcServer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The native note path (exact read, one Undo step) against the in-memory fixture; never native-host evidence. */
public final class NativeNoteWriteTest {

    /** The riff of {@link NoteModuleTest#riff()} with the native binding, plus an off-grid onset only it can see. */
    private static LauncherFake riff() {
        LauncherFake f = LauncherFake.withNativeNotes();
        clipAt(f, 0);
        f.put(0, 0, 0, 60, 100, 1);
        f.put(0, 0, 1, 64, 90, 1);
        f.put(0, 0, .375, 67, 80, .25);
        f.put(0, 2, 2, 36, 110, .5);
        f.put(0, 0, 2.3, 48, 70, .1);
        return f;
    }

    @Test
    void aReadListsEveryNoteExactlyWithoutSelecting() {
        LauncherFake f = riff();
        f.selected = 5;
        JsonObject read = f.call("notes.get", target(f, 0));
        assertTrue(read.get("exact").getAsBoolean());
        assertFalse(read.has("onsetResolution"));
        assertEquals(5, read.get("count").getAsInt());
        assertEquals(obj("{\"start\":0,\"length\":4}"), read.get("loop"));
        JsonArray notes = read.getAsJsonArray("notes");
        assertEquals(2.3, notes.get(4).getAsJsonObject().get("beat").getAsDouble(), 1e-6, "an off-grid onset is exact");
        assertEquals(5, f.selected, "nothing was selected");
        assertFalse(f.services.selection.held(), "the cursor lease is not taken");
        assertEquals(1, f.nativeNotes.reads);
    }

    @Test
    void aReadOfAnyClipWrittenBackIsAlreadyMatchedWithEveryNoteUnchanged() {
        LauncherFake f = riff();
        JsonObject read = f.call("notes.get", target(f, 0));
        JsonObject p = set(f, 0, "all");
        p.add("notes", read.getAsJsonArray("notes"));
        JsonObject echo = f.call("notes.set", p);
        assertEquals("already_matched", echo.get("outcome").getAsString());
        assertEquals("native_readback", echo.get("verification").getAsString());
        assertEquals(
                read.get("count").getAsInt(),
                echo.getAsJsonObject("summary").get("unchanged").getAsInt());
        assertEquals(echo.get("count"), read.get("count"));
        assertEquals("none", echo.get("undo").getAsString());
        assertEquals(0, f.nativeNotes.transactions, "nothing to write opens no transaction");
    }

    @Test
    void aRangeWriteIsOneTransactionReadBackNatively() {
        LauncherFake f = riff();
        JsonObject out = f.call(
                "notes.set",
                set(
                        f,
                        0,
                        obj("{\"start\":0,\"end\":2,\"pitches\":[60,64]}"),
                        note(0, 60, 50, 1),
                        note(1.5, 62, 70, .5)));
        assertEquals("applied", out.get("outcome").getAsString());
        assertEquals("native_readback", out.get("verification").getAsString());
        assertEquals(obj("{\"inserted\":1,\"updated\":1,\"removed\":1,\"unchanged\":0}"), out.get("summary"));
        assertEquals("one_step", out.get("undo").getAsString());
        assertTrue(out.get("exact").getAsBoolean());
        assertEquals(5, out.get("count").getAsInt());
        assertEquals(1, f.nativeNotes.transactions);
        assertNull(f.at(0, 0, 1, 64), "the unlisted note in the range is removed");
        assertNotNull(f.at(0, 0, .375, 67), "pitch 67 lies outside range.pitches");
        assertEquals(0, f.writes, "no public setter");
    }

    @Test
    void anUpsertNeverRemovesAndOmittedFieldsKeepTheirValues() {
        LauncherFake f = riff();
        JsonObject longer = obj("{\"beat\":2.3,\"pitch\":48,\"duration\":0.5}");
        JsonObject out = f.call("notes.set", set(f, 0, null, longer));
        assertEquals(obj("{\"inserted\":0,\"updated\":1,\"removed\":0,\"unchanged\":0}"), out.get("summary"));
        assertEquals(70, f.at(0, 0, 2.3, 48).velocity);
        assertEquals(.5, f.at(0, 0, 2.3, 48).duration, 1e-6);
        assertEquals(5, f.clipNotes(0).size());
    }

    @Test
    void aRefusalInThePlanWritesNothingAndIsAnError() {
        LauncherFake f = riff();
        HostOperation op = f.startAndDrain("notes.set", set(f, 0, null, note(.5, 60, 90, .25)));
        String message = assertRpcError("invalid_params", op).getMessage();
        assertTrue(message.contains("sustains to beat 1: list that note with duration <= 0.5"), message);
        assertFalse(op.dispatched());
        assertEquals(0, f.nativeNotes.transactions);
        HostOperation into = f.startAndDrain("notes.set", set(f, 0, null, note(1.5, 64, 90, 1)));
        assertTrue(assertRpcError("invalid_params", into).getMessage().contains("sustains to beat 2"), "overlap check");
        assertRpcError("invalid_params", f.startAndDrain("notes.set", set(f, 0, null, note(.875, 64, 90, .25))));
    }

    @Test
    void aWriteThatFailsInsideTheTransactionIsUnknown() {
        LauncherFake f = riff();
        f.nativeNotes.failAfterDispatch = true;
        HostOperation op = f.startAndDrain("notes.set", set(f, 0, null, note(3, 50, 90, .5)));
        assertTrue(op.dispatched());
        assertEquals("unknown", op.result.join().get("outcome").getAsString());
        assertEquals(1, f.nativeNotes.transactions);
    }

    @Test
    void aWriteThatDoesNotReadBackIsUnknownAndNamesTheDifference() {
        LauncherFake f = riff();
        f.nativeNotes.drop = true;
        JsonObject out = f.call("notes.set", set(f, 0, null, note(3, 50, 90, .5)));
        assertEquals("unknown", out.get("outcome").getAsString());
        assertEquals("readback_mismatch", out.get("reason").getAsString());
        assertFalse(out.has("verification"));
        assertEquals(array(note(3, 50, 90, .5)), out.getAsJsonObject("mismatch").get("missing"));
        assertEquals(new JsonArray(), out.getAsJsonObject("mismatch").get("unexpected"));
    }

    @Test
    void aMovedTargetIsRefusedBeforeTheWrite() {
        LauncherFake f = riff();
        f.nativeNotes.rename = "Other";
        HostOperation op = f.startAndDrain("notes.set", set(f, 0, null, note(3, 50, 90, .5)));
        assertRpcError("target_changed", op);
        assertFalse(op.dispatched());
        assertRpcError("target_changed", f.startAndDrain("notes.get", target(f, 0)));
    }

    @Test
    void theLargestReadThatFitsOneResponseIsWrittenBackInOneRequest() {
        LauncherFake f = LauncherFake.withNativeNotes();
        clipAt(f, 0);
        f.slots[0].length = 4096;
        int count = NoteModule.NOTE_LIMIT;
        for (int i = 0; i < count; i++) f.put(0, (i / 128) % 16, (i / 2048) * 2 + .001, i % 128, 1 + i % 127, 1.75);
        JsonObject read = f.call("notes.get", target(f, 0));
        assertTrue(read.get("complete").getAsBoolean());
        assertEquals(count, read.getAsJsonArray("notes").size());
        assertTrue(read.toString().getBytes(StandardCharsets.UTF_8).length < RpcServer.MAX_MESSAGE_BYTES);
        JsonObject p = set(f, 0, "all");
        p.add("notes", read.getAsJsonArray("notes"));
        assertTrue(
                p.toString().getBytes(StandardCharsets.UTF_8).length < RpcServer.MAX_MESSAGE_BYTES,
                "the set request fits the same cap");
        assertEquals("already_matched", f.call("notes.set", p).get("outcome").getAsString());
        f.put(0, 0, 4095, 0, 1, 1);
        JsonObject more = f.call("notes.get", target(f, 0));
        assertFalse(more.get("complete").getAsBoolean(), "past the cap the read says it is incomplete");
        assertEquals(count + 1, more.get("count").getAsInt());
    }

    @Test
    void aClipInsertWithNotesIsOneNativeStepReadBackNatively() {
        LauncherFake f = LauncherFake.withNativeNotes();
        JsonObject out = f.call(
                "clips.insert",
                params(
                        "to",
                        f.slot(0),
                        "loop",
                        obj("{\"length\":8}"),
                        "name",
                        "Lead",
                        "notes",
                        array(note(0, 60, 100, .5), note(1.1, 64, 90, .5, 3))));
        assertEquals("applied", out.get("outcome").getAsString());
        assertEquals("one_step", out.get("undo").getAsString());
        JsonObject write = out.getAsJsonObject("noteWrite");
        assertEquals("applied", write.get("outcome").getAsString());
        assertEquals("native_readback", write.get("verification").getAsString());
        assertEquals(obj("{\"inserted\":2,\"updated\":0,\"removed\":0,\"unchanged\":0}"), write.get("summary"));
        assertEquals(1, f.nativeNotes.creates);
        assertEquals(0, f.creates + f.nameWrites + f.stopWrites + f.writes, "no public setter");
        assertNotNull(f.at(0, 3, 1.1, 64), "per-note channel and off-grid onsets are written");
        assertFalse(f.services.holds.held(LauncherStructureLock.MUTATION), "the create is resolved");
    }

    @Test
    void aClipInsertWhoseNotesDoNotReadBackReportsIt() {
        LauncherFake f = LauncherFake.withNativeNotes();
        f.nativeNotes.drop = true;
        JsonObject out = f.call(
                "clips.insert",
                params(
                        "to",
                        f.slot(0),
                        "loop",
                        obj("{\"length\":4}"),
                        "name",
                        "Lead",
                        "notes",
                        array(note(0, 60, 9, 1))));
        assertEquals("unknown", out.get("outcome").getAsString());
        assertEquals(
                "readback_mismatch",
                out.getAsJsonObject("noteWrite").get("reason").getAsString());
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
        JsonObject out = f.call(
                "clips.insert",
                params(
                        "to",
                        f.slot(0),
                        "loop",
                        obj("{\"length\":4}"),
                        "name",
                        "Lead",
                        "notes",
                        array(note(0, 60, 9, 1))));
        assertEquals("unknown", out.get("outcome").getAsString());
        assertEquals("not_read_back", out.get("noteWrite").getAsString());
    }

    @Test
    void theMismatchListsAreCappedAndAgreeingContentHasNone() {
        List<Note> one = List.of(Note.canonical(0, 60, 0, 1, 100));
        assertNull(NoteModule.mismatch(one, List.of(Note.canonical(0, 60, 0, 1, 100))));
        List<Note> many = new java.util.ArrayList<>();
        for (int i = 0; i < 40; i++) many.add(Note.canonical(0, i, 0, 1, 100));
        JsonObject m = NoteModule.mismatch(many, List.of());
        assertEquals(16, m.getAsJsonArray("missing").size());
        assertNotNull(NoteModule.mismatch(List.of(one.get(0), one.get(0)), one), "a duplicate read back is a mismatch");
    }
}
