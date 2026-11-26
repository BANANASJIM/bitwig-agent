package dev.bitwigagent;

import static dev.bitwigagent.GridSnapshotReader.Field.*;
import static dev.bitwigagent.GridSnapshotReader.Kind.*;
import static dev.bitwigagent.GridSnapshotReaderTest.ContextFixture;
import static dev.bitwigagent.GridSnapshotReaderTest.Fixture;
import static dev.bitwigagent.GridSnapshotReaderTest.Node;
import static dev.bitwigagent.RpcAssert.*;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** Step-array and device-voicing semantics on fake models; not evidence of native behavior. */
public final class GridStepsVoicingTest {
    private static final UUID TRACK_ID = new UUID(3, 1), MODULE = new UUID(1, 1);

    // ---- step arrays -----------------------------------------------------------------------------------------

    private static float[] ramp(int length) {
        float[] result = new float[length];
        for (int i = 0; i < length; i++) result[i] = i == 0 ? 0.1f : (i % 7) / 8f;
        return result;
    }

    private static Node stepsNode(Fixture f, float[] data, int length) {
        Node node = new Node(STEP_ARRAY)
                .put(OWNER_MODULE, f.first)
                .put(PATH, "steps/data")
                .put(PARAMETER_ID, "DATA")
                .put(STEP_VALUES, data)
                .put(STEP_LENGTH, length);
        node.forbidden.addAll(List.of(
                PARAMETER_NAME,
                SHORT_NAME,
                DISPLAY,
                FLOAT_VALUE,
                FLOAT_DEFINITION,
                BOOLEAN_VALUE,
                INTEGER_VALUE,
                INTEGER_DEFINITION,
                ENUM_VALUE,
                ENUM_DEFINITION,
                BIPOLAR_PANEL,
                STRING_VALUE));
        return node;
    }

    private static Node countNode(Fixture f, int count) {
        Node definition = new Node()
                .put(INTEGER_MIN, 2)
                .put(INTEGER_MAX, 64)
                .put(INTEGER_PREFIX, "")
                .put(INTEGER_SUFFIX, "");
        return f.parameter(INTEGER, f.first)
                .put(PARAMETER_ID, "STEPS")
                .put(INTEGER_VALUE, count)
                .put(INTEGER_DEFINITION, definition);
    }

    private static ContextFixture stepContext(Node steps) {
        ContextFixture context = new ContextFixture();
        context.values.put(GridParameterContext.Field.GUI_STEP_ARRAY, (Supplier<Object>) Object::new);
        context.values.put(GridParameterContext.Field.LOCK_OWNER, context.dt);
        Supplier<Object> forbidden = () -> {
            throw new AssertionError("Step array used scalar permission or GUI property");
        };
        for (GridParameterContext.Field field : List.of(
                GridParameterContext.Field.LICENSED,
                GridParameterContext.Field.TOUCHED,
                GridParameterContext.Field.GUI_BOOLEAN,
                GridParameterContext.Field.GUI_FLOAT,
                GridParameterContext.Field.GUI_INTEGER,
                GridParameterContext.Field.GUI_ENUM)) context.override(steps, field, forbidden);
        return context;
    }

    private static JsonObject inspect(Fixture f, ContextFixture context, String id, boolean full) {
        return f.reader.parameter(f.proxy, TRACK_ID, MODULE, id, context.reader, full, () -> {});
    }

    @Test
    void stepReadsExposeThePrefixAndTheFullArray() {
        Fixture f = new Fixture();
        Node steps = stepsNode(f, ramp(256), 256);
        f.first.put(CHILDREN, List.of(countNode(f, 4), steps));
        ContextFixture context = stepContext(steps);
        JsonObject row = inspect(f, context, "DATA", false).getAsJsonObject("parameter");
        assertTrue(
                row.get("supported").getAsBoolean()
                        && row.get("kind").getAsString().equals("steps")
                        && row.get("controlFamily").getAsString().equals("step_array")
                        && row.get("parameterId").getAsString().equals("DATA"),
                "step array is a supported steps row with its own identity");
        assertTrue(
                row.getAsJsonArray("values").size() == 4
                        && row.get("arrayLength").getAsInt() == 256
                        && row.get("stepCount").getAsInt() == 4
                        && !row.get("valuesComplete").getAsBoolean(),
                "only the first STEPS entries are exposed, the array length stays visible");
        assertTrue(
                row.getAsJsonArray("values").get(0).getAsDouble() == 0.1
                        && row.getAsJsonArray("values").get(1).getAsDouble() == 0.125,
                "float entries use their shortest decimal form, not a widened double");
        assertTrue(
                row.get("minimum").getAsDouble() == -128
                        && row.get("maximum").getAsDouble() == 128
                        && !row.has("options")
                        && !row.has("value"),
                "model value domain is reported, no scalar value or options are invented");
        JsonObject full = inspect(f, context, "DATA", true).getAsJsonObject("parameter");
        assertTrue(
                full.getAsJsonArray("values").size() == 256
                        && full.get("valuesComplete").getAsBoolean(),
                "full exposes all 256 entries");
        JsonObject graph = f.reader
                .snapshot(f.proxy, TRACK_ID, 0, 64, true, false, null, true, () -> {})
                .getAsJsonArray("modules")
                .get(0)
                .getAsJsonObject()
                .getAsJsonArray("parameters")
                .get(1)
                .getAsJsonObject();
        assertTrue(graph.getAsJsonArray("values").size() == 256, "grid.get full reaches the same row");
        JsonObject plain = f.reader.snapshot(f.proxy, TRACK_ID, 0, 64, true, false, null, () -> {});
        assertTrue(
                plain.getAsJsonArray("modules")
                                .get(0)
                                .getAsJsonObject()
                                .getAsJsonArray("parameters")
                                .get(1)
                                .getAsJsonObject()
                                .getAsJsonArray("values")
                                .size()
                        == 4,
                "default grid.get exposes the STEPS prefix");
        assertTrue(!has(plain, "step_count_unavailable"), "a readable STEPS count leaves coverage untouched");
    }

    @Test
    void stepReadsPadShortNativeArraysAndNeedStepCount() {
        Fixture f = new Fixture();
        Node steps = stepsNode(f, new float[] {0.5f, -0.25f}, 8);
        f.first.put(CHILDREN, List.of(steps));
        JsonObject result = inspect(f, stepContext(steps), "DATA", false);
        JsonObject row = result.getAsJsonObject("parameter");
        assertTrue(
                row.getAsJsonArray("values").size() == 8
                        && row.getAsJsonArray("values").get(5).getAsDouble() == 0.0
                        && row.get("stepCount").isJsonNull()
                        && has(result, "step_count_unavailable"),
                "a native array shorter than its declared size reads zero-padded; a missing STEPS count is reported");
        Fixture big = new Fixture();
        Node data = stepsNode(big, ramp(300), 300);
        big.first.put(CHILDREN, List.of(data));
        row = inspect(big, stepContext(data), "DATA", false).getAsJsonObject("parameter");
        assertTrue(
                row.getAsJsonArray("values").size() == GridSnapshotReader.STEP_FALLBACK,
                "without STEPS the exposure is the bounded fallback");
        JsonObject withFull = inspect(big, stepContext(data), "DATA", true);
        assertTrue(
                withFull.getAsJsonObject("parameter").getAsJsonArray("values").size() == 256
                        && has(withFull, "step_array_longer_than_full_window"),
                "arrays longer than 256 expose 256 and say so");
        Fixture bad = new Fixture();
        Node nan = stepsNode(bad, new float[] {Float.NaN}, 1);
        bad.first.put(CHILDREN, List.of(nan));
        assertNotApplied("grid_unavailable", () -> inspect(bad, stepContext(nan), "DATA", false));
    }

    private static final class BgP {}

    private static final class Kiy {}

    private static final class Mystery {}

    @Test
    void internalChildModelsAreNotUnsupported() {
        Fixture f = new Fixture();
        f.first.put(CHILDREN, List.of(new BgP(), new BgP(), new BgP()));
        JsonObject read = f.reader.snapshot(f.proxy, TRACK_ID, 0, 64, true, false, null, () -> {});
        JsonObject module = read.getAsJsonArray("modules").get(0).getAsJsonObject();
        assertTrue(
                module.getAsJsonArray("parameters").size() == 0
                        && module.get("internalChildren").getAsInt() == 3
                        && !has(read, "unknown_child_model"),
                "internal models are counted per module, never listed and never unsupported");
        JsonObject detailed = f.reader.snapshot(f.proxy, TRACK_ID, 0, 64, true, true, null, () -> {});
        JsonObject row = detailed.getAsJsonArray("modules")
                .get(0)
                .getAsJsonObject()
                .getAsJsonArray("parameters")
                .get(0)
                .getAsJsonObject();
        assertTrue(
                !row.get("supported").getAsBoolean()
                        && row.get("reason").getAsString().equals("internal_child_model")
                        && row.get("internal").getAsBoolean()
                        && !has(detailed, "unknown_child_model"),
                "diagnostics lists internal rows, still not as unsupported");
        Fixture mixed = new Fixture();
        mixed.first.put(CHILDREN, List.of(new BgP(), new Kiy(), new Mystery()));
        JsonObject result = mixed.reader.snapshot(mixed.proxy, TRACK_ID, 0, 64, true, false, null, () -> {});
        assertTrue(
                has(result, "unknown_child_model")
                        && !result.get("complete").getAsBoolean()
                        && result.getAsJsonArray("modules")
                                        .get(0)
                                        .getAsJsonObject()
                                        .getAsJsonArray("parameters")
                                        .size()
                                == 2,
                "an unclassified child model stays unsupported, and Kiy is internal only by its fully qualified name");
    }

    private static final class StepFixture implements GridStepArrayWriter.Access {
        final Fixture model = new Fixture();
        final Node steps, count;
        final ContextFixture context;
        final HostOperation op = new HostOperation(Long.MAX_VALUE);
        GridParameterWriter writer;
        int calls;
        boolean apply = true;
        float[] last;
        Runnable afterMutation = () -> {};

        StepFixture(int length, int stepCount) {
            steps = stepsNode(model, ramp(length), length);
            count = countNode(model, stepCount);
            model.first.put(CHILDREN, List.of(count, steps));
            context = stepContext(steps);
            writer = new GridParameterWriter(
                    model.reader,
                    context.reader,
                    new GridParameterWriter.Access() {
                        @Override
                        public void requireSupported(Object parameter, GridSnapshotReader.Kind kind) {
                            throw new AssertionError("Step array must not enter scalar authorization");
                        }

                        @Override
                        public void setRaw(Object parameter, double value) {
                            throw new AssertionError("Step array must not invoke a numeric controller operation");
                        }
                    },
                    null,
                    null,
                    new GridStepArrayWriter(model.reader, context.reader, this));
        }

        float[] live() {
            return (float[]) steps.values.get(STEP_VALUES);
        }

        String json(int from, int to) {
            StringBuilder out = new StringBuilder("[");
            float[] data = live();
            for (int i = from; i < to; i++)
                out.append(i == from ? "" : ",")
                        .append(new java.math.BigDecimal(Float.toString(data[i])).toPlainString());
            return out.append("]").toString();
        }

        JsonObject set(GridParameterWriter.Request request) {
            return writer.set(model.proxy, TRACK_ID, MODULE, "DATA", request, () -> {}, () -> op.dispatched = true);
        }

        RpcException failure(GridParameterWriter.Request request) {
            {
                RpcException error = assertThrows(RpcException.class, () -> set(request));
                return error;
            }
        }

        @Override
        public void requireSupported(Object parameter) {
            if (parameter != steps) throw Errors.notApplied(ErrorCode.GRID_UNAVAILABLE, "unmapped step array");
        }

        @Override
        public float[] current(Object parameter) {
            return GridSnapshotReader.effectiveSteps(
                    steps.values.get(STEP_VALUES), (Integer) steps.values.get(STEP_LENGTH));
        }

        @Override
        public void set(Object parameter, float[] values) {
            calls++;
            last = values.clone();
            if (apply) steps.put(STEP_VALUES, values.clone());
            afterMutation.run();
        }
    }

    private static GridParameterWriter.Request steps(String expected, String values, boolean full) {
        return GridParameterWriter.Request.parse(HostFixture.json("{\"expectedKind\":\"steps\",\"expectedValues\":"
                + expected + ",\"values\":" + values + (full ? ",\"full\":true" : "") + "}"));
    }

    @Test
    void stepWritesPatchThePrefixAndKeepTheRest() {
        StepFixture f = new StepFixture(256, 4);
        float[] before = f.live().clone();
        JsonObject result = f.set(steps(f.json(0, 4), "[0.5,0.1,1.0]", false));
        float[] after = f.live();
        assertTrue(
                f.calls == 1
                        && f.op.dispatched
                        && after.length == 256
                        && after[0] == 0.5f
                        && after[1] == 0.1f
                        && after[2] == 1.0f
                        && after[3] == before[3],
                "a shorter values list patches the prefix and leaves the rest of the exposed entries");
        boolean restSame = true;
        for (int i = 4; i < 256; i++) restSame &= after[i] == before[i];
        assertTrue(restSame, "entries beyond the exposed prefix are unchanged");
        JsonObject row = result.getAsJsonObject("parameter");
        assertTrue(
                row.getAsJsonArray("values").size() == 4
                        && row.getAsJsonArray("values").get(1).getAsDouble() == 0.1
                        && result.get("mutationDispatched").getAsBoolean()
                        && result.get("verification").getAsString().equals("exact_native_step_array_readback")
                        && result.getAsJsonArray("previousValues").size() == 4
                        && result.getAsJsonArray("requestedValues").size() == 3
                        && !result.get("undoGroupingVerified").getAsBoolean()
                        && !result.get("engineAcknowledgement").getAsBoolean()
                        && result.get("nativeUndo").getAsBoolean(),
                "result reads the values back and never claims undo grouping or DSP acknowledgement");
        StepFixture full = new StepFixture(256, 4);
        full.set(steps(full.json(0, 256), "[" + "0.25,".repeat(255) + "-1]", true));
        assertTrue(
                full.calls == 1 && full.live()[255] == -1f && full.live()[100] == 0.25f,
                "full:true writes the whole 256 window");
        StepFixture noop = new StepFixture(256, 4);
        result = noop.set(steps(noop.json(0, 4), noop.json(0, 4), false));
        assertTrue(
                noop.calls == 0
                        && !noop.op.dispatched
                        && !result.get("mutationDispatched").getAsBoolean()
                        && result.get("verification").getAsString().equals("already_matched"),
                "an identical write is a verified no-op without dispatch");
        StepFixture tiny = new StepFixture(2, 2);
        tiny.set(steps(tiny.json(0, 2), "[0.3,0.4]", false));
        assertTrue(
                tiny.live().length == 2 && tiny.live()[1] == 0.4f,
                "an array shorter than 4 entries is written at its own length");
        StepFixture padded = new StepFixture(8, 4);
        padded.steps.put(STEP_VALUES, new float[] {0.5f});
        padded.set(steps("[0.5,0.0,0.0,0.0]", "[0.6]", false));
        assertTrue(
                padded.last.length == 8 && padded.last[0] == 0.6f && padded.last[7] == 0f,
                "a zero-padded native array is written back at its declared length");
    }

    @Test
    void stepWritesAreGuardedCompareAndSet() {
        StepFixture f = new StepFixture(256, 4);
        RpcException error = f.failure(steps("[0.9,0.9,0.9,0.9]", "[0.5]", false));
        assertTrue(
                error.code.equals("stale_context")
                        && error.outcome.equals("not_applied")
                        && f.calls == 0
                        && !f.op.dispatched,
                "a stale expectation is rejected before dispatch, even for a no-op");
        StepFixture shortArray = new StepFixture(4, 4);
        error = shortArray.failure(steps("[0,0,0,0,0]", "[0.5]", false));
        assertTrue(
                error.code.equals("stale_context") && shortArray.calls == 0,
                "expectedValues longer than the native array is stale");
        StepFixture window = new StepFixture(256, 4);
        window.set(steps(window.json(0, 6), "[0.5,0.5,0.5,0.5,0.5,0.5]", false));
        assertTrue(
                window.calls == 1 && window.live()[5] == 0.5f,
                "the CAS window is expectedValues, not the live STEPS value");
        GridParameterWriter.Request scalar = GridParameterWriter.Request.parse(
                HostFixture.json("{\"expectedKind\":\"float\",\"expectedValue\":0.0,\"value\":0.5}"));
        error = f.failure(scalar);
        assertTrue(error.code.equals("stale_context") && f.calls == 0, "a scalar request cannot target a step array");
        StepFixture scalarTarget = new StepFixture(256, 4);
        Node floating = scalarTarget
                .model
                .parameter(FLOAT, scalarTarget.model.first)
                .put(FLOAT_VALUE, 0.0)
                .put(FLOAT_DEFINITION, new Node().put(FLOAT_MIN, 0.0).put(FLOAT_MAX, 1.0));
        scalarTarget.model.first.put(CHILDREN, List.of(scalarTarget.count, scalarTarget.steps, floating));
        scalarTarget.writer = null;
        {
            RpcException caught = assertThrows(RpcException.class, () -> {
                new GridParameterWriter(
                                scalarTarget.model.reader,
                                scalarTarget.context.reader,
                                new GridParameterWriter.Access() {
                                    @Override
                                    public void requireSupported(Object parameter, GridSnapshotReader.Kind kind) {}

                                    @Override
                                    public void setRaw(Object parameter, double value) {
                                        throw new AssertionError("dispatched");
                                    }
                                },
                                null,
                                null)
                        .set(
                                scalarTarget.model.proxy,
                                TRACK_ID,
                                MODULE,
                                "LOCAL_ID",
                                steps("[0.1]", "[0.1]", false),
                                () -> {},
                                () -> {
                                    throw new AssertionError("dispatched");
                                });
            });
            assertTrue(
                    caught.code.equals("stale_context") && caught.outcome.equals("not_applied"),
                    "a steps request cannot target a scalar parameter");
        }
        StepFixture skipped = new StepFixture(256, 4);
        skipped.context.override(skipped.steps, GridParameterContext.Field.CIU, true);
        assertTrue(
                skipped.failure(steps(skipped.json(0, 4), "[0.5]", false)).code.equals("unsafe_state")
                        && skipped.calls == 0,
                "the skip predicate gates step array writes");
        StepFixture undo = new StepFixture(256, 4);
        undo.context.override(undo.steps, GridParameterContext.Field.UNDO_ACTIVE, false);
        undo.context.values.put(GridParameterContext.Field.UNDO_ACTIVE, true);
        assertTrue(
                undo.failure(steps(undo.json(0, 4), "[0.5]", false)).code.equals("unsafe_state") && undo.calls == 0,
                "an active undo change gates step array writes");
        StepFixture settable = new StepFixture(256, 4);
        settable.context.values.put(GridParameterContext.Field.SETTABLE, false);
        assertTrue(
                settable.failure(steps(settable.json(0, 4), "[0.5]", false))
                                .code
                                .equals("unsafe_state")
                        && settable.calls == 0,
                "a non-settable GUI property gates step array writes");
        StepFixture project = new StepFixture(256, 4);
        project.context.override(project.steps, NativeReadContext.Field.PROJECT, new Object());
        assertTrue(
                project.failure(steps(project.json(0, 4), "[0.5]", false)).code.equals("stale_context")
                        && project.calls == 0,
                "owning project mismatch gates step array writes");
    }

    @Test
    void stepWriteFailuresAndBatch() {
        StepFixture unread = new StepFixture(256, 4);
        unread.apply = false;
        RpcException error = unread.failure(steps(unread.json(0, 4), "[0.5]", false));
        assertTrue(
                error.code.equals("grid_verification_failed") && error.outcome.equals("unknown") && unread.calls == 1,
                "an unobserved requested array is not a successful dispatch");
        StepFixture side = new StepFixture(256, 4);
        side.afterMutation = () -> {
            float[] data = side.live().clone();
            data[200] = 0.9f;
            side.steps.put(STEP_VALUES, data);
        };
        error = side.failure(steps(side.json(0, 4), "[0.5]", false));
        assertTrue(
                error.code.equals("grid_verification_failed") && error.outcome.equals("unknown"),
                "a native side effect outside the prefix fails the whole-array readback");
        StepFixture partial = new StepFixture(256, 4);
        partial.afterMutation = () -> {
            throw Errors.notApplied(ErrorCode.GRID_UNAVAILABLE, "native array failure");
        };
        error = partial.failure(steps(partial.json(0, 4), "[0.5]", false));
        assertTrue(
                partial.calls == 1 && partial.op.dispatched,
                "a native exception after dispatch is not retried (the host operation reports unknown)");
        StepFixture absent = new StepFixture(256, 4);
        GridParameterWriter none =
                new GridParameterWriter(absent.model.reader, absent.context.reader, null, null, null);
        {
            RpcException caught = assertThrows(RpcException.class, () -> {
                none.set(
                        absent.model.proxy,
                        TRACK_ID,
                        MODULE,
                        "DATA",
                        steps(absent.json(0, 4), "[0.5]", false),
                        () -> {},
                        () -> {
                            throw new AssertionError("dispatched without a step binding");
                        });
            });
            assertTrue(
                    caught.code.equals("grid_unavailable") && caught.outcome.equals("not_applied"),
                    "a missing step write binding fails closed");
        }
        StepFixture batch = new StepFixture(256, 4);
        GridParameterWriter.Entry entry =
                new GridParameterWriter.Entry(MODULE, "DATA", steps(batch.json(0, 4), "[0.7,0.8]", false));
        JsonObject result = batch.writer.setAll(
                batch.model.proxy, TRACK_ID, List.of(entry), () -> {}, () -> batch.op.dispatched = true);
        JsonObject item = result.getAsJsonArray("writes").get(0).getAsJsonObject();
        assertTrue(
                batch.calls == 1
                        && item.get("status").getAsString().equals("applied")
                        && item.getAsJsonArray("values").size() == 4
                        && item.getAsJsonArray("previousValues").size() == 4
                        && batch.live()[1] == 0.8f,
                "batch entries may write step arrays");
        StepFixture stale = new StepFixture(256, 4);
        GridParameterWriter.Entry bad = new GridParameterWriter.Entry(MODULE, "DATA", steps("[0.9]", "[0.5]", false));
        {
            RpcException caught = assertThrows(
                    RpcException.class,
                    () -> stale.writer.setAll(
                            stale.model.proxy, TRACK_ID, List.of(bad), () -> {}, () -> stale.op.dispatched = true));
            assertTrue(
                    caught.outcome.equals("not_applied") && stale.calls == 0,
                    "a stale step entry fails the batch preflight");
        }
    }

    @Test
    void stepRequestBounds() {
        String max = "[" + "0.5,".repeat(255) + "0.5]";
        steps(max, max, true);
        steps("[0]", "[-128]", false);
        steps("[0]", "[128]", false);
        steps("[7.5e3,-9]", "[0]", false);
        for (String bad :
                List.of("[]", "[" + "0.5,".repeat(256) + "0.5]", "[\"a\"]", "[true]", "[null]", "5", "{}", "[[0]]"))
            assertInvalidParams(() -> steps("[0]", bad, false));
        for (String bad : List.of("[]", "[" + "0.5,".repeat(256) + "0.5]", "[\"a\"]", "[null]", "5"))
            assertInvalidParams(() -> steps(bad, "[0]", false));
        assertInvalidParams(() -> steps("[0]", "[0,0]", false));
        for (String bad : List.of("[128.0000001]", "[-128.0000001]", "[129]", "[1e999]"))
            assertInvalidParams(() -> steps("[0]", bad, false));
        for (String extra : List.of("\"value\":0.5", "\"expectedValue\":0.5", "\"display\":\"1 kHz\""))
            assertInvalidParams(() -> GridParameterWriter.Request.parse(HostFixture.json(
                    "{\"expectedKind\":\"steps\",\"expectedValues\":[0],\"values\":[0]," + extra + "}")));
        assertInvalidParams(() -> GridParameterWriter.Request.parse(
                HostFixture.json("{\"expectedKind\":\"steps\",\"expectedValues\":[0],\"values\":[0],\"full\":1}")));
        assertInvalidParams(() -> GridParameterWriter.Request.parse(
                HostFixture.json("{\"expectedKind\":\"float\",\"expectedValue\":0,\"value\":0,\"values\":[0]}")));
        assertInvalidParams(() -> GridParameterWriter.Request.parse(
                HostFixture.json("{\"expectedKind\":\"float\",\"expectedValue\":0,\"value\":0,\"full\":true}")));
        assertInvalidParams(() -> GridParameterWriter.Request.parse(
                HostFixture.json("{\"expectedKind\":\"steps\",\"expectedValues\":[0]}")));
    }

    // ---- voicing ---------------------------------------------------------------------------------------------

    private static final class VoicingFixture implements GridVoicingWriter.Access {
        final Fixture model = new Fixture();
        final Node voicing = new Node();
        final ContextFixture context = new ContextFixture();
        final HostOperation op = new HostOperation(Long.MAX_VALUE);
        final GridVoicingWriter writer;
        final List<String> calls = new ArrayList<>();
        JsonObject state = JsonParser.parseString(
                        "{\"voices\":1,\"voiceStacking\":1,\"monoMode\":\"true_mono\",\"retriggerMode\":\"on_note_on\","
                                + "\"notePriority\":\"last\",\"legatoGlide\":false,\"stealSameKey\":true,\"allowOverlappingNotes\":false}")
                .getAsJsonObject();
        boolean present = true, apply = true;
        Runnable afterSet = () -> {};

        VoicingFixture(boolean writable) {
            context.values.put(GridParameterContext.Field.GUI_VOICING, (Supplier<Object>) Object::new);
            context.values.put(GridParameterContext.Field.LOCK_OWNER, context.dt);
            Supplier<Object> forbidden = () -> {
                throw new AssertionError("Voicing used the scalar parameter's K2G predicates");
            };
            context.override(voicing, GridParameterContext.Field.LICENSED, forbidden);
            context.override(voicing, GridParameterContext.Field.TOUCHED, forbidden);
            writer = new GridVoicingWriter(model.reader, context.reader, this, writable);
        }

        VoicingFixture() {
            this(true);
        }

        JsonObject get() {
            return writer.get(model.proxy, TRACK_ID, () -> {});
        }

        JsonObject set(String json) {
            return writer.set(model.proxy, TRACK_ID, request(json), () -> {}, () -> op.dispatched = true);
        }

        RpcException failure(String json) {
            {
                RpcException error = assertThrows(RpcException.class, () -> set(json));
                return error;
            }
        }

        @Override
        public Object model(Object device) {
            return present ? voicing : null;
        }

        @Override
        public JsonObject read(Object model) {
            return state.deepCopy();
        }

        @Override
        public void set(Object model, String field, JsonElement value) {
            calls.add(field);
            if (apply) state.add(field, value);
            afterSet.run();
        }
    }

    private static GridVoicingWriter.Request request(String json) {
        return GridVoicingWriter.Request.parse(HostFixture.json(json));
    }

    @Test
    void voicingReadsAndDerivedMode() {
        VoicingFixture f = new VoicingFixture();
        JsonObject result = f.get();
        assertTrue(
                result.get("supported").getAsBoolean()
                        && result.getAsJsonObject("voicing").get("voices").getAsInt() == 1
                        && result.get("mode").getAsString().equals("true_mono")
                        && !result.get("nativeMutation").getAsBoolean()
                        && result.getAsJsonObject("editContext")
                                .get("effectivelyLicensed")
                                .isJsonNull()
                        && !result.getAsJsonObject("editContext")
                                .get("nativeCIu")
                                .getAsBoolean(),
                "one voice plays the stored mono mode; voicing has no scalar license or touch state");
        f.state.addProperty("voices", 8);
        assertTrue(f.get().get("mode").getAsString().equals("poly"), "several voices are poly");
        f.state.addProperty("voices", 1);
        f.state.addProperty("monoMode", "digi_mono");
        assertTrue(
                f.get().get("mode").getAsString().equals("digi_mono"), "digi mono is the alternating-voices mono mode");
        f.present = false;
        result = f.get();
        assertTrue(
                !result.get("supported").getAsBoolean()
                        && result.get("reason").getAsString().contains("no_voicing_model")
                        && !result.has("voicing"),
                "Note Grid and FX Grid report voicing unsupported with a reason");
        assertNotApplied("grid_unavailable", () -> {
            f.present = true;
            f.model.document = false;
            f.get();
        });
    }

    @Test
    void voicingWritesAreGuardedCompareAndSet() {
        VoicingFixture f = new VoicingFixture();
        JsonObject result =
                f.set("{\"expected\":{\"voices\":1,\"voiceStacking\":1},\"set\":{\"voiceStacking\":3,\"voices\":16}}");
        assertTrue(
                f.calls.equals(List.of("voices", "voiceStacking"))
                        && f.op.dispatched
                        && result.getAsJsonObject("voicing").get("voices").getAsInt() == 16
                        && result.get("mode").getAsString().equals("poly")
                        && result.getAsJsonObject("previousVoicing")
                                        .get("voices")
                                        .getAsInt()
                                == 1
                        && result.get("verification").getAsString().equals("exact_native_voicing_readback")
                        && result.getAsJsonArray("changedUnrequested").size() == 0
                        && !result.get("undoGroupingVerified").getAsBoolean()
                        && !result.get("engineAcknowledgement").getAsBoolean(),
                "voicing fields are written in a fixed order and read back exactly");
        VoicingFixture noop = new VoicingFixture();
        result = noop.set("{\"expected\":{\"voices\":1},\"set\":{\"voices\":1}}");
        assertTrue(
                noop.calls.isEmpty()
                        && !noop.op.dispatched
                        && !result.get("mutationDispatched").getAsBoolean()
                        && result.get("verification").getAsString().equals("already_matched"),
                "an identical voicing write is a verified no-op");
        VoicingFixture mixed = new VoicingFixture();
        mixed.set(
                "{\"expected\":{\"voices\":1,\"monoMode\":\"true_mono\",\"retriggerMode\":\"on_note_on\",\"notePriority\":\"last\","
                        + "\"legatoGlide\":false,\"stealSameKey\":true,\"allowOverlappingNotes\":false},\"set\":{\"monoMode\":\"digi_mono\","
                        + "\"retriggerMode\":\"always\",\"notePriority\":\"high\",\"legatoGlide\":true,\"stealSameKey\":false,\"allowOverlappingNotes\":true}}");
        assertTrue(
                mixed.calls.size() == 6
                        && mixed.state.get("monoMode").getAsString().equals("digi_mono")
                        && mixed.state.get("allowOverlappingNotes").getAsBoolean(),
                "enum and boolean voicing fields are writable");
        VoicingFixture extra = new VoicingFixture();
        extra.set("{\"expected\":{\"voices\":1,\"monoMode\":\"true_mono\"},\"set\":{\"voices\":2}}");
        assertTrue(extra.calls.equals(List.of("voices")), "extra expected fields are preconditions only");
        VoicingFixture side = new VoicingFixture();
        side.afterSet = () -> side.state.addProperty("voiceStacking", 4);
        result = side.set("{\"expected\":{\"monoMode\":\"true_mono\"},\"set\":{\"monoMode\":\"digi_mono\"}}");
        assertTrue(
                result.getAsJsonArray("changedUnrequested").size() == 1
                        && result.getAsJsonArray("changedUnrequested")
                                .get(0)
                                .getAsString()
                                .equals("voiceStacking"),
                "a native side effect on an unrequested field is reported");
    }

    @Test
    void voicingFailuresAndBounds() {
        VoicingFixture f = new VoicingFixture();
        RpcException error = f.failure("{\"expected\":{\"voices\":2},\"set\":{\"voices\":3}}");
        assertTrue(
                error.code.equals("stale_context")
                        && error.outcome.equals("not_applied")
                        && f.calls.isEmpty()
                        && !f.op.dispatched,
                "a stale voicing expectation is rejected before dispatch");
        VoicingFixture unread = new VoicingFixture();
        unread.apply = false;
        error = unread.failure("{\"expected\":{\"voices\":1},\"set\":{\"voices\":4}}");
        assertTrue(
                error.code.equals("grid_verification_failed")
                        && error.outcome.equals("unknown")
                        && unread.calls.size() == 1,
                "an unobserved requested voicing is unknown, never retried");
        VoicingFixture thrown = new VoicingFixture();
        thrown.afterSet = () -> {
            throw Errors.notApplied(ErrorCode.GRID_UNAVAILABLE, "native voicing failure");
        };
        {
            RpcException caught = assertThrows(
                    RpcException.class,
                    () -> thrown.set(
                            "{\"expected\":{\"voices\":1,\"voiceStacking\":1},\"set\":{\"voices\":4,\"voiceStacking\":2}}"));
            assertTrue(thrown.calls.size() == 1 && thrown.op.dispatched, "no retry after a native failure");
        }
        VoicingFixture absent = new VoicingFixture();
        absent.present = false;
        error = absent.failure("{\"expected\":{\"voices\":1},\"set\":{\"voices\":4}}");
        assertTrue(
                error.code.equals("unsupported_scope") && error.outcome.equals("not_applied"),
                "a device without voicing rejects writes unsupported");
        VoicingFixture readOnly = new VoicingFixture(false);
        error = readOnly.failure("{\"expected\":{\"voices\":1},\"set\":{\"voices\":4}}");
        assertTrue(
                error.code.equals("grid_unavailable") && readOnly.calls.isEmpty(),
                "unbound write operations fail closed");
        assertTrue(readOnly.get().get("supported").getAsBoolean(), "an unbound write operation leaves reads available");
        VoicingFixture undo = new VoicingFixture();
        undo.context.values.put(GridParameterContext.Field.UNDO_ACTIVE, true);
        assertTrue(
                undo.failure("{\"expected\":{\"voices\":1},\"set\":{\"voices\":4}}")
                                .code
                                .equals("unsafe_state")
                        && undo.calls.isEmpty(),
                "an active undo change gates voicing writes");
        VoicingFixture skipped = new VoicingFixture();
        skipped.context.override(skipped.voicing, GridParameterContext.Field.CIU, true);
        assertTrue(
                skipped.failure("{\"expected\":{\"voices\":1},\"set\":{\"voices\":4}}")
                                .code
                                .equals("unsafe_state")
                        && skipped.calls.isEmpty(),
                "the skip predicate gates voicing writes");
        VoicingFixture project = new VoicingFixture();
        project.context.override(project.voicing, NativeReadContext.Field.PROJECT, new Object());
        assertTrue(
                project.failure("{\"expected\":{\"voices\":1},\"set\":{\"voices\":4}}")
                                .code
                                .equals("stale_context")
                        && project.calls.isEmpty(),
                "owning project mismatch gates voicing writes");
        for (String bad : List.of(
                "{\"expected\":{\"voices\":1},\"set\":{}}",
                "{\"expected\":{},\"set\":{\"voices\":2}}",
                "{\"expected\":{\"voices\":1},\"set\":{\"voices\":0}}",
                "{\"expected\":{\"voices\":1},\"set\":{\"voices\":65}}",
                "{\"expected\":{\"voices\":1},\"set\":{\"voices\":1.5}}",
                "{\"expected\":{\"voices\":1},\"set\":{\"voices\":\"2\"}}",
                "{\"expected\":{\"voiceStacking\":1},\"set\":{\"voiceStacking\":17}}",
                "{\"expected\":{\"voices\":65},\"set\":{\"voices\":2}}",
                "{\"expected\":{\"monoMode\":\"true_mono\"},\"set\":{\"monoMode\":\"poly\"}}",
                "{\"expected\":{\"retriggerMode\":\"never\"},\"set\":{\"retriggerMode\":\"sometimes\"}}",
                "{\"expected\":{\"legatoGlide\":false},\"set\":{\"legatoGlide\":0}}",
                "{\"expected\":{\"bogus\":1},\"set\":{\"bogus\":2}}",
                "{\"set\":{\"voices\":2}}",
                "{\"expected\":{\"voices\":1}}")) assertInvalidParams(() -> request(bad));
        request("{\"expected\":{\"voices\":64,\"voiceStacking\":16},\"set\":{\"voices\":1,\"voiceStacking\":1}}");
    }

    // ---- RPC boundary ----------------------------------------------------------------------------------------

    @Test
    void moduleValidationAndCapabilities() {
        String target = "\"trackId\":\"" + TRACK_ID + "\",\"deviceIndex\":0,\"expectedDeviceName\":\"Poly Grid\",";
        String module = "\"moduleId\":\"" + MODULE + "\",\"parameterId\":\"DATA\",";
        GridModule.validateParams(
                "grid.parameters.set",
                HostFixture.json("{" + target + module
                        + "\"expectedKind\":\"steps\",\"expectedValues\":[0,0],\"values\":[0.5],\"full\":true}"));
        GridModule.validateParams(
                "grid.parameters.set",
                HostFixture.json("{" + target + "\"writes\":[{" + module
                        + "\"expectedKind\":\"steps\",\"expectedValues\":[0],\"values\":[0.5]},{"
                        + module.replace("DATA", "OTHER")
                        + "\"expectedKind\":\"float\",\"expectedValue\":0,\"value\":1}]}"));
        GridModule.validateParams(
                "grid.parameters.get",
                HostFixture.json("{" + target + module.substring(0, module.length() - 1) + ",\"full\":true}"));
        GridModule.validateParams(
                "grid.get", HostFixture.json("{" + target.substring(0, target.length() - 1) + ",\"full\":true}"));
        for (String bad : List.of(
                "\"expectedKind\":\"steps\",\"expectedValue\":0,\"value\":0.5",
                "\"expectedKind\":\"steps\",\"expectedValues\":[0]",
                "\"expectedKind\":\"float\",\"expectedValue\":0,\"values\":[0.5]"))
            assertInvalidParams(() -> GridModule.validateParams(
                    "grid.parameters.set", HostFixture.json("{" + target + module + bad.substring(0) + "}")));
        assertInvalidParams(() -> GridModule.validateParams(
                "grid.parameters.get",
                HostFixture.json("{" + target + module.substring(0, module.length() - 1) + ",\"full\":\"yes\"}")));
        GridModule.validateParams(
                "grid.voicing.get", HostFixture.json("{" + target.substring(0, target.length() - 1) + "}"));
        GridModule.validateParams(
                "grid.voicing.set",
                HostFixture.json("{" + target + "\"expected\":{\"voices\":1},\"set\":{\"voices\":4}}"));
        assertInvalidParams(() -> GridModule.validateParams(
                "grid.voicing.set", HostFixture.json("{" + target + "\"expected\":{\"voices\":1}}")));
        assertInvalidParams(
                () -> GridModule.validateParams("grid.voicing.get", HostFixture.json("{" + target + "\"extra\":1}")));
        assertTrue(
                GridModule.METHODS.contains("grid.voicing.get") && GridModule.METHODS.contains("grid.voicing.set"),
                "voicing methods are registered");
        JsonObject full = new JsonObject();
        for (String key : List.of("stepArrays", "voicing")) {
            JsonObject entry = new JsonObject();
            entry.addProperty("available", true);
            entry.addProperty("writeAvailable", false);
            entry.addProperty("writeReason", "no setter");
            full.add(key, entry);
        }
        JsonObject compact = GridModule.compactCapabilities(full);
        assertTrue(
                !compact.getAsJsonObject("writers").get("stepArrays").getAsBoolean()
                        && !compact.getAsJsonObject("writers").get("voicing").getAsBoolean()
                        && compact.getAsJsonObject("unavailable").has("voicing"),
                "compact capabilities report step-array and voicing write availability");
    }

    // ---- helpers ---------------------------------------------------------------------------------------------

    private static boolean has(JsonObject result, String reason) {
        return result.getAsJsonArray("unsupported").contains(new JsonPrimitive(reason));
    }
}
