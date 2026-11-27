package dev.bitwigagent.nativehost;

import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.grid.GridStringPanelWriter;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Exact string-panel bindings: reads, plus the final public panel.stringBase set(String) change operation when it binds. */
final class NativeStringPanelAccess implements GridStringPanelWriter.Access {
    private final Class<?> panel;
    private final Method identifier, value, property;
    private Method setter;
    private String setterError;
    private final NativeHost.Access bindings;

    NativeStringPanelAccess(NativeHost.Access bindings) throws ReflectiveOperationException {
        this.bindings = bindings;
        panel = bindings.type("panel.string");
        String[] ancestry = {
            "panel.stringBase",
            "panel.base",
            "panel.identified",
            "panel.component",
            "panel.node",
            "model.element",
            "model.elementBase",
            "model.observableBase",
            "model.nodeBase",
            "model.node",
            "model.object"
        };
        Class<?> current = panel;
        for (String key : ancestry) {
            Class<?> parent = bindings.type(key);
            if (current.getSuperclass() != parent)
                throw new NoSuchMethodException("Inspected panel.string ancestry differs.");
            current = parent;
        }
        Class<?> identified = bindings.type("panel.identified"), valued = bindings.type("panel.stringBase");
        Class<?> wrapper = bindings.type("model.objectProperty");
        if (wrapper.getSuperclass() != bindings.type("model.property"))
            throw new NoSuchMethodException("Inspected string property ancestry differs.");
        identifier = bindings.exactMethod("panel.string.identifier", String.class, false);
        value = bindings.exactMethod("panel.string.value", String.class, false);
        property = bindings.exactMethod("panel.string.property", wrapper, false);
        if (identifier.getDeclaringClass() != identified
                || value.getDeclaringClass() != valued
                || property.getDeclaringClass() != valued)
            throw new NoSuchMethodException("Expected the inspected string identifier/value/property getters.");
        // A write-binding failure must not take the read bindings down with it.
        try {
            Method candidate = bindings.exactMethod("panel.string.set", void.class, false, String.class);
            if (candidate.getDeclaringClass() != valued || !Modifier.isFinal(candidate.getModifiers()))
                throw new NoSuchMethodException(
                        "Expected the inspected final panel.stringBase set(String) string operation.");
            setter = candidate;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            setterError = NativeHost.diagnostic(error);
        }
    }

    String setterError() {
        return setterError;
    }

    boolean isPanel(Object object) {
        return object != null && object.getClass() == panel;
    }

    Object identifier(Object object) {
        requireSupported(object);
        return bindings.invoke(identifier, object);
    }

    Object value(Object object) {
        requireSupported(object);
        return bindings.invoke(value, object);
    }

    Object property(Object object) {
        requireSupported(object);
        return bindings.invoke(property, object);
    }

    @Override
    public void set(Object object, String text) {
        requireSupported(object);
        if (setter == null)
            throw Errors.notApplied(
                    Reason.NATIVE_UNAVAILABLE, "String panel write operation is not bound: " + setterError);
        if (text == null) throw Errors.notApplied(Reason.INVALID_PARAMS, "String panel text must not be null.");
        bindings.invoke(setter, object, text);
    }

    @Override
    public void requireSupported(Object object) {
        if (!isPanel(object))
            throw Errors.notApplied(
                    Reason.NATIVE_UNAVAILABLE, "String panel requires the exact inspected panel.string model.");
    }
}
