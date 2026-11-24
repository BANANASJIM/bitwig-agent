package dev.bitwigagent;

import com.google.gson.*;
import java.util.*;

/** Pure JSON shaping of Grid write results: constant prose stripped, bounded graphs reduced to what changed. */
final class GridDiff {
    private static final Set<String> CONSTANT = Set.of("nativeUndoScope", "undoGroupingVerified", "engineAcknowledgement",
            "safetyScope", "contextScope", "graphScope", "projectScope", "observationScope", "couplingScope",
            "maximumModules", "maximumOccupiedYSpan");
    private GridDiff() {}

    /** Writers attach graph (after) and graphBefore; the default response replaces both with the diff. */
    static JsonObject shape(JsonObject result, boolean fullGraph) {
        for (String key : CONSTANT) result.remove(key);
        JsonObject before = result.has("graphBefore") ? result.remove("graphBefore").getAsJsonObject() : null;
        if (fullGraph || before == null || !result.has("graph")) return result;
        JsonObject after = result.remove("graph").getAsJsonObject();
        diff(before, after, result);
        return result;
    }

    static void diff(JsonObject before, JsonObject after, JsonObject out) {
        Map<String, JsonObject> was = modules(before), now = modules(after);
        JsonArray added = new JsonArray(), removed = new JsonArray(), moved = new JsonArray(), renamed = new JsonArray();
        for (Map.Entry<String, JsonObject> entry : now.entrySet()) {
            JsonObject row = entry.getValue(), old = was.get(entry.getKey());
            if (old == null) {
                JsonObject copy = row.deepCopy(); copy.remove("parameters"); added.add(copy); continue;
            }
            if (!old.get("x").equals(row.get("x")) || !old.get("y").equals(row.get("y"))) {
                JsonObject item = id(entry.getKey());
                item.add("from", position(old)); item.add("to", position(row)); moved.add(item);
            }
            if (!old.get("customName").equals(row.get("customName")) || !old.get("name").equals(row.get("name"))) {
                JsonObject item = id(entry.getKey());
                item.add("from", old.get("customName")); item.add("to", row.get("customName"));
                item.add("name", row.get("name")); renamed.add(item);
            }
        }
        for (Map.Entry<String, JsonObject> entry : was.entrySet()) {
            if (now.containsKey(entry.getKey())) continue;
            JsonObject item = id(entry.getKey()), old = entry.getValue();
            item.add("typeId", old.get("typeId")); item.add("name", old.get("name")); removed.add(item);
        }
        Map<String, JsonObject> oldCables = cables(before), newCables = cables(after);
        JsonArray changed = new JsonArray();
        Set<String> targets = new TreeSet<>(oldCables.keySet()); targets.addAll(newCables.keySet());
        for (String target : targets) {
            JsonObject old = oldCables.get(target), current = newCables.get(target);
            if (old != null && current != null && source(old).equals(source(current))) continue;
            JsonObject item = new JsonObject(), t = new JsonObject();
            JsonObject any = old != null ? old : current;
            t.add("moduleId", any.get("targetModuleId")); t.add("portKey", any.get("targetPortKey"));
            item.add("target", t);
            item.add("from", old == null ? JsonNull.INSTANCE : source(old));
            item.add("to", current == null ? JsonNull.INSTANCE : source(current));
            changed.add(item);
        }
        put(out, "modulesAdded", added); put(out, "modulesRemoved", removed); put(out, "modulesMoved", moved);
        put(out, "modulesRenamed", renamed); put(out, "connectionsChanged", changed);
        out.addProperty("moduleCount", now.size());
        // Parameters are never part of a topology diff; any other unsupported marker limits it.
        JsonArray limits = new JsonArray();
        if (after.has("unsupported"))
            for (JsonElement flag : after.getAsJsonArray("unsupported"))
                if (!flag.getAsString().equals("parameters_not_requested")) limits.add(flag);
        if (limits.size() > 0) { out.addProperty("graphComplete", false); out.add("graphUnsupported", limits); }
    }

    private static void put(JsonObject out, String key, JsonArray value) { if (value.size() > 0) out.add(key, value); }
    private static JsonObject id(String id) { JsonObject item = new JsonObject(); item.addProperty("moduleId", id); return item; }
    private static JsonObject position(JsonObject row) {
        JsonObject item = new JsonObject(); item.add("x", row.get("x")); item.add("y", row.get("y")); return item;
    }
    private static JsonObject source(JsonObject cable) {
        JsonObject item = new JsonObject();
        item.add("moduleId", cable.get("sourceModuleId")); item.add("portKey", cable.get("sourcePortKey")); return item;
    }
    private static Map<String, JsonObject> modules(JsonObject graph) {
        Map<String, JsonObject> rows = new LinkedHashMap<>();
        for (JsonElement row : graph.getAsJsonArray("modules"))
            rows.put(row.getAsJsonObject().get("instanceId").getAsString(), row.getAsJsonObject());
        return rows;
    }
    private static Map<String, JsonObject> cables(JsonObject graph) {
        Map<String, JsonObject> rows = new LinkedHashMap<>();
        for (JsonElement row : graph.getAsJsonArray("connections")) {
            JsonObject cable = row.getAsJsonObject();
            rows.put(cable.get("targetModuleId").getAsString() + "/" + cable.get("targetPortKey").getAsString(), cable);
        }
        return rows;
    }
}
