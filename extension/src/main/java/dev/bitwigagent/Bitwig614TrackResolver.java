package dev.bitwigagent;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.StringJoiner;
import java.util.UUID;

/**
 * The one owner of the 6.1.4 native track model descriptors: it resolves a public track proxy or a launcher slot to
 * its native track and checks attachment, kind and public identity with one wording for every caller.
 */
final class Bitwig614TrackResolver {
    /** Concrete 6.1.4 tracks are mixer channels (xJo), groups (ruS, kWY) and the transport track (SEJ). */
    enum Kind {
        CHANNEL("audio/instrument/hybrid/effect/master"),
        GROUP("group"),
        OTHER("transport");

        final String label;

        Kind(String label) {
            this.label = label;
        }
    }

    private final Bitwig614Runtime.Access a;
    private final Class<?> track, channel, group, alias;
    private final Method target, uuid, aliasOf, aliasUuid, parent, owner, slotTrack;

    Bitwig614TrackResolver(Bitwig614Runtime.Access a) throws ReflectiveOperationException {
        this.a = a;
        String iface = "com.bitwig.flt.document.core.iface.";
        Class<?> identity = a.type(iface + "RYd");
        track = a.type(iface + "WSA");
        channel = a.type("ynF");
        group = a.type(iface + "kWY");
        alias = a.type("iKU");
        for (Class<?>[] pair : new Class<?>[][] {{track, identity}, {channel, track}, {group, track}})
            if (!pair[1].isAssignableFrom(pair[0]))
                throw new NoSuchMethodException(
                        "Inspected ancestry differs: " + pair[0].getName() + " is not a " + pair[1].getName());
        target = a.exactMethod(
                a.type("com.bitwig.flt.control_surface.proxy.ControlSurfaceProxy"),
                "getDeepestTarget",
                Object.class,
                false);
        uuid = a.exactMethod(identity, "kFU", UUID.class, false);
        aliasOf = a.exactMethod(group, "TD2", alias, false);
        aliasUuid = a.exactMethod(alias, "kFU", UUID.class, false);
        parent = a.exactMethod(identity, "QNk", group, false);
        owner = a.exactMethod(a.type("com.bitwig.ramona.core.AVL"), "IwV", Object.class, false, Class.class);
        slotTrack = a.exactMethod(a.type("PAJ"), "Z1C", track, false);
    }

    /** The native track behind a bank track proxy, required to be of an allowed kind and to carry trackId. */
    Object resolve(Object proxy, String trackId, Kind... allowed) {
        return require(target(proxy), trackId, allowed);
    }

    /** {@code model} as the attached native track of an allowed kind whose public identity is trackId. */
    Object require(Object model, String trackId, Kind... allowed) {
        Kind kind = kind(model);
        if (kind == null) throw stale(trackId);
        a.requireModelThread(model);
        requireKind(trackId, kind, allowed);
        requireIdentity(trackId, identity(model));
        return model;
    }

    static void requireKind(String trackId, Kind kind, Kind... allowed) {
        if (Arrays.asList(allowed).contains(kind)) return;
        StringJoiner supported = new StringJoiner("/");
        for (Kind k : allowed) supported.add(k.label);
        throw Errors.notApplied(
                Reason.UNSUPPORTED_SCOPE,
                "Track " + trackId + " is a " + kind.label + " track; this method supports " + supported
                        + " tracks only.");
    }

    static void requireIdentity(String trackId, UUID identity) {
        if (!UUID.fromString(trackId).equals(identity)) throw stale(trackId);
    }

    static RpcException stale(String trackId) {
        return Errors.notApplied(
                Reason.TARGET_NOT_FOUND,
                "Track " + trackId + " no longer resolves to its native track; re-read tracks.list.");
    }

    /** The kind of a native track, or null when {@code model} is not one. */
    Kind kind(Object model) {
        if (!track.isInstance(model)) return null;
        if (group.isInstance(model)) return Kind.GROUP;
        return channel.isInstance(model) ? Kind.CHANNEL : Kind.OTHER;
    }

    Object target(Object proxy) {
        return a.invoke(target, proxy);
    }

    boolean same(Object proxy, Object model) {
        return target(proxy) == model;
    }

    /** The native track that owns a launcher clip slot. */
    Object slotTrack(Object slot) {
        return a.invoke(slotTrack, slot);
    }

    /** The public trackId of a native track: a group's channel-alias UUID, otherwise the track's own UUID. */
    UUID identity(Object model) {
        return group.isInstance(model) ? aliasUuid(alias(model)) : uuid(model);
    }

    UUID uuid(Object model) {
        return (UUID) a.invoke(uuid, model);
    }

    /** A group's channel alias: its own master channel, which carries the group's public trackId. */
    Object alias(Object groupModel) {
        return a.invoke(aliasOf, groupModel);
    }

    UUID aliasUuid(Object channelAlias) {
        return (UUID) a.invoke(aliasUuid, channelAlias);
    }

    Object parent(Object model) {
        return a.invoke(parent, model);
    }

    Object owner(Object model, Class<?> type) {
        return a.invoke(owner, model, type);
    }

    Class<?> trackType() {
        return track;
    }

    Class<?> channelType() {
        return channel;
    }

    Class<?> groupType() {
        return group;
    }

    Class<?> aliasType() {
        return alias;
    }
}
