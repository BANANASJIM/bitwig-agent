package dev.bitwigagent;

import com.bitwig.extension.controller.api.ControllerHost;
import com.google.gson.JsonObject;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import static dev.bitwigagent.HostServices.failure;

/** Composition and the single network-to-host boundary; domain state belongs to modules. */
public final class HostBridge implements RpcServer.Handler, AutoCloseable {
    private static final Map<String, BiConsumer<String, JsonObject>> VALIDATORS = validators();
    private final HostServices services;
    private final Map<String, HostModule> modules = new HashMap<>();
    private final List<HostModule> domains = new ArrayList<>();

    public HostBridge(ControllerHost host) {
        services = new HostServices(host);
        ParameterModule parameters = new ParameterModule(services);
        register(parameters);
        register(new DiscoveryModule(services, parameters));
        register(new ActionModule(services));
        register(new TransportModule(services));
        register(new MixModule(services));
        register(new DeviceModule(services));
        register(new LauncherModule(services));
        register(new GridModule(services));
        register(new ModulatorModule(services));
        if (!modules.keySet().equals(VALIDATORS.keySet()))
            throw new IllegalStateException("Module dispatch and validation registries differ");
    }

    private void register(HostModule module) {
        domains.add(module);
        for (String method : module.methods()) {
            if (modules.putIfAbsent(method, module) != null)
                throw new IllegalStateException("Duplicate host method: " + method);
        }
    }

    private static Map<String, BiConsumer<String, JsonObject>> validators() {
        Map<String, BiConsumer<String, JsonObject>> methods = new TreeMap<>();
        registerValidator(methods, DiscoveryModule.METHODS, DiscoveryModule::validateParams);
        registerValidator(methods, ParameterModule.METHODS, ParameterModule::validateParams);
        registerValidator(methods, ActionModule.METHODS, ActionModule::validateParams);
        registerValidator(methods, TransportModule.METHODS, TransportModule::validateParams);
        registerValidator(methods, MixModule.METHODS, MixModule::validateParams);
        registerValidator(methods, DeviceModule.METHODS, DeviceModule::validateParams);
        registerValidator(methods, LauncherModule.METHODS, LauncherModule::validateParams);
        registerValidator(methods, GridModule.METHODS, GridModule::validateParams);
        registerValidator(methods, ModulatorModule.METHODS, ModulatorModule::validateParams);
        return Collections.unmodifiableMap(methods);
    }

    private static void registerValidator(Map<String, BiConsumer<String, JsonObject>> registry,
            Set<String> methods, BiConsumer<String, JsonObject> validator) {
        for (String method : methods) {
            if (registry.putIfAbsent(method, validator) != null)
                throw new IllegalStateException("Duplicate host validator: " + method);
        }
    }

    /** The same allowlist drives transport admission, host validation and capability discovery. */
    static Set<String> supportedMethods() { return VALIDATORS.keySet(); }

    static void validate(String method, JsonObject params) {
        BiConsumer<String, JsonObject> validator = VALIDATORS.get(method);
        if (validator == null) throw failure("unknown_method", "Unsupported method: " + method);
        validator.accept(method, params);
    }

    @Override public CompletableFuture<JsonObject> handle(String method, JsonObject params, long deadlineNanos) {
        HostOperation operation = new HostOperation(deadlineNanos);
        try {
            validate(method, params);
            JsonObject copy = params.deepCopy();
            HostModule module = modules.get(method);
            services.schedule(operation, () -> module.dispatch(operation, method, copy), 0);
        } catch (RuntimeException | Error error) {
            operation.fail(error);
        }
        return operation.result;
    }

    /** Forward the host's output-update boundary once per domain, not once per RPC method. */
    public void flush() {
        for (HostModule module : domains) module.flush();
    }

    @Override public void close() { services.close(); }
}
