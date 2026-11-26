package dev.bitwigagent;

import static dev.bitwigagent.GridSnapshotReader.Field.*;
import static dev.bitwigagent.GridSnapshotReader.Kind.*;
import static dev.bitwigagent.GridSnapshotReaderTest.Node;
import static dev.bitwigagent.GridSnapshotReaderTest.outcomes;
import static dev.bitwigagent.GridSnapshotReaderTest.unsuccessful;
import static dev.bitwigagent.NativeDeviceTarget.Field.*;
import static dev.bitwigagent.RpcAssert.*;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Host-free behavioral lifecycle tests, discovered by scripts/test-java.py. */
public final class GridInsertionWriterTest {
    static final UUID TYPE = new UUID(2, 1), TRACK = new UUID(3, 1);
    static final String ANY = new UUID(0, 0) + "/0", OTHER = new UUID(0, 1) + "/0";

    static String device(Fixture f) {
        return GridTopologyQuarantine.device(TRACK, f.model.graph);
    }

    @Test
    void processOwnersSurviveReplacementAndRejectMalformedMarkers() {
        Properties process = new Properties();
        GridTopologyQuarantine original = new GridTopologyQuarantine(process),
                replacement = new GridTopologyQuarantine(process);
        original.requireAvailable(ANY);
        String a = original.reserve(ANY);
        assertRpcError("busy", () -> replacement.requireAvailable(ANY));
        assertRpcError("busy", () -> replacement.requireAvailable(OTHER));
        assertTrue(
                !replacement.release(UUID.randomUUID().toString()), "unrelated owner cannot release pending lifetime");
        assertTrue(original.release(a), "exact owner can settle");
        String b = replacement.reserve(ANY);
        assertTrue(!original.release(a), "stale completion cannot release successor");
        replacement.requireOwner(b);
        original.poison(b, ANY, new JsonObject());
        assertTrue(
                !replacement.release(b) && replacement.quarantined(ANY) && !replacement.quarantined(OTHER),
                "poison ends the reservation and keeps the quarantine on its own device");
        replacement.requireAvailable(OTHER);
        assertTrue(
                process.size() == 2
                        && process.get(GridTopologyQuarantine.KEY) instanceof String
                        && process.get(GridTopologyQuarantine.DEVICE_PREFIX + ANY) instanceof String,
                "only namespaced String entries enter shared Properties");
        for (Object invalid :
                List.of("v2:idle", "v1:pending:bad", "v1:unknown", "v1:quarantined", Integer.valueOf(1))) {
            Properties bad = new Properties();
            bad.put(GridTopologyQuarantine.KEY, invalid);
            GridTopologyQuarantine guard = new GridTopologyQuarantine(bad);
            assertRpcError("native_unavailable", () -> guard.requireAvailable(ANY));
            assertTrue(
                    bad.get(GridTopologyQuarantine.KEY) == invalid && guard.quarantined(),
                    "unknown/non-String state is never overwritten or admitted");
        }
        Properties kinds = new Properties();
        GridTopologyQuarantine kind = new GridTopologyQuarantine(kinds);
        String mine = kind.reserve(ANY);
        assertTrue(
                kind.mismatch(mine, ANY, new JsonObject())
                        && GridTopologyQuarantine.SETTLEMENT.equals(kind.origin(ANY))
                        && !kind.pending(),
                "matching pending owner records the settlement kind and ends the reservation");
        String again = kind.reserve(OTHER);
        assertTrue(
                kind.pending() && kind.quarantined(ANY) && !kind.quarantined(OTHER) && kind.release(again),
                "another device is admitted while the first one is quarantined");
        kind.poison(UUID.randomUUID().toString(), ANY, new JsonObject());
        assertTrue(
                GridTopologyQuarantine.NATIVE.equals(kind.origin(ANY)) && kind.ack(ANY, kind.detail(ANY)),
                "native failure replaces the settlement kind and is released by an explicit ack");
        GridTopologyQuarantine other = new GridTopologyQuarantine(new Properties());
        other.reserve(ANY);
        assertTrue(
                !other.mismatch(UUID.randomUUID().toString(), ANY, new JsonObject())
                        && GridTopologyQuarantine.NATIVE.equals(other.origin(ANY)),
                "a mismatch from a non-owner fails closed as a native failure");
        GridTopologyQuarantine sticky = new GridTopologyQuarantine(new Properties());
        sticky.poison(UUID.randomUUID().toString(), ANY, new JsonObject());
        String late = sticky.reserve(OTHER);
        assertTrue(
                !sticky.mismatch(late, ANY, new JsonObject())
                        && GridTopologyQuarantine.NATIVE.equals(sticky.origin(ANY)),
                "a native failure is never downgraded to the recoverable kind");
        GridTopologyQuarantine stale = new GridTopologyQuarantine(new Properties());
        String own = stale.reserve(ANY);
        JsonObject detail = new JsonObject();
        detail.addProperty("a", 1);
        stale.mismatch(own, ANY, detail);
        assertTrue(
                !stale.ack(ANY, "{\"a\":2}")
                        && !stale.ack(OTHER, stale.detail(ANY))
                        && stale.ack(ANY, stale.detail(ANY))
                        && stale.origin(ANY) == null,
                "ack releases only the named device and only the verified record");
        GridTopologyQuarantine denied = new GridTopologyQuarantine(() -> {
            throw new SecurityException("denied");
        });
        assertRpcError("native_unavailable", () -> denied.requireAvailable(ANY));
        assertTrue(
                denied.quarantined() && denied.blockedReason() != null,
                "inaccessible process Properties cannot become availability");
        Properties readDenied = new Properties() {
            @Override
            public synchronized Object get(Object key) {
                throw new SecurityException("read");
            }
        };
        assertRpcError("native_unavailable", () -> new GridTopologyQuarantine(readDenied).reserve(ANY));
        Properties writeDenied = new Properties() {
            @Override
            public synchronized Object setProperty(String key, String value) {
                throw new SecurityException("write");
            }
        };
        assertRpcError("native_unavailable", () -> new GridTopologyQuarantine(writeDenied).requireAvailable(ANY));
    }

    @Test
    void insertionNeedsLaterSettlementAndReturnsReflow() {
        Fixture f = new Fixture();
        f.reflow = true;
        f.normalization = 1;
        HostOperation op = f.start(new GridInsertionWriter.Request(
                TYPE, 8, 3)); // Anchor is inside existing footprint: legal for insertion.
        assertTrue(
                op.dispatched && f.loads == 1 && f.placements == 0 && !op.result.isDone(),
                "loader dispatch precedes callback and is not completion");
        assertRpcError("busy", () -> f.guard.requireAvailable(device(f)));
        f.deliver();
        assertTrue(
                f.placements == 1 && f.cleanups == 0 && f.successes == 1 && !op.result.isDone(),
                "placement/success return still waits for ordinary event");
        assertRpcError("busy", () -> new GridTopologyQuarantine(f.process).requireAvailable(device(f)));
        f.next();
        JsonObject result = op.result.join();
        f.guard.requireAvailable(device(f));
        assertTrue(
                result.get("loaderDispatched").getAsBoolean()
                        && result.get("placementDispatched").getAsBoolean()
                        && result.get("nativeLifetimeSettled").getAsBoolean(),
                "separate loader/placement/settlement scopes reported");
        assertTrue(
                result.get("moduleId").getAsString().equals(new UUID(1, 1025).toString())
                        && result.get("typeId").getAsString().equals(TYPE.toString()),
                "actual new identity/type returned");
        assertTrue(
                result.getAsJsonObject("requestedPosition").get("x").getAsInt() == 8
                        && result.getAsJsonObject("actualPosition").get("x").getAsInt() == 9
                        && result.get("outcome").getAsString().equals("applied"),
                "normalization is observed, not request echo");
        JsonArray modules = result.getAsJsonObject("graph").getAsJsonArray("modules");
        assertTrue(
                modules.size() == 3 && modules.get(0).getAsJsonObject().get("x").getAsInt() == 9,
                "actual complete graph includes rightward reflow of existing modules");
        assertTrue(
                !result.get("undoGroupingVerified").getAsBoolean()
                        && !result.get("engineAcknowledgement").getAsBoolean(),
                "settlement is not commit/DSP proof");
    }

    @Test
    void lateRequestsStillCleanAndSettle() {
        for (int abandoned = 0; abandoned < 4; abandoned++) {
            Fixture f = new Fixture();
            HostOperation op = f.start();
            if (abandoned == 0) f.requestError = "deadline";
            if (abandoned == 1) op.result.cancel(false);
            if (abandoned == 2) op.result.completeExceptionally(Errors.notApplied(Reason.NOT_READY, "RPC closed"));
            if (abandoned == 3) f.requestError = "not_ready";
            f.deliver();
            assertTrue(
                    f.cleanups == 1 && f.placements == 0 && f.successes == 0 && !f.guard.quarantined(),
                    "first delivered rejected callback cleans once despite ended RPC");
            assertTrue(
                    f.order.equals(List.of("load", "cleanup", "enqueue")),
                    "native cleanup returns before outward error notification");
            assertRpcError("busy", () -> f.guard.requireAvailable(device(f)));
            f.next();
            f.guard.requireAvailable(device(f));
            assertTrue(
                    f.order.get(f.order.size() - 1).equals("failure") && unsuccessful(op),
                    "independent healthy native settlement releases while request stays failed");
            if (abandoned == 0)
                assertTrue(
                        assertUnknown("deadline", op) != null, "expired insertion retains native-loader uncertainty");
        }
        Fixture afterPlacement = new Fixture();
        HostOperation op = afterPlacement.start();
        afterPlacement.deliver();
        afterPlacement.requestError = "deadline";
        afterPlacement.next();
        afterPlacement.guard.requireAvailable(device(afterPlacement));
        assertTrue(
                afterPlacement.placements == 1 && assertUnknown("deadline", op) != null,
                "expired delivery never undoes settled native lifetime");
    }

    @Test
    void earlyErrorsDuplicatesAndAbsentCallbacksRemainBlocked() {
        Fixture absent = new Fixture();
        HostOperation missing = absent.start();
        missing.result.cancel(false);
        assertTrue(
                absent.guard.blockedReason() != null && !absent.guard.quarantined(),
                "missing callback retains pending lifetime even after future cancellation");
        Fixture early = new Fixture();
        HostOperation op = early.start();
        early.callback.earlyError("loader rejected");
        assertTrue(
                assertUnknown("native_unavailable", op) != null && early.guard.quarantined(),
                "early String error is unknown, not native cleanup acknowledgement");
        early.deliver();
        early.next();
        assertTrue(
                early.cleanups == 1 && early.placements == 0 && early.guard.quarantined(),
                "later first delivery after early error still cleans but cannot unpoison");
        Fixture duplicate = new Fixture();
        duplicate.start();
        duplicate.deliver();
        duplicate.deliver();
        duplicate.next();
        assertTrue(
                duplicate.placements == 1
                        && duplicate.successes == 1
                        && duplicate.cleanups == 0
                        && duplicate.guard.quarantined(),
                "duplicate native delivery cannot place/clean twice or settle normally");
        Fixture rejectedDuplicate = new Fixture();
        rejectedDuplicate.start();
        rejectedDuplicate.requestError = "deadline";
        rejectedDuplicate.deliver();
        rejectedDuplicate.deliver();
        rejectedDuplicate.next();
        assertTrue(
                rejectedDuplicate.cleanups == 1 && rejectedDuplicate.guard.quarantined(),
                "duplicate rejected callback does not clean twice");
        Fixture stale = new Fixture();
        stale.start();
        stale.deliver();
        stale.next();
        String successor = stale.guard.reserve(device(stale));
        stale.deliver();
        assertTrue(
                stale.guard.quarantined(device(stale)) && stale.guard.release(successor) && stale.guard.quarantined(),
                "stale callback quarantines its device and leaves the successor's own reservation to it");
    }

    @Test
    void capacityCatalogAndDetachedPreflight() {
        Fixture allowed = new Fixture();
        allowed.population(1023);
        HostOperation op = allowed.start();
        allowed.deliver();
        allowed.next();
        assertTrue(
                op.result
                                .join()
                                .getAsJsonObject("graph")
                                .getAsJsonArray("modules")
                                .size()
                        == 1024,
                "1023 plus one reaches bounded complete graph");
        Fixture mid = new Fixture();
        mid.population(500);
        op = mid.start();
        mid.deliver();
        mid.next();
        assertTrue(
                op.result
                                .join()
                                .getAsJsonObject("graph")
                                .getAsJsonArray("modules")
                                .size()
                        == 501,
                "insertion into a 500-module device passes survivor verification");
        Fixture full = new Fixture();
        full.population(1024);
        assertRpcError("out_of_bounds", full.start());
        assertTrue(full.loads == 0, "1024 plus one rejected before loader dispatch");
        full.guard.requireAvailable(device(full));
        Fixture grew = new Fixture();
        grew.population(1023);
        op = grew.start();
        grew.population(1024);
        grew.deliver();
        grew.next();
        assertTrue(
                assertUnknown("out_of_bounds", op) != null && grew.placements == 0 && grew.cleanups == 1,
                "callback capacity rechecked before native placement");
        grew.guard.requireAvailable(device(grew));
        for (int invalid = 0; invalid < 3; invalid++) {
            Fixture catalog = new Fixture();
            if (invalid == 0) catalog.model.catalog = List.of();
            if (invalid == 1) ((Node) catalog.model.catalog.get(0)).put(ENTRY_KIND, "DEVICE");
            if (invalid == 2)
                catalog.model.catalog = List.of(catalog.model.catalog.get(0), catalog.model.catalog.get(0));
            assertRpcError("invalid_params", catalog.start());
            assertTrue(catalog.loads == 0, "only unique known MODULE enters resolver/loader");
        }
        for (int invalid = 0; invalid < 8; invalid++) {
            Fixture f = new Fixture();
            op = f.start();
            if (invalid == 0) f.loaded = List.of();
            if (invalid == 1) f.loaded = List.of(f.incoming, f.incoming);
            if (invalid == 2) f.loaded = List.of(new Object());
            if (invalid == 3) ((Node) f.incoming.values.get(DESCRIPTOR)).put(TYPE_ID, new UUID(9, 9));
            if (invalid == 4) f.incoming.put(INSTANCE_ID, new UUID(1, 1));
            if (invalid == 5) f.deleted = true;
            if (invalid == 6) f.parented = true;
            if (invalid == 7) f.owned = true;
            f.deliver();
            f.next();
            assertTrue(
                    unsuccessful(op) && f.placements == 0 && f.cleanups == 1,
                    "wrong count/kind/type/UUID/detachment always rejects through delivered cleanup");
            f.guard.requireAvailable(device(f));
        }
    }

    @Test
    void geometryAndFreshSnapshotsRejectBeforePlacement() {
        Fixture endpoint = new Fixture();
        endpoint.normalization = 4;
        HostOperation exactEndpoint = endpoint.start(new GridInsertionWriter.Request(TYPE, Integer.MAX_VALUE - 6, 3));
        endpoint.deliver();
        endpoint.next();
        endpoint.guard.requireAvailable(device(endpoint));
        assertTrue(
                exactEndpoint
                                .result
                                .join()
                                .getAsJsonObject("actualPosition")
                                .get("x")
                                .getAsInt()
                        == Integer.MAX_VALUE - 2,
                "actual endpoint within int32 is not rejected by applying a second hypothetical reflow after settlement");
        for (int invalid = 0; invalid < 6; invalid++) {
            Fixture f = new Fixture();
            GridInsertionWriter.Request request = f.request();
            if (invalid == 0) f.incoming.put(WIDTH, 0);
            if (invalid == 1) f.incoming.put(HEIGHT, -1);
            if (invalid == 2) request = new GridInsertionWriter.Request(TYPE, Integer.MAX_VALUE, 3);
            if (invalid == 3) request = new GridInsertionWriter.Request(TYPE, 8, Integer.MAX_VALUE);
            if (invalid == 4) request = new GridInsertionWriter.Request(TYPE, 8, 65540);
            if (invalid == 5) request = new GridInsertionWriter.Request(TYPE, Integer.MAX_VALUE - 3, 3);
            HostOperation op = f.start(request);
            f.deliver();
            f.next();
            assertTrue(
                    assertUnknown("out_of_bounds", op) != null && f.placements == 0 && f.cleanups == 1,
                    "incoming footprint/endpoints/full y span/cumulative rightward overflow checked before allocation");
            f.guard.requireAvailable(device(f));
        }
        for (int changed = 0; changed < 7; changed++) {
            Fixture f = new Fixture();
            HostOperation op = f.start();
            if (changed == 0) f.model.first.put(X, 20);
            if (changed == 1) f.model.graph.put(MODULES, List.of(f.model.second));
            if (changed == 2) f.model.proxy.put(TARGET, f.model.owner);
            if (changed == 3) f.context.values.put(NativeReadContext.Field.CURRENT_DT, new Object());
            if (changed == 4) f.requestError = "unsafe_state";
            if (changed == 5) f.model.first.put(WIDTH, 4);
            if (changed == 6) f.placementReady = false;
            f.deliver();
            assertTrue(
                    f.cleanups == 1 && f.placements == 0,
                    "captured membership/layout/context/target/safety/placement prerequisite cannot be rebaselined");
            f.next();
            assertTrue(unsuccessful(op), "preplacement rejection never becomes fake success");
            if (changed == 2 || changed == 3 || changed == 6)
                assertTrue(
                        f.guard.quarantined(), "uncorroborated original native context remains blocked at settlement");
            else f.guard.requireAvailable(device(f));
        }
    }

    @Test
    void nativeFailuresPoisonButDeliveryFailuresDoNot() {
        for (int boundary = 0; boundary < 6; boundary++) {
            Fixture f = new Fixture();
            if (boundary == 0) f.failLaunch = true;
            HostOperation op = f.start();
            if (boundary == 1) {
                f.requestError = "deadline";
                f.failCleanup = true;
            }
            if (boundary == 2) f.failPlacement = true;
            if (boundary == 3) f.failSuccess = true;
            if (boundary == 4) f.failEnqueue = true;
            if (boundary == 5) f.badMembership = true;
            if (boundary != 0) f.deliver();
            if (boundary == 5) f.next();
            assertTrue(
                    unsuccessful(op) && f.guard.quarantined(),
                    "launch/cleanup/placement/callback/scheduling/native verification failure quarantines");
            if (boundary == 1)
                assertTrue(
                        f.order.indexOf("cleanup") < f.order.indexOf("failure"),
                        "even failed cleanup is attempted before failure delivery");
        }
        Fixture callbackError = new Fixture();
        callbackError.failConverted = true;
        HostOperation callbackOp = callbackError.start();
        callbackError.deliver();
        callbackError.next();
        assertTrue(
                unsuccessful(callbackOp)
                        && callbackError.cleanups == 1
                        && callbackError.guard.quarantined()
                        && callbackError.order.indexOf("cleanup") < callbackError.order.indexOf("failure"),
                "unexpected callback/linkage Error still cleans first, returns normally and poisons native lifetime");
        for (int changed = 0; changed < 5; changed++) {
            Fixture settled = new Fixture();
            HostOperation failed = settled.start();
            settled.deliver();
            if (changed == 0) settled.context.values.put(NativeReadContext.Field.ROOT, new Object());
            if (changed == 1) ((Node) settled.incoming.values.get(DESCRIPTOR)).put(TYPE_ID, new UUID(9, 9));
            if (changed == 2) settled.incoming.put(Y, 4);
            if (changed == 3) settled.incoming.put(X, 7);
            if (changed == 4) settled.model.document = false;
            settled.next();
            assertTrue(
                    unsuccessful(failed) && settled.guard.quarantined(),
                    "settlement independently verifies native thread/original context/new type/row/rightward layout");
        }
        Fixture preparation = new Fixture();
        preparation.failPrepare = true;
        assertRpcError("native_unavailable", preparation.start());
        assertTrue(preparation.loads == 0, "known prelaunch failure releases own reservation");
        preparation.guard.requireAvailable(device(preparation));
        Fixture resolver = new Fixture();
        resolver.failResolve = true;
        assertRpcError("native_unavailable", resolver.start());
        resolver.guard.requireAvailable(device(resolver));
        Fixture serialization = new Fixture();
        serialization.failDelivery = true;
        HostOperation op = serialization.start();
        serialization.deliver();
        serialization.next();
        assertTrue(
                assertUnknown("out_of_bounds", op) != null && serialization.placements == 1,
                "serialization-only delivery failure preserves uncertain RPC outcome");
        serialization.guard.requireAvailable(device(serialization));
        Fixture readback = new Fixture();
        op = readback.start();
        readback.deliver();
        readback.model.failures.put(
                INPUTS, Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "transport graph unsupported"));
        readback.next();
        assertTrue(unsuccessful(op), "bounded graph serialization failure cannot fabricate insertion success");
        readback.guard.requireAvailable(device(readback));
        Fixture callbackReporting = new Fixture();
        callbackReporting.failFailureNotification = true;
        callbackReporting.start();
        callbackReporting.callback.earlyError("error");
        assertTrue(
                callbackReporting.guard.quarantined(),
                "throwing outward observer cannot escape native adapter error handling");
        callbackReporting.deliver();
        callbackReporting.next();
        assertTrue(callbackReporting.cleanups == 1, "throwing outward observer does not suppress later native cleanup");
    }

    @Test
    void reflectedConversionFailuresCleanBeforeStickyQuarantine() {
        for (Throwable cause : List.of(
                new LinkageError("native conversion linkage"),
                new IllegalStateException("native conversion failure"),
                new Exception("native checked conversion failure"),
                Errors.notApplied(Reason.DEADLINE, "native code threw an RPC-shaped error"))) {
            Fixture f = new Fixture();
            f.reflectedConversion = new ReflectedConversion(cause);
            HostOperation op = f.start();
            f.deliver();
            assertTrue(
                    f.cleanups == 1 && f.placements == 0 && f.successes == 0 && f.guard.quarantined(),
                    "actual Method.invoke conversion failure is cleaned once and quarantined before settlement");
            assertTrue(
                    f.cleanupError instanceof RpcException
                            && Bitwig614Runtime.isInvocationFailure(f.cleanupError)
                            && f.cleanupError.getCause() instanceof java.lang.reflect.InvocationTargetException
                            && f.cleanupError.getCause().getCause() == cause,
                    "RPC translation retains reflection boundary and original native throwable identity");
            assertTrue(
                    f.order.equals(List.of("load", "cleanup", "failure", "enqueue"))
                            && assertUnknown("native_unavailable", op) != null,
                    "cleanup returns before outward unknown notification and ordinary native settlement scheduling");
            f.next();
            assertTrue(
                    new GridTopologyQuarantine(f.process).quarantined()
                            && GridTopologyQuarantine.NATIVE.equals(f.guard.origin(device(f))),
                    "healthy later context cannot release translated native conversion failure quarantine");
            JsonObject released = f.writer.ack(f.model.proxy, TRACK, device(f), () -> {});
            assertTrue(
                    released.get("outcome").getAsString().equals("applied")
                            && !f.guard.quarantined()
                            && released.getAsJsonObject("recorded")
                                    .get("origin")
                                    .getAsString()
                                    .equals(GridTopologyQuarantine.NATIVE),
                    "ack releases a native-failure hold only for the named device after re-reading it");
        }
        for (String rejection : List.of("deadline", "stale_context")) {
            Fixture f = new Fixture();
            HostOperation op = f.start();
            f.requestError = rejection;
            f.deliver();
            assertTrue(
                    f.cleanups == 1
                            && !f.guard.quarantined()
                            && !op.result.isDone()
                            && !Bitwig614Runtime.isInvocationFailure(f.cleanupError),
                    "ordinary deadline/context validation remains a cleanup-and-settle rejection");
            f.next();
            f.guard.requireAvailable(device(f));
            assertUnknown(rejection, op);
        }
    }

    public static final class ReflectedConversion {
        static final java.lang.reflect.Method READ = method();
        private final Throwable failure;

        ReflectedConversion(Throwable failure) {
            this.failure = failure;
        }

        private static java.lang.reflect.Method method() {
            try {
                return ReflectedConversion.class.getMethod("converted");
            } catch (NoSuchMethodException error) {
                throw new AssertionError(error);
            }
        }

        public List<?> converted() throws Exception {
            if (failure instanceof Error) throw (Error) failure;
            throw (Exception) failure;
        }
    }

    @Test
    void allTopologyWritersConsultPendingLifetime() {
        Fixture f = new Fixture();
        f.start();
        GridSnapshotReaderTest.ModuleWriterFixture modules = new GridSnapshotReaderTest.ModuleWriterFixture();
        GridModuleWriter writer = new GridModuleWriter(
                modules.model.reader,
                modules.model,
                modules.context.reader,
                modules,
                new GridTopologyQuarantine(f.process));
        for (boolean move : List.of(false, true)) {
            Runnable call = () -> {
                if (move)
                    writer.move(
                            modules.model.proxy,
                            TRACK,
                            new UUID(1, 1),
                            new GridModuleWriter.Move(7, 3, 10, 3),
                            () -> {},
                            () -> {},
                            value -> {},
                            error -> {});
                else
                    writer.delete(
                            modules.model.proxy, TRACK, new UUID(1, 1), () -> {}, () -> {}, value -> {}, error -> {});
            };
            assertRpcError("busy", call);
        }
        GridSnapshotReaderTest.ConnectionFixture connections =
                new GridSnapshotReaderTest.ConnectionFixture(new GridTopologyQuarantine(f.process));
        assertRpcError("busy", connections.start(connections.request(null, connections.output())));
        assertTrue(
                connections.calls == 0 && modules.calls == 0,
                "replacement guards block move/delete/connection before native calls");
        Fixture second = new Fixture(f.process, null);
        assertRpcError("busy", second.start());
        assertTrue(second.loads == 0, "overlapping insertion cannot replace pending loader owner");
    }

    private static List<GridInsertionWriter.Request> batchOf(UUID... types) {
        List<GridInsertionWriter.Request> out = new ArrayList<>();
        for (int i = 0; i < types.length; i++) out.add(new GridInsertionWriter.Request(types[i], 8 + 4 * i, 3));
        return out;
    }

    @Test
    void typeNamesResolveExactlyOrRejectWithCandidates() {
        Fixture f = new Fixture();
        f.catalogNames("Low-pass", "Low-pass LD", "Low-pass MG", "Oscillator");
        for (String name : List.of("low-pass", "LOW PASS", "lowpass", " Low  Pass ")) {
            Fixture g = new Fixture();
            g.catalogNames("Low-pass", "Low-pass LD", "Oscillator");
            HostOperation op = g.start(new GridInsertionWriter.Request(null, name, 8, 3));
            assertTrue(
                    op.dispatched && g.loads == 1,
                    "typeName '" + name + "' resolves case/space/hyphen-insensitively to the exact entry");
            g.deliver();
            g.next();
            assertTrue(
                    op.result.join().get("typeId").getAsString().equals(new UUID(2, 100).toString()),
                    "the resolved typeId is what was inserted");
        }
        RpcException error = assertThrows(RpcException.class, () -> {
            f.writer.insert(
                    f.model.proxy,
                    TRACK,
                    new GridInsertionWriter.Request(null, "Low-pass M", 8, 3),
                    () -> {},
                    () -> {},
                    v -> {},
                    e -> {});
        });
        assertTrue(
                error.reason.wire().equals("invalid_params")
                        && !error.unknown
                        && f.loads == 0
                        && error.getMessage().contains("Low-pass MG")
                        && f.guard.blockedReason() == null,
                "a partial name is not a match; candidates are listed and nothing is reserved or loaded");
        assertTrue(
                GridNames.matches("\u2260", "\u2260")
                        && !GridNames.matches("<", "\u2260")
                        && !GridNames.matches("Oscillator", "<")
                        && GridNames.matches("Oscillator", ""),
                "a symbol-only catalog query matches that name only, not everything");
        assertTrue(
                GridNames.nearest(List.of("<", "<=", "Oscillator"), "<=", 5)
                        .get(0)
                        .equals("<="),
                "symbol-only misses still get nearest candidates");
        f.catalogNames("Filter", "filter", "Oscillator");
        error = null;
        try {
            f.writer.insert(
                    f.model.proxy,
                    TRACK,
                    new GridInsertionWriter.Request(null, "FILTER", 8, 3),
                    () -> {},
                    () -> {},
                    v -> {},
                    e -> {});
        } catch (RpcException caught) {
            error = caught;
        }
        assertTrue(
                error.getMessage().contains("ambiguous")
                        && error.getMessage().contains(new UUID(2, 100).toString())
                        && error.getMessage().contains(new UUID(2, 101).toString())
                        && f.loads == 0,
                "two exact matches are ambiguous and both are listed");
        error = null;
        try {
            f.writer.insert(
                    f.model.proxy,
                    TRACK,
                    new GridInsertionWriter.Request(null, "Oscilator", 8, 3),
                    () -> {},
                    () -> {},
                    v -> {},
                    e -> {});
        } catch (RpcException caught) {
            error = caught;
        }
        assertTrue(error.getMessage().contains("Oscillator"), "a typo lists the nearest names");
        // exact string beats case-insensitive beats loose; symbol-only names match only exactly; ambiguity says use
        // typeId
        for (String[] c : new String[][] {
            {"Slope \u2197", "1"}, {"Slope \u2198", "2"}, {"\u2260", "3"}, {"<", "4"}, {"=", "5"}, {"slope \u2197", "1"}
        }) {
            Fixture g = new Fixture();
            g.catalogNames("Slope \u2197", "Slope \u2198", "\u2260", "<", "=", "Oscillator");
            HostOperation op = g.start(new GridInsertionWriter.Request(null, c[0], 8, 3));
            g.deliver();
            g.next();
            long index = "Slope \u2197".equalsIgnoreCase(c[0])
                    ? 100
                    : "Slope \u2198".equals(c[0]) ? 101 : "\u2260".equals(c[0]) ? 102 : "<".equals(c[0]) ? 103 : 104;
            assertTrue(
                    op.result.join().get("typeId").getAsString().equals(new UUID(2, index).toString()),
                    "name '" + c[0] + "' resolves to its own entry");
        }
        f.catalogNames("Filter", "Oscillator", "\u2260");
        for (String miss : List.of("\u2264", "!=")) {
            error = null;
            try {
                f.writer.insert(
                        f.model.proxy,
                        TRACK,
                        new GridInsertionWriter.Request(null, miss, 8, 3),
                        () -> {},
                        () -> {},
                        v -> {},
                        e -> {});
            } catch (RpcException caught) {
                error = caught;
            }
            assertTrue(
                    error.getMessage().contains("matches no catalog module exactly") && f.loads == 0,
                    "symbol-only miss '" + miss + "' is not loosely matched");
        }
        f.catalogNames("Filter", "filter", "Oscillator");
        error = null;
        try {
            f.writer.insert(
                    f.model.proxy,
                    TRACK,
                    new GridInsertionWriter.Request(null, "FILTER", 8, 3),
                    () -> {},
                    () -> {},
                    v -> {},
                    e -> {});
        } catch (RpcException caught) {
            error = caught;
        }
        assertTrue(error.getMessage().contains("use typeId"), "ambiguity tells the caller to use typeId");
        assertTrue(
                GridNames.resolve(List.of("Filter", "filter"), x -> x, "filter").equals(List.of("filter")),
                "exact case-sensitive match wins over a case-insensitive twin");
        for (String bad : List.of(
                "{\"typeId\":\"" + TYPE + "\",\"typeName\":\"x\",\"x\":1,\"y\":1}",
                "{\"x\":1,\"y\":1}",
                "{\"typeName\":\"\",\"x\":1,\"y\":1}"))
            assertRpcError("invalid_params", () -> GridInsertionWriter.Request.parse(HostFixture.json(bad)));
    }

    @Test
    void batchInsertsInOrderAndReturnsOneDiff() {
        Fixture f = new Fixture();
        f.fresh = true;
        f.catalogNames("Alpha", "Beta");
        List<GridInsertionWriter.Request> requests = List.of(
                new GridInsertionWriter.Request(null, "alpha", 8, 3),
                new GridInsertionWriter.Request(new UUID(2, 101), 12, 3));
        HostOperation op = f.startAll(requests);
        assertTrue(
                op.dispatched && f.loads == 1 && !op.result.isDone(),
                "first entry loads through the single path and is not completion");
        f.deliver();
        assertTrue(f.loads == 1 && !op.result.isDone(), "second entry waits for the first native settlement");
        f.next();
        assertTrue(
                f.loads == 2 && !op.result.isDone() && f.placements == 1,
                "second entry starts only after the first settled");
        f.deliver();
        f.next();
        JsonObject result = op.result.join();
        f.guard.requireAvailable(device(f));
        JsonArray modules = result.getAsJsonArray("modules");
        assertEquals(List.of("applied", "applied"), outcomes(modules));
        assertTrue(
                result.get("outcome").getAsString().equals("applied")
                        && result.get("written").getAsInt() == 2
                        && modules.size() == 2
                        && modules.get(0)
                                .getAsJsonObject()
                                .get("typeId")
                                .getAsString()
                                .equals(new UUID(2, 100).toString())
                        && modules.get(1)
                                .getAsJsonObject()
                                .get("typeId")
                                .getAsString()
                                .equals(new UUID(2, 101).toString())
                        && modules.get(0)
                                .getAsJsonObject()
                                .get("moduleId")
                                .getAsString()
                                .equals(new UUID(1, 1025).toString())
                        && modules.get(1)
                                .getAsJsonObject()
                                .get("moduleId")
                                .getAsString()
                                .equals(new UUID(1, 1026).toString()),
                "one result with per-entry typeId, moduleId and outcome, in request order");
        JsonObject diff = new JsonObject();
        GridDiff.diff(result.getAsJsonObject("graphBefore"), result.getAsJsonObject("graph"), diff);
        assertTrue(
                diff.getAsJsonArray("modulesAdded").size() == 2
                        && diff.get("moduleCount").getAsInt() == 4,
                "one graph diff covers every inserted module");
    }

    @Test
    void batchValidatesEverythingBeforeLoadingAnything() {
        Fixture f = new Fixture();
        f.catalogNames("Alpha", "Beta");
        for (List<GridInsertionWriter.Request> bad : List.of(
                List.of(
                        new GridInsertionWriter.Request(null, "alpha", 8, 3),
                        new GridInsertionWriter.Request(null, "gamma", 12, 3)),
                List.of(
                        new GridInsertionWriter.Request(new UUID(2, 100), 8, 3),
                        new GridInsertionWriter.Request(new UUID(9, 9), 12, 3)),
                List.of(
                        new GridInsertionWriter.Request(new UUID(2, 100), 8, 3),
                        new GridInsertionWriter.Request(new UUID(2, 100), Integer.MAX_VALUE, 3)),
                List.of(
                        new GridInsertionWriter.Request(new UUID(2, 100), 8, 3),
                        new GridInsertionWriter.Request(new UUID(2, 100), 8, -100000)))) {
            HostOperation op = f.startAll(bad);
            RpcException error = failed(op);
            assertTrue(
                    !error.unknown && f.loads == 0 && !op.dispatched && f.guard.blockedReason() == null,
                    "any bad type or geometry rejects the whole batch before reservation, dispatch or loading");
        }
        RpcException named = null;
        try {
            f.writer.insertAll(
                    f.model.proxy,
                    TRACK,
                    List.of(
                            new GridInsertionWriter.Request(null, "alpha", 8, 3),
                            new GridInsertionWriter.Request(null, "gamma", 12, 3)),
                    () -> Long.MAX_VALUE,
                    () -> {},
                    () -> {},
                    v -> {},
                    e -> {});
        } catch (RpcException caught) {
            named = caught;
        }
        assertTrue(
                named != null
                        && named.getMessage().contains("#1")
                        && !named.getMessage().contains("#0"),
                "the failing entry is named");
        Fixture crowded = new Fixture();
        crowded.population(1023);
        HostOperation op = crowded.startAll(batchOf(TYPE, TYPE));
        assertTrue(
                !assertRpcError("out_of_bounds", op).unknown && crowded.loads == 0,
                "destination count plus the whole batch must fit before loading");
    }

    private static RpcException failed(HostOperation op) {
        {
            java.util.concurrent.CompletionException error =
                    assertThrows(java.util.concurrent.CompletionException.class, () -> op.result.join());
            return (RpcException) error.getCause();
        }
    }

    @Test
    void batchStopsAtFirstFailureOrUnknown() {
        // Failure before the first dispatch is an ordinary not_applied error.
        Fixture first = new Fixture();
        first.failResolve = true;
        assertRpcError("native_unavailable", first.startAll(batchOf(TYPE, TYPE)));
        assertTrue(first.loads == 0, "nothing dispatched means an error, not a batch result");
        // A later prelaunch failure leaves an observed partial result with a diff.
        Fixture later = new Fixture();
        later.fresh = true;
        HostOperation op = later.startAll(batchOf(TYPE, TYPE, TYPE));
        later.deliver();
        later.failResolve = true;
        later.next();
        JsonObject partial = op.result.join();
        JsonArray modules = partial.getAsJsonArray("modules");
        assertEquals(
                List.of("applied", "not_applied/native_unavailable", "not_applied/not_reached"), outcomes(modules));
        assertTrue(
                partial.get("outcome").getAsString().equals("not_applied")
                        && partial.get("written").getAsInt() == 1
                        && later.loads == 1
                        && partial.has("graph")
                        && partial.has("graphBefore"),
                "failure before an entry's dispatch is not_applied; the rest is not reached; applied part keeps its diff");
        later.guard.requireAvailable(device(later));
        // A failure after an entry's native loader dispatch is unknown and the topology quarantine stays.
        Fixture unknown = new Fixture();
        unknown.fresh = true;
        op = unknown.startAll(batchOf(TYPE, TYPE, TYPE));
        unknown.deliver();
        unknown.next();
        unknown.failConverted = true;
        unknown.deliver();
        JsonObject result = op.result.join();
        modules = result.getAsJsonArray("modules");
        assertEquals(List.of("applied", "unknown/internal", "not_applied/not_reached"), outcomes(modules));
        assertTrue(
                result.get("outcome").getAsString().equals("unknown")
                        && !result.has("graph")
                        && unknown.guard.quarantined()
                        && unknown.loads == 2,
                "a failure after dispatch is unknown, offers no diff, never retries and keeps the process quarantine");
    }

    @Test
    void batchReportsFinalPositionsAndStopsBeforeTheDeadline() {
        Fixture f = new Fixture();
        f.fresh = true;
        HostOperation op = f.startAll(batchOf(TYPE, TYPE));
        f.deliver();
        // The second insertion's native reflow pushes the first module right before the batch settles.
        f.reflow = true;
        f.next();
        f.deliver();
        f.next();
        JsonObject result = op.result.join();
        JsonArray modules = result.getAsJsonArray("modules");
        JsonObject first = modules.get(0).getAsJsonObject();
        int finalX = -1;
        for (JsonElement row : result.getAsJsonObject("graph").getAsJsonArray("modules"))
            if (row.getAsJsonObject()
                    .get("instanceId")
                    .getAsString()
                    .equals(first.get("moduleId").getAsString()))
                finalX = row.getAsJsonObject().get("x").getAsInt();
        assertTrue(
                finalX > 8
                        && first.getAsJsonObject("actualPosition").get("x").getAsInt() == finalX
                        && first.get("outcome").getAsString().equals("applied"),
                "entry positions are refreshed from the final graph, matching the diff");
        Fixture late = new Fixture();
        late.fresh = true;
        op = late.startAll(batchOf(TYPE, TYPE, TYPE));
        late.deliver();
        late.remaining = 0;
        late.next();
        result = op.result.join();
        modules = result.getAsJsonArray("modules");
        assertEquals(List.of("applied", "not_applied/deadline", "not_applied/not_reached"), outcomes(modules));
        assertTrue(
                late.loads == 1 && result.get("outcome").getAsString().equals("not_applied") && result.has("graph"),
                "with too little time left the batch stops cleanly, keeps its diff and dispatches nothing more");
        late.guard.requireAvailable(device(late));
    }

    @Test
    void slowEntriesStopTheBatchEarlyAsPartial() {
        long second = 1_000_000_000L;
        Fixture f = new Fixture();
        f.fresh = true;
        long[] now = {0};
        f.writer.clock = () -> now[0];
        f.remaining = 5 * second;
        HostOperation op = f.startAll(batchOf(TYPE, TYPE, TYPE));
        now[0] = 3 * second;
        f.deliver();
        f.next(); // the first entry took 3 s; 5 s left is > 1.5 s but < 2 x 3 s
        JsonObject result = op.result.join();
        JsonArray modules = result.getAsJsonArray("modules");
        assertEquals(List.of("applied", "not_applied/deadline", "not_applied/not_reached"), outcomes(modules));
        assertTrue(
                f.loads == 1 && result.get("slowestEntryMs").getAsLong() == 3000 && result.has("graph"),
                "a slow entry stops the batch before the next one (with a diff), never running into the deadline as unknown");
        f.guard.requireAvailable(device(f));
        Fixture fast = new Fixture();
        fast.fresh = true;
        long[] t = {0};
        fast.writer.clock = () -> t[0];
        fast.remaining = 5 * second;
        op = fast.startAll(batchOf(TYPE, TYPE));
        t[0] = second / 10;
        fast.deliver();
        fast.next();
        fast.deliver();
        fast.next();
        assertTrue(
                op.result.join().get("outcome").getAsString().equals("applied") && fast.loads == 2,
                "fast entries keep the fixed 1.5 s margin");
    }

    @Test
    void variableFootprintSettlesWithoutQuarantine() {
        Fixture f = new Fixture();
        f.growOnPlace = 2; // Merge/Sum: detached 1x1, sized once attached
        HostOperation op = f.start();
        f.deliver();
        f.next();
        JsonObject result = op.result.join();
        f.guard.requireAvailable(device(f));
        int height = -1;
        for (JsonElement row : result.getAsJsonObject("graph").getAsJsonArray("modules"))
            if (row.getAsJsonObject()
                    .get("instanceId")
                    .getAsString()
                    .equals(result.get("moduleId").getAsString()))
                height = row.getAsJsonObject().get("height").getAsInt();
        assertTrue(
                height == 2 && f.guard.origin(device(f)) == null,
                "an inserted module that resizes on attach settles normally with its actual footprint");
        Fixture survivor = new Fixture();
        op = survivor.start();
        survivor.deliver();
        survivor.model.first.put(HEIGHT, 3);
        survivor.next();
        assertTrue(
                unsuccessful(op) && GridTopologyQuarantine.SETTLEMENT.equals(survivor.guard.origin(device(survivor))),
                "a survivor footprint change is still a settlement mismatch");
    }

    @Test
    void settlementMismatchIsReleasedOnlyByConsistentAck() {
        Fixture f = new Fixture();
        HostOperation op = f.start();
        f.deliver();
        f.incoming.put(Y, 4);
        f.next(); // the native layout put the new module on another row
        assertTrue(
                assertUnknown("readback_mismatch", op) != null
                        && f.guard.quarantined()
                        && GridTopologyQuarantine.SETTLEMENT.equals(f.guard.origin(device(f))),
                "settlement disagreement is unknown and quarantined, as before");
        RpcException held = assertRpcError("unresolved", () -> f.guard.requireAvailable(device(f)));
        assertEquals(
                "Inspect with grid.get, then ack grid:" + device(f) + ".", held.next, "the refusal names the hold id");
        JsonObject state = Bitwig614GridAdapter.quarantineState(f.guard);
        JsonObject entry = state.getAsJsonArray("devices").get(0).getAsJsonObject();
        assertTrue(
                state.get("state").getAsString().equals("quarantined")
                        && entry.get("origin").getAsString().equals("settlement_mismatch")
                        && entry.get("trackId").getAsString().equals(TRACK.toString())
                        && entry.getAsJsonObject("error")
                                .get("code")
                                .getAsString()
                                .equals("readback_mismatch")
                        && entry.get("moduleId").getAsString().equals(new UUID(1, 1025).toString()),
                "capabilities list the quarantined device with its origin and the recorded error/module");
        String otherOwner = f.guard.reserve(OTHER);
        f.guard.poison(otherOwner, OTHER, new JsonObject());
        // An inconsistent graph keeps the hold: a nonempty native placement list, a busy undo manager, twin UUIDs.
        f.placementReady = false;
        RpcException pending = assertRpcError("readback_mismatch", () -> ack(f));
        assertTrue(
                pending.getMessage().contains("unsafe_state") && f.guard.quarantined(device(f)),
                "a nonempty native placement list keeps the hold");
        f.placementReady = true;
        f.context.values.put(GridTopologyContext.Field.UNDO_ACTIVE, true);
        assertRpcError("readback_mismatch", () -> ack(f));
        assertTrue(f.guard.quarantined(device(f)), "an active undo transaction keeps the hold");
        f.context.values.put(GridTopologyContext.Field.UNDO_ACTIVE, false);
        f.model.second.put(INSTANCE_ID, f.model.first.values.get(INSTANCE_ID));
        assertRpcError("readback_mismatch", () -> ack(f));
        assertTrue(f.guard.quarantined(device(f)), "duplicate module UUIDs keep the hold");
        f.model.second.put(INSTANCE_ID, new UUID(1, 2));
        assertTrue(
                f.writer.ack(f.model.proxy, TRACK, OTHER, () -> {}) == null,
                "another device's hold is not this proxy's to release");
        JsonObject released = ack(f);
        JsonObject module = released.getAsJsonObject("found").getAsJsonObject("module");
        assertTrue(
                released.get("outcome").getAsString().equals("applied")
                        && !f.guard.quarantined(device(f))
                        && module.get("inserted").getAsBoolean()
                        && module.getAsJsonObject("actualPosition").get("y").getAsInt() == 4
                        && released.getAsJsonObject("found").get("moduleCount").getAsInt() == 3
                        && released.getAsJsonObject("found")
                                        .get("modulesBefore")
                                        .getAsInt()
                                == 2,
                "consistent graph releases and reports the late module");
        assertTrue(
                f.guard.quarantined(OTHER) && f.guard.ack(OTHER, f.guard.detail(OTHER)),
                "acking one device leaves a sibling held until it is named itself");
        f.guard.requireAvailable(device(f));
        assertRpcError("unknown_hold", () -> ack(f));
    }

    @Test
    void ackRefusesARecordThatNoLongerMatchesTheGraph() {
        Fixture f = new Fixture();
        JsonObject recorded = new JsonObject();
        recorded.addProperty("moduleId", new UUID(1, 1).toString());
        recorded.addProperty("typeId", new UUID(9, 9).toString());
        f.guard.poison("owner", device(f), recorded);
        RpcException retyped = assertRpcError("readback_mismatch", () -> ack(f));
        assertTrue(
                retyped.getMessage().contains("different type") && f.guard.quarantined(device(f)),
                "a recorded module UUID that now has another type keeps the hold");
        f.process.setProperty(GridTopologyQuarantine.DEVICE_PREFIX + device(f), "not json");
        assertTrue(
                ack(f).getAsJsonObject("found").get("moduleCount").getAsInt() == 2 && !f.guard.quarantined(),
                "a malformed record is released on a consistent graph without echoing it");
        f.guard.poison("owner", device(f), new JsonObject());
        int[] edits = {0};
        assertRpcError(
                "unknown_hold",
                () -> f.writer.ack(f.model.proxy, TRACK, device(f), () -> {
                    JsonObject changed = new JsonObject();
                    changed.addProperty("edit", edits[0]++);
                    f.guard.poison("owner", device(f), changed);
                }));
        assertTrue(f.guard.quarantined(device(f)), "a hold rewritten while it was checked stays");
        f.model.device.put(GRAPH, null);
        assertTrue(
                f.writer.ack(f.model.proxy, TRACK, device(f), () -> {}) == null, "a non-Grid device has no Grid hold");
    }

    @Test
    void malformedNativeCatalogAndGeometryFailClosed() {
        assertInvalidParams(() -> GridInsertionWriter.Request.parseBatch(HostFixture.json("{\"modules\":[1]}")));
        for (int bad = 0; bad < 4; bad++)
            for (boolean named : List.of(false, true)) {
                Fixture f = new Fixture();
                Object entry = f.model.catalog.get(0);
                if (bad == 0) f.model.catalog = null;
                if (bad == 1) f.model.catalog = Collections.nCopies(GridSnapshotReader.MAX_CATALOG + 1, entry);
                if (bad == 2) f.model.catalog = List.of(new Node());
                if (bad == 3) ((Node) entry).put(ENTRY_ID, "not a uuid");
                HostOperation op =
                        f.start(named ? new GridInsertionWriter.Request(null, "Catalog module", 8, 3) : f.request());
                assertRpcError(
                        bad == 1 ? "out_of_bounds" : bad == 3 && !named ? "invalid_params" : "native_unavailable", op);
                assertTrue(f.loads == 0, "a malformed catalog loads nothing");
            }
        for (int bad = 0; bad < 2; bad++) {
            Fixture f = new Fixture();
            if (bad == 0) f.model.first.put(DESCRIPTOR, null);
            else f.model.first.put(WIDTH, "2");
            assertRpcError("native_unavailable", f.start());
            assertTrue(f.loads == 0, "unreadable native geometry or identity loads nothing");
        }
        Fixture detached = new Fixture();
        detached.model.proxy.put(TARGET, null);
        assertRpcError("invalid_target", () -> ack(detached));
    }

    private static JsonObject ack(Fixture f) {
        return f.writer.ack(f.model.proxy, TRACK, device(f), () -> {});
    }

    @Test
    void lateCallbackSettlesAndAckRecoversItsMismatch() {
        Fixture f = new Fixture();
        HostOperation op = f.start();
        op.result.cancel(false);
        assertRpcError("unknown_hold", () -> ack(f));
        assertTrue(f.guard.blockedReason() != null, "ack cannot release a pending native callback");
        Object current = f.context.values.get(NativeReadContext.Field.CURRENT_DT);
        f.requestError = "deadline";
        f.deliver(); // the late callback is rejected and cleaned
        f.context.values.put(NativeReadContext.Field.CURRENT_DT, new Object());
        f.next(); // and settles in another Dt
        assertTrue(
                f.cleanups == 1
                        && f.placements == 0
                        && GridTopologyQuarantine.SETTLEMENT.equals(f.guard.origin(device(f))),
                "a late callback whose settlement context disagreed is a recoverable mismatch, not sticky");
        assertRpcError("readback_mismatch", () -> ack(f));
        assertTrue(f.guard.quarantined(device(f)), "still another Dt: the hold stays");
        f.context.values.put(NativeReadContext.Field.CURRENT_DT, current);
        JsonObject released = ack(f);
        assertTrue(
                released.get("outcome").getAsString().equals("applied")
                        && !released.getAsJsonObject("found").has("module"),
                "back in the default Dt, ack releases");
        f.guard.requireAvailable(device(f));
        Fixture plain = new Fixture();
        HostOperation late = plain.start();
        plain.requestError = "deadline";
        plain.deliver();
        plain.next();
        assertTrue(
                unsuccessful(late) && plain.guard.origin(device(plain)) == null,
                "an ordinary late callback settles and releases by itself");
    }

    static final class Fixture implements GridInsertionWriter.Access {
        final GridSnapshotReaderTest.Fixture model = new GridSnapshotReaderTest.Fixture();
        final GridSnapshotReaderTest.ModuleContextFixture context = new GridSnapshotReaderTest.ModuleContextFixture();
        final Properties process;
        final GridTopologyQuarantine guard;
        final GridInsertionWriter writer;
        final Deque<Runnable> events = new ArrayDeque<>();
        final Consumer<Runnable> queue;
        final List<String> order = new ArrayList<>();
        Node incoming = model.module(1024, null);
        final Object result = new Object(), completion = new Object();
        boolean fresh;
        int created;
        List<?> loaded = List.of(incoming);
        GridInsertionBridge.Callback callback;
        int loads, placements, cleanups, successes, normalization, growOnPlace;
        boolean reflow, deleted, parented, owned, placementReady = true;
        boolean failResolve,
                failPrepare,
                failLaunch,
                failConverted,
                failCleanup,
                failPlacement,
                failSuccess,
                failEnqueue,
                failDelivery,
                badMembership,
                failFailureNotification;
        String requestError;
        ReflectedConversion reflectedConversion;
        Exception cleanupError;

        Fixture() {
            this(new Properties(), null);
        }

        Fixture(Consumer<Runnable> queue) {
            this(new Properties(), queue);
        }

        Fixture(Properties process, Consumer<Runnable> queue) {
            this.process = process;
            guard = new GridTopologyQuarantine(process);
            this.queue = queue == null ? events::add : queue;
            writer = new GridInsertionWriter(model.reader, model, context.reader, this, guard);
            model.catalog = List.of(new Node(ENTRY)
                    .put(ENTRY_ID, TYPE)
                    .put(ENTRY_KIND, "MODULE")
                    .put(ENTRY_NAME, "Catalog module"));
            context.override(incoming, NativeReadContext.Field.CONTEXT, null);
            context.override(incoming, NativeReadContext.Field.PROJECT, null);
        }

        void catalogNames(String... names) {
            List<Object> entries = new ArrayList<>();
            for (int i = 0; i < names.length; i++)
                entries.add(new Node(ENTRY)
                        .put(ENTRY_ID, new UUID(2, 100 + i))
                        .put(ENTRY_KIND, "MODULE")
                        .put(ENTRY_NAME, names[i]));
            model.catalog = entries;
        }

        HostOperation startAll(List<GridInsertionWriter.Request> requests) {
            HostOperation op = new HostOperation(Long.MAX_VALUE);
            try {
                editAll(
                        requests,
                        () -> {
                            if (op.result.isDone())
                                throw Errors.notApplied(Reason.CANCELLED, "request already completed");
                        },
                        () -> op.dispatched = true,
                        op.result::complete,
                        op::fail);
            } catch (Throwable error) {
                op.fail(error);
            }
            return op;
        }

        long remaining = Long.MAX_VALUE;

        void editAll(
                List<GridInsertionWriter.Request> requests,
                Runnable check,
                Runnable dispatch,
                Consumer<JsonObject> complete,
                Consumer<Throwable> fail) {
            writer.insertAll(model.proxy, TRACK, requests, () -> remaining, check, dispatch, complete, fail);
        }

        GridInsertionWriter.Request request() {
            return new GridInsertionWriter.Request(TYPE, 8, 3);
        }

        HostOperation start() {
            return start(request());
        }

        HostOperation start(GridInsertionWriter.Request request) {
            HostOperation op = new HostOperation(Long.MAX_VALUE);
            try {
                edit(
                        request,
                        () -> {
                            if (requestError != null)
                                throw Errors.notApplied(
                                        Reason.valueOf(requestError.toUpperCase(Locale.ROOT)),
                                        "request no longer admissible");
                            if (op.result.isDone())
                                throw Errors.notApplied(Reason.CANCELLED, "request already completed");
                        },
                        () -> op.dispatched = true,
                        value -> {
                            if (failDelivery) throw Errors.notApplied(Reason.OUT_OF_BOUNDS, "serialization failure");
                            op.result.complete(value);
                        },
                        error -> {
                            order.add("failure");
                            if (failFailureNotification) throw new AssertionError("observer failure");
                            op.fail(error);
                        });
            } catch (Throwable error) {
                op.fail(error);
            }
            return op;
        }

        void edit(
                GridInsertionWriter.Request request,
                Runnable check,
                Runnable dispatch,
                Consumer<JsonObject> complete,
                Consumer<Throwable> fail) {
            writer.insert(model.proxy, TRACK, request, check, dispatch, complete, fail);
        }

        void population(int size) {
            List<Object> members = new ArrayList<>();
            for (int i = 0; i < size; i++) members.add(model.module(i, model.graph));
            model.graph.put(MODULES, members);
        }

        void deliver() {
            if (callback.delivered(result, completion)) callback.returned();
        }

        void next() {
            events.removeFirst().run();
        }

        UUID lastResolved = TYPE;

        @Override
        public String resolve(UUID id) {
            lastResolved = id;
            if (failResolve) throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "missing native resource");
            return "native-resolved-resource";
        }

        @Override
        public Runnable prepareLoad(Object project, String path, GridInsertionBridge.Callback callback) {
            if (failPrepare) throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "adapter construction failed");
            if (fresh && created++ > 0) { // Each batch entry loads its own detached singleton.
                incoming = model.module(1024 + created - 1, null);
                loaded = List.of(incoming);
                context.override(incoming, NativeReadContext.Field.CONTEXT, null);
                context.override(incoming, NativeReadContext.Field.PROJECT, null);
            }
            incoming.put(DESCRIPTOR, new Node().put(TYPE_ID, lastResolved)); // The loader delivers the requested type.
            this.callback = callback;
            return () -> {
                loads++;
                order.add("load");
                if (failLaunch) throw new AssertionError("load failure");
            };
        }

        @Override
        public List<?> converted(Object result) {
            if (reflectedConversion != null)
                return (List<?>) Bitwig614Runtime.invoke(ReflectedConversion.READ, reflectedConversion);
            if (failConverted) throw new LinkageError("native callback reader failed");
            return loaded;
        }

        @Override
        public void requireDetached(Object module) {
            if (deleted || parented || owned || ((Node) module).values.get(MODULE_GRAPH) != null)
                throw Errors.notApplied(Reason.STALE_CONTEXT, "not detached live singleton");
        }

        @Override
        public void requirePlacementReady(Object graph) {
            if (!placementReady) throw Errors.notApplied(Reason.UNSAFE_STATE, "nonempty XEl list");
        }

        @Override
        public void place(Object graph, List<?> loaded, int x, int y) {
            placements++;
            order.add("place");
            if (failPlacement) throw new AssertionError("placement/close failed");
            if (badMembership) return;
            List<Object> members = new ArrayList<>((List<?>) model.graph.values.get(MODULES));
            if (reflow)
                for (Object member : members) {
                    Node node = (Node) member;
                    node.put(X, ((Integer) node.values.get(X)) + 2);
                }
            incoming.put(X, x + normalization).put(Y, y).put(MODULE_GRAPH, graph);
            members.add(incoming);
            model.graph.put(MODULES, members);
            if (growOnPlace > 0) incoming.put(HEIGHT, growOnPlace);
            context.override(incoming, NativeReadContext.Field.CONTEXT, context.context);
            context.override(incoming, NativeReadContext.Field.PROJECT, context.project);
        }

        @Override
        public void reject(Object completion, Exception error) {
            cleanups++;
            cleanupError = error;
            order.add("cleanup");
            if (failCleanup) throw new AssertionError("cleanup failed");
        }

        @Override
        public void succeed(Object completion, List<?> loaded) {
            successes++;
            order.add("success");
            if (failSuccess) throw new AssertionError("success observer failed");
        }

        @Override
        public void execute(Runnable event) {
            order.add("enqueue");
            if (failEnqueue) throw new AssertionError("dispatcher failed");
            queue.accept(event);
        }
    }
}
