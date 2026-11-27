package dev.bitwigagent.device;

import com.google.gson.*;
import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.HostOperation;
import dev.bitwigagent.core.Outcome;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.core.Refs;
import dev.bitwigagent.core.Results;
import dev.bitwigagent.core.RpcException;
import dev.bitwigagent.core.ValueInput;
import dev.bitwigagent.core.ValueMatch;
import dev.bitwigagent.core.Verification;
import dev.bitwigagent.grid.GridParameterWriter;
import dev.bitwigagent.host.DeviceSources;
import java.util.*;
import java.util.function.Consumer;

/**
 * Device-modulator reads and edits on one explicit root device: CAS before dispatch, one native operation, exact
 * readback in a later document event, no retry or rollback. Version-specific symbols live behind {@link Access}.
 */
public final class ModulatorEditor implements ParameterResolver.ModuleOwners {
    /** What the raw float of a modulator control is compared with: the grid writer verifies exact equality. */
    static final double RAW_TOLERANCE = 0;

    public static final int MAX_MODULATORS = 64, MAX_CHILDREN = 256, MAX_MAPPINGS = 256, MAX_TEXT = 1024;
    public static final List<String> TRANSFERS =
            List.of("linear", "rectify_pos", "rectify_neg", "abs_linear", "inv_abs_linear", "cubic", "inv_cubic");

    public enum Owner {
        MODULATOR,
        MODULE,
        DEVICE
    }

    public interface Access {
        void documentThread();

        /** The result's nativeVersion and nativeJarSha256. */
        void provenance(JsonObject out);

        void execute(Runnable task);
        /** Native root device behind the public proxy, owning track corroborated; guard re-checks the same objects. */
        Object device(DeviceSources.Source source, Runnable check);

        void guardDevice(DeviceSources.Source source, Object device, Runnable check);

        Object collection(Object device);

        List<Object> modulators(Object collection);

        UUID id(Object modulator);

        String name(Object modulator);
        /** Null when the modulator's contents are not materialized. */
        UUID typeId(Object modulator);

        int[] cell(Object modulator);
        /** Direct children of materialized contents, or null when unmaterialized. */
        List<Object> children(Object modulator);

        boolean isSource(Object child);

        boolean isTarget(Object child);

        String identifier(Object child);

        Object owner(Object model, Owner kind);

        String path(Object model);
        /** float|integer|enum|boolean, else null. */
        String kind(Object parameter);
        /** Typed value row; reasons for partially read fields are added to unsupported. */
        JsonObject value(Object parameter, Runnable check, Collection<String> unsupported);

        String modelClass(Object model);

        List<Object> mappings(Object source);

        Object mappingTarget(Object mapping);

        double amount(Object mapping);

        String transfer(Object mapping);

        boolean enabled(Object mapping);

        boolean polyphonic(Object mapping);

        boolean scaled(Object mapping);

        double range(Object target);

        boolean canModulate(Object source, Object target);
        /** The device's direct parameter with this parameters.list id, or null when it has none. */
        Object directParameter(Object device, String id);

        /** The parameters.list id of one of the device's direct parameters, or null when it is not one. */
        String directId(Object device, Object parameter);

        String directName(Object parameter);
        /** {graph, module, parameter} for a Grid module control on this device, or null when it has none now. */
        Object[] gridParameter(DeviceSources.Source source, UUID moduleId, String parameterId, Runnable check);

        String moduleId(Object module);
        /** The name of the module's gridModule ref, as grid.export gives it. */
        String moduleName(Object module);
        /** The native device helper this access captures with, so a parameter ref can be bound to its owner. */
        DeviceSources.Native natives();

        void requireParameterWritable(Object device, Object container, Object owner, Object parameter, Runnable check);

        void requireStructureWritable(Object device, Object collection, List<Object> members, Runnable check);

        Type resolveType(UUID typeId, String typeName);

        Runnable prepareSet(Object parameter, String kind, double raw);

        Runnable prepareMap(Object source, Object target, double amount, boolean enabled, String transfer);

        Runnable prepareUnmap(Object source, Object target);

        Runnable prepareDelete(Object collection, Object modulator);
        /** settled(null) on native success, settled(reason) on native rejection; called at most once per insertion. */
        Runnable prepareInsert(Object collection, UUID typeId, int x, int y, Consumer<String> settled);
    }

    public static final class Type {
        public final UUID id;
        public final String name;

        public Type(UUID id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    private final Access a;

    ModulatorEditor(Access access) {
        a = access;
    }

    void execute(Runnable task) {
        a.execute(task);
    }

    // ---------------------------------------------------------------- requests

    /** Target control of a mapping on the same device: its own parameter, a Grid module's or another modulator's. */
    static final class TargetRef {
        final boolean direct;
        final UUID moduleId, modulatorId;
        final String parameterId;
        /** The control's name as the ref read it, or null when the ref carries none. */
        final String name;
        /** The parameter ref as given, for the error that names what was not found. */
        final JsonObject ref;

        private TargetRef(
                JsonObject ref, boolean direct, UUID moduleId, UUID modulatorId, String parameterId, String name) {
            this.ref = ref;
            this.direct = direct;
            this.moduleId = moduleId;
            this.modulatorId = modulatorId;
            this.parameterId = parameterId;
            this.name = name;
        }

        /** The control a parameter ref owned by the device, a Grid module or a modulator names. */
        static TargetRef of(JsonObject parameter) {
            JsonObject owner = parameter.getAsJsonObject("owner");
            String kind = Refs.kind(owner);
            boolean direct = ParameterRefs.DEVICE.equals(kind), module = ParameterRefs.GRID_MODULE.equals(kind);
            UUID id = direct ? null : UUID.fromString(owner.get("id").getAsString());
            String name = parameter.has("name") ? parameter.get("name").getAsString() : null;
            return new TargetRef(
                    parameter,
                    direct,
                    module ? id : null,
                    module || direct ? null : id,
                    parameter.get("id").getAsString(),
                    name);
        }
    }

    static final class MapRequest {
        final UUID modulatorId;
        /** The source modulator's name as its ref read it: the write target compares it. */
        final String modulatorName;

        final String outputId;
        final TargetRef target;
        /** The mapping amount an expect asks to find now. */
        final Double expectedAmount, amount;

        final boolean expected, unmap;
        final String transfer;
        final Boolean enabled;

        private MapRequest(
                UUID modulatorId,
                String modulatorName,
                String outputId,
                TargetRef target,
                boolean expected,
                Double expectedAmount,
                Double amount,
                String transfer,
                Boolean enabled,
                boolean unmap) {
            this.modulatorId = modulatorId;
            this.modulatorName = modulatorName;
            this.outputId = outputId;
            this.target = target;
            this.expected = expected;
            this.expectedAmount = expectedAmount;
            this.amount = amount;
            this.transfer = transfer;
            this.enabled = enabled;
            this.unmap = unmap;
        }

        static MapRequest parse(JsonObject p, boolean unmap) {
            JsonObject source = p.getAsJsonObject("source");
            UUID modulatorId = UUID.fromString(source.get("id").getAsString());
            String modulatorName = source.get("name").getAsString();
            String output = p.has("outputId") ? text(p, "outputId") : null;
            TargetRef target = TargetRef.of(p.getAsJsonObject("target"));
            boolean expected = p.has("expect");
            Double expectedAmount =
                    expected ? p.getAsJsonObject("expect").get("amount").getAsDouble() : null;
            if (unmap)
                return new MapRequest(
                        modulatorId, modulatorName, output, target, expected, expectedAmount, null, null, null, true);
            String transfer = p.has("transfer") ? p.get("transfer").getAsString() : null;
            Boolean enabled = p.has("enabled") ? p.get("enabled").getAsBoolean() : null;
            return new MapRequest(
                    modulatorId,
                    modulatorName,
                    output,
                    target,
                    expected,
                    expectedAmount,
                    p.get("amount").getAsDouble(),
                    transfer,
                    enabled,
                    false);
        }
    }

    static String text(JsonObject p, String key) {
        JsonElement value = p.get(key);
        if (value == null
                || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isString()
                || value.getAsString().length() > MAX_TEXT)
            throw Errors.notApplied(Reason.INVALID_PARAMS, key + " must be a string of at most 1024 UTF-16 units.");
        return value.getAsString();
    }

    // ---------------------------------------------------------------- target state

    private final class Scope {
        final DeviceSources.Source source;
        final Object device, collection;
        final List<Object> members;
        final Runnable check;
        final JsonObject deviceRef;

        Scope(DeviceSources.Source source, JsonObject deviceRef, Runnable check) {
            a.documentThread();
            check.run();
            this.source = source;
            this.deviceRef = deviceRef;
            this.check = check;
            device = a.device(source, check);
            collection = a.collection(device);
            if (collection == null)
                throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "Device has no primary modulator collection.");
            members = members(collection);
        }

        void guard() {
            check.run();
            a.guardDevice(source, device, check);
            if (a.collection(device) != collection) stale("Primary modulator collection changed.");
            if (!same(members(collection), members)) stale("Primary modulator membership or order changed.");
            check.run();
        }

        Object modulator(UUID id) {
            Object found = find(members, id);
            if (found == null)
                throw Errors.notApplied(
                        Reason.TARGET_NOT_FOUND,
                        "No primary modulator " + id + " on this device; read modulators.list.",
                        "modulators.list");
            return found;
        }

        boolean contains(Object model) {
            for (Object member : members) if (member == model) return true;
            return false;
        }
    }

    /** The member with this UUID, or null when none has it. */
    private Object find(List<Object> members, UUID id) {
        Object found = null;
        for (Object member : members) {
            if (!id.equals(a.id(member))) continue;
            if (found != null) stale("Modulator UUID is ambiguous.");
            found = member;
        }
        return found;
    }

    @Override
    public Object owner(DeviceSources.Native.Capture device, UUID id, Runnable check) {
        check.run();
        Object collection = a.collection(device.device);
        if (collection == null)
            throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "Device has no primary modulator collection.");
        return find(members(collection), id);
    }

    @Override
    public String ownerName(Object owner) {
        return a.name(owner);
    }

    @Override
    public Object parameter(Object owner, String key, Runnable check) {
        check.run();
        return findChild(owner, key, false);
    }

    @Override
    public String parameterName(Object parameter) {
        JsonElement name = a.value(parameter, () -> {}, new ArrayList<>()).get("name");
        return name != null && name.isJsonPrimitive() ? name.getAsString() : null;
    }

    private List<Object> members(Object collection) {
        List<Object> members = a.modulators(collection);
        if (members.size() > ModulatorSnapshotReader.MAX_MODULATORS)
            throw Errors.notApplied(Reason.OUT_OF_BOUNDS, "Primary modulator collection exceeds 4096 members.");
        return members;
    }

    private static boolean same(List<Object> left, List<Object> right) {
        if (left.size() != right.size()) return false;
        for (int i = 0; i < left.size(); i++) if (left.get(i) != right.get(i)) return false;
        return true;
    }

    private List<Object> children(Object modulator) {
        List<Object> children = a.children(modulator);
        if (children == null)
            throw Errors.notApplied(
                    Reason.NATIVE_UNAVAILABLE,
                    "Modulator contents are not materialized; open the device once and retry.");
        if (children.size() > MAX_CHILDREN)
            throw Errors.notApplied(Reason.OUT_OF_BOUNDS, "Modulator has more than 256 direct children.");
        return children;
    }

    private Object child(Object modulator, String id, boolean source) {
        Object found = findChild(modulator, id, source);
        if (found == null)
            throw Errors.notApplied(
                    Reason.TARGET_NOT_FOUND,
                    (source ? "No output " : "No parameter ") + "with id '" + id
                            + "' on this modulator; read modulators.list.",
                    "modulators.list");
        return found;
    }

    /** The modulator's output or parameter with this id, or null when it has none. */
    private Object findChild(Object modulator, String id, boolean source) {
        Object found = null;
        for (Object child : children(modulator)) {
            if (a.isSource(child) != source) continue;
            if (!id.equals(a.identifier(child))) continue;
            if (found != null) stale("Modulator child id is ambiguous.");
            found = child;
        }
        if (found != null && a.owner(found, Owner.MODULATOR) != modulator)
            stale("Modulator child belongs to another modulator.");
        return found;
    }

    private Object source(Object modulator, String outputId) {
        if (outputId != null) return child(modulator, outputId, true);
        List<Object> sources = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (Object child : children(modulator))
            if (a.isSource(child)) {
                sources.add(child);
                ids.add(a.identifier(child));
            }
        if (sources.size() != 1)
            throw Errors.notApplied(
                    Reason.INVALID_PARAMS,
                    "This modulator has " + sources.size() + " outputs " + ids + "; pass outputId.");
        if (a.owner(sources.get(0), Owner.MODULATOR) != modulator)
            stale("Modulator output belongs to another modulator.");
        return sources.get(0);
    }

    private Object mapping(Object source, Object target) {
        Object found = null;
        List<Object> mappings = a.mappings(source);
        if (mappings.size() > MAX_MAPPINGS)
            throw Errors.notApplied(Reason.OUT_OF_BOUNDS, "Output has more than 256 mappings.");
        for (Object mapping : mappings) {
            if (a.mappingTarget(mapping) != target) continue;
            if (found != null)
                throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "Output has duplicate mappings to one target.");
            found = mapping;
        }
        return found;
    }

    /** Resolved mapping target: the control plus its lifecycle owners for the write-context check. */
    private static final class Resolved {
        final Object parameter, container, owner;

        Resolved(Object parameter, Object container, Object owner) {
            this.parameter = parameter;
            this.container = container;
            this.owner = owner;
        }
    }

    private Resolved resolve(Scope s, TargetRef ref) {
        Resolved found;
        if (ref.moduleId != null) {
            Object[] bound = a.gridParameter(s.source, ref.moduleId, ref.parameterId, s.check);
            if (bound == null)
                throw Errors.targetNotFound(ref.ref, "The device's Grid has no such module or control now.");
            if (!a.isTarget(bound[2]))
                throw Errors.notApplied(Reason.UNSUPPORTED_SCOPE, "This Grid control is not a modulation target.");
            found = new Resolved(bound[2], bound[0], bound[1]);
        } else if (ref.direct) {
            Object parameter = a.directParameter(s.device, ref.parameterId);
            if (parameter == null) throw Errors.targetNotFound(ref.ref, "The device has no such parameter now.");
            if (!a.isTarget(parameter))
                throw Errors.notApplied(Reason.UNSUPPORTED_SCOPE, "This device parameter is not a modulation target.");
            found = new Resolved(parameter, s.device, s.device);
        } else {
            Object modulator = s.modulator(ref.modulatorId);
            Object parameter = child(modulator, ref.parameterId, false);
            if (!a.isTarget(parameter) || a.kind(parameter) == null)
                throw Errors.notApplied(
                        Reason.UNSUPPORTED_SCOPE, "This modulator control is not a supported modulation target.");
            found = new Resolved(parameter, s.collection, modulator);
        }
        if (ref.name != null
                && !ref.name.equals(ref.direct ? a.directName(found.parameter) : parameterName(found.parameter)))
            throw Errors.targetChanged("The control was renamed since it was read.", describe(s, found.parameter));
        return found;
    }

    // ---------------------------------------------------------------- reads

    /**
     * The modulators of the device as rows with refs: one page of them, or only the one with {@code only}. A row carries
     * the refs a parameter, a mapping target or a later call takes back.
     */
    JsonObject read(
            DeviceSources.Source source, JsonObject deviceRef, int offset, int limit, UUID only, Runnable check) {
        Scope s = new Scope(source, deviceRef, check);
        JsonArray rows = new JsonArray();
        List<String> unsupported = new ArrayList<>();
        for (int i = 0; i < s.members.size(); i++) {
            Object modulator = s.members.get(i);
            if (only != null ? !only.equals(a.id(modulator)) : i < offset || i >= offset + limit) continue;
            rows.add(row(s, modulator, i, unsupported));
        }
        if (only != null && rows.size() == 0) s.modulator(only);
        s.guard();
        JsonObject result = new JsonObject();
        result.add("target", s.deviceRef);
        result.addProperty("count", s.members.size());
        result.add("modulators", rows);
        JsonObject window = new JsonObject();
        window.addProperty("offset", offset);
        window.addProperty("limit", limit);
        window.addProperty("total", s.members.size());
        window.addProperty("returned", rows.size());
        result.add("window", window);
        boolean everything = only != null || (offset == 0 && s.members.size() <= limit);
        result.addProperty("complete", everything && unsupported.isEmpty());
        JsonArray missing = new JsonArray();
        unsupported.forEach(missing::add);
        result.add("unsupported", missing);
        result.addProperty("nativeMutation", false);
        return result;
    }

    private JsonObject row(Scope s, Object modulator, int index, List<String> unsupported) {
        JsonObject row = new JsonObject();
        String name = a.name(modulator);
        JsonObject ref = Refs.modulator(s.deviceRef, String.valueOf(a.id(modulator)), name == null ? "" : name);
        row.add("ref", ref);
        row.addProperty("index", index);
        row.addProperty("name", name);
        UUID type = a.typeId(modulator);
        row.addProperty("typeId", type == null ? null : type.toString());
        int[] cell = a.cell(modulator);
        row.addProperty("x", cell[0]);
        row.addProperty("y", cell[1]);
        List<Object> children = a.children(modulator);
        row.addProperty("contentsAvailable", children != null);
        if (children == null) {
            unsupported.add(index + ":contents_unmaterialized");
            return row;
        }
        if (children.size() > MAX_CHILDREN)
            throw Errors.notApplied(Reason.OUT_OF_BOUNDS, "Modulator has more than 256 direct children.");
        JsonArray parameters = new JsonArray(), outputs = new JsonArray();
        for (Object child : children) {
            s.check.run();
            String id = a.identifier(child);
            if (a.isSource(child)) {
                JsonObject output = new JsonObject();
                output.addProperty("outputId", id);
                JsonArray mappings = new JsonArray();
                List<Object> list = a.mappings(child);
                if (list.size() > MAX_MAPPINGS)
                    throw Errors.notApplied(Reason.OUT_OF_BOUNDS, "Output has more than 256 mappings.");
                for (Object mapping : list) mappings.add(mappingRow(s, mapping));
                output.add("mappings", mappings);
                outputs.add(output);
            } else if (a.kind(child) != null) {
                List<String> partial = new ArrayList<>();
                JsonObject value = a.value(child, s.check, partial);
                for (String reason : partial) unsupported.add(index + ":" + id + ":" + reason);
                parameters.add(parameterEntry(ref, value));
            } else if (id != null && !id.isEmpty()) {
                unsupported.add(index + ":" + id + ":" + a.modelClass(child));
            }
        }
        row.add("parameters", parameters);
        row.add("outputs", outputs);
        return row;
    }

    /** A control: its ref (none for an empty id, which a ref cannot carry) and its value unit as a write takes it. */
    private static JsonObject parameterEntry(JsonObject modulatorRef, JsonObject value) {
        String id = value.get("parameterId").getAsString();
        JsonElement name = value.get("name");
        String label = name != null && name.isJsonPrimitive() ? name.getAsString() : null;
        JsonObject entry = new JsonObject();
        if (!id.isEmpty())
            entry.add("ref", Refs.parameter(modulatorRef, id, label == null || label.isEmpty() ? null : label));
        entry.addProperty("name", label);
        JsonObject unit = new JsonObject();
        for (Map.Entry<String, JsonElement> field : value.entrySet())
            if (!Set.of("parameterId", "name", "shortName", "supported").contains(field.getKey()))
                unit.add(field.getKey(), field.getValue());
        entry.add("value", unit);
        return entry;
    }

    private JsonObject mappingRow(Scope s, Object mapping) {
        JsonObject row = new JsonObject();
        row.add("target", describe(s, a.mappingTarget(mapping)));
        row.addProperty("amount", a.amount(mapping));
        row.addProperty("transfer", a.transfer(mapping));
        row.addProperty("enabled", a.enabled(mapping));
        row.addProperty("polyphonic", a.polyphonic(mapping));
        row.addProperty("scaledByAnotherSource", a.scaled(mapping));
        return row;
    }

    /** A mapping's target: the parameter ref of a Grid module or modulator control here, else where it is. */
    private JsonObject describe(Scope s, Object target) {
        String id = target == null ? null : a.identifier(target);
        Object module = target == null ? null : a.owner(target, Owner.MODULE);
        if (module != null && a.owner(module, Owner.DEVICE) == s.device)
            return Refs.parameter(
                    Refs.gridModule(s.deviceRef, a.moduleId(module), a.moduleName(module)), id, readableName(target));
        Object modulator = target == null ? null : a.owner(target, Owner.MODULATOR);
        if (modulator != null && s.contains(modulator))
            return Refs.parameter(modulatorRef(s, modulator), id, readableName(target));
        String direct = target == null ? null : a.directId(s.device, target);
        if (direct != null) return Refs.parameter(s.deviceRef, direct, nonEmpty(a.directName(target)));
        JsonObject result = new JsonObject();
        result.addProperty(
                "location", target != null && a.owner(target, Owner.DEVICE) == s.device ? "device" : "external");
        result.addProperty("path", target == null ? null : a.path(target));
        result.addProperty("parameterId", id);
        return result;
    }

    /** The control's name when the host shows one, else null: a ref then carries none and compares none. */
    private String readableName(Object parameter) {
        try {
            return nonEmpty(parameterName(parameter));
        } catch (RpcException error) {
            return null;
        }
    }

    private static String nonEmpty(String name) {
        return name == null || name.isEmpty() ? null : name;
    }

    private JsonObject modulatorRef(Scope s, Object modulator) {
        String name = a.name(modulator);
        return Refs.modulator(s.deviceRef, String.valueOf(a.id(modulator)), name == null ? "" : name);
    }

    // ---------------------------------------------------------------- parameter write

    /**
     * Writes one control of a modulator, whose ref has been resolved to its device: the bare raw value, {display} for a
     * float or {label} for an enum; an expect compares the raw value now.
     */
    JsonObject setValue(
            HostOperation op,
            ParameterResolver.ModuleParameter target,
            JsonObject deviceRef,
            JsonObject ref,
            ValueInput input,
            JsonObject expect,
            Runnable check,
            Runnable dispatch) {
        Scope s = new Scope(target.device, deviceRef, check);
        ParameterResolver.NativeParameter bound = target.bind(this, a.natives(), check);
        Object modulator = bound.owner, parameter = bound.parameter;
        String kind = a.kind(parameter);
        if (kind == null)
            throw Errors.notApplied(Reason.UNSUPPORTED_SCOPE, "This modulator control family is not writable here.");
        JsonObject before = a.value(parameter, check, new ArrayList<>());
        if (expect != null) {
            JsonElement now = before.get("value");
            ValueMatch rule = kind.equals("float") ? ValueMatch.raw(RAW_TOLERANCE) : ValueMatch.EXACT;
            if (!rule.matches(expect.get("value"), now)) {
                JsonObject observed = new JsonObject();
                observed.add("value", now);
                throw Errors.expectMismatch("The current value differs from expect; nothing was written.", observed);
            }
        }
        GridParameterWriter.Request request = GridParameterWriter.Request.parse(synthetic(kind, before, input));
        request.validateCurrent(before);
        a.requireParameterWritable(s.device, s.collection, modulator, parameter, check);
        s.guard();
        bound.require(op);
        before = a.value(parameter, check, new ArrayList<>());
        request.validateCurrent(before);
        a.requireParameterWritable(s.device, s.collection, modulator, parameter, check);
        Runnable write = a.prepareSet(parameter, kind, request.rawValue);
        check.run();
        JsonElement previous = before.get("value");
        boolean changed = !request.matches(previous, request.value);
        if (changed) {
            dispatch.run();
            write.run();
        }
        check.run();
        s.guard();
        bound.require(op);
        JsonObject current = a.value(parameter, check, new ArrayList<>());
        if (!request.matches(current.get("value"), request.value))
            throw Errors.of(
                    changed ? Reason.READBACK_MISMATCH : Reason.TARGET_CHANGED,
                    "Native readback differs from the requested value; read modulators.list before another write.",
                    changed);
        JsonObject result = new JsonObject();
        result.add("ref", ref);
        JsonObject unit = parameterEntry(modulatorRef(s, modulator), current).getAsJsonObject("value");
        result.add("parameter", unit);
        JsonObject fields = new JsonObject();
        fields.add(
                "value",
                Results.field(
                        input.form == ValueInput.Form.BARE ? input.bare : requested(input),
                        unit,
                        changed ? Outcome.APPLIED : Outcome.ALREADY_MATCHED,
                        null,
                        Verification.NATIVE_READBACK));
        result.add("fields", fields);
        written(
                result,
                changed,
                "native_undo_aware_controller_operation_not_verified_grouping",
                "observed_nonrecording_nonautomation_not_native_transport_atomicity");
        return result;
    }

    private static JsonObject requested(ValueInput input) {
        JsonObject asked = new JsonObject();
        asked.addProperty(input.form.name().toLowerCase(Locale.ROOT), input.text);
        return asked;
    }

    /** The request the grid writer validates, made from the live row: its own kind and value are the precondition. */
    private static JsonObject synthetic(String kind, JsonObject before, ValueInput input) {
        JsonObject request = new JsonObject();
        request.addProperty("expectedKind", kind);
        request.add("expectedValue", before.get("value"));
        switch (input.form) {
            case BARE:
                request.add("value", input.bare);
                break;
            case DISPLAY:
                if (!kind.equals("float"))
                    throw Errors.notApplied(
                            Reason.UNSUPPORTED_UNIT,
                            "Only a float control shows a number to write by display; write the value you read.");
                request.addProperty("display", input.text);
                break;
            default:
                request.addProperty("value", optionValue(before, input.text));
        }
        return request;
    }

    /** The integer token of the one option with this label; a label that is none or several is refused. */
    private static int optionValue(JsonObject row, String label) {
        JsonElement options = row.get("options");
        List<Integer> hits = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        if (options != null && options.isJsonArray())
            for (JsonElement option : options.getAsJsonArray()) {
                String text = option.getAsJsonObject().get("label").getAsString();
                labels.add(text);
                if (text.equals(label))
                    hits.add(option.getAsJsonObject().get("value").getAsInt());
            }
        if (hits.size() != 1)
            throw Errors.notApplied(
                    Reason.INVALID_PARAMS,
                    hits.isEmpty()
                            ? "No option is labelled \"" + label + "\"; the labels are " + labels + "."
                            : "Several options are labelled \"" + label + "\"; write the value you read.");
        return hits.get(0);
    }

    // ---------------------------------------------------------------- mapping writes

    /**
     * Sets or clears one mapping. A map is an upsert: an existing mapping keeps the transfer and enabled state the
     * request leaves out, and a new one gets linear and enabled.
     */
    void map(
            DeviceSources.Source deviceSource,
            JsonObject deviceRef,
            MapRequest r,
            Runnable check,
            Runnable dispatch,
            Consumer<JsonObject> complete,
            Consumer<Throwable> fail) {
        Scope s = new Scope(deviceSource, deviceRef, check);
        Object modulator = s.modulator(r.modulatorId);
        String modulatorName = a.name(modulator);
        if (!r.modulatorName.equals(modulatorName == null ? "" : modulatorName))
            throw Errors.targetChanged("The modulator was renamed since it was read.", modulatorRef(s, modulator));
        Object source = source(modulator, r.outputId);
        String outputId = a.identifier(source);
        Resolved target = resolve(s, r.target);
        JsonObject targetRef = targetRef(s, target.parameter);
        Object existing = mapping(source, target.parameter);
        JsonObject previous = existing == null ? null : mappingRow(s, existing);
        expect(r, existing);
        String transfer;
        boolean enabled;
        if (r.unmap) {
            transfer = null;
            enabled = false;
        } else {
            double range = a.range(target.parameter);
            if (!(Math.abs(r.amount) <= range))
                throw Errors.notApplied(
                        Reason.INVALID_PARAMS,
                        "amount exceeds this target's modulation range ±" + range + "; no clamping is performed.");
            if (existing == null && !a.canModulate(source, target.parameter))
                throw Errors.notApplied(
                        Reason.UNSUPPORTED_SCOPE,
                        "Bitwig does not accept this output/target pair (for example a modulator modulating itself).");
            transfer = r.transfer != null ? r.transfer : existing == null ? "linear" : a.transfer(existing);
            enabled = r.enabled != null ? r.enabled : existing == null || a.enabled(existing);
        }
        boolean changed = r.unmap
                ? existing != null
                : existing == null
                        || a.amount(existing) != r.amount
                        || !transfer.equals(a.transfer(existing))
                        || a.enabled(existing) != enabled;
        a.requireParameterWritable(s.device, target.container, target.owner, target.parameter, check);
        Runnable operation = !changed
                ? null
                : r.unmap
                        ? a.prepareUnmap(source, target.parameter)
                        : a.prepareMap(source, target.parameter, r.amount, enabled, transfer);
        s.guard();
        if (s.modulator(r.modulatorId) != modulator
                || source(modulator, r.outputId) != source
                || resolve(s, r.target).parameter != target.parameter
                || mapping(source, target.parameter) != existing)
            throw Errors.notApplied(
                    Reason.TARGET_CHANGED, "Modulator, output, target or mapping changed before dispatch.");
        expect(r, existing);
        a.requireParameterWritable(s.device, target.container, target.owner, target.parameter, check);
        check.run();
        if (changed) {
            dispatch.run();
            operation.run();
        }
        a.execute(() -> {
            try {
                a.documentThread();
                check.run();
                s.guard();
                Object after = mapping(source, target.parameter);
                if (r.unmap
                        ? after != null
                        : after == null
                                || a.amount(after) != r.amount
                                || !transfer.equals(a.transfer(after))
                                || a.enabled(after) != enabled)
                    throw Errors.of(
                            changed ? Reason.READBACK_MISMATCH : Reason.TARGET_CHANGED,
                            "Mapping readback differs from the request; read modulators.list before another write.",
                            changed);
                JsonObject result = new JsonObject();
                result.add("ref", targetRef);
                result.add("source", modulatorRef(s, modulator));
                result.addProperty("outputId", outputId);
                result.add("previousMapping", previous == null ? JsonNull.INSTANCE : previous);
                result.add("mapping", after == null ? JsonNull.INSTANCE : mappingRow(s, after));
                written(
                        result,
                        changed,
                        r.unmap
                                ? "native_clear_modulation_undo_transaction_not_verified_grouping"
                                : "native_modulation_undo_transaction_not_verified_grouping");
                complete.accept(result);
            } catch (RuntimeException error) {
                fail.accept(error);
            }
        });
    }

    /** The parameter ref of a resolved mapping target, as a read would name it. */
    private JsonObject targetRef(Scope s, Object parameter) {
        return describe(s, parameter);
    }

    /** An expect names the amount the mapping has now, or none when there is no mapping; a different one writes nothing. */
    private void expect(MapRequest r, Object existing) {
        if (!r.expected) return;
        Double now = existing == null ? null : a.amount(existing);
        if (Objects.equals(now, r.expectedAmount)) return;
        JsonObject observed = new JsonObject();
        observed.add("amount", now == null ? JsonNull.INSTANCE : new JsonPrimitive(now));
        throw Errors.expectMismatch("The mapping's amount differs from expect; nothing was written.", observed);
    }

    // ---------------------------------------------------------------- structure writes

    void delete(
            DeviceSources.Source source,
            JsonObject deviceRef,
            UUID modulatorId,
            String expectedName,
            Runnable check,
            Runnable dispatch,
            Consumer<JsonObject> complete,
            Consumer<Throwable> fail) {
        Scope s = new Scope(source, deviceRef, check);
        Object modulator = s.modulator(modulatorId);
        requireName(s, modulator, expectedName);
        a.requireStructureWritable(s.device, s.collection, s.members, check);
        Runnable operation = a.prepareDelete(s.collection, modulator);
        s.guard();
        a.requireStructureWritable(s.device, s.collection, s.members, check);
        check.run();
        dispatch.run();
        operation.run();
        a.execute(() -> {
            try {
                a.documentThread();
                check.run();
                a.guardDevice(s.source, s.device, check);
                if (a.collection(s.device) != s.collection) stale("Primary modulator collection changed.");
                List<Object> expected = new ArrayList<>();
                for (Object member : s.members) if (member != modulator) expected.add(member);
                if (!same(members(s.collection), expected))
                    throw Errors.unknown(
                            Reason.READBACK_MISMATCH,
                            "Membership after delete is not the previous list minus this modulator; read modulators.list.");
                JsonObject result = new JsonObject();
                result.addProperty("count", expected.size());
                written(result, true, "native_delete_modulators_undo_transaction_not_verified_grouping");
                complete.accept(result);
            } catch (RuntimeException error) {
                fail.accept(error);
            }
        });
    }

    /** The ref's name counts when the modulator itself is the target: a renamed one is another thing to act on. */
    private void requireName(Scope s, Object modulator, String expectedName) {
        if (expectedName == null) return;
        JsonObject current = modulatorRef(s, modulator);
        if (!expectedName.equals(current.get("name").getAsString()))
            throw Errors.targetChanged("The modulator was renamed since it was read.", current);
    }

    void insert(
            DeviceSources.Source source,
            JsonObject deviceRef,
            UUID typeId,
            String typeName,
            Runnable check,
            Runnable dispatch,
            Consumer<JsonObject> complete,
            Consumer<Throwable> fail) {
        Scope s = new Scope(source, deviceRef, check);
        Type type = a.resolveType(typeId, typeName);
        int bottom = 0;
        for (Object member : s.members) {
            int[] cell = a.cell(member);
            bottom = Math.max(bottom, cell[1] + Math.max(1, cell[3]));
        }
        final int x = 0, y = bottom;
        a.requireStructureWritable(s.device, s.collection, s.members, check);
        boolean[] settled = {false};
        Runnable operation = a.prepareInsert(s.collection, type.id, x, y, reason -> {
            synchronized (settled) {
                if (settled[0]) return; // the native callback contract is at most once; ignore any repeat
                settled[0] = true;
            }
            a.execute(() -> {
                try {
                    a.documentThread();
                    check.run();
                    if (reason != null)
                        throw Errors.unknown(
                                Reason.HOST_REFUSED,
                                "Native insertion reported: " + reason
                                        + "; read modulators.list before another write.");
                    complete.accept(verifyInsert(s, type, x, y, check));
                } catch (RuntimeException error) {
                    fail.accept(error);
                }
            });
        });
        s.guard();
        a.requireStructureWritable(s.device, s.collection, s.members, check);
        check.run();
        dispatch.run();
        operation.run();
    }

    private JsonObject verifyInsert(Scope s, Type type, int x, int y, Runnable check) {
        a.guardDevice(s.source, s.device, check);
        if (a.collection(s.device) != s.collection) stale("Primary modulator collection changed.");
        List<Object> after = members(s.collection);
        // Identity, not equals: native models may define value equality.
        Set<Object> before = Collections.newSetFromMap(new IdentityHashMap<>());
        before.addAll(s.members);
        List<Object> added = new ArrayList<>(), survivors = new ArrayList<>();
        for (Object member : after) (before.contains(member) ? survivors : added).add(member);
        if (added.size() != 1 || !same(survivors, s.members))
            throw Errors.unknown(
                    Reason.READBACK_MISMATCH,
                    "Membership after insert is not the previous list plus exactly one modulator; read modulators.list.");
        Object created = added.get(0);
        if (!type.id.equals(a.typeId(created)))
            throw Errors.unknown(
                    Reason.READBACK_MISMATCH,
                    "Inserted modulator's type differs from " + type.name
                            + " or its contents are unmaterialized; read modulators.list.");
        Scope now = new Scope(s.source, s.deviceRef, check);
        List<String> unsupported = new ArrayList<>();
        JsonObject result = new JsonObject();
        int index = 0;
        while (after.get(index) != created) index++;
        JsonObject row = row(now, created, index, unsupported);
        result.add("ref", row.get("ref"));
        result.add("modulator", row);
        result.addProperty("typeName", type.name);
        result.addProperty("requestedX", x);
        result.addProperty("requestedY", y);
        result.addProperty("count", after.size());
        written(result, true, "native_browser_insertion_point_undo_not_verified");
        return result;
    }

    private void written(JsonObject result, boolean changed, String undoScope) {
        written(
                result,
                changed,
                undoScope,
                "observed_stopped_nonrecording_nonautomation_not_native_transport_atomicity");
    }

    private void written(JsonObject result, boolean changed, String undoScope, String safetyScope) {
        result.addProperty("mutationDispatched", changed);
        Results.stamp(result, changed ? Outcome.APPLIED : Outcome.ALREADY_MATCHED, Verification.NATIVE_READBACK);
        if (changed) {
            a.provenance(result);
        }
        result.addProperty("nativeMutation", changed);
        result.addProperty("nativeUndo", changed);
        result.addProperty("nativeUndoScope", changed ? undoScope : "no_native_operation");
        result.addProperty("undoGroupingVerified", false);
        result.addProperty("engineAcknowledgement", false);
        result.addProperty("safetyScope", safetyScope);
    }

    private static void stale(String message) {
        throw Errors.notApplied(Reason.TARGET_CHANGED, message);
    }
}
