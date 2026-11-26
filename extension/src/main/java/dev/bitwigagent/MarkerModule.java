package dev.bitwigagent;

import com.bitwig.extension.callback.IntegerValueChangedCallback;
import com.bitwig.extension.controller.api.*;
import com.google.gson.*;
import java.util.*;

/**
 * Arranger cue markers through the public CueMarkerBank. Markers have no stable id: they are addressed by their rank in
 * the beat-sorted list plus an exact expected name (and optionally beat), and every write is verified by comparing the
 * whole observed marker multiset with the one the write should produce.
 */
final class MarkerModule implements HostModule {
    static final Set<String> METHODS = Set.of("markers.list", "markers.insert", "markers.rename", "markers.delete");
    static final int WINDOW = 64;
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

    MarkerModule(
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
        switch (method) {
            case "markers.list":
                Params.fields(p, Set.of(), Set.of());
                return;
            case "markers.insert":
                Params.fields(p, Set.of("beat"), Set.of("name"));
                Params.number(p, "beat", 0, 1e7);
                if (p.has("name")) Params.string(p, "name");
                return;
            case "markers.rename":
                Params.fields(p, Set.of("markerIndex", "expectedName", "name"), Set.of("expectedBeat"));
                Params.integer(p, "markerIndex", 0, WINDOW - 1);
                Params.string(p, "expectedName");
                Params.string(p, "name");
                if (p.has("expectedBeat")) Params.number(p, "expectedBeat", 0, 1e7);
                return;
            case "markers.delete":
                Params.fields(p, Set.of("markerIndex", "expectedName"), Set.of("expectedBeat"));
                Params.integer(p, "markerIndex", 0, WINDOW - 1);
                Params.string(p, "expectedName");
                if (p.has("expectedBeat")) Params.number(p, "expectedBeat", 0, 1e7);
                return;
            default:
                throw Errors.notApplied(Reason.UNKNOWN_METHOD, "Unknown marker method.");
        }
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
            row.addProperty("markerIndex", i);
            row.addProperty("name", snap.markers.get(i).name);
            row.addProperty("beat", snap.markers.get(i).beat);
            list.add(row);
        }
        out.add("markers", list);
        out.add("window", Results.window(snap.offset, WINDOW, snap.total));
        out.addProperty("observationsComplete", snap.complete);
        JsonObject units = new JsonObject();
        units.addProperty("beat", "quarter_note_beats");
        units.addProperty("markerIndex", "rank in the beat-sorted list; not a stable id, pair it with the exact name");
        out.add("units", units);
        return out;
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
        Marker target = locate(before, p);
        Map<String, Integer> expected = before.multiset();
        expected.merge(target.key(), -1, Integer::sum);
        String newName = target.name;
        if (method.equals("markers.rename")) {
            newName = p.get("name").getAsString();
            expected.merge(new Marker(0, newName, target.beat).key(), 1, Integer::sum);
            if (newName.equals(target.name)) {
                finish(op, method, target.beat, newName, Outcome.ALREADY_MATCHED, null);
                return;
            }
        }
        op.dispatched = true;
        holds.place(UNRESOLVED, "A markers.* write was dispatched and its outcome was not observed.");
        CueMarker marker = items[target.slot];
        if (method.equals("markers.rename")) marker.name().set(newName);
        else marker.deleteObject();
        awaitMulti(op, method, expected, target.beat, newName);
    }

    private Marker locate(Snapshot snap, JsonObject p) {
        int rank = p.get("markerIndex").getAsInt();
        if (rank >= snap.markers.size())
            throw Errors.notApplied(Reason.UNKNOWN_NAME, "markerIndex is beyond the observed marker list.");
        Marker m = snap.markers.get(rank);
        if (!m.name.equals(p.get("expectedName").getAsString()))
            throw Errors.notApplied(
                    Reason.STALE_TARGET,
                    "Marker " + rank + " is named " + m.name + ", not the expected name; list again.");
        if (p.has("expectedBeat") && Math.abs(m.beat - p.get("expectedBeat").getAsDouble()) > BEAT_TOLERANCE)
            throw Errors.notApplied(
                    Reason.STALE_TARGET,
                    "Marker " + rank + " is at beat " + m.beat + ", not the expected beat; list again.");
        return m;
    }

    private void insert(HostOperation op, JsonObject p, Snapshot before) {
        transportSafety.requireStopped();
        double beat = p.get("beat").getAsDouble();
        for (Marker m : before.markers)
            if (Math.abs(m.beat - beat) <= BEAT_TOLERANCE)
                throw Errors.notApplied(
                        Reason.TARGET_EXISTS, "A marker named " + m.name + " is already at beat " + m.beat + ".");
        if (before.total >= WINDOW) throw Errors.notApplied(Reason.UNSUPPORTED_SCOPE, "The marker window is full.");
        op.dispatched = true;
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
        // An already-satisfied rename dispatched nothing, so it leaves no unresolved write behind.
        if (outcome.succeeded()) holds.release(UNRESOLVED);
        JsonObject result = describe(read());
        result.addProperty("outcome", outcome.wire());
        JsonObject marker = new JsonObject();
        marker.addProperty("name", name);
        marker.addProperty("beat", beat);
        result.add(method.equals("markers.delete") ? "deleted" : "marker", marker);
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
