package dev.bitwigagent.device;

import static dev.bitwigagent.core.HostLimits.RESOLUTION;
import static dev.bitwigagent.device.ChainStrip.*;

import com.google.gson.*;
import dev.bitwigagent.core.DisplayScale;
import dev.bitwigagent.core.DisplaySolver;
import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.HostModule;
import dev.bitwigagent.core.HostOperation;
import dev.bitwigagent.core.MethodSpecs;
import dev.bitwigagent.core.Outcome;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.core.Refs;
import dev.bitwigagent.core.Results;
import dev.bitwigagent.core.RpcException;
import dev.bitwigagent.core.Scheduler;
import dev.bitwigagent.core.ValueInput;
import dev.bitwigagent.core.ValueMatch;
import dev.bitwigagent.core.Verification;
import dev.bitwigagent.host.TransportSafety;
import java.util.*;
import java.util.function.Consumer;

/**
 * chains.set (a chain's mixer strip and a Selector's active chain) and chains.insert (a new empty chain in a layer
 * device, a Selector or a Drum Machine pad), read, written and proven natively on each request like every chain.
 */
public final class ChainModule implements HostModule {
    static final Set<String> METHODS = Set.of("chains.set", "chains.insert");
    private static final String[] FIELDS = {VOLUME, PAN, MUTE, SOLO, ACTIVE};
    private static final Map<String, ValueMatch> RULES = Map.of(
            VOLUME, ValueMatch.NORMALIZED,
            PAN, ValueMatch.NORMALIZED,
            MUTE, ValueMatch.EXACT,
            SOLO, ValueMatch.EXACT,
            ACTIVE, ValueMatch.EXACT);
    private final Scheduler scheduler;
    private final NativeChains chains;
    private final TransportSafety transportSafety;
    private HostOperation insertion;

    public ChainModule(Scheduler scheduler, NativeChains chains, TransportSafety transportSafety) {
        this.scheduler = scheduler;
        this.chains = chains;
        this.transportSafety = transportSafety;
    }

    @Override
    public Set<String> methods() {
        return METHODS;
    }

    @Override
    public void validate(String method, JsonObject params) {}

    @Override
    public void dispatch(HostOperation op, String method, JsonObject p) {
        if (method.equals("chains.insert")) insert(op, p);
        else set(op, new Request(p));
    }

    // ---------------------------------------------------------------- chains.set

    /** One chains.set call: the fields asked for, normalized values as they will be written, display targets. */
    private static final class Request {
        final JsonObject params, target, expect;
        final Map<String, JsonElement> wanted = new LinkedHashMap<>();
        final List<Solve> solves = new ArrayList<>();
        /** Fields a display solve already moved: whether it wrote anything, and the value it started from. */
        final Map<String, DisplaySolver.Result> solved = new LinkedHashMap<>();

        final JsonObject displayWrite = new JsonObject();

        Request(JsonObject p) {
            params = p;
            target = p.getAsJsonObject("target");
            expect = p.has("expect") ? p.getAsJsonObject("expect") : null;
            for (String field : FIELDS) {
                if (!p.has(field)) continue;
                if (!field.equals(VOLUME) && !field.equals(PAN)) {
                    wanted.put(field, p.get(field));
                    continue;
                }
                ValueInput input = ValueInput.parse(field, p.get(field));
                if (input.form == ValueInput.Form.DISPLAY) solves.add(new Solve(field, input.text));
                else wanted.put(field, new JsonPrimitive(step(input.normalized(field))));
            }
        }
    }

    /** A volume or pan given as display text, found by bisection on the host's own display. */
    private static final class Solve {
        final String field, text;
        final DisplayScale.Kind kind;
        final double target;

        Solve(String field, String text) {
            this.field = field;
            this.text = text;
            kind = field.equals(PAN) ? DisplayScale.Kind.PERCENT : DisplayScale.Kind.DB;
            target = DisplayScale.parse(kind, text);
            if (Double.isNaN(target))
                throw Errors.notApplied(
                        Reason.UNSUPPORTED_UNIT, "The display text of " + field + " is not understood: " + text);
        }
    }

    private void set(HostOperation op, Request request) {
        if (request.solves.isEmpty()) write(op, request);
        else solve(op, request, 0);
    }

    /** Solves display fields one after another; a failure puts the fields solved before it back. */
    private void solve(HostOperation op, Request request, int index) {
        if (index == request.solves.size()) {
            write(op, request);
            return;
        }
        Solve solve = request.solves.get(index);
        JsonObject target = request.target;
        NativeParameterPort port = new NativeParameterPort(
                chains,
                scheduler,
                op,
                target,
                walk -> parameter(walk, target, solve.field),
                transportSafety.atLeast(op, MethodSpecs.Transport.STOPPED));
        chains.async(
                op,
                target,
                walk -> {
                    if (index == 0) {
                        ChainStrip now = strip(walk, target);
                        requireExpected(request.expect, now);
                        preflight(walk.a, now, request);
                    }
                    port.bind(walk);
                    scheduler.schedule(op, () -> start(op, request, index, port), 0);
                },
                op::fail);
    }

    private void start(HostOperation op, Request request, int index, NativeParameterPort port) {
        Solve solve = request.solves.get(index);
        try {
            new DisplaySolver(scheduler, op, port, solve.kind, solve.target, result -> {
                        request.displayWrite.add(solve.field, result.json(solve.text, solve.target));
                        if (!result.ok) {
                            restore(op, request, restored -> failed(op, request, result, restored));
                            return;
                        }
                        request.solved.put(solve.field, result);
                        request.wanted.put(solve.field, new JsonPrimitive(result.normalized));
                        solve(op, request, index + 1);
                    })
                    .start();
        } catch (RpcException error) {
            if (request.solved.isEmpty()) throw error;
            DisplaySolver.Result failure = new DisplaySolver.Result();
            failure.reason = error.reason;
            restore(op, request, restored -> failed(op, request, failure, restored));
        }
    }

    /** Puts the fields solved earlier in this call back to where they started; {@code done} hears whether it held. */
    private void restore(HostOperation op, Request request, Consumer<Boolean> done) {
        Map<String, Double> back = new LinkedHashMap<>();
        request.solved.forEach((field, result) -> {
            if (result.dispatched) back.put(field, result.original);
        });
        if (back.isEmpty()) {
            done.accept(true);
            return;
        }
        chains.async(
                op,
                request.target,
                walk -> {
                    boolean all = true;
                    for (Map.Entry<String, Double> field : back.entrySet()) {
                        Object parameter = parameter(walk, request.target, field.getKey());
                        walk.a.write(parameter, field.getValue());
                        all &= ValueMatch.NORMALIZED.matches(
                                new JsonPrimitive(field.getValue()), new JsonPrimitive(walk.a.value(parameter)));
                    }
                    boolean restored = all;
                    scheduler.schedule(op, () -> done.accept(restored), 0);
                },
                error -> scheduler.schedule(op, () -> done.accept(false), 0));
    }

    /** A display that could not be reached: nothing changed when everything was put back, otherwise unknown. */
    private static void failed(HostOperation op, Request request, DisplaySolver.Result result, boolean restored) {
        boolean clean = restored && (!result.dispatched || result.restored);
        JsonObject out = clean ? Results.notApplied() : Results.unknown();
        out.addProperty("reason", (result.reason != null ? result.reason : Reason.READBACK_MISMATCH).wire());
        out.add("ref", request.target);
        out.add("displayWrite", request.displayWrite);
        op.result.complete(out);
    }

    /** Observe, decide and write in one document-thread pass, then read the strip back in the same pass. */
    private void write(HostOperation op, Request request) {
        JsonObject target = request.target;
        chains.run(op, target, walk -> {
            NativeChains.Chain chain = walk.chain(target, true);
            ChainStrip before = strip(walk, chain);
            if (request.solved.isEmpty()) requireExpected(request.expect, before);
            List<String> writes = plan(walk.a, before, request.wanted);
            List<Boolean> others = writes.contains(SOLO) ? othersSolo(walk, chain) : null;
            if (!writes.isEmpty()) {
                walk.dispatch();
                for (String field : writes) apply(walk.a, chain, before, field, request.wanted.get(field));
            }
            boolean othersKept = others == null || others.equals(othersSolo(walk, chain));
            return result(walk, request, chain, strip(walk, chain), writes, othersKept);
        });
    }

    /** The solo state of the chain's siblings, in order; a chain without a mixer counts as not soloed. */
    private static List<Boolean> othersSolo(NativeChains.Walk walk, NativeChains.Chain chain) {
        List<Boolean> solos = new ArrayList<>();
        for (Object other : walk.a.chains(chain.owner)) {
            if (other == chain.chain) continue;
            NativeChains.Mixer mixer = walk.a.mixer(other);
            solos.add(mixer != null && mixer.solo);
        }
        return solos;
    }

    /**
     * Before the first display probe: every asked field must exist on this chain and a solved fader must be writable,
     * so a call plan() would refuse later never leaves probed values behind.
     */
    private static void preflight(NativeChains.Access a, ChainStrip now, Request request) {
        for (String field : FIELDS) {
            if (!request.params.has(field)) continue;
            requirePresent(now, field);
            boolean solved = request.solves.stream().anyMatch(solve -> solve.field.equals(field));
            if (solved) requireWritable(a, now, field);
        }
    }

    private static void requirePresent(ChainStrip now, String field) {
        if (now.bare(field).isJsonNull())
            throw Errors.notApplied(
                    Reason.UNSUPPORTED_SCOPE,
                    field.equals(ACTIVE)
                            ? "Only a Selector's chain can be the active one."
                            : "The chain has no mixer.");
    }

    private static void requireWritable(NativeChains.Access a, ChainStrip now, String field) {
        if (!a.writable(now.parameter(field)))
            throw Errors.notApplied(Reason.INVALID_TARGET, "The host does not let " + field + " be written now.");
    }

    /**
     * The fields to write: those that differ from what was read. A field the chain does not have, or a parameter the
     * host will not let be written, refuses the whole call before anything is written.
     */
    static List<String> plan(NativeChains.Access a, ChainStrip now, Map<String, JsonElement> wanted) {
        List<String> writes = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : wanted.entrySet()) {
            String field = entry.getKey();
            requirePresent(now, field);
            if (RULES.get(field).matches(entry.getValue(), now.bare(field))) continue;
            if (field.equals(VOLUME) || field.equals(PAN)) requireWritable(a, now, field);
            writes.add(field);
        }
        return writes;
    }

    private static void apply(
            NativeChains.Access a, NativeChains.Chain chain, ChainStrip strip, String field, JsonElement value) {
        switch (field) {
            case VOLUME:
            case PAN:
                a.write(strip.parameter(field), value.getAsDouble());
                return;
            case MUTE:
                a.write(strip.mixer.mute, value.getAsBoolean() ? 1 : 0);
                return;
            case SOLO:
                a.solo(chain.chain, value.getAsBoolean());
                return;
            default:
                a.activeChain(chain.owner, chain.position);
        }
    }

    /**
     * Each asked field's outcome against the strip read back; a display-solved field counts its own probes. A solo
     * that changed another chain's solo does not hold either.
     */
    private static JsonObject result(
            NativeChains.Walk walk,
            Request request,
            NativeChains.Chain chain,
            ChainStrip after,
            List<String> writes,
            boolean othersKept) {
        JsonObject fields = new JsonObject();
        for (String field : FIELDS) {
            if (!request.params.has(field)) continue;
            DisplaySolver.Result solved = request.solved.get(field);
            boolean written = writes.contains(field) || solved != null && solved.dispatched;
            boolean holds = RULES.get(field).matches(request.wanted.get(field), after.bare(field))
                    && (othersKept || !field.equals(SOLO));
            Outcome outcome = !holds ? Outcome.UNKNOWN : written ? Outcome.APPLIED : Outcome.ALREADY_MATCHED;
            fields.add(
                    field,
                    Results.field(
                            request.params.get(field),
                            after.read(field),
                            outcome,
                            holds ? null : Reason.READBACK_MISMATCH,
                            Verification.NATIVE_READBACK));
        }
        JsonObject result = new JsonObject();
        Outcome worst = Results.worst(fields.asMap().values());
        if (worst == Outcome.APPLIED)
            Results.stamp(result, worst, fields.asMap().values());
        else Results.stamp(result, worst, Verification.NATIVE_READBACK);
        if (worst == Outcome.UNKNOWN) result.addProperty("reason", Reason.READBACK_MISMATCH.wire());
        if (worst == Outcome.APPLIED) walk.a.provenance(result);
        result.add("ref", chain.ref);
        result.add("fields", fields);
        if (request.displayWrite.size() > 0) result.add("displayWrite", request.displayWrite);
        return result;
    }

    private static void requireExpected(JsonObject expect, ChainStrip now) {
        if (expect == null) return;
        JsonObject observed = new JsonObject();
        for (String field : expect.keySet()) observed.add(field, now.bare(field));
        ValueMatch.requireExpected(expect, observed, RULES);
    }

    private static ChainStrip strip(NativeChains.Walk walk, JsonObject target) {
        return strip(walk, walk.chain(target, true));
    }

    private static ChainStrip strip(NativeChains.Walk walk, NativeChains.Chain chain) {
        return ChainStrip.read(walk.a, chain, walk.a.activeChain(chain.owner));
    }

    private static NativeChains.Mixer mixer(NativeChains.Walk walk, JsonObject target) {
        NativeChains.Mixer mixer = walk.a.mixer(walk.chain(target, true).chain);
        if (mixer == null) throw Errors.notApplied(Reason.UNSUPPORTED_SCOPE, "The chain has no mixer.");
        return mixer;
    }

    private static Object parameter(NativeChains.Walk walk, JsonObject target, String field) {
        NativeChains.Mixer mixer = mixer(walk, target);
        return field.equals(VOLUME) ? mixer.volume : mixer.pan;
    }

    /** The value as the host stores it: the nearest of its fixed steps. */
    private static double step(double requested) {
        return Math.round(requested * (RESOLUTION - 1)) / (double) (RESOLUTION - 1);
    }

    // ---------------------------------------------------------------- chains.insert

    private void insert(HostOperation op, JsonObject p) {
        if (insertion != null && !insertion.result.isDone())
            throw Errors.notApplied(Reason.BUSY, "Another chain insertion is awaiting observations.");
        insertion = op;
        JsonObject to = p.getAsJsonObject("to");
        Integer pad = p.has("pad") ? p.get("pad").getAsInt() : null;
        chains.edit(op, to, walk -> {
            Object owner = walk.owner(to);
            NativeChains.Container container = walk.a.container(owner);
            if (container == NativeChains.Container.NONE)
                throw Errors.notApplied(
                        Reason.UNSUPPORTED_SCOPE,
                        "Only Instrument Layer, FX Layer, the Selectors and Drum Machine take a new chain.");
            boolean pads = container == NativeChains.Container.PADS;
            if (pads != (pad != null))
                throw Errors.invalid(
                        pads
                                ? "A Drum Machine needs pad: the MIDI pitch of the pad (36 is C1)."
                                : "pad applies only to a Drum Machine.");
            List<Object> before = walk.a.chains(owner);
            if (pads) {
                Object existing = walk.a.padChain(owner, pad);
                if (existing != null)
                    return NativeChainEdits.already(
                            inserted(walk, to, pad, before, before, existing, Outcome.ALREADY_MATCHED));
            }
            walk.dispatch();
            if (pads) walk.a.createPadChain(owner, pad);
            else walk.a.addChain(owner);
            return new NativeChains.Verify() {
                @Override
                public JsonObject done(NativeChains.Walk now) {
                    Object device = now.owner(to);
                    List<Object> after = now.a.chains(device);
                    Object created = pads ? now.a.padChain(device, pad) : added(before, after);
                    if (created == null && NativeChainEdits.sameOrder(after, before)) return null;
                    boolean ok = created != null
                            && after.size() == before.size() + 1
                            && NativeChainEdits.sameOrder(NativeChainEdits.without(after, created), before);
                    return inserted(now, to, pad, before, after, created, ok ? Outcome.APPLIED : null);
                }

                @Override
                public JsonObject timeout(NativeChains.Walk now) {
                    List<Object> after = now.a.chains(now.owner(to));
                    return inserted(now, to, pad, before, after, null, null);
                }
            };
        });
    }

    /** The one chain in {@code after} that {@code before} did not have, or null. */
    private static Object added(List<Object> before, List<Object> after) {
        List<Object> fresh = new ArrayList<>(after);
        fresh.removeIf(chain -> before.stream().anyMatch(old -> old == chain));
        return fresh.size() == 1 ? fresh.get(0) : null;
    }

    /**
     * chains.insert's result: the new chain's ref under the parent as read now; {@code outcome} null is unknown,
     * readback_mismatch when the chains changed another way and not_observed when they did not change in time.
     */
    private static JsonObject inserted(
            NativeChains.Walk walk,
            JsonObject to,
            Integer pad,
            List<Object> before,
            List<Object> after,
            Object chain,
            Outcome outcome) {
        JsonObject result = Results.outcome(outcome != null ? outcome : Outcome.UNKNOWN, Verification.NATIVE_READBACK);
        if (outcome == null)
            result.addProperty(
                    "reason",
                    (NativeChainEdits.sameOrder(after, before) ? Reason.NOT_OBSERVED : Reason.READBACK_MISMATCH)
                            .wire());
        else walk.a.provenance(result);
        JsonObject parent = NativeChainEdits.current(walk, to);
        result.add("parent", parent);
        if (pad != null) result.addProperty("pad", pad);
        result.add(
                "ref",
                outcome == null
                        ? JsonNull.INSTANCE
                        : Refs.chain(parent, walk.a.chainId(chain).toString(), walk.a.chainName(chain)));
        result.addProperty("beforeCount", before.size());
        result.addProperty("afterCount", after.size());
        return result;
    }
}
