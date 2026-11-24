package dev.bitwigagent;

import com.bitwig.extension.controller.api.*;
import com.google.gson.*;
import java.util.Locale;
import java.util.Set;
import static dev.bitwigagent.HostServices.*;

/** Native action catalog and dispatch, deliberately not a workflow/completion engine. */
final class ActionModule implements HostModule {
    static final Set<String> METHODS = Set.of("actions.list", "actions.invoke");
    /** Project-lifecycle actions that can discard unsaved work (IDs from the 6.1.4 catalog; no Revert action exists there). */
    static final Set<String> DISCARDING = Set.of("Quit", "Close", "New", "Open", "new_from_template");
    private final HostServices s;
    ActionModule(HostServices s) { this.s = s; }
    @Override public Set<String> methods() { return METHODS; }
    @Override public void validate(String method, JsonObject params) { validateParams(method, params); }
    static void validateParams(String method, JsonObject params) {
        if (!METHODS.contains(method)) throw failure("unknown_method", "Unknown Action method.");
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
    @Override public void dispatch(HostOperation request, String method, JsonObject params) {
        if (method.equals("actions.list")) request.result.complete(listActions(params));
        else invokeAction(request, params);
    }

    private JsonObject listActions(JsonObject params) {
        String query = params.has("query") ? params.get("query").getAsString().toLowerCase(Locale.ROOT) : null;
        String wanted = params.has("category") ? params.get("category").getAsString().toLowerCase(Locale.ROOT) : null;
        int limit = params.has("limit") ? params.get("limit").getAsInt() : 100;
        JsonArray actions = new JsonArray();
        int total = 0;
        for (Action action : s.application.getActions()) {
            ActionCategory category = action.getCategory();
            if (query != null && !action.getId().toLowerCase(Locale.ROOT).contains(query)
                    && !action.getName().toLowerCase(Locale.ROOT).contains(query)) continue;
            if (wanted != null && (category == null || !(category.getId().toLowerCase(Locale.ROOT).equals(wanted)
                    || category.getName().toLowerCase(Locale.ROOT).equals(wanted)))) continue;
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
        JsonObject result = observed();
        result.addProperty("scope", "host_action_catalog");
        result.addProperty("total", total);
        result.addProperty("complete", total <= limit);
        result.add("actions", actions);
        return result;
    }

    private void invokeAction(HostOperation request, JsonObject params) {
        String actionId = params.get("actionId").getAsString();
        Action action = s.application.getAction(actionId);
        if (action == null) throw failure("unknown_action", "Action ID is absent from the host catalog.");
        boolean discard = params.has("discardUnsavedChanges") && params.get("discardUnsavedChanges").getAsBoolean();
        if (DISCARDING.contains(actionId) && !discard && !Boolean.FALSE.equals(s.projectModified()))
            throw failure("unsaved_changes", "Action \"" + actionId + "\" can discard unsaved work and the project is not observed saved (projectModified="
                    + s.projectModifiedJson() + "). Run project.save, or pass discardUnsavedChanges:true with user consent.");
        s.check(request);
        // Host actions can retarget the launcher cursor clip (select clip/track); never mid launcher edit.
        s.requireSelectionFree();
        request.dispatched = true;
        action.invoke();
        JsonObject result = new JsonObject();
        result.addProperty("status", "dispatched");
        result.addProperty("actionId", actionId);
        result.addProperty("completion", "not_observed");
        request.result.complete(result);
    }
}
