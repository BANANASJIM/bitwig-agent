package dev.bitwigagent.device;

import static dev.bitwigagent.RpcAssert.*;
import static dev.bitwigagent.device.DeviceFixture.params;
import static org.junit.jupiter.api.Assertions.*;

import com.bitwig.extension.callback.*;
import com.bitwig.extension.controller.api.*;
import com.google.gson.*;
import dev.bitwigagent.FakeHost;
import dev.bitwigagent.HostFixture;
import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.HostOperation;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.core.Refs;
import dev.bitwigagent.core.RpcException;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * devices.remote.get and the writes of Remote Controls through parameters.set, against simulated public API proxies;
 * never evidence of native Bitwig behavior.
 */
public final class RemoteModuleTest {
    private static JsonObject get(DeviceFixture f, int device) {
        return f.call(f.remote, "devices.remote.get", params("target", f.twin(device)));
    }

    private static HostOperation runGet(DeviceFixture f, int device) {
        return f.run(f.remote, "devices.remote.get", params("target", f.twin(device)));
    }

    private static JsonObject control(JsonObject listing, int slot) {
        return listing.getAsJsonArray("controls").get(slot).getAsJsonObject();
    }

    private static double value(JsonObject listing) {
        return control(listing, 0).get("value").getAsDouble();
    }

    private static JsonObject ref(JsonObject listing, int slot) {
        return control(listing, slot).getAsJsonObject("ref");
    }

    private static JsonObject gain(DeviceFixture f, int device) {
        return Refs.parameter(Refs.remotePage(f.twin(device), 0, "Main"), "0", "Gain");
    }

    private static JsonObject write(JsonObject ref, double value) {
        return params("target", ref, "value", value);
    }

    private static HostOperation run(DeviceFixture f, JsonObject ref, double value) {
        return f.run(f.parameters(), "parameters.set", write(ref, value));
    }

    private static HostOperation submit(DeviceFixture f, JsonObject ref, double value) {
        return f.submit(f.parameters(), "parameters.set", write(ref, value));
    }

    private static JsonObject call(DeviceFixture f, JsonObject ref, double value) {
        return f.call(f.parameters(), "parameters.set", write(ref, value));
    }

    private static JsonObject pageGet(DeviceFixture f, int page) {
        return f.finish(f.submit(f.remote, "devices.remote.get", params("target", f.twin(1), "page", page)));
    }

    private static void rebind(DeviceFixture f, int device) {
        f.devices[device].child("exists").emit(false);
        f.devices[device].child("exists").emit(true);
    }

    @Test
    void initialRemoteCallbacksSurviveLateIdentityPopulation() {
        for (String order : List.of("device_first", "track_first", "uuid_last")) {
            DeviceFixture f = new DeviceFixture();
            f.seedTwins(true, false);
            f.start();
            if (order.equals("track_first")) {
                f.track.child("channelId").emit(HostFixture.A);
                f.track.child("exists").emit(true);
            }
            if (order.equals("uuid_last")) {
                f.track.child("exists").emit(true);
                f.devices[1].child("exists").emit(true);
            }
            f.devices[1].child("position").emit(1);
            f.devices[1].child("deviceType").emit("audio-effect");
            f.devices[1].child("name").emit("Twin");
            f.devices[1].child("exists").emit(true);
            if (!order.equals("track_first")) {
                f.track.child("channelId").emit(HostFixture.A);
                f.track.child("exists").emit(true);
            }
            JsonObject initial = get(f, 1);
            assertTrue(initial.get("complete").getAsBoolean(), order + ": early remote callbacks are retained");
            assertEquals(.6, value(initial), order);
            assertEquals("applied", call(f, ref(initial, 0), .8).get("outcome").getAsString(), order);
            rebind(f, 1);
            assertRpcError("not_ready", runGet(f, 1));
            f.flush();
            assertEquals(
                    .8, value(get(f, 1)), order + ": the host flush restores the getters without a callback replay");
            assertEquals(1, f.controls[1][0].child("value").writes, order);
        }
    }

    @Test
    void initialControlCallbacksSurviveLatePresence() {
        DeviceFixture established = new DeviceFixture();
        established.seedTwins(false, true);
        established.start();
        established.controls[1][0].child("exists").emit(true);
        JsonObject initial = get(established, 1);
        assertTrue(
                initial.get("complete").getAsBoolean(), "first control presence retains the early metadata and value");
        assertEquals(.6, value(initial));
        assertEquals(
                "applied", call(established, ref(initial, 0), .8).get("outcome").getAsString());
        for (boolean controlFirst : List.of(false, true)) {
            DeviceFixture f = new DeviceFixture();
            f.seedTwins(true, true);
            f.start();
            if (controlFirst) f.controls[1][0].child("exists").emit(true);
            f.devices[1].child("position").emit(1);
            f.devices[1].child("deviceType").emit("audio-effect");
            f.devices[1].child("name").emit("Twin");
            f.devices[1].child("exists").emit(true);
            f.track.child("channelId").emit(HostFixture.A);
            f.track.child("exists").emit(true);
            if (!controlFirst) f.controls[1][0].child("exists").emit(true);
            JsonObject late = get(f, 1);
            assertTrue(late.get("complete").getAsBoolean(), "controlFirst=" + controlFirst);
            assertEquals(.6, value(late));
            assertEquals("applied", call(f, ref(late, 0), .8).get("outcome").getAsString());
        }
    }

    @Test
    void aRemotePageListsItsPagesAndControlsWithRefsThatCarryTheControlName() {
        DeviceFixture f = DeviceFixture.twins();
        f.controls[1][2].child("exists").emit(true);
        JsonObject listing = get(f, 1);
        assertEquals(f.twin(1), listing.getAsJsonObject("ref"));
        assertFalse(
                listing.get("followsUiPageSelection").getAsBoolean(), "the page is the extension's own, not the GUI's");
        JsonObject page = Refs.remotePage(f.twin(1), 0, "Main");
        assertEquals(page, listing.getAsJsonObject("page"));
        HostFixture.REF_SCHEMA.apply(listing.getAsJsonObject("page"));
        assertEquals(2, listing.get("pageCount").getAsInt());
        assertEquals(
                List.of(page, Refs.remotePage(f.twin(1), 1, "Tone")),
                List.of(
                        listing.getAsJsonArray("pages").get(0).getAsJsonObject(),
                        listing.getAsJsonArray("pages").get(1).getAsJsonObject()));
        assertEquals(2, listing.getAsJsonObject("pageWindow").get("total").getAsInt());
        assertEquals(8, listing.getAsJsonArray("controls").size());
        JsonObject gain = control(listing, 0);
        assertEquals(0, gain.get("index").getAsInt());
        assertTrue(gain.get("exists").getAsBoolean());
        assertEquals("Gain", gain.get("name").getAsString());
        assertFalse(gain.get("isBeingMapped").getAsBoolean());
        assertTrue(
                gain.get("value").getAsJsonPrimitive().isNumber(), "the value is the bare number a write takes back");
        assertEquals(Refs.parameter(page, "0", "Gain"), gain.getAsJsonObject("ref"));
        HostFixture.REF_SCHEMA.apply(gain.getAsJsonObject("ref"));
        JsonObject absent = control(listing, 1);
        assertFalse(absent.get("exists").getAsBoolean());
        assertFalse(absent.has("ref"), "an absent control has no ref");
        assertTrue(control(listing, 2).get("exists").getAsBoolean());
        assertFalse(control(listing, 2).has("ref"), "an unnamed control has no ref");
        assertEquals(.25, value(get(f, 0)), "same-name devices keep separate remote pages");
    }

    @Test
    void aPageIsSwitchedByTheExtensionAndNeverTheGuiOrTheSelection() {
        DeviceFixture f = deferred();
        JsonObject switched = pageGet(f, 1);
        assertEquals(Refs.remotePage(f.twin(1), 1, "Tone"), switched.getAsJsonObject("page"));
        assertTrue(switched.get("complete").getAsBoolean(), "the call waits until the new page's controls are settled");
        assertEquals(1, f.pages[1].child("selectedPageIndex").writes);
        assertEquals(0, f.pages[0].child("selectedPageIndex").writes, "only the addressed device's page changes");
        assertEquals(-1, f.top.selected, "a remote page never selects a device in the editor");
        f.controls[1][0].child("value").emit(.35);
        JsonObject again = get(f, 1);
        assertEquals(
                Refs.remotePage(f.twin(1), 1, "Tone"),
                again.getAsJsonObject("page"),
                "no page means the extension's own");
        assertEquals(.35, value(again));
        assertTrue(
                assertRpcError(
                                "target_not_found",
                                f.run(f.remote, "devices.remote.get", params("target", f.twin(1), "page", 2)))
                        .getMessage()
                        .contains("2 Remote Controls page(s)"),
                "a page past the page count says how many pages the device has");
        assertRpcError(
                "invalid_params", f.submit(f.remote, "devices.remote.get", params("target", f.twin(1), "page", -1)));
    }

    @Test
    void aSwitchTheHostReportsLaterIsFollowedByTheFlushAndThenWritten() {
        DeviceFixture f = new DeviceFixture();
        f.deferredPageSwitch = true;
        f.seedTwins(false, false);
        f.start();
        JsonObject switched =
                f.finish(f.submit(f.remote, "devices.remote.get", params("target", f.twin(1), "page", 1)));
        assertTrue(switched.get("complete").getAsBoolean(), "the host's flush made the new page's controls readable");
        assertEquals(.6, value(switched));
        assertEquals(Refs.remotePage(f.twin(1), 1, "Tone"), switched.getAsJsonObject("page"));
        JsonObject main = gain(f, 1);
        assertEquals(
                "applied",
                f.finish(submit(f, main, .8)).get("outcome").getAsString(),
                "a ref of the other page switches back and then writes");
        assertEquals(2, f.pages[1].child("selectedPageIndex").writes);
        assertEquals(1, f.controls[1][0].child("value").writes);
        assertEquals(0, f.pages[0].child("selectedPageIndex").writes);
        assertEquals(-1, f.top.selected);
        JsonObject toneRef = Refs.parameter(Refs.remotePage(f.twin(1), 1, "Tone"), "0", "Gain");
        HostOperation op = submit(f, toneRef, .7);
        assertEquals("applied", f.finish(op).get("outcome").getAsString());
        assertEquals(3, f.pages[1].child("selectedPageIndex").writes, "the page is switched before the write");
        assertEquals(
                Refs.remotePage(f.twin(1), 1, "Tone"),
                get(f, 1).getAsJsonObject("page"),
                "the extension's own page stays where its last write left it");
    }

    @Test
    void remoteControlRebindUsesFlushWithoutAcknowledgingWrites() {
        for (boolean absentCallbacks : List.of(false, true)) {
            DeviceFixture f = DeviceFixture.twins();
            FakeHost control = f.controls[1][0], other = f.controls[1][1];
            other.child("exists").emit(true);
            other.child("name").emit("Other");
            other.child("value").emit(.4);
            f.flush();
            JsonObject old = get(f, 1);
            JsonObject oldRef = ref(old, 0);
            JsonObject otherSource = get(f, 0);
            control.child("exists").emit(false);
            f.flush();
            JsonObject item = control(get(f, 1), 0);
            assertFalse(item.get("exists").getAsBoolean());
            assertTrue(
                    item.get("name").isJsonNull()
                            && item.get("isBeingMapped").isJsonNull()
                            && item.get("value").isJsonNull(),
                    "flush must not expose absent control history");
            if (absentCallbacks) {
                control.child("name").emit("Gain");
                control.child("isBeingMapped").emit(false);
                control.child("value").emit(.91);
                f.flush();
                item = control(get(f, 1), 0);
                assertTrue(
                        item.get("name").isJsonNull()
                                && item.get("isBeingMapped").isJsonNull()
                                && item.get("value").isJsonNull(),
                        "callbacks and flush during absence cannot seed readable or fresh values");
            }
            assertRpcError("target_not_found", run(f, oldRef, .6));
            control.child("exists").emit(true);
            JsonObject rebound = get(f, 1);
            item = control(rebound, 0);
            assertTrue(item.get("exists").getAsBoolean()
                    && item.get("value").isJsonNull()
                    && item.get("name").isJsonNull());
            assertFalse(rebound.get("complete").getAsBoolean(), "reappearance waits for the actual host flush");
            assertRpcError("not_ready", run(f, oldRef, .6));
            assertEquals(.4, control(rebound, 1).get("value").getAsDouble());
            assertEquals("Other", control(rebound, 1).get("name").getAsString(), "another control stays readable");
            assertEquals(
                    "applied",
                    call(f, ref(rebound, 1), .5).get("outcome").getAsString(),
                    "an unaffected control stays writable without a callback replay");
            assertEquals(
                    "applied",
                    call(f, ref(otherSource, 0), .3).get("outcome").getAsString(),
                    "another source is unaffected");
            control.child("value").data = .35;
            f.flush();
            JsonObject current = get(f, 1);
            assertTrue(current.get("complete").getAsBoolean());
            assertEquals(
                    .35,
                    value(current),
                    "flush reads the current getter, not the predecessor or absent callback value");
            control.child("value").notifyWrites = false;
            HostOperation pending = submit(f, ref(current, 0), .35);
            f.host.next();
            assertFalse(pending.result.isDone());
            assertEquals(
                    1, control.child("value").writes, "a same-value request after a rebind dispatches, never a no-op");
            f.flush();
            f.host.next();
            assertFalse(pending.result.isDone(), "a repeated flush cannot acknowledge an unchanged getter write");
            control.child("value").emit(.35);
            f.host.next();
            assertOutcome("applied", pending);
            control.child("exists").emit(true);
            JsonObject fresh = get(f, 1);
            assertEquals(
                    "already_matched",
                    call(f, ref(fresh, 0), .35).get("outcome").getAsString(),
                    "a duplicate presence keeps a genuinely fresh value for a later no-op");
            assertEquals(1, control.child("value").writes);
        }
    }

    @Test
    void aWriteIsAbsoluteAndSourceBoundUnderPendingPickup() {
        FakeHost pickup = new FakeHost("pickup");
        pickup.data = .6;
        pickup.takeoverPending = true;
        SettableRangedValue ranged = (SettableRangedValue) pickup.proxy(SettableRangedValue.class);
        ranged.set(.8);
        assertEquals(.6, ranged.get(), "pickup defers an ordinary set");
        ranged.setImmediately(.8);
        assertEquals(.8, ranged.get());
        assertTrue(pickup.takeoverPending, "the absolute setter leaves the configured takeover alone");

        DeviceFixture f = DeviceFixture.twins();
        FakeHost other = f.controls[1][1];
        other.child("exists").emit(true);
        other.child("name").emit("Other");
        other.child("value").emit(.4);
        f.flush();
        JsonObject original = get(f, 1);
        double originalValue = value(original);
        JsonObject changed = call(f, ref(original, 0), .8);
        assertEquals("applied", changed.get("outcome").getAsString());
        assertEquals(ref(original, 0), changed.getAsJsonObject("ref"));
        JsonObject field = changed.getAsJsonObject("fields").getAsJsonObject("value");
        assertEquals(.8, field.get("requested").getAsDouble());
        assertEquals("applied", field.get("outcome").getAsString());
        JsonObject listing = get(f, 1);
        assertEquals(.8, value(listing));
        assertEquals(.4, control(listing, 1).get("value").getAsDouble(), "a neighbouring control is preserved");
        assertEquals(.25, value(get(f, 0)));
        assertEquals(0, f.controls[0][0].child("value").writes);
        assertEquals(0, other.child("value").writes);
        JsonObject restored = call(f, ref(listing, 0), originalValue);
        assertEquals("applied", restored.get("outcome").getAsString());
        assertEquals(originalValue, value(get(f, 1)));
        assertTrue(f.controls[1][0].child("value").takeoverPending);
        assertEquals(
                "already_matched",
                call(f, ref(listing, 0), originalValue).get("outcome").getAsString());
        assertEquals(2, f.controls[1][0].child("value").writes, "an observed no-op under pickup does not write again");
    }

    @Test
    void aWriteIsRefusedWhileTheControlIsGoneOrTheTransportUnsafe() {
        for (String state : List.of(
                "absent",
                "isArrangerRecordEnabled",
                "isArrangerAutomationWriteEnabled",
                "isClipLauncherAutomationWriteEnabled")) {
            DeviceFixture f = DeviceFixture.twins();
            JsonObject listing = get(f, 1);
            if (state.equals("absent")) f.controls[1][0].child("exists").emit(false);
            else f.host.transport.child(state).emit(true);
            assertRpcError(state.equals("absent") ? "target_not_found" : "unsafe_state", run(f, ref(listing, 0), .8));
            assertEquals(0, f.controls[1][0].child("value").writes, state);
            assertEquals(.6, (Double) f.controls[1][0].child("value").data, state);
        }
    }

    @Test
    void aControlWhoseNameChangedAfterTheRefWasReadIsTargetChanged() {
        DeviceFixture f = DeviceFixture.twins();
        JsonObject listing = get(f, 1);
        JsonObject ref = ref(listing, 0);
        f.controls[1][0].child("name").emit("Remapped");
        assertRpcError("not_ready", run(f, ref, .3));
        f.flush();
        RpcException changed = assertRpcError("target_changed", run(f, ref, .3));
        assertEquals(
                Refs.parameter(Refs.remotePage(f.twin(1), 0, "Main"), "0", "Remapped"),
                changed.current,
                "current is the control that is there now, a ref the agent can send");
        HostFixture.REF_SCHEMA.apply(changed.current);
        assertEquals(0, f.controls[1][0].child("value").writes, "a renamed control is never written");
        JsonObject fresh = get(f, 1);
        assertEquals("Remapped", control(fresh, 0).get("name").getAsString());
        assertEquals("applied", call(f, ref(fresh, 0), .3).get("outcome").getAsString());
        f.controls[1][0].child("isBeingMapped").emit(true);
        JsonObject mapped = get(f, 1);
        assertTrue(control(mapped, 0).get("value").isJsonNull(), "a control being mapped has no readable value");
        assertFalse(mapped.get("complete").getAsBoolean());
        assertRpcError("not_ready", run(f, ref(fresh, 0), .4));
        assertEquals(1, f.controls[1][0].child("value").writes);
    }

    @Test
    void expectAndUnitsAreCheckedBeforeAnyWrite() {
        DeviceFixture f = DeviceFixture.twins();
        JsonObject target = gain(f, 1);
        JsonObject mismatch = write(target, .8);
        mismatch.add("expect", params("value", .25));
        RpcException stale = assertRpcError("target_changed", f.run(f.parameters(), "parameters.set", mismatch));
        assertEquals(.6, stale.observed.get("value").getAsDouble(), "observed holds the value that differs");
        assertEquals(0, f.controls[1][0].child("value").writes);
        JsonObject match = write(target, .8);
        match.add("expect", params("value", .6));
        assertEquals(
                "applied",
                f.call(f.parameters(), "parameters.set", match).get("outcome").getAsString());
        JsonObject display = params("target", target, "value", params("display", "50%"));
        assertRpcError("unsupported_unit", f.run(f.parameters(), "parameters.set", display));
        assertRpcError(
                "invalid_params",
                f.run(f.parameters(), "parameters.set", params("target", target, "value", params("label", "x"))));
        assertRpcError("invalid_params", run(f, target, 1.5));
        assertEquals(1, f.controls[1][0].child("value").writes, "a refused unit or range never writes");
    }

    @Test
    void twinDevicesWriteOnlyTheirOwnSource() {
        DeviceFixture f = DeviceFixture.twins();
        JsonObject second = get(f, 1);
        JsonObject result = call(f, ref(second, 0), .8);
        assertEquals(.8, value(get(f, 1)));
        assertEquals(.25, value(get(f, 0)));
        assertEquals(0, f.controls[0][0].child("value").writes);
        assertEquals(1, f.controls[1][0].child("value").writes);
        assertEquals(ref(second, 0), result.getAsJsonObject("ref"));
    }

    @Test
    void remoteWritesNeedFreshUnchangedSource() {
        DeviceFixture f = DeviceFixture.twins();
        JsonObject ref = ref(get(f, 1), 0);
        f.controls[1][0].child("value").notifyWrites = false;
        HostOperation pending = submit(f, ref, .9);
        f.host.next();
        f.controls[0][0].child("value").emit(.9);
        f.host.next();
        assertFalse(pending.result.isDone(), "a readback from another source cannot complete the write");
        f.controls[1][0].child("value").emit(.9);
        f.host.next();
        assertOutcome("applied", pending);
        JsonObject latest = ref(get(f, 1), 0);
        pending = submit(f, latest, .7);
        f.host.next();
        f.pages[1].child("getName").emit("Changed");
        f.host.next();
        assertUnknown("target_changed", pending);
        assertEquals(2, f.controls[1][0].child("value").writes, "a write is never replayed after an uncertain result");
    }

    @Test
    void observedSourceReplacementReconcilesAtFlush() {
        for (String transition :
                List.of("device_exists", "device_position", "track_uuid", "track_exists", "device_type")) {
            DeviceFixture f = DeviceFixture.twins();
            JsonObject old = get(f, 1);
            JsonObject target = f.twin(1);
            JsonObject oldRef = ref(old, 0);
            JsonObject currentRef = oldRef;
            switch (transition) {
                case "device_exists":
                    f.devices[1].child("exists").emit(false);
                    f.controls[1][0].child("value").emit(.91);
                    f.devices[1].child("exists").emit(true);
                    break;
                case "device_position":
                    f.devices[1].child("position").emit(2);
                    f.flush();
                    assertRpcError("target_not_found", f.run(f.remote, "devices.remote.get", params("target", target)));
                    f.devices[1].child("position").emit(1);
                    break;
                case "track_uuid":
                    String replacement = "7a71b30b-abf3-4e23-9a7e-8a346e301a2f";
                    f.track.child("channelId").emit(replacement);
                    target = Refs.device(Refs.track(replacement, "Track 0"), 1, "Twin");
                    currentRef = Refs.parameter(Refs.remotePage(target, 0, "Main"), "0", "Gain");
                    break;
                case "track_exists":
                    f.track.child("exists").emit(false);
                    f.controls[1][0].child("value").emit(.91);
                    f.flush();
                    f.track.child("exists").emit(true);
                    break;
                case "device_type":
                    f.devices[1].child("deviceType").emit("instrument");
                    break;
                default:
                    throw new AssertionError(transition);
            }
            JsonObject now = target;
            assertRpcError("not_ready", f.run(f.remote, "devices.remote.get", params("target", now)));
            assertRpcError(transition.equals("track_uuid") ? "target_not_found" : "not_ready", run(f, oldRef, .6));
            assertRpcError("not_ready", run(f, currentRef, .6));
            f.pages[1].child("pageNames").data = new String[] {"Main", "Tone", "Third"};
            f.pages[1].child("pageCount").data = 3;
            f.controls[1][0].child("value").data = .35;
            assertTrue(
                    f.host.flushRequests > 0 && f.host.tasks.isEmpty(),
                    transition + ": a rebind requests a host flush, not a timer");
            f.flush();
            JsonObject fresh = f.call(f.remote, "devices.remote.get", params("target", now));
            assertTrue(fresh.get("complete").getAsBoolean(), transition);
            assertEquals(.35, value(fresh), transition + ": the flush restores the getters without a callback replay");
            assertEquals(3, fresh.get("pageCount").getAsInt());
            assertEquals(3, fresh.getAsJsonArray("pages").size());
            f.controls[1][0].child("value").notifyWrites = false;
            HostOperation pending = submit(f, ref(fresh, 0), .35);
            f.host.next();
            assertFalse(pending.result.isDone(), transition + ": a reconciled value is readable, not a verified no-op");
            assertEquals(1, f.controls[1][0].child("value").writes);
            f.flush();
            f.host.next();
            assertFalse(pending.result.isDone(), transition + ": a flush acknowledges no write");
            f.controls[1][0].child("value").emit(.35);
            f.host.next();
            assertOutcome("applied", pending);
        }
    }

    @Test
    void rawDeviceTypeCallbacksReconcileCanonicalGetters() {
        DeviceFixture f = DeviceFixture.twins();
        get(f, 1);
        f.devices[1].child("exists").emit(false);
        FakeHost type = f.devices[1].child("deviceType");
        type.data = "audio-effect";
        for (Object observer : type.observers) ((EnumValueChangedCallback) observer).valueChanged("audio_to_audio");
        f.devices[1].child("exists").emit(true);
        f.flush();
        assertTrue(
                get(f, 1).get("complete").getAsBoolean(),
                "a raw native type callback and the canonical public getter cannot strand remote recovery");
    }

    @Test
    void aRecoveredPageBaselineInvalidatesALaterTransition() {
        DeviceFixture f = DeviceFixture.twins();
        get(f, 1);
        rebind(f, 1);
        f.pages[1].child("selectedPageIndex").data = 1;
        f.pages[1].child("getName").data = "Tone";
        f.flush();
        f.controls[1][0].child("value").emit(.8);
        f.pages[1].child("selectedPageIndex").emit(0);
        f.pages[1].child("getName").data = "Main";
        JsonObject pending = get(f, 1);
        assertTrue(
                control(pending, 0).get("value").isJsonNull(),
                "a page callback after a getter-only recovery invalidates the predecessor's readiness");
        f.controls[1][0].child("value").data = .6;
        f.flush();
        JsonObject current = get(f, 1);
        f.controls[1][0].child("value").notifyWrites = false;
        HostOperation op = f.run(f.parameters(), "parameters.set", write(ref(current, 0), .6), f.host.tasks.after(20));
        assertUnknown("not_observed", op);
        assertEquals(1, f.controls[1][0].child("value").writes);
    }

    @Test
    void aPageRoundtripRecoversUnchangedValues() {
        DeviceFixture f = deferred();
        FakeHost neighbor = f.controls[1][1];
        neighbor.child("exists").emit(true);
        neighbor.child("name").emit("Neighbor");
        neighbor.child("value").emit(.4);
        f.flush();
        get(f, 1);
        assertTrue(pageGet(f, 1).get("complete").getAsBoolean());
        neighbor.child("exists").emit(false);
        f.flush();
        JsonObject tone = get(f, 1);
        assertTrue(tone.get("complete").getAsBoolean());
        assertEquals(.6, value(tone), "an unchanged slot value is readable at the flush without a value callback");
        JsonObject absent = control(tone, 1);
        assertFalse(absent.get("exists").getAsBoolean());
        assertTrue(absent.get("name").isJsonNull() && absent.get("value").isJsonNull());
        neighbor.child("exists").emit(true);
        JsonObject returned = pageGet(f, 0);
        f.flush();
        returned = get(f, 1);
        assertTrue(returned.get("complete").getAsBoolean());
        assertEquals(.6, value(returned));
        assertEquals(
                .4, control(returned, 1).get("value").getAsDouble(), "returning controls need no name or value replay");
        f.controls[1][0].child("value").notifyWrites = false;
        HostOperation op = f.run(f.parameters(), "parameters.set", write(ref(returned, 0), .6), f.host.tasks.after(20));
        assertUnknown("not_observed", op);
        assertEquals(
                1,
                f.controls[1][0].child("value").writes,
                "getter equality after a page return is readable, not fresh");
    }

    @Test
    void aSameProcessReopenRecoversUnchangedHistory() {
        DeviceFixture f = DeviceFixture.twins();
        JsonObject original = get(f, 1);
        f.track.child("exists").emit(false);
        f.devices[1].child("exists").emit(false);
        f.controls[1][0].child("exists").emit(false);
        f.pages[1].child("getName").emit("");
        f.controls[1][0].child("name").emit("");
        f.controls[1][0].child("value").emit(.91);
        f.flush();
        assertRpcError("target_not_found", runGet(f, 1));
        f.pages[1].child("getName").data = "Main";
        f.controls[1][0].child("name").data = "Gain";
        f.controls[1][0].child("value").data = .6;
        f.controls[1][0].child("exists").data = true;
        f.devices[1].child("exists").emit(true);
        f.track.child("channelId").emit(HostFixture.A);
        f.track.child("exists").emit(true);
        assertRpcError("not_ready", runGet(f, 1));
        f.flush();
        JsonObject reopened = get(f, 1);
        assertTrue(reopened.get("complete").getAsBoolean());
        assertEquals(.6, value(reopened));
        assertEquals("Main", reopened.getAsJsonObject("page").get("name").getAsString());
        assertEquals(original.getAsJsonObject("page"), reopened.getAsJsonObject("page"), "refs carry no session token");
        assertEquals(0, f.controls[1][0].child("value").writes);
        assertEquals(0, f.pages[1].child("selectedPageIndex").writes, "recovery never writes to provoke callbacks");
    }

    @Test
    void pendingRemoteWritesCannotSurviveRebinding() {
        for (String transition : List.of("source", "page", "control", "mapping")) {
            DeviceFixture f = DeviceFixture.twins();
            JsonObject original = get(f, 1);
            f.controls[1][0].child("value").notifyWrites = false;
            HostOperation pending = submit(f, ref(original, 0), .9);
            f.host.next();
            if (transition.equals("source")) rebind(f, 1);
            else if (transition.equals("page")) {
                f.pages[1].child("selectedPageIndex").emit(1);
                f.pages[1].child("getName").emit("Tone");
            } else if (transition.equals("control")) {
                f.controls[1][0].child("exists").emit(false);
                f.controls[1][0].child("exists").emit(true);
            } else {
                f.controls[1][0].child("isBeingMapped").emit(true);
                f.controls[1][0].child("value").emit(.9);
                f.flush();
                f.controls[1][0].child("isBeingMapped").emit(false);
            }
            f.flush();
            f.host.next();
            assertUnknown("target_changed", pending);
            JsonObject current = get(f, 1);
            assertEquals(.9, value(current), transition);
            assertEquals(
                    1,
                    f.controls[1][0].child("value").writes,
                    transition + ": recovery never retries or acknowledges the write");
        }
    }

    @Test
    void flushCannotInventUnobservedOrFiniteValues() {
        for (String field : List.of("getName", "exists", "name", "isBeingMapped", "value")) {
            DeviceFixture f = new DeviceFixture();
            f.silent(field);
            f.seedTwins(false, false);
            f.start();
            rebind(f, 1);
            f.flush();
            if (field.equals("getName")) {
                assertRpcError("not_ready", runGet(f, 1));
                f.pages[1].child(field).emit("Main");
            } else {
                JsonObject incomplete = get(f, 1);
                assertFalse(incomplete.get("complete").getAsBoolean(), field);
                assertTrue(
                        control(incomplete, 0).get(field).isJsonNull(),
                        field + ": a flush cannot fabricate an observation from an unobserved default getter");
                boolean awaited = field.equals("isBeingMapped") || field.equals("value");
                assertRpcError(
                        awaited ? "not_observed" : "not_ready",
                        f.run(f.parameters(), "parameters.set", write(gain(f, 1), .6), f.host.tasks.after(20)));
                assertEquals(0, f.controls[1][0].child("value").writes, field);
                f.controls[1][0].child(field).emit(f.controls[1][0].child(field).data);
            }
            f.flush();
            assertEquals(.6, value(get(f, 1)), field + ": the actual first observation makes the source readable");
        }
        DeviceFixture f = DeviceFixture.twins();
        f.controls[1][0].child("value").emit(Double.NaN);
        rebind(f, 1);
        f.flush();
        assertTrue(
                control(get(f, 1), 0).get("value").isJsonNull(),
                "a nonfinite current getter cannot fall back to the last finite observation");
        assertRpcError("not_ready", run(f, gain(f, 1), .6));
        assertEquals(0, f.controls[1][0].child("value").writes, "a nonfinite readback cannot authorize a write");
    }

    @Test
    void recoveredSameValueWritesRetainSafetyAndFreshness() {
        for (String unsafe : List.of(
                "isArrangerRecordEnabled",
                "isArrangerAutomationWriteEnabled",
                "isClipLauncherAutomationWriteEnabled")) {
            DeviceFixture f = DeviceFixture.twins();
            rebind(f, 1);
            f.flush();
            JsonObject current = get(f, 1);
            f.host.transport.child(unsafe).emit(true);
            assertRpcError("unsafe_state", run(f, ref(current, 0), .6));
            assertEquals(0, f.controls[1][0].child("value").writes, unsafe + ": getter equality is not a safe no-op");
        }
        DeviceFixture f = DeviceFixture.twins();
        rebind(f, 1);
        f.flush();
        get(f, 1);
        f.pages[1].child("selectedPageIndex").notifyWrites = false;
        HostOperation page =
                f.run(f.remote, "devices.remote.get", params("target", f.twin(1), "page", 0), f.host.tasks.after(20));
        assertRpcError("not_observed", page);
        assertEquals(
                1,
                f.pages[1].child("selectedPageIndex").writes,
                "a recovered unchanged page index also needs a fresh observer, not a false verified no-op");

        DeviceFixture mapped = DeviceFixture.twins();
        mapped.controls[1][0].child("isBeingMapped").emit(true);
        mapped.controls[1][0].child("value").emit(.6);
        mapped.flush();
        JsonObject mapping = get(mapped, 1);
        assertTrue(control(mapping, 0).get("value").isJsonNull(), "a flush while mapping cannot expose a mapped value");
        assertRpcError("not_ready", run(mapped, gain(mapped, 1), .6));
        mapped.controls[1][0].child("isBeingMapped").emit(false);
        mapped.flush();
        JsonObject unmapped = get(mapped, 1);
        mapped.controls[1][0].child("value").notifyWrites = false;
        HostOperation unacknowledged = mapped.run(
                mapped.parameters(), "parameters.set", write(ref(unmapped, 0), .6), mapped.host.tasks.after(20));
        assertUnknown("not_observed", unacknowledged);
        assertEquals(1, mapped.controls[1][0].child("value").writes, "a callback during remapping is no no-op proof");
    }

    /** A control whose cache is not fresh (rebound, never re-reported), with the model answering as {@code held}. */
    private static DeviceFixture notFresh(RemoteModule.NativeValues model) {
        DeviceFixture f = DeviceFixture.twins();
        rebind(f, 1);
        f.flush();
        f.remote.nativeValues = model;
        f.controls[1][0].child("value").notifyWrites = false;
        return f;
    }

    @Test
    void anEqualWriteOnAStaleCacheIsAlreadyMatchedWhenTheNativeModelHoldsTheValue() {
        int[] reads = {0};
        DeviceFixture f = notFresh((op, device, page, pageName, pageCount, slot, done, failed) -> {
            reads[0]++;
            assertEquals(0, page);
            assertEquals("Main", pageName);
            assertEquals(0, slot);
            done.accept(.6);
        });
        JsonObject current = get(f, 1);
        HostOperation op = f.run(f.parameters(), "parameters.set", write(ref(current, 0), .6), f.host.tasks.after(20));
        JsonObject result = f.finish(op);
        assertEquals("already_matched", result.get("outcome").getAsString());
        assertEquals("native_readback", result.get("verification").getAsString());
        assertEquals(1, reads[0]);
        assertEquals(0, f.controls[1][0].child("value").writes, "nothing was sent");
        assertFalse(op.dispatched());
    }

    @Test
    void aDifferentValueOnAStaleCacheIsStillDispatchedAfterTheNativeRead() {
        DeviceFixture f = notFresh((op, device, page, pageName, pageCount, slot, done, failed) -> done.accept(.2));
        f.controls[1][0].child("value").notifyWrites = true;
        JsonObject current = get(f, 1);
        JsonObject result = f.finish(submit(f, ref(current, 0), .6));
        assertEquals("applied", result.get("outcome").getAsString());
        assertEquals(1, f.controls[1][0].child("value").writes);
    }

    @Test
    void aStaleCacheAloneStillNeverProvesAnEqualWrite() {
        for (String model : List.of("none", "unanswered", "refused")) {
            DeviceFixture f = notFresh(
                    model.equals("none")
                            ? null
                            : (op, device, page, pageName, pageCount, slot, done, failed) -> {
                                if (model.equals("unanswered")) done.accept(null);
                                else failed.accept(Errors.notApplied(Reason.NOT_READY, "model busy"));
                            });
            JsonObject current = get(f, 1);
            HostOperation op =
                    f.run(f.parameters(), "parameters.set", write(ref(current, 0), .6), f.host.tasks.after(20));
            f.finishOrError(op);
            if (model.equals("refused")) assertRpcError("not_ready", op);
            else
                assertEquals(
                        "devices.remote.get",
                        assertUnknown("not_observed", op).get("next").getAsString());
            assertEquals(model.equals("refused") ? 0 : 1, f.controls[1][0].child("value").writes, model);
        }
    }

    @Test
    void aChangeOfTheRemoteStateDuringTheNativeReadIsTargetChangedNeverAlreadyMatched() {
        DeviceFixture f = notFresh(null);
        f.remote.nativeValues = (op, device, page, pageName, pageCount, slot, done, failed) -> {
            f.controls[1][0].child("name").emit("Remapped");
            done.accept(.6);
        };
        JsonObject current = get(f, 1);
        HostOperation op = f.run(f.parameters(), "parameters.set", write(ref(current, 0), .6), f.host.tasks.after(20));
        f.finishOrError(op);
        assertRpcError("target_changed", op);
        assertEquals(0, f.controls[1][0].child("value").writes);
    }

    @Test
    void aFreshCacheNeverAsksTheNativeModel() {
        DeviceFixture f = DeviceFixture.twins();
        f.remote.nativeValues = (op, device, page, pageName, pageCount, slot, done, failed) -> fail("no native read");
        JsonObject current = get(f, 1);
        assertEquals("applied", call(f, ref(current, 0), .3).get("outcome").getAsString());
        assertEquals(
                "already_matched", call(f, ref(current, 0), .3).get("outcome").getAsString());
    }

    @Test
    void aRenamedDeviceKeepsItsObservationsButOldRefsToItAreTargetChanged() {
        DeviceFixture f = DeviceFixture.twins();
        JsonObject old = get(f, 1);
        f.devices[1].child("name").emit("Renamed");
        RpcException changed = assertRpcError("target_changed", runGet(f, 1));
        assertEquals(f.device(1, "Renamed"), changed.current);
        assertRpcError("target_changed", run(f, ref(old, 0), .8));
        assertEquals(0, f.controls[1][0].child("value").writes);
        JsonObject fresh = f.call(f.remote, "devices.remote.get", params("target", f.device(1, "Renamed")));
        assertEquals(
                .6, value(fresh), "a rename retains the continuously bound remote values without a callback replay");
        assertEquals("applied", call(f, ref(fresh, 0), .8).get("outcome").getAsString());
    }

    @Test
    void aDeviceInsideAChainHasNoRemoteControls() {
        DeviceFixture f = new DeviceFixture();
        f.top.seed("Rack");
        DeviceFixture.Rack rack = f.rack(0);
        rack.layer(0, DeviceFixture.LAYER_A, "Pad", "Delay");
        f.start();
        assertRpcError(
                "unsupported_scope", f.run(f.remote, "devices.remote.get", params("target", rack.deviceRef(0, 0))));
        JsonObject page = Refs.remotePage(rack.deviceRef(0, 0), 0, "Main");
        assertRpcError("unsupported_scope", run(f, Refs.parameter(page, "0", "Gain"), .5));
    }

    private static DeviceFixture deferred() {
        DeviceFixture f = new DeviceFixture();
        f.deferredPageSwitch = true;
        f.seedTwins(false, false);
        f.start();
        return f;
    }

    /** Runs host tasks, the other request's included, until this request is settled. */
    private static HostOperation refused(DeviceFixture f, HostOperation op) {
        while (!op.result.isDone()) f.host.next();
        return op;
    }

    private static JsonObject toneGain(DeviceFixture f) {
        return Refs.parameter(Refs.remotePage(f.twin(1), 1, "Tone"), "0", "Gain");
    }

    @Test
    void aSecondRequestMeetsBusyWhileAnotherOwnsTheDevicesPage() {
        DeviceFixture f = deferred();
        HostOperation a = submit(f, toneGain(f), .8);
        f.host.next();
        assertFalse(a.result.isDone(), "the switch is pending");
        assertRpcError(
                "busy", refused(f, f.submit(f.remote, "devices.remote.get", params("target", f.twin(1), "page", 0))));
        assertRpcError("busy", refused(f, submit(f, gain(f, 1), .3)));
        assertEquals(0, f.controls[1][0].child("value").writes, "the refused requests wrote nothing");
        assertEquals("applied", f.finish(a).get("outcome").getAsString());
        assertEquals(1, f.controls[1][0].child("value").writes, "only the page-1 write landed");
        assertEquals(.8, (Double) f.controls[1][0].child("value").data);
        JsonObject back = f.finish(f.submit(f.remote, "devices.remote.get", params("target", f.twin(1), "page", 0)));
        assertEquals(
                Refs.remotePage(f.twin(1), 0, "Main"), back.getAsJsonObject("page"), "after A completed B succeeds");
        assertEquals(1, f.controls[1][0].child("value").writes);
    }

    @Test
    void aWriteWaitsForTheFlushAfterTheSwitchAndNeverLandsOnThePreviousPage() {
        DeviceFixture f = deferred();
        f.flushAfterSwitch = false;
        HostOperation a = submit(f, toneGain(f), .8);
        f.host.next();
        f.host.next();
        f.host.next();
        assertFalse(a.result.isDone(), "the page callback arrived but the controls are still pending their rebind");
        assertEquals(0, f.controls[1][0].child("value").writes, "nothing is written before the flush");
        f.flush();
        assertEquals("applied", f.finish(a).get("outcome").getAsString());
        assertEquals(1, f.controls[1][0].child("value").writes);
        assertEquals(
                1, (int) (Integer) f.pages[1].child("selectedPageIndex").data, "the write landed after the switch");
    }

    @Test
    void thePageRefNameComesFromThePageListNotTheLaggingNameCallback() {
        DeviceFixture f = DeviceFixture.twins();
        f.pages[1].child("getName").emit("Lagging");
        JsonObject listing = get(f, 1);
        assertEquals(Refs.remotePage(f.twin(1), 0, "Main"), listing.getAsJsonObject("page"));
    }

    @Test
    void thePageLeaseIsReleasedWhenTheOwningRequestFails() {
        DeviceFixture f = deferred();
        f.pages[1].child("selectedPageIndex").notifyWrites = false;
        HostOperation a = f.run(f.parameters(), "parameters.set", write(toneGain(f), .8), f.host.tasks.after(20));
        assertRpcError("not_observed", a);
        assertEquals(0, f.controls[1][0].child("value").writes);
        JsonObject b = f.finish(f.submit(f.remote, "devices.remote.get", params("target", f.twin(1), "page", 0)));
        assertTrue(b.get("complete").getAsBoolean(), "the failed owner no longer holds the page");
        HostOperation c = f.run(f.parameters(), "parameters.set", write(gain(f, 1), .3));
        assertEquals("applied", f.finish(c).get("outcome").getAsString());
    }
}
