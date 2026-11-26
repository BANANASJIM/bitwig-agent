package dev.bitwigagent.arranger;

import static dev.bitwigagent.RpcAssert.*;
import static dev.bitwigagent.TestJson.*;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.Holds;
import dev.bitwigagent.core.HostModule;
import dev.bitwigagent.core.HostOperation;
import dev.bitwigagent.core.Outcome;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.core.Refs;
import dev.bitwigagent.core.Results;
import dev.bitwigagent.launcher.ClipModule;
import dev.bitwigagent.launcher.LauncherFake;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Validation, safety and quarantine around a simulated native arranger adapter; not native binding evidence. */
public final class ArrangerModuleTest {

    private static JsonObject note(double beat, int pitch, int velocity, double duration) {
        return params("beat", beat, "pitch", pitch, "velocity", velocity, "durationBeats", duration);
    }

    private static JsonArray notes(JsonObject... notes) {
        JsonArray array = new JsonArray();
        for (JsonObject n : notes) array.add(n);
        return array;
    }

    private static JsonObject arrangerClip(JsonObject track, double start, String name) {
        return Refs.arrangerClip(track, start, name);
    }

    /** The launcher world with a native arranger adapter that queues its work and records what reaches it. */
    private static final class Fixture {
        final LauncherFake f = new LauncherFake();
        final Native lane = new Native();
        final ArrangerModule list = f.services.arranger(lane);
        boolean playing;

        Fixture(String trackType) {
            f.scenes = 40;
            f.base.tracks[0].live("trackType", () -> trackType);
            f.base.transport.live("isPlaying", () -> playing);
            ClipModule clips = f.services.clips(f.settings, lane);
            for (HostModule module : List.of(list, clips))
                for (String method : module.methods()) f.modules.put(method, module);
        }

        HostOperation later() {
            return new HostOperation(f.tasks.after(2000));
        }

        void play(boolean value) {
            playing = value;
            f.base.transport.child("isPlaying").emit(value);
        }

        void occupy(int scene, String name) {
            f.slots[scene].content = true;
            f.slots[scene].name = name;
        }

        JsonObject insert(double start, double length) {
            return params(
                    "to", params("track", f.track(), "startBeats", start), "lengthBeats", length, "name", "Verse");
        }

        HostOperation start(String method, JsonObject p) {
            return f.start(method, p);
        }

        void drain(HostOperation op) {
            f.drain(op);
        }

        JsonObject call(String method, JsonObject p) {
            return f.call(method, p);
        }

        final class Native implements ArrangerModule.Access {
            Runnable beforeRun = () -> {};
            boolean failAfterWrite, failBeforeWrite;
            int calls, writes, lists;
            List<double[]> lastNotes;
            int lastChannel;
            double lastStart, lastLength;
            Object lastSlot;
            String lastName, lastOp;
            JsonObject lastArgs, lastRef;
            final List<String> ops = new ArrayList<>();
            final List<JsonObject> argsSeen = new ArrayList<>();

            @Override
            public void execute(Runnable work) {
                f.tasks.add(() -> {
                    Runnable hook = beforeRun;
                    beforeRun = () -> {};
                    hook.run();
                    work.run();
                });
            }

            private JsonObject row(double start, double length, String name) {
                JsonObject row = params("clipIndex", 0, "startBeats", start, "lengthBeats", length, "name", name);
                return row;
            }

            private JsonObject result(JsonObject clip) {
                JsonObject out = Results.outcome(Outcome.APPLIED);
                out.addProperty("trackId", f.trackId);
                out.addProperty("lane", "main");
                JsonArray rows = new JsonArray();
                rows.add(row(8, 4, "Verse"));
                out.add("clips", rows);
                out.add("clip", clip == null ? JsonNull.INSTANCE : clip);
                return out;
            }

            private JsonObject write(JsonObject clip, Runnable check, Runnable before) {
                calls++;
                check.run();
                if (failBeforeWrite) throw Errors.notApplied(Reason.OVERLAP, "simulated overlap");
                before.run();
                writes++;
                if (failAfterWrite) throw Errors.unknown(Reason.READBACK_MISMATCH, "simulated readback mismatch");
                return result(clip);
            }

            @Override
            public JsonObject list(Object track, String id, Runnable check) {
                lists++;
                check.run();
                JsonObject out = result(null);
                out.remove("outcome");
                out.remove("clip");
                return out;
            }

            @Override
            public JsonObject create(
                    Object track,
                    String id,
                    double start,
                    double length,
                    String name,
                    List<double[]> noteList,
                    int channel,
                    Runnable check,
                    Runnable before) {
                lastNotes = noteList;
                lastChannel = channel;
                lastStart = start;
                lastLength = length;
                lastName = name;
                return write(row(start, length, name), check, before);
            }

            @Override
            public JsonObject copyFromLauncher(
                    Object track,
                    Object slot,
                    String id,
                    String expectedName,
                    double start,
                    Runnable check,
                    Runnable before) {
                lastSlot = slot;
                lastName = expectedName;
                lastStart = start;
                return write(row(start, 4, expectedName), check, before);
            }

            @Override
            public JsonObject edit(
                    Object track, JsonObject ref, String op, JsonObject args, Runnable check, Runnable before) {
                lastOp = op;
                lastArgs = args;
                lastRef = ref;
                ops.add(op);
                argsSeen.add(args);
                JsonObject out = write(
                        op.equals("delete") ? null : row(8, 4, ref.get("name").getAsString()), check, before);
                if (!op.equals("delete"))
                    out.add("previous", row(ref.get("startBeats").getAsDouble(), 4, "Verse"));
                return out;
            }
        }
    }

    @Test
    void listIsReadOnlyShapedAsRefsAndNeedsNoStoppedTransport() {
        Fixture x = new Fixture("Audio");
        x.play(true);
        JsonObject result = x.call("arranger.list", params("target", x.f.track()));
        assertTrue(!result.has("outcome") && x.lane.lists == 1 && x.lane.writes == 0, "list does not write");
        assertFalse(result.has("trackId") || result.has("lane"), "the lane identity is the parent ref, not a field");
        JsonObject row = result.getAsJsonArray("clips").get(0).getAsJsonObject();
        assertEquals(arrangerClip(x.f.track(), 8, "Verse"), row.get("ref"));
        assertFalse(row.has("clipIndex"), "an index is no identity");
        assertEquals(4.0, row.get("lengthBeats").getAsDouble());
    }

    @Test
    void listWithoutANativeBindingIsAHostMismatch() {
        Fixture x = new Fixture("Instrument");
        x.f.modules.put("arranger.list", x.f.services.arranger(null));
        assertRpcError("host_mismatch", x.start("arranger.list", params("target", x.f.track())));
    }

    @Test
    void listRefusesAnUnknownTrack() {
        Fixture x = new Fixture("Instrument");
        JsonObject gone = Refs.track("22222222-2222-2222-2222-222222222222", "Track");
        assertRpcError("target_not_found", x.start("arranger.list", params("target", gone)));
        assertEquals(0, x.lane.lists);
    }

    @Test
    void anInsertNeedsAnInstrumentTrack() {
        Fixture audio = new Fixture("Audio");
        assertRpcError("unsupported_scope", audio.start("clips.insert", audio.insert(0, 4)));
        assertEquals(0, audio.lane.calls, "a note clip on an audio track is refused before native access");
    }

    @Test
    void aPlayingTransportBlocksArrangerWrites() {
        Fixture x = new Fixture("Instrument");
        x.play(true);
        assertRpcError("unsafe_state", x.start("clips.insert", x.insert(0, 4)));
        assertEquals(0, x.lane.calls);
    }

    @Test
    void playbackStartingWhileQueuedBlocksTheWrite() {
        Fixture x = new Fixture("Instrument");
        HostOperation op = x.start("clips.insert", x.insert(0, 4));
        x.lane.beforeRun = () -> x.play(true);
        x.drain(op);
        assertRpcError("unsafe_state", op);
        assertEquals(0, x.lane.writes);
        assertTrue(x.f.services.holds.list().isEmpty(), "the refused operation holds nothing");
    }

    @Test
    void anInsertPassesNotesChannelAndPositionAndShapesTheRef() {
        Fixture x = new Fixture("Instrument");
        JsonObject p = x.insert(8, 4);
        p.add("notes", notes(note(0, 60, 100, 1), note(2, 64, 90, 0.5)));
        p.addProperty("channel", 2);
        JsonObject result = x.call("clips.insert", p);
        assertEquals("applied", result.get("outcome").getAsString());
        assertTrue(x.lane.writes == 1 && x.lane.lastNotes.size() == 2 && x.lane.lastChannel == 2);
        assertArrayEquals(new double[] {2, 64, 90, 0.5}, x.lane.lastNotes.get(1), "beat, pitch, velocity, duration");
        assertTrue(x.lane.lastStart == 8 && x.lane.lastLength == 4 && x.lane.lastName.equals("Verse"));
        assertEquals(arrangerClip(x.f.track(), 8, "Verse"), result.get("ref"));
        assertFalse(result.has("trackId") || result.has("lane"));
        assertFalse(result.getAsJsonObject("clip").has("ref"), "the ref moved to the top level of the result");
        assertFalse(result.getAsJsonObject("clip").has("clipIndex"));
        assertEquals(
                arrangerClip(x.f.track(), 8, "Verse"),
                result.getAsJsonArray("clips").get(0).getAsJsonObject().get("ref"));
        assertTrue(x.f.services.holds.list().isEmpty(), "the settled operation releases its holds");
    }

    @Test
    void anInsertWithoutNotesPassesNone() {
        Fixture x = new Fixture("Instrument");
        x.call("clips.insert", x.insert(0, 4));
        assertTrue(x.lane.lastNotes.isEmpty() && x.lane.lastChannel == 0);
    }

    @Test
    void notesAreCheckedForGridOverlapDuplicatesAndFit() {
        assertEquals(
                2,
                ArrangerModule.notes(notes(note(0, 60, 100, 1), note(1, 60, 100, 1)), 4)
                        .size());
        assertEquals(
                1,
                ArrangerModule.notes(notes(note(0.0078125, 60, 100, 0.0078125)), 4)
                        .size());
        assertRpcError(
                "invalid_params", () -> ArrangerModule.notes(notes(note(0, 60, 100, 2), note(1, 60, 100, 1)), 4));
        assertRpcError("invalid_params", () -> ArrangerModule.notes(notes(note(0, 60, 100, 1), note(0, 60, 90, 1)), 4));
        assertRpcError("invalid_params", () -> ArrangerModule.notes(notes(note(3.5, 60, 100, 1)), 4));
        assertRpcError("invalid_params", () -> ArrangerModule.notes(notes(note(0.001, 60, 100, 1)), 4));
        assertEquals(
                0.45,
                ArrangerModule.notes(notes(note(0, 60, 100, 0.45)), 4).get(0)[3],
                "durations need not align to the onset grid");
        assertEquals(
                2,
                ArrangerModule.notes(notes(note(0, 60, 100, 4), note(0, 61, 100, 4)), 4)
                        .size(),
                "different pitches may overlap");
    }

    @Test
    void invalidNotesAreRefusedBeforeScheduling() {
        Fixture x = new Fixture("Instrument");
        JsonObject p = x.insert(0, 4);
        p.add("notes", notes(note(0, 60, 100, 2), note(1, 60, 100, 1)));
        assertRpcError("invalid_params", x.start("clips.insert", p));
        assertEquals(0, x.lane.calls);
    }

    @Test
    void nativeWorkIsQueuedAndAPendingOperationMakesOthersBusy() {
        Fixture x = new Fixture("Instrument");
        HostOperation first = x.start("clips.insert", x.insert(0, 4));
        assertFalse(first.result.isDone());
        assertEquals(0, x.lane.calls, "the native adapter queues its work");
        List<Holds.View> holds = x.f.services.holds.list();
        assertTrue(holds.size() == 1 && !holds.get(0).ackable, "a running native operation is an in-flight hold");
        JsonObject read = params("target", x.f.track());
        assertRpcError("busy", () -> x.list.dispatch(x.later(), "arranger.list", read));
        JsonObject second = x.insert(8, 4);
        assertRpcError("busy", () -> x.f.clipModule.dispatch(x.later(), "clips.insert", second));
        x.drain(first);
        assertOutcome("applied", first);
        assertTrue(x.f.services.holds.list().isEmpty());
    }

    @Test
    void anUnknownWriteHoldsWritesButNotReadsUntilAck() {
        Fixture x = new Fixture("Instrument");
        x.lane.failAfterWrite = true;
        HostOperation failed = x.start("clips.insert", x.insert(0, 4));
        x.drain(failed);
        assertTrue(failed.dispatched(), "failure happened after dispatch");
        assertUnknown("readback_mismatch", failed);
        String next = assertRpcError("unresolved", x.start("clips.insert", x.insert(8, 4))).next;
        assertTrue(
                next.contains("arranger.list") && next.contains("ack arranger.quarantine"),
                "the refusal says what to inspect and what to ack");
        HostOperation edit = x.start("clips.delete", params("target", arrangerClip(x.f.track(), 8, "Verse")));
        assertRpcError("unresolved", edit);
        JsonObject read = x.call("arranger.list", params("target", x.f.track()));
        assertNotNull(read, "reads stay available");
        List<Holds.View> holds = x.f.services.holds.list();
        assertTrue(
                holds.size() == 1
                        && holds.get(0).ackable
                        && holds.get(0).description.contains(x.f.trackId),
                "the unknown write is an unresolved hold");
        x.f.services.holds.ack("arranger.quarantine");
        x.lane.failAfterWrite = false;
        assertEquals(
                "applied", x.call("clips.insert", x.insert(8, 4)).get("outcome").getAsString());
        assertTrue(x.f.services.holds.list().isEmpty());
    }

    @Test
    void aRefusalBeforeDispatchHoldsNothing() {
        Fixture x = new Fixture("Instrument");
        x.lane.failBeforeWrite = true;
        HostOperation rejected = x.start("clips.insert", x.insert(0, 4));
        x.drain(rejected);
        assertRpcError("overlap", rejected);
        assertTrue(!rejected.dispatched() && x.f.services.holds.list().isEmpty());
    }

    @Test
    void aTrackChangeWhileQueuedIsTargetChanged() {
        Fixture x = new Fixture("Instrument");
        HostOperation op = x.start("clips.insert", x.insert(0, 4));
        x.lane.beforeRun = () -> x.f.observeTrackId("22222222-2222-2222-2222-222222222222");
        x.drain(op);
        assertRpcError("target_changed", op);
        assertEquals(0, x.lane.calls);
        assertTrue(x.f.services.holds.list().isEmpty(), "the pending hold is released on failure");
    }

    @Test
    void aTrackChangeWhileAListIsQueuedIsTargetChanged() {
        Fixture x = new Fixture("Instrument");
        HostOperation op = x.start("arranger.list", params("target", x.f.track()));
        x.lane.beforeRun = () -> x.f.observeTrackId("22222222-2222-2222-2222-222222222222");
        x.drain(op);
        assertRpcError("target_changed", op);
        assertEquals(0, x.lane.lists);
    }

    @Test
    void anEditPassesOnlyItsPayloadAndShapesPreviousAndRef() {
        Fixture x = new Fixture("Instrument");
        JsonObject ref = arrangerClip(x.f.track(), 8, "Verse");
        JsonObject moved = x.call("clips.move", params("target", ref, "to", params("startBeats", 16)));
        assertEquals("move", x.lane.lastOp);
        assertEquals(Set.of("startBeats"), x.lane.lastArgs.keySet());
        assertEquals(ref, x.lane.lastRef, "the native side locates the clip by the ref");
        assertEquals(arrangerClip(x.f.track(), 8, "Verse"), moved.get("ref"));
        assertEquals(
                arrangerClip(x.f.track(), 8, "Verse"),
                moved.getAsJsonObject("previous").get("ref"));
        assertFalse(moved.getAsJsonObject("previous").has("clipIndex"));
    }

    @Test
    void aResizeAndRenameForwardOneEditEach() {
        Fixture x = new Fixture("Instrument");
        JsonObject ref = arrangerClip(x.f.track(), 8, "Verse");
        JsonObject set = x.call("clips.set", params("target", ref, "lengthBeats", 12, "name", "Chorus"));
        assertEquals(List.of("resize", "rename"), x.lane.ops);
        assertEquals(Set.of("lengthBeats"), x.lane.argsSeen.get(0).keySet());
        assertEquals(Set.of("name"), x.lane.argsSeen.get(1).keySet());
        assertEquals("applied", set.get("outcome").getAsString());
    }

    @Test
    void aDeleteResultCarriesNoRefButRowsWithRefs() {
        Fixture x = new Fixture("Instrument");
        JsonObject deleted = x.call("clips.delete", params("target", arrangerClip(x.f.track(), 8, "Verse")));
        assertEquals("delete", x.lane.lastOp);
        assertTrue(x.lane.lastArgs.keySet().isEmpty());
        assertFalse(deleted.has("ref"));
        assertTrue(deleted.get("clip").isJsonNull());
        assertTrue(deleted.getAsJsonArray("clips").get(0).getAsJsonObject().has("ref"));
    }

    @Test
    void aRefToAnUnknownTrackIsRefusedBeforeNativeAccess() {
        Fixture x = new Fixture("Instrument");
        JsonObject gone = Refs.track("22222222-2222-2222-2222-222222222222", "Track");
        assertRpcError("target_not_found", x.start("clips.delete", params("target", arrangerClip(gone, 8, "Verse"))));
        assertEquals(0, x.lane.calls);
    }

    @Test
    void aCopyPassesTheObservedLauncherSlotAndName() {
        Fixture x = new Fixture("Audio");
        x.f.sceneNames[33] = "Drop";
        x.occupy(33, "Hook");
        JsonObject result =
                x.call("clips.copy", params("from", x.f.clip(33), "to", params("track", x.f.track(), "startBeats", 8)));
        assertEquals("applied", result.get("outcome").getAsString());
        assertNotNull(x.lane.lastSlot, "the slot proxy reaches the adapter");
        assertEquals("Hook", x.lane.lastName);
        assertEquals(8.0, x.lane.lastStart);
        assertEquals(arrangerClip(x.f.track(), 8, "Hook"), result.get("ref"));
    }

    @Test
    void aCopyAbortsWhenTheSceneWindowMovesWhileQueued() {
        Fixture x = new Fixture("Audio");
        x.f.sceneNames[33] = "Drop";
        x.occupy(33, "Hook");
        HostOperation op = x.start(
                "clips.copy", params("from", x.f.clip(33), "to", params("track", x.f.track(), "startBeats", 8)));
        x.lane.beforeRun = () -> x.f.scroll(0);
        x.drain(op);
        assertRpcError("target_changed", op);
        assertEquals(0, x.lane.writes);
        assertTrue(x.f.services.holds.list().isEmpty());
    }

    @Test
    void aCopyIsRefusedWhileTheTransportPlays() {
        Fixture x = new Fixture("Audio");
        x.occupy(3, "Hook");
        x.play(true);
        assertRpcError(
                "unsafe_state",
                x.start(
                        "clips.copy",
                        params("from", x.f.clip(3), "to", params("track", x.f.track(), "startBeats", 8))));
        assertEquals(0, x.lane.calls);
    }
}
