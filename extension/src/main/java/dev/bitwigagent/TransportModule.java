package dev.bitwigagent;

import com.bitwig.extension.callback.BooleanValueChangedCallback;
import com.bitwig.extension.callback.DoubleValueChangedCallback;
import com.bitwig.extension.controller.api.*;
import com.google.gson.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Absolute transport controls; recording and automation modes are read-only safety inputs. */
final class TransportModule implements HostModule {
    static final Set<String> METHODS = Set.of("transport.get", "transport.set");
    private static final String[] ORDER = {"tempoBpm", "positionBeats", "metronome", "loopStartBeats", "loopDurationBeats", "loopEnabled", "playing"};
    private static final Set<String> FIELDS = Set.of(ORDER);
    private final HostServices s;
    private final Map<String, Sample> values = new LinkedHashMap<>();

    TransportModule(HostServices services) {
        s = services;
        observe("playing", s.transport.isPlaying());
        Sample tempo = sample("tempoBpm");
        s.transport.tempo().value().addRawValueObserver((DoubleValueChangedCallback) v -> tempo.number(v));
        observe("positionBeats", s.transport.playPosition());
        observe("metronome", s.transport.isMetronomeEnabled());
        observe("loopEnabled", s.transport.isArrangerLoopEnabled());
        observe("loopStartBeats", s.transport.arrangerLoopStart());
        observe("loopDurationBeats", s.transport.arrangerLoopDuration());
    }

    @Override public Set<String> methods() { return METHODS; }
    @Override public void validate(String method, JsonObject params) { validateParams(method, params); }

    static void validateParams(String method, JsonObject p) {
        if (method.equals("transport.get")) { Params.fields(p, Set.of(), Set.of()); return; }
        if (!method.equals("transport.set")) throw HostServices.failure("unknown_method", "Unknown transport method.");
        Params.fields(p, Set.of(), FIELDS);
        if (p.size() == 0) throw HostServices.failure("invalid_params", "At least one transport field is required.");
        for (String key : List.of("playing", "metronome", "loopEnabled")) if (p.has(key)) Params.bool(p, key);
        // The installed Maschine JAM/MainKnobControl.js uses raw BPM 20..666.
        // RangedValue Javadoc leaves raw limits undefined: these are toolkit-supported bounds.
        if (p.has("tempoBpm")) Params.number(p, "tempoBpm", 20, 666);
        for (String key : List.of("positionBeats", "loopStartBeats", "loopDurationBeats")) {
            if (p.has(key)) Params.number(p, key, 0, Double.MAX_VALUE);
        }
        if (p.has("loopDurationBeats") && p.get("loopDurationBeats").getAsDouble() == 0)
            throw HostServices.failure("invalid_params", "loopDurationBeats must be positive.");
        if (p.has("loopStartBeats") && p.has("loopDurationBeats")
                && !Double.isFinite(p.get("loopStartBeats").getAsDouble() + p.get("loopDurationBeats").getAsDouble()))
            throw HostServices.failure("invalid_params", "Loop end must be finite.");
    }

    @Override public void dispatch(HostOperation op, String method, JsonObject p) {
        s.check(op);
        if (method.equals("transport.get")) { op.result.complete(snapshot()); return; }
        for (String key : FIELDS) require(key);
        for (String key : p.keySet()) guard(key, p.get(key));
        double start = p.has("loopStartBeats") ? p.get("loopStartBeats").getAsDouble() : values.get("loopStartBeats").value.getAsDouble();
        double duration = p.has("loopDurationBeats") ? p.get("loopDurationBeats").getAsDouble() : values.get("loopDurationBeats").value.getAsDouble();
        if (!Double.isFinite(start + duration)) throw HostServices.failure("invalid_params", "Loop end must be finite.");
        Map<String, Long> writes = new LinkedHashMap<>();
        long until = Math.min(op.deadline - TimeUnit.MILLISECONDS.toNanos(25),
                System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(750));
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
        if (System.nanoTime() >= until) {
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
                if (p.has(key) && !(key.equals("playing") && !p.get(key).getAsBoolean()))
                    write(op, p, key, writes);
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
        if (matches(key, before.value, p.get(key))) { writes.put(key, -1L); return; }
        writes.put(key, before.sequence);
        s.check(op);
        op.dispatched = true;
        JsonElement value = p.get(key);
        switch (key) {
            case "playing": if (value.getAsBoolean()) s.transport.play(); else s.transport.stop(); break;
            case "tempoBpm": s.transport.tempo().value().setRaw(value.getAsDouble()); break;
            case "positionBeats": s.transport.setPosition(value.getAsDouble()); break;
            case "metronome": s.transport.isMetronomeEnabled().set(value.getAsBoolean()); break;
            case "loopEnabled": s.transport.isArrangerLoopEnabled().set(value.getAsBoolean()); break;
            case "loopStartBeats": s.transport.arrangerLoopStart().set(value.getAsDouble()); break;
            case "loopDurationBeats": s.transport.arrangerLoopDuration().set(value.getAsDouble()); break;
            default: throw new IllegalArgumentException(key);
        }
    }

    private void guard(String key, JsonElement value) {
        if (key.equals("playing") && !value.getAsBoolean()) return;
        s.requirePlaybackSafe();
    }

    private void await(HostOperation op, JsonObject p, Map<String, Long> writes, long until) {
        boolean done = true;
        for (String key : p.keySet()) done &= verified(key, p.get(key), writes.get(key));
        if (done || System.nanoTime() >= until) { finish(op, p, writes, null); return; }
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
            field.addProperty("verification", before == null ? "not_dispatched" : before == -1 ? "already_at_value"
                    : actual.sequence <= before ? "not_observed" : matched ? "observer_after_write" : "readback_mismatch");
            fields.add(key, field);
        }
        result.addProperty("status", all ? "observed" : "dispatched");
        result.addProperty("completion", all ? "observed" : "unknown");
        result.addProperty("matchesRequested", all);
        if (reason != null) result.addProperty("reason", reason);
        result.add("fields", fields);
        op.result.complete(result);
    }

    private JsonObject snapshot() {
        JsonObject result = HostServices.observed();
        result.addProperty("projectName", s.application.projectName().get());
        result.addProperty("scope", "project_transport_observed_values");
        for (Map.Entry<String, Sample> entry : values.entrySet()) {
            require(entry.getKey());
            result.add(entry.getKey(), entry.getValue().value);
        }
        JsonObject units = new JsonObject();
        units.addProperty("tempoBpm", "bpm_raw");
        units.addProperty("positionBeats", "quarter_note_beats_current_play_position");
        units.addProperty("loopStartBeats", "quarter_note_beats");
        units.addProperty("loopDurationBeats", "quarter_note_beats");
        result.add("units", units);
        JsonObject tempoRange = new JsonObject();
        tempoRange.addProperty("minimum", 20); tempoRange.addProperty("maximum", 666);
        tempoRange.addProperty("scope", "toolkit_supported_shipped_controller_range_not_api_raw_limit");
        result.add("tempoRange", tempoRange);
        for (Map.Entry<String, JsonElement> entry : s.playbackSafety().entrySet()) result.add(entry.getKey(), entry.getValue());
        return result;
    }

    private void require(String key) {
        if (values.get(key).value.isJsonNull()) throw HostServices.failure("not_ready", "Transport field not observed: " + key);
    }
    private Sample sample(String key) { Sample value = new Sample(); values.put(key, value); return value; }
    private void observe(String key, BooleanValue source) {
        Sample value = sample(key);
        source.addValueObserver((BooleanValueChangedCallback) v -> value.set(new JsonPrimitive(v)));
    }
    private void observe(String key, DoubleValue source) {
        Sample value = sample(key);
        source.addValueObserver((DoubleValueChangedCallback) value::number);
    }
    private static boolean matches(String key, JsonElement a, JsonElement b) {
        if (a.isJsonNull()) return false;
        if (key.equals("tempoBpm") || key.endsWith("Beats")) return Math.abs(a.getAsDouble() - b.getAsDouble()) <= 1e-6;
        return a.equals(b);
    }
    private static final class Sample {
        JsonElement value = JsonNull.INSTANCE;
        long sequence;
        void set(JsonElement next) { value = next; sequence++; }
        void number(double next) { set(Double.isFinite(next) ? new JsonPrimitive(next) : JsonNull.INSTANCE); }
    }
}
