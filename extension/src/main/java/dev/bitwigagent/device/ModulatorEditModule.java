package dev.bitwigagent.device;

import com.google.gson.*;
import dev.bitwigagent.bitwig614.Bitwig614GridAdapter;
import dev.bitwigagent.bitwig614.Bitwig614ModulatorEditAccess;
import dev.bitwigagent.bitwig614.Bitwig614Runtime;
import dev.bitwigagent.core.BoundedJson;
import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.HostModule;
import dev.bitwigagent.core.HostOperation;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.core.Refs;
import dev.bitwigagent.core.RpcException;
import dev.bitwigagent.core.Scheduler;
import dev.bitwigagent.core.ValueInput;
import dev.bitwigagent.host.DeviceSources;
import dev.bitwigagent.host.NativeRuntime;
import dev.bitwigagent.host.TransportSafety;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Device-modulator edits: insert, delete, map and unmap, and the value write that parameters.set routes here for a
 * modulator control. Value writes may play; structural writes need a stopped transport. One native operation per call.
 */
public final class ModulatorEditModule implements HostModule {
    public static final Set<String> METHODS =
            Set.of("modulators.insert", "modulators.delete", "modulators.map", "modulators.unmap");
    static final int MAX_RESPONSE_CHARS = 240000;
    private final Scheduler scheduler;
    private final DeviceSources sources;
    private final TransportSafety transportSafety;
    private final NativeRuntime natives;
    private ModulatorEditor editor;
    private String unavailable;

    public ModulatorEditModule(
            Scheduler scheduler, DeviceSources sources, TransportSafety transportSafety, NativeRuntime natives) {
        this(scheduler, sources, transportSafety, natives, null);
    }

    public ModulatorEditModule(
            Scheduler scheduler,
            DeviceSources sources,
            TransportSafety transportSafety,
            NativeRuntime natives,
            ModulatorEditor injected) {
        this.scheduler = scheduler;
        this.sources = sources;
        this.transportSafety = transportSafety;
        this.natives = natives;
        editor = injected;
    }

    @Override
    public Set<String> methods() {
        return METHODS;
    }

    @Override
    public void validate(String method, JsonObject p) {
        if (!method.equals("modulators.insert")) return;
        if (p.has("typeId") == p.has("typeName"))
            throw Errors.notApplied(Reason.INVALID_PARAMS, "Supply exactly one of typeId or typeName.");
    }

    /** Lazily bound, so a binding failure surfaces as native_unavailable on use and in the status section. */
    ModulatorEditor editor(HostOperation op) {
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

    /** The edit bindings as bound now: available, or the reason they are not; binding is not native acceptance. */
    @Override
    public void status(HostOperation operation, JsonObject out, boolean full) {
        if (!full) return;
        JsonObject section = new JsonObject();
        section.addProperty("scope", "primary_modulators_on_root_device");
        String reason = null;
        try {
            editor(operation);
        } catch (RpcException error) {
            reason = error.getMessage();
        }
        section.addProperty("editAvailable", reason == null);
        section.addProperty("editUnavailableReason", reason);
        JsonArray methods = new JsonArray();
        METHODS.stream().sorted().forEach(methods::add);
        section.add("editMethods", methods);
        section.addProperty("mappingTargets", "grid_module_controls_and_other_modulator_controls_on_the_same_device");
        section.addProperty("structuralWritesRequireStoppedTransport", true);
        section.addProperty("nativeVerification", "not_established_by_binding");
        out.add("modulators", section);
    }

    @Override
    public void dispatch(HostOperation op, String method, JsonObject p) {
        scheduler.check(op);
        ModulatorEditor edits = editor(op);
        final Runnable safety = transportSafety.writeGuard(op, true);
        final boolean mapping = method.equals("modulators.map") || method.equals("modulators.unmap");
        final JsonObject modulatorRef =
                method.equals("modulators.insert") ? null : p.getAsJsonObject(mapping ? "source" : "target");
        final DeviceSources.Source device = sources.device(
                modulatorRef == null ? p.getAsJsonObject("parent") : modulatorRef.getAsJsonObject("device"), false);
        final JsonObject deviceRef = device.ref();
        final DeviceSources.RootReadTicket target = sources.rootRead(device);
        final UUID modulatorId = modulatorRef == null
                ? null
                : UUID.fromString(modulatorRef.get("id").getAsString());
        final ModulatorEditor.MapRequest request =
                mapping ? ModulatorEditor.MapRequest.parse(p, method.equals("modulators.unmap")) : null;
        if (mapping) requireSameDevice(device, p.getAsJsonObject("target").getAsJsonObject("owner"));
        final UUID typeId = p.has("typeId") ? UUID.fromString(p.get("typeId").getAsString()) : null;
        final String typeName = p.has("typeName") ? p.get("typeName").getAsString() : null;
        sources.guardRootRead(target);
        edits.execute(() -> {
            try {
                Runnable check = () -> {
                    scheduler.check(op);
                    target.check();
                    safety.run();
                };
                check.run();
                Runnable dispatch = () -> {
                    check.run();
                    op.markDispatched();
                };
                Consumer<JsonObject> complete = value -> deliver(op, target, safety, check, value);
                Consumer<Throwable> fail = error -> op.fail(failure(error));
                switch (method) {
                    case "modulators.map":
                    case "modulators.unmap":
                        edits.map(device, deviceRef, request, check, dispatch, complete, fail);
                        break;
                    case "modulators.delete":
                        edits.delete(
                                device,
                                deviceRef,
                                modulatorId,
                                modulatorRef.get("name").getAsString(),
                                check,
                                dispatch,
                                complete,
                                fail);
                        break;
                    case "modulators.insert":
                        edits.insert(device, deviceRef, typeId, typeName, check, dispatch, complete, fail);
                        break;
                    default:
                        throw Errors.notApplied(Reason.UNKNOWN_METHOD, "Unknown modulator edit.");
                }
            } catch (RuntimeException error) {
                op.fail(failure(error));
            } catch (Error error) {
                op.fail(Errors.notApplied(
                        Reason.NATIVE_UNAVAILABLE,
                        "Native modulator operation failed; inspect the loaded host binding and current state."));
            }
        });
    }

    /** A mapping's target control lives on the same device as its source modulator. */
    private void requireSameDevice(DeviceSources.Source device, JsonObject targetOwner) {
        if (sources.device(targetOwner.getAsJsonObject("device"), false) != device)
            throw Errors.invalid("The target control is not on the source modulator's device.");
    }

    /**
     * Writes one modulator control for parameters.set: the control and its owner are bound by their refs on the native
     * thread, and the value is verified by exact readback.
     */
    void setParameter(
            HostOperation op,
            ParameterResolver.ModuleParameter target,
            JsonObject ref,
            ValueInput input,
            JsonObject expect) {
        scheduler.check(op);
        op.inspect = Refs.read(ref);
        ModulatorEditor edits = editor(op);
        final Runnable safety = transportSafety.writeGuard(op, false);
        final DeviceSources.RootReadTicket ticket = sources.rootRead(target.device);
        final JsonObject deviceRef = target.device.ref();
        sources.guardRootRead(ticket);
        edits.execute(() -> {
            try {
                Runnable check = () -> {
                    scheduler.check(op);
                    ticket.check();
                    safety.run();
                };
                check.run();
                Runnable dispatch = () -> {
                    check.run();
                    op.markDispatched();
                };
                JsonObject value = edits.setValue(op, target, deviceRef, ref, input, expect, check, dispatch);
                deliver(op, ticket, safety, check, value);
            } catch (RuntimeException error) {
                op.fail(failure(error));
            } catch (Error error) {
                op.fail(Errors.notApplied(
                        Reason.NATIVE_UNAVAILABLE,
                        "Native modulator operation failed; inspect the loaded host binding and current state."));
            }
        });
    }

    private static Throwable failure(Throwable error) {
        return error instanceof RpcException
                ? error
                : Errors.notApplied(
                        Reason.NATIVE_UNAVAILABLE,
                        "Modulator operation failed: " + error.getClass().getSimpleName()
                                + "; inspect the exact host binding.");
    }

    private void deliver(
            HostOperation op, DeviceSources.RootReadTicket target, Runnable safety, Runnable check, JsonObject value) {
        check.run();
        final String serialized = BoundedJson.serialize(
                value,
                MAX_RESPONSE_CHARS,
                Reason.OUT_OF_BOUNDS,
                "Modulator response exceeds bounded transport size; read one modulator. Values are never truncated.");
        scheduler.schedule(
                op,
                () -> {
                    scheduler.check(op);
                    sources.guardRootRead(target);
                    safety.run();
                    JsonObject result = JsonParser.parseString(serialized).getAsJsonObject();
                    op.result.complete(Errors.withInspect(result, op.inspect));
                },
                0);
    }
}
