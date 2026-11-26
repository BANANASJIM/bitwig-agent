package dev.bitwigagent;

import static dev.bitwigagent.HostServices.failure;

import com.google.gson.*;
import java.util.*;
import java.util.regex.Pattern;

/**
 * One user Remote Controls page in a Grid device's preset-page list (the list the UI's "Add New Page" uses; device
 * pages refuse Grid module parameters): define (create or replace) its name and eight slots, or delete it. Each native
 * step is its own undo-aware model change; there is no outer group, retry or rollback. Device, module and plugin
 * pages are listed for name conflicts but never edited.
 */
final class GridRemotePageWriter {
    static final int SLOTS = 8, MAX_PAGE_NAME = 256, MAX_LABEL = 256;
    private static final Pattern UUID_PATTERN =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Set<GridSnapshotReader.Kind> SCALARS = EnumSet.of(
            GridSnapshotReader.Kind.FLOAT,
            GridSnapshotReader.Kind.BOOLEAN,
            GridSnapshotReader.Kind.INTEGER,
            GridSnapshotReader.Kind.ENUM);

    interface Access {
        /** The device's Remote Controls host. */
        Object host(Object device);
        /** The host's preset-page list: the only list edited here. */
        Object presetPages(Object host);
        /** The host's device-page list (read for name conflicts only). */
        Object devicePages(Object host);
        /** Every page in native display order: preset, plugin, device, chain, module and modulator pages. */
        List<Object> allPages(Object host);
        /** The pages the device's page selector shows, in order (the public Remote Controls page list). */
        List<Object> visiblePages(Object host);
        /** Pages of the preset- or device-page list, in list order. */
        List<Object> pages(Object pageList);

        Object pageList(Object page);

        Object pageOwner(Object page);

        String pageName(Object page);

        boolean licensed(Object device);
        /** A native remote-mappable parameter whose nearest owning device is device. */
        boolean mappable(Object parameter, Object device);
        /** The page's own eligibility predicate for this parameter. */
        boolean eligible(Object page, Object parameter);

        Object slot(Object page, int index);

        Object slotTarget(Object slot);

        String slotLabel(Object slot);

        Object createPage(Object pageList);

        void renamePage(Object page, String name);

        void mapSlot(Object page, int index, String label, Object parameter);

        void clearSlot(Object page, int index);

        void deletePage(Object pageList, Object page);
    }

    private final GridSnapshotReader values;
    private final GridParameterContext contexts;
    private final Access nativeAccess;

    GridRemotePageWriter(GridSnapshotReader values, GridParameterContext contexts, Access nativeAccess) {
        this.values = values;
        this.contexts = contexts;
        this.nativeAccess = nativeAccess;
    }

    JsonObject define(Object proxy, UUID trackId, Request request, Runnable check, Runnable dispatch) {
        GridSnapshotReader.NativeTarget target = values.bindTarget(proxy, trackId, check);
        Object device = target.target;
        Host host = host(device);
        Slot[] wanted = resolve(proxy, trackId, target, device, request, check);
        Object existing = existing(host, request.pageName, request.replace);
        if (existing != null)
            for (Slot slot : wanted)
                if (slot != null && !nativeAccess.eligible(existing, slot.binding.parameter))
                    throw failure(
                            "unsupported_scope",
                            "Remote Controls page refuses " + slot.describe() + " (native page eligibility).");
        GridParameterContext.Capture context =
                contexts.requireRemoteWritable(device, host.host, host.pages, null, check);
        values.guardTarget(target, proxy, trackId);
        for (Slot slot : wanted) if (slot != null) values.guardParameter(slot.binding);
        if (existing != existing(host, request.pageName, request.replace))
            throw failure("stale_context", "The named Remote Controls page changed before dispatch.");
        contexts.requireRemoteWritable(device, host.host, host.pages, context, check);
        check.run();

        JsonArray operations = new JsonArray();
        boolean created = existing == null;
        Object page = existing;
        boolean dispatched = false;
        try {
            if (created) {
                dispatch.run();
                dispatched = true;
                page = nativeAccess.createPage(host.pages);
                operations.add(operation("create", -1));
                if (page == null
                        || nativeAccess.pageList(page) != host.pages
                        || !nativeAccess.pages(host.pages).contains(page))
                    throw new RpcException(
                            "grid_verification_failed",
                            "Native page creation returned no page in the preset-page list; inspect devices.remote.get.",
                            "unknown");
                check.run();
                nativeAccess.renamePage(page, request.pageName);
                operations.add(operation("rename", -1));
                // The page exists now: its own predicate is authoritative before any slot is mapped.
                for (Slot slot : wanted)
                    if (slot != null && !nativeAccess.eligible(page, slot.binding.parameter))
                        throw new RpcException(
                                "unsupported_scope",
                                "The new page refuses " + slot.describe()
                                        + "; it was created empty and named. Remove it with devices.remote.deletePage.",
                                "unknown");
            }
            for (int index = 0; index < SLOTS; index++) {
                Slot slot = wanted[index];
                Object current = nativeAccess.slot(page, index);
                if (slot == null) {
                    if (current == null) continue;
                    if (!dispatched) {
                        dispatch.run();
                        dispatched = true;
                    }
                    check.run();
                    nativeAccess.clearSlot(page, index);
                    operations.add(operation("clear", index));
                } else {
                    boolean sameTarget = current != null && nativeAccess.slotTarget(current) == slot.binding.parameter;
                    if (sameTarget && slot.label.equals(nativeAccess.slotLabel(current))) continue;
                    if (!dispatched) {
                        dispatch.run();
                        dispatched = true;
                    }
                    check.run();
                    if (sameTarget) {
                        // The native map is a no-op for an unchanged target, label included: clear, then map.
                        nativeAccess.clearSlot(page, index);
                        operations.add(operation("clear", index));
                        check.run();
                    }
                    nativeAccess.mapSlot(page, index, slot.label, slot.binding.parameter);
                    operations.add(operation("map", index));
                }
            }
        } catch (RuntimeException error) {
            if (!dispatched) throw error;
            if (error instanceof RpcException && "unknown".equals(((RpcException) error).outcome)) throw error;
            RpcException unknown = new RpcException(
                    error instanceof RpcException ? ((RpcException) error).code : "grid_unavailable",
                    "Remote Controls page edit failed after dispatch (" + operations.size() + " native step(s) done): "
                            + error.getMessage() + " Inspect devices.remote.get before another write.",
                    "unknown");
            unknown.initCause(error);
            throw unknown;
        }
        check.run();
        try {
            values.guardTarget(target, proxy, trackId);
            contexts.guardRemote(context, device, host.host, host.pages, check);
            verify(host, page, request, wanted);
        } catch (RpcException error) {
            if (!dispatched) throw error;
            throw new RpcException(
                    error.code.equals("stale_context") ? "grid_verification_failed" : error.code,
                    error.getMessage() + " Inspect devices.remote.get before another write.",
                    "unknown");
        }
        check.run();
        JsonObject result = HostServices.observed();
        result.add("page", pageJson(host, page));
        result.addProperty("created", created);
        JsonArray slots = new JsonArray();
        for (Slot slot : wanted) slots.add(slot == null ? JsonNull.INSTANCE : slot.json());
        result.add("slots", slots);
        result.add("operations", operations);
        result.add("pages", visibleNames(host));
        return finish(result, dispatched, dispatched ? "exact_native_remote_page_readback" : "already_matched");
    }

    JsonObject deletePage(Object proxy, UUID trackId, String pageName, Runnable check, Runnable dispatch) {
        GridSnapshotReader.NativeTarget target = values.bindTarget(proxy, trackId, check);
        Host host = host(target.target);
        Object page = deletable(host, pageName);
        GridParameterContext.Capture context =
                contexts.requireRemoteWritable(target.target, host.host, host.pages, null, check);
        values.guardTarget(target, proxy, trackId);
        if (deletable(host, pageName) != page)
            throw failure("stale_context", "The named Remote Controls page changed before dispatch.");
        contexts.requireRemoteWritable(target.target, host.host, host.pages, context, check);
        check.run();
        dispatch.run();
        try {
            nativeAccess.deletePage(host.pages, page);
            check.run();
            values.guardTarget(target, proxy, trackId);
            contexts.guardRemote(context, target.target, host.host, host.pages, check);
            if (nativeAccess.pages(host.pages).contains(page)
                    || nativeAccess.allPages(host.host).contains(page))
                throw new RpcException(
                        "grid_verification_failed", "The page is still listed after the native delete.", "unknown");
        } catch (RpcException error) {
            if ("unknown".equals(error.outcome)) throw error;
            throw new RpcException(
                    error.code, error.getMessage() + " Inspect devices.remote.get before another write.", "unknown");
        } catch (RuntimeException error) {
            RpcException unknown = new RpcException(
                    "grid_unavailable",
                    "Native page delete failed after dispatch; inspect devices.remote.get.",
                    "unknown");
            unknown.initCause(error);
            throw unknown;
        }
        check.run();
        JsonObject result = HostServices.observed();
        result.addProperty("deletedPage", pageName);
        result.add("pages", visibleNames(host));
        return finish(result, true, "native_page_absent_after_delete");
    }

    // ---- reads -----------------------------------------------------------------------------------------------

    /** pages: the edited preset-page list; devicePages: read for name conflicts only. */
    private static final class Host {
        final Object host, pages, devicePages;

        Host(Object host, Object pages, Object devicePages) {
            this.host = host;
            this.pages = pages;
            this.devicePages = devicePages;
        }
    }

    private Host host(Object device) {
        Object host = nativeAccess.host(device);
        if (host == null)
            throw failure("grid_unavailable", "Native Remote Controls host is unavailable for this device.");
        values.requireModelThread(host);
        Object pages = nativeAccess.presetPages(host), devicePages = nativeAccess.devicePages(host);
        if (pages == null || devicePages == null)
            throw failure("grid_unavailable", "Native Remote Controls page lists are unavailable.");
        values.requireModelThread(pages);
        if (!nativeAccess.licensed(device))
            throw failure(
                    "unsafe_state", "Device is not licensed: Bitwig does not create Remote Controls pages for it.");
        return new Host(host, pages, devicePages);
    }

    /**
     * The one preset page named name, or null when none exists. A same-named page of another kind (device, module,
     * plugin) or a create over an existing preset page is a conflict; several preset pages of that name are ambiguous.
     */
    private Object existing(Host host, String name, boolean replace) {
        Named named = named(host, name);
        if (named.otherKind != null)
            throw failure(
                    "page_exists",
                    "A " + named.otherKind + " Remote Controls page is already named " + quote(name)
                            + "; only preset pages are edited here. Choose another pageName.");
        if (named.page != null && !replace)
            throw failure(
                    "page_exists",
                    "A Remote Controls page is already named " + quote(name)
                            + "; pass mode \"replace\" to redefine it.");
        return named.page;
    }

    private Object deletable(Host host, String name) {
        Named named = named(host, name);
        if (named.page == null && named.otherKind != null)
            throw failure(
                    "unsupported_scope",
                    "The page named " + quote(name) + " is a " + named.otherKind
                            + " page; only preset pages can be deleted here.");
        if (named.page == null)
            throw failure(
                    "stale_context",
                    "No preset Remote Controls page is named " + quote(name) + "; reacquire devices.remote.get.");
        return named.page;
    }

    private static final class Named {
        Object page;
        String otherKind;
    }

    /** The unique preset page shown as name (several are ambiguous), and the kind of any other same-named page. */
    private Named named(Host host, String name) {
        Named result = new Named();
        int edited = 0;
        for (Object page : nativeAccess.allPages(host.host)) {
            String kind = kind(host, page);
            if (!name.equals(displayName(host, page, kind))) continue;
            if (kind.equals("preset")) {
                edited++;
                result.page = page;
            } else if (result.otherKind == null) result.otherKind = kind;
        }
        if (edited > 1)
            throw failure(
                    "stale_context",
                    edited + " preset Remote Controls pages are named " + quote(name)
                            + "; rename or delete the extras first.");
        return result;
    }

    private String kind(Host host, Object page) {
        Object list = nativeAccess.pageList(page);
        if (list == host.pages) return "preset";
        if (list == host.devicePages) return "device";
        Object owner = nativeAccess.pageOwner(page);
        if (values.effectiveModuleName(owner) != null) return "module";
        return "other";
    }

    /** Stored name, or the owning module's effective name for a Grid module page (as the selector shows it). */
    private String displayName(Host host, Object page, String kind) {
        if (kind.equals("module")) return values.effectiveModuleName(nativeAccess.pageOwner(page));
        String name = nativeAccess.pageName(page);
        if (name != null && !name.isEmpty()) return name;
        if (!kind.equals("preset") && !kind.equals("device")) return "";
        // An unnamed page shows riz's fallback "Page <position+1>"; sZK names its first page "Perform".
        int position = nativeAccess
                .pages(kind.equals("preset") ? host.pages : host.devicePages)
                .indexOf(page);
        return kind.equals("preset") && position == 0 ? "Perform" : "Page " + (position + 1);
    }

    private JsonArray visibleNames(Host host) {
        JsonArray names = new JsonArray();
        for (Object page : nativeAccess.visiblePages(host.host)) names.add(displayName(host, page, kind(host, page)));
        return names;
    }

    private JsonObject pageJson(Host host, Object page) {
        JsonObject result = new JsonObject();
        result.addProperty("name", nativeAccess.pageName(page));
        result.addProperty("kind", "preset");
        int index = nativeAccess.visiblePages(host.host).indexOf(page);
        result.addProperty("visible", index >= 0);
        if (index >= 0) result.addProperty("pageIndex", index);
        else result.add("pageIndex", JsonNull.INSTANCE);
        return result;
    }

    private void verify(Host host, Object page, Request request, Slot[] wanted) {
        if (nativeAccess.pageList(page) != host.pages
                || !nativeAccess.pages(host.pages).contains(page))
            throw failure("stale_context", "The defined page is no longer in the preset-page list.");
        if (!request.pageName.equals(nativeAccess.pageName(page)))
            throw failure("stale_context", "Native page name readback differs from the requested pageName.");
        if (!nativeAccess.visiblePages(host.host).contains(page))
            throw failure("stale_context", "The defined page is not in the device's visible page list.");
        for (int index = 0; index < SLOTS; index++) {
            Object slot = nativeAccess.slot(page, index);
            Slot want = wanted[index];
            if (want == null
                    ? slot != null
                    : slot == null
                            || nativeAccess.slotTarget(slot) != want.binding.parameter
                            || !want.label.equals(nativeAccess.slotLabel(slot)))
                throw failure("stale_context", "Native slot " + index + " readback differs from the request.");
        }
    }

    // ---- slot resolution -------------------------------------------------------------------------------------

    private Slot[] resolve(
            Object proxy,
            UUID trackId,
            GridSnapshotReader.NativeTarget target,
            Object device,
            Request request,
            Runnable check) {
        Slot[] wanted = new Slot[SLOTS];
        IdentityHashMap<Object, Integer> seen = new IdentityHashMap<>();
        for (int index = 0; index < request.slots.size(); index++) {
            SlotSpec spec = request.slots.get(index);
            if (spec == null) continue;
            UUID moduleId =
                    spec.moduleId != null ? spec.moduleId : values.moduleIdNamed(target, spec.moduleName, check);
            GridSnapshotReader.ParameterBinding binding =
                    values.bindParameter(proxy, trackId, moduleId, spec.parameterId, check);
            Slot slot = new Slot(index, spec, moduleId, binding);
            if (!SCALARS.contains(binding.kind))
                throw failure(
                        "unsupported_scope",
                        slot.describe() + " is a " + binding.kind.name().toLowerCase(Locale.ROOT)
                                + " control; only scalar Grid parameters map to Remote Controls.");
            if (!nativeAccess.mappable(binding.parameter, device))
                throw failure(
                        "unsupported_scope", slot.describe() + " is not a remote-mappable parameter of this device.");
            Integer previous = seen.put(binding.parameter, index);
            if (previous != null)
                throw failure("invalid_params", "slots " + previous + " and " + index + " name the same parameter.");
            wanted[index] = slot;
        }
        return wanted;
    }

    private static final class Slot {
        final int index;
        final UUID moduleId;
        final String moduleName, parameterId, label;
        final GridSnapshotReader.ParameterBinding binding;

        Slot(int index, SlotSpec spec, UUID moduleId, GridSnapshotReader.ParameterBinding binding) {
            this.index = index;
            this.moduleId = moduleId;
            this.moduleName = spec.moduleName;
            this.parameterId = spec.parameterId;
            this.label = spec.label;
            this.binding = binding;
        }

        String describe() {
            return "slot " + index + " (" + (moduleName != null ? quote(moduleName) : moduleId) + "/"
                    + quote(parameterId) + ")";
        }

        JsonObject json() {
            JsonObject result = new JsonObject();
            result.addProperty("moduleId", moduleId.toString());
            if (moduleName != null) result.addProperty("moduleName", moduleName);
            result.addProperty("parameterId", parameterId);
            result.addProperty("label", label);
            return result;
        }
    }

    private static JsonObject operation(String op, int slot) {
        JsonObject result = new JsonObject();
        result.addProperty("op", op);
        if (slot >= 0) result.addProperty("slot", slot);
        return result;
    }

    private static JsonObject finish(JsonObject result, boolean dispatched, String verification) {
        result.addProperty("mutationDispatched", dispatched);
        result.addProperty("verification", verification);
        result.addProperty("nativeMutation", dispatched);
        result.addProperty("nativeUndo", dispatched);
        result.addProperty(
                "nativeUndoScope",
                dispatched ? "one_native_undo_step_per_operation_not_grouped" : "no_native_operation");
        result.addProperty("undoGroupingVerified", false);
        result.addProperty("engineAcknowledgement", false);
        return result;
    }

    private static String quote(String text) {
        return "\"" + text + "\"";
    }

    // ---- requests --------------------------------------------------------------------------------------------

    static final class SlotSpec {
        final UUID moduleId;
        final String moduleName, parameterId, label;

        SlotSpec(UUID moduleId, String moduleName, String parameterId, String label) {
            this.moduleId = moduleId;
            this.moduleName = moduleName;
            this.parameterId = parameterId;
            this.label = label;
        }
    }

    /** devices.remote.define: pageName, slots (1..8, slot k = entry k, null = empty) and mode create|replace. */
    static final class Request {
        final String pageName;
        final boolean replace;
        final List<SlotSpec> slots;

        private Request(String pageName, boolean replace, List<SlotSpec> slots) {
            this.pageName = pageName;
            this.replace = replace;
            this.slots = slots;
        }

        static Request parse(JsonObject p) {
            String name = pageName(p);
            boolean replace = false;
            if (p.has("mode")) {
                JsonElement mode = p.get("mode");
                if (!mode.isJsonPrimitive()
                        || !mode.getAsJsonPrimitive().isString()
                        || !(mode.getAsString().equals("create")
                                || mode.getAsString().equals("replace")))
                    throw failure("invalid_params", "mode must be \"create\" or \"replace\".");
                replace = mode.getAsString().equals("replace");
            }
            JsonElement array = p.get("slots");
            if (array == null
                    || !array.isJsonArray()
                    || array.getAsJsonArray().size() < 1
                    || array.getAsJsonArray().size() > SLOTS)
                throw failure(
                        "invalid_params",
                        "slots must be an array of 1..8 entries (slot k = entry k; null leaves it empty).");
            List<SlotSpec> slots = new ArrayList<>();
            Set<String> pairs = new HashSet<>();
            for (JsonElement element : array.getAsJsonArray()) {
                if (element.isJsonNull()) {
                    slots.add(null);
                    continue;
                }
                if (!element.isJsonObject()) throw failure("invalid_params", "Each slot must be an object or null.");
                JsonObject slot = element.getAsJsonObject();
                for (String key : slot.keySet()) {
                    if (!Set.of("moduleId", "moduleName", "parameterId", "label")
                            .contains(key)) throw failure("invalid_params", "Unknown slot field: " + key);
                    if (slot.get(key).isJsonNull())
                        throw failure("invalid_params", "slot " + key + " must not be null.");
                }
                if (slot.has("moduleId") == slot.has("moduleName"))
                    throw failure("invalid_params", "Each slot needs exactly one of moduleId or moduleName.");
                UUID moduleId = null;
                String moduleName = null;
                if (slot.has("moduleId")) {
                    String id = text(slot, "moduleId", 0, 36);
                    if (!UUID_PATTERN.matcher(id).matches())
                        throw failure("invalid_params", "slot moduleId must be a canonical UUID.");
                    moduleId = UUID.fromString(id);
                } else moduleName = text(slot, "moduleName", 1, GridSnapshotReader.MAX_TEXT);
                if (!slot.has("parameterId")) throw failure("invalid_params", "Each slot needs parameterId.");
                String parameterId = text(slot, "parameterId", 0, GridSnapshotReader.MAX_TEXT);
                String label = slot.has("label") ? text(slot, "label", 0, MAX_LABEL) : "";
                String pair = (moduleId != null ? "id:" + moduleId : "name:" + moduleName) + "\n" + parameterId;
                if (!pairs.add(pair)) throw failure("invalid_params", "slots must not repeat a module/parameter pair.");
                slots.add(new SlotSpec(moduleId, moduleName, parameterId, label));
            }
            return new Request(name, replace, slots);
        }

        static String pageName(JsonObject p) {
            return text(p, "pageName", 1, MAX_PAGE_NAME);
        }

        private static String text(JsonObject p, String key, int min, int max) {
            JsonElement value = p.get(key);
            if (value == null
                    || !value.isJsonPrimitive()
                    || !value.getAsJsonPrimitive().isString()
                    || value.getAsString().length() < min
                    || value.getAsString().length() > max)
                throw failure("invalid_params", key + " must be a string of " + min + ".." + max + " UTF-16 units.");
            return value.getAsString();
        }
    }
}
