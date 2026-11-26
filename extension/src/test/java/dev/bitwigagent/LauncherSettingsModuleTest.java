package dev.bitwigagent;

import static dev.bitwigagent.RpcAssert.*;
import static org.junit.jupiter.api.Assertions.*;

import com.bitwig.extension.callback.*;
import com.bitwig.extension.controller.api.*;
import com.google.gson.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

/** Simulated queues and native adapter; does not establish host binding behavior. */
public final class LauncherSettingsModuleTest {
    static final String ID = "11111111-1111-1111-1111-111111111111";

    @Test
    void invalidSettingsAreRejectedBeforeScheduling() {
        JsonObject p = target();
        LauncherSettingsModule.validateParams("launcher.get", p);
        bad("launcher.set", p);
        for (String field : List.of("loopEnabled", "nextActionEnabled", "nextActionLinkToLoop")) {
            JsonObject b = target();
            b.addProperty(field, 1);
            bad("launcher.set", b);
        }
        for (String field : List.of(
                "playStartBeats", "playStopBeats", "loopStartBeats", "loopLengthBeats", "nextActionDelayBeats")) {
            JsonObject b = target();
            b.addProperty(field, -1);
            bad("launcher.set", b);
        }
        for (String field : List.of("playStartBeats", "playStopBeats", "loopStartBeats", "loopLengthBeats")) {
            JsonObject b = target();
            b.addProperty(field, 16);
            bad("launcher.set", b);
        }
        JsonObject readOnly = target();
        readOnly.addProperty("loopEnabled", true);
        bad("launcher.set", readOnly);
        JsonObject b = target();
        b.addProperty("nextAction", "random");
        bad("launcher.set", b);
        b = target();
        b.addProperty("nextActionLoopCount", 65);
        bad("launcher.set", b);
        b = target();
        b.addProperty("playStartBeats", 8);
        b.addProperty("playStopBeats", 4);
        bad("launcher.set", b);
    }

    private static JsonObject settings() {
        JsonObject settings = target();
        settings.addProperty("nextAction", "play_next");
        settings.addProperty("nextActionLoopCount", 1);
        return settings;
    }

    @Test
    void sourceBoundWriteIsReadBackAndReadsDoNotWrite() {
        Fixture f = new Fixture();
        JsonObject settings = settings();
        JsonObject written = f.call("launcher.set", settings);
        assertTrue(
                written.get("nextAction").getAsString().equals("play_next")
                        && written.get("outcome").getAsString().equals("applied"),
                "source-bound write read back");
        assertTrue(f.writes == 1, "one write");
        JsonObject read = f.call("launcher.get", target());
        assertTrue(f.writes == 1 && !read.has("outcome"), "read does not write and carries no outcome");
        JsonObject b = target();
        b.addProperty("expectedClipName", "Wrong");
        assertRpcError("stale_context", f.start("launcher.get", b));
        assertTrue(f.calls == 2, "wrong name rejected before native access");
    }

    @Test
    void unknownWriteStaysQuarantinedWhileReadsAreAllowed() {
        JsonObject settings = settings();
        Fixture fail = new Fixture();
        fail.failWrite = true;
        HostOperation failed = fail.start("launcher.set", settings);
        fail.drain();
        assertUnknown("internal", failed);
        RpcException blocked = assertRpcError("unresolved", fail.start("launcher.set", settings));
        assertTrue(blocked.next.contains("ack " + LauncherStructureLock.SETTINGS), "the refusal names the hold to ack");
        fail.call("launcher.get", target());
        assertTrue(fail.writes == 1, "unknown write stays quarantined but reads allowed");
    }

    @Test
    void cancelledQueuedOperationDoesNotWrite() {
        JsonObject settings = settings();
        Fixture late = new Fixture();
        HostOperation op = late.start("launcher.set", settings);
        op.result.completeExceptionally(new RuntimeException("cancel"));
        late.drain();
        assertTrue(late.writes == 0, "cancelled queued native operation does not write");
    }

    @Test
    void playingTransportBlocksTheSettingsWrite() {
        JsonObject settings = settings();
        Fixture unsafe = new Fixture(true);
        assertRpcError("unsafe_state", unsafe.start("launcher.set", settings));
        assertTrue(unsafe.calls == 0, "playing blocks settings write");
    }

    @Test
    void refusalsAndValidation() {
        assertRpcError("unknown_method", () -> LauncherSettingsModule.validateParams("launcher.bogus", target()));
        JsonObject badId = settings();
        badId.addProperty("trackId", "not-a-uuid");
        bad("launcher.set", badId);
        JsonObject longName = settings();
        longName.addProperty("expectedClipName", "x".repeat(257));
        bad("launcher.set", longName);

        Fixture f = new Fixture();
        JsonObject launch = target();
        launch.addProperty("launchMode", "gate");
        assertRpcError("native_unavailable", f.start("launcher.set", launch));

        f.host.services.holds.inFlight(LauncherStructureLock.SETTINGS_PENDING, "pending");
        assertRpcError("busy", f.start("launcher.get", target()));
        f.host.services.holds.release(LauncherStructureLock.SETTINGS_PENDING);

        LauncherSettingsModule noAccess = f.host.services.launcherSettings((LauncherSettingsModule.Access) null);
        assertRpcError("host_mismatch", f.host.start(noAccess, "launcher.set", settings()));
    }

    static JsonObject target() {
        JsonObject p = new JsonObject();
        p.addProperty("trackId", ID);
        p.addProperty("slotIndex", 0);
        p.addProperty("expectedClipName", "Verse");
        return p;
    }

    static void bad(String m, JsonObject p) {
        assertRpcError("invalid_params", () -> LauncherSettingsModule.validateParams(m, p));
    }

    static final class Fixture {
        final HostFixture host = new HostFixture();
        final ScheduledTasks queue = host.tasks;
        final LauncherSettingsModule module;
        int writes, calls;
        boolean failWrite, playing;

        Fixture() {
            this(false);
        }

        Fixture(boolean playing) {
            this.playing = playing;
            host.tracks[0].child("channelId").emit(ID);
            host.transport.child("isPlaying").emit(playing);
            host.occupySlot(0, 0, "Verse");
            module = host.services.launcherSettings(new LauncherSettingsModule.Access() {
                public void execute(Runnable task) {
                    queue.add(task);
                }

                public JsonObject apply(
                        Object slot, String id, String name, JsonObject changes, Runnable guard, Runnable before) {
                    calls++;
                    guard.run();
                    if (!changes.entrySet().isEmpty()) {
                        before.run();
                        writes++;
                        if (failWrite) throw new RuntimeException("native exception");
                    }
                    JsonObject out = changes.deepCopy();
                    JsonObject fields = new JsonObject();
                    for (var change : changes.entrySet())
                        fields.add(
                                change.getKey(),
                                Results.field(change.getValue(), change.getValue(), Outcome.APPLIED, null));
                    out.add("fields", fields);
                    return out;
                }
            });
        }

        HostOperation start(String m, JsonObject p) {
            return host.start(module, m, p);
        }

        void drain() {
            host.drain();
        }

        JsonObject call(String m, JsonObject p) {
            HostOperation op = start(m, p);
            drain();
            return op.result.join();
        }
    }
}
