package dev.bitwigagent;

import static dev.bitwigagent.GridSnapshotReader.Field.*;

import com.google.gson.*;
import java.util.*;
import java.util.function.Consumer;

/** One input source CAS, with exact membership and later actual graph observation, never compensation. */
final class GridConnectionWriter {
    static final int MAX_BATCH = 32;

    interface Access {
        void requireSupported(Object input, Object undo);

        Runnable prepareSet(Object input, Object source);

        void execute(Runnable observation);
    }

    private final GridSnapshotReader snapshots;
    private final GridSnapshotReader.Access values;
    private final GridTopologyContext contexts;
    private final Access nativeWriter;
    private final GridTopologyQuarantine quarantine;

    GridConnectionWriter(
            GridSnapshotReader snapshots,
            GridSnapshotReader.Access values,
            GridTopologyContext contexts,
            Access nativeWriter,
            GridTopologyQuarantine quarantine) {
        this.snapshots = snapshots;
        this.values = values;
        this.contexts = contexts;
        this.nativeWriter = nativeWriter;
        this.quarantine = quarantine;
    }

    void set(
            DeviceSources.Source source,
            Request request,
            Runnable check,
            Runnable dispatch,
            Consumer<JsonObject> complete,
            Consumer<Throwable> fail) {
        set(source, request, check, dispatch, complete, fail, true);
    }

    private void set(
            DeviceSources.Source deviceSource,
            Request request,
            Runnable check,
            Runnable dispatch,
            Consumer<JsonObject> complete,
            Consumer<Throwable> fail,
            boolean withBefore) {
        values.documentThread();
        check.run();
        GridSnapshotReader.NativeTarget target = snapshots.target(deviceSource);
        String device = GridTopologyQuarantine.device(target.ownership.trackId, target.graph);
        quarantine.requireAvailable(device);
        // The batch keeps one graphBefore for the whole call; per-entry snapshots would be discarded.
        JsonObject graphBefore = withBefore
                ? snapshots.snapshot(deviceSource, 0, GridTopologyContext.MAX_MODULES, false, false, null, check)
                : null;
        Graph before = capture(target.graph, check);
        Port input = before.require(request.target, true);
        Object source = request.source == null ? null : before.require(request.source, false).model;
        request.requireExpected(input.sourceEndpoint);
        GridTopologyContext.Capture context =
                contexts.capture(target.target, target.graph, before.members, null, check);
        nativeWriter.requireSupported(input.model, context.undo());
        boolean changed = input.source != source;
        Runnable mutation = changed ? nativeWriter.prepareSet(input.model, source) : null;
        snapshots.guardTarget(target);
        Graph ready = capture(target.graph, check);
        before.same(ready, true);
        request.requireExpected(ready.requireSame(request.target, true).sourceEndpoint);
        contexts.capture(target.target, target.graph, ready.members, context, check);
        nativeWriter.requireSupported(input.model, context.undo());
        snapshots.guardTarget(target);
        if (values.read(INPUT_SOURCE, input.model) != input.source)
            throw Errors.notApplied(
                    Reason.TARGET_CHANGED,
                    "Native input source changed immediately before dispatch; reacquire grid.export.");
        check.run();
        quarantine.requireAvailable(device);
        if (changed) {
            dispatch.run();
            quarantine.invoke(device, mutation);
        }
        // Setter return is intermediate: normal local/project repair drains before this ordinary later event.
        nativeWriter.execute(() -> {
            try {
                values.documentThread();
                check.run();
                quarantine.requireAvailable(device);
                snapshots.guardTarget(target);
                Graph after = capture(target.graph, check);
                before.same(after, !changed);
                contexts.capture(target.target, target.graph, after.members, context, check);
                Port actual = after.requireSame(request.target, true);
                nativeWriter.requireSupported(actual.model, context.undo());
                JsonObject graph =
                        snapshots.snapshot(deviceSource, 0, GridTopologyContext.MAX_MODULES, false, false, null, check);
                after.same(capture(target.graph, check), true);
                snapshots.guardTarget(target);
                contexts.capture(target.target, target.graph, after.members, context, check);
                check.run();
                quarantine.requireAvailable(device);
                // Native repair may leave another source after a dispatched write: fail closed.
                boolean matches = Objects.equals(request.source, actual.sourceEndpoint);
                JsonObject result = Results.outcome(
                        !matches ? Outcome.UNKNOWN : changed ? Outcome.APPLIED : Outcome.ALREADY_MATCHED);
                if (!matches) result.addProperty("reason", Reason.READBACK_MISMATCH.wire());
                result.addProperty("targetModuleId", request.target.moduleId.toString());
                result.addProperty("targetPortKey", request.target.portKey);
                result.add("previousSource", Endpoint.json(input.sourceEndpoint));
                result.add("requestedSource", Endpoint.json(request.source));
                result.add("actualSource", Endpoint.json(actual.sourceEndpoint));
                result.add("graph", graph);
                if (graphBefore != null) result.add("graphBefore", graphBefore);
                result.addProperty("mutationDispatched", changed);
                result.addProperty("nativeMutation", changed);
                result.addProperty("nativeUndo", changed);
                result.addProperty("verification", "later_document_event_bounded_native_graph_observation");
                result.addProperty(
                        "nativeUndoScope",
                        changed ? "native_operation_not_verified_grouping_or_commit" : "no_native_operation");
                result.addProperty("undoGroupingVerified", false);
                result.addProperty("engineAcknowledgement", false);
                result.addProperty(
                        "safetyScope", "observed_stopped_nonrecording_nonautomation_not_native_transport_atomicity");
                result.addProperty(
                        "contextScope",
                        "same_project_root_default_Dt_idle_undo_lock_owner_observations_not_iqa_idle_or_atomic_permission");
                result.addProperty(
                        "graphScope",
                        "all_bounded_modules_ports_and_current_same_graph_sources_not_DSP_or_opaque_nested_children");
                result.addProperty(
                        "projectScope",
                        "native_repair_may_change_other_inputs_and_project_routes_not_certified_by_Grid_graph");
                result.addProperty(
                        "observationScope",
                        "later_ordinary_event_after_normal_end_event_drain_not_universal_cleanup_or_future_work_completion");
                result.addProperty("maximumModules", GridTopologyContext.MAX_MODULES);
                complete.accept(result);
            } catch (Throwable error) {
                fail.accept(error);
            }
        });
    }

    /** Live CAS, membership and support checks for every entry; nothing is dispatched. */
    private void preflight(DeviceSources.Source source, java.util.List<Request> requests, Runnable check) {
        values.documentThread();
        check.run();
        GridSnapshotReader.NativeTarget target = snapshots.target(source);
        String device = GridTopologyQuarantine.device(target.ownership.trackId, target.graph);
        quarantine.requireAvailable(device);
        Graph before = capture(target.graph, check);
        GridTopologyContext.Capture context =
                contexts.capture(target.target, target.graph, before.members, null, check);
        StringBuilder rejected = new StringBuilder();
        Reason code = null;
        int failures = 0;
        for (int i = 0; i < requests.size(); i++) {
            Request request = requests.get(i);
            try {
                Port input = before.require(request.target, true);
                if (request.source != null) before.require(request.source, false);
                request.requireExpected(input.sourceEndpoint);
                nativeWriter.requireSupported(input.model, context.undo());
            } catch (RpcException error) {
                if (code == null) code = error.reason;
                if (failures++ < 8)
                    rejected.append(rejected.length() == 0 ? "" : "; ")
                            .append('#')
                            .append(i)
                            .append(' ')
                            .append(error.reason.wire())
                            .append(": ")
                            .append(error.getMessage());
            }
        }
        if (failures > 0)
            throw Errors.notApplied(
                    code,
                    "No connection was written; " + failures + " of " + requests.size()
                            + " entries failed their checks: " + rejected + (failures > 8 ? "; ..." : ""));
        snapshots.guardTarget(target);
    }

    /**
     * Same-device batch: every expectedSource is checked against the live graph first (any failure writes nothing),
     * then entries run in order through the ordinary single-input path, stopping at the first failure or unknown.
     * One graph diff covers the batch; nothing is retried or rolled back.
     */
    void setAll(
            DeviceSources.Source source,
            java.util.List<Request> requests,
            Runnable check,
            Runnable dispatch,
            Consumer<JsonObject> complete,
            Consumer<Throwable> fail) {
        preflight(source, requests, check);
        JsonObject graphBefore =
                snapshots.snapshot(source, 0, GridTopologyContext.MAX_MODULES, false, false, null, check);
        new Batch(source, requests, graphBefore, check, dispatch, complete, fail).next(0);
    }

    private final class Batch {
        final DeviceSources.Source source;
        final java.util.List<Request> requests;
        final JsonObject graphBefore;
        final Runnable check, dispatch;
        final Consumer<JsonObject> complete;
        final Consumer<Throwable> fail;
        final JsonArray items = new JsonArray();
        JsonObject lastGraph;
        boolean stale;
        int written;

        Batch(
                DeviceSources.Source source,
                java.util.List<Request> requests,
                JsonObject graphBefore,
                Runnable check,
                Runnable dispatch,
                Consumer<JsonObject> complete,
                Consumer<Throwable> fail) {
            this.source = source;
            this.requests = requests;
            this.graphBefore = graphBefore;
            this.check = check;
            this.dispatch = dispatch;
            this.complete = complete;
            this.fail = fail;
        }

        void next(int i) {
            if (i == requests.size()) {
                finish();
                return;
            }
            Request request = requests.get(i);
            JsonObject item = new JsonObject();
            item.addProperty("targetModuleId", request.target.moduleId.toString());
            item.addProperty("targetPortKey", request.target.portKey);
            items.add(item);
            boolean[] sent = {false};
            try {
                set(
                        source,
                        request,
                        check,
                        () -> {
                            dispatch.run();
                            sent[0] = true;
                        },
                        one -> {
                            if (record(item, one)) next(i + 1);
                            else stop(i);
                        },
                        error -> failed(i, item, error, sent[0]),
                        false);
            } catch (Throwable error) {
                failed(i, item, error, sent[0]);
            }
        }
        /** False when native repair left another source: observed, but later entries may depend on it. */
        private boolean record(JsonObject item, JsonObject one) {
            for (String key : new String[] {"outcome", "reason", "previousSource", "actualSource"})
                if (one.has(key)) item.add(key, one.get(key));
            lastGraph = one.getAsJsonObject("graph");
            if (one.get("mutationDispatched").getAsBoolean()) written++;
            return Results.outcomeOf(one).succeeded();
        }

        private void stop(int i) {
            for (int k = i + 1; k < requests.size(); k++) {
                Request later = requests.get(k);
                JsonObject rest = new JsonObject();
                rest.addProperty("targetModuleId", later.target.moduleId.toString());
                rest.addProperty("targetPortKey", later.target.portKey);
                GridDiff.notApplied(rest, Reason.NOT_REACHED);
                items.add(rest);
            }
            finish();
        }

        private void failed(int i, JsonObject item, Throwable error, boolean dispatched) {
            boolean unknown = dispatched || error instanceof RpcException && ((RpcException) error).unknown;
            if (written == 0 && !unknown) {
                fail.accept(error);
                return;
            }
            GridDiff.failed(item, error, unknown);
            stale = unknown;
            stop(i);
        }

        private void finish() {
            try {
                JsonObject result = Results.outcome(Results.worst(items));
                result.addProperty("written", written);
                result.add("connections", items);
                result.addProperty("mutationDispatched", written > 0 || stale);
                result.addProperty("nativeMutation", written > 0 || stale);
                result.addProperty("nativeUndo", written > 0 || stale);
                result.addProperty("verification", "later_document_event_bounded_native_graph_observation_per_entry");
                // After a failed dispatch the last observed graph is not the current one: no diff is offered.
                if (lastGraph != null && !stale) {
                    result.add("graph", lastGraph);
                    result.add("graphBefore", graphBefore);
                }
                complete.accept(result);
            } catch (Throwable error) {
                fail.accept(error);
            }
        }
    }

    private Graph capture(Object graph, Runnable check) {
        GridSnapshotReader.State state = new GridSnapshotReader.State(check);
        Graph result = new Graph();
        for (Object module : snapshots.topologyModules(graph, state)) {
            UUID id = (UUID) values.read(INSTANCE_ID, module);
            result.modules.put(id, module);
            result.members.add(module);
            ports(result, module, id, true, state);
            ports(result, module, id, false, state);
        }
        for (Port input : result.inputs.values()) {
            state.check();
            input.source = values.read(INPUT_SOURCE, input.model);
            if (input.source == null) continue;
            Port source = result.outputsByIdentity.get(input.source);
            // An xb1-like object with the same owner/key is not membership (pS excludes qXr).
            if (source == null)
                throw Errors.notApplied(
                        Reason.TARGET_CHANGED,
                        "Current native source is not an exact enumerated output of this bounded graph.");
            input.sourceEndpoint = source.endpoint;
        }
        return result;
    }

    private void ports(Graph graph, Object module, UUID id, boolean input, GridSnapshotReader.State state) {
        Map<Endpoint, Port> ports = input ? graph.inputs : graph.outputs;
        for (Object model :
                snapshots.collect(input ? INPUTS : OUTPUTS, module, GridSnapshotReader.MAX_CHILDREN, state)) {
            state.check();
            if (!values.is(input ? GridSnapshotReader.Kind.INPUT : GridSnapshotReader.Kind.OUTPUT, model))
                throw Errors.notApplied(
                        Reason.NATIVE_UNAVAILABLE,
                        "Connection editing requires every enumerated port to have its mapped direction.");
            values.requireModelThread(model);
            if (values.read(OWNER_MODULE, model) != module)
                throw Errors.notApplied(
                        Reason.TARGET_CHANGED,
                        "Native port owner differs from its direction-specific module membership.");
            Endpoint endpoint =
                    new Endpoint(id, GridSnapshotReader.text(values.read(input ? INPUT_KEY : OUTPUT_KEY, model)));
            Port port = new Port(endpoint, model);
            if (ports.put(endpoint, port) != null || !graph.portIdentities.add(model))
                throw Errors.notApplied(
                        Reason.NATIVE_UNAVAILABLE, "Duplicate native direction-specific port key or port identity.");
            graph.members.add(model);
            if (!input) graph.outputsByIdentity.put(model, port);
        }
    }

    private static final class Port {
        final Endpoint endpoint;
        final Object model;
        Object source;
        Endpoint sourceEndpoint;

        Port(Endpoint endpoint, Object model) {
            this.endpoint = endpoint;
            this.model = model;
        }
    }

    private static final class Graph {
        final Map<UUID, Object> modules = new HashMap<>();
        final Map<Endpoint, Port> inputs = new HashMap<>(), outputs = new HashMap<>();
        final IdentityHashMap<Object, Port> outputsByIdentity = new IdentityHashMap<>();
        final Set<Object> portIdentities = Collections.newSetFromMap(new IdentityHashMap<>());
        final List<Object> members = new ArrayList<>();

        Port require(Endpoint endpoint, boolean input) {
            Port port = (input ? inputs : outputs).get(endpoint);
            if (port == null)
                throw Errors.notApplied(
                        Reason.TARGET_NOT_FOUND,
                        "Requested exact native " + (input ? "input" : "output")
                                + " is absent; reacquire grid.export. Matching owner/key without stream membership is insufficient.",
                        "grid.export");
            return port;
        }

        /** A port the write found before dispatch that a later capture no longer has. */
        Port requireSame(Endpoint endpoint, boolean input) {
            Port port = (input ? inputs : outputs).get(endpoint);
            if (port == null)
                throw Errors.notApplied(
                        Reason.TARGET_CHANGED, "A requested native port left the graph during the write.");
            return port;
        }

        void same(Graph after, boolean sources) {
            if (modules.size() != after.modules.size()
                    || inputs.size() != after.inputs.size()
                    || outputs.size() != after.outputs.size())
                throw Errors.notApplied(
                        Reason.TARGET_CHANGED,
                        "Native graph or port membership changed during the connection operation.");
            for (Map.Entry<UUID, Object> module : modules.entrySet())
                if (after.modules.get(module.getKey()) != module.getValue())
                    throw Errors.notApplied(
                            Reason.TARGET_CHANGED, "Native module identity changed during the connection operation.");
            samePorts(inputs, after.inputs, sources);
            samePorts(outputs, after.outputs, false);
        }

        private static void samePorts(Map<Endpoint, Port> before, Map<Endpoint, Port> after, boolean sources) {
            for (Port port : before.values()) {
                Port actual = after.get(port.endpoint);
                if (actual == null || actual.model != port.model || (sources && actual.source != port.source))
                    throw Errors.notApplied(
                            Reason.TARGET_CHANGED,
                            "Native port identity or captured source changed during the connection observation.");
            }
        }
    }

    static final class Request {
        final Endpoint target, expectedSource, source;

        Request(Endpoint target, Endpoint expectedSource, Endpoint source) {
            this.target = target;
            this.expectedSource = expectedSource;
            this.source = source;
        }

        static java.util.List<Request> parseBatch(JsonObject p) {
            JsonElement value = p.get("connections");
            if (!value.isJsonArray()
                    || value.getAsJsonArray().size() < 1
                    || value.getAsJsonArray().size() > MAX_BATCH)
                throw Errors.notApplied(
                        Reason.INVALID_PARAMS,
                        "connections must be an array of 1.." + MAX_BATCH + " connection edits.");
            java.util.List<Request> result = new ArrayList<>();
            Set<Endpoint> seen = new HashSet<>();
            for (JsonElement element : value.getAsJsonArray()) {
                if (!element.isJsonObject())
                    throw Errors.notApplied(Reason.INVALID_PARAMS, "Each connection must be an object.");
                JsonObject entry = element.getAsJsonObject();
                // Source fields are explicitly nullable, so the generic null-rejecting field check does not apply.
                if (!entry.keySet().equals(Set.of("targetModuleId", "targetPortKey", "expectedSource", "source")))
                    throw Errors.notApplied(
                            Reason.INVALID_PARAMS,
                            "Each connection requires exactly targetModuleId, targetPortKey, expectedSource and source.");
                Request request = parse(entry);
                if (!seen.add(request.target))
                    throw Errors.notApplied(
                            Reason.INVALID_PARAMS, "connections must not repeat a targetModuleId/targetPortKey pair.");
                result.add(request);
            }
            return result;
        }

        static Request parse(JsonObject p) {
            return new Request(
                    new Endpoint(uuid(p, "targetModuleId"), key(p, "targetPortKey")),
                    endpoint(p, "expectedSource"),
                    endpoint(p, "source"));
        }

        void requireExpected(Endpoint actual) {
            if (!Objects.equals(expectedSource, actual))
                throw Errors.notApplied(
                        Reason.TARGET_CHANGED,
                        "Exact native input source differs from expectedSource; reacquire grid.export before editing.");
        }

        private static Endpoint endpoint(JsonObject p, String name) {
            JsonElement value = p.get(name);
            if (value == null)
                throw Errors.notApplied(
                        Reason.INVALID_PARAMS, name + " is required, including explicit null for disconnected.");
            if (value.isJsonNull()) return null;
            if (!value.isJsonObject())
                throw Errors.notApplied(
                        Reason.INVALID_PARAMS, name + " must be null or an exact moduleId/portKey object.");
            JsonObject endpoint = value.getAsJsonObject();
            Params.fields(endpoint, Set.of("moduleId", "portKey"), Set.of());
            return new Endpoint(uuid(endpoint, "moduleId"), key(endpoint, "portKey"));
        }

        private static UUID uuid(JsonObject p, String name) {
            GridModule.canonicalUuid(p, name);
            return UUID.fromString(p.get(name).getAsString());
        }

        private static String key(JsonObject p, String name) {
            JsonElement value = p.get(name);
            if (value == null
                    || !value.isJsonPrimitive()
                    || !value.getAsJsonPrimitive().isString()
                    || value.getAsString().length() > GridSnapshotReader.MAX_TEXT)
                throw Errors.notApplied(
                        Reason.INVALID_PARAMS, name + " must be a string of at most 1024 UTF-16 units.");
            return value.getAsString();
        }
    }

    static final class Endpoint {
        final UUID moduleId;
        final String portKey;

        Endpoint(UUID moduleId, String portKey) {
            this.moduleId = moduleId;
            this.portKey = portKey;
        }

        static JsonElement json(Endpoint endpoint) {
            if (endpoint == null) return JsonNull.INSTANCE;
            JsonObject result = new JsonObject();
            result.addProperty("moduleId", endpoint.moduleId.toString());
            result.addProperty("portKey", endpoint.portKey);
            return result;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Endpoint)) return false;
            Endpoint endpoint = (Endpoint) other;
            return moduleId.equals(endpoint.moduleId) && portKey.equals(endpoint.portKey);
        }

        @Override
        public int hashCode() {
            return 31 * moduleId.hashCode() + portKey.hashCode();
        }
    }
}
