package dev.bitwigagent;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import static dev.bitwigagent.HostServices.failure;

/** The public led overload retains PSD's undo wrapper; the typed xOa overload must never be invoked. */
final class Bitwig614GridConnectionAccess implements GridConnectionWriter.Access {
    private static final String D = "com.bitwig.flt.document.core.master.device.";
    private final Bitwig614Runtime.Access bindings;
    private final Class<?> destinationClass, undoClass;
    private final Method setter;

    Bitwig614GridConnectionAccess(Bitwig614Runtime.Access bindings) throws ReflectiveOperationException {
        this.bindings = bindings;
        destinationClass = bindings.type(D + "Wkj");
        // Inspected project manager: Hnt delegates to Kiy validation; close can reject or schedule history replay.
        undoClass = bindings.type("com.bitwig.flt.document.core.master.tj2");
        setter = bindings.exactMethod(bindings.type(D + "cXR"), "cfM", void.class, false, bindings.type("led"));
        if (!Modifier.isFinal(setter.getModifiers()))
            throw failure("grid_unavailable", "Inspected public connection setter is no longer final.");
    }
    @Override public void requireSupported(Object input, Object undo) {
        requireExact(input, destinationClass, "destination input");
        requireExact(undo, undoClass, "ancestor undo manager");
    }
    static void requireExact(Object model, Class<?> inspected, String label) {
        if (model == null || model.getClass() != inspected)
            throw failure("grid_unavailable", "Connection writing requires the inspected exact " + label + " class "
                    + inspected.getName() + "; observed " + (model == null ? "null" : model.getClass().getName())
                    + ". Runtime subclasses require separate dispatch/validator research; read-only modelClass metadata is not permission.");
    }
    @Override public Runnable prepareSet(Object input, Object source) {
        return () -> bindings.invoke(setter, input, source);
    }
    @Override public void execute(Runnable observation) { bindings.execute(observation); }
}
