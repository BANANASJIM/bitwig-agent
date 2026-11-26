package dev.bitwigagent;

import static dev.bitwigagent.RpcAssert.*;
import static org.junit.jupiter.api.Assertions.*;

import com.bitwig.extension.callback.*;
import com.bitwig.extension.controller.api.*;
import com.google.gson.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

/** Validation, safety and quarantine around a simulated native arranger adapter; not native binding evidence. */
public final class ArrangerModuleTest {
    static final String ID = "11111111-1111-1111-1111-111111111111";

    @Test
    void validation() {
        ArrangerModule.validateParams("arranger.clips.list", obj("trackId", ID));
        bad("arranger.clips.list", obj("trackId", "not-a-uuid"));
        bad("arranger.clips.list", obj("trackId", ID, "extra", 1));
        JsonObject create = create(0, 16);
        ArrangerModule.validateParams("arranger.clips.create", create);
        for (String[] field : new String[][] {
            {"startBeats", "-1"}, {"startBeats", "4194304.5"}, {"lengthBeats", "0"}, {"lengthBeats", "65537"}
        }) {
            JsonObject b = create(0, 16);
            b.addProperty(field[0], Double.parseDouble(field[1]));
            bad("arranger.clips.create", b);
        }
        JsonObject named = create(0, 16);
        named.addProperty("name", "");
        bad("arranger.clips.create", named);
        JsonObject channelOnly = create(0, 16);
        channelOnly.addProperty("channel", 1);
        bad("arranger.clips.create", channelOnly);
        JsonObject notes = create(0, 4);
        notes.add("notes", noteArray(note(0, 60, 100, 1), note(1, 60, 100, 1)));
        ArrangerModule.validateParams("arranger.clips.create", notes);
        assertTrue(ArrangerModule.notes(notes, 4).size() == 2, "two adjacent same-pitch notes are valid");
        JsonObject overlap = create(0, 4);
        overlap.add("notes", noteArray(note(0, 60, 100, 2), note(1, 60, 100, 1)));
        bad("arranger.clips.create", overlap);
        JsonObject outside = create(0, 4);
        outside.add("notes", noteArray(note(3.5, 60, 100, 1)));
        bad("arranger.clips.create", outside);
        JsonObject offGrid = create(0, 4);
        offGrid.add("notes", noteArray(note(0.001, 60, 100, 1)));
        bad("arranger.clips.create", offGrid);
        JsonObject withOp = create(0, 4);
        JsonObject n = note(0, 60, 100, 1);
        n.addProperty("op", "set");
        withOp.add("notes", noteArray(n));
        bad("arranger.clips.create", withOp);
        ArrangerModule.validateParams("arranger.clips.copyFromLauncher", copy());
        JsonObject slot = copy();
        slot.addProperty("slotIndex", 16);
        bad("arranger.clips.copyFromLauncher", slot);
        JsonObject edit = edit("move");
        edit.addProperty("startBeats", 8);
        ArrangerModule.validateParams("arranger.clips.edit", edit);
        JsonObject emptyName = edit("delete");
        emptyName.addProperty("expectedName", "");
        ArrangerModule.validateParams("arranger.clips.edit", emptyName);
        bad("arranger.clips.edit", edit("move"));
        JsonObject extra = edit("delete");
        extra.addProperty("startBeats", 1);
        bad("arranger.clips.edit", extra);
        JsonObject op = edit("split");
        bad("arranger.clips.edit", op);
        JsonObject rename = edit("rename");
        rename.addProperty("name", "");
        bad("arranger.clips.edit", rename);
    }

    @Test
    void listIsReadOnlyAndNeedsNoStoppedTransport() {
        Fixture f = new Fixture(true, "Audio");
        JsonObject result = f.call("arranger.clips.list", obj("trackId", ID));
        assertTrue(
                result.get("status").getAsString().equals("observed") && f.lists == 1 && f.writes == 0,
                "list reads through the adapter without writing");
    }

    @Test
    void createChecksTrackTypeSafetyAndPassesNotes() {
        Fixture audio = new Fixture(false, "Audio");
        assertRpcError("unsupported_scope", audio.start("arranger.clips.create", create(0, 4)));
        assertTrue(audio.calls == 0, "note clip on an audio track is refused before native access");
        Fixture playing = new Fixture(true, "Instrument");
        assertRpcError("unsafe_state", playing.start("arranger.clips.create", create(0, 4)));
        assertTrue(playing.calls == 0, "playing transport blocks arranger writes");
        Fixture f = new Fixture(false, "Instrument");
        JsonObject p = create(8, 4);
        p.add("notes", noteArray(note(0, 60, 100, 1), note(2, 64, 90, 0.5)));
        p.addProperty("channel", 2);
        f.call("arranger.clips.create", p);
        assertTrue(
                f.writes == 1 && f.lastNotes.size() == 2 && f.lastChannel == 2, "notes and channel reach the adapter");
        assertTrue(
                f.lastNotes.get(1)[1] == 64
                        && f.lastNotes.get(1)[2] == 90
                        && f.lastNotes.get(1)[3] == 0.5,
                "note fields keep beat/pitch/velocity/duration order");
        assertTrue(f.lastStart == 8 && f.lastLength == 4, "start and length pass through unchanged");
    }

    @Test
    void copyChecksTheObservedLauncherSlot() {
        Fixture f = new Fixture(false, "Audio");
        f.call("arranger.clips.copyFromLauncher", copy());
        assertTrue(f.writes == 1 && f.lastSlot != null, "copy passes the launcher slot proxy");
        Fixture wrong = new Fixture(false, "Audio");
        JsonObject b = copy();
        b.addProperty("expectedClipName", "Chorus");
        assertRpcError("stale_target", wrong.start("arranger.clips.copyFromLauncher", b));
        assertTrue(wrong.calls == 0, "a different observed clip name is refused before native access");
        Fixture empty = new Fixture(false, "Audio");
        empty.host.slot(0, 0).child("hasContent").data = false;
        assertRpcError("stale_target", empty.start("arranger.clips.copyFromLauncher", copy()));
    }

    @Test
    void editPassesOnlyItsPayload() {
        Fixture f = new Fixture(false, "Instrument");
        JsonObject p = edit("resize");
        p.addProperty("lengthBeats", 12);
        f.call("arranger.clips.edit", p);
        assertTrue(
                f.lastOp.equals("resize") && f.lastArgs.keySet().equals(Set.of("lengthBeats")),
                "edit forwards op and exactly its payload");
    }

    @Test
    void unknownWriteQuarantinesWritesButNotReads() {
        Fixture f = new Fixture(false, "Instrument");
        f.failAfterWrite = true;
        HostOperation failed = f.start("arranger.clips.create", create(0, 4));
        f.drain();
        assertTrue(failed.dispatched, "failure happened after dispatch");
        {
            CompletionException e = assertThrows(CompletionException.class, () -> failed.result.join());
            assertTrue(((RpcException) e.getCause()).outcome.equals("unknown"), "post-dispatch failure is unknown");
        }
        assertRpcError("unresolved_mutation", f.start("arranger.clips.create", create(8, 4)));
        assertTrue(
                f.call("arranger.clips.list", obj("trackId", ID))
                        .get("status")
                        .getAsString()
                        .equals("observed"),
                "reads stay available");
        assertTrue(!f.module.unresolved().isEmpty(), "unresolved write is listed for extension.restart");
        Fixture before = new Fixture(false, "Instrument");
        before.failBeforeWrite = true;
        HostOperation rejected = before.start("arranger.clips.create", create(0, 4));
        before.drain();
        assertRpcError("arranger_overlap", rejected);
        assertTrue(
                !rejected.dispatched && before.module.unresolved().isEmpty(),
                "pre-dispatch refusal does not quarantine");
    }

    static JsonObject create(double start, double length) {
        JsonObject p = obj("trackId", ID);
        p.addProperty("startBeats", start);
        p.addProperty("lengthBeats", length);
        p.addProperty("name", "Verse");
        return p;
    }

    static JsonObject copy() {
        JsonObject p = obj("trackId", ID);
        p.addProperty("slotIndex", 0);
        p.addProperty("expectedClipName", "Verse");
        p.addProperty("startBeats", 0);
        return p;
    }

    static JsonObject edit(String op) {
        JsonObject p = obj("trackId", ID);
        p.addProperty("clipIndex", 0);
        p.addProperty("expectedStartBeats", 0);
        p.addProperty("expectedName", "Verse");
        p.addProperty("op", op);
        return p;
    }

    static JsonObject note(double beat, int pitch, int velocity, double duration) {
        JsonObject n = new JsonObject();
        n.addProperty("beat", beat);
        n.addProperty("pitch", pitch);
        n.addProperty("velocity", velocity);
        n.addProperty("durationBeats", duration);
        return n;
    }

    static JsonArray noteArray(JsonObject... notes) {
        JsonArray a = new JsonArray();
        for (JsonObject n : notes) a.add(n);
        return a;
    }

    static JsonObject obj(Object... kv) {
        JsonObject p = new JsonObject();
        for (int i = 0; i < kv.length; i += 2) {
            if (kv[i + 1] instanceof Number) p.addProperty((String) kv[i], (Number) kv[i + 1]);
            else p.addProperty((String) kv[i], (String) kv[i + 1]);
        }
        return p;
    }

    static void bad(String m, JsonObject p) {
        assertRpcError("invalid_params", () -> ArrangerModule.validateParams(m, p));
    }

    static final class Fixture {
        final HostFixture host = new HostFixture();
        final Deque<Runnable> queue = host.tasks;
        final ArrangerModule module;
        final boolean playing;
        final String trackType;
        boolean failAfterWrite, failBeforeWrite;
        int writes, calls, lists;
        List<double[]> lastNotes;
        int lastChannel;
        double lastStart, lastLength;
        Object lastSlot;
        String lastOp;
        JsonObject lastArgs;

        Fixture(boolean playing, String trackType) {
            this.playing = playing;
            this.trackType = trackType;
            host.tracks[0].child("channelId").emit(ID);
            host.tracks[0].child("trackType").emit(trackType);
            host.transport.child("isPlaying").emit(playing);
            host.occupySlot(0, 0, "Verse");
            module = new ArrangerModule(host.services, new ArrangerModule.Access() {
                public void execute(Runnable task) {
                    queue.add(task);
                }

                public JsonObject list(Object track, String id, Runnable check) {
                    lists++;
                    check.run();
                    return HostServices.observed();
                }

                public JsonObject create(
                        Object track,
                        String id,
                        double start,
                        double length,
                        String name,
                        List<double[]> notes,
                        int channel,
                        Runnable check,
                        Runnable before) {
                    lastNotes = notes;
                    lastChannel = channel;
                    lastStart = start;
                    lastLength = length;
                    return write(check, before);
                }

                public JsonObject copyFromLauncher(
                        Object track,
                        Object slot,
                        String id,
                        String expected,
                        double start,
                        Runnable check,
                        Runnable before) {
                    lastSlot = slot;
                    return write(check, before);
                }

                public JsonObject edit(
                        Object track,
                        String id,
                        int index,
                        double start,
                        String name,
                        String op,
                        JsonObject args,
                        Runnable check,
                        Runnable before) {
                    lastOp = op;
                    lastArgs = args;
                    return write(check, before);
                }
            });
        }

        JsonObject write(Runnable check, Runnable before) {
            calls++;
            check.run();
            if (failBeforeWrite) throw Errors.notApplied(ErrorCode.ARRANGER_OVERLAP, "simulated overlap");
            before.run();
            writes++;
            if (failAfterWrite)
                throw new RpcException("arranger_verification_failed", "simulated readback mismatch", "unknown");
            return HostServices.observed();
        }

        HostOperation start(String m, JsonObject p) {
            return host.start(module, m, p);
        }

        void drain() {
            host.drain();
        }

        JsonObject call(String m, JsonObject p) {
            HostOperation op = start(m, p);
            drain();
            return op.result.join();
        }
    }
}
