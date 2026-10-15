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
    private static final class Val implements InvocationHandler {
        Object value;
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
            if (method.getName().equals("addValueObserver")) { observers.add(args[0]); notifyObserver(args[0]); }
            return null;
        }
    }
}
