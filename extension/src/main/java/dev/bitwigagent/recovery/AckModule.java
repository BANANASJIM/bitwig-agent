package dev.bitwigagent.recovery;

import com.google.gson.JsonObject;
import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.Holds;
import dev.bitwigagent.core.HostModule;
import dev.bitwigagent.core.HostOperation;
import dev.bitwigagent.core.Params;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.core.Results;
import dev.bitwigagent.grid.GridModule;
import dev.bitwigagent.grid.GridTopologyQuarantine;
import java.util.Set;

/** {@code ack}: releases one unresolved hold after the agent inspected state; a Grid topology hold re-verifies first. */
public final class AckModule implements HostModule {
    static final Set<String> METHODS = Set.of("ack");
    private static final String GRID = GridTopologyQuarantine.holdId("");
    private static final String DEVICE = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/[0-9a-f]{1,8}";
    private final Holds holds;
    private final GridModule grid;

    public AckModule(Holds holds, GridModule grid) {
        this.holds = holds;
        this.grid = grid;
    }

    @Override
    public Set<String> methods() {
        return METHODS;
    }

    @Override
    public void validate(String method, JsonObject params) {
        Params.fields(params, Set.of("id"), Set.of());
        Params.string(params, "id");
    }

    @Override
    public void dispatch(HostOperation operation, String method, JsonObject params) {
        String id = params.get("id").getAsString();
        if (id.startsWith(GRID)) {
            String device = id.substring(GRID.length());
            if (!device.matches(DEVICE))
                throw Errors.notApplied(Reason.UNKNOWN_HOLD, "No current hold has id " + id + ".");
            grid.ackTopology(operation, device);
            return;
        }
        String released = holds.ack(id);
        JsonObject result = Results.released();
        result.addProperty("id", id);
        result.addProperty("released", released);
        operation.result.complete(result);
    }
}
