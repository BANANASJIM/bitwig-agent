package dev.bitwigagent;

import com.google.gson.JsonObject;
import java.util.concurrent.CompletableFuture;

/** One request's deadline and outcome, shared by controller and native document callbacks. */
final class HostOperation {
    final long deadline;
    /** Applied to a successful result at completion (e.g. cross-cutting warnings); the future stays the caller's cancellation handle. */
    volatile java.util.function.Consumer<JsonObject> decorate;

    final CompletableFuture<JsonObject> result = new CompletableFuture<JsonObject>() {
        @Override
        public boolean complete(JsonObject value) {
            java.util.function.Consumer<JsonObject> d = decorate;
            if (d != null && value != null && !isDone()) d.accept(value);
            return super.complete(value);
        }
    };
    volatile boolean dispatched;
    volatile HostServices.SafetyObservation safetyAtEnqueue;

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
