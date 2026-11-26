package dev.bitwigagent.launcher;

import com.bitwig.extension.controller.api.Track;
import com.google.gson.*;
import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.HostLimits;
import dev.bitwigagent.core.HostModule;
import dev.bitwigagent.core.HostOperation;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.core.Results;
import dev.bitwigagent.core.Scheduler;
import dev.bitwigagent.host.TrackRefs;
import dev.bitwigagent.host.TrackWindow;
import java.util.Set;

/** Per-track launcher reads and stops: every scene of one track's slots, and the track's stop. */
public final class LauncherModule implements HostModule {
    static final Set<String> METHODS = Set.of("launcher.list", "launcher.stop");

    private final LauncherState state;
    private final TrackWindow window;
    private final TrackRefs trackRefs;
    private final Scheduler scheduler;

    public LauncherModule(LauncherState state, TrackRefs trackRefs) {
        this.state = state;
        this.window = state.window;
        this.trackRefs = trackRefs;
        this.scheduler = state.scheduler;
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
        if (method.equals("launcher.stop")) stop(op, p.getAsJsonObject("target"));
        else list(op, p);
    }

    private void stop(HostOperation op, JsonObject ref) {
        int t = trackRefs.resolve(ref, true);
        if (!state.stopReady[t]) throw Errors.notApplied(Reason.NOT_READY, "Track stop state has not been observed");
        Track track = window.at(t);
        JsonObject trackRef = trackRefs.ref(t);
        LauncherState.Target target =
                new LauncherState.Target(new LauncherTargets.Loc(t, -1, -1, trackRef, null), track, null);
        state.guard(op, target);
        op.markDispatched();
        track.clipLauncherSlotBank().stop();
        state.awaitPlayback(op, target, null, trackRef);
    }

    private void list(HostOperation op, JsonObject p) {
        int t = trackRefs.resolve(p.getAsJsonObject("parent"), false);
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
