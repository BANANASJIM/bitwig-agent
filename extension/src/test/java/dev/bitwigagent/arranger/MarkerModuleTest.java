package dev.bitwigagent.arranger;

import static dev.bitwigagent.RpcAssert.*;
import static org.junit.jupiter.api.Assertions.*;

import com.bitwig.extension.controller.api.CueMarker;
import com.google.gson.*;
import dev.bitwigagent.FakeHost;
import dev.bitwigagent.HostFixture;
import dev.bitwigagent.core.HostOperation;
import dev.bitwigagent.core.RpcException;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Marker list/insert/set/delete against a simulated beat-sorted CueMarkerBank; no claim about native marker semantics. */
public final class MarkerModuleTest {

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
        final HostFixture f;
        final FakeHost node;
        final List<Mk> model = new ArrayList<>();
        int created;

        Bank(HostFixture f) {
            this.f = f;
            node = f.host.child("createArranger").child("createCueMarkerBank").items(CueMarker.class);
            for (int i = 0; i < MarkerModule.WINDOW; i++) {
                final int slot = i;
                FakeHost m = node.item(i);
                m.child("name").afterWrite = () -> {
                    model.get(slot).name = (String) m.child("name").data;
                    refresh();
                };
                m.child("position").afterWrite = () -> {
                    model.get(slot).beat = ((Number) m.child("position").data).doubleValue();
                    refresh();
                };
                m.on("deleteObject", args -> {
                    f.mutation();
                    model.remove(slot);
                    refresh();
                    return null;
                });
            }
            node.child("scrollPosition").data = 0;
            // Like the host, the add does nothing where a marker already sits at the playhead.
            f.addMarker = () -> {
                double playhead = ((Number) f.transport.child("playPosition").data).doubleValue();
                if (model.stream().anyMatch(mk -> mk.beat == playhead)) return;
                model.add(new Mk("Marker " + (++created), playhead));
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
                FakeHost m = node.item(i);
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

    private static MarkerModule module(HostFixture f) {
        return f.services.marker();
    }

    private static JsonObject json(String t) {
        return HostFixture.json(t);
    }

    private static String ref(int index, String name, double beat) {
        return "{\"kind\":\"marker\",\"index\":" + index + ",\"name\":\"" + name + "\",\"beat\":" + beat + "}";
    }

    private static String set(int index, String name, double beat, String to) {
        return "{\"target\":" + ref(index, name, beat) + ",\"name\":\"" + to + "\"}";
    }

    private static String target(int index, String name, double beat) {
        return "{\"target\":" + ref(index, name, beat) + "}";
    }

    private static JsonObject run(HostFixture f, HostOperation op) {
        for (int i = 0; i < 500 && !op.result.isDone(); i++) f.next();
        assertTrue(op.result.isDone(), "operation completed");
        return op.result.join();
    }

    private static JsonObject call(HostFixture f, MarkerModule m, String method, String params) {
        return run(f, f.submit(m, method, json(params)));
    }

    @Test
    void listsSortedWithWindow() {
        HostFixture f = new HostFixture();
        Bank bank = new Bank(f);
        bank.add("Chorus", 32);
        bank.add("Intro", 0);
        bank.add("Verse", 8);
        MarkerModule m = module(f);
        JsonObject r = f.call(m, "markers.list", new JsonObject());
        JsonArray list = r.getAsJsonArray("markers");
        assertTrue(
                list.size() == 3
                        && list.get(0)
                                .getAsJsonObject()
                                .get("name")
                                .getAsString()
                                .equals("Intro")
                        && list.get(2).getAsJsonObject().get("beat").getAsDouble() == 32
                        && list.get(1)
                                        .getAsJsonObject()
                                        .getAsJsonObject("ref")
                                        .get("index")
                                        .getAsInt()
                                == 1
                        && list.get(1)
                                        .getAsJsonObject()
                                        .getAsJsonObject("ref")
                                        .get("beat")
                                        .getAsDouble()
                                == 8,
                "sorted by beat, each row carrying its ref with the rank");
        assertTrue(
                r.get("observationsComplete").getAsBoolean()
                        && r.getAsJsonObject("window").get("capacity").getAsInt() == MarkerModule.WINDOW,
                "window reported");
        assertTrue(f.transport.actions == 0 && f.tasks.isEmpty(), "list never mutates");
    }

    @Test
    void invalidRequestsNeverSchedule() {
        HostFixture f = new HostFixture();
        new Bank(f);
        MarkerModule m = module(f);
        for (String[] c : new String[][] {
            {"markers.insert", "{}"},
            {"markers.insert", "{\"beat\":-1}"},
            {"markers.insert", "{\"beat\":1,\"name\":\"\"}"},
            {"markers.set", target(0, "A", 0)},
            {"markers.set", "{\"target\":{\"kind\":\"marker\",\"index\":0},\"name\":\"B\"}"},
            {"markers.set", set(0, "A", 0, "")},
            {"markers.delete", "{}"},
            {"markers.delete", "{\"markerIndex\":0,\"expectedName\":\"A\"}"},
            {"markers.delete", "{\"target\":" + ref(0, "A", 0) + ",\"extra\":1}"},
            {"markers.list", "{\"x\":1}"}
        }) {
            assertRpcError("invalid_params", f.submit(m, c[0], json(c[1])));
            assertTrue(f.tasks.isEmpty(), "invalid marker request not scheduled: " + c[1]);
        }
    }

    @Test
    void insertNamesAndMovesTheNewMarker() {
        HostFixture f = new HostFixture();
        Bank bank = new Bank(f);
        bank.add("Intro", 0);
        bank.add("Outro", 64);
        MarkerModule m = module(f);
        f.transport.child("playPosition").data = 16.0;
        JsonObject r = call(f, m, "markers.insert", "{\"beat\":8,\"name\":\"Verse\"}");
        assertTrue(r.get("outcome").getAsString().equals("applied"), "insert observed: " + r);
        assertTrue(r.get("verification").getAsString().equals("api_readback"), "insert verified: " + r);
        assertTrue(
                bank.view().equals(List.of("Intro@0.0", "Verse@8.0", "Outro@64.0")),
                "marker named and moved to the beat: " + bank.view());
        assertTrue(
                f.transport.calls("addCueMarkerAtPlaybackPosition") == 1
                        && f.transport.child("playPosition").data.equals(16.0),
                "one native add; playhead put back");
        assertTrue(
                r.getAsJsonObject("ref").get("name").getAsString().equals("Verse")
                        && r.getAsJsonObject("ref").get("index").getAsInt() == 1
                        && r.getAsJsonObject("ref").get("beat").getAsDouble() == 8,
                "result carries the new marker's ref");
        assertTrue(r.getAsJsonArray("markers").size() == 3, "result carries the observed list");
        assertTrue(f.services.holds.list().isEmpty(), "observed insert leaves nothing unresolved");
    }

    @Test
    void insertBesideAMarkerAtThePlayhead() {
        HostFixture f = new HostFixture();
        Bank bank = new Bank(f);
        MarkerModule m = module(f);
        call(f, m, "markers.insert", "{\"beat\":0,\"name\":\"a\"}");
        JsonObject r = call(f, m, "markers.insert", "{\"beat\":16,\"name\":\"b\"}");
        assertTrue(r.get("outcome").getAsString().equals("applied"), "insert observed: " + r);
        assertTrue(bank.view().equals(List.of("a@0.0", "b@16.0")), "both markers present: " + bank.view());
        assertTrue(f.transport.child("playPosition").data.equals(0.0), "playhead put back");
        assertTrue(f.services.holds.list().isEmpty(), "nothing left unresolved");
    }

    @Test
    void insertWithoutNameKeepsHostName() {
        HostFixture f = new HostFixture();
        Bank bank = new Bank(f);
        MarkerModule m = module(f);
        JsonObject r = call(f, m, "markers.insert", "{\"beat\":4}");
        assertTrue(
                r.get("outcome").getAsString().equals("applied") && bank.view().equals(List.of("Marker 1@4.0")),
                "host-assigned name kept: " + bank.view());
    }

    @Test
    void insertRefusals() {
        HostFixture f = new HostFixture();
        Bank bank = new Bank(f);
        bank.add("A", 8);
        MarkerModule m = module(f);
        HostOperation dup = f.submit(m, "markers.insert", json("{\"beat\":8}"));
        f.next();
        assertRpcError("target_exists", dup);
        f.transport.child("isPlaying").emit(true);
        HostOperation playing = f.submit(m, "markers.insert", json("{\"beat\":9}"));
        f.next();
        assertRpcError("unsafe_state", playing);
        f.transport.child("isPlaying").emit(false);
        f.transport.child("isArrangerRecordEnabled").emit(true);
        HostOperation recording = f.submit(m, "markers.insert", json("{\"beat\":9}"));
        f.next();
        assertRpcError("unsafe_state", recording);
        assertTrue(
                f.transport.actions == 0 && bank.view().equals(List.of("A@8.0")), "refused inserts dispatched nothing");
    }

    @Test
    void setChecksTheRefAndVerifies() {
        HostFixture f = new HostFixture();
        Bank bank = new Bank(f);
        bank.add("Intro", 0);
        bank.add("Verse", 8);
        MarkerModule m = module(f);
        HostOperation renamed = f.submit(m, "markers.set", json(set(1, "Chorus", 8, "X")));
        f.next();
        RpcException changed = assertRpcError("target_changed", renamed);
        assertEquals("Verse", changed.current.get("name").getAsString(), "the refusal names the marker now there");
        HostOperation wrongBeat = f.submit(m, "markers.set", json(set(1, "Verse", 9, "X")));
        f.next();
        assertEquals(
                8,
                assertRpcError("target_changed", wrongBeat).current.get("beat").getAsDouble());
        HostOperation beyond = f.submit(m, "markers.set", json(set(5, "Verse", 8, "X")));
        f.next();
        assertEquals("markers.list", assertRpcError("target_not_found", beyond).next);
        assertTrue(bank.view().equals(List.of("Intro@0.0", "Verse@8.0")), "refusals changed nothing");
        JsonObject r = call(f, m, "markers.set", set(1, "Verse", 8, "Verse 1"));
        assertTrue(
                r.get("outcome").getAsString().equals("applied")
                        && bank.view().equals(List.of("Intro@0.0", "Verse 1@8.0"))
                        && r.getAsJsonObject("ref").get("name").getAsString().equals("Verse 1"),
                "renamed, with the marker's new ref: " + bank.view());
        JsonObject same = call(f, m, "markers.set", set(1, "Verse 1", 8, "Verse 1"));
        assertTrue(
                same.get("outcome").getAsString().equals("already_matched")
                        && f.services.holds.list().isEmpty(),
                "same name is not rewritten");
    }

    @Test
    void aMovedMarkerMakesTheOldRefStale() {
        HostFixture f = new HostFixture();
        Bank bank = new Bank(f);
        bank.add("Intro", 0);
        bank.add("Verse", 8);
        MarkerModule m = module(f);
        bank.model.get(1).beat = 4;
        bank.refresh();
        for (String[] c :
                new String[][] {{"markers.set", set(1, "Verse", 8, "X")}, {"markers.delete", target(1, "Verse", 8)}}) {
            HostOperation op = f.submit(m, c[0], json(c[1]));
            f.next();
            JsonObject current = assertRpcError("target_changed", op).current;
            assertTrue(
                    current.get("name").getAsString().equals("Verse")
                            && current.get("beat").getAsDouble() == 4,
                    c[0] + " names where the marker is now");
        }
        assertTrue(bank.view().equals(List.of("Intro@0.0", "Verse@4.0")), "nothing was written");
    }

    @Test
    void deleteChecksTheRefAndVerifies() {
        HostFixture f = new HostFixture();
        Bank bank = new Bank(f);
        bank.add("Intro", 0);
        bank.add("Verse", 8);
        bank.add("Chorus", 16);
        MarkerModule m = module(f);
        HostOperation stale = f.submit(m, "markers.delete", json(target(0, "Verse", 8)));
        f.next();
        assertRpcError("target_changed", stale);
        JsonObject r = call(f, m, "markers.delete", target(1, "Verse", 8));
        assertTrue(
                r.get("outcome").getAsString().equals("applied")
                        && bank.view().equals(List.of("Intro@0.0", "Chorus@16.0"))
                        && !r.has("ref"),
                "deleted, and a delete returns no ref: " + bank.view());
    }

    @Test
    void unobservedInsertQuarantines() {
        HostFixture f = new HostFixture();
        Bank bank = new Bank(f);
        f.addMarker = () -> {}; // the host accepts the call but never shows a marker
        MarkerModule m = module(f);
        HostOperation op = f.submit(m, "markers.insert", json("{\"beat\":4}"), f.tasks.after(300));
        while (!op.result.isDone()) f.next();
        JsonObject r = op.result.join();
        assertTrue(
                r.get("outcome").getAsString().equals("unknown")
                        && r.get("reason").getAsString().equals("not_observed")
                        && r.get("next").getAsString().contains("ack markers.unresolved"),
                "unobserved insert is unknown: " + r);
        assertTrue(f.services.holds.list().size() == 1, "reported as unresolved until acked");
        HostOperation next = f.submit(m, "markers.insert", json("{\"beat\":5}"));
        f.next();
        assertTrue(
                assertRpcError("unresolved", next).next.contains("ack markers.unresolved"),
                "the refusal names the hold to ack");
        assertTrue(
                f.transport.calls("addCueMarkerAtPlaybackPosition") == 1
                        && f.transport.child("playPosition").data.equals(0.0),
                "no automatic retry and no second add; playhead put back");
    }

    @Test
    void playheadNeverAtTheBeatAddsNothing() {
        HostFixture f = new HostFixture();
        Bank bank = new Bank(f);
        f.transport.on("setPosition", args -> null); // the host never reports the moved playhead
        MarkerModule m = module(f);
        HostOperation op = f.submit(m, "markers.insert", json("{\"beat\":4}"), f.tasks.after(300));
        while (!op.result.isDone()) f.next();
        JsonObject r = op.result.join();
        assertTrue(
                r.get("outcome").getAsString().equals("unknown")
                        && r.get("reason").getAsString().equals("not_observed")
                        && f.services.holds.list().size() == 1,
                "unobserved playhead move is unknown and held: " + r);
        assertTrue(
                f.transport.calls("addCueMarkerAtPlaybackPosition") == 0
                        && f.transport.calls("setPosition") == 2
                        && bank.view().isEmpty(),
                "no add without the playhead at the beat; the playhead is put back");
    }

    @Test
    void playbackStartingMidInsertStopsBeforeTheAdd() {
        HostFixture f = new HostFixture();
        Bank bank = new Bank(f);
        f.transport.child("playPosition").data = 2.0;
        List<Object> seeks = new ArrayList<>();
        // Playback starts right after the seek, before the moved playhead is reported.
        f.transport.on("setPosition", args -> {
            seeks.add(args[0]);
            if (seeks.size() == 1) f.transport.child("isPlaying").emit(true);
            return null;
        });
        MarkerModule m = module(f);
        JsonObject r = call(f, m, "markers.insert", "{\"beat\":8}");
        assertTrue(
                r.get("outcome").getAsString().equals("unknown")
                        && r.get("reason").getAsString().equals("unsafe_state"),
                "playback mid-insert is reported, not ignored: " + r);
        assertTrue(
                f.transport.calls("addCueMarkerAtPlaybackPosition") == 0
                        && bank.view().isEmpty()
                        && seeks.equals(List.of(8.0, 2.0)),
                "no add; the playhead is sent back: " + seeks);
    }

    @Test
    void unobservedDeleteIsUnknownAndHolds() {
        HostFixture f = new HostFixture();
        Bank bank = new Bank(f);
        bank.add("Intro", 0);
        for (int i = 0; i < MarkerModule.WINDOW; i++)
            bank.node.item(i).on("deleteObject", args -> {
                f.mutation();
                return null;
            });
        MarkerModule m = module(f);
        HostOperation op = f.submit(m, "markers.delete", json(target(0, "Intro", 0)), f.tasks.after(300));
        while (!op.result.isDone()) f.next();
        JsonObject r = op.result.join();
        assertTrue(
                r.get("outcome").getAsString().equals("unknown")
                        && r.get("reason").getAsString().equals("not_observed")
                        && bank.view().equals(List.of("Intro@0.0"))
                        && f.services.holds.list().size() == 1,
                "an unobserved delete is unknown and holds markers: " + r);
    }

    @Test
    void unknownMethodIsRefused() {
        assertRpcError("unknown_method", () -> MarkerModule.validateParams("markers.bogus", new JsonObject()));
    }

    @Test
    void incompleteWindowBlocksWrites() {
        HostFixture f = new HostFixture();
        Bank bank = new Bank(f);
        bank.add("A", 1);
        MarkerModule m = module(f);
        bank.node.child("itemCount").emit(MarkerModule.WINDOW + 1);
        JsonObject list = f.call(m, "markers.list", new JsonObject());
        assertTrue(!list.get("observationsComplete").getAsBoolean(), "list says incomplete");
        HostOperation op = f.submit(m, "markers.insert", json("{\"beat\":4}"));
        f.next();
        assertRpcError("unsupported_scope", op);
        assertTrue(f.transport.actions == 0, "no write over an unseen marker list");
    }
}
