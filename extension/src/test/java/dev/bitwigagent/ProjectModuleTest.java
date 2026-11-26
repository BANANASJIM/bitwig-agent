package dev.bitwigagent;

import static dev.bitwigagent.RpcAssert.*;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
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
        ProjectModule.validateParams("project.saveAs", params("/home/u/Bitwig Studio/Projects/Song/Song.bwproject"));
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
            assertTrue(expected.code.equals("invalid_params"), "project.save still takes no parameters");
        }
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
        assertNotApplied(
                "invalid_target",
                () -> ProjectSaveAsPlan.resolve(root.resolve("Song.bwproject"), root, home, false, false));
        assertNotApplied(
                "invalid_target",
                () -> ProjectSaveAsPlan.resolve(root.resolve("Song/Other.bwproject"), root, home, false, false));
        assertNotApplied(
                "invalid_target",
                () -> ProjectSaveAsPlan.resolve(root.resolve("Missing/Song/Song.bwproject"), root, home, false, false));
        assertNotApplied(
                "path_not_allowed",
                () -> ProjectSaveAsPlan.resolve(home.resolve("Song/Song.bwproject"), root, home, false, false));
        assertNotApplied(
                "path_not_allowed",
                () -> ProjectSaveAsPlan.resolve(root.resolve("Song/Song.bwproject"), null, home, false, false));
        Files.createDirectories(home.resolve("Elsewhere"));
        assertTrue(
                ProjectSaveAsPlan.resolve(home.resolve("Elsewhere/Song/Song.bwproject"), root, home, false, true)
                        .newFolder,
                "allowAnyPath lifts only the root check");
        Path outside = temp("bwa-outside");
        Files.createSymbolicLink(root.resolve("link"), outside);
        assertNotApplied(
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
        assertNotApplied(
                "invalid_target",
                () -> ProjectSaveAsPlan.resolve(existing.resolve("missing/V2.bwproject"), root, home, false, false));
        Files.write(existing.resolve("Existing.bwproject"), new byte[] {1, 2, 3});
        assertNotApplied(
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
        assertNotApplied(
                "target_exists",
                () -> ProjectSaveAsPlan.resolve(root.resolve("Busy/Busy.bwproject"), root, home, true, false));

        Path stray = Files.createFile(home.resolve("Bitwig Studio").resolve(ProjectSaveAsPlan.MARKER));
        assertNotApplied(
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
    void observedSaveNeedsCallbackFileFlagAndName() throws IOException {
        Sim sim = new Sim();
        sim.fixture.project.child("isModified").emit(true);
        HostOperation op = sim.start(sim.root.resolve("Song/Song.bwproject"), false);
        sim.drain();
        JsonObject result = op.result.join();
        assertTrue(
                sim.dispatches == 1 && sim.dispatched.equals(sim.root.resolve("Song.bwproject")),
                "one dispatch at the planned native path");
        assertTrue(
                result.get("status").getAsString().equals("observed")
                        && result.get("saved").getAsBoolean()
                        && result.get("createdProjectFolder").getAsBoolean()
                        && !result.get("overwrote").getAsBoolean()
                        && result.get("projectName").getAsString().equals("Song")
                        && result.get("fileBytes").getAsLong() > 0,
                "saved only with callback, written file, isModified=false and the new name");
        assertTrue(sim.module.unresolved().isEmpty(), "completed callback leaves nothing pending");
        HostOperation again = sim.start(sim.root.resolve("Song/Song.bwproject"), false);
        sim.drain();
        assertRpcError("target_exists", again);
        assertTrue(sim.dispatches == 1, "existing target is refused before dispatch");
        // Name must change too: a stale name keeps the result unobserved.
        Sim stale = new Sim();
        stale.renames = false;
        op = stale.start(stale.root.resolve("Other/Other.bwproject"), false, 150);
        stale.drainUntil(op);
        result = op.result.join();
        assertTrue(
                result.get("status").getAsString().equals("dispatched")
                        && result.get("outcome").getAsString().equals("unknown")
                        && result.get("nativeCompletion").getAsString().equals("completed")
                        && !result.get("saved").getAsBoolean(),
                "completion without the expected project name is not reported saved");
    }

    @Test
    void refusalsAndFailuresKeepTheirOutcome() throws IOException {
        Sim refused = new Sim();
        refused.refusal = "Project is already in the process of being saved";
        HostOperation op = refused.start(refused.root.resolve("Song/Song.bwproject"), false);
        refused.drain();
        RpcException error = assertRpcError("save_refused", op);
        assertTrue(
                error.outcome.equals("not_applied") && error.getMessage().contains("already"),
                "native refusal is not_applied with its message");
        Sim failed = new Sim();
        failed.failure = "IOException: disk full";
        op = failed.start(failed.root.resolve("Song/Song.bwproject"), false);
        failed.drain();
        error = assertRpcError("save_failed", op);
        assertTrue(
                error.outcome.equals("unknown") && error.getMessage().contains("disk full"),
                "native failure after the job started is unknown");
        assertTrue(failed.module.unresolved().isEmpty(), "a failure callback also ends the pending state");
    }

    @Test
    void missingCallbackStaysPendingAndUnknown() throws IOException {
        Sim silent = new Sim();
        silent.silent = true;
        HostOperation op = silent.start(silent.root.resolve("Song/Song.bwproject"), false, 150);
        silent.drainUntil(op);
        JsonObject result = op.result.join();
        assertTrue(
                result.get("outcome").getAsString().equals("unknown")
                        && result.get("nativeCompletion").getAsString().equals("pending"),
                "no callback within the deadline is unknown, never retried");
        assertTrue(
                silent.dispatches == 1 && silent.module.unresolved().size() == 1,
                "pending native save is listed as unresolved");
        HostOperation busy = silent.start(silent.root.resolve("Again/Again.bwproject"), false);
        silent.drain();
        assertRpcError("busy", busy);
        HostOperation save = silent.fixture.submit(silent.module, "project.save", new JsonObject());
        silent.drain();
        assertRpcError("busy", save);
        silent.late.completed();
        assertTrue(silent.module.unresolved().isEmpty(), "a late callback clears the pending state");
    }

    /** Simulated native side: Bitwig's observable effects of a save, or a refusal/failure callback. */
    private static final class Sim {
        final HostFixture fixture = new HostFixture();
        final Deque<Runnable> document = new ArrayDeque<>();
        final Path home, root;
        final ProjectModule module;
        Path dispatched;
        int dispatches;
        boolean renames = true, silent;
        String refusal, failure;
        ProjectModule.Completion late;

        Sim() throws IOException {
            home = temp("bwa-saveas-sim");
            root = Files.createDirectories(home.resolve("Projects"));
            module = new ProjectModule(fixture.services, new ProjectModule.SaveAsAccess() {
                public void execute(Runnable work) {
                    document.add(work);
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
        }

        HostOperation start(Path path, boolean overwrite) {
            return start(path, overwrite, 5000);
        }

        HostOperation start(Path path, boolean overwrite, long timeoutMs) {
            JsonObject p = params(path.toString());
            if (overwrite) p.addProperty("overwrite", true);
            return fixture.submit(
                    module, "project.saveAs", p, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs));
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
