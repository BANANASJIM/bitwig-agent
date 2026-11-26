package dev.bitwigagent;

import com.google.gson.*;
import dev.bitwigagent.core.Refs;
import java.util.*;

/**
 * One user Remote Controls page in a Grid device's preset-page list (the list the UI's "Add New Page" uses; device
 * pages refuse Grid module parameters): define (create, or change the page of that name) its eight slots, or delete it. Each native
 * step is its own undo-aware model change; there is no outer group, retry or rollback. Device, module and plugin
 * pages are listed for name conflicts but never edited.
 */
final class GridRemotePageWriter {
    static final int SLOTS = HostLimits.LIMITS.remotePageSlots;
    private static final Set<GridSnapshotReader.Kind> SCALARS = EnumSet.of(
            GridSnapshotReader.Kind.FLOAT,
            GridSnapshotReader.Kind.BOOLEAN,
            GridSnapshotReader.Kind.INTEGER,
            GridSnapshotReader.Kind.ENUM);

    interface Access {
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

    JsonObject define(DeviceSources.Source source, Request request, Runnable check, Runnable dispatch) {
        GridSnapshotReader.NativeTarget target = values.bindTarget(source, check);
        Object device = target.target;
        Host host = host(device);
        Slot[] wanted = resolve(source, target, device, request, check);
        Object existing = existing(host, request.pageName);
        if (existing != null)
            for (Slot slot : wanted)
                if (slot != null && !nativeAccess.eligible(existing, slot.binding.parameter))
                    throw Errors.notApplied(
                            Reason.UNSUPPORTED_SCOPE,
                            "Remote Controls page refuses " + slot.describe() + " (native page eligibility).");
        GridParameterContext.Capture context =
                contexts.requireRemoteWritable(device, host.host, host.pages, null, check);
        values.guardTarget(target);
        for (Slot slot : wanted) if (slot != null) values.guardParameter(slot.binding);
        if (existing != existing(host, request.pageName))
            throw Errors.notApplied(Reason.TARGET_CHANGED, "The named Remote Controls page changed before dispatch.");
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
                    throw Errors.unknown(
                            Reason.READBACK_MISMATCH,
                            "Native page creation returned no page in the preset-page list; inspect devices.remote.get.");
                check.run();
                nativeAccess.renamePage(page, request.pageName);
                operations.add(operation("rename", -1));
                // The page exists now: its own predicate is authoritative before any slot is mapped.
                for (Slot slot : wanted)
                    if (slot != null && !nativeAccess.eligible(page, slot.binding.parameter))
                        throw Errors.unknown(
                                Reason.UNSUPPORTED_SCOPE,
                                "The new page refuses " + slot.describe()
                                        + "; it was created empty and named. Remove it with devices.remote.delete.");
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
                    if (sameTarget && slot.spec.labelMatches(nativeAccess.slotLabel(current))) continue;
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
                    nativeAccess.mapSlot(
                            page, index, slot.spec.label == null ? "" : slot.spec.label, slot.binding.parameter);
                    operations.add(operation("map", index));
                }
            }
        } catch (RuntimeException error) {
            if (!dispatched) throw error;
            if (error instanceof RpcException && ((RpcException) error).unknown) throw error;
            RpcException unknown = Errors.unknown(
                    error instanceof RpcException ? ((RpcException) error).reason : Reason.NATIVE_UNAVAILABLE,
                    "Remote Controls page edit failed after dispatch (" + operations.size() + " native step(s) done): "
                            + error.getMessage() + " Inspect devices.remote.get before another write.");
            unknown.initCause(error);
            throw unknown;
        }
        check.run();
        try {
            values.guardTarget(target);
            contexts.guardRemote(context, device, host.host, host.pages, check);
            verify(host, page, request, wanted);
        } catch (RpcException error) {
            if (!dispatched) throw error;
            throw Errors.unknown(
                    error.reason, error.getMessage() + " Inspect devices.remote.get before another write.");
        }
        check.run();
        JsonObject result = Results.outcome(dispatched ? Outcome.APPLIED : Outcome.ALREADY_MATCHED);
        result.add("page", pageJson(host, page));
        int shown = nativeAccess.visiblePages(host.host).indexOf(page);
        if (shown >= 0) result.add("ref", Refs.remotePage(source.ref(), shown, request.pageName));
        result.addProperty("created", created);
        JsonArray slots = new JsonArray();
        for (int index = 0; index < SLOTS; index++)
            slots.add(
                    wanted[index] == null
                            ? JsonNull.INSTANCE
                            : wanted[index].json(nativeAccess.slotLabel(nativeAccess.slot(page, index))));
        result.add("slots", slots);
        result.add("operations", operations);
        result.add("pages", visibleNames(host));
        return finish(result, dispatched, "exact_native_remote_page_readback");
    }

    JsonObject deletePage(DeviceSources.Source source, JsonObject ref, Runnable check, Runnable dispatch) {
        String pageName = ref.get("name").getAsString();
        GridSnapshotReader.NativeTarget target = values.bindTarget(source, check);
        Host host = host(target.target);
        Object page = deletable(host, ref);
        int shown = nativeAccess.visiblePages(host.host).indexOf(page);
        if (shown != ref.get("index").getAsInt())
            throw Errors.targetChanged(
                    "The page is at another index than the one read.",
                    shown < 0 ? null : Refs.remotePage(source.ref(), shown, pageName));
        GridParameterContext.Capture context =
                contexts.requireRemoteWritable(target.target, host.host, host.pages, null, check);
        values.guardTarget(target);
        if (deletable(host, ref) != page)
            throw Errors.notApplied(Reason.TARGET_CHANGED, "The named Remote Controls page changed before dispatch.");
        contexts.requireRemoteWritable(target.target, host.host, host.pages, context, check);
        check.run();
        dispatch.run();
        try {
            nativeAccess.deletePage(host.pages, page);
            check.run();
            values.guardTarget(target);
            contexts.guardRemote(context, target.target, host.host, host.pages, check);
            if (nativeAccess.pages(host.pages).contains(page)
                    || nativeAccess.allPages(host.host).contains(page))
                throw Errors.unknown(Reason.READBACK_MISMATCH, "The page is still listed after the native delete.");
        } catch (RpcException error) {
            if (error.unknown) throw error;
            throw Errors.unknown(
                    error.reason, error.getMessage() + " Inspect devices.remote.get before another write.");
        } catch (RuntimeException error) {
            RpcException unknown = Errors.unknown(
                    Reason.NATIVE_UNAVAILABLE, "Native page delete failed after dispatch; inspect devices.remote.get.");
            unknown.initCause(error);
            throw unknown;
        }
        check.run();
        JsonObject result = Results.outcome(Outcome.APPLIED);
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
            throw Errors.notApplied(
                    Reason.NATIVE_UNAVAILABLE, "Native Remote Controls host is unavailable for this device.");
        values.requireModelThread(host);
        Object pages = nativeAccess.presetPages(host), devicePages = nativeAccess.devicePages(host);
        if (pages == null || devicePages == null)
            throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "Native Remote Controls page lists are unavailable.");
        values.requireModelThread(pages);
        if (!nativeAccess.licensed(device))
            throw Errors.notApplied(
                    Reason.UNSAFE_STATE,
                    "Device is not licensed: Bitwig does not create Remote Controls pages for it.");
        return new Host(host, pages, devicePages);
    }

    /**
     * The one preset page named name, or null when none exists. A same-named page of another kind (device, module,
     * plugin) is a conflict; several preset pages of that name are ambiguous.
     */
    private Object existing(Host host, String name) {
        Named named = named(host, name);
        if (named.otherKind != null)
            throw Errors.notApplied(
                    Reason.TARGET_EXISTS,
                    "A " + named.otherKind + " Remote Controls page is already named " + quote(name)
                            + "; only preset pages are edited here. Choose another name.");
        return named.page;
    }

    private Object deletable(Host host, JsonObject ref) {
        String name = ref.get("name").getAsString();
        Named named = named(host, name);
        if (named.page == null && named.otherKind != null)
            throw Errors.notApplied(
                    Reason.UNSUPPORTED_SCOPE,
                    "The page named " + quote(name) + " is a " + named.otherKind
                            + " page; only preset pages can be deleted here.");
        if (named.page == null)
            throw Errors.targetNotFound(ref, "No preset Remote Controls page is named " + quote(name) + ".");
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
            throw Errors.notApplied(
                    Reason.AMBIGUOUS_NAME,
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
            throw Errors.notApplied(Reason.READBACK_MISMATCH, "The defined page is no longer in the preset-page list.");
        if (!request.pageName.equals(displayName(host, page, "preset")))
            throw Errors.notApplied(
                    Reason.READBACK_MISMATCH, "Native page name readback differs from the requested pageName.");
        if (!nativeAccess.visiblePages(host.host).contains(page))
            throw Errors.notApplied(
                    Reason.READBACK_MISMATCH, "The defined page is not in the device's visible page list.");
        for (int index = 0; index < SLOTS; index++) {
            Object slot = nativeAccess.slot(page, index);
            Slot want = wanted[index];
            if (want == null
                    ? slot != null
                    : slot == null
                            || nativeAccess.slotTarget(slot) != want.binding.parameter
                            || !want.spec.labelMatches(nativeAccess.slotLabel(slot)))
                throw Errors.notApplied(
                        Reason.READBACK_MISMATCH, "Native slot " + index + " readback differs from the request.");
        }
    }

    // ---- slot resolution -------------------------------------------------------------------------------------

    private Slot[] resolve(
            DeviceSources.Source source,
            GridSnapshotReader.NativeTarget target,
            Object device,
            Request request,
            Runnable check) {
        Slot[] wanted = new Slot[SLOTS];
        IdentityHashMap<Object, Integer> seen = new IdentityHashMap<>();
        for (int index = 0; index < request.slots.size(); index++) {
            SlotSpec spec = request.slots.get(index);
            if (spec == null) continue;
            if (!Refs.matches(spec.device, source.ref(), false))
                throw Errors.notApplied(
                        Reason.UNSUPPORTED_SCOPE, "slot " + index + " names a parameter of another device.");
            GridSnapshotReader.ParameterBinding binding =
                    values.bindParameter(source, spec.moduleId, spec.parameterId, check);
            Slot slot = new Slot(index, spec, binding);
            if (!SCALARS.contains(binding.kind))
                throw Errors.notApplied(
                        Reason.UNSUPPORTED_SCOPE,
                        slot.describe() + " is a " + binding.kind.name().toLowerCase(Locale.ROOT)
                                + " control; only scalar Grid parameters map to Remote Controls.");
            if (!nativeAccess.mappable(binding.parameter, device))
                throw Errors.notApplied(
                        Reason.UNSUPPORTED_SCOPE,
                        slot.describe() + " is not a remote-mappable parameter of this device.");
            Integer previous = seen.put(binding.parameter, index);
            if (previous != null)
                throw Errors.notApplied(
                        Reason.INVALID_PARAMS, "slots " + previous + " and " + index + " name the same parameter.");
            wanted[index] = slot;
        }
        return wanted;
    }

    private static final class Slot {
        final int index;
        final SlotSpec spec;
        final GridSnapshotReader.ParameterBinding binding;

        Slot(int index, SlotSpec spec, GridSnapshotReader.ParameterBinding binding) {
            this.index = index;
            this.spec = spec;
            this.binding = binding;
        }

        String describe() {
            return "slot " + index + " (" + spec.moduleId + "/" + quote(spec.parameterId) + ")";
        }

        /** The slot as read back: its parameter and the label it now has, empty meaning Bitwig's default. */
        JsonObject json(String label) {
            JsonObject result = new JsonObject();
            result.add("parameter", spec.ref);
            if (label != null && !label.isEmpty()) result.addProperty("label", label);
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

    /** One slot: the gridModule-owned parameter ref, and the module and parameter it names. */
    static final class SlotSpec {
        final JsonObject ref, device;
        final UUID moduleId;
        final String parameterId, label;

        /** {@code label} is null when the request leaves it out: Bitwig's default, and an existing label is kept. */
        SlotSpec(JsonObject ref, String label) {
            this.ref = ref;
            this.label = label;
            JsonObject owner = ref.getAsJsonObject("owner");
            this.device = owner.getAsJsonObject("device");
            this.moduleId = UUID.fromString(owner.get("id").getAsString());
            this.parameterId = ref.get("id").getAsString();
        }

        boolean labelMatches(String actual) {
            return label == null || label.equals(actual);
        }
    }

    /** devices.remote.define: the page name and its slots (1..SLOTS, slot k = entry k, null = empty). */
    static final class Request {
        final String pageName;
        final List<SlotSpec> slots;

        private Request(String pageName, List<SlotSpec> slots) {
            this.pageName = pageName;
            this.slots = slots;
        }

        static Request parse(JsonObject p) {
            List<SlotSpec> slots = new ArrayList<>();
            Set<String> pairs = new HashSet<>();
            for (JsonElement element : p.getAsJsonArray("slots")) {
                if (element.isJsonNull()) {
                    slots.add(null);
                    continue;
                }
                JsonObject entry = element.getAsJsonObject(), ref = entry.getAsJsonObject("parameter");
                if (!"gridModule".equals(Refs.kind(ref.getAsJsonObject("owner"))))
                    throw Errors.notApplied(
                            Reason.UNSUPPORTED_SCOPE, "A page slot takes a parameter owned by a Grid module.");
                SlotSpec slot = new SlotSpec(
                        ref, entry.has("label") ? entry.get("label").getAsString() : null);
                if (!pairs.add(slot.moduleId + "\n" + slot.parameterId))
                    throw Errors.notApplied(Reason.INVALID_PARAMS, "slots must not repeat a module/parameter pair.");
                slots.add(slot);
            }
            return new Request(p.get("name").getAsString(), slots);
        }

        /** devices.remote.delete: the remotePage ref of the page to delete. */
        static JsonObject page(JsonObject p) {
            JsonObject ref = p.getAsJsonObject("target");
            if (!"remotePage".equals(Refs.kind(ref))) throw Errors.invalid("target must be a remotePage ref.");
            return ref;
        }
    }
}
