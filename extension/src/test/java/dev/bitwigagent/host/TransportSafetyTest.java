package dev.bitwigagent.host;

import static dev.bitwigagent.RpcAssert.*;
import static org.junit.jupiter.api.Assertions.*;

import dev.bitwigagent.HostFixture;
import dev.bitwigagent.core.HostOperation;
import dev.bitwigagent.core.RpcException;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The one transport-safety rule set against the simulated transport; no native host. */
public final class TransportSafetyTest {
    private static final List<String> RECORDING = List.of(
            "isArrangerRecordEnabled", "isArrangerAutomationWriteEnabled", "isClipLauncherAutomationWriteEnabled");

    private static HostOperation op() {
        return new HostOperation(Long.MAX_VALUE);
    }

    @Test
    void valueWritesPlayButNotWhileRecording() {
        HostFixture f = new HostFixture();
        f.transport.child("isPlaying").emit(true);
        Runnable guard = f.services.transportSafety.writeGuard(op(), false);
        assertDoesNotThrow(guard::run, "a playing transport admits a value write at enqueue and at delivery");
        for (String key : RECORDING) {
            HostFixture blocked = new HostFixture();
            blocked.transport.child(key).emit(true);
            assertRpcError("unsafe_state", () -> blocked.services.transportSafety.writeGuard(op(), false));
            f.transport.child(key).emit(true);
            assertRpcError("unsafe_state", guard);
            f.transport.child(key).emit(false);
        }
        assertDoesNotThrow(guard::run, "turning recording off again re-admits the same guard");
    }

    @Test
    void structuralWritesNeedStoppedTransport() {
        HostFixture f = new HostFixture();
        f.transport.child("isPlaying").emit(true);
        assertRpcError("unsafe_state", () -> f.services.transportSafety.writeGuard(op(), true));
        f.transport.child("isPlaying").emit(false);
        Runnable guard = f.services.transportSafety.writeGuard(op(), true);
        f.transport.child("isPlaying").emit(true);
        assertRpcError("unsafe_state", guard);
    }

    @Test
    void unobservedSafetyIsNotReady() {
        HostFixture f = new HostFixture(false);
        assertRpcError("not_ready", () -> f.services.transportSafety.writeGuard(op(), false));
    }

    @Test
    void playbackSafetyNeedsEveryModeObservedOff() {
        HostFixture f = new HostFixture(false);
        TransportSafety safety = f.services.transportSafety;
        assertRpcError("not_ready", safety::requirePlaybackSafe);
        for (String key : RECORDING) f.transport.child(key).emit(false);
        assertDoesNotThrow(safety::requirePlaybackSafe, "all three modes observed off");
        f.transport.child("isArrangerAutomationWriteEnabled").emit(true);
        assertRpcError("unsafe_state", safety::requirePlaybackSafe);
    }

    @Test
    void stoppedNeedsAnObservedStoppedTransport() {
        HostFixture f = new HostFixture(false);
        TransportSafety safety = f.services.transportSafety;
        for (String key : RECORDING) f.transport.child(key).emit(false);
        assertRpcError("not_ready", safety::requireStopped);
        f.transport.child("isPlaying").emit(true);
        RpcException playing = assertRpcError("unsafe_state", safety::requireStopped);
        assertEquals("Transport is playing; stop it first.", playing.getMessage());
        f.transport.child("isPlaying").emit(false);
        assertDoesNotThrow(safety::requireStopped);
        f.transport.child("isClipLauncherAutomationWriteEnabled").emit(true);
        assertRpcError("unsafe_state", safety::requireStopped);
    }

    @Test
    void enqueuedObservationIsWhatTheGuardCompares() {
        HostFixture f = new HostFixture();
        TransportSafety safety = f.services.transportSafety;
        HostOperation queued = op();
        queued.safetyAtEnqueue = safety.observation();
        f.transport.child("isPlaying").emit(true);
        f.transport.child("isPlaying").emit(false);
        assertRpcError("unsafe_state", () -> safety.stopped(queued));
        assertDoesNotThrow(() -> safety.stopped(op()), "an operation without a snapshot reads the current state");
    }

    @Test
    void everyChangeOpensANewEpoch() {
        HostFixture f = new HostFixture();
        TransportSafety safety = f.services.transportSafety;
        long before = safety.observation().epoch;
        f.transport.child("isArrangerRecordEnabled").emit(true);
        assertEquals(before + 1, safety.observation().epoch);
        assertEquals(Boolean.TRUE, safety.observation().recording);
        assertEquals(Boolean.FALSE, safety.observation().playing, "other fields keep their observed values");
    }
}
