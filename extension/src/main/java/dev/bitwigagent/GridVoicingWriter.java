package dev.bitwigagent;

import com.google.gson.*;
import java.util.*;
import java.util.regex.Pattern;

/** Poly Grid device voicing: guarded read and compare-and-set with exact readback, one model per device. */
final class GridVoicingWriter {
    interface Access {
        /** The device's voicing model, or null when it has none (Note Grid, FX Grid). */
        Object model(Object device);
        /** Every field as a JSON primitive (voices, voiceStacking, monoMode, ...). */
        JsonObject read(Object voicing);

        void set(Object voicing, String field, JsonElement value);
    }
    /** Field order is also the native write order. */
    static final List<String> FIELDS = List.of(
            "voices",
            "voiceStacking",
            "monoMode",
            "retriggerMode",
            "notePriority",
            "legatoGlide",
            "stealSameKey",
            "allowOverlappingNotes");

    static final Map<String, Set<String>> ENUMS = Map.of(
            "monoMode", Set.of("true_mono", "digi_mono"),
            "retriggerMode", Set.of("never", "on_note_on", "always"),
            "notePriority", Set.of("last", "high", "low"));
    static final int MAX_VOICES = 64, MAX_STACKING = 16;
    private static final Pattern INTEGER_TOKEN = Pattern.compile("-?(0|[1-9][0-9]*)");

    private final GridSnapshotReader values;
    private final GridParameterContext contexts;
    private final Access nativeAccess;
    private final boolean writable;

    GridVoicingWriter(GridSnapshotReader values, GridParameterContext contexts, Access nativeAccess, boolean writable) {
        this.values = values;
        this.contexts = contexts;
        this.nativeAccess = nativeAccess;
        this.writable = writable;
    }

    JsonObject get(Object proxy, java.util.UUID trackId, Runnable check) {
        GridSnapshotReader.NativeTarget target = values.bindTarget(proxy, trackId, check);
        Object model = nativeAccess.model(target.target);
        JsonObject result = HostServices.observed();
        if (model == null) {
            result.addProperty("supported", false);
            result.addProperty(
                    "reason", "no_voicing_model: only Poly Grid has device voicing; Note Grid and FX Grid have none");
            values.guardTarget(target, proxy, trackId);
            check.run();
            return finish(result);
        }
        values.requireModelThread(model);
        GridParameterContext.Capture context = contexts.captureVoicing(target.target, target.graph, model, check);
        JsonObject current = nativeAccess.read(model);
        values.guardTarget(target, proxy, trackId);
        contexts.guardVoicing(context, target.target, target.graph, model, check);
        check.run();
        result.addProperty("supported", true);
        result.add("voicing", current);
        result.addProperty("mode", mode(current));
        result.add("editContext", context.json);
        return finish(result);
    }

    JsonObject set(Object proxy, java.util.UUID trackId, Request request, Runnable check, Runnable dispatch) {
        GridSnapshotReader.NativeTarget target = values.bindTarget(proxy, trackId, check);
        Object model = nativeAccess.model(target.target);
        if (model == null)
            throw Errors.notApplied(
                    ErrorCode.UNSUPPORTED_SCOPE, "This Grid device has no voicing model (only Poly Grid does).");
        if (!writable) throw Errors.notApplied(ErrorCode.GRID_UNAVAILABLE, "Voicing write bindings are unavailable.");
        values.requireModelThread(model);
        JsonObject before = nativeAccess.read(model);
        request.validateCurrent(before);
        GridParameterContext.Capture context =
                contexts.requireVoicingWritable(target.target, target.graph, model, null, check);
        before = nativeAccess.read(model);
        request.validateCurrent(before);
        values.guardTarget(target, proxy, trackId);
        contexts.requireVoicingWritable(target.target, target.graph, model, context, check);
        check.run();
        List<String> changing = new ArrayList<>();
        for (String field : FIELDS)
            if (request.set.containsKey(field) && !request.set.get(field).equals(before.get(field)))
                changing.add(field);
        boolean changed = !changing.isEmpty();
        if (changed) {
            dispatch.run();
            for (String field : changing) nativeAccess.set(model, field, request.set.get(field));
        }
        check.run();
        values.guardTarget(target, proxy, trackId);
        contexts.guardVoicing(context, target.target, target.graph, model, check);
        JsonObject current = nativeAccess.read(model);
        for (Map.Entry<String, JsonElement> wanted : request.set.entrySet())
            if (!wanted.getValue().equals(current.get(wanted.getKey())))
                throw Errors.of(
                        changed ? ErrorCode.GRID_VERIFICATION_FAILED : ErrorCode.STALE_CONTEXT,
                        "Native voicing readback differs from the requested " + wanted.getKey()
                                + "; inspect state before another write.",
                        changed ? Outcome.UNKNOWN : Outcome.NOT_APPLIED);
        check.run();
        JsonObject result = HostServices.observed();
        result.addProperty("supported", true);
        result.add("voicing", current);
        result.addProperty("mode", mode(current));
        result.add("previousVoicing", before);
        // Fields the request did not name but the native change moved (e.g. a mono-mode side effect on voices).
        JsonArray side = new JsonArray();
        for (String field : FIELDS)
            if (!request.set.containsKey(field) && !before.get(field).equals(current.get(field))) side.add(field);
        result.add("changedUnrequested", side);
        result.addProperty("mutationDispatched", changed);
        result.addProperty("verification", changed ? "exact_native_voicing_readback" : "already_matched");
        result.addProperty("nativeMutation", changed);
        result.addProperty("nativeUndo", changed);
        result.addProperty(
                "nativeUndoScope",
                changed ? "native_undo_aware_voicing_operations_not_verified_grouping" : "no_native_operation");
        result.addProperty("undoGroupingVerified", false);
        result.addProperty("engineAcknowledgement", false);
        result.addProperty(
                "safetyScope", "voicing_common_context_guards_observed_stopped_not_native_transport_atomicity");
        return result;
    }

    private static JsonObject finish(JsonObject result) {
        result.addProperty("nativeMutation", false);
        result.addProperty("nativeUndo", false);
        return result;
    }

    /** poly with several voices; with one voice the stored mono mode is what the device plays. */
    private static String mode(JsonObject voicing) {
        return voicing.get("voices").getAsInt() > 1
                ? "poly"
                : voicing.get("monoMode").getAsString();
    }

    /** grid.voicing.set: expected (exact current values, a superset of set's fields) and set (new values). */
    static final class Request {
        final Map<String, JsonElement> expected, set;

        private Request(Map<String, JsonElement> expected, Map<String, JsonElement> set) {
            this.expected = expected;
            this.set = set;
        }

        static Request parse(JsonObject params) {
            Map<String, JsonElement> expected = fields(params.get("expected"), "expected");
            Map<String, JsonElement> set = fields(params.get("set"), "set");
            if (set.isEmpty())
                throw Errors.notApplied(ErrorCode.INVALID_PARAMS, "set must name at least one voicing field.");
            for (String key : set.keySet())
                if (!expected.containsKey(key))
                    throw Errors.notApplied(
                            ErrorCode.INVALID_PARAMS,
                            "expected must also give the current " + key + " (compare-and-set).");
            return new Request(expected, set);
        }

        private static Map<String, JsonElement> fields(JsonElement element, String label) {
            if (element == null || !element.isJsonObject())
                throw Errors.notApplied(ErrorCode.INVALID_PARAMS, label + " must be an object of voicing fields.");
            Map<String, JsonElement> result = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry :
                    element.getAsJsonObject().entrySet()) {
                String key = entry.getKey();
                if (!FIELDS.contains(key))
                    throw Errors.notApplied(ErrorCode.INVALID_PARAMS, "Unknown voicing field in " + label + ": " + key);
                result.put(key, value(key, entry.getValue(), label));
            }
            return result;
        }

        private static JsonElement value(String key, JsonElement element, String label) {
            String where = label + "." + key;
            if (!element.isJsonPrimitive())
                throw Errors.notApplied(ErrorCode.INVALID_PARAMS, where + " must be a JSON primitive.");
            JsonPrimitive value = element.getAsJsonPrimitive();
            if (key.equals("voices") || key.equals("voiceStacking")) {
                int max = key.equals("voices") ? MAX_VOICES : MAX_STACKING;
                if (!value.isNumber()
                        || !INTEGER_TOKEN.matcher(value.getAsString()).matches())
                    throw Errors.notApplied(ErrorCode.INVALID_PARAMS, where + " must be an integer token.");
                int number;
                try {
                    number = Integer.parseInt(value.getAsString());
                } catch (NumberFormatException error) {
                    throw Errors.notApplied(ErrorCode.INVALID_PARAMS, where + " must be an integer token.");
                }
                if (number < 1 || number > max)
                    throw Errors.notApplied(ErrorCode.INVALID_PARAMS, where + " must be within 1.." + max + ".");
                return new JsonPrimitive(number);
            }
            if (ENUMS.containsKey(key)) {
                if (!value.isString() || !ENUMS.get(key).contains(value.getAsString()))
                    throw Errors.notApplied(
                            ErrorCode.INVALID_PARAMS, where + " must be one of " + new TreeSet<>(ENUMS.get(key)) + ".");
                return value;
            }
            if (!value.isBoolean())
                throw Errors.notApplied(ErrorCode.INVALID_PARAMS, where + " must be a JSON boolean.");
            return value;
        }
        /** Every expected field must equal the live value exactly. */
        void validateCurrent(JsonObject current) {
            for (Map.Entry<String, JsonElement> entry : expected.entrySet())
                if (!entry.getValue().equals(current.get(entry.getKey())))
                    throw Errors.notApplied(
                            ErrorCode.STALE_CONTEXT,
                            "Voicing " + entry.getKey()
                                    + " differs from the expected precondition; reacquire grid.voicing.get.");
        }
    }
}
