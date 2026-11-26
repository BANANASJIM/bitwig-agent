package dev.bitwigagent.core;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/** Structural checks on the bundle generated from the Python protocol. */
public final class MethodSpecsTest {
    public static final Set<String> NON_READ_ONLY_ROUTING_AND_MODULATOR = Set.of(
            "tracks.routing.set",
            "devices.sidechain.set",
            "modulators.insert",
            "modulators.delete",
            "modulators.parameters.set",
            "modulators.map",
            "modulators.unmap");

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
                        + "],\"defaultTimeoutMs\":" + def + ",\"maxTimeoutMs\":" + max
                        + ",\"inputSchema\":{\"type\":\"object\",\"properties\":{\"timeoutMs\":{\"type\":\"integer\",\"minimum\":100,\"maximum\":"
                        + max + ",\"default\":" + def + "}},\"additionalProperties\":false}}")
                .getAsJsonObject();
    }

    private static MethodSpecs parse(JsonObject bundle) {
        return MethodSpecs.parse(new StringReader(bundle.toString()));
    }

    private static JsonObject one(String name, JsonObject method) {
        JsonObject methods = new JsonObject(), root = new JsonObject();
        methods.add(name, method);
        root.add("methods", methods);
        JsonObject full = bundle();
        for (String key : new String[] {"limits", "protocol", "outcomes", "errorCodes", "reasons"})
            root.add(key, full.get(key));
        return root;
    }

    private static void malformed(String text, String message) {
        IllegalStateException expected =
                assertThrows(IllegalStateException.class, () -> MethodSpecs.parse(new StringReader(text)), message);
        assertTrue(expected.getMessage().contains("malformed"), message + ": " + expected.getMessage());
    }

    private static Set<String> where(MethodSpecs specs, java.util.function.Predicate<MethodSpecs.Spec> predicate) {
        Set<String> names = new TreeSet<>();
        for (String name : specs.methods()) if (predicate.test(specs.get(name))) names.add(name);
        return names;
    }

    @Test
    void bundleCarriesTheMethodFlags() {
        MethodSpecs specs = MethodSpecs.load();
        for (String name : specs.methods()) {
            MethodSpecs.Spec spec = specs.get(name);
            assertTrue(spec.defaultTimeoutMs <= spec.maxTimeoutMs, name + " default stays within its ceiling");
        }
        assertTrue(
                where(specs, s -> !s.readOnly).containsAll(NON_READ_ONLY_ROUTING_AND_MODULATOR),
                "routing and modulator writes are not read-only");
        assertTrue(
                specs.get("tracks.routing.get").readOnly
                        && specs.get("modulators.get").readOnly
                        && specs.get("status").readOnly,
                "routing and modulator reads are read-only");
        assertTrue(
                !specs.get("ack").projectWrite && !specs.get("ack").readOnly && specs.get("jobs.get").readOnly,
                "ack writes no project structure; jobs.get only reads");
        assertTrue(
                specs.contains("project.saveAs")
                        && specs.get("project.saveAs").defaultTimeoutMs == 30000
                        && specs.get("status").defaultTimeoutMs == 5000,
                "defaults come from the spec");
        assertTrue(
                !specs.contains("grid.apply") && !specs.contains("audio.capture"), "client-side methods are not host");
        assertThrows(IllegalArgumentException.class, () -> specs.get("unknown.method"));
    }

    @Test
    void malformedBundlesAreRejected() {
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
        JsonObject noLimits = one("a", method(true, true, "", 5000, 10000));
        noLimits.remove("limits");
        malformed(noLimits.toString(), "missing limits");
        JsonObject noProtocol = one("a", method(true, true, "", 5000, 10000));
        noProtocol.remove("protocol");
        malformed(noProtocol.toString(), "missing protocol");
        JsonObject strayCode = one("a", method(true, true, "", 5000, 10000));
        strayCode.getAsJsonObject("reasons").addProperty("busy", "nonexistent");
        malformed(strayCode.toString(), "reason with an unknown code");
        JsonObject noReasons = one("a", method(true, true, "", 5000, 10000));
        noReasons.add("reasons", new JsonObject());
        malformed(noReasons.toString(), "empty reasons");
        JsonObject emptyWindow = one("a", method(true, true, "", 5000, 10000));
        emptyWindow.getAsJsonObject("limits").addProperty("trackWindow", 0);
        malformed(emptyWindow.toString(), "empty track window");
        assertTrue(
                parse(one("a", method(true, true, "\"engine\"", 5000, 10000))).get("a").engineRequired,
                "minimal bundle");
    }

    @Test
    void vocabularyComesFromTheBundle() {
        MethodSpecs.Vocabulary vocabulary = MethodSpecs.load().vocabulary();
        assertEquals(bundle().get("protocol").getAsString(), vocabulary.protocol);
        assertEquals("unknown", vocabulary.outcomes.get(vocabulary.outcomes.size() - 1), "worst outcome last");
        assertEquals("stale", vocabulary.reasons.get("stale_target"));
        assertTrue(vocabulary.errorCodes.containsAll(vocabulary.reasons.values()));
    }

    @Test
    void limitsComeFromTheBundle() {
        MethodSpecs.Limits limits = MethodSpecs.load().limits();
        assertTrue(
                limits.trackWindow
                                == bundle().getAsJsonObject("limits")
                                        .get("trackWindow")
                                        .getAsInt()
                        && limits.sceneWindow > 0,
                "limits come from the bundle");
    }
}
