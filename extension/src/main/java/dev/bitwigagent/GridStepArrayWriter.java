package dev.bitwigagent;

import com.google.gson.*;

/**
 * One step-array prefix CAS: same admission, context guards and exact readback as the string panel. The write replaces
 * the whole native array with the requested prefix patched in, so entries beyond the prefix stay unchanged.
 */
final class GridStepArrayWriter {
    interface Access {
        void requireSupported(Object parameter);
        /** The array as the model sees it (zero-padded/truncated to its declared length); a fresh copy. */
        float[] current(Object parameter);

        void set(Object parameter, float[] values);
    }

    private final GridSnapshotReader values;
    private final GridParameterContext contexts;
    private final Access nativeWriter;

    GridStepArrayWriter(GridSnapshotReader values, GridParameterContext contexts, Access nativeWriter) {
        this.values = values;
        this.contexts = contexts;
        this.nativeWriter = nativeWriter;
    }

    /** Every pre-dispatch check of set(); nothing is written. */
    void preflight(GridSnapshotReader.ParameterBinding binding, GridParameterWriter.Request request, Runnable check) {
        nativeWriter.requireSupported(binding.parameter);
        validate(values.parameterValue(binding), nativeWriter.current(binding.parameter), request);
        contexts.requireWritable(binding, null, check);
    }

    JsonObject set(
            GridSnapshotReader.ParameterBinding binding,
            GridParameterWriter.Request request,
            Runnable check,
            Runnable dispatch) {
        nativeWriter.requireSupported(binding.parameter);
        JsonObject before = values.parameterValue(binding);
        validate(before, nativeWriter.current(binding.parameter), request);
        GridParameterContext.Capture context = contexts.requireWritable(binding, null, check);
        before = values.parameterValue(binding);
        validate(before, nativeWriter.current(binding.parameter), request);
        contexts.requireWritable(binding, context, check);
        check.run();
        float[] live = nativeWriter.current(binding.parameter);
        int prefix = request.values.size();
        float[] next = live.clone();
        for (int i = 0; i < prefix; i++) next[i] = (float) request.values.get(i).getAsDouble();
        boolean changed = !same(live, next);
        if (changed) {
            dispatch.run();
            nativeWriter.set(binding.parameter, next);
        }
        check.run();
        contexts.guard(context, binding.device, binding.graph, binding.module, binding.parameter, binding.kind, check);
        JsonObject current = values.parameterValue(binding);
        float[] after = nativeWriter.current(binding.parameter);
        if (!same(after, next))
            throw new RpcException(
                    changed ? "grid_verification_failed" : "stale_context",
                    "Native step array readback differs from the requested values; inspect state before another write.",
                    changed ? "unknown" : "not_applied");
        check.run();
        JsonObject result = values.parameterResult(binding, current);
        result.add("previousValues", request.expectedValues);
        result.add("requestedValues", request.values);
        result.addProperty("mutationDispatched", changed);
        result.addProperty("verification", changed ? "exact_native_step_array_readback" : "already_matched");
        result.addProperty("nativeMutation", changed);
        result.addProperty("nativeUndo", changed);
        result.addProperty(
                "nativeUndoScope",
                changed ? "native_undo_aware_step_array_operation_not_verified_grouping" : "no_native_operation");
        result.addProperty("undoGroupingVerified", false);
        result.addProperty("engineAcknowledgement", false);
        result.addProperty(
                "safetyScope", "step_array_common_context_guards_observed_stopped_not_native_transport_atomicity");
        return result;
    }

    /**
     * Kind, then exact CAS on the first expectedValues.size() live entries (independent of the live STEPS value, so a
     * batch that also changes STEPS keeps its promise), then the prefix write bounds; stale before anything else.
     */
    private static void validate(JsonObject current, float[] live, GridParameterWriter.Request request) {
        if (!"steps".equals(request.kind) || !"steps".equals(current.get("kind").getAsString()))
            throw HostServices.failure(
                    "stale_context",
                    "Parameter kind differs from the expected precondition; reacquire grid.parameters.get.");
        JsonArray expected = request.expectedValues;
        if (expected.size() > live.length)
            throw HostServices.failure(
                    "stale_context",
                    "expectedValues is longer than the native array (" + live.length + "); reacquire grid.get.");
        for (int i = 0; i < expected.size(); i++)
            if ((float) expected.get(i).getAsDouble() != live[i])
                throw HostServices.failure(
                        "stale_context", "Step values differ from the expected precondition; reacquire grid.get.");
        if (request.values.size() > expected.size())
            throw HostServices.failure(
                    "invalid_params", "values must not be longer than expectedValues (" + expected.size() + ").");
    }

    /** Element-wise float equality (so -0.0 equals 0.0), same length. */
    private static boolean same(float[] a, float[] b) {
        if (a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) if (a[i] != b[i]) return false;
        return true;
    }
}
