package dev.bitwigagent;

import com.google.gson.*;
import java.util.Set;
import java.util.UUID;
import static dev.bitwigagent.HostServices.failure;

/** Read-only root-device modulator boundary, independent of Grid and public parameter bindings. */
final class ModulatorModule implements HostModule {
    static final Set<String> METHODS = Set.of("modulators.capabilities", "modulators.list", "modulators.parameters.get");
    static final int MAX_RESPONSE_CHARS = 240000;
    private final HostServices s;
    private ModulatorAdapter adapter;

    ModulatorModule(HostServices services) { this(services, null); }
    ModulatorModule(HostServices services, ModulatorAdapter injected) { s = services; adapter = injected; }
    @Override public Set<String> methods() { return METHODS; }
    @Override public void validate(String method, JsonObject params) { validateParams(method, params); }

    static void validateParams(String method, JsonObject p) {
        if (method.equals("modulators.capabilities")) { Params.fields(p, Set.of(), Set.of()); return; }
        boolean parameter = method.equals("modulators.parameters.get");
        if (!parameter && !method.equals("modulators.list")) throw failure("unknown_method", "Unknown modulator read.");
        Params.fields(p, parameter ? Set.of("parent", "modulatorId", "parameterId") : Set.of("parent"),
                parameter ? Set.of() : Set.of("offset", "limit"));
        if (!p.get("parent").isJsonObject()) throw failure("invalid_params", "parent must be an object.");
        JsonObject parent = p.getAsJsonObject("parent");
        DeviceSources.validateParent(parent);
        integer(parent, "deviceIndex", 0, HostServices.DEVICES - 1);
        if (parameter) {
            if (!DeviceSources.canonicalUuid(Params.string(p, "modulatorId")))
                throw failure("invalid_params", "modulatorId must be a canonical current UUID.");
            JsonElement id = p.get("parameterId");
            if (!id.isJsonPrimitive() || !id.getAsJsonPrimitive().isString() || id.getAsString().length() > 1024)
                throw failure("invalid_params", "parameterId must be an exact string of at most 1024 UTF-16 units; empty is allowed.");
        } else {
            if (p.has("offset")) integer(p, "offset", 0, 100000);
            if (p.has("limit")) integer(p, "limit", 1, 128);
        }
    }

    private static int integer(JsonObject p, String key, int min, int max) {
        int result = Params.integer(p, key, min, max);
        if (!p.get(key).getAsString().matches("-?[0-9]+"))
            throw failure("invalid_params", key + " must be an integer, not a decimal or exponent.");
        return result;
    }

    @Override public void dispatch(HostOperation op, String method, JsonObject p) {
        s.check(op);
        if (adapter == null) adapter = new Bitwig613ModulatorAdapter(s.nativeRuntime(op).access("modulator_unavailable"));
        if (method.equals("modulators.capabilities")) {
            String serialized = serialize(adapter.capabilities());
            s.check(op); op.result.complete(JsonParser.parseString(serialized).getAsJsonObject()); return;
        }
        final DeviceSources.RootReadTicket target = s.sources.rootRead(p.getAsJsonObject("parent"));
        final UUID trackId = UUID.fromString(target.trackId);
        final boolean parameter = method.equals("modulators.parameters.get");
        final UUID modulatorId = parameter ? UUID.fromString(p.get("modulatorId").getAsString()) : null;
        final String parameterId = parameter ? p.get("parameterId").getAsString() : null;
        final int offset = p.has("offset") ? p.get("offset").getAsInt() : 0;
        final int limit = p.has("limit") ? p.get("limit").getAsInt() : 64;
        adapter.execute(() -> {
            try {
                // Never read Controller API getters or mutable observation fields on this queue.
                Runnable check = () -> { s.check(op); target.check(); };
                check.run();
                JsonObject value = parameter
                        ? adapter.parameter(target.device, trackId, modulatorId, parameterId, check)
                        : adapter.list(target.device, trackId, offset, limit, check);
                check.run();
                value.add("parent", target.parent());
                JsonObject identity = target.parent();
                identity.addProperty("identityScope", "observed_bank_proxy_not_persistent_device_identity");
                identity.addProperty("identityCaveat", "Unobserved same-metadata physical replacement cannot be detected by public proxies.");
                identity.addProperty("nativeOwningTrack", "corroborated_before_and_after_capture");
                identity.addProperty("devicePosition", "public_observed_only");
                value.add("target", identity);
                value.addProperty("scope", "primary_modulators_on_root_device");
                value.addProperty("nativeMutation", false);
                final String serialized = serialize(value);
                check.run();
                s.schedule(op, () -> {
                    s.sources.guardRootRead(target);
                    s.check(op);
                    op.result.complete(JsonParser.parseString(serialized).getAsJsonObject());
                }, 0);
            } catch (RuntimeException error) {
                op.fail(error instanceof RpcException ? error
                        : failure("modulator_unavailable", "Modulator read failed: " + error.getClass().getSimpleName() + "; inspect the exact host binding."));
            } catch (Error error) {
                op.fail(failure("modulator_unavailable", "Native modulator read failed; inspect the loaded host binding."));
            }
        });
    }

    private static String serialize(JsonObject value) {
        return BoundedJson.serialize(value, MAX_RESPONSE_CHARS, "modulator_bounds_exceeded",
                "Modulator response exceeds bounded transport size; reduce limit. Values are never truncated.");
    }
}
