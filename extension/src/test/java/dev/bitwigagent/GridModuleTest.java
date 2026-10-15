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
        f.host.next(); JsonObject result = op.result.join();
        check(result.getAsJsonObject("target").get("trackId").getAsString().equals(TransportMixFixture.A), "explicit target metadata returned");
        check(result.getAsJsonObject("target").get("identityScope").getAsString().equals("observed_bank_proxy_not_persistent_device_identity"), "cached target identity caveat explicit");
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
        GridReadAdapter wrongVersion = new Bitwig613GridReadAdapter(new Object(), "6.1.4", () -> {});
        check(!wrongVersion.capabilities().get("gatePassed").getAsBoolean(), "unsupported version fails before any native binding");
        GridReadAdapter noSource = new Bitwig613GridReadAdapter(new Object(), "6.1.3", () -> {});
        check(!noSource.capabilities().get("gatePassed").getAsBoolean(), "missing loaded CodeSource cannot fall back to arbitrary installed JAR");
        try { noSource.execute(() -> { throw new AssertionError("read escaped gate"); }); throw new AssertionError("expected gate failure"); }
        catch (RpcException error) { check(error.code.equals("grid_unavailable"), "failed gate returns actionable unavailable"); }
        Path file = Files.createTempFile("grid-hash-test-", ".bin");
        try {
            Files.writeString(file, "abc", StandardCharsets.UTF_8);
            check(Bitwig613GridReadAdapter.hash(file, () -> {}).equals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"), "hash reads bytes not filename or version text");
            try { Bitwig613GridReadAdapter.hash(file, () -> { throw HostServices.failure("cancelled", "cancel hashing"); }); throw new AssertionError("hash ignored cancellation"); }
            catch (RpcException error) { check(error.code.equals("cancelled"), "gate hashing observes request cancellation"); }
        } finally { Files.deleteIfExists(file); }
    }
    private static JsonObject target() {
        JsonObject result = TransportMixFixture.target(TransportMixFixture.A);
        result.addProperty("deviceIndex", 0); result.addProperty("expectedDeviceName", "Poly Grid"); return result;
    }
    private static void check(boolean condition, String label) { assertions++; if (!condition) throw new AssertionError(label); }
    private static final class Fixture {
        final TransportMixFixture host = new TransportMixFixture();
        final TransportMixFixture.Node device = host.tracks[0].child("createDeviceBank").item(0);
        final FakeAdapter adapter = new FakeAdapter(); final GridModule module;
        Fixture() { this(true); }
        Fixture(boolean observed) {
            device.child("exists").data = true; device.child("name").data = "Poly Grid"; device.child("position").data = 0;
            device.child("name").initial = observed; module = new GridModule(host.services, adapter);
        }
        HostOperation start(JsonObject p) { HostOperation op = host.submit(module, "grid.get", p); host.next(); return op; }
    }
    private static final class FakeAdapter implements GridReadAdapter {
        final Deque<Runnable> tasks = new ArrayDeque<>(); int reads; boolean fail; JsonObject last;
        void next() { tasks.removeFirst().run(); }
        @Override public void execute(Runnable task) { tasks.add(task); }
        @Override public JsonObject capabilities() { JsonObject result = new JsonObject(); result.addProperty("available", true); return result; }
        @Override public JsonObject catalog(String query, int offset, int limit, Runnable check) { return snapshot(null, offset, limit, false, check); }
        @Override public JsonObject snapshot(Object proxy, int offset, int limit, boolean parameters, Runnable check) {
            check.run(); reads++;
            if (fail) throw HostServices.failure("not_grid", "not a Grid device");
            last = new JsonObject(); last.addProperty("offset", offset); last.addProperty("limit", limit); last.addProperty("parameters", parameters); return last;
        }
    }
}
