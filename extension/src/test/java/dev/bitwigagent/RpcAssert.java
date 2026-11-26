package dev.bitwigagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Assertions on the structured errors a module reports, synchronously or through a settled future. */
final class RpcAssert {
    private RpcAssert() {}

    static RpcException assertRpcError(String code, Runnable action) {
        RpcException error = assertThrows(RpcException.class, action::run, "expected " + code);
        assertEquals(code, error.code, "error code: " + error.getMessage());
        return error;
    }

    static RpcException assertRpcError(String code, String outcome, Runnable action) {
        RpcException error = assertRpcError(code, action);
        assertEquals(outcome, error.outcome, "outcome of " + code);
        return error;
    }

    static RpcException assertNotApplied(String code, Runnable action) {
        return assertRpcError(code, Outcome.NOT_APPLIED.wire(), action);
    }

    static RpcException assertInvalidParams(Runnable action) {
        return assertNotApplied(ErrorCode.INVALID_PARAMS.wire(), action);
    }

    static RpcException rejection(CompletableFuture<?> result) {
        assertTrue(result.isDone(), "the future must already be settled");
        CompletionException completion = assertThrows(CompletionException.class, result::join);
        return assertInstanceOf(RpcException.class, completion.getCause());
    }

    static RpcException rejection(HostOperation operation) {
        return rejection(operation.result);
    }

    static RpcException assertRpcError(String code, CompletableFuture<?> result) {
        RpcException error = rejection(result);
        assertEquals(code, error.code, "error code: " + error.getMessage());
        return error;
    }

    static RpcException assertRpcError(String code, HostOperation operation) {
        return assertRpcError(code, operation.result);
    }

    static RpcException assertRpcError(String code, String outcome, HostOperation operation) {
        RpcException error = assertRpcError(code, operation);
        assertEquals(outcome, error.outcome, "outcome of " + code);
        return error;
    }
}
