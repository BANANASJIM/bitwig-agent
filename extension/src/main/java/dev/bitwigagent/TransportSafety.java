package dev.bitwigagent;

import com.bitwig.extension.callback.BooleanValueChangedCallback;
import com.bitwig.extension.controller.api.Transport;

/** The one rule set for transport state that makes a write unsafe; getter defaults are never evidence. */
final class TransportSafety {
    private volatile SafetyObservation observed = new SafetyObservation(0, null, null, null, null);

    TransportSafety(Transport transport) {
        transport.isPlaying().addValueObserver((BooleanValueChangedCallback)
                value -> publish(value, observed.recording, observed.arrangerAutomation, observed.launcherAutomation));
        transport.isArrangerRecordEnabled().addValueObserver((BooleanValueChangedCallback)
                value -> publish(observed.playing, value, observed.arrangerAutomation, observed.launcherAutomation));
        transport.isArrangerAutomationWriteEnabled().addValueObserver((BooleanValueChangedCallback)
                value -> publish(observed.playing, observed.recording, value, observed.launcherAutomation));
        observeLauncherAutomation(transport);
    }

    @SuppressWarnings("deprecation")
    private void observeLauncherAutomation(Transport transport) {
        transport.isClipLauncherAutomationWriteEnabled().addValueObserver((BooleanValueChangedCallback)
                value -> publish(observed.playing, observed.recording, observed.arrangerAutomation, value));
    }

    private void publish(Boolean playing, Boolean recording, Boolean arranger, Boolean launcher) {
        observed = new SafetyObservation(observed.epoch + 1, playing, recording, arranger, launcher);
    }

    SafetyObservation observation() {
        return observed;
    }

    Boolean playing() {
        return observed.playing;
    }

    /** Recording and both automation-write modes observed off; playback may run. Safe from document callbacks. */
    void requirePlaybackSafe() {
        observed.requireNotRecording();
    }

    /** Insert-type edits need an observed stopped transport on top of the recording/automation guard. */
    void requireStopped() {
        SafetyObservation current = observed;
        current.requireNotRecording();
        current.requireNotPlaying();
    }

    /** Stopped with recording off, as observed at enqueue; later changes are caught by {@link #guardStopped}. */
    SafetyObservation stopped(HostOperation operation) {
        SafetyObservation seen = operation.safetyAtEnqueue == null ? observed : operation.safetyAtEnqueue;
        seen.requireStopped();
        guardStopped(seen);
        return seen;
    }

    /** Recording and both automation-write modes observed off at enqueue and now; playback may run. */
    void recording(HostOperation operation) {
        (operation.safetyAtEnqueue == null ? observed : operation.safetyAtEnqueue).requireNotRecording();
        requirePlaybackSafe();
    }

    void guardStopped(SafetyObservation before) {
        SafetyObservation current = observed;
        if (current.epoch != before.epoch)
            throw Errors.notApplied(
                    ErrorCode.STALE_CONTEXT,
                    "Observed transport safety changed while the Grid write was queued or running.");
        current.requireStopped();
    }

    /** The one gate for Grid and modulator writes: value writes may play, structural writes need a stopped transport. */
    Runnable writeGuard(HostOperation operation, boolean structural) {
        if (!structural) {
            recording(operation);
            return this::requirePlaybackSafe;
        }
        SafetyObservation seen = stopped(operation);
        return () -> guardStopped(seen);
    }
}
