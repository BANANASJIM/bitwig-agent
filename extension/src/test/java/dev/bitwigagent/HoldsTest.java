package dev.bitwigagent;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The one place unresolved and in-flight writes are recorded. */
public final class HoldsTest {
    @Test
    void aHoldIsHeldUntilItsDomainReleasesIt() {
        Holds holds = new Holds();
        assertFalse(holds.held("arranger"));
        holds.place("arranger", "An arranger write is unresolved.");
        assertTrue(holds.held("arranger"));
        assertFalse(holds.held("routing"), "domains are independent");
        holds.release("arranger");
        assertFalse(holds.held("arranger"));
        assertTrue(holds.descriptions().isEmpty());
    }

    @Test
    void placingAgainReplacesInPlaceAndKeepsThePlacementOrder() {
        Holds holds = new Holds();
        holds.place("a", "first");
        holds.place("b", "second");
        holds.place("a", "first again");
        assertEquals(List.of("first again", "second"), holds.descriptions());
    }

    @Test
    void holdsThatSayTheSameThingAreListedOnce() {
        Holds holds = new Holds();
        holds.place("x.pending", "A write is pending or unresolved.");
        holds.place("x.quarantine", "A write is pending or unresolved.");
        assertEquals(List.of("A write is pending or unresolved."), holds.descriptions());
        holds.release("x.pending");
        assertEquals(1, holds.descriptions().size(), "the other hold still stands");
    }

    @Test
    void keyedHoldsAreReleasedByKeyAndListedByType() {
        Holds holds = new Holds();
        Object first = "slot 1", second = "slot 2";
        holds.place("launcher.mutation", first, () -> "slot 1 is unresolved");
        holds.place("launcher.mutation", second, () -> "slot 2 is unresolved");
        assertEquals(List.of(first, second), holds.keys("launcher.mutation", Object.class));
        holds.release("launcher.mutation", first);
        assertEquals(List.of(second), holds.keys("launcher.mutation", Object.class));
        assertTrue(holds.held("launcher.mutation"));
    }

    @Test
    void aDescriptionThatSuppliesNullIsNotHeld() {
        Holds holds = new Holds();
        boolean[] rendering = {true};
        holds.place("render.job", "render.job", () -> rendering[0] ? "A render is running." : null);
        assertTrue(holds.held("render.job"));
        assertEquals(List.of("A render is running."), holds.descriptions());
        rendering[0] = false;
        assertFalse(holds.held("render.job"));
        assertTrue(holds.descriptions().isEmpty());
    }
}
