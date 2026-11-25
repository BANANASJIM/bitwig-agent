package dev.bitwigagent;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import static dev.bitwigagent.HostServices.failure;

/** Exact xMn string-panel bindings: reads, plus the final public btw.hUR(String) change operation when it binds. */
final class Bitwig614StringPanelAccess implements GridStringPanelWriter.Access {
    private final Class<?> panel;
    private final Method identifier, value, property;
    private Method setter;
    private String setterError;
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
        // A write-binding failure must not take the read bindings down with it.
        try {
            Method candidate = bindings.exactMethod(panel, "hUR", void.class, false, String.class);
            if (candidate.getDeclaringClass() != valued || !Modifier.isFinal(candidate.getModifiers()))
                throw new NoSuchMethodException("Expected the inspected final btw.hUR(String) string operation.");
            setter = candidate;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            setterError = Bitwig614Runtime.diagnostic(error);
        }
    }

    /** Null when the write operation is bound; otherwise why not. */
    String setterError() { return setterError; }

    boolean isPanel(Object object) { return object != null && object.getClass() == panel; }
    Object identifier(Object object) { requireSupported(object); return bindings.invoke(identifier, object); }
    Object value(Object object) { requireSupported(object); return bindings.invoke(value, object); }
    Object property(Object object) { requireSupported(object); return bindings.invoke(property, object); }
    @Override public void set(Object object, String text) {
        requireSupported(object);
        if (setter == null) throw failure(bindings.unavailableCode(), "String panel write operation is not bound: " + setterError);
        if (text == null) throw failure("invalid_params", "String panel text must not be null.");
        bindings.invoke(setter, object, text);
    }
    @Override public void requireSupported(Object object) {
        if (!isPanel(object)) throw failure(bindings.unavailableCode(), "String panel requires the exact inspected xMn model.");
    }
}
