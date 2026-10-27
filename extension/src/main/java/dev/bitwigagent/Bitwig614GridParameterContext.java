package dev.bitwigagent;

import java.lang.reflect.Method;
import java.util.EnumMap;
import static dev.bitwigagent.GridParameterContext.Field.*;

import static dev.bitwigagent.HostServices.failure;

/** Additional read-only 6.1.4 descriptors, gated separately from ordinary graph serialization. */
final class Bitwig614GridParameterContext implements GridParameterContext.Access {
    private static final String R = "com.bitwig.ramona.core.";
    private final EnumMap<GridParameterContext.Field, Method> readers = new EnumMap<>(GridParameterContext.Field.class);
    private final Bitwig614Runtime.Access bindings;
    private final Bitwig614ReadContextAccess readContext;
    private final Bitwig614GridBooleanPanelAccess panels;
    private final Bitwig614StringPanelAccess strings;

    Bitwig614GridParameterContext(Bitwig614Runtime.Access bindings, Bitwig614GridBooleanPanelAccess panels,
            Bitwig614StringPanelAccess strings)
            throws ReflectiveOperationException {
        this.bindings = bindings; this.panels = panels; this.strings = strings;
        readContext = new Bitwig614ReadContextAccess(bindings);
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
        readers.put(UNDO, bindings.exactMethod(undo, "cfM", undo, true, model));
        bind(UNDO_EXECUTING, undo, "iCS", boolean.class);
        bind(UNDO_ACTIVE, undo, "iHb", boolean.class);
        bind(UNDO_ICL, undo, "nVd", boolean.class);
        bind(LOCK, bindings.type("com.bitwig.flt.document.core.master.device.FDA"), "cm_", lock);
        bind(LOCK_OWNER, lock, "IwV", dt);
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
