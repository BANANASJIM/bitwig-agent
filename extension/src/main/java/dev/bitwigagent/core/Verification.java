package dev.bitwigagent.core;

import com.google.gson.JsonObject;
import java.util.Locale;

/** How a result was confirmed: read back from the native model, through public API observers, from files, or from the operating system. */
public enum Verification {
    NATIVE_READBACK,
    API_READBACK,
    FILE_READBACK,
    SYSTEM_READBACK;

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Only {@link Results} writes the key, so an applied result cannot be built without one. */
    void put(JsonObject result) {
        result.addProperty("verification", wire());
    }
}
