package dev.bitwigagent;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import static dev.bitwigagent.HostServices.failure;

/** Pinned public-final native custom-name property operation, not the UI's outer transaction. */
final class Bitwig613GridModuleNameAccess implements GridModuleNameWriter.Access {
    private static final String D = "com.bitwig.flt.document.core.master.device.";
    private final Bitwig613Runtime.Access bindings;
    private final Class<?> module;
    private final Method setter;

    Bitwig613GridModuleNameAccess(Bitwig613Runtime.Access bindings) throws ReflectiveOperationException {
        this.bindings = bindings;
        Class<?> owner = bindings.type(D + "QKj");
        module = bindings.type(D + "gxR");
        setter = bindings.exactMethod(owner, "HFp", void.class, false, String.class);
        Method declared = owner.getDeclaredMethod("HFp", String.class);
        int modifiers = setter.getModifiers();
        if (!setter.equals(declared) || setter.getDeclaringClass() != owner
                || !Modifier.isPublic(modifiers) || !Modifier.isFinal(modifiers)
                || Modifier.isStatic(modifiers) || setter.isBridge() || setter.isSynthetic()
                || !owner.isAssignableFrom(module) || !module.getMethod("HFp", String.class).equals(setter))
            throw new NoSuchMethodException("Exact declared public-final QKj.HFp(String):void inherited by gxR is required.");
    }
    @Override public void requireSupported(Object target) {
        if (!module.isInstance(target))
            throw failure("grid_unavailable", "Module naming requires a real native gxR Grid module.");
    }
    @Override public Runnable prepareSet(Object target, String customName) {
        requireSupported(target);
        return () -> bindings.invoke(setter, target, customName);
    }
    @Override public void execute(Runnable observation) { bindings.execute(observation); }
}
