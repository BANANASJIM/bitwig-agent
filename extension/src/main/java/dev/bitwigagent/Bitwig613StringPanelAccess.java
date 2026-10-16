package dev.bitwigagent;

import java.lang.reflect.Method;

import static dev.bitwigagent.HostServices.failure;

/** Exact read-only QEE model bindings; no scalar metadata or string setter. */
final class Bitwig613StringPanelAccess {
    private final Class<?> panel;
    private final Method identifier, value, property;
    private final Bitwig613Runtime.Access bindings;

    Bitwig613StringPanelAccess(Bitwig613Runtime.Access bindings) throws ReflectiveOperationException {
        this.bindings = bindings;
        panel = bindings.type("QEE");
        String[] ancestry = {"OJY", "Kx3", "VOa", "owt", "tD", "bKp", "q3", "DRK", "pbT",
                "com.bitwig.ramona.core.xKS", "com.bitwig.ramona.core.fJC"};
        Class<?> current = panel;
        for (String name : ancestry) {
            Class<?> parent = bindings.type(name);
            if (current.getSuperclass() != parent)
                throw new NoSuchMethodException("Inspected QEE string-panel ancestry differs.");
            current = parent;
        }
        Class<?> identified = bindings.type("VOa"), valued = bindings.type("OJY");
        Class<?> wrapper = bindings.type("com.bitwig.ramona.core.eH1");
        if (wrapper.getSuperclass() != bindings.type("com.bitwig.ramona.core.QKj"))
            throw new NoSuchMethodException("Inspected string property ancestry differs.");
        identifier = bindings.exactMethod(panel, "aA_", String.class, false);
        value = bindings.exactMethod(panel, "Ynu", String.class, false);
        property = bindings.exactMethod(panel, "vhT", wrapper, false);
        if (identifier.getDeclaringClass() != identified || value.getDeclaringClass() != valued
                || property.getDeclaringClass() != valued)
            throw new NoSuchMethodException("Expected the inspected string identifier/value/property getters.");
    }

    boolean isPanel(Object object) { return object != null && object.getClass() == panel; }
    Object identifier(Object object) { requireSupported(object); return bindings.invoke(identifier, object); }
    Object value(Object object) { requireSupported(object); return bindings.invoke(value, object); }
    Object property(Object object) { requireSupported(object); return bindings.invoke(property, object); }
    private void requireSupported(Object object) {
        if (!isPanel(object)) throw failure(bindings.unavailableCode(), "String panel requires the exact inspected QEE model.");
    }
}
