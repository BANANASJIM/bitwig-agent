package dev.bitwigagent.recovery;

import com.google.gson.JsonObject;
import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.HostModule;
import dev.bitwigagent.core.HostOperation;
import dev.bitwigagent.core.Params;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.core.Results;
import dev.bitwigagent.core.RpcException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** {@code jobs.get}: the state of a long operation whose request returned outcome unknown with a {@code job} id. */
public final class JobsModule implements HostModule {
    static final Set<String> METHODS = Set.of("jobs.get");

    /** A domain that runs long operations; job ids are {@code <kind>:<n>}. */
    public interface Source {
        String kind();

        /** Completes {@code op} with {@code {job: {id, state, outcome?, ...}}}, or fails if {@code id} is not its job. */
        void job(HostOperation op, String id);
    }

    private final Map<String, Source> sources = new LinkedHashMap<>();

    public JobsModule(Source... sources) {
        for (Source source : sources) this.sources.put(source.kind(), source);
    }

    public static String id(String kind, int sequence) {
        return kind + ":" + sequence;
    }

    public static RpcException unknownJob(String id) {
        return Errors.notApplied(Reason.TARGET_NOT_FOUND, "No current job has id " + id + ".");
    }

    /** A write that outlived its wait: {outcome: unknown, reason: deadline, message, job, next}. */
    public static JsonObject deadline(String id, String message) {
        JsonObject result = Results.unknown();
        result.addProperty("reason", Reason.DEADLINE.wire());
        result.addProperty("message", message);
        result.addProperty("job", id);
        result.addProperty("next", "Follow it with jobs.get " + id + "; never retry.");
        return result;
    }

    public static JsonObject job(JsonObject row) {
        JsonObject result = new JsonObject();
        result.add("job", row);
        return result;
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
        int colon = id.lastIndexOf(':');
        Source source = colon < 0 ? null : sources.get(id.substring(0, colon));
        if (source == null) throw unknownJob(id);
        source.job(operation, id);
    }
}
