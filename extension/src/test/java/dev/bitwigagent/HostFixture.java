package dev.bitwigagent;

import com.bitwig.extension.callback.*;
import com.bitwig.extension.controller.api.*;
import com.google.gson.*;
import dev.bitwigagent.core.MethodSpecs;
import dev.bitwigagent.core.SchemaValidator;
import java.util.*;
import java.util.function.Consumer;

/** A simulated host with a transport, a track bank and queued host tasks; no Bitwig process. */
final class HostFixture {
    static final MethodSpecs SPECS = MethodSpecs.load();
    /** The ref schema the bundle exports, compiled by the extension's own validator. */
    static final SchemaValidator REF_SCHEMA = refSchema();

    static final String A = "00000000-0000-0000-0000-000000000001";
    static final String B = "00000000-0000-0000-0000-000000000002";
    static final String C = "00000000-0000-0000-0000-000000000003";
    final ScheduledTasks tasks = new ScheduledTasks();
    final FakeHost host = new FakeHost("host");
    final FakeHost transport = host.child("createTransport");
    final FakeHost application = host.child("createApplication");
    final FakeHost bank = host.child("createTrackBank");
    final FakeHost project = host.child("getProject");
    /** Tracks past these share one empty, absent track so a window-size bank stays cheap to build. */
    static final int DETAILED_TRACKS = 16;

    final FakeHost[] tracks = new FakeHost[HostLimits.TRACKS];
    final TestServices services;
    boolean onHost;
    int flushRequests;
    Consumer<String> create = type -> {};
    Runnable addMarker = () -> {};
    Consumer<Integer> delete = slot -> {};

    HostFixture() {
        this(true);
    }

    /** The host topology alone, for a caller that builds its own services on {@link #host}. */
    static HostFixture withoutServices() {
        return new HostFixture(true, false);
    }

    HostFixture(boolean observeSafetyInitially) {
        this(observeSafetyInitially, true);
    }

    private HostFixture(boolean observeSafetyInitially, boolean createServices) {
        host.guardWrites(this::mutation);
        project.child("isModified").data = false;
        application.child("projectName").data = "Fixture";
        application.child("hasActiveEngine").data = false;
        for (int i = 0; i < tracks.length; i++) {
            if (i >= DETAILED_TRACKS) {
                tracks[i] = bank.attach("item" + i, tracks[DETAILED_TRACKS - 1]);
                continue;
            }
            FakeHost track = tracks[i] = bank.item(i);
            final int slot = i;
            track.child("exists").data = i < 2;
            track.child("channelId").data = i == 0 ? A : i == 1 ? B : "";
            track.child("name").data = "Track " + i;
            track.child("trackType").data = "Audio";
            track.child("position").data = i;
            track.child("volume").child("value").data = .5;
            track.child("volume").child("displayedValue").data = "-12 dB";
            track.child("pan").child("value").data = .5;
            track.child("pan").child("displayedValue").data = "Center";
            for (String key : List.of("mute", "solo", "arm", "selected")) track.child(key).data = false;
            track.child("color").data = new float[] {0f, 0f, 0f};
            track.child("isActivated").data = true;
            track.child("isGroup").data = false;
            track.child("isGroupExpanded").data = false;
            track.child("monitorMode").data = "AUTO";
            enumOptions(track.child("monitorMode"), "AUTO", "ON", "OFF");
            track.child("crossFadeMode").data = "AB";
            enumOptions(track.child("crossFadeMode"), "A", "AB", "B");
            track.on("addIsSelectedInEditorObserver", args -> {
                track.child("selected").listen(args[0]);
                return null;
            });
            track.on("addVuMeterObserver", args -> null);
            track.on("selectInEditor", args -> {
                mutation();
                track.actions++;
                FakeHost selected = track.child("selected");
                selected.data = true;
                if (selected.notifyWrites) selected.emit(true);
                selected.afterWrite.run();
                return null;
            });
            track.on("deleteObject", args -> {
                mutation();
                track.actions++;
                delete.accept(slot);
                return null;
            });
            track.child("startOfDeviceChainInsertionPoint");
            track.child("endOfDeviceChainInsertionPoint");
            FakeHost slots = track.child("clipLauncherSlotBank").items(ClipLauncherSlot.class);
            slots.child("scrollPosition").data = 0;
            slots.child("itemCount").data = HostLimits.SCENES;
            for (int s = 0; s < HostLimits.SCENES; s++) {
                FakeHost clip = slots.item(s);
                for (String key : List.of("hasContent", "isPlaying", "isPlaybackQueued", "isRecording"))
                    clip.child(key).data = false;
                clip.child("name").data = "";
                clip.child("sceneIndex").data = s;
            }
            track.child("isStopped").data = true;
            track.child("isQueuedForStop").data = false;
            FakeHost parent = track.child("createParentTrack");
            parent.child("exists").data = false;
            parent.child("channelId").data = "";
            FakeHost devices = track.child("createDeviceBank");
            devices.items(Device.class);
            devices.child("exists").data = true;
            devices.child("itemCount").data = 0;
            devices.child("scrollPosition").data = 0;
            for (int d = 0; d < HostLimits.DEVICES; d++) {
                FakeHost device = devices.item(d);
                emptyDevice(device, d);
                device.child("hasLayers").data = false;
                FakeHost layers = device.child("createLayerBank");
                emptyBank(layers);
                layers.items(DeviceLayer.class);
                for (int l = 0; l < 2; l++) {
                    FakeHost layer = layers.item(l);
                    layer.child("exists").data = false;
                    layer.child("channelId").data = "";
                    layer.child("channelIndex").data = l;
                    layer.child("name").data = "";
                    FakeHost children = layer.child("createDeviceBank");
                    emptyBank(children);
                    children.items(Device.class);
                    for (int c = 0; c < 2; c++) emptyDevice(children.item(c), c);
                }
            }
            FakeHost sends = track.child("sendBank");
            sends.items(Send.class);
            sends.child("itemCount").data = i == 0 ? 1 : 0;
            sends.child("scrollPosition").data = 0;
            for (int s = 0; s < HostLimits.SENDS; s++) {
                FakeHost send = sends.item(s);
                boolean reverb = s == 0;
                send.child("exists").data = reverb && i == 0;
                send.child("name").data = reverb ? "Reverb" : "";
                send.child("value").data = reverb ? .25 : 0.0;
                send.child("displayedValue").data = reverb ? "-18 dB" : "";
                send.child("isEnabled").data = reverb;
                send.child("isPreFader").data = false;
            }
        }
        FakeHost markers =
                host.child("createArranger").child("createCueMarkerBank").items(CueMarker.class);
        markers.child("itemCount").data = 0;
        markers.child("scrollPosition").data = 0;
        for (int m = 0; m < MarkerModule.WINDOW; m++) {
            FakeHost marker = markers.item(m);
            marker.child("exists").data = false;
            marker.child("name").data = "";
            marker.child("position").data = 0.0;
        }
        FakeHost scenes = bank.child("sceneBank").items(Scene.class);
        scenes.child("itemCount").data = 0;
        scenes.child("scrollPosition").data = 0;
        for (int s = 0; s < HostLimits.SCENES; s++) {
            scenes.item(s).child("exists").data = false;
            scenes.item(s).child("name").data = "";
        }
        launcherCursorClip(host.child("createLauncherCursorClip"));
        bank.items(Track.class);
        bank.child("itemCount").data = 2;
        bank.child("scrollPosition").data = 0;
        for (String key : List.of(
                "isPlaying",
                "isMetronomeEnabled",
                "isArrangerLoopEnabled",
                "isArrangerRecordEnabled",
                "isArrangerAutomationWriteEnabled",
                "isClipLauncherAutomationWriteEnabled")) transport.child(key).data = false;
        for (String key : List.of(
                "isPlaying",
                "isArrangerRecordEnabled",
                "isArrangerAutomationWriteEnabled",
                "isClipLauncherAutomationWriteEnabled")) transport.child(key).notifyInitial = observeSafetyInitially;
        transport.child("tempo").child("value").data = 120.0;
        transport.child("metronomeVolume").data = .5;
        transport.child("metronomeVolume").child("displayedValue").data = "-12 dB";
        transport.child("isPunchInEnabled").data = false;
        transport.child("isPunchOutEnabled").data = false;
        transport.child("preRoll").data = "none";
        enumOptions(transport.child("preRoll"), "none", "one_bar", "two_bars", "four_bars");
        transport.child("defaultLaunchQuantization").data = "1";
        enumOptions(transport.child("defaultLaunchQuantization"), "none", "8", "4", "2", "1", "1/2");
        application.child("recordQuantizationGrid").data = "1/16";
        enumOptions(application.child("recordQuantizationGrid"), "1/4", "1/8", "1/16");
        transport.child("playPosition").data = 0.0;
        transport.child("timeSignature").data = "4/4";
        transport.child("arrangerLoopStart").data = 0.0;
        transport.child("arrangerLoopDuration").data = 4.0;
        for (String name : List.of("play", "stop"))
            transport.on(name, args -> {
                mutation();
                transport.actions++;
                transport.child("isPlaying").emit(name.equals("play"));
                return null;
            });
        transport.on("addCueMarkerAtPlaybackPosition", args -> {
            mutation();
            transport.actions++;
            addMarker.run();
            return null;
        });
        transport.on("setPosition", args -> {
            mutation();
            transport.actions++;
            transport.child("playPosition").emit(args[0]);
            return null;
        });
        application.onMethods(
                Application.class, name -> name.startsWith("create") && name.endsWith("Track"), (name, args) -> {
                    mutation();
                    application.actions++;
                    create.accept(name);
                    return null;
                });
        host.returns("getHostVersion", "test-only");
        host.on("requestFlush", args -> {
            flushRequests++;
            return null;
        });
        host.on("scheduleTask", args -> {
            tasks.schedule((Runnable) args[0], ((Number) args[1]).longValue());
            return null;
        });
        services = createServices ? new TestServices(host.proxy(ControllerHost.class), tasks::now) : null;
    }

    /** The host's own enum option list, as EnumValue.enumDefinition() reports it. */
    void enumOptions(FakeHost value, String... ids) {
        FakeHost definition = value.child("enumDefinition");
        definition.on("getValueCount", args -> ids.length);
        definition.on("valueDefinitionAt", args -> {
            int index = (Integer) args[0];
            return definition.item(index).returns("getId", ids[index]).proxy(EnumValueDefinition.class);
        });
    }

    private void emptyBank(FakeHost bank) {
        bank.child("exists").data = false;
        bank.child("itemCount").data = 0;
        bank.child("scrollPosition").data = 0;
    }

    private static void launcherCursorClip(FakeHost clip) {
        clip.on("addNoteStepObserver", args -> null);
        clip.on("scrollToKey", args -> null);
        clip.child("exists").data = false;
        clip.child("color").data = new float[] {0f, 0f, 0f};
        for (String beats : List.of("getLoopLength", "getLoopStart", "getPlayStart", "getPlayStop"))
            clip.child(beats).data = 0.0;
        clip.child("isLoopEnabled").data = false;
        clip.child("useLoopStartAsQuantizationReference").data = false;
        clip.child("launchMode").data = "from_group";
        clip.child("launchQuantization").data = "default";
        FakeHost track = clip.child("getTrack");
        track.child("exists").data = false;
        track.child("channelId").data = "";
        FakeHost sameSlot = FakeHost.of(BooleanValue.class, "sameSlot");
        sameSlot.data = false;
        clip.child("clipLauncherSlot").on("createEqualsValue", args -> sameSlot.proxy());
    }

    /** The cursor remote-controls page a device offers, empty and unmapped. */
    static void remotePage(FakeHost device) {
        FakeHost page = device.child("createCursorRemoteControlsPage");
        page.child("getName").data = "";
        page.child("pageCount").data = 0;
        page.child("pageNames").data = new String[0];
        page.child("selectedPageIndex").data = 0;
        for (int r = 0; r < 8; r++) {
            FakeHost control = page.child("parameter" + r);
            control.child("exists").data = false;
            control.child("isBeingMapped").data = false;
            control.child("name").data = "";
            control.child("value").data = 0.0;
        }
        page.on("getParameter", args -> page.child("parameter" + args[0]).proxy(RemoteControl.class));
    }

    private void emptyDevice(FakeHost device, int position) {
        remotePage(device);
        device.child("isEnabled").data = true;
        device.child("exists").data = false;
        device.child("name").data = "";
        device.child("position").data = position;
        device.child("deviceType").data = "";
        device.on("addDirectParameterIdObserver", args -> {
            ((StringArrayValueChangedCallback) args[0]).valueChanged(new String[0]);
            return null;
        });
        device.on("addDirectParameterNameObserver", args -> null);
        device.on("addDirectParameterNormalizedValueObserver", args -> null);
    }

    private static SchemaValidator refSchema() {
        try (java.io.Reader in = new java.io.InputStreamReader(
                MethodSpecs.class.getResourceAsStream(MethodSpecs.RESOURCE), java.nio.charset.StandardCharsets.UTF_8)) {
            JsonObject refs = JsonParser.parseReader(in).getAsJsonObject().getAsJsonObject("refs");
            return SchemaValidator.compile(refs.getAsJsonObject("schema"), "ref");
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    /** One simulated window lent to native-reader fakes, one track per UUID. */
    private static final class Roots {
        final HostFixture host = new HostFixture();
        final Map<UUID, DeviceSources.Source> byTrack = new HashMap<>();
    }

    private static java.lang.ref.SoftReference<Roots> roots = new java.lang.ref.SoftReference<>(null);

    /**
     * The first root device of a track carrying {@code track}: the Source a native-reader fake stands behind. Callers
     * share one window, softly held: each holds tens of thousands of proxies, so the suite keeps at most one it does
     * not use. A test that changes the Source takes a {@link #freshRootSource} instead.
     */
    static synchronized DeviceSources.Source rootSource(UUID track) {
        Roots shared = roots.get();
        if (shared == null) roots = new java.lang.ref.SoftReference<>(shared = new Roots());
        Roots window = shared;
        return window.byTrack.computeIfAbsent(track, id -> {
            int slot = window.byTrack.size();
            if (slot >= DETAILED_TRACKS) throw new IllegalStateException("more shared tracks than detailed slots");
            window.host.tracks[slot].child("channelId").emit(id.toString());
            return window.host.services.sources.roots[slot][0];
        });
    }

    static DeviceSources.Source freshRootSource(UUID track) {
        HostFixture f = withoutServices();
        f.tracks[0].child("channelId").data = track.toString();
        return new TestServices(f.host.proxy(ControllerHost.class), f.tasks::now).sources.roots[0][0];
    }

    HostBridge bridge(MethodSpecs specs) {
        return new HostBridge(host.proxy(ControllerHost.class), specs, tasks::now);
    }

    /** Validates and dispatches directly, as a module sees a request once the host has handed it over. */
    HostOperation start(HostModule module, String method, JsonObject params) {
        HostOperation op = new HostOperation(tasks.after(5000));
        try {
            JsonObject filled = HostBridge.checkSchema(SPECS, method, params);
            module.validate(method, filled);
            module.dispatch(op, method, filled);
        } catch (RuntimeException error) {
            op.fail(error);
        }
        return op;
    }

    void drain() {
        while (!tasks.isEmpty()) tasks.take().run();
    }

    HostOperation submit(HostModule module, String method, JsonObject params) {
        return submit(module, method, params, Long.MAX_VALUE);
    }

    HostOperation submit(HostModule module, String method, JsonObject params, long deadline) {
        HostOperation op = new HostOperation(deadline);
        try {
            JsonObject filled = HostBridge.checkSchema(SPECS, method, params);
            module.validate(method, filled);
            services.enqueue(op, () -> module.dispatch(op, method, filled));
        } catch (RuntimeException error) {
            op.result.completeExceptionally(error);
        }
        return op;
    }

    JsonObject call(HostModule module, String method, JsonObject params) {
        HostOperation op = submit(module, method, params);
        next();
        return op.result.join();
    }

    void next() {
        Runnable next = tasks.take();
        onHost = true;
        try {
            next.run();
        } finally {
            onHost = false;
        }
    }

    FakeHost slot(int track, int scene) {
        return tracks[track].child("clipLauncherSlotBank").item(scene);
    }

    void occupySlot(int track, int scene, String name) {
        slot(track, scene).child("hasContent").data = true;
        slot(track, scene).child("name").data = name;
    }

    void addTrack(int slot, String id, String type) {
        FakeHost track = tracks[slot];
        track.child("channelId").emit(id);
        track.child("exists").emit(true);
        track.child("trackType").emit(type);
        track.child("position").emit(slot);
        bank.child("itemCount").emit(slot + 1);
    }

    void removeTrack(int slot, int remaining) {
        FakeHost track = tracks[slot];
        track.child("exists").emit(false);
        track.child("channelId").emit("");
        bank.child("itemCount").emit(remaining);
    }

    void mutation() {
        if (!onHost) throw new AssertionError("Host mutation outside scheduled callback");
    }

    static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    static JsonObject target(String id) {
        JsonObject p = new JsonObject();
        p.addProperty("trackId", id);
        return p;
    }
}
