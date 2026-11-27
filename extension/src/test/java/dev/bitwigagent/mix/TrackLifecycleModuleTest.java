package dev.bitwigagent.mix;

import static dev.bitwigagent.RpcAssert.*;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import dev.bitwigagent.FakeHost;
import dev.bitwigagent.HostFixture;
import dev.bitwigagent.core.HostLimits;
import dev.bitwigagent.core.HostOperation;
import dev.bitwigagent.core.Refs;
import dev.bitwigagent.core.Results;
import dev.bitwigagent.core.RpcException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * tracks.insert, tracks.group, tracks.duplicate and tracks.delete against the simulated host; no claim about native
 * completion.
 */
public final class TrackLifecycleModuleTest {
    @Test
    void creationNamesOnlyUniqueObservedAddition() {
        for (String type : List.of("audio", "instrument", "effect")) verifyCreation(type);
    }

    private static JsonObject delete(String id, String name) {
        JsonObject p = new JsonObject();
        p.add("target", Refs.track(id, name));
        return p;
    }

    private static MixModule mix(HostFixture f) {
        return f.services.sharedMix();
    }

    private static TrackLifecycleModule lifecycle(HostFixture f) {
        return lifecycle(f, null);
    }

    private static TrackLifecycleModule lifecycle(HostFixture f, TrackLifecycleModule.GroupAccess access) {
        return f.services.trackLifecycle(mix(f), access);
    }

    private static JsonObject read(HostFixture f, JsonObject ref) {
        JsonObject p = new JsonObject();
        p.add("target", ref);
        return f.call(mix(f), "tracks.get", p);
    }

    private static String id(JsonObject result) {
        return result.getAsJsonObject("ref").get("id").getAsString();
    }

    @Test
    void deleteIsNameGuardedAndVerifiedByUuidAndCount() {
        HostFixture f = new HostFixture();
        TrackLifecycleModule module = lifecycle(f);
        f.delete = slot -> f.removeTrack(slot, 1);
        HostOperation wrong = f.submit(module, "tracks.delete", delete(HostFixture.B, "Nope"));
        f.next();
        assertTrue(
                !assertRpcError("target_changed", wrong).unknown && f.tracks[1].actions == 0,
                "name mismatch never dispatches");
        f.tracks[1].child("trackType").emit("Master");
        HostOperation master = f.submit(module, "tracks.delete", delete(HostFixture.B, "Track 1"));
        f.next();
        assertTrue(!assertRpcError("unsupported_scope", master).unknown && f.tracks[1].actions == 0, "master refused");
        f.tracks[1].child("trackType").emit("Group");
        f.tracks[1].child("isGroup").data = true;
        HostOperation group = f.submit(module, "tracks.delete", delete(HostFixture.B, "Track 1"));
        f.next();
        assertTrue(
                !assertRpcError("not_ready", group).unknown && f.tracks[1].actions == 0,
                "a group whose children are not all observed is refused");
        f.tracks[1].child("isGroup").data = false;
        // An instrument track that tracks.bounce turned Hybrid stays deletable.
        f.tracks[1].child("trackType").emit("Hybrid");
        f.transport.child("isPlaying").emit(true);
        HostOperation playing = f.submit(module, "tracks.delete", delete(HostFixture.B, "Track 1"));
        f.next();
        assertTrue(!assertRpcError("unsafe_state", playing).unknown && f.tracks[1].actions == 0, "playing refused");
        f.transport.child("isPlaying").emit(false);
        HostOperation op = f.submit(module, "tracks.delete", delete(HostFixture.B, "Track 1"));
        f.next();
        if (!op.result.isDone()) f.next();
        JsonObject result = op.result.join();
        assertTrue(
                f.tracks[1].actions == 1
                        && result.get("outcome").getAsString().matches("applied|already_matched")
                        && result.get("trackCountBefore").getAsInt() == 2
                        && result.get("trackCountAfter").getAsInt() == 1,
                "deleted once, verified");
        assertEquals("api_readback", result.get("verification").getAsString());
        HostFixture silent = new HostFixture();
        TrackLifecycleModule other = lifecycle(silent);
        HostOperation nothing = silent.submit(other, "tracks.delete", delete(HostFixture.B, "Track 1"));
        silent.next();
        while (!nothing.result.isDone() && !silent.tasks.isEmpty()) silent.next();
        assertTrue(
                nothing.result.isDone()
                        && nothing.result.join().get("outcome").getAsString().equals("unknown"),
                "no observed change stays unknown");
    }

    private static void verifyCreation(String type) {
        HostFixture f = new HostFixture();
        TrackLifecycleModule module = lifecycle(f);
        f.create = factory -> {
            String hostType = Character.toUpperCase(type.charAt(0)) + type.substring(1);
            assertTrue(factory.equals("create" + hostType + "Track"), "requested type chooses exact public factory");
            f.addTrack(2, HostFixture.C, hostType);
        };
        HostOperation op = f.submit(module, "tracks.insert", create(type, -1));
        f.next();
        assertTrue(
                f.tracks[2].child("name").writes == 0 && !op.result.isDone(),
                "void factory is not immediate completion or slot naming");
        f.next();
        JsonObject result = op.result.join();
        assertTrue(id(result).equals(HostFixture.C), "new unique UUID selected by difference");
        assertTrue(result.get("verification").getAsString().equals("api_readback"), "creation evidence declared");
        assertTrue(
                f.tracks[2].child("name").writes == 1
                        && f.tracks[2].child("name").data.equals("Created"),
                "only unique observed target named");
        assertTrue(
                f.tracks[0].child("name").writes == 0 && f.tracks[1].child("name").writes == 0,
                "preexisting tracks not renamed");
        assertTrue(f.application.actions == 1, "factory not retried");
    }

    @Test
    void ambiguousCreationStaysUnknownAndIncompleteBaselineDoesNotDispatch() {
        HostFixture ambiguous = new HostFixture();
        TrackLifecycleModule module = lifecycle(ambiguous);
        ambiguous.create = factory -> {
            ambiguous.addTrack(2, HostFixture.C, "Audio");
            ambiguous.addTrack(3, "extra-track", "Audio");
        };
        JsonObject result = ambiguous.call(module, "tracks.insert", create("audio", -1));
        assertTrue(
                result.get("outcome").getAsString().equals("unknown")
                        && !result.get("nameApplied").getAsBoolean(),
                "multiple additions never claim creation completion");
        assertTrue(
                ambiguous.tracks[2].child("name").writes == 0 && ambiguous.tracks[3].child("name").writes == 0,
                "ambiguous tracks never renamed");
        HostFixture outside = new HostFixture();
        TrackLifecycleModule other = lifecycle(outside);
        outside.bank.child("itemCount").emit(HostLimits.TRACKS + 4);
        HostOperation rejected = outside.submit(other, "tracks.insert", create("effect", -1));
        outside.next();
        assertTrue(
                !assertRpcError("not_ready", rejected).unknown, "incomplete16-track baseline rejects before mutation");
        assertTrue(
                outside.application.actions == 0 && !rejected.dispatched(),
                "known unobservable baseline must never invoke a factory");
    }

    @Test
    void groupedBaselineRejectsWithoutMutationOrQuarantine() {
        for (String type : List.of("audio", "instrument", "effect")) {
            HostFixture f = new HostFixture();
            TrackLifecycleModule module = lifecycle(f);
            f.tracks[0].child("trackType").emit("Group");
            JsonObject beforeA = read(f, ref(f, HostFixture.A));
            JsonObject beforeB = read(f, ref(f, HostFixture.B));
            Object count = f.bank.child("itemCount").data;
            String hostType = Character.toUpperCase(type.charAt(0)) + type.substring(1);
            f.create = factory -> f.addTrack(2, HostFixture.C, hostType);
            for (int position : new int[] {-1, 0}) {
                HostOperation rejected = f.submit(module, "tracks.insert", create(type, position));
                f.next();
                assertTrue(
                        !assertRpcError("unsupported_scope", rejected).unknown,
                        "known grouped baseline explicitly rejects as not applied");
                assertTrue(
                        !rejected.dispatched() && f.application.actions == 0,
                        "known grouped baseline never dispatches any native track factory");
                assertTrue(
                        count.equals(f.bank.child("itemCount").data)
                                && Boolean.FALSE.equals(f.tracks[2].child("exists").data),
                        "rejected grouped creation leaves project track count and empty slot unchanged");
                assertTrue(
                        beforeA.equals(read(f, ref(f, HostFixture.A)))
                                && beforeB.equals(read(f, ref(f, HostFixture.B))),
                        "rejected grouped creation preserves existing track snapshots");
                for (FakeHost track : f.tracks)
                    assertTrue(track.child("name").writes == 0, "rejected grouped creation never renames any track");
                assertTrue(f.tasks.isEmpty(), "rejection leaves no creation observation task pending");
            }
            f.tracks[0].child("trackType").emit("Instrument");
            HostOperation admitted = f.submit(module, "tracks.insert", create(type, -1));
            f.next();
            f.next();
            assertTrue(
                    admitted.result.join().get("outcome").getAsString().matches("applied|already_matched")
                            && f.application.actions == 1,
                    "grouped pre-dispatch rejection does not quarantine later supported creation");
            assertTrue(
                    f.tracks[2].child("name").writes == 1, "later ungrouped creation names only the observed addition");
        }
    }

    @Test
    void concurrentGroupAppearanceRemainsUnknownAndQuarantined() {
        HostFixture f = new HostFixture();
        TrackLifecycleModule module = lifecycle(f);
        f.create = factory -> {
            f.tracks[0].child("trackType").emit("Group");
            f.addTrack(2, HostFixture.C, "Audio");
        };
        HostOperation op = f.submit(module, "tracks.insert", create("audio", -1));
        f.next();
        JsonObject result = op.result.join();
        assertTrue(
                op.dispatched() && f.application.actions == 1, "concurrent grouping occurs after one factory dispatch");
        assertTrue(
                result.get("outcome").getAsString().equals("unknown")
                        && result.get("reason").getAsString().equals("side_effect")
                        && result.get("detail").getAsString().equals("group_appeared_during_insertion"),
                "group appearing after dispatch retains structural unknown attribution");
        assertTrue(
                !result.get("nameApplied").getAsBoolean()
                        && !result.has("ref")
                        && f.tracks[2].child("name").writes == 0,
                "concurrent grouped insertion is never attributed or renamed");
        f.tracks[0].child("trackType").emit("Instrument");
        HostOperation later = f.submit(module, "tracks.insert", create("audio", -1));
        f.next();
        assertTrue(
                !assertRpcError("unresolved", later).unknown && !later.dispatched() && f.application.actions == 1,
                "concurrent structural unknown retains quarantine even when the group disappears");
    }

    @Test
    void wrongTypeOrPositionCannotBeNamed() {
        for (boolean wrongType : new boolean[] {true, false}) {
            HostFixture f = new HostFixture();
            TrackLifecycleModule module = lifecycle(f);
            f.create = factory -> f.addTrack(2, HostFixture.C, wrongType ? "Instrument" : "Audio");
            HostOperation op = f.submit(module, "tracks.insert", create("audio", wrongType ? -1 : 0));
            f.next();
            f.next();
            assertTrue(
                    !op.result.isDone() && f.tracks[2].child("name").writes == 0,
                    "type/position mismatch is not a nameable creation");
            f.services.scheduler.close();
            f.next();
            assertTrue(assertUnknown("not_ready", op) != null, "abandoned factory remains unknown, not not_applied");
        }
    }

    @Test
    void creationNeverNamesAfterCancellation() {
        HostFixture f = new HostFixture();
        TrackLifecycleModule module = lifecycle(f);
        f.create = factory -> f.addTrack(2, HostFixture.C, "Audio");
        HostOperation op = f.submit(module, "tracks.insert", create("audio", -1));
        f.next();
        op.result.cancel(false);
        f.next();
        assertTrue(
                f.tracks[2].child("name").writes == 0 && f.application.actions == 1,
                "late observation after cancelled creation cannot rename or retry");
    }

    @Test
    void unresolvedFactoryQuarantinesLaterCreation() {
        HostFixture f = new HostFixture();
        TrackLifecycleModule module = lifecycle(f);
        HostOperation a = f.submit(module, "tracks.insert", create("audio", -1));
        f.next();
        a.result.cancel(false);
        f.next();
        JsonObject requestB = create("audio", -1);
        requestB.addProperty("name", "Must not name A");
        HostOperation b = f.submit(module, "tracks.insert", requestB);
        f.next();
        assertTrue(
                !assertRpcError("unresolved", b).unknown, "cancelled RPC does not release native factory attribution");
        f.addTrack(2, HostFixture.C, "Audio");
        assertTrue(
                f.application.actions == 1 && f.tracks[2].child("name").writes == 0,
                "late A addition can never be attributed or named by B");
        HostOperation c = f.submit(module, "tracks.insert", requestB);
        f.next();
        assertRpcError("unresolved", c);
        assertTrue(f.application.actions == 1, "late observation alone does not silently release quarantine");
        HostFixture unknown = new HostFixture();
        TrackLifecycleModule second = lifecycle(unknown);
        unknown.create = factory -> {
            unknown.addTrack(2, HostFixture.C, "Audio");
            unknown.addTrack(3, "extra-track", "Audio");
        };
        JsonObject dispatched = unknown.call(second, "tracks.insert", create("audio", -1));
        assertTrue(
                dispatched.get("outcome").getAsString().equals("unknown"),
                "ambiguous post-dispatch observation produces completed unknown result");
        HostOperation next = unknown.submit(second, "tracks.insert", create("audio", -1));
        unknown.next();
        assertRpcError("unresolved", next);
        assertTrue(unknown.application.actions == 1, "completed unknown result also retains quarantine");
    }

    @Test
    void placeholderStartupPreservesNativeStyleBaseline() {
        for (boolean existenceFirst : new boolean[] {true, false}) {
            HostFixture f = new HostFixture();
            String[] ids = {HostFixture.A, HostFixture.B, HostFixture.C, "00000000-0000-0000-0000-000000000004"};
            String[] types = {"Instrument", "Audio", "Effect", "Master"};
            for (int i = 0; i < ids.length; i++) {
                f.tracks[i].child("channelId").data = "";
                f.tracks[i].child("exists").data = false;
                f.tracks[i].child("trackType").data = types[i];
            }
            f.bank.child("itemCount").emit(4);
            TrackLifecycleModule module = lifecycle(f);
            // Native failure shape: complete4-track window includes Effect and Master.
            // Real type/position arrive before first nonempty IDs, with no replay afterward.
            for (int i = 0; i < ids.length; i++) {
                f.tracks[i].child("trackType").emit(types[i]);
                f.tracks[i].child("position").emit(i);
                if (existenceFirst) f.tracks[i].child("exists").emit(true);
                f.tracks[i].child("channelId").emit(ids[i]);
                if (!existenceFirst) f.tracks[i].child("exists").emit(true);
            }
            JsonObject read = read(f, ref(f, ids[3]));
            assertTrue(
                    read.get("type").getAsString().equals("Master")
                            && read.get("index").getAsInt() == 3,
                    "placeholder-to-first-identity preserves type/index without replay");
            f.create = factory -> {
                f.tracks[2].child("position").emit(3);
                f.tracks[3].child("position").emit(4);
                f.addTrack(4, "00000000-0000-0000-0000-000000000005", "Instrument");
                f.tracks[4].child("position").emit(2);
            };
            HostOperation created = f.submit(module, "tracks.insert", create("instrument", -1));
            f.next();
            f.next();
            JsonObject result = created.result.join();
            assertTrue(
                    result.get("outcome").getAsString().matches("applied|already_matched")
                            && f.application.actions == 1,
                    "complete native-style baseline supports one instrument factory and observed name");
            assertTrue(
                    result.getAsJsonObject("insertion").get("observedPosition").getAsInt() == 2,
                    "main insertion ordinal excludes effect/master but full UUID baseline includes both");
        }
    }

    @Test
    void missingCreationMetadataRejectsBeforeFactory() {
        HostFixture f = new HostFixture();
        f.tracks[1].child("trackType").notifyInitial = false;
        TrackLifecycleModule module = lifecycle(f);
        HostOperation rejected = f.submit(module, "tracks.insert", create("audio", -1));
        f.next();
        assertTrue(
                !assertRpcError("not_ready", rejected).unknown,
                "missing type baseline rejects before dispatch despite complete track count");
        assertTrue(
                f.application.actions == 0 && !rejected.dispatched(),
                "no unnamed native creation on known incomplete readiness");
        f.tracks[1].child("trackType").emit("Audio");
        f.create = factory -> f.addTrack(2, HostFixture.C, "Audio");
        HostOperation created = f.submit(module, "tracks.insert", create("audio", -1));
        f.next();
        f.next();
        assertTrue(
                created.result.join().get("outcome").getAsString().matches("applied|already_matched"),
                "pre-dispatch rejection does not quarantine a later fully observed creation");
    }

    @Test
    void creationSurvivesNativeBankShiftWithoutValueReplay() {
        HostFixture f = new HostFixture();
        String master = "00000000-0000-0000-0000-000000000004";
        String added = "00000000-0000-0000-0000-000000000005";
        f.addTrack(2, HostFixture.C, "Effect");
        f.addTrack(3, master, "Master");
        TrackLifecycleModule module = lifecycle(f);
        f.create = factory -> {
            // Existing slot 2 becomes the new main track. New fields can precede its UUID.
            f.tracks[2].child("name").emit("Created");
            f.tracks[2].child("pan").child("value").emit(.62);
            f.tracks[2].child("channelId").emit(added);
            f.tracks[2].child("trackType").emit("Instrument");
            // The Effect shifts onto the previous Master proxy, with position 3 unchanged.
            f.tracks[3].child("name").emit("Shifted Effect");
            f.tracks[3].child("channelId").emit(HostFixture.C);
            f.tracks[3].child("trackType").emit("Effect");
            f.tracks[2].child("volume").child("displayedValue").data = "-9 dB";
            f.addTrack(4, master, "Master");
        };
        HostOperation op = f.submit(module, "tracks.insert", create("instrument", -1));
        f.next();
        assertTrue(
                !op.result.isDone() && f.application.actions == 1,
                "void factory waits for rebound proxy readiness without retry");
        mix(f).flush();
        f.next();
        f.next();
        assertTrue(
                op.result.isDone(),
                "unchanged slot positions no longer permanently block complete factory observation");
        JsonObject result = op.result.join();
        assertTrue(
                result.get("outcome").getAsString().matches("applied|already_matched")
                        && id(result).equals(added),
                "unique factory addition names the correct UUID after native-like callback ordering");
        JsonObject created = read(f, result.getAsJsonObject("ref"));
        assertTrue(
                created.get("index").getAsInt() == 2
                        && created.get("type").getAsString().equals("Instrument")
                        && created.get("name").getAsString().equals("Created"),
                "the returned ref reads back the correct type, position and name");
        assertTrue(
                created.getAsJsonObject("pan").get("value").getAsDouble() == .62
                        && created.getAsJsonObject("volume")
                                .get("display")
                                .getAsString()
                                .equals("-9 dB"),
                "early pan survives rebind and metadata is refreshed from current getters, not predecessor cache");
        assertTrue(
                f.tracks[2].child("name").writes == 1
                        && result.getAsJsonObject("fields")
                                .getAsJsonObject("name")
                                .get("outcome")
                                .getAsString()
                                .equals("applied"),
                "early equal name is readable but cannot suppress the new generation's explicit naming write");
        JsonObject effect = read(f, ref(f, HostFixture.C));
        assertTrue(
                effect.get("name").getAsString().equals("Shifted Effect")
                        && effect.get("index").getAsInt() == 3,
                "shifted preexisting track recovers early name and unchanged position without replay");
        assertTrue(
                f.tracks[3].child("name").writes == 0 && f.tracks[4].child("name").writes == 0,
                "factory recovery never names shifted Effect or Master");
    }

    static final String ROOT = "00000000-0000-0000-0000-0000000000ff", G = "00000000-0000-0000-0000-000000000004";

    @Test
    void deletingAGroupRemovesItWithEveryTrackInside() {
        HostFixture f = new HostFixture();
        TrackLifecycleModule module = lifecycle(f);
        f.addTrack(2, HostFixture.C, "Audio");
        group(f, 0, HostFixture.A, 0);
        parent(f, 0, ROOT);
        parent(f, 1, HostFixture.A);
        parent(f, 2, ROOT);
        f.tracks[0].child("isGroupExpanded").data = false;
        HostOperation collapsed = f.submit(module, "tracks.delete", delete(HostFixture.A, "Track 0"));
        f.next();
        RpcException hidden = assertRpcError("not_ready", collapsed);
        assertTrue(
                hidden.getMessage().contains("collapsed") && f.tracks[0].actions == 0,
                "a collapsed group hides its tracks, so it is not deleted blind");
        f.tracks[0].child("isGroupExpanded").data = true;
        f.delete = slot -> {
            f.removeTrack(1, 2);
            f.tracks[2].child("position").emit(0);
            f.removeTrack(0, 1);
        };
        HostOperation op = f.submit(module, "tracks.delete", delete(HostFixture.A, "Track 0"));
        while (!op.result.isDone()) f.next();
        JsonObject result = op.result.join();
        assertEquals("applied", result.get("outcome").getAsString(), result.toString());
        assertEquals(1, f.tracks[0].actions, "only the group itself is deleted; its children go with it");
        assertEquals(
                HostFixture.B,
                result.getAsJsonArray("deletedChildren")
                        .get(0)
                        .getAsJsonObject()
                        .get("id")
                        .getAsString());
        assertEquals(1, result.getAsJsonArray("deletedChildren").size());
        assertEquals(3, result.get("trackCountBefore").getAsInt());
        assertEquals(1, result.get("trackCountAfter").getAsInt());

        HostFixture partial = new HostFixture();
        group(partial, 0, HostFixture.A, 0);
        partial.tracks[0].child("isGroupExpanded").data = true;
        parent(partial, 0, ROOT);
        parent(partial, 1, HostFixture.A);
        partial.delete = slot -> partial.removeTrack(0, 1);
        HostOperation childLeft = partial.submit(lifecycle(partial), "tracks.delete", delete(HostFixture.A, "Track 0"));
        while (!childLeft.result.isDone()) partial.next();
        assertEquals(
                "unknown",
                childLeft.result.join().get("outcome").getAsString(),
                "a child still in the bank is not the deletion of the group");
    }

    private static void duplicable(HostFixture f, Runnable effect) {
        f.tracks[1].on("duplicate", args -> {
            f.mutation();
            f.tracks[1].actions++;
            effect.run();
            return null;
        });
    }

    @Test
    void duplicateAttributesTheCopyRightAfterTheSource() {
        HostFixture f = new HostFixture();
        TrackLifecycleModule module = lifecycle(f);
        duplicable(f, () -> f.addTrack(2, HostFixture.C, "Audio"));
        HostOperation op = f.submit(module, "tracks.duplicate", HostFixture.targeting(HostFixture.B));
        while (!op.result.isDone()) f.next();
        JsonObject result = op.result.join();
        assertEquals("applied", result.get("outcome").getAsString(), result.toString());
        assertEquals("api_readback", result.get("verification").getAsString());
        assertEquals(HostFixture.C, id(result), "the copy is the one new track");
        assertEquals(HostFixture.B, result.getAsJsonObject("source").get("id").getAsString());
        assertEquals(0, result.get("deviceCount").getAsInt());
        assertEquals(1, f.tracks[1].actions);
        assertTrue(f.services.holds.list().isEmpty(), "an attributed copy releases its hold");

        HostFixture wrongType = new HostFixture();
        duplicable(wrongType, () -> wrongType.addTrack(2, HostFixture.C, "Instrument"));
        HostOperation other = wrongType.submit(
                lifecycle(wrongType),
                "tracks.duplicate",
                HostFixture.targeting(HostFixture.B),
                wrongType.tasks.after(400));
        while (!other.result.isDone()) wrongType.next();
        JsonObject unknown = other.result.join();
        assertEquals("unknown", unknown.get("outcome").getAsString(), "a new track of another type is not the copy");
        assertFalse(unknown.has("nameApplied"), "a duplicate names nothing");
        assertFalse(wrongType.services.holds.list().isEmpty(), "an unattributed copy holds further track creation");
        HostFixture elsewhere = new HostFixture();
        elsewhere.addTrack(2, HostFixture.C, "Audio");
        duplicable(elsewhere, () -> elsewhere.addTrack(3, G, "Audio"));
        HostOperation far = elsewhere.submit(
                lifecycle(elsewhere),
                "tracks.duplicate",
                HostFixture.targeting(HostFixture.B),
                elsewhere.tasks.after(400));
        while (!far.result.isDone()) elsewhere.next();
        assertEquals(
                "unknown", far.result.join().get("outcome").getAsString(), "a new track not right after the source");
        HostOperation insert = wrongType.submit(lifecycle(wrongType), "tracks.insert", create("audio", -1));
        wrongType.next();
        assertRpcError("unresolved", insert);
    }

    @Test
    void duplicateRefusesGroupsMastersAndRecordingAndAmbiguousCopies() {
        HostFixture f = new HostFixture();
        TrackLifecycleModule module = lifecycle(f);
        duplicable(f, () -> {});
        f.tracks[1].child("trackType").emit("Master");
        HostOperation master = f.submit(module, "tracks.duplicate", HostFixture.targeting(HostFixture.B));
        f.next();
        assertTrue(!assertRpcError("unsupported_scope", master).unknown && f.tracks[1].actions == 0);
        f.tracks[1].child("trackType").emit("Audio");
        f.transport.child("isArrangerRecordEnabled").emit(true);
        HostOperation recording = f.submit(module, "tracks.duplicate", HostFixture.targeting(HostFixture.B));
        f.next();
        assertTrue(!assertRpcError("unsafe_state", recording).unknown && f.tracks[1].actions == 0);

        HostFixture twice = new HostFixture();
        duplicable(twice, () -> {
            twice.addTrack(2, HostFixture.C, "Audio");
            twice.addTrack(3, G, "Audio");
        });
        HostOperation op = twice.submit(lifecycle(twice), "tracks.duplicate", HostFixture.targeting(HostFixture.B));
        while (!op.result.isDone()) twice.next();
        assertEquals("side_effect", op.result.join().get("reason").getAsString(), "two new tracks are ambiguous");
    }

    private static void parent(HostFixture f, int slot, String id) {
        FakeHost parent = f.tracks[slot].child("createParentTrack");
        parent.child("exists").data = id != null;
        parent.child("channelId").data = id == null ? "" : id;
    }

    private static void group(HostFixture f, int slot, String id, int position) {
        FakeHost track = f.tracks[slot];
        track.child("channelId").emit(id);
        track.child("exists").emit(true);
        track.child("trackType").emit("Group");
        track.child("isGroup").data = true;
        track.child("position").emit(position);
    }

    private static HostOperation groupedCreation(HostFixture f, String sibling, String createdParent, int position) {
        TrackLifecycleModule module = lifecycle(f);
        f.tracks[0].child("trackType").emit("Group");
        f.tracks[0].child("isGroup").data = true;
        parent(f, 0, ROOT);
        parent(f, 1, sibling);
        f.create = factory -> {
            f.addTrack(2, HostFixture.C, "Audio");
            parent(f, 2, createdParent);
        };
        return f.submit(module, "tracks.insert", create("audio", position));
    }

    @Test
    void groupedCreationAttributesTopLevelIndex() {
        for (int position : new int[] {-1, 1}) {
            HostFixture f = new HostFixture();
            HostOperation op = groupedCreation(f, HostFixture.A, ROOT, position);
            f.next();
            f.next();
            JsonObject result = op.result.join();
            assertTrue(
                    result.get("outcome").getAsString().matches("applied|already_matched")
                            && id(result).equals(HostFixture.C),
                    "grouped project: the top-level index counts the group, not its child");
            assertTrue(
                    result.getAsJsonObject("insertion")
                            .get("parentScope")
                            .getAsString()
                            .equals("top_level_of_grouped_project"),
                    "grouped creation declares its top-level scope");
            assertTrue(
                    f.tracks[2].child("name").writes == 1 && f.application.actions == 1,
                    "only the observed top-level addition is named");
        }
        HostFixture f = new HostFixture();
        HostOperation op = groupedCreation(f, HostFixture.A, ROOT, 0);
        f.next();
        f.next();
        f.next();
        assertTrue(
                !op.result.isDone() && f.tracks[2].child("name").writes == 0,
                "a top-level track at the wrong index is never named");
        f.services.scheduler.close();
        f.next();
        assertTrue(assertUnknown("not_ready", op) != null, "unattributed grouped creation stays unknown");
    }

    private static JsonObject insertAt(String type, String position) {
        JsonObject p = create(type, -1);
        p.add("position", HostFixture.json(position));
        return p;
    }

    private static String after(JsonObject ref) {
        return "{\"after\":" + ref + "}";
    }

    private static RpcException refuse(HostFixture f, TrackLifecycleModule module, String reason, JsonObject params) {
        HostOperation op = f.submit(module, "tracks.insert", params);
        f.next();
        return assertRpcError(reason, op);
    }

    @Test
    void anchorsAreTopLevelOrdinalsOfTheNewTracksOwnList() {
        HostFixture f = new HostFixture();
        String master = "00000000-0000-0000-0000-000000000004", fx2 = "00000000-0000-0000-0000-000000000005";
        f.addTrack(2, HostFixture.C, "Effect");
        f.addTrack(3, fx2, "Effect");
        f.addTrack(4, master, "Master");
        TrackLifecycleModule module = lifecycle(f);
        HostOperation effect = f.submit(module, "tracks.insert", insertAt("effect", after(ref(f, HostFixture.C))));
        f.next();
        assertEquals(1, f.createPosition, "after the first effect track is index 1 of the effect list");
        assertEquals(1, f.application.actions);
        effect.result.cancel(false);
        HostFixture g = new HostFixture();
        g.addTrack(2, HostFixture.C, "Effect");
        TrackLifecycleModule other = lifecycle(g);
        refuse(g, other, "kind_mismatch", insertAt("effect", after(ref(g, HostFixture.A))));
        refuse(g, other, "kind_mismatch", insertAt("audio", after(ref(g, HostFixture.C))));
        assertEquals(0, g.application.actions, "a refused anchor never reaches the factory");
    }

    @Test
    void masterIsNotAnAnchorAndAGroupCountsOnceWithItsChildren() {
        HostFixture f = new HostFixture();
        String master = "00000000-0000-0000-0000-000000000004";
        f.addTrack(2, master, "Master");
        TrackLifecycleModule module = lifecycle(f);
        RpcException refused = refuse(f, module, "unsupported_scope", insertAt("audio", after(ref(f, master))));
        assertTrue(refused.getMessage().contains("master"), refused.getMessage());
        HostFixture g = new HostFixture();
        g.tracks[0].child("trackType").emit("Group");
        g.tracks[0].child("isGroup").data = true;
        parent(g, 0, ROOT);
        parent(g, 1, HostFixture.A);
        TrackLifecycleModule grouped = lifecycle(g);
        g.submit(grouped, "tracks.insert", insertAt("audio", after(ref(g, HostFixture.A))));
        g.next();
        assertEquals(1, g.createPosition, "after the group is after the group and its children");
        HostFixture h = new HostFixture();
        h.tracks[0].child("trackType").emit("Group");
        h.tracks[0].child("isGroup").data = true;
        parent(h, 0, ROOT);
        parent(h, 1, HostFixture.A);
        TrackLifecycleModule inside = lifecycle(h);
        refuse(h, inside, "unsupported_scope", insertAt("audio", after(ref(h, HostFixture.B))));
        assertEquals(0, h.application.actions);
    }

    @Test
    void groupedCreationInsideAGroupIsNeverNamed() {
        HostFixture f = new HostFixture();
        HostOperation op = groupedCreation(f, ROOT, HostFixture.A, -1);
        f.next();
        f.next();
        f.next();
        assertTrue(
                !op.result.isDone() && f.tracks[2].child("name").writes == 0,
                "a new track observed inside a group is not the requested top-level track");
        f.services.scheduler.close();
        f.next();
        assertTrue(assertUnknown("not_ready", op) != null, "it remains an unknown creation");
    }

    @Test
    void trackTreeResolvesParentsAndRejectsInconsistentOrder() {
        HostFixture f = new HostFixture();
        TrackTree flat = TrackTree.capture(f.services.window);
        assertTrue(
                flat.complete
                        && !flat.groups
                        && flat.byId.get(HostFixture.B).topLevel
                        && flat.byId.get(HostFixture.B).depth == 0,
                "without groups every track is top-level and no parent observation is needed");
        f.tracks[0].child("trackType").emit("Group");
        f.tracks[0].child("isGroup").data = true;
        TrackTree unobserved = TrackTree.capture(f.services.window);
        assertTrue(
                !unobserved.complete && "parent_observation_missing".equals(unobserved.reason),
                "groups without parent observations are incomplete");
        parent(f, 0, ROOT);
        parent(f, 1, HostFixture.A);
        TrackTree tree = TrackTree.capture(f.services.window);
        TrackTree.Node child = tree.byId.get(HostFixture.B);
        assertTrue(
                tree.complete && HostFixture.A.equals(child.parentId) && child.depth == 1 && !child.topLevel,
                "child resolves to its group");
        JsonObject row = new JsonObject();
        tree.describe(row, child, f.services.trackRefs::ref);
        assertTrue(
                row.getAsJsonObject("parent").equals(f.services.trackRefs.ref(0))
                        && row.get("depth").getAsInt() == 1,
                "tracks.list row carries the group's ref and the depth");
        JsonObject groupRow = new JsonObject();
        tree.describe(groupRow, tree.byId.get(HostFixture.A), f.services.trackRefs::ref);
        assertTrue(
                groupRow.get("isGroup").getAsBoolean()
                        && groupRow.get("parent").isJsonNull()
                        && groupRow.get("depth").getAsInt() == 0,
                "group row is top-level");
        f.addTrack(2, HostFixture.C, "Audio");
        parent(f, 2, HostFixture.B);
        TrackTree bad = TrackTree.capture(f.services.window);
        assertTrue(
                !bad.complete && "parent_not_a_group_in_window".equals(bad.reason),
                "a parent that is not a group in the window fails closed");
        parent(f, 2, ROOT);
        f.tracks[2].child("position").emit(1);
        f.tracks[1].child("position").emit(2);
        TrackTree order = TrackTree.capture(f.services.window);
        assertTrue(
                !order.complete && "parent_order_inconsistent".equals(order.reason),
                "a top-level track between a group and its child breaks pre-order");
    }

    static final String D = "00000000-0000-0000-0000-000000000005",
            E = "00000000-0000-0000-0000-000000000006",
            FX = "00000000-0000-0000-0000-000000000007",
            MASTER = "00000000-0000-0000-0000-000000000008",
            ELSEWHERE = "00000000-0000-0000-0000-0000000000ee";
    /**
     * The 6.1.4 native observation after tracks.group (0fcbad3): three top-level tracks, the new group, its two
     * children (parent proxy = the group), an effect track and Master. Only the group's own parent proxy is odd.
     */
    private static HostFixture observedGroupLayout(String groupParent) {
        HostFixture f = new HostFixture();
        f.tracks[0].child("trackType").emit("Instrument");
        parent(f, 0, ROOT);
        parent(f, 1, ROOT);
        f.addTrack(2, HostFixture.C, "Instrument");
        parent(f, 2, ROOT);
        group(f, 3, G, 3);
        parent(f, 3, groupParent);
        f.addTrack(4, D, "Audio");
        parent(f, 4, G);
        f.addTrack(5, E, "Audio");
        parent(f, 5, G);
        f.addTrack(6, FX, "Effect");
        parent(f, 6, ROOT);
        f.addTrack(7, MASTER, "Master");
        parent(f, 7, null);
        return f;
    }

    @Test
    void trackTreeInfersGroupWhoseParentProxyIsUninformative() {
        for (String raw : new String[] {G, ELSEWHERE}) {
            HostFixture layout = observedGroupLayout(raw);
            TrackTree tree = TrackTree.capture(layout.services.window);
            TrackTree.Node group = tree.byId.get(G);
            assertTrue(
                    tree.complete && group.topLevel && group.depth == 0 && group.parentInferred,
                    "top-level group resolves from pre-order: " + raw);
            assertTrue(
                    tree.byId.get(D).depth == 1 && G.equals(tree.byId.get(E).parentId) && tree.byId.get(FX).depth == 0,
                    "children and effect track resolve");
            JsonObject row = new JsonObject();
            tree.describe(row, group, layout.services.trackRefs::ref);
            assertTrue(
                    row.get("parent").isJsonNull()
                            && row.get("depth").getAsInt() == 0
                            && row.get("parentResolved").getAsBoolean()
                            && row.get("parentSource").getAsString().equals("preorder_inference")
                            && row.get("parentObserved").getAsString().equals(raw),
                    "tracks.list row shows depth 0, the inference and the raw proxy value");
            JsonObject child = new JsonObject();
            tree.describe(child, tree.byId.get(D), layout.services.trackRefs::ref);
            assertTrue(
                    !child.has("parentSource") && !child.has("parentObserved"),
                    "observed parents carry no inference fields");
        }
        // Group first in the window and naming itself: the root comes from the first top-level non-group track.
        HostFixture first = new HostFixture();
        group(first, 0, G, 0);
        parent(first, 0, G);
        first.addTrack(1, D, "Audio");
        parent(first, 1, G);
        first.tracks[1].child("position").emit(1);
        first.addTrack(2, HostFixture.C, "Audio");
        parent(first, 2, ROOT);
        TrackTree leading = TrackTree.capture(first.services.window);
        assertTrue(
                leading.complete
                        && leading.byId.get(G).topLevel
                        && G.equals(leading.byId.get(D).parentId)
                        && leading.byId.get(HostFixture.C).topLevel,
                "a leading self-reporting group never becomes the root of its own children");
        // Leading group naming an id outside the window: the root still comes from the later top-level track.
        HostFixture foreign = new HostFixture();
        group(foreign, 0, G, 0);
        parent(foreign, 0, ELSEWHERE);
        foreign.addTrack(1, D, "Audio");
        parent(foreign, 1, G);
        foreign.addTrack(2, E, "Audio");
        parent(foreign, 2, G);
        foreign.addTrack(3, HostFixture.C, "Audio");
        parent(foreign, 3, ROOT);
        TrackTree outside = TrackTree.capture(foreign.services.window);
        assertTrue(
                outside.complete
                        && outside.byId.get(G).depth == 0
                        && outside.byId.get(HostFixture.C).topLevel
                        && outside.byId.get(E).depth == 1,
                "a leading group naming a foreign id never becomes the root");
        // Two groups sharing that odd value and no top-level non-group track: the nested group is not made top-level.
        HostFixture odd = new HostFixture();
        group(odd, 0, G, 0);
        parent(odd, 0, ELSEWHERE);
        group(odd, 1, D, 1);
        parent(odd, 1, ELSEWHERE);
        odd.addTrack(2, E, "Audio");
        parent(odd, 2, D);
        TrackTree noRoot = TrackTree.capture(odd.services.window);
        assertTrue(
                !noRoot.complete && "group_parent_ambiguous".equals(noRoot.reason) && !noRoot.byId.get(D).topLevel,
                "without a root observation a group after a group is never guessed top-level");
        // After another group's child, the group could be that child's sibling or top-level: never guessed.
        HostFixture nested = observedGroupLayout(G);
        group(nested, 2, HostFixture.C, 2);
        parent(nested, 2, ROOT);
        nested.tracks[3].child("position").emit(4);
        nested.tracks[4].child("position").emit(3);
        parent(nested, 4, HostFixture.C);
        TrackTree ambiguous = TrackTree.capture(nested.services.window);
        assertTrue(
                !ambiguous.complete
                        && "group_parent_ambiguous".equals(ambiguous.reason)
                        && ambiguous.byId.get(G).depth < 0,
                "ambiguous group parent fails closed");
        // A group naming a non-group track in the window is inconsistent, not inferred.
        TrackTree wrong = TrackTree.capture(observedGroupLayout(HostFixture.C).services.window);
        assertTrue(
                !wrong.complete && "parent_not_a_group_in_window".equals(wrong.reason),
                "a group whose parent is a plain track fails closed");
        TrackTree missing = TrackTree.capture(observedGroupLayout(null).services.window);
        assertTrue(
                !missing.complete && "parent_observation_missing".equals(missing.reason),
                "a missing group parent observation fails closed");
    }

    @Test
    void groupObservedWhenTheGroupParentProxyNamesItself() {
        HostFixture f = new HostFixture();
        FakeGroup access = new FakeGroup();
        TrackLifecycleModule module = lifecycle(f, access);
        f.tracks[0].child("trackType").emit("Instrument");
        f.addTrack(2, D, "Audio");
        f.addTrack(3, E, "Audio");
        f.addTrack(4, FX, "Effect");
        f.addTrack(5, MASTER, "Master");
        access.effect = () -> {
            // Native shape: the group takes the first child's slot, the children shift down, Master moves out of slot
            // 5.
            group(f, 2, G, 2);
            parent(f, 2, G);
            f.addTrack(3, D, "Audio");
            f.addTrack(4, E, "Audio");
            f.addTrack(5, FX, "Effect");
            f.addTrack(6, MASTER, "Master");
            parent(f, 0, ROOT);
            parent(f, 1, ROOT);
            parent(f, 3, G);
            parent(f, 4, G);
            parent(f, 5, ROOT);
            parent(f, 6, null);
        };
        access.rename = name -> f.tracks[2].child("name").emit(name);
        JsonObject p = groupRequest(f, D, E);
        p.addProperty("name", "T: Grp");
        HostOperation op = f.submit(module, "tracks.group", p);
        while (!op.result.isDone() && !f.tasks.isEmpty()) {
            mix(f).flush();
            f.next();
        }
        JsonObject result = op.result.join();
        assertTrue(
                result.get("outcome").getAsString().matches("applied|already_matched")
                        && id(result).equals(G)
                        && f.tracks[2].child("name").data.equals("T: Grp"),
                "observed native grouping succeeds and names the group");
        assertTrue(f.services.holds.list().isEmpty(), "no quarantine after the observed grouping");
    }

    /** The ref of the track with this UUID as the fixture now has it; an absent track is named "x". */
    private static JsonObject ref(HostFixture f, String id) {
        for (FakeHost track : f.tracks)
            if (id.equals(track.child("channelId").data)) return Refs.track(id, (String) track.child("name").data);
        return Refs.track(id, "x");
    }

    private static JsonObject groupRequest(HostFixture f, String... ids) {
        JsonObject p = new JsonObject();
        JsonArray a = new JsonArray();
        for (String id : ids) a.add(ref(f, id));
        p.add("targets", a);
        return p;
    }

    static final class FakeGroup implements TrackLifecycleModule.GroupAccess {
        Runnable effect = () -> {};
        java.util.function.Consumer<String> rename = name -> {};
        int calls;
        List<String> ids;
        String name;

        public void execute(Runnable work) {
            work.run();
        }

        public JsonObject group(List<Object> proxies, List<String> ids, String name, Runnable check, Runnable before) {
            calls++;
            this.ids = ids;
            this.name = name;
            check.run();
            before.run();
            effect.run();
            if (name != null) rename.accept(name);
            JsonObject out = new JsonObject();
            out.addProperty("nativeOperation", "track_group.group_tracks");
            return out;
        }
    }

    @Test
    void groupVerifiesMembershipAndNamesTheGroup() {
        HostFixture f = new HostFixture();
        FakeGroup access = new FakeGroup();
        TrackLifecycleModule module = lifecycle(f, access);
        access.effect = () -> {
            group(f, 2, G, 0);
            f.tracks[0].child("position").emit(1);
            f.tracks[1].child("position").emit(2);
            f.bank.child("itemCount").emit(3);
            parent(f, 2, ROOT);
            parent(f, 0, G);
            parent(f, 1, G);
        };
        access.rename = name -> f.tracks[2].child("name").emit(name);
        JsonObject p = groupRequest(f, HostFixture.B, HostFixture.A);
        p.addProperty("name", "Drums");
        HostOperation op = f.submit(module, "tracks.group", p);
        f.next();
        f.next();
        assertTrue(!op.result.isDone(), "one membership observation is not enough");
        f.next();
        JsonObject result = op.result.join();
        assertTrue(
                access.ids.equals(List.of(HostFixture.A, HostFixture.B)),
                "tracks are passed in flat order, not request order");
        assertTrue(
                id(result).equals(G) && result.get("outcome").getAsString().matches("applied|already_matched"),
                "the new group is identified and named");
        JsonObject info = result.getAsJsonObject("group");
        assertTrue(
                result.get("verification").getAsString().equals("api_readback")
                        && info.getAsJsonArray("children").size() == 2
                        && info.get("parent").isJsonNull(),
                "group evidence lists children and parent");
        assertTrue(
                "Drums".equals(access.name)
                        && f.tracks[2].child("name").data.equals("Drums")
                        && f.tracks[2].child("name").writes == 0,
                "the name goes into the native grouping transaction, not a second public write (one Undo step)");
        assertEquals(
                "applied",
                result.getAsJsonObject("fields")
                        .getAsJsonObject("name")
                        .get("outcome")
                        .getAsString(),
                "the observed name is read back");
        assertTrue(f.services.holds.list().isEmpty(), "observed grouping releases its quarantine");
        HostFixture plain = new HostFixture();
        FakeGroup quiet = new FakeGroup();
        TrackLifecycleModule unnamed = lifecycle(plain, quiet);
        quiet.effect = () -> {
            group(plain, 2, G, 0);
            plain.tracks[0].child("position").emit(1);
            plain.tracks[1].child("position").emit(2);
            plain.bank.child("itemCount").emit(3);
            parent(plain, 2, ROOT);
            parent(plain, 0, G);
            parent(plain, 1, ROOT);
        };
        HostOperation one = plain.submit(unnamed, "tracks.group", groupRequest(plain, HostFixture.A));
        plain.next();
        plain.next();
        plain.next();
        JsonObject single = one.result.join();
        assertTrue(
                single.get("outcome").getAsString().matches("applied|already_matched")
                        && !single.has("fields")
                        && plain.tracks[2].child("name").writes == 0,
                "without name the observed group is returned unnamed");
        assertNull(quiet.name, "an unnamed group asks the native operation for no name");
    }

    @Test
    void groupRefusesTargetsThatAreNotAdjacentSiblings() {
        HostFixture f = new HostFixture();
        FakeGroup access = new FakeGroup();
        TrackLifecycleModule module = lifecycle(f, access);
        f.addTrack(2, HostFixture.C, "Audio");
        HostOperation gap = f.submit(module, "tracks.group", groupRequest(f, HostFixture.A, HostFixture.C));
        f.next();
        RpcException refused = assertRpcError("unsupported_scope", gap);
        assertTrue(refused.getMessage().contains("adjacent") && !refused.unknown && access.calls == 0);
        HostOperation next = f.submit(module, "tracks.group", groupRequest(f, HostFixture.A, HostFixture.B));
        f.next();
        assertEquals(1, access.calls, "adjacent targets reach the native operation");
        assertNotNull(next);
    }

    @Test
    void groupRefusesMixedParentsEffectTracksAndRecording() {
        HostFixture f = new HostFixture();
        FakeGroup access = new FakeGroup();
        TrackLifecycleModule module = lifecycle(f, access);
        f.tracks[0].child("trackType").emit("Group");
        f.tracks[0].child("isGroup").data = true;
        f.addTrack(2, HostFixture.C, "Audio");
        parent(f, 0, ROOT);
        parent(f, 1, HostFixture.A);
        parent(f, 2, ROOT);
        HostOperation mixed = f.submit(module, "tracks.group", groupRequest(f, HostFixture.B, HostFixture.C));
        f.next();
        assertTrue(
                !assertRpcError("unsupported_scope", mixed).unknown && access.calls == 0,
                "different parents are refused before native access");
        f.tracks[2].child("trackType").emit("Effect");
        HostOperation effect = f.submit(module, "tracks.group", groupRequest(f, HostFixture.C));
        f.next();
        assertTrue(
                !assertRpcError("unsupported_scope", effect).unknown && access.calls == 0, "effect tracks are refused");
        HostOperation outside =
                f.submit(module, "tracks.group", groupRequest(f, "00000000-0000-0000-0000-0000000000aa"));
        f.next();
        assertTrue(!assertRpcError("target_not_found", outside).unknown, "unknown track is not in the project");
        f.services.selection.handOff(new HostOperation(Long.MAX_VALUE));
        HostOperation leased = f.submit(module, "tracks.group", groupRequest(f, HostFixture.B));
        f.next();
        assertTrue(
                !assertRpcError("busy", leased).unknown && access.calls == 0, "a held selection lease blocks grouping");
        f.services.selection.handOff(null);
        f.transport.child("isArrangerRecordEnabled").emit(true);
        HostOperation recording = f.submit(module, "tracks.group", groupRequest(f, HostFixture.B));
        f.next();
        assertTrue(
                !assertRpcError("unsafe_state", recording).unknown && access.calls == 0, "recording blocks grouping");
    }

    @Test
    void unobservedGroupQuarantinesCreationAndGrouping() {
        HostFixture f = new HostFixture();
        FakeGroup access = new FakeGroup();
        TrackLifecycleModule module = lifecycle(f, access);
        HostOperation op = f.submit(module, "tracks.group", groupRequest(f, HostFixture.A), f.tasks.after(400));
        while (!op.result.isDone() && !f.tasks.isEmpty()) f.next();
        JsonObject result = op.result.join();
        assertTrue(
                result.get("outcome").getAsString().equals("unknown")
                        && result.get("reason").getAsString().equals("not_observed"),
                "an unobserved native grouping is unknown, never success");
        assertTrue(!f.services.holds.list().isEmpty(), "unknown grouping is listed as a hold");
        HostOperation create = f.submit(module, "tracks.insert", create("audio", -1));
        f.next();
        assertTrue(
                !assertRpcError("unresolved", create).unknown && f.application.actions == 0,
                "creation is quarantined after unknown grouping");
        HostOperation again = f.submit(module, "tracks.group", groupRequest(f, HostFixture.A));
        f.next();
        assertTrue(!assertRpcError("unresolved", again).unknown && access.calls == 1, "grouping is never retried");
    }

    /** position -1 appends, 0 is "start" and 1 is after track A (index 1 of the top-level main tracks). */
    private static JsonObject create(String type, int position) {
        JsonObject p = new JsonObject();
        p.addProperty("type", type);
        p.addProperty("name", "Created");
        if (position == 0) p.addProperty("position", "start");
        if (position == 1) p.add("position", HostFixture.json("{\"after\":" + HostFixture.track(HostFixture.A) + "}"));
        return p;
    }

    @Test
    void groupWithoutBoundNativeAccessIsRefusedByTheRuntimeGate() {
        HostFixture f = new HostFixture();
        HostOperation op = f.submit(lifecycle(f), "tracks.group", groupRequest(f, HostFixture.A));
        f.next();
        assertRpcError("host_mismatch", op);
    }

    /** A file dropped at the given insertion point of the track in {@code slot} makes instrument track C named so. */
    private static List<String> dropsFile(HostFixture f, int slot, String point, String name) {
        List<String> drops = new ArrayList<>();
        f.tracks[slot].child(point).on("insertFile", args -> {
            f.mutation();
            drops.add(slot + ":" + point + ":" + args[0]);
            f.addTrack(2, HostFixture.C, "Instrument");
            f.tracks[2].child("name").emit(name);
            return null;
        });
        return drops;
    }

    private static JsonObject fileInsert(Path file, String position) {
        JsonObject p = new JsonObject();
        p.addProperty("file", file.toString());
        if (position != null) p.addProperty("position", position);
        return p;
    }

    private static Path sample() throws IOException {
        Path dir = Files.createTempDirectory("x8-samples");
        Path file = dir.resolve("Kick.wav");
        Files.write(file, new byte[] {0});
        file.toFile().deleteOnExit();
        dir.toFile().deleteOnExit();
        return file;
    }

    @Test
    void aFileBecomesAnInstrumentTrackNamedAfterItWithoutARename() throws IOException {
        Path file = sample();
        HostFixture f = new HostFixture();
        TrackLifecycleModule module = lifecycle(f);
        List<String> drops = dropsFile(f, 1, "afterTrackInsertionPoint", "Kick");
        HostOperation op = f.submit(module, "tracks.insert", fileInsert(file, null));
        while (!op.result.isDone() && !f.tasks.isEmpty()) f.next();
        JsonObject result = op.result.join();
        assertEquals(List.of("1:afterTrackInsertionPoint:" + file), drops, "dropped after the last main track");
        assertEquals("applied", result.get("outcome").getAsString(), result.toString());
        assertEquals("api_readback", result.get("verification").getAsString());
        assertEquals(HostFixture.C, id(result));
        assertEquals(
                "instrument", result.getAsJsonObject("insertion").get("type").getAsString());
        assertEquals(0, f.application.actions, "no track factory is called");
        assertEquals(0, f.tracks[2].child("name").writes, "Bitwig's own name is kept");
        assertTrue(Results.leafRuleHolds(result), result.toString());
    }

    @Test
    void aFileGoesBeforeTheTrackAtItsIndex() throws IOException {
        Path file = sample();
        HostFixture f = new HostFixture();
        List<String> drops = dropsFile(f, 0, "beforeTrackInsertionPoint", "Kick");
        f.tracks[0].child("beforeTrackInsertionPoint").on("insertFile", args -> {
            f.mutation();
            drops.add("0:before:" + args[0]);
            f.addTrack(2, HostFixture.C, "Instrument");
            f.tracks[2].child("name").emit("Kick");
            for (int slot = 0; slot < 3; slot++)
                f.tracks[slot].child("position").emit((slot + 1) % 3);
            return null;
        });
        HostOperation op = f.submit(lifecycle(f), "tracks.insert", fileInsert(file, "start"));
        while (!op.result.isDone() && !f.tasks.isEmpty()) f.next();
        assertEquals(List.of("0:before:" + file), drops);
        JsonObject result = op.result.join();
        assertEquals("applied", result.get("outcome").getAsString(), result.toString());
        assertEquals(
                0, result.getAsJsonObject("insertion").get("observedPosition").getAsInt());
    }

    @Test
    void aTrackWithoutTheFilesNameIsNeverAttributed() throws IOException {
        Path file = sample();
        HostFixture f = new HostFixture();
        dropsFile(f, 1, "afterTrackInsertionPoint", "Inst 3");
        HostOperation op = f.submit(lifecycle(f), "tracks.insert", fileInsert(file, null), f.tasks.after(400));
        while (!op.result.isDone() && !f.tasks.isEmpty()) f.next();
        assertUnknown("not_observed", op);
    }

    @Test
    void aFileNeedsAMainTrackToLandNextToAndAReadableFile() throws IOException {
        HostFixture f = new HostFixture();
        f.tracks[0].child("trackType").emit("Effect");
        f.tracks[1].child("trackType").emit("Effect");
        refuse(f, lifecycle(f), "unsupported_scope", fileInsert(sample(), null));
        HostFixture g = new HostFixture();
        refuse(g, lifecycle(g), "invalid_file", fileInsert(sample().resolveSibling("missing.wav"), null));
        assertRpcError(
                "invalid_params",
                () -> TrackLifecycleModule.validateParams(
                        "tracks.insert", HostFixture.json("{\"file\":\"/a.wav\",\"type\":\"audio\"}")));
        assertRpcError(
                "invalid_params",
                () -> TrackLifecycleModule.validateParams("tracks.insert", HostFixture.json("{\"type\":\"audio\"}")));
    }

    @Test
    void unobservedCreationIsUnknownAndHoldsCreationAndGrouping() {
        HostFixture f = new HostFixture();
        TrackLifecycleModule module = lifecycle(f);
        f.create = factory -> {};
        HostOperation op = f.submit(module, "tracks.insert", create("audio", -1), f.tasks.after(400));
        while (!op.result.isDone() && !f.tasks.isEmpty()) f.next();
        assertUnknown("not_observed", op);
        HostOperation group = f.submit(module, "tracks.group", groupRequest(f, HostFixture.A));
        f.next();
        assertRpcError("unresolved", group);
    }

    @Test
    void creationThatRemovesAnExistingTrackReportsTargetChange() {
        HostFixture f = new HostFixture();
        TrackLifecycleModule module = lifecycle(f);
        f.create = factory -> {
            f.addTrack(2, HostFixture.C, "Audio");
            f.tracks[0].child("channelId").emit("");
            f.tracks[0].child("exists").emit(false);
        };
        HostOperation op = f.submit(module, "tracks.insert", create("audio", -1));
        while (!op.result.isDone() && !f.tasks.isEmpty()) f.next();
        assertUnknown("target_changed", op);
    }

    @Test
    void creationAndGroupingAreBusyWhileTheNewTrackIsBeingNamed() {
        HostFixture f = new HostFixture();
        TrackLifecycleModule module = lifecycle(f);
        f.create = factory -> f.addTrack(2, HostFixture.C, "Audio");
        f.tracks[2].child("name").notifyWrites = false;
        HostOperation first = f.submit(module, "tracks.insert", create("audio", -1), f.tasks.after(2000));
        while (f.tracks[2].child("name").writes == 0 && !first.result.isDone()) f.next();
        assertTrue(!first.result.isDone(), "the name write awaits its observation");
        HostOperation second = f.submit(module, "tracks.insert", create("audio", -1));
        f.next();
        assertRpcError("busy", second);
        HostOperation group = f.submit(module, "tracks.group", groupRequest(f, HostFixture.A));
        f.next();
        assertRpcError("busy", group);
    }
}
