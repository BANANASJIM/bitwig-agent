package dev.bitwigagent.nativehost;

import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.host.NativeReadContext;
import java.lang.reflect.Method;
import java.util.EnumMap;

/** Reflective context readers keyed by a context field enum, with the shared native read context. */
abstract class NativeContextReaders<F extends Enum<F>> implements NativeReadContext.Access {
    final NativeHost.Access bindings;
    private final EnumMap<F, Method> readers;
    private final NativeReadContextAccess readContext;

    NativeContextReaders(Class<F> fields, NativeHost.Access bindings) throws ReflectiveOperationException {
        this.bindings = bindings;
        readers = new EnumMap<>(fields);
        readContext = new NativeReadContextAccess(bindings);
    }

    final void bind(F field, String key, Class<?> returns) throws ReflectiveOperationException {
        readers.put(field, bindings.exactMethod(key, returns, false));
    }

    final void bindUndo(F field, String key, Class<?> undo, Class<?> model) throws ReflectiveOperationException {
        readers.put(field, bindings.exactMethod(key, undo, true, model));
    }

    public final boolean defaultContext(Object context) {
        return readContext.defaultContext(context);
    }

    @Override
    public final Object read(NativeReadContext.Field field, Object object) {
        return readContext.read(field, object);
    }

    final Object invoke(F field, F undo, Object object, String label) {
        Method reader = readers.get(field);
        if (reader == null)
            throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "Unbound " + label + " reader: " + field);
        if (field == undo) return bindings.invoke(reader, null, object);
        return bindings.invoke(reader, object);
    }
}
