package dev.bitwigagent;

import static dev.bitwigagent.RpcAssert.*;
import static dev.bitwigagent.TestJson.*;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import dev.bitwigagent.core.Refs;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Simulated scene bank: callbacks and queued host tasks only; says nothing about native scene behavior. */
public final class SceneModuleTest {

    private static LauncherFake world(int scenes, String... names) {
        LauncherFake f = new LauncherFake();
        f.scenes = scenes;
        for (int i = 0; i < names.length; i++) f.sceneNames[i] = names[i];
        return f;
    }

    private static String outcome(JsonObject result) {
        return result.get("outcome").getAsString();
    }

    private static int refIndex(JsonObject result) {
        return result.getAsJsonObject("ref").get("index").getAsInt();
    }

    private static JsonObject row(JsonObject listed, int i) {
        return listed.getAsJsonArray("scenes").get(i).getAsJsonObject();
    }

    private static void holdWindow(LauncherFake f, int offset) {
        HostOperation holder = new HostOperation(f.tasks.after(60_000));
        f.services.launcherTargets().lease.enter(holder, offset, false, false, () -> {});
        for (int i = 0; i < 20 && !f.tasks.isEmpty(); i++) f.next();
        assertEquals(offset, f.bankOffset);
    }

    // ---- validation and readiness ----

    @Test
    void parametersAreValidatedBeforeAnythingIsScheduled() {
        LauncherFake f = world(3, "Intro");
        for (JsonObject bad :
                List.of(params("count", 0), params("count", 129), params("count", 1.5), params("extra", 1)))
            assertRpcError("invalid_params", f.start("scenes.insert", bad));
        for (JsonObject bad : List.of(
                params("target", f.scene(0)),
                params("target", f.scene(0), "name", ""),
                params("target", f.scene(0), "name", "x".repeat(129)),
                params("name", "X"),
                params("target", f.slot(0), "name", "X"))) assertRpcError("invalid_params", f.start("scenes.set", bad));
        assertRpcError("invalid_params", f.start("scenes.delete", params("sceneIndex", 0)));
        assertRpcError("invalid_params", f.start("scenes.list", params("sceneOffset", -1)));
        assertRpcError("invalid_params", f.start("scenes.launch", params("target", params("kind", "scene"))));
        assertEquals(0, f.sceneAppends + f.sceneRenames + f.sceneDeletes);
    }

    @Test
    void anUnobservedSceneCountIsNotReadyForEveryMethod() {
        LauncherFake f = new LauncherFake(true, false, false, false, false);
        assertRpcError("not_ready", f.startAndDrain("scenes.list", new JsonObject()));
        assertRpcError("not_ready", f.startAndDrain("scenes.insert", new JsonObject()));
        assertRpcError("not_ready", f.startAndDrain("scenes.set", params("target", f.scene(0), "name", "X")));
        for (String method : List.of("scenes.launch", "scenes.duplicate", "scenes.delete"))
            assertRpcError("not_ready", f.startAndDrain(method, params("target", f.scene(0))));
        assertEquals(0, f.sceneAppends + f.sceneRenames + f.sceneLaunches + f.sceneDuplicates + f.sceneDeletes);
    }

    // ---- list ----

    @Test
    void listReadsRefsAndClipCountsWithPositionalLabelsAsUnnamed() {
        LauncherFake f = world(4, "Intro", "", "Scene 3", "Scene 3");
        f.trackCount = 2;
        f.slots[0].content = true;
        f.extraContent[0] = true;
        f.slots[2].content = true;
        JsonObject out = f.call("scenes.list", new JsonObject());
        assertEquals(4, out.get("sceneCount").getAsInt());
        assertEquals("scene_bank", out.get("scope").getAsString());
        assertTrue(out.get("complete").getAsBoolean());
        assertEquals(4, out.getAsJsonArray("scenes").size());
        assertEquals(f.scene(0), row(out, 0).getAsJsonObject("ref"));
        assertEquals("Intro", row(out, 0).getAsJsonObject("ref").get("name").getAsString());
        assertEquals(2, row(out, 0).get("clipCount").getAsInt());
        assertTrue(row(out, 1).getAsJsonObject("ref").get("name").isJsonNull());
        assertEquals(0, row(out, 1).get("clipCount").getAsInt());
        assertTrue(
                row(out, 2).getAsJsonObject("ref").get("name").isJsonNull(),
                "the label Bitwig shows for scene 3 is not a name");
        assertEquals(
                "Scene 3",
                row(out, 3).getAsJsonObject("ref").get("name").getAsString(),
                "the same label on another scene is a real name");
        assertEquals(1, row(out, 2).get("clipCount").getAsInt());
        assertFalse(out.has("next"));
    }

    @Test
    void insertingFortyScenesThenListingReadsThemAllAcrossWindows() {
        LauncherFake f = world(0);
        f.trackCount = 2;
        JsonObject inserted = f.call("scenes.insert", params("count", 40));
        assertEquals(40, inserted.get("sceneCount").getAsInt());
        f.slots[20].content = true;
        f.extraContent[20] = true;
        f.slots[39].content = true;
        f.sceneNames[35] = "Late";
        JsonObject out = f.call("scenes.list", new JsonObject());
        assertEquals(40, out.getAsJsonArray("scenes").size());
        assertTrue(out.get("complete").getAsBoolean());
        assertEquals(40, out.get("sceneCount").getAsInt());
        for (int i = 0; i < 40; i++)
            assertEquals(i, row(out, i).getAsJsonObject("ref").get("index").getAsInt());
        assertEquals(2, row(out, 20).get("clipCount").getAsInt());
        assertEquals(1, row(out, 39).get("clipCount").getAsInt());
        assertEquals("Late", row(out, 35).getAsJsonObject("ref").get("name").getAsString());
        assertTrue(f.scrolls >= 2, "the listing moved the window");
    }

    @Test
    void aShortDeadlineStopsAtAWindowAndTheNextOffsetResumes() {
        LauncherFake f = world(40);
        f.slots[33].content = true;
        JsonObject page = f.startAndDrain(new HostOperation(f.tasks.after(300)), "scenes.list", new JsonObject())
                .result
                .join();
        assertFalse(page.get("complete").getAsBoolean());
        assertEquals(16, page.getAsJsonArray("scenes").size());
        assertEquals(16, page.getAsJsonObject("next").get("sceneOffset").getAsInt());
        assertEquals(0, page.getAsJsonObject("window").get("offset").getAsInt());

        JsonObject second = f.startAndDrain(
                        new HostOperation(f.tasks.after(300)), "scenes.list", params("sceneOffset", 16))
                .result
                .join();
        assertEquals(16, second.getAsJsonArray("scenes").size());
        assertEquals(16, row(second, 0).getAsJsonObject("ref").get("index").getAsInt());
        assertEquals(32, second.getAsJsonObject("next").get("sceneOffset").getAsInt());

        JsonObject third = f.startAndDrain(
                        new HostOperation(f.tasks.after(300)), "scenes.list", params("sceneOffset", 32))
                .result
                .join();
        assertEquals(8, third.getAsJsonArray("scenes").size());
        assertTrue(third.get("complete").getAsBoolean());
        assertFalse(third.has("next"));
        assertEquals(32, row(third, 0).getAsJsonObject("ref").get("index").getAsInt());
        assertEquals(1, row(third, 1).get("clipCount").getAsInt());
        assertEquals(24, f.bankOffset, "the last page sat in the window that fits the 40 scenes");
    }

    @Test
    void aListOffsetPastTheCountIsOutOfBounds() {
        LauncherFake f = world(3);
        assertRpcError("out_of_bounds", f.startAndDrain("scenes.list", params("sceneOffset", 3)));
        assertEquals(0, f.scrolls);
    }

    // ---- insert ----

    @Test
    void scenesAreAppendedAtTheEndAndCountIsObserved() {
        LauncherFake f = world(3);
        f.slots[0].content = true;
        JsonObject out = f.call("scenes.insert", new JsonObject());
        assertEquals("applied", outcome(out));
        assertEquals("scene_count", out.get("verification").getAsString());
        assertEquals(3, out.get("sceneCountBefore").getAsInt());
        assertEquals(4, out.get("sceneCount").getAsInt());
        assertEquals(1, f.sceneAppends);
        assertEquals(f.scene(3), out.getAsJsonObject("ref"));
        assertTrue(out.getAsJsonObject("ref").get("name").isJsonNull());
        assertEquals(1, out.getAsJsonArray("refs").size());
        assertTrue(f.slots[0].content, "existing slots stay where they are");

        out = f.call("scenes.insert", params("count", 2));
        assertEquals(6, out.get("sceneCount").getAsInt());
        assertEquals(3, f.sceneAppends);
        assertEquals(4, refIndex(out));
        assertEquals(f.scene(4), out.getAsJsonArray("refs").get(0));
        assertEquals(f.scene(5), out.getAsJsonArray("refs").get(1));
        assertEquals(
                6,
                f.call("scenes.list", new JsonObject()).getAsJsonArray("scenes").size());
    }

    @Test
    void thereIsNoSixteenSceneLimit() {
        LauncherFake f = world(16);
        JsonObject out = f.call("scenes.insert", params("count", 30));
        assertEquals(46, out.get("sceneCount").getAsInt());
        assertEquals(16, refIndex(out));
        assertEquals(30, out.getAsJsonArray("refs").size());
        assertEquals(30, f.sceneAppends);
    }

    @Test
    void anUnobservedAppendIsUnknownAfterOneDispatch() {
        LauncherFake f = world(2);
        f.applyScene = false;
        HostOperation op = f.startAndDrain("scenes.insert", new JsonObject());
        assertUnknown("not_observed", op);
        assertTrue(op.dispatched());
        assertEquals(1, f.sceneAppends);
    }

    // ---- rename ----

    @Test
    void renameIsCompareAndSetAndObserved() {
        LauncherFake f = world(2, "Intro", "");
        JsonObject out = f.call("scenes.set", params("target", f.scene(1), "name", "Verse"));
        assertEquals("applied", outcome(out));
        assertEquals("observer_after_write", out.get("verification").getAsString());
        assertEquals("Verse", out.getAsJsonObject("ref").get("name").getAsString());
        assertEquals(f.scene(1), out.getAsJsonObject("ref"));
        assertEquals("Verse", f.sceneNames[1]);
        assertEquals(1, f.sceneRenames);

        out = f.call("scenes.set", params("target", f.scene(1), "name", "Verse"));
        assertEquals("already_matched", outcome(out));
        assertEquals(1, f.sceneRenames, "the same name is not written again");
    }

    @Test
    void aPositionalLabelIsRenamedLikeAnUnnamedScene() {
        LauncherFake f = world(3, "Intro", "Scene 2", "");
        assertTrue(f.scene(1).get("name").isJsonNull(), "the label is not a name");
        JsonObject out = f.call("scenes.set", params("target", f.scene(1), "name", "Bridge"));
        assertEquals("applied", outcome(out));
        assertEquals("Bridge", f.sceneNames[1]);
        assertEquals("Bridge", out.getAsJsonObject("ref").get("name").getAsString());
    }

    @Test
    void anUnobservedRenameStaysUnknown() {
        LauncherFake f = world(2, "Intro", "");
        f.applyRename = false;
        HostOperation op = f.startAndDrain("scenes.set", params("target", f.scene(0), "name", "Start"));
        assertUnknown("not_observed", op);
        assertTrue(op.dispatched());
        assertEquals(1, f.sceneRenames);
    }

    @Test
    void aRenamedSceneStalesTheOldRefWithTheCurrentOne() {
        LauncherFake f = world(3, "Intro", "");
        JsonObject unnamed = f.scene(1);
        f.sceneNames[1] = "Chorus";
        RpcException error =
                assertRpcError("target_changed", f.startAndDrain("scenes.set", params("target", unnamed, "name", "X")));
        assertEquals(f.scene(1), error.current);
        assertEquals("Chorus", error.current.get("name").getAsString());
        assertEquals(0, f.sceneRenames);
        for (String method : List.of("scenes.launch", "scenes.duplicate", "scenes.delete"))
            assertRpcError("target_changed", f.startAndDrain(method, params("target", unnamed)));
        assertEquals(0, f.sceneLaunches + f.sceneDuplicates + f.sceneDeletes);
        assertRpcError(
                "target_changed", f.startAndDrain("scenes.set", params("target", Refs.scene(0, "Other"), "name", "X")));
    }

    @Test
    void aSceneBeyondTheCountIsNotFound() {
        LauncherFake f = world(3);
        JsonObject beyond = Refs.scene(5, null);
        assertRpcError("target_not_found", f.startAndDrain("scenes.set", params("target", beyond, "name", "X")));
        for (String method : List.of("scenes.launch", "scenes.duplicate", "scenes.delete"))
            assertRpcError("target_not_found", f.startAndDrain(method, params("target", beyond)));
        assertEquals(0, f.scrolls);
    }

    // ---- launch ----

    @Test
    void launchObservesClipsOnlyWhenThereAreSome() {
        LauncherFake f = world(2, "Intro", "Empty");
        f.slots[0].content = true;
        JsonObject out = f.call("scenes.launch", params("target", f.scene(0)));
        assertEquals("sent", outcome(out), "a queued launch is sent, not playing");
        assertFalse(out.has("verification"));
        assertEquals(1, out.get("clipCount").getAsInt());
        assertEquals(f.scene(0), out.getAsJsonObject("ref"));

        LauncherFake playing = world(1, "Intro");
        playing.launchPlaying = true;
        playing.slots[0].content = true;
        out = playing.call("scenes.launch", params("target", playing.scene(0)));
        assertEquals("applied", outcome(out));
        assertEquals("clips_playing", out.get("verification").getAsString());

        out = f.call("scenes.launch", params("target", f.scene(1)));
        assertEquals("sent", outcome(out), "a scene without clips is sent, never claimed as playing");
        assertEquals(0, out.get("clipCount").getAsInt());
        assertEquals(2, f.sceneLaunches);
    }

    @Test
    void aLaunchNobodyObservedIsUnknown() {
        LauncherFake f = world(1, "Intro");
        f.slots[0].content = true;
        f.applySceneLaunch = false;
        HostOperation op = f.startAndDrain("scenes.launch", params("target", f.scene(0)));
        assertUnknown("not_observed", op);
        assertEquals(1, f.sceneLaunches);
    }

    @Test
    void aLaunchWhileRecordingIsRefusedBeforeDispatch() {
        LauncherFake f = new LauncherFake(true, true, false, false);
        f.scenes = 2;
        assertRpcError("unsafe_state", f.startAndDrain("scenes.launch", params("target", f.scene(0))));
        assertEquals(0, f.sceneLaunches);
    }

    // ---- duplicate ----

    @Test
    void duplicateVerifiesCountAndNeighbours() {
        LauncherFake f = world(3, "Intro", "Verse", "Outro");
        f.slots[1].content = true;
        JsonObject out = f.call("scenes.duplicate", params("target", f.scene(1)));
        assertEquals("applied", outcome(out));
        assertEquals("scene_count_and_window_names", out.get("verification").getAsString());
        assertEquals(3, out.get("sceneCountBefore").getAsInt());
        assertEquals(4, out.get("sceneCount").getAsInt());
        assertEquals(f.scene(2), out.getAsJsonObject("ref"));
        assertEquals("Verse", out.getAsJsonObject("ref").get("name").getAsString());
        assertEquals(
                List.of("Intro", "Verse", "Verse", "Outro"),
                List.of(f.sceneNames).subList(0, 4));
        assertTrue(f.slots[2].content, "the copy carries the clips");

        LauncherFake tail = world(2, "A", "B");
        out = tail.call("scenes.duplicate", params("target", tail.scene(1)));
        assertEquals("applied", outcome(out), "duplicating the last scene is observed by count");
        assertEquals(2, refIndex(out));
    }

    @Test
    void aDuplicateNobodyObservedIsUnknown() {
        LauncherFake f = world(2, "A", "B");
        f.applyDuplicate = false;
        HostOperation op = f.startAndDrain("scenes.duplicate", params("target", f.scene(0)));
        JsonObject out = assertUnknown("not_observed", op);
        assertEquals(2, out.get("sceneCountBefore").getAsInt());
        assertFalse(out.has("ref"));
        assertEquals(1, f.sceneDuplicates);
    }

    @Test
    void aDuplicateThatCopiesNoClipsIsNotAccepted() {
        LauncherFake f = world(3, "A", "B", "C");
        f.slots[0].content = true;
        f.onContentRead = () -> {
            if (f.sceneDuplicates > 0) f.slots[1].content = false;
        };
        HostOperation op = f.startAndDrain("scenes.duplicate", params("target", f.scene(0)));
        assertUnknown("not_observed", op);
    }

    @Test
    void positionalDefaultNamesShiftWithoutBreakingVerification() {
        LauncherFake f = world(4, "Scene 1", "Scene 2", "Chorus", "Scene 4");
        f.positionalLabels = true;
        JsonObject out = f.call("scenes.duplicate", params("target", f.scene(0)));
        assertEquals("applied", outcome(out));
        assertEquals(1, refIndex(out));
        assertEquals(
                List.of("Scene 1", "Scene 2", "Scene 3", "Chorus", "Scene 5"),
                List.of(f.sceneNames).subList(0, 5),
                "later default labels shifted");
        out = f.call("scenes.delete", params("target", f.scene(1)));
        assertEquals("applied", outcome(out));
        assertEquals(
                List.of("Scene 1", "Scene 2", "Chorus", "Scene 4"),
                List.of(f.sceneNames).subList(0, 4));
    }

    @Test
    void anUnresolvedMutationOrSettingsWriteRefusesDuplicateAndDelete() {
        LauncherFake pinned = world(2, "A", "B");
        pinned.services.holds.place(LauncherStructureLock.MUTATION, "A launcher mutation is pinned.");
        for (String method : List.of("scenes.duplicate", "scenes.delete")) {
            RpcException error =
                    assertRpcError("unresolved", pinned.startAndDrain(method, params("target", pinned.scene(0))));
            assertTrue(error.next.contains("ack " + LauncherStructureLock.MUTATION), error.next);
        }
        LauncherFake settings = world(2, "A", "B");
        settings.services.holds.place(LauncherStructureLock.SETTINGS, "A settings write is pinned.");
        assertRpcError("unresolved", settings.startAndDrain("scenes.duplicate", params("target", settings.scene(0))));
        LauncherFake pending = world(2, "A", "B");
        pending.services.holds.place(LauncherStructureLock.SETTINGS_PENDING, "A settings write is running.");
        assertRpcError("busy", pending.startAndDrain("scenes.delete", params("target", pending.scene(0))));
        assertEquals(0, pinned.sceneDuplicates + pinned.sceneDeletes + settings.sceneDuplicates + pending.sceneDeletes);
        assertEquals("applied", outcome(pinned.call("scenes.set", params("target", pinned.scene(0), "name", "Still"))));
    }

    // ---- delete ----

    @Test
    void deleteIsGuardedAndVerifiedByNames() {
        LauncherFake f = world(3, "Intro", "Verse", "Outro");
        f.slots[1].content = true;
        JsonObject out = f.call("scenes.delete", params("target", f.scene(1)));
        assertEquals("applied", outcome(out));
        assertEquals("Verse", out.get("deletedName").getAsString());
        assertEquals(1, out.get("clipsDeleted").getAsInt());
        assertEquals(3, out.get("sceneCountBefore").getAsInt());
        assertEquals(2, out.get("sceneCount").getAsInt());
        assertEquals(List.of("Intro", "Outro"), List.of(f.sceneNames).subList(0, 2));
        assertRpcError("target_changed", f.startAndDrain("scenes.delete", params("target", Refs.scene(1, "Verse"))));
    }

    @Test
    void deleteRefusesAPlayingClipAPlayingTransportAndTheLastScene() {
        LauncherFake playing = world(2, "A", "B");
        playing.slots[0].content = true;
        playing.slots[0].playing = true;
        assertRpcError("unsafe_state", playing.startAndDrain("scenes.delete", params("target", playing.scene(0))));
        LauncherFake queued = world(2, "A", "B");
        queued.slots[0].content = true;
        queued.slots[0].queued = true;
        assertRpcError("unsafe_state", queued.startAndDrain("scenes.delete", params("target", queued.scene(0))));
        assertEquals(0, playing.sceneDeletes + queued.sceneDeletes);

        LauncherFake transport = world(2, "A", "B");
        transport.transportPlaying = true;
        transport.base.transport.child("isPlaying").emit(true);
        assertRpcError("unsafe_state", transport.startAndDrain("scenes.delete", params("target", transport.scene(1))));
        assertEquals(0, transport.sceneDeletes);

        LauncherFake last = world(1, "Only");
        assertRpcError("limit_reached", last.startAndDrain("scenes.delete", params("target", last.scene(0))));
        assertEquals(0, last.sceneDeletes);
    }

    @Test
    void aDeleteNobodyObservedIsUnknown() {
        LauncherFake f = world(2, "A", "B");
        f.applyDelete = false;
        HostOperation op = f.startAndDrain("scenes.delete", params("target", f.scene(0)));
        assertUnknown("not_observed", op);
        assertEquals(1, f.sceneDeletes);
    }

    // ---- scenes past the first window ----

    @Test
    void renameInALaterWindowUsesThatWindow() {
        LauncherFake f = world(40);
        f.sceneNames[20] = "Mid";
        f.sceneNames[35] = "Late";
        JsonObject out = f.call("scenes.set", params("target", f.scene(20), "name", "Middle"));
        assertEquals("applied", outcome(out));
        assertEquals("Middle", f.sceneNames[20]);
        assertEquals(20, refIndex(out));
        assertEquals(16, f.bankOffset);

        out = f.call("scenes.set", params("target", f.scene(35), "name", "Finale"));
        assertEquals("applied", outcome(out));
        assertEquals("Finale", f.sceneNames[35]);
        assertEquals(24, f.bankOffset, "the last window is the one that fits the 40 scenes");
    }

    @Test
    void aStaleRenameInALaterWindowReportsTheCurrentRef() {
        LauncherFake f = world(40);
        JsonObject unnamed = f.scene(33);
        f.sceneNames[33] = "Drop";
        RpcException error =
                assertRpcError("target_changed", f.startAndDrain("scenes.set", params("target", unnamed, "name", "X")));
        assertEquals(f.scene(33), error.current);
        assertEquals("", f.sceneNames[34]);
    }

    @Test
    void launchInALaterWindowObservesItsClips() {
        LauncherFake f = world(40);
        f.launchPlaying = true;
        f.slots[33].content = true;
        f.trackCount = 2;
        f.extraContent[33] = true;
        JsonObject out = f.call("scenes.launch", params("target", f.scene(33)));
        assertEquals("applied", outcome(out));
        assertEquals(2, out.get("clipCount").getAsInt());
        assertTrue(f.slots[33].playing);
        assertEquals(24, f.bankOffset);
    }

    @Test
    void duplicateInALaterWindowReturnsTheCopysRef() {
        LauncherFake f = world(40);
        f.sceneNames[20] = "Mid";
        f.sceneNames[21] = "After";
        JsonObject out = f.call("scenes.duplicate", params("target", f.scene(20)));
        assertEquals("applied", outcome(out));
        assertEquals(41, out.get("sceneCount").getAsInt());
        assertEquals(21, refIndex(out));
        assertEquals("Mid", f.sceneNames[21]);
        assertEquals("After", f.sceneNames[22]);
        assertEquals(16, f.bankOffset);
    }

    @Test
    void aDuplicateAtAWindowEdgeMovesOnToReturnTheCopysRef() {
        LauncherFake f = world(40);
        f.sceneNames[31] = "Edge";
        f.sceneNames[32] = "Next";
        JsonObject out = f.call("scenes.duplicate", params("target", f.scene(31)));
        assertEquals("applied", outcome(out));
        assertEquals(32, refIndex(out));
        assertEquals("Edge", out.getAsJsonObject("ref").get("name").getAsString());
        assertEquals("Next", f.sceneNames[33]);
        assertEquals(f.scene(32), out.getAsJsonObject("ref"));
        assertTrue(f.bankOffset >= 25, "the window moved to show the copy");
    }

    @Test
    void aDuplicateNeedsTheWindowAloneAndIsBusyWhileAnotherHoldsIt() {
        LauncherFake f = world(40);
        f.sceneNames[31] = "Edge";
        holdWindow(f, 16);
        assertRpcError("busy", f.startAndDrain("scenes.duplicate", params("target", f.scene(31))));
        assertEquals(0, f.sceneDuplicates, "nothing was duplicated");
    }

    @Test
    void aDuplicateWhoseWindowDoesNotSettleAfterTheWriteStillCompletesApplied() {
        LauncherFake f = world(40);
        f.sceneNames[31] = "Edge";
        HostOperation op = f.start("scenes.duplicate", params("target", f.scene(31)));
        f.scrollStuck = true;
        f.drain(op);
        JsonObject out = op.result.join();
        assertEquals("applied", outcome(out));
        assertFalse(out.has("ref"), "the copy lies outside the window that could not be moved");
    }

    @Test
    void deleteInALaterWindowShiftsTheNamesAfterIt() {
        LauncherFake f = world(40);
        f.sceneNames[20] = "X";
        f.sceneNames[21] = "Y";
        f.sceneNames[22] = "Z";
        f.slots[20].content = true;
        JsonObject out = f.call("scenes.delete", params("target", f.scene(20)));
        assertEquals("applied", outcome(out));
        assertEquals("X", out.get("deletedName").getAsString());
        assertEquals(1, out.get("clipsDeleted").getAsInt());
        assertEquals(39, out.get("sceneCount").getAsInt());
        assertEquals("Y", f.sceneNames[20]);
        assertEquals("Z", f.sceneNames[21]);
        assertEquals(16, f.bankOffset);
    }

    @Test
    void aWriteFacingAnotherHeldWindowIsBusy() {
        LauncherFake f = world(40);
        holdWindow(f, 0);
        assertRpcError("busy", f.startAndDrain("scenes.set", params("target", f.scene(33), "name", "X")));
        assertEquals(0, f.sceneRenames);
    }

    @Test
    void aListWaitsForAHeldWindowAndFailsBusyAtItsDeadline() {
        LauncherFake f = world(40);
        holdWindow(f, 16);
        assertRpcError("busy", f.startAndDrain("scenes.list", new JsonObject()));
    }
}
