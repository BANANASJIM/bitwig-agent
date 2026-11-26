package dev.bitwigagent;

import com.google.gson.*;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.UUID;
import java.util.regex.Pattern;

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
    private final GridStringPanelWriter strings;
    private final GridStepArrayWriter steps;

    GridParameterWriter(
            GridSnapshotReader values,
            GridParameterContext contexts,
            Access nativeWriter,
            GridBooleanPanelWriter panels,
            GridStringPanelWriter strings) {
        this(values, contexts, nativeWriter, panels, strings, null);
    }

    GridParameterWriter(
            GridSnapshotReader values,
            GridParameterContext contexts,
            Access nativeWriter,
            GridBooleanPanelWriter panels,
            GridStringPanelWriter strings,
            GridStepArrayWriter steps) {
        this.values = values;
        this.contexts = contexts;
        this.nativeWriter = nativeWriter;
        this.panels = panels;
        this.strings = strings;
        this.steps = steps;
    }

    JsonObject set(
            Object proxy,
            UUID trackId,
            UUID moduleId,
            String parameterId,
            Request request,
            Runnable check,
            Runnable dispatch) {
        GridSnapshotReader.ParameterBinding binding =
                values.bindParameter(proxy, trackId, moduleId, parameterId, request.full, check);
        if (binding.kind == GridSnapshotReader.Kind.STEP_ARRAY) {
            if (steps == null)
                throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "Step array write bindings are unavailable.");
            return steps.set(binding, request, check, dispatch);
        }
        if (binding.kind == GridSnapshotReader.Kind.PANEL_STRING) {
            if (strings == null)
                throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "String panel write bindings are unavailable.");
            return strings.set(binding, request, check, dispatch);
        }
        if (binding.kind == GridSnapshotReader.Kind.PANEL_BOOLEAN) {
            if (panels == null)
                throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "Boolean panel write bindings are unavailable.");
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
            throw Errors.of(
                    changed ? Reason.READBACK_MISMATCH : Reason.STALE_CONTEXT,
                    "Native raw model readback differs from the requested value; inspect state before another write.",
                    changed);
        values.guardParameter(binding);
        check.run();
        JsonObject result = values.parameterResult(binding, current);
        result.addProperty("outcome", (changed ? Outcome.APPLIED : Outcome.ALREADY_MATCHED).wire());
        result.add("previousValue", previous);
        result.add("requestedValue", request.value);
        if (request.display != null) {
            result.addProperty("requestedDisplay", request.display);
            result.addProperty("displayMismatch", GridDisplayUnits.mismatch(current, request.display));
        }
        result.addProperty("mutationDispatched", changed);
        result.addProperty("verification", "exact_native_model_readback");
        result.addProperty("nativeMutation", changed);
        result.addProperty("nativeUndo", changed);
        result.addProperty(
                "nativeUndoScope",
                changed ? "native_undo_aware_controller_operation_not_verified_grouping" : "no_native_operation");
        result.addProperty("undoGroupingVerified", false);
        result.addProperty("engineAcknowledgement", false);
        result.addProperty("safetyScope", "observed_nonrecording_nonautomation_not_native_transport_atomicity");
        return result;
    }

    static final class Entry {
        final UUID moduleId;
        final String parameterId;
        final Request request;

        Entry(UUID moduleId, String parameterId, Request request) {
            this.moduleId = moduleId;
            this.parameterId = parameterId;
            this.request = request;
        }
    }

    /** Live CAS and range/option checks only; nothing is dispatched. */
    private void preflight(Object proxy, UUID trackId, Entry entry, Runnable check) {
        GridSnapshotReader.ParameterBinding binding =
                values.bindParameter(proxy, trackId, entry.moduleId, entry.parameterId, entry.request.full, check);
        if (binding.kind == GridSnapshotReader.Kind.STEP_ARRAY) {
            if (steps == null)
                throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "Step array write bindings are unavailable.");
            steps.preflight(binding, entry.request, check);
            return;
        }
        if (binding.kind == GridSnapshotReader.Kind.PANEL_STRING) {
            if (strings == null)
                throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "String panel write bindings are unavailable.");
            strings.preflight(binding, entry.request, check);
            return;
        }
        if (binding.kind == GridSnapshotReader.Kind.PANEL_BOOLEAN) {
            if (panels == null)
                throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "Boolean panel write bindings are unavailable.");
            panels.preflight(binding, entry.request, check);
            return;
        } else {
            nativeWriter.requireSupported(binding.parameter, binding.kind);
            contexts.requireWritable(binding, null, check);
        }
        entry.request.validateCurrent(values.parameterValue(binding));
        values.guardParameter(binding);
    }

    /**
     * Same-device batch: every CAS first (any failure writes nothing), then in-order dispatch that stops at the first
     * failure or unknown. Each write is the ordinary single guarded CAS; nothing is retried or rolled back.
     */
    JsonObject setAll(Object proxy, UUID trackId, java.util.List<Entry> entries, Runnable check, Runnable dispatch) {
        StringBuilder rejected = new StringBuilder();
        Reason code = null;
        int failures = 0;
        for (int i = 0; i < entries.size(); i++) {
            try {
                preflight(proxy, trackId, entries.get(i), check);
            } catch (RpcException error) {
                if (code == null) code = error.reason;
                if (failures++ < 8)
                    rejected.append(rejected.length() == 0 ? "" : "; ")
                            .append('#')
                            .append(i)
                            .append(' ')
                            .append(error.reason.wire())
                            .append(": ")
                            .append(error.getMessage());
            }
        }
        if (failures > 0)
            throw Errors.notApplied(
                    code,
                    "No parameter was written; " + failures + " of " + entries.size() + " writes failed their checks: "
                            + rejected + (failures > 8 ? "; ..." : ""));
        JsonArray writes = new JsonArray();
        boolean stopped = false;
        int written = 0;
        for (Entry entry : entries) {
            JsonObject item = new JsonObject();
            item.addProperty("moduleId", entry.moduleId.toString());
            item.addProperty("parameterId", entry.parameterId);
            writes.add(item);
            if (stopped) {
                GridDiff.notApplied(item, Reason.NOT_REACHED);
                continue;
            }
            boolean[] sent = {false};
            try {
                JsonObject one = set(proxy, trackId, entry.moduleId, entry.parameterId, entry.request, check, () -> {
                    dispatch.run();
                    sent[0] = true;
                });
                item.add("outcome", one.get("outcome"));
                if (one.has("previousValues")) {
                    item.add("previousValues", one.get("previousValues"));
                    item.add("values", one.getAsJsonObject("parameter").get("values"));
                } else {
                    item.add("previousValue", one.get("previousValue"));
                    item.add("value", one.getAsJsonObject("parameter").get("value"));
                }
                JsonElement display = one.getAsJsonObject("parameter").get("display");
                if (display != null && !display.isJsonNull()) item.add("display", display);
                if (one.has("requestedDisplay"))
                    for (String key : new String[] {"requestedDisplay", "displayMismatch"}) item.add(key, one.get(key));
                for (String key : new String[] {"previousCoupledParameter", "coupledParameter"})
                    if (one.has(key)) item.add(key, one.get(key));
                if (one.get("mutationDispatched").getAsBoolean()) written++;
            } catch (RuntimeException error) {
                // Any failure after this entry's native dispatch may have landed, whatever the error says.
                boolean unknown = sent[0] || error instanceof RpcException && ((RpcException) error).unknown;
                if (written == 0 && !unknown) throw error;
                GridDiff.failed(item, error, unknown);
                stopped = true;
            }
        }
        JsonObject result = Results.outcome(Results.worst(writes));
        result.addProperty("written", written);
        result.add("writes", writes);
        return result;
    }

    static final class Request {
        final String kind;
        final JsonPrimitive expected;
        /** Display-text request; value/rawValue are then resolved from the live row by validateCurrent. */
        final String display;

        JsonPrimitive value;
        double rawValue;
        /** Step arrays: the exposed prefix expected now, the prefix to write, and whether the prefix is the full array. */
        final JsonArray expectedValues, values;

        final boolean full;

        private Request(String kind, JsonPrimitive expected, JsonPrimitive value, String display) {
            this.kind = kind;
            this.expected = expected;
            this.display = display;
            this.expectedValues = null;
            this.values = null;
            this.full = false;
            if (value != null) setValue(value);
        }

        private Request(JsonArray expectedValues, JsonArray values, boolean full) {
            this.kind = "steps";
            this.expected = null;
            this.display = null;
            this.expectedValues = expectedValues;
            this.values = values;
            this.full = full;
        }

        private void setValue(JsonPrimitive value) {
            this.value = value;
            if (!kind.equals("string"))
                rawValue = kind.equals("boolean") ? (value.getAsBoolean() ? 1.0 : 0.0) : value.getAsDouble();
        }

        static Request parse(JsonObject params) {
            String kind = Params.string(params, "expectedKind");
            if (kind.equals("steps")) return parseSteps(params);
            if (!kind.equals("float")
                    && !kind.equals("integer")
                    && !kind.equals("enum")
                    && !kind.equals("boolean")
                    && !kind.equals("string"))
                throw Errors.notApplied(
                        Reason.INVALID_PARAMS, "expectedKind must be float, integer, enum, boolean, string or steps.");
            if (params.has("full") || params.has("expectedValues") || params.has("values"))
                throw Errors.notApplied(
                        Reason.INVALID_PARAMS, "full, expectedValues and values need expectedKind steps.");
            JsonPrimitive expected = typed(params.get("expectedValue"), kind, "expectedValue");
            if (params.has("value") == params.has("display"))
                throw Errors.notApplied(
                        Reason.INVALID_PARAMS,
                        "Supply exactly one of value (raw) or display (text such as \"1.2 kHz\").");
            if (params.has("display")) {
                JsonElement text = params.get("display");
                if (!kind.equals("float")
                        || !text.isJsonPrimitive()
                        || !text.getAsJsonPrimitive().isString()
                        || text.getAsString().isEmpty()
                        || text.getAsString().length() > 64)
                    throw Errors.notApplied(
                            Reason.INVALID_PARAMS,
                            "display must be a string of 1..64 characters and needs expectedKind float.");
                return new Request(kind, expected, null, text.getAsString());
            }
            return new Request(kind, expected, typed(params.get("value"), kind, "value"), null);
        }

        static final int MAX_STEPS = GridSnapshotReader.STEP_FULL;

        private static Request parseSteps(JsonObject params) {
            if (params.has("expectedValue") || params.has("value") || params.has("display"))
                throw Errors.notApplied(
                        Reason.INVALID_PARAMS,
                        "Step arrays take expectedValues and values, not expectedValue, value or display.");
            JsonArray expected = steps(params.get("expectedValues"), "expectedValues", false);
            JsonArray written = steps(params.get("values"), "values", true);
            if (written.size() > expected.size())
                throw Errors.notApplied(Reason.INVALID_PARAMS, "values must not be longer than expectedValues.");
            boolean full = false;
            if (params.has("full")) {
                JsonElement flag = params.get("full");
                if (!flag.isJsonPrimitive() || !flag.getAsJsonPrimitive().isBoolean())
                    throw Errors.notApplied(Reason.INVALID_PARAMS, "full must be a JSON boolean.");
                full = flag.getAsBoolean();
            }
            return new Request(expected, written, full);
        }

        private static JsonArray steps(JsonElement element, String field, boolean bounded) {
            if (element == null
                    || !element.isJsonArray()
                    || element.getAsJsonArray().size() < 1
                    || element.getAsJsonArray().size() > MAX_STEPS)
                throw Errors.notApplied(
                        Reason.INVALID_PARAMS, field + " must be an array of 1.." + MAX_STEPS + " finite numbers.");
            for (JsonElement item : element.getAsJsonArray()) {
                if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isNumber())
                    throw Errors.notApplied(
                            Reason.INVALID_PARAMS, field + " must be an array of 1.." + MAX_STEPS + " finite numbers.");
                double number = item.getAsDouble();
                if (!Double.isFinite(number))
                    throw Errors.notApplied(
                            Reason.INVALID_PARAMS, field + " must be an array of 1.." + MAX_STEPS + " finite numbers.");
                if (bounded && (number < GridSnapshotReader.STEP_MIN || number > GridSnapshotReader.STEP_MAX))
                    throw Errors.notApplied(
                            Reason.INVALID_PARAMS,
                            field + " entries must be within the sanity bound -128..128; no clamping is performed.");
            }
            return element.getAsJsonArray();
        }

        private static JsonPrimitive typed(JsonElement element, String kind, String field) {
            if (element == null || !element.isJsonPrimitive()) throw invalid(field, kind);
            JsonPrimitive value = element.getAsJsonPrimitive();
            if (kind.equals("string")) {
                if (!value.isString() || value.getAsString().length() > GridSnapshotReader.MAX_TEXT)
                    throw invalid(field, kind);
                return value;
            }
            if (kind.equals("boolean")) {
                if (!value.isBoolean()) throw invalid(field, kind);
                return value;
            }
            if (!value.isNumber()) throw invalid(field, kind);
            String token = value.getAsString();
            boolean integerToken = INTEGER_TOKEN.matcher(token).matches();
            if (!kind.equals("float")) {
                if (!integerToken) throw invalid(field, kind);
                try {
                    Integer.parseInt(token);
                } catch (NumberFormatException error) {
                    throw invalid(field, kind);
                }
            } else {
                double number = value.getAsDouble();
                if (!Double.isFinite(number)) throw invalid(field, kind);
                // JSON integer tokens retain their precision across the client; never silently round them.
                if (integerToken && new BigDecimal(number).toBigIntegerExact().compareTo(new BigInteger(token)) != 0)
                    throw Errors.notApplied(
                            Reason.INVALID_PARAMS, field + " is not exactly representable as a native raw double.");
            }
            return value;
        }

        private static RpcException invalid(String field, String kind) {
            return Errors.notApplied(
                    Reason.INVALID_PARAMS,
                    field + " must be "
                            + (kind.equals("string")
                                    ? "a JSON string of at most 1024 UTF-16 units."
                                    : kind.equals("boolean")
                                            ? "a JSON boolean."
                                            : kind.equals("float")
                                                    ? "a finite native-representable JSON number."
                                                    : "a signed 32-bit JSON integer token (not a boolean or fractional number)."));
        }

        void validateCurrent(JsonObject current) {
            if (!kind.equals(current.get("kind").getAsString()) || !matches(current.get("value"), expected))
                throw Errors.notApplied(
                        Reason.STALE_CONTEXT,
                        "Parameter kind or exact raw value differs from the expected precondition; reacquire grid.parameters.get.");
            if (display != null) setValue(new JsonPrimitive(GridDisplayUnits.toRaw(current, display)));
            if (kind.equals("float") || kind.equals("integer")) {
                double min = current.get("minimum").getAsDouble(),
                        max = current.get("maximum").getAsDouble();
                if (rawValue < min || rawValue > max)
                    throw Errors.notApplied(
                            Reason.INVALID_PARAMS,
                            "Requested raw value is outside the native parameter range; no clamping is performed.");
            } else if (kind.equals("enum")) {
                if (!current.get("valueInOptions").getAsBoolean())
                    throw Errors.notApplied(
                            Reason.NATIVE_UNAVAILABLE, "Current native enum value is not in its actual option set.");
                boolean found = false;
                for (JsonElement option : current.getAsJsonArray("options"))
                    if (option.getAsJsonObject().get("value").getAsInt() == value.getAsInt()) {
                        found = true;
                        break;
                    }
                if (!found)
                    throw Errors.notApplied(
                            Reason.INVALID_PARAMS,
                            "Requested enum value must be an actual listed option value, not an ordinal.");
            }
        }

        boolean matches(JsonElement actual, JsonElement wanted) {
            if (kind.equals("string")) return actual.getAsString().equals(wanted.getAsString());
            if (kind.equals("boolean")) return actual.getAsBoolean() == wanted.getAsBoolean();
            return actual.getAsDouble() == wanted.getAsDouble();
        }
    }
}
