package dev.bitwigagent.nativehost;

import static dev.bitwigagent.host.NativeReadContext.Field.*;

import dev.bitwigagent.host.NativeReadContext;
import java.lang.reflect.Method;
import java.util.EnumMap;

/** Pure lifecycle/context descriptors; no scalar, wrapper, license, undo or lock bindings. */
final class NativeReadContextAccess implements NativeReadContext.Access {
    private final NativeHost.Access bindings;
    private final EnumMap<NativeReadContext.Field, Method> readers = new EnumMap<>(NativeReadContext.Field.class);
    private final Class<?> project, defaultContext;

    NativeReadContextAccess(NativeHost.Access bindings) throws ReflectiveOperationException {
        this.bindings = bindings;
        Class<?> context = bindings.type("model.context"), dt = bindings.type("model.thread");
        project = bindings.type("project");
        defaultContext = bindings.type("model.defaultContext");
        bind(DELETED, "model.node.deleted", boolean.class);
        bind(CONTEXT, "model.object.context", context);
        readers.put(PROJECT, bindings.exactMethod("model.object.ancestor", Object.class, false, Class.class));
        bind(ROOT, "model.context.root", bindings.type("model.node"));
        bind(INITIALIZED, "model.context.initialized", boolean.class);
        bind(DISPOSED, "model.context.disposed", boolean.class);
        bind(CONTEXT_THREAD, "model.context.isContextThread", boolean.class);
        bind(CURRENT_DT, "model.context.currentThread", dt);
        bind(DEFAULT_DT, "model.defaultContext.thread", dt);
        bind(DT_CONTEXT, "model.thread.context", context);
    }

    private void bind(NativeReadContext.Field field, String key, Class<?> returns) throws ReflectiveOperationException {
        readers.put(field, bindings.exactMethod(key, returns, false));
    }

    @Override
    public boolean defaultContext(Object context) {
        return defaultContext.isInstance(context);
    }

    @Override
    public Object read(NativeReadContext.Field field, Object object) {
        Method reader = readers.get(field);
        return field == PROJECT ? bindings.invoke(reader, object, project) : bindings.invoke(reader, object);
    }
}
