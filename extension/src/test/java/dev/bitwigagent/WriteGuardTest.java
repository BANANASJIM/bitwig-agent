package dev.bitwigagent;

import static dev.bitwigagent.RpcAssert.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The shared Grid and modulator write gate against the simulated transport; no native host. */
public final class WriteGuardTest {
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
        assertRpcError("stale_context", guard);
    }

    @Test
    void unobservedSafetyIsNotReady() {
        HostFixture f = new HostFixture(false);
        assertRpcError("not_ready", () -> f.services.transportSafety.writeGuard(op(), false));
    }
}
