package dev.bitwigagent;

import static dev.bitwigagent.HostServices.failure;

import com.google.gson.*;
import java.lang.reflect.Method;
import java.util.*;
import java.util.stream.Stream;

/**
 * Exact 6.1.4 bindings for the routing choosers behind Bitwig's own GUI menus (bhe/bhg outputs, bhv/bhy inputs and
 * sidechains). Output: channel {@code ynF.OnS()} destination selector {@code j2s}, set {@code j2s.IwV(twn)} ("Change
 * Routing Destination"), clear {@code j2s.wGc()}, accept check {@code j2s.MRY(Object)}. Input and sidechain: source
 * selector {@code lbA} ({@code ynF.ADn()} or a routing component's {@code GjM.eej()}), set {@code lbA.cfM(qy)}
 * ("Change Routing Source"), clear {@code lbA.wGc()}, accept check {@code lbA.MRY(Object)}. Both setters silently ignore
 * a cyclic or unchanged value and do not check audio vs note, so kinds are enforced here and every write is read back
 * by identity. Each write runs inside one project transaction {@code g2L.cfM(label,false)} … {@code g2L.cfM(false)}, as
 * the GUI wraps it. No UI selection is read or changed.
 */
final class Bitwig614RoutingAccess implements RoutingModule.Access {
    private static final String M = "com.bitwig.flt.document.core.master.",
            I = "com.bitwig.flt.document.core.iface.",
            R = "com.bitwig.ramona.core.",
            D = M + "device.";
    private final Bitwig614Runtime.Access a;
    private final NativeReadContext context;
    private final NativeDeviceTarget targets;
    private final Class<?> trackType,
            groupIface,
            channelType,
            projectType,
            portsType,
            destinationSelector,
            sourceSelector;
    private final Class<?> audioDestination, noteDestination, preFader, postFader, trackNotes, sourceBus;
    private final Class<?> componentType,
            audioSidechain,
            noteSidechain,
            pluginSidechain,
            audioIn,
            deviceType,
            modulatorType,
            moduleType;
    private final Method target,
            channel,
            alias,
            uuid,
            parent,
            owner,
            begin,
            end,
            output,
            input,
            audioInput,
            noteInput,
            pre,
            post,
            notes,
            trackKind;
    private final Method allIns,
            keyboard,
            onscreen,
            outCurrent,
            outSet,
            outClear,
            outAccepts,
            inCurrent,
            inSet,
            inClear,
            inAccepts;
    private final Method busOwner,
            busName,
            descendants,
            selector,
            componentKinds,
            componentId,
            componentLabel,
            modulatorId,
            moduleId;

    Bitwig614RoutingAccess(Bitwig614Runtime.Access a) throws ReflectiveOperationException {
        this.a = a;
        context = new NativeReadContext(new Bitwig614ReadContextAccess(a));
        targets = new NativeDeviceTarget(new Bitwig614DeviceTargetAccess(a));
        a.requireModelThreadAvailable();
        trackType = a.type(M + "zwV");
        groupIface = a.type(I + "kWY");
        channelType = a.type("xJo");
        projectType = a.type(M + "g2L");
        portsType = a.type(I + "pHd");
        destinationSelector = a.type("j2s");
        sourceSelector = a.type("lbA");
        Class<?> destinationBus = a.type("twn"),
                bus = a.type("w3i"),
                named = a.type("bCo"),
                identity = a.type(I + "RYd");
        sourceBus = a.type("qy");
        audioDestination = a.type("DfX");
        noteDestination = a.type("WIv");
        preFader = a.type("LmY");
        postFader = a.type("gqU");
        trackNotes = a.type("IyD");
        componentType = a.type("W2w");
        audioSidechain = a.type("D3M");
        noteSidechain = a.type("gjc");
        pluginSidechain = a.type("aBN");
        audioIn = a.type("NgI");
        deviceType = a.type(D + "xlG");
        modulatorType = a.type(D + "tad");
        moduleType = a.type(D + "rSf");
        Class<?> node = a.type(R + "oDc"), component = a.type("GjM"), labelled = a.type("PBl");
        // Ancestry the readback and kind mapping rely on (inspected 6.1.4 hierarchy).
        ancestry(channelType, trackType);
        ancestry(channelType, a.type("ynF"));
        ancestry(channelType, identity);
        ancestry(a.type(M + "ruS"), groupIface);
        ancestry(a.type(M + "ruS"), trackType);
        ancestry(audioDestination, destinationBus);
        ancestry(noteDestination, destinationBus);
        ancestry(destinationBus, bus);
        ancestry(preFader, sourceBus);
        ancestry(postFader, sourceBus);
        ancestry(trackNotes, sourceBus);
        ancestry(sourceBus, bus);
        ancestry(bus, named);
        ancestry(componentType, component);
        ancestry(component, labelled);
        ancestry(componentType, node);
        ancestry(deviceType, node);
        ancestry(audioSidechain, componentType);
        ancestry(noteSidechain, componentType);
        ancestry(pluginSidechain, audioSidechain);
        ancestry(audioIn, componentType);
        ancestry(projectType, portsType);
        if (deviceType.isAssignableFrom(modulatorType))
            throw new NoSuchMethodException("Inspected modulator model became a device.");
        enumNames(a.type("fP"), "INSTRUMENT", "AUDIO", "HYBRID");
        enumNames(
                a.type("kCk"),
                "HARDWARE_NOTE_SOURCES",
                "HARDWARE_AUDIO_SOURCES",
                "TRACK_AUDIO_SOURCES",
                "TRACK_NOTE_SOURCES");
        target = a.exactMethod(
                a.type("com.bitwig.flt.control_surface.proxy.ControlSurfaceProxy"),
                "getDeepestTarget",
                Object.class,
                false);
        channel = a.exactMethod(trackType, "JJY", channelType, false);
        alias = a.exactMethod(groupIface, "TD2", a.type("iKU"), false);
        uuid = a.exactMethod(identity, "kFU", UUID.class, false);
        parent = a.exactMethod(identity, "QNk", groupIface, false);
        owner = a.exactMethod(a.type(R + "AVL"), "IwV", Object.class, false, Class.class);
        begin = a.exactMethod(projectType, "cfM", void.class, false, String.class, boolean.class);
        end = a.exactMethod(projectType, "cfM", void.class, false, boolean.class);
        output = a.exactMethod(channelType, "OnS", destinationSelector, false);
        input = a.exactMethod(channelType, "ADn", sourceSelector, false);
        audioInput = a.exactMethod(channelType, "Tfs", audioDestination, false);
        noteInput = a.exactMethod(channelType, "Ccl", noteDestination, false);
        pre = a.exactMethod(channelType, "LIl", preFader, false);
        post = a.exactMethod(channelType, "SLC", postFader, false);
        notes = a.exactMethod(channelType, "RbC", trackNotes, false);
        trackKind = a.exactMethod(channelType, "BNq", a.type("fP"), false);
        allIns = a.exactMethod(portsType, "cCk", a.type("Nq"), false);
        keyboard = a.exactMethod(portsType, "JhA", a.type("Dzw"), false);
        onscreen = a.exactMethod(portsType, "tB1", a.type("hYu"), false);
        outCurrent = a.exactMethod(destinationSelector, "UZr", destinationBus, false);
        outSet = a.exactMethod(destinationSelector, "IwV", void.class, false, destinationBus);
        outClear = a.exactMethod(destinationSelector, "wGc", void.class, false);
        outAccepts = a.exactMethod(destinationSelector, "MRY", boolean.class, false, Object.class);
        inCurrent = a.exactMethod(sourceSelector, "UZr", sourceBus, false);
        inSet = a.exactMethod(sourceSelector, "cfM", void.class, false, sourceBus);
        inClear = a.exactMethod(sourceSelector, "wGc", void.class, false);
        inAccepts = a.exactMethod(sourceSelector, "MRY", boolean.class, false, Object.class);
        busOwner = a.exactMethod(bus, "rGR", channelType, false);
        busName = a.exactMethod(named, "qm", String.class, false);
        descendants = a.exactMethod(a.type(R + "zjj"), "cfM", Stream.class, true, node, Class.class, String.class);
        selector = a.exactMethod(component, "eej", sourceSelector, false);
        componentKinds = a.exactMethod(componentType, "cfM", EnumSet.class, false);
        componentId = a.exactMethod(labelled, "aA_", String.class, false);
        componentLabel = a.exactMethod(labelled, "UNj", String.class, false);
        modulatorId = a.exactMethod(modulatorType, "kFU", UUID.class, false);
        moduleId = a.exactMethod(moduleType, "kFU", UUID.class, false);
    }

    private static void ancestry(Class<?> type, Class<?> ancestor) throws NoSuchMethodException {
        if (!ancestor.isAssignableFrom(type))
            throw new NoSuchMethodException(
                    "Inspected ancestry differs: " + type.getName() + " is not a " + ancestor.getName());
    }

    private static void enumNames(Class<?> type, String... names) throws NoSuchMethodException {
        Object[] constants = type.getEnumConstants();
        if (constants == null || constants.length != names.length)
            throw new NoSuchMethodException("Inspected enum differs: " + type.getName());
        for (int i = 0; i < names.length; i++)
            if (!((Enum<?>) constants[i]).name().equals(names[i]))
                throw new NoSuchMethodException("Inspected enum constants differ: " + type.getName());
    }

    @Override
    public void execute(Runnable work) {
        a.execute(work);
    }

    // ---- shared resolution ----

    /** A bank track resolved to its native track model and channel; UUID and model thread corroborated. */
    private final class Channel {
        final RoutingModule.TrackRef ref;
        final Object track, channel;

        Channel(RoutingModule.TrackRef ref) {
            this.ref = ref;
            track = a.invoke(target, ref.proxy);
            if (!trackType.isInstance(track))
                throw failure("stale_context", "Track proxy has no attached native track: " + ref.id);
            a.requireModelThread(track);
            Object found = a.invoke(Bitwig614RoutingAccess.this.channel, track);
            if (!channelType.isInstance(found))
                throw failure(a.unavailableCode(), "Native track has no mapped channel: " + ref.id);
            if (groupIface.isInstance(track) && a.invoke(alias, track) != found)
                throw failure(a.unavailableCode(), "Native group channel alias differs from its channel: " + ref.id);
            a.requireModelThread(found);
            if (!UUID.fromString(ref.id).equals(a.invoke(uuid, found)))
                throw failure(
                        "stale_context", "Native channel UUID differs from the requested trackId " + ref.id + ".");
            channel = found;
        }

        boolean same() {
            return a.invoke(target, ref.proxy) == track
                    && a.invoke(Bitwig614RoutingAccess.this.channel, track) == channel;
        }

        String role() {
            if (track == channel) return "track";
            if (groupIface.isInstance(track)) return a.invoke(parent, track) == null ? "root_group" : "group";
            return "other";
        }

        String nativeKind() {
            Object kind = a.invoke(trackKind, channel);
            return kind == null ? null : ((Enum<?>) kind).name();
        }
    }

    /** One native read/write scope: the models whose project/context lifecycle is captured and re-guarded. */
    private final class Scope {
        final Object project;
        final Object[] models;
        final String[] labels;
        final NativeReadContext.Capture capture;

        Scope(Object anchor, List<Object> extra, Runnable check) {
            project = a.invoke(owner, anchor, projectType);
            if (!projectType.isInstance(project))
                throw failure(a.unavailableCode(), "Native routing target has no owning project.");
            List<Object> all = new ArrayList<>(extra);
            all.add(anchor);
            models = all.toArray();
            labels = new String[models.length + 1];
            for (int i = 0; i < models.length; i++) labels[i] = "model" + i + "Deleted";
            labels[models.length] = "projectDeleted";
            capture = context.capture(project, models, labels, check, false);
        }

        void guard(Runnable check) {
            check.run();
            context.guard(capture, models, labels, check);
        }

        void transaction(String label, Runnable work) {
            a.invoke(begin, project, label, false);
            try {
                work.run();
            } finally {
                a.invoke(end, project, false);
            }
        }

        Object ports() {
            Object ports = a.invoke(owner, models[models.length - 1], portsType);
            if (ports != project)
                throw failure(a.unavailableCode(), "Native note-source owner is not the owning project.");
            return ports;
        }
    }

    private String name(Object bus) {
        String value = (String) a.invoke(busName, bus);
        return value == null ? "" : value;
    }

    /** A copyable entry {kind, trackId?}; only kind other carries its exact name and native type (its CAS identity). */
    private JsonObject entry(String kind, Object channelModel, Object bus) {
        JsonObject out = new JsonObject();
        out.addProperty("kind", kind);
        if (channelModel != null)
            out.addProperty("trackId", a.invoke(uuid, channelModel).toString());
        return out;
    }

    private JsonElement nameOf(Object bus) {
        return bus == null ? JsonNull.INSTANCE : new JsonPrimitive(name(bus));
    }
    /** A choice row: the copyable entry, its display name and Bitwig's accept check. */
    private JsonObject choice(JsonObject entry, Object bus, boolean accepted) {
        JsonObject row = new JsonObject();
        row.add("entry", entry);
        row.add("name", nameOf(bus));
        row.addProperty("accepted", accepted);
        return row;
    }

    /** Classify a destination by native identity: a channel's own audio/note input or something else. */
    private JsonObject describeDestination(Object bus) {
        if (bus == null) return entry("none", null, null);
        a.requireModelThread(bus);
        if (audioDestination.isInstance(bus) || noteDestination.isInstance(bus)) {
            Object owning = a.invoke(busOwner, bus);
            boolean audio = audioDestination.isInstance(bus);
            if (owning != null && a.invoke(audio ? audioInput : noteInput, owning) == bus)
                return entry(audio ? "track_audio_input" : "track_note_input", owning, bus);
        }
        JsonObject other = entry("other", null, bus);
        other.addProperty("name", name(bus));
        other.addProperty("nativeType", bus.getClass().getName());
        return other;
    }

    /** Classify a source by native identity: a channel tap, a project note source, or something else. */
    private JsonObject describeSource(Object bus, Object ports) {
        if (bus == null) return entry("none", null, null);
        a.requireModelThread(bus);
        for (Object[] tap : new Object[][] {
            {preFader, pre, "pre_fader"}, {postFader, post, "post_fader"}, {trackNotes, notes, "track_notes"}
        }) {
            if (!((Class<?>) tap[0]).isInstance(bus)) continue;
            Object owning = a.invoke(busOwner, bus);
            if (owning != null && a.invoke((Method) tap[1], owning) == bus) return entry((String) tap[2], owning, bus);
        }
        if (bus == a.invoke(allIns, ports)) return entry("all_ins", null, bus);
        if (bus == a.invoke(keyboard, ports)) return entry("computer_keyboard", null, bus);
        if (bus == a.invoke(onscreen, ports)) return entry("onscreen_keyboard", null, bus);
        JsonObject other = entry("other", null, bus);
        other.addProperty("name", name(bus));
        other.addProperty("nativeType", bus.getClass().getName());
        return other;
    }

    private Channel find(Map<String, Channel> channels, String id) {
        for (Map.Entry<String, Channel> e : channels.entrySet())
            if (e.getKey().equalsIgnoreCase(id)) return e.getValue();
        return null;
    }

    /** Every resolvable bank channel; unresolvable ones are reported, never guessed. */
    private Map<String, Channel> channels(List<RoutingModule.TrackRef> bank, JsonArray unresolved) {
        Map<String, Channel> out = new LinkedHashMap<>();
        for (RoutingModule.TrackRef ref : bank) {
            try {
                out.put(ref.id, new Channel(ref));
            } catch (RpcException error) {
                if (unresolved == null) throw error;
                JsonObject row = new JsonObject();
                row.addProperty("trackId", ref.id);
                row.addProperty("type", ref.type);
                row.addProperty("code", error.code);
                row.addProperty("reason", error.getMessage());
                unresolved.add(row);
            }
        }
        return out;
    }

    /** The native destination for a requested output entry, or null for none. */
    private Object destination(JsonObject request, Map<String, Channel> channels) {
        String kind = request.get("kind").getAsString();
        if (kind.equals("none")) return null;
        Channel to = find(channels, request.get("trackId").getAsString());
        if (to == null)
            throw failure(
                    "stale_context",
                    "Destination track did not resolve to a native channel; refresh tracks.routing.get.");
        Object bus = a.invoke(kind.equals("track_audio_input") ? audioInput : noteInput, to.channel);
        if (bus == null) throw failure("unsupported_scope", "Destination track has no native " + kind + ".");
        a.requireModelThread(bus);
        return bus;
    }

    /** The native source for a requested input/sidechain entry, or null for none. */
    private Object source(JsonObject request, Map<String, Channel> channels, Object ports) {
        String kind = request.get("kind").getAsString();
        Object bus;
        switch (kind) {
            case "none":
                return null;
            case "all_ins":
                bus = a.invoke(allIns, ports);
                break;
            case "computer_keyboard":
                bus = a.invoke(keyboard, ports);
                break;
            case "onscreen_keyboard":
                bus = a.invoke(onscreen, ports);
                break;
            default: {
                Channel from = find(channels, request.get("trackId").getAsString());
                if (from == null)
                    throw failure(
                            "stale_context", "Source track did not resolve to a native channel; refresh the get.");
                bus = a.invoke(kind.equals("pre_fader") ? pre : kind.equals("post_fader") ? post : notes, from.channel);
            }
        }
        if (bus == null || !sourceBus.isInstance(bus))
            throw failure("unsupported_scope", "No native " + kind + " source is available.");
        a.requireModelThread(bus);
        return bus;
    }

    private JsonArray destinationChoices(Object chooser, Channel self, Map<String, Channel> channels) {
        JsonArray out = new JsonArray();
        out.add(choice(entry("none", null, null), null, true));
        for (Channel to : channels.values()) {
            if (to.channel == self.channel) continue;
            for (String kind : List.of("track_audio_input", "track_note_input")) {
                Object bus = a.invoke(kind.equals("track_audio_input") ? audioInput : noteInput, to.channel);
                if (bus == null) continue;
                out.add(choice(entry(kind, to.channel, bus), bus, (Boolean) a.invoke(outAccepts, chooser, bus)));
            }
        }
        return out;
    }

    private JsonArray sourceChoices(
            Object chooser, Channel exclude, Map<String, Channel> channels, Object ports, List<String> kinds) {
        JsonArray out = new JsonArray();
        out.add(choice(entry("none", null, null), null, true));
        for (String kind : List.of("all_ins", "computer_keyboard", "onscreen_keyboard")) {
            if (!kinds.contains(kind)) continue;
            Object bus = a.invoke(
                    kind.equals("all_ins") ? allIns : kind.equals("computer_keyboard") ? keyboard : onscreen, ports);
            if (bus == null) continue;
            out.add(choice(entry(kind, null, bus), bus, (Boolean) a.invoke(inAccepts, chooser, bus)));
        }
        for (Channel from : channels.values()) {
            if (exclude != null && from.channel == exclude.channel) continue;
            for (String kind : List.of("pre_fader", "post_fader", "track_notes")) {
                if (!kinds.contains(kind)) continue;
                Object bus = a.invoke(
                        kind.equals("pre_fader") ? pre : kind.equals("post_fader") ? post : notes, from.channel);
                if (bus == null) continue;
                out.add(choice(entry(kind, from.channel, bus), bus, (Boolean) a.invoke(inAccepts, chooser, bus)));
            }
        }
        return out;
    }

    private static JsonArray strings(Collection<String> values) {
        JsonArray out = new JsonArray();
        values.forEach(out::add);
        return out;
    }

    // ---- tracks.routing ----

    /** Native view of one track's choosers; input is null where the track type has no input chooser. */
    private final class TrackView {
        final Channel self;
        final Object outputChooser, inputChooser;
        final String nativeKind, inputUnavailable;
        final List<String> inputGroups;

        TrackView(Channel self) {
            this.self = self;
            nativeKind = self.nativeKind();
            outputChooser = a.invoke(output, self.channel);
            if (!destinationSelector.isInstance(outputChooser))
                throw failure(a.unavailableCode(), "Native channel has no destination selector.");
            a.requireModelThread(outputChooser);
            String expectedKind = RoutingModule.INPUT_TRACK_TYPES.get(self.ref.type);
            Object chooser = null;
            String reason = null;
            if (expectedKind == null) reason = "no_input_chooser_for_track_type_" + self.ref.type;
            else if (!expectedKind.equals(nativeKind))
                reason = "native_track_kind_" + nativeKind + "_differs_from_public_type_" + self.ref.type;
            else if (!"track".equals(self.role())) reason = "native_role_" + self.role();
            else {
                chooser = a.invoke(input, self.channel);
                if (!sourceSelector.isInstance(chooser))
                    throw failure(a.unavailableCode(), "Native channel has no source selector.");
                a.requireModelThread(chooser);
            }
            inputChooser = chooser;
            inputUnavailable = reason;
            inputGroups = chooser == null ? List.of() : RoutingModule.trackSourceGroups(nativeKind);
        }

        String outputUnavailable() {
            return "Master".equals(self.ref.type)
                    ? "master_output_is_hardware_only_and_hardware_entries_are_deferred"
                    : null;
        }
    }

    private JsonObject snapshot(
            TrackView view, Object ports, Map<String, Channel> channels, boolean includeChoices, JsonArray unresolved) {
        JsonObject out = HostServices.observed();
        out.addProperty("trackId", view.self.ref.id);
        out.addProperty("trackType", view.self.ref.type);
        out.addProperty("nativeTrackKind", view.nativeKind);
        out.addProperty("nativeRole", view.self.role());
        JsonObject outputRow = new JsonObject();
        Object currentOutput = a.invoke(outCurrent, view.outputChooser);
        outputRow.add("entry", describeDestination(currentOutput));
        outputRow.add("name", nameOf(currentOutput));
        outputRow.add(
                "kinds",
                strings(
                        view.outputUnavailable() == null
                                ? List.of("none", "track_audio_input", "track_note_input")
                                : List.of()));
        outputRow.addProperty("writable", view.outputUnavailable() == null);
        if (view.outputUnavailable() != null) outputRow.addProperty("reason", view.outputUnavailable());
        if (includeChoices) outputRow.add("choices", destinationChoices(view.outputChooser, view.self, channels));
        out.add("output", outputRow);
        if (view.inputChooser == null) {
            out.add("input", JsonNull.INSTANCE);
            out.addProperty("inputUnavailableReason", view.inputUnavailable);
        } else {
            JsonObject inputRow = new JsonObject();
            Object currentInput = a.invoke(inCurrent, view.inputChooser);
            inputRow.add("entry", describeSource(currentInput, ports));
            inputRow.add("name", nameOf(currentInput));
            List<String> kinds = RoutingModule.sourceKinds(view.inputGroups);
            inputRow.add("kinds", strings(kinds));
            inputRow.add("nativeSourceGroups", strings(view.inputGroups));
            inputRow.addProperty("writable", true);
            if (includeChoices)
                inputRow.add("choices", sourceChoices(view.inputChooser, view.self, channels, ports, kinds));
            out.add("input", inputRow);
        }
        if (unresolved != null && unresolved.size() > 0) out.add("unresolvedTracks", unresolved);
        out.addProperty(
                "coverage",
                "tracks_of_the_16_track_bank_only; hardware_and_device_output_entries_read_as_other_and_not_settable");
        out.addProperty("nativeVersion", Bitwig614Runtime.VERSION);
        out.addProperty("selectionSideEffect", "none");
        return out;
    }

    @Override
    public JsonObject trackGet(
            RoutingModule.TrackRef ref, List<RoutingModule.TrackRef> bank, boolean includeChoices, Runnable check) {
        a.documentThread();
        check.run();
        Channel self = new Channel(ref);
        TrackView view = new TrackView(self);
        Scope scope = new Scope(self.channel, List.of(self.track), check);
        a.requireModelThread(self.channel);
        Object ports = scope.ports();
        JsonArray unresolved = includeChoices ? new JsonArray() : null;
        Map<String, Channel> channels = includeChoices ? channels(bank, unresolved) : Map.of();
        JsonObject out = snapshot(view, ports, channels, includeChoices, unresolved);
        scope.guard(check);
        if (!self.same()) throw failure("stale_context", "Native track changed during the routing read.");
        return out;
    }

    /** One requested chooser change after every pre-write check passed. */
    private static final class Change {
        final String field;
        final Object chooser, requested;
        final boolean destination;
        final JsonObject previous, request;
        final boolean already;

        Change(
                String field,
                Object chooser,
                Object requested,
                boolean destination,
                JsonObject previous,
                JsonObject request,
                boolean already) {
            this.field = field;
            this.chooser = chooser;
            this.requested = requested;
            this.destination = destination;
            this.previous = previous;
            this.request = request;
            this.already = already;
        }
    }

    @Override
    public JsonObject trackSet(
            RoutingModule.TrackRef ref,
            List<RoutingModule.TrackRef> bank,
            JsonObject p,
            Runnable check,
            Runnable beforeWrite) {
        a.documentThread();
        check.run();
        Channel self = new Channel(ref);
        TrackView view = new TrackView(self);
        Map<String, Channel> channels = channels(bank, new JsonArray());
        List<Object> extra = new ArrayList<>(List.of(self.track));
        Scope scope = new Scope(self.channel, extra, check);
        Object ports = scope.ports();
        List<Change> changes = new ArrayList<>();
        if (p.has("output")) {
            if (view.outputUnavailable() != null)
                throw failure(
                        "unsupported_scope",
                        "This track's output cannot be set in this version (" + view.outputUnavailable()
                                + "); nothing was dispatched.");
            JsonObject request = p.getAsJsonObject("output");
            Object current = a.invoke(outCurrent, view.outputChooser);
            JsonObject previous = describeDestination(current);
            if (!RoutingModule.sameEntry(p.getAsJsonObject("expectedOutput"), previous))
                throw failure(
                        "stale_target",
                        "Current output is " + previous
                                + ", not expectedOutput; re-read tracks.routing.get. Nothing was dispatched.");
            String kind = request.get("kind").getAsString();
            if (!kind.equals("none") && !RoutingModule.OUTPUT_GROUP.containsKey(kind))
                throw failure("kind_mismatch", "Output kind " + kind + " is not a destination kind.");
            if (request.has("trackId") && request.get("trackId").getAsString().equalsIgnoreCase(ref.id))
                throw failure("rejected_cycle", "A track cannot route its output to itself; nothing was dispatched.");
            Object bus = destination(request, channels);
            boolean already = bus == current;
            if (!already && bus != null && !Boolean.TRUE.equals(a.invoke(outAccepts, view.outputChooser, bus)))
                throw failure(
                        "rejected_cycle",
                        "Bitwig's destination check rejects " + request
                                + " (it would create a routing cycle); nothing was dispatched.");
            changes.add(new Change("output", view.outputChooser, bus, true, previous, request, already));
        }
        if (p.has("input")) {
            if (view.inputChooser == null)
                throw failure(
                        "unsupported_scope",
                        "This track has no input chooser in this version (" + view.inputUnavailable
                                + "); nothing was dispatched.");
            JsonObject request = p.getAsJsonObject("input");
            Object current = a.invoke(inCurrent, view.inputChooser);
            JsonObject previous = describeSource(current, ports);
            if (!RoutingModule.sameEntry(p.getAsJsonObject("expectedInput"), previous))
                throw failure(
                        "stale_target",
                        "Current input is " + previous
                                + ", not expectedInput; re-read tracks.routing.get. Nothing was dispatched.");
            String kind = request.get("kind").getAsString();
            if (!RoutingModule.sourceKinds(view.inputGroups).contains(kind))
                throw failure(
                        "kind_mismatch",
                        "A " + self.ref.type + " track's input accepts " + RoutingModule.sourceKinds(view.inputGroups)
                                + ", not " + kind + "; nothing was dispatched.");
            if (request.has("trackId") && request.get("trackId").getAsString().equalsIgnoreCase(ref.id))
                throw failure("rejected_cycle", "A track cannot be its own input; nothing was dispatched.");
            Object bus = source(request, channels, ports);
            boolean already = bus == current;
            if (!already && bus != null && !Boolean.TRUE.equals(a.invoke(inAccepts, view.inputChooser, bus)))
                throw failure(
                        "rejected_cycle",
                        "Bitwig's source check rejects " + request
                                + " (it would create a routing cycle); nothing was dispatched.");
            changes.add(new Change("input", view.inputChooser, bus, false, previous, request, already));
        }
        if (changes.size() == 2
                && changes.stream().noneMatch(change -> change.already || change.requested == null)
                && changes.get(0).request.has("trackId")
                && changes.get(1).request.has("trackId")
                && changes.get(0)
                        .request
                        .get("trackId")
                        .getAsString()
                        .equalsIgnoreCase(changes.get(1).request.get("trackId").getAsString()))
            throw failure(
                    "rejected_cycle",
                    "output and input name the same track, which would loop through this track; Bitwig checks each against the current graph only, so this pair is refused (set them in two calls if one is meant to replace the other). Nothing was dispatched.");
        Runnable same = () -> {
            if (!self.same()
                    || a.invoke(output, self.channel) != view.outputChooser
                    || (view.inputChooser != null && a.invoke(input, self.channel) != view.inputChooser))
                throw failure(
                        "stale_context", "Native track or its routing selectors changed around the routing write.");
        };
        scope.guard(check);
        same.run();
        JsonObject fields = write(scope, changes, "Change Track Routing", check, beforeWrite, same, ports);
        JsonObject out = snapshot(view, ports, Map.of(), false, null);
        return finish(out, fields, changes);
    }

    /** Dispatch the changes in one project transaction, then read each chooser back by identity. */
    private JsonObject write(
            Scope scope,
            List<Change> changes,
            String label,
            Runnable check,
            Runnable beforeWrite,
            Runnable same,
            Object ports) {
        boolean any = changes.stream().anyMatch(change -> !change.already);
        if (any) {
            beforeWrite.run();
            scope.transaction(label, () -> {
                for (Change change : changes) {
                    if (change.already) continue;
                    if (change.destination) {
                        if (change.requested == null) a.invoke(outClear, change.chooser);
                        else a.invoke(outSet, change.chooser, change.requested);
                    } else if (change.requested == null) a.invoke(inClear, change.chooser);
                    else a.invoke(inSet, change.chooser, change.requested);
                }
            });
            scope.guard(check);
            same.run();
        }
        JsonObject fields = new JsonObject();
        for (Change change : changes) {
            Object now = a.invoke(change.destination ? outCurrent : inCurrent, change.chooser);
            JsonObject row = new JsonObject();
            row.addProperty(
                    "status", change.already ? "already_matched" : now == change.requested ? "applied" : "unknown");
            row.add("requested", change.request);
            row.add("previous", change.previous);
            row.add("readback", change.destination ? describeDestination(now) : describeSource(now, ports));
            if (!change.already && now != change.requested)
                row.addProperty("reason", "readback_identity_differs_from_requested_entry");
            fields.add(change.field, row);
        }
        return fields;
    }

    private JsonObject finish(JsonObject out, JsonObject fields, List<Change> changes) {
        boolean any = changes.stream().anyMatch(change -> !change.already);
        boolean unknown = false;
        for (Map.Entry<String, JsonElement> field : fields.entrySet())
            unknown |= field.getValue()
                    .getAsJsonObject()
                    .get("status")
                    .getAsString()
                    .equals("unknown");
        out.add("fields", fields);
        out.addProperty("status", unknown ? "unknown" : any ? "applied" : "already_matched");
        out.addProperty("completion", unknown ? "unknown" : "observed");
        out.addProperty("verification", any ? "native_selector_identity_readback" : "already_matched");
        out.addProperty("nativeUndo", any);
        out.addProperty("undoGroupingVerified", false);
        out.addProperty("nativeJarSha256", a.actualHash());
        if (unknown)
            out.addProperty(
                    "next",
                    "Never retry: inspect with a fresh get; routing writes are quarantined until extension.restart.");
        return out;
    }

    // ---- devices.sidechain ----

    private final class Component {
        final Object model, chooser;
        final int index;
        final String host, kind, id, label, ownerId, unavailable;
        final List<String> groups;

        Component(Object model, Object device, int index) {
            this.model = model;
            this.index = index;
            a.requireModelThread(model);
            chooser = a.invoke(selector, model);
            if (chooser != null) a.requireModelThread(chooser);
            Object grid = a.invoke(owner, model, moduleType), mod = a.invoke(owner, model, modulatorType);
            if (grid != null && a.invoke(owner, grid, deviceType) == device) {
                host = "grid_module";
                ownerId = a.invoke(moduleId, grid).toString();
            } else if (mod != null && a.invoke(owner, mod, deviceType) == device) {
                host = "modulator";
                ownerId = a.invoke(modulatorId, mod).toString();
            } else {
                host = "device";
                ownerId = null;
            }
            kind = pluginSidechain.isInstance(model)
                    ? "plugin_audio"
                    : audioSidechain.isInstance(model)
                            ? "audio"
                            : noteSidechain.isInstance(model)
                                    ? "note"
                                    : audioIn.isInstance(model) ? "audio_in" : "other";
            id = (String) a.invoke(componentId, model);
            label = (String) a.invoke(componentLabel, model);
            List<String> found = new ArrayList<>();
            Object set = a.invoke(componentKinds, model);
            if (set instanceof Set) for (Object value : (Set<?>) set) found.add(((Enum<?>) value).name());
            groups = found;
            String reason = null;
            if (chooser == null || !sourceSelector.isInstance(chooser)) reason = "component_has_no_source_selector";
            else if (kind.equals("audio_in") || kind.equals("other")) reason = "hardware_or_unknown_component_deferred";
            unavailable = reason;
        }

        String ownerKey() {
            return OWNER_KEYS.get(host);
        }
    }

    private static final Map<String, String> OWNER_KEYS = Map.of("modulator", "modulatorId", "grid_module", "moduleId");

    private List<Component> components(Object device) {
        List<Component> out = new ArrayList<>();
        Iterator<?> found = ((Stream<?>) a.invoke(descendants, null, device, componentType, null)).iterator();
        int seen = 0;
        while (found.hasNext()) {
            Object model = found.next();
            if (++seen > RoutingModule.MAX_COMPONENTS * 4)
                throw failure(
                        "routing_bounds_exceeded", "Device holds more routing components than this version reads.");
            if (!componentType.isInstance(model) || a.invoke(owner, model, deviceType) != device) continue;
            if (out.size() >= RoutingModule.MAX_COMPONENTS)
                throw failure(
                        "routing_bounds_exceeded",
                        "Device holds more than " + RoutingModule.MAX_COMPONENTS + " sidechain components.");
            out.add(new Component(model, device, out.size()));
        }
        return out;
    }

    private JsonObject row(Component c, Object ports, Map<String, Channel> channels, boolean includeChoices) {
        JsonObject out = new JsonObject();
        out.addProperty("componentIndex", c.index);
        out.addProperty("componentId", c.id);
        out.addProperty("label", c.label);
        out.addProperty("kind", c.kind);
        out.addProperty("host", c.host);
        if (c.ownerId != null) out.addProperty(c.ownerKey(), c.ownerId);
        out.add("nativeSourceGroups", strings(c.groups));
        List<String> kinds = c.unavailable == null ? RoutingModule.sourceKinds(c.groups) : List.of();
        out.add("kinds", strings(kinds));
        out.addProperty("writable", c.unavailable == null);
        if (c.unavailable != null) out.addProperty("reason", c.unavailable);
        boolean selectable = c.chooser != null && sourceSelector.isInstance(c.chooser);
        Object current = selectable ? a.invoke(inCurrent, c.chooser) : null;
        out.add("source", selectable ? describeSource(current, ports) : JsonNull.INSTANCE);
        out.add("sourceName", nameOf(current));
        if (includeChoices && c.unavailable == null)
            out.add("choices", sourceChoices(c.chooser, null, channels, ports, kinds));
        return out;
    }

    @Override
    public JsonObject sidechainGet(
            Object proxy, String trackId, List<RoutingModule.TrackRef> bank, boolean includeChoices, Runnable check) {
        NativeDeviceTarget.Capture device = targets.capture(proxy, UUID.fromString(trackId), check);
        Scope scope = new Scope(device.device, List.of(), check);
        Object ports = scope.ports();
        List<Component> components = components(device.device);
        JsonArray unresolved = includeChoices ? new JsonArray() : null;
        Map<String, Channel> channels = includeChoices ? channels(bank, unresolved) : Map.of();
        JsonObject out = HostServices.observed();
        JsonArray rows = new JsonArray();
        for (Component c : components) rows.add(row(c, ports, channels, includeChoices));
        out.add("components", rows);
        out.addProperty("count", components.size());
        if (unresolved != null && unresolved.size() > 0) out.add("unresolvedTracks", unresolved);
        out.addProperty(
                "coverage",
                "routing_components_of_this_device_its_modulators_and_grid_modules; nested_devices_excluded");
        out.addProperty("nativeVersion", Bitwig614Runtime.VERSION);
        out.addProperty("selectionSideEffect", "none");
        scope.guard(check);
        targets.guard(device, proxy, check);
        return out;
    }

    private static String expectedKey(String ownerKey) {
        return "expected" + Character.toUpperCase(ownerKey.charAt(0)) + ownerKey.substring(1);
    }

    private void guardOwner(Component c, JsonObject p) {
        for (String key : OWNER_KEYS.values()) {
            String expected = expectedKey(key);
            if (key.equals(c.ownerKey())) {
                if (!(p.has(expected) && p.get(expected).getAsString().equalsIgnoreCase(c.ownerId)))
                    throw failure(
                            "stale_target",
                            "Component " + c.index + " belongs to " + c.host + " " + c.ownerId + "; pass it as "
                                    + expected
                                    + " (such components shift when their owners change). Nothing was dispatched.");
            } else if (p.has(expected))
                throw failure(
                        "stale_target",
                        "Component " + c.index + " is on " + c.host + ", but " + expected
                                + " was given; re-read devices.sidechain.get. Nothing was dispatched.");
        }
    }

    @Override
    public JsonObject sidechainSet(
            Object proxy,
            String trackId,
            List<RoutingModule.TrackRef> bank,
            JsonObject p,
            Runnable check,
            Runnable beforeWrite) {
        NativeDeviceTarget.Capture device = targets.capture(proxy, UUID.fromString(trackId), check);
        List<Component> components = components(device.device);
        Component c = null;
        if (p.has("componentIndex")) {
            int index = p.get("componentIndex").getAsInt();
            if (index >= components.size())
                throw failure(
                        "target_not_found",
                        "componentIndex " + index + " is outside the " + components.size()
                                + " components; re-read devices.sidechain.get.");
            c = components.get(index);
            if (!p.get("expectedComponentId").getAsString().equals(c.id))
                throw failure(
                        "stale_target",
                        "Component " + index + " has componentId " + c.id
                                + ", not expectedComponentId; re-read devices.sidechain.get. Nothing was dispatched.");
        } else {
            String id = p.get("componentId").getAsString();
            for (Component candidate : components)
                if (id.equals(candidate.id)) {
                    if (c != null)
                        throw failure(
                                "ambiguous_target",
                                "componentId " + id
                                        + " names more than one component; use componentIndex from devices.sidechain.get.");
                    c = candidate;
                }
            if (c == null)
                throw failure(
                        "target_not_found", "No component has componentId " + id + "; re-read devices.sidechain.get.");
        }
        guardOwner(c, p);
        if (c.unavailable != null)
            throw failure(
                    "unsupported_scope",
                    "Component " + c.index + " cannot be set in this version (" + c.unavailable
                            + "); nothing was dispatched.");
        Map<String, Channel> channels = channels(bank, new JsonArray());
        Scope scope = new Scope(device.device, List.of(c.model, c.chooser), check);
        Object ports = scope.ports();
        JsonObject request = p.getAsJsonObject("source");
        Object current = a.invoke(inCurrent, c.chooser);
        JsonObject previous = describeSource(current, ports);
        if (!RoutingModule.sameEntry(p.getAsJsonObject("expectedSource"), previous))
            throw failure(
                    "stale_target",
                    "Current source is " + previous
                            + ", not expectedSource; re-read devices.sidechain.get. Nothing was dispatched.");
        String kind = request.get("kind").getAsString();
        List<String> kinds = RoutingModule.sourceKinds(c.groups);
        if (!kinds.contains(kind))
            throw failure(
                    "kind_mismatch",
                    "This " + c.kind + " component accepts " + kinds + ", not " + kind + "; nothing was dispatched.");
        Object bus = source(request, channels, ports);
        boolean already = bus == current;
        if (!already && bus != null && !Boolean.TRUE.equals(a.invoke(inAccepts, c.chooser, bus)))
            throw failure(
                    "rejected_cycle",
                    "Bitwig's source check rejects " + request
                            + " (it would create a routing cycle); nothing was dispatched.");
        List<Change> changes = List.of(new Change("source", c.chooser, bus, false, previous, request, already));
        final Component chosen = c;
        Runnable same = () -> {
            if (a.invoke(selector, chosen.model) != chosen.chooser)
                throw failure("stale_context", "Component source selector changed during the write.");
            targets.guard(device, proxy, check);
        };
        scope.guard(check);
        same.run();
        JsonObject fields = write(scope, changes, "Change Sidechain Source", check, beforeWrite, same, ports);
        JsonObject out = HostServices.observed();
        out.add("component", row(c, ports, Map.of(), false));
        return finish(out, fields, changes);
    }
}
