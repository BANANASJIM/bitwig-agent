package dev.bitwigagent;

import com.google.gson.*;
import java.util.*;
import java.util.function.BiConsumer;
import static dev.bitwigagent.ModulatorSnapshotReader.Field.*;
import static dev.bitwigagent.ModulatorSnapshotReader.Kind.MODEL;
import static dev.bitwigagent.ModulatorSnapshotReader.Kind.MODULATOR;
import static dev.bitwigagent.ModulatorSnapshotReader.Kind.IDENTIFIED;
import static dev.bitwigagent.ModulatorSnapshotReader.Kind.STRING_PANEL;

/** Host-free semantic models, not evidence of reachable native compatibility. */
public final class ModulatorSnapshotReaderTest {
    private static int assertions;
    private static final UUID TRACK = new UUID(4, 1);
    public static void main(String[] args) {
        inventoryKeepsIdentitiesSeparateAndNeverReadsValues();
        unmaterializedRowsRemainMetadataOnly();
        exactSelectionPreservesEmptyUnicodeAndWhitespace();
        primaryOwnerAndDirectMembershipFailClosed();
        duplicatesAndUnknownIdentityFailClosed();
        sameObjectCurrentUuidAndLiveMembershipAreGuarded();
        contextAndTargetChangesFailClosed();
        boundsApplyToActualWorkNotOnlyPage();
        emptyAndChangingInventoriesRemainHonest();
        selectedReadDoesNotInspectUnrelatedValuesOrContents();
        unsupportedAndUnavailableFamiliesStaySeparate();
        errorsAndCancellationAreNotHidden();
        runtimeGateIsReadOnlyAndDoesNotClaimAcceptance();
        System.out.println("ModulatorSnapshotReaderTest: " + assertions + " assertions passed (semantic models only)");
    }
    private static void inventoryKeepsIdentitiesSeparateAndNeverReadsValues() {
        Fixture f = new Fixture();
        f.first.put(NAME, "  Raw name \t").put(CUSTOM_NAME, "");
        Node unsupported = f.child(f.contents, "LEVEL", false);
        Node unknown = f.child(f.contents, null, false);
        f.panel.put(VALUE, new Object()); unsupported.put(VALUE, new Object());
        JsonObject result = f.list(0, 64);
        JsonObject row = result.getAsJsonArray("modulators").get(0).getAsJsonObject();
        check(row.get("modulatorId").getAsString().equals(f.id(f.first).toString()), "current instance UUID emitted");
        check(!row.get("modulatorId").equals(row.get("typeId")), "type UUID is not instance identity");
        check(row.get("name").getAsString().equals("  Raw name \t") && row.get("customName").getAsString().isEmpty(), "raw names retained separately");
        JsonArray parameters = row.getAsJsonArray("parameters");
        check(parameters.size() == 3 && parameters.get(0).getAsJsonObject().get("parameterId").getAsString().equals("TEXT"), "direct controls described");
        check(!parameters.get(1).getAsJsonObject().get("supported").getAsBoolean(), "non-QEE remains explicit unsupported");
        check(parameters.get(2).getAsJsonObject().get("parameterId").isJsonNull(), "unidentified component gets no guessed identifier");
        check(!parameters.get(0).getAsJsonObject().has("value") && f.valueReads == 0, "inventory never invokes a value getter");
        check(!result.get("complete").getAsBoolean() && !result.get("nativeMutation").getAsBoolean(), "partial unsupported read never claims mutation");
        check(unknown.values.get(PARENT) == f.contents, "inventory does not rewrite ownership");
    }
    private static void unmaterializedRowsRemainMetadataOnly() {
        Fixture f = new Fixture(); f.first.put(CONTENTS, null).put(NAME, null).put(CUSTOM_NAME, null);
        JsonObject result = f.list(0, 64), row = result.getAsJsonArray("modulators").get(0).getAsJsonObject();
        check(!row.get("contentsAvailable").getAsBoolean() && row.get("contentState").getAsString().equals("unmaterialized"), "nonloading absence reported");
        check(row.get("typeId").isJsonNull() && row.get("parameters").isJsonNull() && row.get("name").isJsonNull(), "unavailable details are null, not empty success");
        check(!result.get("complete").getAsBoolean() && f.childrenReads == 0, "missing content is not traversed");
        expect("modulator_unavailable", f::get);
        check(f.valueReads == 0, "unmaterialized get has no value fallback");
    }
    private static void exactSelectionPreservesEmptyUnicodeAndWhitespace() {
        Fixture f = new Fixture(); f.panel.put(PARAMETER_ID, "");
        for (String value : List.of("", "  ", "\n\t", "raw\0text")) {
            f.panel.put(VALUE, value);
            JsonObject result = f.reader.parameter(f.proxy, TRACK, f.id(f.first), "", () -> {});
            check(result.get("value").getAsString().equals(value), "raw empty/whitespace/control text retained");
            check(result.get("labels").isJsonNull() && result.get("shortLabel").isJsonNull() && result.get("display").isJsonNull(), "no manufactured labels/display");
        }
        String unicode = "\u00e9\u4e2d\ud83c\udfb5 e\u0301";
        f.panel.put(PARAMETER_ID, "\u6587\u5b57").put(VALUE, unicode);
        JsonObject result = f.reader.parameter(f.proxy, TRACK, f.id(f.first), "\u6587\u5b57", () -> {});
        check(result.get("value").getAsString().equals(unicode), "Unicode code units preserved without normalization");
        check(!result.getAsJsonObject("readContext").get("activeApplicationProjectIdentityChecked").getAsBoolean(), "owning context is not active-project proof");
        expect("target_not_found", () -> f.reader.parameter(f.proxy, TRACK, f.id(f.first), "TEXT", () -> {}));
        expect("target_not_found", () -> f.reader.parameter(f.proxy, TRACK, new UUID(99, 99), "\u6587\u5b57", () -> {}));
    }
    private static void primaryOwnerAndDirectMembershipFailClosed() {
        Fixture f = new Fixture(); f.collection.put(PARENT, new Node()); expect("stale_context", f::get);
        f = new Fixture(); f.first.put(PARENT, new Node()); expect("stale_context", f::get);
        f = new Fixture(); f.first.put(COLLECTION_OWNER, new Node()); expect("stale_context", f::get);
        f = new Fixture(); f.contents.put(PARENT, new Node()); expect("stale_context", f::get);
        f = new Fixture(); f.contents.put(CONTENTS_OWNER, new Node()); expect("stale_context", f::get);
        f = new Fixture(); f.panel.put(PARENT, new Node()); expect("stale_context", f::get);
        Fixture absent = new Fixture(); Node outside = absent.modulator(); UUID outsideId = absent.id(outside); absent.rows.remove(outside);
        expect("target_not_found", () -> absent.reader.parameter(absent.proxy, TRACK, outsideId, "TEXT", () -> {}));
        f = new Fixture(); f.targetOwner = new Node(); expect("modulator_unavailable", f::get);
        f = new Fixture(); f.trackId = new UUID(1, 8); expect("stale_context", f::get);
    }
    private static void duplicatesAndUnknownIdentityFailClosed() {
        Fixture f = new Fixture(); f.modulator().put(ID, f.id(f.first)); expect("modulator_unavailable", () -> f.list(0, 1));
        Fixture repeated = new Fixture(); repeated.rows.add(repeated.first); expect("modulator_unavailable", repeated::get);
        Fixture ids = new Fixture(); ids.child(ids.contents, "TEXT", false); expect("modulator_unavailable", ids::get);
        Fixture empty = new Fixture(); empty.panel.put(PARAMETER_ID, ""); empty.child(empty.contents, "", true); expect("modulator_unavailable", () -> empty.list(0, 64));
        Fixture objects = new Fixture(); objects.children(objects.contents).add(objects.panel); expect("modulator_unavailable", objects::get);
        Fixture unknown = new Fixture(); unknown.rows.add(new Node()); expect("modulator_unavailable", unknown::get);
        Fixture missing = new Fixture(); UUID requestedId = missing.id(missing.first); missing.first.put(ID, null);
        expect("modulator_unavailable", () -> missing.reader.parameter(missing.proxy, TRACK, requestedId, "TEXT", () -> {}));
    }
    private static void sameObjectCurrentUuidAndLiveMembershipAreGuarded() {
        Fixture f = new Fixture(); UUID original = f.id(f.first);
        f.afterValue = () -> f.first.put(ID, new UUID(7, 7)); expect("stale_context", f::get);
        check(!f.id(f.first).equals(original), "same object changed UUID, not merely replacement");
        Fixture type = new Fixture(); type.afterValue = () -> type.contents.put(TYPE_ID, new UUID(8, 8)); expect("stale_context", type::get);
        Fixture content = new Fixture(); content.afterValue = () -> content.first.put(CONTENTS, null); expect("stale_context", content::get);
        Fixture parent = new Fixture(); parent.afterValue = () -> parent.panel.put(PARENT, parent.first); expect("stale_context", parent::get);
        Fixture child = new Fixture(); child.afterValue = () -> child.children(child.contents).set(0, new Node(MODEL)); expect("stale_context", child::get);
        Fixture identifier = new Fixture(); identifier.afterValue = () -> identifier.panel.put(PARAMETER_ID, "OTHER"); expect("stale_context", identifier::get);
        Fixture value = new Fixture(); value.afterValue = () -> value.panel.put(VALUE, "changed"); expect("stale_context", value::get);
        Fixture order = new Fixture(); order.modulator(); order.afterValue = () -> Collections.reverse(order.rows); expect("stale_context", order::get);
        Fixture count = new Fixture(); count.afterValue = count::modulator; expect("stale_context", count::get);
        Fixture wrapper = new Fixture(); wrapper.freshChildrenWrappers = true;
        check(wrapper.get().get("value").getAsString().equals("initial"), "fresh unmodifiable live wrappers do not imply membership change");
    }
    private static void contextAndTargetChangesFailClosed() {
        Fixture f = new Fixture(); f.afterValue = () -> f.target = new Node(); expect("stale_context", f::get);
        Fixture owner = new Fixture(); owner.afterValue = () -> owner.trackId = new UUID(4, 2); expect("stale_context", owner::get);
        Fixture context = new Fixture(); context.afterValue = () -> context.panel.context = new Node(); expect("stale_context", context::get);
        Fixture project = new Fixture(); project.panel.project = new Node(); expect("stale_context", project::get);
        Fixture deleted = new Fixture(); deleted.afterValue = () -> deleted.first.deleted = true; expect("stale_context", deleted::get);
        Fixture disposed = new Fixture(); disposed.afterValue = () -> disposed.disposed = true; expect("stale_context", disposed::get);
        Fixture dt = new Fixture(); dt.afterValue = () -> dt.currentDt = new Node(); expect("stale_context", dt::get);
        Fixture thread = new Fixture(); thread.panel.thread = false; expect("modulator_unavailable", thread::get);
        Fixture observed = new Fixture(); observed.initialized = false; observed.root = new Node();
        JsonObject attached = observed.get().getAsJsonObject("readContext").getAsJsonObject("attachedContext");
        check(!attached.get("initialized").getAsBoolean() && !attached.get("rootIsOwningProject").getAsBoolean(), "initialization/root equality remain observations, not invented gates");
        Fixture alias = new Fixture(); alias.aliased = true;
        check(alias.get().get("value").getAsString().equals("initial"), "owning channel alias participates in target/context checks");
        alias.afterValue = () -> alias.alias = new Node(); expect("modulator_unavailable", alias::get);
    }
    private static void boundsApplyToActualWorkNotOnlyPage() {
        Fixture primary = new Fixture(); primary.countOverride = 4097; expect("modulator_bounds_exceeded", () -> primary.list(100000, 1));
        check(primary.indexReads == 0, "count bound applies before primary iteration");
        Fixture children = new Fixture(); children.contents.put(CHILDREN, Collections.nCopies(257, children.panel));
        expect("modulator_bounds_exceeded", () -> children.list(0, 1));
        Fixture aggregate = new Fixture(); aggregate.rows.clear();
        for (int i = 0; i < 65; i++) {
            Node row = aggregate.modulator(), contents = aggregate.contents(row);
            for (int j = 0; j < 256; j++) aggregate.child(contents, "control" + j, false);
        }
        expect("modulator_bounds_exceeded", () -> aggregate.list(0, 128));
        Fixture page = new Fixture(); page.modulator();
        JsonObject result = page.list(1, 1); JsonObject window = result.getAsJsonObject("window");
        check(window.get("total").getAsInt() == 2 && window.get("returned").getAsInt() == 1 && !result.get("complete").getAsBoolean(), "page is honest independent partial observation");
        check(page.list(100000, 1).getAsJsonArray("modulators").isEmpty(), "offset outside current collection gives empty bounded page");
        Fixture text = new Fixture(); text.panel.put(VALUE, "x".repeat(1024)); check(text.get().get("value").getAsString().length() == 1024, "exact text bound accepted");
        text.panel.put(VALUE, "x".repeat(1025)); expect("modulator_bounds_exceeded", text::get);
        text.panel.put(VALUE, "\ud83c\udfb5".repeat(513)); expect("modulator_bounds_exceeded", text::get);
        Fixture id = new Fixture(); id.panel.put(PARAMETER_ID, "x".repeat(1025)); expect("modulator_bounds_exceeded", () -> id.list(0, 1));
        Fixture name = new Fixture(); name.first.put(NAME, "x".repeat(1025)); expect("modulator_bounds_exceeded", name::get);
        expect("invalid_params", () -> page.list(-1, 1)); expect("invalid_params", () -> page.list(0, 129));
    }
    private static void emptyAndChangingInventoriesRemainHonest() {
        Fixture empty = new Fixture(); empty.rows.clear();
        JsonObject result = empty.list(0, 64);
        check(result.getAsJsonArray("modulators").isEmpty() && result.getAsJsonObject("window").get("total").getAsInt() == 0,
                "genuinely empty primary collection is distinct from unmaterialized contents");
        check(result.get("complete").getAsBoolean() && empty.valueReads == 0, "empty primary observation has no fabricated value reads");
        Fixture missing = new Fixture(); missing.target = null; expect("target_not_found", missing::get);
        Fixture changed = new Fixture();
        changed.onRead = (field, object) -> {
            if (field == CHILDREN) changed.first.put(ID, new UUID(100, 100));
        };
        expect("stale_context", () -> changed.list(0, 64));
        Fixture work = new Fixture(); work.rows.clear();
        for (int i = 0; i < 33; i++) {
            Node contents = work.contents(work.modulator());
            for (int j = 0; j < 256; j++) work.child(contents, "control" + j, false);
        }
        expect("modulator_bounds_exceeded", () -> work.list(0, 128));
        work.rows.remove(work.rows.size() - 1);
        check(work.list(0, 128).getAsJsonObject("window").get("total").getAsInt() == 32,
                "16384 capture plus revalidation visits accepted; exceeding actual work fails");
    }
    private static void selectedReadDoesNotInspectUnrelatedValuesOrContents() {
        Fixture f = new Fixture(); Node other = f.child(f.contents, "OTHER", true); other.put(VALUE, "x".repeat(2000));
        Node second = f.modulator(); Node secondContents = f.contents(second);
        secondContents.put(CHILDREN, new Object());
        f.onRead = (field, object) -> {
            if (field == VALUE && object != f.panel) throw new AssertionError("unrelated value read");
            if (field == CHILDREN && object == secondContents) throw new AssertionError("unrelated contents traversed by selected get");
        };
        check(f.get().get("value").getAsString().equals("initial") && f.valueReads == 2, "only selected raw value read and rechecked");
        second.put(NAME, new Object()).put(CUSTOM_NAME, new Object());
        check(f.list(0, 1).getAsJsonArray("modulators").size() == 1 && f.valueReads == 2,
                "inventory details are confined to requested page; off-page values/names/contents are not inspected");
        f.panel.put(VALUE, new Object()); expect("modulator_unavailable", f::get);
    }
    private static void unsupportedAndUnavailableFamiliesStaySeparate() {
        Fixture f = new Fixture(); f.panel.kinds.remove(STRING_PANEL); expect("unsupported_scope", f::get);
        check(f.valueReads == 0, "unsupported control has no value fallback");
        Fixture unavailable = new Fixture(); unavailable.strings = false;
        JsonObject descriptor = unavailable.list(0, 64).getAsJsonArray("modulators").get(0).getAsJsonObject().getAsJsonArray("parameters").get(0).getAsJsonObject();
        check(!descriptor.get("supported").getAsBoolean() && descriptor.get("parameterId").getAsString().equals("TEXT"), "discovery survives QEE binding unavailability");
        expect("modulator_unavailable", unavailable::get);
    }
    private static void errorsAndCancellationAreNotHidden() {
        Fixture f = new Fixture(); f.onRead = (field, object) -> { if (field == VALUE) throw HostServices.failure("modulator_unavailable", "native getter failed"); };
        expect("modulator_unavailable", f::get);
        Fixture cancelled = new Fixture();
        expect("cancelled", () -> cancelled.reader.list(cancelled.proxy, TRACK, 0, 64, () -> { throw HostServices.failure("cancelled", "cancelled"); }));
        check(cancelled.indexReads == 0, "cancellation precedes native collection work");
        Fixture deadline = new Fixture(); deadline.afterValue = () -> deadline.expired = true;
        expect("deadline", () -> deadline.reader.parameter(deadline.proxy, TRACK, deadline.id(deadline.first), "TEXT", () -> { if (deadline.expired) throw HostServices.failure("deadline", "expired"); }));
    }
    private static void runtimeGateIsReadOnlyAndDoesNotClaimAcceptance() {
        Bitwig614ModulatorAdapter adapter = new Bitwig614ModulatorAdapter(new Bitwig614Runtime(new Object(), "6.1.2", () -> {}).access("modulator_unavailable"));
        JsonObject capabilities = adapter.capabilities();
        check(!capabilities.getAsJsonObject("runtimeGate").get("available").getAsBoolean(), "wrong runtime version remains closed");
        check(!capabilities.getAsJsonObject("discovery").get("available").getAsBoolean() && !capabilities.getAsJsonObject("stringPanelRead").get("available").getAsBoolean(), "domain capabilities respect runtime failure");
        check(!capabilities.get("writeAvailable").getAsBoolean() && capabilities.get("nativeVerification").getAsString().equals("not_established_by_binding"), "binding is never native acceptance or write authority");
        expect("modulator_unavailable", () -> adapter.list(new Object(), TRACK, 0, 64, () -> {}));
    }
    private static void check(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
    private static void expect(String code, Runnable action) {
        try { action.run(); throw new AssertionError("Expected " + code); }
        catch (RpcException error) { check(code.equals(error.code), "expected " + code + ", got " + error.code + ": " + error.getMessage()); }
    }
    private static final class Node {
        final Set<ModulatorSnapshotReader.Kind> kinds = EnumSet.noneOf(ModulatorSnapshotReader.Kind.class);
        final Map<ModulatorSnapshotReader.Field, Object> values = new EnumMap<>(ModulatorSnapshotReader.Field.class);
        Object context, project; boolean deleted, thread = true;
        Node(ModulatorSnapshotReader.Kind... kinds) { this.kinds.addAll(Arrays.asList(kinds)); }
        Node put(ModulatorSnapshotReader.Field field, Object value) { values.put(field, value); return this; }
    }
    private static final class Fixture implements ModulatorSnapshotReader.Access, NativeDeviceTarget.Access, NativeReadContext.Access {
        final Node proxy = new Node(), device = new Node(MODEL), owner = new Node(MODEL), initialAlias = new Node(MODEL);
        final Node project = new Node(MODEL), context = new Node(), collection = new Node(ModulatorSnapshotReader.Kind.COLLECTION, MODEL);
        final List<Node> rows = new ArrayList<>();
        final Node first, contents, panel;
        final ModulatorSnapshotReader reader = new ModulatorSnapshotReader(this, new NativeDeviceTarget(this), new NativeReadContext(this));
        Object target = device, targetOwner = owner, alias = initialAlias, root = project, currentDt;
        UUID trackId = TRACK; int nextId, valueReads, childrenReads, indexReads; Integer countOverride;
        boolean strings = true, aliased, disposed, initialized = true, freshChildrenWrappers, expired;
        Runnable afterValue; BiConsumer<ModulatorSnapshotReader.Field, Object> onRead = (field, object) -> {};
        Fixture() {
            device.put(COLLECTION, collection); collection.put(PARENT, device);
            first = modulator(); contents = contents(first); panel = child(contents, "TEXT", true).put(VALUE, "initial");
        }
        Node modulator() {
            Node node = new Node(MODULATOR, MODEL).put(ID, new UUID(10, ++nextId)).put(NAME, "Modulator " + nextId)
                    .put(CUSTOM_NAME, "").put(PARENT, collection).put(COLLECTION_OWNER, collection);
            rows.add(node); return node;
        }
        Node contents(Node row) {
            Node node = new Node(ModulatorSnapshotReader.Kind.CONTENTS, MODEL).put(TYPE_ID, new UUID(20, nextId)).put(PARENT, row).put(CONTENTS_OWNER, row)
                    .put(CHILDREN, new ArrayList<Node>());
            row.put(CONTENTS, node); return node;
        }
        Node child(Node contents, String id, boolean string) {
            Node node = new Node(MODEL).put(PARENT, contents);
            if (id != null) { node.kinds.add(IDENTIFIED); node.put(PARAMETER_ID, id); }
            if (string) node.kinds.add(STRING_PANEL);
            children(contents).add(node); return node;
        }
        @SuppressWarnings("unchecked") List<Node> children(Node contents) { return (List<Node>) contents.values.get(CHILDREN); }
        UUID id(Node row) { return (UUID) row.values.get(ID); }
        JsonObject list(int offset, int limit) { return reader.list(proxy, TRACK, offset, limit, () -> {}); }
        JsonObject get() { return reader.parameter(proxy, TRACK, id(first), (String) panel.values.get(PARAMETER_ID), () -> {}); }
        @Override public Object read(ModulatorSnapshotReader.Field field, Object object) {
            onRead.accept(field, object);
            if (field == PROJECT) return project;
            if (field == COUNT) return countOverride == null ? rows.size() : countOverride;
            if (field == CHILDREN) childrenReads++;
            Object value = ((Node) object).values.get(field);
            if (field == CHILDREN && freshChildrenWrappers) return Collections.unmodifiableList((List<?>) value);
            if (field == VALUE) {
                valueReads++;
                if (afterValue != null) { Runnable action = afterValue; afterValue = null; action.run(); }
            }
            return value;
        }
        @Override public Object modulator(Object collection, int index) { indexReads++; return rows.get(index); }
        @Override public boolean is(ModulatorSnapshotReader.Kind kind, Object object) { return object instanceof Node && ((Node) object).kinds.contains(kind); }
        @Override public boolean stringsAvailable() { return strings; }
        @Override public void requireModelThread(Object object) { if (!(object instanceof Node) || !((Node) object).thread) throw HostServices.failure("modulator_unavailable", "model thread"); }
        @Override public boolean is(NativeDeviceTarget.Kind kind, Object object) {
            switch (kind) {
                case DEVICE: return object == device;
                case TRACK: return object == owner;
                case ALIASED_TRACK: return aliased && object == owner;
                case CHANNEL_ALIAS: return object == initialAlias;
                default: throw new AssertionError(kind);
            }
        }
        @Override public Object read(NativeDeviceTarget.Field field, Object object) {
            switch (field) {
                case TARGET: return target;
                case OWNER_TRACK: return targetOwner;
                case TRACK_ALIAS: return alias;
                case TRACK_UUID: case ALIAS_UUID: return trackId;
                default: throw new AssertionError(field);
            }
        }
        @Override public void documentThread() {}
        @Override public String unavailableCode() { return "modulator_unavailable"; }
        @Override public Object read(NativeReadContext.Field field, Object object) {
            Node node = (Node) object;
            switch (field) {
                case DELETED: return node.deleted;
                case CONTEXT: return node.context == null ? context : node.context;
                case PROJECT: return node.project == null ? project : node.project;
                case ROOT: return root;
                case INITIALIZED: return initialized;
                case DISPOSED: return disposed;
                case CONTEXT_THREAD: return true;
                case CURRENT_DT: return currentDt;
                case DEFAULT_DT: return null;
                case DT_CONTEXT: return context;
                default: throw new AssertionError(field);
            }
        }
        @Override public boolean defaultContext(Object context) { return true; }
    }
}
