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
        if (selection.held()) throw Errors.notApplied(ErrorCode.BUSY, "Launcher editor operation is still pending");
    }

    void requireNoSettingsWrite() {
        if (holds.held(SETTINGS_PENDING) || holds.held(SETTINGS))
            throw Errors.notApplied(
                    ErrorCode.UNRESOLVED_MUTATION,
                    "Launcher settings write is pending or unresolved; inspect state before further launcher writes");
    }

    /** Scene duplicate/delete move slot rows: refuse while any of the above pins them. */
    void requireStructureFree() {
        requireNoCursorOperation();
        requireNoSettingsWrite();
        if (holds.held(MUTATION))
            throw Errors.notApplied(
                    ErrorCode.UNRESOLVED_MUTATION,
                    "A launcher mutation is unresolved; a scene edit would shift the slot rows it is quarantined by. Inspect launcher.list first.");
    }
}
