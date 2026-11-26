package dev.bitwigagent;

import com.google.gson.*;

/** Pure JSON shaping; no native model. */
public final class GridDiffTest {
    private static int assertions;
    private static final String A = "00000000-0000-0001-0000-000000000001",
            B = "00000000-0000-0001-0000-000000000002",
            C = "00000000-0000-0001-0000-000000000003",
            D = "00000000-0000-0001-0000-000000000004";

    public static void main(String[] args) {
        diffReportsAddedRemovedMovedRenamedAndEveryCable();
        onlyLimitsThatMatterAreFlagged();
        shapeStripsConstantProseAndHonorsFullGraph();
        System.out.println("GridDiffTest: " + assertions + " assertions passed (pure JSON only)");
    }

    private static JsonObject module(String id, int x, int y, String custom) {
        return JsonParser.parseString(
                        "{\"instanceId\":\"" + id + "\",\"localKey\":\"0\",\"name\":\"N\",\"customName\":\""
                                + custom + "\",\"x\":" + x + ",\"y\":" + y + ",\"typeId\":\"" + C
                                + "\",\"inputs\":[{\"key\":\"in\",\"connected\":false}],"
                                + "\"outputs\":[{\"key\":\"out\"}],\"parameters\":[]}")
                .getAsJsonObject();
    }

    private static JsonObject cable(String from, String to) {
        return JsonParser.parseString("{\"sourceModuleId\":\"" + from
                        + "\",\"sourcePortKey\":\"out\",\"targetModuleId\":\"" + to + "\",\"targetPortKey\":\"in\"}")
                .getAsJsonObject();
    }

    private static JsonObject graph(JsonObject[] modules, JsonObject[] cables, String... unsupported) {
        JsonObject graph = new JsonObject();
        JsonArray m = new JsonArray(), c = new JsonArray(), u = new JsonArray();
        for (JsonObject row : modules) m.add(row);
        for (JsonObject row : cables) c.add(row);
        for (String flag : unsupported) u.add(flag);
        graph.add("modules", m);
        graph.add("connections", c);
        graph.add("unsupported", u);
        return graph;
    }

    private static void diffReportsAddedRemovedMovedRenamedAndEveryCable() {
        JsonObject before = graph(
                new JsonObject[] {module(A, 0, 0, ""), module(B, 4, 0, "keep"), module(C, 8, 0, "")},
                new JsonObject[] {cable(A, B), cable(B, C)});
        // A moved, B renamed, C removed, D inserted; B's input was also rewired by native repair.
        JsonObject after = graph(
                new JsonObject[] {module(A, 2, 1, ""), module(B, 4, 0, "lead"), module(D, 12, 0, "")},
                new JsonObject[] {cable(D, B)});
        JsonObject out = new JsonObject();
        GridDiff.diff(before, after, out);
        JsonObject added = out.getAsJsonArray("modulesAdded").get(0).getAsJsonObject();
        check(
                out.getAsJsonArray("modulesAdded").size() == 1
                        && added.get("instanceId").getAsString().equals(D)
                        && added.getAsJsonArray("outputs").size() == 1
                        && !added.has("parameters"),
                "added row is full (ports included) so it can be wired at once");
        check(
                out.getAsJsonArray("modulesRemoved").size() == 1
                        && out.getAsJsonArray("modulesRemoved")
                                .get(0)
                                .getAsJsonObject()
                                .get("moduleId")
                                .getAsString()
                                .equals(C),
                "removed module id");
        JsonObject moved = out.getAsJsonArray("modulesMoved").get(0).getAsJsonObject();
        check(
                out.getAsJsonArray("modulesMoved").size() == 1
                        && moved.getAsJsonObject("from").get("x").getAsInt() == 0
                        && moved.getAsJsonObject("to").get("y").getAsInt() == 1,
                "moved module reports from/to anchors");
        JsonObject renamed = out.getAsJsonArray("modulesRenamed").get(0).getAsJsonObject();
        check(
                out.getAsJsonArray("modulesRenamed").size() == 1
                        && renamed.get("from").getAsString().equals("keep")
                        && renamed.get("to").getAsString().equals("lead"),
                "renamed module reports raw custom names");
        JsonArray cables = out.getAsJsonArray("connectionsChanged");
        check(
                cables.size() == 2,
                "native side effects anywhere in the graph appear: B's input rewired, C's input gone");
        boolean rewired = false, gone = false;
        for (JsonElement e : cables) {
            JsonObject item = e.getAsJsonObject();
            String target = item.getAsJsonObject("target").get("moduleId").getAsString();
            if (target.equals(B))
                rewired = item.getAsJsonObject("from")
                                .get("moduleId")
                                .getAsString()
                                .equals(A)
                        && item.getAsJsonObject("to")
                                .get("moduleId")
                                .getAsString()
                                .equals(D);
            if (target.equals(C))
                gone = item.get("to").isJsonNull()
                        && item.getAsJsonObject("from")
                                .get("moduleId")
                                .getAsString()
                                .equals(B);
        }
        check(
                rewired && gone && out.get("moduleCount").getAsInt() == 3,
                "cable changes carry exact from/to and module count");
        JsonObject quiet = new JsonObject();
        GridDiff.diff(before, before, quiet);
        check(quiet.entrySet().size() == 1 && quiet.has("moduleCount"), "an unchanged graph yields no change arrays");
    }

    private static void onlyLimitsThatMatterAreFlagged() {
        JsonObject empty = graph(new JsonObject[] {}, new JsonObject[] {}, "parameters_not_requested");
        JsonObject out = new JsonObject();
        GridDiff.diff(empty, empty, out);
        check(!out.has("graphComplete"), "parameters_not_requested is not a diff limitation");
        JsonObject limited = graph(
                new JsonObject[] {},
                new JsonObject[] {},
                "parameters_not_requested",
                "connection_source_outside_graph");
        out = new JsonObject();
        GridDiff.diff(empty, limited, out);
        JsonObject partialBefore = new JsonObject();
        GridDiff.diff(limited, empty, partialBefore);
        check(partialBefore.getAsJsonArray("graphUnsupported").size() == 1, "a partial baseline is flagged too");
        check(
                !out.get("graphComplete").getAsBoolean()
                        && out.getAsJsonArray("graphUnsupported").size() == 1
                        && out.getAsJsonArray("graphUnsupported")
                                .get(0)
                                .getAsString()
                                .equals("connection_source_outside_graph"),
                "coverage limits that matter stay as flags");
    }

    private static void shapeStripsConstantProseAndHonorsFullGraph() {
        JsonObject before = graph(new JsonObject[] {module(A, 0, 0, "")}, new JsonObject[] {});
        JsonObject after = graph(new JsonObject[] {module(A, 5, 0, "")}, new JsonObject[] {});
        JsonObject result = new JsonObject();
        result.add("graph", after);
        result.add("graphBefore", before);
        for (String key :
                new String[] {"nativeUndoScope", "safetyScope", "contextScope", "graphScope", "maximumModules"})
            result.addProperty(key, "constant");
        result.addProperty("matchesRequested", true);
        JsonObject copy = result.deepCopy();
        JsonObject diff = GridDiff.shape(result, false);
        check(
                !diff.has("graph")
                        && !diff.has("graphBefore")
                        && !diff.has("safetyScope")
                        && !diff.has("maximumModules")
                        && diff.get("matchesRequested").getAsBoolean()
                        && diff.getAsJsonArray("modulesMoved").size() == 1,
                "default shape keeps outcome fields and replaces the graph with the diff");
        JsonObject full = GridDiff.shape(copy, true);
        check(
                full.has("graph")
                        && !full.has("graphBefore")
                        && !full.has("modulesMoved")
                        && !full.has("nativeUndoScope"),
                "graph=full keeps the whole bounded graph but still drops constant prose");
        JsonObject bare = new JsonObject();
        bare.addProperty("x", 1);
        bare.add("graph", after);
        check(GridDiff.shape(bare, false).has("graph"), "a result without a before graph is left untouched");
    }

    private static void check(boolean condition, String label) {
        assertions++;
        if (!condition) throw new AssertionError(label);
    }
}
