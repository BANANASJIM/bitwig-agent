package dev.bitwigagent.automation;

import com.bitwig.extension.controller.api.*;
import com.google.gson.*;
import dev.bitwigagent.automation.AutomationLane.Observed;
import dev.bitwigagent.core.BoundedJson;
import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.Holds;
import dev.bitwigagent.core.HostLimits;
import dev.bitwigagent.core.HostModule;
import dev.bitwigagent.core.HostOperation;
import dev.bitwigagent.core.Outcome;
import dev.bitwigagent.core.PortHandle;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.core.Refs;
import dev.bitwigagent.core.Results;
import dev.bitwigagent.core.Scheduler;
import dev.bitwigagent.core.ValueInput;
import dev.bitwigagent.core.Verification;
import dev.bitwigagent.device.ParameterResolver;
import dev.bitwigagent.host.DeviceSources;
import dev.bitwigagent.host.RequestSnapshot;
import dev.bitwigagent.host.TrackEpochs;
import dev.bitwigagent.host.TrackWindow;
import dev.bitwigagent.host.TransportSafety;
import java.util.*;
import java.util.function.Function;

/** Arranger automation points on a track's automation lane for any automatable parameter ref (version-gated). */
public final class AutomationModule implements HostModule {
    static final Set<String> METHODS = Set.of("automation.points.get", "automation.points.set");

    /** Which parameter's lane: a track's volume or pan, a Grid module parameter, or a device's direct parameter. */
    public static final class Target {
        public final Kind kind;
        /** The root device the parameter is on or below; null for a track's own parameter. */
        public final DeviceSources.Source device;
        /** The parameter ref, as the request gave it. */
        public final JsonObject ref;

        public enum Kind {
            VOLUME,
            PAN,
            GRID,
            DEVICE
        }

        Target(Kind kind, DeviceSources.Source device, JsonObject ref) {
            this.kind = kind;
            this.device = device;
            this.ref = ref;
        }

        public UUID moduleId() {
            return UUID.fromString(ref.getAsJsonObject("owner").get("id").getAsString());
        }

        public String parameterId() {
            return ref.get("id").getAsString();
        }
    }

    /** The native lane of one target; the adapter only reads and writes points, every rule is the caller's. */
    public interface Access {
        void execute(Runnable work);

        /** The lane now, with each of {@code displays} resolved on its parameter. */
        Observed read(Object trackProxy, String trackId, Target target, List<String> displays, Runnable check);

        /**
         * In one transaction: reads the lane, asks {@code plan} what to write and, unless that is empty, runs
         * {@code beforeWrite}, creates the lane when missing, deletes and inserts, then reads the lane again.
         */
        Edited edit(
                Object trackProxy,
                String trackId,
                Target target,
                List<String> displays,
                Function<Observed, AutomationPlan.Write> plan,
                Runnable check,
                Runnable beforeWrite);
    }

    /** What an edit saw: the lane before, the plan's write, and the lane after it (the same lane when nothing was written). */
    public static final class Edited {
        final Observed before, after;
        final AutomationPlan.Write write;
        final boolean created;

        public Edited(Observed before, AutomationPlan.Write write, Observed after, boolean created) {
            this.before = before;
            this.write = write;
            this.after = after;
            this.created = created;
        }
    }

    private final Scheduler scheduler;
    private final TrackWindow window;
    private final ParameterResolver resolver;
    private final TransportSafety transportSafety;
    private final PortHandle<Access> access;
    private final Holds holds;
    private final TrackEpochs epochs;
    private static final String PENDING = "automation.pending", QUARANTINE = "automation.quarantine";

    public AutomationModule(
            Scheduler scheduler,
            TrackWindow window,
            ParameterResolver resolver,
            TransportSafety transportSafety,
            PortHandle<Access> access,
            Holds holds) {
        this.scheduler = scheduler;
        this.window = window;
        this.resolver = resolver;
        this.transportSafety = transportSafety;
        this.access = access;
        this.holds = holds;
        this.epochs = new TrackEpochs(window);
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
        range(p);
        if (method.equals("automation.points.set")) points(p);
    }

    /** What the schema cannot say: the range is ordered. Bounds snap to the beat grid; {@code "all"} is ALL. */
    static double[] range(JsonObject p) {
        if (!p.has("range")) return null;
        if (p.get("range").isJsonPrimitive()) return AutomationLane.ALL;
        JsonObject r = p.getAsJsonObject("range");
        double start = AutomationLane.Point.beat(r.get("start").getAsDouble()),
                end = AutomationLane.Point.beat(r.get("end").getAsDouble());
        if (start > end) throw Errors.invalid("range.start must not exceed range.end");
        return new double[] {start, end};
    }

    /** A point's curve, within what Bitwig stores. */
    private static double curve(double curve) {
        if (!(Math.abs(curve) <= HostLimits.LIMITS.maxCurve))
            throw Errors.invalid("points[].curve must be from -" + HostLimits.LIMITS.maxCurve + " to "
                    + HostLimits.LIMITS.maxCurve + ".");
        return curve;
    }

    /**
     * Requested points: beats non-decreasing on the point grid and inside range when one is given; each knows its
     * place among the points at its beat.
     */
    static List<AutomationPlan.Request> points(JsonObject p) {
        double[] range = range(p);
        List<AutomationPlan.Request> out = new ArrayList<>();
        for (JsonElement item : p.getAsJsonArray("points")) {
            JsonObject n = item.getAsJsonObject();
            double beat = AutomationLane.Point.beat(n.get("beat").getAsDouble());
            AutomationPlan.Request before = out.isEmpty() ? null : out.get(out.size() - 1);
            if (before != null && beat < before.beat)
                throw Errors.invalid("point beats must be non-decreasing on the 2^-20 beat grid");
            int ordinal = before != null && beat == before.beat ? before.ordinal + 1 : 0;
            if (!AutomationLane.inRange(beat, range)) throw Errors.invalid("every point must lie inside range");
            ValueInput value = ValueInput.parse("points[].value", n.get("value"));
            boolean display = value.form == ValueInput.Form.DISPLAY;
            out.add(new AutomationPlan.Request(
                    beat,
                    display ? null : value.normalized("points[].value"),
                    display ? value.text : null,
                    n.has("interpolation")
                            ? n.get("interpolation").getAsString().equals("linear")
                            : null,
                    n.has("curve") ? curve(n.get("curve").getAsDouble()) : null,
                    ordinal));
        }
        return out;
    }

    /**
     * Which lane a parameter ref names, by its owner kind: a track's volume or pan, a Grid module parameter or a
     * device's direct parameter. A Remote Control maps a device parameter and a modulator parameter has no lane here:
     * both are unsupported_scope before anything is resolved.
     */
    static Target.Kind kind(JsonObject ref) {
        switch (Refs.kind(ref.getAsJsonObject("owner"))) {
            case "track":
                return ref.get("id").getAsString().equals("volume") ? Target.Kind.VOLUME : Target.Kind.PAN;
            case "gridModule":
                return Target.Kind.GRID;
            case "device":
                return Target.Kind.DEVICE;
            case "remotePage":
                throw Errors.notApplied(
                        Reason.UNSUPPORTED_SCOPE,
                        "automation.points does not take a Remote Control; target the device parameter it maps.",
                        "parameters.list");
            default:
                throw Errors.notApplied(
                        Reason.UNSUPPORTED_SCOPE, "automation.points does not take a modulator parameter.");
        }
    }

    /** The read result: the lane as observed. */
    static JsonObject read(Observed lane, double[] range) {
        return AutomationLane.json(new JsonObject(), lane, range);
    }

    /** The write result: verified against the plan when it wrote, already_matched when it had nothing to write. */
    static JsonObject written(Edited edited, double[] range) {
        boolean applied = !edited.write.empty;
        if (applied) AutomationPlan.verify(edited.write, edited.after);
        JsonObject out = AutomationLane.json(
                Results.outcome(applied ? Outcome.APPLIED : Outcome.ALREADY_MATCHED, Verification.NATIVE_READBACK),
                edited.after,
                range);
        JsonObject before = new JsonObject();
        before.addProperty("count", edited.before.points.size());
        before.addProperty("pointsHash", AutomationLane.hash(edited.before.points));
        out.add("previous", before);
        out.add("summary", edited.write.summary(applied));
        out.addProperty("laneCreated", edited.created);
        out.addProperty("nativeUndo", applied);
        out.addProperty("undoGroupingVerified", false);
        return out;
    }

    // ---- dispatch ---------------------------------------------------------------------------------------------

    @Override
    public void dispatch(HostOperation op, String method, JsonObject p) {
        scheduler.check(op);
        boolean write = method.equals("automation.points.set");
        if (holds.held(PENDING))
            throw Errors.notApplied(Reason.BUSY, "Another native automation operation is pending.");
        if (write && holds.held(QUARANTINE))
            throw holds.blocked(
                    QUARANTINE,
                    "An earlier automation point write has an unknown outcome; automation writes are refused.",
                    "Inspect automation.points.get");
        JsonObject ref = p.getAsJsonObject("target");
        Target.Kind kind = kind(ref);
        ParameterResolver.Resolved resolved = resolver.resolve(ref);
        Target target = new Target(kind, resolved.device(), ref);
        int index =
                target.device == null ? ((ParameterResolver.TrackParameter) resolved).slot : target.device.track.slot;
        Track track = window.at(index);
        String id = track.channelId().get();
        RequestSnapshot snapshot = resolved.snapshot();
        Access bridge = access.get(op);
        long epoch = epochs.get(index);
        Runnable check = () -> {
            scheduler.check(op);
            if (epochs.get(index) != epoch)
                throw Errors.notApplied(
                        Reason.TARGET_CHANGED, "Track bank changed while the automation operation was queued.");
            snapshot.require(op);
            if (write) transportSafety.recheck(op);
        };
        Runnable beforeWrite = () -> {
            check.run();
            op.markDispatched();
            holds.place(
                    QUARANTINE,
                    "An automation point write on track " + id + " has an unknown outcome until read back.");
        };
        double[] range = range(p);
        holds.inFlight(PENDING, "A native automation operation on track " + id + " is running.");
        try {
            bridge.execute(() -> {
                try {
                    check.run();
                    JsonObject result;
                    if (write) {
                        AutomationPlan plan = new AutomationPlan(
                                p.has("expect")
                                        ? p.getAsJsonObject("expect")
                                                .get("pointsHash")
                                                .getAsString()
                                        : null,
                                points(p),
                                range);
                        result = written(
                                bridge.edit(track, id, target, plan.displays(), plan, check, beforeWrite), range);
                    } else result = read(bridge.read(track, id, target, List.of(), check), range);
                    String serialized = BoundedJson.serialize(
                            result,
                            240000,
                            Reason.OUT_OF_BOUNDS,
                            "Automation response exceeds the bounded transport size; pass a smaller range.");
                    scheduler.schedule(
                            op,
                            () -> {
                                check.run();
                                if (!window.trackMatches(id, track))
                                    throw Errors.notApplied(
                                            Reason.TARGET_CHANGED, "Track target changed after native access.");
                                if (write) holds.release(QUARANTINE);
                                holds.release(PENDING);
                                JsonObject out =
                                        JsonParser.parseString(serialized).getAsJsonObject();
                                out.add("ref", ref);
                                op.result.complete(out);
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
