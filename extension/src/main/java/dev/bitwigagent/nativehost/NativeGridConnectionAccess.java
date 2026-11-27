package dev.bitwigagent.nativehost;

import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.grid.GridConnectionWriter;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** The public connection-source overload retains the input's undo wrapper; the typed output overload must never be invoked. */
public final class NativeGridConnectionAccess implements GridConnectionWriter.Access {
    private final NativeHost.Access bindings;
    private final Class<?> destinationClass, undoClass;
    private final Method setter;

    NativeGridConnectionAccess(NativeHost.Access bindings) throws ReflectiveOperationException {
        this.bindings = bindings;
        destinationClass = bindings.type("grid.destinationInput");
        // Inspected project manager: it delegates to connection validation; close can reject or schedule history
        // replay.
        undoClass = bindings.type("project.undoManager");
        setter = bindings.exactMethod(
                "grid.inputBase.connect", void.class, false, bindings.type("grid.connectionSource"));
        if (!Modifier.isFinal(setter.getModifiers()))
            throw Errors.notApplied(
                    Reason.NATIVE_UNAVAILABLE, "Inspected public connection setter is no longer final.");
    }

    @Override
    public void requireSupported(Object input, Object undo) {
        requireExact(input, destinationClass, "destination input");
        requireExact(undo, undoClass, "ancestor undo manager");
    }

    public static void requireExact(Object model, Class<?> inspected, String label) {
        if (model == null || model.getClass() != inspected)
            throw Errors.notApplied(
                    Reason.NATIVE_UNAVAILABLE,
                    "Connection writing requires the inspected exact " + label + " class "
                            + inspected.getName() + "; observed "
                            + (model == null ? "null" : model.getClass().getName())
                            + ". Runtime subclasses require separate dispatch/validator research; read-only modelClass metadata is not permission.");
    }

    @Override
    public Runnable prepareSet(Object input, Object source) {
        return () -> bindings.invoke(setter, input, source);
    }

    @Override
    public void execute(Runnable observation) {
        bindings.execute(observation);
    }
}
