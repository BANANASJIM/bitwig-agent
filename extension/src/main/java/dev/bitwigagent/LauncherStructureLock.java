package dev.bitwigagent;

/** What pins launcher slot rows: a cursor operation, a launcher settings write, an unresolved native mutation. */
final class LauncherStructureLock {
    static final String SETTINGS = "launcher.settings", SETTINGS_PENDING = "launcher.settings.pending";
    static final String MUTATION = "launcher.mutation";

    private final SelectionLease selection;
    private final Holds holds;

    LauncherStructureLock(SelectionLease selection, Holds holds) {
        this.selection = selection;
        this.holds = holds;
    }

    void requireNoCursorOperation() {
        if (selection.held()) throw Errors.notApplied(Reason.BUSY, "Launcher editor operation is still pending");
    }

    void requireNoSettingsWrite() {
        if (holds.held(SETTINGS_PENDING))
            throw Errors.notApplied(Reason.BUSY, "A launcher settings write is still pending.");
        if (holds.held(SETTINGS))
            throw holds.blocked(SETTINGS, "A launcher settings write is unresolved.", "Inspect launcher.get");
    }

    /** Scene duplicate/delete move slot rows: refuse while any of the above pins them. */
    void requireStructureFree() {
        requireNoCursorOperation();
        requireNoSettingsWrite();
        if (holds.held(MUTATION))
            throw holds.blocked(
                    MUTATION,
                    "A launcher mutation is unresolved; a scene edit would shift the slot rows it holds.",
                    "Inspect launcher.list");
    }
}
