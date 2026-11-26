package dev.bitwigagent;

import com.bitwig.extension.callback.*;
import com.bitwig.extension.controller.api.*;
import com.google.gson.*;
import java.util.*;

/** Scene list, rename, launch, duplicate and delete over the public SceneBank; each scene is addressed by index plus its exact current name. */
final class SceneModule implements HostModule {
    static final Set<String> METHODS =
            Set.of("scenes.list", "scenes.rename", "scenes.launch", "scenes.duplicate", "scenes.delete");
    private final Scheduler scheduler;
    private final TrackWindow window;
    private final TransportSafety transportSafety;
    private final LauncherStructureLock lock;
    private final SceneBank bank;
    private final Scene[] scenes = new Scene[HostLimits.SCENES];
    // Names arrive by observer; null means not yet observed (an unnamed scene reports "", which is a real value).
    private final String[] names = new String[HostLimits.SCENES];
    private final long[] nameSequence = new long[HostLimits.SCENES];
    private final ClipLauncherSlot[][] slots = new ClipLauncherSlot[HostLimits.TRACKS][HostLimits.SCENES];
    private int count = -1;

    SceneModule(Scheduler scheduler, TrackWindow window, TransportSafety transportSafety, LauncherStructureLock lock) {
        this.scheduler = scheduler;
        this.window = window;
        this.transportSafety = transportSafety;
        this.lock = lock;
        bank = window.bank().sceneBank();
        bank.scrollPosition().markInterested();
        bank.itemCount().addValueObserver((IntegerValueChangedCallback) v -> count = v);
        for (int i = 0; i < HostLimits.SCENES; i++) {
            final int index = i;
            scenes[i] = (Scene) bank.getItemAt(i);
            scenes[i].exists().markInterested();
            scenes[i].name().addValueObserver((StringValueChangedCallback) v -> {
                names[index] = (String) v;
                nameSequence[index]++;
            });
        }
        for (int t = 0; t < HostLimits.TRACKS; t++) {
            Track track = window.at(t);
            ClipLauncherSlotBank slotBank = track.clipLauncherSlotBank();
            for (int j = 0; j < HostLimits.SCENES; j++) {
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
                    throw Errors.notApplied(Reason.INVALID_PARAMS, "name must contain 1..128 characters.");
                break;
            case "scenes.launch":
            case "scenes.duplicate":
            case "scenes.delete":
                Params.fields(p, Set.of("sceneIndex", "expectedName"), Set.of());
                break;
            default:
                throw Errors.notApplied(Reason.UNKNOWN_METHOD, "Unknown scene method.");
        }
        Params.integer(p, "sceneIndex", 0, HostLimits.SCENES - 1);
        expected(p);
    }

    /** An unnamed scene has the empty name, so unlike other targets expectedName may be empty. */
    private static String expected(JsonObject p) {
        JsonElement value = p.get("expectedName");
        if (!value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isString()
                || value.getAsString().length() > 256)
            throw Errors.notApplied(
                    Reason.INVALID_PARAMS,
                    "expectedName must be a string of at most 256 characters (empty for an unnamed scene).");
        return value.getAsString();
    }

    @Override
    public void dispatch(HostOperation op, String method, JsonObject p) {
        scheduler.check(op);
        if (!window.observed() || count < 0)
            throw Errors.notApplied(Reason.NOT_READY, "Scene bank has not reported its size yet.");
        if (bank.scrollPosition().get() != 0)
            throw Errors.notApplied(Reason.STALE_CONTEXT, "Scene bank is not at offset zero.");
        if (method.equals("scenes.list")) {
            op.result.complete(list());
            return;
        }
        int index = p.get("sceneIndex").getAsInt();
        if (index >= count)
            throw Errors.notApplied(
                    Reason.OUT_OF_BOUNDS,
                    "sceneIndex " + index + " does not exist: the project has " + count + " scene(s).");
        String actual = names[index];
        if (actual == null) throw Errors.notApplied(Reason.NOT_READY, "Scene name has not been observed yet.");
        if (!actual.equals(expected(p)))
            throw Errors.notApplied(
                    Reason.STALE_TARGET,
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
        JsonObject out = new JsonObject();
        out.addProperty("scope", "scene_bank");
        out.add("window", Results.window(0, HostLimits.SCENES, count));
        out.addProperty("sceneCount", count);
        JsonArray rows = new JsonArray();
        for (int i = 0; i < Math.min(count, HostLimits.SCENES); i++) {
            JsonObject row = new JsonObject();
            row.addProperty("sceneIndex", i);
            row.add("name", names[i] == null ? JsonNull.INSTANCE : new JsonPrimitive(names[i]));
            row.addProperty("clipCount", occupied(i).size());
            rows.add(row);
        }
        out.add("scenes", rows);
        return out;
    }

    private List<ClipLauncherSlot> occupied(int index) {
        List<ClipLauncherSlot> out = new ArrayList<>();
        for (int t = 0; t < HostLimits.TRACKS; t++)
            if ((window.at(t)).exists().get() && slots[t][index].hasContent().get()) out.add(slots[t][index]);
        return out;
    }

    /** A write result; {@code verification} says how an applied write was observed. */
    private JsonObject result(Outcome outcome, Reason reason, String verification, int index) {
        JsonObject out = Results.outcome(outcome);
        if (reason != null) out.addProperty("reason", reason.wire());
        out.addProperty("scope", "scene_bank");
        out.addProperty("sceneIndex", index);
        if (verification != null) out.addProperty("verification", verification);
        out.addProperty("sceneCount", count);
        return out;
    }

    private JsonObject result(boolean done, String verification, int index) {
        return done
                ? result(Outcome.APPLIED, null, verification, index)
                : result(Outcome.UNKNOWN, Reason.NOT_OBSERVED, null, index);
    }

    private void rename(HostOperation op, int index, String name) {
        if (name.equals(names[index])) {
            JsonObject out = result(Outcome.ALREADY_MATCHED, null, null, index);
            out.addProperty("name", name);
            op.result.complete(out);
            return;
        }
        long before = nameSequence[index];
        scheduler.check(op);
        op.markDispatched();
        scenes[index].name().set(name);
        scheduler.poll(
                op,
                scheduler.observeUntil(op),
                () -> nameSequence[index] > before && name.equals(names[index]),
                done -> {
                    JsonObject out = result(done, "observer_after_write", index);
                    out.add("name", names[index] == null ? JsonNull.INSTANCE : new JsonPrimitive(names[index]));
                    op.result.complete(out);
                });
    }

    private void launch(HostOperation op, int index) {
        transportSafety.requirePlaybackSafe();
        List<ClipLauncherSlot> clips = occupied(index);
        scheduler.check(op);
        op.markDispatched();
        scenes[index].launch();
        scheduler.poll(
                op,
                scheduler.observeUntil(op),
                () -> {
                    boolean playing = !clips.isEmpty();
                    for (ClipLauncherSlot slot : clips)
                        playing &= slot.isPlaying().get()
                                || slot.isPlaybackQueued().get();
                    return playing;
                },
                done -> {
                    // A scene without clips has nothing to observe: sent, never claimed as playing.
                    boolean playing = !clips.isEmpty();
                    for (ClipLauncherSlot slot : clips)
                        playing &= slot.isPlaying().get();
                    JsonObject out = clips.isEmpty() || done && !playing
                            ? result(Outcome.SENT, null, null, index)
                            : result(done, "clips_playing", index);
                    out.addProperty("clipCount", clips.size());
                    op.result.complete(out);
                });
    }

    private void duplicate(HostOperation op, int index) {
        lock.requireStructureFree();
        if (count + 1 > HostLimits.SCENES)
            throw Errors.notApplied(
                    Reason.LIMIT_REACHED,
                    "The project has " + count + " scene(s); the launcher tools address at most " + HostLimits.SCENES
                            + ", so one more cannot be added.");
        List<String> before = snapshotNames();
        if (before == null) throw Errors.notApplied(Reason.NOT_READY, "Not every scene name has been observed yet.");
        scheduler.check(op);
        op.markDispatched();
        scenes[index].duplicateObject();
        long[] sequenceBefore = nameSequence.clone();
        scheduler.poll(op, scheduler.observeUntil(op), () -> duplicated(index, before), done -> {
            JsonObject out = result(done, "scene_count_and_neighbour_names", index);
            out.addProperty("sceneCountBefore", before.size());
            if (done) {
                out.addProperty("newSceneIndex", index + 1);
                // The name is reported only once its own observer fired after the duplicate; the slot's old
                // value is not evidence.
                out.add(
                        "newSceneName",
                        nameSequence[index + 1] > sequenceBefore[index + 1]
                                ? new JsonPrimitive(snapshotNames().get(index + 1))
                                : JsonNull.INSTANCE);
            }
            op.result.complete(out);
        });
    }

    /** The copy sits directly after its source: everything up to the source is unchanged and the old tail shifted by one.
     *  Unnamed scenes read as their positional label ("Scene N"), which shifts with every insert: compare them as unnamed. */
    private boolean duplicated(int index, List<String> before) {
        List<String> was = unnamed(before), is = unnamed(snapshotNames());
        return count == before.size() + 1
                && is != null
                && is.subList(0, index + 1).equals(was.subList(0, index + 1))
                && is.subList(index + 2, is.size()).equals(was.subList(index + 1, was.size()))
                && occupied(index + 1).size() == occupied(index).size();
    }

    private void delete(HostOperation op, int index) {
        lock.requireStructureFree();
        SafetyObservation safety = transportSafety.stopped(op);
        if (count > HostLimits.SCENES)
            throw Errors.notApplied(
                    Reason.OUTSIDE_WINDOW,
                    "The project has " + count + " scenes; scene edits that need every name address at most "
                            + HostLimits.SCENES + ".");
        if (count <= 1) throw Errors.notApplied(Reason.LIMIT_REACHED, "A project keeps at least one scene.");
        List<ClipLauncherSlot> clips = occupied(index);
        for (ClipLauncherSlot slot : clips)
            if (slot.isPlaying().get()
                    || slot.isPlaybackQueued().get()
                    || slot.isRecording().get())
                throw Errors.notApplied(
                        Reason.UNSAFE_STATE,
                        "Delete requires the scene's clips to be neither playing, queued nor recording.");
        List<String> before = snapshotNames();
        if (before == null) throw Errors.notApplied(Reason.NOT_READY, "Not every scene name has been observed yet.");
        transportSafety.guardStopped(safety);
        scheduler.check(op);
        op.markDispatched();
        scenes[index].deleteObject();
        scheduler.poll(
                op,
                scheduler.observeUntil(op),
                () -> {
                    List<String> expected = unnamed(before);
                    expected.remove(index);
                    return count == before.size() - 1 && expected.equals(unnamed(snapshotNames()));
                },
                done -> {
                    JsonObject out = result(done, "scene_count_and_names", index);
                    out.addProperty("sceneCountBefore", before.size());
                    out.addProperty("deletedName", before.get(index));
                    out.addProperty("clipsDeleted", clips.size());
                    op.result.complete(out);
                });
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
        if (count < 0 || count > HostLimits.SCENES) return null;
        List<String> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            if (names[i] == null) return null;
            out.add(names[i]);
        }
        return out;
    }
}
