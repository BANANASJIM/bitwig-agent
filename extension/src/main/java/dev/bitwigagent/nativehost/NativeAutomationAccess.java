package dev.bitwigagent.nativehost;

import com.google.gson.JsonObject;
import dev.bitwigagent.automation.AutomationModule;
import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.Outcome;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.core.Results;
import dev.bitwigagent.core.Verification;
import dev.bitwigagent.grid.GridSnapshotReader;
import dev.bitwigagent.host.NativeReadContext;
import java.lang.reflect.Method;
import java.util.*;

/**
 * Exact bindings for the arranger automation lane of one parameter on a player track. Paths follow Bitwig's own
 * model tests (AutomationFollowStretchTest, AutomationPropertiesAfterPointDeletionTest) and lane seeding: track
 * {@code track.channel.automationLanes()}, {@code automation.reference.of(parameter)} parameter reference,
 * {@code automation.laneSet.find(target)} find lane, {@code automation.laneSet.add(target,false,false,false)} "Add
 * Automation Lane" without the seed point or UI reveal, {@code automation.lane.content().points()} point timeline,
 * {@code value.range.newPoint(beat,value,interpolation,curve)} new point in the lane's value range,
 * {@code automation.timeline.insert(point,index)} insert, {@code timeline.remove(point)} delete. Values are
 * normalized through the lane's range ({@code value.scale.normalized(value)}); beats are arrangement quarter notes. No
 * UI selection is read or changed.
 */
public final class NativeAutomationAccess implements AutomationModule.Access {
    private final NativeHost.Access a;
    private NativeGridAdapter gridAdapter;
    private final NativeReadContext context;
    private final NativeTrackResolver resolver;
    private final Class<?> parameterType, rangeType, pointType, projectType;
    private final Method lanes, channel, volume, pan, reference, referenceRange, findLane, addLane;
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
    private final Method begin, end;
    private final Object hold, linear;

    /** The exact bindings, or native_unavailable when a descriptor does not match the loaded host. */
    public static NativeAutomationAccess bind(NativeHost.Access a) {
        try {
            return new NativeAutomationAccess(a);
        } catch (ReflectiveOperationException | LinkageError error) {
            throw Errors.notApplied(
                    Reason.NATIVE_UNAVAILABLE,
                    "Exact automation bindings unavailable: " + NativeHost.diagnostic(error));
        }
    }

    NativeAutomationAccess(NativeHost.Access a) throws ReflectiveOperationException {
        this.a = a;
        context = new NativeReadContext(new NativeReadContextAccess(a));
        resolver = new NativeTrackResolver(a);
        parameterType = a.type("parameter");
        rangeType = a.type("parameter.floatDefinition");
        pointType = a.type("automation.point");
        projectType = a.type("project");
        Class<?> referenceType = a.type("automation.reference"), referenceIface = a.type("automation.target");
        Class<?> laneType = a.type("automation.lane"),
                laneSet = a.type("automation.laneSet"),
                timelineType = a.type("automation.timeline"),
                valueRange = a.type("parameter.range");
        Class<?> event = a.type("event"),
                value = a.type("automation.value"),
                interpolationType = a.type("automation.interpolation");
        if (!referenceIface.isAssignableFrom(referenceType)
                || !laneSet.isAssignableFrom(a.type("automation.lanes"))
                || !valueRange.isAssignableFrom(rangeType)
                || !event.isAssignableFrom(pointType)
                || !value.isAssignableFrom(pointType))
            throw new NoSuchMethodException("Inspected automation lane model ancestry differs.");
        lanes = a.exactMethod("track.channel.automationLanes", a.type("automation.lanes"), false);
        channel = a.exactMethod("track.channel.mixer", a.type("mixer.channel"), false);
        volume = a.exactMethod("mixer.channel.volume", a.type("mixer.volume"), false);
        pan = a.exactMethod("mixer.channel.pan", a.type("mixer.pan"), false);
        reference = a.exactMethod("automation.reference.of", referenceType, true, parameterType);
        referenceRange = a.exactMethod("automation.reference.range", valueRange, false);
        findLane = a.exactMethod("automation.laneSet.find", laneType, false, referenceIface);
        addLane = a.exactMethod(
                "automation.laneSet.add", laneType, false, referenceIface, boolean.class, boolean.class, boolean.class);
        laneTimeline = a.exactMethod("automation.lane.content", a.type("automation.laneContent"), false);
        points = a.exactMethod("automation.laneContent.points", timelineType, false);
        range = a.exactMethod("automation.timeline.range", valueRange, false);
        events = a.exactMethod("event.list.events", List.class, false);
        time = a.exactMethod("event.timed.start", double.class, false);
        normalized = a.exactMethod("value.scale.normalized", double.class, false, value);
        interpolation = a.exactMethod("automation.value.interpolation", interpolationType, false);
        curve = a.exactMethod("automation.point.curve", double.class, false);
        newPoint = a.exactMethod(
                "parameter.range.newPoint",
                pointType,
                false,
                double.class,
                double.class,
                interpolationType,
                double.class);
        insert = a.exactMethod("automation.timeline.insert", void.class, false, event, int.class);
        delete = a.exactMethod("timeline.remove", void.class, false, event);
        begin = a.exactMethod("project.begin", void.class, false, String.class, boolean.class);
        end = a.exactMethod("project.end", void.class, false, boolean.class);
        Object h = null, l = null;
        for (Object constant : interpolationType.getEnumConstants()) {
            String name = ((Enum<?>) constant).name();
            if (name.equals("HOLD")) h = constant;
            else if (name.equals("LINEAR")) l = constant;
        }
        if (h == null || l == null || interpolationType.getEnumConstants().length != 2)
            throw new NoSuchFieldException("automation.interpolation is not exactly {HOLD, LINEAR}");
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
            this.spec = spec;
            track = resolver.resolve(proxy, trackId, NativeTrackResolver.Kind.CHANNEL);
            laneSet = a.invoke(lanes, track);
            if (laneSet == null)
                throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "Track has no automation lane set.");
            grid = spec.grid()
                    ? gridAdapter().automationParameter(spec.device, spec.moduleId, spec.parameterId, check)
                    : null;
            parameter = resolve();
            if (!parameterType.isInstance(parameter))
                throw Errors.notApplied(Reason.UNSUPPORTED_SCOPE, "Target is not an automatable native parameter.");
            if (resolver.owner(parameter, resolver.channelType()) != track)
                throw Errors.notApplied(Reason.TARGET_CHANGED, "Parameter does not belong to the target track.");
            ref = a.invoke(reference, null, parameter);
            if (ref == null)
                throw Errors.notApplied(
                        Reason.NATIVE_UNAVAILABLE, "Native automation reference is unavailable for this parameter.");
            project = resolver.owner(track, projectType);
            models = new Object[] {parameter, laneSet, track};
            capture = context.capture(project, models, labels, check, false);
            attach(a.invoke(findLane, laneSet, ref));
        }

        private Object resolve() {
            if (spec.grid()) return grid.parameter;
            Object mixer = a.invoke(channel, track);
            if (mixer == null) throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "Track has no native mixer channel.");
            return a.invoke(spec.kind.equals("volume") ? volume : pan, mixer);
        }

        void attach(Object found) {
            lane = found;
            timeline = found == null ? null : a.invoke(points, a.invoke(laneTimeline, found));
            if (found != null && timeline == null)
                throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "Automation lane has no point timeline.");
            if (timeline != null) a.requireModelThread(timeline);
        }

        void guard(Runnable check) {
            check.run();
            a.requireModelThread(track);
            context.guard(capture, models, labels, check);
            if (spec.grid()) gridAdapter().guardAutomationParameter(grid);
            if (!resolver.same(proxy, track)
                    || a.invoke(lanes, track) != laneSet
                    || resolve() != parameter
                    || a.invoke(findLane, laneSet, ref) != lane
                    || (lane != null && a.invoke(points, a.invoke(laneTimeline, lane)) != timeline))
                throw Errors.notApplied(Reason.TARGET_CHANGED, "Automation lane changed during the native operation.");
        }

        List<Object> natives() {
            if (timeline == null) return List.of();
            List<Object> out = new ArrayList<>((List<?>) a.invoke(events, timeline));
            if (out.size() > AutomationModule.MAX_LANE)
                throw Errors.notApplied(
                        Reason.OUT_OF_BOUNDS,
                        "Automation lane holds more than " + AutomationModule.MAX_LANE + " points.");
            return out;
        }

        Object valueRange() {
            Object r = a.invoke(range, timeline);
            if (!rangeType.isInstance(r))
                throw Errors.notApplied(
                        Reason.UNSUPPORTED_SCOPE, "Only continuous (decimal) automation lanes are supported.");
            return r;
        }

        List<AutomationModule.Point> describe(List<Object> natives) {
            if (natives.isEmpty()) return List.of();
            Object r = valueRange();
            List<AutomationModule.Point> out = new ArrayList<>();
            for (Object point : natives) {
                if (!pointType.isInstance(point))
                    throw Errors.notApplied(Reason.UNSUPPORTED_SCOPE, "Automation lane holds a non-point event.");
                Object mode = a.invoke(interpolation, point);
                if (mode != hold && mode != linear)
                    throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "Unknown automation interpolation.");
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

    private NativeGridAdapter gridAdapter() {
        if (gridAdapter == null) gridAdapter = new NativeGridAdapter(a);
        return gridAdapter;
    }

    @Override
    public JsonObject get(
            Object trackProxy, String trackId, AutomationModule.Target target, double[] range, Runnable check) {
        Lane lane = new Lane(trackProxy, trackId, target, check);
        List<AutomationModule.Point> points = lane.describe(lane.natives());
        lane.guard(check);
        JsonObject out = AutomationModule.lane(new JsonObject(), lane.lane != null, points, range);
        a.provenance(out);
        return out;
    }

    @Override
    public JsonObject set(
            Object trackProxy,
            String trackId,
            AutomationModule.Target target,
            String expectedHash,
            List<AutomationModule.Point> requested,
            double[] range,
            Runnable check,
            Runnable beforeWrite) {
        Lane lane = new Lane(trackProxy, trackId, target, check);
        List<Object> before = lane.natives();
        List<AutomationModule.Point> current = lane.describe(before);
        AutomationModule.requireExpected(current, expectedHash);
        List<AutomationModule.Point> desired = AutomationModule.desired(current, range, requested);
        boolean existed = lane.lane != null;
        if (existed) lane.valueRange();
        else if (!rangeType.isInstance(a.invoke(referenceRange, lane.ref)))
            throw Errors.notApplied(
                    Reason.UNSUPPORTED_SCOPE, "Only continuous (decimal) automation lanes are supported.");
        if (AutomationModule.same(AutomationModule.withoutBasePoint(current, desired), desired)) {
            lane.guard(check);
            JsonObject out = written(Outcome.ALREADY_MATCHED, lane, current, range, current, 0, 0, false);
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
                    throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "Bitwig did not create the automation lane.");
            }
            Object valueRange = lane.valueRange();
            for (Object point : removed) a.invoke(delete, lane.timeline, point);
            for (int i = 0; i < requested.size(); i++) {
                AutomationModule.Point p = requested.get(i);
                Object point = a.invoke(newPoint, valueRange, p.beat, p.value, p.linear ? linear : hold, 0.0);
                if (point == null)
                    throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "Bitwig did not create an automation point.");
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
                        Reason.READBACK_MISMATCH,
                        "A point outside the range disappeared during the write; inspect automation.points.get.");
        List<AutomationModule.Point> actual = lane.describe(after);
        List<AutomationModule.Point> settled = AutomationModule.withoutBasePoint(actual, desired);
        Object base = settled == actual ? null : after.get(0);
        for (Object point : removed)
            if (present.contains(point) && point != base)
                throw Errors.unknown(
                        Reason.READBACK_MISMATCH,
                        "A replaced point is still on the lane; inspect automation.points.get.");
        AutomationModule.verify(desired, settled);
        return written(Outcome.APPLIED, lane, actual, range, current, removed.size(), requested.size(), !existed);
    }

    private JsonObject written(
            Outcome outcome,
            Lane lane,
            List<AutomationModule.Point> all,
            double[] range,
            List<AutomationModule.Point> previous,
            int removed,
            int inserted,
            boolean created) {
        JsonObject out = AutomationModule.lane(
                Results.outcome(outcome, Verification.NATIVE_READBACK), lane.lane != null, all, range);
        a.provenance(out);
        JsonObject before = new JsonObject();
        before.addProperty("count", previous.size());
        before.addProperty("pointsHash", AutomationModule.hash(previous));
        out.add("previous", before);
        out.addProperty("removed", removed);
        out.addProperty("inserted", inserted);
        out.addProperty("laneCreated", created);
        out.addProperty("nativeUndo", true);
        out.addProperty("undoGroupingVerified", false);
        return out;
    }
}
