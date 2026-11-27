package dev.bitwigagent.launcher;

import dev.bitwigagent.bitwig614.Bitwig614NoteWriteAccess;
import dev.bitwigagent.core.HostOperation;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.core.RpcException;
import dev.bitwigagent.host.NativeRuntime;
import java.util.List;

/**
 * Launcher note writes as one Bitwig Undo step: every cell of a request is written natively inside one undo
 * transaction. Without the exact bindings the note methods fall back to the public per-note setters.
 */
public final class NativeNotes {
    /**
     * How a write reached Bitwig's Undo history, as reported in results: one step for the whole call, one per note, a
     * public clip insert's separate steps (create, length, name, stop, then one per note), or none at all.
     */
    public static final String ONE_STEP = "one_step", PER_NOTE = "per_note", PER_STEP = "per_step", NONE = "none";

    /** One requested cell: the absolute grid step {@code step} of {@code stepBeats}, as the public setter takes it. */
    public static final class Cell {
        public final int channel, pitch, velocity, cells;
        public final long step;
        public final double stepBeats, durationBeats;
        public final boolean set;

        public Cell(
                int channel,
                long step,
                double stepBeats,
                int pitch,
                boolean set,
                int velocity,
                double durationBeats,
                int cells) {
            this.channel = channel;
            this.step = step;
            this.stepBeats = stepBeats;
            this.pitch = pitch;
            this.set = set;
            this.velocity = velocity;
            this.durationBeats = durationBeats;
            this.cells = cells;
        }
    }

    /**
     * A cell as the clip holds it before the write: the onsets inside the cell (the first one's velocity 0..1 and
     * duration), whether an earlier note sustains into it, and whether a set would run into a later onset.
     */
    public static final class Observed {
        public final int onsets;
        public final double velocity, durationBeats;
        public final boolean sustained, collides;

        public Observed(int onsets, double velocity, double durationBeats, boolean sustained, boolean collides) {
            this.onsets = onsets;
            this.velocity = velocity;
            this.durationBeats = durationBeats;
            this.sustained = sustained;
            this.collides = collides;
        }
    }

    /** Decides from the observed cells which ones to write; throws to refuse the whole request. */
    public interface Plan {
        boolean[] write(double loopStart, double loopEnd, List<Observed> cells);
    }

    /** Exact native note bindings; every method but {@link #execute} runs on the native document thread. */
    public interface Access {
        void execute(Runnable work);

        /**
         * Resolves the slot's clip, observes every cell, and writes the cells {@code plan} picks in one undo
         * transaction; {@code beforeWrite} runs right before it opens. Returns the cells written.
         */
        boolean[] edit(
                Object slot,
                String trackId,
                String expectedName,
                List<Cell> cells,
                Runnable check,
                Plan plan,
                Runnable beforeWrite);

        /** Creates a named note clip of {@code lengthBeats} in the empty slot and writes {@code cells}, one undo step. */
        void create(
                Object slot,
                String trackId,
                String name,
                int lengthBeats,
                List<Cell> cells,
                Runnable check,
                Runnable beforeWrite);
    }

    private final NativeRuntime natives;
    private Access access;
    private String unavailable;

    public NativeNotes(NativeRuntime natives, Access access) {
        this.natives = natives;
        this.access = access;
    }

    /** The bindings, or null when they do not match the loaded host (the caller then uses the public setters). */
    Access access(HostOperation op) {
        if (access != null || unavailable != null) return access;
        try {
            access = new Bitwig614NoteWriteAccess(natives.get(op).access());
        } catch (ReflectiveOperationException | LinkageError error) {
            unavailable = error.toString();
        } catch (RpcException error) {
            if (!error.is(Reason.NATIVE_UNAVAILABLE) && !error.is(Reason.HOST_MISMATCH)) throw error;
            unavailable = error.getMessage();
        }
        return access;
    }

    /** Why the native bindings are unavailable; null when they are bound or not yet tried. */
    String unavailable() {
        return unavailable;
    }

    /** The {@code undo} value of a write that dispatched {@code dispatched} notes. */
    static String undo(boolean nativeWrite, int dispatched) {
        return dispatched == 0 ? NONE : nativeWrite ? ONE_STEP : PER_NOTE;
    }
}
