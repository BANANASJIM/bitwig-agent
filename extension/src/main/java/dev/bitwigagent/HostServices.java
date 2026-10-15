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
    boolean tracksObserved;
    private volatile boolean closed;
    private Boolean arrangerRecordEnabled, arrangerAutomationWriteEnabled, clipLauncherAutomationWriteEnabled;

    HostServices(ControllerHost host) {
        this.host = host;
        application = host.createApplication();
        transport = host.createTransport();
        application.projectName().markInterested();
        application.hasActiveEngine().markInterested();
        transport.isPlaying().markInterested();
        transport.isArrangerRecordEnabled().addValueObserver((BooleanValueChangedCallback) value -> arrangerRecordEnabled = value);
        transport.isArrangerAutomationWriteEnabled().addValueObserver((BooleanValueChangedCallback) value -> arrangerAutomationWriteEnabled = value);
        transport.isClipLauncherAutomationWriteEnabled().addValueObserver((BooleanValueChangedCallback) value -> clipLauncherAutomationWriteEnabled = value);
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
    }

    void schedule(HostOperation operation, Runnable task, long delayMs) {
        if (operation.result.isDone()) return;
        host.scheduleTask(() -> {
            if (operation.result.isDone()) return;
            try {
                check(operation);
                task.run();
            } catch (RuntimeException error) {
                // A guard or host error after any mutation must never report not_applied.
                if (operation.dispatched && error instanceof RpcException
                        && "not_applied".equals(((RpcException) error).outcome)) {
                    RpcException rpc = (RpcException) error;
                    operation.result.completeExceptionally(new RpcException(rpc.code, rpc.getMessage(), "unknown"));
                } else operation.result.completeExceptionally(error);
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
