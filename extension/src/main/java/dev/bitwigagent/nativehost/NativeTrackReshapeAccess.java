package dev.bitwigagent.nativehost;

import com.google.gson.JsonObject;
import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.mix.TrackReshapeModule;
import java.lang.reflect.Method;
import java.util.*;
import java.util.function.BiPredicate;

/**
 * Exact bindings for the two track commands Bitwig only offers on the track selection: Convert to
 * Hybrid/Audio/Instrument Track and Ungroup.
 *
 * <p>Convert: the project's selection state ({@code project.state.selections}) holds the track selection, whose
 * selection descriptor dispatches a command id to the matching selection method ({@code
 * selection.descriptor.invoke(selection, id, [])}; ids 6917 instrument, 6918 audio, 6919 hybrid are the same on every
 * supported host). The command acts on the selected tracks, so the target is selected natively with {@code
 * selection.editable.set}, the command is dispatched, and the previous selection is set back. Bitwig disables a
 * conversion that would drop content. Its menu state is a cached selection property that lags behind selection and
 * clip changes, so the content is read from the track itself instead: {@code track.player.hasAudioClips()} and
 * {@code hasNoteClips()} are the live predicates Bitwig's own rule uses (any audio or note clip among the track's
 * clips). When the caller's rule refuses, nothing is selected or dispatched.
 *
 * <p>Ungroup: {@code track.groupNode.ungroup()} on the group itself (no selection needed), which moves the children up
 * and then selects the last moved child; the previous selection is set back afterwards.
 *
 * <p>Both commands run inside an outer {@code project.begin(label, false)} transaction: the transaction manager only
 * reports the outermost begin and end to the undo recorder, so each call is one undo step. Selection is not part of
 * the undo history, so restoring it adds no step.
 */
public final class NativeTrackReshapeAccess implements TrackReshapeModule.Access {
    static final String CONVERT_LABEL = "Convert Track", UNGROUP_LABEL = "Ungroup";
    private final NativeHost.Access a;
    private final NativeTrackResolver resolver;
    private final Class<?> projectType;
    private final Method selections, tracks, items, set, descriptor, dispatch, audioClips, noteClips, ungroup;
    private final Method begin, end;
    private final Class<?> playerType;

    public NativeTrackReshapeAccess(NativeHost.Access a) throws ReflectiveOperationException {
        this.a = a;
        resolver = new NativeTrackResolver(a);
        projectType = a.type("project");
        selections = a.exactMethod("project.state.selections", a.type("selection.document"), false);
        tracks = a.exactMethod("selection.documentBase.tracks", a.type("selection.tracks"), false);
        items = a.exactMethod("selection.base.items", List.class, false);
        set = a.exactMethod("selection.editable.set", void.class, false, Collection.class);
        descriptor = a.exactMethod("selection.objects.descriptor", a.type("selection.descriptor"), false);
        dispatch = a.exactMethod(
                "selection.descriptor.invoke",
                Object.class,
                false,
                a.type("selection.channels"),
                int.class,
                List.class);
        playerType = a.type("track.player");
        audioClips = a.exactMethod("track.player.hasAudioClips", boolean.class, false);
        noteClips = a.exactMethod("track.player.hasNoteClips", boolean.class, false);
        ungroup = a.exactMethod("track.groupNode.ungroup", void.class, false);
        begin = a.exactMethod("project.begin", void.class, false, String.class, boolean.class);
        end = a.exactMethod("project.end", void.class, false, boolean.class);
    }

    @Override
    public void execute(Runnable work) {
        a.execute(work);
    }

    @Override
    public JsonObject convert(
            Object proxy,
            String trackId,
            int command,
            BiPredicate<Boolean, Boolean> allowed,
            Runnable check,
            Runnable beforeWrite) {
        a.documentThread();
        check.run();
        Object track = resolver.resolve(proxy, trackId, NativeTrackResolver.Kind.CHANNEL);
        Object project = resolver.owner(track, projectType);
        Object selection = trackSelection(project);
        List<Object> previous = selected(selection);
        Object commands = a.invoke(descriptor, selection);
        if (!playerType.isInstance(track))
            throw Errors.notApplied(
                    Reason.UNSUPPORTED_SCOPE, "Track " + trackId + " is not an audio/instrument/hybrid track.");
        boolean audio = Boolean.TRUE.equals(a.invoke(audioClips, track));
        boolean notes = Boolean.TRUE.equals(a.invoke(noteClips, track));
        check.run();
        if (!allowed.test(audio, notes)) {
            JsonObject refused = info("track_selection.convert_track", true, previous.size());
            refused.addProperty("enabled", false);
            refused.addProperty("hasAudioClips", audio);
            refused.addProperty("hasNoteClips", notes);
            return refused;
        }
        beforeWrite.run();
        a.invoke(begin, project, CONVERT_LABEL, false);
        boolean restored;
        try {
            a.invoke(set, selection, List.of(track));
            a.invoke(dispatch, commands, selection, command, new ArrayList<>());
        } finally {
            a.invoke(end, project, false);
            // A converted track that the host rebuilt is the model now behind the same bank proxy.
            Object now = resolver.target(proxy);
            List<Object> restore = new ArrayList<>(previous);
            restore.replaceAll(item -> item == track ? now : item);
            restored = restore(selection, restore);
        }
        JsonObject out = info("track_selection.convert_track", restored, previous.size());
        out.addProperty("enabled", true);
        return out;
    }

    @Override
    public JsonObject ungroup(Object proxy, String groupId, Runnable check, Runnable beforeWrite) {
        a.documentThread();
        check.run();
        Object group = resolver.resolve(proxy, groupId, NativeTrackResolver.Kind.GROUP);
        Object project = resolver.owner(group, projectType);
        Object selection = trackSelection(project);
        List<Object> previous = selected(selection);
        check.run();
        beforeWrite.run();
        a.invoke(begin, project, UNGROUP_LABEL, false);
        boolean restored;
        try {
            a.invoke(ungroup, group);
        } finally {
            a.invoke(end, project, false);
            List<Object> restore = new ArrayList<>(previous);
            restore.remove(group);
            restored = restore(selection, restore);
        }
        return info("track_group.ungroup", restored, previous.size());
    }

    private Object trackSelection(Object project) {
        return a.invoke(tracks, a.invoke(selections, project));
    }

    private List<Object> selected(Object selection) {
        return new ArrayList<>((List<?>) a.invoke(items, selection));
    }

    /** Sets the selection to {@code items} and reads it back: true when exactly those items are selected. */
    private boolean restore(Object selection, List<Object> items) {
        try {
            a.invoke(set, selection, items);
            Set<Object> now = Collections.newSetFromMap(new IdentityHashMap<>());
            now.addAll(selected(selection));
            Set<Object> wanted = Collections.newSetFromMap(new IdentityHashMap<>());
            wanted.addAll(items);
            return now.equals(wanted);
        } catch (RuntimeException error) {
            return false;
        }
    }

    private JsonObject info(String operation, boolean restored, int selectedBefore) {
        JsonObject out = new JsonObject();
        out.addProperty("nativeOperation", operation);
        out.addProperty("nativeUndo", true);
        out.addProperty("undoGrouping", "one_transaction");
        out.addProperty("selectionRestored", restored);
        out.addProperty("selectedTracksBefore", selectedBefore);
        a.provenance(out);
        return out;
    }
}
