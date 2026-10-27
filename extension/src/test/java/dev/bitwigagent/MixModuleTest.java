package dev.bitwigagent;

import com.google.gson.*;
import java.util.List;

/** Behavioral fake-host tests; no claim about audio, routing, or native factory completion. */
public final class MixModuleTest {
    private static int assertions;
    public static void main(String[] args) {
        readsExposeNormalizedUnitsAndSends();
        invalidRequestsNeverSchedule();
        writesOnlyExplicitUuidAndFields();
        selectionWithoutReplayedCallbackIsDispatchOnly();
        selectionUsesUuidNotCurrentUi();
        selectionRespectsOperationAndIdentityGuards();
        requiresMatchingFreshTargetObservation();
        targetChangePreventsRemainingWrites();
        expiredAndCancelledWritesAreNotApplied();
        creationNamesOnlyUniqueObservedAddition();
        ambiguousCreationStaysUnknownAndIncompleteBaselineDoesNotDispatch();
        groupedBaselineRejectsWithoutMutationOrQuarantine();
        concurrentGroupAppearanceRemainsUnknownAndQuarantined();
        wrongTypeOrPositionCannotBeNamed();
        creationNeverNamesAfterCancellation();
        replacementDoesNotReuseReadiness();
        firstIdentityObservationPreservesStartupValues();
        unresolvedFactoryQuarantinesLaterCreation();
        placeholderStartupPreservesNativeStyleBaseline();
        missingCreationMetadataRejectsBeforeFactory();
        absoluteWritesBypassControllerPickup();
        creationSurvivesNativeBankShiftWithoutValueReplay();
        reopenedSourcesWaitForFlushAndIdentityAgreement();
        reboundGenerationNeverAcknowledgesOldWrite();
        System.out.println("MixModuleTest: " + assertions + " assertions passed (simulated host only)");
    }
    private static void readsExposeNormalizedUnitsAndSends() {
        TransportMixFixture f = new TransportMixFixture(); MixModule module = new MixModule(f.services);
        JsonObject result = f.call(module, "tracks.get", TransportMixFixture.target(TransportMixFixture.A));
        check(result.get("volumeNormalized").getAsDouble() == .5, "actual normalized volume");
        check(result.get("volumeDisplay").getAsString().equals("-12 dB"), "host display retained separately from normalized value");
        check(result.getAsJsonObject("units").get("volumeNormalized").getAsString().equals("normalized_0_1_not_db"), "normalized is never falsely named dB");
        JsonObject send = result.getAsJsonArray("sends").get(0).getAsJsonObject();
        check(send.get("valueNormalized").getAsDouble() == .25 && send.get("display").getAsString().equals("-18 dB"), "send value/display are distinct");
        check(send.get("enabled").getAsBoolean() && !send.get("preFader").getAsBoolean(), "send metadata observed");
        check(result.getAsJsonObject("window").get("capacity").getAsInt() == 16 && result.getAsJsonObject("sendWindow").get("capacity").getAsInt() == 8, "bounded observation scope explicit");
        check(f.bankCreations == 1, "mix never creates a duplicate track bank");
    }
    private static void invalidRequestsNeverSchedule() {
        TransportMixFixture f = new TransportMixFixture(); MixModule module = new MixModule(f.services);
        for (String text : List.of("{\"volumeNormalized\":-0.1}", "{\"panNormalized\":1.1}", "{\"mute\":1}", "{\"arm\":null}", "{\"name\":\"\"}", "{\"extra\":true}", "{}")) {
            JsonObject p = TransportMixFixture.json(text); p.addProperty("trackId", TransportMixFixture.A);
            TransportMixFixture.error(f.submit(module, "tracks.set", p), "invalid_params");
            check(f.tasks.isEmpty(), "invalid mix field rejected before scheduling");
        }
        for (String text : List.of("{\"type\":\"group\",\"position\":0,\"name\":\"X\"}", "{\"type\":\"audio\",\"position\":-2,\"name\":\"X\"}",
                "{\"type\":\"effect\",\"position\":0.5,\"name\":\"X\"}", "{\"type\":\"instrument\",\"position\":0}",
                "{\"type\":\"audio\",\"position\":2147483648,\"name\":\"X\"}")) {
            TransportMixFixture.error(f.submit(module, "tracks.create", TransportMixFixture.json(text)), "invalid_params");
            check(f.tasks.isEmpty(), "invalid create combination rejected before scheduling");
        }
    }
    private static void writesOnlyExplicitUuidAndFields() {
        TransportMixFixture f = new TransportMixFixture(); MixModule module = new MixModule(f.services);
        JsonObject p = TransportMixFixture.target(TransportMixFixture.B); p.addProperty("volumeNormalized", .75); p.addProperty("mute", true);
        JsonObject result = f.call(module, "tracks.set", p);
        check(result.get("matchesRequested").getAsBoolean(), "requested mix observed");
        check(f.tracks[1].child("volume").child("value").writes == 1 && f.tracks[1].child("mute").writes == 1, "only UUID B receives writes");
        check(f.tracks[0].child("volume").child("value").writes == 0 && f.tracks[0].child("mute").writes == 0, "UUID A untouched");
        for (String key : List.of("name", "pan", "solo", "arm")) check(f.tracks[1].child(key).writes == 0, "omitted field unchanged: " + key);
        JsonObject again = f.call(module, "tracks.set", p);
        check(again.getAsJsonObject("fields").getAsJsonObject("mute").get("verification").getAsString().equals("already_at_value"), "idempotent mix set explicit");
        check(f.tracks[1].child("mute").writes == 1, "no unnecessary second mute write");
        HostOperation absent = f.submit(module, "tracks.set", TransportMixFixture.json("{\"trackId\":\"outside\",\"mute\":true}")); f.next();
        TransportMixFixture.error(absent, "outside_window"); check(f.tracks[0].child("mute").writes == 0, "absent UUID never falls back to selected track");
    }
    private static void selectionUsesUuidNotCurrentUi() {
        TransportMixFixture f = new TransportMixFixture(); MixModule module = new MixModule(f.services);
        f.tracks[0].child("selected").emit(true);
        JsonObject selected = f.call(module, "tracks.select", TransportMixFixture.target(TransportMixFixture.B));
        check(selected.get("selected").getAsBoolean() && selected.get("completion").getAsString().equals("not_observed"),
                "a selection callback is snapshot evidence, not UI-navigation completion");
        check(f.tracks[1].actions == 1 && f.tracks[0].actions == 0, "select operates on requested bank proxy, not current UI cursor");
        JsonObject again = f.call(module, "tracks.select", TransportMixFixture.target(TransportMixFixture.B));
        check(f.tracks[1].actions == 2 && again.get("completion").getAsString().equals("not_observed"),
                "an already-selected snapshot does not suppress an explicit editor-navigation command");
    }
    private static void selectionWithoutReplayedCallbackIsDispatchOnly() {
        TransportMixFixture f = new TransportMixFixture(); MixModule module = new MixModule(f.services);
        f.tracks[0].child("selected").emit(true);
        f.tracks[0].child("exists").emit(false);
        f.tracks[0].child("exists").emit(true);
        module.flush();
        f.tracks[0].child("selected").emitWrites = false;
        HostOperation op = f.submit(module, "tracks.select", TransportMixFixture.target(TransportMixFixture.A));
        f.next();
        check(op.result.isDone(), "selection navigation must not wait for an optional legacy callback");
        JsonObject result = op.result.join();
        check(result.get("completion").getAsString().equals("not_observed") && result.get("status").getAsString().equals("dispatched"),
                "missing selection callback yields a UI dispatch receipt, never an observed completion");
        check(result.get("selected").isJsonNull() && !result.getAsJsonObject("readable").get("selected").getAsBoolean(),
                "reopened track cannot inherit the previous generation's selected value");
        check(!result.has("fields") && !result.has("matchesRequested"), "UI navigation does not pretend to be a verified mix setter");
        check(f.tracks[0].actions == 1 && f.tasks.isEmpty(), "selection dispatches once without deferred retries or callback polling");
    }
    private static void selectionRespectsOperationAndIdentityGuards() {
        TransportMixFixture f = new TransportMixFixture(); MixModule module = new MixModule(f.services);
        JsonObject p = TransportMixFixture.target(TransportMixFixture.A);
        HostOperation expired = f.submit(module, "tracks.select", p, System.nanoTime() - 1); f.next();
        check(TransportMixFixture.error(expired, "deadline_exceeded").outcome.equals("not_applied"),
                "expired selection is rejected before dispatch");
        HostOperation cancelled = f.submit(module, "tracks.select", p); cancelled.result.cancel(false); f.next();
        f.tracks[0].child("exists").emit(false); f.tracks[0].child("exists").emit(true);
        HostOperation unready = f.submit(module, "tracks.select", p); f.next();
        check(TransportMixFixture.error(unready, "not_ready").outcome.equals("not_applied") && f.tracks[0].actions == 0,
                "cancelled or rebound-before-flush selection cannot reach the host");
        module.flush();
        f.tracks[0].child("selected").afterWrite = () -> f.tracks[0].child("channelId").emit(TransportMixFixture.C);
        HostOperation shifted = f.submit(module, "tracks.select", p); f.next();
        check(TransportMixFixture.error(shifted, "stale_target").outcome.equals("unknown") && f.tracks[0].actions == 1,
                "identity loss after dispatch remains unknown, not an accepted dispatch-only receipt");
    }
    private static void requiresMatchingFreshTargetObservation() {
        TransportMixFixture f = new TransportMixFixture(); MixModule module = new MixModule(f.services);
        TransportMixFixture.Node volume = f.tracks[1].child("volume").child("value"); volume.emitWrites = false;
        JsonObject p = TransportMixFixture.target(TransportMixFixture.B); p.addProperty("volumeNormalized", .75);
        HostOperation op = f.submit(module, "tracks.set", p); f.next();
        check(!op.result.isDone(), "changed getter without callback cannot verify write");
        f.tracks[0].child("volume").child("value").emit(.75); f.next();
        check(!op.result.isDone(), "another track's callback cannot verify target");
        volume.emit(.6); f.next(); check(!op.result.isDone(), "fresh mismatch cannot verify target");
        volume.emit(.75); f.next();
        check(op.result.join().get("matchesRequested").getAsBoolean() && volume.writes == 1, "fresh matching callback verifies without replay");
    }
    private static void targetChangePreventsRemainingWrites() {
        TransportMixFixture f = new TransportMixFixture(); MixModule module = new MixModule(f.services);
        f.tracks[0].child("name").afterWrite = () -> f.tracks[0].child("channelId").emit(TransportMixFixture.C);
        JsonObject p = TransportMixFixture.target(TransportMixFixture.A); p.addProperty("name", "Renamed"); p.addProperty("volumeNormalized", .8);
        JsonObject result = f.call(module, "tracks.set", p);
        check(result.get("status").getAsString().equals("dispatched") && result.get("completion").getAsString().equals("unknown"), "partial mutation with shifted target is unknown");
        check(result.get("reason").getAsString().equals("stale_target"), "target change identified");
        check(f.tracks[0].child("volume").child("value").writes == 0, "replacement track never receives remaining volume write");
        check(result.getAsJsonObject("fields").getAsJsonObject("name").get("observed").isJsonNull(), "replacement's state not misreported as target readback");
    }
    private static void expiredAndCancelledWritesAreNotApplied() {
        TransportMixFixture f = new TransportMixFixture(); MixModule module = new MixModule(f.services);
        JsonObject p = TransportMixFixture.target(TransportMixFixture.A); p.addProperty("arm", true);
        HostOperation expired = f.submit(module, "tracks.set", p, System.nanoTime() - 1); f.next();
        check(TransportMixFixture.error(expired, "deadline_exceeded").outcome.equals("not_applied"), "expired target setter not applied");
        HostOperation cancelled = f.submit(module, "tracks.set", p); cancelled.result.cancel(false); f.next();
        check(f.tracks[0].child("arm").writes == 0, "queued callbacks after deadline/cancel cannot arm");
        JsonObject partial = TransportMixFixture.target(TransportMixFixture.A);
        partial.addProperty("name", "First"); partial.addProperty("arm", true);
        HostOperation during = f.submit(module, "tracks.set", partial);
        f.tracks[0].child("name").afterWrite = () -> during.result.cancel(false);
        f.next();
        check(during.result.isCancelled() && f.tracks[0].child("name").writes == 1, "cancellation can occur after the first mutation");
        check(f.tracks[0].child("arm").writes == 0, "per-field cancellation guard prevents late arming");
    }
    private static void creationNamesOnlyUniqueObservedAddition() {
        for (String type : List.of("audio", "instrument", "effect")) verifyCreation(type);
    }
    private static void verifyCreation(String type) {
        TransportMixFixture f = new TransportMixFixture(); MixModule module = new MixModule(f.services);
        f.create = factory -> {
            String hostType = Character.toUpperCase(type.charAt(0)) + type.substring(1);
            check(factory.equals("create" + hostType + "Track"), "requested type chooses exact public factory");
            f.addTrack(2, TransportMixFixture.C, hostType);
        };
        HostOperation op = f.submit(module, "tracks.create", create(type, -1)); f.next();
        check(f.tracks[2].child("name").writes == 0 && !op.result.isDone(), "void factory is not immediate completion or slot naming");
        f.next(); JsonObject result = op.result.join();
        check(result.get("trackId").getAsString().equals(TransportMixFixture.C), "new unique UUID selected by difference");
        check(result.getAsJsonObject("creation").get("verification").getAsString().equals("unique_added_uuid_count_type_position"), "creation evidence declared");
        check(f.tracks[2].child("name").writes == 1 && f.tracks[2].child("name").data.equals("Created"), "only unique observed target named");
        check(f.tracks[0].child("name").writes == 0 && f.tracks[1].child("name").writes == 0, "preexisting tracks not renamed");
        check(f.application.actions == 1, "factory not retried");
    }
    private static void ambiguousCreationStaysUnknownAndIncompleteBaselineDoesNotDispatch() {
        TransportMixFixture ambiguous = new TransportMixFixture(); MixModule module = new MixModule(ambiguous.services);
        ambiguous.create = factory -> { ambiguous.addTrack(2, TransportMixFixture.C, "Audio"); ambiguous.addTrack(3, "extra-track", "Audio"); };
        JsonObject result = ambiguous.call(module, "tracks.create", create("audio", -1));
        check(result.get("completion").getAsString().equals("unknown") && !result.get("nameApplied").getAsBoolean(), "multiple additions never claim creation completion");
        check(ambiguous.tracks[2].child("name").writes == 0 && ambiguous.tracks[3].child("name").writes == 0, "ambiguous tracks never renamed");
        TransportMixFixture outside = new TransportMixFixture(); MixModule other = new MixModule(outside.services);
        outside.bank.child("itemCount").emit(20);
        HostOperation rejected = outside.submit(other, "tracks.create", create("effect", -1)); outside.next();
        check(TransportMixFixture.error(rejected, "not_ready").outcome.equals("not_applied"), "incomplete16-track baseline rejects before mutation");
        check(outside.application.actions == 0 && !rejected.dispatched, "known unobservable baseline must never invoke a factory");
    }
    private static void groupedBaselineRejectsWithoutMutationOrQuarantine() {
        for (String type : List.of("audio", "instrument", "effect")) {
            TransportMixFixture f = new TransportMixFixture(); MixModule module = new MixModule(f.services);
            f.tracks[0].child("trackType").emit("Group");
            JsonObject beforeA = f.call(module, "tracks.get", TransportMixFixture.target(TransportMixFixture.A));
            JsonObject beforeB = f.call(module, "tracks.get", TransportMixFixture.target(TransportMixFixture.B));
            Object count = f.bank.child("itemCount").data;
            String hostType = Character.toUpperCase(type.charAt(0)) + type.substring(1);
            f.create = factory -> f.addTrack(2, TransportMixFixture.C, hostType);
            for (int position : new int[]{-1, 0}) {
                HostOperation rejected = f.submit(module, "tracks.create", create(type, position)); f.next();
                check(TransportMixFixture.error(rejected, "unsupported_scope").outcome.equals("not_applied"),
                        "known grouped baseline explicitly rejects as not applied");
                check(!rejected.dispatched && f.application.actions == 0,
                        "known grouped baseline never dispatches any native track factory");
                check(count.equals(f.bank.child("itemCount").data) && Boolean.FALSE.equals(f.tracks[2].child("exists").data),
                        "rejected grouped creation leaves project track count and empty slot unchanged");
                check(beforeA.equals(f.call(module, "tracks.get", TransportMixFixture.target(TransportMixFixture.A)))
                                && beforeB.equals(f.call(module, "tracks.get", TransportMixFixture.target(TransportMixFixture.B))),
                        "rejected grouped creation preserves existing track snapshots");
                for (TransportMixFixture.Node track : f.tracks)
                    check(track.child("name").writes == 0, "rejected grouped creation never renames any track");
                check(f.tasks.isEmpty(), "rejection leaves no creation observation task pending");
            }
            f.tracks[0].child("trackType").emit("Instrument");
            HostOperation admitted = f.submit(module, "tracks.create", create(type, -1)); f.next(); f.next();
            check(admitted.result.join().get("matchesRequested").getAsBoolean() && f.application.actions == 1,
                    "grouped pre-dispatch rejection does not quarantine later supported creation");
            check(f.tracks[2].child("name").writes == 1, "later ungrouped creation names only the observed addition");
        }
    }
    private static void concurrentGroupAppearanceRemainsUnknownAndQuarantined() {
        TransportMixFixture f = new TransportMixFixture(); MixModule module = new MixModule(f.services);
        f.create = factory -> {
            f.tracks[0].child("trackType").emit("Group");
            f.addTrack(2, TransportMixFixture.C, "Audio");
        };
        HostOperation op = f.submit(module, "tracks.create", create("audio", -1)); f.next();
        JsonObject result = op.result.join();
        check(op.dispatched && f.application.actions == 1, "concurrent grouping occurs after one factory dispatch");
        check(result.get("status").getAsString().equals("dispatched")
                        && result.get("completion").getAsString().equals("unknown")
                        && result.get("reason").getAsString().equals("grouped_insertion_position_not_observable"),
                "group appearing after dispatch retains structural unknown attribution");
        check(!result.get("nameApplied").getAsBoolean() && result.get("trackId").isJsonNull()
                        && f.tracks[2].child("name").writes == 0,
                "concurrent grouped insertion is never attributed or renamed");
        f.tracks[0].child("trackType").emit("Instrument");
        HostOperation later = f.submit(module, "tracks.create", create("audio", -1)); f.next();
        check(TransportMixFixture.error(later, "unresolved_creation").outcome.equals("not_applied")
                        && !later.dispatched && f.application.actions == 1,
                "concurrent structural unknown retains quarantine even when the group disappears");
    }
    private static void wrongTypeOrPositionCannotBeNamed() {
        for (boolean wrongType : new boolean[]{true, false}) {
            TransportMixFixture f = new TransportMixFixture(); MixModule module = new MixModule(f.services);
            f.create = factory -> f.addTrack(2, TransportMixFixture.C, wrongType ? "Instrument" : "Audio");
            HostOperation op = f.submit(module, "tracks.create", create("audio", wrongType ? -1 : 0)); f.next(); f.next();
            check(!op.result.isDone() && f.tracks[2].child("name").writes == 0, "type/position mismatch is not a nameable creation");
            f.services.close(); f.next();
            check(TransportMixFixture.error(op, "unavailable").outcome.equals("unknown"), "abandoned factory remains unknown, not not_applied");
        }
    }
    private static void creationNeverNamesAfterCancellation() {
        TransportMixFixture f = new TransportMixFixture(); MixModule module = new MixModule(f.services);
        f.create = factory -> f.addTrack(2, TransportMixFixture.C, "Audio");
        HostOperation op = f.submit(module, "tracks.create", create("audio", -1)); f.next();
        op.result.cancel(false); f.next();
        check(f.tracks[2].child("name").writes == 0 && f.application.actions == 1, "late observation after cancelled creation cannot rename or retry");
    }
    private static void replacementDoesNotReuseReadiness() {
        for (boolean replaceUuid : new boolean[]{true, false}) {
            TransportMixFixture f = new TransportMixFixture(); MixModule module = new MixModule(f.services);
            f.tracks[0].child("mute").emit(true); f.tracks[0].child("selected").emit(true);
            String id = replaceUuid ? TransportMixFixture.C : TransportMixFixture.A;
            if (replaceUuid) f.tracks[0].child("channelId").emit(id);
            else { f.tracks[0].child("exists").emit(false); f.tracks[0].child("exists").emit(true); }
            JsonObject read = f.call(module, "tracks.get", TransportMixFixture.target(id));
            check(read.get("mute").isJsonNull() && read.get("selected").isJsonNull(), "replacement cannot inherit previous identity's mix/selection observations");
            check(!read.get("observationsComplete").getAsBoolean() && !read.getAsJsonObject("readable").get("mute").getAsBoolean(), "partial snapshot explicitly declares missing readiness");
            check(read.get("volumeDisplay").isJsonNull() && read.get("sendWindow").isJsonNull(), "replacement cannot inherit display/send metadata");
            JsonObject pendingWrite = TransportMixFixture.target(id); pendingWrite.addProperty("mute", true);
            HostOperation blocked = f.submit(module, "tracks.set", pendingWrite); f.next();
            TransportMixFixture.error(blocked, "not_ready");
            check(f.tracks[0].child("mute").writes == 0, "no mutation before the actual host flush boundary");
            module.flush();
            JsonObject baseline = f.call(module, "tracks.get", TransportMixFixture.target(id));
            check(baseline.get("mute").getAsBoolean() && baseline.get("index").getAsInt() == 0,
                    "continuous unchanged proxy values become readable after flush without callback replay");
            check(baseline.get("selected").isJsonNull(), "legacy selection callback is not fabricated from predecessor state");
            f.tracks[0].child("mute").emitWrites = false;
            JsonObject p = TransportMixFixture.target(id); p.addProperty("mute", true);
            HostOperation op = f.submit(module, "tracks.set", p); f.next();
            check(f.tracks[0].child("mute").writes == 1 && !op.result.isDone(), "unready matching getter must dispatch rather than falsely skip as already_at_value");
            f.tracks[0].child("mute").emit(true); f.next();
            check(op.result.join().get("matchesRequested").getAsBoolean(), "requested field can verify without every unchanged field replaying");
            JsonObject selected = f.call(module, "tracks.select", TransportMixFixture.target(id));
            check(f.tracks[0].actions == 1 && selected.get("completion").getAsString().equals("not_observed"),
                    "rebound selection dispatches explicitly without pretending to verify UI completion");
        }
    }
    private static void firstIdentityObservationPreservesStartupValues() {
        TransportMixFixture f = new TransportMixFixture();
        f.tracks[0].child("channelId").initial = false; f.tracks[0].child("exists").initial = false;
        MixModule module = new MixModule(f.services);
        f.tracks[0].child("channelId").emit(TransportMixFixture.A); f.tracks[0].child("exists").emit(true);
        JsonObject read = f.call(module, "tracks.get", TransportMixFixture.target(TransportMixFixture.A));
        check(read.get("volumeNormalized").getAsDouble() == .5 && read.get("observationsComplete").getAsBoolean(), "first identity callback after fields is not treated as replacement");
    }
    private static void unresolvedFactoryQuarantinesLaterCreation() {
        TransportMixFixture f = new TransportMixFixture(); MixModule module = new MixModule(f.services);
        HostOperation a = f.submit(module, "tracks.create", create("audio", -1)); f.next();
        a.result.cancel(false); f.next();
        JsonObject requestB = create("audio", -1); requestB.addProperty("name", "Must not name A");
        HostOperation b = f.submit(module, "tracks.create", requestB); f.next();
        check(TransportMixFixture.error(b, "unresolved_creation").outcome.equals("not_applied"), "cancelled RPC does not release native factory attribution");
        f.addTrack(2, TransportMixFixture.C, "Audio");
        check(f.application.actions == 1 && f.tracks[2].child("name").writes == 0, "late A addition can never be attributed or named by B");
        HostOperation c = f.submit(module, "tracks.create", requestB); f.next();
        TransportMixFixture.error(c, "unresolved_creation");
        check(f.application.actions == 1, "late observation alone does not silently release quarantine");
        TransportMixFixture unknown = new TransportMixFixture(); MixModule second = new MixModule(unknown.services);
        unknown.create = factory -> { unknown.addTrack(2, TransportMixFixture.C, "Audio"); unknown.addTrack(3, "extra-track", "Audio"); };
        JsonObject dispatched = unknown.call(second, "tracks.create", create("audio", -1));
        check(dispatched.get("completion").getAsString().equals("unknown"), "ambiguous post-dispatch observation produces completed unknown result");
        HostOperation next = unknown.submit(second, "tracks.create", create("audio", -1)); unknown.next();
        TransportMixFixture.error(next, "unresolved_creation");
        check(unknown.application.actions == 1, "completed unknown result also retains quarantine");
    }
    private static void placeholderStartupPreservesNativeStyleBaseline() {
        for (boolean existenceFirst : new boolean[]{true, false}) {
            TransportMixFixture f = new TransportMixFixture();
            String[] ids = {TransportMixFixture.A, TransportMixFixture.B, TransportMixFixture.C, "00000000-0000-0000-0000-000000000004"};
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
            JsonObject read = f.call(module, "tracks.get", TransportMixFixture.target(ids[3]));
            check(read.get("type").getAsString().equals("Master") && read.get("index").getAsInt() == 3, "placeholder-to-first-identity preserves type/index without replay");
            f.create = factory -> {
                f.tracks[2].child("position").emit(3); f.tracks[3].child("position").emit(4);
                f.addTrack(4, "00000000-0000-0000-0000-000000000005", "Instrument");
                f.tracks[4].child("position").emit(2);
            };
            HostOperation created = f.submit(module, "tracks.create", create("instrument", -1)); f.next(); f.next();
            JsonObject result = created.result.join();
            check(result.get("matchesRequested").getAsBoolean() && f.application.actions == 1, "complete native-style baseline supports one instrument factory and observed name");
            check(result.getAsJsonObject("creation").get("observedPosition").getAsInt() == 2, "main insertion ordinal excludes effect/master but full UUID baseline includes both");
        }
    }
    private static void missingCreationMetadataRejectsBeforeFactory() {
        TransportMixFixture f = new TransportMixFixture();
        f.tracks[1].child("trackType").initial = false;
        MixModule module = new MixModule(f.services);
        HostOperation rejected = f.submit(module, "tracks.create", create("audio", -1)); f.next();
        check(TransportMixFixture.error(rejected, "not_ready").outcome.equals("not_applied"), "missing type baseline rejects before dispatch despite complete track count");
        check(f.application.actions == 0 && !rejected.dispatched, "no unnamed native creation on known incomplete readiness");
        f.tracks[1].child("trackType").emit("Audio");
        f.create = factory -> f.addTrack(2, TransportMixFixture.C, "Audio");
        HostOperation created = f.submit(module, "tracks.create", create("audio", -1)); f.next(); f.next();
        check(created.result.join().get("matchesRequested").getAsBoolean(), "pre-dispatch rejection does not quarantine a later fully observed creation");
    }
    private static void absoluteWritesBypassControllerPickup() {
        TransportMixFixture f = new TransportMixFixture(); MixModule module = new MixModule(f.services);
        // SettableRangedValue.set(double) may leave the value untouched under pickup;
        // setImmediately(double) applies an absolute target regardless of takeover mode.
        for (String field : List.of("volume", "pan")) {
            TransportMixFixture.Node value = f.tracks[1].child(field).child("value");
            value.custom = (method, args) -> {
                if (!method.equals("set")) return null;
                f.mutation(); value.writes++;
                return Boolean.TRUE; // No getter change and no observer delivery while pickup waits.
            };
        }
        JsonObject p = TransportMixFixture.target(TransportMixFixture.B);
        p.addProperty("volumeNormalized", .75); p.addProperty("panNormalized", .62);
        HostOperation op = f.submit(module, "tracks.set", p); f.next();
        check(op.result.isDone(), "absolute RPC mix targets apply without crossing a physical pickup point");
        JsonObject result = op.result.join();
        check(result.get("matchesRequested").getAsBoolean(), "pickup does not leave requested pan/volume unobserved");
        for (String field : List.of("volumeNormalized", "panNormalized"))
            check(result.getAsJsonObject("fields").getAsJsonObject(field).get("verification").getAsString().equals("observer_after_write"),
                    "absolute target still requires fresh observer evidence: " + field);
        JsonObject read = f.call(module, "tracks.get", TransportMixFixture.target(TransportMixFixture.B));
        check(read.get("volumeNormalized").getAsDouble() == .75 && read.get("panNormalized").getAsDouble() == .62,
                "subsequent consumer read exposes the changed normalized values");
        check(f.tracks[0].child("volume").child("value").writes == 0 && f.tracks[0].child("pan").child("value").writes == 0,
                "pickup bypass does not change another track");
    }
    private static void creationSurvivesNativeBankShiftWithoutValueReplay() {
        TransportMixFixture f = new TransportMixFixture();
        String master = "00000000-0000-0000-0000-000000000004";
        String added = "00000000-0000-0000-0000-000000000005";
        f.addTrack(2, TransportMixFixture.C, "Effect"); f.addTrack(3, master, "Master");
        MixModule module = new MixModule(f.services);
        f.create = factory -> {
            // Existing slot 2 becomes the new main track. New fields can precede its UUID.
            f.tracks[2].child("name").emit("Created");
            f.tracks[2].child("pan").child("value").emit(.62);
            f.tracks[2].child("channelId").emit(added);
            f.tracks[2].child("trackType").emit("Instrument");
            // The Effect shifts onto the previous Master proxy, with position 3 unchanged.
            f.tracks[3].child("name").emit("Shifted Effect");
            f.tracks[3].child("channelId").emit(TransportMixFixture.C);
            f.tracks[3].child("trackType").emit("Effect");
            f.tracks[2].child("volume").child("displayedValue").data = "-9 dB";
            f.addTrack(4, master, "Master");
        };
        HostOperation op = f.submit(module, "tracks.create", create("instrument", -1)); f.next();
        check(!op.result.isDone() && f.application.actions == 1, "void factory waits for rebound proxy readiness without retry");
        module.flush(); f.next(); f.next();
        check(op.result.isDone(), "unchanged slot positions no longer permanently block complete factory observation");
        JsonObject result = op.result.join();
        check(result.get("matchesRequested").getAsBoolean() && result.get("trackId").getAsString().equals(added),
                "unique factory addition names the correct UUID after native-like callback ordering");
        check(result.get("index").getAsInt() == 2 && result.get("type").getAsString().equals("Instrument")
                && result.get("name").getAsString().equals("Created"), "factory result retains correct type, position and name");
        check(result.get("panNormalized").getAsDouble() == .62 && result.get("volumeDisplay").getAsString().equals("-9 dB"),
                "early pan survives rebind and metadata is refreshed from current getters, not predecessor cache");
        check(f.tracks[2].child("name").writes == 1
                && result.getAsJsonObject("fields").getAsJsonObject("name").get("verification").getAsString().equals("observer_after_write"),
                "early equal name is readable but cannot suppress the new generation's explicit naming write");
        JsonObject effect = f.call(module, "tracks.get", TransportMixFixture.target(TransportMixFixture.C));
        check(effect.get("name").getAsString().equals("Shifted Effect") && effect.get("index").getAsInt() == 3,
                "shifted preexisting track recovers early name and unchanged position without replay");
        check(f.tracks[3].child("name").writes == 0 && f.tracks[4].child("name").writes == 0,
                "factory recovery never names shifted Effect or Master");
    }
    private static void reopenedSourcesWaitForFlushAndIdentityAgreement() {
        for (boolean sameUuid : new boolean[]{true, false}) {
            TransportMixFixture f = new TransportMixFixture(); MixModule module = new MixModule(f.services);
            String id = sameUuid ? TransportMixFixture.A : TransportMixFixture.C;
            f.tracks[0].child("exists").emit(false);
            module.flush(); // Closing a project is not a valid readable binding.
            f.tracks[0].child("name").emit("Reopened");
            f.tracks[0].child("channelId").data = id;
            f.tracks[0].child("exists").data = true;
            module.flush(); // Getters alone cannot replace observed identity/existence.
            JsonObject incomplete = f.call(module, "tracks.get", TransportMixFixture.target(id));
            check(incomplete.get("name").isJsonNull() && incomplete.get("index").isJsonNull(),
                    "flush cannot expose a reopened getter before observer identity/existence agree");
            if (!sameUuid) {
                f.tracks[0].child("exists").emit(true); module.flush();
                JsonObject wrongIdentity = f.call(module, "tracks.get", TransportMixFixture.target(id));
                check(wrongIdentity.get("name").isJsonNull(), "existence agreement alone cannot validate the wrong observed UUID");
            }
            f.tracks[0].child("channelId").emit(id);
            f.tracks[0].child("exists").emit(true);
            JsonObject pending = f.call(module, "tracks.get", TransportMixFixture.target(id));
            check(pending.get("name").isJsonNull(), "reappearance remains masked until an actual later host flush");
            module.flush();
            JsonObject read = f.call(module, "tracks.get", TransportMixFixture.target(id));
            check(read.get("name").getAsString().equals("Reopened") && read.get("index").getAsInt() == 0
                    && read.get("panNormalized").getAsDouble() == .5,
                    "same/new UUID reappearance recovers continuous values without unchanged callbacks");
        }
    }
    private static void reboundGenerationNeverAcknowledgesOldWrite() {
        TransportMixFixture f = new TransportMixFixture(); MixModule module = new MixModule(f.services);
        TransportMixFixture.Node volume = f.tracks[0].child("volume").child("value");
        volume.emitWrites = false;
        JsonObject p = TransportMixFixture.target(TransportMixFixture.A); p.addProperty("volumeNormalized", .7);
        HostOperation old = f.submit(module, "tracks.set", p); f.next();
        f.tracks[0].child("exists").emit(false);
        f.tracks[0].child("exists").emit(true);
        module.flush();
        volume.emit(.7); f.next();
        JsonObject result = old.result.join();
        check(result.get("completion").getAsString().equals("unknown") && result.get("reason").getAsString().equals("stale_target"),
                "same-UUID rebind plus matching callback cannot acknowledge an old-generation write");
        check(!result.getAsJsonObject("fields").getAsJsonObject("volumeNormalized").get("matchesRequested").getAsBoolean(),
                "reopened source never validates stale write evidence");
    }
    private static JsonObject create(String type, int position) {
        JsonObject p = new JsonObject(); p.addProperty("type", type); p.addProperty("position", position); p.addProperty("name", "Created"); return p;
    }
    private static void check(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }
}
