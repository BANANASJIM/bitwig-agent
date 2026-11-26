package dev.bitwigagent.core;

import com.google.gson.JsonObject;
import dev.bitwigagent.rpc.RpcServer;
import java.util.concurrent.CompletableFuture;

/** One request's deadline and outcome, shared by controller and native document callbacks. */
public final class HostOperation {
    public final long deadline;
    public final CompletableFuture<JsonObject> result = new Result();
    private volatile boolean dispatched;

    private boolean expired;
    public volatile SafetyObservation safetyAtEnqueue;
    /** The long job this request dispatched; an unknown result names it for jobs.get. */
    public volatile String job;

    /** The read an unknown result points at when it names none: the request's method spec says which. */
    public volatile String inspect;

    public HostOperation(long deadline) {
        this(deadline, null);
    }

    public HostOperation(long deadline, String inspect) {
        this.deadline = deadline;
        this.inspect = inspect;
    }

    /** The future the transport waits on; at its deadline it may expire an operation that never dispatched. */
    private final class Result extends CompletableFuture<JsonObject> implements RpcServer.Expiring {
        @Override
        public boolean expire() {
            synchronized (HostOperation.this) {
                if (dispatched) return false;
                expired = true;
            }
            completeExceptionally(
                    Errors.notApplied(Reason.DEADLINE, "Nothing was dispatched before the deadline; nothing changed."));
            return true;
        }
    }

    public boolean dispatched() {
        return dispatched;
    }

    /** Called right before the native call; an operation the transport already expired never dispatches. */
    public synchronized void markDispatched() {
        if (expired) throw Errors.notApplied(Reason.DEADLINE, "The request expired before dispatch; nothing was sent.");
        dispatched = true;
    }

    /** Before dispatch a failure is an error (nothing changed); after it, a result with outcome unknown. */
    public void fail(Throwable error) {
        RpcException rpc = error instanceof RpcException
                ? (RpcException) error
                : Errors.notApplied(Reason.INTERNAL, "Internal failure: " + error);
        if (dispatched || rpc.unknown) {
            JsonObject unknown = Errors.unknownResult(rpc, inspect);
            if (job != null) unknown.addProperty("job", job);
            result.complete(unknown);
        } else result.completeExceptionally(rpc);
    }
}
