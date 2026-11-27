package dev.bitwigagent.core;

import static dev.bitwigagent.RpcAssert.assertRpcError;
import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** A native binding resolved on first use: a missing key fails only its feature, once, and is not retried. */
final class LazyBindingTest {
    private int binds;

    @Test
    void aMissingKeyFailsOnlyThatBindingAndIsNotRetried() {
        LazyBinding<String> missing = new LazyBinding<>(() -> {
            binds++;
            throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "Bitwig 9.9 has no native binding for note.chance.");
        });
        LazyBinding<String> bound = new LazyBinding<>(() -> {
            binds++;
            return "bound";
        });
        assertEquals("Bitwig 9.9 has no native binding for note.chance.", missing.unavailable());
        String message = assertRpcError("native_unavailable", missing::get).getMessage();
        assertTrue(message.contains("note.chance"), message);
        assertNull(bound.unavailable());
        assertEquals("bound", bound.get());
        assertEquals("bound", bound.get());
        assertEquals(2, binds, "each binding is resolved once, its failure included");
    }

    @Test
    void aDescriptorMismatchIsUnavailableAndOtherErrorsPropagateUncached() {
        LazyBinding<Object> mismatch = new LazyBinding<>(() -> {
            throw new NoSuchMethodException("Exact public descriptor mismatch: note.chance");
        });
        assertTrue(mismatch.unavailable().contains("descriptor mismatch"));
        LazyBinding<Object> linkage = new LazyBinding<>(() -> {
            throw new NoClassDefFoundError("c1N");
        });
        assertTrue(linkage.unavailable().contains("c1N"));
        LazyBinding<Object> cancelled = new LazyBinding<>(() -> {
            binds++;
            throw Errors.notApplied(Reason.CANCELLED, "cancelled");
        });
        assertRpcError("cancelled", cancelled::unavailable);
        assertRpcError("cancelled", cancelled::get);
        assertEquals(2, binds, "a failure that is not about the binding is retried");
    }
}
