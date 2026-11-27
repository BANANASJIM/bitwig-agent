package dev.bitwigagent.launcher;

import static dev.bitwigagent.RpcAssert.*;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import dev.bitwigagent.core.HostOperation;
import dev.bitwigagent.core.RpcException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Consumer tests against API-shaped in-memory host proxies; never native-host evidence. */
public final class LauncherModuleTest {
    private static JsonObject listParams(LauncherFake f) {
        JsonObject p = new JsonObject();
        p.add("target", f.track());
        return p;
    }

    private static JsonObject listFrom(LauncherFake f, int sceneOffset) {
        JsonObject p = listParams(f);
        p.addProperty("sceneOffset", sceneOffset);
        return p;
    }

    private static JsonObject stopParams(LauncherFake f) {
        JsonObject p = new JsonObject();
        p.add("target", f.track());
        return p;
    }

    private static JsonObject clipParams(LauncherFake f, int scene) {
        JsonObject p = new JsonObject();
        p.add("target", f.clip(scene));
        return p;
    }

    private static void occupy(LauncherFake f, int scene, String name) {
        f.slots[scene].content = true;
        f.slots[scene].name = name;
    }

    private static JsonObject insert(LauncherFake f, int scene) {
        JsonObject p = new JsonObject();
        p.add("to", f.slot(scene));
        JsonObject loop = new JsonObject();
        loop.addProperty("lengthBeats", 4);
        p.add("loop", loop);
        p.addProperty("name", "Fixture");
        return p;
    }

    private static JsonObject importFile(LauncherFake f, int scene, Path file) {
        JsonObject p = new JsonObject();
        p.add("to", f.slot(scene));
        p.addProperty("path", file.toString());
        return p;
    }

    private static JsonObject row(JsonObject list, int index) {
        return list.getAsJsonArray("slots").get(index).getAsJsonObject();
    }

    /** A page that stops after its first window: the read's deadline is already within the observe margin. */
    private static HostOperation shortRead(LauncherFake f) {
        return new HostOperation(f.tasks.after(800));
    }

    // ---- launcher.list ----

    @Test
    void rowsCarryTheSlotRefTheClipRefAndTheCursor() {
        LauncherFake f = new LauncherFake();
        f.call("clips.insert", insert(f, 0));
        occupy(f, 2, "Other");
        f.selected = 0;
        JsonObject list = f.call("launcher.list", listParams(f));
        assertEquals(f.track(), list.get("ref"));
        assertEquals(16, list.getAsJsonArray("slots").size());
        assertTrue(list.get("complete").getAsBoolean());
        assertFalse(list.has("next"));
        assertEquals(f.slot(0), row(list, 0).get("ref"));
        assertEquals(f.clip(0), row(list, 0).get("clip"));
        assertTrue(row(list, 0).get("selected").getAsBoolean());
        assertTrue(row(list, 0).get("hasContent").getAsBoolean());
        assertFalse(row(list, 1).get("selected").getAsBoolean());
        assertFalse(row(list, 1).has("clip"), "an empty slot has no clip ref");
        assertEquals(f.clip(2), row(list, 2).get("clip"));
        assertEquals(f.slot(15), row(list, 15).get("ref"));
        f.selected = 2;
        list = f.call("launcher.list", listParams(f));
        assertTrue(row(list, 2).get("selected").getAsBoolean(), "the cursor follows the UI selection");
        assertFalse(row(list, 0).get("selected").getAsBoolean());
        f.selected = 1;
        list = f.call("launcher.list", listParams(f));
        for (int i = 0; i < 16; i++)
            assertFalse(row(list, i).get("selected").getAsBoolean(), "an empty selected slot is not a cursor clip");
        assertEquals(0, f.writes, "reads never write");
        assertEquals(1, f.creates);
    }

    @Test
    void rowsFollowTheSceneCountAndCarryTheSceneNames() {
        LauncherFake f = new LauncherFake();
        f.scenes = 1;
        assertEquals(
                1,
                f.call("launcher.list", listParams(f)).getAsJsonArray("slots").size());
        f.scenes = 3;
        f.sceneNames[1] = "Drop";
        f.sceneNames[2] = "Scene 3";
        JsonObject list = f.call("launcher.list", listParams(f));
        assertEquals(3, list.getAsJsonArray("slots").size());
        assertEquals(
                "Drop",
                row(list, 1)
                        .getAsJsonObject("ref")
                        .getAsJsonObject("scene")
                        .get("name")
                        .getAsString());
        assertTrue(
                row(list, 2)
                        .getAsJsonObject("ref")
                        .getAsJsonObject("scene")
                        .get("name")
                        .isJsonNull(),
                "the positional label is no name");
    }

    @Test
    void aLongListIsCompleteWhenTheDeadlineAllows() {
        LauncherFake f = new LauncherFake();
        f.scenes = 40;
        f.sceneNames[33] = "Drop";
        occupy(f, 33, "Hook");
        JsonObject list = f.call("launcher.list", listParams(f));
        assertEquals(40, list.getAsJsonArray("slots").size());
        assertTrue(list.get("complete").getAsBoolean());
        assertFalse(list.has("next"));
        assertEquals(f.slot(39), row(list, 39).get("ref"));
        assertEquals(f.clip(33), row(list, 33).get("clip"));
        assertEquals(0, list.getAsJsonObject("window").get("offset").getAsInt());
        assertEquals(40, list.getAsJsonObject("window").get("total").getAsInt());
    }

    @Test
    void anInterruptedListResumesWithSceneOffset() {
        LauncherFake f = new LauncherFake();
        f.scenes = 40;
        occupy(f, 20, "Mid");
        occupy(f, 35, "Late");
        HostOperation first = shortRead(f);
        f.start(first, "launcher.list", listParams(f));
        f.drain(first);
        JsonObject page = first.result.join();
        assertFalse(page.get("complete").getAsBoolean());
        assertEquals(16, page.getAsJsonArray("slots").size());
        assertEquals(16, page.getAsJsonObject("next").get("sceneOffset").getAsInt());
        assertEquals(f.slot(15), row(page, 15).get("ref"));

        HostOperation second = shortRead(f);
        f.start(second, "launcher.list", listFrom(f, 16));
        f.drain(second);
        page = second.result.join();
        assertEquals(16, page.getAsJsonArray("slots").size());
        assertEquals(f.slot(16), row(page, 0).get("ref"));
        assertEquals(f.clip(20), row(page, 4).get("clip"));
        assertEquals(32, page.getAsJsonObject("next").get("sceneOffset").getAsInt());

        HostOperation third = shortRead(f);
        f.start(third, "launcher.list", listFrom(f, 32));
        f.drain(third);
        page = third.result.join();
        assertTrue(page.get("complete").getAsBoolean());
        assertEquals(8, page.getAsJsonArray("slots").size(), "the last page holds scenes 32..39");
        assertEquals(f.slot(32), row(page, 0).get("ref"));
        assertEquals(f.clip(35), row(page, 3).get("clip"));
        assertFalse(page.has("next"));
    }

    @Test
    void aSceneOffsetBeyondTheCountIsOutOfBounds() {
        LauncherFake f = new LauncherFake();
        f.scenes = 40;
        assertRpcError("out_of_bounds", f.startAndDrain("launcher.list", listFrom(f, 40)));
        assertRpcError("out_of_bounds", f.startAndDrain("launcher.list", listFrom(f, 60)));
        assertEquals(
                40,
                f.call("launcher.list", listFrom(f, 0)).getAsJsonArray("slots").size(),
                "offset 0 reads everything");
    }

    @Test
    void aTrackOutsideTheWindowIsUnavailable() {
        LauncherFake f = new LauncherFake();
        f.base.bank.live("itemCount", () -> 100000);
        JsonObject p = new JsonObject();
        p.add(
                "target",
                com.google.gson.JsonParser.parseString(
                        "{\"kind\":\"track\",\"id\":\"33333333-3333-3333-3333-333333333333\",\"name\":\"Far\"}"));
        assertRpcError("outside_window", f.startAndDrain("launcher.list", p));
    }

    @Test
    void aTargetRefIsLocatedByIdNotByName() {
        LauncherFake f = new LauncherFake();
        JsonObject old = f.track();
        f.trackName = "Renamed";
        JsonObject p = new JsonObject();
        p.add("target", old);
        JsonObject list = f.call("launcher.list", p);
        assertEquals(f.track(), list.get("ref"), "the result carries the current ref");
    }

    @Test
    void aReadWaitsForTheSceneWindowLease(@TempDir Path dir) throws Exception {
        Path file = Files.createFile(dir.resolve("clip.arbitrary"));
        LauncherFake f = new LauncherFake();
        HostOperation importing = f.start("clips.import", importFile(f, 0, file));
        assertFalse(importing.result.isDone());
        HostOperation list = f.start("launcher.list", listParams(f));
        assertFalse(list.result.isDone(), "the read waits while another operation holds the window");
        f.observeContent(0, true);
        f.drain(importing);
        f.drain(list);
        JsonObject out = list.result.join();
        assertEquals(16, out.getAsJsonArray("slots").size());
        assertTrue(row(out, 0).get("hasContent").getAsBoolean());
    }

    @Test
    void aReadFailsBusyWhenTheWindowStaysHeldUntilItsDeadline(@TempDir Path dir) throws Exception {
        Path file = Files.createFile(dir.resolve("clip.arbitrary"));
        LauncherFake f = new LauncherFake();
        f.scenes = 40;
        HostOperation importing = f.start("clips.import", importFile(f, 3, file));
        HostOperation list = new HostOperation(f.tasks.after(300));
        f.start(list, "launcher.list", listFrom(f, 33));
        f.drain(list);
        assertRpcError("busy", list);
        assertFalse(list.dispatched());
        assertFalse(importing.result.isDone(), "the holder is not disturbed");
        f.observeContent(3, true);
        f.drain(importing);
        assertEquals("applied", importing.result.join().get("outcome").getAsString());
    }

    @Test
    void aWriteFacingAnotherWindowIsBusyWhileARead() {
        LauncherFake f = new LauncherFake();
        f.scenes = 40;
        HostOperation list = f.start("launcher.list", listParams(f));
        assertFalse(list.result.isDone());
        HostOperation write = f.start("clips.insert", insert(f, 33));
        assertRpcError("busy", write);
        f.drain(list);
        assertEquals(40, list.result.join().getAsJsonArray("slots").size());
        assertEquals(0, f.creates);
    }

    // ---- launcher.stop ----

    @Test
    void stopStatesAreDistinguished() {
        LauncherFake f = new LauncherFake();
        occupy(f, 0, "Riff");
        f.launchPlaying = true;
        JsonObject launched = f.call("clips.launch", clipParams(f, 0));
        assertEquals("applied", launched.get("outcome").getAsString());
        JsonObject out = f.call("launcher.stop", stopParams(f));
        assertEquals("stopped", out.get("playbackState").getAsString());
        assertEquals("applied", out.get("outcome").getAsString());
        assertEquals(f.track(), out.get("ref"));
        LauncherFake g = new LauncherFake();
        occupy(g, 0, "Riff");
        g.slots[0].playing = true;
        g.stopped = false;
        g.queueStop = true;
        out = g.call("launcher.stop", stopParams(g));
        assertEquals("queued", out.get("playbackState").getAsString());
        assertEquals("sent", out.get("outcome").getAsString(), "a queued stop does not claim completion");
        assertTrue(g.slots[0].playing && !g.stopped, "a queued stop may leave the clip playing");
    }

    @Test
    void observedPlaybackStateReachesTheRenderWarnings() {
        LauncherFake f = new LauncherFake();
        occupy(f, 0, "Riff");
        f.call("launcher.list", listParams(f));
        LauncherState state = f.services.launcherState();
        assertEquals(Boolean.FALSE, state.playing(0), "nothing plays");
        assertEquals(Boolean.TRUE, state.stopped(0), "a track nothing has started is reported stopped");
        f.slots[0].playing = true;
        f.stopped = false;
        f.call("launcher.list", listParams(f));
        assertEquals(Boolean.TRUE, state.playing(0), "a playing slot overrides the arranger");
        assertEquals(Boolean.FALSE, state.stopped(0));
    }

    @Test
    void anUnobservedStopRemainsUnknown() {
        LauncherFake f = new LauncherFake();
        occupy(f, 0, "Riff");
        f.emitPlaybackState = false;
        f.slots[0].playing = true;
        f.stopped = false;
        JsonObject out = f.call("launcher.stop", stopParams(f));
        assertEquals("unknown", out.get("outcome").getAsString());
        assertEquals("not_observed", out.get("reason").getAsString());
        assertEquals("not_observed", out.get("playbackState").getAsString());
        assertEquals(1, f.playbackDispatches, "an observation never redispatches the stop");
    }

    @Test
    void stopRefusesAStaleTrackRef() {
        LauncherFake f = new LauncherFake();
        JsonObject old = f.track();
        f.trackName = "Renamed";
        JsonObject p = new JsonObject();
        p.add("target", old);
        RpcException error = assertRpcError("target_changed", f.startAndDrain("launcher.stop", p));
        assertEquals(f.track(), error.current);
        assertEquals(0, f.playbackDispatches);
    }
}
