package dev.bitwigagent.mix;

import com.bitwig.extension.controller.api.Track;
import com.google.gson.*;
import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.HostLimits;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.host.TrackWindow;
import java.util.*;
import java.util.function.IntFunction;

/**
 * Parent/child structure of the flat track window from public observations only: each bank slot's
 * {@code createParentTrack} proxy, {@code isGroup}, type and flat position. The flat bank lists tracks in
 * tree pre-order, so the first non-master track is top-level and its parent is the root group.
 * On 6.1.4 a group's own parent proxy was observed reporting neither the root nor a group in the window (a group's
 * public channelId is its channel alias); such a group's parent is taken from pre-order only where that is
 * unambiguous, else the tree stays incomplete.
 */
final class TrackTree {
    final List<Node> nodes = new ArrayList<>();
    final Map<String, Node> byId = new HashMap<>();
    /** Every track in a complete zero-offset window with unique identity, type and position. */
    boolean windowComplete;
    /** windowComplete plus a parent for every track that is the root or a Group in the window, consistent with pre-order. */
    boolean complete;

    boolean groups;
    String rootParentId, reason;

    static final class Node {
        final int slot;
        final String id, type;
        final int position;
        final Boolean isGroup, expanded;
        final String parentRaw;
        String parentId;
        int depth = -1;
        boolean topLevel, parentResolved, parentInferred;

        Node(int slot, String id, String type, int position, Boolean isGroup, Boolean expanded, String parentRaw) {
            this.slot = slot;
            this.id = id;
            this.type = type;
            this.position = position;
            this.isGroup = isGroup;
            this.expanded = expanded;
            this.parentRaw = parentRaw;
        }

        boolean master() {
            return "Master".equals(type);
        }

        boolean group() {
            return "Group".equals(type) || Boolean.TRUE.equals(isGroup);
        }
    }

    static TrackTree capture(TrackWindow window) {
        TrackTree tree = new TrackTree();
        int offset = window.offset(), total = window.total();
        boolean unique = true;
        for (int i = 0; i < HostLimits.TRACKS; i++) {
            Track track = window.at(i);
            if (!track.exists().get()) continue;
            String id = track.channelId().get(), type = track.trackType().get();
            int position = track.position().get();
            Track parent = window.parent(i);
            String parentRaw =
                    parent.exists().get() ? blankToNull(parent.channelId().get()) : null;
            Node node = new Node(
                    i,
                    id,
                    blankToNull(type),
                    position,
                    track.isGroup().get(),
                    track.isGroupExpanded().get(),
                    parentRaw);
            if (id == null || id.isEmpty() || tree.byId.put(id, node) != null || node.type == null || position < 0)
                unique = false;
            tree.nodes.add(node);
            tree.groups |= node.group();
        }
        tree.nodes.sort(Comparator.comparingInt(n -> n.position));
        tree.windowComplete = unique && offset == 0 && total <= HostLimits.TRACKS && tree.nodes.size() == total;
        tree.resolve();
        return tree;
    }

    private void resolve() {
        List<Node> ordered = new ArrayList<>();
        for (Node node : nodes) if (!node.master()) ordered.add(node);
        if (!windowComplete) {
            reason = "track_window_incomplete";
            markFlat(ordered);
            return;
        }
        if (ordered.isEmpty()) {
            complete = true;
            return;
        }
        rootParentId = rootParent(ordered);
        if (!groups) {
            // Without a Group in the window every track is top-level; parent observations are not required.
            markFlat(ordered);
            complete = true;
            return;
        }
        boolean ok = true;
        List<Node> unobservable = new ArrayList<>();
        for (Node node : ordered) {
            if (node.parentRaw == null) {
                ok = false;
                reason = "parent_observation_missing";
                continue;
            }
            if (node.parentRaw.equals(rootParentId)) {
                node.topLevel = true;
                node.parentResolved = true;
                continue;
            }
            Node parent = byId.get(node.parentRaw);
            if (parent != null && parent.group() && !parent.master() && parent.position < node.position) {
                node.parentId = node.parentRaw;
                node.parentResolved = true;
                continue;
            }
            // A group reporting itself or an id outside the window: its parent proxy says nothing about its parent.
            if (node.group() && (parent == null || parent == node)) {
                unobservable.add(node);
                continue;
            }
            node.parentId = node.parentRaw;
            ok = false;
            reason = "parent_not_a_group_in_window";
        }
        for (Node node : unobservable) {
            // Pre-order: the parent is the previous track or one of its ancestors. It is the root without doubt
            // only when the group comes first or follows a top-level track that is not a group.
            int i = ordered.indexOf(node);
            Node previous = i == 0 ? null : ordered.get(i - 1);
            if (previous == null || (previous.topLevel && !previous.group())) {
                node.topLevel = true;
                node.parentResolved = true;
                node.parentInferred = true;
            } else {
                ok = false;
                reason = "group_parent_ambiguous";
            }
        }
        if (ok)
            for (Node node : ordered)
                if (depth(node, 0) < 0) {
                    ok = false;
                    reason = "parent_cycle";
                }
        // Pre-order: every track strictly between a group and its child descends from that group.
        if (ok)
            for (int i = 0; i < ordered.size() && ok; i++) {
                Node node = ordered.get(i);
                if (node.topLevel) continue;
                Node parent = byId.get(node.parentId);
                for (int j = ordered.indexOf(parent) + 1; j < i; j++)
                    if (!descends(ordered.get(j), parent)) {
                        ok = false;
                        reason = "parent_order_inconsistent";
                        break;
                    }
            }
        complete = ok;
        if (!ok) for (Node node : ordered) node.depth = -1;
    }

    /**
     * The first track is top-level, so its parent is the root. A group's own parent value is not trusted for that:
     * when the first track is a group, the root is the parent of the first non-group track (an effect track counts)
     * whose parent is outside the window; without one there is no root and groups go through inference.
     */
    private String rootParent(List<Node> ordered) {
        Node first = ordered.get(0);
        if (!first.group()) return first.parentRaw;
        for (Node node : ordered)
            if (!node.group() && node.parentRaw != null && !inWindow(node.parentRaw)) return node.parentRaw;
        return null;
    }

    private boolean inWindow(String id) {
        Node node = byId.get(id);
        return node != null && !node.master();
    }

    private void markFlat(List<Node> ordered) {
        if (groups) return;
        for (Node node : ordered) {
            node.topLevel = true;
            node.parentResolved = true;
            node.depth = 0;
        }
    }

    private int depth(Node node, int guard) {
        if (node.depth >= 0) return node.depth;
        if (guard > HostLimits.TRACKS) return -1;
        if (node.topLevel) return node.depth = 0;
        int parentDepth = depth(byId.get(node.parentId), guard + 1);
        return parentDepth < 0 ? -1 : (node.depth = parentDepth + 1);
    }

    private boolean descends(Node node, Node ancestor) {
        for (int guard = 0; node != null && !node.topLevel && guard <= HostLimits.TRACKS; guard++) {
            if (ancestor.id.equals(node.parentId)) return true;
            node = byId.get(node.parentId);
        }
        return false;
    }

    /** A group whose tracks the bank does not list: collapsed, or its expanded state not observed. */
    static boolean collapsed(Node node) {
        return node.group() && !node.master() && !Boolean.TRUE.equals(node.expanded);
    }

    /** Refuses an edit of a group whose tracks the bank does not list, since they would change unobserved. */
    static void requireListed(Node node, String verb) {
        if (collapsed(node))
            throw Errors.notApplied(
                    Reason.NOT_READY,
                    "Group track " + node.id + " is collapsed, so the tracks inside it are not listed and could not be "
                            + verb + " observably; expand the group in Bitwig (the folder icon in its track header), "
                            + "check tracks.list shows its tracks, then retry. Nothing changed.");
    }

    /** Every track inside {@code group}, at any depth, in flat order; meaningful only on a complete tree. */
    List<Node> descendants(Node group) {
        List<Node> out = new ArrayList<>();
        for (Node node : nodes) if (!node.master() && descends(node, group)) out.add(node);
        return out;
    }

    /** Zero-based index among the root's direct children of the same list (effect vs. main), by flat order. */
    int topLevelOrdinal(Node candidate) {
        boolean effect = "Effect".equals(candidate.type);
        int ordinal = 0;
        for (Node node : nodes)
            if (node != candidate
                    && !node.master()
                    && node.topLevel
                    && "Effect".equals(node.type) == effect
                    && node.position < candidate.position) ordinal++;
        return ordinal;
    }

    int topLevelCount(boolean effect) {
        int count = 0;
        for (Node node : nodes) if (!node.master() && node.topLevel && "Effect".equals(node.type) == effect) count++;
        return count;
    }

    /** Child rows for tracks.list; the parent is null when unresolved rather than guessed. {@code refOf} gives a slot's ref. */
    void describe(JsonObject row, Node node, IntFunction<JsonObject> refOf) {
        row.addProperty("type", node.type);
        row.addProperty("isGroup", node.group());
        row.add(
                "groupExpanded",
                node.group() && node.expanded != null ? new JsonPrimitive(node.expanded) : JsonNull.INSTANCE);
        if (node.master()) {
            row.add("parent", JsonNull.INSTANCE);
            row.add("depth", JsonNull.INSTANCE);
            return;
        }
        row.add(
                "parent",
                node.parentResolved && !node.topLevel ? refOf.apply(byId.get(node.parentId).slot) : JsonNull.INSTANCE);
        row.add("depth", node.depth >= 0 ? new JsonPrimitive(node.depth) : JsonNull.INSTANCE);
        row.addProperty("parentResolved", node.parentResolved);
        if (node.parentInferred) row.addProperty("parentSource", "preorder_inference");
        // The raw proxy value when it did not decide the parent, for diagnosing host behaviour.
        if (groups && (node.parentInferred || !node.parentResolved))
            row.add("parentObserved", node.parentRaw == null ? JsonNull.INSTANCE : new JsonPrimitive(node.parentRaw));
    }

    JsonObject summary() {
        JsonObject out = new JsonObject();
        out.addProperty("complete", complete);
        out.addProperty("groupsPresent", groups);
        int collapsed = 0;
        for (Node node : nodes) if (collapsed(node)) collapsed++;
        // A collapsed group's tracks are not in the bank: the tree is complete for what is listed, not the project.
        out.addProperty("collapsedGroups", collapsed);
        if (reason != null) out.addProperty("reason", reason);
        out.addProperty("scope", "public_parent_proxy_observations_flat_preorder");
        return out;
    }

    private static String blankToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
