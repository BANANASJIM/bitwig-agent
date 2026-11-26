package dev.bitwigagent.core;

import java.util.Locale;

/** What a write did, in rising severity: a result's outcome is the worst of its entries. */
public enum Outcome {
    ALREADY_MATCHED,
    APPLIED,
    SENT,
    NOT_APPLIED,
    UNKNOWN;

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    public Outcome worst(Outcome other) {
        return compareTo(other) >= 0 ? this : other;
    }

    public boolean succeeded() {
        return compareTo(SENT) <= 0;
    }
}
