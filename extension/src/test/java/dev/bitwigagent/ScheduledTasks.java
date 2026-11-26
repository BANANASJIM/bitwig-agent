package dev.bitwigagent;

import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.concurrent.TimeUnit;

/** The host's scheduleTask queue on virtual time: tasks leave in due order and taking one advances the clock to it. */
public final class ScheduledTasks {
    private static final class Task {
        final long due, order;
        final Runnable action;

        Task(long due, long order, Runnable action) {
            this.due = due;
            this.order = order;
            this.action = action;
        }
    }

    private final PriorityQueue<Task> queue =
            new PriorityQueue<>(Comparator.<Task>comparingLong(t -> t.due).thenComparingLong(t -> t.order));
    private long now, added;

    public synchronized long now() {
        return now;
    }

    /** A deadline {@code ms} after now. */
    public synchronized long after(long ms) {
        return now + TimeUnit.MILLISECONDS.toNanos(ms);
    }

    public synchronized void advance(long nanos) {
        now += nanos;
    }

    public synchronized void schedule(Runnable action, long delayMs) {
        queue.add(new Task(now + TimeUnit.MILLISECONDS.toNanos(delayMs), added++, action));
    }

    public void add(Runnable action) {
        schedule(action, 0);
    }

    public synchronized Runnable take() {
        Task next = queue.remove();
        now = Math.max(now, next.due);
        return next.action;
    }

    public synchronized boolean isEmpty() {
        return queue.isEmpty();
    }

    public synchronized int size() {
        return queue.size();
    }
}
