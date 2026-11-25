package dev.bitwigagent;

import com.bitwig.extension.controller.api.ControllerHost;
import com.google.gson.JsonObject;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import static dev.bitwigagent.HostServices.failure;

/** Composition and the single network-to-host boundary; domain state belongs to modules. */
public final class HostBridge implements RpcServer.Handler, AutoCloseable {
    static final Set<String> PROJECT_WRITES = Set.of("tracks.create", "devices.insert", "launcher.create", "launcher.import", "scenes.append", "grid.voicing.set",
            "devices.delete", "devices.move", "devices.layers.insert", "tracks.group", "arranger.clips.create", "arranger.clips.copyFromLauncher", "arranger.clips.edit",
            "grid.modules.insert", "grid.modules.move", "grid.modules.delete", "grid.modules.rename", "grid.connections.set", "grid.parameters.set");
    private static final Map<String, BiConsumer<String, JsonObject>> VALIDATORS = validators();
    private final HostServices services;
    private final Map<String, HostModule> modules = new HashMap<>();
    private final List<HostModule> domains = new ArrayList<>();

    public HostBridge(ControllerHost host) { this(host, System::nanoTime); }

    /** Test seam: injectable clock for the project settle window. */
    HostBridge(ControllerHost host, java.util.function.LongSupplier clock) {
        services = new HostServices(host, clock);
        ParameterModule parameters = new ParameterModule(services);
        register(parameters);
        ProjectModule project = new ProjectModule(services);
        register(new DiscoveryModule(services, parameters, project));
        register(new ActionModule(services));
        register(project);
        TransportModule transport = new TransportModule(services);
        register(transport);
        MixModule mix = new MixModule(services);
        register(mix);
        register(new DeviceModule(services));
        LauncherModule launcher = new LauncherModule(services);
        register(launcher);
        register(new LauncherSettingsModule(services, launcher));
        register(new MarkerModule(services));
        register(new MeterModule(services));
        register(new ArrangerModule(services));
        register(new ContextModule(services, transport, mix, launcher));
        register(new GridModule(services));
        ModulatorEditModule modulatorEdits = new ModulatorEditModule(services);
        register(new ModulatorModule(services, null, modulatorEdits));
        register(modulatorEdits);
        register(new ExtensionModule(services, () -> domains));
        if (!modules.keySet().equals(VALIDATORS.keySet()))
            throw new IllegalStateException("Module dispatch and validation registries differ");
    }

    /** Test seam. */
    HostServices services() { return services; }

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
        registerValidator(methods, ProjectModule.METHODS, ProjectModule::validateParams);
        registerValidator(methods, TransportModule.METHODS, TransportModule::validateParams);
        registerValidator(methods, MixModule.METHODS, MixModule::validateParams);
        registerValidator(methods, MarkerModule.METHODS, MarkerModule::validateParams);
        registerValidator(methods, MeterModule.METHODS, MeterModule::validateParams);
        registerValidator(methods, DeviceModule.METHODS, DeviceModule::validateParams);
        registerValidator(methods, LauncherModule.METHODS, LauncherModule::validateParams);
        registerValidator(methods, LauncherSettingsModule.METHODS, LauncherSettingsModule::validateParams);
        registerValidator(methods, ArrangerModule.METHODS, ArrangerModule::validateParams);
        registerValidator(methods, GridModule.METHODS, GridModule::validateParams);
        registerValidator(methods, ModulatorModule.METHODS, ModulatorModule::validateParams);
        registerValidator(methods, ModulatorEditModule.METHODS, ModulatorEditModule::validateParams);
        registerValidator(methods, ContextModule.METHODS, ContextModule::validateParams);
        registerValidator(methods, ExtensionModule.METHODS, ExtensionModule::validateParams);
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
            services.schedule(operation, () -> {
                if (preflight(method, copy))
                    operation.decorate = result -> addWarning(result, "Playback was started with an inactive audio engine: nothing will sound and captures will be silent. Run project.activateEngine and confirm status.engineActive=true.");
                module.dispatch(operation, method, copy);
            }, 0);
        } catch (RuntimeException | Error error) {
            operation.fail(error);
        }
        return operation.result;
    }

    /** Structure writes need a settled project; launches need an active engine. Returns whether to warn (playback start with inactive engine). */
    boolean preflight(String method, JsonObject params) {
        if (PROJECT_WRITES.contains(method)) services.requireProjectReady();
        if (method.equals("launcher.launch")) services.requireEngineActive("the clip launch");
        return method.equals("transport.set") && params.has("playing") && params.get("playing").getAsBoolean() && !services.engineActive();
    }

    static void addWarning(JsonObject result, String text) {
        if (!result.has("warnings") || !result.get("warnings").isJsonArray()) result.add("warnings", new com.google.gson.JsonArray());
        result.getAsJsonArray("warnings").add(text);
    }

    /** Forward the host's output-update boundary once per domain, not once per RPC method. */
    public void flush() {
        for (HostModule module : domains) module.flush();
    }

    @Override public void close() { services.close(); }
}
