package dev.bitwigagent;

/** Immutable controller observations; document callbacks never call Controller API safety getters. */
final class SafetyObservation {
    final long epoch;
    final Boolean playing, recording, arrangerAutomation, launcherAutomation;

    SafetyObservation(
            long epoch, Boolean playing, Boolean recording, Boolean arrangerAutomation, Boolean launcherAutomation) {
        this.epoch = epoch;
        this.playing = playing;
        this.recording = recording;
        this.arrangerAutomation = arrangerAutomation;
        this.launcherAutomation = launcherAutomation;
    }

    void requireNotRecording() {
        if (recording == null || arrangerAutomation == null || launcherAutomation == null)
            throw Errors.notApplied(
                    Reason.NOT_READY, "Recording and automation safety states have not all been observed.");
        if (recording || arrangerAutomation || launcherAutomation)
            throw Errors.notApplied(
                    Reason.UNSAFE_STATE,
                    "Recording or automation write is enabled, so this write is blocked. Turn off recording and"
                            + " automation write (transport.set), then retry.");
    }

    void requireNotPlaying() {
        if (playing == null)
            throw Errors.notApplied(Reason.NOT_READY, "Transport playing state has not been observed.");
        if (playing) throw Errors.notApplied(Reason.UNSAFE_STATE, "Transport is playing; stop it first.");
    }

    void requireStopped() {
        if (playing == null || recording == null || arrangerAutomation == null || launcherAutomation == null)
            throw Errors.notApplied(
                    Reason.NOT_READY, "Stopped transport, recording and automation safety have not all been observed.");
        if (playing || recording || arrangerAutomation || launcherAutomation)
            throw Errors.notApplied(
                    Reason.UNSAFE_STATE,
                    "This write needs a stopped transport with recording and automation write off (playing="
                            + playing + ", recording=" + recording + ", arrangerAutomation=" + arrangerAutomation
                            + ", launcherAutomation=" + launcherAutomation
                            + "). Stop playback and turn them off with transport.set, then retry.");
    }
}
