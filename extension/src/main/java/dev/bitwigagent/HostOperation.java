package dev.bitwigagent;

import com.google.gson.JsonObject;
import java.util.concurrent.CompletableFuture;

/** One request's deadline and outcome, shared by controller and native document callbacks. */
final class HostOperation {
    final long deadline;
    final CompletableFuture<JsonObject> result = new CompletableFuture<>();
    volatile boolean dispatched;
    volatile SafetyObservation safetyAtEnqueue;

    HostOperation(long deadline) {
        this.deadline = deadline;
    }

    void fail(Throwable error) {
        if (dispatched) {
            if (error instanceof RpcException) {
                RpcException rpc = (RpcException) error;
                error = Errors.withOutcome(rpc, Outcome.UNKNOWN);
            } else {
                error = Errors.unknown(
                        ErrorCode.INTERNAL_ERROR,
                        "Failure after native dispatch; outcome unknown. Inspect state before another write.");
            }
        }
        result.completeExceptionally(error);
    }
}
