package dev.bitwigagent;

import static dev.bitwigagent.HostServices.failure;

import com.google.gson.JsonObject;
import java.lang.reflect.Method;
import java.util.*;

/**
 * Exact 6.1.4 binding for the track-group model operation behind the track group command
 * {@code group_tracks(tracks)}: {@code SC.MRY(List)} on the tracks' common parent group. It opens its own
 * undoable transaction, creates the group at the first track's index and moves the listed tracks into it.
 * No UI selection is read; the native operation selects the new group afterwards.
 */
final class Bitwig614TrackGroupAccess implements MixModule.GroupAccess {
    private final Bitwig614Runtime.Access a;
    private final NativeReadContext context;
    private final Class<?> trackClass, groupIface, groupClass, projectType;
    private final Method target, uuid, alias, aliasUuid, parent, groupTracks, project;

    Bitwig614TrackGroupAccess(Bitwig614Runtime.Access a) throws ReflectiveOperationException {
        this.a = a;
        context = new NativeReadContext(new Bitwig614ReadContextAccess(a));
        String iface = "com.bitwig.flt.document.core.iface.", master = "com.bitwig.flt.document.core.master.";
        trackClass = a.type(master + "zwV");
        groupIface = a.type(iface + "kWY");
        groupClass = a.type(master + "SC");
        projectType = a.type(master + "g2L");
        Class<?> identity = a.type(iface + "RYd"), channelAlias = a.type("iKU");
        target = a.exactMethod(
                a.type("com.bitwig.flt.control_surface.proxy.ControlSurfaceProxy"),
                "getDeepestTarget",
                Object.class,
                false);
        uuid = a.exactMethod(identity, "kFU", UUID.class, false);
        alias = a.exactMethod(groupIface, "TD2", channelAlias, false);
        aliasUuid = a.exactMethod(channelAlias, "kFU", UUID.class, false);
        parent = a.exactMethod(identity, "QNk", groupIface, false);
        groupTracks = a.exactMethod(groupClass, "MRY", void.class, false, List.class);
        project = a.exactMethod(a.type("com.bitwig.ramona.core.AVL"), "IwV", Object.class, false, Class.class);
    }

    @Override
    public void execute(Runnable work) {
        a.execute(work);
    }

    @Override
    public JsonObject group(List<Object> proxies, List<String> ids, Runnable check, Runnable beforeWrite) {
        a.documentThread();
        check.run();
        List<Object> tracks = resolve(proxies, ids);
        Object common = parent(tracks);
        Object owningProject = a.invoke(project, common, projectType);
        Object[] models = new Object[tracks.size() + 1];
        String[] labels = new String[models.length + 1];
        for (int i = 0; i < tracks.size(); i++) {
            models[i] = tracks.get(i);
            labels[i] = "track" + i + "Deleted";
        }
        models[tracks.size()] = common;
        labels[tracks.size()] = "parentDeleted";
        labels[models.length] = "projectDeleted";
        NativeReadContext.Capture capture = context.capture(owningProject, models, labels, check, false);
        a.requireModelThread(common);
        Runnable guard = () -> {
            check.run();
            a.requireModelThread(common);
            context.guard(capture, models, labels, check);
            if (!resolve(proxies, ids).equals(tracks) || parent(tracks) != common)
                throw failure("stale_context", "Native track targets or their parent group changed before grouping.");
        };
        guard.run();
        beforeWrite.run();
        a.invoke(groupTracks, common, new ArrayList<>(tracks));
        JsonObject out = new JsonObject();
        out.addProperty("nativeOperation", "track_group.group_tracks");
        out.addProperty("nativeUndo", true);
        out.addProperty("nativeVersion", Bitwig614Runtime.VERSION);
        out.addProperty("nativeJarSha256", a.actualHash());
        return out;
    }

    private List<Object> resolve(List<Object> proxies, List<String> ids) {
        List<Object> tracks = new ArrayList<>(proxies.size());
        for (int i = 0; i < proxies.size(); i++) {
            Object track = a.invoke(target, proxies.get(i));
            if (!trackClass.isInstance(track))
                throw failure("stale_context", "Track proxy has no attached native track: " + ids.get(i));
            a.requireModelThread(track);
            Object identity =
                    groupIface.isInstance(track) ? a.invoke(aliasUuid, a.invoke(alias, track)) : a.invoke(uuid, track);
            if (!UUID.fromString(ids.get(i)).equals(identity))
                throw failure(
                        "stale_context", "Native track UUID differs from the requested trackId " + ids.get(i) + ".");
            if (tracks.contains(track))
                throw failure("stale_context", "Two trackIds resolve to the same native track.");
            tracks.add(track);
        }
        return tracks;
    }

    private Object parent(List<Object> tracks) {
        Object common = null;
        for (Object track : tracks) {
            Object owner = a.invoke(parent, track);
            if (!groupClass.isInstance(owner))
                throw failure("unsupported_scope", "Native parent is not a track group.");
            if (common != null && owner != common)
                throw failure("unsupported_scope", "Tracks do not share one native parent group.");
            common = owner;
        }
        return common;
    }
}
