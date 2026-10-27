package dev.bitwigagent;

import com.google.gson.*;
import java.util.*;
import java.util.stream.Stream;
import static dev.bitwigagent.HostServices.failure;

/** Document-thread traversal, independent of version-specific symbol resolution and RPC targeting. */
final class GridSnapshotReader {
    static final int MAX_MODULES = 4096, MAX_CHILDREN = 256, MAX_OPTIONS = 256, MAX_CATALOG = 16384;
    static final int MAX_ITEMS = 16384, MAX_TEXT = 1024;
    enum Kind { MODULE, INPUT, OUTPUT, PARAMETER, FLOAT, BOOLEAN, INTEGER, ENUM, PANEL_BOOLEAN, PANEL_STRING, BIPOLAR_FLOAT, ENTRY, OPTION }
    private static final Kind[] PARAMETER_KINDS = {Kind.FLOAT, Kind.BOOLEAN, Kind.INTEGER, Kind.ENUM, Kind.PANEL_BOOLEAN, Kind.PANEL_STRING};
    enum Field {
        GRAPH, MODULES, INSTANCE_ID, LOCAL_KEY, X, Y, WIDTH, HEIGHT, DESCRIPTOR, TYPE_ID,
        INPUTS, OUTPUTS, CHILDREN, MODULE_GRAPH, OWNER_MODULE, INPUT_SOURCE, INPUT_KEY, OUTPUT_KEY,
        PATH, FLOAT_VALUE, FLOAT_DEFINITION, FLOAT_MIN, FLOAT_MAX, BOOLEAN_VALUE, STRING_VALUE, BIPOLAR_PANEL,
        INTEGER_VALUE, INTEGER_DEFINITION, INTEGER_MIN, INTEGER_MAX, ENUM_VALUE, ENUM_DEFINITION,
        OPTIONS, OPTION_VALUE, OPTION_LABEL, DISPLAY, CATALOG, ENTRY_ID, ENTRY_NAME, ENTRY_KIND,
        MODULE_NAME, CUSTOM_NAME, TYPE_ENTRY, TYPE_NAME, PARAMETER_ID, PARAMETER_NAME, SHORT_NAME,
        INPUT_NAME, OUTPUT_NAME, FLOAT_UNIT, UNIT_TOKEN, INTEGER_PREFIX, INTEGER_SUFFIX
    }
    enum Metadata {
        FOOTPRINT("moduleFootprint", "native_primitive_width_height_grid_coordinates_not_pixels"),
        MODULE_NAMES("moduleNames", "native_effective_name_and_raw_custom_override"),
        TYPE_NAMES("typeNames", "catalog_uuid_lookup"),
        PARAMETER_NAMES("parameterNames", "native_identifier_name_and_short_name"),
        PORT_NAMES("portNames", "native_stored_names_not_visibility"),
        FLOAT_UNITS("numericUnits", "stored_definition_unit_kind_not_raw_value_unit"),
        INTEGER_AFFIXES("integerDisplayAffixes", "native_definition_prefix_and_suffix");
        final String key, coverage;
        Metadata(String key, String coverage) { this.key = key; this.coverage = coverage; }
    }
    static final Metadata[] METADATA = Metadata.values();
    interface Access extends NativeDeviceTarget.Access {
        Object read(Field field, Object object);
        boolean is(Kind kind, Object object);
        void documentThread();
        void requireModelThread(Object object);
        boolean parametersAvailable();
        boolean displayAvailable();
        boolean metadataAvailable(Metadata metadata);
    }
    private final Access a;
    private final NativeDeviceTarget targets;
    GridSnapshotReader(Access access) { a = access; targets = new NativeDeviceTarget(access); }

    JsonObject catalog(String query, int offset, int limit, Runnable check) {
        a.documentThread(); check.run();
        Object value = a.read(Field.CATALOG, null);
        if (!(value instanceof List<?>)) throw failure("grid_unavailable", "Native Grid catalog is not a list.");
        List<?> catalog = (List<?>) value;
        if (catalog.size() > MAX_CATALOG) throw failure("grid_bounds_exceeded", "Native catalog exceeds 16384 entries; no partial catalog returned.");
        List<CatalogEntry> matches = new ArrayList<>();
        String needle = query.toLowerCase(Locale.ROOT);
        for (Object entry : catalog) {
            check.run();
            if (!a.is(Kind.ENTRY, entry)) throw failure("grid_unavailable", "Unknown native catalog entry type.");
            if (!"MODULE".equals(String.valueOf(a.read(Field.ENTRY_KIND, entry)))) continue;
            String name = text(a.read(Field.ENTRY_NAME, entry));
            if (!name.toLowerCase(Locale.ROOT).contains(needle)) continue;
            matches.add(new CatalogEntry(uuid(a.read(Field.ENTRY_ID, entry)), name));
        }
        matches.sort(Comparator.comparing((CatalogEntry x) -> x.name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(x -> x.name).thenComparing(x -> x.id));
        JsonArray entries = new JsonArray();
        for (int i = offset; i < Math.min(matches.size(), offset + limit); i++) {
            CatalogEntry entry = matches.get(i); JsonObject item = new JsonObject();
            item.addProperty("typeId", entry.id); item.addProperty("name", entry.name); entries.add(item);
        }
        JsonObject result = HostServices.observed();
        result.add("entries", entries); result.add("window", window(offset, limit, matches.size()));
        result.addProperty("source", "native_module_catalog");
        result.addProperty("nativeMutation", false); result.addProperty("nativeUndo", false);
        check.run(); return result;
    }

    JsonObject snapshot(Object proxy, UUID expectedTrackId, int offset, int limit, boolean parameters, Runnable check) {
        a.documentThread(); check.run();
        NativeTarget binding = target(proxy, expectedTrackId);
        Object graph = binding.graph;
        State state = new State(check);
        List<Object> all = collect(Field.MODULES, graph, MAX_MODULES, state);
        IdentityHashMap<Object, String> ids = new IdentityHashMap<>();
        Set<String> seenIds = new HashSet<>();
        for (Object module : all) {
            state.check();
            if (!a.is(Kind.MODULE, module)) { state.unsupported("unknown_graph_child"); continue; }
            a.requireModelThread(module);
            if (a.read(Field.MODULE_GRAPH, module) != graph) throw failure("stale_context", "Grid module belongs to a different graph.");
            String id = uuid(a.read(Field.INSTANCE_ID, module));
            if (!seenIds.add(id)) throw failure("grid_unavailable", "Duplicate native module UUID; cannot identify cable endpoints safely.");
            ids.put(module, id);
        }
        JsonArray modules = new JsonArray(), connections = new JsonArray();
        int end = Math.min(all.size(), offset + limit);
        for (int i = offset; i < end; i++) {
            Object module = all.get(i); state.check();
            if (!ids.containsKey(module)) continue;
            JsonObject item = new JsonObject();
            item.addProperty("modelClass", text(module.getClass().getName()));
            item.addProperty("instanceId", ids.get(module));
            item.addProperty("localKey", text(a.read(Field.LOCAL_KEY, module)));
            metadataText(item, "name", Field.MODULE_NAME, module, Metadata.MODULE_NAMES, state, "module_name");
            metadataText(item, "customName", Field.CUSTOM_NAME, module, Metadata.MODULE_NAMES, state, "module_custom_name");
            item.addProperty("x", integer(a.read(Field.X, module)));
            item.addProperty("y", integer(a.read(Field.Y, module)));
            metadataDimension(item, "width", Field.WIDTH, module, state);
            metadataDimension(item, "height", Field.HEIGHT, module, state);
            Object descriptor = a.read(Field.DESCRIPTOR, module);
            Object typeId = descriptor == null ? null : a.read(Field.TYPE_ID, descriptor);
            if (descriptor == null) {
                item.add("typeId", JsonNull.INSTANCE); state.unsupported("module_type_id_unavailable");
            } else item.addProperty("typeId", uuid(typeId));
            Object entry = metadataValue(Field.TYPE_ENTRY, typeId, Metadata.TYPE_NAMES, state, "module_type_name");
            metadataText(item, "typeName", Field.TYPE_NAME, entry, Metadata.TYPE_NAMES, state, "module_type_name");
            Set<Object> enumeratedPorts = Collections.newSetFromMap(new IdentityHashMap<>());
            item.add("inputs", ports(module, graph, ids, true, connections, enumeratedPorts, state));
            item.add("outputs", ports(module, graph, ids, false, connections, enumeratedPorts, state));
            JsonArray values = new JsonArray();
            if (parameters && a.parametersAvailable()) {
                for (Object child : collect(Field.CHILDREN, module, MAX_CHILDREN, state)) {
                    state.check();
                    if (a.is(Kind.INPUT, child) || a.is(Kind.OUTPUT, child)) {
                        if (!enumeratedPorts.contains(child)) state.unsupported("port_child_not_enumerated");
                        continue;
                    }
                    values.add(parameter(child, module, state));
                }
            } else state.unsupported(parameters ? "typed_parameter_binding_unavailable" : "parameters_not_requested");
            item.add("parameters", values); modules.add(item);
        }
        if (offset != 0 || all.size() > limit) state.unsupported("module_window_partial");
        if (parameters && !a.parametersAvailable()) state.unsupported("typed_parameter_binding_unavailable");
        if (!parameters) state.unsupported("parameters_not_requested");
        check.run();
        guardTarget(binding, proxy, expectedTrackId);
        JsonObject result = HostServices.observed();
        JsonObject deviceMetadata = new JsonObject(), graphMetadata = new JsonObject();
        deviceMetadata.addProperty("modelClass", text(binding.target.getClass().getName()));
        graphMetadata.addProperty("modelClass", text(graph.getClass().getName()));
        result.add("device", deviceMetadata); result.add("graph", graphMetadata);
        result.add("modules", modules); result.add("connections", connections);
        result.add("window", window(offset, limit, all.size()));
        result.addProperty("complete", state.unsupported.isEmpty());
        JsonObject coverage = new JsonObject();
        coverage.addProperty("structure", "mapped_modules_and_ports");
        coverage.addProperty("nativeOwningTrack", "device_ancestor_channel_uuid_checked_before_and_after_capture");
        coverage.addProperty("connections", "incoming_to_returned_modules_same_graph_only");
        coverage.addProperty("parameters", !parameters ? "not_requested" : a.parametersAvailable() ? "known_typed_children_only" : "unavailable");
        coverage.addProperty("display", a.displayAvailable() ? "native_reader_unverified_semantics" : "unsupported");
        for (Metadata metadata : METADATA)
            coverage.addProperty(metadata.key, a.metadataAvailable(metadata) ? metadata.coverage : "unsupported");
        coverage.addProperty("positionUnits", "native_grid_coordinates_unverified");
        coverage.addProperty("nestedChildren", "not_traversed");
        result.add("coverage", coverage);
        JsonArray unsupported = new JsonArray(); state.unsupported.forEach(unsupported::add); result.add("unsupported", unsupported);
        result.addProperty("nativeRevisionAvailable", false);
        result.addProperty("identityCaveat", "instanceId is a current native UUID, not typeId or localKey; observed UUIDs changed across project reopen and must be reacquired. localKey and paths may change after edits. Native owning-track channel UUID is corroborated before and after capture, including the SCA alias mapping. Device index and name remain public observations that can lag native rebinding; same-track same-name replacement is not distinguished. No persistent device identity, native device-position corroboration, native ABA detection, native revision or cross-request atomicity.");
        result.addProperty("nativeMutation", false); result.addProperty("nativeUndo", false);
        return result;
    }

    JsonObject parameter(Object proxy, UUID expectedTrackId, UUID moduleId, String parameterId,
            GridParameterContext contexts, Runnable check) {
        if (contexts == null) throw failure("grid_unavailable", "Required parameter inspection bindings are unavailable.");
        ParameterBinding binding = bindParameter(proxy, expectedTrackId, moduleId, parameterId, check);
        GridParameterContext.Capture context = contexts.capture(binding.device, binding.graph, binding.module,
                binding.parameter, binding.kind, check);
        ParameterBinding coupled = binding.kind == Kind.PANEL_BOOLEAN ? bindBipolarParameter(binding) : null;
        GridParameterContext.Capture coupledContext = coupled == null ? null
                : contexts.capture(coupled.device, coupled.graph, coupled.module, coupled.parameter, coupled.kind, check);
        JsonObject value = parameterValue(binding);
        JsonObject coupledValue = coupled == null ? null : parameterValue(coupled);
        guardParameter(binding);
        contexts.guard(context, binding.device, binding.graph, binding.module, binding.parameter, binding.kind, check);
        guardParameter(binding);
        if (coupled != null) {
            guardBipolarParameter(binding, coupled);
            contexts.guard(coupledContext, coupled.device, coupled.graph, coupled.module, coupled.parameter, coupled.kind, check);
            guardBipolarParameter(binding, coupled);
        }
        JsonObject result = parameterResult(binding, value);
        result.add("editContext", context.json);
        if (coupled != null) {
            result.add("coupledParameter", coupledValue);
            result.add("coupledEditContext", coupledContext.json);
        }
        result.addProperty("nativeMutation", false); result.addProperty("nativeUndo", false);
        return result;
    }

    ParameterBinding bindParameter(Object proxy, UUID expectedTrackId, UUID moduleId, String parameterId, Runnable check) {
        a.documentThread(); check.run();
        if (!a.parametersAvailable()) throw failure("grid_unavailable", "Required typed parameter bindings are unavailable.");
        NativeTarget target = target(proxy, expectedTrackId);
        State state = new State(check);
        Object module = findModule(target.graph, moduleId, state);
        Object child = findParameter(module, parameterId, state);
        Kind kind = parameterKind(child);
        if (kind == null) throw failure("grid_unavailable", "Requested parameter family is unsupported.");
        return new ParameterBinding(proxy, expectedTrackId, moduleId, parameterId, target, module, child, kind, state);
    }

    void guardParameter(ParameterBinding binding) {
        binding.state.guard.run();
        guardTarget(binding.target, binding.proxy, binding.trackId);
        if (findModule(binding.graph, binding.moduleId, binding.state) != binding.module
                || findParameter(binding.module, binding.parameterId, binding.state) != binding.parameter
                || parameterKind(binding.parameter) != binding.kind)
            throw failure("stale_context", "Selected native module or parameter changed during the operation.");
        a.requireModelThread(binding.parameter);
        binding.state.guard.run();
    }

    ParameterBinding bindBipolarParameter(ParameterBinding panel) {
        Object child = findBipolarParameter(panel);
        String id = text(a.read(Field.PARAMETER_ID, child));
        ParameterBinding result = new ParameterBinding(panel.proxy, panel.trackId, panel.moduleId, id,
                panel.target, panel.module, child, Kind.FLOAT, panel.state);
        guardParameter(result);
        return result;
    }

    void guardBipolarParameter(ParameterBinding panel, ParameterBinding coupled) {
        guardParameter(panel); guardParameter(coupled);
        if (findBipolarParameter(panel) != coupled.parameter)
            throw failure("stale_context", "Boolean panel's unique native bipolar reference changed.");
    }

    private Object findBipolarParameter(ParameterBinding panel) {
        if (panel.kind != Kind.PANEL_BOOLEAN)
            throw failure("grid_unavailable", "Bipolar dependency requires a mapped boolean panel.");
        Object found = null;
        for (Object child : collect(Field.CHILDREN, panel.module, MAX_CHILDREN, panel.state)) {
            panel.state.check();
            if (!a.is(Kind.BIPOLAR_FLOAT, child)) continue;
            a.requireModelThread(child);
            if (a.read(Field.BIPOLAR_PANEL, child) != panel.parameter) continue;
            if (a.read(Field.OWNER_MODULE, child) != panel.module || parameterKind(child) != Kind.FLOAT)
                throw failure("stale_context", "Linked bipolar parameter belongs to another module or family.");
            if (found != null)
                throw failure("grid_unavailable", "Boolean panel has multiple linked bipolar parameters; coupled editing is unsupported.");
            found = child;
        }
        if (found == null)
            throw failure("grid_unavailable", "Boolean panel requires one same-module exact mapped bipolar scalar reference for inspection or editing.");
        return found;
    }

    JsonObject parameterValue(ParameterBinding binding) {
        return parameter(binding.parameter, binding.module, binding.state);
    }

    JsonObject parameterResult(ParameterBinding binding, JsonObject value) {
        JsonObject result = HostServices.observed();
        result.addProperty("moduleId", binding.moduleId.toString());
        result.add("parameter", value);
        result.addProperty("complete", binding.state.unsupported.isEmpty());
        JsonArray unsupported = new JsonArray(); binding.state.unsupported.forEach(unsupported::add);
        result.add("unsupported", unsupported);
        return result;
    }

    /** Strictly callback-local native binding, shared by inspection and mutation orchestration. */
    static final class ParameterBinding {
        final Object device, graph, module, parameter;
        final Kind kind;
        private final Object proxy;
        private final UUID trackId, moduleId;
        private final String parameterId;
        private final NativeTarget target;
        private final State state;
        ParameterBinding(Object proxy, UUID trackId, UUID moduleId, String parameterId, NativeTarget target,
                Object module, Object parameter, Kind kind, State state) {
            this.proxy = proxy; this.trackId = trackId; this.moduleId = moduleId; this.parameterId = parameterId;
            this.target = target; this.device = target.target; this.graph = target.graph;
            this.module = module; this.parameter = parameter; this.kind = kind; this.state = state;
        }
    }

    private Object findModule(Object graph, UUID id, State state) {
        Object found = null;
        for (Object module : collect(Field.MODULES, graph, MAX_MODULES, state)) {
            state.check();
            if (!a.is(Kind.MODULE, module)) continue;
            a.requireModelThread(module);
            if (!id.toString().equals(uuid(a.read(Field.INSTANCE_ID, module)))) continue;
            if (found != null) throw failure("stale_context", "Requested module UUID is ambiguous.");
            if (a.read(Field.MODULE_GRAPH, module) != graph)
                throw failure("stale_context", "Requested module moved outside the target graph.");
            found = module;
        }
        if (found == null) throw failure("stale_context", "Requested module UUID is no longer in the target graph; reacquire grid.get.");
        return found;
    }

    private Object findParameter(Object module, String id, State state) {
        Object found = null;
        for (Object child : collect(Field.CHILDREN, module, MAX_CHILDREN, state)) {
            state.check();
            if (!a.is(Kind.PARAMETER, child) && !a.is(Kind.PANEL_BOOLEAN, child) && !a.is(Kind.PANEL_STRING, child)) continue;
            a.requireModelThread(child);
            if (!id.equals(text(a.read(Field.PARAMETER_ID, child)))) continue;
            if (found != null) throw failure("stale_context", "Requested parameter ID is ambiguous.");
            if (a.read(Field.OWNER_MODULE, child) != module)
                throw failure("stale_context", "Requested parameter moved outside the selected module.");
            found = child;
        }
        if (found == null) throw failure("stale_context", "Requested exact parameter ID is no longer in the selected module.");
        return found;
    }

    /** Full bounded module membership for topology writers; opaque graph children cannot be skipped. */
    List<Object> topologyModules(Object graph, Runnable check) {
        return topologyModules(graph, new State(check));
    }
    List<Object> topologyModules(Object graph, State state) {
        List<Object> modules = collect(Field.MODULES, graph, GridTopologyContext.MAX_MODULES, state);
        Set<UUID> ids = new HashSet<>();
        for (Object module : modules) {
            state.check();
            if (!a.is(Kind.MODULE, module))
                throw failure("grid_unavailable", "Topology editing requires every graph child to be a mapped module.");
            a.requireModelThread(module);
            if (a.read(Field.MODULE_GRAPH, module) != graph)
                throw failure("stale_context", "Native module belongs to a different graph.");
            Object id = a.read(Field.INSTANCE_ID, module);
            if (!(id instanceof UUID) || !ids.add((UUID) id))
                throw failure("grid_unavailable", "Topology editing requires unique native module UUIDs.");
        }
        return modules;
    }

    NativeTarget target(Object proxy, UUID expectedTrackId) {
        NativeDeviceTarget.Capture capture;
        try { capture = targets.capture(proxy, expectedTrackId, () -> {}); }
        catch (RpcException error) {
            if ("unsupported_scope".equals(error.code))
                throw failure("not_grid", "Explicit native device has no mapped Grid graph accessor.");
            throw error;
        }
        Object graph = a.read(Field.GRAPH, capture.device);
        if (graph == null) throw failure("not_grid", "Explicit device does not contain a Grid graph.");
        a.requireModelThread(graph);
        return new NativeTarget(capture, graph);
    }

    void guardTarget(NativeTarget binding, Object proxy, UUID expectedTrackId) {
        if (!binding.ownership.trackId.equals(expectedTrackId))
            throw failure("stale_context", "Expected native owning track changed during the read.");
        targets.guard(binding.ownership, proxy, () -> {});
        if (a.read(Field.GRAPH, binding.target) != binding.graph)
            throw failure("stale_context", "Explicit native graph changed during the read.");
        a.requireModelThread(binding.graph);
    }

    static final class NativeTarget {
        final Object target, graph;
        final NativeDeviceTarget.Capture ownership;
        NativeTarget(NativeDeviceTarget.Capture ownership, Object graph) {
            this.ownership = ownership; this.target = ownership.device; this.graph = graph;
        }
    }

    private Kind parameterKind(Object child) {
        for (Kind candidate : PARAMETER_KINDS) if (a.is(candidate, child)) return candidate;
        return null;
    }


    private JsonArray ports(Object module, Object graph, IdentityHashMap<Object, String> ids,
            boolean input, JsonArray connections, Set<Object> enumeratedPorts, State state) {
        JsonArray ports = new JsonArray(); Set<String> keys = new HashSet<>();
        for (Object port : collect(input ? Field.INPUTS : Field.OUTPUTS, module, MAX_CHILDREN, state)) {
            state.check();
            if (!a.is(input ? Kind.INPUT : Kind.OUTPUT, port)) { state.unsupported("unknown_port_type"); continue; }
            a.requireModelThread(port);
            if (a.read(Field.OWNER_MODULE, port) != module) throw failure("stale_context", "Port owner differs from its enumerated module.");
            String key = text(a.read(input ? Field.INPUT_KEY : Field.OUTPUT_KEY, port));
            if (!keys.add(key)) throw failure("grid_unavailable", "Duplicate native port key; endpoint identity is ambiguous.");
            enumeratedPorts.add(port);
            JsonObject item = new JsonObject(); item.addProperty("key", key);
            item.addProperty("modelClass", text(port.getClass().getName()));
            item.addProperty("path", text(a.read(Field.PATH, port)));
            metadataText(item, "name", input ? Field.INPUT_NAME : Field.OUTPUT_NAME, port,
                    Metadata.PORT_NAMES, state, input ? "input_name" : "output_name");
            ports.add(item);
            if (!input) continue;
            Object source = a.read(Field.INPUT_SOURCE, port);
            item.addProperty("connected", source != null);
            if (source == null) continue;
            if (!a.is(Kind.OUTPUT, source)) { state.unsupported("unknown_connection_source_type"); continue; }
            a.requireModelThread(source);
            Object owner = a.read(Field.OWNER_MODULE, source);
            if (!a.is(Kind.MODULE, owner) || a.read(Field.MODULE_GRAPH, owner) != graph || !ids.containsKey(owner)) {
                state.unsupported("connection_source_outside_graph"); continue;
            }
            a.requireModelThread(owner);
            JsonObject connection = new JsonObject();
            connection.addProperty("sourceModuleId", ids.get(owner));
            connection.addProperty("sourcePortKey", text(a.read(Field.OUTPUT_KEY, source)));
            connection.addProperty("targetModuleId", ids.get(module)); connection.addProperty("targetPortKey", key);
            connections.add(connection);
        }
        return ports;
    }

    private JsonObject parameter(Object child, Object module, State state) {
        JsonObject result = new JsonObject();
        Kind kind = parameterKind(child);
        if (kind == null) {
            result.addProperty("supported", false); result.addProperty("reason", "unknown_child_model");
            result.addProperty("modelClass", child == null ? "null" : child.getClass().getName());
            state.unsupported("unknown_child_model"); return result;
        }
        a.requireModelThread(child);
        if (a.read(Field.OWNER_MODULE, child) != module) throw failure("stale_context", "Parameter belongs to a different module.");
        boolean panel = kind == Kind.PANEL_BOOLEAN || kind == Kind.PANEL_STRING;
        String panelFamily = kind == Kind.PANEL_STRING ? "string_panel" : "boolean_panel";
        result.addProperty("supported", true);
        result.addProperty("kind", panel ? (kind == Kind.PANEL_STRING ? "string" : "boolean") : kind.name().toLowerCase(Locale.ROOT));
        result.addProperty("path", text(a.read(Field.PATH, child)));
        if (panel) {
            result.addProperty("controlFamily", panelFamily);
            result.addProperty("parameterId", text(a.read(Field.PARAMETER_ID, child)));
            result.add("name", JsonNull.INSTANCE); result.add("shortName", JsonNull.INSTANCE);
            state.unsupported(panelFamily + "_labels_unmapped");
        } else {
            metadataText(result, "parameterId", Field.PARAMETER_ID, child, Metadata.PARAMETER_NAMES, state, "parameter_id");
            metadataText(result, "name", Field.PARAMETER_NAME, child, Metadata.PARAMETER_NAMES, state, "parameter_name");
            metadataText(result, "shortName", Field.SHORT_NAME, child, Metadata.PARAMETER_NAMES, state, "parameter_short_name");
        }
        switch (kind) {
            case PANEL_STRING:
                result.addProperty("value", text(a.read(Field.STRING_VALUE, child))); break;
            case FLOAT: {
                double value = finite(a.read(Field.FLOAT_VALUE, child));
                Object definition = a.read(Field.FLOAT_DEFINITION, child);
                double min = finite(a.read(Field.FLOAT_MIN, definition)), max = finite(a.read(Field.FLOAT_MAX, definition));
                Object unit = metadataValue(Field.FLOAT_UNIT, definition, Metadata.FLOAT_UNITS, state, "numeric_unit");
                metadataText(result, "numericUnit", Field.UNIT_TOKEN, unit, Metadata.FLOAT_UNITS, state, "numeric_unit");
                range(min, max); result.addProperty("value", value); result.addProperty("minimum", min); result.addProperty("maximum", max); break;
            }
            case PANEL_BOOLEAN:
            case BOOLEAN: {
                Object value = a.read(Field.BOOLEAN_VALUE, child);
                if (!(value instanceof Boolean)) throw failure("grid_unavailable", "Invalid boolean parameter value.");
                result.addProperty("value", (Boolean) value); break;
            }
            case INTEGER: {
                int value = integer(a.read(Field.INTEGER_VALUE, child));
                Object definition = a.read(Field.INTEGER_DEFINITION, child);
                int min = integer(a.read(Field.INTEGER_MIN, definition)), max = integer(a.read(Field.INTEGER_MAX, definition));
                metadataText(result, "displayPrefix", Field.INTEGER_PREFIX, definition, Metadata.INTEGER_AFFIXES, state, "integer_display_prefix");
                metadataText(result, "displaySuffix", Field.INTEGER_SUFFIX, definition, Metadata.INTEGER_AFFIXES, state, "integer_display_suffix");
                range(min, max); result.addProperty("value", value); result.addProperty("minimum", min); result.addProperty("maximum", max); break;
            }
            case ENUM: {
                int value = integer(a.read(Field.ENUM_VALUE, child)); result.addProperty("value", value);
                Object definition = a.read(Field.ENUM_DEFINITION, child);
                Object options = a.read(Field.OPTIONS, definition);
                if (!(options instanceof List<?>)) throw failure("grid_unavailable", "Invalid native enum choices.");
                List<?> list = (List<?>) options;
                if (list.size() > MAX_OPTIONS) throw failure("grid_bounds_exceeded", "Enum exceeds 256 choices.");
                JsonArray choices = new JsonArray(); boolean found = false; Set<Integer> seen = new HashSet<>();
                for (Object option : list) {
                    state.check();
                    if (!a.is(Kind.OPTION, option)) throw failure("grid_unavailable", "Unknown enum option model.");
                    int number = integer(a.read(Field.OPTION_VALUE, option));
                    if (!seen.add(number)) throw failure("grid_unavailable", "Duplicate native enum choice values.");
                    found |= number == value;
                    JsonObject choice = new JsonObject(); choice.addProperty("value", number);
                    choice.addProperty("label", text(a.read(Field.OPTION_LABEL, option))); choices.add(choice);
                }
                result.add("options", choices); result.addProperty("valueInOptions", found);
                if (!found) state.unsupported("enum_value_not_in_options"); break;
            }
            default: throw new AssertionError(kind);
        }
        if (panel) { result.add("display", JsonNull.INSTANCE); state.unsupported(panelFamily + "_display_unmapped"); }
        else if (a.displayAvailable()) result.addProperty("display", text(a.read(Field.DISPLAY, child)));
        else { result.add("display", JsonNull.INSTANCE); state.unsupported("parameter_display_unavailable"); }
        return result;
    }

    private Object metadataValue(Field field, Object owner, Metadata metadata, State state, String reason) {
        if (!a.metadataAvailable(metadata) || owner == null) {
            state.unsupported(reason + "_unavailable"); return null;
        }
        try {
            Object value = a.read(field, owner);
            if (value == null) state.unsupported(reason + "_unavailable");
            return value;
        } catch (RpcException error) {
            // Optional descriptive reads never suppress identity, ownership, cancellation or size failures.
            if (!"grid_unavailable".equals(error.code)) throw error;
            state.unsupported(reason + "_read_failed"); return null;
        }
    }
    private void metadataText(JsonObject result, String key, Field field, Object owner,
            Metadata metadata, State state, String reason) {
        Object value = metadataValue(field, owner, metadata, state, reason);
        if (value instanceof String) result.addProperty(key, text(value));
        else {
            result.add(key, JsonNull.INSTANCE);
            if (value != null) state.unsupported(reason + "_invalid");
        }
    }

    private void metadataDimension(JsonObject result, String key, Field field, Object owner, State state) {
        Object value = metadataValue(field, owner, Metadata.FOOTPRINT, state, "module_" + key);
        if (value instanceof Integer && (Integer) value > 0) result.addProperty(key, (Integer) value);
        else {
            result.add(key, JsonNull.INSTANCE);
            if (value != null) state.unsupported("module_" + key + "_invalid");
        }
    }

    List<Object> collect(Field field, Object owner, int maximum, State state) {
        Object value = a.read(field, owner);
        if (!(value instanceof Stream<?>)) throw failure("grid_unavailable", "Native " + field + " reader did not return a stream.");
        List<Object> result = new ArrayList<>();
        try (Stream<?> stream = (Stream<?>) value) {
            Iterator<?> iterator = stream.iterator();
            while (iterator.hasNext()) {
                state.check();
                if (result.size() == maximum) throw failure("grid_bounds_exceeded", field + " exceeds traversal limit " + maximum + "; no fake partial graph returned.");
                result.add(iterator.next());
            }
        }
        return result;
    }
    static JsonObject window(int offset, int limit, int total) {
        JsonObject result = HostServices.window(offset, limit, total); result.addProperty("limit", limit); return result;
    }
    private static String uuid(Object value) {
        if (!(value instanceof UUID)) throw failure("grid_unavailable", "Native UUID identity is unavailable.");
        return value.toString();
    }
    static String text(Object value) {
        if (!(value instanceof String)) throw failure("grid_unavailable", "Native text field is unavailable.");
        String text = (String) value;
        if (text.length() > MAX_TEXT) throw failure("grid_bounds_exceeded", "Native text field exceeds 1024 characters.");
        return text;
    }
    private static int integer(Object value) {
        if (!(value instanceof Integer)) throw failure("grid_unavailable", "Native integer field has an unexpected type.");
        return (Integer) value;
    }
    private static double finite(Object value) {
        if (!(value instanceof Double) || !Double.isFinite((Double) value)) throw failure("grid_unavailable", "Native floating value is nonfinite or unsupported.");
        return (Double) value;
    }
    private static void range(double min, double max) {
        if (min > max) throw failure("grid_unavailable", "Native parameter range is inverted.");
    }
    private static final class CatalogEntry {
        final String id, name;
        CatalogEntry(String id, String name) { this.id = id; this.name = name; }
    }
    static final class State {
        final Runnable guard; final Set<String> unsupported = new LinkedHashSet<>(); int work;
        State(Runnable guard) { this.guard = guard; }
        void check() {
            guard.run();
            if (++work > MAX_ITEMS) throw failure("grid_bounds_exceeded", "Grid read exceeds total traversal budget; reduce moduleLimit.");
        }
        void unsupported(String reason) { unsupported.add(reason); }
    }
}
