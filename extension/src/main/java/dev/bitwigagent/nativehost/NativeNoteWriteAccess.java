package dev.bitwigagent.nativehost;

import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.launcher.NativeNotes;
import java.lang.reflect.Method;
import java.util.*;

/**
 * Exact bindings that write a launcher clip's notes as one Undo step. The public {@code Clip.setStep} and
 * {@code clearStep} each post their own document event, and each event opens its own outermost transaction ("Insert
 * Note", "Delete Events"), so every note is an Undo step. Here one document task opens {@code project.begin(label,
 * false)}, makes the same model calls those events end in, and closes it with {@code project.end(false)}: the
 * transaction manager reports only the outermost begin and end to the undo recorder (depth counter), so the inner
 * transactions merge.
 *
 * <p>Set: {@code note.clip.add(note.create(time, duration, velocity * 0.007874016f), channel, key, null)}, which
 * clears the overlap and adds the note like the public setter but leaves the editor selection alone. Clear: the row's
 * notes that start inside the cell, deleted with {@code note.rowEvents.delete(Collection)}. Times use the public
 * setter's rounding ({@code time.grid.round}, 2^-20 beat); durations too, the resolution Bitwig keeps them at after
 * Undo/Redo. Clip creation is {@code launcher.emptySlot.createClip()} plus the length the public
 * {@code createEmptyClip} sets, without its slot selection.
 */
public final class NativeNoteWriteAccess implements NativeNotes.Access {
    static final String EDIT_LABEL = "Edit Notes", CREATE_LABEL = "Insert Clip";
    /** The public setter's MIDI-to-model velocity factor (1/127 as a float). */
    private static final float VELOCITY = 0.007874016f;
    /** The public clearStep's cell end: a note starting before {@code cell + step - CELL_END} is in the cell. */
    private static final double CELL_END = 1e-4, EPS = 1e-9;

    private final NativeHost.Access a;
    private final NativeLauncherClip launcher;
    private final NativeClipTiming timing;
    private final Class<?> noteClip, rowOwner;
    private final Method round, newNote, addNote, rows, row, events, start, length, velocity, delete, begin, end;
    private final Method createClip, clipLength, setLength, rename, setNumber;

    public NativeNoteWriteAccess(NativeHost.Access a) throws ReflectiveOperationException {
        this.a = a;
        launcher = new NativeLauncherClip(a);
        timing = new NativeClipTiming(a);
        noteClip = a.type("note.clip");
        rowOwner = a.type("note.rowOwner");
        Class<?> note = a.type("note");
        round = a.exactMethod("time.grid.round", double.class, true, double.class);
        newNote = a.exactMethod("note.create", note, true, double.class, double.class, double.class);
        addNote =
                a.exactMethod("note.clip.add", void.class, false, note, int.class, int.class, a.type("note.selection"));
        rows = a.exactMethod("note.rowOwner.rows", a.type("note.rows"), false);
        row = a.exactMethod("note.rowTable.row", a.type("note.row"), false, int.class, int.class, boolean.class);
        events = a.exactMethod("event.list.events", List.class, false);
        start = a.exactMethod("event.timed.start", double.class, false);
        length = a.exactMethod("event.enduring.length", double.class, false);
        velocity = a.exactMethod("note.event.velocity", double.class, false);
        delete = a.exactMethod("note.rowEvents.delete", void.class, false, Collection.class);
        begin = a.exactMethod("project.begin", void.class, false, String.class, boolean.class);
        end = a.exactMethod("project.end", void.class, false, boolean.class);
        createClip = a.exactMethod("launcher.emptySlot.createClip", void.class, false);
        clipLength = a.exactMethod("clip.lengthRange", a.type("time.range"), false);
        setLength = a.exactMethod("time.range.setLength", void.class, false, double.class);
        rename = a.exactMethod("clip.rename", void.class, false, String.class);
        setNumber = a.exactMethod("model.floatProperty.set", void.class, false, double.class);
    }

    @Override
    public void execute(Runnable work) {
        a.execute(work);
    }

    @Override
    public boolean[] edit(
            Object slot,
            String trackId,
            String expectedName,
            List<NativeNotes.Cell> cells,
            Runnable check,
            NativeNotes.Plan plan,
            Runnable beforeWrite) {
        NativeLauncherClip.Bound bound = launcher.clip(slot, trackId, expectedName, check);
        Object clip = bound.clip;
        if (!noteClip.isInstance(clip) || !rowOwner.isInstance(clip))
            throw Errors.notApplied(Reason.UNSUPPORTED_SCOPE, "The launcher clip is not a note clip.");
        Object loop = a.invoke(timing.loop, a.invoke(timing.timing, clip));
        double loopStart = number(a.invoke(timing.position, loop));
        double loopEnd = loopStart + number(a.invoke(timing.duration, loop));
        List<NativeNotes.Observed> observed = new ArrayList<>(cells.size());
        List<List<Object>> onsets = new ArrayList<>(cells.size());
        for (NativeNotes.Cell cell : cells) {
            check.run();
            double t = time(cell), cellEnd = t + cell.stepBeats - CELL_END;
            double spanEnd = t + cell.cells * cell.stepBeats - CELL_END;
            List<Object> inCell = new ArrayList<>();
            boolean sustained = false, collides = false;
            for (Object n : notes(clip, cell)) {
                double s = (Double) a.invoke(start, n);
                if (s >= t - EPS && s <= cellEnd) inCell.add(n);
                else if (s < t - EPS && s + (Double) a.invoke(length, n) > t + EPS) sustained = true;
                else if (s > cellEnd && s <= spanEnd) collides = true;
            }
            onsets.add(inCell);
            Object first = inCell.isEmpty() ? null : inCell.get(0);
            observed.add(new NativeNotes.Observed(
                    inCell.size(),
                    first == null ? 0 : (Double) a.invoke(velocity, first),
                    first == null ? 0 : (Double) a.invoke(length, first),
                    sustained,
                    collides));
        }
        boolean[] write = plan.write(loopStart, loopEnd, observed);
        boolean any = false;
        for (boolean w : write) any |= w;
        if (!any) return write;
        bound.guard();
        beforeWrite.run();
        transaction(bound.project, EDIT_LABEL, () -> {
            for (int i = 0; i < cells.size(); i++) {
                if (!write[i]) continue;
                NativeNotes.Cell cell = cells.get(i);
                if (cell.set) add(clip, cell);
                else if (!onsets.get(i).isEmpty()) a.invoke(delete, rowOf(clip, cell), new ArrayList<>(onsets.get(i)));
            }
        });
        return write;
    }

    @Override
    public void create(
            Object slot,
            String trackId,
            String name,
            int lengthBeats,
            List<NativeNotes.Cell> cells,
            Runnable check,
            Runnable beforeWrite) {
        NativeLauncherClip.Bound bound = launcher.empty(slot, trackId, check);
        beforeWrite.run();
        transaction(bound.project, CREATE_LABEL, () -> {
            a.invoke(createClip, bound.slot);
            Object clip = a.invoke(launcher.clip, slot);
            if (!noteClip.isInstance(clip) || a.invoke(launcher.slot, clip) != bound.slot)
                throw Errors.unknown(Reason.READBACK_MISMATCH, "No native note clip appeared in the slot.");
            a.invoke(setLength, a.invoke(clipLength, clip), (double) lengthBeats);
            a.invoke(rename, clip, name);
            a.invoke(
                    setNumber,
                    a.invoke(timing.position, a.invoke(timing.playStop, a.invoke(timing.timing, clip))),
                    (double) lengthBeats);
            for (NativeNotes.Cell cell : cells) add(clip, cell);
        });
    }

    private void transaction(Object project, String label, Runnable work) {
        a.invoke(begin, project, label, false);
        try {
            work.run();
        } finally {
            a.invoke(end, project, false);
        }
    }

    private double time(NativeNotes.Cell cell) {
        return round(cell.step * cell.stepBeats);
    }

    private double round(double beats) {
        return (Double) a.invoke(round, null, beats);
    }

    private void add(Object clip, NativeNotes.Cell cell) {
        // The duration is stored to 2^-20 beat as well: unrounded, a note reads back exactly until Undo/Redo rounds it.
        Object note =
                a.invoke(newNote, null, time(cell), round(cell.durationBeats), (double) (cell.velocity * VELOCITY));
        a.invoke(addNote, clip, note, cell.channel, cell.pitch, null);
    }

    private Object rowOf(Object clip, NativeNotes.Cell cell) {
        return a.invoke(row, a.invoke(rows, clip), cell.channel, cell.pitch, false);
    }

    private List<?> notes(Object clip, NativeNotes.Cell cell) {
        Object r = rowOf(clip, cell);
        return r == null ? List.of() : new ArrayList<>((List<?>) a.invoke(events, r));
    }

    private double number(Object nativeNumber) {
        return (Double) a.invoke(timing.doubleGet, nativeNumber);
    }
}
