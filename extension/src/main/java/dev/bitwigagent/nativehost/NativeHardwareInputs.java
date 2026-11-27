package dev.bitwigagent.nativehost;

import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.routing.RoutingEntry;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * The hardware entries of a track's input chooser, as Bitwig's own Track I/O menu builds them from the preferences:
 * the audio backend's configured input buses (PipeWire, JACK; by bus UUID) and every controller's note inputs (by
 * controller UUID, instance and note input index). A bus is written as the descriptor {@code prefs.audioBus.descriptor}
 * makes through {@code routing.sourceSelector.setHardwareAudio}, a note input through its own
 * {@code prefs.noteInput.assign} (the GUI's path). Readback reads the bus UUID of every descriptor the chooser's own
 * hardware source holds ({@code routing.busDescriptor.uuid}) or its controller descriptor. These keys were added after
 * the supported hosts shipped, so they are bound here on first use and a missing one only disables hardware entries.
 * Nothing here writes preferences; "Add MIDI Controller" (a preferences change) is not offered.
 */
final class NativeHardwareInputs {
    /** The parameter types of the note-input setter a note input calls with its own identity. */
    private static final Class<?>[] IDENTITY = {
        String.class, String.class, String.class, UUID.class, int.class, String.class, int.class, int.class
    };

    /** A listed hardware input: its entry and the host object that writes it (a bus or a note input). */
    static final class Input {
        final RoutingEntry entry;
        final Object handle;

        Input(RoutingEntry entry, Object handle) {
            this.entry = entry;
            this.handle = handle;
        }
    }

    /** The listed inputs of one kind, or none with the reason they cannot be listed. */
    static final class Listing {
        final List<Input> inputs;
        final String unavailable;

        Listing(List<Input> inputs, String unavailable) {
            this.inputs = inputs;
            this.unavailable = unavailable;
        }
    }

    private final NativeHost.Access a;
    private final Class<?> audioSource, controllerSource, controllerDescriptor, noteTarget, busDescriptorType;
    private final Method audioSettings, backend, configuredBuses, buses, inputBuses, busItems, busUuid, busName;
    private final Method busDescriptor, setAudio, audioDescriptors, descriptorBus;
    private final Method application, controllerSettings, controllers, controllerList, noteInputs, assign;
    private final Method controllerDescriptors, descriptorUuid, descriptorInstance, descriptorNoteInput, descriptorName;

    NativeHardwareInputs(NativeHost.Access a) throws ReflectiveOperationException {
        this.a = a;
        Class<?> settings = a.type("prefs.audioSettings"), backendType = a.type("prefs.audioBackend");
        Class<?> busesType = a.type("prefs.audioBuses"), busList = a.type("prefs.audioBusList");
        Class<?> port = a.type("routing.hardwarePort"), descriptor = a.type("routing.hardwareDescriptor");
        audioSource = a.type("routing.hardwareAudioSource");
        controllerSource = a.type("routing.controllerSource");
        controllerDescriptor = a.type("routing.controllerDescriptor");
        noteTarget = a.type("routing.noteInputTarget");
        Class<?> sourceSelector = a.type("routing.sourceSelector"), busDescriptorType = a.type("routing.busDescriptor");
        if (!port.isAssignableFrom(descriptor) || !descriptor.isAssignableFrom(busDescriptorType))
            throw new NoSuchMethodException("Inspected hardware descriptor ancestry differs.");
        if (!noteTarget.isAssignableFrom(sourceSelector))
            throw new NoSuchMethodException("Inspected source selector is not a note input target.");
        audioSettings = a.exactMethod("prefs.audio.settings", settings, true);
        backend = a.exactMethod("prefs.audioSettings.backend", backendType, false);
        configuredBuses = a.exactMethod("prefs.audioBackend.configuredBuses", boolean.class, false);
        buses = a.exactMethod("prefs.audioBackend.buses", busesType, false);
        inputBuses = a.exactMethod("prefs.audioBuses.inputs", busList, false);
        busItems = a.exactMethod("prefs.audioBusList.items", List.class, false);
        busUuid = a.exactMethod("prefs.audioBus.uuid", UUID.class, false);
        busName = a.exactMethod("prefs.audioBus.name", String.class, false);
        busDescriptor = a.exactMethod("prefs.audioBus.descriptor", busDescriptorType, false, backendType);
        setAudio = a.exactMethod("routing.sourceSelector.setHardwareAudio", void.class, false, port);
        audioDescriptors = a.exactMethod("routing.hardwareAudioSource.descriptors", List.class, false);
        descriptorBus = a.exactMethod("routing.busDescriptor.uuid", UUID.class, false);
        this.busDescriptorType = busDescriptorType;
        Class<?> controllerSettingsType = a.type("prefs.controllerSettings");
        Class<?> controllersType = a.type("prefs.controllers");
        application = a.exactMethod("application.singleton", a.type("application"), true);
        controllerSettings = a.exactMethod("application.controllers", controllerSettingsType, false);
        controllers = a.exactMethod("prefs.controllerSettings.controllers", controllersType, false);
        controllerList = a.exactMethod("prefs.controllers.list", List.class, false);
        noteInputs = a.exactMethod("prefs.controller.noteInputs", List.class, false);
        assign = a.exactMethod("prefs.noteInput.assign", void.class, false, noteTarget);
        controllerDescriptors = a.exactMethod("routing.controllerSource.descriptors", List.class, false);
        descriptorUuid = a.exactMethod("routing.controllerDescriptor.uuid", UUID.class, false);
        descriptorInstance = a.exactMethod("routing.controllerDescriptor.instance", int.class, false);
        descriptorNoteInput = a.exactMethod("routing.controllerDescriptor.noteInput", int.class, false);
        descriptorName = a.exactMethod("routing.controllerDescriptor.name", String.class, false);
    }

    private Object currentBackend() {
        return a.invoke(backend, a.invoke(audioSettings, null));
    }

    /** The current audio backend's configured input buses; a backend without them (ALSA) lists none, with why. */
    Listing audio() {
        Object current = currentBackend();
        if (current == null) return new Listing(List.of(), "no_audio_backend");
        if (!Boolean.TRUE.equals(a.invoke(configuredBuses, current)))
            return new Listing(List.of(), "audio_backend_has_no_named_busses");
        List<Input> out = new ArrayList<>();
        for (Object bus : (List<?>) a.invoke(busItems, a.invoke(inputBuses, a.invoke(buses, current)))) {
            UUID id = (UUID) a.invoke(busUuid, bus);
            if (id == null) continue;
            String name = (String) a.invoke(busName, bus);
            out.add(new Input(RoutingEntry.hardware("hardware_audio_input", id.toString(), name), bus));
        }
        return new Listing(out, out.isEmpty() ? "no_named_input_busses_add_one_in_settings_audio" : null);
    }

    /**
     * The note inputs of every controller in the preferences' controller list, each identified by what it writes into
     * a chooser. Two inputs with one identity are not told apart by guessing: then none is listed.
     */
    Listing notes() {
        Object settings = a.invoke(controllerSettings, a.invoke(application, null));
        List<Input> out = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Object controller : (List<?>) a.invoke(controllerList, a.invoke(controllers, settings)))
            for (Object input : (List<?>) a.invoke(noteInputs, controller)) {
                Input listed = new Input(identity(input), input);
                if (!ids.add(listed.entry.id.toLowerCase(Locale.ROOT)))
                    return new Listing(List.of(), "controller_note_inputs_share_an_identity");
                out.add(listed);
            }
        return new Listing(out, null);
    }

    /**
     * A note input's identity, captured from the call it makes on a chooser: it is handed a stand-in chooser that
     * only records the arguments of the note-input setter, so nothing is written.
     */
    private RoutingEntry identity(Object noteInput) {
        Object[][] seen = new Object[1][];
        Object probe =
                Proxy.newProxyInstance(noteTarget.getClassLoader(), new Class<?>[] {noteTarget}, (self, m, args) -> {
                    if (m.getDeclaringClass() == Object.class) {
                        if (m.getName().equals("equals")) return self == args[0];
                        if (m.getName().equals("hashCode")) return System.identityHashCode(self);
                        return "routing-identity-probe";
                    }
                    if (Arrays.equals(m.getParameterTypes(), IDENTITY)) seen[0] = args;
                    Class<?> type = m.getReturnType();
                    if (type == boolean.class) return false;
                    if (type == int.class) return 0;
                    return null;
                });
        a.invoke(assign, noteInput, probe);
        if (seen[0] == null)
            throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "A controller note input did not report its identity.");
        return RoutingEntry.hardware(
                "hardware_note_input", noteId((UUID) seen[0][3], (int) seen[0][4], (int) seen[0][6]), (String)
                        seen[0][2]);
    }

    static String noteId(UUID controller, int instance, int noteInput) {
        return controller + "/" + instance + "/" + noteInput;
    }

    /**
     * The hardware entry a chooser's current source is, or null when it is no hardware source named here (then it
     * reads as kind other): an audio source holding the descriptor of a listed bus, or a controller source.
     */
    RoutingEntry describe(Object source) {
        if (audioSource.isInstance(source)) {
            List<UUID> held = new ArrayList<>();
            for (Object item : (List<?>) a.invoke(audioDescriptors, source))
                if (busDescriptorType.isInstance(item)) held.add((UUID) a.invoke(descriptorBus, item));
            return heldBus(held, audio().inputs);
        }
        if (!controllerSource.isInstance(source)) return null;
        for (Object item : (List<?>) a.invoke(controllerDescriptors, source))
            if (controllerDescriptor.isInstance(item))
                return RoutingEntry.hardware(
                        "hardware_note_input",
                        noteId((UUID) a.invoke(descriptorUuid, item), (int) a.invoke(descriptorInstance, item), (int)
                                a.invoke(descriptorNoteInput, item)),
                        (String) a.invoke(descriptorName, item));
        return null;
    }

    /**
     * The listed bus a chooser holds a descriptor of, by bus UUID only: the descriptor keeps the bus name it was written
     * with, which a rename in the preferences makes stale, so the name is display only (the listed, current one).
     */
    static RoutingEntry heldBus(List<UUID> held, List<Input> listed) {
        for (Input bus : listed)
            for (UUID id : held) if (id != null && id.toString().equalsIgnoreCase(bus.entry.id)) return bus.entry;
        return null;
    }

    /** Write a listed input into a source chooser (inside the caller's project transaction). */
    void set(Object chooser, Input input) {
        if (input.entry.kind.equals("hardware_audio_input"))
            a.invoke(setAudio, chooser, a.invoke(busDescriptor, input.handle, currentBackend()));
        else a.invoke(assign, input.handle, chooser);
    }
}
