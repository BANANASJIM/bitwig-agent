package dev.bitwigagent.automation;

import com.google.gson.JsonObject;
import dev.bitwigagent.automation.AutomationLane.Observed;
import dev.bitwigagent.automation.AutomationLane.Parsed;
import dev.bitwigagent.automation.AutomationLane.Point;
import dev.bitwigagent.core.DisplayScale;
import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.RangeEdit;
import dev.bitwigagent.core.Reason;
import java.math.BigDecimal;
import java.util.*;
import java.util.function.Function;

/**
 * The domain side of one automation point write, as a pure function of the observed lane: the expected hash, display
 * values, the range rule and what the adapter must delete and insert. The adapter runs it inside its transaction and
 * hands back the lane it read before and after, which {@link #verify} checks.
 */
public final class AutomationPlan implements Function<Observed, AutomationPlan.Write> {
    /** A requested point; {@code value} is null when {@code display} gives it, {@code linear} and {@code curve} when omitted. */
    static final class Request {
        final double beat;
        final Double value, curve;
        final String display;
        final Boolean linear;
        /** The request's place among the listed points at its beat. */
        final int ordinal;

        Request(double beat, Double value, String display, Boolean linear, Double curve) {
            this(beat, value, display, linear, curve, 0);
        }

        Request(double beat, Double value, String display, Boolean linear, Double curve, int ordinal) {
            this.ordinal = ordinal;
            this.beat = Point.beat(beat);
            this.value = value;
            this.display = display;
            this.linear = linear;
            this.curve = curve;
        }
    }

    /**
     * The range rule on one lane as native steps: the handles to delete, then each new point with its index in the
     * final lane, ascending. An empty write changes nothing.
     */
    public static final class Write {
        final RangeEdit.Change<Point> change;
        final List<Point> desired;
        public final List<Point> insert = new ArrayList<>();
        public final List<Integer> at = new ArrayList<>();
        public final List<Object> delete = new ArrayList<>();
        final List<Object> kept = new ArrayList<>();
        public final boolean empty;

        Write(List<Point> current, List<Point> listed, double[] range) {
            change = RangeEdit.plan(
                    current,
                    listed,
                    range == null ? null : p -> AutomationLane.inRange(p.beat, range),
                    AutomationLane.KIND);
            desired = RangeEdit.apply(current, change);
            // RangeEdit.apply appends the written points after the kept ones: order the lane by beat, then place.
            desired.sort(
                    Comparator.<Point>comparingDouble(point -> point.beat).thenComparingInt(point -> point.ordinal));
            for (Point point : change.remove) delete.add(point.handle);
            for (RangeEdit.Update<Point> update : change.update) delete.add(update.before.handle);
            for (int i = 0; i < desired.size(); i++) {
                Point point = desired.get(i);
                if (point.handle != null) kept.add(point.handle);
                else {
                    insert.add(point);
                    at.add(i);
                }
            }
            empty = change.isEmpty() || withoutBasePoint(current, desired).equals(desired);
        }

        JsonObject summary(boolean applied) {
            JsonObject out = new JsonObject();
            out.addProperty("inserted", applied ? change.insert.size() : 0);
            out.addProperty("updated", applied ? change.update.size() : 0);
            out.addProperty("removed", applied ? change.remove.size() : 0);
            out.addProperty("unchanged", change.unchanged);
            return out;
        }
    }

    private final String expectedHash;
    private final List<Request> requests;
    private final double[] range;

    /** {@code expectedHash} is null without expect; {@code range} null without one, else as the module parses it. */
    AutomationPlan(String expectedHash, List<Request> requests, double[] range) {
        this.expectedHash = expectedHash;
        this.requests = requests;
        this.range = range;
    }

    /** The display texts the adapter must resolve on the lane's parameter before planning. */
    List<String> displays() {
        List<String> out = new ArrayList<>();
        for (Request request : requests) if (request.display != null) out.add(request.display);
        return out;
    }

    @Override
    public Write apply(Observed lane) {
        requireExpected(lane.points, expectedHash);
        Map<Object, Point> byKey = new HashMap<>();
        Map<Double, Integer> onLane = new HashMap<>(), asked = new HashMap<>();
        for (Point point : lane.points) {
            byKey.put(AutomationLane.KIND.key(point), point);
            onLane.merge(point.beat, 1, Integer::sum);
        }
        for (Request request : requests) asked.merge(request.beat, 1, Integer::sum);
        if (range == null) requireEveryPointAtEachBeat(onLane, asked);
        List<Point> listed = new ArrayList<>();
        for (Request request : requests)
            listed.add(written(byKey.get(List.of(request.beat, request.ordinal)), request, value(lane, request)));
        return new Write(lane.points, listed, range);
    }

    /**
     * Without a range nothing is deleted, so the listed points at a beat must cover the lane's: they update its points
     * in order and the rest are inserted after them. Fewer would leave no way to tell which point is meant.
     */
    private static void requireEveryPointAtEachBeat(Map<Double, Integer> onLane, Map<Double, Integer> asked) {
        for (Map.Entry<Double, Integer> beat : asked.entrySet()) {
            int held = onLane.getOrDefault(beat.getKey(), 0);
            if (beat.getValue() < held)
                throw Errors.notApplied(
                        Reason.INVALID_TARGET,
                        "beat "
                                + new BigDecimal(beat.getKey())
                                        .stripTrailingZeros()
                                        .toPlainString() + " holds " + held
                                + " points (a step); list all of them in order, or give a range");
        }
    }

    /**
     * The point a request writes: it keeps what it omits (interpolation, curve) from the point in its place (beat and
     * place at that beat); a new point is linear with curve 0.
     */
    static Point written(Point observed, Request listed, double value) {
        boolean linear = listed.linear != null ? listed.linear : observed == null || observed.linear;
        double curve = listed.curve != null ? listed.curve : observed == null ? 0 : observed.curve;
        return new Point(listed.beat, value, linear, curve, null, listed.ordinal);
    }

    /**
     * A request's normalized value. A display is what the host's own parser reads on this parameter: text it cannot
     * read, or a number in another unit than the parameter shows, is unsupported_unit; one past either end of the range
     * is out_of_bounds. The parser ignores a unit it does not expect and reads text without a number as 0 (measured on
     * 6.1.4 and 6.1.7), so the host's display of the result must read as the requested text.
     */
    static double value(Observed lane, Request request) {
        if (request.display == null) return request.value;
        Parsed parsed = lane.parsed.get(request.display);
        // The parser ignores a foreign unit, so such a number lands anywhere, often past the range: unit first.
        if (!parsed.parsed || DisplayScale.otherUnit(request.display, parsed.shown)) throw unsupported(lane, request);
        if (outside(parsed.plain, parsed.restored))
            throw Errors.notApplied(
                    Reason.OUT_OF_BOUNDS,
                    "{display: \"" + request.display + "\"} lies outside the parameter's range " + span(lane) + ".");
        if (!DisplayScale.reads(request.display, parsed.shown)) throw unsupported(lane, request);
        return parsed.normalized;
    }

    private static RuntimeException unsupported(Observed lane, Request request) {
        return Errors.notApplied(
                Reason.UNSUPPORTED_UNIT,
                "The parameter does not read {display: \"" + request.display + "\"}; it displays " + span(lane)
                        + ". Write a display in that form, or the normalized value.");
    }

    private static String span(Observed lane) {
        return "\"" + lane.display.apply(0) + "\" to \"" + lane.display.apply(1) + "\"";
    }

    /** Relative difference at which a parsed value no longer survives the value range's clamp. */
    static final double PLAIN_TOLERANCE = 1e-9;

    /**
     * Whether the value range clamped a parsed value: it did not come back. An infinite value ("-inf dB") survives
     * only on a range whose end is that same infinity.
     */
    static boolean outside(double plain, double restored) {
        if (plain == restored) return false;
        if (Double.isInfinite(plain) || Double.isInfinite(restored)) return true;
        return Math.abs(restored - plain) > PLAIN_TOLERANCE * Math.max(1, Math.abs(plain));
    }

    static void requireExpected(List<Point> current, String expectedHash) {
        if (expectedHash == null || AutomationLane.hash(current).equals(expectedHash)) return;
        JsonObject observed = new JsonObject();
        observed.addProperty("pointsHash", AutomationLane.hash(current));
        throw Errors.expectMismatch("The lane's pointsHash differs from expect; nothing was written.", observed);
    }

    /**
     * Bitwig keeps a base point at beat 0 when the last points of a lane are deleted: a lane that should be empty and
     * holds exactly one point at beat 0 counts as cleared. Nothing else is excused.
     */
    static List<Point> withoutBasePoint(List<Point> lane, List<Point> wanted) {
        boolean base = wanted.isEmpty() && lane.size() == 1 && lane.get(0).beat == 0;
        return base ? List.of() : lane;
    }

    /**
     * The lane after a write against what the write planned: every kept point is the same handle, no deleted one is
     * left (but Bitwig's base point), and the points equal the desired ones exactly.
     */
    static void verify(Write write, Observed after) {
        Set<Object> present = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Point point : after.points) present.add(point.handle);
        for (Object handle : write.kept)
            if (!present.contains(handle))
                throw mismatch("A point the write did not touch disappeared; inspect automation.points.get.");
        List<Point> settled = withoutBasePoint(after.points, write.desired);
        Object base = settled == after.points ? null : after.points.get(0).handle;
        for (Object handle : write.delete)
            if (present.contains(handle) && handle != base)
                throw mismatch("A replaced point is still on the lane; inspect automation.points.get.");
        if (settled.size() != write.desired.size())
            throw mismatch("Native lane holds " + settled.size() + " points after the write, expected "
                    + write.desired.size() + "; inspect automation.points.get before another write.");
        for (int i = 0; i < settled.size(); i++)
            if (!write.desired.get(i).equals(settled.get(i)))
                throw mismatch("Native point " + i + " reads back as " + settled.get(i) + ", expected "
                        + write.desired.get(i) + "; inspect automation.points.get before another write.");
    }

    private static RuntimeException mismatch(String message) {
        return Errors.unknown(Reason.READBACK_MISMATCH, message);
    }
}
