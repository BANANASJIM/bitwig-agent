package dev.bitwigagent;

import com.bitwig.extension.controller.api.ControllerHost;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Controller-thread task queue, the one clock, request deadlines and polling for observed state. */
final class Scheduler implements AutoCloseable {
    static final long POLL_MS = 20;
    /** One observation window per write and ONE margin before the deadline (>= 2x every poll period, so the last poll
     *  always runs and a late controller thread still yields the bounded not-observed result, not deadline_exceeded). */
    static final long OBSERVE_WINDOW_NANOS = TimeUnit.MILLISECONDS.toNanos(750);

    static final long DEADLINE_MARGIN_NANOS = TimeUnit.MILLISECONDS.toNanos(50);

    private final ControllerHost host;
    /** The controller's monotonic clock in nanoseconds; request deadlines are on the same clock. */
    private final LongSupplier clock;

    private volatile boolean closed;

    Scheduler(ControllerHost host, LongSupplier clock) {
        this.host = host;
        this.clock = clock;
    }

    long now() {
        return clock.getAsLong();
    }

    void schedule(HostOperation operation, Runnable task, long delayMs) {
        if (operation.result.isDone()) return;
        host.scheduleTask(
                () -> {
                    if (operation.result.isDone()) return;
                    try {
                        check(operation);
                        task.run();
                    } catch (RuntimeException | Error error) {
                        operation.fail(error);
                    }
                },
                delayMs);
    }

    /** Samples now and then every {@link #POLL_MS} until {@code done} holds or {@code until} passes. */
    void poll(HostOperation operation, long until, BooleanSupplier done, Runnable onDone, Runnable onTimeout) {
        if (done.getAsBoolean()) onDone.run();
        else if (now() >= until) onTimeout.run();
        else schedule(operation, () -> poll(operation, until, done, onDone, onTimeout), POLL_MS);
    }

    /** {@link #poll} with one finisher that reads the final state and is told whether {@code done} held. */
    void poll(HostOperation operation, long until, BooleanSupplier done, Consumer<Boolean> finish) {
        poll(operation, until, done, () -> finish.accept(true), () -> finish.accept(false));
    }

    /** Polls {@code step} until it returns the conclusion to run; {@code onTimeout} runs if {@code until} passes first. */
    void conclude(HostOperation operation, long until, Supplier<Runnable> step, Runnable onTimeout) {
        Runnable[] conclusion = new Runnable[1];
        poll(operation, until, () -> (conclusion[0] = step.get()) != null, () -> conclusion[0].run(), onTimeout);
    }

    /** Polls until {@code done}; the request deadline, enforced by every scheduled step, is the only timeout. */
    void await(HostOperation operation, BooleanSupplier done, Runnable onDone) {
        poll(operation, Long.MAX_VALUE, done, onDone, () -> {});
    }

    /** Takes {@code sample} now and every {@link #POLL_MS} until {@code until}, then {@code onEnd}. */
    void sample(HostOperation operation, long until, Runnable sample, Runnable onEnd) {
        poll(
                operation,
                until,
                () -> {
                    sample.run();
                    return false;
                },
                () -> {},
                onEnd);
    }

    long observeUntil(HostOperation operation) {
        return Math.min(operation.deadline - DEADLINE_MARGIN_NANOS, now() + OBSERVE_WINDOW_NANOS);
    }

    /** The whole request deadline minus the shared margin, for waits that may legitimately outlast one window. */
    long deadlineUntil(HostOperation operation) {
        return operation.deadline - DEADLINE_MARGIN_NANOS;
    }

    void check(HostOperation operation) {
        if (closed) throw Errors.of(Reason.NOT_READY, "Extension has stopped.", operation.dispatched());
        if (operation.result.isDone())
            throw Errors.of(Reason.CANCELLED, "Operation is no longer awaiting a result.", operation.dispatched());
        if (now() >= operation.deadline)
            throw Errors.of(
                    Reason.DEADLINE, "Deadline expired; inspect state before another write.", operation.dispatched());
    }

    @Override
    public void close() {
        closed = true;
    }
}
