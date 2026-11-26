package dev.bitwigagent;

import static dev.bitwigagent.RpcAssert.*;
import static org.junit.jupiter.api.Assertions.*;

import com.bitwig.extension.callback.*;
import com.google.gson.JsonObject;
import dev.bitwigagent.core.Refs;
import org.junit.jupiter.api.Test;

/** Track, device, chain and parameter refs resolve against the simulated host; never native evidence. */
public final class RefResolutionTest {
    private static final String LAYER = "00000000-0000-4000-8000-0000000000aa";
    private static final String MODULE = "00000000-0000-4000-8000-0000000000bb";
    private static final JsonObject track = Refs.track(HostFixture.A, "Track 0");
    private static final JsonObject eq = Refs.device(track, 0, "EQ+");
    private static final JsonObject rack = Refs.device(track, 1, "Instrument Layer");
    private static final JsonObject chain = Refs.chain(rack, LAYER, "Pad");
    private static final JsonObject synth = Refs.device(chain, 0, "Polysynth");
    private static final JsonObject main = Refs.remotePage(eq, 0, "Main");

    private static final class Fixture {
        final HostFixture host = new HostFixture();
        final FakeHost bank = host.tracks[0].child("createDeviceBank");
        final FakeHost eqNode = bank.item(0), rackNode = bank.item(1);
        final FakeHost page = eqNode.child("createCursorRemoteControlsPage");
        final FakeHost layer, child;
        final DeviceSources sources = host.services.sources;
        final TrackRefs tracks = host.services.trackRefs;
        StringArrayValueChangedCallback ids;
        final ParameterResolver resolver;

        Fixture() {
            eqNode.on("addDirectParameterIdObserver", args -> {
                ids = (StringArrayValueChangedCallback) args[0];
                ids.valueChanged(new String[] {"gain"});
                return null;
            });
            eqNode.on("addDirectParameterNameObserver", args -> {
                ((DirectParameterNameChangedCallback) args[1]).directParameterNameChanged("gain", "Gain");
                return null;
            });
            page.child("pageNames").data = new String[] {"Main", "Mix"};
            page.child("pageCount").data = 2;
            page.child("getName").data = "Main";
            FakeHost control = page.child("parameter0");
            control.child("exists").data = true;
            control.child("name").data = "Gain 1";
            control.child("value").data = .5;
            device(eqNode, 0, "EQ+", "audio-effect");
            device(rackNode, 1, "Instrument Layer", "instrument");
            bank.child("itemCount").emit(2);
            rackNode.child("hasLayers").emit(true);
            FakeHost layers = rackNode.child("createLayerBank");
            layers.child("exists").emit(true);
            layers.child("itemCount").emit(1);
            layer = layers.item(0);
            layer.child("exists").emit(true);
            layer.child("channelId").emit(LAYER);
            layer.child("name").emit("Pad");
            FakeHost children = layer.child("createDeviceBank");
            children.child("exists").emit(true);
            children.child("itemCount").emit(1);
            child = children.item(0);
            device(child, 0, "Polysynth", "instrument");
            resolver = host.services.parameterResolver(host.services.parameters(), host.services.device());
        }

        static void device(FakeHost node, int position, String name, String type) {
            node.child("exists").emit(true);
            node.child("position").emit(position);
            node.child("deviceType").emit(type);
            node.child("name").emit(name);
        }
    }

    @Test
    void trackRefsLocateByIdAndCompareTheNameOnlyAsTheTarget() {
        Fixture f = new Fixture();
        assertEquals(0, f.tracks.resolve(track, true));
        f.host.tracks[0].child("name").emit("Lead");
        RpcException renamed = assertRpcError("target_changed", () -> f.tracks.resolve(track, true));
        assertEquals(Refs.track(HostFixture.A, "Lead"), renamed.current);
        assertEquals(0, f.tracks.resolve(track, false), "as an ancestor the name is not compared");
        assertEquals("EQ+", f.sources.device(eq, true).name, "a device ref survives its track's rename");
        JsonObject gone = Refs.track(HostFixture.C, "Gone");
        assertRpcError("target_not_found", () -> f.tracks.locate(gone));
        f.host.bank.child("itemCount").emit(HostLimits.TRACKS + 1);
        assertRpcError("outside_window", () -> f.tracks.locate(gone));
    }

    @Test
    void deviceRefsLocateByIndexAndAlwaysCompareTheName() {
        Fixture f = new Fixture();
        assertEquals(eq, f.sources.device(eq, true).ref());
        assertRpcError("target_not_found", () -> f.sources.device(Refs.device(track, 2, "Comp"), true));
        f.eqNode.child("name").emit("Comp");
        RpcException changed = assertRpcError("target_changed", () -> f.sources.device(eq, false));
        assertEquals(Refs.device(track, 0, "Comp"), changed.current);
        f.eqNode.child("name").emit("");
        assertRpcError("not_ready", () -> f.sources.device(eq, true));
    }

    @Test
    void chainRefsLocateTheLayerByUuidAndCompareItsNameOnlyAsTheTarget() {
        Fixture f = new Fixture();
        assertEquals(LAYER, f.sources.chain(chain, true).id);
        assertEquals(synth, f.sources.device(synth, true).ref());
        f.layer.child("name").emit("Lead Pad");
        RpcException renamed = assertRpcError("target_changed", () -> f.sources.chain(chain, true));
        assertEquals("Lead Pad", renamed.current.get("name").getAsString());
        assertEquals("Polysynth", f.sources.device(synth, true).name, "a chain rename leaves its devices' refs valid");
        JsonObject other = Refs.chain(rack, "00000000-0000-4000-8000-0000000000cc", "Pad");
        assertRpcError("target_not_found", () -> f.sources.chain(other, true));
        assertRpcError("unsupported_scope", () -> f.sources.chain(Refs.chain(synth, LAYER, "Pad"), true));
        assertRpcError("target_not_found", () -> f.sources.device(Refs.device(chain, 1, "Delay"), true));
        assertRpcError("outside_window", () -> f.sources.locate(Refs.device(chain, DeviceSources.CHILDREN, "X")));
        f.child.child("deviceType").emit("audio-effect");
        assertTrue(
                assertRpcError("not_ready", () -> f.sources.device(synth, true))
                        .getMessage()
                        .contains("extension reload"),
                "a physically rebound chain device is unavailable, not stale: reading again cannot help");
    }

    @Test
    void directParametersResolveByObservedIdWithASnapshotOfTheIdSet() {
        Fixture f = new Fixture();
        ParameterResolver.Resolved gain = f.resolver.resolve(Refs.parameter(eq, "gain", "Gain"));
        assertInstanceOf(ParameterResolver.Direct.class, gain);
        assertInstanceOf(ParameterResolver.Direct.class, f.resolver.resolve(Refs.parameter(eq, "gain", null)));
        assertRpcError("target_not_found", () -> f.resolver.resolve(Refs.parameter(eq, "tone", null)));
        RpcException renamed =
                assertRpcError("target_changed", () -> f.resolver.resolve(Refs.parameter(eq, "gain", "Tone")));
        assertEquals(Refs.parameter(eq, "gain", "Gain"), renamed.current);
        RequestSnapshot snapshot = gain.snapshot();
        HostOperation op = new HostOperation(Long.MAX_VALUE);
        snapshot.require(op);
        f.ids.valueChanged(new String[] {"gain", "tone"});
        assertRpcError("target_changed", () -> snapshot.require(op));
        op.markDispatched();
        RpcException after = assertThrows(RpcException.class, () -> snapshot.require(op));
        assertTrue(after.unknown && after.is(Reason.TARGET_CHANGED), "after dispatch the outcome is unknown");
        RequestSnapshot fresh =
                f.resolver.resolve(Refs.parameter(eq, "gain", null)).snapshot();
        f.eqNode.child("exists").emit(false);
        assertRpcError("target_changed", () -> fresh.require(new HostOperation(Long.MAX_VALUE)));
        f.eqNode.child("exists").emit(true);
        assertRpcError("not_ready", () -> f.resolver.resolve(Refs.parameter(eq, "gain", null)));
        f.ids.valueChanged(new String[] {"gain"});
        f.eqNode.child("name").emit("Comp");
        RpcException owner =
                assertRpcError("target_changed", () -> f.resolver.resolve(Refs.parameter(eq, "gain", null)));
        assertEquals("Comp", owner.current.getAsJsonObject("owner").get("name").getAsString());
    }

    @Test
    void remoteControlsResolveTheirPageFirstAndTheirControlOnceThePageIsSelected() {
        Fixture f = new Fixture();
        ParameterResolver.Remote gain =
                (ParameterResolver.Remote) f.resolver.resolve(Refs.parameter(main, "0", "Gain 1"));
        gain.requireControl();
        RequestSnapshot snapshot = gain.snapshot();
        HostOperation op = new HostOperation(Long.MAX_VALUE);
        snapshot.require(op);
        ParameterResolver.Remote mix = (ParameterResolver.Remote)
                f.resolver.resolve(Refs.parameter(Refs.remotePage(eq, 1, "Mix"), "0", "Gain 1"));
        assertRpcError("not_ready", mix::requireControl);
        RpcException page = assertRpcError(
                "target_changed", () -> f.resolver.resolve(Refs.parameter(Refs.remotePage(eq, 0, "Old"), "0", "x")));
        assertEquals(main, page.current.getAsJsonObject("owner"));
        assertRpcError(
                "target_not_found", () -> f.resolver.resolve(Refs.parameter(Refs.remotePage(eq, 2, "X"), "0", "x")));
        ParameterResolver.Remote cutoff =
                (ParameterResolver.Remote) f.resolver.resolve(Refs.parameter(main, "0", "Cutoff"));
        assertEquals(
                "Gain 1",
                assertRpcError("target_changed", cutoff::requireControl)
                        .current
                        .get("name")
                        .getAsString());
        ParameterResolver.Remote empty = (ParameterResolver.Remote) f.resolver.resolve(Refs.parameter(main, "1", "x"));
        assertRpcError("target_not_found", empty::requireControl);
        f.page.child("parameter0").child("isBeingMapped").emit(true);
        assertRpcError("not_ready", gain::requireControl);
        assertRpcError("target_changed", () -> snapshot.require(op));
        JsonObject nested = Refs.parameter(Refs.remotePage(synth, 0, "Main"), "0", "x");
        assertRpcError("unsupported_scope", () -> f.resolver.resolve(nested));
        f.eqNode.child("exists").emit(false);
        f.eqNode.child("exists").emit(true);
        assertRpcError("not_ready", () -> f.resolver.resolve(Refs.parameter(main, "0", "Gain 1")));
    }

    @Test
    void trackModulatorAndGridParametersResolveTheirOwner() {
        Fixture f = new Fixture();
        ParameterResolver.TrackParameter volume =
                (ParameterResolver.TrackParameter) f.resolver.resolve(Refs.parameter(track, "volume", null));
        assertEquals(0, volume.slot);
        RequestSnapshot snapshot = volume.snapshot();
        HostOperation op = new HostOperation(Long.MAX_VALUE);
        snapshot.require(op);
        f.host.tracks[0].child("position").emit(1);
        assertRpcError("target_changed", () -> snapshot.require(op));
        assertRpcError(
                "target_not_found",
                () -> f.resolver.resolve(Refs.parameter(Refs.track(HostFixture.C, "Gone"), "pan", null)));
        for (JsonObject owner :
                new JsonObject[] {Refs.modulator(eq, MODULE, "LFO"), Refs.gridModule(eq, MODULE, "F: lp")}) {
            ParameterResolver.ModuleParameter resolved =
                    (ParameterResolver.ModuleParameter) f.resolver.resolve(Refs.parameter(owner, "RATE", null));
            assertEquals(Refs.kind(owner), resolved.owner);
            assertEquals(MODULE, resolved.ownerId.toString());
            assertEquals("RATE", resolved.id);
            assertEquals("EQ+", resolved.device.name);
            resolved.snapshot().require(op);
        }
    }

    @Test
    void parameterRefsNameTheirOwnerAndRemoteSlot() {
        assertRpcError("invalid_params", () -> ParameterRefs.owner(track));
        assertRpcError("invalid_params", () -> ParameterRefs.owner(Refs.parameter(Refs.scene(0, null), "x", null)));
        assertEquals(7, ParameterRefs.control(Refs.parameter(main, "7", null)));
        assertRpcError("invalid_params", () -> ParameterRefs.control(Refs.parameter(main, "8", null)));
    }
}
