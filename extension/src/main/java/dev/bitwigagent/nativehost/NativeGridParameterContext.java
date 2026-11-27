package dev.bitwigagent.nativehost;

import static dev.bitwigagent.grid.GridParameterContext.Field.*;

import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.grid.GridParameterContext;

/** Additional read-only descriptors, gated separately from ordinary graph serialization. */
final class NativeGridParameterContext extends NativeContextReaders<GridParameterContext.Field>
        implements GridParameterContext.Access {
    private final NativeGridBooleanPanelAccess panels;
    private final NativeStringPanelAccess strings;
    private final NativeStepArrayAccess steps;
    private final NativeVoicingAccess voicing;

    NativeGridParameterContext(
            NativeHost.Access bindings,
            NativeGridBooleanPanelAccess panels,
            NativeStringPanelAccess strings,
            NativeStepArrayAccess steps,
            NativeVoicingAccess voicing)
            throws ReflectiveOperationException {
        super(GridParameterContext.Field.class, bindings);
        this.panels = panels;
        this.strings = strings;
        this.steps = steps;
        this.voicing = voicing;
        Class<?> model = bindings.type("model.object");
        Class<?> dt = bindings.type("model.thread"), undo = bindings.type("document.undo");
        Class<?> lock = bindings.type("model.lock");
        bind(PARAMETER_PROJECT, "model.element.project", bindings.type("project"));
        bind(LICENSED, "parameter.controller.licensed", boolean.class);
        bind(GUI_FLOAT, "parameter.float.guiProperty", bindings.type("model.floatProperty"));
        bind(GUI_INTEGER, "parameter.integer.guiProperty", bindings.type("model.valueProperty"));
        bind(GUI_ENUM, "parameter.enum.guiProperty", bindings.type("model.valueProperty"));
        bind(GUI_BOOLEAN, "parameter.boolean.guiProperty", bindings.type("model.booleanProperty"));
        bind(SETTABLE, "model.property.isSettable", boolean.class);
        bind(CIU, "model.element.skipped", boolean.class);
        bind(TOUCHED, "parameter.touched", boolean.class);
        bindUndo(UNDO, "document.undo.of", undo, model);
        bind(UNDO_EXECUTING, "document.undo.executing", boolean.class);
        bind(UNDO_ACTIVE, "document.undo.active", boolean.class);
        bind(UNDO_ICL, "document.undo.recording", boolean.class);
        bind(LOCK, "device.element.lock", lock);
        bind(LOCK_OWNER, "model.lock.owner", dt);
    }

    @Override
    public Object read(GridParameterContext.Field field, Object object) {
        if (field == GUI_PANEL_BOOLEAN) {
            if (panels == null)
                throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "Boolean panel property binding unavailable.");
            return panels.property(object);
        }
        if (field == GUI_STEP_ARRAY) {
            if (steps == null)
                throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "Step array property binding unavailable.");
            return steps.property(object);
        }
        if (field == GUI_VOICING) {
            if (voicing == null)
                throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "Voicing property binding unavailable.");
            return voicing.property(object);
        }
        if (field == GUI_PANEL_STRING) {
            if (strings == null)
                throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "String panel property binding unavailable.");
            return strings.property(object);
        }
        return invoke(field, UNDO, object, "parameter context");
    }
}
