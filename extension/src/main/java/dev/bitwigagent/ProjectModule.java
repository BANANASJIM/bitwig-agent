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
final class ProjectModule implements HostModule {
    static final String SAVE = "project.save", SAVE_AS = "project.saveAs";
    static final String ACTIVATE = "project.activateEngine";
    static final Set<String> METHODS = Set.of(SAVE, SAVE_AS, ACTIVATE);
    static final String ACTIVATE_ACTION = "Activate Engine For Project";
    static final String SAVE_ACTION = "Save";
    static final ErrorCode UNAVAILABLE = ErrorCode.PROJECT_SAVE_AS_UNAVAILABLE;

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
    private String accessError;
    /** Cleared only by the native callback: until then Bitwig may still be writing. */
    private static final String PENDING = "project.saveAs";

    private volatile String pendingPath;

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
        if (!METHODS.contains(method)) throw Errors.notApplied(ErrorCode.UNKNOWN_METHOD, "Unknown project method.");
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
        if (holds.held(PENDING))
            throw Errors.notApplied(
                    ErrorCode.BUSY,
                    "A project.saveAs is still running natively (" + pendingPath + "); no request was queued.");
        if (method.equals(SAVE_AS)) {
            saveAs(op, params);
            return;
        }
        if (method.equals(ACTIVATE)) {
            activateEngine(op);
            return;
        }
        if (Boolean.FALSE.equals(projectState.modified())) {
            JsonObject done = observed();
            done.addProperty("saved", true);
            done.addProperty("alreadySaved", true);
            done.addProperty("projectModified", false);
            op.result.complete(done);
            return;
        }
        Action save = application.getAction(SAVE_ACTION);
        if (save == null)
            throw Errors.notApplied(ErrorCode.UNKNOWN_ACTION, "Save action is absent from the host catalog.");
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
            JsonObject done = observed();
            done.addProperty("engineActive", true);
            done.addProperty("alreadyActive", true);
            op.result.complete(done);
            return;
        }
        Action activate = application.getAction(ACTIVATE_ACTION);
        if (activate == null)
            throw Errors.notApplied(
                    ErrorCode.UNKNOWN_ACTION,
                    "\"" + ACTIVATE_ACTION + "\" is absent from the host catalog; use actions.list query=engine.");
        scheduler.check(op);
        op.dispatched = true;
        activate.invoke();
        awaitEngine(op);
    }

    private void awaitEngine(HostOperation op) {
        scheduler.poll(op, scheduler.deadlineUntil(op), projectState::engineActive, done -> {
            if (done) {
                JsonObject result = observed();
                result.addProperty("engineActive", true);
                op.result.complete(result);
                return;
            }
            JsonObject result = new JsonObject();
            result.addProperty("status", "dispatched");
            result.addProperty("engineActive", false);
            result.addProperty("completion", "not_observed");
            result.addProperty(
                    "hint",
                    "The action was dispatched once, not retried, but hasActiveEngine did not become true in time. A dialog may be blocking it, or the project may still be loading: check status.engineActive and projectReady before another attempt.");
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
                        JsonObject result = observed();
                        result.addProperty("saved", true);
                        result.addProperty("projectModified", false);
                        op.result.complete(result);
                        return;
                    }
                    JsonObject result = new JsonObject();
                    result.addProperty("status", "dispatched");
                    result.addProperty("saved", false);
                    result.addProperty("completion", "not_observed");
                    result.add("projectModified", Results.modified(projectState.modified()));
                    result.addProperty(
                            "hint",
                            "Save was dispatched once, not retried. A modal dialog (Save As for an untitled project, crash recovery, ...) can block the save and needs GUI/user completion; check projectModified via status before any further action.");
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
            if (!error.is(UNAVAILABLE)) throw error;
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
        if (access != null) return access;
        if (accessError != null) throw Errors.notApplied(UNAVAILABLE, accessError);
        try {
            access = new Bitwig614ProjectSaveAsAccess(natives.get(op).access(UNAVAILABLE));
        } catch (ReflectiveOperationException | LinkageError error) {
            accessError = "Exact project save bindings unavailable: " + Bitwig614Runtime.diagnostic(error);
            throw Errors.notApplied(UNAVAILABLE, accessError);
        }
        return access;
    }

    private static final class Outcome implements Completion {
        volatile boolean done, refused;
        volatile String failure;
        final Runnable onDone;

        Outcome(Runnable onDone) {
            this.onDone = onDone;
        }

        @Override
        public void completed() {
            if (!done) {
                done = true;
                onDone.run();
            }
        }

        @Override
        public void failed(String message, boolean refused) {
            if (done) return;
            failure = message;
            this.refused = refused;
            done = true;
            onDone.run();
        }
    }

    private void saveAs(HostOperation op, JsonObject params) {
        Path path = Path.of(params.get("path").getAsString());
        boolean overwrite = params.has("overwrite") && params.get("overwrite").getAsBoolean();
        boolean anyPath =
                params.has("allowAnyPath") && params.get("allowAnyPath").getAsBoolean();
        SaveAsAccess bridge = access(op);
        Path home = Path.of(System.getProperty("user.home"));
        pendingPath = path.toString();
        holds.place(
                PENDING,
                "A project.saveAs to " + pendingPath
                        + " has no native completion callback yet; inspect the file and projectModified.");
        Outcome outcome = new Outcome(() -> holds.release(PENDING));
        try {
            bridge.execute(() -> {
                try {
                    scheduler.check(op);
                    ProjectSaveAsPlan plan =
                            ProjectSaveAsPlan.resolve(path, bridge.projectsRoot(), home, overwrite, anyPath);
                    bridge.saveAs(
                            project,
                            plan.dispatchPath,
                            () -> {
                                scheduler.check(op);
                                op.dispatched = true;
                            },
                            outcome);
                    scheduler.schedule(op, () -> awaitSaveAs(op, plan, outcome, bridge), 0);
                } catch (RuntimeException | Error error) {
                    if (!op.dispatched) holds.release(PENDING);
                    op.fail(error);
                }
            });
        } catch (RuntimeException | Error error) {
            holds.release(PENDING);
            throw error;
        }
    }

    private void awaitSaveAs(HostOperation op, ProjectSaveAsPlan plan, Outcome outcome, SaveAsAccess bridge) {
        scheduler.poll(
                op,
                scheduler.deadlineUntil(op),
                () -> outcome.done && (outcome.failure != null || saveObserved(plan)),
                done -> finishSaveAs(op, plan, outcome, bridge));
    }

    private boolean saveObserved(ProjectSaveAsPlan plan) {
        return ProjectSaveAsPlan.Stat.of(plan.file).writtenSince(plan.before)
                && Boolean.FALSE.equals(projectState.modified())
                && plan.name.equals(application.projectName().get());
    }

    private void finishSaveAs(HostOperation op, ProjectSaveAsPlan plan, Outcome outcome, SaveAsAccess bridge) {
        // One snapshot per poll: failure is written before done, so it is stable once done is seen.
        boolean done = outcome.done;
        String failure = done ? outcome.failure : null;
        if (failure != null) {
            String prefix = "Native save " + (outcome.refused ? "refused" : "failed") + ": " + failure;
            op.result.completeExceptionally(
                    outcome.refused
                            ? Errors.notApplied(ErrorCode.SAVE_REFUSED, prefix + ". Nothing was written.")
                            : Errors.unknown(
                                    ErrorCode.SAVE_FAILED,
                                    prefix
                                            + ". Bitwig restores a previous file from its backup, but the open project may already carry the new name/path and the project folder may exist; inspect status and the folder."));
            return;
        }
        ProjectSaveAsPlan.Stat file = ProjectSaveAsPlan.Stat.of(plan.file);
        boolean written = file.writtenSince(plan.before), saved = Boolean.FALSE.equals(projectState.modified());
        String name = application.projectName().get();
        boolean named = plan.name.equals(name);
        boolean observed = done && written && saved && named;
        JsonObject result = new JsonObject();
        result.addProperty("status", observed ? "observed" : "dispatched");
        result.addProperty("saved", observed);
        if (!observed) {
            result.addProperty("completion", "not_observed");
            result.addProperty("outcome", "unknown");
        }
        result.addProperty("nativeCompletion", done ? "completed" : "pending");
        result.addProperty("file", plan.file.toString());
        result.addProperty("projectFolder", plan.folder.toString());
        result.addProperty("createdProjectFolder", plan.newFolder);
        result.addProperty("overwrote", plan.before.exists);
        result.addProperty("fileWritten", written);
        if (file.exists) result.addProperty("fileBytes", file.size);
        result.addProperty("expectedProjectName", plan.name);
        result.addProperty("projectName", name);
        result.add("projectModified", Results.modified(projectState.modified()));
        result.addProperty("nativeVersion", Bitwig614Runtime.VERSION);
        result.addProperty("nativeJarSha256", bridge.nativeJarSha256());
        result.addProperty("verificationScope", "native_callback_file_and_model_flags_not_reopen");
        if (!observed)
            result.addProperty(
                    "hint",
                    "Dispatched once, never retried. "
                            + (done
                                    ? "Bitwig reported completion but not every observation matched; inspect the file, status.projectName and projectModified."
                                    : "Bitwig has not reported completion (plugin state collection or a large copy can be slow); further project saves stay busy until it does. Inspect the file and status, do not repeat."));
        op.result.complete(result);
    }
}
