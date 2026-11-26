package dev.bitwigagent;

import static dev.bitwigagent.HostLimits.*;
import static dev.bitwigagent.Results.*;

import com.bitwig.extension.callback.*;
import com.bitwig.extension.controller.api.*;
import com.google.gson.*;
import dev.bitwigagent.core.Refs;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Resolves device and chain refs on fixed observed device addresses and corroborates a root device's native owner.
 * Tokens describe continuity, never persistent device identity.
 */
final class DeviceSources {
    static final int LAYERS = 2, CHILDREN = 2;
    final Source[][] roots = new Source[TRACKS][DEVICES];
    final List<Source> all = new ArrayList<>(TRACKS * DEVICES * (1 + LAYERS * CHILDREN));
    private final TrackWindow window;
    private final TrackRefs trackRefs;
    private final TrackState[] tracks = new TrackState[TRACKS];
    private Integer trackOffset;

    DeviceSources(TrackWindow window, TrackRefs trackRefs) {
        this.window = window;
        this.trackRefs = trackRefs;
        window.bank().scrollPosition().addValueObserver((IntegerValueChangedCallback) value -> {
            boolean changed = trackOffset != null && trackOffset != value;
            trackOffset = value;
            if (changed) for (TrackState track : tracks) if (track != null) track.retire();
        });
        window.bank().itemCount().addValueObserver((IntegerValueChangedCallback) value -> {
            for (Source[] row : roots)
                for (Source source : row) if (source != null) source.rootReadEpoch.incrementAndGet();
        });
        for (int t = 0; t < TRACKS; t++) {
            TrackState track = new TrackState(t, window.at(t));
            tracks[t] = track;
            track.observe();
            for (int d = 0; d < DEVICES; d++) {
                Source root = new Source(track, d, (Device) window.devices(t).getItemAt(d), null);
                roots[t][d] = root;
                all.add(root);
                root.observe();
                root.branch = new Branch(root);
            }
        }
    }

    interface Listener {
        void changed(boolean structural, boolean name);
    }

    interface WindowChange {
        void changed(boolean knownDiscontinuity);
    }

    final class TrackState {
        final int slot;
        final Track proxy;
        Boolean exists;
        /** Volatile: native callbacks read it as a device's expected owner. */
        volatile String id;

        Integer position, offset, count;

        TrackState(int slot, Track proxy) {
            this.slot = slot;
            this.proxy = proxy;
        }

        void observe() {
            proxy.exists().addValueObserver((BooleanValueChangedCallback) value -> {
                boolean removed = Boolean.TRUE.equals(exists) && !value;
                boolean change = exists != null && exists != value;
                exists = value;
                if (removed) retire();
                else changed(change);
            });
            proxy.channelId().addValueObserver((StringValueChangedCallback) value -> {
                boolean replaced = canonicalUuid(id) && !id.equals(value);
                boolean change = id != null && !id.equals(value);
                id = (String) value;
                if (replaced) retire();
                else changed(change);
            });
            proxy.position().addValueObserver((IntegerValueChangedCallback) value -> {
                boolean change = position != null && position != value;
                boolean moved = change && position >= 0 && Boolean.TRUE.equals(exists);
                position = value;
                if (moved) retire();
                else changed(change);
            });
            window.devices(slot).scrollPosition().addValueObserver((IntegerValueChangedCallback) value -> {
                boolean change = offset != null && offset != value;
                offset = value;
                if (change && count != null && count > 0) retire();
                else changed(change);
            });
            window.devices(slot).itemCount().addValueObserver((IntegerValueChangedCallback) value -> {
                boolean change = count != null && count != value;
                Integer previousCount = count;
                count = value;
                if (change)
                    for (Source source : roots[slot]) {
                        if (source == null || source.branch == null) continue;
                        boolean previouslyInRange = previousCount != null
                                && offset != null
                                && offset >= 0
                                && (long) offset + source.slot < previousCount;
                        if (previouslyInRange) source.branch.retire();
                        else source.branch.changed(true, false);
                    }
                changed(false);
            });
        }
        // The initial unassigned position must not establish descendants before a real track address.
        boolean ready() {
            return Boolean.TRUE.equals(exists)
                    && canonicalUuid(id)
                    && position != null
                    && position >= 0
                    && offset != null
                    && count != null
                    && count >= 0
                    && trackOffset != null
                    && window.observed();
        }

        void retire() {
            for (Source source : roots[slot]) if (source != null) source.retire();
        }

        void changed(boolean structural) {
            for (Source source : roots[slot]) if (source != null) source.changed(structural, false);
        }
    }

    final class Source {
        final TrackState track;
        final int slot;
        final Device device;
        final Layer layer;
        final List<Listener> listeners = new ArrayList<>(2);
        Branch branch;
        Boolean exists;
        String name, type;
        Integer position;
        long epoch, revision, nameSequence;
        final AtomicLong rootReadEpoch;
        boolean established, rebound;
        // Legacy root direct callbacks can precede Value callbacks. This getter snapshot
        // preserves that root-only bank-proxy policy without marking metadata observed.
        String directTrackId, directName;
        int directPosition;
        String discoveryToken;

        Source(TrackState track, int slot, Device device, Layer layer) {
            this.track = track;
            this.slot = slot;
            this.device = device;
            this.layer = layer;
            rootReadEpoch = layer == null ? new AtomicLong() : null;
            if (layer == null) listeners.add((structural, renamed) -> rootReadEpoch.incrementAndGet());
            if (layer != null) {
                rebound = layer.rebound;
                if (!rebound) discoveryToken = UUID.randomUUID().toString();
            }
        }

        void observe() {
            device.exists().addValueObserver((BooleanValueChangedCallback) value -> {
                boolean removed = Boolean.TRUE.equals(exists) && !value;
                boolean change = exists != null && exists != value;
                exists = value;
                if (removed) retire();
                else changed(change, false);
            });
            device.position().addValueObserver((IntegerValueChangedCallback) value -> {
                boolean change = position != null && position != value;
                boolean moved = change && position >= 0 && Boolean.TRUE.equals(exists);
                position = value;
                if (moved) retire();
                else changed(change, false);
            });
            device.deviceType().addValueObserver((EnumValueChangedCallback) value -> {
                String observed = device.deviceType().get();
                boolean change = type != null && !type.equals(observed);
                boolean replaced = change && !type.isEmpty() && Boolean.TRUE.equals(exists);
                type = observed;
                if (replaced) retire();
                else changed(change, false);
            });
            device.name().addValueObserver((StringValueChangedCallback) value -> {
                boolean change = name != null && !name.equals(value);
                name = (String) value;
                nameSequence++;
                changed(false, change);
            });
        }

        boolean metadataReady() {
            return exists != null && name != null && position != null && type != null;
        }

        boolean present() {
            boolean ready = metadataReady()
                    && Boolean.TRUE.equals(exists)
                    && !name.isEmpty()
                    && !type.isEmpty()
                    && track.ready()
                    && (layer == null
                            ? position == track.offset + slot
                            : layer.ready() && position == slot && slot < layer.window.count);
            if (ready) established = true;
            return ready;
        }

        boolean directReady() {
            if (layer != null) return present() && !rebound;
            reconcileRootDirectContext();
            return present()
                    && track.proxy.exists().get()
                    && device.exists().get()
                    && Objects.equals(track.id, track.proxy.channelId().get())
                    && Objects.equals(name, device.name().get())
                    && position == device.position().get();
        }

        boolean acceptDirectObservation() {
            if (layer != null) return directReady();
            reconcileRootDirectContext();
            if (!track.proxy.exists().get() || !device.exists().get()) return false;
            if (directTrackId == null) {
                directTrackId = track.proxy.channelId().get();
                directName = device.name().get();
                directPosition = device.position().get();
            }
            return true;
        }

        private boolean rootDirectMoved() {
            return !track.proxy.exists().get()
                    || !device.exists().get()
                    || !Objects.equals(directTrackId, track.proxy.channelId().get())
                    || directPosition != device.position().get();
        }

        private void reconcileRootDirectContext() {
            if (directTrackId != null
                    && (rootDirectMoved()
                            || !Objects.equals(directName, device.name().get()))) changed(false, false);
        }

        void changed(boolean structural, boolean renamed) {
            changed(structural, renamed, false);
        }

        private void changed(boolean structural, boolean renamed, boolean knownNestedDiscontinuity) {
            // Initial absence/default metadata is not a physical replacement. Once usable, any
            // structural change retires nested direct access for this extension lifetime.
            boolean hadDirectContext = directTrackId != null;
            if (hadDirectContext) {
                // Before full metadata establishment, agreeing first notifications are not
                // replacements of the root getter tuple captured by an actual direct callback.
                structural = (structural && established) || rootDirectMoved();
                renamed |= !Objects.equals(directName, device.name().get());
                if (structural) {
                    directTrackId = null;
                    directName = null;
                } else if (renamed) directName = device.name().get();
            }
            boolean retired = structural && (established || hadDirectContext);
            if (retired) {
                epoch++;
                if (layer != null) rebound = true;
            }
            if (retired || renamed) {
                revision++;
                if (layer != null)
                    discoveryToken = rebound ? null : UUID.randomUUID().toString();
            }
            if (present()) established = true;
            for (Listener listener : listeners) listener.changed(retired, renamed);
            if (branch != null) {
                if (retired || knownNestedDiscontinuity) branch.retire();
                else if (renamed) branch.changed(false, true);
            }
        }

        void retire() {
            if (layer == null) {
                // Preserve accepted root getter tuples, but never preserve uncertain descendants.
                changed(true, false, true);
                return;
            }
            rebound = true;
            epoch++;
            revision++;
            discoveryToken = null;
            for (Listener listener : listeners) listener.changed(true, false);
        }

        JsonObject parentJson() {
            JsonObject p = new JsonObject();
            p.addProperty("trackId", track.id);
            p.addProperty("deviceIndex", position);
            p.addProperty("expectedDeviceName", name);
            return p;
        }

        JsonObject ref() {
            return Refs.device(layer == null ? trackRefs.ref(track.slot) : layer.ref(), position, name);
        }

        JsonObject address() {
            if (layer == null) return parentJson();
            JsonObject result = new JsonObject();
            result.add("parent", layer.branch.root.parentJson());
            result.addProperty("layerId", layer.id);
            result.addProperty("childDeviceIndex", position);
            result.addProperty("expectedChildDeviceName", name);
            result.addProperty("discoveryToken", discoveryToken);
            return result;
        }
    }

    final class Window {
        final int capacity;
        Boolean exists;
        Integer count, offset;
        long countSequence;

        Window(int capacity) {
            this.capacity = capacity;
        }

        void observe(BooleanValue existence, IntegerValue size, IntegerValue scroll, WindowChange changed) {
            existence.addValueObserver((BooleanValueChangedCallback) value -> {
                boolean change = exists != null && exists != value, removed = Boolean.TRUE.equals(exists) && !value;
                exists = value;
                if (change) changed.changed(removed);
            });
            size.addValueObserver((IntegerValueChangedCallback) value -> {
                boolean change = count != null && count != value, populated = count != null && count > 0;
                count = value;
                countSequence++;
                if (change) changed.changed(populated);
            });
            scroll.addValueObserver((IntegerValueChangedCallback) value -> {
                boolean change = offset != null && offset != value;
                offset = value;
                if (change) changed.changed(count != null && count > 0);
            });
        }

        boolean ready() {
            return Boolean.TRUE.equals(exists) && count != null && count >= 0 && offset != null && offset == 0;
        }

        JsonObject json() {
            JsonObject result = new JsonObject();
            result.addProperty("offset", offset);
            result.addProperty("capacity", capacity);
            result.addProperty("total", count);
            result.addProperty("exists", exists);
            result.addProperty("complete", ready() && count <= capacity);
            result.addProperty("readiness", ready() ? "ready" : "not_ready");
            return result;
        }
    }

    final class Branch {
        final Source root;
        final Window window = new Window(LAYERS);
        final Layer[] layers = new Layer[LAYERS];
        Boolean hasLayers;

        Branch(Source root) {
            this.root = root;
            root.device.hasLayers().addValueObserver((BooleanValueChangedCallback) value -> {
                boolean removed = Boolean.TRUE.equals(hasLayers) && !value;
                boolean change = hasLayers != null && hasLayers != value;
                hasLayers = value;
                if (removed) retire();
                else if (change) changed(true, false);
            });
            DeviceLayerBank bank = root.device.createLayerBank(LAYERS);
            window.observe(bank.exists(), bank.itemCount(), bank.scrollPosition(), known -> {
                if (known) retire();
                else changed(true, false);
            });
            for (int i = 0; i < LAYERS; i++) layers[i] = new Layer(this, i, (DeviceLayer) bank.getItemAt(i));
        }

        boolean ready() {
            return root.directReady()
                    && root.position >= 0
                    && root.position < root.track.count
                    && Boolean.TRUE.equals(hasLayers)
                    && window.ready();
        }

        void changed(boolean structural, boolean name) {
            for (Layer layer : layers) if (layer != null) layer.changed(structural, name);
        }

        void retire() {
            for (Layer layer : layers) if (layer != null) layer.retire();
        }
    }

    final class Layer {
        final Branch branch;
        final int slot;
        final DeviceLayer proxy;
        final Window window = new Window(CHILDREN);
        final Source[] children = new Source[CHILDREN];
        Boolean exists;
        String id, name;
        Integer channelIndex;
        boolean rebound;

        Layer(Branch branch, int slot, DeviceLayer proxy) {
            this.branch = branch;
            this.slot = slot;
            this.proxy = proxy;
            proxy.exists().addValueObserver((BooleanValueChangedCallback) value -> {
                boolean removed = Boolean.TRUE.equals(exists) && !value;
                boolean change = exists != null && exists != value;
                exists = value;
                if (removed) retire();
                else if (change) changed(true, false);
            });
            proxy.channelId().addValueObserver((StringValueChangedCallback) value -> {
                boolean replaced = canonicalUuid(id) && !id.equals(value);
                boolean change = id != null && !id.equals(value);
                id = (String) value;
                if (replaced) retire();
                else if (change) changed(true, false);
            });
            // The ordinary observer's default zero is not an assigned index.
            // Keep unassignment distinct without weakening observed index-change retirement.
            proxy.channelIndex()
                    .addValueObserver(
                            (IntegerValueChangedCallback) value -> {
                                boolean change = channelIndex != null && channelIndex != value;
                                boolean moved =
                                        change && channelIndex >= 0 && Boolean.TRUE.equals(exists) && canonicalUuid(id);
                                channelIndex = value;
                                if (moved) retire();
                                else if (change) changed(true, false);
                            },
                            -1);
            proxy.name().addValueObserver((StringValueChangedCallback) value -> {
                boolean change = name != null && !name.equals(value);
                name = (String) value;
                if (change) changed(false, true);
            });
            // This bank's children have this layer as their construction-fixed device chain.
            DeviceBank bank = proxy.createDeviceBank(CHILDREN);
            window.observe(bank.exists(), bank.itemCount(), bank.scrollPosition(), known -> {
                if (known) retire();
                else changed(true, false);
            });
            for (int i = 0; i < CHILDREN; i++) {
                Source child = new Source(branch.root.track, i, (Device) bank.getItemAt(i), this);
                children[i] = child;
                all.add(child);
                child.observe();
            }
        }

        boolean ready() {
            return branch.ready()
                    && slot < branch.window.count
                    && Boolean.TRUE.equals(exists)
                    && canonicalUuid(id)
                    && name != null
                    && channelIndex != null
                    && channelIndex >= 0
                    && window.ready();
        }

        void changed(boolean structural, boolean name) {
            for (Source child : children) if (child != null) child.changed(structural, name);
        }

        JsonObject ref() {
            return Refs.chain(branch.root.ref(), id, name);
        }

        void retire() {
            rebound = true;
            for (Source child : children) if (child != null) child.retire();
        }
    }

    /** Grid/Device targets address the zero-scrolled bank; an absent device is target_not_found, not a stale identity. */
    void requireZeroWindow(JsonObject p) {
        int t = window.trackSlot(p.get("trackId").getAsString());
        if (window.devices(t).scrollPosition().get() != 0)
            throw Errors.notApplied(Reason.STALE_CONTEXT, "Device bank is not at the zero-based window.");
        Source source = roots[t][p.get("deviceIndex").getAsInt()];
        if (source.metadataReady()
                && (!Boolean.TRUE.equals(source.exists)
                        || source.position != p.get("deviceIndex").getAsInt()))
            throw Errors.notApplied(Reason.TARGET_NOT_FOUND, "Device is absent at the explicit index.");
        if (source.metadataReady() && source.name.isEmpty())
            throw Errors.notApplied(Reason.NOT_READY, "Device name has not been observed.");
    }

    /** The device a ref names: stale when it is gone or another device is there now, not_ready while unobserved. */
    Source device(JsonObject ref, boolean target) {
        Source source = locate(ref);
        JsonObject current = source.ref();
        if (!Refs.matches(ref, current, target))
            throw Errors.targetChanged("Another device is at this position than the one read.", current);
        return source;
    }

    /** The chain a ref names, located by its layer UUID; its name counts only when the chain itself is the target. */
    Layer chain(JsonObject ref, boolean target) {
        Layer layer = locateChain(ref);
        JsonObject current = layer.ref();
        if (!Refs.matches(ref, current, target)) throw Errors.targetChanged("The chain is not the one read.", current);
        return layer;
    }

    /** The device a ref locates, refused when it is gone or unobserved; its compared fields are the caller's to check. */
    Source locate(JsonObject ref) {
        int index = ref.get("index").getAsInt();
        if (!ref.has("chain")) {
            JsonObject trackRef = ref.getAsJsonObject("track");
            TrackState track = tracks[trackRefs.locate(trackRef)];
            Source source = rootAt(track, index);
            if (!trackRef.get("id").getAsString().equals(track.id))
                throw Errors.notApplied(Reason.NOT_READY, "The track's identity has not been observed yet.");
            if (!Boolean.TRUE.equals(source.exists) || source.position != index)
                throw Errors.targetNotFound(ref, "No device is at this index.");
            if (!source.present()) throw Errors.notApplied(Reason.NOT_READY, "Device name has not been observed.");
            return source;
        }
        Layer layer = locateChain(ref.getAsJsonObject("chain"));
        if (index >= CHILDREN)
            throw Errors.notApplied(
                    Reason.OUTSIDE_WINDOW, "The device is outside the " + CHILDREN + "-device chain window.");
        Source child = layer.children[index];
        if (child.rebound) throw rebound();
        if (Boolean.FALSE.equals(child.exists) || index >= layer.window.count)
            throw Errors.targetNotFound(ref, "No device is at this index of the chain.");
        if (!child.present()) throw Errors.notApplied(Reason.NOT_READY, "Device metadata has not been observed.");
        return child;
    }

    private Layer locateChain(JsonObject ref) {
        JsonObject device = ref.getAsJsonObject("device");
        if (device.has("chain"))
            throw Errors.notApplied(Reason.UNSUPPORTED_SCOPE, "Chains of a device inside a chain are not observed.");
        Source root = device(device, false);
        requireBranch(root.branch);
        Layer found = findLayer(root, ref.get("id").getAsString(), Reason.NOT_READY);
        if (found == null) throw Errors.targetNotFound(ref, "The device has no chain with this id.");
        if (!found.ready())
            throw Errors.notApplied(Reason.NOT_READY, "Layer and child window are not ready at offset zero.");
        return found;
    }

    private Source rootAt(TrackState track, int index) {
        if (track.offset == null)
            throw Errors.notApplied(Reason.NOT_READY, "Root device window has not been observed.");
        int slot = index - track.offset;
        if (slot < 0 || slot >= DEVICES)
            throw Errors.notApplied(Reason.OUTSIDE_WINDOW, "Device is outside the root device window.");
        Source source = roots[track.slot][slot];
        if (!source.metadataReady() || !track.ready())
            throw Errors.notApplied(Reason.NOT_READY, "Device metadata/window has not been observed.");
        return source;
    }

    private static RpcException rebound() {
        return Errors.notApplied(
                Reason.NOT_READY,
                "Nested proxy physically rebound; direct callback provenance is unavailable until extension reload.");
    }

    Source root(JsonObject p) {
        Source source = rootAt(
                tracks[window.trackSlot(p.get("trackId").getAsString())],
                p.get("deviceIndex").getAsInt());
        TrackState track = source.track;
        if (!source.present() || !track.id.equals(p.get("trackId").getAsString()))
            throw Errors.notApplied(Reason.STALE_TARGET, "Parent address/name no longer matches observed context.");
        if (!source.name.equals(p.get("expectedDeviceName").getAsString()))
            throw Errors.notApplied(
                    Reason.STALE_TARGET,
                    "Parent address/name no longer matches observed context: the device at index "
                            + p.get("deviceIndex").getAsInt() + " is named \"" + source.name
                            + "\", not expectedDeviceName.");
        return source;
    }

    /** Capture/final guard are controller-only; native callbacks see only the atomic ticket. */
    RootReadTicket rootRead(JsonObject parent) {
        Source source = root(parent);
        if (source.position >= source.track.count)
            throw Errors.notApplied(Reason.TARGET_NOT_FOUND, "Parent is outside the observed device count.");
        RootReadTicket ticket = new RootReadTicket(source, trackOffset, window.total());
        guardRootRead(ticket);
        return ticket;
    }

    /** A ticket for every observed root device of {@code trackId}, for callers that find a device by identity. */
    List<RootReadTicket> rootReads(String trackId) {
        List<RootReadTicket> out = new ArrayList<>();
        for (Source source : roots[window.trackSlot(trackId)]) {
            if (source == null || !source.metadataReady() || !source.present()) continue;
            JsonObject p = new JsonObject();
            p.addProperty("trackId", trackId);
            p.addProperty("deviceIndex", source.position);
            p.addProperty("expectedDeviceName", source.name);
            out.add(rootRead(p));
        }
        return out;
    }

    void guardRootRead(RootReadTicket ticket) {
        ticket.check();
        Source source = ticket.source;
        TrackState track = source.track;
        if (!source.present()
                || !Objects.equals(track.id, ticket.trackId)
                || !Objects.equals(source.name, ticket.name)
                || source.position != ticket.index
                || trackOffset != ticket.trackOffset
                || track.offset != ticket.deviceOffset
                || track.count != ticket.deviceCount
                || track.position != ticket.trackPosition
                || !track.proxy.exists().get()
                || !ticket.trackId.equals(track.proxy.channelId().get())
                || track.proxy.position().get() != ticket.trackPosition
                || window.offset() != ticket.trackOffset
                || window.total() != ticket.trackCount
                || window.devices(track.slot).scrollPosition().get() != ticket.deviceOffset
                || window.devices(track.slot).itemCount().get() != ticket.deviceCount
                || !source.device.exists().get()
                || source.device.position().get() != ticket.index
                || !ticket.name.equals(source.device.name().get())
                || !ticket.type.equals(source.device.deviceType().get()))
            throw Errors.notApplied(Reason.STALE_CONTEXT, "Observed root device changed before read delivery.");
        ticket.check();
    }

    static final class RootReadTicket {
        final Source source;
        private final AtomicLong epoch;
        private final long generation;
        final Device device;
        final String trackId, name, type;
        final int index, trackOffset, trackCount, deviceOffset, deviceCount, trackPosition;

        RootReadTicket(Source source, int trackOffset, int trackCount) {
            this.source = source;
            epoch = source.rootReadEpoch;
            generation = epoch.get();
            device = source.device;
            trackId = source.track.id;
            name = source.name;
            type = source.type;
            index = source.position;
            this.trackOffset = trackOffset;
            this.trackCount = trackCount;
            deviceOffset = source.track.offset;
            deviceCount = source.track.count;
            trackPosition = source.track.position;
        }

        void check() {
            if (epoch.get() != generation)
                throw Errors.notApplied(
                        Reason.STALE_CONTEXT, "Observed root device changed while its read was queued or running.");
        }

        JsonObject parent() {
            JsonObject result = new JsonObject();
            result.addProperty("trackId", trackId);
            result.addProperty("deviceIndex", index);
            result.addProperty("expectedDeviceName", name);
            return result;
        }
    }

    Source child(JsonObject p) {
        Layer found = layer(p.getAsJsonObject("parent"), p.get("layerId").getAsString());
        int index = p.get("childDeviceIndex").getAsInt();
        if (index >= CHILDREN)
            throw Errors.notApplied(Reason.OUTSIDE_WINDOW, "Child is outside the fixed two-device window.");
        Source child = found.children[index];
        if (child.rebound) throw rebound();
        if (!child.present())
            throw Errors.notApplied(Reason.NOT_READY, "Child metadata or ancestor/window context is not ready.");
        if (!child.name.equals(p.get("expectedChildDeviceName").getAsString())
                || !child.discoveryToken.equals(p.get("discoveryToken").getAsString()))
            throw Errors.notApplied(
                    Reason.STALE_CONTEXT, "Rediscover the layer child after an observed context change.");
        return child;
    }

    /** The observed, ready layer of an explicit root parent; the shared half of child addressing. */
    Layer layer(JsonObject parent, String id) {
        Source root = root(parent);
        requireBranch(root.branch);
        Layer found = findLayer(root, id, Reason.STALE_CONTEXT);
        if (found == null)
            throw Errors.notApplied(Reason.STALE_CONTEXT, "Layer UUID is absent from the observed window.");
        if (!found.ready())
            throw Errors.notApplied(Reason.NOT_READY, "Layer and child window are not ready at offset zero.");
        return found;
    }

    /** The visible layer with this UUID, or null when none has it. */
    private static Layer findLayer(Source root, String id, Reason ambiguous) {
        Layer found = null;
        for (Layer layer : root.branch.layers) {
            if (layer.slot >= root.branch.window.count) continue;
            if (layer.exists == null || (Boolean.TRUE.equals(layer.exists) && !canonicalUuid(layer.id)))
                throw Errors.notApplied(Reason.NOT_READY, "Layer identity window is not fully observed.");
            if (Boolean.TRUE.equals(layer.exists) && id.equals(layer.id)) {
                if (found != null)
                    throw Errors.notApplied(ambiguous, "Layer UUID is ambiguous in the observed window.");
                found = layer;
            }
        }
        return found;
    }

    /** The index a position names in a track's root chain or in a chain; an anchor must be a device of it. */
    int insertionIndex(JsonObject parent, JsonElement position) {
        if ("chain".equals(Refs.kind(parent))) {
            Layer layer = chain(parent, false);
            return Positions.index(position, layer.window.count, anchor -> anchorIn(anchor, s -> s.layer == layer));
        }
        TrackState track = tracks[trackRefs.locate(parent)];
        if (track.count == null) throw Errors.notApplied(Reason.NOT_READY, "The device count is not observed yet.");
        return Positions.index(
                position, track.count, anchor -> anchorIn(anchor, s -> s.layer == null && s.track == track));
    }

    private int anchorIn(JsonObject anchor, java.util.function.Predicate<Source> inParent) {
        Source source = device(anchor, false);
        if (!inParent.test(source)) throw Errors.invalid("The anchor device is not in this parent.");
        return source.position;
    }

    private void requireBranch(Branch branch) {
        if (branch.hasLayers == null)
            throw Errors.notApplied(Reason.NOT_READY, "Parent layer support has not been observed.");
        if (!branch.hasLayers)
            throw Errors.notApplied(Reason.UNSUPPORTED_SCOPE, "Parent does not expose device layers.");
        if (!branch.ready())
            throw Errors.notApplied(Reason.NOT_READY, "Layer window has not been observed at offset zero.");
    }

    JsonObject listLayers(JsonObject p) {
        Source root = root(p.getAsJsonObject("parent"));
        requireBranch(root.branch);
        JsonObject result = new JsonObject();
        result.add("parent", root.parentJson());
        result.addProperty("scope", "one_layer_hop");
        result.add("window", root.branch.window.json());
        JsonArray layers = new JsonArray();
        for (Layer layer : root.branch.layers) {
            if (layer.slot >= root.branch.window.count) continue;
            JsonObject item = new JsonObject();
            item.addProperty("index", layer.slot);
            item.addProperty("layerId", layer.id);
            item.addProperty("name", layer.name);
            item.addProperty("channelIndex", layer.channelIndex);
            item.addProperty(
                    "readiness", Boolean.FALSE.equals(layer.exists) ? "absent" : layer.ready() ? "ready" : "not_ready");
            item.add("window", layer.window.json());
            JsonArray children = new JsonArray();
            boolean unique = canonicalUuid(layer.id);
            for (Layer other : root.branch.layers) {
                if (other.slot >= root.branch.window.count) continue;
                if (other.exists == null
                        || (Boolean.TRUE.equals(other.exists) && !canonicalUuid(other.id))
                        || (other != layer && Boolean.TRUE.equals(other.exists) && Objects.equals(layer.id, other.id)))
                    unique = false;
            }
            for (Source child : layer.children) {
                if (layer.window.count != null && child.slot >= layer.window.count) continue;
                JsonObject descriptor = new JsonObject();
                descriptor.addProperty("index", child.slot);
                descriptor.addProperty("childDeviceIndex", child.position);
                descriptor.addProperty("name", child.name);
                descriptor.addProperty("type", child.type);
                boolean ready = unique && child.directReady() && child.slot < layer.window.count;
                descriptor.addProperty(
                        "readiness", Boolean.FALSE.equals(child.exists) ? "absent" : ready ? "ready" : "not_ready");
                descriptor.addProperty("directContextAvailable", ready);
                if (ready) descriptor.addProperty("discoveryToken", child.discoveryToken);
                if (child.rebound)
                    descriptor.addProperty(
                            "reason", "physical_rebind_callback_provenance_unavailable_until_extension_reload");
                children.add(descriptor);
            }
            item.add("devices", children);
            layers.add(item);
        }
        result.add("layers", layers);
        result.addProperty("identityScope", "observed_bank_proxy_context_not_persistent_device_identity");
        result.addProperty(
                "identityCaveat",
                "Unobserved same-metadata physical replacement cannot be detected by these public proxies.");
        result.addProperty(
                "directParameterReadiness", "separate_from_discovery_requires_actual_id_and_value_callbacks");
        return result;
    }

    static boolean canonicalUuid(String value) {
        if (value == null) return false;
        try {
            return UUID.fromString(value).toString().equalsIgnoreCase(value);
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    static void validateParent(JsonObject p) {
        Params.fields(p, Set.of("trackId", "deviceIndex", "expectedDeviceName"), Set.of());
        if (!canonicalUuid(Params.string(p, "trackId")))
            throw Errors.notApplied(Reason.INVALID_PARAMS, "trackId must be a canonical UUID.");
        Params.integer(p, "deviceIndex", 0, DEVICES - 1);
        Params.string(p, "expectedDeviceName");
    }

    static void validateLayer(JsonObject p, boolean write, boolean discovery) {
        Params.fields(
                p,
                discovery
                        ? Set.of("parent")
                        : write
                                ? Set.of(
                                        "parent",
                                        "layerId",
                                        "childDeviceIndex",
                                        "expectedChildDeviceName",
                                        "discoveryToken",
                                        "contextToken",
                                        "parameterId",
                                        "value")
                                : Set.of(
                                        "parent",
                                        "layerId",
                                        "childDeviceIndex",
                                        "expectedChildDeviceName",
                                        "discoveryToken"),
                Set.of());
        if (!p.get("parent").isJsonObject())
            throw Errors.notApplied(Reason.INVALID_PARAMS, "parent must be an object.");
        validateParent(p.getAsJsonObject("parent"));
        if (discovery) return;
        if (!canonicalUuid(Params.string(p, "layerId")))
            throw Errors.notApplied(Reason.INVALID_PARAMS, "layerId must be a canonical UUID.");
        Params.integer(p, "childDeviceIndex", 0, CHILDREN - 1);
        Params.string(p, "expectedChildDeviceName");
        Params.string(p, "discoveryToken");
        if (write) {
            Params.string(p, "contextToken");
            Params.string(p, "parameterId");
            Params.number(p, "value", 0, 1);
        }
    }

    /** The native root device behind a device proxy and its owning track, corroborated within one document callback. */
    static final class Native {
        enum Kind {
            DEVICE,
            TRACK,
            ALIASED_TRACK,
            CHANNEL_ALIAS
        }

        enum Field {
            TARGET,
            OWNER_TRACK,
            TRACK_ALIAS,
            TRACK_UUID,
            ALIAS_UUID
        }

        interface Access {
            boolean is(Kind kind, Object object);

            Object read(Field field, Object object);

            void documentThread();

            void requireModelThread(Object object);
        }

        private final Access a;

        Native(Access access) {
            a = access;
        }

        /** A chain device has no native target here (unsupported_scope); the owner is the Source's observed track. */
        Capture capture(Source source, Runnable check) {
            a.documentThread();
            check.run();
            if (source.layer != null)
                throw Errors.notApplied(Reason.UNSUPPORTED_SCOPE, "A device inside a chain has no native target here.");
            UUID expected = trackUuid(source);
            Object device = a.read(Field.TARGET, source.device);
            if (device == null)
                throw Errors.notApplied(
                        Reason.INVALID_TARGET, "The device has no native target; read the device again.");
            if (!a.is(Kind.DEVICE, device))
                throw Errors.notApplied(Reason.UNSUPPORTED_SCOPE, "The device is not a mapped root device.");
            a.requireModelThread(device);
            Object owner = owningTrack(device), identity = trackIdentity(owner);
            requireTrackUuid(owner, identity, expected);
            check.run();
            return new Capture(source, device, owner, identity, expected);
        }

        void guard(Capture before, Runnable check) {
            a.documentThread();
            check.run();
            if (!before.trackId.equals(trackUuid(before.source)))
                throw Errors.notApplied(Reason.STALE_CONTEXT, "The device's track changed during the read.");
            if (a.read(Field.TARGET, before.source.device) != before.device)
                throw Errors.notApplied(Reason.STALE_CONTEXT, "The device changed during the read.");
            a.requireModelThread(before.device);
            Object owner = owningTrack(before.device), identity = trackIdentity(owner);
            requireTrackUuid(owner, identity, before.trackId);
            if (owner != before.owner || identity != before.identity)
                throw Errors.notApplied(
                        Reason.STALE_CONTEXT, "The device's track or its channel alias changed during the read.");
            check.run();
        }

        private static UUID trackUuid(Source source) {
            String id = source.track.id;
            if (!canonicalUuid(id))
                throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "The device's track UUID is not observed.");
            return UUID.fromString(id);
        }

        private Object owningTrack(Object device) {
            Object owner = a.read(Field.OWNER_TRACK, device);
            if (!a.is(Kind.TRACK, owner))
                throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "The device has no mapped native track.");
            a.requireModelThread(owner);
            return owner;
        }

        private Object trackIdentity(Object owner) {
            if (!a.is(Kind.ALIASED_TRACK, owner)) return owner;
            Object alias = a.read(Field.TRACK_ALIAS, owner);
            if (!a.is(Kind.CHANNEL_ALIAS, alias))
                throw Errors.notApplied(
                        Reason.NATIVE_UNAVAILABLE, "The device's track channel alias is missing or unsupported.");
            a.requireModelThread(alias);
            return alias;
        }

        private void requireTrackUuid(Object owner, Object identity, UUID expected) {
            Object value = a.read(a.is(Kind.ALIASED_TRACK, owner) ? Field.ALIAS_UUID : Field.TRACK_UUID, identity);
            if (!(value instanceof UUID))
                throw Errors.notApplied(
                        Reason.NATIVE_UNAVAILABLE, "The device's native track UUID is missing or unsupported.");
            if (!expected.equals(value))
                throw Errors.notApplied(Reason.STALE_CONTEXT, "The device belongs to another track than the one read.");
        }

        static final class Capture {
            final Source source;
            final Object device, owner, identity;
            final UUID trackId;

            Capture(Source source, Object device, Object owner, Object identity, UUID trackId) {
                this.source = source;
                this.device = device;
                this.owner = owner;
                this.identity = identity;
                this.trackId = trackId;
            }
        }
    }
}
