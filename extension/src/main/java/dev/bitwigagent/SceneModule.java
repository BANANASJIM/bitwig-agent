package dev.bitwigagent;

import com.bitwig.extension.callback.*;
import com.bitwig.extension.controller.api.*;
import com.google.gson.*;
import dev.bitwigagent.core.Refs;
import java.util.*;

/** Scene list, insert, rename, launch, duplicate and delete over the public SceneBank, inside the scene window lease. */
final class SceneModule implements HostModule {
    static final Set<String> METHODS =
            Set.of("scenes.list", "scenes.insert", "scenes.set", "scenes.launch", "scenes.duplicate", "scenes.delete");
    private final Scheduler scheduler;
    private final TrackWindow window;
    private final TransportSafety transportSafety;
    private final LauncherStructureLock lock;
    private final LauncherTargets targets;
    private final Project project;
    private final long[] nameSequence = new long[HostLimits.SCENES];
    private final String[] observedNames = new String[HostLimits.SCENES];

    SceneModule(
            Scheduler scheduler,
            TrackWindow window,
            TransportSafety transportSafety,
            LauncherStructureLock lock,
            LauncherTargets targets,
            Project project) {
        this.scheduler = scheduler;
        this.window = window;
        this.transportSafety = transportSafety;
        this.lock = lock;
        this.targets = targets;
        this.project = project;
        for (int i = 0; i < HostLimits.SCENES; i++) {
            final int index = i;
            window.scene(i).name().addValueObserver((StringValueChangedCallback) v -> {
                observedNames[index] = (String) v;
                nameSequence[index]++;
            });
        }
        for (int t = 0; t < HostLimits.TRACKS; t++) {
            ClipLauncherSlotBank slotBank = window.at(t).clipLauncherSlotBank();
            for (int j = 0; j < HostLimits.SCENES; j++) {
                ClipLauncherSlot slot = (ClipLauncherSlot) slotBank.getItemAt(j);
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
    public void validate(String method, JsonObject params) {}

    @Override
    public void dispatch(HostOperation op, String method, JsonObject p) {
        scheduler.check(op);
        switch (method) {
            case "scenes.list":
                list(op, p);
                return;
            case "scenes.insert":
                insert(op, p.has("count") ? p.get("count").getAsInt() : 1);
                return;
            default:
        }
        boolean structure = method.equals("scenes.duplicate") || method.equals("scenes.delete");
        targets.enterScene(op, p.getAsJsonObject("target"), false, structure, at -> {
            requireShown(at);
            switch (method) {
                case "scenes.set":
                    rename(op, at, p.get("name").getAsString());
                    break;
                case "scenes.launch":
                    launch(op, at);
                    break;
                case "scenes.duplicate":
                    duplicate(op, at);
                    break;
                default:
                    delete(op, at);
            }
        });
    }

    // ---- list ----

    private void list(HostOperation op, JsonObject p) {
        if (!window.scenesObserved())
            throw Errors.notApplied(Reason.NOT_READY, "Scene bank has not reported its size yet.");
        int total = window.sceneCount(),
                first = p.has("sceneOffset") ? p.get("sceneOffset").getAsInt() : 0;
        if (first > 0 && first >= total)
            throw Errors.notApplied(
                    Reason.OUT_OF_BOUNDS, "sceneOffset " + first + " is past the " + total + " scene(s).");
        long until = scheduler.deadlineUntil(op) - Scheduler.OBSERVE_WINDOW_NANOS;
        targets.lease.enter(
                op,
                window.sceneWindowFor(first),
                true,
                true,
                () -> page(op, first, first, total, new JsonArray(), until));
    }

    private void page(HostOperation op, int from, int scene, int total, JsonArray rows, long until) {
        int offset = window.sceneOffset(), end = Math.min(total, offset + HostLimits.SCENES);
        for (int s = scene; s < end; s++) {
            JsonObject row = new JsonObject();
            row.add("ref", targets.sceneRef(s, offset));
            row.addProperty("clipCount", occupied(s - offset).size());
            rows.add(row);
        }
        if (end < total && scheduler.now() < until) {
            targets.lease.move(op, window.sceneWindowFor(end), () -> page(op, from, end, total, rows, until));
            return;
        }
        JsonObject out = new JsonObject();
        out.addProperty("scope", "scene_bank");
        out.addProperty("sceneCount", total);
        JsonObject info = Results.window(from, rows.size(), total);
        info.addProperty("complete", end >= total);
        out.add("window", info);
        out.addProperty("complete", end >= total);
        if (end < total) {
            JsonObject next = new JsonObject();
            next.addProperty("sceneOffset", end);
            out.add("next", next);
        }
        out.add("scenes", rows);
        op.result.complete(out);
    }

    private List<ClipLauncherSlot> occupied(int position) {
        List<ClipLauncherSlot> out = new ArrayList<>();
        for (int t = 0; t < HostLimits.TRACKS; t++) {
            if (!window.at(t).exists().get()) continue;
            ClipLauncherSlot slot =
                    (ClipLauncherSlot) window.at(t).clipLauncherSlotBank().getItemAt(position);
            if (slot.hasContent().get()) out.add(slot);
        }
        return out;
    }

    // ---- writes ----

    /** A write result; {@code verification} says how an applied write was observed. */
    private JsonObject result(Outcome outcome, Reason reason, String verification) {
        JsonObject out = Results.outcome(outcome);
        if (reason != null) out.addProperty("reason", reason.wire());
        out.addProperty("scope", "scene_bank");
        if (verification != null) out.addProperty("verification", verification);
        out.addProperty("sceneCount", window.sceneCount());
        return out;
    }

    private JsonObject result(boolean done, String verification) {
        return done ? result(Outcome.APPLIED, null, verification) : result(Outcome.UNKNOWN, Reason.NOT_OBSERVED, null);
    }

    private void insert(HostOperation op, int count) {
        if (!window.scenesObserved())
            throw Errors.notApplied(Reason.NOT_READY, "Scene bank has not reported its size yet.");
        int before = window.sceneCount();
        op.markDispatched();
        // Project.createScene() is documented to create "a new empty scene as the last scene"; existing slot indices do
        // not move.
        for (int i = 0; i < count; i++) project.createScene();
        scheduler.poll(
                op,
                scheduler.observeUntil(op),
                () -> {
                    scheduler.check(op);
                    return window.sceneCount() == before + count;
                },
                () -> {
                    JsonObject out = Results.outcome(Outcome.APPLIED);
                    out.addProperty("verification", "scene_count");
                    out.addProperty("sceneCountBefore", before);
                    out.addProperty("sceneCount", before + count);
                    JsonArray refs = new JsonArray();
                    for (int i = 0; i < count; i++) refs.add(Refs.scene(before + i, null));
                    out.add("ref", refs.get(0));
                    out.add("refs", refs);
                    out.addProperty(
                            "placement", "last_scene_per_api_documentation; position is not read back, count is");
                    op.result.complete(out);
                },
                () -> {
                    throw Errors.unknown(
                            Reason.NOT_OBSERVED,
                            "Scene creation was dispatched, but the scene count did not reach " + (before + count)
                                    + " (observed " + window.sceneCount()
                                    + "); inspect scenes.list and do not repeat");
                });
    }

    private boolean shown(LauncherTargets.Loc at) {
        return window.scene(at.position()).sceneIndex().get() == at.scene;
    }

    private void requireShown(LauncherTargets.Loc at) {
        if (!shown(at)) throw Errors.notApplied(Reason.NOT_READY, "The scene proxy does not show the scene yet.");
    }

    private void rename(HostOperation op, LauncherTargets.Loc at, String name) {
        int position = at.position();
        Scene scene = window.scene(position);
        if (name.equals(LauncherTargets.sceneName(at.scene, scene.name().get()))) {
            JsonObject out = result(Outcome.ALREADY_MATCHED, null, null);
            out.add("ref", at.sceneRef);
            op.result.complete(out);
            return;
        }
        long before = nameSequence[position];
        scheduler.check(op);
        op.markDispatched();
        scene.name().set(name);
        scheduler.poll(
                op,
                scheduler.observeUntil(op),
                () -> shown(at) && nameSequence[position] > before && name.equals(observedNames[position]),
                done -> {
                    JsonObject out = result(done, "observer_after_write");
                    out.add("ref", targets.sceneRef(at.scene, at.offset));
                    op.result.complete(out);
                });
    }

    private void launch(HostOperation op, LauncherTargets.Loc at) {
        transportSafety.requirePlaybackSafe();
        List<ClipLauncherSlot> clips = occupied(at.position());
        scheduler.check(op);
        op.markDispatched();
        window.scene(at.position()).launch();
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
                            ? result(Outcome.SENT, null, null)
                            : result(done, "clips_playing");
                    out.add("ref", at.sceneRef);
                    out.addProperty("clipCount", clips.size());
                    op.result.complete(out);
                });
    }

    /** The names the window shows, with the positional default blanked so a shifted label is not mistaken for a rename. */
    private List<String> shown(int offset) {
        List<String> out = new ArrayList<>();
        for (int j = 0; j < Math.min(HostLimits.SCENES, window.sceneCount() - offset); j++) {
            String raw = window.scene(j).name().get();
            String name = LauncherTargets.sceneName(offset + j, raw);
            out.add(name == null ? "" : name);
        }
        return out;
    }

    private void duplicate(HostOperation op, LauncherTargets.Loc at) {
        lock.requireStructureFree();
        int index = at.scene, offset = at.offset, position = at.position(), count = window.sceneCount();
        List<String> before = shown(offset);
        int clips = occupied(position).size();
        scheduler.check(op);
        op.markDispatched();
        window.scene(position).duplicateObject();
        scheduler.poll(
                op,
                scheduler.observeUntil(op),
                () -> shown(at) && duplicated(offset, index, count, before, clips),
                done -> {
                    JsonObject out = result(done, "scene_count_and_window_names");
                    out.addProperty("sceneCountBefore", count);
                    if (done) completeWithScene(op, out, index + 1);
                    else op.result.complete(out);
                });
    }

    /** The copy sits directly after its source: names up to the source are unchanged and the old tail is shifted by one. */
    private boolean duplicated(int offset, int index, int count, List<String> before, int clips) {
        if (window.sceneCount() != count + 1) return false;
        List<String> now = shown(offset);
        for (int j = 0; j < now.size(); j++) {
            int scene = offset + j;
            if (scene <= index && !now.get(j).equals(before.get(j))) return false;
            if (scene >= index + 2 && j >= 1 && !now.get(j).equals(before.get(j - 1))) return false;
        }
        int copy = index + 1 - offset;
        return copy >= HostLimits.SCENES || occupied(copy).size() == clips;
    }

    private void completeWithScene(HostOperation op, JsonObject out, int scene) {
        int offset = window.sceneOffset();
        if (scene < offset + HostLimits.SCENES) {
            out.add("ref", targets.sceneRef(scene, offset));
            op.result.complete(out);
            return;
        }
        try {
            targets.lease.moveAfterWrite(
                    op,
                    window.sceneWindowFor(scene),
                    () -> {
                        out.add("ref", targets.sceneRef(scene, window.sceneOffset()));
                        op.result.complete(out);
                    },
                    () -> op.result.complete(out));
        } catch (RpcException busy) {
            op.result.complete(out);
        }
    }

    private void delete(HostOperation op, LauncherTargets.Loc at) {
        lock.requireStructureFree();
        SafetyObservation safety = transportSafety.stopped(op);
        int index = at.scene, offset = at.offset, count = window.sceneCount();
        if (count <= 1) throw Errors.notApplied(Reason.LIMIT_REACHED, "A project keeps at least one scene.");
        List<ClipLauncherSlot> clips = occupied(at.position());
        for (ClipLauncherSlot slot : clips)
            if (slot.isPlaying().get()
                    || slot.isPlaybackQueued().get()
                    || slot.isRecording().get())
                throw Errors.notApplied(
                        Reason.UNSAFE_STATE,
                        "Delete requires the scene's clips to be neither playing, queued nor recording.");
        List<String> before = shown(offset);
        transportSafety.guardStopped(safety);
        scheduler.check(op);
        op.markDispatched();
        window.scene(at.position()).deleteObject();
        scheduler.poll(
                op, scheduler.observeUntil(op), () -> shown(at) && deleted(offset, index, count, before), done -> {
                    JsonObject out = result(done, "scene_count_and_window_names");
                    out.addProperty("sceneCountBefore", count);
                    out.addProperty("deletedName", before.get(index - offset));
                    out.addProperty("clipsDeleted", clips.size());
                    op.result.complete(out);
                });
    }

    private boolean deleted(int offset, int index, int count, List<String> before) {
        if (window.sceneCount() != count - 1) return false;
        List<String> now = shown(offset);
        for (int j = 0; j < now.size(); j++) {
            int scene = offset + j;
            if (scene < index && !now.get(j).equals(before.get(j))) return false;
            if (scene >= index && j + 1 < before.size() && !now.get(j).equals(before.get(j + 1))) return false;
        }
        return true;
    }
}
