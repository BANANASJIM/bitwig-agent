package dev.bitwigagent.nativehost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import dev.bitwigagent.core.Outcome;
import dev.bitwigagent.core.Results;
import org.junit.jupiter.api.Test;

/** The per-chooser field that tracks.routing.set and devices.sidechain.set report, built by the real row builder. */
final class NativeRoutingRowTest {
    private static JsonObject row(Outcome outcome) {
        return NativeRoutingAccess.row(outcome, new JsonObject(), new JsonObject(), new JsonObject());
    }

    @Test
    void anAppliedChooserFieldSaysItWasReadBackNatively() {
        JsonObject fields = new JsonObject();
        fields.add("output", row(Outcome.APPLIED));
        JsonObject result = new JsonObject();
        result.addProperty("outcome", "applied");
        result.addProperty("verification", "native_readback");
        result.add("fields", fields);
        assertTrue(Results.leafRuleHolds(result), result.toString());
        assertEquals(
                "native_readback",
                fields.getAsJsonObject("output").get("verification").getAsString());
    }

    @Test
    void aMismatchedChooserIsUnknownWithoutVerification() {
        JsonObject unknown = row(Outcome.UNKNOWN);
        assertEquals("readback_mismatch", unknown.get("reason").getAsString());
        assertFalse(unknown.has("verification"));
    }
}
