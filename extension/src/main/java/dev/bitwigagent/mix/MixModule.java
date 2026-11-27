package dev.bitwigagent.mix;

import com.bitwig.extension.callback.*;
import com.bitwig.extension.controller.api.*;
import com.google.gson.*;
import dev.bitwigagent.core.DisplayScale;
import dev.bitwigagent.core.DisplaySolver;
import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.HostLimits;
import dev.bitwigagent.core.HostModule;
import dev.bitwigagent.core.HostOperation;
import dev.bitwigagent.core.Outcome;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.core.Refs;
import dev.bitwigagent.core.Results;
import dev.bitwigagent.core.RpcException;
import dev.bitwigagent.core.Scheduler;
import dev.bitwigagent.core.ValueInput;
import dev.bitwigagent.core.ValueMatch;
import dev.bitwigagent.core.Verification;
import dev.bitwigagent.host.EnumOptions;
import dev.bitwigagent.host.SelectionLease;
import dev.bitwigagent.host.TrackRefs;
import dev.bitwigagent.host.TrackWindow;
import java.util.*;

/** Ref-addressed mix operations over the single shared, bounded track bank. */
public final class MixModule implements HostModule {
    static final Set<String> METHODS = Set.of("tracks.get", "tracks.set", "tracks.select", "tracks.sends.set");

    private static final String[] FIELDS = {
        "name", "volume", "pan", "mute", "solo", "arm", "selected", "color", "activated", "monitorMode", "crossFadeMode"
    };
    private static final int NAME = 0, VOLUME = 1, PAN = 2, SELECTED = 6, COLOR = 7, MONITOR = 9, CROSS_FADE = 10;
    /** The first FIELDS entries every track reports; the later ones never decide observationsComplete. */
    private static final int BASE_FIELDS = 7;

    private static final Set<String> WRITABLE = Set.of(
            "name", "volume", "pan", "mute", "solo", "arm", "color", "activated", "monitorMode", "crossFadeMode");
    private static final Map<String, ValueMatch> TRACK_RULES = rules(Set.of("volume", "pan"), WRITABLE);
    private static final Map<String, ValueMatch> SEND_RULES = rules(Set.of("value"), Set.of("value", "enabled"));
    private final Scheduler scheduler;
    private final TrackWindow window;
    private final TrackRefs trackRefs;
    private final SelectionLease selection;
    private final State[] states = new State[HostLimits.TRACKS];

    public MixModule(
            ControllerHost host,
            Scheduler scheduler,
            TrackWindow window,
            TrackRefs trackRefs,
            SelectionLease selection) {
        this.scheduler = scheduler;
        this.window = window;
        this.trackRefs = trackRefs;
        this.selection = selection;
        for (int i = 0; i < states.length; i++) {
            Track track = window.at(i);
            State state = new State(track, host);
            states[i] = state;
            track.channelId().addValueObserver((StringValueChangedCallback) value -> state.identity((String) value));
            track.exists().addValueObserver((BooleanValueChangedCallback) state::existence);
            observe(state, "type", track.trackType());
            observe(state, "index", track.position());
            track.name().addValueObserver((StringValueChangedCallback) v -> state.seen(NAME));
            track.volume().value().addValueObserver((DoubleValueChangedCallback) v -> state.seen(VOLUME));
            track.pan().value().addValueObserver((DoubleValueChangedCallback) v -> state.seen(PAN));
            track.mute().addValueObserver((BooleanValueChangedCallback) v -> state.seen(3));
            track.solo().addValueObserver((BooleanValueChangedCallback) v -> state.seen(4));
            track.arm().addValueObserver((BooleanValueChangedCallback) v -> state.seen(5));
            track.addIsSelectedInEditorObserver(v -> {
                state.selected = v;
                state.seen(SELECTED);
            });
            track.color().addValueObserver((ColorValueChangedCallback) (r, g, b) -> state.seen(COLOR));
            track.isActivated().addValueObserver((BooleanValueChangedCallback) v -> state.seen(8));
            track.monitorMode().addValueObserver((EnumValueChangedCallback) v -> state.seen(MONITOR));
            track.crossFadeMode().addValueObserver((EnumValueChangedCallback) v -> state.seen(CROSS_FADE));
            observe(state, "volumeDisplay", track.volume().displayedValue());
            observe(state, "panDisplay", track.pan().displayedValue());
            SendBank sends = track.sendBank();
            observe(state, "sendCount", sends.itemCount());
            observe(state, "sendOffset", sends.scrollPosition());
            for (int j = 0; j < HostLimits.SENDS; j++) {
                Send send = (Send) sends.getItemAt(j);
                String prefix = "send" + j + ".";
                observe(state, prefix + "exists", send.exists());
                observe(state, prefix + "name", send.name());
                observe(state, prefix + "value", send.value());
                observe(state, prefix + "display", send.displayedValue());
                observe(state, prefix + "enabled", send.isEnabled());
                observe(state, prefix + "preFader", send.isPreFader());
            }
        }
    }

    @Override
    public Set<String> methods() {
        return METHODS;
    }

    @Override
    public void flush() {
        for (State state : states) state.flush();
    }

    @Override
    public void validate(String method, JsonObject p) {
        validateParams(method, p);
    }

    /** What the schema cannot say: a write changes something. */
    static void validateParams(String method, JsonObject p) {
        ValueMatch.requireNamed(p);
        if (method.equals("tracks.set") && WRITABLE.stream().noneMatch(p::has))
            throw Errors.invalid("tracks.set requires at least one changed field.");
        if (method.equals("tracks.sends.set") && !p.has("value") && !p.has("enabled"))
            throw Errors.invalid("tracks.sends.set requires value or enabled.");
    }

    private static Map<String, ValueMatch> rules(Set<String> normalized, Set<String> keys) {
        Map<String, ValueMatch> out = new HashMap<>();
        for (String key : keys) out.put(key, normalized.contains(key) ? ValueMatch.NORMALIZED : ValueMatch.EXACT);
        return out;
    }

    @Override
    public void dispatch(HostOperation op, String method, JsonObject p) {
        scheduler.check(op);
        if (method.equals("tracks.sends.set")) {
            sendSet(op, p);
            return;
        }
        boolean write = method.equals("tracks.set");
        Target target = target(p.getAsJsonObject("target"), write);
        if (method.equals("tracks.get")) {
            op.result.complete(snapshot(target));
            return;
        }
        if (method.equals("tracks.select")) {
            guard(op, target);
            selection.requireFree();
            op.markDispatched();
            target.state.track.selectInEditor();
            guard(op, target);
            JsonObject result = Results.sent();
            result.add("ref", ref(target));
            op.result.complete(result);
            return;
        }
        JsonObject changes = new JsonObject();
        for (String key : WRITABLE) if (p.has(key)) changes.add(key, p.get(key));
        if (changes.has("color"))
            changes.addProperty("color", changes.get("color").getAsString().toLowerCase(Locale.ROOT));
        if (p.has("expect")) requireExpected(target, p.getAsJsonObject("expect"));
        // The host's own option list validates the enums before any write or display probe.
        if (changes.has("monitorMode"))
            EnumOptions.require(
                    "monitorMode",
                    target.state.track.monitorMode(),
                    changes.get("monitorMode").getAsString());
        if (changes.has("crossFadeMode"))
            EnumOptions.require(
                    "crossFadeMode",
                    target.state.track.crossFadeMode(),
                    changes.get("crossFadeMode").getAsString());
        List<Solve> solves = new ArrayList<>();
        for (String key : List.of("volume", "pan")) {
            if (!changes.has(key)) continue;
            ValueInput input = ValueInput.parse(key, changes.get(key));
            if (input.form == ValueInput.Form.BARE) changes.addProperty(key, input.normalized(key));
            else solves.add(new Solve(key, input.text));
        }
        // Parse every display target before the first probe write: a bad string must never reach the host.
        for (Solve solve : solves) solve.parse();
        solveNext(op, target, changes, solves, 0);
    }

    /** One display-addressed mixer value, resolved to a normalized value by DisplaySolver before the ordinary set flow. */
    private static final class Solve {
        final String key, displayKey, text;
        final int field;
        final DisplayScale.Kind kind;
        double target, original;
        long restoreSeq;

        Solve(String key, String text) {
            this.key = key;
            this.text = text;
            boolean pan = key.equals("pan");
            field = pan ? PAN : VOLUME;
            displayKey = pan ? "panDisplay" : "volumeDisplay";
            kind = pan ? DisplayScale.Kind.PERCENT : DisplayScale.Kind.DB;
        }

        void parse() {
            target = DisplayScale.parse(kind, text);
            if (Double.isNaN(target))
                throw Errors.notApplied(
                        Reason.UNSUPPORTED_UNIT, "The display text of " + key + " is not understood: " + text);
        }
    }

    private void solveNext(HostOperation op, Target target, JsonObject changes, List<Solve> solves, int index) {
        if (index == solves.size()) {
            set(op, target, changes, null, null);
            return;
        }
        Solve solve = solves.get(index);
        State state = target.state;
        DisplaySolver.Port port = new DisplaySolver.Port() {
            @Override
            public long valueSeq() {
                return state.sequence[solve.field];
            }

            @Override
            public JsonElement value() {
                return MixModule.value(state, solve.field);
            }

            @Override
            public JsonElement display() {
                return state.meta(solve.displayKey);
            }

            @Override
            public void write(double x) {
                if (solve.field == VOLUME) state.track.volume().value().setImmediately(x);
                else state.track.pan().value().setImmediately(x);
            }

            @Override
            public void guard() {
                MixModule.this.guard(op, target);
            }
        };
        try {
            new DisplaySolver(scheduler, op, port, solve.kind, solve.target, result -> {
                        target.displayWrite.add(solve.key, result.json(solve.text, solve.target));
                        if (!result.ok) {
                            restoreEarlier(
                                    op, target, solves, index, restored -> solverFailure(op, target, result, restored));
                            return;
                        }
                        solve.original = result.original;
                        changes.addProperty(solve.key, result.normalized);
                        target.solved.add(solve.key);
                        solveNext(op, target, changes, solves, index + 1);
                    })
                    .start();
        } catch (RpcException error) {
            if (index == 0) throw error;
            DisplaySolver.Result failed = new DisplaySolver.Result();
            failed.reason = error.reason;
            restoreEarlier(op, target, solves, index, restored -> solverFailure(op, target, failed, restored));
        }
    }

    /** Fields solved earlier in the same call stay at their probed value; put them back so a failed call changes nothing. */
    private void restoreEarlier(
            HostOperation op, Target target, List<Solve> solves, int upTo, java.util.function.Consumer<Boolean> done) {
        List<Solve> pending = new ArrayList<>();
        try {
            for (int i = 0; i < upTo; i++) {
                Solve earlier = solves.get(i);
                guard(op, target);
                JsonElement now = value(target.state, earlier.field);
                if (!now.isJsonNull() && Math.abs(now.getAsDouble() - earlier.original) <= HostLimits.TOLERANCE)
                    continue;
                earlier.restoreSeq = target.state.sequence[earlier.field];
                Track track = target.state.track;
                if (earlier.field == VOLUME) track.volume().value().setImmediately(earlier.original);
                else track.pan().value().setImmediately(earlier.original);
                pending.add(earlier);
            }
        } catch (RpcException error) {
            done.accept(false);
            return;
        }
        scheduler.poll(
                op,
                scheduler.now() + DisplaySolver.PROBE_TIMEOUT_NANOS,
                () -> restored(target, pending) || !target.matches(window),
                finished -> done.accept(restored(target, pending)));
    }

    private boolean restored(Target target, List<Solve> pending) {
        boolean all = target.matches(window);
        if (all)
            for (Solve earlier : pending) {
                JsonElement now = value(target.state, earlier.field);
                all &= target.state.sequence[earlier.field] > earlier.restoreSeq
                        && !now.isJsonNull()
                        && Math.abs(now.getAsDouble() - earlier.original) <= HostLimits.TOLERANCE;
            }
        return all;
    }

    /** A failed display write applied nothing else; it either put the original back (not applied) or leaves the outcome unknown. */
    private void solverFailure(HostOperation op, Target target, DisplaySolver.Result result, boolean earlierRestored) {
        boolean current = target.matches(window);
        JsonObject out = new JsonObject();
        if (current) out.add("ref", ref(target));
        boolean restored = (!result.dispatched || result.restored) && earlierRestored;
        if (restored && current) Results.notApplied(out);
        else Results.unknown(out);
        out.addProperty("reason", result.reason.wire());
        out.addProperty("otherFieldsApplied", !earlierRestored);
        out.add("displayWrite", target.displayWrite);
        op.result.complete(out);
    }

    /** Every key of expect must equal the value observed now, else nothing is written. */
    private void requireExpected(Target target, JsonObject expect) {
        guard(null, target);
        JsonObject observed = new JsonObject();
        for (String key : expect.keySet()) {
            JsonElement now = value(target.state, List.of(FIELDS).indexOf(key));
            if (now.isJsonNull()) throw Errors.notApplied(Reason.NOT_READY, key + " is not observed yet.");
            observed.add(key, now);
        }
        JsonObject lowered = expect.deepCopy();
        if (lowered.has("color"))
            lowered.addProperty("color", lowered.get("color").getAsString().toLowerCase(Locale.ROOT));
        ValueMatch.requireExpected(lowered, observed, TRACK_RULES);
    }

    /** Names a track this module did not create a ref for: the same write flow, with {@code info} added to the result. */
    void rename(HostOperation op, String id, String name, JsonObject info, String infoKey) {
        Target target = new Target(id, states[window.trackSlot(id)]);
        JsonObject changes = new JsonObject();
        changes.addProperty("name", name);
        set(op, target, changes, info, infoKey);
    }

    private void set(HostOperation op, Target target, JsonObject changes, JsonObject info, String infoKey) {
        Map<String, Long> writes = new LinkedHashMap<>();
        try {
            for (int i = 0; i < FIELDS.length; i++) {
                String key = FIELDS[i];
                if (!changes.has(key)) continue;
                guard(op, target);
                if (target.state.fresh(i) && matchesField(i, value(target.state, i), changes.get(key))) {
                    writes.put(key, -1L);
                    continue;
                }
                long before = target.state.sequence[i];
                guard(op, target);
                // No callback has ever reported this field: the getter is the only evidence, and an equal write raises
                // none.
                if (before == 0 && matchesField(i, raw(target.state, i), changes.get(key))) target.unchanged.add(key);
                writes.put(key, before);
                op.markDispatched();
                JsonElement next = changes.get(key);
                Track track = target.state.track;
                switch (i) {
                    case NAME:
                        track.name().set(next.getAsString());
                        break;
                    // Absolute RPC targets must not participate in physical-controller pickup.
                    case VOLUME:
                        track.volume().value().setImmediately(next.getAsDouble());
                        break;
                    case PAN:
                        track.pan().value().setImmediately(next.getAsDouble());
                        break;
                    case 3:
                        track.mute().set(next.getAsBoolean());
                        break;
                    case 4:
                        track.solo().set(next.getAsBoolean());
                        break;
                    case 5:
                        track.arm().set(next.getAsBoolean());
                        break;
                    case COLOR:
                        track.color()
                                .set(
                                        Integer.parseInt(next.getAsString().substring(1, 3), 16) / 255f,
                                        Integer.parseInt(next.getAsString().substring(3, 5), 16) / 255f,
                                        Integer.parseInt(next.getAsString().substring(5, 7), 16) / 255f);
                        break;
                    case 8:
                        track.isActivated().set(next.getAsBoolean());
                        break;
                    case MONITOR:
                        track.monitorMode().set(next.getAsString());
                        break;
                    case CROSS_FADE:
                        track.crossFadeMode().set(next.getAsString());
                        break;
                    default:
                        throw new IllegalArgumentException(key);
                }
            }
        } catch (RpcException error) {
            if (!op.dispatched()) throw error;
            finish(op, target, changes, writes, info, infoKey, error.reason);
            return;
        }
        await(op, target, changes, writes, info, infoKey);
    }

    /** One send, addressed by its ref; levels are verified by observed readback. */
    private void sendSet(HostOperation op, JsonObject p) {
        JsonObject ref = p.getAsJsonObject("target");
        int trackSlot = trackRefs.resolve(ref.getAsJsonObject("track"), false);
        State state = states[trackSlot];
        Target target = new Target(window.at(trackSlot).channelId().get(), state);
        guard(op, target);
        JsonElement offset = state.meta("sendOffset");
        if (offset.isJsonNull() || state.meta("sendCount").isJsonNull())
            throw Errors.notApplied(Reason.NOT_READY, "Send window is not observed yet.");
        int index = ref.get("index").getAsInt(), slot = index - offset.getAsInt();
        if (slot < 0 || slot >= HostLimits.SENDS)
            throw Errors.notApplied(Reason.OUTSIDE_WINDOW, "The send is outside the observed send window.");
        String prefix = "send" + slot + ".";
        JsonElement exists = state.meta(prefix + "exists"), name = state.meta(prefix + "name");
        if (exists.isJsonNull() || name.isJsonNull())
            throw Errors.notApplied(Reason.NOT_READY, "Send is not observed yet.");
        if (!exists.getAsBoolean()) throw Errors.targetNotFound(ref, "No send exists at that index.");
        JsonObject current = Refs.send(trackRefs.ref(trackSlot), index, name.getAsString());
        if (!Refs.matches(ref, current, true))
            throw Errors.targetChanged("Another send is at this index than the one read.", current);
        if (p.has("expect")) requireExpectedSend(state, prefix, p.getAsJsonObject("expect"));
        Solve solve = null;
        if (p.has("value")) {
            ValueInput input = ValueInput.parse("value", p.get("value"));
            if (input.form == ValueInput.Form.DISPLAY) {
                solve = new Solve("value", input.text);
                solve.parse();
            }
        }
        new SendWrite(
                        op,
                        target,
                        trackSlot,
                        slot,
                        index,
                        name.getAsString(),
                        (Send) state.track.sendBank().getItemAt(slot),
                        p,
                        solve)
                .start();
    }

    private void requireExpectedSend(State state, String prefix, JsonObject expect) {
        JsonObject observed = new JsonObject();
        for (String key : expect.keySet()) {
            JsonElement now = state.send(prefix, key);
            if (now.isJsonNull()) throw Errors.notApplied(Reason.NOT_READY, "The send's " + key + " is not observed.");
            observed.add(key, now);
        }
        ValueMatch.requireExpected(expect, observed, SEND_RULES);
    }

    private final class SendWrite {
        final HostOperation op;
        final Target target;
        final State state;
        final int trackSlot, slot, index;
        final String prefix, name;
        final Send send;
        final JsonObject p;
        final Solve solve;
        final Map<String, Long> writes = new LinkedHashMap<>();
        final Map<String, JsonElement> requested = new LinkedHashMap<>();
        JsonElement enabledBefore = JsonNull.INSTANCE;
        boolean solvedValue;

        SendWrite(
                HostOperation op,
                Target target,
                int trackSlot,
                int slot,
                int index,
                String name,
                Send send,
                JsonObject p,
                Solve solve) {
            this.op = op;
            this.target = target;
            this.state = target.state;
            this.trackSlot = trackSlot;
            this.slot = slot;
            this.index = index;
            this.prefix = "send" + slot + ".";
            this.name = name;
            this.send = send;
            this.p = p;
            this.solve = solve;
        }

        void guardSend() {
            guard(op, target);
            JsonElement now = state.meta(prefix + "name");
            if (!state.meta(prefix + "exists").equals(new JsonPrimitive(true))
                    || now.isJsonNull()
                    || !now.getAsString().equals(name))
                throw Errors.notApplied(Reason.TARGET_CHANGED, "Send changed during operation.");
        }

        void start() {
            enabledBefore = state.meta(prefix + "enabled");
            if (solve == null) {
                writeFields();
                return;
            }
            DisplaySolver.Port port = new DisplaySolver.Port() {
                @Override
                public long valueSeq() {
                    return state.metaSeq(prefix + "value");
                }

                @Override
                public JsonElement value() {
                    return state.send(prefix, "value");
                }

                @Override
                public JsonElement display() {
                    return state.meta(prefix + "display");
                }

                @Override
                public void write(double x) {
                    send.value().setImmediately(x);
                }

                @Override
                public void guard() {
                    guardSend();
                }

                @Override
                public JsonElement enabled() {
                    return state.meta(prefix + "enabled");
                }

                @Override
                public long enabledSeq() {
                    return state.metaSeq(prefix + "enabled");
                }

                @Override
                public void writeEnabled(boolean on) {
                    send.isEnabled().set(on);
                }
            };
            new DisplaySolver(scheduler, op, port, solve.kind, solve.target, result -> {
                        target.displayWrite.add("value", result.json(solve.text, solve.target));
                        if (!result.ok) {
                            solverFailure(op, target, result, true);
                            return;
                        }
                        requested.put("value", new JsonPrimitive(result.normalized));
                        writes.put("value", -1L);
                        solvedValue = true;
                        writeFields();
                    })
                    .start();
        }

        void writeFields() {
            try {
                boolean level = p.has("value") && solve == null && write("value", p.get("value"), false);
                // Last: a level write can auto-enable the send on a later flush, so the metadata is stale and the
                // explicit enabled write is dispatched regardless of what it currently shows.
                if (p.has("enabled")) write("enabled", p.get("enabled"), level || solvedValue);
            } catch (RpcException error) {
                if (!op.dispatched()) throw error;
                finish(error.reason);
                return;
            }
            await();
        }

        boolean write(String key, JsonElement value, boolean force) {
            guardSend();
            requested.put(key, value);
            if (!force && matches(state.send(prefix, key), value)) {
                writes.put(key, -1L);
                return false;
            }
            writes.put(key, state.metaSeq(prefix + key));
            guardSend();
            op.markDispatched();
            if (key.equals("enabled")) send.isEnabled().set(value.getAsBoolean());
            else send.value().setImmediately(value.getAsDouble());
            return true;
        }

        boolean verified(String key) {
            Long before = writes.get(key);
            return before != null
                    && (before == -1 || state.metaSeq(prefix + key) > before)
                    && matches(state.send(prefix, key), requested.get(key));
        }

        void await() {
            scheduler.poll(
                    op,
                    scheduler.observeUntil(op),
                    () -> {
                        boolean all = true;
                        for (String key : requested.keySet()) all &= verified(key);
                        return all;
                    },
                    done -> finish(null));
        }

        void finish(Reason reason) {
            boolean current = target.matches(window);
            JsonObject result = new JsonObject();
            if (current) result.add("ref", Refs.send(trackRefs.ref(trackSlot), index, name));
            JsonObject fields = new JsonObject();
            for (Map.Entry<String, JsonElement> entry : requested.entrySet()) {
                String key = entry.getKey();
                fields.add(
                        key,
                        writeField(
                                entry.getValue(),
                                current ? state.send(prefix, key) : JsonNull.INSTANCE,
                                current,
                                writes.get(key),
                                state.metaSeq(prefix + key),
                                current && verified(key),
                                key.equals("value") && solvedValue));
            }
            stamp(result, fields, current ? reason : null);
            result.add("fields", fields);
            // Guide 7.1.6: moving a send level turns the send on; say so rather than leaving it implicit.
            JsonElement now = state.meta(prefix + "enabled");
            boolean auto = !p.has("enabled")
                    && !enabledBefore.isJsonNull()
                    && !now.isJsonNull()
                    && !enabledBefore.getAsBoolean()
                    && now.getAsBoolean();
            result.addProperty("autoEnabled", auto);
            if (target.displayWrite.size() > 0) result.add("displayWrite", target.displayWrite);
            op.result.complete(result);
        }
    }

    /** The effect (FX) track a send feeds: sends follow effect-track order. Null when that track is not in the observed window. */
    private JsonObject effectTrack(int sendIndex) {
        for (int slot = 0; slot < states.length; slot++) {
            Track track = states[slot].track;
            if (!track.exists().get() || !"Effect".equals(track.trackType().get()) || ordinal(track) != sendIndex)
                continue;
            return trackRefs.ref(slot);
        }
        return null;
    }

    private int ordinal(Track candidate) {
        int ordinal = 0;
        for (State state : states) {
            Track track = state.track;
            if (!track.exists().get() || track.trackType().get().equals("Master")) continue;
            if (track.trackType().get().equals("Effect")
                    && track.position().get() < candidate.position().get()) ordinal++;
        }
        return ordinal;
    }

    /** The target's current guard: the same UUID on the same observed proxy, ready and not rebinding. */
    void guard(HostOperation op, Target target) {
        if (!target.matches(window))
            throw Errors.notApplied(Reason.TARGET_CHANGED, "The track changed during the operation.");
        if (target.state.rebindPending)
            throw Errors.notApplied(Reason.NOT_READY, "Track proxy rebind awaits a host flush.");
        if (op != null) scheduler.check(op);
    }

    /** The track a ref names, refused as stale when it is gone, or renamed and {@code write} says it is the target. */
    Target target(JsonObject ref, boolean write) {
        int slot = trackRefs.resolve(ref, write);
        return new Target(ref.get("id").getAsString(), states[slot]);
    }

    /** The observed metadata of a bank slot (type, index), or JSON null while unobserved. */
    JsonElement meta(int slot, String key) {
        return states[slot].meta(key);
    }

    private JsonObject ref(Target target) {
        return trackRefs.ref(window.trackSlot(target.id));
    }

    private void await(
            HostOperation op,
            Target target,
            JsonObject changes,
            Map<String, Long> writes,
            JsonObject info,
            String infoKey) {
        scheduler.poll(
                op,
                scheduler.observeUntil(op),
                () -> {
                    if (!target.matches(window)) return true;
                    boolean all = true;
                    for (int i = 0; i < FIELDS.length; i++)
                        if (changes.has(FIELDS[i]))
                            all &= verified(target.state, i, changes.get(FIELDS[i]), effective(target, i, writes));
                    return all;
                },
                done -> finish(
                        op,
                        target,
                        changes,
                        writes,
                        info,
                        infoKey,
                        target.matches(window) ? null : Reason.TARGET_CHANGED));
    }

    private void finish(
            HostOperation op,
            Target target,
            JsonObject changes,
            Map<String, Long> writes,
            JsonObject info,
            String infoKey,
            Reason reason) {
        boolean current = target.matches(window);
        JsonObject result = new JsonObject();
        if (current) result.add("ref", ref(target));
        JsonObject fields = new JsonObject();
        for (int i = 0; i < FIELDS.length; i++) {
            String key = FIELDS[i];
            if (!changes.has(key)) continue;
            Long before = effective(target, i, writes);
            fields.add(
                    key,
                    writeField(
                            changes.get(key),
                            current
                                    ? (before != null && before == -1 ? raw(target.state, i) : value(target.state, i))
                                    : JsonNull.INSTANCE,
                            current,
                            before,
                            target.state.sequence[i],
                            current && verified(target.state, i, changes.get(key), before),
                            target.solved.contains(key)));
        }
        stamp(result, fields, current ? reason : null);
        result.add("fields", fields);
        if (info != null) result.add(infoKey, info);
        if (target.displayWrite.size() > 0) result.add("displayWrite", target.displayWrite);
        op.result.complete(result);
    }

    /** A write that found the getter already at the value raises no callback: with none, it changed nothing. */
    private static Long effective(Target target, int i, Map<String, Long> writes) {
        Long before = writes.get(FIELDS[i]);
        boolean quiet = before != null && before != -1 && target.unchanged.contains(FIELDS[i]);
        return quiet && target.state.sequence[i] <= before ? Long.valueOf(-1) : before;
    }

    /** One written field: its outcome and, when it is not a success, why. */
    private static JsonObject writeField(
            JsonElement requested,
            JsonElement observed,
            boolean current,
            Long before,
            long sequence,
            boolean matched,
            boolean solved) {
        if (!current) return Results.unknownField(requested, observed, Reason.TARGET_CHANGED);
        if (before == null) return Results.notAppliedField(requested, observed, Reason.NOT_REACHED);
        if (!matched)
            return Results.unknownField(
                    requested,
                    observed,
                    before != -1 && sequence <= before ? Reason.NOT_OBSERVED : Reason.READBACK_MISMATCH);
        return Results.field(
                requested,
                observed,
                before == -1 && !solved ? Outcome.ALREADY_MATCHED : Outcome.APPLIED,
                null,
                Verification.API_READBACK);
    }

    /** The result's outcome is its worst field's; {@code reason} (a failure that stopped the writes) outranks the fields'. */
    private static void stamp(JsonObject result, JsonObject fields, Reason reason) {
        Outcome worst = Outcome.ALREADY_MATCHED;
        String why = null;
        for (Map.Entry<String, JsonElement> entry : fields.entrySet()) {
            JsonObject field = entry.getValue().getAsJsonObject();
            Outcome outcome = Results.outcomeOf(field);
            if (outcome.compareTo(worst) > 0 && field.has("reason"))
                why = field.get("reason").getAsString();
            worst = worst.worst(outcome);
        }
        Results.stamp(result, worst, fields.asMap().values());
        if (!worst.succeeded()) result.addProperty("reason", reason != null ? reason.wire() : why);
    }

    /** Compact identity row for context.get (null fields when unobserved); never selects. */
    public JsonObject brief(int i) {
        State state = states[i];
        JsonObject out = new JsonObject();
        out.add("ref", trackRefs.ref(i));
        out.add("index", state.meta("index"));
        out.add("name", value(state, NAME));
        out.add("type", state.meta("type"));
        out.add("selected", value(state, SELECTED));
        return out;
    }

    /** The tracks.list mix columns of a bank slot; null while unobserved. */
    void columns(int slot, JsonObject row) {
        State state = states[slot];
        for (int i : new int[] {VOLUME, PAN})
            row.add(FIELDS[i], Results.unit(value(state, i), text(state.meta(FIELDS[i] + "Display")), null));
        for (int i = 3; i <= 5; i++) row.add(FIELDS[i], value(state, i));
        JsonArray sends = new JsonArray();
        for (int i = 0; i < HostLimits.SENDS; i++) {
            String prefix = "send" + i + ".";
            if (!state.meta(prefix + "exists").equals(new JsonPrimitive(true))) continue;
            JsonObject send = new JsonObject();
            for (String key : List.of("name", "display", "enabled")) send.add(key, state.meta(prefix + key));
            sends.add(send);
        }
        row.add("sends", sends);
    }

    private JsonObject snapshot(Target target) {
        State state = target.state;
        JsonObject ref = ref(target);
        JsonObject result = new JsonObject();
        result.add("ref", ref);
        boolean complete = true;
        for (int i = 0; i < FIELDS.length; i++) {
            JsonElement observed = value(state, i);
            String key = FIELDS[i];
            if (i == VOLUME || i == PAN) {
                JsonObject unit = Results.unit(observed, text(state.meta(key + "Display")), null);
                unit.add("ref", Refs.parameter(ref, key, null));
                result.add(key, unit);
                complete &= !state.meta(key + "Display").isJsonNull();
            } else if (i == MONITOR || i == CROSS_FADE) {
                result.add(key, Results.unit(observed, null, EnumOptions.json(enumValue(state.track, i))));
            } else result.add(key, observed);
            if (i < BASE_FIELDS) complete &= !observed.isJsonNull();
        }
        for (String key : List.of("index", "type")) {
            JsonElement observed = state.meta(key);
            result.add(key, observed);
            complete &= !observed.isJsonNull();
        }
        JsonElement offset = state.meta("sendOffset"), count = state.meta("sendCount");
        boolean sendsComplete = !offset.isJsonNull() && !count.isJsonNull();
        JsonArray sends = new JsonArray();
        for (int i = 0; i < HostLimits.SENDS; i++) {
            String prefix = "send" + i + ".";
            JsonElement exists = state.meta(prefix + "exists");
            if (exists.isJsonNull()) {
                sendsComplete = false;
                continue;
            }
            if (!exists.getAsBoolean()) continue;
            JsonObject item = new JsonObject();
            JsonElement name = state.meta(prefix + "name");
            if (!offset.isJsonNull() && !name.isJsonNull())
                item.add("ref", Refs.send(ref, offset.getAsInt() + i, name.getAsString()));
            else sendsComplete = false;
            item.add("value", Results.unit(state.send(prefix, "value"), text(state.meta(prefix + "display")), null));
            for (String key : List.of("enabled", "preFader")) item.add(key, state.meta(prefix + key));
            sendsComplete &= !state.send(prefix, "value").isJsonNull()
                    && !state.meta(prefix + "enabled").isJsonNull()
                    && !state.meta(prefix + "preFader").isJsonNull();
            JsonObject effect = offset.isJsonNull() ? null : effectTrack(offset.getAsInt() + i);
            item.add("effectTrack", effect == null ? JsonNull.INSTANCE : effect);
            sends.add(item);
        }
        result.add("sends", sends);
        result.add(
                "sendWindow",
                offset.isJsonNull() || count.isJsonNull()
                        ? JsonNull.INSTANCE
                        : Results.window(offset.getAsInt(), HostLimits.SENDS, count.getAsInt()));
        result.addProperty("sendObservationsComplete", sendsComplete);
        result.addProperty("observationsComplete", complete && sendsComplete);
        return result;
    }

    private static String text(JsonElement element) {
        return element.isJsonNull() ? null : element.getAsString();
    }

    private static EnumValue enumValue(Track track, int field) {
        return field == MONITOR ? track.monitorMode() : track.crossFadeMode();
    }

    private static boolean verified(State state, int i, JsonElement expected, Long before) {
        return before != null
                && (before == -1 || state.sequence[i] > before)
                && matchesField(i, before == -1 ? raw(state, i) : value(state, i), expected);
    }
    /** Colors are stored as floats, so the host's 8-bit readback can land one step off the requested hex. */
    private static boolean matchesField(int i, JsonElement a, JsonElement b) {
        if (i != COLOR || a.isJsonNull() || !a.getAsJsonPrimitive().isString()) return matches(a, b);
        String x = a.getAsString(), y = b.getAsString();
        if (x.length() != 7 || y.length() != 7) return false;
        for (int c = 1; c < 7; c += 2)
            if (Math.abs(Integer.parseInt(x.substring(c, c + 2), 16) - Integer.parseInt(y.substring(c, c + 2), 16)) > 1)
                return false;
        return true;
    }

    private static boolean matches(JsonElement a, JsonElement b) {
        if (a.isJsonNull()) return false;
        if (a.getAsJsonPrimitive().isNumber())
            return Math.abs(a.getAsDouble() - b.getAsDouble()) <= HostLimits.TOLERANCE;
        return a.equals(b);
    }

    private static JsonElement finite(double v) {
        return Double.isFinite(v) ? new JsonPrimitive(v) : JsonNull.INSTANCE;
    }

    private static JsonElement value(State state, int i) {
        return state.sequence[i] == 0 ? JsonNull.INSTANCE : raw(state, i);
    }

    /** The getter's current value, readable even before any callback reported it. */
    private static JsonElement raw(State state, int i) {
        Track track = state.track;
        if (!state.readable()) return JsonNull.INSTANCE;
        switch (i) {
            case NAME:
                return new JsonPrimitive(track.name().get());
            case VOLUME:
                return finite(track.volume().value().get());
            case PAN:
                return finite(track.pan().value().get());
            case 3:
                return new JsonPrimitive(track.mute().get());
            case 4:
                return new JsonPrimitive(track.solo().get());
            case 5:
                return new JsonPrimitive(track.arm().get());
            // This legacy observer has no interested-value getter to refresh at flush.
            case SELECTED:
                return state.fresh(i) ? new JsonPrimitive(state.selected) : JsonNull.INSTANCE;
            case COLOR:
                return new JsonPrimitive(String.format(
                        "#%02x%02x%02x",
                        Math.round(track.color().red() * 255),
                        Math.round(track.color().green() * 255),
                        Math.round(track.color().blue() * 255)));
            case 8:
                return new JsonPrimitive(track.isActivated().get());
            case MONITOR:
                return new JsonPrimitive(track.monitorMode().get());
            case CROSS_FADE:
                return new JsonPrimitive(track.crossFadeMode().get());
            default:
                throw new IllegalArgumentException("field");
        }
    }

    private static void observe(State state, String key, StringValue source) {
        state.metadataSources.put(key, source);
        source.addValueObserver((StringValueChangedCallback) v -> {
            state.metadata.put(key, new JsonPrimitive((String) v));
            state.metaSeq.merge(key, 1L, Long::sum);
        });
    }

    private static void observe(State state, String key, BooleanValue source) {
        state.metadataSources.put(key, source);
        source.addValueObserver((BooleanValueChangedCallback) v -> {
            state.metadata.put(key, new JsonPrimitive(v));
            state.metaSeq.merge(key, 1L, Long::sum);
        });
    }

    private static void observe(State state, String key, IntegerValue source) {
        state.metadataSources.put(key, source);
        source.addValueObserver((IntegerValueChangedCallback) v -> {
            state.metadata.put(key, new JsonPrimitive(v));
            state.metaSeq.merge(key, 1L, Long::sum);
        });
    }

    private static void observe(State state, String key, RangedValue source) {
        state.metadataSources.put(key, source);
        source.addValueObserver((DoubleValueChangedCallback) v -> {
            state.metadata.put(key, finite(v));
            state.metaSeq.merge(key, 1L, Long::sum);
        });
    }

    private static JsonElement current(Value source) {
        if (source instanceof StringValue) return new JsonPrimitive(((StringValue) source).get());
        if (source instanceof BooleanValue) return new JsonPrimitive(((BooleanValue) source).get());
        if (source instanceof IntegerValue) return new JsonPrimitive(((IntegerValue) source).get());
        return finite(((RangedValue) source).get());
    }

    private static final class State {
        final Track track;
        final ControllerHost host;
        // Subscriptions belong to the continuous bank proxy, not to a physical track UUID.
        final long[] sequence = new long[FIELDS.length];
        final long[] fieldGeneration = new long[FIELDS.length];
        final Map<String, JsonElement> metadata = new HashMap<>();
        final Map<String, Value> metadataSources = new HashMap<>();
        final Map<String, Long> metaSeq = new HashMap<>();

        long metaSeq(String key) {
            return metaSeq.getOrDefault(key, 0L);
        }

        String identity;
        Boolean exists;
        boolean established, rebindPending;
        long generation;
        boolean selected;

        State(Track track, ControllerHost host) {
            this.track = track;
            this.host = host;
        }

        void seen(int i) {
            sequence[i]++;
            fieldGeneration[i] = generation;
        }

        boolean fresh(int i) {
            return sequence[i] > 0 && fieldGeneration[i] == generation;
        }

        boolean sourceMatches() {
            return Boolean.TRUE.equals(exists)
                    && track.exists().get()
                    && meaningful(identity)
                    && identity.equals(track.channelId().get());
        }

        boolean readable() {
            return !rebindPending && sourceMatches();
        }

        /** A send's metadata; a level the host shows as -Inf dB with no value is the minimum, 0. */
        JsonElement send(String prefix, String key) {
            JsonElement value = meta(prefix + key);
            if (!key.equals("value") || !value.isJsonNull()) return value;
            JsonElement display = meta(prefix + "display");
            boolean minusInf = !display.isJsonNull()
                    && DisplayScale.parse(DisplayScale.Kind.DB, display.getAsString()) <= DisplayScale.MINUS_INF;
            return minusInf ? new JsonPrimitive(0.0) : value;
        }

        JsonElement meta(String key) {
            return readable() ? metadata.getOrDefault(key, JsonNull.INSTANCE) : JsonNull.INSTANCE;
        }

        void identity(String next) {
            // Empty IDs and false existence are normal startup placeholders. Values may arrive
            // before the first meaningful identity; only an existing identity can be replaced.
            if ((established || meaningful(identity)) && !Objects.equals(identity, next)) invalidate();
            identity = next;
            established |= meaningful(identity) && Boolean.TRUE.equals(exists);
        }

        void existence(boolean next) {
            if (established && exists != null && exists != next) invalidate();
            exists = next;
            established |= meaningful(identity) && next;
        }

        private static boolean meaningful(String id) {
            return id != null && !id.isEmpty();
        }

        void invalidate() {
            generation++;
            rebindPending = true;
            host.requestFlush();
        }

        void flush() {
            if (!rebindPending || !sourceMatches()) return;
            // Value observers report current proxy values, not identity-stamped events. An
            // unchanged position/value need not replay and changed fields may precede the ID.
            // Refresh only previously observed sources at the host's output-update boundary.
            // This is not an atomic physical-UUID snapshot or fresh write acknowledgement.
            metadata.replaceAll((key, old) -> current(metadataSources.get(key)));
            rebindPending = false;
        }
    }

    /** A resolved track: its UUID on one observed bank proxy generation, and what a request wrote while solving. */
    static final class Target {
        final String id;
        private final State state;
        private final long generation;
        final JsonObject displayWrite = new JsonObject();
        final Set<String> solved = new HashSet<>(), unchanged = new HashSet<>();

        Target(String id, State state) {
            this.id = id;
            this.state = state;
            generation = state.generation;
        }

        boolean matches(TrackWindow window) {
            return generation == state.generation && state.sourceMatches() && window.trackMatches(id, state.track);
        }
    }
}
