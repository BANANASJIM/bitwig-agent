package dev.bitwigagent.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.Map;

/** Whether a read-back value counts as the requested one; write verification and expect share it. */
public final class ValueMatch {
    /** A normalized host parameter: within one host step. */
    public static final ValueMatch NORMALIZED = new ValueMatch(false, HostLimits.TOLERANCE);
    /** Integers, enums, booleans, strings, colours, routing entries and hashes: equal JSON values. */
    public static final ValueMatch EXACT = new ValueMatch(true, 0);

    private final boolean exact;
    private final double tolerance;

    private ValueMatch(boolean exact, double tolerance) {
        this.exact = exact;
        this.tolerance = tolerance;
    }

    /** A raw float, within the tolerance its domain already verifies with. */
    public static ValueMatch raw(double tolerance) {
        return new ValueMatch(false, tolerance);
    }

    public boolean matches(JsonElement requested, JsonElement observed) {
        if (requested == null || observed == null) return false;
        if (exact) return requested.equals(observed);
        if (!number(requested) || !number(observed)) return false;
        double value = observed.getAsDouble();
        return Double.isFinite(value) && Math.abs(value - requested.getAsDouble()) <= tolerance;
    }

    private static boolean number(JsonElement element) {
        return element.isJsonPrimitive() && ((JsonPrimitive) element).isNumber();
    }

    /** An expect names at least one field. */
    public static void requireNamed(JsonObject params) {
        if (params.has("expect") && params.getAsJsonObject("expect").size() == 0)
            throw Errors.invalid("expect names at least one field.");
    }

    /** Every key of an expect must match the value observed now, by its key's rule; otherwise nothing is written. */
    public static void requireExpected(JsonObject expect, JsonObject observed, Map<String, ValueMatch> rules) {
        JsonObject differs = new JsonObject();
        for (String key : expect.keySet()) {
            JsonElement now = observed.get(key);
            if (!rules.get(key).matches(expect.get(key), now)) differs.add(key, now == null ? JsonNull.INSTANCE : now);
        }
        if (differs.size() > 0)
            throw Errors.expectMismatch("The current values differ from expect; nothing was written.", differs);
    }
}
