package dev.bitwigagent.nativehost;

import static dev.bitwigagent.grid.GridSnapshotReader.Field.*;
import static dev.bitwigagent.nativehost.NativeHost.diagnostic;

import com.google.gson.JsonObject;
import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.grid.GridAdapter;
import dev.bitwigagent.grid.GridBooleanPanelWriter;
import dev.bitwigagent.grid.GridConnectionWriter;
import dev.bitwigagent.grid.GridInsertionWriter;
import dev.bitwigagent.grid.GridModuleNameWriter;
import dev.bitwigagent.grid.GridModuleWriter;
import dev.bitwigagent.grid.GridParameterContext;
import dev.bitwigagent.grid.GridParameterWriter;
import dev.bitwigagent.grid.GridRemotePageWriter;
import dev.bitwigagent.grid.GridSnapshotReader;
import dev.bitwigagent.grid.GridStepArrayWriter;
import dev.bitwigagent.grid.GridStringPanelWriter;
import dev.bitwigagent.grid.GridTopologyContext;
import dev.bitwigagent.grid.GridTopologyQuarantine;
import dev.bitwigagent.grid.GridVoicingWriter;
import dev.bitwigagent.host.DeviceSources;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Stream;

/** Exact symbol map for one inspected binary; read and write capabilities fail independently. */
public final class NativeGridAdapter implements GridAdapter, GridSnapshotReader.Access {
    // Process String marker survives extension classloader replacement; no native objects enter Properties.
    private static final GridTopologyQuarantine TOPOLOGY_QUARANTINE = new GridTopologyQuarantine();
    private final EnumMap<GridSnapshotReader.Field, Method> readers = new EnumMap<>(GridSnapshotReader.Field.class);
    private final EnumMap<GridSnapshotReader.Kind, Class<?>> kinds = new EnumMap<>(GridSnapshotReader.Kind.class);
    private final EnumMap<GridSnapshotReader.Metadata, String> metadataErrors =
            new EnumMap<>(GridSnapshotReader.Metadata.class);
    private final GridSnapshotReader snapshots = new GridSnapshotReader(this);
    private final NativeHost.Access bindings;
    private final String hostVersion, actualHash, expectedHash, gateError;
    private String catalogError, identityError, structureError, parameterError, displayError;
    private NativeDeviceTargetAccess deviceTargets;
    private GridParameterContext parameterContexts;
    private String inspectionError, writeError;
    private GridParameterWriter parameterWriter;
    private NativeGridBooleanPanelAccess booleanPanels;
    private String booleanPanelError;
    private NativeStringPanelAccess stringPanels;
    private String stringPanelError;
    private NativeStepArrayAccess stepArrays;
    private String stepArrayError;
    private NativeVoicingAccess voicingAccess;
    private String voicingError;
    private GridVoicingWriter voicingWriter;
    private NativeRemotePageAccess remotePageAccess;
    private String remotePageError;
    private GridRemotePageWriter remotePageWriter;
    private String moduleMoveError, moduleDeleteError;
    private GridModuleWriter moduleWriter;
    private String connectionError;
    private GridConnectionWriter connectionWriter;
    private String moduleInsertError;
    private GridInsertionWriter insertionWriter;
    private NativeGridInsertionAccess insertionAccess;
    private String moduleRenameError;
    private GridModuleNameWriter moduleNameWriter;

    public NativeGridAdapter(NativeHost.Access bindings) {
        this.bindings = bindings;
        hostVersion = bindings.hostVersion();
        actualHash = bindings.actualHash();
        expectedHash = bindings.expectedHash();
        gateError = bindings.gateError();
        if (gateError != null) return;
        try {
            bindCatalog();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            catalogError = diagnostic(error);
        }
        try {
            deviceTargets = new NativeDeviceTargetAccess(bindings);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            identityError = diagnostic(error);
        }
        if (identityError != null)
            structureError = "Required native owning-track identity binding unavailable: " + identityError;
        else
            try {
                bindStructure();
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                structureError = diagnostic(error);
            }
        if (structureError == null) {
            try {
                bindParameters();
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                parameterError = diagnostic(error);
            }
            try {
                booleanPanels = new NativeGridBooleanPanelAccess(bindings);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                booleanPanelError = diagnostic(error);
            }
            try {
                stringPanels = new NativeStringPanelAccess(bindings);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                stringPanelError = diagnostic(error);
            }
            try {
                stepArrays = new NativeStepArrayAccess(bindings);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                stepArrayError = diagnostic(error);
            }
            try {
                voicingAccess = new NativeVoicingAccess(bindings);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                voicingError = diagnostic(error);
            }
            try {
                bind(DISPLAY, "parameter.controller.display", String.class);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                displayError = diagnostic(error);
            }
        } else {
            parameterError = "Structure binding unavailable.";
            displayError = parameterError;
            booleanPanelError = parameterError;
            stringPanelError = parameterError;
            stepArrayError = parameterError;
            voicingError = parameterError;
        }
        for (GridSnapshotReader.Metadata metadata : GridSnapshotReader.METADATA) {
            if (structureError != null) {
                metadataErrors.put(metadata, "Structure binding unavailable.");
                continue;
            }
            try {
                bindMetadata(metadata);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                metadataErrors.put(metadata, diagnostic(error));
            }
        }
        if (structureError != null || parameterError != null)
            inspectionError = "Required structure or typed parameter binding unavailable.";
        else
            try {
                kind(GridSnapshotReader.Kind.PARAMETER, "parameter.identified");
                bind(PARAMETER_ID, "parameter.identified.id", String.class);
                parameterContexts = new GridParameterContext(new NativeGridParameterContext(
                        bindings, booleanPanels, stringPanels, stepArrays, voicingAccess));
                // Voicing reads depend only on inspection bindings, never on write-side ones.
                if (voicingAccess != null)
                    voicingWriter = new GridVoicingWriter(
                            snapshots, parameterContexts, voicingAccess, voicingAccess.setterError() == null);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                inspectionError = diagnostic(error);
            }
        // Remote Controls pages: own bindings, plus parameter binding/contexts and module names; never a read
        // dependency.
        if (inspectionError != null) remotePageError = "Required parameter inspection bindings unavailable.";
        else if (metadataErrors.containsKey(GridSnapshotReader.Metadata.MODULE_NAMES))
            remotePageError = "Required native module name readers unavailable.";
        else
            try {
                remotePageAccess = new NativeRemotePageAccess(bindings);
                remotePageWriter = new GridRemotePageWriter(snapshots, parameterContexts, remotePageAccess);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                remotePageError = diagnostic(error);
            }
        if (inspectionError != null) writeError = "Required parameter inspection bindings unavailable.";
        else
            try {
                GridBooleanPanelWriter panels = booleanPanels == null
                        ? null
                        : new GridBooleanPanelWriter(snapshots, parameterContexts, booleanPanels);
                GridStringPanelWriter strings = stringPanels == null || stringPanels.setterError() != null
                        ? null
                        : new GridStringPanelWriter(snapshots, parameterContexts, stringPanels);
                GridStepArrayWriter steps = stepArrays == null || stepArrays.setterError() != null
                        ? null
                        : new GridStepArrayWriter(snapshots, parameterContexts, stepArrays);
                parameterWriter = new GridParameterWriter(
                        snapshots, parameterContexts, new NativeGridParameterWriter(bindings), panels, strings, steps);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                writeError = diagnostic(error);
            }
        GridTopologyContext topologyContexts = null;
        String topologyContextError = structureError == null ? null : "Required structure binding unavailable.";
        if (topologyContextError == null) {
            try {
                topologyContexts = new GridTopologyContext(new NativeGridTopologyContext(bindings));
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                topologyContextError = diagnostic(error);
            }
        }
        if (topologyContextError != null || metadataErrors.containsKey(GridSnapshotReader.Metadata.MODULE_NAMES))
            moduleRenameError = topologyContextError != null
                    ? topologyContextError
                    : "Required native module name readers unavailable.";
        else
            try {
                moduleNameWriter = new GridModuleNameWriter(
                        snapshots,
                        this,
                        topologyContexts,
                        new NativeGridModuleNameAccess(bindings),
                        TOPOLOGY_QUARANTINE);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                moduleRenameError = diagnostic(error);
            }
        if (topologyContextError != null || metadataErrors.containsKey(GridSnapshotReader.Metadata.FOOTPRINT)) {
            moduleMoveError = moduleDeleteError = topologyContextError != null
                    ? topologyContextError
                    : "Required native footprint binding unavailable.";
        } else
            try {
                NativeGridModuleAccess topology = new NativeGridModuleAccess(bindings);
                moduleMoveError = topology.moveError;
                moduleDeleteError = topology.deleteError;
                moduleWriter = new GridModuleWriter(snapshots, this, topologyContexts, topology, TOPOLOGY_QUARANTINE);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                moduleMoveError = moduleDeleteError = diagnostic(error);
            }
        if (topologyContextError != null) connectionError = topologyContextError;
        else
            try {
                connectionWriter = new GridConnectionWriter(
                        snapshots,
                        this,
                        topologyContexts,
                        new NativeGridConnectionAccess(bindings),
                        TOPOLOGY_QUARANTINE);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                connectionError = diagnostic(error);
            }
        if (topologyContextError != null
                || catalogError != null
                || metadataErrors.containsKey(GridSnapshotReader.Metadata.FOOTPRINT))
            moduleInsertError = "Required insertion structure/catalog/context/footprint bindings unavailable.";
        else
            try {
                insertionAccess = new NativeGridInsertionAccess(bindings);
                insertionWriter = new GridInsertionWriter(
                        snapshots, this, topologyContexts, insertionAccess, TOPOLOGY_QUARANTINE);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                moduleInsertError = diagnostic(error);
            }
    }

    private void bindCatalog() throws ReflectiveOperationException {
        kind(GridSnapshotReader.Kind.ENTRY, "catalog.entry");
        bindStatic(CATALOG, "catalog.entries", List.class);
        bind(ENTRY_ID, "catalog.entry.id", UUID.class);
        bind(ENTRY_NAME, "catalog.entry.name", String.class);
        bind(ENTRY_KIND, "catalog.entry.kind", bindings.type("catalog.kind"));
    }

    private void bindStructure() throws ReflectiveOperationException {
        bindings.requireModelThreadAvailable();
        kind(GridSnapshotReader.Kind.MODULE, "grid.module");
        kind(GridSnapshotReader.Kind.INPUT, "grid.input");
        Class<?> output = kind(GridSnapshotReader.Kind.OUTPUT, "grid.output");
        Class<?> graph = bindings.type("grid.patchGraph"), descriptor = bindings.type("grid.moduleDescriptor");
        bind(GRAPH, "device.grid", graph);
        bind(MODULES, "grid.patchGraph.modules", Stream.class);
        bind(INSTANCE_ID, "grid.module.id", UUID.class);
        bind(LOCAL_KEY, "grid.module.key", String.class);
        bind(X, "grid.module.x", int.class);
        bind(Y, "grid.module.y", int.class);
        bind(DESCRIPTOR, "grid.module.descriptor", descriptor);
        bind(TYPE_ID, "grid.moduleDescriptor.typeId", UUID.class);
        bind(INPUTS, "grid.module.inputs", Stream.class);
        bind(OUTPUTS, "grid.module.outputs", Stream.class);
        bind(MODULE_GRAPH, "grid.module.graph", graph);
        readers.put(OWNER_MODULE, bindings.exactMethod("model.object.ancestor", Object.class, false, Class.class));
        bind(INPUT_SOURCE, "grid.input.source", output);
        bind(INPUT_KEY, "grid.input.key", String.class);
        bind(OUTPUT_KEY, "grid.output.key", String.class);
        bind(PATH, "model.node.path", String.class);
    }

    private void bindParameters() throws ReflectiveOperationException {
        bind(CHILDREN, "grid.module.children", Stream.class);
        kind(GridSnapshotReader.Kind.FLOAT, "parameter.float");
        kind(GridSnapshotReader.Kind.BOOLEAN, "parameter.boolean");
        kind(GridSnapshotReader.Kind.INTEGER, "parameter.integer");
        kind(GridSnapshotReader.Kind.ENUM, "parameter.enum");
        kind(GridSnapshotReader.Kind.OPTION, "parameter.option");
        bind(FLOAT_VALUE, "parameter.float.value", double.class);
        bind(FLOAT_DEFINITION, "parameter.float.definition", bindings.type("parameter.floatDefinition"));
        bind(FLOAT_MIN, "parameter.floatDefinition.min", double.class);
        bind(FLOAT_MAX, "parameter.floatDefinition.max", double.class);
        bind(BOOLEAN_VALUE, "parameter.boolean.value", boolean.class);
        bind(INTEGER_VALUE, "parameter.integer.value", int.class);
        bind(INTEGER_DEFINITION, "parameter.integer.definition", bindings.type("parameter.integerDefinition"));
        bind(INTEGER_MIN, "parameter.integerDefinition.min", int.class);
        bind(INTEGER_MAX, "parameter.integerDefinition.max", int.class);
        bind(ENUM_VALUE, "parameter.enum.value", int.class);
        bind(ENUM_DEFINITION, "parameter.enum.definition", bindings.type("parameter.enumDefinition"));
        bind(OPTIONS, "parameter.enumDefinition.options", List.class);
        bind(OPTION_VALUE, "parameter.option.value", int.class);
        bind(OPTION_LABEL, "parameter.option.label", String.class);
    }

    private void bindMetadata(GridSnapshotReader.Metadata metadata) throws ReflectiveOperationException {
        switch (metadata) {
            case FOOTPRINT:
                bind(WIDTH, "grid.footprint.width", int.class);
                bind(HEIGHT, "grid.footprint.height", int.class);
                break;
            case MODULE_NAMES:
                bind(MODULE_NAME, "device.named.name", String.class);
                bind(CUSTOM_NAME, "device.named.customName", String.class);
                break;
            case TYPE_NAMES:
                readers.put(
                        TYPE_ENTRY,
                        bindings.exactMethod("catalog.entryOf", bindings.type("catalog.entry"), true, UUID.class));
                bind(TYPE_NAME, "catalog.entry.name", String.class);
                break;
            case PARAMETER_NAMES:
                bind(PARAMETER_ID, "parameter.identified.id", String.class);
                bind(PARAMETER_NAME, "parameter.identified.name", String.class);
                bind(SHORT_NAME, "parameter.identified.shortName", String.class);
                break;
            case PORT_NAMES:
                bind(INPUT_NAME, "grid.inputBase.name", String.class);
                bind(OUTPUT_NAME, "grid.outputBase.name", String.class);
                break;
            case FLOAT_UNITS:
                bind(FLOAT_UNIT, "parameter.unitOwner.unit", bindings.type("parameter.unit"));
                bind(UNIT_TOKEN, "parameter.unit.token", String.class);
                break;
            case INTEGER_AFFIXES:
                bind(INTEGER_PREFIX, "parameter.integerFormat.prefix", String.class);
                bind(INTEGER_SUFFIX, "parameter.integerFormat.suffix", String.class);
                break;
            default:
                throw new AssertionError(metadata);
        }
    }

    @Override
    public JsonObject capabilities() {
        JsonObject result = new JsonObject();
        result.addProperty("hostVersion", hostVersion);
        result.addProperty("requiredHostVersion", NativeHost.SUPPORTED);
        result.addProperty("loadedHostJarSha256", actualHash);
        result.addProperty("requiredHostJarSha256", expectedHash);
        result.addProperty("gatePassed", gateError == null);
        result.addProperty("available", gateError == null && (catalogError == null || structureError == null));
        result.addProperty("binding", gateError == null ? "exact_descriptors_resolved" : "unavailable");
        result.addProperty("runtimeVerification", "not_established_by_binding");
        result.add("catalog", capability(gateError == null ? catalogError : gateError, "native_MODULE_entries"));
        result.add(
                "structure",
                capability(
                        gateError == null ? structureError : gateError,
                        "modules_ports_incoming_same_graph_connections"));
        result.add(
                "nativeOwningTrack",
                capability(
                        gateError == null ? identityError : gateError,
                        "required_device_track_ancestor_channel_uuid_with_track_alias_before_and_after_capture"));
        result.add(
                "typedParameters",
                capability(
                        gateError == null ? parameterError : gateError,
                        "float_boolean_integer_enum_known_children_only"));
        result.add(
                "display",
                capability(gateError == null ? displayError : gateError, "native_reader_semantics_unverified"));
        result.add(
                "parameterInspection",
                capability(
                        gateError == null ? inspectionError : gateError,
                        "single_exact_parameter_and_same_callback_native_context_observations_only"));
        JsonObject write = capability(
                gateError == null ? writeError : gateError,
                "guarded_raw_controller_float_bipolar_integer_enum_boolean_derivedBoolean_observed_stopped_only");
        write.addProperty("operation", "parameter.controller.setRaw(double):void/set_raw_from_controller");
        write.addProperty("nativeUndoAwareOperation", true);
        write.addProperty("undoGroupingVerified", false);
        write.addProperty("undoCommandAvailable", false);
        write.addProperty("engineAcknowledgement", false);
        result.add("parameterWrite", write);
        JsonObject panels = capability(
                gateError == null ? booleanPanelError : gateError,
                "exact_panel.boolean_no_scalar_label_display_or_touch_license_flags");
        panels.addProperty(
                "inspectionAvailable", gateError == null && booleanPanelError == null && inspectionError == null);
        panels.addProperty("writeAvailable", gateError == null && booleanPanelError == null && writeError == null);
        panels.addProperty("operation", "panel.boolean.set(boolean):void/native_boolean_panel_change");
        panels.addProperty(
                "coupling", "one_same_module_exact_parameter.bipolar_reference_required_for_inspection_and_write");
        panels.addProperty(
                "guardPolicy", "panel_common_context_plus_linked_scalar_license_touch_context_not_claimed_GUI_policy");
        panels.addProperty("nativeUndoAwareOperation", true);
        panels.addProperty("undoGroupingVerified", false);
        panels.addProperty("engineAcknowledgement", false);
        result.add("booleanPanels", panels);
        JsonObject strings = capability(
                gateError == null ? stringPanelError : gateError,
                "exact_panel.string_native_string_panel_no_scalar_labels_display_or_touch_license_flags");
        strings.addProperty(
                "inspectionAvailable", gateError == null && stringPanelError == null && inspectionError == null);
        String stringWriteError = gateError != null
                ? gateError
                : stringPanelError != null
                        ? stringPanelError
                        : stringPanels.setterError() != null ? stringPanels.setterError() : writeError;
        strings.addProperty("writeAvailable", stringWriteError == null);
        if (stringWriteError != null) strings.addProperty("writeReason", stringWriteError);
        strings.addProperty("operation", "panel.string.set(String):void/native_string_panel_change");
        strings.addProperty("nativeUndoAwareOperation", true);
        strings.addProperty("undoGroupingVerified", false);
        strings.addProperty("engineAcknowledgement", false);
        result.add("stringPanels", strings);
        JsonObject steps = capability(
                gateError == null ? stepArrayError : gateError,
                "exact_steps.array_float_array_five_modules_Steps_Pitches_Gates_Probabilities_Accents");
        steps.addProperty(
                "inspectionAvailable", gateError == null && stepArrayError == null && inspectionError == null);
        String stepsWriteError = gateError != null
                ? gateError
                : stepArrayError != null
                        ? stepArrayError
                        : stepArrays.setterError() != null ? stepArrays.setterError() : writeError;
        steps.addProperty("writeAvailable", stepsWriteError == null);
        if (stepsWriteError != null) steps.addProperty("writeReason", stepsWriteError);
        steps.addProperty("operation", "steps.array.set(float[]):void/native_recorded_array_change");
        steps.addProperty("valueDomain", "finite_sanity_bound_not_a_module_range");
        steps.addProperty("nativeUndoAwareOperation", true);
        steps.addProperty("undoGroupingVerified", false);
        steps.addProperty("engineAcknowledgement", false);
        result.add("stepArrays", steps);
        JsonObject voicing = capability(
                gateError == null ? voicingError : gateError,
                "device.voicing_poly_grid_only_note_and_fx_grid_have_no_voicing_model");
        voicing.addProperty(
                "inspectionAvailable", gateError == null && voicingError == null && inspectionError == null);
        String voicingWriteError = gateError != null
                ? gateError
                : voicingError != null
                        ? voicingError
                        : voicingAccess.setterError() != null ? voicingAccess.setterError() : writeError;
        voicing.addProperty("writeAvailable", voicingWriteError == null);
        if (voicingWriteError != null) voicing.addProperty("writeReason", voicingWriteError);
        voicing.addProperty("operation", "voicing.base_final_recorded_setters_per_field");
        voicing.add("fields", new com.google.gson.Gson().toJsonTree(GridVoicingWriter.FIELDS));
        voicing.addProperty("nativeUndoAwareOperation", true);
        voicing.addProperty("undoGroupingVerified", false);
        voicing.addProperty("engineAcknowledgement", false);
        result.add("voicing", voicing);
        JsonObject remotePages = capability(
                gateError == null ? remotePageError : gateError,
                "one_named_user_preset_page_per_call_8_slots_scalar_grid_parameters_device_module_plugin_pages_read_only");
        remotePages.addProperty("methods", "devices.remote.define,devices.remote.delete");
        remotePages.addProperty(
                "operation",
                "create_page+set_page_name(String)+map_slot(int,String,property)+clear_slot(int)+delete_page");
        remotePages.addProperty(
                "pageList", "preset_pages_as_UI_Add_New_Page_device_pages_refuse_grid_module_parameters");
        remotePages.addProperty("nativeUndoAwareOperation", true);
        remotePages.addProperty("undoGroupingVerified", false);
        remotePages.addProperty("engineAcknowledgement", false);
        result.add("remotePages", remotePages);
        for (GridSnapshotReader.Metadata metadata : GridSnapshotReader.METADATA)
            result.add(
                    metadata.key,
                    capability(gateError == null ? metadataErrors.get(metadata) : gateError, metadata.coverage));
        String quarantine = TOPOLOGY_QUARANTINE.blockedReason();
        result.add(
                "moduleMove",
                topologyCapability(
                        gateError != null ? gateError : quarantine != null ? quarantine : moduleMoveError,
                        "public_same_graph_absolute_anchor_singleton_no_copy_native_reflow_later_event_observation"));
        result.add(
                "moduleDelete",
                topologyCapability(
                        gateError != null ? gateError : quarantine != null ? quarantine : moduleDeleteError,
                        "public_selective_singleton_delete_native_bypass_reconnection_later_event_observation"));
        JsonObject rename = topologyCapability(
                gateError != null ? gateError : quarantine != null ? quarantine : moduleRenameError,
                "current_UUID_raw_customName_CAS_public_final_property_setter_later_actual_graph_observation");
        rename.remove("maximumOccupiedYSpan");
        rename.addProperty("operation", "device.named.setCustomName(String):void");
        rename.addProperty("bindingAvailable", gateError == null && moduleRenameError == null);
        rename.addProperty("nameBound", GridModuleNameWriter.MAX_NAME);
        rename.addProperty("nameBoundUnits", "UTF16_code_units");
        rename.addProperty(
                "nativeUndoScope", "native_property_bookkeeping_not_verified_UI_transaction_grouping_or_commit");
        rename.addProperty("runtimeVerification", "not_established_by_binding");
        result.add("moduleRename", rename);
        JsonObject connection = topologyCapability(
                gateError != null ? gateError : quarantine != null ? quarantine : connectionError,
                "single_input_nullable_source_CAS_public_undo_wrapper_later_actual_graph_observation");
        connection.remove("maximumOccupiedYSpan");
        connection.addProperty("operation", "grid.inputBase.connect(grid.connectionSource):void");
        connection.addProperty("destinationRuntimeGate", "exact_grid.destinationInput_only_no_subclasses");
        connection.addProperty(
                "undoRuntimeGate",
                "exact_project.undoManager_only_no_subclasses_native_validation_not_atomic_permission");
        connection.addProperty(
                "projectScope",
                "native_repair_can_change_other_inputs_and_project_routing_not_certified_by_Grid_snapshot");
        result.add("connectionWrite", connection);
        JsonObject insertion = topologyCapability(
                gateError != null ? gateError : quarantine != null ? quarantine : moduleInsertError,
                "known_MODULE_resource_normal_loader_detached_singleton_absolute_anchor_later_native_settlement_and_actual_graph");
        insertion.addProperty(
                "operation",
                "project.load(String[],grid.loadPolicy)_then_grid.patchGraph.place(Collection,int,int,false,null)");
        insertion.addProperty(
                "loaderDispatchedScope", "native_parsing_conversion_not_pure_read_timeout_is_not_cancellation");
        insertion.addProperty("placementDispatchedScope", "collection_placement_invocation_not_atomic_commit");
        insertion.addProperty("runtimeVerification", "not_established_by_read_only_binding_inspection");
        insertion.addProperty("bindingAvailable", gateError == null && moduleInsertError == null);
        if (insertionAccess != null) insertion.add("bindingIdentity", insertionAccess.evidence());
        result.add("moduleInsert", insertion);
        result.addProperty(
                "graphWrites",
                gateError == null
                        && quarantine == null
                        && (moduleMoveError == null
                                || moduleDeleteError == null
                                || moduleRenameError == null
                                || connectionError == null
                                || moduleInsertError == null));
        result.addProperty("graphWriteScope", "module_insert_move_delete_rename_and_single_input_connection_set");
        result.addProperty("topologyQuarantined", TOPOLOGY_QUARANTINE.quarantined());
        result.addProperty("topologyAdmissionBlocked", quarantine != null);
        result.add("topologyQuarantine", quarantineState(TOPOLOGY_QUARANTINE));
        result.addProperty(
                "topologyQuarantineScope",
                "versioned_String_only_process_marker_shared_across_extension_classloaders_one_record_per_device_released_only_by_ack");
        result.addProperty("nativeRevisionAvailable", false);
        return result;
    }
    /** state idle|pending|quarantined, one entry per quarantined device (origin, trackId, detail, next) and what to do. */
    public static JsonObject quarantineState(GridTopologyQuarantine guard) {
        JsonObject state = new JsonObject();
        com.google.gson.JsonArray devices = new com.google.gson.JsonArray();
        for (JsonObject device : guard.quarantinedDevices()) {
            device.addProperty(
                    "id", GridTopologyQuarantine.holdId(device.get("device").getAsString()));
            device.addProperty(
                    "next", "grid.export this device, then ack its id: ack re-verifies the graph and releases it");
            devices.add(device);
        }
        boolean pending = guard.pending();
        state.addProperty("state", pending ? "pending" : devices.size() == 0 ? "idle" : "quarantined");
        state.add("devices", devices);
        if (pending)
            state.addProperty("next", "wait for the native callback; a missing one needs a full Bitwig restart");
        else if (devices.size() > 0)
            state.addProperty(
                    "next", "only the listed devices are blocked; other devices and project.save keep working");
        return state;
    }

    private static JsonObject capability(String error, String coverage) {
        JsonObject result = new JsonObject();
        result.addProperty("available", error == null);
        result.addProperty("coverage", error == null ? coverage : "unavailable");
        result.addProperty("reason", error);
        return result;
    }

    private static JsonObject topologyCapability(String error, String coverage) {
        JsonObject result = capability(error, coverage);
        result.addProperty("maximumModules", GridModuleWriter.MAX_MODULES);
        result.addProperty("maximumOccupiedYSpan", GridModuleWriter.MAX_Y_SPAN);
        result.addProperty("nativeUndoAwareOperation", true);
        result.addProperty("undoGroupingVerified", false);
        result.addProperty("engineAcknowledgement", false);
        return result;
    }

    @Override
    public void execute(Runnable read) {
        bindings.execute(read);
    }

    @Override
    public JsonObject catalog(String query, int offset, int limit, Runnable check) {
        require(gateError, "gate");
        require(catalogError, "catalog");
        return snapshots.catalog(query, offset, limit, check);
    }

    @Override
    public JsonObject snapshot(
            DeviceSources.Source source,
            int offset,
            int limit,
            boolean parameters,
            boolean diagnostics,
            java.util.Set<String> moduleIds,
            boolean fullSteps,
            Runnable check) {
        require(gateError, "gate");
        require(identityError, "native owning-track identity");
        require(structureError, "structure");
        return snapshots.snapshot(source, offset, limit, parameters, diagnostics, moduleIds, fullSteps, check);
    }

    /** A float Grid module parameter's native object (a parameter.float, so a parameter) for its automation lane; document thread only. */
    GridSnapshotReader.ParameterBinding automationParameter(
            DeviceSources.Source source, UUID moduleId, String parameterId, Runnable check) {
        require(gateError, "gate");
        require(identityError, "native owning-track identity");
        require(structureError, "structure");
        require(parameterError, "typed parameters");
        require(inspectionError, "parameter inspection context");
        GridSnapshotReader.ParameterBinding binding = snapshots.bindParameter(source, moduleId, parameterId, check);
        if (binding.kind != GridSnapshotReader.Kind.FLOAT)
            throw Errors.notApplied(
                    Reason.UNSUPPORTED_SCOPE,
                    "Only float Grid parameters are automated here; this one is "
                            + binding.kind.name().toLowerCase(Locale.ROOT) + ".");
        return binding;
    }

    void guardAutomationParameter(GridSnapshotReader.ParameterBinding binding) {
        snapshots.guardParameter(binding);
    }

    @Override
    public JsonObject voicing(DeviceSources.Source source, Runnable check) {
        require(gateError, "gate");
        require(identityError, "native owning-track identity");
        require(structureError, "structure");
        require(inspectionError, "parameter inspection context");
        require(voicingError, "voicing");
        if (voicingWriter == null)
            throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "Grid voicing unavailable: no voicing binding.");
        return voicingWriter.get(source, check);
    }

    @Override
    public JsonObject setVoicing(
            DeviceSources.Source source, GridVoicingWriter.Request request, Runnable check, Runnable dispatch) {
        require(gateError, "gate");
        require(writeError, "parameter write");
        require(voicingError, "voicing");
        return voicingWriter.set(source, request, check, dispatch);
    }

    @Override
    public JsonObject defineRemotePage(
            DeviceSources.Source source, GridRemotePageWriter.Request request, Runnable check, Runnable dispatch) {
        require(gateError, "gate");
        require(remotePageError, "Remote Controls pages");
        return remotePageWriter.define(source, request, check, dispatch);
    }

    @Override
    public JsonObject deleteRemotePage(
            DeviceSources.Source source, JsonObject page, Runnable check, Runnable dispatch) {
        require(gateError, "gate");
        require(remotePageError, "Remote Controls pages");
        return remotePageWriter.deletePage(source, page, check, dispatch);
    }

    @Override
    public JsonObject setParameter(
            DeviceSources.Source source,
            UUID moduleId,
            String parameterId,
            GridParameterWriter.Request request,
            Runnable check,
            Runnable dispatch) {
        require(gateError, "gate");
        require(writeError, "parameter write");
        return parameterWriter.set(source, moduleId, parameterId, request, check, dispatch);
    }

    @Override
    public JsonObject setParameters(
            DeviceSources.Source source,
            java.util.List<GridParameterWriter.Entry> writes,
            Runnable check,
            Runnable dispatch) {
        require(gateError, "gate");
        require(writeError, "parameter write");
        return parameterWriter.setAll(source, writes, check, dispatch);
    }

    @Override
    public void moveModule(
            DeviceSources.Source source,
            UUID moduleId,
            GridModuleWriter.Move request,
            Runnable check,
            Runnable dispatch,
            Consumer<JsonObject> complete,
            Consumer<Throwable> fail) {
        require(gateError, "gate");
        require(moduleMoveError, "module move");
        moduleWriter.move(source, moduleId, request, check, dispatch, complete, fail);
    }

    @Override
    public void deleteModule(
            DeviceSources.Source source,
            UUID moduleId,
            Runnable check,
            Runnable dispatch,
            Consumer<JsonObject> complete,
            Consumer<Throwable> fail) {
        require(gateError, "gate");
        require(moduleDeleteError, "module delete");
        moduleWriter.delete(source, moduleId, check, dispatch, complete, fail);
    }

    @Override
    public void renameModule(
            DeviceSources.Source source,
            UUID moduleId,
            GridModuleNameWriter.Request request,
            Runnable check,
            Runnable dispatch,
            Consumer<JsonObject> complete,
            Consumer<Throwable> fail) {
        require(gateError, "gate");
        require(moduleRenameError, "module rename");
        moduleNameWriter.rename(source, moduleId, request, check, dispatch, complete, fail);
    }

    @Override
    public void setConnection(
            DeviceSources.Source source,
            GridConnectionWriter.Request request,
            Runnable check,
            Runnable dispatch,
            Consumer<JsonObject> complete,
            Consumer<Throwable> fail) {
        require(gateError, "gate");
        require(connectionError, "connection write");
        connectionWriter.set(source, request, check, dispatch, complete, fail);
    }

    @Override
    public void setConnections(
            DeviceSources.Source source,
            java.util.List<GridConnectionWriter.Request> requests,
            Runnable check,
            Runnable dispatch,
            Consumer<JsonObject> complete,
            Consumer<Throwable> fail) {
        require(gateError, "gate");
        require(connectionError, "connection write");
        connectionWriter.setAll(source, requests, check, dispatch, complete, fail);
    }

    @Override
    public void insertModule(
            DeviceSources.Source source,
            GridInsertionWriter.Request request,
            Runnable check,
            Runnable dispatch,
            Consumer<JsonObject> complete,
            Consumer<Throwable> fail) {
        require(gateError, "gate");
        require(moduleInsertError, "module insertion");
        insertionWriter.insert(source, request, check, dispatch, complete, fail);
    }

    @Override
    public void insertModules(
            DeviceSources.Source source,
            java.util.List<GridInsertionWriter.Request> requests,
            java.util.function.LongSupplier timeLeft,
            Runnable check,
            Runnable dispatch,
            Consumer<JsonObject> complete,
            Consumer<Throwable> fail) {
        require(gateError, "gate");
        require(moduleInsertError, "module insertion");
        insertionWriter.insertAll(source, requests, timeLeft, check, dispatch, complete, fail);
    }

    @Override
    public JsonObject ackTopology(DeviceSources.Source source, String device, Runnable check) {
        require(gateError, "gate");
        require(identityError, "native owning-track identity");
        require(structureError, "structure");
        require(moduleInsertError, "module insertion");
        return insertionWriter.ack(source, device, check);
    }

    @Override
    public boolean parametersAvailable() {
        return gateError == null && parameterError == null;
    }

    @Override
    public boolean displayAvailable() {
        return gateError == null && displayError == null;
    }

    @Override
    public boolean metadataAvailable(GridSnapshotReader.Metadata metadata) {
        return gateError == null && !metadataErrors.containsKey(metadata);
    }

    @Override
    public boolean is(GridSnapshotReader.Kind kind, Object object) {
        if (kind == GridSnapshotReader.Kind.PANEL_BOOLEAN)
            return booleanPanels != null && booleanPanels.isPanel(object);
        if (kind == GridSnapshotReader.Kind.PANEL_STRING) return stringPanels != null && stringPanels.isPanel(object);
        if (kind == GridSnapshotReader.Kind.STEP_ARRAY) return stepArrays != null && stepArrays.isModel(object);
        if (kind == GridSnapshotReader.Kind.BIPOLAR_FLOAT)
            return booleanPanels != null && booleanPanels.isBipolar(object);
        Class<?> type = kinds.get(kind);
        return type != null && type.isInstance(object);
    }

    @Override
    public Object read(GridSnapshotReader.Field field, Object object) {
        if (field == BIPOLAR_PANEL) {
            if (booleanPanels == null)
                throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "Boolean panel reference binding unavailable.");
            return booleanPanels.linkedPanel(object);
        }
        if (booleanPanels != null && booleanPanels.isPanel(object)) {
            if (field == PARAMETER_ID) return booleanPanels.identifier(object);
            if (field == BOOLEAN_VALUE) return booleanPanels.value(object);
        }
        if (stepArrays != null && stepArrays.isModel(object)) {
            if (field == PARAMETER_ID) return stepArrays.identifier(object);
            if (field == STEP_VALUES) return stepArrays.data(object);
            if (field == STEP_LENGTH) return stepArrays.length(object);
        }
        if (stringPanels != null && stringPanels.isPanel(object)) {
            if (field == PARAMETER_ID) return stringPanels.identifier(object);
            if (field == STRING_VALUE) return stringPanels.value(object);
        }
        Method method = readers.get(field);
        if (method == null) throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "Unbound native reader: " + field);
        Object value;
        if (field == OWNER_MODULE) value = bindings.invoke(method, object, kinds.get(GridSnapshotReader.Kind.MODULE));
        else if (field == TYPE_ENTRY) value = bindings.invoke(method, null, object);
        else value = bindings.invoke(method, object);
        if (field == ENTRY_KIND) {
            if (!(value instanceof Enum<?>))
                throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "Native catalog kind is not the mapped enum.");
            return ((Enum<?>) value).name();
        }
        return value;
    }

    @Override
    public void documentThread() {
        bindings.documentThread();
    }

    @Override
    public void requireModelThread(Object object) {
        bindings.requireModelThread(object);
    }

    @Override
    public Object read(DeviceSources.Native.Field field, Object object) {
        return deviceTargets.read(field, object);
    }

    @Override
    public boolean is(DeviceSources.Native.Kind kind, Object object) {
        return deviceTargets.is(kind, object);
    }

    private Class<?> kind(GridSnapshotReader.Kind kind, String key) throws ClassNotFoundException {
        Class<?> type = bindings.type(key);
        kinds.put(kind, type);
        return type;
    }

    private void bind(GridSnapshotReader.Field field, String key, Class<?> returns)
            throws ReflectiveOperationException {
        readers.put(field, bindings.exactMethod(key, returns, false));
    }

    private void bindStatic(GridSnapshotReader.Field field, String key, Class<?> returns)
            throws ReflectiveOperationException {
        readers.put(field, bindings.exactMethod(key, returns, true));
    }

    private static void require(String error, String domain) {
        if (error != null)
            throw Errors.notApplied(
                    Reason.NATIVE_UNAVAILABLE,
                    "Grid " + domain + " unavailable: " + error
                            + " Inspect status with detail full; use the exact supported host binary and adapter.");
    }
}
