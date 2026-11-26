package dev.bitwigagent;

import static dev.bitwigagent.GridSnapshotReader.Field.*;

import com.google.gson.*;
import java.util.*;
import java.util.function.Consumer;

/** Raw custom-name CAS on an explicit current module, followed by a separate document-event observation. */
final class GridModuleNameWriter {
    static final int MAX_MODULES = GridTopologyContext.MAX_MODULES, MAX_NAME = GridSnapshotReader.MAX_TEXT;

    interface Access {
        void requireSupported(Object module);

        Runnable prepareSet(Object module, String customName);

        void execute(Runnable observation);
    }

    private final GridSnapshotReader snapshots;
    private final GridSnapshotReader.Access values;
    private final GridTopologyContext contexts;
    private final Access nativeWriter;
    private final GridTopologyQuarantine quarantine;

    GridModuleNameWriter(
            GridSnapshotReader snapshots,
            GridSnapshotReader.Access values,
            GridTopologyContext contexts,
            Access nativeWriter,
            GridTopologyQuarantine quarantine) {
        this.snapshots = snapshots;
        this.values = values;
        this.contexts = contexts;
        this.nativeWriter = nativeWriter;
        this.quarantine = quarantine;
    }

    void rename(
            DeviceSources.Source source,
            UUID moduleId,
            Request request,
            Runnable check,
            Runnable dispatch,
            Consumer<JsonObject> complete,
            Consumer<Throwable> fail) {
        values.documentThread();
        check.run();
        if (!values.metadataAvailable(GridSnapshotReader.Metadata.MODULE_NAMES))
            throw Errors.notApplied(
                    Reason.NATIVE_UNAVAILABLE,
                    "Native raw custom-name and effective-name readers are required for module naming.");
        GridSnapshotReader.NativeTarget target = snapshots.target(source);
        String device = GridTopologyQuarantine.device(target.ownership.trackId, target.graph);
        quarantine.requireAvailable(device);
        JsonObject graphBefore = snapshots.snapshot(source, 0, MAX_MODULES, false, false, null, check);
        Members before = members(target.graph, check);
        Object selected = before.require(moduleId);
        nativeWriter.requireSupported(selected);
        Names previous = names(selected);
        request.requireExpected(previous.custom);
        GridTopologyContext.Capture context =
                contexts.capture(target.target, target.graph, before.modules, null, check);
        boolean changed = !previous.custom.equals(request.customName);
        Runnable mutation = changed ? nativeWriter.prepareSet(selected, request.customName) : null;
        guard(target, source, before, context, check);
        Names ready = names(selected);
        request.requireExpected(ready.custom);
        if (!previous.effective.equals(ready.effective))
            throw Errors.notApplied(Reason.STALE_CONTEXT, "Native effective module name changed before dispatch.");
        check.run();
        quarantine.requireAvailable(device);
        if (changed) {
            dispatch.run();
            quarantine.invoke(device, mutation);
        }
        // Native setter return is not an acknowledged edit, UI Undo group, or DSP commit.
        nativeWriter.execute(() -> {
            try {
                values.documentThread();
                check.run();
                guard(target, source, before, context, check);
                Names actual = names(selected);
                requireActual(actual, request);
                JsonObject graph = snapshots.snapshot(source, 0, MAX_MODULES, false, false, null, check);
                guard(target, source, before, context, check);
                Names finalNames = names(selected);
                requireActual(finalNames, request);
                if (!actual.effective.equals(finalNames.effective))
                    throw Errors.notApplied(
                            Reason.STALE_CONTEXT,
                            "Native effective module name changed during later graph observation.");
                check.run();
                JsonObject result = Results.outcome(changed ? Outcome.APPLIED : Outcome.ALREADY_MATCHED);
                result.addProperty("moduleId", moduleId.toString());
                result.addProperty("operation", "rename");
                result.addProperty("previousCustomName", previous.custom);
                result.addProperty("requestedCustomName", request.customName);
                result.addProperty("actualCustomName", actual.custom);
                result.addProperty("previousName", previous.effective);
                result.addProperty("actualName", actual.effective);
                result.add("graph", graph);
                result.add("graphBefore", graphBefore);
                result.addProperty("mutationDispatched", changed);
                result.addProperty("nativeMutation", changed);
                result.addProperty("nativeUndo", changed);
                result.addProperty("verification", "later_document_event_bounded_native_graph_observation");
                result.addProperty(
                        "nativeUndoScope",
                        changed
                                ? "native_property_bookkeeping_not_verified_UI_transaction_grouping_or_commit"
                                : "no_native_operation");
                result.addProperty("undoGroupingVerified", false);
                result.addProperty("engineAcknowledgement", false);
                result.addProperty(
                        "safetyScope", "observed_stopped_nonrecording_nonautomation_not_native_transport_atomicity");
                result.addProperty(
                        "contextScope",
                        "same_project_root_default_Dt_idle_undo_lock_owner_observations_not_iqa_idle_or_atomic_permission");
                result.addProperty(
                        "graphScope",
                        "all_bounded_actual_modules_and_mapped_incoming_connections_without_parameters_not_DSP_or_opaque_nested_children");
                result.addProperty("maximumModules", MAX_MODULES);
                complete.accept(result);
            } catch (Throwable error) {
                fail.accept(error);
            }
        });
    }

    private void guard(
            GridSnapshotReader.NativeTarget target,
            DeviceSources.Source source,
            Members before,
            GridTopologyContext.Capture context,
            Runnable check) {
        snapshots.guardTarget(target);
        Members current = members(target.graph, check);
        before.requireSame(current);
        contexts.capture(target.target, target.graph, current.modules, context, check);
        snapshots.guardTarget(target);
    }

    private Members members(Object graph, Runnable check) {
        List<Object> modules = snapshots.topologyModules(graph, check);
        Map<UUID, Object> byId = new HashMap<>();
        for (Object module : modules) {
            check.run();
            Object id = values.read(INSTANCE_ID, module);
            if (!(id instanceof UUID) || byId.put((UUID) id, module) != null)
                throw Errors.notApplied(
                        Reason.NATIVE_UNAVAILABLE, "Module naming requires unique native current UUIDs.");
        }
        return new Members(modules, byId);
    }

    private Names names(Object module) {
        return new Names(name(CUSTOM_NAME, module), name(MODULE_NAME, module));
    }

    private String name(GridSnapshotReader.Field field, Object module) {
        Object value = values.read(field, module);
        if (!(value instanceof String))
            throw Errors.notApplied(
                    Reason.NATIVE_UNAVAILABLE, "Native module name reader did not return a String: " + field);
        String text = (String) value;
        if (text.length() > MAX_NAME)
            throw Errors.notApplied(Reason.OUT_OF_BOUNDS, "Native module name exceeds 1024 UTF-16 units.");
        return text;
    }

    private static void requireActual(Names actual, Request request) {
        if (!actual.custom.equals(request.customName))
            throw Errors.notApplied(
                    Reason.READBACK_MISMATCH,
                    "Later raw native customName differs from the request; inspect state before another write.");
    }

    private static final class Names {
        final String custom, effective;

        Names(String custom, String effective) {
            this.custom = custom;
            this.effective = effective;
        }
    }

    private static final class Members {
        final List<Object> modules;
        final Map<UUID, Object> byId;

        Members(List<Object> modules, Map<UUID, Object> byId) {
            this.modules = modules;
            this.byId = byId;
        }

        Object require(UUID id) {
            Object module = byId.get(id);
            if (module == null)
                throw Errors.notApplied(
                        Reason.STALE_CONTEXT,
                        "Requested current module UUID is absent; reacquire grid.export after edits or reopen.");
            return module;
        }

        void requireSame(Members current) {
            if (byId.size() != current.byId.size())
                throw Errors.notApplied(Reason.STALE_CONTEXT, "Native Grid membership changed during module naming.");
            for (Map.Entry<UUID, Object> entry : byId.entrySet())
                if (current.byId.get(entry.getKey()) != entry.getValue())
                    throw Errors.notApplied(
                            Reason.STALE_CONTEXT,
                            "Native module identity or current UUID changed during module naming.");
        }
    }

    static final class Request {
        final String expectedCustomName, customName;

        Request(String expectedCustomName, String customName) {
            this.expectedCustomName = bounded(expectedCustomName, "expectedCustomName");
            this.customName = bounded(customName, "customName");
        }

        static Request parse(JsonObject params) {
            return new Request(string(params, "expectedCustomName"), string(params, "customName"));
        }

        private static String string(JsonObject params, String key) {
            JsonElement value = params.get(key);
            if (value == null
                    || !value.isJsonPrimitive()
                    || !value.getAsJsonPrimitive().isString())
                throw Errors.notApplied(Reason.INVALID_PARAMS, key + " must be a JSON string; empty is allowed.");
            return bounded(value.getAsString(), key);
        }

        private static String bounded(String value, String key) {
            if (value == null || value.length() > MAX_NAME)
                throw Errors.notApplied(
                        Reason.INVALID_PARAMS, key + " must contain at most 1024 UTF-16 units; empty is allowed.");
            return value;
        }

        void requireExpected(String actual) {
            if (!expectedCustomName.equals(actual))
                throw Errors.notApplied(
                        Reason.STALE_CONTEXT,
                        "Raw native customName differs from expectedCustomName; effective and type names are not CAS values.");
        }
    }
}
