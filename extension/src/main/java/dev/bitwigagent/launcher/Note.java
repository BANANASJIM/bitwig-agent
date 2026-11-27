package dev.bitwigagent.launcher;

import com.google.gson.JsonObject;
import dev.bitwigagent.core.HostLimits;
import dev.bitwigagent.core.RangeEdit;
import java.util.Comparator;
import java.util.Objects;

/**
 * One note as the clip stores it, the record notes.get reads and notes.set and clips.insert write. Beat and duration
 * are kept in ticks of 1/1048576 beat, the step Bitwig stores them at, and velocity as MIDI 1..127; construction is
 * {@link #canonical}, so a note read back and written again compares equal.
 */
public final class Note {
    /** Ticks per beat: Bitwig stores note times and durations to 1/1048576 beat. */
    public static final long TICKS = Math.round(1 / HostLimits.LIMITS.durationResolution);

    /** Sorted as notes.get lists them: beat, then pitch, then channel. */
    public static final Comparator<Note> ORDER = Comparator.comparingLong((Note n) -> n.beat)
            .thenComparingInt(n -> n.pitch)
            .thenComparingInt(n -> n.channel);

    /** Identity (channel, pitch, beat) and equality (velocity, duration) for the range rule. */
    public static final RangeEdit.Kind<Note> KIND = new RangeEdit.Kind<>() {
        @Override
        public Object key(Note n) {
            return n.key();
        }

        @Override
        public boolean same(Note a, Note b) {
            return a.velocity == b.velocity && a.duration == b.duration;
        }
    };

    public final int channel, pitch, velocity;
    public final long beat, duration;

    private Note(int channel, int pitch, long beat, long duration, int velocity) {
        this.channel = channel;
        this.pitch = pitch;
        this.beat = beat;
        this.duration = duration;
        this.velocity = velocity;
    }

    /** The note Bitwig stores for these values: beat and duration to the nearest tick, velocity a whole MIDI step. */
    public static Note canonical(int channel, int pitch, double beat, double duration, int velocity) {
        return new Note(channel, pitch, ticks(beat), Math.max(1, ticks(duration)), velocity);
    }

    /** A note read from the host: velocity is the host's 0..1 value. */
    public static Note read(int channel, int pitch, double start, double length, double velocity01) {
        return canonical(channel, pitch, start, length, midi(velocity01));
    }

    static Note ofTicks(int channel, int pitch, long beat, long duration, int velocity) {
        return new Note(channel, pitch, beat, duration, velocity);
    }

    public static long ticks(double beats) {
        return Math.round(beats * TICKS);
    }

    public static double beats(long ticks) {
        return ticks / (double) TICKS;
    }

    /** MIDI velocity 1..127 for the host's 0..1 value. */
    public static int midi(double velocity01) {
        return (int) Math.max(1, Math.min(127, Math.round(velocity01 * 127)));
    }

    public long end() {
        return beat + duration;
    }

    public double beatValue() {
        return beats(beat);
    }

    public double durationValue() {
        return beats(duration);
    }

    /** Channel, pitch and onset packed into one value: the note's identity. */
    Long key() {
        return (beat << 11) | ((long) channel << 7) | pitch;
    }

    Note with(int velocity, long duration) {
        return new Note(channel, pitch, beat, duration, velocity);
    }

    /** The record as notes.get lists it; channel 0 is omitted. */
    public JsonObject json() {
        JsonObject out = new JsonObject();
        out.addProperty("beat", beatValue());
        out.addProperty("pitch", pitch);
        out.addProperty("velocity", velocity);
        out.addProperty("duration", durationValue());
        if (channel != 0) out.addProperty("channel", channel);
        return out;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof Note)) return false;
        Note n = (Note) other;
        return channel == n.channel
                && pitch == n.pitch
                && beat == n.beat
                && duration == n.duration
                && velocity == n.velocity;
    }

    @Override
    public int hashCode() {
        return Objects.hash(channel, pitch, beat, duration, velocity);
    }

    @Override
    public String toString() {
        return json().toString();
    }
}
