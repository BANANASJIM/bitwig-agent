package dev.bitwigagent.nativehost;

import static dev.bitwigagent.host.DeviceSources.Native.Field.*;

import dev.bitwigagent.host.DeviceSources;
import java.util.EnumMap;
import java.util.function.UnaryOperator;

/** Exact public descriptors for native root-device ownership, independent of Grid. */
final class NativeDeviceTargetAccess implements DeviceSources.Native.Access {
    private final NativeHost.Access bindings;
    private final EnumMap<DeviceSources.Native.Kind, Class<?>> kinds = new EnumMap<>(DeviceSources.Native.Kind.class);
    private final EnumMap<DeviceSources.Native.Field, UnaryOperator<Object>> readers =
            new EnumMap<>(DeviceSources.Native.Field.class);

    NativeDeviceTargetAccess(NativeHost.Access bindings) throws ReflectiveOperationException {
        this.bindings = bindings;
        NativeTrackResolver resolver = new NativeTrackResolver(bindings);
        kinds.put(DeviceSources.Native.Kind.DEVICE, bindings.type("device"));
        kinds.put(DeviceSources.Native.Kind.TRACK, resolver.trackType());
        kinds.put(DeviceSources.Native.Kind.ALIASED_TRACK, resolver.groupType());
        kinds.put(DeviceSources.Native.Kind.CHANNEL_ALIAS, resolver.aliasType());
        readers.put(TARGET, resolver::target);
        readers.put(OWNER_TRACK, device -> resolver.owner(device, resolver.trackType()));
        readers.put(TRACK_ALIAS, resolver::alias);
        readers.put(TRACK_UUID, resolver::uuid);
        readers.put(ALIAS_UUID, resolver::aliasUuid);
    }

    @Override
    public boolean is(DeviceSources.Native.Kind kind, Object object) {
        return kinds.get(kind).isInstance(object);
    }

    @Override
    public Object read(DeviceSources.Native.Field field, Object object) {
        return readers.get(field).apply(object);
    }

    @Override
    public void documentThread() {
        bindings.documentThread();
    }

    @Override
    public void requireModelThread(Object object) {
        bindings.requireModelThread(object);
    }
}
