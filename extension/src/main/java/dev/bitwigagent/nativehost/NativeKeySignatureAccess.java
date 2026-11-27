package dev.bitwigagent.nativehost;

import com.google.gson.JsonObject;
import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.project.KeySignatureModule;
import java.lang.reflect.Method;
import java.util.*;

/**
 * Exact bindings for the project Key Signature: the controller Project proxy's target is the native project, whose
 * transport ({@code project.state.transport}) holds the tonal space and its key signature. Root Key and Scale are host
 * enum parameters; their option lists are Bitwig's own (Root Key 0..11, Scale a pitch-class bit mask, e.g. Minor 1453).
 * {@code keySignature.displayName} is Bitwig's scale definition for the current mask: its {@code toString} is the
 * scale's lower-case name, or {@code custom [...]} for a mask outside the list.
 *
 * <p>A write calls {@code parameter.controller.setRaw(double)}, the controller setter, for each changed parameter
 * inside {@code project.begin(label, false)} / {@code project.end(false)}: the transaction manager reports only the
 * outermost begin and end to the undo recorder (depth counter), so Root Key and Scale are one Undo step.
 */
public final class NativeKeySignatureAccess implements KeySignatureModule.Access {
    private final NativeHost.Access a;
    private final Class<?> projectProxyType, projectType;
    private final Method target, transport, tonalSpace, keySignature, root, scale, scaleName;
    private final Method value, definition, options, optionValue, optionLabel, setRaw, begin, end;

    /** The exact bindings, or native_unavailable when a key or descriptor does not match the loaded host. */
    NativeKeySignatureAccess(NativeHost.Access a) throws ReflectiveOperationException {
        this.a = a;
        projectProxyType = a.type("proxy.project");
        projectType = a.type("project");
        target = a.exactMethod("proxy.controlSurface.target", Object.class, false);
        transport = a.exactMethod("project.state.transport", a.type("project.transport"), false);
        tonalSpace = a.exactMethod("project.transport.tonalSpace", a.type("project.tonalSpace"), false);
        keySignature = a.exactMethod("project.tonalSpaceBase.keySignature", a.type("project.keySignature"), false);
        root = a.exactMethod("project.keySignatureBase.rootKey", a.type("project.rootKeyParam"), false);
        scale = a.exactMethod("project.keySignatureBase.scale", a.type("project.scaleParam"), false);
        scaleName = a.exactMethod("project.keySignature.displayName", a.type("project.scaleDefinition"), false);
        value = a.exactMethod("parameter.enum.value", int.class, false);
        definition = a.exactMethod("parameter.enum.definition", a.type("parameter.enumDefinition"), false);
        options = a.exactMethod("parameter.enumDefinition.options", List.class, false);
        optionValue = a.exactMethod("parameter.option.value", int.class, false);
        optionLabel = a.exactMethod("parameter.option.label", String.class, false);
        setRaw = a.exactMethod("parameter.controller.setRaw", void.class, false, double.class);
        begin = a.exactMethod("project.begin", void.class, false, String.class, boolean.class);
        end = a.exactMethod("project.end", void.class, false, boolean.class);
    }

    @Override
    public void execute(Runnable work) {
        a.execute(work);
    }

    @Override
    public void provenance(JsonObject out) {
        a.provenance(out);
    }

    @Override
    public KeySignatureModule.State read(Object projectProxy) {
        a.documentThread();
        Object signature = signature(project(projectProxy));
        return new KeySignatureModule.State(
                parameter(a.invoke(root, signature)),
                parameter(a.invoke(scale, signature)),
                String.valueOf(a.invoke(scaleName, signature)));
    }

    @Override
    public void write(Object projectProxy, Map<String, Integer> values, Runnable beforeWrite) {
        a.documentThread();
        Object project = project(projectProxy), signature = signature(project);
        a.requireModelThread(signature);
        Map<String, Object> parameters = Map.of("root", a.invoke(root, signature), "scale", a.invoke(scale, signature));
        beforeWrite.run();
        a.invoke(begin, project, KeySignatureModule.UNDO_LABEL, false);
        try {
            for (Map.Entry<String, Integer> entry : values.entrySet())
                a.invoke(
                        setRaw, parameters.get(entry.getKey()), entry.getValue().doubleValue());
        } finally {
            a.invoke(end, project, false);
        }
    }

    private Object project(Object projectProxy) {
        if (!projectProxyType.isInstance(projectProxy))
            throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "Controller Project is not the native project proxy.");
        Object project = a.invoke(target, projectProxy);
        if (!projectType.isInstance(project))
            throw Errors.notApplied(Reason.NOT_READY, "Controller Project proxy is not bound to an open project.");
        return project;
    }

    private Object signature(Object project) {
        return a.invoke(keySignature, a.invoke(tonalSpace, a.invoke(transport, project)));
    }

    private KeySignatureModule.Parameter parameter(Object parameter) {
        List<KeySignatureModule.Option> list = new ArrayList<>();
        for (Object option : (List<?>) a.invoke(options, a.invoke(definition, parameter)))
            list.add(new KeySignatureModule.Option(
                    (Integer) a.invoke(optionValue, option), (String) a.invoke(optionLabel, option)));
        return new KeySignatureModule.Parameter((Integer) a.invoke(value, parameter), list);
    }
}
