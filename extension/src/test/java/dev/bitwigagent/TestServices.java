package dev.bitwigagent;

import com.bitwig.extension.controller.api.*;
import dev.bitwigagent.arranger.ArrangerModule;
import dev.bitwigagent.arranger.MarkerModule;
import dev.bitwigagent.automation.AutomationModule;
import dev.bitwigagent.core.Holds;
import dev.bitwigagent.core.HostOperation;
import dev.bitwigagent.core.MethodSpecs;
import dev.bitwigagent.core.Scheduler;
import dev.bitwigagent.device.DeviceChainModule;
import dev.bitwigagent.device.DeviceListModule;
import dev.bitwigagent.device.DeviceModule;
import dev.bitwigagent.device.FakeChains;
import dev.bitwigagent.device.ModulatorAdapter;
import dev.bitwigagent.device.ModulatorEditModule;
import dev.bitwigagent.device.ModulatorEditor;
import dev.bitwigagent.device.ModulatorModule;
import dev.bitwigagent.device.NativeChains;
import dev.bitwigagent.device.ParameterModule;
import dev.bitwigagent.device.ParameterResolver;
import dev.bitwigagent.device.RemoteModule;
import dev.bitwigagent.grid.GridAdapter;
import dev.bitwigagent.grid.GridModule;
import dev.bitwigagent.host.DeviceSources;
import dev.bitwigagent.host.LauncherStructureLock;
import dev.bitwigagent.host.NativeRuntime;
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
import dev.bitwigagent.launcher.NoteModule;
import dev.bitwigagent.launcher.SceneModule;
import dev.bitwigagent.launcher.SceneWindowLease;
import dev.bitwigagent.mix.MeterModule;
import dev.bitwigagent.mix.MixModule;
import dev.bitwigagent.mix.TrackLifecycleModule;
import dev.bitwigagent.project.ContextModule;
import dev.bitwigagent.project.ProjectModule;
import dev.bitwigagent.recovery.AckModule;
import dev.bitwigagent.render.RenderModule;
import dev.bitwigagent.routing.RoutingModule;
import dev.bitwigagent.transport.TransportModule;
import java.util.function.LongSupplier;

/** The shared services a module test needs, wired as {@link HostBridge} wires them, with a factory per module. */
public final class TestServices {
    final ControllerHost host;
    public final Scheduler scheduler;
    final Application application;
    final Transport transport;
    final Project project;
    public final TrackWindow window;
    final ProjectState projectState;
    public final TransportSafety transportSafety;
    public final TrackRefs trackRefs;
    public final DeviceSources sources;
    final NativeRuntime natives;
    /** The simulated native document behind {@link #chains}. */
    public final FakeChains nativeChains;

    public final NativeChains chains;
    public final SelectionLease selection;
    public final Holds holds = new Holds();
    final LauncherStructureLock lock;
    private LauncherTargets launcherTargets;
    private LauncherState launcherState;

    public TestServices(ControllerHost host, LongSupplier clock) {
        this(host, clock, new FakeChains());
    }

    public TestServices(ControllerHost host, LongSupplier clock, FakeChains nativeChains) {
        this.host = host;
        this.nativeChains = nativeChains;
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
        chains = new NativeChains(scheduler, sources, natives, nativeChains);
        selection = new SelectionLease();
        lock = new LauncherStructureLock(selection, holds);
    }

    /** What HostBridge does for a request: capture the safety state, then queue the dispatch. */
    public void enqueue(HostOperation operation, Runnable dispatch) {
        operation.safetyAtEnqueue = transportSafety.observation();
        scheduler.schedule(operation, dispatch, 0);
    }

    public ArrangerModule arranger(ArrangerModule.Access access) {
        return new ArrangerModule(scheduler, window, trackRefs, projectState, transportSafety, natives, holds, access);
    }

    public AutomationModule automation(AutomationModule.Access access) {
        RemoteModule remotes = remote();
        return new AutomationModule(
                scheduler,
                window,
                parameterResolver(parameters(remotes), remotes),
                transportSafety,
                natives,
                holds,
                access);
    }

    public DeviceModule device() {
        return new DeviceModule(scheduler, sources, chains, selection);
    }

    public DeviceListModule deviceList() {
        return new DeviceListModule(window, trackRefs, chains);
    }

    public DeviceChainModule deviceChain() {
        return new DeviceChainModule(scheduler, window, trackRefs, sources, chains, transportSafety);
    }

    public RemoteModule remote() {
        return new RemoteModule(host, scheduler, window, sources, transportSafety);
    }

    public AckModule ack(GridModule grid) {
        return new AckModule(holds, grid);
    }

    public GridModule grid(GridAdapter adapter) {
        return new GridModule(scheduler, sources, transportSafety, natives, adapter);
    }

    /** The scene window lease and the refs resolved through it, shared by every launcher module of one test. */
    public LauncherTargets launcherTargets() {
        if (launcherTargets == null)
            launcherTargets = new LauncherTargets(window, trackRefs, new SceneWindowLease(window, scheduler));
        return launcherTargets;
    }

    public LauncherState launcherState() {
        if (launcherState == null)
            launcherState = new LauncherState(
                    host, scheduler, window, transportSafety, selection, lock, holds, launcherTargets());
        return launcherState;
    }

    public LauncherModule launcher() {
        return new LauncherModule(launcherState(), trackRefs);
    }

    public NoteModule notes() {
        return new NoteModule(launcherState());
    }

    public ClipModule clips(LauncherSettingsModule.Access settings, ArrangerModule.Access arranger) {
        return new ClipModule(launcherState(), trackRefs, notes(), launcherSettings(settings), arranger(arranger));
    }

    public ContextModule context(MixModule mix, TransportModule transport) {
        return new ContextModule(scheduler, window, projectState, transportSafety, transport, mix, launcherState());
    }

    public LauncherSettingsModule launcherSettings(LauncherSettingsModule.Access access) {
        return new LauncherSettingsModule(scheduler, window, transportSafety, natives, holds, access);
    }

    public MarkerModule marker() {
        return new MarkerModule(host, transport, scheduler, transportSafety, holds);
    }

    public MeterModule meter() {
        return new MeterModule(scheduler, window, trackRefs, transportSafety);
    }

    private MixModule shared;

    /** The one mix module of this fixture, for tests that read through the module the lifecycle module names tracks with. */
    public MixModule sharedMix() {
        if (shared == null) shared = mix();
        return shared;
    }

    public MixModule mix() {
        return new MixModule(host, scheduler, window, trackRefs, selection);
    }

    public TrackLifecycleModule trackLifecycle(MixModule mix, TrackLifecycleModule.GroupAccess access) {
        return new TrackLifecycleModule(
                scheduler, window, trackRefs, application, transportSafety, selection, natives, holds, mix, access);
    }

    public ModulatorEditModule modulatorEdits(ModulatorEditor editor) {
        return new ModulatorEditModule(scheduler, sources, transportSafety, natives, editor);
    }

    public ModulatorModule modulator(ModulatorAdapter adapter, ModulatorEditModule edits) {
        return new ModulatorModule(scheduler, sources, natives, adapter, edits);
    }

    public ParameterModule parameters(RemoteModule remotes, ModulatorEditModule edits) {
        return new ParameterModule(scheduler, sources, chains, trackRefs, window, transportSafety, remotes, edits);
    }

    /** Direct parameters and Remote Controls alone; a modulator control would need native edit bindings. */
    public ParameterModule parameters(RemoteModule remotes) {
        return parameters(remotes, modulatorEdits(null));
    }

    public ParameterResolver parameterResolver(ParameterModule parameters, RemoteModule remotes) {
        return new ParameterResolver(sources, trackRefs, window, parameters, remotes);
    }

    public ProjectModule projectModule(ProjectModule.SaveAsAccess access) {
        return new ProjectModule(scheduler, application, project, projectState, natives, holds, access);
    }

    public RenderModule render(RenderModule.Access access) {
        return new RenderModule(scheduler, window, trackRefs, project, transportSafety, natives, holds, access);
    }

    public RenderModule render(RenderModule.Access access, RenderModule.LauncherContent launcher) {
        return new RenderModule(
                scheduler, window, trackRefs, project, transportSafety, natives, holds, access, launcher);
    }

    public RoutingModule routing(MethodSpecs specs, RoutingModule.Access access) {
        return new RoutingModule(scheduler, window, trackRefs, sources, transportSafety, natives, holds, specs, access);
    }

    public SceneModule scene() {
        return new SceneModule(scheduler, window, transportSafety, lock, launcherTargets(), project);
    }

    public TransportModule transportModule() {
        return new TransportModule(transport, application, scheduler, transportSafety, projectState);
    }
}
