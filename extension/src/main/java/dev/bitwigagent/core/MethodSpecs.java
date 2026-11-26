package dev.bitwigagent.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Host method metadata read from the protocol bundle generated from the Python protocol at build time. */
public final class MethodSpecs {
    public static final String RESOURCE = "/protocol.json";
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

    public static final class Limits {
        public final int trackWindow,
                sceneWindow,
                deviceWindow,
                sendWindow,
                remotePageSlots,
                minTimeoutMs,
                maxRequestBytes,
                maxResponseBytes,
                defaultPort,
                maxClipBeats,
                responseGraceMs;
        public final double beatGrid;

        private Limits(JsonObject object) {
            trackWindow = integer(object, "trackWindow");
            sceneWindow = integer(object, "sceneWindow");
            deviceWindow = integer(object, "deviceWindow");
            sendWindow = integer(object, "sendWindow");
            remotePageSlots = integer(object, "remotePageSlots");
            minTimeoutMs = integer(object, "minTimeoutMs");
            maxRequestBytes = integer(object, "maxRequestBytes");
            maxResponseBytes = integer(object, "maxResponseBytes");
            defaultPort = integer(object, "defaultPort");
            maxClipBeats = integer(object, "maxClipBeats");
            responseGraceMs = integer(object, "responseGraceMs");
            beatGrid = object.get("beatGrid").getAsDouble();
            if (Math.min(Math.min(trackWindow, sceneWindow), Math.min(deviceWindow, sendWindow)) < 1
                    || Math.min(remotePageSlots, minTimeoutMs) < 1
                    || Math.min(maxRequestBytes, maxResponseBytes) < 1
                    || defaultPort < 1
                    || defaultPort > 65535
                    || maxClipBeats < 1
                    || responseGraceMs < 2
                    || !(beatGrid > 0)) throw new IllegalStateException("limits must be positive");
        }
    }

    /** The wire vocabulary: protocol version, outcomes in rising severity, error codes and reason to code. */
    public static final class Vocabulary {
        public final String protocol;
        public final List<String> outcomes, errorCodes;
        public final Map<String, String> reasons;

        private Vocabulary(JsonObject root) {
            protocol = root.get("protocol").getAsString();
            outcomes = strings(root.getAsJsonArray("outcomes"));
            errorCodes = strings(root.getAsJsonArray("errorCodes"));
            Map<String, String> map = new TreeMap<>();
            for (Map.Entry<String, JsonElement> entry :
                    root.getAsJsonObject("reasons").entrySet()) {
                String code = entry.getValue().getAsString();
                if (!errorCodes.contains(code))
                    throw new IllegalStateException("reason " + entry.getKey() + " has unknown code " + code);
                map.put(entry.getKey(), code);
            }
            reasons = Collections.unmodifiableMap(map);
            if (protocol.isEmpty() || outcomes.isEmpty() || reasons.isEmpty())
                throw new IllegalStateException("vocabulary must not be empty");
        }

        private static List<String> strings(JsonArray array) {
            List<String> out = new ArrayList<>();
            for (JsonElement item : array) out.add(item.getAsString());
            return Collections.unmodifiableList(out);
        }
    }

    private final Map<String, Spec> hostMethods;
    private final Limits limits;
    private final Vocabulary vocabulary;

    private MethodSpecs(Map<String, Spec> hostMethods, Limits limits, Vocabulary vocabulary) {
        this.hostMethods = Collections.unmodifiableMap(hostMethods);
        this.limits = limits;
        this.vocabulary = vocabulary;
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
            JsonObject root = JsonParser.parseReader(bundle).getAsJsonObject();
            Limits limits = new Limits(root.getAsJsonObject("limits"));
            Vocabulary vocabulary = new Vocabulary(root);
            for (Map.Entry<String, JsonElement> entry :
                    root.getAsJsonObject("methods").entrySet()) {
                JsonObject method = entry.getValue().getAsJsonObject();
                if (flag(method, "host")) specs.put(entry.getKey(), spec(entry.getKey(), method, limits.minTimeoutMs));
            }
            if (specs.isEmpty()) throw new IllegalStateException("no host methods");
            return new MethodSpecs(specs, limits, vocabulary);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Protocol bundle " + RESOURCE + " is malformed: " + e.getMessage(), e);
        }
    }

    private static Spec spec(String name, JsonObject method, int minTimeoutMs) {
        Set<String> requires = new HashSet<>();
        for (JsonElement item : method.getAsJsonArray("requires")) {
            String requirement = item.getAsString();
            if (!REQUIREMENTS.contains(requirement))
                throw new IllegalStateException(name + " requires unknown " + requirement);
            requires.add(requirement);
        }
        int def = integer(method, "defaultTimeoutMs"), max = integer(method, "maxTimeoutMs");
        if (def < minTimeoutMs || def > max) throw new IllegalStateException(name + " has an invalid timeout range");
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

    public Limits limits() {
        return limits;
    }

    public Vocabulary vocabulary() {
        return vocabulary;
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
