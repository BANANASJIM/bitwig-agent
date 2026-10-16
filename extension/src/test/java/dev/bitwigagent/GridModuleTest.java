package dev.bitwigagent;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Independent controller/document queues exercise cancellation, stale targeting and immutable handoff. */
public final class GridModuleTest {
    private static int assertions;
    public static void main(String[] args) throws Exception {
        validatesBeforeScheduling();
        requiresExplicitObservedTarget();
        documentQueueDoesNotCompleteOnDispatch();
        detectsRebindEvenWhenNameReturns();
        rechecksAfterDocumentRead();
        cancelledExpiredAndClosedOperationsDoNotRead();
        independentConcurrentReadsDoNotShareTargets();
        noMutableResultsCrossThreadBoundary();
        boundsTransportAndPreservesNulls();
        loadedSourceGateFailsClosed();
        parameterInspectionRejectsInvalidTargetsAndQueuedRaces();
        writesRequireStrictObservedStoppedInputs();
        queuedWritesRejectSafetyAndTargetTransitions();
        stringWriteSchemaRejectsBeforeScheduling();
        nativeCallbacksNeverReadControllerSafetyGetters();
        everyPostDispatchBoundaryKeepsUnknownOutcome();
        topologyArgumentsRejectBeforeScheduling();
        topologyOperationsRequireLaterDocumentObservation();
        topologySafetyAndTargetsGuardEveryQueueBoundary();
        connectionArgumentsPreserveRequiredNullSemantics();
        connectionResultsWaitForBothQueues();
        connectionSafetyTargetsAndCancellationGuardQueues();
        insertionArguments();
        insertionWaitsForNativeSettlementAndControllerDelivery();
        insertionSafetyCancellationAndCloseKeepNativeLifetimeIndependent();
        renameArgumentsRejectBeforeQueues();
        renameUsesRealWriterAndBothDeliveryQueues();
        renameSafetyAndTargetEpochsGuardEveryBoundary();
        renameCancellationAndClosePreserveDispatchOutcome();
        System.out.println("GridModuleTest: " + assertions + " assertions passed (simulated controller/document queues only)");
    }
    private static void validatesBeforeScheduling() {
        Fixture f = new Fixture();
        for (String text : List.of("{\"limit\":0}", "{\"limit\":257}", "{\"offset\":-1}", "{\"offset\":100001}",
                "{\"limit\":1.5}", "{\"query\":null}", "{\"query\":3}", "{\"unknown\":true}")) {
            TransportMixFixture.error(f.host.submit(f.module, "grid.catalog", TransportMixFixture.json(text)), "invalid_params");
            check(f.host.tasks.isEmpty() && f.adapter.tasks.isEmpty(), "invalid catalog request not queued");
        }
        JsonObject p = new JsonObject(); p.addProperty("query", "a".repeat(129));
        TransportMixFixture.error(f.host.submit(f.module, "grid.catalog", p), "invalid_params");
        String supplementary = new String(Character.toChars(0x1D11E));
        p.addProperty("query", supplementary.repeat(64)); GridModule.validateParams("grid.catalog", p);
        p.addProperty("query", supplementary.repeat(65));
        TransportMixFixture.error(f.host.submit(f.module, "grid.catalog", p), "invalid_params");
        for (String field : List.of("trackId", "deviceIndex", "expectedDeviceName")) {
            p = target(); p.remove(field); TransportMixFixture.error(f.host.submit(f.module, "grid.get", p), "invalid_params");
        }
        p = target(); p.addProperty("moduleLimit", 129); TransportMixFixture.error(f.host.submit(f.module, "grid.get", p), "invalid_params");
        p = target(); p.addProperty("deviceIndex", 16); TransportMixFixture.error(f.host.submit(f.module, "grid.get", p), "invalid_params");
        p = target(); p.addProperty("includeParameters", "false"); TransportMixFixture.error(f.host.submit(f.module, "grid.get", p), "invalid_params");
        p = target(); p.addProperty("trackId", "not-a-uuid"); TransportMixFixture.error(f.host.submit(f.module, "grid.get", p), "invalid_params");
        GridModule.validateParams("grid.catalog", TransportMixFixture.json("{\"query\":\"\",\"offset\":100000,\"limit\":256}"));
        check(f.adapter.reads == 0, "validation never touches adapter");
    }
    private static void requiresExplicitObservedTarget() {
        Fixture f = new Fixture(); f.device.child("name").emit("Tool");
        HostOperation op = f.start(target()); TransportMixFixture.error(op, "stale_context");
        check(f.adapter.tasks.isEmpty(), "wrong expected name never enters document queue");
        f.device.child("name").emit("Poly Grid"); f.device.child("exists").emit(false);
        TransportMixFixture.error(f.start(target()), "target_not_found");
        f.device.child("exists").emit(true); f.host.tracks[0].child("createDeviceBank").child("scrollPosition").emit(1);
        TransportMixFixture.error(f.start(target()), "stale_context");
        Fixture unobserved = new Fixture(false);
        TransportMixFixture.error(unobserved.start(target()), "not_ready");
    }
    private static void documentQueueDoesNotCompleteOnDispatch() {
        Fixture f = new Fixture(); HostOperation op = f.start(target());
        check(!op.result.isDone() && f.adapter.reads == 0, "controller scheduling is not native read completion");
        f.adapter.next(); check(!op.result.isDone() && f.adapter.reads == 1, "document read waits for controller target recheck");
        f.host.next(); op.result.join();
        check(!op.dispatched, "native read never marks a host mutation dispatched");
    }
    private static void detectsRebindEvenWhenNameReturns() {
        Fixture f = new Fixture(); HostOperation op = f.start(target());
        f.device.child("name").emit("Other"); f.device.child("name").emit("Poly Grid");
        f.adapter.next(); TransportMixFixture.error(op, "stale_context");
        check(f.adapter.reads == 0, "generation change detects ABA name transition before native read");
        Fixture track = new Fixture(); op = track.start(target());
        track.host.tracks[0].child("channelId").emit(TransportMixFixture.B);
        track.host.tracks[0].child("channelId").emit(TransportMixFixture.A);
        track.adapter.next(); TransportMixFixture.error(op, "stale_context");
        check(track.adapter.reads == 0, "track ABA detected independently of device callbacks");
    }
    private static void rechecksAfterDocumentRead() {
        Fixture f = new Fixture(); HostOperation op = f.start(target()); f.adapter.next();
        f.device.child("position").emit(1); f.host.next();
        check(TransportMixFixture.error(op, "stale_context").outcome.equals("not_applied"), "post-read rebind rejects stale result without mutation ambiguity");
    }
    private static void cancelledExpiredAndClosedOperationsDoNotRead() {
        Fixture f = new Fixture(); HostOperation op = f.start(target()); op.result.cancel(false); f.adapter.next();
        check(f.adapter.reads == 0 && f.host.tasks.isEmpty(), "cancelled document queue work never reads");
        op = f.host.submit(f.module, "grid.get", target(), 0); f.host.next(); TransportMixFixture.error(op, "deadline_exceeded");
        check(f.adapter.tasks.isEmpty(), "expired controller queue work never dispatches native reads");
        Fixture closed = new Fixture(); op = closed.start(target()); closed.host.services.close(); closed.adapter.next();
        TransportMixFixture.error(op, "unavailable"); check(closed.adapter.reads == 0, "extension close invalidates queued document work");
        Fixture late = new Fixture(); op = late.start(target()); late.adapter.next(); op.result.cancel(false); late.host.next();
        check(op.result.isCancelled(), "late result cannot overwrite cancellation");
    }
    private static void independentConcurrentReadsDoNotShareTargets() {
        Fixture f = new Fixture(); JsonObject first = target(), second = target();
        first.addProperty("moduleOffset", 1); second.addProperty("moduleOffset", 2);
        HostOperation a = f.start(first), b = f.start(second);
        f.adapter.tasks.removeLast().run(); f.adapter.next(); f.host.next(); f.host.next();
        check(a.result.join().get("offset").getAsInt() == 1 && b.result.join().get("offset").getAsInt() == 2,
                "out-of-order document completion remains correlated to each operation");
    }
    private static void noMutableResultsCrossThreadBoundary() {
        Fixture f = new Fixture(); HostOperation op = f.start(target()); f.adapter.next();
        f.adapter.last.addProperty("offset", 999); f.host.next();
        check(op.result.join().get("offset").getAsInt() == 0, "serialized snapshot detached from mutable document JSON before callback");
        Fixture errors = new Fixture(); errors.adapter.fail = true; op = errors.start(target()); errors.adapter.next();
        TransportMixFixture.error(op, "not_grid"); check(errors.host.tasks.isEmpty(), "nonGrid error does not deliver a fake empty graph");
    }
    private static void boundsTransportAndPreservesNulls() {
        JsonObject value = new JsonObject(); value.add("unavailable", JsonNull.INSTANCE);
        check(BoundedJson.serialize(value, 240000, "grid_bounds_exceeded", "oversize").contains("null"), "unsupported null fields survive immutable handoff");
        value.addProperty("large", "x".repeat(240000));
        try { BoundedJson.serialize(value, 240000, "grid_bounds_exceeded", "oversize"); throw new AssertionError("oversized graph escaped bound"); }
        catch (RpcException error) { check(error.code.equals("grid_bounds_exceeded"), "oversize asks caller to reduce page instead of truncating"); }
    }
    private static void loadedSourceGateFailsClosed() throws Exception {
        GridAdapter wrongVersion = new Bitwig613GridAdapter(new Bitwig613Runtime(new Object(), "6.1.4", () -> {}).access("grid_unavailable"));
        check(!wrongVersion.capabilities().get("gatePassed").getAsBoolean(), "unsupported version fails before any native binding");
        GridAdapter noSource = new Bitwig613GridAdapter(new Bitwig613Runtime(new Object(), "6.1.3", () -> {}).access("grid_unavailable"));
        check(!noSource.capabilities().get("gatePassed").getAsBoolean(), "missing loaded CodeSource cannot fall back to arbitrary installed JAR");
        check(!noSource.capabilities().getAsJsonObject("nativeOwningTrack").get("available").getAsBoolean()
                && !noSource.capabilities().getAsJsonObject("structure").get("available").getAsBoolean(),
                "unverified identity bindings cannot advertise usable native ownership or structure");
        check(!noSource.capabilities().getAsJsonObject("moduleMove").get("available").getAsBoolean()
                && !noSource.capabilities().getAsJsonObject("moduleDelete").get("available").getAsBoolean()
                && !noSource.capabilities().getAsJsonObject("connectionWrite").get("available").getAsBoolean()
                && !noSource.capabilities().getAsJsonObject("moduleInsert").get("available").getAsBoolean()
                && !noSource.capabilities().getAsJsonObject("moduleInsert").has("bindingIdentity"),
                "unverified source cannot advertise any native topology capability");
        try {
            noSource.snapshot(new Object(), UUID.fromString(TransportMixFixture.A), 0, 64, true, () -> {});
            throw new AssertionError("snapshot escaped identity gate");
        } catch (RpcException error) { check(error.code.equals("grid_unavailable"), "snapshot requires exact verified identity gate"); }
        try { noSource.execute(() -> { throw new AssertionError("read escaped gate"); }); throw new AssertionError("expected gate failure"); }
        catch (RpcException error) { check(error.code.equals("grid_unavailable"), "failed gate returns actionable unavailable"); }
        Path file = Files.createTempFile("grid-hash-test-", ".bin");
        try {
            Files.writeString(file, "abc", StandardCharsets.UTF_8);
            check(Bitwig613Runtime.hash(file, () -> {}).equals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"), "hash reads bytes not filename or version text");
            try { Bitwig613Runtime.hash(file, () -> { throw HostServices.failure("cancelled", "cancel hashing"); }); throw new AssertionError("hash ignored cancellation"); }
            catch (RpcException error) { check(error.code.equals("cancelled"), "gate hashing observes request cancellation"); }
        } finally { Files.deleteIfExists(file); }
    }
    private static void parameterInspectionRejectsInvalidTargetsAndQueuedRaces() {
        Fixture f = new Fixture();
        for (String field : List.of("trackId", "deviceIndex", "expectedDeviceName", "moduleId", "parameterId")) {
            JsonObject p = inspectionTarget(); p.remove(field);
            TransportMixFixture.error(f.host.submit(f.module, "grid.parameters.get", p), "invalid_params");
            p = inspectionTarget(); p.add(field, JsonNull.INSTANCE);
            TransportMixFixture.error(f.host.submit(f.module, "grid.parameters.get", p), "invalid_params");
        }
        for (JsonElement invalid : List.of(new JsonPrimitive(3), new JsonPrimitive(true),
                new JsonPrimitive("x".repeat(1025)), new JsonArray())) {
            JsonObject p = inspectionTarget(); p.add("parameterId", invalid);
            TransportMixFixture.error(f.host.submit(f.module, "grid.parameters.get", p), "invalid_params");
        }
        for (String id : List.of("1-1-1-1-1", "00000000-0000-0000-0000-00000000000g", "")) {
            JsonObject p = inspectionTarget(); p.addProperty("moduleId", id);
            TransportMixFixture.error(f.host.submit(f.module, "grid.parameters.get", p), "invalid_params");
        }
        JsonObject p = inspectionTarget(); p.addProperty("includeParameters", false);
        TransportMixFixture.error(f.host.submit(f.module, "grid.parameters.get", p), "invalid_params");
        p = inspectionTarget(); p.addProperty("deviceIndex", true);
        TransportMixFixture.error(f.host.submit(f.module, "grid.parameters.get", p), "invalid_params");
        String supplementary = new String(Character.toChars(0x1D11E));
        p = inspectionTarget(); p.addProperty("parameterId", supplementary.repeat(513));
        TransportMixFixture.error(f.host.submit(f.module, "grid.parameters.get", p), "invalid_params");
        p.addProperty("parameterId", supplementary.repeat(512));
        GridModule.validateParams("grid.parameters.get", p);
        p.addProperty("parameterId", ""); p.addProperty("moduleId", "ABCDEFAB-ABCD-ABCD-ABCD-ABCDEFABCDEF");
        GridModule.validateParams("grid.parameters.get", p);
        check(f.host.tasks.isEmpty() && f.adapter.tasks.isEmpty(), "invalid inspection requests never enter either queue");
        HostOperation cancelled = f.host.submit(f.module, "grid.parameters.get", inspectionTarget()); f.host.next();
        cancelled.result.cancel(false); f.adapter.next();
        check(f.adapter.reads == 0, "cancelled inspection does not read native context");
        HostOperation changed = f.host.submit(f.module, "grid.parameters.get", inspectionTarget()); f.host.next();
        f.device.child("name").emit("Other"); f.device.child("name").emit("Poly Grid"); f.adapter.next();
        TransportMixFixture.error(changed, "stale_context");
        check(f.adapter.reads == 0, "inspection rejects public target epoch changes before context capture");
        HostOperation late = f.host.submit(f.module, "grid.parameters.get", inspectionTarget()); f.host.next(); f.adapter.next();
        f.host.tracks[0].child("channelId").emit(TransportMixFixture.B); f.host.next();
        TransportMixFixture.error(late, "stale_context");
    }

    private static JsonObject writeTarget() {
        JsonObject result = inspectionTarget();
        result.addProperty("expectedKind", "boolean"); result.addProperty("expectedValue", true); result.addProperty("value", false);
        return result;
    }

    private static void writesRequireStrictObservedStoppedInputs() {
        Fixture f = new Fixture();
        for (String field : List.of("expectedKind", "expectedValue", "value")) {
            JsonObject p = writeTarget(); p.remove(field);
            TransportMixFixture.error(f.host.submit(f.module, "grid.parameters.set", p), "invalid_params");
        }
        JsonObject invalid = writeTarget(); invalid.addProperty("value", 0);
        TransportMixFixture.error(f.host.submit(f.module, "grid.parameters.set", invalid), "invalid_params");
        check(f.host.tasks.isEmpty() && f.adapter.tasks.isEmpty(), "bad raw values are rejected before scheduling");
        Fixture missing = new Fixture(true, false);
        for (String key : List.of("isArrangerRecordEnabled", "isArrangerAutomationWriteEnabled", "isClipLauncherAutomationWriteEnabled"))
            missing.host.transport.child(key).emit(false);
        TransportMixFixture.error(missing.startWrite(), "not_ready");
        check(missing.adapter.tasks.isEmpty(), "default false getter is not an observed stopped state");
        for (String key : List.of("isPlaying", "isArrangerRecordEnabled", "isArrangerAutomationWriteEnabled", "isClipLauncherAutomationWriteEnabled")) {
            Fixture unsafe = new Fixture(); unsafe.host.transport.child(key).emit(true);
            TransportMixFixture.error(unsafe.startWrite(), "unsafe_state");
            check(unsafe.adapter.writer.calls == 0 && unsafe.adapter.tasks.isEmpty(), "active observed transport mode blocks native scheduling");
        }
    }

    private static void stringWriteSchemaRejectsBeforeScheduling() {
        Fixture f = new Fixture();
        JsonObject stringWrite = writeTarget();
        stringWrite.addProperty("expectedKind", "string");
        stringWrite.addProperty("expectedValue", "before"); stringWrite.addProperty("value", "after");
        TransportMixFixture.error(f.host.submit(f.module, "grid.parameters.set", stringWrite), "invalid_params");
        for (String field : List.of("expectedValue", "value")) {
            JsonObject scalarWrite = writeTarget(); scalarWrite.addProperty(field, "true");
            TransportMixFixture.error(f.host.submit(f.module, "grid.parameters.set", scalarWrite), "invalid_params");
        }
        check(f.host.tasks.isEmpty() && f.adapter.tasks.isEmpty() && f.adapter.writer.calls == 0,
                "readable string family does not widen scalar write kind/value schema or schedule native work");
    }

    private static void queuedWritesRejectSafetyAndTargetTransitions() {
        Fixture controller = new Fixture();
        HostOperation op = controller.host.submit(controller.module, "grid.parameters.set", writeTarget());
        controller.host.transport.child("isPlaying").emit(true); controller.host.transport.child("isPlaying").emit(false);
        controller.host.next();
        check(TransportMixFixture.error(op, "stale_context").outcome.equals("not_applied")
                && controller.adapter.tasks.isEmpty(), "safety ABA in initial controller queue is rejected");
        for (String key : List.of("isPlaying", "isArrangerRecordEnabled", "isArrangerAutomationWriteEnabled", "isClipLauncherAutomationWriteEnabled")) {
            Fixture queued = new Fixture(); op = queued.startWrite();
            queued.host.transport.child(key).emit(true); queued.host.transport.child(key).emit(false);
            queued.adapter.next();
            check(TransportMixFixture.error(op, "stale_context").outcome.equals("not_applied")
                    && queued.adapter.writer.calls == 0 && !op.dispatched, "safety ABA in document queue prevents invocation");
        }
        Fixture changed = new Fixture(); op = changed.startWrite();
        changed.device.child("name").emit("Other"); changed.device.child("name").emit("Poly Grid"); changed.adapter.next();
        check(TransportMixFixture.error(op, "stale_context").outcome.equals("not_applied")
                && changed.adapter.writer.calls == 0, "target ABA before mutation is not-applied");
        Fixture immediate = new Fixture(); op = immediate.startWrite();
        immediate.adapter.beforeWrite = () -> immediate.host.transport.child("isPlaying").emit(true);
        immediate.adapter.next();
        check(TransportMixFixture.error(op, "stale_context").outcome.equals("not_applied")
                && immediate.adapter.writer.calls == 0, "changed safety at writer entry is rechecked, not cached permission");
        Fixture cancelled = new Fixture(); op = cancelled.startWrite(); op.result.cancel(false); cancelled.adapter.next();
        check(cancelled.adapter.writer.calls == 0 && !op.dispatched, "cancelled native queue entry does not invoke setter");
    }

    private static void nativeCallbacksNeverReadControllerSafetyGetters() throws Exception {
        Fixture f = new Fixture(); HostOperation op = f.startWrite();
        for (String key : List.of("isPlaying", "isArrangerRecordEnabled", "isArrangerAutomationWriteEnabled", "isClipLauncherAutomationWriteEnabled"))
            f.host.transport.child(key).custom = (name, args) -> {
                if (name.equals("get") && !f.host.onHost) throw new AssertionError("Controller getter on document thread");
                return null;
            };
        Thread document = new Thread(f.adapter::next); document.start(); document.join();
        check(op.dispatched && !op.result.isDone() && f.adapter.writer.calls == 1, "native callback mutates but awaits controller delivery");
        f.host.next();
        check(op.result.join().getAsJsonObject("parameter").get("value").getAsBoolean() == false,
                "published safety observations support document-thread mutation without Controller API getters");
    }

    private static void everyPostDispatchBoundaryKeepsUnknownOutcome() {
        Fixture nativeFailure = new Fixture();
        nativeFailure.adapter.writer.afterMutation = () -> { throw HostServices.failure("grid_unavailable", "native partial failure"); };
        HostOperation op = nativeFailure.startWrite(); nativeFailure.adapter.next();
        check(TransportMixFixture.error(op, "grid_unavailable").outcome.equals("unknown")
                && nativeFailure.adapter.writer.calls == 1, "document callback normalizes partial native failures without retry");
        Fixture linkage = new Fixture();
        linkage.adapter.writer.afterMutation = () -> { throw new LinkageError("late native linkage failure"); };
        op = linkage.startWrite(); linkage.adapter.next();
        check(TransportMixFixture.error(op, "grid_unavailable").outcome.equals("unknown")
                && linkage.adapter.writer.calls == 1, "native Error completes uncertain result instead of escaping the callback");
        Fixture serialization = new Fixture();
        serialization.adapter.afterWrite = () -> serialization.adapter.last.addProperty("oversized", "x".repeat(240000));
        op = serialization.startWrite(); serialization.adapter.next();
        check(TransportMixFixture.error(op, "grid_bounds_exceeded").outcome.equals("unknown"),
                "serialization failure after mutation cannot become not-applied");
        Fixture target = new Fixture(); op = target.startWrite(); target.adapter.next();
        target.device.child("position").emit(1); target.host.next();
        check(TransportMixFixture.error(op, "stale_context").outcome.equals("unknown"), "late public target failure remains unknown");
        Fixture safety = new Fixture(); op = safety.startWrite(); safety.adapter.next();
        safety.host.transport.child("isPlaying").emit(true); safety.host.transport.child("isPlaying").emit(false); safety.host.next();
        check(TransportMixFixture.error(op, "stale_context").outcome.equals("unknown"), "post-mutation safety ABA cannot erase dispatched state");
        Fixture closed = new Fixture(); op = closed.startWrite();
        closed.adapter.writer.afterMutation = closed.host.services::close;
        closed.adapter.next();
        check(TransportMixFixture.error(op, "unavailable").outcome.equals("unknown"), "extension close during invocation preserves unknown");
        Fixture late = new Fixture(); op = late.startWrite(); late.adapter.next();
        op.result.cancel(false); late.host.next();
        check(op.result.isCancelled() && op.dispatched && late.adapter.writer.calls == 1, "late cancellation does not claim native rollback");
        Fixture noop = new Fixture(); JsonObject p = writeTarget(); p.addProperty("value", true);
        op = noop.host.submit(noop.module, "grid.parameters.set", p); noop.host.next(); noop.adapter.next();
        noop.device.child("position").emit(1); noop.host.next();
        check(TransportMixFixture.error(op, "stale_context").outcome.equals("not_applied")
                && !op.dispatched && noop.adapter.writer.calls == 0, "late error after no-op does not invent a dispatched mutation");
        HostOperation expired = new HostOperation(0); expired.dispatched = true;
        try { noop.host.services.check(expired); throw new AssertionError("expected deadline failure"); }
        catch (RpcException error) { expired.fail(error); }
        check(TransportMixFixture.error(expired, "deadline_exceeded").outcome.equals("unknown"), "deadline after dispatch preserves uncertainty");
    }
    private static JsonObject moduleTarget(boolean move) {
        JsonObject p = inspectionTarget(); p.remove("parameterId");
        if (move) {
            p.addProperty("expectedX", 7); p.addProperty("expectedY", 3);
            p.addProperty("x", 10); p.addProperty("y", 3);
        }
        return p;
    }
    private static void topologyArgumentsRejectBeforeScheduling() {
        Fixture f = new Fixture();
        for (String method : List.of("grid.modules.move", "grid.modules.delete")) {
            boolean move = method.endsWith("move");
            for (String key : moduleTarget(move).keySet()) {
                JsonObject p = moduleTarget(move); p.remove(key);
                TransportMixFixture.error(f.host.submit(f.module, method, p), "invalid_params");
            }
            JsonObject extra = moduleTarget(move); extra.addProperty("copy", false);
            TransportMixFixture.error(f.host.submit(f.module, method, extra), "invalid_params");
            JsonObject stale = moduleTarget(move); stale.addProperty("moduleId", "1-1-1-1-1");
            TransportMixFixture.error(f.host.submit(f.module, method, stale), "invalid_params");
        }
        for (String key : List.of("expectedX", "expectedY", "x", "y")) {
            for (String token : List.of("true", "\"1\"", "null", "1.0", "1e0", "2147483648", "-2147483649")) {
                JsonObject p = moduleTarget(true); p.add(key, JsonParser.parseString(token));
                TransportMixFixture.error(f.host.submit(f.module, "grid.modules.move", p), "invalid_params");
            }
        }
        check(f.host.tasks.isEmpty() && f.adapter.tasks.isEmpty() && f.adapter.topology.calls == 0,
                "invalid topology arguments never reach controller or native document queue");
    }
    private static void topologyOperationsRequireLaterDocumentObservation() {
        for (boolean move : List.of(true, false)) {
            Fixture f = new Fixture();
            HostOperation op = f.host.submit(f.module, move ? "grid.modules.move" : "grid.modules.delete", moduleTarget(move));
            f.host.next();
            check(!op.dispatched && !op.result.isDone(), "queued topology request has not invoked native operation");
            f.adapter.next();
            check(op.dispatched && !op.result.isDone() && f.host.tasks.isEmpty() && f.adapter.topology.calls == 1,
                    "native return does not deliver topology result before later document event");
            f.adapter.next();
            check(!op.result.isDone(), "later document observation still requires controller handoff");
            f.host.next();
            JsonObject result = op.result.join();
            check(result.get("mutationDispatched").getAsBoolean() && result.has("target") && result.has("graph"),
                    "observed topology result includes guarded target and bounded graph");
        }
    }
    private static void topologySafetyAndTargetsGuardEveryQueueBoundary() {
        for (boolean move : List.of(true, false)) {
            String method = move ? "grid.modules.move" : "grid.modules.delete";
            Fixture notStopped = new Fixture();
            notStopped.host.transport.child("isPlaying").emit(true);
            HostOperation early = notStopped.host.submit(notStopped.module, method, moduleTarget(move));
            notStopped.host.next(); TransportMixFixture.error(early, "unsafe_state");
            check(notStopped.adapter.topology.calls == 0 && !early.dispatched, "topology requires observed stopped safety before native queue");
            for (int boundary = 0; boundary < 3; boundary++) {
                Fixture f = new Fixture();
                HostOperation op = f.host.submit(f.module, method, moduleTarget(move)); f.host.next();
                if (boundary > 0) f.adapter.next();
                if (boundary > 1) f.adapter.next();
                f.host.transport.child("isPlaying").emit(true);
                f.host.transport.child("isPlaying").emit(false);
                if (boundary < 2) f.adapter.next(); else f.host.next();
                check(TransportMixFixture.error(op, "stale_context").outcome.equals(boundary == 0 ? "not_applied" : "unknown")
                        && f.adapter.topology.calls == (boundary == 0 ? 0 : 1),
                        "safety epoch change across native queue, later observation or controller delivery never retries");
            }
            Fixture target = new Fixture();
            HostOperation op = target.host.submit(target.module, method, moduleTarget(move)); target.host.next();
            target.device.child("name").emit("Other"); target.device.child("name").emit("Poly Grid");
            target.adapter.next();
            check(TransportMixFixture.error(op, "stale_context").outcome.equals("not_applied")
                    && target.adapter.topology.calls == 0, "observed target ABA blocks topology before native dispatch");
        }
    }

    private static JsonObject connectionTarget() {
        JsonObject p = target(); p.addProperty("targetModuleId", new UUID(1, 1).toString());
        p.addProperty("targetPortKey", "in"); p.add("expectedSource", JsonNull.INSTANCE);
        JsonObject source = new JsonObject(); source.addProperty("moduleId", new UUID(1, 1).toString());
        source.addProperty("portKey", "out"); p.add("source", source); return p;
    }
    private static void connectionArgumentsPreserveRequiredNullSemantics() {
        Fixture f = new Fixture();
        for (String key : connectionTarget().keySet()) {
            JsonObject p = connectionTarget(); p.remove(key);
            TransportMixFixture.error(f.host.submit(f.module, "grid.connections.set", p), "invalid_params");
        }
        for (String field : List.of("expectedSource", "source")) {
            for (String invalid : List.of("false", "1", "\"out\"", "[]", "{}", "{\"moduleId\":\"1-1-1-1-1\",\"portKey\":\"out\"}",
                    "{\"moduleId\":null,\"portKey\":\"out\"}", "{\"moduleId\":1,\"portKey\":\"out\"}",
                    "{\"moduleId\":\"00000000-0000-0001-0000-000000000001\"}",
                    "{\"portKey\":\"out\"}", "{\"moduleId\":\"00000000-0000-0001-0000-000000000001\",\"portKey\":false}",
                    "{\"moduleId\":\"00000000-0000-0001-0000-000000000001\",\"portKey\":null}",
                    "{\"moduleId\":\"00000000-0000-0001-0000-000000000001\",\"portKey\":\"out\",\"extra\":true}")) {
                JsonObject p = connectionTarget(); p.add(field, JsonParser.parseString(invalid));
                TransportMixFixture.error(f.host.submit(f.module, "grid.connections.set", p), "invalid_params");
            }
            JsonObject p = connectionTarget(); JsonObject endpoint = p.getAsJsonObject("source").deepCopy();
            endpoint.addProperty("portKey", "x".repeat(1025)); p.add(field, endpoint);
            TransportMixFixture.error(f.host.submit(f.module, "grid.connections.set", p), "invalid_params");
            endpoint.addProperty("portKey", ""); GridModule.validateParams("grid.connections.set", p);
            endpoint.addProperty("portKey", new String(Character.toChars(0x1D11E)).repeat(512));
            GridModule.validateParams("grid.connections.set", p);
            endpoint.addProperty("portKey", new String(Character.toChars(0x1D11E)).repeat(513));
            TransportMixFixture.error(f.host.submit(f.module, "grid.connections.set", p), "invalid_params");
        }
        for (String field : List.of("targetModuleId", "targetPortKey", "trackId", "deviceIndex", "expectedDeviceName")) {
            JsonObject p = connectionTarget(); p.add(field, JsonNull.INSTANCE);
            TransportMixFixture.error(f.host.submit(f.module, "grid.connections.set", p), "invalid_params");
        }
        JsonObject extra = connectionTarget(); extra.addProperty("retry", true);
        TransportMixFixture.error(f.host.submit(f.module, "grid.connections.set", extra), "invalid_params");
        JsonObject abbreviated = connectionTarget(); abbreviated.addProperty("targetModuleId", "1-1-1-1-1");
        TransportMixFixture.error(f.host.submit(f.module, "grid.connections.set", abbreviated), "invalid_params");
        for (JsonElement key : List.of(new JsonPrimitive(false), new JsonPrimitive(1), new JsonPrimitive("x".repeat(1025)), new JsonArray())) {
            JsonObject p = connectionTarget(); p.add("targetPortKey", key);
            TransportMixFixture.error(f.host.submit(f.module, "grid.connections.set", p), "invalid_params");
        }
        JsonObject valid = connectionTarget(); valid.addProperty("targetPortKey", "");
        valid.add("source", JsonNull.INSTANCE); GridModule.validateParams("grid.connections.set", valid);
        check(f.host.tasks.isEmpty() && f.adapter.tasks.isEmpty() && f.adapter.connections.calls == 0,
                "strict nested validation rejects before scheduling; explicit required null and empty local keys remain valid");
    }
    private static void connectionResultsWaitForBothQueues() {
        Fixture f = new Fixture();
        HostOperation op = f.host.submit(f.module, "grid.connections.set", connectionTarget()); f.host.next();
        check(!op.dispatched && !op.result.isDone(), "cable controller dispatch is not native invocation");
        f.adapter.next();
        check(op.dispatched && !op.result.isDone() && f.host.tasks.isEmpty() && f.adapter.connections.calls == 1,
                "setter return queues later document observation, never immediate completion");
        f.adapter.next(); check(!op.result.isDone(), "later observation still waits for controller target recheck");
        f.host.next(); JsonObject result = op.result.join();
        check(result.get("matchesRequested").getAsBoolean() && result.has("target")
                && result.get("previousSource").isJsonNull() && result.getAsJsonObject("graph").has("connections"),
                "serialized result preserves nullable previous source and full bounded graph");
        Fixture noop = new Fixture(); JsonObject p = connectionTarget(); p.add("source", JsonNull.INSTANCE);
        op = noop.host.submit(noop.module, "grid.connections.set", p); noop.host.next(); noop.adapter.next();
        noop.adapter.next(); noop.host.next(); result = op.result.join();
        check(!op.dispatched && noop.adapter.connections.calls == 0 && result.get("actualSource").isJsonNull()
                && result.get("requestedSource").isJsonNull() && result.get("previousSource").isJsonNull(),
                "explicit null no-op crosses serialized handoff without omission or mutation");
    }
    private static void connectionSafetyTargetsAndCancellationGuardQueues() {
        for (String flag : List.of("isPlaying", "isArrangerRecordEnabled", "isArrangerAutomationWriteEnabled", "isClipLauncherAutomationWriteEnabled")) {
            Fixture f = new Fixture(); f.host.transport.child(flag).emit(true);
            HostOperation op = f.host.submit(f.module, "grid.connections.set", connectionTarget()); f.host.next();
            check(TransportMixFixture.error(op, "unsafe_state").outcome.equals("not_applied") && f.adapter.connections.calls == 0,
                    "every stopped/recording/automation prerequisite blocks cable dispatch");
        }
        Fixture unobserved = new Fixture(true, false);
        HostOperation pending = unobserved.host.submit(unobserved.module, "grid.connections.set", connectionTarget()); unobserved.host.next();
        TransportMixFixture.error(pending, "not_ready");
        for (int boundary = 0; boundary < 3; boundary++) {
            for (boolean safety : List.of(false, true)) {
                Fixture f = new Fixture();
                HostOperation op = f.host.submit(f.module, "grid.connections.set", connectionTarget()); f.host.next();
                if (boundary > 0) f.adapter.next();
                if (boundary > 1) f.adapter.next();
                if (safety) {
                    f.host.transport.child("isPlaying").emit(true); f.host.transport.child("isPlaying").emit(false);
                } else {
                    f.device.child("name").emit("Other"); f.device.child("name").emit("Poly Grid");
                }
                if (boundary < 2) f.adapter.next(); else f.host.next();
                check(TransportMixFixture.error(op, "stale_context").outcome.equals(boundary == 0 ? "not_applied" : "unknown")
                        && f.adapter.connections.calls == (boundary == 0 ? 0 : 1), "cable safety/target ABA guards all three queue boundaries");
            }
            Fixture cancelled = new Fixture();
            HostOperation op = cancelled.host.submit(cancelled.module, "grid.connections.set", connectionTarget()); cancelled.host.next();
            if (boundary > 0) cancelled.adapter.next();
            if (boundary > 1) cancelled.adapter.next();
            op.result.cancel(false);
            if (boundary < 2) cancelled.adapter.next(); else cancelled.host.next();
            check(op.result.isCancelled() && cancelled.adapter.connections.calls == (boundary == 0 ? 0 : 1),
                    "cancelled cable work cannot dispatch late, overwrite cancellation or compensate");
        }
        Fixture expired = new Fixture();
        HostOperation op = expired.host.submit(expired.module, "grid.connections.set", connectionTarget(), 0); expired.host.next();
        check(TransportMixFixture.error(op, "deadline_exceeded").outcome.equals("not_applied") && expired.adapter.tasks.isEmpty(),
                "expired cable request never enters native queue");
        Fixture closed = new Fixture();
        op = closed.host.submit(closed.module, "grid.connections.set", connectionTarget()); closed.host.next(); closed.adapter.next();
        closed.host.services.close(); closed.adapter.next();
        check(TransportMixFixture.error(op, "unavailable").outcome.equals("unknown") && closed.adapter.connections.calls == 1,
                "close after setter return cannot convert abandoned observation into success or rollback");
    }

    private static JsonObject insertionTarget() {
        JsonObject p = target(); p.addProperty("typeId", GridInsertionWriterTest.TYPE.toString());
        p.addProperty("x", 8); p.addProperty("y", 3); return p;
    }
    private static void insertionArguments() {
        Fixture f = new Fixture();
        for (String key : List.of("trackId", "deviceIndex", "expectedDeviceName", "typeId", "x", "y")) {
            JsonObject p = insertionTarget(); p.remove(key);
            TransportMixFixture.error(f.host.submit(f.module, "grid.modules.insert", p), "invalid_params");
        }
        for (String key : List.of("x", "y"))
            for (String token : List.of("true", "\"1\"", "null", "1.0", "1e0", "2147483648", "-2147483649")) {
                JsonObject p = insertionTarget(); p.add(key, JsonParser.parseString(token));
                TransportMixFixture.error(f.host.submit(f.module, "grid.modules.insert", p), "invalid_params");
            }
        for (String extra : List.of("moduleId", "path", "expectedX", "copy", "retry")) {
            JsonObject p = insertionTarget(); p.addProperty(extra, "forbidden");
            TransportMixFixture.error(f.host.submit(f.module, "grid.modules.insert", p), "invalid_params");
        }
        for (String bad : List.of("1-1-1-1-1", "", "native/resource.bwmodule")) {
            JsonObject p = insertionTarget(); p.addProperty("typeId", bad);
            TransportMixFixture.error(f.host.submit(f.module, "grid.modules.insert", p), "invalid_params");
        }
        JsonObject valid = insertionTarget(); valid.addProperty("x", Integer.MIN_VALUE); valid.addProperty("y", Integer.MAX_VALUE);
        HostBridge.validate("grid.modules.insert", valid);
        check(f.host.tasks.isEmpty() && f.adapter.tasks.isEmpty() && f.adapter.insertion.loads == 0,
                "strict insertion shape rejects before any queue/native load");
    }
    private static void insertionWaitsForNativeSettlementAndControllerDelivery() {
        Fixture f = new Fixture();
        HostOperation op = f.host.submit(f.module, "grid.modules.insert", insertionTarget()); f.host.next();
        f.adapter.next();
        check(op.dispatched && f.adapter.insertion.loads == 1 && !op.result.isDone() && f.host.tasks.isEmpty(),
                "loader dispatch has its own native lifetime, not controller completion");
        for (String key : List.of("isPlaying", "isArrangerRecordEnabled", "isArrangerAutomationWriteEnabled", "isClipLauncherAutomationWriteEnabled"))
            f.host.transport.child(key).custom = (name, args) -> {
                if (name.equals("get") && !f.host.onHost) throw new AssertionError("Controller getter in native callback");
                return null;
            };
        f.adapter.insertion.deliver();
        check(f.adapter.insertion.placements == 1 && !op.result.isDone(), "preplacement callback uses published safety only");
        f.adapter.next(); f.adapter.insertion.guard.requireAvailable();
        check(!op.result.isDone() && f.host.tasks.size() == 1, "healthy native settlement releases independently before controller delivery");
        f.host.next();
        JsonObject result = op.result.join();
        check(result.getAsJsonObject("target").get("trackId").getAsString().equals(TransportMixFixture.A)
                && result.get("loaderDispatched").getAsBoolean() && result.get("placementDispatched").getAsBoolean(),
                "actual insertion result receives existing explicit target envelope");
    }
    private static void insertionSafetyCancellationAndCloseKeepNativeLifetimeIndependent() {
        for (String key : List.of("isPlaying", "isArrangerRecordEnabled", "isArrangerAutomationWriteEnabled", "isClipLauncherAutomationWriteEnabled")) {
            Fixture f = new Fixture(); f.host.transport.child(key).emit(true);
            HostOperation op = f.host.submit(f.module, "grid.modules.insert", insertionTarget()); f.host.next();
            check(TransportMixFixture.error(op, "unsafe_state").outcome.equals("not_applied") && f.adapter.insertion.loads == 0,
                    "observed unsafe transport blocks insertion before native loading");
        }
        for (int change = 0; change < 4; change++) {
            Fixture f = new Fixture(); HostOperation op = f.host.submit(f.module, "grid.modules.insert", insertionTarget());
            f.host.next(); f.adapter.next();
            if (change == 0) { f.host.transport.child("isPlaying").emit(true); f.host.transport.child("isPlaying").emit(false); }
            if (change == 1) { f.device.child("name").emit("Other"); f.device.child("name").emit("Poly Grid"); }
            if (change == 2) op.result.cancel(false);
            if (change == 3) f.host.services.close();
            f.adapter.insertion.deliver();
            check(f.adapter.insertion.cleanups == 1 && f.adapter.insertion.placements == 0, "late public epoch/cancel/close rejects via delivered cleanup");
            f.adapter.next(); f.adapter.insertion.guard.requireAvailable();
            check(op.result.isCompletedExceptionally() && f.host.tasks.isEmpty(), "native settlement is not skipped by controller schedule/future/extension close");
            if (change < 2) check(TransportMixFixture.error(op, "stale_context").outcome.equals("unknown"), "loader dispatch preserves uncertainty after public ABA");
        }
        Fixture late = new Fixture(); HostOperation op = late.host.submit(late.module, "grid.modules.insert", insertionTarget());
        late.host.next(); late.adapter.next(); late.adapter.insertion.deliver(); late.adapter.next();
        late.device.child("position").emit(1); late.host.next(); late.adapter.insertion.guard.requireAvailable();
        check(TransportMixFixture.error(op, "stale_context").outcome.equals("unknown") && late.adapter.insertion.placements == 1,
                "post-settlement controller rejection never resurrects native pending lifetime");
    }

    private static JsonObject renameTarget(String expected, String name) {
        JsonObject p = target(); p.addProperty("moduleId", "00000000-0000-0001-0000-000000000001");
        p.addProperty("expectedCustomName", expected); p.addProperty("customName", name); return p;
    }
    private static void renameArgumentsRejectBeforeQueues() {
        Fixture f = new Fixture();
        for (String key : List.of("trackId", "deviceIndex", "expectedDeviceName", "moduleId", "expectedCustomName", "customName")) {
            JsonObject p = renameTarget("", "Lead"); p.remove(key);
            TransportMixFixture.error(f.host.submit(f.module, "grid.modules.rename", p), "invalid_params");
            p = renameTarget("", "Lead"); p.add(key, JsonNull.INSTANCE);
            TransportMixFixture.error(f.host.submit(f.module, "grid.modules.rename", p), "invalid_params");
        }
        for (String key : List.of("expectedCustomName", "customName")) {
            for (String token : List.of("0", "1.0", "true", "[]", "{}")) {
                JsonObject p = renameTarget("", "Lead"); p.add(key, JsonParser.parseString(token));
                TransportMixFixture.error(f.host.submit(f.module, "grid.modules.rename", p), "invalid_params");
            }
            for (String text : List.of("x".repeat(1025), "𝄞".repeat(513), "x".repeat(1023) + "𝄞")) {
                JsonObject p = renameTarget("", "Lead"); p.addProperty(key, text);
                TransportMixFixture.error(f.host.submit(f.module, "grid.modules.rename", p), "invalid_params");
            }
            for (String text : List.of("", " \t音楽=α\n", "x".repeat(1024), "𝄞".repeat(512))) {
                JsonObject p = renameTarget("", "Lead"); p.addProperty(key, text);
                HostBridge.validate("grid.modules.rename", p);
            }
        }
        for (String key : List.of("trackId", "moduleId"))
            for (String id : List.of("", "selected", "1-1-1-1-1", "00000000000000010000000000000001")) {
                JsonObject p = renameTarget("", "Lead"); p.addProperty(key, id);
                TransportMixFixture.error(f.host.submit(f.module, "grid.modules.rename", p), "invalid_params");
            }
        for (String key : List.of("typeId", "name", "parameterId", "selected", "force", "includeParameters")) {
            JsonObject p = renameTarget("", "Lead"); p.addProperty(key, "forbidden");
            TransportMixFixture.error(f.host.submit(f.module, "grid.modules.rename", p), "invalid_params");
        }
        check(f.host.tasks.isEmpty() && f.adapter.tasks.isEmpty() && f.adapter.naming.calls == 0,
                "rename validates exact raw string fields and canonical identities before scheduling");
    }
    private static void renameUsesRealWriterAndBothDeliveryQueues() {
        Fixture f = new Fixture(); String raw = " \tLead = 音楽𝄞\n";
        HostOperation op = f.host.submit(f.module, "grid.modules.rename", renameTarget("", raw)); f.host.next();
        for (String key : List.of("isPlaying", "isArrangerRecordEnabled", "isArrangerAutomationWriteEnabled", "isClipLauncherAutomationWriteEnabled"))
            f.host.transport.child(key).custom = (name, args) -> {
                if (name.equals("get") && !f.host.onHost) throw new AssertionError("Controller getter in name document callback");
                return null;
            };
        f.adapter.next();
        check(op.dispatched && f.adapter.naming.calls == 1 && !op.result.isDone() && f.host.tasks.isEmpty(),
                "real native name setter does not complete before later document observation");
        f.adapter.next();
        check(!op.result.isDone() && f.host.tasks.size() == 1, "observed name waits for controller target and safety delivery");
        f.host.next(); JsonObject result = op.result.join();
        check(result.get("previousCustomName").getAsString().equals("")
                && result.get("requestedCustomName").getAsString().equals(raw)
                && result.get("actualCustomName").getAsString().equals(raw)
                && result.get("previousName").getAsString().equals("Union")
                && result.getAsJsonObject("target").get("deviceIndex").getAsInt() == 0,
                "real writer raw/effective observations and explicit target survive immutable delivery");
        HostOperation reset = f.host.submit(f.module, "grid.modules.rename", renameTarget(raw, ""));
        f.host.next(); f.adapter.next(); f.adapter.next(); f.host.next();
        check(reset.result.join().get("actualCustomName").getAsString().isEmpty() && f.adapter.naming.calls == 2,
                "empty reset passes actual raw CAS and native semantics without type-name substitution");
        HostOperation stale = f.host.submit(f.module, "grid.modules.rename", renameTarget("Union", ""));
        f.host.next(); f.adapter.next();
        check(TransportMixFixture.error(stale, "stale_context").outcome.equals("not_applied") && f.adapter.naming.calls == 2,
                "effective name cannot authorize even an otherwise matching raw no-op");
        HostOperation noop = f.host.submit(f.module, "grid.modules.rename", renameTarget("", ""));
        f.host.next(); f.adapter.next();
        check(!noop.dispatched && f.adapter.naming.calls == 2, "authorized no-op never calls native name setter");
        while (!f.adapter.tasks.isEmpty()) f.adapter.next();
        f.host.next();
        check(!noop.result.join().get("mutationDispatched").getAsBoolean(), "no-op observation never invents native mutation");
    }
    private static void renameSafetyAndTargetEpochsGuardEveryBoundary() {
        for (String key : List.of("isPlaying", "isArrangerRecordEnabled", "isArrangerAutomationWriteEnabled", "isClipLauncherAutomationWriteEnabled")) {
            Fixture unsafe = new Fixture(); unsafe.host.transport.child(key).emit(true);
            HostOperation op = unsafe.host.submit(unsafe.module, "grid.modules.rename", renameTarget("", ""));
            unsafe.host.next();
            check(TransportMixFixture.error(op, "unsafe_state").outcome.equals("not_applied")
                    && unsafe.adapter.naming.calls == 0, "unsafe no-op still requires stopped/nonrecording/nonautomation authorization");
        }
        Fixture controller = new Fixture();
        HostOperation queued = controller.host.submit(controller.module, "grid.modules.rename", renameTarget("", "Lead"));
        controller.host.transport.child("isPlaying").emit(true); controller.host.transport.child("isPlaying").emit(false);
        controller.host.next();
        check(TransportMixFixture.error(queued, "stale_context").outcome.equals("not_applied")
                && controller.adapter.tasks.isEmpty(), "rename safety ticket rejects transitions before initial controller dispatch");
        Fixture unobserved = new Fixture(true, false);
        HostOperation unready = unobserved.host.submit(unobserved.module, "grid.modules.rename", renameTarget("", "Lead"));
        unobserved.host.next();
        check(TransportMixFixture.error(unready, "not_ready").outcome.equals("not_applied")
                && unobserved.adapter.naming.calls == 0, "unobserved safety never authorizes rename");
        for (int boundary = 0; boundary < 3; boundary++)
            for (int transition = 0; transition < 3; transition++) {
                Fixture f = new Fixture(); HostOperation op = f.host.submit(f.module, "grid.modules.rename", renameTarget("", "Lead"));
                f.host.next(); if (boundary > 0) f.adapter.next(); if (boundary > 1) f.adapter.next();
                if (transition == 0) {
                    f.host.transport.child("isPlaying").emit(true); f.host.transport.child("isPlaying").emit(false);
                } else if (transition == 1) {
                    f.device.child("name").emit("Other"); f.device.child("name").emit("Poly Grid");
                } else {
                    f.host.tracks[0].child("channelId").emit(TransportMixFixture.B);
                    f.host.tracks[0].child("channelId").emit(TransportMixFixture.A);
                }
                if (boundary < 2) f.adapter.next(); else f.host.next();
                check(TransportMixFixture.error(op, "stale_context").outcome.equals(boundary == 0 ? "not_applied" : "unknown")
                        && f.adapter.naming.calls == (boundary == 0 ? 0 : 1),
                        "name safety/target ABA at dispatch, document observation or controller delivery never retries");
            }
        Fixture immediate = new Fixture();
        immediate.adapter.naming.onSet = () -> {
            immediate.host.transport.child("isPlaying").emit(true);
            immediate.host.transport.child("isPlaying").emit(false);
        };
        HostOperation changed = immediate.host.submit(immediate.module, "grid.modules.rename", renameTarget("", "Lead"));
        immediate.host.next(); immediate.adapter.next();
        if (!immediate.adapter.tasks.isEmpty()) immediate.adapter.next();
        check(TransportMixFixture.error(changed, "stale_context").outcome.equals("unknown")
                && immediate.adapter.naming.calls == 1, "a safety transition during the native setter cannot be reported not-applied");
        Fixture noop = new Fixture(); HostOperation op = noop.host.submit(noop.module, "grid.modules.rename", renameTarget("", ""));
        noop.host.next(); noop.adapter.next(); while (!noop.adapter.tasks.isEmpty()) noop.adapter.next();
        noop.device.child("position").emit(1); noop.host.next();
        check(TransportMixFixture.error(op, "stale_context").outcome.equals("not_applied") && !op.dispatched,
                "late no-op delivery still checks explicit target without claiming a write");
    }
    private static void renameCancellationAndClosePreserveDispatchOutcome() {
        for (int boundary = 0; boundary < 3; boundary++)
            for (boolean close : List.of(false, true)) {
                Fixture f = new Fixture(); HostOperation op = f.host.submit(f.module, "grid.modules.rename", renameTarget("", "Lead"));
                f.host.next(); if (boundary > 0) f.adapter.next(); if (boundary > 1) f.adapter.next();
                if (close) f.host.services.close(); else op.result.cancel(false);
                if (boundary < 2) f.adapter.next(); else f.host.next();
                check(f.adapter.naming.calls == (boundary == 0 ? 0 : 1) && op.dispatched == (boundary > 0),
                        "cancel/close before native dispatch prevents setter; after dispatch never rolls back or retries");
                if (close) check(TransportMixFixture.error(op, "unavailable").outcome.equals(boundary == 0 ? "not_applied" : "unknown"),
                        "close preserves native rename outcome boundary");
                else check(op.result.isCancelled(), "cancelled rename cannot be completed by late callbacks");
            }
    }

    private static JsonObject inspectionTarget() {
        JsonObject result = target(); result.addProperty("moduleId", "00000000-0000-0001-0000-000000000001");
        result.addProperty("parameterId", "EXACT_ID"); return result;
    }
    private static JsonObject target() {
        JsonObject result = TransportMixFixture.target(TransportMixFixture.A);
        result.addProperty("deviceIndex", 0); result.addProperty("expectedDeviceName", "Poly Grid"); return result;
    }
    private static void check(boolean condition, String label) { assertions++; if (!condition) throw new AssertionError(label); }
    private static final class Fixture {
        final TransportMixFixture host;
        final TransportMixFixture.Node device;
        final FakeAdapter adapter = new FakeAdapter(); final GridModule module;
        Fixture() { this(true, true); }
        Fixture(boolean observed) { this(observed, true); }
        Fixture(boolean observed, boolean safety) {
            host = new TransportMixFixture(safety);
            device = host.tracks[0].child("createDeviceBank").item(0);
            device.child("exists").data = true; device.child("name").data = "Poly Grid"; device.child("position").data = 0;
            device.child("name").initial = observed; module = new GridModule(host.services, adapter);
        }
        HostOperation start(JsonObject p) { HostOperation op = host.submit(module, "grid.get", p); host.next(); return op; }
        HostOperation startWrite() { HostOperation op = host.submit(module, "grid.parameters.set", writeTarget()); host.next(); return op; }
    }
    private static final class FakeAdapter implements GridAdapter {
        final Deque<Runnable> tasks = new ArrayDeque<>(); int reads; boolean fail; JsonObject last;
        final GridSnapshotReaderTest.WriterFixture writer = new GridSnapshotReaderTest.WriterFixture();
        final GridSnapshotReaderTest.ModuleWriterFixture topology = new GridSnapshotReaderTest.ModuleWriterFixture(tasks::add);
        final GridSnapshotReaderTest.ConnectionFixture connections = new GridSnapshotReaderTest.ConnectionFixture(tasks::add);
        final GridInsertionWriterTest.Fixture insertion = new GridInsertionWriterTest.Fixture(tasks::add);
        final GridModuleNameWriterTest.Fixture naming = new GridModuleNameWriterTest.Fixture(tasks::add);
        Runnable beforeWrite = () -> {}, afterWrite = () -> {};
        void next() { tasks.removeFirst().run(); }
        @Override public void execute(Runnable task) { tasks.add(task); }
        @Override public JsonObject capabilities() { JsonObject result = new JsonObject(); result.addProperty("available", true); return result; }
        @Override public JsonObject catalog(String query, int offset, int limit, Runnable check) {
            check.run(); reads++; return result(offset, limit, false);
        }
        @Override public JsonObject snapshot(Object proxy, UUID expectedTrackId, int offset, int limit, boolean parameters, Runnable check) {
            check.run(); reads++;
            if (fail) throw HostServices.failure("not_grid", "not a Grid device");
            return result(offset, limit, parameters);
        }
        @Override public JsonObject parameter(Object proxy, UUID expectedTrackId, UUID moduleId, String parameterId, Runnable check) {
            check.run(); reads++; return HostServices.observed();
        }
        @Override public JsonObject setParameter(Object proxy, UUID track, UUID module, String parameter,
                GridParameterWriter.Request request, Runnable check, Runnable dispatch) {
            beforeWrite.run();
            last = writer.set(request, check, dispatch);
            afterWrite.run();
            return last;
        }
        @Override public void moveModule(Object proxy, UUID track, UUID module, GridModuleWriter.Move request,
                Runnable check, Runnable dispatch, java.util.function.Consumer<JsonObject> complete,
                java.util.function.Consumer<Throwable> failure) {
            topology.moduleId = module;
            topology.edit(request, check, dispatch, complete, failure);
        }
        @Override public void deleteModule(Object proxy, UUID track, UUID module,
                Runnable check, Runnable dispatch, java.util.function.Consumer<JsonObject> complete,
                java.util.function.Consumer<Throwable> failure) {
            topology.moduleId = module;
            topology.edit(null, check, dispatch, complete, failure);
        }
        @Override public void setConnection(Object proxy, UUID track, GridConnectionWriter.Request request,
                Runnable check, Runnable dispatch, java.util.function.Consumer<JsonObject> complete,
                java.util.function.Consumer<Throwable> failure) {
            connections.edit(request, check, dispatch, complete, failure);
        }
        @Override public void insertModule(Object proxy, UUID track, GridInsertionWriter.Request request,
                Runnable check, Runnable dispatch, java.util.function.Consumer<JsonObject> complete,
                java.util.function.Consumer<Throwable> failure) {
            insertion.edit(request, check, dispatch, complete, failure);
        }
        @Override public void renameModule(Object proxy, UUID track, UUID module, GridModuleNameWriter.Request request,
                Runnable check, Runnable dispatch, java.util.function.Consumer<JsonObject> complete,
                java.util.function.Consumer<Throwable> failure) {
            naming.edit(module, request, check, dispatch, complete, failure);
        }
        private JsonObject result(int offset, int limit, boolean parameters) {
            last = new JsonObject(); last.addProperty("offset", offset); last.addProperty("limit", limit); last.addProperty("parameters", parameters); return last;
        }
    }
}
