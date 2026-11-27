package dev.bitwigagent.nativehost;

import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.grid.GridBooleanPanelWriter;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Exact boolean-panel model operations; never a parameter.controller scalar or a GUI widget binding. */
final class NativeGridBooleanPanelAccess implements GridBooleanPanelWriter.Access {
    private final Class<?> panel, bipolar;
    private final Method identifier, value, property, linkedPanel, setter;
    private final NativeHost.Access bindings;

    NativeGridBooleanPanelAccess(NativeHost.Access bindings) throws ReflectiveOperationException {
        this.bindings = bindings;
        panel = bindings.type("panel.boolean");
        Class<?> parent = bindings.type("panel.booleanBase"), intermediate = bindings.type("panel.base");
        Class<?> identified = bindings.type("panel.identified"),
                component = bindings.type("panel.component"),
                base = bindings.type("panel.node");
        if (panel.getSuperclass() != parent
                || parent.getSuperclass() != intermediate
                || intermediate.getSuperclass() != identified
                || identified.getSuperclass() != component
                || component.getSuperclass() != base
                || base.getSuperclass() != bindings.type("model.element"))
            throw new NoSuchMethodException("Inspected panel.boolean ancestry differs.");
        identifier = bindings.exactMethod("panel.boolean.identifier", String.class, false);
        value = bindings.exactMethod("panel.boolean.value", boolean.class, false);
        property = bindings.exactMethod("panel.boolean.property", bindings.type("model.booleanProperty"), false);
        setter = bindings.exactMethod("panel.boolean.set", void.class, false, boolean.class);
        if (identifier.getDeclaringClass() != identified
                || value.getDeclaringClass() != parent
                || property.getDeclaringClass() != parent
                || setter.getDeclaringClass() != parent
                || !Modifier.isFinal(setter.getModifiers()))
            throw new NoSuchMethodException("Expected the inspected identifier/property/final boolean operation.");
        bipolar = bindings.type("parameter.bipolar");
        Class<?> bipolarParent = bindings.type("parameter.bipolarBase");
        if (bipolar.getSuperclass() != bipolarParent
                || bipolarParent.getSuperclass() != bindings.type("parameter.floatController"))
            throw new NoSuchMethodException("Inspected parameter.bipolar ancestry differs.");
        linkedPanel = bindings.exactMethod("parameter.bipolar.linkedPanel", panel, false);
        if (linkedPanel.getDeclaringClass() != bipolarParent)
            throw new NoSuchMethodException(
                    "Expected the inspected parameter.bipolarBase boolean-panel reference getter.");
    }

    boolean isPanel(Object object) {
        return object != null && object.getClass() == panel;
    }

    boolean isBipolar(Object object) {
        return object != null && object.getClass() == bipolar;
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

    Object linkedPanel(Object object) {
        if (!isBipolar(object))
            throw Errors.notApplied(
                    Reason.NATIVE_UNAVAILABLE,
                    "Bipolar reference requires the exact inspected parameter.bipolar model.");
        return bindings.invoke(linkedPanel, object);
    }

    @Override
    public void requireSupported(Object object) {
        if (!isPanel(object))
            throw Errors.notApplied(
                    Reason.NATIVE_UNAVAILABLE, "Boolean panel requires the exact inspected panel.boolean model.");
    }

    @Override
    public void set(Object object, boolean value) {
        bindings.invoke(setter, object, value);
    }
}
