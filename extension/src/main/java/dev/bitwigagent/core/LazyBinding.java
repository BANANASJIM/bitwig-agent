package dev.bitwigagent.core;

/**
 * A native binding resolved on first use and cached, its failure included: a key the loaded host's table lacks (or a
 * descriptor that does not match) fails only the feature that uses it, as {@code native_unavailable}, while the rest
 * of the port stays bound. The result never changes for one host JAR, so a failure is not retried.
 */
public final class LazyBinding<T> {
    /** Resolves the binding: the reflective lookups of one feature. */
    public interface Binder<T> {
        T bind() throws ReflectiveOperationException;
    }

    private final Binder<T> binder;
    private boolean tried;
    private T value;
    private String unavailable;

    public LazyBinding(Binder<T> binder) {
        this.binder = binder;
    }

    /** The binding; {@code native_unavailable} with the cached reason when it cannot bind. */
    public T get() {
        if (unavailable() != null) throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, unavailable);
        return value;
    }

    /** Why it cannot bind; null when it binds. */
    public String unavailable() {
        if (tried) return unavailable;
        try {
            value = binder.bind();
        } catch (ReflectiveOperationException | LinkageError error) {
            unavailable = error.toString();
        } catch (RpcException error) {
            if (!error.is(Reason.NATIVE_UNAVAILABLE)) throw error;
            unavailable = error.getMessage();
        }
        tried = true;
        return unavailable;
    }
}
