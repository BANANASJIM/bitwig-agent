package dev.bitwigagent.nativehost;

import static dev.bitwigagent.arranger.ArrangerNotes.EPS;

import com.google.gson.*;
import dev.bitwigagent.arranger.ArrangerModule;
import dev.bitwigagent.arranger.ArrangerNotes;
import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.Outcome;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.core.Refs;
import dev.bitwigagent.core.Results;
import dev.bitwigagent.core.RpcException;
import dev.bitwigagent.core.ValueMatch;
import dev.bitwigagent.core.Verification;
import dev.bitwigagent.host.NativeReadContext;
import dev.bitwigagent.render.RenderModule;
import java.lang.reflect.Method;
import java.util.*;

/**
 * Exact bindings for the arranger clips on a player track's main clip lane (the note-clip timeline that holds
 * instrument and audio clips). Paths follow Bitwig's own model tests: track {@code track.channel.mainLane()} lane,
 * {@code arranger.clipLane.timeline()} timeline, {@code arranger.noteClip.create(start,length)} new note clip,
 * {@code timeline.insert(event,..)} "Insert Event", {@code timeline.delete(Collection)} "Delete Events",
 * {@code arranger.lane.copyFromLauncher(slots,anchor,time,COPY,..)} "copy launcher clips to arranger". No UI
 * selection is read or passed.
 */
public final class NativeArrangerAccess implements ArrangerModule.Access {
    private final NativeHost.Access a;
    private final NativeReadContext context;
    private final NativeTrackResolver resolver;
    private final Class<?> clipType, slotType, projectType, instrumentClip, audioClip;
    private final Method slotTarget, slotClip, lane, timeline, events, start, length, name, rename;
    private final Method setStart, setLength, insert, delete, newClip, newNote, addNote, noteTimeline, noteRows;
    private final Method rowChannel, rowKey, velocity, copy, arrangeLength, slotLane, clipSlot, begin, end;
    private final NativeClipTiming clipTiming;
    private final Object copyMode;

    /** The exact bindings, or native_unavailable when a descriptor does not match the loaded host. */
    NativeArrangerAccess(NativeHost.Access a) throws ReflectiveOperationException {
        this.a = a;
        context = new NativeReadContext(new NativeReadContextAccess(a));
        resolver = new NativeTrackResolver(a);
        clipType = a.type("clip.event");
        slotType = a.type("launcher.laneSlot");
        projectType = a.type("project");
        instrumentClip = a.type("clip.instrument");
        audioClip = a.type("clip.audio");
        Class<?> laneType = a.type("arranger.lane"), event = a.type("event"), clip = a.type("clip");
        Class<?> noteClip = a.type("arranger.noteClip"), note = a.type("note"), poly = a.type("note.polyTimeline");
        Class<?> selection = a.type("note.selection"), mode = a.type("clip.copyMode");
        slotTarget = a.exactMethod("proxy.controlSurface.target", Object.class, false);
        slotClip = a.exactMethod("proxy.clipSlot.clip", clip, false);
        lane = a.exactMethod("track.channel.mainLane", a.type("arranger.mainLane"), false);
        timeline = a.exactMethod("arranger.clipLane.timeline", a.type("arranger.clipTimeline"), false);
        this.events = a.exactMethod("event.list.events", List.class, false);
        start = a.exactMethod("event.timed.start", double.class, false);
        length = a.exactMethod("event.enduring.length", double.class, false);
        name = a.exactMethod("clip.name", String.class, false);
        rename = a.exactMethod("clip.rename", void.class, false, String.class);
        setStart = a.exactMethod("event.timed.setStart", void.class, false, double.class);
        setLength = a.exactMethod("event.enduring.setLength", void.class, false, double.class);
        insert = a.exactMethod(
                "timeline.insert",
                void.class,
                false,
                event,
                boolean.class,
                boolean.class,
                a.type("timeline.insertContext"));
        delete = a.exactMethod("timeline.delete", void.class, false, Collection.class);
        newClip = a.exactMethod("arranger.noteClip.create", noteClip, true, double.class, double.class);
        newNote = a.exactMethod("note.create", note, true, double.class, double.class, double.class);
        // (note, channel, key, selection): the row factory of note.polyTimeline asserts channel 0..15.
        addNote = a.exactMethod("note.clip.add", void.class, false, note, int.class, int.class, selection);
        noteTimeline = a.exactMethod("note.clipContent.notes", poly, false);
        noteRows = a.exactMethod("note.polyTimeline.rows", List.class, false);
        rowChannel = a.exactMethod("note.keyRow.channel", int.class, false);
        rowKey = a.exactMethod("note.keyRow.key", int.class, false);
        velocity = a.exactMethod("note.event.velocity", double.class, false);
        copy = a.exactMethod(
                "arranger.lane.copyFromLauncher",
                void.class,
                false,
                List.class,
                slotType,
                double.class,
                mode,
                boolean.class,
                selection,
                a.type("async.task"));
        arrangeLength = a.exactMethod("clip.event.arrangeLength", double.class, false);
        slotLane = a.exactMethod("launcher.laneSlot.lane", laneType, false);
        clipSlot = a.exactMethod("clip.slot", a.type("launcher.slot"), false);
        begin = a.exactMethod("project.begin", void.class, false, String.class, boolean.class);
        end = a.exactMethod("project.end", void.class, false, boolean.class);
        clipTiming = new NativeClipTiming(a);
        Object found = null;
        for (Object value : mode.getEnumConstants())
            if (((Enum<?>) value).name().equals("COPY")) found = value;
        if (found == null) throw new NoSuchFieldException("clip.copyMode.COPY absent");
        copyMode = found;
    }

    @Override
    public void execute(Runnable work) {
        a.execute(work);
    }

    /** One resolved main clip lane; every native call re-checks it against the proxy. */
    private final class Lane {
        final Object proxy, track, lane, timeline, project;
        final String trackId;
        final Object[] models;
        final String[] labels = {"timelineDeleted", "laneDeleted", "trackDeleted", "projectDeleted"};
        final NativeReadContext.Capture capture;

        Lane(Object proxy, String trackId, Runnable check) {
            a.documentThread();
            check.run();
            this.proxy = proxy;
            this.trackId = trackId;
            track = resolver.resolve(proxy, trackId, NativeTrackResolver.Kind.CHANNEL);
            lane = a.invoke(NativeArrangerAccess.this.lane, track);
            timeline = lane == null ? null : a.invoke(NativeArrangerAccess.this.timeline, lane);
            if (timeline == null)
                throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "Track has no main arranger clip lane.");
            project = resolver.owner(track, projectType);
            models = new Object[] {timeline, lane, track};
            capture = context.capture(project, models, labels, check, false);
            a.requireModelThread(timeline);
        }

        void guard(Runnable check) {
            check.run();
            a.requireModelThread(timeline);
            context.guard(capture, models, labels, check);
            if (!resolver.same(proxy, track)
                    || a.invoke(NativeArrangerAccess.this.lane, track) != lane
                    || a.invoke(NativeArrangerAccess.this.timeline, lane) != timeline)
                throw Errors.notApplied(Reason.TARGET_CHANGED, "Arranger lane changed during the native operation.");
        }

        List<Object> clips() {
            return new ArrayList<>((List<?>) a.invoke(events, timeline));
        }

        void transaction(String label, Runnable work) {
            a.invoke(begin, project, label, false);
            try {
                work.run();
            } finally {
                a.invoke(end, project, false);
            }
        }
    }

    JsonObject describe(Object clip, int index) {
        JsonObject out = new JsonObject();
        double s = (Double) a.invoke(start, clip), l = (Double) a.invoke(length, clip);
        out.addProperty("clipIndex", index);
        out.addProperty("start", s);
        out.addProperty("length", l);
        out.addProperty("end", s + l);
        out.addProperty("name", (String) a.invoke(name, clip));
        out.addProperty(
                "kind",
                instrumentClip.isInstance(clip) ? "instrument" : audioClip.isInstance(clip) ? "audio" : "other");
        if (clipType.isInstance(clip)) {
            Object time = a.invoke(clipTiming.timing, clip), range = a.invoke(clipTiming.loop, time);
            out.addProperty(
                    "loopEnabled", (Boolean) a.invoke(clipTiming.boolGet, a.invoke(clipTiming.loopEnabled, time)));
            JsonObject loop = new JsonObject();
            loop.addProperty("start", value(a.invoke(clipTiming.position, range)));
            loop.addProperty("length", value(a.invoke(clipTiming.duration, range)));
            out.add("loop", loop);
            out.addProperty("playStart", value(a.invoke(clipTiming.position, a.invoke(clipTiming.playStart, time))));
            out.addProperty("playStop", value(a.invoke(clipTiming.position, a.invoke(clipTiming.playStop, time))));
        }
        return out;
    }

    private double value(Object nativeNumber) {
        return (Double) a.invoke(clipTiming.doubleGet, nativeNumber);
    }

    private JsonObject result(JsonObject out, Lane lane, List<Object> clips) {
        out.addProperty("trackId", lane.trackId);
        out.addProperty("lane", "main_clip_lane");
        JsonArray rows = new JsonArray();
        for (int i = 0; i < clips.size(); i++) rows.add(describe(clips.get(i), i));
        out.add("clips", rows);
        out.addProperty("count", clips.size());
        a.provenance(out);
        out.addProperty("selectionSideEffect", "none");
        return out;
    }

    @Override
    public JsonObject list(Object trackProxy, String trackId, Runnable check) {
        Lane lane = new Lane(trackProxy, trackId, check);
        List<Object> clips = lane.clips();
        lane.guard(check);
        return result(new JsonObject(), lane, clips);
    }

    /** Native clip objects (identity) with their list rows, for callers that diff a lane across a native job. */
    RenderModule.Clips snapshot(Object trackProxy, String trackId, Runnable check) {
        Lane lane = new Lane(trackProxy, trackId, check);
        List<Object> clips = lane.clips();
        JsonArray rows = new JsonArray();
        for (int i = 0; i < clips.size(); i++) rows.add(describe(clips.get(i), i));
        lane.guard(check);
        return new RenderModule.Clips(clips, rows);
    }

    private void requireFree(Lane lane, List<Object> clips, Object except, double from, double to) {
        for (int i = 0; i < clips.size(); i++) {
            Object clip = clips.get(i);
            if (clip == except) continue;
            double s = (Double) a.invoke(start, clip), e = s + (Double) a.invoke(length, clip);
            if (from < e - EPS && s < to - EPS)
                throw Errors.notApplied(
                        Reason.OVERLAP,
                        "Range " + from + ".." + to + " overlaps arranger clip " + i + " (" + s + ".." + e
                                + "); overlapping edits are refused, move or delete that clip first.");
        }
    }

    private Object added(List<Object> before, List<Object> after) {
        Object found = null;
        for (Object clip : after)
            if (!before.contains(clip)) {
                if (found != null)
                    throw Errors.unknown(Reason.READBACK_MISMATCH, "More than one new arranger clip appeared.");
                found = clip;
            }
        if (found == null)
            throw Errors.unknown(Reason.READBACK_MISMATCH, "No new arranger clip was observed after dispatch.");
        if (after.size() != before.size() + 1)
            throw Errors.unknown(Reason.READBACK_MISMATCH, "Existing arranger clips changed during the write.");
        return found;
    }

    private JsonObject written(Lane lane, String op, Object clip, JsonObject previous) {
        return written(Outcome.APPLIED, lane, op, clip, previous);
    }

    private JsonObject written(Outcome outcome, Lane lane, String op, Object clip, JsonObject previous) {
        List<Object> after = lane.clips();
        JsonObject out = result(Results.outcome(outcome, Verification.NATIVE_READBACK), lane, after);
        out.addProperty("op", op);
        int index = clip == null ? -1 : after.indexOf(clip);
        out.add("clip", index < 0 ? JsonNull.INSTANCE : describe(clip, index));
        if (previous != null) out.add("previous", previous);
        out.addProperty("nativeUndo", true);
        out.addProperty("undoGroupingVerified", false);
        return out;
    }

    private static boolean near(double a, double b) {
        return Math.abs(a - b) <= EPS;
    }

    private static RpcException mismatch(String what) {
        return Errors.unknown(Reason.READBACK_MISMATCH, "Native readback differs after dispatch: " + what);
    }

    @Override
    public JsonObject create(
            Object trackProxy,
            String trackId,
            double clipStart,
            double clipLength,
            String clipName,
            List<double[]> notes,
            Runnable check,
            Runnable beforeWrite) {
        Lane lane = new Lane(trackProxy, trackId, check);
        List<Object> before = lane.clips();
        requireFree(lane, before, null, clipStart, clipStart + clipLength);
        lane.guard(check);
        beforeWrite.run();
        Object[] created = new Object[1];
        lane.transaction("Create Arranger Clip", () -> {
            Object clip = a.invoke(newClip, null, clipStart, clipLength);
            created[0] = clip;
            a.invoke(insert, lane.timeline, clip, false, false, null);
            a.invoke(rename, clip, clipName);
            ArrangerNotes.write(noteHost, clip, notes);
        });
        lane.guard(check);
        Object clip = added(before, lane.clips());
        if (clip != created[0]) throw mismatch("the observed clip is not the created clip");
        if (!near((Double) a.invoke(start, clip), clipStart)) throw mismatch("start");
        if (!near((Double) a.invoke(length, clip), clipLength)) throw mismatch("length");
        if (!clipName.equals(a.invoke(name, clip))) throw mismatch("name");
        ArrangerNotes.Readback readback = notes.isEmpty() ? null : ArrangerNotes.verify(noteHost, clip, notes);
        if (readback != null && readback.invalidRows > 0) {
            // Rows outside channel 0..15 / key 0..127 crash Bitwig's note editor when drawn: remove the clip now.
            String cleanup;
            try {
                a.invoke(delete, lane.timeline, new ArrayList<>(List.of(clip)));
                cleanup = lane.clips().contains(clip)
                        ? "deleting the created clip was dispatched but it is still on the lane"
                        : "the created clip was deleted again (readback)";
            } catch (RuntimeException error) {
                cleanup = "deleting the created clip failed: " + error.getMessage();
            }
            throw Errors.unknown(
                    Reason.READBACK_MISMATCH,
                    readback.invalidRows + " native note rows have a channel outside 0..15 or a key"
                            + " outside 0..127; " + cleanup + ". Inspect arranger.list before another write.");
        }
        if (readback != null && !readback.complete)
            throw Errors.unknown(
                    Reason.READBACK_MISMATCH,
                    "Clip was created but "
                            + (notes.size() - readback.json.get("verified").getAsInt())
                            + " requested notes were not read back (clip holds "
                            + readback.json.get("notesInClip").getAsInt() + "); inspect before another write.");
        JsonObject out = written(lane, "create", clip, null);
        // One "Create Arranger Clip" transaction: observed natively as one Undo step for the clip and its notes.
        out.addProperty("undoGroupingVerified", true);
        if (readback != null) out.add("noteWrite", readback.json);
        return out;
    }

    private final ArrangerNotes.Host noteHost = new ArrangerNotes.Host() {
        @Override
        public Object newNote(double beat, double duration, double velocity01) {
            return a.invoke(newNote, null, beat, duration, velocity01);
        }

        @Override
        public void addNote(Object clip, Object note, int channel, int key) {
            a.invoke(NativeArrangerAccess.this.addNote, clip, note, channel, key, null);
        }

        @Override
        public List<?> rows(Object clip) {
            return new ArrayList<>((List<?>) a.invoke(noteRows, a.invoke(noteTimeline, clip)));
        }

        @Override
        public int channel(Object row) {
            return (Integer) a.invoke(rowChannel, row);
        }

        @Override
        public int key(Object row) {
            return (Integer) a.invoke(rowKey, row);
        }

        @Override
        public List<?> notes(Object row) {
            return (List<?>) a.invoke(events, row);
        }

        @Override
        public double start(Object note) {
            return (Double) a.invoke(start, note);
        }

        @Override
        public double length(Object note) {
            return (Double) a.invoke(length, note);
        }

        @Override
        public double velocity01(Object note) {
            return (Double) a.invoke(velocity, note);
        }
    };

    @Override
    public JsonObject copyFromLauncher(
            Object trackProxy,
            Object slotProxy,
            String trackId,
            String expectedClipName,
            double clipStart,
            Runnable check,
            Runnable beforeWrite) {
        Lane lane = new Lane(trackProxy, trackId, check);
        Object slot = a.invoke(slotTarget, slotProxy), source = a.invoke(slotClip, slotProxy);
        if (!slotType.isInstance(slot) || !clipType.isInstance(source) || a.invoke(clipSlot, source) != slot)
            throw Errors.notApplied(
                    Reason.TARGET_CHANGED, "Launcher proxy does not resolve to one attached native clip slot.");
        if (resolver.slotTrack(slot) != lane.track)
            throw Errors.notApplied(Reason.TARGET_CHANGED, "Launcher slot belongs to another native track.");
        if (a.invoke(slotLane, slot) != lane.lane)
            throw Errors.notApplied(Reason.UNSUPPORTED_SCOPE, "Launcher slot is not on the track's main clip lane.");
        if (!expectedClipName.equals(a.invoke(name, source)))
            throw Errors.notApplied(Reason.TARGET_CHANGED, "Native clip name differs from expectedClipName.");
        double expectedLength = (Double) a.invoke(arrangeLength, source);
        List<Object> before = lane.clips();
        requireFree(lane, before, null, clipStart, clipStart + expectedLength);
        lane.guard(check);
        if (a.invoke(slotClip, slotProxy) != source)
            throw Errors.notApplied(Reason.TARGET_CHANGED, "Launcher clip changed before the copy.");
        beforeWrite.run();
        a.invoke(copy, lane.lane, new ArrayList<>(List.of(slot)), slot, clipStart, copyMode, false, null, null);
        lane.guard(check);
        Object clip = added(before, lane.clips());
        if (!near((Double) a.invoke(start, clip), clipStart)) throw mismatch("start");
        if (!near((Double) a.invoke(length, clip), expectedLength))
            throw mismatch("length differs from the clip's arrangement length");
        JsonObject out = written(lane, "copyFromLauncher", clip, null);
        out.addProperty("expectedLength", expectedLength);
        out.addProperty("nativeOperation", "copy_launcher_clips_to_arranger");
        out.addProperty("copyMode", "copy_not_alias");
        return out;
    }

    @Override
    public JsonObject edit(
            Object trackProxy, JsonObject ref, String op, JsonObject args, Runnable check, Runnable beforeWrite) {
        String trackId = ref.getAsJsonObject("track").get("id").getAsString(),
                expectedName = ref.get("name").getAsString();
        double expectedStart = ref.get("start").getAsDouble();
        Lane lane = new Lane(trackProxy, trackId, check);
        List<Object> before = lane.clips();
        int clipIndex = locate(before, ref, expectedStart, expectedName);
        Object clip = before.get(clipIndex);
        double s = (Double) a.invoke(start, clip), l = (Double) a.invoke(length, clip);
        if (args.has("expect")) {
            JsonObject observed = new JsonObject();
            observed.addProperty("name", expectedName);
            observed.addProperty("length", l);
            ValueMatch.requireExpected(
                    args.getAsJsonObject("expect"),
                    observed,
                    Map.of("name", ValueMatch.EXACT, "length", ValueMatch.raw(EPS)));
        }
        JsonObject previous = describe(clip, clipIndex);
        switch (op) {
            case "move": {
                double to = args.get("start").getAsDouble();
                if (near(to, s)) return already(lane, op, clip, previous);
                requireFree(lane, before, clip, to, to + l);
                lane.guard(check);
                beforeWrite.run();
                lane.transaction("Move Arranger Clip", () -> a.invoke(setStart, clip, to));
                lane.guard(check);
                if (!lane.clips().contains(clip)
                        || !near((Double) a.invoke(start, clip), to)
                        || !near((Double) a.invoke(length, clip), l)) throw mismatch("start/length after move");
                return written(lane, op, clip, previous);
            }
            case "resize": {
                double to = args.get("length").getAsDouble();
                if (near(to, l)) return already(lane, op, clip, previous);
                requireFree(lane, before, clip, s, s + to);
                lane.guard(check);
                beforeWrite.run();
                lane.transaction("Resize Arranger Clip", () -> a.invoke(setLength, clip, to));
                lane.guard(check);
                if (!lane.clips().contains(clip)
                        || !near((Double) a.invoke(start, clip), s)
                        || !near((Double) a.invoke(length, clip), to)) throw mismatch("start/length after resize");
                return written(lane, op, clip, previous);
            }
            case "rename": {
                String to = args.get("name").getAsString();
                if (to.equals(expectedName)) return already(lane, op, clip, previous);
                lane.guard(check);
                beforeWrite.run();
                lane.transaction("Rename Arranger Clip", () -> a.invoke(rename, clip, to));
                lane.guard(check);
                if (!to.equals(a.invoke(name, clip))) throw mismatch("name after rename");
                return written(lane, op, clip, previous);
            }
            case "delete": {
                lane.guard(check);
                beforeWrite.run();
                a.invoke(delete, lane.timeline, new ArrayList<>(List.of(clip)));
                lane.guard(check);
                List<Object> after = lane.clips();
                if (after.contains(clip) || after.size() != before.size() - 1)
                    throw mismatch("clip still present or other clips changed");
                JsonObject out = written(lane, op, null, previous);
                out.addProperty("removed", true);
                return out;
            }
            default:
                throw Errors.notApplied(Reason.INVALID_PARAMS, "Unknown arranger op.");
        }
    }

    /** The clip that starts at the ref's start and has its name; another name there is target_changed. */
    private int locate(List<Object> clips, JsonObject ref, double expectedStart, String expectedName) {
        int found = -1;
        for (int i = 0; i < clips.size(); i++) {
            if (!near((Double) a.invoke(start, clips.get(i)), expectedStart)) continue;
            if (expectedName.equals(a.invoke(name, clips.get(i)))) return i;
            if (found < 0) found = i;
        }
        if (found < 0)
            throw Errors.targetNotFound(ref, "No arranger clip starts at " + expectedStart + " on this track.");
        Object clip = clips.get(found);
        throw Errors.targetChanged(
                "Another arranger clip now starts there.",
                Refs.arrangerClip(
                        ref.getAsJsonObject("track"), (Double) a.invoke(start, clip), (String) a.invoke(name, clip)));
    }

    private JsonObject already(Lane lane, String op, Object clip, JsonObject previous) {
        JsonObject out = written(Outcome.ALREADY_MATCHED, lane, op, clip, previous);
        out.addProperty("nativeUndo", false);
        return out;
    }
}
