package dev.bitwigagent;

import com.google.gson.*;
import java.util.*;
import java.util.stream.Stream;

/** Document-thread traversal, independent of version-specific symbol resolution and RPC targeting. */
final class GridSnapshotReader implements ParameterResolver.ModuleOwners {
    static final int MAX_MODULES = 4096, MAX_CHILDREN = 256, MAX_OPTIONS = 256, MAX_CATALOG = 16384;
    static final int MAX_ITEMS = 131072, MAX_TEXT = 1024;
    /** Step arrays: default exposure when STEPS is unreadable, the full-array cap, and the finite sanity bound for step values (not a per-module range: Pitches are in Grid pitch units, the rest 0..1). */
    static final int STEP_FALLBACK = 64, STEP_FULL = 256;

    static final double STEP_MIN = -128.0, STEP_MAX = 128.0;
    /**
     * Child models of the 6.1.4 module classes that carry no user data (engine feeds, indicators, display state);
     * classified internal, never counted as unsupported. By simple class name, valid under the 6.1.4 JAR gate only.
     */
    static final Set<String> INTERNAL_MODELS = Set.of("BgP", "HQh", "L1", "uUS", "fwH", "qzH", "Znu");
    /** Kiy also names another class, so it is matched by its fully qualified name. */
    static final String INTERNAL_KIY = "com.bitwig.flt.document.core.master.device.Kiy";

    enum Kind {
        MODULE,
        INPUT,
        OUTPUT,
        PARAMETER,
        FLOAT,
        BOOLEAN,
        INTEGER,
        ENUM,
        PANEL_BOOLEAN,
        PANEL_STRING,
        STEP_ARRAY,
        VOICING,
        BIPOLAR_FLOAT,
        ENTRY,
        OPTION
    }

    private static final Kind[] PARAMETER_KINDS = {
        Kind.FLOAT, Kind.BOOLEAN, Kind.INTEGER, Kind.ENUM, Kind.PANEL_BOOLEAN, Kind.PANEL_STRING, Kind.STEP_ARRAY
    };

    enum Field {
        GRAPH,
        MODULES,
        INSTANCE_ID,
        LOCAL_KEY,
        X,
        Y,
        WIDTH,
        HEIGHT,
        DESCRIPTOR,
        TYPE_ID,
        INPUTS,
        OUTPUTS,
        CHILDREN,
        MODULE_GRAPH,
        OWNER_MODULE,
        INPUT_SOURCE,
        INPUT_KEY,
        OUTPUT_KEY,
        PATH,
        FLOAT_VALUE,
        FLOAT_DEFINITION,
        FLOAT_MIN,
        FLOAT_MAX,
        BOOLEAN_VALUE,
        STRING_VALUE,
        BIPOLAR_PANEL,
        INTEGER_VALUE,
        INTEGER_DEFINITION,
        INTEGER_MIN,
        INTEGER_MAX,
        ENUM_VALUE,
        ENUM_DEFINITION,
        OPTIONS,
        OPTION_VALUE,
        OPTION_LABEL,
        DISPLAY,
        CATALOG,
        ENTRY_ID,
        ENTRY_NAME,
        ENTRY_KIND,
        MODULE_NAME,
        CUSTOM_NAME,
        TYPE_ENTRY,
        TYPE_NAME,
        PARAMETER_ID,
        PARAMETER_NAME,
        SHORT_NAME,
        INPUT_NAME,
        OUTPUT_NAME,
        FLOAT_UNIT,
        UNIT_TOKEN,
        INTEGER_PREFIX,
        INTEGER_SUFFIX,
        STEP_VALUES,
        STEP_LENGTH
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

        Metadata(String key, String coverage) {
            this.key = key;
            this.coverage = coverage;
        }
    }

    static final Metadata[] METADATA = Metadata.values();

    interface Access extends DeviceSources.Native.Access {
        Object read(Field field, Object object);

        boolean is(Kind kind, Object object);

        void documentThread();

        void requireModelThread(Object object);

        boolean parametersAvailable();

        boolean displayAvailable();

        boolean metadataAvailable(Metadata metadata);
    }

    private final Access a;
    private final DeviceSources.Native targets;

    GridSnapshotReader(Access access) {
        a = access;
        targets = new DeviceSources.Native(access);
    }

    JsonObject catalog(String query, int offset, int limit, Runnable check) {
        a.documentThread();
        check.run();
        Object value = a.read(Field.CATALOG, null);
        if (!(value instanceof List<?>))
            throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "Native Grid catalog is not a list.");
        List<?> catalog = (List<?>) value;
        if (catalog.size() > MAX_CATALOG)
            throw Errors.notApplied(
                    Reason.OUT_OF_BOUNDS, "Native catalog exceeds 16384 entries; no partial catalog returned.");
        List<CatalogEntry> matches = new ArrayList<>();
        List<CatalogEntry> everything = new ArrayList<>();
        for (Object entry : catalog) {
            check.run();
            if (!a.is(Kind.ENTRY, entry))
                throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "Unknown native catalog entry type.");
            if (!"MODULE".equals(String.valueOf(a.read(Field.ENTRY_KIND, entry)))) continue;
            String name = text(a.read(Field.ENTRY_NAME, entry));
            CatalogEntry row = new CatalogEntry(uuid(a.read(Field.ENTRY_ID, entry)), name);
            everything.add(row);
            if (GridNames.matches(name, query)) matches.add(row);
        }
        matches.sort(Comparator.comparing((CatalogEntry x) -> x.name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(x -> x.name)
                .thenComparing(x -> x.id));
        JsonArray entries = new JsonArray();
        for (int i = offset; i < Math.min(matches.size(), offset + limit); i++) {
            CatalogEntry entry = matches.get(i);
            JsonObject item = new JsonObject();
            item.addProperty("typeId", entry.id);
            item.addProperty("name", entry.name);
            entries.add(item);
        }
        JsonObject result = new JsonObject();
        result.add("entries", entries);
        result.add("window", window(offset, limit, matches.size()));
        if (matches.isEmpty() && !query.trim().isEmpty()) {
            Map<String, CatalogEntry> byName = new LinkedHashMap<>();
            for (CatalogEntry row : everything) byName.putIfAbsent(row.name, row);
            JsonArray suggestions = new JsonArray();
            for (String name : GridNames.nearest(byName.keySet(), query, GridNames.SUGGESTIONS)) {
                JsonObject item = new JsonObject();
                item.addProperty("typeId", byName.get(name).id);
                item.addProperty("name", name);
                suggestions.add(item);
            }
            result.add("suggestions", suggestions);
        }
        result.addProperty("source", "native_module_catalog");
        result.addProperty("nativeMutation", false);
        result.addProperty("nativeUndo", false);
        check.run();
        return result;
    }

    JsonObject snapshot(DeviceSources.Source source, int offset, int limit, boolean parameters, Runnable check) {
        return snapshot(source, offset, limit, parameters, true, null, check);
    }

    /** diagnostics adds native modelClass/path and constant coverage prose; moduleIds (lowercase UUIDs) selects rows. */
    JsonObject snapshot(
            DeviceSources.Source source,
            int offset,
            int limit,
            boolean parameters,
            boolean diagnostics,
            Set<String> moduleIds,
            Runnable check) {
        return snapshot(source, offset, limit, parameters, diagnostics, moduleIds, false, check);
    }

    /** fullSteps exposes up to 256 step-array entries instead of the first STEPS ones. */
    JsonObject snapshot(
            DeviceSources.Source source,
            int offset,
            int limit,
            boolean parameters,
            boolean diagnostics,
            Set<String> moduleIds,
            boolean fullSteps,
            Runnable check) {
        a.documentThread();
        check.run();
        NativeTarget binding = target(source);
        Object graph = binding.graph;
        State state = new State(check);
        state.full = fullSteps;
        List<Object> all = collect(Field.MODULES, graph, MAX_MODULES, state);
        IdentityHashMap<Object, String> ids = new IdentityHashMap<>();
        Set<String> seenIds = new HashSet<>();
        for (Object module : all) {
            state.check();
            if (!a.is(Kind.MODULE, module)) {
                state.unsupported("unknown_graph_child");
                continue;
            }
            a.requireModelThread(module);
            if (a.read(Field.MODULE_GRAPH, module) != graph)
                throw Errors.notApplied(Reason.STALE_CONTEXT, "Grid module belongs to a different graph.");
            String id = uuid(a.read(Field.INSTANCE_ID, module));
            if (!seenIds.add(id))
                throw Errors.notApplied(
                        Reason.NATIVE_UNAVAILABLE,
                        "Duplicate native module UUID; cannot identify cable endpoints safely.");
            ids.put(module, id);
        }
        JsonArray modules = new JsonArray(), connections = new JsonArray();
        List<Object> window = all;
        JsonArray missing = new JsonArray();
        if (moduleIds != null) {
            window = new ArrayList<>();
            Set<String> found = new HashSet<>();
            for (Object module : all) {
                String id = ids.get(module);
                if (id != null && moduleIds.contains(id)) {
                    window.add(module);
                    found.add(id);
                }
            }
            for (String id : new TreeSet<>(moduleIds)) if (!found.contains(id)) missing.add(id);
            state.unsupported("module_filter_partial");
        }
        int end = Math.min(window.size(), offset + limit);
        for (int i = offset; i < end; i++) {
            Object module = window.get(i);
            state.check();
            if (!ids.containsKey(module)) continue;
            JsonObject item = new JsonObject();
            if (diagnostics)
                item.addProperty("modelClass", text(module.getClass().getName()));
            item.addProperty("instanceId", ids.get(module));
            item.addProperty("localKey", text(a.read(Field.LOCAL_KEY, module)));
            metadataText(item, "name", Field.MODULE_NAME, module, Metadata.MODULE_NAMES, state, "module_name");
            metadataText(
                    item, "customName", Field.CUSTOM_NAME, module, Metadata.MODULE_NAMES, state, "module_custom_name");
            item.addProperty("x", integer(a.read(Field.X, module)));
            item.addProperty("y", integer(a.read(Field.Y, module)));
            metadataDimension(item, "width", Field.WIDTH, module, state);
            metadataDimension(item, "height", Field.HEIGHT, module, state);
            Object descriptor = a.read(Field.DESCRIPTOR, module);
            Object typeId = descriptor == null ? null : a.read(Field.TYPE_ID, descriptor);
            if (descriptor == null) {
                item.add("typeId", JsonNull.INSTANCE);
                state.unsupported("module_type_id_unavailable");
            } else item.addProperty("typeId", uuid(typeId));
            Object entry = metadataValue(Field.TYPE_ENTRY, typeId, Metadata.TYPE_NAMES, state, "module_type_name");
            metadataText(item, "typeName", Field.TYPE_NAME, entry, Metadata.TYPE_NAMES, state, "module_type_name");
            Set<Object> enumeratedPorts = Collections.newSetFromMap(new IdentityHashMap<>());
            item.add("inputs", ports(module, graph, ids, true, connections, enumeratedPorts, diagnostics, state));
            item.add("outputs", ports(module, graph, ids, false, connections, enumeratedPorts, diagnostics, state));
            JsonArray values = new JsonArray();
            if (parameters && a.parametersAvailable()) {
                int internal = 0;
                for (Object child : collect(Field.CHILDREN, module, MAX_CHILDREN, state)) {
                    state.check();
                    if (a.is(Kind.INPUT, child) || a.is(Kind.OUTPUT, child)) {
                        if (!enumeratedPorts.contains(child)) state.unsupported("port_child_not_enumerated");
                        continue;
                    }
                    if (isInternal(child)) {
                        internal++;
                        if (!diagnostics) continue;
                    }
                    values.add(parameter(child, module, diagnostics, state));
                }
                if (internal > 0) item.addProperty("internalChildren", internal);
            } else state.unsupported(parameters ? "typed_parameter_binding_unavailable" : "parameters_not_requested");
            item.add("parameters", values);
            modules.add(item);
        }
        if (offset != 0 || window.size() > limit) state.unsupported("module_window_partial");
        if (parameters && !a.parametersAvailable()) state.unsupported("typed_parameter_binding_unavailable");
        if (!parameters) state.unsupported("parameters_not_requested");
        check.run();
        guardTarget(binding);
        JsonObject result = new JsonObject();
        JsonObject deviceMetadata = new JsonObject(), graphMetadata = new JsonObject();
        deviceMetadata.addProperty("modelClass", text(binding.target.getClass().getName()));
        graphMetadata.addProperty("modelClass", text(graph.getClass().getName()));
        if (diagnostics) {
            result.add("device", deviceMetadata);
            result.add("graph", graphMetadata);
        }
        result.add("modules", modules);
        result.add("connections", connections);
        if (missing.size() > 0) result.add("missingModuleIds", missing);
        result.add("window", window(offset, limit, window.size()));
        result.addProperty("complete", state.unsupported.isEmpty());
        JsonObject coverage = new JsonObject();
        if (diagnostics) {
            coverage.addProperty("structure", "mapped_modules_and_ports");
            coverage.addProperty("nativeOwningTrack", "device_ancestor_channel_uuid_checked_before_and_after_capture");
            coverage.addProperty("connections", "incoming_to_returned_modules_same_graph_only");
            coverage.addProperty(
                    "parameters",
                    !parameters
                            ? "not_requested"
                            : a.parametersAvailable() ? "known_typed_children_only" : "unavailable");
            coverage.addProperty(
                    "display", a.displayAvailable() ? "native_reader_unverified_semantics" : "unsupported");
            for (Metadata metadata : METADATA)
                coverage.addProperty(metadata.key, a.metadataAvailable(metadata) ? metadata.coverage : "unsupported");
            coverage.addProperty("positionUnits", "native_grid_coordinates_unverified");
            coverage.addProperty("nestedChildren", "not_traversed");
            result.add("coverage", coverage);
        }
        JsonArray unsupported = new JsonArray();
        state.unsupported.forEach(unsupported::add);
        result.add("unsupported", unsupported);
        if (diagnostics) result.addProperty("nativeRevisionAvailable", false);
        if (diagnostics)
            result.addProperty(
                    "identityCaveat",
                    "instanceId is a current native UUID, not typeId or localKey; observed UUIDs changed across project reopen and must be reacquired. localKey and paths may change after edits. Native owning-track channel UUID is corroborated before and after capture, including the SCA alias mapping. Device index and name remain public observations that can lag native rebinding; same-track same-name replacement is not distinguished. No persistent device identity, native device-position corroboration, native ABA detection, native revision or cross-request atomicity.");
        result.addProperty("nativeMutation", false);
        result.addProperty("nativeUndo", false);
        return result;
    }

    JsonObject parameter(
            DeviceSources.Source source,
            UUID moduleId,
            String parameterId,
            GridParameterContext contexts,
            Runnable check) {
        return parameter(source, moduleId, parameterId, contexts, false, check);
    }

    JsonObject parameter(
            DeviceSources.Source source,
            UUID moduleId,
            String parameterId,
            GridParameterContext contexts,
            boolean fullSteps,
            Runnable check) {
        if (contexts == null)
            throw Errors.notApplied(
                    Reason.NATIVE_UNAVAILABLE, "Required parameter inspection bindings are unavailable.");
        ParameterBinding binding = bindParameter(source, moduleId, parameterId, fullSteps, check);
        GridParameterContext.Capture context =
                contexts.capture(binding.device, binding.graph, binding.module, binding.parameter, binding.kind, check);
        ParameterBinding coupled = binding.kind == Kind.PANEL_BOOLEAN ? bindBipolarParameter(binding) : null;
        GridParameterContext.Capture coupledContext = coupled == null
                ? null
                : contexts.capture(
                        coupled.device, coupled.graph, coupled.module, coupled.parameter, coupled.kind, check);
        JsonObject value = parameterValue(binding);
        JsonObject coupledValue = coupled == null ? null : parameterValue(coupled);
        guardParameter(binding);
        contexts.guard(context, binding.device, binding.graph, binding.module, binding.parameter, binding.kind, check);
        guardParameter(binding);
        if (coupled != null) {
            guardBipolarParameter(binding, coupled);
            contexts.guard(
                    coupledContext,
                    coupled.device,
                    coupled.graph,
                    coupled.module,
                    coupled.parameter,
                    coupled.kind,
                    check);
            guardBipolarParameter(binding, coupled);
        }
        JsonObject result = parameterResult(binding, value);
        result.add("editContext", context.json);
        if (coupled != null) {
            result.add("coupledParameter", coupledValue);
            result.add("coupledEditContext", coupledContext.json);
        }
        result.addProperty("nativeMutation", false);
        result.addProperty("nativeUndo", false);
        return result;
    }

    ParameterBinding bindParameter(DeviceSources.Source source, UUID moduleId, String parameterId, Runnable check) {
        return bindParameter(source, moduleId, parameterId, false, check);
    }

    ParameterBinding bindParameter(
            DeviceSources.Source source, UUID moduleId, String parameterId, boolean fullSteps, Runnable check) {
        a.documentThread();
        check.run();
        if (!a.parametersAvailable())
            throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "Required typed parameter bindings are unavailable.");
        NativeTarget target = target(source);
        State state = new State(check);
        state.full = fullSteps;
        Object module = findModule(target.graph, moduleId, state);
        Object child = findParameter(module, parameterId, state);
        Kind kind = parameterKind(child);
        if (kind == null)
            throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "Requested parameter family is unsupported.");
        return new ParameterBinding(source, moduleId, parameterId, target, module, child, kind, state);
    }

    void guardParameter(ParameterBinding binding) {
        binding.state.guard.run();
        guardTarget(binding.target);
        if (findModule(binding.graph, binding.moduleId, binding.state) != binding.module
                || findParameter(binding.module, binding.parameterId, binding.state) != binding.parameter
                || parameterKind(binding.parameter) != binding.kind)
            throw Errors.notApplied(
                    Reason.STALE_CONTEXT, "Selected native module or parameter changed during the operation.");
        a.requireModelThread(binding.parameter);
        binding.state.guard.run();
    }

    ParameterBinding bindBipolarParameter(ParameterBinding panel) {
        Object child = findBipolarParameter(panel);
        String id = text(a.read(Field.PARAMETER_ID, child));
        ParameterBinding result = new ParameterBinding(
                panel.source, panel.moduleId, id, panel.target, panel.module, child, Kind.FLOAT, panel.state);
        guardParameter(result);
        return result;
    }

    void guardBipolarParameter(ParameterBinding panel, ParameterBinding coupled) {
        guardParameter(panel);
        guardParameter(coupled);
        if (findBipolarParameter(panel) != coupled.parameter)
            throw Errors.notApplied(Reason.STALE_CONTEXT, "Boolean panel's unique native bipolar reference changed.");
    }

    private Object findBipolarParameter(ParameterBinding panel) {
        if (panel.kind != Kind.PANEL_BOOLEAN)
            throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "Bipolar dependency requires a mapped boolean panel.");
        Object found = null;
        for (Object child : collect(Field.CHILDREN, panel.module, MAX_CHILDREN, panel.state)) {
            panel.state.check();
            if (!a.is(Kind.BIPOLAR_FLOAT, child)) continue;
            a.requireModelThread(child);
            if (a.read(Field.BIPOLAR_PANEL, child) != panel.parameter) continue;
            if (a.read(Field.OWNER_MODULE, child) != panel.module || parameterKind(child) != Kind.FLOAT)
                throw Errors.notApplied(
                        Reason.STALE_CONTEXT, "Linked bipolar parameter belongs to another module or family.");
            if (found != null)
                throw Errors.notApplied(
                        Reason.NATIVE_UNAVAILABLE,
                        "Boolean panel has multiple linked bipolar parameters; coupled editing is unsupported.");
            found = child;
        }
        if (found == null)
            throw Errors.notApplied(
                    Reason.NATIVE_UNAVAILABLE,
                    "Boolean panel requires one same-module exact mapped bipolar scalar reference for inspection or editing.");
        return found;
    }

    JsonObject parameterValue(ParameterBinding binding) {
        return parameter(binding.parameter, binding.module, true, binding.state);
    }

    /** A scalar control outside any Grid module (a device modulator's own parameter); the caller checks ownership. */
    Kind detachedKind(Object child) {
        Kind kind = parameterKind(child);
        return kind == Kind.FLOAT || kind == Kind.INTEGER || kind == Kind.ENUM || kind == Kind.BOOLEAN ? kind : null;
    }

    JsonObject detachedParameter(Object child, Runnable check, Collection<String> unsupported) {
        if (detachedKind(child) == null)
            throw Errors.notApplied(
                    Reason.UNSUPPORTED_SCOPE, "Only float, integer, enum and boolean controls are supported here.");
        if (a.read(Field.OWNER_MODULE, child) != null)
            throw Errors.notApplied(Reason.STALE_CONTEXT, "Control belongs to a Grid module, not a modulator.");
        State state = new State(check);
        JsonObject result = parameter(child, null, false, state);
        unsupported.addAll(state.unsupported);
        return result;
    }
    /** Module instance UUID and parameter id of a control inside a Grid module, or null when it is not in one. */
    Object ownerModule(Object child) {
        return a.read(Field.OWNER_MODULE, child);
    }

    String moduleId(Object module) {
        return uuid(a.read(Field.INSTANCE_ID, module));
    }

    String parameterId(Object child) {
        return text(a.read(Field.PARAMETER_ID, child));
    }

    Object moduleGraph(Object module) {
        return a.read(Field.MODULE_GRAPH, module);
    }

    JsonObject parameterResult(ParameterBinding binding, JsonObject value) {
        JsonObject result = new JsonObject();
        result.addProperty("moduleId", binding.moduleId.toString());
        result.add("parameter", value);
        result.addProperty("complete", binding.state.unsupported.isEmpty());
        JsonArray unsupported = new JsonArray();
        binding.state.unsupported.forEach(unsupported::add);
        result.add("unsupported", unsupported);
        return result;
    }

    /** Strictly callback-local native binding, shared by inspection and mutation orchestration. */
    static final class ParameterBinding {
        final Object device, graph, module, parameter;
        final Kind kind;
        private final DeviceSources.Source source;
        private final UUID moduleId;
        private final String parameterId;
        private final NativeTarget target;
        private final State state;

        ParameterBinding(
                DeviceSources.Source source,
                UUID moduleId,
                String parameterId,
                NativeTarget target,
                Object module,
                Object parameter,
                Kind kind,
                State state) {
            this.source = source;
            this.moduleId = moduleId;
            this.parameterId = parameterId;
            this.target = target;
            this.device = target.target;
            this.graph = target.graph;
            this.module = module;
            this.parameter = parameter;
            this.kind = kind;
            this.state = state;
        }
    }

    private Object findModule(Object graph, UUID id, State state) {
        Object found = lookupModule(graph, id, state);
        if (found == null)
            throw Errors.notApplied(
                    Reason.STALE_CONTEXT,
                    "Requested module UUID is no longer in the target graph; reacquire grid.get.");
        return found;
    }

    /** The graph's module with this instance UUID, or null when it has none. */
    private Object lookupModule(Object graph, UUID id, State state) {
        Object found = null;
        for (Object module : collect(Field.MODULES, graph, MAX_MODULES, state)) {
            state.check();
            if (!a.is(Kind.MODULE, module)) continue;
            a.requireModelThread(module);
            if (!id.toString().equals(uuid(a.read(Field.INSTANCE_ID, module)))) continue;
            if (found != null) throw Errors.notApplied(Reason.STALE_CONTEXT, "Requested module UUID is ambiguous.");
            if (a.read(Field.MODULE_GRAPH, module) != graph)
                throw Errors.notApplied(Reason.STALE_CONTEXT, "Requested module moved outside the target graph.");
            found = module;
        }
        return found;
    }

    private Object findParameter(Object module, String id, State state) {
        Object found = lookupParameter(module, id, state);
        if (found == null)
            throw Errors.notApplied(
                    Reason.STALE_CONTEXT, "Requested exact parameter ID is no longer in the selected module.");
        return found;
    }

    /** The module's parameter with this id, or null when it has none. */
    private Object lookupParameter(Object module, String id, State state) {
        Object found = null;
        for (Object child : collect(Field.CHILDREN, module, MAX_CHILDREN, state)) {
            state.check();
            if (!a.is(Kind.PARAMETER, child)
                    && !a.is(Kind.PANEL_BOOLEAN, child)
                    && !a.is(Kind.PANEL_STRING, child)
                    && !a.is(Kind.STEP_ARRAY, child)) continue;
            a.requireModelThread(child);
            if (!id.equals(text(a.read(Field.PARAMETER_ID, child)))) continue;
            if (found != null) throw Errors.notApplied(Reason.STALE_CONTEXT, "Requested parameter ID is ambiguous.");
            if (a.read(Field.OWNER_MODULE, child) != module)
                throw Errors.notApplied(Reason.STALE_CONTEXT, "Requested parameter moved outside the selected module.");
            found = child;
        }
        return found;
    }

    @Override
    public Object owner(DeviceSources.Native.Capture device, UUID id, Runnable check) {
        Object graph = a.read(Field.GRAPH, device.device);
        if (graph == null)
            throw Errors.notApplied(Reason.KIND_MISMATCH, "Explicit device does not contain a Grid graph.");
        a.requireModelThread(graph);
        return lookupModule(graph, id, new State(check));
    }

    @Override
    public String ownerName(Object owner) {
        return effectiveModuleName(owner);
    }

    @Override
    public Object parameter(Object owner, String key, Runnable check) {
        return lookupParameter(owner, key, new State(check));
    }

    @Override
    public String parameterName(Object parameter) {
        Object name = a.metadataAvailable(Metadata.PARAMETER_NAMES) ? a.read(Field.PARAMETER_NAME, parameter) : null;
        return name instanceof String ? (String) name : null;
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
                throw Errors.notApplied(
                        Reason.NATIVE_UNAVAILABLE,
                        "Topology editing requires every graph child to be a mapped module.");
            a.requireModelThread(module);
            if (a.read(Field.MODULE_GRAPH, module) != graph)
                throw Errors.notApplied(Reason.STALE_CONTEXT, "Native module belongs to a different graph.");
            Object id = a.read(Field.INSTANCE_ID, module);
            if (!(id instanceof UUID) || !ids.add((UUID) id))
                throw Errors.notApplied(
                        Reason.NATIVE_UNAVAILABLE, "Topology editing requires unique native module UUIDs.");
        }
        return modules;
    }

    /** Current instance UUID of the one module whose native effective name is exactly name. */
    UUID moduleIdNamed(NativeTarget target, String name, Runnable check) {
        if (!a.metadataAvailable(Metadata.MODULE_NAMES))
            throw Errors.notApplied(
                    Reason.NATIVE_UNAVAILABLE, "Native module name readers are required to select a module by name.");
        State state = new State(check);
        UUID found = null;
        for (Object module : collect(Field.MODULES, target.graph, MAX_MODULES, state)) {
            state.check();
            if (!a.is(Kind.MODULE, module)) continue;
            a.requireModelThread(module);
            if (!name.equals(a.read(Field.MODULE_NAME, module))) continue;
            Object id = a.read(Field.INSTANCE_ID, module);
            if (!(id instanceof UUID))
                throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "Native module UUID is unavailable.");
            if (found != null)
                throw Errors.notApplied(
                        Reason.STALE_CONTEXT, "Several modules are named \"" + name + "\"; use moduleId.");
            found = (UUID) id;
        }
        if (found == null)
            throw Errors.notApplied(Reason.STALE_CONTEXT, "No module is named \"" + name + "\"; check grid.get names.");
        return found;
    }

    /** Native effective name when candidate is a mapped Grid module, else null. */
    String effectiveModuleName(Object candidate) {
        if (candidate == null || !a.is(Kind.MODULE, candidate) || !a.metadataAvailable(Metadata.MODULE_NAMES))
            return null;
        Object name = a.read(Field.MODULE_NAME, candidate);
        return name instanceof String ? (String) name : null;
    }

    /** Document-thread-checked device target for device-level (non-module) operations such as voicing. */
    NativeTarget bindTarget(DeviceSources.Source source, Runnable check) {
        a.documentThread();
        check.run();
        return target(source);
    }

    void requireModelThread(Object model) {
        a.requireModelThread(model);
    }

    NativeTarget target(DeviceSources.Source source) {
        DeviceSources.Native.Capture capture;
        try {
            capture = targets.capture(source, () -> {});
        } catch (RpcException error) {
            if (error.is(Reason.UNSUPPORTED_SCOPE) && source.layer == null)
                throw Errors.notApplied(
                        Reason.KIND_MISMATCH, "Explicit native device has no mapped Grid graph accessor.");
            throw error;
        }
        Object graph = a.read(Field.GRAPH, capture.device);
        if (graph == null)
            throw Errors.notApplied(Reason.KIND_MISMATCH, "Explicit device does not contain a Grid graph.");
        a.requireModelThread(graph);
        return new NativeTarget(capture, graph);
    }

    void guardTarget(NativeTarget binding) {
        targets.guard(binding.ownership, () -> {});
        if (a.read(Field.GRAPH, binding.target) != binding.graph)
            throw Errors.notApplied(Reason.STALE_CONTEXT, "Explicit native graph changed during the read.");
        a.requireModelThread(binding.graph);
    }

    static final class NativeTarget {
        final Object target, graph;
        final DeviceSources.Native.Capture ownership;

        NativeTarget(DeviceSources.Native.Capture ownership, Object graph) {
            this.ownership = ownership;
            this.target = ownership.device;
            this.graph = graph;
        }
    }

    private Kind parameterKind(Object child) {
        for (Kind candidate : PARAMETER_KINDS) if (a.is(candidate, child)) return candidate;
        return null;
    }

    private JsonArray ports(
            Object module,
            Object graph,
            IdentityHashMap<Object, String> ids,
            boolean input,
            JsonArray connections,
            Set<Object> enumeratedPorts,
            boolean diagnostics,
            State state) {
        JsonArray ports = new JsonArray();
        Set<String> keys = new HashSet<>();
        for (Object port : collect(input ? Field.INPUTS : Field.OUTPUTS, module, MAX_CHILDREN, state)) {
            state.check();
            if (!a.is(input ? Kind.INPUT : Kind.OUTPUT, port)) {
                state.unsupported("unknown_port_type");
                continue;
            }
            a.requireModelThread(port);
            if (a.read(Field.OWNER_MODULE, port) != module)
                throw Errors.notApplied(Reason.STALE_CONTEXT, "Port owner differs from its enumerated module.");
            String key = text(a.read(input ? Field.INPUT_KEY : Field.OUTPUT_KEY, port));
            if (!keys.add(key))
                throw Errors.notApplied(
                        Reason.NATIVE_UNAVAILABLE, "Duplicate native port key; endpoint identity is ambiguous.");
            enumeratedPorts.add(port);
            JsonObject item = new JsonObject();
            item.addProperty("key", key);
            if (diagnostics) {
                item.addProperty("modelClass", text(port.getClass().getName()));
                item.addProperty("path", text(a.read(Field.PATH, port)));
            }
            metadataText(
                    item,
                    "name",
                    input ? Field.INPUT_NAME : Field.OUTPUT_NAME,
                    port,
                    Metadata.PORT_NAMES,
                    state,
                    input ? "input_name" : "output_name");
            ports.add(item);
            if (!input) continue;
            Object source = a.read(Field.INPUT_SOURCE, port);
            item.addProperty("connected", source != null);
            if (source == null) continue;
            if (!a.is(Kind.OUTPUT, source)) {
                state.unsupported("unknown_connection_source_type");
                continue;
            }
            a.requireModelThread(source);
            Object owner = a.read(Field.OWNER_MODULE, source);
            if (!a.is(Kind.MODULE, owner) || a.read(Field.MODULE_GRAPH, owner) != graph || !ids.containsKey(owner)) {
                state.unsupported("connection_source_outside_graph");
                continue;
            }
            a.requireModelThread(owner);
            JsonObject connection = new JsonObject();
            connection.addProperty("sourceModuleId", ids.get(owner));
            connection.addProperty("sourcePortKey", text(a.read(Field.OUTPUT_KEY, source)));
            connection.addProperty("targetModuleId", ids.get(module));
            connection.addProperty("targetPortKey", key);
            connections.add(connection);
        }
        return ports;
    }

    private JsonObject parameter(Object child, Object module, boolean diagnostics, State state) {
        JsonObject result = new JsonObject();
        Kind kind = parameterKind(child);
        if (kind == null && isInternal(child)) {
            result.addProperty("supported", false);
            result.addProperty("reason", "internal_child_model");
            result.addProperty("internal", true);
            result.addProperty("modelClass", child.getClass().getName());
            return result;
        }
        if (kind == null) {
            result.addProperty("supported", false);
            result.addProperty("reason", "unknown_child_model");
            result.addProperty(
                    "modelClass", child == null ? "null" : child.getClass().getName());
            state.unsupported("unknown_child_model");
            return result;
        }
        a.requireModelThread(child);
        if (a.read(Field.OWNER_MODULE, child) != module)
            throw Errors.notApplied(Reason.STALE_CONTEXT, "Parameter belongs to a different module.");
        if (kind == Kind.STEP_ARRAY) return stepArray(child, module, diagnostics, state);
        boolean panel = kind == Kind.PANEL_BOOLEAN || kind == Kind.PANEL_STRING;
        String panelFamily = kind == Kind.PANEL_STRING ? "string_panel" : "boolean_panel";
        result.addProperty("supported", true);
        result.addProperty(
                "kind",
                panel
                        ? (kind == Kind.PANEL_STRING ? "string" : "boolean")
                        : kind.name().toLowerCase(Locale.ROOT));
        if (diagnostics) result.addProperty("path", text(a.read(Field.PATH, child)));
        if (panel) {
            result.addProperty("controlFamily", panelFamily);
            result.addProperty("parameterId", text(a.read(Field.PARAMETER_ID, child)));
            result.add("name", JsonNull.INSTANCE);
            result.add("shortName", JsonNull.INSTANCE);
            state.unsupported(panelFamily + "_labels_unmapped");
        } else {
            metadataText(
                    result, "parameterId", Field.PARAMETER_ID, child, Metadata.PARAMETER_NAMES, state, "parameter_id");
            metadataText(
                    result, "name", Field.PARAMETER_NAME, child, Metadata.PARAMETER_NAMES, state, "parameter_name");
            metadataText(
                    result,
                    "shortName",
                    Field.SHORT_NAME,
                    child,
                    Metadata.PARAMETER_NAMES,
                    state,
                    "parameter_short_name");
        }
        switch (kind) {
            case PANEL_STRING:
                result.addProperty("value", text(a.read(Field.STRING_VALUE, child)));
                break;
            case FLOAT: {
                double value = finite(a.read(Field.FLOAT_VALUE, child));
                Object definition = a.read(Field.FLOAT_DEFINITION, child);
                double min = finite(a.read(Field.FLOAT_MIN, definition)),
                        max = finite(a.read(Field.FLOAT_MAX, definition));
                Object unit = metadataValue(Field.FLOAT_UNIT, definition, Metadata.FLOAT_UNITS, state, "numeric_unit");
                metadataText(
                        result, "numericUnit", Field.UNIT_TOKEN, unit, Metadata.FLOAT_UNITS, state, "numeric_unit");
                range(min, max);
                result.addProperty("value", value);
                result.addProperty("minimum", min);
                result.addProperty("maximum", max);
                break;
            }
            case PANEL_BOOLEAN:
            case BOOLEAN: {
                Object value = a.read(Field.BOOLEAN_VALUE, child);
                if (!(value instanceof Boolean))
                    throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "Invalid boolean parameter value.");
                result.addProperty("value", (Boolean) value);
                break;
            }
            case INTEGER: {
                int value = integer(a.read(Field.INTEGER_VALUE, child));
                Object definition = a.read(Field.INTEGER_DEFINITION, child);
                int min = integer(a.read(Field.INTEGER_MIN, definition)),
                        max = integer(a.read(Field.INTEGER_MAX, definition));
                metadataText(
                        result,
                        "displayPrefix",
                        Field.INTEGER_PREFIX,
                        definition,
                        Metadata.INTEGER_AFFIXES,
                        state,
                        "integer_display_prefix");
                metadataText(
                        result,
                        "displaySuffix",
                        Field.INTEGER_SUFFIX,
                        definition,
                        Metadata.INTEGER_AFFIXES,
                        state,
                        "integer_display_suffix");
                range(min, max);
                result.addProperty("value", value);
                result.addProperty("minimum", min);
                result.addProperty("maximum", max);
                break;
            }
            case ENUM: {
                int value = integer(a.read(Field.ENUM_VALUE, child));
                result.addProperty("value", value);
                Object definition = a.read(Field.ENUM_DEFINITION, child);
                Object options = a.read(Field.OPTIONS, definition);
                if (!(options instanceof List<?>))
                    throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "Invalid native enum choices.");
                List<?> list = (List<?>) options;
                if (list.size() > MAX_OPTIONS)
                    throw Errors.notApplied(Reason.OUT_OF_BOUNDS, "Enum exceeds 256 choices.");
                JsonArray choices = new JsonArray();
                boolean found = false;
                Set<Integer> seen = new HashSet<>();
                for (Object option : list) {
                    state.check();
                    if (!a.is(Kind.OPTION, option))
                        throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "Unknown enum option model.");
                    int number = integer(a.read(Field.OPTION_VALUE, option));
                    if (!seen.add(number))
                        throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "Duplicate native enum choice values.");
                    found |= number == value;
                    JsonObject choice = new JsonObject();
                    choice.addProperty("value", number);
                    choice.addProperty("label", text(a.read(Field.OPTION_LABEL, option)));
                    choices.add(choice);
                }
                result.add("options", choices);
                result.addProperty("valueInOptions", found);
                if (!found) state.unsupported("enum_value_not_in_options");
                break;
            }
            default:
                throw new AssertionError(kind);
        }
        if (panel) {
            result.add("display", JsonNull.INSTANCE);
            state.unsupported(panelFamily + "_display_unmapped");
        } else if (a.displayAvailable()) result.addProperty("display", text(a.read(Field.DISPLAY, child)));
        else {
            result.add("display", JsonNull.INSTANCE);
            state.unsupported("parameter_display_unavailable");
        }
        return result;
    }

    private static boolean isInternal(Object child) {
        if (child == null) return false;
        String name = child.getClass().getName();
        return name.equals(INTERNAL_KIY)
                || INTERNAL_MODELS.contains(name.substring(Math.max(name.lastIndexOf('.'), name.lastIndexOf('$')) + 1));
    }

    /** Entries exposed for a step array: the STEPS prefix by default, up to 256 with full. */
    static int exposedSteps(int arrayLength, Integer stepCount, boolean full) {
        return Math.min(arrayLength, full ? STEP_FULL : stepCount != null ? stepCount : STEP_FALLBACK);
    }

    /** The native array as the model sees it: padded with zeros or truncated to its declared length. */
    static float[] effectiveSteps(Object raw, int length) {
        if (!(raw instanceof float[]))
            throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "Native step array is not a float array.");
        if (length < 0 || length > 65536)
            throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "Native step array length is out of range.");
        return java.util.Arrays.copyOf((float[]) raw, length);
    }

    private JsonObject stepArray(Object child, Object module, boolean diagnostics, State state) {
        JsonObject result = new JsonObject();
        result.addProperty("supported", true);
        result.addProperty("kind", "steps");
        if (diagnostics) result.addProperty("path", text(a.read(Field.PATH, child)));
        result.addProperty("controlFamily", "step_array");
        result.addProperty("parameterId", text(a.read(Field.PARAMETER_ID, child)));
        result.add("name", JsonNull.INSTANCE);
        result.add("shortName", JsonNull.INSTANCE);
        int length = integer(a.read(Field.STEP_LENGTH, child));
        float[] steps = effectiveSteps(a.read(Field.STEP_VALUES, child), length);
        Integer count = stepCount(module, state);
        if (count == null) state.unsupported("step_count_unavailable");
        int exposed = exposedSteps(length, count, state.full);
        JsonArray values = new JsonArray();
        for (int i = 0; i < exposed; i++) {
            if (!Float.isFinite(steps[i]))
                throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "Native step value is nonfinite.");
            values.add(new JsonPrimitive(new java.math.BigDecimal(Float.toString(steps[i]))));
        }
        if (state.full && length > STEP_FULL) state.unsupported("step_array_longer_than_full_window");
        result.addProperty("arrayLength", length);
        if (count == null) result.add("stepCount", JsonNull.INSTANCE);
        else result.addProperty("stepCount", count);
        result.add("values", values);
        result.addProperty("valuesComplete", exposed == length);
        result.addProperty("minimum", STEP_MIN);
        result.addProperty("maximum", STEP_MAX);
        result.addProperty("valueDomain", "finite_sanity_bound_not_a_module_range");
        result.add("display", JsonNull.INSTANCE);
        return result;
    }

    /** The unique same-module STEPS integer, or null when absent or ambiguous. */
    private Integer stepCount(Object module, State state) {
        Integer found = null;
        for (Object sibling : collect(Field.CHILDREN, module, MAX_CHILDREN, state)) {
            state.check();
            if (parameterKind(sibling) != Kind.INTEGER) continue;
            a.requireModelThread(sibling);
            if (!"STEPS".equals(a.read(Field.PARAMETER_ID, sibling))) continue;
            if (found != null) return null;
            found = integer(a.read(Field.INTEGER_VALUE, sibling));
        }
        return found;
    }

    private Object metadataValue(Field field, Object owner, Metadata metadata, State state, String reason) {
        if (!a.metadataAvailable(metadata) || owner == null) {
            state.unsupported(reason + "_unavailable");
            return null;
        }
        try {
            Object value = a.read(field, owner);
            if (value == null) state.unsupported(reason + "_unavailable");
            return value;
        } catch (RpcException error) {
            // Optional descriptive reads never suppress identity, ownership, cancellation or size failures.
            if (!error.is(Reason.NATIVE_UNAVAILABLE) && !error.is(Reason.HOST_MISMATCH)) throw error;
            state.unsupported(reason + "_read_failed");
            return null;
        }
    }

    private void metadataText(
            JsonObject result, String key, Field field, Object owner, Metadata metadata, State state, String reason) {
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
        if (!(value instanceof Stream<?>))
            throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "Native " + field + " reader did not return a stream.");
        List<Object> result = new ArrayList<>();
        try (Stream<?> stream = (Stream<?>) value) {
            Iterator<?> iterator = stream.iterator();
            while (iterator.hasNext()) {
                state.check();
                if (result.size() == maximum)
                    throw Errors.notApplied(
                            Reason.OUT_OF_BOUNDS,
                            field + " exceeds traversal limit " + maximum + "; no fake partial graph returned.");
                result.add(iterator.next());
            }
        }
        return result;
    }

    static JsonObject window(int offset, int limit, int total) {
        JsonObject result = Results.window(offset, limit, total);
        result.addProperty("limit", limit);
        return result;
    }

    private static String uuid(Object value) {
        if (!(value instanceof UUID))
            throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "Native UUID identity is unavailable.");
        return value.toString();
    }

    static String text(Object value) {
        if (!(value instanceof String))
            throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "Native text field is unavailable.");
        String text = (String) value;
        if (text.length() > MAX_TEXT)
            throw Errors.notApplied(Reason.OUT_OF_BOUNDS, "Native text field exceeds 1024 characters.");
        return text;
    }

    private static int integer(Object value) {
        if (!(value instanceof Integer))
            throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "Native integer field has an unexpected type.");
        return (Integer) value;
    }

    private static double finite(Object value) {
        if (!(value instanceof Double) || !Double.isFinite((Double) value))
            throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "Native floating value is nonfinite or unsupported.");
        return (Double) value;
    }

    private static void range(double min, double max) {
        if (min > max) throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "Native parameter range is inverted.");
    }

    private static final class CatalogEntry {
        final String id, name;

        CatalogEntry(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    static final class State {
        final Runnable guard;
        final Set<String> unsupported = new LinkedHashSet<>();
        int work;
        boolean full;

        State(Runnable guard) {
            this.guard = guard;
        }

        void check() {
            guard.run();
            if (++work > MAX_ITEMS)
                throw Errors.notApplied(
                        Reason.OUT_OF_BOUNDS, "Grid read exceeds total traversal budget; reduce moduleLimit.");
        }

        void unsupported(String reason) {
            unsupported.add(reason);
        }
    }
}
