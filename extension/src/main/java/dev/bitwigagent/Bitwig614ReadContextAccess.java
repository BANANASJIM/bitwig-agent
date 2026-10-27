package dev.bitwigagent;

import java.lang.reflect.Method;
import java.util.EnumMap;
import static dev.bitwigagent.NativeReadContext.Field.*;

/** Pure lifecycle/context descriptors; no scalar, wrapper, license, undo or lock bindings. */
final class Bitwig614ReadContextAccess implements NativeReadContext.Access {
    private final Bitwig614Runtime.Access bindings;
    private final EnumMap<NativeReadContext.Field, Method> readers = new EnumMap<>(NativeReadContext.Field.class);
    private final Class<?> project, defaultContext;
    Bitwig614ReadContextAccess(Bitwig614Runtime.Access bindings) throws ReflectiveOperationException {
        this.bindings = bindings;
        String r = "com.bitwig.ramona.core.";
        Class<?> model = bindings.type(r + "AVL"), context = bindings.type(r + "Rzi"), dt = bindings.type(r + "XKG");
        project = bindings.type("com.bitwig.flt.document.core.master.g2L");
        defaultContext = bindings.type("dqr");
        bind(DELETED, bindings.type(r + "oDc"), "N_", boolean.class);
        bind(CONTEXT, model, "ULc", context);
        readers.put(PROJECT, bindings.exactMethod(model, "IwV", Object.class, false, Class.class));
        bind(ROOT, context, "FzB", bindings.type(r + "oDc"));
        bind(INITIALIZED, context, "eLY", boolean.class);
        bind(DISPOSED, context, "ucQ", boolean.class);
        bind(CONTEXT_THREAD, context, "isEventDispatchThread", boolean.class);
        bind(CURRENT_DT, context, "vpw", dt);
        bind(DEFAULT_DT, defaultContext, "rGR", dt);
        bind(DT_CONTEXT, dt, "FzB", context);
    }
    private void bind(NativeReadContext.Field field, Class<?> owner, String name, Class<?> returns)
            throws ReflectiveOperationException {
        readers.put(field, bindings.exactMethod(owner, name, returns, false));
    }
    @Override public boolean defaultContext(Object context) { return defaultContext.isInstance(context); }
    @Override public String unavailableCode() { return bindings.unavailableCode(); }
    @Override public Object read(NativeReadContext.Field field, Object object) {
        Method reader = readers.get(field);
        return field == PROJECT ? bindings.invoke(reader, object, project) : bindings.invoke(reader, object);
    }
}
