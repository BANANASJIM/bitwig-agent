package dev.bitwigagent.mix;

import com.bitwig.extension.controller.api.*;
import com.google.gson.*;
import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.Holds;
import dev.bitwigagent.core.HostLimits;
import dev.bitwigagent.core.HostModule;
import dev.bitwigagent.core.HostOperation;
import dev.bitwigagent.core.Outcome;
import dev.bitwigagent.core.Positions;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.core.Results;
import dev.bitwigagent.core.SafetyObservation;
import dev.bitwigagent.core.Scheduler;
import dev.bitwigagent.core.Verification;
import dev.bitwigagent.host.NativeRuntime;
import dev.bitwigagent.host.SelectionLease;
import dev.bitwigagent.host.TrackRefs;
import dev.bitwigagent.host.TrackWindow;
import dev.bitwigagent.host.TransportSafety;
import dev.bitwigagent.nativehost.NativeTrackGroupAccess;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** tracks.insert, tracks.group and tracks.delete: structure changes of the track list, each attributed by observation. */
public final class TrackLifecycleModule implements HostModule {
    static final Set<String> METHODS = Set.of("tracks.insert", "tracks.group", "tracks.delete");
    /** How long the new group is awaited once the native call has returned. */
    static final long GROUP_OBSERVE_NANOS = TimeUnit.MILLISECONDS.toNanos(3000);
    /** A completed/cancelled RPC does not cancel a void native factory. Never reuse its attribution window. */
    private static final String INSERTION = "tracks.insert", GROUP = "tracks.group";

    public interface GroupAccess {
        void execute(Runnable work);

        /** Groups the tracks; a non-null name is applied in the same native transaction as the grouping. */
        JsonObject group(
                List<Object> trackProxies, List<String> trackIds, String name, Runnable check, Runnable beforeWrite);
    }

    private final Scheduler scheduler;
    private final TrackWindow window;
    private final TrackRefs trackRefs;
    private final Application application;
    private final TransportSafety transportSafety;
    private final SelectionLease selection;
    private final NativeRuntime natives;
    private final Holds holds;
    private final MixModule mix;
    private HostOperation inserting;
    private GroupAccess groupAccess;

    public TrackLifecycleModule(
            Scheduler scheduler,
            TrackWindow window,
            TrackRefs trackRefs,
            Application application,
            TransportSafety transportSafety,
            SelectionLease selection,
            NativeRuntime natives,
            Holds holds,
            MixModule mix,
            GroupAccess groupAccess) {
        this.scheduler = scheduler;
        this.window = window;
        this.trackRefs = trackRefs;
        this.application = application;
        this.transportSafety = transportSafety;
        this.selection = selection;
        this.natives = natives;
        this.holds = holds;
        this.mix = mix;
        this.groupAccess = groupAccess;
    }

    @Override
    public Set<String> methods() {
        return METHODS;
    }

    @Override
    public void validate(String method, JsonObject p) {
        validateParams(method, p);
    }

    /** What the schema cannot say: a group lists each track once. */
    static void validateParams(String method, JsonObject p) {
        if (method.equals("tracks.group")) {
            Set<String> seen = new HashSet<>();
            for (JsonElement target : p.getAsJsonArray("targets"))
                if (!seen.add(target.getAsJsonObject().get("id").getAsString().toLowerCase(Locale.ROOT)))
                    throw Errors.invalid("targets must be distinct.");
        }
    }

    @Override
    public void dispatch(HostOperation op, String method, JsonObject p) {
        scheduler.check(op);
        switch (method) {
            case "tracks.insert":
                insert(op, p);
                return;
            case "tracks.group":
                group(op, p);
                return;
            default:
                delete(op, mix.target(p.getAsJsonObject("target"), true));
        }
    }

    private void requireNoPending(String refused) {
        for (String hold : List.of(INSERTION, GROUP))
            if (holds.held(hold))
                throw holds.blocked(
                        hold,
                        "A prior " + hold + " operation remains unattributed; " + refused + " is refused.",
                        "Inspect tracks.list");
        if (inserting != null && !inserting.result.isDone())
            throw Errors.notApplied(Reason.BUSY, "Another track insertion or grouping awaits observations.");
        if (!window.observed()) throw Errors.notApplied(Reason.NOT_READY, "Track bank has not reported its size yet.");
    }

    private void insert(HostOperation op, JsonObject p) {
        requireNoPending("track insertion");
        Creation before = capture();
        if (!before.complete)
            throw Errors.notApplied(
                    Reason.NOT_READY,
                    "Insertion requires a complete observed UUID/type/position baseline inside the track window.");
        if (before.total >= HostLimits.TRACKS)
            throw Errors.notApplied(
                    Reason.OUTSIDE_WINDOW,
                    "The " + HostLimits.TRACKS
                            + "-track window is full: the new track could not be observed; nothing changed.");
        if (before.groups && !before.tree.complete)
            throw Errors.notApplied(
                    Reason.UNSUPPORTED_SCOPE,
                    "Track insertion in a grouped project needs every track's parent observed (" + before.tree.reason
                            + "); the insertion position cannot be attributed safely and nothing changed.");
        String type = p.get("type").getAsString();
        int position = position(p.get("position"), type.equals("effect"), before.tree);
        inserting = op;
        scheduler.check(op);
        op.markDispatched();
        holds.place(INSERTION, "A prior tracks.insert factory remains unattributed.");
        switch (type) {
            case "audio":
                application.createAudioTrack(position);
                break;
            case "instrument":
                application.createInstrumentTrack(position);
                break;
            default:
                application.createEffectTrack(position);
        }
        awaitInsertion(op, p, before, position);
    }

    /** The index among the top-level tracks of the list the new track goes to; -1 appends. */
    private int position(JsonElement position, boolean effect, TrackTree tree) {
        if (position == null || position.isJsonPrimitive() && "end".equals(position.getAsString())) return -1;
        return Positions.index(position, tree.topLevelCount(effect), anchor -> {
            TrackTree.Node node = tree.byId.get(anchor.get("id").getAsString());
            trackRefs.resolve(anchor, false);
            if (node != null && node.master())
                throw Errors.notApplied(Reason.UNSUPPORTED_SCOPE, "The master track cannot be an anchor.");
            if (node == null || !node.topLevel)
                throw Errors.notApplied(
                        Reason.UNSUPPORTED_SCOPE, "An anchor must be a top-level track, not a track inside a group.");
            if ("Effect".equals(node.type) != effect)
                throw Errors.notApplied(
                        Reason.KIND_MISMATCH,
                        "The anchor must be in the same list as the new track: "
                                + (effect ? "an effect track" : "a main track") + ".");
            return tree.topLevelOrdinal(node);
        });
    }

    private void group(HostOperation op, JsonObject p) {
        requireNoPending("grouping");
        transportSafety.recording(op);
        selection.requireFree();
        Creation before = capture();
        if (!before.complete)
            throw Errors.notApplied(
                    Reason.NOT_READY,
                    "Grouping requires a complete observed UUID/type/position window of at most " + HostLimits.TRACKS
                            + " tracks.");
        if (before.total >= HostLimits.TRACKS)
            throw Errors.notApplied(
                    Reason.OUTSIDE_WINDOW,
                    "The " + HostLimits.TRACKS
                            + "-track window is full: the new group could not be observed; nothing changed.");
        if (!before.tree.complete)
            throw Errors.notApplied(
                    Reason.UNSUPPORTED_SCOPE,
                    "Grouping needs every track's parent observed (" + before.tree.reason + "); nothing changed.");
        List<TrackTree.Node> nodes = new ArrayList<>();
        for (JsonElement item : p.getAsJsonArray("targets")) {
            JsonObject ref = item.getAsJsonObject();
            String id = ref.get("id").getAsString();
            trackRefs.resolve(ref, true);
            TrackTree.Node node = before.tree.byId.get(id);
            if (node.master() || "Effect".equals(node.type))
                throw Errors.notApplied(
                        Reason.UNSUPPORTED_SCOPE,
                        "Only audio, instrument, hybrid and group tracks can be grouped; "
                                + ref.get("name").getAsString() + " is " + node.type + ".");
            nodes.add(node);
        }
        String parent = nodes.get(0).parentId;
        for (TrackTree.Node node : nodes)
            if (!Objects.equals(node.parentId, parent))
                throw Errors.notApplied(
                        Reason.UNSUPPORTED_SCOPE,
                        "All targets must share one parent (top level or the same group); nothing changed.");
        nodes.sort(Comparator.comparingInt(n -> n.position));
        List<String> siblings = new ArrayList<>();
        for (TrackTree.Node node : before.tree.nodes)
            if (!node.master() && Objects.equals(node.parentId, parent)) siblings.add(node.id);
        int first = siblings.indexOf(nodes.get(0).id);
        for (int i = 0; i < nodes.size(); i++)
            if (first + i >= siblings.size() || !siblings.get(first + i).equals(nodes.get(i).id))
                throw Errors.notApplied(
                        Reason.UNSUPPORTED_SCOPE,
                        "The targets must be adjacent tracks of one parent, with no other track between them (moving tracks comes later); nothing changed.");
        List<Object> proxies = new ArrayList<>();
        List<String> ordered = new ArrayList<>();
        for (TrackTree.Node node : nodes) {
            proxies.add(window.bank().getItemAt(node.slot));
            ordered.add(node.id);
        }
        if (groupAccess == null) {
            try {
                groupAccess = new NativeTrackGroupAccess(natives.get(op).access());
            } catch (ReflectiveOperationException error) {
                throw Errors.notApplied(
                        Reason.NATIVE_UNAVAILABLE, "Exact track group descriptors unavailable: " + error.getMessage());
            }
        }
        GroupAccess access = groupAccess;
        String name = p.has("name") ? p.get("name").getAsString() : null;
        // The native operation selects the new group: hold the selection lease like other selection-moving calls.
        selection.acquire(op);
        inserting = op;
        Runnable check = () -> {
            scheduler.check(op);
            transportSafety.requirePlaybackSafe();
        };
        access.execute(() -> {
            try {
                JsonObject nativeInfo = access.group(proxies, ordered, name, check, () -> {
                    check.run();
                    op.markDispatched();
                    holds.place(GROUP, "A prior tracks.group operation remains unattributed.");
                });
                long until = Math.min(scheduler.deadlineUntil(op), scheduler.now() + GROUP_OBSERVE_NANOS);
                scheduler.schedule(op, () -> awaitGroup(op, p, before, ordered, parent, nativeInfo, until), 0);
            } catch (RuntimeException | Error error) {
                op.fail(error);
            }
        });
    }

    private void awaitGroup(
            HostOperation op,
            JsonObject p,
            Creation before,
            List<String> ids,
            String parent,
            JsonObject nativeInfo,
            long until) {
        String[] candidate = {null};
        scheduler.conclude(
                op,
                until,
                () -> groupStep(op, p, before, ids, parent, nativeInfo, candidate),
                () -> unknownGroup(op, p, Reason.NOT_OBSERVED, "group_membership_not_observed"));
    }

    /** The conclusion once the group is attributed (seen on two polls in a row) or cannot be; null to keep polling. */
    private Runnable groupStep(
            HostOperation op,
            JsonObject p,
            Creation before,
            List<String> ids,
            String parent,
            JsonObject nativeInfo,
            String[] candidate) {
        Creation after = capture();
        if (after.offset != before.offset || !after.ids.containsAll(before.ids))
            return () -> unknownGroup(op, p, Reason.TARGET_CHANGED, "track_window_or_existing_targets_changed");
        Set<String> added = new HashSet<>(after.ids);
        added.removeAll(before.ids);
        if (added.size() > 1 || after.total > before.total + 1)
            return () -> unknownGroup(op, p, Reason.SIDE_EFFECT, "ambiguous_added_tracks");
        String found = null;
        if (after.complete && after.tree.complete && added.size() == 1 && after.total == before.total + 1) {
            String id = added.iterator().next();
            TrackTree.Node group = after.tree.byId.get(id);
            boolean ok = group != null && group.group() && Objects.equals(group.parentId, parent);
            for (TrackTree.Node node : before.tree.nodes) {
                if (node.master()) continue;
                TrackTree.Node now = after.tree.byId.get(node.id);
                ok &= now != null && Objects.equals(now.parentId, ids.contains(node.id) ? id : node.parentId);
            }
            for (int i = 1; ok && i < ids.size(); i++)
                ok = after.tree.byId.get(ids.get(i - 1)).position < after.tree.byId.get(ids.get(i)).position;
            if (ok) found = id;
        }
        if (found != null && found.equals(candidate[0])) {
            String group = found;
            return () -> groupAttributed(op, p, ids, parent, nativeInfo, group);
        }
        candidate[0] = found;
        return null;
    }

    private void groupAttributed(
            HostOperation op, JsonObject p, List<String> ids, String parent, JsonObject nativeInfo, String found) {
        holds.release(GROUP);
        JsonObject info = nativeInfo.deepCopy();
        JsonArray children = new JsonArray();
        for (String id : ids) children.add(trackRefs.ref(window.trackSlot(id)));
        info.add("children", children);
        info.add("parent", parent == null ? JsonNull.INSTANCE : trackRefs.ref(window.trackSlot(parent)));
        info.addProperty("selectionSideEffect", "native_operation_selects_the_new_group");
        int slot = window.trackSlot(found);
        if (!p.has("name")) {
            JsonObject result = Results.outcome(Outcome.APPLIED, Verification.API_READBACK);
            result.add("ref", trackRefs.ref(slot));
            result.add("group", info);
            op.result.complete(result);
            return;
        }
        String name = p.get("name").getAsString();
        Track group = window.at(slot);
        scheduler.poll(
                op,
                scheduler.observeUntil(op),
                () -> !window.trackMatches(found, group)
                        || name.equals(group.name().get()),
                done -> {
                    boolean current = window.trackMatches(found, group);
                    String observed = current ? group.name().get() : null;
                    boolean named = current && name.equals(observed);
                    Reason reason = !current ? Reason.TARGET_CHANGED : named ? null : Reason.NOT_OBSERVED;
                    JsonObject field = Results.field(
                            new JsonPrimitive(name),
                            observed == null ? JsonNull.INSTANCE : new JsonPrimitive(observed),
                            named ? Outcome.APPLIED : Outcome.UNKNOWN,
                            reason,
                            Verification.API_READBACK);
                    JsonObject result =
                            Results.outcome(named ? Outcome.APPLIED : Outcome.UNKNOWN, Verification.API_READBACK);
                    if (reason != null) result.addProperty("reason", reason.wire());
                    if (current) result.add("ref", trackRefs.ref(window.trackSlot(found)));
                    JsonObject fields = new JsonObject();
                    fields.add("name", field);
                    result.add("fields", fields);
                    result.add("group", info);
                    op.result.complete(result);
                });
    }

    private void unknownGroup(HostOperation op, JsonObject p, Reason reason, String detail) {
        op.result.complete(unattributed(p, reason, detail, GROUP));
    }

    private JsonObject unattributed(JsonObject p, Reason reason, String detail, String hold) {
        JsonObject result = Results.unknown();
        result.addProperty("reason", reason.wire());
        result.addProperty("detail", detail);
        result.addProperty("nameApplied", false);
        result.add("requested", p);
        result.addProperty("next", "Inspect tracks.list, then ack " + hold + "; never retry before that.");
        return result;
    }

    /** Delete one audio/instrument/hybrid/effect track by Track.deleteObject (DeleteableObject), verified by bank UUID set and count. */
    private void delete(HostOperation op, MixModule.Target target) {
        SafetyObservation safety = transportSafety.stopped(op);
        if (!window.observed()) throw Errors.notApplied(Reason.NOT_READY, "Track bank has not reported its size yet.");
        mix.guard(op, target);
        int slot = window.trackSlot(target.id);
        JsonElement type = mix.meta(slot, "type"), index = mix.meta(slot, "index");
        if (type.isJsonNull()) throw Errors.notApplied(Reason.NOT_READY, "Track type has not been observed.");
        if (!Set.of("Audio", "Instrument", "Hybrid", "Effect").contains(type.getAsString()))
            throw Errors.notApplied(
                    Reason.UNSUPPORTED_SCOPE,
                    "Only Audio, Instrument, Hybrid and Effect tracks can be deleted; this track is "
                            + type.getAsString() + " (group and master are refused); nothing changed.");
        Creation before = capture();
        if (!before.complete || !before.ids.contains(target.id))
            throw Errors.notApplied(
                    Reason.NOT_READY,
                    "Deletion needs the whole project observed in the " + HostLimits.TRACKS
                            + "-track bank window (offset 0, at most " + HostLimits.TRACKS
                            + " tracks including group children and effect tracks, every UUID/type/position read); this project exceeds it or is not fully observed yet. Nothing changed.");
        transportSafety.guardStopped(safety);
        mix.guard(op, target);
        op.markDispatched();
        JsonObject deleted = new JsonObject();
        deleted.addProperty("name", window.at(slot).name().get());
        deleted.addProperty("type", type.getAsString());
        deleted.add("index", index);
        window.at(slot).deleteObject();
        awaitDeletion(op, target.id, deleted, before);
    }

    private void awaitDeletion(HostOperation op, String id, JsonObject deleted, Creation before) {
        scheduler.poll(op, scheduler.observeUntil(op), () -> deletedFrom(capture(), before, id), gone -> {
            Creation after = capture();
            JsonObject result = Results.outcome(gone ? Outcome.APPLIED : Outcome.UNKNOWN, Verification.API_READBACK);
            if (!gone) result.addProperty("reason", Reason.NOT_OBSERVED.wire());
            result.add("deleted", deleted);
            result.addProperty("trackCountBefore", before.total);
            result.addProperty("trackCountAfter", after.total);
            op.result.complete(result);
        });
    }

    private static boolean deletedFrom(Creation after, Creation before, String id) {
        Set<String> others = new HashSet<>(before.ids);
        others.remove(id);
        return after.complete && !after.ids.contains(id) && after.total == before.total - 1 && after.ids.equals(others);
    }

    private void awaitInsertion(HostOperation op, JsonObject p, Creation before, int requested) {
        String[] candidate = {null};
        scheduler.conclude(
                op,
                scheduler.observeUntil(op),
                () -> insertionStep(op, p, before, requested, candidate),
                () -> unknownInsertion(op, p, Reason.NOT_OBSERVED, "unique_added_track_not_observed"));
    }

    /** The conclusion once the new track is attributed (seen on two polls in a row) or cannot be; null to keep polling. */
    private Runnable insertionStep(HostOperation op, JsonObject p, Creation before, int requested, String[] candidate) {
        Creation after = capture();
        if (after.offset != before.offset || !after.ids.containsAll(before.ids))
            return () -> unknownInsertion(op, p, Reason.TARGET_CHANGED, "track_window_or_existing_targets_changed");
        Set<String> added = new HashSet<>(after.ids);
        added.removeAll(before.ids);
        if (added.size() > 1 || after.total > before.total + 1)
            return () -> unknownInsertion(op, p, Reason.SIDE_EFFECT, "ambiguous_added_tracks");
        if (after.complete && after.total == before.total + 1 && added.size() == 1) {
            String id = added.iterator().next();
            Track track = window.at(window.trackSlot(id));
            String type = p.get("type").getAsString();
            boolean effect = type.equals("effect");
            if (!before.groups && after.groups)
                return () -> unknownInsertion(op, p, Reason.SIDE_EFFECT, "group_appeared_during_insertion");
            // Application inserts into the root group's main or effect list: in a grouped project the index counts
            // only top-level tracks, so attribution needs the observed parent of the new track.
            int oldSize;
            Integer observed;
            if (before.groups) {
                TrackTree.Node node = after.tree.complete ? after.tree.byId.get(id) : null;
                oldSize = before.tree.topLevelCount(effect);
                observed = node != null && node.topLevel ? after.tree.topLevelOrdinal(node) : null;
            } else {
                oldSize = effect ? before.effects : before.main;
                observed = ordinal(track, effect);
            }
            int expected = requested == -1 ? oldSize : Math.min(requested, oldSize);
            if (track.trackType().get().equalsIgnoreCase(type) && observed != null && observed == expected) {
                if (id.equals(candidate[0]))
                    return () -> insertionAttributed(op, p, before, id, track, type, requested);
                candidate[0] = id;
            } else candidate[0] = null;
        } else candidate[0] = null;
        return null;
    }

    private void insertionAttributed(
            HostOperation op, JsonObject p, Creation before, String id, Track track, String type, int requested) {
        holds.release(INSERTION);
        JsonObject info = new JsonObject();
        info.addProperty("identityScope", "observed_addition_not_factory_return_identity");
        info.addProperty("requestedPosition", requested);
        info.addProperty("observedPosition", track.position().get());
        info.addProperty("type", type);
        info.addProperty("parentScope", before.groups ? "top_level_of_grouped_project" : "flat_project");
        mix.rename(op, id, p.get("name").getAsString(), info, "insertion");
    }

    private int ordinal(Track candidate, boolean effect) {
        int ordinal = 0;
        for (int slot = 0; slot < HostLimits.TRACKS; slot++) {
            Track track = window.at(slot);
            if (!track.exists().get() || track.trackType().get().equals("Master")) continue;
            if (track.trackType().get().equals("Effect") == effect
                    && track.position().get() < candidate.position().get()) ordinal++;
        }
        return ordinal;
    }

    private Creation capture() {
        Creation out = new Creation();
        out.offset = window.offset();
        out.total = window.total();
        boolean unique = true;
        for (int slot = 0; slot < HostLimits.TRACKS; slot++) {
            Track track = window.at(slot);
            if (!track.exists().get()) continue;
            String id = track.channelId().get();
            unique &= id != null && !id.isEmpty() && out.ids.add(id);
            JsonElement observedType = mix.meta(slot, "type");
            if (observedType.isJsonNull() || mix.meta(slot, "index").isJsonNull()) {
                unique = false;
                continue;
            }
            String type = observedType.getAsString();
            out.groups |= type.equals("Group");
            if (type.equals("Effect")) out.effects++;
            else if (!type.equals("Master")) out.main++;
        }
        out.complete = unique && out.offset == 0 && out.total <= HostLimits.TRACKS && out.ids.size() == out.total;
        out.tree = TrackTree.capture(window);
        out.groups |= out.tree.groups;
        return out;
    }

    private void unknownInsertion(HostOperation op, JsonObject p, Reason reason, String detail) {
        op.result.complete(unattributed(p, reason, detail, INSERTION));
    }

    private static final class Creation {
        final Set<String> ids = new HashSet<>();
        int offset, total, main, effects;
        boolean complete, groups;
        TrackTree tree;
    }
}
