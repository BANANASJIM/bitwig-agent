package dev.bitwigagent;

import com.bitwig.extension.controller.api.ClipLauncherSlot;
import com.google.gson.JsonObject;
import dev.bitwigagent.core.Refs;
import java.util.function.Consumer;

/** Scene, slot and clip refs: built from the scene window, and resolved by entering the window that shows them. */
final class LauncherTargets {
    /** One located slot: track window slot, absolute scene, window offset and the refs now at that place. */
    static final class Loc {
        final int track, scene, offset;
        final JsonObject trackRef, sceneRef;

        Loc(int track, int scene, int offset, JsonObject trackRef, JsonObject sceneRef) {
            this.track = track;
            this.scene = scene;
            this.offset = offset;
            this.trackRef = trackRef;
            this.sceneRef = sceneRef;
        }

        /** Position of the scene inside the scene window. */
        int position() {
            return scene - offset;
        }

        String trackId() {
            return trackRef.get("id").getAsString();
        }
    }

    private final TrackWindow window;
    private final TrackRefs trackRefs;
    final SceneWindowLease lease;

    LauncherTargets(TrackWindow window, TrackRefs trackRefs, SceneWindowLease lease) {
        this.window = window;
        this.trackRefs = trackRefs;
        this.lease = lease;
    }

    /** null for an unnamed scene: no name, or the positional label Bitwig shows ("Scene N"). */
    static String sceneName(int index, String raw) {
        return raw == null || raw.isEmpty() || raw.equals("Scene " + (index + 1)) ? null : raw;
    }

    /** The ref of an absolute scene the window shows now. */
    JsonObject sceneRef(int scene, int offset) {
        return Refs.scene(
                scene, sceneName(scene, window.scene(scene - offset).name().get()));
    }

    JsonObject slotRef(Loc at) {
        return Refs.slot(at.trackRef, at.sceneRef);
    }

    JsonObject clipRef(Loc at, String name) {
        return Refs.launcherClip(at.trackRef, at.sceneRef, name);
    }

    ClipLauncherSlot api(Loc at) {
        return (ClipLauncherSlot) window.at(at.track).clipLauncherSlotBank().getItemAt(at.position());
    }

    /** The scene index a scene, slot or launcher clip ref names. */
    static int sceneIndex(JsonObject ref) {
        switch (Refs.kind(ref)) {
            case "scene":
                return ref.get("index").getAsInt();
            case "slot":
                return ref.getAsJsonObject("scene").get("index").getAsInt();
            default:
                return ref.getAsJsonObject("slot")
                        .getAsJsonObject("scene")
                        .get("index")
                        .getAsInt();
        }
    }

    /** The scene a ref names, once the scene list is observed and contains it. */
    int requireScene(JsonObject ref) {
        int scene = sceneIndex(ref);
        if (!window.scenesObserved())
            throw Errors.notApplied(Reason.NOT_READY, "Scene bank has not reported its size yet.");
        if (scene >= window.sceneCount())
            throw Errors.targetNotFound(
                    ref, "Scene " + scene + " does not exist: the project has " + window.sceneCount() + " scene(s).");
        return scene;
    }

    /** The scene window that shows a scene ref. */
    int offsetOf(JsonObject ref) {
        return window.sceneWindowFor(requireScene(ref));
    }

    // ---- resolution inside an entered window ----

    Loc scene(JsonObject ref, int offset) {
        int scene = sceneIndex(ref);
        JsonObject current = sceneRef(scene, offset);
        if (!Refs.matches(ref, current, true))
            throw Errors.targetChanged("The scene was renamed since it was read.", current);
        return new Loc(-1, scene, offset, null, current);
    }

    private Loc locate(JsonObject ref, int offset) {
        int track = trackRefs.resolve(ref.getAsJsonObject("track"), false), scene = sceneIndex(ref);
        return new Loc(track, scene, offset, trackRefs.ref(track), sceneRef(scene, offset));
    }

    Loc slot(JsonObject ref, int offset) {
        Loc at = locate(ref, offset);
        JsonObject current = slotRef(at);
        if (!Refs.matches(ref, current, true))
            throw Errors.targetChanged("The scene was renamed since it was read.", current);
        return at;
    }

    /** The slot must hold a clip of the requested name. */
    Loc clip(JsonObject ref, int offset) {
        Loc at = locate(ref, offset);
        ClipLauncherSlot slot = api(at);
        if (!slot.hasContent().get()) throw Errors.targetNotFound(ref, "The slot holds no clip.");
        JsonObject current = clipRef(at, slot.name().get());
        if (!Refs.matches(ref, current, true))
            throw Errors.targetChanged("Another clip or scene name now sits at that slot.", current);
        return at;
    }

    // ---- entering the window, then resolving ----

    void enterScene(HostOperation op, JsonObject ref, boolean wait, Consumer<Loc> ready) {
        int offset = offsetOf(ref);
        lease.enter(op, offset, false, wait, () -> ready.accept(scene(ref, offset)));
    }

    void enterSlot(HostOperation op, JsonObject ref, boolean wait, Consumer<Loc> ready) {
        trackRefs.resolve(ref.getAsJsonObject("track"), false);
        int offset = offsetOf(ref);
        lease.enter(op, offset, false, wait, () -> ready.accept(slot(ref, offset)));
    }

    void enterClip(HostOperation op, JsonObject ref, boolean wait, Consumer<Loc> ready) {
        trackRefs.resolve(ref.getAsJsonObject("track"), false);
        int offset = offsetOf(ref);
        lease.enter(op, offset, false, wait, () -> ready.accept(clip(ref, offset)));
    }
}
