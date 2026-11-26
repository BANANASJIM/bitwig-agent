package dev.bitwigagent;

import com.bitwig.extension.controller.api.*;
import com.google.gson.*;
import dev.bitwigagent.core.Refs;
import java.util.*;

/**
 * One simulated track of root devices with Remote Controls pages, a root chain that reacts to the host's edit calls,
 * and layer devices whose chains live in the simulated native document ({@link FakeChains}); built on
 * {@link HostFixture}'s topology before the services observe it. Consumer tests only.
 */
final class DeviceFixture {
    static final String LAYER_A = "00000000-0000-4000-8000-0000000000aa";
    static final String LAYER_B = "00000000-0000-4000-8000-0000000000bb";
    static final String LAYER_C = "00000000-0000-4000-8000-0000000000cc";
    static final String TWIN = "Twin";

    final HostFixture host = HostFixture.withoutServices();
    final FakeHost track = host.tracks[0];
    final FakeHost bank = track.child("createDeviceBank");
    final FakeHost[] devices = new FakeHost[HostLimits.DEVICES], pages = new FakeHost[HostLimits.DEVICES];
    final FakeHost[][] controls = new FakeHost[HostLimits.DEVICES][8];
    final JsonObject trackRef = Refs.track(HostFixture.A, "Track 0");
    final Chain top;
    /** The native document the chains of layer devices live in. */
    final FakeChains natives = new FakeChains();

    TestServices services;
    RemoteModule remote;
    /** A page switch the host reports after the call returns, followed by its flush, as the real host does. */
    boolean deferredPageSwitch;
    /** Whether the host flush follows a deferred page switch at once; off, the controls stay pending until {@link #flush}. */
    boolean flushAfterSwitch = true;

    private final Set<String> silent = new HashSet<>();
    private DeviceModule deviceModule;
    private DeviceListModule listModule;
    private DeviceChainModule chainModule;
    private ParameterModule parameterModule;

    DeviceFixture() {
        for (int d = 0; d < HostLimits.DEVICES; d++) {
            devices[d] = bank.item(d);
            pages[d] = devices[d].child("createCursorRemoteControlsPage");
            for (int r = 0; r < 8; r++) controls[d][r] = pages[d].child("parameter" + r);
        }
        top = new Chain(
                host,
                bank,
                HostLimits.DEVICES,
                track.child("startOfDeviceChainInsertionPoint"),
                track.child("endOfDeviceChainInsertionPoint"));
    }

    /** Two present "Twin" root devices, each with a Remote Controls page. */
    static DeviceFixture twins() {
        DeviceFixture f = new DeviceFixture();
        f.seedTwins(false, false);
        f.start();
        return f;
    }

    /** The chain of {@code names}, audio effects, started. */
    static DeviceFixture chain(String... names) {
        DeviceFixture f = new DeviceFixture();
        f.top.seed(names);
        f.start();
        return f;
    }

    /**
     * Seeds the two Twin devices and their Remote Controls pages. With {@code lateIdentity} every device and track
     * identity is still a placeholder when the services first observe it; with {@code lateControl} so is control 0.
     */
    void seedTwins(boolean lateIdentity, boolean lateControl) {
        for (int d = 0; d < 2; d++) {
            FakeHost page = pages[d];
            page.child("pageNames").data = new String[] {"Main", "Tone"};
            page.child("pageCount").data = 2;
            page.child("selectedPageIndex").data = 0;
            page.child("getName").data = "Main";
            for (int r = 0; r < 8; r++) {
                FakeHost control = controls[d][r];
                control.child("exists").data = r == 0 && !lateControl;
                control.child("name").data = r == 0 ? "Gain" : "";
                control.child("isBeingMapped").data = false;
                control.child("value").data = d == 0 ? .25 : .6;
                control.child("value").takeoverPending = true;
            }
            page.child("selectedPageIndex").afterWrite = () -> switchedPage(page);
        }
        if (lateIdentity) {
            track.child("exists").data = false;
            track.child("channelId").data = "";
            for (int d = 0; d < 2; d++) {
                devices[d].child("position").data = -1;
                devices[d].child("name").data = "";
                devices[d].child("deviceType").data = "";
            }
            bank.child("itemCount").data = 2;
        } else top.seed(TWIN, TWIN);
    }

    /** Fields whose first observation never arrives, as for a host that reports them only on change. */
    void silent(String... fields) {
        silent.addAll(Arrays.asList(fields));
    }

    void start() {
        for (int d = 0; d < 2; d++) {
            if (silent.contains("getName")) pages[d].child("getName").notifyInitial = false;
            for (int r = 0; r < 8; r++)
                for (String field : List.of("exists", "name", "isBeingMapped", "value"))
                    if (silent.contains(field)) controls[d][r].child(field).notifyInitial = false;
        }
        if (deferredPageSwitch) for (int d = 0; d < 2; d++) pages[d].child("selectedPageIndex").notifyWrites = false;
        services = new TestServices(host.host.proxy(ControllerHost.class), host.tasks::now, natives);
        remote = services.remote();
    }

    private void switchedPage(FakeHost page) {
        FakeHost index = page.child("selectedPageIndex");
        Runnable report = () -> {
            if (deferredPageSwitch) index.emit(index.data);
            page.child("getName").emit(((Integer) index.data) == 0 ? "Main" : "Tone");
        };
        if (!deferredPageSwitch) {
            report.run();
            return;
        }
        host.tasks.schedule(
                () -> {
                    report.run();
                    if (flushAfterSwitch) flush();
                },
                0);
    }

    DeviceModule device() {
        return deviceModule == null ? deviceModule = services.device() : deviceModule;
    }

    DeviceListModule list() {
        return listModule == null ? listModule = services.deviceList() : listModule;
    }

    DeviceChainModule chains() {
        return chainModule == null ? chainModule = services.deviceChain() : chainModule;
    }

    ParameterModule parameters() {
        return parameterModule == null ? parameterModule = services.parameters(remote) : parameterModule;
    }

    HostOperation submit(HostModule module, String method, JsonObject params) {
        return submit(module, method, params, Long.MAX_VALUE);
    }

    /** Validates and queues a request as the bridge does, on this fixture's services. */
    HostOperation submit(HostModule module, String method, JsonObject params, long deadline) {
        HostOperation op = new HostOperation(deadline, HostFixture.SPECS.get(method).inspect);
        try {
            JsonObject filled = HostBridge.checkSchema(HostFixture.SPECS, method, params);
            module.validate(method, filled);
            services.enqueue(op, () -> module.dispatch(op, method, filled));
        } catch (RuntimeException error) {
            op.result.completeExceptionally(error);
        }
        return op;
    }

    HostOperation run(HostModule module, String method, JsonObject params) {
        return run(module, method, params, Long.MAX_VALUE);
    }

    /** {@link #submit} followed by the first host task, which is where most refusals are raised. */
    HostOperation run(HostModule module, String method, JsonObject params, long deadline) {
        HostOperation op = submit(module, method, params, deadline);
        if (!op.result.isDone()) host.next();
        return op;
    }

    /** Runs host tasks until {@code op} settles; a request that never settles ends the test with an empty queue. */
    JsonObject finish(HostOperation op) {
        while (!op.result.isDone()) host.next();
        return op.result.join();
    }

    /** Runs host tasks until {@code op} settles, for a request expected to end in an error. */
    HostOperation finishOrError(HostOperation op) {
        while (!op.result.isDone()) host.next();
        return op;
    }

    JsonObject call(HostModule module, String method, JsonObject params) {
        HostOperation op = submit(module, method, params);
        host.next();
        return op.result.join();
    }

    /** The host's flush of interested values, which the Remote Controls recover their history at. */
    void flush() {
        boolean before = host.onHost;
        host.onHost = true;
        try {
            remote.flush();
        } finally {
            host.onHost = before;
        }
    }

    JsonObject device(int index, String name) {
        return Refs.device(trackRef, index, name);
    }

    JsonObject twin(int index) {
        return device(index, TWIN);
    }

    static JsonObject params(Object... pairs) {
        JsonObject p = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2) {
            String key = (String) pairs[i];
            Object value = pairs[i + 1];
            if (value instanceof JsonElement) p.add(key, (JsonElement) value);
            else if (value instanceof String) p.addProperty(key, (String) value);
            else if (value instanceof Number) p.addProperty(key, (Number) value);
            else p.addProperty(key, (Boolean) value);
        }
        return p;
    }

    /** A layer device at {@code slot} of the root chain, seeded in {@link #top}, with its chains. */
    Rack rack(int slot) {
        return new Rack(slot);
    }

    final class Rack {
        final int slot;
        final FakeChains.Node node;
        final List<FakeChains.Chain> chains = new ArrayList<>();
        final List<JsonObject> refs = new ArrayList<>();

        Rack(int slot) {
            this.slot = slot;
            node = natives.root(
                    0, slot, FakeChains.device(top.entries.get(slot)[0]).layered());
            node.type = "instrument";
        }

        JsonObject ref() {
            return device(slot, top.entries.get(slot)[0]);
        }

        /** The chain at {@code index} (the next one): its UUID, name and devices (audio effects). */
        FakeChains.Chain layer(int index, String id, String name, String... deviceNames) {
            if (index != chains.size()) throw new IllegalArgumentException("Chains are added in order.");
            FakeChains.Node[] devices = new FakeChains.Node[deviceNames.length];
            for (int i = 0; i < devices.length; i++) devices[i] = FakeChains.device(deviceNames[i]);
            FakeChains.Chain chain = node.chain(id, name, devices);
            chains.add(chain);
            refs.add(Refs.chain(ref(), id, name));
            return chain;
        }

        JsonObject chainRef(int index) {
            return refs.get(index);
        }

        JsonObject deviceRef(int chain, int index) {
            return Refs.device(chainRef(chain), index, chains.get(chain).devices.get(index).name);
        }

        List<String> names(int chain) {
            List<String> names = new ArrayList<>();
            for (FakeChains.Node device : chains.get(chain).devices) names.add(device.name);
            return names;
        }
    }

    /** A device chain behind a bank: it renders the host's observer traffic of every edit its insertion points get. */
    static final class Chain {
        final HostFixture host;
        final FakeHost bank;
        final FakeHost[] slots;
        final Device[] proxies;
        final List<String[]> entries = new ArrayList<>();
        final List<String> points = new ArrayList<>();
        int insertions, deletions, selected = -1;
        String insertMode = "one", deleteMode = "one", moveMode = "one", lastInsertion;
        Object lastArgument;

        Chain(HostFixture host, FakeHost bank, int window, FakeHost start, FakeHost end) {
            this.host = host;
            this.bank = bank;
            slots = new FakeHost[window];
            proxies = new Device[window];
            for (int i = 0; i < window; i++) {
                final int index = i;
                slots[i] = bank.item(i);
                proxies[i] = slots[i].proxy(Device.class);
                slots[i].on("selectInEditor", args -> {
                    host.mutation();
                    selected = index;
                    return null;
                });
                slots[i].on("deleteObject", args -> {
                    host.mutation();
                    deletions++;
                    if (!deleteMode.equals("none") && index < entries.size()) {
                        List<String[]> next = new ArrayList<>(entries);
                        next.remove(index);
                        if (deleteMode.equals("prefix") && !next.isEmpty())
                            next.set(0, new String[] {"Changed prefix", "audio-effect"});
                        render(next);
                    }
                    return null;
                });
                point(slots[i].child("beforeDeviceInsertionPoint"), "before", i);
                point(slots[i].child("afterDeviceInsertionPoint"), "after", i);
            }
            point(start, "start", -1);
            end.onMethods(InsertionPoint.class, name -> name.startsWith("insert"), (name, args) -> {
                host.mutation();
                insertions++;
                lastInsertion = name;
                lastArgument = args[0];
                if (!insertMode.equals("none")) {
                    append();
                    if (insertMode.equals("double")) append();
                    if (insertMode.equals("prefix")) slots[0].child("name").emit("Changed prefix");
                }
                return null;
            });
        }

        void seed(String... names) {
            List<String[]> next = new ArrayList<>();
            for (String name : names) next.add(new String[] {name, "audio-effect"});
            render(next);
        }

        List<String> names() {
            List<String> result = new ArrayList<>();
            for (String[] entry : entries) result.add(entry[0]);
            return result;
        }

        private void point(FakeHost node, String mode, int anchor) {
            node.onMethods(
                    InsertionPoint.class,
                    name -> name.startsWith("insert") || name.equals("moveDevices"),
                    (name, args) -> {
                        boolean insert = name.startsWith("insert");
                        host.mutation();
                        points.add(mode + ":" + anchor + ":" + name);
                        if (insert) {
                            insertions++;
                            lastInsertion = name;
                            lastArgument = args[0];
                        }
                        if (insert ? insertMode.equals("none") : moveMode.equals("none")) return null;
                        List<String[]> next = new ArrayList<>(entries);
                        if (insert) {
                            int index = mode.equals("start") ? 0 : mode.equals("before") ? anchor : anchor + 1;
                            next.add(index, new String[] {"Inserted", "audio-effect"});
                        } else {
                            Device moving = ((Device[]) args[0])[0];
                            int from = Arrays.asList(proxies).indexOf(moving);
                            String[] entry = next.remove(from);
                            int adjusted = anchor > from ? anchor - 1 : anchor;
                            next.add(mode.equals("before") ? adjusted : adjusted + 1, entry);
                        }
                        render(next);
                        return null;
                    });
        }

        /** Emits the observer traffic of the chain becoming {@code next}, in the order the host reports it. */
        void render(List<String[]> next) {
            int old = entries.size();
            for (int i = 0; i < slots.length; i++) {
                if (i < next.size()) {
                    String[] entry = next.get(i);
                    boolean fresh = i >= old;
                    if (fresh || !entries.get(i)[1].equals(entry[1]))
                        slots[i].child("deviceType").emit(entry[1]);
                    if (fresh || !entries.get(i)[0].equals(entry[0]))
                        slots[i].child("name").emit(entry[0]);
                    if (fresh) slots[i].child("exists").emit(true);
                } else if (i < old) slots[i].child("exists").emit(false);
            }
            List<String[]> copy = new ArrayList<>(next);
            entries.clear();
            entries.addAll(copy);
            if (next.size() != old) bank.child("itemCount").emit(next.size());
        }

        private void append() {
            int index = (Integer) bank.child("itemCount").data;
            if (index < slots.length) {
                slots[index].child("deviceType").emit("audio-effect");
                if (!insertMode.equals("countOnly")) slots[index].child("name").emit("Inserted");
                slots[index].child("exists").emit(true);
            }
            entries.add(new String[] {"Inserted", "audio-effect"});
            bank.child("itemCount").emit(index + 1);
        }
    }
}
