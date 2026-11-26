package dev.bitwigagent;

import com.google.gson.*;
import java.util.*;
import java.util.function.Consumer;

/**
 * Device-modulator reads and edits on one explicit root device: CAS before dispatch, one native operation, exact
 * readback in a later document event, no retry or rollback. Version-specific symbols live behind {@link Access}.
 */
final class ModulatorEditor {
    static final int MAX_MODULATORS = 64, MAX_CHILDREN = 256, MAX_MAPPINGS = 256, MAX_TEXT = 1024;
    static final List<String> TRANSFERS =
            List.of("linear", "rectify_pos", "rectify_neg", "abs_linear", "inv_abs_linear", "cubic", "inv_cubic");

    enum Owner {
        MODULATOR,
        MODULE,
        DEVICE
    }

    interface Access {
        void documentThread();

        void execute(Runnable task);
        /** Native root device behind the public proxy, owning track corroborated; guard re-checks the same objects. */
        Object device(Object proxy, UUID trackId, Runnable check);

        void guardDevice(Object proxy, UUID trackId, Object device, Runnable check);

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
        /** {graph, module, parameter} for a Grid module control on this device. */
        Object[] gridParameter(Object proxy, UUID trackId, UUID moduleId, String parameterId, Runnable check);

        String moduleId(Object module);

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

    static final class Type {
        final UUID id;
        final String name;

        Type(UUID id, String name) {
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

    /** Target control of a mapping: a Grid module control or another modulator's control on the same device. */
    static final class TargetRef {
        final UUID moduleId, modulatorId;
        final String parameterId;

        TargetRef(UUID moduleId, UUID modulatorId, String parameterId) {
            this.moduleId = moduleId;
            this.modulatorId = modulatorId;
            this.parameterId = parameterId;
        }

        static TargetRef parse(JsonElement element) {
            if (element == null || !element.isJsonObject())
                throw Errors.notApplied(
                        ErrorCode.INVALID_PARAMS,
                        "target must be {moduleId, parameterId} or {modulatorId, parameterId}.");
            JsonObject t = element.getAsJsonObject();
            boolean module = t.has("moduleId");
            Params.fields(t, Set.of(module ? "moduleId" : "modulatorId", "parameterId"), Set.of());
            UUID id = uuid(t, module ? "moduleId" : "modulatorId");
            return new TargetRef(module ? id : null, module ? null : id, text(t, "parameterId"));
        }

        JsonObject json() {
            JsonObject result = new JsonObject();
            if (moduleId != null) result.addProperty("moduleId", moduleId.toString());
            else result.addProperty("modulatorId", modulatorId.toString());
            result.addProperty("parameterId", parameterId);
            return result;
        }
    }

    static final class MapRequest {
        final UUID modulatorId;
        final String outputId;
        final TargetRef target;
        final Double expectedAmount, amount;
        final String transfer;
        final Boolean enabled;
        final boolean unmap;

        private MapRequest(
                UUID modulatorId,
                String outputId,
                TargetRef target,
                Double expectedAmount,
                Double amount,
                String transfer,
                Boolean enabled,
                boolean unmap) {
            this.modulatorId = modulatorId;
            this.outputId = outputId;
            this.target = target;
            this.expectedAmount = expectedAmount;
            this.amount = amount;
            this.transfer = transfer;
            this.enabled = enabled;
            this.unmap = unmap;
        }

        static MapRequest parse(JsonObject p, boolean unmap) {
            Set<String> required = unmap
                    ? Set.of("parent", "modulatorId", "target", "expectedAmount")
                    : Set.of("parent", "modulatorId", "target", "expectedAmount", "amount");
            Set<String> optional = unmap ? Set.of("outputId") : Set.of("outputId", "transfer", "enabled");
            if (p == null || !p.keySet().containsAll(required))
                throw Errors.notApplied(
                        ErrorCode.INVALID_PARAMS, "Parameters must contain required fields " + required);
            for (String key : p.keySet()) {
                if (!required.contains(key) && !optional.contains(key))
                    throw Errors.notApplied(ErrorCode.INVALID_PARAMS, "Unknown parameter: " + key);
                if (p.get(key).isJsonNull() && !(key.equals("expectedAmount") && !unmap))
                    throw Errors.notApplied(ErrorCode.INVALID_PARAMS, key + " must not be null.");
            }
            validateParent(p);
            UUID modulatorId = uuid(p, "modulatorId");
            String output = p.has("outputId") ? text(p, "outputId") : null;
            TargetRef target = TargetRef.parse(p.get("target"));
            Double expected = p.get("expectedAmount").isJsonNull() ? null : amount(p, "expectedAmount");
            if (unmap) return new MapRequest(modulatorId, output, target, expected, null, null, null, true);
            String transfer = null;
            if (p.has("transfer")) {
                JsonElement value = p.get("transfer");
                if (!value.isJsonPrimitive()
                        || !value.getAsJsonPrimitive().isString()
                        || !TRANSFERS.contains(value.getAsString()))
                    throw Errors.notApplied(ErrorCode.INVALID_PARAMS, "transfer must be one of " + TRANSFERS + ".");
                transfer = value.getAsString();
            }
            Boolean enabled = p.has("enabled") ? Params.bool(p, "enabled") : null;
            return new MapRequest(modulatorId, output, target, expected, amount(p, "amount"), transfer, enabled, false);
        }

        private static double amount(JsonObject p, String key) {
            JsonElement value = p.get(key);
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
                throw Errors.notApplied(ErrorCode.INVALID_PARAMS, key + " must be a number in -1..1.");
            double number = value.getAsDouble();
            if (!Double.isFinite(number) || number < -1 || number > 1)
                throw Errors.notApplied(ErrorCode.INVALID_PARAMS, key + " must be a number in -1..1.");
            return number;
        }
    }

    static void validateParent(JsonObject p) {
        if (!p.get("parent").isJsonObject())
            throw Errors.notApplied(ErrorCode.INVALID_PARAMS, "parent must be an object.");
        JsonObject parent = p.getAsJsonObject("parent");
        DeviceSources.validateParent(parent);
        if (!parent.get("deviceIndex").getAsString().matches("-?[0-9]+"))
            throw Errors.notApplied(
                    ErrorCode.INVALID_PARAMS, "deviceIndex must be an integer, not a decimal or exponent.");
    }

    static UUID uuid(JsonObject p, String key) {
        String value = Params.string(p, key);
        if (!DeviceSources.canonicalUuid(value))
            throw Errors.notApplied(ErrorCode.INVALID_PARAMS, key + " must be a canonical UUID.");
        return UUID.fromString(value);
    }

    static String text(JsonObject p, String key) {
        JsonElement value = p.get(key);
        if (value == null
                || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isString()
                || value.getAsString().length() > MAX_TEXT)
            throw Errors.notApplied(ErrorCode.INVALID_PARAMS, key + " must be a string of at most 1024 UTF-16 units.");
        return value.getAsString();
    }

    static int count(JsonObject p, String key, int min) {
        int value = Params.integer(p, key, min, ModulatorSnapshotReader.MAX_MODULATORS);
        if (!p.get(key).getAsString().matches("-?[0-9]+"))
            throw Errors.notApplied(ErrorCode.INVALID_PARAMS, key + " must be an integer, not a decimal or exponent.");
        return value;
    }

    // ---------------------------------------------------------------- target state

    private final class Scope {
        final Object proxy, device, collection;
        final UUID trackId;
        final List<Object> members;
        final Runnable check;

        Scope(Object proxy, UUID trackId, Runnable check) {
            a.documentThread();
            check.run();
            this.proxy = proxy;
            this.trackId = trackId;
            this.check = check;
            device = a.device(proxy, trackId, check);
            collection = a.collection(device);
            if (collection == null)
                throw Errors.notApplied(ErrorCode.MODULATOR_UNAVAILABLE, "Device has no primary modulator collection.");
            members = members(collection);
        }

        void guard() {
            check.run();
            a.guardDevice(proxy, trackId, device, check);
            if (a.collection(device) != collection) stale("Primary modulator collection changed.");
            if (!same(members(collection), members)) stale("Primary modulator membership or order changed.");
            check.run();
        }

        Object modulator(UUID id) {
            Object found = null;
            for (Object member : members) {
                if (!id.equals(a.id(member))) continue;
                if (found != null) stale("Modulator UUID is ambiguous.");
                found = member;
            }
            if (found == null)
                throw Errors.notApplied(
                        ErrorCode.TARGET_NOT_FOUND,
                        "No primary modulator " + id + " on this device; reacquire modulators.get.");
            return found;
        }

        boolean contains(Object model) {
            for (Object member : members) if (member == model) return true;
            return false;
        }
    }

    private List<Object> members(Object collection) {
        List<Object> members = a.modulators(collection);
        if (members.size() > ModulatorSnapshotReader.MAX_MODULATORS)
            throw Errors.notApplied(
                    ErrorCode.MODULATOR_BOUNDS_EXCEEDED, "Primary modulator collection exceeds 4096 members.");
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
                    ErrorCode.MODULATOR_UNAVAILABLE,
                    "Modulator contents are not materialized; open the device once and retry.");
        if (children.size() > MAX_CHILDREN)
            throw Errors.notApplied(
                    ErrorCode.MODULATOR_BOUNDS_EXCEEDED, "Modulator has more than 256 direct children.");
        return children;
    }

    private Object child(Object modulator, String id, boolean source) {
        Object found = null;
        for (Object child : children(modulator)) {
            if (a.isSource(child) != source) continue;
            if (!id.equals(a.identifier(child))) continue;
            if (found != null) stale("Modulator child id is ambiguous.");
            found = child;
        }
        if (found == null)
            throw Errors.notApplied(
                    ErrorCode.TARGET_NOT_FOUND,
                    (source ? "No output " : "No parameter ") + "with id '" + id
                            + "' on this modulator; read modulators.get.");
        if (a.owner(found, Owner.MODULATOR) != modulator) stale("Modulator child belongs to another modulator.");
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
                    ErrorCode.INVALID_PARAMS,
                    "This modulator has " + sources.size() + " outputs " + ids + "; pass outputId.");
        if (a.owner(sources.get(0), Owner.MODULATOR) != modulator)
            stale("Modulator output belongs to another modulator.");
        return sources.get(0);
    }

    private Object mapping(Object source, Object target) {
        Object found = null;
        List<Object> mappings = a.mappings(source);
        if (mappings.size() > MAX_MAPPINGS)
            throw Errors.notApplied(ErrorCode.MODULATOR_BOUNDS_EXCEEDED, "Output has more than 256 mappings.");
        for (Object mapping : mappings) {
            if (a.mappingTarget(mapping) != target) continue;
            if (found != null)
                throw Errors.notApplied(
                        ErrorCode.MODULATOR_UNAVAILABLE, "Output has duplicate mappings to one target.");
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
        if (ref.moduleId != null) {
            Object[] bound = a.gridParameter(s.proxy, s.trackId, ref.moduleId, ref.parameterId, s.check);
            if (!a.isTarget(bound[2]))
                throw Errors.notApplied(ErrorCode.UNSUPPORTED_SCOPE, "This Grid control is not a modulation target.");
            return new Resolved(bound[2], bound[0], bound[1]);
        }
        Object modulator = s.modulator(ref.modulatorId);
        Object parameter = child(modulator, ref.parameterId, false);
        if (!a.isTarget(parameter) || a.kind(parameter) == null)
            throw Errors.notApplied(
                    ErrorCode.UNSUPPORTED_SCOPE, "This modulator control is not a supported modulation target.");
        return new Resolved(parameter, s.collection, modulator);
    }

    // ---------------------------------------------------------------- reads

    JsonObject get(Object proxy, UUID trackId, UUID modulatorId, Runnable check) {
        Scope s = new Scope(proxy, trackId, check);
        if (modulatorId == null && s.members.size() > MAX_MODULATORS)
            throw Errors.notApplied(ErrorCode.MODULATOR_BOUNDS_EXCEEDED, "More than 64 modulators; pass modulatorId.");
        JsonArray rows = new JsonArray();
        List<String> unsupported = new ArrayList<>();
        for (int i = 0; i < s.members.size(); i++) {
            Object modulator = s.members.get(i);
            if (modulatorId != null && !modulatorId.equals(a.id(modulator))) continue;
            rows.add(row(s, modulator, i, unsupported));
        }
        if (modulatorId != null && rows.size() == 0) s.modulator(modulatorId);
        s.guard();
        JsonObject result = HostServices.observed();
        result.addProperty("count", s.members.size());
        result.add("modulators", rows);
        result.addProperty("complete", unsupported.isEmpty());
        JsonArray missing = new JsonArray();
        unsupported.forEach(missing::add);
        result.add("unsupported", missing);
        result.addProperty("nativeMutation", false);
        return result;
    }

    private JsonObject row(Scope s, Object modulator, int index, List<String> unsupported) {
        JsonObject row = new JsonObject();
        row.addProperty("index", index);
        row.addProperty("modulatorId", String.valueOf(a.id(modulator)));
        row.addProperty("name", a.name(modulator));
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
            throw Errors.notApplied(
                    ErrorCode.MODULATOR_BOUNDS_EXCEEDED, "Modulator has more than 256 direct children.");
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
                    throw Errors.notApplied(ErrorCode.MODULATOR_BOUNDS_EXCEEDED, "Output has more than 256 mappings.");
                for (Object mapping : list) mappings.add(mappingRow(s, mapping));
                output.add("mappings", mappings);
                outputs.add(output);
            } else if (a.kind(child) != null) {
                List<String> partial = new ArrayList<>();
                JsonObject value = a.value(child, s.check, partial);
                for (String reason : partial) unsupported.add(index + ":" + id + ":" + reason);
                value.remove("supported");
                parameters.add(value);
            } else if (id != null && !id.isEmpty()) {
                unsupported.add(index + ":" + id + ":" + a.modelClass(child));
            }
        }
        row.add("parameters", parameters);
        row.add("outputs", outputs);
        return row;
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

    private JsonObject describe(Scope s, Object target) {
        JsonObject result = new JsonObject();
        String id = target == null ? null : a.identifier(target);
        Object module = target == null ? null : a.owner(target, Owner.MODULE);
        if (module != null && a.owner(module, Owner.DEVICE) == s.device) {
            result.addProperty("kind", "grid_module");
            result.addProperty("moduleId", a.moduleId(module));
        } else {
            Object modulator = target == null ? null : a.owner(target, Owner.MODULATOR);
            if (modulator != null && s.contains(modulator)) {
                result.addProperty("kind", "modulator");
                result.addProperty("modulatorId", String.valueOf(a.id(modulator)));
            } else {
                result.addProperty(
                        "kind", target != null && a.owner(target, Owner.DEVICE) == s.device ? "device" : "external");
                result.addProperty("path", target == null ? null : a.path(target));
            }
        }
        result.addProperty("parameterId", id);
        return result;
    }

    // ---------------------------------------------------------------- parameter write

    JsonObject setParameter(
            Object proxy,
            UUID trackId,
            UUID modulatorId,
            String parameterId,
            GridParameterWriter.Request request,
            Runnable check,
            Runnable dispatch) {
        if (!Set.of("float", "integer", "enum", "boolean").contains(request.kind))
            throw Errors.notApplied(
                    ErrorCode.INVALID_PARAMS, "Modulator controls take expectedKind float, integer, enum or boolean.");
        Scope s = new Scope(proxy, trackId, check);
        Object modulator = s.modulator(modulatorId);
        Object parameter = child(modulator, parameterId, false);
        String kind = a.kind(parameter);
        if (kind == null)
            throw Errors.notApplied(ErrorCode.UNSUPPORTED_SCOPE, "This modulator control family is not writable here.");
        JsonObject before = a.value(parameter, check, new ArrayList<>());
        request.validateCurrent(before);
        a.requireParameterWritable(s.device, s.collection, modulator, parameter, check);
        s.guard();
        if (child(modulator, parameterId, false) != parameter) stale("Modulator control changed.");
        before = a.value(parameter, check, new ArrayList<>());
        request.validateCurrent(before);
        a.requireParameterWritable(s.device, s.collection, modulator, parameter, check);
        Runnable write =
                a.prepareSet(parameter, kind, request.rawValue); // after the last validateCurrent resolved rawValue
        check.run();
        JsonElement previous = before.get("value");
        boolean changed = !request.matches(previous, request.value);
        if (changed) {
            dispatch.run();
            write.run();
        }
        check.run();
        s.guard();
        JsonObject current = a.value(parameter, check, new ArrayList<>());
        if (!request.matches(current.get("value"), request.value))
            throw Errors.of(
                    changed ? ErrorCode.MODULATOR_VERIFICATION_FAILED : ErrorCode.STALE_CONTEXT,
                    "Native readback differs from the requested value; inspect modulators.get before another write.",
                    changed ? Outcome.UNKNOWN : Outcome.NOT_APPLIED);
        JsonObject result = HostServices.observed();
        result.addProperty("modulatorId", modulatorId.toString());
        current.remove("supported");
        result.add("parameter", current);
        result.add("previousValue", previous);
        result.add("requestedValue", request.value);
        if (request.display != null) {
            result.addProperty("requestedDisplay", request.display);
            result.addProperty("displayMismatch", GridDisplayUnits.mismatch(current, request.display));
        }
        written(
                result,
                changed,
                "exact_native_model_readback",
                "native_undo_aware_controller_operation_not_verified_grouping",
                "observed_nonrecording_nonautomation_not_native_transport_atomicity");
        return result;
    }

    // ---------------------------------------------------------------- mapping writes

    void map(
            Object proxy,
            UUID trackId,
            MapRequest r,
            Runnable check,
            Runnable dispatch,
            Consumer<JsonObject> complete,
            Consumer<Throwable> fail) {
        Scope s = new Scope(proxy, trackId, check);
        Object modulator = s.modulator(r.modulatorId);
        Object source = source(modulator, r.outputId);
        String outputId = a.identifier(source);
        Resolved target = resolve(s, r.target);
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
                        ErrorCode.INVALID_PARAMS,
                        "amount exceeds this target's modulation range ±" + range + "; no clamping is performed.");
            if (existing == null) {
                if (!a.canModulate(source, target.parameter))
                    throw Errors.notApplied(
                            ErrorCode.UNSUPPORTED_SCOPE,
                            "Bitwig does not accept this output/target pair (for example a modulator modulating itself).");
                transfer = r.transfer == null ? "linear" : r.transfer;
                enabled = r.enabled == null || r.enabled;
            } else {
                transfer = a.transfer(existing);
                enabled = a.enabled(existing);
                if ((r.transfer != null && !r.transfer.equals(transfer)) || (r.enabled != null && r.enabled != enabled))
                    throw Errors.notApplied(
                            ErrorCode.INVALID_PARAMS,
                            "An existing mapping only changes amount here; unmap and map again to change transfer or enabled.");
            }
        }
        a.requireParameterWritable(s.device, target.container, target.owner, target.parameter, check);
        Runnable operation = r.unmap
                ? a.prepareUnmap(source, target.parameter)
                : a.prepareMap(source, target.parameter, r.amount, enabled, transfer);
        s.guard();
        if (s.modulator(r.modulatorId) != modulator
                || source(modulator, r.outputId) != source
                || resolve(s, r.target).parameter != target.parameter
                || mapping(source, target.parameter) != existing)
            stale("Modulator, output, target or mapping changed.");
        expect(r, existing);
        a.requireParameterWritable(s.device, target.container, target.owner, target.parameter, check);
        check.run();
        boolean changed = r.unmap || existing == null || a.amount(existing) != r.amount;
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
                            changed ? ErrorCode.MODULATOR_VERIFICATION_FAILED : ErrorCode.STALE_CONTEXT,
                            "Mapping readback differs from the request; inspect modulators.get before another write.",
                            changed ? Outcome.UNKNOWN : Outcome.NOT_APPLIED);
                JsonObject result = HostServices.observed();
                result.addProperty("modulatorId", r.modulatorId.toString());
                result.addProperty("outputId", outputId);
                result.add("target", describe(s, target.parameter));
                result.add("previousMapping", previous == null ? JsonNull.INSTANCE : previous);
                result.add("mapping", after == null ? JsonNull.INSTANCE : mappingRow(s, after));
                written(
                        result,
                        changed,
                        "exact_native_mapping_readback",
                        r.unmap
                                ? "native_clear_modulation_undo_transaction_not_verified_grouping"
                                : "native_modulation_undo_transaction_not_verified_grouping");
                complete.accept(result);
            } catch (RuntimeException error) {
                fail.accept(error);
            }
        });
    }

    private void expect(MapRequest r, Object existing) {
        if (r.expectedAmount == null ? existing != null : existing == null || a.amount(existing) != r.expectedAmount)
            throw Errors.notApplied(
                    ErrorCode.STALE_CONTEXT,
                    existing == null
                            ? "No mapping exists between this output and target; pass expectedAmount null to create one."
                            : "Existing mapping amount is " + a.amount(existing)
                                    + ", not expectedAmount; reacquire modulators.get.");
    }

    // ---------------------------------------------------------------- structure writes

    void delete(
            Object proxy,
            UUID trackId,
            UUID modulatorId,
            int expectedCount,
            Runnable check,
            Runnable dispatch,
            Consumer<JsonObject> complete,
            Consumer<Throwable> fail) {
        Scope s = new Scope(proxy, trackId, check);
        requireCount(s, expectedCount);
        Object modulator = s.modulator(modulatorId);
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
                a.guardDevice(s.proxy, s.trackId, s.device, check);
                if (a.collection(s.device) != s.collection) stale("Primary modulator collection changed.");
                List<Object> expected = new ArrayList<>();
                for (Object member : s.members) if (member != modulator) expected.add(member);
                if (!same(members(s.collection), expected))
                    throw Errors.unknown(
                            ErrorCode.MODULATOR_VERIFICATION_FAILED,
                            "Membership after delete is not the previous list minus this modulator; inspect modulators.get.");
                JsonObject result = HostServices.observed();
                result.addProperty("modulatorId", modulatorId.toString());
                result.addProperty("count", expected.size());
                written(
                        result,
                        true,
                        "exact_native_membership_readback",
                        "native_delete_modulators_undo_transaction_not_verified_grouping");
                complete.accept(result);
            } catch (RuntimeException error) {
                fail.accept(error);
            }
        });
    }

    void insert(
            Object proxy,
            UUID trackId,
            UUID typeId,
            String typeName,
            int expectedCount,
            Runnable check,
            Runnable dispatch,
            Consumer<JsonObject> complete,
            Consumer<Throwable> fail) {
        Scope s = new Scope(proxy, trackId, check);
        requireCount(s, expectedCount);
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
                                ErrorCode.MODULATOR_INSERT_REJECTED,
                                "Native insertion reported: " + reason
                                        + "; inspect modulators.get before another write.");
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
        a.guardDevice(s.proxy, s.trackId, s.device, check);
        if (a.collection(s.device) != s.collection) stale("Primary modulator collection changed.");
        List<Object> after = members(s.collection);
        // Identity, not equals: native models may define value equality.
        Set<Object> before = Collections.newSetFromMap(new IdentityHashMap<>());
        before.addAll(s.members);
        List<Object> added = new ArrayList<>(), survivors = new ArrayList<>();
        for (Object member : after) (before.contains(member) ? survivors : added).add(member);
        if (added.size() != 1 || !same(survivors, s.members))
            throw Errors.unknown(
                    ErrorCode.MODULATOR_VERIFICATION_FAILED,
                    "Membership after insert is not the previous list plus exactly one modulator; inspect modulators.get.");
        Object created = added.get(0);
        if (!type.id.equals(a.typeId(created)))
            throw Errors.unknown(
                    ErrorCode.MODULATOR_VERIFICATION_FAILED,
                    "Inserted modulator's type differs from " + type.name
                            + " or its contents are unmaterialized; inspect modulators.get.");
        Scope now = new Scope(s.proxy, s.trackId, check);
        List<String> unsupported = new ArrayList<>();
        JsonObject result = HostServices.observed();
        int index = 0;
        while (after.get(index) != created) index++;
        result.add("modulator", row(now, created, index, unsupported));
        result.addProperty("typeName", type.name);
        result.addProperty("requestedX", x);
        result.addProperty("requestedY", y);
        result.addProperty("count", after.size());
        written(
                result,
                true,
                "exact_native_membership_and_type_readback",
                "native_browser_insertion_point_undo_not_verified");
        return result;
    }

    private static void requireCount(Scope s, int expected) {
        if (s.members.size() != expected)
            throw Errors.notApplied(
                    ErrorCode.STALE_CONTEXT,
                    "Device has " + s.members.size() + " modulators, not expectedCount " + expected
                            + "; reacquire modulators.get.");
    }

    private static void written(JsonObject result, boolean changed, String verification, String undoScope) {
        written(
                result,
                changed,
                verification,
                undoScope,
                "observed_stopped_nonrecording_nonautomation_not_native_transport_atomicity");
    }

    private static void written(
            JsonObject result, boolean changed, String verification, String undoScope, String safetyScope) {
        result.addProperty("mutationDispatched", changed);
        result.addProperty("verification", changed ? verification : "already_matched");
        result.addProperty("nativeMutation", changed);
        result.addProperty("nativeUndo", changed);
        result.addProperty("nativeUndoScope", changed ? undoScope : "no_native_operation");
        result.addProperty("undoGroupingVerified", false);
        result.addProperty("engineAcknowledgement", false);
        result.addProperty("safetyScope", safetyScope);
    }

    private static void stale(String message) {
        throw Errors.notApplied(ErrorCode.STALE_CONTEXT, message);
    }
}
