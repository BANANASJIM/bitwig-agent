package dev.bitwigagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import dev.bitwigagent.core.HostOperation;
import dev.bitwigagent.core.Outcome;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.core.RpcException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Assertions on what a module reports: errors (nothing changed) and results with an outcome. */
public final class RpcAssert {
    private RpcAssert() {}

    /** A synchronous error with {@code reason}; an error always means nothing changed. */
    public static RpcException assertRpcError(String reason, Runnable action) {
        RpcException error = assertThrows(RpcException.class, action::run, "expected " + reason);
        assertEquals(reason, error.reason.wire(), "error reason: " + error.getMessage());
        assertFalse(error.unknown, "a synchronous error is raised before any host change");
        return error;
    }

    public static RpcException assertInvalidParams(Runnable action) {
        return assertRpcError(Reason.INVALID_PARAMS.wire(), action);
    }

    public static RpcException rejection(CompletableFuture<?> result) {
        assertTrue(result.isDone(), "the future must already be settled");
        CompletionException completion = assertThrows(CompletionException.class, result::join);
        return assertInstanceOf(RpcException.class, completion.getCause());
    }

    public static RpcException rejection(HostOperation operation) {
        return rejection(operation.result);
    }

    public static RpcException assertRpcError(String reason, CompletableFuture<?> result) {
        RpcException error = rejection(result);
        assertEquals(reason, error.reason.wire(), "error reason: " + error.getMessage());
        return error;
    }

    public static RpcException assertRpcError(String reason, HostOperation operation) {
        return assertRpcError(reason, operation.result);
    }

    /** A settled result whose outcome is {@code outcome}. */
    public static JsonObject assertOutcome(String outcome, HostOperation operation) {
        assertTrue(operation.result.isDone(), "the future must already be settled");
        JsonObject result = operation.result.join();
        assertEquals(outcome, result.get("outcome").getAsString(), "outcome: " + result);
        assertVerified(result);
        return result;
    }

    /** The protocol rule: a result whose outcome is applied says how it was confirmed (ack observes nothing). */
    public static JsonObject assertVerified(JsonObject result) {
        if (result.has("outcome")
                && result.get("outcome").getAsString().equals(Outcome.APPLIED.wire())
                && !result.has("released"))
            assertTrue(result.has("verification"), "an applied result carries verification: " + result);
        return result;
    }

    /** A settled result with outcome unknown and {@code reason}: a host change may have happened. */
    public static JsonObject assertUnknown(String reason, HostOperation operation) {
        JsonObject result = assertOutcome(Outcome.UNKNOWN.wire(), operation);
        assertEquals(reason, result.get("reason").getAsString(), "unknown reason: " + result);
        return result;
    }
}
