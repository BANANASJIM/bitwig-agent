package dev.bitwigagent;

import com.bitwig.extension.callback.*;
import com.bitwig.extension.controller.api.*;
import com.google.gson.*;
import java.util.*;

/** Scene list, rename, launch, duplicate and delete over the public SceneBank; each scene is addressed by index plus its exact current name. */
final class SceneModule implements HostModule {
    static final Set<String> METHODS =
            Set.of("scenes.list", "scenes.rename", "scenes.launch", "scenes.duplicate", "scenes.delete");
    private final HostServices s;
    private final SceneBank bank;
    private final Scene[] scenes = new Scene[HostServices.SCENES];
    // Names arrive by observer; null means not yet observed (an unnamed scene reports "", which is a real value).
    private final String[] names = new String[HostServices.SCENES];
    private final long[] nameSequence = new long[HostServices.SCENES];
    private final ClipLauncherSlot[][] slots = new ClipLauncherSlot[HostServices.TRACKS][HostServices.SCENES];
    private int count = -1;
    private final Runnable structureGuard;

    SceneModule(HostServices services) {
        this(services, () -> {});
    }
    /** structureGuard refuses duplicate/delete while launcher work pins the slot rows (see LauncherModule.requireStructureSafe). */
    SceneModule(HostServices services, Runnable structureGuard) {
        s = services;
        this.structureGuard = structureGuard;
        bank = s.tracks.sceneBank();
        bank.scrollPosition().markInterested();
        bank.itemCount().addValueObserver((IntegerValueChangedCallback) v -> count = v);
        for (int i = 0; i < HostServices.SCENES; i++) {
            final int index = i;
            scenes[i] = (Scene) bank.getItemAt(i);
            scenes[i].exists().markInterested();
            scenes[i].name().addValueObserver((StringValueChangedCallback) v -> {
                names[index] = (String) v;
                nameSequence[index]++;
            });
        }
        for (int t = 0; t < HostServices.TRACKS; t++) {
            Track track = (Track) s.tracks.getItemAt(t);
            ClipLauncherSlotBank slotBank = track.clipLauncherSlotBank();
            for (int j = 0; j < HostServices.SCENES; j++) {
                ClipLauncherSlot slot = slots[t][j] = (ClipLauncherSlot) slotBank.getItemAt(j);
                slot.hasContent().markInterested();
                slot.isPlaying().markInterested();
                slot.isPlaybackQueued().markInterested();
                slot.isRecording().markInterested();
            }
        }
    }

    @Override
    public Set<String> methods() {
        return METHODS;
    }

    @Override
    public void validate(String method, JsonObject params) {
        validateParams(method, params);
    }

    static void validateParams(String method, JsonObject p) {
        switch (method) {
            case "scenes.list":
                Params.fields(p, Set.of(), Set.of());
                return;
            case "scenes.rename":
                Params.fields(p, Set.of("sceneIndex", "expectedName", "name"), Set.of());
                if (Params.string(p, "name").length() > 128)
                    throw HostServices.failure("invalid_params", "name must contain 1..128 characters.");
                break;
            case "scenes.launch":
            case "scenes.duplicate":
            case "scenes.delete":
                Params.fields(p, Set.of("sceneIndex", "expectedName"), Set.of());
                break;
            default:
                throw HostServices.failure("unknown_method", "Unknown scene method.");
        }
        Params.integer(p, "sceneIndex", 0, HostServices.SCENES - 1);
        expected(p);
    }

    /** An unnamed scene has the empty name, so unlike other targets expectedName may be empty. */
    private static String expected(JsonObject p) {
        JsonElement value = p.get("expectedName");
        if (!value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isString()
                || value.getAsString().length() > 256)
            throw HostServices.failure(
                    "invalid_params",
                    "expectedName must be a string of at most 256 characters (empty for an unnamed scene).");
        return value.getAsString();
    }

    @Override
    public void dispatch(HostOperation op, String method, JsonObject p) {
        s.check(op);
        if (!s.tracksObserved || count < 0)
            throw HostServices.failure("not_ready", "Scene bank has not reported its size yet.");
        if (bank.scrollPosition().get() != 0)
            throw HostServices.failure("stale_context", "Scene bank is not at offset zero.");
        if (method.equals("scenes.list")) {
            op.result.complete(list());
            return;
        }
        int index = p.get("sceneIndex").getAsInt();
        if (index >= count)
            throw HostServices.failure(
                    "scene_out_of_range",
                    "sceneIndex " + index + " does not exist: the project has " + count + " scene(s).");
        String actual = names[index];
        if (actual == null) throw HostServices.failure("not_ready", "Scene name has not been observed yet.");
        if (!actual.equals(expected(p)))
            throw HostServices.failure(
                    "stale_target",
                    "Scene " + index + " is named \"" + actual + "\", not expectedName; re-read scenes.list.");
        switch (method) {
            case "scenes.rename":
                rename(op, index, p.get("name").getAsString());
                break;
            case "scenes.launch":
                launch(op, index);
                break;
            case "scenes.duplicate":
                duplicate(op, index);
                break;
            default:
                delete(op, index);
        }
    }

    private JsonObject list() {
        JsonObject out = HostServices.observed();
        out.addProperty("scope", "scene_bank");
        out.add("window", HostServices.window(0, HostServices.SCENES, count));
        out.addProperty("sceneCount", count);
        JsonArray rows = new JsonArray();
        for (int i = 0; i < Math.min(count, HostServices.SCENES); i++) {
            JsonObject row = new JsonObject();
            row.addProperty("sceneIndex", i);
            row.add("name", names[i] == null ? JsonNull.INSTANCE : new JsonPrimitive(names[i]));
            row.addProperty("clipCount", occupied(i).size());
            rows.add(row);
        }
        out.add("scenes", rows);
        return out;
    }

    /** Observed tracks that hold a clip in this scene row. */
    private List<ClipLauncherSlot> occupied(int index) {
        List<ClipLauncherSlot> out = new ArrayList<>();
        for (int t = 0; t < HostServices.TRACKS; t++)
            if (((Track) s.tracks.getItemAt(t)).exists().get()
                    && slots[t][index].hasContent().get()) out.add(slots[t][index]);
        return out;
    }

    private JsonObject result(String status, String completion, String verification, int index) {
        JsonObject out = HostServices.observed();
        out.addProperty("scope", "scene_bank");
        out.addProperty("sceneIndex", index);
        out.addProperty("status", status);
        out.addProperty("completion", completion);
        out.addProperty("verification", verification);
        out.addProperty("sceneCount", count);
        return out;
    }

    private void rename(HostOperation op, int index, String name) {
        if (name.equals(names[index])) {
            JsonObject out = result("observed", "observed", "already_at_value", index);
            out.addProperty("name", name);
            op.result.complete(out);
            return;
        }
        long before = nameSequence[index];
        s.check(op);
        op.dispatched = true;
        scenes[index].name().set(name);
        awaitRename(op, index, name, before, HostServices.observeUntil(op));
    }

    private void awaitRename(HostOperation op, int index, String name, long before, long end) {
        boolean done = nameSequence[index] > before && name.equals(names[index]);
        if (done || System.nanoTime() >= end) {
            JsonObject out = result(
                    done ? "observed" : "dispatched",
                    done ? "observed" : "unknown",
                    done ? "observer_after_write" : "name_not_observed",
                    index);
            out.add("name", names[index] == null ? JsonNull.INSTANCE : new JsonPrimitive(names[index]));
            out.addProperty("matchesRequested", done);
            op.result.complete(out);
        } else s.schedule(op, () -> awaitRename(op, index, name, before, end), 20);
    }

    private void launch(HostOperation op, int index) {
        s.requirePlaybackSafe();
        List<ClipLauncherSlot> clips = occupied(index);
        s.check(op);
        op.dispatched = true;
        scenes[index].launch();
        awaitLaunch(op, index, clips, HostServices.observeUntil(op));
    }

    private void awaitLaunch(HostOperation op, int index, List<ClipLauncherSlot> clips, long end) {
        boolean done = !clips.isEmpty();
        for (ClipLauncherSlot slot : clips)
            done &= slot.isPlaying().get() || slot.isPlaybackQueued().get();
        if (done || System.nanoTime() >= end) {
            // A scene without clips has nothing to observe: dispatched, never claimed as playing.
            JsonObject out = result(
                    done ? "observed" : "dispatched",
                    done ? "observed" : "not_observed",
                    done ? "clips_playing_or_queued" : clips.isEmpty() ? "scene_has_no_clips" : "playback_not_observed",
                    index);
            out.addProperty("clipCount", clips.size());
            op.result.complete(out);
        } else s.schedule(op, () -> awaitLaunch(op, index, clips, end), 20);
    }

    private void duplicate(HostOperation op, int index) {
        structureGuard.run();
        if (count + 1 > HostServices.SCENES)
            throw HostServices.failure(
                    "scene_limit",
                    "The project has " + count + " scene(s); the launcher tools address at most " + HostServices.SCENES
                            + ", so one more cannot be added.");
        List<String> before = snapshotNames();
        if (before == null) throw HostServices.failure("not_ready", "Not every scene name has been observed yet.");
        s.check(op);
        op.dispatched = true;
        scenes[index].duplicateObject();
        awaitDuplicate(op, index, before, nameSequence.clone(), HostServices.observeUntil(op));
    }

    private void awaitDuplicate(HostOperation op, int index, List<String> before, long[] sequenceBefore, long end) {
        List<String> now = snapshotNames();
        // The copy sits directly after its source: everything up to the source is unchanged and the old tail shifted by
        // one.
        // Unnamed scenes read as their positional label ("Scene N"), which shifts with every insert: compare them as
        // unnamed.
        List<String> was = unnamed(before), is = unnamed(now);
        boolean done = count == before.size() + 1
                && is != null
                && is.subList(0, index + 1).equals(was.subList(0, index + 1))
                && is.subList(index + 2, is.size()).equals(was.subList(index + 1, was.size()))
                && occupied(index + 1).size() == occupied(index).size();
        if (done || System.nanoTime() >= end) {
            JsonObject out = result(
                    done ? "observed" : "dispatched",
                    done ? "observed" : "unknown",
                    done ? "scene_count_and_neighbour_names" : "duplicate_not_observed",
                    index);
            out.addProperty("sceneCountBefore", before.size());
            if (done) {
                out.addProperty("newSceneIndex", index + 1);
                // The name is reported only once its own observer fired after the duplicate; the slot's old value is
                // not evidence.
                out.add(
                        "newSceneName",
                        nameSequence[index + 1] > sequenceBefore[index + 1]
                                ? new JsonPrimitive(now.get(index + 1))
                                : JsonNull.INSTANCE);
            }
            op.result.complete(out);
        } else s.schedule(op, () -> awaitDuplicate(op, index, before, sequenceBefore, end), 20);
    }

    private void delete(HostOperation op, int index) {
        structureGuard.run();
        HostServices.SafetyObservation safety = s.stoppedSafety(op);
        if (count > HostServices.SCENES)
            throw HostServices.failure(
                    "outside_window",
                    "The project has " + count + " scenes; scene edits that need every name address at most "
                            + HostServices.SCENES + ".");
        if (count <= 1) throw HostServices.failure("last_scene", "A project keeps at least one scene.");
        List<ClipLauncherSlot> clips = occupied(index);
        for (ClipLauncherSlot slot : clips)
            if (slot.isPlaying().get()
                    || slot.isPlaybackQueued().get()
                    || slot.isRecording().get())
                throw HostServices.failure(
                        "unsafe_state",
                        "Delete requires the scene's clips to be neither playing, queued nor recording.");
        List<String> before = snapshotNames();
        if (before == null) throw HostServices.failure("not_ready", "Not every scene name has been observed yet.");
        s.guardStoppedSafety(safety);
        s.check(op);
        op.dispatched = true;
        scenes[index].deleteObject();
        awaitDelete(op, index, before, clips.size(), HostServices.observeUntil(op));
    }

    private void awaitDelete(HostOperation op, int index, List<String> before, int clipCount, long end) {
        List<String> expected = unnamed(before);
        expected.remove(index);
        boolean done = count == before.size() - 1 && expected.equals(unnamed(snapshotNames()));
        if (done || System.nanoTime() >= end) {
            JsonObject out = result(
                    done ? "observed" : "dispatched",
                    done ? "observed" : "unknown",
                    done ? "scene_count_and_names" : "delete_not_observed",
                    index);
            out.addProperty("sceneCountBefore", before.size());
            out.addProperty("deletedName", before.get(index));
            out.addProperty("clipsDeleted", clipCount);
            op.result.complete(out);
        } else s.schedule(op, () -> awaitDelete(op, index, before, clipCount, end), 20);
    }

    /** Names with the positional default ("Scene N" at index N-1) blanked, so a shifted label is not mistaken for a rename; null stays null. */
    static List<String> unnamed(List<String> names) {
        if (names == null) return null;
        List<String> out = new ArrayList<>(names.size());
        for (int i = 0; i < names.size(); i++) out.add(names.get(i).equals("Scene " + (i + 1)) ? "" : names.get(i));
        return out;
    }

    /** Names of all scenes in the window, or null while any is unobserved. */
    private List<String> snapshotNames() {
        if (count < 0 || count > HostServices.SCENES) return null;
        List<String> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            if (names[i] == null) return null;
            out.add(names[i]);
        }
        return out;
    }
}
