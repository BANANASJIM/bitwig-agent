package dev.bitwigagent;

import java.util.ArrayList;
import java.util.List;

/**
 * The scrollable scene window, held by every operation that addresses slot or scene proxies. Holders share a window
 * at the same position unless one is exclusive; a write facing another position is busy, a read waits. The window
 * returns to scene 0 once nobody holds it.
 */
final class SceneWindowLease {
    private final TrackWindow window;
    private final Scheduler scheduler;
    private final List<HostOperation> holders = new ArrayList<>();
    private int offset;
    private boolean exclusive;

    SceneWindowLease(TrackWindow window, Scheduler scheduler) {
        this.window = window;
        this.scheduler = scheduler;
        window.observeScenes();
        for (int t = 0; t < HostLimits.TRACKS; t++) window.markSlotIdentityInterested(window.at(t));
    }

    private void prune() {
        holders.removeIf(holder -> holder.result.isDone());
        if (holders.isEmpty()) exclusive = false;
    }

    boolean held() {
        prune();
        return !holders.isEmpty();
    }

    boolean heldBy(HostOperation operation) {
        prune();
        return holders.contains(operation);
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
    void enter(HostOperation operation, int position, boolean wantExclusive, boolean wait, Runnable ready) {
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
        boolean moved = window.sceneOffset() != position;
        if (moved) window.scrollScenes(position);
        scheduler.poll(
                operation,
                scheduler.observeUntil(operation),
                () -> window.scenesShown(position),
                () -> {
                    if (moved) scheduler.schedule(operation, ready, Scheduler.POLL_MS);
                    else ready.run();
                },
                () -> {
                    throw Errors.notApplied(
                            Reason.NOT_READY, "The scene window did not settle at scene " + position + ".");
                });
    }

    /** Moves a window this operation holds alone, e.g. to the next page of a read. */
    void move(HostOperation operation, int position, Runnable ready) {
        acquire(operation, position, true);
        scroll(operation, position, ready);
    }

    /** {@link #move} once this operation holds the window alone; waits for the other holders until the deadline. */
    void moveWhenFree(HostOperation operation, int position, Runnable ready) {
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

    /** Scrolls back to scene 0 when nobody holds the window; called at the host's flush boundary. */
    void rest() {
        if (!held() && window.scenesObserved() && window.sceneOffset() != 0) window.scrollScenes(0);
    }
}
