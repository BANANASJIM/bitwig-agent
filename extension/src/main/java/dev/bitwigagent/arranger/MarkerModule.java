package dev.bitwigagent.arranger;

import com.bitwig.extension.callback.IntegerValueChangedCallback;
import com.bitwig.extension.controller.api.*;
import com.google.gson.*;
import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.Holds;
import dev.bitwigagent.core.HostModule;
import dev.bitwigagent.core.HostOperation;
import dev.bitwigagent.core.Outcome;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.core.Refs;
import dev.bitwigagent.core.Results;
import dev.bitwigagent.core.Scheduler;
import dev.bitwigagent.core.Verification;
import dev.bitwigagent.host.TransportSafety;
import java.util.*;

/**
 * Arranger cue markers through the public CueMarkerBank. Markers have no stable id: a marker ref is its rank in the
 * beat-sorted list plus its name and beat, all compared, and every write is verified by comparing the whole observed
 * marker multiset with the one the write should produce.
 */
public final class MarkerModule implements HostModule {
    static final Set<String> METHODS = Set.of("markers.list", "markers.insert", "markers.set", "markers.delete");
    public static final int WINDOW = 64;
    static final double BEAT_TOLERANCE = 1e-6;
    private final Scheduler scheduler;
    private final Transport transport;
    private final TransportSafety transportSafety;
    private final Holds holds;
    private final CueMarkerBank bank;
    private final CueMarker[] items = new CueMarker[WINDOW];
    private static final String UNRESOLVED = "markers.unresolved";
    private static final String INSPECT_THEN_ACK = "Inspect markers.list, then ack " + UNRESOLVED + ".";
    private boolean observed;

    public MarkerModule(
            ControllerHost host,
            Transport transport,
            Scheduler scheduler,
            TransportSafety transportSafety,
            Holds holds) {
        this.transport = transport;
        this.scheduler = scheduler;
        this.transportSafety = transportSafety;
        this.holds = holds;
        bank = host.createArranger().createCueMarkerBank(WINDOW);
        bank.itemCount().addValueObserver((IntegerValueChangedCallback) count -> observed = true);
        bank.scrollPosition().markInterested();
        for (int i = 0; i < WINDOW; i++) {
            CueMarker marker = items[i] = (CueMarker) bank.getItemAt(i);
            marker.exists().markInterested();
            marker.name().markInterested();
            marker.position().markInterested();
        }
    }

    @Override
    public Set<String> methods() {
        return METHODS;
    }

    @Override
    public void validate(String method, JsonObject p) {
        validateParams(method, p);
    }

    static void validateParams(String method, JsonObject p) {
        if (!METHODS.contains(method)) throw Errors.notApplied(Reason.UNKNOWN_METHOD, "Unknown marker method.");
    }

    private static final class Marker {
        final int slot;
        final String name;
        final double beat;

        Marker(int slot, String name, double beat) {
            this.slot = slot;
            this.name = name;
            this.beat = beat;
        }

        String key() {
            return name + "@" + Math.round(beat / BEAT_TOLERANCE);
        }
    }

    private static final class Snapshot {
        final List<Marker> markers = new ArrayList<>();
        int total, offset;
        boolean complete;

        Map<String, Integer> multiset() {
            Map<String, Integer> out = new HashMap<>();
            for (Marker m : markers) out.merge(m.key(), 1, Integer::sum);
            return out;
        }
    }

    private Snapshot read() {
        Snapshot out = new Snapshot();
        out.total = bank.itemCount().get();
        out.offset = bank.scrollPosition().get();
        for (int i = 0; i < WINDOW; i++)
            if (items[i].exists().get())
                out.markers.add(
                        new Marker(i, items[i].name().get(), items[i].position().get()));
        out.markers.sort(Comparator.comparingDouble((Marker m) -> m.beat).thenComparingInt(m -> m.slot));
        out.complete = observed && out.offset == 0 && out.total <= WINDOW && out.markers.size() == out.total;
        return out;
    }

    private JsonObject describe(Snapshot snap) {
        JsonObject out = new JsonObject();
        out.addProperty("scope", "arranger_cue_markers");
        JsonArray list = new JsonArray();
        for (int i = 0; i < snap.markers.size(); i++) {
            JsonObject row = new JsonObject();
            row.add("ref", ref(snap, i));
            row.addProperty("name", snap.markers.get(i).name);
            row.addProperty("beat", snap.markers.get(i).beat);
            list.add(row);
        }
        out.add("markers", list);
        out.add("window", Results.window(snap.offset, WINDOW, snap.total));
        out.addProperty("observationsComplete", snap.complete);
        JsonObject units = new JsonObject();
        units.addProperty("beat", "quarter_note_beats");
        out.add("units", units);
        return out;
    }

    private static JsonObject ref(Snapshot snap, int rank) {
        Marker marker = snap.markers.get(rank);
        return Refs.marker(rank, marker.name, marker.beat);
    }

    @Override
    public void dispatch(HostOperation op, String method, JsonObject p) {
        scheduler.check(op);
        if (!observed) throw Errors.notApplied(Reason.NOT_READY, "Cue marker bank has not reported its size yet.");
        Snapshot before = read();
        if (method.equals("markers.list")) {
            op.result.complete(describe(before));
            return;
        }
        if (!before.complete)
            throw Errors.notApplied(
                    Reason.UNSUPPORTED_SCOPE,
                    "Marker edits need the whole marker list inside the " + WINDOW + "-marker window.");
        if (holds.held(UNRESOLVED))
            throw holds.blocked(UNRESOLVED, "A prior marker write was not observed.", "Inspect markers.list");
        transportSafety.requirePlaybackSafe();
        if (method.equals("markers.insert")) {
            insert(op, p, before);
            return;
        }
        Marker target = locate(before, p.getAsJsonObject("target"));
        Map<String, Integer> expected = before.multiset();
        expected.merge(target.key(), -1, Integer::sum);
        String newName = target.name;
        if (method.equals("markers.set")) {
            newName = p.get("name").getAsString();
            expected.merge(new Marker(0, newName, target.beat).key(), 1, Integer::sum);
            if (newName.equals(target.name)) {
                finish(op, method, target.beat, newName, Outcome.ALREADY_MATCHED, null);
                return;
            }
        }
        op.markDispatched();
        holds.place(UNRESOLVED, "A markers.* write was dispatched and its outcome was not observed.");
        CueMarker marker = items[target.slot];
        if (method.equals("markers.set")) marker.name().set(newName);
        else marker.deleteObject();
        awaitMulti(op, method, expected, target.beat, newName);
    }

    private Marker locate(Snapshot snap, JsonObject ref) {
        int rank = ref.get("index").getAsInt();
        if (rank >= snap.markers.size())
            throw Errors.targetNotFound(ref, "The marker is beyond the observed marker list.");
        JsonObject current = ref(snap, rank);
        if (!Refs.matches(ref, current, true))
            throw Errors.targetChanged(
                    "The marker at that rank was renamed, moved or replaced since it was read.", current);
        return snap.markers.get(rank);
    }

    private void insert(HostOperation op, JsonObject p, Snapshot before) {
        transportSafety.requireStopped();
        double beat = p.get("beat").getAsDouble();
        for (Marker m : before.markers)
            if (Math.abs(m.beat - beat) <= BEAT_TOLERANCE)
                throw Errors.notApplied(
                        Reason.TARGET_EXISTS, "A marker named " + m.name + " is already at beat " + m.beat + ".");
        if (before.total >= WINDOW) throw Errors.notApplied(Reason.UNSUPPORTED_SCOPE, "The marker window is full.");
        op.markDispatched();
        holds.place(UNRESOLVED, "A markers.* write was dispatched and its outcome was not observed.");
        transport.addCueMarkerAtPlaybackPosition();
        awaitInserted(op, p, before);
    }

    /** The new marker is whatever single entry the multiset gained; it is then named and moved in one controller callback. */
    private void awaitInserted(HostOperation op, JsonObject p, Snapshot before) {
        Runnable finish = () -> {
            Snapshot now = read();
            Marker created = gained(now, before);
            if (created == null) {
                JsonObject result = describe(now);
                result.addProperty("outcome", Outcome.UNKNOWN.wire());
                result.addProperty("reason", Reason.NOT_OBSERVED.wire());
                result.addProperty("message", "The inserted marker was not observed as a single new entry.");
                result.addProperty("next", INSPECT_THEN_ACK);
                holds.place(UNRESOLVED, "A markers.* write was dispatched and its outcome was not observed.");
                op.result.complete(result);
                return;
            }
            double beat = p.get("beat").getAsDouble();
            String name = p.has("name") ? p.get("name").getAsString() : created.name;
            boolean sameName = name.equals(created.name), sameBeat = Math.abs(created.beat - beat) <= BEAT_TOLERANCE;
            CueMarker marker = items[created.slot];
            if (!sameName) marker.name().set(name);
            if (!sameBeat) marker.position().set(beat);
            Map<String, Integer> expected = before.multiset();
            expected.merge(new Marker(0, name, beat).key(), 1, Integer::sum);
            awaitMulti(op, "markers.insert", expected, beat, name);
        };
        scheduler.poll(
                op,
                scheduler.observeUntil(op),
                () -> {
                    Snapshot now = read();
                    return gained(now, before) != null
                            || now.total > before.total + 1
                            || diff(now.multiset(), before.multiset()).size() > 1;
                },
                finish,
                finish);
    }

    /** The single marker the list gained, or null while that is not (yet) what was observed. */
    private Marker gained(Snapshot now, Snapshot before) {
        Map<String, Integer> gained = diff(now.multiset(), before.multiset());
        if (now.total != before.total + 1
                || !now.complete
                || gained.size() != 1
                || gained.values().iterator().next() != 1) return null;
        String key = gained.keySet().iterator().next();
        for (Marker m : now.markers) if (m.key().equals(key)) return m;
        return null;
    }

    private void awaitMulti(HostOperation op, String method, Map<String, Integer> expected, double beat, String name) {
        scheduler.poll(
                op,
                scheduler.observeUntil(op),
                () -> {
                    Snapshot now = read();
                    return now.complete && same(now.multiset(), expected);
                },
                () -> finish(op, method, beat, name, Outcome.APPLIED, null),
                () -> finish(op, method, beat, name, Outcome.UNKNOWN, Reason.NOT_OBSERVED));
    }

    private void finish(HostOperation op, String method, double beat, String name, Outcome outcome, Reason reason) {
        // An already-satisfied set dispatched nothing, so it leaves no unresolved write behind.
        if (outcome.succeeded()) holds.release(UNRESOLVED);
        Snapshot now = read();
        JsonObject result = describe(now);
        result.addProperty("outcome", outcome.wire());
        Verification.API_READBACK.putIfApplied(result);
        if (outcome.succeeded() && !method.equals("markers.delete")) {
            String key = new Marker(0, name, beat).key();
            for (int i = 0; i < now.markers.size(); i++)
                if (now.markers.get(i).key().equals(key)) {
                    result.add("ref", ref(now, i));
                    break;
                }
        }
        if (reason != null) {
            result.addProperty("reason", reason.wire());
            result.addProperty("message", "The marker list did not reach the expected state.");
            result.addProperty("next", INSPECT_THEN_ACK);
        }
        op.result.complete(result);
    }

    private static Map<String, Integer> diff(Map<String, Integer> a, Map<String, Integer> b) {
        Map<String, Integer> out = new HashMap<>();
        for (Map.Entry<String, Integer> e : a.entrySet()) {
            int d = e.getValue() - b.getOrDefault(e.getKey(), 0);
            if (d != 0) out.put(e.getKey(), d);
        }
        for (Map.Entry<String, Integer> e : b.entrySet())
            if (!a.containsKey(e.getKey())) out.put(e.getKey(), -e.getValue());
        return out;
    }

    private static boolean same(Map<String, Integer> a, Map<String, Integer> b) {
        Map<String, Integer> x = new HashMap<>(a), y = new HashMap<>(b);
        x.values().removeIf(v -> v == 0);
        y.values().removeIf(v -> v == 0);
        return x.equals(y);
    }
}
