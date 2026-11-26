package dev.bitwigagent.host;

import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.Holds;
import dev.bitwigagent.core.Reason;

/** What pins launcher slot rows: a cursor operation, a launcher settings write, an unresolved native mutation. */
public final class LauncherStructureLock {
    public static final String SETTINGS = "launcher.settings", SETTINGS_PENDING = "launcher.settings.pending";
    public static final String MUTATION = "launcher.mutation";

    private final SelectionLease selection;
    private final Holds holds;

    public LauncherStructureLock(SelectionLease selection, Holds holds) {
        this.selection = selection;
        this.holds = holds;
    }

    public void requireNoCursorOperation() {
        if (selection.held()) throw Errors.notApplied(Reason.BUSY, "Launcher editor operation is still pending");
    }

    public void requireNoSettingsWrite() {
        if (holds.held(SETTINGS_PENDING))
            throw Errors.notApplied(Reason.BUSY, "A launcher settings write is still pending.");
        if (holds.held(SETTINGS))
            throw holds.blocked(SETTINGS, "A launcher settings write is unresolved.", "Inspect clips.get");
    }

    /** Scene duplicate/delete move slot rows: refuse while any of the above pins them. */
    public void requireStructureFree() {
        requireNoCursorOperation();
        requireNoSettingsWrite();
        if (holds.held(MUTATION))
            throw holds.blocked(
                    MUTATION,
                    "A launcher mutation is unresolved; a scene edit would shift the slot rows it holds.",
                    "Inspect launcher.list");
    }
}
