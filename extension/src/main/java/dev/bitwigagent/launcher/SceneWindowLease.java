package dev.bitwigagent.launcher;

import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.HostLimits;
import dev.bitwigagent.core.HostOperation;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.core.Scheduler;
import dev.bitwigagent.host.TrackWindow;
import java.util.ArrayList;
import java.util.List;

/**
 * The scrollable scene window, held by every operation that addresses slot or scene proxies. Holders share a window
 * at the same position unless one is exclusive; a write facing another position is busy, a read waits. Every
 * operation scrolls to its own window under the lease; nothing scrolls the window without holding it.
 */
public final class SceneWindowLease {
    private final TrackWindow window;
    private final Scheduler scheduler;
    private final List<HostOperation> holders = new ArrayList<>();
    private int offset;
    private boolean exclusive;

    public SceneWindowLease(TrackWindow window, Scheduler scheduler) {
        this.window = window;
        this.scheduler = scheduler;
        window.observeScenes();
        for (int t = 0; t < HostLimits.TRACKS; t++) window.markSlotIdentityInterested(window.at(t));
    }

    private void prune() {
        holders.removeIf(holder -> holder.result.isDone());
        if (holders.isEmpty()) exclusive = false;
    }

    /** Whether {@code operation} could hold the window at {@code position} now. */
    boolean admits(HostOperation operation, int position, boolean wantExclusive) {
        prune();
        if (holders.isEmpty() || holders.size() == 1 && holders.get(0) == operation) return true;
        return !exclusive && !wantExclusive && offset == position;
    }

    private void acquire(HostOperation operation, int position, boolean wantExclusive) {
        if (!admits(operation, position, wantExclusive))
            throw Errors.notApplied(
                    Reason.BUSY,
                    "Another operation holds the scene window at scene " + offset + "; nothing was changed.");
        if (!holders.contains(operation)) holders.add(operation);
        offset = position;
        exclusive |= wantExclusive;
    }

    /**
     * Holds the window for {@code operation}, scrolled to {@code position} and settled, then runs {@code ready}. A
     * write facing another position is refused as busy; a read ({@code wait}) waits for the lease until its deadline.
     */
    public void enter(HostOperation operation, int position, boolean wantExclusive, boolean wait, Runnable ready) {
        if (!window.observed() || !window.scenesObserved())
            throw Errors.notApplied(Reason.NOT_READY, "Scene bank has not reported its size yet.");
        if (!wait) {
            acquire(operation, position, wantExclusive);
            scroll(operation, position, ready);
            return;
        }
        scheduler.poll(
                operation,
                scheduler.deadlineUntil(operation),
                () -> admits(operation, position, wantExclusive),
                () -> {
                    acquire(operation, position, wantExclusive);
                    scroll(operation, position, ready);
                },
                () -> {
                    throw Errors.notApplied(
                            Reason.BUSY, "The scene window stayed held at scene " + offset + " until the deadline.");
                });
    }

    private void scroll(HostOperation operation, int position, Runnable ready) {
        scroll(operation, position, ready, () -> {
            throw Errors.notApplied(Reason.NOT_READY, "The scene window did not settle at scene " + position + ".");
        });
    }

    private void scroll(HostOperation operation, int position, Runnable ready, Runnable unsettled) {
        boolean moved = window.scrollTarget() != position;
        if (moved) window.scrollScenes(position);
        scheduler.poll(
                operation,
                scheduler.observeUntil(operation),
                () -> window.scenesShown(position),
                () -> {
                    if (moved) scheduler.schedule(operation, ready, Scheduler.POLL_MS);
                    else ready.run();
                },
                unsettled);
    }

    /** Moves a window this operation holds alone, e.g. to the next page of a read. */
    void move(HostOperation operation, int position, Runnable ready) {
        acquire(operation, position, true);
        scroll(operation, position, ready);
    }

    /** {@link #move} held by {@code holder} while {@code step}, one step of it, waits for the window to settle. */
    void move(HostOperation holder, HostOperation step, int position, Runnable ready) {
        acquire(holder, position, true);
        scroll(step, position, ready);
    }

    /** {@link #move} for an operation that already succeeded: {@code unsettled} runs if the window never settles. */
    void moveAfterWrite(HostOperation operation, int position, Runnable ready, Runnable unsettled) {
        acquire(operation, position, true);
        scroll(operation, position, ready, unsettled);
    }

    /** {@link #move} once this operation holds the window alone; waits for the other holders until the deadline. */
    public void moveWhenFree(HostOperation operation, int position, Runnable ready) {
        scheduler.poll(
                operation,
                scheduler.deadlineUntil(operation),
                () -> admits(operation, position, true),
                () -> move(operation, position, ready),
                () -> {
                    throw Errors.notApplied(
                            Reason.BUSY, "The scene window stayed held by another operation until the deadline.");
                });
    }
}
