package dev.bitwigagent;

import com.google.gson.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import static dev.bitwigagent.GridSnapshotReader.Field.*;
import static dev.bitwigagent.HostServices.failure;

/** Native loader lifetime is independent of the RPC. Only a later healthy native event settles it. */
final class GridInsertionWriter {
    interface Access {
        String resolve(UUID typeId);
        Runnable prepareLoad(Object project, String path, GridInsertionBridge.Callback callback);
        List<?> converted(Object result);
        void requireDetached(Object module);
        void requirePlacementReady(Object graph);
        void place(Object graph, List<?> loaded, int x, int y);
        void reject(Object completion, Exception error);
        void succeed(Object completion, List<?> loaded);
        void execute(Runnable event);
    }
    static final class Request {
        final UUID typeId; final int x, y;
        Request(UUID typeId, int x, int y) { this.typeId = typeId; this.x = x; this.y = y; }
        static Request parse(JsonObject p) {
            GridModule.canonicalUuid(p, "typeId");
            return new Request(UUID.fromString(p.get("typeId").getAsString()),
                    GridModuleWriter.Move.coordinate(p, "x"), GridModuleWriter.Move.coordinate(p, "y"));
        }
    }
    private static final Runnable NATIVE_LIFETIME = () -> {};
    private final GridSnapshotReader snapshots;
    private final GridSnapshotReader.Access values;
    private final GridTopologyContext contexts;
    private final Access nativeWriter;
    private final GridTopologyQuarantine quarantine;

    GridInsertionWriter(GridSnapshotReader snapshots, GridSnapshotReader.Access values, GridTopologyContext contexts,
            Access nativeWriter, GridTopologyQuarantine quarantine) {
        this.snapshots = snapshots; this.values = values; this.contexts = contexts;
        this.nativeWriter = nativeWriter; this.quarantine = quarantine;
    }
    void insert(Object proxy, UUID trackId, Request request, Runnable check, Runnable dispatch,
            Consumer<JsonObject> complete, Consumer<Throwable> fail) {
        quarantine.requireAvailable(); values.documentThread(); check.run();
        requireKnownModule(request.typeId, check);
        GridSnapshotReader.NativeTarget target = snapshots.target(proxy, trackId);
        Layout before = layout(target.graph, check); before.capacity(); before.bounds(null);
        GridTopologyContext.Capture context = contexts.capture(target.target, target.graph, before.modules, null, check);
        nativeWriter.requirePlacementReady(target.graph);
        String path = nativeWriter.resolve(request.typeId);
        check.run();
        String owner = quarantine.reserve();
        Lifetime lifetime = new Lifetime(owner, proxy, trackId, request, target, before, context, check, complete, fail);
        try {
            Runnable load = nativeWriter.prepareLoad(context.project(), path, lifetime);
            snapshots.guardTarget(target, proxy, trackId);
            Layout ready = layout(target.graph, check); ready.capacity(); before.same(ready);
            contexts.capture(target.target, target.graph, ready.modules, context, check);
            nativeWriter.requirePlacementReady(target.graph);
            check.run(); quarantine.requireOwner(owner);
            dispatch.run();
            lifetime.loaderDispatched = true; // Parsing/conversion is not a pure read; mark before native launch.
            load.run();
        } catch (Throwable error) {
            if (lifetime.loaderDispatched) lifetime.adapterFailure(error);
            else {
                if (!quarantine.release(owner)) throw failure("grid_unavailable", "Prelaunch insertion reservation changed unexpectedly.");
                if (error instanceof RuntimeException) throw (RuntimeException) error;
                if (error instanceof Error) throw (Error) error;
                throw failure("grid_unavailable", "Insertion preparation failed before native launch.");
            }
        }
    }
    private void requireKnownModule(UUID id, Runnable check) {
        Object catalog = values.read(CATALOG, null);
        if (!(catalog instanceof List<?>)) throw failure("grid_unavailable", "Native module catalog is unavailable.");
        List<?> entries = (List<?>) catalog;
        if (entries.size() > GridSnapshotReader.MAX_CATALOG) throw failure("grid_bounds_exceeded", "Native module catalog exceeds its bound.");
        boolean found = false;
        for (Object entry : entries) {
            check.run();
            if (!values.is(GridSnapshotReader.Kind.ENTRY, entry)) throw failure("grid_unavailable", "Unknown native catalog entry.");
            if (!id.equals(values.read(ENTRY_ID, entry))) continue;
            if (found || !"MODULE".equals(values.read(ENTRY_KIND, entry)))
                throw failure("invalid_params", "typeId must identify exactly one known MODULE catalog entry.");
            found = true;
        }
        if (!found) throw failure("invalid_params", "typeId is not a known MODULE catalog entry; use grid.catalog.");
    }

    private final class Lifetime implements GridInsertionBridge.Callback {
        final String owner; final Object proxy; final UUID trackId; final Request request;
        final GridSnapshotReader.NativeTarget target; final Layout before; final GridTopologyContext.Capture context;
        final Runnable check; final Consumer<JsonObject> complete; final Consumer<Throwable> fail;
        final AtomicBoolean claimed = new AtomicBoolean(), queued = new AtomicBoolean();
        volatile boolean loaderDispatched, placementDispatched, normalHandling;
        volatile Throwable lifecycleError, rejection;
        Row incoming;
        Lifetime(String owner, Object proxy, UUID trackId, Request request, GridSnapshotReader.NativeTarget target,
                Layout before, GridTopologyContext.Capture context, Runnable check, Consumer<JsonObject> complete, Consumer<Throwable> fail) {
            this.owner = owner; this.proxy = proxy; this.trackId = trackId; this.request = request; this.target = target;
            this.before = before; this.context = context; this.check = check; this.complete = complete; this.fail = fail;
        }
        @Override public boolean delivered(Object result, Object completion) {
            // Claim before even checking deadline/future/target. Every first delivery owes native cleanup or placement.
            if (!claimed.compareAndSet(false, true)) {
                adapterFailure(failure("grid_unavailable", "Duplicate native loader delivery; no repeated placement or cleanup is permitted."));
                return false;
            }
            List<?> loaded;
            try {
                if (lifecycleError != null) throw failure("grid_unavailable", "Native loader previously reported an unacknowledged failure.");
                values.documentThread(); check.run(); quarantine.requireOwner(owner);
                snapshots.guardTarget(target, proxy, trackId);
                Layout ready = layout(target.graph, check); ready.capacity(); before.same(ready);
                contexts.capture(target.target, target.graph, ready.modules, context, check);
                loaded = nativeWriter.converted(result);
                if (loaded == null || loaded.size() != 1 || !values.is(GridSnapshotReader.Kind.MODULE, loaded.get(0)))
                    throw failure("grid_unavailable", "Native MODULE loader must deliver exactly one mapped module.");
                Object module = loaded.get(0);
                nativeWriter.requireDetached(module);
                incoming = row(module, request.x, request.y);
                if (!request.typeId.equals(incoming.typeId) || before.find(incoming.id) != null)
                    throw failure("stale_context", "Loaded singleton has the wrong catalog type or a non-fresh instance UUID.");
                ready.bounds(incoming);
                nativeWriter.requirePlacementReady(target.graph);
                snapshots.guardTarget(target, proxy, trackId);
                Layout adjacent = layout(target.graph, check); adjacent.capacity(); before.same(adjacent); adjacent.bounds(incoming);
                contexts.capture(target.target, target.graph, adjacent.modules, context, check);
                nativeWriter.requireDetached(module);
                incoming.identity(row(module, request.x, request.y));
                nativeWriter.requirePlacementReady(target.graph);
                check.run(); quarantine.requireOwner(owner);
            } catch (Throwable error) {
                rejection = error;
                try {
                    nativeWriter.reject(completion, error instanceof Exception ? (Exception) error : new Exception("Insertion preplacement gate failed", error));
                    normalHandling = true; // Record normal delivered cleanup BEFORE any outward notification.
                    if (!(error instanceof RpcException) || Bitwig613Runtime.isInvocationFailure(error)) adapterFailure(error);
                    return true;
                } catch (Throwable cleanupError) { adapterFailure(cleanupError); return false; }
            }
            try {
                placementDispatched = true;
                nativeWriter.place(target.graph, loaded, request.x, request.y);
                nativeWriter.succeed(completion, loaded); // Only after placement returned, never cancellation-as-success.
                normalHandling = true;
                return true;
            } catch (Throwable error) { adapterFailure(error); return false; }
        }
        @Override public void returned() {
            if (!normalHandling || !queued.compareAndSet(false, true)) {
                adapterFailure(failure("grid_unavailable", "Unexpected native callback return/settlement sequence.")); return;
            }
            try { nativeWriter.execute(this::settle); }
            catch (Throwable error) { adapterFailure(error); }
        }
        @Override public void earlyError(String message) {
            String text = message == null ? "unspecified" : message.substring(0, Math.min(message.length(), 512));
            adapterFailure(failure("grid_unavailable", "Native loader early error is not cleanup acknowledgement: " + text));
        }
        @Override public void adapterFailure(Throwable error) {
            poison(error); notifyFailure(error);
        }
        private void poison(Throwable error) {
            lifecycleError = error;
            try { quarantine.poison(); }
            catch (Throwable guardError) { lifecycleError = guardError; } // Guard itself retains inaccessible/fail-closed state.
        }
        private void notifyFailure(Throwable error) {
            try { fail.accept(error); }
            catch (Throwable deliveryError) { poison(deliveryError); }
        }
        private void settle() {
            Layout after;
            try {
                // No RPC check/controller scheduling here: timeout or extension close cannot suppress native settlement.
                values.documentThread(); snapshots.guardTarget(target, proxy, trackId);
                after = layout(target.graph, NATIVE_LIFETIME);
                contexts.capture(target.target, target.graph, after.modules, context, NATIVE_LIFETIME);
                nativeWriter.requirePlacementReady(target.graph);
                if (placementDispatched) before.inserted(after, incoming);
                snapshots.guardTarget(target, proxy, trackId);
                contexts.capture(target.target, target.graph, after.modules, context, NATIVE_LIFETIME);
                if (lifecycleError != null) return; // Early error/duplicate/cleanup failure is sticky, never normal settlement.
                if (!quarantine.release(owner)) throw failure("grid_unavailable", "Native insertion settlement no longer owns the process reservation.");
            } catch (Throwable error) { adapterFailure(error); return; }
            // Native lifetime is now settled. Delivery-only failure must not put it back into pending/quarantine.
            try {
                if (rejection != null) { fail.accept(rejection); return; }
                check.run();
                JsonObject graph = snapshots.snapshot(proxy, trackId, 0, GridTopologyContext.MAX_MODULES, false, check);
                after.same(layout(target.graph, check));
                snapshots.guardTarget(target, proxy, trackId);
                contexts.capture(target.target, target.graph, after.modules, context, check);
                Row actual = after.find(incoming.id);
                JsonObject result = HostServices.observed();
                result.addProperty("operation", "insert"); result.addProperty("moduleId", actual.id.toString());
                result.addProperty("typeId", actual.typeId.toString());
                result.add("requestedPosition", GridModuleWriter.position(request.x, request.y));
                result.add("actualPosition", GridModuleWriter.position(actual.x, actual.y));
                result.addProperty("matchesRequested", actual.x == request.x && actual.y == request.y);
                result.addProperty("loaderDispatched", loaderDispatched); result.addProperty("placementDispatched", placementDispatched);
                result.addProperty("mutationDispatched", loaderDispatched); result.addProperty("nativeMutation", true);
                result.addProperty("nativeUndo", true); result.addProperty("undoGroupingVerified", false);
                result.addProperty("engineAcknowledgement", false); result.addProperty("nativeLifetimeSettled", true);
                result.addProperty("verification", "later_document_event_bounded_native_graph_observation");
                result.addProperty("nativeUndoScope", "native_operation_not_verified_grouping_or_commit");
                result.addProperty("contextScope", "same_project_root_default_Dt_idle_undo_lock_owner_not_balanced_stack_or_atomic_permission");
                result.addProperty("safetyScope", "observed_stopped_nonrecording_nonautomation_not_native_transport_atomicity");
                result.addProperty("graphScope", "bounded_actual_membership_types_layout_and_mapped_incoming_connections_not_DSP_or_opaque_children");
                result.addProperty("maximumModules", GridTopologyContext.MAX_MODULES);
                result.addProperty("maximumOccupiedYSpan", GridModuleWriter.MAX_Y_SPAN);
                result.add("graph", graph); check.run(); complete.accept(result);
            } catch (Throwable error) {
                // A failed external notification cannot resurrect a positively settled native lifetime.
                try { fail.accept(error); } catch (Throwable notificationError) { lifecycleError = notificationError; }
            }
        }
    }
    private Layout layout(Object graph, Runnable check) {
        List<Object> modules = snapshots.topologyModules(graph, check);
        List<Row> rows = new ArrayList<>(modules.size());
        for (Object module : modules) { check.run(); rows.add(row(module, integer(X, module), integer(Y, module))); }
        Layout result = new Layout(modules, rows); result.bounds(null, false); return result;
    }
    private Row row(Object module, int x, int y) {
        Object id = values.read(INSTANCE_ID, module), descriptor = values.read(DESCRIPTOR, module);
        Object typeId = descriptor == null ? null : values.read(TYPE_ID, descriptor);
        if (!(id instanceof UUID) || !(typeId instanceof UUID)) throw failure("grid_unavailable", "Insertion requires native instance and type UUIDs.");
        return new Row(module, (UUID) id, (UUID) typeId, x, y, integer(WIDTH, module), integer(HEIGHT, module));
    }
    private int integer(GridSnapshotReader.Field field, Object module) {
        Object value = values.read(field, module);
        if (!(value instanceof Integer)) throw failure("grid_unavailable", "Insertion geometry reader is unavailable: " + field);
        return (Integer) value;
    }
    private static final class Row {
        final Object module; final UUID id, typeId; final int x, y, width, height;
        Row(Object module, UUID id, UUID typeId, int x, int y, int width, int height) {
            this.module = module; this.id = id; this.typeId = typeId; this.x = x; this.y = y; this.width = width; this.height = height;
        }
        void identity(Row actual) {
            if (actual == null || module != actual.module || !id.equals(actual.id) || !typeId.equals(actual.typeId) || width != actual.width || height != actual.height)
                throw failure("stale_context", "Native insertion membership/type/footprint changed.");
        }
    }
    private static final class Layout {
        final List<Object> modules; final List<Row> rows;
        Layout(List<Object> modules, List<Row> rows) { this.modules = modules; this.rows = rows; }
        Row find(UUID id) { for (Row row : rows) if (row.id.equals(id)) return row; return null; }
        void capacity() {
            if (rows.size() >= GridTopologyContext.MAX_MODULES)
                throw failure("grid_bounds_exceeded", "Insertion requires destination module count plus one <=128 before loading and placement.");
        }
        void same(Layout actual) {
            if (rows.size() != actual.rows.size()) throw failure("stale_context", "Native destination membership changed during insertion.");
            for (Row row : rows) {
                Row now = actual.find(row.id); row.identity(now);
                if (now.x != row.x || now.y != row.y) throw failure("stale_context", "Native destination layout changed during insertion.");
            }
        }
        void inserted(Layout actual, Row incoming) {
            if (actual.rows.size() != rows.size() + 1) throw failure("grid_verification_failed", "Later graph did not contain exactly one additional module.");
            for (Row row : rows) rightward(row, actual.find(row.id));
            rightward(incoming, actual.find(incoming.id));
        }
        private static void rightward(Row before, Row after) {
            before.identity(after);
            if (after.y != before.y || after.x < before.x)
                throw failure("grid_verification_failed", "Native insertion layout violates absolute-row/rightward-reflow invariants.");
        }
        void bounds(Row incoming) { bounds(incoming, true); }
        void bounds(Row incoming, boolean prospectiveReflow) {
            long minY = Long.MAX_VALUE, maxY = Long.MIN_VALUE, maxX = Long.MIN_VALUE, widths = 0;
            int size = rows.size() + (incoming == null ? 0 : 1);
            for (int i = 0; i < size; i++) {
                Row row = i == rows.size() ? incoming : rows.get(i);
                if (row.width <= 0 || row.height <= 0) throw failure("grid_bounds_exceeded", "Insertion requires positive native footprints.");
                checked((long) row.x + row.width); checked((long) row.y + row.height);
                minY = Math.min(minY, row.y); maxY = Math.max(maxY, (long) row.y + row.height);
                maxX = Math.max(maxX, row.x); widths += row.width;
            }
            if (size == 0) return;
            if (maxY - minY > GridModuleWriter.MAX_Y_SPAN) throw failure("grid_bounds_exceeded", "Insertion occupied y-span exceeds 65536 before native allocation.");
            if (prospectiveReflow) checked(maxX + widths); // Preallocation bound, not a second hypothetical reflow of the observed result.
        }
        private static void checked(long value) {
            if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) throw failure("grid_bounds_exceeded", "Insertion endpoint or cumulative horizontal reflow could overflow int32.");
        }
    }
}
