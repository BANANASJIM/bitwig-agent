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

    /** Before dispatch a failure is an error (nothing changed); after it, a result with outcome unknown. */
    void fail(Throwable error) {
        RpcException rpc = error instanceof RpcException
                ? (RpcException) error
                : Errors.notApplied(Reason.INTERNAL, "Internal failure: " + error);
        if (dispatched || rpc.unknown) result.complete(Errors.unknownResult(rpc));
        else result.completeExceptionally(rpc);
    }
}
