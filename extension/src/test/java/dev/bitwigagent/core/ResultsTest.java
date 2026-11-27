package dev.bitwigagent.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        assertTrue(Results.outcome(Outcome.ALREADY_MATCHED, Verification.NATIVE_READBACK)
                .has("verification"));
        assertFalse(
                Results.outcome(Outcome.UNKNOWN, Verification.NATIVE_READBACK).has("verification"));
        assertFalse(Results.sent().has("verification"));
        assertEquals("already_matched", Results.alreadyMatched().get("outcome").getAsString());
        JsonObject field = Results.field(null, null, Outcome.APPLIED, null, Verification.FILE_READBACK);
        assertEquals("file_readback", field.get("verification").getAsString());
    }

    @Test
    void aComposedResultRepeatsTheVerificationItsEntriesShareAndOtherwiseLeavesThemTheirOwn() {
        JsonArray same = new JsonArray();
        same.add(Results.outcome(Outcome.APPLIED, Verification.NATIVE_READBACK));
        same.add(Results.outcome(Outcome.ALREADY_MATCHED, Verification.API_READBACK));
        JsonObject top = Results.ofEntries(same);
        assertEquals("applied", top.get("outcome").getAsString());
        assertEquals("native_readback", top.get("verification").getAsString());

        JsonArray mixed = new JsonArray();
        mixed.add(Results.outcome(Outcome.APPLIED, Verification.NATIVE_READBACK));
        mixed.add(Results.outcome(Outcome.APPLIED, Verification.API_READBACK));
        assertFalse(Results.ofEntries(mixed).has("verification"));

        JsonArray stopped = new JsonArray();
        stopped.add(Results.outcome(Outcome.APPLIED, Verification.NATIVE_READBACK));
        stopped.add(Results.unknown());
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

    @Test
    void aResultMarkedUnknownOrNotAppliedLosesTheVerificationItCarried() {
        JsonObject entry = Results.outcome(Outcome.APPLIED, Verification.API_READBACK);
        assertFalse(Results.unknown(entry).has("verification"));
        assertEquals("unknown", entry.get("outcome").getAsString());
        assertFalse(Results.notApplied(Results.outcome(Outcome.APPLIED, Verification.API_READBACK))
                .has("verification"));
        JsonObject field = Results.unknownField(null, null, Reason.NOT_OBSERVED);
        assertEquals("not_observed", field.get("reason").getAsString());
        assertFalse(field.has("verification"));
        assertEquals(
                "not_applied",
                Results.notAppliedField(null, null, Reason.NOT_REACHED)
                        .get("outcome")
                        .getAsString());
    }

    @Test
    void aCopiedEntryKeepsTheVerificationOfItsWrite() {
        JsonObject entry = new JsonObject();
        Results.copyOutcome(entry, Results.outcome(Outcome.APPLIED, Verification.NATIVE_READBACK));
        assertTrue(Results.leafRuleHolds(entry));
        Results.copyOutcome(entry, Results.unknown());
        assertFalse(entry.has("verification"));
        assertEquals(
                "file_readback",
                Results.readBy(new JsonObject(), Verification.FILE_READBACK)
                        .get("verification")
                        .getAsString());
    }
}
