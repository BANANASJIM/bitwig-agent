package dev.bitwigagent;

import com.google.gson.JsonObject;
import java.lang.reflect.Method;
import java.util.*;
import static dev.bitwigagent.HostServices.failure;
import static dev.bitwigagent.ModulatorSnapshotReader.Field.*;

/** Audited 6.1.4 read bindings only; runtime, discovery and string-panel availability remain separate. */
final class Bitwig614ModulatorAdapter implements ModulatorAdapter, ModulatorSnapshotReader.Access {
    private static final String D = "com.bitwig.flt.document.core.master.device.";
    private static final String R = "com.bitwig.ramona.core.";
    private final Bitwig614Runtime.Access bindings;
    private final EnumMap<ModulatorSnapshotReader.Field, Method> readers = new EnumMap<>(ModulatorSnapshotReader.Field.class);
    private final EnumMap<ModulatorSnapshotReader.Kind, Class<?>> kinds = new EnumMap<>(ModulatorSnapshotReader.Kind.class);
    private Method index;
    private Class<?> panelClass;
    private Bitwig614StringPanelAccess strings;
    private Bitwig614ReadContextAccess contextAccess;
    private ModulatorSnapshotReader reader;
    private String discoveryError, stringError;

    Bitwig614ModulatorAdapter(Bitwig614Runtime.Access bindings) {
        this.bindings = bindings;
        if (bindings.gateError() != null) {
            discoveryError = stringError = "Exact runtime gate is unavailable.";
            return;
        }
        try {
            bindDiscovery();
            NativeDeviceTarget target = new NativeDeviceTarget(new Bitwig614DeviceTargetAccess(bindings));
            contextAccess = new Bitwig614ReadContextAccess(bindings);
            reader = new ModulatorSnapshotReader(this, target, new NativeReadContext(contextAccess));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) { discoveryError = diagnostic(error); }
        try {
            panelClass = bindings.type("xMn");
            strings = new Bitwig614StringPanelAccess(bindings);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) { stringError = diagnostic(error); }
    }

    private void bindDiscovery() throws ReflectiveOperationException {
        Class<?> collection = kind(ModulatorSnapshotReader.Kind.COLLECTION, D + "nZA");
        Class<?> modulator = kind(ModulatorSnapshotReader.Kind.MODULATOR, D + "tad");
        Class<?> contents = kind(ModulatorSnapshotReader.Kind.CONTENTS, D + "CcL");
        bindings.requireModelThreadAvailable();
        Class<?> model = kind(ModulatorSnapshotReader.Kind.MODEL, R + "oDc");
        Class<?> identified = kind(ModulatorSnapshotReader.Kind.IDENTIFIED, "com.bitwig.flt.document.core.iface.NN");
        ancestry(collection, D + "vz3", D + "Vzt", D + "BZG", "K2G", "to", "sw3", "erm", R + "oDc", R + "AVL");
        ancestry(modulator, D + "eaL", D + "JXO", D + "gJi", D + "hgq", D + "Dgc", "K2G", "to", "sw3", "erm", R + "oDc", R + "AVL");
        ancestry(contents, D + "f3y", D + "RYd", D + "iYN", D + "BVs", D + "i2s", "K2G", "to", "sw3", "erm", R + "oDc", R + "AVL");
        if (!identified.isInterface() || !Arrays.equals(identified.getInterfaces(), new Class<?>[]{bindings.type(R + "OLR")}))
            throw new NoSuchMethodException("Inspected direct identifier interface differs.");
        bind(COLLECTION, bindings.type(D + "xlG"), "k2s", collection, D + "FDA");
        bind(COUNT, collection, "lYc", int.class, D + "BZG");
        index = exact(collection, "hUR", modulator, D + "vz3", int.class);
        bind(PARENT, model, "M_", model, R + "oDc");
        bind(COLLECTION_OWNER, modulator, "BBT", collection, D + "tad");
        bind(CONTENTS_OWNER, contents, "tp2", modulator, D + "CcL");
        bind(ID, modulator, "kFU", UUID.class, D + "Dgc");
        bind(NAME, modulator, "vpw", String.class, D + "Dgc");
        bind(CUSTOM_NAME, modulator, "Ai", String.class, D + "Dgc");
        bind(CONTENTS, modulator, "QOl", contents, D + "eaL");
        bind(TYPE_ID, contents, "UNj", UUID.class, D + "iYN");
        bind(CHILDREN, contents, "SQN", List.class, D + "i2s");
        bind(PARAMETER_ID, identified, "aA_", String.class, identified.getName());
    }
    private Class<?> kind(ModulatorSnapshotReader.Kind kind, String name) throws ClassNotFoundException {
        Class<?> type = bindings.type(name); kinds.put(kind, type); return type;
    }
    private void ancestry(Class<?> child, String... parents) throws ClassNotFoundException, NoSuchMethodException {
        for (String name : parents) {
            Class<?> parent = bindings.type(name);
            if (child.getSuperclass() != parent) throw new NoSuchMethodException("Inspected modulator ancestry differs.");
            child = parent;
        }
    }
    private Method exact(Class<?> owner, String name, Class<?> returns, String declaring, Class<?>... arguments)
            throws ReflectiveOperationException {
        Method method = bindings.exactMethod(owner, name, returns, false, arguments);
        if (method.getDeclaringClass() != bindings.type(declaring))
            throw new NoSuchMethodException("Inspected modulator getter declaring class differs.");
        return method;
    }
    private void bind(ModulatorSnapshotReader.Field field, Class<?> owner, String name, Class<?> returns, String declaring)
            throws ReflectiveOperationException {
        readers.put(field, exact(owner, name, returns, declaring));
    }
    @Override public JsonObject capabilities() {
        JsonObject result = HostServices.observed();
        result.addProperty("adapter", "bitwig_6_1_4_primary_modulators_read_only");
        result.addProperty("hostVersion", bindings.hostVersion()); result.addProperty("expectedVersion", Bitwig614Runtime.VERSION);
        result.addProperty("expectedSha256", Bitwig614Runtime.SHA256); result.addProperty("actualSha256", bindings.actualHash());
        result.add("runtimeGate", capability(bindings.gateError()));
        result.add("discovery", capability(discoveryError)); result.add("stringPanelRead", capability(stringError));
        result.addProperty("available", bindings.gateError() == null && discoveryError == null);
        result.addProperty("parameterReadAvailable", bindings.gateError() == null && discoveryError == null && stringError == null);
        result.addProperty("writeAvailable", false); result.addProperty("nativeMutation", false);
        result.addProperty("nativeVerification", "not_established_by_binding");
        result.addProperty("scope", "primary_modulators_on_root_device");
        JsonObject limits = new JsonObject();
        limits.addProperty("primaryModulators", ModulatorSnapshotReader.MAX_MODULATORS);
        limits.addProperty("directChildrenPerContents", ModulatorSnapshotReader.MAX_CHILDREN);
        limits.addProperty("directChildVisitsIncludingRevalidation", ModulatorSnapshotReader.MAX_EXAMINED);
        limits.addProperty("textUtf16Units", ModulatorSnapshotReader.MAX_TEXT);
        result.add("limits", limits);
        return result;
    }
    private static JsonObject capability(String error) {
        JsonObject result = new JsonObject(); result.addProperty("available", error == null); result.addProperty("reason", error); return result;
    }
    @Override public void execute(Runnable task) { bindings.execute(task); }
    @Override public JsonObject list(Object proxy, UUID trackId, int offset, int limit, Runnable check) {
        requireDiscovery(); return reader.list(proxy, trackId, offset, limit, check);
    }
    @Override public JsonObject parameter(Object proxy, UUID trackId, UUID modulatorId, String parameterId, Runnable check) {
        requireDiscovery(); return reader.parameter(proxy, trackId, modulatorId, parameterId, check);
    }
    private void requireDiscovery() {
        bindings.requireAvailable();
        if (reader == null || discoveryError != null) throw failure("modulator_unavailable", "Primary modulator discovery binding unavailable: " + discoveryError);
    }
    @Override public Object read(ModulatorSnapshotReader.Field field, Object object) {
        if (field == PROJECT) return contextAccess.read(NativeReadContext.Field.PROJECT, object);
        if (field == VALUE) {
            if (strings == null) throw failure("modulator_unavailable", "Exact string-panel read binding unavailable.");
            return strings.value(object);
        }
        if (field == PARAMETER_ID && strings != null && strings.isPanel(object)) return strings.identifier(object);
        Method method = readers.get(field);
        if (method == null) throw failure("modulator_unavailable", "Required modulator getter is unavailable.");
        return bindings.invoke(method, object);
    }
    @Override public Object modulator(Object collection, int position) { return bindings.invoke(index, collection, position); }
    @Override public boolean is(ModulatorSnapshotReader.Kind kind, Object object) {
        if (kind == ModulatorSnapshotReader.Kind.STRING_PANEL) return object != null && object.getClass() == panelClass;
        Class<?> type = kinds.get(kind);
        if (type == null || object == null) return false;
        if (kind == ModulatorSnapshotReader.Kind.COLLECTION || kind == ModulatorSnapshotReader.Kind.MODULATOR
                || kind == ModulatorSnapshotReader.Kind.CONTENTS) return object.getClass() == type;
        return type.isInstance(object);
    }
    @Override public boolean stringsAvailable() { return strings != null; }
    @Override public void requireModelThread(Object object) { bindings.requireModelThread(object); }
    private static String diagnostic(Throwable error) { return error.getClass().getSimpleName() + ": " + error.getMessage(); }
}
