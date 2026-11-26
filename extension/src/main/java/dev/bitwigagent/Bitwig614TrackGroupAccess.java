package dev.bitwigagent;

import com.google.gson.JsonObject;
import java.lang.reflect.Method;
import java.util.*;

/**
 * Exact 6.1.4 binding for the track-group model operation behind the track group command
 * {@code group_tracks(tracks)}: {@code SC.MRY(List)} on the tracks' common parent group. It opens its own
 * undoable transaction, creates the group at the first track's index and moves the listed tracks into it.
 * No UI selection is read; the native operation selects the new group afterwards.
 *
 * <p>A name is applied with {@code iYN.d_(String)}, the setter the public {@code setName} ends in, inside an outer
 * {@code g2L.cfM(label, false)} transaction around both calls. The transaction manager only reports the outermost
 * begin and end to the undo recorder (depth counter {@code lYc}), so grouping and naming are one undo step labelled
 * by the outer transaction. {@code g2L.cfM(oDc, ...)}, which {@code SC.MRY} calls, asserts that it is the outermost
 * operation; it is not here, so a JVM started with {@code -ea} fails in it before anything changes. The running host
 * has no {@code -ea}.
 */
final class Bitwig614TrackGroupAccess implements TrackLifecycleModule.GroupAccess {
    static final String UNDO_LABEL = "Group Tracks";
    private final Bitwig614Runtime.Access a;
    private final NativeReadContext context;
    private final Bitwig614TrackResolver resolver;
    private final Class<?> groupClass, projectType;
    private final Method groupTracks, begin, end, rename;

    Bitwig614TrackGroupAccess(Bitwig614Runtime.Access a) throws ReflectiveOperationException {
        this.a = a;
        context = new NativeReadContext(new Bitwig614ReadContextAccess(a));
        resolver = new Bitwig614TrackResolver(a);
        String master = "com.bitwig.flt.document.core.master.";
        groupClass = a.type(master + "SC");
        projectType = a.type(master + "g2L");
        groupTracks = a.exactMethod(groupClass, "MRY", void.class, false, List.class);
        begin = a.exactMethod(projectType, "cfM", void.class, false, String.class, boolean.class);
        end = a.exactMethod(projectType, "cfM", void.class, false, boolean.class);
        rename = a.exactMethod(a.type("com.bitwig.flt.document.core.iface.iYN"), "d_", void.class, false, String.class);
    }

    @Override
    public void execute(Runnable work) {
        a.execute(work);
    }

    @Override
    public JsonObject group(List<Object> proxies, List<String> ids, String name, Runnable check, Runnable beforeWrite) {
        a.documentThread();
        check.run();
        List<Object> tracks = resolve(proxies, ids);
        Object common = parent(tracks);
        Object owningProject = resolver.owner(common, projectType);
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
                throw Errors.notApplied(
                        Reason.STALE_CONTEXT, "Native track targets or their parent group changed before grouping.");
        };
        guard.run();
        beforeWrite.run();
        if (name == null) a.invoke(groupTracks, common, new ArrayList<>(tracks));
        else groupAndName(owningProject, common, tracks, name);
        JsonObject out = new JsonObject();
        out.addProperty("nativeOperation", "track_group.group_tracks");
        out.addProperty("nativeUndo", true);
        out.addProperty("undoGrouping", name == null ? "group_transaction" : "group_and_name_one_transaction");
        out.addProperty("nativeVersion", Bitwig614Runtime.VERSION);
        out.addProperty("nativeJarSha256", a.actualHash());
        return out;
    }

    /** One outer transaction: group the tracks, find the new group as their new common parent, name it. */
    private void groupAndName(Object project, Object common, List<Object> tracks, String name) {
        a.invoke(begin, project, UNDO_LABEL, false);
        try {
            a.invoke(groupTracks, common, new ArrayList<>(tracks));
            a.invoke(rename, newGroup(common, tracks), name);
        } finally {
            a.invoke(end, project, false);
        }
    }

    /** The group the operation created: every track's new parent, itself a child of the common parent. */
    private Object newGroup(Object common, List<Object> tracks) {
        Object group = resolver.parent(tracks.get(0));
        if (!groupClass.isInstance(group) || group == common || resolver.parent(group) != common)
            throw Errors.unknown(
                    Reason.NOT_OBSERVED, "The native grouping did not leave one new group under the common parent.");
        for (Object track : tracks)
            if (resolver.parent(track) != group)
                throw Errors.unknown(Reason.NOT_OBSERVED, "A grouped track is not inside the new native group.");
        return group;
    }

    private List<Object> resolve(List<Object> proxies, List<String> ids) {
        List<Object> tracks = new ArrayList<>(proxies.size());
        for (int i = 0; i < proxies.size(); i++) {
            Object track = resolver.resolve(
                    proxies.get(i), ids.get(i), Bitwig614TrackResolver.Kind.CHANNEL, Bitwig614TrackResolver.Kind.GROUP);
            if (tracks.contains(track))
                throw Errors.notApplied(Reason.STALE_CONTEXT, "Two track refs resolve to the same native track.");
            tracks.add(track);
        }
        return tracks;
    }

    private Object parent(List<Object> tracks) {
        Object common = null;
        for (Object track : tracks) {
            Object owner = resolver.parent(track);
            if (!groupClass.isInstance(owner))
                throw Errors.notApplied(Reason.UNSUPPORTED_SCOPE, "Native parent is not a track group.");
            if (common != null && owner != common)
                throw Errors.notApplied(Reason.UNSUPPORTED_SCOPE, "Tracks do not share one native parent group.");
            common = owner;
        }
        return common;
    }
}
