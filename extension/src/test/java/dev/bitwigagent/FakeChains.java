package dev.bitwigagent;

import java.util.*;
import java.util.function.DoubleFunction;

/**
 * A simulated native document of chains: root devices by track and device slot, their chains and devices as plain
 * objects, edits that change those lists in place. The document thread is the caller's thread.
 */
final class FakeChains implements NativeChains.Access {
    static final class Node {
        String name, type;
        List<Chain> chains;
        final Map<String, Param> params = new LinkedHashMap<>();
        final Param enabled = new Param("On/Off", 1);

        Node(String name, String type) {
            this.name = name;
            this.type = type;
        }

        Param param(String id, String name, double value) {
            Param param = new Param(name, value);
            params.put(id, param);
            return param;
        }

        /** Makes this a layer device: its chains, empty until added. */
        Node layered() {
            if (chains == null) chains = new ArrayList<>();
            return this;
        }

        Chain chain(String id, String name, Node... devices) {
            layered();
            Chain chain = new Chain(UUID.fromString(id), name, chains.size());
            chain.devices.addAll(Arrays.asList(devices));
            chains.add(chain);
            return chain;
        }
    }

    static final class Chain {
        UUID id;
        String name;
        final int index;
        final List<Node> devices = new ArrayList<>();

        Chain(UUID id, String name, int index) {
            this.id = id;
            this.name = name;
            this.index = index;
        }
    }

    static final class Param {
        String name;
        double value;
        boolean writable = true;
        int writes;
        /** What a write leaves behind when it is not the written value (a host that clamps or ignores it). */
        Double sticks;

        DoubleFunction<String> display = v -> String.format(Locale.ROOT, "%.1f %%", v * 100);

        Param(String name, double value) {
            this.name = name;
            this.value = value;
        }
    }

    final Map<String, Node> roots = new HashMap<>();
    /** Polls before an insertion appears; zero shows it right after the call. */
    int insertDelay;

    Set<String> unknownIds = new HashSet<>();
    /** Deletes and moves the host accepts but never carries out. */
    boolean ignoreEdits;
    /** Someone else's change right after an edit of ours. */
    Runnable onEdit = () -> {};
    /** A failure of the binding itself, raised by the next chain listing. */
    RuntimeException broken;

    Node selected;
    int executions;
    private final List<Runnable> pending = new ArrayList<>();
    private int pendingPolls;

    static Node device(String name) {
        return new Node(name, "audio-effect");
    }

    /** The root device at {@code device} of track slot {@code track}. */
    Node root(int track, int device, Node node) {
        roots.put(track + ":" + device, node);
        return node;
    }

    @Override
    public void execute(Runnable work) {
        executions++;
        if (!pending.isEmpty() && pendingPolls-- <= 0) {
            pending.forEach(Runnable::run);
            pending.clear();
        }
        work.run();
    }

    @Override
    public void documentThread() {}

    @Override
    public Object root(DeviceSources.Source source, Runnable check) {
        check.run();
        return roots.computeIfAbsent(source.track.slot + ":" + source.slot, key -> device(source.name));
    }

    @Override
    public List<Object> chains(Object device) {
        if (broken != null) throw broken;
        List<Chain> chains = ((Node) device).chains;
        return chains == null ? null : new ArrayList<>(chains);
    }

    @Override
    public UUID chainId(Object chain) {
        return ((Chain) chain).id;
    }

    @Override
    public String chainName(Object chain) {
        return ((Chain) chain).name;
    }

    @Override
    public int chainIndex(Object chain) {
        return ((Chain) chain).index;
    }

    @Override
    public List<Object> devices(Object chain) {
        return new ArrayList<>(((Chain) chain).devices);
    }

    @Override
    public String deviceName(Object device) {
        return ((Node) device).name;
    }

    @Override
    public String deviceType(Object device) {
        return ((Node) device).type;
    }

    @Override
    public String[] parameterIds(Object device) {
        return ((Node) device).params.keySet().toArray(new String[0]);
    }

    @Override
    public Object parameter(Object device, String id) {
        return ((Node) device).params.get(id);
    }

    @Override
    public String parameterName(Object parameter) {
        return ((Param) parameter).name;
    }

    @Override
    public double value(Object parameter) {
        return ((Param) parameter).value;
    }

    @Override
    public String display(Object parameter) {
        Param param = (Param) parameter;
        return param.display.apply(param.value);
    }

    @Override
    public boolean writable(Object parameter) {
        return ((Param) parameter).writable;
    }

    @Override
    public void write(Object parameter, double normalized) {
        Param param = (Param) parameter;
        param.writes++;
        param.value = param.sticks != null ? param.sticks : normalized;
    }

    @Override
    public Object enabled(Object device) {
        return ((Node) device).enabled;
    }

    @Override
    public Object insertion(String kind, String value) {
        if (unknownIds.contains(value)) return null;
        return kind.equals("file")
                ? device(new java.io.File(value).getName().replaceFirst("\\.[^.]*$", ""))
                : device("Inserted");
    }

    @Override
    public void insert(Object chain, int index, Object insertion) {
        Runnable add = () -> {
            ((Chain) chain).devices.add(index, (Node) insertion);
            onEdit.run();
        };
        if (insertDelay == 0) add.run();
        else {
            pending.add(add);
            pendingPolls = insertDelay;
        }
    }

    @Override
    public void delete(Object device) {
        if (ignoreEdits) return;
        for (Node root : roots.values()) remove(root, (Node) device);
        onEdit.run();
    }

    private static boolean remove(Node owner, Node device) {
        if (owner.chains == null) return false;
        for (Chain chain : owner.chains) {
            if (chain.devices.remove(device)) return true;
            for (Node inner : chain.devices) if (remove(inner, device)) return true;
        }
        return false;
    }

    @Override
    public void move(Object device, Object chain, int index) {
        if (ignoreEdits) return;
        List<Node> devices = ((Chain) chain).devices;
        int from = devices.indexOf(device);
        devices.remove(from);
        devices.add(index > from ? index - 1 : index, (Node) device);
        onEdit.run();
    }

    @Override
    public void select(Object device) {
        selected = (Node) device;
    }
}
