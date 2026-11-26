package dev.bitwigagent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;

/** A batch write: every entry is checked before any is written, then entries are written in order until one fails. */
final class Batch {
    private final int size;
    private final JsonArray entries = new JsonArray();

    private Batch(int size) {
        this.size = size;
    }

    /** Runs every entry's check; if any refuses, the whole batch is refused and entries names each refusal. */
    static Batch checked(List<Runnable> checks) {
        JsonArray refused = new JsonArray();
        Reason first = null;
        for (int i = 0; i < checks.size(); i++) {
            try {
                checks.get(i).run();
            } catch (RpcException error) {
                if (first == null) first = error.reason;
                JsonObject entry = Errors.error(error);
                entry.addProperty("index", i);
                refused.add(entry);
            }
        }
        if (first != null)
            throw Errors.entries(
                    first,
                    refused.size() + " of " + checks.size() + " entries failed their checks; nothing was written.",
                    refused);
        return new Batch(checks.size());
    }

    /** Records the next entry's result; false when the batch stops here (an outcome that did not succeed). */
    boolean record(JsonObject result) {
        entries.add(result);
        return Results.outcomeOf(result).succeeded();
    }

    /** Every recorded result, then each entry the batch did not reach as not_applied with reason not_reached. */
    JsonArray entries() {
        JsonArray out = entries.deepCopy();
        while (out.size() < size) {
            JsonObject skipped = Results.outcome(Outcome.NOT_APPLIED);
            skipped.addProperty("reason", Reason.NOT_REACHED.wire());
            out.add(skipped);
        }
        return out;
    }
}
