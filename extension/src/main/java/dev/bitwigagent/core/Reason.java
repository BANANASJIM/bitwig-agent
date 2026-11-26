package dev.bitwigagent.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Why an error was raised or a result entry is not a success; its error code comes from the protocol bundle. */
public enum Reason {
    INVALID_PARAMS,
    INVALID_REQUEST,
    INVALID_TARGET,
    KIND_MISMATCH,
    UNSUPPORTED_SCOPE,
    UNSUPPORTED_UNIT,
    UNKNOWN_NAME,
    AMBIGUOUS_NAME,
    OUT_OF_BOUNDS,
    INVALID_FILE,
    NO_NOTES,
    UNVERIFIABLE_CLIP,
    TARGET_NOT_FOUND,
    TARGET_CHANGED,
    UNKNOWN_HOLD,
    TARGET_EXISTS,
    OVERLAP,
    LIMIT_REACHED,
    READBACK_MISMATCH,
    SIDE_EFFECT,
    NOT_REACHED,
    BUSY,
    UNRESOLVED,
    UNSAFE_STATE,
    ENGINE_INACTIVE,
    UNSAVED_CHANGES,
    NOT_ISOLATED,
    NOT_READY,
    NATIVE_UNAVAILABLE,
    OUTSIDE_WINDOW,
    DEADLINE,
    TRANSPORT,
    NOT_OBSERVED,
    UNSUPPORTED_PLATFORM,
    HOST_REFUSED,
    ROUTING_CYCLE,
    NEEDS_CONFIRMATION,
    PATH_NOT_ALLOWED,
    JOB_FAILED,
    CANCELLED,
    PROTOCOL_MISMATCH,
    HOST_MISMATCH,
    UNKNOWN_METHOD,
    INTERNAL;

    private static final class Codes {
        static final Map<String, String> CODES = MethodSpecs.load().vocabulary().reasons;
    }

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The bundle's code for this reason; {@link #check} proved at startup that it exists. */
    public ErrorCode code() {
        return ErrorCode.valueOf(Codes.CODES.get(wire()).toUpperCase(Locale.ROOT));
    }

    /** At startup, before any request: the Java vocabulary must equal the bundle's, or the extension fails to load. */
    public static void check(MethodSpecs.Vocabulary vocabulary) {
        List<String> outcomes = new ArrayList<>(), codes = new ArrayList<>();
        for (Outcome outcome : Outcome.values()) outcomes.add(outcome.wire());
        for (ErrorCode code : ErrorCode.values()) codes.add(code.wire());
        if (!outcomes.equals(vocabulary.outcomes) || !codes.equals(vocabulary.errorCodes))
            throw new IllegalStateException("Outcome or ErrorCode differs from the protocol bundle");
        if (vocabulary.reasons.size() != values().length)
            throw new IllegalStateException("Reason differs from the protocol bundle");
        for (Reason reason : values())
            if (!vocabulary.reasons.containsKey(reason.wire()))
                throw new IllegalStateException("Reason " + reason.wire() + " is not in the protocol bundle");
    }
}
