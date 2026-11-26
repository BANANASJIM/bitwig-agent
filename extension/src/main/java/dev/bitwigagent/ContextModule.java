package dev.bitwigagent;

import com.bitwig.extension.controller.api.Track;
import com.google.gson.*;
import dev.bitwigagent.core.Refs;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** One read-only call for orientation: project, safety, tracks, occupied launcher slots, selected track and cursor clip. Never selects. */
final class ContextModule implements HostModule {
    static final Set<String> METHODS = Set.of("context.get");
    private final Scheduler scheduler;
    private final TrackWindow window;
    private final ProjectState projectState;
    private final TransportSafety transportSafety;
    private final TransportModule transport;
    private final MixModule mix;
    private final LauncherState launcher;

    ContextModule(
            Scheduler scheduler,
            TrackWindow window,
            ProjectState projectState,
            TransportSafety transportSafety,
            TransportModule transport,
            MixModule mix,
            LauncherState launcher) {
        this.scheduler = scheduler;
        this.window = window;
        this.projectState = projectState;
        this.transportSafety = transportSafety;
        this.transport = transport;
        this.mix = mix;
        this.launcher = launcher;
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
        JsonObject out = header();
        if (!window.observed() || !window.scenesObserved()) {
            op.result.complete(out);
            return;
        }
        // The window rests at scene 0, so the slots listed are scenes 0-15; a reader waits for a holder elsewhere.
        launcher.targets.lease.enter(op, 0, false, true, () -> {
            Integer cursorTrack = tracks(out);
            if (cursorTrack == null) op.result.complete(out);
            else findCursorClip(op, out, cursorTrack);
        });
    }

    /** Unobserved values are JSON null, never guessed. */
    private JsonObject header() {
        JsonObject out = new JsonObject();
        for (Map.Entry<String, JsonElement> e : transport.basics().entrySet()) out.add(e.getKey(), e.getValue());
        out.add("projectModified", Results.modified(projectState.modified()));
        out.addProperty("engineActive", projectState.engineActive());
        for (Map.Entry<String, JsonElement> e :
                Results.readiness(projectState.readiness()).entrySet()) out.add(e.getKey(), e.getValue());
        out.add("instance", Results.instance());
        Results.addPlaybackSafety(out, transportSafety.observation());
        out.add(
                "sceneCount",
                window.observed() && window.scenesObserved()
                        ? new JsonPrimitive(window.sceneCount())
                        : JsonNull.INSTANCE);
        out.add("tracks", JsonNull.INSTANCE);
        out.add("selectedTrack", JsonNull.INSTANCE);
        out.add("cursorClip", JsonNull.INSTANCE);
        return out;
    }

    /** Adds the track rows and the selected track; returns the cursor clip's track slot, or null when it has none. */
    private Integer tracks(JsonObject out) {
        JsonArray tracks = new JsonArray();
        JsonElement selectedTrack = JsonNull.INSTANCE;
        Integer cursorTrack = null;
        for (int t = 0; t < HostLimits.TRACKS; t++) {
            Track track = window.at(t);
            if (!track.exists().get()) continue;
            JsonObject item = mix.brief(t);
            item.add("launcherSlots", occupiedSlots(t));
            tracks.add(item);
            if (item.get("selected").isJsonPrimitive()
                    && item.get("selected").getAsBoolean()
                    && selectedTrack.isJsonNull())
                selectedTrack = Refs.track(track.channelId().get(), track.name().get());
            if (cursorTrack == null
                    && launcher.clipExistsReady
                    && launcher.clipTrack.exists().get()
                    && window.trackMatches(launcher.clipTrack.channelId().get(), track)) cursorTrack = t;
        }
        out.add("tracks", tracks);
        out.add("selectedTrack", selectedTrack);
        out.addProperty("launcherSlotsComplete", window.sceneCount() <= HostLimits.SCENES);
        return cursorTrack;
    }

    /** Occupied slots of scenes 0-15 of one track as {ref, playing}; null while any slot is unobserved. */
    private JsonArray occupiedSlots(int t) {
        JsonArray out = new JsonArray();
        JsonObject trackRef =
                Refs.track(window.at(t).channelId().get(), window.at(t).name().get());
        for (int j = 0; j < Math.min(HostLimits.SCENES, window.sceneCount()); j++) {
            LauncherState.Slot slot = launcher.slots[t][j];
            if (!slot.ready()) return null;
            if (!slot.api.hasContent().get()) continue;
            JsonObject e = new JsonObject();
            e.add("ref", clipRef(t, trackRef, j, 0));
            e.addProperty("playing", slot.api.isPlaying().get());
            out.add(e);
        }
        return out;
    }

    private JsonObject clipRef(int t, JsonObject trackRef, int scene, int offset) {
        LauncherTargets.Loc at =
                new LauncherTargets.Loc(t, scene, offset, trackRef, launcher.targets.sceneRef(scene, offset));
        return launcher.clipRef(launcher.target(at));
    }

    // ---- the cursor clip, wherever its scene is ----

    /** The cursor clip's slot in the shown window as a clip ref; null when it is elsewhere or unobserved. */
    private JsonObject shownCursorClip(int t) {
        Track track = window.at(t);
        JsonObject trackRef = Refs.track(track.channelId().get(), track.name().get());
        int offset = window.sceneOffset();
        for (int j = 0; j < Math.min(HostLimits.SCENES, window.sceneCount() - offset); j++) {
            Boolean selected =
                    launcher.selected(launcher.slots[t][j], track.channelId().get());
            if (Boolean.TRUE.equals(selected)) return clipRef(t, trackRef, offset + j, offset);
        }
        return null;
    }

    /** Looks at the window shown first, then at the one the cursor's own scene names, then at every other window. */
    private void findCursorClip(HostOperation op, JsonObject out, int t) {
        JsonObject shown = shownCursorClip(t);
        if (shown != null || window.sceneCount() <= HostLimits.SCENES) {
            out.add("cursorClip", shown == null ? JsonNull.INSTANCE : shown);
            op.result.complete(out);
            return;
        }
        List<Integer> visit = new ArrayList<>();
        int hinted = launcher.cursorScene();
        if (hinted >= 0 && hinted < window.sceneCount()) visit.add(window.sceneWindowFor(hinted));
        for (int scene = HostLimits.SCENES; scene < window.sceneCount(); scene += HostLimits.SCENES)
            if (!visit.contains(window.sceneWindowFor(scene))) visit.add(window.sceneWindowFor(scene));
        visit.remove(Integer.valueOf(0));
        scan(op, out, t, visit, 0);
    }

    private void scan(HostOperation op, JsonObject out, int t, List<Integer> visit, int i) {
        if (i == visit.size()) {
            op.result.complete(out);
            return;
        }
        launcher.targets.lease.moveWhenFree(op, visit.get(i), () -> {
            JsonObject found = shownCursorClip(t);
            if (found == null) scan(op, out, t, visit, i + 1);
            else {
                out.add("cursorClip", found);
                op.result.complete(out);
            }
        });
    }
}
