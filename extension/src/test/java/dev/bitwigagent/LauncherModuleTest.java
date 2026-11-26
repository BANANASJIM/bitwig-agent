package dev.bitwigagent;

import static dev.bitwigagent.RpcAssert.*;
import static org.junit.jupiter.api.Assertions.*;

import com.bitwig.extension.callback.*;
import com.bitwig.extension.controller.api.*;
import com.google.gson.*;
import java.lang.reflect.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import org.junit.jupiter.api.Test;

/** Consumer tests against API-shaped in-memory host proxies; never native-host evidence. */
public final class LauncherModuleTest {
    private static final String ID = "11111111-1111-1111-1111-111111111111";

    private static Object stateConstant(String name) {
        try {
            for (Object constant :
                    NoteStep.class.getMethod("state").getReturnType().getEnumConstants())
                if (((Enum<?>) constant).name().equals(name)) return constant;
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
        throw new IllegalStateException("No NoteStep state " + name);
    }

    @Test
    void settingsIsolationIsShared() throws Exception {
        Fake f = new Fake();
        f.services.holds.place(LauncherStructureLock.SETTINGS, "settings unresolved");
        assertRpcError("unresolved", f.start("launcher.create", createTarget()));
        assertTrue(f.creates == 0, "settings quarantine blocks launcher create before dispatch");
        f.services.holds.release(LauncherStructureLock.SETTINGS);
        f.services.holds.inFlight(LauncherStructureLock.SETTINGS_PENDING, "settings pending");
        assertRpcError("busy", f.start("launcher.create", createTarget()));
        f.services.holds.release(LauncherStructureLock.SETTINGS_PENDING);
        Path file = Files.createTempFile("settings-import-quarantine-", ".wav");
        try {
            f.failImport = true;
            f.start("launcher.import", importTarget(file));
            {
                RpcException error = assertThrows(RpcException.class, () -> f.module.requireSettingsSafe(ID, 0));
                assertTrue(error.reason.wire().equals("unresolved"), "unresolved import blocks native settings");
            }
        } finally {
            Files.delete(file);
        }
    }

    @Test
    void strictWindowAndBatchValidation() {
        JsonObject valid = window();
        valid.add("edits", fixture());
        LauncherModule.validateParams("notes.edit", valid);
        assertTrue(
                LauncherModule.edits(valid, new LauncherModule.Window(valid)).size() == 5,
                "melody/chord/drum fixture accepted");
        JsonObject bad = valid.deepCopy();
        bad.addProperty("channel", 16);
        invalid(bad);
        bad = valid.deepCopy();
        bad.addProperty("slotIndex", -1);
        invalid(bad);
        bad = valid.deepCopy();
        bad.addProperty("steps", 129);
        invalid(bad);
        bad = valid.deepCopy();
        bad.addProperty("pitchStart", 120);
        bad.addProperty("pitchCount", 9);
        invalid(bad);
        bad = valid.deepCopy();
        bad.getAsJsonArray("edits").add(bad.getAsJsonArray("edits").get(0).deepCopy());
        invalid(bad);
        bad = valid.deepCopy();
        bad.getAsJsonArray("edits").get(0).getAsJsonObject().addProperty("beat", .3);
        invalid(bad);
        bad = valid.deepCopy();
        bad.getAsJsonArray("edits").get(0).getAsJsonObject().addProperty("durationBeats", .3);
        invalid(bad);
        bad = valid.deepCopy();
        bad.getAsJsonArray("edits").get(0).getAsJsonObject().addProperty("durationBeats", 5);
        invalid(bad);
        bad = valid.deepCopy();
        bad.getAsJsonArray("edits").add(set(.25, 60, 100, .25));
        invalid(bad);
        bad = valid.deepCopy();
        bad.addProperty("unexpected", true);
        invalid(bad);
        bad = valid.deepCopy();
        bad.add("edits", new JsonArray());
        invalid(bad);
        JsonArray tooMany = new JsonArray();
        for (int i = 0; i < 257; i++) tooMany.add(set(0, 60, 100, .25));
        bad = valid.deepCopy();
        bad.add("edits", tooMany);
        invalid(bad);
        assertTrue(
                LauncherModule.aligned(new JsonPrimitive(.3), new JsonPrimitive(.1), "beat") == 3,
                "decimal aligned times are exact");
        assertRpcError(
                "invalid_params",
                () -> LauncherModule.aligned(new JsonPrimitive(.30000000000000004), new JsonPrimitive(.1), "beat"));
        JsonObject clear = window();
        JsonArray edits = new JsonArray();
        JsonObject e = new JsonObject();
        e.addProperty("op", "clear");
        e.addProperty("beat", 0);
        e.addProperty("pitch", 60);
        e.addProperty("velocity", 1);
        edits.add(e);
        clear.add("edits", edits);
        invalid(clear);
    }

    @Test
    void createAndReadActualMusic() throws Exception {
        Fake f = new Fake();
        JsonObject create = target();
        create.addProperty("lengthBeats", 4);
        create.addProperty("name", "Melody + chords 鼓");
        JsonObject created = f.call("launcher.create", create);
        assertTrue(created.get("outcome").getAsString().equals("applied"), "create observes content/name/length");
        assertTrue(created.get("loopLengthBeats").getAsDouble() == 4, "length comes from clip readback");
        assertTrue(f.slots[0].name.equals("Melody + chords 鼓"), "explicit name written");
        assertRpcError("target_exists", f.start("launcher.create", create));
        assertTrue(f.creates == 1, "occupied slot never overwritten");
        f.put(0, 1, 2, 90, 70, .25); // unrelated channel
        f.put(1, 0, 0, 60, 40, .25); // unrelated clip
        f.put(0, 0, 24, 80, 50, .25); // outside requested window
        JsonObject edits = window();
        edits.add("edits", fixture());
        edits.addProperty("detail", "full");
        JsonObject result = f.call("notes.edit", edits);
        assertTrue(result.get("outcome").getAsString().equals("applied"), "fresh changed notes verified");
        assertTrue(result.getAsJsonArray("notes").size() == 5, "actual onset count returns chord/melody/drum notes");
        assertTrue(f.notes.containsKey(key(0, 1, 2, 90)), "unrelated channel preserved");
        assertTrue(f.notes.containsKey(key(1, 0, 0, 60)), "unrelated clip preserved");
        assertTrue(f.notes.containsKey(key(0, 0, 24, 80)), "outside window note preserved");
        f.put(0, 0, 0, 60, 37, .75);
        JsonObject read = f.call("notes.read", window());
        JsonObject c = find(read, 60, 0);
        assertTrue(c.get("velocity").getAsInt() == 37, "read returns host change, not request echo");
        assertTrue(c.get("durationBeats").getAsDouble() == .75, "actual duration returned");
        JsonObject clear = window();
        JsonArray array = new JsonArray();
        JsonObject e = new JsonObject();
        e.addProperty("op", "clear");
        e.addProperty("beat", 0);
        e.addProperty("pitch", 60);
        array.add(e);
        clear.add("edits", array);
        f.call("notes.edit", clear);
        assertTrue(!f.notes.containsKey(key(0, 0, 0, 60)), "clear removes only addressed onset");
        assertTrue(f.notes.containsKey(key(0, 0, 0, 64)), "other chord note preserved after clear");
        JsonObject later = window();
        later.addProperty("startStep", 16);
        later.addProperty("steps", 16);
        assertTrue(
                f.call("notes.read", later).getAsJsonArray("notes").size() == 1, "nonzero window maps absolute steps");
        JsonObject highChannel = window();
        highChannel.addProperty("channel", 15);
        JsonArray highEdits = new JsonArray();
        highEdits.add(set(0, 127, 127, .25));
        highChannel.add("edits", highEdits);
        highChannel.addProperty("detail", "full");
        assertTrue(
                f.call("notes.edit", highChannel).getAsJsonArray("notes").size() == 1,
                "channel15 and pitch127 are writable/readable");
        assertTrue(f.notes.containsKey(key(0, 0, 0, 64)), "channel15 edit leaves channel0 intact");
    }

    @Test
    void createdPlaybackExtentMatchesRequestedLength() throws Exception {
        for (int length : new int[] {1, 16, 4096}) {
            Fake f = new Fake();
            JsonObject p = createTarget();
            p.addProperty("lengthBeats", length);
            JsonObject out = f.call("launcher.create", p);
            assertTrue(out.get("outcome").getAsString().equals("applied"), "created extent is observed");
            assertTrue(
                    out.get("playStopBeats").getAsDouble() == length,
                    "play stop must match requested extent instead of the host default four beats");
            assertTrue(f.stopWrites == 1, "new clip playback stop is explicitly initialized once");
            assertTrue(f.slots[1].playStop == 4, "unrelated slot playback stop is preserved");
        }
        Fake f = new Fake();
        f.applyStop = false;
        JsonObject p = createTarget();
        p.addProperty("lengthBeats", 16);
        HostOperation op = f.start("launcher.create", p);
        f.drain(op);
        JsonObject out = op.result.join();
        assertTrue(
                out.get("outcome").getAsString().equals("unknown")
                        && out.get("reason").getAsString().equals("readback_mismatch")
                        && out.get("next").getAsString().contains("ack " + LauncherStructureLock.MUTATION),
                "unapplied stop cannot claim creation verified");
        assertTrue(out.get("playStopBeats").getAsDouble() == 4, "unapplied stop exposes actual host default");
        assertTrue(f.services.holds.list().size() == 1, "unapplied stop retains the mutation hold");
        expectQuarantine(f.start("launcher.create", createTarget()), "launcher.create", ID);
        assertTrue(f.stopWrites == 1, "unobserved stop is not automatically retried");
    }

    @Test
    void collisionsFailBeforeAnyWrite() throws Exception {
        Fake f = new Fake();
        f.slots[0].content = true;
        f.put(0, 0, 1, 60, 90, .25);
        JsonObject p = window();
        JsonArray edits = new JsonArray();
        edits.add(set(0, 36, 100, .25));
        edits.add(set(0, 60, 100, .5));
        p.add("edits", edits);
        assertUnknown("overlap", f.startAndDrain("notes.edit", p));
        assertTrue(f.writes == 0, "later collision prevents earlier unrelated edit too");
        f.notes.clear();
        f.put(0, 0, 0, 60, 90, 1);
        edits = new JsonArray();
        edits.add(set(.25, 60, 100, .25));
        p.add("edits", edits);
        assertUnknown("overlap", f.startAndDrain("notes.edit", p));
        assertTrue(f.writes == 0, "sustained cell never overwrites earlier onset");
    }

    @Test
    void parallelSelectionIsRejectedWhileCursorEditRuns() throws Exception {
        Fake f = new Fake();
        f.slots[0].content = true;
        HostOperation edit = f.start("notes.edit", editFixture());
        assertTrue(
                !edit.result.isDone() && f.services.selection.held(), "pending cursor edit holds the selection lease");
        {
            RpcException e = assertThrows(RpcException.class, () -> f.services.selection.requireFree());
            assertTrue(e.reason.wire().equals("busy") && !e.unknown, "select methods fail fast, not applied");
        }
        f.drain(edit);
        assertTrue(edit.result.join().get("outcome").getAsString().equals("applied"), "edit completes undisturbed");
        f.services.selection.requireFree();
        assertTrue(!f.services.selection.held(), "lease released with the operation");
    }

    @Test
    void identityAndDeadlineGuards() throws Exception {
        Fake f = new Fake();
        f.slots[0].content = true;
        f.slots[1].content = true;
        JsonObject p = window();
        p.add("edits", fixture());
        HostOperation op = f.start("notes.edit", p);
        f.next(); // cursor identity observed, schedule settled binding
        f.selected = 1;
        f.drain(op);
        assertUnknown("not_observed", op);
        assertTrue(f.writes == 0, "another selected slot cannot receive explicit writes");
        f = new Fake();
        f.slots[0].content = true;
        HostOperation expired = new HostOperation(f.tasks.now() - 1);
        f.services.scheduler.schedule(
                expired,
                () -> {
                    throw new AssertionError("expired operation ran");
                },
                0);
        f.drain(expired);
        assertRpcError("deadline", expired);
        assertTrue(f.writes == 0, "expired operations never write");
        f = new Fake();
        f.slots[0].content = true;
        op = f.start("notes.edit", p);
        f.trackId = "22222222-2222-2222-2222-222222222222";
        f.drain(op);
        assertUnknown("stale_context", op);
        assertTrue(f.writes == 0, "track UUID change aborts cursor edit");
    }

    @Test
    void selectionDispatchPreventsNotAppliedFailure() throws Exception {
        Fake f = new Fake();
        f.slots[1].content = true;
        JsonObject p = window();
        p.addProperty("slotIndex", 1);
        assertTrue(f.selected == 0, "scenario starts with a different selected clip");
        HostOperation op = f.start("notes.read", p);
        assertTrue(f.selected == 1, "explicit note read already changed the selected clip");
        f.trackId = "22222222-2222-2222-2222-222222222222";
        f.drain(op);
        assertUnknown("stale_context", op);
        assertTrue(f.writes == 0, "context failure occurs before any note edit");
    }

    @Test
    void cursorLeaseDoesNotBlockLauncherReads() throws Exception {
        Fake f = new Fake();
        f.slots[0].content = true;
        HostOperation first = f.start("notes.read", window());
        assertRpcError("busy", f.start("notes.read", window()));
        JsonObject list = f.call("launcher.list", trackTarget());
        assertTrue(list.getAsJsonArray("slots").size() == 16, "metadata reads proceed while cursor operation pending");
        f.drain(first);
        assertTrue(first.result.join().getAsJsonArray("notes").isEmpty(), "empty actual grid read completes");
        f.call("notes.read", window());
    }

    @Test
    void lateOrMissingObservationsRemainDispatched() throws Exception {
        Fake f = new Fake();
        f.slots[0].content = true;
        f.emitNotes = false;
        JsonObject p = window();
        p.add("edits", fixture());
        p.addProperty("detail", "full");
        JsonObject out = f.call("notes.edit", p);
        assertTrue(
                out.get("outcome").getAsString().equals("unknown")
                        && out.get("reason").getAsString().equals("not_observed"),
                "readback without fresh callbacks does not verify changed write");
        JsonObject unverified = out.getAsJsonArray("edits").get(0).getAsJsonObject();
        assertTrue(
                unverified.get("outcome").getAsString().equals("unknown")
                        && unverified.get("reason").getAsString().equals("not_observed"),
                "per-cell missing observation explicit");
        assertTrue(out.getAsJsonArray("notes").size() == 5, "partial outcome still exposes actual readable notes");
        f = new Fake();
        f.slots[0].content = true;
        f.failWriteAt = 2;
        HostOperation op = f.startAndDrain("notes.edit", p);
        assertUnknown("host_refused", op);
        assertTrue(f.notes.size() == 1, "first applied note preserved; no destructive rollback or replay");
    }

    @Test
    void explicitLaunchAndStopStates() throws Exception {
        Fake f = new Fake();
        f.slots[0].content = true;
        JsonObject out = f.call("launcher.launch", target());
        assertTrue(out.get("playbackState").getAsString().equals("queued"), "queued launch distinct from playing");
        assertTrue(out.get("outcome").getAsString().equals("sent"), "queue does not claim playback");
        f = new Fake();
        f.slots[0].content = true;
        f.launchPlaying = true;
        out = f.call("launcher.launch", target());
        assertTrue(out.get("playbackState").getAsString().equals("playing"), "actual playing state reported");
        assertTrue(out.get("outcome").getAsString().equals("applied"), "playing launch is applied");
        out = f.call("launcher.stop", trackTarget());
        assertTrue(out.get("playbackState").getAsString().equals("stopped"), "explicit track stop observed");
        assertTrue(out.get("outcome").getAsString().equals("applied"), "stopped track is applied");
        f = new Fake();
        f.slots[0].content = true;
        f.slots[0].playing = true;
        f.stopped = false;
        f.queueStop = true;
        out = f.call("launcher.stop", trackTarget());
        assertTrue(out.get("playbackState").getAsString().equals("queued"), "queued stop distinct from stopped");
        assertTrue(out.get("outcome").getAsString().equals("sent"), "queued stop does not claim completion");
        assertTrue(f.slots[0].playing && !f.stopped, "queued stop may leave the clip playing");
        JsonObject bad = target();
        bad.addProperty("slotIndex", 15);
        assertRpcError("target_not_found", f.start("launcher.launch", bad));
    }

    @Test
    void unobservedPlaybackCompletionRemainsUnknown() throws Exception {
        for (boolean stop : new boolean[] {false, true}) {
            Fake f = new Fake();
            f.slots[0].content = true;
            f.emitPlaybackState = false;
            f.slots[0].playing = stop;
            f.stopped = !stop;
            JsonObject out = f.call(stop ? "launcher.stop" : "launcher.launch", stop ? trackTarget() : target());
            assertTrue(
                    out.get("outcome").getAsString().equals("unknown")
                            && out.get("reason").getAsString().equals("not_observed"),
                    "missing playback observation is unknown");
            assertTrue(
                    out.get("playbackState").getAsString().equals("not_observed"),
                    "neither queue nor desired state observed");
            assertTrue(f.playbackDispatches == 1, "playback observation never redispatches the operation");
        }
    }

    @Test
    void denseReadReportsItsResponseBoundary() throws Exception {
        Fake f = new Fake();
        f.slots[0].content = true;
        f.slots[0].length = 8;
        for (int x = 0; x < 32; x++) for (int pitch = 0; pitch < 128; pitch++) f.put(0, 0, x, pitch, 100, .25);
        JsonObject p = window();
        p.addProperty("steps", 32);
        JsonObject out = f.call("notes.read", p);
        assertTrue(out.get("notesInWindow").getAsInt() == 4096, "dense window reports actual total");
        assertTrue(out.getAsJsonArray("notes").size() == 2048, "response stays bounded");
        assertTrue(
                !out.get("completeWindow").getAsBoolean() && out.has("continuation"), "dense partial read is explicit");
        assertTrue(
                out.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length < RpcServer.MAX_RESPONSE_BYTES,
                "dense read fits protocol frame");
    }

    @Test
    void matchingEditsPreserveExistingNotes() throws Exception {
        Fake f = new Fake();
        f.slots[0].content = true;
        f.put(0, 0, 0, 60, 100, .5);
        Note existing = f.notes.get(key(0, 0, 0, 60));
        JsonObject p = window();
        JsonArray edits = new JsonArray();
        edits.add(set(0, 60, 100, .5));
        p.add("edits", edits);
        JsonObject out = f.call("notes.edit", p);
        assertTrue(f.writes == 0, "matching set does not replace note or clear expression");
        assertTrue(
                f.notes.get(key(0, 0, 0, 60)) == existing, "existing note object and unaddressed properties preserved");
        assertTrue(
                out.get("outcome").getAsString().equals("already_matched"),
                "unchanged actual readback verifies without callback replay");
    }

    @Test
    void loopStartAndPlaybackSafetyFailClosed() throws Exception {
        Fake f = new Fake();
        f.slots[0].content = true;
        f.loopStart = 1;
        JsonObject p = window();
        JsonArray edits = new JsonArray();
        edits.add(set(0, 60, 100, .5));
        p.add("edits", edits);
        assertUnknown("invalid_params", f.startAndDrain("notes.edit", p));
        assertTrue(f.writes == 0, "notes before observed loop start are rejected before any mutation");
        edits = new JsonArray();
        edits.add(set(1, 60, 100, .5));
        p.add("edits", edits);
        assertTrue(
                f.call("notes.edit", p).get("outcome").getAsString().equals("applied"),
                "note at loop start is accepted");
        assertTrue(f.writes == 1, "loop start bound is inclusive");
        for (int flag = 0; flag < 4; flag++) {
            f = new Fake(flag != 3, flag == 0, flag == 1, flag == 2);
            f.slots[0].content = true;
            assertRpcError(flag == 3 ? "not_ready" : "unsafe_state", f.start("launcher.launch", target()));
            assertTrue(
                    f.stopped && !f.slots[0].queued && !f.slots[0].playing,
                    "unsafe or unobserved transport never launches clip");
            assertTrue(
                    f.call("launcher.stop", trackTarget())
                            .get("playbackState")
                            .getAsString()
                            .equals("stopped"),
                    "stop remains available under unsafe or unknown recording state");
        }
    }

    @Test
    void delayedGridPropagationKeepsReadinessExplicit() throws Exception {
        Fake f = new Fake();
        f.slots[0].content = true;
        f.slots[0].length = 32;
        f.put(0, 0, 0, 60, 100, .25);
        f.put(0, 0, 16, 72, 80, .5);
        long before = f.call("notes.read", window()).get("gridGeneration").getAsLong();
        f.deferGridChanges = true;
        JsonObject p = window();
        p.addProperty("startStep", 16);
        p.addProperty("stepSizeBeats", .5);
        HostOperation delayed = f.start("notes.read", p);
        assertTrue(f.start == 0 && f.size == .25, "fixture retains old grid after asynchronous reconfiguration");
        f.next();
        f.applyPendingGrid();
        f.drain(delayed);
        JsonObject out = delayed.result.join();
        assertTrue(find(out, 72, 8) != null, "delayed grid delivered during settling yields new window content");
        assertTrue(
                out.get("gridGeneration").getAsLong() > before, "window change gets distinct observation generation");
        assertTrue(
                out.get("gridReadiness").getAsString().equals("scheduled_settling_not_acknowledged"),
                "scheduled settling never claims a public API grid acknowledgement");
        assertTrue(
                !out.has("gridReadinessLimitation") && !out.has("onsetPrecision") && !out.has("selectionSideEffect"),
                "constant prose lives in the catalog, not every response");
        f.emitNotes = false;
        JsonArray edits = new JsonArray();
        edits.add(set(8, 72, 90, .5));
        p.add("edits", edits);
        HostOperation changed = f.start("notes.edit", p);
        f.observer.noteStepChanged(f.note(0, 0, 72)); // Old/read-generation callback while new binding is unsettled.
        f.applyPendingGrid();
        f.drain(changed);
        assertTrue(
                changed.result
                        .join()
                        .getAsJsonArray("unverifiedEdits")
                        .get(0)
                        .getAsJsonObject()
                        .get("outcome")
                        .getAsString()
                        .equals("unknown"),
                "callback from prior or unsettled window cannot verify a later changed write");
    }

    @Test
    void firstGridReadWaitsForNativeDebounce() throws Exception {
        Fake f = new Fake();
        f.slots[0].content = true;
        f.noteStorageStepSize = .25;
        f.gridRefreshDelayMillis = 100;
        f.put(0, 0, 0, 60, 96, 1);
        f.put(0, 0, 8, 64, 96, 1);
        JsonObject p = window();
        p.addProperty("stepSizeBeats", .125);
        p.addProperty("steps", 32);
        JsonObject out = f.call("notes.read", p);
        assertTrue(out.getAsJsonArray("notes").size() == 2, "first grid read preserves both stored notes");
        assertTrue(
                find(out, 64, 2) != null,
                "first eighth-beat read must not relabel the old quarter-beat cache as beat one");
        assertTrue(f.writes == 0, "grid refresh does not change note content");
    }

    @Test
    void fileImportValidationAndOccupancy() throws Exception {
        Path file = Files.createTempFile("launcher-import-", ".arbitrary");
        try {
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
                Fake f = new Fake();
                JsonObject p = importTarget(file);
                p.addProperty("path", path);
                assertRpcError("invalid_params", f.start("launcher.import", p));
                assertTrue(f.imports == 0, "malformed path never reaches insertion");
            }
            Fake f = new Fake();
            JsonObject p = importTarget(file);
            p.addProperty("overwrite", true);
            assertRpcError("invalid_params", f.start("launcher.import", p));
            assertTrue(f.imports == 0, "overwrite option is not accepted");
            p = importTarget(file);
            p.remove("path");
            assertRpcError("invalid_params", f.start("launcher.import", p));
            p = importTarget(file);
            p.addProperty("slotIndex", 16);
            assertRpcError("invalid_params", f.start("launcher.import", p));
            p = importTarget(file);
            p.addProperty("trackId", "");
            assertRpcError("invalid_params", f.start("launcher.import", p));
            assertTrue(f.imports == 0, "missing path or malformed explicit target cannot insert");
            assertRpcError(
                    "invalid_file",
                    f.start("launcher.import", importTarget(file.resolveSibling(file.getFileName() + ".missing"))));
            assertRpcError("invalid_file", f.start("launcher.import", importTarget(file.getParent())));
            assertTrue(f.imports == 0, "missing files and directories never reach insertion");
            f.slots[0].content = true;
            assertRpcError("target_exists", f.start("launcher.import", importTarget(file)));
            assertTrue(f.imports == 0 && f.slots[0].content, "occupied content is never replaced");
        } finally {
            Files.delete(file);
        }
    }

    @Test
    void emptyMidiImportFailsBeforeMutation() throws Exception {
        Path file = Files.createTempFile("launcher-empty-", ".MID");
        try {
            javax.sound.midi.Sequence sequence = new javax.sound.midi.Sequence(javax.sound.midi.Sequence.PPQ, 480);
            javax.sound.midi.Track track = sequence.createTrack();
            javax.sound.midi.MetaMessage end = new javax.sound.midi.MetaMessage();
            end.setMessage(0x2f, new byte[0], 0);
            track.add(new javax.sound.midi.MidiEvent(end, 7680));
            javax.sound.midi.MidiSystem.write(sequence, 1, file.toFile());
            Fake f = new Fake();
            assertRpcError("invalid_file", f.start("launcher.import", importTarget(file)));
            assertTrue(f.imports == 0, "EOT-only MIDI never reaches host insertion");
            javax.sound.midi.ShortMessage zero = new javax.sound.midi.ShortMessage();
            zero.setMessage(javax.sound.midi.ShortMessage.NOTE_ON, 0, 60, 0);
            track.add(new javax.sound.midi.MidiEvent(zero, 0));
            javax.sound.midi.MidiSystem.write(sequence, 1, file.toFile());
            assertRpcError("invalid_file", f.start("launcher.import", importTarget(file)));
            assertTrue(f.imports == 0, "zero-velocity note-on is not a playable note");
            Files.writeString(file, "not midi");
            assertRpcError("invalid_file", f.start("launcher.import", importTarget(file)));
            assertTrue(f.imports == 0, "malformed MIDI is rejected before host insertion");
            javax.sound.midi.ShortMessage note = new javax.sound.midi.ShortMessage();
            note.setMessage(javax.sound.midi.ShortMessage.NOTE_ON, 0, 60, 90);
            track.add(new javax.sound.midi.MidiEvent(note, 0));
            javax.sound.midi.MidiSystem.write(sequence, 1, file.toFile());
            HostOperation op = f.start("launcher.import", importTarget(file));
            assertTrue(f.imports == 1, "preflight failures do not quarantine a later valid import");
            f.observeContent(0, true);
            f.drain(op);
            assertTrue(
                    op.result.join().get("outcome").getAsString().equals("applied"),
                    "valid MIDI still uses host content observation");
            javax.sound.midi.Sequence ccSequence = new javax.sound.midi.Sequence(javax.sound.midi.Sequence.PPQ, 480);
            javax.sound.midi.ShortMessage cc = new javax.sound.midi.ShortMessage();
            cc.setMessage(javax.sound.midi.ShortMessage.CONTROL_CHANGE, 0, 1, 64);
            ccSequence.createTrack().add(new javax.sound.midi.MidiEvent(cc, 0));
            javax.sound.midi.MidiSystem.write(ccSequence, 1, file.toFile());
            Fake ccHost = new Fake();
            HostOperation ccImport = ccHost.start("launcher.import", importTarget(file));
            assertTrue(ccHost.imports == 1, "controller-only MIDI remains eligible for host import");
            ccHost.observeContent(0, true);
            ccHost.drain(ccImport);
        } finally {
            Files.delete(file);
        }
    }

    @Test
    void fileImportObservesOnlyContentTransition() throws Exception {
        Path file = Files.createTempFile("launcher-import-", ".arbitrary");
        try {
            Fake f = new Fake();
            f.selected = 5;
            JsonObject p = importTarget(file);
            p.addProperty("slotIndex", 3);
            HostOperation op = f.start("launcher.import", p);
            assertTrue(
                    f.imports == 1 && file.toString().equals(f.importedPath) && f.importedSlot == 3,
                    "native insertion targets explicit slot, not selected cursor");
            assertTrue(!op.result.isDone(), "dispatch alone does not report observed import");
            f.slots[3].name = "Host content";
            f.observeContent(3, true);
            f.drain(op);
            JsonObject out = op.result.join();
            assertTrue(
                    out.get("outcome").getAsString().equals("applied")
                            && out.get("verification").getAsString().equals("slot_content_transition"),
                    "fresh content transition is observed");
            assertTrue(
                    !out.get("requestedFileVerified").getAsBoolean(),
                    "content observation never claims file identity or decoding");
            assertTrue(
                    out.getAsJsonObject("slot").get("name").getAsString().equals("Host content"),
                    "response contains host metadata, not path echo");
            assertTrue(f.selected == 5 && !f.slots[0].content, "import does not select cursor or touch other slots");
            for (int mode = 0; mode < 3; mode++) {
                f = new Fake();
                op = f.start("launcher.import", importTarget(file));
                if (mode == 1) f.slots[0].content = true; // Getter changed without a fresh observer.
                if (mode == 2) f.observeContent(0, false); // Fresh callback, but no content transition.
                f.drain(op);
                assertUnknown("not_observed", op);
                assertTrue(f.imports == 1, "unobserved insertion is never replayed");
            }
            f = new Fake();
            op = f.start("launcher.import", importTarget(file));
            f.trackId = "22222222-2222-2222-2222-222222222222";
            f.drain(op);
            assertUnknown("stale_context", op);
            assertTrue(f.imports == 1, "post-dispatch source change stays unknown without retry");
        } finally {
            Files.delete(file);
        }
    }

    @Test
    void fileImportSharesCursorLease() throws Exception {
        Path file = Files.createTempFile("launcher-import-", ".arbitrary");
        try {
            Fake f = new Fake();
            HostOperation importing = f.start("launcher.import", importTarget(file));
            assertRpcError("busy", f.start("launcher.import", importTarget(file)));
            JsonObject create = target();
            create.addProperty("lengthBeats", 4);
            create.addProperty("name", "Cannot race import");
            assertRpcError("busy", f.start("launcher.create", create));
            assertRpcError("busy", f.start("notes.edit", editFixture()));
            assertRpcError("busy", f.start("notes.read", window()));
            assertTrue(
                    f.imports == 1 && f.creates == 0 && f.writes == 0,
                    "pending import excludes cursor/create/import mutations");
            assertTrue(
                    f.call("launcher.list", trackTarget())
                                    .getAsJsonArray("slots")
                                    .size()
                            == 16,
                    "read-only metadata remains available");
            f.observeContent(0, true);
            f.drain(importing);
            HostOperation reading = f.start("notes.read", window());
            JsonObject other = importTarget(file);
            other.addProperty("slotIndex", 1);
            assertRpcError("busy", f.start("launcher.import", other));
            f.drain(reading);
            assertTrue(f.imports == 1, "existing cursor lease also excludes import");
            other = target();
            other.addProperty("slotIndex", 1);
            other.addProperty("lengthBeats", 4);
            other.addProperty("name", "Creating");
            HostOperation creating = f.start("launcher.create", other);
            assertRpcError("busy", f.start("launcher.import", importTarget(file)));
            f.drain(creating);
            assertTrue(f.imports == 1, "existing create lease also excludes import");
        } finally {
            Files.delete(file);
        }
    }

    @Test
    void fileImportMutationBoundaryGuards() throws Exception {
        Path file = Files.createTempFile("launcher-import-", ".arbitrary");
        try {
            for (int flag = 0; flag < 4; flag++) {
                Fake f = new Fake(flag != 3, flag == 0, flag == 1, flag == 2);
                assertRpcError(
                        flag == 3 ? "not_ready" : "unsafe_state", f.start("launcher.import", importTarget(file)));
                assertTrue(f.imports == 0, "unsafe or unobserved transport prevents file insertion");
            }
            Fake changed = new Fake();
            changed.beforeInsertion = () -> changed.trackId = "22222222-2222-2222-2222-222222222222";
            assertRpcError("stale_context", changed.start("launcher.import", importTarget(file)));
            assertTrue(changed.imports == 0, "track binding rechecked immediately before native dispatch");
            Fake scrolled = new Fake();
            scrolled.beforeInsertion = () -> scrolled.bankOffset = 1;
            assertRpcError("stale_context", scrolled.start("launcher.import", importTarget(file)));
            assertTrue(scrolled.imports == 0, "slot bank offset rechecked immediately before dispatch");
            Fake occupied = new Fake();
            occupied.beforeInsertion = () -> occupied.observeContent(0, true);
            assertRpcError("target_exists", occupied.start("launcher.import", importTarget(file)));
            assertTrue(occupied.imports == 0, "late slot occupancy cannot be overwritten");
            Fake unsafe = new Fake();
            unsafe.beforeInsertion = () -> unsafe.recordNode.emit(true);
            assertRpcError("unsafe_state", unsafe.start("launcher.import", importTarget(file)));
            assertTrue(unsafe.imports == 0, "safety is rechecked at mutation boundary");
            Fake cancelled = new Fake();
            HostOperation cancelledOp = new HostOperation(cancelled.tasks.after(2000));
            cancelled.beforeInsertion = () -> cancelledOp.result.completeExceptionally(
                    Errors.notApplied(Reason.CANCELLED, "Fixture cancellation"));
            cancelled.start(cancelledOp, "launcher.import", importTarget(file));
            assertRpcError("cancelled", cancelledOp);
            assertTrue(
                    cancelled.imports == 0 && !cancelledOp.dispatched,
                    "cancellation during target resolution prevents dispatch");
            Fake expired = new Fake();
            HostOperation expiredOp = new HostOperation(expired.tasks.now() - 1);
            expired.start(expiredOp, "launcher.import", importTarget(file));
            assertRpcError("deadline", expiredOp);
            assertTrue(expired.imports == 0, "expired operation cannot dispatch insertion");
            Fake late = new Fake();
            HostOperation lateOp = new HostOperation(late.tasks.after(2000));
            boolean[] resolved = {false};
            late.beforeInsertion = () -> {
                resolved[0] = true;
                late.tasks.advance(lateOp.deadline + 1 - late.tasks.now());
            };
            late.start(lateOp, "launcher.import", importTarget(file));
            assertRpcError("deadline", lateOp);
            assertTrue(resolved[0] && late.imports == 0, "deadline rechecked after resolving insertion point");
        } finally {
            Files.delete(file);
        }
    }

    @Test
    void unresolvedImportsKeepTargetQuarantined() throws Exception {
        Path file = Files.createTempFile("launcher-quarantine-", ".arbitrary");
        try {
            Fake f = new Fake();
            HostOperation first = f.start("launcher.import", importTarget(file));
            f.drain(first);
            assertUnknown("not_observed", first);
            HostOperation second = f.start("launcher.import", importTarget(file));
            expectQuarantine(second, "launcher.import", ID);
            expectQuarantine(f.start("launcher.create", createTarget()), "launcher.import", ID);
            expectQuarantine(f.start("notes.edit", editFixture()), "launcher.import", ID);
            assertTrue(
                    f.imports == 1 && f.creates == 0 && f.writes == 0,
                    "unknown import rejects every conflicting content write before native dispatch");
            assertTrue(
                    f.call("launcher.list", trackTarget())
                                    .getAsJsonArray("slots")
                                    .size()
                            == 16,
                    "quarantine preserves launcher inspection");
            f.observeContent(0, true);
            f.next(); // A's late callback must never be attributed to B.
            expectQuarantine(second, "launcher.import", ID);
            assertTrue(
                    f.call("notes.read", window()).getAsJsonArray("notes").isEmpty(),
                    "late content remains available for read-only note inspection");
            assertTrue(
                    f.imports == 1 && f.creates == 0 && f.writes == 0 && f.nameWrites == 0,
                    "inspection does not resume or replay the unknown mutation");
            f.observeContent(0, false);
            expectQuarantine(f.start("launcher.import", importTarget(file)), "launcher.import", ID);
            JsonObject other = importTarget(file);
            other.addProperty("slotIndex", 1);
            HostOperation independent = f.start("launcher.import", other);
            assertTrue(
                    f.imports == 2 && f.importedSlot == 1,
                    "quarantine is target-specific rather than a permanent global cursor lease");
            f.observeContent(1, true);
            f.drain(independent);
            assertTrue(
                    independent.result.join().get("outcome").getAsString().equals("applied"),
                    "independent slot can complete while original remains unresolved");
            expectQuarantine(f.start("launcher.import", importTarget(file)), "launcher.import", ID);
        } finally {
            Files.delete(file);
        }
    }

    @Test
    void completedMutationsReleaseTheirTargets() throws Exception {
        Path file = Files.createTempFile("launcher-completed-", ".arbitrary");
        try {
            Fake f = new Fake();
            HostOperation first = f.start("launcher.import", importTarget(file));
            f.observeContent(0, true);
            f.drain(first);
            f.observeContent(0, false); // Independent host deletion after a positively observed import.
            HostOperation second = f.start("launcher.import", importTarget(file));
            assertTrue(
                    f.imports == 2 && !second.result.isDone(),
                    "observed import releases quarantine without reusing its earlier callback");
            f.observeContent(0, true);
            f.drain(second);
            assertTrue(
                    second.result.join().get("outcome").getAsString().equals("applied"),
                    "independent same-slot import requires its own fresh completion");
            f = new Fake();
            assertTrue(
                    f.call("launcher.create", createTarget())
                            .get("outcome")
                            .getAsString()
                            .equals("applied"),
                    "successful create observes content/name/length");
            f.observeContent(0, false);
            HostOperation imported = f.start("launcher.import", importTarget(file));
            assertTrue(f.imports == 1, "successful creation releases target for an independent empty-slot import");
            f.observeContent(0, true);
            f.drain(imported);
        } finally {
            Files.delete(file);
        }
    }

    @Test
    void completedRequestFuturesDoNotReleaseMutations() throws Exception {
        Path file = Files.createTempFile("launcher-future-", ".arbitrary");
        try {
            for (String method : new String[] {"launcher.import", "launcher.create"}) {
                for (int mode = 0; mode < 3; mode++) {
                    Fake f = new Fake();
                    f.deferCreate = true;
                    HostOperation first =
                            f.start(method, method.equals("launcher.import") ? importTarget(file) : createTarget());
                    assertTrue(
                            first.dispatched && !first.result.isDone(),
                            "fixture dispatches native mutation before request termination");
                    if (mode == 0) {
                        first.result.cancel(false);
                        assertTrue(first.result.isCancelled(), "request future can be externally cancelled");
                    } else if (mode == 1) {
                        first.fail(Errors.unknown(Reason.DEADLINE, "Fixture transport timeout"));
                        assertUnknown("deadline", first);
                    } else {
                        f.tasks.advance(first.deadline + 1 - f.tasks.now());
                        f.next();
                        assertUnknown("deadline", first);
                    }
                    f.next(); // Scheduled host tasks ignore already-completed futures, not the quarantine.
                    expectQuarantine(f.start("launcher.import", importTarget(file)), method, ID);
                    f.observeContent(0, true);
                    expectQuarantine(f.start("notes.edit", editFixture()), method, ID);
                    f.call("notes.read", window());
                    assertTrue(
                            f.imports + f.creates == 1 && f.writes == 0 && f.nameWrites == 0,
                            "late observation after future termination never resumes a content mutation");
                }
            }
        } finally {
            Files.delete(file);
        }
    }

    @Test
    void reboundTargetsRemainQuarantined() throws Exception {
        Path file = Files.createTempFile("launcher-rebound-", ".arbitrary");
        String replacement = "22222222-2222-2222-2222-222222222222";
        try {
            Fake f = new Fake();
            HostOperation first = f.start("launcher.import", importTarget(file));
            f.observeTrackId(replacement);
            f.drain(first);
            assertUnknown("stale_context", first);
            JsonObject otherTrack = importTarget(file);
            otherTrack.addProperty("trackId", replacement);
            expectQuarantine(f.start("launcher.import", otherTrack), "launcher.import", ID);
            f.observeTrackId(ID);
            expectQuarantine(f.start("launcher.import", importTarget(file)), "launcher.import", ID);
            assertTrue(f.imports == 1, "both original logical target and rebound native slot proxy retain quarantine");
            f = new Fake();
            first = f.start("launcher.import", importTarget(file));
            f.observeTrackId(replacement);
            f.observeTrackId(ID);
            f.observeContent(0, true);
            f.drain(first);
            assertUnknown("stale_context", first);
            expectQuarantine(f.start("notes.edit", editFixture()), "launcher.import", ID);
            assertTrue(
                    f.imports == 1 && f.writes == 0,
                    "returning to the old UUID cannot erase an intervening binding change");
        } finally {
            Files.delete(file);
        }
    }

    @Test
    void preDispatchFailuresDoNotQuarantine() throws Exception {
        Path file = Files.createTempFile("launcher-preflight-", ".arbitrary");
        try {
            Fake f = new Fake();
            JsonObject bad = createTarget();
            bad.addProperty("lengthBeats", 0);
            assertRpcError("invalid_params", f.start("launcher.create", bad));
            assertRpcError(
                    "invalid_file",
                    f.start("launcher.import", importTarget(file.resolveSibling(file.getFileName() + ".missing"))));
            f.observeContent(0, true);
            assertRpcError("target_exists", f.start("launcher.import", importTarget(file)));
            assertRpcError("target_exists", f.start("launcher.create", createTarget()));
            f.observeContent(0, false);
            HostOperation cancelled = new HostOperation(f.tasks.after(2000));
            f.beforeInsertion = () -> cancelled.result.completeExceptionally(
                    Errors.notApplied(Reason.CANCELLED, "Fixture pre-dispatch cancellation"));
            f.start(cancelled, "launcher.import", importTarget(file));
            assertRpcError("cancelled", cancelled);
            f.beforeInsertion = () -> {};
            HostOperation valid = f.start("launcher.import", importTarget(file));
            assertTrue(f.imports == 1 && f.creates == 0, "pre-dispatch failures leave no mutation quarantine");
            f.observeContent(0, true);
            f.drain(valid);
            assertTrue(
                    valid.result.join().get("outcome").getAsString().equals("applied"),
                    "valid request after preflight failures completes normally");
        } finally {
            Files.delete(file);
        }
    }

    @Test
    void unresolvedCreatesKeepTargetQuarantined() throws Exception {
        Path file = Files.createTempFile("launcher-create-failure-", ".arbitrary");
        try {
            for (int mode = 0; mode < 4; mode++) {
                Fake f = new Fake();
                f.deferCreate = mode == 0;
                f.failCreate = mode == 1;
                f.applyName = mode != 2;
                f.emitCreateContent = mode != 3;
                HostOperation first = f.start("launcher.create", createTarget());
                f.drain(first);
                if (mode == 1) assertUnknown("host_refused", first);
                else {
                    JsonObject out = first.result.join();
                    assertTrue(
                            out.get("outcome").getAsString().equals("unknown")
                                    && out.has("reason")
                                    && f.services.holds.list().size() == 1,
                            "unobserved creation reports the unresolved mutation and holds the slot");
                }
                f.observeContent(0, false);
                expectQuarantine(f.start("launcher.import", importTarget(file)), "launcher.create", ID);
                expectQuarantine(f.start("launcher.create", createTarget()), "launcher.create", ID);
                f.observeContent(0, true);
                expectQuarantine(f.start("notes.edit", editFixture()), "launcher.create", ID);
                int names = f.nameWrites;
                f.call("notes.read", window());
                assertTrue(
                        f.creates == 1 && f.imports == 0 && f.writes == 0 && f.nameWrites == names,
                        "failed create cannot be replayed or completed by a later read");
            }
            Fake f = new Fake();
            f.failImport = true;
            assertUnknown("host_refused", f.start("launcher.import", importTarget(file)));
            expectQuarantine(f.start("launcher.create", createTarget()), "launcher.import", ID);
            assertTrue(
                    f.imports == 1 && f.creates == 0,
                    "native insertion exception also retains dispatched mutation quarantine");
        } finally {
            Files.delete(file);
        }
    }

    private static JsonObject createTarget() {
        JsonObject p = target();
        p.addProperty("lengthBeats", 4);
        p.addProperty("name", "Quarantine fixture");
        return p;
    }

    @Test
    void slotsBeyondSceneCountAreRejectedAndNotListed() throws Exception {
        Fake f = new Fake();
        f.scenes = 1;
        assertTrue(
                f.call("launcher.list", trackTarget()).getAsJsonArray("slots").size() == 1, "list follows scene count");
        JsonObject far = createTarget();
        far.addProperty("slotIndex", 1);
        assertRpcError("out_of_bounds", f.start("launcher.create", far));
        assertTrue(f.creates == 0, "out-of-range create is never dispatched");
        HostOperation valid = f.start("launcher.create", createTarget());
        assertTrue(f.creates == 1, "out-of-range rejection leaves no quarantine");
        f.observeContent(0, true);
        f.drain(valid);
        assertTrue(
                valid.result.join().get("outcome").getAsString().equals("applied"), "in-range create still completes");
    }

    @Test
    void scenesAreAppendedAtTheEndAndCountIsObserved() throws Exception {
        Fake f = new Fake();
        f.scenes = 3;
        f.put(0, 0, 0, 60, 90, .25); // clip content in slot 0 must stay addressed by the same index
        JsonObject out = f.call("scenes.append", new JsonObject());
        assertTrue(
                out.get("sceneCountBefore").getAsInt() == 3
                        && out.get("sceneCount").getAsInt() == 4,
                "default appends one scene, count observed");
        assertTrue(
                out.get("firstNewSlotIndex").getAsInt() == 3 && f.sceneAppends == 1,
                "new slot index is the old scene count");
        JsonObject two = new JsonObject();
        two.addProperty("count", 2);
        out = f.call("scenes.append", two);
        assertTrue(
                out.get("sceneCount").getAsInt() == 6 && f.sceneAppends == 3,
                "count appends several scenes in one call");
        assertTrue(
                f.call("launcher.list", trackTarget()).getAsJsonArray("slots").size() == 6,
                "new scenes appear in the launcher window");
        JsonObject many = new JsonObject();
        many.addProperty("count", 11);
        assertRpcError("limit_reached", f.start("scenes.append", many));
        assertTrue(f.sceneAppends == 3, "over-limit append is never dispatched");
        for (double bad : new double[] {0, 17, 1.5}) {
            JsonObject p = new JsonObject();
            p.addProperty("count", bad);
            assertRpcError("invalid_params", () -> LauncherModule.validateParams("scenes.append", p));
        }
        JsonObject extra = new JsonObject();
        extra.addProperty("trackId", ID);
        assertRpcError("invalid_params", () -> LauncherModule.validateParams("scenes.append", extra));
        Fake silent = new Fake();
        silent.scenes = 2;
        silent.applyScene = false;
        HostOperation op = silent.start("scenes.append", new JsonObject());
        silent.drain(op);
        assertUnknown("not_observed", op);
        assertTrue(op.dispatched && silent.sceneAppends == 1, "unobserved count is unknown after exactly one dispatch");
        Fake create = new Fake();
        create.scenes = 1;
        JsonObject far = createTarget();
        far.addProperty("slotIndex", 1);
        HostOperation rejected = create.start("launcher.create", far);
        assertRpcError("out_of_bounds", rejected);
        {
            RpcException error = rejection(rejected);
            assertTrue(error.getMessage().contains("scenes.append"), "out-of-range message points to scenes.append");
        }
    }

    @Test
    void omittedWindowIsDerivedFromEdits() throws Exception {
        Fake f = new Fake();
        f.call("launcher.create", createTarget());
        JsonObject p = target();
        p.add("edits", fixture());
        LauncherModule.validateParams("notes.edit", p);
        LauncherModule.Window w = LauncherModule.deriveEditWindow(p);
        assertTrue(
                w.exact.compareTo(new java.math.BigDecimal("0.015625")) == 0 && w.start == 0 && w.steps == 96,
                "grid is the finest one that fits 128 steps");
        assertTrue(w.pitch == 36 && w.pitches == 32, "pitch range comes from the edits");
        JsonObject out = f.call("notes.edit", p);
        assertTrue(
                out.get("outcome").getAsString().equals("applied")
                        && out.getAsJsonObject("window").get("derived").getAsBoolean(),
                "derived window edit observed and echoed");
        assertTrue(
                f.notes.containsKey(key(0, 0, 0, 60)) && f.notes.containsKey(key(0, 0, 64, 62)),
                "notes landed on absolute steps of the derived grid");
        JsonObject quarter = target();
        JsonArray one = new JsonArray();
        one.add(set(2, 61, 90, 2));
        quarter.add("edits", one);
        assertTrue(
                LauncherModule.deriveEditWindow(quarter).exact.compareTo(new java.math.BigDecimal("0.015625")) == 0,
                "long notes fall back to the finest grid that still fits");
        JsonObject wholeNote = target();
        JsonArray whole = new JsonArray();
        whole.add(set(0, 61, 90, 256));
        wholeNote.add("edits", whole);
        LauncherModule.Window coarse = LauncherModule.deriveEditWindow(wholeNote);
        assertTrue(
                coarse.exact.compareTo(new java.math.BigDecimal("2")) == 0 && coarse.steps == 128,
                "a single 256-beat note derives a coarse grid instead of failing");
        JsonObject fine = target();
        JsonArray finer = new JsonArray();
        finer.add(set(.0625, 61, 90, .125));
        fine.add("edits", finer);
        assertTrue(
                LauncherModule.deriveEditWindow(fine).exact.compareTo(new java.math.BigDecimal("0.0078125")) == 0,
                "short fine edits use the finest grid");
        JsonObject wide = target();
        JsonArray spread = new JsonArray();
        spread.add(set(0, 60, 90, .25));
        spread.add(set(40, 60, 90, .25));
        wide.add("edits", spread);
        {
            RpcException error =
                    assertThrows(RpcException.class, () -> LauncherModule.validateParams("notes.edit", wide));
            assertTrue(
                    error.reason.wire().equals("invalid_params")
                            && error.getMessage().contains("split")
                            && error.getMessage().contains("coarser"),
                    "oversized span names the remedy");
        }
        JsonObject partial = target();
        partial.add("edits", fixture());
        partial.addProperty("steps", 8);
        assertRpcError("invalid_params", () -> LauncherModule.validateParams("notes.edit", partial));
        JsonObject odd = target();
        JsonArray off = new JsonArray();
        off.add(set(.001, 60, 90, .25));
        odd.add("edits", off);
        assertRpcError("invalid_params", () -> LauncherModule.validateParams("notes.edit", odd));
        Fake hidden = new Fake();
        hidden.call("launcher.create", createTarget());
        hidden.noteStorageStepSize = 1.0 / 128;
        hidden.put(0, 0, 0, 36, 90, .5);
        hidden.put(0, 0, 64, 36, 90, .5); // second onset at beat 0.5
        JsonObject overlap = target();
        JsonArray longNote = new JsonArray();
        longNote.add(set(0, 36, 100, 1));
        overlap.add("edits", longNote);
        HostOperation rejectedOverlap = hidden.start("notes.edit", overlap);
        hidden.drain(rejectedOverlap);
        assertUnknown("overlap", rejectedOverlap);
        assertTrue(hidden.writes == 0, "derived window cannot hide a finer existing onset");
        JsonObject explicit = window();
        explicit.add("edits", fixture());
        assertTrue(
                f.call("notes.edit", explicit).get("outcome").getAsString().equals("applied"),
                "explicit windows keep working");
    }

    @Test
    void omittedReadWindowCoversTheClipLoop() throws Exception {
        Fake f = new Fake();
        JsonObject create = createTarget();
        create.addProperty("lengthBeats", 13 * 4);
        f.call("launcher.create", create);
        f.put(0, 0, 100, 60, 90, .25); // beat 25
        JsonObject read = f.call("notes.read", target());
        JsonObject w = read.getAsJsonObject("window");
        assertTrue(
                w.get("stepSizeBeats").getAsDouble() == .25
                        && w.get("steps").getAsInt() == 128
                        && w.get("pitchCount").getAsInt() == 128,
                "long loop reads 128 steps at 1/16 note");
        assertTrue(
                !read.get("windowCoversLoop").getAsBoolean()
                        && read.get("continuation").getAsString().contains("startStep=128"),
                "uncovered loop returns a continuation, not a coarser grid");
        assertTrue(
                w.get("derived").getAsBoolean() && read.getAsJsonArray("notes").size() == 1,
                "derived read lists notes");
        Fake g = new Fake();
        g.call("launcher.create", createTarget());
        JsonObject whole = g.call("notes.read", target());
        assertTrue(
                whole.getAsJsonObject("window").get("stepSizeBeats").getAsDouble() == .125
                        && whole.getAsJsonObject("window").get("steps").getAsInt() == 32,
                "short loop is read at 1/32 note");
        assertTrue(
                whole.get("windowCoversLoop").getAsBoolean() && !whole.has("continuation"),
                "covered loop has no continuation");
        JsonObject partial = target();
        partial.addProperty("steps", 8);
        assertRpcError("invalid_params", () -> LauncherModule.validateParams("notes.read", partial));
    }

    private static JsonObject manyEdits(int count) {
        JsonObject p = target();
        JsonArray edits = new JsonArray();
        for (int i = 0; i < count; i++) edits.add(set(i * .5, 48 + i % 12, 60 + i, .25));
        p.add("edits", edits);
        return p;
    }

    @Test
    void compactEditReportsSummaryAndOnlyUnverifiedEdits() throws Exception {
        Fake f = new Fake();
        JsonObject c16 = createTarget();
        c16.addProperty("lengthBeats", 16);
        f.call("launcher.create", c16);
        JsonObject p = manyEdits(32);
        JsonObject out = f.call("notes.edit", p);
        JsonObject sum = out.getAsJsonObject("summary");
        assertTrue(
                out.get("outcome").getAsString().equals("applied")
                        && sum.get("requested").getAsInt() == 32
                        && sum.get("dispatched").getAsInt() == 32
                        && sum.get("alreadyMatched").getAsInt() == 0
                        && sum.get("verified").getAsInt() == 32
                        && sum.get("unverified").getAsInt() == 0,
                "compact summary counts every edit");
        assertTrue(
                !out.has("edits")
                        && !out.has("unverifiedEdits")
                        && !out.has("notes")
                        && out.get("notesInWindow").getAsInt() == 32,
                "verified edits and the window listing are not repeated");
        int compact = out.toString().length();
        JsonObject again = f.call("notes.edit", p);
        assertTrue(
                again.get("outcome").getAsString().equals("already_matched")
                        && again.getAsJsonObject("summary")
                                        .get("alreadyMatched")
                                        .getAsInt()
                                == 32
                        && again.getAsJsonObject("summary").get("dispatched").getAsInt() == 0,
                "unchanged edits are alreadyMatched, not applied");
        JsonObject full = manyEdits(32);
        full.addProperty("detail", "full");
        JsonObject fullOut = f.call("notes.edit", full);
        assertTrue(
                fullOut.getAsJsonArray("edits").size() == 32
                        && fullOut.getAsJsonArray("notes").size() == 32
                        && fullOut.get("outcome").getAsString().equals("already_matched"),
                "detail=full returns today's window listing and every edit");
        System.out.println("notes.edit 32 notes: compact " + compact + " chars, full "
                + fullOut.toString().length() + " chars");
        assertTrue(compact * 3 < fullOut.toString().length(), "compact is much smaller");
        Fake silent = new Fake();
        silent.call("launcher.create", createTarget());
        silent.emitNotes = false;
        JsonObject bad = silent.call("notes.edit", manyEdits(3));
        assertTrue(bad.get("outcome").getAsString().equals("unknown"), "fail-closed outcome is unchanged");
        assertTrue(
                bad.getAsJsonObject("summary").get("unverified").getAsInt() == 3
                        && bad.getAsJsonArray("unverifiedEdits").size() == 3,
                "every unverified edit is listed");
        JsonObject item = bad.getAsJsonArray("unverifiedEdits").get(0).getAsJsonObject();
        assertTrue(
                item.get("outcome").getAsString().equals("unknown")
                        && item.get("observed").getAsJsonObject().get("pitch").getAsInt() == 48,
                "unverified entry carries the observed cell");
        JsonObject wrong = manyEdits(1);
        wrong.addProperty("detail", "verbose");
        invalid(wrong);
        JsonObject read = window();
        read.addProperty("detail", "full");
        assertRpcError("invalid_params", () -> LauncherModule.validateParams("notes.read", read));
    }

    @Test
    void echoingAReadRowIsAlreadyMatched() throws Exception {
        Fake f = new Fake();
        f.slots[0].content = true;
        f.put(0, 0, 0, 60, 64, .5);
        f.notes.get(key(0, 0, 0, 60)).exact = 0.5; // GUI value 0.5 reads as MIDI 64
        JsonObject row =
                f.call("notes.read", window()).getAsJsonArray("notes").get(0).getAsJsonObject();
        assertTrue(row.get("velocity").getAsInt() == 64, "host 0.5 reads as MIDI 64");
        JsonObject p = window();
        JsonArray edits = new JsonArray();
        edits.add(set(
                row.get("beat").getAsDouble(),
                row.get("pitch").getAsInt(),
                row.get("velocity").getAsInt(),
                row.get("durationBeats").getAsDouble()));
        p.add("edits", edits);
        JsonObject out = f.call("notes.edit", p);
        assertTrue(
                f.writes == 0
                        && out.getAsJsonObject("summary").get("alreadyMatched").getAsInt() == 1
                        && out.getAsJsonObject("summary").get("dispatched").getAsInt() == 0
                        && out.get("outcome").getAsString().equals("already_matched"),
                "echoing a read row back is alreadyMatched with no rewrite");
    }

    @Test
    void noteRowsUseEditUnitsAndCarryNoConstantProse() throws Exception {
        Fake f = new Fake();
        f.slots[0].content = true;
        f.put(0, 0, 4, 61, 100, 1.5);
        JsonObject read = f.call("notes.read", window());
        JsonObject row = read.getAsJsonArray("notes").get(0).getAsJsonObject();
        assertTrue(
                row.keySet().equals(java.util.Set.of("beat", "pitch", "velocity", "durationBeats")),
                "rows are beat/pitch/velocity/durationBeats only");
        assertTrue(
                row.get("velocity").getAsInt() == 100 && row.get("beat").getAsDouble() == 1.0,
                "velocity is MIDI 1..127 and beat is absolute");
        for (String gone : new String[] {
            "gridReadinessLimitation",
            "onsetPrecision",
            "selectionSideEffect",
            "identityVerification",
            "completeClip",
            "scope"
        }) assertTrue(!read.has(gone), gone + " no longer repeated per response");
        assertTrue(
                read.has("gridReadiness")
                        && read.has("completeWindow")
                        && read.get("window").getAsJsonObject().get("channel").getAsInt() == 0,
                "machine-actionable flags and channel remain");
        f.notes.get(key(0, 0, 4, 61)); // sustain marker row below
        f.put(0, 0, 0, 70, 60, 4);
        f.slots[0].length = 8;
        JsonObject later = window();
        later.addProperty("startStep", 2);
        later.addProperty("steps", 4);
        for (JsonElement v : f.call("notes.read", later).getAsJsonArray("continuingAtWindowStart"))
            assertTrue(
                    v.getAsJsonObject().get("state").getAsString().equals("NoteSustain"),
                    "continuing rows are marked, not copyable onsets");
        assertTrue(
                LauncherModule.midiVelocity(1 / 127.0) == 1
                        && LauncherModule.midiVelocity(1.0) == 127
                        && LauncherModule.midiVelocity(0.0) == 1,
                "velocity conversion stays within 1..127");
    }

    @Test
    void launcherListAndContextExposeTheCursorClip() throws Exception {
        Fake f = new Fake();
        f.call("launcher.create", createTarget());
        f.slots[2].content = true;
        f.slots[2].name = "Other";
        f.selected = 0;
        JsonObject list = f.call("launcher.list", trackTarget());
        assertTrue(
                list.getAsJsonArray("slots")
                                .get(0)
                                .getAsJsonObject()
                                .get("selected")
                                .getAsBoolean()
                        && !list.getAsJsonArray("slots")
                                .get(1)
                                .getAsJsonObject()
                                .get("selected")
                                .getAsBoolean(),
                "launcher.list marks the cursor clip slot");
        assertTrue(f.module.cursorSlotIndex(0, f.tracks[0]) == 0, "cursor clip resolves to its slot");
        f.selected = 2;
        f.writes = 0;
        assertTrue(f.module.cursorSlotIndex(0, f.tracks[0]) == 2, "cursor follows the UI selection");
        f.selected = 1; // selected but empty: no clip is the cursor clip
        assertTrue(f.module.cursorSlotIndex(0, f.tracks[0]) == -1, "an empty selected slot is not a cursor clip");
        com.google.gson.JsonArray occupied = f.module.occupiedSlots(0, f.tracks[0]);
        assertTrue(
                occupied.size() == 2
                        && occupied.get(1).getAsJsonObject().get("slotIndex").getAsInt() == 2
                        && occupied.get(1)
                                .getAsJsonObject()
                                .get("name")
                                .getAsString()
                                .equals("Other"),
                "only occupied slots listed");
        f.bankOffset = 1;
        assertTrue(f.module.occupiedSlots(0, f.tracks[0]) == null, "unobserved bank offset yields null, not a guess");
        assertTrue(
                f.module.cursorSlotIndex(0, f.tracks[0]) == -2,
                "cursor clip is not determinable off scene offset zero");
        assertTrue(f.writes == 0 && f.creates == 1, "context reads never write");
    }

    @Test
    void createWritesNotesAfterObservingTheClip() throws Exception {
        Fake f = new Fake();
        JsonObject p = createTarget();
        JsonArray notes = new JsonArray();
        notes.add(set(0, 60, 100, 1));
        notes.add(set(1.5, 64, 80, .5));
        notes.add(set(3, 67, 90, 1));
        p.add("notes", notes);
        JsonObject out = f.call("launcher.create", p);
        assertTrue(
                out.get("outcome").getAsString().equals("applied")
                        && out.get("verification").getAsString().equals("slot_content_name_length"),
                "create verified before notes");
        JsonObject write = out.getAsJsonObject("noteWrite");
        assertTrue(
                write.get("outcome").getAsString().equals("applied")
                        && write.getAsJsonObject("summary").get("verified").getAsInt() == 3
                        && !write.has("unverifiedEdits")
                        && !write.has("edits")
                        && !write.has("notes"),
                "compact verification returned in the same call");
        assertTrue(
                f.notes.containsKey(key(0, 0, 0, 60)) && f.notes.size() == 3 && f.creates == 1,
                "all notes written after exactly one create");
        assertTrue(
                write.getAsJsonObject("window").get("derived").getAsBoolean() && out.get("slot") != null,
                "create fields and derived window both reported");
        JsonObject early = createTarget();
        JsonArray late = new JsonArray();
        late.add(set(3.5, 60, 90, 1));
        early.add("notes", late);
        assertRpcError("invalid_params", () -> LauncherModule.validateParams("launcher.create", early));
        JsonObject clearOp = createTarget();
        JsonArray bad = new JsonArray();
        JsonObject c = set(0, 60, 90, 1);
        c.addProperty("op", "clear");
        bad.add(c);
        clearOp.add("notes", bad);
        assertRpcError("invalid_params", () -> LauncherModule.validateParams("launcher.create", clearOp));
        JsonObject lone = createTarget();
        lone.addProperty("channel", 1);
        assertRpcError("invalid_params", () -> LauncherModule.validateParams("launcher.create", lone));
        Fake g = new Fake();
        g.deferCreate = true;
        JsonObject unobserved = createTarget();
        unobserved.add("notes", notes.deepCopy());
        HostOperation op = g.start("launcher.create", unobserved);
        g.drain(op);
        JsonObject result = op.result.join();
        assertTrue(
                result.get("outcome").getAsString().equals("unknown")
                        && result.get("noteWrite").getAsString().equals("not_attempted")
                        && g.writes == 0,
                "unobserved create writes no notes and stays unresolved");
        Fake lost = new Fake();
        lost.afterPlayStopRead = () -> {
            lost.afterPlayStopRead = () -> {};
            lost.trackId = "22222222-2222-2222-2222-222222222222";
        };
        HostOperation stale = lost.start("launcher.create", unobserved.deepCopy());
        lost.drain(stale);
        JsonObject staleResult = stale.result.join();
        assertTrue(
                staleResult.get("outcome").getAsString().equals("not_applied")
                        && staleResult
                                .getAsJsonObject("noteWrite")
                                .get("reason")
                                .getAsString()
                                .equals("stale_context")
                        && staleResult.has("slot"),
                "a note stage refused before dispatch is not_applied inside noteWrite: " + staleResult);
        Fake k = new Fake();
        k.failWriteAt = 1;
        HostOperation broken = k.start("launcher.create", unobserved.deepCopy());
        k.drain(broken);
        JsonObject kept = broken.result.join();
        assertTrue(
                kept.get("verification").getAsString().equals("slot_content_name_length") && k.slots[0].content,
                "observed create survives a failing note stage");
        assertTrue(
                kept.get("outcome").getAsString().equals("unknown")
                        && kept.getAsJsonObject("noteWrite")
                                .get("reason")
                                .getAsString()
                                .equals("host_refused")
                        && kept.getAsJsonObject("noteWrite")
                                .get("outcome")
                                .getAsString()
                                .equals("unknown"),
                "note stage failure is reported inside noteWrite");
        Fake h = new Fake();
        h.emitNotes = false;
        HostOperation silent = h.start("launcher.create", unobserved.deepCopy());
        h.drain(silent);
        JsonObject partial = silent.result.join();
        assertTrue(
                partial.get("outcome").getAsString().equals("unknown")
                        && partial.getAsJsonObject("noteWrite")
                                .get("outcome")
                                .getAsString()
                                .equals("unknown"),
                "unobserved note writes stay unknown with per-edit evidence");
    }

    private static void expectQuarantine(HostOperation op, String method, String originalId) {
        assertRpcError("unresolved", op);
        {
            RpcException error = rejection(op);
            String message = error.getMessage();
            assertTrue(
                    message.contains(method)
                            && message.contains(originalId)
                            && message.contains("slotIndex=0")
                            && error.next.contains("ack " + LauncherStructureLock.MUTATION),
                    "error identifies the original unresolved mutation and the hold to ack");
        }
        assertTrue(!op.dispatched, "quarantine rejects before any native content mutation");
    }

    // ---- clips.edit ----
    private static JsonObject clipEdit(String op, Object... fields) {
        JsonObject p = target();
        p.addProperty("expectedClipName", "Riff");
        p.addProperty("op", op);
        for (int i = 0; i < fields.length; i += 2) {
            if (fields[i + 1] instanceof Number) p.addProperty((String) fields[i], (Number) fields[i + 1]);
            else p.addProperty((String) fields[i], (String) fields[i + 1]);
        }
        return p;
    }

    private static JsonObject clipEditNamed(String expected, String op, Object... fields) {
        JsonObject p = clipEdit(op, fields);
        p.addProperty("expectedClipName", expected);
        return p;
    }

    private static Fake riff() {
        Fake f = new Fake();
        f.slots[0].content = true;
        f.slots[0].name = "Riff";
        f.put(0, 0, 0, 60, 100, 4);
        f.put(0, 0, 8, 64, 90, 4);
        f.put(0, 0, 3, 67, 80, 2);
        return f;
    }

    private static JsonObject run(Fake f, JsonObject p) throws Exception {
        return f.call("clips.edit", p);
    }

    private static HostOperation fail(Fake f, JsonObject p, String code, String outcome) throws Exception {
        HostOperation op = f.start("clips.edit", p);
        f.drain(op);
        if (outcome.equals("unknown")) assertUnknown(code, op);
        else assertRpcError(code, op);
        return op;
    }

    private static void validClipEdits(JsonObject... all) {
        for (JsonObject p : all) LauncherModule.validateParams("clips.edit", p);
    }

    private static void rejectClipEdit(JsonObject p) {
        assertRpcError("invalid_params", () -> LauncherModule.validateParams("clips.edit", p));
    }

    private static String repeat(int n) {
        return "a".repeat(n);
    }

    @Test
    void clipEditValidation() {
        validClipEdits(
                clipEdit("rename", "name", repeat(128)),
                clipEdit("resize", "lengthBeats", 4096),
                clipEdit("resize", "lengthBeats", 1.5),
                clipEdit("resize", "lengthBeats", 1.0 / 128),
                clipEdit("color", "rgb", "#aB09fF"),
                clipEdit("transpose", "semitones", -48),
                clipEdit("transpose", "semitones", 48),
                clipEdit("quantize", "amount", 0.01),
                clipEdit("quantize", "amount", 1),
                clipEdit("duplicateContent"),
                clipEdit("delete"),
                clipEdit("copyTo", "targetSlotIndex", 15));
        JsonObject longName = clipEdit("delete");
        longName.addProperty("expectedClipName", repeat(256));
        validClipEdits(longName);
        longName.addProperty("expectedClipName", repeat(257));
        rejectClipEdit(longName);
        rejectClipEdit(clipEdit("bogus"));
        rejectClipEdit(clipEdit("rename"));
        rejectClipEdit(clipEdit("rename", "name", ""));
        rejectClipEdit(clipEdit("rename", "name", repeat(129)));
        rejectClipEdit(clipEdit("rename", "name", "x", "semitones", 1));
        rejectClipEdit(clipEdit("resize", "lengthBeats", 0));
        rejectClipEdit(clipEdit("resize", "lengthBeats", 1.0 / 256));
        rejectClipEdit(clipEdit("resize", "lengthBeats", 4097));
        rejectClipEdit(clipEdit("color", "rgb", "ff0000"));
        rejectClipEdit(clipEdit("color", "rgb", "#ff00"));
        rejectClipEdit(clipEdit("color", "rgb", "#gg0000"));
        rejectClipEdit(clipEdit("transpose", "semitones", 49));
        rejectClipEdit(clipEdit("transpose", "semitones", -49));
        rejectClipEdit(clipEdit("quantize", "amount", 0));
        rejectClipEdit(clipEdit("transpose", "semitones", 0));
        rejectClipEdit(clipEdit("quantize", "amount", 1.01));
        rejectClipEdit(clipEdit("quantize", "amount", -0.01));
        rejectClipEdit(clipEdit("copyTo", "targetSlotIndex", 16));
        rejectClipEdit(clipEdit("copyTo"));
        rejectClipEdit(clipEdit("delete", "name", "x"));
        rejectClipEdit(clipEdit("duplicateContent", "amount", 1));
        JsonObject noName = clipEdit("delete");
        noName.remove("expectedClipName");
        rejectClipEdit(noName);
        JsonObject extra = clipEdit("delete");
        extra.addProperty("force", true);
        rejectClipEdit(extra);
    }

    @Test
    void clipEditRenameResizeColor() throws Exception {
        Fake f = riff();
        f.selected = 5;
        assertRpcError("stale_target", f.start("clips.edit", clipEditNamed("Wrong", "rename", "name", "Verse")));
        assertTrue(
                f.nameWrites == 0 && f.selected == 5,
                "a mismatched expectedClipName is rejected before the cursor moves or anything is written");
        JsonObject r = run(f, clipEdit("rename", "name", "Verse"));
        assertTrue(
                r.get("outcome").getAsString().equals("applied")
                        && r.get("verification").getAsString().equals("slot_name")
                        && f.slots[0].name.equals("Verse")
                        && f.nameWrites == 1,
                "rename is verified by name readback");
        assertTrue(f.services.holds.list().isEmpty(), "rename does not quarantine");
        f = riff();
        r = run(f, clipEdit("rename", "name", "Riff"));
        assertTrue(
                r.get("outcome").getAsString().equals("already_matched") && f.nameWrites == 0,
                "matching name is not rewritten");
        f = riff();
        f.applyName = false;
        HostOperation op = f.start("clips.edit", clipEdit("rename", "name", "Verse"));
        f.drain(op);
        r = op.result.join();
        assertTrue(
                r.get("outcome").getAsString().equals("unknown")
                        && r.get("reason").getAsString().equals("readback_mismatch")
                        && f.services.holds.list().isEmpty(),
                "unread rename is unknown, idempotent so no hold");

        f = riff();
        r = run(f, clipEdit("resize", "lengthBeats", 8));
        assertTrue(
                r.get("outcome").getAsString().equals("applied")
                        && f.slots[0].length == 8
                        && f.slots[0].playStop == 8
                        && r.get("loopLengthBeats").getAsDouble() == 8,
                "resize is verified by loop length and play stop readback");
        f = riff();
        assertTrue(
                run(f, clipEdit("resize", "lengthBeats", 4))
                                .get("outcome")
                                .getAsString()
                                .equals("already_matched")
                        && f.loopSets == 0,
                "same length is not rewritten");
        f = riff();
        f.applyLoop = false;
        op = f.start("clips.edit", clipEdit("resize", "lengthBeats", 8));
        f.drain(op);
        assertTrue(
                op.result.join().get("reason").getAsString().equals("readback_mismatch"),
                "unobserved loop length is not reported as done");
        f = riff();
        f.applyStop = false;
        op = f.start("clips.edit", clipEdit("resize", "lengthBeats", 8));
        f.drain(op);
        assertTrue(
                op.result.join().get("outcome").getAsString().equals("unknown"),
                "unobserved play stop is not reported as done");

        f = riff();
        r = run(f, clipEdit("color", "rgb", "#ff8800"));
        assertTrue(
                r.get("outcome").getAsString().equals("applied")
                        && r.get("color").getAsString().equals("#ff8800")
                        && f.colorSets == 1,
                "color is verified by readback");
        assertTrue(
                run(f, clipEdit("color", "rgb", "#ff8800"))
                                .get("outcome")
                                .getAsString()
                                .equals("already_matched")
                        && f.colorSets == 1,
                "matching color is not rewritten");
        f = riff();
        f.applyColor = false;
        op = f.start("clips.edit", clipEdit("color", "rgb", "#ff8800"));
        f.drain(op);
        assertTrue(
                op.result.join().get("reason").getAsString().equals("readback_mismatch"),
                "unobserved color is unknown");
    }

    @Test
    void clipEditNoteOps() throws Exception {
        Fake f = riff();
        JsonObject r = run(f, clipEdit("transpose", "semitones", 2));
        JsonObject c = r.getAsJsonObject("compared");
        assertTrue(
                r.get("outcome").getAsString().equals("applied")
                        && f.transposes == 1
                        && f.notes.containsKey(key(0, 0, 0, 62))
                        && !f.notes.containsKey(key(0, 0, 0, 60)),
                "transpose shifts the cells");
        assertTrue(
                c.get("notesBefore").getAsInt() == 3
                        && c.get("notesAfter").getAsInt() == 3
                        && c.get("pitchMinBefore").getAsInt() == 60
                        && c.get("pitchMinAfter").getAsInt() == 62
                        && c.get("pitchMaxAfter").getAsInt() == 69
                        && c.get("verifiedChannel").getAsInt() == 0,
                "transpose reports what was compared");
        assertTrue(f.services.holds.list().isEmpty(), "an observed transpose releases its mutation");
        assertTrue(f.selected == 0, "the clip was selected on the cursor");

        f = riff();
        f.put(0, 0, 16, 120, 90, 2);
        fail(f, clipEdit("transpose", "semitones", 12), "invalid_params", "not_applied");
        assertTrue(f.transposes == 0 && f.services.holds.list().isEmpty(), "out-of-range transpose changes nothing");
        Fake empty = new Fake();
        empty.slots[0].content = true;
        empty.slots[0].name = "Riff";
        fail(empty, clipEdit("transpose", "semitones", 2), "no_notes", "not_applied");
        f = riff();
        f.slots[0].length = 64;
        fail(f, clipEdit("transpose", "semitones", 2), "unverifiable_clip", "not_applied");
        assertTrue(f.transposes == 0, "unverifiable clip is rejected before dispatch");

        f = riff();
        f.applyClipOp = false;
        fail(f, clipEdit("transpose", "semitones", 2), "not_observed", "unknown");
        assertTrue(
                f.transposes == 1 && f.services.holds.list().size() == 1,
                "unobserved transpose latches the unresolved mutation list");
        expectQuarantine(f.start("clips.edit", clipEdit("rename", "name", "Verse")), "clips.edit transpose", ID);
        expectQuarantine(f.start("notes.edit", editFixture()), "clips.edit transpose", ID);
        f.call("notes.read", window());
        assertTrue(f.transposes == 1 && f.nameWrites == 0, "no retry and no further write on the quarantined slot");

        f = riff();
        r = run(f, clipEdit("quantize", "amount", 1));
        c = r.getAsJsonObject("compared");
        assertTrue(
                r.get("outcome").getAsString().equals("applied")
                        && f.notes.containsKey(key(0, 0, 4, 67))
                        && f.quantizes == 1
                        && c.get("notesBefore").getAsInt() == 3
                        && c.get("notesAfter").getAsInt() == 3,
                "quantize is observed with count and pitches preserved");
        f = riff();
        f.quantizeMoves = false;
        fail(f, clipEdit("quantize", "amount", 0.5), "not_observed", "unknown");
        assertTrue(
                f.services.holds.list().size() == 1,
                "partial quantize with no observed change is unknown and quarantined");
        f = riff();
        f.put(0, 0, 4, 67, 80, 2);
        fail(f, clipEdit("quantize", "amount", 1), "readback_mismatch", "unknown");
        assertTrue(
                f.services.holds.list().size() == 1,
                "quantize that changes the onset count is unknown and quarantined");
        f = riff();
        f.quantizeMoves = false;
        r = run(f, clipEdit("quantize", "amount", 1));
        assertTrue(
                r.get("outcome").getAsString().equals("unknown")
                        && r.get("reason").getAsString().equals("not_observed")
                        && f.services.holds.list().isEmpty(),
                "amount 1 with no movement is idempotent: unknown, no hold");

        f = riff();
        r = run(f, clipEdit("duplicateContent"));
        assertTrue(
                r.get("outcome").getAsString().equals("applied")
                        && f.slots[0].length == 8
                        && r.getAsJsonObject("compared")
                                        .get("loopLengthBeatsAfter")
                                        .getAsDouble()
                                == 8,
                "duplicateContent is verified by the doubled loop");
        f = riff();
        f.applyClipOp = false;
        fail(f, clipEdit("duplicateContent"), "not_observed", "unknown");
        assertTrue(f.services.holds.list().size() == 1, "unobserved duplicateContent is quarantined");
    }

    // ---- launcher.set launch fields (public cursor-clip properties) ----

    private static JsonObject launchSet(Object... fields) {
        JsonObject p = target();
        p.addProperty("expectedClipName", "Riff");
        for (int i = 0; i < fields.length; i += 2) {
            if (fields[i + 1] instanceof Number) p.addProperty((String) fields[i], (Number) fields[i + 1]);
            else if (fields[i + 1] instanceof Boolean) p.addProperty((String) fields[i], (Boolean) fields[i + 1]);
            else p.addProperty((String) fields[i], (String) fields[i + 1]);
        }
        return p;
    }

    private static HostOperation startSettings(Fake f, LauncherSettingsModule settings, JsonObject p) {
        HostOperation op = new HostOperation(f.tasks.after(2000));
        try {
            settings.validate("launcher.set", p);
            f.services.enqueue(op, () -> settings.dispatch(op, "launcher.set", p));
            f.tasks.take().run();
        } catch (RuntimeException error) {
            op.fail(error);
        }
        return op;
    }

    @Test
    void contextGuardsAndUnreadableGrids() throws Exception {
        assertRpcError("unknown_method", () -> LauncherModule.validateParams("launcher.bogus", new JsonObject()));
        Fake offset = riff();
        offset.bankOffset = 1;
        assertRpcError("stale_context", offset.start("launcher.list", trackTarget()));
        LauncherSettingsModule settings = offset.services.launcherSettings(offset.module);
        assertRpcError("stale_context", startSettings(offset, settings, launchSet("launchMode", "gate")));

        Fake renamed = riff();
        renamed.onSelect = () -> renamed.slots[0].name = "Changed";
        settings = renamed.services.launcherSettings(renamed.module);
        HostOperation stale = startSettings(renamed, settings, launchSet("launchMode", "gate"));
        renamed.drain(stale);
        assertRpcError("stale_target", stale);

        Fake moved = riff();
        moved.slots[1].content = true;
        moved.onSetName = () -> moved.selected = 1;
        moved.applyName = false;
        HostOperation lost = moved.start("clips.edit", clipEdit("rename", "name", "Verse"));
        moved.drain(lost);
        assertUnknown("stale_context", lost);

        Fake grid = riff();
        grid.unreadableFromStep = 0;
        fail(grid, clipEdit("transpose", "semitones", 2), "not_ready", "not_applied");
        grid = riff();
        grid.unreadableFromStep = 0;
        JsonObject edit = window();
        JsonArray edits = new JsonArray();
        edits.add(set(0, 60, 100, .5));
        edit.add("edits", edits);
        assertUnknown("not_ready", grid.startAndDrain("notes.edit", edit));
        grid = riff();
        grid.unreadableFromStep = 1;
        assertUnknown("not_ready", grid.startAndDrain("notes.edit", edit));
        grid = riff();
        grid.unreadableFromStep = 0;
        assertUnknown("not_ready", grid.startAndDrain("notes.read", window()));
    }

    @Test
    void launcherSetLaunchFields() throws Exception {
        // Validation: type checks, no empty request, and the two write paths never mix in one call.
        for (JsonObject bad : List.of(
                launchSet("launchQuantization", 1),
                launchSet("launchMode", ""),
                launchSet("useLoopStartAsQuantizationReference", "yes"),
                launchSet("launchMode", "trigger", "nextAction", "stop"),
                launchSet("launchQuantization", "1", "nextActionEnabled", true)))
            assertRpcError("invalid_params", () -> LauncherSettingsModule.validateParams("launcher.set", bad));
        LauncherSettingsModule.validateParams(
                "launcher.set",
                launchSet(
                        "launchQuantization",
                        "1/4",
                        "launchMode",
                        "trigger",
                        "useLoopStartAsQuantizationReference",
                        true));

        Fake f = riff();
        f.selected = 5;
        LauncherSettingsModule settings = f.services.launcherSettings(f.module);
        HostOperation op = startSettings(
                f, settings, launchSet("launchQuantization", "1/4", "useLoopStartAsQuantizationReference", true));
        f.drain(op);
        JsonObject r = op.result.join();
        assertTrue(
                r.get("outcome").getAsString().equals("applied")
                        && f.launchQuant.equals("1/4")
                        && f.useLoopStart
                        && f.launchSets == 2,
                "launch fields are written and read back from the cursor clip");
        assertTrue(
                f.selected == 0 && r.get("selectionSideEffect").getAsString().equals("launcher_cursor_clip_selected"),
                "the selection side effect is reported");
        assertTrue(
                r.getAsJsonObject("fields")
                        .getAsJsonObject("launchQuantization")
                        .get("outcome")
                        .getAsString()
                        .equals("applied"),
                "per-field applied needs a callback after the write");
        assertTrue(
                r.getAsJsonObject("options").getAsJsonArray("launchMode").size() == 3,
                "options come from the host's own enum definition");
        assertTrue(f.services.holds.list().isEmpty(), "idempotent absolute setters do not quarantine");

        f = riff();
        settings = f.services.launcherSettings(f.module);
        op = startSettings(f, settings, launchSet("launchMode", "from_group", "launchQuantization", "default"));
        f.drain(op);
        r = op.result.join();
        assertTrue(
                r.get("outcome").getAsString().equals("already_matched")
                        && f.launchSets == 0
                        && r.getAsJsonObject("fields")
                                .getAsJsonObject("launchMode")
                                .get("outcome")
                                .getAsString()
                                .equals("already_matched"),
                "matching fields are not rewritten");
        f = riff();
        f.emitLaunch = false;
        settings = f.services.launcherSettings(f.module);
        op = startSettings(f, settings, launchSet("launchMode", "gate"));
        f.drain(op);
        r = op.result.join();
        assertTrue(
                f.launchMode.equals("gate")
                        && r.get("outcome").getAsString().equals("unknown")
                        && r.get("reason").getAsString().equals("not_observed")
                        && r.getAsJsonObject("fields")
                                .getAsJsonObject("launchMode")
                                .get("reason")
                                .getAsString()
                                .equals("not_observed"),
                "a matching readback without a callback after the write is not observed");

        f = riff();
        f.selected = 5;
        settings = f.services.launcherSettings(f.module);
        op = startSettings(f, settings, launchSet("launchMode", "bogus"));
        f.drain(op);
        assertRpcError("invalid_params", op);
        assertTrue(
                f.launchSets == 0 && f.selected == 5,
                "an option the host does not list is refused before selecting or writing");
        op = startSettings(f, settings, launchSet("launchMode", "trigger", "expectedClipName", "Other"));
        f.drain(op);
        assertRpcError("stale_target", op);

        f = riff();
        settings = f.services.launcherSettings(f.module);
        f.applyLaunch = false;
        op = startSettings(f, settings, launchSet("launchMode", "gate"));
        f.drain(op);
        r = op.result.join();
        assertTrue(
                r.get("outcome").getAsString().equals("unknown")
                        && r.getAsJsonObject("fields")
                                .getAsJsonObject("launchMode")
                                .get("reason")
                                .getAsString()
                                .equals("readback_mismatch"),
                "an unread write is unknown");

        f = new Fake(true, true, false, false);
        f.slots[0].content = true;
        f.slots[0].name = "Riff";
        settings = f.services.launcherSettings(f.module);
        op = startSettings(f, settings, launchSet("launchMode", "gate"));
        f.drain(op);
        assertRpcError("unsafe_state", op);
        assertTrue(f.launchSets == 0, "a recording state blocks the write");
    }

    @Test
    void clipEditDeleteAndCopy() throws Exception {
        Fake f = riff();
        JsonObject r = run(f, clipEdit("delete"));
        assertTrue(
                r.get("outcome").getAsString().equals("applied")
                        && r.get("verification").getAsString().equals("slot_empty")
                        && !f.slots[0].content
                        && f.deletes == 1
                        && f.services.holds.list().isEmpty(),
                "delete is verified by the empty slot");
        f = riff();
        f.slots[0].playing = true;
        fail(f, clipEdit("delete"), "unsafe_state", "not_applied");
        f = new Fake(true, true, false, false);
        f.slots[0].content = true;
        f.slots[0].name = "Riff";
        fail(f, clipEdit("delete"), "unsafe_state", "not_applied");
        assertTrue(f.deletes == 0, "delete is blocked while recording or playing");
        f = riff();
        f.applyDelete = false;
        fail(f, clipEdit("delete"), "not_observed", "unknown");
        assertTrue(
                f.deletes == 1 && f.services.holds.list().size() == 1, "unobserved delete is unknown and quarantined");
        expectQuarantine(f.start("launcher.create", createTarget()), "clips.edit delete", ID);
        f = riff();
        fail(f, clipEditNamed("Other", "delete"), "stale_target", "not_applied");
        assertTrue(f.deletes == 0, "stale name never reaches delete");

        f = riff();
        r = run(f, clipEdit("copyTo", "targetSlotIndex", 1));
        assertTrue(
                r.get("outcome").getAsString().equals("applied")
                        && f.slots[1].content
                        && f.slots[1].name.equals("Riff")
                        && f.copies == 1
                        && r.get("targetSlotIndex").getAsInt() == 1
                        && f.services.holds.list().isEmpty(),
                "copyTo is verified by target content and name");
        f = riff();
        f.slots[1].content = true;
        fail(f, clipEdit("copyTo", "targetSlotIndex", 1), "target_exists", "not_applied");
        fail(f, clipEdit("copyTo", "targetSlotIndex", 0), "target_exists", "not_applied");
        f = riff();
        f.scenes = 1;
        fail(f, clipEdit("copyTo", "targetSlotIndex", 1), "out_of_bounds", "not_applied");
        assertTrue(f.copies == 0, "rejected copies never dispatch");
        f = riff();
        f.copyName = "Riff copy";
        r = run(f, clipEdit("copyTo", "targetSlotIndex", 1));
        assertTrue(
                r.get("outcome").getAsString().equals("applied")
                        && r.getAsJsonObject("nameChanged")
                                .get("to")
                                .getAsString()
                                .equals("Riff copy")
                        && f.services.holds.list().isEmpty(),
                "a renamed copy is observed with nameChanged");
        assertTrue(f.slots[0].content && f.slots[0].name.equals("Riff"), "the source clip is untouched");
        f = riff();
        f.applyCopy = false;
        fail(f, clipEdit("copyTo", "targetSlotIndex", 1), "not_observed", "unknown");
        assertTrue(
                f.copies == 1 && f.services.holds.list().size() == 1,
                "unobserved copy is unknown and quarantines the target");
        JsonObject create = createTarget();
        create.addProperty("slotIndex", 1);
        HostOperation blocked = f.start("launcher.create", create);
        assertRpcError("unresolved", blocked);
        f.call("notes.read", window());
    }

    private static JsonObject importTarget(Path path) {
        JsonObject p = target();
        p.addProperty("path", path.toString());
        return p;
    }

    private static JsonObject editFixture() {
        JsonObject p = window();
        p.add("edits", fixture());
        return p;
    }

    private static JsonObject trackTarget() {
        JsonObject p = new JsonObject();
        p.addProperty("trackId", ID);
        return p;
    }

    private static JsonObject target() {
        JsonObject p = trackTarget();
        p.addProperty("slotIndex", 0);
        return p;
    }

    private static JsonObject window() {
        JsonObject p = target();
        p.addProperty("channel", 0);
        p.addProperty("stepSizeBeats", .25);
        p.addProperty("startStep", 0);
        p.addProperty("steps", 16);
        p.addProperty("pitchStart", 0);
        p.addProperty("pitchCount", 128);
        return p;
    }

    private static JsonObject set(double beat, int pitch, int velocity, double duration) {
        JsonObject e = new JsonObject();
        e.addProperty("op", "set");
        e.addProperty("beat", beat);
        e.addProperty("pitch", pitch);
        e.addProperty("velocity", velocity);
        e.addProperty("durationBeats", duration);
        return e;
    }

    private static JsonArray fixture() {
        JsonArray edits = new JsonArray();
        edits.add(set(0, 60, 100, .5));
        edits.add(set(0, 64, 80, .5));
        edits.add(set(0, 67, 90, .5));
        edits.add(set(1, 62, 100, .5));
        edits.add(set(0, 36, 110, .25));
        return edits;
    }

    private static JsonObject find(JsonObject result, int pitch, double beat) {
        for (JsonElement v : result.getAsJsonArray("notes")) {
            JsonObject n = v.getAsJsonObject();
            if (n.get("pitch").getAsInt() == pitch && n.get("beat").getAsDouble() == beat) return n;
        }
        throw new AssertionError("missing observed note");
    }

    private static void invalid(JsonObject p) {
        assertRpcError("invalid_params", () -> LauncherModule.validateParams("notes.edit", p));
    }

    private static String key(int slot, int channel, int step, int pitch) {
        return slot + ":" + channel + ":" + step + ":" + pitch;
    }

    private static final class Fake {
        final HostFixture base = HostFixture.withoutServices();
        final ScheduledTasks tasks = base.tasks;
        final Map<String, Note> notes = new HashMap<>();
        final SlotData[] slots = new SlotData[16];
        final Track[] tracks = new Track[HostLimits.TRACKS];
        final TestServices services;
        final LauncherModule module;
        NoteStepChangedCallback observer;
        int scenes = 16;
        int selected, start, writes, creates, failWriteAt, nameWrites, playbackDispatches, stopWrites;
        double size = .25, loopStart;
        String trackId = ID;
        boolean emitNotes = true, launchPlaying, stopped = true;
        boolean emitPlaybackState = true, queueStop, stopQueued;
        boolean deferGridChanges;
        boolean applyStop = true;
        boolean deferCreate, failCreate, failImport, emitCreateContent = true, applyName = true;
        Double pendingSize;
        Integer pendingStart;
        Double noteStorageStepSize;
        long gridRefreshDelayMillis, gridReadyAt;
        int imports, importedSlot = -1, bankOffset, sceneAppends;
        int deletes, copies, transposes, quantizes, duplicates, colorSets, loopSets;
        String copyName;
        boolean applyDelete = true,
                applyCopy = true,
                applyClipOp = true,
                quantizeMoves = true,
                applyColor = true,
                applyLoop = true;
        float[] color = {0f, 0f, 0f};
        String launchQuant = "default", launchMode = "from_group";
        boolean useLoopStart, applyLaunch = true, emitLaunch = true;
        int launchSets;
        boolean applyScene = true, initialised;
        String importedPath;
        Runnable beforeInsertion = () -> {};
        Runnable afterPlayStopRead = () -> {}, onSetName = () -> {}, onSelect = () -> {};
        int unreadableFromStep = -1;
        FakeHost recordNode;
        FakeHost trackIdentity;

        Fake() {
            this(true, false, false, false);
        }

        Fake(boolean safetyObserved, boolean record, boolean arrangerAutomation, boolean launcherAutomation) {
            for (int i = 0; i < 16; i++) slots[i] = new SlotData();
            for (int t = 0; t < HostFixture.DETAILED_TRACKS; t++) {
                final int trackIndex = t;
                FakeHost track = base.tracks[t];
                track.live("exists", () -> trackIndex == 0);
                FakeHost identity = track.attach("channelId", FakeHost.of(StringValue.class, "channelId"))
                        .reading(() -> trackIndex == 0 ? trackId : "");
                if (trackIndex == 0) trackIdentity = identity;
                track.live("name", () -> "Track");
                track.live("position", () -> trackIndex);
                track.live("isStopped", () -> stopped);
                track.live("isQueuedForStop", () -> stopQueued);
                FakeHost bank = FakeHost.of(ClipLauncherSlotBank.class, "bank");
                bank.live("scrollPosition", () -> bankOffset);
                bank.live("itemCount", () -> scenes);
                ClipLauncherSlot[] slotApis = new ClipLauncherSlot[16];
                for (int j = 0; j < 16; j++) {
                    final int index = j;
                    SlotData data = slots[j];
                    FakeHost slot = FakeHost.of(ClipLauncherSlot.class, "slot");
                    FakeHost content = slot.attach("hasContent", FakeHost.of(BooleanValue.class, "hasContent"))
                            .reading(() -> trackIndex == 0 && data.content);
                    if (trackIndex == 0) data.contentNode = content;
                    slot.live("sceneIndex", () -> index);
                    slot.live("name", () -> data.name);
                    slot.live("isPlaying", () -> data.playing);
                    slot.live("isPlaybackQueued", () -> data.queued);
                    slot.live("isRecording", () -> false);
                    slot.on("select", a -> {
                        selected = index;
                        onSelect.run();
                        return null;
                    });
                    slot.on("createEmptyClip", a -> {
                        creates++;
                        if (failCreate) throw Errors.notApplied(Reason.HOST_REFUSED, "Fixture native creation failure");
                        data.length = (Integer) a[0];
                        if (!deferCreate) {
                            if (emitCreateContent) observeContent(index, true);
                            else data.content = true;
                        }
                        return null;
                    });
                    slot.on("deleteObject", a -> {
                        deletes++;
                        if (applyDelete && trackIndex == 0) observeContent(index, false);
                        return null;
                    });
                    slot.on("launch", a -> {
                        playbackDispatches++;
                        if (emitPlaybackState) {
                            data.playing = launchPlaying;
                            data.queued = !launchPlaying;
                            stopped = false;
                        }
                        return null;
                    });
                    FakeHost insertion = FakeHost.of(InsertionPoint.class, "insertion");
                    insertion.on("copySlotsOrScenes", a -> {
                        copies++;
                        if (applyCopy && trackIndex == 0) {
                            ClipLauncherSlotOrScene[] sources = (ClipLauncherSlotOrScene[]) a[0];
                            for (int src = 0; src < 16; src++)
                                if (slotApis[src] == sources[0]) {
                                    data.name = copyName != null ? copyName : slots[src].name;
                                    data.length = slots[src].length;
                                }
                            observeContent(index, true);
                        }
                        return null;
                    });
                    insertion.on("insertFile", a -> {
                        imports++;
                        importedPath = (String) a[0];
                        importedSlot = index;
                        if (failImport)
                            throw Errors.notApplied(Reason.HOST_REFUSED, "Fixture native insertion failure");
                        return null;
                    });
                    slot.on("replaceInsertionPoint", a -> {
                        beforeInsertion.run();
                        return insertion.proxy();
                    });
                    slotApis[j] = slot.proxy(ClipLauncherSlot.class);
                }
                bank.on("getItemAt", a -> slotApis[(Integer) a[0]]);
                bank.on("stop", a -> {
                    playbackDispatches++;
                    if (emitPlaybackState) {
                        stopQueued = queueStop;
                        if (!queueStop) {
                            stopped = true;
                            for (SlotData data : slots) {
                                data.playing = false;
                                data.queued = false;
                            }
                        }
                    }
                    return null;
                });
                track.attach("clipLauncherSlotBank", bank);
                FakeHost devices = FakeHost.of(DeviceBank.class, "devices");
                devices.live("exists", () -> true);
                devices.live("scrollPosition", () -> 0);
                devices.live("itemCount", () -> 0);
                FakeHost[] roots = new FakeHost[16];
                for (int d = 0; d < roots.length; d++) roots[d] = emptyDevice(d, true);
                devices.on("getItemAt", a -> roots[(Integer) a[0]].proxy());
                track.attach("createDeviceBank", devices);
                tracks[t] = track.proxy(Track.class);
            }
            for (int t = HostFixture.DETAILED_TRACKS; t < tracks.length; t++)
                tracks[t] = tracks[HostFixture.DETAILED_TRACKS - 1];
            FakeHost clip = FakeHost.of(Clip.class, "clip");
            clip.on("getTrack", a -> tracks[0]);
            clip.live("exists", () -> slots[selected].content);
            FakeHost loopLength = FakeHost.of(SettableBeatTimeValue.class, "loopLength");
            loopLength.on("get", a -> slots[selected].length);
            loopLength.on("addValueObserver", a -> {
                ((DoubleValueChangedCallback) a[a.length - 1]).valueChanged(slots[selected].length);
                return null;
            });
            loopLength.on("set", a -> {
                loopSets++;
                if (applyLoop) slots[selected].length = (Double) a[0];
                return null;
            });
            clip.on("getLoopLength", a -> loopLength.proxy());
            FakeHost colorNode = FakeHost.of(SettableColorValue.class, "colorNode");
            colorNode.on("red", a -> color[0]);
            colorNode.on("green", a -> color[1]);
            colorNode.on("blue", a -> color[2]);
            colorNode.on("set", a -> {
                colorSets++;
                if (applyColor) color = new float[] {(Float) a[0], (Float) a[1], (Float) a[2]};
                return null;
            });
            clip.on("color", a -> colorNode.proxy());
            Object quantNode = enumValue(() -> launchQuant, v -> launchQuant = v, new String[] {"default", "1", "1/4"});
            Object modeNode =
                    enumValue(() -> launchMode, v -> launchMode = v, new String[] {"from_group", "trigger", "gate"});
            clip.on("launchQuantization", a -> quantNode);
            clip.on("launchMode", a -> modeNode);
            FakeHost useLoop = FakeHost.of(SettableBooleanValue.class, "useLoop");
            final List<BooleanValueChangedCallback> useLoopObservers = new ArrayList<>();
            useLoop.on("get", a -> useLoopStart);
            useLoop.on("addValueObserver", a -> {
                useLoopObservers.add((BooleanValueChangedCallback) a[a.length - 1]);
                return null;
            });
            useLoop.on("set", a -> {
                launchSets++;
                if (applyLaunch) {
                    useLoopStart = (Boolean) a[0];
                    if (emitLaunch) for (BooleanValueChangedCallback o : useLoopObservers) o.valueChanged(useLoopStart);
                }
                return null;
            });
            clip.on("useLoopStartAsQuantizationReference", a -> useLoop.proxy());
            clip.on("transpose", a -> {
                transposes++;
                if (applyClipOp) remap(k -> new int[] {k[0], k[1], k[2] + (Integer) a[0]});
                return null;
            });
            clip.on("quantize", a -> {
                quantizes++;
                if (applyClipOp && quantizeMoves) remap(k -> new int[] {k[0], k[1] % 2 == 0 ? k[1] : k[1] + 1, k[2]});
                return null;
            });
            clip.on("duplicateContent", a -> {
                duplicates++;
                if (applyClipOp) slots[selected].length *= 2;
                return null;
            });
            clip.live("getLoopStart", () -> loopStart);
            clip.live("getPlayStart", () -> 0.0);
            FakeHost playStop = FakeHost.of(SettableBeatTimeValue.class, "playStop");
            playStop.on("get", a -> {
                double value = slots[selected].playStop;
                afterPlayStopRead.run();
                return value;
            });
            playStop.on("set", a -> {
                stopWrites++;
                if (applyStop) slots[selected].playStop = (Double) a[0];
                return null;
            });
            clip.on("getPlayStop", a -> playStop.proxy());
            clip.live("isLoopEnabled", () -> true);
            FakeHost cursorSlot = FakeHost.of(ClipLauncherSlot.class, "cursorSlot");
            cursorSlot.on("createEqualsValue", a -> {
                Object target = a[0];
                return value(
                        BooleanValue.class,
                        () -> tracks[0].clipLauncherSlotBank().getItemAt(selected) == target);
            });
            clip.on("clipLauncherSlot", a -> cursorSlot.proxy());
            clip.on("setName", a -> {
                nameWrites++;
                if (applyName) slots[selected].name = (String) a[0];
                onSetName.run();
                return null;
            });
            clip.on("setStepSize", a -> {
                if (deferGridChanges || gridRefreshDelayMillis > 0) {
                    pendingSize = (Double) a[0];
                    gridReadyAt = tasks.after(gridRefreshDelayMillis);
                } else size = (Double) a[0];
                return null;
            });
            clip.on("scrollToStep", a -> {
                if (deferGridChanges || gridRefreshDelayMillis > 0) pendingStart = (Integer) a[0];
                else start = (Integer) a[0];
                return null;
            });
            clip.on("scrollToKey", a -> null);
            clip.on("addNoteStepObserver", a -> {
                observer = (NoteStepChangedCallback) a[0];
                return null;
            });
            clip.on(
                    "getStep",
                    a -> unreadableFromStep >= 0 && (Integer) a[1] >= unreadableFromStep
                            ? null
                            : note((Integer) a[0], (Integer) a[1], (Integer) a[2]));
            clip.on("setStep", a -> {
                writes++;
                if (writes == failWriteAt) throw Errors.notApplied(Reason.HOST_REFUSED, "fixture interrupted batch");
                int c = (Integer) a[0], x = (Integer) a[1], y = (Integer) a[2];
                put(selected, c, start + x, y, (Integer) a[3], (Double) a[4]);
                if (emitNotes) observer.noteStepChanged(note(c, x, y));
                return null;
            });
            clip.on("clearStep", a -> {
                writes++;
                int c = (Integer) a[0], x = (Integer) a[1], y = (Integer) a[2];
                notes.remove(key(selected, c, start + x, y));
                if (emitNotes) observer.noteStepChanged(note(c, x, y));
                return null;
            });
            FakeHost trackBank = base.bank;
            trackBank.live("scrollPosition", () -> 0);
            trackBank.live("itemCount", () -> 1);
            FakeHost application = base.application;
            application.live("projectName", () -> "Fixture");
            application.live("hasActiveEngine", () -> true);
            FakeHost transport = base.transport;
            transport.live("isPlaying", () -> false);
            recordNode = transport.put("isArrangerRecordEnabled", record);
            transport.live("isArrangerAutomationWriteEnabled", () -> arrangerAutomation);
            transport.live("isClipLauncherAutomationWriteEnabled", () -> launcherAutomation);
            for (String key : List.of(
                    "isArrangerRecordEnabled",
                    "isArrangerAutomationWriteEnabled",
                    "isClipLauncherAutomationWriteEnabled")) transport.child(key).notifyInitial = safetyObserved;
            FakeHost host = base.host;
            FakeHost sceneBankNode = trackBank.child("sceneBank");
            sceneBankNode.live("itemCount", () -> scenes);
            FakeHost project = base.project;
            project.on("createScene", a -> {
                sceneAppends++;
                if (applyScene) scenes++;
                return null;
            });
            host.on("getProject", a -> {
                // Mirrors Bitwig: the Project is only available while the extension initialises.
                if (initialised) throw new IllegalStateException("getProject can only be called during init");
                return project.proxy(Project.class);
            });
            host.on("createLauncherCursorClip", a -> clip.proxy());
            host.on("scheduleTask", a -> {
                Runnable action = (Runnable) a[0];
                long delay = ((Number) a[1]).longValue();
                tasks.schedule(
                        () -> {
                            if (gridRefreshDelayMillis > 0 && tasks.now() >= gridReadyAt) applyPendingGrid();
                            action.run();
                        },
                        delay);
                return null;
            });
            services = new TestServices(host.proxy(ControllerHost.class), tasks::now);
            module = services.launcher();
            initialised = true;
        }

        Object enumValue(Supplier<String> get, Consumer<String> apply, String[] options) {
            FakeHost value = FakeHost.of(SettableEnumValue.class, "value");
            value.on("get", a -> get.get());
            final List<EnumValueChangedCallback> observers = new ArrayList<>();
            value.on("addValueObserver", a -> {
                observers.add((EnumValueChangedCallback) a[a.length - 1]);
                return null;
            });
            value.on("set", a -> {
                launchSets++;
                if (applyLaunch) {
                    apply.accept((String) a[0]);
                    if (emitLaunch) for (EnumValueChangedCallback o : observers) o.valueChanged((String) a[0]);
                }
                return null;
            });
            FakeHost definition = FakeHost.of(EnumDefinition.class, "definition");
            definition.on("getValueCount", a -> options.length);
            definition.on("valueDefinitionAt", a -> {
                FakeHost option = FakeHost.of(EnumValueDefinition.class, "option");
                option.on("getId", b -> options[(Integer) a[0]]);
                return option.proxy();
            });
            value.on("enumDefinition", a -> definition.proxy());
            return value.proxy();
        }

        void observeTrackId(String id) {
            trackId = id;
            trackIdentity.emit(id);
        }

        void observeContent(int slot, boolean content) {
            slots[slot].content = content;
            slots[slot].contentNode.emit(content);
        }

        void applyPendingGrid() {
            if (pendingSize != null) {
                size = pendingSize;
                pendingSize = null;
            }
            if (pendingStart != null) {
                start = pendingStart;
                pendingStart = null;
            }
        }

        void remap(UnaryOperator<int[]> move) {
            Map<String, Note> moved = new HashMap<>();
            for (Iterator<Map.Entry<String, Note>> it = notes.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<String, Note> e = it.next();
                String[] k = e.getKey().split(":");
                if (Integer.parseInt(k[0]) != selected) continue;
                int[] to =
                        move.apply(new int[] {Integer.parseInt(k[1]), Integer.parseInt(k[2]), Integer.parseInt(k[3])});
                it.remove();
                moved.put(key(selected, to[0], to[1], to[2]), e.getValue());
            }
            notes.putAll(moved);
        }

        void put(int slot, int channel, int step, int pitch, int velocity, double duration) {
            notes.put(key(slot, channel, step, pitch), new Note(velocity, duration));
        }

        NoteStep note(int channel, int x, int pitch) {
            double storageSize = noteStorageStepSize == null ? size : noteStorageStepSize;
            double storagePosition = (start + x) * size / storageSize;
            int storedStep = (int) storagePosition;
            Note on = storagePosition == storedStep ? notes.get(key(selected, channel, storedStep, pitch)) : null;
            Object state = stateConstant(on == null ? "Empty" : "NoteOn");
            if (on == null)
                for (int step = 0; step < storagePosition; step++) {
                    Note earlier = notes.get(key(selected, channel, step, pitch));
                    if (earlier != null && step * storageSize + earlier.duration > (start + x) * size) {
                        on = earlier;
                        state = stateConstant("NoteSustain");
                        break;
                    }
                }
            final Note found = on;
            final Object actual = state;
            FakeHost n = FakeHost.of(NoteStep.class, "n");
            n.on("channel", a -> channel);
            n.on("x", a -> x);
            n.on("y", a -> pitch);
            n.on("state", a -> actual);
            n.on("velocity", a -> found == null ? 0.0 : found.exact != null ? found.exact : found.velocity / 127.0);
            n.on("duration", a -> found == null ? 0.0 : found.duration);
            return n.proxy(NoteStep.class);
        }

        HostOperation start(String method, JsonObject p) {
            HostOperation op = new HostOperation(tasks.after(2000));
            return start(op, method, p);
        }

        HostOperation start(HostOperation op, String method, JsonObject p) {
            services.enqueue(op, () -> module.dispatch(op, method, p));
            tasks.take().run();
            return op;
        }

        HostOperation startAndDrain(String method, JsonObject p) throws Exception {
            HostOperation op = start(method, p);
            drain(op);
            return op;
        }

        JsonObject call(String method, JsonObject p) throws Exception {
            return startAndDrain(method, p).result.join();
        }

        void next() {
            if (!tasks.isEmpty()) tasks.take().run();
        }

        void drain(HostOperation op) {
            int iterations = 0;
            while (!op.result.isDone()) {
                if (tasks.isEmpty() || ++iterations > 3000)
                    throw new AssertionError("fixture operation did not settle");
                next();
            }
        }
    }

    private static final class SlotData {
        boolean content, playing, queued;
        String name = "";
        double length = 4, playStop = 4;
        FakeHost contentNode;
    }

    private static final class Note {
        final int velocity;
        final double duration;
        Double exact; // host velocity that is not a whole MIDI step, e.g. a GUI drag

        Note(int velocity, double duration) {
            this.velocity = velocity;
            this.duration = duration;
        }
    }

    private static FakeHost emptyDevice(int position, boolean root) {
        FakeHost device = FakeHost.of(Device.class, "device");
        device.live("exists", () -> false);
        device.live("name", () -> "");
        device.live("position", () -> position);
        device.live("deviceType", () -> "");
        device.on("addDirectParameterIdObserver", a -> {
            ((StringArrayValueChangedCallback) a[0]).valueChanged(new String[0]);
            return null;
        });
        if (root) {
            device.live("hasLayers", () -> false);
            FakeHost layers = FakeHost.of(DeviceLayerBank.class, "layers");
            layers.live("exists", () -> false);
            layers.live("itemCount", () -> 0);
            layers.live("scrollPosition", () -> 0);
            FakeHost[] layerNodes = new FakeHost[2];
            for (int l = 0; l < layerNodes.length; l++) {
                final int index = l;
                FakeHost layer = layerNodes[l] = FakeHost.of(DeviceLayer.class, "layer");
                layer.live("exists", () -> false);
                layer.live("channelId", () -> "");
                layer.live("channelIndex", () -> index);
                layer.live("name", () -> "");
                FakeHost children = FakeHost.of(DeviceBank.class, "children");
                children.live("exists", () -> false);
                children.live("itemCount", () -> 0);
                children.live("scrollPosition", () -> 0);
                FakeHost[] childNodes = {emptyDevice(0, false), emptyDevice(1, false)};
                children.on("getItemAt", a -> childNodes[(Integer) a[0]].proxy());
                layer.on("createDeviceBank", a -> children.proxy());
            }
            layers.on("getItemAt", a -> layerNodes[(Integer) a[0]].proxy());
            device.on("createLayerBank", a -> layers.proxy());
        } else {
            device.on("createCursorRemoteControlsPage", a -> {
                throw new AssertionError("Child remote controls are outside the fixed layer graph");
            });
        }
        return device;
    }

    private static Object value(Class<?> type, Supplier<Object> supplier) {
        return value(type, supplier, true);
    }

    private static Object value(Class<?> type, Supplier<Object> supplier, boolean observed) {
        FakeHost node = FakeHost.of(type, "value").reading(supplier);
        node.notifyInitial = observed;
        return node.proxy();
    }
}
