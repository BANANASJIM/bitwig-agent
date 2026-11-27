package dev.bitwigagent.launcher;

import com.bitwig.extension.controller.api.Application;
import com.bitwig.extension.controller.api.Track;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.HostLimits;
import dev.bitwigagent.core.HostOperation;
import dev.bitwigagent.core.MethodSpecs;
import dev.bitwigagent.core.Outcome;
import dev.bitwigagent.core.PortHandle;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.core.Results;
import dev.bitwigagent.core.RpcException;
import dev.bitwigagent.core.Scheduler;
import dev.bitwigagent.core.Verification;
import dev.bitwigagent.host.TrackRefs;
import java.util.*;
import java.util.function.Consumer;

/**
 * Bitwig's own clip commands on one launcher clip (capability expansion §5.3): the clip's kind decides whether the
 * command takes it and how its effect is verified; keyboard focus is moved to the Clip Launcher before anything is
 * selected, the clip is selected into the launcher cursor, the command is dispatched under launcher focus
 * ({@link LauncherDispatch}) and the effect is read back. Then the previous focus and launcher cursor clip are put back
 * where that can be confirmed; every result after the selection says which were ({@code focusSideEffect},
 * {@code selectionSideEffect}).
 */
public final class ClipCommands {
    /** The kind of a launcher clip, as a command row names it. */
    public enum Kind {
        NOTES,
        AUDIO;

        String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** How a command's effect is read back: the clip's notes, a new clip on the track, a new track, or not at all. */
    enum Verify {
        NOTES,
        CLIPS,
        TRACKS,
        NONE
    }

    /** The native reads clip commands need; every method but {@link #execute} runs on the native document thread. */
    public interface Access {
        void execute(Runnable work);

        /** The kind of the slot's clip, resolved with its track UUID and clip name checked; null for any other. */
        Kind kind(Object slot, String trackId, String expectedName, Runnable check);

        /** The widget holding keyboard focus in the main window; null when there is none. */
        Object focused(Runnable check);

        /**
         * Moves keyboard focus to the main window's Clip Launcher and reads it back: false when it did not get there
         * (no Arrange panel or Clip Launcher shown, or a dialog holding focus).
         */
        boolean focusLauncher(Runnable check);

        /** Moves keyboard focus back to {@code widget} (from {@link #focused}) and reads it back. */
        boolean refocus(Object widget, Runnable check);
    }

    /** One command: the actions invoked in order and, by each clip kind it takes, how its effect is verified. */
    static final class Command {
        final String name;
        final List<String> actions;
        final Map<Kind, Verify> verify;

        Command(String name, List<String> actions, Map<Kind, Verify> verify) {
            this.name = name;
            this.actions = actions;
            this.verify = verify;
        }
    }

    private static final Map<Kind, Verify> CLIPS_OF_ANY = Map.of(Kind.NOTES, Verify.CLIPS, Kind.AUDIO, Verify.CLIPS);
    private static final Map<String, String> STRETCH =
            Map.of("detected", "stretch_to_analyzed_tempo", "project", "stretch_to_project_tempo");
    private static final int GAIN_STEP_DB = 6;
    private static final long RESTORE_NANOS = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(250);

    static Command duplicate(boolean alias) {
        return new Command("duplicate", List.of(alias ? "Duplicate Reference" : "Duplicate"), CLIPS_OF_ANY);
    }

    static Command slice() {
        return new Command("slice", List.of("slice_to_sliced_sampler_track"), Map.of(Kind.AUDIO, Verify.TRACKS));
    }

    static Command stretch(String tempo) {
        return new Command("stretch", List.of(STRETCH.get(tempo)), Map.of(Kind.AUDIO, Verify.NONE));
    }

    /** Bitwig's whole-dB gain commands summing to {@code db}: as many 6 dB steps as fit, then 1 dB steps. */
    static Command gain(int db) {
        String sign = db > 0 ? "increase" : "reduce";
        int size = Math.abs(db);
        List<String> actions = new ArrayList<>();
        for (int i = 0; i < size / GAIN_STEP_DB; i++) actions.add(sign + "_gain_6db");
        for (int i = 0; i < size % GAIN_STEP_DB; i++) actions.add(sign + "_gain_1db");
        return new Command("gain", actions, Map.of(Kind.AUDIO, Verify.NONE));
    }

    private final LauncherState state;
    private final TrackRefs trackRefs;
    private final PortHandle<Access> port;
    private final PortHandle<NativeNotes.Access> notes;
    private final LauncherDispatch dispatch;
    private final Map<String, Command> ops = new HashMap<>();

    public ClipCommands(
            LauncherState state,
            TrackRefs trackRefs,
            Application application,
            PortHandle<Access> port,
            PortHandle<NativeNotes.Access> notes,
            Map<String, MethodSpecs.ClipOp> ops) {
        this.state = state;
        this.trackRefs = trackRefs;
        this.port = port;
        this.notes = notes;
        dispatch = new LauncherDispatch(state, application);
        for (Map.Entry<String, MethodSpecs.ClipOp> op : ops.entrySet()) {
            Map<Kind, Verify> verify = new EnumMap<>(Kind.class);
            for (Map.Entry<String, String> row : op.getValue().verifiedBy.entrySet())
                verify.put(
                        Kind.valueOf(row.getKey().toUpperCase(Locale.ROOT)),
                        Verify.valueOf(row.getValue().toUpperCase(Locale.ROOT)));
            this.ops.put(op.getKey(), new Command(op.getKey(), List.of(op.getValue().action), verify));
        }
    }

    /** The clips.transform op Bitwig runs as its own command; null for an op done through the API. */
    Command op(String name) {
        return ops.get(name);
    }

    /** How {@code command} verifies a clip of {@code kind}; a kind it does not take is refused. */
    static Verify verify(Command command, Kind kind) {
        Verify verify = kind == null ? null : command.verify.get(kind);
        if (verify == null)
            throw Errors.notApplied(
                    Reason.UNSUPPORTED_SCOPE,
                    command.name + " does not take " + (kind == null ? "this kind of" : kind.wire()) + " clip.");
        return verify;
    }

    /** Reads the target's clip kind natively; {@code next} gets the refusal, or null when the command takes it. */
    void checkKind(HostOperation op, LauncherState.Target target, Command command, Consumer<RpcException> next) {
        Access access = port.get(op);
        String id = target.id(), name = target.slot.api.name().get();
        Runnable check = state.nativeCheck(op, target);
        state.scheduler.afterNative(
                op,
                access::execute,
                () -> {
                    try {
                        verify(command, access.kind(target.slot.api, id, name, check));
                        return null;
                    } catch (RpcException refused) {
                        return refused;
                    }
                },
                next);
    }

    /**
     * What a call puts back after each command: the launcher cursor clip and the keyboard focus from before its first
     * selection. One per call; a batch's entries share it, so each entry puts back the user's own state.
     */
    static final class Restore {
        final String track;
        final int scene;
        boolean focusRead;
        Object focus;

        private Restore(String track, int scene) {
            this.track = track;
            this.scene = scene;
        }
    }

    /** Records the launcher cursor clip now, before anything is selected. */
    Restore restore() {
        return new Restore(
                state.clip.exists().get() ? state.clipTrack.channelId().get() : null, state.cursorScene());
    }

    /**
     * Runs {@code command} on the target's clip under {@code op}, which owns the launcher selection; {@code method}
     * names the hold an unobserved effect places on the slot.
     */
    void run(
            HostOperation op,
            String method,
            LauncherState.Target target,
            Command command,
            Restore restore,
            Consumer<JsonObject> done) {
        state.guard(op, target);
        Access access = port.get(op);
        NativeNotes.Access reader = command.verify.containsValue(Verify.NOTES) ? notes.get(op) : null;
        String id = target.id(), name = target.slot.api.name().get();
        Runnable check = state.nativeCheck(op, target);
        state.scheduler.afterNative(
                op,
                access::execute,
                () -> {
                    Verify verify = verify(command, access.kind(target.slot.api, id, name, check));
                    if (verify == Verify.CLIPS && target.at.position() == HostLimits.SCENES - 1)
                        throw Errors.notApplied(
                                Reason.OUTSIDE_WINDOW,
                                "Scene " + target.scene() + " is the last row of its 16-scene window, so the new clip "
                                        + "would land outside the window it is read back in; nothing was sent. Use "
                                        + "clips.copy to an empty slot instead.");
                    List<Note> before =
                            verify == Verify.NOTES ? sorted(reader.read(target.slot.api, id, name, check)) : null;
                    if (!restore.focusRead) {
                        restore.focus = access.focused(check);
                        restore.focusRead = true;
                    }
                    if (!access.focusLauncher(check)) throw LauncherDispatch.notFocused();
                    return new Read(verify, before);
                },
                read -> select(op, method, target, command, read, restore, access, reader, done));
    }

    /**
     * Selects the target into the launcher cursor and runs the command as one step of {@code op}; whatever the step
     * ends with, focus and the previous cursor clip are put back before {@code done} gets the result.
     */
    private void select(
            HostOperation op,
            String method,
            LauncherState.Target target,
            Command command,
            Read read,
            Restore restore,
            Access access,
            NativeNotes.Access reader,
            Consumer<JsonObject> done) {
        HostOperation step = op.child();
        step.result.whenComplete((result, error) -> state.scheduler.schedule(
                op, () -> putBack(op, target, command, access, restore, result, error, done), 0));
        state.selection.handOff(step);
        state.bind(step, target, false, null, state.until(step), (o, t, end, w) -> {
            Effect effect = new Effect(o, method, t, command, read, access, reader, step.result::complete);
            dispatch.dispatch(o, t, access, command.actions, effect::await);
        });
    }

    private void putBack(
            HostOperation op,
            LauncherState.Target target,
            Command command,
            Access access,
            Restore restore,
            JsonObject result,
            Throwable error,
            Consumer<JsonObject> done) {
        state.selection.handOff(op);
        JsonObject out = error == null ? result : Errors.entryResult(error);
        out.addProperty("op", command.name);
        out.add("ref", state.clipRef(target));
        JsonArray actions = new JsonArray();
        command.actions.forEach(actions::add);
        out.add("actions", actions);
        state.scheduler.afterNative(
                op,
                access::execute,
                () -> access.refocus(restore.focus, () -> state.scheduler.check(op)),
                refocused -> {
                    out.addProperty("focusSideEffect", refocused ? "restored" : "launcher");
                    LauncherState.Slot prior = restore.track == null ? null : slotOf(restore.track, restore.scene);
                    if (prior == null) {
                        selected(out, false, done);
                        return;
                    }
                    prior.api.select();
                    state.scheduler.poll(
                            op,
                            state.scheduler.deadlineUntil(op),
                            () -> state.bound(restore.track, prior),
                            restored -> selected(out, restored, done));
                });
    }

    private static void selected(JsonObject out, boolean restored, Consumer<JsonObject> done) {
        out.addProperty("selectionSideEffect", restored ? "restored" : "replaced");
        done.accept(out);
    }

    /** The slot proxy of a track and absolute scene inside the current windows; null when either is outside. */
    private LauncherState.Slot slotOf(String trackId, int scene) {
        int position = scene - state.window.sceneOffset();
        if (position < 0 || position >= HostLimits.SCENES) return null;
        for (int i = 0; i < HostLimits.TRACKS; i++)
            if (state.window.trackMatches(trackId, state.window.at(i))) return state.slots[i][position];
        return null;
    }

    /** The kind's verification and, for a notes check, the clip's notes before the command. */
    private static final class Read {
        final Verify verify;
        final List<Note> notes;

        Read(Verify verify, List<Note> notes) {
            this.verify = verify;
            this.notes = notes;
        }
    }

    /** One dispatched command: what was observed before it, and how its effect is awaited and reported. */
    private final class Effect {
        final HostOperation op;
        final String method;
        final LauncherState.Target target;
        final Command command;
        final Read read;
        final Access access;
        final NativeNotes.Access reader;
        final Consumer<JsonObject> finish;
        final String name;
        final boolean[] occupied;
        final Set<String> tracks;
        Object hold;

        Effect(
                HostOperation op,
                String method,
                LauncherState.Target target,
                Command command,
                Read read,
                Access access,
                NativeNotes.Access reader,
                Consumer<JsonObject> finish) {
            this.op = op;
            this.method = method;
            this.target = target;
            this.command = command;
            this.read = read;
            this.access = access;
            this.reader = reader;
            this.finish = finish;
            name = target.slot.api.name().get();
            occupied = occupied();
            tracks = trackIds();
        }

        /** Runs right after the actions were invoked, on the controller thread. */
        void await() {
            if (read.verify == Verify.NONE) {
                finish.accept(Results.sent());
                return;
            }
            hold = state.hold(target, method);
            // The rest of the deadline is left for putting focus and the cursor clip back.
            long until = state.scheduler.deadlineUntil(op) - RESTORE_NANOS;
            if (read.verify == Verify.NOTES) awaitNotes(until);
            else
                state.scheduler.poll(
                        op,
                        until,
                        () -> read.verify == Verify.CLIPS ? created().size() > 0 : slicedTrack() != null,
                        this::observed,
                        this::unobserved);
        }

        private void awaitNotes(long until) {
            String id = target.id();
            Runnable check = state.nativeCheck(op, target);
            state.scheduler.afterNative(
                    op, access::execute, () -> sorted(reader.read(target.slot.api, id, name, check)), now -> {
                        if (!now.equals(read.notes)) {
                            JsonObject compared = new JsonObject();
                            compared.addProperty("notesBefore", read.notes.size());
                            compared.addProperty("notesAfter", now.size());
                            JsonObject out = applied(Verification.NATIVE_READBACK);
                            out.add("compared", compared);
                            finish.accept(out);
                        } else if (state.scheduler.now() >= until) unobserved();
                        else state.scheduler.schedule(op, () -> awaitNotes(until), Scheduler.POLL_MS);
                    });
        }

        private void observed() {
            JsonObject out = applied(Verification.API_READBACK);
            if (read.verify == Verify.CLIPS) out.add("created", created());
            else out.add("track", slicedTrack());
            finish.accept(out);
        }

        private JsonObject applied(Verification how) {
            state.release(hold);
            return Results.outcome(Outcome.APPLIED, how);
        }

        private void unobserved() {
            finish.accept(state.unresolved(
                    new JsonObject(),
                    Reason.NOT_OBSERVED,
                    "Bitwig's " + command.name + " was sent but its effect was not observed; the slot is held."));
        }

        /** Which slots of the target's track in the scene window hold a clip. */
        private boolean[] occupied() {
            LauncherState.Slot[] slots = state.slots[target.at.track];
            boolean[] out = new boolean[slots.length];
            for (int j = 0; j < slots.length; j++)
                out[j] = slots[j].api.hasContent().get();
            return out;
        }

        /** The refs of the clips named like the source now in slots that were empty before the command. */
        private JsonArray created() {
            JsonArray out = new JsonArray();
            int offset = target.at.offset;
            LauncherState.Slot[] slots = state.slots[target.at.track];
            for (int j = 0; j < slots.length; j++)
                if (!occupied[j]
                        && slots[j].api.hasContent().get()
                        && name.equals(slots[j].api.name().get())) {
                    LauncherTargets.Loc at = new LauncherTargets.Loc(
                            target.at.track,
                            offset + j,
                            offset,
                            target.at.trackRef,
                            state.targets.sceneRef(offset + j, offset));
                    out.add(state.targets.clipRef(at, name));
                }
            return out;
        }

        /** The ref of a track new since the command right after the source track, where Bitwig puts the slice. */
        private JsonObject slicedTrack() {
            int slot = target.at.track + 1;
            if (slot >= HostLimits.TRACKS) return null;
            Track track = state.window.at(slot);
            return track.exists().get() && !tracks.contains(track.channelId().get()) ? trackRefs.ref(slot) : null;
        }
    }

    /** The channel ids of the window's tracks. */
    private Set<String> trackIds() {
        Set<String> out = new HashSet<>();
        for (int i = 0; i < HostLimits.TRACKS; i++) {
            Track track = state.window.at(i);
            if (track.exists().get()) out.add(track.channelId().get());
        }
        return out;
    }

    private static List<Note> sorted(NativeNotes.Observed clip) {
        List<Note> out = new ArrayList<>(clip.notes);
        out.sort(Note.ORDER);
        return out;
    }
}
