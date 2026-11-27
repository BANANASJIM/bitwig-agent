package dev.bitwigagent.automation;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.bitwigagent.core.HostLimits;
import dev.bitwigagent.core.RangeEdit;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.DoubleFunction;

/** What an automation lane holds, as the native adapter reads it, and how it is reported. */
public final class AutomationLane {
    private AutomationLane() {}

    public static final int MAX_READ = 1024, MAX_LANE = 65536;
    /** The whole lane as a range: {@code range:"all"}. */
    public static final double[] ALL = {Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY};

    /**
     * One canonical automation point: beat on the bundle's point grid, value and curve on a 1e-9 grid, so a point read
     * back and written again is equal by construction. A step is two points at one beat; {@code ordinal} is the
     * point's 0-based place among the points at its beat (lane order on a lane, array order in a request), and with
     * the beat it identifies the point. {@code handle} is the adapter's own object for the point (null for a point not
     * on the lane). Neither takes part in equality.
     */
    public static final class Point {
        static final double BEAT_GRID = HostLimits.LIMITS.pointBeatDivisor, VALUE_GRID = 1e9;
        public final double beat, value, curve;
        public final boolean linear;
        public final Object handle;
        final int ordinal;

        public Point(double beat, double value, boolean linear, double curve) {
            this(beat, value, linear, curve, null);
        }

        public Point(double beat, double value, boolean linear, double curve, Object handle) {
            this(beat, value, linear, curve, handle, 0);
        }

        Point(double beat, double value, boolean linear, double curve, Object handle, int ordinal) {
            this.beat = beat(beat);
            this.value = Math.rint(value * VALUE_GRID) / VALUE_GRID;
            this.linear = linear;
            this.curve = Math.rint(curve * VALUE_GRID) / VALUE_GRID;
            this.handle = handle;
            this.ordinal = ordinal;
        }

        Point at(int ordinal) {
            return new Point(beat, value, linear, curve, handle, ordinal);
        }

        static double beat(double beat) {
            return Math.rint(beat * BEAT_GRID) / BEAT_GRID;
        }

        String interpolation() {
            return linear ? "linear" : "hold";
        }

        JsonObject json(DoubleFunction<String> display) {
            JsonObject out = new JsonObject();
            out.addProperty("beat", beat);
            out.addProperty("value", value);
            out.addProperty("display", display.apply(value));
            out.addProperty("interpolation", interpolation());
            out.addProperty("curve", curve);
            return out;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Point)) return false;
            Point o = (Point) other;
            return beat == o.beat && value == o.value && linear == o.linear && curve == o.curve;
        }

        @Override
        public int hashCode() {
            return Objects.hash(beat, value, linear, curve);
        }

        @Override
        public String toString() {
            return "{beat " + beat + ", value " + value + ", " + interpolation() + ", curve " + curve + "}";
        }
    }

    /** Points are identified by beat and their place at that beat, and compared canonically. */
    static final RangeEdit.Kind<Point> KIND = new RangeEdit.Kind<>() {
        public Object key(Point point) {
            return List.of(point.beat, point.ordinal);
        }

        public boolean same(Point a, Point b) {
            return a.equals(b);
        }
    };

    /**
     * A display text as the lane's own parameter resolves it: the host's text parser gives {@code plain}, the lane's
     * value range maps it to {@code normalized} (clamped) and back to {@code restored}, and {@code shown} is the
     * host's display of {@code normalized}. {@code parsed} is false when the host cannot read the text.
     */
    public static final class Parsed {
        public final boolean parsed;
        public final double plain, normalized, restored;
        public final String shown;

        public Parsed(double plain, double normalized, double restored, String shown) {
            this.parsed = true;
            this.plain = plain;
            this.normalized = normalized;
            this.restored = restored;
            this.shown = shown;
        }

        private Parsed() {
            parsed = false;
            plain = normalized = restored = Double.NaN;
            shown = null;
        }

        public static final Parsed UNPARSEABLE = new Parsed();
    }

    /**
     * One read of a lane. {@code display} is the host's display text of a normalized value of this parameter;
     * {@code parsed} holds the display texts the request asked for; {@code provenance} is the native evidence fields.
     */
    public static final class Observed {
        public final boolean exists;
        public final List<Point> points;
        public final Map<String, Parsed> parsed;
        public final DoubleFunction<String> display;
        public final JsonObject provenance;

        public Observed(
                boolean exists,
                List<Point> points,
                Map<String, Parsed> parsed,
                DoubleFunction<String> display,
                JsonObject provenance) {
            this.exists = exists;
            this.points = Collections.unmodifiableList(ordered(points));
            this.parsed = Collections.unmodifiableMap(parsed);
            this.display = display;
            this.provenance = provenance;
        }
    }

    /** The lane's points with their place among the points at each beat, in lane order. */
    private static List<Point> ordered(List<Point> lane) {
        List<Point> out = new ArrayList<>(lane.size());
        for (Point point : lane) {
            Point before = out.isEmpty() ? null : out.get(out.size() - 1);
            out.add(point.at(before != null && before.beat == point.beat ? before.ordinal + 1 : 0));
        }
        return out;
    }

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

    /** Lane summary into {@code out}: whole-lane count and hash, plus the points inside range (bounded). */
    static JsonObject json(JsonObject out, Observed lane, double[] range) {
        JsonObject summary = new JsonObject();
        summary.addProperty("exists", lane.exists);
        summary.addProperty("count", lane.points.size());
        summary.addProperty("pointsHash", hash(lane.points));
        out.add("lane", summary);
        if (range == ALL) out.addProperty("range", "all");
        else if (range != null) {
            JsonObject r = new JsonObject();
            r.addProperty("start", range[0]);
            r.addProperty("end", range[1]);
            out.add("range", r);
        }
        JsonArray rows = new JsonArray();
        int inRange = 0;
        for (Point point : lane.points) {
            if (!inRange(point.beat, range)) continue;
            inRange++;
            if (rows.size() < MAX_READ) rows.add(point.json(lane.display));
        }
        out.add("points", rows);
        out.addProperty("pointsInRange", inRange);
        out.addProperty("truncated", inRange > rows.size());
        out.addProperty("selectionSideEffect", "none");
        for (Map.Entry<String, com.google.gson.JsonElement> field : lane.provenance.entrySet())
            out.add(field.getKey(), field.getValue());
        return out;
    }
}
