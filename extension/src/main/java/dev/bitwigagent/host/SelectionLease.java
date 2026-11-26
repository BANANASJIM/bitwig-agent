package dev.bitwigagent.host;

import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.HostOperation;
import dev.bitwigagent.core.Reason;

/** Launcher cursor operations bind the global editor selection for their whole life. */
public final class SelectionLease {
    /** Volatile: a native select checks it again on the document thread right before it moves the selection. */
    private volatile HostOperation owner;

    public boolean held() {
        return owner != null && !owner.result.isDone();
    }

    public boolean ownedBy(HostOperation operation) {
        return owner == operation;
    }

    public void acquire(HostOperation operation) {
        requireFree();
        owner = operation;
    }

    /** The lease passes to a follow-up operation without a free window in between. */
    public void handOff(HostOperation operation) {
        owner = operation;
    }

    /** Selection-moving methods fail fast instead of rebinding the cursor under a running edit; nothing is queued. */
    public void requireFree() {
        if (held())
            throw Errors.notApplied(
                    Reason.BUSY, "A launcher cursor operation holds the editor selection; no request was queued.");
    }
}
