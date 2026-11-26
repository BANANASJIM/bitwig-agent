package dev.bitwigagent;

import static dev.bitwigagent.RpcAssert.*;
import static org.junit.jupiter.api.Assertions.*;

import com.bitwig.extension.controller.api.*;
import com.google.gson.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Simulated scene bank: callbacks and queued host tasks only; says nothing about native scene behavior. */
public final class SceneModuleTest {

    /** A scene list model that republishes names and count the way observers would. */
    private static final class Model {
        final HostFixture f = new HostFixture();
        final List<String> names = new ArrayList<>();
        final SceneModule module;
        boolean applyDuplicate = true, applyDelete = true, positional, playsOnLaunch;

        Model(String... initial) {
            names.addAll(Arrays.asList(initial));
            FakeHost bank = f.bank.child("sceneBank");
            bank.items(Scene.class);
            bank.child("scrollPosition").data = 0;
            bank.child("itemCount").data = names.size();
            for (int i = 0; i < HostLimits.SCENES; i++) {
                final int index = i;
                FakeHost scene = bank.item(i);
                scene.child("exists").data = i < names.size();
                scene.child("name").data = i < names.size() ? names.get(i) : "";
                scene.on("deleteObject", args -> {
                    f.mutation();
                    scene.actions++;
                    if (applyDelete) {
                        names.remove(index);
                        publish(bank);
                    }
                    return null;
                });
                scene.on("duplicateObject", args -> {
                    f.mutation();
                    scene.actions++;
                    if (applyDuplicate) {
                        names.add(index + 1, names.get(index));
                        publish(bank);
                    }
                    return null;
                });
                scene.on("launch", args -> {
                    f.mutation();
                    scene.actions++;
                    for (int t = 0; t < 2; t++) {
                        FakeHost slot = f.slot(t, index);
                        if (slot.child("hasContent").data.equals(true))
                            slot.child(playsOnLaunch ? "isPlaying" : "isPlaybackQueued").data = true;
                    }
                    return null;
                });
            }
            module = f.services.scene();
        }

        void publish(FakeHost bank) {
            // Bitwig labels an unnamed scene by its position, so every default label shifts after an insert or delete.
            if (positional)
                for (int i = 0; i < names.size(); i++)
                    if (names.get(i).startsWith("Scene ")) names.set(i, "Scene " + (i + 1));
            for (int i = 0; i < HostLimits.SCENES; i++)
                bank.item(i).child("name").emit(i < names.size() ? names.get(i) : "");
            bank.child("itemCount").emit(names.size());
        }

        JsonObject call(String method, String json) {
            return f.call(module, method, HostFixture.json(json));
        }

        HostOperation start(String method, String json) {
            HostOperation op = f.submit(module, method, HostFixture.json(json));
            f.next();
            return op;
        }

        HostOperation startShort(String method, String json) {
            HostOperation op = f.submit(module, method, HostFixture.json(json), f.tasks.after(300));
            while (!op.result.isDone()) f.next();
            return op;
        }
    }

    @Test
    void refusesUnknownMethodsLongNamesAndUnreadyBanks() {
        assertRpcError("unknown_method", () -> SceneModule.validateParams("scenes.bogus", new JsonObject()));
        assertInvalidParams(() -> SceneModule.validateParams(
                "scenes.rename",
                HostFixture.json("{\"sceneIndex\":0,\"expectedName\":\"\",\"name\":\"" + "x".repeat(129) + "\"}")));
        Model unready = new Model("A");
        unready.f.bank.child("sceneBank").child("itemCount").emit(-1);
        assertRpcError("not_ready", unready.start("scenes.list", "{}"));
        Model scrolled = new Model("A");
        scrolled.f.bank.child("sceneBank").child("scrollPosition").data = 1;
        assertRpcError("stale_context", scrolled.start("scenes.list", "{}"));
    }

    @Test
    void listReadsNamesAndClipCounts() {
        Model m = new Model("Intro", "", "Outro");
        m.f.slot(0, 0).child("hasContent").data = true;
        m.f.slot(1, 0).child("hasContent").data = true;
        m.f.slot(0, 2).child("hasContent").data = true;
        JsonObject out = m.call("scenes.list", "{}");
        JsonArray rows = out.getAsJsonArray("scenes");
        assertTrue(out.get("sceneCount").getAsInt() == 3 && rows.size() == 3, "three scenes listed");
        assertTrue(
                rows.get(0).getAsJsonObject().get("name").getAsString().equals("Intro")
                        && rows.get(0).getAsJsonObject().get("clipCount").getAsInt() == 2,
                "name and clip count");
        assertTrue(
                rows.get(1).getAsJsonObject().get("name").getAsString().isEmpty()
                        && rows.get(1).getAsJsonObject().get("clipCount").getAsInt() == 0,
                "an unnamed scene reports the empty name");
        assertTrue(out.has("window"), "window reported");
    }

    @Test
    void validationRejectsBeforeScheduling() {
        Model m = new Model("Intro");
        for (String text : List.of(
                "{\"sceneIndex\":16,\"expectedName\":\"\",\"name\":\"X\"}",
                "{\"sceneIndex\":-1,\"expectedName\":\"\",\"name\":\"X\"}",
                "{\"sceneIndex\":0,\"name\":\"X\"}",
                "{\"sceneIndex\":0,\"expectedName\":\"\",\"name\":\"\"}",
                "{\"sceneIndex\":0,\"expectedName\":1,\"name\":\"X\"}",
                "{\"sceneIndex\":0,\"expectedName\":\"\",\"name\":\"X\",\"extra\":1}")) {
            assertRpcError("invalid_params", m.f.submit(m.module, "scenes.rename", HostFixture.json(text)));
            assertTrue(m.f.tasks.isEmpty(), "invalid rename not scheduled: " + text);
        }
        assertRpcError("invalid_params", m.f.submit(m.module, "scenes.delete", HostFixture.json("{\"sceneIndex\":0}")));
        assertRpcError("invalid_params", m.f.submit(m.module, "scenes.list", HostFixture.json("{\"sceneIndex\":0}")));
        SceneModule.validateParams("scenes.launch", HostFixture.json("{\"sceneIndex\":15,\"expectedName\":\"\"}"));
        assertTrue(m.f.tasks.isEmpty(), "nothing scheduled");
    }

    @Test
    void renameIsCompareAndSetAndObserved() {
        Model m = new Model("Intro", "");
        JsonObject out = m.call("scenes.rename", "{\"sceneIndex\":1,\"expectedName\":\"\",\"name\":\"Verse\"}");
        assertTrue(
                out.get("outcome").getAsString().equals("applied")
                        && out.get("verification").getAsString().equals("observer_after_write")
                        && out.get("name").getAsString().equals("Verse"),
                "rename verified by the name observer");
        assertTrue(
                m.f.bank.child("sceneBank").item(1).child("name").writes == 1
                        && m.f.bank.child("sceneBank").item(0).child("name").writes == 0,
                "only the addressed scene written");
        out = m.call("scenes.rename", "{\"sceneIndex\":1,\"expectedName\":\"Verse\",\"name\":\"Verse\"}");
        assertTrue(
                out.get("outcome").getAsString().equals("already_matched")
                        && m.f.bank.child("sceneBank").item(1).child("name").writes == 1,
                "same name is not rewritten");
        HostOperation stale = m.start("scenes.rename", "{\"sceneIndex\":1,\"expectedName\":\"Chorus\",\"name\":\"X\"}");
        assertTrue(!assertRpcError("stale_target", stale).unknown, "wrong expectedName refused");
        assertRpcError(
                "out_of_bounds", m.start("scenes.rename", "{\"sceneIndex\":5,\"expectedName\":\"\",\"name\":\"X\"}"));
        m.f.bank.child("sceneBank").item(0).child("name").notifyWrites = false;
        HostOperation silent =
                m.startShort("scenes.rename", "{\"sceneIndex\":0,\"expectedName\":\"Intro\",\"name\":\"Start\"}");
        JsonObject result = silent.result.join();
        assertTrue(
                result.get("outcome").getAsString().equals("unknown")
                        && result.get("reason").getAsString().equals("not_observed"),
                "unobserved rename stays unknown");
    }

    @Test
    void duplicateVerifiesCountAndNeighbours() {
        Model m = new Model("Intro", "Verse", "Outro");
        JsonObject out = m.call("scenes.duplicate", "{\"sceneIndex\":1,\"expectedName\":\"Verse\"}");
        assertTrue(
                out.get("outcome").getAsString().equals("applied")
                        && out.get("newSceneIndex").getAsInt() == 2
                        && out.get("sceneCountBefore").getAsInt() == 3,
                "duplicate observed after its source");
        assertTrue(m.names.equals(List.of("Intro", "Verse", "Verse", "Outro")), "tail shifted by one");
        assertRpcError("stale_target", m.start("scenes.duplicate", "{\"sceneIndex\":0,\"expectedName\":\"Other\"}"));
        assertTrue(m.f.bank.child("sceneBank").item(0).actions == 0, "stale target dispatches nothing");
        Model silent = new Model("A", "B");
        silent.applyDuplicate = false;
        JsonObject unknown = silent.startShort("scenes.duplicate", "{\"sceneIndex\":0,\"expectedName\":\"A\"}")
                .result
                .join();
        assertTrue(
                unknown.get("outcome").getAsString().equals("unknown")
                        && unknown.get("reason").getAsString().equals("not_observed"),
                "unobserved duplicate stays unknown");
        Model tail = new Model("A", "B");
        out = tail.call("scenes.duplicate", "{\"sceneIndex\":1,\"expectedName\":\"B\"}");
        assertTrue(
                out.get("outcome").getAsString().equals("applied")
                        && out.get("newSceneIndex").getAsInt() == 2,
                "duplicating the last scene is observed by count");
        assertTrue(
                out.get("newSceneName").isJsonNull()
                        || out.get("newSceneName").getAsString().equals("B"),
                "a stale placeholder name is never reported");
        Model pinned = new Model("A", "B");
        pinned.f.services.holds.place(LauncherStructureLock.MUTATION, "A launcher mutation is pinned.");
        RpcException pinnedError = assertRpcError(
                "unresolved", pinned.start("scenes.duplicate", "{\"sceneIndex\":0,\"expectedName\":\"A\"}"));
        assertTrue(
                pinnedError.next.contains("ack " + LauncherStructureLock.MUTATION),
                "the refusal names the hold to ack");
        assertRpcError("unresolved", pinned.start("scenes.delete", "{\"sceneIndex\":0,\"expectedName\":\"A\"}"));
        assertTrue(pinned.f.bank.child("sceneBank").item(0).actions == 0, "nothing dispatched while pinned");
        String[] full = new String[HostLimits.SCENES];
        Arrays.fill(full, "S");
        Model limit = new Model(full);
        assertRpcError("limit_reached", limit.start("scenes.duplicate", "{\"sceneIndex\":0,\"expectedName\":\"S\"}"));
    }

    @Test
    void deleteIsGuardedAndVerifiedByNames() {
        Model m = new Model("Intro", "Verse", "Outro");
        m.f.slot(0, 1).child("hasContent").data = true;
        JsonObject out = m.call("scenes.delete", "{\"sceneIndex\":1,\"expectedName\":\"Verse\"}");
        assertTrue(
                out.get("outcome").getAsString().equals("applied")
                        && out.get("deletedName").getAsString().equals("Verse")
                        && out.get("clipsDeleted").getAsInt() == 1,
                "delete observed with the clip count it removed");
        assertTrue(m.names.equals(List.of("Intro", "Outro")), "later scenes shifted up");
        assertRpcError("stale_target", m.start("scenes.delete", "{\"sceneIndex\":1,\"expectedName\":\"Verse\"}"));

        Model playing = new Model("A", "B");
        playing.f.slot(0, 0).child("hasContent").data = true;
        playing.f.slot(0, 0).child("isPlaying").data = true;
        assertRpcError("unsafe_state", playing.start("scenes.delete", "{\"sceneIndex\":0,\"expectedName\":\"A\"}"));
        assertTrue(playing.f.bank.child("sceneBank").item(0).actions == 0, "nothing dispatched");
        Model transport = new Model("A", "B");
        transport.f.transport.child("isPlaying").emit(true);
        assertRpcError("unsafe_state", transport.start("scenes.delete", "{\"sceneIndex\":1,\"expectedName\":\"B\"}"));
        String[] many = new String[HostLimits.SCENES + 1];
        Arrays.fill(many, "S");
        assertRpcError(
                "outside_window", new Model(many).start("scenes.delete", "{\"sceneIndex\":0,\"expectedName\":\"S\"}"));
        Model last = new Model("Only");
        assertRpcError("limit_reached", last.start("scenes.delete", "{\"sceneIndex\":0,\"expectedName\":\"Only\"}"));
        Model silent = new Model("A", "B");
        silent.applyDelete = false;
        JsonObject unknown = silent.startShort("scenes.delete", "{\"sceneIndex\":0,\"expectedName\":\"A\"}")
                .result
                .join();
        assertTrue(
                unknown.get("outcome").getAsString().equals("unknown")
                        && unknown.get("reason").getAsString().equals("not_observed"),
                "unobserved delete stays unknown");
    }

    @Test
    void positionalDefaultNamesShiftWithoutBreakingVerification() {
        Model m = new Model("Scene 1", "Scene 2", "Chorus", "Scene 4");
        m.positional = true;
        JsonObject out = m.call("scenes.duplicate", "{\"sceneIndex\":0,\"expectedName\":\"Scene 1\"}");
        assertTrue(
                out.get("outcome").getAsString().equals("applied")
                        && out.get("newSceneIndex").getAsInt() == 1,
                "duplicate of a positionally named scene is observed");
        assertTrue(
                m.names.equals(List.of("Scene 1", "Scene 2", "Scene 3", "Chorus", "Scene 5")),
                "later default labels shifted");
        out = m.call("scenes.delete", "{\"sceneIndex\":1,\"expectedName\":\"Scene 2\"}");
        assertTrue(
                out.get("outcome").getAsString().equals("applied")
                        && out.get("verification").getAsString().equals("scene_count_and_names"),
                "delete is observed despite shifted default labels");
        assertTrue(m.names.equals(List.of("Scene 1", "Scene 2", "Chorus", "Scene 4")), "back to the original labels");
        Model renamed = new Model("Scene 1", "Scene 2");
        renamed.positional = true;
        renamed.applyDelete = false;
        assertTrue(
                renamed.startShort("scenes.delete", "{\"sceneIndex\":0,\"expectedName\":\"Scene 1\"}")
                        .result
                        .join()
                        .get("outcome")
                        .getAsString()
                        .equals("unknown"),
                "an unchanged list is still not observed");
    }

    @Test
    void launchObservesClipsOnlyWhenThereAreSome() {
        Model m = new Model("Intro", "Empty");
        m.f.slot(0, 0).child("hasContent").data = true;
        JsonObject out = m.call("scenes.launch", "{\"sceneIndex\":0,\"expectedName\":\"Intro\"}");
        assertTrue(
                out.get("outcome").getAsString().equals("sent")
                        && !out.has("verification")
                        && out.get("clipCount").getAsInt() == 1,
                "a queued launch is sent, not playing");
        Model playing = new Model("Intro");
        playing.playsOnLaunch = true;
        playing.f.slot(0, 0).child("hasContent").data = true;
        out = playing.call("scenes.launch", "{\"sceneIndex\":0,\"expectedName\":\"Intro\"}");
        assertTrue(
                out.get("outcome").getAsString().equals("applied")
                        && out.get("verification").getAsString().equals("clips_playing"),
                "launch observed through the scene's playing clips");
        JsonObject empty = m.startShort("scenes.launch", "{\"sceneIndex\":1,\"expectedName\":\"Empty\"}")
                .result
                .join();
        assertTrue(
                empty.get("outcome").getAsString().equals("sent") && !empty.has("verification"),
                "a scene without clips is sent, never claimed as playing");
        Model unsafe = new Model("A");
        unsafe.f.transport.child("isArrangerRecordEnabled").emit(true);
        assertRpcError("unsafe_state", unsafe.start("scenes.launch", "{\"sceneIndex\":0,\"expectedName\":\"A\"}"));
        assertTrue(unsafe.f.bank.child("sceneBank").item(0).actions == 0, "nothing dispatched");
    }
}
