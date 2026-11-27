package dev.bitwigagent.nativehost;

import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.RangeEdit;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.launcher.NativeNotes;
import dev.bitwigagent.launcher.Note;
import java.lang.reflect.Method;
import java.util.*;
import java.util.function.Function;

/**
 * Exact bindings that read a launcher clip's notes and write them as one Undo step. The public {@code Clip.setStep}
 * and {@code clearStep} each post their own document event, and each event opens its own outermost transaction
 * ("Insert Note", "Delete Events"), so every note is an Undo step. Here one document task opens {@code
 * project.begin(label, false)}, makes the same model calls those events end in, and closes it with {@code
 * project.end(false)}: the transaction manager reports only the outermost begin and end to the undo recorder (depth
 * counter), so the inner transactions merge.
 *
 * <p>Read: the clip's row table ({@code note.rowOwner.rows}) is a polyphonic note timeline in both supported hosts
 * (its only implementation extends {@code note.polyTimeline}), so {@code note.polyTimeline.rows} lists every row; each
 * row is a key row (channel, key) and an event list. The arranger readback walks the same rows. Nothing is selected.
 * Write: removed and replaced notes are deleted from their rows with {@code note.rowEvents.delete(Collection)}, then
 * each written note is added with {@code note.clip.add(note.create(time, duration, velocity * 0.007874016f), channel,
 * key, null)}, which leaves the editor selection alone. Times and durations are canonical (2^-20 beat), the step the
 * public setter rounds to. A written note's operators and expressions are set on it after the add, in the same
 * transaction ({@link NativeNoteFeatures}); an updated note is written as a new note, so a multi-point expression
 * curve it had keeps only the value notes.get reads (its start). Clip creation is {@code
 * launcher.emptySlot.createClip()} plus the length the public {@code createEmptyClip} sets, without its slot
 * selection.
 */
public final class NativeNoteWriteAccess implements NativeNotes.Access {
    static final String EDIT_LABEL = "Edit Notes", CREATE_LABEL = "Insert Clip";
    /** The public setter's MIDI-to-model velocity factor (1/127 as a float). */
    private static final float VELOCITY = 0.007874016f;

    private final NativeHost.Access a;
    private final NativeLauncherClip launcher;
    private final NativeClipTiming timing;
    private final NativeNoteFeatures features;
    private final Class<?> noteClip, rowOwner, polyTimeline;
    private final Method newNote, addNote, rows, allRows, channel, key, events, start, length, velocity, delete;
    private final Method begin, end, createClip, clipLength, setLength, rename, setNumber;

    public NativeNoteWriteAccess(NativeHost.Access a) throws ReflectiveOperationException {
        this.a = a;
        launcher = new NativeLauncherClip(a);
        timing = new NativeClipTiming(a);
        features = new NativeNoteFeatures(a);
        noteClip = a.type("note.clip");
        rowOwner = a.type("note.rowOwner");
        polyTimeline = a.type("note.polyTimeline");
        Class<?> note = a.type("note");
        newNote = a.exactMethod("note.create", note, true, double.class, double.class, double.class);
        addNote =
                a.exactMethod("note.clip.add", void.class, false, note, int.class, int.class, a.type("note.selection"));
        rows = a.exactMethod("note.rowOwner.rows", a.type("note.rows"), false);
        allRows = a.exactMethod("note.polyTimeline.rows", List.class, false);
        channel = a.exactMethod("note.keyRow.channel", int.class, false);
        key = a.exactMethod("note.keyRow.key", int.class, false);
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
    public List<String> unread() {
        return features.unread();
    }

    @Override
    public NativeNotes.Observed read(Object slot, String trackId, String expectedName, Runnable check) {
        return snapshot(noteClip(launcher.clip(slot, trackId, expectedName, check))).observed;
    }

    @Override
    public NativeNotes.Observed edit(
            Object slot,
            String trackId,
            String expectedName,
            Runnable check,
            Function<NativeNotes.Observed, RangeEdit.Change<Note>> plan,
            Runnable beforeWrite) {
        NativeLauncherClip.Bound bound = launcher.clip(slot, trackId, expectedName, check);
        Object clip = noteClip(bound);
        Snapshot before = snapshot(clip);
        RangeEdit.Change<Note> change = plan.apply(before.observed);
        if (change.isEmpty()) return before.observed;
        features.requireExpressions(clip, change.written());
        bound.guard();
        beforeWrite.run();
        transaction(bound.project, EDIT_LABEL, () -> {
            Map<Object, List<Object>> doomed = new IdentityHashMap<>();
            for (Note n : change.remove) doom(before, n, doomed);
            for (RangeEdit.Update<Note> u : change.update) doom(before, u.before, doomed);
            for (Map.Entry<Object, List<Object>> row : doomed.entrySet())
                a.invoke(delete, row.getKey(), row.getValue());
            for (Note n : change.written()) add(clip, n);
        });
        return snapshot(clip).observed;
    }

    @Override
    public NativeNotes.Observed create(
            Object slot,
            String trackId,
            String name,
            int beats,
            List<Note> notes,
            Runnable check,
            Runnable beforeWrite) {
        NativeLauncherClip.Bound bound = launcher.empty(slot, trackId, check);
        beforeWrite.run();
        Object[] created = new Object[1];
        transaction(bound.project, CREATE_LABEL, () -> {
            a.invoke(createClip, bound.slot);
            Object clip = a.invoke(launcher.clip, slot);
            if (!noteClip.isInstance(clip) || a.invoke(launcher.slot, clip) != bound.slot)
                throw Errors.unknown(Reason.READBACK_MISMATCH, "No native note clip appeared in the slot.");
            a.invoke(setLength, a.invoke(clipLength, clip), (double) beats);
            a.invoke(rename, clip, name);
            a.invoke(
                    setNumber,
                    a.invoke(timing.position, a.invoke(timing.playStop, a.invoke(timing.timing, clip))),
                    (double) beats);
            features.requireExpressions(clip, notes);
            for (Note n : notes) add(clip, n);
            created[0] = clip;
        });
        return snapshot(created[0]).observed;
    }

    /** The bound clip, required to be a note clip with a row table. */
    private Object noteClip(NativeLauncherClip.Bound bound) {
        Object clip = bound.clip;
        if (!noteClip.isInstance(clip) || !rowOwner.isInstance(clip))
            throw Errors.notApplied(Reason.UNSUPPORTED_SCOPE, "The launcher clip is not a note clip.");
        return clip;
    }

    /** Every note of the clip with its native row and event, and the loop. */
    private final class Snapshot {
        final NativeNotes.Observed observed;
        final Map<Note, Object[]> natives = new IdentityHashMap<>();

        Snapshot(Object clip) {
            Object table = a.invoke(rows, clip);
            if (!polyTimeline.isInstance(table))
                throw Errors.notApplied(Reason.UNSUPPORTED_SCOPE, "The clip's note rows are not a note timeline.");
            List<Note> notes = new ArrayList<>();
            for (Object row : new ArrayList<>((List<?>) a.invoke(allRows, table))) {
                int c = (Integer) a.invoke(channel, row), k = (Integer) a.invoke(key, row);
                List<?> list = new ArrayList<>((List<?>) a.invoke(events, row));
                if (list.isEmpty()) continue;
                if (c < 0 || c > 15 || k < 0 || k > 127)
                    throw Errors.notApplied(
                            Reason.UNSUPPORTED_SCOPE, "The clip has a note row outside channel 0..15 or key 0..127.");
                for (Object e : list) {
                    Note n = Note.read(
                            c,
                            k,
                            (Double) a.invoke(start, e),
                            (Double) a.invoke(length, e),
                            (Double) a.invoke(velocity, e),
                            features.read(e));
                    notes.add(n);
                    natives.put(n, new Object[] {row, e});
                }
            }
            notes.sort(Note.ORDER);
            Object loop = a.invoke(timing.loop, a.invoke(timing.timing, clip));
            observed = new NativeNotes.Observed(
                    Collections.unmodifiableList(notes),
                    number(a.invoke(timing.position, loop)),
                    number(a.invoke(timing.duration, loop)));
        }
    }

    private Snapshot snapshot(Object clip) {
        return new Snapshot(clip);
    }

    private static void doom(Snapshot before, Note n, Map<Object, List<Object>> doomed) {
        Object[] at = before.natives.get(n);
        doomed.computeIfAbsent(at[0], r -> new ArrayList<>()).add(at[1]);
    }

    private void transaction(Object project, String label, Runnable work) {
        a.invoke(begin, project, label, false);
        try {
            work.run();
        } finally {
            a.invoke(end, project, false);
        }
    }

    private void add(Object clip, Note n) {
        Object note = a.invoke(newNote, null, n.beatValue(), n.durationValue(), (double) (n.velocity * VELOCITY));
        a.invoke(addNote, clip, note, n.channel, n.pitch, null);
        features.writeNew(clip, note, n.features);
    }

    private double number(Object nativeNumber) {
        return (Double) a.invoke(timing.doubleGet, nativeNumber);
    }
}
