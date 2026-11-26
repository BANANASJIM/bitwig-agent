package dev.bitwigagent.launcher;

import com.bitwig.extension.controller.api.Clip;
import com.bitwig.extension.controller.api.NoteStep;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.HostLimits;
import dev.bitwigagent.core.Reason;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** The note grid of a cursor clip: window, edits and the units the note methods share. Host-free except note reads. */
public final class NoteGrid {
    private NoteGrid() {}

    public static final double BEAT_GRID = HostLimits.LIMITS.beatGrid;
    static final int MAX_CLIP_BEATS = HostLimits.LIMITS.maxClipBeats;
    public static final String BEAT_GRID_LABEL = "1/" + Math.round(1 / BEAT_GRID);
    private static final Set<String> GRID_FIELDS =
            Set.of("stepSizeBeats", "startStep", "steps", "pitchStart", "pitchCount");
    private static final BigDecimal[] DERIVED_GRIDS = {
        new BigDecimal("4"),
        new BigDecimal("2"),
        BigDecimal.ONE,
        new BigDecimal("0.5"),
        new BigDecimal("0.25"),
        new BigDecimal("0.125"),
        new BigDecimal("0.0625"),
        new BigDecimal("0.03125"),
        new BigDecimal("0.015625"),
        BigDecimal.valueOf(BEAT_GRID)
    };

    /** Presentation only: "full" lists the whole window and every edit; the default reports a summary plus unverified edits. */
    static boolean full(JsonObject p) {
        return p.has("detail") && p.get("detail").getAsString().equals("full");
    }

    static final class Window {
        final int channel, start, steps, pitch, pitches;
        final double size;
        final BigDecimal exact;
        final boolean derived;

        Window(int channel, BigDecimal exact, int start, int steps, int pitch, int pitches, boolean derived) {
            if (pitch + pitches > 128) throw Errors.invalid("pitchStart + pitchCount must not exceed 128");
            this.channel = channel;
            this.exact = exact;
            this.size = exact.doubleValue();
            this.start = start;
            this.steps = steps;
            this.pitch = pitch;
            this.pitches = pitches;
            this.derived = derived;
        }

        /** The explicit window of a request that carries all five grid fields. */
        static Window of(JsonObject p, boolean derived) {
            return new Window(
                    p.has("channel") ? p.get("channel").getAsInt() : 0,
                    p.get("stepSizeBeats").getAsBigDecimal(),
                    p.get("startStep").getAsInt(),
                    p.get("steps").getAsInt(),
                    p.get("pitchStart").getAsInt(),
                    p.get("pitchCount").getAsInt(),
                    derived);
        }

        JsonObject json() {
            JsonObject out = new JsonObject();
            out.addProperty("channel", channel);
            out.addProperty("stepSizeBeats", size);
            out.addProperty("startStep", start);
            out.addProperty("steps", steps);
            out.addProperty("pitchStart", pitch);
            out.addProperty("pitchCount", pitches);
            if (derived) out.addProperty("derived", true);
            out.addProperty("startBeat", start * size);
            out.addProperty("endBeatExclusive", (start + steps) * size);
            return out;
        }
    }

    static final class Edit {
        final int x, pitch, velocity, durationSteps;
        final double duration;
        final boolean set;
        long before, generation;
        boolean alreadyMatched, verified;

        Edit(int x, int pitch, int velocity, double duration, int durationSteps, boolean set) {
            this.x = x;
            this.pitch = pitch;
            this.velocity = velocity;
            this.duration = duration;
            this.durationSteps = durationSteps;
            this.set = set;
        }
    }

    static boolean hasGrid(JsonObject p) {
        int present = 0;
        for (String key : GRID_FIELDS) if (p.has(key)) present++;
        if (present != 0 && present != GRID_FIELDS.size())
            throw Errors.invalid(
                    "stepSizeBeats, startStep, steps, pitchStart and pitchCount must be supplied together or all omitted");
        return present != 0;
    }

    /** The explicit window, or for a write the smallest one derived from its edits; null for a read sized from the clip loop. */
    static Window window(JsonObject p, JsonArray edits) {
        if (edits != null) checkShape(edits);
        if (hasGrid(p)) return Window.of(p, false);
        return edits == null ? null : deriveEditWindow(p, edits);
    }

    /** A set carries velocity and duration, a clear neither: the rule the schema cannot say per op. */
    private static void checkShape(JsonArray array) {
        for (JsonElement value : array) {
            JsonObject e = value.getAsJsonObject();
            boolean set = e.get("op").getAsString().equals("set"),
                    carries = e.has("velocity") && e.has("durationBeats");
            if (set ? !carries : e.has("velocity") || e.has("durationBeats"))
                throw Errors.invalid("set requires velocity and durationBeats; clear forbids both");
        }
    }

    static int aligned(JsonElement beat, JsonElement size, String field) {
        BigDecimal[] parts = beat.getAsBigDecimal().divideAndRemainder(size.getAsBigDecimal());
        if (parts[1].signum() != 0)
            throw Errors.invalid(field + " must exactly align with stepSizeBeats; no rounding is performed");
        try {
            return parts[0].intValueExact();
        } catch (ArithmeticException error) {
            throw Errors.invalid(field + " exceeds supported grid range");
        }
    }

    /** The edits aligned to the window; no edit leaves it, repeats a cell or overlaps a same-pitch set. */
    static List<Edit> edits(JsonArray array, Window w) {
        checkShape(array);
        List<Edit> result = new ArrayList<>(array.size());
        Set<Integer> cells = new HashSet<>();
        for (JsonElement value : array) {
            JsonObject e = value.getAsJsonObject();
            boolean set = e.get("op").getAsString().equals("set");
            int x = aligned(e.get("beat"), new JsonPrimitive(w.exact), "beat") - w.start;
            int pitch = e.get("pitch").getAsInt();
            if (x < 0 || x >= w.steps || pitch < w.pitch || pitch >= w.pitch + w.pitches)
                throw Errors.invalid("edit onset is outside the explicit window");
            if (!cells.add(x * 128 + pitch))
                throw Errors.invalid("multiple edits address the same channel/step/pitch cell");
            int velocity = set ? e.get("velocity").getAsInt() : 0;
            double duration = set ? e.get("durationBeats").getAsDouble() : 0;
            int durationSteps = set ? aligned(e.get("durationBeats"), new JsonPrimitive(w.exact), "durationBeats") : 0;
            if (set && (durationSteps < 1 || x + durationSteps > w.steps))
                throw Errors.invalid("set duration must fit entirely inside the explicit window");
            result.add(new Edit(x, pitch, velocity, duration, durationSteps, set));
        }
        for (int i = 0; i < result.size(); i++) {
            Edit a = result.get(i);
            if (!a.set) continue;
            for (int j = i + 1; j < result.size(); j++) {
                Edit b = result.get(j);
                if (b.set && a.pitch == b.pitch && a.x < b.x + b.durationSteps && b.x < a.x + a.durationSteps)
                    throw Errors.invalid("same-channel/pitch note durations overlap");
            }
        }
        return result;
    }

    /** Finest power-of-two grid (4..1/128 beat) aligning every edit within 128 steps, so finer existing onsets stay visible. */
    static Window deriveEditWindow(JsonObject p, JsonArray array) {
        List<BigDecimal[]> spans = new ArrayList<>(); // {beat, duration or null}
        int low = 128, high = -1;
        for (JsonElement value : array) {
            JsonObject e = value.getAsJsonObject();
            boolean set = e.get("op").getAsString().equals("set");
            int pitch = e.get("pitch").getAsInt();
            low = Math.min(low, pitch);
            high = Math.max(high, pitch);
            spans.add(new BigDecimal[] {
                e.get("beat").getAsBigDecimal(), set ? e.get("durationBeats").getAsBigDecimal() : null
            });
        }
        BigDecimal grid = null, coarsest = null;
        long first = 0, last = 0;
        for (int i = DERIVED_GRIDS.length - 1; i >= 0 && grid == null; i--) {
            BigDecimal candidate = DERIVED_GRIDS[i];
            boolean aligns = true;
            for (BigDecimal[] span : spans)
                aligns &= span[0].remainder(candidate).signum() == 0
                        && (span[1] == null || span[1].remainder(candidate).signum() == 0);
            if (!aligns) continue;
            long lo = Long.MAX_VALUE, hi = 0;
            for (BigDecimal[] span : spans) {
                long x = span[0].divide(candidate).longValueExact();
                lo = Math.min(lo, x);
                hi = Math.max(
                        hi, x + (span[1] == null ? 1 : span[1].divide(candidate).longValueExact()));
            }
            coarsest = candidate;
            if (hi - lo <= 128) {
                grid = candidate;
                first = lo;
                last = hi;
            }
        }
        if (coarsest == null)
            throw Errors.invalid("beat and durationBeats must be multiples of " + BEAT_GRID_LABEL
                    + " beat to derive a window; no rounding is performed");
        if (grid == null)
            throw Errors.invalid(
                    "edits do not fit 128 steps on any grid that aligns them (coarsest is "
                            + coarsest.stripTrailingZeros().toPlainString()
                            + " beat): split them by time range into several calls, or use beats/durations that fit a coarser grid");
        return new Window(
                p.has("channel") ? p.get("channel").getAsInt() : 0,
                grid,
                (int) first,
                (int) (last - first),
                low,
                high - low + 1,
                true);
    }

    /** The clip's notes as set edits, for the note stage of a clip insert. */
    static JsonArray setEdits(JsonArray notes) {
        JsonArray list = new JsonArray();
        for (JsonElement n : notes) {
            JsonObject e = n.getAsJsonObject().deepCopy();
            e.addProperty("op", "set");
            list.add(e);
        }
        return list;
    }

    /** Whole clip loop over all pitches at the finest of 1/32 and 1/16 note that fits 128 steps; never coarser. */
    static Window loopWindow(Clip clip, int channel) {
        double start = clip.getLoopStart().get(), length = clip.getLoopLength().get();
        double size = length / 0.125 <= 128 + 1e-9 ? 0.125 : 0.25;
        return new Window(
                channel,
                BigDecimal.valueOf(size),
                (int) Math.floor(start / size + 1e-9),
                Math.max(1, Math.min(128, (int) Math.ceil(length / size - 1e-9))),
                0,
                128,
                true);
    }

    // Matched by constant name: the host jar flattens NoteStep.State, so only the name resolves with both jars.
    static boolean hasState(NoteStep note, String name) {
        return note.state().name().equals(name);
    }

    /** MIDI velocity 1..127, the unit notes.edit takes; the host reports 0..1. */
    static int midiVelocity(double normalized) {
        return Math.max(1, Math.min(127, (int) Math.round(normalized * 127)));
    }

    static boolean matches(Edit e, NoteStep note) {
        if (note == null) return false;
        if (!e.set) return hasState(note, "Empty");
        return hasState(note, "NoteOn")
                && midiVelocity(note.velocity()) == e.velocity
                && Math.abs(note.duration() - e.duration) <= 1e-9;
    }

    /** One onset row in the units notes.edit accepts; channel is the window's, step is beat/stepSizeBeats. */
    static JsonObject noteJson(NoteStep note, Window w, int x, int pitch) {
        JsonObject out = new JsonObject();
        out.addProperty("beat", (w.start + x) * w.size);
        out.addProperty("pitch", pitch);
        out.addProperty("velocity", midiVelocity(note.velocity()));
        out.addProperty("durationBeats", note.duration());
        return out;
    }

    /** A cell as read back after an edit: an onset row, or just the cell with its non-onset state. */
    static JsonObject observedJson(NoteStep note, Window w, int x, int pitch) {
        if (hasState(note, "NoteOn")) return noteJson(note, w, x, pitch);
        JsonObject out = new JsonObject();
        out.addProperty("beat", (w.start + x) * w.size);
        out.addProperty("pitch", pitch);
        out.addProperty("state", note.state().name());
        return out;
    }

    static void preflight(List<Edit> changes, Window w, Clip clip) {
        double loopStart = clip.getLoopStart().get();
        double end = loopStart + clip.getLoopLength().get();
        for (Edit e : changes) {
            NoteStep old = clip.getStep(w.channel, e.x, e.pitch);
            if (old == null || old.state() == null)
                throw Errors.notApplied(Reason.NOT_READY, "Note grid is not readable");
            if (hasState(old, "NoteSustain"))
                throw Errors.notApplied(Reason.OVERLAP, "Edit addresses a sustained note, not its onset");
            double beat = (w.start + e.x) * w.size;
            if (e.set && (beat < loopStart || beat + e.duration > end))
                throw Errors.invalid("set note must fit inside the observed clip loop start and end");
            if (!e.set) continue;
            for (int x = e.x + 1; x < e.x + e.durationSteps; x++) {
                NoteStep next = clip.getStep(w.channel, x, e.pitch);
                if (next == null || next.state() == null)
                    throw Errors.notApplied(Reason.NOT_READY, "Note grid is not readable");
                if (hasState(next, "NoteOn"))
                    throw Errors.notApplied(
                            Reason.OVERLAP,
                            "Set duration overlaps an existing unrelated onset; clear it in a separate explicit edit first");
            }
        }
    }
}
