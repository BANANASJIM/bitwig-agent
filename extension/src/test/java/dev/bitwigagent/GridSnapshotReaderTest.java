package dev.bitwigagent;

import com.google.gson.*;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Stream;
import static dev.bitwigagent.GridSnapshotReader.Field.*;
import static dev.bitwigagent.GridSnapshotReader.Kind.*;

/** Semantic fake model tests, not obfuscated-name echo tests or evidence of native compatibility. */
public final class GridSnapshotReaderTest {
    private static int assertions;
    public static void main(String[] args) {
        typedValuesAndIdentityAreNotConflated();
        pageIncludesIncomingEdgesFromOutsidePage();
        unsupportedModelsAndExternalEdgesAreExplicit();
        excludedPortChildrenRemainExplicit();
        omittedAndUnavailableParametersAreNotComplete();
        rejectsMissingWrongAndStaleTargets();
        boundsCloseStreamsAndHonorCancellation();
        catalogFiltersKindsSortsAndPaginates();
        rejectsInvalidNativeValuesAndOwnership();
        System.out.println("GridSnapshotReaderTest: " + assertions + " assertions passed (semantic fake models only)");
    }
    private static void typedValuesAndIdentityAreNotConflated() {
        Fixture f = new Fixture();
        Node floating = f.parameter(FLOAT, f.first).put(FLOAT_VALUE, 0.75);
        floating.put(FLOAT_DEFINITION, new Node().put(FLOAT_MIN, -2.0).put(FLOAT_MAX, 4.0));
        Node bool = f.parameter(BOOLEAN, f.first).put(BOOLEAN_VALUE, true);
        Node integer = f.parameter(INTEGER, f.first).put(INTEGER_VALUE, 7);
        integer.put(INTEGER_DEFINITION, new Node().put(INTEGER_MIN, -10).put(INTEGER_MAX, 12));
        Node indexed = f.parameter(ENUM, f.first).put(ENUM_VALUE, 42);
        indexed.put(ENUM_DEFINITION, new Node().put(OPTIONS, List.of(option(10, "Ten"), option(42, "Answer"))));
        f.first.put(CHILDREN, List.of(floating, bool, integer, indexed, f.input, f.output));
        JsonObject result = f.read(0, 64, true);
        JsonObject module = result.getAsJsonArray("modules").get(0).getAsJsonObject();
        check(!module.get("instanceId").equals(module.get("typeId")), "instance UUID differs from catalog type UUID");
        check(module.get("localKey").getAsString().equals("0"), "list-local key remains separate");
        check(module.get("x").getAsInt() == 7 && module.get("y").getAsInt() == 3, "asymmetric native coordinates preserved");
        JsonArray values = module.getAsJsonArray("parameters");
        check(values.size() == 4, "ports are not parameters");
        check(values.get(0).getAsJsonObject().get("value").getAsDouble() == .75, "raw float not normalized");
        check(values.get(0).getAsJsonObject().get("minimum").getAsDouble() == -2, "native range retained");
        check(values.get(1).getAsJsonObject().get("value").getAsBoolean(), "boolean is a JSON boolean");
        check(values.get(2).getAsJsonObject().get("value").getAsInt() == 7, "integer retained");
        JsonObject choice = values.get(3).getAsJsonObject();
        check(choice.get("value").getAsInt() == 42 && choice.getAsJsonArray("options").get(1).getAsJsonObject().get("value").getAsInt() == 42,
                "enum values are not ordinals");
        check(choice.get("valueInOptions").getAsBoolean(), "enum membership checked");
        check(!result.get("nativeUndo").getAsBoolean() && !result.get("nativeMutation").getAsBoolean(), "read never claims mutation or undo");
        check(!result.get("nativeRevisionAvailable").getAsBoolean(), "no invented revision");
        check(f.closed == 7, "module, port and child streams all closed");
    }
    private static void pageIncludesIncomingEdgesFromOutsidePage() {
        Fixture f = new Fixture();
        Node secondInput = f.port(INPUT, f.second, "destination"); secondInput.put(INPUT_SOURCE, f.output);
        f.second.put(INPUTS, List.of(secondInput));
        JsonObject result = f.read(1, 1, true);
        check(result.getAsJsonArray("modules").size() == 1, "exact module page");
        JsonObject edge = result.getAsJsonArray("connections").get(0).getAsJsonObject();
        check(edge.get("sourceModuleId").getAsString().equals(f.first.values.get(INSTANCE_ID).toString()), "off-page source identified by UUID");
        check(edge.get("targetModuleId").getAsString().equals(f.second.values.get(INSTANCE_ID).toString()), "returned destination identified");
        check(!result.get("complete").getAsBoolean() && !result.getAsJsonObject("window").get("complete").getAsBoolean(), "page is not full graph");
        result = f.read(99, 3, true);
        check(result.getAsJsonArray("modules").isEmpty() && result.getAsJsonObject("window").get("total").getAsInt() == 2, "past-end page has honest total");
    }
    private static void unsupportedModelsAndExternalEdgesAreExplicit() {
        Fixture f = new Fixture();
        Node external = f.module(8, new Node());
        f.input.put(INPUT_SOURCE, f.port(OUTPUT, external, "outside"));
        f.first.put(CHILDREN, List.of(new Node())); f.first.put(DESCRIPTOR, null);
        f.graph.put(MODULES, List.of(f.first, new Node(), f.second));
        JsonObject result = f.read(0, 64, true);
        check(result.getAsJsonArray("connections").isEmpty(), "external graph source not falsely emitted");
        JsonObject unknown = result.getAsJsonArray("modules").get(0).getAsJsonObject().getAsJsonArray("parameters").get(0).getAsJsonObject();
        check(!unknown.get("supported").getAsBoolean() && unknown.get("reason").getAsString().equals("unknown_child_model"), "unknown child is explicit");
        for (String reason : List.of("unknown_graph_child", "module_type_id_unavailable", "connection_source_outside_graph", "unknown_child_model"))
            check(has(result, reason), "coverage explains " + reason);
        check(!result.get("complete").getAsBoolean(), "unknown data cannot claim full coverage");
    }
    private static void excludedPortChildrenRemainExplicit() {
        Fixture f = new Fixture();
        Node hidden = f.port(OUTPUT, f.first, "filtered-native-output");
        f.first.put(CHILDREN, List.of(f.input, f.output, hidden));
        JsonObject result = f.read(0, 64, true);
        check(!result.get("complete").getAsBoolean() && has(result, "port_child_not_enumerated"),
                "a native port child excluded from the port stream cannot silently disappear from complete coverage");
    }
    private static void omittedAndUnavailableParametersAreNotComplete() {
        Fixture f = new Fixture(); f.parameters = false;
        JsonObject result = f.read(0, 64, true);
        check(has(result, "typed_parameter_binding_unavailable"), "structure still works without typed binding");
        check(result.getAsJsonArray("modules").size() == 2 && !result.get("complete").getAsBoolean(), "partial capability does not fake complete");
        result = f.read(0, 64, false); check(has(result, "parameters_not_requested"), "omitted parameters explicitly incomplete");
        f.parameters = true; f.display = false;
        f.first.put(CHILDREN, List.of(f.parameter(BOOLEAN, f.first).put(BOOLEAN_VALUE, false)));
        result = f.read(0, 64, true); check(has(result, "parameter_display_unavailable"), "missing display separate from typed value");
    }
    private static void rejectsMissingWrongAndStaleTargets() {
        Fixture f = new Fixture(); f.proxy.put(TARGET, null); expect("missing_target", () -> f.read(0, 64, true));
        f.proxy.put(TARGET, new Node()); expect("not_grid", () -> f.read(0, 64, true));
        f.proxy.put(TARGET, f.device); f.device.put(GRAPH, null); expect("not_grid", () -> f.read(0, 64, true));
        f.device.put(GRAPH, f.graph);
        f.afterModules = () -> f.proxy.put(TARGET, new Node(DEVICE));
        expect("stale_context", () -> f.read(0, 64, true));
        check(f.closed > 0, "stale read still closes streams");
        Fixture other = new Fixture(); other.document = false; expect("grid_unavailable", () -> other.read(0, 64, true));
    }
    private static void boundsCloseStreamsAndHonorCancellation() {
        Fixture f = new Fixture();
        f.graph.put(MODULES, (Supplier<Stream<?>>) () -> Stream.generate(() -> f.first).onClose(() -> f.closed++));
        expect("grid_bounds_exceeded", () -> f.read(0, 64, true)); check(f.closed == 1, "infinite stream bounded and closed");
        Fixture cancelled = new Fixture();
        int[] calls = {0};
        expect("cancelled", () -> cancelled.reader.snapshot(cancelled.proxy, 0, 64, true, () -> {
            if (++calls[0] == 3) throw HostServices.failure("cancelled", "cancelled mid-traversal");
        }));
        check(cancelled.closed == 1, "cancellation closes active stream");
        Fixture children = new Fixture(); children.first.put(CHILDREN, Collections.nCopies(257, new Node()));
        expect("grid_bounds_exceeded", () -> children.read(0, 64, true));
        Fixture choices = new Fixture(); Node indexed = choices.parameter(ENUM, choices.first).put(ENUM_VALUE, 0);
        indexed.put(ENUM_DEFINITION, new Node().put(OPTIONS, Collections.nCopies(257, option(0, "x"))));
        choices.first.put(CHILDREN, List.of(indexed)); expect("grid_bounds_exceeded", () -> choices.read(0, 64, true));
    }
    private static void catalogFiltersKindsSortsAndPaginates() {
        Fixture f = new Fixture();
        f.catalog = List.of(entry("MODULE", "zeta", 4), entry("DEVICE", "alpha", 5), entry("MODULE", "Alpha", 3), entry("MODULE", "alpha", 2));
        JsonObject result = f.reader.catalog("ALP", 1, 1, () -> {});
        check(result.getAsJsonArray("entries").size() == 1, "bounded filtered catalog");
        check(result.getAsJsonArray("entries").get(0).getAsJsonObject().get("name").getAsString().equals("alpha"), "deterministic case-insensitive sorting tie-break");
        check(result.getAsJsonObject("window").get("total").getAsInt() == 2, "total after MODULE-only filter");
        result = f.reader.catalog("no match", 0, 64, () -> {});
        check(result.getAsJsonObject("window").get("complete").getAsBoolean(), "genuine empty catalog match is complete");
        f.catalog = Collections.nCopies(GridSnapshotReader.MAX_CATALOG + 1, entry("MODULE", "x", 1));
        expect("grid_bounds_exceeded", () -> f.reader.catalog("", 0, 64, () -> {}));
    }
    private static void rejectsInvalidNativeValuesAndOwnership() {
        Fixture f = new Fixture(); Node floating = f.parameter(FLOAT, f.first).put(FLOAT_VALUE, Double.NaN);
        f.first.put(CHILDREN, List.of(floating)); expect("grid_unavailable", () -> f.read(0, 64, true));
        floating.put(FLOAT_VALUE, 1.0).put(FLOAT_DEFINITION, new Node().put(FLOAT_MIN, 4.0).put(FLOAT_MAX, 2.0));
        expect("grid_unavailable", () -> f.read(0, 64, true));
        Fixture owners = new Fixture(); owners.input.put(OWNER_MODULE, owners.second); expect("stale_context", () -> owners.read(0, 64, true));
        Fixture executor = new Fixture(); executor.first.owned = false; expect("grid_unavailable", () -> executor.read(0, 64, true));
        Fixture identity = new Fixture(); identity.second.put(INSTANCE_ID, identity.first.values.get(INSTANCE_ID));
        expect("grid_unavailable", () -> identity.read(0, 64, true));
        Fixture enums = new Fixture(); Node indexed = enums.parameter(ENUM, enums.first).put(ENUM_VALUE, 99);
        indexed.put(ENUM_DEFINITION, new Node().put(OPTIONS, List.of(option(2, "Two")))); enums.first.put(CHILDREN, List.of(indexed));
        check(has(enums.read(0, 64, true), "enum_value_not_in_options"), "unknown enum value is not relabeled ordinal");
    }
    private static Node option(int value, String label) { return new Node(OPTION).put(OPTION_VALUE, value).put(OPTION_LABEL, label); }
    private static Node entry(String kind, String name, int id) { return new Node(ENTRY).put(ENTRY_KIND, kind).put(ENTRY_NAME, name).put(ENTRY_ID, new UUID(0, id)); }
    private static boolean has(JsonObject result, String reason) { return result.getAsJsonArray("unsupported").contains(new JsonPrimitive(reason)); }
    private static void expect(String code, Runnable call) {
        try { call.run(); throw new AssertionError("Expected " + code); }
        catch (RpcException error) { check(error.code.equals(code) && error.outcome.equals("not_applied"), "expected read-only " + code + ", got " + error.code); }
    }
    private static void check(boolean condition, String label) { assertions++; if (!condition) throw new AssertionError(label); }
    private static final class Node {
        final Set<GridSnapshotReader.Kind> kinds = new HashSet<>();
        final Map<GridSnapshotReader.Field, Object> values = new EnumMap<>(GridSnapshotReader.Field.class);
        boolean owned = true;
        Node(GridSnapshotReader.Kind... types) { kinds.addAll(Arrays.asList(types)); }
        Node put(GridSnapshotReader.Field field, Object value) { values.put(field, value); return this; }
    }
    private static final class Fixture implements GridSnapshotReader.Access {
        final GridSnapshotReader reader = new GridSnapshotReader(this);
        final Node proxy = new Node(), device = new Node(DEVICE), graph = new Node();
        final Node first = module(0, graph), second = module(1, graph);
        final Node input = port(INPUT, first, "in"), output = port(OUTPUT, first, "out");
        List<?> catalog = List.of(); boolean parameters = true, display = true, document = true; int closed;
        Runnable afterModules = () -> {};
        Fixture() {
            proxy.put(TARGET, device); device.put(GRAPH, graph); graph.put(MODULES, List.of(first, second));
            first.put(INPUTS, List.of(input)).put(OUTPUTS, List.of(output));
        }
        Node module(int index, Node owner) {
            return new Node(MODULE).put(INSTANCE_ID, new UUID(1, index + 1)).put(LOCAL_KEY, Integer.toString(index))
                    .put(X, 7 + index).put(Y, 3).put(MODULE_GRAPH, owner)
                    .put(DESCRIPTOR, new Node().put(TYPE_ID, new UUID(2, 1)))
                    .put(INPUTS, List.of()).put(OUTPUTS, List.of()).put(CHILDREN, List.of());
        }
        Node port(GridSnapshotReader.Kind type, Node owner, String key) {
            return new Node(type).put(OWNER_MODULE, owner).put(INPUT_KEY, key).put(OUTPUT_KEY, key).put(PATH, "module/" + key).put(INPUT_SOURCE, null);
        }
        Node parameter(GridSnapshotReader.Kind kind, Node owner) { return new Node(kind).put(OWNER_MODULE, owner).put(PATH, "parameter/" + kind).put(DISPLAY, "native display"); }
        JsonObject read(int offset, int limit, boolean parameters) { return reader.snapshot(proxy, offset, limit, parameters, () -> {}); }
        @Override public Object read(GridSnapshotReader.Field field, Object object) {
            if (field == CATALOG) return catalog;
            Object value = ((Node) object).values.get(field);
            if (field == MODULES || field == INPUTS || field == OUTPUTS || field == CHILDREN) {
                if (value instanceof Supplier<?>) return ((Supplier<?>) value).get();
                Stream<?> stream = ((List<?>) value).stream().onClose(() -> closed++);
                if (field == MODULES) stream = stream.onClose(afterModules);
                return stream;
            }
            return value;
        }
        @Override public boolean is(GridSnapshotReader.Kind kind, Object value) { return value instanceof Node && ((Node) value).kinds.contains(kind); }
        @Override public void documentThread() { if (!document) throw HostServices.failure("grid_unavailable", "wrong thread"); }
        @Override public void owned(Object value) { if (!(value instanceof Node) || !((Node) value).owned) throw HostServices.failure("grid_unavailable", "wrong executor"); }
        @Override public boolean parametersAvailable() { return parameters; }
        @Override public boolean displayAvailable() { return display; }
    }
}
