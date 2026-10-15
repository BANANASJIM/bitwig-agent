package dev.bitwigagent;

import com.bitwig.extension.callback.*;
import com.bitwig.extension.controller.api.*;
import com.google.gson.*;
import java.util.*;
import static dev.bitwigagent.HostServices.*;

/** Fixed observed device addresses. Tokens describe continuity, never persistent device identity. */
final class DeviceSources {
    static final int LAYERS = 2, CHILDREN = 2;
    final Source[][] roots = new Source[TRACKS][DEVICES];
    final List<Source> all = new ArrayList<>(TRACKS * DEVICES * (1 + LAYERS * CHILDREN));
    private final HostServices s;
    private final TrackState[] tracks = new TrackState[TRACKS];
    private Integer trackOffset;

    DeviceSources(HostServices services) {
        s = services;
        s.tracks.scrollPosition().addValueObserver((IntegerValueChangedCallback) value -> {
            boolean changed = trackOffset != null && trackOffset != value;
            trackOffset = value;
            if (changed) for (TrackState track : tracks) if (track != null) track.retire();
        });
        for (int t = 0; t < TRACKS; t++) {
            TrackState track = new TrackState(t, (Track) s.tracks.getItemAt(t));
            tracks[t] = track;
            track.observe();
            for (int d = 0; d < DEVICES; d++) {
                Source root = new Source(track, d, (Device) s.devices[t].getItemAt(d), null);
                roots[t][d] = root;
                all.add(root);
                root.observe();
                root.branch = new Branch(root);
            }
        }
    }

    interface Listener { void changed(boolean structural, boolean name); }
    interface WindowChange { void changed(boolean knownDiscontinuity); }

    final class TrackState {
        final int slot;
        final Track proxy;
        Boolean exists;
        String id;
        Integer position, offset, count;
        TrackState(int slot, Track proxy) { this.slot = slot; this.proxy = proxy; }
        void observe() {
            proxy.exists().addValueObserver((BooleanValueChangedCallback) value -> {
                boolean removed = Boolean.TRUE.equals(exists) && !value;
                boolean change = exists != null && exists != value; exists = value;
                if (removed) retire(); else changed(change);
            });
            proxy.channelId().addValueObserver((StringValueChangedCallback) value -> {
                boolean replaced = canonicalUuid(id) && !id.equals(value);
                boolean change = id != null && !id.equals(value); id = (String) value;
                if (replaced) retire(); else changed(change);
            });
            proxy.position().addValueObserver((IntegerValueChangedCallback) value -> {
                boolean change = position != null && position != value;
                boolean moved = change && position >= 0 && Boolean.TRUE.equals(exists); position = value;
                if (moved) retire(); else changed(change);
            });
            s.devices[slot].scrollPosition().addValueObserver((IntegerValueChangedCallback) value -> {
                boolean change = offset != null && offset != value; offset = value;
                if (change && count != null && count > 0) retire(); else changed(change);
            });
            s.devices[slot].itemCount().addValueObserver((IntegerValueChangedCallback) value -> {
                boolean change = count != null && count != value;
                Integer previousCount = count; count = value;
                if (change) for (Source source : roots[slot]) {
                    if (source == null || source.branch == null) continue;
                    boolean previouslyInRange = previousCount != null && offset != null && offset >= 0
                            && (long) offset + source.slot < previousCount;
                    if (previouslyInRange) source.branch.retire(); else source.branch.changed(true, false);
                }
                changed(false);
            });
        }
        // The initial unassigned position must not establish descendants before a real track address.
        boolean ready() { return Boolean.TRUE.equals(exists) && canonicalUuid(id) && position != null && position >= 0
                && offset != null && count != null && count >= 0 && trackOffset != null && s.tracksObserved; }
        void retire() { for (Source source : roots[slot]) if (source != null) source.retire(); }
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
        boolean established, rebound;
        // Legacy root direct callbacks can precede Value callbacks. This getter snapshot
        // preserves that root-only bank-proxy policy without marking metadata observed.
        String directTrackId, directName;
        int directPosition;
        String discoveryToken;
        Source(TrackState track, int slot, Device device, Layer layer) {
            this.track = track; this.slot = slot; this.device = device; this.layer = layer;
            if (layer != null) {
                rebound = layer.rebound;
                if (!rebound) discoveryToken = UUID.randomUUID().toString();
            }
        }
        void observe() {
            device.exists().addValueObserver((BooleanValueChangedCallback) value -> {
                boolean removed = Boolean.TRUE.equals(exists) && !value;
                boolean change = exists != null && exists != value; exists = value;
                if (removed) retire(); else changed(change, false);
            });
            device.position().addValueObserver((IntegerValueChangedCallback) value -> {
                boolean change = position != null && position != value;
                boolean moved = change && position >= 0 && Boolean.TRUE.equals(exists); position = value;
                if (moved) retire(); else changed(change, false);
            });
            device.deviceType().addValueObserver((EnumValueChangedCallback) value -> {
                String observed = device.deviceType().get();
                boolean change = type != null && !type.equals(observed);
                boolean replaced = change && !type.isEmpty() && Boolean.TRUE.equals(exists); type = observed;
                if (replaced) retire(); else changed(change, false);
            });
            device.name().addValueObserver((StringValueChangedCallback) value -> {
                boolean change = name != null && !name.equals(value); name = (String) value; nameSequence++; changed(false, change);
            });
        }
        boolean metadataReady() { return exists != null && name != null && position != null && type != null; }
        boolean present() {
            boolean ready = metadataReady() && Boolean.TRUE.equals(exists) && !name.isEmpty() && !type.isEmpty()
                    && track.ready() && (layer == null ? position == track.offset + slot
                    : layer.ready() && position == slot && slot < layer.window.count);
            if (ready) established = true;
            return ready;
        }
        boolean directReady() {
            if (layer != null) return present() && !rebound;
            reconcileRootDirectContext();
            return present() && track.proxy.exists().get() && device.exists().get()
                    && Objects.equals(track.id, track.proxy.channelId().get())
                    && Objects.equals(name, device.name().get()) && position == device.position().get();
        }
        boolean acceptDirectObservation() {
            if (layer != null) return directReady();
            reconcileRootDirectContext();
            if (!track.proxy.exists().get() || !device.exists().get()) return false;
            if (directTrackId == null) {
                directTrackId = track.proxy.channelId().get(); directName = device.name().get();
                directPosition = device.position().get();
            }
            return true;
        }
        private boolean rootDirectMoved() {
            return !track.proxy.exists().get() || !device.exists().get()
                    || !Objects.equals(directTrackId, track.proxy.channelId().get())
                    || directPosition != device.position().get();
        }
        private void reconcileRootDirectContext() {
            if (directTrackId != null && (rootDirectMoved() || !Objects.equals(directName, device.name().get())))
                changed(false, false);
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
                if (structural) { directTrackId = null; directName = null; }
                else if (renamed) directName = device.name().get();
            }
            boolean retired = structural && (established || hadDirectContext);
            if (retired) { epoch++; if (layer != null) rebound = true; }
            if (retired || renamed) {
                revision++;
                if (layer != null) discoveryToken = rebound ? null : UUID.randomUUID().toString();
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
            epoch++; revision++; discoveryToken = null;
            for (Listener listener : listeners) listener.changed(true, false);
        }
        JsonObject parentJson() {
            JsonObject p = new JsonObject(); p.addProperty("trackId", track.id);
            p.addProperty("deviceIndex", position); p.addProperty("expectedDeviceName", name); return p;
        }
        JsonObject address() {
            if (layer == null) return parentJson();
            JsonObject result = new JsonObject(); result.add("parent", layer.branch.root.parentJson());
            result.addProperty("layerId", layer.id); result.addProperty("childDeviceIndex", position);
            result.addProperty("expectedChildDeviceName", name); result.addProperty("discoveryToken", discoveryToken);
            return result;
        }
    }

    final class Window {
        final int capacity;
        Boolean exists;
        Integer count, offset;
        Window(int capacity) { this.capacity = capacity; }
        void observe(BooleanValue existence, IntegerValue size, IntegerValue scroll, WindowChange changed) {
            existence.addValueObserver((BooleanValueChangedCallback) value -> {
                boolean change = exists != null && exists != value, removed = Boolean.TRUE.equals(exists) && !value;
                exists = value; if (change) changed.changed(removed);
            });
            size.addValueObserver((IntegerValueChangedCallback) value -> {
                boolean change = count != null && count != value, populated = count != null && count > 0;
                count = value; if (change) changed.changed(populated);
            });
            scroll.addValueObserver((IntegerValueChangedCallback) value -> {
                boolean change = offset != null && offset != value;
                offset = value; if (change) changed.changed(count != null && count > 0);
            });
        }
        boolean ready() { return Boolean.TRUE.equals(exists) && count != null && count >= 0 && offset != null && offset == 0; }
        JsonObject json() {
            JsonObject result = new JsonObject(); result.addProperty("offset", offset); result.addProperty("capacity", capacity);
            result.addProperty("total", count); result.addProperty("exists", exists);
            result.addProperty("complete", ready() && count <= capacity); result.addProperty("readiness", ready() ? "ready" : "not_ready");
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
                boolean change = hasLayers != null && hasLayers != value; hasLayers = value;
                if (removed) retire(); else if (change) changed(true, false);
            });
            DeviceLayerBank bank = root.device.createLayerBank(LAYERS);
            window.observe(bank.exists(), bank.itemCount(), bank.scrollPosition(),
                    known -> { if (known) retire(); else changed(true, false); });
            for (int i = 0; i < LAYERS; i++) layers[i] = new Layer(this, i, (DeviceLayer) bank.getItemAt(i));
        }
        boolean ready() { return root.directReady() && root.position >= 0 && root.position < root.track.count
                && Boolean.TRUE.equals(hasLayers) && window.ready(); }
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
            this.branch = branch; this.slot = slot; this.proxy = proxy;
            proxy.exists().addValueObserver((BooleanValueChangedCallback) value -> {
                boolean removed = Boolean.TRUE.equals(exists) && !value;
                boolean change = exists != null && exists != value; exists = value;
                if (removed) retire(); else if (change) changed(true, false);
            });
            proxy.channelId().addValueObserver((StringValueChangedCallback) value -> {
                boolean replaced = canonicalUuid(id) && !id.equals(value);
                boolean change = id != null && !id.equals(value); id = (String) value;
                if (replaced) retire(); else if (change) changed(true, false);
            });
            proxy.channelIndex().addValueObserver((IntegerValueChangedCallback) value -> {
                boolean change = channelIndex != null && channelIndex != value;
                boolean moved = change && channelIndex >= 0 && Boolean.TRUE.equals(exists) && canonicalUuid(id);
                channelIndex = value;
                if (moved) retire(); else if (change) changed(true, false);
            });
            proxy.name().addValueObserver((StringValueChangedCallback) value -> {
                boolean change = name != null && !name.equals(value); name = (String) value; if (change) changed(false, true);
            });
            // This bank's children have this layer as their construction-fixed device chain.
            DeviceBank bank = proxy.createDeviceBank(CHILDREN);
            window.observe(bank.exists(), bank.itemCount(), bank.scrollPosition(),
                    known -> { if (known) retire(); else changed(true, false); });
            for (int i = 0; i < CHILDREN; i++) {
                Source child = new Source(branch.root.track, i, (Device) bank.getItemAt(i), this);
                children[i] = child; all.add(child); child.observe();
            }
        }
        boolean ready() { return branch.ready() && slot < branch.window.count && Boolean.TRUE.equals(exists)
                && canonicalUuid(id) && name != null && channelIndex != null && channelIndex >= 0 && window.ready(); }
        void changed(boolean structural, boolean name) {
            for (Source child : children) if (child != null) child.changed(structural, name);
        }
        void retire() {
            rebound = true;
            for (Source child : children) if (child != null) child.retire();
        }
    }

    Source root(JsonObject p) {
        int t = s.trackSlot(p.get("trackId").getAsString());
        int index = p.get("deviceIndex").getAsInt();
        TrackState track = tracks[t];
        if (track.offset == null) throw failure("not_ready", "Root device window has not been observed.");
        int slot = index - track.offset;
        if (slot < 0 || slot >= DEVICES) throw failure("outside_window", "Parent is outside the root device window.");
        Source source = roots[t][slot];
        if (!source.metadataReady() || !track.ready()) throw failure("not_ready", "Parent metadata/window has not been observed.");
        if (!source.present() || !track.id.equals(p.get("trackId").getAsString())
                || !source.name.equals(p.get("expectedDeviceName").getAsString()))
            throw failure("stale_target", "Parent address/name no longer matches observed context.");
        return source;
    }

    Source child(JsonObject p) {
        Source root = root(p.getAsJsonObject("parent"));
        requireBranch(root.branch);
        String id = p.get("layerId").getAsString();
        Layer found = null;
        for (Layer layer : root.branch.layers) {
            if (layer.slot >= root.branch.window.count) continue;
            if (layer.exists == null || (Boolean.TRUE.equals(layer.exists) && !canonicalUuid(layer.id)))
                throw failure("not_ready", "Layer identity window is not fully observed.");
            if (Boolean.TRUE.equals(layer.exists) && id.equals(layer.id)) {
                if (found != null) throw failure("stale_context", "Layer UUID is ambiguous in the observed window.");
                found = layer;
            }
        }
        if (found == null) throw failure("stale_context", "Layer UUID is absent from the observed window.");
        if (!found.ready()) throw failure("not_ready", "Layer and child window are not ready at offset zero.");
        int index = p.get("childDeviceIndex").getAsInt();
        if (index >= CHILDREN) throw failure("outside_window", "Child is outside the fixed two-device window.");
        Source child = found.children[index];
        if (child.rebound) throw failure("not_ready", "Nested proxy physically rebound; direct callback provenance is unavailable until extension reload.");
        if (!child.present()) throw failure("not_ready", "Child metadata or ancestor/window context is not ready.");
        if (!child.name.equals(p.get("expectedChildDeviceName").getAsString())
                || !child.discoveryToken.equals(p.get("discoveryToken").getAsString()))
            throw failure("stale_context", "Rediscover the layer child after an observed context change.");
        return child;
    }

    private void requireBranch(Branch branch) {
        if (branch.hasLayers == null) throw failure("not_ready", "Parent layer support has not been observed.");
        if (!branch.hasLayers) throw failure("unsupported_scope", "Parent does not expose device layers.");
        if (!branch.ready()) throw failure("not_ready", "Layer window has not been observed at offset zero.");
    }

    JsonObject listLayers(JsonObject p) {
        Source root = root(p.getAsJsonObject("parent")); requireBranch(root.branch);
        JsonObject result = observed(); result.add("parent", root.parentJson()); result.addProperty("scope", "one_layer_hop");
        result.add("window", root.branch.window.json());
        JsonArray layers = new JsonArray();
        for (Layer layer : root.branch.layers) {
            if (layer.slot >= root.branch.window.count) continue;
            JsonObject item = new JsonObject(); item.addProperty("index", layer.slot); item.addProperty("layerId", layer.id);
            item.addProperty("name", layer.name); item.addProperty("channelIndex", layer.channelIndex);
            item.addProperty("readiness", Boolean.FALSE.equals(layer.exists) ? "absent" : layer.ready() ? "ready" : "not_ready");
            item.add("window", layer.window.json()); JsonArray children = new JsonArray();
            boolean unique = canonicalUuid(layer.id);
            for (Layer other : root.branch.layers) {
                if (other.slot >= root.branch.window.count) continue;
                if (other.exists == null || (Boolean.TRUE.equals(other.exists) && !canonicalUuid(other.id))
                        || (other != layer && Boolean.TRUE.equals(other.exists) && Objects.equals(layer.id, other.id))) unique = false;
            }
            for (Source child : layer.children) {
                if (layer.window.count != null && child.slot >= layer.window.count) continue;
                JsonObject descriptor = new JsonObject(); descriptor.addProperty("index", child.slot);
                descriptor.addProperty("childDeviceIndex", child.position); descriptor.addProperty("name", child.name);
                descriptor.addProperty("type", child.type);
                boolean ready = unique && child.directReady() && child.slot < layer.window.count;
                descriptor.addProperty("readiness", Boolean.FALSE.equals(child.exists) ? "absent" : ready ? "ready" : "not_ready");
                descriptor.addProperty("directContextAvailable", ready);
                if (ready) descriptor.addProperty("discoveryToken", child.discoveryToken);
                if (child.rebound) descriptor.addProperty("reason", "physical_rebind_callback_provenance_unavailable_until_extension_reload");
                children.add(descriptor);
            }
            item.add("devices", children); layers.add(item);
        }
        result.add("layers", layers);
        result.addProperty("identityScope", "observed_bank_proxy_context_not_persistent_device_identity");
        result.addProperty("identityCaveat", "Unobserved same-metadata physical replacement cannot be detected by these public proxies.");
        result.addProperty("directParameterReadiness", "separate_from_discovery_requires_actual_id_and_value_callbacks");
        return result;
    }

    static boolean canonicalUuid(String value) {
        if (value == null) return false;
        try { return UUID.fromString(value).toString().equalsIgnoreCase(value); } catch (IllegalArgumentException error) { return false; }
    }
    static void validateParent(JsonObject p) {
        Params.fields(p, Set.of("trackId", "deviceIndex", "expectedDeviceName"), Set.of());
        if (!canonicalUuid(Params.string(p, "trackId"))) throw failure("invalid_params", "trackId must be a canonical UUID.");
        Params.integer(p, "deviceIndex", 0, DEVICES - 1); Params.string(p, "expectedDeviceName");
    }
    static void validateLayer(JsonObject p, boolean write, boolean discovery) {
        Params.fields(p, discovery ? Set.of("parent") : write
                ? Set.of("parent", "layerId", "childDeviceIndex", "expectedChildDeviceName", "discoveryToken", "contextToken", "parameterId", "value")
                : Set.of("parent", "layerId", "childDeviceIndex", "expectedChildDeviceName", "discoveryToken"), Set.of());
        if (!p.get("parent").isJsonObject()) throw failure("invalid_params", "parent must be an object.");
        validateParent(p.getAsJsonObject("parent"));
        if (discovery) return;
        if (!canonicalUuid(Params.string(p, "layerId"))) throw failure("invalid_params", "layerId must be a canonical UUID.");
        Params.integer(p, "childDeviceIndex", 0, CHILDREN - 1);
        Params.string(p, "expectedChildDeviceName"); Params.string(p, "discoveryToken");
        if (write) { Params.string(p, "contextToken"); Params.string(p, "parameterId"); Params.number(p, "value", 0, 1); }
    }
}
