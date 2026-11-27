package dev.bitwigagent.core;

import com.google.gson.JsonObject;

/** Which host binary answered a native call; written once into a result as nativeVersion and nativeJarSha256. */
public final class NativeProvenance {
    public final String version, jarSha256;

    public NativeProvenance(String version, String jarSha256) {
        this.version = version;
        this.jarSha256 = jarSha256;
    }

    public void write(JsonObject out) {
        out.addProperty("nativeVersion", version);
        out.addProperty("nativeJarSha256", jarSha256);
    }
}
