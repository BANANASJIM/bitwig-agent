package dev.bitwigagent;

import com.google.gson.*;
import java.util.List;

/** Deterministic consumer tests; these do not establish native host tempo or seek behavior. */
public final class TransportModuleTest {
    private static int assertions;
    public static void main(String[] args) throws InterruptedException {
        readsExposeActualValuesAndUnits();
        rejectsInvalidAndEmptyRequestsBeforeScheduling();
        optionalFieldsRemainUnchanged();
        recordingAndAutomationGuardPlaybackButNotStop();
        requiresFreshObservation();
        expiredAndCancelledWorkNeverWrites();
        midOperationGuardStopsRemainingFields();
        stopIsObservedBeforeSeeking();
        lateStopDoesNotSeekAfterObservationBudget();
        cancelledStopDoesNotSeekLater();
        safetyChangeWhileStoppingPreventsSeek();
        sharedSafetyRequiresObservationsButAllowsStop();
        System.out.println("TransportModuleTest: " + assertions + " assertions passed (simulated host only)");
    }
    private static void readsExposeActualValuesAndUnits() {
        TransportMixFixture f = new TransportMixFixture(); TransportModule module = new TransportModule(f.services);
        JsonObject read = f.call(module, "transport.get", new JsonObject());
        check(read.get("tempoBpm").getAsDouble() == 120, "tempo is raw BPM, not normalized");
        check(read.get("positionBeats").getAsDouble() == 0, "current playback position read");
        check(read.getAsJsonObject("units").get("tempoBpm").getAsString().equals("bpm_raw"), "BPM unit explicit");
        check(read.has("arrangerRecordEnabled") && read.has("arrangerAutomationWriteEnabled") && read.has("clipLauncherAutomationWriteEnabled"), "safety states exposed");
        check(read.getAsJsonObject("tempoRange").get("maximum").getAsInt() == 666, "supported range metadata");
        check(f.bankCreations == 1, "transport does not create an extra track bank");
    }
    private static void rejectsInvalidAndEmptyRequestsBeforeScheduling() {
        TransportMixFixture f = new TransportMixFixture(); TransportModule module = new TransportModule(f.services);
        for (String text : List.of("{}", "{\"tempoBpm\":19.99}", "{\"tempoBpm\":666.01}", "{\"tempoBpm\":\"120\"}",
                "{\"playing\":1}", "{\"positionBeats\":-1}", "{\"loopDurationBeats\":0}", "{\"loopEnabled\":null}",
                "{\"recording\":true}", "{\"loopStartBeats\":1e308,\"loopDurationBeats\":1e308}")) {
            TransportMixFixture.error(f.submit(module, "transport.set", TransportMixFixture.json(text)), "invalid_params");
            check(f.tasks.isEmpty(), "invalid request must not schedule host work: " + text);
        }
        for (double invalid : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            JsonObject p = new JsonObject(); p.addProperty("tempoBpm", invalid);
            TransportMixFixture.error(f.submit(module, "transport.set", p), "invalid_params");
            check(f.tasks.isEmpty(), "nonfinite BPM must not schedule");
        }
        TransportMixFixture.error(f.submit(module, "transport.get", TransportMixFixture.json("{\"playing\":false}")), "invalid_params");
        check(f.tasks.isEmpty(), "reads reject extra fields");
        TransportModule.validateParams("transport.set", TransportMixFixture.json("{\"tempoBpm\":20}"));
        TransportModule.validateParams("transport.set", TransportMixFixture.json("{\"tempoBpm\":666}"));
    }
    private static void optionalFieldsRemainUnchanged() {
        TransportMixFixture f = new TransportMixFixture(); TransportModule module = new TransportModule(f.services);
        JsonObject result = f.call(module, "transport.set", TransportMixFixture.json("{\"metronome\":true}"));
        check(result.get("matchesRequested").getAsBoolean(), "explicit metronome setter observes callback");
        check(f.transport.child("isMetronomeEnabled").writes == 1, "only supplied field written");
        check(f.transport.child("tempo").child("value").writes == 0 && f.transport.actions == 0, "tempo and playback left alone");
        check(f.transport.child("arrangerLoopDuration").writes == 0, "omitted loop duration left alone");
        result = f.call(module, "transport.set", TransportMixFixture.json("{\"metronome\":true}"));
        check(result.getAsJsonObject("fields").getAsJsonObject("metronome").get("verification").getAsString().equals("already_at_value"), "idempotent no-op explicit");
        check(f.transport.child("isMetronomeEnabled").writes == 1, "no redundant write for matching value");
    }
    private static void recordingAndAutomationGuardPlaybackButNotStop() {
        for (String safety : List.of("isArrangerRecordEnabled", "isArrangerAutomationWriteEnabled", "isClipLauncherAutomationWriteEnabled")) {
            TransportMixFixture f = new TransportMixFixture(); TransportModule module = new TransportModule(f.services);
            f.transport.child(safety).emit(true);
            HostOperation start = f.submit(module, "transport.set", TransportMixFixture.json("{\"playing\":true}")); f.next();
            check(TransportMixFixture.error(start, "unsafe_state").outcome.equals("not_applied"), "unsafe start not applied");
            HostOperation seek = f.submit(module, "transport.set", TransportMixFixture.json("{\"positionBeats\":8}")); f.next();
            TransportMixFixture.error(seek, "unsafe_state");
            check(f.transport.actions == 0, "unsafe start/seek never dispatched");
            f.transport.child("isPlaying").emit(true);
            JsonObject stopped = f.call(module, "transport.set", TransportMixFixture.json("{\"playing\":false}"));
            check(stopped.get("matchesRequested").getAsBoolean() && f.transport.actions == 1, "explicit stop remains possible");
            check(f.transport.child(safety).writes == 0, "safety mode not automatically cleared");
        }
    }
    private static void requiresFreshObservation() {
        TransportMixFixture f = new TransportMixFixture(); TransportModule module = new TransportModule(f.services);
        TransportMixFixture.Node tempo = f.transport.child("tempo").child("value"); tempo.emitWrites = false;
        HostOperation op = f.submit(module, "transport.set", TransportMixFixture.json("{\"tempoBpm\":130}")); f.next();
        check(tempo.writes == 1 && !op.result.isDone(), "setter dispatch alone cannot verify tempo");
        f.transport.child("isMetronomeEnabled").emit(true); f.next();
        check(!op.result.isDone(), "unrelated callback cannot verify tempo");
        tempo.emit(129.0); f.next(); check(!op.result.isDone(), "mismatched callback does not verify");
        tempo.emit(130.0); f.next();
        check(op.result.join().getAsJsonObject("fields").getAsJsonObject("tempoBpm").get("verification").getAsString().equals("observer_after_write"), "fresh matching raw callback verifies");
        check(tempo.writes == 1, "observation retries never repeat mutations");
    }
    private static void expiredAndCancelledWorkNeverWrites() {
        TransportMixFixture f = new TransportMixFixture(); TransportModule module = new TransportModule(f.services);
        HostOperation expired = f.submit(module, "transport.set", TransportMixFixture.json("{\"playing\":true}"), System.nanoTime() - 1); f.next();
        check(TransportMixFixture.error(expired, "deadline_exceeded").outcome.equals("not_applied"), "expired queue work rejects before dispatch");
        HostOperation cancelled = f.submit(module, "transport.set", TransportMixFixture.json("{\"playing\":true}"));
        cancelled.result.cancel(false); f.next();
        check(f.transport.actions == 0, "late or cancelled queue callback never plays");
    }
    private static void deferStop(TransportMixFixture f) {
        f.transport.child("isPlaying").emit(true);
        f.transport.child("playPosition").emit(12.0);
        TransportMixFixture.Custom original = f.transport.custom;
        f.transport.custom = (name, args) -> {
            if (!name.equals("stop")) return original.invoke(name, args);
            f.mutation(); f.transport.actions++;
            f.tasks.add(() -> {
                f.transport.child("isPlaying").emit(false);
                f.transport.child("playPosition").emit(12.5);
            });
            return null;
        };
    }
    private static void stopIsObservedBeforeSeeking() {
        TransportMixFixture f = new TransportMixFixture(); TransportModule module = new TransportModule(f.services);
        deferStop(f);
        HostOperation op = f.submit(module, "transport.set", TransportMixFixture.json("{\"playing\":false,\"positionBeats\":0}"));
        f.next();
        check(f.transport.child("playPosition").data.equals(12.0), "seek must wait for the asynchronous stop instead of being overwritten by its final position");
        check(!op.result.isDone(), "stop dispatch alone does not complete the combined request");
        f.next(); f.next();
        JsonObject result = op.result.join();
        check(result.get("completion").getAsString().equals("observed"), "stop and subsequent seek both observed");
        check(!result.get("playing").getAsBoolean() && result.get("positionBeats").getAsDouble() == 0, "combined request leaves the transport stopped at the requested position");
        check(f.transport.actions == 2, "one stop and one seek, without mutation retries");
    }
    private static void lateStopDoesNotSeekAfterObservationBudget() throws InterruptedException {
        TransportMixFixture f = new TransportMixFixture(); TransportModule module = new TransportModule(f.services);
        deferStop(f);
        HostOperation op = f.submit(module, "transport.set", TransportMixFixture.json("{\"playing\":false,\"positionBeats\":0}"));
        f.next();
        Thread.sleep(800); // Expire the shared 750-ms observation budget; the fixture's hard deadline remains open.
        f.next(); f.next();
        JsonObject result = op.result.join();
        check(result.get("completion").getAsString().equals("unknown"), "a late observed stop cannot turn the incomplete request into success");
        check(result.getAsJsonObject("fields").getAsJsonObject("positionBeats").get("verification").getAsString().equals("not_dispatched"), "an expired observation budget prevents the dependent seek");
        check(f.transport.child("playPosition").data.equals(12.5) && f.transport.actions == 1, "late stop leaves its final position without another mutation");
    }
    private static void cancelledStopDoesNotSeekLater() {
        TransportMixFixture f = new TransportMixFixture(); TransportModule module = new TransportModule(f.services);
        deferStop(f);
        HostOperation op = f.submit(module, "transport.set", TransportMixFixture.json("{\"playing\":false,\"positionBeats\":0}"));
        f.next(); op.result.cancel(false);
        f.next(); f.next();
        check(f.transport.child("playPosition").data.equals(12.5), "late stop observation cannot trigger a cancelled seek");
        check(f.transport.actions == 1, "cancellation prevents the remaining mutation");
    }
    private static void safetyChangeWhileStoppingPreventsSeek() {
        TransportMixFixture f = new TransportMixFixture(); TransportModule module = new TransportModule(f.services);
        deferStop(f);
        HostOperation op = f.submit(module, "transport.set", TransportMixFixture.json("{\"playing\":false,\"positionBeats\":0}"));
        f.next(); f.next();
        f.transport.child("isArrangerAutomationWriteEnabled").emit(true);
        f.next();
        JsonObject result = op.result.join();
        check(result.get("completion").getAsString().equals("unknown") && result.get("reason").getAsString().equals("unsafe_state"), "a changed safety condition preserves the partial outcome");
        check(result.getAsJsonObject("fields").getAsJsonObject("positionBeats").get("verification").getAsString().equals("not_dispatched"), "seek is not sent after automation becomes active");
        check(f.transport.child("playPosition").data.equals(12.5) && f.transport.actions == 1, "stopped position is not changed by unsafe follow-up work");
    }
    private static void midOperationGuardStopsRemainingFields() {
        TransportMixFixture f = new TransportMixFixture(); TransportModule module = new TransportModule(f.services);
        f.transport.child("tempo").child("value").afterWrite = () -> f.transport.child("isArrangerRecordEnabled").emit(true);
        JsonObject result = f.call(module, "transport.set", TransportMixFixture.json("{\"tempoBpm\":130,\"playing\":true}"));
        check(result.get("status").getAsString().equals("dispatched") && result.get("completion").getAsString().equals("unknown"), "partial write is not full success");
        check(result.getAsJsonObject("fields").getAsJsonObject("playing").get("verification").getAsString().equals("not_dispatched"), "second field explicitly not sent");
        check(f.transport.actions == 0, "rechecked recording guard stops play");
        TransportMixFixture closed = new TransportMixFixture(); TransportModule second = new TransportModule(closed.services);
        closed.transport.child("tempo").child("value").afterWrite = closed.services::close;
        JsonObject partial = closed.call(second, "transport.set", TransportMixFixture.json("{\"tempoBpm\":130,\"metronome\":true}"));
        check(partial.get("reason").getAsString().equals("unavailable"), "close during write reported honestly");
        check(closed.transport.child("isMetronomeEnabled").writes == 0, "no late second-field write after closure");
    }
    private static void sharedSafetyRequiresObservationsButAllowsStop() {
        TransportMixFixture f = new TransportMixFixture(false); TransportModule module = new TransportModule(f.services);
        f.transport.child("isPlaying").emit(false);
        JsonObject read = f.call(module, "transport.get", new JsonObject());
        check(read.get("arrangerRecordEnabled").isJsonNull(), "unobserved safety state is null, never default-safe false");
        HostOperation blocked = f.submit(module, "transport.set", TransportMixFixture.json("{\"playing\":true}")); f.next();
        TransportMixFixture.error(blocked, "not_ready");
        check(f.transport.actions == 0, "unknown shared safety states cannot start playback");
        f.transport.child("isPlaying").emit(true);
        JsonObject stop = f.call(module, "transport.set", TransportMixFixture.json("{\"playing\":false}"));
        check(stop.get("matchesRequested").getAsBoolean(), "explicit stop allowed even before safety initialization");
        for (String key : List.of("isArrangerRecordEnabled", "isArrangerAutomationWriteEnabled", "isClipLauncherAutomationWriteEnabled"))
            f.transport.child(key).emit(false);
        JsonObject start = f.call(module, "transport.set", TransportMixFixture.json("{\"playing\":true}"));
        check(start.get("matchesRequested").getAsBoolean(), "shared observed safe flags allow explicit start");
    }
    private static void check(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }
}
