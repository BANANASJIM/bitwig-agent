package dev.bitwigagent;

import com.bitwig.extension.callback.*;
import com.bitwig.extension.controller.api.*;
import com.google.gson.*;
import java.io.*;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import static dev.bitwigagent.HostServices.failure;

/** Strict RPC boundary and observed public-device targeting; native reads live in a separate adapter. */
final class GridModule implements HostModule {
    static final Set<String> METHODS = Set.of("grid.capabilities", "grid.catalog", "grid.get");
    static final int MAX_RESPONSE_CHARS = 240000;
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();
    private final HostServices s;
    private final Source[][] sources = new Source[HostServices.TRACKS][HostServices.DEVICES];
    private final AtomicLong[] tracks = new AtomicLong[HostServices.TRACKS];
    private GridReadAdapter adapter;

    GridModule(HostServices services) { this(services, null); }
    GridModule(HostServices services, GridReadAdapter adapter) {
        s = services; this.adapter = adapter;
        for (int t = 0; t < HostServices.TRACKS; t++) {
            AtomicLong epoch = tracks[t] = new AtomicLong();
            Track track = (Track) s.tracks.getItemAt(t);
            track.channelId().addValueObserver((StringValueChangedCallback) value -> epoch.incrementAndGet());
            track.exists().addValueObserver((BooleanValueChangedCallback) value -> epoch.incrementAndGet());
            track.position().addValueObserver((IntegerValueChangedCallback) value -> epoch.incrementAndGet());
            s.devices[t].scrollPosition().addValueObserver((IntegerValueChangedCallback) value -> epoch.incrementAndGet());
            for (int d = 0; d < HostServices.DEVICES; d++) {
                Source source = sources[t][d] = new Source((Device) s.devices[t].getItemAt(d));
                source.device.exists().addValueObserver((BooleanValueChangedCallback) value -> {
                    source.existsObserved = true; source.epoch.incrementAndGet();
                });
                source.device.name().addValueObserver((StringValueChangedCallback) value -> {
                    source.nameObserved = true; source.epoch.incrementAndGet();
                });
                source.device.position().addValueObserver((IntegerValueChangedCallback) value -> {
                    source.positionObserved = true; source.epoch.incrementAndGet();
                });
            }
        }
    }
    @Override public Set<String> methods() { return METHODS; }
    @Override public void validate(String method, JsonObject params) { validateParams(method, params); }
    static void validateParams(String method, JsonObject p) {
        switch (method) {
            case "grid.capabilities": Params.fields(p, Set.of(), Set.of()); return;
            case "grid.catalog":
                Params.fields(p, Set.of(), Set.of("query", "offset", "limit"));
                if (p.has("query")) {
                    if (!p.get("query").isJsonPrimitive() || !p.get("query").getAsJsonPrimitive().isString())
                        throw failure("invalid_params", "query must be a string of at most 128 UTF-16 units.");
                    String query = p.get("query").getAsString();
                    if (query.length() > 128)
                        throw failure("invalid_params", "query must be a string of at most 128 UTF-16 units.");
                }
                if (p.has("offset")) Params.integer(p, "offset", 0, 100000);
                if (p.has("limit")) Params.integer(p, "limit", 1, 256);
                return;
            case "grid.get":
                Params.fields(p, Set.of("trackId", "deviceIndex", "expectedDeviceName"), Set.of("moduleOffset", "moduleLimit", "includeParameters"));
                String id = Params.string(p, "trackId");
                if (!id.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
                    throw failure("invalid_params", "trackId must be a canonical UUID.");
                Params.integer(p, "deviceIndex", 0, HostServices.DEVICES - 1); Params.string(p, "expectedDeviceName");
                if (p.has("moduleOffset")) Params.integer(p, "moduleOffset", 0, 100000);
                if (p.has("moduleLimit")) Params.integer(p, "moduleLimit", 1, 128);
                if (p.has("includeParameters")) Params.bool(p, "includeParameters");
                return;
            default: throw failure("unknown_method", "Unknown Grid operation.");
        }
    }
    @Override public void dispatch(HostOperation op, String method, JsonObject p) {
        s.check(op);
        if (adapter == null) adapter = new Bitwig613GridReadAdapter(s.host, s.host.getHostVersion(), () -> s.check(op));
        if (method.equals("grid.capabilities")) { s.check(op); op.result.complete(adapter.capabilities()); return; }
        final Target target = method.equals("grid.get") ? target(p) : null;
        final int offset = integer(p, target == null ? "offset" : "moduleOffset", 0);
        final int limit = integer(p, target == null ? "limit" : "moduleLimit", 64);
        final boolean parameters = !p.has("includeParameters") || p.get("includeParameters").getAsBoolean();
        final String query = p.has("query") ? p.get("query").getAsString() : "";
        guard(op, target);
        adapter.execute(() -> {
            try {
                Runnable check = () -> {
                    s.check(op);
                    if (target != null && (tracks[target.slot].get() != target.trackEpoch || target.source.epoch.get() != target.deviceEpoch))
                        throw failure("stale_context", "Observed bank target changed while the Grid read was queued or running.");
                };
                check.run();
                JsonObject value = target == null ? adapter.catalog(query, offset, limit, check)
                        : adapter.snapshot(target.source.device, offset, limit, parameters, check);
                check.run();
                // No native model, stream, or mutable JSON crosses the document-to-controller boundary.
                final String serialized = serialize(value);
                check.run();
                s.schedule(op, () -> {
                    guard(op, target);
                    JsonObject result = JsonParser.parseString(serialized).getAsJsonObject();
                    if (target != null) {
                        JsonObject identity = new JsonObject(); identity.addProperty("trackId", target.id);
                        identity.addProperty("deviceIndex", target.index); identity.addProperty("expectedDeviceName", target.name);
                        identity.addProperty("identityScope", "observed_bank_proxy_not_persistent_device_identity");
                        result.add("target", identity); result.addProperty("scope", "top_level_device_bank");
                    }
                    op.result.complete(result);
                }, 0);
            } catch (RuntimeException error) {
                op.result.completeExceptionally(error instanceof RpcException ? error
                        : failure("grid_unavailable", "Grid read failed: " + error.getClass().getSimpleName() + "; inspect the exact host binding."));
            }
            catch (LinkageError error) { op.result.completeExceptionally(failure("grid_unavailable", "Native Grid linkage failed; inspect grid.capabilities and loaded host binary.")); }
        });
    }
    private Target target(JsonObject p) {
        String id = p.get("trackId").getAsString(); int slot = s.trackSlot(id), index = p.get("deviceIndex").getAsInt();
        Source source = sources[slot][index];
        if (!source.existsObserved || !source.nameObserved || !source.positionObserved)
            throw failure("not_ready", "Explicit device metadata has not been observed.");
        if (s.devices[slot].scrollPosition().get() != 0) throw failure("stale_context", "Device bank is not at the zero-based window.");
        if (!source.device.exists().get() || source.device.position().get() != index)
            throw failure("target_not_found", "Device is absent at the explicit index.");
        String name = p.get("expectedDeviceName").getAsString();
        if (!name.equals(source.device.name().get())) throw failure("stale_context", "Device name differs from expectedDeviceName.");
        return new Target(id, slot, index, name, source, tracks[slot].get(), source.epoch.get());
    }
    private void guard(HostOperation op, Target target) {
        s.check(op); if (target == null) return;
        Device device = target.source.device;
        if (tracks[target.slot].get() != target.trackEpoch || target.source.epoch.get() != target.deviceEpoch
                || !s.trackMatches(target.id, (Track) s.tracks.getItemAt(target.slot))
                || s.devices[target.slot].scrollPosition().get() != 0 || !device.exists().get()
                || device.position().get() != target.index || !target.name.equals(device.name().get()))
            throw failure("stale_context", "Explicit bank device changed before the Grid result could be delivered.");
    }
    static String serialize(JsonObject value) {
        StringWriter buffer = new StringWriter();
        Writer bounded = new Writer() {
            int count;
            @Override public void write(char[] chars, int offset, int length) throws IOException {
                if (length > MAX_RESPONSE_CHARS - count) throw new IOException("Grid response bound exceeded");
                buffer.write(chars, offset, length); count += length;
            }
            @Override public void flush() { }
            @Override public void close() { }
        };
        try { JSON.toJson(value, bounded); return buffer.toString(); }
        catch (JsonIOException error) { throw failure("grid_bounds_exceeded", "Grid response exceeds bounded transport size; reduce moduleLimit or exclude parameters."); }
    }
    private static int integer(JsonObject p, String key, int fallback) { return p.has(key) ? p.get(key).getAsInt() : fallback; }
    private static final class Source {
        final Device device; final AtomicLong epoch = new AtomicLong();
        boolean existsObserved, nameObserved, positionObserved;
        Source(Device device) { this.device = device; }
    }
    private static final class Target {
        final String id, name; final int slot, index; final Source source; final long trackEpoch, deviceEpoch;
        Target(String id, int slot, int index, String name, Source source, long trackEpoch, long deviceEpoch) {
            this.id = id; this.slot = slot; this.index = index; this.name = name; this.source = source;
            this.trackEpoch = trackEpoch; this.deviceEpoch = deviceEpoch;
        }
    }
}
