package dev.bitwigagent;

import static dev.bitwigagent.HostLimits.*;
import static dev.bitwigagent.Results.*;

import com.bitwig.extension.controller.api.Action;
import com.bitwig.extension.controller.api.Application;
import com.bitwig.extension.controller.api.Project;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.Set;

/**
 * project.activateEngine: the native activate-engine action, confirmed only by an observed hasActiveEngine true.
 * project.save: the native Save action, confirmed only by an observed Project.isModified() change to false.
 * project.saveAs: the native Save Project pipeline to an explicit path without the dialog, confirmed by its
 * completion callback plus the written file, isModified=false and the new project name.
 */
final class ProjectModule implements HostModule, JobsModule.Source {
    static final String SAVE = "project.save", SAVE_AS = "project.saveAs";
    static final String ACTIVATE = "project.activateEngine";
    static final Set<String> METHODS = Set.of(SAVE, SAVE_AS, ACTIVATE);
    static final String ACTIVATE_ACTION = "Activate Engine For Project";
    static final String SAVE_ACTION = "Save";

    interface Completion {
        void completed();

        void failed(String message, boolean refused);
    }

    interface SaveAsAccess {
        void execute(Runnable work);

        String nativeJarSha256();
        /** Bitwig's configured projects folder; native document thread. */
        Path projectsRoot();
        /** Dispatch once on the native document thread; failures before beforeDispatch are not_applied. */
        void saveAs(Object projectProxy, Path dispatchPath, Runnable beforeDispatch, Completion done);
    }

    private final Scheduler scheduler;
    private final Application application;
    private final Project project;
    private final ProjectState projectState;
    private final NativeRuntime natives;
    private final Holds holds;
    private SaveAsAccess access;
    /** Cleared only by the native callback: until then Bitwig may still be writing. */
    private static final String PENDING = "project.saveAs";

    private volatile SaveJob job;
    private int sequence;

    ProjectModule(
            Scheduler scheduler,
            Application application,
            Project project,
            ProjectState projectState,
            NativeRuntime natives,
            Holds holds) {
        this(scheduler, application, project, projectState, natives, holds, null);
    }

    ProjectModule(
            Scheduler scheduler,
            Application application,
            Project project,
            ProjectState projectState,
            NativeRuntime natives,
            Holds holds,
            SaveAsAccess access) {
        this.scheduler = scheduler;
        this.application = application;
        this.project = project;
        this.projectState = projectState;
        this.natives = natives;
        this.holds = holds;
        this.access = access;
    }

    @Override
    public Set<String> methods() {
        return METHODS;
    }

    @Override
    public void validate(String method, JsonObject params) {
        validateParams(method, params);
    }

    static void validateParams(String method, JsonObject params) {
        if (!METHODS.contains(method)) throw Errors.notApplied(Reason.UNKNOWN_METHOD, "Unknown project method.");
        if (method.equals(SAVE) || method.equals(ACTIVATE)) {
            Params.fields(params, Set.of(), Set.of());
            return;
        }
        Params.fields(params, Set.of("path"), Set.of("overwrite", "allowAnyPath"));
        ProjectSaveAsPlan.projectName(Params.absoluteFilePath(params, "path"));
        if (params.has("overwrite")) Params.bool(params, "overwrite");
        if (params.has("allowAnyPath")) Params.bool(params, "allowAnyPath");
    }

    @Override
    public void dispatch(HostOperation op, String method, JsonObject params) {
        scheduler.check(op);
        SaveJob running = job;
        if (holds.held(PENDING))
            throw Errors.notApplied(
                    Reason.BUSY,
                    "A project.saveAs is still running natively (" + running.path + "); no request was queued.",
                    "Follow it with jobs.get " + running.id + ".");
        if (method.equals(SAVE_AS)) {
            saveAs(op, params);
            return;
        }
        if (method.equals(ACTIVATE)) {
            activateEngine(op);
            return;
        }
        if (Boolean.FALSE.equals(projectState.modified())) {
            JsonObject done = Results.outcome(Outcome.ALREADY_MATCHED);
            done.addProperty("projectModified", false);
            op.result.complete(done);
            return;
        }
        Action save = application.getAction(SAVE_ACTION);
        if (save == null) throw Errors.notApplied(Reason.UNKNOWN_NAME, "Save action is absent from the host catalog.");
        long before = projectState.modifiedSequence();
        scheduler.check(op);
        op.dispatched = true;
        save.invoke();
        // Full request deadline, not observeUntil's 750 ms window: a real save can take longer. Shared margin keeps the
        // last poll inside it.
        awaitSaved(op, before);
    }

    private void activateEngine(HostOperation op) {
        if (projectState.engineActive()) {
            JsonObject done = Results.outcome(Outcome.ALREADY_MATCHED);
            done.addProperty("engineActive", true);
            op.result.complete(done);
            return;
        }
        Action activate = application.getAction(ACTIVATE_ACTION);
        if (activate == null)
            throw Errors.notApplied(
                    Reason.UNKNOWN_NAME,
                    "\"" + ACTIVATE_ACTION + "\" is absent from the host catalog; use actions.list query=engine.");
        scheduler.check(op);
        op.dispatched = true;
        activate.invoke();
        awaitEngine(op);
    }

    private void awaitEngine(HostOperation op) {
        scheduler.poll(op, scheduler.deadlineUntil(op), projectState::engineActive, done -> {
            if (done) {
                JsonObject result = Results.outcome(Outcome.APPLIED);
                result.addProperty("engineActive", true);
                op.result.complete(result);
                return;
            }
            JsonObject result = Errors.unknownResult(
                    Errors.unknown(
                            Reason.NOT_OBSERVED,
                            "The action was dispatched once, not retried, but hasActiveEngine did not become true in time. A dialog may be blocking it, or the project may still be loading: check status.engineActive and projectReady before another attempt."));
            result.addProperty("engineActive", false);
            op.result.complete(result);
        });
    }

    private void awaitSaved(HostOperation op, long before) {
        scheduler.poll(
                op,
                scheduler.deadlineUntil(op),
                () -> projectState.modifiedSequence() > before && Boolean.FALSE.equals(projectState.modified()),
                done -> {
                    if (done) {
                        JsonObject result = Results.outcome(Outcome.APPLIED);
                        result.addProperty("projectModified", false);
                        op.result.complete(result);
                        return;
                    }
                    JsonObject result = Errors.unknownResult(
                            Errors.unknown(
                                    Reason.NOT_OBSERVED,
                                    "Save was dispatched once, not retried. A modal dialog (Save As for an untitled project, crash recovery, ...) can block the save and needs GUI/user completion; check projectModified via status before any further action."));
                    result.add("projectModified", Results.modified(projectState.modified()));
                    op.result.complete(result);
                });
    }

    /** Binding availability for capabilities; binds on first use, never dispatches. */
    JsonObject saveAsCapability(HostOperation op) {
        JsonObject result = new JsonObject();
        result.addProperty("method", SAVE_AS);
        try {
            access(op);
            result.addProperty("available", true);
        } catch (RpcException error) {
            if (!error.is(Reason.NATIVE_UNAVAILABLE) && !error.is(Reason.HOST_MISMATCH)) throw error;
            result.addProperty("available", false);
            result.addProperty("reason", error.getMessage());
        }
        result.addProperty(
                "binding",
                "exact_" + Bitwig614Runtime.VERSION
                        + "_hash_gated_save_project_pipeline_with_native_completion_callback");
        result.addProperty("verification", "native_callback_file_written_isModified_false_and_project_name");
        return result;
    }

    private SaveAsAccess access(HostOperation op) {
        if (access == null)
            access = Bitwig614ProjectSaveAsAccess.bind(natives.get(op).access());
        return access;
    }

    /** One project.saveAs: its plan once resolved, and Bitwig's completion callback. */
    private final class SaveJob implements Completion {
        final String id = JobsModule.id(kind(), ++sequence);
        final Path path;
        volatile ProjectSaveAsPlan plan;
        volatile boolean dispatched, done, refused;
        volatile String failure;

        SaveJob(Path path) {
            this.path = path;
        }

        @Override
        public void completed() {
            if (done) return;
            done = true;
            holds.release(PENDING);
        }

        @Override
        public void failed(String message, boolean refused) {
            if (done) return;
            failure = message;
            this.refused = refused;
            done = true;
            holds.release(PENDING);
        }
    }

    private void saveAs(HostOperation op, JsonObject params) {
        Path path = Path.of(params.get("path").getAsString());
        boolean overwrite = params.has("overwrite") && params.get("overwrite").getAsBoolean();
        boolean anyPath =
                params.has("allowAnyPath") && params.get("allowAnyPath").getAsBoolean();
        SaveAsAccess bridge = access(op);
        Path home = Path.of(System.getProperty("user.home"));
        SaveJob previous = job, next = new SaveJob(path);
        job = next;
        holds.inFlight(
                PENDING,
                "A project.saveAs to " + path + " has no native completion callback yet; follow it with jobs.get "
                        + next.id + ".");
        try {
            bridge.execute(() -> {
                try {
                    scheduler.check(op);
                    next.plan = ProjectSaveAsPlan.resolve(path, bridge.projectsRoot(), home, overwrite, anyPath);
                    bridge.saveAs(
                            project,
                            next.plan.dispatchPath,
                            () -> {
                                scheduler.check(op);
                                op.dispatched = true;
                                next.dispatched = true;
                            },
                            next);
                    scheduler.schedule(op, () -> awaitSaveAs(op, next, bridge), 0);
                } catch (RuntimeException | Error error) {
                    if (!op.dispatched) abandon(next, previous);
                    op.fail(error);
                }
            });
        } catch (RuntimeException | Error error) {
            abandon(next, previous);
            throw error;
        }
    }

    /** A save that failed before dispatch gives the job slot back. */
    private void abandon(SaveJob next, SaveJob previous) {
        holds.release(PENDING);
        if (job == next) job = previous;
    }

    private void awaitSaveAs(HostOperation op, SaveJob current, SaveAsAccess bridge) {
        scheduler.poll(
                op,
                scheduler.deadlineUntil(op),
                () -> current.done && (current.failure != null || saveObserved(current.plan)),
                done -> finishSaveAs(op, current, bridge));
    }

    private boolean saveObserved(ProjectSaveAsPlan plan) {
        return ProjectSaveAsPlan.Stat.of(plan.file).writtenSince(plan.before)
                && Boolean.FALSE.equals(projectState.modified())
                && plan.name.equals(application.projectName().get());
    }

    private void finishSaveAs(HostOperation op, SaveJob current, SaveAsAccess bridge) {
        // One snapshot per poll: failure is written before done, so it is stable once done is seen.
        boolean done = current.done;
        String failure = done ? current.failure : null;
        if (failure != null) {
            String prefix = "Native save " + (current.refused ? "refused" : "failed") + ": " + failure;
            if (current.refused)
                op.result.completeExceptionally(
                        Errors.notApplied(Reason.HOST_REFUSED, prefix + ". Nothing was written."));
            else
                op.fail(
                        Errors.unknown(
                                Reason.JOB_FAILED,
                                prefix
                                        + ". Bitwig restores a previous file from its backup, but the open project may already carry the new name/path and the project folder may exist; inspect status and the folder."));
            return;
        }
        JsonObject result;
        if (done && saveObserved(current.plan)) result = Results.outcome(Outcome.APPLIED);
        else {
            result = JobsModule.deadline(
                    current.id,
                    done
                            ? "Bitwig reported completion but not every observation matched; inspect the file, status.projectName and projectModified."
                            : "Bitwig has not reported completion (plugin state collection or a large copy can be slow); further project saves stay busy until it does.");
            result.addProperty("nativeCompletion", done ? "completed" : "pending");
        }
        evidence(result, current.plan, bridge);
        op.result.complete(result);
    }

    private void evidence(JsonObject result, ProjectSaveAsPlan plan, SaveAsAccess bridge) {
        ProjectSaveAsPlan.Stat file = ProjectSaveAsPlan.Stat.of(plan.file);
        result.addProperty("file", plan.file.toString());
        result.addProperty("projectFolder", plan.folder.toString());
        result.addProperty("createdProjectFolder", plan.newFolder);
        result.addProperty("overwrote", plan.before.exists);
        result.addProperty("fileWritten", file.writtenSince(plan.before));
        if (file.exists) result.addProperty("fileBytes", file.size);
        result.addProperty("expectedProjectName", plan.name);
        result.addProperty("projectName", application.projectName().get());
        result.add("projectModified", Results.modified(projectState.modified()));
        result.addProperty("nativeVersion", Bitwig614Runtime.VERSION);
        result.addProperty("nativeJarSha256", bridge.nativeJarSha256());
        result.addProperty("verificationScope", "native_callback_file_and_model_flags_not_reopen");
    }

    @Override
    public String kind() {
        return "saveAs";
    }

    /** The current save: running until Bitwig's callback, then its outcome evaluated now. */
    @Override
    public void job(HostOperation op, String id) {
        scheduler.check(op);
        SaveJob current = job;
        if (current == null || !current.dispatched || !current.id.equals(id)) throw JobsModule.unknownJob(id);
        SaveAsAccess bridge = access(op);
        JsonObject row = new JsonObject();
        row.addProperty("id", current.id);
        row.addProperty("method", SAVE_AS);
        boolean done = current.done;
        row.addProperty("state", done ? "completed" : "running");
        if (done) {
            String failure = current.failure;
            Outcome outcome = failure != null
                    ? current.refused ? Outcome.NOT_APPLIED : Outcome.UNKNOWN
                    : saveObserved(current.plan) ? Outcome.APPLIED : Outcome.UNKNOWN;
            row.addProperty("outcome", outcome.wire());
            if (failure != null) {
                row.addProperty("reason", (current.refused ? Reason.HOST_REFUSED : Reason.JOB_FAILED).wire());
                row.addProperty("failure", failure);
            } else if (outcome == Outcome.UNKNOWN) row.addProperty("reason", Reason.READBACK_MISMATCH.wire());
        }
        evidence(row, current.plan, bridge);
        op.result.complete(JobsModule.job(row));
    }
}
