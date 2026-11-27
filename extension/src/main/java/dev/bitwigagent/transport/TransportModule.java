package dev.bitwigagent.transport;

import com.bitwig.extension.callback.BooleanValueChangedCallback;
import com.bitwig.extension.callback.DoubleValueChangedCallback;
import com.bitwig.extension.callback.EnumValueChangedCallback;
import com.bitwig.extension.callback.StringValueChangedCallback;
import com.bitwig.extension.controller.api.*;
import com.google.gson.*;
import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.HostModule;
import dev.bitwigagent.core.HostOperation;
import dev.bitwigagent.core.Outcome;
import dev.bitwigagent.core.Params;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.core.Results;
import dev.bitwigagent.core.RpcException;
import dev.bitwigagent.core.Scheduler;
import dev.bitwigagent.core.ValueInput;
import dev.bitwigagent.core.ValueMatch;
import dev.bitwigagent.core.Verification;
import dev.bitwigagent.host.EnumOptions;
import dev.bitwigagent.host.ProjectState;
import dev.bitwigagent.host.TransportSafety;
import java.util.*;

/**
 * Absolute transport controls, including the recording and automation-write modes. Those modes stay writable while
 * recording or automation write is on, so recording can always be turned off, and playing=false always stops; every
 * other field, playing=true included, is refused then.
 */
public final class TransportModule implements HostModule {
    static final Set<String> METHODS = Set.of("transport.get", "transport.set");
    private static final String[] ORDER = {
        "tempo",
        "timeSignature",
        "position",
        "metronome",
        "metronomeVolume",
        "loopStart",
        "loopLength",
        "loopEnabled",
        "punchInEnabled",
        "punchOutEnabled",
        "preRoll",
        "defaultLaunchQuantization",
        "recordQuantizationGrid",
        "playing",
        "arrangerRecordEnabled",
        "arrangerOverdubEnabled",
        "clipLauncherOverdubEnabled",
        "arrangerAutomationWriteEnabled",
        "automationWriteMode"
    };
    /** Fields writable while recording or automation write is on: the modes themselves. */
    static final Set<String> RECORDING_FIELDS = Set.of(
            "arrangerRecordEnabled",
            "arrangerOverdubEnabled",
            "clipLauncherOverdubEnabled",
            "arrangerAutomationWriteEnabled",
            "automationWriteMode");
    /** Recording state Bitwig may switch along with a requested field (Global Record also turns automation write on). */
    static final List<String> SIDE_EFFECTS = List.of(
            "arrangerRecordEnabled",
            "arrangerOverdubEnabled",
            "clipLauncherOverdubEnabled",
            "arrangerAutomationWriteEnabled",
            "clipLauncherAutomationWriteEnabled",
            "automationWriteMode");

    private static final ValueMatch BEATS_AND_TEMPO = ValueMatch.raw(1e-6);
    private static final Set<String> BEATS_AND_TEMPO_KEYS = Set.of("tempo", "position", "loopStart", "loopLength");
    /** Fields every host reports at startup; a newer field that is not (yet) observed reads null instead of blocking the rest. */
    private static final Set<String> BASE = Set.of(
            "tempo", "timeSignature", "position", "metronome", "loopStart", "loopLength", "loopEnabled", "playing");

    private final Transport transport;
    private final Application application;
    private final Scheduler scheduler;
    private final TransportSafety transportSafety;
    private final ProjectState projectState;
    private final Map<String, Sample> values = new LinkedHashMap<>();
    private final Map<String, SettableEnumValue> enums = new LinkedHashMap<>();
    private final Sample volumeDisplay = new Sample();

    public TransportModule(
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
        Sample tempo = sample("tempo");
        transport.tempo().value().addRawValueObserver((DoubleValueChangedCallback) v -> tempo.number(v));
        Sample signature = sample("timeSignature");
        transport.timeSignature().markInterested();
        transport.timeSignature().addValueObserver((StringValueChangedCallback)
                v -> signature.set(new JsonPrimitive((String) v)));
        observe("position", transport.playPosition());
        observe("metronome", transport.isMetronomeEnabled());
        observe("loopEnabled", transport.isArrangerLoopEnabled());
        observe("loopStart", transport.arrangerLoopStart());
        observe("loopLength", transport.arrangerLoopDuration());
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
        observe("arrangerRecordEnabled", transport.isArrangerRecordEnabled());
        observe("arrangerOverdubEnabled", transport.isArrangerOverdubEnabled());
        observe("clipLauncherOverdubEnabled", transport.isClipLauncherOverdubEnabled());
        observe("arrangerAutomationWriteEnabled", transport.isArrangerAutomationWriteEnabled());
        observe("clipLauncherAutomationWriteEnabled", launcherAutomationWrite());
        observe("automationWriteMode", transport.automationWriteMode());
    }

    @SuppressWarnings("deprecation")
    private SettableBooleanValue launcherAutomationWrite() {
        return transport.isClipLauncherAutomationWriteEnabled();
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
        if (p.has("clipLauncherAutomationWriteEnabled"))
            throw Errors.notApplied(
                    Reason.UNSUPPORTED_SCOPE,
                    "clipLauncherAutomationWriteEnabled has no effect in Bitwig 6 (one automation write button); use"
                            + " arrangerAutomationWriteEnabled. Nothing was written.");
        if (p.has("timeSignature") && !validSignature(Params.string(p, "timeSignature")))
            throw Errors.notApplied(
                    Reason.INVALID_PARAMS,
                    "timeSignature must be numerator/denominator: numerator 1..32, denominator 1, 2, 4, 8, 16 or 32.");
        if (!p.has("loop")) return;
        JsonObject loop = p.getAsJsonObject("loop");
        if (loop.size() == 0) throw Errors.notApplied(Reason.INVALID_PARAMS, "loop needs start or length.");
        if (loop.has("length") && loop.get("length").getAsDouble() == 0)
            throw Errors.notApplied(Reason.INVALID_PARAMS, "loop.length must be positive.");
        if (loop.has("start")
                && loop.has("length")
                && !Double.isFinite(
                        loop.get("start").getAsDouble() + loop.get("length").getAsDouble()))
            throw Errors.notApplied(Reason.INVALID_PARAMS, "Loop end must be finite.");
    }

    /** The wire nests the arranger loop as loop:{start, length}; the samples keep one key per host value. */
    private static JsonObject flat(JsonObject params) {
        JsonObject out = new JsonObject();
        for (Map.Entry<String, JsonElement> entry : params.entrySet()) {
            if (!entry.getKey().equals("loop")) {
                out.add(entry.getKey(), entry.getValue());
                continue;
            }
            JsonObject loop = entry.getValue().getAsJsonObject();
            if (loop.has("start")) out.add("loopStart", loop.get("start"));
            if (loop.has("length")) out.add("loopLength", loop.get("length"));
        }
        return out;
    }

    private static void nest(JsonObject result) {
        JsonObject loop = new JsonObject();
        if (result.has("loopStart")) loop.add("start", result.remove("loopStart"));
        if (result.has("loopLength")) loop.add("length", result.remove("loopLength"));
        if (loop.size() > 0) result.add("loop", loop);
    }

    static boolean validSignature(String text) {
        if (!text.matches("[1-9][0-9]?/(1|2|4|8|16|32)")) return false;
        return Integer.parseInt(text.substring(0, text.indexOf('/'))) <= 32;
    }

    @Override
    public void dispatch(HostOperation op, String method, JsonObject params) {
        scheduler.check(op);
        if (method.equals("transport.get")) {
            op.result.complete(snapshot());
            return;
        }
        JsonObject p = flat(params);
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
        double start = p.has("loopStart")
                ? p.get("loopStart").getAsDouble()
                : values.get("loopStart").value.getAsDouble();
        double duration = p.has("loopLength")
                ? p.get("loopLength").getAsDouble()
                : values.get("loopLength").value.getAsDouble();
        if (!Double.isFinite(start + duration))
            throw Errors.notApplied(Reason.INVALID_PARAMS, "Loop end must be finite.");
        Request request = new Request(
                op,
                p,
                scheduler.observeUntil(op),
                p.has("playing") && p.get("playing").getAsBoolean() && !projectState.engineActive());
        for (String key : SIDE_EFFECTS) request.before.put(key, values.get(key).value);
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
        final Map<String, JsonElement> before = new LinkedHashMap<>();
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
            case "tempo":
                transport.tempo().value().setRaw(value.getAsDouble());
                break;
            case "timeSignature":
                transport.timeSignature().set(value.getAsString());
                break;
            case "position":
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
            case "automationWriteMode":
                enums.get(key).set(value.getAsString());
                break;
            case "arrangerRecordEnabled":
                transport.isArrangerRecordEnabled().set(value.getAsBoolean());
                break;
            case "arrangerOverdubEnabled":
                transport.isArrangerOverdubEnabled().set(value.getAsBoolean());
                break;
            case "clipLauncherOverdubEnabled":
                transport.isClipLauncherOverdubEnabled().set(value.getAsBoolean());
                break;
            case "arrangerAutomationWriteEnabled":
                transport.isArrangerAutomationWriteEnabled().set(value.getAsBoolean());
                break;
            case "loopStart":
                transport.arrangerLoopStart().set(value.getAsDouble());
                break;
            case "loopLength":
                transport.arrangerLoopDuration().set(value.getAsDouble());
                break;
            default:
                throw new IllegalArgumentException(key);
        }
    }

    /**
     * Stopping playback or turning a recording mode off is always allowed; turning a mode on or changing the write mode
     * needs the recording state observed (it may be on); starting playback and every other field need recording and
     * automation write observed off, as for launches.
     */
    private void guard(String key, JsonElement value) {
        boolean off = value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() && !value.getAsBoolean();
        if (RECORDING_FIELDS.contains(key)) {
            if (!off) transportSafety.requireObserved();
        } else if (!(key.equals("playing") && off)) transportSafety.requirePlaybackSafe();
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
            fields.add(key, Results.field(p.get(key), actual.value, outcome, why, Verification.API_READBACK));
            if (outcome.compareTo(worst) > 0 && why != null) worstReason = why;
            worst = worst.worst(outcome);
        }
        Results.stamp(result, worst, fields.asMap().values());
        if (!worst.succeeded()) result.addProperty("reason", (reason != null ? reason : worstReason).wire());
        nest(fields);
        result.add("fields", fields);
        JsonObject effects = new JsonObject();
        for (Map.Entry<String, JsonElement> seen : request.before.entrySet()) {
            JsonElement now = values.get(seen.getKey()).value;
            if (p.has(seen.getKey()) || now.equals(seen.getValue())) continue;
            JsonObject change = new JsonObject();
            change.add("before", seen.getValue());
            change.add("after", now);
            effects.add(seen.getKey(), change);
        }
        if (effects.size() > 0) result.add("observedSideEffects", effects);
        if (request.silentEngine)
            Results.addWarning(
                    result,
                    "Playback was started with an inactive audio engine: nothing will sound and captures will be silent. Run project.activateEngine and confirm status.engineActive=true.");
        op.result.complete(result);
    }

    /** Observed basics for context.get; unobserved values are null instead of not_ready. */
    public JsonObject basics() {
        JsonObject out = new JsonObject();
        out.addProperty("projectName", application.projectName().get());
        out.add("playing", values.get("playing").value);
        out.add("tempo", values.get("tempo").value);
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
        units.addProperty("tempo", "bpm_raw");
        units.addProperty(
                "timeSignature",
                "numerator/denominator as the host reports it; time-signature change markers are not in the public API");
        units.addProperty("position", "quarter_note_beats_current_play_position");
        units.addProperty("metronomeVolume", "normalized_0_1_not_db");
        units.addProperty("loop", "quarter_note_beats");
        nest(result);
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
        source.addValueObserver(
                (EnumValueChangedCallback) v -> value.set(new JsonPrimitive(EnumOptions.listed(source, (String) v))));
    }

    static ValueMatch rule(String key) {
        if (key.equals("metronomeVolume")) return ValueMatch.NORMALIZED;
        if (BEATS_AND_TEMPO_KEYS.contains(key)) return BEATS_AND_TEMPO;
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
