package dev.bitwigagent;

import static dev.bitwigagent.GridSnapshotReader.Field.*;
import static dev.bitwigagent.GridSnapshotReader.Kind.*;
import static dev.bitwigagent.GridSnapshotReader.Metadata.*;
import static dev.bitwigagent.NativeDeviceTarget.Field.*;
import static dev.bitwigagent.NativeDeviceTarget.Kind.*;
import static dev.bitwigagent.RpcAssert.*;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Semantic fake model tests, not obfuscated-name echo tests or evidence of native compatibility. */
public final class GridSnapshotReaderTest {
    private static final UUID TRACK_ID = new UUID(3, 1);

    @Test
    void typedValuesAndIdentityAreNotConflated() {
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
        assertTrue(
                !module.get("instanceId").equals(module.get("typeId")), "instance UUID differs from catalog type UUID");
        assertTrue(module.get("localKey").getAsString().equals("0"), "list-local key remains separate");
        assertTrue(
                module.get("x").getAsInt() == 7 && module.get("y").getAsInt() == 3,
                "asymmetric native coordinates preserved");
        JsonArray values = module.getAsJsonArray("parameters");
        assertTrue(values.size() == 4, "ports are not parameters");
        assertTrue(values.get(0).getAsJsonObject().get("value").getAsDouble() == .75, "raw float not normalized");
        assertTrue(values.get(0).getAsJsonObject().get("minimum").getAsDouble() == -2, "native range retained");
        assertTrue(values.get(1).getAsJsonObject().get("value").getAsBoolean(), "boolean is a JSON boolean");
        assertTrue(values.get(2).getAsJsonObject().get("value").getAsInt() == 7, "integer retained");
        JsonObject choice = values.get(3).getAsJsonObject();
        assertTrue(
                choice.get("value").getAsInt() == 42
                        && choice.getAsJsonArray("options")
                                        .get(1)
                                        .getAsJsonObject()
                                        .get("value")
                                        .getAsInt()
                                == 42,
                "enum values are not ordinals");
        assertTrue(choice.get("valueInOptions").getAsBoolean(), "enum membership checked");
        assertTrue(
                !result.get("nativeUndo").getAsBoolean()
                        && !result.get("nativeMutation").getAsBoolean(),
                "read never claims mutation or undo");
        assertTrue(!result.get("nativeRevisionAvailable").getAsBoolean(), "no invented revision");
        assertTrue(f.closed == 7, "module, port and child streams all closed");
    }

    @Test
    void moduleNamesPreserveTheirDistinctMeanings() {
        Fixture f = new Fixture();
        f.first.put(MODULE_NAME, "Lead voice").put(CUSTOM_NAME, "Lead voice");
        f.second.put(MODULE_NAME, "Union").put(CUSTOM_NAME, "");
        f.types.put(new UUID(2, 1), new Node(ENTRY).put(TYPE_NAME, "Union"));
        JsonArray modules = f.read(0, 64, true).getAsJsonArray("modules");
        JsonObject custom = modules.get(0).getAsJsonObject(),
                ordinary = modules.get(1).getAsJsonObject();
        assertTrue(
                custom.get("name").getAsString().equals("Lead voice"),
                "effective instance name is native, not catalog-derived");
        assertTrue(
                custom.get("customName").getAsString().equals("Lead voice")
                        && custom.get("typeName").getAsString().equals("Union"),
                "raw custom override remains distinct from catalog type");
        assertTrue(
                ordinary.get("customName").getAsString().isEmpty()
                        && ordinary.get("name").getAsString().equals("Union"),
                "absence of custom override is not filled with effective name");
        assertTrue(f.catalogReads == 0, "snapshot names use UUID lookup, not catalog enumeration");
        f.first.put(MODULE_NAME, "").put(CUSTOM_NAME, "");
        JsonObject result = f.read(0, 64, true);
        assertTrue(
                result.getAsJsonArray("modules")
                        .get(0)
                        .getAsJsonObject()
                        .get("name")
                        .getAsString()
                        .isEmpty(),
                "legitimate empty effective name is preserved");
        assertTrue(result.get("complete").getAsBoolean(), "empty native names alone do not imply missing metadata");
    }

    @Test
    void parameterNamesRemainDistinctFromIdentityAndPath() {
        Fixture f = new Fixture();
        Node floating = f.parameter(FLOAT, f.first)
                .put(FLOAT_VALUE, .25)
                .put(
                        FLOAT_DEFINITION,
                        new Node()
                                .put(FLOAT_MIN, 0.0)
                                .put(FLOAT_MAX, 1.0)
                                .put(FLOAT_UNIT, new Node().put(UNIT_TOKEN, "percent")));
        Node bool = f.parameter(BOOLEAN, f.first).put(BOOLEAN_VALUE, true);
        Node integer = f.parameter(INTEGER, f.first)
                .put(INTEGER_VALUE, 3)
                .put(
                        INTEGER_DEFINITION,
                        new Node()
                                .put(INTEGER_MIN, 0)
                                .put(INTEGER_MAX, 8)
                                .put(INTEGER_PREFIX, "")
                                .put(INTEGER_SUFFIX, ""));
        Node indexed = f.parameter(ENUM, f.first)
                .put(ENUM_VALUE, 2)
                .put(ENUM_DEFINITION, new Node().put(OPTIONS, List.of(option(2, "Pair"))));
        List<Node> children = List.of(floating, bool, integer, indexed);
        for (int i = 0; i < children.size(); i++)
            children.get(i)
                    .put(PARAMETER_ID, "PRESET_" + i)
                    .put(PARAMETER_NAME, "Control " + i)
                    .put(SHORT_NAME, i == 0 ? "" : "C" + i);
        f.first.put(CHILDREN, children);
        JsonArray values = f.read(0, 64, true)
                .getAsJsonArray("modules")
                .get(0)
                .getAsJsonObject()
                .getAsJsonArray("parameters");
        assertTrue(values.size() == 4, "all existing typed families expose descriptive metadata");
        for (int i = 0; i < values.size(); i++) {
            JsonObject value = values.get(i).getAsJsonObject();
            assertTrue(
                    value.get("parameterId").getAsString().equals("PRESET_" + i),
                    "typed family retains native local identifier");
            assertTrue(value.get("name").getAsString().equals("Control " + i), "typed family retains native name");
            assertTrue(
                    value.get("path")
                            .getAsString()
                            .equals(children.get(i).values.get(PATH)),
                    "structural path is not renamed to identifier");
            assertTrue(
                    value.get("shortName").getAsString().equals(i == 0 ? "" : "C" + i),
                    "short name retained verbatim including empty");
        }
    }

    @Test
    void disconnectedPortsKeepTheirOwnNames() {
        Fixture f = new Fixture();
        f.input.put(INPUT_KEY, "PITCH_IN").put(INPUT_NAME, "Pitch input");
        f.output.put(OUTPUT_KEY, "OUT").put(OUTPUT_NAME, "Signal output");
        JsonObject result = f.read(0, 64, true);
        JsonObject module = result.getAsJsonArray("modules").get(0).getAsJsonObject();
        JsonObject input = module.getAsJsonArray("inputs").get(0).getAsJsonObject();
        JsonObject output = module.getAsJsonArray("outputs").get(0).getAsJsonObject();
        assertTrue(
                input.get("name").getAsString().equals("Pitch input")
                        && input.get("key").getAsString().equals("PITCH_IN"),
                "input label is not a prettified key or source label");
        assertTrue(
                !input.get("connected").getAsBoolean()
                        && result.getAsJsonArray("connections").isEmpty(),
                "disconnected input keeps its own name without manufacturing a connection");
        assertTrue(
                output.get("name").getAsString().equals("Signal output")
                        && output.get("key").getAsString().equals("OUT"),
                "output label and key remain distinct");
        f.input.put(INPUT_NAME, "");
        assertTrue(
                f.read(0, 64, true)
                        .getAsJsonArray("modules")
                        .get(0)
                        .getAsJsonObject()
                        .getAsJsonArray("inputs")
                        .get(0)
                        .getAsJsonObject()
                        .get("name")
                        .getAsString()
                        .isEmpty(),
                "empty port label is not replaced with identifier");
    }

    @Test
    void definitionUnitsAndAffixesAreNotDisplayParsing() {
        Fixture f = new Fixture();
        double raw = 0.7047298732064893;
        Node floating = f.parameter(FLOAT, f.first)
                .put(FLOAT_VALUE, raw)
                .put(DISPLAY, "350 ms")
                .put(
                        FLOAT_DEFINITION,
                        new Node()
                                .put(FLOAT_MIN, 0.0)
                                .put(FLOAT_MAX, 2.0)
                                .put(FLOAT_UNIT, new Node().put(UNIT_TOKEN, "seconds")));
        Node integer = f.parameter(INTEGER, f.first)
                .put(INTEGER_VALUE, 7)
                .put(DISPLAY, "~7 voices")
                .put(
                        INTEGER_DEFINITION,
                        new Node()
                                .put(INTEGER_MIN, 1)
                                .put(INTEGER_MAX, 16)
                                .put(INTEGER_PREFIX, "~")
                                .put(INTEGER_SUFFIX, " voices"));
        f.first.put(CHILDREN, List.of(floating, integer));
        JsonObject result = f.read(0, 64, true);
        JsonArray values =
                result.getAsJsonArray("modules").get(0).getAsJsonObject().getAsJsonArray("parameters");
        JsonObject value = values.get(0).getAsJsonObject(),
                count = values.get(1).getAsJsonObject();
        assertTrue(
                value.get("numericUnit").getAsString().equals("seconds")
                        && value.get("display").getAsString().equals("350 ms"),
                "stored unit kind is not parsed from formatted magnitude-dependent suffix");
        assertTrue(value.get("value").getAsDouble() == raw, "unit kind does not convert or relabel raw value");
        assertTrue(
                result.getAsJsonObject("coverage")
                        .get("numericUnits")
                        .getAsString()
                        .equals("stored_definition_unit_kind_not_raw_value_unit"),
                "unit-kind semantics are explicit in coverage");
        assertTrue(
                count.get("displayPrefix").getAsString().equals("~")
                        && count.get("displaySuffix").getAsString().equals(" voices"),
                "integer display affixes remain separate native definition fields");
        f.display = false;
        value = f.read(0, 64, true)
                .getAsJsonArray("modules")
                .get(0)
                .getAsJsonObject()
                .getAsJsonArray("parameters")
                .get(0)
                .getAsJsonObject();
        assertTrue(
                value.get("display").isJsonNull()
                        && value.get("numericUnit").getAsString().equals("seconds"),
                "numeric-unit metadata does not depend on formatted display availability");
        f.display = true;
        f.failures.put(UNIT_TOKEN, Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "unit token unavailable"));
        result = f.read(0, 64, true);
        value = result.getAsJsonArray("modules")
                .get(0)
                .getAsJsonObject()
                .getAsJsonArray("parameters")
                .get(0)
                .getAsJsonObject();
        assertTrue(
                value.get("numericUnit").isJsonNull()
                        && value.get("display").getAsString().equals("350 ms")
                        && has(result, "numeric_unit_read_failed")
                        && !result.get("complete").getAsBoolean(),
                "failed unit metadata remains null rather than inferred from a usable display string");
    }

    @Test
    void unavailableMetadataNeverInventsLabels() {
        Fixture f = new Fixture();
        f.unavailable.add(MODULE_NAMES);
        f.types.clear();
        f.input.put(INPUT_NAME, null);
        Node bool = f.parameter(BOOLEAN, f.first)
                .put(BOOLEAN_VALUE, false)
                .put(PARAMETER_NAME, null)
                .put(SHORT_NAME, "");
        f.first.put(CHILDREN, List.of(bool));
        JsonObject result = f.read(0, 64, true);
        JsonObject module = result.getAsJsonArray("modules").get(0).getAsJsonObject();
        assertTrue(
                module.get("name").isJsonNull()
                        && module.get("customName").isJsonNull()
                        && module.get("typeName").isJsonNull(),
                "unsupported binding and absent catalog entry never invent module labels");
        assertTrue(
                module.getAsJsonArray("inputs")
                        .get(0)
                        .getAsJsonObject()
                        .get("name")
                        .isJsonNull(),
                "missing input label is null, not its key");
        JsonObject parameter = module.getAsJsonArray("parameters").get(0).getAsJsonObject();
        assertTrue(
                parameter.get("name").isJsonNull()
                        && !parameter.get("parameterId").isJsonNull()
                        && parameter.get("shortName").getAsString().isEmpty(),
                "missing parameter name does not fall back to identifier, path, or short name");
        assertTrue(
                !result.get("complete").getAsBoolean()
                        && has(result, "module_name_unavailable")
                        && has(result, "module_type_name_unavailable")
                        && has(result, "input_name_unavailable")
                        && has(result, "parameter_name_unavailable"),
                "each missing descriptive field is explicit partial coverage");
        assertTrue(
                result.getAsJsonObject("coverage")
                        .get("moduleNames")
                        .getAsString()
                        .equals("unsupported"),
                "binding availability stays explicit");
        f.unavailable.clear();
        f.failures.put(MODULE_NAME, Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "native getter failed"));
        result = f.read(0, 64, true);
        module = result.getAsJsonArray("modules").get(0).getAsJsonObject();
        assertTrue(
                module.get("name").isJsonNull()
                        && module.get("customName").getAsString().isEmpty()
                        && has(result, "module_name_read_failed"),
                "failed optional name read retains independent metadata without fabrication");
        f.failures.put(MODULE_NAME, Errors.notApplied(Reason.HOST_MISMATCH, "native gate closed"));
        assertTrue(
                has(f.read(0, 64, true), "module_name_read_failed"),
                "a closed native gate on an optional read is partial coverage, not a failed read");
    }

    @Test
    void metadataPreservesBoundsAndCancellation() {
        Fixture large = new Fixture();
        large.first.put(MODULE_NAME, "x".repeat(GridSnapshotReader.MAX_TEXT + 1));
        assertRpcError("out_of_bounds", () -> large.read(0, 64, true));
        Fixture cancelled = new Fixture();
        cancelled.failures.put(PARAMETER_NAME, Errors.notApplied(Reason.CANCELLED, "cancelled during metadata"));
        cancelled.first.put(
                CHILDREN, List.of(cancelled.parameter(BOOLEAN, cancelled.first).put(BOOLEAN_VALUE, true)));
        assertRpcError("cancelled", () -> cancelled.read(0, 64, true));
        Fixture identity = new Fixture();
        identity.first.put(DESCRIPTOR, new Node().put(TYPE_ID, null));
        assertRpcError("native_unavailable", () -> identity.read(0, 64, true));
    }

    @Test
    void pageIncludesIncomingEdgesFromOutsidePage() {
        Fixture f = new Fixture();
        Node secondInput = f.port(INPUT, f.second, "destination");
        secondInput.put(INPUT_SOURCE, f.output);
        f.second.put(INPUTS, List.of(secondInput));
        JsonObject result = f.read(1, 1, true);
        assertTrue(result.getAsJsonArray("modules").size() == 1, "exact module page");
        JsonObject edge = result.getAsJsonArray("connections").get(0).getAsJsonObject();
        assertTrue(
                edge.get("sourceModuleId")
                        .getAsString()
                        .equals(f.first.values.get(INSTANCE_ID).toString()),
                "off-page source identified by UUID");
        assertTrue(
                edge.get("targetModuleId")
                        .getAsString()
                        .equals(f.second.values.get(INSTANCE_ID).toString()),
                "returned destination identified");
        assertTrue(
                !result.get("complete").getAsBoolean()
                        && !result.getAsJsonObject("window").get("complete").getAsBoolean(),
                "page is not full graph");
        result = f.read(99, 3, true);
        assertTrue(
                result.getAsJsonArray("modules").isEmpty()
                        && result.getAsJsonObject("window").get("total").getAsInt() == 2,
                "past-end page has honest total");
    }

    @Test
    void unsupportedModelsAndExternalEdgesAreExplicit() {
        Fixture f = new Fixture();
        Node external = f.module(8, new Node());
        f.input.put(INPUT_SOURCE, f.port(OUTPUT, external, "outside"));
        f.first.put(CHILDREN, List.of(new Node()));
        f.first.put(DESCRIPTOR, null);
        f.graph.put(MODULES, List.of(f.first, new Node(), f.second));
        JsonObject result = f.read(0, 64, true);
        assertTrue(result.getAsJsonArray("connections").isEmpty(), "external graph source not falsely emitted");
        JsonObject unknown = result.getAsJsonArray("modules")
                .get(0)
                .getAsJsonObject()
                .getAsJsonArray("parameters")
                .get(0)
                .getAsJsonObject();
        assertTrue(
                !unknown.get("supported").getAsBoolean()
                        && unknown.get("reason").getAsString().equals("unknown_child_model"),
                "unknown child is explicit");
        for (String reason : List.of(
                "unknown_graph_child",
                "module_type_id_unavailable",
                "connection_source_outside_graph",
                "unknown_child_model")) assertTrue(has(result, reason), "coverage explains " + reason);
        assertTrue(!result.get("complete").getAsBoolean(), "unknown data cannot claim full coverage");
    }

    @Test
    void excludedPortChildrenRemainExplicit() {
        Fixture f = new Fixture();
        Node hidden = f.port(OUTPUT, f.first, "filtered-native-output");
        f.first.put(CHILDREN, List.of(f.input, f.output, hidden));
        JsonObject result = f.read(0, 64, true);
        assertTrue(
                !result.get("complete").getAsBoolean() && has(result, "port_child_not_enumerated"),
                "a native port child excluded from the port stream cannot silently disappear from complete coverage");
    }

    @Test
    void omittedAndUnavailableParametersAreNotComplete() {
        Fixture f = new Fixture();
        f.parameters = false;
        JsonObject result = f.read(0, 64, true);
        assertTrue(has(result, "typed_parameter_binding_unavailable"), "structure still works without typed binding");
        assertTrue(
                result.getAsJsonArray("modules").size() == 2
                        && !result.get("complete").getAsBoolean(),
                "partial capability does not fake complete");
        result = f.read(0, 64, false);
        assertTrue(has(result, "parameters_not_requested"), "omitted parameters explicitly incomplete");
        f.parameters = true;
        f.display = false;
        f.first.put(CHILDREN, List.of(f.parameter(BOOLEAN, f.first).put(BOOLEAN_VALUE, false)));
        result = f.read(0, 64, true);
        assertTrue(has(result, "parameter_display_unavailable"), "missing display separate from typed value");
    }

    @Test
    void rejectsMissingWrongAndStaleTargets() {
        Fixture f = new Fixture();
        f.proxy.put(TARGET, null);
        assertRpcError("invalid_target", () -> f.read(0, 64, true));
        f.proxy.put(TARGET, new Node());
        assertRpcError("kind_mismatch", () -> f.read(0, 64, true));
        f.proxy.put(TARGET, f.device);
        f.device.put(GRAPH, null);
        assertRpcError("kind_mismatch", () -> f.read(0, 64, true));
        f.device.put(GRAPH, f.graph);
        f.afterModules = () -> f.proxy.put(TARGET, new Node(DEVICE));
        assertRpcError("stale_context", () -> f.read(0, 64, true));
        assertTrue(f.closed > 0, "stale read still closes streams");
        Fixture other = new Fixture();
        other.document = false;
        assertRpcError("native_unavailable", () -> other.read(0, 64, true));
    }

    @Test
    void nativeOwnerMismatchPrecedesGraphAccess() {
        Fixture f = new Fixture();
        // The requested/public-observed ID is still TRACK_ID; the already-bound device has moved.
        f.owner.put(TRACK_UUID, new UUID(3, 2));
        assertRpcError("stale_context", () -> f.read(0, 64, true));
        assertTrue(
                f.graphReads == 0 && f.closed == 0,
                "native rebound owner rejected before graph accessor or enumeration");
        Fixture matching = new Fixture();
        matching.owner.put(TRACK_UUID, new UUID(TRACK_ID.getMostSignificantBits(), TRACK_ID.getLeastSignificantBits()));
        JsonObject result = matching.read(0, 64, true);
        assertTrue(
                result.getAsJsonArray("modules").size() == 2 && matching.graphReads == 2,
                "ordinary owner matches by UUID value, not object identity");
    }

    @Test
    void channelAliasUsesPublicChannelUuidMapping() {
        Fixture f = new Fixture();
        f.alias();
        f.owner.put(TRACK_UUID, new UUID(3, 9));
        f.failures.put(TRACK_UUID, Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "raw SCA UUID must not be read"));
        assertTrue(
                f.read(0, 64, true).getAsJsonArray("modules").size() == 2,
                "SCA uses alias UUID even when raw owner UUID differs");
        Fixture mismatch = new Fixture();
        mismatch.alias().put(ALIAS_UUID, new UUID(3, 9));
        assertRpcError("stale_context", () -> mismatch.read(0, 64, true));
        assertTrue(mismatch.graphReads == 0, "matching raw owner UUID cannot rescue mismatched alias UUID");
    }

    @Test
    void missingNativeIdentityFailsClosed() {
        for (Object owner : Arrays.asList(null, new Node(), "not a native owner")) {
            Fixture f = new Fixture();
            f.device.put(OWNER_TRACK, owner);
            assertRpcError("native_unavailable", () -> f.read(0, 64, true));
            assertTrue(f.graphReads == 0, "absent or wrong-type owning track blocks graph access");
        }
        for (Object alias : Arrays.asList(null, new Node(TRACK), "not a native alias")) {
            Fixture f = new Fixture();
            f.alias();
            f.owner.put(TRACK_ALIAS, alias);
            assertRpcError("native_unavailable", () -> f.read(0, 64, true));
            assertTrue(f.graphReads == 0, "absent or wrong-type channel alias blocks graph access");
        }
        for (Object uuid : Arrays.asList(null, TRACK_ID.toString(), new Node())) {
            Fixture ordinary = new Fixture();
            ordinary.owner.put(TRACK_UUID, uuid);
            assertRpcError("native_unavailable", () -> ordinary.read(0, 64, true));
            assertTrue(ordinary.graphReads == 0, "ordinary UUID must be a native UUID value");
            Fixture aliased = new Fixture();
            aliased.alias().put(ALIAS_UUID, uuid);
            assertRpcError("native_unavailable", () -> aliased.read(0, 64, true));
            assertTrue(aliased.graphReads == 0, "alias UUID cannot fall back to ordinary UUID");
        }
        Fixture unavailable = new Fixture();
        unavailable.failures.put(OWNER_TRACK, Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "unbound identity"));
        assertRpcError("native_unavailable", () -> unavailable.read(0, 64, true));
        assertTrue(
                unavailable.graphReads == 0, "unavailable required identity never degrades into structure-only read");
        assertTrue(
                unavailable
                        .reader
                        .catalog("", 0, 64, () -> {})
                        .getAsJsonArray("entries")
                        .isEmpty(),
                "catalog is independent of native device ownership");
        Fixture absentRequest = new Fixture();
        assertRpcError(
                "native_unavailable",
                () -> absentRequest.reader.snapshot(absentRequest.proxy, null, 0, 64, true, () -> {}));
        assertTrue(absentRequest.targetReads == 0, "missing expected UUID fails before native target resolution");
    }

    @Test
    void nativeIdentityRequiresThreadOwnership() {
        Fixture wrongThread = new Fixture();
        wrongThread.document = false;
        assertRpcError("native_unavailable", () -> wrongThread.read(0, 64, true));
        assertTrue(wrongThread.targetReads == 0, "application thread checked before native proxy resolution");
        for (int kind = 0; kind < 3; kind++) {
            Fixture f = new Fixture();
            Node inaccessible = kind == 0 ? f.device : kind == 1 ? f.owner : f.alias();
            inaccessible.threadAllowed = false;
            assertRpcError("native_unavailable", () -> f.read(0, 64, true));
            assertTrue(
                    f.graphReads == 0 && f.uuidReads == 0,
                    "device, owner and alias require a valid thread predicate before UUID/graph access");
        }
        Fixture finalThread = new Fixture();
        finalThread.afterModules = () -> finalThread.document = false;
        assertRpcError("native_unavailable", () -> finalThread.read(0, 64, true));
        assertTrue(finalThread.targetReads == 1, "completion rechecks application thread before native proxy access");
    }

    @Test
    void nativeIdentityChangesAtFinalGuardAreRejected() {
        Fixture owner = new Fixture();
        owner.afterModules = () -> owner.device.put(OWNER_TRACK, new Node(TRACK).put(TRACK_UUID, TRACK_ID));
        assertRpcError("stale_context", () -> owner.read(0, 64, true));
        Fixture alias = new Fixture();
        alias.alias();
        alias.afterModules = () -> alias.owner.put(TRACK_ALIAS, new Node(CHANNEL_ALIAS).put(ALIAS_UUID, TRACK_ID));
        assertRpcError("stale_context", () -> alias.read(0, 64, true));
        Fixture uuid = new Fixture();
        uuid.afterModules = () -> uuid.owner.put(TRACK_UUID, new UUID(3, 9));
        assertRpcError("stale_context", () -> uuid.read(0, 64, true));
        Fixture aliasUuid = new Fixture();
        Node identity = aliasUuid.alias();
        aliasUuid.afterModules = () -> identity.put(ALIAS_UUID, new UUID(3, 9));
        assertRpcError("stale_context", () -> aliasUuid.read(0, 64, true));
        Fixture missing = new Fixture();
        missing.afterModules = () -> missing.device.put(OWNER_TRACK, null);
        assertRpcError("native_unavailable", () -> missing.read(0, 64, true));
        Fixture missingAlias = new Fixture();
        missingAlias.alias();
        missingAlias.afterModules = () -> missingAlias.owner.put(TRACK_ALIAS, null);
        assertRpcError("native_unavailable", () -> missingAlias.read(0, 64, true));
        Fixture wrongContext = new Fixture();
        Node finalAlias = wrongContext.alias();
        wrongContext.afterModules = () -> finalAlias.threadAllowed = false;
        assertRpcError("native_unavailable", () -> wrongContext.read(0, 64, true));
    }

    @Test
    void boundsCloseStreamsAndHonorCancellation() {
        Fixture f = new Fixture();
        f.graph.put(MODULES, (Supplier<Stream<?>>)
                () -> Stream.generate(() -> f.first).onClose(() -> f.closed++));
        assertRpcError("out_of_bounds", () -> f.read(0, 64, true));
        assertTrue(f.closed == 1, "infinite stream bounded and closed");
        Fixture cancelled = new Fixture();
        int[] calls = {0};
        assertRpcError(
                "cancelled",
                () -> cancelled.reader.snapshot(cancelled.proxy, TRACK_ID, 0, 64, true, () -> {
                    if (++calls[0] == 3) throw Errors.notApplied(Reason.CANCELLED, "cancelled mid-traversal");
                }));
        assertTrue(cancelled.closed == 1, "cancellation closes active stream");
        Fixture children = new Fixture();
        children.first.put(CHILDREN, Collections.nCopies(257, new Node()));
        assertRpcError("out_of_bounds", () -> children.read(0, 64, true));
        Fixture choices = new Fixture();
        Node indexed = choices.parameter(ENUM, choices.first).put(ENUM_VALUE, 0);
        indexed.put(ENUM_DEFINITION, new Node().put(OPTIONS, Collections.nCopies(257, option(0, "x"))));
        choices.first.put(CHILDREN, List.of(indexed));
        assertRpcError("out_of_bounds", () -> choices.read(0, 64, true));
    }

    @Test
    void catalogMatchesLooselyAndSuggestsNearNames() {
        Fixture f = new Fixture();
        f.catalog = List.of(
                entry("MODULE", "Low-pass", 1),
                entry("MODULE", "Low-pass LD", 2),
                entry("MODULE", "Low-pass MG", 3),
                entry("MODULE", "High-pass", 4),
                entry("MODULE", "Oscillator", 5),
                entry("DEVICE", "Low-pass device", 6));
        for (String query : List.of("lowpass", "low pass", "LOW-PASS", "low  pass", "pass low", "Low-pass."))
            assertTrue(
                    f.reader
                                    .catalog(query, 0, 64, () -> {})
                                    .getAsJsonArray("entries")
                                    .size()
                            == 3,
                    "loose query '" + query + "' finds every Low-pass module");
        assertTrue(
                f.reader
                                .catalog("lowpass ld", 0, 64, () -> {})
                                .getAsJsonArray("entries")
                                .size()
                        == 1,
                "every query word must match");
        JsonObject none = f.reader.catalog("lowpas", 0, 64, () -> {});
        assertTrue(none.getAsJsonArray("entries").size() == 3, "substring of the normalized name still matches");
        none = f.reader.catalog("lp mg", 0, 64, () -> {});
        JsonArray hints = none.getAsJsonArray("suggestions");
        assertTrue(
                none.getAsJsonArray("entries").isEmpty()
                        && hints != null
                        && hints.size() <= 5
                        && hints.size() > 0
                        && hints.get(0).getAsJsonObject().has("typeId")
                        && hints.get(0).getAsJsonObject().has("name"),
                "a miss returns up to five nearest module names with their typeIds");
        assertTrue(
                f.reader.catalog("lowpass", 0, 64, () -> {}).get("suggestions") == null,
                "suggestions only accompany a miss");
        assertTrue(
                f.reader
                        .catalog("oscilator", 0, 64, () -> {})
                        .getAsJsonArray("suggestions")
                        .get(0)
                        .getAsJsonObject()
                        .get("name")
                        .getAsString()
                        .equals("Oscillator"),
                "a typo suggests the closest name first");
        assertTrue(
                f.reader
                                .catalog("zzzzzzzz", 0, 64, () -> {})
                                .getAsJsonArray("suggestions")
                                .size()
                        == 0,
                "an unrelated query suggests nothing");
        assertTrue(
                f.reader.catalog("", 0, 64, () -> {}).get("suggestions") == null,
                "empty query lists everything without suggestions");
    }

    @Test
    void displayWritesConvertOnlyVerifiedUnits() {
        WriterFixture f = unitFixture("hertz", 84.0, "1.05 kHz", 0.0, 127.0);
        f.afterMutation = () -> f.child.put(DISPLAY, "1.20 kHz");
        JsonObject result = f.set(displayRequest("84", "1.2 kHz"));
        double raw = (Double) f.child.values.get(FLOAT_VALUE);
        assertTrue(
                f.calls == 1
                        && Math.abs(raw - (69 + 12 * Math.log(1200.0 / 440) / Math.log(2))) < 1e-9
                        && result.get("requestedDisplay").getAsString().equals("1.2 kHz")
                        && !result.get("displayMismatch").getAsBoolean()
                        && result.getAsJsonObject("parameter")
                                .get("display")
                                .getAsString()
                                .equals("1.20 kHz"),
                "hertz display is converted as a MIDI note, written once, and read back as the requested text");
        // Nothing is rewritten when the readback disagrees: it is only reported.
        WriterFixture off = unitFixture("hertz", 84.0, "1.05 kHz", 0.0, 127.0);
        off.afterMutation = () -> off.child.put(DISPLAY, "1.50 kHz");
        result = off.set(displayRequest("84", "1.2 kHz"));
        assertTrue(
                off.calls == 1 && result.get("displayMismatch").getAsBoolean(),
                "a differing post-write display is reported and never corrected by a second write");
        // Percent: raw is 0..1 shown times 100; the scale is confirmed from the row's own pair.
        WriterFixture pct = unitFixture("percent", 0.5, "50.0 %", 0.0, 1.0);
        pct.afterMutation = () -> pct.child.put(DISPLAY, "80.0 %");
        result = pct.set(displayRequest("0.5", "80 %"));
        assertTrue(
                Math.abs((Double) pct.child.values.get(FLOAT_VALUE) - 0.8) < 1e-12
                        && !result.get("displayMismatch").getAsBoolean(),
                "percent display converts as raw = percent / 100");
        WriterFixture db = unitFixture("decibel", -96.0, "-96.0 dB", -144.0, 0.0);
        db.set(displayRequest("-96", "-12 dB"));
        assertTrue(
                (Double) db.child.values.get(FLOAT_VALUE) == -12.0,
                "decibel display converts as raw = dB where the row confirms it");
        // Unverified or unconfirmed scales are rejected before any write.
        for (WriterFixture bad : List.of(
                unitFixture("seconds", 0.7047298732064893, "350 ms", 0.0, 2.0),
                unitFixture("semitones_relative", 0.0, "0.00 st", -48.0, 48.0),
                unitFixture("hertz", 0.0, "0.00 Hz", -3.0, 3.0),
                unitFixture("percent", 0.0, "0.00 %", 0.0, 1.0),
                unitFixture("percent", 0.5, "80.0 %", 0.0, 1.0),
                unitFixture(null, 0.5, "50.0 %", 0.0, 1.0))) {
            RpcException error = assertThrows(RpcException.class, () -> {
                bad.set(displayRequest(bad.child.values.get(FLOAT_VALUE).toString(), "1 Hz"));
            });
            assertTrue(
                    error.reason.wire().equals("unsupported_unit")
                            && !error.unknown
                            && bad.calls == 0
                            && !bad.op.dispatched(),
                    "unverified unit or a row that does not reproduce the model is unsupported_unit before any write");
        }
        WriterFixture suffix = unitFixture("hertz", 84.0, "1.05 kHz", 0.0, 127.0);
        for (String text : List.of("1.2", "1.2 %", "abc Hz", "-5 Hz", "0 Hz", "1.2 kHz extra"))
            assertRpcError("invalid_params", () -> suffix.set(displayRequest("84", text)));
        assertTrue(suffix.calls == 0, "display text needs a number with a matching explicit unit suffix");
        assertRpcError(
                "invalid_params",
                () -> GridParameterWriter.Request.parse(
                        HostFixture.json("{\"expectedKind\":\"float\",\"expectedValue\":1}")));
        assertRpcError(
                "invalid_params",
                () -> GridParameterWriter.Request.parse(HostFixture.json(
                        "{\"expectedKind\":\"float\",\"expectedValue\":1,\"value\":2,\"display\":\"1 Hz\"}")));
        assertRpcError(
                "invalid_params",
                () -> GridParameterWriter.Request.parse(
                        HostFixture.json("{\"expectedKind\":\"integer\",\"expectedValue\":1,\"display\":\"1 Hz\"}")));
        assertRpcError(
                "stale_context",
                () -> unitFixture("hertz", 84.0, "1.05 kHz", 0.0, 127.0).set(displayRequest("83", "1.2 kHz")));
        // Batch entries carry the same fields and the same CAS.
        WriterFixture batch = unitFixture("hertz", 84.0, "1.05 kHz", 0.0, 127.0);
        batch.afterMutation = () -> batch.child.put(DISPLAY, "440 Hz");
        JsonObject out = batch.setAll(
                List.of(new GridParameterWriter.Entry(new UUID(1, 1), "LOCAL_ID", displayRequest("84", "440 Hz"))),
                () -> {},
                () -> batch.op.markDispatched());
        JsonObject item = out.getAsJsonArray("writes").get(0).getAsJsonObject();
        assertTrue(
                (Double) batch.child.values.get(FLOAT_VALUE) == 69.0
                        && item.get("requestedDisplay").getAsString().equals("440 Hz")
                        && !item.get("displayMismatch").getAsBoolean()
                        && item.get("outcome").getAsString().equals("applied"),
                "batch entry accepts display and reports displayMismatch");
    }

    private static WriterFixture unitFixture(String token, double raw, String display, double min, double max) {
        WriterFixture f = new WriterFixture();
        f.scalar(FLOAT, raw, min, max);
        Node definition = new Node().put(FLOAT_MIN, min).put(FLOAT_MAX, max);
        if (token != null) definition.put(FLOAT_UNIT, new Node().put(UNIT_TOKEN, token));
        f.child.put(FLOAT_DEFINITION, definition).put(DISPLAY, display);
        return f;
    }

    private static GridParameterWriter.Request displayRequest(String expected, String display) {
        JsonObject params = HostFixture.json("{\"expectedKind\":\"float\",\"expectedValue\":" + expected + "}");
        params.addProperty("display", display);
        return GridParameterWriter.Request.parse(params);
    }

    @Test
    void catalogFiltersKindsSortsAndPaginates() {
        Fixture f = new Fixture();
        f.catalog = List.of(
                entry("MODULE", "zeta", 4),
                entry("DEVICE", "alpha", 5),
                entry("MODULE", "Alpha", 3),
                entry("MODULE", "alpha", 2));
        JsonObject result = f.reader.catalog("ALP", 1, 1, () -> {});
        assertTrue(result.getAsJsonArray("entries").size() == 1, "bounded filtered catalog");
        assertTrue(
                result.getAsJsonArray("entries")
                        .get(0)
                        .getAsJsonObject()
                        .get("name")
                        .getAsString()
                        .equals("alpha"),
                "deterministic case-insensitive sorting tie-break");
        assertTrue(result.getAsJsonObject("window").get("total").getAsInt() == 2, "total after MODULE-only filter");
        result = f.reader.catalog("no match", 0, 64, () -> {});
        assertTrue(
                result.getAsJsonObject("window").get("complete").getAsBoolean(),
                "genuine empty catalog match is complete");
        f.catalog = Collections.nCopies(GridSnapshotReader.MAX_CATALOG + 1, entry("MODULE", "x", 1));
        assertRpcError("out_of_bounds", () -> f.reader.catalog("", 0, 64, () -> {}));
    }

    @Test
    void rejectsInvalidNativeValuesAndOwnership() {
        Fixture f = new Fixture();
        Node floating = f.parameter(FLOAT, f.first).put(FLOAT_VALUE, Double.NaN);
        f.first.put(CHILDREN, List.of(floating));
        assertRpcError("native_unavailable", () -> f.read(0, 64, true));
        floating.put(FLOAT_VALUE, 1.0)
                .put(FLOAT_DEFINITION, new Node().put(FLOAT_MIN, 4.0).put(FLOAT_MAX, 2.0));
        assertRpcError("native_unavailable", () -> f.read(0, 64, true));
        Fixture owners = new Fixture();
        owners.input.put(OWNER_MODULE, owners.second);
        assertRpcError("stale_context", () -> owners.read(0, 64, true));
        Fixture executor = new Fixture();
        executor.first.threadAllowed = false;
        assertRpcError("native_unavailable", () -> executor.read(0, 64, true));
        Fixture identity = new Fixture();
        identity.second.put(INSTANCE_ID, identity.first.values.get(INSTANCE_ID));
        assertRpcError("native_unavailable", () -> identity.read(0, 64, true));
        Fixture enums = new Fixture();
        Node indexed = enums.parameter(ENUM, enums.first).put(ENUM_VALUE, 99);
        indexed.put(ENUM_DEFINITION, new Node().put(OPTIONS, List.of(option(2, "Two"))));
        enums.first.put(CHILDREN, List.of(indexed));
        assertTrue(
                has(enums.read(0, 64, true), "enum_value_not_in_options"),
                "unknown enum value is not relabeled ordinal");
    }

    @Test
    void singleParameterUsesExactIdentityAndExistingTypedSemantics() {
        Fixture f = new Fixture();
        Node floating = f.parameter(FLOAT, f.first)
                .put(PARAMETER_ID, "")
                .put(FLOAT_VALUE, -1.25)
                .put(FLOAT_DEFINITION, new Node().put(FLOAT_MIN, -2.0).put(FLOAT_MAX, 4.0));
        Node bool = f.parameter(BOOLEAN, f.first).put(PARAMETER_ID, "BOOL").put(BOOLEAN_VALUE, false);
        Node integer = f.parameter(INTEGER, f.first)
                .put(PARAMETER_ID, "INT")
                .put(INTEGER_VALUE, -7)
                .put(INTEGER_DEFINITION, new Node().put(INTEGER_MIN, -10).put(INTEGER_MAX, 12));
        Node indexed = f.parameter(ENUM, f.first)
                .put(PARAMETER_ID, "ENUM")
                .put(ENUM_VALUE, 42)
                .put(ENUM_DEFINITION, new Node().put(OPTIONS, List.of(option(10, "Ten"), option(42, "Answer"))));
        List<Node> children = List.of(floating, bool, integer, indexed);
        f.first.put(CHILDREN, children);
        ContextFixture context = new ContextFixture();
        JsonArray graphValues = f.read(0, 64, true)
                .getAsJsonArray("modules")
                .get(0)
                .getAsJsonObject()
                .getAsJsonArray("parameters");
        List<JsonElement> expectedValues = List.of(
                new JsonPrimitive(-1.25), new JsonPrimitive(false), new JsonPrimitive(-7), new JsonPrimitive(42));
        for (int i = 0; i < children.size(); i++) {
            JsonObject result =
                    inspect(f, context, (String) children.get(i).values.get(PARAMETER_ID));
            assertTrue(
                    result.getAsJsonObject("parameter").equals(graphValues.get(i)),
                    "single parameter preserves typed raw/display/options and partial metadata");
            assertTrue(
                    result.getAsJsonObject("parameter").get("value").equals(expectedValues.get(i)),
                    "exact family returns stored value, not normalized value or enum ordinal");
        }
        // The other module is unreadable; a single-parameter read must never serialize its values.
        f.second.put(CHILDREN, List.of(f.parameter(FLOAT, f.second).put(FLOAT_VALUE, Double.NaN)));
        assertTrue(
                inspect(f, context, "ENUM")
                                .getAsJsonObject("parameter")
                                .get("value")
                                .getAsInt()
                        == 42,
                "inspection resolves one exact parameter, not an entire graph serialization");
        assertRpcError("stale_context", () -> inspect(f, context, "enum"));
        assertRpcError("stale_context", () -> inspect(f, context, "Native control"));
        assertRpcError("stale_context", () -> inspect(f, context, "parameter/ENUM"));
    }

    @Test
    void parameterInspectionRejectsAmbiguityAndStaleMembership() {
        Fixture f = inspectionFixture();
        ContextFixture context = new ContextFixture();
        f.second.put(INSTANCE_ID, f.first.values.get(INSTANCE_ID));
        assertRpcError("stale_context", () -> inspect(f, context, "LOCAL_ID"));
        f.second.put(INSTANCE_ID, new UUID(1, 2));
        Node child = (Node) ((List<?>) f.first.values.get(CHILDREN)).get(0);
        f.first.put(CHILDREN, List.of(child, f.parameter(BOOLEAN, f.first).put(BOOLEAN_VALUE, false)));
        assertRpcError("stale_context", () -> inspect(f, context, "LOCAL_ID"));
        f.first.put(CHILDREN, List.of(child));
        child.put(OWNER_MODULE, f.second);
        assertRpcError("stale_context", () -> inspect(f, context, "LOCAL_ID"));
        child.put(OWNER_MODULE, f.first);
        f.first.put(MODULE_GRAPH, new Node());
        assertRpcError("stale_context", () -> inspect(f, context, "LOCAL_ID"));
        f.first.put(MODULE_GRAPH, f.graph);
        child.kinds.remove(BOOLEAN);
        assertRpcError("native_unavailable", () -> inspect(f, context, "LOCAL_ID"));
        child.kinds.add(BOOLEAN);
        f.first.put(CHILDREN, Collections.nCopies(257, child));
        assertRpcError("out_of_bounds", () -> inspect(f, context, "LOCAL_ID"));
        for (int change = 0; change < 5; change++) {
            Fixture moved = inspectionFixture();
            ContextFixture c = new ContextFixture();
            final int mode = change;
            moved.afterValue = () -> {
                if (mode == 0) moved.first.put(CHILDREN, List.of());
                if (mode == 1)
                    moved.first.put(
                            CHILDREN,
                            List.of(moved.parameter(BOOLEAN, moved.first).put(BOOLEAN_VALUE, true)));
                if (mode == 2) moved.graph.put(MODULES, List.of(moved.module(0, moved.graph)));
                if (mode == 3) moved.first.put(INSTANCE_ID, new UUID(7, 8));
                if (mode == 4) moved.device.put(OWNER_TRACK, new Node(TRACK).put(TRACK_UUID, new UUID(8, 9)));
            };
            assertRpcError("stale_context", () -> inspect(moved, c, "LOCAL_ID"));
        }
        Fixture cancelled = inspectionFixture();
        int[] calls = {0};
        assertRpcError(
                "cancelled",
                () -> cancelled.reader.parameter(
                        cancelled.proxy, TRACK_ID, new UUID(1, 1), "LOCAL_ID", context.reader, () -> {
                            if (++calls[0] == 5) throw Errors.notApplied(Reason.CANCELLED, "cancelled");
                        }));
        assertTrue(cancelled.closed > 0, "cancelled exact lookup closes native streams");
    }

    @Test
    void parameterContextObservationsDoNotInventPermission() {
        Fixture f = inspectionFixture();
        ContextFixture c = new ContextFixture();
        c.values.put(GridParameterContext.Field.LICENSED, false);
        c.values.put(GridParameterContext.Field.SETTABLE, false);
        c.values.put(GridParameterContext.Field.CIU, true);
        c.values.put(GridParameterContext.Field.TOUCHED, true);
        c.values.put(GridParameterContext.Field.UNDO_EXECUTING, true);
        c.values.put(GridParameterContext.Field.UNDO_ACTIVE, true);
        c.values.put(GridParameterContext.Field.UNDO_ICL, true);
        Object foreignDt = new Object();
        c.values.put(GridParameterContext.Field.LOCK_OWNER, foreignDt);
        JsonObject result = inspect(f, c, "LOCAL_ID").getAsJsonObject("editContext");
        assertTrue(
                !result.get("effectivelyLicensed").getAsBoolean()
                        && !result.getAsJsonObject("guiProperty")
                                .get("isSettable")
                                .getAsBoolean(),
                "license and actual property descriptor are independent observed false states, not a read rejection");
        assertTrue(
                result.get("nativeCIu").getAsBoolean()
                        && result.get("parameterTouched").getAsBoolean(),
                "opaque skip and active touch remain distinct observations");
        assertTrue(
                result.getAsJsonObject("ancestorUndoManager")
                                .get("executingUndoRedo")
                                .getAsBoolean()
                        && result.getAsJsonObject("ancestorUndoManager")
                                .get("activeChange")
                                .getAsBoolean(),
                "active undo states remain observable without mutation");
        assertTrue(
                !result.getAsJsonObject("deviceLock").get("ownerIsCurrentDt").getAsBoolean(),
                "foreign lock owner is not reported as current Dt");
        c.values.put(GridParameterContext.Field.UNDO, null);
        c.values.put(GridParameterContext.Field.LOCK_OWNER, null);
        c.values.put(NativeReadContext.Field.CURRENT_DT, null);
        c.defaultSupported = false;
        result = inspect(f, c, "LOCAL_ID").getAsJsonObject("editContext");
        assertTrue(
                !result.getAsJsonObject("ancestorUndoManager").get("present").getAsBoolean()
                        && result.getAsJsonObject("ancestorUndoManager")
                                .get("activeChange")
                                .isJsonNull(),
                "missing undo manager is not an idle manager");
        assertTrue(
                result.getAsJsonObject("deviceLock").get("ownerIsCurrentDt").isJsonNull()
                        && result.getAsJsonObject("attachedContext")
                                .get("currentDtIsDefault")
                                .isJsonNull(),
                "missing owner/default context is unknown rather than compatible");
    }

    @Test
    void parameterContextChangesAndUnavailableBindingsFailClosed() {
        for (Enum<?> field : List.of(
                NativeReadContext.Field.ROOT,
                NativeReadContext.Field.CURRENT_DT,
                NativeReadContext.Field.DEFAULT_DT,
                GridParameterContext.Field.UNDO,
                GridParameterContext.Field.LOCK,
                GridParameterContext.Field.LOCK_OWNER)) {
            Fixture f = inspectionFixture();
            ContextFixture c = new ContextFixture();
            f.afterValue = () -> c.values.put(field, new Object());
            assertRpcError("stale_context", () -> inspect(f, c, "LOCAL_ID"));
        }
        Fixture rebound = inspectionFixture();
        ContextFixture reboundContext = new ContextFixture();
        rebound.afterValue = () -> {
            Object replacement = new Object();
            reboundContext.values.put(NativeReadContext.Field.CONTEXT, replacement);
            reboundContext.values.put(NativeReadContext.Field.DT_CONTEXT, replacement);
        };
        assertRpcError("stale_context", () -> inspect(rebound, reboundContext, "LOCAL_ID"));
        Fixture f = inspectionFixture();
        ContextFixture c = new ContextFixture();
        c.values.put(NativeReadContext.Field.CONTEXT, null);
        assertRpcError("stale_context", () -> inspect(f, c, "LOCAL_ID"));
        c.values.put(NativeReadContext.Field.CONTEXT, new Object());
        assertRpcError("stale_context", () -> inspect(f, c, "LOCAL_ID"));
        ContextFixture deleted = new ContextFixture();
        deleted.values.put(NativeReadContext.Field.DELETED, true);
        assertRpcError("stale_context", () -> inspect(f, deleted, "LOCAL_ID"));
        ContextFixture disposed = new ContextFixture();
        disposed.values.put(NativeReadContext.Field.DISPOSED, true);
        assertRpcError("stale_context", () -> inspect(f, disposed, "LOCAL_ID"));
        ContextFixture unavailable = new ContextFixture();
        unavailable.values.put(GridParameterContext.Field.SETTABLE, null);
        assertRpcError("native_unavailable", () -> inspect(f, unavailable, "LOCAL_ID"));
        ContextFixture missingWrapper = new ContextFixture();
        missingWrapper.values.put(GridParameterContext.Field.GUI_BOOLEAN, null);
        assertRpcError("native_unavailable", () -> inspect(f, missingWrapper, "LOCAL_ID"));
        ContextFixture foreignProject = new ContextFixture();
        foreignProject.values.put(GridParameterContext.Field.PARAMETER_PROJECT, new Object());
        assertRpcError("stale_context", () -> inspect(f, foreignProject, "LOCAL_ID"));
        assertRpcError(
                "native_unavailable",
                () -> f.reader.parameter(f.proxy, TRACK_ID, new UUID(1, 1), "LOCAL_ID", null, () -> {}));
        assertTrue(
                f.read(0, 64, true).getAsJsonArray("modules").size() == 2,
                "unavailable inspection context does not disable ordinary graph reads");
    }

    private static Fixture inspectionFixture() {
        Fixture f = new Fixture();
        f.first.put(CHILDREN, List.of(f.parameter(BOOLEAN, f.first).put(BOOLEAN_VALUE, true)));
        return f;
    }

    private static JsonObject inspect(Fixture f, ContextFixture context, String id) {
        return f.reader.parameter(f.proxy, TRACK_ID, new UUID(1, 1), id, context.reader, () -> {});
    }

    private static GridParameterWriter.Request request(String kind, String expected, String value) {
        return GridParameterWriter.Request.parse(HostFixture.json(
                "{\"expectedKind\":\"" + kind + "\",\"expectedValue\":" + expected + ",\"value\":" + value + "}"));
    }

    @Test
    void rawParameterTypesAndRepresentability() {
        for (String value : List.of("null", "\"1\"", "true", "1.0", "1e0", "2147483648", "-2147483649"))
            assertRpcError("invalid_params", () -> request("integer", "0", value));
        for (String value : List.of("0", "1", "\"false\"", "null"))
            assertRpcError("invalid_params", () -> request("boolean", "false", value));
        for (String value : List.of("1e309", "-1e309", "true", "\"0.5\"", "null", "9007199254740993"))
            assertRpcError("invalid_params", () -> request("float", "0", value));
        assertRpcError("invalid_params", () -> request("float", "9007199254740993", "0"));
        assertRpcError("invalid_params", () -> request("enum", "1.0", "2"));
        assertRpcError("invalid_params", () -> request("number", "0", "1"));
        assertTrue(
                request("integer", "-2147483648", "2147483647").rawValue == Integer.MAX_VALUE,
                "signed integer boundaries remain exact");
        assertTrue(request("boolean", "true", "false").rawValue == 0.0, "boolean converted only for native raw call");
        assertTrue(
                request("float", "0", "9007199254740992").rawValue == 9007199254740992.0,
                "exact large binary64 integer remains usable");
    }

    @Test
    void rawParameterCasRangesAndActualEnumValues() {
        WriterFixture f = new WriterFixture();
        assertRpcError("stale_context", () -> f.set(request("boolean", "false", "true")));
        assertRpcError("stale_context", () -> f.set(request("float", "1", "1")));
        assertTrue(f.calls == 0, "CAS and kind precede same-value no-op");
        f.scalar(FLOAT, 0.5, -1.0, 1.0);
        assertRpcError("invalid_params", () -> f.set(request("float", "0.5", "1.0001")));
        assertRpcError("stale_context", () -> f.set(request("float", "0.5000000000000001", "0.5")));
        assertRpcError("stale_context", () -> f.set(request("float", "0.4", "2")));
        f.scalar(INTEGER, -7, -10, 12);
        assertRpcError("invalid_params", () -> f.set(request("integer", "-7", "13")));
        f.set(request("integer", "-7", "12"));
        assertTrue(
                (Integer) f.child.values.get(INTEGER_VALUE) == 12,
                "signed raw integer edit reaches exact bounded model value");
        f.scalar(ENUM, 42, 0, 0);
        assertRpcError("invalid_params", () -> f.set(request("enum", "42", "1")));
        f.set(request("enum", "42", "10"));
        assertTrue((Integer) f.child.values.get(ENUM_VALUE) == 10, "enum uses listed nonordinal option value");
        f.child.put(ENUM_VALUE, 99);
        assertRpcError("native_unavailable", () -> f.set(request("enum", "99", "10")));
    }

    @Test
    void diagnosticsGateNativeIdentityAndModuleFilterSelectsRows() {
        Fixture f = inspectionFixture();
        JsonObject quiet = f.reader.snapshot(f.proxy, TRACK_ID, 0, 64, true, false, null, () -> {});
        JsonObject row = quiet.getAsJsonArray("modules").get(0).getAsJsonObject();
        assertTrue(
                !row.has("modelClass")
                        && !row.getAsJsonArray("parameters")
                                .get(0)
                                .getAsJsonObject()
                                .has("path")
                        && !quiet.has("coverage")
                        && !quiet.has("identityCaveat")
                        && !quiet.has("device")
                        && !quiet.has("graph"),
                "default read omits obfuscated native class/path and constant coverage prose");
        assertTrue(
                row.get("instanceId").getAsString().equals(new UUID(1, 1).toString())
                        && row.has("localKey")
                        && row.has("typeId")
                        && row.getAsJsonArray("parameters")
                                .get(0)
                                .getAsJsonObject()
                                .has("parameterId"),
                "identity fields writes need stay in the default read");
        JsonObject loud = f.reader.snapshot(f.proxy, TRACK_ID, 0, 64, true, true, null, () -> {});
        assertTrue(
                loud.getAsJsonArray("modules").get(0).getAsJsonObject().has("modelClass")
                        && loud.has("coverage")
                        && loud.has("identityCaveat"),
                "diagnostics restores native class/path and coverage prose");
        String second = new UUID(1, 2).toString(), absent = new UUID(9, 9).toString();
        JsonObject filtered =
                f.reader.snapshot(f.proxy, TRACK_ID, 0, 64, true, false, Set.of(second, absent), () -> {});
        assertTrue(
                filtered.getAsJsonArray("modules").size() == 1
                        && filtered.getAsJsonArray("modules")
                                .get(0)
                                .getAsJsonObject()
                                .get("instanceId")
                                .getAsString()
                                .equals(second)
                        && filtered.getAsJsonArray("missingModuleIds").size() == 1
                        && filtered.getAsJsonArray("missingModuleIds")
                                .get(0)
                                .getAsString()
                                .equals(absent)
                        && !filtered.get("complete").getAsBoolean()
                        && filtered.getAsJsonObject("window").get("total").getAsInt() == 1,
                "moduleIds returns only matching rows, names the missing ids and is never reported complete");
    }

    private static WriterFixture batchFixture() {
        WriterFixture f = new WriterFixture();
        Node other = f.model
                .parameter(BOOLEAN, f.model.first)
                .put(PARAMETER_ID, "OTHER_ID")
                .put(BOOLEAN_VALUE, true);
        Node third = f.model
                .parameter(BOOLEAN, f.model.first)
                .put(PARAMETER_ID, "THIRD_ID")
                .put(BOOLEAN_VALUE, true);
        f.model.first.put(CHILDREN, List.of(f.child, other, third));
        return f;
    }

    private static GridParameterWriter.Entry entry(String id, String expected, String value) {
        return new GridParameterWriter.Entry(new UUID(1, 1), id, request("boolean", expected, value));
    }

    private static JsonObject batch(WriterFixture f, GridParameterWriter.Entry... entries) {
        return f.writer.setAll(f.model.proxy, TRACK_ID, List.of(entries), () -> {}, () -> f.op.markDispatched());
    }

    /** Settled without success: an error, a cancellation or a result with outcome unknown. */
    static boolean unsuccessful(HostOperation op) {
        return op.result.isCompletedExceptionally()
                || op.result.isDone() && !Results.outcomeOf(op.result.join()).succeeded();
    }

    /** Each batch entry as "outcome" or "outcome/reason". */
    static List<String> outcomes(JsonArray entries) {
        List<String> out = new ArrayList<>();
        for (JsonElement element : entries) {
            JsonObject entry = element.getAsJsonObject();
            out.add(entry.get("outcome").getAsString()
                    + (entry.has("reason") ? "/" + entry.get("reason").getAsString() : ""));
        }
        return out;
    }

    @Test
    void batchChecksEveryCasBeforeAnyWrite() {
        WriterFixture f = batchFixture();
        JsonObject ok = batch(f, entry("LOCAL_ID", "true", "false"), entry("OTHER_ID", "true", "true"));
        JsonArray writes = ok.getAsJsonArray("writes");
        assertEquals(List.of("applied", "already_matched"), outcomes(writes));
        assertTrue(
                ok.get("outcome").getAsString().equals("applied")
                        && ok.get("written").getAsInt() == 1
                        && f.calls == 1
                        && !writes.get(0).getAsJsonObject().get("value").getAsBoolean(),
                "batch applies changed entries in order and reports already-matched ones without dispatch");
        assertTrue(
                writes.get(0).getAsJsonObject().get("display").getAsString().equals("native display"),
                "each batch entry carries the post-write native display string");
        WriterFixture stale = batchFixture();
        RpcException error = assertThrows(RpcException.class, () -> {
            batch(
                    stale,
                    entry("LOCAL_ID", "true", "false"),
                    entry("OTHER_ID", "false", "true"),
                    entry("THIRD_ID", "false", "true"));
        });
        assertTrue(
                error.reason.wire().equals("stale_context")
                        && !error.unknown
                        && stale.calls == 0
                        && !stale.op.dispatched()
                        && error.getMessage().contains("#1")
                        && error.getMessage().contains("#2")
                        && !error.getMessage().contains("#0"),
                "one stale expectedValue rejects the whole batch before any native write, naming each failing entry");
        WriterFixture missing = batchFixture();
        error = null;
        try {
            batch(missing, entry("LOCAL_ID", "true", "false"), entry("NOPE", "true", "false"));
        } catch (RpcException caught) {
            error = caught;
        }
        assertTrue(!error.unknown && missing.calls == 0, "unknown parameter id fails the batch closed");
    }

    @Test
    void batchFailureAfterOwnDispatchIsUnknownWhateverTheCode() {
        WriterFixture f = batchFixture();
        int[] seen = {0};
        f.afterMutation = () -> {
            if (++seen[0] == 2) throw Errors.notApplied(Reason.STALE_CONTEXT, "children rebuilt after the native set");
        };
        JsonObject result = batch(
                f,
                entry("LOCAL_ID", "true", "false"),
                entry("OTHER_ID", "true", "false"),
                entry("THIRD_ID", "true", "false"));
        JsonArray writes = result.getAsJsonArray("writes");
        assertEquals(List.of("applied", "unknown/stale_context", "not_applied/not_reached"), outcomes(writes));
        assertTrue(
                f.calls == 2 && result.get("outcome").getAsString().equals("unknown"),
                "a not_applied-coded error thrown after the entry's native set is unknown, not failed");
    }

    @Test
    void malformedNativeValuesFailClosed() {
        Fixture missing = new Fixture();
        missing.catalog = null;
        assertRpcError("native_unavailable", () -> missing.reader.catalog("", 0, 64, () -> {}));
        missing.catalog = List.of(new Node());
        assertRpcError("native_unavailable", () -> missing.reader.catalog("", 0, 64, () -> {}));
        for (int bad = 0; bad < 7; bad++) {
            Fixture f = new Fixture();
            Node child;
            if (bad == 0) child = f.parameter(BOOLEAN, f.first).put(BOOLEAN_VALUE, "yes");
            else if (bad == 1) {
                child = f.parameter(INTEGER, f.first).put(INTEGER_VALUE, "7");
            } else {
                child = f.parameter(ENUM, f.first).put(ENUM_VALUE, 1);
                Object options = bad == 2
                        ? "not a list"
                        : bad == 3 ? List.of(new Node()) : List.of(option(1, "One"), option(1, "Again"));
                child.put(ENUM_DEFINITION, new Node().put(OPTIONS, options));
                if (bad == 4) child.put(OWNER_MODULE, f.second);
                if (bad == 5) f.first.put(CHILDREN, (Supplier<Object>) () -> "not a stream");
            }
            if (bad != 5) f.first.put(CHILDREN, List.of(child));
            assertRpcError(bad == 4 ? "stale_context" : "native_unavailable", () -> f.read(0, 64, true));
        }
        assertRpcError("native_unavailable", () -> GridSnapshotReader.effectiveSteps(new int[1], 1));
        assertRpcError("native_unavailable", () -> GridSnapshotReader.effectiveSteps(new float[1], -1));
    }

    @Test
    void bindingsRequireCurrentIdentity() {
        Fixture f = new Fixture();
        Node floating = f.parameter(FLOAT, f.first);
        f.first.put(CHILDREN, List.of(floating));
        GridSnapshotReader.NativeTarget target = f.reader.target(f.proxy, TRACK_ID);
        assertRpcError("stale_context", () -> f.reader.guardTarget(target, f.proxy, new UUID(9, 9)));
        GridSnapshotReader.ParameterBinding scalar =
                f.reader.bindParameter(f.proxy, TRACK_ID, new UUID(1, 1), "LOCAL_ID", () -> {});
        assertRpcError("native_unavailable", () -> f.reader.bindBipolarParameter(scalar));
        assertRpcError("stale_context", () -> f.reader.detachedParameter(floating, () -> {}, new ArrayList<>()));
        f.first.put(INSTANCE_ID, "not a uuid");
        assertRpcError("native_unavailable", () -> f.reader.moduleIdNamed(target, "Effective module", () -> {}));
        f.parameters = false;
        assertRpcError(
                "native_unavailable",
                () -> f.reader.bindParameter(f.proxy, TRACK_ID, new UUID(1, 1), "LOCAL_ID", () -> {}));
        PanelFixture panels = new PanelFixture();
        GridSnapshotReader.ParameterBinding panel =
                panels.model.reader.bindParameter(panels.model.proxy, TRACK_ID, new UUID(1, 1), "PANEL_ID", () -> {});
        GridSnapshotReader.ParameterBinding coupled = panels.model.reader.bindBipolarParameter(panel);
        Node relinked = panels.model
                .parameter(FLOAT, panels.model.first)
                .put(PARAMETER_ID, "OTHER_VALUE")
                .put(BIPOLAR_PANEL, panels.panel);
        relinked.kinds.add(BIPOLAR_FLOAT);
        panels.coupled.put(BIPOLAR_PANEL, null);
        panels.model.first.put(CHILDREN, List.of(panels.panel, panels.coupled, relinked));
        assertRpcError("stale_context", () -> panels.model.reader.guardBipolarParameter(panel, coupled));
    }

    @Test
    void missingPanelAndStringBindingsRefuseBatchesBeforeDispatch() {
        PanelFixture panels = new PanelFixture();
        GridParameterWriter none = new GridParameterWriter(
                panels.model.reader,
                panels.context.reader,
                new GridParameterWriter.Access() {
                    @Override
                    public void requireSupported(Object parameter, GridSnapshotReader.Kind kind) {
                        throw new AssertionError("a panel never reaches scalar authorization");
                    }

                    @Override
                    public void setRaw(Object parameter, double value) {
                        throw new AssertionError("a panel never reaches the scalar setter");
                    }
                },
                null,
                null);
        GridParameterWriter.Entry panel =
                new GridParameterWriter.Entry(new UUID(1, 1), "PANEL_ID", request("boolean", "true", "false"));
        assertRpcError(
                "native_unavailable",
                () -> none.set(
                        panels.model.proxy, TRACK_ID, new UUID(1, 1), "PANEL_ID", panel.request, () -> {}, () -> {}));
        assertRpcError(
                "native_unavailable",
                () -> none.setAll(panels.model.proxy, TRACK_ID, List.of(panel), () -> {}, () -> {}));
        StringFixture strings = new StringFixture();
        GridParameterWriter bare =
                new GridParameterWriter(strings.model.reader, strings.context.reader, null, null, null);
        GridParameterWriter.Entry text =
                new GridParameterWriter.Entry(new UUID(1, 1), "TEXT_ID", stringRequest("before", "x"));
        assertRpcError(
                "native_unavailable",
                () -> bare.setAll(strings.model.proxy, TRACK_ID, List.of(text), () -> {}, () -> {}));
        assertTrue(panels.calls == 0 && strings.calls == 0, "nothing is dispatched without a write binding");
    }

    @Test
    void batchEntryRefusedBeforeItsDispatchIsNotApplied() {
        WriterFixture f = batchFixture();
        Node other = (Node) ((List<?>) f.model.first.values.get(CHILDREN)).get(1);
        f.afterMutation = () -> other.put(BOOLEAN_VALUE, false);
        JsonObject result = batch(
                f,
                entry("LOCAL_ID", "true", "false"),
                entry("OTHER_ID", "true", "false"),
                entry("THIRD_ID", "true", "false"));
        assertEquals(
                List.of("applied", "not_applied/stale_context", "not_applied/not_reached"),
                outcomes(result.getAsJsonArray("writes")),
                "a stale entry after a written one is refused, later entries are not reached");
        assertTrue(
                result.get("outcome").getAsString().equals("not_applied")
                        && result.get("written").getAsInt() == 1
                        && f.calls == 1,
                "the batch outcome is the worst entry and nothing is retried");
    }

    @Test
    void batchPreflightsAndReportsBooleanPanels() {
        PanelFixture f = new PanelFixture();
        GridParameterWriter.Entry panel =
                new GridParameterWriter.Entry(new UUID(1, 1), "PANEL_ID", request("boolean", "true", "false"));
        JsonObject result =
                f.writer.setAll(f.model.proxy, TRACK_ID, List.of(panel), () -> {}, () -> f.op.markDispatched());
        JsonObject entry = result.getAsJsonArray("writes").get(0).getAsJsonObject();
        assertTrue(
                f.calls == 1
                        && entry.getAsJsonObject("coupledParameter")
                                        .get("value")
                                        .getAsDouble()
                                == 0.0
                        && entry.getAsJsonObject("previousCoupledParameter")
                                        .get("value")
                                        .getAsDouble()
                                == -0.5,
                "batch entry keeps the coupled-parameter observations of a single panel write");
        PanelFixture broken = new PanelFixture();
        broken.model.first.put(CHILDREN, List.of(broken.panel));
        RpcException error = assertThrows(RpcException.class, () -> {
            broken.writer.setAll(
                    broken.model.proxy, TRACK_ID, List.of(panel), () -> {}, () -> broken.op.markDispatched());
        });
        assertTrue(
                !error.unknown && broken.calls == 0 && !broken.op.dispatched(),
                "an unresolvable panel coupling fails the batch before any write");
    }

    @Test
    void batchStopsAtFirstFailureAndNeverRetries() {
        WriterFixture f = batchFixture();
        int[] seen = {0};
        f.afterMutation = () -> {
            if (++seen[0] == 1) f.apply = false;
        };
        JsonObject result = batch(
                f,
                entry("LOCAL_ID", "true", "false"),
                entry("OTHER_ID", "true", "false"),
                entry("THIRD_ID", "true", "false"));
        JsonArray writes = result.getAsJsonArray("writes");
        assertTrue(f.calls == 2, "failed readback stops dispatch");
        assertEquals(
                List.of("applied", "unknown/readback_mismatch", "not_applied/not_reached"),
                outcomes(writes),
                "unknown entry is reported, later entries are not reached");
        assertEquals("unknown", result.get("outcome").getAsString(), "the batch outcome is the worst entry");
        WriterFixture first = batchFixture();
        first.apply = false;
        JsonObject head = batch(first, entry("LOCAL_ID", "true", "false"), entry("OTHER_ID", "true", "false"));
        assertEquals(
                List.of("unknown/readback_mismatch", "not_applied/not_reached"),
                outcomes(head.getAsJsonArray("writes")));
        assertTrue(
                first.calls == 1
                        && head.get("outcome").getAsString().equals("unknown")
                        && head.get("written").getAsInt() == 0,
                "an unknown first entry is reported, not thrown away, and nothing after it is dispatched or retried");
    }

    @Test
    void expectedValuesMayComeFromARecentGridGet() {
        WriterFixture f = new WriterFixture();
        JsonObject row = f.model
                .read(0, 64, true)
                .getAsJsonArray("modules")
                .get(0)
                .getAsJsonObject()
                .getAsJsonArray("parameters")
                .get(0)
                .getAsJsonObject();
        GridParameterWriter.Request fromGet =
                request(row.get("kind").getAsString(), row.get("value").toString(), "false");
        f.set(fromGet);
        assertTrue(
                f.calls == 1 && !((Boolean) f.child.values.get(BOOLEAN_VALUE)),
                "grid.get kind/value is accepted as the CAS expectation");
        assertRpcError("stale_context", () -> f.set(fromGet));
        assertTrue(f.calls == 1, "a grid.get snapshot gone stale is rejected not_applied without a write");
    }

    @Test
    void rawParameterNoopAndNativeReadback() {
        WriterFixture f = new WriterFixture();
        JsonObject noop = f.set(request("boolean", "true", "true"));
        assertTrue(
                f.calls == 0
                        && !f.op.dispatched()
                        && !noop.get("nativeMutation").getAsBoolean()
                        && !noop.get("nativeUndo").getAsBoolean(),
                "same-value CAS performs no native operation");
        f.scalar(FLOAT, -0.0, -1.0, 1.0);
        f.set(request("float", "0.0", "0.0"));
        assertTrue(f.calls == 0, "signed zero equality does not manufacture a native write");
        JsonObject result = f.set(request("float", "0", "0.75"));
        assertTrue(
                f.calls == 1
                        && f.op.dispatched()
                        && result.get("mutationDispatched").getAsBoolean(),
                "distinct raw value invokes exactly once");
        assertTrue(
                result.get("previousValue").getAsDouble() == 0.0
                        && result.get("requestedValue").getAsDouble() == 0.75
                        && result.getAsJsonObject("parameter").get("value").getAsDouble() == 0.75,
                "result distinguishes prior/requested/current raw observations");
        WriterFixture mismatched = new WriterFixture();
        mismatched.apply = false;
        RpcException error = mismatched.failure(request("boolean", "true", "false"));
        assertTrue(
                error.reason.wire().equals("readback_mismatch") && error.unknown && mismatched.calls == 1,
                "void setter with mismatched readback remains unknown without retry");
    }

    @Test
    void rawParameterLiveGuardChanges() {
        for (Enum<?> field : List.of(
                GridParameterContext.Field.LICENSED,
                GridParameterContext.Field.SETTABLE,
                NativeReadContext.Field.INITIALIZED)) {
            WriterFixture f = new WriterFixture();
            f.context.values.put(field, false);
            assertRpcError("unsafe_state", () -> f.set(request("boolean", "true", "true")));
            assertTrue(f.calls == 0, "false native prerequisite blocks even a no-op");
            f.context.values.put(field, null);
            assertRpcError("native_unavailable", () -> f.set(request("boolean", "true", "false")));
        }
        for (GridParameterContext.Field field : List.of(
                GridParameterContext.Field.CIU,
                GridParameterContext.Field.TOUCHED,
                GridParameterContext.Field.UNDO_EXECUTING,
                GridParameterContext.Field.UNDO_ACTIVE,
                GridParameterContext.Field.UNDO_ICL)) {
            WriterFixture f = new WriterFixture();
            int[] reads = {0};
            f.model.afterValue = () -> {
                if (++reads[0] == 2) f.context.values.put(field, true);
            };
            assertRpcError("unsafe_state", () -> f.set(request("boolean", "true", "false")));
            assertTrue(
                    f.calls == 0 && !f.op.dispatched(),
                    "live predicate transition before invocation prevents dispatch");
        }
        for (Enum<?> field : List.of(
                GridParameterContext.Field.UNDO,
                GridParameterContext.Field.LOCK,
                GridParameterContext.Field.LOCK_OWNER,
                NativeReadContext.Field.CURRENT_DT,
                NativeReadContext.Field.DEFAULT_DT)) {
            WriterFixture f = new WriterFixture();
            f.context.values.put(field, null);
            assertRpcError("native_unavailable", () -> f.set(request("boolean", "true", "false")));
            assertTrue(f.calls == 0, "missing native context object is unavailable, never false permission");
        }
        WriterFixture owner = new WriterFixture();
        owner.context.values.put(GridParameterContext.Field.LOCK_OWNER, new Object());
        assertRpcError("unsafe_state", () -> owner.set(request("boolean", "true", "false")));
        WriterFixture root = new WriterFixture();
        root.context.values.put(NativeReadContext.Field.ROOT, new Object());
        assertRpcError("stale_context", () -> root.set(request("boolean", "true", "false")));
        WriterFixture moved = new WriterFixture();
        moved.model.afterValue = () -> moved.model.first.put(CHILDREN, List.of());
        assertRpcError("stale_context", () -> moved.set(request("boolean", "true", "false")));
        WriterFixture changedValue = new WriterFixture();
        changedValue.model.afterValue = () -> changedValue.child.put(BOOLEAN_VALUE, false);
        assertRpcError("stale_context", () -> changedValue.set(request("boolean", "true", "false")));
        WriterFixture rangeChanged = new WriterFixture();
        rangeChanged.scalar(FLOAT, 0.5, -1.0, 1.0);
        Node definition = (Node) rangeChanged.child.values.get(FLOAT_DEFINITION);
        int[] reads = {0};
        rangeChanged.model.afterValue = () -> {
            if (++reads[0] == 2) definition.put(FLOAT_MIN, 0.0);
        };
        assertRpcError("invalid_params", () -> rangeChanged.set(request("float", "0.5", "-0.5")));
        assertTrue(
                rangeChanged.calls == 0 && !rangeChanged.op.dispatched(),
                "in-place live range contraction rejects a formerly valid negative value before dispatch");
        WriterFixture unknown = new WriterFixture();
        unknown.supported = false;
        assertRpcError("native_unavailable", () -> unknown.set(request("boolean", "true", "false")));
        assertTrue(
                inspect(unknown.model, unknown.context, "LOCAL_ID")
                        .getAsJsonObject("parameter")
                        .get("value")
                        .getAsBoolean(),
                "missing write eligibility leaves independent typed inspection usable");
    }

    @Test
    void rawParameterMutationFailuresRemainUnknown() {
        WriterFixture partial = new WriterFixture();
        partial.afterMutation = () -> {
            throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "native failure after partial assignment");
        };
        RpcException error = partial.failure(request("boolean", "true", "false"));
        assertTrue(
                error.unknown && partial.calls == 1 && !(Boolean) partial.child.values.get(BOOLEAN_VALUE),
                "partial mutation exception never fabricates rollback or not-applied");
        WriterFixture target = new WriterFixture();
        target.afterMutation = () -> target.model.proxy.put(TARGET, new Node(DEVICE));
        error = target.failure(request("boolean", "true", "false"));
        assertTrue(
                error.reason.wire().equals("stale_context") && error.unknown && target.calls == 1,
                "post-dispatch target failure remains unknown without retry");
        WriterFixture closed = new WriterFixture();
        closed.afterMutation = () -> closed.cancelled = true;
        error = closed.failure(request("boolean", "true", "false"));
        assertTrue(
                error.reason.wire().equals("cancelled") && error.unknown,
                "late cancellation does not imply native cancellation");
        WriterFixture early = new WriterFixture();
        early.cancelled = true;
        error = early.failure(request("boolean", "true", "false"));
        assertTrue(!error.unknown && early.calls == 0, "before-dispatch cancellation prevents mutation");
    }

    private static Node stringPanel(Fixture f, String id, Object value) {
        Node panel = new Node(PANEL_STRING)
                .put(OWNER_MODULE, f.first)
                .put(PATH, "panel/text")
                .put(PARAMETER_ID, id)
                .put(STRING_VALUE, value);
        panel.forbidden.addAll(List.of(
                PARAMETER_NAME,
                SHORT_NAME,
                DISPLAY,
                FLOAT_VALUE,
                FLOAT_DEFINITION,
                BOOLEAN_VALUE,
                INTEGER_VALUE,
                INTEGER_DEFINITION,
                ENUM_VALUE,
                ENUM_DEFINITION,
                BIPOLAR_PANEL));
        return panel;
    }

    private static ContextFixture stringContext(Node panel) {
        ContextFixture context = new ContextFixture();
        context.values.put(GridParameterContext.Field.GUI_PANEL_STRING, (Supplier<Object>) Object::new);
        Supplier<Object> forbidden = () -> {
            throw new AssertionError("String panel used scalar permission or GUI property");
        };
        for (GridParameterContext.Field field : List.of(
                GridParameterContext.Field.LICENSED,
                GridParameterContext.Field.TOUCHED,
                GridParameterContext.Field.GUI_BOOLEAN,
                GridParameterContext.Field.GUI_FLOAT,
                GridParameterContext.Field.GUI_INTEGER,
                GridParameterContext.Field.GUI_ENUM)) context.override(panel, field, forbidden);
        return context;
    }

    @Test
    void stringPanelReadsPreserveTextAndFamilyBoundaries() {
        Fixture f = new Fixture();
        Node panel = stringPanel(f, "TEXT_ID", "");
        Node scalar = f.parameter(BOOLEAN, f.first).put(BOOLEAN_VALUE, true).put(PARAMETER_ID, "SWITCH_ID");
        Node booleanPanel = new Node(PANEL_BOOLEAN)
                .put(OWNER_MODULE, f.first)
                .put(PATH, "panel/switch")
                .put(PARAMETER_ID, "MODE_ID")
                .put(BOOLEAN_VALUE, false);
        f.first.put(CHILDREN, List.of(scalar, panel, booleanPanel));
        ContextFixture context = stringContext(panel);
        context.values.put(GridParameterContext.Field.SETTABLE, false);
        context.values.put(NativeReadContext.Field.INITIALIZED, false);
        for (String text : List.of("", " \t\n ", "Café e\u0301 \uD834\uDD1E \u0000 end")) {
            panel.put(STRING_VALUE, text);
            JsonObject result = inspect(f, context, "TEXT_ID");
            JsonObject value = result.getAsJsonObject("parameter");
            assertTrue(
                    value.get("supported").getAsBoolean()
                            && value.get("kind").getAsString().equals("string")
                            && value.get("controlFamily").getAsString().equals("string_panel"),
                    "non-scalar panel is a readable string family");
            assertTrue(
                    value.get("value").getAsString().equals(text)
                            && value.get("parameterId").getAsString().equals("TEXT_ID")
                            && value.get("path").getAsString().equals("panel/text"),
                    "string contents and identity survive without trimming or normalization");
            assertTrue(
                    value.get("name").isJsonNull()
                            && value.get("shortName").isJsonNull()
                            && value.get("display").isJsonNull()
                            && has(result, "string_panel_labels_unmapped")
                            && has(result, "string_panel_display_unmapped"),
                    "unmapped metadata stays null with explicit reasons");
            assertTrue(
                    !value.has("minimum")
                            && !value.has("maximum")
                            && !value.has("options")
                            && !value.has("numericUnit"),
                    "string controls do not invent scalar ranges, options or units");
            JsonObject edit = result.getAsJsonObject("editContext");
            assertTrue(
                    !edit.getAsJsonObject("guiProperty").get("isSettable").getAsBoolean()
                            && !edit.getAsJsonObject("attachedContext")
                                    .get("initialized")
                                    .getAsBoolean(),
                    "non-writable context and property flags remain read observations, not permission prerequisites");
            assertTrue(
                    !edit.get("scalarLicenseTouchApplicable").getAsBoolean()
                            && edit.get("effectivelyLicensed").isJsonNull()
                            && edit.get("parameterTouched").isJsonNull(),
                    "scalar licensing and touch do not apply to string panels");
            assertTrue(
                    !result.has("coupledParameter") && !result.has("coupledEditContext"),
                    "string inspection needs no boolean-panel consumer and fabricates no dependent coverage");
            JsonArray graph = f.read(0, 64, true)
                    .getAsJsonArray("modules")
                    .get(0)
                    .getAsJsonObject()
                    .getAsJsonArray("parameters");
            assertTrue(
                    graph.size() == 3
                            && graph.get(1).equals(value)
                            && graph.get(0).getAsJsonObject().get("value").getAsBoolean()
                            && !graph.get(2).getAsJsonObject().get("value").getAsBoolean(),
                    "mixed scalar/boolean-panel/string graph retains each independent typed value");
        }
        context.values.put(GridParameterContext.Field.GUI_PANEL_STRING, null);
        assertRpcError("native_unavailable", () -> inspect(f, context, "TEXT_ID"));
        assertTrue(
                f.read(0, 64, true).getAsJsonArray("modules").size() == 2,
                "missing string property inspection does not disable graph reads");
    }

    @Test
    void stringPanelBoundsAndSelectedControlIsolation() {
        Fixture f = new Fixture();
        String supplementary = new String(Character.toChars(0x1D11E));
        Node panel = stringPanel(f, "TEXT_ID", supplementary.repeat(512));
        Node scalar = f.parameter(BOOLEAN, f.first).put(BOOLEAN_VALUE, true);
        f.first.put(CHILDREN, List.of(panel, scalar));
        ContextFixture context = stringContext(panel);
        assertTrue(
                inspect(f, context, "TEXT_ID")
                        .getAsJsonObject("parameter")
                        .get("value")
                        .getAsString()
                        .equals(supplementary.repeat(512)),
                "exactly 1024 UTF-16 units remain readable, including supplementary characters");
        panel.put(STRING_VALUE, supplementary.repeat(512) + "x");
        assertRpcError("out_of_bounds", () -> inspect(f, context, "TEXT_ID"));
        assertRpcError("out_of_bounds", () -> f.read(0, 64, true));
        panel.forbidden.add(STRING_VALUE);
        assertTrue(
                f.read(0, 64, false).getAsJsonArray("modules").size() == 2,
                "structure-only snapshots never read oversized string values");
        assertTrue(
                inspect(f, context, "LOCAL_ID")
                        .getAsJsonObject("parameter")
                        .get("value")
                        .getAsBoolean(),
                "selected scalar inspection never reads an unrelated oversized string");
        panel.forbidden.remove(STRING_VALUE);
        for (Object invalid : Arrays.asList(null, Boolean.TRUE, 42)) {
            panel.put(STRING_VALUE, invalid);
            assertRpcError("native_unavailable", () -> inspect(f, context, "TEXT_ID"));
            assertRpcError("native_unavailable", () -> f.read(0, 64, true));
        }
    }

    @Test
    void stringPanelIdentityAndLateGuards() {
        Fixture f = new Fixture();
        Node panel = stringPanel(f, "", "empty identifier");
        f.first.put(CHILDREN, List.of(panel));
        ContextFixture context = stringContext(panel);
        assertTrue(
                inspect(f, context, "")
                        .getAsJsonObject("parameter")
                        .get("value")
                        .getAsString()
                        .equals("empty identifier"),
                "unique empty native string identifier remains addressable");
        Node collision = f.parameter(BOOLEAN, f.first).put(PARAMETER_ID, "").put(BOOLEAN_VALUE, false);
        f.first.put(CHILDREN, List.of(panel, collision));
        assertRpcError("stale_context", () -> inspect(f, context, ""));
        collision.kinds.clear();
        collision.kinds.add(PANEL_BOOLEAN);
        assertRpcError("stale_context", () -> inspect(f, context, ""));
        f.first.put(CHILDREN, List.of(panel));
        panel.put(OWNER_MODULE, f.second);
        assertRpcError("stale_context", () -> inspect(f, context, ""));
        panel.put(OWNER_MODULE, f.first);
        panel.threadAllowed = false;
        assertRpcError("native_unavailable", () -> inspect(f, context, ""));
        for (int change = 0; change < 5; change++) {
            Fixture moved = new Fixture();
            Node text = stringPanel(moved, "TEXT_ID", "before");
            moved.first.put(CHILDREN, List.of(text));
            ContextFixture c = stringContext(text);
            final int mode = change;
            moved.afterValue = () -> {
                if (mode == 0) moved.first.put(CHILDREN, List.of());
                if (mode == 1) moved.proxy.put(TARGET, new Node(DEVICE));
                if (mode == 2) moved.device.put(OWNER_TRACK, new Node(TRACK).put(TRACK_UUID, TRACK_ID));
                if (mode == 3) c.values.put(NativeReadContext.Field.CONTEXT, new Object());
                if (mode == 4) text.put(OWNER_MODULE, moved.second);
            };
            assertRpcError("stale_context", () -> inspect(moved, c, "TEXT_ID"));
        }
    }

    @Test
    void stringPanelsRejectScalarWritesBeforeAuthorization() {
        Fixture f = new Fixture();
        Node panel = stringPanel(f, "TEXT_ID", "read only");
        panel.forbidden.add(STRING_VALUE);
        f.first.put(CHILDREN, List.of(panel));
        ContextFixture context = stringContext(panel);
        context.override(panel, NativeReadContext.Field.CONTEXT, (Supplier<Object>) () -> {
            throw new AssertionError("String write captured edit context");
        });
        GridParameterWriter writer = new GridParameterWriter(
                f.reader,
                context.reader,
                new GridParameterWriter.Access() {
                    @Override
                    public void requireSupported(Object parameter, GridSnapshotReader.Kind kind) {
                        throw new AssertionError("String write reached scalar authorization");
                    }

                    @Override
                    public void setRaw(Object parameter, double value) {
                        throw new AssertionError("String write reached scalar mutation");
                    }
                },
                null,
                null);
        for (GridParameterWriter.Request request :
                List.of(request("boolean", "true", "false"), request("boolean", "true", "true"))) {
            {
                RpcException error = assertThrows(RpcException.class, () -> {
                    writer.set(f.proxy, TRACK_ID, new UUID(1, 1), "TEXT_ID", request, () -> {}, () -> {
                        throw new AssertionError("String write dispatched");
                    });
                });
                assertTrue(
                        error.reason.wire().equals("native_unavailable") && !error.unknown,
                        "string panel rejects scalar writes and apparent no-ops before authorization");
            }
        }
    }

    private static final class StringFixture implements GridStringPanelWriter.Access {
        final Fixture model = new Fixture();
        final ContextFixture context = new ContextFixture();
        final HostOperation op = new HostOperation(Long.MAX_VALUE);
        final Node panel = stringPanel(model, "TEXT_ID", "before");
        final GridParameterWriter writer;
        int calls;
        boolean apply = true;
        String last;
        Runnable afterMutation = () -> {};

        StringFixture() {
            model.first.put(CHILDREN, List.of(panel));
            context.values.put(GridParameterContext.Field.GUI_PANEL_STRING, (Supplier<Object>) Object::new);
            context.values.put(GridParameterContext.Field.LOCK_OWNER, context.dt);
            Supplier<Object> forbidden = () -> {
                throw new AssertionError("String panel used scalar permission or GUI property");
            };
            for (GridParameterContext.Field field : List.of(
                    GridParameterContext.Field.LICENSED,
                    GridParameterContext.Field.TOUCHED,
                    GridParameterContext.Field.GUI_BOOLEAN,
                    GridParameterContext.Field.GUI_FLOAT,
                    GridParameterContext.Field.GUI_INTEGER,
                    GridParameterContext.Field.GUI_ENUM)) context.override(panel, field, forbidden);
            writer = new GridParameterWriter(
                    model.reader,
                    context.reader,
                    new GridParameterWriter.Access() {
                        @Override
                        public void requireSupported(Object parameter, GridSnapshotReader.Kind kind) {
                            throw new AssertionError("String panel must not enter scalar authorization");
                        }

                        @Override
                        public void setRaw(Object parameter, double value) {
                            throw new AssertionError("String panel must not invoke a numeric controller operation");
                        }
                    },
                    null,
                    new GridStringPanelWriter(model.reader, context.reader, this));
        }

        JsonObject set(GridParameterWriter.Request request) {
            return writer.set(
                    model.proxy, TRACK_ID, new UUID(1, 1), "TEXT_ID", request, () -> {}, () -> op.markDispatched());
        }

        RpcException failure(GridParameterWriter.Request request) {
            {
                RpcException error = assertThrows(RpcException.class, () -> set(request));
                return error;
            }
        }

        @Override
        public void requireSupported(Object parameter) {
            if (parameter != panel) throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "unmapped panel");
        }

        @Override
        public void set(Object parameter, String value) {
            calls++;
            last = value;
            if (apply) panel.put(STRING_VALUE, value);
            afterMutation.run();
        }
    }

    private static GridParameterWriter.Request stringRequest(String expected, String value) {
        return GridParameterWriter.Request.parse(HostFixture.json("{\"expectedKind\":\"string\",\"expectedValue\":"
                + new JsonPrimitive(expected) + ",\"value\":" + new JsonPrimitive(value) + "}"));
    }

    @Test
    void stringPanelWritesAreGuardedCompareAndSet() {
        StringFixture f = new StringFixture();
        String text = "Line 1\n\uD83D\uDE00 \"quoted\"  ";
        JsonObject result = f.set(stringRequest("before", text));
        assertTrue(
                f.calls == 1
                        && text.equals(f.last)
                        && f.op.dispatched()
                        && text.equals(
                                result.getAsJsonObject("parameter").get("value").getAsString())
                        && result.get("previousValue").getAsString().equals("before")
                        && result.get("requestedValue").getAsString().equals(text)
                        && result.get("mutationDispatched").getAsBoolean()
                        && result.get("verification").getAsString().equals("exact_native_string_panel_readback")
                        && !result.get("undoGroupingVerified").getAsBoolean()
                        && !result.get("engineAcknowledgement").getAsBoolean(),
                "string panel write performs one native operation, preserves text verbatim and reads it back");
        StringFixture noop = new StringFixture();
        result = noop.set(stringRequest("before", "before"));
        assertTrue(
                noop.calls == 0
                        && !noop.op.dispatched()
                        && !result.get("mutationDispatched").getAsBoolean()
                        && result.get("outcome").getAsString().equals("already_matched"),
                "matching string write is a verified no-op without native dispatch");
        StringFixture empty = new StringFixture();
        empty.set(stringRequest("before", ""));
        assertTrue(empty.calls == 1 && "".equals(empty.panel.values.get(STRING_VALUE)), "empty text is a valid write");
        StringFixture stale = new StringFixture();
        RpcException error = stale.failure(stringRequest("other", "x"));
        assertTrue(
                error.reason.wire().equals("stale_context")
                        && !error.unknown
                        && stale.calls == 0
                        && !stale.op.dispatched(),
                "stale string expectation is rejected before dispatch, even for a no-op");
        error = stale.failure(request("boolean", "true", "false"));
        assertTrue(
                error.reason.wire().equals("stale_context") && stale.calls == 0,
                "a scalar request cannot target a string panel");
        StringFixture skipped = new StringFixture();
        skipped.context.override(skipped.panel, GridParameterContext.Field.CIU, true);
        error = skipped.failure(stringRequest("before", "x"));
        assertTrue(
                error.reason.wire().equals("unsafe_state") && skipped.calls == 0,
                "panel skip predicate gates string writes");
        StringFixture wrongProject = new StringFixture();
        wrongProject.context.override(wrongProject.panel, NativeReadContext.Field.PROJECT, new Object());
        error = wrongProject.failure(stringRequest("before", "x"));
        assertTrue(
                error.reason.wire().equals("stale_context") && wrongProject.calls == 0,
                "owning project mismatch gates string writes");
        StringFixture uninitialized = new StringFixture();
        uninitialized.context.values.put(NativeReadContext.Field.INITIALIZED, false);
        error = uninitialized.failure(stringRequest("before", "x"));
        assertTrue(
                error.reason.wire().equals("unsafe_state") && uninitialized.calls == 0,
                "uninitialized document context gates string writes");
    }

    @Test
    void stringPanelWriteFailuresAndBatch() {
        StringFixture unread = new StringFixture();
        unread.apply = false;
        RpcException error = unread.failure(stringRequest("before", "x"));
        assertTrue(
                error.reason.wire().equals("readback_mismatch") && error.unknown && unread.calls == 1,
                "unobserved requested text is not a successful string dispatch");
        StringFixture partial = new StringFixture();
        partial.afterMutation = () -> {
            throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "native string failure");
        };
        error = partial.failure(stringRequest("before", "x"));
        assertTrue(
                partial.calls == 1 && partial.op.dispatched(),
                "native exception after dispatch is not retried or rolled back");
        StringFixture absent = new StringFixture();
        GridParameterWriter none =
                new GridParameterWriter(absent.model.reader, absent.context.reader, null, null, null);
        {
            RpcException caught = assertThrows(RpcException.class, () -> {
                none.set(
                        absent.model.proxy,
                        TRACK_ID,
                        new UUID(1, 1),
                        "TEXT_ID",
                        stringRequest("before", "x"),
                        () -> {},
                        () -> {
                            throw new AssertionError("dispatched without a string binding");
                        });
            });
            assertTrue(
                    caught.reason.wire().equals("native_unavailable") && !caught.unknown,
                    "missing string write binding fails closed");
        }
        StringFixture batch = new StringFixture();
        GridParameterWriter.Entry entry =
                new GridParameterWriter.Entry(new UUID(1, 1), "TEXT_ID", stringRequest("before", "batched"));
        JsonObject result = batch.writer.setAll(
                batch.model.proxy, TRACK_ID, List.of(entry), () -> {}, () -> batch.op.markDispatched());
        JsonObject item = result.getAsJsonArray("writes").get(0).getAsJsonObject();
        assertTrue(
                batch.calls == 1
                        && item.get("outcome").getAsString().equals("applied")
                        && item.get("value").getAsString().equals("batched")
                        && item.get("previousValue").getAsString().equals("before"),
                "batch entries may write string panels");
        StringFixture stalePre = new StringFixture();
        GridParameterWriter.Entry bad =
                new GridParameterWriter.Entry(new UUID(1, 1), "TEXT_ID", stringRequest("nope", "x"));
        {
            RpcException caught = assertThrows(RpcException.class, () -> {
                stalePre.writer.setAll(
                        stalePre.model.proxy, TRACK_ID, List.of(bad), () -> {}, () -> stalePre.op.markDispatched());
            });
            assertTrue(!caught.unknown && stalePre.calls == 0, "stale string entry fails the batch preflight");
        }
    }

    @Test
    void stringRequestBounds() {
        String max = "a".repeat(1024);
        stringRequest(max, max);
        for (String bad : List.of("1", "true", "null", "[]")) {
            assertRpcError(
                    "invalid_params",
                    () -> GridParameterWriter.Request.parse(HostFixture.json(
                            "{\"expectedKind\":\"string\",\"expectedValue\":\"a\",\"value\":" + bad + "}")));
            assertRpcError(
                    "invalid_params",
                    () -> GridParameterWriter.Request.parse(HostFixture.json(
                            "{\"expectedKind\":\"string\",\"expectedValue\":" + bad + ",\"value\":\"a\"}")));
        }
        assertRpcError("invalid_params", () -> stringRequest(max + "a", "a"));
        assertRpcError("invalid_params", () -> stringRequest("a", max + "a"));
        assertRpcError("invalid_params", () -> stringRequest("a", "\uD83D\uDE00".repeat(513)));
        assertRpcError(
                "invalid_params",
                () -> GridParameterWriter.Request.parse(HostFixture.json(
                        "{\"expectedKind\":\"string\",\"expectedValue\":\"a\",\"display\":\"1 kHz\"}")));
        assertRpcError("invalid_params", () -> request("float", "0.0", "\"text\""));
    }

    @Test
    void booleanPanelInspectionKeepsScalarApplicabilityDistinct() {
        PanelFixture f = new PanelFixture();
        JsonObject result = f.model.reader.parameter(
                f.model.proxy, TRACK_ID, new UUID(1, 1), "PANEL_ID", f.context.reader, () -> {});
        JsonObject panel = result.getAsJsonObject("parameter"), context = result.getAsJsonObject("editContext");
        assertTrue(
                panel.get("kind").getAsString().equals("boolean")
                        && panel.get("parameterId").getAsString().equals("PANEL_ID"),
                "non-Tty panel has its own identifier and public boolean value kind");
        assertTrue(
                panel.get("name").isJsonNull()
                        && panel.get("display").isJsonNull()
                        && !result.get("complete").getAsBoolean(),
                "unmapped panel labels/display remain explicit rather than scalar-method calls or invented text");
        assertTrue(
                context.get("effectivelyLicensed").isJsonNull()
                        && context.get("parameterTouched").isJsonNull()
                        && !context.get("scalarLicenseTouchApplicable").getAsBoolean(),
                "panel never fabricates scalar permission or touch observations");
        assertTrue(
                result.getAsJsonObject("coupledParameter")
                                .get("parameterId")
                                .getAsString()
                                .equals("VALUE_ID")
                        && result.getAsJsonObject("coupledEditContext")
                                .get("effectivelyLicensed")
                                .getAsBoolean(),
                "inspection identifies actual same-module reference and separate scalar authorization observations");
        Node collision = f.model
                .parameter(BOOLEAN, f.model.first)
                .put(PARAMETER_ID, "PANEL_ID")
                .put(BOOLEAN_VALUE, false);
        f.model.first.put(CHILDREN, List.of(f.panel, f.coupled, collision));
        assertRpcError("stale_context", () -> f.set(request("boolean", "true", "false")));
        assertTrue(f.calls == 0, "panel/scalar identifier collision cannot choose the first matching child");
    }

    @Test
    void booleanPanelWritesReportNativeCoupledChanges() {
        PanelFixture f = new PanelFixture();
        JsonObject result = f.set(request("boolean", "true", "false"));
        assertTrue(
                f.calls == 1
                        && !result.getAsJsonObject("parameter").get("value").getAsBoolean(),
                "panel setter performs one boolean native operation");
        assertTrue(
                result.getAsJsonObject("previousCoupledParameter").get("value").getAsDouble() == -0.5
                        && result.getAsJsonObject("coupledParameter")
                                        .get("value")
                                        .getAsDouble()
                                == 0.0
                        && result.getAsJsonObject("previousCoupledParameter")
                                        .get("minimum")
                                        .getAsDouble()
                                == -1.0
                        && result.getAsJsonObject("coupledParameter")
                                        .get("minimum")
                                        .getAsDouble()
                                == 0.0,
                "response reads native coupled coercion/range instead of copying the pre-write scalar");
        assertTrue(
                !result.get("undoGroupingVerified").getAsBoolean()
                        && !result.get("engineAcknowledgement").getAsBoolean(),
                "model changes do not become native Undo grouping or DSP acknowledgement");
        PanelFixture noop = new PanelFixture();
        noop.panel.put(BOOLEAN_VALUE, false);
        noop.coupled.put(FLOAT_VALUE, 0.25);
        noop.definition.put(FLOAT_MIN, 0.0);
        result = noop.set(request("boolean", "false", "false"));
        assertTrue(
                noop.calls == 0
                        && !noop.op.dispatched()
                        && !result.get("mutationDispatched").getAsBoolean()
                        && result.getAsJsonObject("coupledParameter")
                                        .get("value")
                                        .getAsDouble()
                                == 0.25,
                "guarded matching panel preserves its dependent value without invoking the setter");
    }

    @Test
    void booleanPanelGuardsCouplingAndLateFailures() {
        PanelFixture unlicensed = new PanelFixture();
        unlicensed.context.override(unlicensed.coupled, GridParameterContext.Field.LICENSED, false);
        RpcException error = unlicensed.failure();
        assertTrue(
                error.reason.wire().equals("unsafe_state") && !error.unknown && unlicensed.calls == 0,
                "linked scalar effective licensing gates panel writes");
        PanelFixture touched = new PanelFixture();
        touched.context.override(touched.coupled, GridParameterContext.Field.TOUCHED, true);
        error = touched.failure();
        assertTrue(
                error.reason.wire().equals("unsafe_state") && touched.calls == 0,
                "linked scalar active touch blocks mode changes");
        PanelFixture skipped = new PanelFixture();
        skipped.context.override(skipped.panel, GridParameterContext.Field.CIU, true);
        error = skipped.failure();
        assertTrue(
                error.reason.wire().equals("unsafe_state") && skipped.calls == 0,
                "panel's own native skip predicate is retained");
        PanelFixture wrongProject = new PanelFixture();
        wrongProject.context.override(wrongProject.coupled, NativeReadContext.Field.PROJECT, new Object());
        error = wrongProject.failure();
        assertTrue(
                error.reason.wire().equals("stale_context") && wrongProject.calls == 0,
                "coupled parameter cannot cross owning projects");
        PanelFixture unknown = new PanelFixture();
        unknown.coupled.kinds.remove(BIPOLAR_FLOAT);
        error = unknown.failure();
        assertTrue(
                error.reason.wire().equals("native_unavailable") && unknown.calls == 0,
                "generic float is not an inspected bipolar dependency");
        PanelFixture ambiguous = new PanelFixture();
        Node second = ambiguous
                .model
                .parameter(FLOAT, ambiguous.model.first)
                .put(PARAMETER_ID, "SECOND_VALUE")
                .put(BIPOLAR_PANEL, ambiguous.panel);
        second.kinds.add(BIPOLAR_FLOAT);
        ambiguous.model.first.put(CHILDREN, List.of(ambiguous.panel, ambiguous.coupled, second));
        error = ambiguous.failure();
        assertTrue(
                error.reason.wire().equals("native_unavailable") && ambiguous.calls == 0,
                "multiple coupled native parameters fail before dispatch");
        PanelFixture stale = new PanelFixture();
        stale.afterMutation = () -> stale.coupled.put(BIPOLAR_PANEL, new Object());
        error = stale.failure();
        assertTrue(error.unknown && stale.calls == 1, "lost post-dispatch reference remains unknown with no retry");
        PanelFixture unread = new PanelFixture();
        unread.apply = false;
        error = unread.failure();
        assertTrue(
                error.reason.wire().equals("readback_mismatch") && error.unknown && unread.calls == 1,
                "unobserved requested boolean is not successful panel dispatch");
        PanelFixture partial = new PanelFixture();
        partial.afterMutation = () -> {
            throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "native panel failure");
        };
        error = partial.failure();
        assertTrue(
                error.unknown && partial.calls == 1 && !(Boolean) partial.panel.values.get(BOOLEAN_VALUE),
                "native exception after panel/coercion changes cannot fabricate rollback");
    }

    @Test
    void moduleFootprintsAreOptionalReadMetadata() {
        Fixture f = new Fixture();
        JsonObject module =
                f.read(0, 64, false).getAsJsonArray("modules").get(0).getAsJsonObject();
        assertTrue(
                module.get("width").getAsInt() == 2 && module.get("height").getAsInt() == 1,
                "native primitive footprint is discoverable alongside asymmetric anchor coordinates");
        f.unavailable.add(FOOTPRINT);
        JsonObject partial = f.read(0, 64, false);
        module = partial.getAsJsonArray("modules").get(0).getAsJsonObject();
        assertTrue(
                module.get("width").isJsonNull()
                        && module.get("height").isJsonNull()
                        && !partial.get("nativeMutation").getAsBoolean(),
                "missing footprint keeps independent graph read available and explicitly partial");
        f.unavailable.clear();
        f.first.put(WIDTH, 0);
        assertTrue(
                f.read(0, 64, false)
                        .getAsJsonArray("modules")
                        .get(0)
                        .getAsJsonObject()
                        .get("width")
                        .isJsonNull(),
                "invalid native footprint is never fabricated as a valid dimension");
    }

    @Test
    void moduleMovesUseCasAndLaterWholeGraphObservation() {
        ModuleWriterFixture stale = new ModuleWriterFixture();
        HostOperation op = stale.start(new GridModuleWriter.Move(6, 3, 7, 3));
        assertTrue(
                !assertRpcError("stale_context", op).unknown && stale.calls == 0 && !stale.writer.quarantined(),
                "CAS precedes no-op and never quarantines");
        ModuleWriterFixture noop = new ModuleWriterFixture();
        op = noop.start(new GridModuleWriter.Move(7, 3, 7, 3));
        assertTrue(
                !op.result.isDone() && !op.dispatched() && noop.calls == 0, "validated no-op skips native operation");
        noop.next();
        assertTrue(
                !op.result.join().get("mutationDispatched").getAsBoolean(),
                "no-op result retains not-dispatched semantics");
        ModuleWriterFixture moved = new ModuleWriterFixture();
        moved.afterMutation = () -> {
            assertTrue(
                    !moved.current.result.isDone() && moved.events.isEmpty(),
                    "native body/callback cannot complete or schedule before native return");
            moved.model.first.put(X, 12);
            moved.model.second.put(X, 14);
        };
        op = moved.start(new GridModuleWriter.Move(7, 3, 11, 3));
        assertTrue(
                op.dispatched() && moved.calls == 1 && !op.result.isDone(),
                "native return only queues later document observation");
        moved.next();
        JsonObject result = op.result.join();
        assertTrue(
                result.getAsJsonObject("previousPosition").get("x").getAsInt() == 7
                        && result.getAsJsonObject("requestedPosition").get("x").getAsInt() == 11
                        && result.getAsJsonObject("actualPosition").get("x").getAsInt() == 12
                        && result.get("outcome").getAsString().equals("applied"),
                "native reflow is honestly distinct from requested placement");
        JsonArray modules = result.getAsJsonObject("graph").getAsJsonArray("modules");
        assertTrue(
                modules.size() == 2 && modules.get(1).getAsJsonObject().get("x").getAsInt() == 14,
                "later graph includes reflowed unselected modules");
        assertTrue(
                !result.get("engineAcknowledgement").getAsBoolean()
                        && !result.get("undoGroupingVerified").getAsBoolean(),
                "native observation never promises DSP acknowledgement or undo grouping");
    }

    @Test
    void moduleDeletesObserveSelectiveBypassChanges() {
        ModuleWriterFixture f = new ModuleWriterFixture();
        Node third = f.model.module(2, f.model.graph);
        Node secondOutput = f.model.port(OUTPUT, f.model.second, "middle");
        Node thirdInput = f.model.port(INPUT, third, "sink").put(INPUT_SOURCE, secondOutput);
        f.model.second.put(OUTPUTS, List.of(secondOutput));
        third.put(INPUTS, List.of(thirdInput));
        f.model.graph.put(MODULES, List.of(f.model.first, f.model.second, third));
        f.moduleId = new UUID(1, 2);
        f.afterMutation = () -> thirdInput.put(INPUT_SOURCE, f.model.output);
        HostOperation op = f.start(null);
        assertTrue(
                !op.result.isDone() && f.calls == 1 && op.dispatched(),
                "selective delete waits for later graph rather than void return");
        f.next();
        JsonObject result = op.result.join(), graph = result.getAsJsonObject("graph");
        assertTrue(
                result.get("removed").getAsBoolean()
                        && result.get("removedModuleId").getAsString().equals(f.moduleId.toString())
                        && graph.getAsJsonArray("modules").size() == 2,
                "delete removes exact UUID without clearing survivors");
        assertTrue(
                graph.getAsJsonArray("connections").size() == 1
                        && graph.getAsJsonArray("connections")
                                .get(0)
                                .getAsJsonObject()
                                .get("sourceModuleId")
                                .getAsString()
                                .equals(new UUID(1, 1).toString()),
                "post-delete graph exposes native bypass reconnection, not just incident edges");
        HostOperation absent = f.start(null);
        assertTrue(
                !assertRpcError("stale_context", absent).unknown && f.calls == 1,
                "missing UUID is not idempotent deletion success or a retry");
    }

    @Test
    void modulePreflightRejectsUnsafeContextsAndGeometry() {
        for (GridTopologyContext.Field field : List.of(
                GridTopologyContext.Field.CIU,
                GridTopologyContext.Field.PROJECT_ADM,
                GridTopologyContext.Field.PROJECT_OOK,
                GridTopologyContext.Field.UNDO_EXECUTING,
                GridTopologyContext.Field.UNDO_ACTIVE,
                GridTopologyContext.Field.UNDO_ICL)) {
            ModuleWriterFixture f = new ModuleWriterFixture();
            f.context.values.put(field, true);
            HostOperation op = f.start(null);
            assertTrue(
                    !assertRpcError("unsafe_state", op).unknown && f.calls == 0 && !f.writer.quarantined(),
                    "native topology predicate rejects before dispatch: " + field);
        }
        for (NativeReadContext.Field field :
                List.of(NativeReadContext.Field.INITIALIZED, NativeReadContext.Field.CONTEXT_THREAD)) {
            ModuleWriterFixture f = new ModuleWriterFixture();
            f.context.values.put(field, false);
            assertRpcError("unsafe_state", f.start(null));
            assertTrue(f.calls == 0, "uninitialized or wrong context thread prevents mutation");
        }
        for (Enum<?> field : List.of(
                NativeReadContext.Field.CURRENT_DT,
                NativeReadContext.Field.DEFAULT_DT,
                GridTopologyContext.Field.UNDO,
                GridTopologyContext.Field.LOCK,
                GridTopologyContext.Field.LOCK_OWNER)) {
            ModuleWriterFixture f = new ModuleWriterFixture();
            f.context.values.put(field, null);
            assertRpcError("native_unavailable", f.start(null));
            assertTrue(f.calls == 0, "unavailable required native context is not permission: " + field);
        }
        for (NativeReadContext.Field field :
                List.of(NativeReadContext.Field.DELETED, NativeReadContext.Field.DISPOSED)) {
            ModuleWriterFixture f = new ModuleWriterFixture();
            f.context.values.put(field, true);
            assertRpcError("stale_context", f.start(null));
        }
        ModuleWriterFixture root = new ModuleWriterFixture();
        root.context.values.put(NativeReadContext.Field.ROOT, new Object());
        assertRpcError("stale_context", root.start(null));
        ModuleWriterFixture lock = new ModuleWriterFixture();
        lock.context.values.put(GridTopologyContext.Field.LOCK_OWNER, new Object());
        assertRpcError("unsafe_state", lock.start(null));
        ModuleWriterFixture project = new ModuleWriterFixture();
        project.context.values.put(NativeReadContext.Field.PROJECT, new Object());
        assertRpcError("stale_context", project.start(null));
        ModuleWriterFixture wrongTrack = new ModuleWriterFixture();
        wrongTrack.trackId = new UUID(99, 99);
        assertRpcError("stale_context", wrongTrack.start(null));
        ModuleWriterFixture moved = new ModuleWriterFixture();
        moved.model.first.put(MODULE_GRAPH, new Node());
        assertRpcError("stale_context", moved.start(null));
        ModuleWriterFixture unknown = new ModuleWriterFixture();
        unknown.model.graph.put(MODULES, List.of(unknown.model.first, new Node()));
        assertRpcError("native_unavailable", unknown.start(null));
        ModuleWriterFixture many = new ModuleWriterFixture();
        List<Node> modules = new ArrayList<>();
        for (int i = 0; i <= GridModuleWriter.MAX_MODULES; i++) modules.add(many.model.module(i, many.model.graph));
        many.model.graph.put(MODULES, modules);
        assertRpcError("out_of_bounds", many.start(null));
        for (GridSnapshotReader.Field field : List.of(WIDTH, HEIGHT)) {
            for (int invalid : List.of(0, -1)) {
                ModuleWriterFixture f = new ModuleWriterFixture();
                f.model.second.put(field, invalid);
                assertRpcError("out_of_bounds", f.start(null));
                assertTrue(
                        f.calls == 0 && !f.writer.quarantined(),
                        "invalid unselected footprint blocks topology without quarantine");
            }
        }
        for (GridModuleWriter.Move move : List.of(
                new GridModuleWriter.Move(7, 3, Integer.MAX_VALUE, 3),
                new GridModuleWriter.Move(7, 3, 9, Integer.MAX_VALUE),
                new GridModuleWriter.Move(7, 3, Integer.MIN_VALUE, 3),
                new GridModuleWriter.Move(7, 3, 9, 65539))) {
            ModuleWriterFixture f = new ModuleWriterFixture();
            assertRpcError("out_of_bounds", f.start(move));
            assertTrue(
                    f.calls == 0 && !f.writer.quarantined(),
                    "prospective endpoint, translation and full graph y-span checked before native allocation");
        }
        ModuleWriterFixture cumulative = new ModuleWriterFixture();
        cumulative.model.second.put(X, Integer.MAX_VALUE - 2);
        assertRpcError("out_of_bounds", cumulative.start(null));
        ModuleWriterFixture occupied = new ModuleWriterFixture();
        assertRpcError("invalid_params", occupied.start(new GridModuleWriter.Move(7, 3, 8, 3)));
        assertTrue(
                occupied.calls == 0 && !occupied.writer.quarantined(),
                "native exact-origin conflict rejected without invoking/quarantining");
        ModuleWriterFixture overlap = new ModuleWriterFixture();
        overlap.model.second.put(X, 10);
        HostOperation overlapOp = overlap.start(new GridModuleWriter.Move(7, 3, 9, 3));
        overlap.next();
        assertTrue(
                overlapOp.result.join().get("mutationDispatched").getAsBoolean(),
                "ordinary rectangle overlap remains native reflow's decision");
    }

    @Test
    void moduleFailuresQuarantineOnlyNativeInvocation() {
        ModuleWriterFixture unsupported = new ModuleWriterFixture();
        unsupported.moveSupported = false;
        HostOperation unsupportedMove = unsupported.start(new GridModuleWriter.Move(7, 3, 10, 3));
        assertTrue(
                !assertRpcError("native_unavailable", unsupportedMove).unknown
                        && !unsupported.writer.quarantined()
                        && unsupported.calls == 0,
                "move binding/preparation failure is preflight, not quarantine");
        HostOperation independentDelete = unsupported.start(null);
        unsupported.next();
        assertTrue(
                independentDelete.result.join().get("removed").getAsBoolean(),
                "unavailable move binding does not disable selective deletion");
        ModuleWriterFixture deleteFailure = new ModuleWriterFixture();
        deleteFailure.afterMutation = () -> {
            throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "native delete failure");
        };
        HostOperation failedDelete = deleteFailure.start(null);
        assertTrue(
                assertUnknown("native_unavailable", failedDelete) != null && deleteFailure.writer.quarantined(),
                "native selective-delete exception also quarantines topology");
        HostOperation blockedMove = deleteFailure.start(new GridModuleWriter.Move(7, 3, 10, 3));
        assertTrue(
                !assertRpcError("unresolved", blockedMove).unknown && deleteFailure.calls == 1,
                "delete failure prevents future move without retry");
        GridModuleWriter recreated = new GridModuleWriter(
                deleteFailure.model.reader,
                deleteFailure.model,
                deleteFailure.context.reader,
                deleteFailure,
                deleteFailure.quarantine);
        assertRpcError(
                "unresolved",
                () -> recreated.delete(
                        deleteFailure.model.proxy,
                        TRACK_ID,
                        new UUID(1, 2),
                        () -> {},
                        () -> {
                            throw new AssertionError("quarantine cleared on writer recreation");
                        },
                        result -> {
                            throw new AssertionError("quarantine completed");
                        },
                        error -> {
                            throw new AssertionError(error);
                        }));
        assertTrue(
                recreated.quarantined() && !new ModuleWriterFixture().writer.quarantined(),
                "shared native-lifetime quarantine survives writer recreation while independent fake lifetimes remain isolated");
        ModuleWriterFixture f = new ModuleWriterFixture();
        f.afterMutation = () -> {
            throw new AssertionError("native close failure");
        };
        HostOperation op = f.start(new GridModuleWriter.Move(7, 3, 10, 3));
        assertTrue(
                assertUnknown("native_unavailable", op) != null
                        && f.writer.quarantined()
                        && f.calls == 1
                        && f.events.isEmpty(),
                "unexpected native failure quarantines both topology routes without retry or observation-as-success");
        HostOperation blocked = f.start(null);
        assertTrue(
                !assertRpcError("unresolved", blocked).unknown && f.calls == 1,
                "quarantine prevents subsequent selective deletion before dispatch");
        assertTrue(
                f.model.read(0, 64, false).getAsJsonArray("modules").size() == 2,
                "native topology failure does not disable independent read traversal");
        ModuleWriterFixture rejected = new ModuleWriterFixture();
        rejected.apply = false;
        op = rejected.start(null);
        rejected.next();
        assertTrue(
                assertUnknown("readback_mismatch", op) != null && !rejected.writer.quarantined() && rejected.calls == 1,
                "normal native delete return without removal is unknown, not fabricated success or automatic retry");
    }

    @Test
    void modulePostEventChangesRemainUnknown() {
        for (int change = 0; change < 6; change++) {
            ModuleWriterFixture f = new ModuleWriterFixture();
            HostOperation op = f.start(new GridModuleWriter.Move(7, 3, 10, 3));
            if (change == 0) f.model.proxy.put(TARGET, new Node(DEVICE));
            if (change == 1) f.context.values.put(GridTopologyContext.Field.PROJECT_OOK, true);
            if (change == 2) f.model.graph.put(MODULES, List.of(f.model.first));
            if (change == 3) f.model.second.put(WIDTH, 3);
            if (change == 4) f.model.second.put(Y, 100000);
            if (change == 5) f.cancelled = true;
            f.next();
            assertOutcome("unknown", op);
            assertTrue(
                    !f.writer.quarantined() && f.calls == 1,
                    "later target/context/layout/bounds/cancellation failure is observed once without native retry");
        }
    }

    /** Collect independent review regressions so one unfixed guard cannot hide the other violations. */
    @Test
    void moduleTopologyReviewRegressions() {
        List<String> failures = new ArrayList<>();
        for (int[] destination : List.of(new int[] {11, 4}, new int[] {10, 5}, new int[] {13, 6})) {
            topologyRegression(failures, "occupied footprint anchor " + Arrays.toString(destination), () -> {
                ModuleWriterFixture f = new ModuleWriterFixture();
                f.model.second.put(X, 10).put(Y, 4).put(WIDTH, 4).put(HEIGHT, 3);
                HostOperation op = f.start(new GridModuleWriter.Move(7, 3, destination[0], destination[1]));
                assertTrue(
                        unsuccessful(op)
                                && !op.dispatched()
                                && f.calls == 0
                                && !f.writer.quarantined()
                                && f.events.isEmpty(),
                        "anchor anywhere inside another half-open footprint must reject before dispatch without quarantine");
                assertTrue(
                        !assertRpcError("invalid_params", op).unknown,
                        "deterministic native occupancy rejection is not an unknown mutation");
            });
        }
        for (int[] destination : List.of(new int[] {14, 4}, new int[] {10, 7}, new int[] {9, 4})) {
            topologyRegression(failures, "outside footprint anchor " + Arrays.toString(destination), () -> {
                ModuleWriterFixture f = new ModuleWriterFixture();
                f.model.second.put(X, 10).put(Y, 4).put(WIDTH, 4).put(HEIGHT, 3);
                HostOperation op = f.start(new GridModuleWriter.Move(7, 3, destination[0], destination[1]));
                assertTrue(
                        op.dispatched() && f.calls == 1 && !op.result.isDone(),
                        "half-open right/bottom boundaries and rectangle overlap with an outside anchor remain allowed");
                f.next();
                assertTrue(
                        op.result.join().get("mutationDispatched").getAsBoolean() && !f.writer.quarantined(),
                        "allowed overlap still uses native placement and later observation");
            });
        }
        for (NativeReadContext.Field field : List.of(
                NativeReadContext.Field.DELETED, NativeReadContext.Field.CONTEXT, NativeReadContext.Field.PROJECT)) {
            for (boolean deleting : List.of(false, true)) {
                topologyRegression(failures, "unselected preflight " + field + " delete=" + deleting, () -> {
                    ModuleWriterFixture f = new ModuleWriterFixture();
                    f.context.override(
                            f.model.second,
                            field,
                            field == NativeReadContext.Field.DELETED
                                    ? true
                                    : field == NativeReadContext.Field.CONTEXT ? null : new Object());
                    HostOperation op = f.start(deleting ? null : new GridModuleWriter.Move(7, 3, 11, 5));
                    assertTrue(
                            unsuccessful(op)
                                    && !op.dispatched()
                                    && f.calls == 0
                                    && !f.writer.quarantined()
                                    && f.events.isEmpty(),
                            "every participating module must be live, attached and in the exact project before native dispatch");
                    assertTrue(
                            !assertRpcError("stale_context", op).unknown,
                            "unselected module lifecycle rejection is a preflight stale context");
                });
                topologyRegression(failures, "surviving post-event " + field + " delete=" + deleting, () -> {
                    ModuleWriterFixture f = new ModuleWriterFixture();
                    HostOperation op = f.start(deleting ? null : new GridModuleWriter.Move(7, 3, 11, 5));
                    assertTrue(
                            op.dispatched() && f.calls == 1 && !op.result.isDone(),
                            "native operation precedes later survivor checks");
                    f.context.override(
                            f.model.second,
                            field,
                            field == NativeReadContext.Field.DELETED
                                    ? true
                                    : field == NativeReadContext.Field.CONTEXT ? null : new Object());
                    f.next();
                    assertTrue(
                            unsuccessful(op) && !f.writer.quarantined() && f.calls == 1,
                            "a later deleted/detached/wrong-project survivor must not become an observed success");
                    assertTrue(
                            assertUnknown("stale_context", op) != null,
                            "post-native survivor failure remains unknown without quarantine or retry");
                });
            }
        }
        for (String change : List.of("selected_y", "unselected_y", "selected_left", "unselected_left")) {
            topologyRegression(failures, "invalid native reflow " + change, () -> {
                ModuleWriterFixture f = new ModuleWriterFixture();
                HostOperation op = f.start(new GridModuleWriter.Move(7, 3, 11, 5));
                assertTrue(
                        op.dispatched() && !op.result.isDone(), "move has returned before independent layout change");
                switch (change) {
                    case "selected_y":
                        f.model.first.put(Y, 6);
                        break;
                    case "unselected_y":
                        f.model.second.put(Y, 4);
                        break;
                    case "selected_left":
                        f.model.first.put(X, 10);
                        break;
                    case "unselected_left":
                        f.model.second.put(X, 7);
                        break;
                    default:
                        throw new AssertionError(change);
                }
                f.next();
                assertTrue(
                        unsuccessful(op) && !f.writer.quarantined() && f.calls == 1,
                        "wrong rows and leftward displacement violate native move/reflow postconditions, not normal reflow");
                assertTrue(
                        assertUnknown("readback_mismatch", op) != null,
                        "inconsistent later move coordinates are unknown without retry");
            });
        }
        topologyRegression(failures, "legitimate rightward native reflow", () -> {
            ModuleWriterFixture f = new ModuleWriterFixture();
            HostOperation op = f.start(new GridModuleWriter.Move(7, 3, 11, 5));
            f.model.first.put(X, 12);
            f.model.second.put(X, 14);
            f.next();
            JsonObject result = op.result.join();
            assertTrue(
                    result.get("outcome").getAsString().equals("applied")
                            && result.getAsJsonObject("actualPosition").get("y").getAsInt() == 5
                            && result.getAsJsonObject("graph")
                                            .getAsJsonArray("modules")
                                            .get(1)
                                            .getAsJsonObject()
                                            .get("y")
                                            .getAsInt()
                                    == 3,
                    "native rightward reflow remains valid while selected and unselected rows retain their respective baselines");
        });
        if (!failures.isEmpty())
            throw new AssertionError("Topology safety regressions: " + String.join("; ", failures));
    }

    private static void topologyRegression(List<String> failures, String label, Runnable scenario) {
        try {
            scenario.run();
        } catch (AssertionError | java.util.concurrent.CompletionException error) {
            failures.add(label + ": " + error.getMessage());
        }
    }

    public interface TestMoveCallback {
        void success(Object value);

        void error(Exception value);

        void error(String value);
    }

    @Test
    void nativeMoveCallbackCaptureNeverThrows() {
        try {
            java.lang.reflect.Method success = TestMoveCallback.class.getMethod("success", Object.class);
            java.lang.reflect.Method exception = TestMoveCallback.class.getMethod("error", Exception.class);
            java.lang.reflect.Method message = TestMoveCallback.class.getMethod("error", String.class);
            for (int mode = 0; mode < 6; mode++) {
                Bitwig614GridModuleAccess.MoveCallback capture =
                        new Bitwig614GridModuleAccess.MoveCallback(success, exception, message);
                if (mode == 0) capture.invoke(null, success, new Object[] {new Object()});
                if (mode == 1) capture.invoke(null, exception, new Object[] {new Exception("native")});
                if (mode == 2) capture.invoke(null, message, new Object[] {"rejected"});
                if (mode == 3) capture.invoke(null, success, new Object[] {null});
                if (mode == 4) {
                    capture.invoke(null, success, new Object[] {new Object()});
                    capture.invoke(null, success, new Object[] {new Object()});
                }
                if (mode == 0) {
                    capture.requireSuccess();
                    assertTrue(true, "nonthrowing success capture is checked only by caller after native return");
                } else assertRpcError("native_unavailable", capture::requireSuccess);
            }
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    @Test
    void connectionCasNoopAndLaterRepairedGraph() {
        ConnectionFixture stale = new ConnectionFixture();
        HostOperation op = stale.start(stale.request(stale.output(), null));
        assertTrue(
                !assertRpcError("stale_context", op).unknown && stale.calls == 0,
                "stale expectedSource fails before already-matched null and dispatch");
        ConnectionFixture noop = new ConnectionFixture();
        op = noop.start(noop.request(null, null));
        noop.next();
        JsonObject result = op.result.join();
        assertTrue(
                noop.calls == 0
                        && !op.dispatched()
                        && !result.get("nativeUndo").getAsBoolean()
                        && result.get("actualSource").isJsonNull(),
                "explicit null no-op skips setter and native undo entirely");
        ConnectionFixture connected = new ConnectionFixture();
        op = connected.start(connected.request(null, connected.output()));
        assertTrue(
                op.dispatched() && !op.result.isDone() && connected.calls == 1,
                "same-module cable uses native setter without invented cycle ban");
        connected.next();
        result = op.result.join();
        assertTrue(
                result.get("outcome").getAsString().equals("applied")
                        && result.get("previousSource").isJsonNull()
                        && result.getAsJsonObject("actualSource")
                                .get("portKey")
                                .getAsString()
                                .equals("out"),
                "later observed endpoint is exact and previous disconnected state preserved");
        op = connected.start(connected.request(connected.output(), connected.output()));
        connected.next();
        assertTrue(
                connected.calls == 1
                        && !op.dispatched()
                        && op.result.join().get("outcome").getAsString().equals("already_matched"),
                "matching connected source does not begin a native undo record");
        op = connected.start(connected.request(connected.output(), null));
        connected.next();
        assertTrue(
                connected.calls == 2 && op.result.join().get("actualSource").isJsonNull(),
                "disconnect passes explicit native null once");
        ConnectionFixture repaired = new ConnectionFixture();
        Node old = repaired.model.port(OUTPUT, repaired.model.second, "old");
        Node sentinel =
                repaired.model.port(INPUT, repaired.model.second, "sentinel").put(INPUT_SOURCE, old);
        repaired.model.second.put(OUTPUTS, List.of(old)).put(INPUTS, List.of(sentinel));
        repaired.model.input.put(INPUT_SOURCE, old);
        GridConnectionWriter.Endpoint prior = new GridConnectionWriter.Endpoint(new UUID(1, 2), "old");
        op = repaired.start(repaired.request(prior, repaired.output()));
        // Simulate native very-end-of-event repair after normal setter return, including collateral links.
        repaired.model.input.put(INPUT_SOURCE, null);
        sentinel.put(INPUT_SOURCE, null);
        repaired.next();
        result = op.result.join();
        assertTrue(
                result.get("outcome").getAsString().equals("unknown")
                        && result.get("reason").getAsString().equals("readback_mismatch")
                        && result.get("actualSource").isJsonNull()
                        && result.get("previousSource").equals(GridConnectionWriter.Endpoint.json(prior))
                        && result.getAsJsonObject("graph")
                                        .getAsJsonArray("connections")
                                        .size()
                                == 0
                        && repaired.calls == 1,
                "rejected replacement can lose old source and other links; observed graph never reconnects or rolls back");
    }

    @Test
    void connectionMembershipContextAndIdentityGuards() {
        for (int change = 0; change < 7; change++) {
            ConnectionFixture f = new ConnectionFixture();
            if (change == 0) f.model.first.put(OUTPUTS, List.of());
            if (change == 1) f.model.input.put(INPUT_SOURCE, f.model.port(OUTPUT, f.model.first, "out"));
            if (change == 2)
                f.model.first.put(OUTPUTS, List.of(f.model.output, f.model.port(OUTPUT, f.model.first, "out")));
            if (change == 3)
                f.model.first.put(INPUTS, List.of(f.model.input, f.model.port(INPUT, f.model.first, "in")));
            if (change == 4) f.model.output.put(OWNER_MODULE, f.model.second);
            if (change == 5) f.model.second.put(INSTANCE_ID, new UUID(1, 1));
            if (change == 6) f.model.output.threadAllowed = false;
            HostOperation op = f.start(f.request(null, f.output()));
            assertTrue(
                    unsuccessful(op) && !op.dispatched() && f.calls == 0 && !f.quarantine.quarantined(),
                    "missing/excluded output, ambiguous keys/UUID, owner or thread errors fail before dispatch");
        }
        for (NativeReadContext.Field field : List.of(
                NativeReadContext.Field.DELETED, NativeReadContext.Field.CONTEXT, NativeReadContext.Field.PROJECT)) {
            for (int member = 0; member < 3; member++) {
                ConnectionFixture f = new ConnectionFixture();
                Node checked = member == 0 ? f.model.input : member == 1 ? f.model.output : f.model.second;
                f.context.override(checked, field, field == NativeReadContext.Field.DELETED ? true : null);
                HostOperation op = f.start(f.request(null, f.output()));
                assertTrue(
                        !assertRpcError("stale_context", op).unknown && f.calls == 0,
                        "every input/output/module requires live attached exact project context");
            }
        }
        for (GridTopologyContext.Field field : List.of(
                GridTopologyContext.Field.CIU,
                GridTopologyContext.Field.PROJECT_ADM,
                GridTopologyContext.Field.PROJECT_OOK,
                GridTopologyContext.Field.UNDO_ACTIVE,
                GridTopologyContext.Field.UNDO_EXECUTING,
                GridTopologyContext.Field.UNDO_ICL)) {
            ConnectionFixture f = new ConnectionFixture();
            f.context.values.put(field, true);
            HostOperation op = f.start(f.request(null, f.output()));
            assertTrue(
                    !assertRpcError("unsafe_state", op).unknown && f.calls == 0,
                    "conservative shared native context blocks cable before dispatch");
        }
        for (int change = 0; change < 4; change++) {
            ConnectionFixture f = new ConnectionFixture();
            final int selected = change;
            f.beforePrepare = () -> {
                if (selected == 0) f.model.input.put(INPUT_SOURCE, f.model.output);
                if (selected == 1) f.model.first.put(OUTPUTS, List.of(f.model.port(OUTPUT, f.model.first, "out")));
                if (selected == 2) f.model.first.put(INPUTS, List.of(f.model.port(INPUT, f.model.first, "in")));
                if (selected == 3)
                    f.model.graph.put(MODULES, List.of(f.model.module(0, f.model.graph), f.model.second));
            };
            HostOperation op = f.start(f.request(null, f.output()));
            assertTrue(
                    !assertRpcError("stale_context", op).unknown && f.calls == 0,
                    "captured source and module/port identities rechecked after native preparation");
        }
        for (int change = 0; change < 5; change++) {
            ConnectionFixture f = new ConnectionFixture();
            HostOperation op = f.start(f.request(null, f.output()));
            if (change == 0) f.model.first.put(OUTPUTS, List.of(f.model.port(OUTPUT, f.model.first, "out")));
            if (change == 1) f.context.override(f.model.output, NativeReadContext.Field.CONTEXT, null);
            if (change == 2) f.context.values.put(GridTopologyContext.Field.UNDO, new Object());
            if (change == 3) f.model.proxy.put(TARGET, new Node(DEVICE));
            if (change == 4) f.cancelled = true;
            f.next();
            assertUnknown(change == 4 ? "cancelled" : "stale_context", op);
            assertTrue(
                    f.calls == 1 && !f.quarantine.quarantined(),
                    "later source membership/context/target/cancellation failure never retries or masquerades as synchronous native failure");
        }
    }

    private static GridConnectionWriter.Request cable(
            ConnectionFixture f, String inputKey, UUID owner, GridConnectionWriter.Endpoint expected) {
        return new GridConnectionWriter.Request(
                new GridConnectionWriter.Endpoint(owner, inputKey), expected, f.output());
    }

    @Test
    void connectionBatchAppliesInOrderWithOneDiff() {
        ConnectionFixture f = new ConnectionFixture();
        f.extraInputs();
        HostOperation op = f.startBatch(List.of(f.request(null, f.output()), cable(f, "in2", new UUID(1, 2), null)));
        assertTrue(
                op.dispatched() && !op.result.isDone() && f.calls == 1,
                "first entry dispatches and the next waits for its later observation");
        f.next();
        assertTrue(f.calls == 2 && !op.result.isDone(), "second entry starts only after the first was observed");
        f.next();
        JsonObject result = op.result.join();
        JsonArray items = result.getAsJsonArray("connections");
        assertEquals(List.of("applied", "applied"), outcomes(items));
        assertTrue(
                result.get("outcome").getAsString().equals("applied")
                        && result.get("written").getAsInt() == 2
                        && items.get(1).getAsJsonObject().has("actualSource"),
                "batch reports per-entry results and the worst entry as its outcome");
        JsonObject shaped = GridDiff.shape(result, false);
        assertTrue(
                !shaped.has("graph")
                        && shaped.getAsJsonArray("connectionsChanged").size() == 2,
                "one diff covers every connection of the batch");
        op = f.startBatch(List.of(f.request(f.output(), f.output())));
        f.next();
        result = op.result.join();
        assertTrue(
                result.get("outcome").getAsString().equals("already_matched")
                        && result.get("written").getAsInt() == 0
                        && outcomes(result.getAsJsonArray("connections")).equals(List.of("already_matched"))
                        && f.calls == 2,
                "already matching entries are not dispatched");
    }

    @Test
    void connectionBatchChecksEveryExpectationBeforeWriting() {
        ConnectionFixture f = new ConnectionFixture();
        f.extraInputs();
        HostOperation op = f.startBatch(List.of(
                f.request(null, f.output()),
                cable(f, "in2", new UUID(1, 2), f.output()),
                cable(f, "nope", new UUID(1, 2), null)));
        RpcException error = assertRpcError("stale_context", op);
        assertTrue(
                !error.unknown
                        && f.calls == 0
                        && !op.dispatched()
                        && f.events.isEmpty()
                        && error.getMessage().contains("#1")
                        && error.getMessage().contains("#2")
                        && !error.getMessage().contains("#0"),
                "any mismatch or absent port writes nothing and names each failing entry");
        ConnectionFixture unsupported = new ConnectionFixture();
        unsupported.supported = false;
        assertRpcError(
                "native_unavailable", unsupported.startBatch(List.of(unsupported.request(null, unsupported.output()))));
        assertTrue(
                unsupported.calls == 0 && !unsupported.quarantine.quarantined(),
                "unsupported runtime class is rejected before any entry dispatches");
    }

    @Test
    void connectionBatchStopsAtFirstFailureAndUnknownQuarantines() {
        ConnectionFixture f = new ConnectionFixture();
        f.extraInputs();
        f.afterMutation = () -> {
            if (f.calls == 2) throw new AssertionError("native failure on second entry");
        };
        HostOperation op = f.startBatch(List.of(
                f.request(null, f.output()),
                cable(f, "in2", new UUID(1, 2), null),
                cable(f, "in3", new UUID(1, 2), null)));
        f.next();
        JsonObject result = op.result.join();
        JsonArray items = result.getAsJsonArray("connections");
        assertEquals(List.of("applied", "unknown/native_unavailable", "not_applied/not_reached"), outcomes(items));
        assertTrue(
                result.get("outcome").getAsString().equals("unknown")
                        && f.calls == 2
                        && f.quarantine.quarantined()
                        && !result.has("graph")
                        && !result.has("connectionsChanged")
                        && f.events.isEmpty(),
                "a failure after an entry's native set is unknown, quarantines topology, stops the rest and offers no diff");
        ConnectionFixture first = new ConnectionFixture();
        first.extraInputs();
        first.afterMutation = () -> {
            throw new AssertionError("native failure on first entry");
        };
        op = first.startBatch(List.of(first.request(null, first.output()), cable(first, "in2", new UUID(1, 2), null)));
        assertTrue(
                op.result.join().get("outcome").getAsString().equals("unknown")
                        && first.calls == 1
                        && first.quarantine.quarantined(),
                "unknown first entry still quarantines and never starts the second");
        ConnectionFixture drift = new ConnectionFixture();
        drift.extraInputs();
        op = drift.startBatch(List.of(drift.request(null, drift.output()), cable(drift, "in2", new UUID(1, 2), null)));
        ((Node) ((List<?>) drift.model.second.values.get(INPUTS)).get(0)).put(INPUT_SOURCE, drift.model.output);
        drift.next();
        result = op.result.join();
        assertEquals(List.of("applied", "not_applied/stale_context"), outcomes(result.getAsJsonArray("connections")));
        assertTrue(
                result.get("outcome").getAsString().equals("not_applied")
                        && drift.calls == 1
                        && result.has("graph")
                        && !drift.quarantine.quarantined(),
                "a later entry that fails before its own dispatch is not_applied with the observed diff and no quarantine");
    }

    @Test
    void connectionBatchStopsAtNativeRepairMismatch() {
        ConnectionFixture f = new ConnectionFixture();
        f.extraInputs();
        HostOperation op = f.startBatch(List.of(f.request(null, f.output()), cable(f, "in2", new UUID(1, 2), null)));
        // Native repair drops the first link after the setter returned.
        f.model.input.put(INPUT_SOURCE, null);
        f.next();
        JsonObject result = op.result.join();
        JsonArray items = result.getAsJsonArray("connections");
        assertEquals(List.of("unknown/readback_mismatch", "not_applied/not_reached"), outcomes(items));
        assertTrue(
                result.get("outcome").getAsString().equals("unknown")
                        && f.calls == 1
                        && items.get(0).getAsJsonObject().get("actualSource").isJsonNull()
                        && result.has("graph")
                        && result.has("graphBefore")
                        && !f.quarantine.quarantined()
                        && f.events.isEmpty(),
                "a native-repair mismatch stops the batch unknown (fail closed) with the observed diff, never quarantining");
    }

    @Test
    void unreadableGeometryAndUnknownContextFamiliesFailClosed() {
        assertInvalidParams(() -> GridConnectionWriter.Request.parseBatch(HostFixture.json("{\"connections\":[1]}")));
        ModuleWriterFixture geometry = new ModuleWriterFixture();
        geometry.model.first.put(WIDTH, "2");
        assertRpcError("native_unavailable", geometry.start(new GridModuleWriter.Move(7, 3, 10, 3)));
        ContextFixture context = new ContextFixture();
        assertRpcError(
                "native_unavailable",
                () -> context.reader.capture(new Object(), new Object(), new Object(), new Object(), MODULE, () -> {}));
        for (int bad = 0; bad < 3; bad++) {
            ModuleWriterFixture f = new ModuleWriterFixture();
            if (bad == 0) f.context.defaultSupported = false;
            if (bad == 1) f.context.values.put(GridTopologyContext.Field.UNDO_ACTIVE, "yes");
            if (bad == 2) f.context.values.put(NativeReadContext.Field.DISPOSED, "yes");
            assertRpcError("native_unavailable", f.start(new GridModuleWriter.Move(7, 3, 10, 3)));
            assertTrue(f.calls == 0, "an unreadable topology context dispatches nothing");
        }
    }

    @Test
    void connectionBatchParsingIsBounded() {
        UUID id = new UUID(1, 1);
        java.util.function.Function<Integer, JsonObject> body = count -> {
            JsonObject p = new JsonObject();
            JsonArray list = new JsonArray();
            for (int i = 0; i < count; i++) {
                JsonObject c = new JsonObject();
                c.addProperty("targetModuleId", id.toString());
                c.addProperty("targetPortKey", "in" + i);
                c.add("expectedSource", JsonNull.INSTANCE);
                c.add("source", JsonNull.INSTANCE);
                list.add(c);
            }
            p.add("connections", list);
            return p;
        };
        assertTrue(GridConnectionWriter.Request.parseBatch(body.apply(32)).size() == 32, "32 connections are accepted");
        assertRpcError("invalid_params", () -> GridConnectionWriter.Request.parseBatch(body.apply(0)));
        assertRpcError("invalid_params", () -> GridConnectionWriter.Request.parseBatch(body.apply(33)));
        JsonObject repeat = body.apply(2);
        repeat.getAsJsonArray("connections").get(1).getAsJsonObject().addProperty("targetPortKey", "in0");
        assertRpcError("invalid_params", () -> GridConnectionWriter.Request.parseBatch(repeat));
        JsonObject extra = body.apply(1);
        extra.getAsJsonArray("connections").get(0).getAsJsonObject().addProperty("x", 1);
        assertRpcError("invalid_params", () -> GridConnectionWriter.Request.parseBatch(extra));
        JsonObject missing = body.apply(1);
        missing.getAsJsonArray("connections").get(0).getAsJsonObject().remove("source");
        assertRpcError("invalid_params", () -> GridConnectionWriter.Request.parseBatch(missing));
    }

    @Test
    void connectionBoundsAndIndependentGeometry() {
        ConnectionFixture independent = new ConnectionFixture();
        independent.model.unavailable.add(FOOTPRINT);
        independent.model.second.put(Y, Integer.MAX_VALUE);
        HostOperation op = independent.start(independent.request(null, independent.output()));
        independent.next();
        assertTrue(
                op.result.join().get("outcome").getAsString().equals("applied") && independent.calls == 1,
                "cable capability does not depend on native footprints or move-specific occupied y-span");
        for (int bound = 0; bound < 3; bound++) {
            ConnectionFixture f = new ConnectionFixture();
            if (bound == 0) {
                List<Node> modules = new ArrayList<>();
                for (int i = 0; i <= GridTopologyContext.MAX_MODULES; i++)
                    modules.add(f.model.module(i, f.model.graph));
                f.model.graph.put(MODULES, modules);
            } else if (bound == 1) {
                List<Node> ports = new ArrayList<>();
                for (int i = 0; i <= GridSnapshotReader.MAX_CHILDREN; i++)
                    ports.add(f.model.port(OUTPUT, f.model.first, "o" + i));
                f.model.first.put(OUTPUTS, ports);
            } else {
                List<Node> modules = new ArrayList<>();
                for (int i = 0; i < 700; i++) {
                    Node module = f.model.module(i, f.model.graph);
                    List<Node> ports = new ArrayList<>();
                    for (int j = 0; j < 200; j++) ports.add(f.model.port(OUTPUT, module, "o" + j));
                    module.put(OUTPUTS, ports);
                    modules.add(module);
                }
                f.model.graph.put(MODULES, modules);
            }
            op = f.start(f.request(null, f.output()));
            assertTrue(
                    !assertRpcError("out_of_bounds", op).unknown && f.calls == 0 && f.model.closed > 0,
                    "module/port/total budgets close streams before native dispatch");
        }
    }

    @Test
    void connectionFailuresShareStickyQuarantine() {
        ConnectionFixture cable = new ConnectionFixture();
        cable.afterMutation = () -> {
            throw new AssertionError("native listener/close failure");
        };
        HostOperation op = cable.start(cable.request(null, cable.output()));
        assertTrue(
                assertUnknown("native_unavailable", op) != null
                        && cable.quarantine.quarantined()
                        && cable.calls == 1
                        && cable.events.isEmpty(),
                "synchronous native failure is sticky unknown, not observation or rollback");
        ModuleWriterFixture modules = new ModuleWriterFixture();
        GridModuleWriter blocked = new GridModuleWriter(
                modules.model.reader, modules.model, modules.context.reader, modules, cable.quarantine);
        boolean[] otherDeviceDispatched = {false};
        ModuleWriterFixture spare = new ModuleWriterFixture();
        new GridModuleWriter(spare.model.reader, spare.model, spare.context.reader, spare, cable.quarantine)
                .delete(
                        spare.model.proxy,
                        TRACK_ID,
                        spare.moduleId,
                        () -> {},
                        () -> otherDeviceDispatched[0] = true,
                        value -> {},
                        error -> {});
        assertTrue(otherDeviceDispatched[0], "another device is not blocked by the failed device's quarantine");
        String owner = cable.quarantine.reserve(GridInsertionWriterTest.ANY);
        cable.quarantine.poison(
                owner, GridTopologyQuarantine.device(TRACK_ID, modules.model.graph), new com.google.gson.JsonObject());
        assertRpcError(
                "unresolved",
                () -> blocked.delete(
                        modules.model.proxy,
                        TRACK_ID,
                        modules.moduleId,
                        () -> {},
                        () -> {
                            throw new AssertionError("shared quarantine dispatched module delete");
                        },
                        value -> {},
                        error -> {}));
        ConnectionFixture recreated = new ConnectionFixture(cable.quarantine);
        recreated.start(recreated.request(null, recreated.output()));
        assertTrue(
                recreated.calls == 1 && cable.quarantine.quarantined(),
                "a recreated writer on another device is admitted and cannot reset the failed device's quarantine");
        modules.afterMutation = () -> {
            throw new AssertionError("native module failure");
        };
        assertUnknown("native_unavailable", modules.start(null));
        ConnectionFixture reverse = new ConnectionFixture(modules.quarantine);
        reverse.start(reverse.request(null, reverse.output()));
        assertTrue(reverse.calls == 1, "a module failure does not block the cable setter on another device");
        assertTrue(cable.model.read(0, 64, false).has("modules"), "read-only graph remains independently available");
        ConnectionFixture unsupported = new ConnectionFixture();
        unsupported.supported = false;
        assertRpcError("native_unavailable", unsupported.start(unsupported.request(null, unsupported.output())));
        assertTrue(
                !unsupported.quarantine.quarantined(),
                "runtime class rejection is predispatch and does not poison lifetime");
        for (boolean noop : List.of(false, true)) {
            ConnectionFixture timeout = new ConnectionFixture();
            HostOperation late = new HostOperation(Long.MAX_VALUE);
            boolean[] expired = {false};
            timeout.edit(
                    timeout.request(null, noop ? null : timeout.output()),
                    () -> {
                        if (expired[0]) throw Errors.notApplied(Reason.DEADLINE, "later observation expired");
                    },
                    late::markDispatched,
                    value -> late.result.complete(value),
                    late::fail);
            expired[0] = true;
            timeout.next();
            if (noop) assertRpcError("deadline", late);
            else assertUnknown("deadline", late);
            assertTrue(
                    timeout.calls == (noop ? 0 : 1) && !timeout.quarantine.quarantined(),
                    "later-event timeout never retries, invents dispatch for no-op, or disguises unknown mutation as rejection");
        }
    }

    @Test
    void connectionExactRuntimeGatesRejectOverrides() {
        class Base {}
        class Derived extends Base {}
        Bitwig614GridConnectionAccess.requireExact(new Base(), Base.class, "test input");
        assertRpcError(
                "native_unavailable",
                () -> Bitwig614GridConnectionAccess.requireExact(new Derived(), Base.class, "test input"));
        assertRpcError(
                "native_unavailable",
                () -> Bitwig614GridConnectionAccess.requireExact(new Derived(), Base.class, "test undo"));
        assertRpcError(
                "native_unavailable", () -> Bitwig614GridConnectionAccess.requireExact(null, Base.class, "test undo"));
    }

    static final class ConnectionFixture implements GridConnectionWriter.Access {
        final Fixture model = new Fixture();
        final ModuleContextFixture context = new ModuleContextFixture();
        final Deque<Runnable> events = new ArrayDeque<>();
        final java.util.function.Consumer<Runnable> queue;
        final GridTopologyQuarantine quarantine;
        final GridConnectionWriter writer;
        int calls;
        boolean cancelled, supported = true;
        Runnable beforePrepare = () -> {}, afterMutation = () -> {};

        ConnectionFixture() {
            this(new GridTopologyQuarantine(new Properties()));
        }

        ConnectionFixture(GridTopologyQuarantine quarantine) {
            this(quarantine, null);
        }

        ConnectionFixture(java.util.function.Consumer<Runnable> queue) {
            this(new GridTopologyQuarantine(new Properties()), queue);
        }

        ConnectionFixture(GridTopologyQuarantine quarantine, java.util.function.Consumer<Runnable> queue) {
            this.quarantine = quarantine;
            this.queue = queue == null ? events::add : queue;
            writer = new GridConnectionWriter(model.reader, model, context.reader, this, quarantine);
        }

        GridConnectionWriter.Endpoint output() {
            return new GridConnectionWriter.Endpoint(new UUID(1, 1), "out");
        }

        GridConnectionWriter.Request request(
                GridConnectionWriter.Endpoint expected, GridConnectionWriter.Endpoint source) {
            return new GridConnectionWriter.Request(
                    new GridConnectionWriter.Endpoint(new UUID(1, 1), "in"), expected, source);
        }

        HostOperation start(GridConnectionWriter.Request request) {
            HostOperation op = new HostOperation(Long.MAX_VALUE);
            try {
                edit(
                        request,
                        () -> {
                            if (cancelled) throw Errors.notApplied(Reason.CANCELLED, "cancelled");
                        },
                        () -> op.markDispatched(),
                        value -> op.result.complete(value),
                        op::fail);
            } catch (Throwable error) {
                op.fail(error);
            }
            return op;
        }

        void edit(
                GridConnectionWriter.Request request,
                Runnable check,
                Runnable dispatch,
                java.util.function.Consumer<JsonObject> complete,
                java.util.function.Consumer<Throwable> fail) {
            writer.set(model.proxy, TRACK_ID, request, check, dispatch, complete, fail);
        }

        void next() {
            events.removeFirst().run();
        }
        /** Adds in2 and in3 inputs on the second module for batch tests. */
        void extraInputs() {
            model.second.put(
                    INPUTS, List.of(model.port(INPUT, model.second, "in2"), model.port(INPUT, model.second, "in3")));
        }

        void editAll(
                List<GridConnectionWriter.Request> requests,
                Runnable check,
                Runnable dispatch,
                java.util.function.Consumer<JsonObject> complete,
                java.util.function.Consumer<Throwable> fail) {
            writer.setAll(model.proxy, TRACK_ID, requests, check, dispatch, complete, fail);
        }

        HostOperation startBatch(List<GridConnectionWriter.Request> requests) {
            HostOperation op = new HostOperation(Long.MAX_VALUE);
            try {
                writer.setAll(
                        model.proxy,
                        TRACK_ID,
                        requests,
                        () -> {
                            if (cancelled) throw Errors.notApplied(Reason.CANCELLED, "cancelled");
                        },
                        () -> op.markDispatched(),
                        value -> op.result.complete(value),
                        op::fail);
            } catch (Throwable error) {
                op.fail(error);
            }
            return op;
        }

        @Override
        public void requireSupported(Object input, Object undo) {
            if (!supported) throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "unsupported runtime class");
        }

        @Override
        public Runnable prepareSet(Object input, Object source) {
            beforePrepare.run();
            return () -> {
                calls++;
                ((Node) input).put(INPUT_SOURCE, source);
                afterMutation.run();
            };
        }

        @Override
        public void execute(Runnable observation) {
            queue.accept(observation);
        }
    }

    static final class ModuleWriterFixture implements GridModuleWriter.Access {
        final Fixture model = new Fixture();
        final ModuleContextFixture context = new ModuleContextFixture();
        final Deque<Runnable> events = new ArrayDeque<>();
        final java.util.function.Consumer<Runnable> queue;
        final GridTopologyQuarantine quarantine = new GridTopologyQuarantine(new Properties());
        final GridModuleWriter writer = new GridModuleWriter(model.reader, model, context.reader, this, quarantine);
        UUID trackId = TRACK_ID, moduleId = new UUID(1, 1);
        int calls;
        boolean apply = true, cancelled, moveSupported = true;
        HostOperation current;
        Runnable afterMutation = () -> {};

        ModuleWriterFixture() {
            queue = events::add;
        }

        ModuleWriterFixture(java.util.function.Consumer<Runnable> queue) {
            this.queue = queue;
        }

        HostOperation start(GridModuleWriter.Move move) {
            HostOperation op = current = new HostOperation(Long.MAX_VALUE);
            try {
                edit(
                        move,
                        () -> {
                            if (cancelled) throw Errors.notApplied(Reason.CANCELLED, "cancelled");
                        },
                        () -> op.markDispatched(),
                        value -> op.result.complete(value),
                        op::fail);
            } catch (Throwable error) {
                op.fail(error);
            }
            return op;
        }

        void edit(
                GridModuleWriter.Move move,
                Runnable check,
                Runnable dispatch,
                java.util.function.Consumer<JsonObject> complete,
                java.util.function.Consumer<Throwable> fail) {
            if (move == null) writer.delete(model.proxy, trackId, moduleId, check, dispatch, complete, fail);
            else writer.move(model.proxy, trackId, moduleId, move, check, dispatch, complete, fail);
        }

        void next() {
            events.removeFirst().run();
        }

        @Override
        public Runnable prepareMove(Object graph, Object module, int x, int y) {
            if (!moveSupported) throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "move descriptor unavailable");
            return () -> {
                calls++;
                if (apply) ((Node) module).put(X, x).put(Y, y);
                afterMutation.run();
            };
        }

        @Override
        public Runnable prepareDelete(Object graph, Object module) {
            return () -> {
                calls++;
                if (apply) {
                    List<Object> remaining = new ArrayList<>((List<?>) ((Node) graph).values.get(MODULES));
                    remaining.remove(module);
                    ((Node) graph).put(MODULES, remaining);
                }
                afterMutation.run();
            };
        }

        @Override
        public void execute(Runnable observation) {
            queue.accept(observation);
        }
    }

    static final class ModuleContextFixture implements GridTopologyContext.Access {
        final GridTopologyContext reader = new GridTopologyContext(this);
        final Map<Enum<?>, Object> values = new HashMap<>();
        final IdentityHashMap<Object, Map<Enum<?>, Object>> perObject = new IdentityHashMap<>();
        final Object context = new Object(), project = new Object(), dt = new Object();
        boolean defaultSupported = true;

        ModuleContextFixture() {
            for (GridTopologyContext.Field field : GridTopologyContext.Field.values()) values.put(field, false);
            for (NativeReadContext.Field field : NativeReadContext.Field.values()) values.put(field, false);
            values.put(NativeReadContext.Field.CONTEXT, context);
            values.put(NativeReadContext.Field.PROJECT, project);
            values.put(GridTopologyContext.Field.GRAPH_PROJECT, project);
            values.put(NativeReadContext.Field.ROOT, project);
            values.put(NativeReadContext.Field.INITIALIZED, true);
            values.put(NativeReadContext.Field.CONTEXT_THREAD, true);
            values.put(NativeReadContext.Field.CURRENT_DT, dt);
            values.put(NativeReadContext.Field.DEFAULT_DT, dt);
            values.put(NativeReadContext.Field.DT_CONTEXT, context);
            values.put(GridTopologyContext.Field.UNDO, new Object());
            values.put(GridTopologyContext.Field.LOCK, new Object());
            values.put(GridTopologyContext.Field.LOCK_OWNER, dt);
        }

        void override(Object object, Enum<?> field, Object value) {
            perObject.computeIfAbsent(object, ignored -> new HashMap<>()).put(field, value);
        }

        @Override
        public Object read(GridTopologyContext.Field field, Object object) {
            return value(field, object);
        }

        @Override
        public Object read(NativeReadContext.Field field, Object object) {
            return value(field, object);
        }

        private Object value(Enum<?> field, Object object) {
            Map<Enum<?>, Object> specific = perObject.get(object);
            return specific != null && specific.containsKey(field) ? specific.get(field) : values.get(field);
        }

        @Override
        public boolean defaultContext(Object context) {
            return defaultSupported;
        }
    }

    /** Real shared binding/context/writer logic over a semantic model, also used by queue-boundary tests. */
    static final class WriterFixture implements GridParameterWriter.Access {
        final Fixture model = inspectionFixture();
        final ContextFixture context = new ContextFixture();
        final HostOperation op = new HostOperation(Long.MAX_VALUE);
        final GridParameterWriter writer = new GridParameterWriter(model.reader, context.reader, this, null, null);
        Node child = (Node) ((List<?>) model.first.values.get(CHILDREN)).get(0);
        GridSnapshotReader.Kind kind = BOOLEAN;
        GridSnapshotReader.Field field = BOOLEAN_VALUE;
        int calls;
        boolean apply = true, supported = true, cancelled;
        Runnable afterMutation = () -> {};

        WriterFixture() {
            context.values.put(GridParameterContext.Field.LOCK_OWNER, context.dt);
        }

        void scalar(GridSnapshotReader.Kind kind, Object value, Number min, Number max) {
            this.kind = kind;
            field = kind == FLOAT
                    ? FLOAT_VALUE
                    : kind == INTEGER ? INTEGER_VALUE : kind == ENUM ? ENUM_VALUE : BOOLEAN_VALUE;
            child = model.parameter(kind, model.first).put(field, value);
            if (kind == FLOAT)
                child.put(
                        FLOAT_DEFINITION,
                        new Node().put(FLOAT_MIN, min.doubleValue()).put(FLOAT_MAX, max.doubleValue()));
            if (kind == INTEGER)
                child.put(
                        INTEGER_DEFINITION,
                        new Node().put(INTEGER_MIN, min.intValue()).put(INTEGER_MAX, max.intValue()));
            if (kind == ENUM)
                child.put(ENUM_DEFINITION, new Node().put(OPTIONS, List.of(option(10, "Ten"), option(42, "Answer"))));
            model.first.put(CHILDREN, List.of(child));
        }

        JsonObject set(GridParameterWriter.Request request) {
            return set(
                    request,
                    () -> {
                        if (cancelled) throw Errors.notApplied(Reason.CANCELLED, "cancelled");
                    },
                    () -> op.markDispatched());
        }

        JsonObject set(GridParameterWriter.Request request, Runnable check, Runnable dispatch) {
            return writer.set(model.proxy, TRACK_ID, new UUID(1, 1), "LOCAL_ID", request, check, dispatch);
        }

        JsonObject setAll(java.util.List<GridParameterWriter.Entry> entries, Runnable check, Runnable dispatch) {
            return writer.setAll(model.proxy, TRACK_ID, entries, check, dispatch);
        }

        /** The writer's failure as the request reports it: once dispatched, unknown. */
        RpcException failure(GridParameterWriter.Request request) {
            RpcException error = assertThrows(RpcException.class, () -> set(request));
            return op.dispatched() ? Errors.unknown(error.reason, error.getMessage()) : error;
        }

        @Override
        public void requireSupported(Object parameter, GridSnapshotReader.Kind kind) {
            if (!supported) throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "unmapped model");
        }

        @Override
        public void setRaw(Object parameter, double value) {
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

    private static final class PanelFixture implements GridBooleanPanelWriter.Access {
        final Fixture model = new Fixture();
        final ContextFixture context = new ContextFixture();
        final HostOperation op = new HostOperation(Long.MAX_VALUE);
        final Node panel = new Node(PANEL_BOOLEAN)
                .put(OWNER_MODULE, model.first)
                .put(PATH, "panel/local")
                .put(PARAMETER_ID, "PANEL_ID")
                .put(BOOLEAN_VALUE, true);
        final Node definition = new Node().put(FLOAT_MIN, -1.0).put(FLOAT_MAX, 1.0);
        final Node coupled = model.parameter(FLOAT, model.first)
                .put(PARAMETER_ID, "VALUE_ID")
                .put(FLOAT_VALUE, -0.5)
                .put(FLOAT_DEFINITION, definition)
                .put(BIPOLAR_PANEL, panel);
        final GridBooleanPanelWriter panelWriter = new GridBooleanPanelWriter(model.reader, context.reader, this);
        final GridParameterWriter writer = new GridParameterWriter(
                model.reader,
                context.reader,
                new GridParameterWriter.Access() {
                    @Override
                    public void requireSupported(Object parameter, GridSnapshotReader.Kind kind) {
                        throw new AssertionError("Panel must not enter scalar authorization");
                    }

                    @Override
                    public void setRaw(Object parameter, double value) {
                        throw new AssertionError("Panel must not invoke a numeric controller operation");
                    }
                },
                panelWriter,
                null);
        int calls;
        boolean apply = true;
        Runnable afterMutation = () -> {};

        PanelFixture() {
            coupled.kinds.add(BIPOLAR_FLOAT);
            model.first.put(CHILDREN, List.of(panel, coupled));
            context.values.put(GridParameterContext.Field.LOCK_OWNER, context.dt);
            context.values.put(GridParameterContext.Field.GUI_PANEL_BOOLEAN, (Supplier<Object>) Object::new);
            context.override(panel, GridParameterContext.Field.LICENSED, null);
            context.override(panel, GridParameterContext.Field.TOUCHED, null);
            context.override(panel, GridParameterContext.Field.GUI_BOOLEAN, null);
        }

        JsonObject set(GridParameterWriter.Request request) {
            return writer.set(
                    model.proxy, TRACK_ID, new UUID(1, 1), "PANEL_ID", request, () -> {}, () -> op.markDispatched());
        }

        RpcException failure() {
            RpcException error = assertThrows(RpcException.class, () -> set(request("boolean", "true", "false")));
            return op.dispatched() ? Errors.unknown(error.reason, error.getMessage()) : error;
        }

        @Override
        public void requireSupported(Object parameter) {
            if (parameter != panel) throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "unmapped panel");
        }

        @Override
        public void set(Object parameter, boolean value) {
            calls++;
            if (apply) {
                panel.put(BOOLEAN_VALUE, value);
                definition.put(FLOAT_MIN, value ? -1.0 : 0.0);
                if (!value && (Double) coupled.values.get(FLOAT_VALUE) < 0.0) coupled.put(FLOAT_VALUE, 0.0);
            }
            afterMutation.run();
        }
    }

    static final class ContextFixture implements GridParameterContext.Access {
        final GridParameterContext reader = new GridParameterContext(this);
        final Map<Enum<?>, Object> values = new HashMap<>();
        final IdentityHashMap<Object, Map<Enum<?>, Object>> perObject = new IdentityHashMap<>();
        final Object context = new Object(), project = new Object(), dt = new Object();
        boolean defaultSupported = true;

        ContextFixture() {
            for (GridParameterContext.Field field : GridParameterContext.Field.values()) values.put(field, false);
            for (NativeReadContext.Field field : NativeReadContext.Field.values()) values.put(field, false);
            values.put(NativeReadContext.Field.CONTEXT, context);
            values.put(NativeReadContext.Field.PROJECT, project);
            values.put(GridParameterContext.Field.PARAMETER_PROJECT, project);
            values.put(NativeReadContext.Field.ROOT, project);
            values.put(NativeReadContext.Field.INITIALIZED, true);
            values.put(NativeReadContext.Field.CONTEXT_THREAD, true);
            values.put(NativeReadContext.Field.CURRENT_DT, dt);
            values.put(NativeReadContext.Field.DEFAULT_DT, dt);
            values.put(NativeReadContext.Field.DT_CONTEXT, context);
            values.put(GridParameterContext.Field.LICENSED, true);
            values.put(GridParameterContext.Field.SETTABLE, true);
            for (GridParameterContext.Field field : List.of(
                    GridParameterContext.Field.GUI_FLOAT,
                    GridParameterContext.Field.GUI_INTEGER,
                    GridParameterContext.Field.GUI_ENUM,
                    GridParameterContext.Field.GUI_BOOLEAN,
                    GridParameterContext.Field.UNDO,
                    GridParameterContext.Field.LOCK)) values.put(field, new Object());
            values.put(GridParameterContext.Field.LOCK_OWNER, null);
            // Native GUI-property getters allocate wrappers; their object identity must not be treated as stable.
            for (GridParameterContext.Field field : List.of(
                    GridParameterContext.Field.GUI_FLOAT,
                    GridParameterContext.Field.GUI_INTEGER,
                    GridParameterContext.Field.GUI_ENUM,
                    GridParameterContext.Field.GUI_BOOLEAN)) values.put(field, (Supplier<Object>) Object::new);
        }

        void override(Object object, Enum<?> field, Object value) {
            perObject.computeIfAbsent(object, ignored -> new HashMap<>()).put(field, value);
        }

        @Override
        public Object read(GridParameterContext.Field field, Object object) {
            return value(field, object);
        }

        @Override
        public Object read(NativeReadContext.Field field, Object object) {
            return value(field, object);
        }

        private Object value(Enum<?> field, Object object) {
            Map<Enum<?>, Object> specific = perObject.get(object);
            Object value = specific != null && specific.containsKey(field) ? specific.get(field) : values.get(field);
            return value instanceof Supplier<?> ? ((Supplier<?>) value).get() : value;
        }

        @Override
        public boolean defaultContext(Object value) {
            return defaultSupported;
        }
    }

    private static Node option(int value, String label) {
        return new Node(OPTION).put(OPTION_VALUE, value).put(OPTION_LABEL, label);
    }

    private static Node entry(String kind, String name, int id) {
        return new Node(ENTRY).put(ENTRY_KIND, kind).put(ENTRY_NAME, name).put(ENTRY_ID, new UUID(0, id));
    }

    private static boolean has(JsonObject result, String reason) {
        return result.getAsJsonArray("unsupported").contains(new JsonPrimitive(reason));
    }

    static final class Node {
        final Set<Enum<?>> kinds = new HashSet<>();
        final Map<Enum<?>, Object> values = new HashMap<>();
        final Set<Enum<?>> forbidden = new HashSet<>();
        boolean threadAllowed = true;

        Node(GridSnapshotReader.Kind... types) {
            kinds.addAll(Arrays.asList(types));
        }

        Node(NativeDeviceTarget.Kind type) {
            kinds.add(type);
        }

        Node put(GridSnapshotReader.Field field, Object value) {
            values.put(field, value);
            return this;
        }

        Node put(NativeDeviceTarget.Field field, Object value) {
            values.put(field, value);
            return this;
        }
    }

    static final class Fixture implements GridSnapshotReader.Access {
        final GridSnapshotReader reader = new GridSnapshotReader(this);
        final Node proxy = new Node(), device = new Node(DEVICE), graph = new Node();
        final Node owner = new Node(TRACK).put(TRACK_UUID, TRACK_ID);
        final Map<UUID, Node> types = new HashMap<>();
        final Set<GridSnapshotReader.Metadata> unavailable = EnumSet.noneOf(GridSnapshotReader.Metadata.class);
        final Map<Enum<?>, RpcException> failures = new HashMap<>();
        final Node first = module(0, graph), second = module(1, graph);
        final Node input = port(INPUT, first, "in"), output = port(OUTPUT, first, "out");
        List<?> catalog = List.of();
        boolean parameters = true, display = true, document = true;
        int closed, catalogReads, graphReads, targetReads, uuidReads;
        Runnable afterModules = () -> {};
        Runnable afterValue = () -> {};

        Fixture() {
            proxy.put(TARGET, device);
            device.put(GRAPH, graph);
            graph.put(MODULES, List.of(first, second));
            device.put(OWNER_TRACK, owner);
            first.put(INPUTS, List.of(input)).put(OUTPUTS, List.of(output));
            types.put(new UUID(2, 1), new Node(ENTRY).put(TYPE_NAME, "Catalog type"));
        }

        Node alias() {
            Node alias = new Node(CHANNEL_ALIAS).put(ALIAS_UUID, TRACK_ID);
            owner.kinds.add(ALIASED_TRACK);
            owner.put(TRACK_ALIAS, alias);
            return alias;
        }

        Node module(int index, Node owner) {
            return new Node(MODULE)
                    .put(INSTANCE_ID, new UUID(1, index + 1))
                    .put(LOCAL_KEY, Integer.toString(index))
                    .put(X, 7 + index)
                    .put(Y, 3)
                    .put(MODULE_GRAPH, owner)
                    .put(WIDTH, 2)
                    .put(HEIGHT, 1)
                    .put(MODULE_NAME, "Effective module")
                    .put(CUSTOM_NAME, "")
                    .put(DESCRIPTOR, new Node().put(TYPE_ID, new UUID(2, 1)))
                    .put(INPUTS, List.of())
                    .put(OUTPUTS, List.of())
                    .put(CHILDREN, List.of());
        }

        Node port(GridSnapshotReader.Kind type, Node owner, String key) {
            return new Node(type)
                    .put(OWNER_MODULE, owner)
                    .put(INPUT_KEY, key)
                    .put(OUTPUT_KEY, key)
                    .put(INPUT_NAME, "Input label")
                    .put(OUTPUT_NAME, "Output label")
                    .put(PATH, "module/" + key)
                    .put(INPUT_SOURCE, null);
        }

        Node parameter(GridSnapshotReader.Kind kind, Node owner) {
            return new Node(kind, PARAMETER)
                    .put(OWNER_MODULE, owner)
                    .put(PATH, "parameter/" + kind)
                    .put(DISPLAY, "native display")
                    .put(PARAMETER_ID, "LOCAL_ID")
                    .put(PARAMETER_NAME, "Native control")
                    .put(SHORT_NAME, "");
        }

        JsonObject read(int offset, int limit, boolean parameters) {
            return reader.snapshot(proxy, TRACK_ID, offset, limit, parameters, () -> {});
        }

        @Override
        public boolean is(NativeDeviceTarget.Kind kind, Object value) {
            return value instanceof Node && ((Node) value).kinds.contains(kind);
        }

        @Override
        public Object read(NativeDeviceTarget.Field field, Object object) {
            if (object instanceof Node && ((Node) object).forbidden.contains(field))
                throw new AssertionError("Forbidden native getter: " + field);
            if (failures.containsKey(field)) throw failures.get(field);
            if (field == TARGET) targetReads++;
            if (field == TRACK_UUID || field == ALIAS_UUID) uuidReads++;
            return ((Node) object).values.get(field);
        }

        @Override
        public Object read(GridSnapshotReader.Field field, Object object) {
            if (object instanceof Node && ((Node) object).forbidden.contains(field))
                throw new AssertionError("Forbidden native getter: " + field);
            if (failures.containsKey(field)) throw failures.get(field);
            if (field == CATALOG) {
                catalogReads++;
                return catalog;
            }
            if (field == TYPE_ENTRY) return types.get(object);
            if (field == GRAPH) graphReads++;
            Object value = ((Node) object).values.get(field);
            if (field == FLOAT_VALUE
                    || field == BOOLEAN_VALUE
                    || field == INTEGER_VALUE
                    || field == ENUM_VALUE
                    || field == STRING_VALUE) afterValue.run();
            if (field == MODULES || field == INPUTS || field == OUTPUTS || field == CHILDREN) {
                if (value instanceof Supplier<?>) return ((Supplier<?>) value).get();
                Stream<?> stream = ((List<?>) value).stream().onClose(() -> closed++);
                if (field == MODULES) stream = stream.onClose(afterModules);
                return stream;
            }
            return value;
        }

        @Override
        public boolean is(GridSnapshotReader.Kind kind, Object value) {
            return value instanceof Node && ((Node) value).kinds.contains(kind);
        }

        @Override
        public void documentThread() {
            if (!document) throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "wrong thread");
        }

        @Override
        public void requireModelThread(Object value) {
            if (!(value instanceof Node) || !((Node) value).threadAllowed)
                throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "wrong thread predicate");
        }

        @Override
        public boolean parametersAvailable() {
            return parameters;
        }

        @Override
        public boolean displayAvailable() {
            return display;
        }

        @Override
        public boolean metadataAvailable(GridSnapshotReader.Metadata metadata) {
            return !unavailable.contains(metadata);
        }
    }
}
