package dev.bitwigagent.launcher;

import com.bitwig.extension.controller.api.NoteStep;
import com.google.gson.*;
import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.HostModule;
import dev.bitwigagent.core.HostOperation;
import dev.bitwigagent.core.Outcome;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.core.Results;
import dev.bitwigagent.core.Scheduler;
import dev.bitwigagent.core.Verification;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Note reads and edits of one launcher clip. Edits are written natively as one Undo step when the exact bindings
 * match the host (else through the cursor clip's public per-note setters) and read back through the cursor clip's note
 * grid.
 */
public final class NoteModule implements HostModule {
    static final Set<String> METHODS = Set.of("notes.get", "notes.set");
    private static final int NOTE_LIMIT = 2048;

    private final LauncherState state;
    private final Scheduler scheduler;
    final NativeNotes natives;

    public NoteModule(LauncherState state, NativeNotes natives) {
        this.state = state;
        this.scheduler = state.scheduler;
        this.natives = natives;
    }

    @Override
    public Set<String> methods() {
        return METHODS;
    }

    @Override
    public void validate(String method, JsonObject p) {
        if (method.equals("notes.set")) NoteGrid.plan(p, p.getAsJsonArray("edits"), "edits", null);
        else NoteGrid.readWindow(p);
    }

    @Override
    public void dispatch(HostOperation op, String method, JsonObject p) {
        scheduler.check(op);
        boolean edit = method.equals("notes.set");
        List<NoteGrid.Part> parts = edit ? NoteGrid.plan(p, p.getAsJsonArray("edits"), "edits", null) : null;
        NoteGrid.Window explicit = edit ? null : NoteGrid.readWindow(p);
        int channel = p.has("channel") ? p.get("channel").getAsInt() : 0;
        boolean full = NoteGrid.full(p);
        state.targets.enterClip(op, p.getAsJsonObject("target"), false, at -> {
            LauncherState.Target target = state.target(at);
            LauncherState.ready(target.slot);
            state.acquire(op);
            if (edit) state.requireResolved(target.id(), target.scene());
            LauncherState.requireContent(target.slot);
            long end = state.until(op);
            if (edit) write(op, target, parts, full, end);
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

    /**
     * Writes the planned windows. Natively every window is written in one Undo step before anything is selected, and
     * the windows then only drive the readback. Through the public setters one window is an ordinary note edit;
     * several run in order as follow-up operations and stop at the first that does not succeed, the rest reported not
     * reached.
     */
    void write(HostOperation op, LauncherState.Target target, List<NoteGrid.Part> parts, boolean full, long end) {
        NativeNotes.Access access = natives.access(op);
        if (access != null) {
            writeNative(op, target, parts, full, access);
            return;
        }
        if (parts.size() == 1) bindAndEdit(op, target, parts.get(0), full, end, true);
        else new Windows(op, target, parts, null, full).run(0);
    }

    /** A write's windows with their edits, also as one flat list in window order for the native task. */
    static final class Planned {
        final List<NoteGrid.Part> parts;
        final List<List<NoteGrid.Edit>> edits = new ArrayList<>();
        final List<NoteGrid.Edit> flat = new ArrayList<>();
        final List<NoteGrid.Window> windows = new ArrayList<>();

        Planned(List<NoteGrid.Part> parts) {
            this.parts = parts;
            for (NoteGrid.Part part : parts) {
                List<NoteGrid.Edit> list = NoteGrid.edits(part);
                edits.add(list);
                for (NoteGrid.Edit e : list) {
                    flat.add(e);
                    windows.add(part.window);
                }
            }
        }

        List<NativeNotes.Cell> cells() {
            return NoteGrid.cells(flat, windows);
        }
    }

    /** One native task: observe every cell, write the ones that differ in one Undo step, then read the windows back. */
    private void writeNative(
            HostOperation op,
            LauncherState.Target target,
            List<NoteGrid.Part> parts,
            boolean full,
            NativeNotes.Access access) {
        Planned planned = new Planned(parts);
        List<NativeNotes.Cell> cells = planned.cells();
        state.guard(op, target);
        String id = target.id(), expected = target.slot.api.name().get();
        Runnable check = state.nativeCheck(op, target);
        access.execute(() -> {
            try {
                access.edit(
                        target.slot.api,
                        id,
                        expected,
                        cells,
                        check,
                        (loopStart, loopEnd, observed) ->
                                NoteGrid.nativePlan(planned.flat, planned.windows, loopStart, loopEnd, observed),
                        () -> {
                            check.run();
                            op.markDispatched();
                        });
                scheduler.schedule(op, () -> readBack(op, target, planned, full), 0);
            } catch (RuntimeException | Error failure) {
                op.fail(failure);
            }
        });
    }

    /** Reads back natively written windows: one directly, several as follow-up operations that each read one. */
    void readBack(HostOperation op, LauncherState.Target target, Planned planned, boolean full) {
        state.guard(op, target);
        if (planned.parts.size() == 1)
            bindAndVerify(op, target, planned.parts.get(0), planned.edits.get(0), full, state.until(op), true);
        else new Windows(op, target, planned.parts, planned.edits, full).run(0);
    }

    private void bindAndVerify(
            HostOperation op,
            LauncherState.Target target,
            NoteGrid.Part part,
            List<NoteGrid.Edit> changes,
            boolean full,
            long end,
            boolean dispatches) {
        state.bind(
                op,
                target,
                dispatches,
                () -> part.window,
                end,
                (o, t, e, w) -> awaitNotes(o, t, w, changes, full, e, true));
    }

    /** Binds the cursor clip with the part's window and writes its edits once it is settled. */
    private void bindAndEdit(
            HostOperation op,
            LauncherState.Target target,
            NoteGrid.Part part,
            boolean full,
            long end,
            boolean dispatches) {
        state.bind(op, target, dispatches, () -> part.window, end, (o, t, e, w) -> editNotes(o, t, part, full));
    }

    /**
     * Windows run in order as follow-up operations. With {@code written} (the native write already holds every
     * window's edits) each window only reads back, all of them run, and a window left unread is unknown; otherwise
     * each writes its own edits and the first that does not succeed stops the rest.
     */
    private final class Windows {
        private final HostOperation op;
        private final LauncherState.Target target;
        private final List<NoteGrid.Part> parts;
        private final List<List<NoteGrid.Edit>> written;
        private final boolean full;
        private final JsonArray entries = new JsonArray();
        private long slowest;

        Windows(
                HostOperation op,
                LauncherState.Target target,
                List<NoteGrid.Part> parts,
                List<List<NoteGrid.Edit>> written,
                boolean full) {
            this.op = op;
            this.target = target;
            this.parts = parts;
            this.written = written;
            this.full = full;
        }

        void run(int index) {
            long started = scheduler.now();
            if (index > 0 && op.deadline - started < 2 * slowest) {
                if (written != null) {
                    for (int i = index; i < parts.size(); i++)
                        record(i, unread(written.get(i), "Too little time left to read this window back."));
                    finish();
                    return;
                }
                JsonObject entry = Results.notApplied();
                entry.addProperty("reason", Reason.DEADLINE.wire());
                entry.addProperty("message", "Too little time left for another window; send its notes again.");
                record(index, entry);
                finish();
                return;
            }
            HostOperation child = op.child();
            state.selection.handOff(child);
            child.result.whenComplete((result, error) -> {
                try {
                    state.selection.handOff(op);
                    slowest = Math.max(slowest, scheduler.now() - started);
                    if (error != null && entries.size() == 0 && !op.dispatched()) {
                        op.fail(Errors.cause(error));
                        return;
                    }
                    JsonObject entry = error == null ? result : Errors.entryResult(error);
                    if (written != null && !entry.has("summary"))
                        entry = Results.unknown(merged(entry, unread(written.get(index), null)));
                    record(index, entry);
                    boolean next = written != null || Results.outcomeOf(entry).succeeded();
                    if (next && index + 1 < parts.size()) run(index + 1);
                    else finish();
                } catch (RuntimeException failure) {
                    op.fail(failure);
                }
            });
            try {
                // Only the first window's selection counts as a write; later ones rebind the clip already selected.
                if (written != null)
                    bindAndVerify(
                            child, target, parts.get(index), written.get(index), full, state.until(child), index == 0);
                else bindAndEdit(child, target, parts.get(index), full, state.until(child), index == 0);
            } catch (RuntimeException failure) {
                child.fail(failure);
            }
        }

        private void record(int index, JsonObject result) {
            JsonObject entry = new JsonObject();
            entry.add("window", parts.get(index).window.json());
            for (Map.Entry<String, JsonElement> e : result.entrySet())
                if (!WINDOW_DROPPED.contains(e.getKey())) entry.add(e.getKey(), e.getValue());
            entries.add(entry);
        }

        private void finish() {
            JsonObject out = new JsonObject();
            out.add("ref", state.clipRef(target));
            state.addClipMetadata(out);
            Outcome worst = Outcome.ALREADY_MATCHED;
            String reason = null;
            int requested = 0, dispatched = 0, matched = 0, verified = 0, unverified = 0, unreached = 0;
            JsonArray windows = new JsonArray(), notReached = new JsonArray();
            for (int i = 0; i < parts.size(); i++) {
                NoteGrid.Part part = parts.get(i);
                int size = part.indices.size();
                requested += size;
                JsonObject entry;
                if (i < entries.size()) entry = entries.get(i).getAsJsonObject();
                else {
                    entry = Results.notApplied();
                    entry.addProperty("reason", Reason.NOT_REACHED.wire());
                    entry.add("window", part.window.json());
                }
                Outcome outcome = Results.outcomeOf(entry);
                worst = worst.worst(outcome);
                if (reason == null && entry.has("reason"))
                    reason = entry.get("reason").getAsString();
                if (entry.has("summary")) {
                    JsonObject s = entry.getAsJsonObject("summary");
                    dispatched += s.get("dispatched").getAsInt();
                    matched += s.get("alreadyMatched").getAsInt();
                    verified += s.get("verified").getAsInt();
                    unverified += s.get("unverified").getAsInt();
                } else if (outcome == Outcome.UNKNOWN) {
                    dispatched += size;
                    unverified += size;
                } else {
                    unreached += size;
                    for (int index : part.indices) notReached.add(index);
                }
                windows.add(entry);
            }
            Results.stamp(out, worst, windows);
            if (reason != null) out.addProperty("reason", reason);
            JsonObject summary = new JsonObject();
            summary.addProperty("requested", requested);
            summary.addProperty("dispatched", dispatched);
            summary.addProperty("alreadyMatched", matched);
            summary.addProperty("verified", verified);
            summary.addProperty("unverified", unverified);
            summary.addProperty("notReached", unreached);
            out.add("summary", summary);
            out.addProperty("undo", NativeNotes.undo(written != null, dispatched));
            out.add("windows", windows);
            if (notReached.size() > 0) out.add("notReached", notReached);
            op.result.complete(out);
        }
    }

    /** A natively written window that was not read back: every edit unknown, counted as dispatched or matched. */
    private static JsonObject unread(List<NoteGrid.Edit> changes, String message) {
        JsonObject entry = Results.unknown();
        entry.addProperty("reason", Reason.NOT_OBSERVED.wire());
        if (message != null) entry.addProperty("message", message);
        int matched = 0;
        for (NoteGrid.Edit e : changes) if (e.alreadyMatched) matched++;
        JsonObject summary = new JsonObject();
        summary.addProperty("requested", changes.size());
        summary.addProperty("dispatched", changes.size() - matched);
        summary.addProperty("alreadyMatched", matched);
        summary.addProperty("verified", 0);
        summary.addProperty("unverified", changes.size());
        entry.add("summary", summary);
        return entry;
    }

    private static JsonObject merged(JsonObject first, JsonObject defaults) {
        for (Map.Entry<String, JsonElement> e : defaults.entrySet())
            if (!first.has(e.getKey())) first.add(e.getKey(), e.getValue());
        return first;
    }

    /** Keys of a window's result the combined result already carries once. */
    private static final Set<String> WINDOW_DROPPED =
            Set.of("ref", "window", "loop", "playStart", "playStop", "loopEnabled", "undo");

    private void editNotes(HostOperation op, LauncherState.Target target, NoteGrid.Part part, boolean full) {
        NoteGrid.Window w = part.window;
        List<NoteGrid.Edit> changes = NoteGrid.edits(part);
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
        scheduler.schedule(op, () -> awaitNotes(op, target, w, changes, full, end, false), Scheduler.POLL_MS);
    }

    /**
     * Polls the window's cells until every edit reads back. A per-note write needs a callback newer than its dispatch
     * (else the grid may be stale); {@code nativeWrite} grids are bound after the write, so the readback alone counts.
     */
    private void awaitNotes(
            HostOperation op,
            LauncherState.Target target,
            NoteGrid.Window w,
            List<NoteGrid.Edit> changes,
            boolean full,
            long end,
            boolean nativeWrite) {
        scheduler.poll(
                op,
                end,
                () -> {
                    state.cursorGuard(op, target);
                    boolean all = true;
                    for (NoteGrid.Edit e : changes) {
                        boolean fresh = nativeWrite || fresh(e);
                        e.verified = (fresh || e.alreadyMatched)
                                && NoteGrid.matches(e, state.clip.getStep(w.channel, e.x, e.pitch));
                        all &= e.verified;
                    }
                    return all;
                },
                all -> finishNotes(op, target, w, changes, full, nativeWrite));
    }

    private boolean fresh(NoteGrid.Edit e) {
        return state.noteGenerations[e.x][e.pitch] == e.generation && state.noteVersions[e.x][e.pitch] > e.before;
    }

    private void finishNotes(
            HostOperation op,
            LauncherState.Target target,
            NoteGrid.Window w,
            List<NoteGrid.Edit> changes,
            boolean full,
            boolean nativeWrite) {
        JsonObject out = notesResult(target, w, full);
        // The outcome always covers every edit; detail only changes how many entries are listed.
        Outcome worst = Outcome.ALREADY_MATCHED;
        Reason firstReason = null;
        JsonArray results = new JsonArray();
        int verified = 0, matched = 0;
        for (NoteGrid.Edit e : changes) {
            if (e.verified) verified++;
            if (e.alreadyMatched) matched++;
            boolean fresh = nativeWrite || fresh(e);
            Outcome outcome = e.alreadyMatched && e.verified
                    ? Outcome.ALREADY_MATCHED
                    : e.verified ? Outcome.APPLIED : Outcome.UNKNOWN;
            Reason reason = e.verified ? null : fresh ? Reason.READBACK_MISMATCH : Reason.NOT_OBSERVED;
            worst = worst.worst(outcome);
            if (firstReason == null) firstReason = reason;
            if (!full && e.verified) continue;
            JsonObject item = new JsonObject();
            item.addProperty("index", e.index);
            item.addProperty("op", e.set ? "set" : "clear");
            if (full) item.addProperty("step", w.start + e.x);
            else item.addProperty("beat", (w.start + e.x) * w.size);
            item.addProperty("pitch", e.pitch);
            Results.stamp(item, outcome, Verification.API_READBACK);
            if (reason != null) item.addProperty("reason", reason.wire());
            item.add("observed", NoteGrid.observedJson(state.clip.getStep(w.channel, e.x, e.pitch), w, e.x, e.pitch));
            results.add(item);
        }
        Results.stamp(out, worst, Verification.API_READBACK);
        if (firstReason != null) out.addProperty("reason", firstReason.wire());
        JsonObject summary = new JsonObject();
        summary.addProperty("requested", changes.size());
        summary.addProperty("dispatched", changes.size() - matched);
        summary.addProperty("alreadyMatched", matched);
        summary.addProperty("verified", verified);
        summary.addProperty("unverified", changes.size() - verified);
        out.add("summary", summary);
        out.addProperty("undo", NativeNotes.undo(nativeWrite, changes.size() - matched));
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
                        + (w.start + w.steps) + " stepSize="
                        + w.exact.stripTrailingZeros().toPlainString() + " steps=128 pitchStart=0 pitchCount=128";
        }
        if (continuation != null) out.addProperty("continuation", continuation);
        return out;
    }
}
