package dev.bitwigagent.bitwig614;

import static dev.bitwigagent.grid.GridParameterContext.Field.*;

import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.grid.GridParameterContext;

/** Additional read-only 6.1.4 descriptors, gated separately from ordinary graph serialization. */
final class Bitwig614GridParameterContext extends Bitwig614ContextReaders<GridParameterContext.Field>
        implements GridParameterContext.Access {
    private static final String R = "com.bitwig.ramona.core.";
    private final Bitwig614GridBooleanPanelAccess panels;
    private final Bitwig614StringPanelAccess strings;
    private final Bitwig614StepArrayAccess steps;
    private final Bitwig614VoicingAccess voicing;

    Bitwig614GridParameterContext(
            Bitwig614Runtime.Access bindings,
            Bitwig614GridBooleanPanelAccess panels,
            Bitwig614StringPanelAccess strings,
            Bitwig614StepArrayAccess steps,
            Bitwig614VoicingAccess voicing)
            throws ReflectiveOperationException {
        super(GridParameterContext.Field.class, bindings);
        this.panels = panels;
        this.strings = strings;
        this.steps = steps;
        this.voicing = voicing;
        Class<?> model = bindings.type(R + "AVL");
        Class<?> dt = bindings.type(R + "XKG"), undo = bindings.type("dfI"), lock = bindings.type(R + "IOJ");
        bind(PARAMETER_PROJECT, bindings.type("K2G"), "ce_", bindings.type("com.bitwig.flt.document.core.master.g2L"));
        bind(LICENSED, bindings.type("aLw"), "d3", boolean.class);
        bind(GUI_FLOAT, bindings.type("Opy"), "Lcd", bindings.type(R + "iYN"));
        bind(GUI_INTEGER, bindings.type("Igl"), "TMF", bindings.type(R + "SYo"));
        bind(GUI_ENUM, bindings.type("RSJ"), "lYc", bindings.type(R + "SYo"));
        bind(GUI_BOOLEAN, bindings.type("Qe"), "iCS", bindings.type(R + "lBQ"));
        bind(SETTABLE, bindings.type(R + "Dgc"), "isSettable", boolean.class);
        bind(CIU, bindings.type("K2G"), "qZj", boolean.class);
        bind(TOUCHED, bindings.type("bDH"), "qI1", boolean.class);
        bindUndo(UNDO, undo, model);
        bind(UNDO_EXECUTING, undo, "iCS", boolean.class);
        bind(UNDO_ACTIVE, undo, "iHb", boolean.class);
        bind(UNDO_ICL, undo, "nVd", boolean.class);
        bind(LOCK, bindings.type("com.bitwig.flt.document.core.master.device.FDA"), "cm_", lock);
        bind(LOCK_OWNER, lock, "IwV", dt);
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
