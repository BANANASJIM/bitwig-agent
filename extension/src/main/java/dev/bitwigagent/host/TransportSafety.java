package dev.bitwigagent.host;

import com.bitwig.extension.callback.BooleanValueChangedCallback;
import com.bitwig.extension.controller.api.Transport;
import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.HostOperation;
import dev.bitwigagent.core.MethodSpecs;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.core.SafetyObservation;

/** The one rule set for transport state that makes a write unsafe; getter defaults are never evidence. */
public final class TransportSafety {
    private volatile SafetyObservation observed = new SafetyObservation(0, null, null, null, null);

    public TransportSafety(Transport transport) {
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

    public SafetyObservation observation() {
        return observed;
    }

    public Boolean playing() {
        return observed.playing;
    }

    /** Recording and both automation-write modes observed, whatever their state. */
    public void requireObserved() {
        observed.requireObserved();
    }

    /** Recording and both automation-write modes observed off; playback may run. Safe from document callbacks. */
    public void requirePlaybackSafe() {
        observed.requireNotRecording();
    }

    /** Insert-type edits need an observed stopped transport on top of the recording/automation guard. */
    public void requireStopped() {
        SafetyObservation current = observed;
        current.requireNotRecording();
        current.requireNotPlaying();
    }

    /** Stopped with recording off, as observed at enqueue; later changes are caught by {@link #guardStopped}. */
    public SafetyObservation stopped(HostOperation operation) {
        SafetyObservation seen = operation.safetyAtEnqueue == null ? observed : operation.safetyAtEnqueue;
        seen.requireStopped();
        guardStopped(seen);
        return seen;
    }

    /** Recording and both automation-write modes observed off at enqueue and now; playback may run. */
    public void recording(HostOperation operation) {
        (operation.safetyAtEnqueue == null ? observed : operation.safetyAtEnqueue).requireNotRecording();
        requirePlaybackSafe();
    }

    public void guardStopped(SafetyObservation before) {
        SafetyObservation current = observed;
        if (current.epoch != before.epoch)
            throw Errors.notApplied(
                    Reason.UNSAFE_STATE,
                    "Transport, recording or automation-write state changed while the write was queued or running.");
        current.requireStopped();
    }

    /** Admit a method's declared transport requirement, as observed at enqueue and now. */
    public void admit(HostOperation operation, MethodSpecs.Transport requirement) {
        if (requirement == MethodSpecs.Transport.STOPPED) stopped(operation);
        else if (requirement == MethodSpecs.Transport.NOT_RECORDING) recording(operation);
    }

    /** The one gate for Grid and modulator writes: value writes may play, structural writes need a stopped transport. */
    public Runnable writeGuard(HostOperation operation, boolean structural) {
        if (!structural) {
            recording(operation);
            return this::requirePlaybackSafe;
        }
        SafetyObservation seen = stopped(operation);
        return () -> guardStopped(seen);
    }
}
