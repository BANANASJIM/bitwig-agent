package dev.bitwigagent;

import com.bitwig.extension.controller.api.Action;
import com.google.gson.JsonObject;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import static dev.bitwigagent.HostServices.*;

/** project.save: the native Save action, confirmed only by an observed Project.isModified() change to false. */
final class ProjectModule implements HostModule {
    static final Set<String> METHODS = Set.of("project.save");
    static final String SAVE_ACTION = "Save";
    private final HostServices s;
    ProjectModule(HostServices s) { this.s = s; }
    @Override public Set<String> methods() { return METHODS; }
    @Override public void validate(String method, JsonObject params) { validateParams(method, params); }
    static void validateParams(String method, JsonObject params) {
        if (!METHODS.contains(method)) throw failure("unknown_method", "Unknown project method.");
        Params.fields(params, Set.of(), Set.of());
    }

    @Override public void dispatch(HostOperation op, String method, JsonObject params) {
        s.check(op);
        if (Boolean.FALSE.equals(s.projectModified())) {
            JsonObject done = observed();
            done.addProperty("saved", true);
            done.addProperty("alreadySaved", true);
            done.addProperty("projectModified", false);
            op.result.complete(done);
            return;
        }
        Action save = s.application.getAction(SAVE_ACTION);
        if (save == null) throw failure("unknown_action", "Save action is absent from the host catalog.");
        long before = s.projectModifiedSequence();
        s.check(op);
        op.dispatched = true;
        save.invoke();
        await(op, before, op.deadline - TimeUnit.MILLISECONDS.toNanos(25));
    }

    private void await(HostOperation op, long before, long until) {
        if (s.projectModifiedSequence() > before && Boolean.FALSE.equals(s.projectModified())) {
            JsonObject done = observed();
            done.addProperty("saved", true);
            done.addProperty("projectModified", false);
            op.result.complete(done);
        } else if (System.nanoTime() >= until) {
            JsonObject result = new JsonObject();
            result.addProperty("status", "dispatched");
            result.addProperty("saved", false);
            result.addProperty("completion", "not_observed");
            result.add("projectModified", s.projectModifiedJson());
            result.addProperty("hint", "Save was dispatched once, not retried. An untitled project opens a Save As dialog that needs GUI/user completion; check projectModified via status before any further action.");
            op.result.complete(result);
        } else {
            s.schedule(op, () -> await(op, before, until), 20);
        }
    }
}
