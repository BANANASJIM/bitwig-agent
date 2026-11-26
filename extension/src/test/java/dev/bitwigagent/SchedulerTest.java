package dev.bitwigagent;

import static dev.bitwigagent.RpcAssert.*;
import static org.junit.jupiter.api.Assertions.*;

import com.bitwig.extension.controller.api.ControllerHost;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Polling, deadlines and cancellation on virtual time; no sleeping and no host. */
public final class SchedulerTest {
    private static final long POLL = TimeUnit.MILLISECONDS.toNanos(Scheduler.POLL_MS);

    private final ScheduledTasks tasks = new ScheduledTasks();
    private final FakeHost host = new FakeHost("host");
    private final Scheduler scheduler;

    public SchedulerTest() {
        host.on("scheduleTask", args -> {
            tasks.schedule((Runnable) args[0], ((Number) args[1]).longValue());
            return null;
        });
        scheduler = new Scheduler(host.proxy(ControllerHost.class), tasks::now);
    }

    private void drain() {
        while (!tasks.isEmpty()) tasks.take().run();
    }

    @Test
    void anAlreadyTrueConditionFinishesWithoutScheduling() {
        HostOperation op = new HostOperation(tasks.after(1000));
        List<String> log = new ArrayList<>();
        scheduler.poll(op, tasks.after(500), () -> true, () -> log.add("done"), () -> log.add("timeout"));
        assertEquals(List.of("done"), log);
        assertTrue(tasks.isEmpty(), "nothing is queued once the first sample decides");
    }

    @Test
    void sampleEveryPeriodUntilTheConditionHolds() {
        HostOperation op = new HostOperation(tasks.after(1000));
        long start = tasks.now();
        int[] samples = {0};
        List<String> log = new ArrayList<>();
        scheduler.poll(op, tasks.after(500), () -> ++samples[0] == 4, () -> log.add("done"), () -> log.add("timeout"));
        drain();
        assertEquals(List.of("done"), log);
        assertEquals(4, samples[0]);
        assertEquals(3 * POLL, tasks.now() - start, "three polling periods between four samples");
    }

    @Test
    void theLastSampleAtOrAfterTheLimitTimesOut() {
        HostOperation op = new HostOperation(tasks.after(1000));
        long start = tasks.now();
        int[] samples = {0};
        List<String> log = new ArrayList<>();
        scheduler.poll(
                op,
                tasks.after(100),
                () -> {
                    samples[0]++;
                    return false;
                },
                () -> log.add("done"),
                () -> log.add("timeout"));
        drain();
        assertEquals(List.of("timeout"), log);
        assertEquals(6, samples[0], "samples at 0, 20, ... 100 ms; the one at the limit is the last");
        assertEquals(5 * POLL, tasks.now() - start);
    }

    @Test
    void aConditionMetAtTheLimitStillCountsAsDone() {
        HostOperation op = new HostOperation(tasks.after(1000));
        int[] samples = {0};
        List<String> log = new ArrayList<>();
        scheduler.poll(
                op,
                tasks.after(2 * Scheduler.POLL_MS),
                () -> ++samples[0] == 3,
                () -> log.add("done"),
                () -> log.add("timeout"));
        drain();
        assertEquals(List.of("done"), log);
    }

    @Test
    void oneFinisherIsToldWhetherTheConditionHeld() {
        HostOperation timedOut = new HostOperation(tasks.after(1000));
        List<Boolean> seen = new ArrayList<>();
        scheduler.poll(timedOut, tasks.after(40), () -> false, seen::add);
        drain();
        HostOperation met = new HostOperation(tasks.after(1000));
        scheduler.poll(met, tasks.after(40), () -> true, seen::add);
        assertEquals(List.of(false, true), seen);
    }

    @Test
    void sampleRunsUntilTheEndAndThenFinishes() {
        HostOperation op = new HostOperation(tasks.after(1000));
        int[] samples = {0};
        List<String> log = new ArrayList<>();
        scheduler.sample(op, tasks.after(60), () -> samples[0]++, () -> log.add("end"));
        drain();
        assertEquals(4, samples[0], "0, 20, 40 and 60 ms");
        assertEquals(List.of("end"), log);
    }

    @Test
    void concludeRunsTheFirstConclusionTheStepReturns() {
        HostOperation op = new HostOperation(tasks.after(1000));
        int[] steps = {0};
        List<String> log = new ArrayList<>();
        scheduler.conclude(
                op,
                tasks.after(500),
                () -> ++steps[0] < 3 ? null : () -> log.add("concluded " + steps[0]),
                () -> log.add("timeout"));
        drain();
        assertEquals(List.of("concluded 3"), log);
    }

    @Test
    void concludeTimesOutWhenNoStepConcludes() {
        HostOperation op = new HostOperation(tasks.after(1000));
        List<String> log = new ArrayList<>();
        scheduler.conclude(op, tasks.after(40), () -> null, () -> log.add("timeout"));
        drain();
        assertEquals(List.of("timeout"), log);
    }

    @Test
    void awaitOnlyEndsWhenTheDeadlineFailsTheOperation() {
        HostOperation op = new HostOperation(tasks.after(100));
        scheduler.await(op, () -> false, () -> fail("never done"));
        drain();
        assertRpcError("deadline", op);
    }

    @Test
    void aSettledOperationStopsBeingPolled() {
        HostOperation op = new HostOperation(tasks.after(1000));
        int[] samples = {0};
        scheduler.poll(
                op,
                tasks.after(500),
                () -> {
                    if (++samples[0] == 2) op.result.complete(new JsonObject());
                    return false;
                },
                () -> fail("done"),
                () -> fail("timeout"));
        drain();
        assertEquals(2, samples[0]);
    }

    @Test
    void aClosedSchedulerRefusesFurtherSteps() {
        HostOperation op = new HostOperation(tasks.after(1000));
        scheduler.poll(op, tasks.after(500), () -> false, () -> fail("done"), () -> fail("timeout"));
        scheduler.close();
        drain();
        assertRpcError("not_ready", op);
    }

    @Test
    void anObservationWindowNeverCrossesTheDeadlineMargin() {
        HostOperation roomy = new HostOperation(tasks.after(10_000));
        assertEquals(tasks.now() + Scheduler.OBSERVE_WINDOW_NANOS, scheduler.observeUntil(roomy));
        HostOperation tight = new HostOperation(tasks.after(300));
        assertEquals(tight.deadline - Scheduler.DEADLINE_MARGIN_NANOS, scheduler.observeUntil(tight));
        assertEquals(tight.deadline - Scheduler.DEADLINE_MARGIN_NANOS, scheduler.deadlineUntil(tight));
    }
}
