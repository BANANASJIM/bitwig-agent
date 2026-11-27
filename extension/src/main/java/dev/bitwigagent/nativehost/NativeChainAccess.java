package dev.bitwigagent.nativehost;

import com.google.gson.JsonObject;
import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.device.NativeChains;
import dev.bitwigagent.host.DeviceSources;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.*;

/**
 * Exact descriptors for chains and the devices inside them, native-verified on the test host (P2-C spike).
 * Chains: {@code device.innerChains()} is a {@code device.layeredChains} for Instrument/FX Layer, the Selectors and
 * Drum Machine (pads with content only); {@code device.layeredChains.chains()} lists its chains
 * ({@code chain.channel}), whose {@code chain.base.id()} is the controller API channelId. A chain's devices are
 * {@code chain.devices().items()}. Parameters are the direct parameters the public {@code DeviceProxy} reads and
 * writes: {@code device.base.parameter("CONTENTS/<id>")}, {@code parameter.read()}/{@code parameter.write(double)},
 * display {@code parameter.displayed.text()}. Edits use the model operations behind the public insertion points and
 * {@code deleteObject}: a {@code insertion.deviceChain} with a file payload ({@code GRZ.doInsertFile}) or a device
 * payload without the copy modifier ({@code GRZ.doInsertDevices} for a move), and the document's delete transaction
 * ({@code BitwigStudioHost.doDeleteObjects}). Each is one Undo step.
 *
 * <p>Layer mixers, Selectors and new chains ({@link Layers}) use keys added after the 6.1.4 and 6.1.7 entries
 * shipped, so they are bound on first use and their absence fails only the methods that need them. They are the
 * calls Bitwig's own controller proxies make on the same objects: {@code AbstractChannelProxy} reads a channel's
 * mixer ({@code document.channel.mixer}) for volume, pan and mute (direct parameters) and solo, {@code SoloProxy}
 * sets solo, and {@code ChainSelectorProxy} reads and sets a Selector's active chain index on the device's chains
 * ({@code device.chainSelector}). A new chain is the native "Add Nested Device Chain" and "Create empty chain"
 * operations, each in its own document transaction.
 */
public final class NativeChainAccess implements NativeChains.Access {
    /**
     * The controller API's ids for {@code device_type} ({@code RamonaEnumValueDefinition.RAMONA_ENUM_VALUE_IDS},
     * package-private in the inspected JAR); an unlisted value is reported by its native id, as the host does.
     */
    private static final Map<String, String> TYPES = Map.of(
            "audio_to_audio", "audio-effect",
            "note_to_audio", "instrument",
            "note_to_note", "note-effect",
            "audio_to_note", "note-detector");

    private final NativeHost.Access a;
    private final DeviceSources.Native roots;
    private final Class<?> container, aliasedOwner, parameterType, displayed;
    private final Method chainsOf, listed, chainList, chainDevices, chainId, chainName, chainIndex, value;
    private final Method deviceName, deviceType, enumId, enabled, parameterIds, parameter;
    private final Method parameterName, read, write, writable, display;
    private final Method document, begin, delete, end, ancestor, deviceSelection, selection, select;
    private final Method insertAt, payloadOf, devicesPayload, applicationResource, application;
    private final Method vst2, vst3, clap, vst2Plugins, vst3Plugins, clapPlugins, pluginFile;
    private final Constructor<?> point, filePayload, file;
    private final Object copy, noModifiers, copyModifier;
    private Layers layers;

    public NativeChainAccess(NativeHost.Access a) throws ReflectiveOperationException {
        this.a = a;
        roots = new DeviceSources.Native(new NativeDeviceTargetAccess(a));
        Class<?> device = a.type("device"), base = a.type("device.base");
        if (!base.isAssignableFrom(device)) throw new NoSuchMethodException("Inspected device is not a device.base.");
        container = a.type("device.layeredChains");
        chainsOf = a.exactMethod("device.innerChains", a.type("device.chainSet"), false);
        Class<?> chainType = a.type("chain"), listType = a.type("chain.deviceList");
        chainList = a.exactMethod("chain.devices", listType, false);
        chainDevices = a.exactMethod("device.list.items", List.class, false);
        listed = a.exactMethod("device.layeredChains.chains", List.class, false);
        chainId = a.exactMethod("chain.base.id", UUID.class, false);
        Class<?> channel = a.type("document.channel");
        Class<?> text = a.type("model.objectProperty"), integer = a.type("model.valueProperty");
        chainName = a.exactMethod("document.channel.name", text, false);
        chainIndex = a.exactMethod("document.channel.index", integer, false);
        value = a.exactMethod("model.property.get", Object.class, false);
        if (!chainType.isAssignableFrom(a.type("chain.channel")) || !channel.isAssignableFrom(a.type("chain.channel")))
            throw new NoSuchMethodException("Inspected chain.channel is not a channel chain.");
        deviceName = a.exactMethod("device.base.name", text, false);
        Class<?> enumAttribute = a.type("model.enum");
        deviceType = a.exactMethod("device.base.type", enumAttribute, false);
        enumId = a.exactMethod("model.enumValue.id", String.class, false);
        parameterType = a.type("parameter.target");
        enabled = a.exactMethod("device.base.enabled", a.type("parameter.onOff"), false);
        parameterIds = a.exactMethod("device.base.parameterIds", String[].class, false);
        parameter = a.exactMethod("device.base.parameter", parameterType, false, String.class);
        parameterName = a.exactMethod("parameter.target.name", String.class, false);
        read = a.exactMethod("parameter.target.read", double.class, false);
        write = a.exactMethod("parameter.target.write", void.class, false, double.class);
        writable = a.exactMethod("parameter.target.writable", boolean.class, false);
        displayed = a.type("parameter.controller");
        display = a.exactMethod("parameter.controller.display", String.class, false);
        // Delete: the document's own transaction, as BitwigStudioHost.doDeleteObjects runs it.
        Class<?> doc = a.type("document");
        document = a.exactMethod("device.base.document", doc, false);
        begin = a.exactMethod("document.begin", void.class, false, String.class, boolean.class);
        delete = a.exactMethod("document.delete", void.class, false, Collection.class);
        end = a.exactMethod("document.end", void.class, false, boolean.class);
        // Select: DeviceProxy.selectInEditor, on the track's device selection.
        Class<?> model = a.type("model.selectable");
        aliasedOwner = a.type("track.alias");
        ancestor = a.exactMethod("device.base.ancestor", Object.class, false, Class.class);
        Class<?> selections = a.type("track.selections"), deviceSelectionType = a.type("device.selection");
        deviceSelection = a.exactMethod("track.alias.selections", selections, false);
        selection = a.exactMethod("track.selections.device", deviceSelectionType, false);
        select = a.exactMethod("device.selection.select", void.class, false, model);
        // Insert and move: GRZ.doInsert on an insertion.deviceChain, as the public insertion points do.
        Class<?> faU = a.type("insertion.list"), handle = a.type("insertion.anchor");
        Class<?> payload = a.type("insertion.payload"), modifiers = a.type("insertion.modifiers");
        Class<?> insertPoint = a.type("insertion.point"), copyKind = a.type("insertion.copyKind");
        Class<?> listener = a.type("async.task"), insertion = a.type("insertion.deviceChain");
        if (!faU.isAssignableFrom(listType) || !insertPoint.isAssignableFrom(insertion))
            throw new NoSuchMethodException("Inspected device chain insertion point ancestry differs.");
        point = a.exactConstructor("insertion.deviceChain", faU, int.class, handle);
        insertAt = a.exactMethod("insertion.point.insert", boolean.class, false, payload, modifiers, listener);
        noModifiers = a.exactConstant("insertion.modifiers.none", modifiers);
        copy = a.exactConstant("insertion.copyKind.copy", copyKind);
        payloadOf = a.exactMethod("insertion.modifiers.with", modifiers, false, copyKind, boolean.class);
        copyModifier = a.invoke(payloadOf, noModifiers, copy, true);
        Class<?> files = a.type("io.file");
        file = a.exactConstructor("io.file", File.class);
        Class<?> fileInsertion = a.type("insertion.filePayload");
        if (!payload.isAssignableFrom(fileInsertion))
            throw new NoSuchMethodException("Inspected insertion.filePayload is not an insertion.payload.");
        filePayload = a.exactConstructor("insertion.filePayload", files);
        Class<?> devices = a.type("insertion.devicesPayload");
        if (!payload.isAssignableFrom(devices))
            throw new NoSuchMethodException("Inspected insertion.devicesPayload is not an insertion.payload.");
        devicesPayload = a.exactMethod("insertion.devicesPayload.of", devices, true, List.class);
        // Ids to files: GRZ.insertBitwigDevice/insertVST2Device/insertVST3Device/insertCLAPDevice.
        application = a.exactMethod("application.singleton", a.type("application"), true);
        applicationResource = a.exactMethod("application.resource", File.class, false, UUID.class);
        Class<?> vst2Reader = a.type("plugins.vst2Reader"), vst3Reader = a.type("plugins.vst3Reader");
        Class<?> clapReader = a.type("plugins.clapReader");
        vst2 = a.exactMethod("plugins.readers.vst2", vst2Reader, true);
        vst3 = a.exactMethod("plugins.readers.vst3", vst3Reader, true);
        clap = a.exactMethod("plugins.readers.clap", clapReader, true);
        vst2Plugins = a.exactMethod("plugins.vst2Reader.plugins", List.class, false, int.class);
        vst3Plugins = a.exactMethod("plugins.vst3Reader.plugins", List.class, false, String.class);
        clapPlugins = a.exactMethod("plugins.clapReader.plugins", List.class, false, String.class);
        pluginFile = a.exactMethod("plugins.entry.file", files, false);
    }

    @Override
    public void execute(Runnable work) {
        a.execute(work);
    }

    @Override
    public void documentThread() {
        a.documentThread();
    }

    @Override
    public Object root(DeviceSources.Source source, Runnable check) {
        return roots.capture(source, check).device;
    }

    @Override
    public List<Object> chains(Object device) {
        Object chains = a.invoke(chainsOf, device);
        if (!container.isInstance(chains)) return null;
        return copy(a.invoke(listed, chains));
    }

    @Override
    public UUID chainId(Object chain) {
        return (UUID) a.invoke(chainId, chain);
    }

    @Override
    public String chainName(Object chain) {
        return (String) a.invoke(value, a.invoke(chainName, chain));
    }

    @Override
    public int chainIndex(Object chain) {
        Object index = a.invoke(value, a.invoke(chainIndex, chain));
        return index instanceof Integer ? (Integer) index : -1;
    }

    @Override
    public List<Object> devices(Object chain) {
        return copy(a.invoke(chainDevices, a.invoke(chainList, chain)));
    }

    @Override
    public String deviceName(Object device) {
        return (String) a.invoke(value, a.invoke(deviceName, device));
    }

    @Override
    public String deviceType(Object device) {
        Object type = a.invoke(value, a.invoke(deviceType, device));
        if (type == null) return "";
        String id = (String) a.invoke(enumId, type);
        return TYPES.getOrDefault(id, id);
    }

    @Override
    public String[] parameterIds(Object device) {
        String[] ids = (String[]) a.invoke(parameterIds, device);
        return ids == null ? new String[0] : ids;
    }

    @Override
    public Object parameter(Object device, String id) {
        return a.invoke(parameter, device, id);
    }

    @Override
    public String parameterName(Object parameter) {
        return (String) a.invoke(parameterName, parameter);
    }

    @Override
    public double value(Object parameter) {
        return (Double) a.invoke(read, parameter);
    }

    @Override
    public String display(Object parameter) {
        return displayed.isInstance(parameter) ? (String) a.invoke(display, parameter) : null;
    }

    @Override
    public boolean writable(Object parameter) {
        return Boolean.TRUE.equals(a.invoke(writable, parameter));
    }

    @Override
    public void write(Object parameter, double normalized) {
        a.invoke(write, parameter, normalized);
    }

    @Override
    public Object enabled(Object device) {
        Object onOff = a.invoke(enabled, device);
        if (!parameterType.isInstance(onOff))
            throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "The device's On/Off is not a direct parameter here.");
        return onOff;
    }

    @Override
    public Object insertion(String kind, String value) {
        File source;
        switch (kind) {
            case "file":
                source = new File(value);
                return source.isFile() ? a.construct(filePayload, a.construct(file, source)) : null;
            case "bitwig":
                source = (File) a.invoke(applicationResource, a.invoke(application, null), UUID.fromString(value));
                return source == null ? null : a.construct(filePayload, a.construct(file, source));
            case "vst2":
                return plugin(a.invoke(vst2Plugins, a.invoke(vst2, null), Integer.parseInt(value)));
            case "vst3":
                return plugin(a.invoke(vst3Plugins, a.invoke(vst3, null), value));
            case "clap":
                return plugin(a.invoke(clapPlugins, a.invoke(clap, null), value));
            default:
                throw new IllegalStateException("Validated insertion kind changed");
        }
    }

    private Object plugin(Object found) {
        List<?> plugins = (List<?>) found;
        if (plugins == null || plugins.isEmpty()) return null;
        Object location = a.invoke(pluginFile, plugins.get(0));
        return location == null ? null : a.construct(filePayload, location);
    }

    @Override
    public void insert(Object chain, int index, Object insertion) {
        Object at = a.construct(point, a.invoke(chainList, chain), index, null);
        if (!Boolean.TRUE.equals(a.invoke(insertAt, at, insertion, copyModifier, null)))
            throw Errors.notApplied(Reason.HOST_REFUSED, "The host refused the insertion.");
    }

    @Override
    public void delete(Object device) {
        Object doc = a.invoke(document, device);
        a.invoke(begin, doc, "Delete Device", false);
        try {
            a.invoke(delete, doc, new ArrayList<>(List.of(device)));
        } finally {
            a.invoke(end, doc, false);
        }
    }

    @Override
    public void move(Object device, Object chain, int index) {
        Object at = a.construct(point, a.invoke(chainList, chain), index, null);
        Object moving = a.invoke(devicesPayload, null, new ArrayList<>(List.of(device)));
        if (!Boolean.TRUE.equals(a.invoke(insertAt, at, moving, noModifiers, null)))
            throw Errors.notApplied(Reason.HOST_REFUSED, "The host refused the move.");
    }

    @Override
    public void provenance(JsonObject out) {
        a.provenance(out);
    }

    @Override
    public void select(Object device) {
        Object owner = a.invoke(ancestor, device, aliasedOwner);
        if (owner == null)
            throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "The device has no track whose selection can change.");
        a.invoke(select, a.invoke(selection, a.invoke(deviceSelection, owner)), device);
    }

    @Override
    public NativeChains.Mixer mixer(Object chain) {
        Layers l = layers();
        Object mixer = a.invoke(l.mixer, chain);
        if (mixer == null) return null;
        return new NativeChains.Mixer(
                a.invoke(l.volume, mixer),
                a.invoke(l.pan, mixer),
                a.invoke(l.mute, mixer),
                Boolean.TRUE.equals(a.invoke(l.solo, mixer)));
    }

    @Override
    public void solo(Object chain, boolean on) {
        Layers l = layers();
        a.invoke(l.setSolo, a.invoke(l.mixer, chain), on);
    }

    @Override
    public Integer activeChain(Object device) {
        Layers l = layers();
        Object index = selectorIndex(l, device);
        return index == null ? null : (Integer) a.invoke(l.get, index);
    }

    @Override
    public void activeChain(Object device, int index) {
        Layers l = layers();
        a.invoke(l.set, selectorIndex(l, device), index);
    }

    private Object selectorIndex(Layers l, Object device) {
        Object chains = a.invoke(chainsOf, device);
        if (!l.selector.isInstance(chains)) return null;
        return a.invoke(l.index, a.invoke(l.selection, chains));
    }

    @Override
    public NativeChains.Container container(Object device) {
        Layers l = layers();
        Object chains = a.invoke(chainsOf, device);
        if (l.chainContainer.isInstance(chains)) return NativeChains.Container.CHAINS;
        return l.drumChains.isInstance(chains) ? NativeChains.Container.PADS : NativeChains.Container.NONE;
    }

    @Override
    public void addChain(Object device) {
        a.invoke(layers().addChain, a.invoke(chainsOf, device));
    }

    @Override
    public Object padChain(Object device, int pitch) {
        Layers l = layers();
        return a.invoke(l.padChain, pad(l, device, pitch));
    }

    @Override
    public void createPadChain(Object device, int pitch) {
        Layers l = layers();
        a.invoke(l.createPadChain, pad(l, device, pitch));
    }

    private Object pad(Layers l, Object device, int pitch) {
        return a.invoke(l.padByKey, a.invoke(chainsOf, device), pitch);
    }

    /** The {@link Layers} binding, resolved on the first call that needs it. */
    private Layers layers() {
        if (layers == null) {
            try {
                layers = new Layers();
            } catch (ReflectiveOperationException | LinkageError error) {
                throw Errors.notApplied(
                        Reason.NATIVE_UNAVAILABLE, "Native layer binding unavailable: " + NativeHost.diagnostic(error));
            }
        }
        return layers;
    }

    /** Exact descriptors for layer mixers, Selectors and new chains. */
    private final class Layers {
        final Class<?> selector, chainContainer, drumChains;
        final Method mixer, volume, pan, mute, solo, setSolo, selection, index, get, set;
        final Method addChain, padByKey, padChain, createPadChain;

        Layers() throws ReflectiveOperationException {
            Class<?> mixerType = a.type("channel.mixer"), target = a.type("parameter.target");
            mixer = a.exactMethod("document.channel.mixer", mixerType, false);
            volume = parameter("channel.mixer.volume", "channel.volume", target);
            pan = parameter("channel.mixer.pan", "channel.pan", target);
            mute = parameter("channel.mixer.mute", "channel.mute", target);
            solo = a.exactMethod("channel.mixer.solo", boolean.class, false);
            setSolo = a.exactMethod("channel.mixer.setSolo", void.class, false, boolean.class);
            // A Selector's chains are the device's inner chains; the selection is its active-chain parameter.
            selector = a.type("device.chainSelector");
            Class<?> selectionType = a.type("parameter.chainSelector"), valueType = a.type("model.valueProperty");
            Class<?> integer = a.type("model.integerValue");
            if (!target.isAssignableFrom(selectionType) || !integer.isAssignableFrom(valueType))
                throw new NoSuchMethodException("Inspected Selector index ancestry differs.");
            selection = a.exactMethod("device.chainSelector.parameter", selectionType, false);
            index = a.exactMethod("parameter.chainSelector.index", valueType, false);
            get = a.exactMethod("model.integerValue.get", int.class, false);
            set = a.exactMethod("model.integerValue.set", void.class, false, int.class);
            Class<?> chainSet = a.type("device.chainSet");
            chainContainer = a.type("device.chainContainer");
            drumChains = a.type("device.drumChains");
            Class<?> pad = a.type("device.drumPad"), slot = a.type("device.padSlot");
            if (!chainSet.isAssignableFrom(chainContainer)
                    || !chainSet.isAssignableFrom(drumChains)
                    || !slot.isAssignableFrom(pad))
                throw new NoSuchMethodException("Inspected chain container ancestry differs.");
            addChain = a.exactMethod("device.chainContainer.addChain", void.class, false);
            padByKey = a.exactMethod("device.drumChains.padByKey", pad, false, int.class);
            padChain = a.exactMethod("device.padSlot.chain", a.type("chain.channel"), false);
            createPadChain = a.exactMethod("device.padSlot.createEmptyChain", void.class, false);
        }

        private Method parameter(String key, String type, Class<?> target) throws ReflectiveOperationException {
            Class<?> returns = a.type(type);
            if (!target.isAssignableFrom(returns))
                throw new NoSuchMethodException("Inspected " + type + " is not a parameter.target.");
            return a.exactMethod(key, returns, false);
        }
    }

    private static List<Object> copy(Object list) {
        return list == null ? new ArrayList<>() : new ArrayList<>((List<?>) list);
    }
}
