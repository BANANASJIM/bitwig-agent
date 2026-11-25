package dev.bitwigagent;

import com.google.gson.*;
import java.util.List;

/** Send writes and display-addressed volume/pan against a simulated host whose display text follows a known (to the test only) curve. */
public final class MixDisplayWriteTest {
    private static int assertions;
    public static void main(String[] args) {
        invalidRequestsNeverSchedule();
        sendNormalizedWriteIsVerifiedAndReportsEffectTrack();
        sendDisplayWriteBisectsObservedText();
        sendByNameAndCas();
        sendEnableAndAutoEnable();
        volumeDbAndDisplay();
        panPercentAndDisplay();
        unreachableDisplayRestoresOriginal();
        unparseableDisplayRestoresAndAppliesNothingElse();
        laterSolveFailureRestoresEarlierField();
        deadlineStopsProbingWhileRestoreFits();
        explicitDisableFollowsLevelWriteOnDisabledSend();
        failedSendProbeRestoresEnabledState();
        minusInfinity();
        staleTargetStopsDisplayWrite();
        System.out.println("MixDisplayWriteTest: " + assertions + " assertions passed (simulated host only)");
    }

    /** The test-only curve: db(n) = 60 log10(n); the module never sees this. */
    private static String db(double n) { return n <= 0 ? "-inf dB" : String.format(java.util.Locale.ROOT, "%.1f dB", 60 * Math.log10(n)); }
    private static String pan(double n) {
        double p = (n - 0.5) * 200;
        return Math.abs(p) < 0.5 ? "Center" : String.format(java.util.Locale.ROOT, "%.0f%% %s", Math.abs(p), p < 0 ? "L" : "R");
    }

    private static TransportMixFixture fixture(MixModule[] out) {
        TransportMixFixture f = new TransportMixFixture();
        TransportMixFixture.Node value = f.tracks[0].child("volume").child("value");
        value.afterWrite = () -> f.tracks[0].child("volume").child("displayedValue").emit(db(((Number) value.data).doubleValue()));
        TransportMixFixture.Node panValue = f.tracks[0].child("pan").child("value");
        panValue.afterWrite = () -> f.tracks[0].child("pan").child("displayedValue").emit(pan(((Number) panValue.data).doubleValue()));
        TransportMixFixture.Node send = f.tracks[0].child("sendBank").item(0);
        TransportMixFixture.Node sendValue = send.child("value");
        sendValue.afterWrite = () -> send.child("displayedValue").emit(db(((Number) sendValue.data).doubleValue()));
        send.child("isEnabled").data = true;
        out[0] = new MixModule(f.services);
        return f;
    }
    private static JsonObject params(String json) {
        JsonObject p = TransportMixFixture.json(json); p.addProperty("trackId", TransportMixFixture.A); return p;
    }
    /** Runs queued host tasks until the operation completes (each poll is one task). */
    private static JsonObject run(TransportMixFixture f, HostOperation op) {
        for (int i = 0; i < 2000 && !op.result.isDone(); i++) { if (f.tasks.isEmpty()) throw new AssertionError("no task queued but operation pending"); f.next(); }
        check(op.result.isDone(), "operation completed");
        return op.result.join();
    }
    private static JsonObject call(TransportMixFixture f, MixModule m, String method, JsonObject p) { return run(f, f.submit(m, method, p)); }

    private static void invalidRequestsNeverSchedule() {
        MixModule[] m = new MixModule[1]; TransportMixFixture f = fixture(m);
        for (String text : List.of("{}", "{\"sendIndex\":0}", "{\"sendIndex\":0,\"sendName\":\"Reverb\",\"valueNormalized\":0.1}",
                "{\"sendIndex\":0,\"valueNormalized\":0.1,\"display\":\"-6 dB\"}", "{\"sendIndex\":-1,\"valueNormalized\":0.1}",
                "{\"sendIndex\":128,\"valueNormalized\":0.1}", "{\"sendIndex\":0,\"valueNormalized\":1.1}", "{\"sendIndex\":0,\"enabled\":1}",
                "{\"sendIndex\":0,\"display\":\"\"}", "{\"sendIndex\":0,\"extra\":1,\"enabled\":true}")) {
            TransportMixFixture.error(f.submit(m[0], "tracks.sends.set", params(text)), "invalid_params");
            check(f.tasks.isEmpty(), "invalid send request not scheduled: " + text);
        }
        for (String text : List.of("{\"volumeNormalized\":0.5,\"volumeDb\":-6}", "{\"volumeDb\":-6,\"volumeDisplay\":\"-6 dB\"}", "{\"volumeDb\":25}",
                "{\"volumeDb\":-151}", "{\"panPercent\":101}", "{\"panPercent\":-101}", "{\"panNormalized\":0.5,\"panDisplay\":\"C\"}", "{\"panDisplay\":\"\"}")) {
            TransportMixFixture.error(f.submit(m[0], "tracks.set", params(text)), "invalid_params");
            check(f.tasks.isEmpty(), "invalid display request not scheduled: " + text);
        }
    }

    private static void sendNormalizedWriteIsVerifiedAndReportsEffectTrack() {
        MixModule[] m = new MixModule[1]; TransportMixFixture f = fixture(m);
        f.addTrack(2, TransportMixFixture.C, "Effect");
        f.tracks[2].child("name").data = "Reverb";
        JsonObject before = f.call(m[0], "tracks.get", TransportMixFixture.target(TransportMixFixture.A));
        JsonObject row = before.getAsJsonArray("sends").get(0).getAsJsonObject();
        check(row.getAsJsonObject("effectTrack").get("trackId").getAsString().equals(TransportMixFixture.C), "send maps to the first effect track");
        check(row.getAsJsonObject("effectTrack").get("name").getAsString().equals("Reverb") && row.get("nameMatchesEffectTrack").getAsBoolean(), "effect track name reported and compared");
        JsonObject result = call(f, m[0], "tracks.sends.set", params("{\"sendIndex\":0,\"expectedSendName\":\"Reverb\",\"valueNormalized\":0.6}"));
        check(result.get("status").getAsString().equals("observed") && result.get("matchesRequested").getAsBoolean(), "send level observed");
        check(result.getAsJsonObject("fields").getAsJsonObject("valueNormalized").get("verification").getAsString().equals("observer_after_write"), "echo verified");
        check(result.get("sendName").getAsString().equals("Reverb") && result.get("sendIndex").getAsInt() == 0, "send identity echoed");
        check(f.tracks[0].child("sendBank").item(0).child("value").writes == 1, "exactly one send write");
        check(f.tracks[0].child("volume").child("value").writes == 0, "track volume untouched");
        JsonObject again = call(f, m[0], "tracks.sends.set", params("{\"sendIndex\":0,\"valueNormalized\":0.6}"));
        check(again.getAsJsonObject("fields").getAsJsonObject("valueNormalized").get("verification").getAsString().equals("already_at_value")
                && f.tracks[0].child("sendBank").item(0).child("value").writes == 1, "same value is not rewritten");
    }

    private static void sendDisplayWriteBisectsObservedText() {
        MixModule[] m = new MixModule[1]; TransportMixFixture f = fixture(m);
        JsonObject result = call(f, m[0], "tracks.sends.set", params("{\"sendIndex\":0,\"display\":\"-12 dB\"}"));
        check(result.get("status").getAsString().equals("observed") && result.get("matchesRequested").getAsBoolean(), "display write observed: " + result);
        JsonObject dw = result.getAsJsonObject("displayWrite");
        check(dw.get("matched").getAsBoolean() && dw.get("observedDisplay").getAsString().equals("-12.0 dB"), "host display shows the target: " + dw);
        double n = result.getAsJsonObject("fields").getAsJsonObject("valueNormalized").get("observed").getAsDouble();
        check(Math.abs(60 * Math.log10(n) + 12) < 0.06, "normalized value really maps to -12 dB under the host curve");
        check(result.getAsJsonObject("fields").getAsJsonObject("valueNormalized").get("verification").getAsString().equals("display_readback"), "verification names the display readback");
        check(dw.get("probes").getAsInt() > 3 && dw.get("probes").getAsInt() <= DisplaySolver.MAX_PROBES, "bounded probe count");
    }

    private static void sendByNameAndCas() {
        MixModule[] m = new MixModule[1]; TransportMixFixture f = fixture(m);
        JsonObject ok = call(f, m[0], "tracks.sends.set", params("{\"sendName\":\"Reverb\",\"valueNormalized\":0.7}"));
        check(ok.get("matchesRequested").getAsBoolean(), "name addressing works");
        HostOperation missing = f.submit(m[0], "tracks.sends.set", params("{\"sendName\":\"Delay\",\"valueNormalized\":0.7}")); f.next();
        TransportMixFixture.error(missing, "unknown_send");
        HostOperation wrong = f.submit(m[0], "tracks.sends.set", params("{\"sendIndex\":0,\"expectedSendName\":\"Delay\",\"valueNormalized\":0.1}")); f.next();
        TransportMixFixture.error(wrong, "stale_target");
        HostOperation absent = f.submit(m[0], "tracks.sends.set", params("{\"sendIndex\":3,\"valueNormalized\":0.1}")); f.next();
        TransportMixFixture.error(absent, "unknown_send");
        HostOperation far = f.submit(m[0], "tracks.sends.set", params("{\"sendIndex\":9,\"valueNormalized\":0.1}")); f.next();
        TransportMixFixture.error(far, "outside_window");
        check(f.tracks[0].child("sendBank").item(0).child("value").writes == 1, "no refused request wrote");
    }

    private static void sendEnableAndAutoEnable() {
        MixModule[] m = new MixModule[1]; TransportMixFixture f = fixture(m);
        TransportMixFixture.Node send = f.tracks[0].child("sendBank").item(0);
        call(f, m[0], "tracks.sends.set", params("{\"sendIndex\":0,\"enabled\":false}"));
        check(Boolean.FALSE.equals(send.child("isEnabled").data), "send disabled");
        // The host turns a send on when its level moves (guide 7.1.6); model that and require the report.
        send.child("value").afterWrite = () -> { send.child("isEnabled").emit(true); send.child("displayedValue").emit(db(((Number) send.child("value").data).doubleValue())); };
        JsonObject result = call(f, m[0], "tracks.sends.set", params("{\"sendIndex\":0,\"valueNormalized\":0.4}"));
        check(result.get("autoEnabled").getAsBoolean(), "auto-enable by a level write is reported, not hidden");
        JsonObject both = call(f, m[0], "tracks.sends.set", params("{\"sendIndex\":0,\"valueNormalized\":0.2,\"enabled\":false}"));
        check(both.get("matchesRequested").getAsBoolean() && Boolean.FALSE.equals(send.child("isEnabled").data), "explicit enabled=false is written after the level");
    }

    private static void volumeDbAndDisplay() {
        MixModule[] m = new MixModule[1]; TransportMixFixture f = fixture(m);
        JsonObject result = call(f, m[0], "tracks.set", params("{\"volumeDb\":-6}"));
        check(result.get("status").getAsString().equals("observed") && result.get("matchesRequested").getAsBoolean(), "volumeDb observed: " + result);
        check(result.getAsJsonObject("volumeDisplayWrite").get("observedDisplay").getAsString().equals("-6.0 dB"), "display shows -6.0 dB");
        double n = (Double) f.tracks[0].child("volume").child("value").data;
        check(Math.abs(60 * Math.log10(n) + 6) < 0.06, "host value matches -6 dB");
        check(result.getAsJsonObject("fields").getAsJsonObject("volumeNormalized").get("verification").getAsString().equals("display_readback"), "display readback verification");
        JsonObject text = call(f, m[0], "tracks.set", params("{\"volumeDisplay\":\"-20 dB\",\"mute\":true}"));
        check(text.get("matchesRequested").getAsBoolean() && Boolean.TRUE.equals(f.tracks[0].child("mute").data), "display text plus another field in one call");
        TransportMixFixture.Node vol = f.tracks[0].child("volume").child("value");
        int writes = vol.writes;
        HostOperation bad = f.submit(m[0], "tracks.set", params("{\"volumeDisplay\":\"quiet\"}")); f.next();
        TransportMixFixture.error(bad, "invalid_params");
        check(vol.writes == writes, "unparseable request text never reaches the host");
    }

    private static void panPercentAndDisplay() {
        MixModule[] m = new MixModule[1]; TransportMixFixture f = fixture(m);
        JsonObject result = call(f, m[0], "tracks.set", params("{\"panPercent\":-25}"));
        check(result.get("matchesRequested").getAsBoolean(), "panPercent observed: " + result);
        check(result.getAsJsonObject("panDisplayWrite").get("observedDisplay").getAsString().equals("25% L"), "host shows 25% L");
        JsonObject right = call(f, m[0], "tracks.set", params("{\"panDisplay\":\"40% R\"}"));
        check(right.get("matchesRequested").getAsBoolean() && right.getAsJsonObject("panDisplayWrite").get("observedDisplay").getAsString().equals("40% R"), "display text form");
        JsonObject both = call(f, m[0], "tracks.set", params("{\"volumeDb\":-3,\"panPercent\":0}"));
        check(both.get("matchesRequested").getAsBoolean() && both.has("volumeDisplayWrite") && both.has("panDisplayWrite"), "volume then pan resolved in one call");
    }

    private static void unreachableDisplayRestoresOriginal() {
        MixModule[] m = new MixModule[1]; TransportMixFixture f = fixture(m);
        TransportMixFixture.Node vol = f.tracks[0].child("volume").child("value");
        JsonObject result = call(f, m[0], "tracks.set", params("{\"volumeDb\":20,\"mute\":true}"));
        check(!result.get("matchesRequested").getAsBoolean() && result.get("reason").getAsString().equals("display_not_reachable"), "refused: " + result);
        check(Math.abs(((Number) vol.data).doubleValue() - 0.5) < 1e-9, "original normalized value restored");
        check(result.getAsJsonObject("volumeDisplayWrite").get("restored").getAsBoolean() && result.get("completion").getAsString().equals("observed"), "restore observed");
        check(!result.get("otherFieldsApplied").getAsBoolean() && Boolean.FALSE.equals(f.tracks[0].child("mute").data), "other fields in the same request were not applied");
    }

    private static void unparseableDisplayRestoresAndAppliesNothingElse() {
        MixModule[] m = new MixModule[1]; TransportMixFixture f = fixture(m);
        TransportMixFixture.Node vol = f.tracks[0].child("volume").child("value");
        vol.afterWrite = () -> f.tracks[0].child("volume").child("displayedValue").emit("???");
        JsonObject result = call(f, m[0], "tracks.set", params("{\"volumeDb\":-6,\"arm\":true}"));
        check(result.get("reason").getAsString().equals("display_unparseable") && !result.get("matchesRequested").getAsBoolean(), "fail closed on unreadable display: " + result);
        check(Math.abs(((Number) vol.data).doubleValue() - 0.5) < 1e-9 && Boolean.FALSE.equals(f.tracks[0].child("arm").data), "restored, arm untouched");
    }

    private static void laterSolveFailureRestoresEarlierField() {
        MixModule[] m = new MixModule[1]; TransportMixFixture f = fixture(m);
        TransportMixFixture.Node vol = f.tracks[0].child("volume").child("value");
        TransportMixFixture.Node pan = f.tracks[0].child("pan").child("value");
        pan.afterWrite = () -> f.tracks[0].child("pan").child("displayedValue").emit("???");
        JsonObject result = call(f, m[0], "tracks.set", params("{\"volumeDb\":-6,\"panPercent\":-25}"));
        check(result.get("reason").getAsString().equals("display_unparseable") && !result.get("matchesRequested").getAsBoolean(), "second solve failed: " + result);
        check(Math.abs(((Number) vol.data).doubleValue() - 0.5) < 1e-9, "earlier volume solve restored: " + vol.data);
        check(Math.abs(((Number) pan.data).doubleValue() - 0.5) < 1e-9, "pan restored");
        check(!result.get("otherFieldsApplied").getAsBoolean() && result.get("completion").getAsString().equals("observed"), "clean restore claimed only because it is true: " + result);
    }

    private static void deadlineStopsProbingWhileRestoreFits() {
        MixModule[] m = new MixModule[1]; TransportMixFixture f = fixture(m);
        TransportMixFixture.Node vol = f.tracks[0].child("volume").child("value");
        Runnable show = vol.afterWrite;
        vol.afterWrite = () -> { try { Thread.sleep(100); } catch (InterruptedException e) { throw new AssertionError(e); } show.run(); };
        HostOperation op = f.submit(m[0], "tracks.set", params("{\"volumeDb\":-6}"), System.nanoTime() + 1_300_000_000L);
        JsonObject result = run(f, op);
        check(result.get("reason").getAsString().equals("deadline"), "stopped at the deadline budget, not deadline_exceeded: " + result);
        check(Math.abs(((Number) vol.data).doubleValue() - 0.5) < 1e-9 && result.getAsJsonObject("volumeDisplayWrite").get("restored").getAsBoolean(), "original restored before the deadline");
    }

    private static void explicitDisableFollowsLevelWriteOnDisabledSend() {
        MixModule[] m = new MixModule[1]; TransportMixFixture f = fixture(m);
        TransportMixFixture.Node send = f.tracks[0].child("sendBank").item(0);
        TransportMixFixture.Node enabled = send.child("isEnabled");
        enabled.emit(false);
        Runnable show = send.child("value").afterWrite;
        // The host turns the send on at once, but the observer echo only arrives on a later flush.
        send.child("value").afterWrite = () -> { show.run(); enabled.data = true; f.tasks.add(() -> enabled.emit(enabled.data)); };
        JsonObject result = call(f, m[0], "tracks.sends.set", params("{\"sendIndex\":0,\"valueNormalized\":0.3,\"enabled\":false}"));
        check(Boolean.FALSE.equals(enabled.data), "send really ends disabled: " + result);
        check(result.get("matchesRequested").getAsBoolean() && !result.getAsJsonObject("fields").getAsJsonObject("enabled").get("verification").getAsString().equals("already_at_value"),
                "enabled write dispatched and verified, not skipped against stale metadata: " + result);
    }

    private static void failedSendProbeRestoresEnabledState() {
        MixModule[] m = new MixModule[1]; TransportMixFixture f = fixture(m);
        TransportMixFixture.Node send = f.tracks[0].child("sendBank").item(0);
        TransportMixFixture.Node enabled = send.child("isEnabled");
        enabled.emit(false);
        Runnable show = send.child("value").afterWrite;
        send.child("value").afterWrite = () -> { show.run(); enabled.emit(true); };
        JsonObject result = call(f, m[0], "tracks.sends.set", params("{\"sendIndex\":0,\"display\":\"5 dB\"}"));
        check(result.get("reason").getAsString().equals("display_not_reachable"), "unreachable: " + result);
        check(Boolean.FALSE.equals(enabled.data), "enabled state restored");
        check(result.getAsJsonObject("displayWrite").get("restored").getAsBoolean() && result.get("completion").getAsString().equals("observed"), "restore reported only after both were put back: " + result);
    }

    private static void minusInfinity() {
        MixModule[] m = new MixModule[1]; TransportMixFixture f = fixture(m);
        JsonObject result = call(f, m[0], "tracks.set", params("{\"volumeDisplay\":\"-inf dB\"}"));
        check(result.get("matchesRequested").getAsBoolean() && result.getAsJsonObject("volumeDisplayWrite").get("probes").getAsInt() == 1, "-inf is one probe at the bottom: " + result);
        check(((Number) f.tracks[0].child("volume").child("value").data).doubleValue() == 0, "bottom of the scale written");
    }

    private static void staleTargetStopsDisplayWrite() {
        MixModule[] m = new MixModule[1]; TransportMixFixture f = fixture(m);
        TransportMixFixture.Node vol = f.tracks[0].child("volume").child("value");
        HostOperation op = f.submit(m[0], "tracks.set", params("{\"volumeDb\":-6}")); f.next();
        int writes = vol.writes;
        f.tracks[0].child("channelId").emit("00000000-0000-0000-0000-0000000000ff");
        f.tasks.removeIf(t -> false);
        while (!op.result.isDone() && !f.tasks.isEmpty()) f.next();
        check(op.result.isDone() && vol.writes <= writes + 1, "no further probe writes after the track identity changed");
    }

    private static void check(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }
}
