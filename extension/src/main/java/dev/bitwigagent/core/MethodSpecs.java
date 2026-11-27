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
        public final SchemaValidator params, timeout;
        /** The read method an unknown result of this write points at; null for a read. */
        public final String inspect;

        private Spec(
                boolean readOnly,
                Set<String> requires,
                int defaultTimeoutMs,
                int maxTimeoutMs,
                SchemaValidator params,
                SchemaValidator timeout,
                String inspect) {
            this.inspect = inspect;
            this.readOnly = readOnly;
            this.projectWrite = requires.contains("projectReady");
            this.engineRequired = requires.contains("engine");
            this.defaultTimeoutMs = defaultTimeoutMs;
            this.maxTimeoutMs = maxTimeoutMs;
            this.params = params;
            this.timeout = timeout;
        }
    }

    public static final class Limits {
        public final int trackWindow,
                sceneWindow,
                deviceWindow,
                sendWindow,
                remotePageSlots,
                parameterResolution,
                minTimeoutMs,
                maxRequestBytes,
                maxResponseBytes,
                defaultPort,
                maxClipBeats,
                responseGraceMs;
        public final double beatGrid, durationResolution, normalizedTolerance;

        private Limits(JsonObject object) {
            trackWindow = integer(object, "trackWindow");
            sceneWindow = integer(object, "sceneWindow");
            deviceWindow = integer(object, "deviceWindow");
            sendWindow = integer(object, "sendWindow");
            remotePageSlots = integer(object, "remotePageSlots");
            parameterResolution = integer(object, "parameterResolution");
            minTimeoutMs = integer(object, "minTimeoutMs");
            maxRequestBytes = integer(object, "maxRequestBytes");
            maxResponseBytes = integer(object, "maxResponseBytes");
            defaultPort = integer(object, "defaultPort");
            maxClipBeats = integer(object, "maxClipBeats");
            responseGraceMs = integer(object, "responseGraceMs");
            beatGrid = object.get("beatGrid").getAsDouble();
            durationResolution = object.get("durationResolution").getAsDouble();
            normalizedTolerance = object.get("normalizedTolerance").getAsDouble();
            if (Math.min(Math.min(trackWindow, sceneWindow), Math.min(deviceWindow, sendWindow)) < 1
                    || Math.min(remotePageSlots, minTimeoutMs) < 1
                    || parameterResolution < 2
                    || Math.min(maxRequestBytes, maxResponseBytes) < 1
                    || defaultPort < 1
                    || defaultPort > 65535
                    || maxClipBeats < 1
                    || responseGraceMs < 2
                    || !(beatGrid > 0)
                    || !(durationResolution > 0)
                    || !(normalizedTolerance > 0)) throw new IllegalStateException("limits must be positive");
        }
    }

    /** The wire vocabulary: protocol version, outcomes in rising severity, error codes, verifications, reason to code. */
    public static final class Vocabulary {
        public final String protocol;
        public final List<String> outcomes, errorCodes, verifications;
        public final Map<String, String> reasons;

        private Vocabulary(JsonObject root) {
            protocol = root.get("protocol").getAsString();
            outcomes = strings(root.getAsJsonArray("outcomes"));
            errorCodes = strings(root.getAsJsonArray("errorCodes"));
            verifications = strings(root.getAsJsonArray("verifications"));
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

    /** How a ref kind's fields are used: locate finds the object, compared must match, targetOnly when written. */
    public static final class RefRoles {
        public final List<String> locate, compared, targetOnly;

        private RefRoles(JsonObject object) {
            locate = Vocabulary.strings(object.getAsJsonArray("locate"));
            compared = Vocabulary.strings(object.getAsJsonArray("compared"));
            targetOnly = Vocabulary.strings(object.getAsJsonArray("targetOnly"));
            if (locate.isEmpty()) throw new IllegalStateException("a ref kind needs a locating field");
        }
    }

    private final Map<String, Spec> hostMethods;
    private final Limits limits;
    private final Vocabulary vocabulary;
    private final Map<String, RefRoles> refRoles;
    private final Map<String, String> refReads;
    private final List<String> parameterOwners;

    private MethodSpecs(Map<String, Spec> hostMethods, Limits limits, Vocabulary vocabulary, JsonObject refs) {
        this.hostMethods = Collections.unmodifiableMap(hostMethods);
        this.limits = limits;
        this.vocabulary = vocabulary;
        Map<String, RefRoles> roles = new TreeMap<>();
        for (Map.Entry<String, JsonElement> entry :
                refs.getAsJsonObject("roles").entrySet())
            roles.put(entry.getKey(), new RefRoles(entry.getValue().getAsJsonObject()));
        Map<String, String> reads = new TreeMap<>();
        for (Map.Entry<String, JsonElement> entry :
                refs.getAsJsonObject("reads").entrySet())
            reads.put(entry.getKey(), entry.getValue().getAsString());
        if (roles.isEmpty() || reads.isEmpty()) throw new IllegalStateException("no ref kinds");
        this.refRoles = Collections.unmodifiableMap(roles);
        this.refReads = Collections.unmodifiableMap(reads);
        this.parameterOwners = Vocabulary.strings(refs.getAsJsonArray("parameterOwners"));
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
            return new MethodSpecs(specs, limits, vocabulary, root.getAsJsonObject("refs"));
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
        JsonElement inspect = method.get("inspect");
        return new Spec(
                flag(method, "readOnly"),
                requires,
                def,
                max,
                paramsSchema(name, method),
                timeoutSchema(name, method),
                inspect == null || inspect.isJsonNull() ? null : inspect.getAsString());
    }

    private static JsonObject properties(String name, JsonObject method) {
        JsonObject schema = method.getAsJsonObject("inputSchema");
        if (schema == null
                || !schema.has("properties")
                || !schema.get("properties").isJsonObject())
            throw new IllegalStateException(name + " inputSchema has no properties");
        return schema.getAsJsonObject("properties");
    }

    private static SchemaValidator compileSchema(String name, JsonObject schema, String label) {
        try {
            return SchemaValidator.compile(schema, label);
        } catch (IllegalStateException e) {
            throw new IllegalStateException(name + " inputSchema: " + e.getMessage(), e);
        }
    }

    /** timeoutMs travels in the request envelope, so it is not part of the params schema. */
    private static SchemaValidator paramsSchema(String name, JsonObject method) {
        properties(name, method);
        JsonObject schema = method.getAsJsonObject("inputSchema").deepCopy();
        schema.getAsJsonObject("properties").remove("timeoutMs");
        return compileSchema(name, schema, "params");
    }

    /** The envelope's timeoutMs is checked by the schema's own node for it. */
    private static SchemaValidator timeoutSchema(String name, JsonObject method) {
        JsonElement node = properties(name, method).get("timeoutMs");
        if (node == null) throw new IllegalStateException(name + " inputSchema has no timeoutMs");
        JsonObject properties = new JsonObject();
        properties.add("timeoutMs", node.deepCopy());
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.addProperty("additionalProperties", false);
        schema.add("properties", properties);
        return compileSchema(name, schema, "request");
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

    public Map<String, RefRoles> refRoles() {
        return refRoles;
    }

    /** By variant tag (see {@link Refs#tag}), the read method that returns refs of that variant. */
    public Map<String, String> refReads() {
        return refReads;
    }

    public List<String> parameterOwners() {
        return parameterOwners;
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
