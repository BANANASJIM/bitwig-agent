package dev.bitwigagent;

import java.lang.reflect.Method;
import java.util.EnumMap;
import static dev.bitwigagent.GridTopologyContext.Field.*;

import static dev.bitwigagent.HostServices.failure;

/** Shared public context observations, independent of move geometry and connection setter bindings. */
final class Bitwig614GridTopologyContext implements GridTopologyContext.Access {
    private static final String R = "com.bitwig.ramona.core.", D = "com.bitwig.flt.document.core.master.device.";
    private final Bitwig614Runtime.Access bindings;
    private final EnumMap<GridTopologyContext.Field, Method> readers = new EnumMap<>(GridTopologyContext.Field.class);
    private final Bitwig614ReadContextAccess readContext;

    Bitwig614GridTopologyContext(Bitwig614Runtime.Access bindings) throws ReflectiveOperationException {
        this.bindings = bindings;
        readContext = new Bitwig614ReadContextAccess(bindings);
        Class<?> model = bindings.type(R + "AVL");
        Class<?> dt = bindings.type(R + "XKG"), undo = bindings.type("dfI"), lock = bindings.type(R + "IOJ");
        bind(GRAPH_PROJECT, bindings.type("K2G"), "ce_", bindings.type("com.bitwig.flt.document.core.master.g2L"));
        bind(CIU, bindings.type("K2G"), "qZj", boolean.class);
        Class<?> projectFlags = bindings.type("com.bitwig.flt.document.core.master.AJH");
        bind(PROJECT_ADM, projectFlags, "jG", boolean.class); // is_being_closed (4701)
        bind(PROJECT_OOK, projectFlags, "zpW", boolean.class); // is_being_saved (4702)
        readers.put(UNDO, bindings.exactMethod(undo, "cfM", undo, true, model));
        bind(UNDO_EXECUTING, undo, "iCS", boolean.class);
        bind(UNDO_ACTIVE, undo, "iHb", boolean.class);
        bind(UNDO_ICL, undo, "nVd", boolean.class);
        bind(LOCK, bindings.type(D + "FDA"), "cm_", lock);
        bind(LOCK_OWNER, lock, "IwV", dt);
    }
    private void bind(GridTopologyContext.Field field, Class<?> owner, String name, Class<?> returns)
            throws ReflectiveOperationException {
        readers.put(field, bindings.exactMethod(owner, name, returns, false));
    }
    @Override public boolean defaultContext(Object context) { return readContext.defaultContext(context); }
    @Override public String unavailableCode() { return bindings.unavailableCode(); }
    @Override public Object read(NativeReadContext.Field field, Object object) { return readContext.read(field, object); }
    @Override public Object read(GridTopologyContext.Field field, Object object) {
        Method reader = readers.get(field);
        if (reader == null) throw failure("grid_unavailable", "Unbound topology context reader: " + field);
        if (field == UNDO) return bindings.invoke(reader, null, object);
        return bindings.invoke(reader, object);
    }
}
