package dev.bitwigagent;

import com.bitwig.extension.callback.BooleanValueChangedCallback;
import com.bitwig.extension.callback.DoubleValueChangedCallback;
import com.bitwig.extension.callback.EnumValueChangedCallback;
import com.bitwig.extension.callback.StringValueChangedCallback;
import com.bitwig.extension.controller.api.*;
import com.google.gson.*;
import java.util.*;

/** Absolute transport controls; recording and automation modes are read-only safety inputs. */
final class TransportModule implements HostModule {
    static final Set<String> METHODS = Set.of("transport.get", "transport.set");
    private static final String[] ORDER = {
        "tempoBpm",
        "timeSignature",
        "positionBeats",
        "metronome",
        "metronomeVolumeNormalized",
        "loopStartBeats",
        "loopDurationBeats",
        "loopEnabled",
        "punchInEnabled",
        "punchOutEnabled",
        "preRoll",
        "defaultLaunchQuantization",
        "recordQuantizationGrid",
        "playing"
    };
    /** Host-listed enums: the API's own option list validates them at dispatch. */
    private static final Set<String> ENUM_FIELDS =
            Set.of("preRoll", "defaultLaunchQuantization", "recordQuantizationGrid");

    private static final Set<String> FIELDS = Set.of(ORDER);
    /** Fields every host reports at startup; a newer field that is not (yet) observed reads null instead of blocking the rest. */
    private static final Set<String> BASE = Set.of(
            "tempoBpm",
            "timeSignature",
            "positionBeats",
            "metronome",
            "loopStartBeats",
            "loopDurationBeats",
            "loopEnabled",
            "playing");

    private final HostServices s;
    private final Map<String, Sample> values = new LinkedHashMap<>();
    private final Map<String, SettableEnumValue> enums = new LinkedHashMap<>();

    TransportModule(HostServices services) {
        s = services;
        observe("playing", s.transport.isPlaying());
        Sample tempo = sample("tempoBpm");
        s.transport.tempo().value().addRawValueObserver((DoubleValueChangedCallback) v -> tempo.number(v));
        Sample signature = sample("timeSignature");
        s.transport.timeSignature().markInterested();
        s.transport.timeSignature().addValueObserver((StringValueChangedCallback)
                v -> signature.set(new JsonPrimitive((String) v)));
        observe("positionBeats", s.transport.playPosition());
        observe("metronome", s.transport.isMetronomeEnabled());
        observe("loopEnabled", s.transport.isArrangerLoopEnabled());
        observe("loopStartBeats", s.transport.arrangerLoopStart());
        observe("loopDurationBeats", s.transport.arrangerLoopDuration());
        Sample volume = sample("metronomeVolumeNormalized");
        s.transport.metronomeVolume().addValueObserver((DoubleValueChangedCallback) volume::number);
        observe("punchInEnabled", s.transport.isPunchInEnabled());
        observe("punchOutEnabled", s.transport.isPunchOutEnabled());
        observe("preRoll", s.transport.preRoll());
        observe("defaultLaunchQuantization", s.transport.defaultLaunchQuantization());
        observe("recordQuantizationGrid", s.application.recordQuantizationGrid());
    }

    @Override
    public Set<String> methods() {
        return METHODS;
    }

    @Override
    public void validate(String method, JsonObject params) {
        validateParams(method, params);
    }

    static void validateParams(String method, JsonObject p) {
        if (method.equals("transport.get")) {
            Params.fields(p, Set.of(), Set.of());
            return;
        }
        if (!method.equals("transport.set"))
            throw Errors.notApplied(ErrorCode.UNKNOWN_METHOD, "Unknown transport method.");
        Params.fields(p, Set.of(), FIELDS);
        if (p.size() == 0)
            throw Errors.notApplied(ErrorCode.INVALID_PARAMS, "At least one transport field is required.");
        for (String key : List.of("playing", "metronome", "loopEnabled", "punchInEnabled", "punchOutEnabled"))
            if (p.has(key)) Params.bool(p, key);
        if (p.has("metronomeVolumeNormalized")) Params.number(p, "metronomeVolumeNormalized", 0, 1);
        for (String key : ENUM_FIELDS) if (p.has(key)) Params.string(p, key);
        // The installed Maschine JAM/MainKnobControl.js uses raw BPM 20..666.
        // RangedValue Javadoc leaves raw limits undefined: these are toolkit-supported bounds.
        if (p.has("tempoBpm")) Params.number(p, "tempoBpm", 20, 666);
        for (String key : List.of("positionBeats", "loopStartBeats", "loopDurationBeats")) {
            if (p.has(key)) Params.number(p, key, 0, Double.MAX_VALUE);
        }
        if (p.has("timeSignature") && !validSignature(Params.string(p, "timeSignature")))
            throw Errors.notApplied(
                    ErrorCode.INVALID_PARAMS,
                    "timeSignature must be numerator/denominator: numerator 1..32, denominator 1, 2, 4, 8, 16 or 32.");
        if (p.has("loopDurationBeats") && p.get("loopDurationBeats").getAsDouble() == 0)
            throw Errors.notApplied(ErrorCode.INVALID_PARAMS, "loopDurationBeats must be positive.");
        if (p.has("loopStartBeats")
                && p.has("loopDurationBeats")
                && !Double.isFinite(p.get("loopStartBeats").getAsDouble()
                        + p.get("loopDurationBeats").getAsDouble()))
            throw Errors.notApplied(ErrorCode.INVALID_PARAMS, "Loop end must be finite.");
    }

    static boolean validSignature(String text) {
        if (!text.matches("[1-9][0-9]?/(1|2|4|8|16|32)")) return false;
        return Integer.parseInt(text.substring(0, text.indexOf('/'))) <= 32;
    }

    @Override
    public void dispatch(HostOperation op, String method, JsonObject p) {
        s.check(op);
        if (method.equals("transport.get")) {
            op.result.complete(snapshot());
            return;
        }
        for (String key : BASE) require(key);
        for (String key : p.keySet()) require(key);
        for (String key : p.keySet()) guard(key, p.get(key));
        for (Map.Entry<String, SettableEnumValue> entry : enums.entrySet())
            if (p.has(entry.getKey()))
                EnumOptions.require(
                        entry.getKey(),
                        entry.getValue(),
                        p.get(entry.getKey()).getAsString(),
                        observedString(entry.getKey()));
        double start = p.has("loopStartBeats")
                ? p.get("loopStartBeats").getAsDouble()
                : values.get("loopStartBeats").value.getAsDouble();
        double duration = p.has("loopDurationBeats")
                ? p.get("loopDurationBeats").getAsDouble()
                : values.get("loopDurationBeats").value.getAsDouble();
        if (!Double.isFinite(start + duration))
            throw Errors.notApplied(ErrorCode.INVALID_PARAMS, "Loop end must be finite.");
        Map<String, Long> writes = new LinkedHashMap<>();
        long until = s.observeUntil(op);
        boolean stopping = p.has("playing") && !p.get("playing").getAsBoolean();
        try {
            if (stopping) write(op, p, "playing", writes);
        } catch (RpcException error) {
            if (!op.dispatched) throw error;
            finish(op, p, writes, error.code);
            return;
        }
        if (stopping && p.size() > 1) awaitStop(op, p, writes, until);
        else writeRemaining(op, p, writes, until);
    }

    private void awaitStop(HostOperation op, JsonObject p, Map<String, Long> writes, long until) {
        // Stop is asynchronous: dispatch order alone cannot protect a following seek.
        if (s.now() >= until) {
            finish(op, p, writes, null);
        } else if (verified("playing", p.get("playing"), writes.get("playing"))) {
            writeRemaining(op, p, writes, until);
        } else {
            s.schedule(op, () -> awaitStop(op, p, writes, until), 20);
        }
    }

    private void writeRemaining(HostOperation op, JsonObject p, Map<String, Long> writes, long until) {
        try {
            for (String key : ORDER)
                if (p.has(key) && !(key.equals("playing") && !p.get(key).getAsBoolean())) write(op, p, key, writes);
        } catch (RpcException error) {
            if (!op.dispatched) throw error;
            finish(op, p, writes, error.code);
            return;
        }
        await(op, p, writes, until);
    }

    private void write(HostOperation op, JsonObject p, String key, Map<String, Long> writes) {
        s.check(op);
        guard(key, p.get(key));
        Sample before = values.get(key);
        if (matches(key, before.value, p.get(key))) {
            writes.put(key, -1L);
            return;
        }
        writes.put(key, before.sequence);
        s.check(op);
        op.dispatched = true;
        JsonElement value = p.get(key);
        switch (key) {
            case "playing":
                if (value.getAsBoolean()) s.transport.play();
                else s.transport.stop();
                break;
            case "tempoBpm":
                s.transport.tempo().value().setRaw(value.getAsDouble());
                break;
            case "timeSignature":
                s.transport.timeSignature().set(value.getAsString());
                break;
            case "positionBeats":
                s.transport.setPosition(value.getAsDouble());
                break;
            case "metronome":
                s.transport.isMetronomeEnabled().set(value.getAsBoolean());
                break;
            case "loopEnabled":
                s.transport.isArrangerLoopEnabled().set(value.getAsBoolean());
                break;
            // Absolute RPC target: no physical-controller pickup.
            case "metronomeVolumeNormalized":
                s.transport.metronomeVolume().setImmediately(value.getAsDouble());
                break;
            case "punchInEnabled":
                s.transport.isPunchInEnabled().set(value.getAsBoolean());
                break;
            case "punchOutEnabled":
                s.transport.isPunchOutEnabled().set(value.getAsBoolean());
                break;
            case "preRoll":
            case "defaultLaunchQuantization":
            case "recordQuantizationGrid":
                enums.get(key).set(value.getAsString());
                break;
            case "loopStartBeats":
                s.transport.arrangerLoopStart().set(value.getAsDouble());
                break;
            case "loopDurationBeats":
                s.transport.arrangerLoopDuration().set(value.getAsDouble());
                break;
            default:
                throw new IllegalArgumentException(key);
        }
    }

    private void guard(String key, JsonElement value) {
        if (key.equals("playing") && !value.getAsBoolean()) return;
        s.requirePlaybackSafe();
    }

    private void await(HostOperation op, JsonObject p, Map<String, Long> writes, long until) {
        boolean done = true;
        for (String key : p.keySet()) done &= verified(key, p.get(key), writes.get(key));
        if (done || s.now() >= until) {
            finish(op, p, writes, null);
            return;
        }
        s.schedule(op, () -> await(op, p, writes, until), 20);
    }

    private boolean verified(String key, JsonElement expected, Long before) {
        Sample actual = values.get(key);
        return before != null && (before == -1 || actual.sequence > before) && matches(key, actual.value, expected);
    }

    private void finish(HostOperation op, JsonObject p, Map<String, Long> writes, String reason) {
        JsonObject result = snapshot();
        JsonObject fields = new JsonObject();
        boolean all = reason == null;
        for (String key : p.keySet()) {
            Long before = writes.get(key);
            Sample actual = values.get(key);
            boolean matched = verified(key, p.get(key), before);
            all &= matched;
            JsonObject field = new JsonObject();
            field.add("requested", p.get(key));
            field.add("observed", actual.value);
            field.addProperty("matchesRequested", matched);
            field.addProperty(
                    "verification",
                    before == null
                            ? "not_dispatched"
                            : before == -1
                                    ? "already_at_value"
                                    : actual.sequence <= before
                                            ? "not_observed"
                                            : matched ? "observer_after_write" : "readback_mismatch");
            fields.add(key, field);
        }
        result.addProperty("status", all ? "observed" : "dispatched");
        result.addProperty("completion", all ? "observed" : "unknown");
        result.addProperty("matchesRequested", all);
        if (reason != null) result.addProperty("reason", reason);
        result.add("fields", fields);
        op.result.complete(result);
    }

    /** Observed basics for context.get; unobserved values are null instead of not_ready. */
    JsonObject basics() {
        JsonObject out = new JsonObject();
        out.addProperty("projectName", s.application.projectName().get());
        out.add("playing", values.get("playing").value);
        out.add("tempoBpm", values.get("tempoBpm").value);
        return out;
    }

    private JsonObject snapshot() {
        JsonObject result = HostServices.observed();
        result.addProperty("projectName", s.application.projectName().get());
        result.addProperty("scope", "project_transport_observed_values");
        for (Map.Entry<String, Sample> entry : values.entrySet()) {
            if (BASE.contains(entry.getKey())) require(entry.getKey());
            result.add(entry.getKey(), entry.getValue().value);
        }
        JsonObject units = new JsonObject();
        units.addProperty("tempoBpm", "bpm_raw");
        units.addProperty(
                "timeSignature",
                "numerator/denominator as the host reports it; time-signature change markers are not in the public API");
        units.addProperty("positionBeats", "quarter_note_beats_current_play_position");
        units.addProperty("metronomeVolumeNormalized", "normalized_0_1_not_db");
        units.addProperty("loopStartBeats", "quarter_note_beats");
        units.addProperty("loopDurationBeats", "quarter_note_beats");
        result.add("units", units);
        JsonObject options = new JsonObject();
        for (Map.Entry<String, SettableEnumValue> entry : enums.entrySet())
            options.add(entry.getKey(), EnumOptions.json(entry.getValue()));
        result.add("options", options);
        JsonArray outside = new JsonArray();
        for (Map.Entry<String, SettableEnumValue> entry : enums.entrySet())
            if (EnumOptions.outsideOptions(entry.getValue(), observedString(entry.getKey())))
                outside.add(entry.getKey());
        result.add("observedOutsideOptions", outside);
        JsonObject tempoRange = new JsonObject();
        tempoRange.addProperty("minimum", 20);
        tempoRange.addProperty("maximum", 666);
        tempoRange.addProperty("scope", "toolkit_supported_shipped_controller_range_not_api_raw_limit");
        result.add("tempoRange", tempoRange);
        for (Map.Entry<String, JsonElement> entry : s.playbackSafety().entrySet())
            result.add(entry.getKey(), entry.getValue());
        return result;
    }

    private void require(String key) {
        if (values.get(key).value.isJsonNull())
            throw Errors.notApplied(ErrorCode.NOT_READY, "Transport field not observed: " + key);
    }

    private String observedString(String key) {
        Sample sample = values.get(key);
        return sample != null && sample.value.isJsonPrimitive() ? sample.value.getAsString() : null;
    }

    private Sample sample(String key) {
        Sample value = new Sample();
        values.put(key, value);
        return value;
    }

    private void observe(String key, BooleanValue source) {
        Sample value = sample(key);
        source.addValueObserver((BooleanValueChangedCallback) v -> value.set(new JsonPrimitive(v)));
    }

    private void observe(String key, DoubleValue source) {
        Sample value = sample(key);
        source.addValueObserver((DoubleValueChangedCallback) value::number);
    }

    private void observe(String key, SettableEnumValue source) {
        Sample value = sample(key);
        enums.put(key, source);
        source.addValueObserver((EnumValueChangedCallback) v -> value.set(new JsonPrimitive((String) v)));
    }

    private static boolean matches(String key, JsonElement a, JsonElement b) {
        if (a.isJsonNull()) return false;
        if (key.equals("metronomeVolumeNormalized"))
            return Math.abs(a.getAsDouble() - b.getAsDouble()) <= HostServices.TOLERANCE;
        if (key.equals("tempoBpm") || key.endsWith("Beats")) return Math.abs(a.getAsDouble() - b.getAsDouble()) <= 1e-6;
        return a.equals(b);
    }

    private static final class Sample {
        JsonElement value = JsonNull.INSTANCE;
        long sequence;

        void set(JsonElement next) {
            value = next;
            sequence++;
        }

        void number(double next) {
            set(Double.isFinite(next) ? new JsonPrimitive(next) : JsonNull.INSTANCE);
        }
    }
}
