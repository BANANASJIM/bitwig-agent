package dev.bitwigagent;

import java.lang.reflect.Method;
import java.util.EnumMap;
import static dev.bitwigagent.GridTopologyContext.Field.*;

import static dev.bitwigagent.HostServices.failure;

/** Shared public context observations, independent of move geometry and connection setter bindings. */
final class Bitwig613GridTopologyContext implements GridTopologyContext.Access {
    private static final String R = "com.bitwig.ramona.core.", D = "com.bitwig.flt.document.core.master.device.";
    private final Bitwig613Runtime.Access bindings;
    private final EnumMap<GridTopologyContext.Field, Method> readers = new EnumMap<>(GridTopologyContext.Field.class);
    private final Bitwig613ReadContextAccess readContext;

    Bitwig613GridTopologyContext(Bitwig613Runtime.Access bindings) throws ReflectiveOperationException {
        this.bindings = bindings;
        readContext = new Bitwig613ReadContextAccess(bindings);
        Class<?> model = bindings.type(R + "fJC");
        Class<?> dt = bindings.type(R + "Dt"), undo = bindings.type("dfW"), lock = bindings.type(R + "IoW");
        bind(GRAPH_PROJECT, bindings.type("bKp"), "ce_", bindings.type("com.bitwig.flt.document.core.master.WN2"));
        bind(CIU, bindings.type("bKp"), "CIu", boolean.class);
        Class<?> projectFlags = bindings.type("com.bitwig.flt.document.core.master.UcW");
        bind(PROJECT_ADM, projectFlags, "adm", boolean.class); // is_being_closed (4701)
        bind(PROJECT_OOK, projectFlags, "OOK", boolean.class); // is_being_saved (4702)
        readers.put(UNDO, bindings.exactMethod(undo, "vhT", undo, true, model));
        bind(UNDO_EXECUTING, undo, "Gtj", boolean.class);
        bind(UNDO_ACTIVE, undo, "HFp", boolean.class);
        bind(UNDO_ICL, undo, "iCL", boolean.class);
        bind(LOCK, bindings.type(D + "PI"), "cm_", lock);
        bind(LOCK_OWNER, lock, "k31", dt);
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
