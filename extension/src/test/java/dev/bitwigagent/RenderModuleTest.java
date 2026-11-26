package dev.bitwigagent;

import static dev.bitwigagent.RpcAssert.*;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/** Host-free export.audio/bounce.inPlace: planning, header checks and a simulated native job; not native acceptance. */
public final class RenderModuleTest {
    static final String A = HostFixture.A, B = HostFixture.B;
    private static final List<Path> temporary = new ArrayList<>();

    @AfterAll
    static void deleteTemporaryTrees() throws Exception {
        for (Path tree : temporary) delete(tree);
    }

    @Test
    void validationIsStrict() {
        RenderModule.validateParams("export.audio", export("/tmp", "master"));
        RenderModule.validateParams("bounce.inPlace", bounce(A));
        RenderModule.validateParams("render.status", new JsonObject());
        JsonObject flac24 = export("/tmp", A);
        flac24.addProperty("format", "flac");
        flac24.addProperty("bitDepth", 24);
        RenderModule.validateParams("export.audio", flac24);
        List<JsonObject> bad = new ArrayList<>();
        JsonObject p;
        p = export("/tmp", "master");
        p.add("sources", new JsonArray());
        bad.add(p);
        p = export("/tmp", "master", "master");
        bad.add(p);
        p = export("/tmp", A, A.toUpperCase());
        bad.add(p);
        p = export("/tmp", "Master");
        bad.add(p);
        p = export("relative", "master");
        bad.add(p);
        p = export("/tmp/../x", "master");
        bad.add(p);
        for (String name : List.of("", ".hidden", " a", "a ", "a/b", "a\\b", "x".repeat(129))) {
            p = export("/tmp", "master");
            p.addProperty("baseName", name);
            bad.add(p);
        }
        p = export("/tmp", "master");
        p.addProperty("endBeats", 0);
        bad.add(p);
        p = export("/tmp", "master");
        p.addProperty("endBeats", 65537);
        bad.add(p);
        p = export("/tmp", "master");
        p.addProperty("format", "mp3");
        bad.add(p);
        p = export("/tmp", "master");
        p.addProperty("bitDepth", 20);
        bad.add(p);
        p = flac24.deepCopy();
        p.addProperty("bitDepth", 32);
        bad.add(p);
        p = export("/tmp", "master");
        p.addProperty("sampleRate", 96000);
        bad.add(p);
        p = export("/tmp", "master");
        p.addProperty("tap", "pre_fx");
        bad.add(p);
        p = export("/tmp", "master");
        p.addProperty("overwrite", "yes");
        bad.add(p);
        p = export("/tmp", "master");
        p.addProperty("realtime", true);
        bad.add(p);
        for (JsonObject b : bad) invalid("export.audio", b);
        invalid("bounce.inPlace", bounce("master"));
        p = bounce(A);
        p.addProperty("tap", "custom");
        invalid("bounce.inPlace", p);
        p = bounce(A);
        p.remove("tap");
        invalid("bounce.inPlace", p);
        p = bounce(A, A);
        invalid("bounce.inPlace", p);
        p = new JsonObject();
        p.addProperty("x", 1);
        invalid("render.status", p);
        assertTrue(HostFixture.SPECS.methods().containsAll(RenderModule.METHODS), "render methods registered");
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
        assertNotApplied("target_exists", () -> RenderPlan.export(dir, "Mix", 1, "wav", false));
        assertTrue(
                RenderPlan.export(dir, "Mix", 1, "wav", true).before.get(0).exists,
                "overwrite records the old file for change detection");
        Files.createDirectory(dir.resolve("Dir.wav"));
        assertNotApplied("invalid_target", () -> RenderPlan.export(dir, "Dir", 1, "wav", true));
        assertNotApplied("invalid_target", () -> RenderPlan.export(dir.resolve("missing"), "Mix", 1, "wav", false));
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
    void exportIsObservedOnlyWithLatchAndValidFiles() throws IOException {
        Sim sim = new Sim();
        JsonObject p = export(sim.dir.toString(), "master", A);
        p.addProperty("bitDepth", 16);
        p.addProperty("sampleRate", 44100);
        p.addProperty("tap", "pre_fader");
        p.addProperty("dither", true);
        HostOperation op = sim.start("export.audio", p, 5000);
        sim.drainUntil(op);
        JsonObject result = op.result.join();
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
                result.get("status").getAsString().equals("observed")
                        && result.get("exported").getAsBoolean()
                        && result.getAsJsonArray("files").size() == 2
                        && result.get("mayOpenFileManager").getAsBoolean(),
                "observed export with verified files");
        assertTrue(
                result.getAsJsonArray("files")
                        .get(1)
                        .getAsJsonObject()
                        .get("source")
                        .getAsString()
                        .equals(A),
                "rows keep source order");
        assertTrue(sim.module.unresolved().isEmpty(), "completed job is not unresolved");
        JsonObject status = sim.call("render.status");
        assertTrue(
                status.getAsJsonObject("job").get("state").getAsString().equals("completed")
                        && status.getAsJsonObject("job").get("verified").getAsBoolean(),
                "render.status re-verifies a completed job");
        HostOperation again = sim.start("export.audio", export(sim.dir.toString(), "master", A), 5000);
        sim.drainUntil(again);
        assertRpcError("target_exists", again);
        assertTrue(sim.dispatches == 1, "existing files refused before dispatch");

        Sim playing = new Sim();
        playing.fixture.transport.child("isPlaying").emit(true);
        HostOperation refused = playing.start("export.audio", export(playing.dir.toString(), "master"), 5000);
        playing.drainUntil(refused);
        assertRpcError("unsafe_state", refused);
        assertTrue(playing.dispatches == 0, "playing transport refuses before dispatch");
    }

    @Test
    void longExportStaysBusyAndIsPolled() throws IOException {
        Sim sim = new Sim();
        sim.finish = false;
        HostOperation op = sim.start("export.audio", export(sim.dir.toString(), "master"), 200);
        sim.drainUntil(op);
        JsonObject result = op.result.join();
        assertTrue(
                result.get("status").getAsString().equals("dispatched")
                        && result.get("outcome").getAsString().equals("unknown")
                        && result.get("nativeCompletion").getAsString().equals("pending")
                        && result.get("poll").getAsString().equals("render.status"),
                "no latch within the deadline: dispatched/unknown with a poll method");
        assertTrue(sim.module.unresolved().size() == 1, "a running job is unresolved");
        HostOperation busy = sim.start("bounce.inPlace", bounce(A), 5000);
        sim.drainUntil(busy);
        assertRpcError("busy", busy);
        assertTrue(
                sim.call("render.status")
                        .getAsJsonObject("job")
                        .get("state")
                        .getAsString()
                        .equals("running"),
                "status reports running");
        sim.complete();
        JsonObject status = sim.call("render.status");
        assertTrue(
                status.getAsJsonObject("job").get("state").getAsString().equals("completed")
                        && status.getAsJsonObject("job").get("verified").getAsBoolean()
                        && sim.module.unresolved().isEmpty(),
                "after the latch the polled job verifies and nothing is unresolved");
        assertTrue(sim.dispatches == 1, "never re-dispatched");
    }

    @Test
    void refusalsBeforeDispatchAreNotApplied() throws IOException {
        Sim sim = new Sim();
        sim.refusal = "render_needs_confirmation";
        HostOperation op = sim.start("export.audio", export(sim.dir.toString(), "master"), 5000);
        sim.drainUntil(op);
        assertTrue(
                assertRpcError("render_needs_confirmation", op).outcome.equals("not_applied"),
                "native preflight refusal is not_applied");
        assertTrue(
                sim.call("render.status").get("job").isJsonNull()
                        && sim.module.unresolved().isEmpty(),
                "a refused job leaves no status or unresolved entry");
        sim.refusal = null;
        HostOperation next = sim.start("export.audio", export(sim.dir.toString(), "master"), 5000);
        sim.drainUntil(next);
        assertTrue(next.result.join().get("exported").getAsBoolean(), "the next export is not blocked");
    }

    @Test
    void completedExportWithoutFilesFails() throws IOException {
        Sim sim = new Sim();
        sim.write = false;
        HostOperation op = sim.start("export.audio", export(sim.dir.toString(), "master"), 5000);
        sim.drainUntil(op);
        assertTrue(
                assertRpcError("export_failed", op).outcome.equals("not_applied"),
                "latch without files: nothing was written");
        Sim garbage = new Sim();
        garbage.garbage = true;
        op = garbage.start("export.audio", export(garbage.dir.toString(), "master"), 5000);
        garbage.drainUntil(op);
        assertTrue(
                assertRpcError("export_failed", op).outcome.equals("unknown"), "written but invalid output is unknown");
        Sim deleted = new Sim();
        deleted.write = false;
        deleted.deleteOld = true;
        Files.write(deleted.dir.resolve("Mix.wav"), new byte[] {1, 2});
        JsonObject p = export(deleted.dir.toString(), "master");
        p.addProperty("overwrite", true);
        op = deleted.start("export.audio", p, 5000);
        deleted.drainUntil(op);
        assertTrue(
                assertRpcError("export_failed", op).outcome.equals("unknown"),
                "an overwritten file that is now gone is unknown, not not_applied");
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
        HostOperation op = sim.start("bounce.inPlace", bounce(A), 5000);
        sim.drainUntil(op);
        JsonObject result = op.result.join();
        assertTrue(sim.dispatches == 1 && sim.tap.equals("pre_fader"), "one bounce dispatch with the requested tap");
        JsonObject typed = result.getAsJsonArray("tracks").get(0).getAsJsonObject();
        assertTrue(
                typed.get("trackTypeBefore").getAsString().equals("Instrument")
                        && typed.get("trackTypeAfter").getAsString().equals("Hybrid")
                        && typed.has("warning"),
                "an instrument track turned Hybrid by the bounce is reported");
        JsonObject track = result.getAsJsonArray("tracks").get(0).getAsJsonObject();
        assertTrue(
                result.get("bounced").getAsBoolean()
                        && result.get("nativeUndo").getAsBoolean()
                        && track.getAsJsonObject("clip")
                                .get("kind")
                                .getAsString()
                                .equals("audio")
                        && track.get("clipsBefore").getAsInt() == 2,
                "bounced only with one new audio clip over the range");
        // A later edit on the lane must not turn the verified job into a quarantine.
        sim.clips.put(A, lanes(clip(64, 4, "Other", "instrument")));
        assertTrue(
                !sim.call("render.status")
                                .getAsJsonObject("job")
                                .get("verified")
                                .getAsBoolean()
                        && sim.module.unresolved().isEmpty(),
                "re-reading a reported job reports, never quarantines");

        Sim partial = new Sim();
        JsonObject longClip = clip(-8, 40, "Long", "instrument");
        partial.clips.put(A, lanes(longClip));
        partial.afterBounce = before -> lanes(
                clip(-8, 8, "Long", "instrument"), clip(0, 16, "Long", "audio"), clip(16, 16, "Long", "instrument"));
        op = partial.start("bounce.inPlace", bounce(A), 5000);
        partial.drainUntil(op);
        assertTrue(
                op.result.join().get("bounced").getAsBoolean(),
                "a clip cut at both range edges leaves new remnants outside the range: still verified");

        Sim again = new Sim();
        again.clips.put(A, lanes(clip(0, 16, "Bounce", "audio")));
        again.afterBounce = before -> lanes(clip(0, 16, "Bounce", "audio"));
        op = again.start("bounce.inPlace", bounce(A), 5000);
        again.drainUntil(op);
        assertTrue(
                op.result.join().get("bounced").getAsBoolean(),
                "re-bouncing a bounced range is a new clip object, not 'unchanged'");

        Sim none = new Sim();
        none.module.applyGraceNanos = 0;
        op = none.start("bounce.inPlace", bounce(A), 5000);
        none.drainUntil(op);
        assertTrue(
                assertRpcError("bounce_failed", op).outcome.equals("not_applied")
                        && none.module.unresolved().isEmpty(),
                "a finished job that changed nothing is not_applied");
    }

    @Test
    void unverifiedBounceQuarantinesBounces() throws IOException {
        Sim sim = new Sim();
        sim.afterBounce = before -> lanes(clip(0, 8, "Half", "audio"));
        HostOperation op = sim.start("bounce.inPlace", bounce(A), 5000);
        sim.drainUntil(op);
        assertTrue(
                assertRpcError("bounce_verification_failed", op).outcome.equals("unknown"),
                "a different lane is unknown");
        assertTrue(sim.module.unresolved().size() == 1, "unknown bounce is listed for extension.restart");
        HostOperation again = sim.start("bounce.inPlace", bounce(A), 5000);
        sim.drainUntil(again);
        assertRpcError("unresolved_mutation", again);
        HostOperation export = sim.start("export.audio", export(sim.dir.toString(), "master"), 5000);
        sim.drainUntil(export);
        assertTrue(
                export.result.join().get("exported").getAsBoolean() && sim.dispatches == 2, "exports stay available");
    }

    @Test
    void lateBounceMustBeVerifiedBeforeAnother() throws IOException {
        Sim sim = new Sim();
        sim.finish = false;
        sim.afterBounce = before -> lanes(clip(0, 16, "Bounce", "audio"));
        HostOperation op = sim.start("bounce.inPlace", bounce(A), 200);
        sim.drainUntil(op);
        assertTrue(
                op.result.join().get("status").getAsString().equals("dispatched"),
                "bounce without latch by the deadline is dispatched");
        sim.completeBounce();
        assertTrue(sim.module.unresolved().size() == 1, "a finished but unverified bounce stays listed");
        HostOperation again = sim.start("bounce.inPlace", bounce(B), 5000);
        sim.drainUntil(again);
        assertRpcError("unresolved_mutation", again);
        HostOperation export = sim.start("export.audio", export(sim.dir.toString(), "master"), 5000);
        sim.drainUntil(export);
        assertRpcError("unresolved_mutation", export);
        JsonObject status = sim.call("render.status");
        assertTrue(
                status.getAsJsonObject("job").get("verified").getAsBoolean()
                        && sim.module.unresolved().isEmpty(),
                "render.status verifies and clears it");
        sim.finish = true;
        again = sim.start("bounce.inPlace", bounce(B), 5000);
        sim.drainUntil(again);
        assertTrue(
                again.result.join().get("bounced").getAsBoolean() && sim.dispatches == 2,
                "the next bounce runs after verification");
    }

    /** Simulated native side: a job that writes its files or replaces lanes, and completes when told. */
    private static final class Sim {
        final HostFixture fixture = new HostFixture();
        final Deque<Runnable> document = new ArrayDeque<>();
        final Path dir;
        final RenderModule module;
        /** Lane per track; the JsonObject instances stand in for native clip objects (identity). */
        final Map<String, List<JsonObject>> clips = new HashMap<>();

        java.util.function.Function<List<JsonObject>, List<JsonObject>> afterBounce = before -> new ArrayList<>();
        boolean finish = true, write = true, garbage, deleteOld, done;
        String refusal, tap;
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
            module = new RenderModule(fixture.services, new RenderModule.Access() {
                public void execute(Runnable work) {
                    document.add(work);
                }

                public String nativeJarSha256() {
                    return "simulated";
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
                    if (refusal != null) throw Errors.of(refusal, "simulated refusal", Outcome.NOT_APPLIED);
                    before.run();
                    dispatches++;
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
            });
        }

        void complete() {
            try {
                if (deleteOld) for (String base : paths) Files.deleteIfExists(Path.of(base + "." + options.format));
                if (write)
                    for (String base : paths) {
                        Path file = Path.of(base + "." + options.format);
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
            return fixture.submit(module, method, p, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs));
        }

        JsonObject call(String method) {
            HostOperation op = start(method, new JsonObject(), 5000);
            drainUntil(op);
            return op.result.join();
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
        for (String source : sources) list.add(source);
        p.add("sources", list);
        p.addProperty("directory", dir);
        p.addProperty("baseName", "Mix");
        p.addProperty("startBeats", 0);
        p.addProperty("endBeats", 16);
        p.addProperty("format", "wav");
        return p;
    }

    private static JsonObject bounce(String... ids) {
        JsonObject p = new JsonObject();
        JsonArray list = new JsonArray();
        for (String id : ids) list.add(id);
        p.add("trackIds", list);
        p.addProperty("startBeats", 0);
        p.addProperty("endBeats", 16);
        p.addProperty("tap", "pre_fader");
        return p;
    }

    private static JsonObject clip(double start, double length, String name, String kind) {
        JsonObject c = new JsonObject();
        c.addProperty("startBeats", start);
        c.addProperty("lengthBeats", length);
        c.addProperty("endBeats", start + length);
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

    private static void invalid(String method, JsonObject params) {
        assertRpcError("invalid_params", () -> RenderModule.validateParams(method, params));
    }
}
