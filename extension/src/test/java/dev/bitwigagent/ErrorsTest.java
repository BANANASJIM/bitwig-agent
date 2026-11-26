package dev.bitwigagent;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

public final class ErrorsTest {
    private static final Map<ErrorCode, String> WIRE = wire();
    private static final Map<Outcome, String> OUTCOMES = Map.of(
            Outcome.NOT_APPLIED, "not_applied",
            Outcome.UNKNOWN, "unknown",
            Outcome.NOT_DISPATCHED, "not_dispatched");

    private static Map<ErrorCode, String> wire() {
        Map<ErrorCode, String> m = new LinkedHashMap<>();
        String[] names = {
            "ambiguous_send",
            "ambiguous_target",
            "arranger_overlap",
            "arranger_unavailable",
            "arranger_verification_failed",
            "automation_bounds_exceeded",
            "automation_unavailable",
            "automation_verification_failed",
            "bounce_failed",
            "bounce_verification_failed",
            "busy",
            "cancelled",
            "clip_not_found",
            "copy_not_observed",
            "deadline_exceeded",
            "delete_not_observed",
            "duplicateContent_not_observed",
            "empty_midi_file",
            "engine_inactive",
            "export_failed",
            "file_unavailable",
            "grid_bounds_exceeded",
            "grid_unavailable",
            "grid_verification_failed",
            "import_not_observed",
            "internal_error",
            "invalid_midi_file",
            "invalid_params",
            "invalid_request",
            "invalid_target",
            "kind_mismatch",
            "last_scene",
            "launcher_settings_unavailable",
            "launcher_unavailable",
            "marker_exists_at_beat",
            "method_not_found",
            "missing_target",
            "modulator_bounds_exceeded",
            "modulator_insert_rejected",
            "modulator_unavailable",
            "modulator_verification_failed",
            "native_unavailable",
            "no_notes",
            "not_grid",
            "not_ready",
            "note_collision",
            "outside_window",
            "page_exists",
            "path_not_allowed",
            "project_not_ready",
            "project_save_as_unavailable",
            "quantize_invariants_violated",
            "quantize_not_observed",
            "rejected_cycle",
            "render_needs_confirmation",
            "render_not_started",
            "render_refused",
            "render_unavailable",
            "routing_bounds_exceeded",
            "routing_unavailable",
            "save_failed",
            "save_refused",
            "scene_count_not_observed",
            "scene_limit",
            "scene_out_of_range",
            "slot_occupied",
            "slot_out_of_range",
            "stale_context",
            "stale_target",
            "target_exists",
            "target_not_found",
            "target_not_observed",
            "track_group_unavailable",
            "transpose_not_observed",
            "unavailable",
            "unexpected_side_effect",
            "unknown_action",
            "unknown_marker",
            "unknown_method",
            "unknown_parameter",
            "unknown_send",
            "unresolved_creation",
            "unresolved_mutation",
            "unsafe_state",
            "unsaved_changes",
            "unsupported_scope",
            "unsupported_unit",
            "unverifiable_clip",
            "write_not_observed",
            "write_quarantined"
        };
        ErrorCode[] codes = ErrorCode.values();
        if (codes.length != names.length)
            throw new AssertionError("ErrorCode has " + codes.length + " entries, expected " + names.length);
        for (String name : names) m.put(byWire(name), name);
        return m;
    }

    private static ErrorCode byWire(String name) {
        for (ErrorCode code : ErrorCode.values()) if (code.wire().equals(name)) return code;
        throw new AssertionError("No ErrorCode with wire name " + name);
    }

    @Test
    void errorCodeWireNames() {
        for (Map.Entry<ErrorCode, String> e : WIRE.entrySet())
            assertTrue(e.getKey().wire().equals(e.getValue()), "wire name of " + e.getKey());
    }

    @Test
    void outcomeWireNames() {
        for (Map.Entry<Outcome, String> e : OUTCOMES.entrySet())
            assertTrue(e.getKey().wire().equals(e.getValue()), "outcome wire name of " + e.getKey());
        assertTrue(Outcome.values().length == OUTCOMES.size(), "no unlisted outcome");
    }

    @Test
    void factoriesBuildStructuredErrors() {
        RpcException notApplied = Errors.notApplied(ErrorCode.BUSY, "m");
        assertTrue(notApplied.code.equals("busy") && notApplied.outcome.equals("not_applied"), "notApplied");
        RpcException unknown = Errors.unknown(ErrorCode.STALE_CONTEXT, "m");
        assertTrue(unknown.code.equals("stale_context") && unknown.outcome.equals("unknown"), "unknown");
        RpcException invalid = Errors.invalid("bad");
        assertTrue(
                invalid.code.equals("invalid_params")
                        && invalid.outcome.equals("not_applied")
                        && invalid.getMessage().equals("bad"),
                "invalid");
        RpcException rewritten = Errors.withOutcome(Errors.notApplied(ErrorCode.BUSY, "m"), Outcome.UNKNOWN);
        assertTrue(
                rewritten.is(ErrorCode.BUSY)
                        && rewritten.is(Outcome.UNKNOWN)
                        && rewritten.getMessage().equals("m"),
                "withOutcome");
    }
}
