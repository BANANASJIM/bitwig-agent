package dev.bitwigagent.project;

import static dev.bitwigagent.RpcAssert.*;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import dev.bitwigagent.FakeHost;
import dev.bitwigagent.HostFixture;
import dev.bitwigagent.TestPaths;
import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.Holds;
import dev.bitwigagent.core.HostOperation;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.core.RpcException;
import dev.bitwigagent.recovery.JobsModule;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/** Host-free project.saveAs: path planning on a temporary tree and a simulated native save; not native acceptance. */
public final class ProjectModuleTest {
    private static final List<Path> temporary = new ArrayList<>();

    @AfterAll
    static void deleteTemporaryTrees() throws Exception {
        for (Path tree : temporary) delete(tree);
    }

    @Test
    void validationIsStrict() {
        ProjectModule.validateParams(
                "project.saveAs", params(TestPaths.absolute("/home/u/Bitwig Studio/Projects/Song/Song.bwproject")));
        for (String bad : List.of(
                "relative/Song/Song.bwproject",
                "/p/Song/Song.txt",
                "/p/Song/.bwproject",
                "/p/Song/ Song.bwproject",
                "/p/Song/Song .bwproject",
                "/p/.hidden/.hidden.bwproject",
                "/p/template/template.bwproject",
                "/p/Song/auto-backups/Song/Song.bwproject",
                "/p/../Song/Song.bwproject",
                "/Song.bwproject",
                "~/Song/Song.bwproject")) invalid(params(bad), bad);
        invalid(new JsonObject(), "missing path");
        JsonObject extra = params("/p/Song/Song.bwproject");
        extra.addProperty("force", true);
        invalid(extra, "unknown field");
        JsonObject flag = params("/p/Song/Song.bwproject");
        flag.addProperty("overwrite", 1);
        invalid(flag, "overwrite must be boolean");
        flag = params("/p/Song/Song.bwproject");
        flag.addProperty("allowAnyPath", "yes");
        invalid(flag, "allowAnyPath must be boolean");
        JsonObject saveExtra = new JsonObject();
        saveExtra.addProperty("path", "/p/Song/Song.bwproject");
        {
            RpcException expected =
                    assertThrows(RpcException.class, () -> ProjectModule.validateParams("project.save", saveExtra));
            assertTrue(expected.reason.wire().equals("invalid_params"), "project.save still takes no parameters");
        }
    }

    @Test
    void ungatedHostIsUnavailable() throws IOException {
        HostFixture fixture = new HostFixture();
        ProjectModule module = fixture.services.projectModule(null);
        JsonObject capability = module.saveAsCapability(new HostOperation(fixture.tasks.after(5000)));
        assertTrue(
                !capability.get("available").getAsBoolean() && capability.has("reason"),
                "capabilities report the binding unavailable off the gated host");
        HostOperation op = fixture.submit(
                module,
                "project.saveAs",
                params(temp("bwa-gate").resolve("S/S.bwproject").toString()));
        fixture.next();
        assertRpcError("host_mismatch", op);
        assertTrue(fixture.services.holds.list().isEmpty(), "nothing is in flight");
    }

    @Test
    void aNativeSaveThatThrowsAfterDispatchEndsItsJob() throws IOException {
        Sim sim = new Sim();
        sim.throwAfter = true;
        HostOperation op = sim.start(sim.root.resolve("Song/Song.bwproject"), false);
        sim.drain();
        JsonObject result = assertUnknown("job_failed", op);
        assertEquals("saveAs:1", result.get("job").getAsString(), "the unknown result names its job");
        assertTrue(sim.holds().isEmpty(), "the failed job holds nothing");
        JsonObject job = sim.job("saveAs:1");
        assertEquals("unknown", job.get("outcome").getAsString());
        assertEquals("job_failed", job.get("reason").getAsString());
        HostOperation save = sim.call("project.save", 5000);
        assertFalse(save.result.isCompletedExceptionally(), "project.save is admitted again");
    }

    @Test
    void ackGivesUpAJobWhoseRequestReturned() throws IOException {
        Sim silent = new Sim();
        silent.silent = true;
        HostOperation running = silent.start(silent.root.resolve("Song/Song.bwproject"), false, 5000);
        silent.step();
        assertRpcError("busy", () -> silent.fixture.services.holds.ack("project.saveAs"));
        silent.drainUntil(running);
        assertUnknown("deadline", running);
        silent.fixture.services.holds.ack("project.saveAs");
        assertTrue(silent.holds().isEmpty(), "ack released the save hold");
        HostOperation save = silent.call("project.save", 5000);
        assertFalse(save.result.isCompletedExceptionally(), "project.save is no longer busy");
        assertEquals("running", silent.job("saveAs:1").get("state").getAsString(), "jobs.get still follows it");
    }

    @Test
    void aLateCallbackOfAnAckedSaveKeepsTheNextSaveHeld() throws IOException {
        Sim silent = new Sim();
        silent.silent = true;
        HostOperation first = silent.start(silent.root.resolve("Song/Song.bwproject"), false, 150);
        silent.drainUntil(first);
        ProjectModule.Completion stale = silent.late;
        silent.fixture.services.holds.ack("project.saveAs");
        HostOperation second = silent.start(silent.root.resolve("Again/Again.bwproject"), false, 150);
        silent.drainUntil(second);
        assertEquals("saveAs:2", assertUnknown("deadline", second).get("job").getAsString());
        stale.completed();
        assertEquals(1, silent.holds().size(), "the first save's callback leaves the second save held");
        silent.late.completed();
        assertTrue(silent.holds().isEmpty(), "its own callback releases it");
    }

    @Test
    void executeFailureGivesTheJobSlotBack() throws IOException {
        Sim sim = new Sim();
        sim.executeFails = true;
        HostOperation op = sim.start(sim.root.resolve("Song/Song.bwproject"), false);
        sim.drain();
        assertRpcError("not_ready", op);
        assertTrue(sim.holds().isEmpty() && sim.dispatches == 0, "nothing was dispatched or held");
        assertRpcError("target_not_found", sim.jobsGet("saveAs:1"));
    }

    @Test
    void planningMirrorsNativeFolderRules() throws IOException {
        Path home = temp("bwa-saveas"), root = Files.createDirectories(home.resolve("Bitwig Studio/Projects"));
        Files.createFile(root.resolve(ProjectSaveAsPlan.MARKER)); // Bitwig ignores markers in the projects root chain.
        ProjectSaveAsPlan plan =
                ProjectSaveAsPlan.resolve(root.resolve("Song/Song.bwproject"), root, home, false, false);
        assertTrue(
                plan.dispatchPath.equals(root.resolve("Song.bwproject"))
                        && plan.folder.equals(root.resolve("Song"))
                        && plan.newFolder
                        && plan.name.equals("Song")
                        && !plan.before.exists,
                "new project is dispatched as <dir>/Name.bwproject so Bitwig creates <dir>/Name/");
        assertRpcError(
                "invalid_target",
                () -> ProjectSaveAsPlan.resolve(root.resolve("Song.bwproject"), root, home, false, false));
        assertRpcError(
                "invalid_target",
                () -> ProjectSaveAsPlan.resolve(root.resolve("Song/Other.bwproject"), root, home, false, false));
        assertRpcError(
                "invalid_target",
                () -> ProjectSaveAsPlan.resolve(root.resolve("Missing/Song/Song.bwproject"), root, home, false, false));
        assertRpcError(
                "path_not_allowed",
                () -> ProjectSaveAsPlan.resolve(home.resolve("Song/Song.bwproject"), root, home, false, false));
        assertRpcError(
                "path_not_allowed",
                () -> ProjectSaveAsPlan.resolve(root.resolve("Song/Song.bwproject"), null, home, false, false));
        Files.createDirectories(home.resolve("Elsewhere"));
        assertTrue(
                ProjectSaveAsPlan.resolve(home.resolve("Elsewhere/Song/Song.bwproject"), root, home, false, true)
                        .newFolder,
                "allowAnyPath lifts only the root check");
        Path outside = temp("bwa-outside");
        Files.createSymbolicLink(root.resolve("link"), outside);
        assertRpcError(
                "path_not_allowed",
                () -> ProjectSaveAsPlan.resolve(root.resolve("link/Song/Song.bwproject"), root, home, false, false));

        Path existing = Files.createDirectories(root.resolve("Existing"));
        Files.createFile(existing.resolve(ProjectSaveAsPlan.MARKER));
        Files.createDirectories(existing.resolve("versions"));
        plan = ProjectSaveAsPlan.resolve(existing.resolve("Alt.bwproject"), root, home, false, false);
        assertTrue(
                plan.dispatchPath.equals(existing.resolve("Alt.bwproject"))
                        && plan.folder.equals(existing)
                        && !plan.newFolder,
                "inside an existing project folder the file is saved where named");
        assertTrue(
                ProjectSaveAsPlan.resolve(existing.resolve("versions/V2.bwproject"), root, home, false, false)
                        .folder
                        .equals(existing),
                "subfolders belong to the nearest marked project folder");
        assertRpcError(
                "invalid_target",
                () -> ProjectSaveAsPlan.resolve(existing.resolve("missing/V2.bwproject"), root, home, false, false));
        Files.createDirectories(existing.resolve("Folder.bwproject"));
        assertRpcError(
                "invalid_target",
                () -> ProjectSaveAsPlan.resolve(existing.resolve("Folder.bwproject"), root, home, true, false));
        Files.write(existing.resolve("Existing.bwproject"), new byte[] {1, 2, 3});
        assertRpcError(
                "target_exists",
                () -> ProjectSaveAsPlan.resolve(existing.resolve("Existing.bwproject"), root, home, false, false));
        plan = ProjectSaveAsPlan.resolve(existing.resolve("Existing.bwproject"), root, home, true, false);
        assertTrue(
                plan.before.exists && plan.before.size == 3,
                "overwrite records the previous file for change detection");

        Files.createDirectories(root.resolve("Empty"));
        plan = ProjectSaveAsPlan.resolve(root.resolve("Empty/Empty.bwproject"), root, home, false, false);
        assertTrue(
                !plan.newFolder && plan.dispatchPath.equals(root.resolve("Empty.bwproject")),
                "an empty unmarked folder is adopted like Bitwig does");
        Files.createDirectories(root.resolve("Busy"));
        Files.createFile(root.resolve("Busy/notes.txt"));
        assertRpcError(
                "target_exists",
                () -> ProjectSaveAsPlan.resolve(root.resolve("Busy/Busy.bwproject"), root, home, true, false));

        Path stray = Files.createFile(home.resolve("Bitwig Studio").resolve(ProjectSaveAsPlan.MARKER));
        assertRpcError(
                "invalid_target",
                () -> ProjectSaveAsPlan.resolve(root.resolve("Fresh/Fresh.bwproject"), root, home, false, false));
        Files.delete(stray);

        ProjectSaveAsPlan.Stat none = ProjectSaveAsPlan.Stat.of(root.resolve("nothing.bwproject"));
        Path written = Files.write(root.resolve("w.bwproject"), new byte[] {1});
        assertTrue(
                ProjectSaveAsPlan.Stat.of(written).writtenSince(none)
                        && !ProjectSaveAsPlan.Stat.of(written).writtenSince(ProjectSaveAsPlan.Stat.of(written)),
                "a file counts as written only if new or changed and nonempty");
    }

    @Test
    void appliedSaveNeedsCallbackFileFlagAndName() throws IOException {
        Sim sim = new Sim();
        sim.fixture.project.child("isModified").emit(true);
        HostOperation op = sim.start(sim.root.resolve("Song/Song.bwproject"), false);
        sim.drain();
        JsonObject result = assertOutcome("applied", op);
        assertTrue(
                sim.dispatches == 1 && sim.dispatched.equals(sim.root.resolve("Song.bwproject")),
                "one dispatch at the planned native path");
        assertTrue(
                result.get("createdProjectFolder").getAsBoolean()
                        && !result.get("overwrote").getAsBoolean()
                        && result.get("projectName").getAsString().equals("Song")
                        && result.get("fileBytes").getAsLong() > 0
                        && !result.has("status"),
                "applied only with callback, written file, isModified=false and the new name");
        assertTrue(sim.holds().isEmpty(), "completed callback leaves nothing in flight");
        JsonObject job = sim.job("saveAs:1");
        assertTrue(
                job.get("method").getAsString().equals("project.saveAs")
                        && job.get("state").getAsString().equals("completed")
                        && job.get("outcome").getAsString().equals("applied"),
                "jobs.get re-evaluates the save");
        HostOperation again = sim.start(sim.root.resolve("Song/Song.bwproject"), false);
        sim.drain();
        assertRpcError("target_exists", again);
        assertTrue(sim.dispatches == 1, "existing target is refused before dispatch");
        assertTrue(
                sim.job("saveAs:1").get("outcome").getAsString().equals("applied"),
                "a save refused before dispatch keeps the previous job current");
        assertRpcError("target_not_found", sim.jobsGet("saveAs:2"));
        // Name must change too: a stale name keeps the result unknown.
        Sim stale = new Sim();
        stale.renames = false;
        op = stale.start(stale.root.resolve("Other/Other.bwproject"), false, 150);
        stale.drainUntil(op);
        result = assertUnknown("deadline", op);
        assertTrue(
                result.get("job").getAsString().equals("saveAs:1")
                        && result.get("nativeCompletion").getAsString().equals("completed"),
                "completion without the expected project name is not applied");
        job = stale.job("saveAs:1");
        assertTrue(
                job.get("outcome").getAsString().equals("unknown")
                        && job.get("reason").getAsString().equals("readback_mismatch"),
                "jobs.get reports the mismatch after completion");
    }

    @Test
    void refusalsAndFailuresKeepTheirOutcome() throws IOException {
        Sim refused = new Sim();
        refused.refusal = "Project is already in the process of being saved";
        HostOperation op = refused.start(refused.root.resolve("Song/Song.bwproject"), false);
        refused.drain();
        RpcException error = assertRpcError("host_refused", op);
        assertTrue(!error.unknown && error.getMessage().contains("already"), "native refusal is an error");
        JsonObject job = refused.job("saveAs:1");
        assertTrue(
                job.get("outcome").getAsString().equals("not_applied")
                        && job.get("reason").getAsString().equals("host_refused")
                        && job.get("failure").getAsString().contains("already"),
                "jobs.get keeps the refusal");
        Sim failed = new Sim();
        failed.failure = "IOException: disk full";
        op = failed.start(failed.root.resolve("Song/Song.bwproject"), false);
        failed.drain();
        assertTrue(
                assertUnknown("job_failed", op).get("message").getAsString().contains("disk full"),
                "native failure after the job started is unknown");
        assertTrue(failed.holds().isEmpty(), "a failure callback also ends the job");
        assertTrue(
                failed.job("saveAs:1").get("outcome").getAsString().equals("unknown"),
                "jobs.get keeps the failure unknown");
    }

    @Test
    void missingCallbackStaysInFlightAndUnknown() throws IOException {
        Sim silent = new Sim();
        silent.silent = true;
        HostOperation op = silent.start(silent.root.resolve("Song/Song.bwproject"), false, 150);
        silent.drainUntil(op);
        JsonObject result = assertUnknown("deadline", op);
        assertTrue(
                result.get("nativeCompletion").getAsString().equals("pending")
                        && result.get("job").getAsString().equals("saveAs:1"),
                "no callback within the deadline is unknown with its job, never retried");
        assertTrue(
                silent.dispatches == 1
                        && silent.holds().size() == 1
                        && silent.holds().get(0).ackable,
                "a pending native save whose request returned is a job hold ack may give up");
        HostOperation busy = silent.start(silent.root.resolve("Again/Again.bwproject"), false);
        silent.drain();
        assertTrue(assertRpcError("busy", busy).next.contains("jobs.get saveAs:1"), "busy points at jobs.get");
        HostOperation save = silent.fixture.submit(silent.module, "project.save", new JsonObject());
        silent.drain();
        assertRpcError("busy", save);
        JsonObject job = silent.job("saveAs:1");
        assertTrue(
                job.get("state").getAsString().equals("running") && !job.has("outcome"),
                "jobs.get reports running until the callback");
        silent.late.completed();
        assertTrue(silent.holds().isEmpty(), "a late callback ends the job");
        assertTrue(
                silent.job("saveAs:1").get("state").getAsString().equals("completed"),
                "jobs.get then evaluates the save");
    }

    @Test
    void saveAndActivateReportOutcomes() throws IOException {
        Sim sim = new Sim();
        boolean[] works = {true};
        int[] invocations = {0};
        sim.fixture.application.on("getAction", args -> {
            FakeHost action = new FakeHost("action");
            action.on("invoke", none -> {
                invocations[0]++;
                if (!works[0]) return null;
                if (args[0].equals(ProjectModule.SAVE_ACTION))
                    sim.fixture.project.child("isModified").emit(false);
                else sim.fixture.application.child("hasActiveEngine").emit(true);
                return null;
            });
            return action.proxy(com.bitwig.extension.controller.api.Action.class);
        });
        assertOutcome("already_matched", sim.call("project.save", 5000));
        sim.fixture.project.child("isModified").emit(true);
        assertTrue(
                !assertOutcome("applied", sim.call("project.save", 5000))
                                .get("projectModified")
                                .getAsBoolean()
                        && invocations[0] == 1,
                "an observed save is applied");
        assertTrue(
                assertOutcome("applied", sim.call("project.activateEngine", 5000))
                                .get("engineActive")
                                .getAsBoolean()
                        && invocations[0] == 2,
                "an observed activation is applied");
        assertOutcome("already_matched", sim.call("project.activateEngine", 5000));
        works[0] = false;
        sim.fixture.project.child("isModified").emit(true);
        assertTrue(
                assertUnknown("not_observed", sim.call("project.save", 150))
                        .get("projectModified")
                        .getAsBoolean(),
                "a save blocked by a dialog is unknown");
        sim.fixture.application.child("hasActiveEngine").emit(false);
        assertTrue(
                !assertUnknown("not_observed", sim.call("project.activateEngine", 150))
                        .get("engineActive")
                        .getAsBoolean(),
                "an activation not observed is unknown");
        assertTrue(invocations[0] == 4, "each dispatched once");
    }

    /** Simulated native side: Bitwig's observable effects of a save, or a refusal/failure callback. */
    private static final class Sim {
        final HostFixture fixture = new HostFixture();
        final Deque<Runnable> document = new ArrayDeque<>();
        final Path home, root;
        final ProjectModule module;
        final JobsModule jobs;
        Path dispatched;
        int dispatches;
        boolean renames = true, silent, executeFails, throwAfter;
        String refusal, failure;
        ProjectModule.Completion late;

        Sim() throws IOException {
            home = temp("bwa-saveas-sim");
            root = Files.createDirectories(home.resolve("Projects"));
            module = fixture.services.projectModule(new ProjectModule.SaveAsAccess() {
                public void execute(Runnable work) {
                    if (executeFails)
                        throw Errors.notApplied(Reason.NOT_READY, "simulated: document thread unavailable");
                    document.add(work);
                }

                public String nativeVersion() {
                    return "simulated";
                }

                public String nativeJarSha256() {
                    return "simulated";
                }

                public Path projectsRoot() {
                    return root;
                }

                public void saveAs(Object proxy, Path path, Runnable beforeDispatch, ProjectModule.Completion done) {
                    beforeDispatch.run();
                    dispatches++;
                    dispatched = path;
                    if (throwAfter) throw new IllegalStateException("simulated native save threw");
                    if (refusal != null) {
                        done.failed(refusal, true);
                        return;
                    }
                    if (failure != null) {
                        done.failed(failure, false);
                        return;
                    }
                    if (silent) {
                        late = done;
                        return;
                    }
                    try {
                        Path folder = path.getParent()
                                .resolve(path.getFileName().toString().replace(".bwproject", ""));
                        Files.createDirectories(folder);
                        Files.createFile(folder.resolve(ProjectSaveAsPlan.MARKER));
                        Files.write(folder.resolve(path.getFileName()), new byte[] {42});
                    } catch (IOException error) {
                        throw new AssertionError(error);
                    }
                    fixture.project.child("isModified").emit(false);
                    if (renames)
                        fixture.application
                                .child("projectName")
                                .emit(path.getFileName().toString().replace(".bwproject", ""));
                    done.completed();
                }
            });
            jobs = new JobsModule(module);
        }

        HostOperation start(Path path, boolean overwrite) {
            return start(path, overwrite, 5000);
        }

        HostOperation start(Path path, boolean overwrite, long timeoutMs) {
            JsonObject p = params(path.toString());
            if (overwrite) p.addProperty("overwrite", true);
            return fixture.submit(module, "project.saveAs", p, fixture.tasks.after(timeoutMs));
        }

        HostOperation call(String method, long timeoutMs) {
            HostOperation op = fixture.submit(module, method, new JsonObject(), fixture.tasks.after(timeoutMs));
            drainUntil(op);
            return op;
        }

        HostOperation jobsGet(String id) {
            JsonObject p = new JsonObject();
            p.addProperty("id", id);
            HostOperation op = fixture.submit(jobs, "jobs.get", p);
            drainUntil(op);
            return op;
        }

        JsonObject job(String id) {
            return jobsGet(id).result.join().getAsJsonObject("job");
        }

        List<Holds.View> holds() {
            return fixture.services.holds.list();
        }

        void drain() {
            while (!fixture.tasks.isEmpty() || !document.isEmpty()) step();
        }

        void drainUntil(HostOperation op) {
            long spins = 0;
            while (!op.result.isDone() && spins++ < 100000000) step();
        }

        private void step() {
            if (!document.isEmpty()) document.removeFirst().run();
            else if (!fixture.tasks.isEmpty()) fixture.next();
        }
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

    private static JsonObject params(String path) {
        JsonObject p = new JsonObject();
        p.addProperty("path", path);
        return p;
    }

    private static void invalid(JsonObject params, String label) {
        assertRpcError("invalid_params", () -> ProjectModule.validateParams("project.saveAs", params));
    }
}
