package dev.bitwigagent;

import com.google.gson.*;
import java.util.Set;
import java.util.UUID;

/** Read-only root-device modulator boundary, independent of Grid and public parameter bindings. */
final class ModulatorModule implements HostModule {
    static final Set<String> METHODS =
            Set.of("modulators.capabilities", "modulators.list", "modulators.parameters.get");
    static final int MAX_RESPONSE_CHARS = 240000;
    private final Scheduler scheduler;
    private final DeviceSources sources;
    private final NativeRuntime natives;
    private ModulatorAdapter adapter;
    private final ModulatorEditModule edits;

    ModulatorModule(Scheduler scheduler, DeviceSources sources, NativeRuntime natives) {
        this(scheduler, sources, natives, null);
    }

    ModulatorModule(Scheduler scheduler, DeviceSources sources, NativeRuntime natives, ModulatorAdapter injected) {
        this(scheduler, sources, natives, injected, null);
    }

    ModulatorModule(
            Scheduler scheduler,
            DeviceSources sources,
            NativeRuntime natives,
            ModulatorAdapter injected,
            ModulatorEditModule edits) {
        this.scheduler = scheduler;
        this.sources = sources;
        this.natives = natives;
        adapter = injected;
        this.edits = edits;
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
        if (method.equals("modulators.capabilities")) {
            Params.fields(p, Set.of(), Set.of());
            return;
        }
        boolean parameter = method.equals("modulators.parameters.get");
        if (!parameter && !method.equals("modulators.list"))
            throw Errors.notApplied(Reason.UNKNOWN_METHOD, "Unknown modulator read.");
        Params.fields(
                p,
                parameter ? Set.of("parent", "modulatorId", "parameterId") : Set.of("parent"),
                parameter ? Set.of() : Set.of("offset", "limit"));
        if (!p.get("parent").isJsonObject())
            throw Errors.notApplied(Reason.INVALID_PARAMS, "parent must be an object.");
        JsonObject parent = p.getAsJsonObject("parent");
        DeviceSources.validateParent(parent);
        integer(parent, "deviceIndex", 0, HostLimits.DEVICES - 1);
        if (parameter) {
            if (!DeviceSources.canonicalUuid(Params.string(p, "modulatorId")))
                throw Errors.notApplied(Reason.INVALID_PARAMS, "modulatorId must be a canonical current UUID.");
            JsonElement id = p.get("parameterId");
            if (!id.isJsonPrimitive()
                    || !id.getAsJsonPrimitive().isString()
                    || id.getAsString().length() > 1024)
                throw Errors.notApplied(
                        Reason.INVALID_PARAMS,
                        "parameterId must be an exact string of at most 1024 UTF-16 units; empty is allowed.");
        } else {
            if (p.has("offset")) integer(p, "offset", 0, 100000);
            if (p.has("limit")) integer(p, "limit", 1, 128);
        }
    }

    private static int integer(JsonObject p, String key, int min, int max) {
        int result = Params.integer(p, key, min, max);
        if (!p.get(key).getAsString().matches("-?[0-9]+"))
            throw Errors.notApplied(Reason.INVALID_PARAMS, key + " must be an integer, not a decimal or exponent.");
        return result;
    }

    @Override
    public void dispatch(HostOperation op, String method, JsonObject p) {
        scheduler.check(op);
        if (adapter == null)
            adapter = new Bitwig614ModulatorAdapter(natives.get(op).access());
        if (method.equals("modulators.capabilities")) {
            JsonObject capabilities = adapter.capabilities();
            // Read-only fields above describe modulators.list/parameters.get; edit methods report separately here.
            if (edits != null) {
                JsonObject edit = edits.capabilities(op);
                capabilities.add("edit", edit);
                capabilities.add("writeAvailable", edit.get("available"));
            }
            String serialized = serialize(capabilities);
            scheduler.check(op);
            op.result.complete(JsonParser.parseString(serialized).getAsJsonObject());
            return;
        }
        final DeviceSources.RootReadTicket target = sources.rootRead(p.getAsJsonObject("parent"));
        final boolean parameter = method.equals("modulators.parameters.get");
        final UUID modulatorId =
                parameter ? UUID.fromString(p.get("modulatorId").getAsString()) : null;
        final String parameterId = parameter ? p.get("parameterId").getAsString() : null;
        final int offset = p.has("offset") ? p.get("offset").getAsInt() : 0;
        final int limit = p.has("limit") ? p.get("limit").getAsInt() : 64;
        adapter.execute(() -> {
            try {
                // Never read Controller API getters or mutable observation fields on this queue.
                Runnable check = () -> {
                    scheduler.check(op);
                    target.check();
                };
                check.run();
                JsonObject value = parameter
                        ? adapter.parameter(target.source, modulatorId, parameterId, check)
                        : adapter.list(target.source, offset, limit, check);
                check.run();
                value.add("parent", target.parent());
                JsonObject identity = target.parent();
                identity.addProperty("identityScope", "observed_bank_proxy_not_persistent_device_identity");
                identity.addProperty(
                        "identityCaveat",
                        "Unobserved same-metadata physical replacement cannot be detected by public proxies.");
                identity.addProperty("nativeOwningTrack", "corroborated_before_and_after_capture");
                identity.addProperty("devicePosition", "public_observed_only");
                value.add("target", identity);
                value.addProperty("scope", "primary_modulators_on_root_device");
                value.addProperty("nativeMutation", false);
                final String serialized = serialize(value);
                check.run();
                scheduler.schedule(
                        op,
                        () -> {
                            sources.guardRootRead(target);
                            scheduler.check(op);
                            op.result.complete(
                                    JsonParser.parseString(serialized).getAsJsonObject());
                        },
                        0);
            } catch (RuntimeException error) {
                op.fail(
                        error instanceof RpcException
                                ? error
                                : Errors.notApplied(
                                        Reason.NATIVE_UNAVAILABLE,
                                        "Modulator read failed: "
                                                + error.getClass().getSimpleName()
                                                + "; inspect the exact host binding."));
            } catch (Error error) {
                op.fail(Errors.notApplied(
                        Reason.NATIVE_UNAVAILABLE, "Native modulator read failed; inspect the loaded host binding."));
            }
        });
    }

    private static String serialize(JsonObject value) {
        return BoundedJson.serialize(
                value,
                MAX_RESPONSE_CHARS,
                Reason.OUT_OF_BOUNDS,
                "Modulator response exceeds bounded transport size; reduce limit. Values are never truncated.");
    }
}
