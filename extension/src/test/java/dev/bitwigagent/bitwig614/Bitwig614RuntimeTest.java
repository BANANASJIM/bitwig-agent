package dev.bitwigagent.bitwig614;

import static dev.bitwigagent.RpcAssert.*;
import static org.junit.jupiter.api.Assertions.*;

import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.core.RpcException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

/** Shared-domain error translation tests, not a substitute for native binding acceptance. */
public final class Bitwig614RuntimeTest {

    @Test
    void aClosedGateIsAnIncompatibleHostForEveryAccessor() {
        Bitwig614Runtime runtime = new Bitwig614Runtime(new Object(), "unsupported-version", () -> {});
        Bitwig614Runtime.Access grid = runtime.access(), modulators = runtime.access();
        assertTrue(
                grid.gateError() != null && grid.gateError().equals(modulators.gateError()),
                "one failed runtime gate is reported consistently to independent domain accessors");
        assertEquals(
                "incompatible",
                assertRpcError("host_mismatch", grid::requireAvailable).code());
        assertRpcError("host_mismatch", modulators::requireAvailable);
        assertRpcError(
                "host_mismatch",
                () -> grid.execute(() -> {
                    throw new AssertionError("Denied runtime dispatched work");
                }));
        assertRpcError("host_mismatch", modulators::documentThread);
        assertRpcError("host_mismatch", () -> modulators.requireModelThread(new Object()));
        assertRpcError(
                "cancelled",
                () -> new Bitwig614Runtime(new Object(), "unsupported-version", () -> {
                    throw Errors.notApplied(Reason.CANCELLED, "stop before binding");
                }));
    }

    @Test
    void nativeRejectionsRetainTheirInvocationBoundary() throws Exception {
        Method reader = ThrowingRead.class.getMethod("read");
        {
            RpcException nativeRejection = Errors.notApplied(Reason.CANCELLED, "native code threw an RPC-shaped error");
            {
                RpcException translated = assertThrows(
                        RpcException.class, () -> Bitwig614Runtime.invoke(reader, new ThrowingRead(nativeRejection)));
                assertTrue(
                        translated.is(Reason.NATIVE_UNAVAILABLE) && Bitwig614Runtime.isInvocationFailure(translated),
                        "native rejection is classified as a failed invocation");
                assertTrue(
                        translated.getCause() instanceof InvocationTargetException
                                && translated.getCause().getCause() == nativeRejection,
                        "translation preserves the actual invocation boundary and native throwable identity");
            }
            assertTrue(
                    !Bitwig614Runtime.isInvocationFailure(nativeRejection),
                    "ordinary controller cancellation remains distinct from reflected invocation failure");
        }
    }

    public static final class ThrowingRead {
        private final RpcException error;

        ThrowingRead(RpcException error) {
            this.error = error;
        }

        public Object read() {
            throw error;
        }
    }
}
