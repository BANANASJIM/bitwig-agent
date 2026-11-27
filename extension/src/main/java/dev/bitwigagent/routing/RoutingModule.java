package dev.bitwigagent.routing;

import com.bitwig.extension.callback.*;
import com.bitwig.extension.controller.api.*;
import com.google.gson.*;
import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.Holds;
import dev.bitwigagent.core.HostLimits;
import dev.bitwigagent.core.HostModule;
import dev.bitwigagent.core.HostOperation;
import dev.bitwigagent.core.MethodSpecs;
import dev.bitwigagent.core.PortHandle;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.core.Results;
import dev.bitwigagent.core.Scheduler;
import dev.bitwigagent.core.SharedData;
import dev.bitwigagent.host.DeviceSources;
import dev.bitwigagent.host.RequestSnapshot;
import dev.bitwigagent.host.TrackRefs;
import dev.bitwigagent.host.TrackWindow;
import dev.bitwigagent.host.TransportSafety;
import dev.bitwigagent.routing.RoutingPort.TrackRef;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Track input/output choosers and device sidechain sources through the {@link RoutingPort}: validation, bank and
 * device targets, the declared transport requirement and the pending/quarantine holds. The domain rules and result
 * shapes are {@link RoutingModel}'s; entries address tracks of the observed track bank and hardware inputs by id.
 */
public final class RoutingModule implements HostModule {
    static final Set<String> METHODS =
            Set.of("tracks.routing.get", "tracks.routing.set", "devices.sidechain.get", "devices.sidechain.set");

    private final Scheduler scheduler;
    private final TrackWindow window;
    private final TrackRefs trackRefs;
    private final DeviceSources sources;
    private final TransportSafety transportSafety;
    private final PortHandle<RoutingPort> access;
    private final Holds holds;
    private final MethodSpecs specs;
    private final AtomicLong epoch = new AtomicLong();
    private static final String PENDING = "routing.pending", QUARANTINE = "routing.quarantine";
    private static final String HOLD = "A routing or sidechain write is pending or unresolved.";

    public RoutingModule(
            Scheduler scheduler,
            TrackWindow window,
            TrackRefs trackRefs,
            DeviceSources sources,
            TransportSafety transportSafety,
            PortHandle<RoutingPort> access,
            Holds holds,
            MethodSpecs specs) {
        this.scheduler = scheduler;
        this.window = window;
        this.trackRefs = trackRefs;
        this.sources = sources;
        this.transportSafety = transportSafety;
        this.access = access;
        this.holds = holds;
        this.specs = specs;
        for (int i = 0; i < HostLimits.TRACKS; i++) {
            Track track = window.at(i);
            track.channelId().addValueObserver((StringValueChangedCallback) v -> epoch.incrementAndGet());
            track.exists().addValueObserver((BooleanValueChangedCallback) v -> epoch.incrementAndGet());
            track.trackType().addValueObserver((StringValueChangedCallback) v -> epoch.incrementAndGet());
        }
    }

    @Override
    public Set<String> methods() {
        return METHODS;
    }

    @Override
    public void validate(String method, JsonObject p) {
        RoutingModel.validateParams(method, p);
    }

    @Override
    public void dispatch(HostOperation op, String method, JsonObject p) {
        scheduler.check(op);
        boolean write = !specs.get(method).readOnly;
        if (holds.held(PENDING)) throw Errors.notApplied(Reason.BUSY, "Another native routing operation is pending.");
        if (write && holds.held(QUARANTINE))
            throw holds.blocked(
                    QUARANTINE,
                    "An unresolved routing/sidechain write blocks further routing writes.",
                    "Inspect tracks.routing.get / devices.sidechain.get");
        JsonObject ref = p.getAsJsonObject("target");
        boolean sidechain = method.startsWith("devices.");
        DeviceSources.Source device = sidechain ? device(ref, write) : null;
        RequestSnapshot snapshot = sidechain ? RequestSnapshot.of(device) : null;
        String id = sidechain ? null : ref.get("id").getAsString();
        int slot = sidechain ? -1 : trackRefs.resolve(ref, write);
        List<TrackRef> bank = bank();
        TrackRef self = null;
        for (TrackRef track : bank) if (track.id.equals(id)) self = track;
        if (!sidechain && self == null)
            throw Errors.notApplied(
                    Reason.OUTSIDE_WINDOW,
                    "Track UUID is absent from the current " + HostLimits.TRACKS + "-track window.");
        for (String key : List.of("output", "input", "source")) {
            if (!p.has(key) || !p.getAsJsonObject(key).has("track")) continue;
            String other =
                    p.getAsJsonObject(key).getAsJsonObject("track").get("id").getAsString();
            if (bank.stream().noneMatch(track -> track.id.equals(other)))
                throw Errors.notApplied(
                        Reason.OUTSIDE_WINDOW,
                        key + ".track is absent from the current " + HostLimits.TRACKS
                                + "-track window; nothing changed.");
        }
        Runnable safety = () -> transportSafety.recheck(op);
        RoutingPort bridge = access.get(op);
        long generation = epoch.get();
        Runnable check = () -> {
            scheduler.check(op);
            if (epoch.get() != generation)
                throw Errors.notApplied(
                        Reason.TARGET_CHANGED, "Track bank changed while the routing operation was queued.");
            if (snapshot != null) snapshot.require(op);
            if (write) safety.run();
        };
        Runnable beforeWrite = () -> {
            check.run();
            op.markDispatched();
            holds.place(QUARANTINE, HOLD);
        };
        TrackRef target = self;
        boolean choices = p.has("includeChoices") && p.get("includeChoices").getAsBoolean();
        holds.inFlight(PENDING, HOLD);
        try {
            bridge.execute(() -> {
                try {
                    check.run();
                    JsonObject result = run(bridge, method, p, target, device, bank, choices, check, beforeWrite);
                    bridge.provenance().write(result);
                    scheduler.schedule(
                            op,
                            () -> {
                                check.run();
                                if (!sidechain && !window.trackMatches(id, window.at(slot)))
                                    throw Errors.notApplied(
                                            Reason.TARGET_CHANGED, "Track target changed after native access.");
                                if (write && Results.outcomeOf(result).succeeded()) holds.release(QUARANTINE);
                                holds.release(PENDING);
                                nameTracks(result, trackNames());
                                if (method.equals("devices.sidechain.get")) hintWhenEmpty(result);
                                if (!sidechain) result.add("ref", trackRefs.ref(slot));
                                else if (write)
                                    result.add(
                                            "ref",
                                            result.getAsJsonObject("component").get("ref"));
                                op.result.complete(result);
                            },
                            0);
                } catch (RuntimeException | Error error) {
                    holds.release(PENDING);
                    op.fail(error);
                }
            });
        } catch (RuntimeException | Error error) {
            holds.release(PENDING);
            throw error;
        }
        op.result.whenComplete((r, e) -> holds.release(PENDING));
    }

    /** One routing call on the document thread: read, or plan, write and read back through the port. */
    private static JsonObject run(
            RoutingPort port,
            String method,
            JsonObject p,
            TrackRef target,
            DeviceSources.Source device,
            List<TrackRef> bank,
            boolean choices,
            Runnable check,
            Runnable beforeWrite) {
        switch (method) {
            case "tracks.routing.get":
                return RoutingModel.track(port.readTrack(target, bank, choices, check), choices);
            case "tracks.routing.set": {
                RoutingPort.Edit<RoutingPort.TrackRouting> edit = port.editTrack(
                        target, bank, seen -> RoutingModel.planTrack(p, target.id, seen), check, beforeWrite);
                if (edit.rejected != null) throw RoutingModel.rejected(edit.rejected, p.getAsJsonObject(edit.rejected));
                Map<String, RoutingEntry> before = new LinkedHashMap<>(), after = new LinkedHashMap<>();
                for (String key : List.of("output", "input")) {
                    if (!p.has(key)) continue;
                    before.put(key, chooser(edit.before, key).current);
                    after.put(key, chooser(edit.after, key).current);
                }
                return RoutingModel.finish(RoutingModel.track(edit.after, false), p, before, after, edit.change);
            }
            case "devices.sidechain.get":
                return RoutingModel.sidechains(
                        device.ref(), port.readSidechains(device, bank, choices, check), choices);
            default: {
                int index = p.getAsJsonObject("target").get("index").getAsInt();
                RoutingPort.Edit<RoutingPort.Component> edit = port.editSidechain(
                        device, bank, index, c -> RoutingModel.planSidechain(p, device.ref(), c), check, beforeWrite);
                if (edit.rejected != null) throw RoutingModel.rejected("source", p.getAsJsonObject("source"));
                JsonObject out = new JsonObject();
                out.add("component", RoutingModel.component(device.ref(), edit.after, false));
                return RoutingModel.finish(
                        out,
                        p,
                        Map.of("source", edit.before.chooser.current),
                        Map.of("source", edit.after.chooser.current),
                        edit.change);
            }
        }
    }

    private static RoutingPort.Chooser chooser(RoutingPort.TrackRouting routing, String key) {
        return key.equals("output") ? routing.output : routing.input;
    }

    /** The top-level device a ref names (the sidechain ref's device for a write); devices inside chains are not read. */
    private DeviceSources.Source device(JsonObject ref, boolean write) {
        JsonObject deviceRef = write ? ref.getAsJsonObject("device") : ref;
        if (deviceRef.has("chain"))
            throw Errors.notApplied(
                    Reason.UNSUPPORTED_SCOPE, "Sidechains of devices inside a chain or layer are not covered.");
        return sources.device(deviceRef, !write);
    }

    private Map<String, String> trackNames() {
        Map<String, String> out = new HashMap<>();
        for (int i = 0; i < HostLimits.TRACKS; i++) {
            Track track = window.at(i);
            if (track.exists().get())
                out.put(track.channelId().get(), track.name().get());
        }
        return out;
    }

    static final String NO_SIDECHAIN_HINT = noSidechainHint(SharedData.read("sidechain"));

    static String noSidechainHint(JsonObject shared) {
        return "This device has no sidechain input. Stock devices with an audio sidechain input: "
                + join(shared.getAsJsonArray("audioSidechainDevices")) + " (the plain "
                + join(shared.getAsJsonArray("withoutSidechain"))
                + " has none). Insert one of them, then read this again.";
    }

    private static String join(JsonArray names) {
        List<String> out = new ArrayList<>();
        for (JsonElement name : names) out.add(name.getAsString());
        return String.join(", ", out);
    }

    /** A device that reports no component gets the names of the stock devices that have an audio sidechain. */
    static void hintWhenEmpty(JsonObject result) {
        JsonElement components = result.get("components");
        if (components != null
                && components.isJsonArray()
                && components.getAsJsonArray().size() == 0) result.addProperty("hint", NO_SIDECHAIN_HINT);
    }

    /** Native names of track buses are blank or generic ("Pre-Fader"): every track ref {kind, id} in an entry gets the
     *  track's public name, so the entry is copyable into a write. */
    static void nameTracks(JsonElement element, Map<String, String> names) {
        if (element.isJsonArray()) for (JsonElement item : element.getAsJsonArray()) nameTracks(item, names);
        if (!element.isJsonObject()) return;
        JsonObject object = element.getAsJsonObject();
        if (object.has("kind") && "track".equals(object.get("kind").getAsString()) && !object.has("name")) {
            String name = names.get(object.get("id").getAsString());
            if (name != null) object.addProperty("name", name);
            return;
        }
        for (Map.Entry<String, JsonElement> field : object.entrySet()) nameTracks(field.getValue(), names);
    }

    private List<TrackRef> bank() {
        List<TrackRef> out = new ArrayList<>();
        for (int i = 0; i < HostLimits.TRACKS; i++) {
            Track track = window.at(i);
            if (!track.exists().get()) continue;
            String id = track.channelId().get();
            if (id == null || id.isEmpty()) continue;
            out.add(new TrackRef(id, track.name().get(), track.trackType().get(), track));
        }
        return out;
    }
}
