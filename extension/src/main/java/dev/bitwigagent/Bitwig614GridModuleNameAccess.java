package dev.bitwigagent;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import static dev.bitwigagent.HostServices.failure;

/** Pinned public-final native custom-name property operation, not the UI's outer transaction. */
final class Bitwig614GridModuleNameAccess implements GridModuleNameWriter.Access {
    private static final String D = "com.bitwig.flt.document.core.master.device.";
    private final Bitwig614Runtime.Access bindings;
    private final Class<?> module;
    private final Method setter;

    Bitwig614GridModuleNameAccess(Bitwig614Runtime.Access bindings) throws ReflectiveOperationException {
        this.bindings = bindings;
        Class<?> owner = bindings.type(D + "Dgc");
        module = bindings.type(D + "rSf");
        setter = bindings.exactMethod(owner, "iHb", void.class, false, String.class);
        Method declared = owner.getDeclaredMethod("iHb", String.class);
        int modifiers = setter.getModifiers();
        if (!setter.equals(declared) || setter.getDeclaringClass() != owner
                || !Modifier.isPublic(modifiers) || !Modifier.isFinal(modifiers)
                || Modifier.isStatic(modifiers) || setter.isBridge() || setter.isSynthetic()
                || !owner.isAssignableFrom(module) || !module.getMethod("iHb", String.class).equals(setter))
            throw new NoSuchMethodException("Exact declared public-final Dgc.iHb(String):void inherited by rSf is required.");
    }
    @Override public void requireSupported(Object target) {
        if (!module.isInstance(target))
            throw failure("grid_unavailable", "Module naming requires a real native rSf Grid module.");
    }
    @Override public Runnable prepareSet(Object target, String customName) {
        requireSupported(target);
        return () -> bindings.invoke(setter, target, customName);
    }
    @Override public void execute(Runnable observation) { bindings.execute(observation); }
}
