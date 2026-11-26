package dev.bitwigagent.grid;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.core.RpcException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * String-only process coordination; no extension/native objects survive in System properties.
 * One marker serializes native topology lifetimes (idle or pending owner). A failed or unverified topology edit
 * quarantines only the Grid device it touched, as one String record per device; other devices keep working.
 * A record survives extension replacement and is released only by an {@link #ack} after re-verification.
 */
public final class GridTopologyQuarantine {
    static final String KEY = "dev.bitwigagent.grid.topology.native-lifetime.v1";
    public static final String DEVICE_PREFIX = KEY + ".device.";
    static final String NATIVE = "native_failure", SETTLEMENT = "settlement_mismatch";
    private static final String IDLE = "v1:idle", PENDING = "v1:pending:";
    private final Supplier<Properties> properties;
    private volatile boolean inaccessible;

    public GridTopologyQuarantine() {
        this(System::getProperties);
    }

    public GridTopologyQuarantine(Properties isolated) {
        this(() -> isolated);
    }

    GridTopologyQuarantine(Supplier<Properties> properties) {
        this.properties = properties;
    }

    /** Identity of one native Grid device for the lifetime of the JVM. */
    static String device(UUID trackId, Object graph) {
        return trackId + "/" + Integer.toHexString(System.identityHashCode(graph));
    }

    private Properties properties() {
        try {
            Properties result = properties.get();
            if (result == null) throw new IllegalStateException("Missing process properties");
            return result;
        } catch (RuntimeException error) {
            inaccessible = true;
            throw unavailable();
        }
    }

    private String state(Properties p) {
        if (inaccessible) throw unavailable();
        try {
            Object value = p.get(KEY);
            if (value == null) {
                p.setProperty(KEY, IDLE);
                return IDLE;
            }
            if (!(value instanceof String)) throw new IllegalStateException("Non-string topology marker");
            String text = (String) value;
            if (IDLE.equals(text)) return text;
            if (text.startsWith(PENDING)
                    && text.substring(PENDING.length())
                            .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) return text;
            throw new IllegalStateException("Unknown topology marker version/state");
        } catch (RuntimeException error) {
            inaccessible = true;
            throw unavailable();
        }
    }

    private void set(Properties p, String state) {
        try {
            p.setProperty(KEY, state);
        } catch (RuntimeException error) {
            inaccessible = true;
            throw unavailable();
        }
    }

    private String record(Properties p, String device) {
        try {
            Object value = p.get(DEVICE_PREFIX + device);
            return value == null ? null : value instanceof String ? (String) value : "{}";
        } catch (RuntimeException error) {
            inaccessible = true;
            throw unavailable();
        }
    }

    private void quarantine(Properties p, String device, String origin, JsonObject detail) {
        JsonObject record = detail == null ? new JsonObject() : detail.deepCopy();
        record.addProperty("origin", origin);
        record.addProperty("device", device);
        record.addProperty("trackId", device.substring(0, device.indexOf('/')));
        try {
            p.setProperty(DEVICE_PREFIX + device, record.toString());
        } catch (RuntimeException error) {
            inaccessible = true;
            throw unavailable();
        }
    }

    private static String originOf(String record) {
        try {
            JsonElement origin =
                    JsonParser.parseString(record).getAsJsonObject().get("origin");
            return origin != null && SETTLEMENT.equals(origin.getAsString()) ? SETTLEMENT : NATIVE;
        } catch (RuntimeException malformed) {
            return NATIVE;
        }
    }

    private static String deviceBlocked(String record) {
        return "This Grid device is held (" + originOf(record)
                + "): a topology edit on it ended with an unknown outcome. Other devices and domains keep working and project.save is allowed. "
                + "Do not retry or assume rollback.";
    }

    private String pendingReason(Properties p) {
        return state(p).startsWith(PENDING)
                ? "A native Grid topology lifetime is pending; timeout/disconnection is not cancellation. A late native callback settles it; missing settlement requires full Bitwig JVM restart."
                : null;
    }

    /** Process-level reason topology writes are blocked on every device (pending lifetime, bad marker), or null. */
    public String blockedReason() {
        try {
            Properties p = properties();
            synchronized (p) {
                return pendingReason(p);
            }
        } catch (RpcException error) {
            return error.getMessage();
        }
    }

    String blockedReason(String device) {
        try {
            Properties p = properties();
            synchronized (p) {
                String pending = pendingReason(p), record = record(p, device);
                return pending != null ? pending : record == null ? null : deviceBlocked(record);
            }
        } catch (RpcException error) {
            return error.getMessage();
        }
    }

    /** Every quarantined device (origin, device, trackId and the recorded detail); empty if the marker is inaccessible. */
    public List<JsonObject> quarantinedDevices() {
        List<JsonObject> out = new ArrayList<>();
        try {
            Properties p = properties();
            synchronized (p) {
                state(p);
                for (Object key : new ArrayList<>(p.keySet())) {
                    if (!(key instanceof String) || !((String) key).startsWith(DEVICE_PREFIX)) continue;
                    String device = ((String) key).substring(DEVICE_PREFIX.length());
                    try {
                        out.add(JsonParser.parseString(record(p, device)).getAsJsonObject());
                    } catch (RuntimeException malformed) {
                        JsonObject fallback = new JsonObject();
                        fallback.addProperty("origin", NATIVE);
                        fallback.addProperty("device", device);
                        out.add(fallback);
                    }
                }
            }
        } catch (RpcException ignored) {
            /* Reported through blockedReason(). */
        }
        return out;
    }

    /** True if any device is quarantined or the marker is unreadable. */
    public boolean quarantined() {
        try {
            Properties p = properties();
            synchronized (p) {
                state(p);
            }
        } catch (RpcException error) {
            return true;
        }
        return !quarantinedDevices().isEmpty();
    }

    boolean quarantined(String device) {
        try {
            Properties p = properties();
            synchronized (p) {
                state(p);
                return record(p, device) != null;
            }
        } catch (RpcException error) {
            return true;
        }
    }

    public boolean pending() {
        try {
            Properties p = properties();
            synchronized (p) {
                return state(p).startsWith(PENDING);
            }
        } catch (RpcException error) {
            return false;
        }
    }

    /** null (not quarantined), NATIVE or SETTLEMENT; a malformed record reads as NATIVE. */
    String origin(String device) {
        try {
            Properties p = properties();
            synchronized (p) {
                state(p);
                String record = record(p, device);
                return record == null ? null : originOf(record);
            }
        } catch (RpcException error) {
            return NATIVE;
        }
    }

    String detail(String device) {
        try {
            Properties p = properties();
            synchronized (p) {
                return record(p, device);
            }
        } catch (RuntimeException error) {
            return null;
        }
    }

    void requireAvailable(String device) {
        Properties p = properties();
        synchronized (p) {
            requireAdmitted(p, device);
        }
    }

    private void requireAdmitted(Properties p, String device) {
        String pending = pendingReason(p), record = record(p, device);
        if (pending != null) throw Errors.notApplied(Reason.BUSY, pending);
        if (record != null) throw Errors.notApplied(Reason.UNRESOLVED, deviceBlocked(record), next(device));
    }

    public static String holdId(String device) {
        return "grid:" + device;
    }

    private static String next(String device) {
        return "Inspect with grid.export, then ack " + holdId(device) + ".";
    }

    String reserve(String device) {
        Properties p = properties();
        synchronized (p) {
            requireAdmitted(p, device);
            String owner = UUID.randomUUID().toString();
            set(p, PENDING + owner);
            return owner;
        }
    }

    void requireOwner(String owner) {
        Properties p = properties();
        synchronized (p) {
            if (!state(p).equals(PENDING + owner)) throw unavailable();
        }
    }

    boolean release(String owner) {
        Properties p = properties();
        synchronized (p) {
            if (!state(p).equals(PENDING + owner)) return false;
            set(p, IDLE);
            return true;
        }
    }

    /** Native failure on {@code device}; also ends the owner's reservation so other devices stay admitted. */
    void poison(String owner, String device, JsonObject detail) {
        Properties p = properties();
        synchronized (p) {
            boolean owned = state(p).equals(PENDING + owner);
            quarantine(p, device, NATIVE, detail);
            if (owned) set(p, IDLE);
        }
    }
    /**
     * Settlement of {@code owner} observed a graph that disagreed with its expectation after the native lifetime
     * finished. Only the matching owner may record the recoverable kind, and a native failure already recorded on the
     * device is never downgraded; anything else fails closed as a native failure.
     */
    boolean mismatch(String owner, String device, JsonObject detail) {
        Properties p = properties();
        synchronized (p) {
            boolean owned = state(p).equals(PENDING + owner), recorded = record(p, device) != null;
            boolean recoverable = owned && !(recorded && NATIVE.equals(originOf(record(p, device))));
            if (recoverable || !recorded) quarantine(p, device, recoverable ? SETTLEMENT : NATIVE, detail);
            if (owned) set(p, IDLE);
            return recoverable;
        }
    }
    /** Releases a device quarantine only if it is still the record the caller verified against (same text). */
    boolean ack(String device, String verifiedRecord) {
        Properties p = properties();
        synchronized (p) {
            state(p);
            String now = record(p, device);
            if (now == null || !now.equals(verifiedRecord)) return false;
            try {
                p.remove(DEVICE_PREFIX + device);
            } catch (RuntimeException error) {
                inaccessible = true;
                throw unavailable();
            }
            return true;
        }
    }

    void invoke(String device, Runnable mutation) {
        String owner = reserve(device);
        try {
            mutation.run();
        } catch (Throwable error) {
            JsonObject detail = new JsonObject();
            detail.addProperty("operation", "synchronous_topology_edit");
            JsonObject why = new JsonObject();
            why.addProperty("code", error.getClass().getSimpleName());
            String message = String.valueOf(error.getMessage());
            why.addProperty("message", message.substring(0, Math.min(message.length(), 512)));
            detail.add("error", why);
            poison(owner, device, detail);
            RpcException translated = Errors.unknown(
                    Reason.NATIVE_UNAVAILABLE,
                    "Native Grid topology operation/callback failed. This Grid device is held (" + holdId(device)
                            + "); other devices and project.save keep working. " + next(device)
                            + " Do not retry or assume rollback.");
            translated.initCause(error);
            throw translated;
        }
        if (!release(owner)) throw unavailable();
    }

    private static RpcException unavailable() {
        return Errors.notApplied(
                Reason.NATIVE_UNAVAILABLE,
                "Native Grid topology process marker is pending, malformed or inaccessible. Admission fails closed across extension replacement; full Bitwig JVM restart may be required.");
    }
}
