package dev.bitwigagent;

import static dev.bitwigagent.HostLimits.*;

import com.bitwig.extension.callback.IntegerValueChangedCallback;
import com.bitwig.extension.controller.api.*;
import com.google.gson.*;
import dev.bitwigagent.core.Refs;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * chains.list and the verified edits of a device chain (devices.insert, devices.delete, devices.move). A track's root
 * chain is edited through the public API and proven by the observed bank delta; a chain inside a device is listed,
 * edited and proven natively by {@link NativeChainEdits}.
 */
final class DeviceChainModule implements HostModule {
    static final Set<String> METHODS = Set.of("chains.list", "devices.insert", "devices.delete", "devices.move");
    private final Scheduler scheduler;
    private final TrackWindow window;
    private final TrackRefs trackRefs;
    private final DeviceSources sources;
    private final TransportSafety transportSafety;
    private final NativeChainEdits nested;
    private final InsertionPoint[] ends = new InsertionPoint[TRACKS];
    private final boolean[] countObserved = new boolean[TRACKS];
    private final long[] countSequence = new long[TRACKS];
    private HostOperation mutation;

    DeviceChainModule(
            Scheduler scheduler,
            TrackWindow window,
            TrackRefs trackRefs,
            DeviceSources sources,
            NativeChains chains,
            TransportSafety transportSafety) {
        this.scheduler = scheduler;
        this.window = window;
        this.trackRefs = trackRefs;
        this.sources = sources;
        this.transportSafety = transportSafety;
        nested = new NativeChainEdits(chains);
        for (int t = 0; t < TRACKS; t++) {
            final int slot = t;
            ends[t] = window.at(t).endOfDeviceChainInsertionPoint();
            window.devices(t).itemCount().addValueObserver((IntegerValueChangedCallback) value -> {
                countObserved[slot] = true;
                countSequence[slot]++;
            });
        }
    }

    @Override
    public Set<String> methods() {
        return METHODS;
    }

    @Override
    public void validate(String method, JsonObject params) {
        if (method.equals("devices.insert")) validateInsertion(params);
    }

    /** The kind-specific shape of an insertion, which the schema cannot state. */
    static void validateInsertion(JsonObject p) {
        String kind = Params.string(p, "kind");
        if (kind.equals("file")) {
            if (!p.has("path") || p.has("id")) throw Errors.invalid("File insertion requires only path, not id.");
            Params.absoluteFilePath(p, "path");
            return;
        }
        if (!p.has("id") || p.has("path")) throw Errors.invalid("Device insertion requires only id, not path.");
        String id = Params.string(p, "id");
        switch (kind) {
            case "bitwig":
                if (!id.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
                    throw Errors.invalid("Bitwig id must be a canonical UUID.");
                return;
            case "vst2":
                if (!id.matches("-?(0|[1-9][0-9]{0,9})"))
                    throw Errors.invalid("VST2 id must be a signed int32 decimal string.");
                try {
                    Integer.parseInt(id);
                } catch (NumberFormatException error) {
                    throw Errors.invalid("VST2 id is outside int32.");
                }
                return;
            case "vst3":
                if (!id.matches("[0-9a-fA-F]{32}"))
                    throw Errors.invalid("VST3 id must contain exactly 32 hexadecimal digits.");
                return;
            case "clap":
                if (!id.matches("[!-~]{1,256}"))
                    throw Errors.invalid("CLAP id must be 1..256 printable non-whitespace ASCII characters.");
                return;
            default:
                throw Errors.invalid("kind must be bitwig, vst2, vst3, clap, or file.");
        }
    }

    @Override
    public void dispatch(HostOperation op, String method, JsonObject p) {
        if (method.equals("chains.list")) {
            nested.list(op, p.getAsJsonObject("parent"));
            return;
        }
        if (mutation != null && !mutation.result.isDone())
            throw Errors.notApplied(Reason.BUSY, "Another device chain edit is awaiting observations.");
        mutation = op;
        boolean inChain = method.equals("devices.insert")
                ? "chain".equals(Refs.kind(p.getAsJsonObject("parent")))
                : NativeChains.nested(p.getAsJsonObject("target"));
        switch (method) {
            case "devices.insert":
                if (inChain) nested.insert(op, p);
                else insert(op, p);
                return;
            case "devices.delete":
                if (inChain) {
                    transportSafety.requirePlaybackSafe();
                    nested.delete(op, p.getAsJsonObject("target"), transportSafety::requirePlaybackSafe);
                } else delete(op, p.getAsJsonObject("target"));
                return;
            default:
                if (inChain) {
                    transportSafety.requirePlaybackSafe();
                    nested.move(
                            op,
                            p.getAsJsonObject("target"),
                            p.getAsJsonObject("to"),
                            transportSafety::requirePlaybackSafe);
                } else move(op, p.getAsJsonObject("target"), p.getAsJsonObject("to"));
        }
    }

    // ---------------------------------------------------------------- the parent chains an edit works on

    /** The track's chain being edited: how to read it, where its insertion points are and what must not change. */
    private abstract class Parent {
        abstract DeviceChainEdit.View view();

        abstract JsonObject ref();

        abstract InsertionPoint start();

        abstract InsertionPoint end();

        abstract DeviceSources.Source at(int index);

        abstract BooleanSupplier context();

        abstract String contextLost();

        /** Complete, ready window: count, per-slot existence and the revisions of every present device. */
        abstract State capture(String staleMessage);

        abstract boolean prefixMatches(long[] prefix);

        abstract int window();
    }

    private final class Top extends Parent {
        final int t;
        final String trackId;

        Top(int t) {
            this.t = t;
            trackId = sources.roots[t][0].track.id;
        }

        @Override
        DeviceChainEdit.View view() {
            return new TopView(t);
        }

        @Override
        JsonObject ref() {
            return trackRefs.ref(t);
        }

        @Override
        InsertionPoint start() {
            return window.at(t).startOfDeviceChainInsertionPoint();
        }

        @Override
        InsertionPoint end() {
            return ends[t];
        }

        @Override
        DeviceSources.Source at(int index) {
            return sources.roots[t][index];
        }

        @Override
        BooleanSupplier context() {
            return () -> window.trackMatches(trackId, window.at(t))
                    && window.devices(t).scrollPosition().get() == 0;
        }

        @Override
        String contextLost() {
            return "Track or device window changed after dispatch.";
        }

        @Override
        int window() {
            return DEVICES;
        }

        @Override
        State capture(String staleMessage) {
            if (!countObserved[t]) throw Errors.notApplied(Reason.NOT_READY, "Device count has not been observed.");
            int count = window.devices(t).itemCount().get();
            if (window.devices(t).scrollPosition().get() != 0) throw Errors.notApplied(Reason.NOT_READY, staleMessage);
            if (count > DEVICES)
                throw Errors.notApplied(
                        Reason.OUTSIDE_WINDOW,
                        "The chain is longer than the 16-device window; only inserts at a bounded count are supported.");
            long[] revisions = new long[count];
            for (int d = 0; d < DEVICES; d++) {
                DeviceSources.Source source = sources.roots[t][d];
                if (d < count
                        && source.exists != null
                        && source.device.exists().get()
                        && source.device.position().get() != d)
                    throw Errors.notApplied(Reason.NOT_READY, "Device count and bank contents disagree.");
                if (d < count) revisions[d] = source.revision;
            }
            return new State(revisions, DeviceChainEdit.Before.capture(view(), staleMessage));
        }

        @Override
        boolean prefixMatches(long[] prefix) {
            for (int d = 0; d < prefix.length; d++) {
                DeviceSources.Source source = sources.roots[t][d];
                if (source.revision != prefix[d]
                        || !source.device.exists().get()
                        || source.device.position().get() != d) return false;
            }
            return true;
        }
    }

    private Parent parentOf(DeviceSources.Source device) {
        return new Top(device.track.slot);
    }

    private Parent parentOf(JsonObject trackRef) {
        return new Top(trackRefs.resolve(trackRef, false));
    }

    private static final class State {
        final long[] revisions;
        final DeviceChainEdit.Before before;

        State(long[] revisions, DeviceChainEdit.Before before) {
            this.revisions = revisions;
            this.before = before;
        }
    }

    // ---------------------------------------------------------------- insert

    private void insert(HostOperation op, JsonObject p) {
        Parent parent = parentOf(p.getAsJsonObject("parent"));
        JsonElement position = p.get("position");
        int index = sources.insertionIndex(parent.ref(), position);
        State state = parent.capture("The chain changed before dispatch.");
        InsertionPoint point = insertionPoint(parent, position);
        scheduler.check(op);
        if (!parent.context().getAsBoolean() || !parent.prefixMatches(prefix(state, index)))
            throw Errors.notApplied(Reason.TARGET_CHANGED, "The chain changed before dispatch.");
        String kind = p.get("kind").getAsString();
        String value = p.get(kind.equals("file") ? "path" : "id").getAsString();
        Edit edit = new Edit(parent, DeviceChainEdit.Plan.insert(index, state.before), state, index);
        edit.extra.addProperty("requestedIdentifierVerified", false);
        edit.extra.add("parent", parent.ref());
        op.markDispatched();
        insertAt(point, kind, value);
        observeEdit(op, edit);
    }

    private InsertionPoint insertionPoint(Parent parent, JsonElement position) {
        if (position == null || position.isJsonPrimitive())
            return position != null && "start".equals(position.getAsString()) ? parent.start() : parent.end();
        JsonObject anchored = position.getAsJsonObject();
        boolean before = anchored.has("before");
        DeviceSources.Source anchor = sources.device(anchored.getAsJsonObject(before ? "before" : "after"), false);
        return before ? anchor.device.beforeDeviceInsertionPoint() : anchor.device.afterDeviceInsertionPoint();
    }

    private static void insertAt(InsertionPoint point, String kind, String value) {
        switch (kind) {
            case "bitwig":
                point.insertBitwigDevice(UUID.fromString(value));
                break;
            case "vst2":
                point.insertVST2Device(Integer.parseInt(value));
                break;
            case "vst3":
                point.insertVST3Device(value);
                break;
            case "clap":
                point.insertCLAPDevice(value);
                break;
            case "file":
                point.insertFile(value);
                break;
            default:
                throw new IllegalStateException("Validated insertion kind changed");
        }
    }

    // ---------------------------------------------------------------- delete and move

    private void delete(HostOperation op, JsonObject target) {
        DeviceSources.Source device = sources.device(target, true);
        Parent parent = parentOf(device);
        int index = device.position;
        transportSafety.requirePlaybackSafe();
        State state = parent.capture("The chain changed before dispatch.");
        if (index >= state.before.count || index >= parent.window())
            throw Errors.notApplied(Reason.TARGET_NOT_FOUND, "The device is beyond the observed device count.");
        scheduler.check(op);
        Edit edit = new Edit(parent, DeviceChainEdit.Plan.delete(index, state.before), state, index);
        edit.extra.add("deleted", DeviceChainEdit.summary(index, state.before.names[index], state.before.types[index]));
        op.markDispatched();
        device.device.deleteObject();
        observeEdit(op, edit);
    }

    private void move(HostOperation op, JsonObject target, JsonObject to) {
        DeviceSources.Source device = sources.device(target, true);
        Parent parent = parentOf(device);
        if (to.has("parent") && !sameParent(parent, to.getAsJsonObject("parent")))
            throw Errors.notApplied(
                    Reason.UNSUPPORTED_SCOPE, "Moving a device to another chain or track is not supported yet.");
        int from = device.position;
        int insertion = sources.insertionIndex(parent.ref(), to.get("position"));
        int dest = insertion > from ? insertion - 1 : insertion;
        transportSafety.requirePlaybackSafe();
        State state = parent.capture("The chain changed before dispatch.");
        int n = state.before.count;
        if (n > parent.window())
            throw Errors.notApplied(
                    Reason.OUTSIDE_WINDOW,
                    "A move needs the whole chain inside its " + parent.window() + "-device window.");
        if (from >= n || dest >= n)
            throw Errors.notApplied(Reason.TARGET_NOT_FOUND, "The position is beyond the chain.");
        scheduler.check(op);
        if (dest == from) {
            JsonObject result = Results.outcome(Outcome.ALREADY_MATCHED);
            result.add("ref", device.ref());
            op.result.complete(result);
            return;
        }
        DeviceChainEdit.Plan plan = DeviceChainEdit.Plan.move(from, dest, state.before);
        Edit edit = new Edit(parent, plan, state, 0);
        JsonObject moved = DeviceChainEdit.summary(from, state.before.names[from], state.before.types[from]);
        moved.addProperty("toIndex", dest);
        edit.extra.add("moved", moved);
        // Moving later lands after the device at dest, moving earlier lands before it: the device ends at index dest.
        DeviceSources.Source anchor = parent.at(dest);
        InsertionPoint point =
                dest > from ? anchor.device.afterDeviceInsertionPoint() : anchor.device.beforeDeviceInsertionPoint();
        op.markDispatched();
        point.moveDevices(device.device);
        if (plan.indistinguishable()) {
            edit.extra.addProperty(
                    "hint", "Same names and types in the same order: the host cannot show whether anything moved.");
            finishEdit(op, edit, DeviceChainEdit.Observation.ambiguous());
            return;
        }
        observeEdit(op, edit);
    }

    private boolean sameParent(Parent own, JsonObject requested) {
        return Refs.matches(requested, own.ref(), false);
    }

    // ---------------------------------------------------------------- observation

    /** One verified edit in flight: what was planned, how to read the chain, and what to add to the result. */
    private static final class Edit {
        final Parent parent;
        final DeviceChainEdit.View view;
        final DeviceChainEdit.Plan plan;
        final long[] prefixRevisions;
        final JsonObject extra = new JsonObject();

        Edit(Parent parent, DeviceChainEdit.Plan plan, State state, int fixedPrefix) {
            this.parent = parent;
            this.view = parent.view();
            this.plan = plan;
            prefixRevisions = prefix(state, fixedPrefix);
        }
    }

    private static long[] prefix(State state, int fixedPrefix) {
        return Arrays.copyOf(state.revisions, Math.min(fixedPrefix, state.revisions.length));
    }

    private void observeEdit(HostOperation op, Edit edit) {
        BooleanSupplier context = edit.parent.context();
        scheduler.conclude(
                op,
                scheduler.observeUntil(op),
                () -> {
                    if (!context.getAsBoolean()) return () -> uncertain(op, edit.parent.contextLost());
                    DeviceChainEdit.Observation outcome = observedEdit(edit);
                    return outcome.pending ? null : () -> finishEdit(op, edit, outcome);
                },
                () -> finishEdit(op, edit, observedEdit(edit)));
    }

    private DeviceChainEdit.Observation observedEdit(Edit edit) {
        return !edit.parent.prefixMatches(edit.prefixRevisions)
                ? DeviceChainEdit.Observation.ambiguous()
                : DeviceChainEdit.observe(edit.view, edit.plan);
    }

    private void finishEdit(HostOperation op, Edit edit, DeviceChainEdit.Observation outcome) {
        DeviceChainEdit.View view = edit.view;
        DeviceChainEdit.Plan plan = edit.plan;
        JsonObject result = outcome.ok ? Results.outcome(Outcome.APPLIED) : Results.outcome(Outcome.UNKNOWN);
        if (!outcome.ok)
            result.addProperty("reason", (outcome.pending ? Reason.NOT_OBSERVED : Reason.READBACK_MISMATCH).wire());
        if (outcome.ok) result.addProperty("verification", outcome.verification);
        result.addProperty("beforeCount", plan.before.count);
        result.addProperty("afterCount", view.count());
        result.add("before", DeviceChainEdit.before(plan.before));
        result.add("after", DeviceChainEdit.after(view));
        for (String key : edit.extra.keySet()) result.add(key, edit.extra.get(key));
        if (plan.kind == DeviceChainEdit.INSERT) {
            boolean identified = outcome.ok && plan.index < view.window();
            if (identified) {
                result.add("ref", edit.parent.at(plan.index).ref());
                result.add(
                        "inserted", DeviceChainEdit.summary(plan.index, view.name(plan.index), view.type(plan.index)));
            } else result.add("inserted", JsonNull.INSTANCE);
        } else if (plan.kind == DeviceChainEdit.MOVE && outcome.ok)
            result.add("ref", edit.parent.at(plan.to).ref());
        op.result.complete(result);
    }

    private static void uncertain(HostOperation op, String message) {
        op.result.complete(Errors.unknownResult(Errors.unknown(Reason.TARGET_CHANGED, message)));
    }

    // ---------------------------------------------------------------- views

    private final class TopView implements DeviceChainEdit.View {
        final int t;

        TopView(int t) {
            this.t = t;
        }

        @Override
        public int count() {
            return window.devices(t).itemCount().get();
        }

        @Override
        public int window() {
            return DEVICES;
        }

        @Override
        public long countSequence() {
            return countSequence[t];
        }

        @Override
        public boolean known(int slot) {
            return sources.roots[t][slot].exists != null;
        }

        @Override
        public boolean exists(int slot) {
            return sources.roots[t][slot].device.exists().get();
        }

        @Override
        public boolean ready(int slot) {
            return sources.roots[t][slot].metadataReady()
                    && !sources.roots[t][slot].device.name().get().isEmpty();
        }

        @Override
        public String name(int slot) {
            return sources.roots[t][slot].device.name().get();
        }

        @Override
        public String type(int slot) {
            return sources.roots[t][slot].device.deviceType().get();
        }

        @Override
        public long nameSequence(int slot) {
            return sources.roots[t][slot].nameSequence;
        }
    }
}
