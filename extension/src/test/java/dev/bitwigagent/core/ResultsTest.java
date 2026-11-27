package dev.bitwigagent.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

/** The construction rule behind every write result: an applied one cannot exist without its verification. */
final class ResultsTest {
    @Test
    void anAppliedResultNeedsItsVerificationAndOnlyOnesThatSucceedCarryIt() {
        JsonObject applied = Results.outcome(Outcome.APPLIED, Verification.API_READBACK);
        assertEquals("api_readback", applied.get("verification").getAsString());
        assertThrows(NullPointerException.class, () -> Results.outcome(Outcome.APPLIED, null));
        assertTrue(Results.outcome(Outcome.ALREADY_MATCHED, Verification.NATIVE_READBACK)
                .has("verification"));
        assertFalse(
                Results.outcome(Outcome.UNKNOWN, Verification.NATIVE_READBACK).has("verification"));
        assertFalse(Results.outcome(Outcome.SENT).has("verification"));
        JsonObject field = Results.field(null, null, Outcome.APPLIED, null, Verification.FILE_READBACK);
        assertEquals("file_readback", field.get("verification").getAsString());
    }

    @Test
    void aComposedResultRepeatsTheVerificationItsEntriesShareAndOtherwiseLeavesThemTheirOwn() {
        JsonArray same = new JsonArray();
        same.add(Results.outcome(Outcome.APPLIED, Verification.NATIVE_READBACK));
        same.add(Results.outcome(Outcome.ALREADY_MATCHED, Verification.NATIVE_READBACK));
        JsonObject top = Results.ofEntries(same);
        assertEquals("applied", top.get("outcome").getAsString());
        assertEquals("native_readback", top.get("verification").getAsString());

        JsonArray mixed = new JsonArray();
        mixed.add(Results.outcome(Outcome.APPLIED, Verification.NATIVE_READBACK));
        mixed.add(Results.outcome(Outcome.APPLIED, Verification.API_READBACK));
        assertFalse(Results.ofEntries(mixed).has("verification"));

        JsonArray stopped = new JsonArray();
        stopped.add(Results.outcome(Outcome.APPLIED, Verification.NATIVE_READBACK));
        stopped.add(Results.outcome(Outcome.UNKNOWN));
        assertEquals("unknown", Results.ofEntries(stopped).get("outcome").getAsString());
        assertFalse(Results.ofEntries(stopped).has("verification"));
    }

    @Test
    void theLeafRuleFindsAnAppliedLeafWithoutVerification() {
        JsonObject bare = new JsonObject();
        bare.addProperty("outcome", "applied");
        assertFalse(Results.leafRuleHolds(bare));
        JsonObject parent = new JsonObject();
        parent.addProperty("outcome", "applied");
        JsonArray entries = new JsonArray();
        entries.add(bare);
        parent.add("entries", entries);
        assertFalse(Results.leafRuleHolds(parent), "the entry is the leaf that lacks it");
        JsonObject ack = Results.released();
        ack.addProperty("released", "hold");
        assertTrue(Results.leafRuleHolds(ack), "ack's release is the one exception");
        assertTrue(Results.leafRuleHolds(Results.outcome(Outcome.APPLIED, Verification.FILE_READBACK)));
    }
}
