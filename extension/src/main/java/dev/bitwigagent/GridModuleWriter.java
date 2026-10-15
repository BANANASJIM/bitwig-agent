package dev.bitwigagent;

import com.google.gson.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import static dev.bitwigagent.GridSnapshotReader.Field.*;
import static dev.bitwigagent.HostServices.failure;

/** Two bounded native operations; native references are retained only by document-event callbacks. */
final class GridModuleWriter {
    static final int MAX_MODULES = GridTopologyContext.MAX_MODULES, MAX_Y_SPAN = 65536;
    private static final Pattern INTEGER_TOKEN = Pattern.compile("-?(0|[1-9][0-9]*)");
    interface Access {
        Runnable prepareMove(Object graph, Object module, int x, int y);
        Runnable prepareDelete(Object graph, Object module);
        void execute(Runnable observation);
    }
    private final GridSnapshotReader snapshots;
    private final GridSnapshotReader.Access values;
    private final GridTopologyContext contexts;
    private final Access nativeWriter;
    private final GridTopologyQuarantine quarantine;

    GridModuleWriter(GridSnapshotReader snapshots, GridSnapshotReader.Access values,
            GridTopologyContext contexts, Access nativeWriter, GridTopologyQuarantine quarantine) {
        this.snapshots = snapshots; this.values = values; this.contexts = contexts; this.nativeWriter = nativeWriter;
        this.quarantine = quarantine;
    }
    boolean quarantined() { return quarantine.quarantined(); }
    private void available() { quarantine.requireAvailable(); }
    void move(Object proxy, UUID trackId, UUID moduleId, Move request, Runnable check, Runnable dispatch,
            Consumer<JsonObject> complete, Consumer<Throwable> fail) {
        edit(proxy, trackId, moduleId, request, check, dispatch, complete, fail);
    }
    void delete(Object proxy, UUID trackId, UUID moduleId, Runnable check, Runnable dispatch,
            Consumer<JsonObject> complete, Consumer<Throwable> fail) {
        edit(proxy, trackId, moduleId, null, check, dispatch, complete, fail);
    }
    private void edit(Object proxy, UUID trackId, UUID moduleId, Move request, Runnable check, Runnable dispatch,
            Consumer<JsonObject> complete, Consumer<Throwable> fail) {
        available(); values.documentThread(); check.run();
        GridSnapshotReader.NativeTarget target = snapshots.target(proxy, trackId);
        Layout before = layout(target.graph, check);
        Row selected = before.require(moduleId);
        if (request != null) request.preflight(before, selected);
        GridTopologyContext.Capture context = contexts.capture(target.target, target.graph, before.modules, null, check);
        boolean changed = request == null || request.x != selected.x || request.y != selected.y;
        Runnable mutation = !changed ? null : request == null ? nativeWriter.prepareDelete(target.graph, selected.module)
                : nativeWriter.prepareMove(target.graph, selected.module, request.x, request.y);
        snapshots.guardTarget(target, proxy, trackId);
        Layout ready = layout(target.graph, check);
        before.sameLayout(ready);
        if (request != null) request.preflight(ready, selected);
        contexts.capture(target.target, target.graph, ready.modules, context, check);
        snapshots.guardTarget(target, proxy, trackId);
        check.run(); available();
        if (changed) {
            dispatch.run();
            quarantine.invoke(mutation);
        }
        // The native move callback precedes close. Only native return may enqueue this later document event.
        nativeWriter.execute(() -> {
            try {
                values.documentThread(); check.run();
                snapshots.guardTarget(target, proxy, trackId);
                Layout after = layout(target.graph, check);
                contexts.capture(target.target, target.graph, after.modules, context, check);
                before.survivors(after, request == null ? selected : null);
                if (!changed) before.sameLayout(after);
                else if (request != null) before.requireMoveLayout(after, selected, request);
                JsonObject graph = snapshots.snapshot(proxy, trackId, 0, MAX_MODULES, false, check);
                after.sameLayout(layout(target.graph, check));
                snapshots.guardTarget(target, proxy, trackId);
                contexts.capture(target.target, target.graph, after.modules, context, check);
                check.run();
                JsonObject result = HostServices.observed();
                result.addProperty("moduleId", moduleId.toString());
                result.addProperty("operation", request == null ? "delete" : "move");
                result.add("previousPosition", selected.position());
                if (request == null) {
                    result.addProperty("removedModuleId", moduleId.toString());
                    result.addProperty("removed", true);
                } else {
                    Row actual = after.require(moduleId);
                    result.add("requestedPosition", position(request.x, request.y));
                    result.add("actualPosition", actual.position());
                    result.addProperty("matchesRequested", actual.x == request.x && actual.y == request.y);
                }
                result.add("graph", graph);
                result.addProperty("mutationDispatched", changed);
                result.addProperty("nativeMutation", changed);
                result.addProperty("nativeUndo", changed);
                result.addProperty("verification", changed ? "later_document_event_bounded_native_graph_observation" : "already_matched");
                result.addProperty("nativeUndoScope", changed ? "native_operation_not_verified_grouping_or_commit" : "no_native_operation");
                result.addProperty("undoGroupingVerified", false);
                result.addProperty("engineAcknowledgement", false);
                result.addProperty("safetyScope", "observed_stopped_nonrecording_nonautomation_not_native_transport_atomicity");
                result.addProperty("contextScope", "same_project_root_default_Dt_idle_undo_lock_owner_observations_not_iqa_idle_or_atomic_permission");
                result.addProperty("graphScope", "all_bounded_surviving_layout_and_mapped_incoming_connections_not_DSP_or_opaque_nested_children");
                result.addProperty("maximumModules", MAX_MODULES);
                result.addProperty("maximumOccupiedYSpan", MAX_Y_SPAN);
                complete.accept(result);
            } catch (Throwable error) { fail.accept(error); }
        });
    }

    private Layout layout(Object graph, Runnable check) {
        List<Object> modules = snapshots.topologyModules(graph, check);
        List<Row> rows = new ArrayList<>(modules.size());
        for (Object module : modules) {
            check.run();
            rows.add(new Row(module, (UUID) values.read(INSTANCE_ID, module), integer(X, module), integer(Y, module),
                    integer(WIDTH, module), integer(HEIGHT, module)));
        }
        Layout result = new Layout(modules, rows); result.bounds(null, null); return result;
    }
    private int integer(GridSnapshotReader.Field field, Object module) {
        Object value = values.read(field, module);
        if (!(value instanceof Integer)) throw failure("grid_unavailable", "Native topology geometry reader unavailable: " + field);
        return (Integer) value;
    }
    static JsonObject position(int x, int y) {
        JsonObject result = new JsonObject(); result.addProperty("x", x); result.addProperty("y", y); return result;
    }
    static final class Move {
        final int expectedX, expectedY, x, y;
        Move(int expectedX, int expectedY, int x, int y) {
            this.expectedX = expectedX; this.expectedY = expectedY; this.x = x; this.y = y;
        }
        static Move parse(JsonObject p) {
            return new Move(coordinate(p, "expectedX"), coordinate(p, "expectedY"), coordinate(p, "x"), coordinate(p, "y"));
        }
        static int coordinate(JsonObject p, String key) {
            JsonElement value = p.get(key);
            if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()
                    && INTEGER_TOKEN.matcher(value.getAsString()).matches()) {
                try { return Integer.parseInt(value.getAsString()); } catch (NumberFormatException ignored) { }
            }
            throw failure("invalid_params", key + " must be a signed 32-bit JSON integer token, not a boolean, string, decimal or exponent.");
        }
        void preflight(Layout layout, Row selected) {
            if (selected.x != expectedX || selected.y != expectedY)
                throw failure("stale_context", "Exact native module position differs from expectedX/expectedY; reacquire grid.get before editing.");
            checked((long) x - selected.x); checked((long) y - selected.y);
            for (Row row : layout.rows)
                if (row.module != selected.module && x >= row.x && x < (long) row.x + row.width
                        && y >= row.y && y < (long) row.y + row.height)
                    throw failure("invalid_params", "The requested native anchor lies inside another module's occupied footprint; choose another destination.");
            layout.bounds(selected, this);
        }
    }
    private static void checked(long value) {
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE)
            throw failure("grid_bounds_exceeded", "Native topology coordinates, endpoints, translation or cumulative horizontal reflow could overflow int32.");
    }
    private static final class Row {
        final Object module; final UUID id; final int x, y, width, height;
        Row(Object module, UUID id, int x, int y, int width, int height) {
            this.module = module; this.id = id; this.x = x; this.y = y; this.width = width; this.height = height;
        }
        JsonObject position() { return GridModuleWriter.position(x, y); }
    }
    private static final class Layout {
        final List<Object> modules;
        final List<Row> rows;
        Layout(List<Object> modules, List<Row> rows) { this.modules = modules; this.rows = rows; }
        Row find(UUID id) {
            for (Row row : rows) if (row.id.equals(id)) return row;
            return null;
        }
        Row require(UUID id) {
            Row row = find(id);
            if (row == null) throw failure("stale_context", "Requested current module UUID is absent; reacquire grid.get. Deletion is not missing-is-success.");
            return row;
        }
        void bounds(Row moved, Move request) {
            if (rows.isEmpty()) return;
            long minY = Long.MAX_VALUE, maxY = Long.MIN_VALUE, maxX = Long.MIN_VALUE, widths = 0;
            for (Row row : rows) {
                if (row.width <= 0 || row.height <= 0)
                    throw failure("grid_bounds_exceeded", "Topology editing requires positive native module footprints.");
                long x = row == moved ? request.x : row.x, y = row == moved ? request.y : row.y;
                checked(x + row.width); checked(y + row.height);
                minY = Math.min(minY, y); maxY = Math.max(maxY, y + row.height);
                maxX = Math.max(maxX, x); widths += row.width;
            }
            if (maxY - minY > MAX_Y_SPAN)
                throw failure("grid_bounds_exceeded", "Complete occupied native y-span exceeds 65536; topology editing is bounded before native allocation.");
            // Conservative upper bounds, not a competing reflow algorithm: native BcP may push every box right.
            checked(maxX + widths);
        }
        void survivors(Layout after, Row removed) {
            if (after.rows.size() != rows.size() - (removed == null ? 0 : 1))
                throw failure("grid_verification_failed", "Later native graph membership differs from the selective operation; inspect state before another write.");
            for (Row row : rows) {
                Row actual = after.find(row.id);
                if (row == removed) {
                    if (actual != null) throw failure("grid_verification_failed", "Requested module remains in the later native graph; native return did not prove deletion.");
                } else if (actual == null || actual.module != row.module || actual.width != row.width || actual.height != row.height)
                    throw failure("stale_context", "Surviving native module identity or footprint changed during topology editing.");
            }
        }
        void requireMoveLayout(Layout after, Row selected, Move request) {
            for (Row row : rows) {
                Row actual = after.require(row.id);
                int baselineX = row == selected ? request.x : row.x;
                int baselineY = row == selected ? request.y : row.y;
                // Native placement sets the selected row; BcP may only push each module rightward.
                if (actual.y != baselineY || actual.x < baselineX)
                    throw failure("grid_verification_failed", "Later native layout violates move/reflow row or rightward-position invariants; inspect state before another write.");
            }
        }
        void sameLayout(Layout after) {
            survivors(after, null);
            for (Row row : rows) {
                Row actual = after.require(row.id);
                if (actual.x != row.x || actual.y != row.y)
                    throw failure("stale_context", "Native graph layout changed during bounded capture.");
            }
        }
    }
}
