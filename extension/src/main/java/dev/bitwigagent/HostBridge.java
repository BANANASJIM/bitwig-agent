package dev.bitwigagent;

import com.bitwig.extension.controller.api.*;
import com.google.gson.JsonObject;
import dev.bitwigagent.core.MethodSpecs;
import dev.bitwigagent.core.SchemaValidator;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Composition and the single network-to-host boundary; domain state belongs to modules. */
public final class HostBridge implements RpcServer.Handler, AutoCloseable {
    private final MethodSpecs specs;
    private final Scheduler scheduler;
    private final TransportSafety transportSafety;
    private final ProjectState projectState;
    private final Map<String, HostModule> modules = new HashMap<>();
    private final List<HostModule> domains = new ArrayList<>();

    public HostBridge(ControllerHost host, MethodSpecs specs) {
        this(host, specs, System::nanoTime);
    }

    /** {@code clock} is the monotonic nanosecond clock that request deadlines are measured on. */
    HostBridge(ControllerHost host, MethodSpecs specs, java.util.function.LongSupplier clock) {
        this.specs = specs;
        scheduler = new Scheduler(host, clock);
        Application application = host.createApplication();
        Transport transport = host.createTransport();
        Project project = host.getProject();
        TrackWindow window = new TrackWindow(host);
        projectState = new ProjectState(application, project, window, scheduler);
        transportSafety = new TransportSafety(transport);
        TrackRefs trackRefs = new TrackRefs(window);
        DeviceSources sources = new DeviceSources(window, trackRefs);
        NativeRuntime natives = new NativeRuntime(host, scheduler);
        NativeChains chains = new NativeChains(scheduler, sources, natives, null);
        SelectionLease selection = new SelectionLease();
        Holds holds = new Holds();
        LauncherStructureLock lock = new LauncherStructureLock(selection, holds);
        RemoteModule remotes = new RemoteModule(host, scheduler, window, sources, transportSafety);
        ModulatorEditModule modulatorEdits = new ModulatorEditModule(scheduler, sources, transportSafety, natives);
        ParameterModule parameters = new ParameterModule(
                scheduler, sources, chains, trackRefs, window, transportSafety, remotes, modulatorEdits);
        register(parameters);
        ParameterResolver parameterResolver = new ParameterResolver(sources, trackRefs, window, parameters, remotes);
        register(remotes);
        ProjectModule projectModule = new ProjectModule(scheduler, application, project, projectState, natives, holds);
        RenderModule render = new RenderModule(scheduler, window, trackRefs, project, transportSafety, natives, holds);
        register(new DiscoveryModule(host, application, transport, window, projectState, specs, holds, domains));
        register(new TrackListModule(window, trackRefs));
        register(new DeviceListModule(window, trackRefs, chains));
        register(new DeviceChainModule(scheduler, window, trackRefs, sources, chains, transportSafety));
        register(new ActionModule(scheduler, application, projectState, selection));
        register(projectModule);
        TransportModule transportModule =
                new TransportModule(transport, application, scheduler, transportSafety, projectState);
        register(transportModule);
        MixModule mix = new MixModule(host, scheduler, window, trackRefs, selection);
        register(mix);
        register(new TrackLifecycleModule(
                scheduler, window, trackRefs, application, transportSafety, selection, natives, holds, mix, null));
        register(new DeviceModule(scheduler, sources, chains, selection));
        LauncherTargets launcherTargets =
                new LauncherTargets(window, trackRefs, new SceneWindowLease(window, scheduler));
        LauncherState launcher =
                new LauncherState(host, scheduler, window, transportSafety, selection, lock, holds, launcherTargets);
        register(new LauncherModule(launcher, trackRefs));
        NoteModule notes = new NoteModule(launcher);
        register(notes);
        ArrangerModule arranger =
                new ArrangerModule(scheduler, window, trackRefs, projectState, transportSafety, natives, holds, null);
        register(new ClipModule(
                launcher,
                trackRefs,
                notes,
                new LauncherSettingsModule(scheduler, window, transportSafety, natives, holds),
                arranger));
        register(new SceneModule(scheduler, window, transportSafety, lock, launcherTargets, project));
        register(new MarkerModule(host, transport, scheduler, transportSafety, holds));
        register(new MeterModule(scheduler, window, trackRefs, transportSafety));
        register(arranger);
        register(new AutomationModule(scheduler, window, parameterResolver, transportSafety, natives, holds));
        register(new RoutingModule(scheduler, window, trackRefs, sources, transportSafety, natives, holds, specs));
        register(render);
        register(new ContextModule(scheduler, window, projectState, transportSafety, transportModule, mix, launcher));
        GridModule grid = new GridModule(scheduler, sources, transportSafety, natives);
        register(grid);
        register(new ModulatorModule(scheduler, sources, natives, null, modulatorEdits));
        register(modulatorEdits);
        register(new AckModule(holds, grid));
        register(new JobsModule(render, projectModule));
        requireHandlersMatch();
        Reason.check(specs.vocabulary());
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

    /** Validate a request against its schema and the module's rules; returns it with schema defaults filled. */
    JsonObject validate(String method, JsonObject params) {
        JsonObject filled = checkSchema(specs, method, params);
        modules.get(method).validate(method, filled);
        return filled;
    }

    static JsonObject checkSchema(MethodSpecs specs, String method, JsonObject params) {
        try {
            return specs.get(method).params.apply(params);
        } catch (SchemaValidator.Violation violation) {
            throw Errors.invalid(violation.getMessage());
        }
    }

    @Override
    public CompletableFuture<JsonObject> handle(String method, JsonObject params, long deadlineNanos) {
        HostOperation operation = new HostOperation(deadlineNanos);
        try {
            JsonObject copy = validate(method, params);
            HostModule module = modules.get(method);
            operation.safetyAtEnqueue = transportSafety.observation();
            scheduler.schedule(
                    operation,
                    () -> {
                        preflight(method);
                        module.dispatch(operation, method, copy);
                    },
                    0);
        } catch (RuntimeException | Error error) {
            operation.fail(error);
        }
        return operation.result;
    }

    /** Structure writes need a settled project; launches need an active engine. Test seam. */
    void preflight(String method) {
        MethodSpecs.Spec spec = specs.get(method);
        if (spec.projectWrite) projectState.requireReady();
        if (spec.engineRequired) projectState.requireEngineActive(method);
    }

    /** Forward the host's output-update boundary once per domain, not once per RPC method. */
    public void flush() {
        for (HostModule module : domains) module.flush();
    }

    @Override
    public void close() {
        scheduler.close();
    }
}
