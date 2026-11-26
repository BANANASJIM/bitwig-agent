package dev.bitwigagent;

import com.bitwig.extension.callback.IntegerValueChangedCallback;
import com.bitwig.extension.controller.api.Track;
import com.google.gson.*;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Track VU meters over a bounded window, from the public Channel.addVuMeterObserver (left/right, peak and RMS).
 * The API publishes the 0..RANGE-1 step count but no dB mapping, so values are reported as steps, never as dB.
 */
final class MeterModule implements HostModule {
    static final Set<String> METHODS = Set.of("tracks.meters");
    static final int RANGE = 128, MIN_WINDOW_MS = 50, MAX_WINDOW_MS = 5000, POLL_MS = 20;
    private static final Pattern UUID =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private final HostServices s;
    // [track][channel] last callback value: observers only fire on change, so these are the standing levels.
    private final int[][] peak = new int[HostServices.TRACKS][2], rms = new int[HostServices.TRACKS][2];
    private final List<Window> windows = new ArrayList<>();
    private String unavailable;

    MeterModule(HostServices services) {
        s = services;
        for (int t = 0; t < HostServices.TRACKS; t++) {
            Track track = (Track) s.tracks.getItemAt(t);
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

    static void validateParams(String method, JsonObject p) {
        if (!method.equals("tracks.meters")) throw HostServices.failure("unknown_method", "Unknown meter method.");
        Params.fields(p, Set.of("trackIds", "windowMs"), Set.of());
        Params.integer(p, "windowMs", MIN_WINDOW_MS, MAX_WINDOW_MS);
        JsonElement ids = p.get("trackIds");
        if (!ids.isJsonArray()
                || ids.getAsJsonArray().size() < 1
                || ids.getAsJsonArray().size() > HostServices.TRACKS)
            throw HostServices.failure(
                    "invalid_params", "trackIds must hold 1.." + HostServices.TRACKS + " track UUIDs.");
        Set<String> seen = new HashSet<>();
        for (JsonElement id : ids.getAsJsonArray()) {
            if (!id.isJsonPrimitive()
                    || !id.getAsJsonPrimitive().isString()
                    || !UUID.matcher(id.getAsString()).matches())
                throw HostServices.failure("invalid_params", "trackIds entries must be canonical UUIDs.");
            if (!seen.add(id.getAsString().toLowerCase(Locale.ROOT)))
                throw HostServices.failure("invalid_params", "trackIds must be unique.");
        }
    }

    private static final class Window {
        final HostOperation op;
        final int[] slots;
        final String[] ids;
        final int[][] peakMax = new int[HostServices.TRACKS][2], rmsMax = new int[HostServices.TRACKS][2];
        final long[][] rmsSum = new long[HostServices.TRACKS][2];
        final int[] callbacks = new int[HostServices.TRACKS];
        int ticks;

        Window(HostOperation op, int[] slots, String[] ids) {
            this.op = op;
            this.slots = slots;
            this.ids = ids;
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
        s.check(op);
        if (unavailable != null) throw HostServices.failure("unsupported_scope", unavailable);
        JsonArray requested = p.getAsJsonArray("trackIds");
        int[] slots = new int[requested.size()];
        String[] ids = new String[requested.size()];
        for (int i = 0; i < slots.length; i++) {
            ids[i] = requested.get(i).getAsString();
            slots[i] = s.trackSlot(ids[i]);
        }
        int windowMs = p.get("windowMs").getAsInt();
        long end = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(windowMs);
        if (op.deadline - end < HostServices.DEADLINE_MARGIN_NANOS)
            throw HostServices.failure(
                    "invalid_params", "timeoutMs is too short for windowMs; allow windowMs plus about 200 ms.");
        windows.removeIf(old -> old.op.result.isDone());
        Window w = new Window(op, slots, ids);
        // Standing levels count: a steady meter fires no callback during the window.
        for (int t : slots)
            for (int c = 0; c < 2; c++) {
                w.peakMax[t][c] = peak[t][c];
                w.rmsMax[t][c] = rms[t][c];
            }
        windows.add(w);
        Boolean playingAtStart = s.playing();
        poll(op, w, end, windowMs, playingAtStart);
    }

    private void poll(HostOperation op, Window w, long end, int windowMs, Boolean playingAtStart) {
        w.tick(rms);
        if (System.nanoTime() < end) {
            s.schedule(op, () -> poll(op, w, end, windowMs, playingAtStart), POLL_MS);
            return;
        }
        windows.remove(w);
        JsonObject out = HostServices.observed();
        out.addProperty("scope", "vu_meters_over_window");
        out.addProperty("windowMs", windowMs);
        out.addProperty("polls", w.ticks);
        out.addProperty("range", RANGE);
        out.addProperty("unit", "vu_steps_0_to_" + (RANGE - 1) + "_public_api_publishes_no_db_mapping");
        out.add("playingAtStart", playingAtStart == null ? JsonNull.INSTANCE : new JsonPrimitive(playingAtStart));
        out.add("playingAtEnd", s.playing() == null ? JsonNull.INSTANCE : new JsonPrimitive(s.playing()));
        JsonArray tracks = new JsonArray();
        for (int i = 0; i < w.slots.length; i++) {
            int t = w.slots[i];
            JsonObject row = new JsonObject();
            row.addProperty("trackId", w.ids[i]);
            boolean same = s.trackMatches(w.ids[i], (Track) s.tracks.getItemAt(t));
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
