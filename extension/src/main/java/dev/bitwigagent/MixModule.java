package dev.bitwigagent;

import com.bitwig.extension.callback.*;
import com.bitwig.extension.controller.api.*;
import com.google.gson.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** UUID-targeted mix operations over the single shared, bounded track bank. */
final class MixModule implements HostModule {
    static final Set<String> METHODS = Set.of(
            "tracks.get",
            "tracks.set",
            "tracks.create",
            "tracks.select",
            "tracks.sends.set",
            "tracks.group",
            "tracks.delete");
    /** Long enough for the native group operation, which may first save plug-in states asynchronously. */
    static final long GROUP_OBSERVE_NANOS = TimeUnit.MILLISECONDS.toNanos(3000);

    interface GroupAccess {
        void execute(Runnable work);

        JsonObject group(List<Object> trackProxies, List<String> trackIds, Runnable check, Runnable beforeWrite);
    }

    private static final String[] FIELDS = {
        "name",
        "volumeNormalized",
        "panNormalized",
        "mute",
        "solo",
        "arm",
        "selected",
        "color",
        "activated",
        "monitorMode",
        "crossFadeMode"
    };
    /** The first FIELDS entries every track reports; the later ones never decide observationsComplete. */
    private static final int BASE_FIELDS = 7;

    private static final Set<String> WRITABLE = Set.of(
            "name",
            "volumeNormalized",
            "panNormalized",
            "mute",
            "solo",
            "arm",
            "color",
            "activated",
            "monitorMode",
            "crossFadeMode");
    private static final java.util.regex.Pattern RGB = java.util.regex.Pattern.compile("#[0-9a-fA-F]{6}");
    // Display-addressed alternatives to volumeNormalized/panNormalized, resolved against the host's own display text.
    private static final Set<String> DISPLAY_WRITABLE = Set.of("volumeDb", "volumeDisplay", "panPercent", "panDisplay");
    private static final Set<String> SEND_OPTIONAL =
            Set.of("sendIndex", "sendName", "expectedSendName", "valueNormalized", "display", "enabled");
    private final HostServices s;
    private final State[] states = new State[HostServices.TRACKS];
    private HostOperation creation;
    // A completed/cancelled RPC does not cancel a void native factory. Never reuse its attribution window.
    private volatile boolean creationUnresolved, groupUnresolved;
    private GroupAccess groupAccess;

    @Override
    public List<String> unresolved() {
        List<String> out = new ArrayList<>();
        if (creationUnresolved) out.add("A prior tracks.create factory remains unattributed.");
        if (groupUnresolved) out.add("A prior tracks.group operation remains unattributed.");
        return out;
    }

    MixModule(HostServices services) {
        this(services, null);
    }

    MixModule(HostServices services, GroupAccess groupAccess) {
        s = services;
        this.groupAccess = groupAccess;
        for (int i = 0; i < states.length; i++) {
            Track track = (Track) s.tracks.getItemAt(i);
            State state = new State(track, s.host);
            states[i] = state;
            track.channelId().addValueObserver((StringValueChangedCallback) value -> state.identity((String) value));
            track.exists().addValueObserver((BooleanValueChangedCallback) state::existence);
            observe(state, "type", track.trackType());
            observe(state, "index", track.position());
            track.name().addValueObserver((StringValueChangedCallback) v -> state.seen(0));
            track.volume().value().addValueObserver((DoubleValueChangedCallback) v -> state.seen(1));
            track.pan().value().addValueObserver((DoubleValueChangedCallback) v -> state.seen(2));
            track.mute().addValueObserver((BooleanValueChangedCallback) v -> state.seen(3));
            track.solo().addValueObserver((BooleanValueChangedCallback) v -> state.seen(4));
            track.arm().addValueObserver((BooleanValueChangedCallback) v -> state.seen(5));
            track.addIsSelectedInEditorObserver(v -> {
                state.selected = v;
                state.seen(6);
            });
            track.color().addValueObserver((ColorValueChangedCallback) (r, g, b) -> state.seen(7));
            track.isActivated().addValueObserver((BooleanValueChangedCallback) v -> state.seen(8));
            track.monitorMode().addValueObserver((EnumValueChangedCallback) v -> state.seen(9));
            track.crossFadeMode().addValueObserver((EnumValueChangedCallback) v -> state.seen(10));
            observe(state, "volumeDisplay", track.volume().displayedValue());
            observe(state, "panDisplay", track.pan().displayedValue());
            SendBank sends = track.sendBank();
            observe(state, "sendCount", sends.itemCount());
            observe(state, "sendOffset", sends.scrollPosition());
            for (int j = 0; j < HostServices.SENDS; j++) {
                Send send = (Send) sends.getItemAt(j);
                String prefix = "send" + j + ".";
                observe(state, prefix + "exists", send.exists());
                observe(state, prefix + "name", send.name());
                observe(state, prefix + "valueNormalized", send.value());
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

    static void validateParams(String method, JsonObject p) {
        switch (method) {
            case "tracks.get":
            case "tracks.select":
                Params.fields(p, Set.of("trackId"), Set.of());
                Params.string(p, "trackId");
                return;
            case "tracks.set":
                Params.fields(p, Set.of("trackId"), union(WRITABLE, DISPLAY_WRITABLE));
                Params.string(p, "trackId");
                if (p.size() == 1)
                    throw Errors.notApplied(ErrorCode.INVALID_PARAMS, "At least one track field is required.");
                if (p.has("name")) Params.string(p, "name");
                for (String key : List.of("volumeNormalized", "panNormalized"))
                    if (p.has(key)) Params.number(p, key, 0, 1);
                for (String key : List.of("mute", "solo", "arm", "activated")) if (p.has(key)) Params.bool(p, key);
                if (p.has("color") && !RGB.matcher(Params.string(p, "color")).matches())
                    throw Errors.notApplied(ErrorCode.INVALID_PARAMS, "color must be #RRGGBB.");
                for (String key : List.of("monitorMode", "crossFadeMode")) if (p.has(key)) Params.string(p, key);
                if (p.has("volumeDb")) Params.number(p, "volumeDb", -150, 24);
                if (p.has("panPercent")) Params.number(p, "panPercent", -100, 100);
                for (String key : List.of("volumeDisplay", "panDisplay")) if (p.has(key)) Params.string(p, key);
                atMostOne(p, "volumeNormalized", "volumeDb", "volumeDisplay");
                atMostOne(p, "panNormalized", "panPercent", "panDisplay");
                return;
            case "tracks.sends.set":
                Params.fields(p, Set.of("trackId"), SEND_OPTIONAL);
                Params.string(p, "trackId");
                if (p.has("sendIndex") == p.has("sendName"))
                    throw Errors.notApplied(ErrorCode.INVALID_PARAMS, "Supply exactly one of sendIndex or sendName.");
                if (p.has("sendIndex")) Params.integer(p, "sendIndex", 0, 127);
                for (String key : List.of("sendName", "expectedSendName", "display"))
                    if (p.has(key)) Params.string(p, key);
                if (p.has("valueNormalized")) Params.number(p, "valueNormalized", 0, 1);
                if (p.has("enabled")) Params.bool(p, "enabled");
                atMostOne(p, "valueNormalized", "display");
                if (!p.has("valueNormalized") && !p.has("display") && !p.has("enabled"))
                    throw Errors.notApplied(
                            ErrorCode.INVALID_PARAMS,
                            "At least one of valueNormalized, display or enabled is required.");
                return;
            case "tracks.create":
                Params.fields(p, Set.of("type", "position", "name"), Set.of());
                if (!Set.of("audio", "instrument", "effect").contains(Params.string(p, "type")))
                    throw Errors.notApplied(ErrorCode.INVALID_PARAMS, "type must be audio, instrument, or effect.");
                Params.integer(p, "position", -1, Integer.MAX_VALUE);
                Params.string(p, "name");
                return;
            case "tracks.group":
                Params.fields(p, Set.of("trackIds"), Set.of("name"));
                trackIds(p);
                if (p.has("name")) Params.string(p, "name");
                return;
            case "tracks.delete":
                Params.fields(p, Set.of("trackId", "expectedName"), Set.of());
                Params.string(p, "trackId");
                Params.string(p, "expectedName");
                return;
            default:
                throw Errors.notApplied(ErrorCode.UNKNOWN_METHOD, "Unknown track method.");
        }
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> out = new HashSet<>(a);
        out.addAll(b);
        return out;
    }

    private static void atMostOne(JsonObject p, String... keys) {
        int n = 0;
        for (String key : keys) if (p.has(key)) n++;
        if (n > 1)
            throw Errors.notApplied(ErrorCode.INVALID_PARAMS, "Supply at most one of " + String.join(", ", keys) + ".");
    }

    static List<String> trackIds(JsonObject p) {
        JsonElement value = p.get("trackIds");
        if (!value.isJsonArray()
                || value.getAsJsonArray().size() < 1
                || value.getAsJsonArray().size() > HostServices.TRACKS)
            throw Errors.notApplied(
                    ErrorCode.INVALID_PARAMS,
                    "trackIds must be an array of 1.." + HostServices.TRACKS + " track UUIDs.");
        List<String> ids = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonElement item : value.getAsJsonArray()) {
            if (!item.isJsonPrimitive()
                    || !item.getAsJsonPrimitive().isString()
                    || !DeviceSources.canonicalUuid(item.getAsString()))
                throw Errors.notApplied(
                        ErrorCode.INVALID_PARAMS, "Each trackIds entry must be a canonical track UUID.");
            if (!seen.add(item.getAsString().toLowerCase(Locale.ROOT)))
                throw Errors.notApplied(ErrorCode.INVALID_PARAMS, "trackIds must be distinct.");
            ids.add(item.getAsString().toLowerCase(Locale.ROOT));
        }
        return ids;
    }

    @Override
    public void dispatch(HostOperation op, String method, JsonObject p) {
        s.check(op);
        if (method.equals("tracks.create")) {
            create(op, p);
            return;
        }
        if (method.equals("tracks.group")) {
            group(op, p);
            return;
        }
        String id = p.get("trackId").getAsString();
        State state = states[s.trackSlot(id)];
        Target target = new Target(id, state);
        if (method.equals("tracks.delete")) {
            delete(op, target, p.get("expectedName").getAsString());
            return;
        }
        if (method.equals("tracks.get")) {
            op.result.complete(snapshot(target));
            return;
        }
        if (method.equals("tracks.sends.set")) {
            sendSet(op, target, p);
            return;
        }
        if (method.equals("tracks.select")) {
            guard(op, target);
            s.requireSelectionFree();
            op.dispatched = true;
            target.state.track.selectInEditor();
            guard(op, target);
            JsonObject result = snapshot(target);
            result.addProperty("status", "dispatched");
            result.addProperty("completion", "not_observed");
            result.addProperty("verification", "selection_has_no_reliable_public_observer");
            op.result.complete(result);
            return;
        }
        JsonObject changes = new JsonObject();
        for (String key : WRITABLE) if (p.has(key)) changes.add(key, p.get(key));
        if (changes.has("color"))
            changes.addProperty("color", changes.get("color").getAsString().toLowerCase(Locale.ROOT));
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
        if (p.has("volumeDb") || p.has("volumeDisplay"))
            solves.add(new Solve(
                    "volumeNormalized",
                    1,
                    "volumeDisplay",
                    DisplayScale.Kind.DB,
                    p.get(p.has("volumeDb") ? "volumeDb" : "volumeDisplay"),
                    p.has("volumeDb")));
        if (p.has("panPercent") || p.has("panDisplay"))
            solves.add(new Solve(
                    "panNormalized",
                    2,
                    "panDisplay",
                    DisplayScale.Kind.PERCENT,
                    p.get(p.has("panPercent") ? "panPercent" : "panDisplay"),
                    p.has("panPercent")));
        if (solves.isEmpty()) {
            set(op, target, changes, null);
            return;
        }
        // Parse every display target before the first probe write: a bad string must never reach the host.
        for (Solve solve : solves) solve.parse();
        solveNext(op, target, changes, solves, 0);
    }

    /** One display-addressed mixer value, resolved to a normalized value by DisplaySolver before the ordinary set flow. */
    private static final class Solve {
        final String key, displayKey;
        final int field;
        final DisplayScale.Kind kind;
        final JsonElement request;
        final boolean numeric;
        double target, original;
        String text;
        long restoreSeq;

        Solve(String key, int field, String displayKey, DisplayScale.Kind kind, JsonElement request, boolean numeric) {
            this.key = key;
            this.field = field;
            this.displayKey = displayKey;
            this.kind = kind;
            this.request = request;
            this.numeric = numeric;
        }

        void parse() {
            if (numeric) {
                target = request.getAsDouble();
                text = kind == DisplayScale.Kind.DB ? target + " dB" : target + " %";
            } else {
                text = request.getAsString();
                target = DisplayScale.parse(kind, text);
                if (Double.isNaN(target))
                    throw Errors.notApplied(
                            ErrorCode.INVALID_PARAMS, "Display text not understood for " + kind + ": " + text);
            }
        }
    }

    private void solveNext(HostOperation op, Target target, JsonObject changes, List<Solve> solves, int index) {
        if (index == solves.size()) {
            set(op, target, changes, null);
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
                if (solve.field == 1) state.track.volume().value().setImmediately(x);
                else state.track.pan().value().setImmediately(x);
            }

            @Override
            public void guard() {
                MixModule.this.guard(op, target);
            }
        };
        try {
            new DisplaySolver(s, op, port, solve.kind, solve.target, result -> {
                        target.extra.add(
                                solve.displayKey.replace("Display", "DisplayWrite"),
                                result.json(solve.text, solve.target));
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
            failed.reason = error.code;
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
                if (!now.isJsonNull() && Math.abs(now.getAsDouble() - earlier.original) <= HostServices.TOLERANCE)
                    continue;
                earlier.restoreSeq = target.state.sequence[earlier.field];
                Track track = target.state.track;
                if (earlier.field == 1) track.volume().value().setImmediately(earlier.original);
                else track.pan().value().setImmediately(earlier.original);
                pending.add(earlier);
            }
        } catch (RpcException error) {
            done.accept(false);
            return;
        }
        awaitEarlier(op, target, pending, done, System.nanoTime() + DisplaySolver.PROBE_TIMEOUT_NANOS);
    }

    private void awaitEarlier(
            HostOperation op,
            Target target,
            List<Solve> pending,
            java.util.function.Consumer<Boolean> done,
            long until) {
        boolean all = target.matches(s);
        if (all)
            for (Solve earlier : pending) {
                JsonElement now = value(target.state, earlier.field);
                all &= target.state.sequence[earlier.field] > earlier.restoreSeq
                        && !now.isJsonNull()
                        && Math.abs(now.getAsDouble() - earlier.original) <= HostServices.TOLERANCE;
            }
        if (all) {
            done.accept(true);
            return;
        }
        if (!target.matches(s) || System.nanoTime() >= until) {
            done.accept(false);
            return;
        }
        s.schedule(op, () -> awaitEarlier(op, target, pending, done, until), DisplaySolver.POLL_MS);
    }

    /** A failed display write applied nothing else; it either put the original back (observed) or leaves the outcome unknown. */
    private void solverFailure(HostOperation op, Target target, DisplaySolver.Result result, boolean earlierRestored) {
        boolean current = target.matches(s);
        JsonObject out = current ? snapshot(target) : scope();
        out.addProperty("trackId", target.id);
        boolean restored = (!result.dispatched || result.restored) && earlierRestored;
        out.addProperty("status", "dispatched");
        out.addProperty("completion", restored && current ? "observed" : "unknown");
        out.addProperty("matchesRequested", false);
        out.addProperty("otherFieldsApplied", !earlierRestored);
        out.addProperty("reason", result.reason);
        for (Map.Entry<String, JsonElement> entry : target.extra.entrySet()) out.add(entry.getKey(), entry.getValue());
        op.result.complete(out);
    }

    private void set(HostOperation op, Target target, JsonObject changes, JsonObject created) {
        set(op, target, changes, created, "creation");
    }

    private void set(HostOperation op, Target target, JsonObject changes, JsonObject created, String createdKey) {
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
                writes.put(key, before);
                op.dispatched = true;
                JsonElement next = changes.get(key);
                Track track = target.state.track;
                switch (i) {
                    case 0:
                        track.name().set(next.getAsString());
                        break;
                    // Absolute RPC targets must not participate in physical-controller pickup.
                    case 1:
                        track.volume().value().setImmediately(next.getAsDouble());
                        break;
                    case 2:
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
                    case 7:
                        track.color()
                                .set(
                                        Integer.parseInt(next.getAsString().substring(1, 3), 16) / 255f,
                                        Integer.parseInt(next.getAsString().substring(3, 5), 16) / 255f,
                                        Integer.parseInt(next.getAsString().substring(5, 7), 16) / 255f);
                        break;
                    case 8:
                        track.isActivated().set(next.getAsBoolean());
                        break;
                    case 9:
                        track.monitorMode().set(next.getAsString());
                        break;
                    case 10:
                        track.crossFadeMode().set(next.getAsString());
                        break;
                    default:
                        throw new IllegalArgumentException(key);
                }
            }
        } catch (RpcException error) {
            if (!op.dispatched) throw error;
            finish(op, target, changes, writes, created, createdKey, error.code);
            return;
        }
        await(op, target, changes, writes, created, createdKey, HostServices.observeUntil(op));
    }

    /** Compare-and-set of one send, addressed by index or exact name; levels are verified by observed readback. */
    private void sendSet(HostOperation op, Target target, JsonObject p) {
        guard(op, target);
        State state = target.state;
        JsonElement offset = state.meta("sendOffset");
        if (offset.isJsonNull() || state.meta("sendCount").isJsonNull())
            throw Errors.notApplied(ErrorCode.NOT_READY, "Send window is not observed yet.");
        int slot = -1;
        if (p.has("sendIndex")) {
            slot = p.get("sendIndex").getAsInt() - offset.getAsInt();
            if (slot < 0 || slot >= HostServices.SENDS)
                throw Errors.notApplied(ErrorCode.OUTSIDE_WINDOW, "sendIndex is outside the observed send window.");
        } else {
            String wanted = p.get("sendName").getAsString();
            for (int j = 0; j < HostServices.SENDS; j++) {
                JsonElement exists = state.meta("send" + j + ".exists"), name = state.meta("send" + j + ".name");
                if (exists.isJsonNull() || name.isJsonNull())
                    throw Errors.notApplied(ErrorCode.NOT_READY, "Send names are not all observed yet.");
                if (exists.getAsBoolean() && name.getAsString().equals(wanted)) {
                    if (slot >= 0)
                        throw Errors.notApplied(
                                ErrorCode.AMBIGUOUS_SEND, "More than one send is named " + wanted + "; use sendIndex.");
                    slot = j;
                }
            }
            if (slot < 0) throw Errors.notApplied(ErrorCode.UNKNOWN_SEND, "No send is named " + wanted + ".");
        }
        String prefix = "send" + slot + ".";
        JsonElement exists = state.meta(prefix + "exists"), name = state.meta(prefix + "name");
        if (exists.isJsonNull() || name.isJsonNull())
            throw Errors.notApplied(ErrorCode.NOT_READY, "Send is not observed yet.");
        if (!exists.getAsBoolean()) throw Errors.notApplied(ErrorCode.UNKNOWN_SEND, "No send exists at that index.");
        if (p.has("expectedSendName")
                && !p.get("expectedSendName").getAsString().equals(name.getAsString()))
            throw Errors.notApplied(
                    ErrorCode.STALE_TARGET,
                    "Send name is " + name.getAsString() + ", not the expected "
                            + p.get("expectedSendName").getAsString() + ".");
        double displayTarget = Double.NaN;
        if (p.has("display")) {
            displayTarget =
                    DisplayScale.parse(DisplayScale.Kind.DB, p.get("display").getAsString());
            if (Double.isNaN(displayTarget))
                throw Errors.notApplied(
                        ErrorCode.INVALID_PARAMS,
                        "Send display must read like \"-12 dB\" or \"-inf dB\": "
                                + p.get("display").getAsString());
        }
        new SendWrite(
                        op,
                        target,
                        slot,
                        name.getAsString(),
                        (Send) state.track.sendBank().getItemAt(slot),
                        p,
                        displayTarget)
                .start();
    }

    private final class SendWrite {
        final HostOperation op;
        final Target target;
        final State state;
        final int slot;
        final String prefix, name;
        final Send send;
        final JsonObject p;
        final double displayTarget;
        final Map<String, Long> writes = new LinkedHashMap<>();
        final Map<String, JsonElement> requested = new LinkedHashMap<>();
        JsonElement enabledBefore = JsonNull.INSTANCE;
        boolean solvedValue;

        SendWrite(
                HostOperation op, Target target, int slot, String name, Send send, JsonObject p, double displayTarget) {
            this.op = op;
            this.target = target;
            this.state = target.state;
            this.slot = slot;
            this.prefix = "send" + slot + ".";
            this.name = name;
            this.send = send;
            this.p = p;
            this.displayTarget = displayTarget;
        }

        void guardSend() {
            guard(op, target);
            JsonElement now = state.meta(prefix + "name");
            if (!state.meta(prefix + "exists").equals(new JsonPrimitive(true))
                    || now.isJsonNull()
                    || !now.getAsString().equals(name))
                throw Errors.notApplied(ErrorCode.STALE_TARGET, "Send changed during operation.");
        }

        void start() {
            enabledBefore = state.meta(prefix + "enabled");
            if (!p.has("display")) {
                writeFields();
                return;
            }
            DisplaySolver.Port port = new DisplaySolver.Port() {
                @Override
                public long valueSeq() {
                    return state.metaSeq(prefix + "valueNormalized");
                }

                @Override
                public JsonElement value() {
                    return state.meta(prefix + "valueNormalized");
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
            String text = p.get("display").getAsString();
            new DisplaySolver(s, op, port, DisplayScale.Kind.DB, displayTarget, result -> {
                        target.extra.add("displayWrite", result.json(text, displayTarget));
                        if (!result.ok) {
                            solverFailure(op, target, result, true);
                            return;
                        }
                        requested.put("valueNormalized", new JsonPrimitive(result.normalized));
                        writes.put("valueNormalized", -1L);
                        solvedValue = true;
                        writeFields();
                    })
                    .start();
        }

        void writeFields() {
            try {
                boolean level = p.has("valueNormalized") && write("valueNormalized", p.get("valueNormalized"), false);
                // Last: a level write can auto-enable the send on a later flush, so the metadata is stale and the
                // explicit enabled write is dispatched regardless of what it currently shows.
                if (p.has("enabled")) write("enabled", p.get("enabled"), level);
            } catch (RpcException error) {
                if (!op.dispatched) throw error;
                finish(error.code);
                return;
            }
            await(HostServices.observeUntil(op));
        }

        boolean write(String key, JsonElement value, boolean force) {
            guardSend();
            requested.put(key, value);
            if (!force && matches(state.meta(prefix + key), value)) {
                writes.put(key, -1L);
                return false;
            }
            writes.put(key, state.metaSeq(prefix + key));
            guardSend();
            op.dispatched = true;
            if (key.equals("enabled")) send.isEnabled().set(value.getAsBoolean());
            else send.value().setImmediately(value.getAsDouble());
            return true;
        }

        boolean verified(String key) {
            Long before = writes.get(key);
            return before != null
                    && (before == -1 || state.metaSeq(prefix + key) > before)
                    && matches(state.meta(prefix + key), requested.get(key));
        }

        void await(long until) {
            boolean all = true;
            for (String key : requested.keySet()) all &= verified(key);
            if (all || System.nanoTime() >= until) {
                finish(null);
                return;
            }
            s.schedule(op, () -> await(until), 20);
        }

        void finish(String reason) {
            boolean current = target.matches(s);
            JsonObject result = current ? snapshot(target) : scope();
            result.addProperty("trackId", target.id);
            result.addProperty(
                    "sendIndex",
                    state.meta("sendOffset").isJsonNull()
                            ? -1
                            : state.meta("sendOffset").getAsInt() + slot);
            result.addProperty("sendName", name);
            boolean all = current && reason == null;
            JsonObject fields = new JsonObject();
            for (Map.Entry<String, JsonElement> entry : requested.entrySet()) {
                String key = entry.getKey();
                Long before = writes.get(key);
                boolean matched = current && verified(key);
                all &= matched;
                JsonObject field = new JsonObject();
                field.add("requested", entry.getValue());
                field.add("observed", current ? state.meta(prefix + key) : JsonNull.INSTANCE);
                field.addProperty("matchesRequested", matched);
                field.addProperty(
                        "verification",
                        !current
                                ? "target_changed"
                                : before == null
                                        ? "not_dispatched"
                                        : key.equals("valueNormalized") && solvedValue && matched
                                                ? "display_readback"
                                                : before == -1
                                                        ? "already_at_value"
                                                        : state.metaSeq(prefix + key) <= before
                                                                ? "not_observed"
                                                                : matched
                                                                        ? "observer_after_write"
                                                                        : "readback_mismatch");
                fields.add(key, field);
            }
            result.addProperty("status", all ? "observed" : "dispatched");
            result.addProperty("completion", all ? "observed" : "unknown");
            result.addProperty("matchesRequested", all);
            result.add("fields", fields);
            // Guide 7.1.6: moving a send level turns the send on; say so rather than leaving it implicit.
            JsonElement now = state.meta(prefix + "enabled");
            boolean auto = !p.has("enabled")
                    && !enabledBefore.isJsonNull()
                    && !now.isJsonNull()
                    && !enabledBefore.getAsBoolean()
                    && now.getAsBoolean();
            result.addProperty("autoEnabled", auto);
            if (reason != null) result.addProperty("reason", reason);
            for (Map.Entry<String, JsonElement> entry : target.extra.entrySet())
                result.add(entry.getKey(), entry.getValue());
            op.result.complete(result);
        }
    }

    /** The effect (FX) track a send feeds: sends follow effect-track order. Null when that track is not in the observed window. */
    private JsonObject effectTrack(int sendIndex) {
        for (State state : states) {
            Track track = state.track;
            if (!track.exists().get() || !"Effect".equals(track.trackType().get()) || ordinal(track, true) != sendIndex)
                continue;
            JsonObject out = new JsonObject();
            out.addProperty("trackId", track.channelId().get());
            out.addProperty("name", track.name().get());
            return out;
        }
        return null;
    }

    private void guard(HostOperation op, Target target) {
        if (!target.matches(s))
            throw Errors.notApplied(ErrorCode.STALE_TARGET, "Track UUID/proxy changed during operation.");
        if (target.state.rebindPending)
            throw Errors.notApplied(ErrorCode.NOT_READY, "Track proxy rebind awaits a host flush.");
        s.check(op);
    }

    private void await(
            HostOperation op,
            Target target,
            JsonObject changes,
            Map<String, Long> writes,
            JsonObject created,
            String createdKey,
            long until) {
        if (!target.matches(s)) {
            finish(op, target, changes, writes, created, createdKey, "stale_target");
            return;
        }
        boolean all = true;
        for (int i = 0; i < FIELDS.length; i++)
            if (changes.has(FIELDS[i])) all &= verified(target.state, i, changes.get(FIELDS[i]), writes.get(FIELDS[i]));
        if (all || System.nanoTime() >= until) {
            finish(op, target, changes, writes, created, createdKey, null);
            return;
        }
        s.schedule(op, () -> await(op, target, changes, writes, created, createdKey, until), 20);
    }

    private void finish(
            HostOperation op,
            Target target,
            JsonObject changes,
            Map<String, Long> writes,
            JsonObject created,
            String createdKey,
            String reason) {
        boolean current = target.matches(s);
        JsonObject result = current ? snapshot(target) : scope();
        result.addProperty("trackId", target.id);
        boolean all = current && reason == null;
        JsonObject fields = new JsonObject();
        for (int i = 0; i < FIELDS.length; i++) {
            String key = FIELDS[i];
            if (!changes.has(key)) continue;
            Long before = writes.get(key);
            boolean matched = current && verified(target.state, i, changes.get(key), before);
            all &= matched;
            JsonObject field = new JsonObject();
            field.add("requested", changes.get(key));
            field.add("observed", current ? value(target.state, i) : JsonNull.INSTANCE);
            field.addProperty("matchesRequested", matched);
            field.addProperty(
                    "verification",
                    !current
                            ? "target_changed"
                            : before == null
                                    ? "not_dispatched"
                                    : before == -1
                                            ? "already_at_value"
                                            : target.state.sequence[i] <= before
                                                    ? "not_observed"
                                                    : matched ? "observer_after_write" : "readback_mismatch");
            if (target.solved.contains(key) && matched) field.addProperty("verification", "display_readback");
            fields.add(key, field);
        }
        result.addProperty("status", all ? "observed" : "dispatched");
        result.addProperty("completion", all ? "observed" : "unknown");
        result.addProperty("matchesRequested", all);
        result.add("fields", fields);
        if (reason != null) result.addProperty("reason", reason);
        if (created != null) result.add(createdKey, created);
        for (Map.Entry<String, JsonElement> entry : target.extra.entrySet())
            result.add(entry.getKey(), entry.getValue());
        op.result.complete(result);
    }

    private void create(HostOperation op, JsonObject p) {
        if (creationUnresolved)
            throw Errors.notApplied(
                    ErrorCode.UNRESOLVED_CREATION,
                    "A prior native track factory remains unattributed; further automatic creation is quarantined for this extension instance.");
        if (groupUnresolved)
            throw Errors.notApplied(
                    ErrorCode.UNRESOLVED_CREATION,
                    "A prior tracks.group operation remains unattributed; track creation is quarantined for this extension instance.");
        if (creation != null && !creation.result.isDone())
            throw Errors.notApplied(ErrorCode.BUSY, "Another track creation or grouping awaits observations.");
        if (!s.tracksObserved)
            throw Errors.notApplied(ErrorCode.NOT_READY, "Track bank has not reported its size yet.");
        Creation before = capture();
        if (!before.complete)
            throw Errors.notApplied(
                    ErrorCode.NOT_READY,
                    "Creation requires a complete observed UUID/type/position baseline inside the track window.");
        if (before.total >= HostServices.TRACKS)
            throw Errors.notApplied(
                    ErrorCode.OUTSIDE_WINDOW,
                    "The " + HostServices.TRACKS
                            + "-track window is full: the new track could not be observed; no factory was dispatched.");
        if (before.groups && !before.tree.complete)
            throw Errors.notApplied(
                    ErrorCode.UNSUPPORTED_SCOPE,
                    "Track creation in a grouped project needs every track's parent observed (" + before.tree.reason
                            + "); the insertion position cannot be attributed safely and no factory was dispatched.");
        creation = op;
        s.check(op);
        op.dispatched = true;
        creationUnresolved = true;
        int position = p.get("position").getAsInt();
        switch (p.get("type").getAsString()) {
            case "audio":
                s.application.createAudioTrack(position);
                break;
            case "instrument":
                s.application.createInstrumentTrack(position);
                break;
            case "effect":
                s.application.createEffectTrack(position);
                break;
            default:
                throw new IllegalArgumentException("type");
        }
        awaitCreation(op, p, before, null, HostServices.observeUntil(op));
    }

    private void group(HostOperation op, JsonObject p) {
        if (creationUnresolved || groupUnresolved)
            throw Errors.notApplied(
                    ErrorCode.UNRESOLVED_CREATION,
                    "A prior track creation or grouping remains unattributed; grouping is quarantined for this extension instance.");
        if (creation != null && !creation.result.isDone())
            throw Errors.notApplied(ErrorCode.BUSY, "Another track creation or grouping awaits observations.");
        if (!s.tracksObserved)
            throw Errors.notApplied(ErrorCode.NOT_READY, "Track bank has not reported its size yet.");
        s.recordingSafety(op);
        s.requireSelectionFree();
        List<String> ids = trackIds(p);
        Creation before = capture();
        if (!before.complete)
            throw Errors.notApplied(
                    ErrorCode.NOT_READY,
                    "Grouping requires a complete observed UUID/type/position window of at most " + HostServices.TRACKS
                            + " tracks.");
        if (before.total >= HostServices.TRACKS)
            throw Errors.notApplied(
                    ErrorCode.OUTSIDE_WINDOW,
                    "The " + HostServices.TRACKS
                            + "-track window is full: the new group could not be observed; nothing was dispatched.");
        if (!before.tree.complete)
            throw Errors.notApplied(
                    ErrorCode.UNSUPPORTED_SCOPE,
                    "Grouping needs every track's parent observed (" + before.tree.reason
                            + "); nothing was dispatched.");
        List<TrackTree.Node> nodes = new ArrayList<>();
        for (String id : ids) {
            TrackTree.Node node = before.tree.byId.get(id);
            if (node == null)
                throw Errors.notApplied(
                        ErrorCode.OUTSIDE_WINDOW,
                        "Track UUID is absent from the current " + HostServices.TRACKS + "-track window: " + id);
            if (node.master() || "Effect".equals(node.type))
                throw Errors.notApplied(
                        ErrorCode.UNSUPPORTED_SCOPE,
                        "Only audio, instrument, hybrid and group tracks can be grouped; " + id + " is " + node.type
                                + ".");
            nodes.add(node);
        }
        String parent = nodes.get(0).parentId;
        for (TrackTree.Node node : nodes)
            if (!Objects.equals(node.parentId, parent))
                throw Errors.notApplied(
                        ErrorCode.UNSUPPORTED_SCOPE,
                        "All trackIds must share one parent (top level or the same group); nothing was dispatched.");
        nodes.sort(Comparator.comparingInt(n -> n.position));
        List<Object> proxies = new ArrayList<>();
        List<String> ordered = new ArrayList<>();
        for (TrackTree.Node node : nodes) {
            proxies.add(s.tracks.getItemAt(node.slot));
            ordered.add(node.id);
        }
        if (groupAccess == null) {
            try {
                groupAccess =
                        new Bitwig614TrackGroupAccess(s.nativeRuntime(op).access(ErrorCode.TRACK_GROUP_UNAVAILABLE));
            } catch (ReflectiveOperationException error) {
                throw Errors.notApplied(
                        ErrorCode.TRACK_GROUP_UNAVAILABLE,
                        "Exact track group descriptors unavailable: " + error.getMessage());
            }
        }
        GroupAccess access = groupAccess;
        // The native operation selects the new group: hold the selection lease like other selection-moving calls.
        s.acquireSelection(op);
        creation = op;
        Runnable check = () -> {
            s.check(op);
            s.guardRecordingSafety();
        };
        access.execute(() -> {
            try {
                JsonObject nativeInfo = access.group(proxies, ordered, check, () -> {
                    check.run();
                    op.dispatched = true;
                    groupUnresolved = true;
                });
                long until = Math.min(
                        op.deadline - HostServices.DEADLINE_MARGIN_NANOS, System.nanoTime() + GROUP_OBSERVE_NANOS);
                s.schedule(op, () -> awaitGroup(op, p, before, ordered, parent, nativeInfo, null, until), 0);
            } catch (RuntimeException | Error error) {
                op.fail(error);
            }
        });
    }

    private void awaitGroup(
            HostOperation op,
            JsonObject p,
            Creation before,
            List<String> ids,
            String parent,
            JsonObject nativeInfo,
            String candidate,
            long until) {
        Creation after = capture();
        if (after.offset != before.offset || !after.ids.containsAll(before.ids)) {
            unknownGroup(op, p, "track_window_or_existing_targets_changed");
            return;
        }
        Set<String> added = new HashSet<>(after.ids);
        added.removeAll(before.ids);
        if (added.size() > 1 || after.total > before.total + 1) {
            unknownGroup(op, p, "ambiguous_added_tracks");
            return;
        }
        String found = null;
        if (after.complete && after.tree.complete && added.size() == 1 && after.total == before.total + 1) {
            String id = added.iterator().next();
            TrackTree.Node group = after.tree.byId.get(id);
            boolean ok = group != null && group.group() && Objects.equals(group.parentId, parent);
            for (TrackTree.Node node : before.tree.nodes) {
                if (node.master()) continue;
                TrackTree.Node now = after.tree.byId.get(node.id);
                ok &= now != null && Objects.equals(now.parentId, ids.contains(node.id) ? id : node.parentId);
            }
            for (int i = 1; ok && i < ids.size(); i++)
                ok = after.tree.byId.get(ids.get(i - 1)).position < after.tree.byId.get(ids.get(i)).position;
            if (ok) found = id;
        }
        if (found != null && found.equals(candidate)) {
            groupUnresolved = false;
            JsonObject info = nativeInfo.deepCopy();
            info.addProperty("verification", "unique_added_group_uuid_and_every_track_parent_observed_twice");
            JsonArray children = new JsonArray();
            for (String id : ids) children.add(id);
            info.add("children", children);
            info.add("parentId", parent == null ? JsonNull.INSTANCE : new JsonPrimitive(parent));
            info.addProperty("selectionSideEffect", "native_operation_selects_the_new_group");
            State state = states[s.trackSlot(found)];
            Target target = new Target(found, state);
            if (p.has("name")) {
                JsonObject changes = new JsonObject();
                changes.add("name", p.get("name"));
                set(op, target, changes, info, "group");
                return;
            }
            JsonObject result = scope();
            result.addProperty("status", "observed");
            result.addProperty("completion", "observed");
            result.addProperty("trackId", found);
            result.add("group", info);
            op.result.complete(result);
            return;
        }
        if (System.nanoTime() >= until) {
            unknownGroup(op, p, "group_membership_not_observed");
            return;
        }
        String next = found;
        s.schedule(op, () -> awaitGroup(op, p, before, ids, parent, nativeInfo, next, until), 20);
    }

    private void unknownGroup(HostOperation op, JsonObject p, String reason) {
        JsonObject result = scope();
        result.addProperty("status", "dispatched");
        result.addProperty("completion", "unknown");
        result.addProperty("verification", "not_observed");
        result.addProperty("reason", reason);
        result.addProperty("nameApplied", false);
        result.add("requested", p);
        result.add("trackId", JsonNull.INSTANCE);
        result.addProperty(
                "quarantine",
                "tracks.create and tracks.group refuse until extension.restart; inspect tracks.list, never retry");
        op.result.complete(result);
    }

    /** Delete one audio/instrument/hybrid/effect track by Track.deleteObject (DeleteableObject), verified by bank UUID set and count. */
    private void delete(HostOperation op, Target target, String expectedName) {
        HostServices.SafetyObservation safety = s.stoppedSafety(op);
        if (!s.tracksObserved)
            throw Errors.notApplied(ErrorCode.NOT_READY, "Track bank has not reported its size yet.");
        guard(op, target);
        JsonElement type = target.state.meta("type"), name = value(target.state, 0), index = target.state.meta("index");
        if (type.isJsonNull() || name.isJsonNull())
            throw Errors.notApplied(ErrorCode.NOT_READY, "Track type/name have not been observed.");
        if (!Set.of("Audio", "Instrument", "Hybrid", "Effect").contains(type.getAsString()))
            throw Errors.notApplied(
                    ErrorCode.UNSUPPORTED_SCOPE,
                    "Only Audio, Instrument, Hybrid and Effect tracks can be deleted; this track is "
                            + type.getAsString() + " (group and master are refused); nothing was dispatched.");
        if (!name.getAsString().equals(expectedName))
            throw Errors.notApplied(
                    ErrorCode.STALE_TARGET, "Track name differs from expectedName; no delete was dispatched.");
        Creation before = capture();
        if (!before.complete || !before.ids.contains(target.id))
            throw Errors.notApplied(
                    ErrorCode.NOT_READY,
                    "Deletion needs the whole project observed in the " + HostServices.TRACKS
                            + "-track bank window (offset 0, at most " + HostServices.TRACKS
                            + " tracks including group children and effect tracks, every UUID/type/position read); this project exceeds it or is not fully observed yet. Nothing was dispatched.");
        s.guardStoppedSafety(safety);
        guard(op, target);
        op.dispatched = true;
        target.state.track.deleteObject();
        JsonObject deleted = new JsonObject();
        deleted.addProperty("trackId", target.id);
        deleted.addProperty("name", expectedName);
        deleted.addProperty("type", type.getAsString());
        deleted.add("index", index);
        awaitDeletion(op, target.id, deleted, before, HostServices.observeUntil(op));
    }

    private void awaitDeletion(HostOperation op, String id, JsonObject deleted, Creation before, long until) {
        Creation after = capture();
        Set<String> others = new HashSet<>(before.ids);
        others.remove(id);
        boolean gone = after.complete
                && !after.ids.contains(id)
                && after.total == before.total - 1
                && after.ids.equals(others);
        if (gone || System.nanoTime() >= until) {
            JsonObject result = scope();
            result.addProperty("status", gone ? "observed" : "dispatched");
            result.addProperty("completion", gone ? "observed" : "unknown");
            result.addProperty("matchesRequested", gone);
            result.addProperty("verification", gone ? "uuid_absent_count_decremented" : "deletion_not_observed");
            result.add("deleted", deleted);
            result.addProperty("trackCountBefore", before.total);
            result.addProperty("trackCountAfter", after.total);
            op.result.complete(result);
            return;
        }
        s.schedule(op, () -> awaitDeletion(op, id, deleted, before, until), 20);
    }

    private void awaitCreation(HostOperation op, JsonObject p, Creation before, String previousCandidate, long until) {
        Creation after = capture();
        if (after.offset != before.offset || !after.ids.containsAll(before.ids)) {
            unknownCreation(op, p, "track_window_or_existing_targets_changed");
            return;
        }
        Set<String> added = new HashSet<>(after.ids);
        added.removeAll(before.ids);
        if (added.size() > 1 || after.total > before.total + 1) {
            unknownCreation(op, p, "ambiguous_added_tracks");
            return;
        }
        if (after.complete && after.total == before.total + 1 && added.size() == 1) {
            String id = added.iterator().next();
            State state = states[s.trackSlot(id)];
            String type = p.get("type").getAsString();
            boolean effect = type.equals("effect");
            int requested = p.get("position").getAsInt();
            if (!before.groups && after.groups) {
                unknownCreation(op, p, "group_appeared_during_creation");
                return;
            }
            // Application inserts into the root group's main or effect list: in a grouped project the index counts
            // only top-level tracks, so attribution needs the observed parent of the new track.
            int oldSize;
            Integer observed;
            if (before.groups) {
                TrackTree.Node node = after.tree.complete ? after.tree.byId.get(id) : null;
                oldSize = before.tree.topLevelCount(effect);
                observed = node != null && node.topLevel ? after.tree.topLevelOrdinal(node) : null;
            } else {
                oldSize = effect ? before.effects : before.main;
                observed = ordinal(state.track, effect);
            }
            int expected = requested == -1 ? oldSize : Math.min(requested, oldSize);
            if (state.track.trackType().get().equalsIgnoreCase(type) && observed != null && observed == expected) {
                if (id.equals(previousCandidate)) {
                    creationUnresolved = false;
                    JsonObject creationInfo = new JsonObject();
                    creationInfo.addProperty("verification", "unique_added_uuid_count_type_position");
                    creationInfo.addProperty("identityScope", "observed_addition_not_factory_return_identity");
                    creationInfo.addProperty("requestedPosition", requested);
                    creationInfo.addProperty(
                            "observedPosition", state.track.position().get());
                    creationInfo.addProperty("type", type);
                    creationInfo.addProperty(
                            "parentScope", before.groups ? "top_level_of_grouped_project" : "flat_project");
                    JsonObject changes = new JsonObject();
                    changes.add("name", p.get("name"));
                    set(op, new Target(id, state), changes, creationInfo);
                    return;
                }
                previousCandidate = id;
            } else previousCandidate = null;
        } else previousCandidate = null;
        if (System.nanoTime() >= until) {
            unknownCreation(op, p, "unique_added_track_not_observed");
            return;
        }
        final String candidate = previousCandidate;
        s.schedule(op, () -> awaitCreation(op, p, before, candidate, until), 20);
    }

    private int ordinal(Track candidate, boolean effect) {
        int ordinal = 0;
        for (State state : states) {
            Track track = state.track;
            if (!track.exists().get() || track.trackType().get().equals("Master")) continue;
            if (track.trackType().get().equals("Effect") == effect
                    && track.position().get() < candidate.position().get()) ordinal++;
        }
        return ordinal;
    }

    private Creation capture() {
        Creation out = new Creation();
        out.offset = s.tracks.scrollPosition().get();
        out.total = s.tracks.itemCount().get();
        boolean unique = true;
        for (State state : states)
            if (state.track.exists().get()) {
                String id = state.track.channelId().get();
                unique &= id != null && !id.isEmpty() && out.ids.add(id);
                JsonElement observedType = state.meta("type");
                if (observedType.isJsonNull() || state.meta("index").isJsonNull()) {
                    unique = false;
                    continue;
                }
                String type = observedType.getAsString();
                out.groups |= type.equals("Group");
                if (type.equals("Effect")) out.effects++;
                else if (!type.equals("Master")) out.main++;
            }
        out.complete = unique && out.offset == 0 && out.total <= HostServices.TRACKS && out.ids.size() == out.total;
        out.tree = TrackTree.capture(s);
        out.groups |= out.tree.groups;
        return out;
    }

    private void unknownCreation(HostOperation op, JsonObject p, String reason) {
        JsonObject result = scope();
        result.addProperty("status", "dispatched");
        result.addProperty("completion", "unknown");
        result.addProperty("verification", "not_observed");
        result.addProperty("reason", reason);
        result.addProperty("nameApplied", false);
        result.add("requested", p);
        result.add("trackId", JsonNull.INSTANCE);
        op.result.complete(result);
    }

    private JsonObject scope() {
        JsonObject result = HostServices.observed();
        result.addProperty("scope", "flat_track_bank");
        result.addProperty("identityScope", "observed_uuid_on_bank_proxy");
        result.add(
                "window",
                HostServices.window(
                        s.tracks.scrollPosition().get(),
                        HostServices.TRACKS,
                        s.tracks.itemCount().get()));
        return result;
    }

    /** Compact identity row for context.get (null fields when unobserved); never selects. */
    JsonObject brief(int i) {
        State state = states[i];
        JsonObject out = new JsonObject();
        out.addProperty("trackId", state.track.channelId().get());
        out.add("index", state.meta("index"));
        out.add("name", value(state, 0));
        out.add("type", state.meta("type"));
        out.add("selected", value(state, 6));
        return out;
    }

    private JsonObject snapshot(Target target) {
        JsonObject result = scope();
        State state = target.state;
        result.addProperty("trackId", target.id);
        JsonObject readable = new JsonObject();
        boolean complete = true;
        for (int i = 0; i < FIELDS.length; i++) {
            JsonElement observed = value(state, i);
            result.add(FIELDS[i], observed);
            readable.addProperty(FIELDS[i], !observed.isJsonNull());
            if (i < BASE_FIELDS) complete &= !observed.isJsonNull();
        }
        JsonObject options = new JsonObject();
        options.add("monitorMode", EnumOptions.json(state.track.monitorMode()));
        options.add("crossFadeMode", EnumOptions.json(state.track.crossFadeMode()));
        result.add("options", options);
        JsonArray outside = new JsonArray();
        if (EnumOptions.outsideOptions(state.track.monitorMode())) outside.add("monitorMode");
        if (EnumOptions.outsideOptions(state.track.crossFadeMode())) outside.add("crossFadeMode");
        result.add("observedOutsideOptions", outside);
        for (String key : List.of("index", "type", "volumeDisplay", "panDisplay")) {
            JsonElement observed = state.meta(key);
            result.add(key, observed);
            readable.addProperty(key, !observed.isJsonNull());
            complete &= !observed.isJsonNull();
        }
        result.add("readable", readable);
        JsonObject units = new JsonObject();
        units.addProperty("volumeNormalized", "normalized_0_1_not_db");
        units.addProperty("panNormalized", "normalized_0_1");
        result.add("units", units);
        JsonElement offset = state.meta("sendOffset"), count = state.meta("sendCount");
        boolean sendsComplete = !offset.isJsonNull() && !count.isJsonNull();
        JsonArray sends = new JsonArray();
        for (int i = 0; i < HostServices.SENDS; i++) {
            String prefix = "send" + i + ".";
            JsonElement exists = state.meta(prefix + "exists");
            if (exists.isJsonNull()) {
                sendsComplete = false;
                continue;
            }
            if (!exists.getAsBoolean()) continue;
            JsonObject item = new JsonObject();
            item.add("sendIndex", offset.isJsonNull() ? JsonNull.INSTANCE : new JsonPrimitive(offset.getAsInt() + i));
            for (String key : List.of("name", "valueNormalized", "display", "enabled", "preFader")) {
                JsonElement observed = state.meta(prefix + key);
                item.add(key, observed);
                sendsComplete &= !observed.isJsonNull();
            }
            item.addProperty("unit", "normalized_0_1_not_db");
            JsonElement sendIndex = item.get("sendIndex");
            JsonObject effect = sendIndex.isJsonNull() ? null : effectTrack(sendIndex.getAsInt());
            item.add("effectTrack", effect == null ? JsonNull.INSTANCE : effect);
            JsonElement sendName = item.get("name");
            item.add(
                    "nameMatchesEffectTrack",
                    effect == null || sendName.isJsonNull()
                            ? JsonNull.INSTANCE
                            : new JsonPrimitive(effect.get("name").getAsString().equals(sendName.getAsString())));
            sends.add(item);
        }
        result.add("sends", sends);
        result.add(
                "sendWindow",
                offset.isJsonNull() || count.isJsonNull()
                        ? JsonNull.INSTANCE
                        : HostServices.window(offset.getAsInt(), HostServices.SENDS, count.getAsInt()));
        result.addProperty("sendObservationsComplete", sendsComplete);
        result.addProperty("observationsComplete", complete && sendsComplete);
        return result;
    }

    private static boolean verified(State state, int i, JsonElement expected, Long before) {
        return before != null
                && (before == -1 || state.sequence[i] > before)
                && matchesField(i, value(state, i), expected);
    }
    /** Colors are stored as floats, so the host's 8-bit readback can land one step off the requested hex. */
    private static boolean matchesField(int i, JsonElement a, JsonElement b) {
        if (i != 7 || a.isJsonNull() || !a.getAsJsonPrimitive().isString()) return matches(a, b);
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
            return Math.abs(a.getAsDouble() - b.getAsDouble()) <= HostServices.TOLERANCE;
        return a.equals(b);
    }

    private static JsonElement finite(double v) {
        return Double.isFinite(v) ? new JsonPrimitive(v) : JsonNull.INSTANCE;
    }

    private static JsonElement value(State state, int i) {
        Track track = state.track;
        if (!state.readable() || state.sequence[i] == 0) return JsonNull.INSTANCE;
        switch (i) {
            case 0:
                return new JsonPrimitive(track.name().get());
            case 1:
                return finite(track.volume().value().get());
            case 2:
                return finite(track.pan().value().get());
            case 3:
                return new JsonPrimitive(track.mute().get());
            case 4:
                return new JsonPrimitive(track.solo().get());
            case 5:
                return new JsonPrimitive(track.arm().get());
            // This legacy observer has no interested-value getter to refresh at flush.
            case 6:
                return state.fresh(i) ? new JsonPrimitive(state.selected) : JsonNull.INSTANCE;
            case 7:
                return new JsonPrimitive(String.format(
                        "#%02x%02x%02x",
                        Math.round(track.color().red() * 255),
                        Math.round(track.color().green() * 255),
                        Math.round(track.color().blue() * 255)));
            case 8:
                return new JsonPrimitive(track.isActivated().get());
            case 9:
                return new JsonPrimitive(track.monitorMode().get());
            case 10:
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

    private static final class Target {
        final String id;
        final State state;
        final long generation;
        final JsonObject extra = new JsonObject();
        final Set<String> solved = new HashSet<>();

        Target(String id, State state) {
            this.id = id;
            this.state = state;
            generation = state.generation;
        }

        boolean matches(HostServices s) {
            return generation == state.generation && state.sourceMatches() && s.trackMatches(id, state.track);
        }
    }

    private static final class Creation {
        final Set<String> ids = new HashSet<>();
        int offset, total, main, effects;
        boolean complete, groups;
        TrackTree tree;
    }
}
