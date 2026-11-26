package dev.bitwigagent;

import com.bitwig.extension.controller.api.Track;
import com.google.gson.*;
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
    private final LauncherModule launcher;

    ContextModule(
            Scheduler scheduler,
            TrackWindow window,
            ProjectState projectState,
            TransportSafety transportSafety,
            TransportModule transport,
            MixModule mix,
            LauncherModule launcher) {
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
    public void validate(String method, JsonObject params) {
        validateParams(method, params);
    }

    static void validateParams(String method, JsonObject p) {
        if (!METHODS.contains(method)) throw Errors.notApplied(ErrorCode.UNKNOWN_METHOD, "Unknown context method.");
        Params.fields(p, Set.of(), Set.of());
    }

    @Override
    public void dispatch(HostOperation op, String method, JsonObject p) {
        scheduler.check(op);
        op.result.complete(snapshot());
    }

    /** Unobserved values are JSON null, never guessed. */
    private JsonObject snapshot() {
        JsonObject out = Results.observed();
        for (Map.Entry<String, JsonElement> e : transport.basics().entrySet()) out.add(e.getKey(), e.getValue());
        out.add("projectModified", Results.modified(projectState.modified()));
        out.addProperty("engineActive", projectState.engineActive());
        for (Map.Entry<String, JsonElement> e :
                Results.readiness(projectState.readiness()).entrySet()) out.add(e.getKey(), e.getValue());
        out.add("instance", Results.instance());
        Results.addPlaybackSafety(out, transportSafety.observation());
        out.add(
                "sceneCount",
                window.observed()
                        ? new JsonPrimitive(
                                window.bank().sceneBank().itemCount().get())
                        : JsonNull.INSTANCE);
        out.add("tracks", JsonNull.INSTANCE);
        out.add("selectedTrack", JsonNull.INSTANCE);
        out.add("cursorClip", JsonNull.INSTANCE);
        if (!window.observed()) return out;
        JsonArray tracks = new JsonArray();
        JsonElement selectedTrack = JsonNull.INSTANCE, cursorClip = JsonNull.INSTANCE;
        for (int t = 0; t < HostLimits.TRACKS; t++) {
            Track track = window.at(t);
            if (!track.exists().get()) continue;
            JsonObject item = mix.brief(t);
            JsonElement id = item.get("trackId");
            item.add("launcherSlots", launcher.occupiedSlots(t, track));
            tracks.add(item);
            if (item.get("selected").isJsonPrimitive()
                    && item.get("selected").getAsBoolean()
                    && selectedTrack.isJsonNull()) selectedTrack = id;
            int slot = launcher.cursorSlotIndex(t, track);
            if (slot >= 0) {
                JsonObject clip = new JsonObject();
                clip.add("trackId", id);
                clip.addProperty("slotIndex", slot);
                clip.addProperty("name", launcher.slotName(t, slot));
                cursorClip = clip;
            }
        }
        out.add("tracks", tracks);
        out.add("selectedTrack", selectedTrack);
        out.add("cursorClip", cursorClip);
        return out;
    }
}
