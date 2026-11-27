package dev.bitwigagent.core;

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

    /** A ref located nothing; next names the read that returns refs of its variant. */
    public static RpcException targetNotFound(JsonObject ref, String message) {
        return new RpcException(Reason.TARGET_NOT_FOUND, message, dev.bitwigagent.core.Refs.read(ref), false);
    }

    /** A ref located an object whose compared fields differ; {@code current} is the ref of what is there now. */
    public static RpcException targetChanged(String message, JsonObject current) {
        return new RpcException(Reason.TARGET_CHANGED, message, null, false, current, null, null);
    }

    /** An expect did not match; {@code observed} holds the current values of the keys that differ. */
    public static RpcException expectMismatch(String message, JsonObject observed) {
        return new RpcException(Reason.TARGET_CHANGED, message, null, false, null, observed, null);
    }

    /** A refused batch: nothing was written, and {@code entries} says why each failing entry was refused. */
    public static RpcException entries(Reason reason, String message, JsonArray entries) {
        return new RpcException(reason, message, null, false, null, null, entries);
    }

    /** The wire error: {code, reason, message, next?, current?, observed?, entries?}. */
    public static JsonObject error(RpcException error) {
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
    public static JsonObject unknownResult(RpcException error) {
        return unknownResult(error, null);
    }

    /** As above; {@code inspect}, the method's read, is the {@code next} when the failure carries none. */
    public static JsonObject unknownResult(RpcException error, String inspect) {
        JsonObject out = new JsonObject();
        Results.unknown(out);
        out.addProperty("reason", error.reason.wire());
        out.addProperty("message", error.getMessage());
        String next = error.next != null ? error.next : inspect;
        if (next != null) out.addProperty("next", next);
        return out;
    }

    /** The failure a completed stage carries, unwrapped from its CompletionException. */
    public static RpcException cause(Throwable error) {
        Throwable cause = error instanceof java.util.concurrent.CompletionException && error.getCause() != null
                ? error.getCause()
                : error;
        return cause instanceof RpcException
                ? (RpcException) cause
                : notApplied(Reason.INTERNAL, String.valueOf(cause.getMessage()));
    }

    /** A stage's own failure as its entry: an error before any write is not_applied, a possible change unknown. */
    public static JsonObject entryResult(Throwable error) {
        RpcException rpc = cause(error);
        JsonObject out = unknownResult(rpc);
        if (!rpc.unknown) Results.notApplied(out);
        return out;
    }

    /** An unknown result a module built itself gets the method's read as {@code next}; its own {@code next} wins. */
    public static JsonObject withInspect(JsonObject result, String inspect) {
        if (inspect != null
                && result.has("outcome")
                && Results.outcomeOf(result) == Outcome.UNKNOWN
                && !result.has("next")) result.addProperty("next", inspect);
        return result;
    }
}
