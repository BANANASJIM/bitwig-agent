package dev.bitwigagent;

import static dev.bitwigagent.Bitwig614Runtime.SHA256;
import static dev.bitwigagent.Bitwig614Runtime.VERSION;
import static dev.bitwigagent.Bitwig614Runtime.diagnostic;
import static dev.bitwigagent.GridSnapshotReader.Field.*;

import com.google.gson.JsonObject;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Stream;

/** Exact symbol map for one inspected binary; read and write capabilities fail independently. */
final class Bitwig614GridAdapter implements GridAdapter, GridSnapshotReader.Access {
    private static final String D = "com.bitwig.flt.document.core.master.device.";
    private static final String R = "com.bitwig.ramona.core.";
    private static final String P = "com.bitwig.flt.packaging.core.";
    // Process String marker survives extension classloader replacement; no native objects enter Properties.
    private static final GridTopologyQuarantine TOPOLOGY_QUARANTINE = new GridTopologyQuarantine();
    private final EnumMap<GridSnapshotReader.Field, Method> readers = new EnumMap<>(GridSnapshotReader.Field.class);
    private final EnumMap<GridSnapshotReader.Kind, Class<?>> kinds = new EnumMap<>(GridSnapshotReader.Kind.class);
    private final EnumMap<GridSnapshotReader.Metadata, String> metadataErrors =
            new EnumMap<>(GridSnapshotReader.Metadata.class);
    private final GridSnapshotReader snapshots = new GridSnapshotReader(this);
    private final Bitwig614Runtime.Access bindings;
    private final String hostVersion, actualHash, gateError;
    private String catalogError, identityError, structureError, parameterError, displayError;
    private Bitwig614DeviceTargetAccess deviceTargets;
    private GridParameterContext parameterContexts;
    private String inspectionError, writeError;
    private GridParameterWriter parameterWriter;
    private Bitwig614GridBooleanPanelAccess booleanPanels;
    private String booleanPanelError;
    private Bitwig614StringPanelAccess stringPanels;
    private String stringPanelError;
    private Bitwig614StepArrayAccess stepArrays;
    private String stepArrayError;
    private Bitwig614VoicingAccess voicingAccess;
    private String voicingError;
    private GridVoicingWriter voicingWriter;
    private Bitwig614RemotePageAccess remotePageAccess;
    private String remotePageError;
    private GridRemotePageWriter remotePageWriter;
    private String moduleMoveError, moduleDeleteError;
    private GridModuleWriter moduleWriter;
    private String connectionError;
    private GridConnectionWriter connectionWriter;
    private String moduleInsertError;
    private GridInsertionWriter insertionWriter;
    private Bitwig614GridInsertionAccess insertionAccess;
    private String moduleRenameError;
    private GridModuleNameWriter moduleNameWriter;

    Bitwig614GridAdapter(Bitwig614Runtime.Access bindings) {
        this.bindings = bindings;
        hostVersion = bindings.hostVersion();
        actualHash = bindings.actualHash();
        gateError = bindings.gateError();
        if (gateError != null) return;
        try {
            bindCatalog();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            catalogError = diagnostic(error);
        }
        try {
            deviceTargets = new Bitwig614DeviceTargetAccess(bindings);
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
                booleanPanels = new Bitwig614GridBooleanPanelAccess(bindings);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                booleanPanelError = diagnostic(error);
            }
            try {
                stringPanels = new Bitwig614StringPanelAccess(bindings);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                stringPanelError = diagnostic(error);
            }
            try {
                stepArrays = new Bitwig614StepArrayAccess(bindings);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                stepArrayError = diagnostic(error);
            }
            try {
                voicingAccess = new Bitwig614VoicingAccess(bindings);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                voicingError = diagnostic(error);
            }
            try {
                bind(DISPLAY, bindings.type("aLw"), "qFI", String.class);
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
                Class<?> parameter = kind(GridSnapshotReader.Kind.PARAMETER, "rBL");
                bind(PARAMETER_ID, parameter, "aA_", String.class);
                parameterContexts = new GridParameterContext(new Bitwig614GridParameterContext(
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
                remotePageAccess = new Bitwig614RemotePageAccess(bindings);
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
                        snapshots,
                        parameterContexts,
                        new Bitwig614GridParameterWriter(bindings),
                        panels,
                        strings,
                        steps);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                writeError = diagnostic(error);
            }
        GridTopologyContext topologyContexts = null;
        String topologyContextError = structureError == null ? null : "Required structure binding unavailable.";
        if (topologyContextError == null) {
            try {
                topologyContexts = new GridTopologyContext(new Bitwig614GridTopologyContext(bindings));
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
                        new Bitwig614GridModuleNameAccess(bindings),
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
                Bitwig614GridModuleAccess topology = new Bitwig614GridModuleAccess(bindings);
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
                        new Bitwig614GridConnectionAccess(bindings),
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
                insertionAccess = new Bitwig614GridInsertionAccess(bindings);
                insertionWriter = new GridInsertionWriter(
                        snapshots, this, topologyContexts, insertionAccess, TOPOLOGY_QUARANTINE);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                moduleInsertError = diagnostic(error);
            }
    }

    private void bindCatalog() throws ReflectiveOperationException {
        Class<?> entry = kind(GridSnapshotReader.Kind.ENTRY, P + "CuM");
        bindStatic(CATALOG, bindings.type(P + "Wkj"), "cfM", List.class);
        bind(ENTRY_ID, entry, "cfM", UUID.class);
        bind(ENTRY_NAME, entry, "MRY", String.class);
        bind(ENTRY_KIND, entry, "Hnt", bindings.type(P + "WzZ"));
    }

    private void bindStructure() throws ReflectiveOperationException {
        bindings.requireModelThreadAvailable();
        Class<?> device = bindings.type(D + "xlG");
        Class<?> module = kind(GridSnapshotReader.Kind.MODULE, D + "rSf");
        Class<?> input = kind(GridSnapshotReader.Kind.INPUT, D + "PSD");
        Class<?> output = kind(GridSnapshotReader.Kind.OUTPUT, D + "xOa");
        Class<?> graph = bindings.type(D + "IcG"), descriptor = bindings.type("com.bitwig.flt.device.Jkg");
        bind(GRAPH, device, "zmH", graph);
        bind(MODULES, graph, "cj_", Stream.class);
        bind(INSTANCE_ID, module, "kFU", UUID.class);
        bind(LOCAL_KEY, module, "aA_", String.class);
        bind(X, module, "FzB", int.class);
        bind(Y, module, "UNj", int.class);
        bind(DESCRIPTOR, module, "Pgx", descriptor);
        bind(TYPE_ID, descriptor, "Hnt", UUID.class);
        bind(INPUTS, module, "BBT", Stream.class);
        bind(OUTPUTS, module, "Cuu", Stream.class);
        bind(MODULE_GRAPH, module, "sut", graph);
        readers.put(
                OWNER_MODULE, bindings.exactMethod(bindings.type(R + "AVL"), "IwV", Object.class, false, Class.class));
        bind(INPUT_SOURCE, input, "Scv", output);
        bind(INPUT_KEY, input, "aA_", String.class);
        bind(OUTPUT_KEY, output, "aA_", String.class);
        bind(PATH, bindings.type(R + "oDc"), "veO", String.class);
    }

    private void bindParameters() throws ReflectiveOperationException {
        bind(CHILDREN, bindings.type(D + "rSf"), "tB1", Stream.class);
        Class<?> floating = kind(GridSnapshotReader.Kind.FLOAT, "Opy");
        Class<?> bool = kind(GridSnapshotReader.Kind.BOOLEAN, "Qe");
        Class<?> integer = kind(GridSnapshotReader.Kind.INTEGER, "Igl");
        Class<?> indexed = kind(GridSnapshotReader.Kind.ENUM, "RSJ");
        Class<?> option = kind(GridSnapshotReader.Kind.OPTION, "aQ");
        Class<?> floatDefinition = bindings.type("aL"),
                integerDefinition = bindings.type("bd"),
                enumDefinition = bindings.type("aX");
        bind(FLOAT_VALUE, floating, "hUR", double.class);
        bind(FLOAT_DEFINITION, floating, "jHi", floatDefinition);
        bind(FLOAT_MIN, floatDefinition, "FzB", double.class);
        bind(FLOAT_MAX, floatDefinition, "UNj", double.class);
        bind(BOOLEAN_VALUE, bool, "hUR", boolean.class);
        bind(INTEGER_VALUE, integer, "kIh", int.class);
        bind(INTEGER_DEFINITION, integer, "jHi", integerDefinition);
        bind(INTEGER_MIN, integerDefinition, "UNj", int.class);
        bind(INTEGER_MAX, integerDefinition, "TMF", int.class);
        bind(ENUM_VALUE, indexed, "UNj", int.class);
        bind(ENUM_DEFINITION, indexed, "DNo", enumDefinition);
        bind(OPTIONS, enumDefinition, "TMF", List.class);
        bind(OPTION_VALUE, option, "cfM", int.class);
        bind(OPTION_LABEL, option, "IwV", String.class);
    }

    private void bindMetadata(GridSnapshotReader.Metadata metadata) throws ReflectiveOperationException {
        switch (metadata) {
            case FOOTPRINT:
                bind(WIDTH, bindings.type(D + "CEE"), "HL", int.class);
                bind(HEIGHT, bindings.type(D + "CEE"), "E1b", int.class);
                break;
            case MODULE_NAMES: {
                Class<?> module = bindings.type(D + "Dgc");
                bind(MODULE_NAME, module, "vpw", String.class);
                bind(CUSTOM_NAME, module, "Ai", String.class);
                break;
            }
            case TYPE_NAMES: {
                Class<?> entry = bindings.type(P + "CuM");
                readers.put(TYPE_ENTRY, bindings.exactMethod(bindings.type(P + "Wkj"), "MRY", entry, true, UUID.class));
                bind(TYPE_NAME, entry, "MRY", String.class);
                break;
            }
            case PARAMETER_NAMES: {
                Class<?> parameter = bindings.type("rBL");
                bind(PARAMETER_ID, parameter, "aA_", String.class);
                bind(PARAMETER_NAME, parameter, "BDm", String.class);
                bind(SHORT_NAME, parameter, "SL2", String.class);
                break;
            }
            case PORT_NAMES:
                bind(INPUT_NAME, bindings.type(D + "cXR"), "cfM", String.class);
                bind(OUTPUT_NAME, bindings.type(D + "YSk"), "emh", String.class);
                break;
            case FLOAT_UNITS: {
                Class<?> unit = bindings.type("fX");
                bind(FLOAT_UNIT, bindings.type("aN"), "ERO", unit);
                bind(UNIT_TOKEN, unit, "MRY", String.class);
                break;
            }
            case INTEGER_AFFIXES: {
                Class<?> definition = bindings.type("bh");
                bind(INTEGER_PREFIX, definition, "E3", String.class);
                bind(INTEGER_SUFFIX, definition, "wGc", String.class);
                break;
            }
            default:
                throw new AssertionError(metadata);
        }
    }

    @Override
    public JsonObject capabilities() {
        JsonObject result = new JsonObject();
        result.addProperty("hostVersion", hostVersion);
        result.addProperty("requiredHostVersion", VERSION);
        result.addProperty("loadedHostJarSha256", actualHash);
        result.addProperty("requiredHostJarSha256", SHA256);
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
                        "required_device_track_ancestor_channel_uuid_with_kWY_alias_before_and_after_capture"));
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
                "guarded_raw_controller_rce_Hgx_iS3_Lig_RuU_nhW_observed_stopped_only");
        write.addProperty("operation", "aLw.UNj(double):void/set_raw_from_controller");
        write.addProperty("nativeUndoAwareOperation", true);
        write.addProperty("undoGroupingVerified", false);
        write.addProperty("undoCommandAvailable", false);
        write.addProperty("engineAcknowledgement", false);
        result.add("parameterWrite", write);
        JsonObject panels = capability(
                gateError == null ? booleanPanelError : gateError,
                "exact_BMo_boolean_panel_no_scalar_label_display_or_touch_license_flags");
        panels.addProperty(
                "inspectionAvailable", gateError == null && booleanPanelError == null && inspectionError == null);
        panels.addProperty("writeAvailable", gateError == null && booleanPanelError == null && writeError == null);
        panels.addProperty("operation", "xqx.MRY(boolean):void/native_boolean_panel_change");
        panels.addProperty("coupling", "one_same_module_exact_Hgx_reference_required_for_inspection_and_write");
        panels.addProperty(
                "guardPolicy", "panel_common_context_plus_linked_scalar_license_touch_context_not_claimed_GUI_policy");
        panels.addProperty("nativeUndoAwareOperation", true);
        panels.addProperty("undoGroupingVerified", false);
        panels.addProperty("engineAcknowledgement", false);
        result.add("booleanPanels", panels);
        JsonObject strings = capability(
                gateError == null ? stringPanelError : gateError,
                "exact_xMn_native_string_panel_no_scalar_labels_display_or_touch_license_flags");
        strings.addProperty(
                "inspectionAvailable", gateError == null && stringPanelError == null && inspectionError == null);
        String stringWriteError = gateError != null
                ? gateError
                : stringPanelError != null
                        ? stringPanelError
                        : stringPanels.setterError() != null ? stringPanels.setterError() : writeError;
        strings.addProperty("writeAvailable", stringWriteError == null);
        if (stringWriteError != null) strings.addProperty("writeReason", stringWriteError);
        strings.addProperty("operation", "btw.hUR(String):void/native_string_panel_change");
        strings.addProperty("nativeUndoAwareOperation", true);
        strings.addProperty("undoGroupingVerified", false);
        strings.addProperty("engineAcknowledgement", false);
        result.add("stringPanels", strings);
        JsonObject steps = capability(
                gateError == null ? stepArrayError : gateError,
                "exact_imU_FDM_float_array_five_modules_Steps_Pitches_Gates_Probabilities_Accents");
        steps.addProperty(
                "inspectionAvailable", gateError == null && stepArrayError == null && inspectionError == null);
        String stepsWriteError = gateError != null
                ? gateError
                : stepArrayError != null
                        ? stepArrayError
                        : stepArrays.setterError() != null ? stepArrays.setterError() : writeError;
        steps.addProperty("writeAvailable", stepsWriteError == null);
        if (stepsWriteError != null) steps.addProperty("writeReason", stepsWriteError);
        steps.addProperty("operation", "FDM.FzB(float[]):void/native_recorded_array_change");
        steps.addProperty("valueDomain", "finite_sanity_bound_not_a_module_range");
        steps.addProperty("nativeUndoAwareOperation", true);
        steps.addProperty("undoGroupingVerified", false);
        steps.addProperty("engineAcknowledgement", false);
        result.add("stepArrays", steps);
        JsonObject voicing = capability(
                gateError == null ? voicingError : gateError,
                "xlG_Mb2_jBG_SOn_poly_grid_only_note_and_fx_grid_have_no_voicing_model");
        voicing.addProperty(
                "inspectionAvailable", gateError == null && voicingError == null && inspectionError == null);
        String voicingWriteError = gateError != null
                ? gateError
                : voicingError != null
                        ? voicingError
                        : voicingAccess.setterError() != null ? voicingAccess.setterError() : writeError;
        voicing.addProperty("writeAvailable", voicingWriteError == null);
        if (voicingWriteError != null) voicing.addProperty("writeReason", voicingWriteError);
        voicing.addProperty("operation", "SOn_final_recorded_setters_per_field");
        voicing.add("fields", new com.google.gson.Gson().toJsonTree(GridVoicingWriter.FIELDS));
        voicing.addProperty("nativeUndoAwareOperation", true);
        voicing.addProperty("undoGroupingVerified", false);
        voicing.addProperty("engineAcknowledgement", false);
        result.add("voicing", voicing);
        JsonObject remotePages = capability(
                gateError == null ? remotePageError : gateError,
                "one_named_user_preset_page_per_call_8_slots_scalar_grid_parameters_device_module_plugin_pages_read_only");
        remotePages.addProperty("methods", "devices.remote.define,devices.remote.deletePage");
        remotePages.addProperty(
                "operation",
                "riz.OzW_create+faW.cfM(String)_name+vNq.cfM(int,String,SYo)_map+vNq.MRY(int)_clear+riz.cfM(FyM)_delete");
        remotePages.addProperty(
                "pageList", "preset_pages_LQR.BNq_as_UI_Add_New_Page_device_pages_refuse_grid_module_parameters");
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
        rename.addProperty("operation", "com.bitwig.flt.document.core.master.device.Dgc.iHb(String):void");
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
        connection.addProperty("operation", "cXR.cfM(led):void");
        connection.addProperty(
                "destinationRuntimeGate", "exact_com.bitwig.flt.document.core.master.device.Wkj_only_no_subclasses");
        connection.addProperty(
                "undoRuntimeGate",
                "exact_com.bitwig.flt.document.core.master.tj2_only_no_subclasses_native_validation_not_atomic_permission");
        connection.addProperty(
                "projectScope",
                "native_repair_can_change_other_inputs_and_project_routing_not_certified_by_Grid_snapshot");
        result.add("connectionWrite", connection);
        JsonObject insertion = topologyCapability(
                gateError != null ? gateError : quarantine != null ? quarantine : moduleInsertError,
                "known_MODULE_resource_normal_loader_detached_singleton_absolute_anchor_later_native_settlement_and_actual_graph");
        insertion.addProperty("operation", "g2L.cfM(String[],ZuD)_then_IcG.cfM(Collection,int,int,false,null)");
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
    static JsonObject quarantineState(GridTopologyQuarantine guard) {
        JsonObject state = new JsonObject();
        com.google.gson.JsonArray devices = new com.google.gson.JsonArray();
        for (JsonObject device : guard.quarantinedDevices()) {
            device.addProperty(
                    "id", GridTopologyQuarantine.holdId(device.get("device").getAsString()));
            device.addProperty(
                    "next", "grid.get this device, then ack its id: ack re-verifies the graph and releases it");
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

    @Override
    public JsonObject parameter(
            DeviceSources.Source source, UUID moduleId, String parameterId, boolean fullSteps, Runnable check) {
        require(gateError, "gate");
        require(identityError, "native owning-track identity");
        require(structureError, "structure");
        require(parameterError, "typed parameters");
        require(inspectionError, "parameter inspection context");
        return snapshots.parameter(source, moduleId, parameterId, parameterContexts, fullSteps, check);
    }
    /** A float Grid module parameter's native object (an Opy, so a bDH) for its automation lane; document thread only. */
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
            DeviceSources.Source source, String pageName, Runnable check, Runnable dispatch) {
        require(gateError, "gate");
        require(remotePageError, "Remote Controls pages");
        return remotePageWriter.deletePage(source, pageName, check, dispatch);
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

    private Class<?> kind(GridSnapshotReader.Kind kind, String name) throws ClassNotFoundException {
        Class<?> type = bindings.type(name);
        kinds.put(kind, type);
        return type;
    }

    private void bind(GridSnapshotReader.Field field, Class<?> owner, String name, Class<?> returns)
            throws ReflectiveOperationException {
        readers.put(field, bindings.exactMethod(owner, name, returns, false));
    }

    private void bindStatic(GridSnapshotReader.Field field, Class<?> owner, String name, Class<?> returns)
            throws ReflectiveOperationException {
        readers.put(field, bindings.exactMethod(owner, name, returns, true));
    }

    private static void require(String error, String domain) {
        if (error != null)
            throw Errors.notApplied(
                    Reason.NATIVE_UNAVAILABLE,
                    "Grid " + domain + " unavailable: " + error
                            + " Inspect grid.capabilities; use the exact supported host binary and adapter.");
    }
}
