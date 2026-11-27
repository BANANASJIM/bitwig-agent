package dev.bitwigagent.launcher;

import com.bitwig.extension.controller.api.NoteStep;
import com.google.gson.JsonObject;
import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.HostOperation;
import dev.bitwigagent.core.RangeEdit;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.core.Scheduler;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * The note methods without the native binding: the launcher cursor clip's public note grid, 128 steps by 128 keys,
 * read and written one beat at a time on Bitwig's 1/128-beat onset grid, the finest it shows. A cell shows one onset,
 * at its start, with that note's duration, so reads and writes are exact to 1/128 beat and a read written back
 * converges. Every window binds the clip again (selecting it), and every note written is its own Undo step (each
 * operator setter one more). Operators are read and written through the note step; expressions are not read (the
 * step's getters do not return what its setters take), so notes.get omits them and notes.set refuses them here.
 */
final class PublicNotes {
    static final int STEPS = 128;
    /** Ticks of one cell of the write grid, and of one beat: window k holds the 128 cells of beat k. */
    private static final long CELL = Note.ticks(NoteGrid.BEAT_GRID), BEAT = Note.TICKS;

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
        final String incomplete;

        Read(List<Note> notes, String incomplete) {
            this.notes = notes;
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
            List<NoteGrid.Window> windows = fine(beats(from, to, new TreeSet<>()));
            List<Note> notes = new ArrayList<>();
            walk(
                    o,
                    t,
                    windows,
                    (op1, window, next) -> {
                        for (Note n : onsets(window)) if (range == null || range.test(n)) notes.add(n);
                        next.run();
                    },
                    read -> {
                        notes.sort(Note.ORDER);
                        String reached =
                                read < windows.size() ? NotePlan.plain(beatOf(windows.get(read)) * BEAT) : null;
                        String incomplete = reached != null
                                ? "Too little time to read every window: read up to beat " + reached
                                        + ": continue with range {start: " + reached + ", end: " + NotePlan.plain(to)
                                        + "}."
                                : pastLoop
                                        ? "Without the native note binding the note grid shows the loop only (up to"
                                                + " beat " + NotePlan.plain(loopEnd) + "); notes past it are not read."
                                        : null;
                        done.accept(new Read(notes, incomplete));
                    });
        });
    }

    /**
     * Writes a planned note change: reads the beats the write touches (and, for a listed note nothing seen shields,
     * every beat from 0 before it), plans against every onset read, writes the cells that differ
     * window by window and polls each window until its whole content reads as planned. {@code done} gets null when
     * every window did, else the window's mismatch. Onsets must lie on the 1/128-beat grid (refused before anything is
     * selected).
     */
    void write(
            HostOperation op,
            LauncherState.Target target,
            List<NotePlan.Listed> listed,
            NoteRange range,
            NotePlan plan,
            Consumer<JsonObject> done) {
        List<String> offGrid = new ArrayList<>();
        for (NotePlan.Listed l : listed)
            if (l.beat % CELL != 0) offGrid.add("notes[" + l.index + "] beat " + NotePlan.plain(l.shown));
        if (range != null && !range.all()) {
            if (range.start % CELL != 0) offGrid.add("range.start " + NotePlan.plain(range.shownStart));
            if (range.end % CELL != 0) offGrid.add("range.end " + NotePlan.plain(range.shownEnd));
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
            TreeSet<Long> beats = touched(listed, range, loopEnd);
            List<Note> seen = new ArrayList<>();
            readAll(
                    o,
                    t,
                    fine(beats),
                    seen,
                    () -> lookback(o, t, listed, beats, seen, () -> {
                        RangeEdit.Change<Note> change = plan.plan(seen, loopEnd);
                        writeChange(o, t, change, RangeEdit.apply(seen, change), done);
                    }));
        });
    }

    /** Reads every onset of {@code windows} into {@code seen}, then runs {@code then}; unknown if time runs out. */
    private void readAll(
            HostOperation op,
            LauncherState.Target target,
            List<NoteGrid.Window> windows,
            List<Note> seen,
            Runnable then) {
        walk(
                op,
                target,
                windows,
                (op1, window, next) -> {
                    seen.addAll(onsets(window));
                    next.run();
                },
                read -> {
                    requireRead(read, windows);
                    then.run();
                });
    }

    /**
     * A write reads everything it plans against before writing anything; when the deadline cannot cover that read it
     * writes nothing.
     */
    private static void requireRead(int visited, List<NoteGrid.Window> windows) {
        if (visited < windows.size())
            throw Errors.unknown(
                    Reason.DEADLINE,
                    "Too little time to read the " + windows.size() + " beat windows this write reads first (up to"
                            + " beat " + (beatOf(windows.get(windows.size() - 1)) + 1) + ", about 0.25 s each)."
                            + " Nothing was written: raise timeoutMs (at most 60000).");
    }

    /**
     * A note sustaining into a listed note from before the beats read shows only at its onset (a coarser cell under an
     * earlier note's sustain hides it). For every listed note with no onset on its row between it and the start of
     * the run of read beats it lies in (a run that starts after beat 0), every beat from 0 up to that run is read on
     * the write grid too, so the planner sees the note with its duration.
     */
    private void lookback(
            HostOperation op,
            LauncherState.Target target,
            List<NotePlan.Listed> listed,
            TreeSet<Long> read,
            List<Note> seen,
            Runnable then) {
        long reach = 0;
        for (NotePlan.Listed l : listed) {
            long run = l.beat / BEAT;
            while (run > 0 && read.contains(run - 1)) run--;
            if (run > 0 && !shielded(seen, l, run * BEAT)) reach = Math.max(reach, run);
        }
        TreeSet<Long> earlier = beats(0, reach * BEAT, new TreeSet<>());
        earlier.removeAll(read);
        readAll(op, target, fine(earlier), seen, then);
    }

    /** Whether an onset on the listed note's row lies in [from, its beat): a note before it ends there or earlier. */
    private static boolean shielded(List<Note> seen, NotePlan.Listed l, long from) {
        for (Note n : seen)
            if (n.channel == l.channel && n.pitch == l.pitch && n.beat >= from && n.beat < l.beat) return true;
        return false;
    }

    /** One cell a write sets or clears, at its window's step. */
    private static final class Cell {
        final int channel, x, pitch;
        final Note note; // null: clear
        /** Whether the cell is set with setStep: a new note, or one whose velocity or duration changes. */
        final boolean set;
        /** Whether its operators are set through the note step: a plain note that was plain needs none. */
        final boolean operators;

        Cell(Note at, Note note, boolean set, boolean operators) {
            this.channel = at.channel;
            this.x = (int) (at.beat % BEAT / CELL);
            this.pitch = at.pitch;
            this.note = note;
            this.set = set;
            this.operators = operators;
        }
    }

    /**
     * Writes the change's cells beat by beat: removed notes cleared, written notes set (a replaced note's cell is
     * set). Every changed note lies in a beat that was read.
     */
    private void writeChange(
            HostOperation op,
            LauncherState.Target target,
            RangeEdit.Change<Note> change,
            List<Note> after,
            Consumer<JsonObject> done) {
        TreeMap<Long, List<Cell>> byBeat = new TreeMap<>();
        for (Note n : change.remove)
            byBeat.computeIfAbsent(n.beat / BEAT, k -> new ArrayList<>()).add(new Cell(n, null, false, false));
        for (Note n : change.insert)
            byBeat.computeIfAbsent(n.beat / BEAT, k -> new ArrayList<>())
                    .add(new Cell(n, n, true, !n.features.equals(NoteFeatures.NONE)));
        for (RangeEdit.Update<Note> u : change.update) {
            boolean set = u.before.velocity != u.after.velocity || u.before.duration != u.after.duration;
            boolean operators =
                    !u.before.features.equals(NoteFeatures.NONE) || !u.after.features.equals(NoteFeatures.NONE);
            byBeat.computeIfAbsent(u.after.beat / BEAT, k -> new ArrayList<>())
                    .add(new Cell(u.after, u.after, set, operators));
        }
        List<NoteGrid.Window> touched = fine(byBeat.navigableKeySet());
        walk(
                op,
                target,
                touched,
                (op1, window, next) -> {
                    long beat = beatOf(window);
                    List<Note> expected = new ArrayList<>();
                    for (Note n : after) if (n.beat / BEAT == beat) expected.add(n);
                    writeWindow(op1, target, window, byBeat.get(beat), expected, mismatch -> {
                        if (mismatch == null) next.run();
                        else done.accept(mismatch);
                    });
                },
                written -> {
                    if (written < touched.size())
                        throw Errors.unknown(
                                Reason.DEADLINE, "Too little time left to write and read back every window.");
                    done.accept(null);
                });
    }

    /**
     * Writes one bound window's cells and polls until the window's whole content (every onset with its velocity,
     * duration and operators, every channel) equals {@code expected}; {@code finish} gets null then, else the mismatch
     * at the deadline. A written note's operators are set through its step once the step shows the note's velocity and
     * duration (a cell set with setStep shows the new note only after the host answers).
     */
    private void writeWindow(
            HostOperation op,
            LauncherState.Target target,
            NoteGrid.Window w,
            List<Cell> cells,
            List<Note> expected,
            Consumer<JsonObject> finish) {
        op.markDispatched();
        List<Cell> pending = new ArrayList<>();
        for (Cell c : cells) {
            state.cursorGuard(op, target);
            if (c.note == null) state.clip.clearStep(c.channel, c.x, c.pitch);
            else {
                if (c.set) state.clip.setStep(c.channel, c.x, c.pitch, c.note.velocity, c.note.durationValue());
                if (c.operators) pending.add(c);
            }
        }
        HashSet<Note> wanted = new HashSet<>(expected);
        scheduler.schedule(
                op,
                () -> scheduler.poll(
                        op,
                        state.until(op),
                        () -> {
                            state.cursorGuard(op, target);
                            setOperators(pending, w);
                            List<Note> actual = onsets(w);
                            return actual.size() == wanted.size() && wanted.containsAll(actual);
                        },
                        ok -> finish.accept(ok ? null : NoteModule.mismatch(expected, onsets(w)))),
                Scheduler.POLL_MS);
    }

    /** Sets the operators of each pending cell whose step shows its note's velocity and duration. */
    private void setOperators(List<Cell> pending, NoteGrid.Window w) {
        for (Iterator<Cell> it = pending.iterator(); it.hasNext(); ) {
            Cell c = it.next();
            NoteStep step = readable(c.channel, c.x, c.pitch);
            if (!NoteGrid.hasState(step, "NoteOn")) continue;
            Note shown = Note.read(c.channel, c.pitch, (w.start + c.x) * w.size, step.duration(), step.velocity());
            if (shown.velocity != c.note.velocity || shown.duration != c.note.duration) continue;
            write(step, c.note.features);
            it.remove();
        }
    }

    /** The operators a note step shows. */
    static NoteFeatures features(NoteStep s) {
        NoteFeatures.Builder b = new NoteFeatures.Builder();
        if (s.isChanceEnabled()) b.chance = s.chance();
        b.velocitySpread = s.velocitySpread();
        b.releaseVelocity = Note.releaseMidi(s.releaseVelocity());
        if (s.isRepeatEnabled())
            b.repeat = new NoteFeatures.Repeat(
                    s.repeatCount(), s.repeatCurve(), s.repeatVelocityEnd(), s.repeatVelocityCurve());
        if (s.isRecurrenceEnabled())
            b.recurrence = new NoteFeatures.Recurrence(s.recurrenceLength(), s.recurrenceMask());
        if (s.isOccurrenceEnabled()) b.occurrence = s.occurrence();
        b.muted = s.isMuted();
        return b.build();
    }

    /**
     * Sets a note step's operators to {@code f}; the step's setters skip a value it already shows. An operator that is
     * off gets its neutral value when its switch is on (Bitwig's new-note state), and is left alone when it is off.
     */
    static void write(NoteStep s, NoteFeatures f) {
        if (f.chance != null) {
            s.setIsChanceEnabled(true);
            s.setChance(f.chance);
        } else if (s.isChanceEnabled()) s.setChance(1);
        s.setVelocitySpread(f.velocitySpread);
        s.setReleaseVelocity(f.releaseVelocity / 127.0);
        if (f.repeat != null) {
            s.setIsRepeatEnabled(true);
            s.setRepeatCount(f.repeat.count);
            s.setRepeatCurve(f.repeat.curve);
            s.setRepeatVelocityEnd(f.repeat.velocityEnd);
            s.setRepeatVelocityCurve(f.repeat.velocityCurve);
        } else if (s.isRepeatEnabled()) s.setRepeatCount(0);
        if (f.recurrence != null) {
            s.setIsRecurrenceEnabled(true);
            s.setRecurrence(f.recurrence.length, f.recurrence.mask);
        } else if (s.isRecurrenceEnabled())
            s.setRecurrence(NoteFeatures.Recurrence.ALWAYS.length, NoteFeatures.Recurrence.ALWAYS.mask);
        if (f.occurrence != null) {
            s.setIsOccurrenceEnabled(true);
            s.setOccurrence(f.occurrence);
        } else if (s.isOccurrenceEnabled()) s.setOccurrence(com.bitwig.extension.controller.api.NoteOccurrence.ALWAYS);
        s.setIsMuted(f.muted);
    }

    /** Walks {@code windows} in order, binding each; stops early when too little time is left for the next. */
    private void walk(
            HostOperation op,
            LauncherState.Target target,
            List<NoteGrid.Window> windows,
            Visit visit,
            IntConsumer finish) {
        step(op, target, windows, 0, 0, visit, finish);
    }

    /** {@code finish} gets how many windows were visited. */
    private void step(
            HostOperation op,
            LauncherState.Target target,
            List<NoteGrid.Window> windows,
            int index,
            long slowest,
            Visit visit,
            IntConsumer finish) {
        if (index == windows.size()) {
            finish.accept(index);
            return;
        }
        long started = scheduler.now();
        if (index > 0 && op.deadline - started < 2 * slowest) {
            finish.accept(index);
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
    private List<Note> onsets(NoteGrid.Window w) {
        List<Note> out = new ArrayList<>();
        for (int c = 0; c < 16; c++)
            for (int x = 0; x < w.steps; x++)
                for (int pitch = 0; pitch < 128; pitch++) {
                    NoteStep step = readable(c, x, pitch);
                    if (NoteGrid.hasState(step, "NoteOn"))
                        out.add(Note.read(
                                c, pitch, (w.start + x) * w.size, step.duration(), step.velocity(), features(step)));
                }
        return out;
    }

    private NoteStep readable(int channel, int x, int pitch) {
        NoteStep step = state.clip.getStep(channel, x, pitch);
        if (step == null || step.state() == null) throw Errors.of(Reason.NOT_READY, "Note grid is not readable", true);
        return step;
    }

    /** The write-grid window of each beat, in order: window k is the 128 cells of beat k. */
    private static List<NoteGrid.Window> fine(java.util.SortedSet<Long> beats) {
        List<NoteGrid.Window> out = new ArrayList<>();
        for (long beat : beats)
            out.add(new NoteGrid.Window(0, NoteGrid.BEAT_GRID, (int) (beat * STEPS), STEPS, 0, 128));
        return out;
    }

    private static long beatOf(NoteGrid.Window w) {
        return w.start / STEPS;
    }

    /** Adds the whole beats that cover [from, to) (ticks) to {@code beats}. */
    private static TreeSet<Long> beats(long from, long to, TreeSet<Long> beats) {
        for (long b = Math.floorDiv(from, BEAT); b < -Math.floorDiv(-to, BEAT); b++) beats.add(b);
        return beats;
    }

    /**
     * The beats a write reads: the range, and each listed note from its onset to its end (its onset cell without a
     * duration), up to the loop end, so every note a written one could run into from its onset on is seen.
     */
    static TreeSet<Long> touched(List<NotePlan.Listed> listed, NoteRange range, long loopEnd) {
        TreeSet<Long> out = new TreeSet<>();
        if (range != null) beats(range.start, range.end, out);
        for (NotePlan.Listed l : listed)
            beats(l.beat, Math.max(l.beat + CELL, Math.min(l.end() != null ? l.end() : 0, loopEnd)), out);
        return out;
    }

    private long loopEnd() {
        return Note.ticks(
                state.clip.getLoopStart().get() + state.clip.getLoopLength().get());
    }
}
