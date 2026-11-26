package dev.bitwigagent;

import com.bitwig.extension.callback.BooleanValueChangedCallback;
import com.bitwig.extension.callback.IntegerValueChangedCallback;
import com.bitwig.extension.callback.StringValueChangedCallback;
import com.bitwig.extension.controller.api.*;
import com.google.gson.*;
import dev.bitwigagent.core.MethodSpecs;
import java.util.function.LongSupplier;

/** One set of fixed host proxies, initialized once and shared by domain modules. */
final class HostServices implements AutoCloseable {
    static final MethodSpecs.Limits LIMITS = MethodSpecs.load().limits();
    static final int TRACKS = LIMITS.trackWindow, SCENES = LIMITS.sceneWindow;
    static final int DEVICES = LIMITS.deviceWindow, SENDS = LIMITS.sendWindow, RESOLUTION = 16384;
    static final double TOLERANCE = 1.0 / (RESOLUTION - 1) + 1e-9;
    final ControllerHost host;
    final Application application;
    final Project project;
    final Transport transport;
    final TrackBank tracks;
    final DeviceBank[] devices = new DeviceBank[TRACKS];
    /** One parent-track proxy per bank slot (public createParentTrack), for TrackTree. */
    final Track[] parents = new Track[TRACKS];

    final DeviceSources sources;
    boolean tracksObserved;
    private volatile boolean closed;
    private Bitwig614Runtime nativeRuntime;
    private Boolean playing, arrangerRecordEnabled, arrangerAutomationWriteEnabled, clipLauncherAutomationWriteEnabled;
    /** A name change (extension start, project switch/recovery, Save As) opens a settle window in which writes may land in a transient project. */
    static final long PROJECT_SETTLE_NANOS = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(3000);
    /** The controller's monotonic clock in nanoseconds; request deadlines are on the same clock. */
    private final LongSupplier clock;

    private volatile boolean projectNameObserved;
    private volatile String observedProjectName;
    private volatile long projectChangedAt;
    private volatile Boolean projectModified;
    private volatile long projectModifiedSequence;
    private volatile SafetyObservation safety = new SafetyObservation(0, null, null, null, null);

    HostServices(ControllerHost host, LongSupplier clock) {
        this.clock = clock;
        this.host = host;
        application = host.createApplication();
        transport = host.createTransport();
        project = host.getProject();
        project.isModified().addValueObserver((BooleanValueChangedCallback) value -> {
            projectModified = value;
            projectModifiedSequence++;
        });
        application.projectName().markInterested();
        application.projectName().addValueObserver((StringValueChangedCallback) value -> {
            String name = String.valueOf(value);
            if (!projectNameObserved || !name.equals(observedProjectName)) projectChangedAt = now();
            observedProjectName = name;
            projectNameObserved = true;
        });
        application.hasActiveEngine().markInterested();
        transport.isPlaying().addValueObserver((BooleanValueChangedCallback) value -> {
            playing = value;
            publishSafety();
        });
        transport.isArrangerRecordEnabled().addValueObserver((BooleanValueChangedCallback) value -> {
            arrangerRecordEnabled = value;
            publishSafety();
        });
        transport.isArrangerAutomationWriteEnabled().addValueObserver((BooleanValueChangedCallback) value -> {
            arrangerAutomationWriteEnabled = value;
            publishSafety();
        });
        observeClipLauncherAutomationWrite(transport);
        tracks = host.createTrackBank(TRACKS, SENDS, SCENES, true);
        tracks.scrollPosition().markInterested();
        tracks.itemCount().addValueObserver((IntegerValueChangedCallback) count -> tracksObserved = true);
        for (int t = 0; t < TRACKS; t++) {
            Track track = (Track) tracks.getItemAt(t);
            track.exists().markInterested();
            track.channelId().markInterested();
            track.name().markInterested();
            track.position().markInterested();
            track.trackType().markInterested();
            track.isGroup().markInterested();
            track.isGroupExpanded().markInterested();
            parents[t] = track.createParentTrack(0, 0);
            parents[t].exists().markInterested();
            parents[t].channelId().markInterested();
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

    @SuppressWarnings("deprecation")
    private void observeClipLauncherAutomationWrite(Transport transport) {
        transport.isClipLauncherAutomationWriteEnabled().addValueObserver((BooleanValueChangedCallback) value -> {
            clipLauncherAutomationWriteEnabled = value;
            publishSafety();
        });
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
        host.scheduleTask(
                () -> {
                    if (operation.result.isDone()) return;
                    try {
                        check(operation);
                        task.run();
                    } catch (RuntimeException | Error error) {
                        operation.fail(error);
                    }
                },
                delayMs);
    }

    /** One observation window per write and ONE margin before the deadline (>= 2x every poll period, so the last poll
     *  always runs and a late controller thread still yields the bounded not-observed result, not deadline_exceeded). */
    static final long OBSERVE_WINDOW_NANOS = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(750);

    static final long DEADLINE_MARGIN_NANOS = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(50);

    long observeUntil(HostOperation operation) {
        return Math.min(operation.deadline - DEADLINE_MARGIN_NANOS, now() + OBSERVE_WINDOW_NANOS);
    }

    long now() {
        return clock.getAsLong();
    }

    /** Selection lease: launcher cursor operations bind the global editor selection for their whole life. */
    HostOperation selectionOwner;

    boolean selectionHeld() {
        return selectionOwner != null && !selectionOwner.result.isDone();
    }

    void acquireSelection(HostOperation operation) {
        requireSelectionFree();
        selectionOwner = operation;
    }

    /** Selection-moving methods fail fast instead of rebinding the cursor under a running edit; nothing is queued. */
    void requireSelectionFree() {
        if (selectionHeld())
            throw Errors.notApplied(
                    ErrorCode.BUSY, "A launcher cursor operation holds the editor selection; no request was queued.");
    }

    void check(HostOperation operation) {
        if (closed)
            throw Errors.of(
                    ErrorCode.UNAVAILABLE,
                    "Extension has stopped.",
                    operation.dispatched ? Outcome.UNKNOWN : Outcome.NOT_APPLIED);
        if (operation.result.isDone())
            throw Errors.of(
                    ErrorCode.CANCELLED,
                    "Operation is no longer awaiting a result.",
                    operation.dispatched ? Outcome.UNKNOWN : Outcome.NOT_APPLIED);
        if (now() >= operation.deadline)
            throw Errors.of(
                    ErrorCode.DEADLINE_EXCEEDED,
                    "Deadline expired; inspect state before another write.",
                    operation.dispatched ? Outcome.UNKNOWN : Outcome.NOT_APPLIED);
    }

    /** projectReady true/false/null (null: project name never observed) with a reason. Heuristic: the public API has no project-loaded event,
     *  so readiness is "name observed, track bank sized, no name change in the last PROJECT_SETTLE_NANOS". Controller thread only. */
    JsonObject projectReadiness() {
        JsonObject out = new JsonObject();
        Boolean ready;
        String reason;
        long sinceMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(now() - projectChangedAt);
        if (!projectNameObserved) {
            ready = null;
            reason = "project_name_unobserved";
        } else if (now() - projectChangedAt < PROJECT_SETTLE_NANOS) {
            ready = false;
            reason = "project_changed_settling";
        } else if (!tracksObserved) {
            ready = false;
            reason = "track_bank_unobserved";
        } else {
            ready = true;
            reason = "settled";
        }
        if (ready == null) out.add("projectReady", JsonNull.INSTANCE);
        else out.addProperty("projectReady", ready);
        out.addProperty("projectReadyReason", reason);
        if (projectNameObserved) out.addProperty("projectChangedMsAgo", sinceMs);
        return out;
    }

    /** Structure writes refuse while the project is observed not ready; unknown (null) does not block. */
    void requireProjectReady() {
        JsonObject r = projectReadiness();
        if (r.get("projectReady").isJsonPrimitive() && !r.get("projectReady").getAsBoolean())
            throw Errors.notApplied(
                    ErrorCode.PROJECT_NOT_READY,
                    "Project is not ready (" + r.get("projectReadyReason").getAsString()
                            + "): it was just opened, recovered or renamed, so this write could land in a transient project. Poll status until projectReady is true, then re-discover targets; nothing was written.");
    }

    boolean engineActive() {
        return application.hasActiveEngine().get();
    }

    /** Launching with an inactive audio engine silently produces no playback and silent captures. */
    void requireEngineActive(String what) {
        if (!engineActive())
            throw Errors.notApplied(
                    ErrorCode.ENGINE_INACTIVE,
                    "The audio engine is not active for this project, so " + what + " would not sound or be observed. "
                            + "Run project.activateEngine (or the \"Activate Engine For Project\" action), confirm status.engineActive=true, then retry; nothing was dispatched.");
    }

    /** Observed Project.isModified(); null until the first callback, never a getter default. */
    Boolean projectModified() {
        return projectModified;
    }

    long projectModifiedSequence() {
        return projectModifiedSequence;
    }

    JsonElement projectModifiedJson() {
        return projectModified == null ? JsonNull.INSTANCE : new JsonPrimitive(projectModified);
    }

    /** Observed safety inputs are shared by transport and launcher; getter defaults are not evidence. */
    void requirePlaybackSafe() {
        safety.requireNotRecording();
    }

    /** Insert-type edits need an observed stopped transport on top of the recording/automation guard. */
    void requireStopped() {
        requirePlaybackSafe();
        if (playing == null)
            throw Errors.notApplied(ErrorCode.NOT_READY, "Transport playing state has not been observed.");
        if (playing) throw Errors.notApplied(ErrorCode.UNSAFE_STATE, "Transport is playing; stop it first.");
    }

    Boolean playing() {
        return playing;
    }

    JsonObject playbackSafety() {
        JsonObject result = new JsonObject();
        result.addProperty("arrangerRecordEnabled", arrangerRecordEnabled);
        result.addProperty("arrangerAutomationWriteEnabled", arrangerAutomationWriteEnabled);
        result.addProperty("clipLauncherAutomationWriteEnabled", clipLauncherAutomationWriteEnabled);
        return result;
    }

    private void publishSafety() {
        safety = new SafetyObservation(
                safety.epoch + 1,
                playing,
                arrangerRecordEnabled,
                arrangerAutomationWriteEnabled,
                clipLauncherAutomationWriteEnabled);
    }

    SafetyObservation stoppedSafety(HostOperation operation) {
        SafetyObservation observed = operation.safetyAtEnqueue == null ? safety : operation.safetyAtEnqueue;
        observed.requireStopped();
        guardStoppedSafety(observed);
        return observed;
    }

    /** Recording and both automation-write modes observed off; playback may run. */
    SafetyObservation recordingSafety(HostOperation operation) {
        SafetyObservation observed = operation.safetyAtEnqueue == null ? safety : operation.safetyAtEnqueue;
        observed.requireNotRecording();
        guardRecordingSafety();
        return observed;
    }

    /** Safe from document callbacks: reads only the immutable volatile observation. */
    void guardRecordingSafety() {
        requirePlaybackSafe();
    }

    /** The one gate for Grid and modulator writes: value writes may play, structural writes need a stopped transport. */
    Runnable writeGuard(HostOperation operation, boolean structural) {
        if (!structural) {
            recordingSafety(operation);
            return this::guardRecordingSafety;
        }
        SafetyObservation observed = stoppedSafety(operation);
        return () -> guardStoppedSafety(observed);
    }

    void guardStoppedSafety(SafetyObservation before) {
        SafetyObservation current = safety;
        if (current.epoch != before.epoch)
            throw Errors.notApplied(
                    ErrorCode.STALE_CONTEXT,
                    "Observed transport safety changed while the Grid write was queued or running.");
        current.requireStopped();
    }

    /** Immutable controller observations; document callbacks never call Controller API safety getters. */
    static final class SafetyObservation {
        final long epoch;
        final Boolean playing, recording, arrangerAutomation, launcherAutomation;

        SafetyObservation(
                long epoch,
                Boolean playing,
                Boolean recording,
                Boolean arrangerAutomation,
                Boolean launcherAutomation) {
            this.epoch = epoch;
            this.playing = playing;
            this.recording = recording;
            this.arrangerAutomation = arrangerAutomation;
            this.launcherAutomation = launcherAutomation;
        }

        void requireNotRecording() {
            if (recording == null || arrangerAutomation == null || launcherAutomation == null)
                throw Errors.notApplied(
                        ErrorCode.NOT_READY, "Recording and automation safety states have not all been observed.");
            if (recording || arrangerAutomation || launcherAutomation)
                throw Errors.notApplied(
                        ErrorCode.UNSAFE_STATE,
                        "Recording or automation write is enabled, so this write is blocked. Turn off recording and"
                                + " automation write (transport.set), then retry.");
        }

        void requireStopped() {
            if (playing == null || recording == null || arrangerAutomation == null || launcherAutomation == null)
                throw Errors.notApplied(
                        ErrorCode.NOT_READY,
                        "Stopped transport, recording and automation safety have not all been observed.");
            if (playing || recording || arrangerAutomation || launcherAutomation)
                throw Errors.notApplied(
                        ErrorCode.UNSAFE_STATE,
                        "This write needs a stopped transport with recording and automation write off (playing="
                                + playing + ", recording=" + recording + ", arrangerAutomation=" + arrangerAutomation
                                + ", launcherAutomation=" + launcherAutomation
                                + "). Stop playback and turn them off with transport.set, then retry.");
        }
    }

    Track track(String trackId) {
        return (Track) tracks.getItemAt(trackSlot(trackId));
    }

    int trackSlot(String trackId) {
        if (!tracksObserved) throw Errors.notApplied(ErrorCode.NOT_READY, "Track bank has not reported its size yet.");
        for (int i = 0; i < TRACKS; i++) {
            if (trackMatches(trackId, (Track) tracks.getItemAt(i))) return i;
        }
        throw Errors.notApplied(
                ErrorCode.OUTSIDE_WINDOW, "Track UUID is absent from the current " + TRACKS + "-track window.");
    }

    boolean trackMatches(String trackId, Track track) {
        return track.exists().get() && trackId.equals(track.channelId().get());
    }

    /** Which Bitwig process answered: pid plus display variables (null when unset). */
    static JsonObject instance() {
        JsonObject result = new JsonObject();
        result.addProperty("pid", ProcessHandle.current().pid());
        ProcessHandle.current().parent().ifPresent(parent -> result.addProperty("parentPid", parent.pid()));
        result.addProperty("display", System.getenv("DISPLAY"));
        result.addProperty("waylandDisplay", System.getenv("WAYLAND_DISPLAY"));
        return result;
    }

    static JsonObject observed() {
        JsonObject result = new JsonObject();
        result.addProperty("status", "observed");
        return result;
    }

    static JsonObject window(int offset, int capacity, int total) {
        JsonObject result = new JsonObject();
        result.addProperty("offset", offset);
        result.addProperty("capacity", capacity);
        result.addProperty("total", total);
        result.addProperty("complete", offset == 0 && total >= 0 && total <= capacity);
        return result;
    }

    @Override
    public void close() {
        closed = true;
    }
}
