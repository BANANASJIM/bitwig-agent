package dev.bitwigagent;

import static dev.bitwigagent.RpcAssert.*;
import static org.junit.jupiter.api.Assertions.*;

import com.bitwig.extension.callback.IntegerValueChangedCallback;
import com.google.gson.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** VU window aggregation against simulated meter callbacks; no claim about what Bitwig's meters mean in dB. */
public final class MeterModuleTest {
    private static final Map<String, IntegerValueChangedCallback> callbacks = new HashMap<>();

    private static HostFixture fixture(boolean failing) {
        callbacks.clear();
        HostFixture f = new HostFixture();
        for (int t = 0; t < f.tracks.length; t++) {
            final int slot = t;
            FakeHost track = f.tracks[t];
            track.on("addVuMeterObserver", args -> {
                if (failing) throw new IllegalStateException("not in init");
                if ((Integer) args[0] != MeterModule.RANGE) throw new AssertionError("range");
                callbacks.put(
                        slot + ":" + args[1] + ":" + (((Boolean) args[2]) ? "peak" : "rms"),
                        (IntegerValueChangedCallback) args[3]);
                return null;
            });
        }
        return f;
    }

    private static void fire(int slot, int channel, String kind, int value) {
        callbacks.get(slot + ":" + channel + ":" + kind).valueChanged(value);
    }

    private static JsonObject params(String json) {
        return HostFixture.json(json);
    }

    @Test
    void invalidRequestsNeverSchedule() {
        HostFixture f = fixture(false);
        MeterModule m = f.services.meter();
        String a = HostFixture.A;
        for (String text : List.of(
                "{}",
                "{\"windowMs\":100}",
                "{\"trackIds\":[\"" + a + "\"]}",
                "{\"trackIds\":[],\"windowMs\":100}",
                "{\"trackIds\":[\"" + a + "\"],\"windowMs\":49}",
                "{\"trackIds\":[\"" + a + "\"],\"windowMs\":5001}",
                "{\"trackIds\":[\"not-a-uuid\"],\"windowMs\":100}",
                "{\"trackIds\":[\"" + a + "\",\"" + a + "\"],\"windowMs\":100}",
                "{\"trackIds\":\"" + a + "\",\"windowMs\":100}",
                "{\"trackIds\":[\"" + a + "\"],\"windowMs\":100,\"x\":1}")) {
            assertRpcError("invalid_params", f.submit(m, "tracks.meters", params(text)));
            assertTrue(f.tasks.isEmpty(), "invalid meter request not scheduled: " + text);
        }
    }

    @Test
    void windowAggregatesPeakAndRms() throws InterruptedException {
        HostFixture f = fixture(false);
        MeterModule m = f.services.meter();
        f.transport.child("isPlaying").emit(true);
        fire(0, 0, "peak", 40); // standing level before the window counts as the baseline
        HostOperation op = f.submit(
                m,
                "tracks.meters",
                params("{\"trackIds\":[\"" + HostFixture.A + "\",\"" + HostFixture.B + "\"],\"windowMs\":60}"));
        f.next();
        fire(0, 0, "peak", 90);
        fire(0, 0, "peak", 20);
        fire(0, 1, "peak", 55);
        fire(0, 0, "rms", 30);
        fire(0, 1, "rms", 10);
        while (!op.result.isDone()) f.next();
        JsonObject r = op.result.join();
        JsonObject a = r.getAsJsonArray("tracks").get(0).getAsJsonObject(),
                b = r.getAsJsonArray("tracks").get(1).getAsJsonObject();
        assertTrue(
                a.getAsJsonObject("peakMax").get("left").getAsInt() == 90
                        && a.getAsJsonObject("peakMax").get("right").getAsInt() == 55,
                "peak max per channel: " + a);
        assertTrue(
                a.getAsJsonObject("rmsMax").get("left").getAsInt() == 30
                        && a.getAsJsonObject("rmsMax").get("right").getAsInt() == 10,
                "rms max per channel");
        assertTrue(
                a.getAsJsonObject("rmsMean").get("left").getAsDouble() > 0
                        && a.get("callbacks").getAsInt() == 5,
                "rms mean sampled by polls; callbacks counted");
        assertTrue(
                a.get("silent").getAsBoolean() == false
                        && b.get("silent").getAsBoolean()
                        && b.getAsJsonObject("peakMax").get("left").getAsInt() == 0,
                "quiet track reads silent, other track not contaminated");
        assertTrue(
                r.get("unit").getAsString().contains("no_db_mapping")
                        && r.get("range").getAsInt() == 128,
                "steps, never dB");
        assertTrue(
                r.get("playingAtStart").getAsBoolean() && r.get("windowMs").getAsInt() == 60,
                "playing state and window reported");
        assertTrue(a.get("targetUnchanged").getAsBoolean(), "identity confirmed at the end");
    }

    @Test
    void shortTimeoutIsRefused() {
        HostFixture f = fixture(false);
        MeterModule m = f.services.meter();
        HostOperation op = f.submit(
                m,
                "tracks.meters",
                params("{\"trackIds\":[\"" + HostFixture.A + "\"],\"windowMs\":2000}"),
                f.tasks.after(500));
        f.next();
        assertRpcError("invalid_params", op);
    }

    @Test
    void unknownTrackAndMissingObserverFailClosed() {
        HostFixture f = fixture(false);
        MeterModule m = f.services.meter();
        HostOperation op = f.submit(
                m,
                "tracks.meters",
                params("{\"trackIds\":[\"00000000-0000-0000-0000-0000000000aa\"],\"windowMs\":100}"));
        f.next();
        assertRpcError("outside_window", op);
        HostFixture g = fixture(true);
        MeterModule broken = g.services.meter();
        HostOperation unavailable = g.submit(
                broken, "tracks.meters", params("{\"trackIds\":[\"" + HostFixture.A + "\"],\"windowMs\":100}"));
        g.next();
        assertRpcError("unsupported_scope", unavailable);
    }
}
