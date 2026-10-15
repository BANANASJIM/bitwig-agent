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
        nativeCallbacksNeverReadControllerSafetyGetters();
        everyPostDispatchBoundaryKeepsUnknownOutcome();
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
        check(GridModule.serialize(value).contains("null"), "unsupported null fields survive immutable handoff");
        value.addProperty("large", "x".repeat(GridModule.MAX_RESPONSE_CHARS));
        try { GridModule.serialize(value); throw new AssertionError("oversized graph escaped bound"); }
        catch (RpcException error) { check(error.code.equals("grid_bounds_exceeded"), "oversize asks caller to reduce page instead of truncating"); }
    }
    private static void loadedSourceGateFailsClosed() throws Exception {
        GridAdapter wrongVersion = new Bitwig613GridAdapter(new Object(), "6.1.4", () -> {});
        check(!wrongVersion.capabilities().get("gatePassed").getAsBoolean(), "unsupported version fails before any native binding");
        GridAdapter noSource = new Bitwig613GridAdapter(new Object(), "6.1.3", () -> {});
        check(!noSource.capabilities().get("gatePassed").getAsBoolean(), "missing loaded CodeSource cannot fall back to arbitrary installed JAR");
        check(!noSource.capabilities().getAsJsonObject("nativeOwningTrack").get("available").getAsBoolean()
                && !noSource.capabilities().getAsJsonObject("structure").get("available").getAsBoolean(),
                "unverified identity bindings cannot advertise usable native ownership or structure");
        try {
            noSource.snapshot(new Object(), UUID.fromString(TransportMixFixture.A), 0, 64, true, () -> {});
            throw new AssertionError("snapshot escaped identity gate");
        } catch (RpcException error) { check(error.code.equals("grid_unavailable"), "snapshot requires exact verified identity gate"); }
        try { noSource.execute(() -> { throw new AssertionError("read escaped gate"); }); throw new AssertionError("expected gate failure"); }
        catch (RpcException error) { check(error.code.equals("grid_unavailable"), "failed gate returns actionable unavailable"); }
        Path file = Files.createTempFile("grid-hash-test-", ".bin");
        try {
            Files.writeString(file, "abc", StandardCharsets.UTF_8);
            check(Bitwig613GridAdapter.hash(file, () -> {}).equals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"), "hash reads bytes not filename or version text");
            try { Bitwig613GridAdapter.hash(file, () -> { throw HostServices.failure("cancelled", "cancel hashing"); }); throw new AssertionError("hash ignored cancellation"); }
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
        serialization.adapter.afterWrite = () -> serialization.adapter.last.addProperty("oversized", "x".repeat(GridModule.MAX_RESPONSE_CHARS));
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
        private JsonObject result(int offset, int limit, boolean parameters) {
            last = new JsonObject(); last.addProperty("offset", offset); last.addProperty("limit", limit); last.addProperty("parameters", parameters); return last;
        }
    }
}
