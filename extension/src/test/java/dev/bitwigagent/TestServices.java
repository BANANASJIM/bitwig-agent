package dev.bitwigagent;

import com.bitwig.extension.controller.api.*;
import dev.bitwigagent.core.MethodSpecs;
import java.util.function.LongSupplier;

/** The shared services a module test needs, wired as {@link HostBridge} wires them, with a factory per module. */
final class TestServices {
    final ControllerHost host;
    final Scheduler scheduler;
    final Application application;
    final Transport transport;
    final Project project;
    final TrackWindow window;
    final ProjectState projectState;
    final TransportSafety transportSafety;
    final TrackRefs trackRefs;
    final DeviceSources sources;
    final NativeRuntime natives;
    final SelectionLease selection;
    final Holds holds = new Holds();
    final LauncherStructureLock lock;
    private LauncherTargets launcherTargets;
    private LauncherState launcherState;

    TestServices(ControllerHost host, LongSupplier clock) {
        this.host = host;
        scheduler = new Scheduler(host, clock);
        application = host.createApplication();
        transport = host.createTransport();
        project = host.getProject();
        window = new TrackWindow(host);
        projectState = new ProjectState(application, project, window, scheduler);
        transportSafety = new TransportSafety(transport);
        trackRefs = new TrackRefs(window);
        sources = new DeviceSources(window, trackRefs);
        natives = new NativeRuntime(host, scheduler);
        selection = new SelectionLease();
        lock = new LauncherStructureLock(selection, holds);
    }

    /** What HostBridge does for a request: capture the safety state, then queue the dispatch. */
    void enqueue(HostOperation operation, Runnable dispatch) {
        operation.safetyAtEnqueue = transportSafety.observation();
        scheduler.schedule(operation, dispatch, 0);
    }

    ArrangerModule arranger(ArrangerModule.Access access) {
        return new ArrangerModule(scheduler, window, trackRefs, projectState, transportSafety, natives, holds, access);
    }

    AutomationModule automation(AutomationModule.Access access) {
        return new AutomationModule(scheduler, window, sources, transportSafety, natives, holds, access);
    }

    DeviceModule device() {
        return new DeviceModule(host, scheduler, window, sources, transportSafety, selection);
    }

    AckModule ack(GridModule grid) {
        return new AckModule(holds, grid);
    }

    GridModule grid(GridAdapter adapter) {
        return new GridModule(scheduler, sources, transportSafety, natives, adapter);
    }

    /** The scene window lease and the refs resolved through it, shared by every launcher module of one test. */
    LauncherTargets launcherTargets() {
        if (launcherTargets == null)
            launcherTargets = new LauncherTargets(window, trackRefs, new SceneWindowLease(window, scheduler));
        return launcherTargets;
    }

    LauncherState launcherState() {
        if (launcherState == null)
            launcherState = new LauncherState(
                    host, scheduler, window, transportSafety, selection, lock, holds, launcherTargets());
        return launcherState;
    }

    LauncherModule launcher() {
        return new LauncherModule(launcherState(), trackRefs);
    }

    NoteModule notes() {
        return new NoteModule(launcherState());
    }

    ClipModule clips(LauncherSettingsModule.Access settings, ArrangerModule.Access arranger) {
        return new ClipModule(launcherState(), trackRefs, notes(), launcherSettings(settings), arranger(arranger));
    }

    ContextModule context(MixModule mix, TransportModule transport) {
        return new ContextModule(scheduler, window, projectState, transportSafety, transport, mix, launcherState());
    }

    LauncherSettingsModule launcherSettings(LauncherSettingsModule.Access access) {
        return new LauncherSettingsModule(scheduler, window, transportSafety, natives, holds, access);
    }

    MarkerModule marker() {
        return new MarkerModule(host, transport, scheduler, transportSafety, holds);
    }

    MeterModule meter() {
        return new MeterModule(scheduler, window, transportSafety);
    }

    MixModule mix() {
        return mix(null);
    }

    MixModule mix(MixModule.GroupAccess access) {
        return new MixModule(host, scheduler, window, application, transportSafety, selection, natives, holds, access);
    }

    ModulatorModule modulator(ModulatorAdapter adapter) {
        return new ModulatorModule(scheduler, sources, natives, adapter);
    }

    ParameterModule parameters() {
        return new ParameterModule(scheduler, sources);
    }

    ParameterResolver parameterResolver(ParameterModule parameters, DeviceModule devices) {
        return new ParameterResolver(sources, trackRefs, window, parameters, devices);
    }

    ProjectModule projectModule(ProjectModule.SaveAsAccess access) {
        return new ProjectModule(scheduler, application, project, projectState, natives, holds, access);
    }

    RenderModule render(RenderModule.Access access) {
        return new RenderModule(scheduler, window, trackRefs, project, transportSafety, natives, holds, access);
    }

    RoutingModule routing(MethodSpecs specs, RoutingModule.Access access) {
        return new RoutingModule(scheduler, window, sources, transportSafety, natives, holds, specs, access);
    }

    SceneModule scene() {
        return new SceneModule(scheduler, window, transportSafety, lock, launcherTargets(), project);
    }

    TransportModule transportModule() {
        return new TransportModule(transport, application, scheduler, transportSafety, projectState);
    }
}
