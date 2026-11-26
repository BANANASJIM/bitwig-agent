package dev.bitwigagent;

/** The only place protocol errors are built. */
public final class Errors {
    private Errors() {}

    public static RpcException of(ErrorCode code, String message, Outcome outcome) {
        return new RpcException(code.wire(), message, outcome.wire());
    }

    public static RpcException of(String code, String message, Outcome outcome) {
        return new RpcException(code, message, outcome.wire());
    }

    public static RpcException notApplied(ErrorCode code, String message) {
        return of(code, message, Outcome.NOT_APPLIED);
    }

    public static RpcException unknown(ErrorCode code, String message) {
        return of(code, message, Outcome.UNKNOWN);
    }

    public static RpcException invalid(String message) {
        return notApplied(ErrorCode.INVALID_PARAMS, message);
    }

    /** Same code and message with the outcome rewritten; for codes only known at runtime. */
    public static RpcException withOutcome(RpcException error, Outcome outcome) {
        return new RpcException(error.code, error.getMessage(), outcome.wire());
    }
}
