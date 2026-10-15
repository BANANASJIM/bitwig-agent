package dev.bitwigagent;

import com.bitwig.extension.controller.api.*;
import com.bitwig.extension.callback.*;
import com.google.gson.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static dev.bitwigagent.HostServices.*;

/** Source-specific direct-parameter observations on fixed bank proxies, never a cursor. */
final class ParameterModule implements HostModule {
    static final Set<String> METHODS = Set.of("parameters.list", "parameters.set");
    private final HostServices s;
    private final ParameterSource[][] parameterSources = new ParameterSource[TRACKS][DEVICES];
    private long valueSequence;
    private Binding binding;
    private HostOperation parameterRequest;

    ParameterModule(HostServices s) {
        this.s = s;
        for (int t = 0; t < TRACKS; t++) {
            Track track = (Track) s.tracks.getItemAt(t);
            for (int d = 0; d < DEVICES; d++) {
                Device device = (Device) s.devices[t].getItemAt(d);
                ParameterSource source = new ParameterSource(t, d, track, device);
                parameterSources[t][d] = source;
                device.name().addValueObserver((StringValueChangedCallback) value -> invalidateChangedSource(source));
                device.position().addValueObserver((IntegerValueChangedCallback) value -> invalidateChangedSource(source));
                device.exists().addValueObserver((BooleanValueChangedCallback) value -> invalidateChangedSource(source));
                device.addDirectParameterIdObserver(ids -> onParameterIds(source, (String[]) ids));
                device.addDirectParameterNameObserver(256, (id, name) -> {
                    if (acceptParameterObservation(source)) source.parameter(id).name = name;
                });
                device.addDirectParameterNormalizedValueObserver((id, value) -> {
                    if (!acceptParameterObservation(source)) return;
                    ParameterState parameter = source.parameter(id);
                    parameter.value = Double.isFinite(value) ? value : null;
                    parameter.sequence = ++valueSequence;
                });
            }
            final int trackSlot = t;
            track.channelId().addValueObserver((StringValueChangedCallback) value -> invalidateTrackSources(trackSlot));
            track.exists().addValueObserver((BooleanValueChangedCallback) value -> invalidateTrackSources(trackSlot));
        }
    }

    @Override public Set<String> methods() { return METHODS; }
    @Override public void validate(String method, JsonObject params) { validateParams(method, params); }
    static void validateParams(String method, JsonObject params) {
        if (!METHODS.contains(method)) throw failure("unknown_method", "Unknown parameter method.");
        boolean write = method.equals("parameters.set");
        Params.fields(params, write ? Set.of("trackId", "deviceIndex", "expectedDeviceName", "contextToken", "parameterId", "value")
                : Set.of("trackId", "deviceIndex", "expectedDeviceName"), Set.of());
        Params.string(params, "trackId");
        Params.integer(params, "deviceIndex", 0, DEVICES - 1);
        Params.string(params, "expectedDeviceName");
        if (write) {
            Params.string(params, "contextToken");
            Params.string(params, "parameterId");
            Params.number(params, "value", 0, 1);
        }
    }

    @Override public void dispatch(HostOperation request, String method, JsonObject params) {
        if (parameterRequest != null && !parameterRequest.result.isDone())
            throw failure("busy", "Another parameter operation is awaiting host observations.");
        parameterRequest = request;
        if (method.equals("parameters.list")) bind(request, params);
        else setParameter(request, params);
    }

    JsonObject diagnostics() {
        JsonObject diagnostics = new JsonObject();
        ParameterSource source = binding == null ? null : parameterSources[binding.trackSlot][binding.deviceSlot];
        diagnostics.add("target", binding == null ? JsonNull.INSTANCE : binding.json());
        diagnostics.addProperty("established", binding != null && binding.established);
        diagnostics.addProperty("bankTargetMatches", binding == null ? null : bankTargetMatches(binding));
        diagnostics.addProperty("matches", binding == null ? null : matches(binding));
        diagnostics.addProperty("idsObserved", source == null ? null : source.idsObserved);
        diagnostics.addProperty("idCount", source == null ? null : source.parameterIds.size());
        diagnostics.addProperty("idCallbacksReceived", source == null ? null : source.idCallbacksReceived);
        diagnostics.addProperty("idCallbacksRejected", source == null ? null : source.idCallbacksRejected);
        diagnostics.addProperty("cacheGeneration", source == null ? null : source.cacheGeneration);
        diagnostics.addProperty("lastIdCallbackGeneration", source == null ? null : source.lastIdCallbackGeneration);
        diagnostics.addProperty("lastAcceptedIdCallbackGeneration", source == null ? null : source.lastAcceptedIdCallbackGeneration);
        diagnostics.addProperty("lastIdCallbackAccepted",
                source == null || source.idCallbacksReceived == 0 ? null : source.lastIdCallbackAccepted);
        diagnostics.addProperty("identityScope", "observed_bank_proxy_not_persistent_device_identity");
        return diagnostics;
    }

    private void bind(HostOperation request, JsonObject params) {
        Binding target = resolve(params);
        ParameterSource source = parameterSources[target.trackSlot][target.deviceSlot];
        invalidateChangedSource(source);
        if (binding != null && binding.established && binding.sameTarget(target) && matches(binding) && source.idsObserved) {
            request.result.complete(listParameters());
            return;
        }
        binding = target;
        s.check(request);
        awaitDevice(request, target);
    }

    private void awaitDevice(HostOperation request, Binding target) {
        if (binding != target || !bankTargetMatches(target)) throw failure("stale_target", "Target changed during binding.");
        if (!matches(target) || !parameterSources[target.trackSlot][target.deviceSlot].idsObserved) {
            s.schedule(request, () -> awaitDevice(request, target), 20);
            return;
        }
        target.established = true;
        request.result.complete(listParameters());
    }

    private JsonObject listParameters() {
        JsonObject result = observed();
        result.add("target", binding.json());
        JsonArray items = new JsonArray();
        ParameterSource source = parameterSources[binding.trackSlot][binding.deviceSlot];
        for (String id : source.parameterIds) {
            ParameterState parameter = source.parameters == null ? null : source.parameters.get(id);
            JsonObject item = new JsonObject();
            item.addProperty("id", id);
            item.addProperty("name", parameter == null ? "" : parameter.name);
            nullable(item, "normalizedValue", parameter == null ? null : parameter.value);
            item.addProperty("readable", parameter != null && parameter.value != null);
            items.add(item);
        }
        result.add("parameters", items);
        result.addProperty("scope", "host_exposed_direct_parameters");
        result.addProperty("identityScope", "observed_bank_proxy_context_not_persistent_device_identity");
        return result;
    }

    private void setParameter(HostOperation request, JsonObject params) {
        Binding requested = resolve(params);
        Binding target = binding;
        String token = params.get("contextToken").getAsString();
        if (target == null || !target.established || !target.token.equals(token)
                || !target.sameTarget(requested) || !matches(target)) {
            throw failure("stale_context", "Read parameters.list again before writing this target.");
        }
        String id = params.get("parameterId").getAsString();
        ParameterSource source = parameterSources[target.trackSlot][target.deviceSlot];
        if (!source.parameterIds.contains(id)) throw failure("unknown_parameter", "Parameter is not in the observed ID list.");
        ParameterState parameter = source.parameters == null ? null : source.parameters.get(id);
        if (parameter == null || parameter.value == null) throw failure("not_ready", "Parameter has no readable value.");
        double requestedValue = params.get("value").getAsDouble();
        double applied = Math.round(requestedValue * (RESOLUTION - 1)) / (double) (RESOLUTION - 1);
        if (Math.abs(parameter.value - applied) <= TOLERANCE) {
            request.result.complete(writeResult(target, id, requestedValue, applied, parameter.value, "already_at_value"));
            return;
        }
        long before = parameter.sequence;
        s.check(request);
        if (!matches(target)) throw failure("stale_context", "Target changed before parameter dispatch.");
        request.dispatched = true;
        source.device.setDirectParameterValueNormalized(id, Math.round(requestedValue * (RESOLUTION - 1)), RESOLUTION);
        long observeUntil = Math.min(request.deadline - TimeUnit.MILLISECONDS.toNanos(40),
                System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(750));
        awaitValue(request, target, token, id, requestedValue, applied, before, observeUntil);
    }

    private void awaitValue(HostOperation request, Binding target, String token, String id,
            double requested, double applied, long before, long observeUntil) {
        if (binding != target || !target.token.equals(token) || !matches(target)) {
            throw new RpcException("stale_context", "Target changed after dispatch; outcome unknown.", "unknown");
        }
        ParameterSource source = parameterSources[target.trackSlot][target.deviceSlot];
        ParameterState parameter = source.parameters == null ? null : source.parameters.get(id);
        boolean fresh = parameter != null && parameter.sequence > before && parameter.value != null;
        if (fresh && Math.abs(parameter.value - applied) <= TOLERANCE) {
            request.result.complete(writeResult(target, id, requested, applied, parameter.value, "observer_after_write"));
        } else if (System.nanoTime() >= observeUntil) {
            request.result.complete(writeResult(target, id, requested, applied,
                    fresh ? parameter.value : null, fresh ? "readback_mismatch" : "not_observed"));
        } else {
            s.schedule(request, () -> awaitValue(request, target, token, id, requested, applied, before, observeUntil), 20);
        }
    }

    private static JsonObject writeResult(Binding target, String id, double requested, double applied,
            Double actual, String verification) {
        boolean matched = actual != null && Math.abs(actual - applied) <= TOLERANCE;
        JsonObject result = new JsonObject();
        result.addProperty("status", matched ? "observed" : "dispatched");
        result.add("target", target.json());
        result.addProperty("parameterId", id);
        result.addProperty("requestedValue", requested);
        result.addProperty("appliedValue", applied);
        nullable(result, "observedValue", actual);
        nullable(result, "difference", actual == null ? null : actual - applied);
        result.addProperty("tolerance", TOLERANCE);
        result.addProperty("matchesRequested", matched);
        result.addProperty("verification", verification);
        result.addProperty("resolution", RESOLUTION);
        return result;
    }

    private void onParameterIds(ParameterSource source, String[] ids) {
        source.idCallbacksReceived++;
        source.lastIdCallbackAccepted = acceptParameterObservation(source);
        source.lastIdCallbackGeneration = source.cacheGeneration;
        if (!source.lastIdCallbackAccepted) {
            source.idCallbacksRejected++;
            return;
        }
        source.lastAcceptedIdCallbackGeneration = source.cacheGeneration;
        Set<String> next = ids.length == 0 ? Collections.emptySet() : new LinkedHashSet<>(Arrays.asList(ids));
        if (binding != null && binding.established && source.isTarget(binding) && !source.parameterIds.equals(next)) {
            binding.token = UUID.randomUUID().toString();
        }
        source.parameterIds = next;
        if (source.parameters != null) source.parameters.keySet().retainAll(next);
        for (String id : next) source.parameter(id);
        source.idsObserved = true;
    }

    private boolean acceptParameterObservation(ParameterSource source) {
        // The registration identifies a bank proxy, not a persistent underlying device.
        invalidateChangedSource(source);
        if (!source.track.exists().get() || !source.device.exists().get()) return false;
        if (!source.contextObserved) {
            source.trackId = source.track.channelId().get();
            source.deviceName = source.device.name().get();
            source.deviceIndex = source.device.position().get();
            source.contextObserved = true;
        }
        return true;
    }

    private void invalidateTrackSources(int trackSlot) {
        for (ParameterSource source : parameterSources[trackSlot]) invalidateChangedSource(source);
    }

    private void invalidateChangedSource(ParameterSource source) {
        if (source.contextObserved && !sourceContextMatches(source)) {
            if (binding != null && source.isTarget(binding)) binding = null;
            // Names are metadata, not parameter identities. Renaming the observed proxy
            // need not replay unchanged IDs or values; invalidate the token, not its cache.
            if (source.track.exists().get() && source.device.exists().get()
                    && Objects.equals(source.trackId, source.track.channelId().get())
                    && source.deviceIndex == source.device.position().get()) {
                source.deviceName = source.device.name().get();
                return;
            }
            source.parameters = null;
            source.parameterIds = Collections.emptySet();
            source.idsObserved = false;
            source.contextObserved = false;
            source.cacheGeneration++;
        }
    }

    private boolean sourceContextMatches(ParameterSource source) {
        return source.contextObserved && source.track.exists().get() && source.device.exists().get()
                && Objects.equals(source.trackId, source.track.channelId().get())
                && Objects.equals(source.deviceName, source.device.name().get())
                && source.deviceIndex == source.device.position().get();
    }

    private Binding resolve(JsonObject params) {
        String trackId = params.get("trackId").getAsString();
        int slot = s.trackSlot(trackId);
        int index = params.get("deviceIndex").getAsInt();
        int deviceSlot = index - s.devices[slot].scrollPosition().get();
        if (deviceSlot < 0 || deviceSlot >= DEVICES) throw failure("outside_window", "Device is outside the current 16-device window.");
        Binding target = new Binding(trackId, slot, deviceSlot, index, params.get("expectedDeviceName").getAsString());
        if (!bankTargetMatches(target)) throw failure("stale_target", "Device index/name no longer matches the observed track.");
        return target;
    }

    private boolean bankTargetMatches(Binding target) {
        Track track = (Track) s.tracks.getItemAt(target.trackSlot);
        Device device = (Device) s.devices[target.trackSlot].getItemAt(target.deviceSlot);
        return track.exists().get() && target.trackId.equals(track.channelId().get())
                && device.exists().get() && device.position().get() == target.deviceIndex
                && target.deviceName.equals(device.name().get());
    }

    private boolean matches(Binding target) {
        return bankTargetMatches(target) && sourceContextMatches(parameterSources[target.trackSlot][target.deviceSlot]);
    }
    private static void nullable(JsonObject object, String key, Double value) {
        if (value == null) object.add(key, JsonNull.INSTANCE); else object.addProperty(key, value);
    }
    private static final class ParameterSource {
        final int trackSlot, deviceSlot;
        final Track track;
        final Device device;
        // Only slot metadata is eager. Empty slots allocate no parameter maps or states.
        Map<String, ParameterState> parameters;
        Set<String> parameterIds = Collections.emptySet();
        String trackId, deviceName;
        int deviceIndex;
        boolean idsObserved, contextObserved, lastIdCallbackAccepted;
        long cacheGeneration, idCallbacksReceived, idCallbacksRejected;
        long lastIdCallbackGeneration = -1, lastAcceptedIdCallbackGeneration = -1;
        ParameterSource(int trackSlot, int deviceSlot, Track track, Device device) {
            this.trackSlot = trackSlot; this.deviceSlot = deviceSlot;
            this.track = track; this.device = device;
        }
        ParameterState parameter(String id) {
            if (parameters == null) parameters = new LinkedHashMap<>();
            return parameters.computeIfAbsent(id, ParameterState::new);
        }
        boolean isTarget(Binding target) { return trackSlot == target.trackSlot && deviceSlot == target.deviceSlot; }
    }
    private static final class ParameterState {
        final String id;
        String name = "";
        Double value;
        long sequence;
        ParameterState(String id) { this.id = id; }
    }
    private static final class Binding {
        final String trackId, deviceName;
        final int trackSlot, deviceSlot, deviceIndex;
        String token = UUID.randomUUID().toString();
        boolean established;
        Binding(String trackId, int trackSlot, int deviceSlot, int deviceIndex, String deviceName) {
            this.trackId = trackId; this.trackSlot = trackSlot; this.deviceSlot = deviceSlot;
            this.deviceIndex = deviceIndex; this.deviceName = deviceName;
        }
        boolean sameTarget(Binding other) {
            return trackId.equals(other.trackId) && deviceIndex == other.deviceIndex && deviceName.equals(other.deviceName);
        }
        JsonObject json() {
            JsonObject result = new JsonObject();
            result.addProperty("trackId", trackId);
            result.addProperty("deviceIndex", deviceIndex);
            result.addProperty("deviceName", deviceName);
            result.addProperty("contextToken", token);
            return result;
        }
    }
}
