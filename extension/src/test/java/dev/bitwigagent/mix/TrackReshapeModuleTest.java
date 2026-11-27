package dev.bitwigagent.mix;

import static dev.bitwigagent.RpcAssert.*;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import dev.bitwigagent.FakeHost;
import dev.bitwigagent.HostFixture;
import dev.bitwigagent.core.HostOperation;
import dev.bitwigagent.core.Refs;
import dev.bitwigagent.core.RpcException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** tracks.convert and tracks.ungroup against the simulated host and a fake native command; no native evidence. */
public final class TrackReshapeModuleTest {
    static final String ROOT = "00000000-0000-0000-0000-0000000000ff";

    /** Records each native command and runs the simulated effect it has on the bank. */
    static final class FakeCommands implements TrackReshapeModule.Access {
        final List<String> calls = new ArrayList<>();
        Runnable effect = () -> {};
        boolean restored = true;
        /** The simulated track's content: whether it holds audio clips and note clips. */
        boolean audioClips, noteClips;

        @Override
        public void execute(Runnable work) {
            work.run();
        }

        @Override
        public JsonObject convert(
                Object proxy,
                String trackId,
                int command,
                java.util.function.BiPredicate<Boolean, Boolean> allowed,
                Runnable check,
                Runnable beforeWrite) {
            if (!allowed.test(audioClips, noteClips)) {
                check.run();
                JsonObject info = new JsonObject();
                info.addProperty("selectionRestored", true);
                info.addProperty("enabled", false);
                info.addProperty("hasAudioClips", audioClips);
                info.addProperty("hasNoteClips", noteClips);
                return info;
            }
            JsonObject info = run("convert:" + trackId + ":" + command, check, beforeWrite);
            info.addProperty("enabled", true);
            return info;
        }

        @Override
        public JsonObject ungroup(Object proxy, String groupId, Runnable check, Runnable beforeWrite) {
            return run("ungroup:" + groupId, check, beforeWrite);
        }

        private JsonObject run(String call, Runnable check, Runnable beforeWrite) {
            check.run();
            beforeWrite.run();
            calls.add(call);
            effect.run();
            JsonObject info = new JsonObject();
            info.addProperty("nativeOperation", call.substring(0, call.indexOf(':')));
            info.addProperty("selectionRestored", restored);
            return info;
        }
    }

    private static TrackReshapeModule module(HostFixture f, FakeCommands commands) {
        return f.services.trackReshape(f.services.sharedMix(), commands);
    }

    private static JsonObject convert(String id, String name, String to) {
        JsonObject p = new JsonObject();
        p.add("target", Refs.track(id, name));
        p.addProperty("to", to);
        return p;
    }

    private static HostOperation submitted(HostFixture f, TrackReshapeModule module, String method, JsonObject p) {
        return settle(f, f.submit(module, method, p));
    }

    private static HostOperation settle(HostFixture f, HostOperation op) {
        while (!op.result.isDone() && !f.tasks.isEmpty()) f.next();
        return op;
    }

    private static JsonObject finish(HostFixture f, HostOperation op) {
        return settle(f, op).result.join();
    }

    /** An instrument track B converted to hybrid by the native command: an applied example. */
    public static JsonObject convertExample() {
        HostFixture f = new HostFixture();
        f.tracks[1].child("trackType").emit("Instrument");
        FakeCommands commands = new FakeCommands();
        commands.effect = () -> f.tracks[1].child("trackType").emit("Hybrid");
        return finish(f, f.submit(module(f, commands), "tracks.convert", convert(HostFixture.B, "Track 1", "hybrid")));
    }

    @Test
    void convertSendsTheCommandIdAndReadsTheNewTypeBack() {
        HostFixture f = new HostFixture();
        f.tracks[1].child("trackType").emit("Instrument");
        FakeCommands commands = new FakeCommands();
        commands.effect = () -> f.tracks[1].child("trackType").emit("Hybrid");
        JsonObject result =
                finish(f, f.submit(module(f, commands), "tracks.convert", convert(HostFixture.B, "Track 1", "hybrid")));
        assertEquals(List.of("convert:" + HostFixture.B + ":6919"), commands.calls);
        assertEquals("applied", result.get("outcome").getAsString(), result.toString());
        assertEquals("api_readback", result.get("verification").getAsString());
        assertEquals(HostFixture.B, result.getAsJsonObject("ref").get("id").getAsString(), "the UUID is kept");
        assertEquals("Instrument", result.getAsJsonObject("type").get("before").getAsString());
        assertEquals("Hybrid", result.getAsJsonObject("type").get("after").getAsString());
        assertTrue(result.get("selectionRestored").getAsBoolean());
        assertFalse(result.has("selectionNote"));
        assertEquals(
                "convert",
                result.getAsJsonObject("native").get("nativeOperation").getAsString());
        assertFalse(f.services.selection.held(), "the selection lease ends with the call");
        assertEquals(6918, TrackReshapeModule.CONVERT_COMMANDS.get("audio"));
        assertEquals(6917, TrackReshapeModule.CONVERT_COMMANDS.get("instrument"));

        JsonObject same =
                finish(f, f.submit(module(f, commands), "tracks.convert", convert(HostFixture.B, "Track 1", "hybrid")));
        assertEquals("already_matched", same.get("outcome").getAsString());
        assertEquals(1, commands.calls.size(), "a track of the requested type sends nothing");
    }

    @Test
    void convertReportsAnUnrestoredSelectionAndAnUnobservedType() {
        HostFixture f = new HostFixture();
        FakeCommands commands = new FakeCommands();
        commands.restored = false;
        commands.effect = () -> f.tracks[1].child("trackType").emit("Hybrid");
        JsonObject result =
                finish(f, f.submit(module(f, commands), "tracks.convert", convert(HostFixture.B, "Track 1", "hybrid")));
        assertEquals("applied", result.get("outcome").getAsString());
        assertFalse(result.get("selectionRestored").getAsBoolean());
        assertTrue(result.get("selectionNote").getAsString().contains("could not be put back"));

        HostFixture quiet = new HostFixture();
        HostOperation op = quiet.submit(
                module(quiet, new FakeCommands()),
                "tracks.convert",
                convert(HostFixture.B, "Track 1", "instrument"),
                quiet.tasks.after(400));
        JsonObject unknown = assertUnknown("not_observed", settle(quiet, op));
        assertEquals("Audio", unknown.getAsJsonObject("type").get("after").getAsString());
    }

    /** Converts hybrid track B to {@code to} with the given content; the effect sets the new type. */
    private static JsonObject convertHybrid(String to, boolean audio, boolean notes, FakeCommands commands) {
        HostFixture f = new HostFixture();
        f.tracks[1].child("trackType").emit("Hybrid");
        commands.audioClips = audio;
        commands.noteClips = notes;
        String type = to.substring(0, 1).toUpperCase() + to.substring(1);
        commands.effect = () -> f.tracks[1].child("trackType").emit(type);
        HostOperation op = f.submit(module(f, commands), "tracks.convert", convert(HostFixture.B, "Track 1", to));
        JsonObject result = finish(f, op);
        if (!result.get("outcome").getAsString().equals("applied"))
            assertFalse(op.dispatched(), "a refused conversion dispatches nothing");
        return result;
    }

    @Test
    void aConversionThatWouldDropClipsIsNotAppliedAndSendsNothing() {
        // {to, audio clips, note clips, applied?}: Bitwig's rule, the same both directions.
        Object[][] cases = {
            {"audio", true, true, false},
            {"instrument", true, true, false},
            {"audio", true, false, true},
            {"instrument", true, false, false},
            {"audio", false, true, false},
            {"instrument", false, true, true},
            {"audio", false, false, true},
            {"instrument", false, false, true},
        };
        for (Object[] c : cases) {
            FakeCommands commands = new FakeCommands();
            JsonObject result = convertHybrid((String) c[0], (Boolean) c[1], (Boolean) c[2], commands);
            String label = java.util.Arrays.toString(c) + " " + result;
            if ((Boolean) c[3]) {
                assertEquals("applied", result.get("outcome").getAsString(), label);
                assertEquals(1, commands.calls.size(), label);
                continue;
            }
            assertEquals("not_applied", result.get("outcome").getAsString(), label);
            assertEquals("host_refused", result.get("reason").getAsString(), label);
            String blocking = c[0].equals("audio") ? "note" : "audio";
            assertTrue(result.get("next").getAsString().contains("Remove the track's " + blocking + " clips"), label);
            assertEquals(c[1], result.get("hasAudioClips").getAsBoolean(), label);
            assertEquals(c[2], result.get("hasNoteClips").getAsBoolean(), label);
            assertTrue(result.get("selectionRestored").getAsBoolean(), label);
            assertTrue(commands.calls.isEmpty(), label);
        }
        FakeCommands mixed = new FakeCommands();
        assertEquals("applied", convertHybridFrom("Audio", mixed).get("outcome").getAsString(), "hybrid holds both");
    }

    private static JsonObject convertHybridFrom(String type, FakeCommands commands) {
        HostFixture f = new HostFixture();
        f.tracks[1].child("trackType").emit(type);
        commands.audioClips = true;
        commands.noteClips = true;
        commands.effect = () -> f.tracks[1].child("trackType").emit("Hybrid");
        return finish(f, f.submit(module(f, commands), "tracks.convert", convert(HostFixture.B, "Track 1", "hybrid")));
    }

    @Test
    void convertRefusesOtherTrackTypesRecordingStaleRefsAndAHeldSelection() {
        HostFixture f = new HostFixture();
        FakeCommands commands = new FakeCommands();
        TrackReshapeModule module = module(f, commands);
        for (String type : List.of("Effect", "Group", "Master")) {
            f.tracks[1].child("trackType").emit(type);
            assertRpcError(
                    "unsupported_scope",
                    submitted(f, module, "tracks.convert", convert(HostFixture.B, "Track 1", "hybrid")));
        }
        f.tracks[1].child("trackType").emit("Audio");
        assertRpcError(
                "target_changed", submitted(f, module, "tracks.convert", convert(HostFixture.B, "Renamed", "hybrid")));
        f.services.selection.handOff(new HostOperation(Long.MAX_VALUE));
        assertRpcError("busy", submitted(f, module, "tracks.convert", convert(HostFixture.B, "Track 1", "hybrid")));
        f.services.selection.handOff(null);
        f.transport.child("isArrangerRecordEnabled").emit(true);
        assertRpcError(
                "unsafe_state", submitted(f, module, "tracks.convert", convert(HostFixture.B, "Track 1", "hybrid")));
        f.drain();
        assertTrue(commands.calls.isEmpty(), "nothing refused reaches the native command");
    }

    @Test
    void withoutBoundNativeAccessTheRuntimeGateRefuses() {
        HostFixture f = new HostFixture();
        HostOperation op = f.submit(
                f.services.trackReshape(f.services.sharedMix(), null),
                "tracks.convert",
                convert(HostFixture.B, "Track 1", "hybrid"));
        assertRpcError("host_mismatch", settle(f, op));
    }

    private static void parent(HostFixture f, int slot, String id) {
        FakeHost parent = f.tracks[slot].child("createParentTrack");
        parent.child("exists").data = id != null;
        parent.child("channelId").data = id == null ? "" : id;
    }

    /** Group A holding B, then C at the top level. */
    private static void grouped(HostFixture f) {
        f.addTrack(2, HostFixture.C, "Audio");
        f.tracks[0].child("trackType").emit("Group");
        f.tracks[0].child("isGroup").data = true;
        f.tracks[0].child("isGroupExpanded").data = true;
        parent(f, 0, ROOT);
        parent(f, 1, HostFixture.A);
        parent(f, 2, ROOT);
    }

    /** The bank after ungrouping A: B and C at the top level. */
    private static void ungrouped(HostFixture f) {
        parent(f, 1, ROOT);
        f.removeTrack(0, 2);
    }

    /** Group A ungrouped by the native command: an applied example. */
    public static JsonObject ungroupExample() {
        HostFixture f = new HostFixture();
        grouped(f);
        FakeCommands commands = new FakeCommands();
        commands.effect = () -> ungrouped(f);
        JsonObject p = new JsonObject();
        p.add("target", Refs.track(HostFixture.A, "Track 0"));
        return finish(f, f.submit(module(f, commands), "tracks.ungroup", p));
    }

    @Test
    void ungroupVerifiesTheChildrenMovedUpAndNothingElseChanged() {
        JsonObject result = ungroupExample();
        assertEquals("applied", result.get("outcome").getAsString(), result.toString());
        assertEquals("api_readback", result.get("verification").getAsString());
        assertEquals(
                HostFixture.A, result.getAsJsonObject("ungrouped").get("id").getAsString());
        JsonArray children = result.getAsJsonArray("children");
        assertEquals(1, children.size());
        assertEquals(HostFixture.B, children.get(0).getAsJsonObject().get("id").getAsString());
        assertTrue(result.get("parent").isJsonNull(), "the group was at the top level");
        assertTrue(result.get("selectionRestored").getAsBoolean());

        HostFixture f = new HostFixture();
        grouped(f);
        FakeCommands commands = new FakeCommands();
        commands.effect = () -> f.removeTrack(0, 2);
        JsonObject p = new JsonObject();
        p.add("target", Refs.track(HostFixture.A, "Track 0"));
        HostOperation op = f.submit(module(f, commands), "tracks.ungroup", p, f.tasks.after(400));
        JsonObject unknown = assertUnknown("not_observed", settle(f, op));
        assertFalse(unknown.has("children"), "a child that did not move up is never reported as ungrouped");
        assertTrue(unknown.has("selectionRestored"));
    }

    @Test
    void ungroupRefusesNonGroupsAndAnIncompleteTree() {
        HostFixture f = new HostFixture();
        FakeCommands commands = new FakeCommands();
        TrackReshapeModule module = module(f, commands);
        JsonObject p = new JsonObject();
        p.add("target", Refs.track(HostFixture.A, "Track 0"));
        assertRpcError("unsupported_scope", submitted(f, module, "tracks.ungroup", p));
        f.tracks[0].child("trackType").emit("Group");
        f.tracks[0].child("isGroup").data = true;
        assertRpcError("not_ready", submitted(f, module, "tracks.ungroup", p));
        HostFixture collapsed = new HostFixture();
        grouped(collapsed);
        collapsed.tracks[0].child("isGroupExpanded").data = false;
        RpcException hidden =
                assertRpcError("not_ready", submitted(collapsed, module(collapsed, commands), "tracks.ungroup", p));
        assertTrue(hidden.getMessage().contains("expand the group"), hidden.getMessage());
        f.drain();
        assertTrue(commands.calls.isEmpty());
    }
}
