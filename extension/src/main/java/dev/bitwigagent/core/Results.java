package dev.bitwigagent.core;

import com.google.gson.*;
import dev.bitwigagent.host.ProjectState;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

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

    /** A write result: {outcome, verification exactly when applied}. */
    public static JsonObject outcome(Outcome outcome, Verification how) {
        JsonObject result = new JsonObject();
        stamp(result, outcome, how);
        return result;
    }

    /** The one applied result with nothing to verify against Bitwig: {@code ack} records that a hold was inspected. */
    public static JsonObject released() {
        JsonObject result = new JsonObject();
        result.addProperty("outcome", Outcome.APPLIED.wire());
        return result;
    }

    /** A write result whose outcome is statically not applied. */
    public static JsonObject outcome(Outcome outcome) {
        return outcome(outcome, null);
    }

    /** Sets {@code outcome} on a result built elsewhere; an applied one (and a native no-op) says how it was confirmed. */
    public static void stamp(JsonObject result, Outcome outcome, Verification how) {
        result.addProperty("outcome", outcome.wire());
        if (outcome == Outcome.APPLIED) Objects.requireNonNull(how, "an applied result needs a verification");
        if (how != null && (outcome == Outcome.APPLIED || outcome == Outcome.ALREADY_MATCHED)) how.put(result);
    }

    /** The worst outcome of {@code entries}, with the verification they share (the leaf rule). */
    public static JsonObject ofEntries(Iterable<JsonElement> entries) {
        JsonObject result = new JsonObject();
        stamp(result, worst(entries), entries);
        return result;
    }

    /**
     * Sets {@code outcome} on a result whose entries carry the confirmations. The leaf rule: when an applied result's
     * entries all name one verification it repeats it; entries that differ keep their own.
     */
    public static void stamp(JsonObject result, Outcome outcome, Iterable<JsonElement> entries) {
        result.addProperty("outcome", outcome.wire());
        if (outcome != Outcome.APPLIED) return;
        Set<String> seen = new HashSet<>();
        for (JsonElement entry : entries) {
            JsonObject e = entry.getAsJsonObject();
            if (e.has("verification")) seen.add(e.get("verification").getAsString());
        }
        if (seen.size() == 1) result.addProperty("verification", seen.iterator().next());
    }

    /**
     * The leaf rule: every applied object that has no outcome-bearing child says how it was confirmed; {@code ack}'s
     * release is the one exception. Asserted on every result a host operation completes (tests run with assertions).
     */
    public static boolean leafRuleHolds(JsonElement node) {
        if (node.isJsonArray()) {
            for (JsonElement child : node.getAsJsonArray()) if (!leafRuleHolds(child)) return false;
            return true;
        }
        if (!node.isJsonObject()) return true;
        JsonObject object = node.getAsJsonObject();
        boolean childOutcome = false;
        for (JsonElement child : object.asMap().values()) {
            if (!leafRuleHolds(child)) return false;
            childOutcome |= hasOutcome(child);
        }
        boolean applied = object.has("outcome") && outcomeOf(object) == Outcome.APPLIED;
        return !applied || childOutcome || object.has("verification") || object.has("released");
    }

    private static boolean hasOutcome(JsonElement node) {
        if (node.isJsonArray()) {
            for (JsonElement child : node.getAsJsonArray()) if (hasOutcome(child)) return true;
            return false;
        }
        if (!node.isJsonObject()) return false;
        JsonObject object = node.getAsJsonObject();
        if (object.has("outcome")) return true;
        for (JsonElement child : object.asMap().values()) if (hasOutcome(child)) return true;
        return false;
    }

    /** One field or entry: {requested, observed (only when it differs), outcome, reason?}. */
    public static JsonObject field(
            JsonElement requested, JsonElement observed, Outcome outcome, Reason reason, Verification how) {
        JsonObject field = new JsonObject();
        field.add("requested", requested == null ? JsonNull.INSTANCE : requested);
        if (observed != null && !observed.equals(requested)) field.add("observed", observed);
        stamp(field, outcome, how);
        if (reason != null) field.addProperty("reason", reason.wire());
        return field;
    }

    /** A field whose outcome is statically not applied. */
    public static JsonObject field(JsonElement requested, JsonElement observed, Outcome outcome, Reason reason) {
        return field(requested, observed, outcome, reason, null);
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
