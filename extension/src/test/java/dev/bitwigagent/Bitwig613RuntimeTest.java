package dev.bitwigagent;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Shared-domain error translation tests, not a substitute for native binding acceptance. */
public final class Bitwig613RuntimeTest {
    private static int assertions;
    public static void main(String[] args) throws Exception {
        deniedRuntimeRetainsEachDomainCode();
        nativeRejectionsRetainTheirInvocationBoundary();
        System.out.println("Bitwig613RuntimeTest: " + assertions + " assertions");
    }
    private static void deniedRuntimeRetainsEachDomainCode() {
        Bitwig613Runtime runtime = new Bitwig613Runtime(new Object(), "unsupported-version", () -> {});
        Bitwig613Runtime.Access grid = runtime.access("grid_unavailable"), modulators = runtime.access("modulator_unavailable");
        check(grid.gateError() != null && grid.gateError().equals(modulators.gateError()),
                "one failed runtime gate is reported consistently to independent domain accessors");
        expect("grid_unavailable", grid::requireAvailable);
        expect("modulator_unavailable", modulators::requireAvailable);
        expect("grid_unavailable", () -> grid.execute(() -> { throw new AssertionError("Denied runtime dispatched work"); }));
        expect("modulator_unavailable", modulators::documentThread);
        expect("modulator_unavailable", () -> modulators.requireModelThread(new Object()));
        expect("cancelled", () -> new Bitwig613Runtime(new Object(), "unsupported-version",
                () -> { throw HostServices.failure("cancelled", "stop before binding"); }));
    }
    private static void nativeRejectionsRetainTheirInvocationBoundary() throws Exception {
        Method reader = ThrowingRead.class.getMethod("read");
        for (String code : new String[]{"grid_unavailable", "modulator_unavailable"}) {
            RpcException nativeRejection = HostServices.failure("cancelled", "native code threw an RPC-shaped error");
            try {
                Bitwig613Runtime.invoke(code, reader, new ThrowingRead(nativeRejection));
                throw new AssertionError("Expected reflected native rejection");
            } catch (RpcException translated) {
                check(code.equals(translated.code) && Bitwig613Runtime.isInvocationFailure(translated),
                        "native rejection is classified as a failed invocation in the calling domain");
                check(translated.getCause() instanceof InvocationTargetException
                        && translated.getCause().getCause() == nativeRejection,
                        "translation preserves the actual invocation boundary and native throwable identity");
            }
            check(!Bitwig613Runtime.isInvocationFailure(nativeRejection),
                    "ordinary controller cancellation remains distinct from reflected invocation failure");
        }
    }
    public static final class ThrowingRead {
        private final RpcException error;
        ThrowingRead(RpcException error) { this.error = error; }
        public Object read() { throw error; }
    }
    private static void expect(String code, Runnable action) {
        try { action.run(); throw new AssertionError("Expected " + code); }
        catch (RpcException error) { check(code.equals(error.code), "exact domain error code: " + code); }
    }
    private static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }
}
