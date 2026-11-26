package dev.bitwigagent;

import com.bitwig.extension.callback.*;
import com.bitwig.extension.controller.api.*;
import com.google.gson.*;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Explicit launcher targets; cursor leases and unresolved native mutations have separate lifetimes. */
final class LauncherModule implements HostModule {
    static final Set<String> METHODS = Set.of(
            "launcher.list",
            "launcher.create",
            "launcher.import",
            "launcher.launch",
            "launcher.stop",
            "notes.read",
            "notes.edit",
            "scenes.append",
            "clips.edit");
    // Per-op payload fields of clips.edit; exactly these plus the common target fields are accepted.
    private static final Map<String, Set<String>> CLIP_OPS = Map.of(
            "rename",
            Set.of("name"),
            "resize",
            Set.of("lengthBeats"),
            "color",
            Set.of("rgb"),
            "transpose",
            Set.of("semitones"),
            "quantize",
            Set.of("amount"),
            "duplicateContent",
            Set.of(),
            "delete",
            Set.of(),
            "copyTo",
            Set.of("targetSlotIndex"));
    // Ops that compare the cursor clip's note cells before and after; they need the observed loop inside one read
    // window.
    private static final Set<String> GRID_OPS = Set.of("transpose", "quantize", "duplicateContent");
    private static final Map<String, Reason> GRID_OP_NOT_OBSERVED = Map.of(
            "transpose", Reason.NOT_OBSERVED,
            "quantize", Reason.NOT_OBSERVED,
            "duplicateContent", Reason.NOT_OBSERVED);
    private static final java.util.regex.Pattern RGB = java.util.regex.Pattern.compile("#[0-9a-fA-F]{6}");
    // channel defaults to 0; the five grid fields are all-or-none (omitted: window derived from the edits or the clip
    // loop).
    private static final Set<String> WINDOW_OPTIONAL =
            Set.of("channel", "stepSizeBeats", "startStep", "steps", "pitchStart", "pitchCount");
    private static final Set<String> EDIT_OPTIONAL = withDetail(WINDOW_OPTIONAL);
    private static final Set<String> GRID_FIELDS =
            Set.of("stepSizeBeats", "startStep", "steps", "pitchStart", "pitchCount");
    static final double BEAT_GRID = HostLimits.LIMITS.beatGrid;
    static final int MAX_CLIP_BEATS = HostLimits.LIMITS.maxClipBeats;
    static final String BEAT_GRID_LABEL = "1/" + Math.round(1 / BEAT_GRID);
    private static final BigDecimal[] DERIVED_GRIDS = {
        new BigDecimal("4"),
        new BigDecimal("2"),
        BigDecimal.ONE,
        new BigDecimal("0.5"),
        new BigDecimal("0.25"),
        new BigDecimal("0.125"),
        new BigDecimal("0.0625"),
        new BigDecimal("0.03125"),
        new BigDecimal("0.015625"),
        BigDecimal.valueOf(BEAT_GRID)
    };
    // Bitwig 6.1.3 debounces step-size grid regeneration by 100ms before queuing controller delivery.
    // This margin avoids consuming that known old cache; it is not a configuration acknowledgement.
    private static final int NOTE_GRID_SETTLE_MS = 150;
    private static final int NOTE_LIMIT = 2048;

    private static Set<String> withDetail(Set<String> base) {
        Set<String> all = new HashSet<>(base);
        all.add("detail");
        return all;
    }

    private final Scheduler scheduler;
    private final TrackWindow window;
    private final TransportSafety transportSafety;
    private final SelectionLease selection;
    private final LauncherStructureLock lock;
    private final Holds holds;
    private final Clip clip;
    private final Track clipTrack;
    private final Slot[][] slots = new Slot[HostLimits.TRACKS][HostLimits.SCENES];
    private final boolean[] stopReady = new boolean[HostLimits.TRACKS];
    private final long[][] noteVersions = new long[128][128];
    private final long[][] noteGenerations = new long[128][128];
    private long sequence, bindingGeneration;
    private GridObservation gridObservation;
    // Only positive completion releases these holds; completed futures and later callbacks cannot release one.
    private Mutation cursorMutation;
    private boolean clipExistsReady, lengthReady;
    private final SceneBank sceneBank;
    // Bitwig only hands out the Project during extension init; calling getProject() later throws.
    private final Project project;

    LauncherModule(
            ControllerHost host,
            Scheduler scheduler,
            TrackWindow window,
            TransportSafety transportSafety,
            SelectionLease selection,
            LauncherStructureLock lock,
            Holds holds) {
        this.scheduler = scheduler;
        this.window = window;
        this.transportSafety = transportSafety;
        this.selection = selection;
        this.lock = lock;
        this.holds = holds;
        clip = host.createLauncherCursorClip(128, 128);
        clipTrack = clip.getTrack();
        sceneBank = window.bank().sceneBank();
        project = host.getProject();
        sceneBank.itemCount().markInterested();
        clipTrack.channelId().markInterested();
        clipTrack.exists().markInterested();
        clip.exists().addValueObserver((BooleanValueChangedCallback) value -> clipExistsReady = true);
        clip.getLoopLength().addValueObserver((DoubleValueChangedCallback) value -> lengthReady = true);
        clip.color().markInterested();
        clip.launchQuantization().markInterested();
        clip.launchMode().markInterested();
        clip.useLoopStartAsQuantizationReference().markInterested();
        clip.launchQuantization().addValueObserver((EnumValueChangedCallback) value -> launchSequence[0]++);
        clip.launchMode().addValueObserver((EnumValueChangedCallback) value -> launchSequence[1]++);
        clip.useLoopStartAsQuantizationReference()
                .addValueObserver((BooleanValueChangedCallback) value -> launchSequence[2]++);
        clip.getPlayStart().markInterested();
        clip.getPlayStop().markInterested();
        clip.getLoopStart().markInterested();
        clip.isLoopEnabled().markInterested();
        clip.addNoteStepObserver(note -> {
            int c = note.channel(), x = note.x(), y = note.y();
            GridObservation grid = gridObservation;
            if (grid != null
                    && c == grid.window.channel
                    && x >= 0
                    && x < 128
                    && y >= 0
                    && y < 128
                    && bound(grid.trackId, grid.slot)) {
                noteVersions[x][y] = ++sequence;
                noteGenerations[x][y] = grid.generation;
            }
        });
        ClipLauncherSlot cursorSlot = clip.clipLauncherSlot();
        for (int t = 0; t < HostLimits.TRACKS; t++) {
            Track track = window.at(t);
            final int index = t;
            track.isStopped().addValueObserver((BooleanValueChangedCallback) value -> stopReady[index] = true);
            track.channelId().addValueObserver((StringValueChangedCallback) value -> {
                for (Mutation mutation : mutations())
                    if (mutation.track == track && !mutation.trackId.equals(value)) mutation.rebound = true;
            });
            track.exists().addValueObserver((BooleanValueChangedCallback) value -> {
                if (!value) invalidateMutations(track);
            });
            track.isQueuedForStop().markInterested();
            ClipLauncherSlotBank bank = track.clipLauncherSlotBank();
            bank.scrollPosition().markInterested();
            bank.itemCount().markInterested();
            bank.scrollPosition().addValueObserver((IntegerValueChangedCallback) value -> {
                if (value != 0) invalidateMutations(track);
            });
            for (int j = 0; j < HostLimits.SCENES; j++) {
                Slot slot = new Slot((ClipLauncherSlot) bank.getItemAt(j), cursorSlot);
                slots[t][j] = slot;
            }
        }
    }

    public Set<String> methods() {
        return METHODS;
    }

    public void validate(String method, JsonObject params) {
        validateParams(method, params);
    }

    static void validateParams(String method, JsonObject p) {
        switch (method) {
            case "launcher.list":
            case "launcher.stop":
                Params.fields(p, Set.of("trackId"), Set.of());
                break;
            case "launcher.launch":
                Params.fields(p, Set.of("trackId", "slotIndex"), Set.of());
                break;
            case "launcher.import":
                Params.fields(p, Set.of("trackId", "slotIndex", "path"), Set.of());
                Params.absoluteFilePath(p, "path");
                break;
            case "launcher.create": {
                Params.fields(
                        p, Set.of("trackId", "slotIndex", "lengthBeats", "name"), Set.of("notes", "channel", "detail"));
                int length = Params.integer(p, "lengthBeats", 1, MAX_CLIP_BEATS);
                if (Params.string(p, "name").length() > 128)
                    throw Errors.invalid("name must contain 1..128 characters");
                if (p.has("notes")) {
                    JsonObject ep = createNotes(p);
                    edits(ep, resolveWindow("notes.edit", ep));
                    for (JsonElement n : ep.getAsJsonArray("edits")) {
                        JsonObject e = n.getAsJsonObject();
                        if (e.get("beat").getAsDouble() + e.get("durationBeats").getAsDouble() > length)
                            throw Errors.invalid("notes must fit inside lengthBeats");
                    }
                } else if (p.has("channel") || p.has("detail"))
                    throw Errors.invalid("channel and detail are only valid together with notes");
                detail(p);
                break;
            }
            case "clips.edit": {
                String op = p.has("op") ? Params.string(p, "op") : "";
                Set<String> payload = CLIP_OPS.get(op);
                if (payload == null) throw Errors.invalid("op must be one of " + new TreeSet<>(CLIP_OPS.keySet()));
                Set<String> required = new HashSet<>(payload);
                required.addAll(Set.of("trackId", "slotIndex", "expectedClipName", "op"));
                Params.fields(p, required, Set.of());
                if (Params.string(p, "expectedClipName").length() > 256)
                    throw Errors.invalid("expectedClipName must contain 1..256 characters");
                if (op.equals("rename") && Params.string(p, "name").length() > 128)
                    throw Errors.invalid("name must contain 1..128 characters");
                if (op.equals("resize")) Params.number(p, "lengthBeats", BEAT_GRID, MAX_CLIP_BEATS);
                if (op.equals("color") && !RGB.matcher(Params.string(p, "rgb")).matches())
                    throw Errors.invalid("rgb must be #RRGGBB");
                if (op.equals("transpose") && Params.integer(p, "semitones", -48, 48) == 0)
                    throw Errors.invalid("semitones must be nonzero");
                if (op.equals("quantize") && Params.number(p, "amount", 0, 1) == 0)
                    throw Errors.invalid("amount must be above 0");
                if (op.equals("copyTo")) Params.integer(p, "targetSlotIndex", 0, HostLimits.SCENES - 1);
                break;
            }
            case "scenes.append":
                Params.fields(p, Set.of(), Set.of("count"));
                if (p.has("count")) Params.integer(p, "count", 1, HostLimits.SCENES);
                return;
            case "notes.read":
            case "notes.edit": {
                Params.fields(
                        p,
                        method.equals("notes.edit")
                                ? Set.of("trackId", "slotIndex", "edits")
                                : Set.of("trackId", "slotIndex"),
                        method.equals("notes.edit") ? EDIT_OPTIONAL : WINDOW_OPTIONAL);
                detail(p);
                Window w = resolveWindow(method, p);
                if (method.equals("notes.edit")) edits(p, w);
                break;
            }
            default:
                throw Errors.notApplied(Reason.UNKNOWN_METHOD, "Unknown launcher method");
        }
        Params.string(p, "trackId");
        if (p.has("slotIndex")) Params.integer(p, "slotIndex", 0, HostLimits.SCENES - 1);
    }

    /** Presentation only: "full" lists the whole window and every edit; the default reports a summary plus unverified edits. */
    static boolean detail(JsonObject p) {
        if (!p.has("detail")) return false;
        JsonElement d = p.get("detail");
        if (!d.isJsonPrimitive()
                || !d.getAsJsonPrimitive().isString()
                || !Set.of("compact", "full").contains(d.getAsString()))
            throw Errors.invalid("detail must be compact or full");
        return d.getAsString().equals("full");
    }

    static List<Edit> edits(JsonObject p, Window w) {
        if (!p.get("edits").isJsonArray()) throw Errors.invalid("edits must be an array");
        JsonArray array = p.getAsJsonArray("edits");
        if (array.size() < 1 || array.size() > 256) throw Errors.invalid("edits must contain 1..256 entries");
        List<Edit> result = new ArrayList<>(array.size());
        Set<Integer> cells = new HashSet<>();
        for (JsonElement value : array) {
            if (!value.isJsonObject()) throw Errors.invalid("each edit must be an object");
            JsonObject e = value.getAsJsonObject();
            String op = Params.string(e, "op");
            boolean set = op.equals("set");
            if (!set && !op.equals("clear")) throw Errors.invalid("edit op must be set or clear");
            Params.fields(
                    e,
                    set ? Set.of("op", "beat", "pitch", "velocity", "durationBeats") : Set.of("op", "beat", "pitch"),
                    Set.of());
            Params.number(e, "beat", 0, 4194304);
            int absolute = aligned(e.get("beat"), new JsonPrimitive(w.exact), "beat");
            int x = absolute - w.start;
            int pitch = Params.integer(e, "pitch", 0, 127);
            if (x < 0 || x >= w.steps || pitch < w.pitch || pitch >= w.pitch + w.pitches)
                throw Errors.invalid("edit onset is outside the explicit window");
            if (!cells.add(x * 128 + pitch))
                throw Errors.invalid("multiple edits address the same channel/step/pitch cell");
            int velocity = set ? Params.integer(e, "velocity", 1, 127) : 0;
            double duration = set ? Params.number(e, "durationBeats", BEAT_GRID, MAX_CLIP_BEATS) : 0;
            int durationSteps = set ? aligned(e.get("durationBeats"), new JsonPrimitive(w.exact), "durationBeats") : 0;
            if (set && (durationSteps < 1 || x + durationSteps > w.steps))
                throw Errors.invalid("set duration must fit entirely inside the explicit window");
            result.add(new Edit(x, pitch, velocity, duration, durationSteps, set));
        }
        for (int i = 0; i < result.size(); i++) {
            Edit a = result.get(i);
            if (!a.set) continue;
            for (int j = i + 1; j < result.size(); j++) {
                Edit b = result.get(j);
                if (b.set && a.pitch == b.pitch && a.x < b.x + b.durationSteps && b.x < a.x + a.durationSteps)
                    throw Errors.invalid("same-channel/pitch note durations overlap");
            }
        }
        return result;
    }

    static boolean hasGrid(JsonObject p) {
        int present = 0;
        for (String key : GRID_FIELDS) if (p.has(key)) present++;
        if (present != 0 && present != GRID_FIELDS.size())
            throw Errors.invalid(
                    "stepSizeBeats, startStep, steps, pitchStart and pitchCount must be supplied together or all omitted");
        return present != 0;
    }

    /** Explicit window, or for notes.edit the smallest one derived from the edits; null for a notes.read to be sized from the clip loop. */
    static Window resolveWindow(String method, JsonObject p) {
        if (hasGrid(p)) return new Window(p);
        return method.equals("notes.edit") ? deriveEditWindow(p) : null;
    }

    static void applyWindow(JsonObject p, Window w) {
        p.addProperty("stepSizeBeats", w.exact);
        p.addProperty("startStep", w.start);
        p.addProperty("steps", w.steps);
        p.addProperty("pitchStart", w.pitch);
        p.addProperty("pitchCount", w.pitches);
        p.addProperty("channel", w.channel);
        p.addProperty("windowDerived", true);
    }

    /** Finest power-of-two grid (4..1/128 beat) aligning every edit within 128 steps, so finer existing onsets stay visible. */
    static Window deriveEditWindow(JsonObject p) {
        if (!p.has("edits") || !p.get("edits").isJsonArray()) throw Errors.invalid("edits must be an array");
        JsonArray array = p.getAsJsonArray("edits");
        if (array.size() < 1 || array.size() > 256) throw Errors.invalid("edits must contain 1..256 entries");
        List<BigDecimal[]> spans = new ArrayList<>(); // {beat, duration or null}
        int low = 128, high = -1;
        for (JsonElement value : array) {
            if (!value.isJsonObject()) throw Errors.invalid("each edit must be an object");
            JsonObject e = value.getAsJsonObject();
            boolean set = Params.string(e, "op").equals("set");
            Params.number(e, "beat", 0, 4194304);
            int pitch = Params.integer(e, "pitch", 0, 127);
            if (set) Params.number(e, "durationBeats", BEAT_GRID, MAX_CLIP_BEATS);
            low = Math.min(low, pitch);
            high = Math.max(high, pitch);
            spans.add(new BigDecimal[] {
                e.get("beat").getAsBigDecimal(), set ? e.get("durationBeats").getAsBigDecimal() : null
            });
        }
        // Finest first: a finer grid keeps existing finer onsets visible to the collision/sustain checks.
        BigDecimal grid = null, coarsest = null;
        long first = 0, last = 0;
        for (int i = DERIVED_GRIDS.length - 1; i >= 0 && grid == null; i--) {
            BigDecimal candidate = DERIVED_GRIDS[i];
            boolean aligns = true;
            for (BigDecimal[] span : spans)
                aligns &= span[0].remainder(candidate).signum() == 0
                        && (span[1] == null || span[1].remainder(candidate).signum() == 0);
            if (!aligns) continue;
            long lo = Long.MAX_VALUE, hi = 0;
            for (BigDecimal[] span : spans) {
                long x = span[0].divide(candidate).longValueExact();
                lo = Math.min(lo, x);
                hi = Math.max(
                        hi, x + (span[1] == null ? 1 : span[1].divide(candidate).longValueExact()));
            }
            coarsest = candidate;
            if (hi - lo <= 128) {
                grid = candidate;
                first = lo;
                last = hi;
            }
        }
        if (coarsest == null)
            throw Errors.invalid("beat and durationBeats must be multiples of " + BEAT_GRID_LABEL
                    + " beat to derive a window; no rounding is performed");
        if (grid == null)
            throw Errors.invalid(
                    "edits do not fit 128 steps on any grid that aligns them (coarsest is "
                            + coarsest.stripTrailingZeros().toPlainString()
                            + " beat): split them by time range into several calls, or use beats/durations that fit a coarser grid");
        JsonObject w = new JsonObject();
        w.addProperty("stepSizeBeats", grid);
        w.addProperty("startStep", first);
        w.addProperty("steps", last - first);
        w.addProperty("pitchStart", low);
        w.addProperty("pitchCount", high - low + 1);
        if (p.has("channel")) w.add("channel", p.get("channel"));
        return new Window(w);
    }

    /** launcher.create notes are set entries of notes.edit; returns the equivalent notes.edit params with a derived window. */
    static JsonObject createNotes(JsonObject p) {
        if (!p.get("notes").isJsonArray()) throw Errors.invalid("notes must be an array");
        JsonObject ep = new JsonObject();
        ep.add("trackId", p.get("trackId"));
        ep.add("slotIndex", p.get("slotIndex"));
        if (p.has("channel")) ep.add("channel", p.get("channel"));
        if (p.has("detail")) ep.add("detail", p.get("detail"));
        JsonArray list = new JsonArray();
        for (JsonElement n : p.getAsJsonArray("notes")) {
            if (!n.isJsonObject()) throw Errors.invalid("each note must be an object");
            JsonObject e = n.getAsJsonObject().deepCopy();
            if (e.has("op")
                    && !(e.get("op").isJsonPrimitive()
                            && e.get("op").getAsString().equals("set")))
                throw Errors.invalid("notes entries must be op=set");
            e.addProperty("op", "set");
            list.add(e);
        }
        ep.add("edits", list);
        applyWindow(ep, deriveEditWindow(ep));
        return ep;
    }

    /** Whole clip loop over all pitches at the finest of 1/32 and 1/16 note that fits 128 steps; never coarser. */
    private void deriveReadWindow(JsonObject p) {
        double start = clip.getLoopStart().get(), length = clip.getLoopLength().get();
        double size = length / 0.125 <= 128 + 1e-9 ? 0.125 : 0.25;
        JsonObject w = new JsonObject();
        w.addProperty("stepSizeBeats", size);
        w.addProperty("startStep", (int) Math.floor(start / size + 1e-9));
        w.addProperty("steps", Math.max(1, Math.min(128, (int) Math.ceil(length / size - 1e-9))));
        w.addProperty("pitchStart", 0);
        w.addProperty("pitchCount", 128);
        if (p.has("channel")) w.add("channel", p.get("channel"));
        applyWindow(p, new Window(w));
    }

    private void appendScenes(HostOperation op, JsonObject p) {
        int count = p.has("count") ? Params.integer(p, "count", 1, HostLimits.SCENES) : 1;
        if (!window.observed()) throw Errors.notApplied(Reason.NOT_READY, "Track bank has not reported its size yet.");
        int before = sceneBank.itemCount().get();
        if (before + count > HostLimits.SCENES)
            throw Errors.notApplied(
                    Reason.LIMIT_REACHED,
                    "The project has " + before + " scene(s); the launcher tools address at most " + HostLimits.SCENES
                            + ", so " + count + " more cannot be appended");
        op.markDispatched();
        // Project.createScene() is documented to create "a new empty scene as the last scene"; existing slot indices do
        // not move.
        for (int i = 0; i < count; i++) project.createScene();
        scheduler.poll(
                op,
                until(op),
                () -> {
                    scheduler.check(op);
                    return sceneBank.itemCount().get() == before + count;
                },
                () -> {
                    JsonObject out = Results.outcome(Outcome.APPLIED);
                    out.addProperty("verification", "scene_count");
                    out.addProperty("sceneCountBefore", before);
                    out.addProperty("sceneCount", before + count);
                    out.addProperty("appended", count);
                    out.addProperty("firstNewSlotIndex", before);
                    out.addProperty(
                            "placement", "last_scene_per_api_documentation; position is not read back, count is");
                    op.result.complete(out);
                },
                () -> {
                    throw Errors.unknown(
                            Reason.NOT_OBSERVED,
                            "Scene creation was dispatched, but the scene count did not reach " + (before + count)
                                    + " (observed " + sceneBank.itemCount().get()
                                    + "); inspect launcher.list and do not repeat");
                });
    }

    static int aligned(JsonElement beat, JsonElement size, String field) {
        BigDecimal[] parts = beat.getAsBigDecimal().divideAndRemainder(size.getAsBigDecimal());
        if (parts[1].signum() != 0)
            throw Errors.invalid(field + " must exactly align with stepSizeBeats; no rounding is performed");
        try {
            return parts[0].intValueExact();
        } catch (ArithmeticException error) {
            throw Errors.invalid(field + " exceeds supported grid range");
        }
    }

    public void dispatch(HostOperation op, String method, JsonObject p) {
        scheduler.check(op);
        validateParams(method, p);
        p = p.deepCopy();
        if (method.equals("scenes.append")) {
            appendScenes(op, p);
            return;
        }
        if (method.equals("notes.edit") && !hasGrid(p)) applyWindow(p, resolveWindow(method, p));
        String id = Params.string(p, "trackId");
        Track track = window.track(id);
        int t = window.trackSlot(id);
        if (track.clipLauncherSlotBank().scrollPosition().get() != 0)
            throw Errors.notApplied(Reason.STALE_CONTEXT, "Launcher bank is not at scene offset zero");
        if (method.equals("launcher.list")) {
            JsonObject out = base(id, -1);
            JsonArray entries = new JsonArray();
            int scenes = track.clipLauncherSlotBank().itemCount().get();
            for (int j = 0; j < Math.min(HostLimits.SCENES, scenes); j++) {
                ready(slots[t][j]);
                entries.add(slotJson(slots[t][j], j, id));
            }
            out.add("slots", entries);
            out.add("window", Results.window(0, HostLimits.SCENES, scenes));
            op.result.complete(out);
            return;
        }
        if (method.equals("launcher.stop")) {
            if (!stopReady[t]) throw Errors.notApplied(Reason.NOT_READY, "Track stop state has not been observed");
            guard(op, id, track);
            op.markDispatched();
            track.clipLauncherSlotBank().stop();
            awaitPlayback(op, id, track, null, -1, until(op));
            return;
        }
        int j = Params.integer(p, "slotIndex", 0, HostLimits.SCENES - 1);
        int scenes = track.clipLauncherSlotBank().itemCount().get();
        if (j >= scenes)
            throw Errors.notApplied(
                    Reason.OUT_OF_BOUNDS,
                    "slotIndex " + j + " does not exist: the project has " + scenes
                            + " scene(s). Append scenes with scenes.append, then re-run launcher.list");
        Slot slot = slots[t][j];
        ready(slot);
        if (method.equals("launcher.launch")) {
            requireContent(slot);
            guard(op, id, track);
            transportSafety.requirePlaybackSafe();
            op.markDispatched();
            slot.api.launch();
            awaitPlayback(op, id, track, slot, j, until(op));
            return;
        }
        acquire(op);
        if (!method.equals("notes.read")) requireResolved(id, slot, j);
        if (method.equals("launcher.create")) {
            if (slot.api.hasContent().get())
                throw Errors.notApplied(Reason.TARGET_EXISTS, "Create only accepts an observed empty slot");
            guard(op, id, track);
            op.markDispatched();
            beginMutation(id, track, slot, j, method);
            slot.api.createEmptyClip(Params.integer(p, "lengthBeats", 1, MAX_CLIP_BEATS));
            long end = until(op);
            JsonObject request = p;
            awaitGuarded(
                    op,
                    () -> guard(op, id, track),
                    end,
                    () -> slot.contentVersion > cursorMutation.contentBefore
                            && slot.observedContent
                            && slot.api.hasContent().get(),
                    () -> bind(op, id, track, slot, j, request, "launcher.create", end),
                    () -> {
                        JsonObject out = base(id, j);
                        if (request.has("notes")) out.addProperty("noteWrite", "not_attempted");
                        out.add("slot", slotJson(slot, j));
                        op.result.complete(unresolved(
                                out, Reason.NOT_OBSERVED, "The new clip's content was not observed in the slot."));
                    });
        } else if (method.equals("launcher.import")) {
            importFile(op, id, track, slot, j, p);
        } else if (method.equals("clips.edit")) {
            clipEdit(op, id, track, t, slot, j, p, scenes);
        } else {
            requireContent(slot);
            bind(op, id, track, slot, j, p, method, until(op));
        }
    }

    private void acquire(HostOperation op) {
        selection.acquire(op);
        cursorMutation = null;
    }

    void requireSettingsSafe(String id, int index) {
        lock.requireNoCursorOperation();
        requireResolved(id, slots[window.trackSlot(id)][index], index);
    }

    private void requireResolved(String id, Slot slot, int index) {
        lock.requireNoSettingsWrite();
        for (Mutation mutation : mutations()) {
            // Keep both the logical target and its native proxy held across track-bank rebinding.
            if (mutation.slot == slot || mutation.trackId.equals(id) && mutation.index == index)
                throw holds.blocked(
                        LauncherStructureLock.MUTATION,
                        mutation.description() + " Writes on this slot are held; reads still work.",
                        "Inspect launcher.list and notes.read");
        }
    }

    private List<Mutation> mutations() {
        return holds.keys(LauncherStructureLock.MUTATION, Mutation.class);
    }

    private void beginMutation(String id, Track track, Slot slot, int index, String method) {
        cursorMutation = new Mutation(id, track, slot, index, method);
        Mutation mutation = cursorMutation;
        holds.place(LauncherStructureLock.MUTATION, mutation, mutation::description);
    }

    private void finishMutation() {
        holds.release(LauncherStructureLock.MUTATION, cursorMutation);
        cursorMutation = null;
    }

    private void invalidateMutations(Track track) {
        for (Mutation mutation : mutations()) if (mutation.track == track) mutation.rebound = true;
    }

    private void importFile(HostOperation op, String id, Track track, Slot slot, int index, JsonObject p) {
        Path path = Params.absoluteFilePath(p, "path");
        if (!Files.isRegularFile(path) || !Files.isReadable(path))
            throw Errors.notApplied(Reason.INVALID_FILE, "Import requires a readable regular local file.");
        if (slot.api.hasContent().get())
            throw Errors.notApplied(Reason.TARGET_EXISTS, "Import only accepts an observed empty slot.");
        MidiImportPreflight.validate(path);
        InsertionPoint insertion = slot.api.replaceInsertionPoint();
        // Resolve everything first, then recheck the target, safety and deadline at the mutation boundary.
        guard(op, id, track);
        transportSafety.requirePlaybackSafe();
        if (slot.api.hasContent().get())
            throw Errors.notApplied(Reason.TARGET_EXISTS, "Import only accepts an observed empty slot.");
        long before = slot.contentVersion;
        op.markDispatched();
        beginMutation(id, track, slot, index, "launcher.import");
        insertion.insertFile(path.toString());
        awaitGuarded(
                op,
                () -> guard(op, id, track),
                until(op),
                () -> slot.contentVersion > before
                        && slot.observedContent
                        && slot.api.hasContent().get(),
                () -> {
                    JsonObject out = written(id, index, Outcome.APPLIED);
                    out.addProperty("verification", "slot_content_transition");
                    out.addProperty("requestedFileVerified", false);
                    out.add("slot", slotJson(slot, index));
                    finishMutation();
                    op.result.complete(out);
                },
                () -> {
                    throw Errors.unknown(
                            Reason.NOT_OBSERVED,
                            "File insertion was dispatched, but a fresh slot content transition was not observed; writes on this slot are held until acked after inspection.");
                });
    }

    private void bind(
            HostOperation op, String id, Track track, Slot slot, int index, JsonObject p, String method, long end) {
        guard(op, id, track);
        gridObservation = null;
        bindingGeneration++;
        // clips.edit counts as dispatched only at its mutation: selecting alone leaves the clip untouched.
        if (!method.equals("clips.edit") && !method.equals("launcher.set")) op.markDispatched();
        slot.api.select();
        scheduler.schedule(op, () -> awaitBinding(op, id, track, slot, index, p, method, end), 40);
    }

    private void awaitBinding(
            HostOperation op, String id, Track track, Slot slot, int index, JsonObject p, String method, long end) {
        awaitGuarded(
                op,
                () -> guard(op, id, track),
                end,
                () -> bound(id, slot),
                () -> {
                    boolean grid = usesGrid(method, p);
                    if ((method.equals("notes.read") || method.equals("clips.edit")) && grid && !p.has("stepSizeBeats"))
                        deriveReadWindow(p);
                    if (grid) {
                        Window w = new Window(p);
                        clip.setStepSize(w.size);
                        clip.scrollToKey(0);
                        clip.scrollToStep(w.start);
                    }
                    scheduler.schedule(
                            op,
                            () -> settledBinding(op, id, track, slot, index, p, method, end),
                            grid ? NOTE_GRID_SETTLE_MS : Scheduler.POLL_MS);
                },
                () -> {
                    throw Errors.of(
                            Reason.NOT_OBSERVED,
                            "Cursor slot identity or clip content was not observed",
                            op.dispatched());
                });
    }

    private void settledBinding(
            HostOperation op, String id, Track track, Slot slot, int index, JsonObject p, String method, long end) {
        guard(op, id, track);
        if (!bound(id, slot)) {
            awaitBinding(op, id, track, slot, index, p, method, end);
            return;
        }
        // API exposes no grid-configuration acknowledgement. This is settling, not a proven data barrier.
        if (usesGrid(method, p)) gridObservation = new GridObservation(bindingGeneration, id, slot, new Window(p));
        if (method.equals("launcher.create")) {
            cursorGuard(op, id, track, slot);
            clip.setName(Params.string(p, "name"));
            // Native createEmptyClip can leave playback stop at its four-beat default.
            clip.getPlayStop().set(Params.integer(p, "lengthBeats", 1, MAX_CLIP_BEATS));
            scheduler.schedule(op, () -> awaitName(op, id, track, slot, index, p, end), Scheduler.POLL_MS);
        } else if (method.equals("notes.read")) {
            cursorGuard(op, id, track, slot);
            op.result.complete(notesResult(id, index, new Window(p), true));
        } else if (method.equals("clips.edit")) {
            cursorClipEdit(op, id, track, slot, index, p, end);
        } else if (method.equals("launcher.set")) {
            cursorLaunchSet(op, id, track, slot, index, p, end);
        } else editNotes(op, id, track, slot, index, p);
    }

    private static boolean usesGrid(String method, JsonObject p) {
        return method.startsWith("notes.")
                || method.equals("clips.edit") && GRID_OPS.contains(p.get("op").getAsString());
    }

    private void awaitName(HostOperation op, String id, Track track, Slot slot, int index, JsonObject p, long end) {
        BooleanSupplier named = () -> slot.api.name().get().equals(Params.string(p, "name"))
                && clip.getLoopLength().get() == Params.integer(p, "lengthBeats", 1, MAX_CLIP_BEATS)
                && clip.getPlayStop().get() == Params.integer(p, "lengthBeats", 1, MAX_CLIP_BEATS);
        scheduler.poll(
                op,
                end,
                () -> {
                    cursorGuard(op, id, track, slot);
                    return named.getAsBoolean();
                },
                matches -> finishCreate(op, id, track, slot, index, p, matches));
    }

    private void finishCreate(
            HostOperation op, String id, Track track, Slot slot, int index, JsonObject p, boolean matches) {
        JsonObject out = base(id, index);
        out.add("slot", slotJson(slot, index));
        addClipMetadata(out);
        if (matches) {
            out.addProperty("verification", "slot_content_name_length");
            finishMutation();
        }
        if (matches && p.has("notes")) {
            // Clip creation is complete and released. The note write is an ordinary notes.edit child sharing the
            // deadline;
            // its failure is reported inside noteWrite and never discards the observed create.
            HostOperation child = new HostOperation(op.deadline);
            selection.handOff(child);
            child.result.whenComplete((written, error) -> {
                if (error == null) {
                    out.addProperty(
                            "outcome",
                            Outcome.APPLIED.worst(Results.outcomeOf(written)).wire());
                    out.add("noteWrite", written);
                } else {
                    Throwable cause =
                            error instanceof java.util.concurrent.CompletionException && error.getCause() != null
                                    ? error.getCause()
                                    : error;
                    RpcException rpc = cause instanceof RpcException
                            ? (RpcException) cause
                            : Errors.notApplied(Reason.INTERNAL, String.valueOf(cause.getMessage()));
                    JsonObject failure = Errors.unknownResult(rpc);
                    if (!rpc.unknown) failure.addProperty("outcome", Outcome.NOT_APPLIED.wire());
                    out.addProperty(
                            "outcome",
                            Outcome.APPLIED.worst(Results.outcomeOf(failure)).wire());
                    out.add("noteWrite", failure);
                }
                op.result.complete(out);
            });
            try {
                bind(child, id, track, slot, index, createNotes(p), "notes.edit", until(op));
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
        if (p.has("notes")) out.addProperty("noteWrite", "not_attempted");
        op.result.complete(
                unresolved(out, Reason.READBACK_MISMATCH, "The new clip's name or length differs from the request."));
    }

    // ---- launcher.set launch fields: public Clip properties of the cursor clip, so the clip is selected (unlike the
    // native next-action fields) ----

    static final List<String> LAUNCH_FIELDS =
            List.of("launchQuantization", "launchMode", "useLoopStartAsQuantizationReference");

    static boolean hasLaunchFields(JsonObject p) {
        for (String key : LAUNCH_FIELDS) if (p.has(key)) return true;
        return false;
    }

    /** Entry from launcher.set when it carries launch fields: same slot CAS and stopped-state guard as the native fields, then a cursor-clip bind. */
    void launchSettings(HostOperation op, JsonObject p) {
        scheduler.check(op);
        String id = Params.string(p, "trackId");
        Track track = window.track(id);
        int t = window.trackSlot(id);
        if (track.clipLauncherSlotBank().scrollPosition().get() != 0)
            throw Errors.notApplied(Reason.STALE_CONTEXT, "Launcher bank is not at scene offset zero");
        int j = Params.integer(p, "slotIndex", 0, HostLimits.SCENES - 1);
        int scenes = track.clipLauncherSlotBank().itemCount().get();
        if (j >= scenes)
            throw Errors.notApplied(
                    Reason.OUT_OF_BOUNDS,
                    "slotIndex " + j + " does not exist: the project has " + scenes + " scene(s).");
        Slot slot = slots[t][j];
        ready(slot);
        acquire(op);
        requireResolved(id, slot, j);
        requireContent(slot);
        String expected = Params.string(p, "expectedClipName"),
                actual = slot.api.name().get();
        if (!actual.equals(expected))
            throw Errors.notApplied(
                    Reason.STALE_TARGET,
                    "Slot " + j + " holds clip \"" + actual + "\", not expectedClipName; re-read launcher.list");
        // Options are the host's own list; reject before the selection moves.
        if (p.has("launchQuantization"))
            EnumOptions.require(
                    "launchQuantization",
                    clip.launchQuantization(),
                    p.get("launchQuantization").getAsString());
        if (p.has("launchMode"))
            EnumOptions.require(
                    "launchMode", clip.launchMode(), p.get("launchMode").getAsString());
        SafetyObservation safety = transportSafety.stopped(op);
        guard(op, id, track);
        launchSafety = safety;
        bind(op, id, track, slot, j, p, "launcher.set", until(op));
    }

    private SafetyObservation launchSafety;
    // Callback counters of the cursor clip's launch properties, in LAUNCH_FIELDS order.
    private final long[] launchSequence = new long[3];

    private JsonElement launchValue(String key) {
        switch (key) {
            case "launchQuantization":
                return new JsonPrimitive(clip.launchQuantization().get());
            case "launchMode":
                return new JsonPrimitive(clip.launchMode().get());
            default:
                return new JsonPrimitive(
                        clip.useLoopStartAsQuantizationReference().get());
        }
    }

    private void cursorLaunchSet(
            HostOperation op, String id, Track track, Slot slot, int index, JsonObject p, long end) {
        cursorGuard(op, id, track, slot);
        if (!p.get("expectedClipName").getAsString().equals(slot.api.name().get()))
            throw Errors.notApplied(Reason.STALE_TARGET, "The bound clip no longer has expectedClipName.");
        transportSafety.guardStopped(launchSafety);
        // Only differing fields are written; a written field counts only once its own observer fires after the write.
        long[] before = launchSequence.clone();
        Set<String> written = new HashSet<>();
        for (String key : LAUNCH_FIELDS) {
            if (!p.has(key) || launchValue(key).equals(p.get(key))) continue;
            op.markDispatched();
            written.add(key);
            JsonElement value = p.get(key);
            if (key.equals("launchQuantization")) clip.launchQuantization().set(value.getAsString());
            else if (key.equals("launchMode")) clip.launchMode().set(value.getAsString());
            else clip.useLoopStartAsQuantizationReference().set(value.getAsBoolean());
        }
        BooleanSupplier matches = () -> {
            for (int i = 0; i < LAUNCH_FIELDS.size(); i++) {
                String key = LAUNCH_FIELDS.get(i);
                if (!p.has(key)) continue;
                if (!launchValue(key).equals(p.get(key)) || written.contains(key) && launchSequence[i] <= before[i])
                    return false;
            }
            return true;
        };
        awaitGuarded(
                op,
                () -> cursorGuard(op, id, track, slot),
                end,
                matches,
                () -> op.result.complete(launchDone(id, index, p, slot, written, before)),
                () -> op.result.complete(launchDone(id, index, p, slot, written, before)));
    }

    private JsonObject launchDone(String id, int index, JsonObject p, Slot slot, Set<String> written, long[] before) {
        JsonObject out = base(id, index);
        out.addProperty("name", slot.api.name().get());
        out.addProperty("scope", "cursor_clip_launch_settings");
        out.addProperty("selectionSideEffect", "launcher_cursor_clip_selected");
        JsonObject fields = new JsonObject();
        for (int i = 0; i < LAUNCH_FIELDS.size(); i++) {
            String key = LAUNCH_FIELDS.get(i);
            if (!p.has(key)) continue;
            boolean match = launchValue(key).equals(p.get(key)), fresh = launchSequence[i] > before[i];
            Outcome outcome = !match
                    ? Outcome.UNKNOWN
                    : !written.contains(key) ? Outcome.ALREADY_MATCHED : fresh ? Outcome.APPLIED : Outcome.UNKNOWN;
            Reason reason = outcome != Outcome.UNKNOWN ? null : match ? Reason.NOT_OBSERVED : Reason.READBACK_MISMATCH;
            fields.add(key, Results.field(p.get(key), launchValue(key), outcome, reason));
        }
        Outcome outcome = Results.worst(fields.asMap().values());
        out.addProperty("outcome", outcome.wire());
        if (!outcome.succeeded())
            for (var field : fields.entrySet()) {
                JsonElement reason = field.getValue().getAsJsonObject().get("reason");
                if (reason != null) {
                    out.add("reason", reason);
                    break;
                }
            }
        out.add("fields", fields);
        JsonObject options = new JsonObject();
        options.add("launchQuantization", EnumOptions.json(clip.launchQuantization()));
        options.add("launchMode", EnumOptions.json(clip.launchMode()));
        out.add("options", options);
        return out;
    }

    // ---- clips.edit: one whole-clip operation per call, every outcome read back; see PROTOCOL.md ----

    private void clipEdit(
            HostOperation op, String id, Track track, int t, Slot slot, int index, JsonObject p, int scenes) {
        requireContent(slot);
        String expected = Params.string(p, "expectedClipName"),
                actual = slot.api.name().get();
        if (!actual.equals(expected))
            throw Errors.notApplied(
                    Reason.STALE_TARGET,
                    "Slot " + index + " holds clip \"" + actual + "\", not expectedClipName; re-read launcher.list");
        String kind = Params.string(p, "op");
        if (kind.equals("delete")) deleteClip(op, id, track, slot, index);
        else if (kind.equals("copyTo"))
            copyClip(
                    op,
                    id,
                    track,
                    t,
                    slot,
                    index,
                    Params.integer(p, "targetSlotIndex", 0, HostLimits.SCENES - 1),
                    scenes,
                    expected);
        else {
            bind(op, id, track, slot, index, p, "clips.edit", until(op));
        }
    }

    /** {@code verification} says how an applied edit was observed; null for any other outcome. */
    private JsonObject clipResult(String id, int index, String kind, Outcome outcome, String verification) {
        JsonObject out = written(id, index, outcome);
        out.addProperty("op", kind);
        if (verification != null) out.addProperty("verification", verification);
        return out;
    }

    private RpcException unobserved(Reason code, String what) {
        return Errors.unknown(
                code,
                what
                        + " was dispatched but its outcome was not observed; writes on this slot are held until acked after inspection."
                        + " Inspect with launcher.list / notes.read, never retry.");
    }

    private void deleteClip(HostOperation op, String id, Track track, Slot slot, int index) {
        SafetyObservation safety = transportSafety.stopped(op);
        if (slot.api.isPlaying().get()
                || slot.api.isPlaybackQueued().get()
                || slot.api.isRecording().get())
            throw Errors.notApplied(
                    Reason.UNSAFE_STATE, "Delete requires the clip to be neither playing, queued nor recording.");
        guard(op, id, track);
        transportSafety.guardStopped(safety);
        op.markDispatched();
        beginMutation(id, track, slot, index, "clips.edit delete");
        long before = cursorMutation.contentBefore;
        slot.api.deleteObject();
        awaitGuarded(
                op,
                () -> guard(op, id, track),
                until(op),
                () -> slot.contentVersion > before
                        && !slot.observedContent
                        && !slot.api.hasContent().get(),
                () -> {
                    JsonObject out = clipResult(id, index, "delete", Outcome.APPLIED, "slot_empty");
                    out.add("slot", slotJson(slot, index));
                    finishMutation();
                    op.result.complete(out);
                },
                () -> {
                    throw unobserved(Reason.NOT_OBSERVED, "Clip delete");
                });
    }

    private void copyClip(
            HostOperation op,
            String id,
            Track track,
            int t,
            Slot slot,
            int index,
            int target,
            int scenes,
            String name) {
        if (target >= scenes)
            throw Errors.notApplied(
                    Reason.OUT_OF_BOUNDS,
                    "targetSlotIndex " + target + " does not exist: the project has " + scenes
                            + " scene(s). Append scenes with scenes.append, then re-run launcher.list");
        if (target == index) throw Errors.notApplied(Reason.TARGET_EXISTS, "targetSlotIndex is the source slot");
        Slot to = slots[t][target];
        ready(to);
        requireResolved(id, to, target);
        if (to.api.hasContent().get())
            throw Errors.notApplied(Reason.TARGET_EXISTS, "copyTo only accepts an observed empty target slot");
        guard(op, id, track);
        op.markDispatched();
        beginMutation(id, track, to, target, "clips.edit copyTo");
        long before = cursorMutation.contentBefore;
        // copySlotsOrScenes copies the supplied slots to this insertion point; copyFrom's direction is ambiguous in the
        // javadoc.
        to.api.replaceInsertionPoint().copySlotsOrScenes(slot.api);
        BooleanSupplier present = () -> to.contentVersion > before
                && to.observedContent
                && to.api.hasContent().get();
        Runnable ok = () -> {
            String copied = to.api.name().get();
            JsonObject out = clipResult(
                    id,
                    index,
                    "copyTo",
                    Outcome.APPLIED,
                    copied.equals(name) ? "target_content_and_name" : "target_content");
            if (!copied.equals(name)) {
                JsonObject changed = new JsonObject();
                changed.addProperty("from", name);
                changed.addProperty("to", copied);
                out.add("nameChanged", changed);
            }
            out.addProperty("targetSlotIndex", target);
            out.add("slot", slotJson(to, target));
            finishMutation();
            op.result.complete(out);
        };
        // Bitwig may rename the copy: wait for the equal name, but a verified content transition alone is still
        // observed.
        awaitGuarded(
                op,
                () -> guard(op, id, track),
                until(op),
                () -> present.getAsBoolean() && to.api.name().get().equals(name),
                ok,
                () -> {
                    if (present.getAsBoolean()) ok.run();
                    else throw unobserved(Reason.NOT_OBSERVED, "Clip copy");
                });
    }

    private void awaitGuarded(
            HostOperation op, Runnable guard, long end, BooleanSupplier done, Runnable ok, Runnable timeout) {
        scheduler.poll(
                op,
                end,
                () -> {
                    guard.run();
                    return done.getAsBoolean();
                },
                ok,
                timeout);
    }

    /** Ops on the bound launcher cursor clip. Idempotent absolute setters (rename/resize/color) that cannot be read back report
     *  outcome unknown (readback_mismatch); transpose/quantize/duplicateContent are not idempotent and hold the slot when unobserved. */
    private void cursorClipEdit(
            HostOperation op, String id, Track track, Slot slot, int index, JsonObject p, long end) {
        cursorGuard(op, id, track, slot);
        String kind = Params.string(p, "op");
        if (kind.equals("rename"))
            setAndRead(
                    op,
                    id,
                    track,
                    slot,
                    index,
                    kind,
                    end,
                    () -> slot.api.name().get().equals(Params.string(p, "name")),
                    () -> clip.setName(Params.string(p, "name")),
                    "slot_name");
        else if (kind.equals("color")) {
            float[] want = rgb(Params.string(p, "rgb"));
            setAndRead(
                    op,
                    id,
                    track,
                    slot,
                    index,
                    kind,
                    end,
                    () -> colorIs(want),
                    () -> clip.color().set(want[0], want[1], want[2]),
                    "clip_color");
        } else if (kind.equals("resize"))
            resize(op, id, track, slot, index, Params.number(p, "lengthBeats", BEAT_GRID, MAX_CLIP_BEATS), end);
        else gridEdit(op, id, track, slot, index, p, kind, end);
    }

    /** Dispatch an idempotent write unless the readback already matches; unobserved after the window is unknown, no hold. */
    private void setAndRead(
            HostOperation op,
            String id,
            Track track,
            Slot slot,
            int index,
            String kind,
            long end,
            BooleanSupplier matches,
            Runnable write,
            String verification) {
        if (matches.getAsBoolean()) {
            op.result.complete(clipDone(id, index, kind, Outcome.ALREADY_MATCHED, null, slot));
            return;
        }
        op.markDispatched();
        write.run();
        awaitGuarded(
                op,
                () -> cursorGuard(op, id, track, slot),
                end,
                matches,
                () -> op.result.complete(clipDone(id, index, kind, Outcome.APPLIED, verification, slot)),
                () -> op.result.complete(clipUnread(id, index, kind, slot)));
    }

    private JsonObject clipUnread(String id, int index, String kind, Slot slot) {
        JsonObject out = clipDone(id, index, kind, Outcome.UNKNOWN, null, slot);
        out.addProperty("reason", Reason.READBACK_MISMATCH.wire());
        return out;
    }

    private JsonObject clipDone(String id, int index, String kind, Outcome outcome, String verification, Slot slot) {
        JsonObject out = clipResult(id, index, kind, outcome, verification);
        out.add("slot", slotJson(slot, index));
        addClipMetadata(out);
        out.addProperty("color", hex());
        return out;
    }

    private void resize(HostOperation op, String id, Track track, Slot slot, int index, double length, long end) {
        double start = clip.getLoopStart().get();
        BooleanSupplier matches =
                () -> clip.getLoopLength().get() == length && clip.getPlayStop().get() == start + length;
        if (matches.getAsBoolean()) {
            op.result.complete(clipDone(id, index, "resize", Outcome.ALREADY_MATCHED, null, slot));
            return;
        }
        op.markDispatched();
        clip.getLoopLength().set(length);
        // Playback stop follows the loop end once the loop change is observed; setting both blindly can be clamped by
        // the host.
        awaitGuarded(
                op,
                () -> cursorGuard(op, id, track, slot),
                end,
                () -> clip.getLoopLength().get() == length,
                () -> {
                    clip.getPlayStop().set(start + length);
                    awaitGuarded(
                            op,
                            () -> cursorGuard(op, id, track, slot),
                            end,
                            matches,
                            () -> op.result.complete(
                                    clipDone(id, index, "resize", Outcome.APPLIED, "loop_length_play_stop", slot)),
                            () -> op.result.complete(clipUnread(id, index, "resize", slot)));
                },
                () -> op.result.complete(clipUnread(id, index, "resize", slot)));
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
        return Math.abs(clip.color().red() - want[0]) <= tolerance
                && Math.abs(clip.color().green() - want[1]) <= tolerance
                && Math.abs(clip.color().blue() - want[2]) <= tolerance;
    }

    private String hex() {
        return String.format(
                "#%02x%02x%02x",
                Math.round(clip.color().red() * 255),
                Math.round(clip.color().green() * 255),
                Math.round(clip.color().blue() * 255));
    }

    /** Onset cells of the window as x*128+pitch -> {velocity, duration}; the same bounded read as notes.read. */
    private Map<Integer, double[]> onsets(Window w) {
        Map<Integer, double[]> cells = new TreeMap<>();
        for (int x = 0; x < w.steps; x++)
            for (int pitch = w.pitch; pitch < w.pitch + w.pitches; pitch++) {
                NoteStep note = clip.getStep(w.channel, x, pitch);
                if (note == null || note.state() == null)
                    throw Errors.notApplied(Reason.NOT_READY, "Note grid is not readable");
                if (hasState(note, "NoteOn"))
                    cells.put(x * 128 + pitch, new double[] {midiVelocity(note.velocity()), note.duration()});
            }
        return cells;
    }

    private static List<Integer> pitches(Map<Integer, double[]> cells) {
        List<Integer> out = new ArrayList<>();
        for (int key : cells.keySet()) out.add(key % 128);
        Collections.sort(out);
        return out;
    }

    // Matched by constant name: the host jar flattens NoteStep.State, so only the name resolves with both jars.
    static boolean hasState(NoteStep note, String name) {
        return note.state().name().equals(name);
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

    private void gridEdit(
            HostOperation op, String id, Track track, Slot slot, int index, JsonObject p, String kind, long end) {
        Window w = new Window(p);
        double loopStart = clip.getLoopStart().get(),
                loop = clip.getLoopLength().get();
        boolean covers = (w.start + w.steps) * w.size >= loopStart + loop - 1e-9;
        Map<Integer, double[]> before = onsets(w);
        List<Integer> beforePitches = pitches(before);
        if (!kind.equals("duplicateContent")) {
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
        int semitones = kind.equals("transpose") ? Params.integer(p, "semitones", -48, 48) : 0;
        if (kind.equals("transpose")
                && (beforePitches.get(0) + semitones < 0
                        || beforePitches.get(beforePitches.size() - 1) + semitones > 127))
            throw Errors.invalid("transpose would move a note outside MIDI 0..127 (observed pitch range "
                    + beforePitches.get(0) + ".." + beforePitches.get(beforePitches.size() - 1) + ")");
        op.markDispatched();
        beginMutation(id, track, slot, index, "clips.edit " + kind);
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
            JsonObject out = clipResult(
                    id,
                    index,
                    kind,
                    Outcome.APPLIED,
                    kind.equals("transpose")
                            ? "onset_cells_shifted"
                            : kind.equals("quantize") ? "invariants_preserved_cells_changed" : "loop_length_doubled");
            summary.addProperty("notesAfter", after.size());
            summary.addProperty("loopLengthBeatsAfter", clip.getLoopLength().get());
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
            out.add("slot", slotJson(slot, index));
            addClipMetadata(out);
            finishMutation();
            op.result.complete(out);
        };
        Runnable timeout = () -> {
            throw unobserved(GRID_OP_NOT_OBSERVED.get(kind), "Clip " + kind);
        };
        if (kind.equals("transpose")) {
            Map<Integer, double[]> expect = shifted(before, semitones);
            clip.transpose(semitones);
            awaitGuarded(
                    op,
                    () -> cursorGuard(op, id, track, slot),
                    end,
                    () -> clip.getLoopLength().get() == loop && sameCells(expect, onsets(w)),
                    complete,
                    timeout);
        } else if (kind.equals("duplicateContent")) {
            clip.duplicateContent();
            awaitGuarded(
                    op,
                    () -> cursorGuard(op, id, track, slot),
                    end,
                    () -> clip.getLoopLength().get() == loop * 2 && sameCells(before, onsets(w)),
                    complete,
                    timeout);
        } else {
            double amount = Params.number(p, "amount", 0, 1);
            List<Map<Integer, double[]>> last = new ArrayList<>(List.of(before));
            BooleanSupplier done = () -> {
                Map<Integer, double[]> now = onsets(w);
                boolean stable = sameCells(now, last.get(0)), moved = !sameCells(now, before);
                last.set(0, now);
                boolean invariants = now.size() == before.size()
                        && pitches(now).equals(beforePitches)
                        && clip.getLoopLength().get() == loop;
                if (moved && stable && !invariants)
                    throw unobserved(
                            Reason.READBACK_MISMATCH, "Clip quantize (note count, pitches or loop length changed)");
                return moved && stable && invariants;
            };
            // amount 1 snaps to the host grid and is idempotent: no visible movement is reported, not latched.
            Runnable settled = () -> {
                if (amount == 1
                        && sameCells(onsets(w), before)
                        && clip.getLoopLength().get() == loop) {
                    JsonObject out = clipResult(id, index, kind, Outcome.UNKNOWN, null);
                    out.addProperty("reason", Reason.NOT_OBSERVED.wire());
                    out.addProperty(
                            "note",
                            "no onset cell moved: already on the host quantize grid, or the move stays inside a cell");
                    out.add("slot", slotJson(slot, index));
                    addClipMetadata(out);
                    finishMutation();
                    op.result.complete(out);
                } else timeout.run();
            };
            clip.quantize(amount);
            awaitGuarded(op, () -> cursorGuard(op, id, track, slot), end, done, complete, settled);
        }
    }

    private void editNotes(HostOperation op, String id, Track track, Slot slot, int index, JsonObject p) {
        Window w = new Window(p);
        List<Edit> changes = edits(p, w);
        cursorGuard(op, id, track, slot);
        preflight(changes, w, clip);
        for (Edit e : changes) {
            cursorGuard(op, id, track, slot);
            e.before = noteVersions[e.x][e.pitch];
            e.generation = bindingGeneration;
            NoteStep old = clip.getStep(w.channel, e.x, e.pitch);
            e.alreadyMatched = matches(e, old);
            if (e.alreadyMatched) continue; // Preserve expression and other properties on an existing matching note.
            op.markDispatched();
            if (e.set) clip.setStep(w.channel, e.x, e.pitch, e.velocity, e.duration);
            else clip.clearStep(w.channel, e.x, e.pitch);
        }
        boolean full = detail(p);
        long end = until(op);
        scheduler.schedule(op, () -> awaitNotes(op, id, track, slot, index, w, changes, full, end), Scheduler.POLL_MS);
    }

    static void preflight(List<Edit> changes, Window w, Clip clip) {
        double loopStart = clip.getLoopStart().get();
        double end = loopStart + clip.getLoopLength().get();
        for (Edit e : changes) {
            NoteStep old = clip.getStep(w.channel, e.x, e.pitch);
            if (old == null || old.state() == null)
                throw Errors.notApplied(Reason.NOT_READY, "Note grid is not readable");
            if (hasState(old, "NoteSustain"))
                throw Errors.notApplied(Reason.OVERLAP, "Edit addresses a sustained note, not its onset");
            double beat = (w.start + e.x) * w.size;
            if (e.set && (beat < loopStart || beat + e.duration > end))
                throw Errors.invalid("set note must fit inside the observed clip loop start and end");
            if (!e.set) continue;
            for (int x = e.x + 1; x < e.x + e.durationSteps; x++) {
                NoteStep next = clip.getStep(w.channel, x, e.pitch);
                if (next == null || next.state() == null)
                    throw Errors.notApplied(Reason.NOT_READY, "Note grid is not readable");
                if (hasState(next, "NoteOn"))
                    throw Errors.notApplied(
                            Reason.OVERLAP,
                            "Set duration overlaps an existing unrelated onset; clear it in a separate explicit edit first");
            }
        }
    }

    private void awaitNotes(
            HostOperation op,
            String id,
            Track track,
            Slot slot,
            int index,
            Window w,
            List<Edit> changes,
            boolean full,
            long end) {
        scheduler.poll(
                op,
                end,
                () -> {
                    cursorGuard(op, id, track, slot);
                    boolean all = true;
                    for (Edit e : changes) {
                        boolean fresh =
                                noteGenerations[e.x][e.pitch] == e.generation && noteVersions[e.x][e.pitch] > e.before;
                        e.verified = (fresh || e.alreadyMatched) && matches(e, clip.getStep(w.channel, e.x, e.pitch));
                        all &= e.verified;
                    }
                    return all;
                },
                all -> finishNotes(op, id, index, w, changes, full));
    }

    private void finishNotes(HostOperation op, String id, int index, Window w, List<Edit> changes, boolean full) {
        JsonObject out = notesResult(id, index, w, full);
        // The outcome always covers every edit; detail only changes how many entries are listed.
        Outcome worst = Outcome.ALREADY_MATCHED;
        Reason firstReason = null;
        JsonArray results = new JsonArray();
        int verified = 0, matched = 0;
        for (Edit e : changes) {
            if (e.verified) verified++;
            if (e.alreadyMatched) matched++;
            boolean fresh = noteGenerations[e.x][e.pitch] == e.generation && noteVersions[e.x][e.pitch] > e.before;
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
            item.add("observed", observedJson(clip.getStep(w.channel, e.x, e.pitch), w, e.x, e.pitch));
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

    static boolean matches(Edit e, NoteStep note) {
        if (note == null) return false;
        if (!e.set) return hasState(note, "Empty");
        return hasState(note, "NoteOn")
                && midiVelocity(note.velocity()) == e.velocity
                && Math.abs(note.duration() - e.duration) <= 1e-9;
    }

    /** Machine-actionable flags only; constant explanations live in the catalog and PROTOCOL.md. list=false omits the note listing. */
    private JsonObject notesResult(String id, int index, Window w, boolean list) {
        JsonObject out = base(id, index);
        out.add("window", w.json());
        addClipMetadata(out);
        out.addProperty("gridGeneration", bindingGeneration);
        out.addProperty("gridReadiness", "scheduled_settling_not_acknowledged");
        JsonArray notes = new JsonArray();
        JsonArray sustains = new JsonArray();
        int total = 0;
        for (int x = 0; x < w.steps; x++)
            for (int pitch = w.pitch; pitch < w.pitch + w.pitches; pitch++) {
                NoteStep note = clip.getStep(w.channel, x, pitch);
                if (note == null || note.state() == null)
                    throw Errors.notApplied(Reason.NOT_READY, "Note grid is not readable");
                if (hasState(note, "NoteOn")) {
                    total++;
                    if (list && notes.size() < NOTE_LIMIT) notes.add(noteJson(note, w, x, pitch));
                } else if (list && hasState(note, "NoteSustain") && x == 0) {
                    JsonObject row = noteJson(note, w, x, pitch);
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
        double loopEnd = clip.getLoopStart().get() + clip.getLoopLength().get(),
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

    /** MIDI velocity 1..127, the unit notes.edit takes; the host reports 0..1. */
    static int midiVelocity(double normalized) {
        return Math.max(1, Math.min(127, (int) Math.round(normalized * 127)));
    }

    /** One onset row in the units notes.edit accepts; channel is the window's, step is beat/stepSizeBeats. */
    static JsonObject noteJson(NoteStep note, Window w, int x, int pitch) {
        JsonObject out = new JsonObject();
        out.addProperty("beat", (w.start + x) * w.size);
        out.addProperty("pitch", pitch);
        out.addProperty("velocity", midiVelocity(note.velocity()));
        out.addProperty("durationBeats", note.duration());
        return out;
    }

    /** A cell as read back after an edit: an onset row, or just the cell with its non-onset state. */
    static JsonObject observedJson(NoteStep note, Window w, int x, int pitch) {
        if (hasState(note, "NoteOn")) return noteJson(note, w, x, pitch);
        JsonObject out = new JsonObject();
        out.addProperty("beat", (w.start + x) * w.size);
        out.addProperty("pitch", pitch);
        out.addProperty("state", note.state().name());
        return out;
    }

    private void awaitPlayback(HostOperation op, String id, Track track, Slot slot, int index, long end) {
        BooleanSupplier observed = () ->
                slot == null ? track.isStopped().get() : slot.api.isPlaying().get();
        BooleanSupplier queued = () -> slot == null
                ? track.isQueuedForStop().get()
                : slot.api.isPlaybackQueued().get();
        scheduler.poll(
                op,
                end,
                () -> {
                    guard(op, id, track);
                    return observed.getAsBoolean() || queued.getAsBoolean();
                },
                done -> finishPlayback(op, id, slot, index, observed.getAsBoolean(), queued.getAsBoolean()));
    }

    private void finishPlayback(HostOperation op, String id, Slot slot, int index, boolean observed, boolean queued) {
        JsonObject out = written(id, index, observed ? Outcome.APPLIED : queued ? Outcome.SENT : Outcome.UNKNOWN);
        if (!observed && !queued) out.addProperty("reason", Reason.NOT_OBSERVED.wire());
        out.addProperty(
                "playbackState", observed ? slot == null ? "stopped" : "playing" : queued ? "queued" : "not_observed");
        if (slot != null) out.add("slot", slotJson(slot, index));
        op.result.complete(out);
    }

    private void guard(HostOperation op, String id, Track track) {
        scheduler.check(op);
        if (op.result.isDone()) throw Errors.of(Reason.CANCELLED, "Operation is no longer active", op.dispatched());
        if (selection.ownedBy(op) && cursorMutation != null && cursorMutation.rebound)
            throw Errors.unknown(
                    Reason.STALE_CONTEXT,
                    cursorMutation.description()
                            + " Its target binding changed; the mutation stays held until acked after inspection.");
        if (!window.trackMatches(id, track)
                || track.clipLauncherSlotBank().scrollPosition().get() != 0)
            throw Errors.of(Reason.STALE_CONTEXT, "Explicit track or launcher bank changed", op.dispatched());
    }
    /** Whether the launcher cursor clip is this slot's clip; null until the cursor identity has been observed. Read-only. */
    private Boolean selected(Slot slot, String trackId) {
        if (!slot.equalReady || !clipExistsReady) return null;
        return slot.equal.get()
                && clip.exists().get()
                && slot.api.hasContent().get()
                && clipTrack.exists().get()
                && trackId.equals(clipTrack.channelId().get());
    }

    /** For context.get: occupied slots of one track, without touching selection; null when unobserved. */
    JsonArray occupiedSlots(int t, Track track) {
        ClipLauncherSlotBank bank = track.clipLauncherSlotBank();
        if (bank.scrollPosition().get() != 0) return null;
        JsonArray out = new JsonArray();
        for (int j = 0; j < Math.min(HostLimits.SCENES, bank.itemCount().get()); j++) {
            Slot slot = slots[t][j];
            if (!slot.ready()) return null;
            if (!slot.api.hasContent().get()) continue;
            JsonObject e = new JsonObject();
            e.addProperty("slotIndex", j);
            e.addProperty("name", slot.api.name().get());
            e.addProperty("playing", slot.api.isPlaying().get());
            out.add(e);
        }
        return out;
    }

    String slotName(int t, int j) {
        return slots[t][j].api.name().get();
    }

    /** Slot index of the cursor clip on track t, -1 when none, -2 when not yet observable. */
    int cursorSlotIndex(int t, Track track) {
        if (track.clipLauncherSlotBank().scrollPosition().get() != 0) return -2;
        if (!clipExistsReady
                || !clipTrack.exists().get()
                || !window.trackMatches(clipTrack.channelId().get(), track)) return -1;
        for (int j = 0; j < HostLimits.SCENES; j++) {
            Boolean sel = selected(slots[t][j], track.channelId().get());
            if (sel == null) return -2;
            if (sel) return j;
        }
        return -1;
    }

    private boolean bound(String id, Slot slot) {
        return slot.equalReady
                && slot.equal.get()
                && clipExistsReady
                && clip.exists().get()
                && lengthReady
                && clipTrack.exists().get()
                && id.equals(clipTrack.channelId().get())
                && slot.api.hasContent().get();
    }

    private void cursorGuard(HostOperation op, String id, Track track, Slot slot) {
        guard(op, id, track);
        if (!selection.ownedBy(op) || !bound(id, slot))
            throw Errors.of(
                    Reason.STALE_CONTEXT, "Launcher cursor no longer proxies the explicit slot", op.dispatched());
    }

    private static void ready(Slot slot) {
        if (!slot.ready()) throw Errors.notApplied(Reason.NOT_READY, "Launcher slot metadata has not been observed");
    }

    private static void requireContent(Slot slot) {
        if (!slot.api.hasContent().get())
            throw Errors.notApplied(Reason.TARGET_NOT_FOUND, "Explicit launcher slot is empty");
    }

    private long until(HostOperation op) {
        return scheduler.observeUntil(op);
    }

    private static JsonObject base(String id, int slot) {
        JsonObject out = new JsonObject();
        out.addProperty("trackId", id);
        if (slot >= 0) out.addProperty("slotIndex", slot);
        return out;
    }

    private static JsonObject written(String id, int slot, Outcome outcome) {
        JsonObject out = base(id, slot);
        out.addProperty("outcome", outcome.wire());
        return out;
    }

    /** An unknown result whose slot stays held until the agent inspects it and acks the hold. */
    private JsonObject unresolved(JsonObject out, Reason reason, String message) {
        out.addProperty("outcome", Outcome.UNKNOWN.wire());
        out.addProperty("reason", reason.wire());
        out.addProperty("message", message);
        out.addProperty(
                "next",
                holds.blocked(LauncherStructureLock.MUTATION, message, "Inspect launcher.list and notes.read").next);
        return out;
    }

    private void addClipMetadata(JsonObject out) {
        out.addProperty("loopLengthBeats", clip.getLoopLength().get());
        out.addProperty("loopStartBeats", clip.getLoopStart().get());
        out.addProperty("playStartBeats", clip.getPlayStart().get());
        out.addProperty("playStopBeats", clip.getPlayStop().get());
        out.addProperty("loopEnabled", clip.isLoopEnabled().get());
    }

    private JsonObject slotJson(Slot slot, int index) {
        return slotJson(slot, index, null);
    }

    private JsonObject slotJson(Slot slot, int index, String selectedFor) {
        JsonObject out = new JsonObject();
        out.addProperty("slotIndex", index);
        if (selectedFor != null) {
            Boolean sel = selected(slot, selectedFor);
            out.add("selected", sel == null ? JsonNull.INSTANCE : new JsonPrimitive(sel));
        }
        out.addProperty("hasContent", slot.api.hasContent().get());
        out.addProperty("name", slot.api.name().get());
        out.addProperty("playing", slot.api.isPlaying().get());
        out.addProperty("queued", slot.api.isPlaybackQueued().get());
        out.addProperty("recording", slot.api.isRecording().get());
        return out;
    }

    static final class Window {
        final int channel, start, steps, pitch, pitches;
        final double size;
        final BigDecimal exact;
        final boolean derived;

        Window(JsonObject p) {
            channel = p.has("channel") ? Params.integer(p, "channel", 0, 15) : 0;
            size = Params.number(p, "stepSizeBeats", BEAT_GRID, 4);
            exact = p.get("stepSizeBeats").getAsBigDecimal();
            derived = p.has("windowDerived");
            start = Params.integer(p, "startStep", 0, 1048576);
            steps = Params.integer(p, "steps", 1, 128);
            pitch = Params.integer(p, "pitchStart", 0, 127);
            pitches = Params.integer(p, "pitchCount", 1, 128);
            if (pitch + pitches > 128) throw Errors.invalid("pitchStart + pitchCount must not exceed 128");
        }

        JsonObject json() {
            JsonObject out = new JsonObject();
            out.addProperty("channel", channel);
            out.addProperty("stepSizeBeats", size);
            out.addProperty("startStep", start);
            out.addProperty("steps", steps);
            out.addProperty("pitchStart", pitch);
            out.addProperty("pitchCount", pitches);
            if (derived) out.addProperty("derived", true);
            out.addProperty("startBeat", start * size);
            out.addProperty("endBeatExclusive", (start + steps) * size);
            return out;
        }
    }

    static final class Edit {
        final int x, pitch, velocity, durationSteps;
        final double duration;
        final boolean set;
        long before, generation;
        boolean alreadyMatched, verified;

        Edit(int x, int pitch, int velocity, double duration, int durationSteps, boolean set) {
            this.x = x;
            this.pitch = pitch;
            this.velocity = velocity;
            this.duration = duration;
            this.durationSteps = durationSteps;
            this.set = set;
        }
    }

    private static final class Mutation {
        final String trackId, method;
        final Track track;
        final Slot slot;
        final int index;
        final long contentBefore;
        boolean rebound;

        Mutation(String trackId, Track track, Slot slot, int index, String method) {
            this.trackId = trackId;
            this.track = track;
            this.slot = slot;
            this.index = index;
            this.method = method;
            contentBefore = slot.contentVersion;
        }

        String description() {
            return "A prior " + method + " was dispatched for trackId=" + trackId + ", slotIndex=" + index
                    + (rebound ? "; its native target binding changed." : ".");
        }
    }

    private static final class GridObservation {
        final long generation;
        final String trackId;
        final Slot slot;
        final Window window;

        GridObservation(long generation, String trackId, Slot slot, Window window) {
            this.generation = generation;
            this.trackId = trackId;
            this.slot = slot;
            this.window = window;
        }
    }

    private static final class Slot {
        final ClipLauncherSlot api;
        final BooleanValue equal;
        boolean contentReady, nameReady, playingReady, queuedReady, recordingReady, equalReady;
        boolean observedContent;
        long contentVersion;

        Slot(ClipLauncherSlot api, ClipLauncherSlot cursor) {
            this.api = api;
            equal = cursor.createEqualsValue(api);
            equal.addValueObserver((BooleanValueChangedCallback) value -> equalReady = true);
            api.hasContent().addValueObserver((BooleanValueChangedCallback) value -> {
                contentReady = true;
                observedContent = value;
                contentVersion++;
            });
            api.name().addValueObserver((StringValueChangedCallback) value -> nameReady = true);
            api.isPlaying().addValueObserver((BooleanValueChangedCallback) value -> playingReady = true);
            api.isPlaybackQueued().addValueObserver((BooleanValueChangedCallback) value -> queuedReady = true);
            api.isRecording().addValueObserver((BooleanValueChangedCallback) value -> recordingReady = true);
        }

        boolean ready() {
            return contentReady && nameReady && playingReady && queuedReady && recordingReady;
        }
    }
}
