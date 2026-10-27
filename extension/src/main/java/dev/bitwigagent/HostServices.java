package dev.bitwigagent;

import com.bitwig.extension.controller.api.*;
import com.bitwig.extension.callback.IntegerValueChangedCallback;
import com.bitwig.extension.callback.BooleanValueChangedCallback;
import com.google.gson.JsonObject;

/** One set of fixed host proxies, initialized once and shared by domain modules. */
final class HostServices implements AutoCloseable {
    static final int TRACKS = 16, DEVICES = 16, SCENES = 16, SENDS = 8, RESOLUTION = 16384;
    static final double TOLERANCE = 1.0 / (RESOLUTION - 1) + 1e-9;
    final ControllerHost host;
    final Application application;
    final Transport transport;
    final TrackBank tracks;
    final DeviceBank[] devices = new DeviceBank[TRACKS];
    final DeviceSources sources;
    boolean tracksObserved;
    private volatile boolean closed;
    private Bitwig614Runtime nativeRuntime;
    private Boolean playing, arrangerRecordEnabled, arrangerAutomationWriteEnabled, clipLauncherAutomationWriteEnabled;
    private volatile SafetyObservation safety = new SafetyObservation(0, null, null, null, null);

    HostServices(ControllerHost host) {
        this.host = host;
        application = host.createApplication();
        transport = host.createTransport();
        application.projectName().markInterested();
        application.hasActiveEngine().markInterested();
        transport.isPlaying().addValueObserver((BooleanValueChangedCallback) value -> { playing = value; publishSafety(); });
        transport.isArrangerRecordEnabled().addValueObserver((BooleanValueChangedCallback) value -> { arrangerRecordEnabled = value; publishSafety(); });
        transport.isArrangerAutomationWriteEnabled().addValueObserver((BooleanValueChangedCallback) value -> { arrangerAutomationWriteEnabled = value; publishSafety(); });
        transport.isClipLauncherAutomationWriteEnabled().addValueObserver((BooleanValueChangedCallback) value -> { clipLauncherAutomationWriteEnabled = value; publishSafety(); });
        tracks = host.createTrackBank(TRACKS, SENDS, SCENES, true);
        tracks.scrollPosition().markInterested();
        tracks.itemCount().addValueObserver((IntegerValueChangedCallback) count -> tracksObserved = true);
        for (int t = 0; t < TRACKS; t++) {
            Track track = (Track) tracks.getItemAt(t);
            track.exists().markInterested();
            track.channelId().markInterested();
            track.name().markInterested();
            track.position().markInterested();
            devices[t] = track.createDeviceBank(DEVICES);
            devices[t].scrollPosition().markInterested();
            devices[t].itemCount().markInterested();
            for (int d = 0; d < DEVICES; d++) {
                Device device = (Device) devices[t].getItemAt(d);
                device.exists().markInterested();
                device.name().markInterested();
                device.position().markInterested();
                device.deviceType().markInterested();
            }
        }
        sources = new DeviceSources(this);
    }

    /** Controller-thread composition: domains share the gate, never their native bindings. */
    Bitwig614Runtime nativeRuntime(HostOperation operation) {
        check(operation);
        if (nativeRuntime == null)
            nativeRuntime = new Bitwig614Runtime(host, host.getHostVersion(), () -> check(operation));
        return nativeRuntime;
    }

    void schedule(HostOperation operation, Runnable task, long delayMs) {
        if (operation.result.isDone()) return;
        if (operation.safetyAtEnqueue == null) operation.safetyAtEnqueue = safety;
        host.scheduleTask(() -> {
            if (operation.result.isDone()) return;
            try {
                check(operation);
                task.run();
            } catch (RuntimeException | Error error) {
                operation.fail(error);
            }
        }, delayMs);
    }

    void check(HostOperation operation) {
        if (closed) throw new RpcException("unavailable", "Extension has stopped.", operation.dispatched ? "unknown" : "not_applied");
        if (operation.result.isDone()) throw new RpcException("cancelled", "Operation is no longer awaiting a result.",
                operation.dispatched ? "unknown" : "not_applied");
        if (System.nanoTime() >= operation.deadline)
            throw new RpcException("deadline_exceeded", "Deadline expired; inspect state before another write.",
                    operation.dispatched ? "unknown" : "not_applied");
    }

    /** Observed safety inputs are shared by transport and launcher; getter defaults are not evidence. */
    void requirePlaybackSafe() {
        if (arrangerRecordEnabled == null || arrangerAutomationWriteEnabled == null || clipLauncherAutomationWriteEnabled == null)
            throw failure("not_ready", "Recording and automation safety states have not all been observed.");
        if (arrangerRecordEnabled || arrangerAutomationWriteEnabled || clipLauncherAutomationWriteEnabled)
            throw failure("unsafe_state", "Recording or automation write is enabled; playback mutations are blocked.");
    }

    JsonObject playbackSafety() {
        JsonObject result = new JsonObject();
        result.addProperty("arrangerRecordEnabled", arrangerRecordEnabled);
        result.addProperty("arrangerAutomationWriteEnabled", arrangerAutomationWriteEnabled);
        result.addProperty("clipLauncherAutomationWriteEnabled", clipLauncherAutomationWriteEnabled);
        return result;
    }

    private void publishSafety() {
        safety = new SafetyObservation(safety.epoch + 1, playing, arrangerRecordEnabled,
                arrangerAutomationWriteEnabled, clipLauncherAutomationWriteEnabled);
    }

    SafetyObservation stoppedSafety(HostOperation operation) {
        SafetyObservation observed = operation.safetyAtEnqueue == null ? safety : operation.safetyAtEnqueue;
        observed.requireStopped();
        guardStoppedSafety(observed);
        return observed;
    }

    void guardStoppedSafety(SafetyObservation before) {
        SafetyObservation current = safety;
        if (current.epoch != before.epoch)
            throw failure("stale_context", "Observed transport safety changed while the Grid write was queued or running.");
        current.requireStopped();
    }

    /** Immutable controller observations; document callbacks never call Controller API safety getters. */
    static final class SafetyObservation {
        final long epoch;
        final Boolean playing, recording, arrangerAutomation, launcherAutomation;
        SafetyObservation(long epoch, Boolean playing, Boolean recording, Boolean arrangerAutomation, Boolean launcherAutomation) {
            this.epoch = epoch; this.playing = playing; this.recording = recording;
            this.arrangerAutomation = arrangerAutomation; this.launcherAutomation = launcherAutomation;
        }
        void requireStopped() {
            if (playing == null || recording == null || arrangerAutomation == null || launcherAutomation == null)
                throw failure("not_ready", "Stopped transport, recording and automation safety have not all been observed.");
            if (playing || recording || arrangerAutomation || launcherAutomation)
                throw failure("unsafe_state", "Grid writes require observed stopped transport with recording and automation write disabled.");
        }
    }

    Track track(String trackId) { return (Track) tracks.getItemAt(trackSlot(trackId)); }

    int trackSlot(String trackId) {
        if (!tracksObserved) throw failure("not_ready", "Track bank has not reported its size yet.");
        for (int i = 0; i < TRACKS; i++) {
            if (trackMatches(trackId, (Track) tracks.getItemAt(i))) return i;
        }
        throw failure("outside_window", "Track UUID is absent from the current 16-track window.");
    }

    boolean trackMatches(String trackId, Track track) {
        return track.exists().get() && trackId.equals(track.channelId().get());
    }

    static JsonObject observed() {
        JsonObject result = new JsonObject(); result.addProperty("status", "observed"); return result;
    }

    static JsonObject window(int offset, int capacity, int total) {
        JsonObject result = new JsonObject();
        result.addProperty("offset", offset);
        result.addProperty("capacity", capacity);
        result.addProperty("total", total);
        result.addProperty("complete", offset == 0 && total >= 0 && total <= capacity);
        return result;
    }

    static RpcException failure(String code, String message) { return new RpcException(code, message, "not_applied"); }
    @Override public void close() { closed = true; }
}
