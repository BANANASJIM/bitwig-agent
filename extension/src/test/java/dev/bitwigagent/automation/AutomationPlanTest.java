package dev.bitwigagent.automation;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import dev.bitwigagent.automation.AutomationFake.Native;
import dev.bitwigagent.automation.AutomationLane.Observed;
import dev.bitwigagent.automation.AutomationLane.Point;
import dev.bitwigagent.core.RpcException;
import java.util.*;
import org.junit.jupiter.api.Test;

/** The planner on observed lanes: expect, display values, curve, the range rule as native steps, and readback. */
final class AutomationPlanTest {
    static final String EMPTY = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    static Point p(double beat, double value, boolean linear) {
        return new Point(beat, value, linear, 0);
    }

    static AutomationPlan.Request r(double beat, double value, Boolean linear) {
        return new AutomationPlan.Request(beat, value, null, linear, null);
    }

    static AutomationPlan.Request shown(double beat, String display) {
        return new AutomationPlan.Request(beat, null, display, null, null);
    }

    static Observed lane(List<Point> points, String... displays) {
        return AutomationFake.observed(true, AutomationFake.natives(points), List.of(displays));
    }

    static AutomationPlan.Write plan(List<Point> points, double[] range, AutomationPlan.Request... requests) {
        List<String> displays = new ArrayList<>();
        for (AutomationPlan.Request request : requests) if (request.display != null) displays.add(request.display);
        return new AutomationPlan(null, List.of(requests), range).apply(lane(points, displays.toArray(new String[0])));
    }

    static JsonObject summary(int inserted, int updated, int removed, int unchanged) {
        JsonObject out = new JsonObject();
        out.addProperty("inserted", inserted);
        out.addProperty("updated", updated);
        out.addProperty("removed", removed);
        out.addProperty("unchanged", unchanged);
        return out;
    }

    static List<Object> handles(Observed observed, int... indices) {
        List<Object> out = new ArrayList<>();
        for (int i : indices) out.add(observed.points.get(i).handle);
        return out;
    }

    @Test
    void theRangeRuleAsNativeSteps() {
        Point a = p(0, 0.2, true), b = p(8, 0.4, false), c = p(16, 0.6, true);
        List<Point> points = List.of(a, b, c);
        Observed observed = lane(points);
        AutomationPlan.Write ranged =
                new AutomationPlan(null, List.of(r(9, 1, false)), new double[] {8, 12}).apply(observed);
        assertEquals(List.of(a, p(9, 1, false), c), ranged.desired, "inclusive range replace keeps points outside it");
        assertEquals(handles(observed, 1), ranged.delete);
        assertEquals(handles(observed, 0, 2), ranged.kept);
        assertEquals(List.of(1), ranged.at);
        AutomationPlan.Write upsert =
                new AutomationPlan(null, List.of(r(4, 1, null), r(16, 0.9, null)), null).apply(observed);
        assertEquals(4, upsert.desired.size(), "without range nothing is removed");
        assertEquals(handles(observed, 2), upsert.delete, "an updated point is replaced natively");
        assertEquals(List.of(1, 3), upsert.at, "new points land at their lane index");
        assertTrue(
                upsert.desired.get(1).linear && upsert.desired.get(3).linear,
                "omitted interpolation is linear for a new point and kept for an updated one");
        assertEquals(summary(1, 1, 0, 0), upsert.summary(true), "summary counts inserted, updated, removed, unchanged");
        assertEquals(summary(0, 0, 0, 0), upsert.summary(false), "nothing written counts nothing changed");
        assertFalse(
                plan(points, null, r(8, 0.5, null)).desired.get(1).linear,
                "an update without interpolation keeps hold");
        AutomationPlan.Write cleared = plan(points, AutomationLane.ALL);
        assertTrue(cleared.desired.isEmpty() && cleared.delete.size() == 3, "range all with no points clears the lane");
        AutomationPlan.Write equal = plan(points, new double[] {8, 8}, r(8, 0.4 + 1e-12, false));
        assertTrue(
                equal.empty && equal.summary(false).equals(summary(0, 0, 0, 1)), "an equal point is already matched");
        AutomationPlan.Write base = plan(List.of(p(0, 0.5, true)), AutomationLane.ALL);
        assertTrue(base.empty, "a lane holding only Bitwig's base point is already cleared");
    }

    @Test
    void curveIsWrittenWhenGivenAndKeptWhenOmitted() {
        Point curved = new Point(8, 0.4, false, 0.6);
        assertTrue(plan(List.of(curved), null, r(8, 0.4, false)).empty, "a listed point keeps the curve it omits");
        AutomationPlan.Write bent = new AutomationPlan(
                        null, List.of(new AutomationPlan.Request(8, 0.4, null, null, -0.25)), null)
                .apply(lane(List.of(curved)));
        assertEquals(List.of(new Point(8, 0.4, false, -0.25)), bent.desired, "a given curve replaces the point's");
        AutomationPlan.Write fresh = new AutomationPlan(
                        null, List.of(new AutomationPlan.Request(2, 0.1, null, null, 1.0)), null)
                .apply(lane(List.of()));
        assertEquals(List.of(new Point(2, 0.1, true, 1)), fresh.desired, "a new point takes the given curve");
        assertEquals(0, plan(List.of(), null, r(2, 0.1, null)).desired.get(0).curve, "a new point is straight");
    }

    @Test
    void displaysAreWhatTheParametersOwnTextReads() {
        AutomationPlan.Write minus6 = plan(List.of(), null, shown(0, "-6 dB"), shown(4, "-6.0 dB"));
        double n = (-6 - AutomationFake.MIN) / (AutomationFake.MAX - AutomationFake.MIN);
        assertEquals(List.of(new Point(0, n, true, 0), new Point(4, n, true, 0)), minus6.desired);
        assertEquals(
                List.of("-6 dB", "-6.0 dB"),
                new AutomationPlan(null, List.of(shown(0, "-6 dB"), r(1, 0.5, null), shown(4, "-6.0 dB")), null)
                        .displays(),
                "the adapter resolves exactly the requested displays");
        assertTrue(
                plan(List.of(new Point(0, n, true, 0)), null, shown(0, "-6 dB")).empty,
                "replaying a display write is already matched");
        String[] refusedAsUnit = {"800 Hz", "Off", "loud"};
        for (String text : refusedAsUnit) {
            RpcException e = assertThrows(RpcException.class, () -> plan(List.of(), null, shown(0, text)), text);
            assertEquals("unsupported_unit", e.reason.wire(), text);
            assertTrue(!e.unknown && e.getMessage().contains("\"-60.0 dB\" to \"6.0 dB\""), e.getMessage());
        }
        RpcException beyond = assertThrows(RpcException.class, () -> plan(List.of(), null, shown(0, "+12 dB")));
        assertEquals("out_of_bounds", beyond.reason.wire());
        assertTrue(beyond.getMessage().contains("outside the parameter's range"), beyond.getMessage());
        RpcException garbled = assertThrows(RpcException.class, () -> plan(List.of(), null, shown(0, "1 2 dB")));
        assertEquals("unsupported_unit", garbled.reason.wire(), "text the host cannot parse");
    }

    /** One display request on a lane whose parameter resolves {@code text} as given. */
    static double resolved(String text, AutomationLane.Parsed parsed, String bottom, String top) {
        Observed lane =
                new Observed(false, List.of(), Map.of(text, parsed), n -> n == 0 ? bottom : top, new JsonObject());
        return AutomationPlan.value(lane, shown(0, text));
    }

    @Test
    void displaysInPercentRatioAndMinusInfinity() {
        assertEquals(0.5, resolved("50 %", new AutomationLane.Parsed(50, 0.5, 50, "50.0 %"), "0.00 %", "100 %"));
        assertEquals(0.5, resolved("50", new AutomationLane.Parsed(50, 0.5, 50, "50.0 %"), "0.00 %", "100 %"), "bare");
        assertEquals(0.25, resolved("4:1", new AutomationLane.Parsed(4, 0.25, 4, "1:4.00"), "1:1.00", "1:16.0"));
        double inf = Double.NEGATIVE_INFINITY;
        assertEquals(
                0,
                resolved("-inf dB", new AutomationLane.Parsed(inf, 0, inf, "-inf dB"), "-inf dB", "+6.0 dB"),
                "minus infinity on a range whose floor is minus infinity");
        RpcException floor = assertThrows(
                RpcException.class,
                () -> resolved("-inf dB", new AutomationLane.Parsed(inf, 0, -60, "-60.0 dB"), "-60.0 dB", "+6.0 dB"));
        assertEquals("out_of_bounds", floor.reason.wire(), "minus infinity past a finite floor");
        assertTrue(AutomationPlan.outside(inf, -60)
                && AutomationPlan.outside(-60, inf)
                && !AutomationPlan.outside(inf, inf));
        assertEquals(
                (-6 - AutomationFake.MIN) / (AutomationFake.MAX - AutomationFake.MIN),
                plan(List.of(), null, shown(0, "-6")).desired.get(0).value,
                1e-9,
                "a bare number is read in the unit the parameter shows");
    }

    @Test
    void expectComparesTheWholeLaneHash() {
        List<Point> points = List.of(p(0, 0.2, true));
        new AutomationPlan(AutomationLane.hash(points), List.of(), null).apply(lane(points));
        RpcException e =
                assertThrows(RpcException.class, () -> new AutomationPlan(EMPTY, List.of(), null).apply(lane(points)));
        assertTrue(
                e.reason.wire().equals("target_changed") && !e.unknown, "a hash mismatch is refused before dispatch");
        assertEquals(AutomationLane.hash(points), e.observed.get("pointsHash").getAsString());
    }

    @Test
    void verifyComparesTheLaneAfterTheWriteWithThePlan() {
        Point a = p(0, 0.2, true), b = p(8, 0.4, false);
        List<Native> before = AutomationFake.natives(List.of(a, b));
        Observed observed = AutomationFake.observed(true, before, List.of());
        AutomationPlan.Write write = new AutomationPlan(null, List.of(r(8, 0.5, null)), null).apply(observed);
        List<Native> after = AutomationFake.execute(before, write);
        AutomationPlan.verify(write, AutomationFake.observed(true, after, List.of()));
        List<Native> lost = new ArrayList<>(after);
        lost.remove(0);
        lost.add(0, new Native(a));
        assertUnknown("disappeared", write, lost);
        List<Native> stale = new ArrayList<>(after);
        stale.add(before.get(1));
        assertUnknown("still on the lane", write, stale);
        List<Native> extra = new ArrayList<>(after);
        extra.add(new Native(p(9, 0.1, true)));
        assertUnknown("holds 3 points", write, extra);
        List<Native> wrong = new ArrayList<>(after);
        wrong.set(1, new Native(p(8, 0.51, true)));
        assertUnknown("reads back as", write, wrong);
    }

    @Test
    void clearingALaneLeavesOnlyBitwigsBasePointAndThatCountsAsCleared() {
        Point base = p(0, 0.5, true), other = p(8, 0.4, false);
        List<Point> none = List.of();
        assertEquals(none, AutomationPlan.withoutBasePoint(List.of(base), none));
        assertEquals(
                List.of(other), AutomationPlan.withoutBasePoint(List.of(other), none), "only beat 0 is a base point");
        assertEquals(
                List.of(base, other), AutomationPlan.withoutBasePoint(List.of(base, other), none), "only a lone point");
        assertEquals(
                List.of(base),
                AutomationPlan.withoutBasePoint(List.of(base), List.of(base)),
                "never when points are wanted");
        List<Native> lane = AutomationFake.natives(List.of(base, other));
        AutomationPlan.Write clear = new AutomationPlan(null, List.of(), AutomationLane.ALL)
                .apply(AutomationFake.observed(true, lane, List.of()));
        AutomationPlan.verify(clear, AutomationFake.observed(true, List.of(new Native(base)), List.of()));
        AutomationPlan.verify(clear, AutomationFake.observed(true, lane.subList(0, 1), List.of()));
        assertUnknown("holds 1 points", clear, List.of(new Native(other)));
    }

    @Test
    void pointsAreCanonical() {
        assertEquals(p(1.0 / 3, 0.1, true), p(1.0 / 3 + 1e-9, 0.1 + 1e-12, true));
        assertNotEquals(p(0, 0.1, true), p(0, 0.1, false));
        assertNotEquals(p(0, 0.1, true), "point");
        assertEquals(p(0, 0.1, true).hashCode(), new Point(0, 0.1, true, 0, new Object()).hashCode());
        assertEquals(EMPTY, AutomationLane.hash(List.of()), "empty lane hash is SHA-256 of nothing");
        assertNotEquals(AutomationLane.hash(List.of(p(0, 0.1, true))), AutomationLane.hash(List.of(p(0, 0.1, false))));
        assertNotEquals(
                AutomationLane.hash(List.of(p(0, 0.1, true))),
                AutomationLane.hash(List.of(new Point(0, 0.1, true, 0.1))),
                "curve changes the hash");
    }

    static void assertUnknown(String message, AutomationPlan.Write write, List<Native> after) {
        RpcException e = assertThrows(
                RpcException.class,
                () -> AutomationPlan.verify(write, AutomationFake.observed(true, after, List.of())));
        assertTrue(e.reason.wire().equals("readback_mismatch") && e.unknown, "a readback mismatch is unknown");
        assertTrue(e.getMessage().contains(message), e.getMessage());
    }
}
