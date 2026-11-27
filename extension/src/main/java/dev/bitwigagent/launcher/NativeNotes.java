package dev.bitwigagent.launcher;

import dev.bitwigagent.core.HostOperation;
import dev.bitwigagent.core.RangeEdit;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.core.RpcException;
import dev.bitwigagent.host.NativeRuntime;
import dev.bitwigagent.nativehost.NativeNoteWriteAccess;
import java.util.List;
import java.util.function.Function;

/**
 * Launcher note reads and writes through the native document: a read sees every note of the clip without selecting
 * it, and a write is one Bitwig Undo step. Without the exact bindings the note methods fall back to the public API.
 */
public final class NativeNotes {
    /**
     * How a write reached Bitwig's Undo history, as reported in results: one step for the whole call, one per note, a
     * public clip insert's separate steps (create, length, name, stop, then one per note), or none at all.
     */
    public static final String ONE_STEP = "one_step", PER_NOTE = "per_note", PER_STEP = "per_step", NONE = "none";

    /** A launcher note clip as the document holds it: every note of every row, and the loop. */
    public static final class Observed {
        public final List<Note> notes;
        public final double loopStart, loopLength;

        public Observed(List<Note> notes, double loopStart, double loopLength) {
            this.notes = notes;
            this.loopStart = loopStart;
            this.loopLength = loopLength;
        }

        long loopEnd() {
            return Note.ticks(loopStart + loopLength);
        }
    }

    /** Exact native note bindings; every method but {@link #execute} runs on the native document thread. */
    public interface Access {
        void execute(Runnable work);

        /** The record keys this host's bindings cannot read or write (a feature group that does not bind). */
        List<String> unread();

        /** Resolves the slot's clip (track UUID, clip name and slot checked) and reads every note of it. */
        Observed read(Object slot, String trackId, String expectedName, Runnable check);

        /**
         * Resolves and reads the clip, asks {@code plan} for the change and, when it is not empty, runs
         * {@code beforeWrite} and writes it in one undo transaction; returns the clip read back afterwards (the
         * first read when nothing changed). {@code plan} may refuse, before anything is dispatched.
         */
        Observed edit(
                Object slot,
                String trackId,
                String expectedName,
                Runnable check,
                Function<Observed, RangeEdit.Change<Note>> plan,
                Runnable beforeWrite);

        /**
         * Creates a named note clip of {@code clipLength} in the empty slot holding {@code notes}, one undo step;
         * returns the clip read back.
         */
        Observed create(
                Object slot,
                String trackId,
                String name,
                int clipLength,
                List<Note> notes,
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
            access = new NativeNoteWriteAccess(natives.get(op).access());
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
