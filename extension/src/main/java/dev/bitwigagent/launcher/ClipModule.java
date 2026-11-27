package dev.bitwigagent.launcher;

import com.bitwig.extension.controller.api.*;
import com.google.gson.*;
import dev.bitwigagent.arranger.ArrangerModule;
import dev.bitwigagent.core.Batch;
import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.HostLimits;
import dev.bitwigagent.core.HostModule;
import dev.bitwigagent.core.HostOperation;
import dev.bitwigagent.core.Outcome;
import dev.bitwigagent.core.Params;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.core.Results;
import dev.bitwigagent.core.RpcException;
import dev.bitwigagent.core.SafetyObservation;
import dev.bitwigagent.core.Scheduler;
import dev.bitwigagent.core.ValueMatch;
import dev.bitwigagent.core.Verification;
import dev.bitwigagent.host.EnumOptions;
import dev.bitwigagent.host.TrackRefs;
import dev.bitwigagent.host.TrackWindow;
import dev.bitwigagent.host.TransportSafety;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/** Whole-clip methods: launcher clips by slot ref, arranger clips by start-and-name ref, every outcome read back. */
public final class ClipModule implements HostModule {
    static final Set<String> METHODS = Set.of(
            "clips.get",
            "clips.insert",
            "clips.import",
            "clips.set",
            "clips.delete",
            "clips.copy",
            "clips.move",
            "clips.launch",
            "clips.transpose",
            "clips.quantize",
            "clips.transform");
    static final List<String> NEXT_ACTION = LauncherSettingsModule.WRITE_ORDER;
    static final List<String> LAUNCH_FIELDS =
            List.of("launchQuantization", "launchMode", "useLoopStartAsQuantizationReference");
    private static final Map<String, ValueMatch> EXPECT = Map.of(
            "name", ValueMatch.EXACT,
            "lengthBeats", ValueMatch.EXACT,
            "loop", ValueMatch.EXACT,
            "nextAction", ValueMatch.EXACT,
            "nextActionEnabled", ValueMatch.EXACT,
            "nextActionLinkToLoop", ValueMatch.EXACT,
            "nextActionLoopCount", ValueMatch.EXACT,
            "nextActionDelayBeats", ValueMatch.raw(1e-8));
    private static final Set<String> LOOP_KEYS = Set.of("loopLengthBeats", "loopStartBeats", "fields");

    private final LauncherState state;
    private final Scheduler scheduler;
    private final TrackWindow window;
    private final TrackRefs trackRefs;
    private final TransportSafety transportSafety;
    private final NoteModule notes;
    private final LauncherSettingsModule settings;
    private final ArrangerModule arranger;

    public ClipModule(
            LauncherState state,
            TrackRefs trackRefs,
            NoteModule notes,
            LauncherSettingsModule settings,
            ArrangerModule arranger) {
        this.state = state;
        this.scheduler = state.scheduler;
        this.window = state.window;
        this.trackRefs = trackRefs;
        this.transportSafety = state.transportSafety;
        this.notes = notes;
        this.settings = settings;
        this.arranger = arranger;
    }

    @Override
    public Set<String> methods() {
        return METHODS;
    }

    // ---- request rules the schema cannot say ----

    @Override
    public void validate(String method, JsonObject p) {
        switch (method) {
            case "clips.insert":
                validateInsert(p);
                break;
            case "clips.set":
                validateSet(p);
                break;
            case "clips.move":
                if (!isArranger(p.getAsJsonObject("target")))
                    throw Errors.notApplied(
                            Reason.UNSUPPORTED_SCOPE, "clips.move takes an arranger clip in this version");
                break;
            case "clips.transpose":
                if (p.get("semitones").getAsInt() == 0) throw Errors.invalid("semitones must be nonzero");
                break;
            default:
        }
    }

    private static boolean isSlot(JsonObject destination) {
        return destination.has("kind");
    }

    private static boolean isArranger(JsonObject clip) {
        return clip.has("lane");
    }

    private static void validateInsert(JsonObject p) {
        boolean slot = isSlot(p.getAsJsonObject("to"));
        if (slot ? !p.has("loop") || p.has("lengthBeats") : !p.has("lengthBeats") || p.has("loop"))
            throw Errors.invalid(
                    slot
                            ? "a launcher slot takes loop:{lengthBeats}, not lengthBeats"
                            : "an arranger position takes lengthBeats, not loop");
        if (!p.has("notes")) {
            if (p.has("channel") || p.has("detail"))
                throw Errors.invalid("channel and detail are only valid together with notes");
            return;
        }
        if (!slot) {
            if (p.has("detail")) throw Errors.invalid("detail applies to launcher slots only");
            ArrangerModule.notes(p.getAsJsonArray("notes"), p.get("lengthBeats").getAsDouble());
            return;
        }
        noteParts(p);
    }

    /** The windows a launcher insert writes its notes in; every rule the notes break is refused together. */
    private static List<NoteGrid.Part> noteParts(JsonObject p) {
        return NoteGrid.plan(
                p,
                NoteGrid.setEdits(p.getAsJsonArray("notes")),
                "notes",
                p.getAsJsonObject("loop").get("lengthBeats").getAsBigDecimal());
    }

    private static void validateSet(JsonObject p) {
        boolean arranger = isArranger(p.getAsJsonObject("target"));
        List<String> launcherOnly = new ArrayList<>(List.of("color", "loop"));
        launcherOnly.addAll(NEXT_ACTION);
        launcherOnly.addAll(LAUNCH_FIELDS);
        for (String key : arranger ? launcherOnly : List.of("lengthBeats")) {
            if (p.has(key))
                throw Errors.notApplied(
                        Reason.UNSUPPORTED_SCOPE,
                        key + " cannot be set on " + (arranger ? "an arranger" : "a launcher") + " clip");
            if (p.has("expect") && p.getAsJsonObject("expect").has(key))
                throw Errors.notApplied(
                        Reason.UNSUPPORTED_SCOPE,
                        "expect." + key + " cannot be compared on " + (arranger ? "an arranger" : "a launcher")
                                + " clip");
        }
        if (p.keySet().stream()
                .allMatch(key -> Set.of("target", "expect", "timeoutMs").contains(key)))
            throw Errors.invalid("supply at least one clip setting");
        if (p.has("expect") && p.getAsJsonObject("expect").size() == 0)
            throw Errors.invalid("expect names at least one field");
    }

    // ---- dispatch ----

    @Override
    public void dispatch(HostOperation op, String method, JsonObject p) {
        scheduler.check(op);
        switch (method) {
            case "clips.get":
                get(op, p.getAsJsonObject("target"));
                break;
            case "clips.insert":
                if (isSlot(p.getAsJsonObject("to"))) insertLauncher(op, p);
                else insertArranger(op, p);
                break;
            case "clips.import":
                importFile(op, p);
                break;
            case "clips.set":
                if (isArranger(p.getAsJsonObject("target"))) setArranger(op, p);
                else setLauncher(op, p);
                break;
            case "clips.delete":
                if (isArranger(p.getAsJsonObject("target")))
                    arranger.edit(op, p.getAsJsonObject("target"), "delete", new JsonObject(), op.result::complete);
                else deleteLauncher(op, p.getAsJsonObject("target"));
                break;
            case "clips.copy":
                copy(op, p.getAsJsonObject("from"), p.getAsJsonObject("to"));
                break;
            case "clips.move":
                move(op, p);
                break;
            case "clips.launch":
                launch(op, p.getAsJsonObject("target"));
                break;
            case "clips.transform":
                transform(op, p.getAsJsonArray("targets"));
                break;
            default:
                gridOp(op, method.substring("clips.".length()), p);
        }
    }

    // ---- get ----

    private void get(HostOperation op, JsonObject ref) {
        state.targets.enterClip(op, ref, true, at -> {
            LauncherState.Target target = state.target(at);
            LauncherState.ready(target.slot);
            settings.run(op, target, new JsonObject(), false, result -> {
                JsonObject out = new JsonObject();
                out.add("ref", state.clipRef(target));
                JsonObject loop = new JsonObject();
                loop.add("lengthBeats", result.get("loopLengthBeats"));
                loop.add("startBeats", result.get("loopStartBeats"));
                out.add("loop", loop);
                for (Map.Entry<String, JsonElement> e : result.entrySet())
                    if (!LOOP_KEYS.contains(e.getKey()) && !e.getKey().equals("name"))
                        out.add(e.getKey(), e.getValue());
                op.result.complete(out);
            });
        });
    }

    // ---- insert and import ----

    private void insertArranger(HostOperation op, JsonObject p) {
        JsonObject to = p.getAsJsonObject("to");
        arranger.create(
                op,
                to.getAsJsonObject("track"),
                to.get("startBeats").getAsDouble(),
                p.get("lengthBeats").getAsDouble(),
                p.get("name").getAsString(),
                p.has("notes") ? p.getAsJsonArray("notes") : null,
                p.has("channel") ? p.get("channel").getAsInt() : 0,
                op.result::complete);
    }

    private void insertLauncher(HostOperation op, JsonObject p) {
        state.targets.enterSlot(op, p.getAsJsonObject("to"), false, at -> {
            LauncherState.Target target = state.target(at);
            LauncherState.ready(target.slot);
            state.acquire(op);
            state.requireResolved(target.id(), target.scene());
            if (target.slot.api.hasContent().get())
                throw Errors.notApplied(Reason.TARGET_EXISTS, "Insert only accepts an observed empty slot");
            state.guard(op, target);
            NativeNotes.Access access = notes.natives.access(op);
            if (access != null) {
                insertNative(op, target, p, access);
                return;
            }
            op.markDispatched();
            state.beginMutation(target, "clips.insert");
            int length = p.getAsJsonObject("loop").get("lengthBeats").getAsInt();
            target.slot.api.createEmptyClip(length);
            long end = state.until(op);
            state.awaitGuarded(
                    op,
                    () -> state.guard(op, target),
                    end,
                    () -> target.slot.contentVersion > state.contentBefore()
                            && target.slot.observedContent
                            && target.slot.api.hasContent().get(),
                    () -> state.bind(op, target, true, null, end, (o, t, e, w) -> nameCreated(o, t, e, p, length)),
                    () -> {
                        JsonObject out = new JsonObject();
                        if (p.has("notes")) out.addProperty("noteWrite", "not_attempted");
                        out.add("slot", state.slotJson(target));
                        op.result.complete(state.unresolved(
                                out, Reason.NOT_OBSERVED, "The new clip's content was not observed in the slot."));
                    });
        });
    }

    /**
     * One native task creates the named clip with its loop length and playback stop and writes the notes, as one Undo
     * step and without selecting anything; the clip is then bound and read back like a public insert.
     */
    private void insertNative(HostOperation op, LauncherState.Target target, JsonObject p, NativeNotes.Access access) {
        int length = p.getAsJsonObject("loop").get("lengthBeats").getAsInt();
        String id = target.id(), name = p.get("name").getAsString();
        NoteModule.Planned planned = new NoteModule.Planned(p.has("notes") ? noteParts(p) : List.of());
        List<NativeNotes.Cell> cells = planned.cells();
        Runnable check = state.nativeCheck(op, target);
        access.execute(() -> {
            try {
                access.create(target.slot.api, id, name, length, cells, check, () -> {
                    check.run();
                    op.markDispatched();
                    state.beginMutation(target, "clips.insert");
                });
                scheduler.schedule(op, () -> awaitNativeCreate(op, target, p, length, planned), 0);
            } catch (RuntimeException | Error failure) {
                op.fail(failure);
            }
        });
    }

    private void awaitNativeCreate(
            HostOperation op, LauncherState.Target target, JsonObject p, int length, NoteModule.Planned planned) {
        long end = state.until(op);
        // The notes are already written: a readback failure is unknown, never "nothing changed".
        Consumer<HostOperation> readNotes = child -> {
            child.markDispatched();
            notes.readBack(child, target, planned, NoteGrid.full(p));
        };
        state.awaitGuarded(
                op,
                () -> state.guard(op, target),
                end,
                () -> target.slot.contentVersion > state.contentBefore()
                        && target.slot.observedContent
                        && target.slot.api.hasContent().get(),
                () -> state.bind(
                        op,
                        target,
                        true,
                        null,
                        end,
                        (o, t, e, w) -> awaitCreated(o, t, e, p, length, NativeNotes.ONE_STEP, readNotes)),
                () -> {
                    JsonObject out = new JsonObject();
                    if (p.has("notes")) out.addProperty("noteWrite", "not_read_back");
                    out.add("slot", state.slotJson(target));
                    out.addProperty("undo", NativeNotes.ONE_STEP);
                    op.result.complete(state.unresolved(
                            out, Reason.NOT_OBSERVED, "The new clip's content was not observed in the slot."));
                });
    }

    private void nameCreated(HostOperation op, LauncherState.Target target, long end, JsonObject p, int length) {
        state.cursorGuard(op, target);
        String name = p.get("name").getAsString();
        state.clip.setName(name);
        // Native createEmptyClip can leave playback stop at its four-beat default.
        state.clip.getPlayStop().set(length);
        awaitCreated(
                op,
                target,
                end,
                p,
                length,
                NativeNotes.PER_STEP,
                child -> notes.write(child, target, noteParts(p), NoteGrid.full(p), state.until(op)));
    }

    /** Waits for the new clip's name, loop length and playback stop, then has {@code writeNotes} handle the notes. */
    private void awaitCreated(
            HostOperation op,
            LauncherState.Target target,
            long end,
            JsonObject p,
            int length,
            String undo,
            Consumer<HostOperation> writeNotes) {
        state.cursorGuard(op, target);
        String name = p.get("name").getAsString();
        BooleanSupplier named = () -> target.slot.api.name().get().equals(name)
                && state.clip.getLoopLength().get() == length
                && state.clip.getPlayStop().get() == length;
        scheduler.schedule(
                op,
                () -> scheduler.poll(
                        op,
                        end,
                        () -> {
                            state.cursorGuard(op, target);
                            return named.getAsBoolean();
                        },
                        matches -> finishInsert(op, target, p, matches, undo, writeNotes)),
                Scheduler.POLL_MS);
    }

    private void finishInsert(
            HostOperation op,
            LauncherState.Target target,
            JsonObject p,
            boolean matches,
            String undo,
            Consumer<HostOperation> writeNotes) {
        JsonObject out = new JsonObject();
        out.add("ref", state.clipRef(target));
        out.add("slot", state.slotJson(target));
        state.addClipMetadata(out);
        out.addProperty("undo", undo);
        if (matches) {
            Verification.API_READBACK.put(out);
            state.finishMutation();
        }
        if (matches && p.has("notes")) {
            // Clip creation is complete and released. The note write is an ordinary notes.edit child sharing the
            // deadline; its failure is reported inside noteWrite and never discards the observed create.
            HostOperation child = new HostOperation(op.deadline);
            state.selection.handOff(child);
            child.result.whenComplete((written, error) -> {
                if (error == null) {
                    out.addProperty(
                            "outcome",
                            Outcome.APPLIED.worst(Results.outcomeOf(written)).wire());
                    out.add("noteWrite", written);
                } else {
                    JsonObject failure = Errors.entryResult(error);
                    out.addProperty(
                            "outcome",
                            Outcome.APPLIED.worst(Results.outcomeOf(failure)).wire());
                    out.add("noteWrite", failure);
                }
                op.result.complete(out);
            });
            try {
                writeNotes.accept(child);
            } catch (RuntimeException failure) {
                child.fail(failure);
            }
            return;
        }
        if (matches) {
            out.addProperty("outcome", Outcome.APPLIED.wire());
            op.result.complete(out);
            return;
        }
        if (p.has("notes"))
            out.addProperty("noteWrite", undo.equals(NativeNotes.ONE_STEP) ? "not_read_back" : "not_attempted");
        op.result.complete(state.unresolved(
                out, Reason.READBACK_MISMATCH, "The new clip's name or length differs from the request."));
    }

    private void importFile(HostOperation op, JsonObject p) {
        Path path = Params.absoluteFilePath(p, "path");
        if (!Files.isRegularFile(path) || !Files.isReadable(path))
            throw Errors.notApplied(Reason.INVALID_FILE, "Import requires a readable regular local file.");
        MidiImportPreflight.validate(path);
        state.targets.enterSlot(op, p.getAsJsonObject("to"), false, at -> {
            LauncherState.Target target = state.target(at);
            LauncherState.ready(target.slot);
            state.acquire(op);
            state.requireResolved(target.id(), target.scene());
            if (target.slot.api.hasContent().get())
                throw Errors.notApplied(Reason.TARGET_EXISTS, "Import only accepts an observed empty slot.");
            InsertionPoint insertion = target.slot.api.replaceInsertionPoint();
            // Resolve everything first, then recheck the target, safety and deadline at the mutation boundary.
            state.guard(op, target);
            transportSafety.requirePlaybackSafe();
            if (target.slot.api.hasContent().get())
                throw Errors.notApplied(Reason.TARGET_EXISTS, "Import only accepts an observed empty slot.");
            long before = target.slot.contentVersion;
            op.markDispatched();
            state.beginMutation(target, "clips.import");
            insertion.insertFile(path.toString());
            state.awaitGuarded(
                    op,
                    () -> state.guard(op, target),
                    state.until(op),
                    () -> target.slot.contentVersion > before
                            && target.slot.observedContent
                            && target.slot.api.hasContent().get(),
                    () -> {
                        JsonObject out = LauncherState.written(Outcome.APPLIED);
                        out.add("ref", state.clipRef(target));
                        Verification.API_READBACK.put(out);
                        out.addProperty("requestedFileVerified", false);
                        out.add("slot", state.slotJson(target));
                        state.finishMutation();
                        op.result.complete(out);
                    },
                    () -> {
                        throw Errors.unknown(
                                Reason.NOT_OBSERVED,
                                "File insertion was dispatched, but a fresh slot content transition was not observed; writes on this slot are held until acked after inspection.");
                    });
        });
    }

    // ---- launch ----

    private void launch(HostOperation op, JsonObject ref) {
        state.targets.enterClip(op, ref, false, at -> {
            LauncherState.Target target = state.target(at);
            LauncherState.ready(target.slot);
            LauncherState.requireContent(target.slot);
            state.guard(op, target);
            transportSafety.requirePlaybackSafe();
            op.markDispatched();
            target.slot.api.launch();
            state.awaitPlayback(op, target, target.slot, state.clipRef(target));
        });
    }

    // ---- delete and copy ----

    private void deleteLauncher(HostOperation op, JsonObject ref) {
        state.targets.enterClip(op, ref, false, at -> {
            LauncherState.Target target = state.target(at);
            LauncherState.ready(target.slot);
            state.acquire(op);
            state.requireResolved(target.id(), target.scene());
            SafetyObservation safety = transportSafety.stopped(op);
            ClipLauncherSlot api = target.slot.api;
            if (api.isPlaying().get()
                    || api.isPlaybackQueued().get()
                    || api.isRecording().get())
                throw Errors.notApplied(
                        Reason.UNSAFE_STATE, "Delete requires the clip to be neither playing, queued nor recording.");
            state.guard(op, target);
            transportSafety.guardStopped(safety);
            op.markDispatched();
            state.beginMutation(target, "clips.delete");
            long before = state.contentBefore();
            api.deleteObject();
            state.awaitGuarded(
                    op,
                    () -> state.guard(op, target),
                    state.until(op),
                    () -> target.slot.contentVersion > before
                            && !target.slot.observedContent
                            && !api.hasContent().get(),
                    () -> {
                        JsonObject out = LauncherState.written(Outcome.APPLIED);
                        Verification.API_READBACK.put(out);
                        out.add("slot", state.slotJson(target));
                        state.finishMutation();
                        op.result.complete(out);
                    },
                    () -> {
                        throw state.unobserved(Reason.NOT_OBSERVED, "Clip delete");
                    });
        });
    }

    private void copy(HostOperation op, JsonObject from, JsonObject to) {
        int track = trackRefs.resolve(from.getAsJsonObject("track"), false);
        String id = from.getAsJsonObject("track").get("id").getAsString();
        if (isSlot(to)) {
            if (!to.getAsJsonObject("track").get("id").getAsString().equals(id))
                throw Errors.notApplied(
                        Reason.UNSUPPORTED_SCOPE,
                        "clips.copy to a slot of another track is not supported in this version");
            int source = state.targets.requireScene(from), target = state.targets.requireScene(to);
            if (Math.abs(source - target) >= HostLimits.SCENES)
                throw Errors.notApplied(
                        Reason.UNSUPPORTED_SCOPE,
                        "The source and target slot must be within " + HostLimits.SCENES
                                + " scenes of each other: copy via an empty slot within " + HostLimits.SCENES
                                + " scenes, or notes.read + clips.insert {notes} for a MIDI clip.");
            int offset = Math.min(Math.min(source, target), Math.max(0, window.sceneCount() - HostLimits.SCENES));
            state.targets.lease.enter(op, offset, false, false, () -> {
                LauncherTargets.Loc from1 = state.targets.clip(from, offset), to1 = state.targets.slot(to, offset);
                copyClip(op, state.target(from1), state.target(to1));
            });
            return;
        }
        if (trackRefs.resolve(to.getAsJsonObject("track"), false) != track)
            throw Errors.notApplied(
                    Reason.UNSUPPORTED_SCOPE,
                    "clips.copy onto another track's timeline is not supported in this version");
        state.targets.enterClip(op, from, false, at -> {
            LauncherState.Target source = state.target(at);
            LauncherState.ready(source.slot);
            LauncherState.requireContent(source.slot);
            state.requireResolved(source.id(), source.scene());
            arranger.copyFromLauncher(op, source, to.get("startBeats").getAsDouble(), op.result::complete);
        });
    }

    private void copyClip(HostOperation op, LauncherState.Target source, LauncherState.Target to) {
        LauncherState.ready(source.slot);
        LauncherState.ready(to.slot);
        state.acquire(op);
        state.requireResolved(source.id(), source.scene());
        LauncherState.requireContent(source.slot);
        if (to.scene() == source.scene())
            throw Errors.notApplied(Reason.TARGET_EXISTS, "The target slot is the source slot");
        state.requireResolved(to.id(), to.scene());
        if (to.slot.api.hasContent().get())
            throw Errors.notApplied(Reason.TARGET_EXISTS, "clips.copy only accepts an observed empty target slot");
        String name = source.slot.api.name().get();
        state.guard(op, source);
        op.markDispatched();
        state.beginMutation(to, "clips.copy");
        long before = state.contentBefore();
        // copySlotsOrScenes copies the supplied slots to this insertion point; copyFrom's direction is ambiguous in the
        // javadoc.
        to.slot.api.replaceInsertionPoint().copySlotsOrScenes(source.slot.api);
        BooleanSupplier present = () -> to.slot.contentVersion > before
                && to.slot.observedContent
                && to.slot.api.hasContent().get();
        Runnable ok = () -> {
            String copied = to.slot.api.name().get();
            JsonObject out = LauncherState.written(Outcome.APPLIED);
            Verification.API_READBACK.put(out);
            out.add("ref", state.clipRef(to));
            if (!copied.equals(name)) {
                JsonObject changed = new JsonObject();
                changed.addProperty("from", name);
                changed.addProperty("to", copied);
                out.add("nameChanged", changed);
            }
            out.add("slot", state.slotJson(to));
            state.finishMutation();
            op.result.complete(out);
        };
        // Bitwig may rename the copy: wait for the equal name, but a verified content transition alone is still
        // observed.
        state.awaitGuarded(
                op,
                () -> state.guard(op, source),
                state.until(op),
                () -> present.getAsBoolean() && to.slot.api.name().get().equals(name),
                ok,
                () -> {
                    if (present.getAsBoolean()) ok.run();
                    else throw state.unobserved(Reason.NOT_OBSERVED, "Clip copy");
                });
    }

    private void move(HostOperation op, JsonObject p) {
        JsonObject args = new JsonObject();
        args.add("startBeats", p.getAsJsonObject("to").get("startBeats"));
        arranger.edit(op, p.getAsJsonObject("target"), "move", args, op.result::complete);
    }

    // ---- set ----

    private void setArranger(HostOperation op, JsonObject p) {
        JsonObject ref = p.getAsJsonObject("target");
        JsonObject expect = p.has("expect") ? p.getAsJsonObject("expect") : null;
        JsonObject fields = new JsonObject();
        Deque<String> keys = new ArrayDeque<>();
        for (String key : List.of("lengthBeats", "name")) if (p.has(key)) keys.add(key);
        List<String> order = new ArrayList<>(keys);
        JsonObject[] last = new JsonObject[1];
        Runnable[] next = new Runnable[1];
        next[0] = () -> {
            String key = keys.poll();
            if (key == null) {
                finishSet(op, fields, order, last[0].get("ref"), null, nativeEvidence(last[0]));
                return;
            }
            JsonObject args = new JsonObject();
            args.add(key, p.get(key));
            if (expect != null && fields.size() == 0) args.add("expect", expect);
            arranger.edit(op, ref, key.equals("name") ? "rename" : "resize", args, result -> {
                last[0] = result;
                JsonObject clip = result.getAsJsonObject("clip");
                fields.add(
                        key,
                        Results.field(
                                p.get(key),
                                clip == null ? null : clip.get(key.equals("name") ? "name" : "lengthBeats"),
                                Results.outcomeOf(result),
                                null));
                if (Results.outcomeOf(result).succeeded()) next[0].run();
                else finishSet(op, fields, order, result.get("ref"), null, nativeEvidence(result));
            });
        };
        next[0].run();
    }

    private void setLauncher(HostOperation op, JsonObject p) {
        state.targets.enterClip(op, p.getAsJsonObject("target"), false, at -> {
            LauncherState.Target target = state.target(at);
            LauncherState.ready(target.slot);
            LauncherState.requireContent(target.slot);
            boolean nextAction = NEXT_ACTION.stream().anyMatch(p::has),
                    launch = LAUNCH_FIELDS.stream().anyMatch(p::has);
            boolean cursor = launch || p.has("name") || p.has("color") || p.has("loop");
            state.requireResolved(target.id(), target.scene());
            if (nextAction) state.lock.requireNoCursorOperation();
            if (cursor) state.acquire(op);
            // Options are the host's own list; reject before the selection moves.
            for (String key : List.of("launchQuantization", "launchMode"))
                if (p.has(key))
                    EnumOptions.require(
                            key,
                            key.equals("launchMode") ? state.clip.launchMode() : state.clip.launchQuantization(),
                            p.get(key).getAsString());
            SafetyObservation safety = nextAction || launch ? transportSafety.stopped(op) : null;
            checkExpect(op, target, p.has("expect") ? p.getAsJsonObject("expect") : null, () -> {
                JsonObject fields = new JsonObject(), changes = new JsonObject();
                JsonObject[] settingsEvidence = new JsonObject[1];
                for (String key : NEXT_ACTION) if (p.has(key)) changes.add(key, p.get(key));
                List<String> order = setOrder(p);
                Runnable finish = () -> finishSet(
                        op,
                        fields,
                        order,
                        state.clipRef(target),
                        launch ? launchOptions() : null,
                        cursor ? apiEvidence() : settingsEvidence[0]);
                Runnable runCursor = () -> {
                    if (!cursor) finish.run();
                    else
                        state.bind(
                                op,
                                target,
                                false,
                                null,
                                state.until(op),
                                (o, t, end, w) -> cursorSteps(o, t, p, safety, fields, finish));
                };
                if (!nextAction) {
                    runCursor.run();
                    return;
                }
                settings.run(op, target, changes, true, result -> {
                    JsonObject done = result.getAsJsonObject("fields");
                    settingsEvidence[0] = nativeEvidence(result);
                    merge(fields, done);
                    if (succeeded(done)) runCursor.run();
                    else finish.run();
                });
            });
        });
    }

    private static List<String> setOrder(JsonObject p) {
        List<String> order = new ArrayList<>();
        for (String key : NEXT_ACTION) if (p.has(key)) order.add(key);
        for (String key : LAUNCH_FIELDS) if (p.has(key)) order.add(key);
        for (String key : List.of("name", "color", "loop")) if (p.has(key)) order.add(key);
        return order;
    }

    /** The comparison every requested expect field must pass before anything is written. */
    private void checkExpect(HostOperation op, LauncherState.Target target, JsonObject expect, Runnable proceed) {
        if (expect == null) {
            proceed.run();
            return;
        }
        JsonObject observed = new JsonObject();
        observed.addProperty("name", target.slot.api.name().get());
        if (expect.keySet().stream().allMatch(key -> key.equals("name"))) {
            ValueMatch.requireExpected(expect, observed, EXPECT);
            proceed.run();
            return;
        }
        settings.run(op, target, new JsonObject(), false, result -> {
            JsonObject loop = new JsonObject();
            loop.add("lengthBeats", result.get("loopLengthBeats"));
            observed.add("loop", loop);
            for (String key : NEXT_ACTION) observed.add(key, result.get(key));
            ValueMatch.requireExpected(expect, observed, EXPECT);
            proceed.run();
        });
    }

    private static void merge(JsonObject into, JsonObject group) {
        for (Map.Entry<String, JsonElement> e : group.entrySet()) into.add(e.getKey(), e.getValue());
    }

    private static JsonObject nativeEvidence(JsonObject nativeResult) {
        JsonObject evidence = new JsonObject();
        Verification.NATIVE_READBACK.put(evidence);
        evidence.add("nativeVersion", nativeResult.get("nativeVersion"));
        evidence.add("nativeJarSha256", nativeResult.get("nativeJarSha256"));
        return evidence;
    }

    private static JsonObject apiEvidence() {
        JsonObject evidence = new JsonObject();
        Verification.API_READBACK.put(evidence);
        return evidence;
    }

    private static boolean succeeded(JsonObject group) {
        return Results.worst(group.asMap().values()).succeeded();
    }

    private JsonObject launchOptions() {
        JsonObject options = new JsonObject();
        options.add("launchQuantization", EnumOptions.json(state.clip.launchQuantization()));
        options.add("launchMode", EnumOptions.json(state.clip.launchMode()));
        return options;
    }

    /** The result of a set: the worst outcome of every field, the fields the run never reached, the clip's new ref. */
    private void finishSet(
            HostOperation op,
            JsonObject fields,
            List<String> order,
            JsonElement ref,
            JsonObject options,
            JsonObject evidence) {
        for (String key : order)
            if (!fields.has(key)) {
                JsonObject skipped = Results.outcome(Outcome.NOT_APPLIED);
                skipped.addProperty("reason", Reason.NOT_REACHED.wire());
                fields.add(key, skipped);
            }
        Outcome outcome = Results.worst(fields.asMap().values());
        JsonObject out = Results.outcome(outcome);
        if (!outcome.succeeded())
            for (Map.Entry<String, JsonElement> field : fields.entrySet()) {
                JsonElement reason = field.getValue().getAsJsonObject().get("reason");
                if (reason != null && !Reason.NOT_REACHED.wire().equals(reason.getAsString())) {
                    out.add("reason", reason);
                    break;
                }
            }
        if (outcome == Outcome.APPLIED) merge(out, evidence);
        out.add("ref", ref);
        out.add("fields", fields);
        if (options != null) out.add("options", options);
        op.result.complete(out);
    }

    /** Launch fields, then name, color and loop on the bound cursor clip; the first that does not succeed stops the rest. */
    private void cursorSteps(
            HostOperation op,
            LauncherState.Target target,
            JsonObject p,
            SafetyObservation safety,
            JsonObject fields,
            Runnable finish) {
        state.cursorGuard(op, target);
        Deque<Consumer<Consumer<JsonObject>>> queue = new ArrayDeque<>();
        if (LAUNCH_FIELDS.stream().anyMatch(p::has)) queue.add(done -> launchFields(op, target, p, safety, done));
        if (p.has("name"))
            queue.add(done -> cursorField(
                    op,
                    target,
                    "name",
                    p.get("name"),
                    () -> target.slot.api.name().get().equals(p.get("name").getAsString()),
                    () -> state.clip.setName(p.get("name").getAsString()),
                    () -> new JsonPrimitive(target.slot.api.name().get()),
                    done));
        if (p.has("color")) {
            float[] want = rgb(p.get("color").getAsString());
            queue.add(done -> cursorField(
                    op,
                    target,
                    "color",
                    p.get("color"),
                    () -> colorIs(want),
                    () -> state.clip.color().set(want[0], want[1], want[2]),
                    () -> new JsonPrimitive(hex()),
                    done));
        }
        if (p.has("loop"))
            queue.add(done -> resize(
                    op, target, p.getAsJsonObject("loop").get("lengthBeats").getAsDouble(), done));
        drain(queue, fields, finish);
    }

    private void drain(Deque<Consumer<Consumer<JsonObject>>> queue, JsonObject fields, Runnable finish) {
        Consumer<Consumer<JsonObject>> next = queue.poll();
        if (next == null) {
            finish.run();
            return;
        }
        next.accept(group -> {
            merge(fields, group);
            if (succeeded(group)) drain(queue, fields, finish);
            else finish.run();
        });
    }

    private JsonElement launchValue(String key) {
        switch (key) {
            case "launchQuantization":
                return new JsonPrimitive(state.clip.launchQuantization().get());
            case "launchMode":
                return new JsonPrimitive(state.clip.launchMode().get());
            default:
                return new JsonPrimitive(
                        state.clip.useLoopStartAsQuantizationReference().get());
        }
    }

    private void launchFields(
            HostOperation op,
            LauncherState.Target target,
            JsonObject p,
            SafetyObservation safety,
            Consumer<JsonObject> done) {
        state.cursorGuard(op, target);
        transportSafety.guardStopped(safety);
        // Only differing fields are written; a written field counts only once its own observer fires after the write.
        long[] before = state.launchSequence.clone();
        Set<String> written = new HashSet<>();
        for (String key : LAUNCH_FIELDS) {
            if (!p.has(key) || launchValue(key).equals(p.get(key))) continue;
            op.markDispatched();
            written.add(key);
            JsonElement value = p.get(key);
            if (key.equals("launchQuantization"))
                state.clip.launchQuantization().set(value.getAsString());
            else if (key.equals("launchMode")) state.clip.launchMode().set(value.getAsString());
            else state.clip.useLoopStartAsQuantizationReference().set(value.getAsBoolean());
        }
        BooleanSupplier matches = () -> {
            for (int i = 0; i < LAUNCH_FIELDS.size(); i++) {
                String key = LAUNCH_FIELDS.get(i);
                if (!p.has(key)) continue;
                if (!launchValue(key).equals(p.get(key))
                        || written.contains(key) && state.launchSequence[i] <= before[i]) return false;
            }
            return true;
        };
        Runnable report = () -> {
            JsonObject fields = new JsonObject();
            for (int i = 0; i < LAUNCH_FIELDS.size(); i++) {
                String key = LAUNCH_FIELDS.get(i);
                if (!p.has(key)) continue;
                boolean match = launchValue(key).equals(p.get(key)), fresh = state.launchSequence[i] > before[i];
                Outcome outcome = !match
                        ? Outcome.UNKNOWN
                        : !written.contains(key) ? Outcome.ALREADY_MATCHED : fresh ? Outcome.APPLIED : Outcome.UNKNOWN;
                Reason reason =
                        outcome != Outcome.UNKNOWN ? null : match ? Reason.NOT_OBSERVED : Reason.READBACK_MISMATCH;
                fields.add(key, Results.field(p.get(key), launchValue(key), outcome, reason));
            }
            done.accept(fields);
        };
        state.awaitGuarded(op, () -> state.cursorGuard(op, target), state.until(op), matches, report, report);
    }

    /** Dispatch an idempotent write unless the readback already matches; unobserved after the window is unknown, no hold. */
    private void cursorField(
            HostOperation op,
            LauncherState.Target target,
            String key,
            JsonElement requested,
            BooleanSupplier matches,
            Runnable write,
            java.util.function.Supplier<JsonElement> observed,
            Consumer<JsonObject> done) {
        state.cursorGuard(op, target);
        if (matches.getAsBoolean()) {
            done.accept(single(key, Results.field(requested, observed.get(), Outcome.ALREADY_MATCHED, null)));
            return;
        }
        op.markDispatched();
        write.run();
        state.awaitGuarded(
                op,
                () -> state.cursorGuard(op, target),
                state.until(op),
                matches,
                () -> done.accept(single(key, Results.field(requested, observed.get(), Outcome.APPLIED, null))),
                () -> done.accept(single(
                        key, Results.field(requested, observed.get(), Outcome.UNKNOWN, Reason.READBACK_MISMATCH))));
    }

    private static JsonObject single(String key, JsonObject field) {
        JsonObject out = new JsonObject();
        out.add(key, field);
        return out;
    }

    private void resize(HostOperation op, LauncherState.Target target, double length, Consumer<JsonObject> done) {
        state.cursorGuard(op, target);
        JsonObject requested = new JsonObject();
        requested.addProperty("lengthBeats", length);
        java.util.function.Supplier<JsonElement> observed = () -> {
            JsonObject loop = new JsonObject();
            loop.addProperty("lengthBeats", state.clip.getLoopLength().get());
            return loop;
        };
        double start = state.clip.getLoopStart().get();
        BooleanSupplier matches = () -> state.clip.getLoopLength().get() == length
                && state.clip.getPlayStop().get() == start + length;
        if (matches.getAsBoolean()) {
            done.accept(single("loop", Results.field(requested, observed.get(), Outcome.ALREADY_MATCHED, null)));
            return;
        }
        Runnable unread = () -> done.accept(
                single("loop", Results.field(requested, observed.get(), Outcome.UNKNOWN, Reason.READBACK_MISMATCH)));
        op.markDispatched();
        state.clip.getLoopLength().set(length);
        long end = state.until(op);
        // Playback stop follows the loop end once the loop change is observed; setting both blindly can be clamped by
        // the host.
        state.awaitGuarded(
                op,
                () -> state.cursorGuard(op, target),
                end,
                () -> state.clip.getLoopLength().get() == length,
                () -> {
                    state.clip.getPlayStop().set(start + length);
                    state.awaitGuarded(
                            op,
                            () -> state.cursorGuard(op, target),
                            end,
                            matches,
                            () -> done.accept(
                                    single("loop", Results.field(requested, observed.get(), Outcome.APPLIED, null))),
                            unread);
                },
                unread);
    }

    private static float[] rgb(String hex) {
        return new float[] {
            Integer.parseInt(hex.substring(1, 3), 16) / 255f,
            Integer.parseInt(hex.substring(3, 5), 16) / 255f,
            Integer.parseInt(hex.substring(5, 7), 16) / 255f
        };
    }

    private boolean colorIs(float[] want) {
        float tolerance = 0.5f / 255 + 1e-4f;
        return Math.abs(state.clip.color().red() - want[0]) <= tolerance
                && Math.abs(state.clip.color().green() - want[1]) <= tolerance
                && Math.abs(state.clip.color().blue() - want[2]) <= tolerance;
    }

    private String hex() {
        return String.format(
                "#%02x%02x%02x",
                Math.round(state.clip.color().red() * 255),
                Math.round(state.clip.color().green() * 255),
                Math.round(state.clip.color().blue() * 255));
    }

    // ---- note-content edits: transpose, quantize, transform ----

    private void gridOp(HostOperation op, String kind, JsonObject p) {
        state.targets.enterClip(op, p.getAsJsonObject("target"), false, at -> {
            LauncherState.Target target = state.target(at);
            LauncherState.ready(target.slot);
            state.acquire(op);
            state.requireResolved(target.id(), target.scene());
            LauncherState.requireContent(target.slot);
            state.bind(
                    op,
                    target,
                    false,
                    () -> NoteGrid.loopWindow(state.clip, 0),
                    state.until(op),
                    (o, t, end, w) -> gridEdit(o, t, kind, p, end, w, o.result::complete));
        });
    }

    private void transform(HostOperation op, JsonArray refs) {
        JsonArray refused = new JsonArray();
        Reason[] first = new Reason[1];
        checkAll(op, refs, 0, refused, first, () -> {
            state.acquire(op);
            Batch batch = new Batch(refs.size());
            transformNext(op, refs, 0, batch);
        });
    }

    /** Resolves every target before any is written; a target that fails its check refuses the whole batch. */
    private void checkAll(
            HostOperation op, JsonArray refs, int i, JsonArray refused, Reason[] first, Runnable proceed) {
        if (i == refs.size()) {
            if (refused.size() > 0)
                throw Errors.entries(
                        first[0],
                        refused.size() + " of " + refs.size() + " entries failed their checks; nothing was written.",
                        refused);
            proceed.run();
            return;
        }
        JsonObject ref = refs.get(i).getAsJsonObject();
        Consumer<RpcException> next = failure -> {
            if (failure != null) {
                if (first[0] == null) first[0] = failure.reason;
                JsonObject entry = Errors.error(failure);
                entry.addProperty("index", i);
                refused.add(entry);
            }
            checkAll(op, refs, i + 1, refused, first, proceed);
        };
        int offset;
        try {
            trackRefs.resolve(ref.getAsJsonObject("track"), false);
            offset = state.targets.offsetOf(ref);
        } catch (RpcException failure) {
            next.accept(failure);
            return;
        }
        Runnable resolve = () -> {
            RpcException failure = null;
            try {
                LauncherTargets.Loc at = state.targets.clip(ref, offset);
                state.requireResolved(at.trackId(), at.scene);
            } catch (RpcException error) {
                failure = error;
            }
            next.accept(failure);
        };
        if (i == 0) state.targets.lease.enter(op, offset, true, false, resolve);
        else state.targets.lease.move(op, offset, resolve);
    }

    private void transformNext(HostOperation op, JsonArray refs, int i, Batch batch) {
        if (i == refs.size()) {
            JsonObject out =
                    Results.outcome(Results.worst(batch.entries().asList()).worst(Outcome.ALREADY_MATCHED));
            out.add("entries", batch.entries());
            op.result.complete(out);
            return;
        }
        JsonObject ref = refs.get(i).getAsJsonObject();
        HostOperation child = new HostOperation(op.deadline);
        child.result.whenComplete((result, error) -> {
            if (child.dispatched()) op.markDispatched();
            JsonObject entry = error == null ? result : Errors.entryResult(error);
            if (batch.record(entry)) transformNext(op, refs, i + 1, batch);
            else {
                JsonObject out = Results.outcome(Results.worst(batch.entries().asList()));
                out.add("entries", batch.entries());
                op.result.complete(out);
            }
        });
        scheduler.schedule(
                op,
                () -> {
                    int offset = state.targets.offsetOf(ref);
                    state.targets.lease.move(op, offset, () -> transformEntry(op, child, ref, offset));
                },
                0);
    }

    private void transformEntry(HostOperation op, HostOperation child, JsonObject ref, int offset) {
        try {
            LauncherState.Target target = state.target(state.targets.clip(ref, offset));
            state.requireResolved(target.id(), target.scene());
            op.markDispatched();
            state.selection.handOff(child);
            state.bind(
                    child,
                    target,
                    false,
                    () -> NoteGrid.loopWindow(state.clip, 0),
                    state.until(child),
                    (o, t, end, w) -> gridEdit(o, t, "transform", null, end, w, o.result::complete));
        } catch (RuntimeException failure) {
            child.fail(failure);
        }
    }

    /** Onset cells of the window as x*128+pitch -> {velocity, duration}; the same bounded read as notes.read. */
    private Map<Integer, double[]> onsets(NoteGrid.Window w) {
        Map<Integer, double[]> cells = new TreeMap<>();
        for (int x = 0; x < w.steps; x++)
            for (int pitch = w.pitch; pitch < w.pitch + w.pitches; pitch++) {
                NoteStep note = state.clip.getStep(w.channel, x, pitch);
                if (note == null || note.state() == null)
                    throw Errors.notApplied(Reason.NOT_READY, "Note grid is not readable");
                if (NoteGrid.hasState(note, "NoteOn"))
                    cells.put(x * 128 + pitch, new double[] {NoteGrid.midiVelocity(note.velocity()), note.duration()});
            }
        return cells;
    }

    private static List<Integer> pitches(Map<Integer, double[]> cells) {
        return cells.keySet().stream().map(key -> key % 128).sorted().collect(Collectors.toList());
    }

    private static boolean sameCells(Map<Integer, double[]> a, Map<Integer, double[]> b) {
        if (!a.keySet().equals(b.keySet())) return false;
        for (Map.Entry<Integer, double[]> e : a.entrySet())
            if (e.getValue()[0] != b.get(e.getKey())[0] || Math.abs(e.getValue()[1] - b.get(e.getKey())[1]) > 1e-9)
                return false;
        return true;
    }

    private static Map<Integer, double[]> shifted(Map<Integer, double[]> cells, int semitones) {
        Map<Integer, double[]> out = new TreeMap<>();
        for (Map.Entry<Integer, double[]> e : cells.entrySet()) out.put(e.getKey() + semitones, e.getValue());
        return out;
    }

    private JsonObject clipResult(LauncherState.Target target, String kind, Outcome outcome) {
        JsonObject out = LauncherState.written(outcome);
        out.addProperty("op", kind);
        out.add("ref", state.clipRef(target));
        if (outcome == Outcome.APPLIED) Verification.API_READBACK.put(out);
        return out;
    }

    /** {@code kind} is transpose, quantize or transform (doubleContent); {@code p} carries semitones or amount. */
    private void gridEdit(
            HostOperation op,
            LauncherState.Target target,
            String kind,
            JsonObject p,
            long end,
            NoteGrid.Window w,
            Consumer<JsonObject> done) {
        double loopStart = state.clip.getLoopStart().get(),
                loop = state.clip.getLoopLength().get();
        boolean covers = (w.start + w.steps) * w.size >= loopStart + loop - 1e-9;
        Map<Integer, double[]> before = onsets(w);
        List<Integer> beforePitches = pitches(before);
        boolean doubling = kind.equals("transform");
        if (!doubling) {
            if (!covers)
                throw Errors.notApplied(
                        Reason.UNVERIFIABLE_CLIP,
                        kind + " is verified by reading the whole loop in one window (at most 32 beats); this loop is "
                                + loop + " beats, so the result could not be observed and nothing was changed");
            if (before.isEmpty())
                throw Errors.notApplied(
                        Reason.NO_NOTES,
                        "No channel-0 onsets observed in the clip, so " + kind
                                + " could not be verified; nothing was changed");
        }
        int semitones = kind.equals("transpose") ? p.get("semitones").getAsInt() : 0;
        if (kind.equals("transpose")
                && (beforePitches.get(0) + semitones < 0
                        || beforePitches.get(beforePitches.size() - 1) + semitones > 127))
            throw Errors.invalid("transpose would move a note outside MIDI 0..127 (observed pitch range "
                    + beforePitches.get(0) + ".." + beforePitches.get(beforePitches.size() - 1) + ")");
        op.markDispatched();
        state.beginMutation(target, "clips." + kind);
        JsonObject summary = new JsonObject();
        summary.addProperty("notesBefore", before.size());
        summary.addProperty("loopLengthBeatsBefore", loop);
        summary.addProperty("verifiedChannel", w.channel);
        if (!before.isEmpty()) {
            summary.addProperty("pitchMinBefore", beforePitches.get(0));
            summary.addProperty("pitchMaxBefore", beforePitches.get(beforePitches.size() - 1));
        }
        Runnable complete = () -> {
            Map<Integer, double[]> after = onsets(w);
            List<Integer> afterPitches = pitches(after);
            JsonObject out = clipResult(target, kind, Outcome.APPLIED);
            summary.addProperty("notesAfter", after.size());
            summary.addProperty(
                    "loopLengthBeatsAfter", state.clip.getLoopLength().get());
            if (!after.isEmpty()) {
                summary.addProperty("pitchMinAfter", afterPitches.get(0));
                summary.addProperty("pitchMaxAfter", afterPitches.get(afterPitches.size() - 1));
            }
            out.add("compared", summary);
            out.addProperty(
                    "comparison",
                    kind.equals("transpose")
                            ? "channel-0 onset cells each moved by semitones with velocity and duration kept; loop length unchanged"
                            : kind.equals("quantize")
                                    ? "channel-0 onset count, pitch multiset and loop length unchanged and at least one onset cell moved; positions inside a grid cell are not observable"
                                    : "loop length doubled and the original first half of the channel-0 onset cells unchanged; the copied second half is not read");
            out.add("slot", state.slotJson(target));
            state.addClipMetadata(out);
            state.finishMutation();
            done.accept(out);
        };
        Runnable timeout = () -> {
            throw state.unobserved(Reason.NOT_OBSERVED, "Clip " + kind);
        };
        if (kind.equals("transpose")) {
            Map<Integer, double[]> expect = shifted(before, semitones);
            state.clip.transpose(semitones);
            state.awaitGuarded(
                    op,
                    () -> state.cursorGuard(op, target),
                    end,
                    () -> state.clip.getLoopLength().get() == loop && sameCells(expect, onsets(w)),
                    complete,
                    timeout);
        } else if (doubling) {
            state.clip.duplicateContent();
            state.awaitGuarded(
                    op,
                    () -> state.cursorGuard(op, target),
                    end,
                    () -> state.clip.getLoopLength().get() == loop * 2 && sameCells(before, onsets(w)),
                    complete,
                    timeout);
        } else {
            double amount = p.get("amount").getAsDouble();
            List<Map<Integer, double[]>> last = new ArrayList<>(List.of(before));
            BooleanSupplier settled = () -> {
                Map<Integer, double[]> now = onsets(w);
                boolean stable = sameCells(now, last.get(0)), moved = !sameCells(now, before);
                last.set(0, now);
                boolean invariants = now.size() == before.size()
                        && pitches(now).equals(beforePitches)
                        && state.clip.getLoopLength().get() == loop;
                if (moved && stable && !invariants)
                    throw state.unobserved(
                            Reason.READBACK_MISMATCH, "Clip quantize (note count, pitches or loop length changed)");
                return moved && stable && invariants;
            };
            // amount 1 snaps to the host grid and is idempotent: no visible movement is reported, not latched.
            Runnable unmoved = () -> {
                if (amount == 1
                        && sameCells(onsets(w), before)
                        && state.clip.getLoopLength().get() == loop) {
                    JsonObject out = clipResult(target, kind, Outcome.UNKNOWN);
                    out.addProperty("reason", Reason.NOT_OBSERVED.wire());
                    out.addProperty(
                            "note",
                            "no onset cell moved: already on the host quantize grid, or the move stays inside a cell");
                    out.add("slot", state.slotJson(target));
                    state.addClipMetadata(out);
                    state.finishMutation();
                    done.accept(out);
                } else timeout.run();
            };
            state.clip.quantize(amount);
            state.awaitGuarded(op, () -> state.cursorGuard(op, target), end, settled, complete, unmoved);
        }
    }
}
