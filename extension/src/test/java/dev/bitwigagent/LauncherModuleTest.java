package dev.bitwigagent;

import com.bitwig.extension.controller.api.*;
import com.bitwig.extension.callback.*;
import com.google.gson.*;
import java.lang.reflect.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Consumer tests against API-shaped in-memory host proxies; never native-host evidence. */
public final class LauncherModuleTest {
    private static int assertions;
    private static final String ID = "11111111-1111-1111-1111-111111111111";

    public static void main(String[] args) throws Exception {
        strictWindowAndBatchValidation();
        createAndReadActualMusic();
        collisionsFailBeforeAnyWrite();
        identityAndDeadlineGuards();
        selectionDispatchPreventsNotAppliedFailure();
        cursorLeaseDoesNotBlockLauncherReads();
        lateOrMissingObservationsRemainDispatched();
        explicitLaunchAndStopStates();
        unobservedPlaybackCompletionRemainsUnknown();
        denseReadReportsItsResponseBoundary();
        matchingEditsPreserveExistingNotes();
        loopStartAndPlaybackSafetyFailClosed();
        delayedGridPropagationKeepsReadinessExplicit();
        firstGridReadWaitsForNativeDebounce();
        fileImportValidationAndOccupancy();
        fileImportObservesOnlyContentTransition();
        fileImportSharesCursorLease();
        fileImportMutationBoundaryGuards();
        unresolvedImportsKeepTargetQuarantined();
        completedMutationsReleaseTheirTargets();
        completedRequestFuturesDoNotReleaseMutations();
        reboundTargetsRemainQuarantined();
        preDispatchFailuresDoNotQuarantine();
        unresolvedCreatesKeepTargetQuarantined();
        System.out.println("LauncherModuleTest: " + assertions + " assertions passed (simulated host only)");
    }

    private static void strictWindowAndBatchValidation() {
        JsonObject valid = window(); valid.add("edits", fixture());
        LauncherModule.validateParams("notes.edit", valid);
        check(LauncherModule.edits(valid, new LauncherModule.Window(valid)).size() == 5, "melody/chord/drum fixture accepted");
        JsonObject bad = valid.deepCopy(); bad.addProperty("channel", 16); invalid(bad);
        bad = valid.deepCopy(); bad.addProperty("slotIndex", -1); invalid(bad);
        bad = valid.deepCopy(); bad.addProperty("steps", 129); invalid(bad);
        bad = valid.deepCopy(); bad.addProperty("pitchStart", 120); bad.addProperty("pitchCount", 9); invalid(bad);
        bad = valid.deepCopy(); bad.getAsJsonArray("edits").add(bad.getAsJsonArray("edits").get(0).deepCopy()); invalid(bad);
        bad = valid.deepCopy(); bad.getAsJsonArray("edits").get(0).getAsJsonObject().addProperty("beat", .3); invalid(bad);
        bad = valid.deepCopy(); bad.getAsJsonArray("edits").get(0).getAsJsonObject().addProperty("durationBeats", .3); invalid(bad);
        bad = valid.deepCopy(); bad.getAsJsonArray("edits").get(0).getAsJsonObject().addProperty("durationBeats", 5); invalid(bad);
        bad = valid.deepCopy(); bad.getAsJsonArray("edits").add(set(.25, 60, 100, .25)); invalid(bad);
        bad = valid.deepCopy(); bad.addProperty("unexpected", true); invalid(bad);
        bad = valid.deepCopy(); bad.add("edits", new JsonArray()); invalid(bad);
        JsonArray tooMany = new JsonArray(); for (int i = 0; i < 257; i++) tooMany.add(set(0, 60, 100, .25));
        bad = valid.deepCopy(); bad.add("edits", tooMany); invalid(bad);
        check(LauncherModule.aligned(new JsonPrimitive(.3), new JsonPrimitive(.1), "beat") == 3, "decimal aligned times are exact");
        expect("invalid_params", () -> LauncherModule.aligned(new JsonPrimitive(.30000000000000004), new JsonPrimitive(.1), "beat"));
        JsonObject clear = window(); JsonArray edits = new JsonArray(); JsonObject e = new JsonObject();
        e.addProperty("op", "clear"); e.addProperty("beat", 0); e.addProperty("pitch", 60); e.addProperty("velocity", 1); edits.add(e); clear.add("edits", edits); invalid(clear);
    }

    private static void createAndReadActualMusic() throws Exception {
        Fake f = new Fake();
        JsonObject create = target(); create.addProperty("lengthBeats", 4); create.addProperty("name", "Melody + chords 鼓");
        JsonObject created = f.call("launcher.create", create);
        check(created.get("status").getAsString().equals("observed"), "create observes content/name/length");
        check(created.get("loopLengthBeats").getAsDouble() == 4, "length comes from clip readback");
        check(f.slots[0].name.equals("Melody + chords 鼓"), "explicit name written");
        expectFuture("slot_occupied", f.start("launcher.create", create));
        check(f.creates == 1, "occupied slot never overwritten");
        f.put(0, 1, 2, 90, 70, .25); // unrelated channel
        f.put(1, 0, 0, 60, 40, .25); // unrelated clip
        f.put(0, 0, 24, 80, 50, .25); // outside requested window
        JsonObject edits = window(); edits.add("edits", fixture());
        JsonObject result = f.call("notes.edit", edits);
        check(result.get("status").getAsString().equals("observed"), "fresh changed notes verified");
        check(result.getAsJsonArray("notes").size() == 5, "actual onset count returns chord/melody/drum notes");
        check(f.notes.containsKey(key(0, 1, 2, 90)), "unrelated channel preserved");
        check(f.notes.containsKey(key(1, 0, 0, 60)), "unrelated clip preserved");
        check(f.notes.containsKey(key(0, 0, 24, 80)), "outside window note preserved");
        f.put(0, 0, 0, 60, 37, .75);
        JsonObject read = f.call("notes.read", window());
        JsonObject c = find(read, 60, 0);
        check(c.get("velocityNormalized").getAsDouble() == 37 / 127.0, "read returns host change, not request echo");
        check(c.get("durationBeats").getAsDouble() == .75, "actual duration returned");
        JsonObject clear = window(); JsonArray array = new JsonArray(); JsonObject e = new JsonObject();
        e.addProperty("op", "clear"); e.addProperty("beat", 0); e.addProperty("pitch", 60); array.add(e); clear.add("edits", array);
        f.call("notes.edit", clear);
        check(!f.notes.containsKey(key(0, 0, 0, 60)), "clear removes only addressed onset");
        check(f.notes.containsKey(key(0, 0, 0, 64)), "other chord note preserved after clear");
        JsonObject later = window(); later.addProperty("startStep", 16); later.addProperty("steps", 16);
        check(f.call("notes.read", later).getAsJsonArray("notes").size() == 1, "nonzero window maps absolute steps");
        check(!read.get("completeClip").getAsBoolean(), "bounded read never claims full clip");
        JsonObject highChannel = window(); highChannel.addProperty("channel", 15);
        JsonArray highEdits = new JsonArray(); highEdits.add(set(0, 127, 127, .25)); highChannel.add("edits", highEdits);
        check(f.call("notes.edit", highChannel).getAsJsonArray("notes").size() == 1, "channel15 and pitch127 are writable/readable");
        check(f.notes.containsKey(key(0, 0, 0, 64)), "channel15 edit leaves channel0 intact");
    }

    private static void collisionsFailBeforeAnyWrite() throws Exception {
        Fake f = new Fake(); f.slots[0].content = true;
        f.put(0, 0, 1, 60, 90, .25);
        JsonObject p = window(); JsonArray edits = new JsonArray(); edits.add(set(0, 36, 100, .25)); edits.add(set(0, 60, 100, .5)); p.add("edits", edits);
        expectFuture("note_collision", f.startAndDrain("notes.edit", p));
        check(f.writes == 0, "later collision prevents earlier unrelated edit too");
        f.notes.clear(); f.put(0, 0, 0, 60, 90, 1);
        edits = new JsonArray(); edits.add(set(.25, 60, 100, .25)); p.add("edits", edits);
        expectFuture("note_collision", f.startAndDrain("notes.edit", p));
        check(f.writes == 0, "sustained cell never overwrites earlier onset");
    }

    private static void identityAndDeadlineGuards() throws Exception {
        Fake f = new Fake(); f.slots[0].content = true; f.slots[1].content = true;
        JsonObject p = window(); p.add("edits", fixture());
        HostOperation op = f.start("notes.edit", p);
        f.next(); // cursor identity observed, schedule settled binding
        f.selected = 1;
        f.drain(op);
        expectFuture("target_not_observed", op);
        check(f.writes == 0, "another selected slot cannot receive explicit writes");
        f = new Fake(); f.slots[0].content = true;
        HostOperation expired = new HostOperation(System.nanoTime() - 1);
        f.services.schedule(expired, () -> { throw new AssertionError("expired operation ran"); }, 0);
        f.drain(expired); expectFuture("deadline_exceeded", expired);
        check(f.writes == 0, "expired operations never write");
        f = new Fake(); f.slots[0].content = true;
        op = f.start("notes.edit", p); f.trackId = "22222222-2222-2222-2222-222222222222"; f.drain(op);
        expectFuture("stale_context", op); check(f.writes == 0, "track UUID change aborts cursor edit");
    }

    private static void selectionDispatchPreventsNotAppliedFailure() throws Exception {
        Fake f = new Fake(); f.slots[1].content = true;
        JsonObject p = window(); p.addProperty("slotIndex", 1);
        check(f.selected == 0, "scenario starts with a different selected clip");
        HostOperation op = f.start("notes.read", p);
        check(f.selected == 1, "explicit note read already changed the selected clip");
        f.trackId = "22222222-2222-2222-2222-222222222222";
        f.drain(op);
        expectFuture("stale_context", op);
        check(f.writes == 0, "context failure occurs before any note edit");
        try { op.result.join(); } catch (CompletionException error) {
            check(((RpcException) error.getCause()).outcome.equals("unknown"),
                    "selection already dispatched: subsequent context failure cannot claim not_applied");
        }
    }

    private static void cursorLeaseDoesNotBlockLauncherReads() throws Exception {
        Fake f = new Fake(); f.slots[0].content = true;
        HostOperation first = f.start("notes.read", window());
        expectFuture("busy", f.start("notes.read", window()));
        JsonObject list = f.call("launcher.list", trackTarget());
        check(list.getAsJsonArray("slots").size() == 16, "metadata reads proceed while cursor operation pending");
        f.drain(first); check(first.result.join().getAsJsonArray("notes").isEmpty(), "empty actual grid read completes");
        f.call("notes.read", window());
    }

    private static void lateOrMissingObservationsRemainDispatched() throws Exception {
        Fake f = new Fake(); f.slots[0].content = true; f.emitNotes = false;
        JsonObject p = window(); p.add("edits", fixture());
        JsonObject out = f.call("notes.edit", p);
        check(out.get("status").getAsString().equals("dispatched"), "readback without fresh callbacks does not verify changed write");
        check(!out.getAsJsonArray("edits").get(0).getAsJsonObject().get("verified").getAsBoolean(), "per-cell missing observation explicit");
        check(out.getAsJsonArray("notes").size() == 5, "partial outcome still exposes actual readable notes");
        f = new Fake(); f.slots[0].content = true; f.failWriteAt = 2;
        HostOperation op = f.startAndDrain("notes.edit", p);
        expectFuture("host_write_failed", op);
        try { op.result.join(); } catch (CompletionException error) {
            check(((RpcException) error.getCause()).outcome.equals("unknown"), "partial batch failure is unknown, not not_applied");
        }
        check(f.notes.size() == 1, "first applied note preserved; no destructive rollback or replay");
    }

    private static void explicitLaunchAndStopStates() throws Exception {
        Fake f = new Fake(); f.slots[0].content = true;
        JsonObject out = f.call("launcher.launch", target());
        check(out.get("playbackState").getAsString().equals("queued"), "queued launch distinct from playing");
        check(out.get("status").getAsString().equals("dispatched"), "queue does not claim playback completion");
        check("not_observed".equals(out.has("completion") ? out.get("completion").getAsString() : null),
                "queued launch declares incomplete dispatch for strict consumers");
        f = new Fake(); f.slots[0].content = true; f.launchPlaying = true;
        out = f.call("launcher.launch", target());
        check(out.get("playbackState").getAsString().equals("playing"), "actual playing state reported");
        check(out.get("completion").getAsString().equals("observed"), "playing launch declares observed completion");
        out = f.call("launcher.stop", trackTarget());
        check(out.get("playbackState").getAsString().equals("stopped"), "explicit track stop observed");
        check(out.get("completion").getAsString().equals("observed"), "stopped track declares observed completion");
        f = new Fake(); f.slots[0].content = true; f.slots[0].playing = true; f.stopped = false; f.queueStop = true;
        out = f.call("launcher.stop", trackTarget());
        check(out.get("playbackState").getAsString().equals("queued"), "queued stop distinct from stopped");
        check(out.get("status").getAsString().equals("dispatched"), "queued stop does not claim completion");
        check(out.get("completion").getAsString().equals("not_observed"), "queued stop declares incomplete dispatch");
        check(f.slots[0].playing && !f.stopped, "queued stop may leave the clip playing");
        JsonObject bad = target(); bad.addProperty("slotIndex", 15);
        expectFuture("clip_not_found", f.start("launcher.launch", bad));
    }

    private static void unobservedPlaybackCompletionRemainsUnknown() throws Exception {
        for (boolean stop : new boolean[]{false, true}) {
            Fake f = new Fake(); f.slots[0].content = true; f.emitPlaybackState = false;
            f.slots[0].playing = stop; f.stopped = !stop;
            JsonObject out = f.call(stop ? "launcher.stop" : "launcher.launch", stop ? trackTarget() : target());
            check(out.get("status").getAsString().equals("dispatched"), "missing playback observation is not completion");
            check(out.get("playbackState").getAsString().equals("not_observed"), "neither queue nor desired state observed");
            check(out.get("completion").getAsString().equals("unknown"), "unobserved launch/stop stops strict consumers");
            check(f.playbackDispatches == 1, "playback observation never redispatches the operation");
        }
    }

    private static void denseReadReportsItsResponseBoundary() throws Exception {
        Fake f = new Fake(); f.slots[0].content = true; f.slots[0].length = 8;
        for (int x = 0; x < 32; x++) for (int pitch = 0; pitch < 128; pitch++) f.put(0, 0, x, pitch, 100, .25);
        JsonObject p = window(); p.addProperty("steps", 32);
        JsonObject out = f.call("notes.read", p);
        check(out.get("notesInWindow").getAsInt() == 4096, "dense window reports actual total");
        check(out.getAsJsonArray("notes").size() == 2048, "response stays bounded");
        check(!out.get("completeWindow").getAsBoolean() && out.has("continuation"), "dense partial read is explicit");
        check(out.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length < RpcServer.MAX_BYTES, "dense read fits protocol frame");
    }

    private static void matchingEditsPreserveExistingNotes() throws Exception {
        Fake f = new Fake(); f.slots[0].content = true;
        f.put(0, 0, 0, 60, 100, .5);
        Note existing = f.notes.get(key(0, 0, 0, 60));
        JsonObject p = window(); JsonArray edits = new JsonArray(); edits.add(set(0, 60, 100, .5)); p.add("edits", edits);
        JsonObject out = f.call("notes.edit", p);
        check(f.writes == 0, "matching set does not replace note or clear expression");
        check(f.notes.get(key(0, 0, 0, 60)) == existing, "existing note object and unaddressed properties preserved");
        check(out.get("status").getAsString().equals("observed"), "unchanged actual readback verifies without callback replay");
    }

    private static void loopStartAndPlaybackSafetyFailClosed() throws Exception {
        Fake f = new Fake(); f.slots[0].content = true; f.loopStart = 1;
        JsonObject p = window(); JsonArray edits = new JsonArray(); edits.add(set(0, 60, 100, .5)); p.add("edits", edits);
        expectFuture("invalid_params", f.startAndDrain("notes.edit", p));
        check(f.writes == 0, "notes before observed loop start are rejected before any mutation");
        edits = new JsonArray(); edits.add(set(1, 60, 100, .5)); p.add("edits", edits);
        check(f.call("notes.edit", p).get("status").getAsString().equals("observed"), "note at loop start is accepted");
        check(f.writes == 1, "loop start bound is inclusive");
        for (int flag = 0; flag < 4; flag++) {
            f = new Fake(flag != 3, flag == 0, flag == 1, flag == 2); f.slots[0].content = true;
            expectFuture(flag == 3 ? "not_ready" : "unsafe_state", f.start("launcher.launch", target()));
            check(f.stopped && !f.slots[0].queued && !f.slots[0].playing, "unsafe or unobserved transport never launches clip");
            check(f.call("launcher.stop", trackTarget()).get("playbackState").getAsString().equals("stopped"), "stop remains available under unsafe or unknown recording state");
        }
    }

    private static void delayedGridPropagationKeepsReadinessExplicit() throws Exception {
        Fake f = new Fake(); f.slots[0].content = true; f.slots[0].length = 32;
        f.put(0, 0, 0, 60, 100, .25); f.put(0, 0, 16, 72, 80, .5);
        long before = f.call("notes.read", window()).get("gridGeneration").getAsLong();
        f.deferGridChanges = true;
        JsonObject p = window(); p.addProperty("startStep", 16); p.addProperty("stepSizeBeats", .5);
        HostOperation delayed = f.start("notes.read", p);
        check(f.start == 0 && f.size == .25, "fixture retains old grid after asynchronous reconfiguration");
        f.next();
        f.applyPendingGrid();
        f.drain(delayed);
        JsonObject out = delayed.result.join();
        check(find(out, 72, 16).get("beat").getAsDouble() == 8, "delayed grid delivered during settling yields new window content");
        check(out.get("gridGeneration").getAsLong() > before, "window change gets distinct observation generation");
        check(out.get("gridReadiness").getAsString().equals("scheduled_settling_not_acknowledged"),
                "scheduled settling never claims a public API grid acknowledgement");
        check(out.has("gridReadinessLimitation"), "unavailable grid barrier is explicit in consumer response");
        f.emitNotes = false;
        JsonArray edits = new JsonArray(); edits.add(set(8, 72, 90, .5)); p.add("edits", edits);
        HostOperation changed = f.start("notes.edit", p);
        f.observer.noteStepChanged(f.note(0, 0, 72)); // Old/read-generation callback while new binding is unsettled.
        f.applyPendingGrid(); f.drain(changed);
        check(!changed.result.join().getAsJsonArray("edits").get(0).getAsJsonObject().get("verified").getAsBoolean(),
                "callback from prior or unsettled window cannot verify a later changed write");
    }

    private static void firstGridReadWaitsForNativeDebounce() throws Exception {
        Fake f = new Fake(); f.slots[0].content = true;
        f.noteStorageStepSize = .25;
        f.gridRefreshDelayMillis = 100;
        f.put(0, 0, 0, 60, 96, 1); f.put(0, 0, 8, 64, 96, 1);
        JsonObject p = window(); p.addProperty("stepSizeBeats", .125); p.addProperty("steps", 32);
        JsonObject out = f.call("notes.read", p);
        check(out.getAsJsonArray("notes").size() == 2, "first grid read preserves both stored notes");
        check(find(out, 64, 16).get("beat").getAsDouble() == 2,
                "first eighth-beat read must not relabel the old quarter-beat cache as beat one");
        check(f.writes == 0, "grid refresh does not change note content");
    }

    private static void fileImportValidationAndOccupancy() throws Exception {
        Path file = Files.createTempFile("launcher-import-", ".arbitrary");
        try {
            for (String path : new String[]{"relative.wav", "file:///tmp/a.wav", "/tmp/../a.wav",
                    "/tmp//a.wav", "/tmp/./a.wav", "/tmp/a.wav/", "/tmp/\u0085.wav", "/tmp/\0.wav",
                    "/" + "a".repeat(4096)}) {
                Fake f = new Fake();
                JsonObject p = importTarget(file); p.addProperty("path", path);
                expectFuture("invalid_params", f.start("launcher.import", p));
                check(f.imports == 0, "malformed path never reaches insertion");
            }
            Fake f = new Fake(); JsonObject p = importTarget(file); p.addProperty("overwrite", true);
            expectFuture("invalid_params", f.start("launcher.import", p));
            check(f.imports == 0, "overwrite option is not accepted");
            p = importTarget(file); p.remove("path");
            expectFuture("invalid_params", f.start("launcher.import", p));
            p = importTarget(file); p.addProperty("slotIndex", 16);
            expectFuture("invalid_params", f.start("launcher.import", p));
            p = importTarget(file); p.addProperty("trackId", "");
            expectFuture("invalid_params", f.start("launcher.import", p));
            check(f.imports == 0, "missing path or malformed explicit target cannot insert");
            expectFuture("file_unavailable", f.start("launcher.import", importTarget(file.resolveSibling(file.getFileName() + ".missing"))));
            expectFuture("file_unavailable", f.start("launcher.import", importTarget(file.getParent())));
            check(f.imports == 0, "missing files and directories never reach insertion");
            f.slots[0].content = true;
            expectFuture("slot_occupied", f.start("launcher.import", importTarget(file)));
            check(f.imports == 0 && f.slots[0].content, "occupied content is never replaced");
        } finally { Files.delete(file); }
    }

    private static void fileImportObservesOnlyContentTransition() throws Exception {
        Path file = Files.createTempFile("launcher-import-", ".arbitrary");
        try {
            Fake f = new Fake(); f.selected = 5;
            JsonObject p = importTarget(file); p.addProperty("slotIndex", 3);
            HostOperation op = f.start("launcher.import", p);
            check(f.imports == 1 && file.toString().equals(f.importedPath) && f.importedSlot == 3,
                    "native insertion targets explicit slot, not selected cursor");
            check(!op.result.isDone(), "dispatch alone does not report observed import");
            f.slots[3].name = "Host content";
            f.observeContent(3, true);
            f.drain(op);
            JsonObject out = op.result.join();
            check(out.get("status").getAsString().equals("observed")
                    && out.get("verification").getAsString().equals("slot_content_transition"), "fresh content transition is observed");
            check(!out.get("requestedFileVerified").getAsBoolean(), "content observation never claims file identity or decoding");
            check(out.getAsJsonObject("slot").get("name").getAsString().equals("Host content"), "response contains host metadata, not path echo");
            check(f.selected == 5 && !f.slots[0].content, "import does not select cursor or touch other slots");
            for (int mode = 0; mode < 3; mode++) {
                f = new Fake();
                op = f.start("launcher.import", importTarget(file));
                if (mode == 1) f.slots[0].content = true; // Getter changed without a fresh observer.
                if (mode == 2) f.observeContent(0, false); // Fresh callback, but no content transition.
                f.drain(op);
                expectOutcome("import_not_observed", "unknown", op);
                check(f.imports == 1, "unobserved insertion is never replayed");
            }
            f = new Fake(); op = f.start("launcher.import", importTarget(file));
            f.trackId = "22222222-2222-2222-2222-222222222222"; f.drain(op);
            expectOutcome("stale_context", "unknown", op);
            check(f.imports == 1, "post-dispatch source change stays unknown without retry");
        } finally { Files.delete(file); }
    }

    private static void fileImportSharesCursorLease() throws Exception {
        Path file = Files.createTempFile("launcher-import-", ".arbitrary");
        try {
            Fake f = new Fake();
            HostOperation importing = f.start("launcher.import", importTarget(file));
            expectFuture("busy", f.start("launcher.import", importTarget(file)));
            JsonObject create = target(); create.addProperty("lengthBeats", 4); create.addProperty("name", "Cannot race import");
            expectFuture("busy", f.start("launcher.create", create));
            expectFuture("busy", f.start("notes.edit", editFixture()));
            expectFuture("busy", f.start("notes.read", window()));
            check(f.imports == 1 && f.creates == 0 && f.writes == 0, "pending import excludes cursor/create/import mutations");
            check(f.call("launcher.list", trackTarget()).getAsJsonArray("slots").size() == 16, "read-only metadata remains available");
            f.observeContent(0, true); f.drain(importing);
            HostOperation reading = f.start("notes.read", window());
            JsonObject other = importTarget(file); other.addProperty("slotIndex", 1);
            expectFuture("busy", f.start("launcher.import", other));
            f.drain(reading);
            check(f.imports == 1, "existing cursor lease also excludes import");
            other = target(); other.addProperty("slotIndex", 1); other.addProperty("lengthBeats", 4); other.addProperty("name", "Creating");
            HostOperation creating = f.start("launcher.create", other);
            expectFuture("busy", f.start("launcher.import", importTarget(file)));
            f.drain(creating);
            check(f.imports == 1, "existing create lease also excludes import");
        } finally { Files.delete(file); }
    }

    private static void fileImportMutationBoundaryGuards() throws Exception {
        Path file = Files.createTempFile("launcher-import-", ".arbitrary");
        try {
            for (int flag = 0; flag < 4; flag++) {
                Fake f = new Fake(flag != 3, flag == 0, flag == 1, flag == 2);
                expectOutcome(flag == 3 ? "not_ready" : "unsafe_state", "not_applied", f.start("launcher.import", importTarget(file)));
                check(f.imports == 0, "unsafe or unobserved transport prevents file insertion");
            }
            Fake changed = new Fake();
            changed.beforeInsertion = () -> changed.trackId = "22222222-2222-2222-2222-222222222222";
            expectOutcome("stale_context", "not_applied", changed.start("launcher.import", importTarget(file)));
            check(changed.imports == 0, "track binding rechecked immediately before native dispatch");
            Fake scrolled = new Fake();
            scrolled.beforeInsertion = () -> scrolled.bankOffset = 1;
            expectOutcome("stale_context", "not_applied", scrolled.start("launcher.import", importTarget(file)));
            check(scrolled.imports == 0, "slot bank offset rechecked immediately before dispatch");
            Fake occupied = new Fake();
            occupied.beforeInsertion = () -> occupied.observeContent(0, true);
            expectOutcome("slot_occupied", "not_applied", occupied.start("launcher.import", importTarget(file)));
            check(occupied.imports == 0, "late slot occupancy cannot be overwritten");
            Fake unsafe = new Fake();
            unsafe.beforeInsertion = () -> unsafe.recordObserver.valueChanged(true);
            expectOutcome("unsafe_state", "not_applied", unsafe.start("launcher.import", importTarget(file)));
            check(unsafe.imports == 0, "safety is rechecked at mutation boundary");
            Fake cancelled = new Fake();
            HostOperation cancelledOp = new HostOperation(System.nanoTime() + TimeUnit.SECONDS.toNanos(2));
            cancelled.beforeInsertion = () -> cancelledOp.result.completeExceptionally(new RpcException("cancelled", "Fixture cancellation", "not_applied"));
            cancelled.start(cancelledOp, "launcher.import", importTarget(file));
            expectOutcome("cancelled", "not_applied", cancelledOp);
            check(cancelled.imports == 0 && !cancelledOp.dispatched, "cancellation during target resolution prevents dispatch");
            Fake expired = new Fake();
            HostOperation expiredOp = new HostOperation(System.nanoTime() - 1);
            expired.start(expiredOp, "launcher.import", importTarget(file));
            expectOutcome("deadline_exceeded", "not_applied", expiredOp);
            check(expired.imports == 0, "expired operation cannot dispatch insertion");
            Fake late = new Fake();
            HostOperation lateOp = new HostOperation(System.nanoTime() + TimeUnit.SECONDS.toNanos(2));
            boolean[] resolved = {false};
            late.beforeInsertion = () -> {
                resolved[0] = true;
                try { TimeUnit.NANOSECONDS.sleep(Math.max(0, lateOp.deadline - System.nanoTime()) + 1); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
            };
            late.start(lateOp, "launcher.import", importTarget(file));
            expectOutcome("deadline_exceeded", "not_applied", lateOp);
            check(resolved[0] && late.imports == 0, "deadline rechecked after resolving insertion point");
        } finally { Files.delete(file); }
    }

    private static void unresolvedImportsKeepTargetQuarantined() throws Exception {
        Path file = Files.createTempFile("launcher-quarantine-", ".arbitrary");
        try {
            Fake f = new Fake();
            HostOperation first = f.startShort("launcher.import", importTarget(file));
            f.drain(first); expectOutcome("import_not_observed", "unknown", first);
            HostOperation second = f.start("launcher.import", importTarget(file));
            expectQuarantine(second, "launcher.import", ID);
            expectQuarantine(f.start("launcher.create", createTarget()), "launcher.import", ID);
            expectQuarantine(f.start("notes.edit", editFixture()), "launcher.import", ID);
            check(f.imports == 1 && f.creates == 0 && f.writes == 0, "unknown import rejects every conflicting content write before native dispatch");
            check(f.call("launcher.list", trackTarget()).getAsJsonArray("slots").size() == 16, "quarantine preserves launcher inspection");
            f.observeContent(0, true); f.next(); // A's late callback must never be attributed to B.
            expectQuarantine(second, "launcher.import", ID);
            check(f.call("notes.read", window()).getAsJsonArray("notes").isEmpty(), "late content remains available for read-only note inspection");
            check(f.imports == 1 && f.creates == 0 && f.writes == 0 && f.nameWrites == 0, "inspection does not resume or replay the unknown mutation");
            f.observeContent(0, false);
            expectQuarantine(f.start("launcher.import", importTarget(file)), "launcher.import", ID);
            JsonObject other = importTarget(file); other.addProperty("slotIndex", 1);
            HostOperation independent = f.start("launcher.import", other);
            check(f.imports == 2 && f.importedSlot == 1, "quarantine is target-specific rather than a permanent global cursor lease");
            f.observeContent(1, true); f.drain(independent);
            check(independent.result.join().get("status").getAsString().equals("observed"), "independent slot can complete while original remains unresolved");
            expectQuarantine(f.start("launcher.import", importTarget(file)), "launcher.import", ID);
        } finally { Files.delete(file); }
    }

    private static void completedMutationsReleaseTheirTargets() throws Exception {
        Path file = Files.createTempFile("launcher-completed-", ".arbitrary");
        try {
            Fake f = new Fake();
            HostOperation first = f.start("launcher.import", importTarget(file));
            f.observeContent(0, true); f.drain(first);
            f.observeContent(0, false); // Independent host deletion after a positively observed import.
            HostOperation second = f.start("launcher.import", importTarget(file));
            check(f.imports == 2 && !second.result.isDone(), "observed import releases quarantine without reusing its earlier callback");
            f.observeContent(0, true); f.drain(second);
            check(second.result.join().get("status").getAsString().equals("observed"), "independent same-slot import requires its own fresh completion");
            f = new Fake();
            check(f.call("launcher.create", createTarget()).get("status").getAsString().equals("observed"), "successful create observes content/name/length");
            f.observeContent(0, false);
            HostOperation imported = f.start("launcher.import", importTarget(file));
            check(f.imports == 1, "successful creation releases target for an independent empty-slot import");
            f.observeContent(0, true); f.drain(imported);
        } finally { Files.delete(file); }
    }

    private static void completedRequestFuturesDoNotReleaseMutations() throws Exception {
        Path file = Files.createTempFile("launcher-future-", ".arbitrary");
        try {
            for (String method : new String[]{"launcher.import", "launcher.create"}) {
                for (int mode = 0; mode < 3; mode++) {
                    Fake f = new Fake(); f.deferCreate = true;
                    HostOperation first = f.startShort(method, method.equals("launcher.import") ? importTarget(file) : createTarget());
                    check(first.dispatched && !first.result.isDone(), "fixture dispatches native mutation before request termination");
                    if (mode == 0) {
                        first.result.cancel(false);
                        check(first.result.isCancelled(), "request future can be externally cancelled");
                    } else if (mode == 1) {
                        first.result.completeExceptionally(new RpcException("deadline_exceeded", "Fixture transport timeout", "unknown"));
                        expectOutcome("deadline_exceeded", "unknown", first);
                    } else {
                        TimeUnit.NANOSECONDS.sleep(Math.max(0, first.deadline - System.nanoTime()) + 1);
                        f.next();
                        expectOutcome("deadline_exceeded", "unknown", first);
                    }
                    f.next(); // Scheduled host tasks ignore already-completed futures, not the quarantine.
                    expectQuarantine(f.start("launcher.import", importTarget(file)), method, ID);
                    f.observeContent(0, true);
                    expectQuarantine(f.start("notes.edit", editFixture()), method, ID);
                    f.call("notes.read", window());
                    check(f.imports + f.creates == 1 && f.writes == 0 && f.nameWrites == 0, "late observation after future termination never resumes a content mutation");
                }
            }
        } finally { Files.delete(file); }
    }

    private static void reboundTargetsRemainQuarantined() throws Exception {
        Path file = Files.createTempFile("launcher-rebound-", ".arbitrary");
        String replacement = "22222222-2222-2222-2222-222222222222";
        try {
            Fake f = new Fake();
            HostOperation first = f.start("launcher.import", importTarget(file));
            f.observeTrackId(replacement); f.drain(first);
            expectOutcome("stale_context", "unknown", first);
            JsonObject otherTrack = importTarget(file); otherTrack.addProperty("trackId", replacement);
            expectQuarantine(f.start("launcher.import", otherTrack), "launcher.import", ID);
            f.observeTrackId(ID);
            expectQuarantine(f.start("launcher.import", importTarget(file)), "launcher.import", ID);
            check(f.imports == 1, "both original logical target and rebound native slot proxy retain quarantine");
            f = new Fake();
            first = f.start("launcher.import", importTarget(file));
            f.observeTrackId(replacement); f.observeTrackId(ID); f.observeContent(0, true);
            f.drain(first); expectOutcome("stale_context", "unknown", first);
            expectQuarantine(f.start("notes.edit", editFixture()), "launcher.import", ID);
            check(f.imports == 1 && f.writes == 0, "returning to the old UUID cannot erase an intervening binding change");
        } finally { Files.delete(file); }
    }

    private static void preDispatchFailuresDoNotQuarantine() throws Exception {
        Path file = Files.createTempFile("launcher-preflight-", ".arbitrary");
        try {
            Fake f = new Fake();
            JsonObject bad = createTarget(); bad.addProperty("lengthBeats", 0);
            expectOutcome("invalid_params", "not_applied", f.start("launcher.create", bad));
            expectOutcome("file_unavailable", "not_applied",
                    f.start("launcher.import", importTarget(file.resolveSibling(file.getFileName() + ".missing"))));
            f.observeContent(0, true);
            expectOutcome("slot_occupied", "not_applied", f.start("launcher.import", importTarget(file)));
            expectOutcome("slot_occupied", "not_applied", f.start("launcher.create", createTarget()));
            f.observeContent(0, false);
            HostOperation cancelled = new HostOperation(System.nanoTime() + TimeUnit.SECONDS.toNanos(2));
            f.beforeInsertion = () -> cancelled.result.completeExceptionally(new RpcException("cancelled", "Fixture pre-dispatch cancellation", "not_applied"));
            f.start(cancelled, "launcher.import", importTarget(file)); expectOutcome("cancelled", "not_applied", cancelled);
            f.beforeInsertion = () -> {};
            HostOperation valid = f.start("launcher.import", importTarget(file));
            check(f.imports == 1 && f.creates == 0, "pre-dispatch failures leave no mutation quarantine");
            f.observeContent(0, true); f.drain(valid);
            check(valid.result.join().get("status").getAsString().equals("observed"), "valid request after preflight failures completes normally");
        } finally { Files.delete(file); }
    }

    private static void unresolvedCreatesKeepTargetQuarantined() throws Exception {
        Path file = Files.createTempFile("launcher-create-failure-", ".arbitrary");
        try {
            for (int mode = 0; mode < 4; mode++) {
                Fake f = new Fake();
                f.deferCreate = mode == 0;
                f.failCreate = mode == 1;
                f.applyName = mode != 2;
                f.emitCreateContent = mode != 3;
                HostOperation first = f.startShort("launcher.create", createTarget());
                f.drain(first);
                if (mode == 1) expectOutcome("host_write_failed", "unknown", first);
                else {
                    JsonObject out = first.result.join();
                    check(out.get("status").getAsString().equals("dispatched") && out.get("mutationUnresolved").getAsBoolean(),
                            "unobserved creation reports actual unresolved mutation state");
                    check(out.get("quarantine").getAsString().equals("extension_instance"), "creation reports conservative quarantine lifetime");
                }
                f.observeContent(0, false);
                expectQuarantine(f.start("launcher.import", importTarget(file)), "launcher.create", ID);
                expectQuarantine(f.start("launcher.create", createTarget()), "launcher.create", ID);
                f.observeContent(0, true);
                expectQuarantine(f.start("notes.edit", editFixture()), "launcher.create", ID);
                int names = f.nameWrites;
                f.call("notes.read", window());
                check(f.creates == 1 && f.imports == 0 && f.writes == 0 && f.nameWrites == names, "failed create cannot be replayed or completed by a later read");
            }
            Fake f = new Fake(); f.failImport = true;
            expectOutcome("host_write_failed", "unknown", f.start("launcher.import", importTarget(file)));
            expectQuarantine(f.start("launcher.create", createTarget()), "launcher.import", ID);
            check(f.imports == 1 && f.creates == 0, "native insertion exception also retains dispatched mutation quarantine");
        } finally { Files.delete(file); }
    }

    private static JsonObject createTarget() {
        JsonObject p = target(); p.addProperty("lengthBeats", 4); p.addProperty("name", "Quarantine fixture"); return p;
    }
    private static void expectQuarantine(HostOperation op, String method, String originalId) {
        expectOutcome("unresolved_mutation", "not_applied", op);
        try { op.result.join(); }
        catch (CompletionException error) {
            String message = error.getCause().getMessage();
            check(message.contains(method) && message.contains(originalId) && message.contains("slotIndex=0")
                    && message.contains("extension instance"), "error identifies original unresolved mutation and its reset boundary");
        }
        check(!op.dispatched, "quarantine rejects before any native content mutation");
    }

    private static JsonObject importTarget(Path path) { JsonObject p = target(); p.addProperty("path", path.toString()); return p; }
    private static JsonObject editFixture() { JsonObject p = window(); p.add("edits", fixture()); return p; }
    private static void expectOutcome(String code, String outcome, HostOperation op) {
        expectFuture(code, op);
        try { op.result.join(); }
        catch (CompletionException error) { check(((RpcException) error.getCause()).outcome.equals(outcome), "expected outcome " + outcome); }
    }

    private static JsonObject trackTarget() { JsonObject p = new JsonObject(); p.addProperty("trackId", ID); return p; }
    private static JsonObject target() { JsonObject p = trackTarget(); p.addProperty("slotIndex", 0); return p; }
    private static JsonObject window() {
        JsonObject p = target(); p.addProperty("channel", 0); p.addProperty("stepSizeBeats", .25); p.addProperty("startStep", 0);
        p.addProperty("steps", 16); p.addProperty("pitchStart", 0); p.addProperty("pitchCount", 128); return p;
    }
    private static JsonObject set(double beat, int pitch, int velocity, double duration) {
        JsonObject e = new JsonObject(); e.addProperty("op", "set"); e.addProperty("beat", beat); e.addProperty("pitch", pitch);
        e.addProperty("velocity", velocity); e.addProperty("durationBeats", duration); return e;
    }
    private static JsonArray fixture() {
        JsonArray edits = new JsonArray(); edits.add(set(0, 60, 100, .5)); edits.add(set(0, 64, 80, .5)); edits.add(set(0, 67, 90, .5));
        edits.add(set(1, 62, 100, .5)); edits.add(set(0, 36, 110, .25)); return edits;
    }
    private static JsonObject find(JsonObject result, int pitch, int step) {
        for (JsonElement v : result.getAsJsonArray("notes")) { JsonObject n = v.getAsJsonObject(); if (n.get("pitch").getAsInt() == pitch && n.get("step").getAsInt() == step) return n; }
        throw new AssertionError("missing observed note");
    }
    private static void invalid(JsonObject p) { expect("invalid_params", () -> LauncherModule.validateParams("notes.edit", p)); }
    private static void expect(String code, Runnable body) {
        try { body.run(); throw new AssertionError("expected " + code); }
        catch (RpcException error) { check(error.code.equals(code), "expected error " + code + ", got " + error.code); }
    }
    private static void expectFuture(String code, HostOperation op) {
        try { op.result.join(); throw new AssertionError("expected " + code); }
        catch (CompletionException error) { check(error.getCause() instanceof RpcException && ((RpcException) error.getCause()).code.equals(code), "expected future error " + code + ", got " + error.getCause()); }
    }
    private static void check(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }
    private static String key(int slot, int channel, int step, int pitch) { return slot + ":" + channel + ":" + step + ":" + pitch; }

    private static final class Fake {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        final Map<String, Note> notes = new HashMap<>();
        final SlotData[] slots = new SlotData[16];
        final Track[] tracks = new Track[16];
        final HostServices services;
        final LauncherModule module;
        NoteStepChangedCallback observer;
        int selected, start, writes, creates, failWriteAt, nameWrites, playbackDispatches;
        double size = .25, loopStart;
        String trackId = ID;
        boolean emitNotes = true, launchPlaying, stopped = true;
        boolean emitPlaybackState = true, queueStop, stopQueued;
        boolean deferGridChanges;
        boolean deferCreate, failCreate, failImport, emitCreateContent = true, applyName = true;
        Double pendingSize;
        Integer pendingStart;
        Double noteStorageStepSize;
        long gridRefreshDelayMillis, clockMillis, gridReadyAt;
        int imports, importedSlot = -1, bankOffset;
        String importedPath;
        Runnable beforeInsertion = () -> {};
        final List<BooleanValueChangedCallback> recordObservers = new ArrayList<>();
        final BooleanValueChangedCallback recordObserver = value -> {
            for (BooleanValueChangedCallback callback : recordObservers) callback.valueChanged(value);
        };
        final List<StringValueChangedCallback> trackIdObservers = new ArrayList<>();
        Fake() { this(true, false, false, false); }
        Fake(boolean safetyObserved, boolean record, boolean arrangerAutomation, boolean launcherAutomation) {
            for (int i = 0; i < 16; i++) slots[i] = new SlotData();
            for (int t = 0; t < 16; t++) {
                final int trackIndex = t;
                Node track = new Node(Track.class);
                track.value("exists", () -> trackIndex == 0);
                Node identity = new Node(StringValue.class);
                identity.action("get", a -> trackIndex == 0 ? trackId : "");
                identity.action("addValueObserver", a -> {
                    StringValueChangedCallback callback = (StringValueChangedCallback) a[a.length - 1];
                    if (trackIndex == 0) trackIdObservers.add(callback);
                    callback.valueChanged(trackIndex == 0 ? trackId : "");
                    return null;
                });
                track.action("channelId", a -> identity.proxy);
                track.value("name", () -> "Track"); track.value("position", () -> trackIndex);
                track.value("isStopped", () -> stopped); track.value("isQueuedForStop", () -> stopQueued);
                Node bank = new Node(ClipLauncherSlotBank.class); bank.value("scrollPosition", () -> bankOffset); bank.value("itemCount", () -> 16);
                ClipLauncherSlot[] slotApis = new ClipLauncherSlot[16];
                for (int j = 0; j < 16; j++) {
                    final int index = j; SlotData data = slots[j]; Node slot = new Node(ClipLauncherSlot.class);
                    Node content = new Node(BooleanValue.class);
                    content.action("get", a -> trackIndex == 0 && data.content);
                    content.action("addValueObserver", a -> {
                        BooleanValueChangedCallback callback = (BooleanValueChangedCallback) a[a.length - 1];
                        if (trackIndex == 0) data.contentObservers.add(callback);
                        callback.valueChanged(trackIndex == 0 && data.content);
                        return null;
                    });
                    slot.action("hasContent", a -> content.proxy); slot.value("name", () -> data.name);
                    slot.value("isPlaying", () -> data.playing); slot.value("isPlaybackQueued", () -> data.queued); slot.value("isRecording", () -> false);
                    slot.action("select", a -> { selected = index; return null; });
                    slot.action("createEmptyClip", a -> {
                        creates++;
                        if (failCreate) throw HostServices.failure("host_write_failed", "Fixture native creation failure");
                        data.length = (Integer) a[0];
                        if (!deferCreate) {
                            if (emitCreateContent) observeContent(index, true);
                            else data.content = true;
                        }
                        return null;
                    });
                    slot.action("launch", a -> {
                        playbackDispatches++;
                        if (emitPlaybackState) { data.playing = launchPlaying; data.queued = !launchPlaying; stopped = false; }
                        return null;
                    });
                    Node insertion = new Node(InsertionPoint.class);
                    insertion.action("insertFile", a -> {
                        imports++; importedPath = (String) a[0]; importedSlot = index;
                        if (failImport) throw HostServices.failure("host_write_failed", "Fixture native insertion failure");
                        return null;
                    });
                    slot.action("replaceInsertionPoint", a -> { beforeInsertion.run(); return insertion.proxy; });
                    slotApis[j] = (ClipLauncherSlot) slot.proxy;
                }
                bank.action("getItemAt", a -> slotApis[(Integer) a[0]]);
                bank.action("stop", a -> {
                    playbackDispatches++;
                    if (emitPlaybackState) {
                        stopQueued = queueStop;
                        if (!queueStop) {
                            stopped = true;
                            for (SlotData data : slots) { data.playing = false; data.queued = false; }
                        }
                    }
                    return null;
                });
                track.action("clipLauncherSlotBank", a -> bank.proxy);
                Node devices = new Node(DeviceBank.class); devices.value("exists", () -> true);
                devices.value("scrollPosition", () -> 0); devices.value("itemCount", () -> 0);
                Node[] roots = new Node[16];
                for (int d = 0; d < roots.length; d++) roots[d] = emptyDevice(d, true);
                devices.action("getItemAt", a -> roots[(Integer) a[0]].proxy);
                track.action("createDeviceBank", a -> devices.proxy);
                tracks[t] = (Track) track.proxy;
            }
            Node clip = new Node(Clip.class); clip.action("getTrack", a -> tracks[0]);
            clip.value("exists", () -> slots[selected].content); clip.value("getLoopLength", () -> slots[selected].length);
            clip.value("getLoopStart", () -> loopStart); clip.value("getPlayStart", () -> 0.0); clip.value("getPlayStop", () -> slots[selected].length); clip.value("isLoopEnabled", () -> true);
            Node cursorSlot = new Node(ClipLauncherSlot.class);
            cursorSlot.action("createEqualsValue", a -> {
                Object target = a[0];
                return value(BooleanValue.class, () -> tracks[0].clipLauncherSlotBank().getItemAt(selected) == target);
            });
            clip.action("clipLauncherSlot", a -> cursorSlot.proxy);
            clip.action("setName", a -> { nameWrites++; if (applyName) slots[selected].name = (String) a[0]; return null; });
            clip.action("setStepSize", a -> {
                if (deferGridChanges || gridRefreshDelayMillis > 0) {
                    pendingSize = (Double) a[0]; gridReadyAt = clockMillis + gridRefreshDelayMillis;
                } else size = (Double) a[0];
                return null;
            });
            clip.action("scrollToStep", a -> { if (deferGridChanges || gridRefreshDelayMillis > 0) pendingStart = (Integer) a[0]; else start = (Integer) a[0]; return null; });
            clip.action("addNoteStepObserver", a -> { observer = (NoteStepChangedCallback) a[0]; return null; });
            clip.action("getStep", a -> note((Integer) a[0], (Integer) a[1], (Integer) a[2]));
            clip.action("setStep", a -> {
                writes++; if (writes == failWriteAt) throw HostServices.failure("host_write_failed", "fixture interrupted batch");
                int c = (Integer) a[0], x = (Integer) a[1], y = (Integer) a[2];
                put(selected, c, start + x, y, (Integer) a[3], (Double) a[4]);
                if (emitNotes) observer.noteStepChanged(note(c, x, y)); return null;
            });
            clip.action("clearStep", a -> {
                writes++; int c = (Integer) a[0], x = (Integer) a[1], y = (Integer) a[2];
                notes.remove(key(selected, c, start + x, y)); if (emitNotes) observer.noteStepChanged(note(c, x, y)); return null;
            });
            Node trackBank = new Node(TrackBank.class); trackBank.action("getItemAt", a -> tracks[(Integer) a[0]]); trackBank.value("scrollPosition", () -> 0); trackBank.value("itemCount", () -> 1);
            Node application = new Node(Application.class); application.value("projectName", () -> "Fixture"); application.value("hasActiveEngine", () -> true);
            Node transport = new Node(Transport.class); transport.value("isPlaying", () -> false);
            Node recordValue = new Node(SettableBooleanValue.class);
            recordValue.action("get", a -> record);
            recordValue.action("addValueObserver", a -> {
                BooleanValueChangedCallback callback = (BooleanValueChangedCallback) a[a.length - 1];
                recordObservers.add(callback);
                if (safetyObserved) callback.valueChanged(record);
                return null;
            });
            transport.action("isArrangerRecordEnabled", a -> recordValue.proxy);
            transport.action("isArrangerAutomationWriteEnabled", a -> value(SettableBooleanValue.class, () -> arrangerAutomation, safetyObserved));
            transport.action("isClipLauncherAutomationWriteEnabled", a -> value(SettableBooleanValue.class, () -> launcherAutomation, safetyObserved));
            Node host = new Node(ControllerHost.class); host.action("createApplication", a -> application.proxy); host.action("createTransport", a -> transport.proxy);
            host.action("createTrackBank", a -> trackBank.proxy); host.action("createLauncherCursorClip", a -> clip.proxy);
            host.action("scheduleTask", a -> {
                Runnable action = (Runnable) a[0]; long delay = ((Number) a[1]).longValue();
                tasks.add(() -> {
                    clockMillis += delay;
                    if (gridRefreshDelayMillis > 0 && clockMillis >= gridReadyAt) applyPendingGrid();
                    action.run();
                });
                return null;
            });
            services = new HostServices((ControllerHost) host.proxy); module = new LauncherModule(services);
        }
        void observeTrackId(String id) {
            trackId = id;
            for (StringValueChangedCallback callback : trackIdObservers) callback.valueChanged(id);
        }
        void observeContent(int slot, boolean content) {
            slots[slot].content = content;
            for (BooleanValueChangedCallback callback : slots[slot].contentObservers) callback.valueChanged(content);
        }
        void applyPendingGrid() {
            if (pendingSize != null) { size = pendingSize; pendingSize = null; }
            if (pendingStart != null) { start = pendingStart; pendingStart = null; }
        }
        void put(int slot, int channel, int step, int pitch, int velocity, double duration) { notes.put(key(slot, channel, step, pitch), new Note(velocity, duration)); }
        NoteStep note(int channel, int x, int pitch) {
            double storageSize = noteStorageStepSize == null ? size : noteStorageStepSize;
            double storagePosition = (start + x) * size / storageSize;
            int storedStep = (int) storagePosition;
            Note on = storagePosition == storedStep ? notes.get(key(selected, channel, storedStep, pitch)) : null;
            NoteStep$State state = on == null ? NoteStep$State.Empty : NoteStep$State.NoteOn;
            if (on == null) for (int step = 0; step < storagePosition; step++) {
                Note earlier = notes.get(key(selected, channel, step, pitch));
                if (earlier != null && step * storageSize + earlier.duration > (start + x) * size) { on = earlier; state = NoteStep$State.NoteSustain; break; }
            }
            final Note found = on; final NoteStep$State actual = state;
            Node n = new Node(NoteStep.class); n.action("channel", a -> channel); n.action("x", a -> x); n.action("y", a -> pitch); n.action("state", a -> actual);
            n.action("velocity", a -> found == null ? 0.0 : found.velocity / 127.0); n.action("duration", a -> found == null ? 0.0 : found.duration); return (NoteStep) n.proxy;
        }
        HostOperation start(String method, JsonObject p) {
            HostOperation op = new HostOperation(System.nanoTime() + TimeUnit.SECONDS.toNanos(2));
            return start(op, method, p);
        }
        HostOperation start(HostOperation op, String method, JsonObject p) {
            services.schedule(op, () -> module.dispatch(op, method, p), 0); tasks.removeLast().run(); return op;
        }
        HostOperation startShort(String method, JsonObject p) {
            return start(new HostOperation(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(100)), method, p);
        }
        HostOperation startAndDrain(String method, JsonObject p) throws Exception { HostOperation op = start(method, p); drain(op); return op; }
        JsonObject call(String method, JsonObject p) throws Exception { return startAndDrain(method, p).result.join(); }
        void next() { if (!tasks.isEmpty()) tasks.removeFirst().run(); }
        void drain(HostOperation op) throws Exception {
            int iterations = 0;
            while (!op.result.isDone()) { if (tasks.isEmpty() || ++iterations > 3000) throw new AssertionError("fixture operation did not settle"); next(); if (!op.result.isDone()) Thread.sleep(1); }
        }
    }
    private static final class SlotData {
        boolean content, playing, queued;
        String name = "";
        double length = 4;
        final List<BooleanValueChangedCallback> contentObservers = new ArrayList<>();
    }
    private static final class Note {
        final int velocity;
        final double duration;
        Note(int velocity, double duration) { this.velocity = velocity; this.duration = duration; }
    }

    private static Node emptyDevice(int position, boolean root) {
        Node device = new Node(Device.class);
        device.value("exists", () -> false); device.value("name", () -> "");
        device.value("position", () -> position); device.value("deviceType", () -> "");
        device.action("addDirectParameterIdObserver", a -> {
            ((StringArrayValueChangedCallback) a[0]).valueChanged(new String[0]); return null;
        });
        if (root) {
            device.value("hasLayers", () -> false);
            Node layers = new Node(DeviceLayerBank.class);
            layers.value("exists", () -> false); layers.value("itemCount", () -> 0);
            layers.value("scrollPosition", () -> 0);
            Node[] layerNodes = new Node[2];
            for (int l = 0; l < layerNodes.length; l++) {
                final int index = l;
                Node layer = layerNodes[l] = new Node(DeviceLayer.class);
                layer.value("exists", () -> false); layer.value("channelId", () -> "");
                layer.value("channelIndex", () -> index); layer.value("name", () -> "");
                Node children = new Node(DeviceBank.class);
                children.value("exists", () -> false); children.value("itemCount", () -> 0);
                children.value("scrollPosition", () -> 0);
                Node[] childNodes = {emptyDevice(0, false), emptyDevice(1, false)};
                children.action("getItemAt", a -> childNodes[(Integer) a[0]].proxy);
                layer.action("createDeviceBank", a -> children.proxy);
            }
            layers.action("getItemAt", a -> layerNodes[(Integer) a[0]].proxy);
            device.action("createLayerBank", a -> layers.proxy);
        } else {
            device.action("createCursorRemoteControlsPage", a -> {
                throw new AssertionError("Child remote controls are outside the fixed layer graph");
            });
        }
        return device;
    }

    private static final class Node implements InvocationHandler {
        final Object proxy;
        final Map<String, Function<Object[], Object>> methods = new HashMap<>();
        final Map<String, Object> children = new HashMap<>();
        final Map<String, Supplier<Object>> values = new HashMap<>();
        Node(Class<?> type) { proxy = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, this); }
        void action(String name, Function<Object[], Object> fn) { methods.put(name, fn); }
        void value(String name, Supplier<Object> supplier) { values.put(name, supplier); }
        public Object invoke(Object self, Method method, Object[] args) {
            String name = method.getName(); Object[] a = args == null ? new Object[0] : args;
            if (name.equals("toString")) return "LauncherFixtureProxy";
            if (name.equals("hashCode")) return System.identityHashCode(self);
            if (name.equals("equals")) return self == a[0];
            if (methods.containsKey(name)) return methods.get(name).apply(a);
            if (values.containsKey(name)) return children.computeIfAbsent(name, k -> LauncherModuleTest.value(method.getReturnType(), values.get(name)));
            Class<?> type = method.getReturnType();
            if (type == void.class) return null;
            if (type == boolean.class) return false;
            if (type == int.class) return 0;
            if (type == double.class) return 0.0;
            if (type == String.class) return "";
            if (type.isInterface()) return children.computeIfAbsent(name, k -> new Node(type).proxy);
            throw new AssertionError("unmodeled host method " + method);
        }
    }
    private static Object value(Class<?> type, Supplier<Object> supplier) {
        return value(type, supplier, true);
    }
    private static Object value(Class<?> type, Supplier<Object> supplier, boolean observed) {
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (self, method, args) -> {
            if (method.getName().equals("get")) return supplier.get();
            if (method.getName().equals("addValueObserver")) {
                if (!observed) return null;
                Object callback = args[args.length - 1]; Object actual = supplier.get();
                if (callback instanceof BooleanValueChangedCallback) ((BooleanValueChangedCallback) callback).valueChanged((Boolean) actual);
                else if (callback instanceof IntegerValueChangedCallback) ((IntegerValueChangedCallback) callback).valueChanged((Integer) actual);
                else if (callback instanceof StringValueChangedCallback) ((StringValueChangedCallback) callback).valueChanged((String) actual);
                else if (callback instanceof EnumValueChangedCallback) ((EnumValueChangedCallback) callback).valueChanged((String) actual);
                else if (callback instanceof DoubleValueChangedCallback) ((DoubleValueChangedCallback) callback).valueChanged(((Number) actual).doubleValue());
                return null;
            }
            if (method.getReturnType() == void.class) return null;
            throw new AssertionError("unmodeled value method " + method);
        });
    }
}
