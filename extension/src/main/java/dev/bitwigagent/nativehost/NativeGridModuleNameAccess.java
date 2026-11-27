package dev.bitwigagent.nativehost;

import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.grid.GridModuleNameWriter;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Pinned public-final native custom-name property operation, not the UI's outer transaction. */
final class NativeGridModuleNameAccess implements GridModuleNameWriter.Access {
    private final NativeHost.Access bindings;
    private final Class<?> module;
    private final Method setter;

    NativeGridModuleNameAccess(NativeHost.Access bindings) throws ReflectiveOperationException {
        this.bindings = bindings;
        Class<?> owner = bindings.type("device.named");
        module = bindings.type("grid.module");
        setter = bindings.exactMethod("device.named.setCustomName", void.class, false, String.class);
        String name = bindings.name("device.named.setCustomName");
        Method declared = owner.getDeclaredMethod(name, String.class);
        int modifiers = setter.getModifiers();
        if (!setter.equals(declared)
                || setter.getDeclaringClass() != owner
                || !Modifier.isPublic(modifiers)
                || !Modifier.isFinal(modifiers)
                || Modifier.isStatic(modifiers)
                || setter.isBridge()
                || setter.isSynthetic()
                || !owner.isAssignableFrom(module)
                || !module.getMethod(name, String.class).equals(setter))
            throw new NoSuchMethodException(
                    "Exact declared public-final device.named.setCustomName(String):void inherited by grid.module is required.");
    }

    @Override
    public void requireSupported(Object target) {
        if (!module.isInstance(target))
            throw Errors.notApplied(
                    Reason.NATIVE_UNAVAILABLE, "Module naming requires a real native grid.module Grid module.");
    }

    @Override
    public Runnable prepareSet(Object target, String customName) {
        requireSupported(target);
        return () -> bindings.invoke(setter, target, customName);
    }

    @Override
    public void execute(Runnable observation) {
        bindings.execute(observation);
    }
}
