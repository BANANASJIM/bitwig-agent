package dev.bitwigagent;

import static dev.bitwigagent.RpcAssert.*;
import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

/** Shared-domain error translation tests, not a substitute for native binding acceptance. */
public final class Bitwig614RuntimeTest {

    @Test
    void deniedRuntimeRetainsEachDomainCode() {
        Bitwig614Runtime runtime = new Bitwig614Runtime(new Object(), "unsupported-version", () -> {});
        Bitwig614Runtime.Access grid = runtime.access(ErrorCode.GRID_UNAVAILABLE),
                modulators = runtime.access(ErrorCode.MODULATOR_UNAVAILABLE);
        assertTrue(
                grid.gateError() != null && grid.gateError().equals(modulators.gateError()),
                "one failed runtime gate is reported consistently to independent domain accessors");
        assertRpcError("grid_unavailable", grid::requireAvailable);
        assertRpcError("modulator_unavailable", modulators::requireAvailable);
        assertRpcError(
                "grid_unavailable",
                () -> grid.execute(() -> {
                    throw new AssertionError("Denied runtime dispatched work");
                }));
        assertRpcError("modulator_unavailable", modulators::documentThread);
        assertRpcError("modulator_unavailable", () -> modulators.requireModelThread(new Object()));
        assertRpcError(
                "cancelled",
                () -> new Bitwig614Runtime(new Object(), "unsupported-version", () -> {
                    throw Errors.notApplied(ErrorCode.CANCELLED, "stop before binding");
                }));
    }

    @Test
    void nativeRejectionsRetainTheirInvocationBoundary() throws Exception {
        Method reader = ThrowingRead.class.getMethod("read");
        for (ErrorCode code : new ErrorCode[] {ErrorCode.GRID_UNAVAILABLE, ErrorCode.MODULATOR_UNAVAILABLE}) {
            RpcException nativeRejection =
                    Errors.notApplied(ErrorCode.CANCELLED, "native code threw an RPC-shaped error");
            {
                RpcException translated = assertThrows(
                        RpcException.class,
                        () -> Bitwig614Runtime.invoke(code, reader, new ThrowingRead(nativeRejection)));
                assertTrue(
                        translated.is(code) && Bitwig614Runtime.isInvocationFailure(translated),
                        "native rejection is classified as a failed invocation in the calling domain");
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
