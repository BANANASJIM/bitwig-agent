package dev.bitwigagent.device;

import static dev.bitwigagent.RpcAssert.*;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import dev.bitwigagent.FakeHost;
import dev.bitwigagent.HostBridge;
import dev.bitwigagent.HostFixture;
import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.HostOperation;
import dev.bitwigagent.core.Outcome;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.core.Refs;
import dev.bitwigagent.core.RpcException;
import dev.bitwigagent.core.ValueInput;
import dev.bitwigagent.host.DeviceSources;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Host-free semantic fake of the device-modulator model; does not establish native acceptance. */
public final class ModulatorEditorTest {
    static final UUID TRACK = UUID.fromString(HostFixture.A);
    static final UUID RANDOM = new UUID(7, 1), LFO = new UUID(7, 2);
    static final UUID MODULE = new UUID(5, 1), RANDOM_TYPE = new UUID(8, 1), LFO_TYPE = new UUID(8, 2);

    // ------------------------------------------------------------------ fake model
    static final class Param {
        final String id, kind;
        String name;
        double value;
        final double min, max;
        final Object owner;
        boolean writable = true, outside;
        List<Object[]> options;
        String unit;

        Param(String id, String name, String kind, double value, double min, double max, Object owner) {
            this.id = id;
            this.name = name;
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
        String name;
        final List<Object> children = new ArrayList<>();
        int y;
        boolean materialized = true;

        Modulator(UUID id, UUID type, String name) {
            this.id = id;
            this.type = type;
            this.name = name;
        }
    }

    static final class Module {
        final UUID id;
        final String name;

        Module(UUID id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    /** The native device helper's access: one device object behind the Source's proxy, one owning track. */
    static final class NativeAccess implements DeviceSources.Native.Access {
        final Object device, track = new Object();
        final DeviceSources.Source source;
        boolean retarget;

        NativeAccess(DeviceSources.Source source, Object device) {
            this.source = source;
            this.device = device;
        }

        @Override
        public boolean is(DeviceSources.Native.Kind kind, Object object) {
            return kind == DeviceSources.Native.Kind.DEVICE
                    ? object != track
                    : kind == DeviceSources.Native.Kind.TRACK && object == track;
        }

        @Override
        public Object read(DeviceSources.Native.Field field, Object object) {
            switch (field) {
                case TARGET:
                    return retarget ? new Object() : device;
                case OWNER_TRACK:
                    return track;
                default:
                    return TRACK;
            }
        }

        @Override
        public void documentThread() {}

        @Override
        public void requireModelThread(Object object) {}
    }

    static final class Fake implements ModulatorEditor.Access {
        final Object device = new Object(), collection = new Object(), graph = new Object(), other = new Object();
        final List<Object> members = new ArrayList<>();
        final Module module = new Module(MODULE, "Env");
        final Param decay = new Param("DECAY", "Decay", "float", 0.2, 0, 1, module);
        final Param level = new Param("LEVEL", "Level", "float", 0.4, 0, 1, new Object());
        final Param farAway = new Param("FAR", "Far", "float", 0.4, 0, 1, new Object());
        /** A direct parameter of the device; TEXT is one that is no modulation target. */
        final Param cutoff = new Param("CONTENTS/CUTOFF", "Cutoff", "float", 0.3, 0, 1, device);

        final Object text = new Object();
        final Deque<Runnable> queue = new ArrayDeque<>();
        final NativeAccess nativeAccess;
        final DeviceSources.Native natives;
        int dispatched;
        boolean ignoreWrites, rejectInsert, wrongType, canModulate = true, insertAlso, deleteAlso;
        Consumer<String> pendingSettle;

        Fake(DeviceSources.Source source) {
            nativeAccess = new NativeAccess(source, device);
            natives = new DeviceSources.Native(nativeAccess);
            farAway.outside = true;
            Modulator random = new Modulator(RANDOM, RANDOM_TYPE, "Random");
            random.children.add(new Source("OUT", random));
            random.children.add(new Param("RATE", "Rate", "float", 0.5, 0, 1, random));
            Param mode = new Param("MODE", "Mode", "enum", 0, 0, 2, random);
            mode.options = List.of(new Object[] {0, "Free"}, new Object[] {1, "Sync"}, new Object[] {2, "Sync"});
            random.children.add(mode);
            random.children.add(new Param("STEPS", "Steps", "integer", 3, 0, 8, random));
            random.children.add(new Param("ON", "On", "boolean", 0, 0, 1, random));
            Param mix = new Param("MIX", "Mix", "float", 0.5, 0, 1, random);
            mix.unit = "percent";
            random.children.add(mix);
            Modulator lfo = new Modulator(LFO, LFO_TYPE, "LFO");
            lfo.y = 2;
            lfo.children.add(new Source("LFO", lfo));
            lfo.children.add(new Param("DEPTH", "Depth", "float", 1.0, 0, 1, lfo));
            members.add(random);
            members.add(lfo);
        }

        Modulator mod(int i) {
            return (Modulator) members.get(i);
        }

        Source source(int i) {
            return (Source) mod(i).children.get(0);
        }

        Param param(int modulator, String id) {
            for (Object child : mod(modulator).children)
                if (child instanceof Param && ((Param) child).id.equals(id)) return (Param) child;
            throw new AssertionError(id);
        }

        void run() {
            while (!queue.isEmpty()) queue.poll().run();
        }

        @Override
        public void documentThread() {}

        @Override
        public void provenance(JsonObject out) {
            out.addProperty("nativeVersion", "simulated");
            out.addProperty("nativeJarSha256", "simulated");
        }

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
            return ((Modulator) m).materialized ? new ArrayList<>(((Modulator) m).children) : null;
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
                    return model instanceof Param && ((Param) model).outside ? other : device;
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
            row.addProperty("name", param.name);
            switch (param.kind) {
                case "boolean":
                    row.addProperty("value", param.value != 0);
                    break;
                case "integer":
                case "enum":
                    row.addProperty("value", (int) param.value);
                    break;
                default:
                    row.addProperty("value", param.value);
            }
            row.addProperty("minimum", param.min);
            row.addProperty("maximum", param.max);
            if (param.options != null) {
                JsonArray options = new JsonArray();
                for (Object[] option : param.options) {
                    JsonObject entry = new JsonObject();
                    entry.addProperty("value", (Integer) option[0]);
                    entry.addProperty("label", (String) option[1]);
                    options.add(entry);
                }
                row.add("options", options);
                row.addProperty("valueInOptions", true);
            }
            if (param.unit != null) {
                row.addProperty("numericUnit", param.unit);
                row.addProperty("display", Math.round(param.value * 100) + " %");
            }
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
        public DirectParameters.Lookup direct() {
            return new DirectParameters.Lookup() {
                @Override
                public String[] parameterIds(Object device) {
                    assertSame(Fake.this.device, device);
                    return new String[] {cutoff.id, "CONTENTS/TEXT"};
                }

                @Override
                public Object parameter(Object device, String id) {
                    return id.equals(cutoff.id) ? cutoff : text;
                }

                @Override
                public String parameterName(Object parameter) {
                    return parameter == cutoff ? cutoff.name : "Text";
                }
            };
        }

        @Override
        public Object[] gridParameter(DeviceSources.Source source, UUID moduleId, String parameterId, Runnable check) {
            if (!MODULE.equals(moduleId) || !parameterId.equals("DECAY")) return null;
            return new Object[] {graph, module, decay};
        }

        @Override
        public String moduleId(Object module) {
            return ((Module) module).id.toString();
        }

        @Override
        public String moduleName(Object module) {
            return ((Module) module).name;
        }

        @Override
        public DeviceSources.Native natives() {
            return natives;
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
                        m.transfer = transfer;
                        m.enabled = enabled;
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
            return () -> {
                members.remove(modulator);
                if (deleteAlso) members.remove(members.size() - 1);
            };
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
                if (insertAlso) members.add(new Modulator(new UUID(7, 10), type, "Random"));
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

    /** The fake model, the root device it stands behind and the refs a request carries. */
    static final class Env {
        final HostFixture host = new HostFixture();
        final DeviceSources.Source source;
        final Fake f;
        final ModulatorEditor editor;
        final JsonObject device, random, lfo, decay, rate, depth, cutoff;

        Env() {
            FakeHost bank = host.tracks[0].child("createDeviceBank");
            FakeHost node = bank.item(0);
            node.child("exists").emit(true);
            node.child("position").emit(0);
            node.child("deviceType").emit("instrument");
            node.child("name").emit("Poly Grid");
            bank.child("itemCount").emit(1);
            source = host.services.sources.roots[0][0];
            f = new Fake(source);
            editor = new ModulatorEditor(f);
            device = source.ref();
            random = Refs.modulator(device, RANDOM.toString(), "Random");
            lfo = Refs.modulator(device, LFO.toString(), "LFO");
            decay = Refs.parameter(Refs.gridModule(device, MODULE.toString(), "Env"), "DECAY", "Decay");
            rate = Refs.parameter(random, "RATE", "Rate");
            depth = Refs.parameter(lfo, "DEPTH", "Depth");
            cutoff = Refs.parameter(device, "CONTENTS/CUTOFF", "Cutoff");
        }

        JsonObject read() {
            return editor.read(source, device, 0, 64, null, OK);
        }

        ParameterResolver.ModuleParameter parameter(JsonObject owner, String id, String name) {
            return new ParameterResolver.ModuleParameter(Refs.parameter(owner, id, name), source);
        }

        ModulatorEditor.MapRequest map(JsonObject from, JsonObject to, String more, boolean unmap) {
            JsonObject p = HostFixture.json("{" + more + "}");
            p.add("source", from);
            p.add("target", to);
            return ModulatorEditor.MapRequest.parse(p, unmap);
        }

        Outcome runMap(ModulatorEditor.MapRequest r, int[] dispatches) {
            Outcome o = new Outcome();
            try {
                editor.map(source, device, r, OK, () -> dispatches[0]++, v -> o.value = v, e -> o.error = e);
            } catch (RpcException error) {
                o.error = error;
            }
            f.run();
            return o;
        }

        Outcome upsert(JsonObject from, JsonObject to, String more, int[] d) {
            return runMap(map(from, to, more, false), d);
        }

        Outcome unmap(JsonObject from, JsonObject to, String more, int[] d) {
            return runMap(map(from, to, more, true), d);
        }

        /** setValue with the bare or object value; result, or the thrown error. */
        JsonObject set(JsonObject ref, String valueJson, String expectJson, int[] d) {
            ParameterResolver.ModuleParameter target = new ParameterResolver.ModuleParameter(ref, source);
            ValueInput input = ValueInput.parse("value", JsonParser.parseString(valueJson));
            JsonObject expect = expectJson == null ? null : HostFixture.json(expectJson);
            return editor.setValue(
                    new HostOperation(Long.MAX_VALUE), target, device, ref, input, expect, OK, () -> d[0]++);
        }
    }

    static RpcException failure(Outcome o) {
        assertInstanceOf(RpcException.class, o.error, String.valueOf(o.error));
        return (RpcException) o.error;
    }

    static String code(Outcome o) {
        return o.error instanceof RpcException ? ((RpcException) o.error).reason.wire() : String.valueOf(o.error);
    }

    // ------------------------------------------------------------------ resolver
    @Test
    void theResolverFindsAModulatorByUuidAndItsParameterByIdOrNothing() {
        Env e = new Env();
        Fake f = e.f;
        ModulatorEditor editor = e.editor;
        DeviceSources.Native.Capture device = new DeviceSources.Native.Capture(null, f.device, null, null, TRACK);
        Object random = editor.owner(device, RANDOM, OK);
        assertSame(f.mod(0), random);
        assertEquals("Random", editor.ownerName(random));
        Object rate = editor.parameter(random, "RATE", OK);
        assertSame(f.param(0, "RATE"), rate);
        assertEquals("Rate", editor.parameterName(rate));
        assertNull(editor.parameter(random, "OUT", OK), "an output is not a parameter");
        assertNull(editor.owner(device, new UUID(7, 9), OK), "no such modulator");
    }

    // ------------------------------------------------------------------ read
    @Test
    void readRowsCarryRefsParametersAndMappingTargetsAsRefs() {
        Env e = new Env();
        Fake f = e.f;
        f.source(0).mappings.add(new Mapping(f.decay, 0.5, "linear", true));
        f.source(1).mappings.add(new Mapping(f.param(0, "RATE"), -0.25, "cubic", false));
        f.source(1).mappings.add(new Mapping(f.level, 0.1, "linear", true));
        f.source(1).mappings.add(new Mapping(f.farAway, 0.2, "linear", true));
        JsonObject all = e.read();
        assertEquals(e.device, all.get("target"));
        assertEquals(2, all.get("count").getAsInt());
        assertTrue(all.get("complete").getAsBoolean());
        assertEquals(0, all.getAsJsonArray("unsupported").size());
        assertFalse(all.get("nativeMutation").getAsBoolean());
        JsonArray rows = all.getAsJsonArray("modulators");
        JsonObject random = rows.get(0).getAsJsonObject();
        assertEquals(e.random, random.get("ref"));
        HostFixture.REF_SCHEMA.apply(random.getAsJsonObject("ref"));
        assertEquals(0, random.get("index").getAsInt());
        assertEquals("Random", random.get("name").getAsString());
        assertEquals(RANDOM_TYPE.toString(), random.get("typeId").getAsString());
        assertTrue(random.get("contentsAvailable").getAsBoolean());
        JsonObject rate = random.getAsJsonArray("parameters").get(0).getAsJsonObject();
        assertEquals(e.rate, rate.get("ref"));
        HostFixture.REF_SCHEMA.apply(rate.getAsJsonObject("ref"));
        assertEquals("Rate", rate.get("name").getAsString());
        JsonObject unit = rate.getAsJsonObject("value");
        assertEquals(0.5, unit.get("value").getAsDouble());
        assertEquals("float", unit.get("kind").getAsString());
        for (String internal : List.of("supported", "parameterId", "name")) assertFalse(unit.has(internal), internal);
        JsonArray options = random.getAsJsonArray("parameters")
                .get(1)
                .getAsJsonObject()
                .getAsJsonObject("value")
                .getAsJsonArray("options");
        assertEquals("Free", options.get(0).getAsJsonObject().get("label").getAsString());
        JsonObject out = random.getAsJsonArray("outputs").get(0).getAsJsonObject();
        assertEquals("OUT", out.get("outputId").getAsString());
        JsonObject mapping = out.getAsJsonArray("mappings").get(0).getAsJsonObject();
        JsonObject grid = Refs.parameter(Refs.gridModule(e.device, MODULE.toString(), "Env"), "DECAY", "Decay");
        assertEquals(grid, mapping.get("target"));
        HostFixture.REF_SCHEMA.apply(mapping.getAsJsonObject("target"));
        assertEquals(0.5, mapping.get("amount").getAsDouble());
        assertEquals("linear", mapping.get("transfer").getAsString());
        assertTrue(mapping.get("enabled").getAsBoolean());
        assertFalse(mapping.get("polyphonic").getAsBoolean());
        assertFalse(mapping.get("scaledByAnotherSource").getAsBoolean());
        JsonArray lfoMappings = rows.get(1)
                .getAsJsonObject()
                .getAsJsonArray("outputs")
                .get(0)
                .getAsJsonObject()
                .getAsJsonArray("mappings");
        JsonObject chained = lfoMappings.get(0).getAsJsonObject();
        assertEquals(Refs.parameter(e.random, "RATE", "Rate"), chained.get("target"));
        HostFixture.REF_SCHEMA.apply(chained.getAsJsonObject("target"));
        assertEquals(-0.25, chained.get("amount").getAsDouble());
        assertEquals("cubic", chained.get("transfer").getAsString());
        assertFalse(chained.get("enabled").getAsBoolean());
        assertEquals(
                HostFixture.json("{\"location\":\"device\",\"path\":\"path\",\"parameterId\":\"LEVEL\"}"),
                lfoMappings.get(1).getAsJsonObject().get("target"));
        assertEquals(
                HostFixture.json("{\"location\":\"external\",\"path\":\"path\",\"parameterId\":\"FAR\"}"),
                lfoMappings.get(2).getAsJsonObject().get("target"));
    }

    @Test
    void readPagesAndFiltersToOneModulator() {
        Env e = new Env();
        JsonObject second = e.editor.read(e.source, e.device, 1, 1, null, OK);
        assertEquals(1, second.getAsJsonArray("modulators").size());
        assertEquals(
                e.lfo,
                second.getAsJsonArray("modulators").get(0).getAsJsonObject().get("ref"));
        assertEquals(2, second.get("count").getAsInt());
        assertEquals(HostFixture.json("{\"offset\":1,\"limit\":1,\"total\":2,\"returned\":1}"), second.get("window"));
        assertFalse(second.get("complete").getAsBoolean(), "a later page is not the whole list");
        JsonObject first = e.editor.read(e.source, e.device, 0, 1, null, OK);
        assertFalse(first.get("complete").getAsBoolean(), "a page that leaves modulators out is not complete");
        JsonObject beyond = e.editor.read(e.source, e.device, 5, 64, null, OK);
        assertEquals(0, beyond.getAsJsonArray("modulators").size());
        JsonObject one = e.editor.read(e.source, e.device, 0, 64, LFO, OK);
        assertEquals(1, one.getAsJsonObject("window").get("returned").getAsInt());
        assertTrue(one.get("complete").getAsBoolean(), "the one modulator asked for is everything asked for");
        assertEquals(
                "modulators.list",
                assertRpcError("target_not_found", () -> e.editor.read(e.source, e.device, 0, 64, new UUID(1, 1), OK))
                        .next);
    }

    @Test
    void readReportsWhatItCannotRead() {
        Env e = new Env();
        e.f.mod(0).materialized = false;
        e.f.mod(1).children.add(new Param("TEXT", "Text", null, 0, 0, 0, e.f.mod(1)));
        JsonObject result = e.read();
        assertFalse(result.get("complete").getAsBoolean());
        JsonObject row = result.getAsJsonArray("modulators").get(0).getAsJsonObject();
        assertFalse(row.get("contentsAvailable").getAsBoolean());
        assertFalse(row.has("parameters"));
        List<String> unsupported = new ArrayList<>();
        result.getAsJsonArray("unsupported").forEach(item -> unsupported.add(item.getAsString()));
        assertEquals(List.of("0:contents_unmaterialized", "1:TEXT:Param"), unsupported);
    }

    // ------------------------------------------------------------------ map
    @Test
    void aNewMappingIsLinearAndEnabledAndReadBack() {
        Env e = new Env();
        int[] d = {0};
        Outcome o = e.upsert(e.random, e.decay, "\"amount\":0.5", d);
        assertNull(o.error);
        assertEquals(1, d[0]);
        Mapping made = e.f.source(0).mappings.get(0);
        assertEquals(0.5, made.amount);
        assertEquals("linear", made.transfer);
        assertTrue(made.enabled);
        assertEquals("applied", o.value.get("outcome").getAsString());
        assertEquals("native_readback", o.value.get("verification").getAsString());
        assertEquals("simulated", o.value.get("nativeJarSha256").getAsString());
        assertEquals(
                Refs.parameter(Refs.gridModule(e.device, MODULE.toString(), "Env"), "DECAY", "Decay"),
                o.value.get("ref"));
        HostFixture.REF_SCHEMA.apply(o.value.getAsJsonObject("ref"));
        assertEquals(e.random, o.value.get("source"));
        assertEquals("OUT", o.value.get("outputId").getAsString());
        assertTrue(o.value.get("previousMapping").isJsonNull());
        JsonObject mapping = o.value.getAsJsonObject("mapping");
        assertEquals(0.5, mapping.get("amount").getAsDouble());
        assertEquals("linear", mapping.get("transfer").getAsString());
        assertTrue(mapping.get("enabled").getAsBoolean());
        assertTrue(o.value.get("mutationDispatched").getAsBoolean());
    }

    @Test
    void anExistingMappingKeepsWhatTheRequestLeavesOutAndWritesWhatItChanges() {
        Env e = new Env();
        int[] d = {0};
        e.f.source(0).mappings.add(new Mapping(e.f.decay, 0.5, "cubic", false));
        Outcome amount = e.upsert(e.random, e.decay, "\"amount\":-0.75", d);
        assertNull(amount.error);
        Mapping m = e.f.source(0).mappings.get(0);
        assertEquals(-0.75, m.amount);
        assertEquals("cubic", m.transfer, "an omitted transfer keeps the existing one");
        assertFalse(m.enabled, "an omitted enabled keeps the existing one");
        assertEquals(1, d[0]);
        assertEquals(
                0.5,
                amount.value.getAsJsonObject("previousMapping").get("amount").getAsDouble());
        Outcome transfer = e.upsert(e.random, e.decay, "\"amount\":-0.75,\"transfer\":\"rectify_pos\"", d);
        assertNull(transfer.error);
        assertEquals("rectify_pos", m.transfer);
        assertFalse(m.enabled);
        assertEquals(2, d[0]);
        Outcome enabled = e.upsert(e.random, e.decay, "\"amount\":-0.75,\"enabled\":true", d);
        assertNull(enabled.error);
        assertTrue(m.enabled);
        assertEquals("rectify_pos", m.transfer);
        assertEquals(3, d[0]);
        assertEquals("applied", enabled.value.get("outcome").getAsString());
    }

    @Test
    void theSameMappingIsAlreadyMatchedWithoutDispatch() {
        Env e = new Env();
        int[] d = {0};
        e.f.source(0).mappings.add(new Mapping(e.f.decay, 0.5, "cubic", false));
        Outcome same = e.upsert(e.random, e.decay, "\"amount\":0.5", d);
        assertNull(same.error);
        assertEquals(0, d[0]);
        assertEquals("already_matched", same.value.get("outcome").getAsString());
        assertFalse(same.value.get("mutationDispatched").getAsBoolean());
        Outcome explicit = e.upsert(e.random, e.decay, "\"amount\":0.5,\"transfer\":\"cubic\",\"enabled\":false", d);
        assertEquals("already_matched", explicit.value.get("outcome").getAsString());
        assertEquals(0, d[0]);
    }

    @Test
    void expectAmountComparesTheCurrentMappingBeforeAnyWrite() {
        Env e = new Env();
        int[] d = {0};
        Outcome none = e.upsert(e.random, e.decay, "\"amount\":0.5,\"expect\":{\"amount\":0.5}", d);
        RpcException absent = failure(none);
        assertEquals("target_changed", absent.reason.wire());
        assertEquals(HostFixture.json("{\"amount\":null}"), absent.observed);
        assertTrue(e.f.source(0).mappings.isEmpty());
        e.f.source(0).mappings.add(new Mapping(e.f.decay, 0.5, "linear", true));
        Outcome wrong = e.upsert(e.random, e.decay, "\"amount\":0.9,\"expect\":{\"amount\":0.25}", d);
        RpcException different = failure(wrong);
        assertEquals("target_changed", different.reason.wire());
        assertEquals(HostFixture.json("{\"amount\":0.5}"), different.observed);
        assertEquals(0.5, e.f.source(0).mappings.get(0).amount);
        assertEquals(0, d[0]);
        Outcome match = e.upsert(e.random, e.decay, "\"amount\":0.9,\"expect\":{\"amount\":0.5}", d);
        assertNull(match.error);
        assertEquals(0.9, e.f.source(0).mappings.get(0).amount);
        assertEquals(1, d[0]);
    }

    @Test
    void aMappingIsRefusedBeforeDispatchWhenItsRequestCannotBeApplied() {
        Env e = new Env();
        int[] d = {0};
        assertEquals("invalid_params", code(e.upsert(e.random, e.decay, "\"amount\":1.5", d)));
        Outcome self = e.upsert(e.random, e.rate, "\"amount\":0.2", d);
        assertEquals("unsupported_scope", code(self));
        e.f.decay.writable = false;
        assertEquals("unsafe_state", code(e.upsert(e.lfo, e.decay, "\"amount\":0.2", d)));
        e.f.decay.writable = true;
        assertEquals("target_not_found", code(e.upsert(e.lfo, e.decay, "\"outputId\":\"NOPE\",\"amount\":0.2", d)));
        e.f.mod(1).children.add(new Source("SECOND", e.f.mod(1)));
        RpcException ambiguous = failure(e.upsert(e.lfo, e.decay, "\"amount\":0.2", d));
        assertEquals("invalid_params", ambiguous.reason.wire());
        assertTrue(ambiguous.getMessage().contains("outputId"));
        e.f.mod(1).children.remove(e.f.mod(1).children.size() - 1);
        e.f.canModulate = false;
        assertEquals("unsupported_scope", code(e.upsert(e.lfo, e.decay, "\"amount\":0.2", d)));
        e.f.canModulate = true;
        JsonObject gone = Refs.parameter(Refs.gridModule(e.device, MODULE.toString(), "Env"), "NOPE", null);
        RpcException vanished = failure(e.upsert(e.random, gone, "\"amount\":0.2", d));
        assertEquals("target_not_found", vanished.reason.wire());
        assertEquals("grid.export", vanished.next, "the next read names where the module refs come from");
        JsonObject renamed = Refs.modulator(e.device, e.random.get("id").getAsString(), "Old name");
        RpcException stale = failure(e.upsert(renamed, e.decay, "\"amount\":0.2", d));
        assertEquals("target_changed", stale.reason.wire(), "the source is the write target: its name is compared");
        assertEquals(e.random, stale.current);
        JsonObject missing = Refs.modulator(e.device, new UUID(7, 9).toString(), "Gone");
        assertEquals("target_not_found", code(e.upsert(missing, e.decay, "\"amount\":0.2", d)));
        assertEquals(0, d[0], "none of the refusals dispatched");
    }

    @Test
    void aModulatorCanMapAnotherModulatorsControlWithTransferAndEnabled() {
        Env e = new Env();
        int[] d = {0};
        Outcome o = e.upsert(e.lfo, e.rate, "\"amount\":0.2,\"transfer\":\"rectify_pos\",\"enabled\":false", d);
        assertNull(o.error);
        Mapping m = e.f.source(1).mappings.get(0);
        assertEquals("rectify_pos", m.transfer);
        assertFalse(m.enabled);
        assertEquals(1, d[0]);
        assertEquals(Refs.parameter(e.random, "RATE", "Rate"), o.value.get("ref"));
    }

    @Test
    void theTargetControlNameInTheRefIsCompared() {
        Env e = new Env();
        int[] d = {0};
        e.f.decay.name = "Release";
        RpcException renamed = failure(e.upsert(e.random, e.decay, "\"amount\":0.5", d));
        assertEquals("target_changed", renamed.reason.wire());
        assertNotNull(renamed.current);
        HostFixture.REF_SCHEMA.apply(renamed.current);
        assertEquals("DECAY", renamed.current.get("id").getAsString());
        assertEquals("Release", renamed.current.get("name").getAsString(), "current carries the name now there");
        assertEquals(0, d[0]);
        e.f.param(0, "RATE").name = "Speed";
        assertEquals("target_changed", code(e.upsert(e.lfo, e.rate, "\"amount\":0.5", d)));
        Outcome nameless = e.upsert(
                e.random,
                Refs.parameter(Refs.gridModule(e.device, MODULE.toString(), "Env"), "DECAY", null),
                "\"amount\":0.5",
                d);
        assertNull(nameless.error, "a ref without a name compares none");
        assertEquals(1, d[0]);
    }

    @Test
    void aMappingThatDoesNotReadBackIsUnknown() {
        Env e = new Env();
        int[] d = {0};
        e.f.ignoreWrites = true;
        Outcome o = e.upsert(e.random, e.decay, "\"amount\":0.5", d);
        RpcException error = failure(o);
        assertEquals("readback_mismatch", error.reason.wire());
        assertTrue(error.unknown);
        assertEquals(1, d[0]);
        Env same = new Env();
        same.f.source(0).mappings.add(new Mapping(same.f.decay, 0.5, "linear", true));
        same.f.ignoreWrites = true;
        Outcome lost = same.upsert(same.random, same.decay, "\"amount\":0.75,\"transfer\":\"cubic\"", d);
        assertEquals("readback_mismatch", code(lost));
        assertEquals("linear", same.f.source(0).mappings.get(0).transfer);
    }

    @Test
    void aDeviceParameterIsMappedByItsIdAndReadBackAsADeviceParameterRef() {
        Env e = new Env();
        int[] d = {0};
        Outcome o = e.upsert(e.lfo, e.cutoff, "\"amount\":0.3", d);
        assertNull(o.error);
        assertEquals(1, d[0]);
        assertSame(e.f.cutoff, e.f.source(1).mappings.get(0).target);
        assertEquals("applied", o.value.get("outcome").getAsString());
        assertEquals("native_readback", o.value.get("verification").getAsString());
        assertEquals(e.cutoff, o.value.get("ref"));
        HostFixture.REF_SCHEMA.apply(o.value.getAsJsonObject("ref"));
        JsonObject read = e.read()
                .getAsJsonArray("modulators")
                .get(1)
                .getAsJsonObject()
                .getAsJsonArray("outputs")
                .get(0)
                .getAsJsonObject()
                .getAsJsonArray("mappings")
                .get(0)
                .getAsJsonObject();
        assertEquals(e.cutoff, read.get("target"));
        assertEquals(0.3, read.get("amount").getAsDouble());
        JsonObject unnamed = Refs.parameter(e.device, "CONTENTS/CUTOFF", null);
        assertEquals(
                "already_matched",
                e.upsert(e.lfo, unnamed, "\"amount\":0.3", d)
                        .value
                        .get("outcome")
                        .getAsString());
        assertEquals(1, d[0], "an unchanged mapping is not dispatched");
    }

    @Test
    void aMappingToADeviceParameterIsUnmappedByItsRef() {
        Env e = new Env();
        int[] d = {0};
        e.f.source(1).mappings.add(new Mapping(e.f.cutoff, 0.3, "linear", true));
        Outcome o = e.unmap(e.lfo, e.cutoff, "\"expect\":{\"amount\":0.3}", d);
        assertNull(o.error);
        assertEquals(1, d[0]);
        assertTrue(e.f.source(1).mappings.isEmpty());
        assertEquals(e.cutoff, o.value.get("ref"));
        assertEquals(e.cutoff, o.value.getAsJsonObject("previousMapping").get("target"));
        assertTrue(o.value.get("mapping").isJsonNull());
    }

    @Test
    void aDeviceParameterThatIsGoneRenamedOrNoTargetIsRefusedBeforeDispatch() {
        Env e = new Env();
        int[] d = {0};
        assertEquals(
                "target_not_found",
                code(e.upsert(e.lfo, Refs.parameter(e.device, "CONTENTS/GONE", null), "\"amount\":0.3", d)));
        RpcException renamed =
                failure(e.upsert(e.lfo, Refs.parameter(e.device, "CONTENTS/CUTOFF", "Old"), "\"amount\":0.3", d));
        assertEquals("target_changed", renamed.reason.wire());
        assertEquals(e.cutoff, renamed.current);
        assertEquals(
                "unsupported_scope",
                code(e.upsert(e.lfo, Refs.parameter(e.device, "CONTENTS/TEXT", null), "\"amount\":0.3", d)));
        assertEquals(0, d[0]);
        assertTrue(e.f.source(1).mappings.isEmpty());
    }

    // ------------------------------------------------------------------ unmap
    @Test
    void unmapRemovesAndAMissingMappingIsAlreadyMatched() {
        Env e = new Env();
        int[] d = {0};
        e.f.source(0).mappings.add(new Mapping(e.f.decay, 0.5, "linear", true));
        Outcome ok = e.unmap(e.random, e.decay, "", d);
        assertNull(ok.error);
        assertEquals(1, d[0]);
        assertTrue(e.f.source(0).mappings.isEmpty());
        assertEquals("applied", ok.value.get("outcome").getAsString());
        assertTrue(ok.value.get("mapping").isJsonNull());
        assertEquals(
                0.5, ok.value.getAsJsonObject("previousMapping").get("amount").getAsDouble());
        Outcome again = e.unmap(e.random, e.decay, "", d);
        assertNull(again.error);
        assertEquals(1, d[0], "nothing to remove is not dispatched");
        assertEquals("already_matched", again.value.get("outcome").getAsString());
        assertTrue(again.value.get("previousMapping").isJsonNull());
    }

    @Test
    void unmapExpectMismatchClearsNothing() {
        Env e = new Env();
        int[] d = {0};
        e.f.source(0).mappings.add(new Mapping(e.f.decay, 0.5, "linear", true));
        RpcException wrong = failure(e.unmap(e.random, e.decay, "\"expect\":{\"amount\":0.4}", d));
        assertEquals("target_changed", wrong.reason.wire());
        assertEquals(HostFixture.json("{\"amount\":0.5}"), wrong.observed);
        assertEquals(0, d[0]);
        assertEquals(1, e.f.source(0).mappings.size());
        Outcome ok = e.unmap(e.random, e.decay, "\"expect\":{\"amount\":0.5}", d);
        assertNull(ok.error);
        assertEquals(1, d[0]);
        assertTrue(e.f.source(0).mappings.isEmpty());
        RpcException gone = failure(e.unmap(e.random, e.decay, "\"expect\":{\"amount\":0.5}", d));
        assertEquals(HostFixture.json("{\"amount\":null}"), gone.observed);
    }

    // ------------------------------------------------------------------ structure
    @Test
    void insertAppendsAndVerifiesTypeAfterSettlement() {
        Env e = new Env();
        int[] d = {0};
        Outcome o = new Outcome();
        e.editor.insert(e.source, e.device, null, "Random", OK, () -> d[0]++, v -> o.value = v, x -> o.error = x);
        assertEquals(1, d[0]);
        assertNull(o.value, "insertion completes only after the native settlement");
        assertNull(o.error);
        e.f.pendingSettle.accept(null);
        e.f.run();
        assertNull(o.error);
        JsonObject row = o.value.getAsJsonObject("modulator");
        assertEquals(Refs.modulator(e.device, new UUID(7, 9).toString(), "Random"), o.value.get("ref"));
        HostFixture.REF_SCHEMA.apply(o.value.getAsJsonObject("ref"));
        assertEquals(o.value.get("ref"), row.get("ref"));
        assertEquals(RANDOM_TYPE.toString(), row.get("typeId").getAsString());
        assertEquals(2, row.get("index").getAsInt());
        assertEquals(3, o.value.get("count").getAsInt());
        assertEquals(4, o.value.get("requestedY").getAsInt(), "appended below the lowest modulator");
        assertEquals("Random", o.value.get("typeName").getAsString());
        assertEquals("applied", o.value.get("outcome").getAsString());
        e.f.pendingSettle.accept(null);
        e.f.run();
        assertNull(o.error, "a repeated settlement is ignored");
        assertRpcError(
                "target_not_found",
                () -> e.editor.insert(e.source, e.device, null, "Nope", OK, () -> d[0]++, v -> {}, x -> {}));
        assertEquals(1, d[0]);
    }

    @Test
    void insertFailuresAreUnknownAfterDispatch() {
        Env rejected = new Env();
        rejected.f.rejectInsert = true;
        Outcome o = new Outcome();
        rejected.editor.insert(
                rejected.source, rejected.device, RANDOM_TYPE, null, OK, OK, v -> o.value = v, x -> o.error = x);
        rejected.f.run();
        assertEquals("host_refused", code(o));
        assertTrue(failure(o).unknown, "native rejection is unknown");
        Env wrongType = new Env();
        wrongType.f.wrongType = true;
        Outcome w = new Outcome();
        wrongType.editor.insert(
                wrongType.source, wrongType.device, RANDOM_TYPE, null, OK, OK, v -> w.value = v, x -> w.error = x);
        wrongType.f.pendingSettle.accept(null);
        wrongType.f.run();
        assertEquals("readback_mismatch", code(w), "a different inserted type fails verification");
        Env two = new Env();
        two.f.insertAlso = true;
        Outcome t = new Outcome();
        two.editor.insert(two.source, two.device, RANDOM_TYPE, null, OK, OK, v -> t.value = v, x -> t.error = x);
        two.f.pendingSettle.accept(null);
        two.f.run();
        assertEquals("readback_mismatch", code(t), "two new members are not one insertion");
        assertTrue(failure(t).unknown);
    }

    @Test
    void deleteVerifiesMembershipAndComparesTheName() {
        Env e = new Env();
        int[] d = {0};
        Outcome o = new Outcome();
        e.editor.delete(e.source, e.device, RANDOM, "Random", OK, () -> d[0]++, v -> o.value = v, x -> o.error = x);
        e.f.run();
        assertNull(o.error);
        assertEquals(1, d[0]);
        assertEquals(1, e.f.members.size());
        assertEquals(1, o.value.get("count").getAsInt());
        assertEquals("applied", o.value.get("outcome").getAsString());
        assertRpcError(
                "target_not_found",
                () -> e.editor.delete(e.source, e.device, RANDOM, "Random", OK, () -> d[0]++, v -> {}, x -> {}));
        assertEquals(1, d[0]);
    }

    @Test
    void aRenamedModulatorIsNotDeleted() {
        Env e = new Env();
        int[] d = {0};
        e.f.mod(0).name = "Wobble";
        RpcException renamed = assertRpcError(
                "target_changed",
                () -> e.editor.delete(e.source, e.device, RANDOM, "Random", OK, () -> d[0]++, v -> {}, x -> {}));
        assertEquals(Refs.modulator(e.device, RANDOM.toString(), "Wobble"), renamed.current);
        assertEquals(0, d[0]);
        assertEquals(2, e.f.members.size());
        Outcome o = new Outcome();
        e.editor.delete(e.source, e.device, RANDOM, null, OK, () -> d[0]++, v -> o.value = v, x -> o.error = x);
        e.f.run();
        assertNull(o.error, "without a name to compare the id alone is the target");
        assertEquals(1, e.f.members.size());
    }

    @Test
    void aDeleteThatRemovesMoreThanTheModulatorIsUnknown() {
        Env e = new Env();
        e.f.deleteAlso = true;
        Outcome o = new Outcome();
        e.editor.delete(e.source, e.device, RANDOM, "Random", OK, OK, v -> o.value = v, x -> o.error = x);
        e.f.run();
        assertEquals("readback_mismatch", code(o));
        assertTrue(failure(o).unknown);
    }

    // ------------------------------------------------------------------ modulator control write
    @Test
    void aBareValueIsWrittenAndReadBackForEveryKind() {
        Env e = new Env();
        int[] d = {0};
        JsonObject rate = e.set(e.rate, "0.75", null, d);
        assertEquals(0.75, e.f.param(0, "RATE").value);
        assertEquals("applied", rate.get("outcome").getAsString());
        assertEquals(e.rate, rate.get("ref"));
        assertEquals(
                0.75,
                rate.getAsJsonObject("fields")
                        .getAsJsonObject("value")
                        .get("requested")
                        .getAsDouble());
        assertEquals(0.75, rate.getAsJsonObject("parameter").get("value").getAsDouble());
        assertEquals("float", rate.getAsJsonObject("parameter").get("kind").getAsString());
        assertEquals(
                "applied",
                rate.getAsJsonObject("fields")
                        .getAsJsonObject("value")
                        .get("outcome")
                        .getAsString());
        e.set(Refs.parameter(e.random, "STEPS", "Steps"), "5", null, d);
        assertEquals(5, e.f.param(0, "STEPS").value);
        e.set(Refs.parameter(e.random, "ON", "On"), "true", null, d);
        assertEquals(1, e.f.param(0, "ON").value);
        e.set(Refs.parameter(e.random, "MODE", "Mode"), "2", null, d);
        assertEquals(2, e.f.param(0, "MODE").value);
        assertEquals(4, d[0]);
    }

    @Test
    void aDisplayWritesAFloatAndIsUnsupportedElsewhere() {
        Env e = new Env();
        int[] d = {0};
        JsonObject mix = e.set(Refs.parameter(e.random, "MIX", "Mix"), "{\"display\":\"75 %\"}", null, d);
        assertEquals(0.75, e.f.param(0, "MIX").value, 1e-9);
        assertEquals(
                HostFixture.json("{\"display\":\"75 %\"}"),
                mix.getAsJsonObject("fields").getAsJsonObject("value").get("requested"));
        assertEquals("75 %", mix.getAsJsonObject("parameter").get("display").getAsString());
        assertEquals(1, d[0]);
        assertRpcError(
                "unsupported_unit",
                () -> e.set(Refs.parameter(e.random, "STEPS", "Steps"), "{\"display\":\"5\"}", null, d));
        assertRpcError(
                "unsupported_unit",
                () -> e.set(Refs.parameter(e.random, "MODE", "Mode"), "{\"display\":\"Free\"}", null, d));
        assertRpcError(
                "unsupported_unit", () -> e.set(Refs.parameter(e.random, "ON", "On"), "{\"display\":\"on\"}", null, d));
        RpcException noUnit = assertThrows(RpcException.class, () -> e.set(e.rate, "{\"display\":\"0.5\"}", null, d));
        assertEquals(
                "unsupported_unit",
                noUnit.reason.wire(),
                "a float without a verified unit cannot be written by display");
        assertEquals(1, d[0]);
    }

    @Test
    void aLabelWritesAnEnumByItsUniqueLabel() {
        Env e = new Env();
        int[] d = {0};
        JsonObject mode = Refs.parameter(e.random, "MODE", "Mode");
        e.f.param(0, "MODE").value = 1;
        e.set(mode, "{\"label\":\"Free\"}", null, d);
        assertEquals(0, e.f.param(0, "MODE").value);
        assertEquals(1, d[0]);
        RpcException unknown = assertRpcError("invalid_params", () -> e.set(mode, "{\"label\":\"Tape\"}", null, d));
        assertTrue(
                unknown.getMessage().contains("Free") && unknown.getMessage().contains("Sync"), unknown.getMessage());
        RpcException ambiguous = assertRpcError("invalid_params", () -> e.set(mode, "{\"label\":\"Sync\"}", null, d));
        assertTrue(ambiguous.getMessage().contains("Several"), ambiguous.getMessage());
        assertRpcError("invalid_params", () -> e.set(e.rate, "{\"label\":\"Free\"}", null, d));
        assertEquals(1, d[0]);
    }

    @Test
    void expectValueComparesTheCurrentValueBeforeAnyWrite() {
        Env e = new Env();
        int[] d = {0};
        RpcException mismatch = assertRpcError("target_changed", () -> e.set(e.rate, "0.75", "{\"value\":0.25}", d));
        assertEquals(HostFixture.json("{\"value\":0.5}"), mismatch.observed);
        assertEquals(0.5, e.f.param(0, "RATE").value);
        assertEquals(0, d[0]);
        e.set(e.rate, "0.75", "{\"value\":0.5}", d);
        assertEquals(0.75, e.f.param(0, "RATE").value);
        assertEquals(1, d[0]);
        JsonObject steps = Refs.parameter(e.random, "STEPS", "Steps");
        e.set(steps, "4", "{\"value\":3}", d);
        assertEquals(4, e.f.param(0, "STEPS").value);
        JsonObject on = Refs.parameter(e.random, "ON", "On");
        assertRpcError("target_changed", () -> e.set(on, "true", "{\"value\":true}", d));
        e.set(on, "true", "{\"value\":false}", d);
        assertEquals(1, e.f.param(0, "ON").value);
    }

    @Test
    void aValueWriteIsAlreadyMatchedWithoutDispatchAndRefusesWhatItCannotWrite() {
        Env e = new Env();
        int[] d = {0};
        JsonObject same = e.set(e.rate, "0.5", null, d);
        assertEquals("already_matched", same.get("outcome").getAsString());
        assertEquals(0, d[0]);
        assertFalse(same.get("mutationDispatched").getAsBoolean());
        assertRpcError("invalid_params", () -> e.set(e.rate, "2", null, d));
        e.f.param(0, "RATE").writable = false;
        assertRpcError("unsafe_state", () -> e.set(e.rate, "0.75", null, d));
        e.f.param(0, "RATE").writable = true;
        assertRpcError("target_not_found", () -> e.set(Refs.parameter(e.random, "NOPE", null), "0.5", null, d));
        assertRpcError("target_not_found", () -> e.set(Refs.parameter(e.random, "OUT", null), "0.5", null, d));
        RpcException renamed = assertRpcError(
                "target_changed", () -> e.set(Refs.parameter(e.random, "RATE", "Speed"), "0.5", null, d));
        assertEquals("Rate", renamed.current.get("name").getAsString());
        e.f.param(0, "RATE").name = "Speed";
        assertRpcError("target_changed", () -> e.set(e.rate, "0.75", null, d));
        assertEquals(0, d[0]);
    }

    @Test
    void aValueThatDoesNotReadBackIsUnknown() {
        Env e = new Env();
        int[] d = {0};
        e.f.ignoreWrites = true;
        RpcException lost = assertThrows(RpcException.class, () -> e.set(e.rate, "0.75", null, d));
        assertEquals("readback_mismatch", lost.reason.wire());
        assertTrue(lost.unknown, "a lost scalar write is unknown");
        assertEquals(1, d[0]);
    }

    @Test
    void aValueWriteIsTargetChangedWhenTheNativeDeviceMovesUnderIt() {
        Env e = new Env();
        int[] d = {0};
        e.f.nativeAccess.retarget = true;
        assertRpcError("target_changed", () -> e.set(e.rate, "0.75", null, d));
        assertEquals(0, d[0]);
    }

    // ------------------------------------------------------------------ edit bindings
    @Test
    void editBindingsAreUnavailableUnderTheHostGate() {
        HostFixture host = new HostFixture();
        ModulatorEditModule module = host.services.modulatorEdits(null);
        assertRpcError("native_unavailable", () -> module.editor(new HostOperation(Long.MAX_VALUE)));
    }

    @Test
    void aSchemaFilledRequestWithoutExpectParses() {
        Env e = new Env();
        JsonObject p = new JsonObject();
        p.add("source", e.random);
        p.add("target", e.decay);
        p.addProperty("amount", 0.5);
        ModulatorEditor.MapRequest map =
                ModulatorEditor.MapRequest.parse(HostBridge.checkSchema(HostFixture.SPECS, "modulators.map", p), false);
        assertFalse(map.expected);
        JsonObject q = new JsonObject();
        q.add("source", e.random);
        q.add("target", e.decay);
        assertFalse(
                ModulatorEditor.MapRequest.parse(HostBridge.checkSchema(HostFixture.SPECS, "modulators.unmap", q), true)
                        .expected);
    }
}
