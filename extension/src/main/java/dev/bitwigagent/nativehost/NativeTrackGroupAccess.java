package dev.bitwigagent.nativehost;

import com.google.gson.JsonObject;
import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.host.NativeReadContext;
import dev.bitwigagent.mix.TrackLifecycleModule;
import java.lang.reflect.Method;
import java.util.*;

/**
 * Exact binding for the track-group model operation behind the track group command
 * {@code group_tracks(tracks)}: {@code track.groupNode.groupTracks(List)} on the tracks' common parent group. It opens
 * its own
 * undoable transaction, creates the group at the first track's index and moves the listed tracks into it.
 * No UI selection is read; the native operation selects the new group afterwards.
 *
 * <p>A name is applied with {@code document.named.rename(String)}, the setter the public {@code setName} ends in,
 * inside an outer {@code project.begin(label, false)} transaction around both calls. The transaction manager only
 * reports the outermost begin and end to the undo recorder (it keeps a depth counter), so grouping and naming are one
 * undo step labelled by the outer transaction. The project operation that {@code track.groupNode.groupTracks} calls
 * asserts that it is the outermost
 * operation; it is not here, so a JVM started with {@code -ea} fails in it before anything changes. The running host
 * has no {@code -ea}.
 */
public final class NativeTrackGroupAccess implements TrackLifecycleModule.GroupAccess {
    static final String UNDO_LABEL = "Group Tracks";
    private final NativeHost.Access a;
    private final NativeReadContext context;
    private final NativeTrackResolver resolver;
    private final Class<?> groupClass, projectType;
    private final Method groupTracks, begin, end, rename;

    public NativeTrackGroupAccess(NativeHost.Access a) throws ReflectiveOperationException {
        this.a = a;
        context = new NativeReadContext(new NativeReadContextAccess(a));
        resolver = new NativeTrackResolver(a);
        groupClass = a.type("track.groupNode");
        projectType = a.type("project");
        groupTracks = a.exactMethod("track.groupNode.groupTracks", void.class, false, List.class);
        begin = a.exactMethod("project.begin", void.class, false, String.class, boolean.class);
        end = a.exactMethod("project.end", void.class, false, boolean.class);
        rename = a.exactMethod("document.named.rename", void.class, false, String.class);
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
                        Reason.TARGET_CHANGED, "Native track targets or their parent group changed before grouping.");
        };
        guard.run();
        beforeWrite.run();
        if (name == null) a.invoke(groupTracks, common, new ArrayList<>(tracks));
        else groupAndName(owningProject, common, tracks, name);
        JsonObject out = new JsonObject();
        out.addProperty("nativeOperation", "track_group.group_tracks");
        out.addProperty("nativeUndo", true);
        out.addProperty("undoGrouping", name == null ? "group_transaction" : "group_and_name_one_transaction");
        out.addProperty("nativeVersion", a.hostVersion());
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
                    proxies.get(i), ids.get(i), NativeTrackResolver.Kind.CHANNEL, NativeTrackResolver.Kind.GROUP);
            if (tracks.contains(track))
                throw Errors.notApplied(Reason.TARGET_CHANGED, "Two track refs resolve to the same native track.");
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
