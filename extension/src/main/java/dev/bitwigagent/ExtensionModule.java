package dev.bitwigagent;

import static dev.bitwigagent.HostServices.failure;

import com.google.gson.*;
import java.util.*;
import java.util.function.Supplier;

/** Controller restart: clears per-instance write quarantine without the Settings toggle. */
final class ExtensionModule implements HostModule {
    static final Set<String> METHODS = Set.of("extension.restart");
    static final long RESTART_DELAY_MS = 250;
    private final HostServices s;
    private final Supplier<List<HostModule>> modules;

    ExtensionModule(HostServices s, Supplier<List<HostModule>> modules) {
        this.s = s;
        this.modules = modules;
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
        if (!METHODS.contains(method)) throw failure(ErrorCode.UNKNOWN_METHOD, "Unknown extension method.");
        Params.fields(params, Set.of(), Set.of("discardUnresolved"));
        if (params.has("discardUnresolved")) Params.bool(params, "discardUnresolved");
    }

    @Override
    public void dispatch(HostOperation operation, String method, JsonObject params) {
        List<String> unresolved = new ArrayList<>();
        for (HostModule module : modules.get()) unresolved.addAll(module.unresolved());
        boolean discard = params.has("discardUnresolved")
                && params.get("discardUnresolved").getAsBoolean();
        if (!unresolved.isEmpty() && !discard)
            throw failure(
                    ErrorCode.UNRESOLVED_MUTATION,
                    "Unresolved mutations exist; inspect them or pass discardUnresolved:true. Unresolved: "
                            + String.join(" | ", unresolved));
        JsonArray list = new JsonArray();
        for (String description : unresolved) list.add(description);
        JsonObject result = new JsonObject();
        result.addProperty("status", "dispatched");
        result.addProperty("completion", "not_observed");
        result.add("unresolvedDiscarded", list);
        result.addProperty(
                "next",
                "RPC drops during restart; poll status until it answers, then re-discover targets and inspect the unresolved items.");
        // Respond first, then stop admitting work so nothing runs on this instance before the restart.
        s.host.scheduleTask(s.host::restart, RESTART_DELAY_MS);
        operation.result.complete(result);
        s.close();
    }
}
