package dev.bitwigagent.device;

import com.google.gson.JsonObject;
import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.Refs;
import java.util.Arrays;

/**
 * A device's direct parameters, the ones {@code parameters.list} names by id: the one lookup automation, modulation
 * and Remote Controls pages share to find the native parameter a device-owned parameter ref names.
 */
public final class DirectParameters {
    /** The document-thread reads of a device's direct parameters; one binding per host version. */
    public interface Lookup {
        String[] parameterIds(Object device);

        /** The device's direct parameter with this id; only asked for an id {@link #parameterIds} lists. */
        Object parameter(Object device, String id);

        String parameterName(Object parameter);
    }

    private DirectParameters() {}

    /**
     * The parameter a device-owned parameter ref names on {@code device}, whose ref is {@code owner}: target_not_found
     * when the device lists no such id, target_changed when the parameter there is not the one the ref read.
     */
    public static Object resolve(Lookup a, Object device, JsonObject owner, JsonObject ref) {
        String id = ref.get("id").getAsString();
        if (!Arrays.asList(a.parameterIds(device)).contains(id))
            throw Errors.targetNotFound(ref, "The device has no such parameter now.");
        Object parameter = a.parameter(device, id);
        JsonObject current = ref(a, owner, id, parameter);
        if (!Refs.matches(ref, current, true)) throw Refs.parameterChanged(ref, current);
        return parameter;
    }

    /** The ref of the device's direct parameter {@code target}, or null when it is not one of them. */
    public static JsonObject find(Lookup a, Object device, JsonObject owner, Object target) {
        for (String id : a.parameterIds(device))
            if (a.parameter(device, id) == target) return ref(a, owner, id, target);
        return null;
    }

    private static JsonObject ref(Lookup a, JsonObject owner, String id, Object parameter) {
        String name = a.parameterName(parameter);
        return Refs.parameter(owner, id, name == null || name.isEmpty() ? null : name);
    }
}
