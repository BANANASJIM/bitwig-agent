package dev.bitwigagent.launcher;

import static dev.bitwigagent.RpcAssert.*;
import static dev.bitwigagent.TestJson.*;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import dev.bitwigagent.HostBridge;
import dev.bitwigagent.HostFixture;
import dev.bitwigagent.core.HostOperation;
import dev.bitwigagent.core.Refs;
import dev.bitwigagent.core.RpcException;
import dev.bitwigagent.rpc.RpcServer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Consumer tests against API-shaped in-memory host proxies; never native-host evidence. */
public final class NoteModuleTest {

    private static JsonObject set(double beat, int pitch, int velocity, double duration) {
        JsonObject e = new JsonObject();
        e.addProperty("op", "set");
        e.addProperty("beat", beat);
        e.addProperty("pitch", pitch);
        e.addProperty("velocity", velocity);
        e.addProperty("durationBeats", duration);
        return e;
    }

    private static JsonObject clear(double beat, int pitch) {
        JsonObject e = new JsonObject();
        e.addProperty("op", "clear");
        e.addProperty("beat", beat);
        e.addProperty("pitch", pitch);
        return e;
    }

    private static JsonArray fixture() {
        JsonArray edits = new JsonArray();
        edits.add(set(0, 60, 100, .5));
        edits.add(set(0, 64, 80, .5));
        edits.add(set(0, 67, 90, .5));
        edits.add(set(1, 62, 100, .5));
        edits.add(set(0, 36, 110, .25));
        return edits;
    }

    private static JsonArray array(JsonObject... all) {
        JsonArray list = new JsonArray();
        for (JsonObject one : all) list.add(one);
        return list;
    }

    /** A launcher clip named Riff at the scene. */
    private static void clipAt(LauncherFake f, int scene) {
        f.slots[scene].content = true;
        f.slots[scene].name = "Riff";
    }

    private static JsonObject target(LauncherFake f, int scene) {
        JsonObject p = new JsonObject();
        p.add("target", f.clip(scene));
        return p;
    }

    private static JsonObject window(LauncherFake f, int scene) {
        JsonObject p = target(f, scene);
        p.addProperty("channel", 0);
        p.addProperty("stepSizeBeats", .25);
        p.addProperty("startStep", 0);
        p.addProperty("steps", 16);
        p.addProperty("pitchStart", 0);
        p.addProperty("pitchCount", 128);
        return p;
    }

    private static JsonObject window(LauncherFake f) {
        return window(f, 0);
    }

    private static JsonObject editFixture(LauncherFake f) {
        JsonObject p = window(f);
        p.add("edits", fixture());
        return p;
    }

    private static JsonObject editing(JsonObject p, JsonArray edits) {
        p.add("edits", edits);
        return p;
    }

    private static JsonObject manyEdits(LauncherFake f, int count) {
        JsonObject p = target(f, 0);
        JsonArray edits = new JsonArray();
        for (int i = 0; i < count; i++) edits.add(set(i * .5, 48 + i % 12, 60 + i, .25));
        p.add("edits", edits);
        return p;
    }

    private static JsonObject find(JsonObject result, int pitch, double beat) {
        for (JsonElement v : result.getAsJsonArray("notes")) {
            JsonObject n = v.getAsJsonObject();
            if (n.get("pitch").getAsInt() == pitch && n.get("beat").getAsDouble() == beat) return n;
        }
        throw new AssertionError("missing observed note");
    }

    private static LauncherFake riff() {
        LauncherFake f = new LauncherFake();
        clipAt(f, 0);
        f.put(0, 0, 0, 60, 100, 4);
        f.put(0, 0, 8, 64, 90, 4);
        f.put(0, 0, 3, 67, 80, 2);
        return f;
    }

    private static NoteGrid.Plan plan(JsonObject p) {
        return NoteGrid.plan(p, p.getAsJsonArray("edits"), "edits", null);
    }

    private static NoteGrid.Window derived(JsonObject p) {
        List<NoteGrid.Part> parts = plan(p).parts;
        assertEquals(1, parts.size(), "one window");
        return parts.get(0).window;
    }

    private static void invalid(LauncherFake f, JsonObject p) {
        HostOperation op = f.start("notes.edit", p);
        assertTrue(op.result.isDone(), () -> "accepted: " + p);
        assertRpcError("invalid_params", op);
        assertFalse(op.dispatched(), "a rejected request touches nothing");
    }

    // ---- validation ----

    @Test
    void schemaRejectsMalformedRequests() {
        LauncherFake f = new LauncherFake();
        clipAt(f, 0);
        JsonObject valid = editFixture(f);
        JsonObject filled = HostBridge.checkSchema(HostFixture.SPECS, "notes.edit", valid);
        assertEquals(5, NoteGrid.edits(plan(filled).parts.get(0)).size(), "melody/chord/drum fixture accepted");
        JsonObject bad = valid.deepCopy();
        bad.addProperty("channel", 16);
        invalid(f, bad);
        bad = valid.deepCopy();
        bad.addProperty("steps", 129);
        invalid(f, bad);
        bad = valid.deepCopy();
        bad.addProperty("pitchStart", 120);
        bad.addProperty("pitchCount", 9);
        invalid(f, bad);
        bad = valid.deepCopy();
        bad.addProperty("unexpected", true);
        invalid(f, bad);
        bad = valid.deepCopy();
        bad.add("edits", new JsonArray());
        invalid(f, bad);
        bad = valid.deepCopy();
        bad.addProperty("detail", "verbose");
        invalid(f, bad);
        bad = valid.deepCopy();
        bad.remove("pitchCount");
        invalid(f, bad);
        JsonArray tooMany = new JsonArray();
        for (int i = 0; i < 257; i++) tooMany.add(set(0, 60, 100, .25));
        bad = valid.deepCopy();
        bad.add("edits", tooMany);
        invalid(f, bad);
        JsonObject read = window(f);
        read.addProperty("detail", "full");
        assertRpcError("invalid_params", f.start("notes.read", read));
        JsonObject partial = target(f, 0);
        partial.addProperty("steps", 8);
        assertRpcError("invalid_params", f.start("notes.read", partial));
        assertRpcError("invalid_params", f.start("notes.edit", editing(partial, fixture())));
    }

    @Test
    void aSetNeedsVelocityAndDurationAndAClearForbidsThem() {
        LauncherFake f = new LauncherFake();
        JsonObject valid = editFixture(f);
        JsonObject bad = valid.deepCopy();
        bad.add("edits", array(obj("{\"op\":\"set\",\"beat\":0,\"pitch\":60,\"durationBeats\":1}")));
        invalid(f, bad);
        bad = valid.deepCopy();
        bad.add("edits", array(obj("{\"op\":\"clear\",\"beat\":0,\"pitch\":60,\"velocity\":90}")));
        invalid(f, bad);
        bad = valid.deepCopy();
        bad.add("edits", array(obj("{\"op\":\"set\",\"beat\":0,\"pitch\":60,\"velocity\":90}")));
        for (String grid : List.of("stepSizeBeats", "startStep", "steps", "pitchStart", "pitchCount")) bad.remove(grid);
        invalid(f, bad);
    }

    @Test
    void semanticValidationRunsBeforeAnythingIsSelected() {
        LauncherFake f = new LauncherFake();
        JsonObject valid = editFixture(f);
        JsonObject bad = valid.deepCopy();
        bad.getAsJsonArray("edits").add(bad.getAsJsonArray("edits").get(0).deepCopy());
        invalid(f, bad);
        bad = valid.deepCopy();
        bad.getAsJsonArray("edits").get(0).getAsJsonObject().addProperty("beat", .3);
        invalid(f, bad);
        bad = valid.deepCopy();
        bad.getAsJsonArray("edits").get(0).getAsJsonObject().addProperty("durationBeats", 5);
        invalid(f, bad);
        bad = valid.deepCopy();
        bad.getAsJsonArray("edits").add(set(.25, 60, 100, .25));
        invalid(f, bad); // overlaps the first edit's pitch-60 span
        bad = valid.deepCopy();
        bad.add("edits", array(set(4, 60, 100, .25)));
        invalid(f, bad); // beat 4 is step 16, one past the 16-step window
        bad = valid.deepCopy();
        bad.addProperty("pitchCount", 61);
        invalid(f, bad); // pitches 62..67 of the fixture lie outside 0..60
        assertEquals(0, f.selected, "no clip was selected");
        assertEquals(0, f.writes);
        JsonObject tenths = window(f);
        tenths.addProperty("stepSizeBeats", .1);
        tenths.add("edits", array(set(.3, 60, 90, .25)));
        assertEquals(3, NoteGrid.edits(plan(tenths).parts.get(0)).get(0).x, "decimal alignment is exact");
        tenths.add("edits", array(set(.30000000000000004, 60, 90, .25)));
        assertRpcError("invalid_params", () -> plan(tenths));
        JsonObject far = target(f, 0);
        far.add("edits", array(set(1e12, 60, 90, .25)));
        assertRpcError("invalid_params", () -> plan(far));
    }

    @Test
    void omittedWindowIsDerivedFromEdits() {
        LauncherFake f = new LauncherFake();
        clipAt(f, 0);
        JsonObject p = target(f, 0);
        p.add("edits", fixture());
        NoteGrid.Window w = derived(p);
        assertEquals(0, w.exact.compareTo(new BigDecimal("0.015625")), "the finest grid that fits 128 steps");
        assertEquals(0, w.start);
        assertEquals(96, w.steps);
        assertEquals(36, w.pitch);
        assertEquals(32, w.pitches, "the pitch range comes from the edits");
        JsonObject out = f.call("notes.edit", p);
        assertEquals("applied", out.get("outcome").getAsString());
        assertTrue(out.getAsJsonObject("window").get("derived").getAsBoolean(), "the derived window is echoed");
        assertTrue(
                f.notes.containsKey(LauncherFake.key(0, 0, 0, 60))
                        && f.notes.containsKey(LauncherFake.key(0, 0, 64, 62)),
                "notes landed on absolute steps of the derived grid");

        JsonObject quarter = target(f, 0);
        quarter.add("edits", array(set(2, 61, 90, 2)));
        assertEquals(0, derived(quarter).exact.compareTo(new BigDecimal("0.015625")));
        JsonObject whole = target(f, 0);
        whole.add("edits", array(set(0, 61, 90, 256)));
        NoteGrid.Window coarse = derived(whole);
        assertEquals(0, coarse.exact.compareTo(new BigDecimal("2")), "a single 256-beat note derives a coarse grid");
        assertEquals(128, coarse.steps);
        JsonObject fine = target(f, 0);
        fine.add("edits", array(set(.0625, 61, 90, .125)));
        assertEquals(
                0, derived(fine).exact.compareTo(new BigDecimal("0.0078125")), "short fine edits use the finest grid");

        JsonObject wide = target(f, 0);
        wide.add("edits", array(set(0, 60, 90, .25), set(40, 60, 90, .25)));
        assertEquals(0, derived(wide).exact.compareTo(new BigDecimal("0.5")), "durations no longer force a finer grid");
        JsonObject odd = target(f, 0);
        odd.add("edits", array(set(.001, 60, 90, .25)));
        assertRpcError("invalid_params", f.start("notes.edit", odd));

        LauncherFake hidden = new LauncherFake();
        clipAt(hidden, 0);
        hidden.noteStorageStepSize = 1.0 / 128;
        hidden.put(0, 0, 0, 36, 90, .5);
        hidden.put(0, 0, 64, 36, 90, .5); // a second onset at beat 0.5
        JsonObject overlap = target(hidden, 0);
        overlap.add("edits", array(set(0, 36, 100, 1)));
        assertUnknown("overlap", hidden.startAndDrain("notes.edit", overlap));
        assertEquals(0, hidden.writes, "a derived window cannot hide a finer existing onset");

        assertEquals(
                "applied", f.call("notes.edit", editFixture(f)).get("outcome").getAsString());
    }

    // ---- grid rules the write absorbs ----

    private static LauncherFake longClip() {
        LauncherFake f = new LauncherFake();
        clipAt(f, 0);
        f.slots[0].length = 128;
        return f;
    }

    /** Three notes 40 beats apart whose onsets need a 1/16-note grid: three windows. */
    private static JsonObject spread(LauncherFake f) {
        JsonObject p = target(f, 0);
        p.add("edits", array(set(0, 60, 90, .25), set(40.25, 61, 90, .5), set(80.5, 62, 90, .3)));
        return p;
    }

    @Test
    void everyBrokenRuleIsRefusedAtOnceBeforeAnythingIsSelected() {
        LauncherFake f = longClip();
        JsonObject p = target(f, 0);
        p.add(
                "edits",
                array(
                        set(0, 33, 90, .75),
                        set(.5, 33, 90, .25),
                        set(1.3, 40, 90, .25),
                        set(2, 41, 90, .25),
                        set(2, 41, 90, .25)));
        String message =
                assertRpcError("invalid_params", f.start("notes.edit", p)).getMessage();
        assertEquals(
                "edits: 3 problems, nothing was written: edits[1] starts at beat 0.5 before edits[0] on pitch 33 ends"
                        + " at beat 0.75: shorten edits[0] to durationBeats <= 0.5; edits[2] beat 1.3 is not on the"
                        + " 1/128-beat grid: use 1.296875 or 1.3046875; edits[4] repeats the beat and pitch of edits[3]:"
                        + " remove one",
                message);
        JsonObject long_ = target(f, 0);
        long_.add("edits", array(set(0, 60, 90, 40), set(40.25, 61, 90, .25)));
        assertTrue(assertRpcError("invalid_params", f.start("notes.edit", long_))
                .getMessage()
                .contains("edits[0] durationBeats 40 spans more than 128 steps of the 0.25-beat grid the onsets"
                        + " need: shorten it to durationBeats <= 32 or write it in a call of its own"));
        JsonObject alone = target(f, 0);
        alone.add("edits", array(set(0, 60, 90, 600)));
        assertTrue(assertRpcError("invalid_params", () -> plan(alone))
                .getMessage()
                .endsWith("spans more than 128 steps of the 4-beat grid the onsets need: shorten it to durationBeats"
                        + " <= 512"));
        assertEquals(0, f.selected, "no clip was selected");
        assertEquals(0, f.writes);
    }

    @Test
    void durationsAreSnappedToTheOnsetGridAndReported() {
        LauncherFake f = longClip();
        JsonObject p = target(f, 0);
        p.add("edits", array(set(0, 60, 90, .45), set(.5, 60, 90, .503), set(1, 62, 90, .5)));
        JsonObject out = f.call("notes.edit", p);
        assertEquals("applied", out.get("outcome").getAsString());
        JsonArray adjusted = out.getAsJsonArray("adjusted");
        assertEquals(obj("{\"index\":0,\"requested\":0.45,\"written\":0.453125}"), adjusted.get(0));
        assertEquals(
                .5,
                adjusted.get(1).getAsJsonObject().get("written").getAsDouble(),
                "0.503 snaps to where the next same-pitch note starts, not past it");
        assertEquals(.453125, f.notes.get(LauncherFake.key(0, 0, 0, 60)).duration);
        assertFalse(f.call("notes.edit", editFixture(f)).has("adjusted"), "on-grid durations report nothing");
    }

    @Test
    void aSpanBeyondOneWindowIsWrittenInSeveralWindowsOfOneCall() {
        LauncherFake f = longClip();
        JsonObject p = spread(f);
        List<NoteGrid.Part> parts = plan(p).parts;
        assertEquals(3, parts.size());
        assertEquals(0, parts.get(1).window.exact.compareTo(new BigDecimal("0.25")), "the coarsest aligning grid");
        assertEquals(161, parts.get(1).window.start);
        JsonObject out = f.call("notes.edit", p);
        assertEquals("applied", out.get("outcome").getAsString());
        JsonArray windows = out.getAsJsonArray("windows");
        assertEquals(3, windows.size());
        JsonObject first = windows.get(0).getAsJsonObject();
        assertEquals("applied", first.get("outcome").getAsString());
        assertTrue(first.has("window") && !first.has("ref") && !first.has("loop"), "clip fields appear once");
        assertEquals(
                obj(
                        "{\"requested\":3,\"dispatched\":3,\"alreadyMatched\":0,\"verified\":3,\"unverified\":0,\"notReached\":0}"),
                out.getAsJsonObject("summary"));
        assertFalse(out.has("notReached"));
        assertEquals(1, out.getAsJsonArray("adjusted").size(), "0.3 is written as 0.296875");
        assertEquals(.296875, f.notes.get(LauncherFake.key(0, 0, 322, 62)).duration);
        assertTrue(f.notes.containsKey(LauncherFake.key(0, 0, 161, 61)));
        assertFalse(f.services.selection.held(), "the cursor lease ends with the call");
        assertEquals(
                "already_matched",
                f.call("notes.edit", spread(f)).get("outcome").getAsString(),
                "re-sending converges");
    }

    @Test
    void aWindowThatFailsAfterItsWriteStopsTheRest() {
        LauncherFake f = longClip();
        f.failWriteAt = 2;
        JsonObject out = f.call("notes.edit", spread(f));
        assertEquals("unknown", out.get("outcome").getAsString());
        JsonArray windows = out.getAsJsonArray("windows");
        assertEquals("applied", windows.get(0).getAsJsonObject().get("outcome").getAsString());
        assertEquals("unknown", windows.get(1).getAsJsonObject().get("outcome").getAsString());
        JsonObject rest = windows.get(2).getAsJsonObject();
        assertEquals("not_applied", rest.get("outcome").getAsString());
        assertEquals("not_reached", rest.get("reason").getAsString());
        assertEquals(JsonParser.parseString("[2]"), out.getAsJsonArray("notReached"));
        JsonObject summary = out.getAsJsonObject("summary");
        assertEquals(2, summary.get("dispatched").getAsInt());
        assertEquals(1, summary.get("notReached").getAsInt());
        assertEquals(2, f.writes);
    }

    @Test
    void aLaterWindowRefusedBeforeItsWriteIsNotApplied() {
        LauncherFake f = longClip();
        f.put(0, 0, 160, 61, 90, 1); // sustains over beat 40.25 on the window grid
        JsonObject out = f.call("notes.edit", spread(f));
        assertEquals("not_applied", out.get("outcome").getAsString());
        JsonObject refused = out.getAsJsonArray("windows").get(1).getAsJsonObject();
        assertEquals("not_applied", refused.get("outcome").getAsString());
        assertEquals("overlap", refused.get("reason").getAsString());
        assertEquals(JsonParser.parseString("[1,2]"), out.getAsJsonArray("notReached"));
        assertEquals(1, f.writes, "only the first window wrote");
    }

    @Test
    void aFirstWindowLostAfterItsSelectionIsUnknown() {
        LauncherFake f = longClip();
        HostOperation op = f.start("notes.edit", spread(f));
        f.trackId = "22222222-2222-2222-2222-222222222222";
        f.drain(op);
        assertTrue(op.dispatched(), "the first window's selection already counts as a write");
        assertEquals("unknown", op.result.join().get("outcome").getAsString());
        assertEquals(0, f.writes);
    }

    @Test
    void tooLittleTimeForAnotherWindowStopsBeforeIt() {
        LauncherFake f = longClip();
        HostOperation op = f.start(new HostOperation(f.tasks.after(WINDOW_DEADLINE_MS)), "notes.edit", spread(f));
        f.drain(op);
        JsonObject out = op.result.join();
        JsonArray windows = out.getAsJsonArray("windows");
        assertEquals("applied", windows.get(0).getAsJsonObject().get("outcome").getAsString());
        assertEquals("deadline", windows.get(1).getAsJsonObject().get("reason").getAsString());
        assertEquals(
                "not_reached", windows.get(2).getAsJsonObject().get("reason").getAsString());
        assertEquals(JsonParser.parseString("[1,2]"), out.getAsJsonArray("notReached"));
        assertEquals(1, f.writes);
    }

    private static final int WINDOW_DEADLINE_MS = 500;

    @Test
    void omittedReadWindowCoversTheClipLoop() {
        LauncherFake f = new LauncherFake();
        clipAt(f, 0);
        f.slots[0].length = 13 * 4;
        f.put(0, 0, 100, 60, 90, .25); // beat 25
        JsonObject read = f.call("notes.read", target(f, 0));
        JsonObject w = read.getAsJsonObject("window");
        assertEquals(.25, w.get("stepSizeBeats").getAsDouble());
        assertEquals(128, w.get("steps").getAsInt());
        assertEquals(128, w.get("pitchCount").getAsInt(), "a long loop reads 128 steps at 1/16 note");
        assertFalse(read.get("windowCoversLoop").getAsBoolean());
        assertTrue(read.get("continuation").getAsString().contains("startStep=128"), "a continuation, not coarser");
        assertTrue(w.get("derived").getAsBoolean());
        assertEquals(1, read.getAsJsonArray("notes").size());

        LauncherFake g = new LauncherFake();
        clipAt(g, 0);
        JsonObject whole = g.call("notes.read", target(g, 0));
        assertEquals(.125, whole.getAsJsonObject("window").get("stepSizeBeats").getAsDouble());
        assertEquals(32, whole.getAsJsonObject("window").get("steps").getAsInt(), "a short loop reads at 1/32 note");
        assertTrue(whole.get("windowCoversLoop").getAsBoolean());
        assertFalse(whole.has("continuation"));
    }

    // ---- reads ----

    @Test
    void denseReadReportsItsResponseBoundary() {
        LauncherFake f = new LauncherFake();
        clipAt(f, 0);
        f.slots[0].length = 8;
        for (int x = 0; x < 32; x++) for (int pitch = 0; pitch < 128; pitch++) f.put(0, 0, x, pitch, 100, .25);
        JsonObject p = window(f);
        p.addProperty("steps", 32);
        JsonObject out = f.call("notes.read", p);
        assertEquals(4096, out.get("notesInWindow").getAsInt(), "a dense window reports the actual total");
        assertEquals(2048, out.getAsJsonArray("notes").size(), "the response stays bounded");
        assertFalse(out.get("completeWindow").getAsBoolean());
        assertTrue(out.has("continuation"), "a dense partial read is explicit");
        assertTrue(out.toString().getBytes(StandardCharsets.UTF_8).length < RpcServer.MAX_RESPONSE_BYTES);
    }

    @Test
    void noteRowsUseEditUnitsAndCarryNoConstantProse() {
        LauncherFake f = new LauncherFake();
        clipAt(f, 0);
        f.put(0, 0, 4, 61, 100, 1.5);
        JsonObject read = f.call("notes.read", window(f));
        JsonObject row = read.getAsJsonArray("notes").get(0).getAsJsonObject();
        assertEquals(Set.of("beat", "pitch", "velocity", "durationBeats"), row.keySet());
        assertEquals(100, row.get("velocity").getAsInt(), "velocity is MIDI 1..127");
        assertEquals(1.0, row.get("beat").getAsDouble(), "beat is absolute");
        for (String gone : new String[] {
            "gridReadinessLimitation",
            "onsetPrecision",
            "selectionSideEffect",
            "identityVerification",
            "completeClip",
            "scope"
        }) assertFalse(read.has(gone), gone + " is not repeated per response");
        assertTrue(read.has("gridReadiness") && read.has("completeWindow"));
        assertEquals(0, read.getAsJsonObject("window").get("channel").getAsInt());

        f.put(0, 0, 0, 70, 60, 4);
        f.slots[0].length = 8;
        JsonObject later = window(f);
        later.addProperty("startStep", 2);
        later.addProperty("steps", 4);
        JsonArray sustains = f.call("notes.read", later).getAsJsonArray("continuingAtWindowStart");
        assertEquals(1, sustains.size());
        for (JsonElement v : sustains)
            assertEquals("NoteSustain", v.getAsJsonObject().get("state").getAsString(), "marked, not a copyable onset");
        assertEquals(1, NoteGrid.midiVelocity(1 / 127.0));
        assertEquals(127, NoteGrid.midiVelocity(1.0));
        assertEquals(1, NoteGrid.midiVelocity(0.0));
    }

    @Test
    void delayedGridPropagationKeepsReadinessExplicit() {
        LauncherFake f = new LauncherFake();
        clipAt(f, 0);
        f.slots[0].length = 32;
        f.put(0, 0, 0, 60, 100, .25);
        f.put(0, 0, 16, 72, 80, .5);
        long before = f.call("notes.read", window(f)).get("gridGeneration").getAsLong();
        f.deferGridChanges = true;
        JsonObject p = window(f);
        p.addProperty("startStep", 16);
        p.addProperty("stepSizeBeats", .5);
        HostOperation delayed = f.start("notes.read", p);
        assertTrue(f.start == 0 && f.size == .25, "the fixture retains the old grid after reconfiguration");
        f.next();
        f.applyPendingGrid();
        f.drain(delayed);
        JsonObject out = delayed.result.join();
        assertNotNull(find(out, 72, 8), "the grid delivered during settling yields the new window content");
        assertTrue(out.get("gridGeneration").getAsLong() > before, "a window change gets a distinct generation");
        assertEquals(
                "scheduled_settling_not_acknowledged", out.get("gridReadiness").getAsString());
        assertFalse(out.has("gridReadinessLimitation") || out.has("onsetPrecision") || out.has("selectionSideEffect"));

        f.emitNotes = false;
        editing(p, array(set(8, 72, 90, .5)));
        HostOperation changed = f.start("notes.edit", p);
        f.observer.noteStepChanged(f.note(0, 0, 72)); // an old-generation callback while the new binding is unsettled
        f.applyPendingGrid();
        f.drain(changed);
        JsonObject unverified =
                changed.result.join().getAsJsonArray("unverifiedEdits").get(0).getAsJsonObject();
        assertEquals("unknown", unverified.get("outcome").getAsString(), "a prior-window callback cannot verify");
    }

    @Test
    void firstGridReadWaitsForNativeDebounce() {
        LauncherFake f = new LauncherFake();
        clipAt(f, 0);
        f.noteStorageStepSize = .25;
        f.gridRefreshDelayMillis = 100;
        f.put(0, 0, 0, 60, 96, 1);
        f.put(0, 0, 8, 64, 96, 1);
        JsonObject p = window(f);
        p.addProperty("stepSizeBeats", .125);
        p.addProperty("steps", 32);
        JsonObject out = f.call("notes.read", p);
        assertEquals(2, out.getAsJsonArray("notes").size(), "the first grid read preserves both stored notes");
        assertNotNull(find(out, 64, 2), "an eighth-beat read must not relabel the old quarter-beat cache");
        assertEquals(0, f.writes, "a grid refresh does not change note content");
    }

    // ---- edits ----

    @Test
    void matchingEditsPreserveExistingNotes() {
        LauncherFake f = new LauncherFake();
        clipAt(f, 0);
        f.put(0, 0, 0, 60, 100, .5);
        LauncherFake.Note existing = f.notes.get(LauncherFake.key(0, 0, 0, 60));
        JsonObject out = f.call("notes.edit", editing(window(f), array(set(0, 60, 100, .5))));
        assertEquals(0, f.writes, "a matching set neither replaces the note nor clears its expression");
        assertSame(existing, f.notes.get(LauncherFake.key(0, 0, 0, 60)));
        assertEquals("already_matched", out.get("outcome").getAsString());
    }

    @Test
    void echoingAReadRowIsAlreadyMatched() {
        LauncherFake f = new LauncherFake();
        clipAt(f, 0);
        f.put(0, 0, 0, 60, 64, .5);
        f.notes.get(LauncherFake.key(0, 0, 0, 60)).exact = 0.5; // GUI value 0.5 reads as MIDI 64
        JsonObject row =
                f.call("notes.read", window(f)).getAsJsonArray("notes").get(0).getAsJsonObject();
        assertEquals(64, row.get("velocity").getAsInt());
        JsonObject out = f.call(
                "notes.edit",
                editing(
                        window(f),
                        array(set(
                                row.get("beat").getAsDouble(),
                                row.get("pitch").getAsInt(),
                                row.get("velocity").getAsInt(),
                                row.get("durationBeats").getAsDouble()))));
        assertEquals(0, f.writes);
        assertEquals(1, out.getAsJsonObject("summary").get("alreadyMatched").getAsInt());
        assertEquals(0, out.getAsJsonObject("summary").get("dispatched").getAsInt());
        assertEquals("already_matched", out.get("outcome").getAsString());
    }

    @Test
    void clearingAndSettingAreReadBackPerCell() {
        LauncherFake f = riff();
        JsonObject p = window(f);
        p.addProperty("detail", "full");
        editing(p, array(clear(0, 60), set(1, 61, 70, .5)));
        JsonObject out = f.call("notes.edit", p);
        assertEquals("applied", out.get("outcome").getAsString());
        JsonArray edits = out.getAsJsonArray("edits");
        assertEquals(2, edits.size());
        assertEquals("clear", edits.get(0).getAsJsonObject().get("op").getAsString());
        assertEquals(0, edits.get(0).getAsJsonObject().get("step").getAsInt());
        assertEquals(4, edits.get(1).getAsJsonObject().get("step").getAsInt());
        assertFalse(f.notes.containsKey(LauncherFake.key(0, 0, 0, 60)));
        assertTrue(f.notes.containsKey(LauncherFake.key(0, 0, 4, 61)));
        assertEquals(2, f.writes);
        assertTrue(out.has("notes"), "full detail lists the window");
    }

    @Test
    void compactEditReportsSummaryAndOnlyUnverifiedEdits() {
        LauncherFake f = new LauncherFake();
        clipAt(f, 0);
        f.slots[0].length = 16;
        JsonObject p = manyEdits(f, 32);
        JsonObject out = f.call("notes.edit", p);
        JsonObject sum = out.getAsJsonObject("summary");
        assertEquals("applied", out.get("outcome").getAsString());
        assertEquals(32, sum.get("requested").getAsInt());
        assertEquals(32, sum.get("dispatched").getAsInt());
        assertEquals(0, sum.get("alreadyMatched").getAsInt());
        assertEquals(32, sum.get("verified").getAsInt());
        assertEquals(0, sum.get("unverified").getAsInt());
        assertFalse(out.has("edits") || out.has("unverifiedEdits") || out.has("notes"));
        assertEquals(32, out.get("notesInWindow").getAsInt());
        int compact = out.toString().length();

        JsonObject again = f.call("notes.edit", p);
        assertEquals("already_matched", again.get("outcome").getAsString());
        assertEquals(32, again.getAsJsonObject("summary").get("alreadyMatched").getAsInt());
        assertEquals(0, again.getAsJsonObject("summary").get("dispatched").getAsInt());

        JsonObject full = manyEdits(f, 32);
        full.addProperty("detail", "full");
        JsonObject fullOut = f.call("notes.edit", full);
        assertEquals(32, fullOut.getAsJsonArray("edits").size());
        assertEquals(32, fullOut.getAsJsonArray("notes").size());
        assertEquals("already_matched", fullOut.get("outcome").getAsString());
        assertTrue(compact * 3 < fullOut.toString().length(), "compact is much smaller");

        LauncherFake silent = new LauncherFake();
        clipAt(silent, 0);
        silent.emitNotes = false;
        JsonObject bad = silent.call("notes.edit", manyEdits(silent, 3));
        assertEquals("unknown", bad.get("outcome").getAsString(), "the fail-closed outcome is unchanged");
        assertEquals(3, bad.getAsJsonObject("summary").get("unverified").getAsInt());
        assertEquals(3, bad.getAsJsonArray("unverifiedEdits").size());
        JsonObject item = bad.getAsJsonArray("unverifiedEdits").get(0).getAsJsonObject();
        assertEquals("unknown", item.get("outcome").getAsString());
        assertEquals(48, item.get("observed").getAsJsonObject().get("pitch").getAsInt());
        assertEquals(0.0, item.get("beat").getAsDouble());
    }

    @Test
    void aFreshObservationThatDiffersIsAReadbackMismatch() {
        LauncherFake f = new LauncherFake();
        clipAt(f, 0);
        f.emitNotes = false;
        f.put(0, 0, 0, 60, 100, .5);
        LauncherFake.Note old = f.notes.get(LauncherFake.key(0, 0, 0, 60));
        HostOperation op = f.start("notes.edit", editing(window(f), array(set(0, 60, 50, .5))));
        while (f.writes < 1) f.next();
        f.notes.put(LauncherFake.key(0, 0, 0, 60), old);
        f.observer.noteStepChanged(f.note(0, 0, 60));
        f.drain(op);
        JsonObject out = op.result.join();
        assertEquals("unknown", out.get("outcome").getAsString());
        assertEquals("readback_mismatch", out.get("reason").getAsString());
        assertEquals(
                "readback_mismatch",
                out.getAsJsonArray("unverifiedEdits")
                        .get(0)
                        .getAsJsonObject()
                        .get("reason")
                        .getAsString());
    }

    @Test
    void loopStartBoundsTheEdit() {
        LauncherFake f = new LauncherFake();
        clipAt(f, 0);
        f.loopStart = 1;
        assertUnknown("invalid_params", f.startAndDrain("notes.edit", editing(window(f), array(set(0, 60, 100, .5)))));
        assertEquals(0, f.writes, "notes before the observed loop start are rejected before any mutation");
        assertEquals(
                "applied",
                f.call("notes.edit", editing(window(f), array(set(1, 60, 100, .5))))
                        .get("outcome")
                        .getAsString());
        assertEquals(1, f.writes, "the loop start bound is inclusive");
        JsonObject past = window(f);
        past.addProperty("steps", 64);
        assertUnknown("invalid_params", f.startAndDrain("notes.edit", editing(past, array(set(4.75, 60, 100, .5)))));
        assertEquals(1, f.writes, "a note past the loop end is rejected too");
    }

    @Test
    void anEditCannotAddressASustainOrAnOverlap() {
        LauncherFake f = new LauncherFake();
        clipAt(f, 0);
        f.put(0, 0, 0, 60, 100, 1);
        assertUnknown("overlap", f.startAndDrain("notes.edit", editing(window(f), array(set(.25, 60, 100, .25)))));
        assertUnknown("overlap", f.startAndDrain("notes.edit", editing(window(f), array(clear(.25, 60)))));
        f.put(0, 0, 8, 61, 100, .25);
        assertUnknown("overlap", f.startAndDrain("notes.edit", editing(window(f), array(set(1.5, 61, 90, 1)))));
        assertEquals(0, f.writes, "unrelated onsets are never overwritten");
    }

    @Test
    void lateOrMissingObservationsRemainDispatched() {
        LauncherFake f = new LauncherFake();
        clipAt(f, 0);
        f.emitNotes = false;
        JsonObject p = editFixture(f);
        p.addProperty("detail", "full");
        JsonObject out = f.call("notes.edit", p);
        assertEquals("unknown", out.get("outcome").getAsString());
        assertEquals("not_observed", out.get("reason").getAsString(), "readback without fresh callbacks");
        JsonObject unverified = out.getAsJsonArray("edits").get(0).getAsJsonObject();
        assertEquals("unknown", unverified.get("outcome").getAsString());
        assertEquals("not_observed", unverified.get("reason").getAsString());
        assertEquals(5, out.getAsJsonArray("notes").size(), "a partial outcome still exposes the readable notes");

        LauncherFake g = new LauncherFake();
        clipAt(g, 0);
        g.failWriteAt = 2;
        assertUnknown("host_refused", g.startAndDrain("notes.edit", p));
        assertEquals(1, g.notes.size(), "the first applied note is preserved; no rollback or replay");
    }

    // ---- guards ----

    @Test
    void parallelSelectionIsRejectedWhileCursorEditRuns() {
        LauncherFake f = new LauncherFake();
        clipAt(f, 0);
        HostOperation edit = f.start("notes.edit", editFixture(f));
        assertFalse(edit.result.isDone());
        assertTrue(f.services.selection.held(), "a pending cursor edit holds the selection lease");
        RpcException e = assertRpcError("busy", f.services.selection::requireFree);
        assertFalse(e.unknown, "select methods fail fast, not applied");
        f.drain(edit);
        assertEquals("applied", edit.result.join().get("outcome").getAsString());
        f.services.selection.requireFree();
        assertFalse(f.services.selection.held(), "the lease is released with the operation");
    }

    @Test
    void identityAndDeadlineGuards() {
        LauncherFake f = new LauncherFake();
        clipAt(f, 0);
        clipAt(f, 1);
        HostOperation op = f.start("notes.edit", editFixture(f));
        f.next(); // the cursor identity is observed, the settled binding is scheduled
        f.selected = 1;
        f.drain(op);
        assertUnknown("not_observed", op);
        assertEquals(0, f.writes, "another selected slot cannot receive explicit writes");

        f = new LauncherFake();
        clipAt(f, 0);
        HostOperation expired = new HostOperation(f.tasks.now() - 1);
        f.services.scheduler.schedule(
                expired,
                () -> {
                    throw new AssertionError("an expired operation ran");
                },
                0);
        f.drain(expired);
        assertRpcError("deadline", expired);
        assertEquals(0, f.writes, "expired operations never write");

        f = new LauncherFake();
        clipAt(f, 0);
        op = f.start("notes.edit", editFixture(f));
        f.trackId = "22222222-2222-2222-2222-222222222222";
        f.drain(op);
        assertTrue(op.result.join().has("reason"), "a track change aborts the edit");
        assertEquals(0, f.writes, "a track UUID change aborts the cursor edit");
    }

    @Test
    void selectionDispatchPreventsNotAppliedFailure() {
        LauncherFake f = new LauncherFake();
        clipAt(f, 1);
        assertEquals(0, f.selected, "the scenario starts with a different selected clip");
        HostOperation op = f.start("notes.read", window(f, 1));
        assertEquals(1, f.selected, "the note read already changed the selected clip");
        f.trackId = "22222222-2222-2222-2222-222222222222";
        f.drain(op);
        assertEquals("unknown", op.result.join().get("outcome").getAsString());
        assertTrue(op.dispatched());
        assertEquals(0, f.writes, "the context failure occurs before any note edit");
    }

    @Test
    void aClipThatNeverBindsIsUnknownNotObserved() {
        LauncherFake f = new LauncherFake();
        clipAt(f, 0);
        f.onSelect = () -> f.slots[0].content = false;
        HostOperation op = f.startAndDrain("notes.read", window(f));
        assertTrue(op.dispatched(), "selecting already counts as a dispatch");
        assertUnknown("not_observed", op);
        assertEquals(0, f.writes);
    }

    @Test
    void cursorLeaseDoesNotBlockLauncherReads() {
        LauncherFake f = new LauncherFake();
        clipAt(f, 0);
        HostOperation first = f.start("notes.read", window(f));
        assertRpcError("busy", f.start("notes.read", window(f)));
        JsonObject list = f.call("launcher.list", listParams(f));
        assertEquals(16, list.getAsJsonArray("slots").size(), "metadata reads proceed while a cursor edit is pending");
        f.drain(first);
        assertTrue(first.result.join().getAsJsonArray("notes").isEmpty(), "an empty actual grid read completes");
        f.call("notes.read", window(f));
    }

    private static JsonObject listParams(LauncherFake f) {
        JsonObject p = new JsonObject();
        p.add("target", f.track());
        return p;
    }

    @Test
    void unreadableGridsFailClosed() {
        LauncherFake grid = riff();
        grid.unreadableFromStep = 0;
        JsonObject edit = editing(window(grid), array(set(0, 60, 100, .5)));
        assertUnknown("not_ready", grid.startAndDrain("notes.edit", edit));
        grid = riff();
        grid.unreadableFromStep = 1;
        assertUnknown("not_ready", grid.startAndDrain("notes.edit", edit));
        assertEquals(0, grid.writes);
        grid = riff();
        grid.unreadableFromStep = 0;
        assertUnknown("not_ready", grid.startAndDrain("notes.read", window(grid)));
    }

    @Test
    void anEmptyOrMissingSlotIsNotFoundBeforeAnythingIsSelected() {
        LauncherFake f = new LauncherFake();
        f.scenes = 40;
        HostOperation empty = f.startAndDrain("notes.read", target(f, 5));
        assertRpcError("target_not_found", empty);
        assertFalse(empty.dispatched());
        JsonObject beyond = new JsonObject();
        beyond.add("target", Refs.launcherClip(f.track(), Refs.scene(50, null), "Riff"));
        assertRpcError("target_not_found", f.startAndDrain("notes.edit", editing(beyond, array(set(0, 60, 1, .25)))));
        assertEquals(0, f.selected);
        assertEquals(0, f.scrolls, "nothing scrolled for a scene that does not exist");
    }

    // ---- scenes beyond the first window, stale refs, holds ----

    private static LauncherFake farScene() {
        LauncherFake f = new LauncherFake();
        f.scenes = 40;
        f.sceneNames[33] = "Drop";
        clipAt(f, 33);
        f.put(33, 0, 4, 61, 100, 1);
        return f;
    }

    @Test
    void notesReadBeyondTheFirstWindowCarriesTheClipRefAndTheWindowRests() {
        LauncherFake f = farScene();
        JsonObject read = f.call("notes.read", window(f, 33));
        assertEquals(f.clip(33), read.get("ref").getAsJsonObject());
        assertFalse(read.has("trackId") || read.has("slotIndex"));
        assertEquals(1, read.getAsJsonArray("notes").size());
        assertEquals(
                1.0,
                read.getAsJsonArray("notes")
                        .get(0)
                        .getAsJsonObject()
                        .get("beat")
                        .getAsDouble());
        assertEquals(33, f.selected);
        assertEquals(24, f.bankOffset, "the window showed scene 33 while the clip was read");
    }

    @Test
    void notesEditBeyondTheFirstWindowCarriesTheClipRefAndTheWindowRests() {
        LauncherFake f = farScene();
        JsonObject out = f.call("notes.edit", editing(window(f, 33), array(set(2, 65, 90, .5))));
        assertEquals("applied", out.get("outcome").getAsString());
        assertEquals(f.clip(33), out.get("ref").getAsJsonObject(), "the result carries the clip ref");
        assertFalse(out.has("trackId") || out.has("slotIndex"));
        assertTrue(f.notes.containsKey(LauncherFake.key(33, 0, 8, 65)), "written into the clip of scene 33");
        assertEquals(24, f.bankOffset);
        assertEquals(
                2, f.call("notes.read", window(f, 33)).getAsJsonArray("notes").size());
    }

    @Test
    void aStaleSceneNameIsRefusedWithTheCurrentRefBeforeAnythingIsSelected() {
        for (String method : new String[] {"notes.read", "notes.edit"}) {
            LauncherFake f = farScene();
            JsonObject p = window(f, 33);
            if (method.equals("notes.edit")) editing(p, array(set(2, 65, 90, .5)));
            f.sceneNames[33] = "Break";
            HostOperation op = f.startAndDrain(method, p);
            RpcException error = assertRpcError("target_changed", op);
            assertEquals(
                    "Break",
                    error.current
                            .getAsJsonObject("slot")
                            .getAsJsonObject("scene")
                            .get("name")
                            .getAsString());
            assertFalse(op.dispatched());
            assertEquals(0, f.selected, "nothing was selected");
            assertEquals(0, f.writes);
        }
        LauncherFake f = farScene();
        JsonObject p = window(f, 33);
        f.slots[33].name = "Other";
        RpcException error = assertRpcError("target_changed", f.startAndDrain("notes.read", p));
        assertEquals("Other", error.current.get("name").getAsString(), "a renamed clip names its current ref");
    }

    @Test
    void anEditIsRefusedWhileAMutationIsUnresolvedForThatAbsoluteScene() {
        LauncherFake f = farScene();
        f.slots[33].content = false;
        f.failCreate = true;
        JsonObject create = obj("{\"loop\":{\"lengthBeats\":4},\"name\":\"Hook\"}");
        create.add("to", f.slot(33));
        assertUnknown("host_refused", f.startAndDrain("clips.insert", create));
        assertEquals(1, f.services.holds.list().size(), "the failed create holds scene 33");
        clipAt(f, 33);
        clipAt(f, 3);
        HostOperation refused = f.startAndDrain("notes.edit", editing(window(f, 33), array(set(2, 65, 90, .5))));
        RpcException error = assertRpcError("unresolved", refused);
        assertTrue(error.getMessage().contains("scene 33"), "names the absolute scene: " + error.getMessage());
        assertFalse(refused.dispatched(), "a held scene is refused before anything is selected");
        assertEquals(0, f.writes);
        assertEquals(
                1, f.call("notes.read", window(f, 33)).getAsJsonArray("notes").size(), "reads still work");
        assertEquals(
                "applied",
                f.call("notes.edit", editing(window(f, 3), array(set(2, 65, 90, .5))))
                        .get("outcome")
                        .getAsString(),
                "another scene of the same track is not held");
    }

    @Test
    void anEditIsRefusedWhileASettingsWriteIsUnresolved() {
        LauncherFake f = farScene();
        clipAt(f, 3);
        f.settings.failWrite = true;
        JsonObject write = new JsonObject();
        write.add("target", f.clip(3));
        write.addProperty("nextAction", "stop");
        HostOperation settings = f.startAndDrain("clips.set", write);
        assertTrue(settings.result.isDone());
        assertEquals(1, f.services.holds.list().size(), "the failed settings write is held");
        HostOperation refused = f.startAndDrain("notes.edit", editing(window(f, 33), array(set(2, 65, 90, .5))));
        assertRpcError("unresolved", refused);
        assertFalse(refused.dispatched());
        assertEquals(0, f.writes);
        assertEquals(0, f.selected);
    }

    @Test
    void anEditFacingAnotherWindowIsBusyWhileTheLeaseIsHeld() {
        LauncherFake f = farScene();
        clipAt(f, 3);
        HostOperation first = f.start("notes.read", window(f, 3));
        assertFalse(first.result.isDone());
        HostOperation other = f.start("notes.edit", editing(window(f, 33), array(set(2, 65, 90, .5))));
        assertRpcError("busy", other);
        assertFalse(other.dispatched());
        f.drain(first);
        assertTrue(first.result.join().has("notes"), "the first read is undisturbed");
    }
}
