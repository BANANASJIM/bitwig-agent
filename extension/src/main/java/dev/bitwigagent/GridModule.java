package dev.bitwigagent;

import com.bitwig.extension.callback.*;
import com.bitwig.extension.controller.api.*;
import com.google.gson.*;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import static dev.bitwigagent.HostServices.failure;

/** Strict RPC boundary and observed public-device targeting; native operations use a separate adapter. */
final class GridModule implements HostModule {
    static final Set<String> METHODS = Set.of("grid.capabilities", "grid.catalog", "grid.get", "grid.parameters.get", "grid.parameters.set",
            "grid.modules.move", "grid.modules.delete", "grid.modules.insert", "grid.modules.rename", "grid.connections.set");
    private final HostServices s;
    private final Source[][] sources = new Source[HostServices.TRACKS][HostServices.DEVICES];
    private final AtomicLong[] tracks = new AtomicLong[HostServices.TRACKS];
    private GridAdapter adapter;

    GridModule(HostServices services) { this(services, null); }
    GridModule(HostServices services, GridAdapter adapter) {
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
            case "grid.capabilities":
                Params.fields(p, Set.of(), Set.of("detail"));
                if (p.has("detail") && !(p.get("detail").isJsonPrimitive() && p.get("detail").getAsJsonPrimitive().isString()
                        && Set.of("summary", "full").contains(p.get("detail").getAsString())))
                    throw failure("invalid_params", "detail must be \"summary\" or \"full\".");
                return;
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
            case "grid.connections.set":
                if (p != null && p.has("connections")) {
                    Params.fields(p, Set.of("trackId", "deviceIndex", "expectedDeviceName", "connections"), Set.of("graph"));
                    graphMode(p);
                    canonicalUuid(p, "trackId");
                    Params.integer(p, "deviceIndex", 0, HostServices.DEVICES - 1);
                    Params.string(p, "expectedDeviceName");
                    GridConnectionWriter.Request.parseBatch(p);
                    return;
                }
                if (p == null || !p.keySet().containsAll(Set.of("trackId", "deviceIndex", "expectedDeviceName",
                        "targetModuleId", "targetPortKey", "expectedSource", "source")))
                    throw failure("invalid_params", "Connection edits require trackId, deviceIndex, expectedDeviceName, targetModuleId, targetPortKey, expectedSource and source (optional graph); source fields must be explicitly nullable.");
                for (String key : p.keySet())
                    if (!key.equals("graph") && !Set.of("trackId", "deviceIndex", "expectedDeviceName",
                            "targetModuleId", "targetPortKey", "expectedSource", "source").contains(key))
                        throw failure("invalid_params", "Unknown parameter: " + key);
                graphMode(p);
                canonicalUuid(p, "trackId");
                Params.integer(p, "deviceIndex", 0, HostServices.DEVICES - 1);
                Params.string(p, "expectedDeviceName");
                GridConnectionWriter.Request.parse(p);
                return;
            case "grid.modules.insert":
                boolean many = p != null && p.has("modules");
                Params.fields(p, many ? Set.of("trackId", "deviceIndex", "expectedDeviceName", "modules")
                        : Set.of("trackId", "deviceIndex", "expectedDeviceName", "x", "y"),
                        many ? Set.of("graph") : Set.of("graph", "typeId", "typeName")); graphMode(p);
                canonicalUuid(p, "trackId");
                Params.integer(p, "deviceIndex", 0, HostServices.DEVICES - 1);
                Params.string(p, "expectedDeviceName");
                if (many) GridInsertionWriter.Request.parseBatch(p); else GridInsertionWriter.Request.parse(p);
                return;
            case "grid.modules.rename":
                Params.fields(p, Set.of("trackId", "deviceIndex", "expectedDeviceName", "moduleId", "expectedCustomName", "customName"), Set.of("graph")); graphMode(p);
                canonicalUuid(p, "trackId"); canonicalUuid(p, "moduleId");
                Params.integer(p, "deviceIndex", 0, HostServices.DEVICES - 1);
                Params.string(p, "expectedDeviceName");
                GridModuleNameWriter.Request.parse(p);
                return;
            case "grid.modules.move":
            case "grid.modules.delete":
                boolean moving = method.equals("grid.modules.move");
                Params.fields(p, moving
                        ? Set.of("trackId", "deviceIndex", "expectedDeviceName", "moduleId", "expectedX", "expectedY", "x", "y")
                        : Set.of("trackId", "deviceIndex", "expectedDeviceName", "moduleId"), Set.of("graph"));
                graphMode(p);
                canonicalUuid(p, "trackId"); canonicalUuid(p, "moduleId");
                Params.integer(p, "deviceIndex", 0, HostServices.DEVICES - 1);
                Params.string(p, "expectedDeviceName");
                if (moving) GridModuleWriter.Move.parse(p);
                return;
            case "grid.parameters.set":
            case "grid.parameters.get":
                boolean writing = method.equals("grid.parameters.set");
                if (writing && p != null && p.has("writes")) { validateBatch(p); return; }
                Params.fields(p, writing
                        ? Set.of("trackId", "deviceIndex", "expectedDeviceName", "moduleId", "parameterId", "expectedKind", "expectedValue")
                        : Set.of("trackId", "deviceIndex", "expectedDeviceName", "moduleId", "parameterId"),
                        writing ? Set.of("value", "display") : Set.of());
                canonicalUuid(p, "trackId"); canonicalUuid(p, "moduleId");
                Params.integer(p, "deviceIndex", 0, HostServices.DEVICES - 1);
                Params.string(p, "expectedDeviceName");
                if (!p.get("parameterId").isJsonPrimitive() || !p.get("parameterId").getAsJsonPrimitive().isString()
                        || p.get("parameterId").getAsString().length() > GridSnapshotReader.MAX_TEXT)
                    throw failure("invalid_params", "parameterId must be a string of at most 1024 UTF-16 units.");
                if (writing) GridParameterWriter.Request.parse(p);
                return;
            case "grid.get":
                Params.fields(p, Set.of("trackId", "deviceIndex", "expectedDeviceName"), Set.of("moduleOffset", "moduleLimit", "includeParameters", "diagnostics", "moduleIds"));
                String id = Params.string(p, "trackId");
                if (!id.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
                    throw failure("invalid_params", "trackId must be a canonical UUID.");
                Params.integer(p, "deviceIndex", 0, HostServices.DEVICES - 1); Params.string(p, "expectedDeviceName");
                if (p.has("moduleOffset")) Params.integer(p, "moduleOffset", 0, 100000);
                if (p.has("moduleLimit")) Params.integer(p, "moduleLimit", 1, 128);
                if (p.has("includeParameters")) Params.bool(p, "includeParameters");
                if (p.has("diagnostics")) Params.bool(p, "diagnostics");
                if (p.has("moduleIds")) moduleIds(p);
                return;
            default: throw failure("unknown_method", "Unknown Grid operation.");
        }
    }
    private static final String[] READS = {"catalog", "structure", "typedParameters", "display", "parameterInspection"};
    private static final String[] WRITERS = {"parameterWrite", "booleanPanels", "moduleMove", "moduleDelete", "moduleRename",
            "moduleInsert", "connectionWrite"};
    /** Default grid.capabilities: availability booleans only (reasons for unavailable ones); detail:"full" has every binding. */
    static JsonObject compactCapabilities(JsonObject full) {
        JsonObject result = HostServices.observed();
        for (String key : new String[] {"hostVersion", "gatePassed", "available", "topologyQuarantined", "topologyAdmissionBlocked"})
            if (full.has(key)) result.add(key, full.get(key));
        JsonObject reads = new JsonObject(), writers = new JsonObject(), unavailable = new JsonObject();
        for (String key : READS) summarize(full, key, "available", reads, unavailable);
        for (String key : WRITERS) summarize(full, key, key.equals("booleanPanels") ? "writeAvailable" : "available", writers, unavailable);
        result.add("reads", reads); result.add("writers", writers);
        if (unavailable.size() > 0) result.add("unavailable", unavailable);
        result.addProperty("detail", "pass detail:\"full\" for per-binding coverage, operations and limits");
        return result;
    }
    private static void summarize(JsonObject full, String key, String flag, JsonObject out, JsonObject unavailable) {
        if (!full.has(key) || !full.get(key).isJsonObject()) return;
        JsonObject binding = full.getAsJsonObject(key);
        boolean available = binding.has(flag) && binding.get(flag).getAsBoolean();
        out.addProperty(key, available);
        if (!available && binding.has("reason") && !binding.get("reason").isJsonNull()) unavailable.add(key, binding.get("reason"));
    }
    private static boolean graphMode(JsonObject p) {
        if (!p.has("graph")) return false;
        JsonElement value = p.get("graph");
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
                || !(value.getAsString().equals("diff") || value.getAsString().equals("full")))
            throw failure("invalid_params", "graph must be \"diff\" or \"full\".");
        return value.getAsString().equals("full");
    }
    private static Set<String> moduleIds(JsonObject p) {
        JsonElement value = p.get("moduleIds");
        if (!value.isJsonArray() || value.getAsJsonArray().size() < 1 || value.getAsJsonArray().size() > 128)
            throw failure("invalid_params", "moduleIds must be an array of 1..128 canonical UUIDs.");
        Set<String> ids = new java.util.HashSet<>();
        for (JsonElement item : value.getAsJsonArray()) {
            if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString() || !item.getAsString().matches(UUID_PATTERN))
                throw failure("invalid_params", "moduleIds must be an array of 1..128 canonical UUIDs.");
            ids.add(item.getAsString().toLowerCase(java.util.Locale.ROOT));
        }
        return ids;
    }
    private static final String UUID_PATTERN = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    private static java.util.List<GridParameterWriter.Entry> batch(JsonObject p) {
        java.util.List<GridParameterWriter.Entry> entries = new java.util.ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        JsonElement writes = p.get("writes");
        if (!writes.isJsonArray() || writes.getAsJsonArray().size() < 1 || writes.getAsJsonArray().size() > 64)
            throw failure("invalid_params", "writes must be an array of 1..64 parameter writes.");
        for (JsonElement element : writes.getAsJsonArray()) {
            if (!element.isJsonObject()) throw failure("invalid_params", "Each write must be an object.");
            JsonObject w = element.getAsJsonObject();
            Params.fields(w, Set.of("moduleId", "parameterId", "expectedKind", "expectedValue"), Set.of("value", "display"));
            canonicalUuid(w, "moduleId");
            if (!w.get("parameterId").isJsonPrimitive() || !w.get("parameterId").getAsJsonPrimitive().isString()
                    || w.get("parameterId").getAsString().length() > GridSnapshotReader.MAX_TEXT)
                throw failure("invalid_params", "parameterId must be a string of at most 1024 UTF-16 units.");
            UUID module = UUID.fromString(w.get("moduleId").getAsString());
            String parameter = w.get("parameterId").getAsString();
            if (!seen.add(module + "/" + parameter))
                throw failure("invalid_params", "writes must not repeat a moduleId/parameterId pair.");
            entries.add(new GridParameterWriter.Entry(module, parameter, GridParameterWriter.Request.parse(w)));
        }
        return entries;
    }
    private static void validateBatch(JsonObject p) {
        Params.fields(p, Set.of("trackId", "deviceIndex", "expectedDeviceName", "writes"), Set.of());
        canonicalUuid(p, "trackId");
        Params.integer(p, "deviceIndex", 0, HostServices.DEVICES - 1);
        Params.string(p, "expectedDeviceName");
        batch(p);
    }
    @Override public void dispatch(HostOperation op, String method, JsonObject p) {
        s.check(op);
        if (adapter == null) adapter = new Bitwig614GridAdapter(s.nativeRuntime(op).access("grid_unavailable"));
        if (method.equals("grid.capabilities")) {
            s.check(op);
            JsonObject full = adapter.capabilities();
            op.result.complete(p.has("detail") && p.get("detail").getAsString().equals("full") ? full : compactCapabilities(full));
            return;
        }
        final boolean moving = method.equals("grid.modules.move");
        final boolean connection = method.equals("grid.connections.set");
        final boolean inserting = method.equals("grid.modules.insert");
        final boolean renaming = method.equals("grid.modules.rename");
        final boolean moduleEditing = moving || method.equals("grid.modules.delete");
        final boolean topology = moduleEditing || connection || inserting || renaming;
        final boolean scalarWriting = method.equals("grid.parameters.set");
        final boolean batching = scalarWriting && p.has("writes");
        final boolean fullGraph = topology && graphMode(p);
        final java.util.List<GridParameterWriter.Entry> entries = batching ? batch(p) : null;
        final boolean writing = scalarWriting || topology;
        final boolean inspection = method.equals("grid.parameters.get") || scalarWriting;
        final GridParameterWriter.Request request = scalarWriting && !batching ? GridParameterWriter.Request.parse(p) : null;
        final GridModuleWriter.Move move = moving ? GridModuleWriter.Move.parse(p) : null;
        final boolean cableBatch = connection && p.has("connections");
        final java.util.List<GridConnectionWriter.Request> cables = cableBatch ? GridConnectionWriter.Request.parseBatch(p) : null;
        final GridConnectionWriter.Request cable = connection && !cableBatch ? GridConnectionWriter.Request.parse(p) : null;
        final boolean insertBatch = inserting && p.has("modules");
        final java.util.List<GridInsertionWriter.Request> insertions = insertBatch ? GridInsertionWriter.Request.parseBatch(p) : null;
        final GridInsertionWriter.Request insertion = inserting && !insertBatch ? GridInsertionWriter.Request.parse(p) : null;
        final GridModuleNameWriter.Request name = renaming ? GridModuleNameWriter.Request.parse(p) : null;
        final HostServices.SafetyObservation safety = writing ? s.stoppedSafety(op) : null;
        final Target target = method.equals("grid.get") || inspection || topology ? target(p) : null;
        final UUID moduleId = (inspection && !batching) || moduleEditing || renaming ? UUID.fromString(p.get("moduleId").getAsString()) : null;
        final String parameterId = inspection && !batching ? p.get("parameterId").getAsString() : null;
        final int offset = integer(p, target == null ? "offset" : "moduleOffset", 0);
        final int limit = integer(p, target == null ? "limit" : "moduleLimit", 64);
        final boolean parameters = !p.has("includeParameters") || p.get("includeParameters").getAsBoolean();
        final boolean diagnostics = p.has("diagnostics") && p.get("diagnostics").getAsBoolean();
        final Set<String> moduleFilter = p.has("moduleIds") ? moduleIds(p) : null;
        final String query = p.has("query") ? p.get("query").getAsString() : "";
        guard(op, target);
        adapter.execute(() -> {
            try {
                Runnable check = () -> {
                    s.check(op);
                    if (target != null && (tracks[target.slot].get() != target.trackEpoch || target.source.epoch.get() != target.deviceEpoch))
                        throw failure("stale_context", "Observed bank target changed while the Grid read was queued or running.");
                    if (writing) s.guardStoppedSafety(safety);
                };
                check.run();
                if (topology) {
                    java.util.function.Consumer<JsonObject> complete = value -> deliver(op, target, safety, true, check, GridDiff.shape(value, fullGraph));
                    Runnable dispatch = () -> { check.run(); op.dispatched = true; };
                    if (insertBatch) adapter.insertModules(target.source.device, target.uuid, insertions, check, dispatch, complete, op::fail);
                    else if (inserting) adapter.insertModule(target.source.device, target.uuid, insertion, check, dispatch, complete, op::fail);
                    else if (renaming) adapter.renameModule(target.source.device, target.uuid, moduleId, name, check, dispatch, complete, op::fail);
                    else if (cableBatch) adapter.setConnections(target.source.device, target.uuid, cables, check, dispatch, complete, op::fail);
                    else if (connection) adapter.setConnection(target.source.device, target.uuid, cable, check, dispatch, complete, op::fail);
                    else if (moving) adapter.moveModule(target.source.device, target.uuid, moduleId, move, check, dispatch, complete, op::fail);
                    else adapter.deleteModule(target.source.device, target.uuid, moduleId, check, dispatch, complete, op::fail);
                    return;
                }
                JsonObject value = target == null ? adapter.catalog(query, offset, limit, check)
                        : batching ? adapter.setParameters(target.source.device, target.uuid, entries, check,
                                () -> { check.run(); op.dispatched = true; })
                        : writing ? GridDiff.shape(adapter.setParameter(target.source.device, target.uuid, moduleId, parameterId, request, check,
                                () -> { check.run(); op.dispatched = true; }), true)
                        : inspection ? adapter.parameter(target.source.device, target.uuid, moduleId, parameterId, check)
                        : adapter.snapshot(target.source.device, target.uuid, offset, limit, parameters, diagnostics, moduleFilter, check);
                deliver(op, target, safety, writing, check, value);
            } catch (RuntimeException error) {
                op.fail(error instanceof RpcException ? error
                        : failure("grid_unavailable", "Grid operation failed: " + error.getClass().getSimpleName() + "; inspect the exact host binding."));
            } catch (Error error) {
                op.fail(failure("grid_unavailable", "Native Grid operation failed; inspect the loaded host binding and current state."));
            }
        });
    }
    private void deliver(HostOperation op, Target target, HostServices.SafetyObservation safety,
            boolean writing, Runnable check, JsonObject value) {
        check.run();
        // No native model, stream, or mutable JSON crosses the document-to-controller boundary.
        final String serialized = BoundedJson.serialize(value, 240000, "grid_bounds_exceeded",
                "Grid response exceeds bounded transport size; reduce moduleLimit or exclude parameters.");
        check.run();
        s.schedule(op, () -> {
            guard(op, target);
            if (writing) s.guardStoppedSafety(safety);
            JsonObject result = JsonParser.parseString(serialized).getAsJsonObject();
            if (target != null) {
                JsonObject identity = new JsonObject(); identity.addProperty("trackId", target.id);
                identity.addProperty("deviceIndex", target.index); identity.addProperty("expectedDeviceName", target.name);
                if (!writing) {
                    identity.addProperty("identityScope", "observed_bank_proxy_not_persistent_device_identity");
                    identity.addProperty("nativeOwningTrack", "corroborated_before_and_after_capture");
                    identity.addProperty("devicePosition", "public_observed_only");
                    result.addProperty("scope", "top_level_device_bank");
                }
                result.add("target", identity);
            }
            op.result.complete(result);
        }, 0);
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
    static void canonicalUuid(JsonObject p, String key) {
        if (!Params.string(p, key).matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
            throw failure("invalid_params", key + " must be a canonical UUID.");
    }
    private static int integer(JsonObject p, String key, int fallback) { return p.has(key) ? p.get(key).getAsInt() : fallback; }
    private static final class Source {
        final Device device; final AtomicLong epoch = new AtomicLong();
        boolean existsObserved, nameObserved, positionObserved;
        Source(Device device) { this.device = device; }
    }
    private static final class Target {
        final String id, name; final int slot, index; final Source source; final long trackEpoch, deviceEpoch;
        final UUID uuid;
        Target(String id, int slot, int index, String name, Source source, long trackEpoch, long deviceEpoch) {
            this.id = id; this.slot = slot; this.index = index; this.name = name; this.source = source;
            this.uuid = UUID.fromString(id);
            this.trackEpoch = trackEpoch; this.deviceEpoch = deviceEpoch;
        }
    }
}
