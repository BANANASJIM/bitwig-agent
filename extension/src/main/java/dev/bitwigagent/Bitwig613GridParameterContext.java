package dev.bitwigagent;

import java.lang.reflect.Method;
import java.util.EnumMap;
import static dev.bitwigagent.GridParameterContext.Field.*;
import static dev.bitwigagent.Bitwig613GridAdapter.invoke;
import static dev.bitwigagent.HostServices.failure;

/** Additional read-only 6.1.3 descriptors, gated separately from ordinary graph serialization. */
final class Bitwig613GridParameterContext implements GridParameterContext.Access {
    private static final String R = "com.bitwig.ramona.core.";
    private final EnumMap<GridParameterContext.Field, Method> readers = new EnumMap<>(GridParameterContext.Field.class);
    private final Bitwig613GridAdapter bindings;
    private final Class<?> project, defaultContext;
    private final Bitwig613GridBooleanPanelAccess panels;

    Bitwig613GridParameterContext(Bitwig613GridAdapter bindings, Bitwig613GridBooleanPanelAccess panels)
            throws ReflectiveOperationException {
        this.bindings = bindings; this.panels = panels;
        Class<?> model = bindings.type(R + "fJC"), context = bindings.type(R + "HLm");
        Class<?> dt = bindings.type(R + "Dt"), undo = bindings.type("dfW"), lock = bindings.type(R + "IoW");
        project = bindings.type("com.bitwig.flt.document.core.master.WN2");
        defaultContext = bindings.type("dqB");
        bind(DELETED, bindings.type(R + "xKS"), "N_", boolean.class);
        bind(CONTEXT, model, "fi2", context);
        readers.put(PROJECT, bindings.exactMethod(model, "k31", Object.class, false, Class.class));
        bind(PARAMETER_PROJECT, bindings.type("bKp"), "ce_", project);
        bind(ROOT, context, "kpO", bindings.type(R + "xKS"));
        bind(INITIALIZED, context, "cLM", boolean.class);
        bind(DISPOSED, context, "UlY", boolean.class);
        bind(CONTEXT_THREAD, context, "isEventDispatchThread", boolean.class);
        bind(CURRENT_DT, context, "Ynu", dt);
        bind(DEFAULT_DT, defaultContext, "IHn", dt);
        bind(DT_CONTEXT, dt, "kpO", context);
        bind(LICENSED, bindings.type("iTc"), "Sqv", boolean.class);
        bind(GUI_FLOAT, bindings.type("BFZ"), "JJF", bindings.type(R + "Fpk"));
        bind(GUI_INTEGER, bindings.type("bEX"), "THY", bindings.type(R + "NEs"));
        bind(GUI_ENUM, bindings.type("ndv"), "lUH", bindings.type(R + "NEs"));
        bind(GUI_BOOLEAN, bindings.type("EjC"), "Gtj", bindings.type(R + "sYG"));
        bind(SETTABLE, bindings.type(R + "QKj"), "isSettable", boolean.class);
        bind(CIU, bindings.type("bKp"), "CIu", boolean.class);
        bind(TOUCHED, bindings.type("q3j"), "t3H", boolean.class);
        readers.put(UNDO, bindings.exactMethod(undo, "vhT", undo, true, model));
        bind(UNDO_EXECUTING, undo, "Gtj", boolean.class);
        bind(UNDO_ACTIVE, undo, "HFp", boolean.class);
        bind(UNDO_ICL, undo, "iCL", boolean.class);
        bind(LOCK, bindings.type("com.bitwig.flt.document.core.master.device.PI"), "cm_", lock);
        bind(LOCK_OWNER, lock, "k31", dt);
    }

    private void bind(GridParameterContext.Field field, Class<?> owner, String name, Class<?> returns)
            throws ReflectiveOperationException {
        readers.put(field, bindings.exactMethod(owner, name, returns, false));
    }
    @Override public boolean defaultContext(Object context) { return defaultContext.isInstance(context); }
    @Override public Object read(GridParameterContext.Field field, Object object) {
        if (field == GUI_PANEL_BOOLEAN) {
            if (panels == null) throw failure("grid_unavailable", "Boolean panel property binding unavailable.");
            return panels.property(object);
        }
        Method reader = readers.get(field);
        if (reader == null) throw failure("grid_unavailable", "Unbound parameter context reader: " + field);
        if (field == PROJECT) return invoke(reader, object, project);
        if (field == UNDO) return invoke(reader, null, object);
        return invoke(reader, object);
    }
}
