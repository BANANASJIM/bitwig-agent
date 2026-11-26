package dev.bitwigagent;

import static dev.bitwigagent.RpcAssert.*;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Behavioral fake-host tests; no claim about audio, routing, or native factory completion. */
public final class MixModuleTest {

    @Test
    void readsExposeNormalizedUnitsAndSends() {
        HostFixture f = new HostFixture();
        MixModule module = new MixModule(f.services);
        JsonObject result = f.call(module, "tracks.get", HostFixture.target(HostFixture.A));
        assertTrue(result.get("volumeNormalized").getAsDouble() == .5, "actual normalized volume");
        assertTrue(
                result.get("volumeDisplay").getAsString().equals("-12 dB"),
                "host display retained separately from normalized value");
        assertTrue(
                result.getAsJsonObject("units")
                        .get("volumeNormalized")
                        .getAsString()
                        .equals("normalized_0_1_not_db"),
                "normalized is never falsely named dB");
        JsonObject send = result.getAsJsonArray("sends").get(0).getAsJsonObject();
        assertTrue(
                send.get("valueNormalized").getAsDouble() == .25
                        && send.get("display").getAsString().equals("-18 dB"),
                "send value/display are distinct");
        assertTrue(
                send.get("enabled").getAsBoolean() && !send.get("preFader").getAsBoolean(), "send metadata observed");
        assertTrue(
                result.getAsJsonObject("window").get("capacity").getAsInt() == HostServices.TRACKS
                        && result.getAsJsonObject("sendWindow").get("capacity").getAsInt() == 8,
                "bounded observation scope explicit");
        assertTrue(f.host.calls("createTrackBank") == 1, "mix never creates a duplicate track bank");
    }

    @Test
    void briefReportsSelectionOnlyWhenObserved() {
        HostFixture f = new HostFixture();
        MixModule module = new MixModule(f.services);
        f.tracks[0].child("selected").emit(true);
        f.tracks[0].child("exists").emit(false);
        f.tracks[0].child("exists").emit(true);
        module.flush();
        JsonObject before = module.brief(0);
        assertTrue(
                before.get("trackId").getAsString().equals(HostFixture.A)
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
        MixModule module = new MixModule(f.services);
        for (String text : List.of(
                "{\"volumeNormalized\":-0.1}",
                "{\"panNormalized\":1.1}",
                "{\"mute\":1}",
                "{\"arm\":null}",
                "{\"name\":\"\"}",
                "{\"extra\":true}",
                "{}")) {
            JsonObject p = HostFixture.json(text);
            p.addProperty("trackId", HostFixture.A);
            assertRpcError("invalid_params", f.submit(module, "tracks.set", p));
            assertTrue(f.tasks.isEmpty(), "invalid mix field rejected before scheduling");
        }
        for (String text : List.of(
                "{\"type\":\"group\",\"position\":0,\"name\":\"X\"}",
                "{\"type\":\"audio\",\"position\":-2,\"name\":\"X\"}",
                "{\"type\":\"effect\",\"position\":0.5,\"name\":\"X\"}",
                "{\"type\":\"instrument\",\"position\":0}",
                "{\"type\":\"audio\",\"position\":2147483648,\"name\":\"X\"}")) {
            assertRpcError("invalid_params", f.submit(module, "tracks.create", HostFixture.json(text)));
            assertTrue(f.tasks.isEmpty(), "invalid create combination rejected before scheduling");
        }
    }

    @Test
    void writesOnlyExplicitUuidAndFields() {
        HostFixture f = new HostFixture();
        MixModule module = new MixModule(f.services);
        JsonObject p = HostFixture.target(HostFixture.B);
        p.addProperty("volumeNormalized", .75);
        p.addProperty("mute", true);
        JsonObject result = f.call(module, "tracks.set", p);
        assertTrue(result.get("matchesRequested").getAsBoolean(), "requested mix observed");
        assertTrue(
                f.tracks[1].child("volume").child("value").writes == 1 && f.tracks[1].child("mute").writes == 1,
                "only UUID B receives writes");
        assertTrue(
                f.tracks[0].child("volume").child("value").writes == 0 && f.tracks[0].child("mute").writes == 0,
                "UUID A untouched");
        for (String key : List.of("name", "pan", "solo", "arm"))
            assertTrue(f.tracks[1].child(key).writes == 0, "omitted field unchanged: " + key);
        JsonObject again = f.call(module, "tracks.set", p);
        assertTrue(
                again.getAsJsonObject("fields")
                        .getAsJsonObject("mute")
                        .get("verification")
                        .getAsString()
                        .equals("already_at_value"),
                "idempotent mix set explicit");
        assertTrue(f.tracks[1].child("mute").writes == 1, "no unnecessary second mute write");
        HostOperation absent =
                f.submit(module, "tracks.set", HostFixture.json("{\"trackId\":\"outside\",\"mute\":true}"));
        f.next();
        assertRpcError("outside_window", absent);
        assertTrue(f.tracks[0].child("mute").writes == 0, "absent UUID never falls back to selected track");
    }

    @Test
    void colorActivatedMonitorAndCrossFadeAreReadAndWritten() {
        HostFixture f = new HostFixture();
        MixModule module = new MixModule(f.services);
        JsonObject read = f.call(module, "tracks.get", HostFixture.target(HostFixture.B));
        assertTrue(
                read.get("color").getAsString().equals("#000000")
                        && read.get("activated").getAsBoolean()
                        && read.get("monitorMode").getAsString().equals("AUTO")
                        && read.get("crossFadeMode").getAsString().equals("AB"),
                "new track fields are read");
        assertTrue(
                read.getAsJsonObject("options").getAsJsonArray("monitorMode").size() == 3
                        && read.getAsJsonObject("options")
                                .getAsJsonArray("crossFadeMode")
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
            JsonObject p = HostFixture.json(text);
            p.addProperty("trackId", HostFixture.B);
            assertRpcError("invalid_params", f.submit(module, "tracks.set", p));
            assertTrue(f.tasks.isEmpty(), "invalid field rejected before scheduling: " + text);
        }
        JsonObject unlisted = HostFixture.json("{\"mute\":true,\"monitorMode\":\"LOUD\"}");
        unlisted.addProperty("trackId", HostFixture.B);
        HostOperation refused = f.submit(module, "tracks.set", unlisted);
        f.next();
        assertTrue(
                assertRpcError("invalid_params", refused).outcome.equals("not_applied"),
                "option the host does not list is refused");
        assertTrue(
                f.tracks[1].child("monitorMode").writes == 0 && f.tracks[1].child("mute").writes == 0,
                "nothing written when an option is refused");
        JsonObject p = HostFixture.json(
                "{\"color\":\"#FF8800\",\"activated\":false,\"monitorMode\":\"ON\",\"crossFadeMode\":\"B\"}");
        p.addProperty("trackId", HostFixture.B);
        JsonObject result = f.call(module, "tracks.set", p);
        assertTrue(
                result.get("matchesRequested").getAsBoolean()
                        && result.get("color").getAsString().equals("#ff8800")
                        && !result.get("activated").getAsBoolean()
                        && result.get("monitorMode").getAsString().equals("ON")
                        && result.get("crossFadeMode").getAsString().equals("B"),
                "every new field is read back");
        assertTrue(
                result.getAsJsonObject("fields")
                        .getAsJsonObject("color")
                        .get("verification")
                        .getAsString()
                        .equals("observer_after_write"),
                "color verified by observer");
        assertTrue(
                f.tracks[1].child("color").writes == 1
                        && f.tracks[0].child("color").writes == 0
                        && f.tracks[0].child("monitorMode").writes == 0,
                "only UUID B is written");
        JsonObject again = f.call(module, "tracks.set", p);
        assertTrue(
                again.getAsJsonObject("fields")
                                .getAsJsonObject("monitorMode")
                                .get("verification")
                                .getAsString()
                                .equals("already_at_value")
                        && f.tracks[1].child("monitorMode").writes == 1
                        && f.tracks[1].child("isActivated").writes == 1,
                "matching values are not rewritten");
        f.tracks[1].child("crossFadeMode").notifyWrites = false;
        JsonObject silent = HostFixture.json("{\"crossFadeMode\":\"A\"}");
        silent.addProperty("trackId", HostFixture.B);
        HostOperation op = f.submit(module, "tracks.set", silent, f.tasks.after(300));
        while (!op.result.isDone()) f.next();
        assertTrue(
                !op.result.join().get("matchesRequested").getAsBoolean(),
                "an unobserved enum write is not reported as done");
    }

    @Test
    void colorReadbackWithinOneStepAndOutOfListEnumsRoundTrip() {
        HostFixture f = new HostFixture();
        MixModule module = new MixModule(f.services);
        FakeHost color = f.tracks[1].child("color");
        color.afterWrite = () -> color.emit(new float[] {1f, 0x57 / 255f, 4 / 255f});
        JsonObject p = HostFixture.json("{\"color\":\"#ff5705\"}");
        p.addProperty("trackId", HostFixture.B);
        JsonObject result = f.call(module, "tracks.set", p);
        assertTrue(
                result.get("matchesRequested").getAsBoolean()
                        && result.get("color").getAsString().equals("#ff5704"),
                "one 8-bit step of float rounding still matches and the observed hex is reported");
        JsonObject far = HostFixture.json("{\"color\":\"#ff5a05\"}");
        far.addProperty("trackId", HostFixture.B);
        color.afterWrite = () -> color.emit(new float[] {1f, 0x57 / 255f, 4 / 255f});
        HostOperation farOp = f.submit(module, "tracks.set", far, f.tasks.after(300));
        while (!farOp.result.isDone()) f.next();
        assertTrue(!farOp.result.join().get("matchesRequested").getAsBoolean(), "a larger difference is a mismatch");
        f.tracks[1].child("monitorMode").emit("STORED");
        JsonObject read = f.call(module, "tracks.get", HostFixture.target(HostFixture.B));
        assertTrue(
                read.getAsJsonArray("observedOutsideOptions").size() == 1
                        && read.getAsJsonArray("observedOutsideOptions")
                                .get(0)
                                .getAsString()
                                .equals("monitorMode"),
                "a host value outside the option list is flagged");
        JsonObject back = HostFixture.json("{\"monitorMode\":\"STORED\"}");
        back.addProperty("trackId", HostFixture.B);
        JsonObject again = f.call(module, "tracks.set", back);
        assertTrue(
                again.getAsJsonObject("fields")
                                .getAsJsonObject("monitorMode")
                                .get("verification")
                                .getAsString()
                                .equals("already_at_value")
                        && f.tracks[1].child("monitorMode").writes == 0,
                "the currently observed out-of-list value can be written back");
        JsonObject bogus = HostFixture.json("{\"monitorMode\":\"LOUD\"}");
        bogus.addProperty("trackId", HostFixture.B);
        HostOperation refused = f.submit(module, "tracks.set", bogus);
        f.next();
        assertRpcError("invalid_params", refused);
    }

    @Test
    void selectionUsesUuidNotCurrentUi() {
        HostFixture f = new HostFixture();
        MixModule module = new MixModule(f.services);
        f.tracks[0].child("selected").emit(true);
        JsonObject selected = f.call(module, "tracks.select", HostFixture.target(HostFixture.B));
        assertTrue(
                selected.get("selected").getAsBoolean()
                        && selected.get("completion").getAsString().equals("not_observed"),
                "a selection callback is snapshot evidence, not UI-navigation completion");
        assertTrue(
                f.tracks[1].actions == 1 && f.tracks[0].actions == 0,
                "select operates on requested bank proxy, not current UI cursor");
        JsonObject again = f.call(module, "tracks.select", HostFixture.target(HostFixture.B));
        assertTrue(
                f.tracks[1].actions == 2
                        && again.get("completion").getAsString().equals("not_observed"),
                "an already-selected snapshot does not suppress an explicit editor-navigation command");
    }

    @Test
    void selectionWithoutReplayedCallbackIsDispatchOnly() {
        HostFixture f = new HostFixture();
        MixModule module = new MixModule(f.services);
        f.tracks[0].child("selected").emit(true);
        f.tracks[0].child("exists").emit(false);
        f.tracks[0].child("exists").emit(true);
        module.flush();
        f.tracks[0].child("selected").notifyWrites = false;
        HostOperation op = f.submit(module, "tracks.select", HostFixture.target(HostFixture.A));
        f.next();
        assertTrue(op.result.isDone(), "selection navigation must not wait for an optional legacy callback");
        JsonObject result = op.result.join();
        assertTrue(
                result.get("completion").getAsString().equals("not_observed")
                        && result.get("status").getAsString().equals("dispatched"),
                "missing selection callback yields a UI dispatch receipt, never an observed completion");
        assertTrue(
                result.get("selected").isJsonNull()
                        && !result.getAsJsonObject("readable").get("selected").getAsBoolean(),
                "reopened track cannot inherit the previous generation's selected value");
        assertTrue(
                !result.has("fields") && !result.has("matchesRequested"),
                "UI navigation does not pretend to be a verified mix setter");
        assertTrue(
                f.tracks[0].actions == 1 && f.tasks.isEmpty(),
                "selection dispatches once without deferred retries or callback polling");
    }

    @Test
    void selectionFailsFastWhileLauncherLeaseHeld() {
        HostFixture f = new HostFixture();
        MixModule module = new MixModule(f.services);
        HostOperation holder = new HostOperation(Long.MAX_VALUE);
        f.services.acquireSelection(holder);
        HostOperation op = f.submit(module, "tracks.select", HostFixture.target(HostFixture.B));
        f.next();
        RpcException busy = assertRpcError("busy", op);
        assertTrue(
                busy.outcome.equals("not_applied") && f.tracks[1].actions == 0,
                "held lease rejects select before dispatch");
        assertTrue(f.tasks.isEmpty(), "rejected select is not queued");
        holder.result.complete(new JsonObject());
        assertTrue(
                f.call(module, "tracks.select", HostFixture.target(HostFixture.B))
                                .get("status")
                                .getAsString()
                                .equals("dispatched")
                        && f.tracks[1].actions == 1,
                "released lease allows select");
    }

    @Test
    void selectionRespectsOperationAndIdentityGuards() {
        HostFixture f = new HostFixture();
        MixModule module = new MixModule(f.services);
        JsonObject p = HostFixture.target(HostFixture.A);
        HostOperation expired = f.submit(module, "tracks.select", p, f.tasks.now() - 1);
        f.next();
        assertTrue(
                assertRpcError("deadline_exceeded", expired).outcome.equals("not_applied"),
                "expired selection is rejected before dispatch");
        HostOperation cancelled = f.submit(module, "tracks.select", p);
        cancelled.result.cancel(false);
        f.next();
        f.tracks[0].child("exists").emit(false);
        f.tracks[0].child("exists").emit(true);
        HostOperation unready = f.submit(module, "tracks.select", p);
        f.next();
        assertTrue(
                assertRpcError("not_ready", unready).outcome.equals("not_applied") && f.tracks[0].actions == 0,
                "cancelled or rebound-before-flush selection cannot reach the host");
        module.flush();
        f.tracks[0].child("selected").afterWrite =
                () -> f.tracks[0].child("channelId").emit(HostFixture.C);
        HostOperation shifted = f.submit(module, "tracks.select", p);
        f.next();
        assertTrue(
                assertRpcError("stale_target", shifted).outcome.equals("unknown") && f.tracks[0].actions == 1,
                "identity loss after dispatch remains unknown, not an accepted dispatch-only receipt");
    }

    @Test
    void requiresMatchingFreshTargetObservation() {
        HostFixture f = new HostFixture();
        MixModule module = new MixModule(f.services);
        FakeHost volume = f.tracks[1].child("volume").child("value");
        volume.notifyWrites = false;
        JsonObject p = HostFixture.target(HostFixture.B);
        p.addProperty("volumeNormalized", .75);
        HostOperation op = f.submit(module, "tracks.set", p);
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
                op.result.join().get("matchesRequested").getAsBoolean() && volume.writes == 1,
                "fresh matching callback verifies without replay");
    }

    @Test
    void targetChangePreventsRemainingWrites() {
        HostFixture f = new HostFixture();
        MixModule module = new MixModule(f.services);
        f.tracks[0].child("name").afterWrite =
                () -> f.tracks[0].child("channelId").emit(HostFixture.C);
        JsonObject p = HostFixture.target(HostFixture.A);
        p.addProperty("name", "Renamed");
        p.addProperty("volumeNormalized", .8);
        JsonObject result = f.call(module, "tracks.set", p);
        assertTrue(
                result.get("status").getAsString().equals("dispatched")
                        && result.get("completion").getAsString().equals("unknown"),
                "partial mutation with shifted target is unknown");
        assertTrue(result.get("reason").getAsString().equals("stale_target"), "target change identified");
        assertTrue(
                f.tracks[0].child("volume").child("value").writes == 0,
                "replacement track never receives remaining volume write");
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
        MixModule module = new MixModule(f.services);
        JsonObject p = HostFixture.target(HostFixture.A);
        p.addProperty("arm", true);
        HostOperation expired = f.submit(module, "tracks.set", p, f.tasks.now() - 1);
        f.next();
        assertTrue(
                assertRpcError("deadline_exceeded", expired).outcome.equals("not_applied"),
                "expired target setter not applied");
        HostOperation cancelled = f.submit(module, "tracks.set", p);
        cancelled.result.cancel(false);
        f.next();
        assertTrue(f.tracks[0].child("arm").writes == 0, "queued callbacks after deadline/cancel cannot arm");
        JsonObject partial = HostFixture.target(HostFixture.A);
        partial.addProperty("name", "First");
        partial.addProperty("arm", true);
        HostOperation during = f.submit(module, "tracks.set", partial);
        f.tracks[0].child("name").afterWrite = () -> during.result.cancel(false);
        f.next();
        assertTrue(
                during.result.isCancelled() && f.tracks[0].child("name").writes == 1,
                "cancellation can occur after the first mutation");
        assertTrue(f.tracks[0].child("arm").writes == 0, "per-field cancellation guard prevents late arming");
    }

    @Test
    void creationNamesOnlyUniqueObservedAddition() {
        for (String type : List.of("audio", "instrument", "effect")) verifyCreation(type);
    }

    private static JsonObject delete(String id, String name) {
        JsonObject p = HostFixture.target(id);
        p.addProperty("expectedName", name);
        return p;
    }

    @Test
    void deleteIsNameGuardedAndVerifiedByUuidAndCount() {
        HostFixture f = new HostFixture();
        MixModule module = new MixModule(f.services);
        f.delete = slot -> f.removeTrack(slot, 1);
        HostOperation wrong = f.submit(module, "tracks.delete", delete(HostFixture.B, "Nope"));
        f.next();
        assertTrue(
                assertRpcError("stale_target", wrong).outcome.equals("not_applied") && f.tracks[1].actions == 0,
                "name mismatch never dispatches");
        f.tracks[1].child("trackType").emit("Master");
        HostOperation master = f.submit(module, "tracks.delete", delete(HostFixture.B, "Track 1"));
        f.next();
        assertTrue(
                assertRpcError("unsupported_scope", master).outcome.equals("not_applied") && f.tracks[1].actions == 0,
                "master refused");
        f.tracks[1].child("trackType").emit("Group");
        HostOperation group = f.submit(module, "tracks.delete", delete(HostFixture.B, "Track 1"));
        f.next();
        assertTrue(
                assertRpcError("unsupported_scope", group).outcome.equals("not_applied") && f.tracks[1].actions == 0,
                "group refused");
        // An instrument track that bounce.inPlace turned Hybrid stays deletable.
        f.tracks[1].child("trackType").emit("Hybrid");
        f.transport.child("isPlaying").emit(true);
        HostOperation playing = f.submit(module, "tracks.delete", delete(HostFixture.B, "Track 1"));
        f.next();
        assertTrue(
                assertRpcError("unsafe_state", playing).outcome.equals("not_applied") && f.tracks[1].actions == 0,
                "playing refused");
        f.transport.child("isPlaying").emit(false);
        HostOperation op = f.submit(module, "tracks.delete", delete(HostFixture.B, "Track 1"));
        f.next();
        if (!op.result.isDone()) f.next();
        JsonObject result = op.result.join();
        assertTrue(
                f.tracks[1].actions == 1
                        && result.get("completion").getAsString().equals("observed")
                        && result.get("verification").getAsString().equals("uuid_absent_count_decremented")
                        && result.get("trackCountBefore").getAsInt() == 2
                        && result.get("trackCountAfter").getAsInt() == 1,
                "deleted once, verified");
        HostFixture silent = new HostFixture();
        MixModule other = new MixModule(silent.services);
        HostOperation nothing = silent.submit(other, "tracks.delete", delete(HostFixture.B, "Track 1"));
        silent.next();
        while (!nothing.result.isDone() && !silent.tasks.isEmpty()) silent.next();
        assertTrue(
                nothing.result.isDone()
                        && nothing.result.join().get("completion").getAsString().equals("unknown"),
                "no observed change stays unknown");
    }

    private static void verifyCreation(String type) {
        HostFixture f = new HostFixture();
        MixModule module = new MixModule(f.services);
        f.create = factory -> {
            String hostType = Character.toUpperCase(type.charAt(0)) + type.substring(1);
            assertTrue(factory.equals("create" + hostType + "Track"), "requested type chooses exact public factory");
            f.addTrack(2, HostFixture.C, hostType);
        };
        HostOperation op = f.submit(module, "tracks.create", create(type, -1));
        f.next();
        assertTrue(
                f.tracks[2].child("name").writes == 0 && !op.result.isDone(),
                "void factory is not immediate completion or slot naming");
        f.next();
        JsonObject result = op.result.join();
        assertTrue(result.get("trackId").getAsString().equals(HostFixture.C), "new unique UUID selected by difference");
        assertTrue(
                result.getAsJsonObject("creation")
                        .get("verification")
                        .getAsString()
                        .equals("unique_added_uuid_count_type_position"),
                "creation evidence declared");
        assertTrue(
                f.tracks[2].child("name").writes == 1
                        && f.tracks[2].child("name").data.equals("Created"),
                "only unique observed target named");
        assertTrue(
                f.tracks[0].child("name").writes == 0 && f.tracks[1].child("name").writes == 0,
                "preexisting tracks not renamed");
        assertTrue(f.application.actions == 1, "factory not retried");
    }

    @Test
    void ambiguousCreationStaysUnknownAndIncompleteBaselineDoesNotDispatch() {
        HostFixture ambiguous = new HostFixture();
        MixModule module = new MixModule(ambiguous.services);
        ambiguous.create = factory -> {
            ambiguous.addTrack(2, HostFixture.C, "Audio");
            ambiguous.addTrack(3, "extra-track", "Audio");
        };
        JsonObject result = ambiguous.call(module, "tracks.create", create("audio", -1));
        assertTrue(
                result.get("completion").getAsString().equals("unknown")
                        && !result.get("nameApplied").getAsBoolean(),
                "multiple additions never claim creation completion");
        assertTrue(
                ambiguous.tracks[2].child("name").writes == 0 && ambiguous.tracks[3].child("name").writes == 0,
                "ambiguous tracks never renamed");
        HostFixture outside = new HostFixture();
        MixModule other = new MixModule(outside.services);
        outside.bank.child("itemCount").emit(HostServices.TRACKS + 4);
        HostOperation rejected = outside.submit(other, "tracks.create", create("effect", -1));
        outside.next();
        assertTrue(
                assertRpcError("not_ready", rejected).outcome.equals("not_applied"),
                "incomplete16-track baseline rejects before mutation");
        assertTrue(
                outside.application.actions == 0 && !rejected.dispatched,
                "known unobservable baseline must never invoke a factory");
    }

    @Test
    void groupedBaselineRejectsWithoutMutationOrQuarantine() {
        for (String type : List.of("audio", "instrument", "effect")) {
            HostFixture f = new HostFixture();
            MixModule module = new MixModule(f.services);
            f.tracks[0].child("trackType").emit("Group");
            JsonObject beforeA = f.call(module, "tracks.get", HostFixture.target(HostFixture.A));
            JsonObject beforeB = f.call(module, "tracks.get", HostFixture.target(HostFixture.B));
            Object count = f.bank.child("itemCount").data;
            String hostType = Character.toUpperCase(type.charAt(0)) + type.substring(1);
            f.create = factory -> f.addTrack(2, HostFixture.C, hostType);
            for (int position : new int[] {-1, 0}) {
                HostOperation rejected = f.submit(module, "tracks.create", create(type, position));
                f.next();
                assertTrue(
                        assertRpcError("unsupported_scope", rejected).outcome.equals("not_applied"),
                        "known grouped baseline explicitly rejects as not applied");
                assertTrue(
                        !rejected.dispatched && f.application.actions == 0,
                        "known grouped baseline never dispatches any native track factory");
                assertTrue(
                        count.equals(f.bank.child("itemCount").data)
                                && Boolean.FALSE.equals(f.tracks[2].child("exists").data),
                        "rejected grouped creation leaves project track count and empty slot unchanged");
                assertTrue(
                        beforeA.equals(f.call(module, "tracks.get", HostFixture.target(HostFixture.A)))
                                && beforeB.equals(f.call(module, "tracks.get", HostFixture.target(HostFixture.B))),
                        "rejected grouped creation preserves existing track snapshots");
                for (FakeHost track : f.tracks)
                    assertTrue(track.child("name").writes == 0, "rejected grouped creation never renames any track");
                assertTrue(f.tasks.isEmpty(), "rejection leaves no creation observation task pending");
            }
            f.tracks[0].child("trackType").emit("Instrument");
            HostOperation admitted = f.submit(module, "tracks.create", create(type, -1));
            f.next();
            f.next();
            assertTrue(
                    admitted.result.join().get("matchesRequested").getAsBoolean() && f.application.actions == 1,
                    "grouped pre-dispatch rejection does not quarantine later supported creation");
            assertTrue(
                    f.tracks[2].child("name").writes == 1, "later ungrouped creation names only the observed addition");
        }
    }

    @Test
    void concurrentGroupAppearanceRemainsUnknownAndQuarantined() {
        HostFixture f = new HostFixture();
        MixModule module = new MixModule(f.services);
        f.create = factory -> {
            f.tracks[0].child("trackType").emit("Group");
            f.addTrack(2, HostFixture.C, "Audio");
        };
        HostOperation op = f.submit(module, "tracks.create", create("audio", -1));
        f.next();
        JsonObject result = op.result.join();
        assertTrue(
                op.dispatched && f.application.actions == 1, "concurrent grouping occurs after one factory dispatch");
        assertTrue(
                result.get("status").getAsString().equals("dispatched")
                        && result.get("completion").getAsString().equals("unknown")
                        && result.get("reason").getAsString().equals("group_appeared_during_creation"),
                "group appearing after dispatch retains structural unknown attribution");
        assertTrue(
                !result.get("nameApplied").getAsBoolean()
                        && result.get("trackId").isJsonNull()
                        && f.tracks[2].child("name").writes == 0,
                "concurrent grouped insertion is never attributed or renamed");
        f.tracks[0].child("trackType").emit("Instrument");
        HostOperation later = f.submit(module, "tracks.create", create("audio", -1));
        f.next();
        assertTrue(
                assertRpcError("unresolved_creation", later).outcome.equals("not_applied")
                        && !later.dispatched
                        && f.application.actions == 1,
                "concurrent structural unknown retains quarantine even when the group disappears");
    }

    @Test
    void wrongTypeOrPositionCannotBeNamed() {
        for (boolean wrongType : new boolean[] {true, false}) {
            HostFixture f = new HostFixture();
            MixModule module = new MixModule(f.services);
            f.create = factory -> f.addTrack(2, HostFixture.C, wrongType ? "Instrument" : "Audio");
            HostOperation op = f.submit(module, "tracks.create", create("audio", wrongType ? -1 : 0));
            f.next();
            f.next();
            assertTrue(
                    !op.result.isDone() && f.tracks[2].child("name").writes == 0,
                    "type/position mismatch is not a nameable creation");
            f.services.close();
            f.next();
            assertTrue(
                    assertRpcError("unavailable", op).outcome.equals("unknown"),
                    "abandoned factory remains unknown, not not_applied");
        }
    }

    @Test
    void creationNeverNamesAfterCancellation() {
        HostFixture f = new HostFixture();
        MixModule module = new MixModule(f.services);
        f.create = factory -> f.addTrack(2, HostFixture.C, "Audio");
        HostOperation op = f.submit(module, "tracks.create", create("audio", -1));
        f.next();
        op.result.cancel(false);
        f.next();
        assertTrue(
                f.tracks[2].child("name").writes == 0 && f.application.actions == 1,
                "late observation after cancelled creation cannot rename or retry");
    }

    @Test
    void replacementDoesNotReuseReadiness() {
        for (boolean replaceUuid : new boolean[] {true, false}) {
            HostFixture f = new HostFixture();
            MixModule module = new MixModule(f.services);
            f.tracks[0].child("mute").emit(true);
            f.tracks[0].child("selected").emit(true);
            String id = replaceUuid ? HostFixture.C : HostFixture.A;
            if (replaceUuid) f.tracks[0].child("channelId").emit(id);
            else {
                f.tracks[0].child("exists").emit(false);
                f.tracks[0].child("exists").emit(true);
            }
            JsonObject read = f.call(module, "tracks.get", HostFixture.target(id));
            assertTrue(
                    read.get("mute").isJsonNull() && read.get("selected").isJsonNull(),
                    "replacement cannot inherit previous identity's mix/selection observations");
            assertTrue(
                    !read.get("observationsComplete").getAsBoolean()
                            && !read.getAsJsonObject("readable").get("mute").getAsBoolean(),
                    "partial snapshot explicitly declares missing readiness");
            assertTrue(
                    read.get("volumeDisplay").isJsonNull()
                            && read.get("sendWindow").isJsonNull(),
                    "replacement cannot inherit display/send metadata");
            JsonObject pendingWrite = HostFixture.target(id);
            pendingWrite.addProperty("mute", true);
            HostOperation blocked = f.submit(module, "tracks.set", pendingWrite);
            f.next();
            assertRpcError("not_ready", blocked);
            assertTrue(f.tracks[0].child("mute").writes == 0, "no mutation before the actual host flush boundary");
            module.flush();
            JsonObject baseline = f.call(module, "tracks.get", HostFixture.target(id));
            assertTrue(
                    baseline.get("mute").getAsBoolean() && baseline.get("index").getAsInt() == 0,
                    "continuous unchanged proxy values become readable after flush without callback replay");
            assertTrue(
                    baseline.get("selected").isJsonNull(),
                    "legacy selection callback is not fabricated from predecessor state");
            f.tracks[0].child("mute").notifyWrites = false;
            JsonObject p = HostFixture.target(id);
            p.addProperty("mute", true);
            HostOperation op = f.submit(module, "tracks.set", p);
            f.next();
            assertTrue(
                    f.tracks[0].child("mute").writes == 1 && !op.result.isDone(),
                    "unready matching getter must dispatch rather than falsely skip as already_at_value");
            f.tracks[0].child("mute").emit(true);
            f.next();
            assertTrue(
                    op.result.join().get("matchesRequested").getAsBoolean(),
                    "requested field can verify without every unchanged field replaying");
            JsonObject selected = f.call(module, "tracks.select", HostFixture.target(id));
            assertTrue(
                    f.tracks[0].actions == 1
                            && selected.get("completion").getAsString().equals("not_observed"),
                    "rebound selection dispatches explicitly without pretending to verify UI completion");
        }
    }

    @Test
    void firstIdentityObservationPreservesStartupValues() {
        HostFixture f = new HostFixture();
        f.tracks[0].child("channelId").notifyInitial = false;
        f.tracks[0].child("exists").notifyInitial = false;
        MixModule module = new MixModule(f.services);
        f.tracks[0].child("channelId").emit(HostFixture.A);
        f.tracks[0].child("exists").emit(true);
        JsonObject read = f.call(module, "tracks.get", HostFixture.target(HostFixture.A));
        assertTrue(
                read.get("volumeNormalized").getAsDouble() == .5
                        && read.get("observationsComplete").getAsBoolean(),
                "first identity callback after fields is not treated as replacement");
    }

    @Test
    void unresolvedFactoryQuarantinesLaterCreation() {
        HostFixture f = new HostFixture();
        MixModule module = new MixModule(f.services);
        HostOperation a = f.submit(module, "tracks.create", create("audio", -1));
        f.next();
        a.result.cancel(false);
        f.next();
        JsonObject requestB = create("audio", -1);
        requestB.addProperty("name", "Must not name A");
        HostOperation b = f.submit(module, "tracks.create", requestB);
        f.next();
        assertTrue(
                assertRpcError("unresolved_creation", b).outcome.equals("not_applied"),
                "cancelled RPC does not release native factory attribution");
        f.addTrack(2, HostFixture.C, "Audio");
        assertTrue(
                f.application.actions == 1 && f.tracks[2].child("name").writes == 0,
                "late A addition can never be attributed or named by B");
        HostOperation c = f.submit(module, "tracks.create", requestB);
        f.next();
        assertRpcError("unresolved_creation", c);
        assertTrue(f.application.actions == 1, "late observation alone does not silently release quarantine");
        HostFixture unknown = new HostFixture();
        MixModule second = new MixModule(unknown.services);
        unknown.create = factory -> {
            unknown.addTrack(2, HostFixture.C, "Audio");
            unknown.addTrack(3, "extra-track", "Audio");
        };
        JsonObject dispatched = unknown.call(second, "tracks.create", create("audio", -1));
        assertTrue(
                dispatched.get("completion").getAsString().equals("unknown"),
                "ambiguous post-dispatch observation produces completed unknown result");
        HostOperation next = unknown.submit(second, "tracks.create", create("audio", -1));
        unknown.next();
        assertRpcError("unresolved_creation", next);
        assertTrue(unknown.application.actions == 1, "completed unknown result also retains quarantine");
    }

    @Test
    void placeholderStartupPreservesNativeStyleBaseline() {
        for (boolean existenceFirst : new boolean[] {true, false}) {
            HostFixture f = new HostFixture();
            String[] ids = {HostFixture.A, HostFixture.B, HostFixture.C, "00000000-0000-0000-0000-000000000004"};
            String[] types = {"Instrument", "Audio", "Effect", "Master"};
            for (int i = 0; i < ids.length; i++) {
                f.tracks[i].child("channelId").data = "";
                f.tracks[i].child("exists").data = false;
                f.tracks[i].child("trackType").data = types[i];
            }
            f.bank.child("itemCount").emit(4);
            MixModule module = new MixModule(f.services);
            // Native failure shape: complete4-track window includes Effect and Master.
            // Real type/position arrive before first nonempty IDs, with no replay afterward.
            for (int i = 0; i < ids.length; i++) {
                f.tracks[i].child("trackType").emit(types[i]);
                f.tracks[i].child("position").emit(i);
                if (existenceFirst) f.tracks[i].child("exists").emit(true);
                f.tracks[i].child("channelId").emit(ids[i]);
                if (!existenceFirst) f.tracks[i].child("exists").emit(true);
            }
            JsonObject read = f.call(module, "tracks.get", HostFixture.target(ids[3]));
            assertTrue(
                    read.get("type").getAsString().equals("Master")
                            && read.get("index").getAsInt() == 3,
                    "placeholder-to-first-identity preserves type/index without replay");
            f.create = factory -> {
                f.tracks[2].child("position").emit(3);
                f.tracks[3].child("position").emit(4);
                f.addTrack(4, "00000000-0000-0000-0000-000000000005", "Instrument");
                f.tracks[4].child("position").emit(2);
            };
            HostOperation created = f.submit(module, "tracks.create", create("instrument", -1));
            f.next();
            f.next();
            JsonObject result = created.result.join();
            assertTrue(
                    result.get("matchesRequested").getAsBoolean() && f.application.actions == 1,
                    "complete native-style baseline supports one instrument factory and observed name");
            assertTrue(
                    result.getAsJsonObject("creation").get("observedPosition").getAsInt() == 2,
                    "main insertion ordinal excludes effect/master but full UUID baseline includes both");
        }
    }

    @Test
    void missingCreationMetadataRejectsBeforeFactory() {
        HostFixture f = new HostFixture();
        f.tracks[1].child("trackType").notifyInitial = false;
        MixModule module = new MixModule(f.services);
        HostOperation rejected = f.submit(module, "tracks.create", create("audio", -1));
        f.next();
        assertTrue(
                assertRpcError("not_ready", rejected).outcome.equals("not_applied"),
                "missing type baseline rejects before dispatch despite complete track count");
        assertTrue(
                f.application.actions == 0 && !rejected.dispatched,
                "no unnamed native creation on known incomplete readiness");
        f.tracks[1].child("trackType").emit("Audio");
        f.create = factory -> f.addTrack(2, HostFixture.C, "Audio");
        HostOperation created = f.submit(module, "tracks.create", create("audio", -1));
        f.next();
        f.next();
        assertTrue(
                created.result.join().get("matchesRequested").getAsBoolean(),
                "pre-dispatch rejection does not quarantine a later fully observed creation");
    }

    @Test
    void absoluteWritesBypassControllerPickup() {
        HostFixture f = new HostFixture();
        MixModule module = new MixModule(f.services);
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
        JsonObject p = HostFixture.target(HostFixture.B);
        p.addProperty("volumeNormalized", .75);
        p.addProperty("panNormalized", .62);
        HostOperation op = f.submit(module, "tracks.set", p);
        f.next();
        assertTrue(op.result.isDone(), "absolute RPC mix targets apply without crossing a physical pickup point");
        JsonObject result = op.result.join();
        assertTrue(
                result.get("matchesRequested").getAsBoolean(), "pickup does not leave requested pan/volume unobserved");
        for (String field : List.of("volumeNormalized", "panNormalized"))
            assertTrue(
                    result.getAsJsonObject("fields")
                            .getAsJsonObject(field)
                            .get("verification")
                            .getAsString()
                            .equals("observer_after_write"),
                    "absolute target still requires fresh observer evidence: " + field);
        JsonObject read = f.call(module, "tracks.get", HostFixture.target(HostFixture.B));
        assertTrue(
                read.get("volumeNormalized").getAsDouble() == .75
                        && read.get("panNormalized").getAsDouble() == .62,
                "subsequent consumer read exposes the changed normalized values");
        assertTrue(
                f.tracks[0].child("volume").child("value").writes == 0
                        && f.tracks[0].child("pan").child("value").writes == 0,
                "pickup bypass does not change another track");
    }

    @Test
    void creationSurvivesNativeBankShiftWithoutValueReplay() {
        HostFixture f = new HostFixture();
        String master = "00000000-0000-0000-0000-000000000004";
        String added = "00000000-0000-0000-0000-000000000005";
        f.addTrack(2, HostFixture.C, "Effect");
        f.addTrack(3, master, "Master");
        MixModule module = new MixModule(f.services);
        f.create = factory -> {
            // Existing slot 2 becomes the new main track. New fields can precede its UUID.
            f.tracks[2].child("name").emit("Created");
            f.tracks[2].child("pan").child("value").emit(.62);
            f.tracks[2].child("channelId").emit(added);
            f.tracks[2].child("trackType").emit("Instrument");
            // The Effect shifts onto the previous Master proxy, with position 3 unchanged.
            f.tracks[3].child("name").emit("Shifted Effect");
            f.tracks[3].child("channelId").emit(HostFixture.C);
            f.tracks[3].child("trackType").emit("Effect");
            f.tracks[2].child("volume").child("displayedValue").data = "-9 dB";
            f.addTrack(4, master, "Master");
        };
        HostOperation op = f.submit(module, "tracks.create", create("instrument", -1));
        f.next();
        assertTrue(
                !op.result.isDone() && f.application.actions == 1,
                "void factory waits for rebound proxy readiness without retry");
        module.flush();
        f.next();
        f.next();
        assertTrue(
                op.result.isDone(),
                "unchanged slot positions no longer permanently block complete factory observation");
        JsonObject result = op.result.join();
        assertTrue(
                result.get("matchesRequested").getAsBoolean()
                        && result.get("trackId").getAsString().equals(added),
                "unique factory addition names the correct UUID after native-like callback ordering");
        assertTrue(
                result.get("index").getAsInt() == 2
                        && result.get("type").getAsString().equals("Instrument")
                        && result.get("name").getAsString().equals("Created"),
                "factory result retains correct type, position and name");
        assertTrue(
                result.get("panNormalized").getAsDouble() == .62
                        && result.get("volumeDisplay").getAsString().equals("-9 dB"),
                "early pan survives rebind and metadata is refreshed from current getters, not predecessor cache");
        assertTrue(
                f.tracks[2].child("name").writes == 1
                        && result.getAsJsonObject("fields")
                                .getAsJsonObject("name")
                                .get("verification")
                                .getAsString()
                                .equals("observer_after_write"),
                "early equal name is readable but cannot suppress the new generation's explicit naming write");
        JsonObject effect = f.call(module, "tracks.get", HostFixture.target(HostFixture.C));
        assertTrue(
                effect.get("name").getAsString().equals("Shifted Effect")
                        && effect.get("index").getAsInt() == 3,
                "shifted preexisting track recovers early name and unchanged position without replay");
        assertTrue(
                f.tracks[3].child("name").writes == 0 && f.tracks[4].child("name").writes == 0,
                "factory recovery never names shifted Effect or Master");
    }

    @Test
    void reopenedSourcesWaitForFlushAndIdentityAgreement() {
        for (boolean sameUuid : new boolean[] {true, false}) {
            HostFixture f = new HostFixture();
            MixModule module = new MixModule(f.services);
            String id = sameUuid ? HostFixture.A : HostFixture.C;
            f.tracks[0].child("exists").emit(false);
            module.flush(); // Closing a project is not a valid readable binding.
            f.tracks[0].child("name").emit("Reopened");
            f.tracks[0].child("channelId").data = id;
            f.tracks[0].child("exists").data = true;
            module.flush(); // Getters alone cannot replace observed identity/existence.
            JsonObject incomplete = f.call(module, "tracks.get", HostFixture.target(id));
            assertTrue(
                    incomplete.get("name").isJsonNull()
                            && incomplete.get("index").isJsonNull(),
                    "flush cannot expose a reopened getter before observer identity/existence agree");
            if (!sameUuid) {
                f.tracks[0].child("exists").emit(true);
                module.flush();
                JsonObject wrongIdentity = f.call(module, "tracks.get", HostFixture.target(id));
                assertTrue(
                        wrongIdentity.get("name").isJsonNull(),
                        "existence agreement alone cannot validate the wrong observed UUID");
            }
            f.tracks[0].child("channelId").emit(id);
            f.tracks[0].child("exists").emit(true);
            JsonObject pending = f.call(module, "tracks.get", HostFixture.target(id));
            assertTrue(
                    pending.get("name").isJsonNull(), "reappearance remains masked until an actual later host flush");
            module.flush();
            JsonObject read = f.call(module, "tracks.get", HostFixture.target(id));
            assertTrue(
                    read.get("name").getAsString().equals("Reopened")
                            && read.get("index").getAsInt() == 0
                            && read.get("panNormalized").getAsDouble() == .5,
                    "same/new UUID reappearance recovers continuous values without unchanged callbacks");
        }
    }

    @Test
    void reboundGenerationNeverAcknowledgesOldWrite() {
        HostFixture f = new HostFixture();
        MixModule module = new MixModule(f.services);
        FakeHost volume = f.tracks[0].child("volume").child("value");
        volume.notifyWrites = false;
        JsonObject p = HostFixture.target(HostFixture.A);
        p.addProperty("volumeNormalized", .7);
        HostOperation old = f.submit(module, "tracks.set", p);
        f.next();
        f.tracks[0].child("exists").emit(false);
        f.tracks[0].child("exists").emit(true);
        module.flush();
        volume.emit(.7);
        f.next();
        JsonObject result = old.result.join();
        assertTrue(
                result.get("completion").getAsString().equals("unknown")
                        && result.get("reason").getAsString().equals("stale_target"),
                "same-UUID rebind plus matching callback cannot acknowledge an old-generation write");
        assertTrue(
                !result.getAsJsonObject("fields")
                        .getAsJsonObject("volumeNormalized")
                        .get("matchesRequested")
                        .getAsBoolean(),
                "reopened source never validates stale write evidence");
    }

    static final String ROOT = "00000000-0000-0000-0000-0000000000ff", G = "00000000-0000-0000-0000-000000000004";

    private static void parent(HostFixture f, int slot, String id) {
        FakeHost parent = f.tracks[slot].child("createParentTrack");
        parent.child("exists").data = id != null;
        parent.child("channelId").data = id == null ? "" : id;
    }

    private static void group(HostFixture f, int slot, String id, int position) {
        FakeHost track = f.tracks[slot];
        track.child("channelId").emit(id);
        track.child("exists").emit(true);
        track.child("trackType").emit("Group");
        track.child("isGroup").data = true;
        track.child("position").emit(position);
    }

    @Test
    void groupedCreationAttributesTopLevelIndex() {
        for (int position : new int[] {-1, 1}) {
            HostFixture f = new HostFixture();
            MixModule module = new MixModule(f.services);
            f.tracks[0].child("trackType").emit("Group");
            f.tracks[0].child("isGroup").data = true;
            parent(f, 0, ROOT);
            parent(f, 1, HostFixture.A);
            f.create = factory -> {
                f.addTrack(2, HostFixture.C, "Audio");
                parent(f, 2, ROOT);
            };
            HostOperation op = f.submit(module, "tracks.create", create("audio", position));
            f.next();
            f.next();
            JsonObject result = op.result.join();
            assertTrue(
                    result.get("matchesRequested").getAsBoolean()
                            && result.get("trackId").getAsString().equals(HostFixture.C),
                    "grouped project: the top-level index counts the group, not its child");
            assertTrue(
                    result.getAsJsonObject("creation")
                            .get("parentScope")
                            .getAsString()
                            .equals("top_level_of_grouped_project"),
                    "grouped creation declares its top-level scope");
            assertTrue(
                    f.tracks[2].child("name").writes == 1 && f.application.actions == 1,
                    "only the observed top-level addition is named");
        }
        HostFixture f = new HostFixture();
        MixModule module = new MixModule(f.services);
        f.tracks[0].child("trackType").emit("Group");
        f.tracks[0].child("isGroup").data = true;
        parent(f, 0, ROOT);
        parent(f, 1, HostFixture.A);
        f.create = factory -> {
            f.addTrack(2, HostFixture.C, "Audio");
            parent(f, 2, ROOT);
        };
        HostOperation op = f.submit(module, "tracks.create", create("audio", 0));
        f.next();
        f.next();
        f.next();
        assertTrue(
                !op.result.isDone() && f.tracks[2].child("name").writes == 0,
                "a top-level track at the wrong index is never named");
        f.services.close();
        f.next();
        assertTrue(
                assertRpcError("unavailable", op).outcome.equals("unknown"),
                "unattributed grouped creation stays unknown");
    }

    @Test
    void groupedCreationInsideAGroupIsNeverNamed() {
        HostFixture f = new HostFixture();
        MixModule module = new MixModule(f.services);
        f.tracks[0].child("trackType").emit("Group");
        f.tracks[0].child("isGroup").data = true;
        parent(f, 0, ROOT);
        parent(f, 1, ROOT);
        f.create = factory -> {
            f.addTrack(2, HostFixture.C, "Audio");
            parent(f, 2, HostFixture.A);
        };
        HostOperation op = f.submit(module, "tracks.create", create("audio", -1));
        f.next();
        f.next();
        f.next();
        assertTrue(
                !op.result.isDone() && f.tracks[2].child("name").writes == 0,
                "a new track observed inside a group is not the requested top-level track");
        f.services.close();
        f.next();
        assertTrue(assertRpcError("unavailable", op).outcome.equals("unknown"), "it remains an unknown creation");
    }

    @Test
    void trackTreeResolvesParentsAndRejectsInconsistentOrder() {
        HostFixture f = new HostFixture();
        TrackTree flat = TrackTree.capture(f.services);
        assertTrue(
                flat.complete
                        && !flat.groups
                        && flat.byId.get(HostFixture.B).topLevel
                        && flat.byId.get(HostFixture.B).depth == 0,
                "without groups every track is top-level and no parent observation is needed");
        f.tracks[0].child("trackType").emit("Group");
        f.tracks[0].child("isGroup").data = true;
        TrackTree unobserved = TrackTree.capture(f.services);
        assertTrue(
                !unobserved.complete && "parent_observation_missing".equals(unobserved.reason),
                "groups without parent observations are incomplete");
        parent(f, 0, ROOT);
        parent(f, 1, HostFixture.A);
        TrackTree tree = TrackTree.capture(f.services);
        TrackTree.Node child = tree.byId.get(HostFixture.B);
        assertTrue(
                tree.complete && HostFixture.A.equals(child.parentId) && child.depth == 1 && !child.topLevel,
                "child resolves to its group");
        JsonObject row = new JsonObject();
        tree.describe(row, child);
        assertTrue(
                row.get("parentId").getAsString().equals(HostFixture.A)
                        && row.get("depth").getAsInt() == 1,
                "tracks.list row carries parentId/depth");
        JsonObject groupRow = new JsonObject();
        tree.describe(groupRow, tree.byId.get(HostFixture.A));
        assertTrue(
                groupRow.get("isGroup").getAsBoolean()
                        && groupRow.get("parentId").isJsonNull()
                        && groupRow.get("depth").getAsInt() == 0,
                "group row is top-level");
        f.addTrack(2, HostFixture.C, "Audio");
        parent(f, 2, HostFixture.B);
        TrackTree bad = TrackTree.capture(f.services);
        assertTrue(
                !bad.complete && "parent_not_a_group_in_window".equals(bad.reason),
                "a parent that is not a group in the window fails closed");
        parent(f, 2, ROOT);
        f.tracks[2].child("position").emit(1);
        f.tracks[1].child("position").emit(2);
        TrackTree order = TrackTree.capture(f.services);
        assertTrue(
                !order.complete && "parent_order_inconsistent".equals(order.reason),
                "a top-level track between a group and its child breaks pre-order");
    }

    static final String D = "00000000-0000-0000-0000-000000000005",
            E = "00000000-0000-0000-0000-000000000006",
            FX = "00000000-0000-0000-0000-000000000007",
            MASTER = "00000000-0000-0000-0000-000000000008",
            ELSEWHERE = "00000000-0000-0000-0000-0000000000ee";
    /**
     * The 6.1.4 native observation after tracks.group (0fcbad3): three top-level tracks, the new group, its two
     * children (parent proxy = the group), an effect track and Master. Only the group's own parent proxy is odd.
     */
    private static HostFixture observedGroupLayout(String groupParent) {
        HostFixture f = new HostFixture();
        f.tracks[0].child("trackType").emit("Instrument");
        parent(f, 0, ROOT);
        parent(f, 1, ROOT);
        f.addTrack(2, HostFixture.C, "Instrument");
        parent(f, 2, ROOT);
        group(f, 3, G, 3);
        parent(f, 3, groupParent);
        f.addTrack(4, D, "Audio");
        parent(f, 4, G);
        f.addTrack(5, E, "Audio");
        parent(f, 5, G);
        f.addTrack(6, FX, "Effect");
        parent(f, 6, ROOT);
        f.addTrack(7, MASTER, "Master");
        parent(f, 7, null);
        return f;
    }

    @Test
    void trackTreeInfersGroupWhoseParentProxyIsUninformative() {
        for (String raw : new String[] {G, ELSEWHERE}) {
            TrackTree tree = TrackTree.capture(observedGroupLayout(raw).services);
            TrackTree.Node group = tree.byId.get(G);
            assertTrue(
                    tree.complete && group.topLevel && group.depth == 0 && group.parentInferred,
                    "top-level group resolves from pre-order: " + raw);
            assertTrue(
                    tree.byId.get(D).depth == 1 && G.equals(tree.byId.get(E).parentId) && tree.byId.get(FX).depth == 0,
                    "children and effect track resolve");
            JsonObject row = new JsonObject();
            tree.describe(row, group);
            assertTrue(
                    row.get("parentId").isJsonNull()
                            && row.get("depth").getAsInt() == 0
                            && row.get("parentResolved").getAsBoolean()
                            && row.get("parentSource").getAsString().equals("preorder_inference")
                            && row.get("parentObserved").getAsString().equals(raw),
                    "tracks.list row shows depth 0, the inference and the raw proxy value");
            JsonObject child = new JsonObject();
            tree.describe(child, tree.byId.get(D));
            assertTrue(
                    !child.has("parentSource") && !child.has("parentObserved"),
                    "observed parents carry no inference fields");
        }
        // Group first in the window and naming itself: the root comes from the first top-level non-group track.
        HostFixture first = new HostFixture();
        group(first, 0, G, 0);
        parent(first, 0, G);
        first.addTrack(1, D, "Audio");
        parent(first, 1, G);
        first.tracks[1].child("position").emit(1);
        first.addTrack(2, HostFixture.C, "Audio");
        parent(first, 2, ROOT);
        TrackTree leading = TrackTree.capture(first.services);
        assertTrue(
                leading.complete
                        && leading.byId.get(G).topLevel
                        && G.equals(leading.byId.get(D).parentId)
                        && leading.byId.get(HostFixture.C).topLevel,
                "a leading self-reporting group never becomes the root of its own children");
        // Leading group naming an id outside the window: the root still comes from the later top-level track.
        HostFixture foreign = new HostFixture();
        group(foreign, 0, G, 0);
        parent(foreign, 0, ELSEWHERE);
        foreign.addTrack(1, D, "Audio");
        parent(foreign, 1, G);
        foreign.addTrack(2, E, "Audio");
        parent(foreign, 2, G);
        foreign.addTrack(3, HostFixture.C, "Audio");
        parent(foreign, 3, ROOT);
        TrackTree outside = TrackTree.capture(foreign.services);
        assertTrue(
                outside.complete
                        && outside.byId.get(G).depth == 0
                        && outside.byId.get(HostFixture.C).topLevel
                        && outside.byId.get(E).depth == 1,
                "a leading group naming a foreign id never becomes the root");
        // Two groups sharing that odd value and no top-level non-group track: the nested group is not made top-level.
        HostFixture odd = new HostFixture();
        group(odd, 0, G, 0);
        parent(odd, 0, ELSEWHERE);
        group(odd, 1, D, 1);
        parent(odd, 1, ELSEWHERE);
        odd.addTrack(2, E, "Audio");
        parent(odd, 2, D);
        TrackTree noRoot = TrackTree.capture(odd.services);
        assertTrue(
                !noRoot.complete && "group_parent_ambiguous".equals(noRoot.reason) && !noRoot.byId.get(D).topLevel,
                "without a root observation a group after a group is never guessed top-level");
        // After another group's child, the group could be that child's sibling or top-level: never guessed.
        HostFixture nested = observedGroupLayout(G);
        group(nested, 2, HostFixture.C, 2);
        parent(nested, 2, ROOT);
        nested.tracks[3].child("position").emit(4);
        nested.tracks[4].child("position").emit(3);
        parent(nested, 4, HostFixture.C);
        TrackTree ambiguous = TrackTree.capture(nested.services);
        assertTrue(
                !ambiguous.complete
                        && "group_parent_ambiguous".equals(ambiguous.reason)
                        && ambiguous.byId.get(G).depth < 0,
                "ambiguous group parent fails closed");
        // A group naming a non-group track in the window is inconsistent, not inferred.
        TrackTree wrong = TrackTree.capture(observedGroupLayout(HostFixture.C).services);
        assertTrue(
                !wrong.complete && "parent_not_a_group_in_window".equals(wrong.reason),
                "a group whose parent is a plain track fails closed");
        TrackTree missing = TrackTree.capture(observedGroupLayout(null).services);
        assertTrue(
                !missing.complete && "parent_observation_missing".equals(missing.reason),
                "a missing group parent observation fails closed");
    }

    @Test
    void groupObservedWhenTheGroupParentProxyNamesItself() {
        HostFixture f = new HostFixture();
        FakeGroup access = new FakeGroup();
        MixModule module = new MixModule(f.services, access);
        f.tracks[0].child("trackType").emit("Instrument");
        f.addTrack(2, D, "Audio");
        f.addTrack(3, E, "Audio");
        f.addTrack(4, FX, "Effect");
        f.addTrack(5, MASTER, "Master");
        access.effect = () -> {
            // Native shape: the group takes the first child's slot, the children shift down, Master moves out of slot
            // 5.
            group(f, 2, G, 2);
            parent(f, 2, G);
            f.addTrack(3, D, "Audio");
            f.addTrack(4, E, "Audio");
            f.addTrack(5, FX, "Effect");
            f.addTrack(6, MASTER, "Master");
            parent(f, 0, ROOT);
            parent(f, 1, ROOT);
            parent(f, 3, G);
            parent(f, 4, G);
            parent(f, 5, ROOT);
            parent(f, 6, null);
        };
        JsonObject p = groupRequest(D, E);
        p.addProperty("name", "T: Grp");
        HostOperation op = f.submit(module, "tracks.group", p);
        while (!op.result.isDone() && !f.tasks.isEmpty()) {
            module.flush();
            f.next();
        }
        JsonObject result = op.result.join();
        assertTrue(
                result.get("status").getAsString().equals("observed")
                        && result.get("trackId").getAsString().equals(G)
                        && f.tracks[2].child("name").data.equals("T: Grp"),
                "observed native grouping succeeds and names the group");
        assertTrue(module.unresolved().isEmpty(), "no quarantine after the observed grouping");
    }

    private static JsonObject groupRequest(String... ids) {
        JsonObject p = new JsonObject();
        JsonArray a = new JsonArray();
        for (String id : ids) a.add(id);
        p.add("trackIds", a);
        return p;
    }

    @Test
    void groupValidationRejectsBeforeScheduling() {
        HostFixture f = new HostFixture();
        MixModule module = new MixModule(f.services);
        List<JsonObject> invalid = new java.util.ArrayList<>(List.of(
                groupRequest(),
                groupRequest("not-a-uuid"),
                groupRequest(HostFixture.A, HostFixture.A),
                groupRequest(HostFixture.A, HostFixture.A.toUpperCase())));
        JsonObject many = groupRequest();
        for (int i = 0; i < HostServices.TRACKS + 1; i++)
            many.getAsJsonArray("trackIds").add(String.format("00000000-0000-0000-0000-%012d", i));
        invalid.add(many);
        JsonObject named = groupRequest(HostFixture.A);
        named.addProperty("name", "");
        invalid.add(named);
        JsonObject extra = groupRequest(HostFixture.A);
        extra.addProperty("position", 0);
        invalid.add(extra);
        for (JsonObject p : invalid) {
            assertRpcError("invalid_params", f.submit(module, "tracks.group", p));
            assertTrue(f.tasks.isEmpty(), "invalid tracks.group rejected before scheduling: " + p);
        }
        MixModule.validateParams("tracks.group", groupRequest(HostFixture.A, HostFixture.B));
    }

    static final class FakeGroup implements MixModule.GroupAccess {
        Runnable effect = () -> {};
        int calls;
        List<String> ids;

        public void execute(Runnable work) {
            work.run();
        }

        public JsonObject group(List<Object> proxies, List<String> ids, Runnable check, Runnable before) {
            calls++;
            this.ids = ids;
            check.run();
            before.run();
            effect.run();
            JsonObject out = new JsonObject();
            out.addProperty("nativeOperation", "track_group.group_tracks");
            return out;
        }
    }

    @Test
    void groupVerifiesMembershipAndNamesTheGroup() {
        HostFixture f = new HostFixture();
        FakeGroup access = new FakeGroup();
        MixModule module = new MixModule(f.services, access);
        access.effect = () -> {
            group(f, 2, G, 0);
            f.tracks[0].child("position").emit(1);
            f.tracks[1].child("position").emit(2);
            f.bank.child("itemCount").emit(3);
            parent(f, 2, ROOT);
            parent(f, 0, G);
            parent(f, 1, G);
        };
        JsonObject p = groupRequest(HostFixture.B, HostFixture.A);
        p.addProperty("name", "Drums");
        HostOperation op = f.submit(module, "tracks.group", p);
        f.next();
        f.next();
        assertTrue(!op.result.isDone(), "one membership observation is not enough");
        f.next();
        JsonObject result = op.result.join();
        assertTrue(
                access.ids.equals(List.of(HostFixture.A, HostFixture.B)),
                "tracks are passed in flat order, not request order");
        assertTrue(
                result.get("trackId").getAsString().equals(G)
                        && result.get("matchesRequested").getAsBoolean(),
                "the new group is identified and named");
        JsonObject info = result.getAsJsonObject("group");
        assertTrue(
                info.get("verification").getAsString().startsWith("unique_added_group_uuid")
                        && info.getAsJsonArray("children").size() == 2
                        && info.get("parentId").isJsonNull(),
                "group evidence lists children and parent");
        assertTrue(
                f.tracks[2].child("name").data.equals("Drums") && f.tracks[0].child("name").writes == 0,
                "only the group is renamed");
        assertTrue(module.unresolved().isEmpty(), "observed grouping releases its quarantine");
        HostFixture plain = new HostFixture();
        FakeGroup quiet = new FakeGroup();
        MixModule unnamed = new MixModule(plain.services, quiet);
        quiet.effect = () -> {
            group(plain, 2, G, 0);
            plain.tracks[0].child("position").emit(1);
            plain.tracks[1].child("position").emit(2);
            plain.bank.child("itemCount").emit(3);
            parent(plain, 2, ROOT);
            parent(plain, 0, G);
            parent(plain, 1, ROOT);
        };
        HostOperation one = plain.submit(unnamed, "tracks.group", groupRequest(HostFixture.A));
        plain.next();
        plain.next();
        plain.next();
        JsonObject single = one.result.join();
        assertTrue(
                single.get("completion").getAsString().equals("observed")
                        && !single.has("fields")
                        && plain.tracks[2].child("name").writes == 0,
                "without name the observed group is returned unnamed");
    }

    @Test
    void groupRefusesMixedParentsEffectTracksAndRecording() {
        HostFixture f = new HostFixture();
        FakeGroup access = new FakeGroup();
        MixModule module = new MixModule(f.services, access);
        f.tracks[0].child("trackType").emit("Group");
        f.tracks[0].child("isGroup").data = true;
        f.addTrack(2, HostFixture.C, "Audio");
        parent(f, 0, ROOT);
        parent(f, 1, HostFixture.A);
        parent(f, 2, ROOT);
        HostOperation mixed = f.submit(module, "tracks.group", groupRequest(HostFixture.B, HostFixture.C));
        f.next();
        assertTrue(
                assertRpcError("unsupported_scope", mixed).outcome.equals("not_applied") && access.calls == 0,
                "different parents are refused before native access");
        f.tracks[2].child("trackType").emit("Effect");
        HostOperation effect = f.submit(module, "tracks.group", groupRequest(HostFixture.C));
        f.next();
        assertTrue(
                assertRpcError("unsupported_scope", effect).outcome.equals("not_applied") && access.calls == 0,
                "effect tracks are refused");
        HostOperation outside = f.submit(module, "tracks.group", groupRequest("00000000-0000-0000-0000-0000000000aa"));
        f.next();
        assertTrue(
                assertRpcError("outside_window", outside).outcome.equals("not_applied"),
                "unknown track is outside the window");
        f.services.selectionOwner = new HostOperation(Long.MAX_VALUE);
        HostOperation leased = f.submit(module, "tracks.group", groupRequest(HostFixture.B));
        f.next();
        assertTrue(
                assertRpcError("busy", leased).outcome.equals("not_applied") && access.calls == 0,
                "a held selection lease blocks grouping");
        f.services.selectionOwner = null;
        f.transport.child("isArrangerRecordEnabled").emit(true);
        HostOperation recording = f.submit(module, "tracks.group", groupRequest(HostFixture.B));
        f.next();
        assertTrue(
                assertRpcError("unsafe_state", recording).outcome.equals("not_applied") && access.calls == 0,
                "recording blocks grouping");
    }

    @Test
    void unobservedGroupQuarantinesCreationAndGrouping() {
        HostFixture f = new HostFixture();
        FakeGroup access = new FakeGroup();
        MixModule module = new MixModule(f.services, access);
        HostOperation op = f.submit(module, "tracks.group", groupRequest(HostFixture.A), f.tasks.after(400));
        while (!op.result.isDone() && !f.tasks.isEmpty()) f.next();
        JsonObject result = op.result.join();
        assertTrue(
                result.get("completion").getAsString().equals("unknown")
                        && result.get("reason").getAsString().equals("group_membership_not_observed"),
                "an unobserved native grouping is unknown, never success");
        assertTrue(!module.unresolved().isEmpty(), "unknown grouping is listed for extension.restart");
        HostOperation create = f.submit(module, "tracks.create", create("audio", -1));
        f.next();
        assertTrue(
                assertRpcError("unresolved_creation", create).outcome.equals("not_applied")
                        && f.application.actions == 0,
                "creation is quarantined after unknown grouping");
        HostOperation again = f.submit(module, "tracks.group", groupRequest(HostFixture.A));
        f.next();
        assertTrue(
                assertRpcError("unresolved_creation", again).outcome.equals("not_applied") && access.calls == 1,
                "grouping is never retried");
    }

    private static JsonObject create(String type, int position) {
        JsonObject p = new JsonObject();
        p.addProperty("type", type);
        p.addProperty("position", position);
        p.addProperty("name", "Created");
        return p;
    }
}
