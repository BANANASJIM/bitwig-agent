package dev.bitwigagent;

import com.google.gson.JsonObject;
import java.lang.reflect.Method;
import java.util.*;

/**
 * Exact 6.1.4 bindings for the arranger automation lane of one parameter on a player track. Paths follow Bitwig's
 * own model tests (AutomationFollowStretchTest, AutomationPropertiesAfterPointDeletionTest) and lane seeding
 * ({@code lkr.DxJ}): track {@code ynF.mvz()} lanes, {@code eD.cfM(bDH)} parameter reference, {@code LEn.Hnt(z2y)}
 * find lane, {@code YW.MRY(z2y,false,false,false)} "Add Automation Lane" without the seed point or UI reveal,
 * {@code lkr.LaE().tSS()} point timeline {@code PQT}, {@code bU.MRY(beat,value,jRs,curve)} new point in the lane's
 * value range, {@code PQT.cfM(point,index)} insert, {@code PSn.MRY(point)} delete. Values are normalized through
 * the lane's range ({@code vcm.cfM(dfo)}); beats are arrangement quarter notes. No UI selection is read or changed.
 */
final class Bitwig614AutomationAccess implements AutomationModule.Access {
    private final Bitwig614Runtime.Access a;
    private final NativeReadContext context;
    private final Class<?> player, parameterType, rangeType, pointType, projectType;
    private final Method target, trackUuid, lanes, channel, volume, pan, reference, referenceRange, findLane, addLane;
    private final Method laneTimeline,
            points,
            range,
            events,
            time,
            normalized,
            interpolation,
            curve,
            newPoint,
            insert,
            delete;
    private final Method nearest, begin, end;
    private final Object hold, linear;

    Bitwig614AutomationAccess(Bitwig614Runtime.Access a) throws ReflectiveOperationException {
        this.a = a;
        context = new NativeReadContext(new Bitwig614ReadContextAccess(a));
        String proxy = "com.bitwig.flt.control_surface.proxy.", master = "com.bitwig.flt.document.core.master.";
        player = a.type("ynF");
        parameterType = a.type("bDH");
        rangeType = a.type("aL");
        pointType = a.type("hMP");
        projectType = a.type(master + "g2L");
        Class<?> referenceType = a.type(master + "eD"),
                referenceIface = a.type("com.bitwig.flt.document.core.iface.z2y");
        Class<?> laneType = a.type("lkr"),
                laneSet = a.type("LEn"),
                timelineType = a.type("PQT"),
                valueRange = a.type("bU");
        Class<?> event = a.type("YvI"), value = a.type("dfo"), interpolationType = a.type("jRs");
        if (!referenceIface.isAssignableFrom(referenceType)
                || !laneSet.isAssignableFrom(a.type("euS"))
                || !valueRange.isAssignableFrom(rangeType)
                || !event.isAssignableFrom(pointType)
                || !value.isAssignableFrom(pointType))
            throw new NoSuchMethodException("Inspected automation lane model ancestry differs.");
        target = a.exactMethod(a.type(proxy + "ControlSurfaceProxy"), "getDeepestTarget", Object.class, false);
        trackUuid = a.exactMethod(a.type("com.bitwig.flt.document.core.iface.RYd"), "kFU", UUID.class, false);
        lanes = a.exactMethod(player, "mvz", a.type("euS"), false);
        channel = a.exactMethod(player, "TbN", a.type("FNX"), false);
        volume = a.exactMethod(a.type("FNX"), "tSS", a.type("A"), false);
        pan = a.exactMethod(a.type("FNX"), "nfq", a.type("mAq"), false);
        reference = a.exactMethod(referenceType, "cfM", referenceType, true, parameterType);
        referenceRange = a.exactMethod(referenceType, "MIO", valueRange, false);
        findLane = a.exactMethod(laneSet, "Hnt", laneType, false, referenceIface);
        addLane = a.exactMethod(
                laneSet, "MRY", laneType, false, referenceIface, boolean.class, boolean.class, boolean.class);
        laneTimeline = a.exactMethod(laneType, "LaE", a.type("GVR"), false);
        points = a.exactMethod(a.type("GVR"), "tSS", timelineType, false);
        range = a.exactMethod(timelineType, "wGc", valueRange, false);
        events = a.exactMethod(a.type("qIe"), "cfM", List.class, false);
        time = a.exactMethod(a.type("SMz"), "x3t", double.class, false);
        normalized = a.exactMethod(a.type("vcm"), "cfM", double.class, false, value);
        interpolation = a.exactMethod(value, "FzB", interpolationType, false);
        curve = a.exactMethod(pointType, "dU_", double.class, false);
        newPoint = a.exactMethod(
                valueRange, "MRY", pointType, false, double.class, double.class, interpolationType, double.class);
        insert = a.exactMethod(timelineType, "cfM", void.class, false, event, int.class);
        delete = a.exactMethod(a.type("PSn"), "MRY", void.class, false, event);
        nearest = a.exactMethod(a.type("com.bitwig.ramona.core.AVL"), "IwV", Object.class, false, Class.class);
        begin = a.exactMethod(projectType, "cfM", void.class, false, String.class, boolean.class);
        end = a.exactMethod(projectType, "cfM", void.class, false, boolean.class);
        Object h = null, l = null;
        for (Object constant : interpolationType.getEnumConstants()) {
            String name = ((Enum<?>) constant).name();
            if (name.equals("HOLD")) h = constant;
            else if (name.equals("LINEAR")) l = constant;
        }
        if (h == null || l == null || interpolationType.getEnumConstants().length != 2)
            throw new NoSuchFieldException("jRs is not exactly {HOLD, LINEAR}");
        hold = h;
        linear = l;
    }

    @Override
    public void execute(Runnable work) {
        a.execute(work);
    }

    /** One resolved parameter lane; every native call re-checks it against the proxy and the parameter source. */
    private final class Lane {
        final Object proxy, track, laneSet, parameter, ref, project;
        final String trackId;
        final AutomationModule.Target spec;
        final GridSnapshotReader.ParameterBinding grid;
        final Object[] models;
        final String[] labels = {"parameterDeleted", "lanesDeleted", "trackDeleted", "projectDeleted"};
        final NativeReadContext.Capture capture;
        Object lane, timeline;

        Lane(Object proxy, String trackId, AutomationModule.Target spec, Runnable check) {
            a.documentThread();
            check.run();
            this.proxy = proxy;
            this.trackId = trackId;
            this.spec = spec;
            track = a.invoke(target, proxy);
            if (track == null)
                throw Errors.notApplied(ErrorCode.STALE_CONTEXT, "Track proxy has no attached native track.");
            if (!player.isInstance(track))
                throw Errors.notApplied(
                        ErrorCode.UNSUPPORTED_SCOPE,
                        "Automation points are supported on audio/instrument/hybrid tracks only (not group, effect or master tracks).");
            a.requireModelThread(track);
            if (!UUID.fromString(trackId).equals(a.invoke(trackUuid, track)))
                throw Errors.notApplied(ErrorCode.STALE_CONTEXT, "Native track UUID differs from trackId.");
            laneSet = a.invoke(lanes, track);
            if (laneSet == null)
                throw Errors.notApplied(ErrorCode.AUTOMATION_UNAVAILABLE, "Track has no automation lane set.");
            grid = spec.grid()
                    ? gridAdapter(spec)
                            .automationParameter(
                                    spec.deviceProxy, UUID.fromString(trackId), spec.moduleId, spec.parameterId, check)
                    : null;
            parameter = resolve();
            if (!parameterType.isInstance(parameter))
                throw Errors.notApplied(ErrorCode.UNSUPPORTED_SCOPE, "Target is not an automatable native parameter.");
            if (a.invoke(nearest, parameter, player) != track)
                throw Errors.notApplied(ErrorCode.STALE_CONTEXT, "Parameter does not belong to the target track.");
            ref = a.invoke(reference, null, parameter);
            if (ref == null)
                throw Errors.notApplied(
                        ErrorCode.AUTOMATION_UNAVAILABLE,
                        "Native automation reference is unavailable for this parameter.");
            project = a.invoke(nearest, track, projectType);
            models = new Object[] {parameter, laneSet, track};
            capture = context.capture(project, models, labels, check, false);
            attach(a.invoke(findLane, laneSet, ref));
        }

        private Object resolve() {
            if (spec.grid()) return grid.parameter;
            Object mixer = a.invoke(channel, track);
            if (mixer == null)
                throw Errors.notApplied(ErrorCode.AUTOMATION_UNAVAILABLE, "Track has no native mixer channel.");
            return a.invoke(spec.kind.equals("volume") ? volume : pan, mixer);
        }

        void attach(Object found) {
            lane = found;
            timeline = found == null ? null : a.invoke(points, a.invoke(laneTimeline, found));
            if (found != null && timeline == null)
                throw Errors.notApplied(ErrorCode.AUTOMATION_UNAVAILABLE, "Automation lane has no point timeline.");
            if (timeline != null) a.requireModelThread(timeline);
        }

        void guard(Runnable check) {
            check.run();
            a.requireModelThread(track);
            context.guard(capture, models, labels, check);
            if (spec.grid()) gridAdapter(spec).guardAutomationParameter(grid);
            if (a.invoke(target, proxy) != track
                    || a.invoke(lanes, track) != laneSet
                    || resolve() != parameter
                    || a.invoke(findLane, laneSet, ref) != lane
                    || (lane != null && a.invoke(points, a.invoke(laneTimeline, lane)) != timeline))
                throw Errors.notApplied(
                        ErrorCode.STALE_CONTEXT, "Automation lane changed during the native operation.");
        }

        List<Object> natives() {
            if (timeline == null) return List.of();
            List<Object> out = new ArrayList<>((List<?>) a.invoke(events, timeline));
            if (out.size() > AutomationModule.MAX_LANE)
                throw Errors.notApplied(
                        ErrorCode.AUTOMATION_BOUNDS_EXCEEDED,
                        "Automation lane holds more than " + AutomationModule.MAX_LANE + " points.");
            return out;
        }

        Object valueRange() {
            Object r = a.invoke(range, timeline);
            if (!rangeType.isInstance(r))
                throw Errors.notApplied(
                        ErrorCode.UNSUPPORTED_SCOPE, "Only continuous (decimal) automation lanes are supported.");
            return r;
        }

        List<AutomationModule.Point> describe(List<Object> natives) {
            if (natives.isEmpty()) return List.of();
            Object r = valueRange();
            List<AutomationModule.Point> out = new ArrayList<>();
            for (Object point : natives) {
                if (!pointType.isInstance(point))
                    throw Errors.notApplied(ErrorCode.UNSUPPORTED_SCOPE, "Automation lane holds a non-point event.");
                Object mode = a.invoke(interpolation, point);
                if (mode != hold && mode != linear)
                    throw Errors.notApplied(ErrorCode.AUTOMATION_UNAVAILABLE, "Unknown automation interpolation.");
                out.add(new AutomationModule.Point(
                        (Double) a.invoke(time, point),
                        (Double) a.invoke(normalized, r, point),
                        mode == linear,
                        (Double) a.invoke(curve, point)));
            }
            return out;
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

    private static Bitwig614GridAdapter gridAdapter(AutomationModule.Target spec) {
        if (!(spec.grid instanceof Bitwig614GridAdapter))
            throw Errors.notApplied(ErrorCode.GRID_UNAVAILABLE, "Grid adapter unavailable for automation targets.");
        return (Bitwig614GridAdapter) spec.grid;
    }

    @Override
    public JsonObject get(
            Object trackProxy, String trackId, AutomationModule.Target target, double[] range, Runnable check) {
        Lane lane = new Lane(trackProxy, trackId, target, check);
        List<AutomationModule.Point> points = lane.describe(lane.natives());
        lane.guard(check);
        return AutomationModule.lane(trackId, target, lane.lane != null, points, range);
    }

    @Override
    public JsonObject set(
            Object trackProxy,
            String trackId,
            AutomationModule.Target target,
            int expectedCount,
            String expectedHash,
            List<AutomationModule.Point> requested,
            double[] range,
            Runnable check,
            Runnable beforeWrite) {
        Lane lane = new Lane(trackProxy, trackId, target, check);
        List<Object> before = lane.natives();
        List<AutomationModule.Point> current = lane.describe(before);
        AutomationModule.requireExpected(current, expectedCount, expectedHash);
        List<AutomationModule.Point> desired = AutomationModule.desired(current, range, requested);
        boolean existed = lane.lane != null;
        if (existed) lane.valueRange();
        else if (!rangeType.isInstance(a.invoke(referenceRange, lane.ref)))
            throw Errors.notApplied(
                    ErrorCode.UNSUPPORTED_SCOPE, "Only continuous (decimal) automation lanes are supported.");
        if (AutomationModule.same(current, desired)) {
            lane.guard(check);
            JsonObject out = written(lane, current, range, current, 0, 0, false);
            out.addProperty("verification", "already_matched");
            out.addProperty("nativeUndo", false);
            return out;
        }
        List<Object> removed = new ArrayList<>(), kept = new ArrayList<>();
        int first = 0;
        for (int i = 0; i < before.size(); i++) {
            if (AutomationModule.inRange(current.get(i).beat, range)) removed.add(before.get(i));
            else {
                kept.add(before.get(i));
                if (range != null && current.get(i).beat < range[0]) first++;
            }
        }
        final int insertAt = first;
        lane.guard(check);
        beforeWrite.run();
        lane.transaction("Set Automation Points", () -> {
            if (lane.lane == null) {
                lane.attach(a.invoke(addLane, lane.laneSet, lane.ref, false, false, false));
                if (lane.lane == null)
                    throw Errors.notApplied(
                            ErrorCode.AUTOMATION_UNAVAILABLE, "Bitwig did not create the automation lane.");
            }
            Object valueRange = lane.valueRange();
            for (Object point : removed) a.invoke(delete, lane.timeline, point);
            for (int i = 0; i < requested.size(); i++) {
                AutomationModule.Point p = requested.get(i);
                Object point = a.invoke(newPoint, valueRange, p.beat, p.value, p.linear ? linear : hold, 0.0);
                if (point == null)
                    throw Errors.notApplied(
                            ErrorCode.AUTOMATION_UNAVAILABLE, "Bitwig did not create an automation point.");
                a.invoke(insert, lane.timeline, point, insertAt + i);
            }
        });
        lane.guard(check);
        List<Object> after = lane.natives();
        Set<Object> present = Collections.newSetFromMap(new IdentityHashMap<>());
        present.addAll(after);
        for (Object point : kept)
            if (!present.contains(point))
                throw Errors.unknown(
                        ErrorCode.AUTOMATION_VERIFICATION_FAILED,
                        "A point outside the range disappeared during the write; inspect automation.points.get.");
        for (Object point : removed)
            if (present.contains(point))
                throw Errors.unknown(
                        ErrorCode.AUTOMATION_VERIFICATION_FAILED,
                        "A replaced point is still on the lane; inspect automation.points.get.");
        List<AutomationModule.Point> actual = lane.describe(after);
        AutomationModule.verify(desired, actual);
        return written(lane, actual, range, current, removed.size(), requested.size(), !existed);
    }

    private JsonObject written(
            Lane lane,
            List<AutomationModule.Point> all,
            double[] range,
            List<AutomationModule.Point> previous,
            int removed,
            int inserted,
            boolean created) {
        JsonObject out = AutomationModule.lane(lane.trackId, lane.spec, lane.lane != null, all, range);
        JsonObject before = new JsonObject();
        before.addProperty("count", previous.size());
        before.addProperty("pointsHash", AutomationModule.hash(previous));
        out.add("previous", before);
        out.addProperty("removed", removed);
        out.addProperty("inserted", inserted);
        out.addProperty("laneCreated", created);
        out.addProperty("verification", "native_lane_readback");
        out.addProperty("nativeUndo", true);
        out.addProperty("undoGroupingVerified", false);
        return out;
    }
}
