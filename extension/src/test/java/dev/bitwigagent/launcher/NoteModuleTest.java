package dev.bitwigagent.launcher;

import static dev.bitwigagent.RpcAssert.*;
import static dev.bitwigagent.TestJson.*;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import dev.bitwigagent.core.HostOperation;
import dev.bitwigagent.core.Refs;
import dev.bitwigagent.core.RpcException;
import org.junit.jupiter.api.Test;

/**
 * The note methods without the native binding: the public note grid, read and written window by window. Consumer
 * tests against API-shaped in-memory host proxies; never native-host evidence.
 */
public final class NoteModuleTest {

    static JsonObject note(double beat, int pitch, int velocity, double duration) {
        JsonObject n = new JsonObject();
        n.addProperty("beat", beat);
        n.addProperty("pitch", pitch);
        n.addProperty("velocity", velocity);
        n.addProperty("duration", duration);
        return n;
    }

    static JsonObject note(double beat, int pitch, int velocity, double duration, int channel) {
        JsonObject n = note(beat, pitch, velocity, duration);
        n.addProperty("channel", channel);
        return n;
    }

    static JsonArray array(JsonObject... all) {
        JsonArray list = new JsonArray();
        for (JsonObject one : all) list.add(one);
        return list;
    }

    /** A launcher clip named Riff at the scene. */
    static void clipAt(LauncherFake f, int scene) {
        f.slots[scene].content = true;
        f.slots[scene].name = "Riff";
    }

    static JsonObject target(LauncherFake f, int scene) {
        JsonObject p = new JsonObject();
        p.add("target", f.clip(scene));
        return p;
    }

    /** notes.set params; {@code range} null for an upsert. */
    static JsonObject set(LauncherFake f, int scene, Object range, JsonObject... notes) {
        JsonObject p = target(f, scene);
        if (range != null) p.add("range", toJson(range));
        p.add("notes", array(notes));
        return p;
    }

    static JsonObject range(double start, double end) {
        JsonObject r = new JsonObject();
        r.addProperty("start", start);
        r.addProperty("end", end);
        return r;
    }

    /** Three notes on channel 0 and one on channel 2 of the clip at scene 0. */
    static LauncherFake riff() {
        LauncherFake f = new LauncherFake();
        clipAt(f, 0);
        f.put(0, 0, 0, 60, 100, 1);
        f.put(0, 0, 1, 64, 90, 1);
        f.put(0, 0, .375, 67, 80, .25);
        f.put(0, 2, 2, 36, 110, .5);
        return f;
    }

    static void invalid(LauncherFake f, String method, JsonObject p) {
        HostOperation op = f.start(method, p);
        assertTrue(op.result.isDone(), () -> "accepted: " + p);
        assertRpcError("invalid_params", op);
        assertFalse(op.dispatched(), "a rejected request touches nothing");
    }

    // ---- validation ----

    @Test
    void malformedRequestsAreRefusedBeforeAnythingIsSelected() {
        LauncherFake f = riff();
        JsonObject bad = set(f, 0, null, note(0, 60, 100, 1));
        bad.addProperty("detail", "full");
        invalid(f, "notes.set", bad);
        invalid(f, "notes.set", set(f, 0, null));
        invalid(f, "notes.set", set(f, 0, range(2, 2), note(2, 60, 100, 1)));
        invalid(f, "notes.set", set(f, 0, obj("{\"start\":0,\"end\":4,\"pitches\":[50,40]}")));
        invalid(f, "notes.set", set(f, 0, "some", note(0, 60, 100, 1)));
        invalid(f, "notes.set", set(f, 0, null, note(0, 60, 100, 1, 16)));
        JsonObject old = target(f, 0);
        old.add("edits", array(note(0, 60, 100, 1)));
        invalid(f, "notes.set", old);
        JsonObject window = target(f, 0);
        window.addProperty("stepSize", .25);
        invalid(f, "notes.get", window);
        invalid(f, "notes.get", set(f, 0, range(3, 1)));
        assertEquals(0, f.selected);
        assertEquals(0, f.writes);
    }

    @Test
    void everyRuleTheListedNotesBreakIsRefusedAtOnce() {
        LauncherFake f = riff();
        JsonObject p = set(
                f,
                0,
                obj("{\"start\":0,\"end\":4,\"pitches\":[60,64]}"),
                note(0, 60, 100, 1),
                note(0, 60, 90, .5),
                note(.5, 60, 100, .25),
                note(5, 62, 100, 1),
                note(1, 70, 100, 1));
        HostOperation op = f.start("notes.set", p);
        String message = assertRpcError("invalid_params", op).getMessage();
        assertTrue(message.startsWith("notes: 4 problems, nothing was written: notes[1] repeats"), message);
        assertTrue(message.contains("notes[2] starts at beat 0.5 before notes[0] on pitch 60 ends at beat 1"), message);
        assertTrue(message.contains("notes[3] beat 5 is outside range [0, 4)"), message);
        assertTrue(message.contains("notes[4] pitch 70 is outside range.pitches [60, 64]"), message);
        invalid(f, "notes.set", set(f, 0, range(1, 2), note(.5, 60, 100, .25)));
        assertEquals(0, f.writes);
    }

    // ---- reads ----

    @Test
    void aReadListsEveryChannelOfTheLoopAsRecordsAtTheCellResolution() {
        LauncherFake f = riff();
        JsonObject read = f.call("notes.get", target(f, 0));
        assertEquals(f.clip(0), read.getAsJsonObject("ref"));
        assertFalse(read.get("exact").getAsBoolean());
        assertEquals(.125, read.get("onsetResolution").getAsDouble(), "a 4-beat loop reads at 1/32 note");
        assertTrue(read.get("complete").getAsBoolean());
        assertEquals(4, read.get("count").getAsInt());
        JsonArray notes = read.getAsJsonArray("notes");
        assertEquals(note(0, 60, 100, 1), notes.get(0));
        assertEquals(note(.375, 67, 80, .25), notes.get(1));
        assertEquals(note(1, 64, 90, 1), notes.get(2));
        assertEquals(note(2, 36, 110, .5, 2), notes.get(3), "channel is listed when it is not 0");
        assertEquals(0, f.selected, "the read selected the clip");
        assertFalse(read.has("window") || read.has("gridReadiness"));
    }

    @Test
    void aRangeLimitsTheReadAndALongLoopReadsInSeveralWindows() {
        LauncherFake f = riff();
        f.slots[0].length = 40;
        f.put(0, 0, 36.5, 72, 70, 1);
        JsonObject read = f.call("notes.get", target(f, 0));
        assertEquals(.25, read.get("onsetResolution").getAsDouble(), "a loop past 16 beats reads at 1/16 note");
        assertEquals(5, read.get("count").getAsInt(), "beat 36.5 lies in the second 128-step window");
        JsonObject p = target(f, 0);
        p.add("range", obj("{\"start\":0,\"end\":2,\"pitches\":[60,64]}"));
        JsonObject ranged = f.call("notes.get", p);
        assertEquals(obj("{\"start\":0,\"end\":2,\"pitches\":[60,64]}"), ranged.get("range"));
        assertEquals(2, ranged.get("count").getAsInt());
        JsonObject far = target(f, 0);
        far.add("range", range(0, 100000));
        JsonObject loop = f.call("notes.get", far);
        assertTrue(loop.get("complete").getAsBoolean(), "a range past the loop end is read up to it");
        assertEquals(5, loop.get("count").getAsInt());
    }

    @Test
    void aReadThatRunsOutOfTimeSaysItIsIncomplete() {
        LauncherFake f = riff();
        f.slots[0].length = 400;
        HostOperation op = f.start(new HostOperation(f.tasks.after(1200)), "notes.get", target(f, 0));
        f.drain(op);
        JsonObject read = op.result.join();
        assertFalse(read.get("complete").getAsBoolean());
    }

    // ---- writes ----

    @Test
    void withoutRangeTheListedNotesAreWrittenInPlaceAndNothingIsRemoved() {
        LauncherFake f = riff();
        JsonObject quieter = new JsonObject();
        quieter.addProperty("beat", 0);
        quieter.addProperty("pitch", 60);
        quieter.addProperty("velocity", 40);
        JsonObject out = f.call("notes.set", set(f, 0, null, quieter, note(3, 48, 90, .5)));
        assertEquals("applied", out.get("outcome").getAsString());
        assertEquals("api_readback", out.get("verification").getAsString());
        assertEquals(obj("{\"inserted\":1,\"updated\":1,\"removed\":0,\"unchanged\":0}"), out.get("summary"));
        assertEquals("per_note", out.get("undo").getAsString());
        assertFalse(out.get("exact").getAsBoolean());
        assertEquals(40, f.at(0, 0, 0, 60).velocity);
        assertEquals(1.0, f.at(0, 0, 0, 60).duration, "an omitted duration is kept");
        assertEquals(5, f.clipNotes(0).size(), "nothing was removed");
    }

    @Test
    void withARangeTheRangeBecomesTheListedNotesAndAReplayConverges() {
        LauncherFake f = riff();
        JsonObject p = set(f, 0, range(0, 2), note(0, 60, 100, 1), note(.5, 62, 70, .5));
        JsonObject out = f.call("notes.set", p);
        assertEquals("applied", out.get("outcome").getAsString());
        assertEquals(obj("{\"inserted\":1,\"updated\":0,\"removed\":2,\"unchanged\":1}"), out.get("summary"));
        assertEquals(range(0, 2), out.get("range"));
        assertNull(f.at(0, 0, 1, 64), "an unlisted note in the range is removed");
        assertNotNull(f.at(0, 2, 2, 36), "a note at the range end stays");
        int writes = f.writes;
        JsonObject again = f.call("notes.set", p);
        assertEquals("already_matched", again.get("outcome").getAsString());
        assertEquals("api_readback", again.get("verification").getAsString());
        assertEquals("none", again.get("undo").getAsString());
        assertEquals(writes, f.writes, "a replay writes nothing");
    }

    @Test
    void aReadWrittenBackIsAlreadyMatchedAndAllWithNoNotesClears() {
        LauncherFake f = riff();
        JsonObject read = f.call("notes.get", target(f, 0));
        JsonObject p = target(f, 0);
        p.addProperty("range", "all");
        p.add("notes", read.getAsJsonArray("notes"));
        JsonObject echo = f.call("notes.set", p);
        assertEquals("already_matched", echo.get("outcome").getAsString());
        assertEquals(4, echo.getAsJsonObject("summary").get("unchanged").getAsInt());
        p.add("notes", new JsonArray());
        JsonObject cleared = f.call("notes.set", p);
        assertEquals(4, cleared.getAsJsonObject("summary").get("removed").getAsInt());
        assertTrue(f.clipNotes(0).isEmpty());
    }

    @Test
    void theClipRulesAreJudgedAgainstTheNotesLeftInPlace() {
        LauncherFake f = riff();
        JsonObject overlap = f.call("notes.set", set(f, 0, null, note(.5, 60, 90, .25)));
        assertEquals("unknown", overlap.get("outcome").getAsString(), "a refusal after selecting is unknown");
        assertTrue(overlap.get("message").getAsString().contains("sustains to beat 1"), overlap.toString());
        JsonObject partial = new JsonObject();
        partial.addProperty("beat", 3);
        partial.addProperty("pitch", 50);
        partial.addProperty("velocity", 90);
        JsonObject missing = f.call("notes.set", set(f, 0, null, partial));
        assertTrue(missing.get("message").getAsString().contains("is a new note"), missing.toString());
        JsonObject past = f.call("notes.set", set(f, 0, null, note(3.5, 50, 90, 1)));
        assertTrue(past.get("message").getAsString().contains("past the clip loop end 4"), past.toString());
        assertEquals(0, f.writes);
    }

    @Test
    void onsetsOffThePublicGridAreRefusedBeforeAnythingIsSelected() {
        LauncherFake f = riff();
        HostOperation op = f.startAndDrain("notes.set", set(f, 0, null, note(.3, 60, 90, .25)));
        RpcException error = assertRpcError("native_unavailable", op);
        assertTrue(error.getMessage().contains("notes[0] beat 0.3 is not on the 1/128-beat grid"), error.getMessage());
        assertFalse(op.dispatched());
    }

    @Test
    void aWriteThatDoesNotReadBackIsUnknown() {
        LauncherFake f = riff();
        f.dropPublicWrites = true;
        JsonObject out = f.call("notes.set", set(f, 0, null, note(3, 48, 90, .5)));
        assertEquals("unknown", out.get("outcome").getAsString());
        assertEquals("not_observed", out.get("reason").getAsString());
        assertEquals(1, out.getAsJsonObject("summary").get("inserted").getAsInt());
    }

    @Test
    void aSpanBeyondOneWindowIsWrittenWindowByWindow() {
        LauncherFake f = new LauncherFake();
        clipAt(f, 0);
        f.slots[0].length = 64;
        JsonObject out = f.call("notes.set", set(f, 0, null, note(0, 60, 90, 1), note(48, 60, 90, 1)));
        assertEquals("applied", out.get("outcome").getAsString());
        assertNotNull(f.at(0, 0, 48, 60));
        assertEquals(2, f.clipNotes(0).size());
    }

    // ---- guards ----

    @Test
    void theCursorLeaseIsHeldWhileThePublicPathRuns() {
        LauncherFake f = riff();
        HostOperation first = f.start("notes.get", target(f, 0));
        assertTrue(f.services.selection.held());
        assertRpcError("busy", f.start("notes.get", target(f, 0)));
        f.drain(first);
        assertFalse(f.services.selection.held(), "the lease is released with the operation");
    }

    @Test
    void aTrackChangeAfterSelectingIsUnknownAndWritesNothing() {
        LauncherFake f = riff();
        HostOperation op = f.start("notes.set", set(f, 0, null, note(3, 48, 90, .5)));
        f.trackId = "22222222-2222-2222-2222-222222222222";
        f.drain(op);
        assertEquals("unknown", op.result.join().get("outcome").getAsString());
        assertEquals(0, f.writes);
    }

    @Test
    void unreadableGridsFailClosed() {
        LauncherFake f = riff();
        f.unreadableFromStep = 0;
        assertUnknown("not_ready", f.startAndDrain("notes.get", target(f, 0)));
        assertUnknown("not_ready", f.startAndDrain("notes.set", set(f, 0, null, note(3, 48, 90, .5))));
        assertEquals(0, f.writes);
    }

    @Test
    void anEmptyOrMissingSlotIsNotFoundBeforeAnythingIsSelected() {
        LauncherFake f = new LauncherFake();
        f.scenes = 40;
        HostOperation empty = f.startAndDrain("notes.get", target(f, 5));
        assertRpcError("target_not_found", empty);
        assertFalse(empty.dispatched());
        JsonObject beyond = new JsonObject();
        beyond.add("target", Refs.launcherClip(f.track(), Refs.scene(50, null), "Riff"));
        beyond.add("notes", array(note(0, 60, 1, .25)));
        assertRpcError("target_not_found", f.startAndDrain("notes.set", beyond));
        assertEquals(0, f.scrolls, "nothing scrolled for a scene that does not exist");
    }

    private static LauncherFake farScene() {
        LauncherFake f = new LauncherFake();
        f.scenes = 40;
        f.sceneNames[33] = "Drop";
        clipAt(f, 33);
        f.put(33, 0, 1, 61, 100, 1);
        return f;
    }

    @Test
    void aClipBeyondTheFirstSceneWindowIsReadAndWrittenThere() {
        LauncherFake f = farScene();
        JsonObject read = f.call("notes.get", target(f, 33));
        assertEquals(f.clip(33), read.getAsJsonObject("ref"));
        assertEquals(1, read.get("count").getAsInt());
        assertEquals(24, f.bankOffset, "the window showed scene 33 while the clip was read");
        assertEquals(
                "applied",
                f.call("notes.set", set(f, 33, null, note(2, 65, 90, .5)))
                        .get("outcome")
                        .getAsString());
        assertNotNull(f.at(33, 0, 2, 65));
    }

    @Test
    void aStaleSceneNameIsRefusedWithTheCurrentRefBeforeAnythingIsSelected() {
        for (String method : new String[] {"notes.get", "notes.set"}) {
            LauncherFake f = farScene();
            JsonObject p = method.equals("notes.set") ? set(f, 33, null, note(2, 65, 90, .5)) : target(f, 33);
            f.sceneNames[33] = "Break";
            HostOperation op = f.startAndDrain(method, p);
            RpcException error = assertRpcError("target_changed", op);
            assertEquals(
                    "Break",
                    error.current
                            .getAsJsonObject("slot")
                            .getAsJsonObject("scene")
                            .get("name")
                            .getAsString());
            assertFalse(op.dispatched());
            assertEquals(0, f.writes);
        }
    }

    @Test
    void aWriteIsRefusedWhileAMutationIsUnresolvedForThatAbsoluteScene() {
        LauncherFake f = farScene();
        f.slots[33].content = false;
        f.failCreate = true;
        JsonObject create = obj("{\"loop\":{\"length\":4},\"name\":\"Hook\"}");
        create.add("to", f.slot(33));
        assertUnknown("host_refused", f.startAndDrain("clips.insert", create));
        clipAt(f, 33);
        clipAt(f, 3);
        HostOperation refused = f.startAndDrain("notes.set", set(f, 33, null, note(2, 65, 90, .5)));
        RpcException error = assertRpcError("unresolved", refused);
        assertTrue(error.getMessage().contains("scene 33"), error.getMessage());
        assertFalse(refused.dispatched());
        assertEquals(1, f.call("notes.get", target(f, 33)).get("count").getAsInt(), "reads still work");
        assertEquals(
                "applied",
                f.call("notes.set", set(f, 3, null, note(2, 65, 90, .5)))
                        .get("outcome")
                        .getAsString(),
                "another scene of the same track is not held");
    }
}
