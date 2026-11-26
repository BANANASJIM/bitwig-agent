package dev.bitwigagent;

import static dev.bitwigagent.RpcAssert.*;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Simulated-host tests of extension.restart and build identity; no claim about native restart behavior. */
public final class ExtensionModuleTest {

    private static int[] countRestarts(HostFixture f) {
        int[] restarts = {0};
        f.host.on("restart", args -> {
            restarts[0]++;
            return null;
        });
        return restarts;
    }

    private static JsonObject params(Boolean discard) {
        JsonObject p = new JsonObject();
        if (discard != null) p.addProperty("discardUnresolved", discard);
        return p;
    }

    @Test
    void restartRepliesFirstThenRestartsOnce() {
        HostFixture f = new HostFixture();
        int[] restarts = countRestarts(f);
        MixModule mix = f.services.mix();
        ExtensionModule module = f.services.extension();
        JsonObject result = f.call(module, "extension.restart", params(null));
        assertTrue(
                result.get("status").getAsString().equals("dispatched")
                        && result.get("completion").getAsString().equals("not_observed"),
                "dispatch-only result");
        assertTrue(result.getAsJsonArray("unresolvedDiscarded").size() == 0, "nothing unresolved listed");
        assertTrue(restarts[0] == 0, "restart is deferred until after the reply");
        assertTrue(f.tasks.size() == 1, "exactly one restart task scheduled");
        HostOperation late = f.submit(module, "extension.restart", params(null));
        while (!f.tasks.isEmpty()) f.next();
        RpcException stopped = assertRpcError("unavailable", late);
        assertTrue(stopped.outcome.equals("not_applied"), "instance stops admitting work after replying");
        assertTrue(restarts[0] == 1, "host.restart called once; no second restart");
    }

    @Test
    void unresolvedCreationRefusesUnlessDiscarded() {
        HostFixture f = new HostFixture();
        int[] restarts = countRestarts(f);
        MixModule mix = f.services.mix();
        ExtensionModule module = f.services.extension();
        JsonObject create = new JsonObject();
        create.addProperty("type", "audio");
        create.addProperty("position", -1);
        create.addProperty("name", "X");
        HostOperation a = f.submit(mix, "tracks.create", create);
        f.next();
        a.result.cancel(false);
        f.next();
        assertTrue(f.services.holds.descriptions().size() == 1, "creation is unresolved");
        HostOperation refused = f.submit(module, "extension.restart", params(null));
        f.next();
        RpcException error = assertRpcError("unresolved_mutation", refused);
        assertTrue(
                error.outcome.equals("not_applied") && error.getMessage().contains("tracks.create"),
                "refusal is not_applied and names the unresolved mutation");
        assertTrue(f.tasks.isEmpty() && restarts[0] == 0, "refusal schedules no restart");
        refused = f.submit(module, "extension.restart", params(false));
        f.next();
        assertRpcError("unresolved_mutation", refused);
        JsonObject result = f.call(module, "extension.restart", params(true));
        assertTrue(
                result.getAsJsonArray("unresolvedDiscarded").size() == 1
                        && result.getAsJsonArray("unresolvedDiscarded")
                                .get(0)
                                .getAsString()
                                .contains("tracks.create"),
                "discarded descriptions are returned");
        f.next();
        assertTrue(restarts[0] == 1, "restart proceeds once discard is explicit");
    }

    @Test
    void invalidParamsNeverSchedule() {
        HostFixture f = new HostFixture();
        ExtensionModule module = f.services.extension();
        for (String bad : List.of("{\"discardUnresolved\":1}", "{\"discardUnresolved\":null}", "{\"other\":true}")) {
            HostOperation op = f.submit(
                    module, "extension.restart", JsonParser.parseString(bad).getAsJsonObject());
            assertTrue(
                    op.result.isCompletedExceptionally() && f.tasks.isEmpty(),
                    "invalid params rejected before scheduling: " + bad);
        }
        assertTrue(HostFixture.SPECS.methods().contains("extension.restart"), "method is registered with the bridge");
    }

    @Test
    void buildIdentityDefaultsToUnknown() {
        BuildInfo info = BuildInfo.load();
        assertTrue(
                info.version.equals("unknown") && info.build.equals("unknown"), "no embedded resource means unknown");
        assertTrue(
                new BuildInfo("1.2.3", "abc1234-dirty")
                        .toJson()
                        .get("build")
                        .getAsString()
                        .equals("abc1234-dirty"),
                "build serialised");
    }
}
