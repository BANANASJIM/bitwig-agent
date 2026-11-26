package dev.bitwigagent;

import java.util.UUID;

/** Native root device and owning-channel corroboration within one document callback. */
final class NativeDeviceTarget {
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

    NativeDeviceTarget(Access access) {
        a = access;
    }

    Capture capture(Object proxy, UUID expected, Runnable check) {
        a.documentThread();
        check.run();
        if (expected == null)
            throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "Expected owning-track UUID is required.");
        Object device = a.read(Field.TARGET, proxy);
        if (device == null)
            throw Errors.notApplied(
                    Reason.INVALID_TARGET, "Explicit device proxy has no native target; refresh devices.get.");
        if (!a.is(Kind.DEVICE, device))
            throw Errors.notApplied(Reason.UNSUPPORTED_SCOPE, "Explicit native target is not a mapped root device.");
        a.requireModelThread(device);
        Object owner = owningTrack(device), identity = trackIdentity(owner);
        requireTrackUuid(owner, identity, expected);
        check.run();
        return new Capture(device, owner, identity, expected);
    }

    void guard(Capture before, Object proxy, Runnable check) {
        a.documentThread();
        check.run();
        if (a.read(Field.TARGET, proxy) != before.device)
            throw Errors.notApplied(Reason.STALE_CONTEXT, "Explicit native device changed during the read.");
        a.requireModelThread(before.device);
        Object owner = owningTrack(before.device), identity = trackIdentity(owner);
        requireTrackUuid(owner, identity, before.trackId);
        if (owner != before.owner || identity != before.identity)
            throw Errors.notApplied(
                    Reason.STALE_CONTEXT, "Explicit native owning track or channel alias changed during the read.");
        check.run();
    }

    private Object owningTrack(Object device) {
        Object owner = a.read(Field.OWNER_TRACK, device);
        if (!a.is(Kind.TRACK, owner))
            throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "Native device has no mapped owning-track ancestor.");
        a.requireModelThread(owner);
        return owner;
    }

    private Object trackIdentity(Object owner) {
        if (!a.is(Kind.ALIASED_TRACK, owner)) return owner;
        Object alias = a.read(Field.TRACK_ALIAS, owner);
        if (!a.is(Kind.CHANNEL_ALIAS, alias))
            throw Errors.notApplied(
                    Reason.NATIVE_UNAVAILABLE, "Native owning-track channel alias is missing or unsupported.");
        a.requireModelThread(alias);
        return alias;
    }

    private void requireTrackUuid(Object owner, Object identity, UUID expected) {
        Object value = a.read(a.is(Kind.ALIASED_TRACK, owner) ? Field.ALIAS_UUID : Field.TRACK_UUID, identity);
        if (!(value instanceof UUID))
            throw Errors.notApplied(
                    Reason.NATIVE_UNAVAILABLE, "Native owning-track channel UUID is missing or unsupported.");
        if (!expected.equals(value))
            throw Errors.notApplied(
                    Reason.STALE_CONTEXT, "Native device owning-track channel UUID differs from requested trackId.");
    }

    static final class Capture {
        final Object device, owner, identity;
        final UUID trackId;

        Capture(Object device, Object owner, Object identity, UUID trackId) {
            this.device = device;
            this.owner = owner;
            this.identity = identity;
            this.trackId = trackId;
        }
    }
}
