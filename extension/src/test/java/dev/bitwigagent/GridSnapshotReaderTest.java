package dev.bitwigagent;

import com.google.gson.*;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Stream;
import static dev.bitwigagent.GridSnapshotReader.Field.*;
import static dev.bitwigagent.GridSnapshotReader.Kind.*;
import static dev.bitwigagent.GridSnapshotReader.Metadata.*;

/** Semantic fake model tests, not obfuscated-name echo tests or evidence of native compatibility. */
public final class GridSnapshotReaderTest {
    private static int assertions;
    private static final UUID TRACK_ID = new UUID(3, 1);
    public static void main(String[] args) {
        typedValuesAndIdentityAreNotConflated();
        moduleNamesPreserveTheirDistinctMeanings();
        parameterNamesRemainDistinctFromIdentityAndPath();
        disconnectedPortsKeepTheirOwnNames();
        definitionUnitsAndAffixesAreNotDisplayParsing();
        unavailableMetadataNeverInventsLabels();
        metadataPreservesBoundsAndCancellation();
        pageIncludesIncomingEdgesFromOutsidePage();
        unsupportedModelsAndExternalEdgesAreExplicit();
        excludedPortChildrenRemainExplicit();
        omittedAndUnavailableParametersAreNotComplete();
        rejectsMissingWrongAndStaleTargets();
        nativeOwnerMismatchPrecedesGraphAccess();
        channelAliasUsesPublicChannelUuidMapping();
        missingNativeIdentityFailsClosed();
        nativeIdentityRequiresThreadOwnership();
        nativeIdentityChangesAtFinalGuardAreRejected();
        boundsCloseStreamsAndHonorCancellation();
        catalogFiltersKindsSortsAndPaginates();
        rejectsInvalidNativeValuesAndOwnership();
        singleParameterUsesExactIdentityAndExistingTypedSemantics();
        parameterInspectionRejectsAmbiguityAndStaleMembership();
        parameterContextObservationsDoNotInventPermission();
        parameterContextChangesAndUnavailableBindingsFailClosed();
        rawParameterTypesAndRepresentability();
        rawParameterCasRangesAndActualEnumValues();
        rawParameterNoopAndNativeReadback();
        rawParameterLiveGuardChanges();
        rawParameterMutationFailuresRemainUnknown();
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
    private static void moduleNamesPreserveTheirDistinctMeanings() {
        Fixture f = new Fixture();
        f.first.put(MODULE_NAME, "Lead voice").put(CUSTOM_NAME, "Lead voice");
        f.second.put(MODULE_NAME, "Union").put(CUSTOM_NAME, "");
        f.types.put(new UUID(2, 1), new Node(ENTRY).put(TYPE_NAME, "Union"));
        JsonArray modules = f.read(0, 64, true).getAsJsonArray("modules");
        JsonObject custom = modules.get(0).getAsJsonObject(), ordinary = modules.get(1).getAsJsonObject();
        check(custom.get("name").getAsString().equals("Lead voice"), "effective instance name is native, not catalog-derived");
        check(custom.get("customName").getAsString().equals("Lead voice") && custom.get("typeName").getAsString().equals("Union"),
                "raw custom override remains distinct from catalog type");
        check(ordinary.get("customName").getAsString().isEmpty() && ordinary.get("name").getAsString().equals("Union"),
                "absence of custom override is not filled with effective name");
        check(f.catalogReads == 0, "snapshot names use UUID lookup, not catalog enumeration");
        f.first.put(MODULE_NAME, "").put(CUSTOM_NAME, "");
        JsonObject result = f.read(0, 64, true);
        check(result.getAsJsonArray("modules").get(0).getAsJsonObject().get("name").getAsString().isEmpty(),
                "legitimate empty effective name is preserved");
        check(result.get("complete").getAsBoolean(), "empty native names alone do not imply missing metadata");
    }
    private static void parameterNamesRemainDistinctFromIdentityAndPath() {
        Fixture f = new Fixture();
        Node floating = f.parameter(FLOAT, f.first).put(FLOAT_VALUE, .25)
                .put(FLOAT_DEFINITION, new Node().put(FLOAT_MIN, 0.0).put(FLOAT_MAX, 1.0)
                        .put(FLOAT_UNIT, new Node().put(UNIT_TOKEN, "percent")));
        Node bool = f.parameter(BOOLEAN, f.first).put(BOOLEAN_VALUE, true);
        Node integer = f.parameter(INTEGER, f.first).put(INTEGER_VALUE, 3)
                .put(INTEGER_DEFINITION, new Node().put(INTEGER_MIN, 0).put(INTEGER_MAX, 8)
                        .put(INTEGER_PREFIX, "").put(INTEGER_SUFFIX, ""));
        Node indexed = f.parameter(ENUM, f.first).put(ENUM_VALUE, 2)
                .put(ENUM_DEFINITION, new Node().put(OPTIONS, List.of(option(2, "Pair"))));
        List<Node> children = List.of(floating, bool, integer, indexed);
        for (int i = 0; i < children.size(); i++)
            children.get(i).put(PARAMETER_ID, "PRESET_" + i).put(PARAMETER_NAME, "Control " + i).put(SHORT_NAME, i == 0 ? "" : "C" + i);
        f.first.put(CHILDREN, children);
        JsonArray values = f.read(0, 64, true).getAsJsonArray("modules").get(0).getAsJsonObject().getAsJsonArray("parameters");
        check(values.size() == 4, "all existing typed families expose descriptive metadata");
        for (int i = 0; i < values.size(); i++) {
            JsonObject value = values.get(i).getAsJsonObject();
            check(value.get("parameterId").getAsString().equals("PRESET_" + i), "typed family retains native local identifier");
            check(value.get("name").getAsString().equals("Control " + i), "typed family retains native name");
            check(value.get("path").getAsString().equals(children.get(i).values.get(PATH)), "structural path is not renamed to identifier");
            check(value.get("shortName").getAsString().equals(i == 0 ? "" : "C" + i), "short name retained verbatim including empty");
        }
    }
    private static void disconnectedPortsKeepTheirOwnNames() {
        Fixture f = new Fixture();
        f.input.put(INPUT_KEY, "PITCH_IN").put(INPUT_NAME, "Pitch input");
        f.output.put(OUTPUT_KEY, "OUT").put(OUTPUT_NAME, "Signal output");
        JsonObject result = f.read(0, 64, true);
        JsonObject module = result.getAsJsonArray("modules").get(0).getAsJsonObject();
        JsonObject input = module.getAsJsonArray("inputs").get(0).getAsJsonObject();
        JsonObject output = module.getAsJsonArray("outputs").get(0).getAsJsonObject();
        check(input.get("name").getAsString().equals("Pitch input") && input.get("key").getAsString().equals("PITCH_IN"),
                "input label is not a prettified key or source label");
        check(!input.get("connected").getAsBoolean() && result.getAsJsonArray("connections").isEmpty(),
                "disconnected input keeps its own name without manufacturing a connection");
        check(output.get("name").getAsString().equals("Signal output") && output.get("key").getAsString().equals("OUT"),
                "output label and key remain distinct");
        f.input.put(INPUT_NAME, "");
        check(f.read(0, 64, true).getAsJsonArray("modules").get(0).getAsJsonObject()
                .getAsJsonArray("inputs").get(0).getAsJsonObject().get("name").getAsString().isEmpty(),
                "empty port label is not replaced with identifier");
    }
    private static void definitionUnitsAndAffixesAreNotDisplayParsing() {
        Fixture f = new Fixture();
        double raw = 0.7047298732064893;
        Node floating = f.parameter(FLOAT, f.first).put(FLOAT_VALUE, raw).put(DISPLAY, "350 ms")
                .put(FLOAT_DEFINITION, new Node().put(FLOAT_MIN, 0.0).put(FLOAT_MAX, 2.0)
                        .put(FLOAT_UNIT, new Node().put(UNIT_TOKEN, "seconds")));
        Node integer = f.parameter(INTEGER, f.first).put(INTEGER_VALUE, 7).put(DISPLAY, "~7 voices")
                .put(INTEGER_DEFINITION, new Node().put(INTEGER_MIN, 1).put(INTEGER_MAX, 16)
                        .put(INTEGER_PREFIX, "~").put(INTEGER_SUFFIX, " voices"));
        f.first.put(CHILDREN, List.of(floating, integer));
        JsonObject result = f.read(0, 64, true);
        JsonArray values = result.getAsJsonArray("modules").get(0).getAsJsonObject().getAsJsonArray("parameters");
        JsonObject value = values.get(0).getAsJsonObject(), count = values.get(1).getAsJsonObject();
        check(value.get("numericUnit").getAsString().equals("seconds") && value.get("display").getAsString().equals("350 ms"),
                "stored unit kind is not parsed from formatted magnitude-dependent suffix");
        check(value.get("value").getAsDouble() == raw, "unit kind does not convert or relabel raw value");
        check(result.getAsJsonObject("coverage").get("numericUnits").getAsString().equals("stored_definition_unit_kind_not_raw_value_unit"),
                "unit-kind semantics are explicit in coverage");
        check(count.get("displayPrefix").getAsString().equals("~") && count.get("displaySuffix").getAsString().equals(" voices"),
                "integer display affixes remain separate native definition fields");
        f.display = false;
        value = f.read(0, 64, true).getAsJsonArray("modules").get(0).getAsJsonObject().getAsJsonArray("parameters").get(0).getAsJsonObject();
        check(value.get("display").isJsonNull() && value.get("numericUnit").getAsString().equals("seconds"),
                "numeric-unit metadata does not depend on formatted display availability");
        f.display = true;
        f.failures.put(UNIT_TOKEN, HostServices.failure("grid_unavailable", "unit token unavailable"));
        result = f.read(0, 64, true);
        value = result.getAsJsonArray("modules").get(0).getAsJsonObject().getAsJsonArray("parameters").get(0).getAsJsonObject();
        check(value.get("numericUnit").isJsonNull() && value.get("display").getAsString().equals("350 ms")
                && has(result, "numeric_unit_read_failed") && !result.get("complete").getAsBoolean(),
                "failed unit metadata remains null rather than inferred from a usable display string");
    }
    private static void unavailableMetadataNeverInventsLabels() {
        Fixture f = new Fixture();
        f.unavailable.add(MODULE_NAMES);
        f.types.clear();
        f.input.put(INPUT_NAME, null);
        Node bool = f.parameter(BOOLEAN, f.first).put(BOOLEAN_VALUE, false).put(PARAMETER_NAME, null).put(SHORT_NAME, "");
        f.first.put(CHILDREN, List.of(bool));
        JsonObject result = f.read(0, 64, true);
        JsonObject module = result.getAsJsonArray("modules").get(0).getAsJsonObject();
        check(module.get("name").isJsonNull() && module.get("customName").isJsonNull() && module.get("typeName").isJsonNull(),
                "unsupported binding and absent catalog entry never invent module labels");
        check(module.getAsJsonArray("inputs").get(0).getAsJsonObject().get("name").isJsonNull(),
                "missing input label is null, not its key");
        JsonObject parameter = module.getAsJsonArray("parameters").get(0).getAsJsonObject();
        check(parameter.get("name").isJsonNull() && !parameter.get("parameterId").isJsonNull() && parameter.get("shortName").getAsString().isEmpty(),
                "missing parameter name does not fall back to identifier, path, or short name");
        check(!result.get("complete").getAsBoolean() && has(result, "module_name_unavailable")
                && has(result, "module_type_name_unavailable") && has(result, "input_name_unavailable") && has(result, "parameter_name_unavailable"),
                "each missing descriptive field is explicit partial coverage");
        check(result.getAsJsonObject("coverage").get("moduleNames").getAsString().equals("unsupported"), "binding availability stays explicit");
        f.unavailable.clear();
        f.failures.put(MODULE_NAME, HostServices.failure("grid_unavailable", "native getter failed"));
        result = f.read(0, 64, true);
        module = result.getAsJsonArray("modules").get(0).getAsJsonObject();
        check(module.get("name").isJsonNull() && module.get("customName").getAsString().isEmpty()
                && has(result, "module_name_read_failed"), "failed optional name read retains independent metadata without fabrication");
    }
    private static void metadataPreservesBoundsAndCancellation() {
        Fixture large = new Fixture();
        large.first.put(MODULE_NAME, "x".repeat(GridSnapshotReader.MAX_TEXT + 1));
        expect("grid_bounds_exceeded", () -> large.read(0, 64, true));
        Fixture cancelled = new Fixture();
        cancelled.failures.put(PARAMETER_NAME, HostServices.failure("cancelled", "cancelled during metadata"));
        cancelled.first.put(CHILDREN, List.of(cancelled.parameter(BOOLEAN, cancelled.first).put(BOOLEAN_VALUE, true)));
        expect("cancelled", () -> cancelled.read(0, 64, true));
        Fixture identity = new Fixture();
        identity.first.put(DESCRIPTOR, new Node().put(TYPE_ID, null));
        expect("grid_unavailable", () -> identity.read(0, 64, true));
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
    private static void nativeOwnerMismatchPrecedesGraphAccess() {
        Fixture f = new Fixture();
        // The requested/public-observed ID is still TRACK_ID; the already-bound device has moved.
        f.owner.put(TRACK_UUID, new UUID(3, 2));
        expect("stale_context", () -> f.read(0, 64, true));
        check(f.graphReads == 0 && f.closed == 0, "native rebound owner rejected before graph accessor or enumeration");
        Fixture matching = new Fixture();
        matching.owner.put(TRACK_UUID, new UUID(TRACK_ID.getMostSignificantBits(), TRACK_ID.getLeastSignificantBits()));
        JsonObject result = matching.read(0, 64, true);
        check(result.getAsJsonArray("modules").size() == 2 && matching.graphReads == 2,
                "ordinary owner matches by UUID value, not object identity");
    }
    private static void channelAliasUsesPublicChannelUuidMapping() {
        Fixture f = new Fixture(); f.alias();
        f.owner.put(TRACK_UUID, new UUID(3, 9));
        f.failures.put(TRACK_UUID, HostServices.failure("grid_unavailable", "raw SCA UUID must not be read"));
        check(f.read(0, 64, true).getAsJsonArray("modules").size() == 2,
                "SCA uses alias UUID even when raw owner UUID differs");
        Fixture mismatch = new Fixture(); mismatch.alias().put(ALIAS_UUID, new UUID(3, 9));
        expect("stale_context", () -> mismatch.read(0, 64, true));
        check(mismatch.graphReads == 0, "matching raw owner UUID cannot rescue mismatched alias UUID");
    }
    private static void missingNativeIdentityFailsClosed() {
        for (Object owner : Arrays.asList(null, new Node(), "not a native owner")) {
            Fixture f = new Fixture(); f.device.put(OWNER_TRACK, owner);
            expect("grid_unavailable", () -> f.read(0, 64, true));
            check(f.graphReads == 0, "absent or wrong-type owning track blocks graph access");
        }
        for (Object alias : Arrays.asList(null, new Node(TRACK), "not a native alias")) {
            Fixture f = new Fixture(); f.alias(); f.owner.put(TRACK_ALIAS, alias);
            expect("grid_unavailable", () -> f.read(0, 64, true));
            check(f.graphReads == 0, "absent or wrong-type channel alias blocks graph access");
        }
        for (Object uuid : Arrays.asList(null, TRACK_ID.toString(), new Node())) {
            Fixture ordinary = new Fixture(); ordinary.owner.put(TRACK_UUID, uuid);
            expect("grid_unavailable", () -> ordinary.read(0, 64, true));
            check(ordinary.graphReads == 0, "ordinary UUID must be a native UUID value");
            Fixture aliased = new Fixture(); aliased.alias().put(ALIAS_UUID, uuid);
            expect("grid_unavailable", () -> aliased.read(0, 64, true));
            check(aliased.graphReads == 0, "alias UUID cannot fall back to ordinary UUID");
        }
        Fixture unavailable = new Fixture();
        unavailable.failures.put(OWNER_TRACK, HostServices.failure("grid_unavailable", "unbound identity"));
        expect("grid_unavailable", () -> unavailable.read(0, 64, true));
        check(unavailable.graphReads == 0, "unavailable required identity never degrades into structure-only read");
        check(unavailable.reader.catalog("", 0, 64, () -> {}).getAsJsonArray("entries").isEmpty(),
                "catalog is independent of native device ownership");
        Fixture absentRequest = new Fixture();
        expect("grid_unavailable", () -> absentRequest.reader.snapshot(absentRequest.proxy, null, 0, 64, true, () -> {}));
        check(absentRequest.targetReads == 0, "missing expected UUID fails before native target resolution");
    }
    private static void nativeIdentityRequiresThreadOwnership() {
        Fixture wrongThread = new Fixture(); wrongThread.document = false;
        expect("grid_unavailable", () -> wrongThread.read(0, 64, true));
        check(wrongThread.targetReads == 0, "application thread checked before native proxy resolution");
        for (int kind = 0; kind < 3; kind++) {
            Fixture f = new Fixture();
            Node inaccessible = kind == 0 ? f.device : kind == 1 ? f.owner : f.alias();
            inaccessible.threadAllowed = false;
            expect("grid_unavailable", () -> f.read(0, 64, true));
            check(f.graphReads == 0 && f.uuidReads == 0, "device, owner and alias require a valid thread predicate before UUID/graph access");
        }
        Fixture finalThread = new Fixture(); finalThread.afterModules = () -> finalThread.document = false;
        expect("grid_unavailable", () -> finalThread.read(0, 64, true));
        check(finalThread.targetReads == 1, "completion rechecks application thread before native proxy access");
    }
    private static void nativeIdentityChangesAtFinalGuardAreRejected() {
        Fixture owner = new Fixture();
        owner.afterModules = () -> owner.device.put(OWNER_TRACK, new Node(TRACK).put(TRACK_UUID, TRACK_ID));
        expect("stale_context", () -> owner.read(0, 64, true));
        Fixture alias = new Fixture(); alias.alias();
        alias.afterModules = () -> alias.owner.put(TRACK_ALIAS, new Node(CHANNEL_ALIAS).put(ALIAS_UUID, TRACK_ID));
        expect("stale_context", () -> alias.read(0, 64, true));
        Fixture uuid = new Fixture(); uuid.afterModules = () -> uuid.owner.put(TRACK_UUID, new UUID(3, 9));
        expect("stale_context", () -> uuid.read(0, 64, true));
        Fixture aliasUuid = new Fixture(); Node identity = aliasUuid.alias();
        aliasUuid.afterModules = () -> identity.put(ALIAS_UUID, new UUID(3, 9));
        expect("stale_context", () -> aliasUuid.read(0, 64, true));
        Fixture missing = new Fixture(); missing.afterModules = () -> missing.device.put(OWNER_TRACK, null);
        expect("grid_unavailable", () -> missing.read(0, 64, true));
        Fixture missingAlias = new Fixture(); missingAlias.alias();
        missingAlias.afterModules = () -> missingAlias.owner.put(TRACK_ALIAS, null);
        expect("grid_unavailable", () -> missingAlias.read(0, 64, true));
        Fixture wrongContext = new Fixture(); Node finalAlias = wrongContext.alias();
        wrongContext.afterModules = () -> finalAlias.threadAllowed = false;
        expect("grid_unavailable", () -> wrongContext.read(0, 64, true));
    }
    private static void boundsCloseStreamsAndHonorCancellation() {
        Fixture f = new Fixture();
        f.graph.put(MODULES, (Supplier<Stream<?>>) () -> Stream.generate(() -> f.first).onClose(() -> f.closed++));
        expect("grid_bounds_exceeded", () -> f.read(0, 64, true)); check(f.closed == 1, "infinite stream bounded and closed");
        Fixture cancelled = new Fixture();
        int[] calls = {0};
        expect("cancelled", () -> cancelled.reader.snapshot(cancelled.proxy, TRACK_ID, 0, 64, true, () -> {
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
        Fixture executor = new Fixture(); executor.first.threadAllowed = false; expect("grid_unavailable", () -> executor.read(0, 64, true));
        Fixture identity = new Fixture(); identity.second.put(INSTANCE_ID, identity.first.values.get(INSTANCE_ID));
        expect("grid_unavailable", () -> identity.read(0, 64, true));
        Fixture enums = new Fixture(); Node indexed = enums.parameter(ENUM, enums.first).put(ENUM_VALUE, 99);
        indexed.put(ENUM_DEFINITION, new Node().put(OPTIONS, List.of(option(2, "Two")))); enums.first.put(CHILDREN, List.of(indexed));
        check(has(enums.read(0, 64, true), "enum_value_not_in_options"), "unknown enum value is not relabeled ordinal");
    }
    private static void singleParameterUsesExactIdentityAndExistingTypedSemantics() {
        Fixture f = new Fixture();
        Node floating = f.parameter(FLOAT, f.first).put(PARAMETER_ID, "").put(FLOAT_VALUE, -1.25)
                .put(FLOAT_DEFINITION, new Node().put(FLOAT_MIN, -2.0).put(FLOAT_MAX, 4.0));
        Node bool = f.parameter(BOOLEAN, f.first).put(PARAMETER_ID, "BOOL").put(BOOLEAN_VALUE, false);
        Node integer = f.parameter(INTEGER, f.first).put(PARAMETER_ID, "INT").put(INTEGER_VALUE, -7)
                .put(INTEGER_DEFINITION, new Node().put(INTEGER_MIN, -10).put(INTEGER_MAX, 12));
        Node indexed = f.parameter(ENUM, f.first).put(PARAMETER_ID, "ENUM").put(ENUM_VALUE, 42)
                .put(ENUM_DEFINITION, new Node().put(OPTIONS, List.of(option(10, "Ten"), option(42, "Answer"))));
        List<Node> children = List.of(floating, bool, integer, indexed);
        f.first.put(CHILDREN, children);
        ContextFixture context = new ContextFixture();
        JsonArray graphValues = f.read(0, 64, true).getAsJsonArray("modules").get(0).getAsJsonObject().getAsJsonArray("parameters");
        List<JsonElement> expectedValues = List.of(new JsonPrimitive(-1.25), new JsonPrimitive(false), new JsonPrimitive(-7), new JsonPrimitive(42));
        for (int i = 0; i < children.size(); i++) {
            JsonObject result = inspect(f, context, (String) children.get(i).values.get(PARAMETER_ID));
            check(result.getAsJsonObject("parameter").equals(graphValues.get(i)), "single parameter preserves typed raw/display/options and partial metadata");
            check(result.getAsJsonObject("parameter").get("value").equals(expectedValues.get(i)), "exact family returns stored value, not normalized value or enum ordinal");
        }
        // The other module is unreadable; a single-parameter read must never serialize its values.
        f.second.put(CHILDREN, List.of(f.parameter(FLOAT, f.second).put(FLOAT_VALUE, Double.NaN)));
        check(inspect(f, context, "ENUM").getAsJsonObject("parameter").get("value").getAsInt() == 42,
                "inspection resolves one exact parameter, not an entire graph serialization");
        expect("stale_context", () -> inspect(f, context, "enum"));
        expect("stale_context", () -> inspect(f, context, "Native control"));
        expect("stale_context", () -> inspect(f, context, "parameter/ENUM"));
    }
    private static void parameterInspectionRejectsAmbiguityAndStaleMembership() {
        Fixture f = inspectionFixture(); ContextFixture context = new ContextFixture();
        f.second.put(INSTANCE_ID, f.first.values.get(INSTANCE_ID));
        expect("stale_context", () -> inspect(f, context, "LOCAL_ID"));
        f.second.put(INSTANCE_ID, new UUID(1, 2));
        Node child = (Node) ((List<?>) f.first.values.get(CHILDREN)).get(0);
        f.first.put(CHILDREN, List.of(child, f.parameter(BOOLEAN, f.first).put(BOOLEAN_VALUE, false)));
        expect("stale_context", () -> inspect(f, context, "LOCAL_ID"));
        f.first.put(CHILDREN, List.of(child));
        child.put(OWNER_MODULE, f.second); expect("stale_context", () -> inspect(f, context, "LOCAL_ID"));
        child.put(OWNER_MODULE, f.first);
        f.first.put(MODULE_GRAPH, new Node()); expect("stale_context", () -> inspect(f, context, "LOCAL_ID"));
        f.first.put(MODULE_GRAPH, f.graph);
        child.kinds.remove(BOOLEAN); expect("grid_unavailable", () -> inspect(f, context, "LOCAL_ID"));
        child.kinds.add(BOOLEAN);
        f.first.put(CHILDREN, Collections.nCopies(257, child));
        expect("grid_bounds_exceeded", () -> inspect(f, context, "LOCAL_ID"));
        for (int change = 0; change < 5; change++) {
            Fixture moved = inspectionFixture(); ContextFixture c = new ContextFixture(); final int mode = change;
            moved.afterValue = () -> {
                if (mode == 0) moved.first.put(CHILDREN, List.of());
                if (mode == 1) moved.first.put(CHILDREN, List.of(moved.parameter(BOOLEAN, moved.first).put(BOOLEAN_VALUE, true)));
                if (mode == 2) moved.graph.put(MODULES, List.of(moved.module(0, moved.graph)));
                if (mode == 3) moved.first.put(INSTANCE_ID, new UUID(7, 8));
                if (mode == 4) moved.device.put(OWNER_TRACK, new Node(TRACK).put(TRACK_UUID, new UUID(8, 9)));
            };
            expect("stale_context", () -> inspect(moved, c, "LOCAL_ID"));
        }
        Fixture cancelled = inspectionFixture(); int[] calls = {0};
        expect("cancelled", () -> cancelled.reader.parameter(cancelled.proxy, TRACK_ID, new UUID(1, 1),
                "LOCAL_ID", context.reader, () -> { if (++calls[0] == 5) throw HostServices.failure("cancelled", "cancelled"); }));
        check(cancelled.closed > 0, "cancelled exact lookup closes native streams");
    }
    private static void parameterContextObservationsDoNotInventPermission() {
        Fixture f = inspectionFixture(); ContextFixture c = new ContextFixture();
        c.values.put(GridParameterContext.Field.LICENSED, false);
        c.values.put(GridParameterContext.Field.SETTABLE, false);
        c.values.put(GridParameterContext.Field.CIU, true);
        c.values.put(GridParameterContext.Field.TOUCHED, true);
        c.values.put(GridParameterContext.Field.UNDO_EXECUTING, true);
        c.values.put(GridParameterContext.Field.UNDO_ACTIVE, true);
        c.values.put(GridParameterContext.Field.UNDO_ICL, true);
        Object foreignDt = new Object(); c.values.put(GridParameterContext.Field.LOCK_OWNER, foreignDt);
        JsonObject result = inspect(f, c, "LOCAL_ID").getAsJsonObject("editContext");
        check(!result.get("effectivelyLicensed").getAsBoolean() && !result.getAsJsonObject("guiProperty").get("isSettable").getAsBoolean(),
                "license and actual property descriptor are independent observed false states, not a read rejection");
        check(result.get("nativeCIu").getAsBoolean() && result.get("parameterTouched").getAsBoolean(), "opaque skip and active touch remain distinct observations");
        check(result.getAsJsonObject("ancestorUndoManager").get("executingUndoRedo").getAsBoolean()
                && result.getAsJsonObject("ancestorUndoManager").get("activeChange").getAsBoolean(), "active undo states remain observable without mutation");
        check(!result.getAsJsonObject("deviceLock").get("ownerIsCurrentDt").getAsBoolean(), "foreign lock owner is not reported as current Dt");
        c.values.put(GridParameterContext.Field.UNDO, null); c.values.put(GridParameterContext.Field.LOCK_OWNER, null);
        c.values.put(GridParameterContext.Field.CURRENT_DT, null); c.defaultSupported = false;
        result = inspect(f, c, "LOCAL_ID").getAsJsonObject("editContext");
        check(!result.getAsJsonObject("ancestorUndoManager").get("present").getAsBoolean()
                && result.getAsJsonObject("ancestorUndoManager").get("activeChange").isJsonNull(), "missing undo manager is not an idle manager");
        check(result.getAsJsonObject("deviceLock").get("ownerIsCurrentDt").isJsonNull()
                && result.getAsJsonObject("attachedContext").get("currentDtIsDefault").isJsonNull(), "missing owner/default context is unknown rather than compatible");
    }
    private static void parameterContextChangesAndUnavailableBindingsFailClosed() {
        for (GridParameterContext.Field field : List.of(GridParameterContext.Field.ROOT, GridParameterContext.Field.CURRENT_DT,
                GridParameterContext.Field.DEFAULT_DT,
                GridParameterContext.Field.UNDO, GridParameterContext.Field.LOCK, GridParameterContext.Field.LOCK_OWNER)) {
            Fixture f = inspectionFixture(); ContextFixture c = new ContextFixture();
            f.afterValue = () -> c.values.put(field, new Object());
            expect("stale_context", () -> inspect(f, c, "LOCAL_ID"));
        }
        Fixture rebound = inspectionFixture(); ContextFixture reboundContext = new ContextFixture();
        rebound.afterValue = () -> {
            Object replacement = new Object();
            reboundContext.values.put(GridParameterContext.Field.CONTEXT, replacement);
            reboundContext.values.put(GridParameterContext.Field.DT_CONTEXT, replacement);
        };
        expect("stale_context", () -> inspect(rebound, reboundContext, "LOCAL_ID"));
        Fixture f = inspectionFixture(); ContextFixture c = new ContextFixture();
        c.values.put(GridParameterContext.Field.CONTEXT, null);
        expect("stale_context", () -> inspect(f, c, "LOCAL_ID"));
        c.values.put(GridParameterContext.Field.CONTEXT, new Object());
        expect("stale_context", () -> inspect(f, c, "LOCAL_ID"));
        ContextFixture deleted = new ContextFixture(); deleted.values.put(GridParameterContext.Field.DELETED, true);
        expect("stale_context", () -> inspect(f, deleted, "LOCAL_ID"));
        ContextFixture disposed = new ContextFixture(); disposed.values.put(GridParameterContext.Field.DISPOSED, true);
        expect("stale_context", () -> inspect(f, disposed, "LOCAL_ID"));
        ContextFixture unavailable = new ContextFixture(); unavailable.values.put(GridParameterContext.Field.SETTABLE, null);
        expect("grid_unavailable", () -> inspect(f, unavailable, "LOCAL_ID"));
        ContextFixture missingWrapper = new ContextFixture(); missingWrapper.values.put(GridParameterContext.Field.GUI_BOOLEAN, null);
        expect("grid_unavailable", () -> inspect(f, missingWrapper, "LOCAL_ID"));
        ContextFixture foreignProject = new ContextFixture(); foreignProject.values.put(GridParameterContext.Field.PARAMETER_PROJECT, new Object());
        expect("stale_context", () -> inspect(f, foreignProject, "LOCAL_ID"));
        expect("grid_unavailable", () -> f.reader.parameter(f.proxy, TRACK_ID, new UUID(1, 1), "LOCAL_ID", null, () -> {}));
        check(f.read(0, 64, true).getAsJsonArray("modules").size() == 2, "unavailable inspection context does not disable ordinary graph reads");
    }
    private static Fixture inspectionFixture() {
        Fixture f = new Fixture(); f.first.put(CHILDREN, List.of(f.parameter(BOOLEAN, f.first).put(BOOLEAN_VALUE, true))); return f;
    }
    private static JsonObject inspect(Fixture f, ContextFixture context, String id) {
        return f.reader.parameter(f.proxy, TRACK_ID, new UUID(1, 1), id, context.reader, () -> {});
    }

    private static GridParameterWriter.Request request(String kind, String expected, String value) {
        return GridParameterWriter.Request.parse(TransportMixFixture.json(
                "{\"expectedKind\":\"" + kind + "\",\"expectedValue\":" + expected + ",\"value\":" + value + "}"));
    }

    private static void rawParameterTypesAndRepresentability() {
        for (String value : List.of("null", "\"1\"", "true", "1.0", "1e0", "2147483648", "-2147483649"))
            expect("invalid_params", () -> request("integer", "0", value));
        for (String value : List.of("0", "1", "\"false\"", "null"))
            expect("invalid_params", () -> request("boolean", "false", value));
        for (String value : List.of("1e309", "-1e309", "true", "\"0.5\"", "null", "9007199254740993"))
            expect("invalid_params", () -> request("float", "0", value));
        expect("invalid_params", () -> request("float", "9007199254740993", "0"));
        expect("invalid_params", () -> request("enum", "1.0", "2"));
        expect("invalid_params", () -> request("number", "0", "1"));
        check(request("integer", "-2147483648", "2147483647").rawValue == Integer.MAX_VALUE, "signed integer boundaries remain exact");
        check(request("boolean", "true", "false").rawValue == 0.0, "boolean converted only for native raw call");
        check(request("float", "0", "9007199254740992").rawValue == 9007199254740992.0, "exact large binary64 integer remains usable");
    }

    private static void rawParameterCasRangesAndActualEnumValues() {
        WriterFixture f = new WriterFixture();
        expect("stale_context", () -> f.set(request("boolean", "false", "true")));
        expect("stale_context", () -> f.set(request("float", "1", "1")));
        check(f.calls == 0, "CAS and kind precede same-value no-op");
        f.scalar(FLOAT, 0.5, -1.0, 1.0);
        expect("invalid_params", () -> f.set(request("float", "0.5", "1.0001")));
        expect("stale_context", () -> f.set(request("float", "0.5000000000000001", "0.5")));
        expect("stale_context", () -> f.set(request("float", "0.4", "2")));
        f.scalar(INTEGER, -7, -10, 12);
        expect("invalid_params", () -> f.set(request("integer", "-7", "13")));
        f.set(request("integer", "-7", "12"));
        check((Integer) f.child.values.get(INTEGER_VALUE) == 12, "signed raw integer edit reaches exact bounded model value");
        f.scalar(ENUM, 42, 0, 0);
        expect("invalid_params", () -> f.set(request("enum", "42", "1")));
        f.set(request("enum", "42", "10"));
        check((Integer) f.child.values.get(ENUM_VALUE) == 10, "enum uses listed nonordinal option value");
        f.child.put(ENUM_VALUE, 99);
        expect("grid_unavailable", () -> f.set(request("enum", "99", "10")));
    }

    private static void rawParameterNoopAndNativeReadback() {
        WriterFixture f = new WriterFixture();
        JsonObject noop = f.set(request("boolean", "true", "true"));
        check(f.calls == 0 && !f.op.dispatched && !noop.get("nativeMutation").getAsBoolean()
                && !noop.get("nativeUndo").getAsBoolean(), "same-value CAS performs no native operation");
        f.scalar(FLOAT, -0.0, -1.0, 1.0);
        f.set(request("float", "0.0", "0.0"));
        check(f.calls == 0, "signed zero equality does not manufacture a native write");
        JsonObject result = f.set(request("float", "0", "0.75"));
        check(f.calls == 1 && f.op.dispatched && result.get("mutationDispatched").getAsBoolean(), "distinct raw value invokes exactly once");
        check(result.get("previousValue").getAsDouble() == 0.0
                && result.get("requestedValue").getAsDouble() == 0.75
                && result.getAsJsonObject("parameter").get("value").getAsDouble() == 0.75,
                "result distinguishes prior/requested/current raw observations");
        WriterFixture mismatched = new WriterFixture(); mismatched.apply = false;
        RpcException error = mismatched.failure(request("boolean", "true", "false"));
        check(error.code.equals("grid_verification_failed") && error.outcome.equals("unknown")
                && mismatched.calls == 1, "void setter with mismatched readback remains unknown without retry");
    }

    private static void rawParameterLiveGuardChanges() {
        for (GridParameterContext.Field field : List.of(GridParameterContext.Field.LICENSED, GridParameterContext.Field.SETTABLE,
                GridParameterContext.Field.INITIALIZED)) {
            WriterFixture f = new WriterFixture(); f.context.values.put(field, false);
            expect("unsafe_state", () -> f.set(request("boolean", "true", "true")));
            check(f.calls == 0, "false native prerequisite blocks even a no-op");
            f.context.values.put(field, null);
            expect("grid_unavailable", () -> f.set(request("boolean", "true", "false")));
        }
        for (GridParameterContext.Field field : List.of(GridParameterContext.Field.CIU, GridParameterContext.Field.TOUCHED,
                GridParameterContext.Field.UNDO_EXECUTING, GridParameterContext.Field.UNDO_ACTIVE, GridParameterContext.Field.UNDO_ICL)) {
            WriterFixture f = new WriterFixture(); int[] reads = {0};
            f.model.afterValue = () -> { if (++reads[0] == 2) f.context.values.put(field, true); };
            expect("unsafe_state", () -> f.set(request("boolean", "true", "false")));
            check(f.calls == 0 && !f.op.dispatched, "live predicate transition before invocation prevents dispatch");
        }
        for (GridParameterContext.Field field : List.of(GridParameterContext.Field.UNDO, GridParameterContext.Field.LOCK,
                GridParameterContext.Field.LOCK_OWNER, GridParameterContext.Field.CURRENT_DT, GridParameterContext.Field.DEFAULT_DT)) {
            WriterFixture f = new WriterFixture(); f.context.values.put(field, null);
            expect("grid_unavailable", () -> f.set(request("boolean", "true", "false")));
            check(f.calls == 0, "missing native context object is unavailable, never false permission");
        }
        WriterFixture owner = new WriterFixture(); owner.context.values.put(GridParameterContext.Field.LOCK_OWNER, new Object());
        expect("unsafe_state", () -> owner.set(request("boolean", "true", "false")));
        WriterFixture root = new WriterFixture(); root.context.values.put(GridParameterContext.Field.ROOT, new Object());
        expect("stale_context", () -> root.set(request("boolean", "true", "false")));
        WriterFixture moved = new WriterFixture(); moved.model.afterValue = () -> moved.model.first.put(CHILDREN, List.of());
        expect("stale_context", () -> moved.set(request("boolean", "true", "false")));
        WriterFixture changedValue = new WriterFixture();
        changedValue.model.afterValue = () -> changedValue.child.put(BOOLEAN_VALUE, false);
        expect("stale_context", () -> changedValue.set(request("boolean", "true", "false")));
        WriterFixture unknown = new WriterFixture(); unknown.supported = false;
        expect("grid_unavailable", () -> unknown.set(request("boolean", "true", "false")));
        check(inspect(unknown.model, unknown.context, "LOCAL_ID").getAsJsonObject("parameter").get("value").getAsBoolean(),
                "missing write eligibility leaves independent typed inspection usable");
    }

    private static void rawParameterMutationFailuresRemainUnknown() {
        WriterFixture partial = new WriterFixture();
        partial.afterMutation = () -> { throw HostServices.failure("grid_unavailable", "native failure after partial assignment"); };
        RpcException error = partial.failure(request("boolean", "true", "false"));
        check(error.outcome.equals("unknown") && partial.calls == 1 && !(Boolean) partial.child.values.get(BOOLEAN_VALUE),
                "partial mutation exception never fabricates rollback or not-applied");
        WriterFixture target = new WriterFixture();
        target.afterMutation = () -> target.model.proxy.put(TARGET, new Node(DEVICE));
        error = target.failure(request("boolean", "true", "false"));
        check(error.code.equals("stale_context") && error.outcome.equals("unknown") && target.calls == 1,
                "post-dispatch target failure remains unknown without retry");
        WriterFixture closed = new WriterFixture();
        closed.afterMutation = () -> closed.cancelled = true;
        error = closed.failure(request("boolean", "true", "false"));
        check(error.code.equals("cancelled") && error.outcome.equals("unknown"), "late cancellation does not imply native cancellation");
        WriterFixture early = new WriterFixture(); early.cancelled = true;
        error = early.failure(request("boolean", "true", "false"));
        check(error.outcome.equals("not_applied") && early.calls == 0, "before-dispatch cancellation prevents mutation");
    }

    /** Real shared binding/context/writer logic over a semantic model, also used by queue-boundary tests. */
    static final class WriterFixture implements GridParameterWriter.Access {
        final Fixture model = inspectionFixture();
        final ContextFixture context = new ContextFixture();
        final HostOperation op = new HostOperation(Long.MAX_VALUE);
        final GridParameterWriter writer = new GridParameterWriter(model.reader, context.reader, this);
        Node child = (Node) ((List<?>) model.first.values.get(CHILDREN)).get(0);
        GridSnapshotReader.Kind kind = BOOLEAN;
        GridSnapshotReader.Field field = BOOLEAN_VALUE;
        int calls;
        boolean apply = true, supported = true, cancelled;
        Runnable afterMutation = () -> {};
        WriterFixture() { context.values.put(GridParameterContext.Field.LOCK_OWNER, context.dt); }
        void scalar(GridSnapshotReader.Kind kind, Object value, Number min, Number max) {
            this.kind = kind;
            field = kind == FLOAT ? FLOAT_VALUE : kind == INTEGER ? INTEGER_VALUE : kind == ENUM ? ENUM_VALUE : BOOLEAN_VALUE;
            child = model.parameter(kind, model.first).put(field, value);
            if (kind == FLOAT) child.put(FLOAT_DEFINITION, new Node().put(FLOAT_MIN, min.doubleValue()).put(FLOAT_MAX, max.doubleValue()));
            if (kind == INTEGER) child.put(INTEGER_DEFINITION, new Node().put(INTEGER_MIN, min.intValue()).put(INTEGER_MAX, max.intValue()));
            if (kind == ENUM) child.put(ENUM_DEFINITION, new Node().put(OPTIONS, List.of(option(10, "Ten"), option(42, "Answer"))));
            model.first.put(CHILDREN, List.of(child));
        }
        JsonObject set(GridParameterWriter.Request request) {
            return set(request, () -> {
                if (cancelled) throw HostServices.failure("cancelled", "cancelled");
            }, () -> op.dispatched = true);
        }
        JsonObject set(GridParameterWriter.Request request, Runnable check, Runnable dispatch) {
            return writer.set(model.proxy, TRACK_ID, new UUID(1, 1), "LOCAL_ID", request, check, dispatch);
        }
        RpcException failure(GridParameterWriter.Request request) {
            try { set(request); throw new AssertionError("Expected failed write"); }
            catch (RpcException error) { op.fail(error); return TransportMixFixture.error(op, error.code); }
        }
        @Override public void requireSupported(Object parameter, GridSnapshotReader.Kind kind) {
            if (!supported) throw HostServices.failure("grid_unavailable", "unmapped model");
        }
        @Override public void setRaw(Object parameter, double value) {
            calls++;
            if (apply) {
                Object raw;
                if (kind == BOOLEAN) raw = value > 0.5;
                else if (kind == FLOAT) raw = value;
                else raw = (int) value;
                ((Node) parameter).put(field, raw);
            }
            afterMutation.run();
        }
    }
    private static final class ContextFixture implements GridParameterContext.Access {
        final GridParameterContext reader = new GridParameterContext(this);
        final EnumMap<GridParameterContext.Field, Object> values = new EnumMap<>(GridParameterContext.Field.class);
        final Object context = new Object(), project = new Object(), dt = new Object();
        boolean defaultSupported = true;
        ContextFixture() {
            for (GridParameterContext.Field field : GridParameterContext.Field.values()) values.put(field, false);
            values.put(GridParameterContext.Field.CONTEXT, context); values.put(GridParameterContext.Field.PROJECT, project);
            values.put(GridParameterContext.Field.PARAMETER_PROJECT, project); values.put(GridParameterContext.Field.ROOT, project);
            values.put(GridParameterContext.Field.INITIALIZED, true); values.put(GridParameterContext.Field.CONTEXT_THREAD, true);
            values.put(GridParameterContext.Field.CURRENT_DT, dt); values.put(GridParameterContext.Field.DEFAULT_DT, dt);
            values.put(GridParameterContext.Field.DT_CONTEXT, context); values.put(GridParameterContext.Field.LICENSED, true);
            values.put(GridParameterContext.Field.SETTABLE, true);
            for (GridParameterContext.Field field : List.of(GridParameterContext.Field.GUI_FLOAT, GridParameterContext.Field.GUI_INTEGER,
                    GridParameterContext.Field.GUI_ENUM, GridParameterContext.Field.GUI_BOOLEAN, GridParameterContext.Field.UNDO, GridParameterContext.Field.LOCK))
                values.put(field, new Object());
            values.put(GridParameterContext.Field.LOCK_OWNER, null);
            // Native GUI-property getters allocate wrappers; their object identity must not be treated as stable.
            for (GridParameterContext.Field field : List.of(GridParameterContext.Field.GUI_FLOAT, GridParameterContext.Field.GUI_INTEGER,
                    GridParameterContext.Field.GUI_ENUM, GridParameterContext.Field.GUI_BOOLEAN))
                values.put(field, (Supplier<Object>) Object::new);
        }
        @Override public Object read(GridParameterContext.Field field, Object object) {
            Object value = values.get(field); return value instanceof Supplier<?> ? ((Supplier<?>) value).get() : value;
        }
        @Override public boolean defaultContext(Object value) { return defaultSupported; }
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
        boolean threadAllowed = true;
        Node(GridSnapshotReader.Kind... types) { kinds.addAll(Arrays.asList(types)); }
        Node put(GridSnapshotReader.Field field, Object value) { values.put(field, value); return this; }
    }
    private static final class Fixture implements GridSnapshotReader.Access {
        final GridSnapshotReader reader = new GridSnapshotReader(this);
        final Node proxy = new Node(), device = new Node(DEVICE), graph = new Node();
        final Node owner = new Node(TRACK).put(TRACK_UUID, TRACK_ID);
        final Map<UUID, Node> types = new HashMap<>();
        final Set<GridSnapshotReader.Metadata> unavailable = EnumSet.noneOf(GridSnapshotReader.Metadata.class);
        final Map<GridSnapshotReader.Field, RpcException> failures = new EnumMap<>(GridSnapshotReader.Field.class);
        final Node first = module(0, graph), second = module(1, graph);
        final Node input = port(INPUT, first, "in"), output = port(OUTPUT, first, "out");
        List<?> catalog = List.of(); boolean parameters = true, display = true, document = true;
        int closed, catalogReads, graphReads, targetReads, uuidReads;
        Runnable afterModules = () -> {};
        Runnable afterValue = () -> {};
        Fixture() {
            proxy.put(TARGET, device); device.put(GRAPH, graph); graph.put(MODULES, List.of(first, second));
            device.put(OWNER_TRACK, owner);
            first.put(INPUTS, List.of(input)).put(OUTPUTS, List.of(output));
            types.put(new UUID(2, 1), new Node(ENTRY).put(TYPE_NAME, "Catalog type"));
        }
        Node alias() {
            Node alias = new Node(CHANNEL_ALIAS).put(ALIAS_UUID, TRACK_ID);
            owner.kinds.add(ALIASED_TRACK); owner.put(TRACK_ALIAS, alias);
            return alias;
        }
        Node module(int index, Node owner) {
            return new Node(MODULE).put(INSTANCE_ID, new UUID(1, index + 1)).put(LOCAL_KEY, Integer.toString(index))
                    .put(X, 7 + index).put(Y, 3).put(MODULE_GRAPH, owner)
                    .put(MODULE_NAME, "Effective module").put(CUSTOM_NAME, "")
                    .put(DESCRIPTOR, new Node().put(TYPE_ID, new UUID(2, 1)))
                    .put(INPUTS, List.of()).put(OUTPUTS, List.of()).put(CHILDREN, List.of());
        }
        Node port(GridSnapshotReader.Kind type, Node owner, String key) {
            return new Node(type).put(OWNER_MODULE, owner).put(INPUT_KEY, key).put(OUTPUT_KEY, key)
                    .put(INPUT_NAME, "Input label").put(OUTPUT_NAME, "Output label").put(PATH, "module/" + key).put(INPUT_SOURCE, null);
        }
        Node parameter(GridSnapshotReader.Kind kind, Node owner) {
            return new Node(kind, PARAMETER).put(OWNER_MODULE, owner).put(PATH, "parameter/" + kind).put(DISPLAY, "native display")
                    .put(PARAMETER_ID, "LOCAL_ID").put(PARAMETER_NAME, "Native control").put(SHORT_NAME, "");
        }
        JsonObject read(int offset, int limit, boolean parameters) { return reader.snapshot(proxy, TRACK_ID, offset, limit, parameters, () -> {}); }
        @Override public Object read(GridSnapshotReader.Field field, Object object) {
            if (failures.containsKey(field)) throw failures.get(field);
            if (field == CATALOG) { catalogReads++; return catalog; }
            if (field == TYPE_ENTRY) return types.get(object);
            if (field == GRAPH) graphReads++;
            if (field == TARGET) targetReads++;
            if (field == TRACK_UUID || field == ALIAS_UUID) uuidReads++;
            Object value = ((Node) object).values.get(field);
            if (field == FLOAT_VALUE || field == BOOLEAN_VALUE || field == INTEGER_VALUE || field == ENUM_VALUE) afterValue.run();
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
        @Override public void requireModelThread(Object value) { if (!(value instanceof Node) || !((Node) value).threadAllowed) throw HostServices.failure("grid_unavailable", "wrong thread predicate"); }
        @Override public boolean parametersAvailable() { return parameters; }
        @Override public boolean displayAvailable() { return display; }
        @Override public boolean metadataAvailable(GridSnapshotReader.Metadata metadata) { return !unavailable.contains(metadata); }
    }
}
