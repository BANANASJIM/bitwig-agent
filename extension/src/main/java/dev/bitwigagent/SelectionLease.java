package dev.bitwigagent;

/** Launcher cursor operations bind the global editor selection for their whole life. */
final class SelectionLease {
    private HostOperation owner;

    boolean held() {
        return owner != null && !owner.result.isDone();
    }

    boolean ownedBy(HostOperation operation) {
        return owner == operation;
    }

    void acquire(HostOperation operation) {
        requireFree();
        owner = operation;
    }

    /** The lease passes to a follow-up operation without a free window in between. */
    void handOff(HostOperation operation) {
        owner = operation;
    }

    /** Selection-moving methods fail fast instead of rebinding the cursor under a running edit; nothing is queued. */
    void requireFree() {
        if (held())
            throw Errors.notApplied(
                    ErrorCode.BUSY, "A launcher cursor operation holds the editor selection; no request was queued.");
    }
}
