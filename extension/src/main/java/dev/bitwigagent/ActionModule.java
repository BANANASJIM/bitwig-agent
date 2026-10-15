package dev.bitwigagent;

import com.bitwig.extension.controller.api.*;
import com.google.gson.*;
import java.util.Set;
import static dev.bitwigagent.HostServices.*;

/** Native action catalog and dispatch, deliberately not a workflow/completion engine. */
final class ActionModule implements HostModule {
    static final Set<String> METHODS = Set.of("actions.list", "actions.invoke");
    private final HostServices s;
    ActionModule(HostServices s) { this.s = s; }
    @Override public Set<String> methods() { return METHODS; }
    @Override public void validate(String method, JsonObject params) { validateParams(method, params); }
    static void validateParams(String method, JsonObject params) {
        if (!METHODS.contains(method)) throw failure("unknown_method", "Unknown Action method.");
        Params.fields(params, method.equals("actions.invoke") ? Set.of("actionId") : Set.of(), Set.of());
        if (method.equals("actions.invoke")) Params.string(params, "actionId");
    }
    @Override public void dispatch(HostOperation request, String method, JsonObject params) {
        if (method.equals("actions.list")) request.result.complete(listActions());
        else invokeAction(request, params.get("actionId").getAsString());
    }

    private JsonObject listActions() {
        JsonArray actions = new JsonArray();
        for (Action action : s.application.getActions()) {
            JsonObject item = new JsonObject();
            item.addProperty("actionId", action.getId());
            item.addProperty("name", action.getName());
            ActionCategory category = action.getCategory();
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
        result.add("actions", actions);
        return result;
    }

    private void invokeAction(HostOperation request, String actionId) {
        Action action = s.application.getAction(actionId);
        if (action == null) throw failure("unknown_action", "Action ID is absent from the host catalog.");
        s.check(request);
        request.dispatched = true;
        action.invoke();
        JsonObject result = new JsonObject();
        result.addProperty("status", "dispatched");
        result.addProperty("actionId", actionId);
        result.addProperty("completion", "not_observed");
        request.result.complete(result);
    }
}
