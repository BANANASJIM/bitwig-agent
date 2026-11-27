package dev.bitwigagent.grid;

import static dev.bitwigagent.RpcAssert.*;
import static dev.bitwigagent.grid.GridSnapshotReader.Field.*;
import static dev.bitwigagent.grid.GridSnapshotReader.Kind.*;
import static dev.bitwigagent.grid.GridSnapshotReaderTest.ContextFixture;
import static dev.bitwigagent.grid.GridSnapshotReaderTest.Fixture;
import static dev.bitwigagent.grid.GridSnapshotReaderTest.Node;
import static dev.bitwigagent.host.DeviceSources.Native.Field.TARGET;
import static dev.bitwigagent.host.DeviceSources.Native.Kind.DEVICE;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import dev.bitwigagent.HostFixture;
import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.HostOperation;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.core.Refs;
import dev.bitwigagent.core.RpcException;
import dev.bitwigagent.device.DeviceModule;
import dev.bitwigagent.host.NativeReadContext;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** Remote Controls page define/delete semantics on fake models; not evidence of native behavior. */
public final class GridRemotePageWriterTest {
    private static final UUID TRACK_ID = new UUID(3, 1), FIRST = new UUID(1, 1), SECOND = new UUID(1, 2);
    private static final Map<String, UUID> MODULES = Map.of("Mx: darkness", FIRST, "Mx: grit", SECOND);

    // ---- fake native page model ------------------------------------------------------------------------------

    private static final class Page {
        String name;
        final Object list, owner;
        final TreeMap<Integer, Slot> slots = new TreeMap<>();

        Page(String name, Object list, Object owner) {
            this.name = name;
            this.list = list;
            this.owner = owner;
        }
    }

    private static final class Slot {
        final Object target;
        final String label;

        Slot(Object target, String label) {
            this.target = target;
            this.label = label;
        }
    }

    private static final class RemoteFixture implements GridRemotePageWriter.Access {
        final Fixture model = new Fixture();
        final ContextFixture context = new ContextFixture();
        final Node host = new Node(), editedPages = new Node(), otherPages = new Node();
        final List<Page> others = new ArrayList<>(), pages = new ArrayList<>(), modulePages = new ArrayList<>();
        final Node darkness, grit, panel;
        final Set<Object> unmappable = Collections.newSetFromMap(new IdentityHashMap<>());
        final Set<Object> ineligible = Collections.newSetFromMap(new IdentityHashMap<>());
        final List<String> calls = new ArrayList<>();
        HostOperation op = new HostOperation(Long.MAX_VALUE);
        final GridRemotePageWriter writer;
        boolean licensed = true, visible = true, apply = true, createNothing, listsMissing;
        Runnable afterCall = () -> {};

        RemoteFixture() {
            context.values.put(GridParameterContext.Field.LOCK_OWNER, context.dt);
            Supplier<Object> forbidden = () -> {
                throw new AssertionError("Remote pages used scalar permission or a GUI property");
            };
            for (GridParameterContext.Field field : List.of(
                    GridParameterContext.Field.LICENSED,
                    GridParameterContext.Field.TOUCHED,
                    GridParameterContext.Field.SETTABLE,
                    GridParameterContext.Field.GUI_FLOAT)) context.values.put(field, forbidden);
            model.first.put(MODULE_NAME, "Mx: darkness");
            model.second.put(MODULE_NAME, "Mx: grit");
            darkness = model.parameter(FLOAT, model.first).put(PARAMETER_ID, "VALUE");
            grit = model.parameter(FLOAT, model.second).put(PARAMETER_ID, "VALUE");
            panel = new Node(PANEL_BOOLEAN).put(OWNER_MODULE, model.second).put(PARAMETER_ID, "BIPOLAR");
            model.first.put(CHILDREN, List.of(darkness));
            model.second.put(CHILDREN, List.of(grit, panel));
            modulePages.add(new Page("", new Object(), model.first));
            writer = new GridRemotePageWriter(model.reader, context.reader, this);
        }

        /** Requests are written as pageName and {moduleId|moduleName, parameterId} slots; this makes them parameter refs. */
        GridRemotePageWriter.Request request(String json) {
            JsonObject given = HostFixture.json(json), out = new JsonObject();
            out.addProperty("name", given.get("pageName").getAsString());
            JsonArray slots = new JsonArray();
            for (JsonElement element : given.getAsJsonArray("slots")) {
                if (element.isJsonNull()) {
                    slots.add(element);
                    continue;
                }
                JsonObject slot = element.getAsJsonObject();
                String id = slot.has("moduleId")
                        ? slot.get("moduleId").getAsString()
                        : MODULES.getOrDefault(slot.get("moduleName").getAsString(), new UUID(9, 9))
                                .toString();
                JsonObject entry = new JsonObject();
                entry.add(
                        "parameter",
                        Refs.parameter(
                                Refs.gridModule(model.source.ref(), id, "module"),
                                slot.get("parameterId").getAsString(),
                                null));
                if (slot.has("label")) entry.add("label", slot.get("label"));
                slots.add(entry);
            }
            out.add("slots", slots);
            return GridRemotePageWriter.Request.parse(out);
        }

        JsonObject define(String json) {
            return writer.define(model.source, request(json), () -> {}, () -> op.markDispatched());
        }

        JsonObject delete(String name, int index) {
            return writer.deletePage(
                    model.source,
                    Refs.remotePage(model.source.ref(), index, name),
                    () -> {},
                    () -> op.markDispatched());
        }

        RpcException defineFailure(String json) {
            {
                RpcException error = assertThrows(RpcException.class, () -> define(json));
                return error;
            }
        }

        RpcException deleteFailure(String name) {
            return assertThrows(RpcException.class, () -> delete(name, 0));
        }

        Page userPage(String name) {
            Page page = new Page(name, editedPages, host);
            pages.add(page);
            return page;
        }

        private void call(String name) {
            calls.add(name);
            afterCall.run();
        }

        @Override
        public Object host(Object device) {
            assertTrue(device == model.device, "the host belongs to the bound device");
            return host;
        }

        @Override
        public Object presetPages(Object host) {
            return listsMissing ? null : editedPages;
        }

        /** The named page is replaced by a same-named one while the write context is read. */
        void replacedDuringContextRead(String name) {
            Object undo = new Object();
            boolean[] replaced = {false};
            context.values.put(GridParameterContext.Field.UNDO, (Supplier<Object>) () -> {
                if (!replaced[0]) {
                    replaced[0] = true;
                    pages.removeIf(page -> page.name.equals(name));
                    userPage(name);
                }
                return undo;
            });
        }

        @Override
        public Object devicePages(Object host) {
            return otherPages;
        }

        @Override
        public List<Object> allPages(Object host) {
            List<Object> all = new ArrayList<>(others);
            all.addAll(pages);
            all.addAll(modulePages);
            return all;
        }

        @Override
        public List<Object> visiblePages(Object host) {
            return visible ? allPages(host) : new ArrayList<>(others);
        }

        @Override
        public List<Object> pages(Object list) {
            return new ArrayList<>(list == editedPages ? pages : others);
        }

        @Override
        public Object pageList(Object page) {
            return ((Page) page).list;
        }

        @Override
        public Object pageOwner(Object page) {
            return ((Page) page).owner;
        }

        @Override
        public String pageName(Object page) {
            return ((Page) page).name;
        }

        @Override
        public boolean licensed(Object device) {
            return licensed;
        }

        @Override
        public boolean mappable(Object parameter, Object device) {
            return device == model.device && !unmappable.contains(parameter);
        }

        @Override
        public boolean eligible(Object page, Object parameter) {
            return !ineligible.contains(parameter);
        }

        @Override
        public Object slot(Object page, int index) {
            return ((Page) page).slots.get(index);
        }

        @Override
        public Object slotTarget(Object slot) {
            return ((Slot) slot).target;
        }

        @Override
        public String slotLabel(Object slot) {
            return ((Slot) slot).label;
        }

        @Override
        public Object createPage(Object list) {
            assertTrue(list == editedPages, "pages are created in the preset-page list");
            call("create");
            if (createNothing) return null;
            Page page = new Page("", editedPages, host);
            pages.add(page);
            return page;
        }

        @Override
        public void renamePage(Object page, String name) {
            call("rename");
            if (apply) ((Page) page).name = name;
        }

        @Override
        public void mapSlot(Object page, int index, String label, Object parameter) {
            call("map" + index);
            Slot current = ((Page) page).slots.get(index);
            // Native: an unchanged target is a no-op, label included.
            if (apply && (current == null || current.target != parameter))
                ((Page) page).slots.put(index, new Slot(parameter, label));
        }

        @Override
        public void clearSlot(Object page, int index) {
            call("clear" + index);
            if (apply) ((Page) page).slots.remove(index);
        }

        @Override
        public void deletePage(Object list, Object page) {
            call("delete");
            if (apply) pages.remove(page);
        }
    }

    private static final String TWO =
            "{\"pageName\":\"Macros\",\"slots\":[{\"moduleName\":\"Mx: darkness\",\"parameterId\":\"VALUE\",\"label\":\"darkness\"},"
                    + "{\"moduleId\":\"" + SECOND + "\",\"parameterId\":\"VALUE\"}]}";

    @Test
    void createsANamedPageAndMapsSlots() {
        RemoteFixture f = new RemoteFixture();
        f.others.add(new Page("Other", f.otherPages, f.host));
        JsonObject result = f.define(TWO);
        Page page = f.pages.get(0);
        assertTrue(
                f.calls.equals(List.of("create", "rename", "map0", "map1")) && f.op.dispatched(),
                "create, name, then map each slot in order");
        assertTrue(
                page.name.equals("Macros")
                        && page.slots.get(0).target == f.darkness
                        && page.slots.get(0).label.equals("darkness")
                        && page.slots.get(1).target == f.grit
                        && page.slots.get(1).label.isEmpty()
                        && page.slots.size() == 2,
                "slots hold the bound native parameters and labels");
        assertTrue(
                result.get("created").getAsBoolean()
                        && result.get("verification").getAsString().equals("native_readback")
                        && result.getAsJsonObject("page")
                                .get("name")
                                .getAsString()
                                .equals("Macros")
                        && result.getAsJsonObject("page").get("pageIndex").getAsInt() == 1
                        && result.getAsJsonObject("page").get("visible").getAsBoolean()
                        && result.getAsJsonArray("operations").size() == 4
                        && result.get("nativeUndo").getAsBoolean()
                        && !result.get("undoGroupingVerified").getAsBoolean(),
                "result reports the visible page index, operations and undo scope");
        JsonArray slots = result.getAsJsonArray("slots");
        assertTrue(
                slots.size() == 8
                        && slots.get(0)
                                .getAsJsonObject()
                                .getAsJsonObject("parameter")
                                .getAsJsonObject("owner")
                                .get("id")
                                .getAsString()
                                .equals(FIRST.toString())
                        && slots.get(2).isJsonNull(),
                "slots echo the parameter refs they hold; unnamed slots are null");
        JsonObject ref = result.getAsJsonObject("ref");
        assertTrue(
                ref.get("kind").getAsString().equals("remotePage")
                        && ref.get("index").getAsInt() == 1
                        && ref.get("name").getAsString().equals("Macros"),
                "the result carries the ref of the page, at its visible index");
        JsonArray names = result.getAsJsonArray("pages");
        assertTrue(
                names.size() == 3
                        && names.get(0).getAsString().equals("Other")
                        && names.get(1).getAsString().equals("Macros")
                        && names.get(2).getAsString().equals("Mx: darkness"),
                "visible page names show module pages by module name");
        RemoteFixture empty = new RemoteFixture();
        JsonObject named = empty.define("{\"pageName\":\"Empty\",\"slots\":[null]}");
        assertTrue(
                empty.calls.equals(List.of("create", "rename"))
                        && named.getAsJsonArray("slots").get(0).isJsonNull(),
                "a page may be created empty");
    }

    @Test
    void conflictsAreRefusedBeforeDispatch() {
        RemoteFixture f = new RemoteFixture();
        f.userPage("Macros");
        JsonObject upserted = f.define(TWO);
        assertTrue(
                !upserted.get("created").getAsBoolean() && !f.calls.contains("create"),
                "an existing preset page of that name is changed, not refused");
        RpcException error;
        RemoteFixture preset = new RemoteFixture();
        preset.others.add(new Page("Macros", preset.otherPages, preset.host));
        error = preset.defineFailure(TWO);
        assertTrue(
                error.reason.wire().equals("target_exists")
                        && error.getMessage().contains("device")
                        && preset.calls.isEmpty(),
                "a same-named device page is never replaced");
        RemoteFixture module = new RemoteFixture();
        error = module.defineFailure(TWO.replace("\"pageName\":\"Macros\"", "\"pageName\":\"Mx: darkness\""));
        assertTrue(
                error.reason.wire().equals("target_exists")
                        && error.getMessage().contains("module")
                        && module.calls.isEmpty(),
                "a name shown by a Grid module page conflicts");
        RemoteFixture twice = new RemoteFixture();
        twice.userPage("Macros");
        twice.userPage("Macros");
        error = twice.defineFailure(TWO);
        assertTrue(
                error.reason.wire().equals("ambiguous_name") && twice.calls.isEmpty(),
                "several same-named preset pages are ambiguous");
    }

    @Test
    void replaceEditsOnlyDifferingSlots() {
        RemoteFixture f = new RemoteFixture();
        Page page = f.userPage("Macros");
        page.slots.put(0, new Slot(f.darkness, "darkness"));
        page.slots.put(1, new Slot(f.darkness, "old"));
        page.slots.put(3, new Slot(f.grit, ""));
        JsonObject result = f.define("{\"pageName\":\"Macros\",\"slots\":[{\"moduleId\":\"" + FIRST
                + "\",\"parameterId\":\"VALUE\"},{\"moduleName\":\"Mx: grit\",\"parameterId\":\"VALUE\"}]}");
        assertTrue(
                f.calls.equals(List.of("map1", "clear3"))
                        && !result.get("created").getAsBoolean()
                        && page.slots.size() == 2
                        && page.slots.get(1).target == f.grit
                        && result.get("verification").getAsString().equals("native_readback"),
                "replace maps changed slots and clears slots the request leaves empty, keeping matching ones");
        f.calls.clear();
        f.op = new HostOperation(Long.MAX_VALUE);
        result = f.define("{\"pageName\":\"Macros\",\"slots\":[{\"moduleId\":\"" + FIRST
                + "\",\"parameterId\":\"VALUE\"},{\"moduleName\":\"Mx: grit\",\"parameterId\":\"VALUE\"}]}");
        assertTrue(
                f.calls.isEmpty()
                        && !f.op.dispatched()
                        && !result.get("mutationDispatched").getAsBoolean()
                        && result.get("outcome").getAsString().equals("already_matched"),
                "an identical replace is a verified no-op");
        page.slots.put(0, new Slot(f.darkness, "old"));
        f.calls.clear();
        String labelled = "{\"pageName\":\"Macros\",\"slots\":[{\"moduleId\":\"" + FIRST
                + "\",\"parameterId\":\"VALUE\",\"label\":\"darkness\"},{\"moduleName\":\"Mx: grit\",\"parameterId\":\"VALUE\"}]}";
        f.define(labelled);
        assertTrue(
                f.calls.equals(List.of("clear0", "map0"))
                        && page.slots.get(0).label.equals("darkness"),
                "a label-only change clears and remaps the slot (the native map ignores an unchanged target)");
        f.calls.clear();
        JsonObject kept = f.define(labelled.replace(",\"label\":\"darkness\"", ""));
        assertTrue(
                kept.getAsJsonArray("slots")
                        .get(0)
                        .getAsJsonObject()
                        .get("label")
                        .getAsString()
                        .equals("darkness"),
                "the readback shows the label that stays");
        assertTrue(
                f.calls.isEmpty() && page.slots.get(0).label.equals("darkness"),
                "a slot left without a label keeps the one it has");
        page.slots.put(0, new Slot(f.grit, ""));
        f.define(labelled.replace(",\"label\":\"darkness\"", ""));
        assertTrue(
                f.calls.equals(List.of("map0")) && page.slots.get(0).target == f.darkness,
                "a slot that holds another parameter is mapped to the requested one");
        RemoteFixture upsert = new RemoteFixture();
        result = upsert.define(TWO);
        assertTrue(
                result.get("created").getAsBoolean() && upsert.calls.get(0).equals("create"),
                "replace creates the page when none exists");
        RemoteFixture refused = new RemoteFixture();
        refused.userPage("Macros");
        refused.ineligible.add(refused.grit);
        RpcException error = refused.defineFailure(TWO);
        assertTrue(
                error.reason.wire().equals("unsupported_scope") && !error.unknown && refused.calls.isEmpty(),
                "an existing page's eligibility is checked before dispatch");
    }

    @Test
    void slotResolutionFailures() {
        RemoteFixture f = new RemoteFixture();
        assertTrue(
                f.defineFailure("{\"pageName\":\"P\",\"slots\":[{\"moduleName\":\"Nope\",\"parameterId\":\"VALUE\"}]}")
                        .reason
                        .wire()
                        .equals("target_not_found"),
                "an unknown module name is not found");
        RpcException error;
        RemoteFixture g = new RemoteFixture();
        assertTrue(
                g.defineFailure("{\"pageName\":\"P\",\"slots\":[{\"moduleId\":\"" + FIRST
                                + "\",\"parameterId\":\"MISSING\"}]}")
                        .reason
                        .wire()
                        .equals("target_not_found"),
                "a missing parameter is not found");
        error = g.defineFailure(
                "{\"pageName\":\"P\",\"slots\":[{\"moduleId\":\"" + SECOND + "\",\"parameterId\":\"BIPOLAR\"}]}");
        assertTrue(
                error.reason.wire().equals("unsupported_scope")
                        && error.getMessage().contains("scalar"),
                "boolean panels are not remote-mappable");
        g.unmappable.add(g.grit);
        assertTrue(
                g.defineFailure("{\"pageName\":\"P\",\"slots\":[{\"moduleId\":\"" + SECOND
                                + "\",\"parameterId\":\"VALUE\"}]}")
                        .reason
                        .wire()
                        .equals("unsupported_scope"),
                "a parameter outside the device or not mappable is refused");
        error = g.defineFailure("{\"pageName\":\"P\",\"slots\":[{\"moduleId\":\"" + FIRST
                + "\",\"parameterId\":\"VALUE\"}," + "{\"moduleName\":\"Mx: darkness\",\"parameterId\":\"VALUE\"}]}");
        assertTrue(
                error.reason.wire().equals("invalid_params") && g.calls.isEmpty(),
                "the same parameter in two slots is refused after resolution");
    }

    @Test
    void licenseAndContextGates() {
        RemoteFixture f = new RemoteFixture();
        f.licensed = false;
        assertTrue(
                f.defineFailure(TWO).reason.wire().equals("unsafe_state") && f.calls.isEmpty(),
                "an unlicensed device is refused");
        RemoteFixture undo = new RemoteFixture();
        undo.context.values.put(GridParameterContext.Field.UNDO_ACTIVE, true);
        assertTrue(
                undo.defineFailure(TWO).reason.wire().equals("unsafe_state") && undo.calls.isEmpty(),
                "an active undo change gates the write");
        RemoteFixture skipped = new RemoteFixture();
        skipped.context.override(skipped.model.device, GridParameterContext.Field.CIU, true);
        assertTrue(
                skipped.defineFailure(TWO).reason.wire().equals("unsafe_state") && skipped.calls.isEmpty(),
                "the device skip predicate gates the write");
        RemoteFixture project = new RemoteFixture();
        project.context.override(project.editedPages, NativeReadContext.Field.PROJECT, new Object());
        assertTrue(
                project.defineFailure(TWO).reason.wire().equals("target_changed") && project.calls.isEmpty(),
                "a page list of another project is refused");
        RemoteFixture lock = new RemoteFixture();
        lock.context.values.put(GridParameterContext.Field.LOCK_OWNER, new Object());
        assertTrue(
                lock.defineFailure(TWO).reason.wire().equals("unsafe_state") && lock.calls.isEmpty(),
                "a foreign device lock owner gates the write");
        RemoteFixture uninitialized = new RemoteFixture();
        uninitialized.context.values.put(NativeReadContext.Field.INITIALIZED, false);
        assertRpcError("unsafe_state", () -> uninitialized.define(TWO));
        RemoteFixture rooted = new RemoteFixture();
        rooted.context.values.put(NativeReadContext.Field.ROOT, new Object());
        assertRpcError("target_changed", () -> rooted.define(TWO));
        RemoteFixture thread = new RemoteFixture();
        thread.editedPages.threadAllowed = false;
        assertTrue(
                thread.defineFailure(TWO).reason.wire().equals("native_unavailable") && thread.calls.isEmpty(),
                "the model thread predicate gates the page list");
    }

    @Test
    void failuresAfterDispatchAreUnknown() {
        RemoteFixture thrown = new RemoteFixture();
        thrown.afterCall = () -> {
            if (thrown.calls.size() == 3) throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "native map failure");
        };
        RpcException error = thrown.defineFailure(TWO);
        assertTrue(
                error.unknown && thrown.calls.equals(List.of("create", "rename", "map0")) && thrown.op.dispatched(),
                "a native failure after dispatch is unknown and nothing is retried or rolled back");
        RemoteFixture unread = new RemoteFixture();
        unread.apply = false;
        error = unread.defineFailure(TWO);
        assertTrue(
                error.reason.wire().equals("readback_mismatch") && error.unknown, "an unobserved page name is unknown");
        RemoteFixture none = new RemoteFixture();
        none.createNothing = true;
        error = none.defineFailure(TWO);
        assertTrue(
                error.reason.wire().equals("readback_mismatch")
                        && error.unknown
                        && none.calls.equals(List.of("create")),
                "a native create that yields no page is unknown");
        RemoteFixture refused = new RemoteFixture();
        refused.ineligible.add(refused.grit);
        error = refused.defineFailure(TWO);
        assertTrue(
                error.reason.wire().equals("unsupported_scope")
                        && error.unknown
                        && error.getMessage().contains("devices.remote.delete")
                        && refused.calls.equals(List.of("create", "rename")),
                "a new page refusing a slot stops before mapping and says how to clean up");
        RemoteFixture hidden = new RemoteFixture();
        hidden.afterCall = () -> {
            if (hidden.calls.size() == 4) hidden.visible = false;
        };
        error = hidden.defineFailure(TWO);
        assertTrue(
                error.reason.wire().equals("readback_mismatch") && error.unknown,
                "a page missing from the visible list fails verification");
    }

    @Test
    void pageChangesAroundDispatchAreCaught() {
        RemoteFixture lists = new RemoteFixture();
        lists.listsMissing = true;
        assertRpcError("native_unavailable", () -> lists.define(TWO));
        RemoteFixture replaced = new RemoteFixture();
        replaced.userPage("Macros");
        replaced.replacedDuringContextRead("Macros");
        assertRpcError("target_changed", () -> replaced.define(TWO));
        RemoteFixture deleted = new RemoteFixture();
        deleted.userPage("Macros");
        deleted.replacedDuringContextRead("Macros");
        assertRpcError("target_changed", () -> deleted.delete("Macros", 1));
        assertTrue(replaced.calls.isEmpty() && deleted.calls.isEmpty(), "a page changed before dispatch is untouched");
        RemoteFixture moved = new RemoteFixture();
        moved.userPage("Macros");
        moved.afterCall = () -> moved.model.proxy.put(TARGET, new Node(DEVICE));
        assertTrue(moved.deleteFailure("Macros").unknown, "a target change after the native delete is unknown");
        RemoteFixture changed = new RemoteFixture();
        changed.afterCall = () -> {
            if (changed.calls.size() == 4) changed.model.proxy.put(TARGET, new Node(DEVICE));
        };
        RpcException swapped = changed.defineFailure(TWO);
        assertTrue(
                swapped.unknown && swapped.reason.wire().equals("target_changed"),
                "a device change after the define dispatched keeps its own reason");
        RemoteFixture dropped = new RemoteFixture();
        dropped.afterCall = () -> {
            if (dropped.calls.size() == 4) dropped.pages.clear();
        };
        RpcException error = dropped.defineFailure(TWO);
        assertTrue(
                error.unknown && error.reason.wire().equals("readback_mismatch"),
                "a defined page that left the preset list is unknown");
    }

    @Test
    void unnamedPagesMatchTheirShownName() {
        RemoteFixture f = new RemoteFixture();
        f.userPage("");
        f.userPage("");
        f.others.add(new Page("", f.otherPages, f.host));
        f.define(TWO.replace("\"pageName\":\"Macros\"", "\"pageName\":\"Perform\""));
        assertTrue(!f.calls.contains("create"), "an unnamed first preset page shows as Perform and is the one edited");
        RpcException error;
        assertTrue(
                f.defineFailure(TWO.replace("\"pageName\":\"Macros\"", "\"pageName\":\"Page 1\""))
                        .getMessage()
                        .contains("device"),
                "an unnamed device page shows as Page <n>");
        JsonObject result = f.delete("Page 2", 2);
        assertTrue(
                f.pages.size() == 1
                        && result.getAsJsonArray("pages").get(1).getAsString().equals("Perform"),
                "delete and the page list use the shown names of unnamed pages");
    }

    @Test
    void deletesOnlyPresetPages() {
        RemoteFixture f = new RemoteFixture();
        f.userPage("Keep");
        Page doomed = f.userPage("Macros");
        JsonObject result = f.delete("Macros", 1);
        assertTrue(
                f.calls.equals(List.of("delete"))
                        && !f.pages.contains(doomed)
                        && f.pages.size() == 1
                        && f.op.dispatched()
                        && result.get("deletedPage").getAsString().equals("Macros")
                        && result.get("verification").getAsString().equals("native_readback")
                        && result.getAsJsonArray("pages").size() == 2,
                "a named preset page is deleted and verified absent");
        RemoteFixture missing = new RemoteFixture();
        assertTrue(
                missing.deleteFailure("Macros").reason.wire().equals("target_not_found") && missing.calls.isEmpty(),
                "a missing page is not found");
        RemoteFixture preset = new RemoteFixture();
        preset.others.add(new Page("Macros", preset.otherPages, preset.host));
        assertTrue(
                preset.deleteFailure("Macros").reason.wire().equals("unsupported_scope") && preset.calls.isEmpty(),
                "device pages are not deleted");
        assertTrue(
                preset.deleteFailure("Mx: darkness").reason.wire().equals("unsupported_scope"),
                "module pages are not deleted");
        RemoteFixture stuck = new RemoteFixture();
        stuck.apply = false;
        stuck.userPage("Macros");
        RpcException error = stuck.deleteFailure("Macros");
        assertTrue(
                error.reason.wire().equals("readback_mismatch") && error.unknown,
                "a page still listed after delete is unknown");
        RemoteFixture undo = new RemoteFixture();
        undo.userPage("Macros");
        undo.context.values.put(GridParameterContext.Field.UNDO_EXECUTING, true);
        assertTrue(
                undo.deleteFailure("Macros").reason.wire().equals("unsafe_state") && undo.calls.isEmpty(),
                "delete shares the write gates");
    }

    private static final String DEVICE_REF = "{\"kind\":\"device\",\"track\":{\"kind\":\"track\",\"id\":\"" + TRACK_ID
            + "\",\"name\":\"Grid\"},\"index\":0,\"name\":\"Poly Grid\"}";
    private static final String PARAMETER = "{\"kind\":\"parameter\",\"owner\":{\"kind\":\"gridModule\",\"device\":"
            + DEVICE_REF + ",\"id\":\"" + FIRST + "\",\"name\":\"Mx\"},\"id\":\"VALUE\"}";
    private static final String SLOT = "{\"parameter\":" + PARAMETER + ",\"label\":\"knob\"}";
    private static final String PAGE_REF =
            "{\"kind\":\"remotePage\",\"device\":" + DEVICE_REF + ",\"index\":1,\"name\":\"Macros\"}";

    @Test
    void requestBounds() {
        GridRemotePageWriter.Request request =
                GridRemotePageWriter.Request.parse(HostFixture.json("{\"name\":\"" + "p".repeat(256) + "\",\"slots\":["
                        + String.join(",", Collections.nCopies(7, "null")) + "," + SLOT + "]}"));
        assertTrue(
                request.slots.size() == 8
                        && request.slots.get(0) == null
                        && request.slots.get(7).moduleId.equals(FIRST),
                "eight slots with nulls parse to the module and parameter their refs name");
        assertTrue(
                GridRemotePageWriter.Request.parse(
                                HostFixture.json("{\"name\":\"P\",\"slots\":[" + SLOT.replace("VALUE", "") + "]}"))
                        .slots
                        .get(0)
                        .parameterId
                        .isEmpty(),
                "an empty parameter id is valid");
        assertRpcError(
                "unsupported_scope",
                () -> GridRemotePageWriter.Request.parse(HostFixture.json(
                        "{\"name\":\"P\",\"slots\":[" + SLOT.replace("gridModule", "modulator") + "]}")));
        assertRpcError(
                "invalid_params",
                () -> GridRemotePageWriter.Request.parse(
                        HostFixture.json("{\"name\":\"P\",\"slots\":[" + SLOT + "," + SLOT + "]}")));
    }

    @Test
    void moduleValidationAndRegistration() {
        String target = "\"target\":" + DEVICE_REF + ",";
        GridModule.validateParams(
                "devices.remote.define",
                HostFixture.json("{" + target + "\"name\":\"Macros\",\"slots\":[null," + SLOT + "]}"));
        GridModule.validateParams("devices.remote.delete", HostFixture.json("{\"target\":" + PAGE_REF + "}"));
        assertInvalidParams(() -> GridModule.validateParams(
                "devices.remote.define", HostFixture.json("{" + target + "\"name\":\"Macros\"}")));
        assertInvalidParams(() -> GridModule.validateParams(
                "devices.remote.define", HostFixture.json("{" + target + "\"pageName\":\"Macros\",\"slots\":[null]}")));
        assertInvalidParams(() -> GridModule.validateParams(
                "devices.remote.delete", HostFixture.json("{\"target\":" + DEVICE_REF + "}")));
        assertInvalidParams(() -> GridModule.validateParams(
                "devices.remote.delete", HostFixture.json("{\"target\":" + PAGE_REF + ",\"pageName\":\"Macros\"}")));
        assertTrue(
                GridModule.METHODS.contains("devices.remote.define")
                        && GridModule.METHODS.contains("devices.remote.delete")
                        && !DeviceModule.METHODS.contains("devices.remote.define"),
                "remote page methods are registered once, on the native Grid module");
    }

    // ---- helpers ---------------------------------------------------------------------------------------------

}
