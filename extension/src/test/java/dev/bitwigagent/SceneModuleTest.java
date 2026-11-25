package dev.bitwigagent;

import com.bitwig.extension.controller.api.*;
import com.google.gson.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Simulated scene bank: callbacks and queued host tasks only; says nothing about native scene behavior. */
public final class SceneModuleTest {
    private static int assertions;

    public static void main(String[] args) {
        listReadsNamesAndClipCounts();
        validationRejectsBeforeScheduling();
        renameIsCompareAndSetAndObserved();
        duplicateVerifiesCountAndNeighbours();
        deleteIsGuardedAndVerifiedByNames();
        positionalDefaultNamesShiftWithoutBreakingVerification();
        launchObservesClipsOnlyWhenThereAreSome();
        System.out.println("SceneModuleTest: " + assertions + " assertions passed (simulated host only)");
    }

    /** A scene list model that republishes names and count the way observers would. */
    private static final class Model {
        final TransportMixFixture f = new TransportMixFixture();
        final List<String> names = new ArrayList<>();
        final SceneModule module;
        boolean applyDuplicate = true, applyDelete = true, positional;
        Runnable guard = () -> {};
        Model(String... initial) { this(() -> {}, initial); }
        Model(Runnable guard, String... initial) {
            names.addAll(Arrays.asList(initial));
            TransportMixFixture.Node bank = f.bank.child("sceneBank");
            bank.child("scrollPosition").data = 0;
            bank.child("itemCount").data = names.size();
            bank.custom = (name, args) -> name.equals("getItemAt") ? bank.item((Integer) args[0]).proxy(Scene.class) : null;
            for (int i = 0; i < HostServices.SCENES; i++) {
                final int index = i;
                TransportMixFixture.Node scene = bank.item(i);
                scene.child("name").data = i < names.size() ? names.get(i) : "";
                scene.custom = (name, args) -> {
                    if (name.equals("deleteObject")) { f.mutation(); scene.actions++; if (applyDelete) { names.remove(index); publish(bank); } }
                    else if (name.equals("duplicateObject")) { f.mutation(); scene.actions++; if (applyDuplicate) { names.add(index + 1, names.get(index)); publish(bank); } }
                    else if (name.equals("launch")) { f.mutation(); scene.actions++; for (int t = 0; t < 2; t++) {
                        TransportMixFixture.Node slot = slot(t, index);
                        if (slot.child("hasContent").data.equals(true)) slot.child("isPlaybackQueued").data = true;
                    } }
                    return null;
                };
            }
            for (int t = 0; t < HostServices.TRACKS; t++) {
                final int track = t;
                TransportMixFixture.Node slots = f.tracks[t].child("clipLauncherSlotBank");
                slots.custom = (name, args) -> name.equals("getItemAt") ? slot(track, (Integer) args[0]).proxy(ClipLauncherSlot.class) : null;
                for (int j = 0; j < HostServices.SCENES; j++) {
                    TransportMixFixture.Node slot = slot(t, j);
                    for (String key : List.of("hasContent", "isPlaying", "isPlaybackQueued", "isRecording")) slot.child(key).data = false;
                }
            }
            module = new SceneModule(f.services, guard);
        }
        TransportMixFixture.Node slot(int track, int scene) { return f.tracks[track].child("clipLauncherSlotBank").item(scene); }
        void publish(TransportMixFixture.Node bank) {
            // Bitwig labels an unnamed scene by its position, so every default label shifts after an insert or delete.
            if (positional) for (int i = 0; i < names.size(); i++) if (names.get(i).startsWith("Scene ")) names.set(i, "Scene " + (i + 1));
            for (int i = 0; i < HostServices.SCENES; i++) bank.item(i).child("name").emit(i < names.size() ? names.get(i) : "");
            bank.child("itemCount").emit(names.size());
        }
        JsonObject call(String method, String json) { return f.call(module, method, TransportMixFixture.json(json)); }
        HostOperation start(String method, String json) { HostOperation op = f.submit(module, method, TransportMixFixture.json(json)); f.next(); return op; }
        HostOperation startShort(String method, String json) {
            HostOperation op = f.submit(module, method, TransportMixFixture.json(json), System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(300));
            try { for (int i = 0; i < 400 && !op.result.isDone(); i++) { f.next(); Thread.sleep(2); } }
            catch (InterruptedException e) { throw new AssertionError(e); }
            return op;
        }
    }

    private static void listReadsNamesAndClipCounts() {
        Model m = new Model("Intro", "", "Outro");
        m.slot(0, 0).child("hasContent").data = true; m.slot(1, 0).child("hasContent").data = true; m.slot(0, 2).child("hasContent").data = true;
        JsonObject out = m.call("scenes.list", "{}");
        JsonArray rows = out.getAsJsonArray("scenes");
        check(out.get("sceneCount").getAsInt() == 3 && rows.size() == 3, "three scenes listed");
        check(rows.get(0).getAsJsonObject().get("name").getAsString().equals("Intro") && rows.get(0).getAsJsonObject().get("clipCount").getAsInt() == 2, "name and clip count");
        check(rows.get(1).getAsJsonObject().get("name").getAsString().isEmpty() && rows.get(1).getAsJsonObject().get("clipCount").getAsInt() == 0, "an unnamed scene reports the empty name");
        check(out.has("window"), "window reported");
    }

    private static void validationRejectsBeforeScheduling() {
        Model m = new Model("Intro");
        for (String text : List.of("{\"sceneIndex\":16,\"expectedName\":\"\",\"name\":\"X\"}", "{\"sceneIndex\":-1,\"expectedName\":\"\",\"name\":\"X\"}",
                "{\"sceneIndex\":0,\"name\":\"X\"}", "{\"sceneIndex\":0,\"expectedName\":\"\",\"name\":\"\"}", "{\"sceneIndex\":0,\"expectedName\":1,\"name\":\"X\"}",
                "{\"sceneIndex\":0,\"expectedName\":\"\",\"name\":\"X\",\"extra\":1}")) {
            TransportMixFixture.error(m.f.submit(m.module, "scenes.rename", TransportMixFixture.json(text)), "invalid_params");
            check(m.f.tasks.isEmpty(), "invalid rename not scheduled: " + text);
        }
        TransportMixFixture.error(m.f.submit(m.module, "scenes.delete", TransportMixFixture.json("{\"sceneIndex\":0}")), "invalid_params");
        TransportMixFixture.error(m.f.submit(m.module, "scenes.list", TransportMixFixture.json("{\"sceneIndex\":0}")), "invalid_params");
        SceneModule.validateParams("scenes.launch", TransportMixFixture.json("{\"sceneIndex\":15,\"expectedName\":\"\"}"));
        check(m.f.tasks.isEmpty(), "nothing scheduled");
    }

    private static void renameIsCompareAndSetAndObserved() {
        Model m = new Model("Intro", "");
        JsonObject out = m.call("scenes.rename", "{\"sceneIndex\":1,\"expectedName\":\"\",\"name\":\"Verse\"}");
        check(out.get("status").getAsString().equals("observed") && out.get("verification").getAsString().equals("observer_after_write") && out.get("name").getAsString().equals("Verse"), "rename verified by the name observer");
        check(m.f.bank.child("sceneBank").item(1).child("name").writes == 1 && m.f.bank.child("sceneBank").item(0).child("name").writes == 0, "only the addressed scene written");
        out = m.call("scenes.rename", "{\"sceneIndex\":1,\"expectedName\":\"Verse\",\"name\":\"Verse\"}");
        check(out.get("verification").getAsString().equals("already_at_value") && m.f.bank.child("sceneBank").item(1).child("name").writes == 1, "same name is not rewritten");
        HostOperation stale = m.start("scenes.rename", "{\"sceneIndex\":1,\"expectedName\":\"Chorus\",\"name\":\"X\"}");
        check(TransportMixFixture.error(stale, "stale_target").outcome.equals("not_applied"), "wrong expectedName refused");
        TransportMixFixture.error(m.start("scenes.rename", "{\"sceneIndex\":5,\"expectedName\":\"\",\"name\":\"X\"}"), "scene_out_of_range");
        m.f.bank.child("sceneBank").item(0).child("name").emitWrites = false;
        HostOperation silent = m.startShort("scenes.rename", "{\"sceneIndex\":0,\"expectedName\":\"Intro\",\"name\":\"Start\"}");
        JsonObject result = silent.result.join();
        check(result.get("status").getAsString().equals("dispatched") && result.get("completion").getAsString().equals("unknown"), "unobserved rename stays unknown");
    }

    private static void duplicateVerifiesCountAndNeighbours() {
        Model m = new Model("Intro", "Verse", "Outro");
        JsonObject out = m.call("scenes.duplicate", "{\"sceneIndex\":1,\"expectedName\":\"Verse\"}");
        check(out.get("status").getAsString().equals("observed") && out.get("newSceneIndex").getAsInt() == 2 && out.get("sceneCountBefore").getAsInt() == 3, "duplicate observed after its source");
        check(m.names.equals(List.of("Intro", "Verse", "Verse", "Outro")), "tail shifted by one");
        TransportMixFixture.error(m.start("scenes.duplicate", "{\"sceneIndex\":0,\"expectedName\":\"Other\"}"), "stale_target");
        check(m.f.bank.child("sceneBank").item(0).actions == 0, "stale target dispatches nothing");
        Model silent = new Model("A", "B"); silent.applyDuplicate = false;
        JsonObject unknown = silent.startShort("scenes.duplicate", "{\"sceneIndex\":0,\"expectedName\":\"A\"}").result.join();
        check(unknown.get("status").getAsString().equals("dispatched") && unknown.get("completion").getAsString().equals("unknown"), "unobserved duplicate stays unknown");
        Model tail = new Model("A", "B");
        out = tail.call("scenes.duplicate", "{\"sceneIndex\":1,\"expectedName\":\"B\"}");
        check(out.get("status").getAsString().equals("observed") && out.get("newSceneIndex").getAsInt() == 2, "duplicating the last scene is observed by count");
        check(out.get("newSceneName").isJsonNull() || out.get("newSceneName").getAsString().equals("B"), "a stale placeholder name is never reported");
        Model pinned = new Model(() -> { throw HostServices.failure("unresolved_mutation", "pinned"); }, "A", "B");
        check(TransportMixFixture.error(pinned.start("scenes.duplicate", "{\"sceneIndex\":0,\"expectedName\":\"A\"}"), "unresolved_mutation").outcome.equals("not_applied"), "a pinned launcher blocks duplicate");
        TransportMixFixture.error(pinned.start("scenes.delete", "{\"sceneIndex\":0,\"expectedName\":\"A\"}"), "unresolved_mutation");
        check(pinned.f.bank.child("sceneBank").item(0).actions == 0, "nothing dispatched while pinned");
        String[] full = new String[HostServices.SCENES]; Arrays.fill(full, "S");
        Model limit = new Model(full);
        TransportMixFixture.error(limit.start("scenes.duplicate", "{\"sceneIndex\":0,\"expectedName\":\"S\"}"), "scene_limit");
    }

    private static void deleteIsGuardedAndVerifiedByNames() {
        Model m = new Model("Intro", "Verse", "Outro");
        m.slot(0, 1).child("hasContent").data = true;
        JsonObject out = m.call("scenes.delete", "{\"sceneIndex\":1,\"expectedName\":\"Verse\"}");
        check(out.get("status").getAsString().equals("observed") && out.get("deletedName").getAsString().equals("Verse") && out.get("clipsDeleted").getAsInt() == 1, "delete observed with the clip count it removed");
        check(m.names.equals(List.of("Intro", "Outro")), "later scenes shifted up");
        TransportMixFixture.error(m.start("scenes.delete", "{\"sceneIndex\":1,\"expectedName\":\"Verse\"}"), "stale_target");

        Model playing = new Model("A", "B");
        playing.slot(0, 0).child("hasContent").data = true; playing.slot(0, 0).child("isPlaying").data = true;
        check(TransportMixFixture.error(playing.start("scenes.delete", "{\"sceneIndex\":0,\"expectedName\":\"A\"}"), "unsafe_state").outcome.equals("not_applied"), "a playing clip blocks delete");
        check(playing.f.bank.child("sceneBank").item(0).actions == 0, "nothing dispatched");
        Model transport = new Model("A", "B");
        transport.f.transport.child("isPlaying").emit(true);
        TransportMixFixture.error(transport.start("scenes.delete", "{\"sceneIndex\":1,\"expectedName\":\"B\"}"), "unsafe_state");
        String[] many = new String[HostServices.SCENES + 1]; Arrays.fill(many, "S");
        TransportMixFixture.error(new Model(many).start("scenes.delete", "{\"sceneIndex\":0,\"expectedName\":\"S\"}"), "outside_window");
        Model last = new Model("Only");
        TransportMixFixture.error(last.start("scenes.delete", "{\"sceneIndex\":0,\"expectedName\":\"Only\"}"), "last_scene");
        Model silent = new Model("A", "B"); silent.applyDelete = false;
        JsonObject unknown = silent.startShort("scenes.delete", "{\"sceneIndex\":0,\"expectedName\":\"A\"}").result.join();
        check(unknown.get("status").getAsString().equals("dispatched") && unknown.get("completion").getAsString().equals("unknown"), "unobserved delete stays unknown");
    }

    private static void positionalDefaultNamesShiftWithoutBreakingVerification() {
        Model m = new Model("Scene 1", "Scene 2", "Chorus", "Scene 4"); m.positional = true;
        JsonObject out = m.call("scenes.duplicate", "{\"sceneIndex\":0,\"expectedName\":\"Scene 1\"}");
        check(out.get("status").getAsString().equals("observed") && out.get("newSceneIndex").getAsInt() == 1, "duplicate of a positionally named scene is observed");
        check(m.names.equals(List.of("Scene 1", "Scene 2", "Scene 3", "Chorus", "Scene 5")), "later default labels shifted");
        out = m.call("scenes.delete", "{\"sceneIndex\":1,\"expectedName\":\"Scene 2\"}");
        check(out.get("status").getAsString().equals("observed") && out.get("verification").getAsString().equals("scene_count_and_names"), "delete is observed despite shifted default labels");
        check(m.names.equals(List.of("Scene 1", "Scene 2", "Chorus", "Scene 4")), "back to the original labels");
        Model renamed = new Model("Scene 1", "Scene 2"); renamed.positional = true; renamed.applyDelete = false;
        check(!renamed.startShort("scenes.delete", "{\"sceneIndex\":0,\"expectedName\":\"Scene 1\"}").result.join().get("status").getAsString().equals("observed"), "an unchanged list is still not observed");
    }

    private static void launchObservesClipsOnlyWhenThereAreSome() {
        Model m = new Model("Intro", "Empty");
        m.slot(0, 0).child("hasContent").data = true;
        JsonObject out = m.call("scenes.launch", "{\"sceneIndex\":0,\"expectedName\":\"Intro\"}");
        check(out.get("status").getAsString().equals("observed") && out.get("verification").getAsString().equals("clips_playing_or_queued") && out.get("clipCount").getAsInt() == 1, "launch observed through the scene's clips");
        JsonObject empty = m.startShort("scenes.launch", "{\"sceneIndex\":1,\"expectedName\":\"Empty\"}").result.join();
        check(empty.get("status").getAsString().equals("dispatched") && empty.get("completion").getAsString().equals("not_observed")
            && empty.get("verification").getAsString().equals("scene_has_no_clips"), "a scene without clips is dispatched, never claimed as playing");
        Model unsafe = new Model("A");
        unsafe.f.transport.child("isArrangerRecordEnabled").emit(true);
        check(TransportMixFixture.error(unsafe.start("scenes.launch", "{\"sceneIndex\":0,\"expectedName\":\"A\"}"), "unsafe_state").outcome.equals("not_applied"), "recording blocks launch");
        check(unsafe.f.bank.child("sceneBank").item(0).actions == 0, "nothing dispatched");
    }

    private static void check(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }
}
