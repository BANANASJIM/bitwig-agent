package dev.bitwigagent.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** The JSON facts shared with the Python package, bundled from {@code bitwig_agent/shared} at build time. */
public final class SharedData {
    private SharedData() {}

    public static JsonObject read(String name) {
        return parse(name).getAsJsonObject();
    }

    public static JsonArray readArray(String name) {
        return parse(name).getAsJsonArray();
    }

    /** The host versions the bundled native name tables map, in {@code host.json} order: "6.1.4, 6.1.7". */
    public static String hostVersions() {
        List<String> versions = new ArrayList<>();
        for (JsonElement entry : readArray("host"))
            versions.add(entry.getAsJsonObject().get("version").getAsString());
        return String.join(", ", versions);
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
