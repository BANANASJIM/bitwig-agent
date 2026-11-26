package dev.bitwigagent;

import com.bitwig.extension.callback.*;
import com.bitwig.extension.controller.api.*;
import com.google.gson.*;
import dev.bitwigagent.core.MethodSpecs;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Track input/output choosers and device sidechain sources through exact version-gated native bindings. Entries
 * address tracks of the observed track bank only; hardware and device-output entries are read as kind "other"
 * and cannot be requested in this version.
 */
final class RoutingModule implements HostModule {
    static final Set<String> METHODS =
            Set.of("tracks.routing.get", "tracks.routing.set", "devices.sidechain.get", "devices.sidechain.set");
    static final Set<String> OUTPUT_KINDS = Set.of("none", "track_audio_input", "track_note_input");
    static final Set<String> SOURCE_KINDS = Set.of(
            "none", "pre_fader", "post_fader", "track_notes", "all_ins", "computer_keyboard", "onscreen_keyboard");
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
    static final Map<String, String> OUTPUT_GROUP =
            Map.of("track_audio_input", "TRACK_AUDIO_DESTINATIONS", "track_note_input", "TRACK_NOTE_DESTINATIONS");
    /** Public track types whose GUI shows an input chooser, with the native track kind (fP) each must report. */
    static final Map<String, String> INPUT_TRACK_TYPES =
            Map.of("Audio", "AUDIO", "Instrument", "INSTRUMENT", "Hybrid", "HYBRID");

    static final int MAX_COMPONENTS = 256;

    interface Access {
        void execute(Runnable work);

        JsonObject trackGet(TrackRef self, List<TrackRef> bank, boolean includeChoices, Runnable check);

        JsonObject trackSet(
                TrackRef self, List<TrackRef> bank, JsonObject params, Runnable check, Runnable beforeWrite);

        JsonObject sidechainGet(
                Object deviceProxy, String trackId, List<TrackRef> bank, boolean includeChoices, Runnable check);

        JsonObject sidechainSet(
                Object deviceProxy,
                String trackId,
                List<TrackRef> bank,
                JsonObject params,
                Runnable check,
                Runnable beforeWrite);
    }

    /** One observed bank track: public UUID, public type and its proxy (the native side re-resolves and re-checks it). */
    static final class TrackRef {
        final String id, type;
        final Object proxy;

        TrackRef(String id, String type, Object proxy) {
            this.id = id;
            this.type = type;
            this.proxy = proxy;
        }
    }

    private final HostServices s;
    private final MethodSpecs specs;
    private final AtomicLong epoch = new AtomicLong();
    private Access access;
    private volatile boolean pending, quarantined;

    RoutingModule(HostServices s, MethodSpecs specs) {
        this(s, specs, null);
    }

    RoutingModule(HostServices s, MethodSpecs specs, Access access) {
        this.s = s;
        this.specs = specs;
        this.access = access;
        for (int i = 0; i < HostServices.TRACKS; i++) {
            Track track = (Track) s.tracks.getItemAt(i);
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
    public List<String> unresolved() {
        return pending || quarantined ? List.of("A routing or sidechain write is pending or unresolved.") : List.of();
    }

    @Override
    public void validate(String method, JsonObject p) {
        validateParams(method, p);
    }

    static void validateParams(String method, JsonObject p) {
        switch (method) {
            case "tracks.routing.get":
                Params.fields(p, Set.of("trackId"), Set.of("includeChoices"));
                if (p.has("includeChoices")) Params.bool(p, "includeChoices");
                break;
            case "tracks.routing.set": {
                Params.fields(p, Set.of("trackId"), Set.of("output", "input", "expectedOutput", "expectedInput"));
                if (!p.has("output") && !p.has("input")) throw Errors.invalid("Supply output, input or both.");
                pair(p, "output", "expectedOutput", OUTPUT_KINDS);
                pair(p, "input", "expectedInput", SOURCE_KINDS);
                break;
            }
            case "devices.sidechain.get":
                Params.fields(p, Set.of("trackId", "deviceIndex", "expectedDeviceName"), Set.of("includeChoices"));
                device(p);
                if (p.has("includeChoices")) Params.bool(p, "includeChoices");
                break;
            case "devices.sidechain.set":
                Params.fields(
                        p,
                        Set.of("trackId", "deviceIndex", "expectedDeviceName", "source", "expectedSource"),
                        Set.of(
                                "componentIndex",
                                "expectedComponentId",
                                "componentId",
                                "expectedModulatorId",
                                "expectedModuleId"));
                device(p);
                if (p.has("componentIndex") == p.has("componentId"))
                    throw Errors.invalid("Supply exactly one of componentIndex or componentId.");
                if (p.has("componentIndex") != p.has("expectedComponentId"))
                    throw Errors.invalid(
                            "componentIndex needs expectedComponentId (that row's componentId), and only it.");
                if (p.has("componentIndex")) {
                    Params.integer(p, "componentIndex", 0, MAX_COMPONENTS - 1);
                    Params.string(p, "expectedComponentId");
                } else Params.string(p, "componentId");
                for (String owner : List.of("expectedModulatorId", "expectedModuleId"))
                    if (p.has(owner)) uuid(p, owner);
                entry(p, "source", SOURCE_KINDS, false);
                entry(p, "expectedSource", SOURCE_KINDS, true);
                break;
            default:
                throw Errors.notApplied(ErrorCode.UNKNOWN_METHOD, "Unknown routing method.");
        }
        uuid(p, "trackId");
    }

    private static void device(JsonObject p) {
        Params.integer(p, "deviceIndex", 0, HostServices.DEVICES - 1);
        Params.string(p, "expectedDeviceName");
    }

    /** A requested field needs its expected (compare-and-set) partner and vice versa. */
    private static void pair(JsonObject p, String key, String expected, Set<String> kinds) {
        if (p.has(key) != p.has(expected))
            throw Errors.invalid(key + " and " + expected + " must be supplied together (" + expected
                    + " from a fresh tracks.routing.get).");
        if (!p.has(key)) return;
        entry(p, key, kinds, false);
        entry(p, expected, kinds, true);
    }

    /**
     * {kind, trackId?}: trackId exactly for track kinds. Kind "other" (only in an expected entry, copied from a get)
     * carries its exact name and optionally nativeType.
     */
    static JsonObject entry(JsonObject p, String key, Set<String> kinds, boolean expected) {
        JsonElement value = p.get(key);
        if (value == null || !value.isJsonObject()) throw Errors.invalid(key + " must be an object {kind, trackId?}.");
        JsonObject e = value.getAsJsonObject();
        String kind = e.has("kind")
                        && e.get("kind").isJsonPrimitive()
                        && e.get("kind").getAsJsonPrimitive().isString()
                ? e.get("kind").getAsString()
                : "";
        boolean other = expected && kind.equals("other");
        if (!kinds.contains(kind) && !other)
            throw Errors.invalid(key + ".kind must be one of " + new TreeSet<>(kinds) + (expected ? " or other" : ""));
        Set<String> required = new HashSet<>(Set.of("kind"));
        if (TRACK_KINDS.contains(kind)) required.add("trackId");
        if (other) required.add("name");
        Params.fields(e, required, other ? Set.of("nativeType") : Set.of());
        if (e.has("trackId")) uuid(e, "trackId");
        if (e.has("name")) {
            JsonElement name = e.get("name");
            // A native bus name may be empty; it is compared exactly.
            if (!name.isJsonPrimitive()
                    || !name.getAsJsonPrimitive().isString()
                    || name.getAsString().length() > 4096)
                throw Errors.invalid(key + ".name must be a string (maximum 4096 characters).");
        }
        if (e.has("nativeType")) Params.string(e, "nativeType");
        return e;
    }

    private static void uuid(JsonObject p, String key) {
        if (!DeviceSources.canonicalUuid(Params.string(p, key)))
            throw Errors.invalid(key + " must be a canonical UUID");
    }

    /** v1 requestable source kinds admitted by a native kCk group set (always including none). */
    static List<String> sourceKinds(Collection<String> nativeGroups) {
        List<String> out = new ArrayList<>(List.of("none"));
        for (String kind :
                List.of("pre_fader", "post_fader", "track_notes", "all_ins", "computer_keyboard", "onscreen_keyboard"))
            if (nativeGroups.contains(SOURCE_GROUP.get(kind))) out.add(kind);
        return out;
    }

    /** The input chooser's native kCk groups for a native track kind, as Bitwig's bhB.LzO derives them. */
    static List<String> trackSourceGroups(String nativeTrackKind) {
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
    static boolean sameEntry(JsonObject expected, JsonObject observed) {
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
        s.check(op);
        boolean write = !specs.get(method).readOnly;
        if (pending) throw Errors.notApplied(ErrorCode.BUSY, "Another native routing operation is pending.");
        if (write && quarantined)
            throw Errors.notApplied(
                    ErrorCode.UNRESOLVED_MUTATION,
                    "An unresolved routing/sidechain write requires inspection (tracks.routing.get / devices.sidechain.get) and extension.restart before further routing writes.");
        String id = p.get("trackId").getAsString();
        int slot = s.trackSlot(id);
        List<TrackRef> bank = bank();
        TrackRef self = null;
        for (TrackRef ref : bank) if (ref.id.equals(id)) self = ref;
        if (self == null)
            throw Errors.notApplied(
                    ErrorCode.OUTSIDE_WINDOW,
                    "Track UUID is absent from the current " + HostServices.TRACKS + "-track window.");
        for (String key : List.of("output", "input", "source")) {
            if (!p.has(key) || !p.getAsJsonObject(key).has("trackId")) continue;
            String other = p.getAsJsonObject(key).get("trackId").getAsString();
            if (bank.stream().noneMatch(ref -> ref.id.equals(other)))
                throw Errors.notApplied(
                        ErrorCode.OUTSIDE_WINDOW,
                        key + ".trackId is absent from the current " + HostServices.TRACKS
                                + "-track window; nothing was dispatched.");
        }
        DeviceSources.RootReadTicket ticket = method.startsWith("devices.") ? s.sources.rootRead(parent(p)) : null;
        HostServices.SafetyObservation safety = write ? s.stoppedSafety(op) : null;
        if (access == null) {
            try {
                access = new Bitwig614RoutingAccess(s.nativeRuntime(op).access(ErrorCode.ROUTING_UNAVAILABLE));
            } catch (ReflectiveOperationException | LinkageError error) {
                throw Errors.notApplied(
                        ErrorCode.ROUTING_UNAVAILABLE,
                        "Exact routing descriptors unavailable: " + Bitwig614Runtime.diagnostic(error));
            }
        }
        long generation = epoch.get();
        Runnable check = () -> {
            s.check(op);
            if (epoch.get() != generation)
                throw Errors.notApplied(
                        ErrorCode.STALE_CONTEXT, "Track bank changed while the routing operation was queued.");
            if (ticket != null) ticket.check();
            if (write) s.guardStoppedSafety(safety);
        };
        Runnable beforeWrite = () -> {
            check.run();
            op.dispatched = true;
            quarantined = true;
        };
        Access bridge = access;
        TrackRef target = self;
        boolean choices = p.has("includeChoices") && p.get("includeChoices").getAsBoolean();
        pending = true;
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
                            result = bridge.sidechainGet(ticket.device, id, bank, choices, check);
                            break;
                        default:
                            result = bridge.sidechainSet(ticket.device, id, bank, p, check, beforeWrite);
                    }
                    s.schedule(
                            op,
                            () -> {
                                check.run();
                                if (!s.trackMatches(id, (Track) s.tracks.getItemAt(slot)))
                                    throw Errors.notApplied(
                                            ErrorCode.STALE_CONTEXT, "Track target changed after native access.");
                                if (ticket != null) s.sources.guardRootRead(ticket);
                                if (write && !"unknown".equals(text(result, "completion"))) quarantined = false;
                                pending = false;
                                annotateTrackNames(result, trackNames());
                                op.result.complete(result);
                            },
                            0);
                } catch (RuntimeException | Error error) {
                    pending = false;
                    op.fail(error);
                }
            });
        } catch (RuntimeException | Error error) {
            pending = false;
            throw error;
        }
        op.result.whenComplete((r, e) -> pending = false);
    }

    private Map<String, String> trackNames() {
        Map<String, String> out = new HashMap<>();
        for (int i = 0; i < HostServices.TRACKS; i++) {
            Track track = (Track) s.tracks.getItemAt(i);
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
        for (int i = 0; i < HostServices.TRACKS; i++) {
            Track track = (Track) s.tracks.getItemAt(i);
            if (!track.exists().get()) continue;
            String id = track.channelId().get();
            if (id == null || id.isEmpty()) continue;
            out.add(new TrackRef(id, track.trackType().get(), track));
        }
        return out;
    }

    private static JsonObject parent(JsonObject p) {
        JsonObject parent = new JsonObject();
        for (String key : List.of("trackId", "deviceIndex", "expectedDeviceName")) parent.add(key, p.get(key));
        return parent;
    }

    private static String text(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : null;
    }
}
