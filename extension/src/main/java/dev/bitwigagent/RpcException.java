package dev.bitwigagent;

/** A protocol error; outcome describes effects, not merely response delivery. */
public final class RpcException extends RuntimeException {
    public final String code;
    public final String outcome;

    public RpcException(String code, String message, String outcome) {
        super(message);
        this.code = code;
        this.outcome = outcome;
    }
}
