package dev.bitwigagent.core;

import com.google.gson.JsonObject;
import java.io.InputStream;
import java.util.Properties;

/** Build identity written into the .bwextension by scripts/build.py; "unknown" when absent. */
public final class BuildInfo {
    static final String RESOURCE = "/bitwig-agent-build.properties";
    public final String version, build;

    BuildInfo(String version, String build) {
        this.version = version;
        this.build = build;
    }

    public static BuildInfo load() {
        Properties p = new Properties();
        try (InputStream in = BuildInfo.class.getResourceAsStream(RESOURCE)) {
            if (in != null) p.load(in);
        } catch (Exception ignored) {
        }
        return new BuildInfo(p.getProperty("version", "unknown"), p.getProperty("build", "unknown"));
    }

    public JsonObject toJson() {
        JsonObject result = new JsonObject();
        result.addProperty("version", version);
        result.addProperty("build", build);
        return result;
    }
}
