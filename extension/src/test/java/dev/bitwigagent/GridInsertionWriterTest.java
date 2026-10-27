package dev.bitwigagent;

import com.google.gson.*;
import java.util.*;
import java.util.function.Consumer;
import static dev.bitwigagent.GridSnapshotReader.Field.*;
import static dev.bitwigagent.GridSnapshotReader.Kind.*;
import static dev.bitwigagent.GridSnapshotReaderTest.Node;
import static dev.bitwigagent.NativeDeviceTarget.Field.*;

/** Host-free behavioral lifecycle tests, discovered by scripts/test-java.py. */
public final class GridInsertionWriterTest {
    private static int assertions;
    static final UUID TYPE = new UUID(2, 1), TRACK = new UUID(3, 1);
    public static void main(String[] args) {
        processOwnersSurviveReplacementAndRejectMalformedMarkers();
        insertionNeedsLaterSettlementAndReturnsReflow();
        lateRequestsStillCleanAndSettle();
        earlyErrorsDuplicatesAndAbsentCallbacksRemainBlocked();
        capacityCatalogAndDetachedPreflight();
        geometryAndFreshSnapshotsRejectBeforePlacement();
        nativeFailuresPoisonButDeliveryFailuresDoNot();
        reflectedConversionFailuresCleanBeforeStickyQuarantine();
        allTopologyWritersConsultPendingLifetime();
        System.out.println("GridInsertionWriterTest: " + assertions + " assertions passed (semantic fake native lifecycle only)");
    }
    private static void processOwnersSurviveReplacementAndRejectMalformedMarkers() {
        Properties process = new Properties();
        GridTopologyQuarantine original = new GridTopologyQuarantine(process), replacement = new GridTopologyQuarantine(process);
        original.requireAvailable(); String a = original.reserve();
        expect("grid_unavailable", replacement::requireAvailable);
        check(!replacement.release(UUID.randomUUID().toString()), "unrelated owner cannot release pending lifetime");
        check(original.release(a), "exact owner can settle");
        String b = replacement.reserve();
        check(!original.release(a), "stale completion cannot release successor");
        replacement.requireOwner(b); original.poison();
        check(!replacement.release(b) && replacement.quarantined(), "owner completion cannot clear sticky process quarantine");
        check(process.size() == 1 && process.get(GridTopologyQuarantine.KEY) instanceof String,
                "only a namespaced String marker enters shared Properties");
        for (Object invalid : List.of("v2:idle", "v1:pending:bad", "v1:unknown", Integer.valueOf(1))) {
            Properties bad = new Properties(); bad.put(GridTopologyQuarantine.KEY, invalid);
            GridTopologyQuarantine guard = new GridTopologyQuarantine(bad);
            expect("grid_unavailable", guard::requireAvailable);
            check(bad.get(GridTopologyQuarantine.KEY) == invalid && guard.quarantined(), "unknown/non-String state is never overwritten or admitted");
        }
        GridTopologyQuarantine denied = new GridTopologyQuarantine(() -> { throw new SecurityException("denied"); });
        expect("grid_unavailable", denied::requireAvailable);
        check(denied.quarantined() && denied.blockedReason() != null, "inaccessible process Properties cannot become availability");
        Properties readDenied = new Properties() { @Override public synchronized Object get(Object key) { throw new SecurityException("read"); } };
        expect("grid_unavailable", new GridTopologyQuarantine(readDenied)::reserve);
        Properties writeDenied = new Properties() { @Override public synchronized Object setProperty(String key, String value) { throw new SecurityException("write"); } };
        expect("grid_unavailable", new GridTopologyQuarantine(writeDenied)::requireAvailable);
    }
    private static void insertionNeedsLaterSettlementAndReturnsReflow() {
        Fixture f = new Fixture(); f.reflow = true; f.normalization = 1;
        HostOperation op = f.start(new GridInsertionWriter.Request(TYPE, 8, 3)); // Anchor is inside existing footprint: legal for insertion.
        check(op.dispatched && f.loads == 1 && f.placements == 0 && !op.result.isDone(), "loader dispatch precedes callback and is not completion");
        expect("grid_unavailable", f.guard::requireAvailable);
        f.deliver();
        check(f.placements == 1 && f.cleanups == 0 && f.successes == 1 && !op.result.isDone(), "placement/success return still waits for ordinary event");
        expect("grid_unavailable", new GridTopologyQuarantine(f.process)::requireAvailable);
        f.next(); JsonObject result = op.result.join(); f.guard.requireAvailable();
        check(result.get("loaderDispatched").getAsBoolean() && result.get("placementDispatched").getAsBoolean()
                && result.get("nativeLifetimeSettled").getAsBoolean(), "separate loader/placement/settlement scopes reported");
        check(result.get("moduleId").getAsString().equals(new UUID(1, 1025).toString()) && result.get("typeId").getAsString().equals(TYPE.toString()), "actual new identity/type returned");
        check(result.getAsJsonObject("requestedPosition").get("x").getAsInt() == 8
                && result.getAsJsonObject("actualPosition").get("x").getAsInt() == 9 && !result.get("matchesRequested").getAsBoolean(), "normalization is observed, not request echo");
        JsonArray modules = result.getAsJsonObject("graph").getAsJsonArray("modules");
        check(modules.size() == 3 && modules.get(0).getAsJsonObject().get("x").getAsInt() == 9,
                "actual complete graph includes rightward reflow of existing modules");
        check(!result.get("undoGroupingVerified").getAsBoolean() && !result.get("engineAcknowledgement").getAsBoolean(), "settlement is not commit/DSP proof");
    }
    private static void lateRequestsStillCleanAndSettle() {
        for (int abandoned = 0; abandoned < 4; abandoned++) {
            Fixture f = new Fixture(); HostOperation op = f.start();
            if (abandoned == 0) f.requestError = "deadline_exceeded";
            if (abandoned == 1) op.result.cancel(false);
            if (abandoned == 2) op.result.completeExceptionally(HostServices.failure("unavailable", "RPC closed"));
            if (abandoned == 3) f.requestError = "unavailable";
            f.deliver();
            check(f.cleanups == 1 && f.placements == 0 && f.successes == 0 && !f.guard.quarantined(), "first delivered rejected callback cleans once despite ended RPC");
            check(f.order.equals(List.of("load", "cleanup", "enqueue")), "native cleanup returns before outward error notification");
            expect("grid_unavailable", f.guard::requireAvailable);
            f.next(); f.guard.requireAvailable();
            check(f.order.get(f.order.size() - 1).equals("failure") && op.result.isCompletedExceptionally(), "independent healthy native settlement releases while request stays failed");
            if (abandoned == 0) check(TransportMixFixture.error(op, "deadline_exceeded").outcome.equals("unknown"), "expired insertion retains native-loader uncertainty");
        }
        Fixture afterPlacement = new Fixture(); HostOperation op = afterPlacement.start(); afterPlacement.deliver();
        afterPlacement.requestError = "deadline_exceeded"; afterPlacement.next(); afterPlacement.guard.requireAvailable();
        check(afterPlacement.placements == 1 && TransportMixFixture.error(op, "deadline_exceeded").outcome.equals("unknown"), "expired delivery never undoes settled native lifetime");
    }
    private static void earlyErrorsDuplicatesAndAbsentCallbacksRemainBlocked() {
        Fixture absent = new Fixture(); HostOperation missing = absent.start(); missing.result.cancel(false);
        check(absent.guard.blockedReason() != null && !absent.guard.quarantined(), "missing callback retains pending lifetime even after future cancellation");
        Fixture early = new Fixture(); HostOperation op = early.start(); early.callback.earlyError("loader rejected");
        check(TransportMixFixture.error(op, "grid_unavailable").outcome.equals("unknown") && early.guard.quarantined(), "early String error is unknown, not native cleanup acknowledgement");
        early.deliver(); early.next();
        check(early.cleanups == 1 && early.placements == 0 && early.guard.quarantined(), "later first delivery after early error still cleans but cannot unpoison");
        Fixture duplicate = new Fixture(); duplicate.start(); duplicate.deliver(); duplicate.deliver(); duplicate.next();
        check(duplicate.placements == 1 && duplicate.successes == 1 && duplicate.cleanups == 0 && duplicate.guard.quarantined(), "duplicate native delivery cannot place/clean twice or settle normally");
        Fixture rejectedDuplicate = new Fixture(); rejectedDuplicate.start(); rejectedDuplicate.requestError = "deadline_exceeded";
        rejectedDuplicate.deliver(); rejectedDuplicate.deliver(); rejectedDuplicate.next();
        check(rejectedDuplicate.cleanups == 1 && rejectedDuplicate.guard.quarantined(), "duplicate rejected callback does not clean twice");
        Fixture stale = new Fixture(); stale.start(); stale.deliver(); stale.next(); String successor = stale.guard.reserve();
        stale.deliver(); check(stale.guard.quarantined() && !stale.guard.release(successor), "stale callback poisons rather than releasing a successor owner");
    }
    private static void capacityCatalogAndDetachedPreflight() {
        Fixture allowed = new Fixture(); allowed.population(127); HostOperation op = allowed.start(); allowed.deliver(); allowed.next();
        check(op.result.join().getAsJsonObject("graph").getAsJsonArray("modules").size() == 128, "127 plus one reaches bounded complete graph");
        Fixture full = new Fixture(); full.population(128);
        check(TransportMixFixture.error(full.start(), "grid_bounds_exceeded").outcome.equals("not_applied") && full.loads == 0, "128 plus one rejected before loader dispatch");
        full.guard.requireAvailable();
        Fixture grew = new Fixture(); grew.population(127); op = grew.start(); grew.population(128); grew.deliver(); grew.next();
        check(TransportMixFixture.error(op, "grid_bounds_exceeded").outcome.equals("unknown") && grew.placements == 0 && grew.cleanups == 1,
                "callback capacity rechecked before native placement"); grew.guard.requireAvailable();
        for (int invalid = 0; invalid < 3; invalid++) {
            Fixture catalog = new Fixture();
            if (invalid == 0) catalog.model.catalog = List.of();
            if (invalid == 1) ((Node) catalog.model.catalog.get(0)).put(ENTRY_KIND, "DEVICE");
            if (invalid == 2) catalog.model.catalog = List.of(catalog.model.catalog.get(0), catalog.model.catalog.get(0));
            TransportMixFixture.error(catalog.start(), "invalid_params"); check(catalog.loads == 0, "only unique known MODULE enters resolver/loader");
        }
        for (int invalid = 0; invalid < 8; invalid++) {
            Fixture f = new Fixture(); op = f.start();
            if (invalid == 0) f.loaded = List.of();
            if (invalid == 1) f.loaded = List.of(f.incoming, f.incoming);
            if (invalid == 2) f.loaded = List.of(new Object());
            if (invalid == 3) ((Node) f.incoming.values.get(DESCRIPTOR)).put(TYPE_ID, new UUID(9, 9));
            if (invalid == 4) f.incoming.put(INSTANCE_ID, new UUID(1, 1));
            if (invalid == 5) f.deleted = true;
            if (invalid == 6) f.parented = true;
            if (invalid == 7) f.owned = true;
            f.deliver(); f.next();
            check(op.result.isCompletedExceptionally() && f.placements == 0 && f.cleanups == 1, "wrong count/kind/type/UUID/detachment always rejects through delivered cleanup");
            f.guard.requireAvailable();
        }
    }
    private static void geometryAndFreshSnapshotsRejectBeforePlacement() {
        Fixture endpoint = new Fixture(); endpoint.normalization = 4;
        HostOperation exactEndpoint = endpoint.start(new GridInsertionWriter.Request(TYPE, Integer.MAX_VALUE - 6, 3));
        endpoint.deliver(); endpoint.next(); endpoint.guard.requireAvailable();
        check(exactEndpoint.result.join().getAsJsonObject("actualPosition").get("x").getAsInt() == Integer.MAX_VALUE - 2,
                "actual endpoint within int32 is not rejected by applying a second hypothetical reflow after settlement");
        for (int invalid = 0; invalid < 6; invalid++) {
            Fixture f = new Fixture(); GridInsertionWriter.Request request = f.request();
            if (invalid == 0) f.incoming.put(WIDTH, 0);
            if (invalid == 1) f.incoming.put(HEIGHT, -1);
            if (invalid == 2) request = new GridInsertionWriter.Request(TYPE, Integer.MAX_VALUE, 3);
            if (invalid == 3) request = new GridInsertionWriter.Request(TYPE, 8, Integer.MAX_VALUE);
            if (invalid == 4) request = new GridInsertionWriter.Request(TYPE, 8, 65540);
            if (invalid == 5) request = new GridInsertionWriter.Request(TYPE, Integer.MAX_VALUE - 3, 3);
            HostOperation op = f.start(request); f.deliver(); f.next();
            check(TransportMixFixture.error(op, "grid_bounds_exceeded").outcome.equals("unknown") && f.placements == 0 && f.cleanups == 1,
                    "incoming footprint/endpoints/full y span/cumulative rightward overflow checked before allocation"); f.guard.requireAvailable();
        }
        for (int changed = 0; changed < 7; changed++) {
            Fixture f = new Fixture(); HostOperation op = f.start();
            if (changed == 0) f.model.first.put(X, 20);
            if (changed == 1) f.model.graph.put(MODULES, List.of(f.model.second));
            if (changed == 2) f.model.proxy.put(TARGET, f.model.owner);
            if (changed == 3) f.context.values.put(NativeReadContext.Field.CURRENT_DT, new Object());
            if (changed == 4) f.requestError = "unsafe_state";
            if (changed == 5) f.model.first.put(WIDTH, 4);
            if (changed == 6) f.placementReady = false;
            f.deliver();
            check(f.cleanups == 1 && f.placements == 0, "captured membership/layout/context/target/safety/placement prerequisite cannot be rebaselined");
            f.next(); check(op.result.isCompletedExceptionally(), "preplacement rejection never becomes fake success");
            if (changed == 2 || changed == 3 || changed == 6) check(f.guard.quarantined(), "uncorroborated original native context remains blocked at settlement");
            else f.guard.requireAvailable();
        }
    }
    private static void nativeFailuresPoisonButDeliveryFailuresDoNot() {
        for (int boundary = 0; boundary < 6; boundary++) {
            Fixture f = new Fixture();
            if (boundary == 0) f.failLaunch = true;
            HostOperation op = f.start();
            if (boundary == 1) { f.requestError = "deadline_exceeded"; f.failCleanup = true; }
            if (boundary == 2) f.failPlacement = true;
            if (boundary == 3) f.failSuccess = true;
            if (boundary == 4) f.failEnqueue = true;
            if (boundary == 5) f.badMembership = true;
            if (boundary != 0) f.deliver();
            if (boundary == 5) f.next();
            check(op.result.isCompletedExceptionally() && f.guard.quarantined(), "launch/cleanup/placement/callback/scheduling/native verification failure quarantines");
            if (boundary == 1) check(f.order.indexOf("cleanup") < f.order.indexOf("failure"), "even failed cleanup is attempted before failure delivery");
        }
        Fixture callbackError = new Fixture(); callbackError.failConverted = true;
        HostOperation callbackOp = callbackError.start(); callbackError.deliver(); callbackError.next();
        check(callbackOp.result.isCompletedExceptionally() && callbackError.cleanups == 1 && callbackError.guard.quarantined()
                && callbackError.order.indexOf("cleanup") < callbackError.order.indexOf("failure"),
                "unexpected callback/linkage Error still cleans first, returns normally and poisons native lifetime");
        for (int changed = 0; changed < 5; changed++) {
            Fixture settled = new Fixture(); HostOperation failed = settled.start(); settled.deliver();
            if (changed == 0) settled.context.values.put(NativeReadContext.Field.ROOT, new Object());
            if (changed == 1) ((Node) settled.incoming.values.get(DESCRIPTOR)).put(TYPE_ID, new UUID(9, 9));
            if (changed == 2) settled.incoming.put(Y, 4);
            if (changed == 3) settled.incoming.put(X, 7);
            if (changed == 4) settled.model.document = false;
            settled.next();
            check(failed.result.isCompletedExceptionally() && settled.guard.quarantined(),
                    "settlement independently verifies native thread/original context/new type/row/rightward layout");
        }
        Fixture preparation = new Fixture(); preparation.failPrepare = true;
        check(TransportMixFixture.error(preparation.start(), "grid_unavailable").outcome.equals("not_applied") && preparation.loads == 0, "known prelaunch failure releases own reservation"); preparation.guard.requireAvailable();
        Fixture resolver = new Fixture(); resolver.failResolve = true;
        TransportMixFixture.error(resolver.start(), "grid_unavailable"); resolver.guard.requireAvailable();
        Fixture serialization = new Fixture(); serialization.failDelivery = true; HostOperation op = serialization.start(); serialization.deliver(); serialization.next();
        check(TransportMixFixture.error(op, "grid_bounds_exceeded").outcome.equals("unknown") && serialization.placements == 1, "serialization-only delivery failure preserves uncertain RPC outcome");
        serialization.guard.requireAvailable();
        Fixture readback = new Fixture(); op = readback.start(); readback.deliver();
        readback.model.failures.put(INPUTS, HostServices.failure("grid_unavailable", "transport graph unsupported")); readback.next();
        check(op.result.isCompletedExceptionally(), "bounded graph serialization failure cannot fabricate insertion success"); readback.guard.requireAvailable();
        Fixture callbackReporting = new Fixture(); callbackReporting.failFailureNotification = true;
        callbackReporting.start(); callbackReporting.callback.earlyError("error");
        check(callbackReporting.guard.quarantined(), "throwing outward observer cannot escape native adapter error handling");
        callbackReporting.deliver(); callbackReporting.next(); check(callbackReporting.cleanups == 1, "throwing outward observer does not suppress later native cleanup");
    }
    private static void reflectedConversionFailuresCleanBeforeStickyQuarantine() {
        for (Throwable cause : List.of(new LinkageError("native conversion linkage"),
                new IllegalStateException("native conversion failure"), new Exception("native checked conversion failure"),
                HostServices.failure("deadline_exceeded", "native code threw an RPC-shaped error"))) {
            Fixture f = new Fixture(); f.reflectedConversion = new ReflectedConversion(cause);
            HostOperation op = f.start(); f.deliver();
            check(f.cleanups == 1 && f.placements == 0 && f.successes == 0 && f.guard.quarantined(),
                    "actual Method.invoke conversion failure is cleaned once and quarantined before settlement");
            check(f.cleanupError instanceof RpcException && Bitwig614Runtime.isInvocationFailure(f.cleanupError)
                    && f.cleanupError.getCause() instanceof java.lang.reflect.InvocationTargetException
                    && f.cleanupError.getCause().getCause() == cause,
                    "RPC translation retains reflection boundary and original native throwable identity");
            check(f.order.equals(List.of("load", "cleanup", "failure", "enqueue"))
                    && TransportMixFixture.error(op, "grid_unavailable").outcome.equals("unknown"),
                    "cleanup returns before outward unknown notification and ordinary native settlement scheduling");
            f.next();
            check(new GridTopologyQuarantine(f.process).quarantined(),
                    "healthy later context cannot release translated native conversion failure quarantine");
        }
        for (String rejection : List.of("deadline_exceeded", "stale_context")) {
            Fixture f = new Fixture(); HostOperation op = f.start(); f.requestError = rejection; f.deliver();
            check(f.cleanups == 1 && !f.guard.quarantined() && !op.result.isDone()
                    && !Bitwig614Runtime.isInvocationFailure(f.cleanupError),
                    "ordinary deadline/context validation remains a cleanup-and-settle rejection");
            f.next(); f.guard.requireAvailable();
            check(TransportMixFixture.error(op, rejection).outcome.equals("unknown"),
                    "ordinary rejected request can settle native lifetime without claiming request success");
        }
    }
    public static final class ReflectedConversion {
        static final java.lang.reflect.Method READ = method();
        private final Throwable failure;
        ReflectedConversion(Throwable failure) { this.failure = failure; }
        private static java.lang.reflect.Method method() {
            try { return ReflectedConversion.class.getMethod("converted"); }
            catch (NoSuchMethodException error) { throw new AssertionError(error); }
        }
        public List<?> converted() throws Exception {
            if (failure instanceof Error) throw (Error) failure;
            throw (Exception) failure;
        }
    }

    private static void allTopologyWritersConsultPendingLifetime() {
        Fixture f = new Fixture(); f.start();
        GridSnapshotReaderTest.ModuleWriterFixture modules = new GridSnapshotReaderTest.ModuleWriterFixture();
        GridModuleWriter writer = new GridModuleWriter(modules.model.reader, modules.model, modules.context.reader, modules, new GridTopologyQuarantine(f.process));
        for (boolean move : List.of(false, true)) {
            Runnable call = () -> {
                if (move) writer.move(modules.model.proxy, TRACK, new UUID(1, 1), new GridModuleWriter.Move(7, 3, 10, 3), () -> {}, () -> {}, value -> {}, error -> {});
                else writer.delete(modules.model.proxy, TRACK, new UUID(1, 1), () -> {}, () -> {}, value -> {}, error -> {});
            };
            expect("grid_unavailable", call);
        }
        GridSnapshotReaderTest.ConnectionFixture connections = new GridSnapshotReaderTest.ConnectionFixture(new GridTopologyQuarantine(f.process));
        check(TransportMixFixture.error(connections.start(connections.request(null, connections.output())), "grid_unavailable").outcome.equals("not_applied")
                && connections.calls == 0 && modules.calls == 0, "replacement guards block move/delete/connection before native calls");
        Fixture second = new Fixture(f.process, null);
        check(TransportMixFixture.error(second.start(), "grid_unavailable").outcome.equals("not_applied") && second.loads == 0, "overlapping insertion cannot replace pending loader owner");
    }
    private static void expect(String code, Runnable action) {
        try { action.run(); throw new AssertionError("Expected " + code); }
        catch (RpcException error) { check(code.equals(error.code), "expected " + code + ", got " + error.code); }
    }
    private static void check(boolean condition, String label) { assertions++; if (!condition) throw new AssertionError(label); }

    static final class Fixture implements GridInsertionWriter.Access {
        final GridSnapshotReaderTest.Fixture model = new GridSnapshotReaderTest.Fixture();
        final GridSnapshotReaderTest.ModuleContextFixture context = new GridSnapshotReaderTest.ModuleContextFixture();
        final Properties process; final GridTopologyQuarantine guard; final GridInsertionWriter writer;
        final Deque<Runnable> events = new ArrayDeque<>(); final Consumer<Runnable> queue;
        final List<String> order = new ArrayList<>();
        final Node incoming = model.module(1024, null); final Object result = new Object(), completion = new Object();
        List<?> loaded = List.of(incoming); GridInsertionBridge.Callback callback;
        int loads, placements, cleanups, successes, normalization;
        boolean reflow, deleted, parented, owned, placementReady = true;
        boolean failResolve, failPrepare, failLaunch, failConverted, failCleanup, failPlacement, failSuccess, failEnqueue, failDelivery, badMembership, failFailureNotification;
        String requestError;
        ReflectedConversion reflectedConversion;
        Exception cleanupError;
        Fixture() { this(new Properties(), null); }
        Fixture(Consumer<Runnable> queue) { this(new Properties(), queue); }
        Fixture(Properties process, Consumer<Runnable> queue) {
            this.process = process; guard = new GridTopologyQuarantine(process); this.queue = queue == null ? events::add : queue;
            writer = new GridInsertionWriter(model.reader, model, context.reader, this, guard);
            model.catalog = List.of(new Node(ENTRY).put(ENTRY_ID, TYPE).put(ENTRY_KIND, "MODULE").put(ENTRY_NAME, "Catalog module"));
            context.override(incoming, NativeReadContext.Field.CONTEXT, null);
            context.override(incoming, NativeReadContext.Field.PROJECT, null);
        }
        GridInsertionWriter.Request request() { return new GridInsertionWriter.Request(TYPE, 8, 3); }
        HostOperation start() { return start(request()); }
        HostOperation start(GridInsertionWriter.Request request) {
            HostOperation op = new HostOperation(Long.MAX_VALUE);
            try { edit(request, () -> {
                if (requestError != null) throw HostServices.failure(requestError, "request no longer admissible");
                if (op.result.isDone()) throw HostServices.failure("cancelled", "request already completed");
            }, () -> op.dispatched = true, value -> {
                if (failDelivery) throw HostServices.failure("grid_bounds_exceeded", "serialization failure");
                op.result.complete(value);
            }, error -> {
                order.add("failure"); if (failFailureNotification) throw new AssertionError("observer failure"); op.fail(error);
            }); } catch (Throwable error) { op.fail(error); }
            return op;
        }
        void edit(GridInsertionWriter.Request request, Runnable check, Runnable dispatch, Consumer<JsonObject> complete, Consumer<Throwable> fail) {
            writer.insert(model.proxy, TRACK, request, check, dispatch, complete, fail);
        }
        void population(int size) {
            List<Object> members = new ArrayList<>();
            for (int i = 0; i < size; i++) members.add(model.module(i, model.graph));
            model.graph.put(MODULES, members);
        }
        void deliver() { if (callback.delivered(result, completion)) callback.returned(); }
        void next() { events.removeFirst().run(); }
        @Override public String resolve(UUID id) {
            if (failResolve) throw HostServices.failure("grid_unavailable", "missing native resource"); return "native-resolved-resource";
        }
        @Override public Runnable prepareLoad(Object project, String path, GridInsertionBridge.Callback callback) {
            if (failPrepare) throw HostServices.failure("grid_unavailable", "adapter construction failed");
            this.callback = callback;
            return () -> { loads++; order.add("load"); if (failLaunch) throw new AssertionError("load failure"); };
        }
        @Override public List<?> converted(Object result) {
            if (reflectedConversion != null)
                return (List<?>) Bitwig614Runtime.invoke("grid_unavailable", ReflectedConversion.READ, reflectedConversion);
            if (failConverted) throw new LinkageError("native callback reader failed");
            return loaded;
        }
        @Override public void requireDetached(Object module) {
            if (deleted || parented || owned || ((Node) module).values.get(MODULE_GRAPH) != null) throw HostServices.failure("stale_context", "not detached live singleton");
        }
        @Override public void requirePlacementReady(Object graph) { if (!placementReady) throw HostServices.failure("unsafe_state", "nonempty XEl list"); }
        @Override public void place(Object graph, List<?> loaded, int x, int y) {
            placements++; order.add("place");
            if (failPlacement) throw new AssertionError("placement/close failed");
            if (badMembership) return;
            List<Object> members = new ArrayList<>((List<?>) model.graph.values.get(MODULES));
            if (reflow) for (Object member : members) { Node node = (Node) member; node.put(X, ((Integer) node.values.get(X)) + 2); }
            incoming.put(X, x + normalization).put(Y, y).put(MODULE_GRAPH, graph); members.add(incoming); model.graph.put(MODULES, members);
            context.override(incoming, NativeReadContext.Field.CONTEXT, context.context);
            context.override(incoming, NativeReadContext.Field.PROJECT, context.project);
        }
        @Override public void reject(Object completion, Exception error) {
            cleanups++; cleanupError = error; order.add("cleanup");
            if (failCleanup) throw new AssertionError("cleanup failed");
        }
        @Override public void succeed(Object completion, List<?> loaded) { successes++; order.add("success"); if (failSuccess) throw new AssertionError("success observer failed"); }
        @Override public void execute(Runnable event) { order.add("enqueue"); if (failEnqueue) throw new AssertionError("dispatcher failed"); queue.accept(event); }
    }
}
