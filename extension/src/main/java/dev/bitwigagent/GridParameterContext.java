package dev.bitwigagent;

import com.google.gson.*;
import java.util.Locale;
import static dev.bitwigagent.HostServices.failure;

/** Live native prerequisite observations and guards; identities stay in one document callback. */
final class GridParameterContext {
    private static final String[] LIFECYCLE_LABELS = {
        "deviceDeleted", "graphDeleted", "moduleDeleted", "parameterDeleted", "projectDeleted"
    };
    enum Field {
        PARAMETER_PROJECT, LICENSED, GUI_FLOAT, GUI_INTEGER, GUI_ENUM, GUI_BOOLEAN, GUI_PANEL_BOOLEAN, GUI_PANEL_STRING,
        SETTABLE, CIU, TOUCHED, UNDO, UNDO_EXECUTING, UNDO_ACTIVE, UNDO_ICL, LOCK, LOCK_OWNER
    }
    interface Access extends NativeReadContext.Access {
        Object read(Field field, Object object);
        boolean defaultContext(Object context);
    }
    private final Access a;
    private final NativeReadContext readContexts;
    GridParameterContext(Access access) { a = access; readContexts = new NativeReadContext(access); }

    Capture capture(Object device, Object graph, Object module, Object parameter,
            GridSnapshotReader.Kind kind, Runnable check) {
        return capture(device, graph, module, parameter, kind, check, true, false);
    }

    private Capture capture(Object device, Object graph, Object module, Object parameter,
            GridSnapshotReader.Kind kind, Runnable check, boolean serialize, boolean writing) {
        check.run();
        boolean panel = kind == GridSnapshotReader.Kind.PANEL_BOOLEAN || kind == GridSnapshotReader.Kind.PANEL_STRING;
        Object project = required(a.read(Field.PARAMETER_PROJECT, parameter), "Owning project");
        NativeReadContext.Capture common = readContexts.capture(project, new Object[]{device, graph, module, parameter},
                LIFECYCLE_LABELS, check, serialize);
        Object context = common.context, root = common.root, current = common.current;
        Object currentContext = common.currentContext, defaultDt = common.defaultDt, defaultDtContext = common.defaultDtContext;
        boolean initialized = common.initialized, hasDefault = common.hasDefault;
        Field wrapperField;
        switch (kind) {
            case FLOAT: wrapperField = Field.GUI_FLOAT; break;
            case INTEGER: wrapperField = Field.GUI_INTEGER; break;
            case ENUM: wrapperField = Field.GUI_ENUM; break;
            case BOOLEAN: wrapperField = Field.GUI_BOOLEAN; break;
            case PANEL_BOOLEAN: wrapperField = Field.GUI_PANEL_BOOLEAN; break;
            case PANEL_STRING: wrapperField = Field.GUI_PANEL_STRING; break;
            default: throw failure("grid_unavailable", "Parameter context family is unsupported.");
        }
        // These getters construct fresh wrappers; wrapper identity is not native model identity.
        Object wrapper = serialize || writing ? required(a.read(wrapperField, parameter), "Native GUI property") : null;
        Object undo = a.read(Field.UNDO, parameter);
        Object lock = a.read(Field.LOCK, device);
        Object lockOwner = lock == null ? null : a.read(Field.LOCK_OWNER, lock);
        Object lockContext = lockOwner == null ? null : a.read(NativeReadContext.Field.DT_CONTEXT, lockOwner);
        check.run();
        Object[] identities = {project, context, root, current, currentContext, defaultDt,
                defaultDtContext, undo, lock, lockOwner, lockContext};
        if (writing) {
            if (!initialized) throw failure("unsafe_state", "Native document context is not initialized.");
            if (root != project) throw failure("stale_context", "Attached context root is not the owning project.");
            if (!hasDefault || current == null || defaultDt == null)
                throw failure("grid_unavailable", "Native current/default transaction context is unavailable.");
            if (current != defaultDt || defaultDtContext != context)
                throw failure("unsafe_state", "Native transaction is outside the bounded default document context.");
            if (!panel && !bool(Field.LICENSED, parameter))
                throw failure("unsafe_state", "Native parameter effective licensing is false.");
            if (!bool(Field.SETTABLE, wrapper))
                throw failure("unsafe_state", "Native GUI property is not settable.");
            if (bool(Field.CIU, parameter) || (!panel && bool(Field.TOUCHED, parameter)))
                throw failure("unsafe_state", "Native parameter skip or active touch state blocks the write.");
            required(undo, "Ancestor undo manager");
            if (bool(Field.UNDO_EXECUTING, undo) || bool(Field.UNDO_ACTIVE, undo) || bool(Field.UNDO_ICL, undo))
                throw failure("unsafe_state", "Native undo manager is outside the bounded idle state.");
            required(lock, "Device lock"); required(lockOwner, "Device lock owner");
            if (lockOwner != current || lockOwner != defaultDt || lockContext != context)
                throw failure("unsafe_state", "Native device lock owner differs from the current/default context.");
            check.run();
        }
        if (!serialize) return new Capture(null, identities);
        JsonObject result = new JsonObject();
        result.addProperty("scope", "same_document_callback_native_prerequisite_observations_not_write_permission_or_readiness");
        result.addProperty("modelClass", parameter.getClass().getName());
        result.addProperty("family", kind.name().toLowerCase(Locale.ROOT));
        result.add("lifecycle", common.lifecycle);
        result.addProperty("sameOwningProject", true);
        result.addProperty("sameAttachedContext", true);
        result.addProperty("applicationDocumentThread", true);
        result.addProperty("modelScopedExecutorThread", true);
        result.add("attachedContext", common.attachedContext);
        nullable(result, "effectivelyLicensed", panel ? null : bool(Field.LICENSED, parameter));
        if (panel) result.addProperty("scalarLicenseTouchApplicable", false);
        JsonObject property = new JsonObject();
        property.addProperty("modelClass", wrapper.getClass().getName());
        property.addProperty("isSettable", bool(Field.SETTABLE, wrapper));
        property.addProperty("scope", "gui_property_descriptor_flag_only");
        result.add("guiProperty", property);
        result.addProperty("nativeCIu", bool(Field.CIU, parameter));
        nullable(result, "parameterTouched", panel ? null : bool(Field.TOUCHED, parameter));
        JsonObject undoState = new JsonObject(); undoState.addProperty("present", undo != null);
        undoState.addProperty("modelClass", undo == null ? null : GridSnapshotReader.text(undo.getClass().getName()));
        nullable(undoState, "executingUndoRedo", undo == null ? null : bool(Field.UNDO_EXECUTING, undo));
        nullable(undoState, "activeChange", undo == null ? null : bool(Field.UNDO_ACTIVE, undo));
        nullable(undoState, "nativeICL", undo == null ? null : bool(Field.UNDO_ICL, undo));
        result.add("ancestorUndoManager", undoState);
        JsonObject lockState = new JsonObject(); lockState.addProperty("present", lock != null);
        nullable(lockState, "ownerPresent", lock == null ? null : lockOwner != null);
        nullable(lockState, "ownerIsCurrentDt", lockOwner == null || current == null ? null : lockOwner == current);
        nullable(lockState, "ownerIsDefaultDt", lockOwner == null || defaultDt == null ? null : lockOwner == defaultDt);
        nullable(lockState, "ownerContextMatches", lockOwner == null ? null : lockContext == context);
        result.add("deviceLock", lockState);
        result.addProperty("nativeMutation", false); result.addProperty("nativeUndo", false);
        check.run();
        return new Capture(result, identities);
    }
    void guard(Capture before, Object device, Object graph, Object module, Object parameter,
            GridSnapshotReader.Kind kind, Runnable check) {
        Capture after = capture(device, graph, module, parameter, kind, check, false, false);
        sameIdentities(before, after);
    }
    Capture requireWritable(GridSnapshotReader.ParameterBinding binding, Capture before, Runnable check) {
        Capture current = capture(binding.device, binding.graph, binding.module, binding.parameter,
                binding.kind, check, false, true);
        if (before != null) sameIdentities(before, current);
        return current;
    }
    private static void sameIdentities(Capture before, Capture after) {
        for (int i = 0; i < before.identities.length; i++)
            if (before.identities[i] != after.identities[i])
                throw failure("stale_context", "Native parameter inspection context identity changed during capture.");
    }
    private boolean bool(Field field, Object object) {
        Object value = a.read(field, object);
        if (!(value instanceof Boolean)) throw failure("grid_unavailable", "Native context predicate unavailable: " + field);
        return (Boolean) value;
    }
    private static Object required(Object value, String label) {
        if (value == null) throw failure("grid_unavailable", label + " is unavailable.");
        return value;
    }
    private static void nullable(JsonObject object, String key, Boolean value) {
        if (value == null) object.add(key, JsonNull.INSTANCE); else object.addProperty(key, value);
    }
    static final class Capture {
        final JsonObject json;
        private final Object[] identities;
        Capture(JsonObject json, Object[] identities) { this.json = json; this.identities = identities; }
    }
}
