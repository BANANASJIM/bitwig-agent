package dev.bitwigagent.nativehost;

import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.NativeProvenance;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.core.RpcException;
import dev.bitwigagent.host.DeviceSources;
import dev.bitwigagent.host.NativeReadContext;
import dev.bitwigagent.routing.RoutingEntry;
import dev.bitwigagent.routing.RoutingPort;
import java.lang.reflect.Method;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Exact bindings for the routing choosers behind Bitwig's own GUI menus (outputs, inputs and sidechains). Output:
 * channel {@code mixer.channel.output()} destination selector, set {@code routing.destinationSelector.set(destination)}
 * ("Change Routing Destination"), clear {@code routing.destinationSelector.clear()}, accept check
 * {@code routing.destinationSelector.accepts(Object)}. Input and sidechain: source selector
 * ({@code mixer.channel.input()} or a routing component's {@code routing.selectable.selector()}), set
 * {@code routing.sourceSelector.set(source)} ("Change Routing Source"), clear {@code routing.sourceSelector.clear()},
 * accept check {@code routing.sourceSelector.accepts(Object)}. Both setters silently ignore a cyclic or unchanged value
 * and do not check audio vs note, so kinds are enforced here and every write is read back by identity. Each write runs
 * inside one project transaction {@code project.begin(label,false)} … {@code project.end(false)}, as the GUI wraps it.
 * Hardware inputs (an audio input bus, a controller's note input) go through {@link NativeHardwareInputs}, bound on
 * first use. No UI selection is read or changed. Results are domain records; the plan of an edit is the domain's.
 */
public final class NativeRoutingAccess implements RoutingPort {
    static final int MAX_COMPONENTS = 256;
    private final NativeHost.Access a;
    private final NativeReadContext context;
    private final DeviceSources.Native targets;
    private final NativeTrackResolver resolver;
    private final Class<?> channelType, projectType, portsType, destinationSelector, sourceSelector;
    private final Class<?> audioDestination, noteDestination, preFader, postFader, trackNotes, sourceBus;
    private final Class<?> componentType,
            audioSidechain,
            noteSidechain,
            pluginSidechain,
            audioIn,
            deviceType,
            modulatorType,
            moduleType;
    private final Method channel, begin, end, output, input, audioInput, noteInput, pre, post, notes, trackKind;
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
    private final LazyBinding<NativeHardwareInputs> hardware = new LazyBinding<>("native_unavailable: ");
    private final Method busOwner,
            busName,
            descendants,
            selector,
            componentKinds,
            componentId,
            componentLabel,
            modulatorId,
            moduleId;

    public NativeRoutingAccess(NativeHost.Access a) throws ReflectiveOperationException {
        this.a = a;
        context = new NativeReadContext(new NativeReadContextAccess(a));
        targets = new DeviceSources.Native(new NativeDeviceTargetAccess(a));
        a.requireModelThreadAvailable();
        resolver = new NativeTrackResolver(a);
        Class<?> trackType = a.type("track.mixer");
        channelType = a.type("routing.channel");
        projectType = a.type("project");
        portsType = a.type("document");
        destinationSelector = a.type("routing.destinationSelector");
        sourceSelector = a.type("routing.sourceSelector");
        Class<?> destinationBus = a.type("routing.destination"), bus = a.type("routing.bus");
        Class<?> named = a.type("routing.named");
        sourceBus = a.type("routing.source");
        audioDestination = a.type("routing.audioDestination");
        noteDestination = a.type("routing.noteDestination");
        preFader = a.type("routing.preFader");
        postFader = a.type("routing.postFader");
        trackNotes = a.type("routing.trackNotes");
        componentType = a.type("routing.component");
        audioSidechain = a.type("routing.audioSidechain");
        noteSidechain = a.type("routing.noteSidechain");
        pluginSidechain = a.type("routing.pluginSidechain");
        audioIn = a.type("routing.audioIn");
        deviceType = a.type("device");
        modulatorType = a.type("modulator");
        moduleType = a.type("grid.module");
        Class<?> node = a.type("model.node"), component = a.type("routing.selectable");
        Class<?> labelled = a.type("model.labelled");
        // Ancestry the readback and kind mapping rely on (inspected host hierarchy).
        ancestry(channelType, trackType);
        ancestry(channelType, resolver.channelType());
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
        enumNames(a.type("track.kind"), "INSTRUMENT", "AUDIO", "HYBRID");
        enumNames(
                a.type("routing.sourceGroup"),
                "HARDWARE_NOTE_SOURCES",
                "HARDWARE_AUDIO_SOURCES",
                "TRACK_AUDIO_SOURCES",
                "TRACK_NOTE_SOURCES");
        channel = a.exactMethod("track.mixer.channel", channelType, false);
        begin = a.exactMethod("project.begin", void.class, false, String.class, boolean.class);
        end = a.exactMethod("project.end", void.class, false, boolean.class);
        output = a.exactMethod("routing.channel.output", destinationSelector, false);
        input = a.exactMethod("routing.channel.input", sourceSelector, false);
        audioInput = a.exactMethod("routing.channel.audioInput", audioDestination, false);
        noteInput = a.exactMethod("routing.channel.noteInput", noteDestination, false);
        pre = a.exactMethod("routing.channel.preFader", preFader, false);
        post = a.exactMethod("routing.channel.postFader", postFader, false);
        notes = a.exactMethod("routing.channel.notes", trackNotes, false);
        trackKind = a.exactMethod("routing.channel.kind", a.type("track.kind"), false);
        allIns = a.exactMethod("document.allInputs", a.type("routing.allInputs"), false);
        keyboard = a.exactMethod("document.keyboardInput", a.type("routing.keyboardInput"), false);
        onscreen = a.exactMethod("document.onscreenKeyboard", a.type("routing.onscreenKeyboard"), false);
        outCurrent = a.exactMethod("routing.destinationSelector.current", destinationBus, false);
        outSet = a.exactMethod("routing.destinationSelector.set", void.class, false, destinationBus);
        outClear = a.exactMethod("routing.destinationSelector.clear", void.class, false);
        outAccepts = a.exactMethod("routing.destinationSelector.accepts", boolean.class, false, Object.class);
        inCurrent = a.exactMethod("routing.sourceSelector.current", sourceBus, false);
        inSet = a.exactMethod("routing.sourceSelector.set", void.class, false, sourceBus);
        inClear = a.exactMethod("routing.sourceSelector.clear", void.class, false);
        inAccepts = a.exactMethod("routing.sourceSelector.accepts", boolean.class, false, Object.class);
        busOwner = a.exactMethod("routing.bus.owner", channelType, false);
        busName = a.exactMethod("routing.named.name", String.class, false);
        descendants = a.exactMethod("model.tree.descendants", Stream.class, true, node, Class.class, String.class);
        selector = a.exactMethod("routing.selectable.selector", sourceSelector, false);
        componentKinds = a.exactMethod("routing.component.kinds", EnumSet.class, false);
        componentId = a.exactMethod("model.labelled.id", String.class, false);
        componentLabel = a.exactMethod("model.labelled.label", String.class, false);
        modulatorId = a.exactMethod("modulator.id", UUID.class, false);
        moduleId = a.exactMethod("grid.module.id", UUID.class, false);
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

    @Override
    public NativeProvenance provenance() {
        return a.nativeProvenance();
    }

    /** Public track types whose GUI shows an input chooser, with the native track kind (fP) each must report. */
    private static final Map<String, String> INPUT_TRACK_TYPES =
            Map.of("Audio", "AUDIO", "Instrument", "INSTRUMENT", "Hybrid", "HYBRID");

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

    /** The hardware input bindings, bound on first use; null (with why) where this host lacks one of their keys. */
    NativeHardwareInputs hardware() {
        return hardwareError() == null ? hardware.get(() -> new NativeHardwareInputs(a)) : null;
    }

    private String hardwareError() {
        return hardware.unavailable(() -> new NativeHardwareInputs(a));
    }

    // ---- shared resolution ----

    /** A bank track resolved to its native track model and its channel: the track itself or a group's alias. */
    private final class Channel {
        final TrackRef ref;
        final Object track, channel;

        Channel(TrackRef ref) {
            this.ref = ref;
            track = resolver.resolve(
                    ref.proxy, ref.id, NativeTrackResolver.Kind.CHANNEL, NativeTrackResolver.Kind.GROUP);
            Object expected = resolver.kind(track) == NativeTrackResolver.Kind.GROUP ? resolver.alias(track) : track;
            Object found = a.invoke(NativeRoutingAccess.this.channel, track);
            if (!channelType.isInstance(found) || found != expected)
                throw Errors.notApplied(
                        Reason.NATIVE_UNAVAILABLE,
                        "Native track channel is neither the track nor its group alias: " + ref.id);
            a.requireModelThread(found);
            channel = found;
        }

        boolean same() {
            return resolver.same(ref.proxy, track) && a.invoke(NativeRoutingAccess.this.channel, track) == channel;
        }

        String role() {
            if (track == channel) return "track";
            return resolver.parent(track) == null ? "root_group" : "group";
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
            project = resolver.owner(anchor, projectType);
            if (!projectType.isInstance(project))
                throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "Native routing target has no owning project.");
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
            Object ports = resolver.owner(models[models.length - 1], portsType);
            if (ports != project)
                throw Errors.notApplied(
                        Reason.NATIVE_UNAVAILABLE, "Native note-source owner is not the owning project.");
            return ports;
        }
    }

    private String nameOf(Object bus) {
        if (bus == null) return null;
        String value = (String) a.invoke(busName, bus);
        return value == null ? "" : value;
    }

    private String trackId(Object channelModel) {
        return resolver.identity(channelModel).toString();
    }

    private RoutingEntry other(Object bus) {
        return RoutingEntry.other(nameOf(bus), bus.getClass().getName());
    }

    /** Classify a destination by native identity: a channel's own audio/note input or something else. */
    private RoutingEntry describeDestination(Object bus) {
        if (bus == null) return RoutingEntry.none();
        a.requireModelThread(bus);
        if (audioDestination.isInstance(bus) || noteDestination.isInstance(bus)) {
            Object owning = a.invoke(busOwner, bus);
            boolean audio = audioDestination.isInstance(bus);
            if (owning != null && a.invoke(audio ? audioInput : noteInput, owning) == bus)
                return RoutingEntry.track(audio ? "track_audio_input" : "track_note_input", trackId(owning));
        }
        return other(bus);
    }

    /** Classify a source by native identity: a channel tap, a project note source, a hardware input, or other. */
    private RoutingEntry describeSource(Object bus, Object ports) {
        if (bus == null) return RoutingEntry.none();
        a.requireModelThread(bus);
        for (Object[] tap : new Object[][] {
            {preFader, pre, "pre_fader"}, {postFader, post, "post_fader"}, {trackNotes, notes, "track_notes"}
        }) {
            if (!((Class<?>) tap[0]).isInstance(bus)) continue;
            Object owning = a.invoke(busOwner, bus);
            if (owning != null && a.invoke((Method) tap[1], owning) == bus)
                return RoutingEntry.track((String) tap[2], trackId(owning));
        }
        if (bus == a.invoke(allIns, ports)) return RoutingEntry.source("all_ins");
        if (bus == a.invoke(keyboard, ports)) return RoutingEntry.source("computer_keyboard");
        if (bus == a.invoke(onscreen, ports)) return RoutingEntry.source("onscreen_keyboard");
        NativeHardwareInputs inputs = hardware();
        RoutingEntry entry = inputs == null ? null : inputs.describe(bus);
        return entry != null ? entry : other(bus);
    }

    private Channel find(Map<String, Channel> channels, String id) {
        for (Map.Entry<String, Channel> e : channels.entrySet())
            if (e.getKey().equalsIgnoreCase(id)) return e.getValue();
        return null;
    }

    /** Every resolvable bank channel; unresolvable ones are reported, never guessed. */
    private Map<String, Channel> channels(List<TrackRef> bank, List<Unresolved> unresolved) {
        Map<String, Channel> out = new LinkedHashMap<>();
        for (TrackRef ref : bank) {
            try {
                out.put(ref.id, new Channel(ref));
            } catch (RpcException error) {
                unresolved.add(new Unresolved(ref, error.reason, error.getMessage()));
            }
        }
        return out;
    }

    private Channel requireChannel(Map<String, Channel> channels, RoutingEntry request, String next) {
        Channel found = find(channels, request.track);
        if (found == null)
            throw Errors.notApplied(
                    Reason.TARGET_NOT_FOUND,
                    "The track of " + request + " did not resolve to a native channel; refresh " + next + ".",
                    next);
        return found;
    }

    /** The native destination for a requested output entry, or null for none. */
    private Object destination(RoutingEntry request, Map<String, Channel> channels) {
        if (request.kind.equals("none")) return null;
        Channel to = requireChannel(channels, request, "tracks.routing.get");
        Object bus = a.invoke(request.kind.equals("track_audio_input") ? audioInput : noteInput, to.channel);
        if (bus == null)
            throw Errors.notApplied(Reason.UNSUPPORTED_SCOPE, "Destination track has no native " + request.kind + ".");
        a.requireModelThread(bus);
        return bus;
    }

    /** A resolved source target: a shared source bus (null for none) or a listed hardware input. */
    private static final class Source {
        final Object bus;
        final NativeHardwareInputs.Input hardware;

        Source(Object bus, NativeHardwareInputs.Input hardware) {
            this.bus = bus;
            this.hardware = hardware;
        }
    }

    private Source source(RoutingEntry request, Map<String, Channel> channels, Object ports, String next) {
        Object bus;
        switch (request.kind) {
            case "none":
                return new Source(null, null);
            case "all_ins":
                bus = a.invoke(allIns, ports);
                break;
            case "computer_keyboard":
                bus = a.invoke(keyboard, ports);
                break;
            case "onscreen_keyboard":
                bus = a.invoke(onscreen, ports);
                break;
            case "hardware_audio_input":
            case "hardware_note_input":
                return new Source(null, hardwareInput(request));
            default: {
                Channel from = requireChannel(channels, request, next);
                bus = a.invoke(
                        request.kind.equals("pre_fader") ? pre : request.kind.equals("post_fader") ? post : notes,
                        from.channel);
            }
        }
        if (bus == null || !sourceBus.isInstance(bus))
            throw Errors.notApplied(Reason.UNSUPPORTED_SCOPE, "No native " + request.kind + " source is available.");
        a.requireModelThread(bus);
        return new Source(bus, null);
    }

    /** The listed hardware input a request names by id. */
    private NativeHardwareInputs.Input hardwareInput(RoutingEntry request) {
        NativeHardwareInputs inputs = hardware();
        if (inputs == null)
            throw Errors.notApplied(
                    Reason.NATIVE_UNAVAILABLE, "Hardware input bindings are unavailable: " + hardwareError());
        NativeHardwareInputs.Listing listing =
                request.kind.equals("hardware_audio_input") ? inputs.audio() : inputs.notes();
        if (listing.unavailable != null)
            throw Errors.notApplied(
                    Reason.UNSUPPORTED_SCOPE,
                    "No " + request.kind + " can be listed (" + listing.unavailable + "); nothing was dispatched.");
        for (NativeHardwareInputs.Input input : listing.inputs) if (request.matches(input.entry)) return input;
        throw Errors.notApplied(
                Reason.TARGET_NOT_FOUND,
                "No " + request.kind + " has id " + request.id
                        + " now; read tracks.routing.get with includeChoices for the current ids.",
                "tracks.routing.get");
    }

    private boolean accepts(Method check, Object chooser, Object bus) {
        return Boolean.TRUE.equals(a.invoke(check, chooser, bus));
    }

    private List<Choice> destinationChoices(Object chooser, Channel self, Map<String, Channel> channels) {
        List<Choice> out = new ArrayList<>(List.of(new Choice(RoutingEntry.none(), null, true)));
        for (Channel to : channels.values()) {
            if (to.channel == self.channel) continue;
            for (String kind : List.of("track_audio_input", "track_note_input")) {
                Object bus = a.invoke(kind.equals("track_audio_input") ? audioInput : noteInput, to.channel);
                if (bus == null) continue;
                out.add(new Choice(
                        RoutingEntry.track(kind, trackId(to.channel)), nameOf(bus), accepts(outAccepts, chooser, bus)));
            }
        }
        return out;
    }

    /**
     * What a source chooser offers by its native groups, as Bitwig's menu groups them: project note sources (hardware
     * note group), other tracks' taps (track groups) and, for a track input, the listed hardware inputs.
     */
    private List<Choice> sourceChoices(
            Object chooser, Channel exclude, Map<String, Channel> channels, Object ports, List<String> groups) {
        List<Choice> out = new ArrayList<>(List.of(new Choice(RoutingEntry.none(), null, true)));
        if (groups.contains("HARDWARE_NOTE_SOURCES"))
            for (String kind : List.of("all_ins", "computer_keyboard", "onscreen_keyboard")) {
                Object bus = a.invoke(
                        kind.equals("all_ins") ? allIns : kind.equals("computer_keyboard") ? keyboard : onscreen,
                        ports);
                if (bus != null)
                    out.add(new Choice(RoutingEntry.source(kind), nameOf(bus), accepts(inAccepts, chooser, bus)));
            }
        for (Channel from : channels.values()) {
            if (exclude != null && from.channel == exclude.channel) continue;
            for (String kind : List.of("pre_fader", "post_fader", "track_notes")) {
                if (!groups.contains(kind.equals("track_notes") ? "TRACK_NOTE_SOURCES" : "TRACK_AUDIO_SOURCES"))
                    continue;
                Object bus = a.invoke(
                        kind.equals("pre_fader") ? pre : kind.equals("post_fader") ? post : notes, from.channel);
                if (bus != null)
                    out.add(new Choice(
                            RoutingEntry.track(kind, trackId(from.channel)),
                            nameOf(bus),
                            accepts(inAccepts, chooser, bus)));
            }
        }
        return out;
    }

    /** The hardware rows of a track input and why a kind is not listed (null: listed). */
    private Hardware hardwareChoices(List<String> groups, List<Choice> out) {
        String[] why = new String[2];
        String[][] kinds = {{"HARDWARE_AUDIO_SOURCES", "audio"}, {"HARDWARE_NOTE_SOURCES", "notes"}};
        for (int i = 0; i < kinds.length; i++) {
            if (!groups.contains(kinds[i][0])) {
                why[i] = "this_input_takes_no_hardware_" + kinds[i][1];
                continue;
            }
            NativeHardwareInputs inputs = hardware();
            if (inputs == null) {
                why[i] = hardwareError();
                continue;
            }
            NativeHardwareInputs.Listing listing = i == 0 ? inputs.audio() : inputs.notes();
            why[i] = listing.unavailable;
            for (NativeHardwareInputs.Input input : listing.inputs)
                out.add(new Choice(input.entry, input.entry.name, true));
        }
        return new Hardware(why[0], why[1]);
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
                throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "Native channel has no destination selector.");
            a.requireModelThread(outputChooser);
            String expectedKind = INPUT_TRACK_TYPES.get(self.ref.type);
            Object chooser = null;
            String reason = null;
            if (expectedKind == null) reason = "no_input_chooser_for_track_type_" + self.ref.type;
            else if (!expectedKind.equals(nativeKind))
                reason = "native_track_kind_" + nativeKind + "_differs_from_public_type_" + self.ref.type;
            else if (!"track".equals(self.role())) reason = "native_role_" + self.role();
            else {
                chooser = a.invoke(input, self.channel);
                if (!sourceSelector.isInstance(chooser))
                    throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "Native channel has no source selector.");
                a.requireModelThread(chooser);
            }
            inputChooser = chooser;
            inputUnavailable = reason;
            inputGroups = chooser == null ? List.of() : trackSourceGroups(nativeKind);
        }

        boolean same() {
            return self.same()
                    && a.invoke(output, self.channel) == outputChooser
                    && (inputChooser == null || a.invoke(input, self.channel) == inputChooser);
        }
    }

    private TrackRouting snapshot(
            TrackView view, Object ports, Map<String, Channel> channels, boolean choices, List<Unresolved> unresolved) {
        Object currentOutput = a.invoke(outCurrent, view.outputChooser);
        Chooser output = new Chooser(
                describeDestination(currentOutput),
                nameOf(currentOutput),
                List.of(),
                choices ? destinationChoices(view.outputChooser, view.self, channels) : List.of());
        Chooser input = null;
        Hardware hardware = new Hardware(null, null);
        if (view.inputChooser != null) {
            Object currentInput = a.invoke(inCurrent, view.inputChooser);
            List<Choice> offered = choices
                    ? sourceChoices(view.inputChooser, view.self, channels, ports, view.inputGroups)
                    : new ArrayList<>();
            if (choices) hardware = hardwareChoices(view.inputGroups, offered);
            input = new Chooser(describeSource(currentInput, ports), nameOf(currentInput), view.inputGroups, offered);
        }
        return new TrackRouting(
                view.self.ref.type,
                view.nativeKind,
                view.self.role(),
                output,
                input,
                view.inputUnavailable,
                unresolved,
                hardware);
    }

    @Override
    public TrackRouting readTrack(TrackRef ref, List<TrackRef> bank, boolean choices, Runnable check) {
        a.documentThread();
        check.run();
        Channel self = new Channel(ref);
        TrackView view = new TrackView(self);
        Scope scope = new Scope(self.channel, List.of(self.track), check);
        Object ports = scope.ports();
        List<Unresolved> unresolved = new ArrayList<>();
        Map<String, Channel> channels = choices ? channels(bank, unresolved) : Map.of();
        TrackRouting out = snapshot(view, ports, channels, choices, unresolved);
        scope.guard(check);
        if (!self.same())
            throw Errors.notApplied(Reason.TARGET_CHANGED, "Native track changed during the routing read.");
        return out;
    }

    /** One resolved chooser write: the chooser and what to put in it. */
    private static final class Write {
        final Object chooser, destination;
        final boolean output;
        final Source source;

        Write(Object chooser, boolean output, Object destination, Source source) {
            this.chooser = chooser;
            this.output = output;
            this.destination = destination;
            this.source = source;
        }
    }

    private void apply(Write write) {
        if (write.output) {
            if (write.destination == null) a.invoke(outClear, write.chooser);
            else a.invoke(outSet, write.chooser, write.destination);
        } else if (write.source.hardware != null) hardware().set(write.chooser, write.source.hardware);
        else if (write.source.bus == null) a.invoke(inClear, write.chooser);
        else a.invoke(inSet, write.chooser, write.source.bus);
    }

    /** Write every resolved change in one project transaction, between the pre-write checks and the re-guard. */
    private void write(
            Scope scope, String label, List<Write> writes, Runnable check, Runnable beforeWrite, Runnable same) {
        scope.guard(check);
        same.run();
        beforeWrite.run();
        scope.transaction(label, () -> writes.forEach(this::apply));
        scope.guard(check);
        same.run();
    }

    @Override
    public Edit<TrackRouting> editTrack(
            TrackRef ref,
            List<TrackRef> bank,
            Function<TrackRouting, Change> plan,
            Runnable check,
            Runnable beforeWrite) {
        a.documentThread();
        check.run();
        Channel self = new Channel(ref);
        TrackView view = new TrackView(self);
        Map<String, Channel> channels = channels(bank, new ArrayList<>());
        Scope scope = new Scope(self.channel, List.of(self.track), check);
        Object ports = scope.ports();
        TrackRouting before = snapshot(view, ports, Map.of(), false, List.of());
        Change change = plan.apply(before);
        if (change.isEmpty()) return new Edit<>(before, change, before, null);
        List<Write> writes = new ArrayList<>();
        for (Map.Entry<String, RoutingEntry> target : change.targets.entrySet()) {
            boolean out = target.getKey().equals("output");
            if (out) {
                Object bus = destination(target.getValue(), channels);
                if (bus != null && !accepts(outAccepts, view.outputChooser, bus))
                    return new Edit<>(before, change, before, "output");
                writes.add(new Write(view.outputChooser, true, bus, null));
            } else {
                Source source = source(target.getValue(), channels, ports, "tracks.routing.get");
                if (source.bus != null && !accepts(inAccepts, view.inputChooser, source.bus))
                    return new Edit<>(before, change, before, "input");
                writes.add(new Write(view.inputChooser, false, null, source));
            }
        }
        Runnable same = () -> {
            if (!view.same())
                throw Errors.notApplied(
                        Reason.TARGET_CHANGED,
                        "Native track or its routing selectors changed around the routing write.");
        };
        write(scope, "Change Track Routing", writes, check, beforeWrite, same);
        return new Edit<>(before, change, snapshot(view, ports, Map.of(), false, List.of()), null);
    }

    // ---- devices.sidechain ----

    /** A device's routing component on the native side. */
    private final class Part {
        final Object model, chooser;
        final int index;
        final String kind, id, label, host, ownerId;
        final List<String> groups;

        Part(Object model, Object device, int index) {
            this.model = model;
            this.index = index;
            a.requireModelThread(model);
            Object found = a.invoke(selector, model);
            if (found != null) a.requireModelThread(found);
            chooser = found != null && sourceSelector.isInstance(found) ? found : null;
            Object grid = resolver.owner(model, moduleType), mod = resolver.owner(model, modulatorType);
            if (grid != null && resolver.owner(grid, deviceType) == device) {
                host = "grid_module";
                ownerId = a.invoke(moduleId, grid).toString();
            } else if (mod != null && resolver.owner(mod, deviceType) == device) {
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
            List<String> kinds = new ArrayList<>();
            Object set = a.invoke(componentKinds, model);
            if (set instanceof Set) for (Object value : (Set<?>) set) kinds.add(((Enum<?>) value).name());
            groups = kinds;
        }

        Component record(Object ports, Map<String, Channel> channels, boolean choices) {
            Chooser source = null;
            if (chooser != null) {
                Object current = a.invoke(inCurrent, chooser);
                source = new Chooser(
                        describeSource(current, ports),
                        nameOf(current),
                        groups,
                        choices ? sourceChoices(chooser, null, channels, ports, groups) : List.of());
            }
            return new Component(index, id, label, kind, host, ownerId, groups, source);
        }
    }

    private List<Part> parts(Object device) {
        List<Part> out = new ArrayList<>();
        Iterator<?> found = ((Stream<?>) a.invoke(descendants, null, device, componentType, null)).iterator();
        int seen = 0;
        while (found.hasNext()) {
            Object model = found.next();
            if (++seen > MAX_COMPONENTS * 4)
                throw Errors.notApplied(
                        Reason.OUT_OF_BOUNDS, "Device holds more routing components than this version reads.");
            if (!componentType.isInstance(model) || resolver.owner(model, deviceType) != device) continue;
            if (out.size() >= MAX_COMPONENTS)
                throw Errors.notApplied(
                        Reason.OUT_OF_BOUNDS, "Device holds more than " + MAX_COMPONENTS + " sidechain components.");
            out.add(new Part(model, device, out.size()));
        }
        return out;
    }

    @Override
    public Sidechains readSidechains(
            DeviceSources.Source source, List<TrackRef> bank, boolean choices, Runnable check) {
        DeviceSources.Native.Capture device = targets.capture(source, check);
        Scope scope = new Scope(device.device, List.of(), check);
        Object ports = scope.ports();
        List<Unresolved> unresolved = new ArrayList<>();
        Map<String, Channel> channels = choices ? channels(bank, unresolved) : Map.of();
        List<Component> components = new ArrayList<>();
        for (Part part : parts(device.device)) components.add(part.record(ports, channels, choices));
        scope.guard(check);
        targets.guard(device, check);
        return new Sidechains(components, unresolved);
    }

    @Override
    public Edit<Component> editSidechain(
            DeviceSources.Source source,
            List<TrackRef> bank,
            int index,
            Function<Component, Change> plan,
            Runnable check,
            Runnable beforeWrite) {
        DeviceSources.Native.Capture device = targets.capture(source, check);
        List<Part> parts = parts(device.device);
        Object ports = new Scope(device.device, List.of(), check).ports();
        Part part = index < parts.size() ? parts.get(index) : null;
        Component before = part == null ? null : part.record(ports, Map.of(), false);
        Change change = plan.apply(before);
        if (change.isEmpty()) return new Edit<>(before, change, before, null);
        Map<String, Channel> channels = channels(bank, new ArrayList<>());
        Scope scope = new Scope(device.device, List.of(part.model, part.chooser), check);
        Source target = source(change.targets.get("source"), channels, ports, "devices.sidechain.get");
        if (target.bus != null && !accepts(inAccepts, part.chooser, target.bus))
            return new Edit<>(before, change, before, "source");
        Runnable same = () -> {
            if (a.invoke(selector, part.model) != part.chooser)
                throw Errors.notApplied(Reason.TARGET_CHANGED, "Component source selector changed during the write.");
            targets.guard(device, check);
        };
        write(
                scope,
                "Change Sidechain Source",
                List.of(new Write(part.chooser, false, null, target)),
                check,
                beforeWrite,
                same);
        return new Edit<>(before, change, part.record(ports, Map.of(), false), null);
    }
}
