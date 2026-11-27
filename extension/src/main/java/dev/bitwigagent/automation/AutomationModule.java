package dev.bitwigagent.automation;

import com.bitwig.extension.callback.*;
import com.bitwig.extension.controller.api.*;
import com.google.gson.*;
import dev.bitwigagent.core.BoundedJson;
import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.Holds;
import dev.bitwigagent.core.HostModule;
import dev.bitwigagent.core.HostOperation;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.core.SafetyObservation;
import dev.bitwigagent.core.Scheduler;
import dev.bitwigagent.device.ParameterResolver;
import dev.bitwigagent.host.DeviceSources;
import dev.bitwigagent.host.NativeRuntime;
import dev.bitwigagent.host.RequestSnapshot;
import dev.bitwigagent.host.TrackEpochs;
import dev.bitwigagent.host.TrackWindow;
import dev.bitwigagent.host.TransportSafety;
import dev.bitwigagent.nativehost.NativeAutomationAccess;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Arranger automation points on a track's automation lane for volume, pan or a Grid module parameter (version-gated). */
public final class AutomationModule implements HostModule {
    static final Set<String> METHODS = Set.of("automation.points.get", "automation.points.set");
    static final double MAX_BEATS = 4194304, EPS = 1e-9;
    public static final int MAX_READ = 1024, MAX_LANE = 65536;

    /** One automation point as read or requested: beat, normalized value, interpolation, curve. */
    public static final class Point {
        public final double beat, value, curve;
        public final boolean linear;

        public Point(double beat, double value, boolean linear, double curve) {
            this.beat = beat;
            this.value = value;
            this.linear = linear;
            this.curve = curve;
        }

        String interpolation() {
            return linear ? "linear" : "hold";
        }

        JsonObject json() {
            JsonObject out = new JsonObject();
            out.addProperty("beat", beat);
            out.addProperty("value", value);
            out.addProperty("interpolation", interpolation());
            out.addProperty("curve", curve);
            return out;
        }
    }

    /** Which parameter's lane: a track's volume or pan, or a Grid module parameter of one of its devices. */
    public static final class Target {
        public final String kind, parameterId;
        public final UUID moduleId;
        public final DeviceSources.Source device;

        Target(String kind, DeviceSources.Source device, UUID moduleId, String parameterId) {
            this.kind = kind;
            this.device = device;
            this.moduleId = moduleId;
            this.parameterId = parameterId;
        }

        public boolean grid() {
            return kind.equals("grid");
        }
    }

    public interface Access {
        void execute(Runnable work);
        /** range is null for the whole lane, else {startBeat, endBeat} inclusive. */
        JsonObject get(Object trackProxy, String trackId, Target target, double[] range, Runnable check);

        /** {@code expectedHash} is null when the request has no expect. */
        JsonObject set(
                Object trackProxy,
                String trackId,
                Target target,
                String expectedHash,
                List<Point> points,
                double[] range,
                Runnable check,
                Runnable beforeWrite);
    }

    private final Scheduler scheduler;
    private final TrackWindow window;
    private final ParameterResolver resolver;
    private final TransportSafety transportSafety;
    private final NativeRuntime natives;
    private final Holds holds;
    private final TrackEpochs epochs;
    private Access access;
    private static final String PENDING = "automation.pending", QUARANTINE = "automation.quarantine";

    public AutomationModule(
            Scheduler scheduler,
            TrackWindow window,
            ParameterResolver resolver,
            TransportSafety transportSafety,
            NativeRuntime natives,
            Holds holds) {
        this(scheduler, window, resolver, transportSafety, natives, holds, null);
    }

    public AutomationModule(
            Scheduler scheduler,
            TrackWindow window,
            ParameterResolver resolver,
            TransportSafety transportSafety,
            NativeRuntime natives,
            Holds holds,
            Access access) {
        this.scheduler = scheduler;
        this.window = window;
        this.resolver = resolver;
        this.transportSafety = transportSafety;
        this.natives = natives;
        this.holds = holds;
        this.access = access;
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

    /** What the schema cannot say: the range is ordered. */
    static double[] range(JsonObject p) {
        if (!p.has("range")) return null;
        JsonObject r = p.getAsJsonObject("range");
        double start = r.get("startBeat").getAsDouble(), end = r.get("endBeat").getAsDouble();
        if (start > end) throw Errors.invalid("range.startBeat must not exceed range.endBeat");
        return new double[] {start, end};
    }

    /** Requested points: strictly increasing beats, inside range when one is given. */
    static List<Point> points(JsonObject p) {
        double[] range = range(p);
        List<Point> out = new ArrayList<>();
        for (JsonElement item : p.getAsJsonArray("points")) {
            JsonObject n = item.getAsJsonObject();
            double beat = n.get("beat").getAsDouble();
            if (!out.isEmpty() && beat <= out.get(out.size() - 1).beat)
                throw Errors.invalid("point beats must be strictly increasing");
            if (range != null && (beat < range[0] || beat > range[1]))
                throw Errors.invalid("every point must lie inside range");
            out.add(new Point(
                    beat,
                    n.get("value").getAsDouble(),
                    n.get("interpolation").getAsString().equals("linear"),
                    0));
        }
        return out;
    }

    // ---- host-free lane logic shared by the native access -----------------------------------------------------

    public static boolean inRange(double beat, double[] range) {
        return range == null || (beat >= range[0] && beat <= range[1]);
    }

    /** SHA-256 over every point's exact beat, value, interpolation and curve, in lane order. */
    public static String hash(List<Point> points) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Point point : points)
                digest.update((point.beat + "," + point.value + "," + point.interpolation() + "," + point.curve + "\n")
                        .getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(64);
            for (byte b : digest.digest()) {
                out.append(Character.forDigit((b >>> 4) & 15, 16));
                out.append(Character.forDigit(b & 15, 16));
            }
            return out.toString();
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    /** The lane after the write: points outside range are kept, the requested ones replace the rest. */
    public static List<Point> desired(List<Point> current, double[] range, List<Point> requested) {
        List<Point> out = new ArrayList<>();
        for (Point point : current) if (!inRange(point.beat, range)) out.add(point);
        out.addAll(requested);
        out.sort(Comparator.comparingDouble(point -> point.beat));
        return out;
    }

    static boolean same(Point a, Point b) {
        return Math.abs(a.beat - b.beat) <= EPS
                && Math.abs(a.value - b.value) <= EPS
                && a.linear == b.linear
                && Math.abs(a.curve - b.curve) <= EPS;
    }

    public static boolean same(List<Point> a, List<Point> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!same(a.get(i), b.get(i))) return false;
        return true;
    }

    /** Exact readback: same count, order, beats, values (within 1e-9), interpolation and curve. */
    public static void verify(List<Point> expected, List<Point> actual) {
        if (actual.size() != expected.size())
            throw Errors.unknown(
                    Reason.READBACK_MISMATCH,
                    "Native lane holds " + actual.size() + " points after the write, expected " + expected.size()
                            + "; inspect automation.points.get before another write.");
        for (int i = 0; i < expected.size(); i++)
            if (!same(expected.get(i), actual.get(i)))
                throw Errors.unknown(
                        Reason.READBACK_MISMATCH,
                        "Native point " + i + " reads back as " + actual.get(i).json() + ", expected "
                                + expected.get(i).json() + "; inspect automation.points.get before another write.");
    }

    public static void requireExpected(List<Point> current, String expectedHash) {
        if (expectedHash == null || hash(current).equals(expectedHash)) return;
        JsonObject observed = new JsonObject();
        observed.addProperty("pointsHash", hash(current));
        throw Errors.expectMismatch("The lane's pointsHash differs from expect; nothing was written.", observed);
    }

    /**
     * Bitwig keeps a base point at beat 0 when the last points of a lane are deleted: a lane that should be empty and
     * holds exactly one point at beat 0 counts as cleared. Nothing else is excused.
     */
    public static List<Point> withoutBasePoint(List<Point> lane, List<Point> wanted) {
        boolean base = wanted.isEmpty() && lane.size() == 1 && lane.get(0).beat <= EPS;
        return base ? List.of() : lane;
    }

    /** Lane summary into {@code out}: whole-lane count and hash, plus the points inside range (bounded). */
    public static JsonObject lane(JsonObject out, boolean exists, List<Point> all, double[] range) {
        JsonObject lane = new JsonObject();
        lane.addProperty("exists", exists);
        lane.addProperty("count", all.size());
        lane.addProperty("pointsHash", hash(all));
        out.add("lane", lane);
        if (range != null) {
            JsonObject r = new JsonObject();
            r.addProperty("startBeat", range[0]);
            r.addProperty("endBeat", range[1]);
            out.add("range", r);
        }
        JsonArray rows = new JsonArray();
        int inRange = 0;
        for (Point point : all) {
            if (!inRange(point.beat, range)) continue;
            inRange++;
            if (rows.size() < MAX_READ) rows.add(point.json());
        }
        out.add("points", rows);
        out.addProperty("pointsInRange", inRange);
        out.addProperty("truncated", inRange > rows.size());
        out.addProperty("selectionSideEffect", "none");
        return out;
    }

    /**
     * The lane owner of a resolved parameter: a track's volume or pan, or a Grid module parameter of a root device (a
     * device inside a chain is unsupported_scope when its ref is resolved).
     */
    private static Target target(ParameterResolver.Resolved resolved) {
        if (resolved instanceof ParameterResolver.TrackParameter)
            return new Target(((ParameterResolver.TrackParameter) resolved).id, null, null, null);
        ParameterResolver.ModuleParameter module = (ParameterResolver.ModuleParameter) resolved;
        return new Target("grid", module.device, module.ownerId, module.id);
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
        ParameterResolver.Resolved resolved = resolver.resolve(ref);
        Target target = target(resolved);
        int index = resolved instanceof ParameterResolver.TrackParameter
                ? ((ParameterResolver.TrackParameter) resolved).slot
                : target.device.track.slot;
        Track track = window.at(index);
        String id = track.channelId().get();
        RequestSnapshot snapshot = resolved.snapshot();
        SafetyObservation safety = write ? transportSafety.stopped(op) : null;
        if (access == null) access = NativeAutomationAccess.bind(natives.get(op).access());
        long epoch = epochs.get(index);
        Runnable check = () -> {
            scheduler.check(op);
            if (epochs.get(index) != epoch)
                throw Errors.notApplied(
                        Reason.TARGET_CHANGED, "Track bank changed while the automation operation was queued.");
            snapshot.require(op);
            if (write) transportSafety.guardStopped(safety);
        };
        Runnable beforeWrite = () -> {
            check.run();
            op.markDispatched();
            holds.place(
                    QUARANTINE,
                    "An automation point write on track " + id + " has an unknown outcome until read back.");
        };
        Access bridge = access;
        double[] range = range(p);
        holds.inFlight(PENDING, "A native automation operation on track " + id + " is running.");
        try {
            bridge.execute(() -> {
                try {
                    check.run();
                    JsonObject result = write
                            ? bridge.set(
                                    track,
                                    id,
                                    target,
                                    p.has("expect")
                                            ? p.getAsJsonObject("expect")
                                                    .get("pointsHash")
                                                    .getAsString()
                                            : null,
                                    points(p),
                                    range,
                                    check,
                                    beforeWrite)
                            : bridge.get(track, id, target, range, check);
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
