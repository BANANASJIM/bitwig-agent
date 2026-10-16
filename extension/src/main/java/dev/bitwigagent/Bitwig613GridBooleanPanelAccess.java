package dev.bitwigagent;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import static dev.bitwigagent.HostServices.failure;

/** Exact boolean-panel model operations; never an iTc scalar or a GUI widget binding. */
final class Bitwig613GridBooleanPanelAccess implements GridBooleanPanelWriter.Access {
    private final Class<?> panel, bipolar;
    private final Method identifier, value, property, linkedPanel, setter;
    private final Bitwig613Runtime.Access bindings;

    Bitwig613GridBooleanPanelAccess(Bitwig613Runtime.Access bindings) throws ReflectiveOperationException {
        this.bindings = bindings;
        panel = bindings.type("Fln");
        Class<?> parent = bindings.type("ihn"), intermediate = bindings.type("Kx3");
        Class<?> identified = bindings.type("VOa"), component = bindings.type("owt"), base = bindings.type("tD");
        if (panel.getSuperclass() != parent || parent.getSuperclass() != intermediate
                || intermediate.getSuperclass() != identified || identified.getSuperclass() != component
                || component.getSuperclass() != base || base.getSuperclass() != bindings.type("bKp"))
            throw new NoSuchMethodException("Inspected Fln boolean-panel ancestry differs.");
        identifier = bindings.exactMethod(panel, "aA_", String.class, false);
        value = bindings.exactMethod(panel, "Ynu", boolean.class, false);
        property = bindings.exactMethod(panel, "vhT", bindings.type("com.bitwig.ramona.core.sYG"), false);
        setter = bindings.exactMethod(panel, "E3I", void.class, false, boolean.class);
        if (identifier.getDeclaringClass() != identified || value.getDeclaringClass() != parent
                || property.getDeclaringClass() != parent || setter.getDeclaringClass() != parent
                || !Modifier.isFinal(setter.getModifiers()))
            throw new NoSuchMethodException("Expected the inspected identifier/property/final boolean operation.");
        bipolar = bindings.type("TZj");
        Class<?> bipolarParent = bindings.type("Jcb");
        if (bipolar.getSuperclass() != bipolarParent || bipolarParent.getSuperclass() != bindings.type("APs"))
            throw new NoSuchMethodException("Inspected TZj/Jcb/APs ancestry differs.");
        linkedPanel = bindings.exactMethod(bipolar, "XTm", panel, false);
        if (linkedPanel.getDeclaringClass() != bipolarParent)
            throw new NoSuchMethodException("Expected the inspected Jcb boolean-panel reference getter.");
    }

    boolean isPanel(Object object) { return object != null && object.getClass() == panel; }
    boolean isBipolar(Object object) { return object != null && object.getClass() == bipolar; }
    Object identifier(Object object) { requireSupported(object); return bindings.invoke(identifier, object); }
    Object value(Object object) { requireSupported(object); return bindings.invoke(value, object); }
    Object property(Object object) { requireSupported(object); return bindings.invoke(property, object); }
    Object linkedPanel(Object object) {
        if (!isBipolar(object)) throw failure("grid_unavailable", "Bipolar reference requires the exact inspected TZj model.");
        return bindings.invoke(linkedPanel, object);
    }
    @Override public void requireSupported(Object object) {
        if (!isPanel(object)) throw failure("grid_unavailable", "Boolean panel requires the exact inspected Fln model.");
    }
    @Override public void set(Object object, boolean value) { bindings.invoke(setter, object, value); }
}
