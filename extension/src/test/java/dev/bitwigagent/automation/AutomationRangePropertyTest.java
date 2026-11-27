package dev.bitwigagent.automation;

import static org.junit.jupiter.api.Assertions.*;

import dev.bitwigagent.core.RangeEdit;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import net.jqwik.api.*;

/** The range rule on automation points, over generated lanes, requests and ranges, executed on a fake native lane. */
final class AutomationRangePropertyTest {
    /** A native point stand-in: the lane holds these, and identity is what "the same native object" means. */
    static final class Native {
        final AutomationModule.Point point;

        Native(AutomationModule.Point point) {
            this.point = point;
        }
    }

    /** One generated case: observed lane, listed request and range (null: none; ALL: "all"). */
    static final class Case {
        final List<AutomationModule.Point> lane;
        final List<AutomationModule.Request> listed;
        final double[] range;

        Case(List<AutomationModule.Point> lane, List<AutomationModule.Request> listed, double[] range) {
            this.lane = lane;
            this.listed = listed;
            this.range = range;
        }

        @Override
        public String toString() {
            return "lane=" + lane.size() + " listed=" + listed.size() + " range=" + Arrays.toString(range);
        }

        Predicate<AutomationModule.Point> scope() {
            return range == null ? null : p -> AutomationModule.inRange(p.beat, range);
        }
    }

    @Provide
    Arbitrary<Case> cases() {
        Arbitrary<double[]> ranges = Arbitraries.oneOf(List.of(
                Arbitraries.just((double[]) null),
                Arbitraries.just(AutomationModule.ALL),
                Combinators.combine(beat(), beat()).as((x, y) -> new double[] {Math.min(x, y), Math.max(x, y)})));
        return Combinators.combine(lanes(), requests(), ranges)
                .as((lane, listed, range) -> new Case(
                        lane,
                        listed.stream()
                                .filter(r -> range == null || AutomationModule.inRange(r.beat, range))
                                .collect(Collectors.toList()),
                        range));
    }

    static Arbitrary<Double> beat() {
        return Arbitraries.integers().between(0, 64).map(k -> k / 4.0);
    }

    static Arbitrary<List<AutomationModule.Point>> lanes() {
        Arbitrary<AutomationModule.Point> point = Combinators.combine(
                        beat(),
                        Arbitraries.integers().between(0, 1000),
                        Arbitraries.integers().between(0, 1).map(i -> i == 1),
                        Arbitraries.integers().between(-1, 2).map(i -> i / 4.0))
                .as((beat, value, linear, curve) -> new AutomationModule.Point(beat, value / 1000.0, linear, curve));
        return point.list()
                .ofMaxSize(24)
                .uniqueElements(p -> p.beat)
                .map(list -> list.stream()
                        .sorted(Comparator.comparingDouble(p -> p.beat))
                        .collect(Collectors.toList()));
    }

    static Arbitrary<List<AutomationModule.Request>> requests() {
        Arbitrary<AutomationModule.Request> request = Combinators.combine(
                        beat(),
                        Arbitraries.integers().between(0, 1000),
                        Arbitraries.integers().between(0, 2).map(i -> i == 2 ? null : i == 1))
                .as((beat, value, linear) -> new AutomationModule.Request(beat, value / 1000.0, linear));
        return request.list()
                .ofMaxSize(12)
                .uniqueElements(r -> r.beat)
                .map(list -> list.stream()
                        .sorted(Comparator.comparingDouble(r -> r.beat))
                        .collect(Collectors.toList()));
    }

    static List<AutomationModule.Request> asListed(List<AutomationModule.Point> lane) {
        return lane.stream()
                .map(p -> new AutomationModule.Request(p.beat, p.value, p.linear))
                .collect(Collectors.toList());
    }

    static List<Native> natives(List<AutomationModule.Point> lane) {
        return lane.stream().map(Native::new).collect(Collectors.toList());
    }

    /** Executes an edit on a fake native lane the way the native adapter does: deletes, then inserts at indices. */
    static List<Native> execute(List<Native> lane, AutomationModule.Edit<Native> edit) {
        List<Native> out = new ArrayList<>(lane);
        for (Native gone : edit.delete) assertTrue(out.remove(gone));
        for (int i = 0; i < edit.insert.size(); i++) out.add(edit.at.get(i), new Native(edit.insert.get(i)));
        return out;
    }

    @Property
    void readingBackAndWritingTheWholeLaneIsAlreadyMatched(@ForAll("cases") Case c) {
        AutomationModule.Edit<Native> edit =
                new AutomationModule.Edit<>(natives(c.lane), c.lane, asListed(c.lane), AutomationModule.ALL);
        assertTrue(edit.change.empty() && edit.matched(c.lane));
        assertEquals(c.lane.size(), edit.change.unchanged.size());
    }

    @Property
    void replayingARequestIsAlreadyMatched(@ForAll("cases") Case c) {
        List<AutomationModule.Point> after =
                new AutomationModule.Edit<>(natives(c.lane), c.lane, c.listed, c.range).desired;
        assertTrue(new AutomationModule.Edit<>(natives(after), after, c.listed, c.range).change.empty());
    }

    @Property
    void everyChangeLiesInsideTheRangeAndWithoutOneNothingIsRemoved(@ForAll("cases") Case c) {
        RangeEdit.Change<AutomationModule.Point> change =
                new AutomationModule.Edit<>(natives(c.lane), c.lane, c.listed, c.range).change;
        if (c.range == null) assertTrue(change.remove.isEmpty());
        else {
            for (AutomationModule.Point p : change.remove) assertTrue(c.scope().test(p));
            for (RangeEdit.Update<AutomationModule.Point> u : change.update)
                assertTrue(c.scope().test(u.from));
            for (AutomationModule.Point p : change.insert) assertTrue(c.scope().test(p));
        }
    }

    @Property
    void insideAGivenRangeTheLaneBecomesExactlyTheListedPoints(@ForAll("cases") Case c) {
        Assume.that(c.range != null);
        List<AutomationModule.Point> desired =
                new AutomationModule.Edit<>(natives(c.lane), c.lane, c.listed, c.range).desired;
        List<Double> inside =
                desired.stream().filter(c.scope()).map(p -> p.beat).collect(Collectors.toList());
        assertEquals(c.listed.stream().map(r -> r.beat).collect(Collectors.toList()), inside);
        for (AutomationModule.Request r : c.listed)
            assertTrue(desired.stream().anyMatch(p -> p.beat == r.beat && p.value == r.value));
    }

    @Property
    void anUpsertNeverDeletesAPoint(@ForAll("cases") Case c) {
        Assume.that(c.range == null);
        List<AutomationModule.Point> desired =
                new AutomationModule.Edit<>(natives(c.lane), c.lane, c.listed, null).desired;
        Set<Double> beats = new HashSet<>();
        for (AutomationModule.Point p : desired) beats.add(p.beat);
        for (AutomationModule.Point p : c.lane) assertTrue(beats.contains(p.beat));
    }

    @Property
    void untouchedPointsStayTheSameNativeObjects(@ForAll("cases") Case c) {
        List<Native> before = natives(c.lane);
        AutomationModule.Edit<Native> edit = new AutomationModule.Edit<>(before, c.lane, c.listed, c.range);
        List<Native> after = execute(before, edit);
        assertEquals(edit.desired, after.stream().map(n -> n.point).collect(Collectors.toList()));
        Set<Object> listedBeats = new HashSet<>();
        for (AutomationModule.Request r : c.listed) listedBeats.add(r.beat);
        Set<Native> present = Collections.newSetFromMap(new IdentityHashMap<>());
        present.addAll(after);
        for (Native n : before) {
            boolean outside = c.range == null || !c.scope().test(n.point);
            if (outside && !listedBeats.contains(n.point.beat)) assertTrue(present.contains(n));
        }
        for (Native n : edit.kept) assertTrue(present.contains(n));
    }
}
