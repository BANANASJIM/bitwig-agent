package dev.bitwigagent;

import com.google.gson.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.Consumer;
import static dev.bitwigagent.GridSnapshotReader.Field.*;
import static dev.bitwigagent.GridSnapshotReader.Kind.*;
import static dev.bitwigagent.GridSnapshotReader.Metadata.*;
import static dev.bitwigagent.GridSnapshotReaderTest.Node;
import static dev.bitwigagent.NativeDeviceTarget.Field.*;

/** Semantic host-free tests: native GUI transactions, Undo, persistence and sound are not established here. */
public final class GridModuleNameWriterTest {
    static final UUID TRACK = new UUID(3, 1), MODULE = new UUID(1, 1);
    private static int assertions;
    public static void main(String[] args) {
        rawCasNeverUsesEffectiveOrTypeNames();
        emptyResetUnicodeWhitespaceAndNoop();
        strictStringBoundsRejectBeforeQueues();
        membershipAndCurrentUuidCannotBeRebaselined();
        boundedRealModulesAndOwnershipAreRequired();
        contextAndAllMemberLifecycleGateNaming();
        lateSafetyCancellationAndTargetChangesStayUnknown();
        laterReadbackIsNotSetterAcknowledgement();
        unrelatedBindingsAndModulesStayIndependent();
        reflectedPartialFailurePreservesCauseAndQuarantines();
        pendingAndQuarantinedProcessGuardsBlockNaming();
        System.out.println("GridModuleNameWriterTest: " + assertions + " assertions passed (semantic fake models only)");
    }
    private static void rawCasNeverUsesEffectiveOrTypeNames() {
        for (String wrong : List.of("Union", "Catalog type", " raw")) {
            Fixture f = new Fixture(); f.model.first.put(CUSTOM_NAME, "raw");
            failed(f.start(new GridModuleNameWriter.Request(wrong, wrong)), "stale_context", "not_applied");
            check(f.calls == 0 && f.events.isEmpty(), "even a no-op requires exact raw CAS, never effective/type/trimmed text");
        }
        Fixture f = new Fixture(); f.model.first.put(CUSTOM_NAME, "raw").put(MODULE_NAME, "Native effective");
        HostOperation op = f.start(new GridModuleNameWriter.Request("raw", "changed"));
        check(f.calls == 1 && op.dispatched && !op.result.isDone(), "single setter dispatch is not completion");
        f.next(); JsonObject result = op.result.join();
        check(text(result, "previousCustomName").equals("raw") && text(result, "previousName").equals("Native effective"), "raw and effective observations remain separate");
        check(text(result, "requestedCustomName").equals("changed") && text(result, "actualCustomName").equals("changed"), "actual raw readback accompanies request");
        check(text(result, "operation").equals("rename") && text(result, "moduleId").equals(MODULE.toString()), "result identifies current UUID and operation");
        check(result.get("nativeUndo").getAsBoolean() && !result.get("undoGroupingVerified").getAsBoolean()
                && !result.get("engineAcknowledgement").getAsBoolean(), "native property bookkeeping is not UI Undo grouping or engine acknowledgement");
    }
    private static void emptyResetUnicodeWhitespaceAndNoop() {
        for (String desired : List.of("", "  \t", "Lead 🎹 / Ω\n尾", "x".repeat(1024))) {
            Fixture f = new Fixture(); f.model.first.put(CUSTOM_NAME, "old").put(MODULE_NAME, "old");
            HostOperation op = f.start(new GridModuleNameWriter.Request("old", desired));
            f.next(); JsonObject result = op.result.join();
            check(text(result, "actualCustomName").equals(desired) && f.calls == 1, "empty, whitespace, Unicode and exact bound pass verbatim");
            check(text(result, "actualName").equals(desired.isEmpty() ? "Native reset name" : desired), "reset effective semantics come from native reader, not catalog fallback");
        }
        Fixture f = new Fixture(); f.model.first.put(MODULE_NAME, "Distinct effective");
        HostOperation op = f.start(new GridModuleNameWriter.Request("", ""));
        check(f.calls == 0 && !op.dispatched && !op.result.isDone(), "authorized no-op skips setter but waits for later observation");
        f.next(); JsonObject result = op.result.join();
        check(!result.get("mutationDispatched").getAsBoolean() && !result.get("nativeMutation").getAsBoolean()
                && !result.get("nativeUndo").getAsBoolean() && text(result, "verification").equals("already_matched"), "no-op does not claim native edit");
        check(text(result, "actualName").equals("Distinct effective"), "no-op does not infer effective name from raw empty");
    }
    private static void strictStringBoundsRejectBeforeQueues() {
        for (String key : List.of("expectedCustomName", "customName")) {
            for (JsonElement invalid : Arrays.asList(null, JsonNull.INSTANCE, new JsonPrimitive(7), new JsonPrimitive(false), new JsonArray(), new JsonObject(), new JsonPrimitive("x".repeat(1025)))) {
                JsonObject p = params("", "");
                if (invalid == null) p.remove(key); else p.add(key, invalid);
                expect("invalid_params", () -> GridModuleNameWriter.Request.parse(p));
            }
        }
        expect("invalid_params", () -> new GridModuleNameWriter.Request(null, ""));
        expect("invalid_params", () -> new GridModuleNameWriter.Request("", null));
        expect("invalid_params", () -> new GridModuleNameWriter.Request("", "🎹".repeat(513)));
        GridModuleNameWriter.Request exact = GridModuleNameWriter.Request.parse(params(" ", "🎹".repeat(512)));
        check(exact.expectedCustomName.equals(" ") && exact.customName.length() == 1024, "UTF-16 bound counts surrogate pairs without coercion or trimming");
    }
    private static void membershipAndCurrentUuidCannotBeRebaselined() {
        for (int change = 0; change < 7; change++) {
            Fixture f = new Fixture(); final int kind = change;
            f.beforePrepare = () -> changeMembership(f, kind);
            HostOperation op = f.start(new GridModuleNameWriter.Request("", "new"));
            check(op.result.isCompletedExceptionally() && !op.dispatched && f.calls == 0, "late predispatch membership/currentUUID/CAS changes reject without setter");
            f.quarantine.requireAvailable();
        }
        for (int change = 0; change < 7; change++) {
            Fixture f = new Fixture(); HostOperation op = f.start(new GridModuleNameWriter.Request("", "new"));
            changeMembership(f, change); f.next();
            check(op.result.isCompletedExceptionally() && op.dispatched, "later changed membership/currentUUID/raw never becomes observed success");
            check(error(op).outcome.equals("unknown"), "postdispatch membership failure retains uncertain outcome");
            f.quarantine.requireAvailable();
        }
    }
    private static void changeMembership(Fixture f, int kind) {
        if (kind == 0) f.model.first.put(INSTANCE_ID, new UUID(1, 99));
        if (kind == 1) f.model.second.put(INSTANCE_ID, new UUID(1, 99));
        if (kind == 2) f.model.graph.put(MODULES, List.of(f.model.first));
        if (kind == 3) f.model.graph.put(MODULES, List.of(f.model.first, f.model.module(7, f.model.graph)));
        if (kind == 4) f.model.second.put(MODULE_GRAPH, new Node());
        if (kind == 5) f.model.first.put(CUSTOM_NAME, "intervening");
        if (kind == 6) f.model.proxy.put(TARGET, f.model.owner);
    }
    private static void boundedRealModulesAndOwnershipAreRequired() {
        for (int invalid = 0; invalid < 7; invalid++) {
            Fixture f = new Fixture(); String code = "grid_unavailable";
            if (invalid == 0) f.model.second.put(INSTANCE_ID, MODULE);
            if (invalid == 1) f.model.graph.put(MODULES, List.of(f.model.first, new Node()));
            if (invalid == 2) { f.model.second.put(MODULE_GRAPH, new Node()); code = "stale_context"; }
            if (invalid == 3) { f.model.owner.put(TRACK_UUID, new UUID(3, 2)); code = "stale_context"; }
            if (invalid == 4) f.model.second.threadAllowed = false;
            if (invalid == 5) f.model.first.put(INSTANCE_ID, null);
            if (invalid == 6) f.supported = false;
            failed(f.start(new GridModuleNameWriter.Request("", "new")), code, "not_applied");
            check(f.calls == 0, "ambiguous/opaque/wrong-owner/nonmodule native target rejects before setter");
        }
        Fixture missing = new Fixture();
        failed(missing.start(new UUID(1, 99), new GridModuleNameWriter.Request("", "new")), "stale_context", "not_applied");
        Fixture full = new Fixture(); full.population(128); HostOperation op = full.start(new GridModuleNameWriter.Request("", "new")); full.next();
        check(op.result.join().getAsJsonObject("graph").getAsJsonArray("modules").size() == 128, "128 modules returns full bounded actual graph");
        Fixture large = new Fixture(); large.population(129);
        failed(large.start(new GridModuleNameWriter.Request("", "new")), "grid_bounds_exceeded", "not_applied");
        check(large.calls == 0 && large.model.closed > 0, "over-bound enumeration closes stream before dispatch");
    }
    private static void contextAndAllMemberLifecycleGateNaming() {
        for (boolean noop : List.of(false, true)) {
            for (int invalid = 0; invalid < 13; invalid++) {
                Fixture f = new Fixture();
                if (invalid == 0) f.context.override(f.model.second, NativeReadContext.Field.DELETED, true);
                if (invalid == 1) f.context.override(f.model.second, NativeReadContext.Field.PROJECT, new Object());
                if (invalid == 2) f.context.override(f.model.second, NativeReadContext.Field.CONTEXT, new Object());
                if (invalid == 3) f.context.values.put(NativeReadContext.Field.DISPOSED, true);
                if (invalid == 4) f.context.values.put(NativeReadContext.Field.INITIALIZED, false);
                if (invalid == 5) f.context.values.put(NativeReadContext.Field.CURRENT_DT, new Object());
                if (invalid == 6) f.context.values.put(GridTopologyContext.Field.UNDO_ACTIVE, true);
                if (invalid == 7) f.context.values.put(GridTopologyContext.Field.UNDO_EXECUTING, true);
                if (invalid == 8) f.context.values.put(GridTopologyContext.Field.UNDO_ICL, true);
                if (invalid == 9) f.context.values.put(GridTopologyContext.Field.CIU, true);
                if (invalid == 10) f.context.values.put(GridTopologyContext.Field.PROJECT_ADM, true);
                if (invalid == 11) f.context.values.put(GridTopologyContext.Field.PROJECT_OOK, true);
                if (invalid == 12) f.context.values.put(GridTopologyContext.Field.LOCK_OWNER, new Object());
                HostOperation op = f.start(new GridModuleNameWriter.Request("", noop ? "" : "new"));
                check(op.result.isCompletedExceptionally() && !op.dispatched && f.calls == 0, "all-member lifecycle/context/idle guards apply equally to no-op");
            }
        }
        Fixture changed = new Fixture(); changed.beforePrepare = () -> changed.context.values.put(GridTopologyContext.Field.LOCK, new Object());
        failed(changed.start(new GridModuleNameWriter.Request("", "new")), "stale_context", "not_applied");
        Fixture late = new Fixture(); HostOperation op = late.start(new GridModuleNameWriter.Request("", "new"));
        late.context.override(late.model.second, NativeReadContext.Field.DELETED, true); late.next();
        failed(op, "stale_context", "unknown"); late.quarantine.requireAvailable();
        for (int drift = 0; drift < 6; drift++) {
            Fixture f = new Fixture(); HostOperation pending = f.start(new GridModuleNameWriter.Request("", "new"));
            if (drift == 0) f.context.values.put(NativeReadContext.Field.ROOT, new Object());
            if (drift == 1) f.context.values.put(NativeReadContext.Field.CURRENT_DT, new Object());
            if (drift == 2) f.context.values.put(GridTopologyContext.Field.UNDO, new Object());
            if (drift == 3) f.context.values.put(GridTopologyContext.Field.LOCK, new Object());
            if (drift == 4) f.context.values.put(GridTopologyContext.Field.PROJECT_ADM, true);
            if (drift == 5) f.model.device.put(GRAPH, new Node());
            f.next();
            check(error(pending).outcome.equals("unknown") && f.calls == 1, "later context/target identity or safety drift never acknowledges the setter");
            f.quarantine.requireAvailable();
        }
    }
    private static void lateSafetyCancellationAndTargetChangesStayUnknown() {
        for (String rejection : List.of("cancelled", "deadline_exceeded", "unsafe_state", "stale_context")) {
            Fixture before = new Fixture(); before.beforePrepare = () -> before.requestError = rejection;
            failed(before.start(new GridModuleNameWriter.Request("", "new")), rejection, "not_applied");
            check(before.calls == 0, "fresh safety/cancellation check before dispatch");
            for (boolean noop : List.of(false, true)) {
                Fixture after = new Fixture(); HostOperation op = after.start(new GridModuleNameWriter.Request("", noop ? "" : "new"));
                after.requestError = rejection; after.next();
                failed(op, rejection, noop ? "not_applied" : "unknown"); after.quarantine.requireAvailable();
            }
        }
        Fixture noOpStale = new Fixture(); HostOperation op = noOpStale.start(new GridModuleNameWriter.Request("", ""));
        noOpStale.model.first.put(CUSTOM_NAME, "later"); noOpStale.next(); failed(op, "grid_verification_failed", "not_applied");
        Fixture cancelled = new Fixture(); op = cancelled.start(new GridModuleNameWriter.Request("", "new"));
        cancelled.cancelled = true; cancelled.next(); failed(op, "cancelled", "unknown");
        Fixture abandoned = new Fixture(); op = abandoned.start(new GridModuleNameWriter.Request("", "new"));
        op.result.cancel(false); abandoned.next();
        check(op.result.isCancelled() && abandoned.calls == 1, "later observation cannot resurrect cancelled future");
    }
    private static void laterReadbackIsNotSetterAcknowledgement() {
        Fixture ignored = new Fixture(); ignored.apply = false;
        HostOperation op = ignored.start(new GridModuleNameWriter.Request("", "new")); ignored.next();
        failed(op, "grid_verification_failed", "unknown"); ignored.quarantine.requireAvailable();
        Fixture effective = new Fixture(); effective.onSet = () -> effective.model.first.put(MODULE_NAME, "Native effective != requested");
        op = effective.start(new GridModuleNameWriter.Request("", "new")); effective.next();
        check(text(op.result.join(), "actualName").equals("Native effective != requested"), "reported effective getter is never fabricated from requested raw");
        Fixture readError = new Fixture(); op = readError.start(new GridModuleNameWriter.Request("", "new"));
        readError.model.failures.put(CUSTOM_NAME, HostServices.failure("grid_unavailable", "native getter failed")); readError.next();
        failed(op, "grid_unavailable", "unknown"); readError.quarantine.requireAvailable();
        Fixture enqueue = new Fixture(r -> { throw HostServices.failure("grid_unavailable", "queue failed"); });
        failed(enqueue.start(new GridModuleNameWriter.Request("", "new")), "grid_unavailable", "unknown"); enqueue.quarantine.requireAvailable();
        for (Object invalid : List.of(7, "x".repeat(1025))) {
            Fixture f = new Fixture(); f.model.first.put(CUSTOM_NAME, invalid);
            failed(f.start(new GridModuleNameWriter.Request("", "new")), invalid instanceof String ? "grid_bounds_exceeded" : "grid_unavailable", "not_applied");
        }
    }
    private static void unrelatedBindingsAndModulesStayIndependent() {
        Fixture f = new Fixture(); f.model.parameters = false; f.model.display = false;
        f.model.unavailable.addAll(EnumSet.of(FOOTPRINT, TYPE_NAMES, PARAMETER_NAMES, PORT_NAMES, FLOAT_UNITS, INTEGER_AFFIXES));
        f.model.first.put(WIDTH, null).put(HEIGHT, null);
        f.model.second.put(CUSTOM_NAME, "untouched").put(MODULE_NAME, "Other effective");
        f.model.first.forbidden.add(CHILDREN); f.model.second.forbidden.add(CHILDREN);
        HostOperation op = f.start(new GridModuleNameWriter.Request("", "new")); f.next(); JsonObject result = op.result.join();
        check(f.calls == 1 && f.model.catalogReads == 0, "naming needs no scalar/display/footprint/catalog enumeration binding");
        JsonObject graph = result.getAsJsonObject("graph");
        check(graph.getAsJsonObject("window").get("complete").getAsBoolean() && graph.getAsJsonArray("modules").size() == 2, "all bounded modules returned despite optional metadata limitations");
        JsonObject other = graph.getAsJsonArray("modules").get(1).getAsJsonObject();
        check(text(other, "customName").equals("untouched") && text(other, "name").equals("Other effective"), "unrelated native module names are untouched");
        check(graph.getAsJsonObject("coverage").get("parameters").getAsString().equals("not_requested"), "name result omits parameters explicitly");
        Fixture unavailable = new Fixture(); unavailable.model.unavailable.add(MODULE_NAMES);
        failed(unavailable.start(new GridModuleNameWriter.Request("", "new")), "grid_unavailable", "not_applied");
    }
    private static void reflectedPartialFailurePreservesCauseAndQuarantines() {
        for (Throwable cause : List.of(new IllegalStateException("partial native failure"), new LinkageError("native linkage"),
                HostServices.failure("deadline_exceeded", "native RPC-shaped throwable"))) {
            Fixture f = new Fixture(); f.reflectedFailure = cause;
            HostOperation op = f.start(new GridModuleNameWriter.Request("", "new"));
            failed(op, "grid_unavailable", "unknown");
            check(f.calls == 1 && f.model.first.values.get(CUSTOM_NAME).equals("new") && f.quarantine.quarantined(), "partial setter is not rolled back and poisons future topology writes");
            Throwable nativeError = f.failure.getCause();
            check(nativeError instanceof RpcException && Bitwig613Runtime.isInvocationFailure(nativeError)
                    && nativeError.getCause() instanceof InvocationTargetException && nativeError.getCause().getCause() == cause,
                    "shared quarantine preserves translated reflection boundary and original native throwable identity");
            failed(f.start(new GridModuleNameWriter.Request("new", "later")), "grid_unavailable", "not_applied");
            check(f.calls == 1 && f.events.isEmpty(), "quarantine blocks retry and failed invocation never schedules fake success");
        }
    }
    private static void pendingAndQuarantinedProcessGuardsBlockNaming() {
        Fixture pending = new Fixture(); pending.quarantine.reserve();
        failed(pending.start(new GridModuleNameWriter.Request("", "new")), "grid_unavailable", "not_applied");
        check(pending.calls == 0, "existing insertion pending marker blocks name writes");
    }
    private static JsonObject params(String expected, String desired) {
        JsonObject p = new JsonObject(); p.addProperty("expectedCustomName", expected); p.addProperty("customName", desired); return p;
    }
    private static String text(JsonObject object, String key) { return object.get(key).getAsString(); }
    private static RpcException error(HostOperation op) {
        try { op.result.join(); throw new AssertionError("Expected operation failure"); }
        catch (java.util.concurrent.CompletionException error) {
            if (!(error.getCause() instanceof RpcException)) throw new AssertionError("Expected RpcException", error);
            return (RpcException) error.getCause();
        }
    }
    private static RpcException failed(HostOperation op, String code, String outcome) {
        RpcException error = error(op);
        check(error.code.equals(code) && error.outcome.equals(outcome), "expected " + code + "/" + outcome + ", got " + error.code + "/" + error.outcome);
        return error;
    }
    private static void expect(String code, Runnable action) {
        try { action.run(); throw new AssertionError("Expected " + code); }
        catch (RpcException error) { check(error.code.equals(code), "expected " + code + ", got " + error.code); }
    }
    private static void check(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }

    /** Real writer/context/snapshot semantics, also used by boundary queue tests. */
    static final class Fixture implements GridModuleNameWriter.Access {
        final GridSnapshotReaderTest.Fixture model = new GridSnapshotReaderTest.Fixture();
        final GridSnapshotReaderTest.ModuleContextFixture context = new GridSnapshotReaderTest.ModuleContextFixture();
        final GridTopologyQuarantine quarantine = new GridTopologyQuarantine(new Properties());
        final Deque<Runnable> events = new ArrayDeque<>();
        final Consumer<Runnable> queue;
        final GridModuleNameWriter writer;
        int calls;
        boolean apply = true, cancelled, supported = true;
        String requestError;
        Throwable reflectedFailure;
        Throwable failure;
        Runnable beforePrepare = () -> {}, onSet = () -> {};
        Fixture() { this(null); }
        Fixture(Consumer<Runnable> queue) {
            this.queue = queue == null ? events::add : queue;
            model.first.put(MODULE_NAME, "Union");
            writer = new GridModuleNameWriter(model.reader, model, context.reader, this, quarantine);
        }
        HostOperation start(GridModuleNameWriter.Request request) { return start(MODULE, request); }
        HostOperation start(UUID moduleId, GridModuleNameWriter.Request request) {
            HostOperation op = new HostOperation(Long.MAX_VALUE);
            try {
                edit(moduleId, request, () -> {
                    if (op.result.isDone()) throw HostServices.failure("cancelled", "request ended");
                    if (cancelled) throw HostServices.failure("cancelled", "cancelled");
                    if (requestError != null) throw HostServices.failure(requestError, "request changed");
                }, () -> op.dispatched = true, value -> op.result.complete(value), error -> { failure = error; op.fail(error); });
            } catch (Throwable error) { failure = error; op.fail(error); }
            return op;
        }
        void edit(UUID moduleId, GridModuleNameWriter.Request request, Runnable check, Runnable dispatch,
                Consumer<JsonObject> complete, Consumer<Throwable> fail) {
            writer.rename(model.proxy, TRACK, moduleId, request, check, dispatch, complete, fail);
        }
        void population(int count) {
            List<Object> modules = new ArrayList<>(); modules.add(model.first);
            for (int i = 1; i < count; i++) modules.add(model.module(i, model.graph));
            model.graph.put(MODULES, modules);
        }
        void next() { events.removeFirst().run(); }
        @Override public void requireSupported(Object module) {
            if (!supported || !model.is(GridSnapshotReader.Kind.MODULE, module)) throw HostServices.failure("grid_unavailable", "not a supported native module");
        }
        @Override public Runnable prepareSet(Object module, String customName) {
            beforePrepare.run();
            return () -> {
                calls++;
                if (reflectedFailure != null) {
                    Bitwig613Runtime.invoke("grid_unavailable", PartialSetter.SET, new PartialSetter((Node) module, reflectedFailure), customName);
                    return;
                }
                if (apply) ((Node) module).put(CUSTOM_NAME, customName).put(MODULE_NAME, customName.isEmpty() ? "Native reset name" : customName);
                onSet.run();
            };
        }
        @Override public void execute(Runnable observation) { queue.accept(observation); }
    }
    public static final class PartialSetter {
        static final Method SET = method();
        private final Node module;
        private final Throwable failure;
        PartialSetter(Node module, Throwable failure) { this.module = module; this.failure = failure; }
        private static Method method() {
            try { return PartialSetter.class.getMethod("set", String.class); }
            catch (NoSuchMethodException error) { throw new AssertionError(error); }
        }
        public final void set(String customName) throws Throwable {
            module.put(CUSTOM_NAME, customName); throw failure;
        }
    }
}
