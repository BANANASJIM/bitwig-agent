package dev.bitwigagent;

import com.bitwig.extension.controller.api.Track;
import com.google.gson.*;
import java.util.*;

/**
 * Parent/child structure of the flat track window from public observations only: each bank slot's
 * {@code createParentTrack} proxy, {@code isGroup}, type and flat position. The flat bank lists tracks in
 * tree pre-order, so the first non-master track is top-level and its parent is the root group.
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
        boolean topLevel, parentResolved;
        Node(int slot, String id, String type, int position, Boolean isGroup, Boolean expanded, String parentRaw) {
            this.slot = slot; this.id = id; this.type = type; this.position = position;
            this.isGroup = isGroup; this.expanded = expanded; this.parentRaw = parentRaw;
        }
        boolean master() { return "Master".equals(type); }
        boolean group() { return "Group".equals(type) || Boolean.TRUE.equals(isGroup); }
    }

    static TrackTree capture(HostServices s) {
        TrackTree tree = new TrackTree();
        int offset = s.tracks.scrollPosition().get(), total = s.tracks.itemCount().get();
        boolean unique = true;
        for (int i = 0; i < HostServices.TRACKS; i++) {
            Track track = (Track) s.tracks.getItemAt(i);
            if (!track.exists().get()) continue;
            String id = track.channelId().get(), type = track.trackType().get();
            int position = track.position().get();
            Track parent = s.parents[i];
            String parentRaw = parent.exists().get() ? blankToNull(parent.channelId().get()) : null;
            Node node = new Node(i, id, blankToNull(type), position, track.isGroup().get(), track.isGroupExpanded().get(), parentRaw);
            if (id == null || id.isEmpty() || tree.byId.put(id, node) != null || node.type == null || position < 0) unique = false;
            tree.nodes.add(node);
            tree.groups |= node.group();
        }
        tree.nodes.sort(Comparator.comparingInt(n -> n.position));
        tree.windowComplete = unique && offset == 0 && total <= HostServices.TRACKS && tree.nodes.size() == total;
        tree.resolve();
        return tree;
    }

    private void resolve() {
        List<Node> ordered = new ArrayList<>();
        for (Node node : nodes) if (!node.master()) ordered.add(node);
        if (!windowComplete) { reason = "track_window_incomplete"; markFlat(ordered); return; }
        if (ordered.isEmpty()) { complete = true; return; }
        rootParentId = ordered.get(0).parentRaw;
        if (!groups) {
            // Without a Group in the window every track is top-level; parent observations are not required.
            markFlat(ordered); complete = true; return;
        }
        boolean ok = rootParentId != null;
        if (!ok) reason = "parent_observation_missing";
        for (Node node : ordered) {
            if (node.parentRaw == null) { ok = false; reason = "parent_observation_missing"; continue; }
            if (node.parentRaw.equals(rootParentId)) { node.topLevel = true; node.parentResolved = true; continue; }
            Node parent = byId.get(node.parentRaw);
            node.parentId = node.parentRaw;
            if (parent == null || !parent.group() || parent.master() || parent.position >= node.position) {
                ok = false; reason = "parent_not_a_group_in_window"; continue;
            }
            node.parentResolved = true;
        }
        if (ok) for (Node node : ordered) if (depth(node, 0) < 0) { ok = false; reason = "parent_cycle"; }
        // Pre-order: every track strictly between a group and its child descends from that group.
        if (ok) for (int i = 0; i < ordered.size() && ok; i++) {
            Node node = ordered.get(i);
            if (node.topLevel) continue;
            Node parent = byId.get(node.parentId);
            for (int j = ordered.indexOf(parent) + 1; j < i; j++)
                if (!descends(ordered.get(j), parent)) { ok = false; reason = "parent_order_inconsistent"; break; }
        }
        complete = ok;
        if (!ok) for (Node node : ordered) node.depth = -1;
    }

    private void markFlat(List<Node> ordered) {
        if (groups) return;
        for (Node node : ordered) { node.topLevel = true; node.parentResolved = true; node.depth = 0; }
    }

    private int depth(Node node, int guard) {
        if (node.depth >= 0) return node.depth;
        if (guard > HostServices.TRACKS) return -1;
        if (node.topLevel) return node.depth = 0;
        int parentDepth = depth(byId.get(node.parentId), guard + 1);
        return parentDepth < 0 ? -1 : (node.depth = parentDepth + 1);
    }

    private boolean descends(Node node, Node ancestor) {
        for (int guard = 0; node != null && !node.topLevel && guard <= HostServices.TRACKS; guard++) {
            if (ancestor.id.equals(node.parentId)) return true;
            node = byId.get(node.parentId);
        }
        return false;
    }

    /** Zero-based index among the root's direct children of the same list (effect vs. main), by flat order. */
    int topLevelOrdinal(Node candidate) {
        boolean effect = "Effect".equals(candidate.type);
        int ordinal = 0;
        for (Node node : nodes)
            if (node != candidate && !node.master() && node.topLevel && "Effect".equals(node.type) == effect
                    && node.position < candidate.position) ordinal++;
        return ordinal;
    }

    int topLevelCount(boolean effect) {
        int count = 0;
        for (Node node : nodes) if (!node.master() && node.topLevel && "Effect".equals(node.type) == effect) count++;
        return count;
    }

    /** Child rows for tracks.list; parent fields are null when unresolved rather than guessed. */
    void describe(JsonObject row, Node node) {
        row.addProperty("type", node.type);
        row.addProperty("isGroup", node.group());
        row.add("groupExpanded", node.group() && node.expanded != null ? new JsonPrimitive(node.expanded) : JsonNull.INSTANCE);
        if (node.master()) { row.add("parentId", JsonNull.INSTANCE); row.add("depth", JsonNull.INSTANCE); return; }
        row.add("parentId", node.parentResolved && !node.topLevel ? new JsonPrimitive(node.parentId) : JsonNull.INSTANCE);
        row.add("depth", node.depth >= 0 ? new JsonPrimitive(node.depth) : JsonNull.INSTANCE);
        row.addProperty("parentResolved", node.parentResolved);
    }

    JsonObject summary() {
        JsonObject out = new JsonObject();
        out.addProperty("complete", complete);
        out.addProperty("groupsPresent", groups);
        if (reason != null) out.addProperty("reason", reason);
        out.addProperty("scope", "public_parent_proxy_observations_flat_preorder");
        return out;
    }

    private static String blankToNull(String value) { return value == null || value.isEmpty() ? null : value; }
}
