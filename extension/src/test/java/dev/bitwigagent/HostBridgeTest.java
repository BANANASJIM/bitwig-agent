package dev.bitwigagent;

import static dev.bitwigagent.RpcAssert.*;
import static org.junit.jupiter.api.Assertions.*;

import com.bitwig.extension.callback.*;
import com.bitwig.extension.controller.api.*;
import com.google.gson.*;
import dev.bitwigagent.core.MethodSpecs;
import dev.bitwigagent.core.MethodSpecsTest;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

/** Host-free contract tests. Proxies simulate callbacks, never a running Bitwig instance. */
public final class HostBridgeTest {

    @Test
    void sameIdDeviceRebindDoesNotRequireObserverReplay() {
        Fake host = new Fake();
        HostBridge bridge = host.bridge();
        JsonObject first = call(host, bridge, "parameters.list", target());
        assertTrue(
                first.getAsJsonArray("parameters")
                                .get(0)
                                .getAsJsonObject()
                                .get("normalizedValue")
                                .getAsDouble()
                        == .25,
                "device A has its own initial value");
        int idEmissions = host.idEmissions;
        JsonObject second = target();
        second.addProperty("deviceIndex", 1);
        CompletableFuture<JsonObject> pending = bridge.handle("parameters.list", second, host.deadline());
        host.next();
        assertTrue(pending.isDone(), "same-ID device rebind must use source-bound observations without ID replay");
        JsonObject current = pending.join();
        assertTrue(
                current.getAsJsonArray("parameters")
                                .get(0)
                                .getAsJsonObject()
                                .get("normalizedValue")
                                .getAsDouble()
                        == .6,
                "device B uses its own observed value, not device A's value");
        assertTrue(host.idEmissions == idEmissions, "binding must not depend on replaying unchanged parameter IDs");
        host.emitIds(0, new String[0]);
        host.emitValue(0, .11);
        host.nameObservers[0].directParameterNameChanged("gain", "Old device");
        JsonObject unchanged = call(host, bridge, "parameters.list", second);
        assertTrue(unchanged.getAsJsonArray("parameters").size() == 1, "A ID callback cannot change B's ID set");
        assertTrue(
                unchanged
                                .getAsJsonArray("parameters")
                                .get(0)
                                .getAsJsonObject()
                                .get("normalizedValue")
                                .getAsDouble()
                        == .6,
                "A value callback cannot populate B's cache");
        assertTrue(
                unchanged
                        .getAsJsonArray("parameters")
                        .get(0)
                        .getAsJsonObject()
                        .get("name")
                        .getAsString()
                        .equals("Gain 1"),
                "A name callback cannot populate B's cache");
        assertTrue(
                unchanged
                        .getAsJsonObject("target")
                        .get("contextToken")
                        .equals(current.getAsJsonObject("target").get("contextToken")),
                "A ID changes cannot invalidate B's token");
        JsonObject written = call(host, bridge, "parameters.set", write(current, .8));
        assertTrue(
                written.get("verification").getAsString().equals("observer_after_write"),
                "B write needs B's fresh callback");
        assertTrue(host.deviceWrites[0] == 0 && host.deviceWrites[1] == 1, "write dispatched exactly to bank device B");
        assertTrue(host.parameterValues[0] == .11, "writing B cannot change A");
        CompletableFuture<JsonObject> stale = bridge.handle("parameters.set", write(first, .75), host.deadline());
        host.next();
        assertRpcError("stale_context", stale);
    }

    @Test
    void renamedSourceRetainsContinuousObservations() {
        Fake host = new Fake();
        HostBridge bridge = host.bridge();
        JsonObject previous = call(host, bridge, "parameters.list", target());
        int emissions = host.idEmissions;
        host.deviceNodes[0].child("name").emit("Renamed");
        JsonObject renamed = target();
        renamed.addProperty("expectedDeviceName", "Renamed");
        CompletableFuture<JsonObject> pending = bridge.handle("parameters.list", renamed, host.deadline());
        host.next();
        assertTrue(pending.isDone(), "renaming a continuously observed source must not require ID replay");
        JsonObject current = pending.join();
        assertTrue(
                current.getAsJsonArray("parameters")
                                .get(0)
                                .getAsJsonObject()
                                .get("normalizedValue")
                                .getAsDouble()
                        == .25,
                "rename retains the same source's observed value");
        assertTrue(host.idEmissions == emissions, "metadata rename must not depend on parameter callback replay");
        JsonObject staleWrite = write(previous, .8);
        staleWrite.addProperty("expectedDeviceName", "Renamed");
        CompletableFuture<JsonObject> stale = bridge.handle("parameters.set", staleWrite, host.deadline());
        host.next();
        assertRpcError("stale_context", stale);
        JsonObject freshWrite = write(current, .8);
        assertTrue(
                call(host, bridge, "parameters.set", freshWrite)
                        .get("verification")
                        .getAsString()
                        .equals("observer_after_write"),
                "fresh renamed context can still verify a write");
        assertTrue(
                host.deviceWrites[0] == 1 && host.deviceWrites[1] == 0,
                "renamed source write stays on the exact device");
    }

    @Test
    void actionsStayOnHostThreadAndReportDispatchOnly() {
        Fake host = new Fake();
        HostBridge bridge = host.bridge();
        CompletableFuture<JsonObject> pending = bridge.handle("actions.list", new JsonObject(), host.deadline());
        assertTrue(host.actionReads == 0 && !pending.isDone(), "catalog must await scheduled host callback");
        host.next();
        JsonArray actions = pending.join().getAsJsonArray("actions");
        assertTrue(actions.size() == 2, "real catalog entries retained");
        JsonObject action = actions.get(0).getAsJsonObject();
        assertTrue(action.get("actionId").getAsString().equals("fixture.action"), "catalog ID retained");
        assertTrue(action.get("name").getAsString().equals("Action 音色"), "localized name retained");
        assertTrue(
                action.getAsJsonObject("category").get("id").getAsString().equals("fixture.category"),
                "category metadata retained");
        assertTrue(actions.get(1).getAsJsonObject().get("category").isJsonNull(), "absent category explicit");
        pending = bridge.handle("actions.invoke", actionParams("fixture.action"), host.deadline());
        assertTrue(
                host.actionLookups == 0 && host.actionInvocations == 0,
                "network entry cannot query or invoke host action");
        host.next();
        JsonObject result = pending.join();
        assertTrue(host.actionInvocations == 1, "action invoked once");
        assertTrue(result.get("status").getAsString().equals("dispatched"), "Action has no completion observation");
        assertTrue(result.get("completion").getAsString().equals("not_observed"), "completion scope explicit");
    }

    @Test
    void invalidAndExpiredActionsNeverInvoke() {
        Fake host = new Fake();
        HostBridge bridge = host.bridge();
        for (JsonObject params : Arrays.asList(new JsonObject(), actionParams(""), actionParams("x".repeat(4097)))) {
            assertRpcError("invalid_params", bridge.handle("actions.invoke", params, host.deadline()));
        }
        JsonObject extra = actionParams("fixture.action");
        extra.addProperty("extra", true);
        assertRpcError("invalid_params", bridge.handle("actions.invoke", extra, host.deadline()));
        assertRpcError("invalid_params", bridge.handle("actions.list", extra, host.deadline()));
        assertTrue(host.tasks.isEmpty(), "invalid Action arguments cannot schedule host work");
        CompletableFuture<JsonObject> pending =
                bridge.handle("actions.invoke", actionParams("missing"), host.deadline());
        host.next();
        assertRpcError("unknown_action", pending);
        pending = bridge.handle("actions.invoke", actionParams("fixture.action"), host.tasks.now() - 1);
        host.next();
        assertRpcError("deadline_exceeded", pending);
        host.slowActionLookup = true;
        int beforeSlowLookup = host.actionLookups;
        pending = bridge.handle("actions.invoke", actionParams("fixture.action"), host.tasks.after(30));
        host.next();
        assertRpcError("deadline_exceeded", pending);
        assertTrue(host.actionLookups == beforeSlowLookup + 1, "deadline test expires during lookup, before invoke");
        host.slowActionLookup = false;
        pending = bridge.handle("actions.invoke", actionParams("fixture.action"), host.deadline());
        pending.cancel(false);
        host.next();
        assertTrue(host.actionInvocations == 0, "unknown, expired, and abandoned Actions never invoke");
        pending = bridge.handle("actions.invoke", actionParams("fixture.action"), host.deadline());
        bridge.close();
        host.next();
        assertRpcError("unavailable", pending);
        assertTrue(host.actionInvocations == 0, "closed bridge never invokes Action");
    }

    @Test
    void projectModifiedIsObservedNeverGuessed() {
        Fake host = new Fake();
        HostBridge bridge = host.bridge();
        assertTrue(
                call(host, bridge, "status", new JsonObject())
                        .get("projectModified")
                        .isJsonNull(),
                "status: unobserved projectModified is null");
        assertTrue(
                call(host, bridge, "context.get", new JsonObject())
                        .get("projectModified")
                        .isJsonNull(),
                "context: unobserved projectModified is null");
        for (String method : List.of("status", "context.get")) {
            JsonObject instance = call(host, bridge, method, new JsonObject()).getAsJsonObject("instance");
            assertTrue(
                    instance.get("pid").getAsLong() == ProcessHandle.current().pid()
                            && instance.has("parentPid")
                                    == ProcessHandle.current().parent().isPresent()
                            && instance.has("display")
                            && instance.has("waylandDisplay"),
                    method + " reports which process answered");
        }
        host.modified.emit(true);
        assertTrue(
                call(host, bridge, "status", new JsonObject())
                        .get("projectModified")
                        .getAsBoolean(),
                "status reports unsaved");
        host.modified.emit(false);
        JsonObject context = call(host, bridge, "context.get", new JsonObject());
        assertTrue(
                context.has("projectModified")
                        && !context.get("projectModified").getAsBoolean(),
                "context reports saved");
        assertTrue(
                HostFixture.SPECS.methods().contains("project.save")
                        && HostFixture.SPECS.methods().contains("project.saveAs"),
                "project.save and project.saveAs registered");
        JsonObject saveAs = call(host, bridge, "capabilities", new JsonObject()).getAsJsonObject("projectSaveAs");
        assertTrue(
                saveAs.get("method").getAsString().equals("project.saveAs")
                        && !saveAs.get("available").getAsBoolean()
                        && saveAs.has("reason"),
                "capabilities reports the saveAs binding separately and fails closed off the gated host");
        JsonObject render = call(host, bridge, "capabilities", new JsonObject()).getAsJsonObject("render");
        assertTrue(
                render.getAsJsonArray("methods").size() == 3
                        && !render.get("available").getAsBoolean()
                        && render.has("reason"),
                "capabilities reports the render binding and fails closed off the gated host");
    }

    @Test
    void projectSaveIsObservedOnceAndNeverRetried() {
        Fake host = new Fake();
        HostBridge bridge = host.bridge();
        host.modified.emit(true);
        JsonObject saved = call(host, bridge, "project.save", new JsonObject());
        assertTrue(
                host.saveInvocations == 1
                        && saved.get("saved").getAsBoolean()
                        && saved.get("status").getAsString().equals("observed"),
                "observed isModified=false after dispatch reports saved");
        JsonObject again = call(host, bridge, "project.save", new JsonObject());
        assertTrue(
                host.saveInvocations == 1
                        && again.get("alreadySaved").getAsBoolean()
                        && again.get("saved").getAsBoolean(),
                "observed-saved project dispatches nothing");
        // Unobserved (null) state still dispatches, and only a fresh false callback counts.
        Fake unobserved = new Fake();
        HostBridge b2 = unobserved.bridge();
        unobserved.saveClears = false;
        CompletableFuture<JsonObject> pending =
                b2.handle("project.save", new JsonObject(), unobserved.tasks.after(150));
        int spins = 0;
        while (!pending.isDone() && spins++ < 100000000) unobserved.next();
        JsonObject result = pending.join();
        assertTrue(unobserved.saveInvocations == 1, "save dispatched exactly once, never re-invoked while waiting");
        assertTrue(
                result.get("status").getAsString().equals("dispatched")
                        && !result.get("saved").getAsBoolean()
                        && result.get("completion").getAsString().equals("not_observed")
                        && result.get("hint").getAsString().contains("Save As"),
                "no observation is not_observed with a dialog hint");
        // A stale false from before the dispatch is not evidence.
        Fake stale = new Fake();
        HostBridge b3 = stale.bridge();
        stale.modified.emit(true);
        stale.saveClears = false;
        pending = b3.handle("project.save", new JsonObject(), stale.tasks.after(150));
        stale.next();
        stale.modified.emit(false);
        spins = 0;
        while (!pending.isDone() && spins++ < 100000000) stale.next();
        assertTrue(pending.join().get("saved").getAsBoolean(), "later observer callback to false confirms save");
        Fake bad = new Fake();
        HostBridge b4 = bad.bridge();
        JsonObject extra = new JsonObject();
        extra.addProperty("x", 1);
        assertRpcError("invalid_params", b4.handle("project.save", extra, bad.deadline()));
    }

    private static final long SETTLE = HostServices.PROJECT_SETTLE_NANOS;

    @Test
    void engineActivationAndInactiveEngineGuards() {
        Fake f = new Fake();
        HostBridge bridge = f.bridge();
        JsonObject launch = new JsonObject();
        launch.addProperty("trackId", Fake.TRACK_ID);
        launch.addProperty("slotIndex", 0);
        JsonObject play = new JsonObject();
        play.addProperty("playing", true);
        JsonObject already = call(f, bridge, "project.activateEngine", new JsonObject());
        assertTrue(
                already.get("engineActive").getAsBoolean()
                        && already.get("alreadyActive").getAsBoolean()
                        && f.activateInvocations == 0,
                "an active engine dispatches nothing");
        assertTrue(
                call(f, bridge, "context.get", new JsonObject())
                        .get("engineActive")
                        .getAsBoolean(),
                "context.get reports engineActive");
        f.engine.data = false;
        assertTrue(
                !call(f, bridge, "status", new JsonObject()).get("engineActive").getAsBoolean(),
                "status reports an inactive engine");
        CompletableFuture<JsonObject> refused = bridge.handle("launcher.launch", launch, f.deadline());
        f.next();
        assertRpcError("engine_inactive", refused);
        {
            RpcException e = rejection(refused);
            assertTrue(e.outcome.equals("not_applied"), "inactive-engine refusal dispatched nothing");
        }
        assertTrue(bridge.preflight("transport.set", play), "playing=true on an inactive engine asks for a warning");
        JsonObject stop = new JsonObject();
        stop.addProperty("playing", false);
        assertTrue(!bridge.preflight("transport.set", stop), "stopping needs no warning");
        HostOperation op = new HostOperation(Long.MAX_VALUE);
        op.decorate = r -> HostBridge.addWarning(r, "w");
        JsonObject out = new JsonObject();
        out.addProperty("status", "dispatched");
        op.result.complete(out);
        assertTrue(op.result.join().getAsJsonArray("warnings").size() == 1, "a decorated result carries the warning");
        JsonObject activated = call(f, bridge, "project.activateEngine", new JsonObject());
        assertTrue(
                f.activateInvocations == 1
                        && activated.get("status").getAsString().equals("observed")
                        && activated.get("engineActive").getAsBoolean(),
                "activation is invoked once and confirmed by an observed true");
        assertTrue(!bridge.preflight("transport.set", play), "no warning once active");
        // The action runs but the engine never reports active: bounded, not retried, never claimed.
        Fake stuck = new Fake();
        HostBridge b2 = stuck.bridge();
        stuck.engine.data = false;
        stuck.activateWorks = false;
        CompletableFuture<JsonObject> pending =
                b2.handle("project.activateEngine", new JsonObject(), stuck.tasks.after(150));
        int spins = 0;
        while (!pending.isDone() && spins++ < 100000000) stuck.next();
        JsonObject result = pending.join();
        assertTrue(
                stuck.activateInvocations == 1
                        && result.get("status").getAsString().equals("dispatched")
                        && !result.get("engineActive").getAsBoolean()
                        && result.get("completion").getAsString().equals("not_observed"),
                "unconfirmed activation is reported, not retried");
    }

    @Test
    void writesWaitForSettledProject() {
        Fake f = new Fake();
        HostBridge bridge = f.bridge();
        JsonObject create = new JsonObject();
        create.addProperty("type", "audio");
        create.addProperty("position", -1);
        create.addProperty("name", "x");
        f.projectName.emit("Fixture2");
        JsonObject status = call(f, bridge, "status", new JsonObject());
        assertTrue(
                !status.get("projectReady").getAsBoolean()
                        && status.get("projectReadyReason").getAsString().equals("project_changed_settling"),
                "a freshly observed project name opens the settle window");
        assertTrue(
                !call(f, bridge, "context.get", new JsonObject())
                        .get("projectReady")
                        .getAsBoolean(),
                "context.get carries projectReady");
        CompletableFuture<JsonObject> refused = bridge.handle("tracks.create", create, f.deadline());
        f.next();
        assertRpcError("project_not_ready", refused);
        {
            RpcException e = rejection(refused);
            assertTrue(e.outcome.equals("not_applied"), "refused write is not_applied");
        }
        f.tasks.advance(SETTLE - 1);
        assertTrue(
                !call(f, bridge, "status", new JsonObject()).get("projectReady").getAsBoolean(),
                "still settling just before the window ends");
        f.projectName.emit("Fixture2");
        f.tasks.advance(1);
        JsonObject settled = call(f, bridge, "status", new JsonObject());
        assertTrue(
                settled.get("projectReady").getAsBoolean()
                        && settled.get("projectReadyReason").getAsString().equals("settled"),
                "a same-name callback does not restart the window ");
        f.projectName.emit("Recovered");
        status = call(f, bridge, "status", new JsonObject());
        assertTrue(
                !status.get("projectReady").getAsBoolean()
                        && status.get("projectChangedMsAgo").getAsLong() == 0,
                "a project switch reopens the window");
        refused = bridge.handle("grid.modules.insert", new JsonObject(), f.deadline());
        assertRpcError("invalid_params", refused);
        f.tasks.advance(SETTLE);
        assertTrue(
                call(f, bridge, "status", new JsonObject()).get("projectReady").getAsBoolean(),
                "ready again after the window");
    }

    @Test
    void admissionFollowsMethodSpecs() {
        Fake f = new Fake();
        HostBridge bridge = f.bridge();
        f.projectName.emit("Fixture2");
        f.engine.data = false;
        for (String method : HostFixture.SPECS.methods()) {
            boolean projectWrite = MethodSpecsTest.PROJECT_WRITES.contains(method);
            boolean engine = MethodSpecsTest.ENGINE_REQUIRED.contains(method);
            String refusal = null;
            try {
                bridge.preflight(method, new JsonObject());
            } catch (RpcException e) {
                refusal = e.code;
            }
            assertTrue(
                    Objects.equals(refusal, projectWrite ? "project_not_ready" : engine ? "engine_inactive" : null),
                    method + " admission while the project settles and the engine is inactive: " + refusal);
        }
        f.tasks.advance(SETTLE);
        for (String method : MethodSpecsTest.ENGINE_REQUIRED) {
            RpcException refused =
                    assertThrows(RpcException.class, () -> bridge.preflight(method, new JsonObject()), method);
            assertTrue(
                    refused.code.equals("engine_inactive")
                            && refused.getMessage().contains(method),
                    method + " names itself");
        }
        for (String method : MethodSpecsTest.PROJECT_WRITES) bridge.preflight(method, new JsonObject());
    }

    @Test
    void handlersMustMatchTheBundle() {
        JsonObject extra = MethodSpecsTest.bundle();
        extra.getAsJsonObject("methods").add("no.handler", MethodSpecsTest.method(true, true, "", 5000, 10000));
        JsonObject missing = MethodSpecsTest.bundle();
        missing.getAsJsonObject("methods").remove("status");
        for (JsonObject bundle : List.of(extra, missing)) {
            IllegalStateException mismatch = assertThrows(
                    IllegalStateException.class,
                    () -> new Fake().bridge(MethodSpecs.parse(new java.io.StringReader(bundle.toString()))));
            assertTrue(
                    mismatch.getMessage().contains(bundle == extra ? "no.handler" : "status"),
                    "the mismatch names the method: " + mismatch.getMessage());
        }
    }

    @Test
    void discardingActionsAreGuardedByUnsavedChanges() {
        Fake host = new Fake();
        HostBridge bridge = host.bridge();
        for (Boolean state : new Boolean[] {null, true}) {
            if (state != null) host.modified.emit(state);
            CompletableFuture<JsonObject> refused =
                    bridge.handle("actions.invoke", actionParams("Quit"), host.deadline());
            host.next();
            assertRpcError("unsaved_changes", refused);
        }
        assertTrue(host.quitInvocations == 0, "refused lifecycle action never invokes");
        CompletableFuture<JsonObject> closeRefused =
                bridge.handle("actions.invoke", actionParams("Close"), host.deadline());
        host.next();
        assertRpcError("unsaved_changes", closeRefused);
        JsonObject consent = actionParams("Quit");
        consent.addProperty("discardUnsavedChanges", true);
        assertTrue(
                call(host, bridge, "actions.invoke", consent)
                                .get("status")
                                .getAsString()
                                .equals("dispatched")
                        && host.quitInvocations == 1,
                "explicit discardUnsavedChanges dispatches");
        host.modified.emit(false);
        CompletableFuture<JsonObject> quitSaved =
                bridge.handle("actions.invoke", actionParams("Quit"), host.deadline());
        host.next();
        assertRpcError("unsaved_changes", quitSaved);
        assertTrue(host.quitInvocations == 1, "Quit always needs consent: other open tabs are not observable");
        call(host, bridge, "actions.invoke", actionParams("Close"));
        call(host, bridge, "actions.invoke", actionParams("New"));
        assertTrue(
                host.lifecycleInvocations == 2, "Close on an observed-saved tab and New (opens a tab) need no consent");
        host.modified.emit(true);
        call(host, bridge, "actions.invoke", actionParams("fixture.action"));
        assertTrue(host.actionInvocations > 0, "non-lifecycle actions are never guarded");
        JsonObject bad = actionParams("Quit");
        bad.addProperty("discardUnsavedChanges", "yes");
        assertRpcError("invalid_params", bridge.handle("actions.invoke", bad, host.deadline()));
    }

    @Test
    void actionsListFiltersAndReportsTotals() {
        Fake host = new Fake();
        HostBridge bridge = host.bridge();
        JsonObject all = call(host, bridge, "actions.list", new JsonObject());
        assertTrue(
                all.get("total").getAsInt() == 2 && all.get("complete").getAsBoolean(),
                "unfiltered total and complete");
        JsonObject p = new JsonObject();
        p.addProperty("query", "OTHER");
        JsonObject byId = call(host, bridge, "actions.list", p);
        assertTrue(
                byId.getAsJsonArray("actions").size() == 1 && byId.get("total").getAsInt() == 1,
                "query matches id case-insensitively");
        p = new JsonObject();
        p.addProperty("query", "音色");
        assertTrue(
                call(host, bridge, "actions.list", p).getAsJsonArray("actions").size() == 1, "query matches name");
        p = new JsonObject();
        p.addProperty("category", "CATEGORY");
        assertTrue(
                call(host, bridge, "actions.list", p).getAsJsonArray("actions").size() == 1,
                "category matches name; null category excluded");
        p = new JsonObject();
        p.addProperty("limit", 1);
        JsonObject limited = call(host, bridge, "actions.list", p);
        assertTrue(
                limited.getAsJsonArray("actions").size() == 1
                        && limited.get("total").getAsInt() == 2
                        && !limited.get("complete").getAsBoolean(),
                "limit truncates and reports incomplete total");
        p = new JsonObject();
        p.addProperty("limit", 0);
        assertRpcError("invalid_params", bridge.handle("actions.list", p, host.deadline()));
        p = new JsonObject();
        p.addProperty("query", "");
        assertRpcError("invalid_params", bridge.handle("actions.list", p, host.deadline()));
    }

    private static JsonObject actionParams(String id) {
        JsonObject params = new JsonObject();
        params.addProperty("actionId", id);
        return params;
    }

    @Test
    void invalidInputDoesNotSchedule() {
        Fake host = new Fake();
        HostBridge bridge = host.bridge();
        JsonObject params = new JsonObject();
        params.addProperty("extra", true);
        assertRpcError("invalid_params", bridge.handle("status", params, host.deadline()));
        assertTrue(host.tasks.isEmpty(), "invalid request must not schedule host work");
        params = target();
        params.addProperty("deviceIndex", 1.5);
        assertRpcError("invalid_params", bridge.handle("parameters.list", params, host.deadline()));
        params = target();
        params.addProperty("deviceIndex", 16);
        assertRpcError("invalid_params", bridge.handle("parameters.list", params, host.deadline()));
    }

    @Test
    void initialObserverDataAndWindow() {
        Fake host = new Fake();
        HostBridge bridge = host.bridge();
        JsonObject result = call(host, bridge, "parameters.list", target());
        assertTrue(
                result.getAsJsonArray("parameters")
                                .get(0)
                                .getAsJsonObject()
                                .get("normalizedValue")
                                .getAsDouble()
                        == .25,
                "initial data belongs to the requested bank proxy");
        assertTrue(host.tasks.isEmpty(), "cached parameter listing completes without extra scheduled polling");
        JsonObject tracks = call(host, bridge, "tracks.list", new JsonObject());
        assertTrue(
                tracks.getAsJsonObject("window").get("capacity").getAsInt() == HostServices.TRACKS,
                "bounded track window");
        assertTrue(
                !tracks.getAsJsonObject("window").get("complete").getAsBoolean(),
                "a track count beyond the window cannot be declared complete");
        JsonObject params = new JsonObject();
        params.addProperty("trackId", Fake.TRACK_ID);
        JsonObject devices = call(host, bridge, "devices.list", params);
        assertTrue(devices.getAsJsonArray("devices").size() == 2, "only existing devices listed");
        params.addProperty("trackId", "outside");
        CompletableFuture<JsonObject> future = bridge.handle("devices.list", params, host.deadline());
        host.next();
        assertRpcError("outside_window", future);
    }

    @Test
    void contextGetIsReadOnlyAndNeverGuesses() {
        Fake host = new Fake();
        HostBridge bridge = host.bridge();
        JsonObject out = call(host, bridge, "context.get", new JsonObject());
        for (String key : new String[] {
            "projectName",
            "playing",
            "tempoBpm",
            "arrangerRecordEnabled",
            "arrangerAutomationWriteEnabled",
            "clipLauncherAutomationWriteEnabled",
            "sceneCount",
            "tracks",
            "selectedTrack",
            "cursorClip"
        }) assertTrue(out.has(key), "context.get reports " + key + " (null when unobserved)");
        assertTrue(
                out.get("cursorClip").isJsonNull() && out.get("selectedTrack").isJsonNull(),
                "unobserved selection and cursor clip are null, not guessed");
        assertTrue(
                out.getAsJsonArray("tracks").size() >= 1
                        && out.getAsJsonArray("tracks")
                                .get(0)
                                .getAsJsonObject()
                                .get("trackId")
                                .getAsString()
                                .equals(Fake.TRACK_ID),
                "tracks carry the observed UUID");
        assertTrue(HostFixture.SPECS.methods().contains("context.get"), "method is registered with the bridge");
        JsonObject bad = new JsonObject();
        bad.addProperty("x", 1);
        assertRpcError("invalid_params", bridge.handle("context.get", bad, host.deadline()));
    }

    @Test
    void matchingWriteRequiresFreshCallback() {
        Fake host = new Fake();
        HostBridge bridge = host.bridge();
        JsonObject listing = call(host, bridge, "parameters.list", target());
        host.writeMode = "match";
        JsonObject result = call(host, bridge, "parameters.set", write(listing, .75));
        assertTrue(host.writes == 1, "one dispatch");
        assertTrue(result.get("status").getAsString().equals("observed"), "matching callback observed");
        assertTrue(result.get("verification").getAsString().equals("observer_after_write"), "fresh callback evidence");
        assertTrue(result.get("matchesRequested").getAsBoolean(), "readback within declared tolerance");
        result = call(host, bridge, "parameters.set", write(listing, .75));
        assertTrue(host.writes == 1, "already satisfied absolute set need not dispatch");
        assertTrue(result.get("verification").getAsString().equals("already_at_value"), "no-op explicitly identified");
    }

    @Test
    void staleValuesDoNotVerifyWrites() {
        Fake host = new Fake();
        HostBridge bridge = host.bridge();
        JsonObject listing = call(host, bridge, "parameters.list", target());
        host.writeMode = "none";
        CompletableFuture<JsonObject> result = bridge.handle("parameters.set", write(listing, .75), host.deadline());
        host.next();
        assertTrue(!result.isDone(), "cached value is not fresh readback");
        while (!result.isDone()) host.next();
        JsonObject response = result.join();
        assertTrue(response.get("status").getAsString().equals("dispatched"), "unobserved write remains dispatched");
        assertTrue(
                response.get("observedValue").isJsonNull(), "old pre-write value not reported as post-write evidence");
        assertTrue(response.get("verification").getAsString().equals("not_observed"), "honest unobserved state");
    }

    @Test
    void mismatchedValuesAreReported() {
        Fake host = new Fake();
        HostBridge bridge = host.bridge();
        JsonObject listing = call(host, bridge, "parameters.list", target());
        host.writeMode = "mismatch";
        CompletableFuture<JsonObject> result = bridge.handle("parameters.set", write(listing, .75), host.deadline());
        while (!result.isDone()) host.next();
        JsonObject response = result.join();
        assertTrue(response.get("status").getAsString().equals("dispatched"), "mismatch is not observed success");
        assertTrue(response.get("verification").getAsString().equals("readback_mismatch"), "mismatch explicit");
        assertTrue(response.get("observedValue").getAsDouble() == .4, "actual mismatch value retained");
        assertTrue(!response.get("matchesRequested").getAsBoolean(), "match false");
    }

    @Test
    void staleTokensAndTargetsDoNotWrite() {
        Fake host = new Fake();
        HostBridge bridge = host.bridge();
        JsonObject listing = call(host, bridge, "parameters.list", target());
        JsonObject params = write(listing, .75);
        params.addProperty("contextToken", "stale");
        CompletableFuture<JsonObject> result = bridge.handle("parameters.set", params, host.deadline());
        host.next();
        assertRpcError("stale_context", result);
        host.deviceNodes[0].child("name").emit("Changed");
        result = bridge.handle("parameters.set", write(listing, .75), host.deadline());
        host.next();
        assertRpcError("stale_target", result);
        assertTrue(host.writes == 0, "stale target never dispatched");
    }

    @Test
    void deadlineAndLateCallbacksDoNotWrite() {
        Fake host = new Fake();
        HostBridge bridge = host.bridge();
        JsonObject listing = call(host, bridge, "parameters.list", target());
        CompletableFuture<JsonObject> result =
                bridge.handle("parameters.set", write(listing, .75), host.tasks.now() - 1);
        host.next();
        assertRpcError("deadline_exceeded", result);
        assertTrue(host.writes == 0, "expired queued operation cannot mutate");
        result = bridge.handle("parameters.set", write(listing, .75), host.deadline());
        result.completeExceptionally(new IllegalStateException("caller abandoned operation"));
        host.next();
        assertTrue(host.writes == 0, "late callback skips completed request");
        result = bridge.handle("parameters.set", write(listing, .75), host.deadline());
        bridge.close();
        host.next();
        assertRpcError("unavailable", result);
        assertTrue(host.writes == 0, "closed bridge cannot write");
    }

    @Test
    void missingSourceIdsDoNotReuseAnotherDevicesObservations() {
        Fake host = new Fake();
        host.initialIds[1] = false;
        HostBridge bridge = host.bridge();
        JsonObject previous = call(host, bridge, "parameters.list", target());
        JsonObject second = target();
        second.addProperty("deviceIndex", 1);
        CompletableFuture<JsonObject> pending = bridge.handle("parameters.list", second, host.deadline());
        host.next();
        assertTrue(!pending.isDone(), "B cannot reuse A's IDs when B has never reported its own list");
        CompletableFuture<JsonObject> busy = bridge.handle("parameters.set", write(previous, .75), host.deadline());
        host.next();
        host.next();
        assertRpcError("busy", busy);
        host.emitIds(0);
        host.emitValue(0, .9);
        host.next();
        assertTrue(!pending.isDone(), "A callbacks cannot make B ready");
        host.emitIds(1);
        host.emitValue(1, .6);
        host.next();
        JsonObject current = pending.join();
        assertTrue(
                current.getAsJsonArray("parameters")
                                .get(0)
                                .getAsJsonObject()
                                .get("normalizedValue")
                                .getAsDouble()
                        == .6,
                "B becomes ready only from B's continuously observed stream");
        assertTrue(
                !current.getAsJsonObject("target")
                        .get("contextToken")
                        .getAsString()
                        .equals(previous.getAsJsonObject("target")
                                .get("contextToken")
                                .getAsString()),
                "rebind changes token");
        CompletableFuture<JsonObject> stale = bridge.handle("parameters.set", write(previous, .75), host.deadline());
        host.next();
        assertRpcError("stale_context", stale);
        assertTrue(host.writes == 0, "old context cannot write after rebind");
    }

    @Test
    void otherSourceCallbacksCannotVerifyWrites() {
        Fake host = new Fake();
        HostBridge bridge = host.bridge();
        JsonObject second = target();
        second.addProperty("deviceIndex", 1);
        JsonObject listing = call(host, bridge, "parameters.list", second);
        host.writeMode = "none";
        CompletableFuture<JsonObject> pending = bridge.handle("parameters.set", write(listing, .75), host.deadline());
        host.next();
        host.emitValue(0, .75);
        host.next();
        assertTrue(!pending.isDone(), "A's fresh matching value cannot verify a write to B");
        host.emitValue(1, .75);
        host.next();
        assertTrue(
                pending.join().get("verification").getAsString().equals("observer_after_write"),
                "only B's fresh callback verifies B's write");
        assertTrue(host.deviceWrites[0] == 0 && host.deviceWrites[1] == 1, "no cursor or wrong-device write path");
    }

    @Test
    void sourceIdentityAndIdChangesInvalidateTokens() {
        Fake host = new Fake();
        HostBridge bridge = host.bridge();
        JsonObject listing = call(host, bridge, "parameters.list", target());
        host.emitIds(0, new String[] {"gain", "tone"});
        JsonObject changed = call(host, bridge, "parameters.list", target());
        assertTrue(
                !changed.getAsJsonObject("target")
                        .get("contextToken")
                        .equals(listing.getAsJsonObject("target").get("contextToken")),
                "observed ID-set change invalidates the token");
        assertTrue(
                !changed.getAsJsonArray("parameters")
                        .get(1)
                        .getAsJsonObject()
                        .get("readable")
                        .getAsBoolean(),
                "new IDs cannot inherit another parameter's value");
        CompletableFuture<JsonObject> stale = bridge.handle("parameters.set", write(listing, .75), host.deadline());
        host.next();
        assertRpcError("stale_context", stale);
        host.deviceNodes[0].child("exists").emit(false);
        host.emitIds(0);
        host.emitValue(0, .9);
        host.deviceNodes[0].child("exists").emit(true);
        CompletableFuture<JsonObject> pending = bridge.handle("parameters.list", target(), host.deadline());
        host.next();
        assertTrue(!pending.isDone(), "observable proxy replacement clears old parameter data");
        host.emitIds(0);
        host.emitValue(0, .35);
        host.next();
        JsonObject current = pending.join();
        assertTrue(
                current.getAsJsonArray("parameters")
                                .get(0)
                                .getAsJsonObject()
                                .get("normalizedValue")
                                .getAsDouble()
                        == .35,
                "nonexistent-source callbacks cannot repopulate a replacement cache");
        assertTrue(
                !current.getAsJsonObject("target")
                        .get("contextToken")
                        .equals(changed.getAsJsonObject("target").get("contextToken")),
                "existence transition invalidates the old token");
        host.trackNodes[0].child("channelId").emit("replacement-track");
        host.trackNodes[0].child("channelId").emit(Fake.TRACK_ID);
        stale = bridge.handle("parameters.set", write(current, .75), host.deadline());
        host.next();
        assertRpcError("stale_context", stale);
        assertTrue(host.writes == 0, "observable track replacement cannot reuse a token");
    }

    @Test
    void statusDiagnosticsAreScheduledAndSourceScoped() {
        Fake host = new Fake();
        HostBridge bridge = host.bridge();
        CompletableFuture<JsonObject> pending = bridge.handle("status", new JsonObject(), host.deadline());
        assertTrue(!pending.isDone(), "status must await the scheduled host task");
        host.next();
        JsonObject diagnostics = pending.join().getAsJsonObject("parameterBindingDiagnostics");
        assertTrue(
                diagnostics.get("target").isJsonNull()
                        && diagnostics.get("idCount").isJsonNull(),
                "no binding must not claim a source cache");
        call(host, bridge, "parameters.list", target());
        diagnostics = call(host, bridge, "status", new JsonObject()).getAsJsonObject("parameterBindingDiagnostics");
        assertTrue(
                diagnostics.get("matches").getAsBoolean()
                        && diagnostics.get("idsObserved").getAsBoolean(),
                "diagnostics describe the bound bank source");
        long received = diagnostics.get("idCallbacksReceived").getAsLong();
        host.emitIds(1);
        diagnostics = call(host, bridge, "status", new JsonObject()).getAsJsonObject("parameterBindingDiagnostics");
        assertTrue(
                diagnostics.get("idCallbacksReceived").getAsLong() == received,
                "another source's callback does not change the bound-source counters");
        assertTrue(
                diagnostics.get("cacheGeneration").getAsLong() == 0,
                "binding alone must not clear continuous observations");
    }

    @Test
    void capabilitiesDescribeBoundedImplementedSurfaces() {
        Fake host = new Fake();
        HostBridge bridge = host.bridge();
        CompletableFuture<JsonObject> pending = bridge.handle("capabilities", new JsonObject(), host.deadline());
        assertTrue(!pending.isDone(), "capabilities must read host windows on the scheduled host thread");
        host.next();
        JsonObject result = pending.join();
        assertTrue(
                result.getAsJsonObject("windows")
                                .getAsJsonObject("tracks")
                                .get("total")
                                .getAsInt()
                        == HostServices.TRACKS + 4,
                "discovery reports the actual host track count, not the window capacity");
        assertTrue(
                !result.getAsJsonObject("windows")
                        .getAsJsonObject("tracks")
                        .get("complete")
                        .getAsBoolean(),
                "capabilities cannot promise a full project beyond the bounded bank");
        JsonObject modulators = result.getAsJsonObject("privateModulatorReads");
        assertTrue(
                modulators.get("writeAvailability").getAsString().equals("query_modulators.capabilities_edit")
                        && modulators.get("nativeVerification").getAsString().equals("not_established_by_binding"),
                "modulator write availability is delegated to capabilities and discovery is not native acceptance");
        Set<String> reads = new HashSet<>();
        for (JsonElement method : result.getAsJsonObject("domains").getAsJsonArray("modulators"))
            reads.add(method.getAsString());
        Set<String> registered = new HashSet<>(ModulatorModule.METHODS);
        registered.addAll(ModulatorEditModule.METHODS);
        assertTrue(
                reads.equals(registered),
                "discovery derives the modulator domain from registered read and edit methods");
    }

    @Test
    void strictValidationPrecedesAllDomainScheduling() {
        Fake host = new Fake();
        HostBridge bridge = host.bridge();
        for (String method : List.of("capabilities", "transport.get", "devices.catalog", "modulators.capabilities")) {
            JsonObject extra = new JsonObject();
            extra.addProperty("unexpected", true);
            assertRpcError("invalid_params", bridge.handle(method, extra, host.deadline()));
        }
        JsonObject fraction = target();
        fraction.add("deviceIndex", new JsonPrimitive(new java.math.BigDecimal("1.0000000000000000001")));
        assertRpcError("invalid_params", bridge.handle("parameters.list", fraction, host.deadline()));
        JsonObject nullName = target();
        nullName.add("expectedDeviceName", JsonNull.INSTANCE);
        assertRpcError("invalid_params", bridge.handle("parameters.list", nullName, host.deadline()));
        assertRpcError("invalid_params", bridge.handle("capabilities", null, host.deadline()));
        assertRpcError("unknown_method", bridge.handle("not.a.method", new JsonObject(), host.deadline()));
        JsonObject modulators = new JsonObject();
        modulators.add("parent", target());
        modulators.add("offset", JsonNull.INSTANCE);
        assertRpcError("invalid_params", bridge.handle("modulators.list", modulators, host.deadline()));
        modulators.remove("offset");
        modulators.addProperty("modulatorId", "1-1-1-1-1");
        modulators.addProperty("parameterId", "");
        assertRpcError("invalid_params", bridge.handle("modulators.parameters.get", modulators, host.deadline()));
        assertRpcError("invalid_params", bridge.handle("modulators.parameters.set", modulators, host.deadline()));
        assertRpcError("unknown_method", bridge.handle("modulators.parameters.reset", modulators, host.deadline()));
        assertTrue(host.tasks.isEmpty(), "strict validation rejects every invalid domain request before scheduling");
    }

    @Test
    void initialRootAndParentWindowOrderingKeepsUsableData() {
        List<String> failures = new ArrayList<>();
        Fake early = new Fake();
        early.deviceNodes[1].child("position").emit(0);
        HostBridge earlyBridge = early.bridge();
        early.deviceNodes[1].child("position").data = 1;
        early.emitIds(1);
        early.emitValue(1, .66);
        int emissions = early.idEmissions;
        early.deviceNodes[1].child("position").emit(1);
        JsonObject root = target();
        root.addProperty("deviceIndex", 1);
        CompletableFuture<JsonObject> listed = earlyBridge.handle("parameters.list", root, early.deadline());
        early.next();
        if (!listed.isDone()
                || listed.isCompletedExceptionally()
                || parameterValue(listed.join()) != .66
                || early.idEmissions != emissions)
            failures.add("late agreeing root position discarded current callback data");
        for (int initialCount : List.of(0, 1)) {
            Fake pending = new Fake();
            FakeHost bank = pending.trackNodes[0].child("createDeviceBank");
            bank.child("itemCount").emit(initialCount);
            bank.child("scrollPosition").emit(initialCount);
            pending.deviceNodes[0].child("position").emit(initialCount);
            HostBridge bridge = pending.bridge();
            JsonObject parent = target();
            parent.addProperty("deviceIndex", initialCount);
            JsonObject request = new JsonObject();
            request.add("parent", parent);
            CompletableFuture<JsonObject> unavailable =
                    bridge.handle("devices.layers.list", request, pending.deadline());
            pending.next();
            if (!unavailable.isCompletedExceptionally())
                failures.add("root count " + initialCount + " admitted an out-of-count parent");
            bank.child("itemCount").emit(initialCount + 1);
            pending.emitChildIds(0, 0, new String[] {"gain"});
            pending.emitChildValue(0, 0, .42);
            JsonObject discovery = call(pending, bridge, "devices.layers.list", request);
            JsonObject child = discovery
                    .getAsJsonArray("layers")
                    .get(0)
                    .getAsJsonObject()
                    .getAsJsonArray("devices")
                    .get(0)
                    .getAsJsonObject();
            if (!child.has("discoveryToken")) failures.add("initial count population retired parent " + initialCount);
            else if (parameterValue(call(pending, bridge, "parameters.layer.list", layerTarget(discovery, 0, 0)))
                    != .42) failures.add("initial count population lost current child observation " + initialCount);
        }
        assertTrue(
                failures.isEmpty(),
                "initial observation ordering must not invent a rebind or callback replay: " + failures);
    }

    @Test
    void initialTrackPositionDoesNotRetireNestedSources() {
        Fake f = new Fake();
        f.trackNodes[0].child("position").emit(-1);
        HostBridge bridge = f.bridge();
        int rootEmissions = f.idEmissions, childEmissions = f.childIdEmissions;
        List<String> failures = new ArrayList<>();
        CompletableFuture<JsonObject> initial = bridge.handle("devices.layers.list", layerDiscovery(), f.deadline());
        f.next();
        if (initial.isCompletedExceptionally()) assertRpcError("not_ready", initial);
        else failures.add("unassigned track position admitted nested discovery");
        f.trackNodes[0].child("position").emit(0);
        JsonObject discovery = call(f, bridge, "devices.layers.list", layerDiscovery());
        for (int l = 0; l < 2; l++)
            for (int c = 0; c < 2; c++) {
                JsonObject child = discovery
                        .getAsJsonArray("layers")
                        .get(l)
                        .getAsJsonObject()
                        .getAsJsonArray("devices")
                        .get(c)
                        .getAsJsonObject();
                if (!child.has("discoveryToken"))
                    failures.add("first assigned track position retired child " + l + "/" + c);
            }
        assertTrue(failures.isEmpty(), "initial track position must establish ancestry, not replace it: " + failures);
        assertTrue(
                parameterValue(call(f, bridge, "parameters.list", target())) == .25 && f.idEmissions == rootEmissions,
                "first assigned track position preserves the independently accepted root getter tuple");
        assertTrue(f.childIdEmissions == childEmissions, "metadata completion does not synthesize child ID callbacks");
        for (int l = 0; l < 2; l++)
            for (int c = 0; c < 2; c++) {
                CompletableFuture<JsonObject> pending =
                        bridge.handle("parameters.layer.list", layerTarget(discovery, l, c), f.deadline());
                f.next();
                assertTrue(
                        !pending.isDone(), "child observations rejected before track assignment are not resurrected");
                double value = .21 + l * .1 + c * .01;
                f.emitChildIds(l, c, new String[] {"gain"});
                f.emitChildValue(l, c, value);
                f.next();
                assertTrue(
                        parameterValue(pending.join()) == value,
                        "actual fresh child callback is admitted after first track assignment");
            }
    }

    @Test
    void unassignedLayerIndexDoesNotRetireInitialSources() {
        for (boolean notifyUnassigned : List.of(false, true)) {
            Fake f = new Fake();
            f.trackNodes[0].child("position").emit(-1);
            FakeHost[] indices = {FakeHost.assignmentInteger("channelIndex"), FakeHost.assignmentInteger("channelIndex")
            };
            for (int l = 0; l < 2; l++) {
                f.layerNodes[l].attach("channelIndex", indices[l]);
                f.layerNodes[l].child("exists").emit(false);
                f.layerNodes[l].child("channelId").emit("");
            }
            HostBridge bridge = f.bridge();
            int emissions = f.childIdEmissions;
            for (int l = 0; l < 2; l++) {
                if (notifyUnassigned) indices[l].unassign();
                f.layerNodes[l].child("channelId").emit(layerId(l));
                f.layerNodes[l].child("exists").emit(true);
            }
            f.trackNodes[0].child("position").emit(8);
            for (int l = 0; l < 2; l++) indices[l].emit(9 + l);
            JsonObject discovery = call(f, bridge, "devices.layers.list", layerDiscovery());
            List<String> retired = new ArrayList<>();
            for (int l = 0; l < 2; l++)
                for (int c = 0; c < 2; c++) {
                    JsonObject child = discovery
                            .getAsJsonArray("layers")
                            .get(l)
                            .getAsJsonObject()
                            .getAsJsonArray("devices")
                            .get(c)
                            .getAsJsonObject();
                    if (!child.has("discoveryToken")) retired.add(l + "/" + c);
                }
            assertTrue(
                    retired.isEmpty(), "first assigned channel indices must not retire initial children: " + retired);
            assertTrue(f.childIdEmissions == emissions, "index assignment cannot replay rejected initial ID callbacks");
            for (int l = 0; l < 2; l++)
                for (int c = 0; c < 2; c++) {
                    CompletableFuture<JsonObject> pending =
                            bridge.handle("parameters.layer.list", layerTarget(discovery, l, c), f.deadline());
                    f.next();
                    assertTrue(!pending.isDone(), "metadata completion cannot resurrect rejected child observations");
                    double expected = .21 + l * .1 + c * .01;
                    f.emitChildIds(l, c, new String[] {"gain"});
                    f.emitChildValue(l, c, expected);
                    f.next();
                    assertTrue(
                            parameterValue(pending.join()) == expected,
                            "actual post-assignment callback supplies the correct child value");
                }
        }
    }

    @Test
    void assignedLayerIndexDiscontinuitiesStayClosed() {
        for (int original : List.of(0, 9))
            for (boolean incomplete : List.of(false, true)) {
                Fake f = new Fake();
                FakeHost index = FakeHost.assignmentInteger("channelIndex");
                f.layerNodes[0].attach("channelIndex", index);
                if (incomplete) f.childNodes[0][0].child("position").notifyInitial = false;
                HostBridge bridge = f.bridge();
                // A real native -1 notification followed by 0 makes assigned zero observable;
                // assigning cached zero directly is suppressed by the native proxy.
                index.emit(-1);
                index.emit(original);
                index.emit(original + 1);
                index.emit(original);
                if (incomplete) f.childNodes[0][0].child("position").emit(0);
                f.emitChildIds(0, 0, new String[] {"gain"});
                f.emitChildValue(0, 0, .91);
                JsonObject discovery = call(f, bridge, "devices.layers.list", layerDiscovery());
                JsonObject child = discovery
                        .getAsJsonArray("layers")
                        .get(0)
                        .getAsJsonObject()
                        .getAsJsonArray("devices")
                        .get(0)
                        .getAsJsonObject();
                assertTrue(
                        !child.has("discoveryToken")
                                && child.get("reason").getAsString().contains("provenance"),
                        "observed assigned index ABA stays retired, including zero and incomplete child metadata");
            }
        Fake f = new Fake();
        FakeHost index = FakeHost.assignmentInteger("channelIndex");
        f.layerNodes[0].attach("channelIndex", index);
        HostBridge bridge = f.bridge();
        index.emit(0);
        JsonObject discovery = call(f, bridge, "devices.layers.list", layerDiscovery());
        JsonObject layer = discovery.getAsJsonArray("layers").get(0).getAsJsonObject();
        assertTrue(
                layer.get("channelIndex").isJsonNull()
                        && layer.get("readiness").getAsString().equals("not_ready"),
                "a suppressed assigned-zero callback must remain unobserved, not fabricated from a getter default");
    }

    @Test
    void rootEarlyDirectCallbacksKeepLegacyAdmission() {
        Fake f = new Fake();
        for (String field : List.of("exists", "position", "name", "deviceType"))
            f.deviceNodes[0].child(field).notifyInitial = false;
        HostBridge bridge = f.bridge();
        int rootEmissions = f.idEmissions, childEmissions = f.childIdEmissions;
        for (String field : List.of("exists", "position", "name", "deviceType")) {
            FakeHost value = f.deviceNodes[0].child(field);
            value.emit(value.data);
        }
        JsonObject listing = call(f, bridge, "parameters.list", target());
        assertTrue(
                parameterValue(listing) == .25 && f.idEmissions == rootEmissions,
                "root's actual initial direct callback is retained before agreeing metadata notifications, without replay");
        JsonObject address = layerTarget(f, bridge, 0, 0);
        CompletableFuture<JsonObject> pending = bridge.handle("parameters.layer.list", address, f.deadline());
        f.next();
        assertTrue(
                !pending.isDone() && f.childIdEmissions == childEmissions,
                "nested callbacks rejected before observed ancestry are not resurrected by later metadata");
        f.emitChildIds(0, 0, new String[] {"gain"});
        f.emitChildValue(0, 0, .18);
        f.next();
        assertTrue(
                parameterValue(pending.join()) == .18, "newly ready nested cache requires actual accepted callbacks");
    }

    @Test
    void layerBindingRoundTripsKeepContinuousSources() {
        Fake f = new Fake();
        HostBridge bridge = f.bridge();
        JsonObject flat = call(f, bridge, "parameters.list", target());
        JsonObject a = layerTarget(f, bridge, 0, 0), b = layerTarget(f, bridge, 1, 1);
        JsonObject first = call(f, bridge, "parameters.layer.list", a);
        int emissions = f.childIdEmissions;
        assertTrue(parameterValue(first) == .15, "layer A reads its own continuously observed value");
        JsonObject second = call(f, bridge, "parameters.layer.list", b);
        assertTrue(parameterValue(second) == .75, "same-name layer and child B resolve by observed UUID and index");
        JsonObject again = call(f, bridge, "parameters.layer.list", a);
        assertTrue(
                parameterValue(again) == .15 && f.childIdEmissions == emissions,
                "A to B to A needs no callback replay");
        assertTrue(
                !first.getAsJsonObject("target")
                        .get("contextToken")
                        .equals(again.getAsJsonObject("target").get("contextToken")),
                "returning to A issues a new active binding token");
        CompletableFuture<JsonObject> stale =
                bridge.handle("parameters.layer.set", layerWrite(first, .8), f.deadline());
        f.next();
        assertRpcError("stale_context", stale);
        stale = bridge.handle("parameters.set", write(flat, .8), f.deadline());
        f.next();
        assertRpcError("stale_context", stale);
        JsonObject restored = call(f, bridge, "parameters.list", target());
        assertTrue(parameterValue(restored) == .25, "flat to layer to flat keeps the root source cache");
        stale = bridge.handle("parameters.layer.set", layerWrite(again, .8), f.deadline());
        f.next();
        assertRpcError("stale_context", stale);
        JsonObject stable = call(f, bridge, "parameters.list", target());
        assertTrue(
                stable.getAsJsonObject("target")
                        .get("contextToken")
                        .equals(restored.getAsJsonObject("target").get("contextToken")),
                "repeated current binding preserves its token");
    }

    @Test
    void layerWritesRequireExactSourceAndFreshCallbacks() {
        Fake f = new Fake();
        HostBridge bridge = f.bridge();
        JsonObject listing = call(f, bridge, "parameters.layer.list", layerTarget(f, bridge, 0, 1));
        JsonObject noOp = call(f, bridge, "parameters.layer.set", layerWrite(listing, .35));
        assertTrue(
                noOp.get("verification").getAsString().equals("already_at_value") && f.childWrites[0][1] == 0,
                "nested no-op is distinct from post-dispatch observation");
        f.writeMode = "none";
        CompletableFuture<JsonObject> write =
                bridge.handle("parameters.layer.set", layerWrite(listing, .8), f.deadline());
        f.next();
        assertTrue(!write.isDone(), "nested setter dispatch cannot acknowledge a write");
        double applied = Math.round(.8 * (HostServices.RESOLUTION - 1)) / (double) (HostServices.RESOLUTION - 1);
        f.emitChildValue(1, 1, applied);
        f.emitValue(0, applied);
        f.next();
        assertTrue(!write.isDone(), "same-ID root and sibling callbacks cannot verify the child write");
        CompletableFuture<JsonObject> busy = bridge.handle("parameters.list", target(), f.deadline());
        f.next();
        f.next();
        assertRpcError("busy", busy);
        f.emitChildValue(0, 1, applied);
        f.next();
        assertTrue(
                write.join().get("verification").getAsString().equals("observer_after_write"),
                "exact child fresh callback verifies");
        assertTrue(
                f.childWrites[0][1] == 1
                        && f.childWrites[0][0] == 0
                        && f.childWrites[1][0] == 0
                        && f.childWrites[1][1] == 0
                        && f.writes == 0,
                "only addressed child setter dispatches, with no root or sibling mutation");
        assertTrue(
                f.childParameterValues[0][0] == .15 && f.childParameterValues[1][0] == .55,
                "untouched child values stay unchanged");
    }

    @Test
    void layerRenamesRetainDataButRetireTokens() {
        for (String renamed : List.of("parent", "layer", "child")) {
            Fake f = new Fake();
            HostBridge bridge = f.bridge();
            JsonObject oldAddress = layerTarget(f, bridge, 0, 0);
            JsonObject oldListing = call(f, bridge, "parameters.layer.list", oldAddress);
            int emissions = f.childIdEmissions;
            FakeHost node = renamed.equals("parent")
                    ? f.deviceNodes[0]
                    : renamed.equals("layer") ? f.layerNodes[0] : f.childNodes[0][0];
            node.child("name").emit("Renamed");
            JsonObject parent = target();
            if (renamed.equals("parent")) parent.addProperty("expectedDeviceName", "Renamed");
            JsonObject discovery = new JsonObject();
            discovery.add("parent", parent);
            JsonObject address = layerTarget(call(f, bridge, "devices.layers.list", discovery), 0, 0);
            JsonObject staleAddress = address.deepCopy();
            staleAddress.add("discoveryToken", oldAddress.get("discoveryToken"));
            CompletableFuture<JsonObject> stale = bridge.handle("parameters.layer.list", staleAddress, f.deadline());
            f.next();
            assertRpcError("stale_context", stale);
            JsonObject fresh = call(f, bridge, "parameters.layer.list", address);
            assertTrue(
                    parameterValue(fresh) == .15 && f.childIdEmissions == emissions,
                    renamed + " rename preserves continuous direct observations without replay");
            JsonObject staleWrite = layerWrite(fresh, .8);
            staleWrite.add("contextToken", oldListing.getAsJsonObject("target").get("contextToken"));
            stale = bridge.handle("parameters.layer.set", staleWrite, f.deadline());
            f.next();
            assertRpcError("stale_context", stale);
            assertTrue(
                    call(f, bridge, "parameters.layer.set", layerWrite(fresh, .8))
                            .get("verification")
                            .getAsString()
                            .equals("observer_after_write"),
                    renamed + " fresh rename context still writes its exact child");
        }
    }

    @Test
    void layerStructuralChangesFailClosedAcrossAba() {
        for (String changed :
                List.of("parent", "track", "layer", "layer_window", "child", "child_type", "child_window")) {
            Fake f = new Fake();
            HostBridge bridge = f.bridge();
            JsonObject address = layerTarget(f, bridge, 0, 0);
            call(f, bridge, "parameters.layer.list", address);
            FakeHost value;
            Object replacement;
            switch (changed) {
                case "parent":
                    value = f.deviceNodes[0].child("exists");
                    replacement = false;
                    break;
                case "track":
                    value = f.trackNodes[0].child("channelId");
                    replacement = layerId(1);
                    break;
                case "layer":
                    value = f.layerNodes[0].child("channelId");
                    replacement = layerId(1);
                    break;
                case "layer_window":
                    value = f.layerBank.child("scrollPosition");
                    replacement = 1;
                    break;
                case "child":
                    value = f.childNodes[0][0].child("exists");
                    replacement = false;
                    break;
                case "child_type":
                    value = f.childNodes[0][0].child("deviceType");
                    replacement = "instrument";
                    break;
                default:
                    value = f.childBanks[0].child("scrollPosition");
                    replacement = 1;
            }
            Object original = value.data;
            value.emit(replacement);
            f.emitChildIds(0, 0, new String[] {"gain"});
            f.emitChildValue(0, 0, .91);
            value.emit(original);
            f.emitChildIds(0, 0, new String[] {"gain"});
            f.emitChildValue(0, 0, .92);
            CompletableFuture<JsonObject> stale = bridge.handle("parameters.layer.list", address, f.deadline());
            f.next();
            assertRpcError("not_ready", stale);
            JsonObject discovery = call(f, bridge, "devices.layers.list", layerDiscovery());
            JsonObject child = discovery
                    .getAsJsonArray("layers")
                    .get(0)
                    .getAsJsonObject()
                    .getAsJsonArray("devices")
                    .get(0)
                    .getAsJsonObject();
            assertTrue(
                    child.get("readiness").getAsString().equals("not_ready")
                            && !child.has("discoveryToken")
                            && child.get("reason").getAsString().contains("provenance"),
                    changed + " ABA cannot use delayed same-proxy callbacks as a provenance boundary");
            if (changed.equals("track")) {
                f.emitIds(1);
                f.emitValue(1, .6);
            }
            JsonObject other = target();
            other.addProperty("deviceIndex", 1);
            assertTrue(
                    call(f, bridge, "parameters.list", other)
                                    .getAsJsonArray("parameters")
                                    .size()
                            == 1,
                    "root direct behavior remains separate; track rebind recovery uses actual root callbacks");
        }
        Fake early = new Fake();
        early.childNodes[0][0].child("position").notifyInitial = false;
        HostBridge earlyBridge = early.bridge();
        early.layerNodes[0].child("channelId").emit(layerId(1));
        early.layerNodes[0].child("channelId").emit(layerId(0));
        early.childNodes[0][0].child("position").emit(0);
        early.emitChildIds(0, 0, new String[] {"gain"});
        early.emitChildValue(0, 0, .9);
        JsonObject discovery = call(early, earlyBridge, "devices.layers.list", layerDiscovery());
        JsonObject child = discovery
                .getAsJsonArray("layers")
                .get(0)
                .getAsJsonObject()
                .getAsJsonArray("devices")
                .get(0)
                .getAsJsonObject();
        assertTrue(
                !child.has("discoveryToken")
                        && child.get("reason").getAsString().contains("provenance"),
                "known layer UUID replacement retires even a child whose initial metadata was incomplete");
        List<String> unsafe = new ArrayList<>();
        for (String event : List.of(
                "child_remove",
                "child_position",
                "child_type",
                "layer_support",
                "layer_bank_exists",
                "layer_bank_count",
                "layer_bank_offset",
                "child_bank_exists",
                "child_bank_count",
                "child_bank_offset",
                "root_remove",
                "root_position",
                "root_type",
                "track_remove",
                "track_uuid",
                "track_position",
                "track_window",
                "root_window",
                "root_count")) {
            Fake pending = new Fake();
            boolean ancestor = event.startsWith("root_") || event.startsWith("track_");
            FakeHost incomplete = ancestor ? pending.deviceNodes[0] : pending.childNodes[0][0];
            String missing = event.endsWith("_position") ? "deviceType" : "position";
            Object initial = incomplete.child(missing).data;
            incomplete.child(missing).notifyInitial = false;
            if (ancestor) incomplete.child("exists").emit(false);
            HostBridge pendingBridge = pending.bridge();
            if (ancestor) incomplete.child("exists").emit(true);
            FakeHost rootBank = pending.trackNodes[0].child("createDeviceBank");
            FakeHost changed;
            Object replacement;
            switch (event) {
                case "child_remove":
                    changed = incomplete.child("exists");
                    replacement = false;
                    break;
                case "child_position":
                    changed = incomplete.child("position");
                    replacement = 1;
                    break;
                case "child_type":
                    changed = incomplete.child("deviceType");
                    replacement = "instrument";
                    break;
                case "layer_support":
                    changed = pending.deviceNodes[0].child("hasLayers");
                    replacement = false;
                    break;
                case "layer_bank_exists":
                    changed = pending.layerBank.child("exists");
                    replacement = false;
                    break;
                case "layer_bank_count":
                    changed = pending.layerBank.child("itemCount");
                    replacement = 1;
                    break;
                case "layer_bank_offset":
                    changed = pending.layerBank.child("scrollPosition");
                    replacement = 1;
                    break;
                case "child_bank_exists":
                    changed = pending.childBanks[0].child("exists");
                    replacement = false;
                    break;
                case "child_bank_count":
                    changed = pending.childBanks[0].child("itemCount");
                    replacement = 1;
                    break;
                case "child_bank_offset":
                    changed = pending.childBanks[0].child("scrollPosition");
                    replacement = 1;
                    break;
                case "root_remove":
                    changed = incomplete.child("exists");
                    replacement = false;
                    break;
                case "root_position":
                    changed = incomplete.child("position");
                    replacement = 1;
                    break;
                case "root_type":
                    changed = incomplete.child("deviceType");
                    replacement = "instrument";
                    break;
                case "track_remove":
                    changed = pending.trackNodes[0].child("exists");
                    replacement = false;
                    break;
                case "track_uuid":
                    changed = pending.trackNodes[0].child("channelId");
                    replacement = layerId(1);
                    break;
                case "track_position":
                    changed = pending.trackNodes[0].child("position");
                    replacement = 1;
                    break;
                case "track_window":
                    changed = pending.trackBank.child("scrollPosition");
                    replacement = 1;
                    break;
                case "root_window":
                    changed = rootBank.child("scrollPosition");
                    replacement = 1;
                    break;
                default:
                    changed = rootBank.child("itemCount");
                    replacement = 1;
            }
            Object original = changed.data;
            changed.emit(replacement);
            changed.emit(original);
            incomplete.child(missing).emit(initial);
            pending.emitChildIds(0, 0, new String[] {"gain"});
            pending.emitChildValue(0, 0, .91);
            JsonObject after = call(pending, pendingBridge, "devices.layers.list", layerDiscovery());
            JsonObject candidate = after.getAsJsonArray("layers")
                    .get(0)
                    .getAsJsonObject()
                    .getAsJsonArray("devices")
                    .get(0)
                    .getAsJsonObject();
            if (candidate.has("discoveryToken")
                    || !candidate.get("readiness").getAsString().equals("not_ready")) unsafe.add(event);
        }
        assertTrue(
                unsafe.isEmpty(),
                "known discontinuities before complete metadata must reject delayed predecessor data: " + unsafe);
        Fake initial = new Fake();
        initial.deviceNodes[0].child("exists").emit(false);
        initial.deviceNodes[0].child("position").notifyInitial = false;
        HostBridge initialBridge = initial.bridge();
        initial.deviceNodes[0].child("exists").emit(true);
        initial.deviceNodes[0].child("position").emit(0);
        initial.emitChildIds(0, 0, new String[] {"gain"});
        initial.emitChildValue(0, 0, .44);
        assertTrue(
                parameterValue(call(
                                initial,
                                initialBridge,
                                "parameters.layer.list",
                                layerTarget(initial, initialBridge, 0, 0)))
                        == .44,
                "first presence and first metadata with actual callbacks are not mistaken for physical replacement");
    }

    @Test
    void layerDiscoveryReportsObservedWindowsAndReadiness() {
        Fake f = new Fake();
        f.deviceNodes[0].child("hasLayers").notifyInitial = false;
        HostBridge bridge = f.bridge();
        CompletableFuture<JsonObject> pending = bridge.handle("devices.layers.list", layerDiscovery(), f.deadline());
        f.next();
        assertRpcError("not_ready", pending);
        f.deviceNodes[0].child("hasLayers").emit(true);
        JsonObject discovery = call(f, bridge, "devices.layers.list", layerDiscovery());
        assertTrue(
                discovery.getAsJsonObject("window").get("capacity").getAsInt() == 2
                        && discovery.getAsJsonObject("window").get("complete").getAsBoolean(),
                "fixed observed layer window reports actual total and completeness");
        assertTrue(
                discovery
                                .getAsJsonArray("layers")
                                .get(0)
                                .getAsJsonObject()
                                .get("channelIndex")
                                .getAsInt()
                        == 10,
                "observed channelIndex is not fabricated from the bank-local slot");
        f.layerBank.child("itemCount").emit(3);
        discovery = call(f, bridge, "devices.layers.list", layerDiscovery());
        assertTrue(
                !discovery.getAsJsonObject("window").get("complete").getAsBoolean()
                        && discovery.getAsJsonObject("window").get("total").getAsInt() == 3,
                "larger actual layer counts do not pretend the bounded window is complete");
        f.deviceNodes[0].child("hasLayers").emit(false);
        pending = bridge.handle("devices.layers.list", layerDiscovery(), f.deadline());
        f.next();
        assertRpcError("unsupported_scope", pending);

        Fake empty = new Fake();
        empty.layerBank.child("itemCount").data = 0;
        for (int l = 0; l < 2; l++) {
            empty.layerNodes[l].child("exists").data = false;
            empty.childBanks[l].child("itemCount").data = 0;
            for (int c = 0; c < 2; c++) empty.childNodes[l][c].child("exists").data = false;
        }
        HostBridge emptyBridge = empty.bridge();
        discovery = call(empty, emptyBridge, "devices.layers.list", layerDiscovery());
        assertTrue(
                discovery.getAsJsonObject("window").get("total").getAsInt() == 0
                        && discovery.getAsJsonArray("layers").isEmpty(),
                "observed zero-count supported bank is distinguished from unknown and unsupported");

        Fake unknown = new Fake();
        unknown.layerBank.child("scrollPosition").notifyInitial = false;
        HostBridge unknownBridge = unknown.bridge();
        pending = unknownBridge.handle("devices.layers.list", layerDiscovery(), unknown.deadline());
        unknown.next();
        assertRpcError("not_ready", pending);
        unknown.layerBank.child("scrollPosition").emit(0);
        assertTrue(
                call(unknown, unknownBridge, "devices.layers.list", layerDiscovery())
                        .getAsJsonObject("window")
                        .get("readiness")
                        .getAsString()
                        .equals("ready"),
                "actual first offset observation, not getter default, opens discovery");

        Fake ambiguous = new Fake();
        HostBridge ambiguousBridge = ambiguous.bridge();
        JsonObject address = layerTarget(ambiguous, ambiguousBridge, 0, 0);
        ambiguous.layerNodes[1].child("channelId").emit(layerId(0));
        discovery = call(ambiguous, ambiguousBridge, "devices.layers.list", layerDiscovery());
        assertTrue(
                !discovery
                        .getAsJsonArray("layers")
                        .get(0)
                        .getAsJsonObject()
                        .getAsJsonArray("devices")
                        .get(0)
                        .getAsJsonObject()
                        .has("discoveryToken"),
                "duplicate visible UUIDs cannot issue usable child tokens");
        pending = ambiguousBridge.handle("parameters.layer.list", address, ambiguous.deadline());
        ambiguous.next();
        assertRpcError("stale_context", pending);

        Fake missingIdentity = new Fake();
        missingIdentity.layerNodes[1].child("channelId").notifyInitial = false;
        HostBridge missingBridge = missingIdentity.bridge();
        discovery = call(missingIdentity, missingBridge, "devices.layers.list", layerDiscovery());
        assertTrue(
                !discovery
                        .getAsJsonArray("layers")
                        .get(0)
                        .getAsJsonObject()
                        .getAsJsonArray("devices")
                        .get(0)
                        .getAsJsonObject()
                        .has("discoveryToken"),
                "unobserved sibling UUID cannot prove unique layer correspondence");
    }

    @Test
    void layerValidationPrecedesScheduling() {
        Fake f = new Fake();
        HostBridge bridge = f.bridge();
        JsonObject valid = new JsonObject();
        valid.add("parent", target());
        valid.addProperty("layerId", layerId(0));
        valid.addProperty("childDeviceIndex", 0);
        valid.addProperty("expectedChildDeviceName", "Twin");
        valid.addProperty("discoveryToken", "observed");
        for (String field :
                List.of("parent", "layerId", "childDeviceIndex", "expectedChildDeviceName", "discoveryToken")) {
            JsonObject bad = valid.deepCopy();
            bad.add(field, JsonNull.INSTANCE);
            assertRpcError("invalid_params", bridge.handle("parameters.layer.list", bad, f.deadline()));
        }
        for (String field : List.of("path", "padId", "slot", "layer")) {
            JsonObject bad = valid.deepCopy();
            bad.addProperty(field, 0);
            assertRpcError("invalid_params", bridge.handle("parameters.layer.list", bad, f.deadline()));
        }
        JsonObject bad = valid.deepCopy();
        bad.addProperty("childDeviceIndex", 2);
        assertRpcError("invalid_params", bridge.handle("parameters.layer.list", bad, f.deadline()));
        bad = valid.deepCopy();
        bad.addProperty("childDeviceIndex", .5);
        assertRpcError("invalid_params", bridge.handle("parameters.layer.list", bad, f.deadline()));
        bad = valid.deepCopy();
        bad.addProperty("layerId", "1-2-3-4-5");
        assertRpcError("invalid_params", bridge.handle("parameters.layer.list", bad, f.deadline()));
        bad = valid.deepCopy();
        bad.getAsJsonObject("parent").addProperty("layerId", layerId(0));
        assertRpcError("invalid_params", bridge.handle("parameters.layer.list", bad, f.deadline()));
        bad = valid.deepCopy();
        bad.addProperty("contextToken", "bound");
        bad.addProperty("parameterId", "gain");
        bad.addProperty("value", Double.NaN);
        assertRpcError("invalid_params", bridge.handle("parameters.layer.set", bad, f.deadline()));
        assertRpcError("invalid_params", bridge.handle("parameters.list", valid, f.deadline()));
        assertTrue(f.tasks.isEmpty(), "strict nested validation and narrow flat targeting precede host scheduling");
    }

    @Test
    void layerMissingIdsAndUnreadableValuesStayExplicit() {
        Fake f = new Fake();
        f.initialChildIds[0][0] = false;
        HostBridge bridge = f.bridge();
        JsonObject address = layerTarget(f, bridge, 0, 0);
        CompletableFuture<JsonObject> pending = bridge.handle("parameters.layer.list", address, f.deadline());
        f.next();
        assertTrue(!pending.isDone(), "discovery readiness does not invent a direct ID observation");
        f.emitChildIds(1, 0, new String[] {"gain"});
        f.next();
        assertTrue(!pending.isDone(), "another layer's same-ID callback cannot establish this source");
        f.emitChildIds(0, 0, new String[0]);
        f.next();
        assertTrue(
                pending.join().getAsJsonArray("parameters").isEmpty(),
                "actual empty ID callback is a ready empty direct surface");
        f.emitChildIds(0, 0, new String[] {"gain"});
        f.emitChildValue(0, 0, Double.NaN);
        JsonObject listing = call(f, bridge, "parameters.layer.list", address);
        assertTrue(
                !listing.getAsJsonArray("parameters")
                        .get(0)
                        .getAsJsonObject()
                        .get("readable")
                        .getAsBoolean(),
                "nonfinite direct values remain unreadable");
        pending = bridge.handle("parameters.layer.set", layerWrite(listing, .8), f.deadline());
        f.next();
        assertRpcError("not_ready", pending);
        assertTrue(f.childWrites[0][0] == 0, "unreadable parameters never dispatch");
    }

    @Test
    void layerRebindDuringWriteHasUnknownOutcome() {
        Fake f = new Fake();
        HostBridge bridge = f.bridge();
        JsonObject listing = call(f, bridge, "parameters.layer.list", layerTarget(f, bridge, 0, 0));
        f.writeMode = "none";
        CompletableFuture<JsonObject> pending =
                bridge.handle("parameters.layer.set", layerWrite(listing, .8), f.deadline());
        f.next();
        assertTrue(!pending.isDone() && f.childWrites[0][0] == 1, "write is dispatched before structural change");
        f.layerNodes[0].child("exists").emit(false);
        f.layerNodes[0].child("exists").emit(true);
        f.emitChildValue(0, 0, .8);
        f.next();
        assertRpcError("stale_context", pending);
        {
            RpcException error = rejection(pending);
            assertTrue(error.outcome.equals("unknown"), "replacement callback cannot acknowledge a pending write");
        }
    }

    private static double parameterValue(JsonObject listing) {
        return listing.getAsJsonArray("parameters")
                .get(0)
                .getAsJsonObject()
                .get("normalizedValue")
                .getAsDouble();
    }

    private static JsonObject layerInsert(int layer, int count, String position) {
        JsonObject p = new JsonObject();
        p.add("parent", target());
        p.addProperty("layerId", layerId(layer));
        p.addProperty("expectedChildCount", count);
        p.addProperty("kind", "bitwig");
        p.addProperty("id", "11111111-2222-4333-8444-555555555555");
        if (position != null) p.addProperty("position", position);
        return p;
    }

    @Test
    void layerInsertionObservesTheChildWindow() {
        Fake full = new Fake();
        HostBridge bridge = full.bridge();
        JsonObject beyond = call(full, bridge, "devices.layers.insert", layerInsert(0, 2, null));
        assertTrue(
                full.layerCalls.equals(List.of("0:end:insertBitwigDevice")),
                "layer end uses that layer's chain: " + full.layerCalls);
        assertTrue(
                beyond.get("status").getAsString().equals("observed")
                        && beyond.get("verification").getAsString().equals("observed_count_increment_beyond_window")
                        && beyond.get("inserted").isJsonNull()
                        && beyond.get("afterCount").getAsInt() == 3
                        && beyond.get("layerId").getAsString().equals(layerId(0)),
                "a full two-child window can only observe the count delta");

        Fake f = new Fake();
        bridge = f.bridge();
        f.setLayer(1, "Solo");
        JsonObject start = call(f, bridge, "devices.layers.insert", layerInsert(1, 1, "start"));
        assertTrue(
                f.layerCalls.equals(List.of("1:start:insertBitwigDevice"))
                        && f.layerNames.get(1).equals(List.of("Inserted", "Solo")),
                "layer start inserts before the first child: " + f.layerNames);
        assertTrue(
                start.get("status").getAsString().equals("observed")
                        && start.get("verification").getAsString().equals("observed_count_increment_shifted_suffix")
                        && start.getAsJsonObject("inserted").get("deviceIndex").getAsInt() == 0
                        && start.getAsJsonArray("before").size() == 1
                        && start.getAsJsonArray("after").size() == 2,
                "start identifies the new child and the shifted old one");

        Fake g = new Fake();
        bridge = g.bridge();
        g.setLayer(1, "Solo");
        JsonObject end = call(g, bridge, "devices.layers.insert", layerInsert(1, 1, "end"));
        assertTrue(
                end.get("verification").getAsString().equals("observed_count_increment_and_unchanged_prefix")
                        && end.getAsJsonObject("inserted").get("deviceIndex").getAsInt() == 1
                        && g.layerNames.get(1).equals(List.of("Solo", "Inserted")),
                "layer end appends after the observed child");

        Fake guards = new Fake();
        bridge = guards.bridge();
        CompletableFuture<JsonObject> wrongCount =
                bridge.handle("devices.layers.insert", layerInsert(0, 1, null), guards.deadline());
        guards.next();
        assertRpcError("stale_context", wrongCount);
        JsonObject unknown = layerInsert(0, 2, null);
        unknown.addProperty("layerId", "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
        CompletableFuture<JsonObject> missing = bridge.handle("devices.layers.insert", unknown, guards.deadline());
        guards.next();
        assertRpcError("stale_context", missing);
        JsonObject badParent = layerInsert(0, 2, null);
        badParent.getAsJsonObject("parent").addProperty("expectedDeviceName", "Nope");
        CompletableFuture<JsonObject> stale = bridge.handle("devices.layers.insert", badParent, guards.deadline());
        guards.next();
        assertRpcError("stale_target", stale);
        assertTrue(guards.layerCalls.isEmpty(), "rejected layer inserts never reach the host");
        for (JsonObject bad : List.of(layerInsert(0, 2, "before"), layerInsert(0, -1, null))) {
            CompletableFuture<JsonObject> rejected = bridge.handle("devices.layers.insert", bad, guards.deadline());
            assertRpcError("invalid_params", rejected);
        }
        JsonObject badId = layerInsert(0, 2, null);
        badId.addProperty("layerId", "not-a-uuid");
        assertRpcError("invalid_params", bridge.handle("devices.layers.insert", badId, guards.deadline()));

        Fake silent = new Fake();
        bridge = silent.bridge();
        silent.layerMode = "none";
        CompletableFuture<JsonObject> pending =
                bridge.handle("devices.layers.insert", layerInsert(0, 2, null), silent.tasks.after(20));
        silent.next();
        JsonObject unobserved = pending.join();
        assertTrue(
                unobserved.get("verification").getAsString().equals("insertion_not_observed")
                        && silent.layerCalls.size() == 1,
                "an unobserved layer insert is reported, never retried");
    }

    private static String layerId(int index) {
        return index == 0 ? "11111111-2222-4333-8444-555555555555" : "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee";
    }

    private static JsonObject layerDiscovery() {
        JsonObject p = new JsonObject();
        p.add("parent", target());
        return p;
    }

    private static JsonObject layerTarget(Fake f, HostBridge bridge, int layer, int child) {
        return layerTarget(call(f, bridge, "devices.layers.list", layerDiscovery()), layer, child);
    }

    private static JsonObject layerTarget(JsonObject discovery, int layer, int child) {
        JsonObject l = discovery.getAsJsonArray("layers").get(layer).getAsJsonObject();
        JsonObject c = l.getAsJsonArray("devices").get(child).getAsJsonObject();
        JsonObject p = new JsonObject();
        p.add("parent", discovery.getAsJsonObject("parent").deepCopy());
        p.add("layerId", l.get("layerId"));
        p.add("childDeviceIndex", c.get("childDeviceIndex"));
        p.add("expectedChildDeviceName", c.get("name"));
        p.add("discoveryToken", c.get("discoveryToken"));
        return p;
    }

    private static JsonObject layerWrite(JsonObject listing, double value) {
        JsonObject p = listing.getAsJsonObject("target").deepCopy();
        p.addProperty("parameterId", "gain");
        p.addProperty("value", value);
        return p;
    }

    private static JsonObject target() {
        JsonObject value = new JsonObject();
        value.addProperty("trackId", Fake.TRACK_ID);
        value.addProperty("deviceIndex", 0);
        value.addProperty("expectedDeviceName", "Twin");
        return value;
    }

    private static JsonObject write(JsonObject listing, double value) {
        JsonObject target = listing.getAsJsonObject("target");
        JsonObject params = new JsonObject();
        params.add("trackId", target.get("trackId"));
        params.add("deviceIndex", target.get("deviceIndex"));
        params.add("expectedDeviceName", target.get("deviceName"));
        params.add("contextToken", target.get("contextToken"));
        params.addProperty("parameterId", "gain");
        params.addProperty("value", value);
        return params;
    }

    private static JsonObject call(Fake fake, HostBridge bridge, String method, JsonObject params) {
        CompletableFuture<JsonObject> result = bridge.handle(method, params, fake.deadline());
        fake.next();
        return result.join();
    }

    private static final class Fake {
        static final String TRACK_ID = "9baa5190-b120-47f9-8e06-0fda476123ca";
        final HostFixture base = HostFixture.withoutServices();
        final ScheduledTasks tasks = base.tasks;
        final FakeHost trackBank = base.bank;
        final FakeHost[] trackNodes = new FakeHost[HostFixture.DETAILED_TRACKS], deviceNodes = new FakeHost[16];
        final Device[] deviceObjects = new Device[16];
        final StringArrayValueChangedCallback[] idObservers = new StringArrayValueChangedCallback[16];
        final DirectParameterNormalizedValueChangedCallback[] valueObservers =
                new DirectParameterNormalizedValueChangedCallback[16];
        final DirectParameterNameChangedCallback[] nameObservers = new DirectParameterNameChangedCallback[16];
        final boolean[] initialIds = new boolean[16];
        final double[] parameterValues = new double[16];
        final int[] deviceWrites = new int[16];
        int writes, idEmissions;
        FakeHost modified, engine, projectName;
        int activateInvocations;
        boolean activateWorks = true;
        int saveInvocations, quitInvocations, lifecycleInvocations;
        boolean saveClears = true;
        final FakeHost[] layerNodes = new FakeHost[2], childBanks = new FakeHost[2];
        final FakeHost[][] childNodes = new FakeHost[2][2];
        final StringArrayValueChangedCallback[][] childIds = new StringArrayValueChangedCallback[2][2];
        final DirectParameterNormalizedValueChangedCallback[][] childValues =
                new DirectParameterNormalizedValueChangedCallback[2][2];
        final boolean[][] initialChildIds = {{true, true}, {true, true}};
        final double[][] childParameterValues = {{.15, .35}, {.55, .75}};
        final int[][] childWrites = new int[2][2];
        FakeHost layerBank;
        final List<List<String>> layerNames =
                List.of(new ArrayList<>(List.of("Twin", "Twin")), new ArrayList<>(List.of("Twin", "Twin")));
        final List<String> layerCalls = new ArrayList<>();
        String layerMode = "one";
        int childIdEmissions;
        int actionReads, actionLookups, actionInvocations;
        boolean slowActionLookup, initializing;
        String writeMode = "match";

        Fake() {
            Arrays.fill(initialIds, true);
            parameterValues[0] = .25;
            parameterValues[1] = .6;
            for (int t = 0; t < HostFixture.DETAILED_TRACKS; t++) {
                Device[] bankDevices = t == 0 ? deviceObjects : new Device[16];
                for (int d = 0; d < 16; d++) {
                    FakeHost node = new FakeHost("node");
                    node.put("exists", t == 0 && d < 2);
                    node.put("name", "Twin");
                    node.put("position", d);
                    node.put("deviceType", "audio-effect");
                    node.put("isEnabled", true);
                    HostFixture.remotePage(node);
                    attachLayers(node, t == 0 && d == 0);
                    for (String observer : List.of(
                            "addDirectParameterIdObserver",
                            "addDirectParameterNameObserver",
                            "addDirectParameterNormalizedValueObserver")) node.on(observer, args -> null);
                    bankDevices[d] = node.proxy(Device.class);
                    if (t == 0) {
                        deviceNodes[d] = node;
                        final int index = d;
                        node.on("addDirectParameterIdObserver", args -> {
                            requireInitializing("parameter observers must be registered during initialization");
                            idObservers[index] = (StringArrayValueChangedCallback) args[0];
                            if (index < 2 && initialIds[index]) emitIds(index);
                            return null;
                        });
                        node.on("addDirectParameterNameObserver", args -> {
                            requireInitializing("parameter observers must be registered during initialization");
                            nameObservers[index] = (DirectParameterNameChangedCallback) args[1];
                            if (index < 2) nameObservers[index].directParameterNameChanged("gain", "Gain " + index);
                            return null;
                        });
                        node.on("addDirectParameterNormalizedValueObserver", args -> {
                            requireInitializing("parameter observers must be registered during initialization");
                            valueObservers[index] = (DirectParameterNormalizedValueChangedCallback) args[0];
                            if (index < 2) emitValue(index, parameterValues[index]);
                            return null;
                        });
                        node.on("setDirectParameterValueNormalized", args -> {
                            if (!base.onHost) throw new AssertionError("parameter write outside host callback");
                            writes++;
                            deviceWrites[index]++;
                            if (writeMode.equals("match"))
                                emitValue(
                                        index,
                                        ((Number) args[1]).doubleValue() / (((Number) args[2]).doubleValue() - 1));
                            if (writeMode.equals("mismatch")) emitValue(index, .4);
                            return null;
                        });
                    }
                }
                trackNodes[t] = base.tracks[t];
                trackNodes[t].put("exists", t == 0);
                trackNodes[t].put("name", "Track");
                trackNodes[t].put("channelId", t == 0 ? TRACK_ID : "");
                trackNodes[t].put("position", t);
                FakeHost bank = new FakeHost("bank");
                bank.put("scrollPosition", 0);
                bank.put("itemCount", t == 0 ? 2 : 0);
                bank.on("getItemAt", args -> bankDevices[(Integer) args[0]]);
                trackNodes[t].attach("createDeviceBank", bank);
            }
            trackBank.put("itemCount", HostServices.TRACKS + 4);
        }

        void attachLayers(FakeHost root, boolean populated) {
            root.put("hasLayers", populated);
            FakeHost bank = new FakeHost("bank");
            bank.put("exists", populated);
            bank.put("itemCount", populated ? 2 : 0);
            bank.put("scrollPosition", 0);
            DeviceLayer[] layers = new DeviceLayer[2];
            if (populated) layerBank = bank;
            for (int l = 0; l < 2; l++) {
                final int layer = l;
                FakeHost node = new FakeHost("node");
                node.put("exists", populated);
                node.put("channelId", populated ? layerId(l) : "");
                node.put("channelIndex", 10 + l);
                node.put("name", "Same Layer");
                if (populated) {
                    node.attach("startOfDeviceChainInsertionPoint", layerPoint(layer, "start"));
                    node.attach("endOfDeviceChainInsertionPoint", layerPoint(layer, "end"));
                }
                FakeHost children = new FakeHost("children");
                children.put("exists", populated);
                children.put("itemCount", populated ? 2 : 0);
                children.put("scrollPosition", 0);
                Device[] devices = new Device[2];
                if (populated) {
                    layerNodes[l] = node;
                    childBanks[l] = children;
                }
                DeviceLayer owner = node.proxy(DeviceLayer.class);
                layers[l] = owner;
                for (int c = 0; c < 2; c++) {
                    final int child = c;
                    FakeHost leaf = new FakeHost("leaf");
                    leaf.put("exists", populated);
                    leaf.put("name", "Twin");
                    leaf.put("position", c);
                    leaf.put("deviceType", "audio-effect");
                    FakeHost chain = new FakeHost("chain");
                    chain.on("createEqualsValue", args -> {
                        if (args[0] == owner)
                            throw new IllegalArgumentException("These proxies always refer to the same target");
                        FakeHost same = new FakeHost("equals");
                        same.data = false;
                        return same.proxy(BooleanValue.class);
                    });
                    leaf.attach("deviceChain", chain);
                    if (populated) childNodes[l][c] = leaf;
                    leaf.on("createCursorRemoteControlsPage", args -> {
                        throw new AssertionError("No child remotes");
                    });
                    leaf.on("addDirectParameterIdObserver", args -> {
                        requireInitializing("child direct observers must be init-created");
                        if (populated) {
                            childIds[layer][child] = (StringArrayValueChangedCallback) args[0];
                            if (initialChildIds[layer][child]) emitChildIds(layer, child, new String[] {"gain"});
                        }
                        return null;
                    });
                    leaf.on("addDirectParameterNameObserver", args -> {
                        requireInitializing("child direct observers must be init-created");
                        if (populated)
                            ((DirectParameterNameChangedCallback) args[1])
                                    .directParameterNameChanged("gain", "Child gain");
                        return null;
                    });
                    leaf.on("addDirectParameterNormalizedValueObserver", args -> {
                        requireInitializing("child direct observers must be init-created");
                        if (populated) {
                            childValues[layer][child] = (DirectParameterNormalizedValueChangedCallback) args[0];
                            emitChildValue(layer, child, childParameterValues[layer][child]);
                        }
                        return null;
                    });
                    leaf.on("setDirectParameterValueNormalized", args -> {
                        if (populated) {
                            if (!base.onHost) throw new AssertionError("child write outside host callback");
                            childWrites[layer][child]++;
                            if (writeMode.equals("match"))
                                emitChildValue(
                                        layer,
                                        child,
                                        ((Number) args[1]).doubleValue() / (((Number) args[2]).doubleValue() - 1));
                            if (writeMode.equals("mismatch")) emitChildValue(layer, child, .4);
                        }
                        return null;
                    });
                    devices[c] = leaf.proxy(Device.class);
                }
                children.on("getItemAt", args -> devices[(Integer) args[0]]);
                node.attach("createDeviceBank", children);
            }
            bank.on("getItemAt", args -> layers[(Integer) args[0]]);
            root.attach("createLayerBank", bank);
        }

        FakeHost layerPoint(int layer, String mode) {
            FakeHost node = new FakeHost("node");
            node.onMethods(InsertionPoint.class, method -> method.startsWith("insert"), (method, args) -> {
                if (!base.onHost) throw new AssertionError("layer insertion outside host callback");
                layerCalls.add(layer + ":" + mode + ":" + method);
                if (layerMode.equals("none")) return null;
                List<String> names = new ArrayList<>(layerNames.get(layer));
                names.add(mode.equals("start") ? 0 : names.size(), "Inserted");
                setLayer(layer, names.toArray(new String[0]));
                return null;
            });
            return node;
        }
        /** Make layer `layer` hold exactly these children, emitting observer traffic as the host would. */
        void setLayer(int layer, String... names) {
            for (int c = 0; c < 2; c++) {
                FakeHost leaf = childNodes[layer][c];
                if (c < names.length) {
                    if (!names[c].equals(leaf.child("name").data))
                        leaf.child("name").emit(names[c]);
                    if (!Boolean.TRUE.equals(leaf.child("exists").data))
                        leaf.child("exists").emit(true);
                } else if (!Boolean.FALSE.equals(leaf.child("exists").data))
                    leaf.child("exists").emit(false);
            }
            layerNames.get(layer).clear();
            layerNames.get(layer).addAll(Arrays.asList(names));
            childBanks[layer].child("itemCount").emit(names.length);
        }

        void emitChildIds(int layer, int child, String[] ids) {
            if (childIds[layer][child] != null) {
                childIdEmissions++;
                childIds[layer][child].valueChanged(ids);
            }
        }

        void emitChildValue(int layer, int child, double value) {
            childParameterValues[layer][child] = value;
            if (childValues[layer][child] != null)
                childValues[layer][child].directParameterNormalizedValueChanged("gain", value);
        }

        HostBridge bridge() {
            return bridge(HostFixture.SPECS);
        }

        HostBridge bridge(MethodSpecs specs) {
            FakeHost app = base.application;
            app.put("projectName", "Fixture");
            app.put("hasActiveEngine", true);
            FakeHost category = new FakeHost("category");
            category.returns("getId", "fixture.category");
            category.returns("getName", "Category");
            FakeHost first = new FakeHost("first");
            first.returns("getId", "fixture.action");
            first.returns("getName", "Action 音色");
            first.attach("getCategory", category);
            first.on("invoke", args -> {
                if (!base.onHost) throw new AssertionError("Action invoked outside host callback");
                actionInvocations++;
                return null;
            });
            FakeHost second = new FakeHost("second");
            second.returns("getId", "fixture.other");
            second.returns("getName", "Other");
            second.returns("getCategory", null);
            Action[] actions = {first.proxy(Action.class), second.proxy(Action.class)};
            app.on("getActions", args -> {
                if (!base.onHost) throw new AssertionError("Action lookup outside host callback");
                actionReads++;
                return actions;
            });
            app.on("getAction", args -> {
                if (!base.onHost) throw new AssertionError("Action lookup outside host callback");
                actionLookups++;
                if (slowActionLookup) tasks.advance(TimeUnit.MILLISECONDS.toNanos(50));
                for (Action action : actions) if (action.getId().equals(args[0])) return action;
                if ("Activate Engine For Project".equals(args[0])) {
                    FakeHost act = new FakeHost("act");
                    act.on("invoke", none -> {
                        activateInvocations++;
                        if (activateWorks) engine.emit(true);
                        return null;
                    });
                    return act.proxy(Action.class);
                }
                if (Set.of("Save", "Quit", "Close", "New", "Open", "new_from_template")
                        .contains(args[0])) {
                    FakeHost extra = new FakeHost("extra");
                    extra.on("invoke", none -> {
                        if ("Save".equals(args[0])) {
                            saveInvocations++;
                            if (saveClears) modified.emit(false);
                        } else if ("Quit".equals(args[0])) quitInvocations++;
                        else lifecycleInvocations++;
                        return null;
                    });
                    return extra.proxy(Action.class);
                }
                return null;
            });
            engine = app.child("hasActiveEngine");
            projectName = app.child("projectName");
            modified = base.project.child("isModified");
            modified.notifyInitial = false;
            initializing = true;
            try {
                HostBridge b = base.bridge(specs);
                tasks.advance(2 * HostServices.PROJECT_SETTLE_NANOS);
                return b;
            } finally {
                initializing = false;
            }
        }

        void requireInitializing(String message) {
            if (!initializing) throw new AssertionError(message);
        }

        void emitIds(int index) {
            emitIds(index, new String[] {"gain"});
        }

        void emitIds(int index, String[] ids) {
            if (idObservers[index] != null) {
                idEmissions++;
                idObservers[index].valueChanged(ids);
            }
        }

        void emitValue(int index, double value) {
            parameterValues[index] = value;
            if (valueObservers[index] != null)
                valueObservers[index].directParameterNormalizedValueChanged("gain", value);
        }

        void next() {
            if (tasks.isEmpty()) throw new AssertionError("No scheduled host callback");
            base.next();
        }

        long deadline() {
            return tasks.after(2000);
        }
    }
}
