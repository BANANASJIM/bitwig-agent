package dev.bitwigagent.device;

import com.google.gson.JsonObject;
import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.HostOperation;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.core.Refs;
import dev.bitwigagent.core.RpcException;
import dev.bitwigagent.core.Scheduler;
import dev.bitwigagent.host.DeviceSources;
import dev.bitwigagent.host.NativeRuntime;
import dev.bitwigagent.nativehost.NativeChainAccess;
import dev.bitwigagent.nativehost.NativeHost;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Chains of a device and the devices inside them, read and edited through the native document model. Nothing is
 * observed between requests: every request walks down from its verified root device, finds a chain by its UUID and a
 * device by its index, and compares the ref's fields with what it found, so a chain may hold any number of devices
 * and a device stays addressable after inserts and deletes around it. Ancestors deeper than one hop are walked the
 * same way.
 */
public final class NativeChains {
    /** Rows one list returns; a longer container reports an incomplete window. */
    static final int MAX_ITEMS = 256;

    /** The document-thread operations on chains and the devices in them; one binding per host version. */
    public interface Access {
        void execute(Runnable work);

        void documentThread();

        /** The native root device behind an observed root device, owned by its observed track. */
        Object root(DeviceSources.Source source, Runnable check);

        /** The device's chains (layers, selector chains, drum pads with content), or null when it has none. */
        List<Object> chains(Object device);

        UUID chainId(Object chain);

        String chainName(Object chain);

        int chainIndex(Object chain);

        /** The chain's devices in order; a snapshot the caller may keep. */
        List<Object> devices(Object chain);

        String deviceName(Object device);

        /** The type as the controller API names it: instrument, audio-effect, note-effect or note-detector. */
        String deviceType(Object device);

        String[] parameterIds(Object device);

        /** The device's direct parameter with this id, or null. */
        Object parameter(Object device, String id);

        String parameterName(Object parameter);

        double value(Object parameter);

        /** The host's display text of the parameter's value, or null when it has none. */
        String display(Object parameter);

        boolean writable(Object parameter);

        void write(Object parameter, double normalized);

        /** The device's On/Off parameter. */
        Object enabled(Object device);

        /** What to insert for kind and id or path, or null when the host knows no such device, plug-in or file. */
        Object insertion(String kind, String value);

        /** Inserts at {@code index} of {@code chain}; the device may appear only after this call returns. */
        void insert(Object chain, int index, Object insertion);

        void delete(Object device);

        /** Moves {@code device} to before the device now at {@code index} of its own chain (the end when index is the count). */
        void move(Object device, Object chain, int index);

        void select(Object device);

        /** The result's nativeVersion and nativeJarSha256. */
        void provenance(JsonObject out);
    }

    private final Scheduler scheduler;
    private final DeviceSources sources;
    private final NativeRuntime natives;
    private Access access;

    public NativeChains(Scheduler scheduler, DeviceSources sources, NativeRuntime natives, Access access) {
        this.scheduler = scheduler;
        this.sources = sources;
        this.natives = natives;
        this.access = access;
    }

    /** Whether a device ref names a device inside a chain. */
    public static boolean nested(JsonObject deviceRef) {
        return deviceRef.has("chain");
    }

    /** The root device ref every chain and nested device ref descends from. */
    static JsonObject rootDevice(JsonObject ref) {
        if ("chain".equals(Refs.kind(ref))) return rootDevice(ref.getAsJsonObject("device"));
        return nested(ref) ? rootDevice(ref.getAsJsonObject("chain")) : ref;
    }

    private Access access(HostOperation op) {
        if (access == null) {
            try {
                access = new NativeChainAccess(natives.get(op).access());
            } catch (ReflectiveOperationException | LinkageError error) {
                throw Errors.notApplied(
                        Reason.NATIVE_UNAVAILABLE, "Native chain binding unavailable: " + NativeHost.diagnostic(error));
            }
        }
        return access;
    }

    // ---------------------------------------------------------------- walking from the root

    /** One chain found by a walk: its ref as read now and its devices at that moment. */
    static final class Chain {
        final Object chain;
        final JsonObject ref;
        final List<Object> devices;

        Chain(Object chain, JsonObject ref, List<Object> devices) {
            this.chain = chain;
            this.ref = ref;
            this.devices = devices;
        }
    }

    /** One device inside a chain found by a walk. */
    static final class Device {
        final Chain chain;
        final Object device;
        final int index;
        final String name;
        final JsonObject ref;

        Device(Chain chain, Object device, int index, String name, JsonObject ref) {
            this.chain = chain;
            this.device = device;
            this.index = index;
            this.name = name;
            this.ref = ref;
        }
    }

    /** One document-thread pass from the verified root device. */
    final class Walk {
        final HostOperation op;
        final Access a;
        final Runnable check;
        private final JsonObject rootRef;
        private final Object root;

        private Walk(HostOperation op, Access a, Root context) {
            this.op = op;
            this.a = a;
            check = op.dispatched() ? () -> scheduler.check(op) : context.check;
            a.documentThread();
            check.run();
            rootRef = context.ref;
            root = a.root(context.source, check);
        }

        /** The native device a device ref names: the verified root, or a device found inside a chain. */
        Object owner(JsonObject deviceRef) {
            if (nested(deviceRef)) return device(deviceRef, false).device;
            requireRoot(deviceRef);
            return root;
        }

        private void requireRoot(JsonObject deviceRef) {
            if (!Refs.matches(deviceRef, rootRef, false))
                throw Errors.invalid("The device is not under this request's root device.");
        }

        /** The chains of the device a ref names, or unsupported_scope when it has none. */
        List<Chain> chains(JsonObject deviceRef) {
            Object owner;
            JsonObject parent;
            if (nested(deviceRef)) {
                Device device = device(deviceRef, false);
                owner = device.device;
                parent = device.ref;
            } else {
                requireRoot(deviceRef);
                owner = root;
                parent = rootRef;
            }
            List<Object> found = a.chains(owner);
            if (found == null)
                throw Errors.notApplied(Reason.UNSUPPORTED_SCOPE, "The device has no layers, chains or drum pads.");
            List<Chain> out = new ArrayList<>(found.size());
            for (Object chain : found)
                out.add(new Chain(
                        chain, Refs.chain(parent, a.chainId(chain).toString(), a.chainName(chain)), a.devices(chain)));
            return out;
        }

        /** The chain a ref names by its UUID; its name counts only when the chain itself is the target. */
        Chain chain(JsonObject ref, boolean target) {
            String id = ref.get("id").getAsString();
            Chain found = null;
            for (Chain chain : chains(ref.getAsJsonObject("device"))) {
                if (!id.equals(chain.ref.get("id").getAsString())) continue;
                if (found != null) throw Errors.notApplied(Reason.NOT_READY, "Two chains of the device have this id.");
                found = chain;
            }
            if (found == null) throw Errors.targetNotFound(ref, "The device has no chain with this id.");
            if (!Refs.matches(ref, found.ref, target))
                throw Errors.targetChanged("The chain is not the one read.", found.ref);
            return found;
        }

        /** The device at a nested ref's index of its chain, compared by name like any device ref. */
        Device device(JsonObject ref, boolean target) {
            Chain chain = chain(ref.getAsJsonObject("chain"), false);
            int index = ref.get("index").getAsInt();
            if (index >= chain.devices.size())
                throw Errors.targetNotFound(ref, "No device is at this index of the chain.");
            Device found = at(chain, index);
            if (!Refs.matches(ref, found.ref, target))
                throw Errors.targetChanged("Another device is at this position than the one read.", found.ref);
            return found;
        }

        Device at(Chain chain, int index) {
            Object device = chain.devices.get(index);
            String name = a.deviceName(device);
            return new Device(chain, device, index, name, Refs.device(chain.ref, index, name));
        }

        /** The chain's devices as read now, in order. */
        List<Object> now(Chain chain) {
            return a.devices(chain.chain);
        }

        /** Right before the native call that changes the document: from here on a failure is an unknown outcome. */
        void dispatch() {
            dispatch(() -> {});
        }

        /** {@link #dispatch()} after {@code guard}, the method's own safety check, runs once more right here. */
        void dispatch(Runnable guard) {
            check.run();
            guard.run();
            op.markDispatched();
        }
    }

    /** What a request resolved on the controller thread before going native. */
    private static final class Root {
        final DeviceSources.Source source;
        final DeviceSources.RootReadTicket ticket;
        final JsonObject ref;
        final Runnable check;

        Root(DeviceSources.Source source, DeviceSources.RootReadTicket ticket, Runnable check) {
            this.source = source;
            this.ticket = ticket;
            this.ref = source.ref();
            this.check = check;
        }
    }

    private Root root(HostOperation op, JsonObject ref) {
        DeviceSources.Source source = sources.device(rootDevice(ref), false);
        DeviceSources.RootReadTicket ticket = sources.rootRead(source);
        return new Root(source, ticket, () -> {
            scheduler.check(op);
            ticket.check();
        });
    }

    // ---------------------------------------------------------------- requests

    /** Reads (or writes and reads back at once) on the document thread; the result completes {@code op}. */
    void run(HostOperation op, JsonObject ref, Function<Walk, JsonObject> work) {
        Access a = access(op);
        Root root = root(op, ref);
        a.execute(() -> {
            try {
                JsonObject result = work.apply(new Walk(op, a, root));
                complete(op, root, result);
            } catch (RuntimeException | Error error) {
                op.fail(translated(error));
            }
        });
    }

    /**
     * An edit whose effect may appear after its native call returns: {@code dispatch} runs once on the document
     * thread and returns how to verify; the verification runs on every poll until it concludes or the observation
     * window ends.
     */
    void edit(HostOperation op, JsonObject ref, Function<Walk, Verify> dispatch) {
        Access a = access(op);
        Root root = root(op, ref);
        long until = scheduler.observeUntil(op);
        a.execute(() -> {
            try {
                Walk walk = new Walk(op, a, root);
                Verify verify = dispatch.apply(walk);
                JsonObject done = verify.done(walk);
                if (done != null) complete(op, root, done);
                else scheduler.schedule(op, () -> poll(op, a, root, verify, until), Scheduler.POLL_MS);
            } catch (RuntimeException | Error error) {
                op.fail(translated(error));
            }
        });
    }

    /** Native work for a request that completes elsewhere (a display write's probes); failures go to {@code failed}. */
    void async(HostOperation op, JsonObject ref, Consumer<Walk> work, Consumer<RuntimeException> failed) {
        Access a = access(op);
        Root root = root(op, ref);
        a.execute(() -> {
            try {
                work.accept(new Walk(op, a, root));
            } catch (RuntimeException error) {
                failed.accept(translated(error));
            } catch (Error error) {
                failed.accept(translated(error));
            }
        });
    }

    /** How an edit is proven: {@code done} concludes from the chain as it is now, null while it may still change. */
    interface Verify {
        JsonObject done(Walk walk);

        /** The conclusion when the observation window ended before {@link #done} did. */
        JsonObject timeout(Walk walk);
    }

    private void poll(HostOperation op, Access a, Root root, Verify verify, long until) {
        boolean last = scheduler.now() >= until;
        a.execute(() -> {
            try {
                Walk walk = new Walk(op, a, root);
                JsonObject done = verify.done(walk);
                if (done == null && last) done = verify.timeout(walk);
                if (done != null) complete(op, root, done);
                else scheduler.schedule(op, () -> poll(op, a, root, verify, until), Scheduler.POLL_MS);
            } catch (RuntimeException | Error error) {
                op.fail(translated(error));
            }
        });
    }

    /** Completes on the controller thread; a read is delivered only while the root device is still the one read. */
    private void complete(HostOperation op, Root root, JsonObject result) {
        scheduler.schedule(
                op,
                () -> {
                    if (!op.dispatched()) sources.guardRootRead(root.ticket);
                    op.result.complete(result);
                },
                0);
    }

    private static RuntimeException translated(Throwable error) {
        if (error instanceof RpcException) return (RpcException) error;
        return Errors.notApplied(
                Reason.NATIVE_UNAVAILABLE,
                "Native chain access failed: " + error.getClass().getSimpleName()
                        + "; inspect the exact host binding.");
    }
}
