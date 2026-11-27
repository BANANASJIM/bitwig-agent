package dev.bitwigagent.project;

import static dev.bitwigagent.RpcAssert.*;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import dev.bitwigagent.HostFixture;
import dev.bitwigagent.core.HostOperation;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Host-free project.key.get/set on a simulated native key signature; not native acceptance. */
final class KeySignatureModuleTest {
    private final HostFixture f = new HostFixture();
    private final FakeKeySignature key = new FakeKeySignature();
    private final KeySignatureModule module = f.services.keySignature(key);

    private HostOperation run(String method, String params) {
        HostOperation op = f.submit(module, method, HostFixture.json(params));
        f.drain();
        return op;
    }

    @Test
    void getReadsBitwigsOwnOptionsAndLabels() {
        key.root = 2;
        key.scale = FakeKeySignature.MINOR;
        JsonObject result = run("project.key.get", "{}").result.join();
        assertEquals("D", result.getAsJsonObject("root").get("label").getAsString());
        assertEquals(2, result.getAsJsonObject("root").get("value").getAsInt());
        assertEquals(
                12, result.getAsJsonObject("root").getAsJsonArray("options").size());
        JsonObject scale = result.getAsJsonObject("scale");
        assertEquals(FakeKeySignature.MINOR, scale.get("value").getAsInt());
        assertEquals("Minor", scale.get("label").getAsString());
        assertEquals(
                "Major",
                scale.getAsJsonArray("options")
                        .get(0)
                        .getAsJsonObject()
                        .get("label")
                        .getAsString());
        assertEquals("minor", result.get("scaleName").getAsString());
        assertEquals("native_readback", result.get("verification").getAsString());
        assertEquals("fake", result.get("nativeVersion").getAsString(), "the answering host is named");
        assertTrue(key.writes.isEmpty());
    }

    @Test
    void aMaskOutsideTheListHasNoLabel() {
        key.scale = 7;
        JsonObject scale = run("project.key.get", "{}").result.join().getAsJsonObject("scale");
        assertTrue(scale.get("label").isJsonNull(), "no option is invented for a custom mask: " + scale);
    }

    @Test
    void setWritesBothFieldsInOneTransactionAndReadsThemBack() {
        JsonObject result = assertOutcome(
                "applied", run("project.key.set", "{\"root\":\"d\",\"scale\":" + FakeKeySignature.MINOR + "}"));
        assertEquals(List.of(Map.of("root", 2, "scale", FakeKeySignature.MINOR)), key.writes, "one native write");
        assertEquals(List.of("root", "scale"), List.copyOf(key.writes.get(0).keySet()), "root first");
        assertEquals("native_readback", result.get("verification").getAsString());
        JsonObject root = result.getAsJsonObject("fields").getAsJsonObject("root");
        assertEquals("applied", root.get("outcome").getAsString());
        assertEquals("D", root.get("observed").getAsString(), "a label request reads back as Bitwig's label");
        assertFalse(
                result.getAsJsonObject("fields").getAsJsonObject("scale").has("observed"),
                "a value request reads back as the same value");
        assertTrue(result.get("nativeUndo").getAsBoolean());
        assertEquals("Set Key Signature", result.get("undoLabel").getAsString());
        assertEquals("Minor", result.getAsJsonObject("scale").get("label").getAsString());
        assertTrue(f.services.holds.list().isEmpty(), "a confirmed write holds nothing");
    }

    @Test
    void onlyChangedFieldsAreWritten() {
        key.root = 2;
        JsonObject result = assertOutcome("applied", run("project.key.set", "{\"root\":\"D\",\"scale\":\"Minor\"}"));
        assertEquals(List.of(Map.of("scale", FakeKeySignature.MINOR)), key.writes);
        assertEquals(
                "already_matched",
                result.getAsJsonObject("fields")
                        .getAsJsonObject("root")
                        .get("outcome")
                        .getAsString());
    }

    @Test
    void aMatchingRequestWritesNothing() {
        JsonObject result =
                assertOutcome("already_matched", run("project.key.set", "{\"root\":0,\"scale\":\"major\"}"));
        assertTrue(key.writes.isEmpty());
        assertEquals("native_readback", result.get("verification").getAsString());
        assertFalse(result.get("nativeUndo").getAsBoolean());
        assertFalse(result.has("undoLabel"));
    }

    @Test
    void aChoiceOutsideBitwigsListIsRefusedBeforeWriting() {
        assertRpcError("invalid_params", run("project.key.set", "{\"root\":\"H\"}"));
        assertRpcError("invalid_params", run("project.key.set", "{\"scale\":1}"));
        assertTrue(key.writes.isEmpty());
    }

    @Test
    void aReadbackMismatchIsUnknownAndHoldsFurtherWrites() {
        key.dropWrites = true;
        JsonObject result = assertUnknown("readback_mismatch", run("project.key.set", "{\"root\":\"E\"}"));
        JsonObject root = result.getAsJsonObject("fields").getAsJsonObject("root");
        assertEquals("unknown", root.get("outcome").getAsString());
        assertEquals("C", root.get("observed").getAsString());
        assertFalse(result.has("verification"));
        assertTrue(result.has("message"));
        assertRpcError("unresolved", run("project.key.set", "{\"root\":\"E\"}"));
        assertEquals(1, key.writes.size(), "an unresolved write is never repeated");
        assertEquals(
                "native_readback",
                run("project.key.get", "{}").result.join().get("verification").getAsString());
        f.services.holds.ack("project.key");
        key.dropWrites = false;
        assertOutcome("applied", run("project.key.set", "{\"root\":\"E\"}"));
    }

    @Test
    void recordingOrAutomationWriteBlocksTheWrite() {
        f.transport.child("isArrangerAutomationWriteEnabled").emit(true);
        assertRpcError("unsafe_state", run("project.key.set", "{\"root\":\"E\"}"));
        assertTrue(key.writes.isEmpty());
        assertEquals(
                0,
                run("project.key.get", "{}")
                        .result
                        .join()
                        .getAsJsonObject("root")
                        .get("value")
                        .getAsInt());
    }

    @Test
    void aClosedGateIsNativeUnavailable() {
        KeySignatureModule ungated = f.services.keySignature(null);
        for (String method : List.of("project.key.get", "project.key.set")) {
            HostOperation op =
                    f.submit(ungated, method, HostFixture.json(method.endsWith("get") ? "{}" : "{\"root\":\"D\"}"));
            f.drain();
            assertRpcError("native_unavailable", op);
        }
    }

    @Test
    void validationIsStrict() {
        KeySignatureModule.validateParams("project.key.get", new JsonObject());
        KeySignatureModule.validateParams("project.key.set", HostFixture.json("{\"root\":2.0,\"scale\":\"Minor\"}"));
        for (String bad : List.of("{}", "{\"tonic\":\"D\"}", "{\"root\":2.5}", "{\"root\":true}", "{\"scale\":[1]}"))
            assertInvalidParams(() -> KeySignatureModule.validateParams("project.key.set", HostFixture.json(bad)));
        assertInvalidParams(() -> KeySignatureModule.validateParams("project.key.get", HostFixture.json("{\"x\":1}")));
        assertRpcError("unknown_method", () -> KeySignatureModule.validateParams("project.key.list", new JsonObject()));
    }
}
