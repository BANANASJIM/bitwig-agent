package dev.bitwigagent;

import com.google.gson.*;
import java.util.List;

/** Simulated-host tests of extension.restart and build identity; no claim about native restart behavior. */
public final class ExtensionModuleTest {
    private static int assertions;

    public static void main(String[] args) {
        restartRepliesFirstThenRestartsOnce();
        unresolvedCreationRefusesUnlessDiscarded();
        invalidParamsNeverSchedule();
        buildIdentityDefaultsToUnknown();
        System.out.println("ExtensionModuleTest: " + assertions + " assertions passed (simulated host only)");
    }

    private static int[] countRestarts(TransportMixFixture f) {
        int[] restarts = {0};
        TransportMixFixture.Custom inner = f.host.custom;
        f.host.custom = (name, a) -> {
            if (name.equals("restart")) {
                restarts[0]++;
                return null;
            }
            return inner.invoke(name, a);
        };
        return restarts;
    }

    private static JsonObject params(Boolean discard) {
        JsonObject p = new JsonObject();
        if (discard != null) p.addProperty("discardUnresolved", discard);
        return p;
    }

    private static void restartRepliesFirstThenRestartsOnce() {
        TransportMixFixture f = new TransportMixFixture();
        int[] restarts = countRestarts(f);
        MixModule mix = new MixModule(f.services);
        ExtensionModule module = new ExtensionModule(f.services, () -> List.<HostModule>of(mix));
        JsonObject result = f.call(module, "extension.restart", params(null));
        check(
                result.get("status").getAsString().equals("dispatched")
                        && result.get("completion").getAsString().equals("not_observed"),
                "dispatch-only result");
        check(result.getAsJsonArray("unresolvedDiscarded").size() == 0, "nothing unresolved listed");
        check(restarts[0] == 0, "restart is deferred until after the reply");
        check(f.tasks.size() == 1, "exactly one restart task scheduled");
        HostOperation late = f.submit(module, "extension.restart", params(null));
        while (!f.tasks.isEmpty()) f.next();
        RpcException stopped = TransportMixFixture.error(late, "unavailable");
        check(stopped.outcome.equals("not_applied"), "instance stops admitting work after replying");
        check(restarts[0] == 1, "host.restart called once; no second restart");
    }

    private static void unresolvedCreationRefusesUnlessDiscarded() {
        TransportMixFixture f = new TransportMixFixture();
        int[] restarts = countRestarts(f);
        MixModule mix = new MixModule(f.services);
        ExtensionModule module = new ExtensionModule(f.services, () -> List.<HostModule>of(mix));
        JsonObject create = new JsonObject();
        create.addProperty("type", "audio");
        create.addProperty("position", -1);
        create.addProperty("name", "X");
        HostOperation a = f.submit(mix, "tracks.create", create);
        f.next();
        a.result.cancel(false);
        f.next();
        check(mix.unresolved().size() == 1, "creation is unresolved");
        HostOperation refused = f.submit(module, "extension.restart", params(null));
        f.next();
        RpcException error = TransportMixFixture.error(refused, "unresolved_mutation");
        check(
                error.outcome.equals("not_applied") && error.getMessage().contains("tracks.create"),
                "refusal is not_applied and names the unresolved mutation");
        check(f.tasks.isEmpty() && restarts[0] == 0, "refusal schedules no restart");
        refused = f.submit(module, "extension.restart", params(false));
        f.next();
        TransportMixFixture.error(refused, "unresolved_mutation");
        JsonObject result = f.call(module, "extension.restart", params(true));
        check(
                result.getAsJsonArray("unresolvedDiscarded").size() == 1
                        && result.getAsJsonArray("unresolvedDiscarded")
                                .get(0)
                                .getAsString()
                                .contains("tracks.create"),
                "discarded descriptions are returned");
        f.next();
        check(restarts[0] == 1, "restart proceeds once discard is explicit");
    }

    private static void invalidParamsNeverSchedule() {
        TransportMixFixture f = new TransportMixFixture();
        ExtensionModule module = new ExtensionModule(f.services, () -> List.<HostModule>of());
        for (String bad : List.of("{\"discardUnresolved\":1}", "{\"discardUnresolved\":null}", "{\"other\":true}")) {
            HostOperation op = f.submit(
                    module, "extension.restart", JsonParser.parseString(bad).getAsJsonObject());
            check(
                    op.result.isCompletedExceptionally() && f.tasks.isEmpty(),
                    "invalid params rejected before scheduling: " + bad);
        }
        check(HostBridge.supportedMethods().contains("extension.restart"), "method is registered with the bridge");
    }

    private static void buildIdentityDefaultsToUnknown() {
        BuildInfo info = BuildInfo.load();
        check(info.version.equals("unknown") && info.build.equals("unknown"), "no embedded resource means unknown");
        check(
                new BuildInfo("1.2.3", "abc1234-dirty")
                        .toJson()
                        .get("build")
                        .getAsString()
                        .equals("abc1234-dirty"),
                "build serialised");
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
}
