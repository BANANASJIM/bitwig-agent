package dev.bitwigagent.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Host method metadata read from the protocol bundle generated from the Python protocol at build time. */
public final class MethodSpecs {
    public static final String RESOURCE = "/protocol.json";
    private static final int MIN_TIMEOUT_MS = 100;
    private static final Set<String> REQUIREMENTS = Set.of("projectReady", "engine");

    public static final class Spec {
        public final boolean readOnly, projectWrite, engineRequired;
        public final int defaultTimeoutMs, maxTimeoutMs;

        private Spec(boolean readOnly, Set<String> requires, int defaultTimeoutMs, int maxTimeoutMs) {
            this.readOnly = readOnly;
            this.projectWrite = requires.contains("projectReady");
            this.engineRequired = requires.contains("engine");
            this.defaultTimeoutMs = defaultTimeoutMs;
            this.maxTimeoutMs = maxTimeoutMs;
        }
    }

    private final Map<String, Spec> hostMethods;

    private MethodSpecs(Map<String, Spec> hostMethods) {
        this.hostMethods = Collections.unmodifiableMap(hostMethods);
    }

    public static MethodSpecs load() {
        try (InputStream in = MethodSpecs.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException("Protocol bundle " + RESOURCE + " is missing");
            return parse(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Protocol bundle " + RESOURCE + " is unreadable", e);
        }
    }

    public static MethodSpecs parse(Reader bundle) {
        try {
            Map<String, Spec> specs = new TreeMap<>();
            for (Map.Entry<String, JsonElement> entry : JsonParser.parseReader(bundle)
                    .getAsJsonObject()
                    .getAsJsonObject("methods")
                    .entrySet()) {
                JsonObject method = entry.getValue().getAsJsonObject();
                if (flag(method, "host")) specs.put(entry.getKey(), spec(entry.getKey(), method));
            }
            if (specs.isEmpty()) throw new IllegalStateException("no host methods");
            return new MethodSpecs(specs);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Protocol bundle " + RESOURCE + " is malformed: " + e.getMessage(), e);
        }
    }

    private static Spec spec(String name, JsonObject method) {
        Set<String> requires = new HashSet<>();
        for (JsonElement item : method.getAsJsonArray("requires")) {
            String requirement = item.getAsString();
            if (!REQUIREMENTS.contains(requirement))
                throw new IllegalStateException(name + " requires unknown " + requirement);
            requires.add(requirement);
        }
        int def = integer(method, "defaultTimeoutMs"), max = integer(method, "maxTimeoutMs");
        if (def < MIN_TIMEOUT_MS || def > max) throw new IllegalStateException(name + " has an invalid timeout range");
        return new Spec(flag(method, "readOnly"), requires, def, max);
    }

    private static boolean flag(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null
                || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isBoolean()) throw new IllegalStateException(key + " must be a boolean");
        return value.getAsBoolean();
    }

    private static int integer(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null
                || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isNumber()) throw new IllegalStateException(key + " must be an integer");
        return value.getAsBigDecimal().intValueExact();
    }

    public Set<String> methods() {
        return hostMethods.keySet();
    }

    public boolean contains(String method) {
        return hostMethods.containsKey(method);
    }

    public Spec get(String method) {
        Spec spec = hostMethods.get(method);
        if (spec == null) throw new IllegalArgumentException("Unknown host method: " + method);
        return spec;
    }
}
