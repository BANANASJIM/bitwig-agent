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
    static final int MAX_BATCH = 16;
    /** Exactly one of typeId or typeName; typeName is resolved against the native catalog before anything is loaded. */
    static final class Request {
        final UUID typeId; final String typeName; final int x, y;
        Request(UUID typeId, int x, int y) { this(typeId, null, x, y); }
        Request(UUID typeId, String typeName, int x, int y) { this.typeId = typeId; this.typeName = typeName; this.x = x; this.y = y; }
        static Request parse(JsonObject p) {
            if (p.has("typeId") == p.has("typeName"))
                throw failure("invalid_params", "Supply exactly one of typeId or typeName (a catalog module name).");
            int x = GridModuleWriter.Move.coordinate(p, "x"), y = GridModuleWriter.Move.coordinate(p, "y");
            if (p.has("typeName")) {
                String name = Params.string(p, "typeName");
                if (name.length() > 128 || GridNames.norm(name).isEmpty())
                    throw failure("invalid_params", "typeName must be a catalog module name of at most 128 UTF-16 units.");
                return new Request(null, name, x, y);
            }
            GridModule.canonicalUuid(p, "typeId");
            return new Request(UUID.fromString(p.get("typeId").getAsString()), x, y);
        }
        static List<Request> parseBatch(JsonObject p) {
            JsonElement modules = p.get("modules");
            if (!modules.isJsonArray() || modules.getAsJsonArray().size() < 1 || modules.getAsJsonArray().size() > MAX_BATCH)
                throw failure("invalid_params", "modules must be an array of 1.." + MAX_BATCH + " insertions.");
            List<Request> out = new ArrayList<>();
            for (JsonElement element : modules.getAsJsonArray()) {
                if (!element.isJsonObject()) throw failure("invalid_params", "Each module must be an object.");
                JsonObject m = element.getAsJsonObject();
                Params.fields(m, Set.of("x", "y"), Set.of("typeId", "typeName"));
                out.add(parse(m));
            }
            return out;
        }
        Request resolved(UUID id) { return new Request(id, null, x, y); }
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
    void insert(Object proxy, UUID trackId, Request given, Runnable check, Runnable dispatch,
            Consumer<JsonObject> complete, Consumer<Throwable> fail) {
        quarantine.requireAvailable(); values.documentThread(); check.run();
        Request request = resolveNames(List.of(given), check).get(0);
        requireKnownModule(request.typeId, check);
        GridSnapshotReader.NativeTarget target = snapshots.target(proxy, trackId);
        Layout before = layout(target.graph, check); before.capacity(); before.bounds(null);
        GridTopologyContext.Capture context = contexts.capture(target.target, target.graph, before.modules, null, check);
        nativeWriter.requirePlacementReady(target.graph);
        String path = nativeWriter.resolve(request.typeId);
        check.run();
        JsonObject graphBefore = snapshots.snapshot(proxy, trackId, 0, GridTopologyContext.MAX_MODULES, false, false, null, check);
        String owner = quarantine.reserve();
        Lifetime lifetime = new Lifetime(owner, proxy, trackId, request, target, before, context, check, complete, fail);
        lifetime.graphBefore = graphBefore;
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
    /** Catalog MODULE entries as (id, name); one bounded read. */
    private List<Map.Entry<UUID, String>> moduleEntries(Runnable check) {
        Object catalog = values.read(CATALOG, null);
        if (!(catalog instanceof List<?>)) throw failure("grid_unavailable", "Native module catalog is unavailable.");
        List<?> entries = (List<?>) catalog;
        if (entries.size() > GridSnapshotReader.MAX_CATALOG) throw failure("grid_bounds_exceeded", "Native module catalog exceeds its bound.");
        List<Map.Entry<UUID, String>> out = new ArrayList<>();
        for (Object entry : entries) {
            check.run();
            if (!values.is(GridSnapshotReader.Kind.ENTRY, entry)) throw failure("grid_unavailable", "Unknown native catalog entry.");
            if (!"MODULE".equals(values.read(ENTRY_KIND, entry))) continue;
            Object id = values.read(ENTRY_ID, entry);
            if (!(id instanceof UUID)) throw failure("grid_unavailable", "Unknown native catalog entry.");
            out.add(Map.entry((UUID) id, GridSnapshotReader.text(values.read(ENTRY_NAME, entry))));
        }
        return out;
    }
    /**
     * Resolves every typeName to exactly one catalog entry (typeId entries are checked for membership); any failure
     * rejects not_applied naming each entry, so a batch never starts with an unresolved type.
     */
    private List<Request> resolveNames(List<Request> requests, Runnable check) {
        List<Map.Entry<UUID, String>> catalog = null;
        List<Request> out = new ArrayList<>();
        StringBuilder rejected = new StringBuilder(); int failures = 0;
        for (int i = 0; i < requests.size(); i++) {
            Request request = requests.get(i);
            if (request.typeName == null) {
                if (requests.size() == 1) { out.add(request); continue; }
                try { requireKnownModule(request.typeId, check); out.add(request); }
                catch (RpcException error) { failures = reject(rejected, failures, i, error.getMessage()); }
                continue;
            }
            if (catalog == null) catalog = moduleEntries(check);
            List<Map.Entry<UUID, String>> exact = new ArrayList<>();
            for (Map.Entry<UUID, String> entry : catalog) if (GridNames.same(entry.getValue(), request.typeName)) exact.add(entry);
            if (exact.size() == 1) { out.add(request.resolved(exact.get(0).getKey())); continue; }
            List<String> candidates = new ArrayList<>();
            if (exact.isEmpty()) {
                List<String> names = new ArrayList<>();
                for (Map.Entry<UUID, String> entry : catalog) {
                    if (GridNames.matches(entry.getValue(), request.typeName) && candidates.size() < GridNames.SUGGESTIONS) candidates.add(entry.getValue());
                    names.add(entry.getValue());
                }
                if (candidates.isEmpty()) candidates = GridNames.nearest(names, request.typeName, GridNames.SUGGESTIONS);
            } else for (Map.Entry<UUID, String> entry : exact) candidates.add(entry.getValue() + " " + entry.getKey());
            failures = reject(rejected, failures, i, "typeName '" + request.typeName + "' "
                    + (exact.isEmpty() ? "matches no catalog module exactly" : "is ambiguous")
                    + (candidates.isEmpty() ? "" : "; candidates: " + String.join(", ", candidates)));
        }
        if (failures > 0)
            throw failure("invalid_params", (requests.size() == 1 ? "" : "No module was inserted; " + failures + " of " + requests.size()
                    + " entries failed type lookup: ") + rejected + (failures > 8 ? "; ..." : ""));
        return out;
    }
    private static int reject(StringBuilder rejected, int failures, int index, String message) {
        if (failures < 8) rejected.append(rejected.length() == 0 ? "" : "; ").append('#').append(index).append(' ').append(message);
        return failures + 1;
    }

    /**
     * Same-device batch: types and geometry first (any failure inserts nothing), then entries run in order through the
     * ordinary single path, each settling before the next starts; stops at the first failure or unknown.
     */
    /** Time left before the RPC deadline must exceed this to start another batch entry (a load plus its delivery). */
    static final long NEXT_ENTRY_MARGIN_NANOS = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(1500);
    void insertAll(Object proxy, UUID trackId, List<Request> given, java.util.function.BooleanSupplier timeLeft, Runnable check, Runnable dispatch,
            Consumer<JsonObject> complete, Consumer<Throwable> fail) {
        quarantine.requireAvailable(); values.documentThread(); check.run();
        List<Request> requests = resolveNames(given, check);
        GridSnapshotReader.NativeTarget target = snapshots.target(proxy, trackId);
        Layout before = layout(target.graph, check); before.capacity(requests.size()); before.requested(requests);
        JsonObject graphBefore = snapshots.snapshot(proxy, trackId, 0, GridTopologyContext.MAX_MODULES, false, false, null, check);
        new Batch(proxy, trackId, requests, graphBefore, timeLeft, check, dispatch, complete, fail).next(0);
    }

    private final class Batch {
        final Object proxy; final UUID trackId; final List<Request> requests; final JsonObject graphBefore;
        final Runnable check, dispatch; final Consumer<JsonObject> complete; final Consumer<Throwable> fail;
        final JsonArray items = new JsonArray();
        JsonObject lastGraph; String status = "applied"; int written; boolean done;
        final java.util.function.BooleanSupplier timeLeft;
        Batch(Object proxy, UUID trackId, List<Request> requests, JsonObject graphBefore, java.util.function.BooleanSupplier timeLeft,
                Runnable check, Runnable dispatch, Consumer<JsonObject> complete, Consumer<Throwable> fail) {
            this.timeLeft = timeLeft;
            this.proxy = proxy; this.trackId = trackId; this.requests = requests; this.graphBefore = graphBefore;
            this.check = check; this.dispatch = dispatch; this.complete = complete; this.fail = fail;
        }
        JsonObject item(Request request) {
            JsonObject item = new JsonObject();
            item.addProperty("typeId", request.typeId.toString());
            item.add("requestedPosition", GridModuleWriter.position(request.x, request.y));
            return item;
        }
        void next(int i) {
            if (done) return;
            if (i == requests.size()) { finish(); return; }
            if (i > 0 && !timeLeft.getAsBoolean()) { // Stop cleanly while per-entry results can still be delivered.
                status = "partial";
                for (int k = i; k < requests.size(); k++) {
                    JsonObject rest = item(requests.get(k)); rest.addProperty("status", "not_dispatched"); items.add(rest);
                }
                finish(); return;
            }
            Request request = requests.get(i);
            JsonObject item = item(request);
            items.add(item);
            boolean[] sent = {false};
            try {
                insert(proxy, trackId, request, check, () -> { dispatch.run(); sent[0] = true; },
                        one -> {
                            if (done) return;
                            try {
                                item.addProperty("status", "applied");
                                item.add("moduleId", one.get("moduleId")); item.add("actualPosition", one.get("actualPosition"));
                                item.add("matchesRequested", one.get("matchesRequested"));
                                lastGraph = one.getAsJsonObject("graph"); written++;
                                next(i + 1);
                            } catch (Throwable error) { failed(i, item, error, sent[0]); }
                        },
                        error -> failed(i, item, error, sent[0]));
            } catch (Throwable error) { failed(i, item, error, sent[0]); }
        }
        private void failed(int i, JsonObject item, Throwable error, boolean dispatched) {
            if (done) return;
            boolean unknown = dispatched || error instanceof RpcException && "unknown".equals(((RpcException) error).outcome);
            if (written == 0 && !unknown || !(error instanceof RuntimeException) && !unknown) { done = true; fail.accept(error); return; }
            item.addProperty("status", unknown ? "unknown" : "failed");
            JsonObject why = new JsonObject();
            why.addProperty("code", error instanceof RpcException ? ((RpcException) error).code : "internal_error");
            why.addProperty("message", error.getMessage());
            item.add("error", why);
            status = unknown ? "unknown" : written > 0 ? "partial" : "failed";
            for (int k = i + 1; k < requests.size(); k++) {
                JsonObject rest = item(requests.get(k)); rest.addProperty("status", "not_dispatched"); items.add(rest);
            }
            finish();
        }
        /** Later inserts can push earlier modules right: report where each module is in the final graph. */
        private void refreshPositions() {
            for (JsonElement row : lastGraph.getAsJsonArray("modules")) {
                JsonObject module = row.getAsJsonObject();
                for (JsonElement element : items) {
                    JsonObject item = element.getAsJsonObject();
                    if (!item.has("moduleId") || !item.get("moduleId").getAsString().equals(module.get("instanceId").getAsString())) continue;
                    item.add("actualPosition", GridModuleWriter.position(module.get("x").getAsInt(), module.get("y").getAsInt()));
                    JsonObject wanted = item.getAsJsonObject("requestedPosition");
                    item.addProperty("matchesRequested", wanted.get("x").equals(module.get("x")) && wanted.get("y").equals(module.get("y")));
                }
            }
        }
        private void finish() {
            if (done) return;
            done = true;
            try {
                boolean unknown = status.equals("unknown");
                if (lastGraph != null && !unknown) refreshPositions();
                JsonObject result = HostServices.observed();
                result.addProperty("batchStatus", status); result.addProperty("completion", unknown ? "unknown" : "observed");
                result.addProperty("written", written);
                result.add("modules", items);
                result.addProperty("mutationDispatched", written > 0 || unknown);
                result.addProperty("nativeMutation", written > 0 || unknown); result.addProperty("nativeUndo", written > 0 || unknown);
                result.addProperty("verification", "later_document_event_bounded_native_graph_observation_per_entry");
                // After an unknown entry the last observed graph is not the current one: no diff is offered.
                if (lastGraph != null && !unknown) { result.add("graph", lastGraph); result.add("graphBefore", graphBefore); }
                complete.accept(result);
            } catch (Throwable error) { fail.accept(error); }
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
        Row incoming; JsonObject graphBefore;
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
                    if (!(error instanceof RpcException) || Bitwig614Runtime.isInvocationFailure(error)) adapterFailure(error);
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
                JsonObject graph = snapshots.snapshot(proxy, trackId, 0, GridTopologyContext.MAX_MODULES, false, false, null, check);
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
                result.add("graph", graph); result.add("graphBefore", graphBefore); check.run(); complete.accept(result);
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
        void capacity() { capacity(1); }
        void capacity(int extra) {
            if (rows.size() + extra > GridTopologyContext.MAX_MODULES)
                throw failure("grid_bounds_exceeded", "Insertion requires destination module count plus " + extra + " <=128 before loading and placement.");
        }
        /** Requested anchors only (footprints are unknown before loading): int32 endpoints and the occupied y-span. */
        void requested(List<Request> requests) {
            long minY = Long.MAX_VALUE, maxY = Long.MIN_VALUE;
            for (Row row : rows) { minY = Math.min(minY, row.y); maxY = Math.max(maxY, (long) row.y + row.height); }
            for (Request request : requests) {
                checked((long) request.x + 1); checked((long) request.y + 1);
                minY = Math.min(minY, request.y); maxY = Math.max(maxY, (long) request.y + 1);
            }
            if (maxY - minY > GridModuleWriter.MAX_Y_SPAN)
                throw failure("grid_bounds_exceeded", "Insertion occupied y-span exceeds 65536 before native allocation.");
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
