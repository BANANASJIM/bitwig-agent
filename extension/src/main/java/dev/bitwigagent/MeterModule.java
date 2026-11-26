package dev.bitwigagent;

import com.bitwig.extension.callback.IntegerValueChangedCallback;
import com.bitwig.extension.controller.api.Track;
import com.google.gson.*;
import java.util.*;

/**
 * Track VU meters over a bounded window, from the public Channel.addVuMeterObserver (left/right, peak and RMS).
 * The API publishes the 0..RANGE-1 step count but no dB mapping, so values are reported as steps, never as dB.
 */
final class MeterModule implements HostModule {
    static final Set<String> METHODS = Set.of("meters.get");
    static final int RANGE = 128, MIN_WINDOW_MS = 50, MAX_WINDOW_MS = 5000;
    private final Scheduler scheduler;
    private final TrackWindow window;
    private final TrackRefs trackRefs;
    private final TransportSafety transportSafety;
    // [track][channel] last callback value: observers only fire on change, so these are the standing levels.
    private final int[][] peak = new int[HostLimits.TRACKS][2], rms = new int[HostLimits.TRACKS][2];
    private final List<Window> windows = new ArrayList<>();
    private String unavailable;

    MeterModule(Scheduler scheduler, TrackWindow window, TrackRefs trackRefs, TransportSafety transportSafety) {
        this.scheduler = scheduler;
        this.window = window;
        this.trackRefs = trackRefs;
        this.transportSafety = transportSafety;
        for (int t = 0; t < HostLimits.TRACKS; t++) {
            Track track = window.at(t);
            for (int c = 0; c < 2; c++) {
                final int ti = t, ci = c;
                try {
                    track.addVuMeterObserver(
                            RANGE, c, true, (IntegerValueChangedCallback) v -> record(ti, ci, true, v));
                    track.addVuMeterObserver(
                            RANGE, c, false, (IntegerValueChangedCallback) v -> record(ti, ci, false, v));
                } catch (RuntimeException error) {
                    unavailable = "Channel.addVuMeterObserver failed: " + error;
                }
            }
        }
    }

    private void record(int t, int c, boolean isPeak, int value) {
        (isPeak ? peak : rms)[t][c] = value;
        for (Window w : windows) w.add(t, c, isPeak, value);
    }

    @Override
    public Set<String> methods() {
        return METHODS;
    }

    @Override
    public void validate(String method, JsonObject p) {
        validateParams(method, p);
    }

    /** What the schema cannot say: each track is metered once. */
    static void validateParams(String method, JsonObject p) {
        Set<String> seen = new HashSet<>();
        for (JsonElement target : p.getAsJsonArray("targets"))
            if (!seen.add(target.getAsJsonObject().get("id").getAsString().toLowerCase(Locale.ROOT)))
                throw Errors.invalid("targets must be unique.");
    }

    private static final class Window {
        final HostOperation op;
        final int[] slots;
        final JsonObject[] refs;
        final int[][] peakMax = new int[HostLimits.TRACKS][2], rmsMax = new int[HostLimits.TRACKS][2];
        final long[][] rmsSum = new long[HostLimits.TRACKS][2];
        final int[] callbacks = new int[HostLimits.TRACKS];
        int ticks;

        Window(HostOperation op, int[] slots, JsonObject[] refs) {
            this.op = op;
            this.slots = slots;
            this.refs = refs;
        }

        void add(int t, int c, boolean isPeak, int value) {
            callbacks[t]++;
            int[][] max = isPeak ? peakMax : rmsMax;
            max[t][c] = Math.max(max[t][c], value);
        }

        void tick(int[][] rms) {
            ticks++;
            for (int t : slots) for (int c = 0; c < 2; c++) rmsSum[t][c] += rms[t][c];
        }
    }

    @Override
    public void dispatch(HostOperation op, String method, JsonObject p) {
        scheduler.check(op);
        if (unavailable != null) throw Errors.notApplied(Reason.UNSUPPORTED_SCOPE, unavailable);
        JsonArray requested = p.getAsJsonArray("targets");
        int[] slots = new int[requested.size()];
        JsonObject[] refs = new JsonObject[requested.size()];
        for (int i = 0; i < slots.length; i++) {
            refs[i] = requested.get(i).getAsJsonObject();
            slots[i] = trackRefs.resolve(refs[i], false);
        }
        int windowMs = p.get("windowMs").getAsInt();
        long end = scheduler.now() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(windowMs);
        if (op.deadline - end < Scheduler.DEADLINE_MARGIN_NANOS)
            throw Errors.notApplied(
                    Reason.INVALID_PARAMS, "timeoutMs is too short for windowMs; allow windowMs plus about 200 ms.");
        windows.removeIf(old -> old.op.result.isDone());
        Window w = new Window(op, slots, refs);
        // Standing levels count: a steady meter fires no callback during the window.
        for (int t : slots)
            for (int c = 0; c < 2; c++) {
                w.peakMax[t][c] = peak[t][c];
                w.rmsMax[t][c] = rms[t][c];
            }
        windows.add(w);
        Boolean playingAtStart = transportSafety.playing();
        scheduler.sample(op, end, () -> w.tick(rms), () -> finish(op, w, windowMs, playingAtStart));
    }

    private void finish(HostOperation op, Window w, int windowMs, Boolean playingAtStart) {
        windows.remove(w);
        JsonObject out = new JsonObject();
        out.addProperty("scope", "vu_meters_over_window");
        out.addProperty("windowMs", windowMs);
        out.addProperty("polls", w.ticks);
        out.addProperty("range", RANGE);
        out.addProperty("unit", "vu_steps_0_to_" + (RANGE - 1) + "_public_api_publishes_no_db_mapping");
        out.add("playingAtStart", playingAtStart == null ? JsonNull.INSTANCE : new JsonPrimitive(playingAtStart));
        out.add(
                "playingAtEnd",
                transportSafety.playing() == null ? JsonNull.INSTANCE : new JsonPrimitive(transportSafety.playing()));
        JsonArray tracks = new JsonArray();
        for (int i = 0; i < w.slots.length; i++) {
            int t = w.slots[i];
            JsonObject row = new JsonObject();
            boolean same = window.trackMatches(w.refs[i].get("id").getAsString(), window.at(t));
            row.add("ref", same ? trackRefs.ref(t) : w.refs[i]);
            row.addProperty("targetUnchanged", same);
            if (same) {
                row.add("peakMax", pair(w.peakMax[t]));
                row.add("rmsMax", pair(w.rmsMax[t]));
                JsonObject mean = new JsonObject();
                mean.addProperty("left", w.ticks == 0 ? 0 : (double) w.rmsSum[t][0] / w.ticks);
                mean.addProperty("right", w.ticks == 0 ? 0 : (double) w.rmsSum[t][1] / w.ticks);
                row.add("rmsMean", mean);
                row.addProperty("callbacks", w.callbacks[t]);
                row.addProperty("silent", w.peakMax[t][0] == 0 && w.peakMax[t][1] == 0);
            }
            tracks.add(row);
        }
        out.add("tracks", tracks);
        out.addProperty(
                "note",
                "Meters move only while audio renders; a stopped transport reads zero. Peak and RMS are the host's VU values, per channel, not LUFS or dBFS.");
        op.result.complete(out);
    }

    private static JsonObject pair(int[] v) {
        JsonObject out = new JsonObject();
        out.addProperty("left", v[0]);
        out.addProperty("right", v[1]);
        return out;
    }
}
