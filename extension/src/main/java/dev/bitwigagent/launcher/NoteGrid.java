package dev.bitwigagent.launcher;

import com.bitwig.extension.controller.api.Clip;
import com.bitwig.extension.controller.api.NoteStep;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.HostLimits;
import dev.bitwigagent.core.Reason;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The note grid of a cursor clip: window, edits and the units the note methods share. Host-free except note reads. */
public final class NoteGrid {
    private NoteGrid() {}

    public static final double BEAT_GRID = HostLimits.LIMITS.beatGrid;
    static final int MAX_CLIP_BEATS = HostLimits.LIMITS.maxClipBeats;
    public static final String BEAT_GRID_LABEL = "1/" + Math.round(1 / BEAT_GRID);
    private static final int MAX_STEPS = 128;
    private static final BigDecimal ONSET_GRID = BigDecimal.valueOf(BEAT_GRID);
    /** Bitwig 6.1.4 stores a note duration to 1/1048576 beat (native readback), so readback is compared at that step. */
    static final double DURATION_RESOLUTION = HostLimits.LIMITS.durationResolution;

    private static final BigDecimal DURATION_STEP = BigDecimal.valueOf(DURATION_RESOLUTION);
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
        /** Position in the request's notes/edits array. */
        final int index;
        /** {@code cells}: grid cells the note touches from its onset cell, so the sustain cells preflight checks. */
        final int x, pitch, velocity, cells;

        final double duration;
        final boolean set;
        long before, generation;
        boolean alreadyMatched, verified;

        Edit(int index, int x, int pitch, int velocity, double duration, int cells, boolean set) {
            this.index = index;
            this.x = x;
            this.pitch = pitch;
            this.velocity = velocity;
            this.duration = duration;
            this.cells = cells;
            this.set = set;
        }
    }

    /** One window of a write and the request entries whose onsets it holds, in request order. */
    static final class Part {
        final Window window;
        final List<Integer> indices;
        final JsonArray edits;

        Part(Window window, List<Integer> indices, JsonArray all) {
            this.window = window;
            this.indices = indices;
            this.edits = new JsonArray();
            for (int i : indices) edits.add(all.get(i));
        }
    }

    /**
     * A request entry in exact beats: a set spans beat..end, a clear marks one cell. {@code end} is where the note ends
     * as Bitwig stores it, which every rule judges; {@code requestedEnd} is what messages show.
     */
    private static final class Span {
        final int index, pitch;
        final boolean set;
        final BigDecimal beat, end, requestedEnd;

        Span(int index, JsonObject e) {
            this.index = index;
            this.pitch = e.get("pitch").getAsInt();
            this.set = e.get("op").getAsString().equals("set");
            this.beat = e.get("beat").getAsBigDecimal();
            BigDecimal duration = set ? e.get("durationBeats").getAsBigDecimal() : BigDecimal.ZERO;
            this.requestedEnd = beat.add(duration);
            this.end = beat.add(stored(duration));
        }

        long cell(BigDecimal grid) {
            return beat.divide(grid).longValueExact();
        }

        /** One past the last cell the entry touches. */
        long cellEnd(BigDecimal grid) {
            long first = cell(grid);
            if (!set) return first + 1;
            return Math.max(first + 1, end.divide(grid, 0, RoundingMode.CEILING).longValueExact());
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

    /** The explicit window of a read, or null for one sized from the clip loop. */
    static Window readWindow(JsonObject p) {
        return hasGrid(p) ? Window.of(p, false) : null;
    }

    /** The duration Bitwig stores for a requested one: the nearest multiple of its duration resolution. */
    static BigDecimal stored(BigDecimal duration) {
        return duration.divide(DURATION_STEP).setScale(0, RoundingMode.HALF_UP).multiply(DURATION_STEP);
    }

    /**
     * The windows a write runs in: the explicit one, or derived from the onsets alone (durations need not align).
     * Every rule the entries break is refused together, each with its index and a fix; {@code length} bounds the
     * entries when known.
     */
    static List<Part> plan(JsonObject p, JsonArray array, String field, BigDecimal length) {
        checkShape(array);
        Window explicit = hasGrid(p) ? Window.of(p, false) : null;
        List<Span> spans = new ArrayList<>(array.size());
        for (int i = 0; i < array.size(); i++)
            spans.add(new Span(i, array.get(i).getAsJsonObject()));
        refuse(field, violations(spans, field, length, explicit));
        List<Integer> all = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) all.add(i);
        return explicit != null ? List.of(new Part(explicit, all, array)) : derive(p, array, spans, field);
    }

    /** The rules of clip notes {beat, pitch, velocity, durationBeats} within {@code length}, without a window. */
    public static void requireNoteRules(JsonArray notes, BigDecimal length) {
        JsonArray sets = setEdits(notes);
        List<Span> spans = new ArrayList<>(sets.size());
        for (int i = 0; i < sets.size(); i++) spans.add(new Span(i, sets.get(i).getAsJsonObject()));
        refuse("notes", violations(spans, "notes", length, null));
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

    static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static void add(List<Object[]> out, int index, String message) {
        out.add(new Object[] {index, message});
    }

    /** The rules of the grid and the clip, in request order; the same texts as the Python validator. */
    private static List<Object[]> violations(List<Span> spans, String field, BigDecimal length, Window explicit) {
        List<Object[]> out = new ArrayList<>();
        for (Span s : spans) {
            String at = field + "[" + s.index + "]";
            if (explicit != null) {
                BigDecimal[] step = s.beat.divideAndRemainder(explicit.exact);
                BigDecimal first = BigDecimal.valueOf(explicit.start),
                        limit = first.add(BigDecimal.valueOf(explicit.steps));
                BigDecimal windowEnd = limit.multiply(explicit.exact);
                if (step[1].signum() != 0)
                    add(
                            out,
                            s.index,
                            at + " beat " + plain(s.beat) + " does not align with stepSizeBeats "
                                    + plain(explicit.exact) + ": use a multiple of it");
                else if (step[0].compareTo(first) < 0
                        || step[0].compareTo(limit) >= 0
                        || s.pitch < explicit.pitch
                        || s.pitch >= explicit.pitch + explicit.pitches)
                    add(
                            out,
                            s.index,
                            at + " (beat " + plain(s.beat) + ", pitch " + s.pitch
                                    + ") is outside the window: beats " + plain(first.multiply(explicit.exact)) + " to "
                                    + plain(windowEnd) + ", pitches " + explicit.pitch + " to "
                                    + (explicit.pitch + explicit.pitches - 1));
                else if (s.end.compareTo(windowEnd) > 0)
                    add(
                            out,
                            s.index,
                            at + " ends at beat " + plain(s.requestedEnd) + ", past the window end " + plain(windowEnd)
                                    + ": shorten it to durationBeats <= " + plain(windowEnd.subtract(s.beat)));
            } else if (s.beat.remainder(ONSET_GRID).signum() != 0) {
                BigDecimal low =
                        s.beat.divide(ONSET_GRID, 0, RoundingMode.FLOOR).multiply(ONSET_GRID);
                add(
                        out,
                        s.index,
                        at + " beat " + plain(s.beat) + " is not on the " + BEAT_GRID_LABEL + "-beat grid: use "
                                + plain(low) + " or " + plain(low.add(ONSET_GRID)));
            }
            if (length != null && s.set && s.end.compareTo(length) > 0)
                add(
                        out,
                        s.index,
                        s.beat.compareTo(length) >= 0
                                ? at + " starts at beat " + plain(s.beat) + ", at or past the clip length "
                                        + plain(length)
                                : at + " ends at beat " + plain(s.requestedEnd) + ", past the clip length "
                                        + plain(length) + ": shorten it to durationBeats <= "
                                        + plain(length.subtract(s.beat)));
        }
        Map<String, Span> cells = new HashMap<>();
        for (Span s : spans) {
            Span first = cells.putIfAbsent(plain(s.beat) + "/" + s.pitch, s);
            if (first != null)
                add(
                        out,
                        s.index,
                        field + "[" + s.index + "] repeats the beat and pitch of " + field + "[" + first.index
                                + "]: remove one");
        }
        Map<Integer, Span> holders = new HashMap<>(); // per pitch, the set seen so far that ends last
        List<Span> byOnset = new ArrayList<>(spans);
        byOnset.sort(Comparator.comparing((Span s) -> s.beat).thenComparingInt(s -> s.index));
        for (Span s : byOnset) {
            if (!s.set) continue;
            Span holder = holders.get(s.pitch);
            if (holder != null && holder.beat.compareTo(s.beat) != 0 && holder.end.compareTo(s.beat) > 0)
                add(
                        out,
                        s.index,
                        field + "[" + s.index + "] starts at beat " + plain(s.beat) + " before " + field
                                + "[" + holder.index + "] on pitch " + s.pitch + " ends at beat "
                                + plain(holder.requestedEnd)
                                + ": shorten " + field + "[" + holder.index + "] to durationBeats <= "
                                + plain(s.beat.subtract(holder.beat)));
            if (holder == null || s.end.compareTo(holder.end) > 0) holders.put(s.pitch, s);
        }
        out.sort(Comparator.comparingInt(v -> (Integer) v[0]));
        return out;
    }

    private static void refuse(String field, List<Object[]> violations) {
        if (violations.isEmpty()) return;
        StringBuilder message = new StringBuilder(field + ": " + violations.size()
                + (violations.size() == 1 ? " problem" : " problems") + ", nothing was written: ");
        for (int i = 0; i < violations.size(); i++)
            message.append(i == 0 ? "" : "; ").append(violations.get(i)[1]);
        throw Errors.invalid(message.toString());
    }

    /**
     * The finest power-of-two grid (4..1/128 beat) aligning every onset whose cells fit one 128-step window, so finer
     * existing onsets stay visible; else the coarsest aligning grid, cut into 128-step windows holding whole notes.
     */
    private static List<Part> derive(JsonObject p, JsonArray array, List<Span> spans, String field) {
        int channel = p.has("channel") ? p.get("channel").getAsInt() : 0;
        BigDecimal coarsest = null;
        for (int i = DERIVED_GRIDS.length - 1; i >= 0; i--) {
            BigDecimal grid = DERIVED_GRIDS[i];
            if (!aligns(spans, grid)) continue;
            coarsest = grid;
            long first = Long.MAX_VALUE, last = 0;
            for (Span s : spans) {
                first = Math.min(first, s.cell(grid));
                last = Math.max(last, s.cellEnd(grid));
            }
            if (last - first <= MAX_STEPS) {
                List<Integer> all = new ArrayList<>();
                for (Span s : spans) all.add(s.index);
                return List.of(new Part(window(channel, grid, spans, first, last), all, array));
            }
        }
        BigDecimal grid = coarsest;
        List<Object[]> tooLong = new ArrayList<>();
        for (Span s : spans)
            if (s.cellEnd(grid) - s.cell(grid) > MAX_STEPS)
                add(
                        tooLong,
                        s.index,
                        field + "[" + s.index + "] durationBeats " + plain(s.requestedEnd.subtract(s.beat))
                                + " spans more than " + MAX_STEPS + " steps of the " + plain(grid)
                                + "-beat grid the onsets need: shorten it to durationBeats <= "
                                + plain(grid.multiply(BigDecimal.valueOf(MAX_STEPS)))
                                + (spans.size() > 1 ? " or write it in a call of its own" : ""));
        refuse(field, tooLong);
        List<Span> byOnset = new ArrayList<>(spans);
        byOnset.sort(Comparator.comparing((Span s) -> s.beat).thenComparingInt(s -> s.index));
        List<Part> parts = new ArrayList<>();
        List<Span> current = new ArrayList<>();
        long first = 0, last = 0;
        for (Span s : byOnset) {
            if (!current.isEmpty() && s.cellEnd(grid) - first > MAX_STEPS) {
                parts.add(part(channel, grid, current, first, last, array));
                current = new ArrayList<>();
            }
            if (current.isEmpty()) first = s.cell(grid);
            current.add(s);
            last = Math.max(last, s.cellEnd(grid));
        }
        parts.add(part(channel, grid, current, first, last, array));
        return parts;
    }

    private static boolean aligns(List<Span> spans, BigDecimal grid) {
        for (Span s : spans) if (s.beat.remainder(grid).signum() != 0) return false;
        return true;
    }

    private static Part part(int channel, BigDecimal grid, List<Span> spans, long first, long last, JsonArray all) {
        List<Integer> indices = new ArrayList<>();
        for (Span s : spans) indices.add(s.index);
        indices.sort(null);
        return new Part(window(channel, grid, spans, first, last), indices, all);
    }

    private static Window window(int channel, BigDecimal grid, List<Span> spans, long first, long last) {
        int low = 127, high = 0;
        for (Span s : spans) {
            low = Math.min(low, s.pitch);
            high = Math.max(high, s.pitch);
        }
        if (first > Integer.MAX_VALUE) throw Errors.invalid("beat exceeds supported grid range");
        return new Window(channel, grid, (int) first, (int) (last - first), low, high - low + 1, true);
    }

    /** The part's entries as cells of its window; plan() already checked every rule. */
    static List<Edit> edits(Part part) {
        Window w = part.window;
        List<Edit> result = new ArrayList<>(part.indices.size());
        for (int i = 0; i < part.indices.size(); i++) {
            JsonObject e = part.edits.get(i).getAsJsonObject();
            Span s = new Span(part.indices.get(i), e);
            int x = (int) (s.cell(w.exact) - w.start), cells = (int) (s.cellEnd(w.exact) - s.cell(w.exact));
            if (x < 0 || x + cells > w.steps || s.pitch < w.pitch || s.pitch >= w.pitch + w.pitches)
                throw Errors.invalid("edit does not fit its window");
            result.add(new Edit(
                    s.index,
                    x,
                    s.pitch,
                    s.set ? e.get("velocity").getAsInt() : 0,
                    s.set ? e.get("durationBeats").getAsDouble() : 0,
                    cells,
                    s.set));
        }
        return result;
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
                && Math.abs(note.duration() - e.duration) <= DURATION_RESOLUTION / 2;
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
            for (int x = e.x + 1; x < e.x + e.cells; x++) {
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
