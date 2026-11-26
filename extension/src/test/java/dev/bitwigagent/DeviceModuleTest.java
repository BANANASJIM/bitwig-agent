package dev.bitwigagent;

import static dev.bitwigagent.DeviceFixture.params;
import static dev.bitwigagent.RpcAssert.*;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import dev.bitwigagent.core.Refs;
import java.util.*;
import org.junit.jupiter.api.Test;

/** devices.get/set/select/catalog against a simulated host; never evidence of native Bitwig behavior. */
public final class DeviceModuleTest {
    private static JsonObject target(JsonObject ref) {
        return params("target", ref);
    }

    private static JsonObject enable(JsonObject ref, boolean enabled) {
        return params("target", ref, "enabled", enabled);
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
        DeviceFixture f = DeviceFixture.twins();
        DeviceModule module = f.device();
        List<JsonObject> badTargets = new ArrayList<>();
        JsonObject ref = f.twin(0);
        ref.addProperty("index", 16);
        badTargets.add(target(ref));
        ref = f.twin(0);
        ref.addProperty("index", .5);
        badTargets.add(target(ref));
        ref = f.twin(0);
        ref.remove("name");
        badTargets.add(target(ref));
        ref = f.twin(0);
        ref.addProperty("extra", true);
        badTargets.add(target(ref));
        badTargets.add(target(f.trackRef));
        badTargets.add(params("target", JsonNull.INSTANCE));
        badTargets.add(new JsonObject());
        for (JsonObject bad : badTargets) assertRpcError("invalid_params", f.submit(module, "devices.get", bad));
        assertRpcError(
                "invalid_params", f.submit(module, "devices.set", params("target", f.twin(0), "enabled", "false")));
        assertRpcError("invalid_params", f.submit(module, "devices.set", target(f.twin(0))));
        assertRpcError("invalid_params", f.submit(module, "devices.catalog", params("extra", true)));
        assertTrue(f.host.tasks.isEmpty(), "invalid inputs cannot schedule");
        assertEquals(0, f.devices[0].child("isEnabled").writes + f.devices[1].child("isEnabled").writes);
    }

    @Test
    void sameNameTargetsStayExplicit() {
        DeviceFixture f = DeviceFixture.twins();
        DeviceModule module = f.device();
        JsonObject first = f.call(module, "devices.get", target(f.twin(0)));
        JsonObject second = f.call(module, "devices.get", target(f.twin(1)));
        assertEquals(first.get("name"), second.get("name"), "the fixture has same-name devices");
        assertNotEquals(first.get("ref"), second.get("ref"), "their refs differ by index");
        assertEquals(f.twin(1), second.getAsJsonObject("ref"));
        HostFixture.REF_SCHEMA.apply(second.getAsJsonObject("ref"));
        assertEquals("audio-effect", second.get("type").getAsString());
        assertTrue(second.get("enabled").getAsBoolean());
        assertEquals("root_device", second.get("scope").getAsString());
        assertTrue(second.get("remoteControlsAvailable").getAsBoolean());
        assertFalse(first.get("nameWritable").getAsBoolean(), "public device rename limitation is explicit");

        JsonObject result = f.call(module, "devices.set", enable(f.twin(1), false));
        assertEquals("applied", result.get("outcome").getAsString());
        assertEquals(f.twin(1), result.getAsJsonObject("ref"));
        JsonObject field = result.getAsJsonObject("fields").getAsJsonObject("enabled");
        assertFalse(field.get("requested").getAsBoolean());
        assertEquals("applied", field.get("outcome").getAsString());
        assertFalse(field.has("observed"), "observed is present only when it differs from what was asked");
        assertEquals(0, f.devices[0].child("isEnabled").writes, "the same-name device at index zero is untouched");
        assertEquals(1, f.devices[1].child("isEnabled").writes);

        result = f.call(module, "devices.select", target(f.twin(1)));
        assertEquals(1, f.top.selected, "selection targets the ref's device, not the first matching name");
        assertEquals("sent", result.get("outcome").getAsString(), "selection never fabricates completion");
        assertEquals(f.twin(1), result.getAsJsonObject("ref"));

        RpcException renamed =
                assertRpcError("target_changed", f.run(module, "devices.set", enable(f.device(1, "Wrong"), true)));
        assertEquals(f.twin(1), renamed.current, "target_changed names the device that is there now");
        HostFixture.REF_SCHEMA.apply(renamed.current);
        assertEquals(1, f.devices[1].child("isEnabled").writes, "a name mismatch fails before dispatch");

        RpcException absent =
                assertRpcError("target_not_found", f.run(module, "devices.get", target(f.device(2, "Twin"))));
        assertEquals("devices.list", absent.next);
    }

    @Test
    void aDeviceInsideAChainIsReadAndWrittenByItsRef() {
        DeviceFixture f = new DeviceFixture();
        f.top.seed("Comp", "Rack");
        DeviceFixture.Rack rack = f.rack(1);
        rack.layer(0, DeviceFixture.LAYER_A, "Pad", "Delay", "Reverb");
        f.start();
        DeviceModule module = f.device();
        JsonObject delay = rack.deviceRef(0, 0);
        JsonObject got = f.call(module, "devices.get", target(delay));
        assertEquals("chain_device", got.get("scope").getAsString());
        assertFalse(
                got.get("remoteControlsAvailable").getAsBoolean(),
                "a device inside a chain has no Remote Controls here");
        assertEquals(delay, got.getAsJsonObject("ref"));
        HostFixture.REF_SCHEMA.apply(got.getAsJsonObject("ref"));
        JsonObject result = f.call(module, "devices.set", enable(rack.deviceRef(0, 1), false));
        assertEquals("applied", result.get("outcome").getAsString());
        assertEquals(rack.deviceRef(0, 1), result.getAsJsonObject("ref"));
        FakeHost children = rack.layerNode(0).child("createDeviceBank");
        assertEquals(1, children.item(1).child("isEnabled").writes);
        assertEquals(0, children.item(0).child("isEnabled").writes);
        assertEquals(
                "sent",
                f.call(module, "devices.select", target(delay)).get("outcome").getAsString());
        assertEquals(0, rack.chains.get(0).selected, "the chain's own device is the one selected");
        assertEquals(-1, f.top.selected);
        RpcException renamed = assertRpcError(
                "target_changed", f.run(module, "devices.get", target(Refs.device(rack.chainRef(0), 1, "Gone"))));
        assertEquals(rack.deviceRef(0, 1), renamed.current);
        assertRpcError(
                "outside_window", f.run(module, "devices.get", target(Refs.device(rack.chainRef(0), 2, "Gone"))));
    }

    @Test
    void bypassNeedsFreshSameSourceObservation() {
        DeviceFixture f = DeviceFixture.twins();
        DeviceModule module = f.device();
        f.devices[1].child("isEnabled").notifyWrites = false;
        HostOperation op = f.submit(module, "devices.set", enable(f.twin(1), false));
        f.host.next();
        assertFalse(op.result.isDone(), "cached matching value without callback cannot verify bypass");
        f.devices[0].child("isEnabled").emit(false);
        f.host.next();
        assertFalse(op.result.isDone(), "other source cannot verify target bypass");
        f.devices[1].child("isEnabled").emit(false);
        f.host.next();
        assertOutcome("applied", op);
        int writes = f.devices[1].child("isEnabled").writes;
        HostOperation again = f.submit(module, "devices.set", enable(f.twin(1), false));
        f.host.next();
        assertOutcome("already_matched", again);
        assertEquals(writes, f.devices[1].child("isEnabled").writes, "an already observed bypass does not write again");
    }

    @Test
    void targetChangeAndDeadlinesNeverReplay() {
        DeviceFixture f = DeviceFixture.twins();
        DeviceModule module = f.device();
        JsonObject bypass = enable(f.twin(0), false);
        HostOperation expired = f.submit(module, "devices.set", bypass, f.host.tasks.now() - 1);
        f.host.next();
        assertRpcError("deadline", expired);
        assertEquals(0, f.devices[0].child("isEnabled").writes, "an expired request cannot bypass the device");
        HostOperation canceled = f.submit(module, "devices.set", bypass);
        canceled.result.cancel(false);
        f.host.next();
        assertEquals(0, f.devices[0].child("isEnabled").writes, "a canceled queued request cannot bypass the device");

        f.devices[0].child("isEnabled").notifyWrites = false;
        HostOperation pending = f.submit(module, "devices.set", bypass);
        f.host.next();
        f.devices[0].child("name").emit("Replacement");
        f.host.next();
        assertUnknown("target_changed", pending);
        assertEquals(1, f.devices[0].child("isEnabled").writes, "a changed target cannot replay the write");

        DeviceFixture shortWait = DeviceFixture.twins();
        shortWait.devices[0].child("isEnabled").notifyWrites = false;
        HostOperation timeout =
                shortWait.submit(shortWait.device(), "devices.set", bypass, shortWait.host.tasks.after(20));
        shortWait.host.next();
        assertUnknown("not_observed", timeout);
        assertEquals(1, shortWait.devices[0].child("isEnabled").writes, "a timeout never retries the write");
    }
}
