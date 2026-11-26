package dev.bitwigagent;

import com.google.gson.*;
import java.lang.reflect.Method;
import java.util.*;

/**
 * Exact 6.1.4 bindings for the arranger clips on a player track's main clip lane (the note-clip timeline that
 * holds instrument and audio clips). Paths follow Bitwig's own model tests: track {@code ynF.HVx()} lane,
 * {@code sru.iAj()} timeline, {@code g1d.cfM(start,length)} new note clip, {@code PSn.cfM(event,..)} "Insert Event",
 * {@code PSn.cfM(Collection)} "Delete Events", {@code shO.cfM(slots,anchor,time,COPY,..)} "copy launcher clips to
 * arranger". No UI selection is read or passed.
 */
final class Bitwig614ArrangerAccess implements ArrangerModule.Access {
    static final double EPS = 1e-6;
    private final Bitwig614Runtime.Access a;
    private final NativeReadContext context;
    private final Bitwig614TrackResolver resolver;
    private final Class<?> clipType, slotType, projectType, instrumentClip, audioClip;
    private final Method slotTarget, slotClip, lane, timeline, events, start, length, name, rename;
    private final Method setStart, setLength, insert, delete, newClip, newNote, addNote, noteTimeline, noteRows;
    private final Method rowChannel, rowKey, velocity, copy, arrangeLength, slotLane, clipSlot, begin, end;
    private final Bitwig614ClipTiming clipTiming;
    private final Object copyMode;

    /** The exact bindings, or native_unavailable when a descriptor does not match the loaded host. */
    static Bitwig614ArrangerAccess bind(Bitwig614Runtime.Access a) {
        try {
            return new Bitwig614ArrangerAccess(a);
        } catch (ReflectiveOperationException | LinkageError error) {
            throw Errors.notApplied(
                    Reason.NATIVE_UNAVAILABLE,
                    "Exact arranger bindings unavailable: " + Bitwig614Runtime.diagnostic(error));
        }
    }

    Bitwig614ArrangerAccess(Bitwig614Runtime.Access a) throws ReflectiveOperationException {
        this.a = a;
        context = new NativeReadContext(new Bitwig614ReadContextAccess(a));
        resolver = new Bitwig614TrackResolver(a);
        String proxy = "com.bitwig.flt.control_surface.proxy.", master = "com.bitwig.flt.document.core.master.";
        Class<?> player = resolver.channelType();
        clipType = a.type("zZi");
        slotType = a.type("TGq");
        projectType = a.type(master + "g2L");
        instrumentClip = a.type("kOI");
        audioClip = a.type("Nbl");
        Class<?> laneType = a.type("shO"), mainLane = a.type("Tsu"), noteClips = a.type("VDz"), events = a.type("qIe");
        Class<?> event = a.type("YvI"),
                enduring = a.type("asx"),
                timed = a.type("SMz"),
                clip = a.type("Ob"),
                timelineType = a.type("PSn");
        Class<?> g1d = a.type("g1d"),
                note = a.type("n1"),
                noteClip = a.type("pBi"),
                poly = a.type("YeP"),
                row = a.type("Rke");
        Class<?> selection = a.type("Bz"), mode = a.type("i3D"), async = a.type("com.bitwig.base.async.lBQ");
        slotTarget = a.exactMethod(a.type(proxy + "ControlSurfaceProxy"), "getTarget", Object.class, false);
        slotClip = a.exactMethod(a.type(proxy + "ClipLauncherSlotProxy"), "getClip", clip, false);
        lane = a.exactMethod(player, "HVx", mainLane, false);
        timeline = a.exactMethod(a.type("sru"), "iAj", noteClips, false);
        this.events = a.exactMethod(events, "cfM", List.class, false);
        start = a.exactMethod(timed, "x3t", double.class, false);
        length = a.exactMethod(enduring, "nfq", double.class, false);
        name = a.exactMethod(clip, "dJ_", String.class, false);
        rename = a.exactMethod(clip, "g_", void.class, false, String.class);
        setStart = a.exactMethod(timed, "wGc", void.class, false, double.class);
        setLength = a.exactMethod(enduring, "lYc", void.class, false, double.class);
        insert = a.exactMethod(
                timelineType, "cfM", void.class, false, event, boolean.class, boolean.class, a.type("dR"));
        delete = a.exactMethod(timelineType, "cfM", void.class, false, Collection.class);
        newClip = a.exactMethod(g1d, "cfM", g1d, true, double.class, double.class);
        newNote = a.exactMethod(note, "cfM", note, true, double.class, double.class, double.class);
        // (note, channel, key, selection): the row factory YeP.TMF(channel, key, create) asserts channel 0..15.
        addNote = a.exactMethod(a.type("AGr"), "MRY", void.class, false, note, int.class, int.class, selection);
        noteTimeline = a.exactMethod(noteClip, "MLK", poly, false);
        noteRows = a.exactMethod(poly, "wGc", List.class, false);
        rowChannel = a.exactMethod(row, "hUR", int.class, false);
        rowKey = a.exactMethod(row, "TMF", int.class, false);
        velocity = a.exactMethod(a.type("Roz"), "UNj", double.class, false);
        copy = a.exactMethod(
                laneType,
                "cfM",
                void.class,
                false,
                List.class,
                slotType,
                double.class,
                mode,
                boolean.class,
                selection,
                async);
        arrangeLength = a.exactMethod(clipType, "Mb2", double.class, false);
        slotLane = a.exactMethod(slotType, "z3I", laneType, false);
        clipSlot = a.exactMethod(clip, "kFU", a.type("Us"), false);
        begin = a.exactMethod(projectType, "cfM", void.class, false, String.class, boolean.class);
        end = a.exactMethod(projectType, "cfM", void.class, false, boolean.class);
        clipTiming = new Bitwig614ClipTiming(a);
        Object found = null;
        for (Object value : mode.getEnumConstants())
            if (((Enum<?>) value).name().equals("COPY")) found = value;
        if (found == null) throw new NoSuchFieldException("i3D.COPY absent");
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
            track = resolver.resolve(proxy, trackId, Bitwig614TrackResolver.Kind.CHANNEL);
            lane = a.invoke(Bitwig614ArrangerAccess.this.lane, track);
            timeline = lane == null ? null : a.invoke(Bitwig614ArrangerAccess.this.timeline, lane);
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
                    || a.invoke(Bitwig614ArrangerAccess.this.lane, track) != lane
                    || a.invoke(Bitwig614ArrangerAccess.this.timeline, lane) != timeline)
                throw Errors.notApplied(Reason.STALE_CONTEXT, "Arranger lane changed during the native operation.");
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
        out.addProperty("startBeats", s);
        out.addProperty("lengthBeats", l);
        out.addProperty("endBeats", s + l);
        out.addProperty("name", (String) a.invoke(name, clip));
        out.addProperty(
                "kind",
                instrumentClip.isInstance(clip) ? "instrument" : audioClip.isInstance(clip) ? "audio" : "other");
        if (clipType.isInstance(clip)) {
            Object time = a.invoke(clipTiming.timing, clip), range = a.invoke(clipTiming.loop, time);
            out.addProperty(
                    "loopEnabled", (Boolean) a.invoke(clipTiming.boolGet, a.invoke(clipTiming.loopEnabled, time)));
            out.addProperty("loopStartBeats", value(a.invoke(clipTiming.position, range)));
            out.addProperty("loopLengthBeats", value(a.invoke(clipTiming.duration, range)));
            out.addProperty(
                    "playStartBeats", value(a.invoke(clipTiming.position, a.invoke(clipTiming.playStart, time))));
            out.addProperty("playStopBeats", value(a.invoke(clipTiming.position, a.invoke(clipTiming.playStop, time))));
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
        out.addProperty("nativeVersion", Bitwig614Runtime.VERSION);
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
        JsonObject out = result(Results.outcome(outcome), lane, after);
        out.addProperty("op", op);
        int index = clip == null ? -1 : after.indexOf(clip);
        out.add("clip", index < 0 ? JsonNull.INSTANCE : describe(clip, index));
        if (previous != null) out.add("previous", previous);
        out.addProperty("verification", "native_timeline_readback");
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
            double startBeats,
            double lengthBeats,
            String clipName,
            List<double[]> notes,
            int channel,
            Runnable check,
            Runnable beforeWrite) {
        Lane lane = new Lane(trackProxy, trackId, check);
        List<Object> before = lane.clips();
        requireFree(lane, before, null, startBeats, startBeats + lengthBeats);
        lane.guard(check);
        beforeWrite.run();
        Object[] created = new Object[1];
        lane.transaction("Create Arranger Clip", () -> {
            Object clip = a.invoke(newClip, null, startBeats, lengthBeats);
            created[0] = clip;
            a.invoke(insert, lane.timeline, clip, false, false, null);
            a.invoke(rename, clip, clipName);
            ArrangerNotes.write(noteHost, clip, notes, channel);
        });
        lane.guard(check);
        Object clip = added(before, lane.clips());
        if (clip != created[0]) throw mismatch("the observed clip is not the created clip");
        if (!near((Double) a.invoke(start, clip), startBeats)) throw mismatch("startBeats");
        if (!near((Double) a.invoke(length, clip), lengthBeats)) throw mismatch("lengthBeats");
        if (!clipName.equals(a.invoke(name, clip))) throw mismatch("name");
        ArrangerNotes.Readback readback = notes.isEmpty() ? null : ArrangerNotes.verify(noteHost, clip, notes, channel);
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
                            + " outside 0..127; " + cleanup + ". Inspect arranger.clips.list before another write.");
        }
        if (readback != null && !readback.complete)
            throw Errors.unknown(
                    Reason.READBACK_MISMATCH,
                    "Clip was created but "
                            + (notes.size() - readback.json.get("verified").getAsInt())
                            + " requested notes were not read back (clip holds "
                            + readback.json.get("notesInClip").getAsInt() + "); inspect before another write.");
        JsonObject out = written(lane, "create", clip, null);
        if (readback != null) out.add("noteWrite", readback.json);
        return out;
    }

    private final ArrangerNotes.Host noteHost = new ArrangerNotes.Host() {
        @Override
        public Object newNote(double beat, double durationBeats, double velocity01) {
            return a.invoke(newNote, null, beat, durationBeats, velocity01);
        }

        @Override
        public void addNote(Object clip, Object note, int channel, int key) {
            a.invoke(Bitwig614ArrangerAccess.this.addNote, clip, note, channel, key, null);
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
            double startBeats,
            Runnable check,
            Runnable beforeWrite) {
        Lane lane = new Lane(trackProxy, trackId, check);
        Object slot = a.invoke(slotTarget, slotProxy), source = a.invoke(slotClip, slotProxy);
        if (!slotType.isInstance(slot) || !clipType.isInstance(source) || a.invoke(clipSlot, source) != slot)
            throw Errors.notApplied(
                    Reason.STALE_CONTEXT, "Launcher proxy does not resolve to one attached native clip slot.");
        if (resolver.slotTrack(slot) != lane.track)
            throw Errors.notApplied(Reason.STALE_CONTEXT, "Launcher slot belongs to another native track.");
        if (a.invoke(slotLane, slot) != lane.lane)
            throw Errors.notApplied(Reason.UNSUPPORTED_SCOPE, "Launcher slot is not on the track's main clip lane.");
        if (!expectedClipName.equals(a.invoke(name, source)))
            throw Errors.notApplied(Reason.STALE_CONTEXT, "Native clip name differs from expectedClipName.");
        double expectedLength = (Double) a.invoke(arrangeLength, source);
        List<Object> before = lane.clips();
        requireFree(lane, before, null, startBeats, startBeats + expectedLength);
        lane.guard(check);
        if (a.invoke(slotClip, slotProxy) != source)
            throw Errors.notApplied(Reason.STALE_CONTEXT, "Launcher clip changed before the copy.");
        beforeWrite.run();
        a.invoke(copy, lane.lane, new ArrayList<>(List.of(slot)), slot, startBeats, copyMode, false, null, null);
        lane.guard(check);
        Object clip = added(before, lane.clips());
        if (!near((Double) a.invoke(start, clip), startBeats)) throw mismatch("startBeats");
        if (!near((Double) a.invoke(length, clip), expectedLength))
            throw mismatch("lengthBeats differs from the clip's arrangement length");
        JsonObject out = written(lane, "copyFromLauncher", clip, null);
        out.addProperty("expectedLengthBeats", expectedLength);
        out.addProperty("nativeOperation", "copy_launcher_clips_to_arranger");
        out.addProperty("copyMode", "copy_not_alias");
        return out;
    }

    @Override
    public JsonObject edit(
            Object trackProxy,
            String trackId,
            int clipIndex,
            double expectedStart,
            String expectedName,
            String op,
            JsonObject args,
            Runnable check,
            Runnable beforeWrite) {
        Lane lane = new Lane(trackProxy, trackId, check);
        List<Object> before = lane.clips();
        if (clipIndex >= before.size())
            throw Errors.notApplied(
                    Reason.TARGET_NOT_FOUND,
                    "clipIndex " + clipIndex + " is outside the " + before.size() + " arranger clips.");
        Object clip = before.get(clipIndex);
        double s = (Double) a.invoke(start, clip), l = (Double) a.invoke(length, clip);
        if (!near(s, expectedStart) || !expectedName.equals(a.invoke(name, clip)))
            throw Errors.notApplied(
                    Reason.STALE_TARGET,
                    "Arranger clip " + clipIndex + " is \"" + a.invoke(name, clip) + "\" at " + s
                            + ", not expectedName at expectedStartBeats; re-read arranger.clips.list.");
        JsonObject previous = describe(clip, clipIndex);
        switch (op) {
            case "move": {
                double to = args.get("startBeats").getAsDouble();
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
                double to = args.get("lengthBeats").getAsDouble();
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

    private JsonObject already(Lane lane, String op, Object clip, JsonObject previous) {
        JsonObject out = written(Outcome.ALREADY_MATCHED, lane, op, clip, previous);
        out.addProperty("nativeUndo", false);
        return out;
    }
}
