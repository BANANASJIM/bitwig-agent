package dev.bitwigagent.bitwig614;

import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.grid.GridSnapshotReader;
import dev.bitwigagent.grid.GridStepArrayWriter;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Exact imU step-array bindings (Steps, Pitches, Gates, Probabilities, Accents): FDM reads, plus the public final
 * FDM.FzB(float[]) recorded document change when it binds.
 */
final class Bitwig614StepArrayAccess implements GridStepArrayWriter.Access {
    private final Class<?> model;
    private final Method identifier, data, length, property;
    private Method setter;
    private String setterError;
    private final Bitwig614Runtime.Access bindings;

    Bitwig614StepArrayAccess(Bitwig614Runtime.Access bindings) throws ReflectiveOperationException {
        this.bindings = bindings;
        model = bindings.type("imU");
        Class<?> base = bindings.type("FDM");
        if (model.getSuperclass() != base || base.getSuperclass() != bindings.type("K2G"))
            throw new NoSuchMethodException("Inspected imU/FDM/K2G ancestry differs.");
        Class<?> wrapper = bindings.type("com.bitwig.ramona.core.ESe");
        identifier = bindings.exactMethod(model, "aA_", String.class, false);
        data = bindings.exactMethod(model, "UNj", float[].class, false);
        length = bindings.exactMethod(model, "ODA", int.class, false);
        property = bindings.exactMethod(model, "tp2", wrapper, false);
        for (Method method : new Method[] {identifier, data, length, property})
            if (method.getDeclaringClass() != base)
                throw new NoSuchMethodException("Expected the inspected FDM step-array getters.");
        // A write-binding failure must not take the read bindings down with it.
        try {
            Method candidate = bindings.exactMethod(model, "FzB", void.class, false, float[].class);
            if (candidate.getDeclaringClass() != base || !Modifier.isFinal(candidate.getModifiers()))
                throw new NoSuchMethodException("Expected the inspected final FDM.FzB(float[]) array operation.");
            setter = candidate;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            setterError = Bitwig614Runtime.diagnostic(error);
        }
    }

    /** Null when the write operation is bound; otherwise why not. */
    String setterError() {
        return setterError;
    }

    boolean isModel(Object object) {
        return object != null && object.getClass() == model;
    }

    Object identifier(Object object) {
        requireSupported(object);
        return bindings.invoke(identifier, object);
    }

    Object data(Object object) {
        requireSupported(object);
        return bindings.invoke(data, object);
    }

    Object length(Object object) {
        requireSupported(object);
        return bindings.invoke(length, object);
    }

    Object property(Object object) {
        requireSupported(object);
        return bindings.invoke(property, object);
    }

    @Override
    public float[] current(Object object) {
        requireSupported(object);
        Object size = bindings.invoke(length, object);
        if (!(size instanceof Integer))
            throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "Native step array length is unavailable.");
        return GridSnapshotReader.effectiveSteps(bindings.invoke(data, object), (Integer) size);
    }

    @Override
    public void set(Object object, float[] values) {
        requireSupported(object);
        if (setter == null)
            throw Errors.notApplied(
                    Reason.NATIVE_UNAVAILABLE, "Step array write operation is not bound: " + setterError);
        if (values == null) throw Errors.notApplied(Reason.INVALID_PARAMS, "Step array must not be null.");
        bindings.invoke(setter, object, values);
    }

    @Override
    public void requireSupported(Object object) {
        if (!isModel(object))
            throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "Step array requires the exact inspected imU model.");
    }
}
