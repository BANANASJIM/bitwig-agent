package dev.bitwigagent;

import com.bitwig.extension.controller.api.ControllerHost;
import com.google.gson.JsonObject;
import dev.bitwigagent.core.MethodSpecs;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Composition and the single network-to-host boundary; domain state belongs to modules. */
public final class HostBridge implements RpcServer.Handler, AutoCloseable {
    private final MethodSpecs specs;
    private final HostServices services;
    private final Map<String, HostModule> modules = new HashMap<>();
    private final List<HostModule> domains = new ArrayList<>();

    public HostBridge(ControllerHost host, MethodSpecs specs) {
        this(host, specs, System::nanoTime);
    }

    /** {@code clock} is the monotonic nanosecond clock that request deadlines are measured on. */
    HostBridge(ControllerHost host, MethodSpecs specs, java.util.function.LongSupplier clock) {
        this.specs = specs;
        services = new HostServices(host, clock);
        ParameterModule parameters = new ParameterModule(services);
        register(parameters);
        ProjectModule project = new ProjectModule(services);
        RenderModule render = new RenderModule(services);
        register(new DiscoveryModule(services, specs, parameters, project, render));
        register(new ActionModule(services));
        register(project);
        TransportModule transport = new TransportModule(services);
        register(transport);
        MixModule mix = new MixModule(services);
        register(mix);
        register(new DeviceModule(services));
        LauncherModule launcher = new LauncherModule(services);
        register(launcher);
        register(new SceneModule(services, launcher::requireStructureSafe));
        register(new LauncherSettingsModule(services, launcher));
        register(new MarkerModule(services));
        register(new MeterModule(services));
        register(new ArrangerModule(services));
        register(new AutomationModule(services));
        register(new RoutingModule(services, specs));
        register(render);
        register(new ContextModule(services, transport, mix, launcher));
        register(new GridModule(services));
        ModulatorEditModule modulatorEdits = new ModulatorEditModule(services, specs);
        register(new ModulatorModule(services, null, modulatorEdits));
        register(modulatorEdits);
        register(new ExtensionModule(services, () -> domains));
        requireHandlersMatch();
    }

    /** Test seam. */
    HostServices services() {
        return services;
    }

    private void register(HostModule module) {
        domains.add(module);
        for (String method : module.methods()) {
            if (modules.putIfAbsent(method, module) != null)
                throw new IllegalStateException("Duplicate host method: " + method);
        }
    }

    private void requireHandlersMatch() {
        Set<String> unhandled = new TreeSet<>(specs.methods());
        unhandled.removeAll(modules.keySet());
        Set<String> unspecified = new TreeSet<>(modules.keySet());
        unspecified.removeAll(specs.methods());
        if (!unhandled.isEmpty() || !unspecified.isEmpty())
            throw new IllegalStateException(
                    "Protocol bundle and registered handlers differ; bundle methods without a handler: " + unhandled
                            + "; handlers without a bundle method: " + unspecified);
    }

    void validate(String method, JsonObject params) {
        modules.get(method).validate(method, params);
    }

    @Override
    public CompletableFuture<JsonObject> handle(String method, JsonObject params, long deadlineNanos) {
        HostOperation operation = new HostOperation(deadlineNanos);
        try {
            validate(method, params);
            HostModule module = modules.get(method);
            JsonObject copy = params.deepCopy();
            services.schedule(
                    operation,
                    () -> {
                        if (preflight(method, copy))
                            operation.decorate = result -> addWarning(
                                    result,
                                    "Playback was started with an inactive audio engine: nothing will sound and captures will be silent. Run project.activateEngine and confirm status.engineActive=true.");
                        module.dispatch(operation, method, copy);
                    },
                    0);
        } catch (RuntimeException | Error error) {
            operation.fail(error);
        }
        return operation.result;
    }

    /** Structure writes need a settled project; launches need an active engine. Returns whether to warn (playback start with inactive engine). */
    boolean preflight(String method, JsonObject params) {
        MethodSpecs.Spec spec = specs.get(method);
        if (spec.projectWrite) services.requireProjectReady();
        if (spec.engineRequired) services.requireEngineActive(method);
        return method.equals("transport.set")
                && params.has("playing")
                && params.get("playing").getAsBoolean()
                && !services.engineActive();
    }

    static void addWarning(JsonObject result, String text) {
        if (!result.has("warnings") || !result.get("warnings").isJsonArray())
            result.add("warnings", new com.google.gson.JsonArray());
        result.getAsJsonArray("warnings").add(text);
    }

    /** Forward the host's output-update boundary once per domain, not once per RPC method. */
    public void flush() {
        for (HostModule module : domains) module.flush();
    }

    @Override
    public void close() {
        services.close();
    }
}
