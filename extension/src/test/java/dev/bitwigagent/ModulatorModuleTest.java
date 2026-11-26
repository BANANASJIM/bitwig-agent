package dev.bitwigagent;

import static dev.bitwigagent.RpcAssert.*;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Simulated independent controller/document queues; these tests do not establish native acceptance. */
public final class ModulatorModuleTest {

    @Test
    void validationPrecedesBothQueues() {
        Fixture f = new Fixture();
        for (String field : List.of("parent", "modulatorId", "parameterId")) {
            JsonObject p = parameter();
            p.remove(field);
            invalid(f, "modulators.parameters.get", p);
            p = parameter();
            p.add(field, JsonNull.INSTANCE);
            invalid(f, "modulators.parameters.get", p);
        }
        for (String key : List.of("offset", "limit")) {
            for (String raw : List.of("null", "true", "\"1\"", "1.5", "1.0", "1e0", "[]", "{}", "2147483648")) {
                JsonObject p = request();
                p.add(key, JsonParser.parseString(raw));
                invalid(f, "modulators.list", p);
            }
        }
        for (String raw : List.of("true", "\"0\"", "0.0", "0e0", "-1", "16")) {
            JsonObject p = request();
            p.getAsJsonObject("parent").add("deviceIndex", JsonParser.parseString(raw));
            invalid(f, "modulators.list", p);
        }
        for (String field : List.of("trackId", "deviceIndex", "expectedDeviceName")) {
            JsonObject p = request();
            p.getAsJsonObject("parent").remove(field);
            invalid(f, "modulators.list", p);
        }
        for (String id : List.of("", "1-1-1-1-1", "not-a-uuid", HostFixture.B + " ")) {
            JsonObject p = parameter();
            p.addProperty("modulatorId", id);
            invalid(f, "modulators.parameters.get", p);
        }
        for (JsonElement id : List.of(
                new JsonPrimitive(true), new JsonPrimitive(3), new JsonArray(), new JsonPrimitive("x".repeat(1025)))) {
            JsonObject p = parameter();
            p.add("parameterId", id);
            invalid(f, "modulators.parameters.get", p);
        }
        JsonObject p = request();
        p.addProperty("limit", 0);
        invalid(f, "modulators.list", p);
        p.addProperty("limit", 129);
        invalid(f, "modulators.list", p);
        p = request();
        p.addProperty("offset", -1);
        invalid(f, "modulators.list", p);
        p.addProperty("offset", 100001);
        invalid(f, "modulators.list", p);
        p = request();
        p.getAsJsonObject("parent").addProperty("expectedDeviceName", "");
        invalid(f, "modulators.list", p);
        p = request();
        p.getAsJsonObject("parent").addProperty("extra", true);
        invalid(f, "modulators.list", p);
        p = request();
        p.addProperty("modulatorId", HostFixture.B);
        invalid(f, "modulators.list", p);
        p = parameter();
        p.addProperty("limit", 1);
        invalid(f, "modulators.parameters.get", p);
        p = parameter();
        p.addProperty("parameterId", "\uD834\uDD1E".repeat(513));
        invalid(f, "modulators.parameters.get", p);
        p.addProperty("parameterId", "\uD834\uDD1E".repeat(512));
        ModulatorModule.validateParams("modulators.parameters.get", p);
        p.addProperty("parameterId", "");
        p.addProperty("modulatorId", "ABCDEFAB-ABCD-ABCD-ABCD-ABCDEFABCDEF");
        ModulatorModule.validateParams("modulators.parameters.get", p);
        p = request();
        p.addProperty("offset", 100000);
        p.addProperty("limit", 128);
        ModulatorModule.validateParams("modulators.list", p);
        invalid(f, "modulators.capabilities", request());
        assertTrue(
                f.host.tasks.isEmpty() && f.adapter.tasks.isEmpty() && f.adapter.reads == 0,
                "invalid schema never schedules either queue or touches the native adapter");
    }

    @Test
    void explicitRootUsesObservedWindow() {
        Fixture f = new Fixture();
        f.device.child("name").emit("Different");
        assertRpcError("stale_target", f.start("modulators.list", request()));
        assertTrue(f.adapter.tasks.isEmpty(), "wrong explicit parent never enters native queue");
        f.device.child("name").emit("Tool");
        f.host.tracks[0].child("position").emit(-1);
        assertRpcError("not_ready", f.start("modulators.list", request()));
        f.host.tracks[0].child("position").emit(0);
        f.devices.child("scrollPosition").emit(1);
        f.devices.child("itemCount").emit(2);
        f.device.child("position").emit(1);
        JsonObject p = request();
        p.getAsJsonObject("parent").addProperty("deviceIndex", 1);
        HostOperation op = f.start("modulators.list", p);
        f.adapter.next();
        f.host.next();
        assertTrue(
                op.result.join().getAsJsonObject("parent").get("deviceIndex").getAsInt() == 1
                        && f.adapter.proxy == f.host.services.sources.roots[0][0].device,
                "root authority resolves absolute index in the observed nonzero window, not a separate bank");
        assertRpcError("outside_window", f.start("modulators.list", request()));
        f.devices.child("itemCount").emit(1);
        assertRpcError("target_not_found", f.start("modulators.list", p));
    }

    @Test
    void queuesDetachJsonAndNeverDispatchMutation() {
        Fixture f = new Fixture();
        HostOperation op = f.start("modulators.list", request());
        assertTrue(!op.result.isDone() && f.adapter.reads == 0, "controller dispatch does not complete document work");
        f.adapter.next();
        assertTrue(
                !op.result.isDone() && f.adapter.reads == 1 && !op.dispatched,
                "document read waits for controller delivery without mutation dispatch");
        f.adapter
                .last
                .getAsJsonArray("modulators")
                .get(0)
                .getAsJsonObject()
                .addProperty("name", "changed after capture");
        f.host.next();
        JsonObject result = op.result.join();
        assertTrue(
                result.getAsJsonArray("modulators")
                        .get(0)
                        .getAsJsonObject()
                        .get("name")
                        .getAsString()
                        .equals("Macro"),
                "only immutable serialized JSON crosses document/controller boundary");
        assertTrue(
                !result.get("nativeMutation").getAsBoolean() && !op.dispatched && f.device.writes == 0,
                "read has no mutation dispatch or public write");
        assertTrue(
                result.getAsJsonObject("target")
                        .get("identityScope")
                        .getAsString()
                        .contains("not_persistent"),
                "public identity caveat remains explicit");
    }

    @Test
    void atomicTicketsRejectEveryObservedTargetTransition() {
        List<Consumer<Fixture>> transitions = List.of(
                f -> {
                    f.device.child("name").emit("Away");
                    f.device.child("name").emit("Tool");
                },
                f -> {
                    f.device.child("position").emit(1);
                    f.device.child("position").emit(0);
                },
                f -> {
                    f.device.child("exists").emit(false);
                    f.device.child("exists").emit(true);
                },
                f -> {
                    f.device.child("deviceType").emit("Instrument");
                    f.device.child("deviceType").emit("AudioEffect");
                },
                f -> {
                    f.host.tracks[0].child("channelId").emit(HostFixture.C);
                    f.host.tracks[0].child("channelId").emit(HostFixture.A);
                },
                f -> {
                    f.host.tracks[0].child("position").emit(1);
                    f.host.tracks[0].child("position").emit(0);
                },
                f -> {
                    f.devices.child("scrollPosition").emit(1);
                    f.devices.child("scrollPosition").emit(0);
                },
                f -> {
                    f.devices.child("itemCount").emit(2);
                    f.devices.child("itemCount").emit(1);
                },
                f -> {
                    f.host.bank.child("itemCount").emit(3);
                    f.host.bank.child("itemCount").emit(2);
                },
                f -> {
                    f.host.bank.child("scrollPosition").emit(1);
                    f.host.bank.child("scrollPosition").emit(0);
                });
        for (Consumer<Fixture> change : transitions) {
            Fixture f = new Fixture();
            HostOperation op = f.start("modulators.list", request());
            change.accept(f);
            f.adapter.next();
            assertRpcError("stale_context", op);
            assertTrue(
                    f.adapter.reads == 0 && f.host.tasks.isEmpty() && !op.dispatched,
                    "target/window ABA invalidates atomic ticket before any document read");
        }
    }

    @Test
    void guardsDuringReadAndBeforeDelivery() {
        Fixture during = new Fixture();
        HostOperation op = during.start("modulators.list", request());
        during.adapter.during = () -> {
            during.device.child("name").emit("Away");
            during.device.child("name").emit("Tool");
        };
        during.adapter.next();
        assertRpcError("stale_context", op);
        assertTrue(
                during.host.tasks.isEmpty() && !op.dispatched,
                "target ABA during document capture cannot enqueue delivery");
        Fixture late = new Fixture();
        op = late.start("modulators.list", request());
        late.adapter.next();
        late.device.child("name").emit("Away");
        late.device.child("name").emit("Tool");
        late.host.next();
        assertRpcError("stale_context", op);
        Fixture silent = new Fixture();
        op = silent.start("modulators.list", request());
        silent.adapter.next();
        silent.device.child("name").data = "getter changed before callback";
        silent.host.next();
        assertRpcError("stale_context", op);
        assertTrue(!op.dispatched, "final controller getter corroboration rejects changes not yet notified");
        Fixture before = new Fixture();
        op = before.host.submit(before.module, "modulators.list", request());
        before.device.child("name").emit("Away");
        before.host.next();
        assertRpcError("stale_target", op);
        assertTrue(before.adapter.tasks.isEmpty(), "wrong parent before capture never dispatches document work");
    }

    @Test
    void documentCallbacksNeverReadPublicGetters() {
        Fixture f = new Fixture();
        for (FakeHost node : List.of(
                f.device.child("exists"),
                f.device.child("name"),
                f.device.child("position"),
                f.device.child("deviceType"),
                f.host.tracks[0].child("exists"),
                f.host.tracks[0].child("channelId"),
                f.host.tracks[0].child("position"),
                f.devices.child("scrollPosition"),
                f.devices.child("itemCount"),
                f.host.bank.child("scrollPosition"),
                f.host.bank.child("itemCount"))) {
            node.onCall = (name, args) -> {
                if (name.equals("get") && f.adapter.onDocument)
                    throw new AssertionError("Controller getter on document queue");
            };
        }
        HostOperation op = f.start("modulators.parameters.get", parameter());
        f.adapter.next();
        f.host.next();
        assertTrue(
                op.result.join().get("value").getAsString().equals("  音色\n\uD834\uDD1E  "),
                "atomic native guards never call Controller getters and raw Unicode/whitespace survives");
    }

    @Test
    void cancellationDeadlineAndClose() {
        Fixture f = new Fixture();
        HostOperation op = f.host.submit(f.module, "modulators.list", request());
        op.result.cancel(false);
        f.host.next();
        assertTrue(f.adapter.tasks.isEmpty(), "controller cancellation never schedules native work");
        op = f.start("modulators.list", request());
        op.result.cancel(false);
        f.adapter.next();
        assertTrue(f.adapter.reads == 0 && f.host.tasks.isEmpty(), "document cancellation never reads");
        op = f.host.submit(f.module, "modulators.list", request(), 0);
        f.host.next();
        assertRpcError("deadline", op);
        assertTrue(f.adapter.tasks.isEmpty(), "deadline before capture never dispatches");
        Fixture late = new Fixture();
        op = late.start("modulators.list", request());
        late.adapter.next();
        op.result.cancel(false);
        late.host.next();
        assertTrue(op.result.isCancelled() && !op.dispatched, "late delivery cannot overwrite cancellation");
        Fixture during = new Fixture();
        op = during.start("modulators.list", request());
        final HostOperation cancelled = op;
        during.adapter.during = () -> cancelled.result.cancel(false);
        during.adapter.next();
        assertTrue(
                op.result.isCancelled() && during.host.tasks.isEmpty(), "cancellation during read suppresses delivery");
        Fixture closed = new Fixture();
        op = closed.start("modulators.list", request());
        closed.host.services.scheduler.close();
        closed.adapter.next();
        assertRpcError("not_ready", op);
        assertTrue(closed.adapter.reads == 0, "close invalidates native queue before capture");
    }

    @Test
    void currentUuidAndExactEmptyStringIdentity() {
        Fixture f = new Fixture();
        HostOperation stale = f.start("modulators.parameters.get", parameter());
        f.adapter.currentId = UUID.fromString(HostFixture.C);
        f.adapter.next();
        assertRpcError("target_not_found", stale);
        JsonObject p = parameter();
        p.addProperty("modulatorId", HostFixture.C);
        p.addProperty("parameterId", "");
        HostOperation fresh = f.start("modulators.parameters.get", p);
        f.adapter.next();
        f.host.next();
        assertTrue(
                fresh.result.join().get("value").getAsString().isEmpty(),
                "current UUID and unique empty exact ID are valid; empty value is not missing");
        p.addProperty("parameterId", "label");
        HostOperation wrong = f.start("modulators.parameters.get", p);
        f.adapter.next();
        assertRpcError("target_not_found", wrong);
        p.addProperty("parameterId", "UNSUPPORTED");
        wrong = f.start("modulators.parameters.get", p);
        f.adapter.next();
        assertRpcError("unsupported_scope", wrong);
        assertTrue(!stale.dispatched && !wrong.dispatched, "identity and family failures do not imply mutation");
    }

    @Test
    void concurrentRequestsKeepIndependentTargets() {
        Fixture f = new Fixture();
        JsonObject emptyPage = request();
        emptyPage.addProperty("offset", 1);
        HostOperation a = f.start("modulators.list", request()), b = f.start("modulators.list", emptyPage);
        f.adapter.tasks.removeLast().run();
        f.adapter.next();
        f.host.next();
        f.host.next();
        assertTrue(
                a.result.join().getAsJsonArray("modulators").size() == 1
                        && b.result.join().getAsJsonArray("modulators").isEmpty(),
                "out-of-order native reads deliver each request's independently captured page");
    }

    @Test
    void boundedResponsesPreserveNullsAndFailWithoutTruncation() {
        Fixture f = new Fixture();
        HostOperation op = f.start("modulators.parameters.get", parameter());
        f.adapter.next();
        f.host.next();
        JsonObject result = op.result.join();
        assertTrue(
                result.has("labels")
                        && result.get("labels").isJsonNull()
                        && result.get("shortLabel").isJsonNull()
                        && result.get("display").isJsonNull(),
                "explicit unavailable details survive serialization as null");
        Fixture oversized = new Fixture();
        oversized.adapter.extra = "x".repeat(ModulatorModule.MAX_RESPONSE_CHARS);
        op = oversized.start("modulators.list", request());
        oversized.adapter.next();
        assertRpcError("out_of_bounds", op);
        assertTrue(
                oversized.host.tasks.isEmpty() && !op.dispatched,
                "oversized complete response fails without truncation or delivery");
        JsonObject exact = new JsonObject();
        exact.addProperty("v", "x");
        String json = BoundedJson.serialize(exact, 9, Reason.OUT_OF_BOUNDS, "bound");
        assertTrue(json.equals("{\"v\":\"x\"}"), "exact transport bound is inclusive");
        {
            RpcException error = assertThrows(
                    RpcException.class, () -> BoundedJson.serialize(exact, 8, Reason.OUT_OF_BOUNDS, "bound"));
            assertTrue(error.reason.wire().equals("out_of_bounds"), "one character overflow fails closed");
        }
    }

    @Test
    void sharedRuntimeAndGridPolicyRemainIndependent() {
        Fixture f = new Fixture();
        HostOperation op = new HostOperation(Long.MAX_VALUE);
        Bitwig614Runtime first = f.host.services.natives.get(op);
        assertTrue(
                first == f.host.services.natives.get(new HostOperation(Long.MAX_VALUE)),
                "services compose one lazy shared runtime gate");
        GridModule grid = f.host.services.grid(new Bitwig614GridAdapter(first.access()));
        f.devices.child("scrollPosition").emit(1);
        f.devices.child("itemCount").emit(2);
        f.device.child("position").emit(1);
        JsonObject target = request().getAsJsonObject("parent");
        target.addProperty("deviceIndex", 1);
        HostOperation gridRead = f.host.submit(grid, "grid.get", target);
        f.host.next();
        assertRpcError("stale_context", gridRead);
        JsonObject p = request();
        p.add("parent", target);
        op = f.start("modulators.list", p);
        f.adapter.next();
        f.host.next();
        assertTrue(
                op.result.join().getAsJsonArray("modulators").size() == 1,
                "modulator observed-root policy does not silently change Grid zero-offset policy");
        assertTrue(
                f.host.host.calls("createTrackBank") == 1,
                "modulator boundary reuses the shared public root authority without another bank");
    }

    private static void invalid(Fixture f, String method, JsonObject p) {
        assertRpcError("invalid_params", f.host.submit(f.module, method, p));
    }

    private static JsonObject request() {
        JsonObject parent = new JsonObject();
        parent.addProperty("trackId", HostFixture.A);
        parent.addProperty("deviceIndex", 0);
        parent.addProperty("expectedDeviceName", "Tool");
        JsonObject result = new JsonObject();
        result.add("parent", parent);
        return result;
    }

    private static JsonObject parameter() {
        JsonObject result = request();
        result.addProperty("modulatorId", HostFixture.B);
        result.addProperty("parameterId", "LABEL");
        return result;
    }

    private static final class Fixture {
        final HostFixture host = new HostFixture();
        final FakeHost devices = host.tracks[0].child("createDeviceBank"), device = devices.item(0);
        final Adapter adapter = new Adapter();
        final ModulatorModule module;

        Fixture() {
            device.child("exists").emit(true);
            device.child("name").emit("Tool");
            device.child("position").emit(0);
            device.child("deviceType").emit("AudioEffect");
            devices.child("itemCount").emit(1);
            module = host.services.modulator(adapter);
        }

        HostOperation start(String method, JsonObject p) {
            HostOperation op = host.submit(module, method, p);
            host.next();
            return op;
        }
    }

    /** Stateful lookup fixture, not a native model: rejects previous IDs and wrong parameter families. */
    private static final class Adapter implements ModulatorAdapter {
        final Deque<Runnable> tasks = new ArrayDeque<>();
        final Map<String, String> strings = Map.of("LABEL", "  音色\n\uD834\uDD1E  ", "", "");
        UUID currentId = UUID.fromString(HostFixture.B);
        Runnable during = () -> {};
        JsonObject last;
        Object proxy;
        int reads;
        boolean onDocument;
        String extra;

        @Override
        public JsonObject capabilities() {
            JsonObject result = new JsonObject();
            result.addProperty("writeAvailable", false);
            return result;
        }

        @Override
        public void execute(Runnable task) {
            tasks.add(task);
        }

        void next() {
            onDocument = true;
            try {
                tasks.removeFirst().run();
            } finally {
                onDocument = false;
            }
        }

        @Override
        public JsonObject list(Object proxy, UUID track, int offset, int limit, Runnable check) {
            check.run();
            this.proxy = proxy;
            reads++;
            during.run();
            check.run();
            JsonObject result = new JsonObject();
            JsonArray rows = new JsonArray();
            if (offset == 0) {
                JsonObject row = new JsonObject();
                row.addProperty("name", "Macro");
                row.addProperty("modulatorId", currentId.toString());
                rows.add(row);
            }
            result.add("modulators", rows);
            JsonObject window = new JsonObject();
            window.addProperty("offset", offset);
            window.addProperty("limit", limit);
            window.addProperty("total", 1);
            window.addProperty("returned", rows.size());
            result.add("window", window);
            if (extra != null) result.addProperty("extra", extra);
            return last = result;
        }

        @Override
        public JsonObject parameter(Object proxy, UUID track, UUID modulator, String id, Runnable check) {
            check.run();
            this.proxy = proxy;
            reads++;
            during.run();
            check.run();
            if (!currentId.equals(modulator))
                throw Errors.notApplied(Reason.TARGET_NOT_FOUND, "Previous UUID is not current.");
            if (id.equals("UNSUPPORTED")) throw Errors.notApplied(Reason.UNSUPPORTED_SCOPE, "Not a string panel.");
            if (!strings.containsKey(id))
                throw Errors.notApplied(Reason.TARGET_NOT_FOUND, "No exact parameter identifier.");
            JsonObject result = new JsonObject();
            result.addProperty("modulatorId", currentId.toString());
            result.addProperty("parameterId", id);
            result.addProperty("kind", "string");
            result.addProperty("controlFamily", "string_panel");
            result.addProperty("value", strings.get(id));
            result.add("labels", JsonNull.INSTANCE);
            result.add("shortLabel", JsonNull.INSTANCE);
            result.add("display", JsonNull.INSTANCE);
            return last = result;
        }
    }

    @Test
    void unknownReadParentShapeAndUnboundAdapter() {
        assertRpcError("unknown_method", () -> ModulatorModule.validateParams("modulators.bogus", new JsonObject()));
        JsonObject p = request();
        p.addProperty("parent", 5);
        assertRpcError("invalid_params", () -> ModulatorModule.validateParams("modulators.list", p));
        Fixture f = new Fixture();
        ModulatorModule bare = f.host.services.modulator(null);
        JsonObject capabilities = f.host.call(bare, "modulators.capabilities", new JsonObject());
        assertTrue(capabilities.has("runtimeGate"), "the runtime gate is reported without a bound adapter");
    }
}
