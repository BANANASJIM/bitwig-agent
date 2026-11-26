package dev.bitwigagent.host;

import static dev.bitwigagent.core.HostLimits.TRACKS;

import com.google.gson.JsonObject;
import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.core.Refs;

/** Resolves track refs against the track window and builds the ref of a window slot. */
public final class TrackRefs {
    private final TrackWindow window;

    public TrackRefs(TrackWindow window) {
        this.window = window;
    }

    /** The window slot of the track a ref locates; its compared fields are the caller's to check. */
    public int locate(JsonObject ref) {
        String id = ref.get("id").getAsString();
        if (!window.observed()) throw Errors.notApplied(Reason.NOT_READY, "Track bank has not reported its size yet.");
        for (int slot = 0; slot < TRACKS; slot++) if (window.trackMatches(id, window.at(slot))) return slot;
        if (window.offset() == 0 && window.total() <= TRACKS)
            throw Errors.targetNotFound(ref, "The track is not in the project.");
        throw Errors.notApplied(Reason.OUTSIDE_WINDOW, "The track is outside the current " + TRACKS + "-track window.");
    }

    /** The window slot of the track a ref names, refused as stale when the track there no longer matches it. */
    public int resolve(JsonObject ref, boolean target) {
        int slot = locate(ref);
        JsonObject current = ref(slot);
        if (!Refs.matches(ref, current, target))
            throw Errors.targetChanged("The track was renamed since it was read.", current);
        return slot;
    }

    public JsonObject ref(int slot) {
        return Refs.track(
                window.at(slot).channelId().get(), window.at(slot).name().get());
    }
}
