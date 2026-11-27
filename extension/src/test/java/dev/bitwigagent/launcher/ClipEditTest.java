package dev.bitwigagent.launcher;

import static dev.bitwigagent.RpcAssert.*;
import static dev.bitwigagent.TestJson.*;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import dev.bitwigagent.core.HostOperation;
import dev.bitwigagent.core.Refs;
import dev.bitwigagent.core.RpcException;
import dev.bitwigagent.host.LauncherStructureLock;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Consumer tests against API-shaped in-memory host proxies; never native-host evidence. */
public final class ClipEditTest {

    private static JsonObject loop(double length) {
        return obj("{\"length\":" + length + "}");
    }

    private static LauncherFake riff() {
        return LauncherFake.riff();
    }

    private static JsonObject arrangerRef(LauncherFake f, double start, String name) {
        return Refs.arrangerClip(f.track(), start, name);
    }

    private static LauncherFake lane() {
        LauncherFake f = new LauncherFake();
        f.lane.clips.add(new double[] {8, 4});
        f.lane.names.add("Verse");
        return f;
    }

    private static JsonObject set(LauncherFake f, Object... pairs) {
        JsonObject p = params("target", f.clip(0));
        for (int i = 0; i < pairs.length; i += 2) p.add((String) pairs[i], toJson(pairs[i + 1]));
        return p;
    }

    private static String outcomeOf(JsonObject result, String field) {
        return result.getAsJsonObject("fields")
                .getAsJsonObject(field)
                .get("outcome")
                .getAsString();
    }

    private static String reasonOf(JsonObject result, String field) {
        return result.getAsJsonObject("fields")
                .getAsJsonObject(field)
                .get("reason")
                .getAsString();
    }

    private static HostOperation run(LauncherFake f, String method, JsonObject p) {
        return f.startAndDrain(method, p);
    }

    // ---- request rules ----

    @Test
    void invalidClipEditsAreRefusedBeforeAnythingIsScheduled() {
        LauncherFake f = riff();
        JsonObject ref = f.clip(0);
        for (JsonObject bad : List.of(
                params("target", ref),
                params("target", ref, "name", ""),
                params("target", ref, "name", "a".repeat(129)),
                params("target", ref, "color", "ff0000"),
                params("target", ref, "color", "#ff00"),
                params("target", ref, "color", "#gg0000"),
                params("target", ref, "loop", loop(0)),
                params("target", ref, "loop", loop(4097)),
                params("target", ref, "nextAction", "random"),
                params("target", ref, "nextActionLoopCount", 65),
                params("target", ref, "nextActionDelay", -1),
                params("target", ref, "launchMode", ""),
                params("target", ref, "launchQuantization", 1),
                params("target", ref, "useLoopStartAsQuantizationReference", "yes"),
                params("target", ref, "force", true))) assertRpcError("invalid_params", f.start("clips.set", bad));
        for (String[] ok : List.of(new String[] {"name", "a".repeat(128)}, new String[] {"color", "#aB09fF"})) {
            LauncherFake fresh = riff();
            HostOperation op = fresh.start("clips.set", params("target", fresh.clip(0), ok[0], ok[1]));
            assertFalse(op.result.isCompletedExceptionally(), ok[0]);
        }
        for (double length : new double[] {4096, 1.0 / 128}) {
            LauncherFake fresh = riff();
            HostOperation op = fresh.start("clips.set", params("target", fresh.clip(0), "loop", loop(length)));
            assertFalse(op.result.isCompletedExceptionally(), "loop " + length);
        }
        assertRpcError("invalid_params", f.start("clips.transpose", params("target", ref, "semitones", 0)));
        assertRpcError("invalid_params", f.start("clips.transpose", params("target", ref, "semitones", 49)));
        assertRpcError("invalid_params", f.start("clips.transpose", params("target", ref, "semitones", -49)));
        assertRpcError("invalid_params", f.start("clips.quantize", params("target", ref, "amount", 0)));
        assertRpcError("invalid_params", f.start("clips.quantize", params("target", ref, "amount", 1.01)));
        assertRpcError(
                "invalid_params",
                f.start("clips.transform", params("targets", new JsonArray(), "op", "doubleContent")));
        assertRpcError(
                "invalid_params",
                f.start("clips.transform", params("targets", JsonParser.parseString("[" + ref + "]"), "op", "bogus")));
        JsonArray many = new JsonArray();
        for (int i = 0; i < 17; i++) many.add(ref);
        assertRpcError("invalid_params", f.start("clips.transform", params("targets", many, "op", "doubleContent")));
    }

    @Test
    void aFieldTheClipKindDoesNotTakeIsUnsupportedScope() {
        LauncherFake f = lane();
        JsonObject launcherRef = f.clip(0);
        assertRpcError("unsupported_scope", f.start("clips.set", params("target", launcherRef, "length", 8)));
        JsonObject arranger = arrangerRef(f, 8, "Verse");
        assertRpcError("unsupported_scope", f.start("clips.set", params("target", arranger, "loop", loop(8))));
        assertRpcError("unsupported_scope", f.start("clips.set", params("target", arranger, "color", "#ff8800")));
        assertRpcError("unsupported_scope", f.start("clips.set", params("target", arranger, "nextAction", "stop")));
        assertRpcError("unsupported_scope", f.start("clips.set", params("target", arranger, "launchMode", "gate")));
        assertRpcError(
                "unsupported_scope",
                f.start("clips.set", params("target", arranger, "useLoopStartAsQuantizationReference", true)));
        assertRpcError(
                "unsupported_scope", f.start("clips.move", params("target", launcherRef, "to", obj("{\"start\":4}"))));
        assertEquals(0, f.lane.edited, "nothing reached the arranger lane");
        assertTrue(f.services.holds.list().isEmpty());
    }

    // ---- name, color, loop on a launcher clip ----

    @Test
    void aWrongClipNameIsStaleBeforeTheCursorMovesOrAnythingIsWritten() {
        LauncherFake f = riff();
        f.selected = 5;
        JsonObject wrong = Refs.launcherClip(f.track(), f.scene(0), "Wrong");
        RpcException error = assertRpcError(
                "target_changed", f.startAndDrain("clips.set", params("target", wrong, "name", "Verse")));
        assertEquals("Riff", error.current.get("name").getAsString(), "current names what sits there now");
        assertEquals(0, f.nameWrites);
        assertEquals(5, f.selected, "the cursor did not move");
        HostOperation gone = run(f, "clips.set", params("target", f.clip(3), "name", "Verse"));
        assertRpcError("target_not_found", gone);
        assertEquals(5, f.selected);
    }

    @Test
    void aRenameIsVerifiedByReadbackAndTheRefCarriesTheNewName() {
        LauncherFake f = riff();
        JsonObject r = f.call("clips.set", set(f, "name", "Verse"));
        assertEquals("applied", r.get("outcome").getAsString());
        assertEquals("applied", outcomeOf(r, "name"));
        assertEquals("Verse", f.slots[0].name);
        assertEquals(1, f.nameWrites);
        assertEquals("Verse", r.getAsJsonObject("ref").get("name").getAsString());
        assertEquals(f.clip(0), r.getAsJsonObject("ref"));
        assertTrue(f.services.holds.list().isEmpty(), "a rename does not quarantine");

        f = riff();
        r = f.call("clips.set", set(f, "name", "Riff"));
        assertEquals("already_matched", r.get("outcome").getAsString());
        assertEquals("already_matched", outcomeOf(r, "name"));
        assertEquals(0, f.nameWrites, "a matching name is not rewritten");

        f = riff();
        f.applyName = false;
        r = assertUnknown("readback_mismatch", f.startAndDrain("clips.set", set(f, "name", "Verse")));
        assertEquals("readback_mismatch", reasonOf(r, "name"));
        assertEquals(
                "Riff",
                r.getAsJsonObject("fields")
                        .getAsJsonObject("name")
                        .get("observed")
                        .getAsString());
        assertTrue(f.services.holds.list().isEmpty(), "an idempotent rename leaves no hold");
    }

    @Test
    void aLoopResizeMovesThePlayStopAndEachStepIsReadBack() {
        LauncherFake f = riff();
        JsonObject r = f.call("clips.set", set(f, "loop", loop(8)));
        assertEquals("applied", r.get("outcome").getAsString());
        assertEquals("applied", outcomeOf(r, "loop"));
        assertEquals(8, f.slots[0].length);
        assertEquals(8, f.slots[0].playStop);

        f = riff();
        r = f.call("clips.set", set(f, "loop", loop(4)));
        assertEquals("already_matched", r.get("outcome").getAsString());
        assertEquals(0, f.loopSets, "the same length is not rewritten");

        f = riff();
        f.applyLoop = false;
        r = assertUnknown("readback_mismatch", f.startAndDrain("clips.set", set(f, "loop", loop(8))));
        assertEquals("readback_mismatch", reasonOf(r, "loop"));

        f = riff();
        f.applyStop = false;
        r = assertUnknown("readback_mismatch", f.startAndDrain("clips.set", set(f, "loop", loop(8))));
        assertEquals("unknown", outcomeOf(r, "loop"));
    }

    @Test
    void aColorIsVerifiedByReadbackAndAnUnreadColorIsUnknown() {
        LauncherFake f = riff();
        JsonObject r = f.call("clips.set", set(f, "color", "#ff8800"));
        assertEquals("applied", r.get("outcome").getAsString());
        assertEquals(1, f.colorSets);
        assertEquals("applied", outcomeOf(r, "color"));
        r = f.call("clips.set", set(f, "color", "#ff8800"));
        assertEquals("already_matched", r.get("outcome").getAsString());
        assertEquals(1, f.colorSets, "a matching color is not rewritten");

        f = riff();
        f.applyColor = false;
        r = assertUnknown("readback_mismatch", f.startAndDrain("clips.set", set(f, "color", "#ff8800")));
        assertEquals(
                "#000000",
                r.getAsJsonObject("fields")
                        .getAsJsonObject("color")
                        .get("observed")
                        .getAsString());
    }

    @Test
    void aClipThatLeftTheSlotMidWriteIsUnknown() {
        LauncherFake f = riff();
        f.slots[1].content = true;
        f.onSetName = () -> f.selected = 1;
        f.applyName = false;
        HostOperation op = f.startAndDrain("clips.set", set(f, "name", "Verse"));
        assertTrue(op.result.isDone());
        JsonObject result = op.result.join();
        assertEquals("unknown", result.get("outcome").getAsString(), result.toString());
    }

    // ---- launch fields ----

    @Test
    void launchFieldsAreWrittenAndEachReadBackFromItsOwnObserver() {
        LauncherFake f = riff();
        f.selected = 5;
        JsonObject r =
                f.call("clips.set", set(f, "launchQuantization", "1/4", "useLoopStartAsQuantizationReference", true));
        assertEquals("applied", r.get("outcome").getAsString());
        assertEquals("1/4", f.launchQuant);
        assertTrue(f.useLoopStart);
        assertEquals(2, f.launchSets);
        assertEquals(0, f.selected, "the clip was selected on the cursor");
        assertEquals("applied", outcomeOf(r, "launchQuantization"));
        assertEquals("applied", outcomeOf(r, "useLoopStartAsQuantizationReference"));
        JsonObject options = r.getAsJsonObject("options");
        assertEquals(3, options.getAsJsonArray("launchMode").size(), "options come from the host's own enum");
        assertEquals(3, options.getAsJsonArray("launchQuantization").size());
        assertTrue(f.services.holds.list().isEmpty(), "absolute setters do not quarantine");
    }

    @Test
    void onlyDifferingLaunchFieldsAreWritten() {
        LauncherFake f = riff();
        JsonObject r = f.call("clips.set", set(f, "launchMode", "gate", "launchQuantization", "default"));
        assertEquals(1, f.launchSets);
        assertEquals("applied", outcomeOf(r, "launchMode"));
        assertEquals("already_matched", outcomeOf(r, "launchQuantization"));
        assertEquals("applied", r.get("outcome").getAsString());

        f = riff();
        r = f.call("clips.set", set(f, "launchMode", "from_group", "launchQuantization", "default"));
        assertEquals("already_matched", r.get("outcome").getAsString());
        assertEquals(0, f.launchSets);
    }

    @Test
    void aLaunchWriteWithoutItsObserverIsNotObservedAndAnUnreadOneIsUnknown() {
        LauncherFake f = riff();
        f.emitLaunch = false;
        JsonObject r = assertUnknown("not_observed", f.startAndDrain("clips.set", set(f, "launchMode", "gate")));
        assertEquals("gate", f.launchMode);
        assertEquals("not_observed", reasonOf(r, "launchMode"));

        f = riff();
        f.applyLaunch = false;
        r = assertUnknown("readback_mismatch", f.startAndDrain("clips.set", set(f, "launchMode", "gate")));
        assertEquals("readback_mismatch", reasonOf(r, "launchMode"));
    }

    @Test
    void anOptionTheHostDoesNotListIsRefusedBeforeTheSelectionMoves() {
        LauncherFake f = riff();
        f.selected = 5;
        RpcException error =
                assertRpcError("invalid_params", f.startAndDrain("clips.set", set(f, "launchMode", "bogus")));
        assertTrue(error.getMessage().contains("launchMode"), error.getMessage());
        assertEquals(0, f.launchSets);
        assertEquals(5, f.selected, "refused before selecting or writing");
        assertRpcError("invalid_params", f.startAndDrain("clips.set", set(f, "launchQuantization", "1/3")));
        assertEquals(5, f.selected);
    }

    @Test
    void aRecordingTransportBlocksLaunchAndNextActionWrites() {
        LauncherFake f = new LauncherFake(true, true, false, false);
        f.slots[0].content = true;
        f.slots[0].name = "Riff";
        assertRpcError("unsafe_state", f.startAndDrain("clips.set", set(f, "launchMode", "gate")));
        assertEquals(0, f.launchSets);
        assertRpcError("unsafe_state", f.startAndDrain("clips.set", set(f, "nextAction", "stop")));
        assertEquals(0, f.settings.calls, "refused before native access");
        JsonObject renamed = f.call("clips.set", set(f, "name", "Verse"));
        assertEquals("applied", renamed.get("outcome").getAsString(), "a rename needs no stopped transport");
    }

    // ---- Next Action, native settings ----

    private static JsonObject nextAction(LauncherFake f) {
        return set(f, "nextAction", "play_next", "nextActionLoopCount", 1);
    }

    @Test
    void aNextActionWriteIsReadBackAndReadsDoNotWrite() {
        LauncherFake f = riff();
        JsonObject written = f.call("clips.set", nextAction(f));
        assertEquals("applied", written.get("outcome").getAsString());
        assertEquals("applied", outcomeOf(written, "nextAction"));
        assertEquals("applied", outcomeOf(written, "nextActionLoopCount"));
        assertEquals(2, f.settings.writes);
        assertEquals(0, f.selected);
        assertEquals(0, f.nameWrites);

        JsonObject read = f.call("clips.get", params("target", f.clip(0)));
        assertEquals("play_next", read.get("nextAction").getAsString());
        assertEquals(2, f.settings.writes, "a read does not write");
        assertFalse(read.has("outcome"), "a read carries no outcome");

        JsonObject again = f.call("clips.set", nextAction(f));
        assertEquals("already_matched", again.get("outcome").getAsString());
        assertEquals(2, f.settings.writes, "matching fields are not rewritten");
    }

    @Test
    void aWrongNameIsRefusedBeforeNativeAccess() {
        LauncherFake f = riff();
        JsonObject wrong = Refs.launcherClip(f.track(), f.scene(0), "Wrong");
        assertRpcError("target_changed", f.startAndDrain("clips.set", params("target", wrong, "nextAction", "stop")));
        assertEquals(0, f.settings.calls);
    }

    @Test
    void anUnknownNextActionWriteStaysQuarantinedWhileReadsAreAllowed() {
        LauncherFake f = riff();
        f.settings.failWrite = true;
        assertUnknown("internal", f.startAndDrain("clips.set", nextAction(f)));
        assertEquals(1, f.settings.writes);
        RpcException blocked = assertRpcError("unresolved", f.startAndDrain("clips.set", nextAction(f)));
        assertTrue(blocked.next.contains("ack " + LauncherStructureLock.SETTINGS), blocked.next);
        f.call("clips.get", params("target", f.clip(0)));
        assertEquals(1, f.settings.writes, "no retry; reads are allowed");
        assertEquals(1, f.services.holds.list().size());
    }

    @Test
    void aCancelledOperationDoesNotWrite() {
        LauncherFake f = riff();
        HostOperation op = new HostOperation(f.tasks.after(2000));
        op.result.completeExceptionally(new RuntimeException("cancel"));
        f.start(op, "clips.set", nextAction(f));
        assertEquals(0, f.settings.writes);
        assertEquals(0, f.settings.calls);
    }

    @Test
    void aPlayingTransportBlocksTheNextActionWrite() {
        LauncherFake f = riff();
        f.base.transport.live("isPlaying", () -> true);
        f.base.transport.child("isPlaying").emit(true);
        assertRpcError("unsafe_state", f.startAndDrain("clips.set", nextAction(f)));
        assertEquals(0, f.settings.calls, "playback blocks the settings write");
    }

    @Test
    void aPendingSettingsOperationMakesSettingsAccessBusy() {
        LauncherFake f = riff();
        f.services.holds.inFlight(LauncherStructureLock.SETTINGS_PENDING, "pending");
        assertRpcError("busy", f.startAndDrain("clips.set", nextAction(f)));
        assertRpcError("busy", f.startAndDrain("clips.get", params("target", f.clip(0))));
        f.services.holds.release(LauncherStructureLock.SETTINGS_PENDING);
        assertEquals(
                "applied", f.call("clips.set", nextAction(f)).get("outcome").getAsString());
    }

    @Test
    void withoutTheNativeSettingsAccessANextActionWriteIsRefused() {
        LauncherFake f = riff();
        ClipModule bare = f.services.clips(null, f.lane);
        for (String method : bare.methods()) f.modules.put(method, bare);
        assertRpcError("host_mismatch", f.startAndDrain("clips.set", nextAction(f)));
        assertEquals(0, f.settings.calls);
    }

    // ---- mixed fields ----

    @Test
    void mixedFieldsRunNextActionThenLaunchThenClip() {
        LauncherFake f = riff();
        JsonObject r = f.call(
                "clips.set",
                set(
                        f,
                        "color",
                        "#ff8800",
                        "name",
                        "Verse",
                        "loop",
                        loop(8),
                        "launchMode",
                        "gate",
                        "nextAction",
                        "stop"));
        assertEquals("applied", r.get("outcome").getAsString());
        assertEquals(
                List.of("nextAction", "launchMode", "name", "color", "loop"),
                List.copyOf(r.getAsJsonObject("fields").keySet()),
                "the order is next action, launch, clip");
        assertEquals("Verse", f.slots[0].name);
        assertEquals(8, f.slots[0].length);
        assertEquals("gate", f.launchMode);
        assertEquals("Verse", r.getAsJsonObject("ref").get("name").getAsString());
        assertTrue(f.services.holds.list().isEmpty());
    }

    @Test
    void theFirstFieldThatDoesNotSucceedStopsTheRest() {
        LauncherFake f = riff();
        f.applyLaunch = false;
        JsonObject r = f.call(
                "clips.set",
                set(
                        f,
                        "name",
                        "Verse",
                        "color",
                        "#ff8800",
                        "loop",
                        loop(8),
                        "launchMode",
                        "gate",
                        "nextAction",
                        "stop"));
        assertEquals("unknown", r.get("outcome").getAsString(), "the worst outcome of the fields");
        assertEquals("readback_mismatch", r.get("reason").getAsString(), "the reason of the failing field");
        assertEquals("applied", outcomeOf(r, "nextAction"));
        assertEquals("unknown", outcomeOf(r, "launchMode"));
        for (String key : List.of("name", "color", "loop")) {
            assertEquals("not_applied", outcomeOf(r, key));
            assertEquals("not_reached", reasonOf(r, key));
        }
        assertEquals("Riff", f.slots[0].name);
        assertEquals(0, f.nameWrites + f.colorSets + f.loopSets, "nothing after the failing field was written");
        assertTrue(f.services.holds.list().isEmpty(), "the settled next action write released its hold");
    }

    @Test
    void aFailingFieldInTheClipStageStopsTheLaterOnes() {
        LauncherFake f = riff();
        f.applyName = false;
        JsonObject r =
                f.call("clips.set", set(f, "name", "Verse", "color", "#ff8800", "loop", loop(8), "nextAction", "stop"));
        assertEquals("unknown", r.get("outcome").getAsString());
        assertEquals("readback_mismatch", r.get("reason").getAsString());
        assertEquals("applied", outcomeOf(r, "nextAction"));
        assertEquals("not_reached", reasonOf(r, "color"));
        assertEquals("not_reached", reasonOf(r, "loop"));
        assertEquals(0, f.colorSets + f.loopSets);
    }

    @Test
    void anUnknownNextActionWriteStopsBeforeLaunchAndClipFieldsAndKeepsItsHold() {
        LauncherFake f = riff();
        f.settings.failWrite = true;
        assertUnknown(
                "internal",
                f.startAndDrain("clips.set", set(f, "launchMode", "gate", "name", "Verse", "nextAction", "stop")));
        assertEquals(0, f.launchSets + f.nameWrites, "later paths did not run");
        assertEquals(1, f.services.holds.list().size(), "the unresolved settings hold stays");
        assertEquals(LauncherStructureLock.SETTINGS, f.services.holds.list().get(0).id);
    }

    // ---- expect ----

    @Test
    void anExpectThatMatchesLetsTheWriteThrough() {
        LauncherFake f = riff();
        JsonObject r = f.call(
                "clips.set", set(f, "name", "Verse", "expect", obj("{\"name\":\"Riff\",\"loop\":{\"length\":4}}")));
        assertEquals("applied", r.get("outcome").getAsString());
        assertEquals("Verse", f.slots[0].name);

        f = riff();
        f.call("clips.set", nextAction(f));
        r = f.call(
                "clips.set",
                set(f, "loop", loop(8), "expect", obj("{\"nextAction\":\"play_next\",\"nextActionLoopCount\":1}")));
        assertEquals("applied", r.get("outcome").getAsString());
        assertEquals(8, f.slots[0].length);
    }

    @Test
    void anExpectMismatchIsStaleWithTheObservedValuesAndNothingIsWritten() {
        LauncherFake f = riff();
        f.selected = 5;
        RpcException error = assertRpcError(
                "target_changed",
                f.startAndDrain("clips.set", set(f, "name", "Verse", "expect", obj("{\"name\":\"Other\"}"))));
        assertEquals("Riff", error.observed.get("name").getAsString());
        assertEquals(0, f.nameWrites);
        assertEquals(5, f.selected);

        error = assertRpcError(
                "target_changed",
                f.startAndDrain("clips.set", set(f, "name", "Verse", "expect", obj("{\"loop\":{\"length\":8}}"))));
        assertEquals(
                4.0,
                error.observed.getAsJsonObject("loop").get("length").getAsDouble(),
                "the loop length is read natively without selecting");
        assertEquals(5, f.selected);

        f.call("clips.set", nextAction(f));
        int writes = f.settings.writes;
        error = assertRpcError(
                "target_changed",
                f.startAndDrain("clips.set", set(f, "nextAction", "stop", "expect", obj("{\"nextAction\":\"stop\"}"))));
        assertEquals("play_next", error.observed.get("nextAction").getAsString());
        assertEquals(writes, f.settings.writes, "nothing was written");
        assertEquals(5, f.selected);
        assertEquals(0, f.nameWrites);
    }

    // ---- arranger clips ----

    @Test
    void anArrangerClipIsResizedThenRenamedAndTheRefCarriesTheNewName() {
        LauncherFake f = lane();
        JsonObject r =
                f.call("clips.set", params("target", arrangerRef(f, 8, "Verse"), "length", 16, "name", "Chorus"));
        assertEquals("applied", r.get("outcome").getAsString());
        assertEquals("applied", outcomeOf(r, "length"));
        assertEquals("applied", outcomeOf(r, "name"));
        assertEquals(16, f.lane.clips.get(0)[1]);
        assertEquals("Chorus", f.lane.names.get(0));
        assertEquals(arrangerRef(f, 8, "Chorus"), r.getAsJsonObject("ref"));
        assertEquals(2, f.lane.edited);

        JsonObject renamed = f.call("clips.set", params("target", r.get("ref"), "name", "Bridge"));
        assertEquals(arrangerRef(f, 8, "Bridge"), renamed.getAsJsonObject("ref"));
        assertEquals(
                List.of("name"), List.copyOf(renamed.getAsJsonObject("fields").keySet()));
    }

    @Test
    void anArrangerSetOnAWrongNameIsStaleAndNothingIsWritten() {
        LauncherFake f = lane();
        RpcException error = assertRpcError(
                "target_changed",
                f.startAndDrain("clips.set", params("target", arrangerRef(f, 8, "Wrong"), "length", 16)));
        assertEquals("Verse", error.current.get("name").getAsString());
        assertEquals(4, f.lane.clips.get(0)[1]);
        assertRpcError(
                "target_not_found",
                f.startAndDrain("clips.set", params("target", arrangerRef(f, 40, "Verse"), "length", 16)));
        assertTrue(f.services.holds.list().isEmpty());
    }

    @Test
    void anArrangerClipIsMovedOnItsOwnTrack() {
        LauncherFake f = lane();
        JsonObject r = f.call("clips.move", params("target", arrangerRef(f, 8, "Verse"), "to", obj("{\"start\":32}")));
        assertEquals("applied", r.get("outcome").getAsString());
        assertEquals(32, f.lane.clips.get(0)[0]);
        assertEquals(4, f.lane.clips.get(0)[1], "the length is kept");
        assertEquals(arrangerRef(f, 32, "Verse"), r.getAsJsonObject("ref"));

        RpcException error = assertRpcError(
                "target_changed",
                f.startAndDrain(
                        "clips.move", params("target", arrangerRef(f, 32, "Wrong"), "to", obj("{\"start\":0}"))));
        assertEquals("Verse", error.current.get("name").getAsString());
        assertEquals(32, f.lane.clips.get(0)[0], "a stale move writes nothing");
    }

    // ---- note-content edits ----

    @Test
    void transposeShiftsTheCellsAndReportsWhatWasCompared() {
        LauncherFake f = riff();
        f.selected = 5;
        JsonObject r = f.call("clips.transpose", params("target", f.clip(0), "semitones", 2));
        JsonObject c = r.getAsJsonObject("compared");
        assertEquals("applied", r.get("outcome").getAsString());
        assertEquals(1, f.transposes);
        assertNotNull(f.at(0, 0, 0, 62));
        assertNull(f.at(0, 0, 0, 60));
        assertEquals(3, c.get("notesBefore").getAsInt());
        assertEquals(3, c.get("notesAfter").getAsInt());
        assertEquals(60, c.get("pitchMinBefore").getAsInt());
        assertEquals(62, c.get("pitchMinAfter").getAsInt());
        assertEquals(69, c.get("pitchMaxAfter").getAsInt());
        assertEquals(0, c.get("verifiedChannel").getAsInt());
        assertEquals(f.clip(0), r.getAsJsonObject("ref"));
        assertTrue(f.services.holds.list().isEmpty(), "an observed transpose releases its mutation");
        assertEquals(0, f.selected, "the clip was selected on the cursor");
    }

    @Test
    void transposeRefusalsChangeNothing() {
        LauncherFake f = riff();
        f.put(0, 0, 2, 120, 90, 2);
        assertRpcError("invalid_params", run(f, "clips.transpose", params("target", f.clip(0), "semitones", 12)));
        assertEquals(0, f.transposes);
        assertTrue(f.services.holds.list().isEmpty());

        LauncherFake empty = new LauncherFake();
        empty.slots[0].content = true;
        empty.slots[0].name = "Riff";
        assertRpcError("no_notes", run(empty, "clips.transpose", params("target", empty.clip(0), "semitones", 2)));

        f = riff();
        f.slots[0].length = 64;
        assertRpcError("unverifiable_clip", run(f, "clips.transpose", params("target", f.clip(0), "semitones", 2)));
        assertEquals(0, f.transposes, "an unverifiable clip is refused before dispatch");

        f = riff();
        f.unreadableFromStep = 0;
        assertRpcError("not_ready", run(f, "clips.transpose", params("target", f.clip(0), "semitones", 2)));
        assertEquals(0, f.transposes);

        f = riff();
        assertRpcError(
                "target_changed",
                run(
                        f,
                        "clips.quantize",
                        params("target", Refs.launcherClip(f.track(), f.scene(0), "Wrong"), "amount", 1)));
        assertRpcError("target_not_found", run(f, "clips.transpose", params("target", f.clip(2), "semitones", 2)));
    }

    @Test
    void anUnobservedTransposeHoldsTheSlot() {
        LauncherFake f = riff();
        f.applyClipOp = false;
        assertUnknown("not_observed", run(f, "clips.transpose", params("target", f.clip(0), "semitones", 2)));
        assertEquals(1, f.transposes);
        assertEquals(1, f.services.holds.list().size(), "the unresolved mutation is listed");
        RpcException held = assertRpcError("unresolved", run(f, "clips.set", set(f, "name", "Verse")));
        assertTrue(held.getMessage().contains("clips.transpose"), held.getMessage());
        assertTrue(held.next.contains("ack " + LauncherStructureLock.MUTATION), held.next);
        assertRpcError("unresolved", run(f, "clips.quantize", params("target", f.clip(0), "amount", 1)));
        assertRpcError(
                "unresolved",
                run(
                        f,
                        "clips.transform",
                        params("targets", JsonParser.parseString("[" + f.clip(0) + "]"), "op", "doubleContent")));
        assertEquals(1, f.transposes, "no retry");
        assertEquals(0, f.nameWrites);
        assertEquals(0, f.quantizes);
    }

    @Test
    void quantizeIsObservedByMovedCellsWithCountAndPitchesKept() {
        LauncherFake f = riff();
        JsonObject r = f.call("clips.quantize", params("target", f.clip(0), "amount", 1));
        JsonObject c = r.getAsJsonObject("compared");
        assertEquals("applied", r.get("outcome").getAsString());
        assertNotNull(f.at(0, 0, .5, 67));
        assertEquals(1, f.quantizes);
        assertEquals(3, c.get("notesBefore").getAsInt());
        assertEquals(3, c.get("notesAfter").getAsInt());
    }

    @Test
    void anUnobservedPartialQuantizeAndAChangedCountHoldTheSlot() {
        LauncherFake f = riff();
        f.quantizeMoves = false;
        assertUnknown("not_observed", run(f, "clips.quantize", params("target", f.clip(0), "amount", 0.5)));
        assertEquals(1, f.services.holds.list().size());

        f = riff();
        f.put(0, 0, .875, 64, 80, .125); // quantizes onto 64@1: two onsets in one cell
        assertUnknown("readback_mismatch", run(f, "clips.quantize", params("target", f.clip(0), "amount", 1)));
        assertEquals(1, f.services.holds.list().size(), "a changed onset count is unknown and holds");
    }

    @Test
    void quantizeAmountOneWithNoMovementIsIdempotentUnknownWithoutAHold() {
        LauncherFake f = riff();
        f.quantizeMoves = false;
        JsonObject r = f.call("clips.quantize", params("target", f.clip(0), "amount", 1));
        assertEquals("unknown", r.get("outcome").getAsString());
        assertEquals("not_observed", r.get("reason").getAsString());
        assertTrue(f.services.holds.list().isEmpty(), "idempotent, so no hold");
        assertEquals(1, f.quantizes);
    }

    @Test
    void quantizeRefusesAClipItCannotVerify() {
        LauncherFake f = riff();
        f.slots[0].length = 64;
        assertRpcError("unverifiable_clip", run(f, "clips.quantize", params("target", f.clip(0), "amount", 1)));
        LauncherFake empty = new LauncherFake();
        empty.slots[0].content = true;
        empty.slots[0].name = "Riff";
        assertRpcError("no_notes", run(empty, "clips.quantize", params("target", empty.clip(0), "amount", 1)));
        assertEquals(0, f.quantizes + empty.quantizes);
    }

    @Test
    void doubleContentIsVerifiedByTheDoubledLoop() {
        LauncherFake f = riff();
        JsonObject r = f.call("clips.transform", params("targets", clips(f, 0), "op", "doubleContent"));
        assertEquals("applied", r.get("outcome").getAsString());
        JsonObject entry = r.getAsJsonArray("entries").get(0).getAsJsonObject();
        assertEquals("applied", entry.get("outcome").getAsString());
        assertEquals(8, f.slots[0].length);
        assertEquals(
                8, entry.getAsJsonObject("compared").get("loopLengthBeatsAfter").getAsDouble());
        assertEquals(f.clip(0), entry.getAsJsonObject("ref"));
        assertTrue(f.services.holds.list().isEmpty());

        f = riff();
        f.applyClipOp = false;
        r = f.call("clips.transform", params("targets", clips(f, 0), "op", "doubleContent"));
        assertEquals("unknown", r.get("outcome").getAsString());
        assertEquals(
                "not_observed",
                r.getAsJsonArray("entries")
                        .get(0)
                        .getAsJsonObject()
                        .get("reason")
                        .getAsString());
        assertEquals(1, f.services.holds.list().size(), "an unobserved double holds the slot");
    }

    private static JsonArray clips(LauncherFake f, int... scenes) {
        JsonArray out = new JsonArray();
        for (int scene : scenes) out.add(f.clip(scene));
        return out;
    }

    // ---- transform across windows ----

    private static LauncherFake twoWindows() {
        LauncherFake f = new LauncherFake();
        f.scenes = 40;
        f.sceneNames[33] = "Drop";
        for (int scene : new int[] {3, 33}) {
            f.slots[scene].content = true;
            f.slots[scene].name = "Clip" + scene;
            f.put(scene, 0, 0, 60, 100, 1);
        }
        return f;
    }

    @Test
    void transformWritesEveryTargetInOrderAcrossTwoWindows() {
        LauncherFake f = twoWindows();
        JsonObject r = f.call("clips.transform", params("targets", clips(f, 3, 33), "op", "doubleContent"));
        assertEquals("applied", r.get("outcome").getAsString());
        assertEquals(2, r.getAsJsonArray("entries").size());
        for (JsonElement entry : r.getAsJsonArray("entries"))
            assertEquals("applied", entry.getAsJsonObject().get("outcome").getAsString());
        assertEquals(2, f.duplicates);
        assertEquals(8, f.slots[3].length);
        assertEquals(8, f.slots[33].length);
        assertEquals(
                f.clip(3), r.getAsJsonArray("entries").get(0).getAsJsonObject().get("ref"));
        assertEquals(
                f.clip(33), r.getAsJsonArray("entries").get(1).getAsJsonObject().get("ref"));
        assertEquals(24, f.bankOffset, "the second window is held for the last write");
        assertTrue(f.services.holds.list().isEmpty());
    }

    @Test
    void oneStaleTargetRefusesTheWholeBatchBeforeAnythingIsWritten() {
        LauncherFake f = twoWindows();
        JsonArray targets = clips(f, 3);
        targets.add(Refs.launcherClip(f.track(), f.scene(33), "Renamed"));
        RpcException error = assertRpcError(
                "target_changed", run(f, "clips.transform", params("targets", targets, "op", "doubleContent")));
        assertEquals(1, error.entries.size());
        JsonObject entry = error.entries.get(0).getAsJsonObject();
        assertEquals(1, entry.get("index").getAsInt(), "the entry names the failing target");
        assertEquals("target_changed", entry.get("reason").getAsString());
        assertEquals("Clip33", entry.getAsJsonObject("current").get("name").getAsString());
        assertEquals(0, f.duplicates, "nothing was written");
        assertEquals(4, f.slots[3].length);

        JsonArray beyond = clips(f, 3);
        beyond.add(Refs.launcherClip(f.track(), Refs.scene(50, null), "Clip50"));
        RpcException missing = assertRpcError(
                "target_not_found", run(f, "clips.transform", params("targets", beyond, "op", "doubleContent")));
        assertEquals(1, missing.entries.get(0).getAsJsonObject().get("index").getAsInt());
        JsonArray both = clips(f, 3, 33);
        both.add(f.clip(9));
        RpcException empty = assertRpcError(
                "target_not_found", run(f, "clips.transform", params("targets", both, "op", "doubleContent")));
        assertEquals(2, empty.entries.get(0).getAsJsonObject().get("index").getAsInt());
        assertEquals(0, f.duplicates);
    }

    @Test
    void theFirstFailedEntryStopsTheRestAsNotReached() {
        LauncherFake f = twoWindows();
        f.applyClipOp = false;
        JsonObject r = f.call("clips.transform", params("targets", clips(f, 3, 33), "op", "doubleContent"));
        assertEquals("unknown", r.get("outcome").getAsString());
        JsonArray entries = r.getAsJsonArray("entries");
        assertEquals(2, entries.size());
        assertEquals("unknown", entries.get(0).getAsJsonObject().get("outcome").getAsString());
        assertEquals(
                "not_applied", entries.get(1).getAsJsonObject().get("outcome").getAsString());
        assertEquals(
                "not_reached", entries.get(1).getAsJsonObject().get("reason").getAsString());
        assertEquals(1, f.duplicates, "the second target was never written");
        assertEquals(4, f.slots[33].length);
        assertEquals(1, f.services.holds.list().size(), "only the failed first clip is held");
    }

    // ---- a clip deep in the scene list ----

    @Test
    void aSetOnASceneInTheThirdWindowHoldsTheLeaseAtTheRightOffset() {
        LauncherFake f = new LauncherFake();
        f.scenes = 40;
        f.sceneNames[33] = "Drop";
        f.slots[33].content = true;
        f.slots[33].name = "Hook";
        JsonObject r = f.call("clips.set", params("target", f.clip(33), "name", "Hook2", "nextAction", "stop"));
        assertEquals("applied", r.get("outcome").getAsString());
        assertEquals("Hook2", f.slots[33].name);
        assertEquals("stop", f.slots[33].nextAction.get("nextAction").getAsString());
        assertEquals(33, f.selected);
        assertEquals(24, f.bankOffset, "scene 33 of 40 sits in the window at 24");
        assertEquals(f.clip(33), r.getAsJsonObject("ref"));
        assertEquals(
                33,
                r.getAsJsonObject("ref")
                        .getAsJsonObject("slot")
                        .getAsJsonObject("scene")
                        .get("index")
                        .getAsInt());
    }

    @Test
    void aWriteAfterAScrollResolvesAgainstTheSettledWindow() {
        LauncherFake f = new LauncherFake();
        f.scenes = 40;
        f.scrollEchoMs = 200;
        f.slots[3].content = true;
        f.slots[3].name = "Near";
        f.slots[33].content = true;
        f.slots[33].name = "Far";
        JsonObject far = f.call("clips.set", params("target", f.clip(33), "nextAction", "stop"));
        assertEquals("applied", far.get("outcome").getAsString());
        assertEquals(24, f.bankOffset);
        JsonObject near = f.call("clips.set", params("target", f.clip(3), "nextAction", "stop"));
        assertEquals("applied", near.get("outcome").getAsString());
        assertEquals(0, f.bankOffset);
        assertEquals(
                f.clip(3),
                near.get("ref").getAsJsonObject(),
                "the ref is the near clip's, not a clip of the old window");
        assertTrue(f.slots[3].nextAction.has("nextAction") && f.slots[33].nextAction.has("nextAction"));
    }

    @Test
    void aFailureBetweenTransformEntriesReleasesTheSelection() {
        LauncherFake f = twoWindows();
        f.onSelect = () -> f.scrollStuck = true;
        HostOperation op =
                f.startAndDrain("clips.transform", params("targets", clips(f, 3, 33), "op", "doubleContent"));
        JsonObject out = op.result.join();
        JsonArray entries = out.getAsJsonArray("entries");
        assertEquals("applied", entries.get(0).getAsJsonObject().get("outcome").getAsString());
        assertEquals(
                "not_ready", entries.get(1).getAsJsonObject().get("reason").getAsString(), "the move is the entry's");
        f.scrollStuck = false;
        f.onSelect = () -> {};
        HostOperation read = f.startAndDrain("notes.get", params("target", f.clip(3)));
        assertTrue(read.result.join().has("notes"), "the next cursor operation is admitted");
    }

    @Test
    void anExpectKeyTheClipKindCannotHaveIsRefusedAsUnsupportedScope() {
        LauncherFake f = twoWindows();
        f.lane.clips.add(new double[] {8, 4});
        f.lane.names.add("Verse");
        RpcException arranger = assertRpcError(
                "unsupported_scope",
                f.start(
                        "clips.set",
                        params(
                                "target",
                                arrangerRef(f, 8, "Verse"),
                                "name",
                                "x",
                                "expect",
                                obj("{\"nextAction\":\"stop\"}"))));
        assertTrue(arranger.getMessage().contains("expect.nextAction"));
        assertRpcError(
                "unsupported_scope",
                f.start("clips.set", params("target", f.clip(3), "name", "x", "expect", obj("{\"length\":4}"))));
        assertEquals(0, f.lane.edited);
    }
}
