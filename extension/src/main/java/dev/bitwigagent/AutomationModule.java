package dev.bitwigagent;

import com.bitwig.extension.callback.*;
import com.bitwig.extension.controller.api.*;
import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Pattern;

/** Arranger automation points on a track's automation lane for volume, pan or a Grid module parameter (version-gated). */
final class AutomationModule implements HostModule {
    static final Set<String> METHODS = Set.of("automation.points.get", "automation.points.set");
    static final double MAX_BEATS = 4194304, EPS = 1e-9;
    static final int MAX_WRITE = 1024, MAX_READ = 1024, MAX_LANE = 65536, MAX_PARAMETER_ID = 1024;
    static final Set<String> KINDS = Set.of("volume", "pan", "grid");
    private static final Pattern UUID_PATTERN =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");

    /** One automation point as read or requested: beat, normalized value, interpolation, curve. */
    static final class Point {
        final double beat, value, curve;
        final boolean linear;

        Point(double beat, double value, boolean linear, double curve) {
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

    /** Which parameter's lane; deviceProxy/grid are set only for kind grid. */
    static final class Target {
        final String kind, expectedDeviceName, parameterId;
        final int deviceIndex;
        final UUID moduleId;
        Object deviceProxy;

        Target(String kind, int deviceIndex, String expectedDeviceName, UUID moduleId, String parameterId) {
            this.kind = kind;
            this.deviceIndex = deviceIndex;
            this.expectedDeviceName = expectedDeviceName;
            this.moduleId = moduleId;
            this.parameterId = parameterId;
        }

        boolean grid() {
            return kind.equals("grid");
        }

        JsonObject json() {
            JsonObject out = new JsonObject();
            out.addProperty("kind", kind);
            if (grid()) {
                out.addProperty("deviceIndex", deviceIndex);
                out.addProperty("expectedDeviceName", expectedDeviceName);
                out.addProperty("moduleId", moduleId.toString());
                out.addProperty("parameterId", parameterId);
            }
            return out;
        }

        static Target parse(JsonObject p) {
            JsonObject t = p.getAsJsonObject("target");
            String kind = t.get("kind").getAsString();
            if (!kind.equals("grid")) return new Target(kind, -1, null, null, null);
            return new Target(
                    kind,
                    t.get("deviceIndex").getAsInt(),
                    t.get("expectedDeviceName").getAsString(),
                    java.util.UUID.fromString(t.get("moduleId").getAsString()),
                    t.get("parameterId").getAsString());
        }
    }

    interface Access {
        void execute(Runnable work);
        /** range is null for the whole lane, else {startBeat, endBeat} inclusive. */
        JsonObject get(Object trackProxy, String trackId, Target target, double[] range, Runnable check);

        JsonObject set(
                Object trackProxy,
                String trackId,
                Target target,
                int expectedCount,
                String expectedHash,
                List<Point> points,
                double[] range,
                Runnable check,
                Runnable beforeWrite);
    }

    private final Scheduler scheduler;
    private final TrackWindow window;
    private final DeviceSources sources;
    private final TransportSafety transportSafety;
    private final NativeRuntime natives;
    private final Holds holds;
    private final TrackEpochs epochs;
    private Access access;
    private static final String PENDING = "automation.pending", QUARANTINE = "automation.quarantine";

    AutomationModule(
            Scheduler scheduler,
            TrackWindow window,
            DeviceSources sources,
            TransportSafety transportSafety,
            NativeRuntime natives,
            Holds holds) {
        this(scheduler, window, sources, transportSafety, natives, holds, null);
    }

    AutomationModule(
            Scheduler scheduler,
            TrackWindow window,
            DeviceSources sources,
            TransportSafety transportSafety,
            NativeRuntime natives,
            Holds holds,
            Access access) {
        this.scheduler = scheduler;
        this.window = window;
        this.sources = sources;
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
        switch (method) {
            case "automation.points.get":
                Params.fields(p, Set.of("trackId", "target"), Set.of("range"));
                break;
            case "automation.points.set":
                Params.fields(
                        p, Set.of("trackId", "target", "expectedCount", "expectedHash", "points"), Set.of("range"));
                Params.integer(p, "expectedCount", 0, MAX_LANE);
                JsonElement hash = p.get("expectedHash");
                if (!hash.isJsonPrimitive()
                        || !hash.getAsJsonPrimitive().isString()
                        || !HASH.matcher(hash.getAsString()).matches())
                    throw Errors.invalid(
                            "expectedHash must be the 64-character lowercase hex pointsHash from automation.points.get");
                points(p);
                break;
            default:
                throw Errors.notApplied(Reason.UNKNOWN_METHOD, "Unknown automation method.");
        }
        if (!DeviceSources.canonicalUuid(Params.string(p, "trackId")))
            throw Errors.invalid("trackId must be a canonical UUID");
        target(p);
        range(p);
    }

    private static void target(JsonObject p) {
        JsonElement value = p.get("target");
        if (!value.isJsonObject()) throw Errors.invalid("target must be an object");
        JsonObject t = value.getAsJsonObject();
        JsonElement kind = t.get("kind");
        if (kind == null
                || !kind.isJsonPrimitive()
                || !kind.getAsJsonPrimitive().isString()
                || !KINDS.contains(kind.getAsString()))
            throw Errors.invalid("target.kind must be one of " + new TreeSet<>(KINDS));
        if (!kind.getAsString().equals("grid")) {
            Params.fields(t, Set.of("kind"), Set.of());
            return;
        }
        Params.fields(t, Set.of("kind", "deviceIndex", "expectedDeviceName", "moduleId", "parameterId"), Set.of());
        Params.integer(t, "deviceIndex", 0, HostLimits.DEVICES - 1);
        Params.string(t, "expectedDeviceName");
        if (!UUID_PATTERN.matcher(Params.string(t, "moduleId")).matches())
            throw Errors.invalid("target.moduleId must be a canonical UUID");
        JsonElement id = t.get("parameterId");
        if (!id.isJsonPrimitive()
                || !id.getAsJsonPrimitive().isString()
                || id.getAsString().length() > MAX_PARAMETER_ID)
            throw Errors.invalid(
                    "target.parameterId must be a string of at most " + MAX_PARAMETER_ID + " UTF-16 code units");
    }

    static double[] range(JsonObject p) {
        if (!p.has("range")) return null;
        JsonElement value = p.get("range");
        if (!value.isJsonObject()) throw Errors.invalid("range must be an object");
        JsonObject r = value.getAsJsonObject();
        Params.fields(r, Set.of("startBeat", "endBeat"), Set.of());
        double start = Params.number(r, "startBeat", 0, MAX_BEATS), end = Params.number(r, "endBeat", 0, MAX_BEATS);
        if (start > end) throw Errors.invalid("range.startBeat must not exceed range.endBeat");
        return new double[] {start, end};
    }

    /** Requested points: strictly increasing beats, normalized values, inside range when one is given. */
    static List<Point> points(JsonObject p) {
        JsonElement value = p.get("points");
        if (!value.isJsonArray()) throw Errors.invalid("points must be an array");
        JsonArray array = value.getAsJsonArray();
        if (array.size() > MAX_WRITE) throw Errors.invalid("points must contain at most " + MAX_WRITE + " entries");
        double[] range = range(p);
        List<Point> out = new ArrayList<>();
        for (JsonElement item : array) {
            if (!item.isJsonObject()) throw Errors.invalid("each point must be an object");
            JsonObject n = item.getAsJsonObject();
            Params.fields(n, Set.of("beat", "value", "interpolation"), Set.of());
            double beat = Params.number(n, "beat", 0, MAX_BEATS), v = Params.number(n, "value", 0, 1);
            JsonElement interpolation = n.get("interpolation");
            if (!interpolation.isJsonPrimitive()
                    || !interpolation.getAsJsonPrimitive().isString()
                    || !Set.of("linear", "hold").contains(interpolation.getAsString()))
                throw Errors.invalid("interpolation must be linear or hold");
            if (!out.isEmpty() && beat <= out.get(out.size() - 1).beat)
                throw Errors.invalid("point beats must be strictly increasing");
            if (range != null && (beat < range[0] || beat > range[1]))
                throw Errors.invalid("every point must lie inside range");
            out.add(new Point(beat, v, interpolation.getAsString().equals("linear"), 0));
        }
        return out;
    }

    // ---- host-free lane logic shared by the native access -----------------------------------------------------

    static boolean inRange(double beat, double[] range) {
        return range == null || (beat >= range[0] && beat <= range[1]);
    }

    /** SHA-256 over every point's exact beat, value, interpolation and curve, in lane order. */
    static String hash(List<Point> points) {
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
    static List<Point> desired(List<Point> current, double[] range, List<Point> requested) {
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

    static boolean same(List<Point> a, List<Point> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!same(a.get(i), b.get(i))) return false;
        return true;
    }

    /** Exact readback: same count, order, beats, values (within 1e-9), interpolation and curve. */
    static void verify(List<Point> expected, List<Point> actual) {
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

    static void requireExpected(List<Point> current, int expectedCount, String expectedHash) {
        if (current.size() != expectedCount || !hash(current).equals(expectedHash))
            throw Errors.notApplied(
                    Reason.STALE_TARGET,
                    "The lane holds " + current.size()
                            + " points with a different pointsHash than expected; re-read automation.points.get.");
    }

    /** Lane summary into {@code out}: whole-lane count and hash, plus the points inside range (bounded). */
    static JsonObject lane(
            JsonObject out, String trackId, Target target, boolean exists, List<Point> all, double[] range) {
        out.addProperty("trackId", trackId);
        out.add("target", target.json());
        out.addProperty("laneExists", exists);
        out.addProperty("count", all.size());
        out.addProperty("pointsHash", hash(all));
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
        out.addProperty("units", "beats_from_arrangement_start_and_normalized_0_to_1_values");
        out.addProperty("nativeVersion", Bitwig614Runtime.VERSION);
        out.addProperty("selectionSideEffect", "none");
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
        String id = p.get("trackId").getAsString();
        int index = window.trackSlot(id);
        Track track = window.at(index);
        Target target = Target.parse(p);
        DeviceSources.RootReadTicket device = null;
        if (target.grid()) {
            JsonObject parent = new JsonObject();
            parent.addProperty("trackId", id);
            parent.addProperty("deviceIndex", target.deviceIndex);
            parent.addProperty("expectedDeviceName", target.expectedDeviceName);
            sources.requireZeroWindow(parent);
            device = sources.rootRead(parent);
            target.deviceProxy = device.device;
        }
        SafetyObservation safety = write ? transportSafety.stopped(op) : null;
        if (access == null)
            access = Bitwig614AutomationAccess.bind(natives.get(op).access());
        long epoch = epochs.get(index);
        DeviceSources.RootReadTicket ticket = device;
        Runnable check = () -> {
            scheduler.check(op);
            if (epochs.get(index) != epoch)
                throw Errors.notApplied(
                        Reason.STALE_CONTEXT, "Track bank changed while the automation operation was queued.");
            if (ticket != null) ticket.check();
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
                                    p.get("expectedCount").getAsInt(),
                                    p.get("expectedHash").getAsString(),
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
                                            Reason.STALE_CONTEXT, "Track target changed after native access.");
                                if (ticket != null) sources.guardRootRead(ticket);
                                if (write) holds.release(QUARANTINE);
                                holds.release(PENDING);
                                op.result.complete(
                                        JsonParser.parseString(serialized).getAsJsonObject());
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
