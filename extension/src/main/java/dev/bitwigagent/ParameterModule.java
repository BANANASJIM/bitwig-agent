package dev.bitwigagent;

import com.bitwig.extension.controller.api.*;
import com.bitwig.extension.callback.*;
import com.google.gson.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static dev.bitwigagent.HostServices.*;

/** Source-specific direct-parameter observations on fixed bank proxies, never a cursor. */
final class ParameterModule implements HostModule {
    static final Set<String> METHODS = Set.of("parameters.list", "parameters.set", "parameters.layer.list", "parameters.layer.set");
    private final HostServices s;
    private final Map<DeviceSources.Source, ParameterSource> parameterSources = new IdentityHashMap<>();
    private long valueSequence;
    private Binding binding;
    private HostOperation parameterRequest;

    ParameterModule(HostServices s) {
        this.s = s;
        for (DeviceSources.Source identity : s.sources.all) {
            ParameterSource source = new ParameterSource(identity);
            parameterSources.put(identity, source);
            identity.listeners.add((structural, name) -> {
                if ((structural || name) && binding != null && binding.source == source) binding = null;
                if (structural) {
                    source.parameters = null;
                    source.parameterIds = Collections.emptySet();
                    source.idsObserved = false;
                    source.cacheGeneration++;
                }
            });
            identity.device.addDirectParameterIdObserver(ids -> onParameterIds(source, (String[]) ids));
            identity.device.addDirectParameterNameObserver(256, (id, name) -> {
                if (acceptParameterObservation(source)) source.parameter(id).name = name;
            });
            identity.device.addDirectParameterNormalizedValueObserver((id, value) -> {
                if (!acceptParameterObservation(source)) return;
                ParameterState parameter = source.parameter(id);
                parameter.value = Double.isFinite(value) ? value : null;
                parameter.sequence = ++valueSequence;
            });
        }
    }

    @Override public Set<String> methods() { return METHODS; }
    @Override public void validate(String method, JsonObject params) { validateParams(method, params); }
    static void validateParams(String method, JsonObject params) {
        if (!METHODS.contains(method)) throw failure("unknown_method", "Unknown parameter method.");
        if (method.startsWith("parameters.layer.")) {
            DeviceSources.validateLayer(params, method.endsWith(".set"), false); return;
        }
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
        if (method.endsWith(".list")) bind(request, params);
        else setParameter(request, params);
    }

    JsonObject diagnostics() {
        JsonObject diagnostics = new JsonObject();
        ParameterSource source = binding == null ? null : binding.source;
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
        ParameterSource source = resolve(params);
        if (binding != null && binding.established && binding.source == source && matches(binding) && source.idsObserved) {
            request.result.complete(listParameters());
            return;
        }
        Binding target = new Binding(source);
        binding = target;
        s.check(request);
        awaitDevice(request, target);
    }

    private void awaitDevice(HostOperation request, Binding target) {
        if (binding != target || !bankTargetMatches(target)) throw failure("stale_target", "Target changed during binding.");
        if (!matches(target) || !target.source.idsObserved) {
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
        ParameterSource source = binding.source;
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
        ParameterSource requested = resolve(params);
        Binding target = binding;
        String token = params.get("contextToken").getAsString();
        if (target == null || !target.established || !target.token.equals(token)
                || target.source != requested || !matches(target)) {
            throw failure("stale_context", "Read the matching parameter list method again before writing this target.");
        }
        String id = params.get("parameterId").getAsString();
        ParameterSource source = target.source;
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
        source.identity.device.setDirectParameterValueNormalized(id, Math.round(requestedValue * (RESOLUTION - 1)), RESOLUTION);
        long observeUntil = Math.min(request.deadline - TimeUnit.MILLISECONDS.toNanos(40),
                System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(750));
        awaitValue(request, target, token, id, requestedValue, applied, before, observeUntil);
    }

    private void awaitValue(HostOperation request, Binding target, String token, String id,
            double requested, double applied, long before, long observeUntil) {
        if (binding != target || !target.token.equals(token) || !matches(target)) {
            throw new RpcException("stale_context", "Target changed after dispatch; outcome unknown.", "unknown");
        }
        ParameterSource source = target.source;
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
        if (binding != null && binding.established && source == binding.source && !source.parameterIds.equals(next)) {
            binding.token = UUID.randomUUID().toString();
        }
        source.parameterIds = next;
        if (source.parameters != null) source.parameters.keySet().retainAll(next);
        for (String id : next) source.parameter(id);
        source.idsObserved = true;
    }

    private boolean acceptParameterObservation(ParameterSource source) {
        // Registration identifies a fixed proxy, not the originating physical device.
        // Nested sources that physically rebound stay closed; a callback is not a barrier.
        return source.identity.acceptDirectObservation();
    }

    private ParameterSource resolve(JsonObject params) {
        DeviceSources.Source identity = params.has("parent") ? s.sources.child(params) : s.sources.root(params);
        return parameterSources.get(identity);
    }

    private boolean bankTargetMatches(Binding target) {
        DeviceSources.Source identity = target.source.identity;
        return identity.directReady() && identity.epoch == target.epoch && identity.revision == target.revision;
    }

    private boolean matches(Binding target) { return bankTargetMatches(target); }
    private static void nullable(JsonObject object, String key, Double value) {
        if (value == null) object.add(key, JsonNull.INSTANCE); else object.addProperty(key, value);
    }
    private static final class ParameterSource {
        final DeviceSources.Source identity;
        Map<String, ParameterState> parameters;
        Set<String> parameterIds = Collections.emptySet();
        boolean idsObserved, lastIdCallbackAccepted;
        long cacheGeneration, idCallbacksReceived, idCallbacksRejected;
        long lastIdCallbackGeneration = -1, lastAcceptedIdCallbackGeneration = -1;
        ParameterSource(DeviceSources.Source identity) { this.identity = identity; }
        ParameterState parameter(String id) {
            if (parameters == null) parameters = new LinkedHashMap<>();
            return parameters.computeIfAbsent(id, ParameterState::new);
        }
    }
    private static final class ParameterState {
        final String id;
        String name = "";
        Double value;
        long sequence;
        ParameterState(String id) { this.id = id; }
    }
    private static final class Binding {
        final ParameterSource source;
        final long epoch, revision;
        final JsonObject address;
        String token = UUID.randomUUID().toString();
        boolean established;
        Binding(ParameterSource source) {
            this.source = source; epoch = source.identity.epoch; revision = source.identity.revision;
            address = source.identity.address();
        }
        JsonObject json() {
            JsonObject result = address.deepCopy();
            if (source.identity.layer == null) {
                result.add("deviceName", result.remove("expectedDeviceName"));
            }
            result.addProperty("contextToken", token);
            return result;
        }
    }
}
