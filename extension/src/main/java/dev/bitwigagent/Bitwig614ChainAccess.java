package dev.bitwigagent;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.*;

/**
 * Exact 6.1.4 descriptors for chains and the devices inside them, native-verified on the test host (P2-C spike).
 * Chains: {@code xlG.Wbp()} is an {@code RHX} for Instrument/FX Layer ({@code Dys}), the Selectors ({@code CHR}
 * subclasses) and Drum Machine ({@code liP}, pads with content only); {@code RHX.MIO()} lists its chains
 * ({@code zTz}), whose {@code cXR.kFU()} is the controller API channelId. A chain's devices are
 * {@code xJ.KAS().HL()}. Parameters are the direct parameters the public {@code DeviceProxy} reads and writes:
 * {@code jsd.MRY("CONTENTS/<id>")}, {@code FFu()}/{@code FzB(double)}, display {@code aLw.qFI()}. Edits use the
 * model operations behind the public insertion points and {@code deleteObject}: a
 * {@code DeviceChainInsertionPoint} with a file payload ({@code GRZ.doInsertFile}) or a device payload without the
 * copy modifier ({@code GRZ.doInsertDevices} for a move), and the document's delete transaction
 * ({@code BitwigStudioHost.doDeleteObjects}). Each is one Undo step.
 */
final class Bitwig614ChainAccess implements NativeChains.Access {
    private static final String M = "com.bitwig.flt.document.core.master.", D = M + "device.";
    private static final String I = "com.bitwig.flt.document.core.iface.";
    private static final String L = "com.bitwig.flt.library.metadata.";
    /**
     * The controller API's ids for {@code device_type} ({@code RamonaEnumValueDefinition.RAMONA_ENUM_VALUE_IDS},
     * package-private in the inspected JAR); an unlisted value is reported by its native id, as the host does.
     */
    private static final Map<String, String> TYPES = Map.of(
            "audio_to_audio", "audio-effect",
            "note_to_audio", "instrument",
            "note_to_note", "note-effect",
            "audio_to_note", "note-detector");

    private final Bitwig614Runtime.Access a;
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

    Bitwig614ChainAccess(Bitwig614Runtime.Access a) throws ReflectiveOperationException {
        this.a = a;
        roots = new DeviceSources.Native(new Bitwig614DeviceTargetAccess(a));
        Class<?> device = a.type(D + "xlG"), jsd = a.type("jsd");
        if (!jsd.isAssignableFrom(device)) throw new NoSuchMethodException("Inspected xlG is not a jsd.");
        container = a.type(D + "RHX");
        chainsOf = a.exactMethod(device, "Wbp", a.type(D + "zZo"), false);
        Class<?> chainType = a.type(D + "xJ"), listType = a.type(D + "ISf");
        chainList = a.exactMethod(chainType, "KAS", listType, false);
        chainDevices = a.exactMethod(a.type(D + "IPO"), "HL", List.class, false);
        listed = a.exactMethod(container, "MIO", List.class, false);
        chainId = a.exactMethod(a.type(M + "cXR"), "kFU", UUID.class, false);
        Class<?> channel = a.type(I + "RYd");
        Class<?> text = a.type("com.bitwig.ramona.core.ESe"), integer = a.type("com.bitwig.ramona.core.SYo");
        chainName = a.exactMethod(channel, "z3I", text, false);
        chainIndex = a.exactMethod(channel, "rGR", integer, false);
        value = a.exactMethod(a.type("com.bitwig.ramona.core.Dgc"), "getValue", Object.class, false);
        if (!chainType.isAssignableFrom(a.type(D + "zTz")) || !channel.isAssignableFrom(a.type(D + "zTz")))
            throw new NoSuchMethodException("Inspected zTz is not a channel chain.");
        deviceName = a.exactMethod(jsd, "Z1C", text, false);
        Class<?> enumAttribute = a.type("com.bitwig.ramona.core.sQv");
        deviceType = a.exactMethod(jsd, "ifN", enumAttribute, false);
        enumId = a.exactMethod(a.type("com.bitwig.ramona.core.Vzt"), "MRY", String.class, false);
        parameterType = a.type("v3U");
        enabled = a.exactMethod(jsd, "wZs", a.type("BhF"), false);
        parameterIds = a.exactMethod(jsd, "qm", String[].class, false);
        parameter = a.exactMethod(jsd, "MRY", parameterType, false, String.class);
        parameterName = a.exactMethod(parameterType, "fFs", String.class, false);
        read = a.exactMethod(parameterType, "FFu", double.class, false);
        write = a.exactMethod(parameterType, "FzB", void.class, false, double.class);
        writable = a.exactMethod(parameterType, "d3", boolean.class, false);
        displayed = a.type("aLw");
        display = a.exactMethod(displayed, "qFI", String.class, false);
        // Delete: the document's own transaction, as BitwigStudioHost.doDeleteObjects runs it.
        Class<?> doc = a.type(I + "pHd");
        document = a.exactMethod(jsd, "apV", doc, false);
        begin = a.exactMethod(doc, "cfM", void.class, false, String.class, boolean.class);
        delete = a.exactMethod(doc, "MRY", void.class, false, Collection.class);
        end = a.exactMethod(doc, "cfM", void.class, false, boolean.class);
        // Select: DeviceProxy.selectInEditor, on the track's device selection.
        Class<?> model = a.type("com.bitwig.ramona.core.OLR");
        aliasedOwner = a.type("iKU");
        ancestor = a.exactMethod(jsd, "IwV", Object.class, false, Class.class);
        Class<?> selections = a.type("iFO"), deviceSelectionType = a.type("EqN");
        deviceSelection = a.exactMethod(aliasedOwner, "fFM", selections, false);
        selection = a.exactMethod(selections, "wGc", deviceSelectionType, false);
        select = a.exactMethod(deviceSelectionType, "UNj", void.class, false, model);
        // Insert and move: GRZ.doInsert on a DeviceChainInsertionPoint, as the public insertion points do.
        Class<?> faU = a.type("FAu"), handle = a.type("ICh"), payload = a.type("qIC"), modifiers = a.type("XuI");
        Class<?> base = a.type("uGG"), copyKind = a.type("pHE"), listener = a.type("com.bitwig.base.async.lBQ");
        Class<?> insertion = a.type("com.bitwig.flt.document.core.iface.clipboard.device.DeviceChainInsertionPoint");
        if (!faU.isAssignableFrom(listType) || !base.isAssignableFrom(insertion))
            throw new NoSuchMethodException("Inspected device chain insertion point ancestry differs.");
        point = a.exactConstructor(insertion, faU, int.class, handle);
        insertAt = a.exactMethod(base, "cfM", boolean.class, false, payload, modifiers, listener);
        noModifiers = a.exactConstant(modifiers, "cfM", modifiers);
        copy = a.exactConstant(copyKind, "MRY", copyKind);
        payloadOf = a.exactMethod(modifiers, "cfM", modifiers, false, copyKind, boolean.class);
        copyModifier = a.invoke(payloadOf, noModifiers, copy, true);
        Class<?> files = a.type("com.bitwig.base.io.z2y");
        file = a.exactConstructor(files, File.class);
        Class<?> fileInsertion = a.type("IkS");
        if (!payload.isAssignableFrom(fileInsertion)) throw new NoSuchMethodException("Inspected IkS is not a qIC.");
        filePayload = a.exactConstructor(fileInsertion, files);
        Class<?> devices = a.type("com.bitwig.flt.document.core.iface.clipboard.device.Uev");
        if (!payload.isAssignableFrom(devices)) throw new NoSuchMethodException("Inspected Uev is not a qIC.");
        devicesPayload = a.exactMethod(devices, "cfM", devices, true, List.class);
        // Ids to files: GRZ.insertBitwigDevice/insertVST2Device/insertVST3Device/insertCLAPDevice.
        Class<?> app = a.type("com.bitwig.flt.app.CHR");
        application = a.exactMethod(app, "Grk", app, true);
        applicationResource = a.exactMethod(app, "cfM", File.class, false, UUID.class);
        Class<?> readers = a.type(L + "reader.BZG");
        Class<?> vst2Reader = a.type(L + "reader.RKg"), vst3Reader = a.type(L + "reader.z2y");
        Class<?> clapReader = a.type(L + "reader.oRV");
        vst2 = a.exactMethod(readers, "cfM", vst2Reader, true);
        vst3 = a.exactMethod(readers, "MRY", vst3Reader, true);
        clap = a.exactMethod(readers, "Hnt", clapReader, true);
        vst2Plugins = a.exactMethod(vst2Reader, "MRY", List.class, false, int.class);
        vst3Plugins = a.exactMethod(vst3Reader, "MRY", List.class, false, String.class);
        clapPlugins = a.exactMethod(clapReader, "MRY", List.class, false, String.class);
        pluginFile = a.exactMethod(a.type(L + "master.cXR"), "ik_", files, false);
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
    public void select(Object device) {
        Object owner = a.invoke(ancestor, device, aliasedOwner);
        if (owner == null)
            throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "The device has no track whose selection can change.");
        a.invoke(select, a.invoke(selection, a.invoke(deviceSelection, owner)), device);
    }

    private static List<Object> copy(Object list) {
        return list == null ? new ArrayList<>() : new ArrayList<>((List<?>) list);
    }
}
