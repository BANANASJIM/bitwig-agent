package dev.bitwigagent.core;

/**
 * A host capability (a port) a domain module uses without knowing how it is bound: {@link #get} returns it, binding it
 * on first use, or throws why it is unavailable on this host ({@code native_unavailable}, or the gate's
 * {@code host_mismatch}). Production handles come from {@code nativehost.NativePorts}; tests pass {@link #of}.
 */
@FunctionalInterface
public interface PortHandle<P> {
    P get(HostOperation op);

    static <P> PortHandle<P> of(P port) {
        return op -> port;
    }
}
