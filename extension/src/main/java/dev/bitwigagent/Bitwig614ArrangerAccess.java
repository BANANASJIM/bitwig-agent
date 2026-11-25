package dev.bitwigagent;

import com.google.gson.*;
import java.lang.reflect.Method;
import java.util.*;
import static dev.bitwigagent.HostServices.failure;

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
    private final Class<?> player, clipType, slotType, projectType, instrumentClip, audioClip, number, bool;
    private final Method target, slotTarget, slotClip, trackUuid, lane, timeline, events, start, length, name, rename;
    private final Method project, setStart, setLength, insert, delete, newClip, newNote, addNote, noteTimeline, noteRow;
    private final Method noteCount, velocity, copy, arrangeLength, slotLane, slotOwner, clipSlot, begin, end;
    private final Method timing, playStart, playStop, loop, loopEnabled, position, duration, doubleGet, boolGet;
    private final Object copyMode;

    Bitwig614ArrangerAccess(Bitwig614Runtime.Access a) throws ReflectiveOperationException {
        this.a = a;
        context = new NativeReadContext(new Bitwig614ReadContextAccess(a));
        String proxy = "com.bitwig.flt.control_surface.proxy.", master = "com.bitwig.flt.document.core.master.";
        player = a.type("ynF"); clipType = a.type("zZi"); slotType = a.type("TGq"); projectType = a.type(master + "g2L");
        instrumentClip = a.type("kOI"); audioClip = a.type("Nbl");
        Class<?> laneType = a.type("shO"), mainLane = a.type("Tsu"), noteClips = a.type("VDz"), events = a.type("qIe");
        Class<?> event = a.type("YvI"), enduring = a.type("asx"), timed = a.type("SMz"), clip = a.type("Ob"), timelineType = a.type("PSn");
        Class<?> g1d = a.type("g1d"), note = a.type("n1"), noteClip = a.type("pBi"), poly = a.type("YeP"), row = a.type("Rke");
        Class<?> selection = a.type("Bz"), mode = a.type("i3D"), async = a.type("com.bitwig.base.async.lBQ");
        target = a.exactMethod(a.type(proxy + "ControlSurfaceProxy"), "getDeepestTarget", Object.class, false);
        slotTarget = a.exactMethod(a.type(proxy + "ControlSurfaceProxy"), "getTarget", Object.class, false);
        slotClip = a.exactMethod(a.type(proxy + "ClipLauncherSlotProxy"), "getClip", clip, false);
        trackUuid = a.exactMethod(a.type("com.bitwig.flt.document.core.iface.RYd"), "kFU", UUID.class, false);
        lane = a.exactMethod(player, "HVx", mainLane, false);
        timeline = a.exactMethod(a.type("sru"), "iAj", noteClips, false);
        this.events = a.exactMethod(events, "cfM", List.class, false);
        start = a.exactMethod(timed, "x3t", double.class, false);
        length = a.exactMethod(enduring, "nfq", double.class, false);
        name = a.exactMethod(clip, "dJ_", String.class, false);
        rename = a.exactMethod(clip, "g_", void.class, false, String.class);
        project = a.exactMethod(a.type("com.bitwig.ramona.core.AVL"), "IwV", Object.class, false, Class.class);
        setStart = a.exactMethod(timed, "wGc", void.class, false, double.class);
        setLength = a.exactMethod(enduring, "lYc", void.class, false, double.class);
        insert = a.exactMethod(timelineType, "cfM", void.class, false, event, boolean.class, boolean.class, a.type("dR"));
        delete = a.exactMethod(timelineType, "cfM", void.class, false, Collection.class);
        newClip = a.exactMethod(g1d, "cfM", g1d, true, double.class, double.class);
        newNote = a.exactMethod(note, "cfM", note, true, double.class, double.class, double.class);
        addNote = a.exactMethod(a.type("AGr"), "MRY", void.class, false, note, int.class, int.class, selection);
        noteTimeline = a.exactMethod(noteClip, "MLK", poly, false);
        noteRow = a.exactMethod(poly, "TMF", row, false, int.class, int.class, boolean.class);
        noteCount = a.exactMethod(poly, "E1b", int.class, false);
        velocity = a.exactMethod(a.type("Roz"), "UNj", double.class, false);
        copy = a.exactMethod(laneType, "cfM", void.class, false, List.class, slotType, double.class, mode, boolean.class, selection, async);
        arrangeLength = a.exactMethod(clipType, "Mb2", double.class, false);
        slotLane = a.exactMethod(slotType, "z3I", laneType, false);
        slotOwner = a.exactMethod(a.type("PAJ"), "Z1C", a.type("com.bitwig.flt.document.core.iface.WSA"), false);
        clipSlot = a.exactMethod(clip, "kFU", a.type("Us"), false);
        begin = a.exactMethod(projectType, "cfM", void.class, false, String.class, boolean.class);
        end = a.exactMethod(projectType, "cfM", void.class, false, boolean.class);
        number = a.type("com.bitwig.ramona.core.iYN"); bool = a.type("com.bitwig.ramona.core.lBQ");
        timing = a.exactMethod(a.type("eib"), "Nnn", a.type("mdC"), false);
        playStart = a.exactMethod(a.type("RsJ"), "TMF", a.type("etA"), false);
        playStop = a.exactMethod(a.type("RsJ"), "hy_", a.type("XbH"), false);
        loop = a.exactMethod(a.type("nLa"), "tp2", a.type("PTe"), false);
        loopEnabled = a.exactMethod(a.type("nLa"), "LaE", bool, false);
        position = a.exactMethod(a.type("vFg"), "Ktv", number, false);
        duration = a.exactMethod(a.type("iCu"), "Coi", number, false);
        doubleGet = a.exactMethod(number, "getDoubleValue", double.class, false);
        boolGet = a.exactMethod(bool, "getBoolValue", boolean.class, false);
        Object found = null;
        for (Object value : mode.getEnumConstants()) if (((Enum<?>) value).name().equals("COPY")) found = value;
        if (found == null) throw new NoSuchFieldException("i3D.COPY absent");
        copyMode = found;
    }

    @Override public void execute(Runnable work) { a.execute(work); }

    /** One resolved main clip lane; every native call re-checks it against the proxy. */
    private final class Lane {
        final Object proxy, track, lane, timeline, project;
        final String trackId;
        final Object[] models;
        final String[] labels = {"timelineDeleted", "laneDeleted", "trackDeleted", "projectDeleted"};
        final NativeReadContext.Capture capture;
        Lane(Object proxy, String trackId, Runnable check) {
            a.documentThread(); check.run();
            this.proxy = proxy; this.trackId = trackId;
            track = a.invoke(target, proxy);
            if (track == null) throw failure("stale_context", "Track proxy has no attached native track.");
            if (!player.isInstance(track))
                throw failure("unsupported_scope", "Arranger clips are supported on audio/instrument/hybrid tracks only (not group, effect or master tracks).");
            a.requireModelThread(track);
            if (!UUID.fromString(trackId).equals(a.invoke(trackUuid, track))) throw failure("stale_context", "Native track UUID differs from trackId.");
            lane = a.invoke(Bitwig614ArrangerAccess.this.lane, track);
            timeline = lane == null ? null : a.invoke(Bitwig614ArrangerAccess.this.timeline, lane);
            if (timeline == null) throw failure("arranger_unavailable", "Track has no main arranger clip lane.");
            project = a.invoke(Bitwig614ArrangerAccess.this.project, track, projectType);
            models = new Object[]{timeline, lane, track};
            capture = context.capture(project, models, labels, check, false);
            a.requireModelThread(timeline);
        }
        void guard(Runnable check) {
            check.run(); a.requireModelThread(timeline); context.guard(capture, models, labels, check);
            if (a.invoke(target, proxy) != track || a.invoke(Bitwig614ArrangerAccess.this.lane, track) != lane
                    || a.invoke(Bitwig614ArrangerAccess.this.timeline, lane) != timeline)
                throw failure("stale_context", "Arranger lane changed during the native operation.");
        }
        List<Object> clips() { return new ArrayList<>((List<?>) a.invoke(events, timeline)); }
        void transaction(String label, Runnable work) {
            a.invoke(begin, project, label, false);
            try { work.run(); } finally { a.invoke(end, project, false); }
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
        out.addProperty("kind", instrumentClip.isInstance(clip) ? "instrument" : audioClip.isInstance(clip) ? "audio" : "other");
        if (clipType.isInstance(clip)) {
            Object time = a.invoke(timing, clip), range = a.invoke(loop, time);
            out.addProperty("loopEnabled", (Boolean) a.invoke(boolGet, a.invoke(loopEnabled, time)));
            out.addProperty("loopStartBeats", value(a.invoke(position, range)));
            out.addProperty("loopLengthBeats", value(a.invoke(duration, range)));
            out.addProperty("playStartBeats", value(a.invoke(position, a.invoke(playStart, time))));
            out.addProperty("playStopBeats", value(a.invoke(position, a.invoke(playStop, time))));
        }
        return out;
    }
    private double value(Object nativeNumber) { return (Double) a.invoke(doubleGet, nativeNumber); }

    private JsonObject result(Lane lane, List<Object> clips) {
        JsonObject out = HostServices.observed();
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

    @Override public JsonObject list(Object trackProxy, String trackId, Runnable check) {
        Lane lane = new Lane(trackProxy, trackId, check);
        List<Object> clips = lane.clips();
        lane.guard(check);
        return result(lane, clips);
    }

    private void requireFree(Lane lane, List<Object> clips, Object except, double from, double to) {
        for (int i = 0; i < clips.size(); i++) {
            Object clip = clips.get(i);
            if (clip == except) continue;
            double s = (Double) a.invoke(start, clip), e = s + (Double) a.invoke(length, clip);
            if (from < e - EPS && s < to - EPS)
                throw failure("arranger_overlap", "Range " + from + ".." + to + " overlaps arranger clip " + i + " (" + s + ".." + e
                        + "); overlapping edits are refused, move or delete that clip first.");
        }
    }

    private Object added(List<Object> before, List<Object> after) {
        Object found = null;
        for (Object clip : after) if (!before.contains(clip)) {
            if (found != null) throw new RpcException("arranger_verification_failed", "More than one new arranger clip appeared.", "unknown");
            found = clip;
        }
        if (found == null) throw new RpcException("arranger_verification_failed", "No new arranger clip was observed after dispatch.", "unknown");
        if (after.size() != before.size() + 1) throw new RpcException("arranger_verification_failed", "Existing arranger clips changed during the write.", "unknown");
        return found;
    }

    private JsonObject written(Lane lane, String op, Object clip, JsonObject previous) {
        List<Object> after = lane.clips();
        JsonObject out = result(lane, after);
        out.addProperty("op", op);
        int index = clip == null ? -1 : after.indexOf(clip);
        out.add("clip", index < 0 ? JsonNull.INSTANCE : describe(clip, index));
        if (previous != null) out.add("previous", previous);
        out.addProperty("verification", "native_timeline_readback");
        out.addProperty("nativeUndo", true);
        out.addProperty("undoGroupingVerified", false);
        return out;
    }

    private static boolean near(double a, double b) { return Math.abs(a - b) <= EPS; }
    private static RpcException mismatch(String what) {
        return new RpcException("arranger_verification_failed", "Native readback differs after dispatch: " + what, "unknown");
    }

    @Override public JsonObject create(Object trackProxy, String trackId, double startBeats, double lengthBeats, String clipName,
                                       List<double[]> notes, int channel, Runnable check, Runnable beforeWrite) {
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
            for (double[] n : notes)
                a.invoke(addNote, clip, a.invoke(newNote, null, n[0], n[3], n[2] / 127.0), (int) n[1], channel, null);
        });
        lane.guard(check);
        Object clip = added(before, lane.clips());
        if (clip != created[0]) throw mismatch("the observed clip is not the created clip");
        if (!near((Double) a.invoke(start, clip), startBeats)) throw mismatch("startBeats");
        if (!near((Double) a.invoke(length, clip), lengthBeats)) throw mismatch("lengthBeats");
        if (!clipName.equals(a.invoke(name, clip))) throw mismatch("name");
        JsonObject out = written(lane, "create", clip, null);
        if (!notes.isEmpty()) out.add("noteWrite", verifyNotes(clip, notes, channel));
        return out;
    }

    private JsonObject verifyNotes(Object clip, List<double[]> notes, int channel) {
        Object poly = a.invoke(noteTimeline, clip);
        int count = (Integer) a.invoke(noteCount, poly);
        int verified = 0;
        JsonArray missing = new JsonArray();
        for (double[] n : notes) {
            Object row = a.invoke(noteRow, poly, (int) n[1], channel, false);
            boolean ok = false;
            if (row != null) for (Object note : (List<?>) a.invoke(events, row))
                ok |= near((Double) a.invoke(start, note), n[0]) && near((Double) a.invoke(length, note), n[3])
                        && Math.abs((Double) a.invoke(velocity, note) * 127.0 - n[2]) < 0.5;
            if (ok) verified++;
            else {
                JsonObject m = new JsonObject();
                m.addProperty("beat", n[0]); m.addProperty("pitch", (int) n[1]);
                missing.add(m);
            }
        }
        JsonObject out = new JsonObject();
        out.addProperty("requested", notes.size());
        out.addProperty("verified", verified);
        out.addProperty("notesInClip", count);
        out.add("unverifiedNotes", missing);
        out.addProperty("verification", "native_note_rows_readback_beat_duration_velocity");
        if (verified != notes.size() || count != notes.size())
            throw new RpcException("arranger_verification_failed", "Clip was created but " + (notes.size() - verified)
                    + " requested notes were not read back (clip holds " + count + "); inspect before another write.", "unknown");
        return out;
    }

    @Override public JsonObject copyFromLauncher(Object trackProxy, Object slotProxy, String trackId, String expectedClipName,
                                                 double startBeats, Runnable check, Runnable beforeWrite) {
        Lane lane = new Lane(trackProxy, trackId, check);
        Object slot = a.invoke(slotTarget, slotProxy), source = a.invoke(slotClip, slotProxy);
        if (!slotType.isInstance(slot) || !clipType.isInstance(source) || a.invoke(clipSlot, source) != slot)
            throw failure("stale_context", "Launcher proxy does not resolve to one attached native clip slot.");
        if (a.invoke(slotOwner, slot) != lane.track) throw failure("stale_context", "Launcher slot belongs to another native track.");
        if (a.invoke(slotLane, slot) != lane.lane) throw failure("unsupported_scope", "Launcher slot is not on the track's main clip lane.");
        if (!expectedClipName.equals(a.invoke(name, source))) throw failure("stale_context", "Native clip name differs from expectedClipName.");
        double expectedLength = (Double) a.invoke(arrangeLength, source);
        List<Object> before = lane.clips();
        requireFree(lane, before, null, startBeats, startBeats + expectedLength);
        lane.guard(check);
        if (a.invoke(slotClip, slotProxy) != source) throw failure("stale_context", "Launcher clip changed before the copy.");
        beforeWrite.run();
        a.invoke(copy, lane.lane, new ArrayList<>(List.of(slot)), slot, startBeats, copyMode, false, null, null);
        lane.guard(check);
        Object clip = added(before, lane.clips());
        if (!near((Double) a.invoke(start, clip), startBeats)) throw mismatch("startBeats");
        if (!near((Double) a.invoke(length, clip), expectedLength)) throw mismatch("lengthBeats differs from the clip's arrangement length");
        JsonObject out = written(lane, "copyFromLauncher", clip, null);
        out.addProperty("expectedLengthBeats", expectedLength);
        out.addProperty("nativeOperation", "copy_launcher_clips_to_arranger");
        out.addProperty("copyMode", "copy_not_alias");
        return out;
    }

    @Override public JsonObject edit(Object trackProxy, String trackId, int clipIndex, double expectedStart, String expectedName,
                                     String op, JsonObject args, Runnable check, Runnable beforeWrite) {
        Lane lane = new Lane(trackProxy, trackId, check);
        List<Object> before = lane.clips();
        if (clipIndex >= before.size()) throw failure("target_not_found", "clipIndex " + clipIndex + " is outside the " + before.size() + " arranger clips.");
        Object clip = before.get(clipIndex);
        double s = (Double) a.invoke(start, clip), l = (Double) a.invoke(length, clip);
        if (!near(s, expectedStart) || !expectedName.equals(a.invoke(name, clip)))
            throw failure("stale_target", "Arranger clip " + clipIndex + " is \"" + a.invoke(name, clip) + "\" at " + s
                    + ", not expectedName at expectedStartBeats; re-read arranger.clips.list.");
        JsonObject previous = describe(clip, clipIndex);
        switch (op) {
            case "move": {
                double to = args.get("startBeats").getAsDouble();
                if (near(to, s)) return already(lane, op, clip, previous);
                requireFree(lane, before, clip, to, to + l);
                lane.guard(check); beforeWrite.run();
                lane.transaction("Move Arranger Clip", () -> a.invoke(setStart, clip, to));
                lane.guard(check);
                if (!lane.clips().contains(clip) || !near((Double) a.invoke(start, clip), to) || !near((Double) a.invoke(length, clip), l))
                    throw mismatch("start/length after move");
                return written(lane, op, clip, previous);
            }
            case "resize": {
                double to = args.get("lengthBeats").getAsDouble();
                if (near(to, l)) return already(lane, op, clip, previous);
                requireFree(lane, before, clip, s, s + to);
                lane.guard(check); beforeWrite.run();
                lane.transaction("Resize Arranger Clip", () -> a.invoke(setLength, clip, to));
                lane.guard(check);
                if (!lane.clips().contains(clip) || !near((Double) a.invoke(start, clip), s) || !near((Double) a.invoke(length, clip), to))
                    throw mismatch("start/length after resize");
                return written(lane, op, clip, previous);
            }
            case "rename": {
                String to = args.get("name").getAsString();
                if (to.equals(expectedName)) return already(lane, op, clip, previous);
                lane.guard(check); beforeWrite.run();
                lane.transaction("Rename Arranger Clip", () -> a.invoke(rename, clip, to));
                lane.guard(check);
                if (!to.equals(a.invoke(name, clip))) throw mismatch("name after rename");
                return written(lane, op, clip, previous);
            }
            case "delete": {
                lane.guard(check); beforeWrite.run();
                a.invoke(delete, lane.timeline, new ArrayList<>(List.of(clip)));
                lane.guard(check);
                List<Object> after = lane.clips();
                if (after.contains(clip) || after.size() != before.size() - 1) throw mismatch("clip still present or other clips changed");
                JsonObject out = written(lane, op, null, previous);
                out.addProperty("removed", true);
                return out;
            }
            default: throw failure("invalid_params", "Unknown arranger op.");
        }
    }

    private JsonObject already(Lane lane, String op, Object clip, JsonObject previous) {
        JsonObject out = written(lane, op, clip, previous);
        out.addProperty("verification", "already_matched");
        out.addProperty("nativeUndo", false);
        return out;
    }
}
