package dev.bitwigagent;

import com.bitwig.extension.controller.api.*;
import com.google.gson.*;
import java.util.*;
import static dev.bitwigagent.HostServices.*;

/** Runtime discovery of implemented surfaces, bounded host windows and explicit exclusions. */
final class DiscoveryModule implements HostModule {
    static final Set<String> METHODS = Set.of("status", "tracks.list", "devices.list", "capabilities");
    private final HostServices s;
    private final ParameterModule parameters;
    DiscoveryModule(HostServices s, ParameterModule parameters) { this.s = s; this.parameters = parameters; }
    @Override public Set<String> methods() { return METHODS; }
    @Override public void validate(String method, JsonObject params) { validateParams(method, params); }
    static void validateParams(String method, JsonObject params) {
        if (!METHODS.contains(method)) throw failure("unknown_method", "Unknown discovery method.");
        Params.fields(params, method.equals("devices.list") ? Set.of("trackId") : Set.of(), Set.of());
        if (method.equals("devices.list")) Params.string(params, "trackId");
    }
    @Override public void dispatch(HostOperation operation, String method, JsonObject params) {
        switch (method) {
            case "status": operation.result.complete(status()); return;
            case "tracks.list": operation.result.complete(listTracks()); return;
            case "devices.list": operation.result.complete(listDevices(params.get("trackId").getAsString())); return;
            case "capabilities": operation.result.complete(capabilities()); return;
            default: throw failure("unknown_method", "Unsupported discovery method: " + method);
        }
    }

    private JsonObject status() {
        JsonObject result = observed();
        result.addProperty("hostVersion", s.host.getHostVersion());
        result.addProperty("projectName", s.application.projectName().get());
        result.addProperty("engineActive", s.application.hasActiveEngine().get());
        result.addProperty("playing", s.transport.isPlaying().get());
        result.addProperty("apiVersion", 25);
        result.addProperty("trackWindowCapacity", TRACKS);
        result.addProperty("deviceWindowCapacity", DEVICES);
        result.add("parameterBindingDiagnostics", parameters.diagnostics());
        return result;
    }

    private JsonObject capabilities() {
        JsonObject result = observed();
        result.addProperty("scope", "implemented_toolkit_interfaces");
        result.addProperty("apiVersion", 25);
        result.addProperty("hostVersion", s.host.getHostVersion());
        JsonArray methods = new JsonArray();
        JsonObject domains = new JsonObject();
        for (String method : HostBridge.supportedMethods()) {
            methods.add(method);
            String domain = method.contains(".") ? method.substring(0, method.indexOf('.')) : "discovery";
            if (!domains.has(domain)) domains.add(domain, new JsonArray());
            domains.getAsJsonArray(domain).add(method);
        }
        result.add("methods", methods);
        result.add("domains", domains);
        JsonObject windows = new JsonObject();
        windows.add("tracks", window(s.tracks.scrollPosition().get(), TRACKS, s.tracks.itemCount().get()));
        windows.addProperty("devicesPerTrack", DEVICES);
        windows.addProperty("launcherSlotsPerTrack", SCENES);
        windows.addProperty("sendsPerTrack", SENDS);
        windows.addProperty("trackScope", "flat_track_bank");
        windows.addProperty("deviceScope", "top_level");
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
        units.addProperty("noteVelocity", "normalized_0_to_1");
        units.addProperty("notePitch", "midi_0_to_127");
        units.addProperty("gridParameterValues", "native_raw_typed_values_not_normalized_or_display_units");
        units.addProperty("gridModulePosition", "native_absolute_anchor_grid_coordinates_not_pixels");
        units.addProperty("gridModuleFootprint", "native_primitive_width_height_in_grid_coordinates");
        result.add("units", units);
        result.addProperty("parameterIdentityScope", "observed_bank_proxy_context_not_persistent_device_identity");
        result.addProperty("writeSemantics", "absolute_or_explicit_command_no_automatic_retries");
        result.addProperty("completionSemantics", "dispatched_is_not_completed_read_verification_fields");
        JsonObject grid = new JsonObject();
        grid.addProperty("scope", "version_gated_private_grid_reads_guarded_raw_parameter_module_and_single_input_connection_writes");
        grid.addProperty("capabilitiesMethod", "grid.capabilities");
        grid.addProperty("reason", "Query grid.capabilities for independent read/parameterWrite/moduleInsert/moduleMove/moduleDelete/connectionWrite bindings, read-only adapter Class identity and process topology lifetime guard, not native acceptance.");
        JsonArray reads = new JsonArray();
        for (String method : List.of("grid.catalog", "grid.get", "grid.parameters.get")) reads.add(method);
        grid.add("readMethods", reads);
        JsonArray writes = new JsonArray();
        for (String method : List.of("grid.parameters.set", "grid.modules.insert", "grid.modules.move", "grid.modules.delete", "grid.connections.set")) writes.add(method);
        grid.add("writeMethods", writes);
        grid.addProperty("graphWriteAvailability", "query_grid.capabilities_moduleInsert_moduleMove_moduleDelete_and_connectionWrite");
        grid.addProperty("graphWriteScope", "module_insert_move_delete_and_single_input_connection_set");
        grid.addProperty("parameterWriteAvailability", "query_grid.capabilities_parameterWrite");
        grid.addProperty("nativeUndoAvailable", false);
        grid.addProperty("nativeUndoScope", "native_parameter_and_topology_operations_not_an_undo_command_or_grouping_guarantee");
        result.add("privateGridTopology", grid);
        result.addProperty("evidence", "Runtime implementation discovery only; not per-method host verification or full interface coverage.");
        return result;
    }

    private JsonObject listTracks() {
        if (!s.tracksObserved) throw failure("not_ready", "Track bank has not reported its size yet.");
        JsonObject result = observed();
        JsonArray items = new JsonArray();
        for (int i = 0; i < TRACKS; i++) {
            Track track = (Track) s.tracks.getItemAt(i);
            if (!track.exists().get()) continue;
            JsonObject item = new JsonObject();
            item.addProperty("trackId", track.channelId().get());
            item.addProperty("index", track.position().get());
            item.addProperty("name", track.name().get());
            items.add(item);
        }
        result.add("tracks", items);
        result.add("window", window(s.tracks.scrollPosition().get(), TRACKS, s.tracks.itemCount().get()));
        result.addProperty("scope", "flat_track_bank");
        return result;
    }

    private JsonObject listDevices(String trackId) {
        int trackSlot = s.trackSlot(trackId);
        DeviceBank bank = s.devices[trackSlot];
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
        JsonObject result = observed();
        result.addProperty("trackId", trackId);
        result.add("devices", items);
        result.add("window", window(bank.scrollPosition().get(), DEVICES, bank.itemCount().get()));
        result.addProperty("scope", "top_level");
        return result;
    }
}
