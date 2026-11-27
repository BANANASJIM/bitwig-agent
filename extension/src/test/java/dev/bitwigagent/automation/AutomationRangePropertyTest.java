package dev.bitwigagent.automation;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.bitwigagent.automation.AutomationFake.Native;
import dev.bitwigagent.automation.AutomationLane.Observed;
import dev.bitwigagent.automation.AutomationLane.Point;
import dev.bitwigagent.core.DisplayScale;
import dev.bitwigagent.core.RangeEdit;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.core.RpcException;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import net.jqwik.api.*;

/** The range rule, curve and display values on automation points, over generated lanes and requests, on a fake lane. */
final class AutomationRangePropertyTest {
    /** One generated case: observed lane, listed request and range (null: none; ALL: "all"). */
    static final class Case {
        final List<Point> lane;
        final List<AutomationPlan.Request> listed;
        final double[] range;

        Case(List<Point> lane, List<AutomationPlan.Request> listed, double[] range) {
            this.lane = lane;
            this.listed = listed;
            this.range = range;
        }

        @Override
        public String toString() {
            return "lane=" + lane + " listed=" + listed.size() + " range=" + Arrays.toString(range);
        }

        Predicate<Point> scope() {
            return range == null ? null : p -> AutomationLane.inRange(p.beat, range);
        }

        List<String> displays() {
            return listed.stream()
                    .filter(r -> r.display != null)
                    .map(r -> r.display)
                    .collect(Collectors.toList());
        }
    }

    @Provide
    Arbitrary<Case> cases() {
        Arbitrary<double[]> ranges = Arbitraries.oneOf(List.of(
                Arbitraries.just((double[]) null),
                Arbitraries.just(AutomationLane.ALL),
                Combinators.combine(beat(), beat()).as((x, y) -> new double[] {Math.min(x, y), Math.max(x, y)})));
        return Combinators.combine(lanes(), requests(), ranges)
                .as((lane, listed, range) -> new Case(
                        lane,
                        listed.stream()
                                .filter(r -> AutomationLane.inRange(r.beat, range))
                                .collect(Collectors.toList()),
                        range));
    }

    static Arbitrary<Double> beat() {
        return Arbitraries.integers().between(0, 64).map(k -> k / 4.0);
    }

    static Arbitrary<Double> curve() {
        return Arbitraries.integers().between(-4, 4).map(i -> i / 4.0);
    }

    static Arbitrary<List<Point>> lanes() {
        Arbitrary<Point> point = Combinators.combine(
                        beat(),
                        Arbitraries.integers().between(0, 1000),
                        Arbitraries.integers().between(0, 1).map(i -> i == 1),
                        curve())
                .as((beat, value, linear, curve) -> new Point(beat, value / 1000.0, linear, curve));
        return point.list()
                .ofMaxSize(24)
                .uniqueElements(p -> p.beat)
                .map(list -> list.stream()
                        .sorted(Comparator.comparingDouble(p -> p.beat))
                        .collect(Collectors.toList()));
    }

    /** Requests: a bare value or a display in tenths of a dB, interpolation and curve each present or omitted. */
    static Arbitrary<List<AutomationPlan.Request>> requests() {
        Arbitrary<AutomationPlan.Request> request = Combinators.combine(
                        beat(),
                        Arbitraries.integers().between(0, 1000),
                        Arbitraries.integers().between(0, 1).map(i -> i == 1),
                        Arbitraries.integers().between(0, 2).map(i -> i == 2 ? null : i == 1),
                        Arbitraries.oneOf(List.of(Arbitraries.just((Double) null), curve())))
                .as((beat, value, display, linear, curve) -> display
                        ? new AutomationPlan.Request(beat, null, decibels(value), linear, curve)
                        : new AutomationPlan.Request(beat, value / 1000.0, null, linear, curve));
        return request.list()
                .ofMaxSize(12)
                .uniqueElements(r -> r.beat)
                .map(list -> list.stream()
                        .sorted(Comparator.comparingDouble(r -> r.beat))
                        .collect(Collectors.toList()));
    }

    /** A display on the fake's -60..+6 dB scale, in steps of its 0.1 dB resolution. */
    static String decibels(int thousandths) {
        return AutomationFake.display(Math.round(thousandths * 0.66) / 660.0);
    }

    static List<AutomationPlan.Request> asListed(List<Point> lane) {
        return lane.stream()
                .map(p -> new AutomationPlan.Request(p.beat, p.value, null, p.linear, p.curve))
                .collect(Collectors.toList());
    }

    static Observed observed(List<Native> lane, List<String> displays) {
        return AutomationFake.observed(true, lane, displays);
    }

    static AutomationPlan.Write plan(List<Native> lane, Case c) {
        return new AutomationPlan(null, c.listed, c.range).apply(observed(lane, c.displays()));
    }

    /** The lane after the case's write, executed on the same native objects it was planned on. */
    static List<Native> written(Case c) {
        List<Native> before = AutomationFake.natives(c.lane);
        return AutomationFake.execute(before, plan(before, c));
    }

    static List<Point> points(List<Native> lane) {
        return lane.stream().map(n -> n.point).collect(Collectors.toList());
    }

    @Property
    void readingBackAndWritingTheWholeLaneIsAlreadyMatched(@ForAll("cases") Case c) {
        AutomationPlan.Write write = new AutomationPlan(null, asListed(c.lane), AutomationLane.ALL)
                .apply(observed(AutomationFake.natives(c.lane), List.of()));
        assertTrue(write.change.isEmpty() && write.empty);
        assertEquals(c.lane.size(), write.change.unchanged);
    }

    @Property
    void replayingARequestIsAlreadyMatched(@ForAll("cases") Case c) {
        List<Native> after = written(c);
        assertTrue(plan(after, c).change.isEmpty());
    }

    @Property
    void writtenCurvesAndDisplaysReadBackAsRequested(@ForAll("cases") Case c) {
        List<Native> after = written(c);
        Map<Double, Point> byBeat = new HashMap<>();
        for (Point p : points(after)) byBeat.put(p.beat, p);
        Map<Double, Point> before = new HashMap<>();
        for (Point p : c.lane) before.put(p.beat, p);
        for (AutomationPlan.Request r : c.listed) {
            Point written = byBeat.get(r.beat), was = before.get(r.beat);
            double curve = r.curve != null ? r.curve : was == null ? 0 : was.curve;
            assertEquals(curve, written.curve, "a given curve is written, an omitted one kept or 0");
            if (r.display != null)
                assertTrue(DisplayScale.reads(r.display, AutomationFake.display(written.value)), "display round trip");
            else assertEquals(new Point(0, r.value, true, 0).value, written.value);
        }
    }

    @Property
    void everyChangeLiesInsideTheRangeAndWithoutOneNothingIsRemoved(@ForAll("cases") Case c) {
        RangeEdit.Change<Point> change = plan(AutomationFake.natives(c.lane), c).change;
        if (c.range == null) assertTrue(change.remove.isEmpty());
        else {
            for (Point p : change.remove) assertTrue(c.scope().test(p));
            for (RangeEdit.Update<Point> u : change.update) assertTrue(c.scope().test(u.before));
            for (Point p : change.insert) assertTrue(c.scope().test(p));
        }
    }

    @Property
    void insideAGivenRangeTheLaneBecomesExactlyTheListedPoints(@ForAll("cases") Case c) {
        Assume.that(c.range != null);
        List<Point> desired = plan(AutomationFake.natives(c.lane), c).desired;
        List<Double> inside =
                desired.stream().filter(c.scope()).map(p -> p.beat).collect(Collectors.toList());
        assertEquals(c.listed.stream().map(r -> r.beat).collect(Collectors.toList()), inside);
    }

    @Property
    void anUpsertNeverDeletesAPoint(@ForAll("cases") Case c) {
        Assume.that(c.range == null);
        Set<Double> beats = new HashSet<>();
        for (Point p : plan(AutomationFake.natives(c.lane), c).desired) beats.add(p.beat);
        for (Point p : c.lane) assertTrue(beats.contains(p.beat));
    }

    @Property
    void untouchedPointsStayTheSameNativeObjects(@ForAll("cases") Case c) {
        List<Native> before = AutomationFake.natives(c.lane);
        AutomationPlan.Write write = plan(before, c);
        List<Native> after = AutomationFake.execute(before, write);
        assertEquals(write.desired, points(after));
        AutomationPlan.verify(write, observed(after, List.of()));
        Set<Object> listedBeats = new HashSet<>();
        for (AutomationPlan.Request r : c.listed) listedBeats.add(r.beat);
        Set<Native> present = Collections.newSetFromMap(new IdentityHashMap<>());
        present.addAll(after);
        for (Native n : before) {
            boolean outside = c.range == null || !c.scope().test(n.point);
            if (outside && !listedBeats.contains(n.point.beat)) assertTrue(present.contains(n));
        }
    }

    @Property
    void aDisplayResolvesToAValueWhoseDisplayReadsTheSame(@ForAll("decibelTexts") String text) {
        AutomationPlan.Request first = new AutomationPlan.Request(0, null, text, null, null);
        double value;
        try {
            value = AutomationPlan.value(observed(List.of(), List.of(text)), first);
        } catch (RpcException refused) {
            assertTrue(
                    !refused.unknown
                            && (refused.reason == Reason.UNSUPPORTED_UNIT || refused.reason == Reason.OUT_OF_BOUNDS),
                    refused.getMessage());
            return;
        }
        String shown = AutomationFake.display(value);
        assertTrue(DisplayScale.reads(text, shown), text + " -> " + shown);
        double settled = resolve(shown);
        String again = AutomationFake.display(settled);
        assertTrue(DisplayScale.reads(shown, again), shown + " -> " + again);
        assertEquals(settled, resolve(again), 1e-12, "the value a read-back display resolves to is a fixed point");
    }

    static double resolve(String text) {
        return AutomationPlan.value(
                observed(List.of(), List.of(text)), new AutomationPlan.Request(0, null, text, null, null));
    }

    /** Display texts as an agent may write them: in range or not, any precision, with or without the unit's case. */
    @Provide
    Arbitrary<String> decibelTexts() {
        return Combinators.combine(
                        Arbitraries.doubles().between(-80, 20).ofScale(3), Arbitraries.of("dB", "db", " dB", "Hz", ""))
                .as((value, unit) -> value + (unit.isEmpty() ? "" : " " + unit.trim()));
    }

    @Property
    void anyRawRequestIsRefusedAsInvalidOrPlannedAndReplayable(
            @ForAll("rawLanes") List<Point> lane,
            @ForAll("rawBeats") List<Double> beats,
            @ForAll("rawBeats") List<Double> bounds,
            @ForAll double value,
            @ForAll boolean ranged) {
        JsonObject p = new JsonObject();
        JsonArray points = new JsonArray();
        double start = bounds.isEmpty() ? 0 : bounds.get(0),
                end = bounds.isEmpty() ? 64 : bounds.get(bounds.size() - 1);
        for (double beat : beats) {
            if (ranged && (beat < start || beat > end)) continue;
            JsonObject point = new JsonObject();
            point.addProperty("beat", beat);
            point.addProperty("value", Math.abs(value % 1));
            points.add(point);
        }
        p.add("points", points);
        if (ranged) {
            JsonObject range = new JsonObject();
            range.addProperty("start", start);
            range.addProperty("end", end);
            p.add("range", range);
        }
        List<AutomationPlan.Request> listed;
        try {
            listed = AutomationModule.points(p);
        } catch (RpcException refused) {
            assertTrue(refused.reason == Reason.INVALID_PARAMS && !refused.unknown);
            return;
        }
        double[] range = AutomationModule.range(p);
        AutomationPlan plan = new AutomationPlan(null, listed, range);
        List<Native> before = AutomationFake.natives(lane);
        List<Native> after = AutomationFake.execute(before, plan.apply(observed(before, List.of())));
        assertTrue(plan.apply(observed(after, List.of())).change.isEmpty());
    }

    /** Arbitrary doubles, and beats a hair off the quarter grid so that distinct raw beats share a grid point. */
    @Provide
    Arbitrary<List<Double>> rawBeats() {
        Arbitrary<Double> nearGrid = Combinators.combine(
                        Arbitraries.integers().between(0, 16),
                        Arbitraries.doubles().between(-1e-6, 1e-6).ofScale(12))
                .as((k, jitter) -> Math.max(0, k / 4.0 + jitter));
        return Arbitraries.oneOf(List.of(Arbitraries.doubles().between(0, 64).ofScale(12), nearGrid))
                .list()
                .ofMaxSize(12)
                .uniqueElements()
                .map(list -> list.stream().sorted().collect(Collectors.toList()));
    }

    @Provide
    Arbitrary<List<Point>> rawLanes() {
        return Combinators.combine(
                        rawBeats(), Arbitraries.doubles().between(0, 1).ofScale(12))
                .as((beats, value) -> beats.stream()
                        .map(beat -> new Point(beat, value, true, 0))
                        .distinct()
                        .collect(Collectors.toList()));
    }
}
