package dev.bitwigagent.routing;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.HostLimits;
import dev.bitwigagent.core.Outcome;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.core.Refs;
import dev.bitwigagent.core.Results;
import dev.bitwigagent.core.RpcException;
import dev.bitwigagent.core.ValueMatch;
import dev.bitwigagent.core.Verification;
import dev.bitwigagent.routing.RoutingPort.Change;
import dev.bitwigagent.routing.RoutingPort.Choice;
import dev.bitwigagent.routing.RoutingPort.Component;
import dev.bitwigagent.routing.RoutingPort.Sidechains;
import dev.bitwigagent.routing.RoutingPort.TrackRouting;
import dev.bitwigagent.routing.RoutingPort.Unresolved;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * The routing domain rules, host-free: which entry kinds a chooser takes, the write plan (expect, kind admission, a
 * track routed to itself or through itself), per-field outcomes from the native readback, and the result shapes.
 */
public final class RoutingModel {
    static final List<String> OUTPUT_KINDS = List.of("none", "track_audio_input", "track_note_input");
    /** Native source-kind group (kCk) that admits each requestable source kind; "none" is always admitted. */
    static final Map<String, String> SOURCE_GROUP = sourceGroups();

    private RoutingModel() {}

    private static Map<String, String> sourceGroups() {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("pre_fader", "TRACK_AUDIO_SOURCES");
        out.put("post_fader", "TRACK_AUDIO_SOURCES");
        out.put("track_notes", "TRACK_NOTE_SOURCES");
        out.put("all_ins", "HARDWARE_NOTE_SOURCES");
        out.put("computer_keyboard", "HARDWARE_NOTE_SOURCES");
        out.put("onscreen_keyboard", "HARDWARE_NOTE_SOURCES");
        out.put("hardware_audio_input", "HARDWARE_AUDIO_SOURCES");
        out.put("hardware_note_input", "HARDWARE_NOTE_SOURCES");
        return out;
    }

    /** The source kinds a chooser with these native groups takes (always none); hardware inputs only where asked. */
    public static List<String> sourceKinds(Collection<String> nativeGroups, boolean hardware) {
        List<String> out = new ArrayList<>(List.of("none"));
        for (Map.Entry<String, String> kind : SOURCE_GROUP.entrySet())
            if (nativeGroups.contains(kind.getValue())
                    && (hardware || !RoutingEntry.HARDWARE_KINDS.contains(kind.getKey()))) out.add(kind.getKey());
        return out;
    }

    /** Master's output only offers hardware outputs, which this version does not set. */
    static String outputUnavailable(String trackType) {
        return "Master".equals(trackType) ? "master_output_is_hardware_only_and_hardware_outputs_are_deferred" : null;
    }

    static String componentUnavailable(Component c) {
        if (c.chooser == null) return "component_has_no_source_selector";
        if (c.kind.equals("audio_in") || c.kind.equals("other")) return "hardware_or_unknown_component_deferred";
        return null;
    }

    // ---- request rules ----

    /**
     * What the schema cannot say: a routing write changes the output or the input, an expect compares only those, and
     * an entry has a track exactly for the track kinds, an id exactly for the hardware kinds, and a name only for kind
     * other (required) or a hardware kind.
     */
    static void validateParams(String method, JsonObject p) {
        if (method.equals("tracks.routing.get") || method.equals("devices.sidechain.get")) return;
        ValueMatch.requireNamed(p);
        if (method.equals("tracks.routing.set") && !p.has("output") && !p.has("input"))
            throw Errors.invalid("Supply output, input or both.");
        JsonObject expect = p.has("expect") ? p.getAsJsonObject("expect") : new JsonObject();
        for (String key : expect.keySet())
            if (!p.has(key)) throw Errors.invalid("expect." + key + " compares a field this call does not write.");
        for (String key : List.of("output", "input", "source")) if (p.has(key)) entry(p.getAsJsonObject(key), key);
        for (String key : expect.keySet()) entry(expect.getAsJsonObject(key), "expect." + key);
    }

    private static void entry(JsonObject entry, String where) {
        String kind = entry.get("kind").getAsString();
        boolean track = RoutingEntry.TRACK_KINDS.contains(kind),
                hardware = RoutingEntry.HARDWARE_KINDS.contains(kind),
                other = kind.equals(RoutingEntry.OTHER);
        if (entry.has("track") != track)
            throw Errors.invalid(where + ": track is required for " + new TreeSet<>(RoutingEntry.TRACK_KINDS)
                    + " and not allowed otherwise.");
        if (entry.has("id") != hardware)
            throw Errors.invalid(where + ": id is required for " + new TreeSet<>(RoutingEntry.HARDWARE_KINDS)
                    + " and not allowed otherwise.");
        if (other ? !entry.has("name") : entry.has("name") && !hardware)
            throw Errors.invalid(where + ": name is required for kind other and allowed only there and on hardware.");
        if (entry.has("nativeType") && !other) throw Errors.invalid(where + ": nativeType is only for kind other.");
    }

    /** When the request carries expect.{key}, the entry read now must be that one; otherwise nothing is written. */
    static void requireExpected(JsonObject p, String key, RoutingEntry observed) {
        if (!p.has("expect") || !p.getAsJsonObject("expect").has(key)) return;
        if (!RoutingEntry.of(p.getAsJsonObject("expect").getAsJsonObject(key)).matches(observed)) {
            JsonObject differs = new JsonObject();
            differs.add(key, observed.json());
            throw Errors.expectMismatch("The current " + key + " differs from expect; nothing changed.", differs);
        }
    }

    /** The write plan of tracks.routing.set against the observed choosers; fields already holding their entry drop out. */
    static Change planTrack(JsonObject p, String self, TrackRouting seen) {
        Map<String, RoutingEntry> targets = new LinkedHashMap<>();
        if (p.has("output")) {
            String unavailable = outputUnavailable(seen.trackType);
            if (unavailable != null)
                throw Errors.notApplied(
                        Reason.UNSUPPORTED_SCOPE,
                        "This track's output cannot be set in this version (" + unavailable
                                + "); nothing was dispatched.");
            requireExpected(p, "output", seen.output.current);
            RoutingEntry request = RoutingEntry.of(p.getAsJsonObject("output"));
            if (request.track != null && request.track.equalsIgnoreCase(self))
                throw Errors.notApplied(
                        Reason.ROUTING_CYCLE, "A track cannot route its output to itself; nothing was dispatched.");
            if (!request.matches(seen.output.current)) targets.put("output", request);
        }
        if (p.has("input")) {
            if (seen.input == null)
                throw Errors.notApplied(
                        Reason.UNSUPPORTED_SCOPE,
                        "This track has no input chooser in this version (" + seen.inputUnavailable
                                + "); nothing was dispatched.");
            requireExpected(p, "input", seen.input.current);
            RoutingEntry request = RoutingEntry.of(p.getAsJsonObject("input"));
            List<String> kinds = sourceKinds(seen.input.groups, true);
            if (!kinds.contains(request.kind))
                throw Errors.notApplied(
                        Reason.KIND_MISMATCH,
                        "A " + seen.trackType + " track's input accepts " + kinds + ", not " + request.kind
                                + "; nothing was dispatched.");
            if (request.track != null && request.track.equalsIgnoreCase(self))
                throw Errors.notApplied(
                        Reason.ROUTING_CYCLE, "A track cannot be its own input; nothing was dispatched.");
            if (!request.matches(seen.input.current)) targets.put("input", request);
        }
        RoutingEntry output = targets.get("output"), input = targets.get("input");
        if (output != null
                && input != null
                && output.track != null
                && input.track != null
                && output.track.equalsIgnoreCase(input.track))
            throw Errors.notApplied(
                    Reason.ROUTING_CYCLE,
                    "output and input name the same track, which would loop through this track; Bitwig checks each against the current graph only, so this pair is refused (set them in two calls if one is meant to replace the other). Nothing changed.");
        return new Change(targets);
    }

    /** The write plan of devices.sidechain.set for the component now at the requested index (null: none). */
    static Change planSidechain(JsonObject p, JsonObject device, Component c) {
        JsonObject ref = p.getAsJsonObject("target");
        if (c == null) throw Errors.targetNotFound(ref, "The device has no sidechain component at this index now.");
        JsonObject found = ref(device, c);
        if (!Refs.matches(ref, found, true))
            throw Errors.targetChanged("Another sidechain component is at this index than the one read.", found);
        String unavailable = componentUnavailable(c);
        if (unavailable != null)
            throw Errors.notApplied(
                    Reason.UNSUPPORTED_SCOPE,
                    "Component " + c.index + " cannot be set in this version (" + unavailable
                            + "); nothing was dispatched.");
        requireExpected(p, "source", c.chooser.current);
        RoutingEntry request = RoutingEntry.of(p.getAsJsonObject("source"));
        List<String> kinds = sourceKinds(c.groups, false);
        if (!kinds.contains(request.kind))
            throw Errors.notApplied(
                    Reason.KIND_MISMATCH,
                    "This " + c.kind + " component accepts " + kinds + ", not " + request.kind
                            + "; nothing was dispatched.");
        Map<String, RoutingEntry> targets = new LinkedHashMap<>();
        if (!request.matches(c.chooser.current)) targets.put("source", request);
        return new Change(targets);
    }

    /** Bitwig's own accept check refused a target: nothing was dispatched. */
    static RpcException rejected(String field, JsonObject request) {
        return Errors.notApplied(
                Reason.ROUTING_CYCLE,
                "Bitwig's " + (field.equals("output") ? "destination" : "source") + " check rejects " + request
                        + " (it would create a routing cycle); nothing was dispatched.");
    }

    // ---- results ----

    private static JsonArray strings(Collection<String> values) {
        JsonArray out = new JsonArray();
        values.forEach(out::add);
        return out;
    }

    private static JsonElement name(String name) {
        return name == null ? JsonNull.INSTANCE : new JsonPrimitive(name);
    }

    private static JsonArray choices(List<Choice> choices) {
        JsonArray out = new JsonArray();
        for (Choice choice : choices) {
            JsonObject row = new JsonObject();
            row.add("entry", choice.entry.json());
            row.add("name", name(choice.name));
            row.addProperty("accepted", choice.accepted);
            out.add(row);
        }
        return out;
    }

    private static void unresolved(JsonObject out, List<Unresolved> unresolved) {
        if (unresolved.isEmpty()) return;
        JsonArray rows = new JsonArray();
        for (Unresolved track : unresolved) {
            JsonObject row = new JsonObject();
            row.add("ref", Refs.track(track.track.id, track.track.name));
            row.addProperty("type", track.track.type);
            row.addProperty("reason", track.reason.wire());
            row.addProperty("message", track.message);
            rows.add(row);
        }
        out.add("unresolvedTracks", rows);
    }

    /** tracks.routing.get, and the snapshot a tracks.routing.set returns. */
    static JsonObject track(TrackRouting r, boolean includeChoices) {
        JsonObject out = new JsonObject();
        out.addProperty("trackType", r.trackType);
        out.addProperty("nativeTrackKind", r.nativeKind);
        out.addProperty("nativeRole", r.nativeRole);
        String unavailable = outputUnavailable(r.trackType);
        JsonObject output = new JsonObject();
        output.add("entry", r.output.current.json());
        output.add("name", name(r.output.currentName));
        output.add("kinds", strings(unavailable == null ? OUTPUT_KINDS : List.of()));
        output.addProperty("writable", unavailable == null);
        if (unavailable != null) output.addProperty("reason", unavailable);
        if (includeChoices) output.add("choices", choices(r.output.choices));
        out.add("output", output);
        if (r.input == null) {
            out.add("input", JsonNull.INSTANCE);
            out.addProperty("inputUnavailableReason", r.inputUnavailable);
        } else {
            JsonObject input = new JsonObject();
            input.add("entry", r.input.current.json());
            input.add("name", name(r.input.currentName));
            input.add("kinds", strings(sourceKinds(r.input.groups, true)));
            input.add("nativeSourceGroups", strings(r.input.groups));
            input.addProperty("writable", true);
            if (includeChoices) input.add("choices", choices(r.input.choices));
            out.add("input", input);
        }
        unresolved(out, r.unresolved);
        if (includeChoices) {
            JsonObject hardware = new JsonObject();
            hardware.add("audio", name(r.hardware.audio));
            hardware.add("notes", name(r.hardware.notes));
            out.add("hardwareInputs", hardware);
        }
        out.addProperty(
                "coverage",
                "tracks_of_the_" + HostLimits.TRACKS
                        + "_track_window_and_hardware_inputs_by_stable_id; device_outputs_and_hardware_outputs_read_as_other_and_not_settable");
        out.addProperty("selectionSideEffect", "none");
        return out;
    }

    /** The sidechain ref of a component: its index and componentId, plus the modulator or Grid module that owns it. */
    static JsonObject ref(JsonObject device, Component c) {
        return Refs.sidechain(
                device,
                c.index,
                c.id,
                "modulator".equals(c.host) ? c.ownerId : null,
                "grid_module".equals(c.host) ? c.ownerId : null);
    }

    static JsonObject component(JsonObject device, Component c, boolean includeChoices) {
        String unavailable = componentUnavailable(c);
        JsonObject out = new JsonObject();
        out.add("ref", ref(device, c));
        out.addProperty("label", c.label);
        out.addProperty("kind", c.kind);
        out.addProperty("host", c.host);
        out.add("nativeSourceGroups", strings(c.groups));
        out.add("kinds", strings(unavailable == null ? sourceKinds(c.groups, false) : List.of()));
        out.addProperty("writable", unavailable == null);
        if (unavailable != null) out.addProperty("reason", unavailable);
        out.add("source", c.chooser == null ? JsonNull.INSTANCE : c.chooser.current.json());
        out.add("sourceName", name(c.chooser == null ? null : c.chooser.currentName));
        if (includeChoices && unavailable == null) out.add("choices", choices(c.chooser.choices));
        return out;
    }

    static JsonObject sidechains(JsonObject device, Sidechains s, boolean includeChoices) {
        JsonObject out = new JsonObject();
        JsonArray rows = new JsonArray();
        for (Component c : s.components) rows.add(component(device, c, includeChoices));
        out.add("components", rows);
        out.addProperty("count", s.components.size());
        unresolved(out, s.unresolved);
        out.addProperty(
                "coverage",
                "routing_components_of_this_device_its_modulators_and_grid_modules; nested_devices_excluded");
        out.addProperty("selectionSideEffect", "none");
        return out;
    }

    /** One chooser's field: confirmed by reading the chooser back by identity, a mismatch is unknown. */
    static JsonObject row(Outcome outcome, JsonObject request, JsonObject previous, JsonObject readback) {
        JsonObject row = new JsonObject();
        Results.stamp(row, outcome, Verification.NATIVE_READBACK);
        row.add("requested", request);
        row.add("previous", previous);
        row.add("readback", readback);
        if (outcome == Outcome.UNKNOWN) row.addProperty("reason", Reason.READBACK_MISMATCH.wire());
        return row;
    }

    /**
     * The per-field outcomes of an edit, from the entries before and after it: a field the plan left out already held
     * its entry, a written one is applied when the readback is the requested entry and unknown otherwise.
     */
    static JsonObject finish(
            JsonObject out,
            JsonObject p,
            Map<String, RoutingEntry> before,
            Map<String, RoutingEntry> after,
            Change change) {
        JsonObject fields = new JsonObject();
        boolean unknown = false;
        for (String key : before.keySet()) {
            JsonObject request = p.getAsJsonObject(key);
            Outcome outcome = !change.targets.containsKey(key)
                    ? Outcome.ALREADY_MATCHED
                    : RoutingEntry.of(request).matches(after.get(key)) ? Outcome.APPLIED : Outcome.UNKNOWN;
            unknown |= outcome == Outcome.UNKNOWN;
            fields.add(
                    key,
                    row(outcome, request, before.get(key).json(), after.get(key).json()));
        }
        out.add("fields", fields);
        boolean any = !change.isEmpty();
        Results.stamp(
                out,
                unknown ? Outcome.UNKNOWN : any ? Outcome.APPLIED : Outcome.ALREADY_MATCHED,
                Verification.NATIVE_READBACK);
        if (unknown) out.addProperty("reason", Reason.READBACK_MISMATCH.wire());
        out.addProperty("nativeUndo", any);
        out.addProperty("undoGroupingVerified", true);
        if (unknown)
            out.addProperty(
                    "next",
                    "Never retry: inspect with a fresh get, then ack routing.quarantine; routing writes are refused until then.");
        return out;
    }
}
