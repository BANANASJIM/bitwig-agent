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
 * Resolves root device refs on fixed observed device addresses and corroborates a root device's native owner. Devices
 * inside chains are not observed here: {@link NativeChains} walks to them natively on every request.
 */
final class DeviceSources {
    final Source[][] roots = new Source[TRACKS][DEVICES];
    final List<Source> all = new ArrayList<>(TRACKS * DEVICES);
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
                Source root = new Source(track, d, (Device) window.devices(t).getItemAt(d));
                roots[t][d] = root;
                all.add(root);
                root.observe();
            }
        }
    }

    interface Listener {
        void changed(boolean structural, boolean name);
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
                count = value;
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
        final List<Listener> listeners = new ArrayList<>(2);
        Boolean exists;
        String name, type;
        Integer position;
        long epoch, revision, nameSequence;
        final AtomicLong rootReadEpoch = new AtomicLong();
        boolean established;
        // Legacy root direct callbacks can precede Value callbacks. This getter snapshot
        // preserves that root-only bank-proxy policy without marking metadata observed.
        String directTrackId, directName;
        int directPosition;

        Source(TrackState track, int slot, Device device) {
            this.track = track;
            this.slot = slot;
            this.device = device;
            listeners.add((structural, renamed) -> rootReadEpoch.incrementAndGet());
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
                    && position == track.offset + slot;
            if (ready) established = true;
            return ready;
        }

        boolean directReady() {
            reconcileRootDirectContext();
            return present()
                    && track.proxy.exists().get()
                    && device.exists().get()
                    && Objects.equals(track.id, track.proxy.channelId().get())
                    && Objects.equals(name, device.name().get())
                    && position == device.position().get();
        }

        boolean acceptDirectObservation() {
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
            // Initial absence/default metadata is not a physical replacement.
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
            if (retired) epoch++;
            if (retired || renamed) revision++;
            if (present()) established = true;
            for (Listener listener : listeners) listener.changed(retired, renamed);
        }

        void retire() {
            changed(true, false);
        }

        JsonObject ref() {
            return Refs.device(trackRefs.ref(track.slot), position, name);
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

    /**
     * The root device a ref locates, refused when it is gone or unobserved; its compared fields are the caller's to
     * check. A device inside a chain is {@link NativeChains}' to resolve; a method that does not support one gets
     * unsupported_scope here.
     */
    Source locate(JsonObject ref) {
        if (NativeChains.nested(ref))
            throw Errors.notApplied(Reason.UNSUPPORTED_SCOPE, "This method does not support a device inside a chain.");
        int index = ref.get("index").getAsInt();
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

    /** The read ticket of a resolved root device. */
    RootReadTicket rootRead(Source source) {
        if (source.position >= source.track.count)
            throw Errors.targetNotFound(source.ref(), "The device is outside the observed device count.");
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
            throw Errors.notApplied(Reason.TARGET_CHANGED, "Observed root device changed before read delivery.");
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
                        Reason.TARGET_CHANGED, "Observed root device changed while its read was queued or running.");
        }

        JsonObject parent() {
            JsonObject result = new JsonObject();
            result.addProperty("trackId", trackId);
            result.addProperty("deviceIndex", index);
            result.addProperty("expectedDeviceName", name);
            return result;
        }
    }

    /** The index a position names in a track's root chain; an anchor must be a root device of that track. */
    int insertionIndex(JsonObject trackRef, JsonElement position) {
        TrackState track = tracks[trackRefs.locate(trackRef)];
        if (track.count == null) throw Errors.notApplied(Reason.NOT_READY, "The device count is not observed yet.");
        return Positions.index(position, track.count, anchor -> {
            if (NativeChains.nested(anchor)) throw Errors.invalid("The anchor device is not in this parent.");
            Source source = device(anchor, false);
            if (source.track != track) throw Errors.invalid("The anchor device is not in this parent.");
            return source.position;
        });
    }

    static boolean canonicalUuid(String value) {
        if (value == null) return false;
        try {
            return UUID.fromString(value).toString().equalsIgnoreCase(value);
        } catch (IllegalArgumentException error) {
            return false;
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

        /** The owner is the Source's observed track. */
        Capture capture(Source source, Runnable check) {
            a.documentThread();
            check.run();
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
                throw Errors.notApplied(Reason.TARGET_CHANGED, "The device's track changed during the read.");
            if (a.read(Field.TARGET, before.source.device) != before.device)
                throw Errors.notApplied(Reason.TARGET_CHANGED, "The device changed during the read.");
            a.requireModelThread(before.device);
            Object owner = owningTrack(before.device), identity = trackIdentity(owner);
            requireTrackUuid(owner, identity, before.trackId);
            if (owner != before.owner || identity != before.identity)
                throw Errors.notApplied(
                        Reason.TARGET_CHANGED, "The device's track or its channel alias changed during the read.");
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
                throw Errors.notApplied(
                        Reason.TARGET_CHANGED, "The device belongs to another track than the one read.");
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
