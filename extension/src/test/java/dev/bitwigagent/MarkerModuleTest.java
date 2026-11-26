package dev.bitwigagent;

import com.bitwig.extension.controller.api.CueMarker;
import com.google.gson.*;
import java.util.*;

/** Marker list/insert/rename/delete against a simulated beat-sorted CueMarkerBank; no claim about native marker semantics. */
public final class MarkerModuleTest {
    private static int assertions;

    private static final class Mk {
        String name;
        double beat;

        Mk(String n, double b) {
            name = n;
            beat = b;
        }
    }

    /** A bank that keeps its entries sorted by beat and mirrors them into the fixture nodes after every write. */
    private static final class Bank {
        final TransportMixFixture f;
        final TransportMixFixture.Node node;
        final List<Mk> model = new ArrayList<>();
        int created;

        Bank(TransportMixFixture f) {
            this.f = f;
            node = f.host.child("createArranger").child("createCueMarkerBank");
            node.custom = (name, args) ->
                    name.equals("getItemAt") ? node.item((Integer) args[0]).proxy(CueMarker.class) : null;
            for (int i = 0; i < MarkerModule.WINDOW; i++) {
                final int slot = i;
                TransportMixFixture.Node m = node.item(i);
                m.child("name").afterWrite = () -> {
                    model.get(slot).name = (String) m.child("name").data;
                    refresh();
                };
                m.child("position").afterWrite = () -> {
                    model.get(slot).beat = ((Number) m.child("position").data).doubleValue();
                    refresh();
                };
                m.custom = (name, args) -> {
                    if (name.equals("deleteObject")) {
                        f.mutation();
                        model.remove(slot);
                        refresh();
                    }
                    return null;
                };
            }
            node.child("scrollPosition").data = 0;
            f.addMarker = () -> {
                model.add(new Mk(
                        "Marker " + (++created), ((Number) f.transport.child("playPosition").data).doubleValue()));
                refresh();
            };
            refresh();
        }

        void add(String name, double beat) {
            model.add(new Mk(name, beat));
            refresh();
        }

        void refresh() {
            model.sort(Comparator.comparingDouble(m -> m.beat));
            for (int i = 0; i < MarkerModule.WINDOW; i++) {
                TransportMixFixture.Node m = node.item(i);
                m.child("exists").data = i < model.size();
                m.child("name").data = i < model.size() ? model.get(i).name : "";
                m.child("position").data = i < model.size() ? model.get(i).beat : 0.0;
            }
            node.child("itemCount").emit(model.size());
        }

        List<String> view() {
            List<String> out = new ArrayList<>();
            for (Mk m : model) out.add(m.name + "@" + m.beat);
            return out;
        }
    }

    public static void main(String[] args) {
        listsSortedWithWindow();
        invalidRequestsNeverSchedule();
        insertNamesAndMovesTheNewMarker();
        insertWithoutNameKeepsHostName();
        insertRefusals();
        renameIsCasAndVerified();
        deleteIsCasAndVerified();
        unobservedInsertQuarantines();
        incompleteWindowBlocksWrites();
        System.out.println("MarkerModuleTest: " + assertions + " assertions passed (simulated host only)");
    }

    private static MarkerModule module(TransportMixFixture f) {
        return new MarkerModule(f.services);
    }

    private static JsonObject json(String t) {
        return TransportMixFixture.json(t);
    }

    private static JsonObject run(TransportMixFixture f, HostOperation op) {
        for (int i = 0; i < 500 && !op.result.isDone(); i++) f.next();
        check(op.result.isDone(), "operation completed");
        return op.result.join();
    }

    private static JsonObject call(TransportMixFixture f, MarkerModule m, String method, String params) {
        return run(f, f.submit(m, method, json(params)));
    }

    private static void listsSortedWithWindow() {
        TransportMixFixture f = new TransportMixFixture();
        Bank bank = new Bank(f);
        bank.add("Chorus", 32);
        bank.add("Intro", 0);
        bank.add("Verse", 8);
        MarkerModule m = module(f);
        JsonObject r = f.call(m, "markers.list", new JsonObject());
        JsonArray list = r.getAsJsonArray("markers");
        check(
                list.size() == 3
                        && list.get(0)
                                .getAsJsonObject()
                                .get("name")
                                .getAsString()
                                .equals("Intro")
                        && list.get(2).getAsJsonObject().get("beat").getAsDouble() == 32
                        && list.get(1).getAsJsonObject().get("markerIndex").getAsInt() == 1,
                "sorted by beat with rank index");
        check(
                r.get("observationsComplete").getAsBoolean()
                        && r.getAsJsonObject("window").get("capacity").getAsInt() == MarkerModule.WINDOW,
                "window reported");
        check(f.transport.actions == 0 && f.tasks.isEmpty(), "list never mutates");
    }

    private static void invalidRequestsNeverSchedule() {
        TransportMixFixture f = new TransportMixFixture();
        new Bank(f);
        MarkerModule m = module(f);
        for (String[] c : new String[][] {
            {"markers.insert", "{}"},
            {"markers.insert", "{\"beat\":-1}"},
            {"markers.insert", "{\"beat\":1,\"name\":\"\"}"},
            {"markers.rename", "{\"markerIndex\":0,\"expectedName\":\"A\"}"},
            {"markers.rename", "{\"markerIndex\":64,\"expectedName\":\"A\",\"name\":\"B\"}"},
            {"markers.delete", "{\"markerIndex\":0}"},
            {"markers.delete", "{\"markerIndex\":0,\"expectedName\":\"A\",\"extra\":1}"},
            {"markers.list", "{\"x\":1}"}
        }) {
            TransportMixFixture.error(f.submit(m, c[0], json(c[1])), "invalid_params");
            check(f.tasks.isEmpty(), "invalid marker request not scheduled: " + c[1]);
        }
    }

    private static void insertNamesAndMovesTheNewMarker() {
        TransportMixFixture f = new TransportMixFixture();
        Bank bank = new Bank(f);
        bank.add("Intro", 0);
        bank.add("Outro", 64);
        MarkerModule m = module(f);
        f.transport.child("playPosition").data = 16.0;
        JsonObject r = call(f, m, "markers.insert", "{\"beat\":8,\"name\":\"Verse\"}");
        check(
                r.get("status").getAsString().equals("observed")
                        && r.get("matchesRequested").getAsBoolean(),
                "insert observed: " + r);
        check(
                bank.view().equals(List.of("Intro@0.0", "Verse@8.0", "Outro@64.0")),
                "marker named and moved to the beat: " + bank.view());
        check(
                f.transport.actions == 1
                        && f.transport.child("playPosition").data.equals(16.0),
                "one native add; playhead untouched");
        check(r.getAsJsonObject("marker").get("name").getAsString().equals("Verse"), "result names the marker");
        check(r.getAsJsonArray("markers").size() == 3, "result carries the observed list");
        check(m.unresolved().isEmpty(), "observed insert leaves nothing unresolved");
    }

    private static void insertWithoutNameKeepsHostName() {
        TransportMixFixture f = new TransportMixFixture();
        Bank bank = new Bank(f);
        MarkerModule m = module(f);
        JsonObject r = call(f, m, "markers.insert", "{\"beat\":4}");
        check(
                r.get("matchesRequested").getAsBoolean() && bank.view().equals(List.of("Marker 1@4.0")),
                "host-assigned name kept: " + bank.view());
    }

    private static void insertRefusals() {
        TransportMixFixture f = new TransportMixFixture();
        Bank bank = new Bank(f);
        bank.add("A", 8);
        MarkerModule m = module(f);
        HostOperation dup = f.submit(m, "markers.insert", json("{\"beat\":8}"));
        f.next();
        TransportMixFixture.error(dup, "marker_exists_at_beat");
        f.transport.child("isPlaying").emit(true);
        HostOperation playing = f.submit(m, "markers.insert", json("{\"beat\":9}"));
        f.next();
        TransportMixFixture.error(playing, "unsafe_state");
        f.transport.child("isPlaying").emit(false);
        f.transport.child("isArrangerRecordEnabled").emit(true);
        HostOperation recording = f.submit(m, "markers.insert", json("{\"beat\":9}"));
        f.next();
        TransportMixFixture.error(recording, "unsafe_state");
        check(f.transport.actions == 0 && bank.view().equals(List.of("A@8.0")), "refused inserts dispatched nothing");
    }

    private static void renameIsCasAndVerified() {
        TransportMixFixture f = new TransportMixFixture();
        Bank bank = new Bank(f);
        bank.add("Intro", 0);
        bank.add("Verse", 8);
        MarkerModule m = module(f);
        HostOperation stale =
                f.submit(m, "markers.rename", json("{\"markerIndex\":1,\"expectedName\":\"Chorus\",\"name\":\"X\"}"));
        f.next();
        TransportMixFixture.error(stale, "stale_target");
        HostOperation wrongBeat = f.submit(
                m,
                "markers.rename",
                json("{\"markerIndex\":1,\"expectedName\":\"Verse\",\"expectedBeat\":9,\"name\":\"X\"}"));
        f.next();
        TransportMixFixture.error(wrongBeat, "stale_target");
        HostOperation beyond =
                f.submit(m, "markers.rename", json("{\"markerIndex\":5,\"expectedName\":\"Verse\",\"name\":\"X\"}"));
        f.next();
        TransportMixFixture.error(beyond, "unknown_marker");
        check(bank.view().equals(List.of("Intro@0.0", "Verse@8.0")), "refusals changed nothing");
        JsonObject r = call(
                f,
                m,
                "markers.rename",
                "{\"markerIndex\":1,\"expectedName\":\"Verse\",\"expectedBeat\":8,\"name\":\"Verse 1\"}");
        check(
                r.get("matchesRequested").getAsBoolean() && bank.view().equals(List.of("Intro@0.0", "Verse 1@8.0")),
                "renamed: " + bank.view());
        JsonObject same =
                call(f, m, "markers.rename", "{\"markerIndex\":1,\"expectedName\":\"Verse 1\",\"name\":\"Verse 1\"}");
        check(
                same.get("verification").getAsString().equals("already_at_value")
                        && m.unresolved().isEmpty(),
                "same name is not rewritten");
    }

    private static void deleteIsCasAndVerified() {
        TransportMixFixture f = new TransportMixFixture();
        Bank bank = new Bank(f);
        bank.add("Intro", 0);
        bank.add("Verse", 8);
        bank.add("Chorus", 16);
        MarkerModule m = module(f);
        HostOperation stale = f.submit(m, "markers.delete", json("{\"markerIndex\":0,\"expectedName\":\"Verse\"}"));
        f.next();
        TransportMixFixture.error(stale, "stale_target");
        JsonObject r =
                call(f, m, "markers.delete", "{\"markerIndex\":1,\"expectedName\":\"Verse\",\"expectedBeat\":8}");
        check(
                r.get("matchesRequested").getAsBoolean() && bank.view().equals(List.of("Intro@0.0", "Chorus@16.0")),
                "deleted: " + bank.view());
        check(
                r.getAsJsonObject("deleted").get("name").getAsString().equals("Verse"),
                "result names the deleted marker");
    }

    private static void unobservedInsertQuarantines() {
        TransportMixFixture f = new TransportMixFixture();
        Bank bank = new Bank(f);
        f.addMarker = () -> {}; // the host accepts the call but never shows a marker
        MarkerModule m = module(f);
        HostOperation op = f.submit(m, "markers.insert", json("{\"beat\":4}"), System.nanoTime() + 300_000_000L);
        for (int i = 0; i < 500 && !op.result.isDone(); i++) {
            f.next();
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        }
        JsonObject r = op.result.join();
        check(
                r.get("completion").getAsString().equals("unknown")
                        && !r.get("matchesRequested").getAsBoolean(),
                "unobserved insert is unknown: " + r);
        check(m.unresolved().size() == 1, "reported as unresolved for extension.restart");
        HostOperation next = f.submit(m, "markers.insert", json("{\"beat\":5}"));
        f.next();
        TransportMixFixture.error(next, "unresolved_mutation");
        check(f.transport.actions == 1, "no automatic retry and no second add");
    }

    private static void incompleteWindowBlocksWrites() {
        TransportMixFixture f = new TransportMixFixture();
        Bank bank = new Bank(f);
        bank.add("A", 1);
        MarkerModule m = module(f);
        bank.node.child("itemCount").emit(MarkerModule.WINDOW + 1);
        JsonObject list = f.call(m, "markers.list", new JsonObject());
        check(!list.get("observationsComplete").getAsBoolean(), "list says incomplete");
        HostOperation op = f.submit(m, "markers.insert", json("{\"beat\":4}"));
        f.next();
        TransportMixFixture.error(op, "unsupported_scope");
        check(f.transport.actions == 0, "no write over an unseen marker list");
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
}
