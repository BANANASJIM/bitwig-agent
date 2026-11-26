package dev.bitwigagent;

import static dev.bitwigagent.RpcAssert.*;
import static org.junit.jupiter.api.Assertions.*;

import com.bitwig.extension.callback.*;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import dev.bitwigagent.core.Refs;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
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
            RemoteModule remotes = host.services.remote();
            resolver = host.services.parameterResolver(host.services.parameters(remotes), remotes);
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
        assertEquals("tracks.list", assertRpcError("target_not_found", () -> f.tracks.locate(gone)).next);
        f.host.bank.child("itemCount").emit(HostLimits.TRACKS + 1);
        assertRpcError("outside_window", () -> f.tracks.locate(gone));
    }

    @Test
    void deviceRefsLocateByIndexAndAlwaysCompareTheName() {
        Fixture f = new Fixture();
        assertEquals(eq, f.sources.device(eq, true).ref());
        assertEquals(
                "devices.list",
                assertRpcError("target_not_found", () -> f.sources.device(Refs.device(track, 2, "Comp"), true)).next);
        f.sources.roots[0][0].track.id = HostFixture.B;
        assertRpcError("not_ready", () -> f.sources.device(eq, true));
        f.sources.roots[0][0].track.id = HostFixture.A;
        assertEquals(0, f.sources.insertionIndex(track, new JsonPrimitive("start")));
        assertEquals(2, f.sources.insertionIndex(track, new JsonPrimitive("end")));
        assertEquals(1, f.sources.insertionIndex(track, json("{\"before\":" + rack + "}")));
        assertEquals(1, f.sources.insertionIndex(track, json("{\"after\":" + eq + "}")));
        assertEquals(1, f.sources.insertionIndex(chain, null));
        assertEquals(1, f.sources.insertionIndex(chain, json("{\"after\":" + synth + "}")));
        assertInvalidParams(() -> f.sources.insertionIndex(chain, json("{\"before\":" + eq + "}")));
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
        assertEquals("chains.list", assertRpcError("target_not_found", () -> f.sources.chain(other, true)).next);
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
        assertEquals(
                "parameters.list",
                assertRpcError("target_not_found", () -> f.resolver.resolve(Refs.parameter(eq, "tone", null))).next);
        RpcException renamed =
                assertRpcError("target_changed", () -> f.resolver.resolve(Refs.parameter(eq, "gain", "Tone")));
        assertEquals(Refs.parameter(eq, "gain", "Gain"), renamed.current);
        RequestSnapshot snapshot = gain.snapshot();
        HostOperation op = new HostOperation(Long.MAX_VALUE);
        snapshot.require(op);
        f.ids.valueChanged(new String[] {"gain", "tone"});
        assertRpcError("target_changed", () -> snapshot.require(op));
        assertInstanceOf(ParameterResolver.Direct.class, f.resolver.resolve(Refs.parameter(eq, "tone", null)));
        assertRpcError("not_ready", () -> f.resolver.resolve(Refs.parameter(eq, "tone", "Tone")));
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
        assertEquals(Refs.device(track, 0, "Comp"), owner.current, "current is the device there now");
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
        assertEquals(main, page.current, "current is the page now at that index, a ref the agent can send");
        HostFixture.REF_SCHEMA.apply(page.current);
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

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    @Test
    void aReplacedDeviceIsTargetChangedUnderEveryOwnerWhateverItsIdsAre() {
        JsonObject modulator = Refs.modulator(eq, MODULE, "LFO"), module = Refs.gridModule(eq, MODULE, "F: lp");
        JsonObject[] refs = {
            Refs.parameter(eq, "gain", null),
            Refs.parameter(main, "0", "Gain 1"),
            Refs.parameter(modulator, "RATE", null),
            Refs.parameter(module, "CUTOFF", null)
        };
        for (boolean sameIds : new boolean[] {true, false}) {
            Fixture f = new Fixture();
            f.eqNode.child("name").emit("Comp");
            if (!sameIds) {
                f.ids.valueChanged(new String[] {"ratio"});
                f.page.child("pageNames").emit(new String[] {"Dynamics"});
            }
            for (JsonObject ref : refs) {
                RpcException replaced = assertRpcError("target_changed", () -> f.resolver.resolve(ref));
                assertEquals(Refs.device(track, 0, "Comp"), replaced.current, ref + ", same ids " + sameIds);
            }
            f.rackNode.child("name").emit("Drum Machine");
            RpcException chained = assertRpcError("target_changed", () -> f.sources.chain(chain, false));
            assertEquals(Refs.device(track, 1, "Drum Machine"), chained.current, "a chain under a replaced device");
            assertRpcError("target_changed", () -> f.sources.device(synth, false));
        }
    }

    /** A native device with its owning track, and Grid modules or modulators with their parameters. */
    private static final class Natives implements DeviceSources.Native.Access, ParameterResolver.ModuleOwners {
        final Object device = new Object(), owner = new Object();
        final DeviceSources.Source source;
        final Map<UUID, Object> owners = new HashMap<>();
        final Map<Object, String> names = new HashMap<>();
        final Map<Object, Map<String, Object>> parameters = new HashMap<>();
        boolean namesReadable = true, retarget;

        Natives(DeviceSources.Source source) {
            this.source = source;
        }

        Object add(String id, String name, String key, String parameterName) {
            Object found = new Object(), parameter = new Object();
            owners.put(UUID.fromString(id), found);
            names.put(found, name);
            names.put(parameter, parameterName);
            parameters.computeIfAbsent(found, k -> new HashMap<>()).put(key, parameter);
            return found;
        }

        @Override
        public boolean is(DeviceSources.Native.Kind kind, Object object) {
            return kind == DeviceSources.Native.Kind.DEVICE
                    ? object != owner
                    : kind == DeviceSources.Native.Kind.TRACK && object == owner;
        }

        @Override
        public Object read(DeviceSources.Native.Field field, Object object) {
            switch (field) {
                case TARGET:
                    return object == source.device ? (retarget ? new Object() : device) : null;
                case OWNER_TRACK:
                    return owner;
                default:
                    return UUID.fromString(HostFixture.A);
            }
        }

        @Override
        public void documentThread() {}

        @Override
        public void requireModelThread(Object object) {}

        @Override
        public Object owner(DeviceSources.Native.Capture captured, UUID id, Runnable check) {
            return owners.get(id);
        }

        @Override
        public String ownerName(Object found) {
            return names.get(found);
        }

        @Override
        public Object parameter(Object found, String key, Runnable check) {
            return parameters.getOrDefault(found, Map.of()).get(key);
        }

        @Override
        public String parameterName(Object parameter) {
            return namesReadable ? names.get(parameter) : null;
        }
    }

    @Test
    void moduleParametersBindTheirOwnerAndParameterNatively() {
        Fixture f = new Fixture();
        DeviceSources.Source eqSource = f.sources.roots[0][0];
        Natives natives = new Natives(eqSource);
        DeviceSources.Native nativeDevices = new DeviceSources.Native(natives);
        natives.add(MODULE, "F: lp", "CUTOFF", "Cutoff");
        JsonObject module = Refs.gridModule(eq, MODULE, "renamed modules keep their parameters");
        JsonObject modulator = Refs.modulator(eq, MODULE, "LFO");
        for (JsonObject owner : new JsonObject[] {module, modulator}) {
            ParameterResolver.ModuleParameter cutoff =
                    (ParameterResolver.ModuleParameter) f.resolver.resolve(Refs.parameter(owner, "CUTOFF", "Cutoff"));
            assertEquals(Refs.kind(owner), cutoff.owner);
            assertEquals("EQ+", cutoff.device.name);
            cutoff.snapshot().require(new HostOperation(Long.MAX_VALUE));
            ParameterResolver.NativeParameter bound = cutoff.bind(natives, nativeDevices, () -> {});
            assertSame(natives.device, bound.device.device);
            HostOperation op = new HostOperation(Long.MAX_VALUE);
            bound.require(op);
            ParameterResolver.ModuleParameter tone =
                    (ParameterResolver.ModuleParameter) f.resolver.resolve(Refs.parameter(owner, "TONE", null));
            assertEquals(
                    Refs.read(Refs.parameter(owner, "TONE", null)),
                    assertRpcError("target_not_found", () -> tone.bind(natives, nativeDevices, () -> {})).next);
            ParameterResolver.ModuleParameter renamed =
                    (ParameterResolver.ModuleParameter) f.resolver.resolve(Refs.parameter(owner, "CUTOFF", "Freq"));
            assertEquals(
                    "Cutoff",
                    assertRpcError("target_changed", () -> renamed.bind(natives, nativeDevices, () -> {}))
                            .current
                            .get("name")
                            .getAsString());
            natives.namesReadable = false;
            assertRpcError("native_unavailable", () -> cutoff.bind(natives, nativeDevices, () -> {}));
            natives.namesReadable = true;
            natives.retarget = true;
            assertRpcError("target_changed", () -> bound.require(op));
            HostOperation sent = new HostOperation(Long.MAX_VALUE);
            sent.markDispatched();
            RpcException late = assertThrows(RpcException.class, () -> bound.require(sent));
            assertTrue(late.unknown && late.is(Reason.TARGET_CHANGED), "after dispatch the outcome is unknown");
            natives.retarget = false;
            eqSource.track.id = HostFixture.B;
            assertRpcError("target_changed", () -> cutoff.bind(natives, nativeDevices, () -> {}));
            eqSource.track.id = HostFixture.A;
            natives.owners.put(UUID.fromString(MODULE), new Object());
            assertRpcError("target_changed", () -> bound.require(op));
            natives.owners.remove(UUID.fromString(MODULE));
            assertEquals(
                    Refs.read(owner),
                    assertRpcError("target_not_found", () -> cutoff.bind(natives, nativeDevices, () -> {})).next);
            natives.add(MODULE, "F: lp", "CUTOFF", "Cutoff");
        }
        assertRpcError("unsupported_scope", () -> nativeDevices.capture(f.sources.device(synth, true), () -> {}));
        eqSource.track.id = null;
        assertRpcError("native_unavailable", () -> nativeDevices.capture(eqSource, () -> {}));
    }

    @Test
    void parameterOwnerKindsComeFromTheBundle() {
        assertEquals(5, ParameterRefs.OWNERS.size());
        assertThrows(IllegalStateException.class, () -> ParameterRefs.check(java.util.List.of("device")));
    }
}
