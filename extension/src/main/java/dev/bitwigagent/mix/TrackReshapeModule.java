package dev.bitwigagent.mix;

import com.bitwig.extension.controller.api.Track;
import com.google.gson.*;
import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.HostModule;
import dev.bitwigagent.core.HostOperation;
import dev.bitwigagent.core.Outcome;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.core.Results;
import dev.bitwigagent.core.Scheduler;
import dev.bitwigagent.core.Verification;
import dev.bitwigagent.host.NativeRuntime;
import dev.bitwigagent.host.SelectionLease;
import dev.bitwigagent.host.TrackRefs;
import dev.bitwigagent.host.TrackWindow;
import dev.bitwigagent.host.TransportSafety;
import dev.bitwigagent.nativehost.NativeTrackReshapeAccess;
import java.util.*;

/**
 * tracks.convert and tracks.ungroup: Bitwig's selection-bound track commands, run natively on the named track with
 * the user's track selection put back afterwards, and verified by the observed track type or tree.
 */
public final class TrackReshapeModule implements HostModule {
    static final Set<String> METHODS = Set.of("tracks.convert", "tracks.ungroup");
    /** Bitwig's command ids for converting the selected tracks; the same on every supported host. */
    static final Map<String, Integer> CONVERT_COMMANDS = Map.of("instrument", 6917, "audio", 6918, "hybrid", 6919);

    private static final Set<String> CONVERTIBLE = Set.of("Audio", "Instrument", "Hybrid");

    /** The native commands, run on the document thread; each returns native provenance and selectionRestored. */
    public interface Access {
        void execute(Runnable work);

        JsonObject convert(Object trackProxy, String trackId, int command, Runnable check, Runnable beforeWrite);

        JsonObject ungroup(Object groupProxy, String groupId, Runnable check, Runnable beforeWrite);
    }

    private final Scheduler scheduler;
    private final TrackWindow window;
    private final TrackRefs trackRefs;
    private final TransportSafety transportSafety;
    private final SelectionLease selection;
    private final NativeRuntime natives;
    private final MixModule mix;
    private Access access;

    public TrackReshapeModule(
            Scheduler scheduler,
            TrackWindow window,
            TrackRefs trackRefs,
            TransportSafety transportSafety,
            SelectionLease selection,
            NativeRuntime natives,
            MixModule mix,
            Access access) {
        this.scheduler = scheduler;
        this.window = window;
        this.trackRefs = trackRefs;
        this.transportSafety = transportSafety;
        this.selection = selection;
        this.natives = natives;
        this.mix = mix;
        this.access = access;
    }

    @Override
    public Set<String> methods() {
        return METHODS;
    }

    @Override
    public void validate(String method, JsonObject p) {}

    @Override
    public void dispatch(HostOperation op, String method, JsonObject p) {
        scheduler.check(op);
        transportSafety.recording(op);
        selection.requireFree();
        MixModule.Target target = mix.target(p.getAsJsonObject("target"), true);
        mix.guard(op, target);
        int slot = window.trackSlot(target.id);
        JsonElement type = mix.meta(slot, "type");
        if (type.isJsonNull()) throw Errors.notApplied(Reason.NOT_READY, "Track type has not been observed.");
        if (method.equals("tracks.convert"))
            convert(op, target, slot, type.getAsString(), p.get("to").getAsString());
        else ungroup(op, target, slot, type.getAsString());
    }

    private Access bound(HostOperation op) {
        if (access == null) {
            try {
                access = new NativeTrackReshapeAccess(natives.get(op).access());
            } catch (ReflectiveOperationException error) {
                throw Errors.notApplied(
                        Reason.NATIVE_UNAVAILABLE,
                        "Exact track command descriptors unavailable: " + error.getMessage());
            }
        }
        return access;
    }

    /** One native command: given the bound access, the safety check and the hook to run right before writing. */
    private interface Command {
        JsonObject run(Access access, Runnable check, Runnable beforeWrite);
    }

    /** Runs a native command with the selection lease held and finishes with {@code observe} on the controller thread. */
    private void run(HostOperation op, Command command, java.util.function.Consumer<JsonObject> observe) {
        Access bound = bound(op);
        Runnable check = () -> {
            scheduler.check(op);
            transportSafety.requirePlaybackSafe();
        };
        selection.acquire(op);
        bound.execute(() -> {
            try {
                JsonObject info = command.run(bound, check, () -> {
                    check.run();
                    op.markDispatched();
                });
                scheduler.schedule(op, () -> observe.accept(info), 0);
            } catch (RuntimeException | Error error) {
                op.fail(error);
            }
        });
    }

    // ---------------------------------------------------------------- convert

    private void convert(HostOperation op, MixModule.Target target, int slot, String type, String to) {
        if (!CONVERTIBLE.contains(type))
            throw Errors.notApplied(
                    Reason.UNSUPPORTED_SCOPE,
                    "Only Audio, Instrument and Hybrid tracks can be converted; this track is " + type
                            + "; nothing changed.");
        if (type.equalsIgnoreCase(to)) {
            JsonObject result = Results.alreadyMatched();
            result.add("ref", trackRefs.ref(slot));
            result.addProperty("type", type);
            op.result.complete(result);
            return;
        }
        Object proxy = window.bank().getItemAt(slot);
        int command = CONVERT_COMMANDS.get(to);
        run(op, (a, check, write) -> a.convert(proxy, target.id, command, check, write), info -> {
            Track track = window.at(slot);
            scheduler.poll(
                    op,
                    scheduler.observeUntil(op),
                    () -> !window.trackMatches(target.id, track)
                            || to.equalsIgnoreCase(track.trackType().get()),
                    done -> {
                        boolean current = window.trackMatches(target.id, track);
                        String observed = current ? track.trackType().get() : null;
                        boolean converted = current && to.equalsIgnoreCase(observed);
                        JsonObject result = Results.outcome(
                                converted ? Outcome.APPLIED : Outcome.UNKNOWN, Verification.API_READBACK);
                        if (!converted)
                            result.addProperty(
                                    "reason", (current ? Reason.NOT_OBSERVED : Reason.TARGET_CHANGED).wire());
                        if (current) result.add("ref", trackRefs.ref(window.trackSlot(target.id)));
                        JsonObject types = new JsonObject();
                        types.addProperty("before", type);
                        types.add("after", observed == null ? JsonNull.INSTANCE : new JsonPrimitive(observed));
                        result.add("type", types);
                        finish(op, result, info);
                    });
        });
    }

    // ---------------------------------------------------------------- ungroup

    private void ungroup(HostOperation op, MixModule.Target target, int slot, String type) {
        if (!type.equals("Group"))
            throw Errors.notApplied(
                    Reason.UNSUPPORTED_SCOPE, "Only a group track can be ungrouped; this track is " + type + ".");
        TrackTree before = TrackTree.capture(window);
        if (!before.complete)
            throw Errors.notApplied(
                    Reason.NOT_READY,
                    "Ungrouping needs the whole project in the track window with every parent observed ("
                            + before.reason + "); nothing changed.");
        TrackTree.Node group = before.byId.get(target.id);
        List<String> children = new ArrayList<>();
        for (TrackTree.Node node : before.nodes)
            if (!node.master() && target.id.equals(node.parentId)) children.add(node.id);
        Object proxy = window.bank().getItemAt(slot);
        run(op, (a, check, write) -> a.ungroup(proxy, target.id, check, write), info -> {
            boolean[] seen = {false};
            scheduler.conclude(
                    op,
                    scheduler.observeUntil(op),
                    () -> {
                        // Attributed once the ungrouped tree is seen on two polls in a row.
                        boolean now = ungrouped(before, group, children), stable = now && seen[0];
                        seen[0] = now;
                        return stable ? () -> ungroupObserved(op, group, children, info) : null;
                    },
                    () -> {
                        JsonObject result = Results.unknown();
                        result.addProperty("reason", Reason.NOT_OBSERVED.wire());
                        result.addProperty("detail", "ungrouped_tree_not_observed");
                        finish(op, result, info);
                    });
        });
    }

    /** The group left the bank and its children now sit in its parent, in order; every other parent is unchanged. */
    private boolean ungrouped(TrackTree before, TrackTree.Node group, List<String> children) {
        TrackTree after = TrackTree.capture(window);
        if (!after.complete || after.byId.containsKey(group.id) || after.nodes.size() != before.nodes.size() - 1)
            return false;
        for (TrackTree.Node node : before.nodes) {
            if (node == group || node.master()) continue;
            TrackTree.Node now = after.byId.get(node.id);
            String parent = children.contains(node.id) ? group.parentId : node.parentId;
            // A flat tree ignores parent observations, so a child still reporting the group is checked here.
            if (now == null || !Objects.equals(now.parentId, parent) || group.id.equals(now.parentRaw)) return false;
        }
        for (int i = 1; i < children.size(); i++)
            if (after.byId.get(children.get(i - 1)).position > after.byId.get(children.get(i)).position) return false;
        return true;
    }

    private void ungroupObserved(HostOperation op, TrackTree.Node group, List<String> children, JsonObject info) {
        JsonObject result = Results.outcome(Outcome.APPLIED, Verification.API_READBACK);
        JsonObject ungrouped = new JsonObject();
        ungrouped.addProperty("id", group.id);
        ungrouped.addProperty("type", group.type);
        result.add("ungrouped", ungrouped);
        JsonArray refs = new JsonArray();
        for (String id : children) refs.add(trackRefs.ref(window.trackSlot(id)));
        result.add("children", refs);
        result.add(
                "parent", group.parentId == null ? JsonNull.INSTANCE : trackRefs.ref(window.trackSlot(group.parentId)));
        finish(op, result, info);
    }

    /** Adds the native provenance and the selection outcome, then completes. */
    private void finish(HostOperation op, JsonObject result, JsonObject info) {
        boolean restored = info.get("selectionRestored").getAsBoolean();
        result.addProperty("selectionRestored", restored);
        if (!restored)
            result.addProperty(
                    "selectionNote",
                    "The user's track selection could not be put back; the command's own selection remains.");
        JsonObject nativeInfo = info.deepCopy();
        nativeInfo.remove("selectionRestored");
        result.add("native", nativeInfo);
        op.result.complete(result);
    }
}
