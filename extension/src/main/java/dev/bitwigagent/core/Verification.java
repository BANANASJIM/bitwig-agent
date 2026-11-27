package dev.bitwigagent.core;

import com.google.gson.JsonObject;
import java.util.Locale;

/** How a result was confirmed: read back from the native model, through public API observers, or from files. */
public enum Verification {
    NATIVE_READBACK,
    API_READBACK,
    FILE_READBACK;

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    public void put(JsonObject result) {
        result.addProperty("verification", wire());
    }

    /** Stamps a write result whose outcome is {@code applied}: an applied result always says how it was confirmed. */
    public void putIfApplied(JsonObject result) {
        if (Results.outcomeOf(result) == Outcome.APPLIED) put(result);
    }
}
