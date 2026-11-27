package dev.bitwigagent.launcher;

import com.bitwig.extension.controller.api.Action;
import com.bitwig.extension.controller.api.Application;
import com.bitwig.extension.controller.api.Track;
import com.bitwig.extension.controller.api.Transport;
import com.google.gson.*;
import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.HostLimits;
import dev.bitwigagent.core.HostModule;
import dev.bitwigagent.core.HostOperation;
import dev.bitwigagent.core.Outcome;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.core.Results;
import dev.bitwigagent.core.Scheduler;
import dev.bitwigagent.core.Verification;
import dev.bitwigagent.host.TrackRefs;
import dev.bitwigagent.host.TrackWindow;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Launcher playback per track or across the project: every scene of one track's slots, stopping one track or all, and
 * returning one track or all to Arranger playback.
 */
public final class LauncherModule implements HostModule {
    static final String STOP = "launcher.stop", RETURN = "launcher.returnToArrangement";
    static final Set<String> METHODS = Set.of("launcher.list", STOP, RETURN);
    /** Bitwig's Stop Playback of All Tracks; API 25 has no stop-all call. */
    static final String STOP_ALL_ACTION = "stop_playback_of_all_tracks";

    static final String STOPPED = "stopped", QUEUED = "queued", ARRANGER = "arranger", LAUNCHER = "launcher";

    private final LauncherState state;
    private final TrackWindow window;
    private final TrackRefs trackRefs;
    private final Scheduler scheduler;
    private final Application application;
    private final Transport transport;

    public LauncherModule(LauncherState state, TrackRefs trackRefs, Application application, Transport transport) {
        this.state = state;
        this.window = state.window;
        this.trackRefs = trackRefs;
        this.scheduler = state.scheduler;
        this.application = application;
        this.transport = transport;
    }

    @Override
    public Set<String> methods() {
        return METHODS;
    }

    @Override
    public void validate(String method, JsonObject params) {}

    @Override
    public void dispatch(HostOperation op, String method, JsonObject p) {
        scheduler.check(op);
        if (!method.equals(STOP) && !method.equals(RETURN)) {
            list(op, p);
            return;
        }
        boolean stop = method.equals(STOP);
        JsonElement target = p.get("target");
        if (target.isJsonPrimitive()) all(op, stop);
        else track(op, target.getAsJsonObject(), stop);
    }

    /** Every track at once: Bitwig's stop-all action, or Transport.returnToArrangement; read back over the window. */
    private void all(HostOperation op, boolean stop) {
        if (!window.observed()) throw Errors.notApplied(Reason.NOT_READY, "Track bank has not reported its size yet.");
        Runnable dispatch = transport::returnToArrangement;
        if (stop) {
            Action action = application.getAction(STOP_ALL_ACTION);
            if (action == null)
                throw Errors.notApplied(
                        Reason.UNKNOWN_NAME, "Action " + STOP_ALL_ACTION + " is absent from the host catalog.");
            dispatch = action::invoke;
        }
        op.markDispatched();
        dispatch.run();
        await(op, () -> scheduler.check(op), -1, stop, null);
    }

    /** One track: its launcher slot bank's stop, or Track.returnToArrangement. */
    private void track(HostOperation op, JsonObject ref, boolean stop) {
        int t = trackRefs.resolve(ref, true);
        if (!state.stopReady[t]) throw Errors.notApplied(Reason.NOT_READY, "Track stop state has not been observed");
        Track track = window.at(t);
        JsonObject trackRef = trackRefs.ref(t);
        LauncherState.Target target =
                new LauncherState.Target(new LauncherTargets.Loc(t, -1, -1, trackRef, null), track, null);
        state.guard(op, target);
        op.markDispatched();
        if (stop) {
            track.clipLauncherSlotBank().stop();
            state.awaitPlayback(op, target, null, trackRef);
        } else {
            track.returnToArrangement();
            await(op, () -> state.guard(op, target), t, false, trackRef);
        }
    }

    /** Polls the tracks (one, or every existing window track when track is -1) until each reaches the wanted state. */
    private void await(HostOperation op, Runnable guard, int track, boolean stop, JsonObject ref) {
        scheduler.poll(
                op,
                state.until(op),
                () -> {
                    guard.run();
                    for (int t : tracks(track)) if (reached(playback(t), stop) == Outcome.UNKNOWN) return false;
                    return true;
                },
                done -> finish(op, tracks(track), stop, ref));
    }

    private void finish(HostOperation op, List<Integer> tracks, boolean stop, JsonObject ref) {
        Outcome outcome = Outcome.APPLIED;
        JsonArray rows = new JsonArray();
        String last = null;
        for (int t : tracks) {
            last = playback(t);
            Outcome one = reached(last, stop);
            if (one == Outcome.UNKNOWN || outcome == Outcome.APPLIED) outcome = one;
            JsonObject row = new JsonObject();
            row.add("ref", trackRefs.ref(t));
            row.addProperty("playbackState", last == null ? "not_observed" : last);
            rows.add(row);
        }
        boolean complete = (ref != null || window.offset() == 0 && window.total() <= HostLimits.TRACKS)
                && (stop
                        || window.scenesObserved()
                                && window.sceneOffset() == 0
                                && window.sceneCount() <= HostLimits.SCENES);
        if (outcome == Outcome.APPLIED && !complete) outcome = Outcome.SENT;
        JsonObject out = Results.outcome(outcome, Verification.API_READBACK);
        if (outcome == Outcome.UNKNOWN) out.addProperty("reason", Reason.NOT_OBSERVED.wire());
        if (ref != null) {
            out.add("ref", ref);
            out.addProperty("playbackState", last == null ? "not_observed" : last);
        } else {
            out.addProperty("target", "all");
            out.add("tracks", rows);
        }
        out.addProperty("complete", complete);
        op.result.complete(out);
    }

    /** applied when the track reached the wanted state, sent for a queued stop, unknown otherwise or unobserved. */
    private static Outcome reached(String playback, boolean stop) {
        if (stop && STOPPED.equals(playback) || !stop && ARRANGER.equals(playback)) return Outcome.APPLIED;
        return stop && QUEUED.equals(playback) ? Outcome.SENT : Outcome.UNKNOWN;
    }

    private List<Integer> tracks(int track) {
        List<Integer> out = new ArrayList<>();
        if (track >= 0) out.add(track);
        else
            for (int t = 0; t < Math.min(HostLimits.TRACKS, window.total() - window.offset()); t++)
                if (window.at(t).exists().get()) out.add(t);
        return out;
    }

    /**
     * What the track plays as observed: stopped from the launcher, a queued stop, a launcher clip of the scene window
     * playing or queued, else the arranger; null while unobserved.
     */
    private String playback(int t) {
        if (!state.stopReady[t]) return null;
        Track track = window.at(t);
        if (track.isStopped().get()) return STOPPED;
        if (track.isQueuedForStop().get()) return QUEUED;
        Boolean launcher = state.playing(t);
        return launcher == null ? null : launcher ? LAUNCHER : ARRANGER;
    }

    private void list(HostOperation op, JsonObject p) {
        int t = trackRefs.resolve(p.getAsJsonObject("target"), false);
        if (!window.scenesObserved())
            throw Errors.notApplied(Reason.NOT_READY, "Scene bank has not reported its size yet.");
        int total = window.sceneCount(),
                first = p.has("sceneOffset") ? p.get("sceneOffset").getAsInt() : 0;
        if (first > 0 && first >= total)
            throw Errors.notApplied(
                    Reason.OUT_OF_BOUNDS, "sceneOffset " + first + " is past the " + total + " scene(s).");
        JsonObject trackRef = trackRefs.ref(t);
        JsonArray rows = new JsonArray();
        long until = scheduler.deadlineUntil(op) - Scheduler.OBSERVE_WINDOW_NANOS;
        state.targets.lease.enter(
                op,
                window.sceneWindowFor(first),
                true,
                true,
                () -> page(op, t, trackRef, first, first, total, rows, until));
    }

    private void page(
            HostOperation op, int t, JsonObject trackRef, int from, int scene, int total, JsonArray rows, long until) {
        int offset = window.sceneOffset(), end = Math.min(total, offset + HostLimits.SCENES);
        for (int s = scene; s < end; s++) rows.add(row(t, trackRef, s, offset));
        if (end < total && scheduler.now() < until) {
            state.targets.lease.move(
                    op, window.sceneWindowFor(end), () -> page(op, t, trackRef, from, end, total, rows, until));
            return;
        }
        JsonObject out = new JsonObject();
        out.add("ref", trackRef);
        out.add("slots", rows);
        JsonObject info = Results.window(from, rows.size(), total);
        info.addProperty("complete", end >= total);
        out.add("window", info);
        out.addProperty("complete", end >= total);
        if (end < total) {
            JsonObject next = new JsonObject();
            next.addProperty("sceneOffset", end);
            out.add("next", next);
        }
        op.result.complete(out);
    }

    private JsonObject row(int t, JsonObject trackRef, int scene, int offset) {
        LauncherTargets.Loc at =
                new LauncherTargets.Loc(t, scene, offset, trackRef, state.targets.sceneRef(scene, offset));
        LauncherState.Target target = state.target(at);
        LauncherState.ready(target.slot);
        JsonObject out = new JsonObject();
        out.add("ref", state.targets.slotRef(at));
        boolean content = target.slot.api.hasContent().get();
        if (content) out.add("clip", state.clipRef(target));
        out.addProperty("hasContent", content);
        out.addProperty("playing", target.slot.api.isPlaying().get());
        out.addProperty("queued", target.slot.api.isPlaybackQueued().get());
        out.addProperty("recording", target.slot.api.isRecording().get());
        Boolean selected = state.selected(target.slot, target.id());
        out.add("selected", selected == null ? JsonNull.INSTANCE : new JsonPrimitive(selected));
        return out;
    }
}
