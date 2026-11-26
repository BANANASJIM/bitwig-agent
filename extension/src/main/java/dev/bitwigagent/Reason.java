package dev.bitwigagent;

import dev.bitwigagent.core.MethodSpecs;
import java.util.ArrayList;
import java.util.EnumMap;
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
    STALE_CONTEXT,
    STALE_TARGET,
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
        static final Map<Reason, ErrorCode> CODES = check(MethodSpecs.load().vocabulary());
    }

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    public ErrorCode code() {
        return Codes.CODES.get(this);
    }

    /** The Java vocabulary must equal the bundle's: the same outcomes in the same order, codes and reasons. */
    static Map<Reason, ErrorCode> check(MethodSpecs.Vocabulary vocabulary) {
        List<String> outcomes = new ArrayList<>(), codes = new ArrayList<>();
        for (Outcome outcome : Outcome.values()) outcomes.add(outcome.wire());
        for (ErrorCode code : ErrorCode.values()) codes.add(code.wire());
        if (!outcomes.equals(vocabulary.outcomes) || !codes.equals(vocabulary.errorCodes))
            throw new IllegalStateException("Outcome or ErrorCode differs from the protocol bundle");
        if (vocabulary.reasons.size() != values().length)
            throw new IllegalStateException("Reason differs from the protocol bundle");
        Map<Reason, ErrorCode> out = new EnumMap<>(Reason.class);
        for (Reason reason : values()) {
            String code = vocabulary.reasons.get(reason.wire());
            if (code == null)
                throw new IllegalStateException("Reason " + reason.wire() + " is not in the protocol bundle");
            out.put(reason, ErrorCode.valueOf(code.toUpperCase(Locale.ROOT)));
        }
        return out;
    }
}
