package dev.bitwigagent;

import com.google.gson.JsonObject;
import java.lang.reflect.Method;
import java.util.*;
import static dev.bitwigagent.HostServices.failure;
import static dev.bitwigagent.ModulatorSnapshotReader.Field.*;

/** Audited 6.1.3 read bindings only; runtime, discovery and QEE availability remain separate. */
final class Bitwig613ModulatorAdapter implements ModulatorAdapter, ModulatorSnapshotReader.Access {
    private static final String D = "com.bitwig.flt.document.core.master.device.";
    private static final String R = "com.bitwig.ramona.core.";
    private final Bitwig613Runtime.Access bindings;
    private final EnumMap<ModulatorSnapshotReader.Field, Method> readers = new EnumMap<>(ModulatorSnapshotReader.Field.class);
    private final EnumMap<ModulatorSnapshotReader.Kind, Class<?>> kinds = new EnumMap<>(ModulatorSnapshotReader.Kind.class);
    private Method index;
    private Class<?> panelClass;
    private Bitwig613StringPanelAccess strings;
    private Bitwig613ReadContextAccess contextAccess;
    private ModulatorSnapshotReader reader;
    private String discoveryError, stringError;

    Bitwig613ModulatorAdapter(Bitwig613Runtime.Access bindings) {
        this.bindings = bindings;
        if (bindings.gateError() != null) {
            discoveryError = stringError = "Exact runtime gate is unavailable.";
            return;
        }
        try {
            bindDiscovery();
            NativeDeviceTarget target = new NativeDeviceTarget(new Bitwig613DeviceTargetAccess(bindings));
            contextAccess = new Bitwig613ReadContextAccess(bindings);
            reader = new ModulatorSnapshotReader(this, target, new NativeReadContext(contextAccess));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) { discoveryError = diagnostic(error); }
        try {
            panelClass = bindings.type("QEE");
            strings = new Bitwig613StringPanelAccess(bindings);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) { stringError = diagnostic(error); }
    }

    private void bindDiscovery() throws ReflectiveOperationException {
        Class<?> collection = kind(ModulatorSnapshotReader.Kind.COLLECTION, D + "TQi");
        Class<?> modulator = kind(ModulatorSnapshotReader.Kind.MODULATOR, D + "KXw");
        Class<?> contents = kind(ModulatorSnapshotReader.Kind.CONTENTS, D + "Xo");
        bindings.requireModelThreadAvailable();
        Class<?> model = kind(ModulatorSnapshotReader.Kind.MODEL, R + "xKS");
        Class<?> identified = kind(ModulatorSnapshotReader.Kind.IDENTIFIED, "com.bitwig.flt.document.core.iface.hCB");
        ancestry(collection, D + "uAG", D + "sF", D + "jhe", "bKp", "q3", "DRK", "pbT", R + "xKS", R + "fJC");
        ancestry(modulator, D + "JY1", D + "urR", D + "PaU", D + "AA", D + "QKj", "bKp", "q3", "DRK", "pbT", R + "xKS", R + "fJC");
        ancestry(contents, D + "hha", D + "sIL", D + "Fpk", D + "JkF", D + "Ca1", "bKp", "q3", "DRK", "pbT", R + "xKS", R + "fJC");
        if (!identified.isInterface() || !Arrays.equals(identified.getInterfaces(), new Class<?>[]{bindings.type(R + "J3L")}))
            throw new NoSuchMethodException("Inspected direct identifier interface differs.");
        bind(COLLECTION, bindings.type(D + "Y2N"), "nmR", collection, D + "PI");
        bind(COUNT, collection, "lUH", int.class, D + "jhe");
        index = exact(collection, "Dvd", modulator, D + "uAG", int.class);
        bind(PARENT, model, "M_", model, R + "xKS");
        bind(COLLECTION_OWNER, modulator, "BXY", collection, D + "KXw");
        bind(CONTENTS_OWNER, contents, "eCM", modulator, D + "Xo");
        bind(ID, modulator, "FJH", UUID.class, D + "QKj");
        bind(NAME, modulator, "Ynu", String.class, D + "QKj");
        bind(CUSTOM_NAME, modulator, "JtS", String.class, D + "QKj");
        bind(CONTENTS, modulator, "ioq", contents, D + "JY1");
        bind(TYPE_ID, contents, "JQk", UUID.class, D + "Fpk");
        bind(CHILDREN, contents, "cHs", List.class, D + "Ca1");
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
        result.addProperty("adapter", "bitwig_6_1_3_primary_modulators_read_only");
        result.addProperty("hostVersion", bindings.hostVersion()); result.addProperty("expectedVersion", Bitwig613Runtime.VERSION);
        result.addProperty("expectedSha256", Bitwig613Runtime.SHA256); result.addProperty("actualSha256", bindings.actualHash());
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
