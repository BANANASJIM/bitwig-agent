package dev.bitwigagent;

import com.bitwig.extension.controller.api.Track;
import com.google.gson.*;
import java.util.Map;
import java.util.Set;

/** One read-only call for orientation: project, safety, tracks, occupied launcher slots, selected track and cursor clip. Never selects. */
final class ContextModule implements HostModule {
    static final Set<String> METHODS = Set.of("context.get");
    private final HostServices s;
    private final TransportModule transport;
    private final MixModule mix;
    private final LauncherModule launcher;

    ContextModule(HostServices services, TransportModule transport, MixModule mix, LauncherModule launcher) {
        s = services; this.transport = transport; this.mix = mix; this.launcher = launcher;
    }

    @Override public Set<String> methods() { return METHODS; }
    @Override public void validate(String method, JsonObject params) { validateParams(method, params); }
    static void validateParams(String method, JsonObject p) {
        if (!METHODS.contains(method)) throw HostServices.failure("unknown_method", "Unknown context method.");
        Params.fields(p, Set.of(), Set.of());
    }

    @Override public void dispatch(HostOperation op, String method, JsonObject p) {
        s.check(op);
        op.result.complete(snapshot());
    }

    /** Unobserved values are JSON null, never guessed. */
    private JsonObject snapshot() {
        JsonObject out = HostServices.observed();
        for (Map.Entry<String, JsonElement> e : transport.basics().entrySet()) out.add(e.getKey(), e.getValue());
        out.add("projectModified", s.projectModifiedJson());
        out.addProperty("engineActive", s.engineActive());
        for (Map.Entry<String, JsonElement> e : s.projectReadiness().entrySet()) out.add(e.getKey(), e.getValue());
        out.add("instance", HostServices.instance());
        for (Map.Entry<String, JsonElement> e : s.playbackSafety().entrySet()) out.add(e.getKey(), e.getValue());
        out.add("sceneCount", s.tracksObserved ? new JsonPrimitive(s.tracks.sceneBank().itemCount().get()) : JsonNull.INSTANCE);
        out.add("tracks", JsonNull.INSTANCE); out.add("selectedTrack", JsonNull.INSTANCE); out.add("cursorClip", JsonNull.INSTANCE);
        if (!s.tracksObserved) return out;
        JsonArray tracks = new JsonArray();
        JsonElement selectedTrack = JsonNull.INSTANCE, cursorClip = JsonNull.INSTANCE;
        for (int t = 0; t < HostServices.TRACKS; t++) {
            Track track = (Track) s.tracks.getItemAt(t);
            if (!track.exists().get()) continue;
            JsonObject item = mix.brief(t);
            JsonElement id = item.get("trackId");
            item.add("launcherSlots", launcher.occupiedSlots(t, track));
            tracks.add(item);
            if (item.get("selected").isJsonPrimitive() && item.get("selected").getAsBoolean() && selectedTrack.isJsonNull()) selectedTrack = id;
            int slot = launcher.cursorSlotIndex(t, track);
            if (slot >= 0) {
                JsonObject clip = new JsonObject();
                clip.add("trackId", id); clip.addProperty("slotIndex", slot);
                clip.addProperty("name", launcher.slotName(t, slot));
                cursorClip = clip;
            }
        }
        out.add("tracks", tracks); out.add("selectedTrack", selectedTrack); out.add("cursorClip", cursorClip);
        return out;
    }
}
