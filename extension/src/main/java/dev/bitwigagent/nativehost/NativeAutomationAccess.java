package dev.bitwigagent.nativehost;

import com.google.gson.JsonObject;
import dev.bitwigagent.automation.AutomationLane;
import dev.bitwigagent.automation.AutomationModule;
import dev.bitwigagent.automation.AutomationPlan;
import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.core.RpcException;
import dev.bitwigagent.device.NativeChains;
import dev.bitwigagent.grid.GridSnapshotReader;
import dev.bitwigagent.host.NativeReadContext;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.function.DoubleFunction;
import java.util.function.Function;

/**
 * Exact bindings for the arranger automation lane of one parameter on a player track. Paths follow Bitwig's own
 * model tests (AutomationFollowStretchTest, AutomationPropertiesAfterPointDeletionTest) and lane seeding: track
 * {@code track.channel.automationLanes()}, {@code automation.reference.of(parameter)} parameter reference,
 * {@code automation.laneSet.find(target)} find lane, {@code automation.laneSet.add(target,false,false,false)} "Add
 * Automation Lane" without the seed point or UI reveal, {@code automation.lane.content().points()} point timeline,
 * {@code value.range.newPoint(beat,value,interpolation,curve)} new point in the lane's value range (the curve is
 * clamped to [-1, 1] by the point), {@code automation.timeline.insert(point,index)} insert,
 * {@code timeline.remove(point)} delete. Values are normalized through the lane's range
 * ({@code value.scale.normalized(value)}); beats are arrangement quarter notes. A device's direct parameter is
 * {@code device.base.parameter("CONTENTS/<id>")} on the device a {@link NativeChains.Finder} walks to. Display text is
 * the controller's own: {@code parameter.text.format(definition, plain, {ADD_UNIT_LABEL, LIMITED_LENGTH})} (what
 * {@code parameter.controller.display} returns for the parameter's current value), and the host's text-entry parser
 * {@code parameter.text.parse(text, definition)}, which throws on text it cannot read; the float definition maps
 * plain to normalized linearly and clamps. No UI selection is read or changed.
 */
public final class NativeAutomationAccess implements AutomationModule.Access {
    private final NativeHost.Access a;
    private NativeGridAdapter gridAdapter;
    private NativeChainAccess chainAccess;
    private Text text;
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
            throw unavailable("Exact automation bindings unavailable: ", error);
        }
    }

    private static RpcException unavailable(String what, Throwable error) {
        return Errors.notApplied(Reason.NATIVE_UNAVAILABLE, what + NativeHost.diagnostic(error));
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

    /**
     * Display text bindings, added after the 6.1.4 entry shipped: resolved on first use, so a host without them fails
     * only the reads and writes that need them.
     */
    private final class Text {
        final Method format, parse, normalize, denormalize;
        final EnumSet<?> options;

        Text() throws ReflectiveOperationException {
            Class<?> definition = a.type("parameter.valueDefinition"), option = a.type("parameter.textOption");
            if (!definition.isAssignableFrom(rangeType) || !option.isEnum())
                throw new NoSuchMethodException("Inspected parameter text formatting ancestry differs.");
            format =
                    a.exactMethod("parameter.text.format", String.class, true, definition, double.class, EnumSet.class);
            parse = a.exactMethod("parameter.text.parse", double.class, true, String.class, definition);
            normalize = a.exactMethod("parameter.floatDefinition.normalize", double.class, false, double.class);
            denormalize = a.exactMethod("parameter.floatDefinition.denormalize", double.class, false, double.class);
            options = options(option, "ADD_UNIT_LABEL", "LIMITED_LENGTH");
        }

        String display(Object definition, double normalized) {
            return (String)
                    a.invoke(format, null, definition, (Double) a.invoke(denormalize, definition, normalized), options);
        }

        AutomationLane.Parsed parse(Object definition, String shown) {
            double plain;
            try {
                plain = (Double) a.invoke(parse, null, shown, definition);
            } catch (RpcException error) {
                if (error.getCause() instanceof InvocationTargetException) return AutomationLane.Parsed.UNPARSEABLE;
                throw error;
            }
            double n = (Double) a.invoke(normalize, definition, plain);
            return new AutomationLane.Parsed(
                    plain, n, (Double) a.invoke(denormalize, definition, n), display(definition, n));
        }
    }

    /** The host's own option names; the enum class is only known at runtime. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static EnumSet<?> options(Class<?> type, String... names) {
        EnumSet set = EnumSet.noneOf((Class) type);
        for (String name : names) set.add(Enum.valueOf((Class) type, name));
        return set;
    }

    private Text text() {
        if (text == null) {
            try {
                text = new Text();
            } catch (ReflectiveOperationException | LinkageError error) {
                throw unavailable("Automation display bindings unavailable: ", error);
            }
        }
        return text;
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
        final Runnable check;
        Object lane, timeline;

        Lane(Object proxy, String trackId, AutomationModule.Target spec, Runnable check) {
            a.documentThread();
            check.run();
            this.proxy = proxy;
            this.spec = spec;
            this.check = check;
            track = resolver.resolve(proxy, trackId, NativeTrackResolver.Kind.CHANNEL);
            laneSet = a.invoke(lanes, track);
            if (laneSet == null)
                throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "Track has no automation lane set.");
            grid = spec.kind == AutomationModule.Target.Kind.GRID
                    ? gridAdapter().automationParameter(spec.device, spec.moduleId(), spec.parameterId(), check)
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
            switch (spec.kind) {
                case GRID:
                    return grid.parameter;
                case DEVICE:
                    NativeChains.Access chains = chains();
                    return new NativeChains.Finder(chains, chains.root(spec.device, check), spec.device.ref())
                            .parameter(spec.ref);
                default:
                    Object mixer = a.invoke(channel, track);
                    if (mixer == null)
                        throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "Track has no native mixer channel.");
                    return a.invoke(spec.kind == AutomationModule.Target.Kind.VOLUME ? volume : pan, mixer);
            }
        }

        void attach(Object found) {
            lane = found;
            timeline = found == null ? null : a.invoke(points, a.invoke(laneTimeline, found));
            if (found != null && timeline == null)
                throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "Automation lane has no point timeline.");
            if (timeline != null) a.requireModelThread(timeline);
        }

        void guard() {
            check.run();
            a.requireModelThread(track);
            context.guard(capture, models, labels, check);
            if (grid != null) gridAdapter().guardAutomationParameter(grid);
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
            if (out.size() > AutomationLane.MAX_LANE)
                throw Errors.notApplied(
                        Reason.OUT_OF_BOUNDS,
                        "Automation lane holds more than " + AutomationLane.MAX_LANE + " points.");
            return out;
        }

        /** The lane's value range, or the parameter's own while it has no lane; only decimal ones are supported. */
        Object valueRange() {
            Object r = timeline == null ? a.invoke(referenceRange, ref) : a.invoke(range, timeline);
            if (!rangeType.isInstance(r))
                throw Errors.notApplied(
                        Reason.UNSUPPORTED_SCOPE, "Only continuous (decimal) automation lanes are supported.");
            return r;
        }

        AutomationLane.Observed observe(List<String> displays) {
            List<Object> natives = natives();
            List<AutomationLane.Point> out = new ArrayList<>();
            Object r = natives.isEmpty() ? null : valueRange();
            for (Object point : natives) {
                if (!pointType.isInstance(point))
                    throw Errors.notApplied(Reason.UNSUPPORTED_SCOPE, "Automation lane holds a non-point event.");
                Object mode = a.invoke(interpolation, point);
                if (mode != hold && mode != linear)
                    throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "Unknown automation interpolation.");
                out.add(new AutomationLane.Point(
                        (Double) a.invoke(time, point),
                        (Double) a.invoke(normalized, r, point),
                        mode == linear,
                        (Double) a.invoke(curve, point),
                        point));
            }
            Map<String, AutomationLane.Parsed> parsed = new HashMap<>();
            for (String shown : displays) parsed.computeIfAbsent(shown, t -> text().parse(valueRange(), t));
            DoubleFunction<String> display = n -> text().display(valueRange(), n);
            JsonObject provenance = new JsonObject();
            a.provenance(provenance);
            return new AutomationLane.Observed(lane != null, out, parsed, display, provenance);
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

    private NativeChainAccess chains() {
        if (chainAccess == null) {
            try {
                chainAccess = new NativeChainAccess(a);
            } catch (ReflectiveOperationException | LinkageError error) {
                throw unavailable("Native chain binding unavailable: ", error);
            }
        }
        return chainAccess;
    }

    @Override
    public AutomationLane.Observed read(
            Object trackProxy, String trackId, AutomationModule.Target target, List<String> displays, Runnable check) {
        Lane lane = new Lane(trackProxy, trackId, target, check);
        AutomationLane.Observed observed = lane.observe(displays);
        lane.guard();
        return observed;
    }

    @Override
    public AutomationModule.Edited edit(
            Object trackProxy,
            String trackId,
            AutomationModule.Target target,
            List<String> displays,
            Function<AutomationLane.Observed, AutomationPlan.Write> plan,
            Runnable check,
            Runnable beforeWrite) {
        Lane lane = new Lane(trackProxy, trackId, target, check);
        lane.valueRange();
        AutomationLane.Observed before = lane.observe(displays);
        AutomationPlan.Write write = plan.apply(before);
        lane.guard();
        if (write.empty) return new AutomationModule.Edited(before, write, before, false);
        boolean created = lane.lane == null;
        beforeWrite.run();
        lane.transaction("Set Automation Points", () -> {
            if (created) {
                lane.attach(a.invoke(addLane, lane.laneSet, lane.ref, false, false, false));
                if (lane.lane == null)
                    throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "Bitwig did not create the automation lane.");
            }
            Object valueRange = lane.valueRange();
            for (Object point : write.delete) a.invoke(delete, lane.timeline, point);
            for (int i = 0; i < write.insert.size(); i++) {
                AutomationLane.Point p = write.insert.get(i);
                Object point = a.invoke(newPoint, valueRange, p.beat, p.value, p.linear ? linear : hold, p.curve);
                if (point == null)
                    throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "Bitwig did not create an automation point.");
                a.invoke(insert, lane.timeline, point, write.at.get(i));
            }
        });
        lane.guard();
        return new AutomationModule.Edited(before, write, lane.observe(List.of()), created);
    }
}
