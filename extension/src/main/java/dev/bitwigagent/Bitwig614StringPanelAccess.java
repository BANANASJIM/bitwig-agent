package dev.bitwigagent;

import java.lang.reflect.Method;

import static dev.bitwigagent.HostServices.failure;

/** Exact read-only xMn model bindings; no scalar metadata or string setter. */
final class Bitwig614StringPanelAccess {
    private final Class<?> panel;
    private final Method identifier, value, property;
    private final Bitwig614Runtime.Access bindings;

    Bitwig614StringPanelAccess(Bitwig614Runtime.Access bindings) throws ReflectiveOperationException {
        this.bindings = bindings;
        panel = bindings.type("xMn");
        String[] ancestry = {"btw", "moR", "Hys", "EpE", "Bxc", "K2G", "to", "sw3", "erm",
                "com.bitwig.ramona.core.oDc", "com.bitwig.ramona.core.AVL"};
        Class<?> current = panel;
        for (String name : ancestry) {
            Class<?> parent = bindings.type(name);
            if (current.getSuperclass() != parent)
                throw new NoSuchMethodException("Inspected xMn string-panel ancestry differs.");
            current = parent;
        }
        Class<?> identified = bindings.type("Hys"), valued = bindings.type("btw");
        Class<?> wrapper = bindings.type("com.bitwig.ramona.core.ESe");
        if (wrapper.getSuperclass() != bindings.type("com.bitwig.ramona.core.Dgc"))
            throw new NoSuchMethodException("Inspected string property ancestry differs.");
        identifier = bindings.exactMethod(panel, "aA_", String.class, false);
        value = bindings.exactMethod(panel, "vpw", String.class, false);
        property = bindings.exactMethod(panel, "cfM", wrapper, false);
        if (identifier.getDeclaringClass() != identified || value.getDeclaringClass() != valued
                || property.getDeclaringClass() != valued)
            throw new NoSuchMethodException("Expected the inspected string identifier/value/property getters.");
    }

    boolean isPanel(Object object) { return object != null && object.getClass() == panel; }
    Object identifier(Object object) { requireSupported(object); return bindings.invoke(identifier, object); }
    Object value(Object object) { requireSupported(object); return bindings.invoke(value, object); }
    Object property(Object object) { requireSupported(object); return bindings.invoke(property, object); }
    private void requireSupported(Object object) {
        if (!isPanel(object)) throw failure(bindings.unavailableCode(), "String panel requires the exact inspected xMn model.");
    }
}
