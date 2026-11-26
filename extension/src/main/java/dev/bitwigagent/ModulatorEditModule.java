package dev.bitwigagent;

import com.google.gson.*;
import dev.bitwigagent.core.MethodSpecs;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/** Device-modulator reads/edits: strict RPC boundary, recording-safe value writes and stopped-transport structural writes, one native operation per call. */
final class ModulatorEditModule implements HostModule {
    static final Set<String> METHODS = Set.of(
            "modulators.get",
            "modulators.insert",
            "modulators.delete",
            "modulators.parameters.set",
            "modulators.map",
            "modulators.unmap");
    static final int MAX_RESPONSE_CHARS = 240000;
    private final Scheduler scheduler;
    private final DeviceSources sources;
    private final TransportSafety transportSafety;
    private final NativeRuntime natives;
    private final MethodSpecs specs;
    private ModulatorEditor editor;
    private String unavailable;

    ModulatorEditModule(
            Scheduler scheduler,
            DeviceSources sources,
            TransportSafety transportSafety,
            NativeRuntime natives,
            MethodSpecs specs) {
        this(scheduler, sources, transportSafety, natives, specs, null);
    }

    ModulatorEditModule(
            Scheduler scheduler,
            DeviceSources sources,
            TransportSafety transportSafety,
            NativeRuntime natives,
            MethodSpecs specs,
            ModulatorEditor injected) {
        this.scheduler = scheduler;
        this.sources = sources;
        this.transportSafety = transportSafety;
        this.natives = natives;
        this.specs = specs;
        editor = injected;
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
        switch (method) {
            case "modulators.get":
                Params.fields(p, Set.of("parent"), Set.of("modulatorId"));
                ModulatorEditor.validateParent(p);
                if (p.has("modulatorId")) ModulatorEditor.uuid(p, "modulatorId");
                return;
            case "modulators.insert":
                Params.fields(p, Set.of("parent", "expectedCount"), Set.of("typeId", "typeName"));
                ModulatorEditor.validateParent(p);
                ModulatorEditor.count(p, "expectedCount", 0);
                if (p.has("typeId") == p.has("typeName"))
                    throw Errors.notApplied(Reason.INVALID_PARAMS, "Supply exactly one of typeId or typeName.");
                if (p.has("typeId")) ModulatorEditor.uuid(p, "typeId");
                else {
                    String name = Params.string(p, "typeName");
                    if (name.length() > 128)
                        throw Errors.notApplied(Reason.INVALID_PARAMS, "typeName must be 1..128 UTF-16 units.");
                }
                return;
            case "modulators.delete":
                Params.fields(p, Set.of("parent", "modulatorId", "expectedCount"), Set.of());
                ModulatorEditor.validateParent(p);
                ModulatorEditor.uuid(p, "modulatorId");
                ModulatorEditor.count(p, "expectedCount", 1);
                return;
            case "modulators.parameters.set": {
                Params.fields(
                        p,
                        Set.of("parent", "modulatorId", "parameterId", "expectedKind", "expectedValue"),
                        Set.of("value", "display"));
                ModulatorEditor.validateParent(p);
                ModulatorEditor.uuid(p, "modulatorId");
                ModulatorEditor.text(p, "parameterId");
                String kind = Params.string(p, "expectedKind");
                if (!Set.of("float", "integer", "enum", "boolean").contains(kind))
                    throw Errors.notApplied(
                            Reason.INVALID_PARAMS, "expectedKind must be float, integer, enum or boolean.");
                GridParameterWriter.Request.parse(p);
                return;
            }
            case "modulators.map":
            case "modulators.unmap":
                ModulatorEditor.MapRequest.parse(p, method.equals("modulators.unmap"));
                return;
            default:
                throw Errors.notApplied(Reason.UNKNOWN_METHOD, "Unknown modulator edit.");
        }
    }

    /** Lazily bound, so a binding failure surfaces as native_unavailable on use and in capabilities. */
    private ModulatorEditor editor(HostOperation op) {
        if (editor != null) return editor;
        if (unavailable != null) throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, unavailable);
        Bitwig614Runtime.Access bindings = natives.get(op).access();
        if (bindings.gateError() != null)
            throw Errors.notApplied(
                    Reason.NATIVE_UNAVAILABLE, "Exact runtime gate is unavailable: " + bindings.gateError());
        try {
            Bitwig614GridAdapter grid = new Bitwig614GridAdapter(natives.get(op).access());
            editor = new ModulatorEditor(new Bitwig614ModulatorEditAccess(bindings, grid));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            unavailable = "Device-modulator edit bindings unavailable: " + Bitwig614Runtime.diagnostic(error);
            throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, unavailable);
        }
        return editor;
    }

    /** Merged into modulators.capabilities; binding availability is not native acceptance. */
    JsonObject capabilities(HostOperation op) {
        JsonObject result = new JsonObject();
        String reason = null;
        try {
            editor(op);
        } catch (RpcException error) {
            reason = error.getMessage();
        }
        result.addProperty("available", reason == null);
        result.addProperty("reason", reason);
        JsonArray methods = new JsonArray();
        METHODS.stream().sorted().forEach(methods::add);
        result.add("methods", methods);
        result.addProperty("mappingTargets", "grid_module_controls_and_other_modulator_controls_on_the_same_device");
        result.addProperty("insertion", "native_modulator_browser_insertion_point_appends_below_existing");
        result.addProperty("nativeUndo", "native_undo_transactions_not_verified_grouping");
        result.addProperty("nativeVerification", "not_established_by_binding");
        result.addProperty("structuralWritesRequireStoppedTransport", true);
        return result;
    }

    @Override
    public void dispatch(HostOperation op, String method, JsonObject p) {
        scheduler.check(op);
        ModulatorEditor edits = editor(op);
        final boolean writing = !specs.get(method).readOnly;
        final Runnable safety =
                writing ? transportSafety.writeGuard(op, !method.equals("modulators.parameters.set")) : null;
        final DeviceSources.RootReadTicket target = sources.rootRead(p.getAsJsonObject("parent"));
        final UUID trackId = UUID.fromString(target.trackId);
        final UUID modulatorId =
                p.has("modulatorId") ? UUID.fromString(p.get("modulatorId").getAsString()) : null;
        final ModulatorEditor.MapRequest mapping = method.equals("modulators.map") || method.equals("modulators.unmap")
                ? ModulatorEditor.MapRequest.parse(p, method.equals("modulators.unmap"))
                : null;
        final GridParameterWriter.Request request =
                method.equals("modulators.parameters.set") ? GridParameterWriter.Request.parse(p) : null;
        final int expectedCount =
                p.has("expectedCount") ? p.get("expectedCount").getAsInt() : -1;
        final UUID typeId = p.has("typeId") ? UUID.fromString(p.get("typeId").getAsString()) : null;
        final String typeName = p.has("typeName") ? p.get("typeName").getAsString() : null;
        guard(op, target);
        edits.execute(() -> {
            try {
                Runnable check = () -> {
                    scheduler.check(op);
                    target.check();
                    if (writing) safety.run();
                };
                check.run();
                Runnable dispatch = () -> {
                    check.run();
                    op.dispatched = true;
                };
                Consumer<JsonObject> complete = value -> deliver(op, target, safety, check, value);
                Consumer<Throwable> fail = error -> op.fail(
                        error instanceof RpcException
                                ? error
                                : Errors.notApplied(
                                        Reason.NATIVE_UNAVAILABLE,
                                        "Modulator operation failed: "
                                                + error.getClass().getSimpleName()));
                switch (method) {
                    case "modulators.get":
                        complete.accept(edits.get(target.device, trackId, modulatorId, check));
                        break;
                    case "modulators.parameters.set":
                        complete.accept(edits.setParameter(
                                target.device,
                                trackId,
                                modulatorId,
                                p.get("parameterId").getAsString(),
                                request,
                                check,
                                dispatch));
                        break;
                    case "modulators.map":
                    case "modulators.unmap":
                        edits.map(target.device, trackId, mapping, check, dispatch, complete, fail);
                        break;
                    case "modulators.delete":
                        edits.delete(
                                target.device, trackId, modulatorId, expectedCount, check, dispatch, complete, fail);
                        break;
                    case "modulators.insert":
                        edits.insert(
                                target.device,
                                trackId,
                                typeId,
                                typeName,
                                expectedCount,
                                check,
                                dispatch,
                                complete,
                                fail);
                        break;
                    default:
                        throw Errors.notApplied(Reason.UNKNOWN_METHOD, "Unknown modulator edit.");
                }
            } catch (RuntimeException error) {
                op.fail(
                        error instanceof RpcException
                                ? error
                                : Errors.notApplied(
                                        Reason.NATIVE_UNAVAILABLE,
                                        "Modulator operation failed: "
                                                + error.getClass().getSimpleName()
                                                + "; inspect the exact host binding."));
            } catch (Error error) {
                op.fail(Errors.notApplied(
                        Reason.NATIVE_UNAVAILABLE,
                        "Native modulator operation failed; inspect the loaded host binding and current state."));
            }
        });
    }

    private void deliver(
            HostOperation op, DeviceSources.RootReadTicket target, Runnable safety, Runnable check, JsonObject value) {
        check.run();
        final String serialized = BoundedJson.serialize(
                value,
                MAX_RESPONSE_CHARS,
                Reason.OUT_OF_BOUNDS,
                "Modulator response exceeds bounded transport size; pass modulatorId. Values are never truncated.");
        scheduler.schedule(
                op,
                () -> {
                    guard(op, target);
                    if (safety != null) safety.run();
                    JsonObject result = JsonParser.parseString(serialized).getAsJsonObject();
                    result.add("parent", target.parent());
                    result.addProperty("scope", "primary_modulators_on_root_device");
                    op.result.complete(result);
                },
                0);
    }

    private void guard(HostOperation op, DeviceSources.RootReadTicket target) {
        scheduler.check(op);
        sources.guardRootRead(target);
    }
}
