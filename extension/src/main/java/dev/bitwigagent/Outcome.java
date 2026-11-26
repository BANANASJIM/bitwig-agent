package dev.bitwigagent;

/** What an error says about host-side effects. */
public enum Outcome {
    NOT_APPLIED("not_applied"),
    UNKNOWN("unknown"),
    NOT_DISPATCHED("not_dispatched");

    private final String wire;

    Outcome(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }
}
