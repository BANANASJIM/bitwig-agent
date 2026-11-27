package dev.bitwigagent.launcher;

import com.bitwig.extension.controller.api.NoteStep;
import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.HostOperation;
import dev.bitwigagent.core.RangeEdit;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.core.Scheduler;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The note methods without the native binding: the launcher cursor clip's public note grid, 128 steps by 128 keys,
 * read and written window by window. The grid shows one onset per cell at the cell's start, so reads are exact only
 * to the cell size; every window binds the clip again (selecting it), and every note written is its own Undo step.
 */
final class PublicNotes {
    static final int STEPS = 128;
    /** Cell sizes of a read: 1/32 note while the region is at most 16 beats long, else 1/16 note. */
    private static final double FINE = 0.125, COARSE = 0.25, FINE_SPAN = 16;
    /** The grid a write reads and writes on: Bitwig's 1/128-beat onset grid, the finest the public grid shows. */
    private static final long WRITE_GRID = Note.ticks(NoteGrid.BEAT_GRID);

    private final LauncherState state;
    private final Scheduler scheduler;

    PublicNotes(LauncherState state) {
        this.state = state;
        this.scheduler = state.scheduler;
    }

    /**
     * Notes seen in the cursor clip; {@code incomplete} says why the read may miss notes (the deadline stopped the
     * walk, or the range reaches past the loop end, which the public grid does not show), null when it does not.
     */
    static final class Read {
        final List<Note> notes;
        final double size;
        final String incomplete;

        Read(List<Note> notes, double size, String incomplete) {
            this.notes = notes;
            this.size = size;
            this.incomplete = incomplete;
        }
    }

    /** Called with each bound window; {@code next} continues the walk. */
    private interface Visit {
        void window(HostOperation op, NoteGrid.Window w, Runnable next);
    }

    /**
     * Reads the notes of {@code range} (the loop from beat 0 for "all" or none), every channel. Binding the clip is a
     * dispatch: a failure after it is unknown.
     */
    void read(HostOperation op, LauncherState.Target target, NoteRange range, Consumer<Read> done) {
        state.bind(op, target, true, null, state.until(op), (o, t, e, w) -> {
            state.cursorGuard(o, t);
            long from = range == null || range.all() ? 0 : range.start, loopEnd = loopEnd();
            // The public grid covers the loop from beat 0: a range past the loop end is read up to it.
            boolean pastLoop = range == null || range.all() || range.end > loopEnd;
            long to = pastLoop ? Math.max(from + 1, loopEnd) : range.end;
            double size = Note.beats(to - from) <= FINE_SPAN ? FINE : COARSE;
            List<Note> notes = new ArrayList<>();
            walk(
                    o,
                    t,
                    windows(from, to, Note.ticks(size)),
                    (op1, window, next) -> {
                        for (Note n : cells(window)) if (range == null || range.test(n)) notes.add(n);
                        next.run();
                    },
                    complete -> {
                        notes.sort(Note.ORDER);
                        String incomplete = !complete
                                ? "Too little time to read every window: read the rest by range."
                                : pastLoop
                                        ? "Without the native note binding the note grid shows the loop only (up to"
                                                + " beat " + NotePlan.plain(loopEnd) + "); notes past it are not read."
                                        : null;
                        done.accept(new Read(notes, size, incomplete));
                    });
        });
    }

    /**
     * Writes a planned note change: reads the region the write touches, plans against it, writes the cells that
     * differ window by window and polls each window until its cells read back. {@code done} gets whether every
     * written cell read back. Onsets must lie on the 1/128-beat grid (refused before anything is selected).
     */
    void write(
            HostOperation op,
            LauncherState.Target target,
            List<NotePlan.Listed> listed,
            NoteRange range,
            NotePlan plan,
            Consumer<Boolean> done) {
        List<String> offGrid = new ArrayList<>();
        for (NotePlan.Listed l : listed)
            if (l.beat % WRITE_GRID != 0) offGrid.add("notes[" + l.index + "] beat " + NotePlan.plain(l.shown));
        if (range != null && !range.all()) {
            if (range.start % WRITE_GRID != 0) offGrid.add("range.start " + NotePlan.plain(range.shownStart));
            if (range.end % WRITE_GRID != 0) offGrid.add("range.end " + NotePlan.plain(range.shownEnd));
        }
        if (!offGrid.isEmpty())
            throw Errors.notApplied(
                    Reason.NATIVE_UNAVAILABLE,
                    "Without the native note binding notes are written on the public " + NoteGrid.BEAT_GRID_LABEL
                            + "-beat grid, and these are not on it: " + String.join("; ", offGrid)
                            + ". Nothing was written.");
        if (range != null && range.all())
            throw Errors.notApplied(
                    Reason.NATIVE_UNAVAILABLE,
                    "Without the native note binding the note grid shows the loop only, so range \"all\" cannot be"
                            + " written: give range {start: 0, end: <loop end>} (clips.get reads the loop). Nothing was"
                            + " written.");
        state.bind(op, target, true, null, state.until(op), (o, t, e, w) -> {
            state.cursorGuard(o, t);
            long loopEnd = loopEnd();
            if (range != null && range.end > loopEnd)
                throw Errors.notApplied(
                        Reason.NATIVE_UNAVAILABLE,
                        "range ends at beat " + NotePlan.plain(range.shownEnd) + ", past the loop end "
                                + NotePlan.plain(loopEnd) + ", and without the native note binding the note grid"
                                + " shows the loop only: end the range at or before beat " + NotePlan.plain(loopEnd)
                                + ", or lengthen the loop (clips.set). Nothing was written.");
            List<long[]> spans = spans(listed, range, loopEnd);
            List<NoteGrid.Window> windows = new ArrayList<>();
            Region seen = new Region();
            for (long[] span : spans) {
                windows.addAll(windows(span[0], span[1], WRITE_GRID));
                seen.starts.add(span[0]);
            }
            walk(
                    o,
                    t,
                    windows,
                    (op1, window, next) -> {
                        seen.read(window);
                        next.run();
                    },
                    complete -> {
                        if (!complete)
                            throw Errors.unknown(
                                    Reason.DEADLINE, "Too little time to read the region before writing it.");
                        RangeEdit.Change<Note> change = plan.plan(seen.notes, loopEnd);
                        seen.requireNoSustainInto(change, listed);
                        Map<Integer, List<Cell>> byWindow = cells(change, windows);
                        List<NoteGrid.Window> touched = new ArrayList<>();
                        for (int i = 0; i < windows.size(); i++)
                            if (byWindow.containsKey(i)) touched.add(windows.get(i));
                        boolean[] verified = {true};
                        walk(
                                o,
                                t,
                                touched,
                                (op2, window, next) ->
                                        writeWindow(op2, t, window, byWindow.get(windows.indexOf(window)), ok -> {
                                            verified[0] &= ok;
                                            if (ok) next.run();
                                            else done.accept(false);
                                        }),
                                finished -> {
                                    if (!finished)
                                        throw Errors.unknown(
                                                Reason.DEADLINE,
                                                "Too little time left to write and read back every window.");
                                    done.accept(verified[0]);
                                });
                    });
        });
    }

    /**
     * The notes of the region a write reads, on the 1/128-beat grid, and the notes that sustain into it from an onset
     * before it: the grid shows those only as sustained cells, so they are tracked by row until their first cell that
     * is not sustained.
     */
    private final class Region {
        /** Where each read span starts (ticks): a sustained cell there belongs to a note from before the span. */
        final java.util.Set<Long> starts = new java.util.HashSet<>();

        final List<Note> notes = new ArrayList<>();
        /** By channel * 128 + pitch: {span start, beat the note from before it sustains to (-1 while open)}. */
        final Map<Integer, long[]> sustained = new HashMap<>();

        void read(NoteGrid.Window w) {
            for (int c = 0; c < 16; c++)
                for (int x = 0; x < w.steps; x++)
                    for (int pitch = 0; pitch < 128; pitch++) {
                        NoteStep step = readable(c, x, pitch);
                        long beat = Note.ticks((w.start + x) * w.size);
                        int row = c * 128 + pitch;
                        boolean sustain = NoteGrid.hasState(step, "NoteSustain");
                        long[] open = sustained.get(row);
                        if (sustain && starts.contains(beat) && (open == null || open[1] != -1))
                            sustained.put(row, new long[] {beat, -1});
                        else if (!sustain && open != null && open[1] == -1) open[1] = beat;
                        if (NoteGrid.hasState(step, "NoteOn"))
                            notes.add(Note.read(c, pitch, Note.beats(beat), step.duration(), step.velocity()));
                    }
        }

        /** Refuses a written note that starts while a note from before the region still sounds on its row. */
        void requireNoSustainInto(RangeEdit.Change<Note> change, List<NotePlan.Listed> listed) {
            List<String> found = new ArrayList<>();
            for (Note n : change.written()) {
                long[] held = sustained.get(n.channel * 128 + n.pitch);
                if (held == null || n.beat < held[0] || held[1] != -1 && n.beat >= held[1]) continue;
                int index = 0;
                for (NotePlan.Listed l : listed) if (l.key().equals(n.key())) index = l.index;
                found.add("notes[" + index + "] starts at beat " + NotePlan.plain(n.beat) + " while a note from before"
                        + " beat " + NotePlan.plain(held[0]) + " on pitch " + n.pitch
                        + (held[1] == -1
                                ? " still sounds: start notes[" + index + "] after that note ends"
                                : " sustains to beat " + NotePlan.plain(held[1]) + ": start notes[" + index
                                        + "] at or after beat " + NotePlan.plain(held[1]))
                        + ", or include that note's onset in range");
            }
            if (!found.isEmpty())
                throw Errors.invalid("notes: " + found.size() + (found.size() == 1 ? " problem" : " problems")
                        + ", nothing was written: " + String.join("; ", found));
        }
    }

    /** One cell a write sets or clears, at its window's step. */
    private static final class Cell {
        final int channel, x, pitch;
        final Note note; // null: clear

        Cell(int channel, int x, int pitch, Note note) {
            this.channel = channel;
            this.x = x;
            this.pitch = pitch;
            this.note = note;
        }
    }

    /** The change's cells by window index: removed notes cleared, written notes set (a replaced note's cell is set). */
    private static Map<Integer, List<Cell>> cells(RangeEdit.Change<Note> change, List<NoteGrid.Window> windows) {
        Map<Integer, List<Cell>> out = new HashMap<>();
        for (Note n : change.remove) place(out, windows, n, null);
        for (Note n : change.written()) place(out, windows, n, n);
        return out;
    }

    /** A planned note at its cell of the read window that holds its onset (every changed note lies in one). */
    private static void place(Map<Integer, List<Cell>> out, List<NoteGrid.Window> windows, Note at, Note note) {
        long step = at.beat / WRITE_GRID;
        int window = 0;
        while (step >= windows.get(window).start + windows.get(window).steps) window++;
        out.computeIfAbsent(window, k -> new ArrayList<>())
                .add(new Cell(at.channel, (int) (step - windows.get(window).start), at.pitch, note));
    }

    private void writeWindow(
            HostOperation op, LauncherState.Target target, NoteGrid.Window w, List<Cell> cells, Consumer<Boolean> ok) {
        op.markDispatched();
        for (Cell c : cells) {
            state.cursorGuard(op, target);
            if (c.note == null) state.clip.clearStep(c.channel, c.x, c.pitch);
            else state.clip.setStep(c.channel, c.x, c.pitch, c.note.velocity, c.note.durationValue());
        }
        scheduler.schedule(
                op,
                () -> scheduler.poll(
                        op,
                        state.until(op),
                        () -> {
                            state.cursorGuard(op, target);
                            for (Cell c : cells) if (!reads(c)) return false;
                            return true;
                        },
                        ok),
                Scheduler.POLL_MS);
    }

    /** Whether the cell reads as written: the note's onset, velocity and stored duration, or empty when cleared. */
    private boolean reads(Cell c) {
        NoteStep step = state.clip.getStep(c.channel, c.x, c.pitch);
        if (step == null || step.state() == null) return false;
        if (c.note == null) return !NoteGrid.hasState(step, "NoteOn");
        return NoteGrid.hasState(step, "NoteOn")
                && Note.midi(step.velocity()) == c.note.velocity
                && Note.ticks(step.duration()) == c.note.duration;
    }

    /** Walks {@code windows} in order, binding each; stops early (false) when too little time is left for the next. */
    private void walk(
            HostOperation op,
            LauncherState.Target target,
            List<NoteGrid.Window> windows,
            Visit visit,
            Consumer<Boolean> finish) {
        step(op, target, windows, 0, 0, visit, finish);
    }

    private void step(
            HostOperation op,
            LauncherState.Target target,
            List<NoteGrid.Window> windows,
            int index,
            long slowest,
            Visit visit,
            Consumer<Boolean> finish) {
        if (index == windows.size()) {
            finish.accept(true);
            return;
        }
        long started = scheduler.now();
        if (index > 0 && op.deadline - started < 2 * slowest) {
            finish.accept(false);
            return;
        }
        state.bind(op, target, false, () -> windows.get(index), state.until(op), (o, t, e, w) -> {
            state.cursorGuard(o, t);
            visit.window(
                    o,
                    w,
                    () -> step(o, t, windows, index + 1, Math.max(slowest, scheduler.now() - started), visit, finish));
        });
    }

    /** Every onset of a bound window, all channels, at its cell's start. */
    private List<Note> cells(NoteGrid.Window w) {
        List<Note> out = new ArrayList<>();
        for (int c = 0; c < 16; c++)
            for (int x = 0; x < w.steps; x++)
                for (int pitch = 0; pitch < 128; pitch++) {
                    NoteStep step = readable(c, x, pitch);
                    if (NoteGrid.hasState(step, "NoteOn"))
                        out.add(Note.read(c, pitch, (w.start + x) * w.size, step.duration(), step.velocity()));
                }
        return out;
    }

    private NoteStep readable(int channel, int x, int pitch) {
        NoteStep step = state.clip.getStep(channel, x, pitch);
        if (step == null || step.state() == null) throw Errors.of(Reason.NOT_READY, "Note grid is not readable", true);
        return step;
    }

    /** Windows of 128 cells of {@code grid} ticks covering [from, to). */
    static List<NoteGrid.Window> windows(long from, long to, long grid) {
        List<NoteGrid.Window> out = new ArrayList<>();
        double size = Note.beats(grid);
        long first = Math.floorDiv(from, grid), last = Math.max(first + 1, -Math.floorDiv(-to, grid));
        for (long s = first; s < last; s += STEPS) {
            if (s > Integer.MAX_VALUE) throw Errors.invalid("beat exceeds the public note grid's range");
            out.add(new NoteGrid.Window(0, size, (int) s, (int) Math.min(STEPS, last - s), 0, 128));
        }
        return out;
    }

    /**
     * The spans a write reads, in ticks, sorted and merged: the range (to the end of the last listed note, if later),
     * or around each listed note from its onset to its end, so every note a written one could run into is seen.
     * Notes sustaining into a span from before it show as sustained cells at its start.
     */
    static List<long[]> spans(List<NotePlan.Listed> listed, NoteRange range, long loopEnd) {
        List<long[]> raw = new ArrayList<>();
        if (range != null) raw.add(new long[] {range.start, range.end});
        for (NotePlan.Listed l : listed) raw.add(new long[] {l.beat, l.end() != null ? l.end() : l.beat + WRITE_GRID});
        raw.sort(java.util.Comparator.comparingLong(span -> span[0]));
        List<long[]> out = new ArrayList<>();
        for (long[] span : raw) {
            long from = span[0] / WRITE_GRID * WRITE_GRID;
            long to = Math.max(from + WRITE_GRID, Math.min(span[1], loopEnd));
            long[] last = out.isEmpty() ? null : out.get(out.size() - 1);
            if (last != null && from <= last[1]) last[1] = Math.max(last[1], to);
            else out.add(new long[] {from, to});
        }
        return out;
    }

    private long loopEnd() {
        return Note.ticks(
                state.clip.getLoopStart().get() + state.clip.getLoopLength().get());
    }
}
