package dev.bitwigagent;

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
    private final Bitwig614TrackResolver resolver;
    private final Class<?> groupClass, projectType;
    private final Method groupTracks;

    Bitwig614TrackGroupAccess(Bitwig614Runtime.Access a) throws ReflectiveOperationException {
        this.a = a;
        context = new NativeReadContext(new Bitwig614ReadContextAccess(a));
        resolver = new Bitwig614TrackResolver(a);
        String master = "com.bitwig.flt.document.core.master.";
        groupClass = a.type(master + "SC");
        projectType = a.type(master + "g2L");
        groupTracks = a.exactMethod(groupClass, "MRY", void.class, false, List.class);
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
                        ErrorCode.STALE_CONTEXT, "Native track targets or their parent group changed before grouping.");
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
            Object track = resolver.track(proxies.get(i), ids.get(i));
            if (!UUID.fromString(ids.get(i)).equals(resolver.identity(track)))
                throw Errors.notApplied(
                        ErrorCode.STALE_CONTEXT,
                        "Native track UUID differs from the requested trackId " + ids.get(i) + ".");
            if (tracks.contains(track))
                throw Errors.notApplied(ErrorCode.STALE_CONTEXT, "Two trackIds resolve to the same native track.");
            tracks.add(track);
        }
        return tracks;
    }

    private Object parent(List<Object> tracks) {
        Object common = null;
        for (Object track : tracks) {
            Object owner = resolver.parent(track);
            if (!groupClass.isInstance(owner))
                throw Errors.notApplied(ErrorCode.UNSUPPORTED_SCOPE, "Native parent is not a track group.");
            if (common != null && owner != common)
                throw Errors.notApplied(ErrorCode.UNSUPPORTED_SCOPE, "Tracks do not share one native parent group.");
            common = owner;
        }
        return common;
    }
}
