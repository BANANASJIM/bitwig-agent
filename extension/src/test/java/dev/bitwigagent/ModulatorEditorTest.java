package dev.bitwigagent;

import static dev.bitwigagent.RpcAssert.*;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Host-free semantic fake of the device-modulator model; does not establish native acceptance. */
public final class ModulatorEditorTest {
    static final UUID TRACK = new UUID(9, 1), RANDOM = new UUID(7, 1), LFO = new UUID(7, 2);
    static final UUID MODULE = new UUID(5, 1), RANDOM_TYPE = new UUID(8, 1), LFO_TYPE = new UUID(8, 2);

    // ------------------------------------------------------------------ fake model
    static final class Param {
        final String id, kind;
        double value;
        final double min, max;
        final Object owner;
        boolean writable = true;

        Param(String id, String kind, double value, double min, double max, Object owner) {
            this.id = id;
            this.kind = kind;
            this.value = value;
            this.min = min;
            this.max = max;
            this.owner = owner;
        }
    }

    static final class Mapping {
        final Param target;
        double amount;
        String transfer;
        boolean enabled;

        Mapping(Param target, double amount, String transfer, boolean enabled) {
            this.target = target;
            this.amount = amount;
            this.transfer = transfer;
            this.enabled = enabled;
        }
    }

    static final class Source {
        final String id;
        final Object owner;
        final List<Mapping> mappings = new ArrayList<>();

        Source(String id, Object owner) {
            this.id = id;
            this.owner = owner;
        }
    }

    static final class Modulator {
        final UUID id, type;
        final String name;
        final List<Object> children = new ArrayList<>();
        int y;

        Modulator(UUID id, UUID type, String name) {
            this.id = id;
            this.type = type;
            this.name = name;
        }
    }

    static final class Module {
        final UUID id;

        Module(UUID id) {
            this.id = id;
        }
    }

    static final class Fake implements ModulatorEditor.Access {
        final Object device = new Object(), collection = new Object(), graph = new Object();
        final List<Object> members = new ArrayList<>();
        final Module module = new Module(MODULE);
        final Param decay = new Param("DECAY", "float", 0.2, 0, 1, module);
        final Deque<Runnable> queue = new ArrayDeque<>();
        int dispatched;
        boolean ignoreWrites, rejectInsert, wrongType, canModulate = true;
        Consumer<String> pendingSettle;

        Fake() {
            Modulator random = new Modulator(RANDOM, RANDOM_TYPE, "Random");
            random.children.add(new Source("OUT", random));
            random.children.add(new Param("RATE", "float", 0.5, 0, 1, random));
            Modulator lfo = new Modulator(LFO, LFO_TYPE, "LFO");
            lfo.y = 2;
            lfo.children.add(new Source("LFO", lfo));
            lfo.children.add(new Param("DEPTH", "float", 1.0, 0, 1, lfo));
            members.add(random);
            members.add(lfo);
        }

        Modulator mod(int i) {
            return (Modulator) members.get(i);
        }

        Source source(int i) {
            return (Source) mod(i).children.get(0);
        }

        void run() {
            while (!queue.isEmpty()) queue.poll().run();
        }

        @Override
        public void documentThread() {}

        @Override
        public void execute(Runnable task) {
            queue.add(task);
        }

        @Override
        public Object device(DeviceSources.Source source, Runnable check) {
            return device;
        }

        @Override
        public void guardDevice(DeviceSources.Source source, Object device, Runnable check) {}

        @Override
        public Object collection(Object device) {
            return collection;
        }

        @Override
        public List<Object> modulators(Object collection) {
            return new ArrayList<>(members);
        }

        @Override
        public UUID id(Object m) {
            return ((Modulator) m).id;
        }

        @Override
        public String name(Object m) {
            return ((Modulator) m).name;
        }

        @Override
        public UUID typeId(Object m) {
            return ((Modulator) m).type;
        }

        @Override
        public int[] cell(Object m) {
            return new int[] {0, ((Modulator) m).y, 1, 2};
        }

        @Override
        public List<Object> children(Object m) {
            return new ArrayList<>(((Modulator) m).children);
        }

        @Override
        public boolean isSource(Object c) {
            return c instanceof Source;
        }

        @Override
        public boolean isTarget(Object c) {
            return c instanceof Param;
        }

        @Override
        public String identifier(Object c) {
            return c instanceof Source ? ((Source) c).id : c instanceof Param ? ((Param) c).id : null;
        }

        @Override
        public Object owner(Object model, ModulatorEditor.Owner kind) {
            Object owner = model instanceof Source
                    ? ((Source) model).owner
                    : model instanceof Param ? ((Param) model).owner : null;
            if (model instanceof Module || model instanceof Modulator) owner = null;
            switch (kind) {
                case MODULATOR:
                    return owner instanceof Modulator ? owner : null;
                case MODULE:
                    return owner instanceof Module ? owner : null;
                default:
                    return device;
            }
        }

        @Override
        public String path(Object model) {
            return "path";
        }

        @Override
        public String kind(Object p) {
            return p instanceof Param ? ((Param) p).kind : null;
        }

        @Override
        public JsonObject value(Object p, Runnable check, Collection<String> unsupported) {
            Param param = (Param) p;
            JsonObject row = new JsonObject();
            row.addProperty("supported", true);
            row.addProperty("kind", param.kind);
            row.addProperty("parameterId", param.id);
            row.addProperty("value", param.value);
            row.addProperty("minimum", param.min);
            row.addProperty("maximum", param.max);
            return row;
        }

        @Override
        public String modelClass(Object model) {
            return model.getClass().getSimpleName();
        }

        @Override
        public List<Object> mappings(Object s) {
            return new ArrayList<>(((Source) s).mappings);
        }

        @Override
        public Object mappingTarget(Object m) {
            return ((Mapping) m).target;
        }

        @Override
        public double amount(Object m) {
            return ((Mapping) m).amount;
        }

        @Override
        public String transfer(Object m) {
            return ((Mapping) m).transfer;
        }

        @Override
        public boolean enabled(Object m) {
            return ((Mapping) m).enabled;
        }

        @Override
        public boolean polyphonic(Object m) {
            return false;
        }

        @Override
        public boolean scaled(Object m) {
            return false;
        }

        @Override
        public double range(Object target) {
            return 1.0;
        }

        @Override
        public boolean canModulate(Object source, Object target) {
            return canModulate && ((Param) target).owner != ((Source) source).owner;
        }

        @Override
        public Object[] gridParameter(DeviceSources.Source source, UUID moduleId, String parameterId, Runnable check) {
            if (!MODULE.equals(moduleId) || !parameterId.equals("DECAY"))
                throw Errors.notApplied(Reason.STALE_CONTEXT, "no such grid control");
            return new Object[] {graph, module, decay};
        }

        @Override
        public String moduleId(Object module) {
            return ((Module) module).id.toString();
        }

        @Override
        public void requireParameterWritable(
                Object device, Object container, Object owner, Object parameter, Runnable check) {
            if (!((Param) parameter).writable) throw Errors.notApplied(Reason.UNSAFE_STATE, "touched");
        }

        @Override
        public void requireStructureWritable(Object device, Object collection, List<Object> members, Runnable check) {}

        @Override
        public ModulatorEditor.Type resolveType(UUID typeId, String typeName) {
            if ("Random".equals(typeName) || RANDOM_TYPE.equals(typeId))
                return new ModulatorEditor.Type(RANDOM_TYPE, "Random");
            throw Errors.notApplied(Reason.TARGET_NOT_FOUND, "no type");
        }

        @Override
        public Runnable prepareSet(Object parameter, String kind, double raw) {
            return () -> {
                if (!ignoreWrites) ((Param) parameter).value = raw;
            };
        }

        @Override
        public Runnable prepareMap(Object source, Object target, double amount, boolean enabled, String transfer) {
            return () -> {
                if (ignoreWrites) return;
                for (Mapping m : ((Source) source).mappings)
                    if (m.target == target) {
                        m.amount = amount;
                        return;
                    }
                ((Source) source).mappings.add(new Mapping((Param) target, amount, transfer, enabled));
            };
        }

        @Override
        public Runnable prepareUnmap(Object source, Object target) {
            return () -> {
                if (!ignoreWrites) ((Source) source).mappings.removeIf(m -> m.target == target);
            };
        }

        @Override
        public Runnable prepareDelete(Object collection, Object modulator) {
            return () -> members.remove(modulator);
        }

        @Override
        public Runnable prepareInsert(Object collection, UUID type, int x, int y, Consumer<String> settled) {
            return () -> {
                if (rejectInsert) {
                    settled.accept("IllegalStateException");
                    return;
                }
                Modulator created = new Modulator(new UUID(7, 9), wrongType ? LFO_TYPE : type, "Random");
                created.y = y;
                created.children.add(new Source("OUT", created));
                members.add(created);
                pendingSettle = settled;
            };
        }
    }

    // ------------------------------------------------------------------ helpers
    static final Runnable OK = () -> {};

    static final class Outcome {
        JsonObject value;
        Throwable error;
    }

    static Outcome outcome() {
        return new Outcome();
    }

    static ModulatorEditor.MapRequest map(String json, boolean unmap) {
        JsonObject p = JsonParser.parseString(json).getAsJsonObject();
        p.add("parent", parent());
        return ModulatorEditor.MapRequest.parse(p, unmap);
    }

    static JsonObject parent() {
        return JsonParser.parseString(
                        "{\"trackId\":\"" + TRACK + "\",\"deviceIndex\":0,\"expectedDeviceName\":\"Poly Grid\"}")
                .getAsJsonObject();
    }

    static String gridTarget() {
        return "{\"moduleId\":\"" + MODULE + "\",\"parameterId\":\"DECAY\"}";
    }

    static void runMap(Fake f, ModulatorEditor.MapRequest r, Outcome o, int[] dispatches) {
        try {
            new ModulatorEditor(f).map(null, r, OK, () -> dispatches[0]++, v -> o.value = v, e -> o.error = e);
        } catch (RpcException error) {
            o.error = error;
        } // pre-dispatch rejections throw synchronously
        f.run();
    }

    // ------------------------------------------------------------------ tests
    @Test
    void theResolverFindsAModulatorByUuidAndItsParameterByIdOrNothing() {
        Fake f = new Fake();
        ModulatorEditor editor = new ModulatorEditor(f);
        DeviceSources.Native.Capture device = new DeviceSources.Native.Capture(null, f.device, null, null, TRACK);
        Object random = editor.owner(device, RANDOM, OK);
        assertSame(f.mod(0), random);
        assertEquals("Random", editor.ownerName(random));
        Object rate = editor.parameter(random, "RATE", OK);
        assertSame(f.mod(0).children.get(1), rate);
        assertNull(editor.parameterName(rate), "the fake's value row carries no name");
        assertNull(editor.parameter(random, "OUT", OK), "an output is not a parameter");
        assertNull(editor.owner(device, new UUID(7, 9), OK), "no such modulator");
    }

    @Test
    void readsParametersOutputsAndDescribedMappings() {
        Fake f = new Fake();
        f.source(0).mappings.add(new Mapping(f.decay, 0.5, "linear", true));
        f.source(1).mappings.add(new Mapping((Param) f.mod(0).children.get(1), -0.25, "cubic", true));
        JsonObject all = new ModulatorEditor(f).get(null, null, OK);
        JsonArray rows = all.getAsJsonArray("modulators");
        assertTrue(
                all.get("count").getAsInt() == 2
                        && rows.size() == 2
                        && all.get("complete").getAsBoolean(),
                "all modulators are listed");
        JsonObject random = rows.get(0).getAsJsonObject();
        assertTrue(
                random.getAsJsonArray("parameters")
                                .get(0)
                                .getAsJsonObject()
                                .get("parameterId")
                                .getAsString()
                                .equals("RATE")
                        && !random.getAsJsonArray("parameters")
                                .get(0)
                                .getAsJsonObject()
                                .has("supported"),
                "typed parameters are listed");
        JsonObject target = random.getAsJsonArray("outputs")
                .get(0)
                .getAsJsonObject()
                .getAsJsonArray("mappings")
                .get(0)
                .getAsJsonObject()
                .getAsJsonObject("target");
        assertTrue(
                target.get("kind").getAsString().equals("grid_module")
                        && target.get("moduleId").getAsString().equals(MODULE.toString())
                        && target.get("parameterId").getAsString().equals("DECAY"),
                "Grid module targets are described by moduleId/parameterId");
        JsonObject chained = rows.get(1)
                .getAsJsonObject()
                .getAsJsonArray("outputs")
                .get(0)
                .getAsJsonObject()
                .getAsJsonArray("mappings")
                .get(0)
                .getAsJsonObject();
        assertTrue(
                chained.getAsJsonObject("target").get("kind").getAsString().equals("modulator")
                        && chained.getAsJsonObject("target")
                                .get("modulatorId")
                                .getAsString()
                                .equals(RANDOM.toString())
                        && chained.get("amount").getAsDouble() == -0.25
                        && chained.get("transfer").getAsString().equals("cubic"),
                "modulator-to-modulator mappings keep amount and transfer");
        JsonObject one = new ModulatorEditor(f).get(null, LFO, OK);
        assertTrue(one.getAsJsonArray("modulators").size() == 1, "modulatorId filters to one row");
        assertRpcError("target_not_found", () -> new ModulatorEditor(f).get(null, new UUID(1, 1), OK));
    }

    @Test
    void mapCreatesWithExactReadbackAndCasFirst() {
        Fake f = new Fake();
        int[] d = {0};
        Outcome o = outcome();
        runMap(
                f,
                map(
                        "{\"modulatorId\":\"" + RANDOM + "\",\"target\":" + gridTarget()
                                + ",\"expectedAmount\":null,\"amount\":0.5}",
                        false),
                o,
                d);
        assertTrue(
                o.error == null
                        && d[0] == 1
                        && f.source(0).mappings.size() == 1
                        && f.source(0).mappings.get(0).amount == 0.5,
                "a new mapping is created once with the exact amount");
        assertTrue(
                o.value.getAsJsonObject("mapping").get("amount").getAsDouble() == 0.5
                        && o.value.get("previousMapping").isJsonNull()
                        && o.value.get("outputId").getAsString().equals("OUT")
                        && o.value.get("mutationDispatched").getAsBoolean(),
                "result carries readback, previous state and the single output id");
        Outcome again = outcome();
        runMap(
                f,
                map(
                        "{\"modulatorId\":\"" + RANDOM + "\",\"target\":" + gridTarget()
                                + ",\"expectedAmount\":null,\"amount\":0.5}",
                        false),
                again,
                d);
        assertTrue(
                again.error instanceof RpcException
                        && ((RpcException) again.error).reason.wire().equals("stale_context")
                        && d[0] == 1,
                "expectedAmount null against an existing mapping rejects before dispatch");
    }

    @Test
    void mapUpdatesAmountOnlyAndRejectsBeforeDispatch() {
        Fake f = new Fake();
        int[] d = {0};
        f.source(0).mappings.add(new Mapping(f.decay, 0.5, "linear", true));
        Outcome update = outcome();
        runMap(
                f,
                map(
                        "{\"modulatorId\":\"" + RANDOM + "\",\"outputId\":\"OUT\",\"target\":" + gridTarget()
                                + ",\"expectedAmount\":0.5,\"amount\":-0.75}",
                        false),
                update,
                d);
        assertTrue(
                update.error == null && f.source(0).mappings.get(0).amount == -0.75 && d[0] == 1,
                "existing mapping amount changes");
        Outcome same = outcome();
        runMap(
                f,
                map(
                        "{\"modulatorId\":\"" + RANDOM + "\",\"target\":" + gridTarget()
                                + ",\"expectedAmount\":-0.75,\"amount\":-0.75}",
                        false),
                same,
                d);
        assertTrue(
                same.error == null
                        && d[0] == 1
                        && !same.value.get("mutationDispatched").getAsBoolean(),
                "same amount is not dispatched");
        Outcome transfer = outcome();
        runMap(
                f,
                map(
                        "{\"modulatorId\":\"" + RANDOM + "\",\"target\":" + gridTarget()
                                + ",\"expectedAmount\":-0.75,\"amount\":0.1,\"transfer\":\"cubic\"}",
                        false),
                transfer,
                d);
        assertTrue(
                code(transfer).equals("invalid_params") && d[0] == 1,
                "transfer of an existing mapping is not silently ignored");
        Outcome self = outcome();
        String selfTarget = "{\"modulatorId\":\"" + RANDOM + "\",\"parameterId\":\"RATE\"}";
        runMap(
                f,
                map(
                        "{\"modulatorId\":\"" + RANDOM + "\",\"target\":" + selfTarget
                                + ",\"expectedAmount\":null,\"amount\":0.2}",
                        false),
                self,
                d);
        assertTrue(
                code(self).equals("unsupported_scope") && d[0] == 1,
                "a pair Bitwig refuses is rejected before dispatch");
        Outcome touched = outcome();
        f.decay.writable = false;
        runMap(
                f,
                map(
                        "{\"modulatorId\":\"" + LFO + "\",\"target\":" + gridTarget()
                                + ",\"expectedAmount\":null,\"amount\":0.2}",
                        false),
                touched,
                d);
        assertTrue(
                code(touched).equals("unsafe_state") && d[0] == 1, "target write context is required before dispatch");
        Outcome missing = outcome();
        f.decay.writable = true;
        runMap(
                f,
                map(
                        "{\"modulatorId\":\"" + LFO + "\",\"outputId\":\"NOPE\",\"target\":" + gridTarget()
                                + ",\"expectedAmount\":null,\"amount\":0.2}",
                        false),
                missing,
                d);
        assertTrue(code(missing).equals("target_not_found") && d[0] == 1, "unknown output ids are not aliased");
        Outcome chained = outcome();
        runMap(
                f,
                map(
                        "{\"modulatorId\":\"" + LFO + "\",\"target\":" + selfTarget
                                + ",\"expectedAmount\":null,\"amount\":0.2,\"transfer\":\"rectify_pos\",\"enabled\":false}",
                        false),
                chained,
                d);
        Mapping m = f.source(1).mappings.get(0);
        assertTrue(
                chained.error == null
                        && d[0] == 2
                        && m.transfer.equals("rectify_pos")
                        && !m.enabled
                        && chained.value
                                .getAsJsonObject("target")
                                .get("kind")
                                .getAsString()
                                .equals("modulator"),
                "a modulator can target another modulator's control with transfer and enabled");
    }

    @Test
    void nativeNoOpIsUnknownNotSuccess() {
        Fake f = new Fake();
        int[] d = {0};
        f.ignoreWrites = true;
        Outcome o = outcome();
        runMap(
                f,
                map(
                        "{\"modulatorId\":\"" + RANDOM + "\",\"target\":" + gridTarget()
                                + ",\"expectedAmount\":null,\"amount\":0.5}",
                        false),
                o,
                d);
        assertTrue(
                code(o).equals("readback_mismatch") && ((RpcException) o.error).unknown && d[0] == 1,
                "a dispatched mapping that does not read back is unknown");
    }

    @Test
    void unmapRequiresExactExpectedAmount() {
        Fake f = new Fake();
        int[] d = {0};
        f.source(0).mappings.add(new Mapping(f.decay, 0.5, "linear", true));
        Outcome wrong = outcome();
        runMap(
                f,
                map(
                        "{\"modulatorId\":\"" + RANDOM + "\",\"target\":" + gridTarget() + ",\"expectedAmount\":0.4}",
                        true),
                wrong,
                d);
        assertTrue(
                code(wrong).equals("stale_context")
                        && d[0] == 0
                        && f.source(0).mappings.size() == 1,
                "wrong expectedAmount never clears");
        Outcome ok = outcome();
        runMap(
                f,
                map(
                        "{\"modulatorId\":\"" + RANDOM + "\",\"target\":" + gridTarget() + ",\"expectedAmount\":0.5}",
                        true),
                ok,
                d);
        assertTrue(
                ok.error == null
                        && d[0] == 1
                        && f.source(0).mappings.isEmpty()
                        && ok.value.get("mapping").isJsonNull()
                        && ok.value
                                        .getAsJsonObject("previousMapping")
                                        .get("amount")
                                        .getAsDouble()
                                == 0.5,
                "unmap clears and reports the previous mapping");
    }

    @Test
    void parameterSetUsesScalarCas() {
        Fake f = new Fake();
        int[] d = {0};
        JsonObject p = JsonParser.parseString("{\"expectedKind\":\"float\",\"expectedValue\":0.5,\"value\":0.75}")
                .getAsJsonObject();
        JsonObject result = new ModulatorEditor(f)
                .setParameter(null, RANDOM, "RATE", GridParameterWriter.Request.parse(p), OK, () -> d[0]++);
        assertTrue(
                d[0] == 1
                        && ((Param) f.mod(0).children.get(1)).value == 0.75
                        && result.get("previousValue").getAsDouble() == 0.5,
                "scalar write applies once with readback");
        assertRpcError(
                "stale_context",
                () -> new ModulatorEditor(f)
                        .setParameter(null, RANDOM, "RATE", GridParameterWriter.Request.parse(p), OK, () -> d[0]++));
        JsonObject far = JsonParser.parseString("{\"expectedKind\":\"float\",\"expectedValue\":0.75,\"value\":2}")
                .getAsJsonObject();
        assertRpcError(
                "invalid_params",
                () -> new ModulatorEditor(f)
                        .setParameter(null, RANDOM, "RATE", GridParameterWriter.Request.parse(far), OK, () -> d[0]++));
        assertRpcError(
                "target_not_found",
                () -> new ModulatorEditor(f)
                        .setParameter(null, RANDOM, "OUT", GridParameterWriter.Request.parse(far), OK, () -> d[0]++));
        assertTrue(d[0] == 1, "rejections never dispatch");
        f.ignoreWrites = true;
        JsonObject next = JsonParser.parseString("{\"expectedKind\":\"float\",\"expectedValue\":0.75,\"value\":0.1}")
                .getAsJsonObject();
        RpcException error = assertThrows(
                RpcException.class,
                () -> new ModulatorEditor(f)
                        .setParameter(null, RANDOM, "RATE", GridParameterWriter.Request.parse(next), OK, () -> d[0]++));
        assertTrue(error.unknown && error.is(Reason.READBACK_MISMATCH), "lost scalar write is unknown");
    }

    @Test
    void insertAppendsAndVerifiesTypeAfterSettlement() {
        Fake f = new Fake();
        int[] d = {0};
        Outcome o = outcome();
        assertRpcError(
                "stale_context",
                () -> new ModulatorEditor(f).insert(null, null, "Random", 3, OK, () -> d[0]++, v -> {}, e -> {}));
        new ModulatorEditor(f).insert(null, null, "Random", 2, OK, () -> d[0]++, v -> o.value = v, e -> o.error = e);
        assertTrue(
                d[0] == 1 && o.value == null && o.error == null,
                "insertion completes only after the native settlement");
        f.pendingSettle.accept(null);
        f.run();
        assertTrue(
                o.error == null
                        && o.value.get("count").getAsInt() == 3
                        && o.value.get("requestedY").getAsInt() == 4
                        && o.value
                                .getAsJsonObject("modulator")
                                .get("typeId")
                                .getAsString()
                                .equals(RANDOM_TYPE.toString())
                        && o.value.getAsJsonObject("modulator").get("index").getAsInt() == 2,
                "appended below the lowest modulator and verified");
        f.pendingSettle.accept(null);
        f.run();
        assertTrue(o.error == null, "a repeated settlement is ignored");
        assertRpcError(
                "target_not_found",
                () -> new ModulatorEditor(f).insert(null, null, "Nope", 3, OK, () -> d[0]++, v -> {}, e -> {}));
    }

    @Test
    void insertFailuresAreUnknownAfterDispatch() {
        Fake f = new Fake();
        f.rejectInsert = true;
        Outcome o = outcome();
        new ModulatorEditor(f).insert(null, RANDOM_TYPE, null, 2, OK, OK, v -> o.value = v, e -> o.error = e);
        f.run();
        assertTrue(code(o).equals("host_refused") && ((RpcException) o.error).unknown, "native rejection is unknown");
        Fake g = new Fake();
        g.wrongType = true;
        Outcome w = outcome();
        new ModulatorEditor(g).insert(null, RANDOM_TYPE, null, 2, OK, OK, v -> w.value = v, e -> w.error = e);
        g.pendingSettle.accept(null);
        g.run();
        assertTrue(code(w).equals("readback_mismatch"), "a different inserted type fails verification");
    }

    @Test
    void deleteVerifiesMembership() {
        Fake f = new Fake();
        int[] d = {0};
        Outcome o = outcome();
        assertRpcError(
                "stale_context",
                () -> new ModulatorEditor(f).delete(null, RANDOM, 1, OK, () -> d[0]++, v -> {}, e -> {}));
        new ModulatorEditor(f).delete(null, RANDOM, 2, OK, () -> d[0]++, v -> o.value = v, e -> o.error = e);
        f.run();
        assertTrue(
                o.error == null
                        && d[0] == 1
                        && f.members.size() == 1
                        && o.value.get("count").getAsInt() == 1,
                "delete removes exactly one");
        assertRpcError(
                "target_not_found",
                () -> new ModulatorEditor(f).delete(null, RANDOM, 1, OK, () -> d[0]++, v -> {}, e -> {}));
    }

    @Test
    void moduleValidationRejectsBeforeQueues() {
        String parent = parent().toString(), target = gridTarget();
        String base = "{\"parent\":" + parent + ",\"modulatorId\":\"" + RANDOM + "\",\"target\":" + target;
        ModulatorEditModule.validateParams(
                "modulators.map",
                JsonParser.parseString(base + ",\"expectedAmount\":null,\"amount\":1}")
                        .getAsJsonObject());
        ModulatorEditModule.validateParams(
                "modulators.unmap",
                JsonParser.parseString(base + ",\"expectedAmount\":-1}").getAsJsonObject());
        for (String bad : List.of(
                base + ",\"expectedAmount\":null,\"amount\":1.5}",
                base + ",\"amount\":0.5}",
                base + ",\"expectedAmount\":null,\"amount\":\"0.5\"}",
                base + ",\"expectedAmount\":null,\"amount\":0.5,\"transfer\":\"log\"}",
                base + ",\"expectedAmount\":null,\"amount\":0.5,\"extra\":1}",
                base + ",\"expectedAmount\":null,\"amount\":0.5,\"enabled\":1}",
                base + ",\"expectedAmount\":null,\"amount\":0.5,\"outputId\":null}"))
            assertRpcError(
                    "invalid_params",
                    () -> ModulatorEditModule.validateParams(
                            "modulators.map", JsonParser.parseString(bad).getAsJsonObject()));
        assertRpcError(
                "invalid_params",
                () -> ModulatorEditModule.validateParams(
                        "modulators.unmap",
                        JsonParser.parseString(base + ",\"expectedAmount\":null}")
                                .getAsJsonObject()));
        String both = "{\"parent\":" + parent + ",\"modulatorId\":\"" + RANDOM + "\",\"target\":{\"moduleId\":\""
                + MODULE + "\",\"modulatorId\":\"" + LFO
                + "\",\"parameterId\":\"X\"},\"expectedAmount\":null,\"amount\":0.5}";
        assertRpcError(
                "invalid_params",
                () -> ModulatorEditModule.validateParams(
                        "modulators.map", JsonParser.parseString(both).getAsJsonObject()));
        ModulatorEditModule.validateParams(
                "modulators.insert",
                JsonParser.parseString("{\"parent\":" + parent + ",\"expectedCount\":0,\"typeName\":\"Random\"}")
                        .getAsJsonObject());
        for (String bad : List.of(
                "{\"parent\":" + parent + ",\"expectedCount\":0}",
                "{\"parent\":" + parent + ",\"expectedCount\":0,\"typeName\":\"Random\",\"typeId\":\"" + RANDOM_TYPE
                        + "\"}",
                "{\"parent\":" + parent + ",\"expectedCount\":-1,\"typeName\":\"Random\"}",
                "{\"parent\":" + parent + ",\"expectedCount\":1.0,\"typeName\":\"Random\"}"))
            assertRpcError(
                    "invalid_params",
                    () -> ModulatorEditModule.validateParams(
                            "modulators.insert", JsonParser.parseString(bad).getAsJsonObject()));
        assertRpcError(
                "invalid_params",
                () -> ModulatorEditModule.validateParams(
                        "modulators.delete",
                        JsonParser.parseString("{\"parent\":" + parent + ",\"modulatorId\":\"" + RANDOM
                                        + "\",\"expectedCount\":0}")
                                .getAsJsonObject()));
        assertRpcError(
                "invalid_params",
                () -> ModulatorEditModule.validateParams(
                        "modulators.parameters.set",
                        JsonParser.parseString(
                                        "{\"parent\":" + parent + ",\"modulatorId\":\"" + RANDOM
                                                + "\",\"parameterId\":\"RATE\",\"expectedKind\":\"string\",\"expectedValue\":\"a\",\"value\":\"b\"}")
                                .getAsJsonObject()));
        ModulatorEditModule.validateParams(
                "modulators.get",
                JsonParser.parseString("{\"parent\":" + parent + "}").getAsJsonObject());
        assertRpcError(
                "invalid_params",
                () -> ModulatorEditModule.validateParams(
                        "modulators.get",
                        JsonParser.parseString("{\"parent\":" + parent + ",\"modulatorId\":\"x\"}")
                                .getAsJsonObject()));
    }

    private static String code(Outcome o) {
        return o.error instanceof RpcException ? ((RpcException) o.error).reason.wire() : String.valueOf(o.error);
    }

    @Test
    void moduleValidationEdgeCases() {
        assertRpcError(
                "unknown_method", () -> ModulatorEditModule.validateParams("modulators.bogus", new JsonObject()));
        JsonObject notObject = JsonParser.parseString("{\"parent\":5}").getAsJsonObject();
        assertRpcError("invalid_params", () -> ModulatorEditModule.validateParams("modulators.get", notObject));
        JsonObject longName = JsonParser.parseString(
                        "{\"parent\":" + parent() + ",\"expectedCount\":0,\"typeName\":\"" + "x".repeat(129) + "\"}")
                .getAsJsonObject();
        assertRpcError("invalid_params", () -> ModulatorEditModule.validateParams("modulators.insert", longName));
        JsonObject longText =
                JsonParser.parseString("{\"k\":\"" + "x".repeat(1025) + "\"}").getAsJsonObject();
        assertRpcError("invalid_params", () -> ModulatorEditor.text(longText, "k"));
    }

    @Test
    void editBindingsAreUnavailableUnderTheHostGate() {
        HostFixture host = new HostFixture();
        TestServices s = host.services;
        ModulatorEditModule module =
                new ModulatorEditModule(s.scheduler, s.sources, s.transportSafety, s.natives, HostFixture.SPECS);
        JsonObject capabilities = module.capabilities(new HostOperation(Long.MAX_VALUE));
        assertTrue(!capabilities.get("available").getAsBoolean(), "the gate keeps edit bindings closed");
    }
}
