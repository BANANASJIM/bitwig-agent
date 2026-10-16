package dev.bitwigagent;

import java.lang.reflect.Method;
import java.util.EnumMap;
import static dev.bitwigagent.GridParameterContext.Field.*;

import static dev.bitwigagent.HostServices.failure;

/** Additional read-only 6.1.3 descriptors, gated separately from ordinary graph serialization. */
final class Bitwig613GridParameterContext implements GridParameterContext.Access {
    private static final String R = "com.bitwig.ramona.core.";
    private final EnumMap<GridParameterContext.Field, Method> readers = new EnumMap<>(GridParameterContext.Field.class);
    private final Bitwig613Runtime.Access bindings;
    private final Bitwig613ReadContextAccess readContext;
    private final Bitwig613GridBooleanPanelAccess panels;
    private final Bitwig613StringPanelAccess strings;

    Bitwig613GridParameterContext(Bitwig613Runtime.Access bindings, Bitwig613GridBooleanPanelAccess panels,
            Bitwig613StringPanelAccess strings)
            throws ReflectiveOperationException {
        this.bindings = bindings; this.panels = panels; this.strings = strings;
        readContext = new Bitwig613ReadContextAccess(bindings);
        Class<?> model = bindings.type(R + "fJC");
        Class<?> dt = bindings.type(R + "Dt"), undo = bindings.type("dfW"), lock = bindings.type(R + "IoW");
        bind(PARAMETER_PROJECT, bindings.type("bKp"), "ce_", bindings.type("com.bitwig.flt.document.core.master.WN2"));
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
    @Override public boolean defaultContext(Object context) { return readContext.defaultContext(context); }
    @Override public String unavailableCode() { return bindings.unavailableCode(); }
    @Override public Object read(NativeReadContext.Field field, Object object) { return readContext.read(field, object); }
    @Override public Object read(GridParameterContext.Field field, Object object) {
        if (field == GUI_PANEL_BOOLEAN) {
            if (panels == null) throw failure("grid_unavailable", "Boolean panel property binding unavailable.");
            return panels.property(object);
        }
        if (field == GUI_PANEL_STRING) {
            if (strings == null) throw failure("grid_unavailable", "String panel property binding unavailable.");
            return strings.property(object);
        }
        Method reader = readers.get(field);
        if (reader == null) throw failure("grid_unavailable", "Unbound parameter context reader: " + field);
        if (field == UNDO) return bindings.invoke(reader, null, object);
        return bindings.invoke(reader, object);
    }
}
