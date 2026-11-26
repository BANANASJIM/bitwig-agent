package dev.bitwigagent;

import static dev.bitwigagent.HostLimits.*;

import com.bitwig.extension.controller.api.*;
import com.google.gson.*;
import dev.bitwigagent.core.Refs;
import java.util.*;

/**
 * parameters.list and parameters.set: one parameter of a device by its ref. Direct parameters of a root device or of a
 * device inside a chain are handled here; a Remote Control and a modulator control are written by their own modules.
 */
final class ParameterModule implements HostModule, ParameterResolver.DirectParameters {
    static final Set<String> METHODS = Set.of("parameters.list", "parameters.set");
    private static final int DISPLAY_CHARS = 64;
    /** How long the host gets to report a parameter's display text once it is observed. */
    private static final long DISPLAY_WAIT_NANOS = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(300);

    private final Scheduler scheduler;
    private final DeviceSources sources;
    private final TransportSafety transportSafety;
    private final RemoteModule remotes;
    private final ModulatorEditModule modulators;
    private final ParameterResolver resolver;
    private final Map<DeviceSources.Source, ParameterSource> parameterSources = new IdentityHashMap<>();
    private long valueSequence, displaySequence;

    ParameterModule(
            Scheduler scheduler,
            DeviceSources sources,
            TrackRefs trackRefs,
            TrackWindow window,
            TransportSafety transportSafety,
            RemoteModule remotes,
            ModulatorEditModule modulators) {
        this.scheduler = scheduler;
        this.sources = sources;
        this.transportSafety = transportSafety;
        this.remotes = remotes;
        this.modulators = modulators;
        resolver = new ParameterResolver(sources, trackRefs, window, this, remotes);
        for (DeviceSources.Source identity : sources.all) {
            ParameterSource source = new ParameterSource(identity);
            parameterSources.put(identity, source);
            identity.listeners.add((structural, name) -> {
                if (structural) {
                    source.parameters = null;
                    source.parameterIds = Collections.emptySet();
                    source.idsObserved = false;
                }
            });
            identity.device.addDirectParameterIdObserver(ids -> onParameterIds(source, (String[]) ids));
            identity.device.addDirectParameterNameObserver(256, (id, name) -> {
                if (acceptParameterObservation(source)) source.parameter(id).name = name;
            });
            identity.device.addDirectParameterNormalizedValueObserver((id, value) -> {
                if (!acceptParameterObservation(source)) return;
                ParameterState parameter = source.parameter(id);
                parameter.value = Double.isFinite(value) ? value : null;
                parameter.sequence = ++valueSequence;
            });
            // Observes no parameter until a request asks for one, so it costs nothing while idle.
            source.displays = identity.device.addDirectParameterValueDisplayObserver(DISPLAY_CHARS, (id, text) -> {
                if (!acceptParameterObservation(source)) return;
                ParameterState parameter = source.parameter(id);
                parameter.display = text;
                parameter.displaySequence = ++displaySequence;
            });
        }
    }

    @Override
    public Set<String> methods() {
        return METHODS;
    }

    /** What the schema cannot state: a host parameter takes a normalized number or a display, never a label or a flag. */
    @Override
    public void validate(String method, JsonObject params) {
        if (!method.equals("parameters.set")) return;
        ValueInput input = ValueInput.parse("value", params.get("value"));
        if (ParameterRefs.MODULATOR.equals(ParameterRefs.owner(params.getAsJsonObject("target")))) return;
        if (input.form == ValueInput.Form.LABEL)
            throw Errors.invalid("{label} is only for a modulator control's enum; write the value you read.");
        if (input.form == ValueInput.Form.BARE) input.normalized("value");
        JsonObject expect = params.has("expect") ? params.getAsJsonObject("expect") : null;
        if (expect != null && expect.get("value").getAsJsonPrimitive().isBoolean())
            throw Errors.invalid("expect.value must be a number from 0 to 1.");
    }

    @Override
    public void dispatch(HostOperation request, String method, JsonObject params) {
        if (method.equals("parameters.list")) list(request, params.getAsJsonObject("parent"));
        else set(request, params);
    }

    @Override
    public Set<String> ids(DeviceSources.Source device) {
        ParameterSource source = parameterSources.get(device);
        return source.idsObserved ? Collections.unmodifiableSet(source.parameterIds) : null;
    }

    @Override
    public String name(DeviceSources.Source device, String id) {
        ParameterSource source = parameterSources.get(device);
        ParameterState parameter = source.parameters == null ? null : source.parameters.get(id);
        return parameter == null || parameter.name.isEmpty() ? null : parameter.name;
    }

    // ---------------------------------------------------------------- list

    private void list(HostOperation op, JsonObject parentRef) {
        DeviceSources.Source device = sources.device(parentRef, false);
        ParameterSource source = parameterSources.get(device);
        RequestSnapshot snapshot = RequestSnapshot.of(device);
        scheduler.await(op, () -> device.directReady() && source.idsObserved, () -> {
            snapshot.require(op);
            gatherDisplays(op, device, source, snapshot);
        });
    }

    /** Asks the host for the display text of every parameter while the list is built, then stops asking. */
    private void gatherDisplays(
            HostOperation op, DeviceSources.Source device, ParameterSource source, RequestSnapshot snapshot) {
        String[] ids = source.parameterIds.toArray(new String[0]);
        if (ids.length == 0 || source.displayOwner != null) {
            op.result.complete(listing(device, source, -1));
            return;
        }
        long mark = displaySequence;
        claimDisplays(op, source);
        source.displays.setObservedParameterIds(ids);
        scheduler.poll(
                op,
                Math.min(scheduler.observeUntil(op), scheduler.now() + DISPLAY_WAIT_NANOS),
                () -> {
                    snapshot.require(op);
                    for (String id : ids) if (source.parameter(id).displaySequence <= mark) return false;
                    return true;
                },
                complete -> op.result.complete(listing(device, source, mark)));
    }

    private void claimDisplays(HostOperation op, ParameterSource source) {
        source.displayOwner = op;
        op.result.whenComplete((result, error) -> {
            if (source.displayOwner != op) return;
            source.displayOwner = null;
            source.displays.setObservedParameterIds(null);
        });
    }

    /** The rows; a display is shown only when it was reported after {@code mark} (none when mark is negative). */
    private JsonObject listing(DeviceSources.Source device, ParameterSource source, long mark) {
        JsonObject deviceRef = device.ref();
        JsonArray rows = new JsonArray();
        for (String id : source.parameterIds) {
            ParameterState parameter = source.parameter(id);
            JsonObject item = new JsonObject();
            item.add("ref", Refs.parameter(deviceRef, id, parameter.name.isEmpty() ? null : parameter.name));
            item.addProperty("name", parameter.name);
            boolean shown = mark >= 0 && parameter.displaySequence > mark;
            item.add("value", unit(parameter.value, shown ? parameter.display : null));
            rows.add(item);
        }
        JsonObject result = new JsonObject();
        result.add("parent", deviceRef);
        result.add("parameters", rows);
        result.addProperty("scope", "host_exposed_direct_parameters");
        return result;
    }

    private static JsonObject unit(Double value, String display) {
        return Results.unit(value == null ? null : new JsonPrimitive(value), display, null);
    }

    // ---------------------------------------------------------------- set

    private void set(HostOperation op, JsonObject params) {
        JsonObject target = params.getAsJsonObject("target");
        ValueInput input = ValueInput.parse("value", params.get("value"));
        JsonObject expect = params.has("expect") ? params.getAsJsonObject("expect") : null;
        String owner = ParameterRefs.owner(target);
        if (!List.of(ParameterRefs.DEVICE, ParameterRefs.REMOTE_PAGE, ParameterRefs.MODULATOR)
                .contains(owner))
            throw Errors.notApplied(
                    Reason.UNSUPPORTED_SCOPE,
                    "parameters.set does not take a parameter owned by a " + owner + ".",
                    owner.equals(ParameterRefs.TRACK) ? "tracks.set" : "grid.apply");
        ParameterResolver.Resolved resolved = resolver.resolve(target);
        if (resolved instanceof ParameterResolver.Direct)
            direct(op, (ParameterResolver.Direct) resolved, target, input, expect);
        else if (resolved instanceof ParameterResolver.Remote)
            remotes.setControl(op, (ParameterResolver.Remote) resolved, target, input, expect);
        else modulators.setParameter(op, (ParameterResolver.ModuleParameter) resolved, target, input, expect);
    }

    private void direct(
            HostOperation op, ParameterResolver.Direct target, JsonObject ref, ValueInput input, JsonObject expect) {
        ParameterSource source = parameterSources.get(target.device);
        ParameterState parameter = source.parameters == null ? null : source.parameters.get(target.id);
        if (parameter == null || parameter.value == null)
            throw Errors.notApplied(Reason.NOT_READY, "Parameter has no readable value.");
        RequestSnapshot snapshot = target.snapshot();
        if (expect != null) {
            JsonObject observed = new JsonObject();
            observed.addProperty("value", parameter.value);
            ValueMatch.requireExpected(expect, observed, Map.of("value", ValueMatch.NORMALIZED));
        }
        if (input.form == ValueInput.Form.DISPLAY) {
            solve(op, target, ref, input.text, snapshot, source, parameter);
            return;
        }
        double requested = input.normalized("value");
        double applied = step(requested);
        JsonElement asked = input.bare;
        if (Math.abs(parameter.value - applied) <= TOLERANCE) {
            op.result.complete(result(ref, asked, parameter.value, null, Outcome.ALREADY_MATCHED, null));
            return;
        }
        long before = parameter.sequence;
        scheduler.check(op);
        snapshot.require(op);
        op.markDispatched();
        write(target, applied);
        scheduler.poll(
                op,
                scheduler.observeUntil(op),
                () -> {
                    snapshot.require(op);
                    Double seen = observedAfter(parameter, before);
                    return seen != null && Math.abs(seen - applied) <= TOLERANCE;
                },
                observed -> {
                    snapshot.require(op);
                    Double seen = observedAfter(parameter, before);
                    op.result.complete(result(
                            ref,
                            asked,
                            seen,
                            null,
                            observed ? Outcome.APPLIED : Outcome.UNKNOWN,
                            observed ? null : seen != null ? Reason.READBACK_MISMATCH : Reason.NOT_OBSERVED));
                });
    }

    /** The value as the host stores it: the nearest of its fixed steps. */
    private static double step(double requested) {
        return Math.round(requested * (RESOLUTION - 1)) / (double) (RESOLUTION - 1);
    }

    private static void write(ParameterResolver.Direct target, double normalized) {
        target.device.device.setDirectParameterValueNormalized(
                target.id, Math.round(normalized * (RESOLUTION - 1)), RESOLUTION);
    }

    /** The parameter's value if its observer fired after {@code before}; null while that has not happened. */
    private static Double observedAfter(ParameterState parameter, long before) {
        return parameter.sequence > before && parameter.value != null ? parameter.value : null;
    }

    // ---------------------------------------------------------------- display writes

    /**
     * {display: "800 Hz"}: the host has no display-to-value setter, so the value is found by bisection against the
     * display text the host reports. Only a numeric display in the unit the parameter shows is solved, and only while
     * the transport is stopped, because every probe is a real write that may be heard.
     */
    private void solve(
            HostOperation op,
            ParameterResolver.Direct target,
            JsonObject ref,
            String text,
            RequestSnapshot snapshot,
            ParameterSource source,
            ParameterState parameter) {
        DisplayScale.Quantity wanted = DisplayScale.quantity(text);
        if (wanted == null)
            throw Errors.notApplied(
                    Reason.UNSUPPORTED_UNIT,
                    "A display write takes a number and its unit, like \"800 Hz\"; write the value you read.");
        Runnable safety = transportSafety.writeGuard(op, true);
        if (source.displayOwner != null)
            throw Errors.notApplied(Reason.BUSY, "Another request is reading this device's display texts.");
        long mark = displaySequence;
        claimDisplays(op, source);
        source.displays.setObservedParameterIds(new String[] {target.id});
        scheduler.poll(
                op,
                Math.min(scheduler.observeUntil(op), scheduler.now() + DISPLAY_WAIT_NANOS),
                () -> parameter.displaySequence > mark,
                shown -> {
                    if (!shown)
                        throw Errors.notApplied(Reason.NOT_READY, "The host did not report this parameter's display.");
                    DisplaySolver.Port port = new DisplaySolver.Port() {
                        @Override
                        public long valueSeq() {
                            return parameter.sequence;
                        }

                        @Override
                        public JsonElement value() {
                            return parameter.value == null ? JsonNull.INSTANCE : new JsonPrimitive(parameter.value);
                        }

                        @Override
                        public JsonElement display() {
                            return parameter.display == null || parameter.displaySequence <= mark
                                    ? JsonNull.INSTANCE
                                    : new JsonPrimitive(parameter.display);
                        }

                        @Override
                        public long displaySeq() {
                            return parameter.displaySequence;
                        }

                        @Override
                        public void write(double x) {
                            ParameterModule.write(target, x);
                        }

                        @Override
                        public void guard() {
                            safety.run();
                            scheduler.check(op);
                            snapshot.require(op);
                        }
                    };
                    new DisplaySolver(
                                    scheduler,
                                    op,
                                    port,
                                    wanted,
                                    outcome -> finishSolve(op, ref, text, wanted, parameter, outcome))
                            .start();
                });
    }

    private void finishSolve(
            HostOperation op,
            JsonObject ref,
            String text,
            DisplayScale.Quantity wanted,
            ParameterState parameter,
            DisplaySolver.Result solved) {
        JsonElement asked = new JsonObject();
        ((JsonObject) asked).addProperty("display", text);
        Outcome outcome = solved.ok
                ? solved.dispatched ? Outcome.APPLIED : Outcome.ALREADY_MATCHED
                : !solved.dispatched || solved.restored ? Outcome.NOT_APPLIED : Outcome.UNKNOWN;
        JsonObject result =
                result(ref, asked, parameter.value, solved.observedDisplay, outcome, solved.ok ? null : solved.reason);
        result.add("displayWrite", solved.json(text, wanted.value));
        op.result.complete(result);
    }

    // ---------------------------------------------------------------- results

    /** {outcome, ref, fields:{value}}: what was asked, and what the host shows when that differs. */
    private static JsonObject result(
            JsonObject ref, JsonElement requested, Double observed, String display, Outcome outcome, Reason reason) {
        JsonObject result = Results.outcome(outcome);
        if (reason != null) result.addProperty("reason", reason.wire());
        result.add("ref", ref);
        JsonObject fields = new JsonObject();
        fields.add(
                "value", Results.field(requested, observed == null ? null : unit(observed, display), outcome, reason));
        result.add("fields", fields);
        return result;
    }

    // ---------------------------------------------------------------- observations

    private void onParameterIds(ParameterSource source, String[] ids) {
        if (!acceptParameterObservation(source)) return;
        Set<String> next = ids.length == 0 ? Collections.emptySet() : new LinkedHashSet<>(Arrays.asList(ids));
        source.parameterIds = next;
        if (source.parameters != null) source.parameters.keySet().retainAll(next);
        for (String id : next) source.parameter(id);
        source.idsObserved = true;
    }

    private boolean acceptParameterObservation(ParameterSource source) {
        // Registration identifies a fixed proxy, not the originating physical device.
        // Nested sources that physically rebound stay closed; a callback is not a barrier.
        return source.identity.acceptDirectObservation();
    }

    private static final class ParameterSource {
        final DeviceSources.Source identity;
        Map<String, ParameterState> parameters;
        Set<String> parameterIds = Collections.emptySet();
        boolean idsObserved;
        DirectParameterValueDisplayObserver displays;
        /** The request that currently has this device's display texts observed. */
        HostOperation displayOwner;

        ParameterSource(DeviceSources.Source identity) {
            this.identity = identity;
        }

        ParameterState parameter(String id) {
            if (parameters == null) parameters = new LinkedHashMap<>();
            return parameters.computeIfAbsent(id, ParameterState::new);
        }
    }

    private static final class ParameterState {
        final String id;
        String name = "", display;
        Double value;
        long sequence, displaySequence;

        ParameterState(String id) {
            this.id = id;
        }
    }
}
