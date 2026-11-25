package dev.bitwigagent;

import com.bitwig.extension.callback.IntegerValueChangedCallback;
import com.google.gson.*;
import java.util.*;

/** VU window aggregation against simulated meter callbacks; no claim about what Bitwig's meters mean in dB. */
public final class MeterModuleTest {
    private static int assertions;
    private static final Map<String, IntegerValueChangedCallback> callbacks = new HashMap<>();

    public static void main(String[] args) throws InterruptedException {
        invalidRequestsNeverSchedule();
        windowAggregatesPeakAndRms();
        shortTimeoutIsRefused();
        unknownTrackAndMissingObserverFailClosed();
        System.out.println("MeterModuleTest: " + assertions + " assertions passed (simulated host only)");
    }

    private static TransportMixFixture fixture(boolean failing) {
        callbacks.clear();
        TransportMixFixture f = new TransportMixFixture();
        for (int t = 0; t < f.tracks.length; t++) {
            final int slot = t;
            TransportMixFixture.Node track = f.tracks[t];
            TransportMixFixture.Custom prior = track.custom;
            track.custom = (name, args) -> {
                if (name.equals("addVuMeterObserver")) {
                    if (failing) throw new IllegalStateException("not in init");
                    if ((Integer) args[0] != MeterModule.RANGE) throw new AssertionError("range");
                    callbacks.put(slot + ":" + args[1] + ":" + (((Boolean) args[2]) ? "peak" : "rms"), (IntegerValueChangedCallback) args[3]);
                    return null;
                }
                return prior.invoke(name, args);
            };
        }
        return f;
    }
    private static void fire(int slot, int channel, String kind, int value) { callbacks.get(slot + ":" + channel + ":" + kind).valueChanged(value); }
    private static JsonObject params(String json) { return TransportMixFixture.json(json); }

    private static void invalidRequestsNeverSchedule() {
        TransportMixFixture f = fixture(false); MeterModule m = new MeterModule(f.services);
        String a = TransportMixFixture.A;
        for (String text : List.of("{}", "{\"windowMs\":100}", "{\"trackIds\":[\"" + a + "\"]}", "{\"trackIds\":[],\"windowMs\":100}",
                "{\"trackIds\":[\"" + a + "\"],\"windowMs\":49}", "{\"trackIds\":[\"" + a + "\"],\"windowMs\":5001}",
                "{\"trackIds\":[\"not-a-uuid\"],\"windowMs\":100}", "{\"trackIds\":[\"" + a + "\",\"" + a + "\"],\"windowMs\":100}",
                "{\"trackIds\":\"" + a + "\",\"windowMs\":100}", "{\"trackIds\":[\"" + a + "\"],\"windowMs\":100,\"x\":1}")) {
            TransportMixFixture.error(f.submit(m, "tracks.meters", params(text)), "invalid_params");
            check(f.tasks.isEmpty(), "invalid meter request not scheduled: " + text);
        }
    }

    private static void windowAggregatesPeakAndRms() throws InterruptedException {
        TransportMixFixture f = fixture(false); MeterModule m = new MeterModule(f.services);
        f.transport.child("isPlaying").emit(true);
        fire(0, 0, "peak", 40); // standing level before the window counts as the baseline
        HostOperation op = f.submit(m, "tracks.meters", params("{\"trackIds\":[\"" + TransportMixFixture.A + "\",\"" + TransportMixFixture.B + "\"],\"windowMs\":60}"));
        f.next();
        fire(0, 0, "peak", 90); fire(0, 0, "peak", 20); fire(0, 1, "peak", 55);
        fire(0, 0, "rms", 30); fire(0, 1, "rms", 10);
        for (int i = 0; i < 500 && !op.result.isDone(); i++) { Thread.sleep(5); f.next(); }
        JsonObject r = op.result.join();
        JsonObject a = r.getAsJsonArray("tracks").get(0).getAsJsonObject(), b = r.getAsJsonArray("tracks").get(1).getAsJsonObject();
        check(a.getAsJsonObject("peakMax").get("left").getAsInt() == 90 && a.getAsJsonObject("peakMax").get("right").getAsInt() == 55, "peak max per channel: " + a);
        check(a.getAsJsonObject("rmsMax").get("left").getAsInt() == 30 && a.getAsJsonObject("rmsMax").get("right").getAsInt() == 10, "rms max per channel");
        check(a.getAsJsonObject("rmsMean").get("left").getAsDouble() > 0 && a.get("callbacks").getAsInt() == 5, "rms mean sampled by polls; callbacks counted");
        check(a.get("silent").getAsBoolean() == false && b.get("silent").getAsBoolean() && b.getAsJsonObject("peakMax").get("left").getAsInt() == 0, "quiet track reads silent, other track not contaminated");
        check(r.get("unit").getAsString().contains("no_db_mapping") && r.get("range").getAsInt() == 128, "steps, never dB");
        check(r.get("playingAtStart").getAsBoolean() && r.get("windowMs").getAsInt() == 60, "playing state and window reported");
        check(a.get("targetUnchanged").getAsBoolean(), "identity confirmed at the end");
    }

    private static void shortTimeoutIsRefused() {
        TransportMixFixture f = fixture(false); MeterModule m = new MeterModule(f.services);
        HostOperation op = f.submit(m, "tracks.meters", params("{\"trackIds\":[\"" + TransportMixFixture.A + "\"],\"windowMs\":2000}"), System.nanoTime() + 500_000_000L);
        f.next();
        TransportMixFixture.error(op, "invalid_params");
    }

    private static void unknownTrackAndMissingObserverFailClosed() {
        TransportMixFixture f = fixture(false); MeterModule m = new MeterModule(f.services);
        HostOperation op = f.submit(m, "tracks.meters", params("{\"trackIds\":[\"00000000-0000-0000-0000-0000000000aa\"],\"windowMs\":100}"));
        f.next();
        TransportMixFixture.error(op, "outside_window");
        TransportMixFixture g = fixture(true); MeterModule broken = new MeterModule(g.services);
        HostOperation unavailable = g.submit(broken, "tracks.meters", params("{\"trackIds\":[\"" + TransportMixFixture.A + "\"],\"windowMs\":100}"));
        g.next();
        TransportMixFixture.error(unavailable, "unsupported_scope");
    }

    private static void check(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }
}
