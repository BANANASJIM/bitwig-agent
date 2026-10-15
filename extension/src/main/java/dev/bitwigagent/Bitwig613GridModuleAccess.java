package dev.bitwigagent;

import java.lang.reflect.*;
import java.util.*;
import static dev.bitwigagent.GridModuleContext.Field.*;
import static dev.bitwigagent.Bitwig613GridAdapter.invoke;
import static dev.bitwigagent.HostServices.failure;

/** Public exact 6.1.3 descriptors only; no coordinate setters, clear, locks or outer transaction. */
final class Bitwig613GridModuleAccess implements GridModuleContext.Access, GridModuleWriter.Access {
    private static final String R = "com.bitwig.ramona.core.", D = "com.bitwig.flt.document.core.master.device.";
    private final Bitwig613GridAdapter bindings;
    private final EnumMap<GridModuleContext.Field, Method> readers = new EnumMap<>(GridModuleContext.Field.class);
    private final Class<?> project, defaultContext;
    private Class<?> callbackType;
    private Method move, delete, success, exception, message;
    String moveError, deleteError;

    Bitwig613GridModuleAccess(Bitwig613GridAdapter bindings) throws ReflectiveOperationException {
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
        try {
            callbackType = bindings.type("com.bitwig.base.async.jJa");
            if (!callbackType.isInterface()) throw failure("grid_unavailable", "Native move callback is not an interface.");
            success = bindings.exactMethod(callbackType, "vhT", void.class, false, Object.class);
            exception = bindings.exactMethod(callbackType, "vhT", void.class, false, Exception.class);
            message = bindings.exactMethod(callbackType, "vhT", void.class, false, String.class);
            move = bindings.exactMethod(bindings.type(D + "zXc"), "vhT", void.class, false,
                    bindings.type(D + "gxR"), Collection.class, int.class, int.class, boolean.class, callbackType);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) { moveError = error.getClass().getSimpleName(); }
        try {
            delete = bindings.exactMethod(bindings.type(D + "sF"), "vhT", void.class, false, List.class);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) { deleteError = error.getClass().getSimpleName(); }
    }
    private void bind(GridModuleContext.Field field, Class<?> owner, String name, Class<?> returns)
            throws ReflectiveOperationException {
        readers.put(field, bindings.exactMethod(owner, name, returns, false));
    }
    @Override public boolean defaultContext(Object context) { return defaultContext.isInstance(context); }
    @Override public Object read(GridModuleContext.Field field, Object object) {
        Method reader = readers.get(field);
        if (reader == null) throw failure("grid_unavailable", "Unbound topology context reader: " + field);
        if (field == PROJECT) return invoke(reader, object, project);
        if (field == UNDO) return invoke(reader, null, object);
        return invoke(reader, object);
    }
    @Override public Runnable prepareMove(Object graph, Object module, int x, int y) {
        if (moveError != null) throw failure("grid_unavailable", "Native module move binding is unavailable.");
        List<Object> selected = Collections.singletonList(module);
        MoveCallback capture = new MoveCallback(success, exception, message);
        Object callback = Proxy.newProxyInstance(callbackType.getClassLoader(), new Class<?>[]{callbackType}, capture);
        return () -> {
            invoke(move, graph, module, selected, x, y, false, callback);
            capture.requireSuccess(); // callback itself never throws into iqa or completes the RPC before close.
        };
    }
    @Override public Runnable prepareDelete(Object graph, Object module) {
        if (deleteError != null) throw failure("grid_unavailable", "Native module delete binding is unavailable.");
        List<Object> selected = Collections.singletonList(module);
        return () -> invoke(delete, graph, selected);
    }
    @Override public void execute(Runnable observation) { bindings.execute(observation); }

    /** Only primitive capture inside native callback; no native payload is inspected or exported. */
    static final class MoveCallback implements InvocationHandler {
        private final Method success, exception, message;
        private int successes;
        private boolean failed;
        MoveCallback(Method success, Method exception, Method message) {
            this.success = success; this.exception = exception; this.message = message;
        }
        @Override public Object invoke(Object proxy, Method method, Object[] args) {
            try {
                if (method.equals(success)) {
                    if (successes != 0 || args == null || args.length != 1 || args[0] == null) failed = true;
                    successes = 1;
                } else if (method.equals(exception) || method.equals(message)) failed = true;
                else if (method.getDeclaringClass() == Object.class) {
                    if (method.getName().equals("equals")) return args != null && args.length == 1 && proxy == args[0];
                    if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                    if (method.getName().equals("toString")) return "GridMoveCallback";
                    failed = true;
                } else failed = true;
            } catch (Throwable error) { failed = true; }
            return null;
        }
        void requireSuccess() {
            if (failed || successes != 1)
                throw failure("grid_unavailable", "Native move callback failed, was missing or had an unexpected shape; native return is not commit proof.");
        }
    }
}
