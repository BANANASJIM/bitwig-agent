package dev.bitwigagent.nativehost;

import com.google.gson.JsonObject;
import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.device.ModulatorEditor;
import dev.bitwigagent.device.ModulatorSnapshotReader;
import dev.bitwigagent.grid.GridNames;
import dev.bitwigagent.grid.GridParameterContext;
import dev.bitwigagent.grid.GridSnapshotReader;
import dev.bitwigagent.grid.GridTopologyContext;
import dev.bitwigagent.host.DeviceSources;
import java.io.File;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.Consumer;

/**
 * Exact public descriptors for device-modulator edits. Each native operation is the one Bitwig itself uses:
 * the modulator browser's insertion point (as in Bitwig's own TestModulatorGrid), modulation source add/clear (each
 * wrapped in a named native undo transaction), the auxiliary device grid's delete (shared with Grid module delete)
 * and the external-controller raw write already used for Grid parameters.
 */
public final class NativeModulatorEditAccess implements ModulatorEditor.Access {
    private final NativeHost.Access bindings;
    private final DeviceSources.Native targets;
    private final GridSnapshotReader grid;
    private final GridParameterContext parameterContexts;
    private final GridTopologyContext topologyContexts;
    private final NativeGridParameterWriter rawWriter;
    private final Class<?> modulatorType,
            moduleType,
            deviceType,
            sourceType,
            targetType,
            mappingType,
            insertionType,
            listenerType;
    /**
     * The source class itself and its labelled subclass (a source carrying a label panel); neither overrides the
     * mapping operations.
     */
    private final Set<Class<?>> sourceClasses;

    private final Method collection,
            count,
            index,
            id,
            name,
            contents,
            typeId,
            children,
            x,
            y,
            height,
            identifier,
            owner,
            path;
    private final Method mappings,
            mappingTarget,
            targetParameter,
            amount,
            transfer,
            enabled,
            polyphonic,
            scaled,
            modulationRange,
            range;
    private final Method accepts, skipped, map, unmap, delete, insertionPoint, insertFile, singleton, resource, catalog;
    private final Method entryId, entryName, entryKind;
    private final Object noModifiers;
    private final String completed;
    private final Class<?> transferType;
    String structureError, mapError, insertError;

    public NativeModulatorEditAccess(NativeHost.Access bindings, NativeGridAdapter gridAccess)
            throws ReflectiveOperationException {
        this.bindings = bindings;
        targets = new DeviceSources.Native(new NativeDeviceTargetAccess(bindings));
        grid = new GridSnapshotReader(gridAccess);
        parameterContexts = new GridParameterContext(new NativeGridParameterContext(bindings, null, null, null, null));
        topologyContexts = new GridTopologyContext(new NativeGridTopologyContext(bindings));
        rawWriter = new NativeGridParameterWriter(bindings);
        bindings.requireModelThreadAvailable();
        Class<?> collectionType = bindings.type("modulator.collection"),
                contentsType = bindings.type("modulator.content");
        modulatorType = bindings.type("modulator");
        moduleType = bindings.type("grid.module");
        deviceType = bindings.type("device");
        sourceType = bindings.type("modulation.source");
        targetType = bindings.type("parameter");
        mappingType = bindings.type("modulation.mapping");
        Class<?> source = bindings.type("modulation.sourceBase"),
                mapping = bindings.type("modulation.mappingBase"),
                reference = bindings.type("parameter.reference");
        Class<?> labelled = bindings.type("modulation.labelledSource");
        if (labelled.getSuperclass() != bindings.type("modulation.panelSource")
                || labelled.getSuperclass().getSuperclass() != sourceType)
            throw new NoSuchMethodException("Inspected labelled modulation source ancestry differs.");
        sourceClasses = Set.of(sourceType, labelled);
        transferType = bindings.type("modulation.transfer");
        if (!transferType.isEnum() || transferType.getEnumConstants().length != ModulatorEditor.TRANSFERS.size())
            throw new NoSuchMethodException("Inspected transfer-function enum differs.");
        for (Object constant : transferType.getEnumConstants())
            if (!ModulatorEditor.TRANSFERS.contains(((Enum<?>) constant).name().toLowerCase(Locale.ROOT)))
                throw new NoSuchMethodException("Inspected transfer-function constants differ.");
        if (!source.isAssignableFrom(sourceType) || !mapping.isAssignableFrom(mappingType))
            throw new NoSuchMethodException("Inspected modulation source/mapping ancestry differs.");
        collection = bindings.exactMethod("device.modulators", collectionType, false);
        count = bindings.exactMethod("modulator.collection.count", int.class, false);
        index = bindings.exactMethod("modulator.collection.get", modulatorType, false, int.class);
        id = bindings.exactMethod("modulator.id", UUID.class, false);
        name = bindings.exactMethod("modulator.name", String.class, false);
        contents = bindings.exactMethod("modulator.contents", contentsType, false);
        typeId = bindings.exactMethod("modulator.content.typeId", UUID.class, false);
        children = bindings.exactMethod("modulator.content.children", List.class, false);
        x = bindings.exactMethod("modulator.x", int.class, false);
        y = bindings.exactMethod("modulator.y", int.class, false);
        height = bindings.exactMethod("modulator.height", int.class, false);
        identifier = bindings.exactMethod("document.identified.id", String.class, false);
        owner = bindings.exactMethod("model.object.ancestor", Object.class, false, Class.class);
        path = bindings.exactMethod("model.node.path", String.class, false);
        mappings = bindings.exactMethod("modulation.sourceBase.mappings", List.class, false);
        mappingTarget = bindings.exactMethod("modulation.mappingBase.target", reference, false);
        targetParameter = bindings.exactMethod("parameter.reference.parameter", targetType, false);
        amount = bindings.exactMethod("modulation.mappingBase.amount", double.class, false);
        transfer = bindings.exactMethod("modulation.mappingBase.transfer", transferType, false);
        enabled = bindings.exactMethod("modulation.mappingBase.enabled", boolean.class, false);
        polyphonic = bindings.exactMethod("modulation.mappingBase.polyphonic", boolean.class, false);
        scaled = bindings.exactMethod("modulation.mappingBase.scaled", boolean.class, false);
        Class<?> rangeOwner = bindings.type("parameter.range");
        modulationRange = bindings.exactMethod("parameter.modulationRange", rangeOwner, false);
        range = bindings.exactMethod("parameter.range.span", double.class, false);
        accepts = bindings.exactMethod(
                "modulation.source.accepts", boolean.class, false, bindings.type("parameter.target"));
        skipped = bindings.exactMethod("model.element.skipped", boolean.class, false);
        // Public final wrappers: map opens a named model undo transaction around the source's mapping write; unmap is
        // "Clear modulation".
        map = bindings.exactMethod(
                "modulation.sourceBase.map",
                void.class,
                false,
                targetType,
                double.class,
                boolean.class,
                transferType,
                sourceType);
        unmap = bindings.exactMethod("modulation.sourceBase.unmap", void.class, false, targetType);
        if (!Modifier.isFinal(map.getModifiers()) || !Modifier.isFinal(unmap.getModifiers()))
            throw new NoSuchMethodException("Inspected public final modulation wrappers changed.");
        // A transaction "auxiliary_device_grid.operations.delete_modulators", shared with Grid module delete.
        delete = bindings.exactMethod("device.componentList.delete", void.class, false, List.class);
        Class<?> point = bindings.type("modulator.insertionPoint");
        insertionType = point;
        insertionPoint = bindings.exactMethod(
                "modulator.collection.insertionPoint",
                point,
                false,
                int.class,
                int.class,
                bindings.type("insertion.anchor"));
        listenerType = bindings.type("async.task");
        if (!listenerType.isInterface())
            throw new NoSuchMethodException("Inspected insertion listener is not an interface.");
        completed = bindings.name("async.task.completed");
        Class<?> modifiers = bindings.type("insertion.modifiers");
        insertFile = bindings.exactMethod(
                "insertion.point.insertFile", boolean.class, false, File.class, modifiers, listenerType);
        noModifiers = bindings.exactConstant("insertion.modifiers.none", modifiers);
        Class<?> app = bindings.type("app");
        singleton = bindings.exactMethod("app.singleton", app, true);
        resource = bindings.exactMethod("app.resource", File.class, false, UUID.class);
        catalog = bindings.exactMethod("catalog.entries", List.class, true);
        entryId = bindings.exactMethod("catalog.entry.id", UUID.class, false);
        entryName = bindings.exactMethod("catalog.entry.name", String.class, false);
        entryKind = bindings.exactMethod("catalog.entry.kind", bindings.type("catalog.kind"), false);
    }

    @Override
    public void documentThread() {
        bindings.documentThread();
    }

    @Override
    public void execute(Runnable task) {
        bindings.execute(task);
    }

    @Override
    public Object device(DeviceSources.Source source, Runnable check) {
        return targets.capture(source, check).device;
    }

    @Override
    public void guardDevice(DeviceSources.Source source, Object device, Runnable check) {
        DeviceSources.Native.Capture now = targets.capture(source, check);
        if (now.device != device)
            throw Errors.notApplied(Reason.TARGET_CHANGED, "The device changed during the operation.");
    }

    @Override
    public Object collection(Object device) {
        Object value = bindings.invoke(collection, device);
        if (value != null) bindings.requireModelThread(value);
        return value;
    }

    @Override
    public List<Object> modulators(Object collection) {
        int size = (Integer) bindings.invoke(count, collection);
        if (size < 0 || size > ModulatorSnapshotReader.MAX_MODULATORS)
            throw Errors.notApplied(Reason.OUT_OF_BOUNDS, "Primary modulator collection size is outside 0..4096.");
        List<Object> result = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            Object member = bindings.invoke(index, collection, i);
            if (member == null || member.getClass() != modulatorType)
                throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "Unknown primary modulator model.");
            bindings.requireModelThread(member);
            result.add(member);
        }
        return result;
    }

    @Override
    public UUID id(Object modulator) {
        return (UUID) bindings.invoke(id, modulator);
    }

    @Override
    public String name(Object modulator) {
        return text(bindings.invoke(name, modulator));
    }

    @Override
    public UUID typeId(Object modulator) {
        Object value = bindings.invoke(contents, modulator);
        return value == null ? null : (UUID) bindings.invoke(typeId, value);
    }

    @Override
    public int[] cell(Object modulator) {
        return new int[] {
            (Integer) bindings.invoke(x, modulator),
            (Integer) bindings.invoke(y, modulator),
            1,
            (Integer) bindings.invoke(height, modulator)
        };
    }

    @Override
    public List<Object> children(Object modulator) {
        Object value = bindings.invoke(contents, modulator);
        if (value == null) return null;
        Object list = bindings.invoke(children, value);
        if (!(list instanceof List<?>))
            throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "Modulator contents children are not a list.");
        List<Object> result = new ArrayList<>(((List<?>) list).size());
        for (Object child : (List<?>) list) {
            if (child != null) bindings.requireModelThread(child);
            result.add(child);
        }
        return result;
    }

    @Override
    public boolean isSource(Object child) {
        return child != null && sourceClasses.contains(child.getClass());
    }

    @Override
    public boolean isTarget(Object child) {
        return targetType.isInstance(child);
    }

    @Override
    public String identifier(Object child) {
        return child != null && identifier.getDeclaringClass().isInstance(child)
                ? text(bindings.invoke(identifier, child))
                : null;
    }

    @Override
    public Object owner(Object model, ModulatorEditor.Owner kind) {
        Class<?> type = kind == ModulatorEditor.Owner.MODULATOR
                ? modulatorType
                : kind == ModulatorEditor.Owner.MODULE ? moduleType : deviceType;
        return bindings.invoke(owner, model, type);
    }

    @Override
    public String path(Object model) {
        return text(bindings.invoke(path, model));
    }

    @Override
    public String kind(Object parameter) {
        GridSnapshotReader.Kind kind = grid.detachedKind(parameter);
        return kind == null ? null : kind.name().toLowerCase(Locale.ROOT);
    }

    @Override
    public JsonObject value(Object parameter, Runnable check, Collection<String> unsupported) {
        return grid.detachedParameter(parameter, check, unsupported);
    }

    @Override
    public String modelClass(Object model) {
        return model == null ? "null" : model.getClass().getName();
    }

    @Override
    public List<Object> mappings(Object source) {
        Object list = bindings.invoke(mappings, source);
        if (!(list instanceof List<?>))
            throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "Modulation mappings are not a list.");
        List<Object> result = new ArrayList<>();
        for (Object item : (List<?>) list) {
            if (item == null || item.getClass() != mappingType)
                throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "Unknown modulation mapping model.");
            result.add(item);
        }
        return result;
    }

    @Override
    public Object mappingTarget(Object mapping) {
        Object reference = bindings.invoke(mappingTarget, mapping);
        return reference == null ? null : bindings.invoke(targetParameter, reference);
    }

    @Override
    public double amount(Object mapping) {
        return (Double) bindings.invoke(amount, mapping);
    }

    @Override
    public String transfer(Object mapping) {
        Object value = bindings.invoke(transfer, mapping);
        if (value == null) return null;
        if (!transferType.isInstance(value))
            throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "Unknown transfer-function value.");
        return ((Enum<?>) value).name().toLowerCase(Locale.ROOT);
    }

    @Override
    public boolean enabled(Object mapping) {
        return (Boolean) bindings.invoke(enabled, mapping);
    }

    @Override
    public boolean polyphonic(Object mapping) {
        return (Boolean) bindings.invoke(polyphonic, mapping);
    }

    @Override
    public boolean scaled(Object mapping) {
        return (Boolean) bindings.invoke(scaled, mapping);
    }

    @Override
    public double range(Object target) {
        Object owner = bindings.invoke(modulationRange, target);
        if (owner == null)
            throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "Target has no modulation range descriptor.");
        return (Double) bindings.invoke(range, owner);
    }

    @Override
    public boolean canModulate(Object source, Object target) {
        return !Boolean.TRUE.equals(bindings.invoke(skipped, source))
                && Boolean.TRUE.equals(bindings.invoke(accepts, source, target));
    }

    @Override
    public Object[] gridParameter(DeviceSources.Source source, UUID moduleId, String parameterId, Runnable check) {
        GridSnapshotReader.ParameterBinding binding = grid.bindParameterIfPresent(source, moduleId, parameterId, check);
        if (binding == null) return null;
        grid.guardParameter(binding);
        return new Object[] {binding.graph, binding.module, binding.parameter};
    }

    @Override
    public String moduleId(Object module) {
        return grid.moduleId(module);
    }

    @Override
    public String moduleName(Object module) {
        String name = grid.moduleRefName(module, () -> {});
        return name == null ? "" : name;
    }

    @Override
    public DeviceSources.Native natives() {
        return targets;
    }

    @Override
    public void requireParameterWritable(
            Object device, Object container, Object owner, Object parameter, Runnable check) {
        GridSnapshotReader.Kind kind = grid.detachedKind(parameter);
        if (kind == null)
            throw Errors.notApplied(
                    Reason.UNSUPPORTED_SCOPE, "Only float, integer, enum and boolean controls are supported.");
        parameterContexts.requireWritable(device, container, owner, parameter, kind, null, check);
    }

    @Override
    public void requireStructureWritable(Object device, Object collection, List<Object> members, Runnable check) {
        topologyContexts.capture(device, collection, members, null, check);
    }

    @Override
    public ModulatorEditor.Type resolveType(UUID wantedId, String wantedName) {
        Object value = bindings.invoke(catalog, null);
        if (!(value instanceof List<?>))
            throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "Native catalog is not a list.");
        Map<String, ModulatorEditor.Type> byName = new LinkedHashMap<>();
        List<ModulatorEditor.Type> types = new ArrayList<>();
        for (Object entry : (List<?>) value) {
            if (!"MODULATOR".equals(String.valueOf(bindings.invoke(entryKind, entry)))) continue;
            ModulatorEditor.Type type = new ModulatorEditor.Type(
                    (UUID) bindings.invoke(entryId, entry), text(bindings.invoke(entryName, entry)));
            types.add(type);
            byName.putIfAbsent(type.name, type);
        }
        List<ModulatorEditor.Type> hits = wantedId != null
                ? types.stream().filter(type -> wantedId.equals(type.id)).collect(java.util.stream.Collectors.toList())
                : GridNames.resolve(types, type -> type.name, wantedName);
        if (hits.size() == 1) return hits.get(0);
        if (hits.size() > 1)
            throw Errors.notApplied(Reason.INVALID_PARAMS, "Modulator type name is ambiguous; pass typeId.");
        throw Errors.notApplied(
                Reason.TARGET_NOT_FOUND,
                "No modulator type " + (wantedId != null ? wantedId : "'" + wantedName + "'")
                        + (wantedName == null
                                ? ""
                                : "; nearest: " + GridNames.nearest(byName.keySet(), wantedName, GridNames.SUGGESTIONS))
                        + ". Use modulators.describe.");
    }

    @Override
    public Runnable prepareSet(Object parameter, String kind, double raw) {
        rawWriter.requireSupported(parameter, GridSnapshotReader.Kind.valueOf(kind.toUpperCase(Locale.ROOT)));
        return () -> rawWriter.setRaw(parameter, raw);
    }

    @Override
    public Runnable prepareMap(Object source, Object target, double value, boolean on, String function) {
        requireExact(source, target);
        Object constant = null;
        for (Object item : transferType.getEnumConstants())
            if (((Enum<?>) item).name().equalsIgnoreCase(function)) constant = item;
        if (constant == null) throw Errors.notApplied(Reason.INVALID_PARAMS, "Unknown transfer function.");
        Object chosen = constant;
        return () -> bindings.invoke(map, source, target, value, on, chosen, null);
    }

    @Override
    public Runnable prepareUnmap(Object source, Object target) {
        requireExact(source, target);
        return () -> bindings.invoke(unmap, source, target);
    }

    private void requireExact(Object source, Object target) {
        if (!isSource(source) || !targetType.isInstance(target))
            throw Errors.notApplied(
                    Reason.NATIVE_UNAVAILABLE,
                    "Mapping requires the inspected exact source class and a parameter target.");
    }

    @Override
    public Runnable prepareDelete(Object collection, Object modulator) {
        if (modulator == null || modulator.getClass() != modulatorType)
            throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "Not an inspected modulator model.");
        List<Object> selected = Collections.singletonList(modulator);
        return () -> bindings.invoke(delete, collection, selected);
    }

    @Override
    public Runnable prepareInsert(Object collection, UUID type, int column, int row, Consumer<String> settled) {
        Object app = bindings.invoke(singleton, null);
        if (app == null)
            throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "Native application singleton is unavailable.");
        Object file = bindings.invoke(resource, app, type);
        if (!(file instanceof File) || !((File) file).isFile())
            throw Errors.notApplied(
                    Reason.NATIVE_UNAVAILABLE, "Modulator resource is unavailable through the native resolver.");
        Object point = bindings.invoke(insertionPoint, collection, column, row, null);
        if (point == null || point.getClass() != insertionType)
            throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "Native insertion point differs.");
        Object listener = Proxy.newProxyInstance(
                listenerType.getClassLoader(), new Class<?>[] {listenerType}, new Listener(settled, completed));
        return () -> {
            Object accepted = bindings.invoke(insertFile, point, file, noModifiers, listener);
            // false: no native task; the listener has already been told why. Readback still decides.
            if (!Boolean.TRUE.equals(accepted)) settled.accept("insertion point refused the modulator file");
        };
    }

    /** Captures only the outcome; never throws into the native task or touches the model from its thread. */
    private static final class Listener implements InvocationHandler {
        private final Consumer<String> settled;
        private final String completed;

        Listener(Consumer<String> settled, String completed) {
            this.settled = settled;
            this.completed = completed;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            try {
                if (method.getDeclaringClass() == Object.class) {
                    if (method.getName().equals("equals")) return args != null && args.length == 1 && proxy == args[0];
                    if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                    return "ModulatorInsertListener";
                }
                Class<?>[] types = method.getParameterTypes();
                if (method.getName().equals(completed) && types.length == 0) settled.accept(null);
                else if (types.length == 1 && types[0] == Object.class) settled.accept(null);
                else if (types.length == 1 && types[0] == Exception.class)
                    settled.accept(
                            args[0] == null
                                    ? "native failure"
                                    : args[0].getClass().getSimpleName());
                else if (types.length == 1 && types[0] == String.class) settled.accept(String.valueOf(args[0]));
                else settled.accept("unexpected native callback " + method.getName());
            } catch (Throwable ignored) {
                /* the editor treats a missing settlement as a timeout/unknown */
            }
            return null;
        }
    }

    private static String text(Object value) {
        if (value != null && !(value instanceof String))
            throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "Native text has an unexpected type.");
        String text = (String) value;
        if (text != null && text.length() > ModulatorEditor.MAX_TEXT)
            throw Errors.notApplied(
                    Reason.OUT_OF_BOUNDS, "Native text exceeds 1024 UTF-16 units; values are never truncated.");
        return text;
    }
}
