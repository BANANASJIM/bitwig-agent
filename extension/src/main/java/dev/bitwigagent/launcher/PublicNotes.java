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
    /** The finest grid a write uses: Bitwig's 1/128-beat onset grid. */
    private static final long WRITE_GRID = Note.TICKS / 128;

    private final LauncherState state;
    private final Scheduler scheduler;

    PublicNotes(LauncherState state) {
        this.state = state;
        this.scheduler = state.scheduler;
    }

    /** Notes seen in the cursor clip; {@code complete} is false when the deadline stopped the walk early. */
    static final class Read {
        final List<Note> notes;
        final double size;
        final boolean complete;

        Read(List<Note> notes, double size, boolean complete) {
            this.notes = notes;
            this.size = size;
            this.complete = complete;
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
            long from = range == null || range.all() ? 0 : Math.max(0, range.start);
            // The public grid covers the loop from beat 0: a range past the loop end is read up to it.
            long to = range == null || range.all() ? loopEnd() : Math.min(range.end, Math.max(from + 1, loopEnd()));
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
                        done.accept(new Read(notes, size, complete));
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
            if (l.beat % WRITE_GRID != 0)
                offGrid.add("notes[" + l.index + "] beat " + NotePlan.plain(l.shown) + " is not on the "
                        + NoteGrid.BEAT_GRID_LABEL + "-beat grid");
        if (!offGrid.isEmpty())
            throw Errors.notApplied(
                    Reason.NATIVE_UNAVAILABLE,
                    "Without the native note binding notes are written on the public " + NoteGrid.BEAT_GRID_LABEL
                            + "-beat grid: " + String.join("; ", offGrid) + ". Nothing was written.");
        long grid = grid(listed, range);
        state.bind(op, target, true, null, state.until(op), (o, t, e, w) -> {
            state.cursorGuard(o, t);
            long loopEnd = loopEnd();
            long[] region = region(listed, range, loopEnd, grid);
            List<NoteGrid.Window> windows = windows(region[0], region[1], grid);
            List<Note> observed = new ArrayList<>();
            walk(
                    o,
                    t,
                    windows,
                    (op1, window, next) -> {
                        observed.addAll(cells(window));
                        next.run();
                    },
                    complete -> {
                        if (!complete)
                            throw Errors.unknown(
                                    Reason.DEADLINE, "Too little time to read the region before writing it.");
                        RangeEdit.Change<Note> change = plan.plan(observed, loopEnd);
                        Map<Integer, List<Cell>> byWindow = cells(change, windows, grid);
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
    private static Map<Integer, List<Cell>> cells(
            RangeEdit.Change<Note> change, List<NoteGrid.Window> windows, long grid) {
        Map<Integer, List<Cell>> out = new HashMap<>();
        long first = windows.isEmpty() ? 0 : windows.get(0).start;
        for (Note n : change.remove) place(out, first, grid, n, null);
        for (Note n : change.written()) place(out, first, grid, n, n);
        return out;
    }

    private static void place(Map<Integer, List<Cell>> out, long first, long grid, Note at, Note note) {
        long step = Math.floorDiv(at.beat, grid) - first;
        int window = (int) (step / STEPS), x = (int) (step % STEPS);
        out.computeIfAbsent(window, k -> new ArrayList<>()).add(new Cell(at.channel, x, at.pitch, note));
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
                for (int pitch = w.pitch; pitch < w.pitch + w.pitches; pitch++) {
                    NoteStep step = state.clip.getStep(c, x, pitch);
                    if (step == null || step.state() == null)
                        throw Errors.of(Reason.NOT_READY, "Note grid is not readable", true);
                    if (NoteGrid.hasState(step, "NoteOn"))
                        out.add(Note.read(c, pitch, (w.start + x) * w.size, step.duration(), step.velocity()));
                }
        return out;
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

    /** The coarsest grid from 1/4 down to 1/128 beat that holds every listed onset and the range start on a cell. */
    static long grid(List<NotePlan.Listed> listed, NoteRange range) {
        long grid = Note.TICKS / 4;
        while (grid > WRITE_GRID && !aligned(grid, listed, range)) grid /= 2;
        return grid;
    }

    private static boolean aligned(long grid, List<NotePlan.Listed> listed, NoteRange range) {
        if (range != null && !range.all() && range.start % grid != 0) return false;
        for (NotePlan.Listed l : listed) if (l.beat % grid != 0) return false;
        return true;
    }

    /**
     * What a write reads first, in ticks: from beat 0, so notes sustaining into the written span are seen, to the end
     * of the range (at most the loop end, which bounds every written note) or of the last listed note, whichever is
     * later.
     */
    static long[] region(List<NotePlan.Listed> listed, NoteRange range, long loopEnd, long grid) {
        long to = range == null ? 0 : range.all() ? loopEnd : Math.min(range.end, loopEnd);
        for (NotePlan.Listed l : listed) to = Math.max(to, l.end() != null ? l.end() : l.beat + grid);
        return new long[] {0, to};
    }

    private long loopEnd() {
        return Note.ticks(
                state.clip.getLoopStart().get() + state.clip.getLoopLength().get());
    }
}
