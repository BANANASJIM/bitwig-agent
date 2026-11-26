package dev.bitwigagent;

import com.google.gson.*;

/** Result fragments shared by every domain. */
final class Results {
    private Results() {}

    /** Which Bitwig process answered: pid plus display variables (null when unset). */
    static JsonObject instance() {
        JsonObject result = new JsonObject();
        result.addProperty("pid", ProcessHandle.current().pid());
        ProcessHandle.current().parent().ifPresent(parent -> result.addProperty("parentPid", parent.pid()));
        result.addProperty("display", System.getenv("DISPLAY"));
        result.addProperty("waylandDisplay", System.getenv("WAYLAND_DISPLAY"));
        return result;
    }

    static JsonObject observed() {
        JsonObject result = new JsonObject();
        result.addProperty("status", "observed");
        return result;
    }

    static JsonObject window(int offset, int capacity, int total) {
        JsonObject result = new JsonObject();
        result.addProperty("offset", offset);
        result.addProperty("capacity", capacity);
        result.addProperty("total", total);
        result.addProperty("complete", offset == 0 && total >= 0 && total <= capacity);
        return result;
    }

    static JsonObject readiness(ProjectState.Readiness readiness) {
        JsonObject out = new JsonObject();
        if (readiness.ready == null) out.add("projectReady", JsonNull.INSTANCE);
        else out.addProperty("projectReady", readiness.ready);
        out.addProperty("projectReadyReason", readiness.reason);
        if (readiness.changedMsAgo != null) out.addProperty("projectChangedMsAgo", readiness.changedMsAgo);
        return out;
    }

    static JsonElement modified(Boolean modified) {
        return modified == null ? JsonNull.INSTANCE : new JsonPrimitive(modified);
    }

    static void addPlaybackSafety(JsonObject out, SafetyObservation safety) {
        out.addProperty("arrangerRecordEnabled", safety.recording);
        out.addProperty("arrangerAutomationWriteEnabled", safety.arrangerAutomation);
        out.addProperty("clipLauncherAutomationWriteEnabled", safety.launcherAutomation);
    }

    static void addWarning(JsonObject result, String text) {
        if (!result.has("warnings") || !result.get("warnings").isJsonArray()) result.add("warnings", new JsonArray());
        result.getAsJsonArray("warnings").add(text);
    }
}
