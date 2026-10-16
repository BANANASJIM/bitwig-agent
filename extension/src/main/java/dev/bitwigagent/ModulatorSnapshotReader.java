package dev.bitwigagent;

import com.google.gson.*;
import java.util.*;
import static dev.bitwigagent.HostServices.failure;

/** One document-callback observation; never loads contents or traverses another control's value. */
final class ModulatorSnapshotReader {
    static final int MAX_MODULATORS = 4096, MAX_CHILDREN = 256, MAX_EXAMINED = 16384, MAX_TEXT = 1024;
    enum Kind { COLLECTION, MODULATOR, CONTENTS, MODEL, IDENTIFIED, STRING_PANEL }
    enum Field { COLLECTION, COUNT, PARENT, COLLECTION_OWNER, CONTENTS_OWNER, ID, NAME, CUSTOM_NAME,
        CONTENTS, TYPE_ID, CHILDREN, PARAMETER_ID, VALUE, PROJECT }
    interface Access {
        Object read(Field field, Object object);
        Object modulator(Object collection, int index);
        boolean is(Kind kind, Object object);
        boolean stringsAvailable();
        void requireModelThread(Object object);
    }
    private final Access a;
    private final NativeDeviceTarget targets;
    private final NativeReadContext contexts;
    ModulatorSnapshotReader(Access access, NativeDeviceTarget targets, NativeReadContext contexts) {
        a = access; this.targets = targets; this.contexts = contexts;
    }

    JsonObject list(Object proxy, UUID trackId, int offset, int limit, Runnable check) {
        if (offset < 0 || offset > 100000 || limit < 1 || limit > 128)
            throw failure("invalid_params", "Invalid modulator page bounds.");
        Snapshot snapshot = capture(proxy, trackId, null, offset, limit, check);
        JsonArray rows = new JsonArray();
        boolean partial = offset != 0 || snapshot.rows.length > limit;
        for (int i = 0; i < snapshot.rows.length; i++) {
            Row row = snapshot.rows[i];
            if (row.contents == null) partial = true;
            if (row.children != null) for (Child child : row.children) if (!child.supported) partial = true;
            if (i < offset || i >= offset + limit) continue;
            JsonObject item = new JsonObject();
            item.addProperty("index", i); item.addProperty("modulatorId", row.id.toString());
            item.addProperty("name", row.name); item.addProperty("customName", row.customName);
            item.addProperty("contentsAvailable", row.contents != null);
            item.addProperty("contentState", row.contents == null ? "unmaterialized" : "materialized");
            item.addProperty("typeId", row.typeId == null ? null : row.typeId.toString());
            if (row.contents == null) item.add("parameters", JsonNull.INSTANCE);
            else {
                JsonArray parameters = new JsonArray();
                for (Child child : row.children) {
                    JsonObject descriptor = new JsonObject();
                    descriptor.addProperty("parameterId", child.id);
                    descriptor.addProperty("modelClass", text(child.object.getClass().getName(), false));
                    descriptor.addProperty("supported", child.supported);
                    descriptor.addProperty("kind", child.supported ? "string" : null);
                    descriptor.addProperty("controlFamily", child.supported ? "string_panel" : null);
                    parameters.add(descriptor);
                }
                item.add("parameters", parameters);
            }
            rows.add(item);
        }
        guard(snapshot, check);
        JsonObject result = result();
        result.add("modulators", rows);
        JsonObject window = new JsonObject();
        window.addProperty("offset", offset); window.addProperty("limit", limit);
        window.addProperty("total", snapshot.rows.length); window.addProperty("returned", rows.size());
        result.add("window", window); result.addProperty("complete", !partial);
        JsonObject coverage = new JsonObject();
        coverage.addProperty("primaryCollection", "all_primary_members_checked_before_and_after_capture");
        coverage.addProperty("parameters", "direct_children_described_without_value_reads");
        coverage.addProperty("secondaryModulators", "excluded"); coverage.addProperty("nestedChildren", "excluded");
        coverage.addProperty("unsupportedFamilies", "explicit_descriptors_not_readable");
        coverage.addProperty("unmaterializedContents", "metadata_only_no_loading");
        result.add("coverage", coverage);
        return result;
    }

    JsonObject parameter(Object proxy, UUID trackId, UUID modulatorId, String parameterId, Runnable check) {
        if (modulatorId == null || parameterId == null || parameterId.length() > MAX_TEXT)
            throw failure("invalid_params", "Exact current modulator UUID and bounded parameter ID are required.");
        Snapshot snapshot = capture(proxy, trackId, modulatorId, 0, 0, check);
        Row row = null;
        for (Row candidate : snapshot.rows) if (candidate.id.equals(modulatorId)) row = candidate;
        if (row == null) throw failure("target_not_found", "Current primary modulator UUID was not found.");
        if (row.contents == null) throw failure("modulator_unavailable", "Selected modulator contents are not materialized; loading is not permitted.");
        Child selected = null;
        for (Child child : row.children) if (parameterId.equals(child.id)) selected = child;
        if (selected == null) throw failure("target_not_found", "Exact direct parameter ID was not found.");
        if (!selected.supported) {
            if (a.is(Kind.STRING_PANEL, selected.object) && !a.stringsAvailable())
                throw failure("modulator_unavailable", "String-panel read binding is unavailable.");
            throw failure("unsupported_scope", "Selected direct control is not a supported exact string panel.");
        }
        Object[] models = {snapshot.target.device, snapshot.collection, row.object, row.contents, selected.object};
        String[] labels = {"device", "collection", "modulator", "contents", "parameter", "project"};
        NativeReadContext.Capture context = contexts.capture(snapshot.context.project, models, labels, check, true);
        String value = text(a.read(Field.VALUE, selected.object), false);
        guard(snapshot, check);
        contexts.guard(context, models, labels, check);
        guard(snapshot, check);
        if (!value.equals(text(a.read(Field.VALUE, selected.object), false)))
            throw failure("stale_context", "Selected string value changed during capture.");
        JsonObject result = result();
        result.addProperty("modulatorId", row.id.toString()); result.addProperty("typeId", row.typeId.toString());
        result.addProperty("parameterId", selected.id); result.addProperty("kind", "string");
        result.addProperty("controlFamily", "string_panel"); result.addProperty("value", value);
        result.add("labels", JsonNull.INSTANCE); result.add("shortLabel", JsonNull.INSTANCE); result.add("display", JsonNull.INSTANCE);
        JsonObject readContext = new JsonObject();
        readContext.add("lifecycle", context.lifecycle); readContext.add("attachedContext", context.attachedContext);
        readContext.addProperty("owningProjectChecked", true);
        readContext.addProperty("activeApplicationProjectIdentityChecked", false);
        readContext.addProperty("nativeMutation", false);
        result.add("readContext", readContext);
        return result;
    }

    private Snapshot capture(Object proxy, UUID trackId, UUID selectedId, int offset, int limit, Runnable check) {
        NativeDeviceTarget.Capture target;
        try { target = targets.capture(proxy, trackId, check); }
        catch (RpcException error) {
            if (!"missing_target".equals(error.code)) throw error;
            throw failure("target_not_found", "Explicit device proxy has no native target.");
        }
        Object collection = a.read(Field.COLLECTION, target.device);
        require(Kind.COLLECTION, collection); parent(collection, target.device);
        int count = count(collection);
        Row[] rows = new Row[count];
        Set<UUID> ids = new HashSet<>();
        Set<Object> objects = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayList<Object> models = new ArrayList<>();
        models.add(target.device); models.add(collection);
        int examined = 0;
        for (int i = 0; i < count; i++) {
            check.run(); Object object = a.modulator(collection, i);
            require(Kind.MODULATOR, object); parent(object, collection);
            if (a.read(Field.COLLECTION_OWNER, object) != collection) stale("Modulator collection owner differs.");
            UUID id = uuid(a.read(Field.ID, object));
            if (!ids.add(id) || !objects.add(object)) throw failure("modulator_unavailable", "Duplicate primary modulator identity.");
            boolean inspected = selectedId == null ? i >= offset && i < offset + limit : selectedId.equals(id);
            Row row = new Row(object, id, inspected ? text(a.read(Field.NAME, object), true) : null,
                    inspected ? text(a.read(Field.CUSTOM_NAME, object), true) : null);
            rows[i] = row; models.add(object); row.inspected = inspected;
            if (!row.inspected) continue;
            row.contents = a.read(Field.CONTENTS, object);
            if (row.contents == null) continue;
            require(Kind.CONTENTS, row.contents); parent(row.contents, object);
            if (a.read(Field.CONTENTS_OWNER, row.contents) != object) stale("Contents owner differs.");
            row.typeId = uuid(a.read(Field.TYPE_ID, row.contents)); models.add(row.contents);
            List<?> children = children(row.contents);
            int size = children.size();
            examined += size;
            if (examined > MAX_EXAMINED) bounds("Aggregate direct child work exceeds 16384.");
            row.children = new Child[size];
            Set<String> childIds = new HashSet<>();
            Set<Object> childObjects = Collections.newSetFromMap(new IdentityHashMap<>());
            for (int j = 0; j < size; j++) {
                check.run(); Object child = children.get(j); require(Kind.MODEL, child); parent(child, row.contents);
                if (!childObjects.add(child)) throw failure("modulator_unavailable", "Duplicate direct child object.");
                String childId = identifier(child);
                if (childId != null && !childIds.add(childId)) throw failure("modulator_unavailable", "Duplicate direct parameter ID.");
                boolean supported = a.is(Kind.STRING_PANEL, child) && a.stringsAvailable();
                if (supported && childId == null) throw failure("modulator_unavailable", "String panel has no exact identifier.");
                row.children[j] = new Child(child, childId, supported); models.add(child);
            }
        }
        Object[] capturedModels = models.toArray();
        String[] labels = new String[capturedModels.length + 1];
        Arrays.fill(labels, "model");
        labels[capturedModels.length] = "project";
        Object project = a.read(Field.PROJECT, target.device);
        NativeReadContext.Capture context = contexts.capture(project, capturedModels, labels, check, false);
        return new Snapshot(proxy, target, collection, rows, capturedModels, labels, context, examined);
    }

    private void guard(Snapshot snapshot, Runnable check) {
        check.run(); targets.guard(snapshot.target, snapshot.proxy, check);
        if (a.read(Field.COLLECTION, snapshot.target.device) != snapshot.collection) stale("Primary collection changed.");
        parent(snapshot.collection, snapshot.target.device);
        if (count(snapshot.collection) != snapshot.rows.length) stale("Primary collection count changed.");
        for (int i = 0; i < snapshot.rows.length; i++) {
            check.run(); Row row = snapshot.rows[i];
            if (a.modulator(snapshot.collection, i) != row.object || !row.id.equals(uuid(a.read(Field.ID, row.object))))
                stale("Primary collection order, object or current UUID changed.");
            parent(row.object, snapshot.collection);
            if (a.read(Field.COLLECTION_OWNER, row.object) != snapshot.collection) stale("Primary modulator owner changed.");
            if (!row.inspected) continue;
            if (!Objects.equals(row.name, text(a.read(Field.NAME, row.object), true))
                    || !Objects.equals(row.customName, text(a.read(Field.CUSTOM_NAME, row.object), true)))
                stale("Primary modulator metadata changed.");
            if (a.read(Field.CONTENTS, row.object) != row.contents) stale("Modulator contents changed.");
            if (row.contents == null) continue;
            parent(row.contents, row.object);
            if (a.read(Field.CONTENTS_OWNER, row.contents) != row.object
                    || !row.typeId.equals(uuid(a.read(Field.TYPE_ID, row.contents)))) stale("Contents owner or type UUID changed.");
            List<?> children = children(row.contents);
            if (children.size() != row.children.length) stale("Direct child count changed.");
            snapshot.examined += row.children.length;
            if (snapshot.examined > MAX_EXAMINED) bounds("Aggregate direct child capture/revalidation work exceeds 16384.");
            for (int j = 0; j < row.children.length; j++) {
                check.run(); Child child = row.children[j];
                if (children.get(j) != child.object || !Objects.equals(identifier(child.object), child.id)
                        || child.supported != (a.is(Kind.STRING_PANEL, child.object) && a.stringsAvailable()))
                    stale("Direct child membership or identity changed.");
                parent(child.object, row.contents);
            }
        }
        contexts.guard(snapshot.context, snapshot.models, snapshot.labels, check);
        targets.guard(snapshot.target, snapshot.proxy, check); check.run();
    }

    private int count(Object collection) {
        Object count = a.read(Field.COUNT, collection);
        if (!(count instanceof Integer) || (Integer) count < 0) throw failure("modulator_unavailable", "Invalid primary count.");
        if ((Integer) count > MAX_MODULATORS) bounds("Primary collection exceeds 4096 modulators.");
        return (Integer) count;
    }
    private List<?> children(Object contents) {
        Object value = a.read(Field.CHILDREN, contents);
        if (!(value instanceof List<?>)) throw failure("modulator_unavailable", "Direct components are not a list.");
        List<?> result = (List<?>) value;
        if (result.size() > MAX_CHILDREN) bounds("Materialized contents exceed 256 direct children.");
        return result;
    }
    private String identifier(Object object) {
        return a.is(Kind.IDENTIFIED, object) ? text(a.read(Field.PARAMETER_ID, object), false) : null;
    }
    private void require(Kind kind, Object object) {
        if (!a.is(kind, object)) throw failure("modulator_unavailable", "Unknown native " + kind + " identity.");
        a.requireModelThread(object);
    }
    private void parent(Object object, Object expected) {
        a.requireModelThread(object);
        if (a.read(Field.PARENT, object) != expected) stale("Immediate native parent differs.");
    }
    private static UUID uuid(Object value) {
        if (!(value instanceof UUID)) throw failure("modulator_unavailable", "Native UUID is unavailable.");
        return (UUID) value;
    }
    private static String text(Object value, boolean nullable) {
        if (value == null && nullable) return null;
        if (!(value instanceof String)) throw failure("modulator_unavailable", "Native text has an unsupported type.");
        String text = (String) value;
        if (text.length() > MAX_TEXT) bounds("Native text exceeds 1024 UTF-16 units; not truncated.");
        return text;
    }
    private static void stale(String message) { throw failure("stale_context", message); }
    private static void bounds(String message) { throw failure("modulator_bounds_exceeded", message); }
    private static JsonObject result() {
        JsonObject result = HostServices.observed();
        result.addProperty("scope", "primary_modulators_on_root_device");
        result.addProperty("nativeMutation", false); result.addProperty("nativeUndo", false);
        result.addProperty("nativeRevisionAvailable", false);
        result.addProperty("identityCaveat", "modulatorId is the current native UUID, not a type, name or durable identity; reacquire after reopen. Owning track and owning project/context are checked, not application active-project identity. Public device index/name may lag native rebinding; no persistent device identity, native ABA detection, revision, atomic snapshot or cross-page consistency is claimed.");
        return result;
    }
    private static final class Row {
        final Object object; final UUID id; final String name, customName;
        boolean inspected; Object contents; UUID typeId; Child[] children;
        Row(Object object, UUID id, String name, String customName) {
            this.object = object; this.id = id; this.name = name; this.customName = customName;
        }
    }
    private static final class Child {
        final Object object; final String id; final boolean supported;
        Child(Object object, String id, boolean supported) { this.object = object; this.id = id; this.supported = supported; }
    }
    private static final class Snapshot {
        final Object proxy, collection; final NativeDeviceTarget.Capture target; final Row[] rows;
        final Object[] models; final String[] labels; final NativeReadContext.Capture context;
        int examined;
        Snapshot(Object proxy, NativeDeviceTarget.Capture target, Object collection, Row[] rows,
                Object[] models, String[] labels, NativeReadContext.Capture context, int examined) {
            this.proxy = proxy; this.target = target; this.collection = collection; this.rows = rows;
            this.models = models; this.labels = labels; this.context = context;
            this.examined = examined;
        }
    }
}
