package dev.bitwigagent;

import java.lang.reflect.Method;
import java.util.EnumMap;

/** Reflective 6.1.4 context readers keyed by a context field enum, with the shared native read context. */
abstract class Bitwig614ContextReaders<F extends Enum<F>> implements NativeReadContext.Access {
    final Bitwig614Runtime.Access bindings;
    private final EnumMap<F, Method> readers;
    private final Bitwig614ReadContextAccess readContext;

    Bitwig614ContextReaders(Class<F> fields, Bitwig614Runtime.Access bindings) throws ReflectiveOperationException {
        this.bindings = bindings;
        readers = new EnumMap<>(fields);
        readContext = new Bitwig614ReadContextAccess(bindings);
    }

    final void bind(F field, Class<?> owner, String name, Class<?> returns) throws ReflectiveOperationException {
        readers.put(field, bindings.exactMethod(owner, name, returns, false));
    }

    final void bindUndo(F field, Class<?> undo, Class<?> model) throws ReflectiveOperationException {
        readers.put(field, bindings.exactMethod(undo, "cfM", undo, true, model));
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
