package dev.bitwigagent;

import com.google.gson.*;

/** One panel CAS with its unique same-module TZj dependency guarded and reported explicitly. */
final class GridBooleanPanelWriter {
    interface Access {
        void requireSupported(Object parameter);
        void set(Object parameter, boolean value);
    }
    private final GridSnapshotReader values;
    private final GridParameterContext contexts;
    private final Access nativeWriter;

    GridBooleanPanelWriter(GridSnapshotReader values, GridParameterContext contexts, Access nativeWriter) {
        this.values = values; this.contexts = contexts; this.nativeWriter = nativeWriter;
    }

    JsonObject set(GridSnapshotReader.ParameterBinding binding, GridParameterWriter.Request request,
            Runnable check, Runnable dispatch) {
        nativeWriter.requireSupported(binding.parameter);
        GridSnapshotReader.ParameterBinding coupled = values.bindBipolarParameter(binding);
        JsonObject before = values.parameterValue(binding);
        request.validateCurrent(before);
        GridParameterContext.Capture context = contexts.requireWritable(binding, null, check);
        GridParameterContext.Capture coupledContext = contexts.requireWritable(coupled, null, check);
        values.guardBipolarParameter(binding, coupled);
        before = values.parameterValue(binding);
        request.validateCurrent(before);
        JsonObject coupledBefore = values.parameterValue(coupled);
        contexts.requireWritable(binding, context, check);
        contexts.requireWritable(coupled, coupledContext, check);
        values.guardBipolarParameter(binding, coupled);
        check.run();
        JsonElement previous = before.get("value");
        boolean changed = !request.matches(previous, request.value);
        if (changed) {
            dispatch.run();
            nativeWriter.set(binding.parameter, request.value.getAsBoolean());
        }
        check.run();
        values.guardBipolarParameter(binding, coupled);
        contexts.guard(context, binding.device, binding.graph, binding.module, binding.parameter, binding.kind, check);
        contexts.guard(coupledContext, coupled.device, coupled.graph, coupled.module, coupled.parameter, coupled.kind, check);
        JsonObject current = values.parameterValue(binding);
        if (!request.matches(current.get("value"), request.value))
            throw new RpcException(changed ? "grid_verification_failed" : "stale_context",
                    "Native boolean panel readback differs; inspect both panel and linked parameter before another write.",
                    changed ? "unknown" : "not_applied");
        JsonObject coupledAfter = values.parameterValue(coupled);
        values.guardBipolarParameter(binding, coupled);
        check.run();
        JsonObject result = values.parameterResult(binding, current);
        result.add("previousValue", previous); result.add("requestedValue", request.value);
        result.add("previousCoupledParameter", coupledBefore); result.add("coupledParameter", coupledAfter);
        result.addProperty("couplingScope", "unique_same_module_exact_TZj_reference_native_range_and_value_side_effects");
        result.addProperty("mutationDispatched", changed);
        result.addProperty("verification", changed ? "exact_native_boolean_panel_readback" : "already_matched");
        result.addProperty("nativeMutation", changed); result.addProperty("nativeUndo", changed);
        result.addProperty("nativeUndoScope", changed ? "native_undo_aware_boolean_panel_operation_not_verified_grouping" : "no_native_operation");
        result.addProperty("undoGroupingVerified", false); result.addProperty("engineAcknowledgement", false);
        result.addProperty("safetyScope", "panel_common_context_and_linked_scalar_guards_observed_stopped_not_native_transport_atomicity");
        return result;
    }
}
