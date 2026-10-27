package dev.bitwigagent;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import static dev.bitwigagent.HostServices.failure;

/** Exact boolean-panel model operations; never an aLw scalar or a GUI widget binding. */
final class Bitwig614GridBooleanPanelAccess implements GridBooleanPanelWriter.Access {
    private final Class<?> panel, bipolar;
    private final Method identifier, value, property, linkedPanel, setter;
    private final Bitwig614Runtime.Access bindings;

    Bitwig614GridBooleanPanelAccess(Bitwig614Runtime.Access bindings) throws ReflectiveOperationException {
        this.bindings = bindings;
        panel = bindings.type("BMo");
        Class<?> parent = bindings.type("xqx"), intermediate = bindings.type("moR");
        Class<?> identified = bindings.type("Hys"), component = bindings.type("EpE"), base = bindings.type("Bxc");
        if (panel.getSuperclass() != parent || parent.getSuperclass() != intermediate
                || intermediate.getSuperclass() != identified || identified.getSuperclass() != component
                || component.getSuperclass() != base || base.getSuperclass() != bindings.type("K2G"))
            throw new NoSuchMethodException("Inspected BMo boolean-panel ancestry differs.");
        identifier = bindings.exactMethod(panel, "aA_", String.class, false);
        value = bindings.exactMethod(panel, "vpw", boolean.class, false);
        property = bindings.exactMethod(panel, "cfM", bindings.type("com.bitwig.ramona.core.lBQ"), false);
        setter = bindings.exactMethod(panel, "MRY", void.class, false, boolean.class);
        if (identifier.getDeclaringClass() != identified || value.getDeclaringClass() != parent
                || property.getDeclaringClass() != parent || setter.getDeclaringClass() != parent
                || !Modifier.isFinal(setter.getModifiers()))
            throw new NoSuchMethodException("Expected the inspected identifier/property/final boolean operation.");
        bipolar = bindings.type("Hgx");
        Class<?> bipolarParent = bindings.type("rXz");
        if (bipolar.getSuperclass() != bipolarParent || bipolarParent.getSuperclass() != bindings.type("rce"))
            throw new NoSuchMethodException("Inspected Hgx/rXz/rce ancestry differs.");
        linkedPanel = bindings.exactMethod(bipolar, "Z1C", panel, false);
        if (linkedPanel.getDeclaringClass() != bipolarParent)
            throw new NoSuchMethodException("Expected the inspected rXz boolean-panel reference getter.");
    }

    boolean isPanel(Object object) { return object != null && object.getClass() == panel; }
    boolean isBipolar(Object object) { return object != null && object.getClass() == bipolar; }
    Object identifier(Object object) { requireSupported(object); return bindings.invoke(identifier, object); }
    Object value(Object object) { requireSupported(object); return bindings.invoke(value, object); }
    Object property(Object object) { requireSupported(object); return bindings.invoke(property, object); }
    Object linkedPanel(Object object) {
        if (!isBipolar(object)) throw failure("grid_unavailable", "Bipolar reference requires the exact inspected Hgx model.");
        return bindings.invoke(linkedPanel, object);
    }
    @Override public void requireSupported(Object object) {
        if (!isPanel(object)) throw failure("grid_unavailable", "Boolean panel requires the exact inspected BMo model.");
    }
    @Override public void set(Object object, boolean value) { bindings.invoke(setter, object, value); }
}
