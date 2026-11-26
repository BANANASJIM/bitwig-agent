package dev.bitwigagent;

import static dev.bitwigagent.HostLimits.*;
import static dev.bitwigagent.Results.*;

import com.bitwig.extension.controller.api.*;
import com.google.gson.*;
import dev.bitwigagent.core.MethodSpecs;
import java.util.*;

/** Runtime discovery of implemented surfaces, bounded host windows and explicit exclusions. */
final class DiscoveryModule implements HostModule {
    static final Set<String> METHODS =
            Set.of("status", "tracks.list", "devices.list", "devices.layers.list", "capabilities");
    private static final BuildInfo BUILD = BuildInfo.load();
    private static final GridTopologyQuarantine GRID_HOLDS = new GridTopologyQuarantine();
    private final ControllerHost host;
    private final Application application;
    private final Transport transport;
    private final TrackWindow window;
    private final DeviceSources sources;
    private final ProjectState projectState;
    private final MethodSpecs specs;
    private final ParameterModule parameters;
    private final ProjectModule project;
    private final RenderModule render;
    private final Holds holds;

    DiscoveryModule(
            ControllerHost host,
            Application application,
            Transport transport,
            TrackWindow window,
            DeviceSources sources,
            ProjectState projectState,
            MethodSpecs specs,
            ParameterModule parameters,
            ProjectModule project,
            RenderModule render,
            Holds holds) {
        this.host = host;
        this.application = application;
        this.transport = transport;
        this.window = window;
        this.sources = sources;
        this.projectState = projectState;
        this.specs = specs;
        this.parameters = parameters;
        this.project = project;
        this.render = render;
        this.holds = holds;
    }

    @Override
    public Set<String> methods() {
        return METHODS;
    }

    @Override
    public void validate(String method, JsonObject params) {
        validateParams(method, params);
    }

    static void validateParams(String method, JsonObject params) {
        if (!METHODS.contains(method)) throw Errors.notApplied(Reason.UNKNOWN_METHOD, "Unknown discovery method.");
        if (method.equals("devices.layers.list")) {
            DeviceSources.validateLayer(params, false, true);
            return;
        }
        Params.fields(params, method.equals("devices.list") ? Set.of("trackId") : Set.of(), Set.of());
        if (method.equals("devices.list")) Params.string(params, "trackId");
    }

    @Override
    public void dispatch(HostOperation operation, String method, JsonObject params) {
        switch (method) {
            case "status":
                operation.result.complete(status());
                return;
            case "tracks.list":
                operation.result.complete(listTracks());
                return;
            case "devices.list":
                operation.result.complete(listDevices(params.get("trackId").getAsString()));
                return;
            case "devices.layers.list":
                operation.result.complete(sources.listLayers(params));
                return;
            case "capabilities":
                operation.result.complete(capabilities(operation));
                return;
            default:
                throw Errors.notApplied(Reason.UNKNOWN_METHOD, "Unsupported discovery method: " + method);
        }
    }

    private JsonObject status() {
        JsonObject result = new JsonObject();
        result.addProperty("hostVersion", host.getHostVersion());
        result.addProperty("projectName", application.projectName().get());
        result.addProperty("engineActive", application.hasActiveEngine().get());
        result.addProperty("playing", transport.isPlaying().get());
        for (Map.Entry<String, JsonElement> e :
                Results.readiness(projectState.readiness()).entrySet()) result.add(e.getKey(), e.getValue());
        result.add("projectModified", Results.modified(projectState.modified()));
        result.addProperty("apiVersion", 25);
        result.add("extension", BUILD.toJson());
        result.add("instance", Results.instance());
        result.addProperty("trackWindowCapacity", TRACKS);
        result.addProperty("deviceWindowCapacity", DEVICES);
        result.add("parameterBindingDiagnostics", parameters.diagnostics());
        result.add("holds", holds(holds, GRID_HOLDS));
        return result;
    }

    /** Every hold: the id ack takes, whether ack accepts it, what is held; Grid devices from the process marker. */
    static JsonArray holds(Holds holds, GridTopologyQuarantine grid) {
        JsonArray out = new JsonArray();
        for (Holds.View hold : holds.list()) {
            JsonObject row = new JsonObject();
            row.addProperty("id", hold.id);
            row.addProperty("ackable", hold.ackable);
            row.addProperty("description", hold.description);
            out.add(row);
        }
        for (JsonObject device : grid.quarantinedDevices()) {
            JsonObject row = new JsonObject();
            row.addProperty(
                    "id", GridTopologyQuarantine.holdId(device.get("device").getAsString()));
            row.addProperty("ackable", true);
            row.addProperty(
                    "description",
                    "A Grid topology edit on a device of track "
                            + device.get("trackId").getAsString()
                            + " has an unknown outcome; ack re-verifies the graph first.");
            out.add(row);
        }
        return out;
    }

    private JsonObject capabilities(HostOperation operation) {
        JsonObject result = new JsonObject();
        result.addProperty("scope", "implemented_toolkit_interfaces");
        result.addProperty("apiVersion", 25);
        result.addProperty("hostVersion", host.getHostVersion());
        result.add("extension", BUILD.toJson());
        JsonArray methods = new JsonArray();
        JsonObject domains = new JsonObject();
        for (String method : specs.methods()) {
            methods.add(method);
            String domain = method.contains(".") ? method.substring(0, method.indexOf('.')) : "discovery";
            if (!domains.has(domain)) domains.add(domain, new JsonArray());
            domains.getAsJsonArray(domain).add(method);
        }
        result.add("methods", methods);
        result.add("domains", domains);
        JsonObject windows = new JsonObject();
        windows.add("tracks", window(window.offset(), TRACKS, window.total()));
        windows.addProperty("devicesPerTrack", DEVICES);
        windows.addProperty("launcherSlotsPerTrack", SCENES);
        windows.addProperty("sendsPerTrack", SENDS);
        windows.addProperty("trackScope", "flat_track_bank");
        windows.addProperty("deviceScope", "top_level");
        windows.addProperty("layersPerRootDevice", DeviceSources.LAYERS);
        windows.addProperty("devicesPerLayer", DeviceSources.CHILDREN);
        windows.addProperty(
                "layerScope", "one_layer_hop_fixed_zero_offset_no_pads_slots_deeper_or_grid_private_targeting");
        windows.addProperty(
                "layerPhysicalRebindPolicy",
                "direct_access_fail_closed_until_extension_reload_no_callback_provenance_boundary");
        windows.addProperty("indexBase", 0);
        result.add("windows", windows);
        JsonObject units = new JsonObject();
        units.addProperty("normalizedValues", "0_to_1");
        units.addProperty("directParameterResolution", RESOLUTION);
        units.addProperty("transportTempo", "beats_per_minute");
        units.addProperty("transportPosition", "quarter_note_beats");
        units.addProperty("clipLength", "quarter_note_beats");
        units.addProperty("noteDuration", "quarter_note_beats");
        units.addProperty("noteOnset", "absolute_quarter_note_beats_on_explicit_step_grid");
        units.addProperty("noteWindowPosition", "steps_at_explicit_stepSizeBeats");
        units.addProperty("noteVelocity", "midi_1_to_127_integer_same_unit_as_notes.edit");
        units.addProperty("notePitch", "midi_0_to_127");
        units.addProperty("gridParameterValues", "native_raw_typed_values_not_normalized_or_display_units");
        units.addProperty("gridModulePosition", "native_absolute_anchor_grid_coordinates_not_pixels");
        units.addProperty("gridModuleFootprint", "native_primitive_width_height_in_grid_coordinates");
        result.add("units", units);
        result.addProperty("parameterIdentityScope", "observed_bank_proxy_context_not_persistent_device_identity");
        result.addProperty("writeSemantics", "absolute_or_explicit_command_no_automatic_retries");
        result.addProperty("completionSemantics", "dispatched_is_not_completed_read_verification_fields");
        JsonObject grid = new JsonObject();
        grid.addProperty(
                "scope",
                "version_gated_private_grid_reads_guarded_raw_parameter_module_and_single_input_connection_writes");
        grid.addProperty("capabilitiesMethod", "grid.capabilities");
        grid.addProperty(
                "reason",
                "Query grid.capabilities for independent read/parameterWrite/moduleInsert/moduleMove/moduleDelete/moduleRename/connectionWrite bindings, read-only adapter Class identity and process topology lifetime guard, not native acceptance.");
        JsonArray reads = new JsonArray();
        for (String method : List.of("grid.catalog", "grid.get", "grid.parameters.get", "grid.voicing.get"))
            reads.add(method);
        grid.add("readMethods", reads);
        JsonArray writes = new JsonArray();
        for (String method : List.of(
                "grid.parameters.set",
                "grid.voicing.set",
                "devices.remote.define",
                "devices.remote.deletePage",
                "grid.modules.insert",
                "grid.modules.move",
                "grid.modules.delete",
                "grid.modules.rename",
                "grid.connections.set")) writes.add(method);
        grid.add("writeMethods", writes);
        grid.addProperty(
                "graphWriteAvailability",
                "query_grid.capabilities_moduleInsert_moduleMove_moduleDelete_moduleRename_and_connectionWrite");
        grid.addProperty("graphWriteScope", "module_insert_move_delete_rename_and_single_input_connection_set");
        grid.addProperty("parameterWriteAvailability", "query_grid.capabilities_parameterWrite");
        grid.addProperty("nativeUndoAvailable", false);
        grid.addProperty(
                "nativeUndoScope",
                "native_parameter_and_topology_operations_not_an_undo_command_or_grouping_guarantee");
        result.add("privateGridTopology", grid);
        JsonObject modulators = new JsonObject();
        modulators.addProperty("scope", "primary_modulators_on_root_device");
        modulators.addProperty("capabilitiesMethod", "modulators.capabilities");
        modulators.addProperty(
                "readAvailability", "query_modulators.capabilities_independent_discovery_and_string_panel_bindings");
        modulators.addProperty("writeAvailability", "query_modulators.capabilities_edit");
        modulators.addProperty("nativeVerification", "not_established_by_binding");
        modulators.addProperty(
                "coverage",
                "primary_modulators_and_their_mappings_on_one_root_device_excludes_secondary_nested_modulators_and_materialization");
        modulators.addProperty("pagination", "independent_observations_not_revision_consistent");
        modulators.addProperty(
                "edits",
                "modulators.get/insert/delete/parameters.set/map/unmap; availability in modulators.capabilities edit");
        result.add("privateModulatorReads", modulators);
        result.add("projectSaveAs", project.saveAsCapability(operation));
        result.add("render", render.capability(operation));
        result.addProperty(
                "evidence",
                "Runtime implementation discovery only; not per-method host verification or full interface coverage.");
        return result;
    }

    private JsonObject listTracks() {
        if (!window.observed()) throw Errors.notApplied(Reason.NOT_READY, "Track bank has not reported its size yet.");
        JsonObject result = new JsonObject();
        JsonArray items = new JsonArray();
        TrackTree tree = TrackTree.capture(window);
        for (TrackTree.Node node : tree.nodes) {
            Track track = window.at(node.slot);
            JsonObject item = new JsonObject();
            item.addProperty("trackId", node.id);
            item.addProperty("index", node.position);
            item.addProperty("name", track.name().get());
            tree.describe(item, node);
            items.add(item);
        }
        result.add("tracks", items);
        result.add("tree", tree.summary());
        result.add("window", window(window.offset(), TRACKS, window.total()));
        result.addProperty("scope", "flat_track_bank");
        return result;
    }

    private JsonObject listDevices(String trackId) {
        int trackSlot = window.trackSlot(trackId);
        DeviceBank bank = window.devices(trackSlot);
        JsonArray items = new JsonArray();
        for (int i = 0; i < DEVICES; i++) {
            Device device = (Device) bank.getItemAt(i);
            if (!device.exists().get()) continue;
            JsonObject item = new JsonObject();
            item.addProperty("deviceIndex", device.position().get());
            item.addProperty("name", device.name().get());
            item.addProperty("type", device.deviceType().get());
            items.add(item);
        }
        JsonObject result = new JsonObject();
        result.addProperty("trackId", trackId);
        result.add("devices", items);
        result.add(
                "window",
                window(bank.scrollPosition().get(), DEVICES, bank.itemCount().get()));
        result.addProperty("scope", "top_level");
        return result;
    }
}
