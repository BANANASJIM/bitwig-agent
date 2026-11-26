package dev.bitwigagent;

import static dev.bitwigagent.RpcAssert.*;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The one place unresolved and in-flight writes are recorded, and ack releases them. */
public final class HoldsTest {
    private static List<String> ids(Holds holds) {
        return holds.list().stream().map(view -> view.id).collect(java.util.stream.Collectors.toList());
    }

    @Test
    void aHoldIsHeldUntilItsDomainReleasesIt() {
        Holds holds = new Holds();
        assertFalse(holds.held("arranger"));
        holds.place("arranger", "An arranger write is unresolved.");
        assertTrue(holds.held("arranger"));
        assertFalse(holds.held("routing"), "domains are independent");
        holds.release("arranger");
        assertFalse(holds.held("arranger"));
        assertTrue(holds.list().isEmpty());
    }

    @Test
    void placingAgainReplacesInPlaceAndKeepsTheIdAndOrder() {
        Holds holds = new Holds();
        holds.place("a", "first");
        holds.place("b", "second");
        holds.place("a", "first again");
        assertEquals(List.of("a", "b"), ids(holds));
        assertEquals("first again", holds.list().get(0).description);
    }

    @Test
    void keyedHoldsGetTheirOwnIdsAndAreReleasedByKey() {
        Holds holds = new Holds();
        Object first = "slot 1", second = "slot 2";
        holds.place("launcher.mutation", first, () -> "slot 1 is unresolved");
        holds.place("launcher.mutation", second, () -> "slot 2 is unresolved");
        assertEquals(List.of("launcher.mutation:1", "launcher.mutation:2"), ids(holds));
        assertEquals(List.of(first, second), holds.keys("launcher.mutation", Object.class));
        holds.release("launcher.mutation", first);
        assertEquals(List.of(second), holds.keys("launcher.mutation", Object.class));
        assertTrue(holds.held("launcher.mutation"));
    }

    @Test
    void aDescriptionThatSuppliesNullIsNotHeld() {
        Holds holds = new Holds();
        boolean[] rendering = {true};
        holds.job("render.job", () -> rendering[0] ? "A render is running." : null, () -> false, () -> {});
        assertTrue(holds.held("render.job"));
        assertEquals(1, holds.list().size());
        rendering[0] = false;
        assertFalse(holds.held("render.job"));
        assertTrue(holds.list().isEmpty());
        assertRpcError("unknown_hold", () -> holds.ack("render.job"));
    }

    @Test
    void ackReleasesAnUnresolvedHoldButNotAnInFlightOne() {
        Holds holds = new Holds();
        holds.place("routing.quarantine", "A routing write is unresolved.");
        holds.inFlight("routing.pending", "A routing write is pending.");
        assertEquals(
                List.of(true, false),
                holds.list().stream().map(view -> view.ackable).collect(java.util.stream.Collectors.toList()));
        assertRpcError("busy", () -> holds.ack("routing.pending"));
        assertTrue(holds.held("routing.pending"), "a refused ack releases nothing");
        assertEquals("A routing write is unresolved.", holds.ack("routing.quarantine"));
        assertFalse(holds.held("routing.quarantine"));
        assertRpcError("unknown_hold", () -> holds.ack("routing.quarantine"));
    }

    @Test
    void aJobHoldIsAckableOnlyAfterItsRequestReturned() {
        Holds holds = new Holds();
        boolean[] returned = {false}, running = {true};
        holds.job(
                "render.job",
                () -> running[0] ? "A render is running." : null,
                () -> returned[0],
                () -> running[0] = false);
        assertFalse(holds.list().get(0).ackable);
        assertRpcError("busy", () -> holds.ack("render.job"));
        returned[0] = true;
        assertTrue(holds.list().get(0).ackable);
        assertEquals("A render is running.", holds.ack("render.job"));
        assertFalse(holds.held("render.job"), "the owner gave the job up");
        running[0] = true;
        assertTrue(holds.held("render.job"), "the registration stays for the next job");
    }

    @Test
    void theRefusalNamesWhatToInspectAndWhichIdToAck() {
        Holds holds = new Holds();
        holds.place("markers", "x");
        RpcException refusal = holds.blocked("markers", "A marker write is unresolved.", "Inspect markers.list");
        assertTrue(refusal.is(Reason.UNRESOLVED) && !refusal.unknown);
        assertEquals("pending", refusal.code());
        assertEquals("Inspect markers.list, then ack markers.", refusal.next);
    }

    @Test
    void statusListsEveryHoldWithItsAckId() {
        Holds holds = new Holds();
        holds.place("markers", "A marker write is unresolved.");
        holds.inFlight("render.job", "A render is running.");
        java.util.Properties process = new java.util.Properties();
        String device = HostFixture.A + "/1f";
        process.setProperty(
                GridTopologyQuarantine.DEVICE_PREFIX + device,
                "{\"origin\":\"native_failure\",\"device\":\"" + device + "\",\"trackId\":\"" + HostFixture.A + "\"}");
        com.google.gson.JsonArray rows = DiscoveryModule.holds(holds, new GridTopologyQuarantine(process));
        assertEquals(3, rows.size());
        assertEquals("markers", rows.get(0).getAsJsonObject().get("id").getAsString());
        assertFalse(rows.get(1).getAsJsonObject().get("ackable").getAsBoolean(), "in flight");
        JsonObject grid = rows.get(2).getAsJsonObject();
        assertEquals("grid:" + device, grid.get("id").getAsString());
        assertTrue(grid.get("ackable").getAsBoolean()
                && grid.get("description").getAsString().contains(HostFixture.A));
    }

    @Test
    void ackModuleReleasesByIdAndRoutesGridIds() {
        HostFixture f = new HostFixture();
        f.services.holds.place("markers", "A marker write is unresolved.");
        AckModule ack = f.services.ack(f.services.grid(null));
        JsonObject id = new JsonObject();
        id.addProperty("id", "markers");
        JsonObject result = f.call(ack, "ack", id);
        assertEquals("applied", result.get("outcome").getAsString());
        assertEquals("A marker write is unresolved.", result.get("released").getAsString());
        assertFalse(f.services.holds.held("markers"));
        JsonObject grid = new JsonObject();
        grid.addProperty("id", "grid:not-a-device");
        HostOperation op = f.submit(ack, "ack", grid);
        f.next();
        assertRpcError("unknown_hold", op);
        assertInvalidParams(() -> ack.validate("ack", new JsonObject()));
    }
}
