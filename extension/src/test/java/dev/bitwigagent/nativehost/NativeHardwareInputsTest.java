package dev.bitwigagent.nativehost;

import static org.junit.jupiter.api.Assertions.*;

import dev.bitwigagent.routing.RoutingEntry;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The host-free rule that reads a chooser's held bus descriptors back as a listed bus. */
final class NativeHardwareInputsTest {
    static final UUID BUS = UUID.fromString("0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0");

    @Test
    void aHeldDescriptorIsTheListedBusByUuidWhateverNameItStored() {
        RoutingEntry renamed =
                RoutingEntry.hardware("hardware_audio_input", BUS.toString().toUpperCase(), "X1 Bus");
        List<NativeHardwareInputs.Input> listed = List.of(
                new NativeHardwareInputs.Input(
                        RoutingEntry.hardware(
                                "hardware_audio_input", UUID.randomUUID().toString(), "Stereo 2"),
                        null),
                new NativeHardwareInputs.Input(renamed, null));
        assertSame(renamed, NativeHardwareInputs.heldBus(List.of(BUS), listed), "matched by UUID, current name");
        assertNull(NativeHardwareInputs.heldBus(List.of(UUID.randomUUID()), listed), "another bus reads as other");
        java.util.ArrayList<UUID> unknown = new java.util.ArrayList<>();
        unknown.add(null);
        assertNull(NativeHardwareInputs.heldBus(unknown, listed), "a descriptor without a bus UUID is no bus");
    }
}
