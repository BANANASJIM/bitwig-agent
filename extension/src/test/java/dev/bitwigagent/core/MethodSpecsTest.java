package dev.bitwigagent.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.TreeSet;

/** The bundle generated from the Python protocol must reproduce the method tables Java used to hard-code. */
public final class MethodSpecsTest {
    public static final Set<String> PROJECT_WRITES = Set.of(
            "tracks.create",
            "devices.insert",
            "launcher.create",
            "launcher.import",
            "scenes.append",
            "scenes.duplicate",
            "scenes.delete",
            "grid.voicing.set",
            "devices.delete",
            "devices.move",
            "devices.layers.insert",
            "tracks.group",
            "tracks.delete",
            "arranger.clips.create",
            "arranger.clips.copyFromLauncher",
            "arranger.clips.edit",
            "grid.modules.insert",
            "grid.modules.move",
            "grid.modules.delete",
            "grid.modules.rename",
            "grid.connections.set",
            "grid.parameters.set",
            "tracks.routing.set",
            "devices.sidechain.set");
    public static final Set<String> ENGINE_REQUIRED = Set.of("launcher.launch", "scenes.launch");
    public static final Set<String> LONG_TIMEOUT = Set.of(
            "grid.modules.insert",
            "grid.modules.move",
            "grid.modules.delete",
            "grid.modules.rename",
            "grid.connections.set",
            "grid.topology.reconcile",
            "project.saveAs",
            "export.audio",
            "bounce.inPlace");
    public static final Set<String> NON_READ_ONLY_ROUTING_AND_MODULATOR = Set.of(
            "tracks.routing.set",
            "devices.sidechain.set",
            "modulators.insert",
            "modulators.delete",
            "modulators.parameters.set",
            "modulators.map",
            "modulators.unmap");
    private static final int DEFAULT_CEILING_MS = 10000, LONG_CEILING_MS = 60000;
    private static int checks;

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    public static JsonObject bundle() {
        try (Reader in = new InputStreamReader(
                MethodSpecs.class.getResourceAsStream(MethodSpecs.RESOURCE), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(in).getAsJsonObject();
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    public static JsonObject method(boolean host, boolean readOnly, String requires, int def, int max) {
        return JsonParser.parseString("{\"host\":" + host + ",\"readOnly\":" + readOnly + ",\"requires\":[" + requires
                        + "],\"defaultTimeoutMs\":" + def + ",\"maxTimeoutMs\":" + max + "}")
                .getAsJsonObject();
    }

    private static MethodSpecs parse(JsonObject bundle) {
        return MethodSpecs.parse(new StringReader(bundle.toString()));
    }

    private static JsonObject one(String name, JsonObject method) {
        JsonObject methods = new JsonObject(), root = new JsonObject();
        methods.add(name, method);
        root.add("methods", methods);
        return root;
    }

    private static void malformed(String text, String message) {
        try {
            MethodSpecs.parse(new StringReader(text));
        } catch (IllegalStateException expected) {
            check(expected.getMessage().contains("malformed"), message + ": " + expected.getMessage());
            return;
        }
        throw new AssertionError(message + " was accepted");
    }

    private static Set<String> where(MethodSpecs specs, java.util.function.Predicate<MethodSpecs.Spec> predicate) {
        Set<String> names = new TreeSet<>();
        for (String name : specs.methods()) if (predicate.test(specs.get(name))) names.add(name);
        return names;
    }

    public static void main(String[] args) {
        MethodSpecs specs = MethodSpecs.load();
        check(
                where(specs, s -> s.projectWrite).equals(new TreeSet<>(PROJECT_WRITES)),
                "project writes match the former HostBridge.PROJECT_WRITES");
        check(
                where(specs, s -> s.engineRequired).equals(new TreeSet<>(ENGINE_REQUIRED)),
                "engine-required methods match the former launch checks");
        check(
                where(specs, s -> s.maxTimeoutMs > DEFAULT_CEILING_MS).equals(new TreeSet<>(LONG_TIMEOUT)),
                "long-timeout methods match the former LONG sets, SAVE_AS and topology edits");
        for (String name : specs.methods()) {
            MethodSpecs.Spec spec = specs.get(name);
            check(
                    spec.maxTimeoutMs == (LONG_TIMEOUT.contains(name) ? LONG_CEILING_MS : DEFAULT_CEILING_MS),
                    name + " keeps the former timeout ceiling");
            check(spec.defaultTimeoutMs <= spec.maxTimeoutMs, name + " default stays within its ceiling");
        }
        check(
                where(specs, s -> !s.readOnly).containsAll(NON_READ_ONLY_ROUTING_AND_MODULATOR),
                "routing and modulator writes are not read-only");
        check(
                specs.get("tracks.routing.get").readOnly
                        && specs.get("modulators.get").readOnly
                        && specs.get("status").readOnly,
                "routing and modulator reads are read-only");
        check(
                !specs.get("grid.topology.reconcile").projectWrite && !specs.get("grid.topology.reconcile").readOnly,
                "reconcile is long-running and not read-only, but is not a project structure write");
        check(
                specs.contains("project.saveAs")
                        && specs.get("project.saveAs").defaultTimeoutMs == 30000
                        && specs.get("status").defaultTimeoutMs == 5000,
                "defaults come from the spec");
        check(!specs.contains("grid.apply") && !specs.contains("audio.capture"), "client-side methods are not host");
        try {
            specs.get("unknown.method");
            throw new AssertionError("unknown method resolved");
        } catch (IllegalArgumentException expected) {
            checks++;
        }

        malformed("", "empty bundle");
        malformed("{", "truncated bundle");
        malformed("{}", "missing methods");
        malformed("{\"methods\":{}}", "no host methods");
        malformed(one("a", method(false, true, "", 5000, 10000)).toString(), "only client-side methods");
        malformed(one("a", method(true, true, "\"flying\"", 5000, 10000)).toString(), "unknown requirement");
        malformed(one("a", method(true, true, "", 50, 10000)).toString(), "default below the minimum");
        malformed(one("a", method(true, true, "", 20000, 10000)).toString(), "default above the ceiling");
        JsonObject text = one("a", method(true, true, "", 5000, 10000));
        text.getAsJsonObject("methods").getAsJsonObject("a").addProperty("readOnly", "true");
        malformed(text.toString(), "string flag");
        JsonObject fraction = one("a", method(true, true, "", 5000, 10000));
        fraction.getAsJsonObject("methods").getAsJsonObject("a").addProperty("maxTimeoutMs", 10000.5);
        malformed(fraction.toString(), "fractional timeout");
        check(parse(one("a", method(true, true, "\"engine\"", 5000, 10000))).get("a").engineRequired, "minimal bundle");
        System.out.println("PASS: " + checks + " method-spec assertions.");
    }
}
