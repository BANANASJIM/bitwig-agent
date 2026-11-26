package dev.bitwigagent;

import static dev.bitwigagent.NativeDeviceTarget.Field.*;

import java.util.EnumMap;
import java.util.function.UnaryOperator;

/** Exact public descriptors for native root-device ownership, independent of Grid. */
final class Bitwig614DeviceTargetAccess implements NativeDeviceTarget.Access {
    private final Bitwig614Runtime.Access bindings;
    private final EnumMap<NativeDeviceTarget.Kind, Class<?>> kinds = new EnumMap<>(NativeDeviceTarget.Kind.class);
    private final EnumMap<NativeDeviceTarget.Field, UnaryOperator<Object>> readers =
            new EnumMap<>(NativeDeviceTarget.Field.class);

    Bitwig614DeviceTargetAccess(Bitwig614Runtime.Access bindings) throws ReflectiveOperationException {
        this.bindings = bindings;
        Bitwig614TrackResolver resolver = new Bitwig614TrackResolver(bindings);
        kinds.put(NativeDeviceTarget.Kind.DEVICE, bindings.type("com.bitwig.flt.document.core.master.device.xlG"));
        kinds.put(NativeDeviceTarget.Kind.TRACK, resolver.trackType());
        kinds.put(NativeDeviceTarget.Kind.ALIASED_TRACK, resolver.groupType());
        kinds.put(NativeDeviceTarget.Kind.CHANNEL_ALIAS, resolver.aliasType());
        readers.put(TARGET, resolver::target);
        readers.put(OWNER_TRACK, device -> resolver.owner(device, resolver.trackType()));
        readers.put(TRACK_ALIAS, resolver::alias);
        readers.put(TRACK_UUID, resolver::uuid);
        readers.put(ALIAS_UUID, resolver::aliasUuid);
    }

    @Override
    public boolean is(NativeDeviceTarget.Kind kind, Object object) {
        return kinds.get(kind).isInstance(object);
    }

    @Override
    public Object read(NativeDeviceTarget.Field field, Object object) {
        return readers.get(field).apply(object);
    }

    @Override
    public ErrorCode unavailableCode() {
        return bindings.unavailableCode();
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
