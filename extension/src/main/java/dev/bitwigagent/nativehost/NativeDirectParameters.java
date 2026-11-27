package dev.bitwigagent.nativehost;

import dev.bitwigagent.device.DirectParameters;
import java.lang.reflect.Method;

/**
 * The one binding of a device's direct parameters, as the public {@code DeviceProxy} addresses them:
 * {@code device.base.parameterIds()} lists the parameters.list ids and {@code device.base.parameter(id)} is the native
 * parameter behind one, the object automation lanes, modulation mappings and Remote Controls slots take.
 */
final class NativeDirectParameters implements DirectParameters.Lookup {
    private final NativeHost.Access a;
    private final Method ids, parameter, name;

    NativeDirectParameters(NativeHost.Access a) throws ReflectiveOperationException {
        this.a = a;
        ids = a.exactMethod("device.base.parameterIds", String[].class, false);
        parameter = a.exactMethod("device.base.parameter", a.type("parameter.target"), false, String.class);
        name = a.exactMethod("parameter.target.name", String.class, false);
    }

    @Override
    public String[] parameterIds(Object device) {
        String[] found = (String[]) a.invoke(ids, device);
        return found == null ? new String[0] : found;
    }

    @Override
    public Object parameter(Object device, String id) {
        return a.invoke(parameter, device, id);
    }

    @Override
    public String parameterName(Object target) {
        return (String) a.invoke(name, target);
    }
}
