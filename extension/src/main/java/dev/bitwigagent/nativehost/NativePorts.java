package dev.bitwigagent.nativehost;

import com.bitwig.extension.controller.api.ControllerHost;
import dev.bitwigagent.arranger.ArrangerModule;
import dev.bitwigagent.automation.AutomationModule;
import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.HostOperation;
import dev.bitwigagent.core.PortHandle;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.core.Scheduler;
import dev.bitwigagent.device.ModulatorAdapter;
import dev.bitwigagent.device.ModulatorEditor;
import dev.bitwigagent.device.NativeChains;
import dev.bitwigagent.grid.GridAdapter;
import dev.bitwigagent.grid.GridRemotePageWriter;
import dev.bitwigagent.launcher.LauncherSettingsModule;
import dev.bitwigagent.launcher.NativeNotes;
import dev.bitwigagent.mix.TrackLifecycleModule;
import dev.bitwigagent.mix.TrackReshapeModule;
import dev.bitwigagent.project.KeySignatureModule;
import dev.bitwigagent.project.ProjectModule;
import dev.bitwigagent.render.RenderModule;
import dev.bitwigagent.routing.RoutingPort;
import java.util.List;
import java.util.function.Function;

/**
 * The one place native adapters are built (compat layer §3): a handle per port, over the gate {@link NativeHost} opens
 * for the loaded host jar and the name table its entry selects. Nothing touches the jar until a request first needs
 * native access; each port binds on its own first use and is kept, and a port that cannot bind fails only its own
 * features. Ports that share an adapter share this one instance (Grid with device-modulator edits).
 */
public final class NativePorts {
    @FunctionalInterface
    interface Port<P> {
        P bind(NativeHost.Access access) throws ReflectiveOperationException;
    }

    private final Function<HostOperation, NativeHost> gate;
    private final PortHandle<NativeGridAdapter> grid = port("", NativeGridAdapter::new);
    private final PortHandle<RoutingPort> routing =
            port("Exact routing descriptors unavailable: ", NativeRoutingAccess::new);
    private final PortHandle<LauncherSettingsModule.Access> launcherSettings =
            port("Exact launcher settings descriptors unavailable: ", NativeLauncherSettingsAccess::new);
    private final PortHandle<TrackLifecycleModule.GroupAccess> trackGroup =
            port("Exact track group descriptors unavailable: ", NativeTrackGroupAccess::new);
    private final PortHandle<TrackReshapeModule.Access> trackReshape =
            port("Exact track command descriptors unavailable: ", NativeTrackReshapeAccess::new);
    private final PortHandle<NativeChains.Access> chains =
            port("Native chain binding unavailable: ", NativeChainAccess::new);
    private final PortHandle<GridRemotePageWriter.Access> remotePages =
            port("Native Remote Controls binding unavailable: ", NativeRemotePageAccess::new);
    private final PortHandle<NativeNotes.Access> noteWrites =
            port("Exact note bindings unavailable: ", NativeNoteWriteAccess::new);
    private final PortHandle<ModulatorAdapter> modulators = port("", NativeModulatorAdapter::new);
    private final PortHandle<ModulatorEditor.Access> modulatorEdits = new Handle<>(
            "Device-modulator edit bindings unavailable: ",
            (a, op) -> new NativeModulatorEditAccess(gated(a, "Exact runtime gate is unavailable: "), grid.get(op)));
    private final PortHandle<RenderModule.Access> render =
            port("Exact render bindings unavailable: ", NativeRenderAccess::new);
    private final PortHandle<ProjectModule.SaveAsAccess> saveAs =
            port("Exact project save bindings unavailable: ", NativeProjectSaveAsAccess::new);
    private final PortHandle<KeySignatureModule.Access> keySignature = port(
            "Exact key signature bindings unavailable: ",
            a -> new NativeKeySignatureAccess(gated(a, "The project key signature needs the native gate: ")));
    private final PortHandle<ArrangerModule.Access> arranger =
            port("Exact arranger bindings unavailable: ", NativeArrangerAccess::new);
    private final PortHandle<AutomationModule.Access> automation =
            port("Exact automation bindings unavailable: ", NativeAutomationAccess::new);

    /** The gate opens on the first request that needs it: hashing the jar checks that request for cancellation. */
    public NativePorts(ControllerHost host, Scheduler scheduler) {
        this(new Function<>() {
            private NativeHost runtime;

            @Override
            public NativeHost apply(HostOperation op) {
                scheduler.check(op);
                if (runtime == null) runtime = new NativeHost(host, host.getHostVersion(), () -> scheduler.check(op));
                return runtime;
            }
        });
    }

    NativePorts(Function<HostOperation, NativeHost> gate) {
        this.gate = gate;
    }

    public PortHandle<GridAdapter> grid() {
        return grid::get;
    }

    public PortHandle<RoutingPort> routing() {
        return routing;
    }

    public PortHandle<LauncherSettingsModule.Access> launcherSettings() {
        return launcherSettings;
    }

    public PortHandle<TrackLifecycleModule.GroupAccess> trackGroup() {
        return trackGroup;
    }

    public PortHandle<TrackReshapeModule.Access> trackReshape() {
        return trackReshape;
    }

    public PortHandle<NativeChains.Access> chains() {
        return chains;
    }

    public PortHandle<GridRemotePageWriter.Access> remotePages() {
        return remotePages;
    }

    public PortHandle<NativeNotes.Access> noteWrites() {
        return noteWrites;
    }

    public PortHandle<ModulatorAdapter> modulators() {
        return modulators;
    }

    public PortHandle<ModulatorEditor.Access> modulatorEdits() {
        return modulatorEdits;
    }

    public PortHandle<RenderModule.Access> render() {
        return render;
    }

    public PortHandle<ProjectModule.SaveAsAccess> saveAs() {
        return saveAs;
    }

    public PortHandle<KeySignatureModule.Access> keySignature() {
        return keySignature;
    }

    public PortHandle<ArrangerModule.Access> arranger() {
        return arranger;
    }

    public PortHandle<AutomationModule.Access> automation() {
        return automation;
    }

    /** Every port, for the check that each binds on each shipped entry's jar (compat layer §3.2). */
    List<PortHandle<?>> all() {
        return List.of(
                grid,
                routing,
                launcherSettings,
                trackGroup,
                trackReshape,
                chains,
                remotePages,
                noteWrites,
                modulators,
                modulatorEdits,
                render,
                saveAs,
                keySignature,
                arranger,
                automation);
    }

    /** A port whose binding refuses a closed gate as {@code native_unavailable}, not the gate's own host mismatch. */
    private static NativeHost.Access gated(NativeHost.Access a, String unavailable) {
        if (a.gateError() != null) throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, unavailable + a.gateError());
        return a;
    }

    private <P> PortHandle<P> port(String unavailable, Port<P> bind) {
        return new Handle<>(unavailable, (a, op) -> bind.bind(a));
    }

    @FunctionalInterface
    private interface Dependent<P> {
        P bind(NativeHost.Access access, HostOperation op) throws ReflectiveOperationException;
    }

    /** One port: the gate is asked only while the port is unbound, under the request that needs it. */
    private final class Handle<P> implements PortHandle<P> {
        private final LazyBinding<P> binding;
        private final Dependent<P> bind;

        Handle(String unavailable, Dependent<P> bind) {
            binding = new LazyBinding<>(unavailable);
            this.bind = bind;
        }

        @Override
        public P get(HostOperation op) {
            return binding.get(() -> bind.bind(gate.apply(op).access(), op));
        }
    }
}
