package dev.bitwigagent.routing;

import static dev.bitwigagent.RpcAssert.assertRpcError;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.bitwigagent.HostBridge;
import dev.bitwigagent.HostFixture;
import dev.bitwigagent.core.Outcome;
import dev.bitwigagent.core.Results;
import dev.bitwigagent.routing.RoutingPort.Change;
import dev.bitwigagent.routing.RoutingPort.Chooser;
import dev.bitwigagent.routing.RoutingPort.Component;
import dev.bitwigagent.routing.RoutingPort.Hardware;
import dev.bitwigagent.routing.RoutingPort.TrackRouting;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The host-free routing rules: kinds, compare-and-set identity, the write plans, outcomes and result shapes. */
final class RoutingModelTest {
    static final String A = HostFixture.A, B = HostFixture.B, C = HostFixture.C;
    static final List<String> AUDIO = List.of("HARDWARE_AUDIO_SOURCES", "TRACK_AUDIO_SOURCES");
    static final List<String> INSTRUMENT = List.of("HARDWARE_NOTE_SOURCES", "TRACK_NOTE_SOURCES");

    static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    static TrackRouting routing(String type, RoutingEntry output, RoutingEntry input, List<String> groups) {
        return new TrackRouting(
                type,
                type.toUpperCase(Locale.ROOT),
                "track",
                new Chooser(output, null, List.of(), List.of()),
                input == null ? null : new Chooser(input, null, groups, List.of()),
                input == null ? "no_input_chooser_for_track_type_Group" : null,
                List.of(),
                new Hardware(null, null));
    }

    static JsonObject set(String members) {
        return json("{\"target\":" + HostFixture.track(A) + "," + members + "}");
    }

    static String track(String id) {
        return HostFixture.track(id).toString();
    }

    @Test
    void kindsFollowTheNativeGroupsAndHardwareOnlyWhereAsked() {
        assertEquals(
                List.of("none", "pre_fader", "post_fader", "hardware_audio_input"),
                RoutingModel.sourceKinds(AUDIO, true));
        assertEquals(List.of("none", "pre_fader", "post_fader"), RoutingModel.sourceKinds(AUDIO, false));
        assertEquals(
                List.of(
                        "none",
                        "track_notes",
                        "all_ins",
                        "computer_keyboard",
                        "onscreen_keyboard",
                        "hardware_note_input"),
                RoutingModel.sourceKinds(INSTRUMENT, true));
        assertEquals(List.of("none"), RoutingModel.sourceKinds(List.of(), true));
    }

    @Test
    void compareAndSetIdentity() {
        RoutingEntry pre = RoutingEntry.track("pre_fader", B);
        assertTrue(RoutingEntry.track("pre_fader", B.toUpperCase(Locale.ROOT)).matches(pre), "track UUID case");
        assertFalse(RoutingEntry.track("post_fader", B).matches(pre), "kind");
        assertFalse(RoutingEntry.track("pre_fader", C).matches(pre), "track");
        RoutingEntry bus = RoutingEntry.hardware("hardware_audio_input", "ab-cd", "Stereo 1");
        assertTrue(
                RoutingEntry.of(json("{\"kind\":\"hardware_audio_input\",\"id\":\"AB-CD\"}"))
                        .matches(bus),
                "id only");
        assertFalse(
                RoutingEntry.hardware("hardware_audio_input", "ef", "Stereo 1").matches(bus), "same name, other id");
        assertFalse(RoutingEntry.hardware("hardware_note_input", "ab-cd", null).matches(bus), "hardware kind");
        RoutingEntry other = RoutingEntry.other("Out 1", "x");
        assertTrue(RoutingEntry.other("Out 1", null).matches(other));
        assertFalse(RoutingEntry.other("Out 2", null).matches(other));
        assertFalse(RoutingEntry.other("Out 1", "y").matches(other));
        assertTrue(RoutingEntry.none().matches(RoutingEntry.none()));
        assertFalse(RoutingEntry.none().matches(bus));
        assertEquals(json("{\"kind\":\"hardware_audio_input\",\"id\":\"ab-cd\",\"name\":\"Stereo 1\"}"), bus.json());
        assertEquals(bus.json(), RoutingEntry.of(bus.json()).json(), "an entry round-trips through its JSON");
    }

    @Test
    void entriesCarryWhatTheirKindNeeds() {
        String m = "tracks.routing.set";
        ok(m, set("\"input\":{\"kind\":\"hardware_audio_input\",\"id\":\"x\",\"name\":\"Stereo 1\"}"));
        ok(m, set("\"input\":{\"kind\":\"hardware_note_input\",\"id\":\"x\"}"));
        ok(
                m,
                set(
                        "\"input\":{\"kind\":\"none\"},\"expect\":{\"input\":{\"kind\":\"hardware_note_input\",\"id\":\"x\",\"name\":\"Keys\"}}"));
        bad(m, set("\"input\":{\"kind\":\"hardware_audio_input\"}"));
        bad(m, set("\"input\":{\"kind\":\"all_ins\",\"id\":\"x\"}"));
        bad(m, set("\"input\":{\"kind\":\"all_ins\",\"name\":\"x\"}"));
        bad(m, set("\"input\":{\"kind\":\"none\"},\"expect\":{\"input\":{\"kind\":\"other\"}}"));
        bad(
                m,
                set(
                        "\"input\":{\"kind\":\"none\"},\"expect\":{\"input\":{\"kind\":\"hardware_note_input\",\"id\":\"x\",\"nativeType\":\"y\"}}"));
        bad(
                "devices.sidechain.set",
                json("{\"target\":{\"kind\":\"sidechain\",\"device\":"
                        + dev.bitwigagent.core.Refs.device(HostFixture.track(A), 0, "Compressor")
                        + ",\"index\":0,\"id\":\"S\"},\"source\":{\"kind\":\"hardware_audio_input\",\"id\":\"x\"}}"));
    }

    private static void ok(String m, JsonObject p) {
        assertDoesNotThrow(
                () -> RoutingModel.validateParams(m, HostBridge.checkSchema(HostFixture.SPECS, m, p)), p.toString());
    }

    private static void bad(String m, JsonObject p) {
        assertRpcError(
                "invalid_params",
                () -> RoutingModel.validateParams(m, HostBridge.checkSchema(HostFixture.SPECS, m, p)));
    }

    @Test
    void theTrackPlanKeepsOnlyRealChanges() {
        TrackRouting seen = routing("Audio", RoutingEntry.none(), RoutingEntry.track("pre_fader", B), AUDIO);
        Change change = RoutingModel.planTrack(
                set("\"output\":{\"kind\":\"none\"},\"input\":{\"kind\":\"hardware_audio_input\",\"id\":\"bus\"}"),
                A,
                seen);
        assertEquals(List.of("input"), List.copyOf(change.targets.keySet()), "none already holds: only input");
        assertTrue(RoutingModel.planTrack(set("\"input\":{\"kind\":\"pre_fader\",\"track\":" + track(B) + "}"), A, seen)
                .isEmpty());
    }

    @Test
    void theTrackPlanRefusesBeforeAnyWrite() {
        TrackRouting audio = routing("Audio", RoutingEntry.none(), RoutingEntry.none(), AUDIO);
        assertRpcError(
                "kind_mismatch",
                () -> RoutingModel.planTrack(
                        set("\"input\":{\"kind\":\"hardware_note_input\",\"id\":\"n\"}"), A, audio));
        assertRpcError(
                "kind_mismatch", () -> RoutingModel.planTrack(set("\"input\":{\"kind\":\"all_ins\"}"), A, audio));
        assertRpcError(
                "routing_cycle",
                () -> RoutingModel.planTrack(
                        set("\"output\":{\"kind\":\"track_audio_input\",\"track\":" + track(A) + "}"), A, audio));
        assertRpcError(
                "routing_cycle",
                () -> RoutingModel.planTrack(
                        set("\"input\":{\"kind\":\"post_fader\",\"track\":" + track(A) + "}"), A, audio));
        assertRpcError(
                "routing_cycle",
                () -> RoutingModel.planTrack(
                        set("\"output\":{\"kind\":\"track_audio_input\",\"track\":" + track(B)
                                + "},\"input\":{\"kind\":\"post_fader\",\"track\":" + track(B) + "}"),
                        A,
                        audio));
        assertRpcError(
                "target_changed",
                () -> RoutingModel.planTrack(
                        set(
                                "\"input\":{\"kind\":\"none\"},\"expect\":{\"input\":{\"kind\":\"hardware_audio_input\",\"id\":\"b\"}}"),
                        A,
                        routing(
                                "Audio",
                                RoutingEntry.none(),
                                RoutingEntry.hardware("hardware_audio_input", "a", "In"),
                                AUDIO)));
        assertRpcError(
                "unsupported_scope",
                () -> RoutingModel.planTrack(
                        set("\"input\":{\"kind\":\"none\"}"),
                        A,
                        routing("Group", RoutingEntry.none(), null, List.of())));
        assertRpcError(
                "unsupported_scope",
                () -> RoutingModel.planTrack(
                        set("\"output\":{\"kind\":\"none\"}"),
                        A,
                        routing("Master", RoutingEntry.none(), null, List.of())));
    }

    static Component component(String kind, Chooser chooser) {
        return new Component(0, "S", "Sidechain", kind, "device", null, List.of("TRACK_AUDIO_SOURCES"), chooser);
    }

    @Test
    void theSidechainPlanChecksTheComponentFirst() {
        JsonObject device = dev.bitwigagent.core.Refs.device(HostFixture.track(A), 0, "Compressor");
        JsonObject p = json("{\"target\":{\"kind\":\"sidechain\",\"device\":" + device
                + ",\"index\":0,\"id\":\"S\"},\"source\":{\"kind\":\"pre_fader\",\"track\":" + track(B) + "}}");
        Chooser none = new Chooser(RoutingEntry.none(), null, List.of("TRACK_AUDIO_SOURCES"), List.of());
        assertRpcError("target_not_found", () -> RoutingModel.planSidechain(p, device, null));
        assertRpcError(
                "target_changed",
                () -> RoutingModel.planSidechain(
                        p,
                        device,
                        new Component(0, "T", "Other", "audio", "device", null, List.of("TRACK_AUDIO_SOURCES"), none)));
        assertRpcError("unsupported_scope", () -> RoutingModel.planSidechain(p, device, component("audio_in", none)));
        assertRpcError("unsupported_scope", () -> RoutingModel.planSidechain(p, device, component("audio", null)));
        assertEquals(
                List.of("source"),
                List.copyOf(RoutingModel.planSidechain(p, device, component("audio", none))
                        .targets
                        .keySet()));
        JsonObject notes = p.deepCopy();
        notes.add("source", json("{\"kind\":\"track_notes\",\"track\":" + track(B) + "}"));
        assertRpcError("kind_mismatch", () -> RoutingModel.planSidechain(notes, device, component("audio", none)));
    }

    @Test
    void outcomesComeFromTheReadback() {
        JsonObject p =
                set("\"output\":{\"kind\":\"none\"},\"input\":{\"kind\":\"hardware_audio_input\",\"id\":\"bus\"}");
        RoutingEntry bus = RoutingEntry.hardware("hardware_audio_input", "bus", "Stereo 1");
        Map<String, RoutingEntry> before = Map.of("output", RoutingEntry.none(), "input", RoutingEntry.none());
        Change change = new Change(Map.of("input", bus));
        JsonObject applied = RoutingModel.finish(
                new JsonObject(), p, before, Map.of("output", RoutingEntry.none(), "input", bus), change);
        assertEquals("applied", applied.get("outcome").getAsString());
        assertEquals(
                "already_matched",
                applied.getAsJsonObject("fields")
                        .getAsJsonObject("output")
                        .get("outcome")
                        .getAsString());
        assertTrue(Results.leafRuleHolds(applied), applied.toString());
        assertTrue(applied.get("nativeUndo").getAsBoolean());
        JsonObject unknown = RoutingModel.finish(new JsonObject(), p, before, before, change);
        assertEquals("unknown", unknown.get("outcome").getAsString());
        JsonObject field = unknown.getAsJsonObject("fields").getAsJsonObject("input");
        assertEquals("readback_mismatch", field.get("reason").getAsString());
        assertFalse(field.has("verification"), "an unknown field names no verification");
        assertTrue(unknown.has("next"));
        JsonObject matched = RoutingModel.finish(new JsonObject(), p, before, before, new Change(Map.of()));
        assertEquals("already_matched", matched.get("outcome").getAsString());
        assertFalse(matched.get("nativeUndo").getAsBoolean());
        assertEquals(
                Outcome.APPLIED.wire(),
                RoutingModel.row(Outcome.APPLIED, p, p, p).get("outcome").getAsString());
    }

    @Test
    void theTrackSnapshotNamesWhyHardwareIsNotListed() {
        TrackRouting r = new TrackRouting(
                "Audio",
                "AUDIO",
                "track",
                new Chooser(RoutingEntry.none(), null, List.of(), List.of()),
                new Chooser(
                        RoutingEntry.hardware("hardware_audio_input", "bus", "Stereo 1"),
                        "Stereo 1",
                        AUDIO,
                        List.of(new RoutingPort.Choice(RoutingEntry.none(), null, true))),
                null,
                List.of(),
                new Hardware("audio_backend_has_no_configured_buses", "this_input_takes_no_hardware_notes"));
        JsonObject out = RoutingModel.track(r, true);
        assertEquals(
                "audio_backend_has_no_configured_buses",
                out.getAsJsonObject("hardwareInputs").get("audio").getAsString());
        assertEquals("Stereo 1", out.getAsJsonObject("input").get("name").getAsString());
        assertEquals(
                "bus",
                out.getAsJsonObject("input").getAsJsonObject("entry").get("id").getAsString());
        assertFalse(RoutingModel.track(r, false).has("hardwareInputs"), "only with includeChoices");
        JsonObject master =
                RoutingModel.track(routing("Master", RoutingEntry.other("Out", "t"), null, List.of()), false);
        assertFalse(master.getAsJsonObject("output").get("writable").getAsBoolean());
        assertTrue(master.get("input").isJsonNull());
    }

    @Test
    void unresolvedBankTracksAreReportedNotGuessed() {
        RoutingPort.TrackRef lost = new RoutingPort.TrackRef(C, "Lost", "Audio", null);
        TrackRouting r = new TrackRouting(
                "Audio",
                "AUDIO",
                "track",
                new Chooser(RoutingEntry.none(), null, List.of(), List.of()),
                new Chooser(RoutingEntry.source("all_ins"), "All ins", INSTRUMENT, List.of()),
                null,
                List.of(new RoutingPort.Unresolved(lost, dev.bitwigagent.core.Reason.TARGET_NOT_FOUND, "gone")),
                new Hardware(null, null));
        JsonObject row = RoutingModel.track(r, false)
                .getAsJsonArray("unresolvedTracks")
                .get(0)
                .getAsJsonObject();
        assertEquals(HostFixture.track(C).get("id"), row.getAsJsonObject("ref").get("id"));
        assertEquals("target_not_found", row.get("reason").getAsString());
        assertEquals("gone", row.get("message").getAsString());
        assertEquals("{\"kind\":\"all_ins\"}", r.input.current.toString());
    }
}
