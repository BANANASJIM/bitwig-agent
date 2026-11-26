package dev.bitwigagent.core;

import com.google.gson.*;
import dev.bitwigagent.host.ProjectState;

/** Result fragments shared by every domain. */
public final class Results {
    private Results() {}

    /** Which Bitwig process answered: pid plus display variables (null when unset). */
    public static JsonObject instance() {
        JsonObject result = new JsonObject();
        result.addProperty("pid", ProcessHandle.current().pid());
        ProcessHandle.current().parent().ifPresent(parent -> result.addProperty("parentPid", parent.pid()));
        result.addProperty("display", System.getenv("DISPLAY"));
        result.addProperty("waylandDisplay", System.getenv("WAYLAND_DISPLAY"));
        return result;
    }

    /** A write result: {outcome}. */
    public static JsonObject outcome(Outcome outcome) {
        JsonObject result = new JsonObject();
        result.addProperty("outcome", outcome.wire());
        return result;
    }

    /** One field or entry: {requested, observed (only when it differs), outcome, reason?}. */
    public static JsonObject field(JsonElement requested, JsonElement observed, Outcome outcome, Reason reason) {
        JsonObject field = new JsonObject();
        field.add("requested", requested == null ? JsonNull.INSTANCE : requested);
        if (observed != null && !observed.equals(requested)) field.add("observed", observed);
        field.addProperty("outcome", outcome.wire());
        if (reason != null) field.addProperty("reason", reason.wire());
        return field;
    }

    /**
     * A read unit: {value, display?, options?}. Its value is what a write takes back; display and options are present
     * exactly when the write also takes {display} or an option value.
     */
    public static JsonObject unit(JsonElement value, String display, JsonArray options) {
        JsonObject unit = new JsonObject();
        unit.add("value", value == null ? JsonNull.INSTANCE : value);
        if (display != null) unit.addProperty("display", display);
        if (options != null) unit.add("options", options);
        return unit;
    }

    /** The worst {@code outcome} of the entries; no entries already matched. */
    public static Outcome worst(Iterable<JsonElement> entries) {
        Outcome worst = Outcome.ALREADY_MATCHED;
        for (JsonElement entry : entries) worst = worst.worst(outcomeOf(entry.getAsJsonObject()));
        return worst;
    }

    public static Outcome outcomeOf(JsonObject result) {
        return Outcome.valueOf(result.get("outcome").getAsString().toUpperCase(java.util.Locale.ROOT));
    }

    public static JsonObject window(int offset, int capacity, int total) {
        JsonObject result = new JsonObject();
        result.addProperty("offset", offset);
        result.addProperty("capacity", capacity);
        result.addProperty("total", total);
        result.addProperty("complete", offset == 0 && total >= 0 && total <= capacity);
        return result;
    }

    public static JsonObject readiness(ProjectState.Readiness readiness) {
        JsonObject out = new JsonObject();
        if (readiness.ready == null) out.add("projectReady", JsonNull.INSTANCE);
        else out.addProperty("projectReady", readiness.ready);
        out.addProperty("projectReadyReason", readiness.reason);
        if (readiness.changedMsAgo != null) out.addProperty("projectChangedMsAgo", readiness.changedMsAgo);
        return out;
    }

    public static JsonElement modified(Boolean modified) {
        return modified == null ? JsonNull.INSTANCE : new JsonPrimitive(modified);
    }

    public static void addPlaybackSafety(JsonObject out, SafetyObservation safety) {
        out.addProperty("arrangerRecordEnabled", safety.recording);
        out.addProperty("arrangerAutomationWriteEnabled", safety.arrangerAutomation);
        out.addProperty("clipLauncherAutomationWriteEnabled", safety.launcherAutomation);
    }

    public static void addWarning(JsonObject result, String text) {
        if (!result.has("warnings") || !result.get("warnings").isJsonArray()) result.add("warnings", new JsonArray());
        result.getAsJsonArray("warnings").add(text);
    }
}
