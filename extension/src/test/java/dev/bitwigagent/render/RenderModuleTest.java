package dev.bitwigagent.render;

import static dev.bitwigagent.RpcAssert.*;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import dev.bitwigagent.HostBridge;
import dev.bitwigagent.HostFixture;
import dev.bitwigagent.TestPaths;
import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.Holds;
import dev.bitwigagent.core.HostOperation;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.core.Refs;
import dev.bitwigagent.project.ProjectSaveAsPlan;
import dev.bitwigagent.recovery.JobsModule;
import dev.bitwigagent.rpc.RpcServer;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/** Host-free export.audio/tracks.bounce: planning, header checks and a simulated native job; not native acceptance. */
public final class RenderModuleTest {
    static final String A = HostFixture.A, B = HostFixture.B;
    private static final List<Path> temporary = new ArrayList<>();

    @AfterAll
    static void deleteTemporaryTrees() throws Exception {
        for (Path tree : temporary) delete(tree);
    }

    @Test
    void validationIsStrict() {
        valid("export.audio", export(TestPaths.absolute("/tmp"), "master"));
        valid("tracks.bounce", bounce(A));
        JsonObject flac24 = export(TestPaths.absolute("/tmp"), A);
        flac24.addProperty("fileType", "flac");
        flac24.addProperty("bitDepth", 24);
        valid("export.audio", flac24);
        List<JsonObject> bad = new ArrayList<>();
        JsonObject p;
        p = export(TestPaths.absolute("/tmp"), "master");
        p.add("sources", new JsonArray());
        bad.add(p);
        p = export(TestPaths.absolute("/tmp"), "master", "master");
        bad.add(p);
        p = export(TestPaths.absolute("/tmp"), A, A.toUpperCase());
        bad.add(p);
        p = export(TestPaths.absolute("/tmp"), "Master");
        bad.add(p);
        p = export("relative", "master");
        bad.add(p);
        p = export("/tmp/../x", "master");
        bad.add(p);
        for (String name : List.of("", ".hidden", " a", "a ", "a/b", "a\\b", "x".repeat(129))) {
            p = export(TestPaths.absolute("/tmp"), "master");
            p.addProperty("baseName", name);
            bad.add(p);
        }
        p = export(TestPaths.absolute("/tmp"), "master");
        p.addProperty("end", 0);
        bad.add(p);
        p = export(TestPaths.absolute("/tmp"), "master");
        p.addProperty("end", 65537);
        bad.add(p);
        p = export(TestPaths.absolute("/tmp"), "master");
        p.addProperty("fileType", "mp3");
        bad.add(p);
        p = export(TestPaths.absolute("/tmp"), "master");
        p.addProperty("bitDepth", 20);
        bad.add(p);
        p = flac24.deepCopy();
        p.addProperty("bitDepth", 32);
        bad.add(p);
        p = export(TestPaths.absolute("/tmp"), "master");
        p.addProperty("sampleRate", 96000);
        bad.add(p);
        p = export(TestPaths.absolute("/tmp"), "master");
        p.addProperty("tap", "pre_fx");
        bad.add(p);
        p = export(TestPaths.absolute("/tmp"), "master");
        p.addProperty("overwrite", "yes");
        bad.add(p);
        p = export(TestPaths.absolute("/tmp"), "master");
        p.addProperty("realtime", true);
        bad.add(p);
        for (JsonObject b : bad) invalid("export.audio", b);
        p = bounce(A);
        p.getAsJsonArray("targets").set(0, new JsonPrimitive("master"));
        invalid("tracks.bounce", p);
        p = export(TestPaths.absolute("/tmp"), "master");
        p.getAsJsonArray("sources").set(0, new JsonPrimitive(A));
        invalid("export.audio", p);
        p = export(TestPaths.absolute("/tmp"), "master");
        p.addProperty("format", "wav");
        invalid("export.audio", p);
        p = bounce(A);
        p.addProperty("inPlace", false);
        invalid("tracks.bounce", p);
        p = bounce(A);
        p.remove("inPlace");
        valid("tracks.bounce", p);
        p = bounce(A);
        p.addProperty("tap", "custom");
        invalid("tracks.bounce", p);
        p = bounce(A);
        p.remove("tap");
        invalid("tracks.bounce", p);
        p = bounce(A, A);
        invalid("tracks.bounce", p);
        assertTrue(HostFixture.SPECS.methods().containsAll(RenderModule.METHODS), "render methods registered");
    }

    @Test
    void aRenamedTrackRefStopsABounceButNotAnExport() throws IOException {
        Sim sim = new Sim();
        JsonObject renamed = bounce(A);
        renamed.getAsJsonArray("targets").get(0).getAsJsonObject().addProperty("name", "Old name");
        HostOperation op = sim.start("tracks.bounce", renamed, 5000);
        sim.drainUntil(op);
        assertEquals(ref(A), assertRpcError("target_changed", op).current, "the error carries the current ref");
        JsonObject export = export(sim.dir.toString(), "master", A);
        export.getAsJsonArray("sources").get(1).getAsJsonObject().addProperty("name", "Old name");
        assertTrue(sim.dispatches == 0, "a stale ref is refused before dispatch");
        op = sim.start("export.audio", export, 5000);
        sim.drainUntil(op);
        JsonObject result = assertOutcome("applied", op);
        assertEquals(
                ref(A),
                result.getAsJsonArray("files").get(1).getAsJsonObject().get("source"),
                "an export source is identified by its id: a renamed track still renders, and the row has the current ref");
    }

    @Test
    void ungatedHostIsUnavailable() throws IOException {
        assertRpcError("unknown_method", () -> RenderModule.validateParams("render.status", new JsonObject()));
        HostFixture fixture = new HostFixture();
        RenderModule module = fixture.services.render(null);
        JsonObject capability = module.capability(new HostOperation(fixture.tasks.after(5000)));
        assertTrue(
                !capability.get("available").getAsBoolean() && capability.has("reason"),
                "capabilities report the binding unavailable off the gated host");
        HostOperation op = fixture.submit(
                module, "export.audio", export(temp("bwa-render-gate").toString(), "master"));
        fixture.next();
        assertRpcError("host_mismatch", op);
    }

    @Test
    void planningNamesFilesAndRefusesExisting() throws IOException {
        Path dir = temp("bwa-render-plan");
        RenderPlan one = RenderPlan.export(dir, "Mix", 1, "wav", false);
        assertTrue(
                one.files.equals(List.of(dir.resolve("Mix.wav")))
                        && one.basePaths()[0].equals(dir.resolve("Mix").toString()),
                "one source: <baseName>.<format>, dispatched without the extension Bitwig appends");
        RenderPlan stems = RenderPlan.export(dir, "Stem", 3, "flac", false);
        assertTrue(
                stems.files.get(0).equals(dir.resolve("Stem-01.flac"))
                        && stems.files.get(2).equals(dir.resolve("Stem-03.flac")),
                "several: -01, -02, ...");
        Files.write(dir.resolve("Mix.wav"), new byte[] {1});
        assertRpcError("target_exists", () -> RenderPlan.export(dir, "Mix", 1, "wav", false));
        assertTrue(
                RenderPlan.export(dir, "Mix", 1, "wav", true).before.get(0).exists,
                "overwrite records the old file for change detection");
        Files.createDirectory(dir.resolve("Dir.wav"));
        assertRpcError("invalid_target", () -> RenderPlan.export(dir, "Dir", 1, "wav", true));
        assertRpcError("invalid_target", () -> RenderPlan.export(dir.resolve("missing"), "Mix", 1, "wav", false));
        Path locked = Files.createDirectory(dir.resolve("locked"));
        assertTrue(locked.toFile().setWritable(false), "directory made read-only");
        if (!Files.isWritable(locked))
            assertRpcError("invalid_target", () -> RenderPlan.export(locked, "Mix", 1, "wav", false));
        assertTrue(locked.toFile().setWritable(true), "directory writable again for cleanup");
    }

    @Test
    void headersAreCheckedNotJustFiles() throws IOException {
        Path dir = temp("bwa-render-headers");
        ProjectSaveAsPlan.Stat none = ProjectSaveAsPlan.Stat.of(dir.resolve("none"));
        Path pcm = wav(dir.resolve("a.wav"), 1, 2, 48000, 24, 100);
        JsonObject row = RenderPlan.inspect(pcm, none, "wav", 24, 48000);
        assertTrue(
                row.get("valid").getAsBoolean()
                        && row.get("frames").getAsLong() == 100
                        && row.get("channels").getAsInt() == 2
                        && row.get("encoding").getAsString().equals("pcm"),
                "24-bit PCM WAV parsed from its chunks");
        assertTrue(!RenderPlan.inspect(pcm, none, "wav", 16, 0).get("valid").getAsBoolean(), "bit depth must match");
        assertTrue(
                RenderPlan.inspect(pcm, none, "wav", 24, 44100)
                        .get("problem")
                        .getAsString()
                        .equals("sample_rate_mismatch"),
                "sample rate must match when given");
        assertTrue(
                RenderPlan.inspect(pcm, ProjectSaveAsPlan.Stat.of(pcm), "wav", 24, 0)
                        .get("problem")
                        .getAsString()
                        .equals("not_written_by_this_export"),
                "an untouched pre-existing file is not this export's output");
        Path fl = wav(dir.resolve("f.wav"), 3, 2, 44100, 32, 10);
        assertTrue(
                RenderPlan.inspect(fl, none, "wav", 32, 44100)
                        .get("encoding")
                        .getAsString()
                        .equals("float"),
                "32-bit float WAV");
        assertTrue(
                RenderPlan.inspect(wav(dir.resolve("e.wav"), 1, 2, 48000, 24, 0), none, "wav", 24, 0)
                        .get("problem")
                        .getAsString()
                        .equals("no_audio_frames"),
                "empty data chunk fails");
        Path junk = Files.write(dir.resolve("j.wav"), new byte[64]);
        assertTrue(
                RenderPlan.inspect(junk, none, "wav", 24, 0)
                        .get("problem")
                        .getAsString()
                        .equals("not_riff_wav"),
                "nonempty garbage fails");
        Path flac = flac(dir.resolve("a.flac"), 48000, 2, 24, 4800);
        row = RenderPlan.inspect(flac, none, "flac", 24, 48000);
        assertTrue(
                row.get("valid").getAsBoolean()
                        && row.get("frames").getAsLong() == 4800
                        && row.get("durationSeconds").getAsDouble() == 0.1,
                "FLAC STREAMINFO parsed");
    }

    @Test
    void exportWarnsWhenALauncherTrackHasNoArrangerClipInTheRange() throws IOException {
        Sim bare = new Sim();
        bare.launcherContent = true;
        HostOperation op = bare.start("export.audio", export(bare.dir.toString(), "master", A), 5000);
        bare.drainUntil(op);
        JsonArray warnings = assertOutcome("applied", op).getAsJsonArray("warnings");
        assertTrue(
                warnings.size() == 1
                        && warnings.get(0).getAsString().contains(A)
                        && warnings.get(0).getAsString().contains("clips.copy")
                        && warnings.get(0).getAsString().contains("follows the arranger"),
                "launcher clips and an empty arranger lane warn, naming the track and the fix");

        Sim covered = new Sim();
        covered.launcherContent = true;
        covered.clips.put(A, lanes(clip(8, 16, "Verse", "instrument")));
        HostOperation fine = covered.start("export.audio", export(covered.dir.toString(), "master", A), 5000);
        covered.drainUntil(fine);
        assertFalse(assertOutcome("applied", fine).has("warnings"), "an overlapping arranger clip is enough");

        RenderModule.LauncherContent only = slot -> true;
        assertTrue(only.playing(0) == null && only.stopped(0) == null, "an unobserved state defaults to null");
        for (Boolean content : new Boolean[] {false, null}) {
            Sim quiet = new Sim();
            quiet.launcherContent = content;
            HostOperation none = quiet.start("export.audio", export(quiet.dir.toString(), "master", A), 5000);
            quiet.drainUntil(none);
            assertFalse(
                    assertOutcome("applied", none).has("warnings"),
                    "no launcher content, or none observed: no warning");
        }
    }

    @Test
    void exportWarnsPerTrackWhenItsRenderDiffersFromTheArranger() throws IOException {
        // Playing a launcher clip overrides the arranger, whether or not the arranger holds a clip in range.
        for (boolean covered : new boolean[] {false, true}) {
            Sim playing = new Sim();
            playing.launcherContent = true;
            playing.launcherPlaying = true;
            if (covered) playing.clips.put(A, lanes(clip(8, 16, "Verse", "instrument")));
            HostOperation op = playing.start("export.audio", export(playing.dir.toString(), "master", A), 5000);
            playing.drainUntil(op);
            JsonObject result = assertOutcome("applied", op);
            String warning = result.getAsJsonArray("warnings").get(0).getAsString();
            assertTrue(
                    result.getAsJsonArray("warnings").size() == 1
                            && warning.contains(A)
                            && warning.contains("playing a launcher clip")
                            && warning.contains("switch_playback_to_arranger_for_all_tracks")
                            && warning.contains("clips.copy") == !covered,
                    "a playing launcher clip warns with the switch fix, and the copy fix only for an empty arranger");
            assertTrue(
                    result.get("next").getAsString().contains("switch_playback_to_arranger_for_all_tracks"),
                    "next names the fix");
        }

        // Stopped from the launcher: silent even with arranger clips in range, and the copy fix only when it is empty.
        Sim stopped = new Sim();
        stopped.launcherContent = true;
        stopped.launcherStopped = true;
        stopped.clips.put(A, lanes(clip(8, 16, "Verse", "instrument")));
        HostOperation op = stopped.start("export.audio", export(stopped.dir.toString(), "master", A), 5000);
        stopped.drainUntil(op);
        JsonObject result = assertOutcome("applied", op);
        String warning = result.getAsJsonArray("warnings").get(0).getAsString();
        assertTrue(
                warning.contains("stopped from the launcher")
                        && warning.contains("renders silent")
                        && warning.contains("switch_playback_to_arranger_for_all_tracks")
                        && !warning.contains("clips.copy")
                        && !result.get("next").getAsString().contains("clips.copy"),
                "a launcher-stopped track warns even with an arranger clip in range");

        // Following the arranger with the launcher unobserved or idle and nothing to copy: no warning, no next.
        Sim following = new Sim();
        following.launcherContent = false;
        following.launcherPlaying = false;
        following.launcherStopped = false;
        HostOperation quiet = following.start("export.audio", export(following.dir.toString(), "master", A), 5000);
        following.drainUntil(quiet);
        JsonObject clean = assertOutcome("applied", quiet);
        assertFalse(clean.has("warnings") || clean.has("next"), "a track following its arranger is not warned");
    }

    @Test
    void overlapIsHalfOpen() {
        JsonArray rows = new JsonArray();
        rows.add(clip(0, 8, "a", "audio"));
        assertTrue(RenderModule.overlaps(rows, 4, 16));
        assertTrue(!RenderModule.overlaps(rows, 8, 16), "a clip ending at the start does not overlap");
        assertTrue(!RenderModule.overlaps(rows, -4, 0), "a clip starting at the end does not overlap");
    }

    @Test
    void exportIsAppliedOnlyWithLatchAndValidFiles() throws IOException {
        Sim sim = new Sim();
        JsonObject p = export(sim.dir.toString(), "master", A);
        p.addProperty("bitDepth", 16);
        p.addProperty("sampleRate", 44100);
        p.addProperty("tap", "pre_fader");
        p.addProperty("dither", true);
        HostOperation op = sim.start("export.audio", p, 5000);
        sim.drainUntil(op);
        JsonObject result = assertOutcome("applied", op);
        assertTrue(
                sim.dispatches == 1 && sim.sources.get(0) == null && sim.sources.get(1) != null,
                "one dispatch; master is the null source, the track its proxy");
        assertTrue(
                sim.options.bitDepth == 16
                        && sim.options.sampleRate == 44100
                        && sim.options.tap.equals("pre_fader")
                        && sim.options.dither
                        && sim.options.start == 0
                        && sim.options.end == 16,
                "options reach the native call unchanged");
        assertTrue(sim.paths[0].equals(sim.dir.resolve("Mix-01").toString()), "extensionless base paths");
        assertTrue(
                result.getAsJsonArray("files").size() == 2
                        && result.get("mayOpenFileManager").getAsBoolean()
                        && !result.has("status"),
                "applied export with verified files");
        assertTrue(
                result.getAsJsonArray("files")
                        .get(1)
                        .getAsJsonObject()
                        .get("source")
                        .equals(ref(A)),
                "rows keep source order");
        assertTrue(sim.holds().isEmpty(), "a completed job holds nothing");
        JsonObject job = sim.job("render:1");
        assertTrue(
                job.get("id").getAsString().equals("render:1")
                        && job.get("method").getAsString().equals("export.audio")
                        && job.get("state").getAsString().equals("completed")
                        && job.get("outcome").getAsString().equals("applied")
                        && job.getAsJsonArray("files").size() == 2,
                "jobs.get re-verifies a completed job");
        long elapsed = job.get("elapsedMs").getAsLong();
        sim.fixture.tasks.advance(10_000_000_000L);
        assertEquals(
                elapsed, sim.job("render:1").get("elapsedMs").getAsLong(), "a completed job's elapsedMs is frozen");
        HostOperation again = sim.start("export.audio", export(sim.dir.toString(), "master", A), 5000);
        sim.drainUntil(again);
        assertRpcError("target_exists", again);
        assertTrue(sim.dispatches == 1, "existing files refused before dispatch");
        assertTrue(
                sim.job("render:1").get("outcome").getAsString().equals("applied"),
                "a job refused before dispatch keeps the previous job current");
        assertRpcError("target_not_found", sim.jobsGet("render:2"));

        Sim playing = new Sim();
        playing.fixture.transport.child("isPlaying").emit(true);
        HostOperation refused = playing.start("export.audio", export(playing.dir.toString(), "master"), 5000);
        playing.drainUntil(refused);
        assertRpcError("unsafe_state", refused);
        assertTrue(playing.dispatches == 0, "playing transport refuses before dispatch");
    }

    @Test
    void longExportStaysBusyAndIsFollowedWithJobsGet() throws IOException {
        Sim sim = new Sim();
        sim.finish = false;
        HostOperation op = sim.start("export.audio", export(sim.dir.toString(), "master"), 200);
        sim.drainUntil(op);
        JsonObject result = assertUnknown("deadline", op);
        assertTrue(
                result.get("job").getAsString().equals("render:1")
                        && result.get("next").getAsString().contains("jobs.get render:1")
                        && result.get("nativeCompletion").getAsString().equals("pending")
                        && result.getAsJsonArray("plannedFiles").size() == 1
                        && !result.has("status")
                        && !result.has("poll"),
                "no latch within the deadline: unknown/deadline with the job id");
        assertTrue(
                sim.holds().size() == 1
                        && sim.holds().get(0).ackable
                        && sim.holds().get(0).description.contains("jobs.get render:1"),
                "a running job whose request returned is a job hold ack may give up");
        HostOperation busy = sim.start("tracks.bounce", bounce(A), 5000);
        sim.drainUntil(busy);
        assertTrue(assertRpcError("busy", busy).next.contains("jobs.get render:1"), "busy points at jobs.get");
        assertTrue(sim.job("render:1").get("state").getAsString().equals("running"), "jobs.get reports running");
        assertTrue(!sim.job("render:1").has("outcome"), "a running job has no outcome yet");
        assertRpcError("target_not_found", sim.jobsGet("render:9"));
        assertRpcError("target_not_found", sim.jobsGet("saveAs:1"));
        assertRpcError("target_not_found", sim.jobsGet("render"));
        sim.complete();
        JsonObject job = sim.job("render:1");
        assertTrue(
                job.get("state").getAsString().equals("completed")
                        && job.get("outcome").getAsString().equals("applied")
                        && sim.holds().isEmpty(),
                "after the latch the job verifies and nothing is held");
        assertTrue(sim.dispatches == 1, "never re-dispatched");
    }

    @Test
    void refusalsBeforeDispatchAreErrors() throws IOException {
        Sim sim = new Sim();
        sim.refusal = Reason.NEEDS_CONFIRMATION;
        HostOperation op = sim.start("export.audio", export(sim.dir.toString(), "master"), 5000);
        sim.drainUntil(op);
        assertTrue(!assertRpcError("needs_confirmation", op).unknown, "native preflight refusal changes nothing");
        assertRpcError("target_not_found", sim.jobsGet("render:1"));
        assertTrue(sim.holds().isEmpty(), "a refused job leaves no job or hold");
        sim.refusal = null;
        HostOperation next = sim.start("export.audio", export(sim.dir.toString(), "master"), 5000);
        sim.drainUntil(next);
        assertOutcome("applied", next);
    }

    @Test
    void dispatchWithoutNativeJobIsUnknown() throws IOException {
        Sim sim = new Sim();
        sim.lost = true;
        HostOperation op = sim.start("export.audio", export(sim.dir.toString(), "master"), 5000);
        sim.drainUntil(op);
        assertUnknown("job_failed", op);
        JsonObject job = sim.job("render:1");
        assertTrue(
                job.get("state").getAsString().equals("unknown") && job.has("hint") && !job.has("outcome"),
                "a dispatched call without a native job is state unknown");
        assertTrue(sim.holds().isEmpty(), "a lost export does not quarantine");

        Sim bounce = new Sim();
        bounce.lost = true;
        op = bounce.start("tracks.bounce", bounce(A), 5000);
        bounce.drainUntil(op);
        assertTrue(
                assertUnknown("job_failed", op) != null
                        && bounce.holds().size() == 1
                        && bounce.holds().get(0).ackable,
                "a lost bounce quarantines bounces");
    }

    @Test
    void aLostBounceReleasedByAckAdmitsTheNextRender() throws IOException {
        Sim bounce = new Sim();
        bounce.lost = true;
        HostOperation op = bounce.start("tracks.bounce", bounce(A), 5000);
        bounce.drainUntil(op);
        assertEquals("render:1", assertUnknown("job_failed", op).get("job").getAsString(), "it names its job");
        bounce.fixture.services.holds.ack("render.quarantine");
        bounce.lost = false;
        bounce.job("render:1");
        assertTrue(bounce.holds().isEmpty(), "nothing is held after the ack");
        HostOperation export = bounce.start("export.audio", export(bounce.dir.toString(), "master"), 5000);
        bounce.drainUntil(export);
        assertOutcome("applied", export);
    }

    @Test
    void completedExportWithoutFilesFails() throws IOException {
        Sim sim = new Sim();
        sim.write = false;
        HostOperation op = sim.start("export.audio", export(sim.dir.toString(), "master"), 5000);
        sim.drainUntil(op);
        assertTrue(!assertRpcError("job_failed", op).unknown, "latch without files: nothing was written");
        JsonObject job = sim.job("render:1");
        assertTrue(
                job.get("outcome").getAsString().equals("not_applied")
                        && job.get("reason").getAsString().equals("job_failed"),
                "jobs.get reports the same evaluation");
        Sim garbage = new Sim();
        garbage.garbage = true;
        op = garbage.start("export.audio", export(garbage.dir.toString(), "master"), 5000);
        garbage.drainUntil(op);
        assertUnknown("job_failed", op);
        job = garbage.job("render:1");
        assertTrue(
                job.get("outcome").getAsString().equals("unknown")
                        && job.get("reason").getAsString().equals("job_failed"),
                "written but invalid output is unknown");
        Sim deleted = new Sim();
        deleted.write = false;
        deleted.deleteOld = true;
        Files.write(deleted.dir.resolve("Mix.wav"), new byte[] {1, 2});
        JsonObject p = export(deleted.dir.toString(), "master");
        p.addProperty("overwrite", true);
        op = deleted.start("export.audio", p, 5000);
        deleted.drainUntil(op);
        assertUnknown("job_failed", op);
    }

    @Test
    void bounceIsVerifiedByArrangerReadback() throws IOException {
        Sim sim = new Sim();
        JsonObject tail = clip(32, 4, "Tail", "instrument");
        sim.clips.put(A, lanes(clip(0, 8, "Verse", "instrument"), tail));
        sim.fixture.tracks[0].child("trackType").emit("Instrument");
        sim.afterBounce = before -> {
            sim.fixture.tracks[0].child("trackType").emit("Hybrid");
            return lanes(clip(0, 16, "Bounce", "audio"), tail);
        };
        HostOperation op = sim.start("tracks.bounce", bounce(A), 5000);
        sim.drainUntil(op);
        JsonObject result = assertOutcome("applied", op);
        assertTrue(sim.dispatches == 1 && sim.tap.equals("pre_fader"), "one bounce dispatch with the requested tap");
        JsonObject track = result.getAsJsonArray("tracks").get(0).getAsJsonObject();
        assertEquals(ref(A), track.get("track"), "the row names the track by its ref");
        assertEquals(
                Refs.arrangerClip(ref(A), 0, "Bounce"),
                track.get("clipRef"),
                "the new clip is named by its arranger ref");
        assertTrue(
                track.get("trackTypeBefore").getAsString().equals("Instrument")
                        && track.get("trackTypeAfter").getAsString().equals("Hybrid")
                        && track.has("warning"),
                "an instrument track turned Hybrid by the bounce is reported");
        assertTrue(
                result.get("nativeUndo").getAsBoolean()
                        && track.getAsJsonObject("clip")
                                .get("kind")
                                .getAsString()
                                .equals("audio")
                        && track.get("clipsBefore").getAsInt() == 2,
                "applied only with one new audio clip over the range");
        sim.clips.put(A, lanes(clip(64, 4, "Other", "instrument")));
        assertTrue(
                sim.job("render:1").get("outcome").getAsString().equals("unknown")
                        && sim.holds().isEmpty(),
                "re-reading a reported job reports, never quarantines");

        Sim partial = new Sim();
        JsonObject longClip = clip(-8, 40, "Long", "instrument");
        partial.clips.put(A, lanes(longClip));
        partial.afterBounce = before -> lanes(
                clip(-8, 8, "Long", "instrument"), clip(0, 16, "Long", "audio"), clip(16, 16, "Long", "instrument"));
        op = partial.start("tracks.bounce", bounce(A), 5000);
        partial.drainUntil(op);
        assertOutcome("applied", op);

        Sim again = new Sim();
        again.clips.put(A, lanes(clip(0, 16, "Bounce", "audio")));
        again.afterBounce = before -> lanes(clip(0, 16, "Bounce", "audio"));
        op = again.start("tracks.bounce", bounce(A), 5000);
        again.drainUntil(op);
        assertOutcome("applied", op);

        Sim none = new Sim();
        none.module.applyGraceNanos = 0;
        op = none.start("tracks.bounce", bounce(A), 5000);
        none.drainUntil(op);
        assertTrue(
                !assertRpcError("job_failed", op).unknown && none.holds().isEmpty(),
                "a finished job that changed nothing is an error");

        Sim slow = new Sim();
        op = slow.start("tracks.bounce", bounce(A), 200);
        slow.drainUntil(op);
        assertTrue(
                assertUnknown("deadline", op)
                        .get("nativeCompletion")
                        .getAsString()
                        .equals("completed"),
                "a finished bounce whose lane has not changed within the deadline is followed with jobs.get");
        JsonObject job = slow.job("render:1");
        assertTrue(
                job.get("outcome").getAsString().equals("not_applied")
                        && job.get("reason").getAsString().equals("job_failed")
                        && slow.holds().isEmpty(),
                "jobs.get decides it: nothing changed");
    }

    @Test
    void unverifiedBounceHoldsBouncesUntilAck() throws IOException {
        Sim sim = new Sim();
        sim.afterBounce = before -> lanes(clip(0, 8, "Half", "audio"));
        HostOperation op = sim.start("tracks.bounce", bounce(A), 5000);
        sim.drainUntil(op);
        assertTrue(
                assertUnknown("readback_mismatch", op)
                        .get("message")
                        .getAsString()
                        .contains("ack render.quarantine"),
                "a different lane is unknown and names the hold");
        assertTrue(
                sim.holds().size() == 1
                        && sim.holds().get(0).ackable
                        && sim.holds().get(0).id.equals("render.quarantine"),
                "the unknown bounce is an unresolved hold");
        HostOperation again = sim.start("tracks.bounce", bounce(A), 5000);
        sim.drainUntil(again);
        String next = assertRpcError("unresolved", again).next;
        assertTrue(
                next.contains("arranger.list") && next.contains("ack render.quarantine"),
                "the refusal says what to inspect and what to ack");
        HostOperation export = sim.start("export.audio", export(sim.dir.toString(), "master"), 5000);
        sim.drainUntil(export);
        assertTrue(assertOutcome("applied", export) != null && sim.dispatches == 2, "exports stay available");
        sim.fixture.services.holds.ack("render.quarantine");
        sim.afterBounce = before -> lanes(clip(0, 16, "Bounce", "audio"));
        again = sim.start("tracks.bounce", bounce(A), 5000);
        sim.drainUntil(again);
        assertTrue(assertOutcome("applied", again) != null && sim.dispatches == 3, "ack lifts the hold");
    }

    @Test
    void aRenderTheTransportExpiredBeforeDispatchNeverStarts() throws IOException {
        Sim sim = new Sim();
        HostOperation op = sim.start("export.audio", export(sim.dir.toString(), "master"), 5000);
        assertTrue(((RpcServer.Expiring) op.result).expire(), "the deadline fired while the request still waited");
        sim.drainUntil(op);
        assertRpcError("deadline", op);
        assertTrue(sim.dispatches == 0 && sim.holds().isEmpty(), "nothing was dispatched or held");
        assertRpcError("target_not_found", sim.jobsGet("render:1"));
    }

    @Test
    void ackGivesUpARunningJobWhoseRequestReturned() throws IOException {
        Sim sim = new Sim();
        sim.finish = false;
        HostOperation op = sim.start("export.audio", export(sim.dir.toString(), "master"), 200);
        sim.drainUntil(op);
        assertUnknown("deadline", op);
        sim.fixture.services.holds.ack("render.job");
        assertTrue(sim.holds().isEmpty(), "ack released the running job");
        assertTrue(sim.job("render:1").get("acknowledged").getAsBoolean(), "jobs.get still reports it");
        sim.finish = true;
        HostOperation next = sim.start("export.audio", export(sim.dir.toString(), "master"), 5000);
        sim.drainUntil(next);
        assertTrue(sim.dispatches == 2, "the next render is admitted");
    }

    @Test
    void aBounceAckedWhileRunningMustStillBeVerifiedWhenItFinishes() throws IOException {
        Sim sim = new Sim();
        sim.finish = false;
        sim.afterBounce = before -> lanes(clip(0, 16, "Bounce", "audio"));
        HostOperation op = sim.start("tracks.bounce", bounce(A), 200);
        sim.drainUntil(op);
        assertTrue(
                assertUnknown("deadline", op).get("next").getAsString().contains("jobs.get render:1"),
                "the deadline result names the job");
        sim.fixture.services.holds.ack("render.job");
        assertTrue(sim.holds().isEmpty(), "the running bounce was given up");
        sim.completeBounce();
        assertTrue(
                sim.holds().size() == 1 && sim.holds().get(0).id.equals("render.finished"),
                "its late finish raises the finished hold");
        HostOperation again = sim.start("tracks.bounce", bounce(B), 5000);
        sim.drainUntil(again);
        assertRpcError("busy", again);
        assertTrue(sim.job("render:1").get("outcome").getAsString().equals("applied"), "jobs.get verifies it");
        assertTrue(sim.holds().isEmpty(), "verification clears the hold");
        sim.finish = true;
        again = sim.start("tracks.bounce", bounce(B), 5000);
        sim.drainUntil(again);
        assertOutcome("applied", again);
    }

    @Test
    void lateBounceMustBeVerifiedBeforeAnother() throws IOException {
        Sim sim = new Sim();
        sim.finish = false;
        sim.afterBounce = before -> lanes(clip(0, 16, "Bounce", "audio"));
        HostOperation op = sim.start("tracks.bounce", bounce(A), 200);
        sim.drainUntil(op);
        assertTrue(
                assertUnknown("deadline", op).get("job").getAsString().equals("render:1"),
                "bounce without latch by the deadline returns its job");
        JsonObject running = sim.job("render:1");
        assertTrue(
                running.get("method").getAsString().equals("tracks.bounce")
                        && running.get("state").getAsString().equals("running"),
                "jobs.get reports the new method name while the job runs");
        sim.completeBounce();
        assertTrue(
                sim.holds().size() == 1
                        && sim.holds().get(0).ackable
                        && sim.holds().get(0).description.contains("jobs.get render:1"),
                "a finished but unverified bounce is held until jobs.get or ack");
        HostOperation again = sim.start("tracks.bounce", bounce(B), 5000);
        sim.drainUntil(again);
        assertTrue(assertRpcError("busy", again).next.contains("jobs.get render:1"), "verify first");
        HostOperation export = sim.start("export.audio", export(sim.dir.toString(), "master"), 5000);
        sim.drainUntil(export);
        assertRpcError("busy", export);
        assertTrue(
                sim.job("render:1").get("outcome").getAsString().equals("applied")
                        && sim.holds().isEmpty(),
                "jobs.get verifies and clears it");
        sim.finish = true;
        again = sim.start("tracks.bounce", bounce(B), 5000);
        sim.drainUntil(again);
        assertTrue(
                assertOutcome("applied", again) != null && sim.dispatches == 2,
                "the next bounce runs after verification");

        Sim wrong = new Sim();
        wrong.finish = false;
        wrong.afterBounce = before -> lanes(clip(0, 8, "Half", "audio"));
        op = wrong.start("tracks.bounce", bounce(A), 200);
        wrong.drainUntil(op);
        wrong.completeBounce();
        JsonObject job = wrong.job("render:1");
        assertTrue(
                job.get("outcome").getAsString().equals("unknown")
                        && job.get("reason").getAsString().equals("readback_mismatch")
                        && wrong.holds().size() == 1
                        && wrong.holds().get(0).id.equals("render.quarantine"),
                "a late bounce's first verification decides the quarantine");
    }

    /** Simulated native side: a job that writes its files or replaces lanes, and completes when told. */
    private static final class Sim {
        final HostFixture fixture = new HostFixture();
        final Deque<Runnable> document = new ArrayDeque<>();
        final Path dir;
        final RenderModule module;
        final JobsModule jobs;
        /** Lane per track; the JsonObject instances stand in for native clip objects (identity). */
        final Map<String, List<JsonObject>> clips = new HashMap<>();

        java.util.function.Function<List<JsonObject>, List<JsonObject>> afterBounce = before -> new ArrayList<>();
        boolean finish = true, write = true, garbage, deleteOld, done, lost;
        /** What the launcher reports for every track: null = unobserved. */
        Boolean launcherContent;
        /** Launcher clip playing/queued and launcher-stopped, for every track: null = unobserved. */
        Boolean launcherPlaying, launcherStopped;

        Reason refusal;
        String tap;
        int dispatches;
        List<Object> sources;
        String[] paths;
        RenderModule.Export options;
        List<String> bounced;

        void completeBounce() {
            for (String id : bounced) clips.put(id, afterBounce.apply(clips.getOrDefault(id, new ArrayList<>())));
            done = true;
        }

        Sim() throws IOException {
            dir = temp("bwa-render-sim");
            module = fixture.services.render(
                    new RenderModule.Access() {
                        public void execute(Runnable work) {
                            document.add(work);
                        }

                        public void provenance(JsonObject out) {
                            out.addProperty("nativeVersion", "simulated");
                        }

                        public RenderModule.Handle export(
                                Object project,
                                List<Object> s,
                                List<String> ids,
                                String[] basePaths,
                                RenderModule.Export o,
                                Runnable check,
                                Runnable before) {
                            check.run();
                            if (refusal != null) throw Errors.notApplied(refusal, "simulated refusal");
                            before.run();
                            dispatches++;
                            if (lost) throw Errors.unknown(Reason.JOB_FAILED, "simulated: no job registered");
                            sources = s;
                            paths = basePaths;
                            options = o;
                            done = false;
                            if (finish) complete();
                            return () -> done;
                        }

                        public RenderModule.Handle bounce(
                                Object project,
                                List<Object> tracks,
                                List<String> ids,
                                double start,
                                double end,
                                String t,
                                Runnable check,
                                Runnable before) {
                            check.run();
                            before.run();
                            dispatches++;
                            if (lost) throw Errors.unknown(Reason.JOB_FAILED, "simulated: no job registered");
                            tap = t;
                            done = false;
                            bounced = ids;
                            if (finish) completeBounce();
                            return () -> done;
                        }

                        public RenderModule.Clips clips(Object track, String id, Runnable check) {
                            check.run();
                            List<JsonObject> lane = new ArrayList<>(clips.getOrDefault(id, new ArrayList<>()));
                            JsonArray rows = new JsonArray();
                            for (JsonObject c : lane) rows.add(c.deepCopy());
                            return new RenderModule.Clips(new ArrayList<>(lane), rows);
                        }
                    },
                    new RenderModule.LauncherContent() {
                        public Boolean hasContent(int slot) {
                            return launcherContent;
                        }

                        public Boolean playing(int slot) {
                            return launcherPlaying;
                        }

                        public Boolean stopped(int slot) {
                            return launcherStopped;
                        }
                    });
            jobs = new JobsModule(module);
        }

        void complete() {
            try {
                if (deleteOld) for (String base : paths) Files.deleteIfExists(Path.of(base + "." + options.fileType));
                if (write)
                    for (String base : paths) {
                        Path file = Path.of(base + "." + options.fileType);
                        if (garbage) Files.write(file, new byte[64]);
                        else
                            wav(
                                    file,
                                    options.bitDepth == 32 ? 3 : 1,
                                    2,
                                    options.sampleRate == 0 ? 48000 : options.sampleRate,
                                    options.bitDepth,
                                    480);
                    }
            } catch (IOException error) {
                throw new AssertionError(error);
            }
            done = true;
        }

        HostOperation start(String method, JsonObject p, long timeoutMs) {
            return fixture.submit(module, method, p, fixture.tasks.after(timeoutMs));
        }

        HostOperation jobsGet(String id) {
            JsonObject p = new JsonObject();
            p.addProperty("id", id);
            HostOperation op = fixture.submit(jobs, "jobs.get", p, fixture.tasks.after(5000));
            drainUntil(op);
            return op;
        }

        JsonObject job(String id) {
            return jobsGet(id).result.join().getAsJsonObject("job");
        }

        List<Holds.View> holds() {
            return fixture.services.holds.list();
        }

        void drainUntil(HostOperation op) {
            long spins = 0;
            while (!op.result.isDone() && spins++ < 100000000) {
                if (!document.isEmpty()) document.removeFirst().run();
                else if (!fixture.tasks.isEmpty()) fixture.next();
            }
        }
    }

    private static JsonObject export(String dir, String... sources) {
        JsonObject p = new JsonObject();
        JsonArray list = new JsonArray();
        for (String source : sources) list.add(source.equals("master") ? new JsonPrimitive(source) : ref(source));
        p.add("sources", list);
        p.addProperty("directory", dir);
        p.addProperty("baseName", "Mix");
        p.addProperty("start", 0);
        p.addProperty("end", 16);
        p.addProperty("fileType", "wav");
        return p;
    }

    private static JsonObject bounce(String... ids) {
        JsonObject p = new JsonObject();
        JsonArray list = new JsonArray();
        for (String id : ids) list.add(ref(id));
        p.add("targets", list);
        p.addProperty("inPlace", true);
        p.addProperty("start", 0);
        p.addProperty("end", 16);
        p.addProperty("tap", "pre_fader");
        return p;
    }

    private static JsonObject ref(String id) {
        return Refs.track(id, id.equals(A) ? "Track 0" : "Track 1");
    }

    private static JsonObject clip(double start, double length, String name, String kind) {
        JsonObject c = new JsonObject();
        c.addProperty("start", start);
        c.addProperty("length", length);
        c.addProperty("end", start + length);
        c.addProperty("name", name);
        c.addProperty("kind", kind);
        return c;
    }

    private static List<JsonObject> lanes(JsonObject... clips) {
        return new ArrayList<>(List.of(clips));
    }

    static Path wav(Path file, int format, int channels, int rate, int bits, int frames) throws IOException {
        int block = channels * bits / 8, data = frames * block;
        ByteBuffer b = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes())
                .putInt(36 + data)
                .put("WAVE".getBytes())
                .put("fmt ".getBytes())
                .putInt(16)
                .putShort((short) format)
                .putShort((short) channels)
                .putInt(rate)
                .putInt(rate * block)
                .putShort((short) block)
                .putShort((short) bits)
                .put("data".getBytes())
                .putInt(data);
        return Files.write(file, b.array());
    }

    static Path flac(Path file, int rate, int channels, int bits, long frames) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(4 + 4 + 34 + 16);
        b.put("fLaC".getBytes()).put((byte) 0x80).put((byte) 0).put((byte) 0).put((byte) 34);
        b.putShort((short) 4096).putShort((short) 4096).put(new byte[6]);
        b.putLong(((long) rate << 44) | ((long) (channels - 1) << 41) | ((long) (bits - 1) << 36) | frames);
        b.put(new byte[16]);
        return Files.write(file, b.array());
    }

    private static Path temp(String prefix) throws IOException {
        Path tree = Files.createTempDirectory(prefix);
        temporary.add(tree);
        return tree;
    }

    private static void delete(Path tree) throws IOException {
        try (var walk = Files.walk(tree)) {
            for (Path path : (Iterable<Path>) walk.sorted(Comparator.reverseOrder())::iterator)
                Files.deleteIfExists(path);
        }
    }

    private static void valid(String method, JsonObject params) {
        RenderModule.validateParams(method, HostBridge.checkSchema(HostFixture.SPECS, method, params));
    }

    private static void invalid(String method, JsonObject params) {
        assertRpcError("invalid_params", () -> valid(method, params));
    }
}
