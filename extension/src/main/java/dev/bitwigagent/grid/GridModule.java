package dev.bitwigagent.grid;

import com.bitwig.extension.callback.*;
import com.bitwig.extension.controller.api.*;
import com.google.gson.*;
import dev.bitwigagent.core.BoundedJson;
import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.HostModule;
import dev.bitwigagent.core.HostOperation;
import dev.bitwigagent.core.Params;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.core.Refs;
import dev.bitwigagent.core.RpcException;
import dev.bitwigagent.core.Scheduler;
import dev.bitwigagent.host.DeviceSources;
import dev.bitwigagent.host.NativeRuntime;
import dev.bitwigagent.host.TransportSafety;
import dev.bitwigagent.nativehost.NativeGridAdapter;
import java.util.Set;
import java.util.UUID;

/** Strict RPC boundary and observed public-device targeting; native operations use a separate adapter. */
public final class GridModule implements HostModule {
    static final Set<String> METHODS = Set.of(
            "grid.catalog",
            "grid.get",
            "grid.parameters.set",
            "grid.modules.move",
            "grid.modules.delete",
            "grid.modules.insert",
            "grid.modules.set",
            "grid.connections.set",
            "grid.voicing.get",
            "grid.voicing.set",
            "devices.remote.define",
            "devices.remote.delete");
    private final Scheduler scheduler;
    private final DeviceSources sources;
    private final TransportSafety transportSafety;
    private final NativeRuntime natives;
    private GridAdapter adapter;

    public GridModule(
            Scheduler scheduler, DeviceSources sources, TransportSafety transportSafety, NativeRuntime natives) {
        this(scheduler, sources, transportSafety, natives, null);
    }

    public GridModule(
            Scheduler scheduler,
            DeviceSources sources,
            TransportSafety transportSafety,
            NativeRuntime natives,
            GridAdapter adapter) {
        this.scheduler = scheduler;
        this.sources = sources;
        this.transportSafety = transportSafety;
        this.natives = natives;
        this.adapter = adapter;
    }

    @Override
    public Set<String> methods() {
        return METHODS;
    }

    @Override
    public void validate(String method, JsonObject params) {
        validateParams(method, params);
    }

    public static void validateParams(String method, JsonObject p) {
        switch (method) {
            case "grid.catalog":
                Params.fields(p, Set.of(), Set.of("query", "offset", "limit"));
                if (p.has("query")) {
                    if (!p.get("query").isJsonPrimitive()
                            || !p.get("query").getAsJsonPrimitive().isString())
                        throw Errors.notApplied(
                                Reason.INVALID_PARAMS, "query must be a string of at most 128 UTF-16 units.");
                    String query = p.get("query").getAsString();
                    if (query.length() > 128)
                        throw Errors.notApplied(
                                Reason.INVALID_PARAMS, "query must be a string of at most 128 UTF-16 units.");
                }
                if (p.has("offset")) Params.integer(p, "offset", 0, 100000);
                if (p.has("limit")) Params.integer(p, "limit", 1, 256);
                return;
            case "grid.connections.set":
                if (p != null && p.has("connections")) {
                    Params.fields(p, Set.of("target", "connections"), Set.of("graph"));
                    graphMode(p);
                    deviceRef(p);
                    GridConnectionWriter.Request.parseBatch(p);
                    return;
                }
                Set<String> cable = Set.of("target", "targetModuleId", "targetPortKey", "expectedSource", "source");
                if (p == null || !p.keySet().containsAll(cable))
                    throw Errors.notApplied(
                            Reason.INVALID_PARAMS,
                            "Connection edits require target, targetModuleId, targetPortKey, expectedSource and source (optional graph); source fields must be explicitly nullable.");
                for (String key : p.keySet())
                    if (!key.equals("graph") && !cable.contains(key))
                        throw Errors.notApplied(Reason.INVALID_PARAMS, "Unknown parameter: " + key);
                graphMode(p);
                deviceRef(p);
                GridConnectionWriter.Request.parse(p);
                return;
            case "grid.modules.insert":
                boolean many = p != null && p.has("modules");
                Params.fields(
                        p,
                        many ? Set.of("target", "modules") : Set.of("target", "x", "y"),
                        many ? Set.of("graph") : Set.of("graph", "typeId", "typeName"));
                graphMode(p);
                deviceRef(p);
                if (many) GridInsertionWriter.Request.parseBatch(p);
                else GridInsertionWriter.Request.parse(p);
                return;
            case "grid.modules.set":
                Params.fields(p, Set.of("target", "moduleId", "expectedCustomName", "customName"), Set.of("graph"));
                graphMode(p);
                deviceRef(p);
                canonicalUuid(p, "moduleId");
                GridModuleNameWriter.Request.parse(p);
                return;
            case "grid.modules.move":
            case "grid.modules.delete":
                boolean moving = method.equals("grid.modules.move");
                Params.fields(
                        p,
                        moving
                                ? Set.of("target", "moduleId", "expectedX", "expectedY", "x", "y")
                                : Set.of("target", "moduleId"),
                        Set.of("graph"));
                graphMode(p);
                deviceRef(p);
                canonicalUuid(p, "moduleId");
                if (moving) GridModuleWriter.Move.parse(p);
                return;
            case "grid.voicing.get":
                Params.fields(p, Set.of("target"), Set.of("detail"));
                if (p.has("detail")
                        && !(p.get("detail").isJsonPrimitive()
                                && p.get("detail").getAsJsonPrimitive().isString()
                                && Set.of("summary", "full")
                                        .contains(p.get("detail").getAsString())))
                    throw Errors.notApplied(Reason.INVALID_PARAMS, "detail must be \"summary\" or \"full\".");
                deviceRef(p);
                return;
            case "devices.remote.define":
                Params.fields(p, Set.of("target", "name", "slots"), Set.of());
                deviceRef(p);
                GridRemotePageWriter.Request.parse(p);
                return;
            case "devices.remote.delete":
                Params.fields(p, Set.of("target"), Set.of());
                GridRemotePageWriter.Request.page(p);
                return;
            case "grid.voicing.set":
                Params.fields(p, Set.of("target", "expected", "set"), Set.of());
                deviceRef(p);
                GridVoicingWriter.Request.parse(p);
                return;
            case "grid.parameters.set":
                if (p.has("writes")) batch(p);
                else GridParameterWriter.Request.parse(p);
                return;
            case "grid.get":
                Params.fields(
                        p,
                        Set.of("target"),
                        Set.of("moduleOffset", "moduleLimit", "includeParameters", "diagnostics", "moduleIds", "full"));
                deviceRef(p);
                if (p.has("full")) Params.bool(p, "full");
                if (p.has("moduleOffset")) Params.integer(p, "moduleOffset", 0, 100000);
                if (p.has("moduleLimit")) Params.integer(p, "moduleLimit", 1, 256);
                if (p.has("includeParameters")) Params.bool(p, "includeParameters");
                if (p.has("diagnostics")) Params.bool(p, "diagnostics");
                if (p.has("moduleIds")) moduleIds(p);
                return;
            default:
                throw Errors.notApplied(Reason.UNKNOWN_METHOD, "Unknown Grid operation.");
        }
    }

    /** The target of a Grid method is a device ref; the schema has already checked its shape. */
    static JsonObject deviceRef(JsonObject p) {
        JsonElement target = p.get("target");
        if (target == null || !target.isJsonObject() || !"device".equals(Refs.kind(target.getAsJsonObject())))
            throw Errors.invalid("target must be a device ref.");
        return target.getAsJsonObject();
    }

    @Override
    public void status(HostOperation operation, JsonObject out, boolean full) {
        if (!full) return;
        if (adapter == null)
            adapter = new NativeGridAdapter(natives.get(operation).access());
        out.add("grid", adapter.capabilities());
    }

    private static boolean graphMode(JsonObject p) {
        if (!p.has("graph")) return false;
        JsonElement value = p.get("graph");
        if (!value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isString()
                || !(value.getAsString().equals("diff") || value.getAsString().equals("full")))
            throw Errors.notApplied(Reason.INVALID_PARAMS, "graph must be \"diff\" or \"full\".");
        return value.getAsString().equals("full");
    }

    private static Set<String> moduleIds(JsonObject p) {
        JsonElement value = p.get("moduleIds");
        if (!value.isJsonArray()
                || value.getAsJsonArray().size() < 1
                || value.getAsJsonArray().size() > 256)
            throw Errors.notApplied(Reason.INVALID_PARAMS, "moduleIds must be an array of 1..256 canonical UUIDs.");
        Set<String> ids = new java.util.HashSet<>();
        for (JsonElement item : value.getAsJsonArray()) {
            if (!item.isJsonPrimitive()
                    || !item.getAsJsonPrimitive().isString()
                    || !item.getAsString().matches(UUID_PATTERN))
                throw Errors.notApplied(Reason.INVALID_PARAMS, "moduleIds must be an array of 1..256 canonical UUIDs.");
            ids.add(item.getAsString().toLowerCase(java.util.Locale.ROOT));
        }
        return ids;
    }

    private static final String UUID_PATTERN =
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";

    private static java.util.List<GridParameterWriter.Entry> batch(JsonObject p) {
        java.util.List<GridParameterWriter.Entry> entries = new java.util.ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        for (JsonElement element : p.getAsJsonArray("writes")) {
            JsonObject w = element.getAsJsonObject();
            UUID module = UUID.fromString(w.get("moduleId").getAsString());
            String parameter = w.get("parameterId").getAsString();
            if (!seen.add(module + "/" + parameter))
                throw Errors.notApplied(Reason.INVALID_PARAMS, "writes must not repeat a moduleId/parameterId pair.");
            entries.add(new GridParameterWriter.Entry(module, parameter, GridParameterWriter.Request.parse(w)));
        }
        return entries;
    }

    @Override
    public void dispatch(HostOperation op, String method, JsonObject p) {
        scheduler.check(op);
        if (adapter == null) adapter = new NativeGridAdapter(natives.get(op).access());
        final boolean moving = method.equals("grid.modules.move");
        final boolean connection = method.equals("grid.connections.set");
        final boolean inserting = method.equals("grid.modules.insert");
        final boolean renaming = method.equals("grid.modules.set");
        final boolean moduleEditing = moving || method.equals("grid.modules.delete");
        final boolean topology = moduleEditing || connection || inserting || renaming;
        final boolean voicingGet = method.equals("grid.voicing.get"), voicingSet = method.equals("grid.voicing.set");
        final boolean remoteDefine = method.equals("devices.remote.define"),
                remoteDelete = method.equals("devices.remote.delete");
        final boolean remotePages = remoteDefine || remoteDelete;
        final boolean scalarWriting = method.equals("grid.parameters.set");
        final boolean batching = scalarWriting && p.has("writes");
        final boolean fullGraph = topology && graphMode(p);
        final java.util.List<GridParameterWriter.Entry> entries = batching ? batch(p) : null;
        final boolean writing = scalarWriting || topology || voicingSet || remotePages;
        final GridParameterWriter.Request request =
                scalarWriting && !batching ? GridParameterWriter.Request.parse(p) : null;
        final GridModuleWriter.Move move = moving ? GridModuleWriter.Move.parse(p) : null;
        final boolean cableBatch = connection && p.has("connections");
        final java.util.List<GridConnectionWriter.Request> cables =
                cableBatch ? GridConnectionWriter.Request.parseBatch(p) : null;
        final GridConnectionWriter.Request cable =
                connection && !cableBatch ? GridConnectionWriter.Request.parse(p) : null;
        final boolean insertBatch = inserting && p.has("modules");
        final java.util.List<GridInsertionWriter.Request> insertions =
                insertBatch ? GridInsertionWriter.Request.parseBatch(p) : null;
        final GridInsertionWriter.Request insertion =
                inserting && !insertBatch ? GridInsertionWriter.Request.parse(p) : null;
        final GridModuleNameWriter.Request name = renaming ? GridModuleNameWriter.Request.parse(p) : null;
        final GridVoicingWriter.Request voicing = voicingSet ? GridVoicingWriter.Request.parse(p) : null;
        final GridRemotePageWriter.Request remotePage = remoteDefine ? GridRemotePageWriter.Request.parse(p) : null;
        final JsonObject deletedPage = remoteDelete ? GridRemotePageWriter.Request.page(p) : null;
        final boolean fullSteps =
                p.has("full") && !scalarWriting && p.get("full").getAsBoolean();
        final Runnable safety = writing ? transportSafety.writeGuard(op, topology) : null;
        final DeviceSources.RootReadTicket target =
                method.equals("grid.get") || scalarWriting || topology || voicingGet || voicingSet || remotePages
                        ? target(p)
                        : null;
        final UUID moduleId = (scalarWriting && !batching) || moduleEditing || renaming
                ? UUID.fromString(p.get("moduleId").getAsString())
                : null;
        final String parameterId =
                scalarWriting && !batching ? p.get("parameterId").getAsString() : null;
        final int offset = integer(p, target == null ? "offset" : "moduleOffset", 0);
        final int limit = integer(p, target == null ? "limit" : "moduleLimit", 64);
        final boolean parameters =
                !p.has("includeParameters") || p.get("includeParameters").getAsBoolean();
        final boolean diagnostics = p.has("diagnostics") && p.get("diagnostics").getAsBoolean();
        final Set<String> moduleFilter = p.has("moduleIds") ? moduleIds(p) : null;
        final String query = p.has("query") ? p.get("query").getAsString() : "";
        guard(op, target);
        adapter.execute(() -> {
            try {
                Runnable check = () -> {
                    scheduler.check(op);
                    if (target != null) target.check();
                    if (writing) safety.run();
                };
                check.run();
                if (topology) {
                    java.util.function.Consumer<JsonObject> complete =
                            value -> deliver(op, target, safety, true, check, GridDiff.shape(value, fullGraph));
                    Runnable dispatch = () -> {
                        check.run();
                        op.markDispatched();
                    };
                    if (insertBatch)
                        adapter.insertModules(
                                target.source,
                                insertions,
                                () -> op.deadline - scheduler.now(),
                                check,
                                dispatch,
                                complete,
                                op::fail);
                    else if (inserting)
                        adapter.insertModule(target.source, insertion, check, dispatch, complete, op::fail);
                    else if (renaming)
                        adapter.renameModule(target.source, moduleId, name, check, dispatch, complete, op::fail);
                    else if (cableBatch)
                        adapter.setConnections(target.source, cables, check, dispatch, complete, op::fail);
                    else if (connection)
                        adapter.setConnection(target.source, cable, check, dispatch, complete, op::fail);
                    else if (moving)
                        adapter.moveModule(target.source, moduleId, move, check, dispatch, complete, op::fail);
                    else adapter.deleteModule(target.source, moduleId, check, dispatch, complete, op::fail);
                    return;
                }
                JsonObject value = target == null
                        ? adapter.catalog(query, offset, limit, check)
                        : voicingGet
                                ? adapter.voicing(target.source, check)
                                : voicingSet
                                        ? adapter.setVoicing(target.source, voicing, check, () -> {
                                            check.run();
                                            op.markDispatched();
                                        })
                                        : remoteDefine
                                                ? adapter.defineRemotePage(target.source, remotePage, check, () -> {
                                                    check.run();
                                                    op.markDispatched();
                                                })
                                                : remoteDelete
                                                        ? adapter.deleteRemotePage(
                                                                target.source, deletedPage, check, () -> {
                                                                    check.run();
                                                                    op.markDispatched();
                                                                })
                                                        : batching
                                                                ? adapter.setParameters(
                                                                        target.source, entries, check, () -> {
                                                                            check.run();
                                                                            op.markDispatched();
                                                                        })
                                                                : writing
                                                                        ? GridDiff.shape(
                                                                                adapter.setParameter(
                                                                                        target.source,
                                                                                        moduleId,
                                                                                        parameterId,
                                                                                        request,
                                                                                        check,
                                                                                        () -> {
                                                                                            check.run();
                                                                                            op.markDispatched();
                                                                                        }),
                                                                                true)
                                                                        : adapter.snapshot(
                                                                                target.source,
                                                                                offset,
                                                                                limit,
                                                                                parameters,
                                                                                diagnostics,
                                                                                moduleFilter,
                                                                                fullSteps,
                                                                                check);
                if (voicingGet
                        && !(p.has("detail") && p.get("detail").getAsString().equals("full")))
                    value.remove("editContext");
                deliver(op, target, safety, writing, check, value);
            } catch (RuntimeException error) {
                op.fail(
                        error instanceof RpcException
                                ? error
                                : Errors.notApplied(
                                        Reason.NATIVE_UNAVAILABLE,
                                        "Grid operation failed: "
                                                + error.getClass().getSimpleName()
                                                + "; inspect the exact host binding."));
            } catch (Error error) {
                op.fail(Errors.notApplied(
                        Reason.NATIVE_UNAVAILABLE,
                        "Native Grid operation failed; inspect the loaded host binding and current state."));
            }
        });
    }

    /** Releases the topology hold of {@code device} (track UUID/graph identity) after today's consistency check. */
    public void ackTopology(HostOperation op, String device) {
        scheduler.check(op);
        String trackId = device.substring(0, device.indexOf('/'));
        if (adapter == null) adapter = new NativeGridAdapter(natives.get(op).access());
        java.util.List<DeviceSources.RootReadTicket> targets = sources.rootReads(trackId);
        adapter.execute(() -> {
            try {
                for (DeviceSources.RootReadTicket target : targets) {
                    Runnable check = () -> {
                        scheduler.check(op);
                        target.check();
                    };
                    check.run();
                    JsonObject value = adapter.ackTopology(target.source, device, check);
                    if (value != null) {
                        deliver(op, target, () -> {}, true, check, value);
                        return;
                    }
                }
                op.fail(Errors.notApplied(
                        Reason.UNKNOWN_HOLD,
                        "No observed Grid device on track " + trackId + " has this topology hold.",
                        "status lists the holds, including the held devices."));
            } catch (RuntimeException error) {
                op.fail(error);
            }
        });
    }

    private void deliver(
            HostOperation op,
            DeviceSources.RootReadTicket target,
            Runnable safety,
            boolean writing,
            Runnable check,
            JsonObject value) {
        check.run();
        // No native model, stream, or mutable JSON crosses the document-to-controller boundary.
        final String serialized = BoundedJson.serialize(
                value,
                240000,
                Reason.OUT_OF_BOUNDS,
                "Grid response exceeds bounded transport size; reduce moduleLimit or exclude parameters.");
        check.run();
        scheduler.schedule(
                op,
                () -> {
                    guard(op, target);
                    if (writing) safety.run();
                    JsonObject result = JsonParser.parseString(serialized).getAsJsonObject();
                    if (target != null) result.add("target", target.source.ref());
                    op.result.complete(result);
                },
                0);
    }

    /** The root device a ref names (target_not_found or target_changed otherwise); a Grid device inside a chain is not read. */
    private DeviceSources.RootReadTicket target(JsonObject p) {
        JsonObject ref = deviceOf(p);
        if (ref.has("chain"))
            throw Errors.notApplied(Reason.UNSUPPORTED_SCOPE, "Grid devices inside a chain are not addressed.");
        return sources.rootRead(sources.device(ref, true));
    }

    /** The device ref a request addresses: its target, or the device of the Remote Controls page it deletes. */
    private static JsonObject deviceOf(JsonObject p) {
        JsonObject target = p.getAsJsonObject("target");
        return Refs.kind(target).equals("remotePage") ? target.getAsJsonObject("device") : target;
    }

    private void guard(HostOperation op, DeviceSources.RootReadTicket target) {
        scheduler.check(op);
        if (target != null) sources.guardRootRead(target);
    }

    static void canonicalUuid(JsonObject p, String key) {
        if (!Params.string(p, key)
                .matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
            throw Errors.notApplied(Reason.INVALID_PARAMS, key + " must be a canonical UUID.");
    }

    private static int integer(JsonObject p, String key, int fallback) {
        return p.has(key) ? p.get(key).getAsInt() : fallback;
    }
}
