package dev.bitwigagent;

import static dev.bitwigagent.RpcAssert.*;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import dev.bitwigagent.core.Refs;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Simulated independent controller/document queues; these tests do not establish native acceptance. */
public final class ModulatorModuleTest {
    private static final String LAYER = "00000000-0000-4000-8000-0000000000aa";
    private static final String PANEL = "  音色\n𝄞  ";

    @Test
    void listCarriesRowsWithRefsAndStringPanels() {
        Fixture f = new Fixture();
        JsonObject result = f.read("modulators.list", f.list());
        assertEquals(f.ref, result.get("parent"));
        assertEquals(2, result.get("count").getAsInt());
        assertTrue(result.get("complete").getAsBoolean());
        assertFalse(result.get("nativeMutation").getAsBoolean());
        assertEquals(HostFixture.json("{\"offset\":0,\"limit\":64,\"total\":2,\"returned\":2}"), result.get("window"));
        JsonArray rows = result.getAsJsonArray("modulators");
        JsonObject random = rows.get(0).getAsJsonObject();
        assertEquals(f.random, random.get("ref"));
        HostFixture.REF_SCHEMA.apply(random.getAsJsonObject("ref"));
        JsonArray panels = random.getAsJsonArray("panels");
        assertEquals(1, panels.size(), "an unsupported descriptor is not a panel");
        assertEquals("LABEL", panels.get(0).getAsJsonObject().get("id").getAsString());
        assertEquals("string", panels.get(0).getAsJsonObject().get("kind").getAsString());
        assertEquals(
                PANEL,
                panels.get(0).getAsJsonObject().get("value").getAsString(),
                "raw Unicode and whitespace survive");
        JsonObject lfo = rows.get(1).getAsJsonObject();
        assertEquals(f.lfo, lfo.get("ref"));
        assertFalse(lfo.has("panels"), "a descriptor without controls adds no panels");
        assertEquals(0, f.adapter.sourceIndex, "panels are read for the page the read returned");
    }

    @Test
    void listPagesAndAnEmptyPageNeedsNoPanelRead() {
        Fixture f = new Fixture();
        JsonObject p = f.list();
        p.addProperty("offset", 1);
        p.addProperty("limit", 1);
        JsonObject page = f.read("modulators.list", p);
        assertEquals(1, page.getAsJsonArray("modulators").size());
        assertEquals(
                f.lfo,
                page.getAsJsonArray("modulators").get(0).getAsJsonObject().get("ref"));
        assertFalse(page.get("complete").getAsBoolean());
        assertEquals(1, f.adapter.sourceIndex);
        p.addProperty("offset", 5);
        int before = f.adapter.reads;
        JsonObject beyond = f.read("modulators.list", p);
        assertEquals(0, beyond.getAsJsonArray("modulators").size());
        assertEquals(before, f.adapter.reads);
    }

    @Test
    void getReturnsTheRowAtTopLevel() {
        Fixture f = new Fixture();
        JsonObject p = new JsonObject();
        p.add("target", f.lfo);
        JsonObject result = f.read("modulators.get", p);
        assertEquals(f.lfo, result.get("ref"));
        assertEquals("LFO", result.get("name").getAsString());
        assertEquals(1, result.get("index").getAsInt());
        assertEquals(f.ref, result.get("parent"));
        assertTrue(result.get("complete").getAsBoolean());
        assertEquals(0, result.getAsJsonArray("unsupported").size());
        assertFalse(result.get("nativeMutation").getAsBoolean());
        for (String key : List.of("modulators", "count", "window")) assertFalse(result.has(key), key);
        assertTrue(result.has("parameters") && result.has("outputs"));
        JsonObject random = new JsonObject();
        random.add("target", f.random);
        assertEquals(
                1, f.read("modulators.get", random).getAsJsonArray("panels").size());
    }

    @Test
    void getReportsARenamedModulatorByItsCurrentRefAndRefusesAMissingOne() {
        Fixture f = new Fixture();
        f.model.mod(1).name = "Wobble";
        JsonObject p = new JsonObject();
        p.add("target", f.lfo);
        HostOperation op = f.start("modulators.get", p);
        f.adapter.next();
        f.host.next();
        JsonObject row = op.result.join();
        assertEquals(Refs.modulator(f.ref, ModulatorEditorTest.LFO.toString(), "Wobble"), row.get("ref"));
        HostFixture.REF_SCHEMA.apply(row.getAsJsonObject("ref"));
        assertFalse(op.dispatched(), "a read never compares the name it was given");
        JsonObject gone = new JsonObject();
        gone.add("target", Refs.modulator(f.ref, new UUID(7, 9).toString(), "Gone"));
        HostOperation missing = f.start("modulators.get", gone);
        f.adapter.next();
        assertEquals("modulators.list", assertRpcError("target_not_found", missing).next);
    }

    @Test
    void aDeviceInsideAChainHasNoModulatorsHere() {
        Fixture f = new Fixture();
        f.addChain();
        JsonObject chainDevice = Refs.device(f.chain, 0, "Polysynth");
        JsonObject list = new JsonObject();
        list.add("parent", chainDevice);
        assertRpcError("unsupported_scope", f.start("modulators.list", list));
        JsonObject get = new JsonObject();
        get.add("target", Refs.modulator(chainDevice, ModulatorEditorTest.RANDOM.toString(), "Random"));
        assertRpcError("unsupported_scope", f.start("modulators.get", get));
        JsonObject insert = new JsonObject();
        insert.add("parent", chainDevice);
        insert.addProperty("typeName", "Random");
        assertRpcError("unsupported_scope", f.start(f.edits, "modulators.insert", insert));
        assertTrue(f.adapter.tasks.isEmpty() && f.model.queue.isEmpty(), "nothing reached either native queue");
    }

    @Test
    void aRenamedDeviceIsTargetChangedBeforeAnyNativeWork() {
        Fixture f = new Fixture();
        f.device.child("name").emit("Different");
        RpcException changed = assertRpcError("target_changed", f.start("modulators.list", f.list()));
        assertEquals(Refs.device(Refs.track(HostFixture.A, "Track 0"), 0, "Different"), changed.current);
        assertTrue(f.adapter.tasks.isEmpty(), "a wrong explicit parent never enters the native queue");
        f.device.child("name").emit("Tool");
        f.host.tracks[0].child("position").emit(-1);
        assertRpcError("not_ready", f.start("modulators.list", f.list()));
    }

    @Test
    void aRootInAnObservedNonzeroWindowIsAddressedByItsAbsoluteIndex() {
        Fixture f = new Fixture();
        f.devices.child("scrollPosition").emit(1);
        f.devices.child("itemCount").emit(2);
        f.device.child("position").emit(1);
        JsonObject p = new JsonObject();
        p.add("parent", Refs.device(Refs.track(HostFixture.A, "Track 0"), 1, "Tool"));
        JsonObject result = f.read("modulators.list", p);
        assertEquals(1, result.getAsJsonObject("parent").get("index").getAsInt());
        assertSame(f.host.services.sources.roots[0][0], f.adapter.source);
        JsonObject outside = f.list();
        assertRpcError("outside_window", f.start("modulators.list", outside));
        f.devices.child("itemCount").emit(1);
        assertRpcError("target_not_found", f.start("modulators.list", p));
    }

    @Test
    void queuesDetachJsonAndNeverDispatchMutation() {
        Fixture f = new Fixture();
        HostOperation op = f.start("modulators.list", f.list());
        assertTrue(!op.result.isDone() && f.adapter.reads == 0, "controller dispatch does not complete document work");
        f.adapter.next();
        assertTrue(
                !op.result.isDone() && f.adapter.reads > 0 && !op.dispatched(),
                "document read waits for controller delivery without mutation dispatch");
        f.model.mod(0).name = "changed after capture";
        f.adapter.last.addProperty("value", "changed after capture");
        f.host.next();
        JsonObject result = op.result.join();
        assertEquals(
                "Random",
                result.getAsJsonArray("modulators")
                        .get(0)
                        .getAsJsonObject()
                        .get("name")
                        .getAsString());
        assertEquals(
                PANEL,
                result.getAsJsonArray("modulators")
                        .get(0)
                        .getAsJsonObject()
                        .getAsJsonArray("panels")
                        .get(0)
                        .getAsJsonObject()
                        .get("value")
                        .getAsString(),
                "only immutable serialized JSON crosses document/controller boundary");
        assertTrue(!result.get("nativeMutation").getAsBoolean() && !op.dispatched() && f.device.writes == 0);
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
            HostOperation op = f.start("modulators.list", f.list());
            change.accept(f);
            f.adapter.next();
            assertRpcError("target_changed", op);
            assertTrue(
                    f.adapter.reads == 0 && f.host.tasks.isEmpty() && !op.dispatched(),
                    "target/window ABA invalidates atomic ticket before any document read");
        }
    }

    @Test
    void guardsDuringReadAndBeforeDelivery() {
        Fixture during = new Fixture();
        HostOperation op = during.start("modulators.list", during.list());
        during.adapter.during = () -> {
            during.device.child("name").emit("Away");
            during.device.child("name").emit("Tool");
        };
        during.adapter.next();
        assertRpcError("target_changed", op);
        assertTrue(
                during.host.tasks.isEmpty() && !op.dispatched(),
                "target ABA during document capture cannot enqueue delivery");
        Fixture late = new Fixture();
        op = late.start("modulators.list", late.list());
        late.adapter.next();
        late.device.child("name").emit("Away");
        late.device.child("name").emit("Tool");
        late.host.next();
        assertRpcError("target_changed", op);
        Fixture silent = new Fixture();
        op = silent.start("modulators.list", silent.list());
        silent.adapter.next();
        silent.device.child("name").data = "getter changed before callback";
        silent.host.next();
        assertRpcError("target_changed", op);
        assertFalse(op.dispatched(), "final controller getter corroboration rejects changes not yet notified");
        Fixture before = new Fixture();
        op = before.host.submit(before.module, "modulators.list", before.list());
        before.device.child("name").emit("Away");
        before.host.next();
        assertRpcError("target_changed", op);
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
                f.host.tracks[0].child("name"),
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
        JsonObject result = f.read("modulators.list", f.list());
        assertEquals(
                PANEL,
                result.getAsJsonArray("modulators")
                        .get(0)
                        .getAsJsonObject()
                        .getAsJsonArray("panels")
                        .get(0)
                        .getAsJsonObject()
                        .get("value")
                        .getAsString(),
                "atomic native guards never call Controller getters");
    }

    @Test
    void mapAndUnmapGoThroughTheEditModule() {
        Fixture f = new Fixture();
        JsonObject p = new JsonObject();
        p.add("source", f.random);
        p.add("target", f.decay);
        p.addProperty("amount", 0.5);
        HostOperation map = f.start(f.edits, "modulators.map", p);
        f.finish(map);
        JsonObject mapped = map.result.join();
        assertEquals("applied", mapped.get("outcome").getAsString());
        assertEquals(0.5, mapped.getAsJsonObject("mapping").get("amount").getAsDouble());
        assertEquals(f.decay, mapped.get("ref"));
        assertEquals(1, f.model.source(0).mappings.size());
        JsonObject q = new JsonObject();
        q.add("source", f.random);
        q.add("target", f.decay);
        HostOperation unmap = f.start(f.edits, "modulators.unmap", q);
        f.finish(unmap);
        assertEquals("applied", unmap.result.join().get("outcome").getAsString());
        assertTrue(f.model.source(0).mappings.isEmpty());
    }

    @Test
    void mapAndUnmapRefuseAnotherDeviceAndRenamedRefs() {
        Fixture f = new Fixture();
        f.addRack();
        JsonObject otherDevice = Refs.device(Refs.track(HostFixture.A, "Track 0"), 1, "Instrument Layer");
        for (String method : List.of("modulators.map", "modulators.unmap")) {
            JsonObject elsewhere = new JsonObject();
            elsewhere.add("source", f.random);
            elsewhere.add(
                    "target",
                    Refs.parameter(
                            Refs.gridModule(otherDevice, ModulatorEditorTest.MODULE.toString(), "Env"), "DECAY", null));
            elsewhere.addProperty("amount", 0.5);
            if (method.endsWith("unmap")) elsewhere.remove("amount");
            assertRpcError("invalid_params", f.finish(f.start(f.edits, method, elsewhere)));
        }
        JsonObject renamedControl = new JsonObject();
        renamedControl.add("source", f.random);
        renamedControl.add(
                "target",
                Refs.parameter(Refs.gridModule(f.ref, ModulatorEditorTest.MODULE.toString(), "Env"), "DECAY", "Old"));
        renamedControl.addProperty("amount", 0.5);
        HostOperation op = f.finish(f.start(f.edits, "modulators.map", renamedControl));
        RpcException renamed = assertRpcError("target_changed", op);
        assertEquals("Decay", renamed.current.get("name").getAsString());
        HostFixture.REF_SCHEMA.apply(renamed.current);
        assertFalse(op.dispatched());
        assertTrue(f.model.source(0).mappings.isEmpty());
    }

    private static JsonObject setRate(Fixture f, String valueJson, String expectJson) {
        JsonObject p = new JsonObject();
        p.add("target", Refs.parameter(f.random, "RATE", "Rate"));
        p.add("value", JsonParser.parseString(valueJson));
        if (expectJson != null) p.add("expect", HostFixture.json(expectJson));
        return p;
    }

    @Test
    void aModulatorControlIsWrittenThroughParametersSet() {
        Fixture f = new Fixture();
        HostOperation op = f.finish(f.start(f.parameters, "parameters.set", setRate(f, "0.75", null)));
        JsonObject result = op.result.join();
        assertEquals("applied", result.get("outcome").getAsString());
        assertEquals(Refs.parameter(f.random, "RATE", "Rate"), result.get("ref"));
        HostFixture.REF_SCHEMA.apply(result.getAsJsonObject("ref"));
        assertEquals(
                0.75,
                result.getAsJsonObject("fields")
                        .getAsJsonObject("value")
                        .get("requested")
                        .getAsDouble());
        assertEquals(0.75, result.getAsJsonObject("parameter").get("value").getAsDouble());
        assertEquals(0.75, f.model.param(0, "RATE").value);
        assertTrue(op.dispatched());
    }

    @Test
    void anExpectMismatchOnAModulatorControlWritesNothing() {
        Fixture f = new Fixture();
        HostOperation op = f.finish(f.start(f.parameters, "parameters.set", setRate(f, "0.75", "{\"value\":0.25}")));
        RpcException mismatch = assertRpcError("target_changed", op);
        assertEquals(HostFixture.json("{\"value\":0.5}"), mismatch.observed);
        assertEquals(0.5, f.model.param(0, "RATE").value);
        assertFalse(op.dispatched());
        HostOperation match = f.finish(f.start(f.parameters, "parameters.set", setRate(f, "0.75", "{\"value\":0.5}")));
        assertEquals("applied", match.result.join().get("outcome").getAsString());
    }

    @Test
    void aModulatorControlOnAChainDeviceIsUnsupportedScope() {
        Fixture f = new Fixture();
        f.addChain();
        JsonObject chainDevice = Refs.device(f.chain, 0, "Polysynth");
        JsonObject p = new JsonObject();
        p.add(
                "target",
                Refs.parameter(
                        Refs.modulator(chainDevice, ModulatorEditorTest.RANDOM.toString(), "Random"), "RATE", "Rate"));
        p.addProperty("value", 0.75);
        assertRpcError("unsupported_scope", f.finish(f.start(f.parameters, "parameters.set", p)));
        assertTrue(f.model.queue.isEmpty());
        assertEquals(0.5, f.model.param(0, "RATE").value);
    }

    @Test
    void aModulatorControlMayBeWrittenWhilePlayingButNotWhileRecording() {
        Fixture playing = new Fixture();
        playing.host.transport.child("isPlaying").emit(true);
        HostOperation op =
                playing.finish(playing.start(playing.parameters, "parameters.set", setRate(playing, "0.75", null)));
        assertEquals("applied", op.result.join().get("outcome").getAsString());
        Fixture recording = new Fixture();
        recording.host.transport.child("isArrangerRecordEnabled").emit(true);
        HostOperation refused = recording.finish(
                recording.start(recording.parameters, "parameters.set", setRate(recording, "0.75", null)));
        assertRpcError("unsafe_state", refused);
        assertEquals(0.5, recording.model.param(0, "RATE").value);
        assertFalse(refused.dispatched());
    }

    @Test
    void cancellationDeadlineAndClose() {
        Fixture f = new Fixture();
        HostOperation op = f.host.submit(f.module, "modulators.list", f.list());
        op.result.cancel(false);
        f.host.next();
        assertTrue(f.adapter.tasks.isEmpty(), "controller cancellation never schedules native work");
        op = f.start("modulators.list", f.list());
        op.result.cancel(false);
        f.adapter.next();
        assertTrue(f.adapter.reads == 0 && f.host.tasks.isEmpty(), "document cancellation never reads");
        op = f.host.submit(f.module, "modulators.list", f.list(), 0);
        f.host.next();
        assertRpcError("deadline", op);
        assertTrue(f.adapter.tasks.isEmpty(), "deadline before capture never dispatches");
        Fixture late = new Fixture();
        op = late.start("modulators.list", late.list());
        late.adapter.next();
        op.result.cancel(false);
        late.host.next();
        assertTrue(op.result.isCancelled() && !op.dispatched(), "late delivery cannot overwrite cancellation");
        Fixture during = new Fixture();
        op = during.start("modulators.list", during.list());
        final HostOperation cancelled = op;
        during.adapter.during = () -> cancelled.result.cancel(false);
        during.adapter.next();
        assertTrue(
                op.result.isCancelled() && during.host.tasks.isEmpty(), "cancellation during read suppresses delivery");
        Fixture closed = new Fixture();
        op = closed.start("modulators.list", closed.list());
        closed.host.services.scheduler.close();
        closed.adapter.next();
        assertRpcError("not_ready", op);
        assertTrue(closed.adapter.reads == 0, "close invalidates native queue before capture");
    }

    @Test
    void concurrentRequestsKeepIndependentTargets() {
        Fixture f = new Fixture();
        JsonObject emptyPage = f.list();
        emptyPage.addProperty("offset", 5);
        HostOperation a = f.start("modulators.list", f.list()), b = f.start("modulators.list", emptyPage);
        f.adapter.tasks.removeLast().run();
        f.adapter.next();
        f.host.next();
        f.host.next();
        assertEquals(2, a.result.join().getAsJsonArray("modulators").size());
        assertTrue(b.result.join().getAsJsonArray("modulators").isEmpty());
    }

    @Test
    void boundedResponsesFailWithoutTruncation() {
        Fixture f = new Fixture();
        f.adapter.strings.put("LABEL", "x".repeat(ModulatorModule.MAX_RESPONSE_CHARS));
        HostOperation op = f.start("modulators.list", f.list());
        f.adapter.next();
        assertRpcError("out_of_bounds", op);
        assertTrue(f.host.tasks.isEmpty() && !op.dispatched(), "oversized complete response fails without delivery");
        JsonObject exact = new JsonObject();
        exact.addProperty("v", "x");
        assertEquals("{\"v\":\"x\"}", BoundedJson.serialize(exact, 9, Reason.OUT_OF_BOUNDS, "bound"));
        assertRpcError("out_of_bounds", () -> BoundedJson.serialize(exact, 8, Reason.OUT_OF_BOUNDS, "bound"));
    }

    @Test
    void anUnboundAdapterIsRefusedUnderAnotherHostVersion() {
        Fixture f = new Fixture();
        ModulatorModule bare = f.host.services.modulator(null, f.edits);
        assertRpcError("host_mismatch", f.start(bare, "modulators.list", f.list()));
    }

    // ------------------------------------------------------------------ edits
    @Test
    void insertGoesThroughTheEditModuleAndReturnsTheNewRow() {
        Fixture f = new Fixture();
        JsonObject p = new JsonObject();
        p.add("parent", f.ref);
        p.addProperty("typeName", "Random");
        HostOperation op = f.start(f.edits, "modulators.insert", p);
        f.model.run();
        assertFalse(op.result.isDone());
        assertTrue(op.dispatched());
        f.model.pendingSettle.accept(null);
        f.model.run();
        f.host.next();
        JsonObject result = op.result.join();
        assertEquals("applied", result.get("outcome").getAsString());
        assertEquals(Refs.modulator(f.ref, new UUID(7, 9).toString(), "Random"), result.get("ref"));
        assertEquals(3, result.get("count").getAsInt());
        assertEquals(result.get("ref"), result.getAsJsonObject("modulator").get("ref"));
    }

    @Test
    void deleteComparesTheModulatorNameAndReportsTheCount() {
        Fixture f = new Fixture();
        JsonObject p = new JsonObject();
        p.add("target", Refs.modulator(f.ref, ModulatorEditorTest.RANDOM.toString(), "Old name"));
        HostOperation op = f.start(f.edits, "modulators.delete", p);
        RpcException renamed = assertRpcError("target_changed", f.finish(op));
        assertEquals(f.random, renamed.current);
        assertFalse(op.dispatched());
        p.add("target", f.random);
        HostOperation done = f.start(f.edits, "modulators.delete", p);
        f.finish(done);
        JsonObject result = done.result.join();
        assertEquals("applied", result.get("outcome").getAsString());
        assertEquals(1, result.get("count").getAsInt());
        assertEquals(1, f.model.members.size());
    }

    @Test
    void theEditStatusSectionSaysWhetherTheBindingsAreAvailableAndNamesTheMethods() {
        Fixture f = new Fixture();
        HostOperation op = new HostOperation(Long.MAX_VALUE);
        JsonObject quiet = new JsonObject();
        f.edits.status(op, quiet, false);
        assertFalse(quiet.has("modulators"));
        JsonObject out = new JsonObject();
        f.edits.status(op, out, true);
        JsonObject section = out.getAsJsonObject("modulators");
        assertTrue(section.get("editAvailable").getAsBoolean());
        assertTrue(section.get("editUnavailableReason").isJsonNull());
        assertEquals("primary_modulators_on_root_device", section.get("scope").getAsString());
        assertEquals(
                List.of("modulators.delete", "modulators.insert", "modulators.map", "modulators.unmap"),
                section.getAsJsonArray("editMethods").asList().stream()
                        .map(JsonElement::getAsString)
                        .collect(java.util.stream.Collectors.toList()));
        assertTrue(section.get("structuralWritesRequireStoppedTransport").getAsBoolean());
        JsonObject unbound = new JsonObject();
        f.host.services.modulatorEdits(null).status(op, unbound, true);
        JsonObject missing = unbound.getAsJsonObject("modulators");
        assertFalse(missing.get("editAvailable").getAsBoolean());
        assertFalse(missing.get("editUnavailableReason").isJsonNull());
    }

    @Test
    void insertTakesExactlyOneOfTypeIdAndTypeName() {
        Fixture f = new Fixture();
        JsonObject neither = new JsonObject();
        neither.add("parent", f.ref);
        JsonObject both = neither.deepCopy();
        both.addProperty("typeName", "Random");
        both.addProperty("typeId", ModulatorEditorTest.RANDOM_TYPE.toString());
        assertInvalidParams(() -> f.edits.validate("modulators.insert", neither));
        assertInvalidParams(() -> f.edits.validate("modulators.insert", both));
        JsonObject byName = neither.deepCopy();
        byName.addProperty("typeName", "Random");
        f.edits.validate("modulators.insert", byName);
        JsonObject byId = neither.deepCopy();
        byId.addProperty("typeId", ModulatorEditorTest.RANDOM_TYPE.toString());
        f.edits.validate("modulators.insert", byId);
        f.edits.validate("modulators.delete", neither);
        assertRpcError("invalid_params", f.host.submit(f.edits, "modulators.insert", both).result);
    }

    @Test
    void theExportedSchemaRejectsLegacyShapesAndAcceptsRefs() {
        Fixture f = new Fixture();
        JsonObject legacyParent = HostFixture.json(
                "{\"trackId\":\"" + HostFixture.A + "\",\"deviceIndex\":0,\"expectedDeviceName\":\"Tool\"}");
        JsonObject legacyList = new JsonObject();
        legacyList.add("parent", legacyParent);
        assertInvalidParams(() -> HostBridge.checkSchema(HostFixture.SPECS, "modulators.list", legacyList));
        JsonObject legacyDelete = new JsonObject();
        legacyDelete.add("parent", legacyParent);
        legacyDelete.addProperty("modulatorId", ModulatorEditorTest.RANDOM.toString());
        legacyDelete.addProperty("expectedCount", 2);
        assertInvalidParams(() -> HostBridge.checkSchema(HostFixture.SPECS, "modulators.delete", legacyDelete));
        JsonObject legacyInsert = new JsonObject();
        legacyInsert.add("parent", f.ref);
        legacyInsert.addProperty("typeName", "Random");
        legacyInsert.addProperty("expectedCount", 2);
        assertInvalidParams(() -> HostBridge.checkSchema(HostFixture.SPECS, "modulators.insert", legacyInsert));
        JsonObject legacyMap = new JsonObject();
        legacyMap.add("parent", legacyParent);
        legacyMap.addProperty("modulatorId", ModulatorEditorTest.RANDOM.toString());
        legacyMap.addProperty("amount", 0.5);
        assertInvalidParams(() -> HostBridge.checkSchema(HostFixture.SPECS, "modulators.map", legacyMap));
        JsonObject ref = new JsonObject();
        ref.add("parent", f.ref);
        HostBridge.checkSchema(HostFixture.SPECS, "modulators.list", ref);
        JsonObject get = new JsonObject();
        get.add("target", f.random);
        HostBridge.checkSchema(HostFixture.SPECS, "modulators.get", get);
        JsonObject delete = new JsonObject();
        delete.add("target", f.random);
        HostBridge.checkSchema(HostFixture.SPECS, "modulators.delete", delete);
        for (String removed : List.of(
                "modulators.parameters.get",
                "modulators.parameters.set",
                "modulators.capabilities",
                "modulators.catalog"))
            assertThrows(
                    IllegalArgumentException.class,
                    () -> HostBridge.checkSchema(HostFixture.SPECS, removed, new JsonObject()),
                    removed);
    }

    // ------------------------------------------------------------------ fixtures
    private static final class Fixture {
        final HostFixture host = new HostFixture();
        final FakeHost devices = host.tracks[0].child("createDeviceBank"), device = devices.item(0);
        final FakeHost rackNode = devices.item(1);
        final Adapter adapter = new Adapter();
        final ModulatorEditorTest.Fake model;
        final ModulatorEditModule edits;
        final ModulatorModule module;
        final ParameterModule parameters;
        final JsonObject ref, random, lfo, decay;
        JsonObject chain;

        Fixture() {
            device.child("exists").emit(true);
            device.child("name").emit("Tool");
            device.child("position").emit(0);
            device.child("deviceType").emit("AudioEffect");
            devices.child("itemCount").emit(1);
            DeviceSources.Source source = host.services.sources.roots[0][0];
            model = new ModulatorEditorTest.Fake(source);
            edits = host.services.modulatorEdits(new ModulatorEditor(model));
            module = host.services.modulator(adapter, edits);
            parameters = host.services.parameters(host.services.remote(), edits);
            ref = source.ref();
            random = Refs.modulator(ref, ModulatorEditorTest.RANDOM.toString(), "Random");
            lfo = Refs.modulator(ref, ModulatorEditorTest.LFO.toString(), "LFO");
            decay = Refs.parameter(
                    Refs.gridModule(ref, ModulatorEditorTest.MODULE.toString(), "Env"), "DECAY", "Decay");
            adapter.descriptors.add(descriptor(ModulatorEditorTest.RANDOM, true));
            adapter.descriptors.add(descriptor(ModulatorEditorTest.LFO, false));
        }

        private static JsonObject descriptor(UUID id, boolean withControls) {
            JsonObject row = new JsonObject();
            row.addProperty("modulatorId", id.toString());
            if (!withControls) {
                row.add("parameters", JsonNull.INSTANCE);
                return row;
            }
            JsonArray parameters = new JsonArray();
            for (String[] control : new String[][] {{"true", "LABEL"}, {"false", "UNSUPPORTED"}}) {
                JsonObject entry = new JsonObject();
                entry.addProperty("supported", Boolean.parseBoolean(control[0]));
                entry.addProperty("parameterId", control[1]);
                parameters.add(entry);
            }
            row.add("parameters", parameters);
            return row;
        }

        void addRack() {
            rackNode.child("exists").emit(true);
            rackNode.child("position").emit(1);
            rackNode.child("deviceType").emit("instrument");
            rackNode.child("name").emit("Instrument Layer");
            devices.child("itemCount").emit(2);
        }

        /** A chain of the rack; its devices are the native document's, never observed here. */
        void addChain() {
            addRack();
            chain = Refs.chain(Refs.device(Refs.track(HostFixture.A, "Track 0"), 1, "Instrument Layer"), LAYER, "Pad");
        }

        JsonObject list() {
            JsonObject p = new JsonObject();
            p.add("parent", ref);
            return p;
        }

        HostOperation start(String method, JsonObject p) {
            return start(module, method, p);
        }

        HostOperation start(HostModule target, String method, JsonObject p) {
            HostOperation op = host.submit(target, method, p);
            host.next();
            return op;
        }

        JsonObject read(String method, JsonObject p) {
            HostOperation op = start(method, p);
            adapter.next();
            host.next();
            return op.result.join();
        }

        /** Runs the native queue of the edit model and delivers what it settled. */
        HostOperation finish(HostOperation op) {
            model.run();
            if (!op.result.isDone() && !host.tasks.isEmpty()) host.next();
            return op;
        }
    }

    /** Stateful lookup fixture, not a native model: the string panels of the modulators. */
    private static final class Adapter implements ModulatorAdapter {
        final Deque<Runnable> tasks = new ArrayDeque<>();
        final List<JsonObject> descriptors = new ArrayList<>();
        final Map<String, String> strings = new HashMap<>(Map.of("LABEL", PANEL));
        Runnable during = () -> {};
        JsonObject last;
        DeviceSources.Source source;
        int reads, sourceIndex = -1;
        boolean onDocument;

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
        public JsonObject list(DeviceSources.Source source, int offset, int limit, Runnable check) {
            check.run();
            this.source = source;
            reads++;
            sourceIndex = offset;
            during.run();
            check.run();
            JsonObject result = new JsonObject();
            JsonArray rows = new JsonArray();
            for (int i = offset; i < Math.min(descriptors.size(), offset + limit); i++) rows.add(descriptors.get(i));
            result.add("modulators", rows);
            return result;
        }

        @Override
        public JsonObject parameter(DeviceSources.Source source, UUID modulator, String id, Runnable check) {
            check.run();
            this.source = source;
            reads++;
            during.run();
            check.run();
            if (!strings.containsKey(id))
                throw Errors.notApplied(Reason.TARGET_NOT_FOUND, "No exact parameter identifier.");
            JsonObject result = new JsonObject();
            result.addProperty("modulatorId", modulator.toString());
            result.addProperty("parameterId", id);
            result.addProperty("value", strings.get(id));
            return last = result;
        }
    }
}
