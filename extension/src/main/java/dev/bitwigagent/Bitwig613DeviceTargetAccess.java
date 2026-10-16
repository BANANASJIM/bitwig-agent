package dev.bitwigagent;

import java.lang.reflect.Method;
import java.util.EnumMap;
import java.util.UUID;
import static dev.bitwigagent.NativeDeviceTarget.Field.*;

/** Exact public descriptors for native root-device ownership, independent of Grid. */
final class Bitwig613DeviceTargetAccess implements NativeDeviceTarget.Access {
    private final Bitwig613Runtime.Access bindings;
    private final EnumMap<NativeDeviceTarget.Kind, Class<?>> kinds = new EnumMap<>(NativeDeviceTarget.Kind.class);
    private final EnumMap<NativeDeviceTarget.Field, Method> readers = new EnumMap<>(NativeDeviceTarget.Field.class);
    Bitwig613DeviceTargetAccess(Bitwig613Runtime.Access bindings) throws ReflectiveOperationException {
        this.bindings = bindings;
        String iface = "com.bitwig.flt.document.core.iface.";
        kinds.put(NativeDeviceTarget.Kind.DEVICE, bindings.type("com.bitwig.flt.document.core.master.device.Y2N"));
        kinds.put(NativeDeviceTarget.Kind.TRACK, bindings.type(iface + "HBW"));
        Class<?> aliased = bindings.type(iface + "SCA"), alias = bindings.type("jkg");
        kinds.put(NativeDeviceTarget.Kind.ALIASED_TRACK, aliased);
        kinds.put(NativeDeviceTarget.Kind.CHANNEL_ALIAS, alias);
        readers.put(TARGET, bindings.exactMethod(bindings.type("com.bitwig.flt.control_surface.proxy.ControlSurfaceProxy"), "getDeepestTarget", Object.class, false));
        readers.put(OWNER_TRACK, bindings.exactMethod(bindings.type("com.bitwig.ramona.core.fJC"), "k31", Object.class, false, Class.class));
        readers.put(TRACK_ALIAS, bindings.exactMethod(aliased, "xZH", alias, false));
        readers.put(TRACK_UUID, bindings.exactMethod(bindings.type(iface + "sIL"), "FJH", UUID.class, false));
        readers.put(ALIAS_UUID, bindings.exactMethod(alias, "FJH", UUID.class, false));
    }
    @Override public boolean is(NativeDeviceTarget.Kind kind, Object object) { return kinds.get(kind).isInstance(object); }
    @Override public Object read(NativeDeviceTarget.Field field, Object object) {
        Method method = readers.get(field);
        return field == OWNER_TRACK ? bindings.invoke(method, object, kinds.get(NativeDeviceTarget.Kind.TRACK))
                : bindings.invoke(method, object);
    }
    @Override public String unavailableCode() { return bindings.unavailableCode(); }
    @Override public void documentThread() { bindings.documentThread(); }
    @Override public void requireModelThread(Object object) { bindings.requireModelThread(object); }
}
