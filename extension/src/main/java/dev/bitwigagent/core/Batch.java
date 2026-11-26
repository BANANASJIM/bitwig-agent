package dev.bitwigagent.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** A batch write: every entry is checked before any is written, then entries are written in order until one fails. */
public final class Batch {
    private final int size;
    private final JsonArray entries = new JsonArray();

    public Batch(int size) {
        this.size = size;
    }

    /** Each entry's check result; if any check refuses, the whole batch is refused and entries names each refusal. */
    static <T> List<T> checked(List<Supplier<T>> checks) {
        List<T> resolved = new ArrayList<>(checks.size());
        JsonArray refused = new JsonArray();
        Reason first = null;
        for (int i = 0; i < checks.size(); i++) {
            try {
                resolved.add(checks.get(i).get());
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
        return resolved;
    }

    /** Records the next entry's result; false when the batch stops here (an outcome that did not succeed). */
    public boolean record(JsonObject result) {
        entries.add(result);
        return Results.outcomeOf(result).succeeded();
    }

    /** Every recorded result, then each entry the batch did not reach as not_applied with reason not_reached. */
    public JsonArray entries() {
        JsonArray out = entries.deepCopy();
        while (out.size() < size) {
            JsonObject skipped = Results.outcome(Outcome.NOT_APPLIED);
            skipped.addProperty("reason", Reason.NOT_REACHED.wire());
            out.add(skipped);
        }
        return out;
    }
}
