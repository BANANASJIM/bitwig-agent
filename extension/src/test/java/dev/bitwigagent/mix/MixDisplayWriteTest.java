package dev.bitwigagent.mix;

import static dev.bitwigagent.RpcAssert.*;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import dev.bitwigagent.FakeHost;
import dev.bitwigagent.HostFixture;
import dev.bitwigagent.core.DisplaySolver;
import dev.bitwigagent.core.HostOperation;
import dev.bitwigagent.core.Refs;
import dev.bitwigagent.core.RpcException;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Send writes and display-addressed volume/pan against a simulated host whose display text follows a known (to the test only) curve. */
public final class MixDisplayWriteTest {

    /** The test-only curve: db(n) = 60 log10(n); the module never sees this. */
    private static String db(double n) {
        return n <= 0 ? "-inf dB" : String.format(java.util.Locale.ROOT, "%.1f dB", 60 * Math.log10(n));
    }

    private static String pan(double n) {
        double p = (n - 0.5) * 200;
        return Math.abs(p) < 0.5 ? "0.00 %" : String.format(java.util.Locale.ROOT, "%.0f %%", p);
    }

    private static HostFixture fixture(MixModule[] out) {
        HostFixture f = new HostFixture();
        FakeHost value = f.tracks[0].child("volume").child("value");
        value.afterWrite =
                () -> f.tracks[0].child("volume").child("displayedValue").emit(db(((Number) value.data).doubleValue()));
        FakeHost panValue = f.tracks[0].child("pan").child("value");
        panValue.afterWrite = () ->
                f.tracks[0].child("pan").child("displayedValue").emit(pan(((Number) panValue.data).doubleValue()));
        FakeHost send = f.tracks[0].child("sendBank").item(0);
        FakeHost sendValue = send.child("value");
        sendValue.afterWrite = () -> send.child("displayedValue").emit(db(((Number) sendValue.data).doubleValue()));
        send.child("isEnabled").data = true;
        out[0] = f.services.mix();
        return f;
    }

    private static JsonObject params(String json) {
        JsonObject p = HostFixture.json(json);
        p.add("target", HostFixture.track(HostFixture.A));
        return p;
    }

    private static JsonObject send(String json) {
        return send(json, "Reverb", 0);
    }

    private static JsonObject send(String json, String name, int index) {
        JsonObject p = HostFixture.json(json);
        p.add("target", Refs.send(HostFixture.track(HostFixture.A), index, name));
        return p;
    }
    /** Runs queued host tasks until the operation completes (each poll is one task). */
    private static JsonObject run(HostFixture f, HostOperation op) {
        for (int i = 0; i < 2000 && !op.result.isDone(); i++) {
            if (f.tasks.isEmpty()) throw new AssertionError("no task queued but operation pending");
            f.next();
        }
        assertTrue(op.result.isDone(), "operation completed");
        return op.result.join();
    }

    private static JsonObject call(HostFixture f, MixModule m, String method, JsonObject p) {
        return run(f, f.submit(m, method, p));
    }

    @Test
    void invalidRequestsNeverSchedule() {
        MixModule[] m = new MixModule[1];
        HostFixture f = fixture(m);
        for (String text : List.of(
                "{}",
                "{\"value\":1.1}",
                "{\"value\":-0.1}",
                "{\"enabled\":1}",
                "{\"value\":{\"display\":\"\"}}",
                "{\"value\":{\"label\":\"-6 dB\"}}",
                "{\"extra\":1,\"enabled\":true}",
                "{\"sendIndex\":0,\"value\":0.1}")) {
            assertRpcError("invalid_params", f.submit(m[0], "tracks.sends.set", send(text)));
            assertTrue(f.tasks.isEmpty(), "invalid send request not scheduled: " + text);
        }
        JsonObject byIndex =
                HostFixture.json("{\"value\":0.1,\"target\":{\"kind\":\"send\",\"index\":128,\"name\":\"x\"}}");
        byIndex.getAsJsonObject("target").add("track", HostFixture.track(HostFixture.A));
        assertRpcError("invalid_params", f.submit(m[0], "tracks.sends.set", byIndex));
        for (String text : List.of(
                "{\"volume\":{\"display\":\"\"}}",
                "{\"volume\":{\"label\":\"-6 dB\"}}",
                "{\"volume\":{\"display\":\"-6 dB\",\"label\":\"x\"}}",
                "{\"pan\":{}}",
                "{\"volumeDb\":-6}",
                "{\"panPercent\":25}",
                "{\"volume\":-0.5}")) {
            assertRpcError("invalid_params", f.submit(m[0], "tracks.set", params(text)));
            assertTrue(f.tasks.isEmpty(), "invalid display request not scheduled: " + text);
        }
    }

    @Test
    void sendNormalizedWriteIsVerifiedAndReportsEffectTrack() {
        MixModule[] m = new MixModule[1];
        HostFixture f = fixture(m);
        f.addTrack(2, HostFixture.C, "Effect");
        f.tracks[2].child("name").data = "Reverb";
        JsonObject before = f.call(m[0], "tracks.get", HostFixture.targeting(HostFixture.A));
        JsonObject row = before.getAsJsonArray("sends").get(0).getAsJsonObject();
        assertTrue(
                row.getAsJsonObject("effectTrack").equals(Refs.track(HostFixture.C, "Reverb")),
                "send maps to the first effect track, as a track ref");
        JsonObject result = call(f, m[0], "tracks.sends.set", send("{\"value\":0.6}"));
        assertTrue(
                result.get("outcome").getAsString().matches("applied|already_matched")
                        && result.get("outcome").getAsString().matches("applied|already_matched"),
                "send level observed");
        assertTrue(
                result.getAsJsonObject("fields")
                        .getAsJsonObject("value")
                        .get("outcome")
                        .getAsString()
                        .equals("applied"),
                "echo verified");
        assertEquals(
                Refs.send(HostFixture.track(HostFixture.A), 0, "Reverb"),
                result.getAsJsonObject("ref"),
                "the written send's ref");
        assertTrue(f.tracks[0].child("sendBank").item(0).child("value").writes == 1, "exactly one send write");
        assertTrue(f.tracks[0].child("volume").child("value").writes == 0, "track volume untouched");
        JsonObject again = call(f, m[0], "tracks.sends.set", send("{\"value\":0.6}"));
        assertTrue(
                again.getAsJsonObject("fields")
                                .getAsJsonObject("value")
                                .get("outcome")
                                .getAsString()
                                .equals("already_matched")
                        && f.tracks[0].child("sendBank").item(0).child("value").writes == 1,
                "same value is not rewritten");
    }

    @Test
    void sendDisplayWriteBisectsObservedText() {
        MixModule[] m = new MixModule[1];
        HostFixture f = fixture(m);
        JsonObject result = call(f, m[0], "tracks.sends.set", send("{\"value\":{\"display\":\"-12 dB\"}}"));
        assertTrue(
                result.get("outcome").getAsString().matches("applied|already_matched")
                        && result.get("outcome").getAsString().matches("applied|already_matched"),
                "display write observed: " + result);
        JsonObject dw = result.getAsJsonObject("displayWrite").getAsJsonObject("value");
        assertTrue(
                dw.get("matched").getAsBoolean()
                        && dw.get("observedDisplay").getAsString().equals("-12.0 dB"),
                "host display shows the target: " + dw);
        double n = result.getAsJsonObject("fields")
                .getAsJsonObject("value")
                .get("requested")
                .getAsDouble();
        assertTrue(
                Math.abs(60 * Math.log10(n) + 12) < 0.06,
                "normalized value really maps to -12 dB under the host curve");
        assertTrue(
                result.getAsJsonObject("fields")
                        .getAsJsonObject("value")
                        .get("outcome")
                        .getAsString()
                        .equals("applied"),
                "verification names the display readback");
        assertTrue(
                dw.get("probes").getAsInt() > 3 && dw.get("probes").getAsInt() <= DisplaySolver.MAX_PROBES,
                "bounded probe count");
    }

    @Test
    void sendRefsAreComparedAndExpectIsAValueCas() {
        MixModule[] m = new MixModule[1];
        HostFixture f = fixture(m);
        HostOperation renamed = f.submit(m[0], "tracks.sends.set", send("{\"value\":0.1}", "Delay", 0));
        f.next();
        RpcException changed = assertRpcError("target_changed", renamed);
        assertEquals(Refs.send(HostFixture.track(HostFixture.A), 0, "Reverb"), changed.current);
        HostOperation absent = f.submit(m[0], "tracks.sends.set", send("{\"value\":0.1}", "Reverb", 3));
        f.next();
        assertRpcError("target_not_found", absent);
        HostOperation mismatch =
                f.submit(m[0], "tracks.sends.set", send("{\"value\":0.1,\"expect\":{\"value\":0.9,\"enabled\":true}}"));
        f.next();
        RpcException expect = assertRpcError("target_changed", mismatch);
        assertEquals(.25, expect.observed.get("value").getAsDouble());
        assertEquals(0, f.tracks[0].child("sendBank").item(0).child("value").writes, "no refused request wrote");
        JsonObject ok =
                call(f, m[0], "tracks.sends.set", send("{\"value\":0.7,\"expect\":{\"value\":0.25,\"enabled\":true}}"));
        assertTrue(ok.get("outcome").getAsString().matches("applied|already_matched"), "a matching expect writes");
    }

    @Test
    void aSendAtMinusInfinityIsTheMinimumAndAcceptsADisplayWrite() {
        MixModule[] m = new MixModule[1];
        HostFixture f = fixture(m);
        FakeHost send = f.tracks[0].child("sendBank").item(0);
        send.child("value").emit(Double.NaN);
        send.child("displayedValue").emit("-Inf dB");
        JsonObject read = f.call(m[0], "tracks.get", HostFixture.targeting(HostFixture.A));
        assertEquals(
                0.0,
                read.getAsJsonArray("sends")
                        .get(0)
                        .getAsJsonObject()
                        .getAsJsonObject("value")
                        .get("value")
                        .getAsDouble(),
                "-Inf dB reads as the minimum");
        JsonObject result = call(f, m[0], "tracks.sends.set", send("{\"value\":{\"display\":\"-12 dB\"}}"));
        assertTrue(result.get("outcome").getAsString().matches("applied|already_matched"), result.toString());
    }

    @Test
    void sendEnableAndAutoEnable() {
        MixModule[] m = new MixModule[1];
        HostFixture f = fixture(m);
        FakeHost send = f.tracks[0].child("sendBank").item(0);
        call(f, m[0], "tracks.sends.set", send("{\"enabled\":false}"));
        assertTrue(Boolean.FALSE.equals(send.child("isEnabled").data), "send disabled");
        // The host turns a send on when its level moves (guide 7.1.6); model that and require the report.
        send.child("value").afterWrite = () -> {
            send.child("isEnabled").emit(true);
            send.child("displayedValue").emit(db(((Number) send.child("value").data).doubleValue()));
        };
        JsonObject result = call(f, m[0], "tracks.sends.set", send("{\"value\":0.4}"));
        assertTrue(result.get("autoEnabled").getAsBoolean(), "auto-enable by a level write is reported, not hidden");
        JsonObject both = call(f, m[0], "tracks.sends.set", send("{\"value\":0.2,\"enabled\":false}"));
        assertTrue(
                both.get("outcome").getAsString().matches("applied|already_matched")
                        && Boolean.FALSE.equals(send.child("isEnabled").data),
                "explicit enabled=false is written after the level");
    }

    @Test
    void equalDisplayValueIsAlreadyMatched() {
        MixModule[] m = new MixModule[1];
        HostFixture f = fixture(m);
        call(f, m[0], "tracks.set", params("{\"volume\":{\"display\":\"-6 dB\"}}"));
        FakeHost value = f.tracks[0].child("volume").child("value");
        int writes = value.writes;
        JsonObject again = call(f, m[0], "tracks.set", params("{\"volume\":{\"display\":\"-6 dB\"}}"));
        assertEquals("already_matched", again.get("outcome").getAsString(), again.toString());
        assertEquals(
                0,
                again.getAsJsonObject("displayWrite")
                        .getAsJsonObject("volume")
                        .get("probes")
                        .getAsInt());
        assertEquals(writes, value.writes, "nothing written");
    }

    @Test
    void volumeDisplay() {
        MixModule[] m = new MixModule[1];
        HostFixture f = fixture(m);
        JsonObject result = call(f, m[0], "tracks.set", params("{\"volume\":{\"display\":\"-6 dB\"}}"));
        assertTrue(
                result.get("outcome").getAsString().matches("applied|already_matched")
                        && result.get("outcome").getAsString().matches("applied|already_matched"),
                "volume display observed: " + result);
        assertTrue(
                result.getAsJsonObject("displayWrite")
                        .getAsJsonObject("volume")
                        .get("observedDisplay")
                        .getAsString()
                        .equals("-6.0 dB"),
                "display shows -6.0 dB");
        double n = (Double) f.tracks[0].child("volume").child("value").data;
        assertTrue(Math.abs(60 * Math.log10(n) + 6) < 0.06, "host value matches -6 dB");
        assertTrue(
                result.getAsJsonObject("fields")
                        .getAsJsonObject("volume")
                        .get("outcome")
                        .getAsString()
                        .equals("applied"),
                "display readback verification");
        JsonObject text = call(f, m[0], "tracks.set", params("{\"volume\":{\"display\":\"-20 dB\"},\"mute\":true}"));
        assertTrue(
                text.get("outcome").getAsString().matches("applied|already_matched")
                        && Boolean.TRUE.equals(f.tracks[0].child("mute").data),
                "display text plus another field in one call");
        FakeHost vol = f.tracks[0].child("volume").child("value");
        int writes = vol.writes;
        HostOperation bad = f.submit(m[0], "tracks.set", params("{\"volume\":{\"display\":\"quiet\"}}"));
        f.next();
        assertRpcError("unsupported_unit", bad);
        assertTrue(vol.writes == writes, "unparseable request text never reaches the host");
    }

    @Test
    void panDisplay() {
        MixModule[] m = new MixModule[1];
        HostFixture f = fixture(m);
        JsonObject result = call(f, m[0], "tracks.set", params("{\"pan\":{\"display\":\"25% L\"}}"));
        assertTrue(
                result.get("outcome").getAsString().matches("applied|already_matched"),
                "pan display observed: " + result);
        assertTrue(
                result.getAsJsonObject("displayWrite")
                        .getAsJsonObject("pan")
                        .get("observedDisplay")
                        .getAsString()
                        .equals("-25 %"),
                "host shows -25 %");
        JsonObject right = call(f, m[0], "tracks.set", params("{\"pan\":{\"display\":\"40% R\"}}"));
        assertTrue(
                right.get("outcome").getAsString().matches("applied|already_matched")
                        && right.getAsJsonObject("displayWrite")
                                .getAsJsonObject("pan")
                                .get("observedDisplay")
                                .getAsString()
                                .equals("40 %"),
                "display text form");
        JsonObject both =
                call(f, m[0], "tracks.set", params("{\"volume\":{\"display\":\"-3 dB\"},\"pan\":{\"display\":\"C\"}}"));
        assertTrue(
                both.get("outcome").getAsString().matches("applied|already_matched")
                        && both.getAsJsonObject("displayWrite").has("volume")
                        && both.getAsJsonObject("displayWrite").has("pan"),
                "volume then pan resolved in one call");
    }

    @Test
    void unreachableDisplayRestoresOriginal() {
        MixModule[] m = new MixModule[1];
        HostFixture f = fixture(m);
        FakeHost vol = f.tracks[0].child("volume").child("value");
        JsonObject result = call(f, m[0], "tracks.set", params("{\"volume\":{\"display\":\"20 dB\"},\"mute\":true}"));
        assertTrue(
                !result.get("outcome").getAsString().matches("applied|already_matched")
                        && result.getAsJsonObject("displayWrite")
                                .getAsJsonObject("volume")
                                .get("failure")
                                .getAsString()
                                .equals("display_not_reachable"),
                "refused: " + result);
        assertTrue(Math.abs(((Number) vol.data).doubleValue() - 0.5) < 1e-9, "original normalized value restored");
        assertTrue(
                result.getAsJsonObject("displayWrite")
                                .getAsJsonObject("volume")
                                .get("restored")
                                .getAsBoolean()
                        && result.get("outcome").getAsString().equals("not_applied"),
                "restore observed");
        assertTrue(
                !result.get("otherFieldsApplied").getAsBoolean()
                        && Boolean.FALSE.equals(f.tracks[0].child("mute").data),
                "other fields in the same request were not applied");
    }

    @Test
    void unparseableDisplayRestoresAndAppliesNothingElse() {
        MixModule[] m = new MixModule[1];
        HostFixture f = fixture(m);
        FakeHost vol = f.tracks[0].child("volume").child("value");
        vol.afterWrite =
                () -> f.tracks[0].child("volume").child("displayedValue").emit("???");
        JsonObject result = call(f, m[0], "tracks.set", params("{\"volume\":{\"display\":\"-6 dB\"},\"arm\":true}"));
        assertTrue(
                result.getAsJsonObject("displayWrite")
                                .getAsJsonObject("volume")
                                .get("failure")
                                .getAsString()
                                .equals("display_unparseable")
                        && !result.get("outcome").getAsString().matches("applied|already_matched"),
                "fail closed on unreadable display: " + result);
        assertTrue(
                Math.abs(((Number) vol.data).doubleValue() - 0.5) < 1e-9
                        && Boolean.FALSE.equals(f.tracks[0].child("arm").data),
                "restored, arm untouched");
    }

    @Test
    void laterSolveFailureRestoresEarlierField() {
        MixModule[] m = new MixModule[1];
        HostFixture f = fixture(m);
        FakeHost vol = f.tracks[0].child("volume").child("value");
        FakeHost pan = f.tracks[0].child("pan").child("value");
        pan.afterWrite = () -> f.tracks[0].child("pan").child("displayedValue").emit("???");
        JsonObject result = call(
                f, m[0], "tracks.set", params("{\"volume\":{\"display\":\"-6 dB\"},\"pan\":{\"display\":\"25% L\"}}"));
        assertTrue(
                result.getAsJsonObject("displayWrite")
                                .getAsJsonObject("pan")
                                .get("failure")
                                .getAsString()
                                .equals("display_unparseable")
                        && !result.get("outcome").getAsString().matches("applied|already_matched"),
                "second solve failed: " + result);
        assertTrue(
                Math.abs(((Number) vol.data).doubleValue() - 0.5) < 1e-9, "earlier volume solve restored: " + vol.data);
        assertTrue(Math.abs(((Number) pan.data).doubleValue() - 0.5) < 1e-9, "pan restored");
        assertTrue(
                !result.get("otherFieldsApplied").getAsBoolean()
                        && result.get("outcome").getAsString().equals("not_applied"),
                "clean restore claimed only because it is true: " + result);
    }

    @Test
    void deadlineStopsProbingWhileRestoreFits() {
        MixModule[] m = new MixModule[1];
        HostFixture f = fixture(m);
        FakeHost vol = f.tracks[0].child("volume").child("value");
        Runnable show = vol.afterWrite;
        vol.afterWrite = () -> {
            f.tasks.advance(java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(100));
            show.run();
        };
        HostOperation op =
                f.submit(m[0], "tracks.set", params("{\"volume\":{\"display\":\"-6 dB\"}}"), f.tasks.after(1300));
        JsonObject result = run(f, op);
        assertTrue(
                result.get("reason").getAsString().equals("deadline"),
                "stopped at the deadline budget, not deadline_exceeded: " + result);
        assertTrue(
                Math.abs(((Number) vol.data).doubleValue() - 0.5) < 1e-9
                        && result.getAsJsonObject("displayWrite")
                                .getAsJsonObject("volume")
                                .get("restored")
                                .getAsBoolean(),
                "original restored before the deadline");
    }

    @Test
    void explicitDisableFollowsLevelWriteOnDisabledSend() {
        MixModule[] m = new MixModule[1];
        HostFixture f = fixture(m);
        FakeHost send = f.tracks[0].child("sendBank").item(0);
        FakeHost enabled = send.child("isEnabled");
        enabled.emit(false);
        Runnable show = send.child("value").afterWrite;
        // The host turns the send on at once, but the observer echo only arrives on a later flush.
        send.child("value").afterWrite = () -> {
            show.run();
            enabled.data = true;
            f.tasks.add(() -> enabled.emit(enabled.data));
        };
        JsonObject result = call(f, m[0], "tracks.sends.set", send("{\"value\":0.3,\"enabled\":false}"));
        assertTrue(Boolean.FALSE.equals(enabled.data), "send really ends disabled: " + result);
        assertTrue(
                result.get("outcome").getAsString().matches("applied|already_matched")
                        && !result.getAsJsonObject("fields")
                                .getAsJsonObject("enabled")
                                .get("outcome")
                                .getAsString()
                                .equals("already_matched"),
                "enabled write dispatched and verified, not skipped against stale metadata: " + result);
    }

    @Test
    void displayLevelWithEnableOnDisabledSend() {
        MixModule[] m = new MixModule[1];
        HostFixture f = fixture(m);
        FakeHost send = f.tracks[0].child("sendBank").item(0);
        FakeHost enabled = send.child("isEnabled");
        enabled.emit(false);
        // The solver's first audible probe turns the send on; enabling it again raises no callback.
        enabled.notifyWrites = false;
        Runnable show = send.child("value").afterWrite;
        send.child("value").afterWrite = () -> {
            show.run();
            if (!Boolean.TRUE.equals(enabled.data)) enabled.emit(true);
        };
        JsonObject result =
                call(f, m[0], "tracks.sends.set", send("{\"value\":{\"display\":\"-9 dB\"},\"enabled\":true}"));
        assertEquals("applied", result.get("outcome").getAsString(), result.toString());
        assertTrue(Boolean.TRUE.equals(enabled.data), "send ends enabled");
    }

    @Test
    void failedSendProbeRestoresEnabledState() {
        MixModule[] m = new MixModule[1];
        HostFixture f = fixture(m);
        FakeHost send = f.tracks[0].child("sendBank").item(0);
        FakeHost enabled = send.child("isEnabled");
        enabled.emit(false);
        Runnable show = send.child("value").afterWrite;
        send.child("value").afterWrite = () -> {
            show.run();
            enabled.emit(true);
        };
        JsonObject result = call(f, m[0], "tracks.sends.set", send("{\"value\":{\"display\":\"5 dB\"}}"));
        assertTrue(
                result.getAsJsonObject("displayWrite")
                        .getAsJsonObject("value")
                        .get("failure")
                        .getAsString()
                        .equals("display_not_reachable"),
                "unreachable: " + result);
        assertTrue(Boolean.FALSE.equals(enabled.data), "enabled state restored");
        assertTrue(
                result.getAsJsonObject("displayWrite")
                                .getAsJsonObject("value")
                                .get("restored")
                                .getAsBoolean()
                        && result.get("outcome").getAsString().equals("not_applied"),
                "restore reported only after both were put back: " + result);
    }

    @Test
    void minusInfinity() {
        MixModule[] m = new MixModule[1];
        HostFixture f = fixture(m);
        JsonObject result = call(f, m[0], "tracks.set", params("{\"volume\":{\"display\":\"-inf dB\"}}"));
        assertTrue(
                result.get("outcome").getAsString().matches("applied|already_matched")
                        && result.getAsJsonObject("displayWrite")
                                        .getAsJsonObject("volume")
                                        .get("probes")
                                        .getAsInt()
                                == 1,
                "-inf is one probe at the bottom: " + result);
        assertTrue(
                ((Number) f.tracks[0].child("volume").child("value").data).doubleValue() == 0,
                "bottom of the scale written");
    }

    @Test
    void staleTargetStopsDisplayWrite() {
        MixModule[] m = new MixModule[1];
        HostFixture f = fixture(m);
        FakeHost vol = f.tracks[0].child("volume").child("value");
        HostOperation op = f.submit(m[0], "tracks.set", params("{\"volume\":{\"display\":\"-6 dB\"}}"));
        f.next();
        int writes = vol.writes;
        f.tracks[0].child("channelId").emit("00000000-0000-0000-0000-0000000000ff");
        while (!op.result.isDone() && !f.tasks.isEmpty()) f.next();
        assertTrue(
                op.result.isDone() && vol.writes <= writes + 1,
                "no further probe writes after the track identity changed");
    }

    @Test
    void bottomOfTheScaleThatTheDisplayNeverShowsIsNotReachable() {
        MixModule[] m = new MixModule[1];
        HostFixture f = fixture(m);
        f.tracks[0].child("volume").child("value").afterWrite = () -> {};
        JsonObject result = call(f, m[0], "tracks.set", params("{\"volume\":{\"display\":\"-inf dB\"}}"));
        assertTrue(
                result.get("reason").getAsString().equals("readback_mismatch")
                        && result.getAsJsonObject("displayWrite")
                                .getAsJsonObject("volume")
                                .get("failure")
                                .getAsString()
                                .equals("display_not_reachable"),
                "an unreachable bottom is refused: " + result);
    }

    @Test
    void unobservedProbeWriteIsRestoredAndReported() {
        MixModule[] m = new MixModule[1];
        HostFixture f = fixture(m);
        f.tracks[0].child("volume").child("value").notifyWrites = false;
        HostOperation op =
                f.submit(m[0], "tracks.set", params("{\"volume\":{\"display\":\"-6 dB\"}}"), f.tasks.after(3000));
        JsonObject result = run(f, op);
        assertTrue(result.get("reason").getAsString().equals("not_observed"), "probe never observed: " + result);
    }
}
