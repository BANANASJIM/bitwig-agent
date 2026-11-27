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

    private SafetyObservation seen(HostOperation operation) {
        return operation.safetyAtEnqueue == null ? observed : operation.safetyAtEnqueue;
    }

    private void guardStopped(SafetyObservation before) {
        SafetyObservation current = observed;
        if (current.epoch != before.epoch)
            throw Errors.notApplied(
                    Reason.UNSAFE_STATE,
                    "Transport, recording or automation-write state changed while the write was queued or running.");
        current.requireStopped();
    }

    private void admit(HostOperation operation, MethodSpecs.Transport level) {
        SafetyObservation seen = seen(operation);
        if (level == MethodSpecs.Transport.STOPPED) {
            seen.requireStopped();
            guardStopped(seen);
        } else if (level == MethodSpecs.Transport.NOT_RECORDING) {
            seen.requireNotRecording();
            requirePlaybackSafe();
        }
    }

    /** Admit the operation's declared requirement (its spec's requires), from the enqueue observation and now. */
    public void admit(HostOperation operation) {
        admit(operation, operation.transport);
    }

    /**
     * Re-check the declared requirement while the write is queued or running. A module only re-checks what its
     * method declares, so a call for a method that declares none is a missing declaration.
     */
    public void recheck(HostOperation operation) {
        if (operation.transport == null || operation.transport == MethodSpecs.Transport.ANY)
            throw new IllegalStateException(
                    "A module re-checks transport safety for a method whose requires declares none.");
        if (operation.transport == MethodSpecs.Transport.STOPPED) guardStopped(seen(operation));
        else requirePlaybackSafe();
    }

    /**
     * A requirement that depends on the request (a target kind, a field, a display solve): when the declared one
     * covers it only the in-flight re-check remains, otherwise it is admitted now. The guard re-checks it in flight.
     */
    public Runnable atLeast(HostOperation operation, MethodSpecs.Transport level) {
        MethodSpecs.Transport declared = operation.transport;
        if (declared != null && declared != MethodSpecs.Transport.ANY && declared.compareTo(level) >= 0)
            return () -> recheck(operation);
        admit(operation, level);
        SafetyObservation seen = seen(operation);
        return level == MethodSpecs.Transport.STOPPED ? () -> guardStopped(seen) : this::requirePlaybackSafe;
    }
}
