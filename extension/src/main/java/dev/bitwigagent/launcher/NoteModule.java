package dev.bitwigagent.launcher;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.HostLimits;
import dev.bitwigagent.core.HostModule;
import dev.bitwigagent.core.HostOperation;
import dev.bitwigagent.core.Outcome;
import dev.bitwigagent.core.RangeEdit;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.core.Results;
import dev.bitwigagent.core.Scheduler;
import dev.bitwigagent.core.Verification;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Note reads and writes of one launcher clip, in one record shape and one range rule. With the exact native binding a
 * read sees every note without selecting anything and a write is one Undo step read back natively; without it the
 * public note grid is read and written window by window ({@link PublicNotes}).
 */
public final class NoteModule implements HostModule {
    static final Set<String> METHODS = Set.of("notes.get", "notes.set");
    /** A bound on one note's JSON, so the notes a response may list fit the message cap that requests share. */
    static final int NOTE_BYTES = 128;
    /** At most this many notes per response; a request of as many notes fits the same cap. */
    static final int NOTE_LIMIT = HostLimits.LIMITS.maxMessageBytes / NOTE_BYTES;
    /** Expected or unexpected notes a readback mismatch lists. */
    private static final int MISMATCH_LIMIT = 16;

    private final LauncherState state;
    private final Scheduler scheduler;
    private final PublicNotes publicNotes;
    final NativeNotes natives;

    public NoteModule(LauncherState state, NativeNotes natives) {
        this.state = state;
        this.scheduler = state.scheduler;
        this.natives = natives;
        this.publicNotes = new PublicNotes(state);
    }

    @Override
    public Set<String> methods() {
        return METHODS;
    }

    @Override
    public void validate(String method, JsonObject p) {
        requireRange(p);
        if (!method.equals("notes.set")) return;
        if (!p.has("range") && p.getAsJsonArray("notes").size() == 0)
            throw Errors.invalid(
                    "notes is empty: give range (e.g. \"all\") to clear that range, or list notes to write");
        NotePlan.rules(NotePlan.listed(p.getAsJsonArray("notes")), NoteRange.of(p), null);
    }

    /** The cross-field rules of {@code range} the schema cannot say. */
    private static void requireRange(JsonObject p) {
        if (!p.has("range") || !p.get("range").isJsonObject()) return;
        JsonObject r = p.getAsJsonObject("range");
        if (r.get("end").getAsDouble() <= r.get("start").getAsDouble())
            throw Errors.invalid("range.end must be greater than range.start");
        if (r.has("pitches")
                && r.getAsJsonArray("pitches").get(0).getAsInt()
                        > r.getAsJsonArray("pitches").get(1).getAsInt())
            throw Errors.invalid("range.pitches is [low, high] with low <= high");
    }

    @Override
    public void dispatch(HostOperation op, String method, JsonObject p) {
        scheduler.check(op);
        boolean write = method.equals("notes.set");
        NoteRange range = NoteRange.of(p);
        List<NotePlan.Listed> listed = write ? NotePlan.listed(p.getAsJsonArray("notes")) : null;
        state.targets.enterClip(op, p.getAsJsonObject("target"), false, at -> {
            LauncherState.Target target = state.target(at);
            LauncherState.ready(target.slot);
            if (write) state.requireResolved(target.id(), target.scene());
            LauncherState.requireContent(target.slot);
            NativeNotes.Access access = natives.access(op);
            if (access == null) state.acquire(op);
            if (write) {
                NotePlan plan = new NotePlan(listed, range);
                if (access != null) writeNative(op, target, range, plan, access);
                else
                    publicNotes.write(
                            op,
                            target,
                            listed,
                            range,
                            plan,
                            ok -> op.result.complete(publicWritten(target, range, plan, ok)));
            } else if (access != null) readNative(op, target, range, access);
            else publicNotes.read(op, target, range, read -> op.result.complete(publicRead(target, range, read)));
        });
    }

    // ---- native ----

    private void readNative(HostOperation op, LauncherState.Target target, NoteRange range, NativeNotes.Access access) {
        String id = target.id(), expected = target.slot.api.name().get();
        Runnable check = state.nativeCheck(op, target);
        access.execute(() -> {
            try {
                NativeNotes.Observed clip = access.read(target.slot.api, id, expected, check);
                scheduler.schedule(op, () -> op.result.complete(nativeRead(target, range, clip)), 0);
            } catch (RuntimeException | Error failure) {
                op.fail(failure);
            }
        });
    }

    private JsonObject nativeRead(LauncherState.Target target, NoteRange range, NativeNotes.Observed clip) {
        JsonObject out = new JsonObject();
        out.add("ref", state.clipRef(target));
        out.add("loop", loop(clip.loopStart, clip.loopLength));
        listing(out, range, clip.notes);
        out.addProperty("exact", true);
        return out;
    }

    /**
     * One native task: read every note, plan the range rule against them, write the change in one Undo step and read
     * the clip back; the outcome compares that readback with the planned content.
     */
    private void writeNative(
            HostOperation op, LauncherState.Target target, NoteRange range, NotePlan plan, NativeNotes.Access access) {
        state.guard(op, target);
        String id = target.id(), expected = target.slot.api.name().get();
        Runnable check = state.nativeCheck(op, target);
        List<Note> before = new ArrayList<>();
        access.execute(() -> {
            try {
                NativeNotes.Observed after = access.edit(
                        target.slot.api,
                        id,
                        expected,
                        check,
                        observed -> {
                            before.addAll(observed.notes);
                            return plan.plan(observed.notes, observed.loopEnd());
                        },
                        () -> {
                            check.run();
                            op.markDispatched();
                        });
                scheduler.schedule(op, () -> op.result.complete(nativeWritten(target, range, plan, before, after)), 0);
            } catch (RuntimeException | Error failure) {
                op.fail(failure);
            }
        });
    }

    private JsonObject nativeWritten(
            LauncherState.Target target,
            NoteRange range,
            NotePlan plan,
            List<Note> before,
            NativeNotes.Observed after) {
        RangeEdit.Change<Note> change = plan.change;
        JsonObject out;
        if (change.isEmpty()) out = Results.outcome(Outcome.ALREADY_MATCHED, Verification.NATIVE_READBACK);
        else {
            List<Note> expected = new ArrayList<>(RangeEdit.apply(before, change));
            JsonObject mismatch = mismatch(expected, after.notes);
            if (mismatch == null) out = Results.outcome(Outcome.APPLIED, Verification.NATIVE_READBACK);
            else {
                out = Results.unknown();
                out.addProperty("reason", Reason.READBACK_MISMATCH.wire());
                out.add("mismatch", mismatch);
            }
        }
        written(out, target, range, change, NativeNotes.undo(true, change.isEmpty() ? 0 : 1), true);
        out.addProperty("count", after.notes.size());
        return out;
    }

    /** Notes expected but not read back and notes read back but not expected; null when they agree. */
    static JsonObject mismatch(List<Note> expected, List<Note> actual) {
        List<Note> missing = new ArrayList<>(expected), extra = new ArrayList<>(actual);
        Set<Note> seen = new HashSet<>(actual), wanted = new HashSet<>(expected);
        missing.removeIf(seen::contains);
        extra.removeIf(wanted::contains);
        if (missing.isEmpty() && extra.isEmpty() && expected.size() == actual.size()) return null;
        JsonObject out = new JsonObject();
        out.add("missing", notes(missing));
        out.add("unexpected", notes(extra));
        return out;
    }

    private static JsonArray notes(List<Note> list) {
        list.sort(Note.ORDER);
        JsonArray out = new JsonArray();
        for (Note n : list.subList(0, Math.min(MISMATCH_LIMIT, list.size()))) out.add(n.json());
        return out;
    }

    // ---- public ----

    private JsonObject publicRead(LauncherState.Target target, NoteRange range, PublicNotes.Read read) {
        JsonObject out = new JsonObject();
        out.add("ref", state.clipRef(target));
        out.add(
                "loop",
                loop(state.clip.getLoopStart().get(), state.clip.getLoopLength().get()));
        listing(out, range, read.notes);
        if (!read.complete) out.addProperty("complete", false);
        out.addProperty("exact", false);
        out.addProperty("onsetResolution", read.size);
        return out;
    }

    private JsonObject publicWritten(LauncherState.Target target, NoteRange range, NotePlan plan, boolean verified) {
        RangeEdit.Change<Note> change = plan.change;
        int dispatched = change.insert.size() + change.update.size() + change.remove.size();
        JsonObject out;
        if (verified)
            out = Results.outcome(
                    change.isEmpty() ? Outcome.ALREADY_MATCHED : Outcome.APPLIED, Verification.API_READBACK);
        else {
            out = Results.unknown();
            out.addProperty("reason", Reason.NOT_OBSERVED.wire());
        }
        written(out, target, range, change, NativeNotes.undo(false, dispatched), false);
        return out;
    }

    // ---- the note stage of clips.insert ----

    /** The notes of a clips.insert, canonical, after the rules that need no clip ({@code length} in beats). */
    public static List<Note> insertNotes(JsonArray notes, java.math.BigDecimal length) {
        List<NotePlan.Listed> listed = NotePlan.listed(notes);
        NotePlan.rules(listed, null, length);
        List<Note> out = new ArrayList<>(listed.size());
        for (NotePlan.Listed l : listed) out.add(l.resolve(null));
        return out;
    }

    /** noteWrite of a native clips.insert: the created clip, read back natively, against the requested notes. */
    JsonObject insertedNative(LauncherState.Target target, List<Note> requested, NativeNotes.Observed created) {
        JsonObject mismatch = mismatch(new ArrayList<>(requested), created.notes);
        JsonObject out;
        if (mismatch == null) out = Results.outcome(Outcome.APPLIED, Verification.NATIVE_READBACK);
        else {
            out = Results.unknown();
            out.addProperty("reason", Reason.READBACK_MISMATCH.wire());
            out.add("mismatch", mismatch);
        }
        RangeEdit.Change<Note> change = RangeEdit.plan(List.of(), requested, null, Note.KIND);
        written(out, target, null, change, NativeNotes.ONE_STEP, true);
        out.addProperty("count", created.notes.size());
        return out;
    }

    /** The note stage of a public clips.insert: the listed notes written into the new clip like notes.set. */
    void insertPublic(HostOperation op, LauncherState.Target target, JsonArray notes) {
        List<NotePlan.Listed> listed = NotePlan.listed(notes);
        NotePlan plan = new NotePlan(listed, null);
        publicNotes.write(
                op, target, listed, null, plan, ok -> op.result.complete(publicWritten(target, null, plan, ok)));
    }

    // ---- results ----

    private void written(
            JsonObject out,
            LauncherState.Target target,
            NoteRange range,
            RangeEdit.Change<Note> change,
            String undo,
            boolean exact) {
        out.add("ref", state.clipRef(target));
        if (range != null) out.add("range", range.json());
        JsonObject summary = new JsonObject();
        summary.addProperty("inserted", change.insert.size());
        summary.addProperty("updated", change.update.size());
        summary.addProperty("removed", change.remove.size());
        summary.addProperty("unchanged", change.unchanged);
        out.add("summary", summary);
        out.addProperty("undo", undo);
        out.addProperty("exact", exact);
    }

    /** count, complete and notes of the range (all notes without one), capped at {@link #NOTE_LIMIT}. */
    private static void listing(JsonObject out, NoteRange range, List<Note> notes) {
        JsonArray list = new JsonArray();
        int count = 0;
        for (Note n : notes) {
            if (range != null && !range.test(n)) continue;
            if (count++ < NOTE_LIMIT) list.add(n.json());
        }
        if (range != null) out.add("range", range.json());
        out.addProperty("count", count);
        out.addProperty("complete", count == list.size());
        out.add("notes", list);
    }

    private static JsonObject loop(double start, double length) {
        JsonObject loop = new JsonObject();
        loop.addProperty("start", start);
        loop.addProperty("length", length);
        return loop;
    }
}
