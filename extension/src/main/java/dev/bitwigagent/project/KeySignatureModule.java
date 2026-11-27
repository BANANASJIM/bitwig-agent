package dev.bitwigagent.project;

import com.bitwig.extension.controller.api.Project;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.Holds;
import dev.bitwigagent.core.HostModule;
import dev.bitwigagent.core.HostOperation;
import dev.bitwigagent.core.Outcome;
import dev.bitwigagent.core.Params;
import dev.bitwigagent.core.PortHandle;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.core.Results;
import dev.bitwigagent.core.Scheduler;
import dev.bitwigagent.core.Verification;
import dev.bitwigagent.host.TransportSafety;
import java.util.*;

/**
 * project.key.get and project.key.set: the project Key Signature (Root Key and Scale in the transport bar). The public
 * controller API has no key signature, so both run natively behind the version/JAR-hash gate. A write sets both
 * parameters in one native transaction (one Undo step) and reads them back from the model.
 */
public final class KeySignatureModule implements HostModule {
    static final String GET = "project.key.get", SET = "project.key.set";
    static final Set<String> METHODS = Set.of(GET, SET);
    /** Write order: the fields of project.key.set, each one host enum parameter. */
    static final List<String> FIELDS = List.of("root", "scale");

    private static final String HOLD = "project.key";
    private static final String HOLD_TEXT = "A project.key.set was dispatched and its readback did not confirm it.";

    /** One option of a host enum parameter: the raw value a write takes and the label Bitwig shows. */
    public static final class Option {
        final int value;
        final String label;

        public Option(int value, String label) {
            this.value = value;
            this.label = label;
        }
    }

    /** A host enum parameter: its current raw value and Bitwig's own option list. */
    public static final class Parameter {
        final int value;
        final List<Option> options;

        public Parameter(int value, List<Option> options) {
            this.value = value;
            this.options = List.copyOf(options);
        }

        Option option(int raw) {
            for (Option option : options) if (option.value == raw) return option;
            return null;
        }
    }

    /** The key signature as the native model holds it. */
    public static final class State {
        final Map<String, Parameter> fields = new LinkedHashMap<>();
        final String scaleName;

        public State(Parameter root, Parameter scale, String scaleName) {
            fields.put("root", root);
            fields.put("scale", scale);
            this.scaleName = scaleName;
        }
    }

    public interface Access {
        void execute(Runnable work);

        void provenance(JsonObject out);

        /** The key signature of the project behind the controller Project proxy; native document thread. */
        State read(Object projectProxy);

        /** Sets the raw values (by field, in {@link #FIELDS} order) in one native transaction; document thread. */
        void write(Object projectProxy, Map<String, Integer> values, Runnable beforeWrite);
    }

    /** The name of the one Undo step a write is, as Bitwig's Edit menu shows it. */
    public static final String UNDO_LABEL = "Set Key Signature";

    private final Scheduler scheduler;
    private final Project project;
    private final TransportSafety transportSafety;
    private final PortHandle<Access> access;
    private final Holds holds;

    public KeySignatureModule(
            Scheduler scheduler,
            Project project,
            TransportSafety transportSafety,
            PortHandle<Access> access,
            Holds holds) {
        this.scheduler = scheduler;
        this.project = project;
        this.transportSafety = transportSafety;
        this.access = access;
        this.holds = holds;
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
        if (method.equals(GET)) {
            Params.fields(params, Set.of(), Set.of());
            return;
        }
        if (!method.equals(SET)) throw Errors.notApplied(Reason.UNKNOWN_METHOD, "Unknown project key method.");
        Params.fields(params, Set.of(), new HashSet<>(FIELDS));
        if (params.size() == 0) throw Errors.invalid("project.key.set needs root or scale.");
        for (String field : FIELDS) {
            if (!params.has(field)) continue;
            JsonElement value = params.get(field);
            if (!value.isJsonPrimitive()
                    || !(value.getAsJsonPrimitive().isString() || isInteger(value.getAsJsonPrimitive())))
                throw Errors.invalid(field + " must be an option label or an integer option value.");
        }
    }

    private static boolean isInteger(JsonPrimitive value) {
        return value.isNumber() && value.getAsDouble() == Math.rint(value.getAsDouble());
    }

    @Override
    public void dispatch(HostOperation op, String method, JsonObject params) {
        scheduler.check(op);
        boolean write = method.equals(SET);
        if (write && holds.held(HOLD)) throw holds.blocked(HOLD, HOLD_TEXT, "Inspect project.key.get");
        Runnable guard = write ? () -> transportSafety.recheck(op) : () -> {};
        Access bridge = access.get(op);
        Runnable check = () -> {
            scheduler.check(op);
            guard.run();
        };
        bridge.execute(() -> {
            try {
                check.run();
                State before = bridge.read(project);
                JsonObject result = write ? set(op, bridge, before, params, check) : snapshot(before);
                bridge.provenance(result);
                scheduler.schedule(op, () -> op.result.complete(result), 0);
            } catch (RuntimeException | Error error) {
                op.fail(error);
            }
        });
    }

    /** Plans against the read state, writes the changed fields in one transaction, then compares a fresh read. */
    private JsonObject set(HostOperation op, Access bridge, State before, JsonObject params, Runnable check) {
        Map<String, Integer> wanted = new LinkedHashMap<>(), changed = new LinkedHashMap<>();
        for (String field : FIELDS)
            if (params.has(field)) wanted.put(field, raw(field, before.fields.get(field), params.get(field)));
        for (Map.Entry<String, Integer> entry : wanted.entrySet())
            if (before.fields.get(entry.getKey()).value != entry.getValue())
                changed.put(entry.getKey(), entry.getValue());
        State after = before;
        if (!changed.isEmpty()) {
            bridge.write(project, changed, () -> {
                check.run();
                op.markDispatched();
                holds.place(HOLD, HOLD_TEXT);
            });
            after = bridge.read(project);
        }
        JsonObject result = snapshot(after), fields = new JsonObject();
        Outcome worst = Outcome.ALREADY_MATCHED;
        for (Map.Entry<String, Integer> entry : wanted.entrySet()) {
            String field = entry.getKey();
            Parameter now = after.fields.get(field);
            JsonElement requested = params.get(field);
            JsonElement observed = requested.getAsJsonPrimitive().isString()
                    ? label(now.option(now.value))
                    : new JsonPrimitive(now.value);
            Outcome outcome = now.value != entry.getValue()
                    ? Outcome.UNKNOWN
                    : changed.containsKey(field) ? Outcome.APPLIED : Outcome.ALREADY_MATCHED;
            fields.add(
                    field,
                    outcome == Outcome.UNKNOWN
                            ? Results.unknownField(requested, observed, Reason.READBACK_MISMATCH)
                            : Results.field(requested, observed, outcome, null, Verification.NATIVE_READBACK));
            worst = worst.worst(outcome);
        }
        Results.stamp(result, worst, fields.asMap().values());
        if (worst == Outcome.ALREADY_MATCHED) Results.stamp(result, worst, Verification.NATIVE_READBACK);
        if (worst == Outcome.UNKNOWN) {
            result.addProperty("reason", Reason.READBACK_MISMATCH.wire());
            result.addProperty(
                    "message", "The key signature read back differs from the request; it was not written again.");
        } else holds.release(HOLD);
        result.add("fields", fields);
        result.addProperty("nativeUndo", !changed.isEmpty());
        if (!changed.isEmpty()) result.addProperty("undoLabel", UNDO_LABEL);
        return result;
    }

    /** The raw value a request names: an option label (case-insensitive) or an option value of Bitwig's own list. */
    static int raw(String field, Parameter parameter, JsonElement requested) {
        JsonPrimitive value = requested.getAsJsonPrimitive();
        for (Option option : parameter.options)
            if (value.isString()
                    ? option.label.equalsIgnoreCase(value.getAsString())
                    : option.value == value.getAsInt()) return option.value;
        throw Errors.notApplied(
                Reason.INVALID_PARAMS,
                field + " " + requested + " is not one of Bitwig's options; nothing was written.",
                "Read the options with project.key.get.");
    }

    static JsonObject snapshot(State state) {
        JsonObject out = new JsonObject();
        out.addProperty("scope", "project_key_signature");
        for (Map.Entry<String, Parameter> entry : state.fields.entrySet()) {
            Parameter parameter = entry.getValue();
            JsonArray options = new JsonArray();
            for (Option option : parameter.options) {
                JsonObject one = new JsonObject();
                one.addProperty("value", option.value);
                one.addProperty("label", option.label);
                options.add(one);
            }
            JsonObject unit = Results.unit(new JsonPrimitive(parameter.value), null, options);
            unit.add("label", label(parameter.option(parameter.value)));
            out.add(entry.getKey(), unit);
        }
        out.addProperty("scaleName", state.scaleName);
        Results.readBy(out, Verification.NATIVE_READBACK);
        return out;
    }

    private static JsonElement label(Option option) {
        return option == null ? JsonNull.INSTANCE : new JsonPrimitive(option.label);
    }
}
