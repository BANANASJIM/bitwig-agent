package dev.bitwigagent;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.bitwigagent.core.MethodSpecs;
import java.io.StringReader;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

/** The wire vocabulary and the one place failures become errors or unknown results. */
public final class ErrorsTest {
    @Test
    void javaVocabularyEqualsTheBundle() {
        MethodSpecs.Vocabulary vocabulary = MethodSpecs.load().vocabulary();
        assertDoesNotThrow(() -> Reason.check(vocabulary));
        assertEquals(ErrorCode.STALE, Reason.STALE_TARGET.code());
        assertEquals(ErrorCode.PENDING, Reason.UNRESOLVED.code());
        assertEquals(ErrorCode.INCOMPATIBLE, Reason.PROTOCOL_MISMATCH.code());
    }

    @Test
    void aBundleThatDisagreesFailsTheCheck() {
        JsonObject bundle = bundleJson();
        bundle.getAsJsonObject("reasons").remove("busy");
        assertThrows(IllegalStateException.class, () -> Reason.check(parse(bundle)), "a missing reason");
        bundle.getAsJsonObject("reasons").addProperty("idle", "pending");
        assertThrows(IllegalStateException.class, () -> Reason.check(parse(bundle)), "a renamed reason");
        JsonObject reordered = bundleJson();
        reordered.add(
                "outcomes",
                JsonParser.parseString("[\"applied\",\"already_matched\",\"sent\",\"not_applied\",\"unknown\"]"));
        assertThrows(IllegalStateException.class, () -> Reason.check(parse(reordered)), "severity order matters");
    }

    private static JsonObject bundleJson() {
        try (java.io.InputStream in = MethodSpecs.class.getResourceAsStream(MethodSpecs.RESOURCE)) {
            return JsonParser.parseReader(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    private static MethodSpecs.Vocabulary parse(JsonObject bundle) {
        return MethodSpecs.parse(new StringReader(bundle.toString())).vocabulary();
    }

    @Test
    void outcomesRiseInSeverity() {
        assertEquals(Outcome.UNKNOWN, Outcome.APPLIED.worst(Outcome.UNKNOWN));
        assertEquals(Outcome.APPLIED, Outcome.ALREADY_MATCHED.worst(Outcome.APPLIED));
        assertEquals(Outcome.NOT_APPLIED, Outcome.NOT_APPLIED.worst(Outcome.SENT));
        assertTrue(Outcome.SENT.succeeded() && Outcome.ALREADY_MATCHED.succeeded());
        assertFalse(Outcome.NOT_APPLIED.succeeded() || Outcome.UNKNOWN.succeeded());
        assertEquals("already_matched", Outcome.ALREADY_MATCHED.wire());
    }

    @Test
    void errorsSerializeCodeReasonMessageAndNext() {
        JsonObject plain = Errors.error(Errors.notApplied(Reason.BUSY, "m"));
        assertEquals("{\"code\":\"pending\",\"reason\":\"busy\",\"message\":\"m\"}", plain.toString());
        JsonObject next = Errors.error(Errors.notApplied(Reason.UNRESOLVED, "m", "ack x"));
        assertEquals("ack x", next.get("next").getAsString());
        RpcException invalid = Errors.invalid("bad");
        assertTrue(invalid.is(Reason.INVALID_PARAMS)
                && !invalid.unknown
                && invalid.code().equals("invalid_params"));
    }

    @Test
    void unknownFailuresSerializeAsResults() {
        RpcException unknown = Errors.unknown(Reason.NOT_OBSERVED, "m");
        assertTrue(unknown.unknown && Errors.of(Reason.DEADLINE, "m", true).unknown);
        assertFalse(Errors.of(Reason.DEADLINE, "m", false).unknown);
        assertEquals(
                "{\"outcome\":\"unknown\",\"reason\":\"not_observed\",\"message\":\"m\"}",
                Errors.unknownResult(unknown).toString());
        assertEquals(
                "n",
                Errors.unknownResult(Errors.notApplied(Reason.BUSY, "m", "n"))
                        .get("next")
                        .getAsString());
    }

    @Test
    void aFailureBeforeDispatchIsAnErrorAndAfterItAnUnknownResult() {
        HostOperation before = new HostOperation(Long.MAX_VALUE);
        before.fail(Errors.notApplied(Reason.STALE_TARGET, "moved"));
        CompletionException error = assertThrows(CompletionException.class, before.result::join);
        assertTrue(((RpcException) error.getCause()).is(Reason.STALE_TARGET));

        HostOperation internal = new HostOperation(Long.MAX_VALUE);
        internal.fail(new IllegalStateException("boom"));
        assertTrue(((RpcException) assertThrows(CompletionException.class, internal.result::join)
                        .getCause())
                .is(Reason.INTERNAL));

        HostOperation after = new HostOperation(Long.MAX_VALUE);
        after.markDispatched();
        after.fail(new IllegalStateException("boom"));
        JsonObject result = after.result.join();
        assertEquals("unknown", result.get("outcome").getAsString());
        assertEquals("internal", result.get("reason").getAsString());

        HostOperation job = new HostOperation(Long.MAX_VALUE);
        job.markDispatched();
        job.job = "render:1";
        job.fail(Errors.notApplied(Reason.DEADLINE, "late"));
        assertEquals("render:1", job.result.join().get("job").getAsString(), "an unknown result names its job");

        HostOperation expiring = new HostOperation(Long.MAX_VALUE);
        assertTrue(((RpcServer.Expiring) expiring.result).expire(), "an undispatched operation expires");
        assertTrue(((RpcException) assertThrows(CompletionException.class, expiring.result::join)
                        .getCause())
                .is(Reason.DEADLINE));
        assertThrows(RpcException.class, expiring::markDispatched, "an expired operation never dispatches");
        assertFalse(expiring.dispatched());
        assertFalse(((RpcServer.Expiring) job.result).expire(), "a dispatched operation does not expire");

        HostOperation flagged = new HostOperation(Long.MAX_VALUE);
        flagged.fail(Errors.unknown(Reason.READBACK_MISMATCH, "differs"));
        assertEquals("readback_mismatch", flagged.result.join().get("reason").getAsString());
    }
}
