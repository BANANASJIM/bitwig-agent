package dev.bitwigagent;

import java.util.Properties;
import java.util.UUID;
import java.util.function.Supplier;
import static dev.bitwigagent.HostServices.failure;

/** String-only process coordination; no extension/native objects survive in System properties. */
final class GridTopologyQuarantine {
    static final String KEY = "dev.bitwigagent.grid.topology.native-lifetime.v1";
    private static final String IDLE = "v1:idle", PENDING = "v1:pending:", POISONED = "v1:quarantined";
    private final Supplier<Properties> properties;
    private volatile boolean inaccessible;

    GridTopologyQuarantine() { this(System::getProperties); }
    GridTopologyQuarantine(Properties isolated) { this(() -> isolated); }
    GridTopologyQuarantine(Supplier<Properties> properties) { this.properties = properties; }

    private Properties properties() {
        try {
            Properties result = properties.get();
            if (result == null) throw new IllegalStateException("Missing process properties");
            return result;
        } catch (RuntimeException error) { inaccessible = true; throw unavailable(); }
    }
    private String state(Properties p) {
        if (inaccessible) throw unavailable();
        try {
            Object value = p.get(KEY);
            if (value == null) { p.setProperty(KEY, IDLE); return IDLE; }
            if (!(value instanceof String)) throw new IllegalStateException("Non-string topology marker");
            String text = (String) value;
            if (IDLE.equals(text) || POISONED.equals(text)) return text;
            if (text.startsWith(PENDING) && text.substring(PENDING.length()).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) return text;
            throw new IllegalStateException("Unknown topology marker version/state");
        } catch (RuntimeException error) { inaccessible = true; throw unavailable(); }
    }
    private void set(Properties p, String state) {
        try { p.setProperty(KEY, state); }
        catch (RuntimeException error) { inaccessible = true; throw unavailable(); }
    }
    String blockedReason() {
        try {
            Properties p = properties();
            synchronized (p) {
                String state = state(p);
                if (IDLE.equals(state)) return null;
                return state.startsWith(PENDING) ? "A native Grid topology lifetime is pending; timeout/disconnection is not cancellation. Missing settlement requires full Bitwig JVM restart."
                        : "Grid topology is process-quarantined after a native lifecycle failure; fully restart Bitwig and inspect state, do not retry or assume rollback.";
            }
        } catch (RpcException error) { return error.getMessage(); }
    }
    boolean quarantined() {
        try { Properties p = properties(); synchronized (p) { return POISONED.equals(state(p)); } }
        catch (RpcException error) { return true; }
    }
    void requireAvailable() { String reason = blockedReason(); if (reason != null) throw failure("grid_unavailable", reason); }
    String reserve() {
        Properties p = properties();
        synchronized (p) {
            if (!IDLE.equals(state(p))) throw unavailable();
            String owner = UUID.randomUUID().toString();
            set(p, PENDING + owner); return owner;
        }
    }
    void requireOwner(String owner) {
        Properties p = properties();
        synchronized (p) { if (!state(p).equals(PENDING + owner)) throw unavailable(); }
    }
    boolean release(String owner) {
        Properties p = properties();
        synchronized (p) {
            if (!state(p).equals(PENDING + owner)) return false;
            set(p, IDLE); return true;
        }
    }
    void poison() {
        Properties p = properties();
        synchronized (p) { state(p); set(p, POISONED); }
    }
    void invoke(Runnable mutation) {
        String owner = reserve();
        try { mutation.run(); }
        catch (Throwable error) {
            poison();
            RpcException translated = failure("grid_unavailable", "Native Grid topology operation/callback failed; outcome unknown. Process topology guard quarantined; fully restart Bitwig, inspect state and do not assume rollback.");
            translated.initCause(error);
            throw translated;
        }
        if (!release(owner)) throw unavailable();
    }
    private static RpcException unavailable() {
        return failure("grid_unavailable", "Native Grid topology process marker is pending, quarantined, malformed or inaccessible. Admission fails closed across extension replacement; full Bitwig JVM restart may be required.");
    }
}
