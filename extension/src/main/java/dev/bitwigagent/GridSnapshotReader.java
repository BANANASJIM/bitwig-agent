package dev.bitwigagent;

import com.google.gson.*;
import java.util.*;
import java.util.stream.Stream;
import static dev.bitwigagent.HostServices.failure;

/** Document-thread traversal, independent of version-specific symbol resolution and RPC targeting. */
final class GridSnapshotReader {
    static final int MAX_MODULES = 4096, MAX_CHILDREN = 256, MAX_OPTIONS = 256, MAX_CATALOG = 16384;
    static final int MAX_ITEMS = 16384, MAX_TEXT = 1024;
    enum Kind { DEVICE, MODULE, INPUT, OUTPUT, FLOAT, BOOLEAN, INTEGER, ENUM, ENTRY, OPTION }
    private static final Kind[] PARAMETER_KINDS = {Kind.FLOAT, Kind.BOOLEAN, Kind.INTEGER, Kind.ENUM};
    enum Field {
        TARGET, GRAPH, EXECUTOR, MODULES, INSTANCE_ID, LOCAL_KEY, X, Y, DESCRIPTOR, TYPE_ID,
        INPUTS, OUTPUTS, CHILDREN, MODULE_GRAPH, OWNER_MODULE, INPUT_SOURCE, INPUT_KEY, OUTPUT_KEY,
        PATH, FLOAT_VALUE, FLOAT_DEFINITION, FLOAT_MIN, FLOAT_MAX, BOOLEAN_VALUE,
        INTEGER_VALUE, INTEGER_DEFINITION, INTEGER_MIN, INTEGER_MAX, ENUM_VALUE, ENUM_DEFINITION,
        OPTIONS, OPTION_VALUE, OPTION_LABEL, DISPLAY, CATALOG, ENTRY_ID, ENTRY_NAME, ENTRY_KIND,
        MODULE_NAME, CUSTOM_NAME, TYPE_ENTRY, TYPE_NAME, PARAMETER_ID, PARAMETER_NAME, SHORT_NAME,
        INPUT_NAME, OUTPUT_NAME, FLOAT_UNIT, UNIT_TOKEN, INTEGER_PREFIX, INTEGER_SUFFIX
    }
    enum Metadata {
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
    interface Access {
        Object read(Field field, Object object);
        boolean is(Kind kind, Object object);
        void documentThread();
        void owned(Object object);
        boolean parametersAvailable();
        boolean displayAvailable();
        boolean metadataAvailable(Metadata metadata);
    }
    private final Access a;
    GridSnapshotReader(Access access) { a = access; }

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

    JsonObject snapshot(Object proxy, int offset, int limit, boolean parameters, Runnable check) {
        a.documentThread(); check.run();
        Object target = a.read(Field.TARGET, proxy);
        if (target == null) throw failure("missing_target", "Explicit device proxy has no native target; refresh devices.get.");
        if (!a.is(Kind.DEVICE, target)) throw failure("not_grid", "Explicit native device has no mapped Grid graph accessor.");
        a.owned(target);
        Object graph = a.read(Field.GRAPH, target);
        if (graph == null) throw failure("not_grid", "Explicit device does not contain a Grid graph.");
        a.owned(graph);
        State state = new State(check);
        List<Object> all = collect(Field.MODULES, graph, MAX_MODULES, state);
        IdentityHashMap<Object, String> ids = new IdentityHashMap<>();
        Set<String> seenIds = new HashSet<>();
        for (Object module : all) {
            state.check();
            if (!a.is(Kind.MODULE, module)) { state.unsupported("unknown_graph_child"); continue; }
            a.owned(module);
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
            item.addProperty("instanceId", ids.get(module));
            item.addProperty("localKey", text(a.read(Field.LOCAL_KEY, module)));
            metadataText(item, "name", Field.MODULE_NAME, module, Metadata.MODULE_NAMES, state, "module_name");
            metadataText(item, "customName", Field.CUSTOM_NAME, module, Metadata.MODULE_NAMES, state, "module_custom_name");
            item.addProperty("x", integer(a.read(Field.X, module)));
            item.addProperty("y", integer(a.read(Field.Y, module)));
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
        if (a.read(Field.TARGET, proxy) != target || a.read(Field.GRAPH, target) != graph)
            throw failure("stale_context", "Explicit native device or graph changed during the read.");
        a.owned(target); a.owned(graph);
        JsonObject result = HostServices.observed();
        result.add("modules", modules); result.add("connections", connections);
        result.add("window", window(offset, limit, all.size()));
        result.addProperty("complete", state.unsupported.isEmpty());
        JsonObject coverage = new JsonObject();
        coverage.addProperty("structure", "mapped_modules_and_ports");
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
        result.addProperty("identityCaveat", "instanceId is a current native UUID, not typeId or localKey; observed UUIDs changed across project reopen and must be reacquired. localKey and paths may change after edits. Public bank observations can lag native rebinding; same-name replacement before the document read is not corroborated by native track UUID. No native revision or cross-request atomicity.");
        result.addProperty("nativeMutation", false); result.addProperty("nativeUndo", false);
        return result;
    }

    private JsonArray ports(Object module, Object graph, IdentityHashMap<Object, String> ids,
            boolean input, JsonArray connections, Set<Object> enumeratedPorts, State state) {
        JsonArray ports = new JsonArray(); Set<String> keys = new HashSet<>();
        for (Object port : collect(input ? Field.INPUTS : Field.OUTPUTS, module, MAX_CHILDREN, state)) {
            state.check();
            if (!a.is(input ? Kind.INPUT : Kind.OUTPUT, port)) { state.unsupported("unknown_port_type"); continue; }
            a.owned(port);
            if (a.read(Field.OWNER_MODULE, port) != module) throw failure("stale_context", "Port owner differs from its enumerated module.");
            String key = text(a.read(input ? Field.INPUT_KEY : Field.OUTPUT_KEY, port));
            if (!keys.add(key)) throw failure("grid_unavailable", "Duplicate native port key; endpoint identity is ambiguous.");
            enumeratedPorts.add(port);
            JsonObject item = new JsonObject(); item.addProperty("key", key);
            item.addProperty("path", text(a.read(Field.PATH, port)));
            metadataText(item, "name", input ? Field.INPUT_NAME : Field.OUTPUT_NAME, port,
                    Metadata.PORT_NAMES, state, input ? "input_name" : "output_name");
            ports.add(item);
            if (!input) continue;
            Object source = a.read(Field.INPUT_SOURCE, port);
            item.addProperty("connected", source != null);
            if (source == null) continue;
            if (!a.is(Kind.OUTPUT, source)) { state.unsupported("unknown_connection_source_type"); continue; }
            a.owned(source);
            Object owner = a.read(Field.OWNER_MODULE, source);
            if (!a.is(Kind.MODULE, owner) || a.read(Field.MODULE_GRAPH, owner) != graph || !ids.containsKey(owner)) {
                state.unsupported("connection_source_outside_graph"); continue;
            }
            a.owned(owner);
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
        Kind kind = null;
        for (Kind candidate : PARAMETER_KINDS)
            if (a.is(candidate, child)) { kind = candidate; break; }
        if (kind == null) {
            result.addProperty("supported", false); result.addProperty("reason", "unknown_child_model");
            result.addProperty("modelClass", child == null ? "null" : child.getClass().getName());
            state.unsupported("unknown_child_model"); return result;
        }
        a.owned(child);
        if (a.read(Field.OWNER_MODULE, child) != module) throw failure("stale_context", "Parameter belongs to a different module.");
        result.addProperty("supported", true); result.addProperty("kind", kind.name().toLowerCase(Locale.ROOT));
        result.addProperty("path", text(a.read(Field.PATH, child)));
        metadataText(result, "parameterId", Field.PARAMETER_ID, child, Metadata.PARAMETER_NAMES, state, "parameter_id");
        metadataText(result, "name", Field.PARAMETER_NAME, child, Metadata.PARAMETER_NAMES, state, "parameter_name");
        metadataText(result, "shortName", Field.SHORT_NAME, child, Metadata.PARAMETER_NAMES, state, "parameter_short_name");
        switch (kind) {
            case FLOAT: {
                double value = finite(a.read(Field.FLOAT_VALUE, child));
                Object definition = a.read(Field.FLOAT_DEFINITION, child);
                double min = finite(a.read(Field.FLOAT_MIN, definition)), max = finite(a.read(Field.FLOAT_MAX, definition));
                Object unit = metadataValue(Field.FLOAT_UNIT, definition, Metadata.FLOAT_UNITS, state, "numeric_unit");
                metadataText(result, "numericUnit", Field.UNIT_TOKEN, unit, Metadata.FLOAT_UNITS, state, "numeric_unit");
                range(min, max); result.addProperty("value", value); result.addProperty("minimum", min); result.addProperty("maximum", max); break;
            }
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
        if (a.displayAvailable()) result.addProperty("display", text(a.read(Field.DISPLAY, child)));
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

    private List<Object> collect(Field field, Object owner, int maximum, State state) {
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
    private static String text(Object value) {
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
    private static final class State {
        final Runnable guard; final Set<String> unsupported = new LinkedHashSet<>(); int work;
        State(Runnable guard) { this.guard = guard; }
        void check() {
            guard.run();
            if (++work > MAX_ITEMS) throw failure("grid_bounds_exceeded", "Grid read exceeds total traversal budget; reduce moduleLimit.");
        }
        void unsupported(String reason) { unsupported.add(reason); }
    }
}
