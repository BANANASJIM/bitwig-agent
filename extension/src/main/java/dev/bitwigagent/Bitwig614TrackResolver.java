package dev.bitwigagent;

import java.lang.reflect.Method;
import java.util.UUID;

/** Exact 6.1.4 descriptors that resolve a public track proxy to its native track model and identity. */
final class Bitwig614TrackResolver {
    private final Bitwig614Runtime.Access a;
    private final Class<?> track, group, player;
    private final Method target, uuid, alias, aliasUuid, parent, owner;

    Bitwig614TrackResolver(Bitwig614Runtime.Access a) throws ReflectiveOperationException {
        this.a = a;
        String iface = "com.bitwig.flt.document.core.iface.";
        Class<?> identity = a.type(iface + "RYd"), channelAlias = a.type("iKU");
        track = a.type("com.bitwig.flt.document.core.master.zwV");
        group = a.type(iface + "kWY");
        player = a.type("ynF");
        target = a.exactMethod(
                a.type("com.bitwig.flt.control_surface.proxy.ControlSurfaceProxy"),
                "getDeepestTarget",
                Object.class,
                false);
        uuid = a.exactMethod(identity, "kFU", UUID.class, false);
        alias = a.exactMethod(group, "TD2", channelAlias, false);
        aliasUuid = a.exactMethod(channelAlias, "kFU", UUID.class, false);
        parent = a.exactMethod(identity, "QNk", group, false);
        owner = a.exactMethod(a.type("com.bitwig.ramona.core.AVL"), "IwV", Object.class, false, Class.class);
    }

    /** The attached native track behind a bank track proxy, checked on the model thread; identity is not checked. */
    Object track(Object proxy, String trackId) {
        Object model = target(proxy);
        if (!track.isInstance(model))
            throw Errors.notApplied(ErrorCode.STALE_CONTEXT, "Track proxy has no attached native track: " + trackId);
        a.requireModelThread(model);
        return model;
    }

    /** The audio/instrument/hybrid track whose own UUID is trackId; other track kinds fail with unsupported. */
    Object player(Object proxy, String trackId, String unsupported) {
        Object model = target(proxy);
        if (model == null)
            throw Errors.notApplied(ErrorCode.STALE_CONTEXT, "Track proxy has no attached native track.");
        if (!player.isInstance(model)) throw Errors.notApplied(ErrorCode.UNSUPPORTED_SCOPE, unsupported);
        a.requireModelThread(model);
        if (!UUID.fromString(trackId).equals(uuid(model)))
            throw Errors.notApplied(ErrorCode.STALE_CONTEXT, "Native track UUID differs from trackId.");
        return model;
    }

    Object target(Object proxy) {
        return a.invoke(target, proxy);
    }

    boolean same(Object proxy, Object model) {
        return target(proxy) == model;
    }

    boolean isGroup(Object model) {
        return group.isInstance(model);
    }

    /** The public trackId of a native track: a group's channel-alias UUID, otherwise the track's own UUID. */
    UUID identity(Object model) {
        return isGroup(model) ? aliasUuid(alias(model)) : uuid(model);
    }

    UUID uuid(Object model) {
        return (UUID) a.invoke(uuid, model);
    }

    Object alias(Object groupModel) {
        return a.invoke(alias, groupModel);
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
}
