package dev.bitwigagent.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.Map;
import java.util.Objects;

/** Builds the ref of every kind and compares a requested ref with the one now found at its location. */
public final class Refs {
    private Refs() {}

    private static final class Roles {
        static final Map<String, MethodSpecs.RefRoles> ROLES =
                MethodSpecs.load().refRoles();
    }

    /**
     * Whether {@code current}, the ref now at the location {@code requested} names, is still that object: every
     * locating and compared field equal, ancestors compared as ancestors, and the target-only fields that {@code
     * requested} carries too when it names the object being written.
     */
    public static boolean matches(JsonObject requested, JsonObject current, boolean target) {
        MethodSpecs.RefRoles roles = Roles.ROLES.get(kind(requested));
        if (roles == null || !Objects.equals(kind(requested), kind(current))) return false;
        for (String field : roles.locate) if (!sameAncestor(requested.get(field), current.get(field))) return false;
        for (String field : roles.compared)
            if (!value(requested.get(field)).equals(value(current.get(field)))) return false;
        if (target)
            for (String field : roles.targetOnly)
                if (requested.has(field) && !requested.get(field).equals(current.get(field))) return false;
        return true;
    }

    private static boolean sameAncestor(JsonElement requested, JsonElement current) {
        if (requested == null || current == null) return requested == current;
        if (!requested.isJsonObject() || !current.isJsonObject()) return requested.equals(current);
        JsonObject left = requested.getAsJsonObject(), right = current.getAsJsonObject();
        if (left.has("kind")) return matches(left, right, false);
        if (!left.keySet().equals(right.keySet())) return false;
        for (String key : left.keySet()) if (!sameAncestor(left.get(key), right.get(key))) return false;
        return true;
    }

    private static JsonElement value(JsonElement element) {
        return element == null ? JsonNull.INSTANCE : element;
    }

    public static String kind(JsonObject ref) {
        JsonElement kind = ref.get("kind");
        return kind != null && kind.isJsonPrimitive() ? kind.getAsString() : null;
    }

    private static JsonObject of(String kind) {
        JsonObject ref = new JsonObject();
        ref.addProperty("kind", kind);
        return ref;
    }

    public static JsonObject track(String id, String name) {
        JsonObject ref = of("track");
        ref.addProperty("id", id);
        ref.addProperty("name", name);
        return ref;
    }

    /** {@code name} is null for an unnamed scene. */
    public static JsonObject scene(int index, String name) {
        JsonObject ref = of("scene");
        ref.addProperty("index", index);
        ref.add("name", name == null ? JsonNull.INSTANCE : new JsonPrimitive(name));
        return ref;
    }

    public static JsonObject slot(JsonObject track, JsonObject scene) {
        JsonObject ref = of("slot");
        ref.add("track", track);
        ref.add("scene", scene);
        return ref;
    }

    public static JsonObject launcherClip(JsonObject track, JsonObject scene, String name) {
        JsonObject ref = of("clip");
        ref.add("track", track);
        JsonObject slot = new JsonObject();
        slot.add("scene", scene);
        ref.add("slot", slot);
        ref.addProperty("name", name);
        return ref;
    }

    public static JsonObject arrangerClip(JsonObject track, double startBeats, String name) {
        JsonObject ref = of("clip");
        ref.add("track", track);
        ref.addProperty("lane", "main");
        ref.addProperty("startBeats", startBeats);
        ref.addProperty("name", name);
        return ref;
    }

    /** {@code parent} is the track ref of a root device or the chain ref of a device inside a chain. */
    public static JsonObject device(JsonObject parent, int index, String name) {
        JsonObject ref = of("device");
        ref.add(kind(parent), parent);
        ref.addProperty("index", index);
        ref.addProperty("name", name);
        return ref;
    }

    public static JsonObject chain(JsonObject device, String id, String name) {
        return owned("chain", device, id, name);
    }

    public static JsonObject modulator(JsonObject device, String id, String name) {
        return owned("modulator", device, id, name);
    }

    public static JsonObject gridModule(JsonObject device, String id, String name) {
        return owned("gridModule", device, id, name);
    }

    private static JsonObject owned(String kind, JsonObject device, String id, String name) {
        JsonObject ref = of(kind);
        ref.add("device", device);
        ref.addProperty("id", id);
        ref.addProperty("name", name);
        return ref;
    }

    public static JsonObject remotePage(JsonObject device, int index, String name) {
        JsonObject ref = of("remotePage");
        ref.add("device", device);
        ref.addProperty("index", index);
        ref.addProperty("name", name);
        return ref;
    }

    /** {@code name} is omitted when null. */
    public static JsonObject parameter(JsonObject owner, String id, String name) {
        JsonObject ref = of("parameter");
        ref.add("owner", owner);
        ref.addProperty("id", id);
        if (name != null) ref.addProperty("name", name);
        return ref;
    }

    public static JsonObject send(JsonObject track, int index, String name) {
        JsonObject ref = of("send");
        ref.add("track", track);
        ref.addProperty("index", index);
        ref.addProperty("name", name);
        return ref;
    }

    /** {@code modulatorId} and {@code moduleId} are omitted when null. */
    public static JsonObject sidechain(JsonObject device, int index, String id, String modulatorId, String moduleId) {
        JsonObject ref = of("sidechain");
        ref.add("device", device);
        ref.addProperty("index", index);
        ref.addProperty("id", id);
        if (modulatorId != null) ref.addProperty("modulatorId", modulatorId);
        if (moduleId != null) ref.addProperty("moduleId", moduleId);
        return ref;
    }

    public static JsonObject marker(int index, String name, double beat) {
        JsonObject ref = of("marker");
        ref.addProperty("index", index);
        ref.addProperty("name", name);
        ref.addProperty("beat", beat);
        return ref;
    }
}
