package dev.bitwigagent.launcher;

import com.bitwig.extension.controller.api.Clip;
import com.bitwig.extension.controller.api.NoteStep;
import dev.bitwigagent.core.HostLimits;

/** The public note grid of the launcher cursor clip: its windows and how its cells read. Host-free except reads. */
public final class NoteGrid {
    private NoteGrid() {}

    public static final double BEAT_GRID = HostLimits.LIMITS.beatGrid;
    public static final String BEAT_GRID_LABEL = "1/" + Math.round(1 / BEAT_GRID);

    /** A window of the grid: {@code steps} cells of {@code size} beats from step {@code start}, over a pitch range. */
    static final class Window {
        final int channel, start, steps, pitch, pitches;
        final double size;

        Window(int channel, double size, int start, int steps, int pitch, int pitches) {
            this.channel = channel;
            this.size = size;
            this.start = start;
            this.steps = steps;
            this.pitch = pitch;
            this.pitches = pitches;
        }
    }

    /** Whole clip loop over all pitches at the finest of 1/32 and 1/16 note that fits 128 steps; never coarser. */
    static Window loopWindow(Clip clip, int channel) {
        double start = clip.getLoopStart().get(), length = clip.getLoopLength().get();
        double size = length / 0.125 <= 128 + 1e-9 ? 0.125 : 0.25;
        return new Window(
                channel,
                size,
                (int) Math.floor(start / size + 1e-9),
                Math.max(1, Math.min(128, (int) Math.ceil(length / size - 1e-9))),
                0,
                128);
    }

    // Matched by constant name: the host jar flattens NoteStep.State, so only the name resolves with both jars.
    static boolean hasState(NoteStep note, String name) {
        return note.state().name().equals(name);
    }
}
