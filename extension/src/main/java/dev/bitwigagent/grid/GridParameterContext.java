package dev.bitwigagent.grid;

import com.google.gson.*;
import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.host.NativeReadContext;
import java.util.Locale;

/** Live native prerequisite observations and guards; identities stay in one document callback. */
public final class GridParameterContext {
    private static final String[] LIFECYCLE_LABELS = {
        "deviceDeleted", "graphDeleted", "moduleDeleted", "parameterDeleted", "projectDeleted"
    };
    private static final String[] VOICING_LABELS = {"deviceDeleted", "graphDeleted", "voicingDeleted", "projectDeleted"
    };
    private static final String[] REMOTE_LABELS = {
        "deviceDeleted", "remoteHostDeleted", "devicePagesDeleted", "projectDeleted"
    };

    public enum Field {
        PARAMETER_PROJECT,
        LICENSED,
        GUI_FLOAT,
        GUI_INTEGER,
        GUI_ENUM,
        GUI_BOOLEAN,
        GUI_PANEL_BOOLEAN,
        GUI_PANEL_STRING,
        GUI_STEP_ARRAY,
        GUI_VOICING,
        SETTABLE,
        CIU,
        TOUCHED,
        UNDO,
        UNDO_EXECUTING,
        UNDO_ACTIVE,
        UNDO_ICL,
        LOCK,
        LOCK_OWNER
    }

    public interface Access extends NativeReadContext.Access {
        Object read(Field field, Object object);

        boolean defaultContext(Object context);
    }

    private final Access a;
    private final NativeReadContext readContexts;

    public GridParameterContext(Access access) {
        a = access;
        readContexts = new NativeReadContext(access);
    }

    Capture capture(
            Object device,
            Object graph,
            Object module,
            Object parameter,
            GridSnapshotReader.Kind kind,
            Runnable check) {
        return capture(device, graph, module, parameter, kind, check, true, false);
    }

    private Capture capture(
            Object device,
            Object graph,
            Object module,
            Object parameter,
            GridSnapshotReader.Kind kind,
            Runnable check,
            boolean serialize,
            boolean writing) {
        check.run();
        boolean panel = kind == GridSnapshotReader.Kind.PANEL_BOOLEAN
                || kind == GridSnapshotReader.Kind.PANEL_STRING
                || kind == GridSnapshotReader.Kind.STEP_ARRAY
                || kind == GridSnapshotReader.Kind.VOICING;
        // The device voicing model is owned by the device itself: no module level (jBG is a K2G, so the skip predicate
        // applies).
        boolean voicing = kind == GridSnapshotReader.Kind.VOICING;
        Object project = required(a.read(Field.PARAMETER_PROJECT, parameter), "Owning project");
        NativeReadContext.Capture common = readContexts.capture(
                project,
                voicing ? new Object[] {device, graph, parameter} : new Object[] {device, graph, module, parameter},
                voicing ? VOICING_LABELS : LIFECYCLE_LABELS,
                check,
                serialize);
        Object context = common.context, root = common.root, current = common.current;
        Object currentContext = common.currentContext,
                defaultDt = common.defaultDt,
                defaultDtContext = common.defaultDtContext;
        boolean initialized = common.initialized, hasDefault = common.hasDefault;
        Field wrapperField;
        switch (kind) {
            case FLOAT:
                wrapperField = Field.GUI_FLOAT;
                break;
            case INTEGER:
                wrapperField = Field.GUI_INTEGER;
                break;
            case ENUM:
                wrapperField = Field.GUI_ENUM;
                break;
            case BOOLEAN:
                wrapperField = Field.GUI_BOOLEAN;
                break;
            case PANEL_BOOLEAN:
                wrapperField = Field.GUI_PANEL_BOOLEAN;
                break;
            case PANEL_STRING:
                wrapperField = Field.GUI_PANEL_STRING;
                break;
            case STEP_ARRAY:
                wrapperField = Field.GUI_STEP_ARRAY;
                break;
            case VOICING:
                wrapperField = Field.GUI_VOICING;
                break;
            default:
                throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "Parameter context family is unsupported.");
        }
        // These getters construct fresh wrappers; wrapper identity is not native model identity.
        Object wrapper = serialize || writing ? required(a.read(wrapperField, parameter), "Native GUI property") : null;
        Object undo = a.read(Field.UNDO, parameter);
        Object lock = a.read(Field.LOCK, device);
        Object lockOwner = lock == null ? null : a.read(Field.LOCK_OWNER, lock);
        Object lockContext = lockOwner == null ? null : a.read(NativeReadContext.Field.DT_CONTEXT, lockOwner);
        check.run();
        Object[] identities = {
            project,
            context,
            root,
            current,
            currentContext,
            defaultDt,
            defaultDtContext,
            undo,
            lock,
            lockOwner,
            lockContext
        };
        if (writing) {
            if (!initialized)
                throw Errors.notApplied(Reason.UNSAFE_STATE, "Native document context is not initialized.");
            if (root != project)
                throw Errors.notApplied(Reason.TARGET_CHANGED, "Attached context root is not the owning project.");
            if (!hasDefault || current == null || defaultDt == null)
                throw Errors.notApplied(
                        Reason.NATIVE_UNAVAILABLE, "Native current/default transaction context is unavailable.");
            if (current != defaultDt || defaultDtContext != context)
                throw Errors.notApplied(
                        Reason.UNSAFE_STATE, "Native transaction is outside the bounded default document context.");
            if (!panel && !bool(Field.LICENSED, parameter))
                throw Errors.notApplied(Reason.UNSAFE_STATE, "Native parameter effective licensing is false.");
            if (!bool(Field.SETTABLE, wrapper))
                throw Errors.notApplied(Reason.UNSAFE_STATE, "Native GUI property is not settable.");
            if (bool(Field.CIU, parameter) || (!panel && bool(Field.TOUCHED, parameter)))
                throw Errors.notApplied(
                        Reason.UNSAFE_STATE, "Native parameter skip or active touch state blocks the write.");
            required(undo, "Ancestor undo manager");
            if (bool(Field.UNDO_EXECUTING, undo) || bool(Field.UNDO_ACTIVE, undo) || bool(Field.UNDO_ICL, undo))
                throw Errors.notApplied(Reason.UNSAFE_STATE, "Native undo manager is outside the bounded idle state.");
            required(lock, "Device lock");
            required(lockOwner, "Device lock owner");
            if (lockOwner != current || lockOwner != defaultDt || lockContext != context)
                throw Errors.notApplied(
                        Reason.UNSAFE_STATE, "Native device lock owner differs from the current/default context.");
            check.run();
        }
        if (!serialize) return new Capture(null, identities);
        JsonObject result = new JsonObject();
        result.addProperty(
                "scope", "same_document_callback_native_prerequisite_observations_not_write_permission_or_readiness");
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
        JsonObject undoState = new JsonObject();
        undoState.addProperty("present", undo != null);
        undoState.addProperty(
                "modelClass",
                undo == null ? null : GridSnapshotReader.text(undo.getClass().getName()));
        nullable(undoState, "executingUndoRedo", undo == null ? null : bool(Field.UNDO_EXECUTING, undo));
        nullable(undoState, "activeChange", undo == null ? null : bool(Field.UNDO_ACTIVE, undo));
        nullable(undoState, "nativeICL", undo == null ? null : bool(Field.UNDO_ICL, undo));
        result.add("ancestorUndoManager", undoState);
        JsonObject lockState = new JsonObject();
        lockState.addProperty("present", lock != null);
        nullable(lockState, "ownerPresent", lock == null ? null : lockOwner != null);
        nullable(lockState, "ownerIsCurrentDt", lockOwner == null || current == null ? null : lockOwner == current);
        nullable(lockState, "ownerIsDefaultDt", lockOwner == null || defaultDt == null ? null : lockOwner == defaultDt);
        nullable(lockState, "ownerContextMatches", lockOwner == null ? null : lockContext == context);
        result.add("deviceLock", lockState);
        result.addProperty("nativeMutation", false);
        result.addProperty("nativeUndo", false);
        check.run();
        return new Capture(result, identities);
    }

    void guard(
            Capture before,
            Object device,
            Object graph,
            Object module,
            Object parameter,
            GridSnapshotReader.Kind kind,
            Runnable check) {
        Capture after = capture(device, graph, module, parameter, kind, check, false, false);
        sameIdentities(before, after);
    }
    /** Voicing is a device-level model: observation and write guards without a module or scalar parameter. */
    Capture captureVoicing(Object device, Object graph, Object voicing, Runnable check) {
        return capture(device, graph, null, voicing, GridSnapshotReader.Kind.VOICING, check, true, false);
    }

    Capture requireVoicingWritable(Object device, Object graph, Object voicing, Capture before, Runnable check) {
        Capture current = capture(device, graph, null, voicing, GridSnapshotReader.Kind.VOICING, check, false, true);
        if (before != null) sameIdentities(before, current);
        return current;
    }

    void guardVoicing(Capture before, Object device, Object graph, Object voicing, Runnable check) {
        sameIdentities(
                before, capture(device, graph, null, voicing, GridSnapshotReader.Kind.VOICING, check, false, false));
    }
    /**
     * Remote Controls pages are device-owned models (no GUI property, license or touch of their own): the device's
     * project, attached context, default transaction, skip predicate, undo manager and lock owner gate the write.
     */
    Capture requireRemoteWritable(Object device, Object host, Object pages, Capture before, Runnable check) {
        Capture current = remote(device, host, pages, check, true);
        if (before != null) sameIdentities(before, current);
        return current;
    }

    void guardRemote(Capture before, Object device, Object host, Object pages, Runnable check) {
        sameIdentities(before, remote(device, host, pages, check, false));
    }

    private Capture remote(Object device, Object host, Object pages, Runnable check, boolean writing) {
        check.run();
        Object project = required(a.read(Field.PARAMETER_PROJECT, device), "Owning project");
        NativeReadContext.Capture common =
                readContexts.capture(project, new Object[] {device, host, pages}, REMOTE_LABELS, check, false);
        Object undo = a.read(Field.UNDO, device);
        Object lock = a.read(Field.LOCK, device);
        Object lockOwner = lock == null ? null : a.read(Field.LOCK_OWNER, lock);
        Object lockContext = lockOwner == null ? null : a.read(NativeReadContext.Field.DT_CONTEXT, lockOwner);
        check.run();
        if (writing) {
            if (!common.initialized)
                throw Errors.notApplied(Reason.UNSAFE_STATE, "Native document context is not initialized.");
            if (common.root != project)
                throw Errors.notApplied(Reason.TARGET_CHANGED, "Attached context root is not the owning project.");
            if (!common.hasDefault || common.current == null || common.defaultDt == null)
                throw Errors.notApplied(
                        Reason.NATIVE_UNAVAILABLE, "Native current/default transaction context is unavailable.");
            if (common.current != common.defaultDt || common.defaultDtContext != common.context)
                throw Errors.notApplied(
                        Reason.UNSAFE_STATE, "Native transaction is outside the bounded default document context.");
            if (bool(Field.CIU, device))
                throw Errors.notApplied(Reason.UNSAFE_STATE, "Native device skip state blocks the write.");
            required(undo, "Ancestor undo manager");
            if (bool(Field.UNDO_EXECUTING, undo) || bool(Field.UNDO_ACTIVE, undo) || bool(Field.UNDO_ICL, undo))
                throw Errors.notApplied(Reason.UNSAFE_STATE, "Native undo manager is outside the bounded idle state.");
            required(lock, "Device lock");
            required(lockOwner, "Device lock owner");
            if (lockOwner != common.current || lockOwner != common.defaultDt || lockContext != common.context)
                throw Errors.notApplied(
                        Reason.UNSAFE_STATE, "Native device lock owner differs from the current/default context.");
            check.run();
        }
        return new Capture(null, new Object[] {
            project,
            common.context,
            common.root,
            common.current,
            common.currentContext,
            common.defaultDt,
            common.defaultDtContext,
            undo,
            lock,
            lockOwner,
            lockContext
        });
    }

    Capture requireWritable(GridSnapshotReader.ParameterBinding binding, Capture before, Runnable check) {
        Capture current = capture(
                binding.device, binding.graph, binding.module, binding.parameter, binding.kind, check, false, true);
        if (before != null) sameIdentities(before, current);
        return current;
    }
    /** The same write prerequisites for a scalar whose lifecycle owners are not a Grid graph/module (device modulators). */
    public Capture requireWritable(
            Object device,
            Object container,
            Object owner,
            Object parameter,
            GridSnapshotReader.Kind kind,
            Capture before,
            Runnable check) {
        Capture current = capture(device, container, owner, parameter, kind, check, false, true);
        if (before != null) sameIdentities(before, current);
        return current;
    }

    private static void sameIdentities(Capture before, Capture after) {
        for (int i = 0; i < before.identities.length; i++)
            if (before.identities[i] != after.identities[i])
                throw Errors.notApplied(
                        Reason.TARGET_CHANGED, "Native parameter inspection context identity changed during capture.");
    }

    private boolean bool(Field field, Object object) {
        Object value = a.read(field, object);
        if (!(value instanceof Boolean))
            throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "Native context predicate unavailable: " + field);
        return (Boolean) value;
    }

    private static Object required(Object value, String label) {
        if (value == null) throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, label + " is unavailable.");
        return value;
    }

    private static void nullable(JsonObject object, String key, Boolean value) {
        if (value == null) object.add(key, JsonNull.INSTANCE);
        else object.addProperty(key, value);
    }

    static final class Capture {
        final JsonObject json;
        private final Object[] identities;

        Capture(JsonObject json, Object[] identities) {
            this.json = json;
            this.identities = identities;
        }
    }
}
