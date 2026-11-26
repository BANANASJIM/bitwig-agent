package dev.bitwigagent;

import static dev.bitwigagent.HostLimits.*;
import static dev.bitwigagent.Results.*;

import com.bitwig.extension.controller.api.*;
import com.google.gson.*;
import dev.bitwigagent.core.MethodSpecs;
import java.util.*;

/** status: the brief project state, and with detail full the implemented surface plus each domain's section. */
final class DiscoveryModule implements HostModule {
    static final Set<String> METHODS = Set.of("status");
    private static final BuildInfo BUILD = BuildInfo.load();
    private static final GridTopologyQuarantine GRID_HOLDS = new GridTopologyQuarantine();
    private final ControllerHost host;
    private final Application application;
    private final Transport transport;
    private final TrackWindow window;
    private final ProjectState projectState;
    private final MethodSpecs specs;
    private final ParameterModule parameters;
    private final Holds holds;
    private final List<HostModule> domains;

    /** {@code domains} is every registered module, read when status asks each for its section. */
    DiscoveryModule(
            ControllerHost host,
            Application application,
            Transport transport,
            TrackWindow window,
            ProjectState projectState,
            MethodSpecs specs,
            ParameterModule parameters,
            Holds holds,
            List<HostModule> domains) {
        this.host = host;
        this.application = application;
        this.transport = transport;
        this.window = window;
        this.projectState = projectState;
        this.specs = specs;
        this.parameters = parameters;
        this.holds = holds;
        this.domains = domains;
    }

    @Override
    public Set<String> methods() {
        return METHODS;
    }

    @Override
    public void validate(String method, JsonObject params) {
        Params.fields(params, Set.of(), Set.of("detail"));
    }

    @Override
    public void dispatch(HostOperation operation, String method, JsonObject params) {
        operation.result.complete(status(operation, params.has("detail")));
    }

    private JsonObject status(HostOperation operation, boolean full) {
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
        addDomainSections(operation, result, domains, full);
        if (full) implementation(result);
        return result;
    }

    static void addDomainSections(HostOperation operation, JsonObject out, List<HostModule> domains, boolean full) {
        for (HostModule domain : domains) domain.status(operation, out, full);
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

    /** The implemented methods, bounded windows and units; native binding availability comes from each module's section. */
    private void implementation(JsonObject result) {
        JsonArray methods = new JsonArray();
        JsonObject byDomain = new JsonObject();
        for (String method : specs.methods()) {
            methods.add(method);
            String domain = method.contains(".") ? method.substring(0, method.indexOf('.')) : "discovery";
            if (!byDomain.has(domain)) byDomain.add(domain, new JsonArray());
            byDomain.getAsJsonArray(domain).add(method);
        }
        result.add("methods", methods);
        result.add("domains", byDomain);
        JsonObject windows = new JsonObject();
        windows.add("tracks", window(window.offset(), TRACKS, window.total()));
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
        units.addProperty("noteVelocity", "midi_1_to_127_integer_same_unit_as_notes.edit");
        units.addProperty("notePitch", "midi_0_to_127");
        result.add("units", units);
        result.addProperty("parameterIdentityScope", "observed_bank_proxy_context_not_persistent_device_identity");
        result.addProperty("writeSemantics", "absolute_or_explicit_command_no_automatic_retries");
        result.addProperty(
                "evidence",
                "Runtime implementation discovery only; not per-method host verification or full interface coverage.");
    }
}
