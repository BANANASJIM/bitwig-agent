package dev.bitwigagent;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

/** Request-building helpers the module tests share. */
public final class TestJson {
    private TestJson() {}

    public static JsonObject obj(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    public static JsonElement toJson(Object value) {
        if (value instanceof JsonElement) return (JsonElement) value;
        if (value instanceof Number) return new JsonPrimitive((Number) value);
        if (value instanceof Boolean) return new JsonPrimitive((Boolean) value);
        return new JsonPrimitive(String.valueOf(value));
    }

    /** The object of alternating key and value arguments. */
    public static JsonObject params(Object... pairs) {
        JsonObject p = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2) p.add((String) pairs[i], toJson(pairs[i + 1]));
        return p;
    }
}
