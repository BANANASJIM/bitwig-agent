package dev.bitwigagent.arranger;

import com.bitwig.extension.callback.*;
import com.bitwig.extension.controller.api.*;
import com.google.gson.*;
import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.Holds;
import dev.bitwigagent.core.HostLimits;
import dev.bitwigagent.core.HostModule;
import dev.bitwigagent.core.HostOperation;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.core.Refs;
import dev.bitwigagent.core.SafetyObservation;
import dev.bitwigagent.core.Scheduler;
import dev.bitwigagent.host.NativeRuntime;
import dev.bitwigagent.host.ProjectState;
import dev.bitwigagent.host.TrackRefs;
import dev.bitwigagent.host.TrackWindow;
import dev.bitwigagent.host.TransportSafety;
import dev.bitwigagent.launcher.LauncherState;
import dev.bitwigagent.launcher.NoteGrid;
import dev.bitwigagent.nativehost.NativeArrangerAccess;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.function.Consumer;

/** Arranger timeline clips on a track's main clip lane through exact version-gated native bindings. */
public final class ArrangerModule implements HostModule {
    static final Set<String> METHODS = Set.of("arranger.list");

    public interface Access {
        void execute(Runnable work);

        JsonObject list(Object trackProxy, String trackId, Runnable check);

        JsonObject create(
                Object trackProxy,
                String trackId,
                double clipStart,
                double clipLength,
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
                double clipStart,
                Runnable check,
                Runnable beforeWrite);

        /** The clip {@code ref} names, located by start and name; {@code op} is move, resize, rename or delete. */
        JsonObject edit(
                Object trackProxy, JsonObject ref, String op, JsonObject args, Runnable check, Runnable beforeWrite);
    }

    /** One native call on a resolved lane. */
    private interface Call {
        JsonObject run(Access bridge, Object track, String id, Runnable check, Runnable beforeWrite);
    }

    private final Scheduler scheduler;
    private final TrackWindow window;
    private final TrackRefs trackRefs;
    private final ProjectState projectState;
    private final TransportSafety transportSafety;
    private final NativeRuntime natives;
    private final Holds holds;
    private final AtomicLongArray epochs = new AtomicLongArray(HostLimits.TRACKS);
    private Access access;
    private static final String PENDING = "arranger.pending", QUARANTINE = "arranger.quarantine";

    public ArrangerModule(
            Scheduler scheduler,
            TrackWindow window,
            TrackRefs trackRefs,
            ProjectState projectState,
            TransportSafety transportSafety,
            NativeRuntime natives,
            Holds holds,
            Access access) {
        this.scheduler = scheduler;
        this.window = window;
        this.trackRefs = trackRefs;
        this.projectState = projectState;
        this.transportSafety = transportSafety;
        this.natives = natives;
        this.holds = holds;
        this.access = access;
        for (int i = 0; i < HostLimits.TRACKS; i++) {
            final int index = i;
            Track track = window.at(i);
            track.channelId().addValueObserver((StringValueChangedCallback) v -> epochs.incrementAndGet(index));
            track.exists().addValueObserver((BooleanValueChangedCallback) v -> epochs.incrementAndGet(index));
        }
    }

    @Override
    public Set<String> methods() {
        return METHODS;
    }

    @Override
    public void validate(String method, JsonObject p) {}

    /** Notes are {beat, pitch, velocity, duration}; beats are relative to the clip start, on a 1/128-beat grid. */
    public static List<double[]> notes(JsonArray array, double length) {
        NoteGrid.requireNoteRules(array, BigDecimal.valueOf(length));
        List<double[]> out = new ArrayList<>();
        for (JsonElement item : array) {
            JsonObject n = item.getAsJsonObject();
            out.add(new double[] {
                n.get("beat").getAsDouble(),
                n.get("pitch").getAsInt(),
                n.get("velocity").getAsInt(),
                n.get("duration").getAsDouble()
            });
        }
        return out;
    }

    @Override
    public void dispatch(HostOperation op, String method, JsonObject p) {
        scheduler.check(op);
        JsonObject target = p.getAsJsonObject("target");
        int index = trackRefs.resolve(target, false);
        JsonObject trackRef = trackRefs.ref(index);
        run(
                op,
                index,
                -1,
                false,
                (bridge, track, id, check, beforeWrite) -> bridge.list(track, id, check),
                result -> op.result.complete(shaped(result, trackRef)));
    }

    /** A new arranger MIDI clip; the track is resolved here. */
    public void create(
            HostOperation op,
            JsonObject trackRef,
            double clipStart,
            double clipLength,
            String name,
            JsonArray notes,
            int channel,
            Consumer<JsonObject> done) {
        int index = trackRefs.resolve(trackRef, false);
        if (!"Instrument".equals(window.at(index).trackType().get()))
            throw Errors.notApplied(
                    Reason.UNSUPPORTED_SCOPE,
                    "clips.insert makes MIDI note clips and needs an instrument track; use clips.copy for audio.");
        List<double[]> list = notes == null ? List.of() : notes(notes, clipLength);
        JsonObject current = trackRefs.ref(index);
        run(
                op,
                index,
                -1,
                true,
                (bridge, track, id, check, beforeWrite) ->
                        bridge.create(track, id, clipStart, clipLength, name, list, channel, check, beforeWrite),
                result -> done.accept(shaped(result, current)));
    }

    /** The launcher clip in {@code source}'s slot, copied onto the same track's timeline. */
    public void copyFromLauncher(
            HostOperation op, LauncherState.Target source, double clipStart, Consumer<JsonObject> done) {
        int index = source.at.track;
        String name = source.slot.api.name().get();
        run(
                op,
                index,
                source.at.offset,
                true,
                (bridge, track, id, check, beforeWrite) ->
                        bridge.copyFromLauncher(track, source.slot.api, id, name, clipStart, check, beforeWrite),
                result -> done.accept(shaped(result, source.at.trackRef)));
    }

    /** An edit of the arranger clip {@code ref} names: move, resize, rename or delete. */
    public void edit(HostOperation op, JsonObject ref, String kind, JsonObject args, Consumer<JsonObject> done) {
        int index = trackRefs.resolve(ref.getAsJsonObject("track"), false);
        JsonObject trackRef = trackRefs.ref(index);
        run(
                op,
                index,
                -1,
                true,
                (bridge, track, id, check, beforeWrite) -> bridge.edit(track, ref, kind, args, check, beforeWrite),
                result -> done.accept(shaped(result, trackRef)));
    }

    /** The access result with arranger clip rows as refs plus values; indexes are no identity. */
    static JsonObject shaped(JsonObject result, JsonObject trackRef) {
        JsonObject out = result.deepCopy();
        out.remove("trackId");
        out.remove("lane");
        if (out.has("clips")) {
            JsonArray rows = new JsonArray();
            for (JsonElement row : out.getAsJsonArray("clips")) rows.add(row(trackRef, row.getAsJsonObject()));
            out.add("clips", rows);
        }
        if (out.has("previous")) out.add("previous", row(trackRef, out.getAsJsonObject("previous")));
        if (out.has("clip") && out.get("clip").isJsonObject()) {
            JsonObject clip = row(trackRef, out.getAsJsonObject("clip"));
            out.add("ref", clip.remove("ref"));
            out.add("clip", clip);
        }
        return out;
    }

    private static JsonObject row(JsonObject trackRef, JsonObject row) {
        JsonObject out = new JsonObject();
        out.add(
                "ref",
                Refs.arrangerClip(
                        trackRef,
                        row.get("start").getAsDouble(),
                        row.get("name").getAsString()));
        for (Map.Entry<String, JsonElement> e : row.entrySet())
            if (!e.getKey().equals("clipIndex")) out.add(e.getKey(), e.getValue());
        return out;
    }

    private void run(HostOperation op, int index, int offset, boolean write, Call call, Consumer<JsonObject> done) {
        if (holds.held(PENDING)) throw Errors.notApplied(Reason.BUSY, "Another native arranger operation is pending.");
        if (write && holds.held(QUARANTINE))
            throw holds.blocked(
                    QUARANTINE,
                    "An earlier arranger clip write has an unknown outcome; arranger writes are refused.",
                    "Inspect arranger.list");
        if (write) projectState.requireReady();
        Track track = window.at(index);
        String id = track.channelId().get();
        if (write) op.inspect = dev.bitwigagent.core.Refs.read("clip:arranger");
        SafetyObservation safety = write ? transportSafety.stopped(op) : null;
        if (access == null) access = NativeArrangerAccess.bind(natives.get(op).access());
        long epoch = epochs.get(index);
        Runnable check = () -> {
            scheduler.check(op);
            if (epochs.get(index) != epoch || offset >= 0 && window.sceneOffset() != offset)
                throw Errors.notApplied(
                        Reason.TARGET_CHANGED,
                        "The track or scene window changed while the arranger operation was queued.");
            if (write) transportSafety.guardStopped(safety);
        };
        Runnable beforeWrite = () -> {
            check.run();
            op.markDispatched();
            holds.place(
                    QUARANTINE, "An arranger clip write on track " + id + " has an unknown outcome until read back.");
        };
        Access bridge = access;
        holds.inFlight(PENDING, "A native arranger operation on track " + id + " is running.");
        try {
            bridge.execute(() -> {
                try {
                    check.run();
                    JsonObject result = call.run(bridge, track, id, check, beforeWrite);
                    scheduler.schedule(
                            op,
                            () -> {
                                check.run();
                                if (!window.trackMatches(id, track))
                                    throw Errors.notApplied(
                                            Reason.TARGET_CHANGED, "Track target changed after native access.");
                                if (write) holds.release(QUARANTINE);
                                holds.release(PENDING);
                                done.accept(Errors.withInspect(result, op.inspect));
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
