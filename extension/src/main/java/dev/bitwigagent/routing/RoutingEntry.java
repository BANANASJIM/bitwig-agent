package dev.bitwigagent.routing;

import com.google.gson.JsonObject;
import java.util.Set;

/**
 * One chooser entry in domain terms: none, another track's tap or input (by track UUID), a project note source, a
 * hardware input (by its stable id, with a display name), or anything else (other, by exact name and native type).
 */
public final class RoutingEntry {
    public static final Set<String> TRACK_KINDS =
            Set.of("track_audio_input", "track_note_input", "pre_fader", "post_fader", "track_notes");
    public static final Set<String> HARDWARE_KINDS = Set.of("hardware_audio_input", "hardware_note_input");
    public static final String OTHER = "other";

    public final String kind, track, id, name, nativeType;

    private RoutingEntry(String kind, String track, String id, String name, String nativeType) {
        this.kind = kind;
        this.track = track;
        this.id = id;
        this.name = name;
        this.nativeType = nativeType;
    }

    public static RoutingEntry none() {
        return new RoutingEntry("none", null, null, null, null);
    }

    /** A project note source (all_ins, computer_keyboard, onscreen_keyboard). */
    public static RoutingEntry source(String kind) {
        return new RoutingEntry(kind, null, null, null, null);
    }

    public static RoutingEntry track(String kind, String trackId) {
        return new RoutingEntry(kind, trackId, null, null, null);
    }

    public static RoutingEntry hardware(String kind, String id, String name) {
        return new RoutingEntry(kind, null, id, name, null);
    }

    public static RoutingEntry other(String name, String nativeType) {
        return new RoutingEntry(OTHER, null, null, name, nativeType);
    }

    /** The entry a validated request or expect names. */
    public static RoutingEntry of(JsonObject entry) {
        String kind = entry.get("kind").getAsString();
        String track =
                entry.has("track") ? entry.getAsJsonObject("track").get("id").getAsString() : null;
        String id = entry.has("id") ? entry.get("id").getAsString() : null;
        String name = entry.has("name") ? entry.get("name").getAsString() : null;
        String nativeType = entry.has("nativeType") ? entry.get("nativeType").getAsString() : null;
        return new RoutingEntry(kind, track, id, name, nativeType);
    }

    public JsonObject json() {
        JsonObject out = new JsonObject();
        out.addProperty("kind", kind);
        if (track != null) {
            JsonObject ref = new JsonObject();
            ref.addProperty("kind", "track");
            ref.addProperty("id", track);
            out.add("track", ref);
        }
        if (id != null) out.addProperty("id", id);
        if (name != null) out.addProperty("name", name);
        if (nativeType != null) out.addProperty("nativeType", nativeType);
        return out;
    }

    /**
     * Compare-and-set identity, with this entry as the expected one: same kind, and the same track UUID or hardware
     * id (case-insensitive), or for kind other the exact name and, when expected, the native type. Display names of
     * tracks and hardware are not compared.
     */
    public boolean matches(RoutingEntry observed) {
        if (!kind.equals(observed.kind)) return false;
        if (track != null) return track.equalsIgnoreCase(observed.track == null ? "" : observed.track);
        if (HARDWARE_KINDS.contains(kind)) return id.equalsIgnoreCase(observed.id == null ? "" : observed.id);
        if (kind.equals(OTHER))
            return name.equals(observed.name) && (nativeType == null || nativeType.equals(observed.nativeType));
        return true;
    }

    @Override
    public String toString() {
        return json().toString();
    }
}
