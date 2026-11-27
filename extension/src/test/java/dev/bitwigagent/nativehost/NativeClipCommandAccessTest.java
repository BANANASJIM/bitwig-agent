package dev.bitwigagent.nativehost;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The focus chain's null handling, without a host: any missing link means the launcher is not focused. */
final class NativeClipCommandAccessTest {
    private enum State {
        NOT_FOCUSED,
        FOCUSED
    }

    @Test
    void aNullLinkEndsTheChainWithoutCallingTheRest() {
        List<String> called = new ArrayList<>();
        assertNull(NativeClipCommandAccess.follow(
                "window",
                w -> {
                    called.add("view");
                    return null;
                },
                v -> {
                    called.add("panel");
                    return v;
                }));
        assertEquals(List.of("view"), called);
        assertNull(NativeClipCommandAccess.follow(null, w -> w));
        assertEquals("launcher", NativeClipCommandAccess.follow("window", w -> "panel", p -> "launcher"));
    }

    @Test
    void focusHoldsOnlyWithAFocusedStateAndTheFocusedWidgetInside() {
        assertTrue(NativeClipCommandAccess.holds("launcher", State.FOCUSED, "launcher", (f, w) -> false));
        assertTrue(NativeClipCommandAccess.holds("launcher", State.FOCUSED, "cell", (f, w) -> true));
        assertFalse(NativeClipCommandAccess.holds("launcher", null, "launcher", (f, w) -> true));
        assertFalse(NativeClipCommandAccess.holds("launcher", State.NOT_FOCUSED, "launcher", (f, w) -> true));
        assertFalse(NativeClipCommandAccess.holds("launcher", State.FOCUSED, null, (f, w) -> true));
        assertFalse(NativeClipCommandAccess.holds("launcher", State.FOCUSED, "dialog", (f, w) -> false));
    }
}
