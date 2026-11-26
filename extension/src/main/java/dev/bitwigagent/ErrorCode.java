package dev.bitwigagent;

import java.util.Locale;

/** What the caller can do about an error; every error means nothing changed. */
public enum ErrorCode {
    INVALID_PARAMS,
    STALE,
    CONFLICT,
    PENDING,
    UNSAFE_STATE,
    UNAVAILABLE,
    REFUSED,
    INCOMPATIBLE,
    UNKNOWN_METHOD,
    INTERNAL;

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }
}
