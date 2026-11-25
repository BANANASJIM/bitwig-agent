package dev.bitwigagent;

import com.google.gson.JsonObject;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Supplier;
import static dev.bitwigagent.HostServices.failure;

/**
 * String-only process coordination; no extension/native objects survive in System properties.
 * Two quarantine kinds: native failure (sticky until JVM restart) and settlement mismatch (the native lifetime
 * finished but its later graph check disagreed; only an explicit reconcile that re-verifies the graph releases it).
 */
final class GridTopologyQuarantine {
    static final String KEY = "dev.bitwigagent.grid.topology.native-lifetime.v1";
    /** Bounded JSON describing why the marker is quarantined (origin, operation, target, error); diagnostic only. */
    static final String DETAIL_KEY = KEY + ".detail";
    static final int MAX_DETAIL = 4096;
    static final String NATIVE = "native_failure", SETTLEMENT = "settlement_mismatch";
    private static final String IDLE = "v1:idle", PENDING = "v1:pending:", POISONED = "v1:quarantined", MISMATCH = "v1:quarantined:settlement";
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
            if (IDLE.equals(text) || POISONED.equals(text) || MISMATCH.equals(text)) return text;
            if (text.startsWith(PENDING) && text.substring(PENDING.length()).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) return text;
            throw new IllegalStateException("Unknown topology marker version/state");
        } catch (RuntimeException error) { inaccessible = true; throw unavailable(); }
    }
    private void set(Properties p, String state) {
        try { p.setProperty(KEY, state); }
        catch (RuntimeException error) { inaccessible = true; throw unavailable(); }
    }
    private void detail(Properties p, String detail) {
        try {
            if (detail == null) p.remove(DETAIL_KEY);
            else p.setProperty(DETAIL_KEY, detail.length() > MAX_DETAIL ? detail.substring(0, MAX_DETAIL) : detail);
        } catch (RuntimeException ignored) { /* Diagnostic only; the marker alone decides admission. */ }
    }
    String blockedReason() {
        try {
            Properties p = properties();
            synchronized (p) {
                String state = state(p);
                if (IDLE.equals(state)) return null;
                if (MISMATCH.equals(state)) return "Grid topology is quarantined: an insertion's native callbacks returned but its later graph check disagreed. "
                        + "Inspect with grid.get, then call grid.topology.reconcile on that device; it releases once the graph is consistent and nothing native is pending, no restart needed.";
                return state.startsWith(PENDING) ? "A native Grid topology lifetime is pending; timeout/disconnection is not cancellation. A late native callback settles it; missing settlement requires full Bitwig JVM restart."
                        : "Grid topology is process-quarantined after a native lifecycle failure; fully restart Bitwig and inspect state, do not retry or assume rollback.";
            }
        } catch (RpcException error) { return error.getMessage(); }
    }
    boolean quarantined() {
        try { Properties p = properties(); synchronized (p) { String state = state(p); return POISONED.equals(state) || MISMATCH.equals(state); } }
        catch (RpcException error) { return true; }
    }
    /** null (idle), "pending", NATIVE or SETTLEMENT; malformed/inaccessible reads as NATIVE. */
    String origin() {
        try {
            Properties p = properties();
            synchronized (p) {
                String state = state(p);
                return IDLE.equals(state) ? null : state.startsWith(PENDING) ? "pending" : MISMATCH.equals(state) ? SETTLEMENT : NATIVE;
            }
        } catch (RpcException error) { return NATIVE; }
    }
    /** Recorded detail JSON text, or null. */
    String detail() {
        try {
            Properties p = properties();
            synchronized (p) { Object value = p.get(DETAIL_KEY); return value instanceof String ? (String) value : null; }
        } catch (RuntimeException error) { return null; }
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
            set(p, IDLE); detail(p, null); return true;
        }
    }
    void poison() { poison(null); }
    /** Sticky native-failure quarantine; overrides a settlement mismatch, never downgraded. */
    void poison(String detail) {
        Properties p = properties();
        synchronized (p) { state(p); set(p, POISONED); if (detail != null) detail(p, detail); }
    }
    /**
     * Settlement of {@code owner} observed a graph that disagreed with its expectation after the native lifetime
     * finished. Only the matching pending owner may enter the recoverable state; anything else fails closed (sticky).
     */
    boolean mismatch(String owner, String detail) {
        Properties p = properties();
        synchronized (p) {
            if (!state(p).equals(PENDING + owner)) { set(p, POISONED); return false; }
            set(p, MISMATCH); detail(p, detail); return true;
        }
    }
    /** Releases a settlement mismatch only if it is still the one the caller verified against (same detail text). */
    boolean reconcile(String verifiedDetail) {
        Properties p = properties();
        synchronized (p) {
            if (!MISMATCH.equals(state(p))) return false;
            Object now = p.get(DETAIL_KEY);
            if (verifiedDetail == null ? now != null : !verifiedDetail.equals(now)) return false;
            set(p, IDLE); detail(p, null); return true;
        }
    }
    void invoke(Runnable mutation) {
        String owner = reserve();
        try { mutation.run(); }
        catch (Throwable error) {
            JsonObject detail = new JsonObject(); detail.addProperty("origin", NATIVE); detail.addProperty("operation", "synchronous_topology_edit");
            JsonObject why = new JsonObject(); why.addProperty("code", error.getClass().getSimpleName());
            String message = String.valueOf(error.getMessage()); why.addProperty("message", message.substring(0, Math.min(message.length(), 512)));
            detail.add("error", why);
            poison(detail.toString());
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
