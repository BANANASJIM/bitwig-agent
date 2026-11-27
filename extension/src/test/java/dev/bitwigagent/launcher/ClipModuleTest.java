package dev.bitwigagent.launcher;

import static dev.bitwigagent.RpcAssert.*;
import static dev.bitwigagent.TestJson.*;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.HostOperation;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.core.Refs;
import dev.bitwigagent.core.RpcException;
import dev.bitwigagent.host.LauncherStructureLock;
import dev.bitwigagent.rpc.RpcServer;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Consumer tests against API-shaped in-memory host proxies; never native-host evidence. */
public final class ClipModuleTest {
    private static final String OTHER_TRACK = "33333333-3333-3333-3333-333333333333";

    @Test
    void aClipInsertedBeyondTheFirstWindowIsReadBackAndTheWindowRests() {
        LauncherFake f = new LauncherFake();
        f.scenes = 40;
        f.sceneNames[33] = "Drop";
        JsonObject inserted = f.call(
                "clips.insert",
                params(
                        "to", f.slot(33),
                        "loop", obj("{\"length\":4}"),
                        "name", "Hook",
                        "notes", JsonParser.parseString("[{\"beat\":0,\"pitch\":60,\"velocity\":90,\"duration\":1}]")));
        assertEquals("applied", inserted.get("outcome").getAsString());
        assertEquals(33, f.selected);
        assertTrue(f.slots[33].content && f.slots[33].name.equals("Hook"), "created in the slot of scene 33");
        assertEquals(
                24, f.bankOffset, "the window showed scene 33 while the clip was created, kept inside the 40 scenes");
        assertEquals(f.clip(33), inserted.get("ref").getAsJsonObject(), "the result carries the new clip's ref");

        JsonObject read = f.call("clips.get", params("target", inserted.get("ref")));
        assertEquals(4.0, read.getAsJsonObject("loop").get("length").getAsDouble());
    }

    @Test
    void aRenamedSceneStalesAnOldClipRef() {
        LauncherFake f = new LauncherFake();
        f.scenes = 40;
        f.sceneNames[33] = "Drop";
        f.slots[33].content = true;
        f.slots[33].name = "Hook";
        JsonObject ref = f.clip(33);
        f.sceneNames[33] = "Break";
        HostOperation op = f.startAndDrain("clips.launch", params("target", ref));
        RpcException error = assertRpcError("target_changed", op);
        assertEquals(
                "Break",
                error.current
                        .getAsJsonObject("slot")
                        .getAsJsonObject("scene")
                        .get("name")
                        .getAsString());
    }

    @Test
    void aWriteFacingAnotherWindowIsBusyWhileTheFirstOneApplies() {
        LauncherFake f = new LauncherFake();
        f.scenes = 40;
        f.slots[3].content = true;
        f.slots[3].name = "A";
        f.slots[33].content = true;
        f.slots[33].name = "B";
        HostOperation first = f.start("clips.set", params("target", f.clip(3), "nextAction", "stop"));
        assertFalse(
                first.result.isDone(),
                () -> first.result
                        .handle((r, e) -> e == null ? "done" : e.getMessage())
                        .join());
        JsonObject other = params("target", f.clip(33), "nextAction", "stop");
        HostOperation second = new HostOperation(f.tasks.after(2000));
        assertRpcError("busy", () -> f.clipModule.dispatch(second, "clips.set", other));
        f.drain(first);
        assertEquals("applied", first.result.join().get("outcome").getAsString());
    }

    // ---- helpers ----

    private static JsonObject noteSet(double beat, int pitch, int velocity, double duration) {
        JsonObject e = new JsonObject();
        e.addProperty("beat", beat);
        e.addProperty("pitch", pitch);
        e.addProperty("velocity", velocity);
        e.addProperty("duration", duration);
        return e;
    }

    private static JsonArray threeNotes() {
        JsonArray notes = new JsonArray();
        notes.add(noteSet(0, 60, 100, 1));
        notes.add(noteSet(1.5, 64, 80, .5));
        notes.add(noteSet(3, 67, 90, 1));
        return notes;
    }

    private static JsonObject withNotes(JsonObject p) {
        p.add("notes", threeNotes());
        return p;
    }

    private static JsonObject insertAt(JsonObject slot, int length, String name) {
        return params("to", slot, "loop", obj("{\"length\":" + length + "}"), "name", name);
    }

    private static JsonObject insertParams(LauncherFake f, int scene) {
        return insertAt(f.slot(scene), 4, "Hold fixture");
    }

    private static JsonObject importParams(LauncherFake f, int scene, Path file) {
        return params("to", f.slot(scene), "path", file.toString());
    }

    private static JsonObject clipParams(LauncherFake f, int scene) {
        return params("target", f.clip(scene));
    }

    private static void occupy(LauncherFake f, int scene, String name) {
        f.slots[scene].content = true;
        f.slots[scene].name = name;
    }

    private static void expectHeld(HostOperation op, String method, int scene) {
        RpcException error = assertRpcError("unresolved", op);
        assertTrue(error.getMessage().contains(method), error.getMessage());
        assertTrue(error.getMessage().contains(LauncherFake.ID), error.getMessage());
        assertTrue(error.getMessage().contains("scene " + scene), error.getMessage());
        assertTrue(error.next.contains("ack " + LauncherStructureLock.MUTATION), error.next);
        assertFalse(op.dispatched(), "a held slot refuses before any native content mutation");
    }

    private static void until(LauncherFake f, java.util.function.BooleanSupplier done) {
        for (int i = 0; i < 200 && !done.getAsBoolean(); i++) f.next();
    }

    private static Path file(Path dir) throws Exception {
        return Files.createFile(dir.resolve("clip.arbitrary"));
    }

    private static ShortMessage shortMessage(int command, int a, int b) throws Exception {
        ShortMessage message = new ShortMessage();
        message.setMessage(command, 0, a, b);
        return message;
    }

    // ---- clips.insert, launcher slot ----

    @Test
    void insertCreatesTheNamedClipAndReadsItBack() {
        LauncherFake f = new LauncherFake();
        JsonObject out = f.call("clips.insert", insertAt(f.slot(0), 4, "Melody + chords 鼓"));
        assertEquals("applied", out.get("outcome").getAsString());
        assertEquals("api_readback", out.get("verification").getAsString());
        assertEquals(4.0, out.getAsJsonObject("loop").get("length").getAsDouble());
        assertEquals("Melody + chords 鼓", f.slots[0].name);
        assertEquals(Refs.launcherClip(f.track(), f.scene(0), "Melody + chords 鼓"), out.get("ref"));
        assertFalse(out.has("noteWrite"));
        assertTrue(f.services.holds.list().isEmpty(), "a verified create leaves no hold");
        assertRpcError("target_exists", f.startAndDrain("clips.insert", insertAt(f.slot(0), 4, "Again")));
        assertEquals(1, f.creates, "an occupied slot is never overwritten");
    }

    @Test
    void thePlaybackExtentMatchesTheRequestedLength() {
        for (int length : new int[] {1, 16, 4096}) {
            LauncherFake f = new LauncherFake();
            JsonObject out = f.call("clips.insert", insertAt(f.slot(1), length, "Extent"));
            assertEquals("applied", out.get("outcome").getAsString());
            assertEquals(length, out.get("playStop").getAsDouble(), "not the host default of four beats");
            assertEquals(1, f.stopWrites, "the play stop is written once");
            assertEquals(4, f.slots[0].playStop, "another slot keeps its play stop");
        }
        LauncherFake f = new LauncherFake();
        f.applyStop = false;
        JsonObject out = f.call("clips.insert", insertAt(f.slot(0), 16, "Extent"));
        assertEquals("unknown", out.get("outcome").getAsString());
        assertEquals("readback_mismatch", out.get("reason").getAsString());
        assertTrue(out.get("next").getAsString().contains("ack " + LauncherStructureLock.MUTATION));
        assertEquals(4, out.get("playStop").getAsDouble(), "the host default is reported as it is");
        assertEquals(1, f.services.holds.list().size(), "an unapplied play stop holds the slot");
        expectHeld(f.startAndDrain("clips.insert", insertParams(f, 0)), "clips.insert", 0);
        assertEquals(1, f.stopWrites, "an unobserved write is never retried");
    }

    @Test
    void insertRefusesWhatCannotBeCreated() {
        LauncherFake f = new LauncherFake();
        f.scenes = 40;
        assertRpcError("target_not_found", f.startAndDrain("clips.insert", insertAt(f.slot(40), 4, "Far")));
        assertEquals(0, f.creates, "a scene past the count is never dispatched");
        JsonObject renamed = insertAt(f.slot(3), 4, "X");
        f.sceneNames[3] = "Chorus";
        RpcException error = assertRpcError("target_changed", f.startAndDrain("clips.insert", renamed));
        assertEquals(
                "Chorus", error.current.getAsJsonObject("scene").get("name").getAsString());
        assertEquals(0, f.creates);
        HostOperation valid = f.start("clips.insert", insertAt(f.slot(1), 4, "Valid"));
        assertEquals(1, f.creates, "refusals leave no hold behind");
        f.drain(valid);
        assertEquals("applied", valid.result.join().get("outcome").getAsString());
    }

    @Test
    void insertValidatesNotesAgainstTheLoop() {
        LauncherFake f = new LauncherFake();
        JsonObject late = insertAt(f.slot(0), 4, "Late");
        JsonArray notes = new JsonArray();
        notes.add(noteSet(3.5, 60, 90, 1));
        late.add("notes", notes);
        assertRpcError("invalid_params", f.start("clips.insert", late));
        JsonObject channel = insertAt(f.slot(0), 4, "Channel");
        channel.add("notes", notes);
        channel.addProperty("channel", 1);
        assertRpcError("invalid_params", f.start("clips.insert", channel));
        assertEquals(0, f.creates);
    }

    @Test
    void insertWritesTheNotesAfterObservingTheClip() {
        LauncherFake f = new LauncherFake();
        f.scenes = 40;
        JsonObject out = f.call("clips.insert", withNotes(insertAt(f.slot(33), 4, "Hook")));
        assertEquals("applied", out.get("outcome").getAsString());
        assertEquals("api_readback", out.get("verification").getAsString());
        JsonObject write = out.getAsJsonObject("noteWrite");
        assertEquals("applied", write.get("outcome").getAsString());
        assertEquals("api_readback", write.get("verification").getAsString());
        assertEquals(3, write.getAsJsonObject("summary").get("inserted").getAsInt());
        assertEquals("per_note", write.get("undo").getAsString());
        assertNotNull(f.at(33, 0, 0, 60));
        assertEquals(3, f.clipNotes(33).size());
        assertEquals(1, f.creates, "all notes are written after exactly one create");
        assertNotNull(out.get("slot"));
        JsonObject full = withNotes(insertAt(f.slot(1), 4, "Full"));
        full.addProperty("detail", "full");
        assertRpcError("invalid_params", f.start("clips.insert", full));
    }

    @Test
    void anUnobservedCreateWritesNoNotes() {
        LauncherFake f = new LauncherFake();
        f.deferCreate = true;
        JsonObject out = f.call("clips.insert", withNotes(insertParams(f, 0)));
        assertEquals("unknown", out.get("outcome").getAsString());
        assertEquals("not_attempted", out.get("noteWrite").getAsString());
        assertEquals(0, f.writes);
        assertEquals(1, f.services.holds.list().size());
    }

    @Test
    void aNoteStageFailureIsReportedInsideNoteWrite() {
        LauncherFake lost = new LauncherFake();
        lost.afterPlayStopRead = () -> {
            lost.afterPlayStopRead = () -> {};
            lost.trackId = "22222222-2222-2222-2222-222222222222";
        };
        JsonObject stale = lost.call("clips.insert", withNotes(insertParams(lost, 0)));
        assertNotNull(stale.get("slot"));
        assertEquals("not_applied", stale.get("outcome").getAsString(), stale.toString());
        assertEquals(
                "not_ready", stale.getAsJsonObject("noteWrite").get("reason").getAsString(), stale.toString());

        LauncherFake refused = new LauncherFake();
        refused.failWriteAt = 1;
        JsonObject kept = refused.call("clips.insert", withNotes(insertParams(refused, 0)));
        assertFalse(kept.has("verification"), "an unknown result carries none");
        assertTrue(refused.slots[0].content, "an observed create survives a failing note stage");
        assertEquals("unknown", kept.get("outcome").getAsString());
        assertEquals(
                "host_refused", kept.getAsJsonObject("noteWrite").get("reason").getAsString());

        LauncherFake silent = new LauncherFake();
        silent.dropPublicWrites = true;
        JsonObject partial = silent.call("clips.insert", withNotes(insertParams(silent, 0)));
        assertEquals("unknown", partial.get("outcome").getAsString());
        assertEquals(
                "unknown", partial.getAsJsonObject("noteWrite").get("outcome").getAsString());
    }

    // ---- clips.insert, arranger ----

    @Test
    void insertOnTheArrangerGoesThroughTheLane() {
        LauncherFake f = new LauncherFake();
        f.base.tracks[0].live("trackType", () -> "Instrument");
        JsonObject to = params("track", f.track(), "start", 16);
        JsonObject out = f.call("clips.insert", params("to", to, "length", 8, "name", "Verse"));
        assertEquals("applied", out.get("outcome").getAsString());
        assertEquals(1, f.lane.created);
        assertEquals(16.0, f.lane.clips.get(0)[0]);
        assertEquals(8.0, f.lane.clips.get(0)[1]);
        assertEquals(Refs.arrangerClip(f.track(), 16, "Verse"), out.get("ref"));
        assertEquals(0, f.creates, "no launcher slot is touched");
    }

    @Test
    void arrangerInsertValidatesNotesAgainstTheLength() {
        LauncherFake f = new LauncherFake();
        JsonObject to = params("track", f.track(), "start", 0);
        JsonObject p = params("to", to, "length", 1, "name", "Short");
        JsonArray notes = new JsonArray();
        notes.add(noteSet(0.5, 60, 90, 1));
        p.add("notes", notes);
        assertRpcError("invalid_params", f.start("clips.insert", p));
        assertEquals(0, f.lane.created);
    }

    @Test
    void insertWritesDurationsAsRequested() {
        LauncherFake f = new LauncherFake();
        JsonObject p = insertAt(f.slot(0), 4, "Free");
        JsonArray notes = new JsonArray();
        notes.add(noteSet(0, 60, 100, .45));
        notes.add(noteSet(1, 60, 100, .5));
        p.add("notes", notes);
        JsonObject out = f.call("clips.insert", p);
        assertEquals("applied", out.get("outcome").getAsString());
        assertEquals(.45, f.at(0, 0, 0, 60).duration, 1e-6);

        f.base.tracks[0].live("trackType", () -> "Instrument");
        JsonObject arranger = params("to", params("track", f.track(), "start", 0), "length", 4, "name", "A");
        arranger.add("notes", notes);
        assertEquals("applied", f.call("clips.insert", arranger).get("outcome").getAsString());
        assertEquals(.45, f.lane.notes.get(0)[3], 1e-6, "the arranger writes the requested duration (canonical)");
    }

    @Test
    void insertRefusesEveryBrokenNoteRuleAtOnce() {
        LauncherFake f = new LauncherFake();
        JsonObject p = insertAt(f.slot(0), 4, "Bad");
        JsonArray notes = new JsonArray();
        notes.add(noteSet(0, 33, 100, .75));
        notes.add(noteSet(.5, 33, 100, .25));
        notes.add(noteSet(1.3, 40, 100, .25));
        notes.add(noteSet(3.5, 41, 100, 1));
        p.add("notes", notes);
        String message =
                assertRpcError("invalid_params", f.start("clips.insert", p)).getMessage();
        assertTrue(message.startsWith("notes: 2 problems, nothing was written: notes[1] "), message);
        assertFalse(message.contains("notes[2]"), "an off-grid onset is a valid note: " + message);
        assertTrue(message.contains("notes[3] ends at beat 4.5, past the clip length 4"), message);
        assertEquals(0, f.creates);
    }

    // ---- clips.import ----

    @Test
    void importValidatesTheFileAndTheSlot(@TempDir Path dir) throws Exception {
        Path file = file(dir);
        for (String path : new String[] {
            "relative.wav",
            "file:///tmp/a.wav",
            "/tmp/../a.wav",
            "/tmp//a.wav",
            "/tmp/./a.wav",
            "/tmp/a.wav/",
            "/tmp/\u0085.wav",
            "/tmp/\0.wav",
            "/" + "a".repeat(4096)
        }) {
            LauncherFake f = new LauncherFake();
            JsonObject p = importParams(f, 0, file);
            p.addProperty("path", path);
            assertRpcError("invalid_params", f.start("clips.import", p));
            assertEquals(0, f.imports, "a malformed path never reaches insertion");
        }
        LauncherFake f = new LauncherFake();
        JsonObject overwrite = importParams(f, 0, file);
        overwrite.addProperty("overwrite", true);
        assertRpcError("invalid_params", f.start("clips.import", overwrite));
        JsonObject noPath = importParams(f, 0, file);
        noPath.remove("path");
        assertRpcError("invalid_params", f.start("clips.import", noPath));
        assertRpcError("invalid_file", f.start("clips.import", importParams(f, 0, dir.resolve("clip.missing"))));
        assertRpcError("invalid_file", f.start("clips.import", importParams(f, 0, dir)));
        assertEquals(0, f.imports, "missing files and directories never reach insertion");
        occupy(f, 0, "Existing");
        assertRpcError("target_exists", f.startAndDrain("clips.import", importParams(f, 0, file)));
        assertTrue(f.imports == 0 && f.slots[0].content, "occupied content is never replaced");
        assertRpcError("target_not_found", f.startAndDrain("clips.import", importParams(f, 20, file)));
        assertEquals(0, f.imports);
    }

    @Test
    void anEmptyMidiImportFailsBeforeMutation(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("empty.MID");
        Sequence sequence = new Sequence(Sequence.PPQ, 480);
        javax.sound.midi.Track track = sequence.createTrack();
        MetaMessage end = new MetaMessage();
        end.setMessage(0x2f, new byte[0], 0);
        track.add(new MidiEvent(end, 7680));
        MidiSystem.write(sequence, 1, file.toFile());
        LauncherFake f = new LauncherFake();
        assertRpcError("invalid_file", f.start("clips.import", importParams(f, 0, file)));
        assertEquals(0, f.imports, "an end-of-track-only MIDI never reaches host insertion");
        track.add(new MidiEvent(shortMessage(ShortMessage.NOTE_ON, 60, 0), 0));
        MidiSystem.write(sequence, 1, file.toFile());
        assertRpcError("invalid_file", f.start("clips.import", importParams(f, 0, file)));
        assertEquals(0, f.imports, "a zero-velocity note-on is not a playable note");
        Files.writeString(file, "not midi");
        assertRpcError("invalid_file", f.start("clips.import", importParams(f, 0, file)));
        assertEquals(0, f.imports, "malformed MIDI is rejected before host insertion");
        track.add(new MidiEvent(shortMessage(ShortMessage.NOTE_ON, 60, 90), 0));
        MidiSystem.write(sequence, 1, file.toFile());
        HostOperation op = f.start("clips.import", importParams(f, 0, file));
        assertEquals(1, f.imports, "preflight failures do not hold a later valid import");
        f.observeContent(0, true);
        f.drain(op);
        assertEquals("applied", op.result.join().get("outcome").getAsString());
        Path controller = dir.resolve("controller.mid");
        Sequence ccSequence = new Sequence(Sequence.PPQ, 480);
        MidiMessage cc = shortMessage(ShortMessage.CONTROL_CHANGE, 1, 64);
        ccSequence.createTrack().add(new MidiEvent(cc, 0));
        MidiSystem.write(ccSequence, 1, controller.toFile());
        LauncherFake ccHost = new LauncherFake();
        HostOperation ccImport = ccHost.start("clips.import", importParams(ccHost, 0, controller));
        assertEquals(1, ccHost.imports, "a controller-only MIDI file remains eligible for host import");
        ccHost.observeContent(0, true);
        ccHost.drain(ccImport);
    }

    @Test
    void importObservesOnlyTheContentTransition(@TempDir Path dir) throws Exception {
        Path file = file(dir);
        LauncherFake f = new LauncherFake();
        f.scenes = 40;
        f.selected = 5;
        HostOperation op = f.start("clips.import", importParams(f, 33, file));
        until(f, () -> f.imports == 1);
        assertTrue(f.imports == 1 && file.toString().equals(f.importedPath));
        assertEquals(33, f.importedSlot, "the native insertion targets the explicit scene, not the cursor");
        assertFalse(op.result.isDone(), "dispatch alone does not report an observed import");
        f.slots[33].name = "Host content";
        f.observeContent(33, true);
        f.drain(op);
        JsonObject out = op.result.join();
        assertEquals("applied", out.get("outcome").getAsString());
        assertEquals("api_readback", out.get("verification").getAsString());
        assertFalse(out.get("requestedFileVerified").getAsBoolean());
        assertEquals("Host content", out.getAsJsonObject("slot").get("name").getAsString());
        assertEquals(Refs.launcherClip(f.track(), f.scene(33), "Host content"), out.get("ref"));
        assertEquals(5, f.selected, "an import does not select the cursor");
        for (int mode = 0; mode < 3; mode++) {
            LauncherFake g = new LauncherFake();
            op = g.start("clips.import", importParams(g, 0, file));
            if (mode == 1) g.slots[0].content = true; // the getter changed without a fresh observer
            if (mode == 2) g.observeContent(0, false); // a fresh callback, but no content transition
            g.drain(op);
            assertUnknown("not_observed", op);
            assertEquals(1, g.imports, "an unobserved insertion is never replayed");
        }
        LauncherFake g = new LauncherFake();
        op = g.start("clips.import", importParams(g, 0, file));
        g.trackId = "22222222-2222-2222-2222-222222222222";
        g.drain(op);
        assertUnknown("not_ready", op);
        assertEquals(1, g.imports, "a post-dispatch source change stays unknown without retry");
    }

    @Test
    void anImportExpiredAtItsMutationBoundaryLeavesNoHold(@TempDir Path dir) throws Exception {
        Path file = file(dir);
        LauncherFake f = new LauncherFake();
        HostOperation op = new HostOperation(Long.MAX_VALUE);
        boolean[] armed = {false};
        f.beforeInsertion = () -> armed[0] = true;
        f.onContentRead = () -> {
            if (armed[0]) ((RpcServer.Expiring) op.result).expire();
        };
        f.start(op, "clips.import", importParams(f, 0, file));
        assertRpcError("deadline", op);
        assertTrue(f.imports == 0 && f.services.holds.list().isEmpty(), "nothing dispatched, nothing held");
        f.beforeInsertion = () -> {};
        f.onContentRead = () -> {};
        f.start("clips.import", importParams(f, 0, file));
        assertEquals(1, f.imports, "the slot admits the next write");
    }

    @Test
    void importMutationBoundaryGuards(@TempDir Path dir) throws Exception {
        Path file = file(dir);
        for (int flag = 0; flag < 4; flag++) {
            LauncherFake f = new LauncherFake(flag != 3, flag == 0, flag == 1, flag == 2);
            assertRpcError(flag == 3 ? "not_ready" : "unsafe_state", f.start("clips.import", importParams(f, 0, file)));
            assertEquals(0, f.imports, "an unsafe or unobserved transport prevents file insertion");
        }
        LauncherFake changed = new LauncherFake();
        changed.beforeInsertion = () -> changed.trackId = "22222222-2222-2222-2222-222222222222";
        assertRpcError("not_ready", changed.start("clips.import", importParams(changed, 0, file)));
        assertEquals(0, changed.imports, "the track binding is rechecked right before native dispatch");
        LauncherFake scrolled = new LauncherFake();
        scrolled.beforeInsertion = () -> scrolled.bankOffset = 1;
        assertRpcError("not_ready", scrolled.start("clips.import", importParams(scrolled, 0, file)));
        assertEquals(0, scrolled.imports, "the scene window is rechecked right before dispatch");
        LauncherFake occupied = new LauncherFake();
        occupied.beforeInsertion = () -> occupied.observeContent(0, true);
        assertRpcError("target_exists", occupied.start("clips.import", importParams(occupied, 0, file)));
        assertEquals(0, occupied.imports, "late slot occupancy cannot be overwritten");
        LauncherFake unsafe = new LauncherFake();
        unsafe.beforeInsertion = () -> unsafe.recordNode.emit(true);
        assertRpcError("unsafe_state", unsafe.start("clips.import", importParams(unsafe, 0, file)));
        assertEquals(0, unsafe.imports, "safety is rechecked at the mutation boundary");
        LauncherFake cancelled = new LauncherFake();
        HostOperation cancelledOp = new HostOperation(cancelled.tasks.after(2000));
        cancelled.beforeInsertion = () ->
                cancelledOp.result.completeExceptionally(Errors.notApplied(Reason.CANCELLED, "Fixture cancellation"));
        cancelled.start(cancelledOp, "clips.import", importParams(cancelled, 0, file));
        assertRpcError("cancelled", cancelledOp);
        assertTrue(cancelled.imports == 0 && !cancelledOp.dispatched());
        LauncherFake expired = new LauncherFake();
        HostOperation expiredOp = new HostOperation(expired.tasks.now() - 1);
        expired.start(expiredOp, "clips.import", importParams(expired, 0, file));
        assertRpcError("deadline", expiredOp);
        assertEquals(0, expired.imports, "an expired operation cannot dispatch insertion");
        LauncherFake late = new LauncherFake();
        HostOperation lateOp = new HostOperation(late.tasks.after(2000));
        boolean[] resolved = {false};
        late.beforeInsertion = () -> {
            resolved[0] = true;
            late.tasks.advance(lateOp.deadline + 1 - late.tasks.now());
        };
        late.start(lateOp, "clips.import", importParams(late, 0, file));
        assertRpcError("deadline", lateOp);
        assertTrue(resolved[0] && late.imports == 0, "the deadline is rechecked after resolving the insertion point");
    }

    // ---- holds and the lease ----

    @Test
    void settingsHoldsAreSharedWithTheContentWrites(@TempDir Path dir) throws Exception {
        LauncherFake f = new LauncherFake();
        f.services.holds.place(LauncherStructureLock.SETTINGS, "settings unresolved");
        assertRpcError("unresolved", f.startAndDrain("clips.insert", insertParams(f, 0)));
        assertEquals(0, f.creates, "a settings hold blocks a create before dispatch");
        f.services.holds.release(LauncherStructureLock.SETTINGS);
        f.services.holds.inFlight(LauncherStructureLock.SETTINGS_PENDING, "settings pending");
        assertRpcError("busy", f.startAndDrain("clips.insert", insertParams(f, 0)));
        f.services.holds.release(LauncherStructureLock.SETTINGS_PENDING);
        f.failImport = true;
        f.start("clips.import", importParams(f, 0, file(dir)));
        assertRpcError("unresolved", f.startAndDrain("clips.insert", insertParams(f, 0)));
    }

    @Test
    void anImportSharesTheCursorLease(@TempDir Path dir) throws Exception {
        Path file = file(dir);
        LauncherFake f = new LauncherFake();
        HostOperation importing = f.start("clips.import", importParams(f, 0, file));
        assertRpcError("busy", f.start("clips.import", importParams(f, 1, file)));
        assertRpcError("busy", f.start("clips.insert", insertParams(f, 1)));
        assertEquals(1, f.imports);
        assertEquals(0, f.creates);
        f.observeContent(0, true);
        f.drain(importing);
        occupy(f, 0, "Imported");
        HostOperation reading = f.start("notes.get", params("target", f.clip(0)));
        assertRpcError("busy", f.start("clips.import", importParams(f, 1, file)));
        f.drain(reading);
        assertEquals(1, f.imports, "an existing cursor lease also excludes an import");
        HostOperation creating = f.start("clips.insert", insertParams(f, 1));
        assertRpcError("busy", f.start("clips.import", importParams(f, 2, file)));
        f.drain(creating);
        assertEquals(1, f.imports, "an existing create also excludes an import");
    }

    @Test
    void anUnresolvedImportHoldsOnlyItsOwnSlot(@TempDir Path dir) throws Exception {
        Path file = file(dir);
        LauncherFake f = new LauncherFake();
        f.drain(f.start("clips.import", importParams(f, 0, file)));
        expectHeld(f.start("clips.import", importParams(f, 0, file)), "clips.import", 0);
        expectHeld(f.start("clips.insert", insertParams(f, 0)), "clips.import", 0);
        assertEquals(1, f.imports);
        assertEquals(0, f.creates);
        assertEquals(
                16,
                f.call("launcher.list", params("target", f.track()))
                        .getAsJsonArray("slots")
                        .size(),
                "a held slot stays readable");
        f.observeContent(0, true);
        f.next();
        occupy(f, 0, "Late");
        expectHeld(f.startAndDrain("clips.delete", clipParams(f, 0)), "clips.import", 0);
        assertEquals(0, f.deletes, "a late observation never resumes the unknown mutation");
        f.observeContent(0, false);
        expectHeld(f.start("clips.import", importParams(f, 0, file)), "clips.import", 0);
        HostOperation independent = f.start("clips.import", importParams(f, 1, file));
        assertEquals(2, f.imports);
        assertEquals(1, f.importedSlot, "the hold is per slot, not a permanent global lease");
        f.observeContent(1, true);
        f.drain(independent);
        assertEquals("applied", independent.result.join().get("outcome").getAsString());
        expectHeld(f.start("clips.import", importParams(f, 0, file)), "clips.import", 0);
    }

    @Test
    void mutationsAreKeyedByTheAbsoluteScene(@TempDir Path dir) throws Exception {
        Path file = file(dir);
        LauncherFake f = new LauncherFake();
        f.scenes = 40;
        f.drain(f.start("clips.import", importParams(f, 33, file)));
        expectHeld(f.startAndDrain("clips.import", importParams(f, 33, file)), "clips.import", 33);
        HostOperation other = f.start("clips.import", importParams(f, 3, file));
        until(f, () -> f.imports == 2);
        assertEquals(2, f.imports, "scene 3 sits at the same window position but is another slot");
        assertEquals(3, f.importedSlot);
        f.observeContent(3, true);
        f.drain(other);
        assertEquals("applied", other.result.join().get("outcome").getAsString());
        expectHeld(f.startAndDrain("clips.insert", insertParams(f, 33)), "clips.import", 33);
    }

    @Test
    void completedMutationsReleaseTheirSlots(@TempDir Path dir) throws Exception {
        Path file = file(dir);
        LauncherFake f = new LauncherFake();
        HostOperation first = f.start("clips.import", importParams(f, 0, file));
        f.observeContent(0, true);
        f.drain(first);
        f.observeContent(0, false);
        HostOperation second = f.start("clips.import", importParams(f, 0, file));
        assertTrue(f.imports == 2 && !second.result.isDone(), "an observed import releases its hold");
        f.observeContent(0, true);
        f.drain(second);
        assertEquals("applied", second.result.join().get("outcome").getAsString());
        LauncherFake g = new LauncherFake();
        assertEquals(
                "applied",
                g.call("clips.insert", insertParams(g, 0)).get("outcome").getAsString());
        g.observeContent(0, false);
        HostOperation imported = g.start("clips.import", importParams(g, 0, file));
        assertEquals(1, g.imports, "a successful create releases its slot");
        g.observeContent(0, true);
        g.drain(imported);
    }

    @Test
    void completedRequestFuturesDoNotReleaseMutations(@TempDir Path dir) throws Exception {
        Path file = file(dir);
        for (String method : new String[] {"clips.import", "clips.insert"}) {
            for (int mode = 0; mode < 3; mode++) {
                LauncherFake f = new LauncherFake();
                f.deferCreate = true;
                HostOperation first =
                        f.start(method, method.equals("clips.import") ? importParams(f, 0, file) : insertParams(f, 0));
                assertTrue(first.dispatched() && !first.result.isDone());
                if (mode == 0) {
                    first.result.cancel(false);
                    assertTrue(first.result.isCancelled());
                } else if (mode == 1) {
                    first.fail(Errors.unknown(Reason.DEADLINE, "Fixture transport timeout"));
                    assertUnknown("deadline", first);
                } else {
                    f.tasks.advance(first.deadline + 1 - f.tasks.now());
                    f.next();
                    assertUnknown("deadline", first);
                }
                f.next();
                expectHeld(f.start("clips.import", importParams(f, 0, file)), method, 0);
                f.observeContent(0, true);
                occupy(f, 0, "Late");
                expectHeld(f.startAndDrain("clips.delete", clipParams(f, 0)), method, 0);
                assertEquals(1, f.imports + f.creates, "a late observation never resumes a content mutation");
                assertEquals(0, f.nameWrites);
            }
        }
    }

    @Test
    void reboundTargetsRemainHeld(@TempDir Path dir) throws Exception {
        Path file = file(dir);
        String replacement = "22222222-2222-2222-2222-222222222222";
        LauncherFake f = new LauncherFake();
        JsonObject original = importParams(f, 0, file);
        HostOperation first = f.start("clips.import", original);
        f.observeTrackId(replacement);
        f.drain(first);
        assertUnknown("not_ready", first);
        assertRpcError("target_not_found", f.startAndDrain("clips.import", original));
        f.observeTrackId(LauncherFake.ID);
        expectHeld(f.start("clips.import", original), "clips.import", 0);
        assertEquals(1, f.imports, "the original target and the rebound proxy both stay held");
        LauncherFake g = new LauncherFake();
        first = g.start("clips.import", importParams(g, 0, file));
        g.observeTrackId(replacement);
        g.observeTrackId(LauncherFake.ID);
        g.observeContent(0, true);
        g.drain(first);
        assertUnknown("not_ready", first);
        occupy(g, 0, "Late");
        expectHeld(g.startAndDrain("clips.delete", clipParams(g, 0)), "clips.import", 0);
        assertEquals(1, g.imports, "returning to the old id cannot erase a binding change");
    }

    @Test
    void preDispatchFailuresDoNotHold(@TempDir Path dir) throws Exception {
        Path file = file(dir);
        LauncherFake f = new LauncherFake();
        JsonObject bad = insertParams(f, 0);
        bad.add("loop", obj("{\"length\":0}"));
        assertRpcError("invalid_params", f.start("clips.insert", bad));
        assertRpcError("invalid_file", f.start("clips.import", importParams(f, 0, dir.resolve("none"))));
        f.observeContent(0, true);
        assertRpcError("target_exists", f.startAndDrain("clips.import", importParams(f, 0, file)));
        assertRpcError("target_exists", f.startAndDrain("clips.insert", insertParams(f, 0)));
        f.observeContent(0, false);
        HostOperation cancelled = new HostOperation(f.tasks.after(2000));
        f.beforeInsertion = () ->
                cancelled.result.completeExceptionally(Errors.notApplied(Reason.CANCELLED, "Fixture cancellation"));
        f.start(cancelled, "clips.import", importParams(f, 0, file));
        assertRpcError("cancelled", cancelled);
        f.beforeInsertion = () -> {};
        HostOperation valid = f.start("clips.import", importParams(f, 0, file));
        assertTrue(f.imports == 1 && f.creates == 0, "pre-dispatch failures leave no hold");
        f.observeContent(0, true);
        f.drain(valid);
        assertEquals("applied", valid.result.join().get("outcome").getAsString());
    }

    @Test
    void unresolvedCreatesHoldTheSlot(@TempDir Path dir) throws Exception {
        Path file = file(dir);
        for (int mode = 0; mode < 4; mode++) {
            LauncherFake f = new LauncherFake();
            f.deferCreate = mode == 0;
            f.failCreate = mode == 1;
            f.applyName = mode != 2;
            f.emitCreateContent = mode != 3;
            HostOperation first = f.start("clips.insert", insertParams(f, 0));
            f.drain(first);
            if (mode == 1) assertUnknown("host_refused", first);
            else {
                JsonObject out = first.result.join();
                assertEquals("unknown", out.get("outcome").getAsString());
                assertTrue(out.has("reason"));
            }
            assertEquals(1, f.services.holds.list().size(), "an unobserved create holds the slot");
            f.observeContent(0, false);
            expectHeld(f.start("clips.import", importParams(f, 0, file)), "clips.insert", 0);
            expectHeld(f.start("clips.insert", insertParams(f, 0)), "clips.insert", 0);
            f.observeContent(0, true);
            occupy(f, 0, "Late");
            expectHeld(f.startAndDrain("clips.delete", clipParams(f, 0)), "clips.insert", 0);
            assertTrue(f.creates == 1 && f.imports == 0 && f.deletes == 0, "a failed create is never replayed");
        }
        LauncherFake f = new LauncherFake();
        f.failImport = true;
        assertUnknown("host_refused", f.start("clips.import", importParams(f, 0, file)));
        expectHeld(f.start("clips.insert", insertParams(f, 0)), "clips.import", 0);
        assertTrue(f.imports == 1 && f.creates == 0, "a native insertion exception also holds");
    }

    @Test
    void aSceneWindowHeldElsewhereMakesAWriteBusy() {
        LauncherFake f = new LauncherFake();
        f.scenes = 40;
        HostOperation first = f.start("clips.insert", insertAt(f.slot(3), 4, "A"));
        assertFalse(first.result.isDone());
        HostOperation second = f.start("clips.insert", insertAt(f.slot(33), 4, "B"));
        assertRpcError("busy", second);
        assertFalse(second.dispatched());
        f.drain(first);
        assertEquals("applied", first.result.join().get("outcome").getAsString());
        assertFalse(f.slots[33].content);
    }

    // ---- clips.get ----

    @Test
    void getReadsTheLoopWithoutMovingTheCursor() {
        LauncherFake f = new LauncherFake();
        f.scenes = 40;
        occupy(f, 33, "Hook");
        f.selected = 5;
        JsonObject out = f.call("clips.get", clipParams(f, 33));
        assertEquals(f.clip(33), out.get("ref"));
        assertEquals(4.0, out.getAsJsonObject("loop").get("length").getAsDouble());
        assertEquals(0.0, out.getAsJsonObject("loop").get("start").getAsDouble());
        assertEquals(5, f.selected, "clips.get selects nothing");
    }

    @Test
    void aStaleClipRefIsRefusedWithWhatIsThere() {
        LauncherFake f = new LauncherFake();
        occupy(f, 0, "Riff");
        JsonObject ref = f.clip(0);
        f.slots[0].name = "Other";
        RpcException changed = assertRpcError("target_changed", f.startAndDrain("clips.get", params("target", ref)));
        assertEquals(f.clip(0), changed.current);
        RpcException empty = assertRpcError("target_not_found", f.startAndDrain("clips.get", clipParams(f, 1)));
        assertTrue(empty.next.contains("launcher.list"), empty.next);
        assertRpcError("target_not_found", f.startAndDrain("clips.launch", clipParams(f, 1)));
        assertEquals(0, f.playbackDispatches);
    }

    // ---- clips.launch ----

    @Test
    void launchStatesAreDistinguished() {
        LauncherFake f = new LauncherFake();
        occupy(f, 0, "Riff");
        JsonObject out = f.call("clips.launch", clipParams(f, 0));
        assertEquals("queued", out.get("playbackState").getAsString());
        assertEquals("sent", out.get("outcome").getAsString());
        assertEquals(f.clip(0), out.get("ref"));
        LauncherFake g = new LauncherFake();
        occupy(g, 0, "Riff");
        g.launchPlaying = true;
        out = g.call("clips.launch", clipParams(g, 0));
        assertEquals("playing", out.get("playbackState").getAsString());
        assertEquals("applied", out.get("outcome").getAsString());
        LauncherFake h = new LauncherFake();
        occupy(h, 0, "Riff");
        h.emitPlaybackState = false;
        out = h.call("clips.launch", clipParams(h, 0));
        assertEquals("unknown", out.get("outcome").getAsString());
        assertEquals("not_observed", out.get("reason").getAsString());
        assertEquals("not_observed", out.get("playbackState").getAsString());
        assertEquals(1, h.playbackDispatches, "an unobserved launch is never redispatched");
    }

    @Test
    void launchRefusesUnsafeTransportsAndAbsentScenes() {
        LauncherFake f = new LauncherFake(true, true, false, false);
        occupy(f, 0, "Riff");
        assertRpcError("unsafe_state", f.startAndDrain("clips.launch", clipParams(f, 0)));
        assertEquals(0, f.playbackDispatches);
        LauncherFake g = new LauncherFake();
        JsonObject far = Refs.launcherClip(g.track(), Refs.scene(40, null), "x");
        assertRpcError("target_not_found", g.startAndDrain("clips.launch", params("target", far)));
    }

    // ---- clips.delete ----

    @Test
    void deleteIsVerifiedByAnEmptySlot() {
        LauncherFake f = new LauncherFake();
        occupy(f, 0, "Riff");
        JsonObject out = f.call("clips.delete", clipParams(f, 0));
        assertEquals("applied", out.get("outcome").getAsString());
        assertEquals("api_readback", out.get("verification").getAsString());
        assertFalse(f.slots[0].content);
        assertEquals(1, f.deletes);
        assertTrue(f.services.holds.list().isEmpty());
    }

    @Test
    void deleteRefusesBusyClipsStaleRefsAndUnsafeTransports() {
        LauncherFake f = new LauncherFake();
        occupy(f, 0, "Riff");
        f.slots[0].playing = true;
        assertRpcError("unsafe_state", f.startAndDrain("clips.delete", clipParams(f, 0)));
        LauncherFake g = new LauncherFake(true, true, false, false);
        occupy(g, 0, "Riff");
        assertRpcError("unsafe_state", g.startAndDrain("clips.delete", clipParams(g, 0)));
        assertEquals(0, g.deletes, "delete is blocked while recording");
        LauncherFake h = new LauncherFake();
        occupy(h, 0, "Riff");
        JsonObject ref = h.clip(0);
        h.slots[0].name = "Other";
        RpcException changed = assertRpcError("target_changed", h.startAndDrain("clips.delete", params("target", ref)));
        assertEquals("Other", changed.current.get("name").getAsString());
        assertEquals(0, h.deletes, "a stale name never reaches delete");
        assertRpcError("target_not_found", h.startAndDrain("clips.delete", clipParams(h, 1)));
    }

    @Test
    void anUnobservedDeleteIsUnknownAndHoldsTheSlot() {
        LauncherFake f = new LauncherFake();
        occupy(f, 0, "Riff");
        f.applyDelete = false;
        assertUnknown("not_observed", f.startAndDrain("clips.delete", clipParams(f, 0)));
        assertTrue(f.deletes == 1 && f.services.holds.list().size() == 1);
        expectHeld(f.start("clips.insert", insertParams(f, 0)), "clips.delete", 0);
    }

    @Test
    void deleteOnTheArrangerGoesThroughTheLane() {
        LauncherFake f = new LauncherFake();
        f.lane.clips.add(new double[] {0, 4});
        f.lane.names.add("Verse");
        JsonObject out = f.call("clips.delete", params("target", Refs.arrangerClip(f.track(), 0, "Verse")));
        assertEquals("applied", out.get("outcome").getAsString());
        assertTrue(f.lane.clips.isEmpty());
        assertEquals(1, f.lane.edited);
        assertEquals(0, f.deletes, "no launcher slot is touched");
        assertRpcError(
                "target_not_found",
                f.startAndDrain("clips.delete", params("target", Refs.arrangerClip(f.track(), 8, "Chorus"))));
    }

    // ---- clips.copy ----

    @Test
    void copyToAnEmptySlotIsVerifiedByContentAndName() {
        LauncherFake f = new LauncherFake();
        occupy(f, 0, "Riff");
        JsonObject out = f.call("clips.copy", params("from", f.clip(0), "to", f.slot(1)));
        assertEquals("applied", out.get("outcome").getAsString());
        assertEquals("api_readback", out.get("verification").getAsString());
        assertEquals(Refs.launcherClip(f.track(), f.scene(1), "Riff"), out.get("ref"));
        assertTrue(f.slots[1].content && f.slots[0].content);
        assertEquals(1, f.copies);
        assertTrue(f.services.holds.list().isEmpty());
        LauncherFake g = new LauncherFake();
        occupy(g, 0, "Riff");
        g.copyName = "Riff copy";
        JsonObject renamed = g.call("clips.copy", params("from", g.clip(0), "to", g.slot(1)));
        assertEquals("applied", renamed.get("outcome").getAsString());
        assertEquals(
                "Riff copy", renamed.getAsJsonObject("nameChanged").get("to").getAsString());
        assertEquals("Riff", g.slots[0].name, "the source is untouched");
        assertTrue(g.services.holds.list().isEmpty());
    }

    @Test
    void copyAcrossAWindowBoundaryStaysWithinSixteenScenes() {
        LauncherFake f = new LauncherFake();
        f.scenes = 40;
        occupy(f, 14, "Riff");
        JsonObject out = f.call("clips.copy", params("from", f.clip(14), "to", f.slot(18)));
        assertEquals("applied", out.get("outcome").getAsString());
        assertTrue(f.slots[18].content);
        assertEquals(Refs.launcherClip(f.track(), f.scene(18), "Riff"), out.get("ref"));
        occupy(f, 30, "Back");
        JsonObject backwards = f.call("clips.copy", params("from", f.clip(30), "to", f.slot(25)));
        assertEquals("applied", backwards.get("outcome").getAsString());
        assertTrue(f.slots[25].content);
        occupy(f, 5, "Far");
        assertRpcError("unsupported_scope", f.startAndDrain("clips.copy", params("from", f.clip(5), "to", f.slot(33))));
        assertRpcError("unsupported_scope", f.startAndDrain("clips.copy", params("from", f.clip(5), "to", f.slot(21))));
        assertEquals(2, f.copies, "the refused copies never dispatch");
    }

    @Test
    void copyRefusesOccupiedSameAndMissingTargets() {
        LauncherFake f = new LauncherFake();
        occupy(f, 0, "Riff");
        occupy(f, 1, "Taken");
        assertRpcError("target_exists", f.startAndDrain("clips.copy", params("from", f.clip(0), "to", f.slot(1))));
        assertRpcError("target_exists", f.startAndDrain("clips.copy", params("from", f.clip(0), "to", f.slot(0))));
        assertRpcError("target_not_found", f.startAndDrain("clips.copy", params("from", f.clip(0), "to", f.slot(16))));
        assertRpcError("target_not_found", f.startAndDrain("clips.copy", params("from", f.clip(2), "to", f.slot(3))));
        assertEquals(0, f.copies, "rejected copies never dispatch");
        JsonObject stale = f.clip(0);
        f.slots[0].name = "Changed";
        RpcException changed =
                assertRpcError("target_changed", f.startAndDrain("clips.copy", params("from", stale, "to", f.slot(2))));
        assertEquals("Changed", changed.current.get("name").getAsString());
        assertEquals(0, f.copies);
    }

    @Test
    void anUnobservedCopyIsUnknownAndHoldsTheTarget() {
        LauncherFake f = new LauncherFake();
        occupy(f, 0, "Riff");
        f.applyCopy = false;
        assertUnknown("not_observed", f.startAndDrain("clips.copy", params("from", f.clip(0), "to", f.slot(1))));
        assertTrue(f.copies == 1 && f.services.holds.list().size() == 1);
        expectHeld(f.start("clips.insert", insertParams(f, 1)), "clips.copy", 1);
        HostOperation other = f.start("clips.insert", insertParams(f, 2));
        assertEquals(1, f.creates, "only the copy target is held");
        f.drain(other);
    }

    @Test
    void copyRefusesAnotherTrack() {
        LauncherFake f = new LauncherFake();
        occupy(f, 0, "Riff");
        JsonObject other = Refs.track(OTHER_TRACK, "Other");
        JsonObject slot = Refs.slot(other, f.scene(1));
        assertRpcError("unsupported_scope", f.startAndDrain("clips.copy", params("from", f.clip(0), "to", slot)));
        JsonObject arranger = params("track", other, "start", 0);
        assertRpcError("target_not_found", f.startAndDrain("clips.copy", params("from", f.clip(0), "to", arranger)));
        assertEquals(0, f.copies + f.lane.copied);
    }

    @Test
    void copyToTheArrangerGoesThroughTheLane() {
        LauncherFake f = new LauncherFake();
        occupy(f, 2, "Riff");
        JsonObject to = params("track", f.track(), "start", 32);
        JsonObject out = f.call("clips.copy", params("from", f.clip(2), "to", to));
        assertEquals("applied", out.get("outcome").getAsString());
        assertEquals(1, f.lane.copied);
        assertEquals(32.0, f.lane.clips.get(0)[0]);
        assertEquals(Refs.arrangerClip(f.track(), 32, "Riff"), out.get("ref"));
        assertEquals(0, f.copies, "no launcher slot is written");
        assertRpcError("target_not_found", f.startAndDrain("clips.copy", params("from", f.clip(3), "to", to)));
    }

    @Test
    void aTrackOutsideTheWindowIsUnavailable() {
        LauncherFake f = new LauncherFake();
        f.base.bank.live("itemCount", () -> 100000);
        JsonObject far = Refs.slot(Refs.track(OTHER_TRACK, "Far"), f.scene(1));
        assertRpcError("outside_window", f.startAndDrain("clips.insert", insertAt(far, 4, "X")));
        assertEquals(0, f.creates);
    }
}
