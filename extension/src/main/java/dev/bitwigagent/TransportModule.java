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
        "metronomeVolume",
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

    private static final ValueMatch BEATS_AND_TEMPO = ValueMatch.raw(1e-6);
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

    private final Transport transport;
    private final Application application;
    private final Scheduler scheduler;
    private final TransportSafety transportSafety;
    private final ProjectState projectState;
    private final Map<String, Sample> values = new LinkedHashMap<>();
    private final Map<String, SettableEnumValue> enums = new LinkedHashMap<>();
    private final Sample volumeDisplay = new Sample();

    TransportModule(
            Transport transport,
            Application application,
            Scheduler scheduler,
            TransportSafety transportSafety,
            ProjectState projectState) {
        this.transport = transport;
        this.application = application;
        this.scheduler = scheduler;
        this.transportSafety = transportSafety;
        this.projectState = projectState;
        observe("playing", transport.isPlaying());
        Sample tempo = sample("tempoBpm");
        transport.tempo().value().addRawValueObserver((DoubleValueChangedCallback) v -> tempo.number(v));
        Sample signature = sample("timeSignature");
        transport.timeSignature().markInterested();
        transport.timeSignature().addValueObserver((StringValueChangedCallback)
                v -> signature.set(new JsonPrimitive((String) v)));
        observe("positionBeats", transport.playPosition());
        observe("metronome", transport.isMetronomeEnabled());
        observe("loopEnabled", transport.isArrangerLoopEnabled());
        observe("loopStartBeats", transport.arrangerLoopStart());
        observe("loopDurationBeats", transport.arrangerLoopDuration());
        Sample volume = sample("metronomeVolume");
        transport.metronomeVolume().addValueObserver((DoubleValueChangedCallback) volume::number);
        transport.metronomeVolume().displayedValue().markInterested();
        transport.metronomeVolume().displayedValue().addValueObserver((StringValueChangedCallback)
                v -> volumeDisplay.set(new JsonPrimitive((String) v)));
        observe("punchInEnabled", transport.isPunchInEnabled());
        observe("punchOutEnabled", transport.isPunchOutEnabled());
        observe("preRoll", transport.preRoll());
        observe("defaultLaunchQuantization", transport.defaultLaunchQuantization());
        observe("recordQuantizationGrid", application.recordQuantizationGrid());
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
        if (method.equals("transport.get")) return;
        if (!method.equals("transport.set"))
            throw Errors.notApplied(Reason.UNKNOWN_METHOD, "Unknown transport method.");
        if (p.size() == 0) throw Errors.notApplied(Reason.INVALID_PARAMS, "At least one transport field is required.");
        for (String key : ENUM_FIELDS) if (p.has(key)) Params.string(p, key);
        if (p.has("metronomeVolume") && p.get("metronomeVolume").isJsonObject())
            Params.string(p.getAsJsonObject("metronomeVolume"), "display");
        if (p.has("timeSignature") && !validSignature(Params.string(p, "timeSignature")))
            throw Errors.notApplied(
                    Reason.INVALID_PARAMS,
                    "timeSignature must be numerator/denominator: numerator 1..32, denominator 1, 2, 4, 8, 16 or 32.");
        if (p.has("loopDurationBeats") && p.get("loopDurationBeats").getAsDouble() == 0)
            throw Errors.notApplied(Reason.INVALID_PARAMS, "loopDurationBeats must be positive.");
        if (p.has("loopStartBeats")
                && p.has("loopDurationBeats")
                && !Double.isFinite(p.get("loopStartBeats").getAsDouble()
                        + p.get("loopDurationBeats").getAsDouble()))
            throw Errors.notApplied(Reason.INVALID_PARAMS, "Loop end must be finite.");
    }

    static boolean validSignature(String text) {
        if (!text.matches("[1-9][0-9]?/(1|2|4|8|16|32)")) return false;
        return Integer.parseInt(text.substring(0, text.indexOf('/'))) <= 32;
    }

    @Override
    public void dispatch(HostOperation op, String method, JsonObject p) {
        scheduler.check(op);
        if (method.equals("transport.get")) {
            op.result.complete(snapshot());
            return;
        }
        if (p.has("metronomeVolume"))
            ValueInput.parse("metronomeVolume", p.get("metronomeVolume")).normalized("metronomeVolume");
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
            throw Errors.notApplied(Reason.INVALID_PARAMS, "Loop end must be finite.");
        Request request = new Request(
                op,
                p,
                scheduler.observeUntil(op),
                p.has("playing") && p.get("playing").getAsBoolean() && !projectState.engineActive());
        boolean stopping = p.has("playing") && !p.get("playing").getAsBoolean();
        try {
            if (stopping) write(request, "playing");
        } catch (RpcException error) {
            if (!op.dispatched()) throw error;
            finish(request, error.reason);
            return;
        }
        if (stopping && p.size() > 1) awaitStop(request);
        else writeRemaining(request);
    }

    /** One transport.set: what was asked, what was dispatched, and whether it started playback on a silent engine. */
    private static final class Request {
        final HostOperation op;
        final JsonObject p;
        final Map<String, Long> writes = new LinkedHashMap<>();
        final long until;
        final boolean silentEngine;

        Request(HostOperation op, JsonObject p, long until, boolean silentEngine) {
            this.op = op;
            this.p = p;
            this.until = until;
            this.silentEngine = silentEngine;
        }
    }

    private void awaitStop(Request request) {
        // Stop is asynchronous: dispatch order alone cannot protect a following seek.
        scheduler.poll(
                request.op,
                request.until,
                () -> scheduler.now() < request.until
                        && verified("playing", request.p.get("playing"), request.writes.get("playing")),
                () -> writeRemaining(request),
                () -> finish(request, null));
    }

    private void writeRemaining(Request request) {
        try {
            for (String key : ORDER)
                if (request.p.has(key)
                        && !(key.equals("playing") && !request.p.get(key).getAsBoolean())) write(request, key);
        } catch (RpcException error) {
            if (!request.op.dispatched()) throw error;
            finish(request, error.reason);
            return;
        }
        await(request);
    }

    private void write(Request request, String key) {
        HostOperation op = request.op;
        JsonObject p = request.p;
        Map<String, Long> writes = request.writes;
        scheduler.check(op);
        guard(key, p.get(key));
        Sample before = values.get(key);
        if (matches(key, before.value, p.get(key))) {
            writes.put(key, -1L);
            return;
        }
        writes.put(key, before.sequence);
        scheduler.check(op);
        op.markDispatched();
        JsonElement value = p.get(key);
        switch (key) {
            case "playing":
                if (value.getAsBoolean()) transport.play();
                else transport.stop();
                break;
            case "tempoBpm":
                transport.tempo().value().setRaw(value.getAsDouble());
                break;
            case "timeSignature":
                transport.timeSignature().set(value.getAsString());
                break;
            case "positionBeats":
                transport.setPosition(value.getAsDouble());
                break;
            case "metronome":
                transport.isMetronomeEnabled().set(value.getAsBoolean());
                break;
            case "loopEnabled":
                transport.isArrangerLoopEnabled().set(value.getAsBoolean());
                break;
            // Absolute RPC target: no physical-controller pickup.
            case "metronomeVolume":
                transport.metronomeVolume().setImmediately(value.getAsDouble());
                break;
            case "punchInEnabled":
                transport.isPunchInEnabled().set(value.getAsBoolean());
                break;
            case "punchOutEnabled":
                transport.isPunchOutEnabled().set(value.getAsBoolean());
                break;
            case "preRoll":
            case "defaultLaunchQuantization":
            case "recordQuantizationGrid":
                enums.get(key).set(value.getAsString());
                break;
            case "loopStartBeats":
                transport.arrangerLoopStart().set(value.getAsDouble());
                break;
            case "loopDurationBeats":
                transport.arrangerLoopDuration().set(value.getAsDouble());
                break;
            default:
                throw new IllegalArgumentException(key);
        }
    }

    private void guard(String key, JsonElement value) {
        if (key.equals("playing") && !value.getAsBoolean()) return;
        transportSafety.requirePlaybackSafe();
    }

    private void await(Request request) {
        scheduler.poll(
                request.op,
                request.until,
                () -> {
                    boolean done = true;
                    for (String key : request.p.keySet())
                        done &= verified(key, request.p.get(key), request.writes.get(key));
                    return done;
                },
                done -> finish(request, null));
    }

    private boolean verified(String key, JsonElement expected, Long before) {
        Sample actual = values.get(key);
        return before != null && (before == -1 || actual.sequence > before) && matches(key, actual.value, expected);
    }

    private void finish(Request request, Reason reason) {
        HostOperation op = request.op;
        JsonObject p = request.p;
        Map<String, Long> writes = request.writes;
        JsonObject result = snapshot();
        JsonObject fields = new JsonObject();
        Outcome worst = Outcome.ALREADY_MATCHED;
        Reason worstReason = null;
        for (String key : p.keySet()) {
            Long before = writes.get(key);
            Sample actual = values.get(key);
            Outcome outcome;
            Reason why = null;
            if (before == null) {
                outcome = Outcome.NOT_APPLIED;
                why = Reason.NOT_REACHED;
            } else if (before == -1) outcome = Outcome.ALREADY_MATCHED;
            else if (actual.sequence <= before) {
                outcome = Outcome.UNKNOWN;
                why = Reason.NOT_OBSERVED;
            } else if (verified(key, p.get(key), before)) outcome = Outcome.APPLIED;
            else {
                outcome = Outcome.UNKNOWN;
                why = Reason.READBACK_MISMATCH;
            }
            fields.add(key, Results.field(p.get(key), actual.value, outcome, why));
            if (outcome.compareTo(worst) > 0 && why != null) worstReason = why;
            worst = worst.worst(outcome);
        }
        result.addProperty("outcome", worst.wire());
        if (!worst.succeeded()) result.addProperty("reason", (reason != null ? reason : worstReason).wire());
        result.add("fields", fields);
        if (request.silentEngine)
            Results.addWarning(
                    result,
                    "Playback was started with an inactive audio engine: nothing will sound and captures will be silent. Run project.activateEngine and confirm status.engineActive=true.");
        op.result.complete(result);
    }

    /** Observed basics for context.get; unobserved values are null instead of not_ready. */
    JsonObject basics() {
        JsonObject out = new JsonObject();
        out.addProperty("projectName", application.projectName().get());
        out.add("playing", values.get("playing").value);
        out.add("tempoBpm", values.get("tempoBpm").value);
        return out;
    }

    private JsonObject snapshot() {
        JsonObject result = new JsonObject();
        result.addProperty("projectName", application.projectName().get());
        result.addProperty("scope", "project_transport_observed_values");
        for (Map.Entry<String, Sample> entry : values.entrySet()) {
            String key = entry.getKey();
            if (BASE.contains(key)) require(key);
            result.add(key, unit(key, entry.getValue().value));
        }
        JsonObject units = new JsonObject();
        units.addProperty("tempoBpm", "bpm_raw");
        units.addProperty(
                "timeSignature",
                "numerator/denominator as the host reports it; time-signature change markers are not in the public API");
        units.addProperty("positionBeats", "quarter_note_beats_current_play_position");
        units.addProperty("metronomeVolume", "normalized_0_1_not_db");
        units.addProperty("loopStartBeats", "quarter_note_beats");
        units.addProperty("loopDurationBeats", "quarter_note_beats");
        result.add("units", units);
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
        Results.addPlaybackSafety(result, transportSafety.observation());
        return result;
    }

    /** A host-parameter read unit: the bare value, with its display or its option list. */
    private JsonElement unit(String key, JsonElement value) {
        boolean volume = key.equals("metronomeVolume");
        if (!volume && !enums.containsKey(key)) return value;
        JsonObject unit = new JsonObject();
        unit.add("value", value);
        if (volume) unit.add("display", volumeDisplay.value);
        else unit.add("options", EnumOptions.json(enums.get(key)));
        return unit;
    }

    private void require(String key) {
        if (values.get(key).value.isJsonNull())
            throw Errors.notApplied(Reason.NOT_READY, "Transport field not observed: " + key);
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

    private static ValueMatch rule(String key) {
        if (key.equals("metronomeVolume")) return ValueMatch.NORMALIZED;
        if (key.equals("tempoBpm") || key.endsWith("Beats")) return BEATS_AND_TEMPO;
        return ValueMatch.EXACT;
    }

    private static boolean matches(String key, JsonElement observed, JsonElement requested) {
        return rule(key).matches(requested, observed);
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
