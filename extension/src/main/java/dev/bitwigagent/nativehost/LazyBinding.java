package dev.bitwigagent.nativehost;

import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.core.RpcException;

/**
 * Bindings resolved on first use and kept, so a key a host lacks fails only the features that use it (compat layer
 * §3.2). A failure to bind (a reflective, linkage or other unchecked error from the binding code) is that feature's
 * {@code native_unavailable} and is kept too: it cannot change for the loaded jar. A refusal the binding raises itself
 * is kept when it is {@code native_unavailable} or {@code host_mismatch}; any other (a cancelled request) is not.
 *
 * <p>Not thread-safe, and the kept value is published without synchronization: an instance belongs to the thread that
 * calls {@link #get}, the extension's controller thread for {@link NativePorts}' handles and the owning adapter's
 * calling thread for an adapter's own first-use bindings. Hand the bound value to other threads only through a
 * happens-before edge (the host's dispatch to its document or model thread is one).
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
        } catch (RpcException error) {
            if (error.is(Reason.NATIVE_UNAVAILABLE) || error.is(Reason.HOST_MISMATCH)) failure = error;
            throw error;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException error) {
            failure = Errors.notApplied(Reason.NATIVE_UNAVAILABLE, unavailable + Errors.diagnostic(error));
            throw failure;
        }
        return value;
    }

    /** Why it cannot bind (running {@code bind} now if it has not been tried), or null when it binds. */
    String unavailable(Bind<T> bind) {
        try {
            get(bind);
            return null;
        } catch (RpcException error) {
            if (error != failure) throw error;
            return failure.getMessage();
        }
    }
}
