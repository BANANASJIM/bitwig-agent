package dev.bitwigagent.nativehost;

import static dev.bitwigagent.device.ModulatorSnapshotReader.Field.*;

import com.google.gson.JsonObject;
import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.device.ModulatorAdapter;
import dev.bitwigagent.device.ModulatorSnapshotReader;
import dev.bitwigagent.host.DeviceSources;
import dev.bitwigagent.host.NativeReadContext;
import java.lang.reflect.Method;
import java.util.*;

/** Audited read bindings only; runtime, discovery and string-panel availability remain separate. */
public final class NativeModulatorAdapter implements ModulatorAdapter, ModulatorSnapshotReader.Access {
    private final NativeHost.Access bindings;
    private final EnumMap<ModulatorSnapshotReader.Field, Method> readers =
            new EnumMap<>(ModulatorSnapshotReader.Field.class);
    private final EnumMap<ModulatorSnapshotReader.Kind, Class<?>> kinds =
            new EnumMap<>(ModulatorSnapshotReader.Kind.class);
    private Method index;
    private Class<?> panelClass;
    private NativeStringPanelAccess strings;
    private NativeReadContextAccess contextAccess;
    private ModulatorSnapshotReader reader;
    private String discoveryError;

    public NativeModulatorAdapter(NativeHost.Access bindings) {
        this.bindings = bindings;
        if (bindings.gateError() != null) {
            discoveryError = "Exact runtime gate is unavailable.";
            return;
        }
        try {
            bindDiscovery();
            DeviceSources.Native target = new DeviceSources.Native(new NativeDeviceTargetAccess(bindings));
            contextAccess = new NativeReadContextAccess(bindings);
            reader = new ModulatorSnapshotReader(this, target, new NativeReadContext(contextAccess));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            discoveryError = diagnostic(error);
        }
        try {
            panelClass = bindings.type("panel.string");
            strings = new NativeStringPanelAccess(bindings);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            strings = null;
        }
    }

    private void bindDiscovery() throws ReflectiveOperationException {
        Class<?> collection = kind(ModulatorSnapshotReader.Kind.COLLECTION, "modulator.collection");
        Class<?> modulator = kind(ModulatorSnapshotReader.Kind.MODULATOR, "modulator");
        Class<?> contents = kind(ModulatorSnapshotReader.Kind.CONTENTS, "modulator.content");
        bindings.requireModelThreadAvailable();
        Class<?> model = kind(ModulatorSnapshotReader.Kind.MODEL, "model.node");
        Class<?> identified = kind(ModulatorSnapshotReader.Kind.IDENTIFIED, "document.identified");
        String[] base = {
            "model.element", "model.elementBase", "model.observableBase", "model.nodeBase", "model.node", "model.object"
        };
        ancestry(collection, "modulator.collectionBase", "device.componentList", "device.listBase");
        ancestry(bindings.type("device.listBase"), base);
        ancestry(
                modulator,
                "modulator.base",
                "device.ownedComponent",
                "device.componentBase",
                "device.component",
                "device.named");
        ancestry(bindings.type("device.named"), base);
        ancestry(
                contents,
                "modulator.contentsBase",
                "device.contents",
                "device.typed",
                "device.contentsList",
                "device.parentNode");
        ancestry(bindings.type("device.parentNode"), base);
        if (!identified.isInterface()
                || !Arrays.equals(identified.getInterfaces(), new Class<?>[] {bindings.type("model.selectable")}))
            throw new NoSuchMethodException("Inspected direct identifier interface differs.");
        bind(COLLECTION, "device.modulators", collection, "device.element");
        bind(COUNT, "modulator.collection.count", int.class, "device.listBase");
        index = exact("modulator.collection.get", modulator, "modulator.collectionBase", int.class);
        bind(PARENT, "model.node.parent", model, "model.node");
        bind(COLLECTION_OWNER, "modulator.owner", collection, "modulator");
        bind(CONTENTS_OWNER, "modulator.content.owner", modulator, "modulator.content");
        bind(ID, "modulator.id", UUID.class, "device.named");
        bind(NAME, "modulator.name", String.class, "device.named");
        bind(CUSTOM_NAME, "modulator.customName", String.class, "device.named");
        bind(CONTENTS, "modulator.contents", contents, "modulator.base");
        bind(TYPE_ID, "modulator.content.typeId", UUID.class, "device.typed");
        bind(CHILDREN, "modulator.content.children", List.class, "device.parentNode");
        bind(PARAMETER_ID, "document.identified.id", String.class, "document.identified");
    }

    private Class<?> kind(ModulatorSnapshotReader.Kind kind, String name) throws ClassNotFoundException {
        Class<?> type = bindings.type(name);
        kinds.put(kind, type);
        return type;
    }

    private void ancestry(Class<?> child, String... parents) throws ClassNotFoundException, NoSuchMethodException {
        for (String name : parents) {
            Class<?> parent = bindings.type(name);
            if (child.getSuperclass() != parent)
                throw new NoSuchMethodException("Inspected modulator ancestry differs.");
            child = parent;
        }
    }

    private Method exact(String key, Class<?> returns, String declaring, Class<?>... arguments)
            throws ReflectiveOperationException {
        Method method = bindings.exactMethod(key, returns, false, arguments);
        if (method.getDeclaringClass() != bindings.type(declaring))
            throw new NoSuchMethodException("Inspected modulator getter declaring class differs.");
        return method;
    }

    private void bind(ModulatorSnapshotReader.Field field, String key, Class<?> returns, String declaring)
            throws ReflectiveOperationException {
        readers.put(field, exact(key, returns, declaring));
    }

    @Override
    public void execute(Runnable task) {
        bindings.execute(task);
    }

    @Override
    public JsonObject list(DeviceSources.Source source, int offset, int limit, Runnable check) {
        requireDiscovery();
        return reader.list(source, offset, limit, check);
    }

    @Override
    public JsonObject parameter(DeviceSources.Source source, UUID modulatorId, String parameterId, Runnable check) {
        requireDiscovery();
        return reader.parameter(source, modulatorId, parameterId, check);
    }

    private void requireDiscovery() {
        bindings.requireAvailable();
        if (reader == null || discoveryError != null)
            throw Errors.notApplied(
                    Reason.NATIVE_UNAVAILABLE, "Primary modulator discovery binding unavailable: " + discoveryError);
    }

    @Override
    public Object read(ModulatorSnapshotReader.Field field, Object object) {
        if (field == PROJECT) return contextAccess.read(NativeReadContext.Field.PROJECT, object);
        if (field == VALUE) {
            if (strings == null)
                throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "Exact string-panel read binding unavailable.");
            return strings.value(object);
        }
        if (field == PARAMETER_ID && strings != null && strings.isPanel(object)) return strings.identifier(object);
        Method method = readers.get(field);
        if (method == null)
            throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "Required modulator getter is unavailable.");
        return bindings.invoke(method, object);
    }

    @Override
    public Object modulator(Object collection, int position) {
        return bindings.invoke(index, collection, position);
    }

    @Override
    public boolean is(ModulatorSnapshotReader.Kind kind, Object object) {
        if (kind == ModulatorSnapshotReader.Kind.STRING_PANEL) return object != null && object.getClass() == panelClass;
        Class<?> type = kinds.get(kind);
        if (type == null || object == null) return false;
        if (kind == ModulatorSnapshotReader.Kind.COLLECTION
                || kind == ModulatorSnapshotReader.Kind.MODULATOR
                || kind == ModulatorSnapshotReader.Kind.CONTENTS) return object.getClass() == type;
        return type.isInstance(object);
    }

    @Override
    public boolean stringsAvailable() {
        return strings != null;
    }

    @Override
    public void requireModelThread(Object object) {
        bindings.requireModelThread(object);
    }

    private static String diagnostic(Throwable error) {
        return error.getClass().getSimpleName() + ": " + error.getMessage();
    }
}
