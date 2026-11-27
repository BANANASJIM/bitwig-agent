package dev.bitwigagent;

import com.bitwig.extension.controller.api.*;
import com.google.gson.JsonObject;
import dev.bitwigagent.actions.ActionModule;
import dev.bitwigagent.arranger.ArrangerModule;
import dev.bitwigagent.arranger.MarkerModule;
import dev.bitwigagent.automation.AutomationModule;
import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.Holds;
import dev.bitwigagent.core.HostModule;
import dev.bitwigagent.core.HostOperation;
import dev.bitwigagent.core.MethodSpecs;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.core.Scheduler;
import dev.bitwigagent.core.SchemaValidator;
import dev.bitwigagent.device.ChainModule;
import dev.bitwigagent.device.DeviceChainModule;
import dev.bitwigagent.device.DeviceListModule;
import dev.bitwigagent.device.DeviceModule;
import dev.bitwigagent.device.ModulatorEditModule;
import dev.bitwigagent.device.ModulatorModule;
import dev.bitwigagent.device.NativeChains;
import dev.bitwigagent.device.NativeRemoteValues;
import dev.bitwigagent.device.ParameterModule;
import dev.bitwigagent.device.ParameterResolver;
import dev.bitwigagent.device.RemoteModule;
import dev.bitwigagent.grid.GridModule;
import dev.bitwigagent.host.DeviceSources;
import dev.bitwigagent.host.LauncherStructureLock;
import dev.bitwigagent.host.ProjectState;
import dev.bitwigagent.host.SelectionLease;
import dev.bitwigagent.host.TrackRefs;
import dev.bitwigagent.host.TrackWindow;
import dev.bitwigagent.host.TransportSafety;
import dev.bitwigagent.launcher.ClipModule;
import dev.bitwigagent.launcher.LauncherModule;
import dev.bitwigagent.launcher.LauncherSettingsModule;
import dev.bitwigagent.launcher.LauncherState;
import dev.bitwigagent.launcher.LauncherTargets;
import dev.bitwigagent.launcher.NativeNotes;
import dev.bitwigagent.launcher.NoteModule;
import dev.bitwigagent.launcher.SceneModule;
import dev.bitwigagent.launcher.SceneWindowLease;
import dev.bitwigagent.mix.MeterModule;
import dev.bitwigagent.mix.MixModule;
import dev.bitwigagent.mix.TrackLifecycleModule;
import dev.bitwigagent.mix.TrackListModule;
import dev.bitwigagent.nativehost.NativePorts;
import dev.bitwigagent.project.ContextModule;
import dev.bitwigagent.project.DiscoveryModule;
import dev.bitwigagent.project.KeySignatureModule;
import dev.bitwigagent.project.ProjectModule;
import dev.bitwigagent.recovery.AckModule;
import dev.bitwigagent.recovery.JobsModule;
import dev.bitwigagent.render.RenderModule;
import dev.bitwigagent.routing.RoutingModule;
import dev.bitwigagent.rpc.RpcServer;
import dev.bitwigagent.transport.TransportModule;
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
        NativePorts natives = new NativePorts(host, scheduler);
        NativeChains chains = new NativeChains(scheduler, sources, natives.chains());
        SelectionLease selection = new SelectionLease();
        Holds holds = new Holds();
        LauncherStructureLock lock = new LauncherStructureLock(selection, holds);
        RemoteModule remotes = new RemoteModule(host, scheduler, window, sources, transportSafety);
        ModulatorEditModule modulatorEdits =
                new ModulatorEditModule(scheduler, sources, transportSafety, natives.modulatorEdits());
        ParameterModule parameters = new ParameterModule(
                scheduler, sources, chains, trackRefs, window, transportSafety, remotes, modulatorEdits);
        register(parameters);
        ParameterResolver parameterResolver = new ParameterResolver(sources, trackRefs, window, parameters, remotes);
        remotes.nativeValues = new NativeRemoteValues(natives.remotePages(), chains);
        register(remotes);
        ProjectModule projectModule =
                new ProjectModule(scheduler, application, project, projectState, natives.saveAs(), holds);
        register(new DiscoveryModule(host, application, transport, window, projectState, specs, holds, domains));
        register(new DeviceListModule(window, trackRefs, chains));
        register(new DeviceChainModule(scheduler, window, trackRefs, sources, chains, transportSafety));
        register(new ChainModule(scheduler, chains, transportSafety));
        register(new ActionModule(scheduler, application, projectState, selection));
        register(projectModule);
        register(new KeySignatureModule(scheduler, project, transportSafety, natives.keySignature(), holds));
        TransportModule transportModule =
                new TransportModule(transport, application, scheduler, transportSafety, projectState);
        register(transportModule);
        MixModule mix = new MixModule(host, scheduler, window, trackRefs, selection);
        register(mix);
        register(new TrackListModule(window, trackRefs, mix));
        register(new TrackLifecycleModule(
                scheduler,
                window,
                trackRefs,
                application,
                transportSafety,
                selection,
                natives.trackGroup(),
                holds,
                mix));
        register(new DeviceModule(scheduler, sources, chains, selection));
        LauncherTargets launcherTargets =
                new LauncherTargets(window, trackRefs, new SceneWindowLease(window, scheduler));
        LauncherState launcher =
                new LauncherState(host, scheduler, window, transportSafety, selection, lock, holds, launcherTargets);
        register(new LauncherModule(launcher, trackRefs, application, transport));
        RenderModule render = new RenderModule(
                scheduler, window, trackRefs, project, transportSafety, natives.render(), holds, launcher);
        NoteModule notes = new NoteModule(launcher, new NativeNotes(natives.noteWrites()));
        register(notes);
        MarkerModule markers = new MarkerModule(host, transport, scheduler, transportSafety, holds);
        ArrangerModule arranger = new ArrangerModule(
                scheduler,
                window,
                trackRefs,
                projectState,
                transportSafety,
                natives.arranger(),
                holds,
                markers,
                transport);
        register(new ClipModule(
                launcher,
                trackRefs,
                notes,
                new LauncherSettingsModule(scheduler, window, transportSafety, natives.launcherSettings(), holds),
                arranger));
        register(new SceneModule(scheduler, window, transportSafety, lock, launcherTargets, project));
        register(markers);
        register(new MeterModule(scheduler, window, trackRefs, transportSafety));
        register(arranger);
        register(new AutomationModule(
                scheduler, window, parameterResolver, transportSafety, natives.automation(), holds));
        register(new RoutingModule(
                scheduler, window, trackRefs, sources, transportSafety, natives.routing(), holds, specs));
        register(render);
        register(new ContextModule(scheduler, window, projectState, transportSafety, transportModule, mix, launcher));
        GridModule grid = new GridModule(scheduler, sources, transportSafety, natives.grid());
        register(grid);
        register(new ModulatorModule(scheduler, sources, natives.modulators(), modulatorEdits));
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

    public static JsonObject checkSchema(MethodSpecs specs, String method, JsonObject params) {
        try {
            return specs.get(method).params.apply(params);
        } catch (SchemaValidator.Violation violation) {
            throw Errors.invalid(violation.getMessage());
        }
    }

    @Override
    public CompletableFuture<JsonObject> handle(String method, JsonObject params, long deadlineNanos) {
        HostOperation operation =
                new HostOperation(deadlineNanos, specs.contains(method) ? specs.get(method).inspect : null);
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
