package dev.bitwigagent;

import static dev.bitwigagent.RpcAssert.*;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Deterministic consumer tests; these do not establish native host tempo or seek behavior. */
public final class TransportModuleTest {

    @Test
    void readsExposeActualValuesAndUnits() {
        HostFixture f = new HostFixture();
        TransportModule module = new TransportModule(f.services);
        JsonObject read = f.call(module, "transport.get", new JsonObject());
        assertTrue(read.get("tempoBpm").getAsDouble() == 120, "tempo is raw BPM, not normalized");
        assertTrue(read.get("positionBeats").getAsDouble() == 0, "current playback position read");
        assertTrue(read.getAsJsonObject("units").get("tempoBpm").getAsString().equals("bpm_raw"), "BPM unit explicit");
        assertTrue(
                read.has("arrangerRecordEnabled")
                        && read.has("arrangerAutomationWriteEnabled")
                        && read.has("clipLauncherAutomationWriteEnabled"),
                "safety states exposed");
        assertTrue(read.getAsJsonObject("tempoRange").get("maximum").getAsInt() == 666, "supported range metadata");
        assertTrue(f.host.calls("createTrackBank") == 1, "transport does not create an extra track bank");
    }

    @Test
    void rejectsInvalidAndEmptyRequestsBeforeScheduling() {
        HostFixture f = new HostFixture();
        TransportModule module = new TransportModule(f.services);
        for (String text : List.of(
                "{}",
                "{\"tempoBpm\":19.99}",
                "{\"tempoBpm\":666.01}",
                "{\"tempoBpm\":\"120\"}",
                "{\"playing\":1}",
                "{\"positionBeats\":-1}",
                "{\"loopDurationBeats\":0}",
                "{\"loopEnabled\":null}",
                "{\"recording\":true}",
                "{\"loopStartBeats\":1e308,\"loopDurationBeats\":1e308}")) {
            assertRpcError("invalid_params", f.submit(module, "transport.set", HostFixture.json(text)));
            assertTrue(f.tasks.isEmpty(), "invalid request must not schedule host work: " + text);
        }
        for (double invalid : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            JsonObject p = new JsonObject();
            p.addProperty("tempoBpm", invalid);
            assertRpcError("invalid_params", f.submit(module, "transport.set", p));
            assertTrue(f.tasks.isEmpty(), "nonfinite BPM must not schedule");
        }
        assertRpcError("invalid_params", f.submit(module, "transport.get", HostFixture.json("{\"playing\":false}")));
        assertTrue(f.tasks.isEmpty(), "reads reject extra fields");
        TransportModule.validateParams("transport.set", HostFixture.json("{\"tempoBpm\":20}"));
        TransportModule.validateParams("transport.set", HostFixture.json("{\"tempoBpm\":666}"));
    }

    @Test
    void optionalFieldsRemainUnchanged() {
        HostFixture f = new HostFixture();
        TransportModule module = new TransportModule(f.services);
        JsonObject result = f.call(module, "transport.set", HostFixture.json("{\"metronome\":true}"));
        assertTrue(result.get("matchesRequested").getAsBoolean(), "explicit metronome setter observes callback");
        assertTrue(f.transport.child("isMetronomeEnabled").writes == 1, "only supplied field written");
        assertTrue(
                f.transport.child("tempo").child("value").writes == 0 && f.transport.actions == 0,
                "tempo and playback left alone");
        assertTrue(f.transport.child("arrangerLoopDuration").writes == 0, "omitted loop duration left alone");
        result = f.call(module, "transport.set", HostFixture.json("{\"metronome\":true}"));
        assertTrue(
                result.getAsJsonObject("fields")
                        .getAsJsonObject("metronome")
                        .get("verification")
                        .getAsString()
                        .equals("already_at_value"),
                "idempotent no-op explicit");
        assertTrue(f.transport.child("isMetronomeEnabled").writes == 1, "no redundant write for matching value");
    }

    @Test
    void recordingAndAutomationGuardPlaybackButNotStop() {
        for (String safety : List.of(
                "isArrangerRecordEnabled",
                "isArrangerAutomationWriteEnabled",
                "isClipLauncherAutomationWriteEnabled")) {
            HostFixture f = new HostFixture();
            TransportModule module = new TransportModule(f.services);
            f.transport.child(safety).emit(true);
            HostOperation start = f.submit(module, "transport.set", HostFixture.json("{\"playing\":true}"));
            f.next();
            assertTrue(assertRpcError("unsafe_state", start).outcome.equals("not_applied"), "unsafe start not applied");
            HostOperation seek = f.submit(module, "transport.set", HostFixture.json("{\"positionBeats\":8}"));
            f.next();
            assertRpcError("unsafe_state", seek);
            assertTrue(f.transport.actions == 0, "unsafe start/seek never dispatched");
            f.transport.child("isPlaying").emit(true);
            JsonObject stopped = f.call(module, "transport.set", HostFixture.json("{\"playing\":false}"));
            assertTrue(
                    stopped.get("matchesRequested").getAsBoolean() && f.transport.actions == 1,
                    "explicit stop remains possible");
            assertTrue(f.transport.child(safety).writes == 0, "safety mode not automatically cleared");
        }
    }

    @Test
    void requiresFreshObservation() {
        HostFixture f = new HostFixture();
        TransportModule module = new TransportModule(f.services);
        FakeHost tempo = f.transport.child("tempo").child("value");
        tempo.notifyWrites = false;
        HostOperation op = f.submit(module, "transport.set", HostFixture.json("{\"tempoBpm\":130}"));
        f.next();
        assertTrue(tempo.writes == 1 && !op.result.isDone(), "setter dispatch alone cannot verify tempo");
        f.transport.child("isMetronomeEnabled").emit(true);
        f.next();
        assertTrue(!op.result.isDone(), "unrelated callback cannot verify tempo");
        tempo.emit(129.0);
        f.next();
        assertTrue(!op.result.isDone(), "mismatched callback does not verify");
        tempo.emit(130.0);
        f.next();
        assertTrue(
                op.result
                        .join()
                        .getAsJsonObject("fields")
                        .getAsJsonObject("tempoBpm")
                        .get("verification")
                        .getAsString()
                        .equals("observer_after_write"),
                "fresh matching raw callback verifies");
        assertTrue(tempo.writes == 1, "observation retries never repeat mutations");
    }

    @Test
    void expiredAndCancelledWorkNeverWrites() {
        HostFixture f = new HostFixture();
        TransportModule module = new TransportModule(f.services);
        HostOperation expired =
                f.submit(module, "transport.set", HostFixture.json("{\"playing\":true}"), System.nanoTime() - 1);
        f.next();
        assertTrue(
                assertRpcError("deadline_exceeded", expired).outcome.equals("not_applied"),
                "expired queue work rejects before dispatch");
        HostOperation cancelled = f.submit(module, "transport.set", HostFixture.json("{\"playing\":true}"));
        cancelled.result.cancel(false);
        f.next();
        assertTrue(f.transport.actions == 0, "late or cancelled queue callback never plays");
    }

    private static void deferStop(HostFixture f) {
        f.transport.child("isPlaying").emit(true);
        f.transport.child("playPosition").emit(12.0);
        f.transport.on("stop", args -> {
            f.mutation();
            f.transport.actions++;
            f.tasks.add(() -> {
                f.transport.child("isPlaying").emit(false);
                f.transport.child("playPosition").emit(12.5);
            });
            return null;
        });
    }

    @Test
    void stopIsObservedBeforeSeeking() {
        HostFixture f = new HostFixture();
        TransportModule module = new TransportModule(f.services);
        deferStop(f);
        HostOperation op =
                f.submit(module, "transport.set", HostFixture.json("{\"playing\":false,\"positionBeats\":0}"));
        f.next();
        assertTrue(
                f.transport.child("playPosition").data.equals(12.0),
                "seek must wait for the asynchronous stop instead of being overwritten by its final position");
        assertTrue(!op.result.isDone(), "stop dispatch alone does not complete the combined request");
        f.next();
        f.next();
        JsonObject result = op.result.join();
        assertTrue(result.get("completion").getAsString().equals("observed"), "stop and subsequent seek both observed");
        assertTrue(
                !result.get("playing").getAsBoolean()
                        && result.get("positionBeats").getAsDouble() == 0,
                "combined request leaves the transport stopped at the requested position");
        assertTrue(f.transport.actions == 2, "one stop and one seek, without mutation retries");
    }

    @Test
    void lateStopDoesNotSeekAfterObservationBudget() throws InterruptedException {
        HostFixture f = new HostFixture();
        TransportModule module = new TransportModule(f.services);
        deferStop(f);
        HostOperation op =
                f.submit(module, "transport.set", HostFixture.json("{\"playing\":false,\"positionBeats\":0}"));
        f.next();
        Thread.sleep(800); // Expire the shared 750-ms observation budget; the fixture's hard deadline remains open.
        f.next();
        f.next();
        JsonObject result = op.result.join();
        assertTrue(
                result.get("completion").getAsString().equals("unknown"),
                "a late observed stop cannot turn the incomplete request into success");
        assertTrue(
                result.getAsJsonObject("fields")
                        .getAsJsonObject("positionBeats")
                        .get("verification")
                        .getAsString()
                        .equals("not_dispatched"),
                "an expired observation budget prevents the dependent seek");
        assertTrue(
                f.transport.child("playPosition").data.equals(12.5) && f.transport.actions == 1,
                "late stop leaves its final position without another mutation");
    }

    private static final int LATE_POLL_ATTEMPTS = 5;

    @Test
    void shortTimeoutYieldsBoundedNotObservedNotDeadlineExceeded() throws InterruptedException {
        // The late poll must land inside the margin before the hard deadline; a scheduler that overshoots the
        // sleep past it never produced the scenario, so try again.
        for (int attempt = 0; attempt < LATE_POLL_ATTEMPTS; attempt++) {
            if (latePollLandedInsideMargin()) return;
        }
        throw new AssertionError("late poll never landed inside the deadline margin");
    }

    private static boolean latePollLandedInsideMargin() throws InterruptedException {
        HostFixture f = new HostFixture();
        TransportModule module = new TransportModule(f.services);
        f.transport.child("tempo").child("value").notifyWrites = false;
        long timeoutMs = 300;
        HostOperation op = f.submit(
                module,
                "transport.set",
                HostFixture.json("{\"tempoBpm\":130}"),
                System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMs));
        f.next();
        assertTrue(!op.result.isDone(), "unobserved write keeps polling");
        // The controller thread is late: poll lands after the observation end but before the hard deadline.
        // Sleep only guarantees a minimum, so aim just past the observation end to leave most of the margin for
        // overshoot.
        Thread.sleep(timeoutMs - HostServices.DEADLINE_MARGIN_NANOS / 1_000_000 + 5);
        f.next();
        if (op.result.isCompletedExceptionally()) return false;
        JsonObject result = op.result.join();
        assertTrue(
                result.get("status").getAsString().equals("dispatched")
                        && result.get("completion").getAsString().equals("unknown"),
                "short timeout yields the bounded dispatched/not-observed result, not deadline_exceeded");
        assertTrue(f.transport.child("tempo").child("value").writes == 1, "no retry write");
        return true;
    }

    @Test
    void cancelledStopDoesNotSeekLater() {
        HostFixture f = new HostFixture();
        TransportModule module = new TransportModule(f.services);
        deferStop(f);
        HostOperation op =
                f.submit(module, "transport.set", HostFixture.json("{\"playing\":false,\"positionBeats\":0}"));
        f.next();
        op.result.cancel(false);
        f.next();
        f.next();
        assertTrue(
                f.transport.child("playPosition").data.equals(12.5),
                "late stop observation cannot trigger a cancelled seek");
        assertTrue(f.transport.actions == 1, "cancellation prevents the remaining mutation");
    }

    @Test
    void safetyChangeWhileStoppingPreventsSeek() {
        HostFixture f = new HostFixture();
        TransportModule module = new TransportModule(f.services);
        deferStop(f);
        HostOperation op =
                f.submit(module, "transport.set", HostFixture.json("{\"playing\":false,\"positionBeats\":0}"));
        f.next();
        f.next();
        f.transport.child("isArrangerAutomationWriteEnabled").emit(true);
        f.next();
        JsonObject result = op.result.join();
        assertTrue(
                result.get("completion").getAsString().equals("unknown")
                        && result.get("reason").getAsString().equals("unsafe_state"),
                "a changed safety condition preserves the partial outcome");
        assertTrue(
                result.getAsJsonObject("fields")
                        .getAsJsonObject("positionBeats")
                        .get("verification")
                        .getAsString()
                        .equals("not_dispatched"),
                "seek is not sent after automation becomes active");
        assertTrue(
                f.transport.child("playPosition").data.equals(12.5) && f.transport.actions == 1,
                "stopped position is not changed by unsafe follow-up work");
    }

    @Test
    void midOperationGuardStopsRemainingFields() {
        HostFixture f = new HostFixture();
        TransportModule module = new TransportModule(f.services);
        f.transport.child("tempo").child("value").afterWrite =
                () -> f.transport.child("isArrangerRecordEnabled").emit(true);
        JsonObject result = f.call(module, "transport.set", HostFixture.json("{\"tempoBpm\":130,\"playing\":true}"));
        assertTrue(
                result.get("status").getAsString().equals("dispatched")
                        && result.get("completion").getAsString().equals("unknown"),
                "partial write is not full success");
        assertTrue(
                result.getAsJsonObject("fields")
                        .getAsJsonObject("playing")
                        .get("verification")
                        .getAsString()
                        .equals("not_dispatched"),
                "second field explicitly not sent");
        assertTrue(f.transport.actions == 0, "rechecked recording guard stops play");
        HostFixture closed = new HostFixture();
        TransportModule second = new TransportModule(closed.services);
        closed.transport.child("tempo").child("value").afterWrite = closed.services::close;
        JsonObject partial =
                closed.call(second, "transport.set", HostFixture.json("{\"tempoBpm\":130,\"metronome\":true}"));
        assertTrue(partial.get("reason").getAsString().equals("unavailable"), "close during write reported honestly");
        assertTrue(
                closed.transport.child("isMetronomeEnabled").writes == 0, "no late second-field write after closure");
    }

    @Test
    void sharedSafetyRequiresObservationsButAllowsStop() {
        HostFixture f = new HostFixture(false);
        TransportModule module = new TransportModule(f.services);
        f.transport.child("isPlaying").emit(false);
        JsonObject read = f.call(module, "transport.get", new JsonObject());
        assertTrue(
                read.get("arrangerRecordEnabled").isJsonNull(),
                "unobserved safety state is null, never default-safe false");
        HostOperation blocked = f.submit(module, "transport.set", HostFixture.json("{\"playing\":true}"));
        f.next();
        assertRpcError("not_ready", blocked);
        assertTrue(f.transport.actions == 0, "unknown shared safety states cannot start playback");
        f.transport.child("isPlaying").emit(true);
        JsonObject stop = f.call(module, "transport.set", HostFixture.json("{\"playing\":false}"));
        assertTrue(
                stop.get("matchesRequested").getAsBoolean(), "explicit stop allowed even before safety initialization");
        for (String key : List.of(
                "isArrangerRecordEnabled", "isArrangerAutomationWriteEnabled", "isClipLauncherAutomationWriteEnabled"))
            f.transport.child(key).emit(false);
        JsonObject start = f.call(module, "transport.set", HostFixture.json("{\"playing\":true}"));
        assertTrue(start.get("matchesRequested").getAsBoolean(), "shared observed safe flags allow explicit start");
    }

    @Test
    void timeSignatureIsValidatedWrittenAndReadBack() {
        HostFixture f = new HostFixture();
        TransportModule module = new TransportModule(f.services);
        assertTrue(
                f.call(module, "transport.get", new JsonObject())
                        .get("timeSignature")
                        .getAsString()
                        .equals("4/4"),
                "time signature read as the host reports it");
        for (String bad : List.of("7", "0/4", "33/4", "4/3", "4/64", "4/0", "a/b", "4/4/4", " 4/4", "04/4", "4 / 4")) {
            JsonObject p = new JsonObject();
            p.addProperty("timeSignature", bad);
            assertRpcError("invalid_params", f.submit(module, "transport.set", p));
            assertTrue(f.tasks.isEmpty(), "invalid time signature not scheduled: " + bad);
        }
        assertRpcError("invalid_params", f.submit(module, "transport.set", HostFixture.json("{\"timeSignature\":4}")));
        JsonObject result = f.call(module, "transport.set", HostFixture.json("{\"timeSignature\":\"7/8\"}"));
        assertTrue(
                result.get("matchesRequested").getAsBoolean()
                        && result.get("timeSignature").getAsString().equals("7/8"),
                "write observed");
        assertTrue(
                result.getAsJsonObject("fields")
                        .getAsJsonObject("timeSignature")
                        .get("verification")
                        .getAsString()
                        .equals("observer_after_write"),
                "verified by observer after the write");
        assertTrue(f.transport.child("timeSignature").writes == 1, "one write");
        result = f.call(module, "transport.set", HostFixture.json("{\"timeSignature\":\"7/8\"}"));
        assertTrue(
                result.getAsJsonObject("fields")
                                .getAsJsonObject("timeSignature")
                                .get("verification")
                                .getAsString()
                                .equals("already_at_value")
                        && f.transport.child("timeSignature").writes == 1,
                "same signature not rewritten");
        f.transport.child("timeSignature").notifyWrites = false;
        HostOperation silent = f.submit(
                module,
                "transport.set",
                HostFixture.json("{\"timeSignature\":\"3/4\"}"),
                System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(300));
        try {
            for (int i = 0; i < 400 && !silent.result.isDone(); i++) {
                f.next();
                Thread.sleep(2);
            }
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        }
        result = silent.result.join();
        assertTrue(
                !result.get("matchesRequested").getAsBoolean()
                        && result.get("completion").getAsString().equals("unknown"),
                "unobserved change stays unknown");
        f.transport.child("isArrangerRecordEnabled").emit(true);
        HostOperation blocked = f.submit(module, "transport.set", HostFixture.json("{\"timeSignature\":\"5/4\"}"));
        f.next();
        assertRpcError("unsafe_state", blocked);
    }

    @Test
    void guideSettingsAreReadWrittenAndVerified() {
        HostFixture f = new HostFixture();
        TransportModule module = new TransportModule(f.services);
        JsonObject read = f.call(module, "transport.get", new JsonObject());
        assertTrue(
                read.get("metronomeVolumeNormalized").getAsDouble() == .5
                        && read.get("preRoll").getAsString().equals("none")
                        && !read.get("punchInEnabled").getAsBoolean()
                        && read.get("defaultLaunchQuantization").getAsString().equals("1")
                        && read.get("recordQuantizationGrid").getAsString().equals("1/16"),
                "new settings are read");
        assertTrue(
                read.getAsJsonObject("options").getAsJsonArray("preRoll").size() == 4
                        && read.getAsJsonObject("options")
                                .getAsJsonArray("recordQuantizationGrid")
                                .get(0)
                                .getAsString()
                                .equals("1/4"),
                "options come from the host's enum definitions");
        for (String bad : List.of(
                "{\"metronomeVolumeNormalized\":1.01}",
                "{\"metronomeVolumeNormalized\":\"1\"}",
                "{\"punchInEnabled\":1}",
                "{\"preRoll\":1}",
                "{\"preRoll\":\"\"}",
                "{\"defaultLaunchQuantization\":null}")) {
            assertRpcError("invalid_params", f.submit(module, "transport.set", HostFixture.json(bad)));
            assertTrue(f.tasks.isEmpty(), "invalid request not scheduled: " + bad);
        }
        HostOperation unlisted =
                f.submit(module, "transport.set", HostFixture.json("{\"metronome\":true,\"preRoll\":\"eight_bars\"}"));
        f.next();
        assertTrue(
                assertRpcError("invalid_params", unlisted).outcome.equals("not_applied"),
                "option the host does not list is refused");
        assertTrue(
                f.transport.child("preRoll").writes == 0 && f.transport.child("isMetronomeEnabled").writes == 0,
                "nothing written when one field is refused");
        JsonObject result = f.call(
                module,
                "transport.set",
                HostFixture.json(
                        "{\"metronomeVolumeNormalized\":0.75,\"preRoll\":\"two_bars\",\"punchInEnabled\":true,\"punchOutEnabled\":true,"
                                + "\"defaultLaunchQuantization\":\"1/2\",\"recordQuantizationGrid\":\"1/8\"}"));
        assertTrue(
                result.get("matchesRequested").getAsBoolean()
                        && result.get("completion").getAsString().equals("observed"),
                "every field verified by observer");
        assertTrue(
                result.getAsJsonObject("fields")
                        .getAsJsonObject("preRoll")
                        .get("verification")
                        .getAsString()
                        .equals("observer_after_write"),
                "per-field verification");
        assertTrue(
                f.transport.child("metronomeVolume").writes == 1
                        && f.transport.child("isPunchInEnabled").writes == 1
                        && f.application.child("recordQuantizationGrid").writes == 1,
                "each setter written once");
        assertTrue(
                f.transport.child("preRoll").data.equals("two_bars")
                        && f.application.child("recordQuantizationGrid").data.equals("1/8"),
                "values landed");
        result =
                f.call(module, "transport.set", HostFixture.json("{\"preRoll\":\"two_bars\",\"punchInEnabled\":true}"));
        assertTrue(
                result.getAsJsonObject("fields")
                                .getAsJsonObject("preRoll")
                                .get("verification")
                                .getAsString()
                                .equals("already_at_value")
                        && f.transport.child("preRoll").writes == 1
                        && f.transport.child("isPunchInEnabled").writes == 1,
                "matching values are not rewritten");
        f.transport.child("preRoll").notifyWrites = false;
        HostOperation silent = f.submit(
                module,
                "transport.set",
                HostFixture.json("{\"preRoll\":\"four_bars\"}"),
                System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(300));
        try {
            for (int i = 0; i < 400 && !silent.result.isDone(); i++) {
                f.next();
                Thread.sleep(2);
            }
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        }
        result = silent.result.join();
        assertTrue(
                !result.get("matchesRequested").getAsBoolean()
                        && result.get("completion").getAsString().equals("unknown"),
                "unobserved enum change stays unknown");
        f.transport.child("isArrangerRecordEnabled").emit(true);
        HostOperation blocked = f.submit(module, "transport.set", HostFixture.json("{\"punchOutEnabled\":false}"));
        f.next();
        assertTrue(
                assertRpcError("unsafe_state", blocked).outcome.equals("not_applied"),
                "recording blocks punch edits like the other settings");
    }

    @Test
    void observedValueWinsWhenGetDisagrees() {
        // Bitwig 6.1.4: the observer reported a stored project value ('bar') while EnumValue.get() returned a listed
        // one.
        HostFixture f = new HostFixture();
        TransportModule module = new TransportModule(f.services);
        FakeHost node = f.transport.child("defaultLaunchQuantization");
        node.emit("bar");
        node.on("get", args -> "1");
        JsonObject read = f.call(module, "transport.get", new JsonObject());
        assertTrue(
                read.get("defaultLaunchQuantization").getAsString().equals("bar")
                        && read.getAsJsonArray("observedOutsideOptions")
                                .get(0)
                                .getAsString()
                                .equals("defaultLaunchQuantization"),
                "flag follows the reported (observed) value, not get()");
        JsonObject result =
                f.call(module, "transport.set", HostFixture.json("{\"defaultLaunchQuantization\":\"bar\"}"));
        assertTrue(
                result.getAsJsonObject("fields")
                                .getAsJsonObject("defaultLaunchQuantization")
                                .get("verification")
                                .getAsString()
                                .equals("already_at_value")
                        && node.writes == 0,
                "the reported value can be written back even when get() disagrees");
    }

    @Test
    void storedValueOutsideOptionsCanBeWrittenBack() {
        HostFixture f = new HostFixture();
        TransportModule module = new TransportModule(f.services);
        f.transport.child("defaultLaunchQuantization").emit("bar");
        JsonObject read = f.call(module, "transport.get", new JsonObject());
        assertTrue(
                read.get("defaultLaunchQuantization").getAsString().equals("bar")
                        && read.getAsJsonArray("observedOutsideOptions").size() == 1
                        && read.getAsJsonArray("observedOutsideOptions")
                                .get(0)
                                .getAsString()
                                .equals("defaultLaunchQuantization"),
                "a stored value outside the host's option list is read and flagged");
        JsonObject result =
                f.call(module, "transport.set", HostFixture.json("{\"defaultLaunchQuantization\":\"bar\"}"));
        assertTrue(
                result.getAsJsonObject("fields")
                                .getAsJsonObject("defaultLaunchQuantization")
                                .get("verification")
                                .getAsString()
                                .equals("already_at_value")
                        && f.transport.child("defaultLaunchQuantization").writes == 0,
                "writing the observed value back is already_at_value");
        HostOperation refused =
                f.submit(module, "transport.set", HostFixture.json("{\"defaultLaunchQuantization\":\"beat\"}"));
        f.next();
        assertRpcError("invalid_params", refused);
        result = f.call(module, "transport.set", HostFixture.json("{\"defaultLaunchQuantization\":\"1\"}"));
        assertTrue(
                result.get("matchesRequested").getAsBoolean()
                        && result.getAsJsonArray("observedOutsideOptions").size() == 0,
                "a listed value still writes and clears the flag");
    }
}
