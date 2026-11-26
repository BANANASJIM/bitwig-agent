package dev.bitwigagent.routing;

import com.bitwig.extension.callback.*;
import com.bitwig.extension.controller.api.*;
import com.google.gson.*;
import dev.bitwigagent.bitwig614.Bitwig614RoutingAccess;
import dev.bitwigagent.bitwig614.Bitwig614Runtime;
import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.Holds;
import dev.bitwigagent.core.HostLimits;
import dev.bitwigagent.core.HostModule;
import dev.bitwigagent.core.HostOperation;
import dev.bitwigagent.core.MethodSpecs;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.core.Results;
import dev.bitwigagent.core.SafetyObservation;
import dev.bitwigagent.core.Scheduler;
import dev.bitwigagent.core.ValueMatch;
import dev.bitwigagent.host.DeviceSources;
import dev.bitwigagent.host.NativeRuntime;
import dev.bitwigagent.host.RequestSnapshot;
import dev.bitwigagent.host.TrackRefs;
import dev.bitwigagent.host.TrackWindow;
import dev.bitwigagent.host.TransportSafety;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Track input/output choosers and device sidechain sources through exact version-gated native bindings. Entries
 * address tracks of the observed track bank only; hardware and device-output entries are read as kind "other"
 * and cannot be requested in this version.
 */
public final class RoutingModule implements HostModule {
    static final Set<String> METHODS =
            Set.of("tracks.routing.get", "tracks.routing.set", "devices.sidechain.get", "devices.sidechain.set");
    static final Set<String> TRACK_KINDS =
            Set.of("track_audio_input", "track_note_input", "pre_fader", "post_fader", "track_notes");
    /** Native source-kind group (kCk) that admits each requestable source kind; "none" is always admitted. */
    static final Map<String, String> SOURCE_GROUP = Map.of(
            "pre_fader",
            "TRACK_AUDIO_SOURCES",
            "post_fader",
            "TRACK_AUDIO_SOURCES",
            "track_notes",
            "TRACK_NOTE_SOURCES",
            "all_ins",
            "HARDWARE_NOTE_SOURCES",
            "computer_keyboard",
            "HARDWARE_NOTE_SOURCES",
            "onscreen_keyboard",
            "HARDWARE_NOTE_SOURCES");
    /** The output chooser (Bitwig's bhF) enables every destination group for every track; v1 exposes the two track groups. */
    public static final Map<String, String> OUTPUT_GROUP =
            Map.of("track_audio_input", "TRACK_AUDIO_DESTINATIONS", "track_note_input", "TRACK_NOTE_DESTINATIONS");
    /** Public track types whose GUI shows an input chooser, with the native track kind (fP) each must report. */
    public static final Map<String, String> INPUT_TRACK_TYPES =
            Map.of("Audio", "AUDIO", "Instrument", "INSTRUMENT", "Hybrid", "HYBRID");

    public static final int MAX_COMPONENTS = 256;

    public interface Access {
        void execute(Runnable work);

        JsonObject trackGet(TrackRef self, List<TrackRef> bank, boolean includeChoices, Runnable check);

        JsonObject trackSet(
                TrackRef self, List<TrackRef> bank, JsonObject params, Runnable check, Runnable beforeWrite);

        JsonObject sidechainGet(
                DeviceSources.Source device, List<TrackRef> bank, boolean includeChoices, Runnable check);

        JsonObject sidechainSet(
                DeviceSources.Source device,
                List<TrackRef> bank,
                JsonObject params,
                Runnable check,
                Runnable beforeWrite);
    }

    /** One observed bank track: public UUID, name, type and its proxy (the native side re-resolves and re-checks it). */
    public static final class TrackRef {
        public final String id, name, type;
        public final Object proxy;

        TrackRef(String id, String name, String type, Object proxy) {
            this.id = id;
            this.name = name;
            this.type = type;
            this.proxy = proxy;
        }
    }

    private final Scheduler scheduler;
    private final TrackWindow window;
    private final TrackRefs trackRefs;
    private final DeviceSources sources;
    private final TransportSafety transportSafety;
    private final NativeRuntime natives;
    private final Holds holds;
    private final MethodSpecs specs;
    private final AtomicLong epoch = new AtomicLong();
    private Access access;
    private static final String PENDING = "routing.pending", QUARANTINE = "routing.quarantine";
    private static final String HOLD = "A routing or sidechain write is pending or unresolved.";

    public RoutingModule(
            Scheduler scheduler,
            TrackWindow window,
            TrackRefs trackRefs,
            DeviceSources sources,
            TransportSafety transportSafety,
            NativeRuntime natives,
            Holds holds,
            MethodSpecs specs) {
        this(scheduler, window, trackRefs, sources, transportSafety, natives, holds, specs, null);
    }

    public RoutingModule(
            Scheduler scheduler,
            TrackWindow window,
            TrackRefs trackRefs,
            DeviceSources sources,
            TransportSafety transportSafety,
            NativeRuntime natives,
            Holds holds,
            MethodSpecs specs,
            Access access) {
        this.scheduler = scheduler;
        this.window = window;
        this.trackRefs = trackRefs;
        this.sources = sources;
        this.transportSafety = transportSafety;
        this.natives = natives;
        this.holds = holds;
        this.specs = specs;
        this.access = access;
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
        validateParams(method, p);
    }

    /**
     * What the schema cannot say: a routing write changes the output or the input, an expect compares only those, and
     * an entry has a trackId exactly for the track kinds and a name exactly for kind other.
     */
    static void validateParams(String method, JsonObject p) {
        if (method.equals("tracks.routing.get") || method.equals("devices.sidechain.get")) return;
        ValueMatch.requireNamed(p);
        if (method.equals("tracks.routing.set") && !p.has("output") && !p.has("input"))
            throw Errors.invalid("Supply output, input or both.");
        JsonObject expect = p.has("expect") ? p.getAsJsonObject("expect") : new JsonObject();
        for (String key : expect.keySet())
            if (!p.has(key)) throw Errors.invalid("expect." + key + " compares a field this call does not write.");
        for (String key : List.of("output", "input", "source")) if (p.has(key)) entry(p.getAsJsonObject(key), key);
        for (String key : expect.keySet()) entry(expect.getAsJsonObject(key), "expect." + key);
    }

    private static void entry(JsonObject entry, String where) {
        String kind = entry.get("kind").getAsString();
        boolean track = TRACK_KINDS.contains(kind), other = kind.equals("other");
        if (entry.has("trackId") != track)
            throw Errors.invalid(
                    where + ": trackId is required for " + new TreeSet<>(TRACK_KINDS) + " and not allowed otherwise.");
        if (entry.has("name") != other)
            throw Errors.invalid(where + ": name is required for kind other and only then.");
        if (entry.has("nativeType") && !other) throw Errors.invalid(where + ": nativeType is only for kind other.");
    }

    /** v1 requestable source kinds admitted by a native kCk group set (always including none). */
    public static List<String> sourceKinds(Collection<String> nativeGroups) {
        List<String> out = new ArrayList<>(List.of("none"));
        for (String kind :
                List.of("pre_fader", "post_fader", "track_notes", "all_ins", "computer_keyboard", "onscreen_keyboard"))
            if (nativeGroups.contains(SOURCE_GROUP.get(kind))) out.add(kind);
        return out;
    }

    /** The input chooser's native kCk groups for a native track kind, as Bitwig's bhB.LzO derives them. */
    public static List<String> trackSourceGroups(String nativeTrackKind) {
        switch (nativeTrackKind) {
            case "AUDIO":
                return List.of("HARDWARE_AUDIO_SOURCES", "TRACK_AUDIO_SOURCES");
            case "INSTRUMENT":
                return List.of("HARDWARE_NOTE_SOURCES", "TRACK_NOTE_SOURCES");
            case "HYBRID":
                return List.of(
                        "HARDWARE_NOTE_SOURCES", "HARDWARE_AUDIO_SOURCES", "TRACK_AUDIO_SOURCES", "TRACK_NOTE_SOURCES");
            default:
                return List.of();
        }
    }

    /** Compare-and-set: same kind, and the same trackId (track kinds) or exact name (kind other). */
    public static boolean sameEntry(JsonObject expected, JsonObject observed) {
        String kind = expected.get("kind").getAsString();
        if (!kind.equals(observed.get("kind").getAsString())) return false;
        if (TRACK_KINDS.contains(kind))
            return observed.has("trackId")
                    && expected.get("trackId")
                            .getAsString()
                            .equalsIgnoreCase(observed.get("trackId").getAsString());
        if (kind.equals("other"))
            return observed.has("name")
                    && observed.get("name").isJsonPrimitive()
                    && expected.get("name")
                            .getAsString()
                            .equals(observed.get("name").getAsString())
                    && (!expected.has("nativeType")
                            || observed.has("nativeType")
                                    && expected.get("nativeType")
                                            .getAsString()
                                            .equals(observed.get("nativeType").getAsString()));
        return true;
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
            if (!p.has(key) || !p.getAsJsonObject(key).has("trackId")) continue;
            String other = p.getAsJsonObject(key).get("trackId").getAsString();
            if (bank.stream().noneMatch(track -> track.id.equals(other)))
                throw Errors.notApplied(
                        Reason.OUTSIDE_WINDOW,
                        key + ".trackId is absent from the current " + HostLimits.TRACKS
                                + "-track window; nothing changed.");
        }
        SafetyObservation safety = write ? transportSafety.stopped(op) : null;
        if (access == null) {
            try {
                access = new Bitwig614RoutingAccess(natives.get(op).access());
            } catch (ReflectiveOperationException | LinkageError error) {
                throw Errors.notApplied(
                        Reason.NATIVE_UNAVAILABLE,
                        "Exact routing descriptors unavailable: " + Bitwig614Runtime.diagnostic(error));
            }
        }
        long generation = epoch.get();
        Runnable check = () -> {
            scheduler.check(op);
            if (epoch.get() != generation)
                throw Errors.notApplied(
                        Reason.TARGET_CHANGED, "Track bank changed while the routing operation was queued.");
            if (snapshot != null) snapshot.require(op);
            if (write) transportSafety.guardStopped(safety);
        };
        Runnable beforeWrite = () -> {
            check.run();
            op.markDispatched();
            holds.place(QUARANTINE, HOLD);
        };
        Access bridge = access;
        TrackRef target = self;
        boolean choices = p.has("includeChoices") && p.get("includeChoices").getAsBoolean();
        holds.inFlight(PENDING, HOLD);
        try {
            bridge.execute(() -> {
                try {
                    check.run();
                    JsonObject result;
                    switch (method) {
                        case "tracks.routing.get":
                            result = bridge.trackGet(target, bank, choices, check);
                            break;
                        case "tracks.routing.set":
                            result = bridge.trackSet(target, bank, p, check, beforeWrite);
                            break;
                        case "devices.sidechain.get":
                            result = bridge.sidechainGet(device, bank, choices, check);
                            break;
                        default:
                            result = bridge.sidechainSet(device, bank, p, check, beforeWrite);
                    }
                    scheduler.schedule(
                            op,
                            () -> {
                                check.run();
                                if (!sidechain && !window.trackMatches(id, window.at(slot)))
                                    throw Errors.notApplied(
                                            Reason.TARGET_CHANGED, "Track target changed after native access.");
                                if (write && Results.outcomeOf(result).succeeded()) holds.release(QUARANTINE);
                                holds.release(PENDING);
                                annotateTrackNames(result, trackNames());
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

    /** Native names of track buses are blank or generic ("Pre-Fader"): next to every {kind, trackId} entry put the track's public
     *  name as a sibling (entry -> trackName, source -> sourceTrackName, ...). Entries themselves stay verbatim, copyable into expected*. */
    static void annotateTrackNames(JsonElement element, Map<String, String> names) {
        if (element == null) return;
        if (element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) annotateTrackNames(item, names);
            return;
        }
        if (!element.isJsonObject()) return;
        JsonObject object = element.getAsJsonObject();
        Map<String, String> add = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> field : object.entrySet()) {
            JsonElement value = field.getValue();
            if (value.isJsonObject()
                    && value.getAsJsonObject().has("kind")
                    && value.getAsJsonObject().has("trackId")) {
                String name = names.get(value.getAsJsonObject().get("trackId").getAsString());
                if (name != null)
                    add.put(field.getKey().equals("entry") ? "trackName" : field.getKey() + "TrackName", name);
            } else annotateTrackNames(value, names);
        }
        for (Map.Entry<String, String> extra : add.entrySet())
            if (!object.has(extra.getKey())) object.addProperty(extra.getKey(), extra.getValue());
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
