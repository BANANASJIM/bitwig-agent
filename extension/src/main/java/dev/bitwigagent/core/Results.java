package dev.bitwigagent.core;

import com.google.gson.*;
import dev.bitwigagent.host.ProjectState;
import java.util.HashSet;
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

    /** A write result: {outcome, verification exactly when applied (and a native no-op)}. */
    public static JsonObject outcome(Outcome outcome, Verification how) {
        JsonObject result = new JsonObject();
        stamp(result, outcome, how);
        return result;
    }

    /** The one applied result with nothing to verify against Bitwig: {@code ack} records that a hold was inspected. */
    public static JsonObject released() {
        return bare(new JsonObject(), Outcome.APPLIED);
    }

    /** An already-matched result whose match was not read back through a {@link Verification}. */
    public static JsonObject alreadyMatched() {
        return bare(new JsonObject(), Outcome.ALREADY_MATCHED);
    }

    /** A dispatched write that cannot be observed. */
    public static JsonObject sent() {
        return bare(new JsonObject(), Outcome.SENT);
    }

    public static JsonObject notApplied() {
        return notApplied(new JsonObject());
    }

    /** Marks {@code result} not applied; any verification it carried goes. */
    public static JsonObject notApplied(JsonObject result) {
        return bare(result, Outcome.NOT_APPLIED);
    }

    public static JsonObject unknown() {
        return unknown(new JsonObject());
    }

    /** Marks {@code result} unknown; any verification it carried goes. */
    public static JsonObject unknown(JsonObject result) {
        return bare(result, Outcome.UNKNOWN);
    }

    /** Sets {@code outcome} on a result built elsewhere; an applied one (and a native no-op) says how it was confirmed. */
    public static void stamp(JsonObject result, Outcome outcome, Verification how) {
        bare(result, outcome);
        if (outcome == Outcome.APPLIED || outcome == Outcome.ALREADY_MATCHED) how.put(result);
    }

    /** A readback or capability block that is not itself a write result: it only says how it was confirmed. */
    public static JsonObject readBy(JsonObject block, Verification how) {
        how.put(block);
        return block;
    }

    /** An entry takes the outcome and the verification of the single write that produced it. */
    public static void copyOutcome(JsonObject entry, JsonObject one) {
        bare(entry, outcomeOf(one));
        if (one.has("verification")) entry.add("verification", one.get("verification"));
    }

    private static JsonObject bare(JsonObject result, Outcome outcome) {
        result.addProperty("outcome", outcome.wire());
        result.remove("verification");
        return result;
    }

    /** The worst outcome of {@code entries}, with the verification they share (the leaf rule). */
    public static JsonObject ofEntries(Iterable<JsonElement> entries) {
        JsonObject result = new JsonObject();
        stamp(result, worst(entries), entries);
        return result;
    }

    /**
     * Sets {@code outcome} on a result whose entries carry the confirmations. The leaf rule: when an applied result's
     * applied entries all name one verification it repeats it; entries that differ keep their own.
     */
    public static void stamp(JsonObject result, Outcome outcome, Iterable<JsonElement> entries) {
        bare(result, outcome);
        if (outcome != Outcome.APPLIED) return;
        Set<String> seen = new HashSet<>();
        for (JsonElement entry : entries) {
            JsonObject e = entry.getAsJsonObject();
            if (outcomeOf(e) == Outcome.APPLIED && e.has("verification"))
                seen.add(e.get("verification").getAsString());
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
        JsonObject field = field(requested, observed);
        stamp(field, outcome, how);
        if (reason != null) field.addProperty("reason", reason.wire());
        return field;
    }

    /** A field whose write may have landed but was not confirmed. */
    public static JsonObject unknownField(JsonElement requested, JsonElement observed, Reason reason) {
        JsonObject field = unknown(field(requested, observed));
        field.addProperty("reason", reason.wire());
        return field;
    }

    /** A field that was not written. */
    public static JsonObject notAppliedField(JsonElement requested, JsonElement observed, Reason reason) {
        JsonObject field = notApplied(field(requested, observed));
        field.addProperty("reason", reason.wire());
        return field;
    }

    private static JsonObject field(JsonElement requested, JsonElement observed) {
        JsonObject field = new JsonObject();
        field.add("requested", requested == null ? JsonNull.INSTANCE : requested);
        if (observed != null && !observed.equals(requested)) field.add("observed", observed);
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
