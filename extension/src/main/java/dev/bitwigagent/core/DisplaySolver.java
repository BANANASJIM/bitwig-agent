package dev.bitwigagent.core;

import com.google.gson.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Closed-loop write of a display target ("-6 dB", "25% L"). The controller API has no display-to-normalized setter and
 * the curve is not published, so the normalized value is bisected against the host's own observed display text.
 * Fails closed: an unparseable, unreachable or unobserved display restores the original value and says why.
 */
public final class DisplaySolver {
    public static final int MAX_PROBES = 28;
    public static final long PROBE_TIMEOUT_NANOS = TimeUnit.MILLISECONDS.toNanos(500);
    /** Polls after the value callback before the display text is read (the two callbacks are independent). */
    static final int SETTLE_POLLS = 2;

    /** One writable parameter as seen on the controller thread. */
    public interface Port {
        long valueSeq();

        JsonElement value();

        JsonElement display();

        void write(double normalized);

        void guard();

        /** Counts display updates; a port that has it lets a probe wait for the text its own write produced. */
        default long displaySeq() {
            return -1;
        }
        /** Enabled state of the parameter's owner when writing it can toggle it (a send); null JSON when not applicable. */
        default JsonElement enabled() {
            return JsonNull.INSTANCE;
        }

        default long enabledSeq() {
            return 0;
        }

        default void writeEnabled(boolean enabled) {}
    }

    public static final class Result {
        public boolean ok, dispatched, restoreAttempted, restored;
        public Reason reason;
        public String failure, observedDisplay;
        Boolean originalEnabled;
        public double normalized = Double.NaN, original = Double.NaN;
        int probes;

        /** Whether the solver wrote anything: a display that already matched is solved with no probe. */
        public boolean wrote() {
            return probes > 0;
        }

        public JsonObject json(String requested, double target) {
            JsonObject out = new JsonObject();
            out.addProperty("requested", requested);
            if (target <= DisplayScale.MINUS_INF) out.addProperty("target", "-inf");
            else out.addProperty("target", target);
            if (observedDisplay != null) out.addProperty("observedDisplay", observedDisplay);
            if (!Double.isNaN(normalized)) out.addProperty("normalized", normalized);
            if (!Double.isNaN(original)) out.addProperty("originalNormalized", original);
            out.addProperty("probes", probes);
            out.addProperty("matched", ok);
            out.addProperty("method", "bisection_on_observed_display");
            if (failure != null) out.addProperty("failure", failure);
            if (restoreAttempted) out.addProperty("restored", restored);
            return out;
        }
    }

    private final Scheduler scheduler;
    private final HostOperation op;
    private final Port port;
    /** The fixed scale of a mixer value; null when the target is a quantity in whatever unit the parameter shows. */
    private final DisplayScale.Kind kind;

    private final DisplayScale.Quantity quantity;
    private final double target;
    private final Consumer<Result> done;
    private final Result result = new Result();
    private final java.util.List<double[]> seen = new java.util.ArrayList<>();
    private double lo = 0, hi = 1, floor = 0, ceiling = 1;

    public DisplaySolver(
            Scheduler scheduler,
            HostOperation op,
            Port port,
            DisplayScale.Kind kind,
            double target,
            Consumer<Result> done) {
        this(scheduler, op, port, kind, null, target, done);
    }

    /** A parameter whose display is a number in some unit ("800 Hz"); the unit must be the one the host shows. */
    public DisplaySolver(
            Scheduler scheduler, HostOperation op, Port port, DisplayScale.Quantity quantity, Consumer<Result> done) {
        this(scheduler, op, port, null, quantity, quantity.value, done);
    }

    private DisplaySolver(
            Scheduler scheduler,
            HostOperation op,
            Port port,
            DisplayScale.Kind kind,
            DisplayScale.Quantity quantity,
            double target,
            Consumer<Result> done) {
        this.scheduler = scheduler;
        this.op = op;
        this.port = port;
        this.kind = kind;
        this.quantity = quantity;
        this.target = target;
        this.done = done;
    }

    public void start() {
        port.guard();
        JsonElement now = port.value();
        if (now.isJsonNull() || port.display().isJsonNull())
            throw Errors.notApplied(Reason.NOT_READY, "Parameter value or display is not observed yet.");
        if (quantity != null) requireUnit(port.display().getAsString());
        result.original = now.getAsDouble();
        JsonElement enabled = port.enabled();
        result.originalEnabled = enabled.isJsonNull() ? null : enabled.getAsBoolean();
        if (matchesNow(port.display().getAsString(), now.getAsDouble())) return;
        // -inf is the bottom of the scale: one probe at 0 either shows it or the target is refused.
        probe(target <= DisplayScale.MINUS_INF ? 0 : 0.5);
    }

    /** The display already shows the target to its own resolution: nothing is written. */
    private boolean matchesNow(String shown, double value) {
        if (target <= DisplayScale.MINUS_INF && quantity == null) return false;
        double number = number(shown), tol = tolerance(shown);
        if (Double.isNaN(number) || Math.abs(number - target) > tol) return false;
        result.ok = true;
        result.normalized = value;
        result.observedDisplay = shown;
        done.accept(result);
        return true;
    }

    private double number(String shown) {
        if (quantity == null) return DisplayScale.parse(kind, shown);
        DisplayScale.Quantity q = DisplayScale.quantity(shown);
        return q != null && q.unit.equals(quantity.unit) ? q.value : Double.NaN;
    }

    private double tolerance(String shown) {
        if (target <= DisplayScale.MINUS_INF) return 0.5;
        if (quantity == null) return DisplayScale.tolerance(shown);
        DisplayScale.Quantity q = DisplayScale.quantity(shown);
        return q == null ? 0.5 : q.resolution;
    }

    /** Before anything is written: a text display or another unit is unsupported_unit, never probed. */
    private void requireUnit(String shown) {
        DisplayScale.Quantity now = DisplayScale.quantity(shown);
        if (now == null || !now.unit.equals(quantity.unit))
            throw unsupportedUnit(
                    "The parameter does not display a number in " + quantity.unit
                            + " (write the display in the shape it shows)",
                    port.value(),
                    port.display());
    }

    /** The unsupported_unit refusal, carrying the parameter's current value and display so the expected format is visible. */
    public static RpcException unsupportedUnit(String problem, JsonElement value, JsonElement display) {
        StringBuilder message = new StringBuilder(problem).append("; it is now ");
        boolean known = false;
        if (value != null && !value.isJsonNull()) {
            message.append("value ").append(value.getAsString());
            known = true;
        }
        if (display != null && !display.isJsonNull()) {
            message.append(known ? ", displaying \"" : "displaying \"")
                    .append(display.getAsString())
                    .append('"');
            known = true;
        }
        if (!known) message.append("not observed yet");
        return Errors.notApplied(
                Reason.UNSUPPORTED_UNIT,
                message.append(". Or write the value you read.").toString());
    }

    private void probe(double x) {
        port.guard();
        // The scheduler throws deadline_exceeded once the deadline passes, so stop while a restore still fits.
        if (op.deadline - scheduler.now() < 2 * PROBE_TIMEOUT_NANOS + Scheduler.DEADLINE_MARGIN_NANOS) {
            fail("deadline", Reason.DEADLINE);
            return;
        }
        result.probes++;
        long before = port.valueSeq(), shown = port.displaySeq();
        boolean same = near(port.value(), x);
        if (!same) {
            op.markDispatched();
            result.dispatched = true;
            port.write(x);
        }
        if (same) settle(x, -1);
        else awaitValue(x, before, shown);
    }

    private boolean targetHolds() {
        try {
            port.guard();
            return true;
        } catch (RpcException error) {
            return false;
        }
    }

    private void awaitValue(double x, long before, long shown) {
        scheduler.poll(
                op,
                scheduler.now() + PROBE_TIMEOUT_NANOS,
                () -> !targetHolds() || port.valueSeq() > before && near(port.value(), x),
                () -> {
                    if (targetHolds()) settle(x, shown);
                    else fail("target_changed", Reason.TARGET_CHANGED);
                },
                () -> fail("probe_not_observed", Reason.NOT_OBSERVED));
    }

    /**
     * Waits for the display text this probe's write produced: its update counter moving past {@code shown}. A port without
     * one, a value that was already there, or a text that did not change (the host reports changes only) fall back to a
     * few polls, so a late callback cannot be mistaken for the text of the value before it.
     */
    private void settle(double x, long shown) {
        boolean counted = shown >= 0 && port.displaySeq() >= 0;
        int[] polls = {0};
        Runnable evaluate = () -> {
            if (targetHolds()) evaluate(x);
            else fail("target_changed", Reason.TARGET_CHANGED);
        };
        scheduler.poll(
                op,
                counted ? scheduler.now() + PROBE_TIMEOUT_NANOS : Long.MAX_VALUE,
                () -> !targetHolds() || (counted ? port.displaySeq() > shown : polls[0]++ >= SETTLE_POLLS),
                evaluate,
                evaluate);
    }

    private void evaluate(double x) {
        JsonElement display = port.display();
        result.normalized = x;
        result.observedDisplay = display.isJsonNull() ? null : display.getAsString();
        boolean bottom = target <= DisplayScale.MINUS_INF;
        double number = number(result.observedDisplay), tol = tolerance(result.observedDisplay);
        if (Double.isNaN(number)) {
            // An end of the scale may read "Off": that is out of range, not a failure, while the target itself parses.
            if (bottom || quantity == null || seen.isEmpty() || result.probes >= MAX_PROBES) {
                fail("display_unparseable", Reason.READBACK_MISMATCH);
                return;
            }
            if (x < 0.5) floor = Math.max(floor, x);
            else ceiling = Math.min(ceiling, x);
            narrow();
            if (hi - lo < 2e-5) fail("display_not_reachable", Reason.READBACK_MISMATCH);
            else probe((lo + hi) / 2);
            return;
        }
        if (Math.abs(number - target) <= tol) {
            result.ok = true;
            done.accept(result);
            return;
        }
        if (bottom) {
            fail("display_not_reachable", Reason.READBACK_MISMATCH);
            return;
        }
        seen.add(new double[] {x, number});
        narrow();
        if (result.probes >= MAX_PROBES || hi - lo < 2e-5) {
            fail("display_not_reachable", Reason.READBACK_MISMATCH);
            return;
        }
        probe((lo + hi) / 2);
    }

    /**
     * The bounds from every probe so far. A parameter's display rises with its value until two probes show otherwise,
     * so the direction is learned from the first pair of probes that differ, not assumed.
     */
    private void narrow() {
        boolean ascending = true;
        for (int i = 1; i < seen.size(); i++) {
            double dn = seen.get(i)[1] - seen.get(0)[1], dx = seen.get(i)[0] - seen.get(0)[0];
            if (dn != 0) {
                ascending = dn * dx > 0;
                break;
            }
        }
        lo = floor;
        hi = ceiling;
        for (double[] probe : seen) {
            boolean below = probe[1] < target;
            if (below == ascending) lo = Math.max(lo, probe[0]);
            else hi = Math.min(hi, probe[0]);
        }
    }

    /** Put the original value (and enabled state, which a probe write can flip) back and report whether the host showed it; never retried. */
    private void fail(String failure, Reason reason) {
        result.failure = failure;
        result.reason = reason;
        if (!result.dispatched) {
            done.accept(result);
            return;
        }
        result.restoreAttempted = true;
        try {
            port.guard();
            long before = port.valueSeq(), enabledBefore = port.enabledSeq();
            boolean value = !near(port.value(), result.original);
            JsonElement enabledNow = port.enabled();
            boolean enabled = result.originalEnabled != null
                    && !enabledNow.isJsonNull()
                    && enabledNow.getAsBoolean() != result.originalEnabled;
            if (!value && !enabled) {
                result.restored = true;
                done.accept(result);
                return;
            }
            if (value) port.write(result.original);
            if (enabled) port.writeEnabled(result.originalEnabled);
            awaitRestore(before, enabledBefore, value, enabled);
        } catch (RpcException error) {
            done.accept(result);
        }
    }

    private void awaitRestore(long before, long enabledBefore, boolean value, boolean enabled) {
        scheduler.poll(
                op,
                scheduler.now() + PROBE_TIMEOUT_NANOS,
                () -> !targetHolds() || restored(before, enabledBefore, value, enabled),
                () -> {
                    result.restored = targetHolds();
                    done.accept(result);
                },
                () -> done.accept(result));
    }

    private boolean restored(long before, long enabledBefore, boolean value, boolean enabled) {
        JsonElement enabledNow = port.enabled();
        boolean valueOk = !value || (port.valueSeq() > before && near(port.value(), result.original));
        boolean enabledOk = !enabled
                || (port.enabledSeq() > enabledBefore
                        && !enabledNow.isJsonNull()
                        && enabledNow.getAsBoolean() == result.originalEnabled);
        return valueOk && enabledOk;
    }

    private static boolean near(JsonElement value, double x) {
        return !value.isJsonNull() && Math.abs(value.getAsDouble() - x) <= HostLimits.TOLERANCE;
    }
}
