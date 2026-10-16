package dev.bitwigagent;

import com.bitwig.extension.controller.api.*;
import com.bitwig.extension.callback.*;
import com.google.gson.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;

/** Host-free contract tests. Proxies simulate callbacks, never a running Bitwig instance. */
public final class HostBridgeTest {
    private static int assertions;

    public static void main(String[] args) throws Exception {
        sameIdDeviceRebindDoesNotRequireObserverReplay();
        renamedSourceRetainsContinuousObservations();
        invalidInputDoesNotSchedule();
        initialObserverDataAndWindow();
        matchingWriteRequiresFreshCallback();
        staleValuesDoNotVerifyWrites();
        mismatchedValuesAreReported();
        staleTokensAndTargetsDoNotWrite();
        deadlineAndLateCallbacksDoNotWrite();
        missingSourceIdsDoNotReuseAnotherDevicesObservations();
        otherSourceCallbacksCannotVerifyWrites();
        sourceIdentityAndIdChangesInvalidateTokens();
        statusDiagnosticsAreScheduledAndSourceScoped();
        actionsStayOnHostThreadAndReportDispatchOnly();
        invalidAndExpiredActionsNeverInvoke();
        capabilitiesDescribeBoundedImplementedSurfaces();
        strictValidationPrecedesAllDomainScheduling();
        rootEarlyDirectCallbacksKeepLegacyAdmission();
        initialRootAndParentWindowOrderingKeepsUsableData();
        initialTrackPositionDoesNotRetireNestedSources();
        unassignedLayerIndexDoesNotRetireInitialSources();
        assignedLayerIndexDiscontinuitiesStayClosed();
        layerBindingRoundTripsKeepContinuousSources();
        layerWritesRequireExactSourceAndFreshCallbacks();
        layerRenamesRetainDataButRetireTokens();
        layerStructuralChangesFailClosedAcrossAba();
        layerDiscoveryReportsObservedWindowsAndReadiness();
        layerValidationPrecedesScheduling();
        layerMissingIdsAndUnreadableValuesStayExplicit();
        layerRebindDuringWriteHasUnknownOutcome();
        System.out.println("HostBridgeTest: " + assertions + " assertions passed (simulated host only)");
    }

    private static void sameIdDeviceRebindDoesNotRequireObserverReplay() {
        Fake host = new Fake(); HostBridge bridge = host.bridge();
        JsonObject first = call(host, bridge, "parameters.list", target());
        check(first.getAsJsonArray("parameters").get(0).getAsJsonObject().get("normalizedValue").getAsDouble() == .25,
                "device A has its own initial value");
        int idEmissions = host.idEmissions;
        JsonObject second = target(); second.addProperty("deviceIndex", 1);
        CompletableFuture<JsonObject> pending = bridge.handle("parameters.list", second, deadline());
        host.next();
        check(pending.isDone(), "same-ID device rebind must use source-bound observations without ID replay");
        JsonObject current = pending.join();
        check(current.getAsJsonArray("parameters").get(0).getAsJsonObject().get("normalizedValue").getAsDouble() == .6,
                "device B uses its own observed value, not device A's value");
        check(host.idEmissions == idEmissions, "binding must not depend on replaying unchanged parameter IDs");
        host.emitIds(0, new String[0]);
        host.emitValue(0, .11);
        host.nameObservers[0].directParameterNameChanged("gain", "Old device");
        JsonObject unchanged = call(host, bridge, "parameters.list", second);
        check(unchanged.getAsJsonArray("parameters").size() == 1, "A ID callback cannot change B's ID set");
        check(unchanged.getAsJsonArray("parameters").get(0).getAsJsonObject().get("normalizedValue").getAsDouble() == .6,
                "A value callback cannot populate B's cache");
        check(unchanged.getAsJsonArray("parameters").get(0).getAsJsonObject().get("name").getAsString().equals("Gain 1"),
                "A name callback cannot populate B's cache");
        check(unchanged.getAsJsonObject("target").get("contextToken").equals(current.getAsJsonObject("target").get("contextToken")),
                "A ID changes cannot invalidate B's token");
        JsonObject written = call(host, bridge, "parameters.set", write(current, .8));
        check(written.get("verification").getAsString().equals("observer_after_write"), "B write needs B's fresh callback");
        check(host.deviceWrites[0] == 0 && host.deviceWrites[1] == 1, "write dispatched exactly to bank device B");
        check(host.parameterValues[0] == .11, "writing B cannot change A");
        CompletableFuture<JsonObject> stale = bridge.handle("parameters.set", write(first, .75), deadline());
        host.next(); expectError(stale, "stale_context");
    }

    private static void renamedSourceRetainsContinuousObservations() {
        Fake host = new Fake(); HostBridge bridge = host.bridge();
        JsonObject previous = call(host, bridge, "parameters.list", target());
        int emissions = host.idEmissions;
        host.deviceNodes[0].value("name").set("Renamed");
        JsonObject renamed = target(); renamed.addProperty("expectedDeviceName", "Renamed");
        CompletableFuture<JsonObject> pending = bridge.handle("parameters.list", renamed, deadline());
        host.next();
        check(pending.isDone(), "renaming a continuously observed source must not require ID replay");
        JsonObject current = pending.join();
        check(current.getAsJsonArray("parameters").get(0).getAsJsonObject().get("normalizedValue").getAsDouble() == .25,
                "rename retains the same source's observed value");
        check(host.idEmissions == emissions, "metadata rename must not depend on parameter callback replay");
        JsonObject staleWrite = write(previous, .8); staleWrite.addProperty("expectedDeviceName", "Renamed");
        CompletableFuture<JsonObject> stale = bridge.handle("parameters.set", staleWrite, deadline());
        host.next(); expectError(stale, "stale_context");
        JsonObject freshWrite = write(current, .8);
        check(call(host, bridge, "parameters.set", freshWrite).get("verification").getAsString().equals("observer_after_write"),
                "fresh renamed context can still verify a write");
        check(host.deviceWrites[0] == 1 && host.deviceWrites[1] == 0, "renamed source write stays on the exact device");
    }

    private static void actionsStayOnHostThreadAndReportDispatchOnly() {
        Fake host = new Fake(); HostBridge bridge = host.bridge();
        CompletableFuture<JsonObject> pending = bridge.handle("actions.list", new JsonObject(), deadline());
        check(host.actionReads == 0 && !pending.isDone(), "catalog must await scheduled host callback");
        host.next();
        JsonArray actions = pending.join().getAsJsonArray("actions");
        check(actions.size() == 2, "real catalog entries retained");
        JsonObject action = actions.get(0).getAsJsonObject();
        check(action.get("actionId").getAsString().equals("fixture.action"), "catalog ID retained");
        check(action.get("name").getAsString().equals("Action 音色"), "localized name retained");
        check(action.getAsJsonObject("category").get("id").getAsString().equals("fixture.category"), "category metadata retained");
        check(actions.get(1).getAsJsonObject().get("category").isJsonNull(), "absent category explicit");
        pending = bridge.handle("actions.invoke", actionParams("fixture.action"), deadline());
        check(host.actionLookups == 0 && host.actionInvocations == 0, "network entry cannot query or invoke host action");
        host.next();
        JsonObject result = pending.join();
        check(host.actionInvocations == 1, "action invoked once");
        check(result.get("status").getAsString().equals("dispatched"), "Action has no completion observation");
        check(result.get("completion").getAsString().equals("not_observed"), "completion scope explicit");
    }

    private static void invalidAndExpiredActionsNeverInvoke() {
        Fake host = new Fake(); HostBridge bridge = host.bridge();
        for (JsonObject params : Arrays.asList(new JsonObject(), actionParams(""), actionParams("x".repeat(4097)))) {
            expectError(bridge.handle("actions.invoke", params, deadline()), "invalid_params");
        }
        JsonObject extra = actionParams("fixture.action"); extra.addProperty("extra", true);
        expectError(bridge.handle("actions.invoke", extra, deadline()), "invalid_params");
        expectError(bridge.handle("actions.list", extra, deadline()), "invalid_params");
        check(host.tasks.isEmpty(), "invalid Action arguments cannot schedule host work");
        CompletableFuture<JsonObject> pending = bridge.handle("actions.invoke", actionParams("missing"), deadline());
        host.next(); expectError(pending, "unknown_action");
        pending = bridge.handle("actions.invoke", actionParams("fixture.action"), System.nanoTime() - 1);
        host.next(); expectError(pending, "deadline_exceeded");
        host.slowActionLookup = true;
        int beforeSlowLookup = host.actionLookups;
        pending = bridge.handle("actions.invoke", actionParams("fixture.action"), System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(30));
        host.next(); expectError(pending, "deadline_exceeded");
        check(host.actionLookups == beforeSlowLookup + 1, "deadline test expires during lookup, before invoke");
        host.slowActionLookup = false;
        pending = bridge.handle("actions.invoke", actionParams("fixture.action"), deadline());
        pending.cancel(false); host.next();
        check(host.actionInvocations == 0, "unknown, expired, and abandoned Actions never invoke");
        pending = bridge.handle("actions.invoke", actionParams("fixture.action"), deadline());
        bridge.close(); host.next(); expectError(pending, "unavailable");
        check(host.actionInvocations == 0, "closed bridge never invokes Action");
    }

    private static JsonObject actionParams(String id) {
        JsonObject params = new JsonObject(); params.addProperty("actionId", id); return params;
    }

    private static void invalidInputDoesNotSchedule() {
        Fake host = new Fake();
        HostBridge bridge = host.bridge();
        JsonObject params = new JsonObject(); params.addProperty("extra", true);
        expectError(bridge.handle("status", params, deadline()), "invalid_params");
        check(host.tasks.isEmpty(), "invalid request must not schedule host work");
        params = target(); params.addProperty("deviceIndex", 1.5);
        expectError(bridge.handle("parameters.list", params, deadline()), "invalid_params");
        params = target(); params.addProperty("deviceIndex", 16);
        expectError(bridge.handle("parameters.list", params, deadline()), "invalid_params");
    }

    private static void initialObserverDataAndWindow() {
        Fake host = new Fake(); HostBridge bridge = host.bridge();
        JsonObject result = call(host, bridge, "parameters.list", target());
        check(result.getAsJsonArray("parameters").get(0).getAsJsonObject().get("normalizedValue").getAsDouble() == .25,
                "initial data belongs to the requested bank proxy");
        check(host.tasks.isEmpty(), "cached parameter listing completes without extra scheduled polling");
        JsonObject tracks = call(host, bridge, "tracks.list", new JsonObject());
        check(tracks.getAsJsonObject("window").get("capacity").getAsInt() == 16, "bounded track window");
        check(!tracks.getAsJsonObject("window").get("complete").getAsBoolean(), "20 tracks cannot be declared complete");
        JsonObject params = new JsonObject(); params.addProperty("trackId", Fake.TRACK_ID);
        JsonObject devices = call(host, bridge, "devices.list", params);
        check(devices.getAsJsonArray("devices").size() == 2, "only existing devices listed");
        params.addProperty("trackId", "outside");
        CompletableFuture<JsonObject> future = bridge.handle("devices.list", params, deadline()); host.next();
        expectError(future, "outside_window");
    }

    private static void matchingWriteRequiresFreshCallback() {
        Fake host = new Fake(); HostBridge bridge = host.bridge();
        JsonObject listing = call(host, bridge, "parameters.list", target());
        host.writeMode = "match";
        JsonObject result = call(host, bridge, "parameters.set", write(listing, .75));
        check(host.writes == 1, "one dispatch");
        check(result.get("status").getAsString().equals("observed"), "matching callback observed");
        check(result.get("verification").getAsString().equals("observer_after_write"), "fresh callback evidence");
        check(result.get("matchesRequested").getAsBoolean(), "readback within declared tolerance");
        result = call(host, bridge, "parameters.set", write(listing, .75));
        check(host.writes == 1, "already satisfied absolute set need not dispatch");
        check(result.get("verification").getAsString().equals("already_at_value"), "no-op explicitly identified");
    }

    private static void staleValuesDoNotVerifyWrites() throws Exception {
        Fake host = new Fake(); HostBridge bridge = host.bridge();
        JsonObject listing = call(host, bridge, "parameters.list", target());
        host.writeMode = "none";
        CompletableFuture<JsonObject> result = bridge.handle("parameters.set", write(listing, .75), shortDeadline());
        host.next();
        check(!result.isDone(), "cached value is not fresh readback");
        Thread.sleep(90); host.next();
        JsonObject response = result.join();
        check(response.get("status").getAsString().equals("dispatched"), "unobserved write remains dispatched");
        check(response.get("observedValue").isJsonNull(), "old pre-write value not reported as post-write evidence");
        check(response.get("verification").getAsString().equals("not_observed"), "honest unobserved state");
    }

    private static void mismatchedValuesAreReported() throws Exception {
        Fake host = new Fake(); HostBridge bridge = host.bridge();
        JsonObject listing = call(host, bridge, "parameters.list", target());
        host.writeMode = "mismatch";
        CompletableFuture<JsonObject> result = bridge.handle("parameters.set", write(listing, .75), shortDeadline());
        host.next(); Thread.sleep(90); host.next();
        JsonObject response = result.join();
        check(response.get("status").getAsString().equals("dispatched"), "mismatch is not observed success");
        check(response.get("verification").getAsString().equals("readback_mismatch"), "mismatch explicit");
        check(response.get("observedValue").getAsDouble() == .4, "actual mismatch value retained");
        check(!response.get("matchesRequested").getAsBoolean(), "match false");
    }

    private static void staleTokensAndTargetsDoNotWrite() {
        Fake host = new Fake(); HostBridge bridge = host.bridge();
        JsonObject listing = call(host, bridge, "parameters.list", target());
        JsonObject params = write(listing, .75); params.addProperty("contextToken", "stale");
        CompletableFuture<JsonObject> result = bridge.handle("parameters.set", params, deadline()); host.next();
        expectError(result, "stale_context");
        host.deviceNodes[0].value("name").set("Changed");
        result = bridge.handle("parameters.set", write(listing, .75), deadline()); host.next();
        expectError(result, "stale_target");
        check(host.writes == 0, "stale target never dispatched");
    }

    private static void deadlineAndLateCallbacksDoNotWrite() {
        Fake host = new Fake(); HostBridge bridge = host.bridge();
        JsonObject listing = call(host, bridge, "parameters.list", target());
        CompletableFuture<JsonObject> result = bridge.handle("parameters.set", write(listing, .75), System.nanoTime() - 1);
        host.next();
        expectError(result, "deadline_exceeded");
        check(host.writes == 0, "expired queued operation cannot mutate");
        result = bridge.handle("parameters.set", write(listing, .75), deadline());
        result.completeExceptionally(new IllegalStateException("caller abandoned operation"));
        host.next();
        check(host.writes == 0, "late callback skips completed request");
        result = bridge.handle("parameters.set", write(listing, .75), deadline());
        bridge.close(); host.next(); expectError(result, "unavailable");
        check(host.writes == 0, "closed bridge cannot write");
    }

    private static void missingSourceIdsDoNotReuseAnotherDevicesObservations() {
        Fake host = new Fake(); host.initialIds[1] = false; HostBridge bridge = host.bridge();
        JsonObject previous = call(host, bridge, "parameters.list", target());
        JsonObject second = target(); second.addProperty("deviceIndex", 1);
        CompletableFuture<JsonObject> pending = bridge.handle("parameters.list", second, deadline()); host.next();
        check(!pending.isDone(), "B cannot reuse A's IDs when B has never reported its own list");
        CompletableFuture<JsonObject> busy = bridge.handle("parameters.set", write(previous, .75), deadline());
        host.next(); host.next();
        expectError(busy, "busy");
        host.emitIds(0); host.emitValue(0, .9); host.next();
        check(!pending.isDone(), "A callbacks cannot make B ready");
        host.emitIds(1); host.emitValue(1, .6); host.next();
        JsonObject current = pending.join();
        check(current.getAsJsonArray("parameters").get(0).getAsJsonObject().get("normalizedValue").getAsDouble() == .6,
                "B becomes ready only from B's continuously observed stream");
        check(!current.getAsJsonObject("target").get("contextToken").getAsString().equals(
                previous.getAsJsonObject("target").get("contextToken").getAsString()), "rebind changes token");
        CompletableFuture<JsonObject> stale = bridge.handle("parameters.set", write(previous, .75), deadline()); host.next();
        expectError(stale, "stale_context");
        check(host.writes == 0, "old context cannot write after rebind");
    }

    private static void otherSourceCallbacksCannotVerifyWrites() {
        Fake host = new Fake(); HostBridge bridge = host.bridge();
        JsonObject second = target(); second.addProperty("deviceIndex", 1);
        JsonObject listing = call(host, bridge, "parameters.list", second);
        host.writeMode = "none";
        CompletableFuture<JsonObject> pending = bridge.handle("parameters.set", write(listing, .75), deadline());
        host.next();
        host.emitValue(0, .75); host.next();
        check(!pending.isDone(), "A's fresh matching value cannot verify a write to B");
        host.emitValue(1, .75); host.next();
        check(pending.join().get("verification").getAsString().equals("observer_after_write"),
                "only B's fresh callback verifies B's write");
        check(host.deviceWrites[0] == 0 && host.deviceWrites[1] == 1, "no cursor or wrong-device write path");
    }

    private static void sourceIdentityAndIdChangesInvalidateTokens() {
        Fake host = new Fake(); HostBridge bridge = host.bridge();
        JsonObject listing = call(host, bridge, "parameters.list", target());
        host.emitIds(0, new String[]{"gain", "tone"});
        JsonObject changed = call(host, bridge, "parameters.list", target());
        check(!changed.getAsJsonObject("target").get("contextToken").equals(listing.getAsJsonObject("target").get("contextToken")),
                "observed ID-set change invalidates the token");
        check(!changed.getAsJsonArray("parameters").get(1).getAsJsonObject().get("readable").getAsBoolean(),
                "new IDs cannot inherit another parameter's value");
        CompletableFuture<JsonObject> stale = bridge.handle("parameters.set", write(listing, .75), deadline());
        host.next(); expectError(stale, "stale_context");
        host.deviceNodes[0].value("exists").set(false);
        host.emitIds(0); host.emitValue(0, .9);
        host.deviceNodes[0].value("exists").set(true);
        CompletableFuture<JsonObject> pending = bridge.handle("parameters.list", target(), deadline()); host.next();
        check(!pending.isDone(), "observable proxy replacement clears old parameter data");
        host.emitIds(0); host.emitValue(0, .35); host.next();
        JsonObject current = pending.join();
        check(current.getAsJsonArray("parameters").get(0).getAsJsonObject().get("normalizedValue").getAsDouble() == .35,
                "nonexistent-source callbacks cannot repopulate a replacement cache");
        check(!current.getAsJsonObject("target").get("contextToken").equals(changed.getAsJsonObject("target").get("contextToken")),
                "existence transition invalidates the old token");
        host.trackNodes[0].value("channelId").set("replacement-track");
        host.trackNodes[0].value("channelId").set(Fake.TRACK_ID);
        stale = bridge.handle("parameters.set", write(current, .75), deadline()); host.next();
        expectError(stale, "stale_context");
        check(host.writes == 0, "observable track replacement cannot reuse a token");
    }

    private static void statusDiagnosticsAreScheduledAndSourceScoped() {
        Fake host = new Fake(); HostBridge bridge = host.bridge();
        CompletableFuture<JsonObject> pending = bridge.handle("status", new JsonObject(), deadline());
        check(!pending.isDone(), "status must await the scheduled host task");
        host.next();
        JsonObject diagnostics = pending.join().getAsJsonObject("parameterBindingDiagnostics");
        check(diagnostics.get("target").isJsonNull() && diagnostics.get("idCount").isJsonNull(),
                "no binding must not claim a source cache");
        call(host, bridge, "parameters.list", target());
        diagnostics = call(host, bridge, "status", new JsonObject()).getAsJsonObject("parameterBindingDiagnostics");
        check(diagnostics.get("matches").getAsBoolean() && diagnostics.get("idsObserved").getAsBoolean(),
                "diagnostics describe the bound bank source");
        long received = diagnostics.get("idCallbacksReceived").getAsLong();
        host.emitIds(1);
        diagnostics = call(host, bridge, "status", new JsonObject()).getAsJsonObject("parameterBindingDiagnostics");
        check(diagnostics.get("idCallbacksReceived").getAsLong() == received,
                "another source's callback does not change the bound-source counters");
        check(diagnostics.get("cacheGeneration").getAsLong() == 0, "binding alone must not clear continuous observations");
    }

    private static void capabilitiesDescribeBoundedImplementedSurfaces() {
        Fake host = new Fake(); HostBridge bridge = host.bridge();
        CompletableFuture<JsonObject> pending = bridge.handle("capabilities", new JsonObject(), deadline());
        check(!pending.isDone(), "capabilities must read host windows on the scheduled host thread");
        host.next();
        JsonObject result = pending.join();
        check(result.getAsJsonObject("windows").getAsJsonObject("tracks").get("total").getAsInt() == 20,
                "discovery reports the actual host track count, not the window capacity");
        check(!result.getAsJsonObject("windows").getAsJsonObject("tracks").get("complete").getAsBoolean(),
                "capabilities cannot promise a full project beyond the bounded bank");
    }

    private static void strictValidationPrecedesAllDomainScheduling() {
        Fake host = new Fake(); HostBridge bridge = host.bridge();
        for (String method : List.of("capabilities", "transport.get", "devices.catalog")) {
            JsonObject extra = new JsonObject(); extra.addProperty("unexpected", true);
            expectError(bridge.handle(method, extra, deadline()), "invalid_params");
        }
        JsonObject fraction = target();
        fraction.add("deviceIndex", new JsonPrimitive(new java.math.BigDecimal("1.0000000000000000001")));
        expectError(bridge.handle("parameters.list", fraction, deadline()), "invalid_params");
        JsonObject nullName = target(); nullName.add("expectedDeviceName", JsonNull.INSTANCE);
        expectError(bridge.handle("parameters.list", nullName, deadline()), "invalid_params");
        expectError(bridge.handle("capabilities", null, deadline()), "invalid_params");
        expectError(bridge.handle("not.a.method", new JsonObject(), deadline()), "unknown_method");
        check(host.tasks.isEmpty(), "strict validation rejects every invalid domain request before scheduling");
    }

    private static void initialRootAndParentWindowOrderingKeepsUsableData() {
        List<String> failures = new ArrayList<>();
        Fake early = new Fake(); early.deviceNodes[1].value("position").set(0);
        HostBridge earlyBridge = early.bridge();
        early.deviceNodes[1].value("position").value = 1;
        early.emitIds(1); early.emitValue(1, .66);
        int emissions = early.idEmissions;
        early.deviceNodes[1].value("position").set(1);
        JsonObject root = target(); root.addProperty("deviceIndex", 1);
        CompletableFuture<JsonObject> listed = earlyBridge.handle("parameters.list", root, deadline()); early.next();
        if (!listed.isDone() || listed.isCompletedExceptionally() || parameterValue(listed.join()) != .66
                || early.idEmissions != emissions)
            failures.add("late agreeing root position discarded current callback data");
        for (int initialCount : List.of(0, 1)) {
            Fake pending = new Fake();
            Node bank = (Node) Proxy.getInvocationHandler(pending.trackNodes[0].objects.get("createDeviceBank"));
            bank.value("itemCount").set(initialCount); bank.value("scrollPosition").set(initialCount);
            pending.deviceNodes[0].value("position").set(initialCount);
            HostBridge bridge = pending.bridge();
            JsonObject parent = target(); parent.addProperty("deviceIndex", initialCount);
            JsonObject request = new JsonObject(); request.add("parent", parent);
            CompletableFuture<JsonObject> unavailable = bridge.handle("devices.layers.list", request, deadline()); pending.next();
            if (!unavailable.isCompletedExceptionally()) failures.add("root count " + initialCount + " admitted an out-of-count parent");
            bank.value("itemCount").set(initialCount + 1);
            pending.emitChildIds(0, 0, new String[]{"gain"}); pending.emitChildValue(0, 0, .42);
            JsonObject discovery = call(pending, bridge, "devices.layers.list", request);
            JsonObject child = discovery.getAsJsonArray("layers").get(0).getAsJsonObject()
                    .getAsJsonArray("devices").get(0).getAsJsonObject();
            if (!child.has("discoveryToken")) failures.add("initial count population retired parent " + initialCount);
            else if (parameterValue(call(pending, bridge, "parameters.layer.list", layerTarget(discovery, 0, 0))) != .42)
                failures.add("initial count population lost current child observation " + initialCount);
        }
        check(failures.isEmpty(), "initial observation ordering must not invent a rebind or callback replay: " + failures);
    }

    private static void initialTrackPositionDoesNotRetireNestedSources() {
        Fake f = new Fake(); f.trackNodes[0].value("position").set(-1);
        HostBridge bridge = f.bridge();
        int rootEmissions = f.idEmissions, childEmissions = f.childIdEmissions;
        List<String> failures = new ArrayList<>();
        CompletableFuture<JsonObject> initial = bridge.handle("devices.layers.list", layerDiscovery(), deadline());
        f.next();
        if (initial.isCompletedExceptionally()) expectError(initial, "not_ready");
        else failures.add("unassigned track position admitted nested discovery");
        f.trackNodes[0].value("position").set(0);
        JsonObject discovery = call(f, bridge, "devices.layers.list", layerDiscovery());
        for (int l = 0; l < 2; l++) for (int c = 0; c < 2; c++) {
            JsonObject child = discovery.getAsJsonArray("layers").get(l).getAsJsonObject()
                    .getAsJsonArray("devices").get(c).getAsJsonObject();
            if (!child.has("discoveryToken")) failures.add("first assigned track position retired child " + l + "/" + c);
        }
        check(failures.isEmpty(), "initial track position must establish ancestry, not replace it: " + failures);
        check(parameterValue(call(f, bridge, "parameters.list", target())) == .25 && f.idEmissions == rootEmissions,
                "first assigned track position preserves the independently accepted root getter tuple");
        check(f.childIdEmissions == childEmissions, "metadata completion does not synthesize child ID callbacks");
        for (int l = 0; l < 2; l++) for (int c = 0; c < 2; c++) {
            CompletableFuture<JsonObject> pending = bridge.handle("parameters.layer.list", layerTarget(discovery, l, c), deadline());
            f.next();
            check(!pending.isDone(), "child observations rejected before track assignment are not resurrected");
            double value = .21 + l * .1 + c * .01;
            f.emitChildIds(l, c, new String[]{"gain"}); f.emitChildValue(l, c, value); f.next();
            check(parameterValue(pending.join()) == value, "actual fresh child callback is admitted after first track assignment");
        }
    }

    private static void unassignedLayerIndexDoesNotRetireInitialSources() {
        for (boolean notifyUnassigned : List.of(false, true)) {
            Fake f = new Fake(); f.trackNodes[0].value("position").set(-1);
            AssignmentInteger[] indices = {new AssignmentInteger(), new AssignmentInteger()};
            for (int l = 0; l < 2; l++) {
                f.layerNodes[l].values.put("channelIndex", indices[l]);
                f.layerNodes[l].value("exists").set(false);
                f.layerNodes[l].value("channelId").set("");
            }
            HostBridge bridge = f.bridge();
            int emissions = f.childIdEmissions;
            for (int l = 0; l < 2; l++) {
                if (notifyUnassigned) indices[l].unassign();
                f.layerNodes[l].value("channelId").set(layerId(l));
                f.layerNodes[l].value("exists").set(true);
            }
            f.trackNodes[0].value("position").set(8);
            for (int l = 0; l < 2; l++) indices[l].set(9 + l);
            JsonObject discovery = call(f, bridge, "devices.layers.list", layerDiscovery());
            List<String> retired = new ArrayList<>();
            for (int l = 0; l < 2; l++) for (int c = 0; c < 2; c++) {
                JsonObject child = discovery.getAsJsonArray("layers").get(l).getAsJsonObject()
                        .getAsJsonArray("devices").get(c).getAsJsonObject();
                if (!child.has("discoveryToken")) retired.add(l + "/" + c);
            }
            check(retired.isEmpty(), "first assigned channel indices must not retire initial children: " + retired);
            check(f.childIdEmissions == emissions, "index assignment cannot replay rejected initial ID callbacks");
            for (int l = 0; l < 2; l++) for (int c = 0; c < 2; c++) {
                CompletableFuture<JsonObject> pending = bridge.handle("parameters.layer.list", layerTarget(discovery, l, c), deadline());
                f.next();
                check(!pending.isDone(), "metadata completion cannot resurrect rejected child observations");
                double expected = .21 + l * .1 + c * .01;
                f.emitChildIds(l, c, new String[]{"gain"}); f.emitChildValue(l, c, expected); f.next();
                check(parameterValue(pending.join()) == expected, "actual post-assignment callback supplies the correct child value");
            }
        }
    }

    private static void assignedLayerIndexDiscontinuitiesStayClosed() {
        for (int original : List.of(0, 9)) for (boolean incomplete : List.of(false, true)) {
            Fake f = new Fake(); AssignmentInteger index = new AssignmentInteger();
            f.layerNodes[0].values.put("channelIndex", index);
            if (incomplete) f.childNodes[0][0].value("position").publishInitial = false;
            HostBridge bridge = f.bridge();
            // A real native -1 notification followed by 0 makes assigned zero observable;
            // assigning cached zero directly is suppressed by the native proxy.
            index.set(-1); index.set(original);
            index.set(original + 1); index.set(original);
            if (incomplete) f.childNodes[0][0].value("position").set(0);
            f.emitChildIds(0, 0, new String[]{"gain"}); f.emitChildValue(0, 0, .91);
            JsonObject discovery = call(f, bridge, "devices.layers.list", layerDiscovery());
            JsonObject child = discovery.getAsJsonArray("layers").get(0).getAsJsonObject()
                    .getAsJsonArray("devices").get(0).getAsJsonObject();
            check(!child.has("discoveryToken") && child.get("reason").getAsString().contains("provenance"),
                    "observed assigned index ABA stays retired, including zero and incomplete child metadata");
        }
        Fake f = new Fake(); AssignmentInteger index = new AssignmentInteger();
        f.layerNodes[0].values.put("channelIndex", index);
        HostBridge bridge = f.bridge(); index.set(0);
        JsonObject discovery = call(f, bridge, "devices.layers.list", layerDiscovery());
        JsonObject layer = discovery.getAsJsonArray("layers").get(0).getAsJsonObject();
        check(layer.get("channelIndex").isJsonNull() && layer.get("readiness").getAsString().equals("not_ready"),
                "a suppressed assigned-zero callback must remain unobserved, not fabricated from a getter default");
    }

    private static void rootEarlyDirectCallbacksKeepLegacyAdmission() {
        Fake f = new Fake();
        for (String field : List.of("exists", "position", "name", "deviceType")) f.deviceNodes[0].value(field).publishInitial = false;
        HostBridge bridge = f.bridge();
        int rootEmissions = f.idEmissions, childEmissions = f.childIdEmissions;
        for (String field : List.of("exists", "position", "name", "deviceType")) {
            Val value = f.deviceNodes[0].value(field); value.set(value.current());
        }
        JsonObject listing = call(f, bridge, "parameters.list", target());
        check(parameterValue(listing) == .25 && f.idEmissions == rootEmissions,
                "root's actual initial direct callback is retained before agreeing metadata notifications, without replay");
        JsonObject address = layerTarget(f, bridge, 0, 0);
        CompletableFuture<JsonObject> pending = bridge.handle("parameters.layer.list", address, deadline()); f.next();
        check(!pending.isDone() && f.childIdEmissions == childEmissions,
                "nested callbacks rejected before observed ancestry are not resurrected by later metadata");
        f.emitChildIds(0, 0, new String[]{"gain"}); f.emitChildValue(0, 0, .18); f.next();
        check(parameterValue(pending.join()) == .18, "newly ready nested cache requires actual accepted callbacks");
    }

    private static void layerBindingRoundTripsKeepContinuousSources() {
        Fake f = new Fake(); HostBridge bridge = f.bridge();
        JsonObject flat = call(f, bridge, "parameters.list", target());
        JsonObject a = layerTarget(f, bridge, 0, 0), b = layerTarget(f, bridge, 1, 1);
        JsonObject first = call(f, bridge, "parameters.layer.list", a);
        int emissions = f.childIdEmissions;
        check(parameterValue(first) == .15, "layer A reads its own continuously observed value");
        JsonObject second = call(f, bridge, "parameters.layer.list", b);
        check(parameterValue(second) == .75, "same-name layer and child B resolve by observed UUID and index");
        JsonObject again = call(f, bridge, "parameters.layer.list", a);
        check(parameterValue(again) == .15 && f.childIdEmissions == emissions, "A to B to A needs no callback replay");
        check(!first.getAsJsonObject("target").get("contextToken").equals(again.getAsJsonObject("target").get("contextToken")),
                "returning to A issues a new active binding token");
        CompletableFuture<JsonObject> stale = bridge.handle("parameters.layer.set", layerWrite(first, .8), deadline());
        f.next(); expectError(stale, "stale_context");
        stale = bridge.handle("parameters.set", write(flat, .8), deadline()); f.next(); expectError(stale, "stale_context");
        JsonObject restored = call(f, bridge, "parameters.list", target());
        check(parameterValue(restored) == .25, "flat to layer to flat keeps the root source cache");
        stale = bridge.handle("parameters.layer.set", layerWrite(again, .8), deadline()); f.next(); expectError(stale, "stale_context");
        JsonObject stable = call(f, bridge, "parameters.list", target());
        check(stable.getAsJsonObject("target").get("contextToken").equals(restored.getAsJsonObject("target").get("contextToken")),
                "repeated current binding preserves its token");
    }

    private static void layerWritesRequireExactSourceAndFreshCallbacks() {
        Fake f = new Fake(); HostBridge bridge = f.bridge();
        JsonObject listing = call(f, bridge, "parameters.layer.list", layerTarget(f, bridge, 0, 1));
        JsonObject noOp = call(f, bridge, "parameters.layer.set", layerWrite(listing, .35));
        check(noOp.get("verification").getAsString().equals("already_at_value") && f.childWrites[0][1] == 0,
                "nested no-op is distinct from post-dispatch observation");
        f.writeMode = "none";
        CompletableFuture<JsonObject> write = bridge.handle("parameters.layer.set", layerWrite(listing, .8), deadline());
        f.next(); check(!write.isDone(), "nested setter dispatch cannot acknowledge a write");
        double applied = Math.round(.8 * (HostServices.RESOLUTION - 1)) / (double) (HostServices.RESOLUTION - 1);
        f.emitChildValue(1, 1, applied); f.emitValue(0, applied); f.next();
        check(!write.isDone(), "same-ID root and sibling callbacks cannot verify the child write");
        CompletableFuture<JsonObject> busy = bridge.handle("parameters.list", target(), deadline());
        f.next(); f.next(); expectError(busy, "busy");
        f.emitChildValue(0, 1, applied); f.next();
        check(write.join().get("verification").getAsString().equals("observer_after_write"), "exact child fresh callback verifies");
        check(f.childWrites[0][1] == 1 && f.childWrites[0][0] == 0 && f.childWrites[1][0] == 0
                        && f.childWrites[1][1] == 0 && f.writes == 0,
                "only addressed child setter dispatches, with no root or sibling mutation");
        check(f.childParameterValues[0][0] == .15 && f.childParameterValues[1][0] == .55,
                "untouched child values stay unchanged");
    }

    private static void layerRenamesRetainDataButRetireTokens() {
        for (String renamed : List.of("parent", "layer", "child")) {
            Fake f = new Fake(); HostBridge bridge = f.bridge();
            JsonObject oldAddress = layerTarget(f, bridge, 0, 0);
            JsonObject oldListing = call(f, bridge, "parameters.layer.list", oldAddress);
            int emissions = f.childIdEmissions;
            Node node = renamed.equals("parent") ? f.deviceNodes[0] : renamed.equals("layer") ? f.layerNodes[0] : f.childNodes[0][0];
            node.value("name").set("Renamed");
            JsonObject parent = target(); if (renamed.equals("parent")) parent.addProperty("expectedDeviceName", "Renamed");
            JsonObject discovery = new JsonObject(); discovery.add("parent", parent);
            JsonObject address = layerTarget(call(f, bridge, "devices.layers.list", discovery), 0, 0);
            JsonObject staleAddress = address.deepCopy(); staleAddress.add("discoveryToken", oldAddress.get("discoveryToken"));
            CompletableFuture<JsonObject> stale = bridge.handle("parameters.layer.list", staleAddress, deadline());
            f.next(); expectError(stale, "stale_context");
            JsonObject fresh = call(f, bridge, "parameters.layer.list", address);
            check(parameterValue(fresh) == .15 && f.childIdEmissions == emissions,
                    renamed + " rename preserves continuous direct observations without replay");
            JsonObject staleWrite = layerWrite(fresh, .8); staleWrite.add("contextToken", oldListing.getAsJsonObject("target").get("contextToken"));
            stale = bridge.handle("parameters.layer.set", staleWrite, deadline()); f.next(); expectError(stale, "stale_context");
            check(call(f, bridge, "parameters.layer.set", layerWrite(fresh, .8)).get("verification").getAsString().equals("observer_after_write"),
                    renamed + " fresh rename context still writes its exact child");
        }
    }

    private static void layerStructuralChangesFailClosedAcrossAba() {
        for (String changed : List.of("parent", "track", "layer", "layer_window", "child", "child_type", "child_window")) {
            Fake f = new Fake(); HostBridge bridge = f.bridge();
            JsonObject address = layerTarget(f, bridge, 0, 0);
            call(f, bridge, "parameters.layer.list", address);
            Val value; Object replacement;
            switch (changed) {
                case "parent": value = f.deviceNodes[0].value("exists"); replacement = false; break;
                case "track": value = f.trackNodes[0].value("channelId"); replacement = layerId(1); break;
                case "layer": value = f.layerNodes[0].value("channelId"); replacement = layerId(1); break;
                case "layer_window": value = f.layerBank.value("scrollPosition"); replacement = 1; break;
                case "child": value = f.childNodes[0][0].value("exists"); replacement = false; break;
                case "child_type": value = f.childNodes[0][0].value("deviceType"); replacement = "instrument"; break;
                default: value = f.childBanks[0].value("scrollPosition"); replacement = 1;
            }
            Object original = value.current(); value.set(replacement);
            f.emitChildIds(0, 0, new String[]{"gain"}); f.emitChildValue(0, 0, .91);
            value.set(original);
            f.emitChildIds(0, 0, new String[]{"gain"}); f.emitChildValue(0, 0, .92);
            CompletableFuture<JsonObject> stale = bridge.handle("parameters.layer.list", address, deadline());
            f.next(); expectError(stale, "not_ready");
            JsonObject discovery = call(f, bridge, "devices.layers.list", layerDiscovery());
            JsonObject child = discovery.getAsJsonArray("layers").get(0).getAsJsonObject().getAsJsonArray("devices").get(0).getAsJsonObject();
            check(child.get("readiness").getAsString().equals("not_ready") && !child.has("discoveryToken")
                            && child.get("reason").getAsString().contains("provenance"),
                    changed + " ABA cannot use delayed same-proxy callbacks as a provenance boundary");
            if (changed.equals("track")) { f.emitIds(1); f.emitValue(1, .6); }
            JsonObject other = target(); other.addProperty("deviceIndex", 1);
            check(call(f, bridge, "parameters.list", other).getAsJsonArray("parameters").size() == 1,
                    "root direct behavior remains separate; track rebind recovery uses actual root callbacks");
        }
        Fake early = new Fake(); early.childNodes[0][0].value("position").publishInitial = false;
        HostBridge earlyBridge = early.bridge();
        early.layerNodes[0].value("channelId").set(layerId(1));
        early.layerNodes[0].value("channelId").set(layerId(0));
        early.childNodes[0][0].value("position").set(0);
        early.emitChildIds(0, 0, new String[]{"gain"}); early.emitChildValue(0, 0, .9);
        JsonObject discovery = call(early, earlyBridge, "devices.layers.list", layerDiscovery());
        JsonObject child = discovery.getAsJsonArray("layers").get(0).getAsJsonObject().getAsJsonArray("devices").get(0).getAsJsonObject();
        check(!child.has("discoveryToken") && child.get("reason").getAsString().contains("provenance"),
                "known layer UUID replacement retires even a child whose initial metadata was incomplete");
        List<String> unsafe = new ArrayList<>();
        for (String event : List.of("child_remove", "child_position", "child_type",
                "layer_support", "layer_bank_exists", "layer_bank_count", "layer_bank_offset",
                "child_bank_exists", "child_bank_count", "child_bank_offset",
                "root_remove", "root_position", "root_type", "track_remove", "track_uuid",
                "track_position", "track_window", "root_window", "root_count")) {
            Fake pending = new Fake();
            boolean ancestor = event.startsWith("root_") || event.startsWith("track_");
            Node incomplete = ancestor ? pending.deviceNodes[0] : pending.childNodes[0][0];
            String missing = event.endsWith("_position") ? "deviceType" : "position";
            Object initial = incomplete.value(missing).current();
            incomplete.value(missing).publishInitial = false;
            if (ancestor) incomplete.value("exists").set(false);
            HostBridge pendingBridge = pending.bridge();
            if (ancestor) incomplete.value("exists").set(true);
            Node rootBank = (Node) Proxy.getInvocationHandler(pending.trackNodes[0].objects.get("createDeviceBank"));
            Val changed; Object replacement;
            switch (event) {
                case "child_remove": changed = incomplete.value("exists"); replacement = false; break;
                case "child_position": changed = incomplete.value("position"); replacement = 1; break;
                case "child_type": changed = incomplete.value("deviceType"); replacement = "instrument"; break;
                case "layer_support": changed = pending.deviceNodes[0].value("hasLayers"); replacement = false; break;
                case "layer_bank_exists": changed = pending.layerBank.value("exists"); replacement = false; break;
                case "layer_bank_count": changed = pending.layerBank.value("itemCount"); replacement = 1; break;
                case "layer_bank_offset": changed = pending.layerBank.value("scrollPosition"); replacement = 1; break;
                case "child_bank_exists": changed = pending.childBanks[0].value("exists"); replacement = false; break;
                case "child_bank_count": changed = pending.childBanks[0].value("itemCount"); replacement = 1; break;
                case "child_bank_offset": changed = pending.childBanks[0].value("scrollPosition"); replacement = 1; break;
                case "root_remove": changed = incomplete.value("exists"); replacement = false; break;
                case "root_position": changed = incomplete.value("position"); replacement = 1; break;
                case "root_type": changed = incomplete.value("deviceType"); replacement = "instrument"; break;
                case "track_remove": changed = pending.trackNodes[0].value("exists"); replacement = false; break;
                case "track_uuid": changed = pending.trackNodes[0].value("channelId"); replacement = layerId(1); break;
                case "track_position": changed = pending.trackNodes[0].value("position"); replacement = 1; break;
                case "track_window": changed = pending.trackBank.value("scrollPosition"); replacement = 1; break;
                case "root_window": changed = rootBank.value("scrollPosition"); replacement = 1; break;
                default: changed = rootBank.value("itemCount"); replacement = 1;
            }
            Object original = changed.current(); changed.set(replacement); changed.set(original);
            incomplete.value(missing).set(initial);
            pending.emitChildIds(0, 0, new String[]{"gain"}); pending.emitChildValue(0, 0, .91);
            JsonObject after = call(pending, pendingBridge, "devices.layers.list", layerDiscovery());
            JsonObject candidate = after.getAsJsonArray("layers").get(0).getAsJsonObject()
                    .getAsJsonArray("devices").get(0).getAsJsonObject();
            if (candidate.has("discoveryToken") || !candidate.get("readiness").getAsString().equals("not_ready"))
                unsafe.add(event);
        }
        check(unsafe.isEmpty(), "known discontinuities before complete metadata must reject delayed predecessor data: " + unsafe);
        Fake initial = new Fake(); initial.deviceNodes[0].value("exists").set(false);
        initial.deviceNodes[0].value("position").publishInitial = false;
        HostBridge initialBridge = initial.bridge();
        initial.deviceNodes[0].value("exists").set(true); initial.deviceNodes[0].value("position").set(0);
        initial.emitChildIds(0, 0, new String[]{"gain"}); initial.emitChildValue(0, 0, .44);
        check(parameterValue(call(initial, initialBridge, "parameters.layer.list", layerTarget(initial, initialBridge, 0, 0))) == .44,
                "first presence and first metadata with actual callbacks are not mistaken for physical replacement");
    }

    private static void layerDiscoveryReportsObservedWindowsAndReadiness() {
        Fake f = new Fake(); f.deviceNodes[0].value("hasLayers").publishInitial = false;
        HostBridge bridge = f.bridge();
        CompletableFuture<JsonObject> pending = bridge.handle("devices.layers.list", layerDiscovery(), deadline());
        f.next(); expectError(pending, "not_ready");
        f.deviceNodes[0].value("hasLayers").set(true);
        JsonObject discovery = call(f, bridge, "devices.layers.list", layerDiscovery());
        check(discovery.getAsJsonObject("window").get("capacity").getAsInt() == 2
                        && discovery.getAsJsonObject("window").get("complete").getAsBoolean(),
                "fixed observed layer window reports actual total and completeness");
        check(discovery.getAsJsonArray("layers").get(0).getAsJsonObject().get("channelIndex").getAsInt() == 10,
                "observed channelIndex is not fabricated from the bank-local slot");
        f.layerBank.value("itemCount").set(3);
        discovery = call(f, bridge, "devices.layers.list", layerDiscovery());
        check(!discovery.getAsJsonObject("window").get("complete").getAsBoolean()
                        && discovery.getAsJsonObject("window").get("total").getAsInt() == 3,
                "larger actual layer counts do not pretend the bounded window is complete");
        f.deviceNodes[0].value("hasLayers").set(false);
        pending = bridge.handle("devices.layers.list", layerDiscovery(), deadline()); f.next(); expectError(pending, "unsupported_scope");

        Fake empty = new Fake(); empty.layerBank.value("itemCount").value = 0;
        for (int l = 0; l < 2; l++) {
            empty.layerNodes[l].value("exists").value = false;
            empty.childBanks[l].value("itemCount").value = 0;
            for (int c = 0; c < 2; c++) empty.childNodes[l][c].value("exists").value = false;
        }
        HostBridge emptyBridge = empty.bridge();
        discovery = call(empty, emptyBridge, "devices.layers.list", layerDiscovery());
        check(discovery.getAsJsonObject("window").get("total").getAsInt() == 0
                        && discovery.getAsJsonArray("layers").isEmpty(),
                "observed zero-count supported bank is distinguished from unknown and unsupported");

        Fake unknown = new Fake(); unknown.layerBank.value("scrollPosition").publishInitial = false;
        HostBridge unknownBridge = unknown.bridge();
        pending = unknownBridge.handle("devices.layers.list", layerDiscovery(), deadline()); unknown.next(); expectError(pending, "not_ready");
        unknown.layerBank.value("scrollPosition").set(0);
        check(call(unknown, unknownBridge, "devices.layers.list", layerDiscovery()).getAsJsonObject("window").get("readiness").getAsString().equals("ready"),
                "actual first offset observation, not getter default, opens discovery");

        Fake ambiguous = new Fake(); HostBridge ambiguousBridge = ambiguous.bridge();
        JsonObject address = layerTarget(ambiguous, ambiguousBridge, 0, 0);
        ambiguous.layerNodes[1].value("channelId").set(layerId(0));
        discovery = call(ambiguous, ambiguousBridge, "devices.layers.list", layerDiscovery());
        check(!discovery.getAsJsonArray("layers").get(0).getAsJsonObject().getAsJsonArray("devices")
                        .get(0).getAsJsonObject().has("discoveryToken"), "duplicate visible UUIDs cannot issue usable child tokens");
        pending = ambiguousBridge.handle("parameters.layer.list", address, deadline()); ambiguous.next(); expectError(pending, "stale_context");

        Fake missingIdentity = new Fake(); missingIdentity.layerNodes[1].value("channelId").publishInitial = false;
        HostBridge missingBridge = missingIdentity.bridge();
        discovery = call(missingIdentity, missingBridge, "devices.layers.list", layerDiscovery());
        check(!discovery.getAsJsonArray("layers").get(0).getAsJsonObject().getAsJsonArray("devices")
                        .get(0).getAsJsonObject().has("discoveryToken"), "unobserved sibling UUID cannot prove unique layer correspondence");
    }

    private static void layerValidationPrecedesScheduling() {
        Fake f = new Fake(); HostBridge bridge = f.bridge();
        JsonObject valid = new JsonObject(); valid.add("parent", target()); valid.addProperty("layerId", layerId(0));
        valid.addProperty("childDeviceIndex", 0); valid.addProperty("expectedChildDeviceName", "Twin"); valid.addProperty("discoveryToken", "observed");
        for (String field : List.of("parent", "layerId", "childDeviceIndex", "expectedChildDeviceName", "discoveryToken")) {
            JsonObject bad = valid.deepCopy(); bad.add(field, JsonNull.INSTANCE);
            expectError(bridge.handle("parameters.layer.list", bad, deadline()), "invalid_params");
        }
        for (String field : List.of("path", "padId", "slot", "layer")) {
            JsonObject bad = valid.deepCopy(); bad.addProperty(field, 0);
            expectError(bridge.handle("parameters.layer.list", bad, deadline()), "invalid_params");
        }
        JsonObject bad = valid.deepCopy(); bad.addProperty("childDeviceIndex", 2);
        expectError(bridge.handle("parameters.layer.list", bad, deadline()), "invalid_params");
        bad = valid.deepCopy(); bad.addProperty("childDeviceIndex", .5);
        expectError(bridge.handle("parameters.layer.list", bad, deadline()), "invalid_params");
        bad = valid.deepCopy(); bad.addProperty("layerId", "1-2-3-4-5");
        expectError(bridge.handle("parameters.layer.list", bad, deadline()), "invalid_params");
        bad = valid.deepCopy(); bad.getAsJsonObject("parent").addProperty("layerId", layerId(0));
        expectError(bridge.handle("parameters.layer.list", bad, deadline()), "invalid_params");
        bad = valid.deepCopy(); bad.addProperty("contextToken", "bound"); bad.addProperty("parameterId", "gain"); bad.addProperty("value", Double.NaN);
        expectError(bridge.handle("parameters.layer.set", bad, deadline()), "invalid_params");
        expectError(bridge.handle("parameters.list", valid, deadline()), "invalid_params");
        check(f.tasks.isEmpty(), "strict nested validation and narrow flat targeting precede host scheduling");
    }

    private static void layerMissingIdsAndUnreadableValuesStayExplicit() {
        Fake f = new Fake(); f.initialChildIds[0][0] = false; HostBridge bridge = f.bridge();
        JsonObject address = layerTarget(f, bridge, 0, 0);
        CompletableFuture<JsonObject> pending = bridge.handle("parameters.layer.list", address, deadline());
        f.next(); check(!pending.isDone(), "discovery readiness does not invent a direct ID observation");
        f.emitChildIds(1, 0, new String[]{"gain"}); f.next();
        check(!pending.isDone(), "another layer's same-ID callback cannot establish this source");
        f.emitChildIds(0, 0, new String[0]); f.next();
        check(pending.join().getAsJsonArray("parameters").isEmpty(), "actual empty ID callback is a ready empty direct surface");
        f.emitChildIds(0, 0, new String[]{"gain"}); f.emitChildValue(0, 0, Double.NaN);
        JsonObject listing = call(f, bridge, "parameters.layer.list", address);
        check(!listing.getAsJsonArray("parameters").get(0).getAsJsonObject().get("readable").getAsBoolean(),
                "nonfinite direct values remain unreadable");
        pending = bridge.handle("parameters.layer.set", layerWrite(listing, .8), deadline()); f.next(); expectError(pending, "not_ready");
        check(f.childWrites[0][0] == 0, "unreadable parameters never dispatch");
    }

    private static void layerRebindDuringWriteHasUnknownOutcome() {
        Fake f = new Fake(); HostBridge bridge = f.bridge();
        JsonObject listing = call(f, bridge, "parameters.layer.list", layerTarget(f, bridge, 0, 0));
        f.writeMode = "none";
        CompletableFuture<JsonObject> pending = bridge.handle("parameters.layer.set", layerWrite(listing, .8), deadline());
        f.next(); check(!pending.isDone() && f.childWrites[0][0] == 1, "write is dispatched before structural change");
        f.layerNodes[0].value("exists").set(false); f.layerNodes[0].value("exists").set(true);
        f.emitChildValue(0, 0, .8); f.next(); expectError(pending, "stale_context");
        try { pending.join(); } catch (CompletionException error) {
            check(((RpcException) error.getCause()).outcome.equals("unknown"), "replacement callback cannot acknowledge a pending write");
        }
    }

    private static double parameterValue(JsonObject listing) {
        return listing.getAsJsonArray("parameters").get(0).getAsJsonObject().get("normalizedValue").getAsDouble();
    }
    private static String layerId(int index) { return index == 0 ? "11111111-2222-4333-8444-555555555555" : "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"; }
    private static JsonObject layerDiscovery() { JsonObject p = new JsonObject(); p.add("parent", target()); return p; }
    private static JsonObject layerTarget(Fake f, HostBridge bridge, int layer, int child) {
        return layerTarget(call(f, bridge, "devices.layers.list", layerDiscovery()), layer, child);
    }
    private static JsonObject layerTarget(JsonObject discovery, int layer, int child) {
        JsonObject l = discovery.getAsJsonArray("layers").get(layer).getAsJsonObject();
        JsonObject c = l.getAsJsonArray("devices").get(child).getAsJsonObject();
        JsonObject p = new JsonObject(); p.add("parent", discovery.getAsJsonObject("parent").deepCopy());
        p.add("layerId", l.get("layerId")); p.add("childDeviceIndex", c.get("childDeviceIndex"));
        p.add("expectedChildDeviceName", c.get("name")); p.add("discoveryToken", c.get("discoveryToken")); return p;
    }
    private static JsonObject layerWrite(JsonObject listing, double value) {
        JsonObject p = listing.getAsJsonObject("target").deepCopy(); p.addProperty("parameterId", "gain"); p.addProperty("value", value); return p;
    }


    private static JsonObject target() {
        JsonObject value = new JsonObject(); value.addProperty("trackId", Fake.TRACK_ID);
        value.addProperty("deviceIndex", 0); value.addProperty("expectedDeviceName", "Twin"); return value;
    }
    private static JsonObject write(JsonObject listing, double value) {
        JsonObject target = listing.getAsJsonObject("target");
        JsonObject params = new JsonObject();
        params.add("trackId", target.get("trackId")); params.add("deviceIndex", target.get("deviceIndex"));
        params.add("expectedDeviceName", target.get("deviceName")); params.add("contextToken", target.get("contextToken"));
        params.addProperty("parameterId", "gain"); params.addProperty("value", value); return params;
    }
    private static long deadline() { return System.nanoTime() + TimeUnit.SECONDS.toNanos(2); }
    private static long shortDeadline() { return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(120); }
    private static JsonObject call(Fake fake, HostBridge bridge, String method, JsonObject params) {
        CompletableFuture<JsonObject> result = bridge.handle(method, params, deadline()); fake.next(); return result.join();
    }
    private static void check(boolean condition, String message) {
        assertions++; if (!condition) throw new AssertionError(message);
    }
    private static void expectError(CompletableFuture<JsonObject> future, String code) {
        check(future.isDone(), "error must complete future: " + code);
        try { future.join(); throw new AssertionError("Expected " + code); }
        catch (CompletionException error) {
            check(error.getCause() instanceof RpcException && ((RpcException) error.getCause()).code.equals(code),
                    "expected error " + code + ", got " + error.getCause());
        }
    }

    private static final class Fake {
        static final String TRACK_ID = "9baa5190-b120-47f9-8e06-0fda476123ca";
        final Deque<Runnable> tasks = new ArrayDeque<>();
        final Node trackBank = new Node();
        Node hostNode;
        final Node[] trackNodes = new Node[16], deviceNodes = new Node[16];
        final Track[] trackObjects = new Track[16];
        final Device[] deviceObjects = new Device[16];
        final StringArrayValueChangedCallback[] idObservers = new StringArrayValueChangedCallback[16];
        final DirectParameterNormalizedValueChangedCallback[] valueObservers = new DirectParameterNormalizedValueChangedCallback[16];
        final DirectParameterNameChangedCallback[] nameObservers = new DirectParameterNameChangedCallback[16];
        final boolean[] initialIds = new boolean[16];
        final double[] parameterValues = new double[16];
        final int[] deviceWrites = new int[16];
        int writes, idEmissions;
        final Node[] layerNodes = new Node[2], childBanks = new Node[2];
        final Node[][] childNodes = new Node[2][2];
        final StringArrayValueChangedCallback[][] childIds = new StringArrayValueChangedCallback[2][2];
        final DirectParameterNormalizedValueChangedCallback[][] childValues = new DirectParameterNormalizedValueChangedCallback[2][2];
        final boolean[][] initialChildIds = {{true, true}, {true, true}};
        final double[][] childParameterValues = {{.15, .35}, {.55, .75}};
        final int[][] childWrites = new int[2][2];
        Node layerBank;
        int childIdEmissions;
        int actionReads, actionLookups, actionInvocations;
        boolean runningHostTask, slowActionLookup, initializing;
        String writeMode = "match";

        Fake() {
            Arrays.fill(initialIds, true);
            parameterValues[0] = .25; parameterValues[1] = .6;
            for (int t = 0; t < 16; t++) {
                Device[] bankDevices = t == 0 ? deviceObjects : new Device[16];
                for (int d = 0; d < 16; d++) {
                    Node node = new Node(); node.put("exists", t == 0 && d < 2); node.put("name", "Twin");
                    node.put("position", d); node.put("deviceType", "audio-effect");
                    attachLayers(node, t == 0 && d == 0);
                    bankDevices[d] = node.proxy(Device.class);
                    if (t == 0) {
                        deviceNodes[d] = node;
                        final int index = d;
                        node.custom = (method, args) -> {
                            if (method.startsWith("addDirectParameter") && !initializing) {
                                throw new AssertionError("parameter observers must be registered during initialization");
                            }
                            if (method.equals("addDirectParameterIdObserver")) {
                                idObservers[index] = (StringArrayValueChangedCallback) args[0];
                                if (index < 2 && initialIds[index]) emitIds(index);
                            }
                            if (method.equals("addDirectParameterNameObserver")) {
                                nameObservers[index] = (DirectParameterNameChangedCallback) args[1];
                                if (index < 2) nameObservers[index].directParameterNameChanged("gain", "Gain " + index);
                            }
                            if (method.equals("addDirectParameterNormalizedValueObserver")) {
                                valueObservers[index] = (DirectParameterNormalizedValueChangedCallback) args[0];
                                if (index < 2) emitValue(index, parameterValues[index]);
                            }
                            if (method.equals("setDirectParameterValueNormalized")) {
                                if (!runningHostTask) throw new AssertionError("parameter write outside host callback");
                                writes++; deviceWrites[index]++;
                                if (writeMode.equals("match")) emitValue(index,
                                        ((Number) args[1]).doubleValue() / (((Number) args[2]).doubleValue() - 1));
                                if (writeMode.equals("mismatch")) emitValue(index, .4);
                            }
                            return null;
                        };
                    }
                }
                trackNodes[t] = new Node(); trackNodes[t].put("exists", t == 0); trackNodes[t].put("name", "Track");
                trackNodes[t].put("channelId", t == 0 ? TRACK_ID : ""); trackNodes[t].put("position", t);
                Node bank = new Node(); bank.put("scrollPosition", 0); bank.put("itemCount", t == 0 ? 2 : 0);
                bank.custom = (method, args) -> method.equals("getItemAt") ? bankDevices[(Integer) args[0]] : null;
                trackNodes[t].objects.put("createDeviceBank", bank.proxy(DeviceBank.class));
                trackObjects[t] = trackNodes[t].proxy(Track.class);
            }
            trackBank.put("scrollPosition", 0); trackBank.put("itemCount", 20);
            trackBank.custom = (method, args) -> method.equals("getItemAt") ? trackObjects[(Integer) args[0]] : null;
        }
        void attachLayers(Node root, boolean populated) {
            root.put("hasLayers", populated);
            Node bank = new Node(); bank.put("exists", populated); bank.put("itemCount", populated ? 2 : 0); bank.put("scrollPosition", 0);
            DeviceLayer[] layers = new DeviceLayer[2];
            if (populated) layerBank = bank;
            for (int l = 0; l < 2; l++) {
                final int layer = l;
                Node node = new Node(); node.put("exists", populated); node.put("channelId", populated ? layerId(l) : "");
                node.put("channelIndex", 10 + l); node.put("name", "Same Layer");
                Node children = new Node(); children.put("exists", populated);
                children.put("itemCount", populated ? 2 : 0); children.put("scrollPosition", 0);
                Device[] devices = new Device[2];
                if (populated) { layerNodes[l] = node; childBanks[l] = children; }
                DeviceLayer owner = node.proxy(DeviceLayer.class);
                layers[l] = owner;
                for (int c = 0; c < 2; c++) {
                    final int child = c;
                    Node leaf = new Node(); leaf.put("exists", populated); leaf.put("name", "Twin");
                    leaf.put("position", c); leaf.put("deviceType", "audio-effect");
                    Node chain = new Node();
                    chain.custom = (method, args) -> {
                        if (method.equals("createEqualsValue") && args[0] == owner)
                            throw new IllegalArgumentException("These proxies always refer to the same target");
                        return null;
                    };
                    leaf.objects.put("deviceChain", chain.proxy(DeviceChain.class));
                    if (populated) childNodes[l][c] = leaf;
                    leaf.custom = (method, args) -> {
                        if (method.startsWith("addDirectParameter") && !initializing)
                            throw new AssertionError("child direct observers must be init-created");
                        if (method.equals("createCursorRemoteControlsPage")) throw new AssertionError("No child remotes");
                        if (populated && method.equals("addDirectParameterIdObserver")) {
                            childIds[layer][child] = (StringArrayValueChangedCallback) args[0];
                            if (initialChildIds[layer][child]) emitChildIds(layer, child, new String[]{"gain"});
                        }
                        if (populated && method.equals("addDirectParameterNameObserver"))
                            ((DirectParameterNameChangedCallback) args[1]).directParameterNameChanged("gain", "Child gain");
                        if (populated && method.equals("addDirectParameterNormalizedValueObserver")) {
                            childValues[layer][child] = (DirectParameterNormalizedValueChangedCallback) args[0];
                            emitChildValue(layer, child, childParameterValues[layer][child]);
                        }
                        if (populated && method.equals("setDirectParameterValueNormalized")) {
                            if (!runningHostTask) throw new AssertionError("child write outside host callback");
                            childWrites[layer][child]++;
                            if (writeMode.equals("match")) emitChildValue(layer, child,
                                    ((Number) args[1]).doubleValue() / (((Number) args[2]).doubleValue() - 1));
                            if (writeMode.equals("mismatch")) emitChildValue(layer, child, .4);
                        }
                        return null;
                    };
                    devices[c] = leaf.proxy(Device.class);
                }
                children.custom = (method, args) -> method.equals("getItemAt") ? devices[(Integer) args[0]] : null;
                node.objects.put("createDeviceBank", children.proxy(DeviceBank.class));
            }
            bank.custom = (method, args) -> method.equals("getItemAt") ? layers[(Integer) args[0]] : null;
            root.objects.put("createLayerBank", bank.proxy(DeviceLayerBank.class));
        }
        void emitChildIds(int layer, int child, String[] ids) {
            if (childIds[layer][child] != null) { childIdEmissions++; childIds[layer][child].valueChanged(ids); }
        }
        void emitChildValue(int layer, int child, double value) {
            childParameterValues[layer][child] = value;
            if (childValues[layer][child] != null) childValues[layer][child].directParameterNormalizedValueChanged("gain", value);
        }
        HostBridge bridge() {
            Node app = new Node(); app.put("projectName", "Fixture"); app.put("hasActiveEngine", true);
            Node category = new Node(); category.objects.put("getId", "fixture.category"); category.objects.put("getName", "Category");
            Node first = new Node(); first.objects.put("getId", "fixture.action"); first.objects.put("getName", "Action 音色");
            first.objects.put("getCategory", category.proxy(ActionCategory.class));
            first.custom = (method, args) -> {
                if (method.equals("invoke")) {
                    if (!runningHostTask) throw new AssertionError("Action invoked outside host callback");
                    actionInvocations++;
                }
                return null;
            };
            Node second = new Node(); second.objects.put("getId", "fixture.other"); second.objects.put("getName", "Other");
            second.objects.put("getCategory", null);
            Action[] actions = {first.proxy(Action.class), second.proxy(Action.class)};
            app.custom = (method, args) -> {
                if (method.equals("getActions") || method.equals("getAction")) {
                    if (!runningHostTask) throw new AssertionError("Action lookup outside host callback");
                    if (method.equals("getActions")) { actionReads++; return actions; }
                    actionLookups++;
                    if (slowActionLookup) {
                        try { Thread.sleep(50); } catch (InterruptedException error) { throw new AssertionError(error); }
                    }
                    for (Action action : actions) if (action.getId().equals(args[0])) return action;
                }
                return null;
            };
            Node transport = new Node(); transport.put("isPlaying", false);
            Node host = new Node(); host.objects.put("createApplication", app.proxy(Application.class));
            hostNode = host;
            host.objects.put("createTransport", transport.proxy(Transport.class));
            host.objects.put("createTrackBank", trackBank.proxy(TrackBank.class));
            host.objects.put("getHostVersion", "test-only");
            host.custom = (method, args) -> {
                if (method.equals("scheduleTask")) tasks.add((Runnable) args[0]);
                return null;
            };
            initializing = true;
            try { return new HostBridge(host.proxy(ControllerHost.class)); }
            finally { initializing = false; }
        }
        void emitIds(int index) { emitIds(index, new String[]{"gain"}); }
        void emitIds(int index, String[] ids) {
            if (idObservers[index] != null) { idEmissions++; idObservers[index].valueChanged(ids); }
        }
        void emitValue(int index, double value) {
            parameterValues[index] = value;
            if (valueObservers[index] != null) valueObservers[index].directParameterNormalizedValueChanged("gain", value);
        }
        void next() {
            if (tasks.isEmpty()) throw new AssertionError("No scheduled host callback");
            runningHostTask = true;
            try { tasks.remove().run(); } finally { runningHostTask = false; }
        }
    }

    private interface Custom { Object invoke(String method, Object[] args); }
    private static final class Node implements InvocationHandler {
        final Map<String, Val> values = new HashMap<>();
        final Map<String, Object> objects = new HashMap<>();
        final Map<String, Integer> calls = new HashMap<>();
        Custom custom = (method, args) -> null;
        void put(String key, Object value) { values.put(key, new Val(value)); }
        Val value(String key) { return values.get(key); }
        <T> T proxy(Class<T> type) { return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, this)); }
        public Object invoke(Object proxy, Method method, Object[] args) {
            calls.merge(method.getName(), 1, Integer::sum);
            if (method.getName().equals("equals")) return proxy == args[0];
            if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
            if (values.containsKey(method.getName())) return values.get(method.getName()).proxy(method.getReturnType());
            if (objects.containsKey(method.getName())) return objects.get(method.getName());
            Object result = custom.invoke(method.getName(), args);
            if (result != null || method.getReturnType() == void.class) return result;
            if (method.getName().equals("getAction")) return null;
            if (method.getName().equals("getItemAt")) {
                String key = "item:" + args[0];
                if (proxy instanceof SendBank)
                    return objects.computeIfAbsent(key, ignored -> new Node().proxy(Send.class));
                if (proxy instanceof ClipLauncherSlotBank)
                    return objects.computeIfAbsent(key, ignored -> new Node().proxy(ClipLauncherSlot.class));
            }
            // Unrelated domain proxies stay unobserved; these parameter tests never invent their callbacks.
            if (method.getReturnType().isInterface()) {
                String key = method.getName() + Arrays.toString(args);
                return objects.computeIfAbsent(key, ignored -> new Node().proxy(method.getReturnType()));
            }
            if (method.getReturnType() == boolean.class) return false;
            if (method.getReturnType() == int.class) return 0;
            if (method.getReturnType() == long.class) return 0L;
            if (method.getReturnType() == double.class) return 0.0;
            return null;
        }
    }
    private static class Val implements InvocationHandler {
        Object value;
        boolean publishInitial = true;
        final List<Object> observers = new ArrayList<>();
        Val(Object value) { this.value = value; }
        Object current() { return value; }
        Object proxy(Class<?> type) { return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, this); }
        void set(Object newValue) { value = newValue; for (Object observer : observers) notifyObserver(observer); }
        void notifyObserver(Object observer) {
            if (observer instanceof IntegerValueChangedCallback) ((IntegerValueChangedCallback) observer).valueChanged((Integer) current());
            else if (observer instanceof BooleanValueChangedCallback) ((BooleanValueChangedCallback) observer).valueChanged((Boolean) current());
            else if (observer instanceof ObjectValueChangedCallback) ((ObjectValueChangedCallback) observer).valueChanged(current());
        }
        public Object invoke(Object proxy, Method method, Object[] args) {
            if (method.getName().equals("get")) return current();
            if (method.getName().equals("addValueObserver")) { observers.add(args[0]); if (publishInitial) notifyObserver(args[0]); }
            return null;
        }
    }
    /** Models the integer proxy's separate unassigned observers and equal-value suppression. */
    private static final class AssignmentInteger extends Val {
        final Map<IntegerValueChangedCallback, Integer> unassignedObservers = new IdentityHashMap<>();
        AssignmentInteger() { super(0); }
        @Override void set(Object newValue) {
            boolean changed = !Objects.equals(value, newValue); value = newValue;
            if (changed) {
                for (Object observer : observers) notifyObserver(observer);
                for (IntegerValueChangedCallback observer : unassignedObservers.keySet())
                    observer.valueChanged((Integer) value);
            }
        }
        void unassign() {
            boolean changed = !Objects.equals(value, 0); value = 0;
            if (changed) for (Object observer : observers) notifyObserver(observer);
            for (Map.Entry<IntegerValueChangedCallback, Integer> observer : unassignedObservers.entrySet())
                observer.getKey().valueChanged(observer.getValue());
        }
        @Override public Object invoke(Object proxy, Method method, Object[] args) {
            if (method.getName().equals("addValueObserver") && args.length == 2 && (Integer) args[1] != 0) {
                unassignedObservers.put((IntegerValueChangedCallback) args[0], (Integer) args[1]);
                return null;
            }
            return super.invoke(proxy, method, args);
        }
    }
}
