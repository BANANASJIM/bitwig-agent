package dev.bitwigagent.mix;

import static dev.bitwigagent.RpcAssert.*;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import dev.bitwigagent.FakeHost;
import dev.bitwigagent.HostFixture;
import dev.bitwigagent.core.HostOperation;
import dev.bitwigagent.core.Refs;
import dev.bitwigagent.core.RpcException;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Behavioral fake-host tests; no claim about audio, routing, or native factory completion. */
public final class MixModuleTest {

    private static JsonObject set(String id, String json) {
        JsonObject p = HostFixture.json(json);
        p.add("target", HostFixture.track(id));
        return p;
    }

    private static String outcome(JsonObject result, String field) {
        return result.getAsJsonObject("fields")
                .getAsJsonObject(field)
                .get("outcome")
                .getAsString();
    }

    @Test
    void readsReturnRefsAndValueUnits() {
        HostFixture f = new HostFixture();
        MixModule module = f.services.mix();
        JsonObject result = f.call(module, "tracks.get", HostFixture.targeting(HostFixture.A));
        assertEquals(HostFixture.track(HostFixture.A), result.getAsJsonObject("ref"));
        JsonObject volume = result.getAsJsonObject("volume");
        assertEquals(.5, volume.get("value").getAsDouble());
        assertEquals("-12 dB", volume.get("display").getAsString());
        assertEquals(Refs.parameter(HostFixture.track(HostFixture.A), "volume", null), volume.getAsJsonObject("ref"));
        assertEquals("0.00 %", result.getAsJsonObject("pan").get("display").getAsString());
        assertFalse(result.has("units") || result.has("volumeNormalized") || result.has("volumeDisplay"));
        JsonObject send = result.getAsJsonArray("sends").get(0).getAsJsonObject();
        assertEquals(Refs.send(HostFixture.track(HostFixture.A), 0, "Reverb"), send.getAsJsonObject("ref"));
        assertEquals(.25, send.getAsJsonObject("value").get("value").getAsDouble());
        assertEquals("-18 dB", send.getAsJsonObject("value").get("display").getAsString());
        assertTrue(send.get("enabled").getAsBoolean() && !send.get("preFader").getAsBoolean());
        assertEquals(8, result.getAsJsonObject("sendWindow").get("capacity").getAsInt());
        assertEquals(1, f.host.calls("createTrackBank"), "mix never creates a duplicate track bank");
    }

    @Test
    void briefReportsSelectionOnlyWhenObserved() {
        HostFixture f = new HostFixture();
        MixModule module = f.services.mix();
        f.tracks[0].child("selected").emit(true);
        f.tracks[0].child("exists").emit(false);
        f.tracks[0].child("exists").emit(true);
        module.flush();
        JsonObject before = module.brief(0);
        assertTrue(
                before.getAsJsonObject("ref").equals(HostFixture.track(HostFixture.A))
                        && before.get("name").getAsString().equals("Track 0")
                        && before.get("type").getAsString().equals("Audio")
                        && before.get("index").getAsInt() == 0,
                "brief carries identity from observed values");
        assertTrue(
                before.get("selected").isJsonNull(),
                "selection without an observed callback is null, not guessed false");
        f.tracks[0].child("selected").emit(true);
        f.tracks[1].child("selected").emit(false);
        assertTrue(
                module.brief(0).get("selected").getAsBoolean()
                        && !module.brief(1).get("selected").getAsBoolean(),
                "observed selection is reported");
        assertTrue(f.tracks[0].actions == 0 && f.tasks.isEmpty(), "brief never selects or schedules");
    }

    @Test
    void invalidRequestsNeverSchedule() {
        HostFixture f = new HostFixture();
        MixModule module = f.services.mix();
        for (String text : List.of(
                "{\"volume\":-0.1}",
                "{\"pan\":1.1}",
                "{\"pan\":{\"label\":\"C\"}}",
                "{\"volume\":{\"display\":\"\"}}",
                "{\"mute\":1}",
                "{\"arm\":null}",
                "{\"name\":\"\"}",
                "{\"extra\":true}",
                "{\"mute\":true,\"expect\":{\"mute\":1}}",
                "{}")) {
            assertRpcError("invalid_params", f.submit(module, "tracks.set", set(HostFixture.A, text)));
            assertTrue(f.tasks.isEmpty(), "invalid mix field rejected before scheduling: " + text);
        }
        JsonObject legacy = HostFixture.json("{\"trackId\":\"" + HostFixture.A + "\",\"mute\":true}");
        assertRpcError("invalid_params", f.submit(module, "tracks.set", legacy));
        JsonObject unnamed = HostFixture.json("{\"target\":{\"kind\":\"track\",\"id\":\"" + HostFixture.A + "\"}}");
        unnamed.addProperty("mute", true);
        assertRpcError("invalid_params", f.submit(module, "tracks.set", unnamed));
        assertTrue(f.tasks.isEmpty());
    }

    @Test
    void writesOnlyTheRefAndFieldsAndRoundTripsReadValues() {
        HostFixture f = new HostFixture();
        MixModule module = f.services.mix();
        JsonObject p = set(HostFixture.B, "{\"volume\":0.75,\"mute\":true}");
        JsonObject result = f.call(module, "tracks.set", p);
        assertTrue(result.get("outcome").getAsString().matches("applied|already_matched"), "requested mix observed");
        assertEquals(HostFixture.track(HostFixture.B), result.getAsJsonObject("ref"), "R5: the written ref");
        assertTrue(
                f.tracks[1].child("volume").child("value").writes == 1 && f.tracks[1].child("mute").writes == 1,
                "only track B receives writes");
        assertTrue(
                f.tracks[0].child("volume").child("value").writes == 0 && f.tracks[0].child("mute").writes == 0,
                "track A untouched");
        for (String key : List.of("name", "pan", "solo", "arm"))
            assertTrue(f.tracks[1].child(key).writes == 0, "omitted field unchanged: " + key);
        assertEquals("already_matched", outcome(f.call(module, "tracks.set", p), "mute"), "idempotent");
        assertEquals(1, f.tracks[1].child("mute").writes, "no unnecessary second mute write");
        JsonObject read = f.call(module, "tracks.get", HostFixture.targeting(HostFixture.B));
        JsonObject back = HostFixture.targeting(HostFixture.B);
        back.add("volume", read.getAsJsonObject("volume").get("value"));
        back.add("pan", read.getAsJsonObject("pan").get("value"));
        back.add("name", read.get("name"));
        back.add("color", read.get("color"));
        back.add("monitorMode", read.getAsJsonObject("monitorMode").get("value"));
        back.add("crossFadeMode", read.getAsJsonObject("crossFadeMode").get("value"));
        back.add("activated", read.get("activated"));
        JsonObject roundTrip = f.call(module, "tracks.set", back);
        assertEquals("already_matched", roundTrip.get("outcome").getAsString(), "a read value writes back unchanged");
        HostOperation absent = f.submit(
                module,
                "tracks.set",
                HostFixture.json(
                        "{\"target\":{\"kind\":\"track\",\"id\":\"aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee\",\"name\":\"x\"},\"mute\":true}"));
        f.next();
        RpcException gone = assertRpcError("target_not_found", absent);
        assertEquals("tracks.list", gone.next);
        assertTrue(f.tracks[0].child("mute").writes == 0, "absent UUID never falls back to the selected track");
    }

    @Test
    void writingTheValueAFieldAlreadyHasIsAlreadyMatchedEvenWithoutAnyCallback() {
        HostFixture f = new HostFixture();
        for (String field : List.of("volume", "pan")) {
            FakeHost value = f.tracks[1].child(field).child("value");
            value.notifyInitial = false;
            value.notifyWrites = false;
        }
        MixModule module = f.services.mix();
        for (String field : List.of("volume", "pan")) {
            HostOperation op = f.submit(module, "tracks.set", set(HostFixture.B, "{\"" + field + "\":0.5}"));
            for (int i = 0; i < 50 && !op.result.isDone() && !f.tasks.isEmpty(); i++) f.next();
            assertTrue(op.result.isDone(), field + " settles");
            JsonObject result = op.result.join();
            assertEquals("already_matched", result.get("outcome").getAsString(), field + ": " + result);
        }
    }

    @Test
    void aSendReadValueWritesBackUnchanged() {
        HostFixture f = new HostFixture();
        MixModule module = f.services.mix();
        JsonObject row = f.call(module, "tracks.get", HostFixture.targeting(HostFixture.A))
                .getAsJsonArray("sends")
                .get(0)
                .getAsJsonObject();
        JsonObject back = new JsonObject();
        back.add("target", row.get("ref"));
        back.add("value", row.getAsJsonObject("value").get("value"));
        back.add("enabled", row.get("enabled"));
        JsonObject result = f.call(module, "tracks.sends.set", back);
        assertEquals("already_matched", result.get("outcome").getAsString());
        assertEquals(0, f.tracks[0].child("sendBank").item(0).child("value").writes);
    }

    @Test
    void aRenamedTrackIsStaleAsTheTargetButNotAsAnAncestor() {
        HostFixture f = new HostFixture();
        MixModule module = f.services.mix();
        f.tracks[1].child("name").emit("Bass");
        HostOperation stale = f.submit(module, "tracks.set", set(HostFixture.B, "{\"mute\":true}"));
        f.next();
        RpcException changed = assertRpcError("target_changed", stale);
        assertEquals(Refs.track(HostFixture.B, "Bass"), changed.current, "the ref found there now");
        assertEquals(0, f.tracks[1].child("mute").writes);
        JsonObject read = f.call(module, "tracks.get", HostFixture.targeting(HostFixture.B));
        assertEquals(
                Refs.track(HostFixture.B, "Bass"), read.getAsJsonObject("ref"), "a read does not compare the name");
        JsonObject current = HostFixture.json("{\"mute\":true}");
        current.add("target", changed.current);
        assertTrue(f.call(module, "tracks.set", current)
                .get("outcome")
                .getAsString()
                .matches("applied|already.*"));
        JsonObject sendRef = f.call(module, "tracks.get", HostFixture.targeting(HostFixture.A))
                .getAsJsonArray("sends")
                .get(0)
                .getAsJsonObject()
                .getAsJsonObject("ref");
        f.tracks[0].child("name").emit("Lead");
        JsonObject send = HostFixture.json("{\"enabled\":false}");
        send.add("target", sendRef);
        assertTrue(
                f.call(module, "tracks.sends.set", send)
                        .get("outcome")
                        .getAsString()
                        .matches("applied|already.*"),
                "a send ref survives its track's rename");
    }

    @Test
    void expectComparesBareValuesBeforeAnyWrite() {
        HostFixture f = new HostFixture();
        MixModule module = f.services.mix();
        HostOperation mismatch =
                f.submit(module, "tracks.set", set(HostFixture.B, "{\"pan\":0.2,\"expect\":{\"pan\":0.9}}"));
        f.next();
        RpcException error = assertRpcError("target_changed", mismatch);
        assertEquals(.5, error.observed.get("pan").getAsDouble());
        assertEquals(0, f.tracks[1].child("pan").child("value").writes, "nothing written on a mismatch");
        JsonObject matched = f.call(
                module,
                "tracks.set",
                set(
                        HostFixture.B,
                        "{\"pan\":0.2,\"color\":\"#FF0000\",\"expect\":{\"pan\":0.5,\"color\":\"#000000\"}}"));
        assertTrue(matched.get("outcome").getAsString().matches("applied|already_matched"));
        assertEquals(1, f.tracks[1].child("pan").child("value").writes);
    }

    @Test
    void colorActivatedMonitorAndCrossFadeAreReadAndWritten() {
        HostFixture f = new HostFixture();
        MixModule module = f.services.mix();
        JsonObject read = f.call(module, "tracks.get", HostFixture.targeting(HostFixture.B));
        assertTrue(
                read.get("color").getAsString().equals("#000000")
                        && read.get("activated").getAsBoolean()
                        && read.getAsJsonObject("monitorMode")
                                .get("value")
                                .getAsString()
                                .equals("AUTO")
                        && read.getAsJsonObject("crossFadeMode")
                                .get("value")
                                .getAsString()
                                .equals("AB"),
                "new track fields are read");
        assertTrue(
                read.getAsJsonObject("monitorMode").getAsJsonArray("options").size() == 3
                        && read.getAsJsonObject("crossFadeMode")
                                .getAsJsonArray("options")
                                .get(0)
                                .getAsString()
                                .equals("A"),
                "enum options come from the host");
        for (String text : List.of(
                "{\"color\":\"ff0000\"}",
                "{\"color\":\"#ff00\"}",
                "{\"color\":1}",
                "{\"activated\":\"yes\"}",
                "{\"monitorMode\":3}",
                "{\"crossFadeMode\":\"\"}")) {
            assertRpcError("invalid_params", f.submit(module, "tracks.set", set(HostFixture.B, text)));
            assertTrue(f.tasks.isEmpty(), "invalid field rejected before scheduling: " + text);
        }
        HostOperation refused =
                f.submit(module, "tracks.set", set(HostFixture.B, "{\"mute\":true,\"monitorMode\":\"LOUD\"}"));
        f.next();
        assertTrue(!assertRpcError("invalid_params", refused).unknown, "option the host does not list is refused");
        assertTrue(
                f.tracks[1].child("monitorMode").writes == 0 && f.tracks[1].child("mute").writes == 0,
                "nothing written when an option is refused");
        JsonObject p = set(
                HostFixture.B,
                "{\"color\":\"#FF8800\",\"activated\":false,\"monitorMode\":\"ON\",\"crossFadeMode\":\"B\"}");
        JsonObject result = f.call(module, "tracks.set", p);
        assertTrue(result.get("outcome").getAsString().matches("applied|already_matched"), "every field is applied");
        assertEquals("applied", outcome(result, "color"), "color verified by observer");
        assertEquals(
                "#ff8800",
                result.getAsJsonObject("fields")
                        .getAsJsonObject("color")
                        .get("requested")
                        .getAsString());
        assertTrue(
                f.tracks[1].child("color").writes == 1
                        && f.tracks[0].child("color").writes == 0
                        && f.tracks[0].child("monitorMode").writes == 0,
                "only track B is written");
        JsonObject again = f.call(module, "tracks.set", p);
        assertTrue(
                outcome(again, "monitorMode").equals("already_matched")
                        && f.tracks[1].child("monitorMode").writes == 1
                        && f.tracks[1].child("isActivated").writes == 1,
                "matching values are not rewritten");
        f.tracks[1].child("crossFadeMode").notifyWrites = false;
        HostOperation op =
                f.submit(module, "tracks.set", set(HostFixture.B, "{\"crossFadeMode\":\"A\"}"), f.tasks.after(300));
        while (!op.result.isDone()) f.next();
        assertTrue(
                !op.result.join().get("outcome").getAsString().matches("applied|already_matched"),
                "an unobserved enum write is not reported as done");
    }

    @Test
    void colorReadbackWithinOneStepAndOutOfListEnumsRoundTrip() {
        HostFixture f = new HostFixture();
        MixModule module = f.services.mix();
        FakeHost color = f.tracks[1].child("color");
        color.afterWrite = () -> color.emit(new float[] {1f, 0x57 / 255f, 4 / 255f});
        JsonObject result = f.call(module, "tracks.set", set(HostFixture.B, "{\"color\":\"#ff5705\"}"));
        assertTrue(
                result.get("outcome").getAsString().matches("applied|already_matched")
                        && result.getAsJsonObject("fields")
                                .getAsJsonObject("color")
                                .get("observed")
                                .getAsString()
                                .equals("#ff5704"),
                "one 8-bit step of float rounding still matches and the observed hex is reported");
        color.afterWrite = () -> color.emit(new float[] {1f, 0x57 / 255f, 4 / 255f});
        HostOperation farOp =
                f.submit(module, "tracks.set", set(HostFixture.B, "{\"color\":\"#ff5a05\"}"), f.tasks.after(300));
        while (!farOp.result.isDone()) f.next();
        assertTrue(
                !farOp.result.join().get("outcome").getAsString().matches("applied|already_matched"),
                "a larger difference is a mismatch");
        f.tracks[1].child("monitorMode").emit("STORED");
        JsonObject again = f.call(module, "tracks.set", set(HostFixture.B, "{\"monitorMode\":\"STORED\"}"));
        assertTrue(
                outcome(again, "monitorMode").equals("already_matched") && f.tracks[1].child("monitorMode").writes == 0,
                "the currently observed out-of-list value can be written back");
        HostOperation refused = f.submit(module, "tracks.set", set(HostFixture.B, "{\"monitorMode\":\"LOUD\"}"));
        f.next();
        assertRpcError("invalid_params", refused);
    }

    @Test
    void selectionUsesTheRefNotCurrentUi() {
        HostFixture f = new HostFixture();
        MixModule module = f.services.mix();
        f.tracks[0].child("selected").emit(true);
        JsonObject selected = f.call(module, "tracks.select", HostFixture.targeting(HostFixture.B));
        assertEquals("sent", selected.get("outcome").getAsString());
        assertEquals(HostFixture.track(HostFixture.B), selected.getAsJsonObject("ref"));
        assertTrue(f.tracks[1].actions == 1 && f.tracks[0].actions == 0, "select operates on the requested proxy");
        JsonObject again = f.call(module, "tracks.select", HostFixture.targeting(HostFixture.B));
        assertTrue(
                f.tracks[1].actions == 2 && again.get("outcome").getAsString().equals("sent"),
                "an already-selected snapshot does not suppress an explicit editor-navigation command");
    }

    @Test
    void selectionWithoutReplayedCallbackIsDispatchOnly() {
        HostFixture f = new HostFixture();
        MixModule module = f.services.mix();
        f.tracks[0].child("selected").emit(true);
        f.tracks[0].child("exists").emit(false);
        f.tracks[0].child("exists").emit(true);
        module.flush();
        f.tracks[0].child("selected").notifyWrites = false;
        HostOperation op = f.submit(module, "tracks.select", HostFixture.targeting(HostFixture.A));
        f.next();
        assertTrue(op.result.isDone(), "selection navigation must not wait for an optional legacy callback");
        JsonObject result = op.result.join();
        assertEquals("sent", result.get("outcome").getAsString());
        assertFalse(result.has("fields"), "UI navigation does not pretend to be a verified mix setter");
        assertTrue(
                f.tracks[0].actions == 1 && f.tasks.isEmpty(),
                "selection dispatches once without deferred retries or callback polling");
    }

    @Test
    void selectionFailsFastWhileLauncherLeaseHeld() {
        HostFixture f = new HostFixture();
        MixModule module = f.services.mix();
        HostOperation holder = new HostOperation(Long.MAX_VALUE);
        f.services.selection.acquire(holder);
        HostOperation op = f.submit(module, "tracks.select", HostFixture.targeting(HostFixture.B));
        f.next();
        RpcException busy = assertRpcError("busy", op);
        assertTrue(!busy.unknown && f.tracks[1].actions == 0, "held lease rejects select before dispatch");
        assertTrue(f.tasks.isEmpty(), "rejected select is not queued");
        holder.result.complete(new JsonObject());
        assertTrue(
                f.call(module, "tracks.select", HostFixture.targeting(HostFixture.B))
                                .get("outcome")
                                .getAsString()
                                .equals("sent")
                        && f.tracks[1].actions == 1,
                "released lease allows select");
    }

    @Test
    void selectionRespectsOperationAndIdentityGuards() {
        HostFixture f = new HostFixture();
        MixModule module = f.services.mix();
        JsonObject p = HostFixture.targeting(HostFixture.A);
        HostOperation expired = f.submit(module, "tracks.select", p, f.tasks.now() - 1);
        f.next();
        assertTrue(!assertRpcError("deadline", expired).unknown, "expired selection is rejected before dispatch");
        HostOperation cancelled = f.submit(module, "tracks.select", p);
        cancelled.result.cancel(false);
        f.next();
        f.tracks[0].child("exists").emit(false);
        f.tracks[0].child("exists").emit(true);
        HostOperation unready = f.submit(module, "tracks.select", p);
        f.next();
        assertTrue(
                !assertRpcError("not_ready", unready).unknown && f.tracks[0].actions == 0,
                "cancelled or rebound-before-flush selection cannot reach the host");
        module.flush();
        f.tracks[0].child("selected").afterWrite =
                () -> f.tracks[0].child("channelId").emit(HostFixture.C);
        HostOperation shifted = f.submit(module, "tracks.select", p);
        f.next();
        assertTrue(
                assertUnknown("target_changed", shifted) != null && f.tracks[0].actions == 1,
                "identity loss after dispatch remains unknown, not an accepted dispatch-only receipt");
    }

    @Test
    void requiresMatchingFreshTargetObservation() {
        HostFixture f = new HostFixture();
        MixModule module = f.services.mix();
        FakeHost volume = f.tracks[1].child("volume").child("value");
        volume.notifyWrites = false;
        HostOperation op = f.submit(module, "tracks.set", set(HostFixture.B, "{\"volume\":0.75}"));
        f.next();
        assertTrue(!op.result.isDone(), "changed getter without callback cannot verify write");
        f.tracks[0].child("volume").child("value").emit(.75);
        f.next();
        assertTrue(!op.result.isDone(), "another track's callback cannot verify target");
        volume.emit(.6);
        f.next();
        assertTrue(!op.result.isDone(), "fresh mismatch cannot verify target");
        volume.emit(.75);
        f.next();
        assertTrue(
                op.result.join().get("outcome").getAsString().matches("applied|already_matched") && volume.writes == 1,
                "fresh matching callback verifies without replay");
    }

    @Test
    void targetChangePreventsRemainingWrites() {
        HostFixture f = new HostFixture();
        MixModule module = f.services.mix();
        f.tracks[0].child("name").afterWrite =
                () -> f.tracks[0].child("channelId").emit(HostFixture.C);
        JsonObject result = f.call(module, "tracks.set", set(HostFixture.A, "{\"name\":\"Renamed\",\"volume\":0.8}"));
        assertEquals("unknown", result.get("outcome").getAsString(), "partial mutation with shifted target is unknown");
        assertEquals("target_changed", result.get("reason").getAsString());
        assertFalse(result.has("ref"), "no ref of a target that is no longer there");
        assertEquals(0, f.tracks[0].child("volume").child("value").writes, "the replacement never receives the volume");
        assertTrue(
                result.getAsJsonObject("fields")
                        .getAsJsonObject("name")
                        .get("observed")
                        .isJsonNull(),
                "replacement's state not misreported as target readback");
    }

    @Test
    void expiredAndCancelledWritesAreNotApplied() {
        HostFixture f = new HostFixture();
        MixModule module = f.services.mix();
        JsonObject p = set(HostFixture.A, "{\"arm\":true}");
        HostOperation expired = f.submit(module, "tracks.set", p, f.tasks.now() - 1);
        f.next();
        assertTrue(!assertRpcError("deadline", expired).unknown, "expired target setter not applied");
        HostOperation cancelled = f.submit(module, "tracks.set", p);
        cancelled.result.cancel(false);
        f.next();
        assertTrue(f.tracks[0].child("arm").writes == 0, "queued callbacks after deadline/cancel cannot arm");
        HostOperation during = f.submit(module, "tracks.set", set(HostFixture.A, "{\"name\":\"First\",\"arm\":true}"));
        f.tracks[0].child("name").afterWrite = () -> during.result.cancel(false);
        f.next();
        assertTrue(
                during.result.isCancelled() && f.tracks[0].child("name").writes == 1,
                "cancellation can occur after the first mutation");
        assertTrue(f.tracks[0].child("arm").writes == 0, "per-field cancellation guard prevents late arming");
    }

    @Test
    void replacementDoesNotReuseReadiness() {
        for (boolean replaceUuid : new boolean[] {true, false}) {
            HostFixture f = new HostFixture();
            MixModule module = f.services.mix();
            f.tracks[0].child("mute").emit(true);
            f.tracks[0].child("selected").emit(true);
            String id = replaceUuid ? HostFixture.C : HostFixture.A;
            if (replaceUuid) f.tracks[0].child("channelId").emit(id);
            else {
                f.tracks[0].child("exists").emit(false);
                f.tracks[0].child("exists").emit(true);
            }
            JsonObject target = Refs.track(id, "Track 0");
            JsonObject read = f.call(module, "tracks.get", params(target));
            assertTrue(
                    read.get("mute").isJsonNull() && read.get("selected").isJsonNull(),
                    "replacement cannot inherit previous identity's mix/selection observations");
            assertFalse(read.get("observationsComplete").getAsBoolean(), "partial snapshot declares missing readiness");
            assertTrue(
                    read.getAsJsonObject("volume").get("display") == null
                            && read.get("sendWindow").isJsonNull(),
                    "replacement cannot inherit display/send metadata");
            JsonObject pendingWrite = params(target);
            pendingWrite.addProperty("mute", true);
            HostOperation blocked = f.submit(module, "tracks.set", pendingWrite);
            f.next();
            assertRpcError("not_ready", blocked);
            assertTrue(f.tracks[0].child("mute").writes == 0, "no mutation before the actual host flush boundary");
            module.flush();
            JsonObject baseline = f.call(module, "tracks.get", params(target));
            assertTrue(
                    baseline.get("mute").getAsBoolean() && baseline.get("index").getAsInt() == 0,
                    "continuous unchanged proxy values become readable after flush without callback replay");
            assertTrue(
                    baseline.get("selected").isJsonNull(),
                    "legacy selection callback is not fabricated from predecessor state");
            f.tracks[0].child("mute").notifyWrites = false;
            HostOperation op = f.submit(module, "tracks.set", pendingWrite);
            f.next();
            assertTrue(
                    f.tracks[0].child("mute").writes == 1,
                    "unready matching getter must dispatch rather than falsely skip as already_at_value");
            assertEquals(
                    "already_matched",
                    op.result.join().get("outcome").getAsString(),
                    "the equal write raises no callback; the readable getter verifies it without a replay");
            JsonObject selected = f.call(module, "tracks.select", params(target));
            assertTrue(
                    f.tracks[0].actions == 1
                            && selected.get("outcome").getAsString().equals("sent"),
                    "rebound selection dispatches explicitly without pretending to verify UI completion");
        }
    }

    private static JsonObject params(JsonObject target) {
        JsonObject p = new JsonObject();
        p.add("target", target);
        return p;
    }

    @Test
    void firstIdentityObservationPreservesStartupValues() {
        HostFixture f = new HostFixture();
        f.tracks[0].child("channelId").notifyInitial = false;
        f.tracks[0].child("exists").notifyInitial = false;
        MixModule module = f.services.mix();
        f.tracks[0].child("channelId").emit(HostFixture.A);
        f.tracks[0].child("exists").emit(true);
        JsonObject read = f.call(module, "tracks.get", HostFixture.targeting(HostFixture.A));
        assertTrue(
                read.getAsJsonObject("volume").get("value").getAsDouble() == .5
                        && read.get("observationsComplete").getAsBoolean(),
                "first identity callback after fields is not treated as replacement");
    }

    @Test
    void absoluteWritesBypassControllerPickup() {
        HostFixture f = new HostFixture();
        MixModule module = f.services.mix();
        // SettableRangedValue.set(double) may leave the value untouched under pickup;
        // setImmediately(double) applies an absolute target regardless of takeover mode.
        for (String field : List.of("volume", "pan")) {
            FakeHost value = f.tracks[1].child(field).child("value");
            value.on("set", args -> {
                f.mutation();
                value.writes++;
                return null; // No getter change and no observer delivery while pickup waits.
            });
        }
        HostOperation op = f.submit(module, "tracks.set", set(HostFixture.B, "{\"volume\":0.75,\"pan\":0.62}"));
        f.next();
        assertTrue(op.result.isDone(), "absolute RPC mix targets apply without crossing a physical pickup point");
        JsonObject result = op.result.join();
        assertTrue(
                result.get("outcome").getAsString().matches("applied|already_matched"),
                "pickup does not leave requested pan/volume unobserved");
        for (String field : List.of("volume", "pan"))
            assertEquals("applied", outcome(result, field), "absolute target still requires fresh evidence: " + field);
        JsonObject read = f.call(module, "tracks.get", HostFixture.targeting(HostFixture.B));
        assertTrue(
                read.getAsJsonObject("volume").get("value").getAsDouble() == .75
                        && read.getAsJsonObject("pan").get("value").getAsDouble() == .62,
                "subsequent consumer read exposes the changed normalized values");
        assertTrue(
                f.tracks[0].child("volume").child("value").writes == 0
                        && f.tracks[0].child("pan").child("value").writes == 0,
                "pickup bypass does not change another track");
    }

    @Test
    void reopenedSourcesWaitForFlushAndIdentityAgreement() {
        for (boolean sameUuid : new boolean[] {true, false}) {
            HostFixture f = new HostFixture();
            MixModule module = f.services.mix();
            String id = sameUuid ? HostFixture.A : HostFixture.C;
            JsonObject ref = Refs.track(id, "Reopened");
            f.tracks[0].child("exists").emit(false);
            module.flush(); // Closing a project is not a valid readable binding.
            f.tracks[0].child("name").emit("Reopened");
            f.tracks[0].child("channelId").data = id;
            f.tracks[0].child("exists").data = true;
            module.flush(); // Getters alone cannot replace observed identity/existence.
            JsonObject incomplete = f.call(module, "tracks.get", params(Refs.track(id, "Reopened")));
            assertTrue(
                    incomplete.get("name").isJsonNull()
                            && incomplete.get("index").isJsonNull(),
                    "flush cannot expose a reopened getter before observer identity/existence agree");
            if (!sameUuid) {
                f.tracks[0].child("exists").emit(true);
                module.flush();
                JsonObject wrongIdentity = f.call(module, "tracks.get", params(ref));
                assertTrue(
                        wrongIdentity.get("name").isJsonNull(),
                        "existence agreement alone cannot validate the wrong observed UUID");
            }
            f.tracks[0].child("channelId").emit(id);
            f.tracks[0].child("exists").emit(true);
            JsonObject pending = f.call(module, "tracks.get", params(ref));
            assertTrue(
                    pending.get("name").isJsonNull(), "reappearance remains masked until an actual later host flush");
            module.flush();
            JsonObject read = f.call(module, "tracks.get", params(ref));
            assertTrue(
                    read.get("name").getAsString().equals("Reopened")
                            && read.get("index").getAsInt() == 0
                            && read.getAsJsonObject("pan").get("value").getAsDouble() == .5,
                    "same/new UUID reappearance recovers continuous values without unchanged callbacks");
        }
    }

    @Test
    void equalWriteAfterRebindIsAlreadyMatched() {
        HostFixture f = new HostFixture();
        MixModule module = f.services.mix();
        // A group or insert rebinds the slot; unchanged values never replay, and an equal write raises no callback.
        f.tracks[0].child("exists").emit(false);
        f.tracks[0].child("exists").emit(true);
        module.flush();
        JsonObject read = f.call(module, "tracks.get", HostFixture.targeting(HostFixture.A));
        for (String field : List.of("mute", "arm")) f.tracks[0].child(field).notifyWrites = false;
        f.tracks[0].child("volume").child("value").notifyWrites = false;
        JsonObject write = set(HostFixture.A, "{}");
        write.add("mute", read.get("mute"));
        write.add("arm", read.get("arm"));
        write.add("volume", read.getAsJsonObject("volume").get("value"));
        HostOperation op = f.submit(module, "tracks.set", write);
        for (int i = 0; i < 2000 && !op.result.isDone(); i++) f.next();
        assertEquals(
                "already_matched",
                op.result.join().get("outcome").getAsString(),
                op.result.join().toString());
    }

    @Test
    void reboundGenerationNeverAcknowledgesOldWrite() {
        HostFixture f = new HostFixture();
        MixModule module = f.services.mix();
        FakeHost volume = f.tracks[0].child("volume").child("value");
        volume.notifyWrites = false;
        HostOperation old = f.submit(module, "tracks.set", set(HostFixture.A, "{\"volume\":0.7}"));
        f.next();
        f.tracks[0].child("exists").emit(false);
        f.tracks[0].child("exists").emit(true);
        module.flush();
        volume.emit(.7);
        f.next();
        JsonObject result = old.result.join();
        assertTrue(
                result.get("outcome").getAsString().equals("unknown")
                        && result.get("reason").getAsString().equals("target_changed"),
                "same-UUID rebind plus matching callback cannot acknowledge an old-generation write");
        assertFalse(
                outcome(result, "volume").matches("applied|already_matched"),
                "reopened source never validates stale write evidence");
    }

    @Test
    void unknownMethodsAreNotRegistered() {
        assertFalse(MixModule.METHODS.contains("tracks.create"));
        assertFalse(MixModule.METHODS.contains("tracks.meters"));
    }
}
