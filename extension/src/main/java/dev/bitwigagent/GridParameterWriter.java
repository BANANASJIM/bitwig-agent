package dev.bitwigagent;

import com.google.gson.*;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.UUID;
import java.util.regex.Pattern;
import static dev.bitwigagent.HostServices.failure;

/** One raw CAS edit on the document thread; no transaction wrapper, retry or rollback. */
final class GridParameterWriter {
    private static final Pattern INTEGER_TOKEN = Pattern.compile("-?(0|[1-9][0-9]*)");
    interface Access {
        void requireSupported(Object parameter, GridSnapshotReader.Kind kind);
        void setRaw(Object parameter, double value);
    }
    private final GridSnapshotReader values;
    private final GridParameterContext contexts;
    private final Access nativeWriter;
    private final GridBooleanPanelWriter panels;

    GridParameterWriter(GridSnapshotReader values, GridParameterContext contexts, Access nativeWriter,
            GridBooleanPanelWriter panels) {
        this.values = values; this.contexts = contexts; this.nativeWriter = nativeWriter; this.panels = panels;
    }

    JsonObject set(Object proxy, UUID trackId, UUID moduleId, String parameterId,
            Request request, Runnable check, Runnable dispatch) {
        GridSnapshotReader.ParameterBinding binding = values.bindParameter(proxy, trackId, moduleId, parameterId, check);
        if (binding.kind == GridSnapshotReader.Kind.PANEL_BOOLEAN) {
            if (panels == null) throw failure("grid_unavailable", "Boolean panel write bindings are unavailable.");
            return panels.set(binding, request, check, dispatch);
        }
        nativeWriter.requireSupported(binding.parameter, binding.kind);
        JsonObject before = values.parameterValue(binding);
        request.validateCurrent(before);
        GridParameterContext.Capture context = contexts.requireWritable(binding, null, check);
        values.guardParameter(binding);
        // Read again after bounded traversal/context access, not a previous API's JSON as permission.
        before = values.parameterValue(binding);
        request.validateCurrent(before);
        values.guardParameter(binding);
        contexts.requireWritable(binding, context, check);
        check.run();
        JsonElement previous = before.get("value");
        boolean changed = !request.matches(previous, request.value);
        if (changed) {
            dispatch.run();
            nativeWriter.setRaw(binding.parameter, request.rawValue);
        }
        check.run();
        values.guardParameter(binding);
        contexts.guard(context, binding.device, binding.graph, binding.module, binding.parameter, binding.kind, check);
        JsonObject current = values.parameterValue(binding);
        if (!request.matches(current.get("value"), request.value))
            throw new RpcException(changed ? "grid_verification_failed" : "stale_context",
                    "Native raw model readback differs from the requested value; inspect state before another write.",
                    changed ? "unknown" : "not_applied");
        values.guardParameter(binding);
        check.run();
        JsonObject result = values.parameterResult(binding, current);
        result.add("previousValue", previous); result.add("requestedValue", request.value);
        result.addProperty("mutationDispatched", changed);
        result.addProperty("verification", changed ? "exact_native_model_readback" : "already_matched");
        result.addProperty("nativeMutation", changed);
        result.addProperty("nativeUndo", changed);
        result.addProperty("nativeUndoScope", changed ? "native_undo_aware_controller_operation_not_verified_grouping" : "no_native_operation");
        result.addProperty("undoGroupingVerified", false);
        result.addProperty("engineAcknowledgement", false);
        result.addProperty("safetyScope", "observed_stopped_nonrecording_nonautomation_not_native_transport_atomicity");
        return result;
    }

    static final class Request {
        final String kind;
        final JsonPrimitive expected, value;
        final double rawValue;
        private Request(String kind, JsonPrimitive expected, JsonPrimitive value) {
            this.kind = kind; this.expected = expected; this.value = value;
            rawValue = kind.equals("boolean") ? (value.getAsBoolean() ? 1.0 : 0.0) : value.getAsDouble();
        }
        static Request parse(JsonObject params) {
            String kind = Params.string(params, "expectedKind");
            if (!kind.equals("float") && !kind.equals("integer") && !kind.equals("enum") && !kind.equals("boolean"))
                throw failure("invalid_params", "expectedKind must be float, integer, enum or boolean.");
            JsonPrimitive expected = typed(params.get("expectedValue"), kind, "expectedValue");
            JsonPrimitive value = typed(params.get("value"), kind, "value");
            return new Request(kind, expected, value);
        }
        private static JsonPrimitive typed(JsonElement element, String kind, String field) {
            if (element == null || !element.isJsonPrimitive()) throw invalid(field, kind);
            JsonPrimitive value = element.getAsJsonPrimitive();
            if (kind.equals("boolean")) {
                if (!value.isBoolean()) throw invalid(field, kind);
                return value;
            }
            if (!value.isNumber()) throw invalid(field, kind);
            String token = value.getAsString();
            boolean integerToken = INTEGER_TOKEN.matcher(token).matches();
            if (!kind.equals("float")) {
                if (!integerToken) throw invalid(field, kind);
                try { Integer.parseInt(token); }
                catch (NumberFormatException error) { throw invalid(field, kind); }
            } else {
                double number = value.getAsDouble();
                if (!Double.isFinite(number)) throw invalid(field, kind);
                // JSON integer tokens retain their precision across the client; never silently round them.
                if (integerToken && new BigDecimal(number).toBigIntegerExact().compareTo(new BigInteger(token)) != 0)
                    throw failure("invalid_params", field + " is not exactly representable as a native raw double.");
            }
            return value;
        }
        private static RpcException invalid(String field, String kind) {
            return failure("invalid_params", field + " must be " + (kind.equals("boolean") ? "a JSON boolean."
                    : kind.equals("float") ? "a finite native-representable JSON number."
                    : "a signed 32-bit JSON integer token (not a boolean or fractional number)."));
        }
        void validateCurrent(JsonObject current) {
            if (!kind.equals(current.get("kind").getAsString()) || !matches(current.get("value"), expected))
                throw failure("stale_context", "Parameter kind or exact raw value differs from the expected precondition; reacquire grid.parameters.get.");
            if (kind.equals("float") || kind.equals("integer")) {
                double min = current.get("minimum").getAsDouble(), max = current.get("maximum").getAsDouble();
                if (rawValue < min || rawValue > max)
                    throw failure("invalid_params", "Requested raw value is outside the native parameter range; no clamping is performed.");
            } else if (kind.equals("enum")) {
                if (!current.get("valueInOptions").getAsBoolean())
                    throw failure("grid_unavailable", "Current native enum value is not in its actual option set.");
                boolean found = false;
                for (JsonElement option : current.getAsJsonArray("options"))
                    if (option.getAsJsonObject().get("value").getAsInt() == value.getAsInt()) { found = true; break; }
                if (!found) throw failure("invalid_params", "Requested enum value must be an actual listed option value, not an ordinal.");
            }
        }
        boolean matches(JsonElement actual, JsonElement wanted) {
            if (kind.equals("boolean")) return actual.getAsBoolean() == wanted.getAsBoolean();
            return actual.getAsDouble() == wanted.getAsDouble();
        }
    }
}
