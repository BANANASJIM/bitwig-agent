package dev.bitwigagent.core;

/** Immutable controller observations; document callbacks never call Controller API safety getters. */
public final class SafetyObservation {
    private static final String OFF = "arrangerRecordEnabled=false arrangerAutomationWriteEnabled=false";
    public final long epoch;
    public final Boolean playing, recording, arrangerAutomation, launcherAutomation;

    public SafetyObservation(
            long epoch, Boolean playing, Boolean recording, Boolean arrangerAutomation, Boolean launcherAutomation) {
        this.epoch = epoch;
        this.playing = playing;
        this.recording = recording;
        this.arrangerAutomation = arrangerAutomation;
        this.launcherAutomation = launcherAutomation;
    }

    public void requireObserved() {
        if (recording == null || arrangerAutomation == null || launcherAutomation == null)
            throw Errors.notApplied(
                    Reason.NOT_READY, "Recording and automation safety states have not all been observed.");
    }

    public void requireNotRecording() {
        requireObserved();
        if (recording || arrangerAutomation || launcherAutomation)
            throw Errors.notApplied(
                    Reason.UNSAFE_STATE,
                    "Recording or automation write is enabled, so this write is blocked. Turn them off with"
                            + " transport.set " + OFF + ", then retry.");
    }

    public void requireNotPlaying() {
        if (playing == null)
            throw Errors.notApplied(Reason.NOT_READY, "Transport playing state has not been observed.");
        if (playing) throw Errors.notApplied(Reason.UNSAFE_STATE, "Transport is playing; stop it first.");
    }

    public void requireStopped() {
        if (playing == null || recording == null || arrangerAutomation == null || launcherAutomation == null)
            throw Errors.notApplied(
                    Reason.NOT_READY, "Stopped transport, recording and automation safety have not all been observed.");
        if (playing || recording || arrangerAutomation || launcherAutomation)
            throw Errors.notApplied(
                    Reason.UNSAFE_STATE,
                    "This write needs a stopped transport with recording and automation write off (playing="
                            + playing + ", recording=" + recording + ", arrangerAutomation=" + arrangerAutomation
                            + ", launcherAutomation=" + launcherAutomation
                            + "). Stop playback and turn recording and automation write off with transport.set playing=false "
                            + OFF + ", then retry.");
    }
}
