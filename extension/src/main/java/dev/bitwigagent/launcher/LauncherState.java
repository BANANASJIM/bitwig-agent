package dev.bitwigagent.launcher;

import com.bitwig.extension.callback.*;
import com.bitwig.extension.controller.api.*;
import com.google.gson.*;
import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.Holds;
import dev.bitwigagent.core.HostLimits;
import dev.bitwigagent.core.HostOperation;
import dev.bitwigagent.core.Outcome;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.core.Results;
import dev.bitwigagent.core.RpcException;
import dev.bitwigagent.core.Scheduler;
import dev.bitwigagent.core.Verification;
import dev.bitwigagent.host.LauncherStructureLock;
import dev.bitwigagent.host.SelectionLease;
import dev.bitwigagent.host.TrackWindow;
import dev.bitwigagent.host.TransportSafety;
import dev.bitwigagent.render.RenderModule;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * What the launcher, clip and note modules share: the slot proxies of the scene window, the launcher cursor clip and
 * how an operation binds it, and the unresolved native mutations. Every operation here runs inside a scene window
 * lease and addresses slots by absolute scene.
 */
public final class LauncherState implements RenderModule.LauncherContent {
    // Bitwig 6.1.3 debounces step-size grid regeneration by 100ms before queuing controller delivery.
    // This margin avoids consuming that known old cache; it is not a configuration acknowledgement.
    static final int NOTE_GRID_SETTLE_MS = 150;
    private static final int NOTE_GRID_SIZE = 128;

    /** Whether any slot of scenes 0-15 of the track in this bank slot holds a clip; null while one is unobserved. */
    @Override
    public Boolean hasContent(int track) {
        boolean any = false;
        for (int j = 0; j < Math.min(HostLimits.SCENES, window.sceneCount()); j++) {
            Slot slot = slots[track][j];
            if (!slot.contentReady) return null;
            any |= slot.observedContent;
        }
        return any;
    }

    /** Whether a launcher clip of scenes 0-15 of the track is playing or queued (overriding the arranger); null while unobserved. */
    @Override
    public Boolean playing(int track) {
        boolean any = false;
        for (int j = 0; j < Math.min(HostLimits.SCENES, window.sceneCount()); j++) {
            Slot slot = slots[track][j];
            if (!slot.playingReady || !slot.queuedReady) return null;
            any |= slot.api.isPlaying().get() || slot.api.isPlaybackQueued().get();
        }
        return any;
    }

    /** Whether the track was stopped from the launcher (Track.isStopped); null while unobserved. */
    @Override
    public Boolean stopped(int track) {
        return stopReady[track] ? window.at(track).isStopped().get() : null;
    }

    /** One slot of the window, the track it sits on and the scene it holds now. */
    public static final class Target {
        public final LauncherTargets.Loc at;
        final Track track;
        public final Slot slot;

        Target(LauncherTargets.Loc at, Track track, Slot slot) {
            this.at = at;
            this.track = track;
            this.slot = slot;
        }

        String id() {
            return at.trackId();
        }

        int scene() {
            return at.scene;
        }
    }

    /** Runs once the cursor clip is bound to the target's slot and its note grid is settled. */
    interface Bound {
        void run(HostOperation op, Target target, long end, NoteGrid.Window grid);
    }

    final Scheduler scheduler;
    final TrackWindow window;
    final TransportSafety transportSafety;
    final SelectionLease selection;
    final LauncherStructureLock lock;
    final Holds holds;
    public final LauncherTargets targets;
    final Clip clip;
    public final Track clipTrack;
    public final Slot[][] slots = new Slot[HostLimits.TRACKS][HostLimits.SCENES];
    final boolean[] stopReady = new boolean[HostLimits.TRACKS];
    // Callback counters of the cursor clip's launch properties, in ClipModule.LAUNCH order.
    final long[] launchSequence = new long[3];
    public boolean clipExistsReady, lengthReady;
    // Only positive completion releases these holds; completed futures and later callbacks cannot release one.
    private Mutation cursorMutation;

    public LauncherState(
            ControllerHost host,
            Scheduler scheduler,
            TrackWindow window,
            TransportSafety transportSafety,
            SelectionLease selection,
            LauncherStructureLock lock,
            Holds holds,
            LauncherTargets targets) {
        this.scheduler = scheduler;
        this.window = window;
        this.transportSafety = transportSafety;
        this.selection = selection;
        this.lock = lock;
        this.holds = holds;
        this.targets = targets;
        clip = host.createLauncherCursorClip(NOTE_GRID_SIZE, NOTE_GRID_SIZE);
        clipTrack = clip.getTrack();
        clipTrack.channelId().markInterested();
        clipTrack.exists().markInterested();
        clip.exists().addValueObserver((BooleanValueChangedCallback) value -> clipExistsReady = true);
        clip.getLoopLength().addValueObserver((DoubleValueChangedCallback) value -> lengthReady = true);
        clip.color().markInterested();
        clip.launchQuantization().markInterested();
        clip.launchMode().markInterested();
        clip.useLoopStartAsQuantizationReference().markInterested();
        clip.launchQuantization().addValueObserver((EnumValueChangedCallback) value -> launchSequence[0]++);
        clip.launchMode().addValueObserver((EnumValueChangedCallback) value -> launchSequence[1]++);
        clip.useLoopStartAsQuantizationReference()
                .addValueObserver((BooleanValueChangedCallback) value -> launchSequence[2]++);
        clip.getPlayStart().markInterested();
        clip.getPlayStop().markInterested();
        clip.getLoopStart().markInterested();
        clip.isLoopEnabled().markInterested();
        // The grid is read through getStep; the subscription stays because dropping it is untested on a host.
        clip.addNoteStepObserver(note -> {});
        ClipLauncherSlot cursorSlot = clip.clipLauncherSlot();
        cursorSlot.sceneIndex().markInterested();
        for (int t = 0; t < HostLimits.TRACKS; t++) {
            Track track = window.at(t);
            final int index = t;
            track.isStopped().addValueObserver((BooleanValueChangedCallback) value -> stopReady[index] = true);
            track.channelId().addValueObserver((StringValueChangedCallback) value -> {
                for (Mutation mutation : mutations())
                    if (mutation.track == track && !mutation.trackId.equals(value)) mutation.rebound = true;
            });
            track.exists().addValueObserver((BooleanValueChangedCallback) value -> {
                if (!value) invalidateMutations(track);
            });
            track.isQueuedForStop().markInterested();
            track.arm().markInterested();
            ClipLauncherSlotBank bank = track.clipLauncherSlotBank();
            for (int j = 0; j < HostLimits.SCENES; j++)
                slots[t][j] = new Slot((ClipLauncherSlot) bank.getItemAt(j), cursorSlot);
        }
    }

    public Target target(LauncherTargets.Loc at) {
        return new Target(at, window.at(at.track), slots[at.track][at.position()]);
    }

    // ---- leases, holds and mutations ----

    void acquire(HostOperation op) {
        selection.acquire(op);
        cursorMutation = null;
    }

    void requireResolved(String id, int scene) {
        lock.requireNoSettingsWrite();
        for (Mutation mutation : mutations())
            if (mutation.trackId.equals(id) && mutation.scene == scene)
                throw holds.blocked(
                        LauncherStructureLock.MUTATION,
                        mutation.description() + " Writes on this slot are held; reads still work.",
                        "Inspect launcher.list and notes.get");
    }

    private List<Mutation> mutations() {
        return holds.keys(LauncherStructureLock.MUTATION, Mutation.class);
    }

    void beginMutation(Target target, String method) {
        cursorMutation = new Mutation(target, method);
        Mutation mutation = cursorMutation;
        holds.place(LauncherStructureLock.MUTATION, mutation, mutation::description);
    }

    long contentBefore() {
        return cursorMutation.contentBefore;
    }

    void finishMutation() {
        holds.release(LauncherStructureLock.MUTATION, cursorMutation);
        cursorMutation = null;
    }

    private void invalidateMutations(Track track) {
        for (Mutation mutation : mutations()) if (mutation.track == track) mutation.rebound = true;
    }

    // ---- guards ----

    void guard(HostOperation op, Target target) {
        scheduler.check(op);
        if (op.result.isDone()) throw Errors.of(Reason.CANCELLED, "Operation is no longer active", op.dispatched());
        if (selection.ownedBy(op) && cursorMutation != null && cursorMutation.rebound)
            throw Errors.unknown(
                    Reason.NOT_READY,
                    cursorMutation.description()
                            + " Its target binding changed; the mutation stays held until acked after inspection.");
        if (!window.trackMatches(target.id(), target.track)
                || target.at.offset >= 0
                        && (window.sceneOffset() != target.at.offset
                                || target.slot.api.sceneIndex().get() != target.scene()))
            throw Errors.of(Reason.NOT_READY, "The track or the scene window changed", op.dispatched());
    }

    /**
     * The check a native task on the document thread runs: the operation is live and the scene window has not moved
     * (the slot proxy follows its bank position). The task itself verifies the native track and clip.
     */
    Runnable nativeCheck(HostOperation op, Target target) {
        int offset = target.at.offset;
        return () -> {
            scheduler.check(op);
            if (offset >= 0 && window.sceneOffset() != offset)
                throw Errors.notApplied(
                        Reason.TARGET_CHANGED, "The scene window changed while the native note write was queued");
        };
    }

    void cursorGuard(HostOperation op, Target target) {
        guard(op, target);
        if (!selection.ownedBy(op) || !bound(target.id(), target.slot))
            throw Errors.of(Reason.NOT_READY, "Launcher cursor no longer proxies the explicit slot", op.dispatched());
    }

    boolean bound(String id, Slot slot) {
        return slot.equalReady
                && slot.equal.get()
                && clipExistsReady
                && clip.exists().get()
                && lengthReady
                && clipTrack.exists().get()
                && id.equals(clipTrack.channelId().get())
                && slot.api.hasContent().get();
    }

    static void ready(Slot slot) {
        if (!slot.ready()) throw Errors.notApplied(Reason.NOT_READY, "Launcher slot metadata has not been observed");
    }

    static void requireContent(Slot slot) {
        if (!slot.api.hasContent().get()) throw Errors.notApplied(Reason.TARGET_NOT_FOUND, "The slot is empty");
    }

    long until(HostOperation op) {
        return scheduler.observeUntil(op);
    }

    void awaitGuarded(HostOperation op, Runnable guard, long end, BooleanSupplier done, Runnable ok, Runnable timeout) {
        scheduler.poll(
                op,
                end,
                () -> {
                    guard.run();
                    return done.getAsBoolean();
                },
                ok,
                timeout);
    }

    // ---- binding the cursor clip ----

    /**
     * Selects the target's clip into the launcher cursor and settles it. {@code dispatches} says whether selecting
     * already counts as a write; {@code grid} sizes the note window once the clip is bound (null: no grid).
     */
    void bind(
            HostOperation op, Target target, boolean dispatches, Supplier<NoteGrid.Window> grid, long end, Bound then) {
        guard(op, target);
        if (dispatches) op.markDispatched();
        target.slot.api.select();
        scheduler.schedule(op, () -> awaitBinding(op, target, grid, end, then), 40);
    }

    private void awaitBinding(HostOperation op, Target target, Supplier<NoteGrid.Window> grid, long end, Bound then) {
        awaitGuarded(
                op,
                () -> guard(op, target),
                end,
                () -> bound(target.id(), target.slot),
                () -> {
                    NoteGrid.Window w = grid == null ? null : grid.get();
                    if (w != null) {
                        clip.setStepSize(w.size);
                        clip.scrollToKey(0);
                        clip.scrollToStep(w.start);
                    }
                    scheduler.schedule(
                            op,
                            () -> settledBinding(op, target, grid, w, end, then),
                            w != null ? NOTE_GRID_SETTLE_MS : Scheduler.POLL_MS);
                },
                () -> {
                    throw Errors.of(
                            Reason.NOT_OBSERVED,
                            "Cursor slot identity or clip content was not observed",
                            op.dispatched());
                });
    }

    private void settledBinding(
            HostOperation op, Target target, Supplier<NoteGrid.Window> grid, NoteGrid.Window w, long end, Bound then) {
        guard(op, target);
        if (!bound(target.id(), target.slot)) {
            awaitBinding(op, target, grid, end, then);
            return;
        }
        // API exposes no grid-configuration acknowledgement. This is settling, not a proven data barrier.
        then.run(op, target, end, w);
    }

    // ---- results ----

    static JsonObject written(Outcome outcome, Verification how) {
        return Results.outcome(outcome, how);
    }

    /** An unknown result whose slot stays held until the agent inspects it and acks the hold. */
    JsonObject unresolved(JsonObject out, Reason reason, String message) {
        Results.unknown(out);
        out.addProperty("reason", reason.wire());
        out.addProperty("message", message);
        out.addProperty(
                "next",
                holds.blocked(LauncherStructureLock.MUTATION, message, "Inspect launcher.list and notes.get").next);
        return out;
    }

    RpcException unobserved(Reason code, String what) {
        return Errors.unknown(
                code,
                what
                        + " was dispatched but its outcome was not observed; writes on this slot are held until acked after inspection."
                        + " Inspect with launcher.list / notes.get, never retry.");
    }

    void addClipMetadata(JsonObject out) {
        JsonObject loop = new JsonObject();
        loop.addProperty("length", clip.getLoopLength().get());
        loop.addProperty("start", clip.getLoopStart().get());
        out.add("loop", loop);
        out.addProperty("playStart", clip.getPlayStart().get());
        out.addProperty("playStop", clip.getPlayStop().get());
        out.addProperty("loopEnabled", clip.isLoopEnabled().get());
    }

    JsonObject slotJson(Target target) {
        JsonObject out = new JsonObject();
        out.addProperty("hasContent", target.slot.api.hasContent().get());
        out.addProperty("name", target.slot.api.name().get());
        out.addProperty("playing", target.slot.api.isPlaying().get());
        out.addProperty("queued", target.slot.api.isPlaybackQueued().get());
        out.addProperty("recording", target.slot.api.isRecording().get());
        return out;
    }

    /** The current ref of the clip in the target's slot. */
    public JsonObject clipRef(Target target) {
        return targets.clipRef(target.at, target.slot.api.name().get());
    }

    // ---- playback ----

    /** Waits for a launch to be observed playing or queued, then completes the result. */
    void awaitPlayback(HostOperation op, Target target, Slot slot, JsonObject ref) {
        BooleanSupplier observed = () -> slot.api.isPlaying().get();
        BooleanSupplier queued = () -> slot.api.isPlaybackQueued().get();
        scheduler.poll(
                op,
                until(op),
                () -> {
                    guard(op, target);
                    return observed.getAsBoolean() || queued.getAsBoolean();
                },
                done -> finishPlayback(op, target, slot, ref, observed.getAsBoolean(), queued.getAsBoolean()));
    }

    private void finishPlayback(
            HostOperation op, Target target, Slot slot, JsonObject ref, boolean observed, boolean queued) {
        JsonObject out = written(
                observed ? Outcome.APPLIED : queued ? Outcome.SENT : Outcome.UNKNOWN, Verification.API_READBACK);
        out.add("ref", ref);
        if (!observed && !queued) out.addProperty("reason", Reason.NOT_OBSERVED.wire());
        out.addProperty("playbackState", observed ? "playing" : queued ? "queued" : "not_observed");
        out.add("slot", slotJson(target));
        op.result.complete(out);
    }

    /** Waits for a slot record to be observed recording or queued, then completes the result. */
    void awaitRecording(HostOperation op, Target target, JsonObject ref) {
        ClipLauncherSlot api = target.slot.api;
        scheduler.poll(
                op,
                until(op),
                () -> {
                    guard(op, target);
                    return api.isRecording().get() || api.isRecordingQueued().get();
                },
                done -> {
                    boolean recording = api.isRecording().get(),
                            queued = api.isRecordingQueued().get();
                    JsonObject out = written(
                            recording ? Outcome.APPLIED : queued ? Outcome.SENT : Outcome.UNKNOWN,
                            Verification.API_READBACK);
                    out.add("ref", ref);
                    if (!recording && !queued) out.addProperty("reason", Reason.NOT_OBSERVED.wire());
                    out.addProperty("recordingState", recording ? "recording" : queued ? "queued" : "not_observed");
                    out.add("slot", slotJson(target));
                    op.result.complete(out);
                });
    }

    // ---- cursor identity, for reads ----

    /** The absolute scene of the cursor clip's own slot; -1 while that is not observed. */
    public int cursorScene() {
        return clip.exists().get() ? clip.clipLauncherSlot().sceneIndex().get() : -1;
    }

    /** Whether the launcher cursor clip is this slot's clip; null until the cursor identity has been observed. */
    public Boolean selected(Slot slot, String trackId) {
        if (!slot.equalReady || !clipExistsReady) return null;
        return slot.equal.get()
                && clip.exists().get()
                && slot.api.hasContent().get()
                && clipTrack.exists().get()
                && trackId.equals(clipTrack.channelId().get());
    }

    static final class Mutation {
        final String trackId, method;
        final Track track;
        final int scene;
        final long contentBefore;
        boolean rebound;

        Mutation(Target target, String method) {
            this.trackId = target.id();
            this.track = target.track;
            this.scene = target.scene();
            this.method = method;
            contentBefore = target.slot.contentVersion;
        }

        String description() {
            return "A prior " + method + " was dispatched for the clip slot of track " + trackId + " at scene " + scene
                    + (rebound ? "; its native target binding changed." : ".");
        }
    }

    public static final class Slot {
        public final ClipLauncherSlot api;
        final BooleanValue equal;
        boolean contentReady, nameReady, playingReady, queuedReady, recordingReady, equalReady;
        boolean observedContent;
        long contentVersion;

        Slot(ClipLauncherSlot api, ClipLauncherSlot cursor) {
            this.api = api;
            equal = cursor.createEqualsValue(api);
            equal.addValueObserver((BooleanValueChangedCallback) value -> equalReady = true);
            api.hasContent().addValueObserver((BooleanValueChangedCallback) value -> {
                contentReady = true;
                observedContent = value;
                contentVersion++;
            });
            api.name().addValueObserver((StringValueChangedCallback) value -> nameReady = true);
            api.isPlaying().addValueObserver((BooleanValueChangedCallback) value -> playingReady = true);
            api.isPlaybackQueued().addValueObserver((BooleanValueChangedCallback) value -> queuedReady = true);
            api.isRecording().addValueObserver((BooleanValueChangedCallback) value -> recordingReady = true);
            api.isRecordingQueued().markInterested();
        }

        public boolean ready() {
            return contentReady && nameReady && playingReady && queuedReady && recordingReady;
        }
    }
}
