package dev.bitwigagent;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import dev.bitwigagent.core.MethodSpecs;
import dev.bitwigagent.core.Refs;
import dev.bitwigagent.core.SchemaValidator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Refs built in Java are the refs the Python protocol defines, and compare by the roles it exports. */
public final class RefsTest {
    private static final String TRACK = "8f2c0000-0000-4000-8000-000000000001";
    private static final String UUID = "c41d0000-0000-4000-8000-000000000002";
    private static final JsonObject track = Refs.track(TRACK, "Lead");
    private static final JsonObject device = Refs.device(track, 1, "EQ+");
    private static final JsonObject chain = Refs.chain(device, UUID, "Pad");
    private static final JsonObject scene = Refs.scene(33, "Drop");

    private static List<JsonObject> everyKind() {
        JsonObject page = Refs.remotePage(device, 0, "Main");
        JsonObject module = Refs.gridModule(device, UUID, "F: lp");
        JsonObject modulator = Refs.modulator(device, UUID, "LFO");
        return List.of(
                track,
                scene,
                Refs.scene(0, null),
                Refs.slot(track, scene),
                Refs.launcherClip(track, scene, "Hook"),
                Refs.arrangerClip(track, 64, "Hook"),
                device,
                chain,
                Refs.device(chain, 0, "Polysynth"),
                page,
                Refs.parameter(device, "CONTENTS/GAIN", "Gain"),
                Refs.parameter(page, "2", "Gain 1"),
                Refs.parameter(modulator, "RATE", null),
                Refs.parameter(module, "CUTOFF", null),
                Refs.parameter(track, "volume", null),
                Refs.send(track, 0, "Reverb"),
                Refs.sidechain(device, 0, "component", UUID, null),
                Refs.sidechain(device, 1, "component", null, UUID),
                modulator,
                module,
                Refs.marker(2, "Verse", 16));
    }

    @Test
    void everyBuiltRefPassesTheProtocolSchemaAndUsesOnlyItsKindsRoles() {
        Map<String, MethodSpecs.RefRoles> roles = MethodSpecs.load().refRoles();
        Set<String> kinds = new HashSet<>();
        SchemaValidator schema = HostFixture.REF_SCHEMA;
        Set<String> tags = new HashSet<>();
        for (JsonObject ref : everyKind()) {
            assertDoesNotThrow(() -> schema.apply(ref), ref.toString());
            tags.add(Refs.tag(ref));
            assertNotNull(Refs.read(ref), Refs.tag(ref));
            MethodSpecs.RefRoles role = roles.get(Refs.kind(ref));
            Set<String> fields = new HashSet<>(Set.of("kind"));
            fields.addAll(role.locate);
            fields.addAll(role.compared);
            fields.addAll(role.targetOnly);
            assertTrue(fields.containsAll(ref.keySet()), ref + " has a field outside its kind's roles");
            kinds.add(Refs.kind(ref));
        }
        assertEquals(roles.keySet(), kinds, "a builder for every kind");
        assertEquals(MethodSpecs.load().refReads().keySet(), tags, "Java tags every variant as Python does");
    }

    @Test
    void comparedFieldsAlwaysMatterTargetOnlyFieldsOnlyForTheTarget() {
        JsonObject renamedTrack = Refs.device(Refs.track(TRACK, "Bass"), 1, "EQ+");
        assertTrue(Refs.matches(device, renamedTrack, true), "a track name is not compared as an ancestor");
        assertFalse(Refs.matches(track, Refs.track(TRACK, "Bass"), true), "a track name is compared as the target");
        assertTrue(Refs.matches(track, Refs.track(TRACK, "Bass"), false));
        assertFalse(Refs.matches(device, Refs.device(track, 1, "Comp"), false), "a device name is always compared");
        assertFalse(Refs.matches(device, Refs.device(track, 2, "EQ+"), true), "a locating field differs");
        assertFalse(Refs.matches(device, Refs.device(chain, 1, "EQ+"), true), "another parent kind");
        JsonObject renamedChain = Refs.device(Refs.chain(device, UUID, "Lead"), 0, "Polysynth");
        assertTrue(Refs.matches(Refs.device(chain, 0, "Polysynth"), renamedChain, true));
        assertFalse(Refs.matches(scene, Refs.scene(33, null), true), "an unnamed scene is not the named one");
        assertTrue(Refs.matches(Refs.scene(3, null), Refs.scene(3, null), true));
        JsonObject clip = Refs.launcherClip(track, scene, "Hook");
        assertFalse(
                Refs.matches(clip, Refs.launcherClip(track, Refs.scene(33, "Intro"), "Hook"), true),
                "the scene inside the slot location is compared as an ancestor");
        assertFalse(Refs.matches(clip, Refs.arrangerClip(track, 0, "Hook"), true), "another clip location");
        JsonObject wider = clip.deepCopy();
        wider.getAsJsonObject("slot").addProperty("lane", "main");
        assertFalse(Refs.matches(clip, wider, true), "a location object with other fields");
        assertTrue(Refs.matches(Refs.arrangerClip(track, 64, "Hook"), Refs.arrangerClip(track, 64.0, "Hook"), true));
        JsonObject direct = Refs.parameter(device, "GAIN", null);
        assertTrue(
                Refs.matches(direct, Refs.parameter(device, "GAIN", "Gain"), true), "an omitted name is not compared");
        assertFalse(Refs.matches(Refs.parameter(device, "GAIN", "Tone"), Refs.parameter(device, "GAIN", "Gain"), true));
        JsonObject owned = Refs.sidechain(device, 0, "component", UUID, null);
        assertFalse(Refs.matches(owned, Refs.sidechain(device, 0, "component", null, null), true), "owner vanished");
        assertFalse(Refs.matches(Refs.marker(1, "A", 4), Refs.marker(1, "A", 8), true), "a moved marker");
        JsonObject unknown = new JsonObject();
        unknown.addProperty("kind", "galaxy");
        assertFalse(Refs.matches(unknown, unknown, true), "an unknown kind never matches");
        assertNull(Refs.kind(new JsonObject()));
    }
}
