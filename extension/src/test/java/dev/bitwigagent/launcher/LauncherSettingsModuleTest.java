package dev.bitwigagent.launcher;

import static dev.bitwigagent.RpcAssert.*;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import dev.bitwigagent.core.Holds;
import dev.bitwigagent.core.HostOperation;
import dev.bitwigagent.core.RpcException;
import dev.bitwigagent.host.LauncherStructureLock;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Simulated queues and native adapter; does not establish host binding behavior. */
public final class LauncherSettingsModuleTest {
    private static final String OTHER = "22222222-2222-2222-2222-222222222222";

    private static JsonObject changes() {
        JsonObject changes = new JsonObject();
        changes.addProperty("nextAction", "play_next");
        changes.addProperty("nextActionLoopCount", 1);
        return changes;
    }

    /** The settings service over the launcher world, with a native access that queues its work. */
    private static final class Fixture {
        final LauncherFake f = new LauncherFake();
        final LauncherSettingsModule module;
        Runnable beforeRun = () -> {}, afterApply = () -> {};
        int applies;
        boolean playing;

        Fixture() {
            f.scenes = 40;
            f.slots[3].content = true;
            f.slots[3].name = "Verse";
            f.base.transport.live("isPlaying", () -> playing);
            module = f.services.launcherSettings(new LauncherSettingsModule.Access() {
                @Override
                public void execute(Runnable work) {
                    f.tasks.add(() -> {
                        Runnable hook = beforeRun;
                        beforeRun = () -> {};
                        hook.run();
                        work.run();
                    });
                }

                @Override
                public JsonObject apply(
                        Object slot,
                        String id,
                        String expectedName,
                        JsonObject changes,
                        Runnable check,
                        Runnable beforeWrite) {
                    applies++;
                    JsonObject out = f.settings.apply(slot, id, expectedName, changes, check, beforeWrite);
                    afterApply.run();
                    return out;
                }
            });
        }

        void play() {
            playing = true;
            f.base.transport.child("isPlaying").emit(true);
        }

        /** Resolves the clip at scene 3 as a launcher operation does, then hands its target to the service. */
        HostOperation run(LauncherSettingsModule service, JsonObject changes, boolean write, JsonObject[] out) {
            HostOperation op = new HostOperation(f.tasks.after(2000));
            f.services.enqueue(op, () -> {
                try {
                    f.services.launcherTargets().enterClip(op, f.clip(3), false, at -> {
                        LauncherState.Target target = f.services.launcherState().target(at);
                        service.run(op, target, changes, write, result -> {
                            if (out != null) out[0] = result;
                            op.result.complete(result);
                        });
                    });
                } catch (RuntimeException error) {
                    op.fail(error);
                }
            });
            f.tasks.take().run();
            return op;
        }

        HostOperation run(JsonObject changes, boolean write) {
            return run(module, changes, write, null);
        }

        JsonObject call(JsonObject changes, boolean write) {
            HostOperation op = run(changes, write);
            f.drain(op);
            return op.result.join();
        }

        void settle() {
            while (!f.tasks.isEmpty()) f.next();
        }
    }

    @Test
    void aWriteIsReadBackAndReadsDoNotWrite() {
        Fixture x = new Fixture();
        JsonObject written = x.call(changes(), true);
        assertEquals("play_next", written.get("nextAction").getAsString());
        assertEquals(
                "applied",
                written.getAsJsonObject("fields")
                        .getAsJsonObject("nextAction")
                        .get("outcome")
                        .getAsString());
        assertEquals(2, x.f.settings.writes, "each changed field is one write");
        int writes = x.f.settings.writes;
        JsonObject read = x.call(new JsonObject(), false);
        assertTrue(x.f.settings.writes == writes && !read.has("outcome"), "a read does not write and has no outcome");
        assertEquals("play_next", read.get("nextAction").getAsString());
        JsonObject again = x.call(changes(), true);
        assertEquals(
                "already_matched",
                again.getAsJsonObject("fields")
                        .getAsJsonObject("nextAction")
                        .get("outcome")
                        .getAsString());
        assertEquals(writes, x.f.settings.writes, "an identical change writes nothing");
        assertTrue(x.f.services.holds.list().isEmpty(), "a completed operation releases its holds");
    }

    @Test
    void anUnknownWriteStaysQuarantinedWhileReadsAreAllowed() {
        Fixture x = new Fixture();
        x.f.settings.failWrite = true;
        HostOperation failed = x.run(changes(), true);
        x.f.drain(failed);
        assertUnknown("internal", failed);
        List<Holds.View> holds = x.f.services.holds.list();
        assertTrue(holds.size() == 1 && holds.get(0).ackable, "the unknown write is an unresolved hold");
        RpcException blocked = assertRpcError("unresolved", x.run(changes(), true));
        assertTrue(blocked.next.contains("ack " + LauncherStructureLock.SETTINGS), "the refusal names the hold to ack");
        int calls = x.f.settings.calls;
        assertNotNull(x.call(new JsonObject(), false), "reads stay available");
        assertEquals(calls + 1, x.f.settings.calls);
        x.f.services.holds.ack(LauncherStructureLock.SETTINGS);
        x.f.settings.failWrite = false;
        assertEquals(
                "applied",
                x.call(changes(), true)
                        .getAsJsonObject("fields")
                        .getAsJsonObject("nextAction")
                        .get("outcome")
                        .getAsString());
    }

    @Test
    void aPendingOperationMakesAnotherBusy() {
        Fixture x = new Fixture();
        HostOperation first = x.run(changes(), true);
        assertFalse(first.result.isDone());
        assertEquals(0, x.applies, "the native work is queued");
        List<Holds.View> holds = x.f.services.holds.list();
        assertTrue(holds.size() == 1 && !holds.get(0).ackable, "a running operation is an in-flight hold");
        x.f.drain(first);
        assertEquals("play_next", first.result.join().get("nextAction").getAsString());
        assertTrue(x.f.services.holds.list().isEmpty());
        x.f.services.holds.inFlight(LauncherStructureLock.SETTINGS_PENDING, "pending");
        assertRpcError("busy", x.run(new JsonObject(), false));
        x.f.services.holds.release(LauncherStructureLock.SETTINGS_PENDING);
    }

    @Test
    void aCancelledQueuedOperationDoesNotWrite() {
        Fixture x = new Fixture();
        HostOperation op = x.run(changes(), true);
        op.result.completeExceptionally(new RuntimeException("cancel"));
        x.settle();
        assertEquals(0, x.f.settings.writes);
        assertEquals(0, x.applies);
        assertTrue(x.f.services.holds.list().isEmpty(), "the pending hold is released");
    }

    @Test
    void aPlayingTransportBlocksTheWrite() {
        Fixture x = new Fixture();
        x.play();
        assertRpcError("unsafe_state", x.run(changes(), true));
        assertEquals(0, x.applies);
        x.play();
        assertNotNull(x.call(new JsonObject(), false), "a read does not need a stopped transport");
    }

    @Test
    void aTrackChangeWhileQueuedIsTargetChanged() {
        Fixture x = new Fixture();
        HostOperation op = x.run(changes(), true);
        x.beforeRun = () -> x.f.observeTrackId(OTHER);
        x.f.drain(op);
        assertRpcError("target_changed", op);
        assertEquals(0, x.f.settings.writes);
        assertTrue(x.f.services.holds.list().isEmpty(), "the failure releases the pending hold");
    }

    @Test
    void aSceneWindowChangeWhileQueuedIsTargetChanged() {
        Fixture x = new Fixture();
        HostOperation op = x.run(changes(), true);
        x.beforeRun = () -> x.f.scroll(8);
        x.f.drain(op);
        assertRpcError("target_changed", op);
        assertEquals(0, x.f.settings.writes);
    }

    @Test
    void aPublicTargetChangedAfterNativeAccessIsTargetChanged() {
        Fixture x = new Fixture();
        x.afterApply = () -> x.f.slots[3].name = "Other";
        HostOperation op = x.run(new JsonObject(), false);
        x.f.drain(op);
        assertRpcError("target_changed", op);
        assertTrue(x.f.services.holds.list().isEmpty());
    }

    @Test
    void aWriteChangedAfterNativeAccessKeepsItsQuarantine() {
        Fixture x = new Fixture();
        x.afterApply = () -> x.f.slots[3].name = "Other";
        HostOperation op = x.run(changes(), true);
        x.f.drain(op);
        assertUnknown("target_changed", op);
        assertEquals(2, x.f.settings.writes);
        List<Holds.View> holds = x.f.services.holds.list();
        assertTrue(holds.size() == 1 && holds.get(0).ackable, "the dispatched write stays unresolved");
    }

    @Test
    void withoutANativeBindingTheServiceIsAHostMismatch() {
        Fixture x = new Fixture();
        LauncherSettingsModule bare = x.f.services.launcherSettings(null);
        HostOperation op = x.run(bare, changes(), true, null);
        x.settle();
        assertRpcError("host_mismatch", op);
        assertTrue(x.f.services.holds.list().isEmpty());
    }
}
