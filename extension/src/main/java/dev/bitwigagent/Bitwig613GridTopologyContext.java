package dev.bitwigagent;

import java.lang.reflect.Method;
import java.util.EnumMap;
import static dev.bitwigagent.GridTopologyContext.Field.*;
import static dev.bitwigagent.Bitwig613GridAdapter.invoke;
import static dev.bitwigagent.HostServices.failure;

/** Shared public context observations, independent of move geometry and connection setter bindings. */
final class Bitwig613GridTopologyContext implements GridTopologyContext.Access {
    private static final String R = "com.bitwig.ramona.core.", D = "com.bitwig.flt.document.core.master.device.";
    private final Bitwig613GridAdapter bindings;
    private final EnumMap<GridTopologyContext.Field, Method> readers = new EnumMap<>(GridTopologyContext.Field.class);
    private final Class<?> project, defaultContext;

    Bitwig613GridTopologyContext(Bitwig613GridAdapter bindings) throws ReflectiveOperationException {
        this.bindings = bindings;
        Class<?> model = bindings.type(R + "fJC"), context = bindings.type(R + "HLm");
        Class<?> dt = bindings.type(R + "Dt"), undo = bindings.type("dfW"), lock = bindings.type(R + "IoW");
        project = bindings.type("com.bitwig.flt.document.core.master.WN2");
        defaultContext = bindings.type("dqB");
        bind(DELETED, bindings.type(R + "xKS"), "N_", boolean.class);
        bind(CONTEXT, model, "fi2", context);
        readers.put(PROJECT, bindings.exactMethod(model, "k31", Object.class, false, Class.class));
        bind(GRAPH_PROJECT, bindings.type("bKp"), "ce_", project);
        bind(ROOT, context, "kpO", bindings.type(R + "xKS"));
        bind(INITIALIZED, context, "cLM", boolean.class);
        bind(DISPOSED, context, "UlY", boolean.class);
        bind(CONTEXT_THREAD, context, "isEventDispatchThread", boolean.class);
        bind(CURRENT_DT, context, "Ynu", dt);
        bind(DEFAULT_DT, defaultContext, "IHn", dt);
        bind(DT_CONTEXT, dt, "kpO", context);
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
    @Override public boolean defaultContext(Object context) { return defaultContext.isInstance(context); }
    @Override public Object read(GridTopologyContext.Field field, Object object) {
        Method reader = readers.get(field);
        if (reader == null) throw failure("grid_unavailable", "Unbound topology context reader: " + field);
        if (field == PROJECT) return invoke(reader, object, project);
        if (field == UNDO) return invoke(reader, null, object);
        return invoke(reader, object);
    }
}
