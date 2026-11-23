package dev.bitwigagent;

import com.bitwig.extension.controller.api.*;
import com.bitwig.extension.callback.*;
import com.google.gson.*;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Explicit launcher targets; cursor leases and unresolved native mutations have separate lifetimes. */
final class LauncherModule implements HostModule {
    static final Set<String> METHODS = Set.of("launcher.list", "launcher.create", "launcher.import", "launcher.launch", "launcher.stop", "notes.read", "notes.edit", "scenes.append");
    // channel defaults to 0; the five grid fields are all-or-none (omitted: window derived from the edits or the clip loop).
    private static final Set<String> WINDOW_OPTIONAL = Set.of("channel", "stepSizeBeats", "startStep", "steps", "pitchStart", "pitchCount");
    private static final Set<String> GRID_FIELDS = Set.of("stepSizeBeats", "startStep", "steps", "pitchStart", "pitchCount");
    private static final BigDecimal[] DERIVED_GRIDS = {BigDecimal.ONE, new BigDecimal("0.5"), new BigDecimal("0.25"), new BigDecimal("0.125"),
        new BigDecimal("0.0625"), new BigDecimal("0.03125"), new BigDecimal("0.015625"), new BigDecimal("0.0078125")};
    // Bitwig 6.1.3 debounces step-size grid regeneration by 100ms before queuing controller delivery.
    // This margin avoids consuming that known old cache; it is not a configuration acknowledgement.
    private static final int NOTE_GRID_SETTLE_MS = 150;
    private final HostServices s;
    private final Clip clip;
    private final Track clipTrack;
    private final Slot[][] slots = new Slot[HostServices.TRACKS][HostServices.SCENES];
    private final boolean[] stopReady = new boolean[HostServices.TRACKS];
    private final long[][] noteVersions = new long[128][128];
    private final long[][] noteGenerations = new long[128][128];
    private long sequence, bindingGeneration;
    private GridObservation gridObservation;
    private HostOperation cursorOwner;
    Runnable settingsWriteGuard = () -> {};
    // Only positive completion removes these; completed futures and later callbacks cannot reset quarantine.
    private final List<Mutation> unresolvedMutations = new ArrayList<>();
    private Mutation cursorMutation;
    private boolean clipExistsReady, lengthReady;
    private HostOperation carryOp; // launcher.create result awaiting its note-write stage
    private JsonObject carry;

    LauncherModule(HostServices services) {
        s = services;
        clip = s.host.createLauncherCursorClip(128, 128);
        clipTrack = clip.getTrack();
        clipTrack.channelId().markInterested();
        clipTrack.exists().markInterested();
        clip.exists().addValueObserver((BooleanValueChangedCallback) value -> clipExistsReady = true);
        clip.getLoopLength().addValueObserver((DoubleValueChangedCallback) value -> lengthReady = true);
        clip.getPlayStart().markInterested();
        clip.getPlayStop().markInterested();
        clip.getLoopStart().markInterested();
        clip.isLoopEnabled().markInterested();
        clip.addNoteStepObserver(note -> {
            int c = note.channel(), x = note.x(), y = note.y();
            GridObservation grid = gridObservation;
            if (grid != null && c == grid.window.channel && x >= 0 && x < 128 && y >= 0 && y < 128
                    && bound(grid.trackId, grid.slot)) {
                noteVersions[x][y] = ++sequence;
                noteGenerations[x][y] = grid.generation;
            }
        });
        ClipLauncherSlot cursorSlot = clip.clipLauncherSlot();
        for (int t = 0; t < HostServices.TRACKS; t++) {
            Track track = (Track) s.tracks.getItemAt(t);
            final int index = t;
            track.isStopped().addValueObserver((BooleanValueChangedCallback) value -> stopReady[index] = true);
            track.channelId().addValueObserver((StringValueChangedCallback) value -> {
                for (Mutation mutation : unresolvedMutations)
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
            for (int j = 0; j < HostServices.SCENES; j++) {
                Slot slot = new Slot((ClipLauncherSlot) bank.getItemAt(j), cursorSlot);
                slots[t][j] = slot;
            }
        }
    }

    public Set<String> methods() { return METHODS; }
    public void validate(String method, JsonObject params) { validateParams(method, params); }

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
                Params.fields(p, Set.of("trackId", "slotIndex", "lengthBeats", "name"), Set.of("notes", "channel"));
                int length = Params.integer(p, "lengthBeats", 1, 4096);
                if (Params.string(p, "name").length() > 128) throw invalid("name must contain 1..128 characters");
                if (p.has("notes")) {
                    JsonObject ep = createNotes(p);
                    edits(ep, resolveWindow("notes.edit", ep));
                    for (JsonElement n : ep.getAsJsonArray("edits")) {
                        JsonObject e = n.getAsJsonObject();
                        if (e.get("beat").getAsDouble() + e.get("durationBeats").getAsDouble() > length)
                            throw invalid("notes must fit inside lengthBeats");
                    }
                } else if (p.has("channel")) throw invalid("channel is only valid together with notes");
                break;
            }
            case "scenes.append":
                Params.fields(p, Set.of(), Set.of("count"));
                if (p.has("count")) Params.integer(p, "count", 1, 16);
                return;
            case "notes.read":
            case "notes.edit": {
                Params.fields(p, method.equals("notes.edit") ? Set.of("trackId", "slotIndex", "edits") : Set.of("trackId", "slotIndex"), WINDOW_OPTIONAL);
                Window w = resolveWindow(method, p);
                if (method.equals("notes.edit")) edits(p, w);
                break;
            }
            default: throw HostServices.failure("unknown_method", "Unknown launcher method");
        }
        Params.string(p, "trackId");
        if (p.has("slotIndex")) Params.integer(p, "slotIndex", 0, 15);
    }

    static List<Edit> edits(JsonObject p, Window w) {
        if (!p.get("edits").isJsonArray()) throw invalid("edits must be an array");
        JsonArray array = p.getAsJsonArray("edits");
        if (array.size() < 1 || array.size() > 256) throw invalid("edits must contain 1..256 entries");
        List<Edit> result = new ArrayList<>(array.size());
        Set<Integer> cells = new HashSet<>();
        for (JsonElement value : array) {
            if (!value.isJsonObject()) throw invalid("each edit must be an object");
            JsonObject e = value.getAsJsonObject();
            String op = Params.string(e, "op");
            boolean set = op.equals("set");
            if (!set && !op.equals("clear")) throw invalid("edit op must be set or clear");
            Params.fields(e, set ? Set.of("op", "beat", "pitch", "velocity", "durationBeats") : Set.of("op", "beat", "pitch"), Set.of());
            Params.number(e, "beat", 0, 4194304);
            int absolute = aligned(e.get("beat"), new JsonPrimitive(w.exact), "beat");
            int x = absolute - w.start;
            int pitch = Params.integer(e, "pitch", 0, 127);
            if (x < 0 || x >= w.steps || pitch < w.pitch || pitch >= w.pitch + w.pitches)
                throw invalid("edit onset is outside the explicit window");
            if (!cells.add(x * 128 + pitch)) throw invalid("multiple edits address the same channel/step/pitch cell");
            int velocity = set ? Params.integer(e, "velocity", 1, 127) : 0;
            double duration = set ? Params.number(e, "durationBeats", 1.0 / 128, 4096) : 0;
            int durationSteps = set ? aligned(e.get("durationBeats"), new JsonPrimitive(w.exact), "durationBeats") : 0;
            if (set && (durationSteps < 1 || x + durationSteps > w.steps))
                throw invalid("set duration must fit entirely inside the explicit window");
            result.add(new Edit(x, pitch, velocity, duration, durationSteps, set));
        }
        for (int i = 0; i < result.size(); i++) {
            Edit a = result.get(i);
            if (!a.set) continue;
            for (int j = i + 1; j < result.size(); j++) {
                Edit b = result.get(j);
                if (b.set && a.pitch == b.pitch && a.x < b.x + b.durationSteps && b.x < a.x + a.durationSteps)
                    throw invalid("same-channel/pitch note durations overlap");
            }
        }
        return result;
    }

    static boolean hasGrid(JsonObject p) {
        int present = 0;
        for (String key : GRID_FIELDS) if (p.has(key)) present++;
        if (present != 0 && present != GRID_FIELDS.size())
            throw invalid("stepSizeBeats, startStep, steps, pitchStart and pitchCount must be supplied together or all omitted");
        return present != 0;
    }

    /** Explicit window, or for notes.edit the smallest one derived from the edits; null for a notes.read to be sized from the clip loop. */
    static Window resolveWindow(String method, JsonObject p) {
        if (hasGrid(p)) return new Window(p);
        return method.equals("notes.edit") ? deriveEditWindow(p) : null;
    }

    static void applyWindow(JsonObject p, Window w) {
        p.addProperty("stepSizeBeats", w.exact); p.addProperty("startStep", w.start); p.addProperty("steps", w.steps);
        p.addProperty("pitchStart", w.pitch); p.addProperty("pitchCount", w.pitches);
        p.addProperty("channel", w.channel); p.addProperty("windowDerived", true);
    }

    /** Coarsest power-of-two grid (1..1/128 beat) aligning every beat and duration, i.e. the fewest steps. */
    static Window deriveEditWindow(JsonObject p) {
        if (!p.has("edits") || !p.get("edits").isJsonArray()) throw invalid("edits must be an array");
        JsonArray array = p.getAsJsonArray("edits");
        if (array.size() < 1 || array.size() > 256) throw invalid("edits must contain 1..256 entries");
        List<BigDecimal[]> spans = new ArrayList<>(); // {beat, duration or null}
        int low = 128, high = -1;
        for (JsonElement value : array) {
            if (!value.isJsonObject()) throw invalid("each edit must be an object");
            JsonObject e = value.getAsJsonObject();
            boolean set = Params.string(e, "op").equals("set");
            Params.number(e, "beat", 0, 4194304);
            int pitch = Params.integer(e, "pitch", 0, 127);
            if (set) Params.number(e, "durationBeats", 1.0 / 128, 4096);
            low = Math.min(low, pitch); high = Math.max(high, pitch);
            spans.add(new BigDecimal[]{e.get("beat").getAsBigDecimal(), set ? e.get("durationBeats").getAsBigDecimal() : null});
        }
        BigDecimal grid = null;
        for (BigDecimal candidate : DERIVED_GRIDS) {
            boolean fits = true;
            for (BigDecimal[] span : spans)
                fits &= span[0].remainder(candidate).signum() == 0 && (span[1] == null || span[1].remainder(candidate).signum() == 0);
            if (fits) { grid = candidate; break; }
        }
        if (grid == null) throw invalid("beat and durationBeats must be multiples of 1/128 beat to derive a window; no rounding is performed");
        long first = Long.MAX_VALUE, last = 0;
        for (BigDecimal[] span : spans) {
            long x = span[0].divide(grid).longValueExact();
            first = Math.min(first, x);
            last = Math.max(last, x + (span[1] == null ? 1 : span[1].divide(grid).longValueExact()));
        }
        if (last - first > 128)
            throw invalid("edits span " + (last - first) + " steps on the derived " + grid.stripTrailingZeros().toPlainString()
                + "-beat grid (maximum 128 per call): split them by time range into several calls, or use beats/durations that fit a coarser grid");
        JsonObject w = new JsonObject();
        w.addProperty("stepSizeBeats", grid); w.addProperty("startStep", first); w.addProperty("steps", last - first);
        w.addProperty("pitchStart", low); w.addProperty("pitchCount", high - low + 1);
        if (p.has("channel")) w.add("channel", p.get("channel"));
        return new Window(w);
    }

    /** launcher.create notes are set entries of notes.edit; returns the equivalent notes.edit params with a derived window. */
    static JsonObject createNotes(JsonObject p) {
        if (!p.get("notes").isJsonArray()) throw invalid("notes must be an array");
        JsonObject ep = new JsonObject();
        ep.add("trackId", p.get("trackId")); ep.add("slotIndex", p.get("slotIndex"));
        if (p.has("channel")) ep.add("channel", p.get("channel"));
        JsonArray list = new JsonArray();
        for (JsonElement n : p.getAsJsonArray("notes")) {
            if (!n.isJsonObject()) throw invalid("each note must be an object");
            JsonObject e = n.getAsJsonObject().deepCopy();
            if (e.has("op") && !(e.get("op").isJsonPrimitive() && e.get("op").getAsString().equals("set"))) throw invalid("notes entries must be op=set");
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
        w.addProperty("stepSizeBeats", size); w.addProperty("startStep", (int) Math.floor(start / size + 1e-9));
        w.addProperty("steps", Math.max(1, Math.min(128, (int) Math.ceil(length / size - 1e-9))));
        w.addProperty("pitchStart", 0); w.addProperty("pitchCount", 128);
        if (p.has("channel")) w.add("channel", p.get("channel"));
        applyWindow(p, new Window(w));
    }

    private void appendScenes(HostOperation op, JsonObject p) {
        int count = p.has("count") ? Params.integer(p, "count", 1, 16) : 1;
        if (!s.tracksObserved) throw HostServices.failure("not_ready", "Track bank has not reported its size yet.");
        Track probe = (Track) s.tracks.getItemAt(0);
        if (!probe.exists().get())
            throw HostServices.failure("not_ready", "Scene count is observed through the first track, which does not exist; create a track first");
        ClipLauncherSlotBank bank = probe.clipLauncherSlotBank();
        if (bank.scrollPosition().get() != 0) throw HostServices.failure("stale_context", "Launcher bank is not at scene offset zero");
        int before = bank.itemCount().get();
        if (before + count > HostServices.SCENES)
            throw HostServices.failure("scene_limit", "The project has " + before + " scene(s); the launcher tools address at most "
                + HostServices.SCENES + ", so " + count + " more cannot be appended");
        op.dispatched = true;
        // Project.createScene() is documented to create "a new empty scene as the last scene"; existing slot indices do not move.
        for (int i = 0; i < count; i++) s.host.getProject().createScene();
        awaitScenes(op, bank, before, count, until(op));
    }

    private void awaitScenes(HostOperation op, ClipLauncherSlotBank bank, int before, int count, long end) {
        s.check(op);
        int now = bank.itemCount().get();
        if (now == before + count) {
            JsonObject out = HostServices.observed();
            out.addProperty("status", "observed"); out.addProperty("verification", "scene_count");
            out.addProperty("sceneCountBefore", before); out.addProperty("sceneCount", now);
            out.addProperty("appended", count); out.addProperty("firstNewSlotIndex", before);
            out.addProperty("placement", "last_scene_per_api_documentation; position is not read back, count is");
            op.result.complete(out);
        } else if (System.nanoTime() >= end) {
            throw new RpcException("scene_count_not_observed", "Scene creation was dispatched, but the scene count did not reach " + (before + count)
                + " (observed " + now + "); inspect launcher.list and do not blindly repeat", "unknown");
        } else s.schedule(op, () -> awaitScenes(op, bank, before, count, end), 20);
    }

    static int aligned(JsonElement beat, JsonElement size, String field) {
        BigDecimal[] parts = beat.getAsBigDecimal().divideAndRemainder(size.getAsBigDecimal());
        if (parts[1].signum() != 0) throw invalid(field + " must exactly align with stepSizeBeats; no rounding is performed");
        try { return parts[0].intValueExact(); }
        catch (ArithmeticException error) { throw invalid(field + " exceeds supported grid range"); }
    }

    public void dispatch(HostOperation op, String method, JsonObject p) {
        s.check(op);
        validateParams(method, p);
        p = p.deepCopy();
        if (method.equals("scenes.append")) { appendScenes(op, p); return; }
        if (method.equals("notes.edit") && !hasGrid(p)) applyWindow(p, resolveWindow(method, p));
        String id = Params.string(p, "trackId");
        Track track = s.track(id);
        int t = s.trackSlot(id);
        if (track.clipLauncherSlotBank().scrollPosition().get() != 0)
            throw HostServices.failure("stale_context", "Launcher bank is not at scene offset zero");
        if (method.equals("launcher.list")) {
            JsonObject out = base(id, -1);
            JsonArray entries = new JsonArray();
            int scenes = track.clipLauncherSlotBank().itemCount().get();
            for (int j = 0; j < Math.min(16, scenes); j++) { ready(slots[t][j]); entries.add(slotJson(slots[t][j], j)); }
            out.add("slots", entries);
            out.add("window", HostServices.window(0, 16, scenes));
            op.result.complete(out);
            return;
        }
        if (method.equals("launcher.stop")) {
            if (!stopReady[t]) throw HostServices.failure("not_ready", "Track stop state has not been observed");
            guard(op, id, track);
            op.dispatched = true;
            track.clipLauncherSlotBank().stop();
            awaitPlayback(op, id, track, null, -1, until(op));
            return;
        }
        int j = Params.integer(p, "slotIndex", 0, 15);
        int scenes = track.clipLauncherSlotBank().itemCount().get();
        if (j >= scenes)
            throw HostServices.failure("slot_out_of_range", "slotIndex " + j + " does not exist: the project has " + scenes
                + " scene(s). Append scenes with scenes.append, then re-run launcher.list");
        Slot slot = slots[t][j];
        ready(slot);
        if (method.equals("launcher.launch")) {
            requireContent(slot);
            guard(op, id, track);
            s.requirePlaybackSafe();
            op.dispatched = true;
            slot.api.launch();
            awaitPlayback(op, id, track, slot, j, until(op));
            return;
        }
        acquire(op);
        if (!method.equals("notes.read")) requireResolved(id, slot, j);
        if (method.equals("launcher.create")) {
            if (slot.api.hasContent().get()) throw HostServices.failure("slot_occupied", "Create only accepts an observed empty slot");
            guard(op, id, track);
            beginMutation(id, track, slot, j, method);
            op.dispatched = true;
            slot.api.createEmptyClip(Params.integer(p, "lengthBeats", 1, 4096));
            awaitCreated(op, id, track, slot, j, p, until(op));
        } else if (method.equals("launcher.import")) {
            importFile(op, id, track, slot, j, p);
        } else {
            requireContent(slot);
            bind(op, id, track, slot, j, p, method, until(op));
        }
    }

    private void acquire(HostOperation op) {
        if (cursorOwner != null && !cursorOwner.result.isDone())
            throw HostServices.failure("busy", "Another launcher cursor operation is active; no request was queued");
        cursorOwner = op;
        cursorMutation = null;
    }

    void requireSettingsSafe(String id, int index) {
        if (cursorOwner != null && !cursorOwner.result.isDone())
            throw HostServices.failure("busy", "Launcher editor operation is still pending");
        requireResolved(id, slots[s.trackSlot(id)][index], index);
    }

    private void requireResolved(String id, Slot slot, int index) {
        settingsWriteGuard.run();
        for (Mutation mutation : unresolvedMutations) {
            // Keep both the logical target and its native proxy quarantined across track-bank rebinding.
            if (mutation.slot == slot || mutation.trackId.equals(id) && mutation.index == index)
                throw HostServices.failure("unresolved_mutation", mutation.description()
                        + " Completion remains unresolved; create/import/note writes are quarantined for this extension instance."
                        + " Read-only inspection is allowed; an empty slot or elapsed time does not release quarantine.");
        }
    }

    @Override public List<String> unresolved() {
        List<String> result = new ArrayList<>();
        for (Mutation mutation : unresolvedMutations) result.add(mutation.description());
        return result;
    }

    private void beginMutation(String id, Track track, Slot slot, int index, String method) {
        cursorMutation = new Mutation(id, track, slot, index, method);
        unresolvedMutations.add(cursorMutation);
    }

    private void finishMutation() {
        unresolvedMutations.remove(cursorMutation);
        cursorMutation = null;
    }

    private void invalidateMutations(Track track) {
        for (Mutation mutation : unresolvedMutations)
            if (mutation.track == track) mutation.rebound = true;
    }

    private void importFile(HostOperation op, String id, Track track, Slot slot, int index, JsonObject p) {
        Path path = Params.absoluteFilePath(p, "path");
        if (!Files.isRegularFile(path) || !Files.isReadable(path))
            throw HostServices.failure("file_unavailable", "Import requires a readable regular local file.");
        if (slot.api.hasContent().get())
            throw HostServices.failure("slot_occupied", "Import only accepts an observed empty slot.");
        MidiImportPreflight.validate(path);
        InsertionPoint insertion = slot.api.replaceInsertionPoint();
        // Resolve everything first, then recheck the target, safety and deadline at the mutation boundary.
        guard(op, id, track);
        s.requirePlaybackSafe();
        if (slot.api.hasContent().get())
            throw HostServices.failure("slot_occupied", "Import only accepts an observed empty slot.");
        long before = slot.contentVersion;
        beginMutation(id, track, slot, index, "launcher.import");
        op.dispatched = true;
        insertion.insertFile(path.toString());
        awaitImported(op, id, track, slot, index, before, until(op));
    }

    private void awaitImported(HostOperation op, String id, Track track, Slot slot, int index, long before, long end) {
        guard(op, id, track);
        if (slot.contentVersion > before && slot.observedContent && slot.api.hasContent().get()) {
            JsonObject out = base(id, index);
            out.addProperty("verification", "slot_content_transition");
            out.addProperty("requestedFileVerified", false);
            out.add("slot", slotJson(slot, index));
            finishMutation();
            op.result.complete(out);
        } else if (System.nanoTime() >= end) {
            throw new RpcException("import_not_observed",
                    "File insertion was dispatched, but a fresh slot content transition was not observed; create/import/note writes remain quarantined for this extension instance. Read-only inspection is allowed.", "unknown");
        } else s.schedule(op, () -> awaitImported(op, id, track, slot, index, before, end), 20);
    }

    private void awaitCreated(HostOperation op, String id, Track track, Slot slot, int index, JsonObject p, long end) {
        guard(op, id, track);
        if (slot.contentVersion > cursorMutation.contentBefore && slot.observedContent && slot.api.hasContent().get())
            bind(op, id, track, slot, index, p, "launcher.create", end);
        else if (System.nanoTime() >= end) {
            JsonObject out = base(id, index);
            out.addProperty("status", "dispatched"); out.addProperty("verification", "content_not_observed");
            if (p.has("notes")) out.addProperty("noteWrite", "not_attempted");
            out.addProperty("mutationUnresolved", true);
            out.addProperty("quarantine", "extension_instance");
            out.add("slot", slotJson(slot, index)); op.result.complete(out);
        } else s.schedule(op, () -> awaitCreated(op, id, track, slot, index, p, end), 20);
    }

    private void bind(HostOperation op, String id, Track track, Slot slot, int index, JsonObject p, String method, long end) {
        guard(op, id, track);
        gridObservation = null;
        bindingGeneration++;
        op.dispatched = true;
        slot.api.select();
        s.schedule(op, () -> awaitBinding(op, id, track, slot, index, p, method, end, false), 40);
    }

    private void awaitBinding(HostOperation op, String id, Track track, Slot slot, int index, JsonObject p, String method, long end, boolean settled) {
        guard(op, id, track);
        if (!bound(id, slot)) {
            if (System.nanoTime() >= end) throw new RpcException("target_not_observed", "Cursor slot identity or clip content was not observed", op.dispatched ? "unknown" : "not_applied");
            s.schedule(op, () -> awaitBinding(op, id, track, slot, index, p, method, end, false), 20);
            return;
        }
        if (!settled) {
            if (method.equals("notes.read") && !p.has("stepSizeBeats")) deriveReadWindow(p);
            if (method.startsWith("notes.")) {
                Window w = new Window(p);
                clip.setStepSize(w.size);
                clip.scrollToKey(0);
                clip.scrollToStep(w.start);
            }
            s.schedule(op, () -> awaitBinding(op, id, track, slot, index, p, method, end, true),
                    method.startsWith("notes.") ? NOTE_GRID_SETTLE_MS : 20);
            return;
        }
        // API exposes no grid-configuration acknowledgement. This is settling, not a proven data barrier.
        if (method.startsWith("notes."))
            gridObservation = new GridObservation(bindingGeneration, id, slot, new Window(p));
        if (method.equals("launcher.create")) {
            cursorGuard(op, id, track, slot);
            clip.setName(Params.string(p, "name"));
            // Native createEmptyClip can leave playback stop at its four-beat default.
            clip.getPlayStop().set(Params.integer(p, "lengthBeats", 1, 4096));
            s.schedule(op, () -> awaitName(op, id, track, slot, index, p, end), 20);
        } else if (method.equals("notes.read")) {
            cursorGuard(op, id, track, slot);
            op.result.complete(notesResult(id, index, new Window(p)));
        } else editNotes(op, id, track, slot, index, p);
    }

    private void awaitName(HostOperation op, String id, Track track, Slot slot, int index, JsonObject p, long end) {
        cursorGuard(op, id, track, slot);
        boolean matches = slot.api.name().get().equals(Params.string(p, "name")) && clip.getLoopLength().get() == Params.integer(p, "lengthBeats", 1, 4096)
                && clip.getPlayStop().get() == Params.integer(p, "lengthBeats", 1, 4096);
        if (!matches && System.nanoTime() < end) { s.schedule(op, () -> awaitName(op, id, track, slot, index, p, end), 20); return; }
        JsonObject out = base(id, index);
        out.addProperty("status", matches ? "observed" : "dispatched");
        out.addProperty("verification", matches ? "slot_content_name_length" : "readback_mismatch");
        out.add("slot", slotJson(slot, index));
        addClipMetadata(out);
        if (matches) finishMutation();
        if (matches && p.has("notes")) {
            carryOp = op; carry = out; // clip creation is complete; the note write is an ordinary notes.edit stage
            bind(op, id, track, slot, index, createNotes(p), "notes.edit", until(op));
            return;
        }
        if (!matches) {
            if (p.has("notes")) out.addProperty("noteWrite", "not_attempted");
            out.addProperty("mutationUnresolved", true);
            out.addProperty("quarantine", "extension_instance");
        }
        op.result.complete(out);
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
            op.dispatched = true;
            if (e.set) clip.setStep(w.channel, e.x, e.pitch, e.velocity, e.duration);
            else clip.clearStep(w.channel, e.x, e.pitch);
        }
        s.schedule(op, () -> awaitNotes(op, id, track, slot, index, w, changes, until(op)), 20);
    }

    static void preflight(List<Edit> changes, Window w, Clip clip) {
        double loopStart = clip.getLoopStart().get();
        double end = loopStart + clip.getLoopLength().get();
        for (Edit e : changes) {
            NoteStep old = clip.getStep(w.channel, e.x, e.pitch);
            if (old == null || old.state() == null) throw HostServices.failure("not_ready", "Note grid is not readable");
            if (old.state() == NoteStep$State.NoteSustain) throw HostServices.failure("note_collision", "Edit addresses a sustained note, not its onset");
            double beat = (w.start + e.x) * w.size;
            if (e.set && (beat < loopStart || beat + e.duration > end))
                throw invalid("set note must fit inside the observed clip loop start and end");
            if (!e.set) continue;
            for (int x = e.x + 1; x < e.x + e.durationSteps; x++) {
                NoteStep next = clip.getStep(w.channel, x, e.pitch);
                if (next == null || next.state() == null) throw HostServices.failure("not_ready", "Note grid is not readable");
                if (next.state() == NoteStep$State.NoteOn)
                    throw HostServices.failure("note_collision", "Set duration overlaps an existing unrelated onset; clear it in a separate explicit edit first");
            }
        }
    }

    private void awaitNotes(HostOperation op, String id, Track track, Slot slot, int index, Window w, List<Edit> changes, long end) {
        cursorGuard(op, id, track, slot);
        boolean all = true;
        for (Edit e : changes) {
            boolean fresh = noteGenerations[e.x][e.pitch] == e.generation && noteVersions[e.x][e.pitch] > e.before;
            e.verified = (fresh || e.alreadyMatched) && matches(e, clip.getStep(w.channel, e.x, e.pitch));
            all &= e.verified;
        }
        if (!all && System.nanoTime() < end) { s.schedule(op, () -> awaitNotes(op, id, track, slot, index, w, changes, end), 20); return; }
        JsonObject out = notesResult(id, index, w);
        out.addProperty("status", all ? "observed" : "dispatched");
        out.addProperty("verification", all ? "changed_cells_read_back" : "partial_or_unobserved");
        JsonArray results = new JsonArray();
        for (Edit e : changes) {
            JsonObject item = new JsonObject();
            item.addProperty("op", e.set ? "set" : "clear");
            item.addProperty("step", w.start + e.x); item.addProperty("pitch", e.pitch);
            item.addProperty("verified", e.verified);
            item.addProperty("verification", e.alreadyMatched ? "already_matched_readback"
                    : noteGenerations[e.x][e.pitch] == e.generation && noteVersions[e.x][e.pitch] > e.before ? "observer_after_write" : "not_observed");
            item.add("observed", noteJson(clip.getStep(w.channel, e.x, e.pitch), w, e.x, e.pitch));
            results.add(item);
        }
        out.add("edits", results);
        if (carryOp == op) {
            JsonObject merged = carry; carryOp = null; carry = null;
            merged.addProperty("status", out.get("status").getAsString());
            merged.add("noteWrite", out);
            out = merged;
        }
        op.result.complete(out);
    }

    static boolean matches(Edit e, NoteStep note) {
        if (note == null) return false;
        if (!e.set) return note.state() == NoteStep$State.Empty;
        return note.state() == NoteStep$State.NoteOn && Math.abs(note.velocity() - e.velocity / 127.0) <= 1.0 / 16383 + 1e-9
                && Math.abs(note.duration() - e.duration) <= 1e-9;
    }

    private JsonObject notesResult(String id, int index, Window w) {
        JsonObject out = base(id, index);
        out.add("window", w.json());
        addClipMetadata(out);
        out.addProperty("scope", "single_channel_grid_window");
        out.addProperty("completeClip", false);
        out.addProperty("onsetPrecision", "grid_cell; API does not expose sub-step onset or multiple onsets in one cell");
        out.addProperty("gridGeneration", bindingGeneration);
        out.addProperty("gridReadiness", "scheduled_settling_not_acknowledged");
        out.addProperty("gridSettleMs", NOTE_GRID_SETTLE_MS);
        out.addProperty("gridReadinessLimitation", "Grid configuration follows observed cursor identity, then scheduled settling covers the known 6.1.3 100ms refresh debounce plus delivery margin. Public API has no current grid offset/step-size acknowledgement; this is not a host-data barrier. Delayed same-cell callbacks carry no source-window identity.");
        JsonArray notes = new JsonArray();
        JsonArray sustains = new JsonArray();
        int total = 0;
        for (int x = 0; x < w.steps; x++) for (int pitch = w.pitch; pitch < w.pitch + w.pitches; pitch++) {
            NoteStep note = clip.getStep(w.channel, x, pitch);
            if (note == null || note.state() == null) throw HostServices.failure("not_ready", "Note grid is not readable");
            if (note.state() == NoteStep$State.NoteOn) {
                total++;
                if (notes.size() < 2048) notes.add(noteJson(note, w, x, pitch));
            }
            else if (note.state() == NoteStep$State.NoteSustain && x == 0) sustains.add(noteJson(note, w, x, pitch));
        }
        out.add("notes", notes); out.add("continuingAtWindowStart", sustains);
        out.addProperty("notesInWindow", total);
        out.addProperty("notesReturned", notes.size());
        out.addProperty("completeWindow", total == notes.size());
        out.addProperty("noteResponseLimit", 2048);
        String continuation = null;
        if (total > notes.size()) continuation = "Subdivide steps or pitchCount to read the remaining onset cells; response is bounded below the 1 MiB transport limit";
        double loopEnd = clip.getLoopStart().get() + clip.getLoopLength().get(), windowEnd = (w.start + w.steps) * w.size;
        boolean coversLoop = windowEnd >= loopEnd - 1e-9;
        if (w.derived) {
            out.addProperty("windowCoversLoop", coversLoop);
            if (!coversLoop) continuation = (continuation == null ? "" : continuation + ". ") + "The clip loop continues past this window and a coarser grid would drop below 1/16 note: read the rest with an explicit window, startStep="
                + (w.start + w.steps) + " stepSizeBeats=" + w.exact.stripTrailingZeros().toPlainString() + " steps=128 pitchStart=0 pitchCount=128";
        }
        if (continuation != null) out.addProperty("continuation", continuation);
        return out;
    }

    static JsonObject noteJson(NoteStep note, Window w, int x, int pitch) {
        JsonObject out = new JsonObject();
        out.addProperty("channel", w.channel); out.addProperty("step", w.start + x);
        out.addProperty("beat", (w.start + x) * w.size); out.addProperty("pitch", pitch);
        out.addProperty("state", note.state().name());
        if (note.state() != NoteStep$State.Empty) {
            out.addProperty("velocityNormalized", note.velocity());
            out.addProperty("durationBeats", note.duration());
        }
        return out;
    }

    private void awaitPlayback(HostOperation op, String id, Track track, Slot slot, int index, long end) {
        guard(op, id, track);
        boolean observed = slot == null ? track.isStopped().get() : slot.api.isPlaying().get();
        boolean queued = slot == null ? track.isQueuedForStop().get() : slot.api.isPlaybackQueued().get();
        if (!observed && !queued && System.nanoTime() < end) { s.schedule(op, () -> awaitPlayback(op, id, track, slot, index, end), 20); return; }
        JsonObject out = base(id, index);
        out.addProperty("status", observed ? "observed" : "dispatched");
        out.addProperty("completion", observed ? "observed" : queued ? "not_observed" : "unknown");
        out.addProperty("playbackState", observed ? slot == null ? "stopped" : "playing" : queued ? "queued" : "not_observed");
        out.addProperty("verification", observed || queued ? "launcher_state" : "not_observed");
        if (slot != null) out.add("slot", slotJson(slot, index));
        op.result.complete(out);
    }

    private void guard(HostOperation op, String id, Track track) {
        s.check(op);
        if (op.result.isDone()) throw new RpcException("cancelled", "Operation is no longer active", op.dispatched ? "unknown" : "not_applied");
        if (cursorOwner == op && cursorMutation != null && cursorMutation.rebound)
            throw new RpcException("stale_context", cursorMutation.description()
                    + " Its target binding changed; the mutation remains quarantined for this extension instance.", "unknown");
        if (!s.trackMatches(id, track) || track.clipLauncherSlotBank().scrollPosition().get() != 0)
            throw new RpcException("stale_context", "Explicit track or launcher bank changed", op.dispatched ? "unknown" : "not_applied");
    }
    private boolean bound(String id, Slot slot) {
        return slot.equalReady && slot.equal.get() && clipExistsReady && clip.exists().get() && lengthReady
                && clipTrack.exists().get() && id.equals(clipTrack.channelId().get()) && slot.api.hasContent().get();
    }
    private void cursorGuard(HostOperation op, String id, Track track, Slot slot) {
        guard(op, id, track);
        if (cursorOwner != op || !bound(id, slot))
            throw new RpcException("stale_context", "Launcher cursor no longer proxies the explicit slot", op.dispatched ? "unknown" : "not_applied");
    }
    private static void ready(Slot slot) {
        if (!slot.ready()) throw HostServices.failure("not_ready", "Launcher slot metadata has not been observed");
    }
    private static void requireContent(Slot slot) {
        if (!slot.api.hasContent().get()) throw HostServices.failure("clip_not_found", "Explicit launcher slot is empty");
    }
    private static long until(HostOperation op) {
        return Math.min(op.deadline - TimeUnit.MILLISECONDS.toNanos(40), System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(750));
    }
    private static RpcException invalid(String message) { return HostServices.failure("invalid_params", message); }
    private static JsonObject base(String id, int slot) {
        JsonObject out = HostServices.observed(); out.addProperty("trackId", id);
        if (slot >= 0) out.addProperty("slotIndex", slot);
        return out;
    }
    private void addClipMetadata(JsonObject out) {
        out.addProperty("loopLengthBeats", clip.getLoopLength().get());
        out.addProperty("loopStartBeats", clip.getLoopStart().get());
        out.addProperty("playStartBeats", clip.getPlayStart().get());
        out.addProperty("playStopBeats", clip.getPlayStop().get());
        out.addProperty("loopEnabled", clip.isLoopEnabled().get());
        out.addProperty("selectionSideEffect", "explicit launcher slot selected in editor");
        out.addProperty("identityVerification", "cursor_slot_proxy_equality_and_track_id");
    }
    private static JsonObject slotJson(Slot slot, int index) {
        JsonObject out = new JsonObject(); out.addProperty("slotIndex", index);
        out.addProperty("hasContent", slot.api.hasContent().get()); out.addProperty("name", slot.api.name().get());
        out.addProperty("playing", slot.api.isPlaying().get()); out.addProperty("queued", slot.api.isPlaybackQueued().get());
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
            size = Params.number(p, "stepSizeBeats", 1.0 / 128, 4);
            exact = p.get("stepSizeBeats").getAsBigDecimal();
            derived = p.has("windowDerived");
            start = Params.integer(p, "startStep", 0, 1048576);
            steps = Params.integer(p, "steps", 1, 128);
            pitch = Params.integer(p, "pitchStart", 0, 127);
            pitches = Params.integer(p, "pitchCount", 1, 128);
            if (pitch + pitches > 128) throw invalid("pitchStart + pitchCount must not exceed 128");
        }
        JsonObject json() {
            JsonObject out = new JsonObject(); out.addProperty("channel", channel); out.addProperty("stepSizeBeats", size);
            out.addProperty("startStep", start); out.addProperty("steps", steps); out.addProperty("pitchStart", pitch); out.addProperty("pitchCount", pitches);
            if (derived) out.addProperty("derived", true);
            out.addProperty("startBeat", start * size); out.addProperty("endBeatExclusive", (start + steps) * size);
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
            this.x = x; this.pitch = pitch; this.velocity = velocity; this.duration = duration; this.durationSteps = durationSteps; this.set = set;
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
            this.trackId = trackId; this.track = track; this.slot = slot; this.index = index; this.method = method;
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
            this.generation = generation; this.trackId = trackId; this.slot = slot; this.window = window;
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
        boolean ready() { return contentReady && nameReady && playingReady && queuedReady && recordingReady; }
    }
}
