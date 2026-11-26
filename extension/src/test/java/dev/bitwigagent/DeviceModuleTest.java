package dev.bitwigagent;

import static dev.bitwigagent.RpcAssert.*;
import static org.junit.jupiter.api.Assertions.*;

import com.bitwig.extension.callback.*;
import com.bitwig.extension.controller.api.*;
import com.google.gson.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

/** Consumer tests against public API proxies; never evidence of native Bitwig behavior. */
public final class DeviceModuleTest {
    private static final String TRACK = "9baa5190-b120-47f9-8e06-0fda476123ca";
    private static final String NATIVE = "11111111-2222-4333-8444-555555555555";

    @Test
    void initialRemoteCallbacksSurviveLateIdentityPopulation() {
        for (String order : List.of("device_first", "track_first", "uuid_last")) {
            Fake f = new Fake(true);
            // Remote page/control callbacks already fired once, while every source identity was a default placeholder.
            if (order.equals("track_first")) {
                f.firstTrack.child("channelId").emit(TRACK);
                f.firstTrack.child("exists").emit(true);
            }
            if (order.equals("uuid_last")) {
                f.firstTrack.child("exists").emit(true);
                f.devices[1].child("exists").emit(true);
            }
            f.devices[1].child("position").emit(1);
            f.devices[1].child("deviceType").emit("audio-effect");
            f.devices[1].child("name").emit("Twin");
            f.devices[1].child("exists").emit(true);
            if (!order.equals("track_first")) {
                f.firstTrack.child("channelId").emit(TRACK);
                f.firstTrack.child("exists").emit(true);
            }
            JsonObject initial = f.call("devices.remote.get", target(1));
            assertTrue(
                    initial.get("complete").getAsBoolean() && remoteValue(initial) == .6,
                    order + ": first meaningful identity must retain early remote callbacks without replay");
            assertTrue(
                    f.call("devices.remote.set", remoteWrite(initial, 1, .8))
                            .get("outcome")
                            .getAsString()
                            .equals("applied"),
                    order + ": initial source is usable without metadata replay");
            f.devices[1].child("exists").emit(false);
            f.devices[1].child("exists").emit(true);
            assertRpcError("not_ready", f.run("devices.remote.get", target(1)));
            f.flush();
            assertTrue(
                    remoteValue(f.call("devices.remote.get", target(1))) == .8,
                    order + ": actual host flush restores continuous getters without callback replay");
            assertTrue(
                    f.controls[1][0].child("value").writes == 1,
                    order + ": retaining initialization callbacks does not weaken later replacement invalidation");
        }
    }

    @Test
    void initialControlCallbacksSurviveLatePresence() {
        Fake established = new Fake(false, true);
        established.controls[1][0].child("exists").emit(true);
        JsonObject initial = established.call("devices.remote.get", target(1));
        assertTrue(
                initial.get("complete").getAsBoolean() && remoteValue(initial) == .6,
                "first control presence retains metadata/value received after initial absent placeholder");
        assertTrue(
                established
                        .call("devices.remote.set", remoteWrite(initial, 1, .8))
                        .get("outcome")
                        .getAsString()
                        .equals("applied"),
                "initial out-of-order control population is writable without callback replay");
        for (boolean controlFirst : List.of(false, true)) {
            Fake f = new Fake(true, true);
            if (controlFirst) f.controls[1][0].child("exists").emit(true);
            f.devices[1].child("position").emit(1);
            f.devices[1].child("deviceType").emit("audio-effect");
            f.devices[1].child("name").emit("Twin");
            f.devices[1].child("exists").emit(true);
            f.firstTrack.child("channelId").emit(TRACK);
            f.firstTrack.child("exists").emit(true);
            if (!controlFirst) f.controls[1][0].child("exists").emit(true);
            initial = f.call("devices.remote.get", target(1));
            assertTrue(
                    initial.get("complete").getAsBoolean() && remoteValue(initial) == .6,
                    "first device and control presence retain early remote observations, controlFirst=" + controlFirst);
            assertTrue(
                    f.call("devices.remote.set", remoteWrite(initial, 1, .8))
                            .get("outcome")
                            .getAsString()
                            .equals("applied"),
                    "late initial device/control presence permits setter, controlFirst=" + controlFirst);
        }
    }

    @Test
    void remoteControlRebindUsesFlushWithoutAcknowledgingWrites() {
        for (boolean absentCallbacks : List.of(false, true)) {
            Fake f = new Fake();
            FakeHost control = f.controls[1][0];
            FakeHost other = f.controls[1][1];
            other.child("exists").emit(true);
            other.child("name").emit("Other");
            other.child("value").emit(.4);
            f.flush();
            JsonObject old = f.call("devices.remote.get", target(1));
            JsonObject otherSource = f.call("devices.remote.get", target(0));
            control.child("exists").emit(false);
            f.flush();
            JsonObject absent = f.call("devices.remote.get", target(1));
            JsonObject item = absent.getAsJsonArray("controls").get(0).getAsJsonObject();
            assertTrue(
                    !item.get("exists").getAsBoolean()
                            && item.get("name").isJsonNull()
                            && item.get("isBeingMapped").isJsonNull()
                            && item.get("normalizedValue").isJsonNull(),
                    "flush must not expose absent control history");
            if (absentCallbacks) {
                control.child("name").emit("Gain");
                control.child("isBeingMapped").emit(false);
                control.child("value").emit(.91);
                f.flush();
                item = f.call("devices.remote.get", target(1))
                        .getAsJsonArray("controls")
                        .get(0)
                        .getAsJsonObject();
                assertTrue(
                        item.get("name").isJsonNull()
                                && item.get("isBeingMapped").isJsonNull()
                                && item.get("normalizedValue").isJsonNull(),
                        "callbacks and flush during absence cannot seed readable or fresh values");
            }
            assertRpcError("stale_context", f.run("devices.remote.set", remoteWrite(old, 1, .6)));
            control.child("exists").emit(true);
            JsonObject rebound = f.call("devices.remote.get", target(1));
            item = rebound.getAsJsonArray("controls").get(0).getAsJsonObject();
            assertTrue(
                    item.get("exists").getAsBoolean()
                            && item.get("normalizedValue").isJsonNull()
                            && item.get("name").isJsonNull()
                            && !rebound.get("complete").getAsBoolean(),
                    "reappearance waits for the actual host flush, not an RPC or timer");
            assertRpcError("stale_context", f.run("devices.remote.set", remoteWrite(rebound, 1, .6)));
            JsonObject unaffected = rebound.getAsJsonArray("controls").get(1).getAsJsonObject();
            assertTrue(
                    unaffected.get("normalizedValue").getAsDouble() == .4
                            && unaffected.get("name").getAsString().equals("Other"),
                    "another control remains readable during the target's rebind");
            JsonObject otherWrite = remoteWrite(rebound, 1, .5);
            otherWrite.addProperty("parameterIndex", 1);
            otherWrite.addProperty("expectedParameterName", "Other");
            assertTrue(
                    f.call("devices.remote.set", otherWrite)
                            .get("outcome")
                            .getAsString()
                            .equals("applied"),
                    "unaffected control remains writable without callback replay");
            assertTrue(
                    f.call("devices.remote.set", remoteWrite(otherSource, 0, .3))
                            .get("outcome")
                            .getAsString()
                            .equals("applied"),
                    "another source retains its token and readiness");
            control.child("value").data = .35;
            f.flush();
            JsonObject current = f.call("devices.remote.get", target(1));
            assertTrue(
                    current.get("complete").getAsBoolean() && remoteValue(current) == .35,
                    "flush reads the current getter, not the predecessor or absent callback value");
            assertRpcError("stale_context", f.run("devices.remote.set", remoteWrite(old, 1, .35)));
            assertRpcError("stale_context", f.run("devices.remote.set", remoteWrite(absent, 1, .35)));
            control.child("value").notifyWrites = false;
            CompletableFuture<JsonObject> pending = f.run("devices.remote.set", remoteWrite(current, 1, .35));
            assertTrue(
                    !pending.isDone() && control.child("value").writes == 1,
                    "same-value request after rebind dispatches rather than claiming a verified no-op");
            f.flush();
            f.next();
            assertTrue(!pending.isDone(), "repeated flush cannot acknowledge unchanged getter write");
            control.child("value").emit(.35);
            f.next();
            assertTrue(
                    pending.join().get("outcome").getAsString().equals("applied"),
                    "only fresh same-source callback acknowledges the explicit write");
            control.child("exists").emit(true);
            JsonObject fresh = f.call("devices.remote.get", target(1));
            assertTrue(
                    f.call("devices.remote.set", remoteWrite(fresh, 1, .35))
                                    .get("outcome")
                                    .getAsString()
                                    .equals("already_matched")
                            && control.child("value").writes == 1,
                    "duplicate presence preserves a genuinely fresh value for subsequent no-op");
        }
    }

    @Test
    void catalogListsFactoryDevicesWithRuntimeTexts() {
        try {
            java.nio.file.Path root = java.nio.file.Files.createTempDirectory("bwcatalog");
            java.nio.file.Path devices = java.nio.file.Files.createDirectories(
                    root.resolve("Library").resolve("devices"));
            java.nio.file.Path text = java.nio.file.Files.createDirectories(
                    root.resolve("resources").resolve("localization"));
            for (String name : List.of("Poly Grid.bwdevice", "v9 Hat Open.bwdevice", "Mystery.bwdevice", "ignored.txt"))
                java.nio.file.Files.write(devices.resolve(name), new byte[] {1});
            java.nio.file.Files.write(
                    text.resolve("Device-descriptions-resources.properties"),
                    ("\uFEFF#the_grid.\n"
                                    + "device.poly_grid.desc=Modular poly\\nsynth\ndevice.poly_grid.keywords=x\n#drum.\ndevice.v9_open_hat.desc=Open hat\n")
                            .getBytes("UTF-8"));
            java.nio.file.Files.write(
                    text.resolve("Categories-resources.properties"),
                    "The\\ Grid=The Grid\nDrum=Drum\n".getBytes("UTF-8"));
            JsonObject result = DeviceModule.catalog(root.toString());
            JsonArray list = result.getAsJsonArray("devices");
            assertTrue(result.get("supported").getAsBoolean() && list.size() == 3, "only .bwdevice files are listed");
            JsonObject poly = null, hat = null, mystery = null;
            for (JsonElement e : list) {
                JsonObject item = e.getAsJsonObject();
                String name = item.get("name").getAsString();
                if (name.equals("Poly Grid")) poly = item;
                else if (name.equals("v9 Hat Open")) hat = item;
                else mystery = item;
                assertTrue(
                        item.get("kind").getAsString().equals("file")
                                && item.get("path").getAsString().endsWith(name + ".bwdevice"),
                        "kind=file path usable by devices.insert");
            }
            assertTrue(
                    poly.get("category").getAsString().equals("The Grid")
                            && poly.get("description").getAsString().equals("Modular poly\nsynth"),
                    "category and description come from the resources");
            assertTrue(
                    hat.get("category").getAsString().equals("Drum")
                            && hat.get("description").getAsString().equals("Open hat"),
                    "word order does not matter");
            assertTrue(
                    !mystery.has("category") && !mystery.has("description"), "unknown device keeps name and path only");
            assertTrue(!result.get("bitwigIdAvailable").getAsBoolean(), "no native UUIDs claimed");
            JsonObject none = DeviceModule.catalog(null);
            assertTrue(
                    !none.get("supported").getAsBoolean()
                            && none.getAsJsonArray("devices").size() == 0,
                    "no root, no guesses");
            JsonObject missing = DeviceModule.catalog(root.resolve("nowhere").toString());
            assertTrue(
                    missing.get("supported").getAsBoolean()
                            && missing.getAsJsonArray("devices").size() == 0,
                    "missing directory lists nothing");
        } catch (java.io.IOException error) {
            throw new AssertionError(error);
        }
    }

    @Test
    void strictShapesRejectBeforeScheduling() {
        Fake f = new Fake();
        List<JsonObject> badTargets = new ArrayList<>();
        JsonObject p = target(0);
        p.addProperty("deviceIndex", 16);
        badTargets.add(p);
        p = target(0);
        p.addProperty("deviceIndex", .5);
        badTargets.add(p);
        p = target(0);
        p.addProperty("expectedDeviceName", "");
        badTargets.add(p);
        p = target(0);
        p.add("trackId", JsonNull.INSTANCE);
        badTargets.add(p);
        p = target(0);
        p.addProperty("extra", true);
        badTargets.add(p);
        for (JsonObject bad : badTargets) assertRpcError("invalid_params", f.request("devices.get", bad));
        p = target(0);
        p.addProperty("enabled", "false");
        assertRpcError("invalid_params", f.request("devices.set", p));
        p = insert("bitwig", "1-1-1-1-1");
        assertRpcError("invalid_params", f.request("devices.insert", p));
        p = insert("bitwig", NATIVE);
        p.addProperty("expectedDeviceCount", 16);
        assertRpcError("invalid_params", f.request("devices.insert", p));
        p = insert("vst2", "2147483648");
        assertRpcError("invalid_params", f.request("devices.insert", p));
        p = insert("vst2", "1.5");
        assertRpcError("invalid_params", f.request("devices.insert", p));
        p = insert("vst3", "1234");
        assertRpcError("invalid_params", f.request("devices.insert", p));
        p = insert("clap", "plugin id");
        assertRpcError("invalid_params", f.request("devices.insert", p));
        p = insert("shell", "echo hi");
        assertRpcError("invalid_params", f.request("devices.insert", p));
        for (String path : List.of("relative.bwpreset", "/tmp/../bad.bwpreset", "/tmp/bad\nfile.bwpreset")) {
            p = file(path);
            assertRpcError("invalid_params", f.request("devices.insert", p));
        }
        p = file(TestPaths.absolute("/tmp/device.bwpreset"));
        p.addProperty("id", NATIVE);
        assertRpcError("invalid_params", f.request("devices.insert", p));
        p = new JsonObject();
        p.addProperty("extra", true);
        assertRpcError("invalid_params", f.request("devices.catalog", p));
        assertTrue(f.tasks.isEmpty() && f.insertions == 0, "invalid inputs cannot schedule or mutate");
    }

    @Test
    void sameNameTargetsStayExplicit() {
        Fake f = new Fake();
        JsonObject first = f.call("devices.get", target(0));
        JsonObject second = f.call("devices.get", target(1));
        assertTrue(
                first.get("name").getAsString().equals(second.get("name").getAsString()),
                "fixture has same-name devices");
        JsonObject p = target(1);
        p.addProperty("enabled", false);
        JsonObject result = f.call("devices.set", p);
        assertTrue(!result.get("enabled").getAsBoolean(), "bypass readback is disabled");
        assertTrue(
                f.devices[0].child("isEnabled").writes == 0 && f.devices[1].child("isEnabled").writes == 1,
                "same-name device at index one is the only write target");
        result = f.call("devices.select", target(1));
        assertTrue(f.selected == 1, "selection targets explicit fixed device, not first matching name");
        assertTrue(result.get("outcome").getAsString().equals("sent"), "selection never fabricates completion");
        p = target(1);
        p.addProperty("expectedDeviceName", "Wrong");
        p.addProperty("enabled", true);
        assertRpcError("stale_target", f.run("devices.set", p));
        assertTrue(f.devices[1].child("isEnabled").writes == 1, "name mismatch fails before dispatch");
        {
            RpcException e = rejection(f.run("devices.set", p));
            assertTrue(
                    e.getMessage().contains("is named \"") && e.getMessage().contains("not expectedDeviceName"),
                    "stale_target names the observed device");
        }
        p = target(2);
        assertRpcError("target_not_found", f.run("devices.get", p));
        assertTrue(!first.get("nameWritable").getAsBoolean(), "public device rename limitation is explicit");
    }

    @Test
    void bypassNeedsFreshSameSourceObservation() {
        Fake f = new Fake();
        f.devices[1].child("isEnabled").notifyWrites = false;
        JsonObject p = target(1);
        p.addProperty("enabled", false);
        CompletableFuture<JsonObject> future = f.run("devices.set", p);
        assertTrue(!future.isDone(), "cached matching value without callback cannot verify bypass");
        f.devices[0].child("isEnabled").emit(false);
        f.next();
        assertTrue(!future.isDone(), "other source cannot verify target bypass");
        f.devices[1].child("isEnabled").emit(false);
        f.next();
        assertTrue(
                future.join().get("outcome").getAsString().equals("applied"),
                "fresh same-source callback verifies bypass");
        int writes = f.devices[1].child("isEnabled").writes;
        assertTrue(
                f.call("devices.set", p).get("outcome").getAsString().equals("already_matched"),
                "idempotent state avoids duplicate mutation");
        assertTrue(f.devices[1].child("isEnabled").writes == writes, "already observed bypass does not write again");
    }

    @Test
    void targetChangeAndDeadlinesNeverReplay() {
        Fake f = new Fake();
        JsonObject p = target(0);
        p.addProperty("enabled", false);
        assertRpcError("deadline", f.run("devices.set", p, f.tasks.now() - 1));
        assertTrue(f.devices[0].child("isEnabled").writes == 0, "expired request cannot bypass device");
        CompletableFuture<JsonObject> canceled = f.request("devices.set", p);
        canceled.cancel(false);
        f.next();
        assertTrue(f.devices[0].child("isEnabled").writes == 0, "canceled queued request cannot bypass device");
        f.devices[0].child("isEnabled").notifyWrites = false;
        CompletableFuture<JsonObject> pending = f.run("devices.set", p);
        f.devices[0].child("name").emit("Replacement");
        f.next();
        assertTrue(
                pending.join().get("outcome").getAsString().equals("unknown"),
                "changed target after dispatch is unknown");
        assertTrue(f.devices[0].child("isEnabled").writes == 1, "target change cannot replay write");
        Fake shortWait = new Fake();
        shortWait.devices[0].child("isEnabled").notifyWrites = false;
        JsonObject result =
                shortWait.run("devices.set", p, shortWait.tasks.after(20)).join();
        assertTrue(
                result.get("outcome").getAsString().equals("unknown"),
                "bounded observation timeout does not invent completion");
        assertTrue(shortWait.devices[0].child("isEnabled").writes == 1, "timeout never retries mutation");
    }

    @Test
    void insertionGuardsAndObservedDelta() {
        Fake f = new Fake();
        JsonObject wrong = insert("bitwig", NATIVE);
        wrong.addProperty("expectedDeviceCount", 1);
        assertRpcError("stale_context", f.run("devices.insert", wrong));
        assertTrue(f.insertions == 0, "count mismatch fails closed before insertion");
        JsonObject result = f.call("devices.insert", insert("bitwig", NATIVE));
        assertTrue(
                f.insertions == 1 && f.lastArgument.equals(UUID.fromString(NATIVE)),
                "native ID reaches exact public insertion API once");
        assertTrue(
                result.get("beforeCount").getAsInt() == 2
                        && result.get("afterCount").getAsInt() == 3,
                "insertion reports count delta");
        assertTrue(
                result.getAsJsonObject("inserted").get("deviceIndex").getAsInt() == 2,
                "append identifies new final slot, not same-name predecessor");
        assertTrue(
                result.getAsJsonArray("before").size() == 2
                        && result.getAsJsonArray("after").size() == 3,
                "insertion preserves before/after evidence");
        assertTrue(
                !result.get("requestedIdentifierVerified").getAsBoolean(),
                "name/type readback is not proof of requested plugin UUID");
        assertRpcError("stale_context", f.run("devices.insert", insert("bitwig", NATIVE)));
        assertTrue(f.insertions == 1, "reusing old expected count cannot duplicate insert");
        Fake delayed = new Fake();
        delayed.insertMode = "countOnly";
        CompletableFuture<JsonObject> pending = delayed.run("devices.insert", insert("bitwig", NATIVE));
        assertTrue(
                !pending.isDone(),
                "count and existence alone cannot relabel stale empty-slot metadata as the inserted device");
        delayed.devices[2].child("name").emit("Fresh inserted name");
        delayed.next();
        assertTrue(
                pending.join()
                        .getAsJsonObject("inserted")
                        .get("name")
                        .getAsString()
                        .equals("Fresh inserted name"),
                "insertion waits for fresh metadata from the appended slot");
    }

    @Test
    void insertionKindsPassExactArguments() {
        String[][] cases = {
            {"bitwig", NATIVE, "insertBitwigDevice"},
            {"vst2", "-123456", "insertVST2Device"},
            {"vst3", "00112233445566778899AABBCCDDEEFF", "insertVST3Device"},
            {"clap", "org.example.effect", "insertCLAPDevice"}
        };
        for (String[] item : cases) {
            Fake f = new Fake();
            f.call("devices.insert", insert(item[0], item[1]));
            assertTrue(f.lastInsertion.equals(item[2]), "correct public insertion method for " + item[0]);
            assertTrue(f.lastArgument.toString().equals(item[1]), "identifier passed without guessing or expansion");
        }
        Fake f = new Fake();
        String literal = TestPaths.absolute("/tmp/Device $(not-a-command).bwpreset");
        f.call("devices.insert", file(literal));
        assertTrue(
                f.lastInsertion.equals("insertFile") && f.lastArgument.equals(literal),
                "file path is literal host API input, never a shell command");
    }

    @Test
    void insertionAmbiguityAndNoopStayUncertain() {
        for (String mode : List.of("double", "prefix")) {
            Fake f = new Fake();
            f.insertMode = mode;
            JsonObject result = f.call("devices.insert", insert("bitwig", NATIVE));
            assertTrue(
                    result.get("reason").getAsString().equals("readback_mismatch"),
                    "concurrent chain edits are ambiguous: " + mode);
            assertTrue(
                    result.get("inserted").isJsonNull(),
                    "ambiguous result never labels an arbitrary device as inserted");
            assertTrue(f.insertions == 1, "ambiguous insertion is not retried");
        }
        Fake f = new Fake();
        f.insertMode = "none";
        JsonObject result = f.run("devices.insert", insert("bitwig", NATIVE), f.tasks.after(20))
                .join();
        assertTrue(
                result.get("reason").getAsString().equals("not_observed"),
                "public unknown-ID no-op is honestly reported");
        assertTrue(result.get("inserted").isJsonNull() && f.insertions == 1, "no-op never invents ID or retries");
        Fake shifted = new Fake();
        shifted.bank.child("scrollPosition").emit(1);
        assertRpcError("stale_context", shifted.run("devices.insert", insert("bitwig", NATIVE)));
        assertTrue(shifted.insertions == 0, "shifted window cannot silently append without observing prefix");
    }

    @Test
    void positionedInsertionShiftsTheSuffix() {
        Fake f = chain("A", "B", "C");
        JsonObject before = insert("bitwig", NATIVE);
        before.addProperty("expectedDeviceCount", 3);
        before.addProperty("position", "before");
        before.addProperty("deviceIndex", 1);
        before.addProperty("expectedDeviceName", "B");
        JsonObject result = f.call("devices.insert", before);
        assertTrue(
                f.points.equals(List.of("before:1:insertBitwigDevice")),
                "before anchors on the named device only: " + f.points);
        assertTrue(names(f).equals(List.of("A", "Inserted", "B", "C")), "chain after before-insert: " + names(f));
        assertTrue(
                result.getAsJsonObject("inserted").get("deviceIndex").getAsInt() == 1
                        && result.get("verification").getAsString().equals("observed_count_increment_shifted_suffix")
                        && result.getAsJsonObject("position").get("index").getAsInt() == 1,
                "mid-chain insertion is identified at its final slot");
        assertTrue(
                result.getAsJsonArray("after").size() == 4
                        && result.get("outcome").getAsString().equals("applied"),
                "mid-chain insertion reports the whole shifted chain");

        for (String[] c : new String[][] {{"start", "start:-1", "0"}, {"after", "after:2", "3"}}) {
            Fake g = chain("A", "B", "C");
            JsonObject p = insert("clap", "org.example.fx");
            p.addProperty("expectedDeviceCount", 3);
            p.addProperty("position", c[0]);
            if (c[0].equals("after")) {
                p.addProperty("deviceIndex", 2);
                p.addProperty("expectedDeviceName", "C");
            }
            JsonObject r = g.call("devices.insert", p);
            assertTrue(
                    g.points.size() == 1 && g.points.get(0).startsWith(c[1] + ":insertCLAPDevice"),
                    c[0] + " uses its public insertion point once: " + g.points);
            assertTrue(
                    r.getAsJsonObject("inserted").get("deviceIndex").getAsInt() == Integer.parseInt(c[2]),
                    c[0] + " final slot");
        }
        Fake end = chain("A", "B");
        JsonObject explicitEnd = insert("bitwig", NATIVE);
        explicitEnd.addProperty("position", "end");
        assertTrue(
                end.call("devices.insert", explicitEnd)
                                .get("verification")
                                .getAsString()
                                .equals("observed_count_increment_and_unchanged_prefix")
                        && end.insertions == 1,
                "explicit end is the existing append path");

        Fake guards = chain("A", "B", "C");
        JsonObject wrong = insert("bitwig", NATIVE);
        wrong.addProperty("expectedDeviceCount", 3);
        wrong.addProperty("position", "before");
        wrong.addProperty("deviceIndex", 1);
        wrong.addProperty("expectedDeviceName", "A");
        assertRpcError("stale_target", guards.run("devices.insert", wrong));
        wrong.addProperty("deviceIndex", 3);
        assertRpcError("target_not_found", guards.run("devices.insert", wrong));
        assertTrue(guards.points.isEmpty(), "a wrong anchor never reaches the host");
        for (String[] bad : new String[][] {{"before", ""}, {"end", "deviceIndex"}, {"sideways", ""}}) {
            JsonObject p = insert("bitwig", NATIVE);
            p.addProperty("position", bad[0]);
            if (bad[1].equals("deviceIndex")) {
                p.addProperty("deviceIndex", 0);
                p.addProperty("expectedDeviceName", "A");
            }
            Fake v = chain("A", "B");
            assertRpcError("invalid_params", v.request("devices.insert", p));
        }
        Fake none = chain("A", "B");
        none.insertMode = "none";
        JsonObject p = insert("bitwig", NATIVE);
        p.addProperty("position", "start");
        JsonObject missed = none.run("devices.insert", p, none.tasks.after(20)).join();
        assertTrue(
                missed.get("reason").getAsString().equals("not_observed")
                        && missed.get("inserted").isJsonNull()
                        && none.points.size() == 1,
                "an unobserved positioned insert is not retried");
    }

    @Test
    void deletionNeedsExactNameAndObservesShift() {
        Fake f = chain("A", "B", "C");
        JsonObject p = named(1, "B");
        JsonObject result = f.call("devices.delete", p);
        assertTrue(
                f.deletions == 1 && names(f).equals(List.of("A", "C")),
                "delete removes exactly the named device: " + names(f));
        assertTrue(
                result.get("outcome").getAsString().equals("applied")
                        && result.get("verification")
                                .getAsString()
                                .equals("observed_count_decrement_and_shifted_suffix")
                        && result.get("beforeCount").getAsInt() == 3
                        && result.get("afterCount").getAsInt() == 2
                        && result.getAsJsonObject("deleted")
                                .get("name")
                                .getAsString()
                                .equals("B")
                        && result.getAsJsonArray("after").size() == 2,
                "delete reports the count delta and shifted chain");
        assertRpcError("stale_target", f.run("devices.delete", named(1, "B")));
        assertRpcError("target_not_found", f.run("devices.delete", named(5, "B")));
        assertTrue(f.deletions == 1, "a stale name or absent index never deletes");

        Fake last = chain("A", "B");
        assertTrue(
                last.call("devices.delete", named(1, "B"))
                                .get("outcome")
                                .getAsString()
                                .equals("applied")
                        && names(last).equals(List.of("A")),
                "deleting the last device observes the empty tail");

        Fake wide = chain("A", "B");
        wide.bank.child("itemCount").emit(17);
        assertRpcError("outside_window", wide.run("devices.delete", named(0, "A")));
        assertTrue(wide.deletions == 0, "a chain beyond the 16-device window is never edited blind");

        Fake unsafe = chain("A", "B");
        unsafe.transport.child("isArrangerRecordEnabled").emit(true);
        assertRpcError("unsafe_state", unsafe.run("devices.delete", named(0, "A")));
        assertTrue(unsafe.deletions == 0, "recording blocks deletion");

        Fake silent = chain("A", "B");
        silent.deleteMode = "none";
        JsonObject r = silent.run("devices.delete", named(0, "A"), silent.tasks.after(20))
                .join();
        assertTrue(
                r.get("outcome").getAsString().equals("unknown")
                        && r.get("reason").getAsString().equals("not_observed")
                        && silent.deletions == 1,
                "an unobserved deletion is honest and never retried");
        Fake changed = chain("A", "B", "C");
        changed.deleteMode = "prefix";
        assertTrue(
                changed.call("devices.delete", named(2, "C"))
                        .get("reason")
                        .getAsString()
                        .equals("readback_mismatch"),
                "a changed prefix during deletion is ambiguous");
    }

    @Test
    void moveObservesTheExpectedArrangement() {
        Fake f = chain("A", "B", "C", "D");
        JsonObject up = named(0, "A");
        up.addProperty("toIndex", 2);
        JsonObject result = f.call("devices.move", up);
        assertTrue(
                f.points.equals(List.of("after:2:moveDevices")) && names(f).equals(List.of("B", "C", "A", "D")),
                "moving to a later index lands after that device: " + f.points + names(f));
        assertTrue(
                result.get("verification").getAsString().equals("observed_expected_arrangement")
                        && result.getAsJsonObject("moved").get("toIndex").getAsInt() == 2
                        && result.get("beforeCount").getAsInt() == 4
                        && result.get("afterCount").getAsInt() == 4,
                "move observed");
        JsonObject down = named(3, "D");
        down.addProperty("toIndex", 1);
        f.call("devices.move", down);
        assertTrue(
                f.points.get(1).equals("before:1:moveDevices") && names(f).equals(List.of("B", "D", "C", "A")),
                "moving to an earlier index lands before that device: " + names(f));
        JsonObject beyond = named(0, "B");
        beyond.addProperty("toIndex", 4);
        assertRpcError("target_not_found", f.run("devices.move", beyond));
        JsonObject same = named(0, "B");
        same.addProperty("toIndex", 0);
        assertRpcError("invalid_params", f.request("devices.move", same));
        JsonObject wrongName = named(0, "Z");
        wrongName.addProperty("toIndex", 1);
        assertRpcError("stale_target", f.run("devices.move", wrongName));
        assertTrue(f.points.size() == 2, "rejected moves never reach the host");

        Fake twins = new Fake();
        JsonObject swap = target(0);
        swap.addProperty("toIndex", 1);
        JsonObject r = twins.call("devices.move", swap);
        assertTrue(
                r.get("outcome").getAsString().equals("unknown")
                        && r.get("reason").getAsString().equals("readback_mismatch")
                        && twins.points.size() == 1,
                "identically named neighbours cannot prove a move");

        Fake silent = chain("A", "B", "C");
        silent.moveMode = "none";
        JsonObject p = named(0, "A");
        p.addProperty("toIndex", 1);
        JsonObject missed =
                silent.run("devices.move", p, silent.tasks.after(20)).join();
        assertTrue(
                missed.get("reason").getAsString().equals("not_observed") && silent.points.size() == 1,
                "an unobserved move is reported and not retried");
    }

    private static Fake chain(String... names) {
        Fake f = new Fake();
        List<String[]> next = new ArrayList<>();
        for (String name : names) next.add(new String[] {name, "audio-effect"});
        f.render(next);
        return f;
    }

    private static List<String> names(Fake f) {
        List<String> result = new ArrayList<>();
        for (String[] entry : f.chain) result.add(entry[0]);
        return result;
    }

    private static JsonObject named(int index, String name) {
        JsonObject p = target(index);
        p.addProperty("expectedDeviceName", name);
        return p;
    }

    @Test
    void remotesAreSourceBoundAndTokenGuarded() {
        Fake f = new Fake();
        JsonObject first = f.call("devices.remote.get", target(0));
        JsonObject second = f.call("devices.remote.get", target(1));
        assertTrue(
                remoteValue(first) == .25 && remoteValue(second) == .6,
                "same-name device remote pages keep separate sources");
        assertTrue(!second.get("followsUiPageSelection").getAsBoolean(), "independent page is not UI-selected cursor");
        JsonObject result = f.call("devices.remote.set", remoteWrite(second, 1, .8));
        assertTrue(
                remoteValue(result) == .8
                        && f.controls[0][0].child("value").writes == 0
                        && f.controls[1][0].child("value").writes == 1,
                "remote mutation writes only explicit second source");
        f.controls[1][0].child("name").emit("Remapped");
        assertRpcError("stale_context", f.run("devices.remote.set", remoteWrite(second, 1, .3)));
        assertTrue(
                f.controls[1][0].child("value").writes == 1,
                "observed mapping metadata change invalidates context token");
        JsonObject fresh = f.call("devices.remote.get", target(1));
        f.controls[1][0].child("isBeingMapped").emit(true);
        JsonObject mapped = f.call("devices.remote.get", target(1));
        JsonObject p = remoteWrite(mapped, 1, .4);
        p.addProperty("expectedParameterName", "Remapped");
        assertRpcError("stale_context", f.run("devices.remote.set", p));
        assertTrue(fresh.has("identityCaveat"), "unobservable same-name remap caveat is returned to consumers");
    }

    @Test
    void remotePageSelectionIsIndependentAndObserved() {
        Fake f = new Fake();
        JsonObject listing = f.call("devices.remote.get", target(1));
        JsonObject p = target(1);
        p.add("contextToken", listing.get("contextToken"));
        p.addProperty("pageIndex", 1);
        p.addProperty("expectedPageName", "Tone");
        JsonObject result = f.call("devices.remote.page", p);
        assertTrue(
                result.get("pageName").getAsString().equals("Tone")
                        && result.get("pageIndex").getAsInt() == 1,
                "requested page index and name are observed");
        assertTrue(result.get("outcome").getAsString().equals("applied"), "page requires fresh index callback");
        assertTrue(
                !result.get("complete").getAsBoolean()
                        && result.getAsJsonArray("controls")
                                .get(0)
                                .getAsJsonObject()
                                .get("normalizedValue")
                                .isJsonNull(),
                "new page cannot relabel a cached value from the previous page as current");
        assertRpcError("stale_context", f.run("devices.remote.set", remoteWrite(result, 1, .7)));
        f.controls[1][0].child("value").emit(.35);
        f.flush();
        JsonObject fresh = f.call("devices.remote.get", target(1));
        assertTrue(remoteValue(fresh) == .35, "new page value becomes available after its own source callback");
        assertTrue(
                f.pages[0].child("selectedPageIndex").writes == 0 && f.pages[1].child("selectedPageIndex").writes == 1,
                "page selection affects only addressed proxy");
        assertTrue(f.selected == -1, "remote page does not select device in editor");
        assertRpcError("stale_context", f.run("devices.remote.page", p));
        p.add("contextToken", result.get("contextToken"));
        p.addProperty("expectedPageName", "Wrong");
        assertRpcError("stale_context", f.run("devices.remote.page", p));
    }

    @Test
    void remoteAbsoluteWritesBypassPickupWithoutBypassingGuards() {
        // SettableRangedValue documents that set(double) may defer under takeover,
        // while setImmediately(double) applies the absolute value regardless of that mode.
        FakeHost pickup = new FakeHost("pickup");
        pickup.data = .6;
        pickup.takeoverPending = true;
        SettableRangedValue ranged = (SettableRangedValue) pickup.proxy(SettableRangedValue.class);
        ranged.set(.8);
        assertTrue(ranged.get() == .6, "pickup defers ordinary set rather than changing the host value");
        ranged.setImmediately(.8);
        assertTrue(
                ranged.get() == .8 && pickup.takeoverPending,
                "absolute setter changes the value without changing configured takeover");

        Fake f = new Fake();
        FakeHost other = f.controls[1][1];
        other.child("exists").emit(true);
        other.child("name").emit("Other");
        other.child("isBeingMapped").emit(false);
        other.child("value").emit(.4);
        f.flush();
        JsonObject original = f.call("devices.remote.get", target(1));
        double originalValue = remoteValue(original);
        JsonObject changed = f.call("devices.remote.set", remoteWrite(original, 1, .8));
        assertTrue(
                changed.get("outcome").getAsString().equals("applied")
                        && remoteValue(changed) == .8
                        && remoteValue(f.call("devices.remote.get", target(1))) == .8,
                "absolute RPC reaches the observed target under pending pickup");
        assertTrue(
                remoteValue(f.call("devices.remote.get", target(0))) == .25
                        && f.controls[0][0].child("value").writes == 0
                        && other.child("value").writes == 0
                        && changed.getAsJsonArray("controls")
                                        .get(1)
                                        .getAsJsonObject()
                                        .get("normalizedValue")
                                        .getAsDouble()
                                == .4,
                "absolute write preserves the other source and neighboring control");
        JsonObject restored = f.call("devices.remote.set", remoteWrite(changed, 1, originalValue));
        assertTrue(
                remoteValue(restored) == originalValue
                        && restored.get("outcome").getAsString().equals("applied")
                        && f.controls[1][0].child("value").takeoverPending,
                "fresh source-bound absolute write restores the original value without altering takeover");
        assertTrue(
                f.call("devices.remote.set", remoteWrite(restored, 1, originalValue))
                                .get("outcome")
                                .getAsString()
                                .equals("already_matched")
                        && f.controls[1][0].child("value").writes == 2,
                "observed no-op under pickup does not dispatch another write");

        for (String state : List.of(
                "stale",
                "absent",
                "isArrangerRecordEnabled",
                "isArrangerAutomationWriteEnabled",
                "isClipLauncherAutomationWriteEnabled")) {
            Fake guarded = new Fake();
            JsonObject listing = guarded.call("devices.remote.get", target(1));
            if (state.equals("stale")) guarded.controls[1][0].child("name").emit("Remapped");
            else if (state.equals("absent"))
                guarded.controls[1][0].child("exists").emit(false);
            else guarded.transport.child(state).emit(true);
            assertRpcError(
                    state.equals("stale") || state.equals("absent") ? "stale_context" : "unsafe_state",
                    guarded.run("devices.remote.set", remoteWrite(listing, 1, .8)));
            assertTrue(
                    guarded.controls[1][0].child("value").writes == 0
                            && (Double) guarded.controls[1][0].child("value").data == .6,
                    state + ": absolute write remains blocked with no host value change");
        }
    }

    @Test
    void remoteWritesNeedFreshUnchangedSource() {
        Fake f = new Fake();
        JsonObject listing = f.call("devices.remote.get", target(1));
        f.controls[1][0].child("value").notifyWrites = false;
        CompletableFuture<JsonObject> pending = f.run("devices.remote.set", remoteWrite(listing, 1, .9));
        f.controls[0][0].child("value").emit(.9);
        f.next();
        assertTrue(!pending.isDone(), "remote readback from another source cannot complete write");
        f.controls[1][0].child("value").emit(.9);
        f.next();
        assertTrue(
                pending.join().get("outcome").getAsString().equals("applied"),
                "same-source fresh remote observation verifies write");
        JsonObject latest = f.call("devices.remote.get", target(1));
        pending = f.run("devices.remote.set", remoteWrite(latest, 1, .7));
        f.pages[1].child("getName").emit("Changed");
        f.next();
        assertTrue(
                pending.join().get("outcome").getAsString().equals("unknown"),
                "page change during remote write prevents misleading verification");
        assertTrue(f.controls[1][0].child("value").writes == 2, "remote writes never replay after uncertain result");
    }

    @Test
    void observedSourceReplacementReconcilesAtFlush() {
        for (String transition :
                List.of("device_exists", "device_position", "track_uuid", "track_exists", "device_type")) {
            Fake f = new Fake();
            JsonObject old = f.call("devices.remote.get", target(1));
            JsonObject currentTarget = target(1);
            switch (transition) {
                case "device_exists":
                    f.devices[1].child("exists").emit(false);
                    f.controls[1][0].child("value").emit(.91);
                    f.devices[1].child("exists").emit(true);
                    break;
                case "device_position":
                    f.devices[1].child("position").emit(2);
                    f.flush();
                    assertRpcError("target_not_found", f.run("devices.remote.get", currentTarget));
                    f.devices[1].child("position").emit(1);
                    break;
                case "track_uuid":
                    String replacement = "7a71b30b-abf3-4e23-9a7e-8a346e301a2f";
                    f.firstTrack.child("channelId").emit(replacement);
                    currentTarget.addProperty("trackId", replacement);
                    break;
                case "track_exists":
                    f.firstTrack.child("exists").emit(false);
                    f.controls[1][0].child("value").emit(.91);
                    f.flush();
                    f.firstTrack.child("exists").emit(true);
                    break;
                case "device_type":
                    f.devices[1].child("deviceType").emit("instrument");
                    break;
                default:
                    throw new AssertionError(transition);
            }
            assertRpcError("not_ready", f.run("devices.remote.get", currentTarget));
            JsonObject staleWrite = remoteWrite(old, 1, .6);
            staleWrite.add("trackId", currentTarget.get("trackId"));
            assertRpcError("not_ready", f.run("devices.remote.set", staleWrite));
            f.pages[1].child("pageNames").data = new String[] {"Main", "Tone", "Third"};
            f.pages[1].child("pageCount").data = 3;
            f.controls[1][0].child("value").data = .35;
            assertTrue(
                    f.flushRequests > 0 && f.tasks.isEmpty(),
                    transition + ": rebind requests a host flush, not a recovery timer");
            f.flush();
            JsonObject fresh = f.call("devices.remote.get", currentTarget);
            assertTrue(
                    fresh.get("complete").getAsBoolean()
                            && remoteValue(fresh) == .35
                            && fresh.get("pageCount").getAsInt() == 3
                            && fresh.getAsJsonArray("pages").size() == 3,
                    transition
                            + ": flush restores current previously observed getters without any metadata/value callback replay");
            assertRpcError("stale_context", f.run("devices.remote.set", staleWrite));
            JsonObject write = remoteWrite(fresh, 1, .35);
            write.add("trackId", currentTarget.get("trackId"));
            f.controls[1][0].child("value").notifyWrites = false;
            CompletableFuture<JsonObject> pending = f.run("devices.remote.set", write);
            assertTrue(
                    !pending.isDone() && f.controls[1][0].child("value").writes == 1,
                    transition + ": reconciled value is readable but not a verified same-value no-op");
            f.flush();
            f.next();
            assertTrue(!pending.isDone(), transition + ": flush advances no write acknowledgement sequence");
            f.controls[1][0].child("value").emit(.35);
            f.next();
            assertTrue(
                    pending.join().get("outcome").getAsString().equals("applied"),
                    transition + ": fresh same-source callback still verifies the write");
        }
    }

    @Test
    void rawDeviceTypeCallbacksReconcileCanonicalGetters() {
        Fake f = new Fake();
        f.call("devices.remote.get", target(1));
        f.devices[1].child("exists").emit(false);
        FakeHost type = f.devices[1].child("deviceType");
        type.data = "audio-effect";
        for (Object observer : type.observers) ((EnumValueChangedCallback) observer).valueChanged("audio_to_audio");
        f.devices[1].child("exists").emit(true);
        f.flush();
        JsonObject recovered = f.call("devices.remote.get", target(1));
        assertTrue(
                recovered.get("complete").getAsBoolean()
                        && recovered.get("type").getAsString().equals("audio-effect"),
                "raw native type callback and canonical public getter cannot strand remote recovery");
    }

    @Test
    void recoveredPageBaselineInvalidatesLaterTransition() {
        Fake f = new Fake();
        f.call("devices.remote.get", target(1));
        f.devices[1].child("exists").emit(false);
        f.devices[1].child("exists").emit(true);
        f.pages[1].child("selectedPageIndex").data = 1;
        f.pages[1].child("getName").data = "Tone";
        f.flush();
        f.controls[1][0].child("value").emit(.8);
        f.pages[1].child("selectedPageIndex").emit(0);
        f.pages[1].child("getName").data = "Main";
        JsonObject pending = f.call("devices.remote.get", target(1));
        assertTrue(
                pending.getAsJsonArray("controls")
                        .get(0)
                        .getAsJsonObject()
                        .get("normalizedValue")
                        .isJsonNull(),
                "page callback after getter-only recovery invalidates predecessor readiness");
        f.controls[1][0].child("value").data = .6;
        f.flush();
        JsonObject current = f.call("devices.remote.get", target(1));
        f.controls[1][0].child("value").notifyWrites = false;
        JsonObject result = f.run("devices.remote.set", remoteWrite(current, 1, .6), f.tasks.after(20))
                .join();
        assertTrue(
                result.get("reason").getAsString().equals("not_observed")
                        && f.controls[1][0].child("value").writes == 1,
                "recovered page baseline does not preserve predecessor value freshness");
    }

    @Test
    void pageRoundtripRecoversUnchangedValues() {
        Fake f = new Fake();
        FakeHost neighbor = f.controls[1][1];
        neighbor.child("exists").emit(true);
        neighbor.child("name").emit("Neighbor");
        neighbor.child("value").emit(.4);
        f.flush();
        JsonObject original = f.call("devices.remote.get", target(1));
        JsonObject page = target(1);
        page.add("contextToken", original.get("contextToken"));
        page.addProperty("pageIndex", 1);
        page.addProperty("expectedPageName", "Tone");
        JsonObject switched = f.call("devices.remote.page", page);
        neighbor.child("exists").emit(false);
        assertTrue(
                !switched.get("complete").getAsBoolean(),
                "page switch cannot expose predecessor controls before flush");
        f.flush();
        JsonObject tone = f.call("devices.remote.get", target(1));
        assertTrue(
                tone.get("complete").getAsBoolean() && remoteValue(tone) == .6,
                "unchanged slot value is readable at flush without any value callback");
        JsonObject absent = tone.getAsJsonArray("controls").get(1).getAsJsonObject();
        assertTrue(
                !absent.get("exists").getAsBoolean()
                        && absent.get("name").isJsonNull()
                        && absent.get("normalizedValue").isJsonNull(),
                "page recovery cannot expose an absent slot");
        page.add("contextToken", tone.get("contextToken"));
        page.addProperty("pageIndex", 0);
        page.addProperty("expectedPageName", "Main");
        f.call("devices.remote.page", page);
        neighbor.child("exists").emit(true);
        f.flush();
        JsonObject returned = f.call("devices.remote.get", target(1));
        assertTrue(
                returned.get("complete").getAsBoolean()
                        && remoteValue(returned) == .6
                        && returned.getAsJsonArray("controls")
                                        .get(1)
                                        .getAsJsonObject()
                                        .get("normalizedValue")
                                        .getAsDouble()
                                == .4,
                "page roundtrip restores unchanged values and returning controls without name/value callback replay");
        assertRpcError("stale_context", f.run("devices.remote.set", remoteWrite(original, 1, .6)));
        f.controls[1][0].child("value").notifyWrites = false;
        JsonObject result = f.run("devices.remote.set", remoteWrite(returned, 1, .6), f.tasks.after(20))
                .join();
        assertTrue(
                result.get("reason").getAsString().equals("not_observed")
                        && f.controls[1][0].child("value").writes == 1,
                "page-return getter equality is readable, not fresh no-op or write acknowledgement");
    }

    @Test
    void sameProcessReopenRecoversUnchangedHistory() {
        Fake f = new Fake();
        JsonObject original = f.call("devices.remote.get", target(1));
        f.firstTrack.child("exists").emit(false);
        f.devices[1].child("exists").emit(false);
        f.controls[1][0].child("exists").emit(false);
        f.pages[1].child("getName").emit("");
        f.controls[1][0].child("name").emit("");
        f.controls[1][0].child("value").emit(.91);
        f.flush();
        assertRpcError("outside_window", f.run("devices.remote.get", target(1)));
        // Native interested getters are updated at reopen; unchanged remote callbacks need not replay.
        f.pages[1].child("getName").data = "Main";
        f.controls[1][0].child("name").data = "Gain";
        f.controls[1][0].child("value").data = .6;
        f.controls[1][0].child("exists").data = true;
        f.devices[1].child("exists").emit(true);
        f.firstTrack.child("channelId").emit(TRACK);
        f.firstTrack.child("exists").emit(true);
        assertRpcError("not_ready", f.run("devices.remote.get", target(1)));
        f.flush();
        JsonObject reopened = f.call("devices.remote.get", target(1));
        assertTrue(
                reopened.get("complete").getAsBoolean()
                        && remoteValue(reopened) == .6
                        && reopened.get("pageName").getAsString().equals("Main"),
                "same-process reopen restores unchanged remote metadata/value history at the host flush");
        assertTrue(
                !reopened.get("contextToken").equals(original.get("contextToken")),
                "same UUID, device name and page after reopen still carry a different context token");
        assertRpcError("stale_context", f.run("devices.remote.set", remoteWrite(original, 1, .6)));
        assertTrue(
                f.controls[1][0].child("value").writes == 0 && f.pages[1].child("selectedPageIndex").writes == 0,
                "read recovery never writes values or selects pages to provoke callbacks");
    }

    @Test
    void pendingRemoteWritesCannotSurviveRebinding() {
        for (String transition : List.of("source", "page", "control", "mapping")) {
            Fake f = new Fake();
            JsonObject original = f.call("devices.remote.get", target(1));
            f.controls[1][0].child("value").notifyWrites = false;
            CompletableFuture<JsonObject> pending = f.run("devices.remote.set", remoteWrite(original, 1, .9));
            if (transition.equals("source")) {
                f.devices[1].child("exists").emit(false);
                f.devices[1].child("exists").emit(true);
            } else if (transition.equals("page")) {
                f.pages[1].child("selectedPageIndex").emit(1);
                f.pages[1].child("getName").emit("Tone");
            } else if (transition.equals("control")) {
                f.controls[1][0].child("exists").emit(false);
                f.controls[1][0].child("exists").emit(true);
            } else {
                f.controls[1][0].child("isBeingMapped").emit(true);
                f.controls[1][0].child("value").emit(.9);
                f.flush();
                f.controls[1][0].child("isBeingMapped").emit(false);
            }
            f.flush();
            f.next();
            assertTrue(
                    pending.join().get("outcome").getAsString().equals("unknown")
                            && pending.join().get("reason").getAsString().equals("target_changed"),
                    transition + ": lifecycle change invalidates the pending write even after flush recovery");
            JsonObject current = f.call("devices.remote.get", target(1));
            assertTrue(
                    remoteValue(current) == .9 && f.controls[1][0].child("value").writes == 1,
                    transition
                            + ": getter recovery never retries or retrospectively acknowledges the invalidated write");
        }
    }

    @Test
    void flushCannotInventUnobservedOrFiniteValues() {
        for (String field : List.of("getName", "exists", "name", "isBeingMapped", "value")) {
            Fake f = new Fake(false, false, Set.of(field));
            f.devices[1].child("exists").emit(false);
            f.devices[1].child("exists").emit(true);
            f.flush();
            if (field.equals("getName")) {
                assertRpcError("not_ready", f.run("devices.remote.get", target(1)));
                f.pages[1].child(field).emit("Main");
            } else {
                JsonObject incomplete = f.call("devices.remote.get", target(1));
                String key = field.equals("value") ? "normalizedValue" : field;
                assertTrue(
                        !incomplete.get("complete").getAsBoolean()
                                && incomplete
                                        .getAsJsonArray("controls")
                                        .get(0)
                                        .getAsJsonObject()
                                        .get(key)
                                        .isJsonNull(),
                        field + ": flush cannot fabricate an observation from an unobserved default getter");
                assertRpcError("stale_context", f.run("devices.remote.set", remoteWrite(incomplete, 1, .6)));
                f.controls[1][0].child(field).emit(f.controls[1][0].child(field).data);
            }
            f.flush();
            assertTrue(
                    remoteValue(f.call("devices.remote.get", target(1))) == .6,
                    field + ": actual first observation makes the source readable");
        }
        Fake f = new Fake();
        f.controls[1][0].child("value").emit(Double.NaN);
        f.devices[1].child("exists").emit(false);
        f.devices[1].child("exists").emit(true);
        f.flush();
        JsonObject nonfinite = f.call("devices.remote.get", target(1));
        assertTrue(
                nonfinite
                        .getAsJsonArray("controls")
                        .get(0)
                        .getAsJsonObject()
                        .get("normalizedValue")
                        .isJsonNull(),
                "nonfinite current getter cannot fall back to the last finite observation");
        assertRpcError("stale_context", f.run("devices.remote.set", remoteWrite(nonfinite, 1, .6)));
        assertTrue(f.controls[1][0].child("value").writes == 0, "nonfinite readback cannot authorize a write");
    }

    @Test
    void recoveredSameValueWritesRetainSafetyAndFreshness() {
        for (String unsafe : List.of(
                "isArrangerRecordEnabled",
                "isArrangerAutomationWriteEnabled",
                "isClipLauncherAutomationWriteEnabled")) {
            Fake f = new Fake();
            f.devices[1].child("exists").emit(false);
            f.devices[1].child("exists").emit(true);
            f.flush();
            JsonObject current = f.call("devices.remote.get", target(1));
            f.transport.child(unsafe).emit(true);
            assertRpcError("unsafe_state", f.run("devices.remote.set", remoteWrite(current, 1, .6)));
            assertTrue(
                    f.controls[1][0].child("value").writes == 0,
                    unsafe + ": getter equality after rebind cannot bypass playback safety as a false no-op");
        }
        Fake f = new Fake();
        f.devices[1].child("exists").emit(false);
        f.devices[1].child("exists").emit(true);
        f.flush();
        JsonObject current = f.call("devices.remote.get", target(1));
        JsonObject page = target(1);
        page.add("contextToken", current.get("contextToken"));
        page.addProperty("pageIndex", 0);
        page.addProperty("expectedPageName", "Main");
        f.pages[1].child("selectedPageIndex").notifyWrites = false;
        JsonObject result =
                f.run("devices.remote.page", page, f.tasks.after(20)).join();
        assertTrue(
                result.get("reason").getAsString().equals("not_observed")
                        && f.pages[1].child("selectedPageIndex").writes == 1,
                "recovered unchanged page index also requires a fresh observer, not a false verified no-op");
        Fake mapped = new Fake();
        mapped.controls[1][0].child("isBeingMapped").emit(true);
        mapped.controls[1][0].child("value").emit(.6);
        mapped.flush();
        JsonObject mapping = mapped.call("devices.remote.get", target(1));
        assertTrue(
                mapping.getAsJsonArray("controls")
                        .get(0)
                        .getAsJsonObject()
                        .get("normalizedValue")
                        .isJsonNull(),
                "flush while mapping cannot expose a mapped value");
        assertRpcError("stale_context", mapped.run("devices.remote.set", remoteWrite(mapping, 1, .6)));
        mapped.controls[1][0].child("isBeingMapped").emit(false);
        mapped.flush();
        JsonObject unmapped = mapped.call("devices.remote.get", target(1));
        mapped.controls[1][0].child("value").notifyWrites = false;
        JsonObject unacknowledged = mapped.run(
                        "devices.remote.set", remoteWrite(unmapped, 1, .6), mapped.tasks.after(20))
                .join();
        assertTrue(
                unacknowledged.get("reason").getAsString().equals("not_observed")
                        && mapped.controls[1][0].child("value").writes == 1,
                "callback during remapping cannot become fresh no-op proof after flush");
    }

    @Test
    void deviceRenamePreservesRemoteObservations() {
        Fake f = new Fake();
        JsonObject old = f.call("devices.remote.get", target(1));
        f.devices[1].child("name").emit("Renamed");
        JsonObject renamed = target(1);
        renamed.addProperty("expectedDeviceName", "Renamed");
        JsonObject fresh = f.call("devices.remote.get", renamed);
        assertTrue(
                remoteValue(fresh) == .6,
                "device metadata rename retains continuously bound remote values without callback replay");
        JsonObject stale = remoteWrite(old, 1, .8);
        stale.addProperty("expectedDeviceName", "Renamed");
        assertRpcError("stale_context", f.run("devices.remote.set", stale));
        JsonObject write = remoteWrite(fresh, 1, .8);
        write.addProperty("expectedDeviceName", "Renamed");
        assertTrue(
                f.call("devices.remote.set", write).get("outcome").getAsString().equals("applied"),
                "fresh name/context permits same-source remote write without replaying unchanged page metadata");
    }

    private static double remoteValue(JsonObject result) {
        return result.getAsJsonArray("controls")
                .get(0)
                .getAsJsonObject()
                .get("normalizedValue")
                .getAsDouble();
    }

    private static JsonObject remoteWrite(JsonObject listing, int device, double value) {
        JsonObject p = target(device);
        p.add("contextToken", listing.get("contextToken"));
        p.add("expectedPageName", listing.get("pageName"));
        p.addProperty("parameterIndex", 0);
        p.addProperty("expectedParameterName", "Gain");
        p.addProperty("normalizedValue", value);
        return p;
    }

    private static JsonObject target(int index) {
        JsonObject p = new JsonObject();
        p.addProperty("trackId", TRACK);
        p.addProperty("deviceIndex", index);
        p.addProperty("expectedDeviceName", "Twin");
        return p;
    }

    private static JsonObject insert(String kind, String id) {
        JsonObject p = new JsonObject();
        p.addProperty("trackId", TRACK);
        p.addProperty("expectedDeviceCount", 2);
        p.addProperty("kind", kind);
        p.addProperty("id", id);
        return p;
    }

    private static JsonObject file(String path) {
        JsonObject p = insert("file", "");
        p.remove("id");
        p.addProperty("path", path);
        return p;
    }

    private static final class Fake {
        final ScheduledTasks tasks = new ScheduledTasks();
        final FakeHost[] devices = new FakeHost[16], pages = new FakeHost[16];
        final FakeHost[][] controls = new FakeHost[16][8];
        final FakeHost bank = new FakeHost("bank");
        final FakeHost transport = new FakeHost("transport");
        final TestServices services;
        final DeviceModule module;
        int insertions, flushRequests, selected = -1;
        String insertMode = "one", deleteMode = "one", moveMode = "one", lastInsertion;
        final List<String[]> chain =
                new ArrayList<>(List.of(new String[] {"Twin", "audio-effect"}, new String[] {"Twin", "audio-effect"}));
        final List<String> points = new ArrayList<>();
        final Device[] deviceProxies = new Device[16];
        int deletions;
        Object lastArgument;
        FakeHost firstTrack;
        boolean initialized, running;

        Fake() {
            this(false);
        }

        Fake(boolean lateIdentity) {
            this(lateIdentity, false);
        }

        Fake(boolean lateIdentity, boolean lateControlPresence) {
            this(lateIdentity, lateControlPresence, Set.of());
        }

        Fake(boolean lateIdentity, boolean lateControlPresence, Set<String> unobserved) {
            FakeHost trackBank = new FakeHost("trackBank");
            Track[] tracks = new Track[HostLimits.TRACKS];
            trackBank.put("scrollPosition", 0);
            trackBank.put("itemCount", 1);
            trackBank.on("getItemAt", args -> tracks[(Integer) args[0]]);
            for (int t = 0; t < HostFixture.DETAILED_TRACKS; t++) {
                final int trackIndex = t;
                FakeHost track = new FakeHost("track" + t);
                track.put("exists", t == 0 && !lateIdentity);
                track.put("channelId", t == 0 && !lateIdentity ? TRACK : "");
                track.put("name", "Fixture");
                track.put("position", t);
                track.put("trackType", "Instrument");
                track.put("isGroup", false);
                track.put("isGroupExpanded", false);
                FakeHost parentTrack = new FakeHost("parentTrack");
                parentTrack.put("exists", false);
                parentTrack.put("channelId", "");
                track.attach("createParentTrack", parentTrack);
                if (t == 0) firstTrack = track;
                FakeHost deviceBank = t == 0 ? bank : new FakeHost("deviceBank" + t);
                Device[] objects = new Device[16];
                deviceBank.put("scrollPosition", 0);
                deviceBank.put("itemCount", t == 0 ? 2 : 0);
                deviceBank.put("exists", true);
                deviceBank.on("getItemAt", args -> objects[(Integer) args[0]]);
                for (int d = 0; d < 16; d++) {
                    final int deviceIndex = d;
                    FakeHost device = new FakeHost("device" + d);
                    device.put("exists", t == 0 && d < 2 && !lateIdentity);
                    device.put("name", lateIdentity ? "" : "Twin");
                    device.put("position", lateIdentity ? -1 : d);
                    device.put("deviceType", lateIdentity ? "" : "audio-effect");
                    device.put("isEnabled", true);
                    device.put("hasLayers", false);
                    device.attach("createLayerBank", emptyLayers());
                    device.child("isEnabled").beforeWrite = this::assertHostTask;
                    FakeHost page = new FakeHost("page" + d);
                    page.put("pageNames", new String[] {"Main", "Tone"});
                    page.put("pageCount", 2);
                    page.put("selectedPageIndex", 0);
                    page.put("getName", "Main");
                    for (String field : unobserved)
                        if (page.children.containsKey(field)) page.child(field).notifyInitial = false;
                    page.child("selectedPageIndex").beforeWrite = this::assertHostTask;
                    page.child("selectedPageIndex").afterWrite = () -> page.child("getName")
                            .emit(((Integer) page.child("selectedPageIndex").data) == 0 ? "Main" : "Tone");
                    RemoteControl[] remoteObjects = new RemoteControl[8];
                    for (int r = 0; r < 8; r++) {
                        FakeHost remote = new FakeHost("remote" + r);
                        remote.put("exists", r == 0 && !lateControlPresence);
                        remote.put("name", r == 0 ? "Gain" : "");
                        remote.put("isBeingMapped", false);
                        remote.put("value", d == 0 ? .25 : .6);
                        for (String field : unobserved)
                            if (remote.children.containsKey(field)) remote.child(field).notifyInitial = false;
                        remote.child("value").beforeWrite = this::assertHostTask;
                        remote.child("value").takeoverPending = true;
                        remoteObjects[r] = remote.proxy(RemoteControl.class);
                        if (t == 0) controls[d][r] = remote;
                    }
                    page.on("getParameter", args -> remoteObjects[(Integer) args[0]]);
                    device.on("createCursorRemoteControlsPage", args -> {
                        if (initialized) throw new AssertionError("remote proxy created after initialization");
                        return page.proxy(CursorRemoteControlsPage.class);
                    });
                    device.on("selectInEditor", args -> {
                        assertHostTask();
                        selected = deviceIndex;
                        return null;
                    });
                    device.on("deleteObject", args -> {
                        assertHostTask();
                        assertTrue(trackIndex == 0, "deletion uses the explicit track");
                        deletions++;
                        if (!deleteMode.equals("none") && deviceIndex < chain.size()) {
                            List<String[]> next = new ArrayList<>(chain);
                            next.remove(deviceIndex);
                            if (deleteMode.equals("prefix") && !next.isEmpty())
                                next.set(0, new String[] {"Changed prefix", "audio-effect"});
                            render(next);
                        }
                        return null;
                    });
                    device.attach("beforeDeviceInsertionPoint", point("before", deviceIndex, trackIndex));
                    device.attach("afterDeviceInsertionPoint", point("after", deviceIndex, trackIndex));
                    objects[d] = device.proxy(Device.class);
                    if (t == 0) deviceProxies[d] = objects[d];
                    if (t == 0) {
                        devices[d] = device;
                        pages[d] = page;
                    }
                }
                track.attach("createDeviceBank", deviceBank);
                FakeHost end = new FakeHost("end");
                end.onMethods(InsertionPoint.class, name -> name.startsWith("insert"), (name, args) -> {
                    assertHostTask();
                    assertTrue(trackIndex == 0, "insertion uses explicit track endpoint");
                    insertions++;
                    lastInsertion = name;
                    lastArgument = args[0];
                    if (!insertMode.equals("none")) {
                        append();
                        if (insertMode.equals("double")) append();
                        if (insertMode.equals("prefix"))
                            devices[0].child("name").emit("Changed prefix");
                    }
                    return null;
                });
                track.attach("endOfDeviceChainInsertionPoint", end);
                track.attach("startOfDeviceChainInsertionPoint", point("start", -1, trackIndex));
                tracks[t] = track.proxy(Track.class);
            }
            for (int t = HostFixture.DETAILED_TRACKS; t < tracks.length; t++)
                tracks[t] = tracks[HostFixture.DETAILED_TRACKS - 1];
            FakeHost app = new FakeHost("app");
            app.put("projectName", "Fixture");
            app.put("hasActiveEngine", true);
            transport.put("isPlaying", false);
            transport.put("isArrangerRecordEnabled", false);
            transport.put("isArrangerAutomationWriteEnabled", false);
            transport.put("isClipLauncherAutomationWriteEnabled", false);
            FakeHost host = new FakeHost("host");
            host.attach("createApplication", app);
            FakeHost project = new FakeHost("project");
            project.put("isModified", false);
            host.attach("getProject", project);
            host.attach("createTransport", transport);
            host.attach("createTrackBank", trackBank);
            host.on("scheduleTask", args -> {
                tasks.schedule((Runnable) args[0], ((Number) args[1]).longValue());
                return null;
            });
            host.on("requestFlush", args -> {
                flushRequests++;
                return null;
            });
            services = new TestServices(host.proxy(ControllerHost.class), tasks::now);
            module = services.device();
            initialized = true;
        }
        /** Public insertion-point model: inserts or moves relative to an anchor slot of the track-0 chain. */
        FakeHost point(String mode, int anchor, int trackIndex) {
            FakeHost node = new FakeHost("node");
            node.onMethods(
                    InsertionPoint.class,
                    name -> name.startsWith("insert") || name.equals("moveDevices"),
                    (name, args) -> {
                        boolean insert = name.startsWith("insert");
                        assertHostTask();
                        assertTrue(trackIndex == 0, "positioned edit uses the explicit track");
                        points.add(mode + ":" + anchor + ":" + name);
                        if (insert ? insertMode.equals("none") : moveMode.equals("none")) return null;
                        List<String[]> next = new ArrayList<>(chain);
                        if (insert) {
                            int index = mode.equals("start") ? 0 : mode.equals("before") ? anchor : anchor + 1;
                            next.add(index, new String[] {"Inserted", "audio-effect"});
                        } else {
                            Device moving = ((Device[]) args[0])[0];
                            int from = Arrays.asList(deviceProxies).indexOf(moving);
                            String[] entry = next.remove(from);
                            int adjusted = anchor > from ? anchor - 1 : anchor;
                            next.add(mode.equals("before") ? adjusted : adjusted + 1, entry);
                        }
                        render(next);
                        return null;
                    });
            return node;
        }
        /** Emit the observer traffic of the chain becoming `next`, in the order the host reports it. */
        void render(List<String[]> next) {
            int old = chain.size();
            for (int i = 0; i < 16; i++) {
                if (i < next.size()) {
                    String[] entry = next.get(i);
                    boolean fresh = i >= old;
                    if (fresh || !chain.get(i)[1].equals(entry[1]))
                        devices[i].child("deviceType").emit(entry[1]);
                    if (fresh || !chain.get(i)[0].equals(entry[0]))
                        devices[i].child("name").emit(entry[0]);
                    if (fresh) devices[i].child("exists").emit(true);
                } else if (i < old) {
                    devices[i].child("exists").emit(false);
                }
            }
            List<String[]> copy = new ArrayList<>(next);
            chain.clear();
            chain.addAll(copy);
            if (next.size() != old) bank.child("itemCount").emit(next.size());
        }

        void assertHostTask() {
            if (!running) throw new AssertionError("mutation outside scheduled host task");
        }

        void flush() {
            running = true;
            try {
                module.flush();
            } finally {
                running = false;
            }
        }

        void append() {
            int index = (Integer) bank.child("itemCount").data;
            if (!insertMode.equals("countOnly")) devices[index].child("name").emit("Inserted");
            devices[index].child("exists").emit(true);
            bank.child("itemCount").emit(index + 1);
        }

        CompletableFuture<JsonObject> request(String method, JsonObject p) {
            return request(method, p, tasks.after(2000));
        }

        CompletableFuture<JsonObject> request(String method, JsonObject p, long deadline) {
            HostOperation op = new HostOperation(deadline);
            try {
                DeviceModule.validateParams(method, p);
                services.enqueue(op, () -> module.dispatch(op, method, p.deepCopy()));
            } catch (RuntimeException error) {
                op.result.completeExceptionally(error);
            }
            return op.result;
        }

        CompletableFuture<JsonObject> run(String method, JsonObject p) {
            CompletableFuture<JsonObject> f = request(method, p);
            next();
            return f;
        }

        CompletableFuture<JsonObject> run(String method, JsonObject p, long deadline) {
            CompletableFuture<JsonObject> f = request(method, p, deadline);
            next();
            return f;
        }

        JsonObject call(String method, JsonObject p) {
            return run(method, p).join();
        }

        void next() {
            Runnable task = tasks.take();
            running = true;
            try {
                task.run();
            } finally {
                running = false;
            }
        }
    }

    private static FakeHost emptyLayers() {
        FakeHost bank = emptyBank();
        DeviceLayer[] layers = new DeviceLayer[2];
        bank.on("getItemAt", args -> layers[(Integer) args[0]]);
        for (int l = 0; l < layers.length; l++) {
            FakeHost layer = new FakeHost("layer");
            layer.put("exists", false);
            layer.put("channelId", "");
            layer.put("channelIndex", l);
            layer.put("name", "");
            FakeHost children = emptyBank();
            Device[] devices = new Device[2];
            children.on("getItemAt", args -> devices[(Integer) args[0]]);
            for (int c = 0; c < devices.length; c++) {
                FakeHost child = new FakeHost("child");
                child.put("exists", false);
                child.put("name", "");
                child.put("position", c);
                child.put("deviceType", "");
                child.on("addDirectParameterIdObserver", args -> {
                    ((StringArrayValueChangedCallback) args[0]).valueChanged(new String[0]);
                    return null;
                });
                child.on("createCursorRemoteControlsPage", args -> {
                    throw new AssertionError("Child remote controls are outside the fixed layer graph");
                });
                devices[c] = child.proxy(Device.class);
            }
            layer.attach("createDeviceBank", children);
            layers[l] = layer.proxy(DeviceLayer.class);
        }
        return bank;
    }

    private static FakeHost emptyBank() {
        FakeHost bank = new FakeHost("bank");
        bank.put("exists", false);
        bank.put("itemCount", 0);
        bank.put("scrollPosition", 0);
        return bank;
    }

    @Test
    void unknownOperationAndUnchangedPageAreReported() {
        assertRpcError("unknown_method", () -> DeviceModule.validateParams("devices.bogus", new JsonObject()));
        Fake f = new Fake();
        JsonObject listing = f.call("devices.remote.get", target(1));
        JsonObject p = target(1);
        p.add("contextToken", listing.get("contextToken"));
        p.addProperty("pageIndex", 0);
        p.addProperty("expectedPageName", "Main");
        assertTrue(
                f.call("devices.remote.page", p).get("outcome").getAsString().equals("already_matched"),
                "the selected page is not written again");
    }
}
