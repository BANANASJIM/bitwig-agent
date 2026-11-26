package dev.bitwigagent;

import com.bitwig.extension.callback.BooleanValueChangedCallback;
import com.bitwig.extension.callback.StringValueChangedCallback;
import com.bitwig.extension.controller.api.Application;
import com.bitwig.extension.controller.api.Project;
import java.util.concurrent.TimeUnit;

/** What the controller has observed about the open project: name settle, modified flag and audio engine. */
final class ProjectState {
    /** A name change (extension start, project switch/recovery, Save As) opens a settle window in which writes may land in a transient project. */
    static final long SETTLE_NANOS = TimeUnit.MILLISECONDS.toNanos(3000);

    private final Application application;
    private final TrackWindow window;
    private final Scheduler scheduler;
    private volatile boolean nameObserved;
    private volatile String observedName;
    private volatile long changedAt;
    private volatile Boolean modified;
    private volatile long modifiedSequence;

    ProjectState(Application application, Project project, TrackWindow window, Scheduler scheduler) {
        this.application = application;
        this.window = window;
        this.scheduler = scheduler;
        project.isModified().addValueObserver((BooleanValueChangedCallback) value -> {
            modified = value;
            modifiedSequence++;
        });
        application.projectName().markInterested();
        application.projectName().addValueObserver((StringValueChangedCallback) value -> {
            String name = String.valueOf(value);
            if (!nameObserved || !name.equals(observedName)) changedAt = scheduler.now();
            observedName = name;
            nameObserved = true;
        });
        application.hasActiveEngine().markInterested();
    }

    /** ready is true/false/null (null: project name never observed). Heuristic: the public API has no project-loaded event,
     *  so readiness is "name observed, track bank sized, no name change in the last SETTLE_NANOS". Controller thread only. */
    Readiness readiness() {
        long now = scheduler.now();
        Long sinceMs = nameObserved ? TimeUnit.NANOSECONDS.toMillis(now - changedAt) : null;
        if (!nameObserved) return new Readiness(null, "project_name_unobserved", sinceMs);
        if (now - changedAt < SETTLE_NANOS) return new Readiness(false, "project_changed_settling", sinceMs);
        if (!window.observed()) return new Readiness(false, "track_bank_unobserved", sinceMs);
        return new Readiness(true, "settled", sinceMs);
    }

    static final class Readiness {
        final Boolean ready;
        final String reason;
        final Long changedMsAgo;

        Readiness(Boolean ready, String reason, Long changedMsAgo) {
            this.ready = ready;
            this.reason = reason;
            this.changedMsAgo = changedMsAgo;
        }
    }

    /** Structure writes refuse while the project is observed not ready; unknown (null) does not block. */
    void requireReady() {
        Readiness r = readiness();
        if (Boolean.FALSE.equals(r.ready))
            throw Errors.notApplied(
                    ErrorCode.PROJECT_NOT_READY,
                    "Project is not ready (" + r.reason
                            + "): it was just opened, recovered or renamed, so this write could land in a transient project. Poll status until projectReady is true, then re-discover targets; nothing was written.");
    }

    boolean engineActive() {
        return application.hasActiveEngine().get();
    }

    /** Launching with an inactive audio engine silently produces no playback and silent captures. */
    void requireEngineActive(String what) {
        if (!engineActive())
            throw Errors.notApplied(
                    ErrorCode.ENGINE_INACTIVE,
                    "The audio engine is not active for this project, so " + what + " would not sound or be observed. "
                            + "Run project.activateEngine (or the \"Activate Engine For Project\" action), confirm status.engineActive=true, then retry; nothing was dispatched.");
    }

    /** Observed Project.isModified(); null until the first callback, never a getter default. */
    Boolean modified() {
        return modified;
    }

    long modifiedSequence() {
        return modifiedSequence;
    }
}
