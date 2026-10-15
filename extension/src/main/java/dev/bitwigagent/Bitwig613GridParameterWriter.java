package dev.bitwigagent;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import static dev.bitwigagent.GridSnapshotReader.Kind.*;
import static dev.bitwigagent.HostServices.failure;

/** The inspected external-controller operation, never the engine-input or generated GUI setter. */
final class Bitwig613GridParameterWriter implements GridParameterWriter.Access {
    private final Method raw;
    private final Map<Class<?>, GridSnapshotReader.Kind> models;

    Bitwig613GridParameterWriter(Bitwig613GridAdapter bindings) throws ReflectiveOperationException {
        Class<?> controller = bindings.type("iTc");
        raw = bindings.exactMethod(controller, "JQk", void.class, false, double.class);
        if (raw.getDeclaringClass() != controller || !Modifier.isFinal(raw.getModifiers()))
            throw new NoSuchMethodException("Expected the final iTc.JQk(double):void controller operation.");
        Class<?> bool = bindings.type("y1l"), derived = bindings.type("ZPH"), parent = bindings.type("jC2");
        if (derived.getSuperclass() != parent || parent.getSuperclass() != bool)
            throw new NoSuchMethodException("Inspected ZPH/jC2/y1l boolean inheritance differs.");
        Class<?> floating = bindings.type("APs"), bipolar = bindings.type("TZj"), bipolarParent = bindings.type("Jcb");
        if (bipolar.getSuperclass() != bipolarParent || bipolarParent.getSuperclass() != floating)
            throw new NoSuchMethodException("Inspected TZj/Jcb/APs floating inheritance differs.");
        models = Map.of(floating, FLOAT, bipolar, FLOAT, bindings.type("aJV"), INTEGER,
                bindings.type("LtR"), ENUM, bool, BOOLEAN, derived, BOOLEAN);
        for (Class<?> model : models.keySet()) {
            if (!controller.isAssignableFrom(model)
                    || !bindings.exactMethod(model, "JQk", void.class, false, double.class).equals(raw))
                throw new NoSuchMethodException("Model does not inherit the exact inspected raw controller operation: " + model.getName());
        }
    }

    @Override public void requireSupported(Object parameter, GridSnapshotReader.Kind kind) {
        if (parameter == null || models.get(parameter.getClass()) != kind)
            throw failure("grid_unavailable", "Native scalar implementation is not an inspected controller-write model.");
    }

    @Override public void setRaw(Object parameter, double value) {
        Bitwig613GridAdapter.invoke(raw, parameter, value);
    }
}
