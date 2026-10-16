package dev.bitwigagent;

import java.lang.reflect.Method;
import java.util.EnumMap;
import static dev.bitwigagent.NativeReadContext.Field.*;

/** Pure lifecycle/context descriptors; no scalar, wrapper, license, undo or lock bindings. */
final class Bitwig613ReadContextAccess implements NativeReadContext.Access {
    private final Bitwig613Runtime.Access bindings;
    private final EnumMap<NativeReadContext.Field, Method> readers = new EnumMap<>(NativeReadContext.Field.class);
    private final Class<?> project, defaultContext;
    Bitwig613ReadContextAccess(Bitwig613Runtime.Access bindings) throws ReflectiveOperationException {
        this.bindings = bindings;
        String r = "com.bitwig.ramona.core.";
        Class<?> model = bindings.type(r + "fJC"), context = bindings.type(r + "HLm"), dt = bindings.type(r + "Dt");
        project = bindings.type("com.bitwig.flt.document.core.master.WN2");
        defaultContext = bindings.type("dqB");
        bind(DELETED, bindings.type(r + "xKS"), "N_", boolean.class);
        bind(CONTEXT, model, "fi2", context);
        readers.put(PROJECT, bindings.exactMethod(model, "k31", Object.class, false, Class.class));
        bind(ROOT, context, "kpO", bindings.type(r + "xKS"));
        bind(INITIALIZED, context, "cLM", boolean.class);
        bind(DISPOSED, context, "UlY", boolean.class);
        bind(CONTEXT_THREAD, context, "isEventDispatchThread", boolean.class);
        bind(CURRENT_DT, context, "Ynu", dt);
        bind(DEFAULT_DT, defaultContext, "IHn", dt);
        bind(DT_CONTEXT, dt, "kpO", context);
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
