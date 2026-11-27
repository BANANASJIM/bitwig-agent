package dev.bitwigagent.nativehost;

import static dev.bitwigagent.grid.GridSnapshotReader.Kind.*;

import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.grid.GridParameterWriter;
import dev.bitwigagent.grid.GridSnapshotReader;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;

/** The inspected external-controller operation, never the engine-input or generated GUI setter. */
final class NativeGridParameterWriter implements GridParameterWriter.Access {
    private final Method raw;
    private final Map<Class<?>, GridSnapshotReader.Kind> models;
    private final NativeHost.Access bindings;

    NativeGridParameterWriter(NativeHost.Access bindings) throws ReflectiveOperationException {
        this.bindings = bindings;
        Class<?> controller = bindings.type("parameter.controller");
        raw = bindings.exactMethod("parameter.controller.setRaw", void.class, false, double.class);
        if (raw.getDeclaringClass() != controller || !Modifier.isFinal(raw.getModifiers()))
            throw new NoSuchMethodException(
                    "Expected the final parameter.controller.setRaw(double):void controller operation.");
        Class<?> bool = bindings.type("parameter.booleanController"),
                derived = bindings.type("parameter.derivedBoolean"),
                parent = bindings.type("parameter.derivedBooleanBase");
        if (derived.getSuperclass() != parent || parent.getSuperclass() != bool)
            throw new NoSuchMethodException("Inspected parameter.derivedBoolean boolean inheritance differs.");
        Class<?> floating = bindings.type("parameter.floatController"),
                bipolar = bindings.type("parameter.bipolar"),
                bipolarParent = bindings.type("parameter.bipolarBase");
        if (bipolar.getSuperclass() != bipolarParent || bipolarParent.getSuperclass() != floating)
            throw new NoSuchMethodException("Inspected parameter.bipolar floating inheritance differs.");
        Map<String, GridSnapshotReader.Kind> keys = Map.of(
                "parameter.floatController",
                FLOAT,
                "parameter.bipolar",
                FLOAT,
                "parameter.integerController",
                INTEGER,
                "parameter.enumController",
                ENUM,
                "parameter.booleanController",
                BOOLEAN,
                "parameter.derivedBoolean",
                BOOLEAN);
        models = new HashMap<>();
        for (Map.Entry<String, GridSnapshotReader.Kind> entry : keys.entrySet()) {
            Class<?> model = bindings.type(entry.getKey());
            if (!controller.isAssignableFrom(model)
                    || !bindings.exactMethod(entry.getKey() + ".setRaw", void.class, false, double.class)
                            .equals(raw))
                throw new NoSuchMethodException(
                        "Model does not inherit the exact inspected raw controller operation: " + entry.getKey());
            models.put(model, entry.getValue());
        }
    }

    @Override
    public void requireSupported(Object parameter, GridSnapshotReader.Kind kind) {
        if (parameter == null || models.get(parameter.getClass()) != kind)
            throw Errors.notApplied(
                    Reason.NATIVE_UNAVAILABLE,
                    "Native scalar implementation is not an inspected controller-write model.");
    }

    @Override
    public void setRaw(Object parameter, double value) {
        bindings.invoke(raw, parameter, value);
    }
}
