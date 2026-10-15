package dev.bitwigagent;

import com.bitwig.extension.callback.*;
import com.bitwig.extension.controller.api.*;
import com.google.gson.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

/** Test-only host proxies: explicit callbacks and queued host tasks, no Bitwig process. */
final class TransportMixFixture {
    static final String A = "00000000-0000-0000-0000-000000000001";
    static final String B = "00000000-0000-0000-0000-000000000002";
    static final String C = "00000000-0000-0000-0000-000000000003";
    final Deque<Runnable> tasks = new ArrayDeque<>();
    final Node transport = new Node(), application = new Node(), bank = new Node(), host = new Node();
    final Node[] tracks = new Node[HostServices.TRACKS];
    final HostServices services;
    boolean onHost;
    int bankCreations;
    Consumer<String> create = type -> {};

    TransportMixFixture() { this(true); }
    TransportMixFixture(boolean observeSafetyInitially) {
        for (int i = 0; i < tracks.length; i++) {
            Node track = tracks[i] = new Node();
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
            track.custom = (name, args) -> {
                if (name.equals("addIsSelectedInEditorObserver")) {
                    track.child("selected").listen(args[0]); return null;
                }
                if (name.equals("selectInEditor")) {
                    mutation(); track.actions++; track.child("selected").emit(true); return null;
                }
                return null;
            };
            Node devices = track.child("createDeviceBank");
            devices.child("exists").data = true;
            devices.child("itemCount").data = 0;
            devices.child("scrollPosition").data = 0;
            for (int d = 0; d < HostServices.DEVICES; d++) {
                Node device = devices.item(d);
                emptyDevice(device, d);
                device.child("hasLayers").data = false;
                Node layers = device.child("createLayerBank");
                emptyBank(layers);
                layers.custom = (name, args) -> name.equals("getItemAt") ? layers.item((Integer) args[0]).proxy(DeviceLayer.class) : null;
                for (int l = 0; l < 2; l++) {
                    Node layer = layers.item(l);
                    layer.child("exists").data = false;
                    layer.child("channelId").data = "";
                    layer.child("channelIndex").data = l;
                    layer.child("name").data = "";
                    Node children = layer.child("createDeviceBank");
                    emptyBank(children);
                    children.custom = (name, args) -> name.equals("getItemAt") ? children.item((Integer) args[0]).proxy(Device.class) : null;
                    for (int c = 0; c < 2; c++) {
                        Node child = children.item(c);
                        emptyDevice(child, c);
                    }
                }
            }
            devices.custom = (name, args) -> name.equals("getItemAt") ? devices.item((Integer) args[0]).proxy(Device.class) : null;
            Node sends = track.child("sendBank"); sends.child("itemCount").data = i == 0 ? 1 : 0;
            sends.custom = (name, args) -> name.equals("getItemAt") ? sends.item((Integer) args[0]).proxy(Send.class) : null;
            Node send = sends.item(0); send.child("exists").data = i == 0;
            send.child("name").data = "Reverb"; send.child("value").data = .25;
            send.child("displayedValue").data = "-18 dB";
            send.child("isEnabled").data = true; send.child("isPreFader").data = false;
        }
        bank.child("itemCount").data = 2; bank.child("scrollPosition").data = 0;
        bank.custom = (name, args) -> name.equals("getItemAt") ? tracks[(Integer) args[0]].proxy(Track.class) : null;
        for (String key : List.of("isPlaying", "isMetronomeEnabled", "isArrangerLoopEnabled", "isArrangerRecordEnabled",
                "isArrangerAutomationWriteEnabled", "isClipLauncherAutomationWriteEnabled")) transport.child(key).data = false;
        for (String key : List.of("isPlaying", "isArrangerRecordEnabled", "isArrangerAutomationWriteEnabled", "isClipLauncherAutomationWriteEnabled"))
            transport.child(key).initial = observeSafetyInitially;
        transport.child("tempo").child("value").data = 120.0;
        transport.child("playPosition").data = 0.0;
        transport.child("arrangerLoopStart").data = 0.0;
        transport.child("arrangerLoopDuration").data = 4.0;
        transport.custom = (name, args) -> {
            if (name.equals("play") || name.equals("stop")) {
                mutation(); transport.actions++; transport.child("isPlaying").emit(name.equals("play"));
            }
            if (name.equals("setPosition")) {
                mutation(); transport.actions++; transport.child("playPosition").emit(args[0]);
            }
            return null;
        };
        application.custom = (name, args) -> {
            if (name.startsWith("create") && name.endsWith("Track")) {
                mutation(); application.actions++; create.accept(name);
            }
            return null;
        };
        host.custom = (name, args) -> {
            if (name.equals("createApplication")) return application.proxy(Application.class);
            if (name.equals("createTransport")) return transport.proxy(Transport.class);
            if (name.equals("createTrackBank")) { bankCreations++; return bank.proxy(TrackBank.class); }
            if (name.equals("scheduleTask")) tasks.add((Runnable) args[0]);
            return null;
        };
        services = new HostServices(host.proxy(ControllerHost.class));
    }

    private void emptyBank(Node bank) {
        bank.child("exists").data = false;
        bank.child("itemCount").data = 0;
        bank.child("scrollPosition").data = 0;
    }

    private void emptyDevice(Node device, int position) {
        device.child("exists").data = false;
        device.child("name").data = "";
        device.child("position").data = position;
        device.child("deviceType").data = "";
        device.custom = (name, args) -> {
            if (name.equals("addDirectParameterIdObserver"))
                ((StringArrayValueChangedCallback) args[0]).valueChanged(new String[0]);
            return null;
        };
    }

    HostOperation submit(HostModule module, String method, JsonObject params) { return submit(module, method, params, Long.MAX_VALUE); }
    HostOperation submit(HostModule module, String method, JsonObject params, long deadline) {
        HostOperation op = new HostOperation(deadline);
        try {
            module.validate(method, params);
            services.schedule(op, () -> module.dispatch(op, method, params), 0);
        } catch (RuntimeException error) { op.result.completeExceptionally(error); }
        return op;
    }
    JsonObject call(HostModule module, String method, JsonObject params) {
        HostOperation op = submit(module, method, params); next(); return op.result.join();
    }
    void next() {
        Runnable next = tasks.removeFirst(); onHost = true;
        try { next.run(); } finally { onHost = false; }
    }
    void addTrack(int slot, String id, String type) {
        Node track = tracks[slot];
        track.child("channelId").emit(id); track.child("exists").emit(true);
        track.child("trackType").emit(type); track.child("position").emit(slot);
        bank.child("itemCount").emit(slot + 1);
    }
    void mutation() { if (!onHost) throw new AssertionError("Host mutation outside scheduled callback"); }
    static JsonObject json(String text) { return JsonParser.parseString(text).getAsJsonObject(); }
    static JsonObject target(String id) { JsonObject p = new JsonObject(); p.addProperty("trackId", id); return p; }
    static RpcException error(HostOperation op, String code) {
        if (!op.result.isDone()) throw new AssertionError("Expected completed error " + code);
        try { op.result.join(); throw new AssertionError("Expected " + code); }
        catch (CompletionException error) {
            if (!(error.getCause() instanceof RpcException)) throw new AssertionError(error.getCause());
            RpcException rpc = (RpcException) error.getCause();
            if (!rpc.code.equals(code)) throw new AssertionError("Expected " + code + " got " + rpc.code);
            return rpc;
        }
    }

    interface Custom { Object invoke(String method, Object[] args); }
    final class Node implements InvocationHandler {
        final Map<String, Node> children = new HashMap<>();
        final Map<Class<?>, Object> proxies = new HashMap<>();
        final List<Object> observers = new ArrayList<>();
        Object data;
        Custom custom = (name, args) -> null;
        Runnable afterWrite = () -> {};
        boolean emitWrites = true, updateWrites = true, initial = true;
        int writes, actions;
        Node child(String name) { return children.computeIfAbsent(name, key -> new Node()); }
        Node item(int index) { return child("item" + index); }
        <T> T proxy(Class<T> type) {
            return type.cast(proxies.computeIfAbsent(type, key -> Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, this)));
        }
        void listen(Object observer) {
            observers.add(observer);
            if (initial) notifyOne(observer);
        }
        void emit(Object value) { data = value; for (Object observer : observers) notifyOne(observer); }
        void notifyOne(Object observer) {
            if (observer instanceof BooleanValueChangedCallback) ((BooleanValueChangedCallback) observer).valueChanged(data instanceof Boolean && (Boolean) data);
            else if (observer instanceof IntegerValueChangedCallback) ((IntegerValueChangedCallback) observer).valueChanged(data instanceof Number ? ((Number) data).intValue() : 0);
            else if (observer instanceof DoubleValueChangedCallback) ((DoubleValueChangedCallback) observer).valueChanged(data instanceof Number ? ((Number) data).doubleValue() : 0.0);
            else if (observer instanceof StringValueChangedCallback) ((StringValueChangedCallback) observer).valueChanged(data instanceof String ? (String) data : "");
            else if (observer instanceof EnumValueChangedCallback) ((EnumValueChangedCallback) observer).valueChanged(data instanceof String ? (String) data : "");
            else throw new AssertionError("Unexpected observer " + observer.getClass());
        }
        @Override public Object invoke(Object proxy, Method method, Object[] supplied) {
            String name = method.getName(); Object[] args = supplied == null ? new Object[0] : supplied;
            if (name.equals("toString")) return "TestHostProxy";
            if (name.equals("hashCode")) return System.identityHashCode(proxy);
            if (name.equals("equals")) return proxy == args[0];
            Object customized = custom.invoke(name, args);
            if (customized != null) return customized;
            if (name.equals("addValueObserver") || name.equals("addRawValueObserver")) { listen(args[args.length - 1]); return null; }
            if (name.equals("set") || name.equals("setRaw") || name.equals("setImmediately")) {
                mutation(); writes++;
                if (updateWrites) data = args[0];
                if (emitWrites) for (Object observer : observers) notifyOne(observer);
                afterWrite.run(); return null;
            }
            if (name.equals("get") || name.equals("getRaw") || name.equals("getAsDouble")) {
                if (data != null) return data;
                Class<?> type = method.getReturnType();
                if (type == boolean.class) return false;
                if (type == int.class) return 0;
                if (type == double.class) return 0.0;
                return "";
            }
            if (method.getReturnType().isInterface()) {
                Node node = name.equals("getItemAt") ? item((Integer) args[0]) : child(name);
                return node.proxy(method.getReturnType());
            }
            if (method.getReturnType() == boolean.class) return false;
            if (method.getReturnType() == int.class) return 0;
            if (method.getReturnType() == double.class) return 0.0;
            return null;
        }
    }
}
