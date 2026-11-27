package dev.bitwigagent.nativehost;

import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.core.RpcException;

/**
 * Bindings resolved on first use and kept, so a key a host lacks fails only the features that use it (compat layer
 * §3.2). A failure to bind is that feature's {@code native_unavailable} and is kept too: it cannot change for the
 * loaded jar. Other failures (a cancelled request) are not kept. Used from one thread, the extension's or a handle's.
 */
final class LazyBinding<T> {
    @FunctionalInterface
    interface Bind<T> {
        T bind() throws ReflectiveOperationException;
    }

    private final String unavailable;
    private T value;
    private RpcException failure;

    /** {@code unavailable} prefixes the binding error's diagnostic in the {@code native_unavailable} message. */
    LazyBinding(String unavailable) {
        this.unavailable = unavailable;
    }

    /** The kept binding, or {@code bind} run now when there is none yet (and no kept failure). */
    T get(Bind<T> bind) {
        if (value != null) return value;
        if (failure != null) throw failure;
        try {
            value = bind.bind();
        } catch (ReflectiveOperationException | LinkageError error) {
            failure = Errors.notApplied(Reason.NATIVE_UNAVAILABLE, unavailable + Errors.diagnostic(error));
            throw failure;
        } catch (RpcException error) {
            if (error.is(Reason.NATIVE_UNAVAILABLE) || error.is(Reason.HOST_MISMATCH)) failure = error;
            throw error;
        }
        return value;
    }
}
