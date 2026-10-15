package dev.bitwigagent;

import com.google.gson.*;
import java.util.*;
import java.util.function.Consumer;
import static dev.bitwigagent.GridSnapshotReader.Field.*;
import static dev.bitwigagent.HostServices.failure;

/** One input source CAS, with exact membership and later actual graph observation, never compensation. */
final class GridConnectionWriter {
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

    GridConnectionWriter(GridSnapshotReader snapshots, GridSnapshotReader.Access values,
            GridTopologyContext contexts, Access nativeWriter, GridTopologyQuarantine quarantine) {
        this.snapshots = snapshots; this.values = values; this.contexts = contexts;
        this.nativeWriter = nativeWriter; this.quarantine = quarantine;
    }
    void set(Object proxy, UUID trackId, Request request, Runnable check, Runnable dispatch,
            Consumer<JsonObject> complete, Consumer<Throwable> fail) {
        quarantine.requireAvailable(); values.documentThread(); check.run();
        GridSnapshotReader.NativeTarget target = snapshots.target(proxy, trackId);
        Graph before = capture(target.graph, check);
        Port input = before.require(request.target, true);
        Object source = request.source == null ? null : before.require(request.source, false).model;
        request.requireExpected(input.sourceEndpoint);
        GridTopologyContext.Capture context = contexts.capture(target.target, target.graph, before.members, null, check);
        nativeWriter.requireSupported(input.model, context.undo());
        boolean changed = input.source != source;
        Runnable mutation = changed ? nativeWriter.prepareSet(input.model, source) : null;
        snapshots.guardTarget(target, proxy, trackId);
        Graph ready = capture(target.graph, check);
        before.same(ready, true);
        request.requireExpected(ready.require(request.target, true).sourceEndpoint);
        contexts.capture(target.target, target.graph, ready.members, context, check);
        nativeWriter.requireSupported(input.model, context.undo());
        snapshots.guardTarget(target, proxy, trackId);
        if (values.read(INPUT_SOURCE, input.model) != input.source)
            throw failure("stale_context", "Native input source changed immediately before dispatch; reacquire grid.get.");
        check.run(); quarantine.requireAvailable();
        if (changed) {
            dispatch.run();
            quarantine.invoke(mutation);
        }
        // Setter return is intermediate: normal local/project repair drains before this ordinary later event.
        nativeWriter.execute(() -> {
            try {
                values.documentThread(); check.run(); quarantine.requireAvailable();
                snapshots.guardTarget(target, proxy, trackId);
                Graph after = capture(target.graph, check);
                before.same(after, !changed);
                contexts.capture(target.target, target.graph, after.members, context, check);
                Port actual = after.require(request.target, true);
                nativeWriter.requireSupported(actual.model, context.undo());
                JsonObject graph = snapshots.snapshot(proxy, trackId, 0, GridTopologyContext.MAX_MODULES, false, check);
                after.same(capture(target.graph, check), true);
                snapshots.guardTarget(target, proxy, trackId);
                contexts.capture(target.target, target.graph, after.members, context, check);
                check.run(); quarantine.requireAvailable();
                JsonObject result = HostServices.observed();
                result.addProperty("targetModuleId", request.target.moduleId.toString());
                result.addProperty("targetPortKey", request.target.portKey);
                result.add("previousSource", Endpoint.json(input.sourceEndpoint));
                result.add("requestedSource", Endpoint.json(request.source));
                result.add("actualSource", Endpoint.json(actual.sourceEndpoint));
                result.addProperty("matchesRequested", Objects.equals(request.source, actual.sourceEndpoint));
                result.add("graph", graph);
                result.addProperty("mutationDispatched", changed);
                result.addProperty("nativeMutation", changed); result.addProperty("nativeUndo", changed);
                result.addProperty("verification", changed ? "later_document_event_bounded_native_graph_observation" : "already_matched");
                result.addProperty("nativeUndoScope", changed ? "native_operation_not_verified_grouping_or_commit" : "no_native_operation");
                result.addProperty("undoGroupingVerified", false); result.addProperty("engineAcknowledgement", false);
                result.addProperty("safetyScope", "observed_stopped_nonrecording_nonautomation_not_native_transport_atomicity");
                result.addProperty("contextScope", "same_project_root_default_Dt_idle_undo_lock_owner_observations_not_iqa_idle_or_atomic_permission");
                result.addProperty("graphScope", "all_bounded_modules_ports_and_current_same_graph_sources_not_DSP_or_opaque_nested_children");
                result.addProperty("projectScope", "native_repair_may_change_other_inputs_and_project_routes_not_certified_by_Grid_graph");
                result.addProperty("observationScope", "later_ordinary_event_after_normal_end_event_drain_not_universal_cleanup_or_future_work_completion");
                result.addProperty("maximumModules", GridTopologyContext.MAX_MODULES);
                complete.accept(result);
            } catch (Throwable error) { fail.accept(error); }
        });
    }

    private Graph capture(Object graph, Runnable check) {
        GridSnapshotReader.State state = new GridSnapshotReader.State(check);
        Graph result = new Graph();
        for (Object module : snapshots.topologyModules(graph, state)) {
            UUID id = (UUID) values.read(INSTANCE_ID, module);
            result.modules.put(id, module); result.members.add(module);
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
                throw failure("stale_context", "Current native source is not an exact enumerated output of this bounded graph.");
            input.sourceEndpoint = source.endpoint;
        }
        return result;
    }
    private void ports(Graph graph, Object module, UUID id, boolean input, GridSnapshotReader.State state) {
        Map<Endpoint, Port> ports = input ? graph.inputs : graph.outputs;
        for (Object model : snapshots.collect(input ? INPUTS : OUTPUTS, module, GridSnapshotReader.MAX_CHILDREN, state)) {
            state.check();
            if (!values.is(input ? GridSnapshotReader.Kind.INPUT : GridSnapshotReader.Kind.OUTPUT, model))
                throw failure("grid_unavailable", "Connection editing requires every enumerated port to have its mapped direction.");
            values.requireModelThread(model);
            if (values.read(OWNER_MODULE, model) != module)
                throw failure("stale_context", "Native port owner differs from its direction-specific module membership.");
            Endpoint endpoint = new Endpoint(id, GridSnapshotReader.text(values.read(input ? INPUT_KEY : OUTPUT_KEY, model)));
            Port port = new Port(endpoint, model);
            if (ports.put(endpoint, port) != null || !graph.portIdentities.add(model))
                throw failure("grid_unavailable", "Duplicate native direction-specific port key or port identity.");
            graph.members.add(model);
            if (!input) graph.outputsByIdentity.put(model, port);
        }
    }
    private static final class Port {
        final Endpoint endpoint;
        final Object model;
        Object source;
        Endpoint sourceEndpoint;
        Port(Endpoint endpoint, Object model) { this.endpoint = endpoint; this.model = model; }
    }
    private static final class Graph {
        final Map<UUID, Object> modules = new HashMap<>();
        final Map<Endpoint, Port> inputs = new HashMap<>(), outputs = new HashMap<>();
        final IdentityHashMap<Object, Port> outputsByIdentity = new IdentityHashMap<>();
        final Set<Object> portIdentities = Collections.newSetFromMap(new IdentityHashMap<>());
        final List<Object> members = new ArrayList<>();
        Port require(Endpoint endpoint, boolean input) {
            Port port = (input ? inputs : outputs).get(endpoint);
            if (port == null) throw failure("stale_context", "Requested exact native " + (input ? "input" : "output")
                    + " is absent; reacquire grid.get. Matching owner/key without stream membership is insufficient.");
            return port;
        }
        void same(Graph after, boolean sources) {
            if (modules.size() != after.modules.size() || inputs.size() != after.inputs.size() || outputs.size() != after.outputs.size())
                throw failure("stale_context", "Native graph or port membership changed during the connection operation.");
            for (Map.Entry<UUID, Object> module : modules.entrySet())
                if (after.modules.get(module.getKey()) != module.getValue())
                    throw failure("stale_context", "Native module identity changed during the connection operation.");
            samePorts(inputs, after.inputs, sources); samePorts(outputs, after.outputs, false);
        }
        private static void samePorts(Map<Endpoint, Port> before, Map<Endpoint, Port> after, boolean sources) {
            for (Port port : before.values()) {
                Port actual = after.get(port.endpoint);
                if (actual == null || actual.model != port.model || (sources && actual.source != port.source))
                    throw failure("stale_context", "Native port identity or captured source changed during the connection observation.");
            }
        }
    }
    static final class Request {
        final Endpoint target, expectedSource, source;
        Request(Endpoint target, Endpoint expectedSource, Endpoint source) {
            this.target = target; this.expectedSource = expectedSource; this.source = source;
        }
        static Request parse(JsonObject p) {
            return new Request(new Endpoint(uuid(p, "targetModuleId"), key(p, "targetPortKey")),
                    endpoint(p, "expectedSource"), endpoint(p, "source"));
        }
        void requireExpected(Endpoint actual) {
            if (!Objects.equals(expectedSource, actual))
                throw failure("stale_context", "Exact native input source differs from expectedSource; reacquire grid.get before editing.");
        }
        private static Endpoint endpoint(JsonObject p, String name) {
            JsonElement value = p.get(name);
            if (value == null) throw failure("invalid_params", name + " is required, including explicit null for disconnected.");
            if (value.isJsonNull()) return null;
            if (!value.isJsonObject()) throw failure("invalid_params", name + " must be null or an exact moduleId/portKey object.");
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
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
                    || value.getAsString().length() > GridSnapshotReader.MAX_TEXT)
                throw failure("invalid_params", name + " must be a string of at most 1024 UTF-16 units.");
            return value.getAsString();
        }
    }
    static final class Endpoint {
        final UUID moduleId;
        final String portKey;
        Endpoint(UUID moduleId, String portKey) { this.moduleId = moduleId; this.portKey = portKey; }
        static JsonElement json(Endpoint endpoint) {
            if (endpoint == null) return JsonNull.INSTANCE;
            JsonObject result = new JsonObject(); result.addProperty("moduleId", endpoint.moduleId.toString());
            result.addProperty("portKey", endpoint.portKey); return result;
        }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Endpoint)) return false;
            Endpoint endpoint = (Endpoint) other;
            return moduleId.equals(endpoint.moduleId) && portKey.equals(endpoint.portKey);
        }
        @Override public int hashCode() { return 31 * moduleId.hashCode() + portKey.hashCode(); }
    }
}
