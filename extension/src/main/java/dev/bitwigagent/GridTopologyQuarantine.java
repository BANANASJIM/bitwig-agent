package dev.bitwigagent;

import static dev.bitwigagent.HostServices.failure;

/** One native-lifetime quarantine shared by move, delete and connection writes. */
final class GridTopologyQuarantine {
    volatile boolean active;
    void requireAvailable() {
        if (active) throw failure("grid_unavailable", "Grid topology writes are quarantined after a native operation/callback failure. Fully restart Bitwig before further topology writes; do not retry or assume rollback. Quarantine is sticky in this extension classloader, not guaranteed across classloader replacement. Scalar operations remain independently gated.");
    }
    void invoke(Runnable mutation) {
        // Only synchronous native invocation/listener/close failures poison this lifetime, not later event failures.
        try { mutation.run(); }
        catch (Throwable error) {
            active = true;
            throw failure("grid_unavailable", "Native Grid topology operation/callback failed; outcome unknown. Topology writes are quarantined in this extension classloader. Fully restart Bitwig; inspect state, do not retry or assume rollback. Quarantine is not guaranteed across classloader replacement.");
        }
    }
}
