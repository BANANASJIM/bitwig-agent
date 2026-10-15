package dev.bitwigagent;

import com.bitwig.extension.callback.*;
import com.bitwig.extension.controller.api.*;
import com.google.gson.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;

/** Consumer tests against public API proxies; never evidence of native Bitwig behavior. */
public final class DeviceModuleTest {
    private static int assertions;
    private static final String TRACK = "9baa5190-b120-47f9-8e06-0fda476123ca";
    private static final String NATIVE = "11111111-2222-4333-8444-555555555555";

    public static void main(String[] args) {
        strictShapesRejectBeforeScheduling();
        sameNameTargetsStayExplicit();
        bypassNeedsFreshSameSourceObservation();
        targetChangeAndDeadlinesNeverReplay();
        insertionGuardsAndObservedDelta();
        insertionKindsPassExactArguments();
        insertionAmbiguityAndNoopStayUncertain();
        remotesAreSourceBoundAndTokenGuarded();
        remotePageSelectionIsIndependentAndObserved();
        remoteWritesNeedFreshUnchangedSource();
        remoteAbsoluteWritesBypassPickupWithoutBypassingGuards();
        observedSourceReplacementReconcilesAtFlush();
        deviceRenamePreservesRemoteObservations();
        initialRemoteCallbacksSurviveLateIdentityPopulation();
        remoteControlRebindUsesFlushWithoutAcknowledgingWrites();
        initialControlCallbacksSurviveLatePresence();
        pageRoundtripRecoversUnchangedValues();
        sameProcessReopenRecoversUnchangedHistory();
        recoveredPageBaselineInvalidatesLaterTransition();
        pendingRemoteWritesCannotSurviveRebinding();
        flushCannotInventUnobservedOrFiniteValues();
        recoveredSameValueWritesRetainSafetyAndFreshness();
        System.out.println("DeviceModuleTest: " + assertions + " assertions passed (simulated host only)");
    }

    private static void initialRemoteCallbacksSurviveLateIdentityPopulation() {
        for (String order : List.of("device_first", "track_first", "uuid_last")) {
            Fake f = new Fake(true);
            // Remote page/control callbacks already fired once, while every source identity was a default placeholder.
            if (order.equals("track_first")) {
                f.firstTrack.value("channelId").emit(TRACK);
                f.firstTrack.value("exists").emit(true);
            }
            if (order.equals("uuid_last")) {
                f.firstTrack.value("exists").emit(true);
                f.devices[1].value("exists").emit(true);
            }
            f.devices[1].value("position").emit(1);
            f.devices[1].value("deviceType").emit("audio-effect");
            f.devices[1].value("name").emit("Twin");
            f.devices[1].value("exists").emit(true);
            if (!order.equals("track_first")) {
                f.firstTrack.value("channelId").emit(TRACK);
                f.firstTrack.value("exists").emit(true);
            }
            JsonObject initial = f.call("devices.remote.get", target(1));
            check(initial.get("complete").getAsBoolean() && remoteValue(initial) == .6,
                    order + ": first meaningful identity must retain early remote callbacks without replay");
            check(f.call("devices.remote.set", remoteWrite(initial, 1, .8)).get("verification").getAsString()
                    .equals("observer_after_write"), order + ": initial source is usable without metadata replay");
            f.devices[1].value("exists").emit(false);
            f.devices[1].value("exists").emit(true);
            error(f.run("devices.remote.get", target(1)), "not_ready");
            f.flush();
            check(remoteValue(f.call("devices.remote.get", target(1))) == .8,
                    order + ": actual host flush restores continuous getters without callback replay");
            check(f.controls[1][0].value("value").writes == 1,
                    order + ": retaining initialization callbacks does not weaken later replacement invalidation");
        }
    }

    private static void initialControlCallbacksSurviveLatePresence() {
        Fake established = new Fake(false, true);
        established.controls[1][0].value("exists").emit(true);
        JsonObject initial = established.call("devices.remote.get", target(1));
        check(initial.get("complete").getAsBoolean() && remoteValue(initial) == .6,
                "first control presence retains metadata/value received after initial absent placeholder");
        check(established.call("devices.remote.set", remoteWrite(initial, 1, .8))
                .get("verification").getAsString().equals("observer_after_write"),
                "initial out-of-order control population is writable without callback replay");
        for (boolean controlFirst : List.of(false, true)) {
            Fake f = new Fake(true, true);
            if (controlFirst) f.controls[1][0].value("exists").emit(true);
            f.devices[1].value("position").emit(1);
            f.devices[1].value("deviceType").emit("audio-effect");
            f.devices[1].value("name").emit("Twin");
            f.devices[1].value("exists").emit(true);
            f.firstTrack.value("channelId").emit(TRACK);
            f.firstTrack.value("exists").emit(true);
            if (!controlFirst) f.controls[1][0].value("exists").emit(true);
            initial = f.call("devices.remote.get", target(1));
            check(initial.get("complete").getAsBoolean() && remoteValue(initial) == .6,
                    "first device and control presence retain early remote observations, controlFirst=" + controlFirst);
            check(f.call("devices.remote.set", remoteWrite(initial, 1, .8))
                    .get("verification").getAsString().equals("observer_after_write"),
                    "late initial device/control presence permits setter, controlFirst=" + controlFirst);
        }
    }

    private static void remoteControlRebindUsesFlushWithoutAcknowledgingWrites() {
        for (boolean absentCallbacks : List.of(false, true)) {
            Fake f = new Fake();
            Node control = f.controls[1][0];
            Node other = f.controls[1][1];
            other.value("exists").emit(true);
            other.value("name").emit("Other");
            other.value("value").emit(.4);
            f.flush();
            JsonObject old = f.call("devices.remote.get", target(1));
            JsonObject otherSource = f.call("devices.remote.get", target(0));
            control.value("exists").emit(false);
            f.flush();
            JsonObject absent = f.call("devices.remote.get", target(1));
            JsonObject item = absent.getAsJsonArray("controls").get(0).getAsJsonObject();
            check(!item.get("exists").getAsBoolean() && item.get("name").isJsonNull()
                    && item.get("isBeingMapped").isJsonNull() && item.get("normalizedValue").isJsonNull(),
                    "flush must not expose absent control history");
            if (absentCallbacks) {
                control.value("name").emit("Gain");
                control.value("isBeingMapped").emit(false);
                control.value("value").emit(.91);
                f.flush();
                item = f.call("devices.remote.get", target(1)).getAsJsonArray("controls").get(0).getAsJsonObject();
                check(item.get("name").isJsonNull() && item.get("isBeingMapped").isJsonNull()
                        && item.get("normalizedValue").isJsonNull(),
                        "callbacks and flush during absence cannot seed readable or fresh values");
            }
            error(f.run("devices.remote.set", remoteWrite(old, 1, .6)), "stale_context");
            control.value("exists").emit(true);
            JsonObject rebound = f.call("devices.remote.get", target(1));
            item = rebound.getAsJsonArray("controls").get(0).getAsJsonObject();
            check(item.get("exists").getAsBoolean() && item.get("normalizedValue").isJsonNull()
                    && item.get("name").isJsonNull() && !rebound.get("complete").getAsBoolean(),
                    "reappearance waits for the actual host flush, not an RPC or timer");
            error(f.run("devices.remote.set", remoteWrite(rebound, 1, .6)), "stale_context");
            JsonObject unaffected = rebound.getAsJsonArray("controls").get(1).getAsJsonObject();
            check(unaffected.get("normalizedValue").getAsDouble() == .4
                    && unaffected.get("name").getAsString().equals("Other"),
                    "another control remains readable during the target's rebind");
            JsonObject otherWrite = remoteWrite(rebound, 1, .5);
            otherWrite.addProperty("parameterIndex", 1); otherWrite.addProperty("expectedParameterName", "Other");
            check(f.call("devices.remote.set", otherWrite).get("verification").getAsString().equals("observer_after_write"),
                    "unaffected control remains writable without callback replay");
            check(f.call("devices.remote.set", remoteWrite(otherSource, 0, .3))
                    .get("verification").getAsString().equals("observer_after_write"),
                    "another source retains its token and readiness");
            control.value("value").current = .35;
            f.flush();
            JsonObject current = f.call("devices.remote.get", target(1));
            check(current.get("complete").getAsBoolean() && remoteValue(current) == .35,
                    "flush reads the current getter, not the predecessor or absent callback value");
            error(f.run("devices.remote.set", remoteWrite(old, 1, .35)), "stale_context");
            error(f.run("devices.remote.set", remoteWrite(absent, 1, .35)), "stale_context");
            control.value("value").notifyWrites = false;
            CompletableFuture<JsonObject> pending = f.run("devices.remote.set", remoteWrite(current, 1, .35));
            check(!pending.isDone() && control.value("value").writes == 1,
                    "same-value request after rebind dispatches rather than claiming a verified no-op");
            f.flush(); f.next();
            check(!pending.isDone(), "repeated flush cannot acknowledge unchanged getter write");
            control.value("value").emit(.35); f.next();
            check(pending.join().get("verification").getAsString().equals("observer_after_write"),
                    "only fresh same-source callback acknowledges the explicit write");
            control.value("exists").emit(true);
            JsonObject fresh = f.call("devices.remote.get", target(1));
            check(f.call("devices.remote.set", remoteWrite(fresh, 1, .35))
                    .get("verification").getAsString().equals("already_observed_no_write")
                    && control.value("value").writes == 1,
                    "duplicate presence preserves a genuinely fresh value for subsequent no-op");
        }
    }

    private static void strictShapesRejectBeforeScheduling() {
        Fake f = new Fake();
        List<JsonObject> badTargets = new ArrayList<>();
        JsonObject p = target(0); p.addProperty("deviceIndex", 16); badTargets.add(p);
        p = target(0); p.addProperty("deviceIndex", .5); badTargets.add(p);
        p = target(0); p.addProperty("expectedDeviceName", ""); badTargets.add(p);
        p = target(0); p.add("trackId", JsonNull.INSTANCE); badTargets.add(p);
        p = target(0); p.addProperty("extra", true); badTargets.add(p);
        for (JsonObject bad : badTargets) error(f.request("devices.get", bad), "invalid_params");
        p = target(0); p.addProperty("enabled", "false"); error(f.request("devices.set", p), "invalid_params");
        p = insert("bitwig", "1-1-1-1-1"); error(f.request("devices.insert", p), "invalid_params");
        p = insert("bitwig", NATIVE); p.addProperty("expectedDeviceCount", 16); error(f.request("devices.insert", p), "invalid_params");
        p = insert("vst2", "2147483648"); error(f.request("devices.insert", p), "invalid_params");
        p = insert("vst2", "1.5"); error(f.request("devices.insert", p), "invalid_params");
        p = insert("vst3", "1234"); error(f.request("devices.insert", p), "invalid_params");
        p = insert("clap", "plugin id"); error(f.request("devices.insert", p), "invalid_params");
        p = insert("shell", "echo hi"); error(f.request("devices.insert", p), "invalid_params");
        for (String path : List.of("relative.bwpreset", "/tmp/../bad.bwpreset", "/tmp/bad\nfile.bwpreset")) {
            p = file(path); error(f.request("devices.insert", p), "invalid_params");
        }
        p = file("/tmp/device.bwpreset"); p.addProperty("id", NATIVE); error(f.request("devices.insert", p), "invalid_params");
        p = new JsonObject(); p.addProperty("extra", true); error(f.request("devices.catalog", p), "invalid_params");
        check(f.tasks.isEmpty() && f.insertions == 0, "invalid inputs cannot schedule or mutate");
    }

    private static void sameNameTargetsStayExplicit() {
        Fake f = new Fake();
        JsonObject first = f.call("devices.get", target(0));
        JsonObject second = f.call("devices.get", target(1));
        check(first.get("name").getAsString().equals(second.get("name").getAsString()), "fixture has same-name devices");
        JsonObject p = target(1); p.addProperty("enabled", false);
        JsonObject result = f.call("devices.set", p);
        check(!result.get("enabled").getAsBoolean(), "bypass readback is disabled");
        check(f.devices[0].value("isEnabled").writes == 0 && f.devices[1].value("isEnabled").writes == 1,
                "same-name device at index one is the only write target");
        result = f.call("devices.select", target(1));
        check(f.selected == 1, "selection targets explicit fixed device, not first matching name");
        check(result.get("status").getAsString().equals("dispatched")
                && result.get("completion").getAsString().equals("not_observed"), "selection never fabricates completion");
        p = target(1); p.addProperty("expectedDeviceName", "Wrong"); p.addProperty("enabled", true);
        error(f.run("devices.set", p), "stale_context");
        check(f.devices[1].value("isEnabled").writes == 1, "name mismatch fails before dispatch");
        p = target(2); error(f.run("devices.get", p), "target_not_found");
        check(!first.get("nameWritable").getAsBoolean(), "public device rename limitation is explicit");
    }

    private static void bypassNeedsFreshSameSourceObservation() {
        Fake f = new Fake();
        f.devices[1].value("isEnabled").notifyWrites = false;
        JsonObject p = target(1); p.addProperty("enabled", false);
        CompletableFuture<JsonObject> future = f.run("devices.set", p);
        check(!future.isDone(), "cached matching value without callback cannot verify bypass");
        f.devices[0].value("isEnabled").emit(false); f.next();
        check(!future.isDone(), "other source cannot verify target bypass");
        f.devices[1].value("isEnabled").emit(false); f.next();
        check(future.join().get("verification").getAsString().equals("observer_after_write"), "fresh same-source callback verifies bypass");
        int writes = f.devices[1].value("isEnabled").writes;
        check(f.call("devices.set", p).get("verification").getAsString().equals("already_observed_no_write"), "idempotent state avoids duplicate mutation");
        check(f.devices[1].value("isEnabled").writes == writes, "already observed bypass does not write again");
    }

    private static void targetChangeAndDeadlinesNeverReplay() {
        Fake f = new Fake();
        JsonObject p = target(0); p.addProperty("enabled", false);
        error(f.run("devices.set", p, System.nanoTime() - 1), "deadline_exceeded");
        check(f.devices[0].value("isEnabled").writes == 0, "expired request cannot bypass device");
        CompletableFuture<JsonObject> canceled = f.request("devices.set", p); canceled.cancel(false); f.next();
        check(f.devices[0].value("isEnabled").writes == 0, "canceled queued request cannot bypass device");
        f.devices[0].value("isEnabled").notifyWrites = false;
        CompletableFuture<JsonObject> pending = f.run("devices.set", p);
        f.devices[0].value("name").emit("Replacement"); f.next();
        check(pending.join().get("completion").getAsString().equals("unknown"), "changed target after dispatch is unknown");
        check(f.devices[0].value("isEnabled").writes == 1, "target change cannot replay write");
        Fake shortWait = new Fake(); shortWait.devices[0].value("isEnabled").notifyWrites = false;
        JsonObject result = shortWait.run("devices.set", p, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(20)).join();
        check(result.get("status").getAsString().equals("dispatched"), "bounded observation timeout does not invent completion");
        check(shortWait.devices[0].value("isEnabled").writes == 1, "timeout never retries mutation");
    }

    private static void insertionGuardsAndObservedDelta() {
        Fake f = new Fake();
        JsonObject wrong = insert("bitwig", NATIVE); wrong.addProperty("expectedDeviceCount", 1);
        error(f.run("devices.insert", wrong), "stale_context");
        check(f.insertions == 0, "count mismatch fails closed before insertion");
        JsonObject result = f.call("devices.insert", insert("bitwig", NATIVE));
        check(f.insertions == 1 && f.lastArgument.equals(UUID.fromString(NATIVE)), "native ID reaches exact public insertion API once");
        check(result.get("beforeCount").getAsInt() == 2 && result.get("afterCount").getAsInt() == 3, "insertion reports count delta");
        check(result.getAsJsonObject("inserted").get("deviceIndex").getAsInt() == 2, "append identifies new final slot, not same-name predecessor");
        check(result.getAsJsonArray("before").size() == 2 && result.getAsJsonArray("after").size() == 3, "insertion preserves before/after evidence");
        check(!result.get("requestedIdentifierVerified").getAsBoolean(), "name/type readback is not proof of requested plugin UUID");
        error(f.run("devices.insert", insert("bitwig", NATIVE)), "stale_context");
        check(f.insertions == 1, "reusing old expected count cannot duplicate insert");
        Fake delayed = new Fake(); delayed.insertMode = "countOnly";
        CompletableFuture<JsonObject> pending = delayed.run("devices.insert", insert("bitwig", NATIVE));
        check(!pending.isDone(), "count and existence alone cannot relabel stale empty-slot metadata as the inserted device");
        delayed.devices[2].value("name").emit("Fresh inserted name"); delayed.next();
        check(pending.join().getAsJsonObject("inserted").get("name").getAsString().equals("Fresh inserted name"),
                "insertion waits for fresh metadata from the appended slot");
    }

    private static void insertionKindsPassExactArguments() {
        String[][] cases = {{"bitwig", NATIVE, "insertBitwigDevice"}, {"vst2", "-123456", "insertVST2Device"},
                {"vst3", "00112233445566778899AABBCCDDEEFF", "insertVST3Device"},
                {"clap", "org.example.effect", "insertCLAPDevice"}};
        for (String[] item : cases) {
            Fake f = new Fake(); f.call("devices.insert", insert(item[0], item[1]));
            check(f.lastInsertion.equals(item[2]), "correct public insertion method for " + item[0]);
            check(f.lastArgument.toString().equals(item[1]), "identifier passed without guessing or expansion");
        }
        Fake f = new Fake();
        String literal = "/tmp/Device $(not-a-command).bwpreset";
        f.call("devices.insert", file(literal));
        check(f.lastInsertion.equals("insertFile") && f.lastArgument.equals(literal), "file path is literal host API input, never a shell command");
    }

    private static void insertionAmbiguityAndNoopStayUncertain() {
        for (String mode : List.of("double", "prefix")) {
            Fake f = new Fake(); f.insertMode = mode;
            JsonObject result = f.call("devices.insert", insert("bitwig", NATIVE));
            check(result.get("verification").getAsString().equals("ambiguous_chain_change"), "concurrent chain edits are ambiguous: " + mode);
            check(result.get("inserted").isJsonNull(), "ambiguous result never labels an arbitrary device as inserted");
            check(f.insertions == 1, "ambiguous insertion is not retried");
        }
        Fake f = new Fake(); f.insertMode = "none";
        JsonObject result = f.run("devices.insert", insert("bitwig", NATIVE),
                System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(20)).join();
        check(result.get("verification").getAsString().equals("insertion_not_observed"), "public unknown-ID no-op is honestly reported");
        check(result.get("inserted").isJsonNull() && f.insertions == 1, "no-op never invents ID or retries");
        Fake shifted = new Fake(); shifted.bank.value("scrollPosition").emit(1);
        error(shifted.run("devices.insert", insert("bitwig", NATIVE)), "stale_context");
        check(shifted.insertions == 0, "shifted window cannot silently append without observing prefix");
    }


    private static void remotesAreSourceBoundAndTokenGuarded() {
        Fake f = new Fake();
        JsonObject first = f.call("devices.remote.get", target(0));
        JsonObject second = f.call("devices.remote.get", target(1));
        check(remoteValue(first) == .25 && remoteValue(second) == .6, "same-name device remote pages keep separate sources");
        check(!second.get("followsUiPageSelection").getAsBoolean(), "independent page is not UI-selected cursor");
        JsonObject result = f.call("devices.remote.set", remoteWrite(second, 1, .8));
        check(remoteValue(result) == .8 && f.controls[0][0].value("value").writes == 0
                && f.controls[1][0].value("value").writes == 1, "remote mutation writes only explicit second source");
        f.controls[1][0].value("name").emit("Remapped");
        error(f.run("devices.remote.set", remoteWrite(second, 1, .3)), "stale_context");
        check(f.controls[1][0].value("value").writes == 1, "observed mapping metadata change invalidates context token");
        JsonObject fresh = f.call("devices.remote.get", target(1));
        f.controls[1][0].value("isBeingMapped").emit(true);
        JsonObject mapped = f.call("devices.remote.get", target(1));
        JsonObject p = remoteWrite(mapped, 1, .4); p.addProperty("expectedParameterName", "Remapped");
        error(f.run("devices.remote.set", p), "stale_context");
        check(fresh.has("identityCaveat"), "unobservable same-name remap caveat is returned to consumers");
    }

    private static void remotePageSelectionIsIndependentAndObserved() {
        Fake f = new Fake(); JsonObject listing = f.call("devices.remote.get", target(1));
        JsonObject p = target(1); p.add("contextToken", listing.get("contextToken"));
        p.addProperty("pageIndex", 1); p.addProperty("expectedPageName", "Tone");
        JsonObject result = f.call("devices.remote.page", p);
        check(result.get("pageName").getAsString().equals("Tone") && result.get("pageIndex").getAsInt() == 1,
                "requested page index and name are observed");
        check(result.get("verification").getAsString().equals("page_observer_after_write"), "page requires fresh index callback");
        check(!result.get("complete").getAsBoolean()
                && result.getAsJsonArray("controls").get(0).getAsJsonObject().get("normalizedValue").isJsonNull(),
                "new page cannot relabel a cached value from the previous page as current");
        error(f.run("devices.remote.set", remoteWrite(result, 1, .7)), "stale_context");
        f.controls[1][0].value("value").emit(.35);
        f.flush();
        JsonObject fresh = f.call("devices.remote.get", target(1));
        check(remoteValue(fresh) == .35, "new page value becomes available after its own source callback");
        check(f.pages[0].value("selectedPageIndex").writes == 0 && f.pages[1].value("selectedPageIndex").writes == 1,
                "page selection affects only addressed proxy");
        check(f.selected == -1, "remote page does not select device in editor");
        error(f.run("devices.remote.page", p), "stale_context");
        p.add("contextToken", result.get("contextToken")); p.addProperty("expectedPageName", "Wrong");
        error(f.run("devices.remote.page", p), "stale_context");
    }

    private static void remoteAbsoluteWritesBypassPickupWithoutBypassingGuards() {
        // SettableRangedValue documents that set(double) may defer under takeover,
        // while setImmediately(double) applies the absolute value regardless of that mode.
        Val pickup = new Val(.6);
        pickup.takeoverPending = true;
        SettableRangedValue ranged = (SettableRangedValue) pickup.proxy(SettableRangedValue.class);
        ranged.set(.8);
        check(ranged.get() == .6, "pickup defers ordinary set rather than changing the host value");
        ranged.setImmediately(.8);
        check(ranged.get() == .8 && pickup.takeoverPending,
                "absolute setter changes the value without changing configured takeover");

        Fake f = new Fake();
        Node other = f.controls[1][1];
        other.value("exists").emit(true);
        other.value("name").emit("Other");
        other.value("isBeingMapped").emit(false);
        other.value("value").emit(.4);
        f.flush();
        JsonObject original = f.call("devices.remote.get", target(1));
        double originalValue = remoteValue(original);
        JsonObject changed = f.call("devices.remote.set", remoteWrite(original, 1, .8));
        check(changed.get("verification").getAsString().equals("observer_after_write")
                && remoteValue(changed) == .8
                && remoteValue(f.call("devices.remote.get", target(1))) == .8,
                "absolute RPC reaches the observed target under pending pickup");
        check(remoteValue(f.call("devices.remote.get", target(0))) == .25
                && f.controls[0][0].value("value").writes == 0
                && other.value("value").writes == 0
                && changed.getAsJsonArray("controls").get(1).getAsJsonObject()
                        .get("normalizedValue").getAsDouble() == .4,
                "absolute write preserves the other source and neighboring control");
        JsonObject restored = f.call("devices.remote.set", remoteWrite(changed, 1, originalValue));
        check(remoteValue(restored) == originalValue
                && restored.get("verification").getAsString().equals("observer_after_write")
                && f.controls[1][0].value("value").takeoverPending,
                "fresh source-bound absolute write restores the original value without altering takeover");
        check(f.call("devices.remote.set", remoteWrite(restored, 1, originalValue))
                .get("verification").getAsString().equals("already_observed_no_write")
                && f.controls[1][0].value("value").writes == 2,
                "observed no-op under pickup does not dispatch another write");

        for (String state : List.of("stale", "absent", "isArrangerRecordEnabled",
                "isArrangerAutomationWriteEnabled", "isClipLauncherAutomationWriteEnabled")) {
            Fake guarded = new Fake();
            JsonObject listing = guarded.call("devices.remote.get", target(1));
            if (state.equals("stale")) guarded.controls[1][0].value("name").emit("Remapped");
            else if (state.equals("absent")) guarded.controls[1][0].value("exists").emit(false);
            else guarded.transport.value(state).emit(true);
            error(guarded.run("devices.remote.set", remoteWrite(listing, 1, .8)),
                    state.equals("stale") || state.equals("absent") ? "stale_context" : "unsafe_state");
            check(guarded.controls[1][0].value("value").writes == 0
                    && (Double) guarded.controls[1][0].value("value").current == .6,
                    state + ": absolute write remains blocked with no host value change");
        }
    }

    private static void remoteWritesNeedFreshUnchangedSource() {
        Fake f = new Fake(); JsonObject listing = f.call("devices.remote.get", target(1));
        f.controls[1][0].value("value").notifyWrites = false;
        CompletableFuture<JsonObject> pending = f.run("devices.remote.set", remoteWrite(listing, 1, .9));
        f.controls[0][0].value("value").emit(.9); f.next();
        check(!pending.isDone(), "remote readback from another source cannot complete write");
        f.controls[1][0].value("value").emit(.9); f.next();
        check(pending.join().get("verification").getAsString().equals("observer_after_write"), "same-source fresh remote observation verifies write");
        JsonObject latest = f.call("devices.remote.get", target(1));
        pending = f.run("devices.remote.set", remoteWrite(latest, 1, .7));
        f.pages[1].value("getName").emit("Changed"); f.next();
        check(pending.join().get("completion").getAsString().equals("unknown"), "page change during remote write prevents misleading verification");
        check(f.controls[1][0].value("value").writes == 2, "remote writes never replay after uncertain result");
    }

    private static void observedSourceReplacementReconcilesAtFlush() {
        for (String transition : List.of("device_exists", "device_position", "track_uuid", "track_exists", "device_type")) {
            Fake f = new Fake();
            JsonObject old = f.call("devices.remote.get", target(1));
            JsonObject currentTarget = target(1);
            switch (transition) {
                case "device_exists":
                    f.devices[1].value("exists").emit(false);
                    f.controls[1][0].value("value").emit(.91);
                    f.devices[1].value("exists").emit(true);
                    break;
                case "device_position":
                    f.devices[1].value("position").emit(2);
                    f.flush();
                    error(f.run("devices.remote.get", currentTarget), "target_not_found");
                    f.devices[1].value("position").emit(1);
                    break;
                case "track_uuid":
                    String replacement = "7a71b30b-abf3-4e23-9a7e-8a346e301a2f";
                    f.firstTrack.value("channelId").emit(replacement);
                    currentTarget.addProperty("trackId", replacement);
                    break;
                case "track_exists":
                    f.firstTrack.value("exists").emit(false);
                    f.controls[1][0].value("value").emit(.91);
                    f.flush();
                    f.firstTrack.value("exists").emit(true);
                    break;
                case "device_type":
                    f.devices[1].value("deviceType").emit("instrument");
                    break;
                default: throw new AssertionError(transition);
            }
            error(f.run("devices.remote.get", currentTarget), "not_ready");
            JsonObject staleWrite = remoteWrite(old, 1, .6);
            staleWrite.add("trackId", currentTarget.get("trackId"));
            error(f.run("devices.remote.set", staleWrite), "not_ready");
            f.pages[1].value("pageNames").current = new String[]{"Main", "Tone", "Third"};
            f.pages[1].value("pageCount").current = 3;
            f.controls[1][0].value("value").current = .35;
            check(f.flushRequests > 0 && f.tasks.isEmpty(), transition + ": rebind requests a host flush, not a recovery timer");
            f.flush();
            JsonObject fresh = f.call("devices.remote.get", currentTarget);
            check(fresh.get("complete").getAsBoolean() && remoteValue(fresh) == .35
                    && fresh.get("pageCount").getAsInt() == 3 && fresh.getAsJsonArray("pages").size() == 3,
                    transition + ": flush restores current previously observed getters without any metadata/value callback replay");
            error(f.run("devices.remote.set", staleWrite), "stale_context");
            JsonObject write = remoteWrite(fresh, 1, .35); write.add("trackId", currentTarget.get("trackId"));
            f.controls[1][0].value("value").notifyWrites = false;
            CompletableFuture<JsonObject> pending = f.run("devices.remote.set", write);
            check(!pending.isDone() && f.controls[1][0].value("value").writes == 1,
                    transition + ": reconciled value is readable but not a verified same-value no-op");
            f.flush(); f.next();
            check(!pending.isDone(), transition + ": flush advances no write acknowledgement sequence");
            f.controls[1][0].value("value").emit(.35); f.next();
            check(pending.join().get("verification").getAsString().equals("observer_after_write"),
                    transition + ": fresh same-source callback still verifies the write");
        }
    }

    private static void recoveredPageBaselineInvalidatesLaterTransition() {
        Fake f = new Fake();
        f.call("devices.remote.get", target(1));
        f.devices[1].value("exists").emit(false);
        f.devices[1].value("exists").emit(true);
        f.pages[1].value("selectedPageIndex").current = 1;
        f.pages[1].value("getName").current = "Tone";
        f.flush();
        f.controls[1][0].value("value").emit(.8);
        f.pages[1].value("selectedPageIndex").emit(0);
        f.pages[1].value("getName").current = "Main";
        JsonObject pending = f.call("devices.remote.get", target(1));
        check(pending.getAsJsonArray("controls").get(0).getAsJsonObject().get("normalizedValue").isJsonNull(),
                "page callback after getter-only recovery invalidates predecessor readiness");
        f.controls[1][0].value("value").current = .6;
        f.flush();
        JsonObject current = f.call("devices.remote.get", target(1));
        f.controls[1][0].value("value").notifyWrites = false;
        JsonObject result = f.run("devices.remote.set", remoteWrite(current, 1, .6),
                System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(20)).join();
        check(result.get("verification").getAsString().equals("postcondition_not_observed")
                && f.controls[1][0].value("value").writes == 1,
                "recovered page baseline does not preserve predecessor value freshness");
    }

    private static void pageRoundtripRecoversUnchangedValues() {
        Fake f = new Fake();
        Node neighbor = f.controls[1][1];
        neighbor.value("exists").emit(true); neighbor.value("name").emit("Neighbor");
        neighbor.value("value").emit(.4);
        f.flush();
        JsonObject original = f.call("devices.remote.get", target(1));
        JsonObject page = target(1); page.add("contextToken", original.get("contextToken"));
        page.addProperty("pageIndex", 1); page.addProperty("expectedPageName", "Tone");
        JsonObject switched = f.call("devices.remote.page", page);
        neighbor.value("exists").emit(false);
        check(!switched.get("complete").getAsBoolean(), "page switch cannot expose predecessor controls before flush");
        f.flush();
        JsonObject tone = f.call("devices.remote.get", target(1));
        check(tone.get("complete").getAsBoolean() && remoteValue(tone) == .6,
                "unchanged slot value is readable at flush without any value callback");
        JsonObject absent = tone.getAsJsonArray("controls").get(1).getAsJsonObject();
        check(!absent.get("exists").getAsBoolean() && absent.get("name").isJsonNull()
                && absent.get("normalizedValue").isJsonNull(), "page recovery cannot expose an absent slot");
        page.add("contextToken", tone.get("contextToken"));
        page.addProperty("pageIndex", 0); page.addProperty("expectedPageName", "Main");
        f.call("devices.remote.page", page);
        neighbor.value("exists").emit(true);
        f.flush();
        JsonObject returned = f.call("devices.remote.get", target(1));
        check(returned.get("complete").getAsBoolean() && remoteValue(returned) == .6
                && returned.getAsJsonArray("controls").get(1).getAsJsonObject().get("normalizedValue").getAsDouble() == .4,
                "page roundtrip restores unchanged values and returning controls without name/value callback replay");
        error(f.run("devices.remote.set", remoteWrite(original, 1, .6)), "stale_context");
        f.controls[1][0].value("value").notifyWrites = false;
        JsonObject result = f.run("devices.remote.set", remoteWrite(returned, 1, .6),
                System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(20)).join();
        check(result.get("verification").getAsString().equals("postcondition_not_observed")
                && f.controls[1][0].value("value").writes == 1,
                "page-return getter equality is readable, not fresh no-op or write acknowledgement");
    }

    private static void sameProcessReopenRecoversUnchangedHistory() {
        Fake f = new Fake();
        JsonObject original = f.call("devices.remote.get", target(1));
        f.firstTrack.value("exists").emit(false);
        f.devices[1].value("exists").emit(false);
        f.controls[1][0].value("exists").emit(false);
        f.pages[1].value("getName").emit("");
        f.controls[1][0].value("name").emit("");
        f.controls[1][0].value("value").emit(.91);
        f.flush();
        error(f.run("devices.remote.get", target(1)), "outside_window");
        // Native interested getters are updated at reopen; unchanged remote callbacks need not replay.
        f.pages[1].value("getName").current = "Main";
        f.controls[1][0].value("name").current = "Gain";
        f.controls[1][0].value("value").current = .6;
        f.controls[1][0].value("exists").current = true;
        f.devices[1].value("exists").emit(true);
        f.firstTrack.value("channelId").emit(TRACK);
        f.firstTrack.value("exists").emit(true);
        error(f.run("devices.remote.get", target(1)), "not_ready");
        f.flush();
        JsonObject reopened = f.call("devices.remote.get", target(1));
        check(reopened.get("complete").getAsBoolean() && remoteValue(reopened) == .6
                && reopened.get("pageName").getAsString().equals("Main"),
                "same-process reopen restores unchanged remote metadata/value history at the host flush");
        check(!reopened.get("contextToken").equals(original.get("contextToken")),
                "same UUID, device name and page after reopen still carry a different context token");
        error(f.run("devices.remote.set", remoteWrite(original, 1, .6)), "stale_context");
        check(f.controls[1][0].value("value").writes == 0
                && f.pages[1].value("selectedPageIndex").writes == 0,
                "read recovery never writes values or selects pages to provoke callbacks");
    }

    private static void pendingRemoteWritesCannotSurviveRebinding() {
        for (String transition : List.of("source", "page", "control", "mapping")) {
            Fake f = new Fake();
            JsonObject original = f.call("devices.remote.get", target(1));
            f.controls[1][0].value("value").notifyWrites = false;
            CompletableFuture<JsonObject> pending = f.run("devices.remote.set", remoteWrite(original, 1, .9));
            if (transition.equals("source")) {
                f.devices[1].value("exists").emit(false);
                f.devices[1].value("exists").emit(true);
            } else if (transition.equals("page")) {
                f.pages[1].value("selectedPageIndex").emit(1);
                f.pages[1].value("getName").emit("Tone");
            } else if (transition.equals("control")) {
                f.controls[1][0].value("exists").emit(false);
                f.controls[1][0].value("exists").emit(true);
            } else {
                f.controls[1][0].value("isBeingMapped").emit(true);
                f.controls[1][0].value("value").emit(.9);
                f.flush();
                f.controls[1][0].value("isBeingMapped").emit(false);
            }
            f.flush(); f.next();
            check(pending.join().get("completion").getAsString().equals("unknown")
                    && pending.join().get("verification").getAsString().equals("remote_context_changed_after_dispatch"),
                    transition + ": lifecycle change invalidates the pending write even after flush recovery");
            JsonObject current = f.call("devices.remote.get", target(1));
            check(remoteValue(current) == .9 && f.controls[1][0].value("value").writes == 1,
                    transition + ": getter recovery never retries or retrospectively acknowledges the invalidated write");
        }
    }

    private static void flushCannotInventUnobservedOrFiniteValues() {
        for (String field : List.of("getName", "exists", "name", "isBeingMapped", "value")) {
            Fake f = new Fake(false, false, Set.of(field));
            f.devices[1].value("exists").emit(false);
            f.devices[1].value("exists").emit(true);
            f.flush();
            if (field.equals("getName")) {
                error(f.run("devices.remote.get", target(1)), "not_ready");
                f.pages[1].value(field).emit("Main");
            } else {
                JsonObject incomplete = f.call("devices.remote.get", target(1));
                String key = field.equals("value") ? "normalizedValue" : field;
                check(!incomplete.get("complete").getAsBoolean()
                        && incomplete.getAsJsonArray("controls").get(0).getAsJsonObject().get(key).isJsonNull(),
                        field + ": flush cannot fabricate an observation from an unobserved default getter");
                error(f.run("devices.remote.set", remoteWrite(incomplete, 1, .6)), "stale_context");
                f.controls[1][0].value(field).emit(f.controls[1][0].value(field).current);
            }
            f.flush();
            check(remoteValue(f.call("devices.remote.get", target(1))) == .6,
                    field + ": actual first observation makes the source readable");
        }
        Fake f = new Fake();
        f.controls[1][0].value("value").emit(Double.NaN);
        f.devices[1].value("exists").emit(false);
        f.devices[1].value("exists").emit(true);
        f.flush();
        JsonObject nonfinite = f.call("devices.remote.get", target(1));
        check(nonfinite.getAsJsonArray("controls").get(0).getAsJsonObject().get("normalizedValue").isJsonNull(),
                "nonfinite current getter cannot fall back to the last finite observation");
        error(f.run("devices.remote.set", remoteWrite(nonfinite, 1, .6)), "stale_context");
        check(f.controls[1][0].value("value").writes == 0, "nonfinite readback cannot authorize a write");
    }

    private static void recoveredSameValueWritesRetainSafetyAndFreshness() {
        for (String unsafe : List.of("isArrangerRecordEnabled", "isArrangerAutomationWriteEnabled",
                "isClipLauncherAutomationWriteEnabled")) {
            Fake f = new Fake();
            f.devices[1].value("exists").emit(false); f.devices[1].value("exists").emit(true);
            f.flush();
            JsonObject current = f.call("devices.remote.get", target(1));
            f.transport.value(unsafe).emit(true);
            error(f.run("devices.remote.set", remoteWrite(current, 1, .6)), "unsafe_state");
            check(f.controls[1][0].value("value").writes == 0,
                    unsafe + ": getter equality after rebind cannot bypass playback safety as a false no-op");
        }
        Fake f = new Fake();
        f.devices[1].value("exists").emit(false); f.devices[1].value("exists").emit(true);
        f.flush();
        JsonObject current = f.call("devices.remote.get", target(1));
        JsonObject page = target(1); page.add("contextToken", current.get("contextToken"));
        page.addProperty("pageIndex", 0); page.addProperty("expectedPageName", "Main");
        f.pages[1].value("selectedPageIndex").notifyWrites = false;
        JsonObject result = f.run("devices.remote.page", page, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(20)).join();
        check(result.get("verification").getAsString().equals("postcondition_not_observed")
                && f.pages[1].value("selectedPageIndex").writes == 1,
                "recovered unchanged page index also requires a fresh observer, not a false verified no-op");
        Fake mapped = new Fake();
        mapped.controls[1][0].value("isBeingMapped").emit(true);
        mapped.controls[1][0].value("value").emit(.6);
        mapped.flush();
        JsonObject mapping = mapped.call("devices.remote.get", target(1));
        check(mapping.getAsJsonArray("controls").get(0).getAsJsonObject().get("normalizedValue").isJsonNull(),
                "flush while mapping cannot expose a mapped value");
        error(mapped.run("devices.remote.set", remoteWrite(mapping, 1, .6)), "stale_context");
        mapped.controls[1][0].value("isBeingMapped").emit(false);
        mapped.flush();
        JsonObject unmapped = mapped.call("devices.remote.get", target(1));
        mapped.controls[1][0].value("value").notifyWrites = false;
        JsonObject unacknowledged = mapped.run("devices.remote.set", remoteWrite(unmapped, 1, .6),
                System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(20)).join();
        check(unacknowledged.get("verification").getAsString().equals("postcondition_not_observed")
                && mapped.controls[1][0].value("value").writes == 1,
                "callback during remapping cannot become fresh no-op proof after flush");
    }

    private static void deviceRenamePreservesRemoteObservations() {
        Fake f = new Fake();
        JsonObject old = f.call("devices.remote.get", target(1));
        f.devices[1].value("name").emit("Renamed");
        JsonObject renamed = target(1); renamed.addProperty("expectedDeviceName", "Renamed");
        JsonObject fresh = f.call("devices.remote.get", renamed);
        check(remoteValue(fresh) == .6, "device metadata rename retains continuously bound remote values without callback replay");
        JsonObject stale = remoteWrite(old, 1, .8); stale.addProperty("expectedDeviceName", "Renamed");
        error(f.run("devices.remote.set", stale), "stale_context");
        JsonObject write = remoteWrite(fresh, 1, .8); write.addProperty("expectedDeviceName", "Renamed");
        check(f.call("devices.remote.set", write).get("verification").getAsString().equals("observer_after_write"),
                "fresh name/context permits same-source remote write without replaying unchanged page metadata");
    }

    private static double remoteValue(JsonObject result) {
        return result.getAsJsonArray("controls").get(0).getAsJsonObject().get("normalizedValue").getAsDouble();
    }
    private static JsonObject remoteWrite(JsonObject listing, int device, double value) {
        JsonObject p = target(device); p.add("contextToken", listing.get("contextToken"));
        p.add("expectedPageName", listing.get("pageName")); p.addProperty("parameterIndex", 0);
        p.addProperty("expectedParameterName", "Gain"); p.addProperty("normalizedValue", value); return p;
    }
    private static JsonObject target(int index) {
        JsonObject p = new JsonObject(); p.addProperty("trackId", TRACK); p.addProperty("deviceIndex", index);
        p.addProperty("expectedDeviceName", "Twin"); return p;
    }
    private static JsonObject insert(String kind, String id) {
        JsonObject p = new JsonObject(); p.addProperty("trackId", TRACK); p.addProperty("expectedDeviceCount", 2);
        p.addProperty("kind", kind); p.addProperty("id", id); return p;
    }
    private static JsonObject file(String path) {
        JsonObject p = insert("file", ""); p.remove("id"); p.addProperty("path", path); return p;
    }
    private static void check(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
    private static void error(CompletableFuture<JsonObject> future, String code) {
        check(future.isDone(), "error completes immediately: " + code);
        try { future.join(); throw new AssertionError("Expected " + code); }
        catch (CompletionException e) {
            check(e.getCause() instanceof RpcException && ((RpcException) e.getCause()).code.equals(code),
                    "expected " + code + ", received " + e.getCause());
        }
    }

    private static final class Fake {
        final Deque<Runnable> tasks = new ArrayDeque<>();
        final Node[] devices = new Node[16], pages = new Node[16];
        final Node[][] controls = new Node[16][8];
        final Node bank = new Node();
        final Node transport = new Node();
        final HostServices services;
        final DeviceModule module;
        int insertions, flushRequests, selected = -1;
        String insertMode = "one", lastInsertion;
        Object lastArgument;
        Node firstTrack;
        boolean initialized, running;

        Fake() { this(false); }

        Fake(boolean lateIdentity) { this(lateIdentity, false); }

        Fake(boolean lateIdentity, boolean lateControlPresence) { this(lateIdentity, lateControlPresence, Set.of()); }

        Fake(boolean lateIdentity, boolean lateControlPresence, Set<String> unobserved) {
            Node trackBank = new Node(); Track[] tracks = new Track[16];
            trackBank.put("scrollPosition", 0); trackBank.put("itemCount", 1);
            trackBank.custom = (name, args) -> name.equals("getItemAt") ? tracks[(Integer) args[0]] : null;
            for (int t = 0; t < 16; t++) {
                final int trackIndex = t;
                Node track = new Node(); track.put("exists", t == 0 && !lateIdentity);
                track.put("channelId", t == 0 && !lateIdentity ? TRACK : "");
                track.put("name", "Fixture"); track.put("position", t);
                if (t == 0) firstTrack = track;
                Node deviceBank = t == 0 ? bank : new Node();
                Device[] objects = new Device[16];
                deviceBank.put("scrollPosition", 0); deviceBank.put("itemCount", t == 0 ? 2 : 0);
                deviceBank.custom = (name, args) -> name.equals("getItemAt") ? objects[(Integer) args[0]] : null;
                for (int d = 0; d < 16; d++) {
                    final int deviceIndex = d;
                    Node device = new Node(); device.put("exists", t == 0 && d < 2 && !lateIdentity);
                    device.put("name", lateIdentity ? "" : "Twin");
                    device.put("position", lateIdentity ? -1 : d);
                    device.put("deviceType", lateIdentity ? "" : "audio-effect"); device.put("isEnabled", true);
                    device.value("isEnabled").beforeWrite = this::assertHostTask;
                    Node page = new Node(); page.put("pageNames", new String[]{"Main", "Tone"});
                    page.put("pageCount", 2); page.put("selectedPageIndex", 0); page.put("getName", "Main");
                    for (String field : unobserved)
                        if (page.values.containsKey(field)) page.value(field).notifyInitial = false;
                    page.value("selectedPageIndex").beforeWrite = this::assertHostTask;
                    page.value("selectedPageIndex").afterWrite = () -> page.value("getName").emit(
                            ((Integer) page.value("selectedPageIndex").current) == 0 ? "Main" : "Tone");
                    RemoteControl[] remoteObjects = new RemoteControl[8];
                    for (int r = 0; r < 8; r++) {
                        Node remote = new Node(); remote.put("exists", r == 0 && !lateControlPresence);
                        remote.put("name", r == 0 ? "Gain" : "");
                        remote.put("isBeingMapped", false); remote.put("value", d == 0 ? .25 : .6);
                        for (String field : unobserved)
                            if (remote.values.containsKey(field)) remote.value(field).notifyInitial = false;
                        remote.value("value").beforeWrite = this::assertHostTask;
                        remote.value("value").takeoverPending = true;
                        remoteObjects[r] = remote.proxy(RemoteControl.class);
                        if (t == 0) controls[d][r] = remote;
                    }
                    page.custom = (name, args) -> name.equals("getParameter") ? remoteObjects[(Integer) args[0]] : null;
                    device.custom = (name, args) -> {
                        if (name.equals("createCursorRemoteControlsPage")) {
                            if (initialized) throw new AssertionError("remote proxy created after initialization");
                            return page.proxy(CursorRemoteControlsPage.class);
                        }
                        if (name.equals("selectInEditor")) { assertHostTask(); selected = deviceIndex; }
                        return null;
                    };
                    objects[d] = device.proxy(Device.class);
                    if (t == 0) { devices[d] = device; pages[d] = page; }
                }
                track.objects.put("createDeviceBank", deviceBank.proxy(DeviceBank.class));
                Node end = new Node();
                end.custom = (name, args) -> {
                    if (name.startsWith("insert")) {
                        assertHostTask(); check(trackIndex == 0, "insertion uses explicit track endpoint");
                        insertions++; lastInsertion = name; lastArgument = args[0];
                        if (!insertMode.equals("none")) {
                            append(); if (insertMode.equals("double")) append();
                            if (insertMode.equals("prefix")) devices[0].value("name").emit("Changed prefix");
                        }
                    }
                    return null;
                };
                track.objects.put("endOfDeviceChainInsertionPoint", end.proxy(InsertionPoint.class));
                tracks[t] = track.proxy(Track.class);
            }
            Node app = new Node(); app.put("projectName", "Fixture"); app.put("hasActiveEngine", true);
            transport.put("isPlaying", false);
            transport.put("isArrangerRecordEnabled", false);
            transport.put("isArrangerAutomationWriteEnabled", false);
            transport.put("isClipLauncherAutomationWriteEnabled", false);
            Node host = new Node(); host.objects.put("createApplication", app.proxy(Application.class));
            host.objects.put("createTransport", transport.proxy(Transport.class));
            host.objects.put("createTrackBank", trackBank.proxy(TrackBank.class));
            host.custom = (name, args) -> {
                if (name.equals("scheduleTask")) tasks.add((Runnable) args[0]);
                if (name.equals("requestFlush")) flushRequests++;
                return null;
            };
            services = new HostServices(host.proxy(ControllerHost.class)); module = new DeviceModule(services); initialized = true;
        }
        void assertHostTask() { if (!running) throw new AssertionError("mutation outside scheduled host task"); }
        void flush() {
            running = true;
            try { module.flush(); } finally { running = false; }
        }
        void append() {
            int index = (Integer) bank.value("itemCount").current;
            if (!insertMode.equals("countOnly")) devices[index].value("name").emit("Inserted");
            devices[index].value("exists").emit(true);
            bank.value("itemCount").emit(index + 1);
        }
        CompletableFuture<JsonObject> request(String method, JsonObject p) {
            return request(method, p, System.nanoTime() + TimeUnit.SECONDS.toNanos(2));
        }
        CompletableFuture<JsonObject> request(String method, JsonObject p, long deadline) {
            HostOperation op = new HostOperation(deadline);
            try { DeviceModule.validateParams(method, p); services.schedule(op, () -> module.dispatch(op, method, p.deepCopy()), 0); }
            catch (RuntimeException error) { op.result.completeExceptionally(error); }
            return op.result;
        }
        CompletableFuture<JsonObject> run(String method, JsonObject p) { CompletableFuture<JsonObject> f = request(method, p); next(); return f; }
        CompletableFuture<JsonObject> run(String method, JsonObject p, long deadline) { CompletableFuture<JsonObject> f = request(method, p, deadline); next(); return f; }
        JsonObject call(String method, JsonObject p) { return run(method, p).join(); }
        void next() {
            Runnable task = tasks.remove(); running = true;
            try { task.run(); } finally { running = false; }
        }
    }

    private interface Custom { Object invoke(String name, Object[] args); }
    private static final class Node implements InvocationHandler {
        final Map<String, Val> values = new HashMap<>();
        final Map<String, Object> objects = new HashMap<>();
        Custom custom = (name, args) -> null;
        void put(String name, Object value) { values.put(name, new Val(value)); }
        Val value(String name) { return values.get(name); }
        <T> T proxy(Class<T> type) { return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, this)); }
        @Override public Object invoke(Object proxy, Method method, Object[] args) {
            if (values.containsKey(method.getName())) return values.get(method.getName()).proxy(method.getReturnType());
            if (objects.containsKey(method.getName())) return objects.get(method.getName());
            Object result = custom.invoke(method.getName(), args);
            if (result != null || method.getReturnType() == void.class) return result;
            if (method.getName().equals("equals")) return proxy == args[0];
            if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
            throw new AssertionError("Unexpected public API call: " + method);
        }
    }
    private static final class Val implements InvocationHandler {
        Object current;
        final List<Object> observers = new ArrayList<>();
        boolean notifyWrites = true, notifyInitial = true, takeoverPending;
        int writes;
        Runnable beforeWrite = () -> {}, afterWrite = () -> {};
        Val(Object current) { this.current = current; }
        Object proxy(Class<?> type) { return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, this); }
        void emit(Object value) { current = value; for (Object callback : observers) notify(callback); }
        @SuppressWarnings("unchecked") void notify(Object callback) {
            if (callback instanceof IntegerValueChangedCallback)
                ((IntegerValueChangedCallback) callback).valueChanged((Integer) current);
            else if (callback instanceof BooleanValueChangedCallback)
                ((BooleanValueChangedCallback) callback).valueChanged((Boolean) current);
            else if (callback instanceof DoubleValueChangedCallback)
                ((DoubleValueChangedCallback) callback).valueChanged((Double) current);
            else if (callback instanceof ObjectValueChangedCallback)
                ((ObjectValueChangedCallback) callback).valueChanged(current);
            else throw new AssertionError("Unexpected observer type: " + callback);
        }
        @Override public Object invoke(Object proxy, Method method, Object[] args) {
            switch (method.getName()) {
                case "get": return current;
                case "markInterested": return null;
                case "addValueObserver":
                    // Native EnumValue rejects a StringValue callback despite the erased API signature.
                    if (proxy instanceof EnumValue) EnumValueChangedCallback.class.cast(args[0]);
                    observers.add(args[0]); if (notifyInitial) notify(args[0]); return null;
                case "set":
                case "setImmediately":
                    beforeWrite.run(); writes++;
                    if (method.getName().equals("set") && takeoverPending) return null;
                    current = args[0];
                    if (notifyWrites) emit(current);
                    afterWrite.run(); return null;
                default: throw new AssertionError("Unexpected value API call: " + method);
            }
        }
    }
}
