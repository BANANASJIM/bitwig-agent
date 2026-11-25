package dev.bitwigagent;

import com.google.gson.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Closed-loop write of a display target ("-6 dB", "25% L"). The controller API has no display-to-normalized setter and
 * the curve is not published, so the normalized value is bisected against the host's own observed display text.
 * Fails closed: an unparseable, unreachable or unobserved display restores the original value and says why.
 */
final class DisplaySolver {
    static final int MAX_PROBES = 28;
    static final long PROBE_TIMEOUT_NANOS = TimeUnit.MILLISECONDS.toNanos(500);
    static final long POLL_MS = 20;
    /** Polls after the value callback before the display text is read (the two callbacks are independent). */
    static final int SETTLE_POLLS = 2;

    /** One writable parameter as seen on the controller thread. */
    interface Port {
        long valueSeq();
        JsonElement value();
        JsonElement display();
        void write(double normalized);
        void guard();
    }

    static final class Result {
        boolean ok, dispatched, restoreAttempted, restored;
        String reason, observedDisplay;
        double normalized = Double.NaN, original = Double.NaN;
        int probes;

        JsonObject json(String requested, double target) {
            JsonObject out = new JsonObject();
            out.addProperty("requested", requested);
            if (target <= DisplayScale.MINUS_INF) out.addProperty("target", "-inf"); else out.addProperty("target", target);
            if (observedDisplay != null) out.addProperty("observedDisplay", observedDisplay);
            if (!Double.isNaN(normalized)) out.addProperty("normalized", normalized);
            if (!Double.isNaN(original)) out.addProperty("originalNormalized", original);
            out.addProperty("probes", probes);
            out.addProperty("matched", ok);
            out.addProperty("method", "bisection_on_observed_display");
            if (reason != null) out.addProperty("reason", reason);
            if (restoreAttempted) out.addProperty("restored", restored);
            return out;
        }
    }

    private final HostServices s;
    private final HostOperation op;
    private final Port port;
    private final DisplayScale.Kind kind;
    private final double target;
    private final Consumer<Result> done;
    private final Result result = new Result();
    private double lo = 0, hi = 1;

    DisplaySolver(HostServices s, HostOperation op, Port port, DisplayScale.Kind kind, double target, Consumer<Result> done) {
        this.s = s; this.op = op; this.port = port; this.kind = kind; this.target = target; this.done = done;
    }

    void start() {
        port.guard();
        JsonElement now = port.value();
        if (now.isJsonNull() || port.display().isJsonNull())
            throw HostServices.failure("not_ready", "Parameter value or display is not observed yet.");
        result.original = now.getAsDouble();
        // -inf is the bottom of the scale: one probe at 0 either shows it or the target is refused.
        probe(target <= DisplayScale.MINUS_INF ? 0 : 0.5);
    }

    private void probe(double x) {
        port.guard();
        result.probes++;
        long before = port.valueSeq();
        boolean same = near(port.value(), x);
        if (!same) { op.dispatched = true; result.dispatched = true; port.write(x); }
        awaitValue(x, before, same, System.nanoTime() + PROBE_TIMEOUT_NANOS, same ? 0 : -1);
    }

    /** settle < 0: waiting for the value callback; otherwise counts the polls that let the display text catch up. */
    private void awaitValue(double x, long before, boolean same, long until, int settle) {
        try { port.guard(); } catch (RpcException error) { fail("target_changed"); return; }
        if (settle < 0 && port.valueSeq() > before && near(port.value(), x)) settle = 0;
        if (settle >= SETTLE_POLLS) { evaluate(x); return; }
        if (settle < 0 && System.nanoTime() >= until) { fail("probe_not_observed"); return; }
        int next = settle < 0 ? -1 : settle + 1;
        s.schedule(op, () -> awaitValue(x, before, same, until, next), POLL_MS);
    }

    private void evaluate(double x) {
        JsonElement display = port.display();
        result.normalized = x;
        result.observedDisplay = display.isJsonNull() ? null : display.getAsString();
        double number = DisplayScale.parse(kind, result.observedDisplay);
        if (Double.isNaN(number)) { fail("display_unparseable"); return; }
        boolean bottom = target <= DisplayScale.MINUS_INF;
        double tol = bottom ? 0.5 : DisplayScale.tolerance(result.observedDisplay);
        if (Math.abs(number - target) <= tol) { result.ok = true; done.accept(result); return; }
        if (bottom) { fail("display_not_reachable"); return; }
        if (number < target) lo = x; else hi = x;
        if (result.probes >= MAX_PROBES || hi - lo < 2e-5) { fail("display_not_reachable"); return; }
        probe((lo + hi) / 2);
    }

    /** Put the original value back and report whether the host showed it again; never retried. */
    private void fail(String reason) {
        result.reason = reason;
        if (!result.dispatched) { done.accept(result); return; }
        result.restoreAttempted = true;
        try {
            port.guard();
            long before = port.valueSeq();
            if (near(port.value(), result.original)) { result.restored = true; done.accept(result); return; }
            port.write(result.original);
            awaitRestore(before, System.nanoTime() + PROBE_TIMEOUT_NANOS);
        } catch (RpcException error) { done.accept(result); }
    }

    private void awaitRestore(long before, long until) {
        try { port.guard(); } catch (RpcException error) { done.accept(result); return; }
        if (port.valueSeq() > before && near(port.value(), result.original)) { result.restored = true; done.accept(result); return; }
        if (System.nanoTime() >= until) { done.accept(result); return; }
        s.schedule(op, () -> awaitRestore(before, until), POLL_MS);
    }

    private static boolean near(JsonElement value, double x) {
        return !value.isJsonNull() && Math.abs(value.getAsDouble() - x) <= HostServices.TOLERANCE;
    }
}
