package dev.bitwigagent;

import java.lang.reflect.*;
import java.util.*;

import static dev.bitwigagent.HostServices.failure;

/** Public exact 6.1.3 descriptors only; no coordinate setters, clear, locks or outer transaction. */
final class Bitwig613GridModuleAccess implements GridModuleWriter.Access {
    private static final String D = "com.bitwig.flt.document.core.master.device.";
    private final Bitwig613Runtime.Access bindings;
    private Class<?> callbackType;
    private Method move, delete, success, exception, message;
    String moveError, deleteError;

    Bitwig613GridModuleAccess(Bitwig613Runtime.Access bindings) throws ReflectiveOperationException {
        this.bindings = bindings;
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
    @Override public Runnable prepareMove(Object graph, Object module, int x, int y) {
        if (moveError != null) throw failure("grid_unavailable", "Native module move binding is unavailable.");
        List<Object> selected = Collections.singletonList(module);
        MoveCallback capture = new MoveCallback(success, exception, message);
        Object callback = Proxy.newProxyInstance(callbackType.getClassLoader(), new Class<?>[]{callbackType}, capture);
        return () -> {
            bindings.invoke(move, graph, module, selected, x, y, false, callback);
            capture.requireSuccess(); // callback itself never throws into iqa or completes the RPC before close.
        };
    }
    @Override public Runnable prepareDelete(Object graph, Object module) {
        if (deleteError != null) throw failure("grid_unavailable", "Native module delete binding is unavailable.");
        List<Object> selected = Collections.singletonList(module);
        return () -> bindings.invoke(delete, graph, selected);
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
