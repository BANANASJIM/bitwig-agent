package dev.bitwigagent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * A failure. Raised before any host change it is a protocol error (nothing changed); marked unknown it is reported
 * as a result with outcome unknown, because a host change may have happened.
 */
public final class RpcException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public final Reason reason;
    public final String next;
    public final boolean unknown;
    /** The ref now found where a stale ref pointed; the values an expect did not match; each refused batch entry. */
    final transient JsonObject current, observed;

    final transient JsonArray entries;

    RpcException(Reason reason, String message, String next, boolean unknown) {
        this(reason, message, next, unknown, null, null, null);
    }

    RpcException(
            Reason reason,
            String message,
            String next,
            boolean unknown,
            JsonObject current,
            JsonObject observed,
            JsonArray entries) {
        super(message);
        this.reason = reason;
        this.next = next;
        this.unknown = unknown;
        this.current = current;
        this.observed = observed;
        this.entries = entries;
    }

    public String code() {
        return reason.code().wire();
    }

    public boolean is(Reason expected) {
        return reason == expected;
    }
}
