package dev.bitwigagent.automation;

import com.google.gson.JsonObject;
import dev.bitwigagent.automation.AutomationLane.Observed;
import dev.bitwigagent.automation.AutomationLane.Parsed;
import dev.bitwigagent.automation.AutomationLane.Point;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An in-memory automation lane behind the port: it executes the domain's plan the way the native adapter does (deletes
 * by handle, then inserts at indices) and resolves displays on a -60..+6 dB scale with the host parser's measured
 * leniency (a foreign unit is ignored, text without a number reads as 0).
 */
final class AutomationFake implements AutomationModule.Access {
    /** A native point stand-in: identity is what "the same native object" means. */
    static final class Native {
        final Point point;

        Native(Point point) {
            this.point = point;
        }
    }

    static final double MIN = -60, MAX = 6;
    private static final Pattern NUMBER = Pattern.compile("^\\s*([+-]?\\d+(?:\\.\\d+)?)\\s*[a-zA-Z%]*\\s*$");

    List<Native> lane = new ArrayList<>();
    boolean exists;
    private final Consumer<Runnable> executor;
    int reads, edits, writes;
    AutomationModule.Target lastTarget;
    List<String> lastDisplays;
    /** Runs inside the write, after beforeWrite: a fault there is an unknown outcome. */
    Consumer<List<Native>> afterWrite = lane -> {};

    AutomationFake(Consumer<Runnable> executor) {
        this.executor = executor;
    }

    static List<Native> natives(List<Point> points) {
        List<Native> out = new ArrayList<>();
        for (Point point : points) out.add(new Native(point));
        return out;
    }

    static String display(double normalized) {
        return String.format(Locale.ROOT, "%.1f dB", MIN + (MAX - MIN) * normalized);
    }

    static Parsed parse(String text) {
        Matcher m = NUMBER.matcher(text);
        double plain;
        if (m.matches()) plain = Double.parseDouble(m.group(1));
        else if (text.chars().noneMatch(Character::isDigit)) plain = 0;
        else return Parsed.UNPARSEABLE;
        double n = Math.max(0, Math.min(1, (plain - MIN) / (MAX - MIN)));
        return new Parsed(plain, n, MIN + (MAX - MIN) * n, display(n));
    }

    static Observed observed(boolean exists, List<Native> lane, List<String> displays) {
        List<Point> points = new ArrayList<>();
        for (Native n : lane) {
            Point p = n.point;
            points.add(new Point(p.beat, p.value, p.linear, p.curve, n));
        }
        Map<String, Parsed> parsed = new HashMap<>();
        for (String text : displays) parsed.put(text, parse(text));
        return new Observed(exists, points, parsed, AutomationFake::display, new JsonObject());
    }

    @Override
    public void execute(Runnable work) {
        executor.accept(work);
    }

    @Override
    public Observed read(
            Object track, String id, AutomationModule.Target target, List<String> displays, Runnable check) {
        check.run();
        reads++;
        lastTarget = target;
        return observed(exists, lane, displays);
    }

    @Override
    public AutomationModule.Edited edit(
            Object track,
            String id,
            AutomationModule.Target target,
            List<String> displays,
            Function<Observed, AutomationPlan.Write> plan,
            Runnable check,
            Runnable beforeWrite) {
        check.run();
        edits++;
        lastTarget = target;
        lastDisplays = displays;
        Observed before = observed(exists, lane, displays);
        AutomationPlan.Write write = plan.apply(before);
        if (write.empty) return new AutomationModule.Edited(before, write, before, false);
        beforeWrite.run();
        writes++;
        boolean created = !exists;
        exists = true;
        lane = execute(lane, write);
        afterWrite.accept(lane);
        return new AutomationModule.Edited(before, write, observed(true, lane, List.of()), created);
    }

    static List<Native> execute(List<Native> lane, AutomationPlan.Write write) {
        List<Native> out = new ArrayList<>(lane);
        for (Object gone : write.delete) out.remove(gone);
        for (int i = 0; i < write.insert.size(); i++) out.add(write.at.get(i), new Native(write.insert.get(i)));
        return out;
    }
}
