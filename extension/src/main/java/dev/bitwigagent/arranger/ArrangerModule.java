package dev.bitwigagent.arranger;

import com.bitwig.extension.callback.*;
import com.bitwig.extension.controller.api.*;
import com.google.gson.*;
import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.Holds;
import dev.bitwigagent.core.HostLimits;
import dev.bitwigagent.core.HostModule;
import dev.bitwigagent.core.HostOperation;
import dev.bitwigagent.core.MethodSpecs;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.core.Refs;
import dev.bitwigagent.core.Results;
import dev.bitwigagent.core.Scheduler;
import dev.bitwigagent.host.NativeRuntime;
import dev.bitwigagent.host.ProjectState;
import dev.bitwigagent.host.TrackRefs;
import dev.bitwigagent.host.TrackWindow;
import dev.bitwigagent.host.TransportSafety;
import dev.bitwigagent.launcher.LauncherState;
import dev.bitwigagent.launcher.Note;
import dev.bitwigagent.launcher.NoteFeatures;
import dev.bitwigagent.launcher.NoteModule;
import dev.bitwigagent.nativehost.NativeArrangerAccess;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.function.Consumer;

/** Arranger timeline clips on a track's main clip lane through exact version-gated native bindings. */
public final class ArrangerModule implements HostModule {
    static final Set<String> METHODS = Set.of("arranger.list");
    private static final Set<String> LANE_TYPES = Set.of("Audio", "Instrument", "Hybrid");

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
    private final MarkerModule markers;
    private final Transport transport;
    private final AtomicLongArray epochs = new AtomicLongArray(HostLimits.TRACKS);
    private Access access;
    private volatile String signature;
    private volatile Double tempo;
    private static final String PENDING = "arranger.pending", QUARANTINE = "arranger.quarantine";

    public ArrangerModule(
            Scheduler scheduler,
            TrackWindow window,
            TrackRefs trackRefs,
            ProjectState projectState,
            TransportSafety transportSafety,
            NativeRuntime natives,
            Holds holds,
            MarkerModule markers,
            Transport transport,
            Access access) {
        this.scheduler = scheduler;
        this.window = window;
        this.trackRefs = trackRefs;
        this.projectState = projectState;
        this.transportSafety = transportSafety;
        this.natives = natives;
        this.holds = holds;
        this.markers = markers;
        this.transport = transport;
        this.access = access;
        transport.tempo().value().addRawValueObserver((DoubleValueChangedCallback) v -> tempo = v);
        transport.timeSignature().addValueObserver((StringValueChangedCallback) v -> signature = (String) v);
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

    /**
     * Notes are {beat, pitch, velocity, duration, channel}, canonical; beats are relative to the clip start. Operators
     * and expressions are not written into arranger clips in this version.
     */
    public static List<double[]> notes(JsonArray array, double length) {
        List<double[]> out = new ArrayList<>();
        List<Note> notes = NoteModule.insertNotes(array, BigDecimal.valueOf(length));
        for (JsonElement n : array)
            if (NoteFeatures.named(n.getAsJsonObject()))
                throw Errors.notApplied(
                        Reason.UNSUPPORTED_SCOPE,
                        "notes on an arranger clip take beat, pitch, velocity, duration and channel only: operators"
                                + " and expressions are written into launcher clips in this version");
        for (Note n : notes) {
            out.add(new double[] {n.beatValue(), n.pitch, n.velocity, n.durationValue(), n.channel});
        }
        return out;
    }

    @Override
    public void dispatch(HostOperation op, String method, JsonObject p) {
        scheduler.check(op);
        List<Integer> slots = new ArrayList<>();
        if (p.has("target")) slots.add(trackRefs.resolve(p.getAsJsonObject("target"), false));
        else {
            if (!window.observed())
                throw Errors.notApplied(Reason.NOT_READY, "Track bank has not reported its size yet.");
            for (int i = 0; i < HostLimits.TRACKS; i++)
                if (window.at(i).exists().get()
                        && LANE_TYPES.contains(window.at(i).trackType().get())) slots.add(i);
        }
        int[] indexes = slots.stream().mapToInt(Integer::intValue).toArray();
        JsonObject[] refs = new JsonObject[indexes.length];
        for (int i = 0; i < indexes.length; i++) refs[i] = trackRefs.ref(indexes[i]);
        run(
                op,
                indexes,
                -1,
                false,
                (bridge, track, id, check, beforeWrite) -> bridge.list(track, id, check),
                results -> op.result.complete(timeline(results, refs)));
    }

    /** Every listed lane's rows in track order, each lane sorted by start, with markers, tempo and bar positions. */
    private JsonObject timeline(List<JsonObject> results, JsonObject[] refs) {
        JsonObject out = new JsonObject();
        JsonArray rows = new JsonArray();
        String signature = this.signature;
        String[] parts = signature == null ? null : signature.split("/");
        Double barBeats = parts == null ? null : Integer.parseInt(parts[0]) * 4.0 / Integer.parseInt(parts[1]);
        for (int i = 0; i < results.size(); i++) {
            JsonObject lane = shaped(results.get(i), refs[i]);
            List<JsonObject> clips = new ArrayList<>();
            for (JsonElement row : lane.remove("clips").getAsJsonArray()) clips.add(row.getAsJsonObject());
            clips.sort(Comparator.comparingDouble(c -> c.get("start").getAsDouble()));
            for (JsonObject clip : clips) {
                clip.addProperty(
                        "bar", barBeats == null ? null : 1 + clip.get("start").getAsDouble() / barBeats);
                rows.add(clip);
            }
            for (Map.Entry<String, JsonElement> e : lane.entrySet()) out.add(e.getKey(), e.getValue());
        }
        out.add("clips", rows);
        out.addProperty("count", rows.size());
        JsonObject marked = markers.list();
        out.add("markers", marked.get("markers"));
        out.add("markersComplete", marked.get("observationsComplete"));
        out.addProperty("tempo", tempo);
        out.addProperty("timeSignature", signature);
        out.add("window", Results.window(window.offset(), HostLimits.TRACKS, window.total()));
        return out;
    }

    /** A new arranger MIDI clip; the track is resolved here. */
    public void create(
            HostOperation op,
            JsonObject trackRef,
            double clipStart,
            double clipLength,
            String name,
            JsonArray notes,
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
                new int[] {index},
                -1,
                true,
                (bridge, track, id, check, beforeWrite) ->
                        bridge.create(track, id, clipStart, clipLength, name, list, check, beforeWrite),
                results -> done.accept(shaped(results.get(0), current)));
    }

    /** The launcher clip in {@code source}'s slot, copied onto the same track's timeline. */
    public void copyFromLauncher(
            HostOperation op, LauncherState.Target source, double clipStart, Consumer<JsonObject> done) {
        int index = source.at.track;
        String name = source.slot.api.name().get();
        run(
                op,
                new int[] {index},
                source.at.offset,
                true,
                (bridge, track, id, check, beforeWrite) ->
                        bridge.copyFromLauncher(track, source.slot.api, id, name, clipStart, check, beforeWrite),
                results -> done.accept(shaped(results.get(0), source.at.trackRef)));
    }

    /** An edit of the arranger clip {@code ref} names: move, resize, rename or delete. */
    public void edit(HostOperation op, JsonObject ref, String kind, JsonObject args, Consumer<JsonObject> done) {
        int index = trackRefs.resolve(ref.getAsJsonObject("track"), false);
        JsonObject trackRef = trackRefs.ref(index);
        run(
                op,
                new int[] {index},
                -1,
                true,
                (bridge, track, id, check, beforeWrite) -> bridge.edit(track, ref, kind, args, check, beforeWrite),
                results -> done.accept(shaped(results.get(0), trackRef)));
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

    private void run(
            HostOperation op, int[] indexes, int offset, boolean write, Call call, Consumer<List<JsonObject>> done) {
        if (holds.held(PENDING)) throw Errors.notApplied(Reason.BUSY, "Another native arranger operation is pending.");
        if (write && holds.held(QUARANTINE))
            throw holds.blocked(
                    QUARANTINE,
                    "An earlier arranger clip write has an unknown outcome; arranger writes are refused.",
                    "Inspect arranger.list");
        if (write) projectState.requireReady();
        Track[] tracks = new Track[indexes.length];
        String[] ids = new String[indexes.length];
        long[] epoch = new long[indexes.length];
        for (int i = 0; i < indexes.length; i++) {
            tracks[i] = window.at(indexes[i]);
            ids[i] = tracks[i].channelId().get();
            epoch[i] = epochs.get(indexes[i]);
        }
        String where = ids.length == 1 ? "track " + ids[0] : ids.length + " tracks";
        if (write) op.inspect = dev.bitwigagent.core.Refs.read("clip:arranger");
        Runnable safety = write ? transportSafety.atLeast(op, MethodSpecs.Transport.STOPPED) : null;
        if (access == null) access = NativeArrangerAccess.bind(natives.get(op).access());
        Runnable check = () -> {
            scheduler.check(op);
            boolean moved = offset >= 0 && window.sceneOffset() != offset;
            for (int i = 0; i < indexes.length; i++) moved |= epochs.get(indexes[i]) != epoch[i];
            if (moved)
                throw Errors.notApplied(
                        Reason.TARGET_CHANGED,
                        "The track or scene window changed while the arranger operation was queued.");
            if (write) safety.run();
        };
        Runnable beforeWrite = () -> {
            check.run();
            op.markDispatched();
            holds.place(QUARANTINE, "An arranger clip write on " + where + " has an unknown outcome until read back.");
        };
        Access bridge = access;
        holds.inFlight(PENDING, "A native arranger operation on " + where + " is running.");
        try {
            bridge.execute(() -> {
                try {
                    check.run();
                    List<JsonObject> results = new ArrayList<>();
                    for (int i = 0; i < indexes.length; i++)
                        results.add(Errors.withInspect(
                                call.run(bridge, tracks[i], ids[i], check, beforeWrite), op.inspect));
                    scheduler.schedule(
                            op,
                            () -> {
                                check.run();
                                for (int i = 0; i < indexes.length; i++)
                                    if (!window.trackMatches(ids[i], tracks[i]))
                                        throw Errors.notApplied(
                                                Reason.TARGET_CHANGED, "Track target changed after native access.");
                                if (write) holds.release(QUARANTINE);
                                holds.release(PENDING);
                                done.accept(results);
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
