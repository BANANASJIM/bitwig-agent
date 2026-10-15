package dev.bitwigagent;

import com.google.gson.JsonObject;
import java.util.concurrent.CompletableFuture;

/** One request's deadline and dispatch outcome; owned by the host thread after enqueue. */
final class HostOperation {
    final long deadline;
    final CompletableFuture<JsonObject> result = new CompletableFuture<>();
    boolean dispatched;

    HostOperation(long deadline) { this.deadline = deadline; }
}
