package dev.bitwigagent.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** The JSON facts shared with the Python package, bundled from {@code bitwig_agent/shared} at build time. */
public final class SharedData {
    private SharedData() {}

    public static JsonObject read(String name) {
        return parse(name).getAsJsonObject();
    }

    public static JsonArray readArray(String name) {
        return parse(name).getAsJsonArray();
    }

    private static JsonElement parse(String name) {
        String resource = "/shared/" + name + ".json";
        try (InputStream in = SharedData.class.getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("Shared data " + resource + " is missing");
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Shared data " + resource + " is unreadable: " + e.getMessage(), e);
        }
    }
}
