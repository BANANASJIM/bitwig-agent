package dev.bitwigagent.device;

import static dev.bitwigagent.RpcAssert.*;
import static org.junit.jupiter.api.Assertions.*;

import com.bitwig.extension.callback.*;
import com.google.gson.*;
import dev.bitwigagent.FakeHost;
import dev.bitwigagent.HostFixture;
import dev.bitwigagent.ScheduledTasks;
import dev.bitwigagent.core.DisplaySolver;
import dev.bitwigagent.core.HostOperation;
import dev.bitwigagent.core.Refs;
import dev.bitwigagent.core.RpcException;
import dev.bitwigagent.host.DeviceSources;
import java.util.*;
import java.util.function.DoubleFunction;
import org.junit.jupiter.api.Test;

/** parameters.list and parameters.set on the simulated host: direct parameters by ref, display writes by bisection. */
public final class ParameterModuleTest {
    private static final String LAYER = "00000000-0000-4000-8000-0000000000aa";
    private static final JsonObject track = Refs.track(HostFixture.A, "Track 0");
    private static final JsonObject eq = Refs.device(track, 0, "EQ+");
    private static final JsonObject comp = Refs.device(track, 1, "Comp");
    private static final JsonObject rack = Refs.device(track, 2, "Instrument Layer");
    private static final JsonObject chain = Refs.chain(rack, LAYER, "Pad");
    private static final JsonObject synth = Refs.device(chain, 0, "Polysynth");

    /** A device with direct parameters whose host-side model is a value and the text the host shows for it. */
    private static final class Direct {
        final FakeHost node;
        final Map<String, Double> values = new LinkedHashMap<>();
        final Map<String, String> names = new LinkedHashMap<>();
        final Map<String, DoubleFunction<String>> displays = new HashMap<>();
        final Set<String> ignoredWrites = new HashSet<>();
        DirectParameterNormalizedValueChangedCallback valueCallback;
        DirectParameterNameChangedCallback nameCallback;
        StringArrayValueChangedCallback idCallback;
        DirectParameterDisplayedValueChangedCallback displayCallback;
        String[] observed;
        int writes;
        final List<String[]> observations = new ArrayList<>();

        Direct(FakeHost node) {
            this.node = node;
            node.on("addDirectParameterIdObserver", args -> {
                idCallback = (StringArrayValueChangedCallback) args[0];
                idCallback.valueChanged(values.keySet().toArray(new String[0]));
                return null;
            });
            node.on("addDirectParameterNameObserver", args -> {
                nameCallback = (DirectParameterNameChangedCallback) args[1];
                names.forEach(nameCallback::directParameterNameChanged);
                return null;
            });
            node.on("addDirectParameterNormalizedValueObserver", args -> {
                valueCallback = (DirectParameterNormalizedValueChangedCallback) args[0];
                values.forEach(valueCallback::directParameterNormalizedValueChanged);
                return null;
            });
            node.on("setDirectParameterValueNormalized", args -> {
                String id = (String) args[0];
                double value = ((Number) args[1]).doubleValue() / ((Number) args[2]).doubleValue();
                writes++;
                if (ignoredWrites.contains(id)) return null;
                values.put(id, value);
                valueCallback.directParameterNormalizedValueChanged(id, value);
                showIfObserved(id);
                return null;
            });
            FakeHost observer = node.child("directDisplayObserver");
            observer.onCall = (name, args) -> {
                if (!name.equals("setObservedParameterIds")) return;
                observed = (String[]) args[0];
                observations.add(observed);
                displayCallback =
                        (DirectParameterDisplayedValueChangedCallback) node.child("directDisplayCallback").data;
                if (observed != null) for (String id : observed) showIfObserved(id);
            };
        }

        /** When set, the host reports a display this many milliseconds after the value it belongs to. */
        ScheduledTasks later;

        long delayMs;

        void showIfObserved(String id) {
            if (observed == null || !Arrays.asList(observed).contains(id) || !displays.containsKey(id)) return;
            String text = displays.get(id).apply(values.get(id));
            if (delayMs > 0)
                later.schedule(() -> displayCallback.directParameterDisplayedValueChanged(id, text), delayMs);
            else displayCallback.directParameterDisplayedValueChanged(id, text);
        }
    }

    private static final class Fixture {
        final HostFixture host = new HostFixture();
        final FakeHost bank = host.tracks[0].child("createDeviceBank");
        final FakeHost eqNode = bank.item(0), compNode = bank.item(1), rackNode = bank.item(2);
        final Direct eqParameters = new Direct(eqNode), compParameters = new Direct(compNode);
        /** The device inside the rack's chain, in the simulated native document. */
        final FakeChains.Node synth = new FakeChains.Node("Polysynth", "instrument");

        final ParameterModule parameters;
        final DeviceSources sources = host.services.sources;

        Fixture() {
            eqParameters.later = host.tasks;
            eqParameters.values.put("gain", .5);
            eqParameters.names.put("gain", "Gain");
            eqParameters.displays.put("gain", v -> String.format("%.1f dB", -24 + 36 * v));
            eqParameters.values.put("freq", .25);
            eqParameters.names.put("freq", "Frequency");
            eqParameters.displays.put("freq", v -> String.format("%.0f Hz", 20 * Math.pow(1000, v)));
            eqParameters.values.put("mode", 0.0);
            eqParameters.names.put("mode", "Mode");
            eqParameters.displays.put("mode", v -> "LP 24");
            compParameters.values.put("ratio", .1);
            compParameters.names.put("ratio", "Ratio");
            device(eqNode, 0, "EQ+", "audio-effect");
            device(compNode, 1, "Comp", "audio-effect");
            device(rackNode, 2, "Instrument Layer", "instrument");
            bank.child("itemCount").emit(3);
            host.services
                    .nativeChains
                    .root(0, 2, FakeChains.device("Instrument Layer").layered())
                    .chain(LAYER, "Pad", synth);
            synth.param("cutoff", "Cutoff", .5).display = v -> String.format("%.0f Hz", 20 * Math.pow(1000, v));
            RemoteModule remotes = host.services.remote();
            parameters = host.services.parameters(remotes);
        }

        static void device(FakeHost node, int position, String name, String type) {
            node.child("exists").emit(true);
            node.child("position").emit(position);
            node.child("deviceType").emit(type);
            node.child("name").emit(name);
        }

        JsonObject run(String method, JsonObject params) {
            HostOperation op = host.submit(parameters, method, params);
            while (!op.result.isDone()) host.next();
            return op.result.join();
        }

        HostOperation start(String method, JsonObject params) {
            return host.submit(parameters, method, params);
        }

        void finish(HostOperation op) {
            while (!op.result.isDone()) host.next();
        }

        /** The error a request ends in, once the host has run everything it scheduled. */
        RpcException refused(String reason, String method, JsonObject params) {
            HostOperation op = start(method, params);
            finish(op);
            return assertRpcError(reason, op);
        }
    }

    private static JsonObject params(JsonObject target, String value) {
        JsonObject p = new JsonObject();
        p.add("target", target);
        p.add("value", JsonParser.parseString(value));
        return p;
    }

    private static JsonObject list(JsonObject parent) {
        JsonObject p = new JsonObject();
        p.add("parent", parent);
        return p;
    }

    @Test
    void listingGivesEveryParameterItsRefNameAndValueAndNoDisplayUntilTheHostReportsOne() {
        Fixture f = new Fixture();
        f.eqParameters.displays.clear();
        JsonObject result = f.run("parameters.list", list(eq));
        assertEquals(eq, result.get("parent"));
        JsonArray rows = result.getAsJsonArray("parameters");
        assertEquals(3, rows.size());
        JsonObject gain = rows.get(0).getAsJsonObject();
        assertEquals(Refs.parameter(eq, "gain", "Gain"), gain.get("ref"));
        HostFixture.REF_SCHEMA.apply(gain.getAsJsonObject("ref"));
        assertEquals("Gain", gain.get("name").getAsString());
        assertEquals(.5, gain.getAsJsonObject("value").get("value").getAsDouble());
        assertFalse(gain.getAsJsonObject("value").has("display"));
        assertNull(f.eqParameters.observed, "display observation is released after the list");
    }

    @Test
    void listingAsksTheHostForDisplaysOnlyWhileItBuildsTheRows() {
        Fixture f = new Fixture();
        JsonObject rows = f.run("parameters.list", list(eq));
        JsonObject freq = rows.getAsJsonArray("parameters").get(1).getAsJsonObject();
        assertEquals("112 Hz", freq.getAsJsonObject("value").get("display").getAsString());
        assertEquals(
                List.of("gain", "freq", "mode"), Arrays.asList(f.eqParameters.observations.get(0)), "all ids at once");
        assertNull(f.eqParameters.observations.get(1), "then none");
        assertEquals(2, f.eqParameters.observations.size());
        JsonObject again = f.run("parameters.list", list(eq));
        assertEquals(
                "112 Hz",
                again.getAsJsonArray("parameters")
                        .get(1)
                        .getAsJsonObject()
                        .getAsJsonObject("value")
                        .get("display")
                        .getAsString());
    }

    @Test
    void everyValueAListReturnedWritesBackAsAlreadyMatched() {
        Fixture f = new Fixture();
        for (JsonElement row : f.run("parameters.list", list(eq)).getAsJsonArray("parameters")) {
            JsonObject parameter = row.getAsJsonObject();
            JsonObject back = f.run(
                    "parameters.set",
                    params(
                            parameter.getAsJsonObject("ref"),
                            parameter.getAsJsonObject("value").get("value").toString()));
            assertEquals(
                    "already_matched",
                    back.get("outcome").getAsString(),
                    parameter.get("name").getAsString());
        }
        assertEquals(0, f.eqParameters.writes, "writing the read value back sends nothing");
    }

    @Test
    void aDeviceInsideAChainIsListedAndWrittenLikeARootDevice() {
        Fixture f = new Fixture();
        JsonObject listed = f.run("parameters.list", list(synth));
        JsonObject row = listed.getAsJsonArray("parameters").get(0).getAsJsonObject();
        assertEquals(Refs.parameter(synth, "cutoff", "Cutoff"), row.get("ref"));
        assertEquals(.5, row.getAsJsonObject("value").get("value").getAsDouble());
        assertEquals("632 Hz", row.getAsJsonObject("value").get("display").getAsString(), "the host's own display");
        FakeChains.Param cutoff = f.synth.params.get("cutoff");
        JsonObject written = f.run("parameters.set", params(Refs.parameter(synth, "cutoff", null), "0.8"));
        assertEquals("applied", written.get("outcome").getAsString());
        assertEquals(.8, cutoff.value, 1e-4);
        JsonObject again = f.run("parameters.set", params(Refs.parameter(synth, "cutoff", "Cutoff"), "0.8"));
        assertEquals("already_matched", again.get("outcome").getAsString());
        assertEquals(1, cutoff.writes, "an equal value is not written");
        cutoff.sticks = .3;
        JsonObject stuck = f.run("parameters.set", params(Refs.parameter(synth, "cutoff", null), "0.6"));
        assertEquals("unknown", stuck.get("outcome").getAsString());
        assertEquals("readback_mismatch", stuck.get("reason").getAsString());
        cutoff.sticks = null;
        RpcException renamed =
                f.refused("target_changed", "parameters.set", params(Refs.parameter(synth, "cutoff", "Freq"), "0.1"));
        assertEquals("Cutoff", renamed.current.get("name").getAsString());
        assertEquals(
                "parameters.list",
                f.refused("target_not_found", "parameters.set", params(Refs.parameter(synth, "res", null), "0.1"))
                        .next);
        JsonObject expect = params(Refs.parameter(synth, "cutoff", null), "0.1");
        expect.add("expect", JsonParser.parseString("{\"value\":0.9}"));
        f.refused("target_changed", "parameters.set", expect);
        cutoff.writable = false;
        f.refused("invalid_target", "parameters.set", params(Refs.parameter(synth, "cutoff", null), "0.1"));
        assertEquals(2, cutoff.writes, "a refused write sends nothing");
    }

    @Test
    void aBareValueIsWrittenOnceAndAMatchingOneIsNotWritten() {
        Fixture f = new Fixture();
        JsonObject result = f.run("parameters.set", params(Refs.parameter(eq, "gain", "Gain"), "0.75"));
        assertEquals("applied", result.get("outcome").getAsString());
        assertEquals(Refs.parameter(eq, "gain", "Gain"), result.get("ref"));
        JsonObject value = result.getAsJsonObject("fields").getAsJsonObject("value");
        assertEquals(.75, value.get("requested").getAsDouble());
        assertEquals("applied", value.get("outcome").getAsString());
        assertEquals(1, f.eqParameters.writes);
        JsonObject again = f.run("parameters.set", params(Refs.parameter(eq, "gain", null), "0.75"));
        assertEquals("already_matched", again.get("outcome").getAsString());
        assertEquals(1, f.eqParameters.writes, "nothing is sent when the value is already there");
    }

    @Test
    void aWriteNeedsNoListingFirstAndAnotherDevicesListingDoesNotInvalidateIt() {
        Fixture f = new Fixture();
        f.run("parameters.list", list(eq));
        f.run("parameters.list", list(comp));
        JsonObject result = f.run("parameters.set", params(Refs.parameter(eq, "gain", "Gain"), "0.25"));
        assertEquals("applied", result.get("outcome").getAsString());
        assertEquals(.25, f.eqParameters.values.get("gain"), 1e-4);
    }

    @Test
    void aWriteThatTheHostDoesNotShowIsUnknownWithItsReason() {
        Fixture f = new Fixture();
        f.eqParameters.ignoredWrites.add("gain");
        JsonObject lost = f.run("parameters.set", params(Refs.parameter(eq, "gain", null), "0.75"));
        assertEquals("unknown", lost.get("outcome").getAsString());
        assertEquals("not_observed", lost.get("reason").getAsString());
        assertEquals(1, f.eqParameters.writes);
    }

    @Test
    void refsThatNameNothingOrSomethingElseAreStaleAndNothingIsWritten() {
        Fixture f = new Fixture();
        assertEquals(
                "parameters.list",
                f.refused("target_not_found", "parameters.set", params(Refs.parameter(eq, "tone", null), "0.5")).next);
        RpcException renamed =
                f.refused("target_changed", "parameters.set", params(Refs.parameter(eq, "gain", "Tone"), "0.5"));
        assertEquals(Refs.parameter(eq, "gain", "Gain"), renamed.current);
        assertTrue(renamed.getMessage().contains("names \"Tone\"")
                && renamed.getMessage().contains("here is \"Gain\""));
        f.eqNode.child("name").emit("Eq-Three");
        RpcException replaced =
                f.refused("target_changed", "parameters.set", params(Refs.parameter(eq, "gain", null), "0.5"));
        assertEquals(Refs.device(track, 0, "Eq-Three"), replaced.current);
        assertEquals(0, f.eqParameters.writes);
    }

    @Test
    void anExpectedValueThatIsNotThereWritesNothing() {
        Fixture f = new Fixture();
        JsonObject p = params(Refs.parameter(eq, "gain", null), "0.9");
        p.add("expect", HostFixture.json("{\"value\":0.4}"));
        RpcException mismatch = f.refused("target_changed", "parameters.set", p);
        assertEquals(.5, mismatch.observed.get("value").getAsDouble());
        assertEquals(0, f.eqParameters.writes);
        p.add("expect", HostFixture.json("{\"value\":0.5}"));
        assertEquals("applied", f.run("parameters.set", p).get("outcome").getAsString());
    }

    @Test
    void aDisplayIsSolvedAgainstTheHostsOwnTextAndTheOriginalStaysWhenItCannotBe() {
        Fixture f = new Fixture();
        JsonObject result =
                f.run("parameters.set", params(Refs.parameter(eq, "freq", null), "{\"display\":\"800 Hz\"}"));
        assertEquals("applied", result.get("outcome").getAsString());
        JsonObject solved = result.getAsJsonObject("displayWrite");
        assertTrue(solved.get("matched").getAsBoolean());
        assertEquals("800 Hz", solved.get("observedDisplay").getAsString());
        assertEquals("bisection_on_observed_display", solved.get("method").getAsString());
        assertTrue(solved.get("probes").getAsInt() <= DisplaySolver.MAX_PROBES);
        assertEquals("800 Hz", f.eqParameters.displays.get("freq").apply(f.eqParameters.values.get("freq")));
        assertEquals(
                "800 Hz",
                result.getAsJsonObject("fields")
                        .getAsJsonObject("value")
                        .getAsJsonObject("observed")
                        .get("display")
                        .getAsString());
        assertNull(f.eqParameters.observed, "the display observation ends with the request");
        double kept = f.eqParameters.values.get("freq");
        JsonObject far =
                f.run("parameters.set", params(Refs.parameter(eq, "freq", null), "{\"display\":\"900000 Hz\"}"));
        assertEquals("not_applied", far.get("outcome").getAsString());
        assertEquals("readback_mismatch", far.get("reason").getAsString());
        assertEquals(kept, f.eqParameters.values.get("freq"), 1e-4, "the value before the probes is put back");
        assertTrue(far.getAsJsonObject("displayWrite").get("restored").getAsBoolean());
    }

    @Test
    void aDisplayWriteIsAlreadyMatchedWhenTheFirstProbeShowsIt() {
        Fixture f = new Fixture();
        f.eqParameters.values.put("freq", .5);
        f.eqParameters.valueCallback.directParameterNormalizedValueChanged("freq", .5);
        int shown = (int) Math.round(20 * Math.pow(1000, .5));
        JsonObject result =
                f.run("parameters.set", params(Refs.parameter(eq, "freq", null), "{\"display\":\"" + shown + " Hz\"}"));
        assertEquals("already_matched", result.get("outcome").getAsString());
        assertEquals(0, f.eqParameters.writes);
    }

    @Test
    void aParameterWhoseDisplayFallsAsItsValueRisesIsSolvedToo() {
        Fixture f = new Fixture();
        f.eqParameters.displays.put("freq", v -> String.format("%.0f Hz", 20000 - 15000 * v));
        JsonObject result =
                f.run("parameters.set", params(Refs.parameter(eq, "freq", null), "{\"display\":\"8000 Hz\"}"));
        assertEquals("applied", result.get("outcome").getAsString());
        assertEquals("8000 Hz", f.eqParameters.displays.get("freq").apply(f.eqParameters.values.get("freq")));
    }

    @Test
    void aKiloHertzDisplayMeansTheSameFrequencyAsHertz() {
        Fixture f = new Fixture();
        f.eqParameters.displays.put("freq", v -> String.format("%.2f kHz", 0.02 * Math.pow(1000, v)));
        JsonObject result =
                f.run("parameters.set", params(Refs.parameter(eq, "freq", null), "{\"display\":\"800 Hz\"}"));
        assertEquals("applied", result.get("outcome").getAsString());
        assertEquals(
                "0.80 kHz",
                result.getAsJsonObject("displayWrite").get("observedDisplay").getAsString());
    }

    @Test
    void aDisplayThatIsNotANumberInTheParametersUnitIsRefusedBeforeAnyWrite() {
        Fixture f = new Fixture();
        for (String text : List.of("LP 12", "800", "800 ms", "loud", "-6 dB")) {
            f.refused(
                    "unsupported_unit",
                    "parameters.set",
                    params(Refs.parameter(eq, "mode", null), "{\"display\":\"" + text + "\"}"));
        }
        f.refused(
                "unsupported_unit",
                "parameters.set",
                params(Refs.parameter(eq, "freq", null), "{\"display\":\"6 dB\"}"));
        f.refused(
                "unsupported_unit", "parameters.set", params(Refs.parameter(eq, "freq", null), "{\"display\":\"Hz\"}"));
        assertEquals(0, f.eqParameters.writes);
        assertNull(f.eqParameters.observed);
    }

    @Test
    void anUnsupportedUnitRefusalShowsTheParametersValueAndDisplay() {
        Fixture f = new Fixture();
        RpcException error = f.refused(
                "unsupported_unit",
                "parameters.set",
                params(Refs.parameter(eq, "mode", null), "{\"display\":\"800 Hz\"}"));
        assertTrue(error.getMessage().contains("displaying \"LP 24\""), error.getMessage());
    }

    @Test
    void aDisplayWriteNeedsAStoppedTransport() {
        Fixture f = new Fixture();
        f.host.transport.child("isPlaying").emit(true);
        f.refused(
                "unsafe_state", "parameters.set", params(Refs.parameter(eq, "freq", null), "{\"display\":\"800 Hz\"}"));
        assertEquals(0, f.eqParameters.writes);
        JsonObject bare = f.run("parameters.set", params(Refs.parameter(eq, "freq", null), "0.6"));
        assertEquals("applied", bare.get("outcome").getAsString(), "a bare value may be written while playing");
    }

    @Test
    void aDisplayWriteInAChainDeviceAndTheHostNeverShowingADisplayAreAnswered() {
        Fixture f = new Fixture();
        JsonObject chained =
                f.run("parameters.set", params(Refs.parameter(synth, "cutoff", null), "{\"display\":\"800 Hz\"}"));
        assertEquals("applied", chained.get("outcome").getAsString());
        assertEquals(
                "800 Hz",
                chained.getAsJsonObject("displayWrite").get("observedDisplay").getAsString());
        assertEquals("800 Hz", f.synth.params.get("cutoff").display.apply(f.synth.params.get("cutoff").value));
        f.compParameters.displays.clear();
        f.refused("not_ready", "parameters.set", params(Refs.parameter(comp, "ratio", null), "{\"display\":\"2 dB\"}"));
        assertEquals(0, f.compParameters.writes);
        assertNull(f.compParameters.observed, "the observation is released when the request fails");
    }

    @Test
    void aSolveThatTheTargetInterruptsIsUnknownAndSaysWhyAfterTheProbesWereWritten() {
        Fixture f = new Fixture();
        HostOperation op =
                f.start("parameters.set", params(Refs.parameter(eq, "freq", null), "{\"display\":\"800 Hz\"}"));
        f.eqParameters.delayMs = 60;
        while (f.eqParameters.writes < 1) f.host.next();
        f.eqNode.child("name").emit("Eq-Three");
        f.finish(op);
        JsonObject result = op.result.join();
        assertEquals("unknown", result.get("outcome").getAsString());
        assertEquals("target_changed", result.get("reason").getAsString());
        JsonObject solved = result.getAsJsonObject("displayWrite");
        assertFalse(solved.get("matched").getAsBoolean());
        assertEquals("target_changed", solved.get("failure").getAsString());
        assertEquals(1, f.eqParameters.writes, "nothing is written once the target is no longer the one asked for");
    }

    @Test
    void theSameDisplayAgainIsAlreadyMatchedWithoutAWrite() {
        Fixture f = new Fixture();
        JsonObject first =
                f.run("parameters.set", params(Refs.parameter(eq, "freq", null), "{\"display\":\"800 Hz\"}"));
        assertEquals("applied", first.get("outcome").getAsString());
        int writes = f.eqParameters.writes;
        JsonObject again =
                f.run("parameters.set", params(Refs.parameter(eq, "freq", null), "{\"display\":\"800 Hz\"}"));
        assertEquals("already_matched", again.get("outcome").getAsString());
        assertEquals(writes, f.eqParameters.writes, "a display the parameter already shows is not probed");
        assertEquals(0, again.getAsJsonObject("displayWrite").get("probes").getAsInt());
    }

    @Test
    void aDisplayThatTheHostReportsLateIsNeverTakenForTheTextOfTheValueBefore() {
        Fixture f = new Fixture();
        f.eqParameters.delayMs = 120;
        JsonObject result =
                f.run("parameters.set", params(Refs.parameter(eq, "freq", null), "{\"display\":\"800 Hz\"}"));
        assertEquals("applied", result.get("outcome").getAsString());
        String shownNow = f.eqParameters.displays.get("freq").apply(f.eqParameters.values.get("freq"));
        assertEquals("800 Hz", shownNow);
        assertEquals(
                shownNow,
                result.getAsJsonObject("displayWrite").get("observedDisplay").getAsString());
    }

    @Test
    void anEndOfTheScaleThatReadsOffIsOutOfRangeNotAFailure() {
        Fixture f = new Fixture();
        f.eqParameters.values.put("freq", .5);
        f.eqParameters.valueCallback.directParameterNormalizedValueChanged("freq", .5);
        f.eqParameters.displays.put("freq", v -> v < 0.3 ? "Off" : String.format("%.0f Hz", 20 * Math.pow(1000, v)));
        JsonObject result =
                f.run("parameters.set", params(Refs.parameter(eq, "freq", null), "{\"display\":\"300 Hz\"}"));
        assertEquals("applied", result.get("outcome").getAsString());
        assertEquals(
                "300 Hz",
                result.getAsJsonObject("displayWrite").get("observedDisplay").getAsString());
    }

    @Test
    void aTrackVolumeAndAGridParameterAreNotWrittenHere() {
        Fixture f = new Fixture();
        RpcException wrongOwner = assertThrows(
                RpcException.class,
                () -> f.parameters.dispatch(
                        new HostOperation(Long.MAX_VALUE),
                        "parameters.set",
                        params(Refs.parameter(Refs.gridModule(eq, LAYER, "F"), "CUTOFF", null), "0.5")));
        assertEquals("unsupported_scope", wrongOwner.reason.wire());
        assertEquals("grid.apply", wrongOwner.next);
    }
}
