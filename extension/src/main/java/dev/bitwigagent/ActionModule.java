package dev.bitwigagent;

import static dev.bitwigagent.HostLimits.*;
import static dev.bitwigagent.Results.*;

import com.bitwig.extension.controller.api.*;
import com.google.gson.*;
import java.util.Locale;
import java.util.Set;

/** Native action catalog and dispatch, deliberately not a workflow/completion engine. */
final class ActionModule implements HostModule {
    static final Set<String> METHODS = Set.of("actions.list", "actions.invoke");
    /** Closes the current project tab; guarded by the current tab's observed saved state. New/Open open another tab (observed on 6.1.4). */
    static final Set<String> DISCARDING = Set.of("Close");
    /** Closes every open tab; the bridge only observes the current one, so explicit consent is always required. */
    static final Set<String> ALWAYS_CONSENT = Set.of("Quit");

    private final Scheduler scheduler;
    private final Application application;
    private final ProjectState projectState;
    private final SelectionLease selection;

    ActionModule(Scheduler scheduler, Application application, ProjectState projectState, SelectionLease selection) {
        this.scheduler = scheduler;
        this.application = application;
        this.projectState = projectState;
        this.selection = selection;
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
        if (!METHODS.contains(method)) throw Errors.notApplied(Reason.UNKNOWN_METHOD, "Unknown Action method.");
        if (method.equals("actions.invoke")) {
            Params.fields(params, Set.of("actionId"), Set.of("discardUnsavedChanges"));
            Params.string(params, "actionId");
            if (params.has("discardUnsavedChanges")) Params.bool(params, "discardUnsavedChanges");
        } else {
            Params.fields(params, Set.of(), Set.of("query", "category", "limit"));
            if (params.has("query")) Params.string(params, "query");
            if (params.has("category")) Params.string(params, "category");
            if (params.has("limit")) Params.integer(params, "limit", 1, 1000);
        }
    }

    @Override
    public void dispatch(HostOperation request, String method, JsonObject params) {
        if (method.equals("actions.list")) request.result.complete(listActions(params));
        else invokeAction(request, params);
    }

    private JsonObject listActions(JsonObject params) {
        String query = params.has("query") ? params.get("query").getAsString().toLowerCase(Locale.ROOT) : null;
        String wanted =
                params.has("category") ? params.get("category").getAsString().toLowerCase(Locale.ROOT) : null;
        int limit = params.get("limit").getAsInt();
        JsonArray actions = new JsonArray();
        int total = 0;
        for (Action action : application.getActions()) {
            ActionCategory category = action.getCategory();
            if (query != null
                    && !action.getId().toLowerCase(Locale.ROOT).contains(query)
                    && !action.getName().toLowerCase(Locale.ROOT).contains(query)) continue;
            if (wanted != null
                    && (category == null
                            || !(category.getId().toLowerCase(Locale.ROOT).equals(wanted)
                                    || category.getName()
                                            .toLowerCase(Locale.ROOT)
                                            .equals(wanted)))) continue;
            if (total++ >= limit) continue;
            JsonObject item = new JsonObject();
            item.addProperty("actionId", action.getId());
            item.addProperty("name", action.getName());
            if (category == null) item.add("category", JsonNull.INSTANCE);
            else {
                JsonObject group = new JsonObject();
                group.addProperty("id", category.getId());
                group.addProperty("name", category.getName());
                item.add("category", group);
            }
            actions.add(item);
        }
        JsonObject result = new JsonObject();
        result.addProperty("scope", "host_action_catalog");
        result.addProperty("total", total);
        result.addProperty("complete", total <= limit);
        result.add("actions", actions);
        return result;
    }

    private void invokeAction(HostOperation request, JsonObject params) {
        String actionId = params.get("actionId").getAsString();
        Action action = application.getAction(actionId);
        if (action == null) throw Errors.notApplied(Reason.UNKNOWN_NAME, "Action ID is absent from the host catalog.");
        boolean discard = params.has("discardUnsavedChanges")
                && params.get("discardUnsavedChanges").getAsBoolean();
        if (ALWAYS_CONSENT.contains(actionId) && !discard)
            throw Errors.notApplied(
                    Reason.UNSAVED_CHANGES,
                    "Action \"" + actionId
                            + "\" closes every open project tab; only the current tab's saved state is observable (projectModified="
                            + Results.modified(projectState.modified())
                            + "). Save each tab, then pass discardUnsavedChanges:true with user consent.");
        if (DISCARDING.contains(actionId) && !discard && !Boolean.FALSE.equals(projectState.modified()))
            throw Errors.notApplied(
                    Reason.UNSAVED_CHANGES,
                    "Action \"" + actionId
                            + "\" closes the current project tab, which is not observed saved (projectModified="
                            + Results.modified(projectState.modified())
                            + "). Run project.save, or pass discardUnsavedChanges:true with user consent.");
        scheduler.check(request);
        // Host actions can retarget the launcher cursor clip (select clip/track); never mid launcher edit.
        selection.requireFree();
        request.markDispatched();
        action.invoke();
        JsonObject result = Results.outcome(Outcome.SENT);
        result.addProperty("actionId", actionId);
        request.result.complete(result);
    }
}
