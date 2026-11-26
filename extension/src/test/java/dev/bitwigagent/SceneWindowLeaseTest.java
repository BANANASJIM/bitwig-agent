package dev.bitwigagent;

import static dev.bitwigagent.RpcAssert.*;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

/** The scene window lease over the simulated scene bank; says nothing about native scrolling. */
public final class SceneWindowLeaseTest {
    private static LauncherFake world() {
        LauncherFake f = new LauncherFake();
        f.scenes = 40;
        return f;
    }

    private static HostOperation operation(LauncherFake f) {
        return new HostOperation(f.tasks.after(2000));
    }

    private static SceneWindowLease lease(LauncherFake f) {
        return f.services.launcherTargets().lease;
    }

    /** Runs host tasks until nothing is left to run or {@code until} holds. */
    private static void settle(LauncherFake f, BooleanSupplier until) {
        for (int i = 0; i < 3000 && !until.getAsBoolean() && !f.tasks.isEmpty(); i++) f.next();
    }

    private static boolean[] enter(LauncherFake f, HostOperation op, int position, boolean exclusive, boolean wait) {
        boolean[] ready = new boolean[1];
        lease(f).enter(op, position, exclusive, wait, () -> ready[0] = true);
        settle(f, () -> ready[0] || op.result.isDone());
        return ready;
    }

    private static void finish(HostOperation op) {
        op.result.complete(new JsonObject());
    }

    @Test
    void holdersShareAWindowAtTheSameOffset() {
        LauncherFake f = world();
        HostOperation a = operation(f), b = operation(f);
        assertTrue(enter(f, a, 16, false, false)[0]);
        assertTrue(enter(f, b, 16, false, false)[0]);
        assertEquals(16, f.bankOffset);
        assertTrue(lease(f).heldBy(a));
        assertTrue(lease(f).heldBy(b));
        assertTrue(lease(f).admits(operation(f), 16, false));
        assertFalse(lease(f).admits(operation(f), 16, true), "an exclusive request needs the window alone");
        assertFalse(lease(f).admits(operation(f), 0, false), "another position is not shared");
    }

    @Test
    void aWriteFacingAnotherOffsetIsBusyAndChangesNothing() {
        LauncherFake f = world();
        enter(f, operation(f), 16, false, false);
        int scrolls = f.scrolls;
        assertRpcError("busy", () -> lease(f).enter(operation(f), 0, false, false, () -> {}));
        assertEquals(scrolls, f.scrolls);
        assertEquals(16, f.bankOffset);
    }

    @Test
    void anExclusiveHolderRefusesASharerAndAnExclusiveRefusesAHolder() {
        LauncherFake f = world();
        HostOperation exclusive = operation(f);
        enter(f, exclusive, 16, true, false);
        assertRpcError("busy", () -> lease(f).enter(operation(f), 16, false, false, () -> {}));
        finish(exclusive);
        enter(f, operation(f), 16, false, false);
        assertRpcError("busy", () -> lease(f).enter(operation(f), 16, true, false, () -> {}));
    }

    @Test
    void aDoneOperationReleasesTheWindowImplicitly() {
        LauncherFake f = world();
        HostOperation a = operation(f);
        enter(f, a, 16, true, false);
        assertTrue(lease(f).held());
        finish(a);
        assertFalse(lease(f).held());
        assertFalse(lease(f).heldBy(a));
        assertTrue(enter(f, operation(f), 0, true, false)[0], "the exclusive flag went with its holder");
        assertEquals(0, f.bankOffset);
    }

    @Test
    void aReadWaitsForTheHolderAndThenEnters() {
        LauncherFake f = world();
        HostOperation holder = operation(f);
        enter(f, holder, 16, false, false);
        HostOperation reader = operation(f);
        boolean[] ready = new boolean[1];
        lease(f).enter(reader, 0, false, true, () -> ready[0] = true);
        for (int i = 0; i < 5; i++) f.next();
        assertFalse(ready[0], "the reader is still waiting");
        assertEquals(16, f.bankOffset);
        finish(holder);
        settle(f, () -> ready[0]);
        assertTrue(ready[0]);
        assertEquals(0, f.bankOffset);
        assertTrue(lease(f).heldBy(reader));
    }

    @Test
    void aReadFailsBusyAtItsDeadlineWhenTheHolderStays() {
        LauncherFake f = world();
        enter(f, operation(f), 16, false, false);
        HostOperation reader = new HostOperation(f.tasks.after(300));
        boolean[] ready = new boolean[1];
        lease(f).enter(reader, 0, false, true, () -> ready[0] = true);
        settle(f, () -> reader.result.isDone());
        assertRpcError("busy", reader);
        assertFalse(ready[0]);
        assertEquals(16, f.bankOffset);
    }

    @Test
    void anAlreadyShownWindowIsNotScrolledAgain() {
        LauncherFake f = world();
        enter(f, operation(f), 16, false, false);
        int scrolls = f.scrolls;
        assertTrue(enter(f, operation(f), 16, false, false)[0]);
        assertEquals(scrolls, f.scrolls);
    }

    @Test
    void restScrollsBackOnlyWhenTheWindowIsFree() {
        LauncherFake f = world();
        HostOperation holder = operation(f);
        enter(f, holder, 16, false, false);
        lease(f).rest();
        assertEquals(16, f.bankOffset, "a held window stays");
        finish(holder);
        lease(f).rest();
        assertEquals(0, f.bankOffset);
        int scrolls = f.scrolls;
        lease(f).rest();
        assertEquals(scrolls, f.scrolls, "a resting window is not scrolled again");
    }

    @Test
    void restDoesNothingBeforeTheSceneCountIsObserved() {
        LauncherFake f = new LauncherFake(true, false, false, false, false);
        lease(f).rest();
        assertEquals(0, f.scrolls);
    }

    @Test
    void anUnobservedSceneCountIsNotReady() {
        LauncherFake f = new LauncherFake(true, false, false, false, false);
        assertRpcError("not_ready", () -> lease(f).enter(operation(f), 0, false, false, () -> {}));
        assertRpcError("not_ready", () -> lease(f).enter(operation(f), 0, false, true, () -> {}));
    }

    @Test
    void aSoleHolderMovesTheWindowAndASharedOneCannot() {
        LauncherFake f = world();
        HostOperation a = operation(f);
        enter(f, a, 0, false, false);
        boolean[] moved = new boolean[1];
        lease(f).move(a, 24, () -> moved[0] = true);
        settle(f, () -> moved[0]);
        assertTrue(moved[0]);
        assertEquals(24, f.bankOffset);

        assertRpcError("busy", () -> lease(f).enter(operation(f), 24, false, false, () -> {}));

        LauncherFake shared = world();
        HostOperation first = operation(shared);
        enter(shared, first, 0, false, false);
        enter(shared, operation(shared), 0, false, false);
        assertRpcError("busy", () -> lease(shared).move(first, 24, () -> {}));
        assertEquals(0, shared.bankOffset);
    }

    @Test
    void moveWhenFreeWaitsForTheOtherHolder() {
        LauncherFake f = world();
        HostOperation a = operation(f), b = operation(f);
        enter(f, a, 0, false, false);
        enter(f, b, 0, false, false);
        boolean[] moved = new boolean[1];
        lease(f).moveWhenFree(a, 16, () -> moved[0] = true);
        for (int i = 0; i < 5; i++) f.next();
        assertFalse(moved[0]);
        assertEquals(0, f.bankOffset);
        finish(b);
        settle(f, () -> moved[0]);
        assertTrue(moved[0]);
        assertEquals(16, f.bankOffset);
    }

    @Test
    void moveWhenFreeFailsBusyAtTheDeadline() {
        LauncherFake f = world();
        HostOperation a = new HostOperation(f.tasks.after(300));
        enter(f, a, 0, false, false);
        enter(f, operation(f), 0, false, false);
        lease(f).moveWhenFree(a, 16, () -> fail("the window is never free"));
        settle(f, () -> a.result.isDone());
        assertRpcError("busy", a);
        assertEquals(0, f.bankOffset);
    }

    @Test
    void aScrollThatNeverSettlesIsNotReady() {
        LauncherFake f = world();
        f.scrollStuck = true;
        HostOperation op = operation(f);
        boolean[] ready = new boolean[1];
        lease(f).enter(op, 16, false, false, () -> ready[0] = true);
        settle(f, () -> op.result.isDone());
        assertRpcError("not_ready", op);
        assertFalse(ready[0]);
        assertEquals(0, f.bankOffset);
    }
}
