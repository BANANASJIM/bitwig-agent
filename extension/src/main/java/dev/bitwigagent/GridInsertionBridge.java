package dev.bitwigagent;

/** Public ordinary-Java boundary; merely loading the extension does not link the native adapter. */
public interface GridInsertionBridge {
    void load(Object project, String path);

    /** All callbacks are document-side except earlyError; implementations must never throw outward. */
    interface Callback {
        boolean delivered(Object converted, Object completion);
        void returned();
        void earlyError(String message);
        void adapterFailure(Throwable error);
    }
}
