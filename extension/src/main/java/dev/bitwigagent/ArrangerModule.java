package dev.bitwigagent;

import com.bitwig.extension.callback.*;
import com.bitwig.extension.controller.api.*;
import com.google.gson.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.atomic.AtomicLongArray;

/** Arranger timeline clips on a track's main clip lane through exact version-gated native bindings. */
final class ArrangerModule implements HostModule {
    static final Set<String> METHODS = Set.of(
            "arranger.clips.list", "arranger.clips.create", "arranger.clips.copyFromLauncher", "arranger.clips.edit");
    static final double MAX_BEATS = 4194304, MAX_LENGTH = 65536, GRID = LauncherModule.BEAT_GRID;
    static final Map<String, Set<String>> EDIT_OPS = Map.of(
            "move",
            Set.of("startBeats"),
            "resize",
            Set.of("lengthBeats"),
            "rename",
            Set.of("name"),
            "delete",
            Set.of());

    interface Access {
        void execute(Runnable work);

        JsonObject list(Object trackProxy, String trackId, Runnable check);

        JsonObject create(
                Object trackProxy,
                String trackId,
                double startBeats,
                double lengthBeats,
                String name,
                List<double[]> notes,
                int channel,
                Runnable check,
                Runnable beforeWrite);

        JsonObject copyFromLauncher(
                Object trackProxy,
                Object slotProxy,
                String trackId,
                String expectedClipName,
                double startBeats,
                Runnable check,
                Runnable beforeWrite);

        JsonObject edit(
                Object trackProxy,
                String trackId,
                int clipIndex,
                double expectedStartBeats,
                String expectedName,
                String op,
                JsonObject args,
                Runnable check,
                Runnable beforeWrite);
    }

    private final Scheduler scheduler;
    private final TrackWindow window;
    private final TransportSafety transportSafety;
    private final NativeRuntime natives;
    private final Holds holds;
    private final AtomicLongArray epochs = new AtomicLongArray(HostLimits.TRACKS);
    private Access access;
    private static final String PENDING = "arranger.pending", QUARANTINE = "arranger.quarantine";
    private static final String HOLD = "An arranger clip write is pending or unresolved.";

    ArrangerModule(
            Scheduler scheduler, TrackWindow window, TransportSafety transportSafety, NativeRuntime natives, Holds holds) {
        this(scheduler, window, transportSafety, natives, holds, null);
    }

    ArrangerModule(
            Scheduler scheduler,
            TrackWindow window,
            TransportSafety transportSafety,
            NativeRuntime natives,
            Holds holds,
            Access access) {
        this.scheduler = scheduler;
        this.window = window;
        this.transportSafety = transportSafety;
        this.natives = natives;
        this.holds = holds;
        this.access = access;
        for (int i = 0; i < HostLimits.TRACKS; i++) {
            final int index = i;
            Track track = window.at(i);
            track.channelId().addValueObserver((StringValueChangedCallback) v -> epochs.incrementAndGet(index));
            track.exists().addValueObserver((BooleanValueChangedCallback) v -> epochs.incrementAndGet(index));
            ClipLauncherSlotBank bank = track.clipLauncherSlotBank();
            bank.scrollPosition().markInterested();
            bank.itemCount().markInterested();
            for (int j = 0; j < HostLimits.SCENES; j++) {
                ClipLauncherSlot slot = (ClipLauncherSlot) bank.getItemAt(j);
                slot.name().markInterested();
                slot.hasContent().markInterested();
                slot.sceneIndex().markInterested();
            }
        }
    }

    @Override
    public Set<String> methods() {
        return METHODS;
    }

    @Override
    public void validate(String method, JsonObject p) {
        validateParams(method, p);
    }

    static void validateParams(String method, JsonObject p) {
        switch (method) {
            case "arranger.clips.list":
                Params.fields(p, Set.of("trackId"), Set.of());
                break;
            case "arranger.clips.create":
                Params.fields(p, Set.of("trackId", "startBeats", "lengthBeats", "name"), Set.of("notes", "channel"));
                Params.number(p, "startBeats", 0, MAX_BEATS);
                double length = Params.number(p, "lengthBeats", GRID, MAX_LENGTH);
                name(p, "name", 1, 128);
                if (p.has("notes")) notes(p, length);
                else if (p.has("channel")) throw Errors.invalid("channel is only valid together with notes");
                if (p.has("channel")) Params.integer(p, "channel", 0, 15);
                break;
            case "arranger.clips.copyFromLauncher":
                Params.fields(p, Set.of("trackId", "slotIndex", "expectedClipName", "startBeats"), Set.of());
                Params.integer(p, "slotIndex", 0, HostLimits.SCENES - 1);
                name(p, "expectedClipName", 1, 256);
                Params.number(p, "startBeats", 0, MAX_BEATS);
                break;
            case "arranger.clips.edit": {
                String op = p.has("op") ? Params.string(p, "op") : "";
                Set<String> payload = EDIT_OPS.get(op);
                if (payload == null) throw Errors.invalid("op must be one of " + new TreeSet<>(EDIT_OPS.keySet()));
                Set<String> required = new HashSet<>(payload);
                required.addAll(Set.of("trackId", "clipIndex", "expectedStartBeats", "expectedName", "op"));
                Params.fields(p, required, Set.of());
                Params.integer(p, "clipIndex", 0, 4095);
                Params.number(p, "expectedStartBeats", 0, MAX_BEATS);
                name(p, "expectedName", 0, 256);
                if (op.equals("move")) Params.number(p, "startBeats", 0, MAX_BEATS);
                if (op.equals("resize")) Params.number(p, "lengthBeats", GRID, MAX_LENGTH);
                if (op.equals("rename")) name(p, "name", 1, 128);
                break;
            }
            default:
                throw Errors.notApplied(ErrorCode.UNKNOWN_METHOD, "Unknown arranger method.");
        }
        if (!DeviceSources.canonicalUuid(Params.string(p, "trackId")))
            throw Errors.invalid("trackId must be a canonical UUID");
    }

    /** UTF-16 length bounds; expectedName may be empty because arranger clips often have no name. */
    private static String name(JsonObject p, String key, int min, int max) {
        JsonElement value = p.get(key);
        if (value == null
                || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isString()) throw Errors.invalid(key + " must be a string");
        String text = value.getAsString();
        if (text.length() < min || text.length() > max)
            throw Errors.invalid(key + " must contain " + min + ".." + max + " characters");
        return text;
    }

    /** Notes are {beat, pitch, velocity, durationBeats}; beats are relative to the clip start, on a 1/128-beat grid. */
    static List<double[]> notes(JsonObject p, double length) {
        JsonElement value = p.get("notes");
        if (!value.isJsonArray()) throw Errors.invalid("notes must be an array");
        JsonArray array = value.getAsJsonArray();
        if (array.size() < 1 || array.size() > 256) throw Errors.invalid("notes must contain 1..256 entries");
        List<double[]> out = new ArrayList<>();
        for (JsonElement item : array) {
            if (!item.isJsonObject()) throw Errors.invalid("each note must be an object");
            JsonObject n = item.getAsJsonObject();
            Params.fields(n, Set.of("beat", "pitch", "velocity", "durationBeats"), Set.of());
            double beat = Params.number(n, "beat", 0, MAX_BEATS);
            int pitch = Params.integer(n, "pitch", 0, 127), velocity = Params.integer(n, "velocity", 1, 127);
            double duration = Params.number(n, "durationBeats", GRID, LauncherModule.MAX_CLIP_BEATS);
            aligned(n.get("beat"), "beat");
            aligned(n.get("durationBeats"), "durationBeats");
            for (double[] o : out) {
                if (o[1] == pitch && o[0] == beat) throw Errors.invalid("duplicate note cell (same beat and pitch)");
                if (o[1] == pitch && beat < o[0] + o[3] && o[0] < beat + duration)
                    throw Errors.invalid("same-pitch note durations overlap");
            }
            if (new BigDecimal(n.get("beat").getAsString())
                            .add(new BigDecimal(n.get("durationBeats").getAsString()))
                            .compareTo(new BigDecimal(p.get("lengthBeats").getAsString()))
                    > 0) throw Errors.invalid("notes must fit inside lengthBeats");
            out.add(new double[] {beat, pitch, velocity, duration});
        }
        return out;
    }

    private static void aligned(JsonElement value, String key) {
        if (new BigDecimal(value.getAsString())
                        .divide(BigDecimal.valueOf(GRID))
                        .stripTrailingZeros()
                        .scale()
                > 0) throw Errors.invalid(key + " must be a multiple of " + LauncherModule.BEAT_GRID_LABEL + " beat");
    }

    @Override
    public void dispatch(HostOperation op, String method, JsonObject p) {
        scheduler.check(op);
        boolean write = !method.equals("arranger.clips.list");
        if (holds.held(PENDING)) throw Errors.notApplied(ErrorCode.BUSY, "Another native arranger operation is pending.");
        if (write && holds.held(QUARANTINE))
            throw Errors.notApplied(
                    ErrorCode.UNRESOLVED_MUTATION,
                    "An unresolved arranger write requires inspection (arranger.clips.list) and extension.restart before further arranger writes.");
        String id = p.get("trackId").getAsString();
        int index = window.trackSlot(id);
        Track track = window.at(index);
        Object slot = null;
        if (method.equals("arranger.clips.create")
                && !"Instrument".equals(track.trackType().get()))
            throw Errors.notApplied(
                    ErrorCode.UNSUPPORTED_SCOPE,
                    "arranger.clips.create makes MIDI note clips and needs an instrument track; use copyFromLauncher for audio.");
        if (method.equals("arranger.clips.copyFromLauncher")) {
            int number = p.get("slotIndex").getAsInt();
            String expected = p.get("expectedClipName").getAsString();
            ClipLauncherSlotBank bank = track.clipLauncherSlotBank();
            if (bank.scrollPosition().get() != 0 || number >= bank.itemCount().get())
                throw Errors.notApplied(
                        ErrorCode.SLOT_OUT_OF_RANGE, "Launcher slot is outside the current zero-offset scene window.");
            ClipLauncherSlot launcherSlot = (ClipLauncherSlot) bank.getItemAt(number);
            if (!launcherSlot.hasContent().get()
                    || !expected.equals(launcherSlot.name().get())
                    || launcherSlot.sceneIndex().get() != number)
                throw Errors.notApplied(
                        ErrorCode.STALE_TARGET,
                        "Observe the exact occupied slot name and index with launcher.list first.");
            slot = launcherSlot;
        }
        SafetyObservation safety = write ? transportSafety.stopped(op) : null;
        if (access == null) {
            try {
                access = new Bitwig614ArrangerAccess(natives.get(op).access(ErrorCode.ARRANGER_UNAVAILABLE));
            } catch (ReflectiveOperationException error) {
                throw Errors.notApplied(
                        ErrorCode.ARRANGER_UNAVAILABLE,
                        "Exact arranger descriptors unavailable: " + error.getMessage());
            }
        }
        long epoch = epochs.get(index);
        Runnable check = () -> {
            scheduler.check(op);
            if (epochs.get(index) != epoch)
                throw Errors.notApplied(
                        ErrorCode.STALE_CONTEXT, "Track bank changed while the arranger operation was queued.");
            if (write) transportSafety.guardStopped(safety);
        };
        Runnable beforeWrite = () -> {
            check.run();
            op.dispatched = true;
            holds.place(QUARANTINE, HOLD);
        };
        Access bridge = access;
        Object launcherSlot = slot;
        holds.place(PENDING, HOLD);
        try {
            bridge.execute(() -> {
                try {
                    check.run();
                    JsonObject result;
                    switch (method) {
                        case "arranger.clips.list":
                            result = bridge.list(track, id, check);
                            break;
                        case "arranger.clips.create": {
                            List<double[]> notes = p.has("notes")
                                    ? notes(p, p.get("lengthBeats").getAsDouble())
                                    : List.of();
                            result = bridge.create(
                                    track,
                                    id,
                                    p.get("startBeats").getAsDouble(),
                                    p.get("lengthBeats").getAsDouble(),
                                    p.get("name").getAsString(),
                                    notes,
                                    p.has("channel") ? p.get("channel").getAsInt() : 0,
                                    check,
                                    beforeWrite);
                            break;
                        }
                        case "arranger.clips.copyFromLauncher":
                            result = bridge.copyFromLauncher(
                                    track,
                                    launcherSlot,
                                    id,
                                    p.get("expectedClipName").getAsString(),
                                    p.get("startBeats").getAsDouble(),
                                    check,
                                    beforeWrite);
                            break;
                        default: {
                            JsonObject args = new JsonObject();
                            for (String key : EDIT_OPS.get(p.get("op").getAsString())) args.add(key, p.get(key));
                            result = bridge.edit(
                                    track,
                                    id,
                                    p.get("clipIndex").getAsInt(),
                                    p.get("expectedStartBeats").getAsDouble(),
                                    p.get("expectedName").getAsString(),
                                    p.get("op").getAsString(),
                                    args,
                                    check,
                                    beforeWrite);
                        }
                    }
                    scheduler.schedule(
                            op,
                            () -> {
                                check.run();
                                if (!window.trackMatches(id, track))
                                    throw Errors.notApplied(
                                            ErrorCode.STALE_CONTEXT, "Track target changed after native access.");
                                if (write) holds.release(QUARANTINE);
                                holds.release(PENDING);
                                op.result.complete(result);
                            },
                            0);
                } catch (RuntimeException | Error error) {
                    holds.release(PENDING);
                    op.fail(error);
                }
            });
        } catch (RuntimeException | Error error) {
            holds.release(PENDING);
            throw error;
        }
        op.result.whenComplete((r, e) -> holds.release(PENDING));
    }
}
