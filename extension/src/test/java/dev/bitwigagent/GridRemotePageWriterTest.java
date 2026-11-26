package dev.bitwigagent;

import static dev.bitwigagent.GridSnapshotReader.Field.*;
import static dev.bitwigagent.GridSnapshotReader.Kind.*;
import static dev.bitwigagent.GridSnapshotReaderTest.ContextFixture;
import static dev.bitwigagent.GridSnapshotReaderTest.Fixture;
import static dev.bitwigagent.GridSnapshotReaderTest.Node;

import com.google.gson.*;
import java.util.*;
import java.util.function.Supplier;

/** Remote Controls page define/delete semantics on fake models; not evidence of native behavior. */
public final class GridRemotePageWriterTest {
    private static int assertions;
    private static final UUID TRACK_ID = new UUID(3, 1), FIRST = new UUID(1, 1), SECOND = new UUID(1, 2);

    public static void main(String[] args) {
        createsANamedPageAndMapsSlots();
        conflictsAreRefusedBeforeDispatch();
        replaceEditsOnlyDifferingSlots();
        slotResolutionFailures();
        licenseAndContextGates();
        failuresAfterDispatchAreUnknown();
        deletesOnlyPresetPages();
        unnamedPagesMatchTheirShownName();
        requestBounds();
        moduleValidationAndCapabilities();
        System.out.println(
                "GridRemotePageWriterTest: " + assertions + " assertions passed (semantic fake models only)");
    }

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
        final HostOperation op = new HostOperation(Long.MAX_VALUE);
        final GridRemotePageWriter writer;
        boolean licensed = true, visible = true, apply = true, createNothing;
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

        JsonObject define(String json) {
            return writer.define(
                    model.proxy,
                    TRACK_ID,
                    GridRemotePageWriter.Request.parse(TransportMixFixture.json(json)),
                    () -> {},
                    () -> op.dispatched = true);
        }

        JsonObject delete(String name) {
            return writer.deletePage(model.proxy, TRACK_ID, name, () -> {}, () -> op.dispatched = true);
        }

        RpcException defineFailure(String json) {
            try {
                define(json);
                throw new AssertionError("Expected failed define");
            } catch (RpcException error) {
                return error;
            }
        }

        RpcException deleteFailure(String name) {
            try {
                delete(name);
                throw new AssertionError("Expected failed delete");
            } catch (RpcException error) {
                return error;
            }
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
            check(device == model.device, "the host belongs to the bound device");
            return host;
        }

        @Override
        public Object presetPages(Object host) {
            return editedPages;
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
            check(list == editedPages, "pages are created in the preset-page list");
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

    private static void createsANamedPageAndMapsSlots() {
        RemoteFixture f = new RemoteFixture();
        f.others.add(new Page("Other", f.otherPages, f.host));
        JsonObject result = f.define(TWO);
        Page page = f.pages.get(0);
        check(
                f.calls.equals(List.of("create", "rename", "map0", "map1")) && f.op.dispatched,
                "create, name, then map each slot in order");
        check(
                page.name.equals("Macros")
                        && page.slots.get(0).target == f.darkness
                        && page.slots.get(0).label.equals("darkness")
                        && page.slots.get(1).target == f.grit
                        && page.slots.get(1).label.isEmpty()
                        && page.slots.size() == 2,
                "slots hold the bound native parameters and labels");
        check(
                result.get("created").getAsBoolean()
                        && result.get("verification").getAsString().equals("exact_native_remote_page_readback")
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
        check(
                slots.size() == 8
                        && slots.get(0)
                                .getAsJsonObject()
                                .get("moduleId")
                                .getAsString()
                                .equals(FIRST.toString())
                        && slots.get(0)
                                .getAsJsonObject()
                                .get("moduleName")
                                .getAsString()
                                .equals("Mx: darkness")
                        && !slots.get(1).getAsJsonObject().has("moduleName")
                        && slots.get(2).isJsonNull(),
                "slots echo the resolved module identity; unnamed slots are null");
        JsonArray names = result.getAsJsonArray("pages");
        check(
                names.size() == 3
                        && names.get(0).getAsString().equals("Other")
                        && names.get(1).getAsString().equals("Macros")
                        && names.get(2).getAsString().equals("Mx: darkness"),
                "visible page names show module pages by module name");
        RemoteFixture empty = new RemoteFixture();
        JsonObject named = empty.define("{\"pageName\":\"Empty\",\"slots\":[null]}");
        check(
                empty.calls.equals(List.of("create", "rename"))
                        && named.getAsJsonArray("slots").get(0).isJsonNull(),
                "a page may be created empty");
    }

    private static void conflictsAreRefusedBeforeDispatch() {
        RemoteFixture f = new RemoteFixture();
        f.userPage("Macros");
        RpcException error = f.defineFailure(TWO);
        check(
                error.code.equals("page_exists")
                        && error.outcome.equals("not_applied")
                        && error.getMessage().contains("replace")
                        && f.calls.isEmpty()
                        && !f.op.dispatched,
                "create over an existing preset page is refused");
        RemoteFixture preset = new RemoteFixture();
        preset.others.add(new Page("Macros", preset.otherPages, preset.host));
        error = preset.defineFailure(
                TWO.replace("\"pageName\":\"Macros\",", "\"pageName\":\"Macros\",\"mode\":\"replace\","));
        check(
                error.code.equals("page_exists") && error.getMessage().contains("device") && preset.calls.isEmpty(),
                "a same-named device page is never replaced");
        RemoteFixture module = new RemoteFixture();
        error = module.defineFailure(TWO.replace("\"pageName\":\"Macros\"", "\"pageName\":\"Mx: darkness\""));
        check(
                error.code.equals("page_exists") && error.getMessage().contains("module") && module.calls.isEmpty(),
                "a name shown by a Grid module page conflicts");
        RemoteFixture twice = new RemoteFixture();
        twice.userPage("Macros");
        twice.userPage("Macros");
        error = twice.defineFailure(
                TWO.replace("\"pageName\":\"Macros\",", "\"pageName\":\"Macros\",\"mode\":\"replace\","));
        check(
                error.code.equals("stale_context") && twice.calls.isEmpty(),
                "several same-named preset pages are ambiguous");
    }

    private static void replaceEditsOnlyDifferingSlots() {
        RemoteFixture f = new RemoteFixture();
        Page page = f.userPage("Macros");
        page.slots.put(0, new Slot(f.darkness, "darkness"));
        page.slots.put(1, new Slot(f.darkness, "old"));
        page.slots.put(3, new Slot(f.grit, ""));
        JsonObject result = f.define(
                "{\"pageName\":\"Macros\",\"mode\":\"replace\",\"slots\":[{\"moduleId\":\"" + FIRST
                        + "\",\"parameterId\":\"VALUE\",\"label\":\"darkness\"},{\"moduleName\":\"Mx: grit\",\"parameterId\":\"VALUE\"}]}");
        check(
                f.calls.equals(List.of("map1", "clear3"))
                        && !result.get("created").getAsBoolean()
                        && page.slots.size() == 2
                        && page.slots.get(1).target == f.grit
                        && result.get("verification").getAsString().equals("exact_native_remote_page_readback"),
                "replace maps changed slots and clears slots the request leaves empty, keeping matching ones");
        f.calls.clear();
        f.op.dispatched = false;
        result = f.define(
                "{\"pageName\":\"Macros\",\"mode\":\"replace\",\"slots\":[{\"moduleId\":\"" + FIRST
                        + "\",\"parameterId\":\"VALUE\",\"label\":\"darkness\"},{\"moduleName\":\"Mx: grit\",\"parameterId\":\"VALUE\"}]}");
        check(
                f.calls.isEmpty()
                        && !f.op.dispatched
                        && !result.get("mutationDispatched").getAsBoolean()
                        && result.get("verification").getAsString().equals("already_matched"),
                "an identical replace is a verified no-op");
        page.slots.put(0, new Slot(f.darkness, "old"));
        f.calls.clear();
        f.define(
                "{\"pageName\":\"Macros\",\"mode\":\"replace\",\"slots\":[{\"moduleId\":\"" + FIRST
                        + "\",\"parameterId\":\"VALUE\",\"label\":\"darkness\"},{\"moduleName\":\"Mx: grit\",\"parameterId\":\"VALUE\"}]}");
        check(
                f.calls.equals(List.of("clear0", "map0"))
                        && page.slots.get(0).label.equals("darkness"),
                "a label-only change clears and remaps the slot (the native map ignores an unchanged target)");
        RemoteFixture upsert = new RemoteFixture();
        result =
                upsert.define(TWO.replace("\"pageName\":\"Macros\",", "\"pageName\":\"Macros\",\"mode\":\"replace\","));
        check(
                result.get("created").getAsBoolean() && upsert.calls.get(0).equals("create"),
                "replace creates the page when none exists");
        RemoteFixture refused = new RemoteFixture();
        refused.userPage("Macros");
        refused.ineligible.add(refused.grit);
        RpcException error = refused.defineFailure(
                TWO.replace("\"pageName\":\"Macros\",", "\"pageName\":\"Macros\",\"mode\":\"replace\","));
        check(
                error.code.equals("unsupported_scope")
                        && error.outcome.equals("not_applied")
                        && refused.calls.isEmpty(),
                "an existing page's eligibility is checked before dispatch");
    }

    private static void slotResolutionFailures() {
        RemoteFixture f = new RemoteFixture();
        check(
                f.defineFailure("{\"pageName\":\"P\",\"slots\":[{\"moduleName\":\"Nope\",\"parameterId\":\"VALUE\"}]}")
                        .code
                        .equals("stale_context"),
                "an unknown module name is stale");
        f.model.second.put(MODULE_NAME, "Mx: darkness");
        RpcException error = f.defineFailure(
                "{\"pageName\":\"P\",\"slots\":[{\"moduleName\":\"Mx: darkness\",\"parameterId\":\"VALUE\"}]}");
        check(
                error.code.equals("stale_context") && error.getMessage().contains("moduleId"),
                "an ambiguous module name asks for moduleId");
        RemoteFixture g = new RemoteFixture();
        check(
                g.defineFailure("{\"pageName\":\"P\",\"slots\":[{\"moduleId\":\"" + FIRST
                                + "\",\"parameterId\":\"MISSING\"}]}")
                        .code
                        .equals("stale_context"),
                "a missing parameter is stale");
        error = g.defineFailure(
                "{\"pageName\":\"P\",\"slots\":[{\"moduleId\":\"" + SECOND + "\",\"parameterId\":\"BIPOLAR\"}]}");
        check(
                error.code.equals("unsupported_scope") && error.getMessage().contains("scalar"),
                "boolean panels are not remote-mappable");
        g.unmappable.add(g.grit);
        check(
                g.defineFailure("{\"pageName\":\"P\",\"slots\":[{\"moduleId\":\"" + SECOND
                                + "\",\"parameterId\":\"VALUE\"}]}")
                        .code
                        .equals("unsupported_scope"),
                "a parameter outside the device or not mappable is refused");
        error = g.defineFailure("{\"pageName\":\"P\",\"slots\":[{\"moduleId\":\"" + FIRST
                + "\",\"parameterId\":\"VALUE\"}," + "{\"moduleName\":\"Mx: darkness\",\"parameterId\":\"VALUE\"}]}");
        check(
                error.code.equals("invalid_params") && g.calls.isEmpty(),
                "the same parameter in two slots is refused after resolution");
        RemoteFixture names = new RemoteFixture();
        names.model.unavailable.add(GridSnapshotReader.Metadata.MODULE_NAMES);
        check(
                names.defineFailure(
                                "{\"pageName\":\"P\",\"slots\":[{\"moduleName\":\"Mx: grit\",\"parameterId\":\"VALUE\"}]}")
                        .code
                        .equals("grid_unavailable"),
                "selecting by module name needs the native name readers");
    }

    private static void licenseAndContextGates() {
        RemoteFixture f = new RemoteFixture();
        f.licensed = false;
        check(f.defineFailure(TWO).code.equals("unsafe_state") && f.calls.isEmpty(), "an unlicensed device is refused");
        RemoteFixture undo = new RemoteFixture();
        undo.context.values.put(GridParameterContext.Field.UNDO_ACTIVE, true);
        check(
                undo.defineFailure(TWO).code.equals("unsafe_state") && undo.calls.isEmpty(),
                "an active undo change gates the write");
        RemoteFixture skipped = new RemoteFixture();
        skipped.context.override(skipped.model.device, GridParameterContext.Field.CIU, true);
        check(
                skipped.defineFailure(TWO).code.equals("unsafe_state") && skipped.calls.isEmpty(),
                "the device skip predicate gates the write");
        RemoteFixture project = new RemoteFixture();
        project.context.override(project.editedPages, NativeReadContext.Field.PROJECT, new Object());
        check(
                project.defineFailure(TWO).code.equals("stale_context") && project.calls.isEmpty(),
                "a page list of another project is refused");
        RemoteFixture lock = new RemoteFixture();
        lock.context.values.put(GridParameterContext.Field.LOCK_OWNER, new Object());
        check(
                lock.defineFailure(TWO).code.equals("unsafe_state") && lock.calls.isEmpty(),
                "a foreign device lock owner gates the write");
        RemoteFixture thread = new RemoteFixture();
        thread.editedPages.threadAllowed = false;
        check(
                thread.defineFailure(TWO).code.equals("grid_unavailable") && thread.calls.isEmpty(),
                "the model thread predicate gates the page list");
    }

    private static void failuresAfterDispatchAreUnknown() {
        RemoteFixture thrown = new RemoteFixture();
        thrown.afterCall = () -> {
            if (thrown.calls.size() == 3) throw Errors.notApplied(ErrorCode.GRID_UNAVAILABLE, "native map failure");
        };
        RpcException error = thrown.defineFailure(TWO);
        check(
                error.outcome.equals("unknown")
                        && thrown.calls.equals(List.of("create", "rename", "map0"))
                        && thrown.op.dispatched,
                "a native failure after dispatch is unknown and nothing is retried or rolled back");
        RemoteFixture unread = new RemoteFixture();
        unread.apply = false;
        error = unread.defineFailure(TWO);
        check(
                error.code.equals("grid_verification_failed") && error.outcome.equals("unknown"),
                "an unobserved page name is unknown");
        RemoteFixture none = new RemoteFixture();
        none.createNothing = true;
        error = none.defineFailure(TWO);
        check(
                error.code.equals("grid_verification_failed")
                        && error.outcome.equals("unknown")
                        && none.calls.equals(List.of("create")),
                "a native create that yields no page is unknown");
        RemoteFixture refused = new RemoteFixture();
        refused.ineligible.add(refused.grit);
        error = refused.defineFailure(TWO);
        check(
                error.code.equals("unsupported_scope")
                        && error.outcome.equals("unknown")
                        && error.getMessage().contains("deletePage")
                        && refused.calls.equals(List.of("create", "rename")),
                "a new page refusing a slot stops before mapping and says how to clean up");
        RemoteFixture hidden = new RemoteFixture();
        hidden.afterCall = () -> {
            if (hidden.calls.size() == 4) hidden.visible = false;
        };
        error = hidden.defineFailure(TWO);
        check(
                error.code.equals("grid_verification_failed") && error.outcome.equals("unknown"),
                "a page missing from the visible list fails verification");
    }

    private static void unnamedPagesMatchTheirShownName() {
        RemoteFixture f = new RemoteFixture();
        f.userPage("");
        f.userPage("");
        f.others.add(new Page("", f.otherPages, f.host));
        RpcException error = f.defineFailure(TWO.replace("\"pageName\":\"Macros\"", "\"pageName\":\"Perform\""));
        check(error.code.equals("page_exists") && f.calls.isEmpty(), "an unnamed first preset page shows as Perform");
        check(
                f.defineFailure(TWO.replace("\"pageName\":\"Macros\"", "\"pageName\":\"Page 1\""))
                        .getMessage()
                        .contains("device"),
                "an unnamed device page shows as Page <n>");
        JsonObject result = f.delete("Page 2");
        check(
                f.pages.size() == 1
                        && result.getAsJsonArray("pages").get(1).getAsString().equals("Perform"),
                "delete and the page list use the shown names of unnamed pages");
    }

    private static void deletesOnlyPresetPages() {
        RemoteFixture f = new RemoteFixture();
        f.userPage("Keep");
        Page doomed = f.userPage("Macros");
        JsonObject result = f.delete("Macros");
        check(
                f.calls.equals(List.of("delete"))
                        && !f.pages.contains(doomed)
                        && f.pages.size() == 1
                        && f.op.dispatched
                        && result.get("deletedPage").getAsString().equals("Macros")
                        && result.get("verification").getAsString().equals("native_page_absent_after_delete")
                        && result.getAsJsonArray("pages").size() == 2,
                "a named preset page is deleted and verified absent");
        RemoteFixture missing = new RemoteFixture();
        check(
                missing.deleteFailure("Macros").code.equals("stale_context") && missing.calls.isEmpty(),
                "a missing page is stale");
        RemoteFixture preset = new RemoteFixture();
        preset.others.add(new Page("Macros", preset.otherPages, preset.host));
        check(
                preset.deleteFailure("Macros").code.equals("unsupported_scope") && preset.calls.isEmpty(),
                "device pages are not deleted");
        check(preset.deleteFailure("Mx: darkness").code.equals("unsupported_scope"), "module pages are not deleted");
        RemoteFixture stuck = new RemoteFixture();
        stuck.apply = false;
        stuck.userPage("Macros");
        RpcException error = stuck.deleteFailure("Macros");
        check(
                error.code.equals("grid_verification_failed") && error.outcome.equals("unknown"),
                "a page still listed after delete is unknown");
        RemoteFixture undo = new RemoteFixture();
        undo.userPage("Macros");
        undo.context.values.put(GridParameterContext.Field.UNDO_EXECUTING, true);
        check(
                undo.deleteFailure("Macros").code.equals("unsafe_state") && undo.calls.isEmpty(),
                "delete shares the write gates");
    }

    private static void requestBounds() {
        String slot = "{\"moduleId\":\"" + FIRST + "\",\"parameterId\":\"VALUE\"}";
        GridRemotePageWriter.Request request = GridRemotePageWriter.Request.parse(
                TransportMixFixture.json("{\"pageName\":\"" + "p".repeat(256) + "\",\"mode\":\"create\",\"slots\":["
                        + String.join(",", Collections.nCopies(7, "null")) + "," + slot + "]}"));
        check(
                request.slots.size() == 8 && request.slots.get(0) == null && !request.replace,
                "eight slots with nulls and create mode parse");
        check(
                GridRemotePageWriter.Request.parse(
                                TransportMixFixture.json(
                                        "{\"pageName\":\"P\",\"mode\":\"replace\",\"slots\":[{\"moduleName\":\"M\",\"parameterId\":\"\",\"label\":\"\"}]}"))
                        .replace,
                "replace mode, empty parameterId and empty label are valid");
        for (String bad : List.of(
                "{\"pageName\":\"\",\"slots\":[null]}",
                "{\"pageName\":\"" + "p".repeat(257) + "\",\"slots\":[null]}",
                "{\"pageName\":\"P\",\"slots\":[]}",
                "{\"pageName\":\"P\",\"slots\":[" + String.join(",", Collections.nCopies(9, "null")) + "]}",
                "{\"pageName\":\"P\",\"slots\":[{\"parameterId\":\"VALUE\"}]}",
                "{\"pageName\":\"P\",\"slots\":[{\"moduleId\":\"" + FIRST
                        + "\",\"moduleName\":\"M\",\"parameterId\":\"VALUE\"}]}",
                "{\"pageName\":\"P\",\"slots\":[{\"moduleId\":\"not-a-uuid\",\"parameterId\":\"VALUE\"}]}",
                "{\"pageName\":\"P\",\"slots\":[{\"moduleName\":\"\",\"parameterId\":\"VALUE\"}]}",
                "{\"pageName\":\"P\",\"slots\":[{\"moduleName\":\"M\"}]}",
                "{\"pageName\":\"P\",\"slots\":[{\"moduleName\":\"M\",\"parameterId\":\"V\",\"label\":\""
                        + "l".repeat(257) + "\"}]}",
                "{\"pageName\":\"P\",\"slots\":[{\"moduleName\":\"M\",\"parameterId\":\"V\",\"label\":null}]}",
                "{\"pageName\":\"P\",\"slots\":[{\"moduleName\":\"M\",\"parameterId\":\"V\",\"extra\":1}]}",
                "{\"pageName\":\"P\",\"slots\":[1]}",
                "{\"pageName\":\"P\",\"slots\":[null],\"mode\":\"upsert\"}",
                "{\"pageName\":\"P\",\"slots\":[" + slot + "," + slot + "]}",
                "{\"pageName\":1,\"slots\":[null]}"))
            expectInvalid(() -> GridRemotePageWriter.Request.parse(TransportMixFixture.json(bad)));
    }

    private static void moduleValidationAndCapabilities() {
        String target = "\"trackId\":\"" + TRACK_ID + "\",\"deviceIndex\":0,\"expectedDeviceName\":\"Poly Grid\",";
        GridModule.validateParams(
                "devices.remote.define",
                TransportMixFixture.json(
                        "{" + target
                                + "\"pageName\":\"Macros\",\"mode\":\"replace\",\"slots\":[null,{\"moduleName\":\"Mx: grit\",\"parameterId\":\"VALUE\"}]}"));
        GridModule.validateParams(
                "devices.remote.deletePage", TransportMixFixture.json("{" + target + "\"pageName\":\"Macros\"}"));
        expectInvalid(() -> GridModule.validateParams(
                "devices.remote.define", TransportMixFixture.json("{" + target + "\"pageName\":\"Macros\"}")));
        expectInvalid(() -> GridModule.validateParams(
                "devices.remote.deletePage",
                TransportMixFixture.json("{" + target + "\"pageName\":\"Macros\",\"mode\":\"create\"}")));
        expectInvalid(() -> GridModule.validateParams(
                "devices.remote.deletePage", TransportMixFixture.json("{" + target + "\"pageName\":\"\"}")));
        expectInvalid(() -> GridModule.validateParams(
                "devices.remote.define",
                TransportMixFixture.json("{" + target + "\"pageName\":\"Macros\",\"slots\":[null],\"mode\":null}")));
        check(
                GridModule.METHODS.contains("devices.remote.define")
                        && GridModule.METHODS.contains("devices.remote.deletePage")
                        && !DeviceModule.METHODS.contains("devices.remote.define"),
                "remote page methods are registered once, on the native Grid module");
        JsonObject full = new JsonObject();
        JsonObject entry = new JsonObject();
        entry.addProperty("available", false);
        entry.addProperty("reason", "no binding");
        full.add("remotePages", entry);
        JsonObject compact = GridModule.compactCapabilities(full);
        check(
                !compact.getAsJsonObject("writers").get("remotePages").getAsBoolean()
                        && compact.getAsJsonObject("unavailable").has("remotePages"),
                "compact capabilities report remote page availability");
    }

    // ---- helpers ---------------------------------------------------------------------------------------------

    private static void expectInvalid(Runnable call) {
        try {
            call.run();
            throw new AssertionError("Expected invalid_params");
        } catch (RpcException error) {
            check(
                    error.code.equals("invalid_params") && error.outcome.equals("not_applied"),
                    "invalid request: " + error.getMessage());
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        assertions++;
    }
}
