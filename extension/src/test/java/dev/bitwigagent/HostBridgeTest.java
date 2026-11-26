package dev.bitwigagent;

import static dev.bitwigagent.RpcAssert.*;
import static org.junit.jupiter.api.Assertions.*;

import com.bitwig.extension.callback.*;
import com.bitwig.extension.controller.api.*;
import com.google.gson.*;
import dev.bitwigagent.core.MethodSpecs;
import dev.bitwigagent.core.MethodSpecsTest;
import dev.bitwigagent.core.Refs;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

/** Host-free contract tests. Proxies simulate callbacks, never a running Bitwig instance. */
public final class HostBridgeTest {

    @Test
    void rootDevicesKeepTheirOwnObservationsAcrossListings() {
        Fake host = new Fake();
        HostBridge bridge = host.bridge();
        JsonObject first = call(host, bridge, "parameters.list", list(root(0)));
        assertEquals(.25, parameterValue(first), "device A has its own initial value");
        int idEmissions = host.idEmissions;
        CompletableFuture<JsonObject> pending = bridge.handle("parameters.list", list(root(1)), host.deadline());
        host.next();
        assertTrue(pending.isDone(), "same-ID device rebind must use source-bound observations without ID replay");
        JsonObject current = pending.join();
        assertEquals(.6, parameterValue(current), "device B uses its own observed value, not device A's value");
        assertEquals(idEmissions, host.idEmissions, "binding must not depend on replaying unchanged parameter IDs");
        host.emitIds(0, new String[0]);
        host.emitValue(0, .11);
        host.nameObservers[0].directParameterNameChanged("gain", "Old device");
        JsonObject unchanged = call(host, bridge, "parameters.list", list(root(1)));
        assertEquals(1, unchanged.getAsJsonArray("parameters").size(), "A ID callback cannot change B's ID set");
        assertEquals(.6, parameterValue(unchanged), "A value callback cannot populate B's cache");
        assertEquals("Gain 1", parameterName(unchanged), "A name callback cannot populate B's cache");
        JsonObject written = call(host, bridge, "parameters.set", set(unchanged, .8));
        assertEquals("applied", written.get("outcome").getAsString(), "B write needs B's fresh callback");
        assertTrue(host.deviceWrites[0] == 0 && host.deviceWrites[1] == 1, "write dispatched exactly to bank device B");
        assertEquals(.11, host.parameterValues[0], "writing B cannot change A");
        RpcException vanished = reject(host, bridge, "target_not_found", "parameters.set", set(first, .75));
        assertEquals("parameters.list", vanished.next);
        assertEquals(1, host.writes, "A's emptied parameter list refuses its old ref");
    }

    @Test
    void renamedSourceRetainsContinuousObservations() {
        Fake host = new Fake();
        HostBridge bridge = host.bridge();
        JsonObject previous = call(host, bridge, "parameters.list", list(root(0)));
        int emissions = host.idEmissions;
        host.deviceNodes[0].child("name").emit("Renamed");
        JsonObject renamed = Refs.device(track(), 0, "Renamed");
        CompletableFuture<JsonObject> pending = bridge.handle("parameters.list", list(renamed), host.deadline());
        host.next();
        assertTrue(pending.isDone(), "renaming a continuously observed source must not require ID replay");
        JsonObject current = pending.join();
        assertEquals(.25, parameterValue(current), "rename retains the same source's observed value");
        assertEquals(emissions, host.idEmissions, "metadata rename must not depend on parameter callback replay");
        RpcException stale = reject(host, bridge, "target_changed", "parameters.set", set(previous, .8));
        assertEquals(renamed, stale.current);
        reject(host, bridge, "target_changed", "parameters.list", list(root(0)));
        assertEquals(
                "applied",
                call(host, bridge, "parameters.set", set(current, .8))
                        .get("outcome")
                        .getAsString(),
                "the re-read ref of the renamed device still verifies a write");
        assertTrue(
                host.deviceWrites[0] == 1 && host.deviceWrites[1] == 0,
                "renamed source write stays on the exact device");
    }

    @Test
    void listingAnotherDeviceDoesNotInvalidateAWrite() {
        Fake host = new Fake();
        HostBridge bridge = host.bridge();
        JsonObject a = call(host, bridge, "parameters.list", list(root(0)));
        JsonObject b = call(host, bridge, "parameters.list", list(root(1)));
        assertEquals("applied", outcome(call(host, bridge, "parameters.set", set(a, .7))));
        assertEquals("applied", outcome(call(host, bridge, "parameters.set", set(b, .2))));
        assertTrue(
                host.deviceWrites[0] == 1 && host.deviceWrites[1] == 1,
                "each write reaches exactly the device its ref names");
        assertEquals(2, host.writes, "no write reaches another root device");
    }

    @Test
    void aVanishedParameterIsNotFoundAndPointsToTheList() {
        Fake host = new Fake();
        HostBridge bridge = host.bridge();
        JsonObject listing = call(host, bridge, "parameters.list", list(root(0)));
        host.emitIds(0, new String[] {"tone"});
        RpcException gone = reject(host, bridge, "target_not_found", "parameters.set", set(listing, .75));
        assertEquals("parameters.list", gone.next);
        JsonObject unlisted = set(listing, .75);
        unlisted.getAsJsonObject("target").addProperty("id", "not-listed");
        reject(host, bridge, "target_not_found", "parameters.set", unlisted);
        assertEquals(0, host.writes, "nothing is written");
    }

    @Test
    void aDeviceRenamedBetweenReadAndWriteIsTargetChangedWithCurrent() {
        Fake host = new Fake();
        HostBridge bridge = host.bridge();
        JsonObject listing = call(host, bridge, "parameters.list", list(root(0)));
        host.deviceNodes[0].child("name").emit("Changed");
        RpcException changed = reject(host, bridge, "target_changed", "parameters.set", set(listing, .75));
        assertEquals(Refs.device(track(), 0, "Changed"), changed.current);
        assertEquals(0, host.writes, "a stale target never dispatches");
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
        assertTrue(result.get("outcome").getAsString().equals("sent"), "completion scope explicit");
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
        assertRpcError("unknown_name", pending);
        pending = bridge.handle("actions.invoke", actionParams("fixture.action"), host.tasks.now() - 1);
        host.next();
        assertRpcError("deadline", pending);
        host.slowActionLookup = true;
        int beforeSlowLookup = host.actionLookups;
        pending = bridge.handle("actions.invoke", actionParams("fixture.action"), host.tasks.after(30));
        host.next();
        assertRpcError("deadline", pending);
        assertTrue(host.actionLookups == beforeSlowLookup + 1, "deadline test expires during lookup, before invoke");
        host.slowActionLookup = false;
        pending = bridge.handle("actions.invoke", actionParams("fixture.action"), host.deadline());
        pending.cancel(false);
        host.next();
        assertTrue(host.actionInvocations == 0, "unknown, expired, and abandoned Actions never invoke");
        pending = bridge.handle("actions.invoke", actionParams("fixture.action"), host.deadline());
        bridge.close();
        host.next();
        assertRpcError("not_ready", pending);
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
        JsonObject full = call(host, bridge, "status", HostFixture.json("{\"detail\":\"full\"}"));
        JsonObject saveAs = full.getAsJsonObject("projectSaveAs");
        assertTrue(
                saveAs.get("method").getAsString().equals("project.saveAs")
                        && !saveAs.get("available").getAsBoolean()
                        && saveAs.has("reason"),
                "full status reports the saveAs binding separately and fails closed off the gated host");
        JsonObject render = full.getAsJsonObject("render");
        assertTrue(
                render.getAsJsonArray("methods").size() == 2
                        && !render.get("available").getAsBoolean()
                        && render.has("reason"),
                "full status reports the render binding and fails closed off the gated host");
    }

    @Test
    void projectSaveIsObservedOnceAndNeverRetried() {
        Fake host = new Fake();
        HostBridge bridge = host.bridge();
        host.modified.emit(true);
        JsonObject saved = call(host, bridge, "project.save", new JsonObject());
        assertTrue(
                host.saveInvocations == 1
                        && saved.get("outcome").getAsString().equals("applied")
                        && !saved.get("projectModified").getAsBoolean(),
                "observed isModified=false after dispatch reports saved");
        JsonObject again = call(host, bridge, "project.save", new JsonObject());
        assertTrue(
                host.saveInvocations == 1 && again.get("outcome").getAsString().equals("already_matched"),
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
                result.get("outcome").getAsString().equals("unknown")
                        && result.get("reason").getAsString().equals("not_observed")
                        && result.get("message").getAsString().contains("Save As"),
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
        assertTrue(
                pending.join().get("outcome").getAsString().equals("applied"),
                "later observer callback to false confirms save");
        Fake bad = new Fake();
        HostBridge b4 = bad.bridge();
        JsonObject extra = new JsonObject();
        extra.addProperty("x", 1);
        assertRpcError("invalid_params", b4.handle("project.save", extra, bad.deadline()));
    }

    private static final long SETTLE = ProjectState.SETTLE_NANOS;

    @Test
    void engineActivationAndInactiveEngineGuards() {
        Fake f = new Fake();
        HostBridge bridge = f.bridge();
        JsonObject launch = new JsonObject();
        launch.add("target", Refs.launcherClip(Refs.track(Fake.TRACK_ID, "Track"), Refs.scene(0, null), "Verse"));
        JsonObject play = new JsonObject();
        play.addProperty("playing", true);
        JsonObject already = call(f, bridge, "project.activateEngine", new JsonObject());
        assertTrue(
                already.get("engineActive").getAsBoolean()
                        && already.get("outcome").getAsString().equals("already_matched")
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
        CompletableFuture<JsonObject> refused = bridge.handle("clips.launch", launch, f.deadline());
        f.next();
        assertRpcError("engine_inactive", refused);
        {
            RpcException e = rejection(refused);
            assertTrue(!e.unknown, "inactive-engine refusal dispatched nothing");
        }
        JsonObject activated = call(f, bridge, "project.activateEngine", new JsonObject());
        assertTrue(
                f.activateInvocations == 1
                        && activated.get("outcome").getAsString().equals("applied")
                        && activated.get("engineActive").getAsBoolean(),
                "activation is invoked once and confirmed by an observed true");
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
                        && result.get("outcome").getAsString().equals("unknown")
                        && result.get("reason").getAsString().equals("not_observed")
                        && !result.get("engineActive").getAsBoolean(),
                "unconfirmed activation is reported, not retried");
    }

    @Test
    void fullStatusAddsTheImplementedSurfaceToTheBriefState() {
        Fake f = new Fake();
        HostBridge bridge = f.bridge();
        JsonObject brief = call(f, bridge, "status", new JsonObject());
        JsonObject full = call(f, bridge, "status", HostFixture.json("{\"detail\":\"full\"}"));
        for (String key : brief.keySet()) assertEquals(brief.get(key), full.get(key), key);
        for (String key :
                List.of("grid", "methods", "domains", "windows", "units", "projectSaveAs", "render", "modulators")) {
            assertTrue(full.has(key) && !brief.has(key), key + " only with detail full");
        }
        assertRpcError(
                "invalid_params", bridge.handle("status", HostFixture.json("{\"detail\":\"some\"}"), f.deadline()));
    }

    @Test
    void writesWaitForSettledProject() {
        Fake f = new Fake();
        HostBridge bridge = f.bridge();
        JsonObject create = new JsonObject();
        create.addProperty("type", "audio");
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
        CompletableFuture<JsonObject> refused = bridge.handle("tracks.insert", create, f.deadline());
        f.next();
        assertRpcError("not_ready", refused);
        {
            RpcException e = rejection(refused);
            assertTrue(!e.unknown, "refused write is not_applied");
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
            boolean projectWrite = HostFixture.SPECS.get(method).projectWrite;
            boolean engine = HostFixture.SPECS.get(method).engineRequired;
            String refusal = null;
            try {
                bridge.preflight(method);
            } catch (RpcException e) {
                refusal = e.reason.wire();
            }
            assertTrue(
                    Objects.equals(refusal, projectWrite ? "not_ready" : engine ? "engine_inactive" : null),
                    method + " admission while the project settles and the engine is inactive: " + refusal);
        }
        f.tasks.advance(SETTLE);
        for (String method : HostFixture.SPECS.methods()) {
            if (!HostFixture.SPECS.get(method).engineRequired) continue;
            RpcException refused = assertThrows(RpcException.class, () -> bridge.preflight(method), method);
            assertTrue(
                    refused.reason.wire().equals("engine_inactive")
                            && refused.getMessage().contains(method),
                    method + " names itself");
        }
        for (String method : HostFixture.SPECS.methods())
            if (!HostFixture.SPECS.get(method).engineRequired) bridge.preflight(method);
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
    void aBundleWithAnotherVocabularyFailsAtStartup() {
        JsonObject bundle = MethodSpecsTest.bundle();
        bundle.getAsJsonObject("reasons").remove("busy");
        bundle.getAsJsonObject("reasons").addProperty("idle", "pending");
        IllegalStateException mismatch = assertThrows(
                IllegalStateException.class,
                () -> new Fake().bridge(MethodSpecs.parse(new java.io.StringReader(bundle.toString()))));
        assertTrue(mismatch.getMessage().contains("busy"), "the startup check names the missing reason");
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
                                .get("outcome")
                                .getAsString()
                                .equals("sent")
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
        JsonObject parent = root(0);
        parent.addProperty("index", 1.5);
        assertRpcError("invalid_params", bridge.handle("parameters.list", list(parent), host.deadline()));
        parent = root(0);
        parent.addProperty("index", 16);
        assertRpcError("invalid_params", bridge.handle("parameters.list", list(parent), host.deadline()));
        assertTrue(host.tasks.isEmpty(), "invalid refs must not schedule host work");
    }

    @Test
    void initialObserverDataAndWindow() {
        Fake host = new Fake();
        HostBridge bridge = host.bridge();
        JsonObject result = call(host, bridge, "parameters.list", list(root(0)));
        assertEquals(.25, parameterValue(result), "initial data belongs to the requested bank proxy");
        assertEquals(
                "shown gain",
                result.getAsJsonArray("parameters")
                        .get(0)
                        .getAsJsonObject()
                        .getAsJsonObject("value")
                        .get("display")
                        .getAsString(),
                "the display is the host's text reported while the list was built");
        assertNull(host.deviceNodes[0].child("directDisplayObserver").data, "the display observer is released");
        assertTrue(host.tasks.isEmpty(), "cached parameter listing completes without extra scheduled polling");
        HostFixture.REF_SCHEMA.apply(parameterRef(result));
        JsonObject tracks = call(host, bridge, "tracks.list", new JsonObject());
        assertTrue(
                tracks.getAsJsonObject("window").get("capacity").getAsInt() == HostLimits.TRACKS,
                "bounded track window");
        assertTrue(
                !tracks.getAsJsonObject("window").get("complete").getAsBoolean(),
                "a track count beyond the window cannot be declared complete");
        JsonObject devices = call(host, bridge, "devices.list", list(track()));
        assertEquals(2, devices.getAsJsonArray("devices").size(), "only existing devices listed");
        for (JsonElement row : devices.getAsJsonArray("devices"))
            HostFixture.REF_SCHEMA.apply(row.getAsJsonObject().getAsJsonObject("ref"));
        reject(
                host,
                bridge,
                "outside_window",
                "devices.list",
                list(Refs.track("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee", "Track")));
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
                                .getAsJsonObject("ref")
                                .get("id")
                                .getAsString()
                                .equals(Fake.TRACK_ID),
                "tracks carry the observed UUID in their ref");
        assertTrue(HostFixture.SPECS.methods().contains("context.get"), "method is registered with the bridge");
        JsonObject bad = new JsonObject();
        bad.addProperty("x", 1);
        assertRpcError("invalid_params", bridge.handle("context.get", bad, host.deadline()));
    }

    @Test
    void matchingWriteRequiresFreshCallback() {
        Fake host = new Fake();
        HostBridge bridge = host.bridge();
        JsonObject listing = call(host, bridge, "parameters.list", list(root(0)));
        host.writeMode = "match";
        JsonObject result = call(host, bridge, "parameters.set", set(listing, .75));
        assertEquals(1, host.writes, "one dispatch");
        assertEquals("applied", outcome(result), "fresh callback evidence");
        assertEquals(parameterRef(listing), result.getAsJsonObject("ref"));
        assertEquals(
                .75,
                result.getAsJsonObject("fields")
                        .getAsJsonObject("value")
                        .get("requested")
                        .getAsDouble());
        result = call(host, bridge, "parameters.set", set(listing, .75));
        assertEquals(1, host.writes, "already satisfied absolute set need not dispatch");
        assertEquals("already_matched", outcome(result), "no-op explicitly identified");
    }

    @Test
    void staleValuesDoNotVerifyWrites() {
        Fake host = new Fake();
        HostBridge bridge = host.bridge();
        JsonObject listing = call(host, bridge, "parameters.list", list(root(0)));
        host.writeMode = "none";
        CompletableFuture<JsonObject> result = bridge.handle("parameters.set", set(listing, .75), host.deadline());
        host.next();
        assertTrue(!result.isDone(), "cached value is not fresh readback");
        while (!result.isDone()) host.next();
        JsonObject response = result.join();
        assertEquals("unknown", outcome(response), "unobserved write is unknown");
        assertFalse(
                response.getAsJsonObject("fields").getAsJsonObject("value").has("observed"),
                "old pre-write value not reported as post-write evidence");
        assertEquals("not_observed", response.get("reason").getAsString(), "honest unobserved state");
    }

    @Test
    void mismatchedValuesAreReported() {
        Fake host = new Fake();
        HostBridge bridge = host.bridge();
        JsonObject listing = call(host, bridge, "parameters.list", list(root(0)));
        host.writeMode = "mismatch";
        CompletableFuture<JsonObject> result = bridge.handle("parameters.set", set(listing, .75), host.deadline());
        while (!result.isDone()) host.next();
        JsonObject response = result.join();
        assertEquals("unknown", outcome(response), "mismatch is not observed success");
        assertEquals("readback_mismatch", response.get("reason").getAsString(), "mismatch explicit");
        assertEquals(
                .4,
                response.getAsJsonObject("fields")
                        .getAsJsonObject("value")
                        .getAsJsonObject("observed")
                        .get("value")
                        .getAsDouble(),
                "actual mismatch value retained");
    }

    @Test
    void expectedValuesGuardTheWrite() {
        Fake host = new Fake();
        HostBridge bridge = host.bridge();
        JsonObject listing = call(host, bridge, "parameters.list", list(root(0)));
        JsonObject guarded = set(listing, .75);
        guarded.add("expect", HostFixture.json("{\"value\":0.9}"));
        RpcException mismatch = reject(host, bridge, "target_changed", "parameters.set", guarded);
        assertEquals(.25, mismatch.observed.get("value").getAsDouble());
        assertEquals(0, host.writes, "a differing expectation never dispatches");
        guarded.add("expect", HostFixture.json("{\"value\":0.25}"));
        assertEquals("applied", outcome(call(host, bridge, "parameters.set", guarded)));
    }

    @Test
    void deadlineAndLateCallbacksDoNotWrite() {
        Fake host = new Fake();
        HostBridge bridge = host.bridge();
        JsonObject listing = call(host, bridge, "parameters.list", list(root(0)));
        CompletableFuture<JsonObject> result = bridge.handle("parameters.set", set(listing, .75), host.tasks.now() - 1);
        host.next();
        assertRpcError("deadline", result);
        assertEquals(0, host.writes, "expired queued operation cannot mutate");
        result = bridge.handle("parameters.set", set(listing, .75), host.deadline());
        result.completeExceptionally(new IllegalStateException("caller abandoned operation"));
        host.next();
        assertEquals(0, host.writes, "late callback skips completed request");
        result = bridge.handle("parameters.set", set(listing, .75), host.deadline());
        bridge.close();
        host.next();
        assertRpcError("not_ready", result);
        assertEquals(0, host.writes, "closed bridge cannot write");
    }

    @Test
    void missingSourceIdsDoNotReuseAnotherDevicesObservations() {
        Fake host = new Fake();
        host.initialIds[1] = false;
        HostBridge bridge = host.bridge();
        JsonObject previous = call(host, bridge, "parameters.list", list(root(0)));
        CompletableFuture<JsonObject> pending = bridge.handle("parameters.list", list(root(1)), host.deadline());
        host.next();
        assertTrue(!pending.isDone(), "B cannot reuse A's IDs when B has never reported its own list");
        assertEquals(
                "applied",
                outcome(call(host, bridge, "parameters.set", set(previous, .75))),
                "a write to A does not wait for B's pending list");
        host.emitIds(0);
        host.emitValue(0, .9);
        host.next();
        assertTrue(!pending.isDone(), "A callbacks cannot make B ready");
        host.emitIds(1);
        host.emitValue(1, .6);
        host.next();
        JsonObject current = pending.join();
        assertEquals(.6, parameterValue(current), "B becomes ready only from B's continuously observed stream");
        assertEquals(
                "applied",
                outcome(call(host, bridge, "parameters.set", set(previous, .5))),
                "A's ref stays valid after B was bound: no list-before-set");
        assertEquals(0, host.deviceWrites[1], "nothing was written to B");
    }

    @Test
    void otherSourceCallbacksCannotVerifyWrites() {
        Fake host = new Fake();
        HostBridge bridge = host.bridge();
        JsonObject listing = call(host, bridge, "parameters.list", list(root(1)));
        host.writeMode = "none";
        CompletableFuture<JsonObject> pending = bridge.handle("parameters.set", set(listing, .75), host.deadline());
        host.next();
        host.emitValue(0, .75);
        host.next();
        assertTrue(!pending.isDone(), "A's fresh matching value cannot verify a write to B");
        host.emitValue(1, .75);
        host.next();
        assertEquals("applied", outcome(pending.join()), "only B's fresh callback verifies B's write");
        assertTrue(host.deviceWrites[0] == 0 && host.deviceWrites[1] == 1, "no cursor or wrong-device write path");
    }

    @Test
    void parameterSetChangesUnderAWriteHaveUnknownOutcome() {
        Fake host = new Fake();
        HostBridge bridge = host.bridge();
        JsonObject listing = call(host, bridge, "parameters.list", list(root(0)));
        host.writeMode = "none";
        CompletableFuture<JsonObject> pending = bridge.handle("parameters.set", set(listing, .75), host.deadline());
        host.next();
        assertTrue(!pending.isDone() && host.writes == 1, "the write is dispatched before the change");
        host.emitIds(0, new String[] {"gain", "tone"});
        host.emitValue(0, .75);
        host.next();
        JsonObject result = pending.join();
        assertEquals("unknown", outcome(result));
        assertEquals("target_changed", result.get("reason").getAsString());
    }

    @Test
    void newIdsDoNotInheritValuesAndReplacementClearsParameterData() {
        Fake host = new Fake();
        HostBridge bridge = host.bridge();
        host.emitIds(0, new String[] {"gain", "tone"});
        JsonObject changed = call(host, bridge, "parameters.list", list(root(0)));
        assertTrue(
                changed.getAsJsonArray("parameters")
                        .get(1)
                        .getAsJsonObject()
                        .getAsJsonObject("value")
                        .get("value")
                        .isJsonNull(),
                "new IDs cannot inherit another parameter's value");
        reject(host, bridge, "not_ready", "parameters.set", set(changed, .75, 1));
        host.deviceNodes[0].child("exists").emit(false);
        host.emitIds(0);
        host.emitValue(0, .9);
        host.deviceNodes[0].child("exists").emit(true);
        CompletableFuture<JsonObject> pending = bridge.handle("parameters.list", list(root(0)), host.deadline());
        host.next();
        assertTrue(!pending.isDone(), "observable proxy replacement clears old parameter data");
        host.emitIds(0);
        host.emitValue(0, .35);
        host.next();
        JsonObject current = pending.join();
        assertEquals(
                .35, parameterValue(current), "nonexistent-source callbacks cannot repopulate a replacement cache");
        host.trackNodes[0].child("channelId").emit("replacement-track");
        host.trackNodes[0].child("channelId").emit(Fake.TRACK_ID);
        reject(host, bridge, "not_ready", "parameters.set", set(current, .75));
        assertEquals(0, host.writes, "observable track replacement leaves the device unobserved until new callbacks");
    }

    @Test
    void theModulatorSectionIsOnlyInTheFullStatus() {
        Fake host = new Fake();
        HostBridge bridge = host.bridge();
        JsonObject brief = call(host, bridge, "status", new JsonObject());
        JsonObject full = call(host, bridge, "status", HostFixture.json("{\"detail\":\"full\"}"));
        assertFalse(brief.has("modulators"));
        assertTrue(full.get("modulators").isJsonObject());
        assertFalse(full.has("parameterBindingDiagnostics"), "the single parameter binding no longer exists");
    }

    @Test
    void fullStatusDescribesBoundedImplementedSurfaces() {
        Fake host = new Fake();
        HostBridge bridge = host.bridge();
        CompletableFuture<JsonObject> pending =
                bridge.handle("status", HostFixture.json("{\"detail\":\"full\"}"), host.deadline());
        assertTrue(!pending.isDone(), "status must read host windows on the scheduled host thread");
        host.next();
        JsonObject result = pending.join();
        assertTrue(
                result.getAsJsonObject("windows")
                                .getAsJsonObject("tracks")
                                .get("total")
                                .getAsInt()
                        == HostLimits.TRACKS + 4,
                "discovery reports the actual host track count, not the window capacity");
        assertTrue(
                !result.getAsJsonObject("windows")
                        .getAsJsonObject("tracks")
                        .get("complete")
                        .getAsBoolean(),
                "status cannot promise a full project beyond the bounded bank");
        assertTrue(
                !result.getAsJsonObject("windows").has("layersPerRootDevice") && !result.has("privateGridTopology"),
                "sections owned by other domains arrive with their slices");
        Set<String> reads = new HashSet<>();
        for (JsonElement method : result.getAsJsonObject("domains").getAsJsonArray("modulators"))
            reads.add(method.getAsString());
        Set<String> registered = new HashSet<>(ModulatorModule.METHODS);
        registered.addAll(ModulatorEditModule.METHODS);
        assertEquals(registered, reads, "discovery derives the modulator domain from registered methods");
    }

    @Test
    void strictValidationPrecedesAllDomainScheduling() {
        Fake host = new Fake();
        HostBridge bridge = host.bridge();
        for (String method : List.of("status", "transport.get", "devices.catalog")) {
            JsonObject extra = new JsonObject();
            extra.addProperty("unexpected", true);
            assertRpcError("invalid_params", bridge.handle(method, extra, host.deadline()));
        }
        JsonObject fraction = root(0);
        fraction.add("index", new JsonPrimitive(new java.math.BigDecimal("1.0000000000000000001")));
        assertRpcError("invalid_params", bridge.handle("parameters.list", list(fraction), host.deadline()));
        JsonObject nullName = root(0);
        nullName.add("name", JsonNull.INSTANCE);
        assertRpcError("invalid_params", bridge.handle("parameters.list", list(nullName), host.deadline()));
        assertRpcError("invalid_params", bridge.handle("status", null, host.deadline()));
        JsonObject modulators = list(root(0));
        modulators.add("offset", JsonNull.INSTANCE);
        assertRpcError("invalid_params", bridge.handle("modulators.list", modulators, host.deadline()));
        JsonObject modulator = new JsonObject();
        modulator.add("target", root(0));
        assertRpcError("invalid_params", bridge.handle("modulators.get", modulator, host.deadline()));
        JsonObject write = set(Refs.parameter(root(0), "gain", null), Double.NaN);
        assertRpcError("invalid_params", bridge.handle("parameters.set", write, host.deadline()));
        for (String removed : List.of(
                "modulators.capabilities",
                "modulators.catalog",
                "modulators.parameters.get",
                "modulators.parameters.set",
                "devices.layers.list",
                "devices.layers.insert",
                "devices.remote.page",
                "devices.remote.set",
                "parameters.layer.list",
                "parameters.layer.set"))
            assertFalse(HostFixture.SPECS.methods().contains(removed), removed + " has no alias");
        assertTrue(host.tasks.isEmpty(), "strict validation rejects every invalid domain request before scheduling");
    }

    @Test
    void theSchemaRunsBeforeTheModuleAndFillsDefaults() {
        Fake host = new Fake();
        HostBridge bridge = host.bridge();
        assertEquals(
                100,
                bridge.validate("actions.list", new JsonObject()).get("limit").getAsInt());
        JsonObject fraction = root(0);
        fraction.add("index", JsonParser.parseString("1.0"));
        assertRpcError("invalid_params", bridge.handle("parameters.list", list(fraction), host.deadline()));
        JsonObject newline = list(root(0));
        newline.getAsJsonObject("parent").getAsJsonObject("track").addProperty("id", Fake.TRACK_ID + "\n");
        assertRpcError("invalid_params", bridge.handle("parameters.list", newline, host.deadline()));
        RpcException refused = assertThrows(RpcException.class, () -> bridge.validate("parameters.list", newline));
        assertTrue(refused.getMessage().contains("params.parent"), refused.getMessage());
        assertTrue(host.tasks.isEmpty(), "a schema rejection schedules nothing");
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
        CompletableFuture<JsonObject> listed = earlyBridge.handle("parameters.list", list(root(1)), early.deadline());
        early.next();
        if (!listed.isDone()
                || listed.isCompletedExceptionally()
                || parameterValue(listed.join()) != .66
                || early.idEmissions != emissions)
            failures.add("late agreeing root position discarded current callback data");
        assertTrue(
                failures.isEmpty(),
                "initial observation ordering must not invent a rebind or callback replay: " + failures);
    }

    @Test
    void rootEarlyDirectCallbacksKeepLegacyAdmission() {
        Fake f = new Fake();
        for (String field : List.of("exists", "position", "name", "deviceType"))
            f.deviceNodes[0].child(field).notifyInitial = false;
        HostBridge bridge = f.bridge();
        int rootEmissions = f.idEmissions;
        for (String field : List.of("exists", "position", "name", "deviceType")) {
            FakeHost value = f.deviceNodes[0].child(field);
            value.emit(value.data);
        }
        JsonObject listing = call(f, bridge, "parameters.list", list(root(0)));
        assertTrue(
                parameterValue(listing) == .25 && f.idEmissions == rootEmissions,
                "root's actual initial direct callback is retained before agreeing metadata notifications, without replay");
    }

    @Test
    void chainValidationPrecedesScheduling() {
        Fake f = new Fake();
        HostBridge bridge = f.bridge();
        JsonObject valid = list(root(0));
        for (String field : List.of("path", "padId", "slot", "layer", "layerId", "contextToken", "discoveryToken")) {
            JsonObject bad = valid.deepCopy();
            bad.addProperty(field, 0);
            assertRpcError("invalid_params", bridge.handle("chains.list", bad, f.deadline()));
        }
        JsonObject bad = valid.deepCopy();
        bad.add("parent", JsonNull.INSTANCE);
        assertRpcError("invalid_params", bridge.handle("chains.list", bad, f.deadline()));
        assertRpcError("invalid_params", bridge.handle("chains.list", list(chainRef(0)), f.deadline()));
        assertRpcError("invalid_params", bridge.handle("parameters.list", list(chainRef(0)), f.deadline()));
        JsonObject badChain = Refs.chain(root(0), "1-2-3-4-5", "Same Layer");
        assertRpcError("invalid_params", bridge.handle("devices.list", list(badChain), f.deadline()));
        JsonObject extraParent = list(chainDevice(0, 0));
        extraParent.getAsJsonObject("parent").getAsJsonObject("chain").addProperty("layerId", layerId(0));
        assertRpcError("invalid_params", bridge.handle("parameters.list", extraParent, f.deadline()));
        assertTrue(f.tasks.isEmpty(), "strict nested validation precedes host scheduling");
        JsonObject outOfRange = set(Refs.parameter(chainDevice(0, 0), "gain", null), 1.5);
        assertRpcError("invalid_params", bridge.handle("parameters.set", outOfRange, f.deadline()));
        assertTrue(f.tasks.isEmpty(), "an out-of-range value is refused before dispatch");
    }

    @Test
    void chainsFailClosedWithoutTheInspectedNativeRuntime() {
        Fake f = new Fake();
        HostBridge bridge = f.bridge();
        for (String[] call :
                new String[][] {{"chains.list", "parent"}, {"devices.list", "chain"}, {"parameters.list", "device"}}) {
            JsonObject parent =
                    call[1].equals("parent") ? root(0) : call[1].equals("chain") ? chainRef(0) : chainDevice(0, 0);
            reject(f, bridge, "host_mismatch", call[0], list(parent));
        }
        assertEquals(0, f.writes, "nothing reaches the host when chains cannot be walked natively");
    }

    private static String layerId(int index) {
        return index == 0 ? "11111111-2222-4333-8444-555555555555" : "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee";
    }

    private static JsonObject track() {
        return Refs.track(Fake.TRACK_ID, "Track");
    }

    private static JsonObject root(int index) {
        return Refs.device(track(), index, "Twin");
    }

    private static JsonObject chainRef(int layer) {
        return chainRef(root(0), layer);
    }

    private static JsonObject chainRef(JsonObject parent, int layer) {
        return Refs.chain(parent, layerId(layer), "Same Layer");
    }

    private static JsonObject chainDevice(int layer, int child) {
        return Refs.device(chainRef(layer), child, "Twin");
    }

    private static JsonObject list(JsonObject parent) {
        JsonObject p = new JsonObject();
        p.add("parent", parent);
        return p;
    }

    private static JsonObject insert(JsonObject parent, String position) {
        JsonObject p = new JsonObject();
        p.add("parent", parent);
        p.addProperty("kind", "bitwig");
        p.addProperty("id", "11111111-2222-4333-8444-555555555555");
        if (position != null) p.addProperty("position", position);
        return p;
    }

    private static JsonObject parameterRef(JsonObject listing) {
        return parameterRef(listing, 0);
    }

    private static JsonObject parameterRef(JsonObject listing, int row) {
        return listing.getAsJsonArray("parameters")
                .get(row)
                .getAsJsonObject()
                .getAsJsonObject("ref")
                .deepCopy();
    }

    private static JsonObject set(JsonObject target, double value) {
        JsonObject p = new JsonObject();
        p.add("target", target.has("parameters") ? parameterRef(target) : target);
        p.addProperty("value", value);
        return p;
    }

    private static JsonObject set(JsonObject listing, double value, int row) {
        JsonObject p = new JsonObject();
        p.add("target", parameterRef(listing, row));
        p.addProperty("value", value);
        return p;
    }

    private static double parameterValue(JsonObject listing) {
        return listing.getAsJsonArray("parameters")
                .get(0)
                .getAsJsonObject()
                .getAsJsonObject("value")
                .get("value")
                .getAsDouble();
    }

    private static String parameterName(JsonObject listing) {
        return listing.getAsJsonArray("parameters")
                .get(0)
                .getAsJsonObject()
                .get("name")
                .getAsString();
    }

    private static String readiness(JsonObject chains, int row) {
        return chains.getAsJsonArray("chains")
                .get(row)
                .getAsJsonObject()
                .get("readiness")
                .getAsString();
    }

    private static String outcome(JsonObject result) {
        return result.get("outcome").getAsString();
    }

    private static JsonObject call(Fake fake, HostBridge bridge, String method, JsonObject params) {
        CompletableFuture<JsonObject> result = bridge.handle(method, params, fake.deadline());
        while (!result.isDone()) fake.next();
        return result.join();
    }

    private static RpcException reject(Fake fake, HostBridge bridge, String reason, String method, JsonObject params) {
        CompletableFuture<JsonObject> result = bridge.handle(method, params, fake.deadline());
        while (!result.isDone()) fake.next();
        return assertRpcError(reason, result);
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
                    displayObserver(node);
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
            trackBank.put("itemCount", HostLimits.TRACKS + 4);
        }

        /** The host reports a display text for every observed id as soon as it is told which ids to observe. */
        void displayObserver(FakeHost node) {
            FakeHost observer = node.child("directDisplayObserver");
            node.on("addDirectParameterValueDisplayObserver", args -> {
                node.child("directDisplayCallback").data = args[1];
                return observer.proxy(DirectParameterValueDisplayObserver.class);
            });
            observer.on("setObservedParameterIds", args -> {
                observer.data = args[0];
                if (args[0] != null)
                    for (String id : (String[]) args[0])
                        ((DirectParameterDisplayedValueChangedCallback) node.child("directDisplayCallback").data)
                                .directParameterDisplayedValueChanged(id, "shown " + id);
                return null;
            });
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
                tasks.advance(2 * ProjectState.SETTLE_NANOS);
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
