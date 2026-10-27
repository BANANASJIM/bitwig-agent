package dev.bitwigagent;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import static dev.bitwigagent.GridSnapshotReader.Kind.*;
import static dev.bitwigagent.HostServices.failure;

/** The inspected external-controller operation, never the engine-input or generated GUI setter. */
final class Bitwig614GridParameterWriter implements GridParameterWriter.Access {
    private final Method raw;
    private final Map<Class<?>, GridSnapshotReader.Kind> models;
    private final Bitwig614Runtime.Access bindings;

    Bitwig614GridParameterWriter(Bitwig614Runtime.Access bindings) throws ReflectiveOperationException {
        this.bindings = bindings;
        Class<?> controller = bindings.type("aLw");
        raw = bindings.exactMethod(controller, "UNj", void.class, false, double.class);
        if (raw.getDeclaringClass() != controller || !Modifier.isFinal(raw.getModifiers()))
            throw new NoSuchMethodException("Expected the final aLw.UNj(double):void controller operation.");
        Class<?> bool = bindings.type("RuU"), derived = bindings.type("nhW"), parent = bindings.type("BEI");
        if (derived.getSuperclass() != parent || parent.getSuperclass() != bool)
            throw new NoSuchMethodException("Inspected nhW/BEI/RuU boolean inheritance differs.");
        Class<?> floating = bindings.type("rce"), bipolar = bindings.type("Hgx"), bipolarParent = bindings.type("rXz");
        if (bipolar.getSuperclass() != bipolarParent || bipolarParent.getSuperclass() != floating)
            throw new NoSuchMethodException("Inspected Hgx/rXz/rce floating inheritance differs.");
        models = Map.of(floating, FLOAT, bipolar, FLOAT, bindings.type("iS3"), INTEGER,
                bindings.type("Lig"), ENUM, bool, BOOLEAN, derived, BOOLEAN);
        for (Class<?> model : models.keySet()) {
            if (!controller.isAssignableFrom(model)
                    || !bindings.exactMethod(model, "UNj", void.class, false, double.class).equals(raw))
                throw new NoSuchMethodException("Model does not inherit the exact inspected raw controller operation: " + model.getName());
        }
    }

    @Override public void requireSupported(Object parameter, GridSnapshotReader.Kind kind) {
        if (parameter == null || models.get(parameter.getClass()) != kind)
            throw failure("grid_unavailable", "Native scalar implementation is not an inspected controller-write model.");
    }

    @Override public void setRaw(Object parameter, double value) {
        bindings.invoke(raw, parameter, value);
    }
}
