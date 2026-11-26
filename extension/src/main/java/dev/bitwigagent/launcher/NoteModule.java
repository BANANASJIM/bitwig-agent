package dev.bitwigagent.launcher;

import com.bitwig.extension.controller.api.NoteStep;
import com.google.gson.*;
import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.HostModule;
import dev.bitwigagent.core.HostOperation;
import dev.bitwigagent.core.Outcome;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.core.Scheduler;
import java.util.List;
import java.util.Set;

/** Note reads and edits of one launcher clip through the cursor clip's note grid. */
public final class NoteModule implements HostModule {
    static final Set<String> METHODS = Set.of("notes.read", "notes.edit");
    private static final int NOTE_LIMIT = 2048;

    private final LauncherState state;
    private final Scheduler scheduler;

    public NoteModule(LauncherState state) {
        this.state = state;
        this.scheduler = state.scheduler;
    }

    @Override
    public Set<String> methods() {
        return METHODS;
    }

    @Override
    public void validate(String method, JsonObject p) {
        boolean edit = method.equals("notes.edit");
        NoteGrid.Window w = NoteGrid.window(p, edit ? p.getAsJsonArray("edits") : null);
        if (edit) NoteGrid.edits(p.getAsJsonArray("edits"), w);
    }

    @Override
    public void dispatch(HostOperation op, String method, JsonObject p) {
        scheduler.check(op);
        boolean edit = method.equals("notes.edit");
        JsonArray edits = edit ? p.getAsJsonArray("edits") : null;
        NoteGrid.Window explicit = NoteGrid.window(p, edits);
        int channel = p.has("channel") ? p.get("channel").getAsInt() : 0;
        boolean full = NoteGrid.full(p);
        state.targets.enterClip(op, p.getAsJsonObject("target"), false, at -> {
            LauncherState.Target target = state.target(at);
            LauncherState.ready(target.slot);
            state.acquire(op);
            if (edit) state.requireResolved(target.id(), target.scene());
            LauncherState.requireContent(target.slot);
            long end = state.until(op);
            if (edit) bindAndEdit(op, target, explicit, edits, full, end);
            else
                state.bind(
                        op,
                        target,
                        true,
                        () -> explicit != null ? explicit : NoteGrid.loopWindow(state.clip, channel),
                        end,
                        (o, t, e, w) -> {
                            state.cursorGuard(o, t);
                            o.result.complete(notesResult(t, w, true));
                        });
        });
    }

    /** Binds the cursor clip with the note window and writes the edits once it is settled. */
    void bindAndEdit(
            HostOperation op,
            LauncherState.Target target,
            NoteGrid.Window window,
            JsonArray edits,
            boolean full,
            long end) {
        state.bind(op, target, true, () -> window, end, (o, t, e, w) -> editNotes(o, t, w, edits, full));
    }

    private void editNotes(
            HostOperation op, LauncherState.Target target, NoteGrid.Window w, JsonArray edits, boolean full) {
        List<NoteGrid.Edit> changes = NoteGrid.edits(edits, w);
        state.cursorGuard(op, target);
        NoteGrid.preflight(changes, w, state.clip);
        for (NoteGrid.Edit e : changes) {
            state.cursorGuard(op, target);
            e.before = state.noteVersions[e.x][e.pitch];
            e.generation = state.bindingGeneration;
            NoteStep old = state.clip.getStep(w.channel, e.x, e.pitch);
            e.alreadyMatched = NoteGrid.matches(e, old);
            if (e.alreadyMatched) continue; // Preserve expression and other properties on an existing matching note.
            op.markDispatched();
            if (e.set) state.clip.setStep(w.channel, e.x, e.pitch, e.velocity, e.duration);
            else state.clip.clearStep(w.channel, e.x, e.pitch);
        }
        long end = state.until(op);
        scheduler.schedule(op, () -> awaitNotes(op, target, w, changes, full, end), Scheduler.POLL_MS);
    }

    private void awaitNotes(
            HostOperation op,
            LauncherState.Target target,
            NoteGrid.Window w,
            List<NoteGrid.Edit> changes,
            boolean full,
            long end) {
        scheduler.poll(
                op,
                end,
                () -> {
                    state.cursorGuard(op, target);
                    boolean all = true;
                    for (NoteGrid.Edit e : changes) {
                        boolean fresh = fresh(e);
                        e.verified = (fresh || e.alreadyMatched)
                                && NoteGrid.matches(e, state.clip.getStep(w.channel, e.x, e.pitch));
                        all &= e.verified;
                    }
                    return all;
                },
                all -> finishNotes(op, target, w, changes, full));
    }

    private boolean fresh(NoteGrid.Edit e) {
        return state.noteGenerations[e.x][e.pitch] == e.generation && state.noteVersions[e.x][e.pitch] > e.before;
    }

    private void finishNotes(
            HostOperation op,
            LauncherState.Target target,
            NoteGrid.Window w,
            List<NoteGrid.Edit> changes,
            boolean full) {
        JsonObject out = notesResult(target, w, full);
        // The outcome always covers every edit; detail only changes how many entries are listed.
        Outcome worst = Outcome.ALREADY_MATCHED;
        Reason firstReason = null;
        JsonArray results = new JsonArray();
        int verified = 0, matched = 0;
        for (NoteGrid.Edit e : changes) {
            if (e.verified) verified++;
            if (e.alreadyMatched) matched++;
            boolean fresh = fresh(e);
            Outcome outcome = e.alreadyMatched && e.verified
                    ? Outcome.ALREADY_MATCHED
                    : e.verified ? Outcome.APPLIED : Outcome.UNKNOWN;
            Reason reason = e.verified ? null : fresh ? Reason.READBACK_MISMATCH : Reason.NOT_OBSERVED;
            worst = worst.worst(outcome);
            if (firstReason == null) firstReason = reason;
            if (!full && e.verified) continue;
            JsonObject item = new JsonObject();
            item.addProperty("op", e.set ? "set" : "clear");
            if (full) item.addProperty("step", w.start + e.x);
            else item.addProperty("beat", (w.start + e.x) * w.size);
            item.addProperty("pitch", e.pitch);
            item.addProperty("outcome", outcome.wire());
            if (reason != null) item.addProperty("reason", reason.wire());
            item.add("observed", NoteGrid.observedJson(state.clip.getStep(w.channel, e.x, e.pitch), w, e.x, e.pitch));
            results.add(item);
        }
        out.addProperty("outcome", worst.wire());
        if (firstReason != null) out.addProperty("reason", firstReason.wire());
        JsonObject summary = new JsonObject();
        summary.addProperty("requested", changes.size());
        summary.addProperty("dispatched", changes.size() - matched);
        summary.addProperty("alreadyMatched", matched);
        summary.addProperty("verified", verified);
        summary.addProperty("unverified", changes.size() - verified);
        out.add("summary", summary);
        if (full) out.add("edits", results);
        else if (results.size() > 0) out.add("unverifiedEdits", results);
        op.result.complete(out);
    }

    /** Machine-actionable flags only; constant explanations live in the catalog and PROTOCOL.md. list=false omits the note listing. */
    JsonObject notesResult(LauncherState.Target target, NoteGrid.Window w, boolean list) {
        JsonObject out = new JsonObject();
        out.add("ref", state.clipRef(target));
        out.add("window", w.json());
        state.addClipMetadata(out);
        out.addProperty("gridGeneration", state.bindingGeneration);
        out.addProperty("gridReadiness", "scheduled_settling_not_acknowledged");
        JsonArray notes = new JsonArray();
        JsonArray sustains = new JsonArray();
        int total = 0;
        for (int x = 0; x < w.steps; x++)
            for (int pitch = w.pitch; pitch < w.pitch + w.pitches; pitch++) {
                NoteStep note = state.clip.getStep(w.channel, x, pitch);
                if (note == null || note.state() == null)
                    throw Errors.notApplied(Reason.NOT_READY, "Note grid is not readable");
                if (NoteGrid.hasState(note, "NoteOn")) {
                    total++;
                    if (list && notes.size() < NOTE_LIMIT) notes.add(NoteGrid.noteJson(note, w, x, pitch));
                } else if (list && NoteGrid.hasState(note, "NoteSustain") && x == 0) {
                    JsonObject row = NoteGrid.noteJson(note, w, x, pitch);
                    row.addProperty("state", "NoteSustain"); // continues from before the window: not an onset to copy
                    sustains.add(row);
                }
            }
        out.addProperty("notesInWindow", total);
        if (!list) return out;
        out.add("notes", notes);
        out.add("continuingAtWindowStart", sustains);
        out.addProperty("notesReturned", notes.size());
        out.addProperty("completeWindow", total == notes.size());
        String continuation = null;
        if (total > notes.size())
            continuation = "Subdivide steps or pitchCount to read the remaining onset cells; response is capped at "
                    + NOTE_LIMIT + " rows";
        double
                loopEnd =
                        state.clip.getLoopStart().get()
                                + state.clip.getLoopLength().get(),
                windowEnd = (w.start + w.steps) * w.size;
        boolean coversLoop = windowEnd >= loopEnd - 1e-9;
        if (w.derived) {
            out.addProperty("windowCoversLoop", coversLoop);
            if (!coversLoop)
                continuation = (continuation == null ? "" : continuation + ". ")
                        + "The clip loop continues past this window and a coarser grid would drop below 1/16 note: read the rest with an explicit window, startStep="
                        + (w.start + w.steps) + " stepSizeBeats="
                        + w.exact.stripTrailingZeros().toPlainString() + " steps=128 pitchStart=0 pitchCount=128";
        }
        if (continuation != null) out.addProperty("continuation", continuation);
        return out;
    }
}
