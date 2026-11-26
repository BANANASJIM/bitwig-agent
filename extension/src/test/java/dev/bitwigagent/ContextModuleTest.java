package dev.bitwigagent;

import static dev.bitwigagent.RpcAssert.*;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import org.junit.jupiter.api.Test;

/** context.get over the simulated launcher world; callbacks and queued host tasks only. */
public final class ContextModuleTest {
    private static LauncherFake world(int scenes) {
        return register(new LauncherFake(), scenes);
    }

    private static LauncherFake register(LauncherFake f, int scenes) {
        f.scenes = scenes;
        ContextModule context = f.services.context(f.services.mix(), f.services.transportModule());
        for (String method : context.methods()) f.modules.put(method, context);
        return f;
    }

    private static JsonObject get(LauncherFake f) {
        return f.call("context.get", new JsonObject());
    }

    private static void occupy(LauncherFake f, int scene, String clipName) {
        f.slots[scene].content = true;
        f.slots[scene].name = clipName;
    }

    private static JsonArray rows(JsonObject out) {
        return out.getAsJsonArray("tracks").get(0).getAsJsonObject().getAsJsonArray("launcherSlots");
    }

    private static HostOperation holdWindow(LauncherFake f, int offset) {
        HostOperation holder = new HostOperation(f.tasks.after(60_000));
        f.services.launcherTargets().lease.enter(holder, offset, false, false, () -> {});
        for (int i = 0; i < 20 && !f.tasks.isEmpty(); i++) f.next();
        assertEquals(offset, f.bankOffset);
        return holder;
    }

    @Test
    void launcherSlotsListOccupiedSlotsOfTheFirstSixteenScenes() {
        LauncherFake f = world(16);
        f.sceneNames[2] = "Drop";
        occupy(f, 2, "Hook");
        occupy(f, 5, "Bass");
        f.slots[5].playing = true;
        JsonObject out = get(f);
        assertEquals(16, out.get("sceneCount").getAsInt());
        assertTrue(out.get("launcherSlotsComplete").getAsBoolean());
        JsonArray slots = rows(out);
        assertEquals(2, slots.size());
        assertEquals(f.clip(2), slots.get(0).getAsJsonObject().getAsJsonObject("ref"));
        assertFalse(slots.get(0).getAsJsonObject().get("playing").getAsBoolean());
        assertEquals(f.clip(5), slots.get(1).getAsJsonObject().getAsJsonObject("ref"));
        assertTrue(slots.get(1).getAsJsonObject().get("playing").getAsBoolean());
        assertTrue(out.get("cursorClip").isJsonNull());
    }

    @Test
    void moreThanSixteenScenesMakeTheSlotListIncomplete() {
        LauncherFake f = world(40);
        occupy(f, 1, "Early");
        occupy(f, 20, "Late");
        JsonObject out = get(f);
        assertEquals(40, out.get("sceneCount").getAsInt());
        assertFalse(out.get("launcherSlotsComplete").getAsBoolean());
        assertEquals(1, rows(out).size(), "only scenes 0-15 are listed");
        assertEquals(f.clip(1), rows(out).get(0).getAsJsonObject().getAsJsonObject("ref"));
    }

    @Test
    void theSelectedTrackIsATrackRef() {
        LauncherFake f = world(16);
        assertTrue(get(f).get("selectedTrack").isJsonNull());
        f.base.tracks[0].child("selected").emit(true);
        assertEquals(f.track(), get(f).getAsJsonObject("selectedTrack"));
    }

    @Test
    void theCursorClipInTheFirstWindowIsFoundWithoutScrolling() {
        LauncherFake f = world(40);
        f.sceneNames[3] = "Intro";
        occupy(f, 3, "Hook");
        f.selected = 3;
        JsonObject out = get(f);
        assertEquals(f.clip(3), out.getAsJsonObject("cursorClip"));
        assertEquals(0, f.scrolls);
    }

    @Test
    void aCursorClipBeyondTheFirstWindowIsFoundThroughTheCursorSlotsOwnScene() {
        LauncherFake f = world(40);
        f.sceneNames[33] = "Drop";
        occupy(f, 33, "Hook");
        f.selected = 33;
        JsonObject out = get(f);
        assertEquals(f.clip(33), out.getAsJsonObject("cursorClip"));
        assertEquals(
                "Drop",
                out.getAsJsonObject("cursorClip")
                        .getAsJsonObject("slot")
                        .getAsJsonObject("scene")
                        .get("name")
                        .getAsString());
        assertEquals(1, f.scrolls, "the hinted window is the only one visited");
        f.flush();
        assertEquals(0, f.bankOffset, "the window rests at scene 0");
    }

    @Test
    void anUnusableHintFallsBackToScanningTheWindows() {
        LauncherFake f = world(40);
        f.cursorSceneUnknown = true;
        occupy(f, 20, "Mid");
        f.selected = 20;
        assertEquals(f.clip(20), get(f).getAsJsonObject("cursorClip"));
        assertEquals(1, f.scrolls, "the first window after the first one holds it");
        f.flush();

        LauncherFake late = world(40);
        late.cursorSceneUnknown = true;
        occupy(late, 35, "End");
        late.selected = 35;
        assertEquals(late.clip(35), get(late).getAsJsonObject("cursorClip"));
        assertEquals(2, late.scrolls, "every window is scanned in turn");
        late.flush();
        assertEquals(0, late.bankOffset);
    }

    @Test
    void aHintPastTheSceneCountIsIgnored() {
        LauncherFake f = world(40);
        occupy(f, 20, "Mid");
        occupy(f, 50, "Gone");
        f.selected = 50;
        assertTrue(get(f).get("cursorClip").isJsonNull(), "no scene of the project shows that clip");
    }

    @Test
    void noSelectedClipGivesANullCursorClip() {
        LauncherFake f = world(16);
        assertTrue(get(f).get("cursorClip").isJsonNull());
        LauncherFake many = world(40);
        occupy(many, 20, "Mid");
        assertTrue(get(many).get("cursorClip").isJsonNull(), "the selected slot 0 holds no clip");
        assertEquals(2, many.scrolls, "every other window was looked at");
    }

    @Test
    void twoCallsGiveTheSameResultAndTheWindowRests() {
        LauncherFake f = world(40);
        occupy(f, 3, "Head");
        occupy(f, 33, "Hook");
        f.selected = 33;
        JsonObject first = get(f);
        f.flush();
        assertEquals(0, f.bankOffset);
        JsonObject second = get(f);
        f.flush();
        first.remove("projectChangedMsAgo");
        second.remove("projectChangedMsAgo");
        assertEquals(first, second);
        assertEquals(0, f.bankOffset);
        assertFalse(f.services.launcherTargets().lease.held());
    }

    @Test
    void aReadWaitsForAHeldOtherWindowAndFailsBusyAtItsDeadline() {
        LauncherFake f = world(40);
        holdWindow(f, 16);
        HostOperation op = f.startAndDrain("context.get", new JsonObject());
        assertRpcError("busy", op);
        assertEquals(16, f.bankOffset);
    }

    @Test
    void aReadWaitsForAHeldOtherWindowAndThenAnswers() {
        LauncherFake f = world(40);
        occupy(f, 1, "Early");
        HostOperation holder = holdWindow(f, 16);
        HostOperation op = f.start("context.get", new JsonObject());
        for (int i = 0; i < 5; i++) f.next();
        assertFalse(op.result.isDone());
        holder.result.complete(new JsonObject());
        f.drain(op);
        assertEquals(f.clip(1), rows(op.result.join()).get(0).getAsJsonObject().getAsJsonObject("ref"));
    }

    @Test
    void theWindowScanNeedsTheWindowAloneAndFailsBusyAtTheDeadline() {
        LauncherFake f = world(40);
        f.cursorSceneUnknown = true;
        occupy(f, 33, "Hook");
        f.selected = 33;
        holdWindow(f, 0);
        assertRpcError("busy", f.startAndDrain("context.get", new JsonObject()));
        assertEquals(0, f.bankOffset);
    }

    @Test
    void theWindowScanWaitsForAnotherHolderToLeave() {
        LauncherFake f = world(40);
        f.cursorSceneUnknown = true;
        occupy(f, 33, "Hook");
        f.selected = 33;
        HostOperation holder = holdWindow(f, 0);
        HostOperation op = f.start("context.get", new JsonObject());
        for (int i = 0; i < 5; i++) f.next();
        assertFalse(op.result.isDone());
        holder.result.complete(new JsonObject());
        f.drain(op);
        assertEquals(f.clip(33), op.result.join().getAsJsonObject("cursorClip"));
    }

    @Test
    void anUnobservedBankGivesNullsInsteadOfAnError() {
        LauncherFake f = register(new LauncherFake(true, false, false, false, false), 16);
        JsonObject out = get(f);
        assertTrue(out.get("sceneCount").isJsonNull());
        assertTrue(out.get("tracks").isJsonNull());
        assertTrue(out.get("selectedTrack").isJsonNull());
        assertTrue(out.get("cursorClip").isJsonNull());
        assertEquals(0, f.scrolls);
    }
}
