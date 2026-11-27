package dev.bitwigagent.nativehost;

import static dev.bitwigagent.RpcAssert.assertRpcError;
import static org.junit.jupiter.api.Assertions.*;

import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.HostOperation;
import dev.bitwigagent.core.PortHandle;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.core.RpcException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The composition root over a gate that does not open (no host jar here): every port fails closed with its own reason
 * while the ports that report availability themselves still bind, the gate is opened once, and a binding outcome is
 * kept unless the request was cancelled. Binding every port on each shipped entry's jar is
 * {@link NativeTrackResolverTest}, which runs where that jar is on the classpath.
 */
public final class NativePortsTest {
    private static final HostOperation OP = new HostOperation(Long.MAX_VALUE);

    @Test
    void aClosedGateFailsEachPortClosedAndNeverAsAnInternalError() {
        NativeHost closed = new NativeHost(new Object(), "0.0.0", () -> {});
        List<Integer> asked = new ArrayList<>();
        NativePorts ports = new NativePorts(op -> {
            asked.add(1);
            return closed;
        });
        int bound = 0;
        for (PortHandle<?> port : ports.all()) {
            int before = asked.size();
            try {
                assertNotNull(port.get(OP));
                assertSame(port.get(OP), port.get(OP), "a bound port is kept");
                bound++;
            } catch (RpcException error) {
                assertTrue(
                        Set.of(Reason.NATIVE_UNAVAILABLE, Reason.HOST_MISMATCH).contains(error.reason),
                        error.getMessage());
                assertSame(error, assertThrows(RpcException.class, () -> port.get(OP)), "a refusal is kept");
            }
            assertTrue(asked.size() - before <= 1, "a port asks the gate only until it is bound or refused");
        }
        assertEquals(2, bound, "Grid and modulator reads bind and report their own availability");
        RpcException gated = assertRpcError(
                Reason.NATIVE_UNAVAILABLE.wire(), () -> ports.keySignature().get(OP));
        assertTrue(gated.getMessage().startsWith("The project key signature needs the native gate: Native access"));
        assertTrue(assertRpcError(
                        Reason.NATIVE_UNAVAILABLE.wire(),
                        () -> ports.modulatorEdits().get(OP))
                .getMessage()
                .startsWith("Exact runtime gate is unavailable: "));
        assertRpcError(Reason.HOST_MISMATCH.wire(), () -> ports.routing().get(OP));
    }

    @Test
    void aCancelledRequestIsNotKeptAsTheBindingOutcome() {
        NativeHost closed = new NativeHost(new Object(), "0.0.0", () -> {});
        boolean[] cancel = {true};
        NativePorts ports = new NativePorts(op -> {
            if (cancel[0]) throw Errors.notApplied(Reason.CANCELLED, "stop");
            return closed;
        });
        assertRpcError(Reason.CANCELLED.wire(), () -> ports.render().get(OP));
        cancel[0] = false;
        assertRpcError(Reason.HOST_MISMATCH.wire(), () -> ports.render().get(OP));
    }

    @Test
    void aBindingErrorIsTheFeaturesNativeUnavailableWithItsDiagnostic() {
        LazyBinding<Object> binding = new LazyBinding<>("Exact thing unavailable: ");
        int[] tries = {0};
        LazyBinding.Bind<Object> failing = () -> {
            tries[0]++;
            throw new NoSuchMethodException("q1");
        };
        RpcException error = assertRpcError(Reason.NATIVE_UNAVAILABLE.wire(), () -> binding.get(failing));
        assertEquals("Exact thing unavailable: NoSuchMethodException: q1", error.getMessage());
        assertSame(error, assertThrows(RpcException.class, () -> binding.get(failing)));
        assertEquals(1, tries[0], "a failure to bind cannot change for the loaded jar and is not retried");
        LazyBinding<Object> linkage = new LazyBinding<>("");
        assertEquals(
                "NoClassDefFoundError: q2",
                assertRpcError(
                                Reason.NATIVE_UNAVAILABLE.wire(),
                                () -> linkage.get(() -> {
                                    throw new NoClassDefFoundError("q2");
                                }))
                        .getMessage());
        LazyBinding<Object> broken = new LazyBinding<>("Exact thing unavailable: ");
        int[] calls = {0};
        LazyBinding.Bind<Object> npe = () -> {
            calls[0]++;
            throw new IllegalStateException("q3");
        };
        RpcException unchecked = assertRpcError(Reason.NATIVE_UNAVAILABLE.wire(), () -> broken.get(npe));
        assertEquals("Exact thing unavailable: IllegalStateException: q3", unchecked.getMessage());
        assertSame(unchecked, assertThrows(RpcException.class, () -> broken.get(npe)));
        assertEquals(1, calls[0], "an unchecked error from binding code is kept like a reflective one");
        LazyBinding<Object> missing = new LazyBinding<>("");
        LazyBinding.Bind<Object> noKey = () -> {
            throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "Bitwig 9.9 has no native binding for note.chance.");
        };
        assertEquals("Bitwig 9.9 has no native binding for note.chance.", missing.unavailable(noKey));
        assertSame(missing.unavailable(noKey), missing.unavailable(noKey), "a missing key is kept, not retried");
        assertNull(new LazyBinding<>("").unavailable(() -> "bound"), "a binding that binds has no reason");
        LazyBinding<Object> busy = new LazyBinding<>("");
        assertRpcError(
                Reason.BUSY.wire(),
                () -> busy.get(() -> {
                    throw Errors.notApplied(Reason.BUSY, "later");
                }));
        assertRpcError(
                Reason.BUSY.wire(),
                () -> busy.unavailable(() -> {
                    throw Errors.notApplied(Reason.BUSY, "later");
                }));
        Object value = new Object();
        assertSame(value, busy.get(() -> value), "only refusals of the binding itself are kept");
    }
}
