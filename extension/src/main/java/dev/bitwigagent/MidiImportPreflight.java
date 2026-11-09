package dev.bitwigagent;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;

/** Reject MIDI files the host may silently ignore before acquiring a mutation lease. */
final class MidiImportPreflight {
    static void validate(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".mid") && !name.endsWith(".midi")) return;
        final Sequence sequence;
        try {
            sequence = MidiSystem.getSequence(path.toFile());
        } catch (IOException | InvalidMidiDataException error) {
            throw HostServices.failure("invalid_midi_file", "MIDI import requires a readable Standard MIDI File.");
        }
        for (Track track : sequence.getTracks()) {
            for (int i = 0; i < track.size(); i++) {
                MidiEvent event = track.get(i);
                if (event.getMessage() instanceof ShortMessage) {
                    ShortMessage message = (ShortMessage) event.getMessage();
                    int command = message.getCommand();
                    if (command == ShortMessage.NOTE_ON && message.getData2() > 0) return;
                    // Preserve controller/program/pitch MIDI imports; only empty or note-off-only files
                    // are known to contain no material from which to create a launcher clip.
                    if (message.getStatus() < 0xf0 && command != ShortMessage.NOTE_ON
                            && command != ShortMessage.NOTE_OFF) return;
                }
            }
        }
        throw HostServices.failure("empty_midi_file",
                "MIDI import requires a positive-velocity note-on or another channel event; use launcher.create for an empty timed clip.");
    }

    private MidiImportPreflight() {}
}
