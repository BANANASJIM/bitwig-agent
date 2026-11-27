package dev.bitwigagent.grid;

import com.google.gson.*;
import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.Outcome;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.core.Results;
import dev.bitwigagent.core.Verification;

/** One string-panel CAS: same admission, context guards and readback as the boolean panel, without a coupled scalar. */
public final class GridStringPanelWriter {
    public interface Access {
        void requireSupported(Object parameter);

        void set(Object parameter, String value);
    }

    private final GridSnapshotReader values;
    private final GridParameterContext contexts;
    private final Access nativeWriter;

    public GridStringPanelWriter(GridSnapshotReader values, GridParameterContext contexts, Access nativeWriter) {
        this.values = values;
        this.contexts = contexts;
        this.nativeWriter = nativeWriter;
    }

    void preflight(GridSnapshotReader.ParameterBinding binding, GridParameterWriter.Request request, Runnable check) {
        nativeWriter.requireSupported(binding.parameter);
        request.validateCurrent(values.parameterValue(binding));
        contexts.requireWritable(binding, null, check);
    }

    JsonObject set(
            GridSnapshotReader.ParameterBinding binding,
            GridParameterWriter.Request request,
            Runnable check,
            Runnable dispatch) {
        nativeWriter.requireSupported(binding.parameter);
        JsonObject before = values.parameterValue(binding);
        request.validateCurrent(before);
        GridParameterContext.Capture context = contexts.requireWritable(binding, null, check);
        before = values.parameterValue(binding);
        request.validateCurrent(before);
        contexts.requireWritable(binding, context, check);
        check.run();
        JsonElement previous = before.get("value");
        boolean changed = !request.matches(previous, request.value);
        if (changed) {
            dispatch.run();
            nativeWriter.set(binding.parameter, request.value.getAsString());
        }
        check.run();
        contexts.guard(context, binding.device, binding.graph, binding.module, binding.parameter, binding.kind, check);
        JsonObject current = values.parameterValue(binding);
        if (!request.matches(current.get("value"), request.value))
            throw Errors.of(
                    changed ? Reason.READBACK_MISMATCH : Reason.TARGET_CHANGED,
                    "Native string panel readback differs from the requested text; inspect state before another write.",
                    changed);
        check.run();
        JsonObject result = values.parameterResult(binding, current);
        Results.stamp(result, changed ? Outcome.APPLIED : Outcome.ALREADY_MATCHED, Verification.NATIVE_READBACK);
        result.add("previousValue", previous);
        result.add("requestedValue", request.value);
        result.addProperty("mutationDispatched", changed);
        result.addProperty("nativeMutation", changed);
        result.addProperty("nativeUndo", changed);
        result.addProperty(
                "nativeUndoScope",
                changed ? "native_undo_aware_string_panel_operation_not_verified_grouping" : "no_native_operation");
        result.addProperty("undoGroupingVerified", false);
        result.addProperty("engineAcknowledgement", false);
        result.addProperty(
                "safetyScope",
                "panel_common_context_guards_observed_nonrecording_nonautomation_not_native_transport_atomicity");
        return result;
    }
}
