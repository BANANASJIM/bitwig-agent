package dev.bitwigagent;

import static dev.bitwigagent.RpcAssert.*;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Write values, match rules, expect and batches: the shared pieces every domain's writes use. */
public final class ValueTest {
    private static JsonElement json(String text) {
        return JsonParser.parseString(text);
    }

    @Test
    void aValueIsBareDisplayOrLabel() {
        ValueInput bare = ValueInput.parse("volume", json("0.5"));
        assertEquals(ValueInput.Form.BARE, bare.form);
        assertEquals(.5, bare.normalized("volume"));
        ValueInput display = ValueInput.parse("volume", json("{\"display\":\"-6 dB\"}"));
        assertEquals(ValueInput.Form.DISPLAY, display.form);
        assertEquals("-6 dB", display.text);
        assertEquals(ValueInput.Form.LABEL, ValueInput.parse("value", json("{\"label\":\"Bipolar\"}")).form);
        for (String wrong : List.of("{\"display\":\"\"}", "{\"display\":1}", "{\"unit\":\"dB\"}", "[0.5]"))
            assertInvalidParams(() -> ValueInput.parse("volume", json(wrong)));
        assertInvalidParams(() -> ValueInput.parse("volume", null));
        assertInvalidParams(() -> display.normalized("volume"));
        assertInvalidParams(() -> ValueInput.parse("volume", json("1.5")).normalized("volume"));
        assertInvalidParams(() -> ValueInput.parse("volume", json("\"loud\"")).normalized("volume"));
    }

    @Test
    void aReadUnitCarriesDisplayAndOptionsOnlyWhenTheWriteTakesThem() {
        assertEquals(json("{\"value\":0.5,\"display\":\"C\"}"), Results.unit(json("0.5"), "C", null));
        JsonArray options = json("[\"1/16\",\"1/8\"]").getAsJsonArray();
        assertEquals(
                json("{\"value\":\"1/16\",\"options\":[\"1/16\",\"1/8\"]}"),
                Results.unit(json("\"1/16\""), null, options));
        assertEquals(json("{\"value\":null}"), Results.unit(null, null, null));
    }

    @Test
    void readBacksMatchByTheirKindsRule() {
        assertTrue(ValueMatch.NORMALIZED.matches(json("0.5"), json(Double.toString(.5 + 1.0 / 16383))));
        assertFalse(ValueMatch.NORMALIZED.matches(json("0.5"), json(Double.toString(.5 + 2.0 / 16383))));
        assertFalse(ValueMatch.NORMALIZED.matches(json("0.5"), json("true")), "a boolean is not a number");
        assertFalse(ValueMatch.NORMALIZED.matches(json("0.5"), null));
        assertTrue(ValueMatch.raw(.01).matches(json("440"), json("440.005")));
        assertFalse(ValueMatch.raw(.01).matches(json("440"), json("440.5")));
        assertTrue(ValueMatch.EXACT.matches(json("1"), json("1.0")), "JSON numbers compare by value");
        assertFalse(ValueMatch.EXACT.matches(json("1"), json("true")));
        assertTrue(ValueMatch.EXACT.matches(json("{\"kind\":\"none\"}"), json("{\"kind\":\"none\"}")));
    }

    @Test
    void expectRefusesWithTheObservedValuesThatDiffer() {
        Map<String, ValueMatch> rules = Map.of("volume", ValueMatch.NORMALIZED, "mute", ValueMatch.EXACT);
        JsonObject now = json("{\"volume\":0.5,\"mute\":false}").getAsJsonObject();
        ValueMatch.requireExpected(json("{\"volume\":0.5}").getAsJsonObject(), now, rules);
        RpcException stale = assertRpcError(
                "target_changed",
                () -> ValueMatch.requireExpected(
                        json("{\"volume\":0.5,\"mute\":true}").getAsJsonObject(), now, rules));
        assertEquals(json("{\"mute\":false}"), stale.observed);
        assertEquals(json("{\"mute\":false}"), Errors.error(stale).get("observed"));
        RpcException missing = assertRpcError(
                "target_changed",
                () -> ValueMatch.requireExpected(json("{\"mute\":true}").getAsJsonObject(), new JsonObject(), rules));
        assertEquals(JsonNull.INSTANCE, missing.observed.get("mute"));
    }

    @Test
    void aBatchChecksEveryEntryFirstThenStopsAtTheFirstFailure() {
        RpcException refused = assertRpcError(
                "target_not_found",
                () -> Batch.checked(List.of(
                        () -> {},
                        () -> {
                            throw Errors.notApplied(Reason.TARGET_NOT_FOUND, "gone");
                        },
                        () -> {
                            throw Errors.targetChanged(
                                    "moved", json("{\"kind\":\"marker\"}").getAsJsonObject());
                        })));
        JsonArray entries = Errors.error(refused).getAsJsonArray("entries");
        assertEquals(2, entries.size(), "only the failing entries are listed");
        assertEquals(1, entries.get(0).getAsJsonObject().get("index").getAsInt());
        assertEquals(
                "target_changed", entries.get(1).getAsJsonObject().get("reason").getAsString());
        assertEquals(
                "marker",
                entries.get(1)
                        .getAsJsonObject()
                        .getAsJsonObject("current")
                        .get("kind")
                        .getAsString());

        Batch batch = Batch.checked(List.of(() -> {}, () -> {}, () -> {}, () -> {}));
        assertTrue(batch.record(Results.outcome(Outcome.APPLIED)));
        assertTrue(batch.record(Results.outcome(Outcome.SENT)), "sent continues");
        assertFalse(batch.record(Results.outcome(Outcome.UNKNOWN)), "unknown stops");
        JsonArray results = batch.entries();
        assertEquals(4, results.size());
        assertEquals(
                "not_reached", results.get(3).getAsJsonObject().get("reason").getAsString());
        assertEquals(
                "not_applied", results.get(3).getAsJsonObject().get("outcome").getAsString());
    }
}
