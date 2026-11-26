package dev.bitwigagent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** The only place failures are built and serialized. */
public final class Errors {
    private Errors() {}

    public static RpcException notApplied(Reason reason, String message) {
        return new RpcException(reason, message, null, false);
    }

    public static RpcException notApplied(Reason reason, String message, String next) {
        return new RpcException(reason, message, next, false);
    }

    /** A failure after a host change may have happened; it is reported as a result with outcome unknown. */
    public static RpcException unknown(Reason reason, String message) {
        return new RpcException(reason, message, null, true);
    }

    public static RpcException of(Reason reason, String message, boolean changed) {
        return new RpcException(reason, message, null, changed);
    }

    public static RpcException invalid(String message) {
        return notApplied(Reason.INVALID_PARAMS, message);
    }

    /** A ref located an object whose compared fields differ; {@code current} is the ref of what is there now. */
    static RpcException targetChanged(String message, JsonObject current) {
        return new RpcException(Reason.TARGET_CHANGED, message, null, false, current, null, null);
    }

    /** An expect did not match; {@code observed} holds the current values of the keys that differ. */
    static RpcException expectMismatch(String message, JsonObject observed) {
        return new RpcException(Reason.TARGET_CHANGED, message, null, false, null, observed, null);
    }

    /** A refused batch: nothing was written, and {@code entries} says why each failing entry was refused. */
    static RpcException entries(Reason reason, String message, JsonArray entries) {
        return new RpcException(reason, message, null, false, null, null, entries);
    }

    /** The wire error: {code, reason, message, next?, current?, observed?, entries?}. */
    static JsonObject error(RpcException error) {
        JsonObject out = new JsonObject();
        out.addProperty("code", error.code());
        out.addProperty("reason", error.reason.wire());
        out.addProperty("message", error.getMessage());
        if (error.next != null) out.addProperty("next", error.next);
        if (error.current != null) out.add("current", error.current);
        if (error.observed != null) out.add("observed", error.observed);
        if (error.entries != null) out.add("entries", error.entries);
        return out;
    }

    /** The wire result of a failure that may have changed state: {outcome: unknown, reason, message, next?}. */
    static JsonObject unknownResult(RpcException error) {
        JsonObject out = new JsonObject();
        out.addProperty("outcome", Outcome.UNKNOWN.wire());
        out.addProperty("reason", error.reason.wire());
        out.addProperty("message", error.getMessage());
        if (error.next != null) out.addProperty("next", error.next);
        return out;
    }
}
