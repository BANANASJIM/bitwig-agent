package dev.bitwigagent.nativehost;

import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * A device's direct parameters as the public {@code DeviceProxy} addresses them: {@code device.base.parameterIds()}
 * lists the parameters.list ids and {@code device.base.parameter(id)} is the native parameter behind one, the same
 * object automation lanes, modulation mappings and Remote Controls slots take.
 */
final class NativeDirectParameters {
    private final NativeHost.Access a;
    private final Method ids, parameter, name;

    NativeDirectParameters(NativeHost.Access a) throws ReflectiveOperationException {
        this.a = a;
        ids = a.exactMethod("device.base.parameterIds", String[].class, false);
        parameter = a.exactMethod("device.base.parameter", a.type("parameter.target"), false, String.class);
        name = a.exactMethod("parameter.target.name", String.class, false);
    }

    /** The device's direct parameter with this id, or null when it has none. */
    Object find(Object device, String id) {
        return Arrays.asList(ids(device)).contains(id) ? a.invoke(parameter, device, id) : null;
    }

    /** The id the device lists this parameter under, or null when it is not one of its direct parameters. */
    String id(Object device, Object target) {
        for (String id : ids(device)) if (a.invoke(parameter, device, id) == target) return id;
        return null;
    }

    String name(Object target) {
        return (String) a.invoke(name, target);
    }

    private String[] ids(Object device) {
        String[] found = (String[]) a.invoke(ids, device);
        return found == null ? new String[0] : found;
    }
}
