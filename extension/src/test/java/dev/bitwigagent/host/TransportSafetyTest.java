package dev.bitwigagent.host;

import static dev.bitwigagent.RpcAssert.*;
import static org.junit.jupiter.api.Assertions.*;

import dev.bitwigagent.HostFixture;
import dev.bitwigagent.core.HostOperation;
import dev.bitwigagent.core.MethodSpecs.Transport;
import dev.bitwigagent.core.RpcException;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The one transport-safety rule set against the simulated transport; no native host. */
public final class TransportSafetyTest {
    private static final List<String> RECORDING = List.of(
            "isArrangerRecordEnabled", "isArrangerAutomationWriteEnabled", "isClipLauncherAutomationWriteEnabled");

    private static HostOperation op(Transport declared) {
        HostOperation op = new HostOperation(Long.MAX_VALUE);
        op.transport = declared;
        return op;
    }

    @Test
    void notRecordingAdmitsPlaybackButNotRecording() {
        HostFixture f = new HostFixture();
        TransportSafety safety = f.services.transportSafety;
        f.transport.child("isPlaying").emit(true);
        HostOperation op = op(Transport.NOT_RECORDING);
        safety.admit(op);
        assertDoesNotThrow(() -> safety.recheck(op), "a playing transport is fine at enqueue and in flight");
        for (String key : RECORDING) {
            HostFixture blocked = new HostFixture();
            blocked.transport.child(key).emit(true);
            assertRpcError("unsafe_state", () -> blocked.services.transportSafety.admit(op(Transport.NOT_RECORDING)));
            f.transport.child(key).emit(true);
            assertRpcError("unsafe_state", () -> safety.recheck(op));
            f.transport.child(key).emit(false);
        }
        assertDoesNotThrow(() -> safety.recheck(op), "turning recording off again re-admits the same operation");
    }

    @Test
    void stoppedNeedsAStoppedTransportAndNoChangeInFlight() {
        HostFixture f = new HostFixture();
        TransportSafety safety = f.services.transportSafety;
        f.transport.child("isPlaying").emit(true);
        assertRpcError("unsafe_state", () -> safety.admit(op(Transport.STOPPED)));
        f.transport.child("isPlaying").emit(false);
        HostOperation op = op(Transport.STOPPED);
        op.safetyAtEnqueue = safety.observation();
        safety.admit(op);
        f.transport.child("isPlaying").emit(true);
        assertRpcError("unsafe_state", () -> safety.recheck(op));
    }

    @Test
    void aModuleReCheckNeedsADeclaration() {
        HostFixture f = new HostFixture();
        TransportSafety safety = f.services.transportSafety;
        for (Transport undeclared : new Transport[] {null, Transport.ANY}) {
            IllegalStateException missing =
                    assertThrows(IllegalStateException.class, () -> safety.recheck(op(undeclared)));
            assertTrue(missing.getMessage().contains("requires"), missing.getMessage());
        }
        assertDoesNotThrow(() -> safety.admit(op(Transport.ANY)), "nothing declared admits anything");
    }

    @Test
    void aRequestDependentRequirementIsAdmittedOnlyWhenTheDeclarationDoesNotCoverIt() {
        HostFixture f = new HostFixture();
        TransportSafety safety = f.services.transportSafety;
        f.transport.child("isPlaying").emit(true);
        assertRpcError("unsafe_state", () -> safety.atLeast(op(Transport.ANY), Transport.STOPPED));
        assertRpcError("unsafe_state", () -> safety.atLeast(op(Transport.NOT_RECORDING), Transport.STOPPED));
        Runnable covered = safety.atLeast(op(Transport.NOT_RECORDING), Transport.NOT_RECORDING);
        assertDoesNotThrow(covered::run);
        f.transport.child("isPlaying").emit(false);
        Runnable stopped = safety.atLeast(op(null), Transport.STOPPED);
        f.transport.child("isPlaying").emit(true);
        assertRpcError("unsafe_state", stopped);
        Runnable recording = safety.atLeast(op(Transport.ANY), Transport.NOT_RECORDING);
        f.transport.child("isArrangerRecordEnabled").emit(true);
        assertRpcError("unsafe_state", recording);
    }

    @Test
    void unobservedSafetyIsNotReady() {
        HostFixture f = new HostFixture(false);
        assertRpcError("not_ready", () -> f.services.transportSafety.admit(op(Transport.NOT_RECORDING)));
        assertRpcError("not_ready", f.services.transportSafety::requireObserved);
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
        assertDoesNotThrow(safety::requireObserved, "observed, even though on");
    }

    @Test
    void stoppedNeedsAnObservedStoppedTransport() {
        HostFixture f = new HostFixture(false);
        TransportSafety safety = f.services.transportSafety;
        for (String key : RECORDING) f.transport.child(key).emit(false);
        assertRpcError("not_ready", () -> safety.admit(op(Transport.STOPPED)));
        f.transport.child("isPlaying").emit(true);
        RpcException playing = assertRpcError("unsafe_state", () -> safety.admit(op(Transport.STOPPED)));
        assertTrue(playing.getMessage().contains("stopped transport"), playing.getMessage());
        f.transport.child("isPlaying").emit(false);
        assertDoesNotThrow(() -> safety.admit(op(Transport.STOPPED)));
        f.transport.child("isClipLauncherAutomationWriteEnabled").emit(true);
        assertRpcError("unsafe_state", () -> safety.admit(op(Transport.STOPPED)));
    }

    @Test
    void enqueuedObservationIsWhatTheGuardCompares() {
        HostFixture f = new HostFixture();
        TransportSafety safety = f.services.transportSafety;
        HostOperation queued = op(Transport.STOPPED);
        queued.safetyAtEnqueue = safety.observation();
        f.transport.child("isPlaying").emit(true);
        f.transport.child("isPlaying").emit(false);
        assertRpcError("unsafe_state", () -> safety.admit(queued));
        assertDoesNotThrow(
                () -> safety.admit(op(Transport.STOPPED)), "an operation without a snapshot reads the current state");
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
