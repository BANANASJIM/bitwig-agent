package dev.bitwigagent;

/**
 * A failure. Raised before any host change it is a protocol error (nothing changed); marked unknown it is reported
 * as a result with outcome unknown, because a host change may have happened.
 */
public final class RpcException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public final Reason reason;
    public final String next;
    public final boolean unknown;

    RpcException(Reason reason, String message, String next, boolean unknown) {
        super(message);
        this.reason = reason;
        this.next = next;
        this.unknown = unknown;
    }

    public String code() {
        return reason.code().wire();
    }

    public boolean is(Reason expected) {
        return reason == expected;
    }
}
