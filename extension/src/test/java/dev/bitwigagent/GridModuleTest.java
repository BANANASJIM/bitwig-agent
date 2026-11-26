package dev.bitwigagent;

import static dev.bitwigagent.RpcAssert.*;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Independent controller/document queues exercise cancellation, stale targeting and immutable handoff. */
public final class GridModuleTest {

    @Test
    void validatesBeforeScheduling() {
        Fixture f = new Fixture();
        for (String text : List.of(
                "{\"limit\":0}",
                "{\"limit\":257}",
                "{\"offset\":-1}",
                "{\"offset\":100001}",
                "{\"limit\":1.5}",
                "{\"query\":null}",
                "{\"query\":3}",
                "{\"unknown\":true}")) {
            assertRpcError("invalid_params", f.host.submit(f.module, "grid.catalog", HostFixture.json(text)));
            assertTrue(f.host.tasks.isEmpty() && f.adapter.tasks.isEmpty(), "invalid catalog request not queued");
        }
        JsonObject p = new JsonObject();
        p.addProperty("query", "a".repeat(129));
        assertRpcError("invalid_params", f.host.submit(f.module, "grid.catalog", p));
        String supplementary = new String(Character.toChars(0x1D11E));
        p.addProperty("query", supplementary.repeat(64));
        GridModule.validateParams("grid.catalog", p);
        p.addProperty("query", supplementary.repeat(65));
        assertRpcError("invalid_params", f.host.submit(f.module, "grid.catalog", p));
        for (String field : List.of("trackId", "deviceIndex", "expectedDeviceName")) {
            p = target();
            p.remove(field);
            assertRpcError("invalid_params", f.host.submit(f.module, "grid.get", p));
        }
        p = target();
        p.addProperty("moduleLimit", 257);
        assertRpcError("invalid_params", f.host.submit(f.module, "grid.get", p));
        p = target();
        p.addProperty("deviceIndex", 16);
        assertRpcError("invalid_params", f.host.submit(f.module, "grid.get", p));
        p = target();
        p.addProperty("includeParameters", "false");
        assertRpcError("invalid_params", f.host.submit(f.module, "grid.get", p));
        p = target();
        p.addProperty("trackId", "not-a-uuid");
        assertRpcError("invalid_params", f.host.submit(f.module, "grid.get", p));
        GridModule.validateParams("grid.catalog", HostFixture.json("{\"query\":\"\",\"offset\":100000,\"limit\":256}"));
        assertTrue(f.adapter.reads == 0, "validation never touches adapter");
    }

    @Test
    void requiresExplicitObservedTarget() {
        Fixture f = new Fixture();
        f.device.child("name").emit("Tool");
        HostOperation op = f.start(target());
        assertRpcError("stale_target", op);
        assertTrue(f.adapter.tasks.isEmpty(), "wrong expected name never enters document queue");
        f.device.child("name").emit("Poly Grid");
        f.device.child("exists").emit(false);
        assertRpcError("target_not_found", f.start(target()));
        f.device.child("exists").emit(true);
        f.host.tracks[0].child("createDeviceBank").child("scrollPosition").emit(1);
        assertRpcError("stale_context", f.start(target()));
        Fixture unobserved = new Fixture(false);
        assertRpcError("not_ready", unobserved.start(target()));
    }

    @Test
    void documentQueueDoesNotCompleteOnDispatch() {
        Fixture f = new Fixture();
        HostOperation op = f.start(target());
        assertTrue(!op.result.isDone() && f.adapter.reads == 0, "controller scheduling is not native read completion");
        f.adapter.next();
        assertTrue(!op.result.isDone() && f.adapter.reads == 1, "document read waits for controller target recheck");
        f.host.next();
        op.result.join();
        assertTrue(!op.dispatched(), "native read never marks a host mutation dispatched");
    }

    @Test
    void detectsRebindEvenWhenNameReturns() {
        Fixture f = new Fixture();
        HostOperation op = f.start(target());
        f.device.child("name").emit("Other");
        f.device.child("name").emit("Poly Grid");
        f.adapter.next();
        assertRpcError("stale_context", op);
        assertTrue(f.adapter.reads == 0, "generation change detects ABA name transition before native read");
        Fixture type = new Fixture();
        op = type.start(target());
        type.device.child("deviceType").emit("Instrument");
        type.adapter.next();
        assertRpcError("stale_context", op);
        assertTrue(type.adapter.reads == 0, "device-type replacement retires a queued Grid read (shared root target)");
        Fixture track = new Fixture();
        op = track.start(target());
        track.host.tracks[0].child("channelId").emit(HostFixture.B);
        track.host.tracks[0].child("channelId").emit(HostFixture.A);
        track.adapter.next();
        assertRpcError("stale_context", op);
        assertTrue(track.adapter.reads == 0, "track ABA detected independently of device callbacks");
    }

    @Test
    void rechecksAfterDocumentRead() {
        Fixture f = new Fixture();
        HostOperation op = f.start(target());
        f.adapter.next();
        f.device.child("position").emit(1);
        f.host.next();
        assertTrue(
                !assertRpcError("stale_context", op).unknown,
                "post-read rebind rejects stale result without mutation ambiguity");
    }

    @Test
    void cancelledExpiredAndClosedOperationsDoNotRead() {
        Fixture f = new Fixture();
        HostOperation op = f.start(target());
        op.result.cancel(false);
        f.adapter.next();
        assertTrue(f.adapter.reads == 0 && f.host.tasks.isEmpty(), "cancelled document queue work never reads");
        op = f.host.submit(f.module, "grid.get", target(), 0);
        f.host.next();
        assertRpcError("deadline", op);
        assertTrue(f.adapter.tasks.isEmpty(), "expired controller queue work never dispatches native reads");
        Fixture closed = new Fixture();
        op = closed.start(target());
        closed.host.services.scheduler.close();
        closed.adapter.next();
        assertRpcError("not_ready", op);
        assertTrue(closed.adapter.reads == 0, "extension close invalidates queued document work");
        Fixture late = new Fixture();
        op = late.start(target());
        late.adapter.next();
        op.result.cancel(false);
        late.host.next();
        assertTrue(op.result.isCancelled(), "late result cannot overwrite cancellation");
    }

    @Test
    void independentConcurrentReadsDoNotShareTargets() {
        Fixture f = new Fixture();
        JsonObject first = target(), second = target();
        first.addProperty("moduleOffset", 1);
        second.addProperty("moduleOffset", 2);
        HostOperation a = f.start(first), b = f.start(second);
        f.adapter.tasks.removeLast().run();
        f.adapter.next();
        f.host.next();
        f.host.next();
        assertTrue(
                a.result.join().get("offset").getAsInt() == 1
                        && b.result.join().get("offset").getAsInt() == 2,
                "out-of-order document completion remains correlated to each operation");
    }

    @Test
    void noMutableResultsCrossThreadBoundary() {
        Fixture f = new Fixture();
        HostOperation op = f.start(target());
        f.adapter.next();
        f.adapter.last.addProperty("offset", 999);
        f.host.next();
        assertTrue(
                op.result.join().get("offset").getAsInt() == 0,
                "serialized snapshot detached from mutable document JSON before callback");
        Fixture errors = new Fixture();
        errors.adapter.fail = true;
        op = errors.start(target());
        errors.adapter.next();
        assertRpcError("kind_mismatch", op);
        assertTrue(errors.host.tasks.isEmpty(), "nonGrid error does not deliver a fake empty graph");
    }

    @Test
    void boundsTransportAndPreservesNulls() {
        JsonObject value = new JsonObject();
        value.add("unavailable", JsonNull.INSTANCE);
        assertTrue(
                BoundedJson.serialize(value, 240000, Reason.OUT_OF_BOUNDS, "oversize")
                        .contains("null"),
                "unsupported null fields survive immutable handoff");
        value.addProperty("large", "x".repeat(240000));
        {
            RpcException error = assertThrows(
                    RpcException.class, () -> BoundedJson.serialize(value, 240000, Reason.OUT_OF_BOUNDS, "oversize"));
            assertTrue(
                    error.reason.wire().equals("out_of_bounds"),
                    "oversize asks caller to reduce page instead of truncating");
        }
    }

    @Test
    void loadedSourceGateFailsClosed() throws Exception {
        GridAdapter wrongVersion =
                new Bitwig614GridAdapter(new Bitwig614Runtime(new Object(), "6.1.3", () -> {}).access());
        assertTrue(
                !wrongVersion.capabilities().get("gatePassed").getAsBoolean(),
                "unsupported version fails before any native binding");
        GridAdapter noSource = new Bitwig614GridAdapter(new Bitwig614Runtime(new Object(), "6.1.4", () -> {}).access());
        assertTrue(
                !noSource.capabilities().get("gatePassed").getAsBoolean(),
                "missing loaded CodeSource cannot fall back to arbitrary installed JAR");
        assertTrue(
                !noSource.capabilities()
                                .getAsJsonObject("nativeOwningTrack")
                                .get("available")
                                .getAsBoolean()
                        && !noSource.capabilities()
                                .getAsJsonObject("structure")
                                .get("available")
                                .getAsBoolean(),
                "unverified identity bindings cannot advertise usable native ownership or structure");
        assertTrue(
                !noSource.capabilities()
                                .getAsJsonObject("moduleMove")
                                .get("available")
                                .getAsBoolean()
                        && !noSource.capabilities()
                                .getAsJsonObject("moduleDelete")
                                .get("available")
                                .getAsBoolean()
                        && !noSource.capabilities()
                                .getAsJsonObject("connectionWrite")
                                .get("available")
                                .getAsBoolean()
                        && !noSource.capabilities()
                                .getAsJsonObject("moduleInsert")
                                .get("available")
                                .getAsBoolean()
                        && !noSource.capabilities()
                                .getAsJsonObject("moduleInsert")
                                .has("bindingIdentity"),
                "unverified source cannot advertise any native topology capability");
        {
            RpcException error = assertThrows(RpcException.class, () -> {
                noSource.snapshot(
                        new Object(), UUID.fromString(HostFixture.A), 0, 64, true, false, null, false, () -> {});
            });
            assertTrue(
                    error.reason.wire().equals("native_unavailable"), "snapshot requires exact verified identity gate");
        }
        {
            RpcException error = assertThrows(RpcException.class, () -> {
                noSource.execute(() -> {
                    throw new AssertionError("read escaped gate");
                });
            });
            assertTrue(error.reason.wire().equals("host_mismatch"), "a failed gate is a host mismatch");
        }
        Path file = Files.createTempFile("grid-hash-test-", ".bin");
        try {
            Files.writeString(file, "abc", StandardCharsets.UTF_8);
            assertTrue(
                    Bitwig614Runtime.hash(file, () -> {})
                            .equals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"),
                    "hash reads bytes not filename or version text");
            {
                RpcException error = assertThrows(RpcException.class, () -> {
                    Bitwig614Runtime.hash(file, () -> {
                        throw Errors.notApplied(Reason.CANCELLED, "cancel hashing");
                    });
                });
                assertTrue(error.reason.wire().equals("cancelled"), "gate hashing observes request cancellation");
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void parameterInspectionRejectsInvalidTargetsAndQueuedRaces() {
        Fixture f = new Fixture();
        for (String field : List.of("trackId", "deviceIndex", "expectedDeviceName", "moduleId", "parameterId")) {
            JsonObject p = inspectionTarget();
            p.remove(field);
            assertRpcError("invalid_params", f.host.submit(f.module, "grid.parameters.get", p));
            p = inspectionTarget();
            p.add(field, JsonNull.INSTANCE);
            assertRpcError("invalid_params", f.host.submit(f.module, "grid.parameters.get", p));
        }
        for (JsonElement invalid : List.of(
                new JsonPrimitive(3), new JsonPrimitive(true), new JsonPrimitive("x".repeat(1025)), new JsonArray())) {
            JsonObject p = inspectionTarget();
            p.add("parameterId", invalid);
            assertRpcError("invalid_params", f.host.submit(f.module, "grid.parameters.get", p));
        }
        for (String id : List.of("1-1-1-1-1", "00000000-0000-0000-0000-00000000000g", "")) {
            JsonObject p = inspectionTarget();
            p.addProperty("moduleId", id);
            assertRpcError("invalid_params", f.host.submit(f.module, "grid.parameters.get", p));
        }
        JsonObject p = inspectionTarget();
        p.addProperty("includeParameters", false);
        assertRpcError("invalid_params", f.host.submit(f.module, "grid.parameters.get", p));
        p = inspectionTarget();
        p.addProperty("deviceIndex", true);
        assertRpcError("invalid_params", f.host.submit(f.module, "grid.parameters.get", p));
        String supplementary = new String(Character.toChars(0x1D11E));
        p = inspectionTarget();
        p.addProperty("parameterId", supplementary.repeat(513));
        assertRpcError("invalid_params", f.host.submit(f.module, "grid.parameters.get", p));
        p.addProperty("parameterId", supplementary.repeat(512));
        GridModule.validateParams("grid.parameters.get", p);
        p.addProperty("parameterId", "");
        p.addProperty("moduleId", "ABCDEFAB-ABCD-ABCD-ABCD-ABCDEFABCDEF");
        GridModule.validateParams("grid.parameters.get", p);
        assertTrue(
                f.host.tasks.isEmpty() && f.adapter.tasks.isEmpty(),
                "invalid inspection requests never enter either queue");
        HostOperation cancelled = f.host.submit(f.module, "grid.parameters.get", inspectionTarget());
        f.host.next();
        cancelled.result.cancel(false);
        f.adapter.next();
        assertTrue(f.adapter.reads == 0, "cancelled inspection does not read native context");
        HostOperation changed = f.host.submit(f.module, "grid.parameters.get", inspectionTarget());
        f.host.next();
        f.device.child("name").emit("Other");
        f.device.child("name").emit("Poly Grid");
        f.adapter.next();
        assertRpcError("stale_context", changed);
        assertTrue(f.adapter.reads == 0, "inspection rejects public target epoch changes before context capture");
        HostOperation late = f.host.submit(f.module, "grid.parameters.get", inspectionTarget());
        f.host.next();
        f.adapter.next();
        f.host.tracks[0].child("channelId").emit(HostFixture.B);
        f.host.next();
        assertRpcError("stale_context", late);
    }

    private static JsonObject writeTarget() {
        JsonObject result = inspectionTarget();
        result.addProperty("expectedKind", "boolean");
        result.addProperty("expectedValue", true);
        result.addProperty("value", false);
        return result;
    }

    @Test
    void valueWritesRequireObservedRecordingAutomationOffAndMayPlay() {
        Fixture f = new Fixture();
        for (String field : List.of("expectedKind", "expectedValue", "value")) {
            JsonObject p = writeTarget();
            p.remove(field);
            assertRpcError("invalid_params", f.host.submit(f.module, "grid.parameters.set", p));
        }
        JsonObject invalid = writeTarget();
        invalid.addProperty("value", 0);
        assertRpcError("invalid_params", f.host.submit(f.module, "grid.parameters.set", invalid));
        assertTrue(
                f.host.tasks.isEmpty() && f.adapter.tasks.isEmpty(), "bad raw values are rejected before scheduling");
        Fixture missing = new Fixture(true, false);
        for (String key : List.of("isArrangerRecordEnabled", "isArrangerAutomationWriteEnabled"))
            missing.host.transport.child(key).emit(false);
        assertRpcError("not_ready", missing.startWrite());
        assertTrue(missing.adapter.tasks.isEmpty(), "default false getter is not an observed safe state");
        for (String key : List.of(
                "isArrangerRecordEnabled",
                "isArrangerAutomationWriteEnabled",
                "isClipLauncherAutomationWriteEnabled")) {
            Fixture unsafe = new Fixture();
            unsafe.host.transport.child(key).emit(true);
            assertRpcError("unsafe_state", unsafe.startWrite());
            assertTrue(
                    unsafe.adapter.writer.calls == 0 && unsafe.adapter.tasks.isEmpty(),
                    "recording or automation write blocks native scheduling");
        }
        Fixture playing = new Fixture();
        playing.host.transport.child("isPlaying").emit(true);
        HostOperation op = playing.startWrite();
        playing.adapter.next();
        playing.host.next();
        assertTrue(
                op.result.join().getAsJsonObject("parameter").get("value").getAsBoolean() == false
                        && playing.adapter.writer.calls == 1,
                "a playing transport does not block a value write");
    }

    @Test
    void stringWriteSchemaRejectsBeforeScheduling() {
        Fixture f = new Fixture();
        JsonObject stringWrite = writeTarget();
        stringWrite.addProperty("expectedKind", "string");
        stringWrite.addProperty("expectedValue", "before");
        stringWrite.addProperty("value", "after");
        GridModule.validateParams("grid.parameters.set", stringWrite);
        JsonObject tooLong = stringWrite.deepCopy();
        tooLong.addProperty("value", "a".repeat(1025));
        assertRpcError("invalid_params", f.host.submit(f.module, "grid.parameters.set", tooLong));
        JsonObject mixed = stringWrite.deepCopy();
        mixed.addProperty("value", true);
        assertRpcError("invalid_params", f.host.submit(f.module, "grid.parameters.set", mixed));
        for (String field : List.of("expectedValue", "value")) {
            JsonObject scalarWrite = writeTarget();
            scalarWrite.addProperty(field, "true");
            assertRpcError("invalid_params", f.host.submit(f.module, "grid.parameters.set", scalarWrite));
        }
        assertTrue(
                f.host.tasks.isEmpty() && f.adapter.tasks.isEmpty() && f.adapter.writer.calls == 0,
                "string kind validates text bounds; scalar kinds still reject string values; nothing is scheduled by validation");
    }

    @Test
    void queuedWritesRejectSafetyAndTargetTransitions() {
        Fixture controller = new Fixture();
        HostOperation op = controller.host.submit(controller.module, "grid.parameters.set", writeTarget());
        controller.host.transport.child("isArrangerRecordEnabled").emit(true);
        controller.host.next();
        assertTrue(
                !assertRpcError("unsafe_state", op).unknown && controller.adapter.tasks.isEmpty(),
                "recording enabled in initial controller queue is rejected");
        for (String key : List.of(
                "isArrangerRecordEnabled",
                "isArrangerAutomationWriteEnabled",
                "isClipLauncherAutomationWriteEnabled")) {
            Fixture queued = new Fixture();
            op = queued.startWrite();
            queued.host.transport.child(key).emit(true);
            queued.adapter.next();
            assertTrue(
                    !assertRpcError("unsafe_state", op).unknown && queued.adapter.writer.calls == 0 && !op.dispatched(),
                    "recording enabled in document queue prevents invocation");
        }
        Fixture started = new Fixture();
        op = started.startWrite();
        started.host.transport.child("isPlaying").emit(true);
        started.adapter.next();
        started.host.next();
        assertTrue(
                op.result.join().getAsJsonObject("parameter").get("value").getAsBoolean() == false
                        && started.adapter.writer.calls == 1,
                "playback starting while queued does not stop a value write");
        Fixture changed = new Fixture();
        op = changed.startWrite();
        changed.device.child("name").emit("Other");
        changed.device.child("name").emit("Poly Grid");
        changed.adapter.next();
        assertTrue(
                !assertRpcError("stale_context", op).unknown && changed.adapter.writer.calls == 0,
                "target ABA before mutation is not-applied");
        Fixture immediate = new Fixture();
        op = immediate.startWrite();
        immediate.adapter.beforeWrite =
                () -> immediate.host.transport.child("isArrangerRecordEnabled").emit(true);
        immediate.adapter.next();
        assertTrue(
                !assertRpcError("unsafe_state", op).unknown && immediate.adapter.writer.calls == 0,
                "changed safety at writer entry is rechecked, not cached permission");
        Fixture cancelled = new Fixture();
        op = cancelled.startWrite();
        op.result.cancel(false);
        cancelled.adapter.next();
        assertTrue(
                cancelled.adapter.writer.calls == 0 && !op.dispatched(),
                "cancelled native queue entry does not invoke setter");
    }

    @Test
    void nativeCallbacksNeverReadControllerSafetyGetters() throws Exception {
        Fixture f = new Fixture();
        HostOperation op = f.startWrite();
        for (String key : List.of(
                "isPlaying",
                "isArrangerRecordEnabled",
                "isArrangerAutomationWriteEnabled",
                "isClipLauncherAutomationWriteEnabled"))
            f.host.transport.child(key).onCall = (name, args) -> {
                if (name.equals("get") && !f.host.onHost)
                    throw new AssertionError("Controller getter on document thread");
            };
        Thread document = new Thread(f.adapter::next);
        document.start();
        document.join();
        assertTrue(
                op.dispatched() && !op.result.isDone() && f.adapter.writer.calls == 1,
                "native callback mutates but awaits controller delivery");
        f.host.next();
        assertTrue(
                op.result.join().getAsJsonObject("parameter").get("value").getAsBoolean() == false,
                "published safety observations support document-thread mutation without Controller API getters");
    }

    @Test
    void everyPostDispatchBoundaryKeepsUnknownOutcome() {
        Fixture nativeFailure = new Fixture();
        nativeFailure.adapter.writer.afterMutation = () -> {
            throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "native partial failure");
        };
        HostOperation op = nativeFailure.startWrite();
        nativeFailure.adapter.next();
        assertTrue(
                assertUnknown("native_unavailable", op) != null && nativeFailure.adapter.writer.calls == 1,
                "document callback normalizes partial native failures without retry");
        Fixture linkage = new Fixture();
        linkage.adapter.writer.afterMutation = () -> {
            throw new LinkageError("late native linkage failure");
        };
        op = linkage.startWrite();
        linkage.adapter.next();
        assertTrue(
                assertUnknown("native_unavailable", op) != null && linkage.adapter.writer.calls == 1,
                "native Error completes uncertain result instead of escaping the callback");
        Fixture serialization = new Fixture();
        serialization.adapter.afterWrite =
                () -> serialization.adapter.last.addProperty("oversized", "x".repeat(240000));
        op = serialization.startWrite();
        serialization.adapter.next();
        assertTrue(
                assertUnknown("out_of_bounds", op) != null,
                "serialization failure after mutation cannot become not-applied");
        Fixture target = new Fixture();
        op = target.startWrite();
        target.adapter.next();
        target.device.child("position").emit(1);
        target.host.next();
        assertTrue(assertUnknown("stale_context", op) != null, "late public target failure remains unknown");
        Fixture safety = new Fixture();
        op = safety.startWrite();
        safety.adapter.next();
        safety.host.transport.child("isArrangerRecordEnabled").emit(true);
        safety.host.next();
        assertTrue(
                assertUnknown("unsafe_state", op) != null, "post-mutation safety change cannot erase dispatched state");
        Fixture closed = new Fixture();
        op = closed.startWrite();
        closed.adapter.writer.afterMutation = closed.host.services.scheduler::close;
        closed.adapter.next();
        assertTrue(assertUnknown("not_ready", op) != null, "extension close during invocation preserves unknown");
        Fixture late = new Fixture();
        op = late.startWrite();
        late.adapter.next();
        op.result.cancel(false);
        late.host.next();
        assertTrue(
                op.result.isCancelled() && op.dispatched() && late.adapter.writer.calls == 1,
                "late cancellation does not claim native rollback");
        Fixture noop = new Fixture();
        JsonObject p = writeTarget();
        p.addProperty("value", true);
        op = noop.host.submit(noop.module, "grid.parameters.set", p);
        noop.host.next();
        noop.adapter.next();
        noop.device.child("position").emit(1);
        noop.host.next();
        assertTrue(
                !assertRpcError("stale_context", op).unknown && !op.dispatched() && noop.adapter.writer.calls == 0,
                "late error after no-op does not invent a dispatched mutation");
        HostOperation expired = new HostOperation(0);
        expired.markDispatched();
        {
            RpcException error = assertThrows(RpcException.class, () -> noop.host.services.scheduler.check(expired));
            expired.fail(error);
        }
        assertTrue(assertUnknown("deadline", expired) != null, "deadline after dispatch preserves uncertainty");
    }

    private static JsonObject moduleTarget(boolean move) {
        JsonObject p = inspectionTarget();
        p.remove("parameterId");
        if (move) {
            p.addProperty("expectedX", 7);
            p.addProperty("expectedY", 3);
            p.addProperty("x", 10);
            p.addProperty("y", 3);
        }
        return p;
    }

    @Test
    void topologyArgumentsRejectBeforeScheduling() {
        Fixture f = new Fixture();
        for (String method : List.of("grid.modules.move", "grid.modules.delete")) {
            boolean move = method.endsWith("move");
            for (String key : moduleTarget(move).keySet()) {
                JsonObject p = moduleTarget(move);
                p.remove(key);
                assertRpcError("invalid_params", f.host.submit(f.module, method, p));
            }
            JsonObject extra = moduleTarget(move);
            extra.addProperty("copy", false);
            assertRpcError("invalid_params", f.host.submit(f.module, method, extra));
            JsonObject stale = moduleTarget(move);
            stale.addProperty("moduleId", "1-1-1-1-1");
            assertRpcError("invalid_params", f.host.submit(f.module, method, stale));
        }
        for (String key : List.of("expectedX", "expectedY", "x", "y")) {
            for (String token : List.of("true", "\"1\"", "null", "1.0", "1e0", "2147483648", "-2147483649")) {
                JsonObject p = moduleTarget(true);
                p.add(key, JsonParser.parseString(token));
                assertRpcError("invalid_params", f.host.submit(f.module, "grid.modules.move", p));
            }
        }
        assertTrue(
                f.host.tasks.isEmpty() && f.adapter.tasks.isEmpty() && f.adapter.topology.calls == 0,
                "invalid topology arguments never reach controller or native document queue");
    }

    @Test
    void topologyOperationsRequireLaterDocumentObservation() {
        for (boolean move : List.of(true, false)) {
            Fixture f = new Fixture();
            HostOperation op =
                    f.host.submit(f.module, move ? "grid.modules.move" : "grid.modules.delete", moduleTarget(move));
            f.host.next();
            assertTrue(
                    !op.dispatched() && !op.result.isDone(),
                    "queued topology request has not invoked native operation");
            f.adapter.next();
            assertTrue(
                    op.dispatched() && !op.result.isDone() && f.host.tasks.isEmpty() && f.adapter.topology.calls == 1,
                    "native return does not deliver topology result before later document event");
            f.adapter.next();
            assertTrue(!op.result.isDone(), "later document observation still requires controller handoff");
            f.host.next();
            JsonObject result = op.result.join();
            assertTrue(
                    result.get("mutationDispatched").getAsBoolean()
                            && result.has("target")
                            && result.has("moduleCount")
                            && !result.has("graph")
                            && !result.has("graphBefore")
                            && !result.has("safetyScope"),
                    "observed topology result includes guarded target and a diff, not the bounded graph or constant prose");
        }
    }

    @Test
    void topologySafetyAndTargetsGuardEveryQueueBoundary() {
        for (boolean move : List.of(true, false)) {
            String method = move ? "grid.modules.move" : "grid.modules.delete";
            Fixture notStopped = new Fixture();
            notStopped.host.transport.child("isPlaying").emit(true);
            HostOperation early = notStopped.host.submit(notStopped.module, method, moduleTarget(move));
            notStopped.host.next();
            assertRpcError("unsafe_state", early);
            assertTrue(
                    notStopped.adapter.topology.calls == 0 && !early.dispatched(),
                    "topology requires observed stopped safety before native queue");
            for (int boundary = 0; boundary < 3; boundary++) {
                Fixture f = new Fixture();
                HostOperation op = f.host.submit(f.module, method, moduleTarget(move));
                f.host.next();
                if (boundary > 0) f.adapter.next();
                if (boundary > 1) f.adapter.next();
                f.host.transport.child("isPlaying").emit(true);
                f.host.transport.child("isPlaying").emit(false);
                if (boundary < 2) f.adapter.next();
                else f.host.next();
                assertTrue(
                        failed("stale_context", op, boundary == 0)
                                && f.adapter.topology.calls == (boundary == 0 ? 0 : 1),
                        "safety epoch change across native queue, later observation or controller delivery never retries");
            }
            Fixture target = new Fixture();
            HostOperation op = target.host.submit(target.module, method, moduleTarget(move));
            target.host.next();
            target.device.child("name").emit("Other");
            target.device.child("name").emit("Poly Grid");
            target.adapter.next();
            assertTrue(
                    !assertRpcError("stale_context", op).unknown && target.adapter.topology.calls == 0,
                    "observed target ABA blocks topology before native dispatch");
        }
    }

    private static JsonObject connectionTarget() {
        JsonObject p = target();
        p.addProperty("targetModuleId", new UUID(1, 1).toString());
        p.addProperty("targetPortKey", "in");
        p.add("expectedSource", JsonNull.INSTANCE);
        JsonObject source = new JsonObject();
        source.addProperty("moduleId", new UUID(1, 1).toString());
        source.addProperty("portKey", "out");
        p.add("source", source);
        return p;
    }

    @Test
    void connectionArgumentsPreserveRequiredNullSemantics() {
        Fixture f = new Fixture();
        for (String key : connectionTarget().keySet()) {
            JsonObject p = connectionTarget();
            p.remove(key);
            assertRpcError("invalid_params", f.host.submit(f.module, "grid.connections.set", p));
        }
        for (String field : List.of("expectedSource", "source")) {
            for (String invalid : List.of(
                    "false",
                    "1",
                    "\"out\"",
                    "[]",
                    "{}",
                    "{\"moduleId\":\"1-1-1-1-1\",\"portKey\":\"out\"}",
                    "{\"moduleId\":null,\"portKey\":\"out\"}",
                    "{\"moduleId\":1,\"portKey\":\"out\"}",
                    "{\"moduleId\":\"00000000-0000-0001-0000-000000000001\"}",
                    "{\"portKey\":\"out\"}",
                    "{\"moduleId\":\"00000000-0000-0001-0000-000000000001\",\"portKey\":false}",
                    "{\"moduleId\":\"00000000-0000-0001-0000-000000000001\",\"portKey\":null}",
                    "{\"moduleId\":\"00000000-0000-0001-0000-000000000001\",\"portKey\":\"out\",\"extra\":true}")) {
                JsonObject p = connectionTarget();
                p.add(field, JsonParser.parseString(invalid));
                assertRpcError("invalid_params", f.host.submit(f.module, "grid.connections.set", p));
            }
            JsonObject p = connectionTarget();
            JsonObject endpoint = p.getAsJsonObject("source").deepCopy();
            endpoint.addProperty("portKey", "x".repeat(1025));
            p.add(field, endpoint);
            assertRpcError("invalid_params", f.host.submit(f.module, "grid.connections.set", p));
            endpoint.addProperty("portKey", "");
            GridModule.validateParams("grid.connections.set", p);
            endpoint.addProperty("portKey", new String(Character.toChars(0x1D11E)).repeat(512));
            GridModule.validateParams("grid.connections.set", p);
            endpoint.addProperty("portKey", new String(Character.toChars(0x1D11E)).repeat(513));
            assertRpcError("invalid_params", f.host.submit(f.module, "grid.connections.set", p));
        }
        for (String field :
                List.of("targetModuleId", "targetPortKey", "trackId", "deviceIndex", "expectedDeviceName")) {
            JsonObject p = connectionTarget();
            p.add(field, JsonNull.INSTANCE);
            assertRpcError("invalid_params", f.host.submit(f.module, "grid.connections.set", p));
        }
        JsonObject extra = connectionTarget();
        extra.addProperty("retry", true);
        assertRpcError("invalid_params", f.host.submit(f.module, "grid.connections.set", extra));
        JsonObject abbreviated = connectionTarget();
        abbreviated.addProperty("targetModuleId", "1-1-1-1-1");
        assertRpcError("invalid_params", f.host.submit(f.module, "grid.connections.set", abbreviated));
        for (JsonElement key : List.of(
                new JsonPrimitive(false), new JsonPrimitive(1), new JsonPrimitive("x".repeat(1025)), new JsonArray())) {
            JsonObject p = connectionTarget();
            p.add("targetPortKey", key);
            assertRpcError("invalid_params", f.host.submit(f.module, "grid.connections.set", p));
        }
        JsonObject valid = connectionTarget();
        valid.addProperty("targetPortKey", "");
        valid.add("source", JsonNull.INSTANCE);
        GridModule.validateParams("grid.connections.set", valid);
        assertTrue(
                f.host.tasks.isEmpty() && f.adapter.tasks.isEmpty() && f.adapter.connections.calls == 0,
                "strict nested validation rejects before scheduling; explicit required null and empty local keys remain valid");
    }

    @Test
    void connectionResultsWaitForBothQueues() {
        Fixture f = new Fixture();
        HostOperation op = f.host.submit(f.module, "grid.connections.set", connectionTarget());
        f.host.next();
        assertTrue(!op.dispatched() && !op.result.isDone(), "cable controller dispatch is not native invocation");
        f.adapter.next();
        assertTrue(
                op.dispatched() && !op.result.isDone() && f.host.tasks.isEmpty() && f.adapter.connections.calls == 1,
                "setter return queues later document observation, never immediate completion");
        f.adapter.next();
        assertTrue(!op.result.isDone(), "later observation still waits for controller target recheck");
        f.host.next();
        JsonObject result = op.result.join();
        assertTrue(
                result.get("outcome").getAsString().equals("applied")
                        && result.has("target")
                        && result.get("previousSource").isJsonNull()
                        && !result.has("graph")
                        && !result.has("nativeUndoScope"),
                "serialized result preserves nullable previous source and carries a diff, not the graph");
        JsonArray changed = result.getAsJsonArray("connectionsChanged");
        assertTrue(
                changed.size() == 1
                        && changed.get(0).getAsJsonObject().get("from").isJsonNull()
                        && changed.get(0)
                                .getAsJsonObject()
                                .getAsJsonObject("to")
                                .get("portKey")
                                .getAsString()
                                .equals("out")
                        && changed.get(0)
                                .getAsJsonObject()
                                .getAsJsonObject("target")
                                .get("portKey")
                                .getAsString()
                                .equals("in")
                        && !result.has("modulesAdded")
                        && result.get("moduleCount").getAsInt() == 2,
                "diff lists exactly the changed cable with endpoints and module count");
        Fixture full = new Fixture();
        JsonObject fullParams = connectionTarget();
        fullParams.addProperty("graph", "full");
        HostOperation fullOp = full.host.submit(full.module, "grid.connections.set", fullParams);
        full.host.next();
        full.adapter.next();
        full.adapter.next();
        full.host.next();
        JsonObject fullResult = fullOp.result.join();
        assertTrue(
                fullResult.getAsJsonObject("graph").has("connections")
                        && !fullResult.has("connectionsChanged")
                        && !fullResult.has("graphBefore"),
                "graph=full returns the bounded graph instead of the diff");
        JsonObject bad = connectionTarget();
        bad.addProperty("graph", "everything");
        assertRpcError("invalid_params", full.host.submit(full.module, "grid.connections.set", bad));
        Fixture noop = new Fixture();
        JsonObject p = connectionTarget();
        p.add("source", JsonNull.INSTANCE);
        op = noop.host.submit(noop.module, "grid.connections.set", p);
        noop.host.next();
        noop.adapter.next();
        noop.adapter.next();
        noop.host.next();
        result = op.result.join();
        assertTrue(
                !op.dispatched()
                        && noop.adapter.connections.calls == 0
                        && result.get("actualSource").isJsonNull()
                        && result.get("requestedSource").isJsonNull()
                        && result.get("previousSource").isJsonNull(),
                "explicit null no-op crosses serialized handoff without omission or mutation");
    }

    @Test
    void connectionSafetyTargetsAndCancellationGuardQueues() {
        for (String flag : List.of(
                "isPlaying",
                "isArrangerRecordEnabled",
                "isArrangerAutomationWriteEnabled",
                "isClipLauncherAutomationWriteEnabled")) {
            Fixture f = new Fixture();
            f.host.transport.child(flag).emit(true);
            HostOperation op = f.host.submit(f.module, "grid.connections.set", connectionTarget());
            f.host.next();
            assertTrue(
                    !assertRpcError("unsafe_state", op).unknown && f.adapter.connections.calls == 0,
                    "every stopped/recording/automation prerequisite blocks cable dispatch");
        }
        Fixture unobserved = new Fixture(true, false);
        HostOperation pending = unobserved.host.submit(unobserved.module, "grid.connections.set", connectionTarget());
        unobserved.host.next();
        assertRpcError("not_ready", pending);
        for (int boundary = 0; boundary < 3; boundary++) {
            for (boolean safety : List.of(false, true)) {
                Fixture f = new Fixture();
                HostOperation op = f.host.submit(f.module, "grid.connections.set", connectionTarget());
                f.host.next();
                if (boundary > 0) f.adapter.next();
                if (boundary > 1) f.adapter.next();
                if (safety) {
                    f.host.transport.child("isPlaying").emit(true);
                    f.host.transport.child("isPlaying").emit(false);
                } else {
                    f.device.child("name").emit("Other");
                    f.device.child("name").emit("Poly Grid");
                }
                if (boundary < 2) f.adapter.next();
                else f.host.next();
                assertTrue(
                        failed("stale_context", op, boundary == 0)
                                && f.adapter.connections.calls == (boundary == 0 ? 0 : 1),
                        "cable safety/target ABA guards all three queue boundaries");
            }
            Fixture cancelled = new Fixture();
            HostOperation op = cancelled.host.submit(cancelled.module, "grid.connections.set", connectionTarget());
            cancelled.host.next();
            if (boundary > 0) cancelled.adapter.next();
            if (boundary > 1) cancelled.adapter.next();
            op.result.cancel(false);
            if (boundary < 2) cancelled.adapter.next();
            else cancelled.host.next();
            assertTrue(
                    op.result.isCancelled() && cancelled.adapter.connections.calls == (boundary == 0 ? 0 : 1),
                    "cancelled cable work cannot dispatch late, overwrite cancellation or compensate");
        }
        Fixture expired = new Fixture();
        HostOperation op = expired.host.submit(expired.module, "grid.connections.set", connectionTarget(), 0);
        expired.host.next();
        assertTrue(
                !assertRpcError("deadline", op).unknown && expired.adapter.tasks.isEmpty(),
                "expired cable request never enters native queue");
        Fixture closed = new Fixture();
        op = closed.host.submit(closed.module, "grid.connections.set", connectionTarget());
        closed.host.next();
        closed.adapter.next();
        closed.host.services.scheduler.close();
        closed.adapter.next();
        assertTrue(
                assertUnknown("not_ready", op) != null && closed.adapter.connections.calls == 1,
                "close after setter return cannot convert abandoned observation into success or rollback");
    }

    private static JsonObject insertionTarget() {
        JsonObject p = target();
        p.addProperty("typeId", GridInsertionWriterTest.TYPE.toString());
        p.addProperty("x", 8);
        p.addProperty("y", 3);
        return p;
    }

    private static JsonObject batchTarget(String... ids) {
        JsonObject p = target();
        JsonArray writes = new JsonArray();
        for (String id : ids) {
            JsonObject w = new JsonObject();
            w.addProperty("moduleId", "00000000-0000-0001-0000-000000000001");
            w.addProperty("parameterId", id);
            w.addProperty("expectedKind", "boolean");
            w.addProperty("expectedValue", true);
            w.addProperty("value", false);
            writes.add(w);
        }
        p.add("writes", writes);
        return p;
    }

    @Test
    void batchAndReadOptionsValidateAndDispatch() {
        Fixture f = new Fixture();
        GridModule.validateParams("grid.parameters.set", batchTarget("LOCAL_ID", "OTHER"));
        JsonObject mixed = batchTarget("LOCAL_ID");
        mixed.addProperty("parameterId", "LOCAL_ID");
        JsonObject empty = batchTarget();
        JsonObject dup = batchTarget("LOCAL_ID", "LOCAL_ID");
        JsonObject extra = batchTarget("LOCAL_ID");
        extra.getAsJsonArray("writes").get(0).getAsJsonObject().addProperty("nope", 1);
        JsonObject nullValue = batchTarget("LOCAL_ID");
        nullValue.getAsJsonArray("writes").get(0).getAsJsonObject().add("value", JsonNull.INSTANCE);
        JsonObject badKind = batchTarget("LOCAL_ID");
        badKind.getAsJsonArray("writes").get(0).getAsJsonObject().addProperty("expectedKind", "string");
        String[] many = new String[65];
        for (int i = 0; i < many.length; i++) many[i] = "P" + i;
        String[] limit = new String[64];
        for (int i = 0; i < limit.length; i++) limit[i] = "P" + i;
        GridModule.validateParams("grid.parameters.set", batchTarget(limit));
        for (JsonObject bad : List.of(mixed, empty, dup, extra, nullValue, badKind, batchTarget(many)))
            assertRpcError("invalid_params", f.host.submit(f.module, "grid.parameters.set", bad));
        JsonObject getBad = target();
        getBad.addProperty("diagnostics", "yes");
        JsonObject idsBad = target();
        JsonArray ids = new JsonArray();
        ids.add("not-a-uuid");
        idsBad.add("moduleIds", ids);
        JsonObject idsEmpty = target();
        idsEmpty.add("moduleIds", new JsonArray());
        JsonObject idsMany = target();
        JsonArray lots = new JsonArray();
        for (int i = 0; i < 257; i++) lots.add("00000000-0000-0001-0000-" + String.format("%012d", i));
        idsMany.add("moduleIds", lots);
        for (JsonObject bad : List.of(getBad, idsBad, idsEmpty, idsMany))
            assertRpcError("invalid_params", f.host.submit(f.module, "grid.get", bad));
        JsonObject good = target();
        JsonArray one = new JsonArray();
        one.add("00000000-0000-0001-0000-000000000001");
        good.add("moduleIds", one);
        good.addProperty("diagnostics", true);
        GridModule.validateParams("grid.get", good);
        assertTrue(
                f.host.tasks.isEmpty() && f.adapter.tasks.isEmpty(),
                "invalid batch and read options reject before scheduling");
        HostOperation op = f.host.submit(f.module, "grid.parameters.set", batchTarget("LOCAL_ID"));
        f.host.next();
        assertTrue(!op.dispatched() && !op.result.isDone(), "batch controller dispatch is not native invocation");
        f.adapter.next();
        f.host.next();
        JsonObject result = op.result.join();
        assertTrue(
                op.dispatched()
                        && result.get("outcome").getAsString().equals("applied")
                        && result.get("written").getAsInt() == 1
                        && result.getAsJsonArray("writes").size() == 1
                        && result.has("target")
                        && f.adapter.writer.calls == 1,
                "batch crosses both queues once and returns per-entry results with the explicit target");
        Fixture single = new Fixture();
        HostOperation write = single.startWrite();
        single.adapter.next();
        single.host.next();
        JsonObject one2 = write.result.join();
        assertTrue(
                one2.has("parameter")
                        && !one2.has("nativeUndoScope")
                        && !one2.has("safetyScope")
                        && !one2.has("scope")
                        && !one2.getAsJsonObject("target").has("identityScope"),
                "single write keeps its form without constant prose");
    }

    @Test
    void capabilitiesAreCompactByDefault() {
        Fixture f = new Fixture();
        for (String text : List.of("{\"detail\":\"everything\"}", "{\"detail\":1}", "{\"detail\":null}", "{\"x\":1}"))
            assertRpcError("invalid_params", f.host.submit(f.module, "grid.capabilities", HostFixture.json(text)));
        GridModule.validateParams("grid.capabilities", HostFixture.json("{\"detail\":\"full\"}"));
        GridModule.validateParams("grid.capabilities", HostFixture.json("{\"detail\":\"summary\"}"));
        JsonObject full = new JsonObject();
        full.addProperty("hostVersion", "6.1.4");
        full.addProperty("gatePassed", true);
        full.addProperty("available", true);
        full.addProperty("topologyQuarantined", false);
        full.addProperty("topologyAdmissionBlocked", false);
        full.addProperty("loadedHostJarSha256", "x".repeat(64));
        for (String key : List.of(
                "catalog",
                "structure",
                "typedParameters",
                "display",
                "parameterInspection",
                "parameterWrite",
                "moduleMove",
                "moduleDelete",
                "moduleRename",
                "moduleInsert")) {
            JsonObject binding = new JsonObject();
            binding.addProperty("available", !key.equals("moduleMove"));
            binding.addProperty("coverage", "long coverage text");
            binding.add("reason", key.equals("moduleMove") ? new JsonPrimitive("no binding") : JsonNull.INSTANCE);
            full.add(key, binding);
        }
        JsonObject panels = new JsonObject();
        panels.addProperty("available", true);
        panels.addProperty("writeAvailable", false);
        full.add("booleanPanels", panels);
        JsonObject cable = new JsonObject();
        cable.addProperty("available", true);
        full.add("connectionWrite", cable);
        JsonObject compact = GridModule.compactCapabilities(full);
        assertTrue(
                compact.get("gatePassed").getAsBoolean()
                        && compact.get("hostVersion").getAsString().equals("6.1.4")
                        && !compact.get("topologyQuarantined").getAsBoolean()
                        && !compact.get("topologyAdmissionBlocked").getAsBoolean()
                        && compact.getAsJsonObject("writers")
                                .get("connectionWrite")
                                .getAsBoolean()
                        && !compact.getAsJsonObject("writers").get("moduleMove").getAsBoolean()
                        && !compact.getAsJsonObject("writers")
                                .get("booleanPanels")
                                .getAsBoolean()
                        && compact.getAsJsonObject("reads").get("catalog").getAsBoolean()
                        && compact.getAsJsonObject("unavailable")
                                .get("moduleMove")
                                .getAsString()
                                .equals("no binding")
                        && !compact.has("loadedHostJarSha256")
                        && compact.toString().length() < 1200,
                "default capabilities are availability booleans with reasons only for unavailable bindings");
        Fixture g = new Fixture();
        HostOperation op = g.host.submit(g.module, "grid.capabilities", new JsonObject());
        g.host.next();
        assertTrue(
                op.result.join().has("writers")
                        && op.result.join().get("available").getAsBoolean(),
                "capabilities dispatch returns the compact summary");
        JsonObject detail = new JsonObject();
        detail.addProperty("detail", "full");
        op = g.host.submit(g.module, "grid.capabilities", detail);
        g.host.next();
        assertTrue(
                !op.result.join().has("writers")
                        && op.result.join().get("available").getAsBoolean(),
                "detail full returns the adapter result unchanged");
    }

    @Test
    void connectionBatchArgumentsValidateBeforeScheduling() {
        Fixture f = new Fixture();
        JsonObject good = target();
        JsonArray list = new JsonArray();
        JsonObject c = new JsonObject();
        c.addProperty("targetModuleId", "00000000-0000-0001-0000-000000000001");
        c.addProperty("targetPortKey", "in");
        c.add("expectedSource", JsonNull.INSTANCE);
        c.add("source", JsonNull.INSTANCE);
        list.add(c);
        good.add("connections", list);
        GridModule.validateParams("grid.connections.set", good);
        good.addProperty("graph", "full");
        GridModule.validateParams("grid.connections.set", good);
        JsonObject mixed = good.deepCopy();
        mixed.addProperty("targetPortKey", "in");
        JsonObject empty = good.deepCopy();
        empty.add("connections", new JsonArray());
        JsonObject extra = good.deepCopy();
        extra.addProperty("unknown", 1);
        for (JsonObject bad : List.of(mixed, empty, extra))
            assertRpcError("invalid_params", f.host.submit(f.module, "grid.connections.set", bad));
        assertTrue(
                f.host.tasks.isEmpty() && f.adapter.tasks.isEmpty(),
                "invalid connection batches reject before scheduling");
    }

    @Test
    void insertionArguments() {
        Fixture f = new Fixture();
        for (String key : List.of("trackId", "deviceIndex", "expectedDeviceName", "typeId", "x", "y")) {
            JsonObject p = insertionTarget();
            p.remove(key);
            assertRpcError("invalid_params", f.host.submit(f.module, "grid.modules.insert", p));
        }
        for (String key : List.of("x", "y"))
            for (String token : List.of("true", "\"1\"", "null", "1.0", "1e0", "2147483648", "-2147483649")) {
                JsonObject p = insertionTarget();
                p.add(key, JsonParser.parseString(token));
                assertRpcError("invalid_params", f.host.submit(f.module, "grid.modules.insert", p));
            }
        for (String extra : List.of("moduleId", "path", "expectedX", "copy", "retry")) {
            JsonObject p = insertionTarget();
            p.addProperty(extra, "forbidden");
            assertRpcError("invalid_params", f.host.submit(f.module, "grid.modules.insert", p));
        }
        for (String bad : List.of("1-1-1-1-1", "", "native/resource.bwmodule")) {
            JsonObject p = insertionTarget();
            p.addProperty("typeId", bad);
            assertRpcError("invalid_params", f.host.submit(f.module, "grid.modules.insert", p));
        }
        JsonObject valid = insertionTarget();
        valid.addProperty("x", Integer.MIN_VALUE);
        valid.addProperty("y", Integer.MAX_VALUE);
        GridModule.validateParams("grid.modules.insert", valid);
        assertTrue(
                f.host.tasks.isEmpty() && f.adapter.tasks.isEmpty() && f.adapter.insertion.loads == 0,
                "strict insertion shape rejects before any queue/native load");
    }

    private static JsonObject batchInsertion(JsonObject... modules) {
        JsonObject p = target();
        JsonArray array = new JsonArray();
        for (JsonObject m : modules) array.add(m);
        p.add("modules", array);
        return p;
    }

    private static JsonObject moduleAt(String key, String value, int x, int y) {
        JsonObject m = new JsonObject();
        m.addProperty(key, value);
        m.addProperty("x", x);
        m.addProperty("y", y);
        return m;
    }

    @Test
    void insertionBatchAndTypeNameArguments() {
        Fixture f = new Fixture();
        JsonObject named = insertionTarget();
        named.remove("typeId");
        named.addProperty("typeName", "Low-pass");
        GridModule.validateParams("grid.modules.insert", named);
        JsonObject both = insertionTarget();
        both.addProperty("typeName", "Low-pass");
        JsonObject blank = insertionTarget();
        blank.remove("typeId");
        blank.addProperty("typeName", "");
        JsonObject longName = insertionTarget();
        longName.remove("typeId");
        longName.addProperty("typeName", "a".repeat(129));
        String type = GridInsertionWriterTest.TYPE.toString();
        GridModule.validateParams(
                "grid.modules.insert",
                batchInsertion(moduleAt("typeId", type, 1, 1), moduleAt("typeName", "Low-pass", 5, 1)));
        JsonObject withSingle = batchInsertion(moduleAt("typeId", type, 1, 1));
        withSingle.addProperty("x", 1);
        JsonObject emptyBatch = batchInsertion();
        JsonObject[] many = new JsonObject[17];
        Arrays.fill(many, moduleAt("typeId", type, 1, 1));
        GridModule.validateParams("grid.modules.insert", batchInsertion(many));
        JsonObject neither = batchInsertion(moduleAt("typeId", type, 1, 1));
        neither.getAsJsonArray("modules").get(0).getAsJsonObject().remove("typeId");
        JsonObject badCoordinate = batchInsertion(moduleAt("typeId", type, 1, 1));
        badCoordinate.getAsJsonArray("modules").get(0).getAsJsonObject().addProperty("x", 1.5);
        JsonObject extraKey = batchInsertion(moduleAt("typeId", type, 1, 1));
        extraKey.getAsJsonArray("modules").get(0).getAsJsonObject().addProperty("copy", true);
        for (JsonObject bad : List.of(both, blank, longName, withSingle, emptyBatch, neither, badCoordinate, extraKey))
            assertRpcError("invalid_params", f.host.submit(f.module, "grid.modules.insert", bad));
        assertTrue(
                f.host.tasks.isEmpty() && f.adapter.tasks.isEmpty() && f.adapter.insertion.loads == 0,
                "invalid insertion batches reject before scheduling or loading");
    }

    @Test
    void insertionBatchCrossesBothQueuesWithOneDiff() {
        Fixture f = new Fixture();
        f.adapter.insertion.fresh = true;
        f.adapter.insertion.catalogNames("Alpha", "Beta");
        HostOperation op = f.host.submit(
                f.module,
                "grid.modules.insert",
                batchInsertion(
                        moduleAt("typeName", "alpha", 8, 3), moduleAt("typeId", new UUID(2, 101).toString(), 12, 3)));
        f.host.next();
        f.adapter.next();
        assertTrue(
                op.dispatched() && f.adapter.insertion.loads == 1 && !op.result.isDone(),
                "first entry loads; a controller dispatch is not completion");
        f.adapter.insertion.deliver();
        f.adapter.next();
        assertTrue(
                f.adapter.insertion.loads == 2 && !op.result.isDone(),
                "second entry starts only after the first settled");
        f.adapter.insertion.deliver();
        f.adapter.next();
        f.adapter.insertion.guard.requireAvailable(GridInsertionWriterTest.ANY);
        f.host.next();
        JsonObject result = op.result.join();
        assertTrue(
                result.get("outcome").getAsString().equals("applied")
                        && result.get("written").getAsInt() == 2
                        && result.getAsJsonArray("modules").size() == 2
                        && result.getAsJsonArray("modulesAdded").size() == 2
                        && !result.has("graph")
                        && !result.has("graphBefore")
                        && result.getAsJsonObject("target")
                                .get("trackId")
                                .getAsString()
                                .equals(HostFixture.A),
                "batch returns per-entry results, one diff of every added module and the explicit target");
    }

    @Test
    void displayWriteArgumentsValidateBeforeScheduling() {
        Fixture f = new Fixture();
        JsonObject single = target();
        single.addProperty("moduleId", "00000000-0000-0001-0000-000000000001");
        single.addProperty("parameterId", "P");
        single.addProperty("expectedKind", "float");
        single.addProperty("expectedValue", 84);
        single.addProperty("display", "1.2 kHz");
        GridModule.validateParams("grid.parameters.set", single);
        JsonObject batch = batchTarget("P");
        JsonObject w = batch.getAsJsonArray("writes").get(0).getAsJsonObject();
        w.addProperty("expectedKind", "float");
        w.addProperty("expectedValue", 84);
        w.remove("value");
        w.addProperty("display", "440 Hz");
        GridModule.validateParams("grid.parameters.set", batch);
        JsonObject both = single.deepCopy();
        both.addProperty("value", 90);
        JsonObject neither = single.deepCopy();
        neither.remove("display");
        JsonObject notFloat = single.deepCopy();
        notFloat.addProperty("expectedKind", "integer");
        JsonObject number = single.deepCopy();
        number.addProperty("display", 5);
        JsonObject empty = single.deepCopy();
        empty.addProperty("display", "");
        JsonObject batchBoth = batchTarget("P");
        batchBoth.getAsJsonArray("writes").get(0).getAsJsonObject().addProperty("display", "1 %");
        for (JsonObject bad : List.of(both, neither, notFloat, number, empty, batchBoth))
            assertRpcError("invalid_params", f.host.submit(f.module, "grid.parameters.set", bad));
        assertTrue(
                f.host.tasks.isEmpty() && f.adapter.tasks.isEmpty(),
                "display and value are mutually exclusive and typed before scheduling");
    }

    @Test
    void ackReleasesSettlementMismatchThroughTheQueues() {
        Fixture f = new Fixture();
        HostOperation op = f.host.submit(f.module, "grid.modules.insert", insertionTarget());
        f.host.next();
        f.adapter.next();
        f.adapter.insertion.deliver();
        f.adapter.insertion.incoming.put(GridSnapshotReader.Field.Y, 9);
        f.adapter.next();
        assertTrue(
                f.adapter.insertion.guard.quarantined() && assertUnknown("readback_mismatch", op) != null,
                "settlement mismatch holds the device with an unknown request");
        JsonObject compact = GridModule.compactCapabilities(capabilitiesWith(f.adapter.insertion.guard));
        assertTrue(
                compact.getAsJsonObject("topologyQuarantine")
                        .getAsJsonArray("devices")
                        .get(0)
                        .getAsJsonObject()
                        .get("origin")
                        .getAsString()
                        .equals("settlement_mismatch"),
                "compact capabilities keep the quarantined device and its origin");
        AckModule ack = f.host.services.ack(f.module);
        JsonObject id = new JsonObject();
        id.addProperty("id", GridTopologyQuarantine.holdId(HostFixture.A + "/" + graphKey(f)));
        HostOperation acked = f.host.submit(ack, "ack", id);
        f.host.next();
        f.adapter.next();
        f.host.next();
        JsonObject result = acked.result.join();
        assertTrue(
                result.get("outcome").getAsString().equals("applied")
                        && result.getAsJsonObject("found")
                                .getAsJsonObject("module")
                                .get("inserted")
                                .getAsBoolean()
                        && result.getAsJsonObject("target")
                                .get("trackId")
                                .getAsString()
                                .equals(HostFixture.A)
                        && !acked.dispatched(),
                "ack is a document-queue read that releases a consistent mismatch");
        f.adapter.insertion.guard.requireAvailable(GridInsertionWriterTest.ANY);
        HostOperation again = f.host.submit(ack, "ack", id);
        f.host.next();
        f.adapter.next();
        assertRpcError("unknown_hold", again);
        JsonObject elsewhere = new JsonObject();
        elsewhere.addProperty("id", GridTopologyQuarantine.holdId(HostFixture.A + "/0"));
        HostOperation missing = f.host.submit(ack, "ack", elsewhere);
        f.host.next();
        f.adapter.next();
        assertTrue(
                assertRpcError("unknown_hold", missing).getMessage().contains("No observed Grid device"),
                "a hold id naming no observed device on the track releases nothing");
    }

    /** The device part of a Grid hold id: the identity of the fixture's native graph. */
    private static String graphKey(Fixture f) {
        String device = GridInsertionWriterTest.device(f.adapter.insertion);
        return device.substring(device.indexOf('/') + 1);
    }

    /** An error when nothing was dispatched, else a settled unknown result; both carry {@code reason}. */
    private static boolean failed(String reason, HostOperation op, boolean beforeDispatch) {
        if (beforeDispatch) assertRpcError(reason, op);
        else assertUnknown(reason, op);
        return true;
    }

    private static JsonObject capabilitiesWith(GridTopologyQuarantine guard) {
        JsonObject full = new JsonObject();
        full.addProperty("topologyQuarantined", guard.quarantined());
        full.add("topologyQuarantine", Bitwig614GridAdapter.quarantineState(guard));
        return full;
    }

    @Test
    void insertionWaitsForNativeSettlementAndControllerDelivery() {
        Fixture f = new Fixture();
        HostOperation op = f.host.submit(f.module, "grid.modules.insert", insertionTarget());
        f.host.next();
        f.adapter.next();
        assertTrue(
                op.dispatched() && f.adapter.insertion.loads == 1 && !op.result.isDone() && f.host.tasks.isEmpty(),
                "loader dispatch has its own native lifetime, not controller completion");
        for (String key : List.of(
                "isPlaying",
                "isArrangerRecordEnabled",
                "isArrangerAutomationWriteEnabled",
                "isClipLauncherAutomationWriteEnabled"))
            f.host.transport.child(key).onCall = (name, args) -> {
                if (name.equals("get") && !f.host.onHost)
                    throw new AssertionError("Controller getter in native callback");
            };
        f.adapter.insertion.deliver();
        assertTrue(
                f.adapter.insertion.placements == 1 && !op.result.isDone(),
                "preplacement callback uses published safety only");
        f.adapter.next();
        f.adapter.insertion.guard.requireAvailable(GridInsertionWriterTest.ANY);
        assertTrue(
                !op.result.isDone() && f.host.tasks.size() == 1,
                "healthy native settlement releases independently before controller delivery");
        f.host.next();
        JsonObject result = op.result.join();
        assertTrue(
                result.getAsJsonObject("target").get("trackId").getAsString().equals(HostFixture.A)
                        && result.get("loaderDispatched").getAsBoolean()
                        && result.get("placementDispatched").getAsBoolean(),
                "actual insertion result receives existing explicit target envelope");
        assertTrue(
                result.getAsJsonArray("modulesAdded").size() == 1
                        && !result.has("graph")
                        && !result.has("graphBefore")
                        && result.getAsJsonArray("modulesAdded")
                                .get(0)
                                .getAsJsonObject()
                                .get("instanceId")
                                .getAsString()
                                .equals(result.get("moduleId").getAsString())
                        && result.getAsJsonArray("modulesAdded")
                                .get(0)
                                .getAsJsonObject()
                                .has("outputs"),
                "insert returns the new module row with ports as a diff, so it can be wired without a grid.get");
    }

    @Test
    void insertionSafetyCancellationAndCloseKeepNativeLifetimeIndependent() {
        for (String key : List.of(
                "isPlaying",
                "isArrangerRecordEnabled",
                "isArrangerAutomationWriteEnabled",
                "isClipLauncherAutomationWriteEnabled")) {
            Fixture f = new Fixture();
            f.host.transport.child(key).emit(true);
            HostOperation op = f.host.submit(f.module, "grid.modules.insert", insertionTarget());
            f.host.next();
            assertTrue(
                    !assertRpcError("unsafe_state", op).unknown && f.adapter.insertion.loads == 0,
                    "observed unsafe transport blocks insertion before native loading");
        }
        for (int change = 0; change < 4; change++) {
            Fixture f = new Fixture();
            HostOperation op = f.host.submit(f.module, "grid.modules.insert", insertionTarget());
            f.host.next();
            f.adapter.next();
            if (change == 0) {
                f.host.transport.child("isPlaying").emit(true);
                f.host.transport.child("isPlaying").emit(false);
            }
            if (change == 1) {
                f.device.child("name").emit("Other");
                f.device.child("name").emit("Poly Grid");
            }
            if (change == 2) op.result.cancel(false);
            if (change == 3) f.host.services.scheduler.close();
            f.adapter.insertion.deliver();
            assertTrue(
                    f.adapter.insertion.cleanups == 1 && f.adapter.insertion.placements == 0,
                    "late public epoch/cancel/close rejects via delivered cleanup");
            f.adapter.next();
            f.adapter.insertion.guard.requireAvailable(GridInsertionWriterTest.ANY);
            assertTrue(
                    (op.result.isCancelled() || assertOutcome("unknown", op) != null) && f.host.tasks.isEmpty(),
                    "native settlement is not skipped by controller schedule/future/extension close");
            if (change < 2)
                assertTrue(
                        assertUnknown("stale_context", op) != null,
                        "loader dispatch preserves uncertainty after public ABA");
        }
        Fixture late = new Fixture();
        HostOperation op = late.host.submit(late.module, "grid.modules.insert", insertionTarget());
        late.host.next();
        late.adapter.next();
        late.adapter.insertion.deliver();
        late.adapter.next();
        late.device.child("position").emit(1);
        late.host.next();
        late.adapter.insertion.guard.requireAvailable(GridInsertionWriterTest.ANY);
        assertTrue(
                assertUnknown("stale_context", op) != null && late.adapter.insertion.placements == 1,
                "post-settlement controller rejection never resurrects native pending lifetime");
    }

    private static JsonObject renameTarget(String expected, String name) {
        JsonObject p = target();
        p.addProperty("moduleId", "00000000-0000-0001-0000-000000000001");
        p.addProperty("expectedCustomName", expected);
        p.addProperty("customName", name);
        return p;
    }

    @Test
    void renameArgumentsRejectBeforeQueues() {
        Fixture f = new Fixture();
        for (String key : List.of(
                "trackId", "deviceIndex", "expectedDeviceName", "moduleId", "expectedCustomName", "customName")) {
            JsonObject p = renameTarget("", "Lead");
            p.remove(key);
            assertRpcError("invalid_params", f.host.submit(f.module, "grid.modules.rename", p));
            p = renameTarget("", "Lead");
            p.add(key, JsonNull.INSTANCE);
            assertRpcError("invalid_params", f.host.submit(f.module, "grid.modules.rename", p));
        }
        for (String key : List.of("expectedCustomName", "customName")) {
            for (String token : List.of("0", "1.0", "true", "[]", "{}")) {
                JsonObject p = renameTarget("", "Lead");
                p.add(key, JsonParser.parseString(token));
                assertRpcError("invalid_params", f.host.submit(f.module, "grid.modules.rename", p));
            }
            for (String text : List.of("x".repeat(1025), "𝄞".repeat(513), "x".repeat(1023) + "𝄞")) {
                JsonObject p = renameTarget("", "Lead");
                p.addProperty(key, text);
                assertRpcError("invalid_params", f.host.submit(f.module, "grid.modules.rename", p));
            }
            for (String text : List.of("", " \t音楽=α\n", "x".repeat(1024), "𝄞".repeat(512))) {
                JsonObject p = renameTarget("", "Lead");
                p.addProperty(key, text);
                GridModule.validateParams("grid.modules.rename", p);
            }
        }
        for (String key : List.of("trackId", "moduleId"))
            for (String id : List.of("", "selected", "1-1-1-1-1", "00000000000000010000000000000001")) {
                JsonObject p = renameTarget("", "Lead");
                p.addProperty(key, id);
                assertRpcError("invalid_params", f.host.submit(f.module, "grid.modules.rename", p));
            }
        for (String key : List.of("typeId", "name", "parameterId", "selected", "force", "includeParameters")) {
            JsonObject p = renameTarget("", "Lead");
            p.addProperty(key, "forbidden");
            assertRpcError("invalid_params", f.host.submit(f.module, "grid.modules.rename", p));
        }
        assertTrue(
                f.host.tasks.isEmpty() && f.adapter.tasks.isEmpty() && f.adapter.naming.calls == 0,
                "rename validates exact raw string fields and canonical identities before scheduling");
    }

    @Test
    void renameUsesRealWriterAndBothDeliveryQueues() {
        Fixture f = new Fixture();
        String raw = " \tLead = 音楽𝄞\n";
        HostOperation op = f.host.submit(f.module, "grid.modules.rename", renameTarget("", raw));
        f.host.next();
        for (String key : List.of(
                "isPlaying",
                "isArrangerRecordEnabled",
                "isArrangerAutomationWriteEnabled",
                "isClipLauncherAutomationWriteEnabled"))
            f.host.transport.child(key).onCall = (name, args) -> {
                if (name.equals("get") && !f.host.onHost)
                    throw new AssertionError("Controller getter in name document callback");
            };
        f.adapter.next();
        assertTrue(
                op.dispatched() && f.adapter.naming.calls == 1 && !op.result.isDone() && f.host.tasks.isEmpty(),
                "real native name setter does not complete before later document observation");
        f.adapter.next();
        assertTrue(
                !op.result.isDone() && f.host.tasks.size() == 1,
                "observed name waits for controller target and safety delivery");
        f.host.next();
        JsonObject result = op.result.join();
        assertTrue(
                result.get("previousCustomName").getAsString().equals("")
                        && result.get("requestedCustomName").getAsString().equals(raw)
                        && result.get("actualCustomName").getAsString().equals(raw)
                        && result.get("previousName").getAsString().equals("Union")
                        && result.getAsJsonObject("target").get("deviceIndex").getAsInt() == 0,
                "real writer raw/effective observations and explicit target survive immutable delivery");
        HostOperation reset = f.host.submit(f.module, "grid.modules.rename", renameTarget(raw, ""));
        f.host.next();
        f.adapter.next();
        f.adapter.next();
        f.host.next();
        assertTrue(
                reset.result.join().get("actualCustomName").getAsString().isEmpty() && f.adapter.naming.calls == 2,
                "empty reset passes actual raw CAS and native semantics without type-name substitution");
        HostOperation stale = f.host.submit(f.module, "grid.modules.rename", renameTarget("Union", ""));
        f.host.next();
        f.adapter.next();
        assertTrue(
                !assertRpcError("stale_context", stale).unknown && f.adapter.naming.calls == 2,
                "effective name cannot authorize even an otherwise matching raw no-op");
        HostOperation noop = f.host.submit(f.module, "grid.modules.rename", renameTarget("", ""));
        f.host.next();
        f.adapter.next();
        assertTrue(
                !noop.dispatched() && f.adapter.naming.calls == 2, "authorized no-op never calls native name setter");
        while (!f.adapter.tasks.isEmpty()) f.adapter.next();
        f.host.next();
        assertTrue(
                !noop.result.join().get("mutationDispatched").getAsBoolean(),
                "no-op observation never invents native mutation");
    }

    @Test
    void renameSafetyAndTargetEpochsGuardEveryBoundary() {
        for (String key : List.of(
                "isPlaying",
                "isArrangerRecordEnabled",
                "isArrangerAutomationWriteEnabled",
                "isClipLauncherAutomationWriteEnabled")) {
            Fixture unsafe = new Fixture();
            unsafe.host.transport.child(key).emit(true);
            HostOperation op = unsafe.host.submit(unsafe.module, "grid.modules.rename", renameTarget("", ""));
            unsafe.host.next();
            assertTrue(
                    !assertRpcError("unsafe_state", op).unknown && unsafe.adapter.naming.calls == 0,
                    "unsafe no-op still requires stopped/nonrecording/nonautomation authorization");
        }
        Fixture controller = new Fixture();
        HostOperation queued =
                controller.host.submit(controller.module, "grid.modules.rename", renameTarget("", "Lead"));
        controller.host.transport.child("isPlaying").emit(true);
        controller.host.transport.child("isPlaying").emit(false);
        controller.host.next();
        assertTrue(
                !assertRpcError("stale_context", queued).unknown && controller.adapter.tasks.isEmpty(),
                "rename safety ticket rejects transitions before initial controller dispatch");
        Fixture unobserved = new Fixture(true, false);
        HostOperation unready =
                unobserved.host.submit(unobserved.module, "grid.modules.rename", renameTarget("", "Lead"));
        unobserved.host.next();
        assertTrue(
                !assertRpcError("not_ready", unready).unknown && unobserved.adapter.naming.calls == 0,
                "unobserved safety never authorizes rename");
        for (int boundary = 0; boundary < 3; boundary++)
            for (int transition = 0; transition < 3; transition++) {
                Fixture f = new Fixture();
                HostOperation op = f.host.submit(f.module, "grid.modules.rename", renameTarget("", "Lead"));
                f.host.next();
                if (boundary > 0) f.adapter.next();
                if (boundary > 1) f.adapter.next();
                if (transition == 0) {
                    f.host.transport.child("isPlaying").emit(true);
                    f.host.transport.child("isPlaying").emit(false);
                } else if (transition == 1) {
                    f.device.child("name").emit("Other");
                    f.device.child("name").emit("Poly Grid");
                } else {
                    f.host.tracks[0].child("channelId").emit(HostFixture.B);
                    f.host.tracks[0].child("channelId").emit(HostFixture.A);
                }
                if (boundary < 2) f.adapter.next();
                else f.host.next();
                assertTrue(
                        failed("stale_context", op, boundary == 0) && f.adapter.naming.calls == (boundary == 0 ? 0 : 1),
                        "name safety/target ABA at dispatch, document observation or controller delivery never retries");
            }
        Fixture immediate = new Fixture();
        immediate.adapter.naming.onSet = () -> {
            immediate.host.transport.child("isPlaying").emit(true);
            immediate.host.transport.child("isPlaying").emit(false);
        };
        HostOperation changed =
                immediate.host.submit(immediate.module, "grid.modules.rename", renameTarget("", "Lead"));
        immediate.host.next();
        immediate.adapter.next();
        if (!immediate.adapter.tasks.isEmpty()) immediate.adapter.next();
        assertTrue(
                assertUnknown("stale_context", changed) != null && immediate.adapter.naming.calls == 1,
                "a safety transition during the native setter cannot be reported not-applied");
        Fixture noop = new Fixture();
        HostOperation op = noop.host.submit(noop.module, "grid.modules.rename", renameTarget("", ""));
        noop.host.next();
        noop.adapter.next();
        while (!noop.adapter.tasks.isEmpty()) noop.adapter.next();
        noop.device.child("position").emit(1);
        noop.host.next();
        assertTrue(
                !assertRpcError("stale_context", op).unknown && !op.dispatched(),
                "late no-op delivery still checks explicit target without claiming a write");
    }

    @Test
    void renameCancellationAndClosePreserveDispatchOutcome() {
        for (int boundary = 0; boundary < 3; boundary++)
            for (boolean close : List.of(false, true)) {
                Fixture f = new Fixture();
                HostOperation op = f.host.submit(f.module, "grid.modules.rename", renameTarget("", "Lead"));
                f.host.next();
                if (boundary > 0) f.adapter.next();
                if (boundary > 1) f.adapter.next();
                if (close) f.host.services.scheduler.close();
                else op.result.cancel(false);
                if (boundary < 2) f.adapter.next();
                else f.host.next();
                assertTrue(
                        f.adapter.naming.calls == (boundary == 0 ? 0 : 1) && op.dispatched() == (boundary > 0),
                        "cancel/close before native dispatch prevents setter; after dispatch never rolls back or retries");
                if (close)
                    assertTrue(
                            failed("not_ready", op, boundary == 0), "close preserves native rename outcome boundary");
                else assertTrue(op.result.isCancelled(), "cancelled rename cannot be completed by late callbacks");
            }
    }

    private static JsonObject inspectionTarget() {
        JsonObject result = target();
        result.addProperty("moduleId", "00000000-0000-0001-0000-000000000001");
        result.addProperty("parameterId", "EXACT_ID");
        return result;
    }

    @Test
    void layerAddressingNeedsObservedLayersAndChildren() {
        JsonObject badParent = target();
        badParent.addProperty("trackId", "not-a-uuid");
        assertInvalidParams(() -> DeviceSources.validateParent(badParent));
        assertInvalidParams(() -> DeviceSources.validateLayer(HostFixture.json("{\"parent\":1}"), false, true));
        HostFixture unobserved = HostFixture.withoutServices();
        unobserved.tracks[0].child("createDeviceBank").child("scrollPosition").notifyInitial = false;
        TestServices services = new TestServices(
                unobserved.host.proxy(com.bitwig.extension.controller.api.ControllerHost.class), unobserved.tasks::now);
        assertTrue(
                assertRpcError("not_ready", () -> services.sources.root(target()))
                        .getMessage()
                        .contains("Root device window"),
                "a root device window that was never observed is not addressable");
        Fixture f = new Fixture();
        DeviceSources sources = f.host.services.sources;
        FakeHost layers = f.device.child("createLayerBank");
        f.device.child("hasLayers").emit(true);
        layers.child("exists").emit(true);
        layers.child("itemCount").emit(1);
        FakeHost layer = layers.item(0);
        String id = "00000000-0000-0000-0000-0000000000aa";
        layer.child("exists").emit(true);
        assertTrue(
                assertRpcError("not_ready", () -> sources.layer(target(), id))
                        .getMessage()
                        .contains("Layer identity window"),
                "an existing layer without an observed UUID is not addressable");
        layer.child("channelId").emit(id);
        assertTrue(
                assertRpcError("not_ready", () -> sources.layer(target(), id))
                        .getMessage()
                        .contains("Layer and child window"),
                "a layer whose child window was never observed is not addressable");
        layer.child("createDeviceBank").child("exists").emit(true);
        JsonObject child = new JsonObject();
        child.add("parent", target());
        child.addProperty("layerId", id);
        child.addProperty("childDeviceIndex", 2);
        child.addProperty("expectedChildDeviceName", "Child");
        child.addProperty("discoveryToken", "token");
        assertRpcError("outside_window", () -> sources.child(child));
        child.addProperty("childDeviceIndex", 0);
        assertTrue(
                assertRpcError("not_ready", () -> sources.child(child))
                        .getMessage()
                        .contains("Child metadata"),
                "an absent layer child is not addressable");
    }

    @Test
    void voicingAndRemotePagesCrossBothQueues() {
        Fixture f = new Fixture();
        String slots = "\"slots\":[{\"moduleId\":\"" + new UUID(1, 1) + "\",\"parameterId\":\"VALUE\"}]";
        for (String[] call : new String[][] {
            {"grid.voicing.get", ""},
            {"grid.voicing.set", "\"expected\":{\"voices\":1},\"set\":{\"voices\":2}"},
            {"devices.remote.define", "\"pageName\":\"Macros\"," + slots},
            {"devices.remote.deletePage", "\"pageName\":\"Macros\""}
        }) {
            JsonObject params = target();
            if (!call[1].isEmpty())
                HostFixture.json("{" + call[1] + "}").entrySet().forEach(e -> params.add(e.getKey(), e.getValue()));
            HostOperation op = f.host.submit(f.module, call[0], params);
            f.host.next();
            f.adapter.next();
            f.host.next();
            assertTrue(
                    op.result.join().has("target") && op.dispatched() == !call[0].equals("grid.voicing.get"),
                    call[0] + " reaches the adapter once and dispatches only when it writes");
        }
        assertTrue(f.adapter.reads == 1 && f.adapter.remoteCalls == 2, "each call reached its adapter method");
    }

    private static JsonObject target() {
        JsonObject result = HostFixture.target(HostFixture.A);
        result.addProperty("deviceIndex", 0);
        result.addProperty("expectedDeviceName", "Poly Grid");
        return result;
    }

    private static final class Fixture {
        final HostFixture host;
        final FakeHost device;
        final FakeAdapter adapter = new FakeAdapter();
        final GridModule module;

        Fixture() {
            this(true, true);
        }

        Fixture(boolean observed) {
            this(observed, true);
        }

        Fixture(boolean observed, boolean safety) {
            host = new HostFixture(safety);
            device = host.tracks[0].child("createDeviceBank").item(0);
            FakeHost bank = host.tracks[0].child("createDeviceBank");
            device.child("exists").emit(true);
            device.child("position").emit(0);
            device.child("deviceType").emit("AudioEffect");
            bank.child("itemCount").emit(1);
            device.child("name").emit(observed ? "Poly Grid" : "");
            module = host.services.grid(adapter);
        }

        HostOperation start(JsonObject p) {
            HostOperation op = host.submit(module, "grid.get", p);
            host.next();
            return op;
        }

        HostOperation startWrite() {
            HostOperation op = host.submit(module, "grid.parameters.set", writeTarget());
            host.next();
            return op;
        }
    }

    private static final class FakeAdapter implements GridAdapter {
        final Deque<Runnable> tasks = new ArrayDeque<>();
        int reads, remoteCalls;
        boolean fail;
        JsonObject last;
        final GridSnapshotReaderTest.WriterFixture writer = new GridSnapshotReaderTest.WriterFixture();
        final GridSnapshotReaderTest.ModuleWriterFixture topology =
                new GridSnapshotReaderTest.ModuleWriterFixture(tasks::add);
        final GridSnapshotReaderTest.ConnectionFixture connections =
                new GridSnapshotReaderTest.ConnectionFixture(tasks::add);
        final GridInsertionWriterTest.Fixture insertion = new GridInsertionWriterTest.Fixture(tasks::add);
        final GridModuleNameWriterTest.Fixture naming = new GridModuleNameWriterTest.Fixture(tasks::add);
        Runnable beforeWrite = () -> {}, afterWrite = () -> {};

        void next() {
            tasks.removeFirst().run();
        }

        @Override
        public void execute(Runnable task) {
            tasks.add(task);
        }

        @Override
        public JsonObject capabilities() {
            JsonObject result = new JsonObject();
            result.addProperty("available", true);
            return result;
        }

        @Override
        public JsonObject catalog(String query, int offset, int limit, Runnable check) {
            check.run();
            reads++;
            return result(offset, limit, false);
        }

        @Override
        public JsonObject setParameters(
                Object proxy,
                UUID track,
                java.util.List<GridParameterWriter.Entry> writes,
                Runnable check,
                Runnable dispatch) {
            last = writer.setAll(writes, check, dispatch);
            return last;
        }

        @Override
        public JsonObject snapshot(
                Object proxy,
                UUID expectedTrackId,
                int offset,
                int limit,
                boolean parameters,
                boolean diagnostics,
                java.util.Set<String> moduleIds,
                boolean fullSteps,
                Runnable check) {
            check.run();
            reads++;
            if (fail) throw Errors.notApplied(Reason.KIND_MISMATCH, "not a Grid device");
            return result(offset, limit, parameters);
        }

        @Override
        public JsonObject parameter(
                Object proxy,
                UUID expectedTrackId,
                UUID moduleId,
                String parameterId,
                boolean fullSteps,
                Runnable check) {
            check.run();
            reads++;
            return new JsonObject();
        }

        @Override
        public JsonObject voicing(Object proxy, UUID expectedTrackId, Runnable check) {
            check.run();
            reads++;
            return new JsonObject();
        }

        @Override
        public JsonObject setVoicing(
                Object proxy, UUID track, GridVoicingWriter.Request request, Runnable check, Runnable dispatch) {
            check.run();
            dispatch.run();
            return new JsonObject();
        }

        @Override
        public JsonObject defineRemotePage(
                Object proxy, UUID track, GridRemotePageWriter.Request request, Runnable check, Runnable dispatch) {
            check.run();
            dispatch.run();
            remoteCalls++;
            return new JsonObject();
        }

        @Override
        public JsonObject deleteRemotePage(
                Object proxy, UUID track, String pageName, Runnable check, Runnable dispatch) {
            check.run();
            dispatch.run();
            remoteCalls++;
            return new JsonObject();
        }

        @Override
        public JsonObject setParameter(
                Object proxy,
                UUID track,
                UUID module,
                String parameter,
                GridParameterWriter.Request request,
                Runnable check,
                Runnable dispatch) {
            beforeWrite.run();
            last = writer.set(request, check, dispatch);
            afterWrite.run();
            return last;
        }

        @Override
        public void moveModule(
                Object proxy,
                UUID track,
                UUID module,
                GridModuleWriter.Move request,
                Runnable check,
                Runnable dispatch,
                java.util.function.Consumer<JsonObject> complete,
                java.util.function.Consumer<Throwable> failure) {
            topology.moduleId = module;
            topology.edit(request, check, dispatch, complete, failure);
        }

        @Override
        public void deleteModule(
                Object proxy,
                UUID track,
                UUID module,
                Runnable check,
                Runnable dispatch,
                java.util.function.Consumer<JsonObject> complete,
                java.util.function.Consumer<Throwable> failure) {
            topology.moduleId = module;
            topology.edit(null, check, dispatch, complete, failure);
        }

        @Override
        public void setConnection(
                Object proxy,
                UUID track,
                GridConnectionWriter.Request request,
                Runnable check,
                Runnable dispatch,
                java.util.function.Consumer<JsonObject> complete,
                java.util.function.Consumer<Throwable> failure) {
            connections.edit(request, check, dispatch, complete, failure);
        }

        @Override
        public void setConnections(
                Object proxy,
                UUID track,
                java.util.List<GridConnectionWriter.Request> requests,
                Runnable check,
                Runnable dispatch,
                java.util.function.Consumer<JsonObject> complete,
                java.util.function.Consumer<Throwable> failure) {
            connections.editAll(requests, check, dispatch, complete, failure);
        }

        @Override
        public void insertModule(
                Object proxy,
                UUID track,
                GridInsertionWriter.Request request,
                Runnable check,
                Runnable dispatch,
                java.util.function.Consumer<JsonObject> complete,
                java.util.function.Consumer<Throwable> failure) {
            insertion.edit(request, check, dispatch, complete, failure);
        }

        @Override
        public void insertModules(
                Object proxy,
                UUID track,
                java.util.List<GridInsertionWriter.Request> requests,
                java.util.function.LongSupplier timeLeft,
                Runnable check,
                Runnable dispatch,
                java.util.function.Consumer<JsonObject> complete,
                java.util.function.Consumer<Throwable> failure) {
            insertion.editAll(requests, check, dispatch, complete, failure);
        }

        @Override
        public JsonObject ackTopology(Object proxy, UUID track, String device, Runnable check) {
            String nativeDevice = GridInsertionWriterTest.TRACK + device.substring(device.indexOf('/'));
            return insertion.writer.ack(insertion.model.proxy, GridInsertionWriterTest.TRACK, nativeDevice, check);
        }

        @Override
        public void renameModule(
                Object proxy,
                UUID track,
                UUID module,
                GridModuleNameWriter.Request request,
                Runnable check,
                Runnable dispatch,
                java.util.function.Consumer<JsonObject> complete,
                java.util.function.Consumer<Throwable> failure) {
            naming.edit(module, request, check, dispatch, complete, failure);
        }

        private JsonObject result(int offset, int limit, boolean parameters) {
            last = new JsonObject();
            last.addProperty("offset", offset);
            last.addProperty("limit", limit);
            last.addProperty("parameters", parameters);
            return last;
        }
    }
}
