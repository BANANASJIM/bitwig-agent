package dev.bitwigagent;

import com.bitwig.extension.callback.*;
import com.bitwig.extension.controller.api.*;
import com.google.gson.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** UUID-targeted mix operations over the single shared, bounded track bank. */
final class MixModule implements HostModule {
    static final Set<String> METHODS = Set.of("tracks.get", "tracks.set", "tracks.create", "tracks.select");
    private static final String[] FIELDS = {"name", "volumeNormalized", "panNormalized", "mute", "solo", "arm", "selected"};
    private static final Set<String> WRITABLE = Set.of("name", "volumeNormalized", "panNormalized", "mute", "solo", "arm");
    private final HostServices s;
    private final State[] states = new State[HostServices.TRACKS];
    private HostOperation creation;
    // A completed/cancelled RPC does not cancel a void native factory. Never reuse its attribution window.
    private boolean creationUnresolved;

    MixModule(HostServices services) {
        s = services;
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
            track.addIsSelectedInEditorObserver(v -> { state.selected = v; state.seen(6); });
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

    @Override public Set<String> methods() { return METHODS; }
    @Override public void flush() { for (State state : states) state.flush(); }
    @Override public void validate(String method, JsonObject p) { validateParams(method, p); }
    static void validateParams(String method, JsonObject p) {
        switch (method) {
            case "tracks.get": case "tracks.select":
                Params.fields(p, Set.of("trackId"), Set.of()); Params.string(p, "trackId"); return;
            case "tracks.set":
                Params.fields(p, Set.of("trackId"), WRITABLE); Params.string(p, "trackId");
                if (p.size() == 1) throw HostServices.failure("invalid_params", "At least one track field is required.");
                if (p.has("name")) Params.string(p, "name");
                for (String key : List.of("volumeNormalized", "panNormalized")) if (p.has(key)) Params.number(p, key, 0, 1);
                for (String key : List.of("mute", "solo", "arm")) if (p.has(key)) Params.bool(p, key);
                return;
            case "tracks.create":
                Params.fields(p, Set.of("type", "position", "name"), Set.of());
                if (!Set.of("audio", "instrument", "effect").contains(Params.string(p, "type")))
                    throw HostServices.failure("invalid_params", "type must be audio, instrument, or effect.");
                Params.integer(p, "position", -1, Integer.MAX_VALUE); Params.string(p, "name"); return;
            default: throw HostServices.failure("unknown_method", "Unknown track method.");
        }
    }

    @Override public void dispatch(HostOperation op, String method, JsonObject p) {
        s.check(op);
        if (method.equals("tracks.create")) { create(op, p); return; }
        String id = p.get("trackId").getAsString();
        State state = states[s.trackSlot(id)];
        Target target = new Target(id, state);
        if (method.equals("tracks.get")) { op.result.complete(snapshot(target)); return; }
        JsonObject changes = new JsonObject();
        if (method.equals("tracks.select")) changes.addProperty("selected", true);
        else for (String key : WRITABLE) if (p.has(key)) changes.add(key, p.get(key));
        set(op, target, changes, null);
    }

    private void set(HostOperation op, Target target, JsonObject changes, JsonObject created) {
        Map<String, Long> writes = new LinkedHashMap<>();
        try {
            for (int i = 0; i < FIELDS.length; i++) {
                String key = FIELDS[i];
                if (!changes.has(key)) continue;
                guard(op, target);
                if (target.state.fresh(i) && matches(value(target.state, i), changes.get(key))) { writes.put(key, -1L); continue; }
                long before = target.state.sequence[i];
                guard(op, target);
                writes.put(key, before); op.dispatched = true;
                JsonElement next = changes.get(key);
                Track track = target.state.track;
                switch (i) {
                    case 0: track.name().set(next.getAsString()); break;
                    // Absolute RPC targets must not participate in physical-controller pickup.
                    case 1: track.volume().value().setImmediately(next.getAsDouble()); break;
                    case 2: track.pan().value().setImmediately(next.getAsDouble()); break;
                    case 3: track.mute().set(next.getAsBoolean()); break;
                    case 4: track.solo().set(next.getAsBoolean()); break;
                    case 5: track.arm().set(next.getAsBoolean()); break;
                    case 6: track.selectInEditor(); break;
                    default: throw new IllegalArgumentException(key);
                }
            }
        } catch (RpcException error) {
            if (!op.dispatched) throw error;
            finish(op, target, changes, writes, created, error.code); return;
        }
        await(op, target, changes, writes, created,
                Math.min(op.deadline - TimeUnit.MILLISECONDS.toNanos(25), System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(750)));
    }

    private void guard(HostOperation op, Target target) {
        if (!target.matches(s)) throw HostServices.failure("stale_target", "Track UUID/proxy changed during operation.");
        if (target.state.rebindPending) throw HostServices.failure("not_ready", "Track proxy rebind awaits a host flush.");
        s.check(op);
    }

    private void await(HostOperation op, Target target, JsonObject changes, Map<String, Long> writes, JsonObject created, long until) {
        if (!target.matches(s)) { finish(op, target, changes, writes, created, "stale_target"); return; }
        boolean all = true;
        for (int i = 0; i < FIELDS.length; i++) if (changes.has(FIELDS[i])) all &= verified(target.state, i, changes.get(FIELDS[i]), writes.get(FIELDS[i]));
        if (all || System.nanoTime() >= until) { finish(op, target, changes, writes, created, null); return; }
        s.schedule(op, () -> await(op, target, changes, writes, created, until), 20);
    }

    private void finish(HostOperation op, Target target, JsonObject changes, Map<String, Long> writes, JsonObject created, String reason) {
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
            field.addProperty("verification", !current ? "target_changed" : before == null ? "not_dispatched" : before == -1 ? "already_at_value"
                    : target.state.sequence[i] <= before ? "not_observed" : matched ? "observer_after_write" : "readback_mismatch");
            fields.add(key, field);
        }
        result.addProperty("status", all ? "observed" : "dispatched");
        result.addProperty("completion", all ? "observed" : "unknown");
        result.addProperty("matchesRequested", all);
        result.add("fields", fields);
        if (reason != null) result.addProperty("reason", reason);
        if (created != null) result.add("creation", created);
        op.result.complete(result);
    }

    private void create(HostOperation op, JsonObject p) {
        if (creationUnresolved) throw HostServices.failure("unresolved_creation", "A prior native track factory remains unattributed; further automatic creation is quarantined for this extension instance.");
        if (creation != null && !creation.result.isDone()) throw HostServices.failure("busy", "Another track creation awaits observations.");
        if (!s.tracksObserved) throw HostServices.failure("not_ready", "Track bank has not reported its size yet.");
        Creation before = capture();
        if (!before.complete) throw HostServices.failure("not_ready", "Creation requires a complete observed UUID/type/position baseline inside the track window.");
        if (before.groups) throw HostServices.failure("unsupported_scope", "Track creation in an observed grouped project is unsupported: insertion position cannot be attributed safely; no factory was dispatched.");
        creation = op;
        s.check(op); op.dispatched = true; creationUnresolved = true;
        int position = p.get("position").getAsInt();
        switch (p.get("type").getAsString()) {
            case "audio": s.application.createAudioTrack(position); break;
            case "instrument": s.application.createInstrumentTrack(position); break;
            case "effect": s.application.createEffectTrack(position); break;
            default: throw new IllegalArgumentException("type");
        }
        awaitCreation(op, p, before, null,
                Math.min(op.deadline - TimeUnit.MILLISECONDS.toNanos(25), System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(750)));
    }

    private void awaitCreation(HostOperation op, JsonObject p, Creation before, String previousCandidate, long until) {
        Creation after = capture();
        if (after.offset != before.offset || !after.ids.containsAll(before.ids)) {
            unknownCreation(op, p, "track_window_or_existing_targets_changed"); return;
        }
        Set<String> added = new HashSet<>(after.ids); added.removeAll(before.ids);
        if (added.size() > 1 || after.total > before.total + 1) { unknownCreation(op, p, "ambiguous_added_tracks"); return; }
        if (after.complete && after.total == before.total + 1 && added.size() == 1) {
            String id = added.iterator().next();
            State state = states[s.trackSlot(id)];
            String type = p.get("type").getAsString();
            boolean effect = type.equals("effect");
            int oldSize = effect ? before.effects : before.main;
            int requested = p.get("position").getAsInt();
            int expected = requested == -1 ? oldSize : Math.min(requested, oldSize);
            // Flat child tracks cannot be converted safely to Application's main-list insertion index.
            if (before.groups || after.groups) { unknownCreation(op, p, "grouped_insertion_position_not_observable"); return; }
            if (state.track.trackType().get().equalsIgnoreCase(type) && ordinal(state.track, effect) == expected) {
                if (id.equals(previousCandidate)) {
                    creationUnresolved = false;
                    JsonObject creationInfo = new JsonObject();
                    creationInfo.addProperty("verification", "unique_added_uuid_count_type_position");
                    creationInfo.addProperty("identityScope", "observed_addition_not_factory_return_identity");
                    creationInfo.addProperty("requestedPosition", requested);
                    creationInfo.addProperty("observedPosition", state.track.position().get());
                    creationInfo.addProperty("type", type);
                    JsonObject changes = new JsonObject(); changes.add("name", p.get("name"));
                    set(op, new Target(id, state), changes, creationInfo); return;
                }
                previousCandidate = id;
            } else previousCandidate = null;
        } else previousCandidate = null;
        if (System.nanoTime() >= until) { unknownCreation(op, p, "unique_added_track_not_observed"); return; }
        final String candidate = previousCandidate;
        s.schedule(op, () -> awaitCreation(op, p, before, candidate, until), 20);
    }

    private int ordinal(Track candidate, boolean effect) {
        int ordinal = 0;
        for (State state : states) {
            Track track = state.track;
            if (!track.exists().get() || track.trackType().get().equals("Master")) continue;
            if (track.trackType().get().equals("Effect") == effect && track.position().get() < candidate.position().get()) ordinal++;
        }
        return ordinal;
    }

    private Creation capture() {
        Creation out = new Creation();
        out.offset = s.tracks.scrollPosition().get(); out.total = s.tracks.itemCount().get();
        boolean unique = true;
        for (State state : states) if (state.track.exists().get()) {
            String id = state.track.channelId().get();
            unique &= id != null && !id.isEmpty() && out.ids.add(id);
            JsonElement observedType = state.meta("type");
            if (observedType.isJsonNull() || state.meta("index").isJsonNull()) { unique = false; continue; }
            String type = observedType.getAsString();
            out.groups |= type.equals("Group");
            if (type.equals("Effect")) out.effects++;
            else if (!type.equals("Master")) out.main++;
        }
        out.complete = unique && out.offset == 0 && out.total <= HostServices.TRACKS && out.ids.size() == out.total;
        return out;
    }

    private void unknownCreation(HostOperation op, JsonObject p, String reason) {
        JsonObject result = scope();
        result.addProperty("status", "dispatched"); result.addProperty("completion", "unknown");
        result.addProperty("verification", "not_observed"); result.addProperty("reason", reason);
        result.addProperty("nameApplied", false); result.add("requested", p);
        result.add("trackId", JsonNull.INSTANCE); op.result.complete(result);
    }

    private JsonObject scope() {
        JsonObject result = HostServices.observed();
        result.addProperty("scope", "flat_track_bank");
        result.addProperty("identityScope", "observed_uuid_on_bank_proxy");
        result.add("window", HostServices.window(s.tracks.scrollPosition().get(), HostServices.TRACKS, s.tracks.itemCount().get()));
        return result;
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
            complete &= !observed.isJsonNull();
        }
        for (String key : List.of("index", "type", "volumeDisplay", "panDisplay")) {
            JsonElement observed = state.meta(key);
            result.add(key, observed);
            readable.addProperty(key, !observed.isJsonNull());
            complete &= !observed.isJsonNull();
        }
        result.add("readable", readable);
        JsonObject units = new JsonObject();
        units.addProperty("volumeNormalized", "normalized_0_1_not_db"); units.addProperty("panNormalized", "normalized_0_1");
        result.add("units", units);
        JsonElement offset = state.meta("sendOffset"), count = state.meta("sendCount");
        boolean sendsComplete = !offset.isJsonNull() && !count.isJsonNull();
        JsonArray sends = new JsonArray();
        for (int i = 0; i < HostServices.SENDS; i++) {
            String prefix = "send" + i + ".";
            JsonElement exists = state.meta(prefix + "exists");
            if (exists.isJsonNull()) { sendsComplete = false; continue; }
            if (!exists.getAsBoolean()) continue;
            JsonObject item = new JsonObject();
            item.add("sendIndex", offset.isJsonNull() ? JsonNull.INSTANCE : new JsonPrimitive(offset.getAsInt() + i));
            for (String key : List.of("name", "valueNormalized", "display", "enabled", "preFader")) {
                JsonElement observed = state.meta(prefix + key);
                item.add(key, observed);
                sendsComplete &= !observed.isJsonNull();
            }
            item.addProperty("unit", "normalized_0_1_not_db");
            sends.add(item);
        }
        result.add("sends", sends);
        result.add("sendWindow", offset.isJsonNull() || count.isJsonNull() ? JsonNull.INSTANCE
                : HostServices.window(offset.getAsInt(), HostServices.SENDS, count.getAsInt()));
        result.addProperty("sendObservationsComplete", sendsComplete);
        result.addProperty("observationsComplete", complete && sendsComplete);
        return result;
    }

    private static boolean verified(State state, int i, JsonElement expected, Long before) {
        return before != null && (before == -1 || state.sequence[i] > before) && matches(value(state, i), expected);
    }
    private static boolean matches(JsonElement a, JsonElement b) {
        if (a.isJsonNull()) return false;
        if (a.getAsJsonPrimitive().isNumber()) return Math.abs(a.getAsDouble() - b.getAsDouble()) <= HostServices.TOLERANCE;
        return a.equals(b);
    }
    private static JsonElement finite(double v) { return Double.isFinite(v) ? new JsonPrimitive(v) : JsonNull.INSTANCE; }
    private static JsonElement value(State state, int i) {
        Track track = state.track;
        if (!state.readable() || state.sequence[i] == 0) return JsonNull.INSTANCE;
        switch (i) {
            case 0: return new JsonPrimitive(track.name().get());
            case 1: return finite(track.volume().value().get());
            case 2: return finite(track.pan().value().get());
            case 3: return new JsonPrimitive(track.mute().get());
            case 4: return new JsonPrimitive(track.solo().get());
            case 5: return new JsonPrimitive(track.arm().get());
            // This legacy observer has no interested-value getter to refresh at flush.
            case 6: return state.fresh(i) ? new JsonPrimitive(state.selected) : JsonNull.INSTANCE;
            default: throw new IllegalArgumentException("field");
        }
    }
    private static void observe(State state, String key, StringValue source) {
        state.metadataSources.put(key, source);
        source.addValueObserver((StringValueChangedCallback) v -> state.metadata.put(key, new JsonPrimitive((String) v)));
    }
    private static void observe(State state, String key, BooleanValue source) {
        state.metadataSources.put(key, source);
        source.addValueObserver((BooleanValueChangedCallback) v -> state.metadata.put(key, new JsonPrimitive(v)));
    }
    private static void observe(State state, String key, IntegerValue source) {
        state.metadataSources.put(key, source);
        source.addValueObserver((IntegerValueChangedCallback) v -> state.metadata.put(key, new JsonPrimitive(v)));
    }
    private static void observe(State state, String key, RangedValue source) {
        state.metadataSources.put(key, source);
        source.addValueObserver((DoubleValueChangedCallback) v -> state.metadata.put(key, finite(v)));
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
        String identity;
        Boolean exists;
        boolean established, rebindPending;
        long generation;
        boolean selected;
        State(Track track, ControllerHost host) { this.track = track; this.host = host; }
        void seen(int i) { sequence[i]++; fieldGeneration[i] = generation; }
        boolean fresh(int i) { return sequence[i] > 0 && fieldGeneration[i] == generation; }
        boolean sourceMatches() {
            return Boolean.TRUE.equals(exists) && track.exists().get()
                    && meaningful(identity) && identity.equals(track.channelId().get());
        }
        boolean readable() { return !rebindPending && sourceMatches(); }
        JsonElement meta(String key) { return readable() ? metadata.getOrDefault(key, JsonNull.INSTANCE) : JsonNull.INSTANCE; }
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
        private static boolean meaningful(String id) { return id != null && !id.isEmpty(); }
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
        Target(String id, State state) { this.id = id; this.state = state; generation = state.generation; }
        boolean matches(HostServices s) {
            return generation == state.generation && state.sourceMatches() && s.trackMatches(id, state.track);
        }
    }
    private static final class Creation {
        final Set<String> ids = new HashSet<>();
        int offset, total, main, effects;
        boolean complete, groups;
    }
}
