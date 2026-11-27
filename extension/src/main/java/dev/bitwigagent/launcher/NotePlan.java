package dev.bitwigagent.launcher;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.RangeEdit;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * The domain rules of a note write, host-free. {@link #rules} judges the listed notes on their own, with the Python
 * validator's texts; {@link #plan} judges them against the clip as observed and returns the change of the range rule.
 * The native adapter runs {@link #plan} inside its transaction, the fake port and the public path run the same code.
 */
public final class NotePlan {
    /** One listed note in request order; velocity and duration are null when notes.set omits them. */
    static final class Listed {
        final int index, channel, pitch;
        final long beat;
        final Integer velocity;
        final Long duration;
        /** The requested beat and end, as messages show them; the end is null without a duration. */
        final BigDecimal shown, requestedEnd;

        Listed(int index, JsonObject n) {
            this.index = index;
            this.channel = n.has("channel") ? n.get("channel").getAsInt() : 0;
            this.pitch = n.get("pitch").getAsInt();
            this.beat = Note.ticks(n.get("beat").getAsDouble());
            this.shown = n.get("beat").getAsBigDecimal();
            this.velocity = n.has("velocity") ? n.get("velocity").getAsInt() : null;
            this.duration =
                    n.has("duration") ? Math.max(1, Note.ticks(n.get("duration").getAsDouble())) : null;
            this.requestedEnd = n.has("duration")
                    ? n.get("beat").getAsBigDecimal().add(n.get("duration").getAsBigDecimal())
                    : null;
        }

        Long end() {
            return duration == null ? null : beat + duration;
        }

        Long key() {
            return Note.ofTicks(channel, pitch, beat, 1, 1).key();
        }

        /** The note this entry writes over {@code existing} (null: a new note, which needs both fields). */
        Note resolve(Note existing) {
            if (existing == null) return Note.ofTicks(channel, pitch, beat, duration, velocity);
            return existing.with(
                    velocity == null ? existing.velocity : velocity, duration == null ? existing.duration : duration);
        }
    }

    static List<Listed> listed(JsonArray notes) {
        List<Listed> out = new ArrayList<>(notes.size());
        for (int i = 0; i < notes.size(); i++)
            out.add(new Listed(i, notes.get(i).getAsJsonObject()));
        return out;
    }

    private final List<Listed> listed;
    private final NoteRange range;
    /** The change the last {@link #plan} chose, for the result's summary. */
    RangeEdit.Change<Note> change;

    NotePlan(List<Listed> listed, NoteRange range) {
        this.listed = listed;
        this.range = range;
    }

    static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    static String plain(long ticks) {
        return plain(BigDecimal.valueOf(ticks).divide(BigDecimal.valueOf(Note.TICKS)));
    }

    /**
     * The rules the listed notes break on their own: inside {@code range}, inside {@code length} (beats; null when the
     * clip is not known yet), no repeated (channel, pitch, beat), no overlap among them. Refused together.
     */
    static void rules(List<Listed> listed, NoteRange range, BigDecimal length) {
        List<Object[]> out = new ArrayList<>();
        Long limit = length == null ? null : Note.ticks(length.doubleValue());
        for (Listed n : listed) {
            String at = "notes[" + n.index + "]";
            if (limit != null && n.end() != null && n.end() > limit)
                add(
                        out,
                        n.index,
                        n.beat >= limit
                                ? at + " starts at beat " + plain(n.shown) + ", at or past the clip length "
                                        + plain(length)
                                : at + " ends at beat " + plain(n.requestedEnd) + ", past the clip length "
                                        + plain(length) + ": shorten it to duration <= "
                                        + plain(length.subtract(n.shown)));
            if (range != null && !range.all()) {
                if (n.beat < range.start)
                    add(
                            out,
                            n.index,
                            at + " beat " + plain(n.shown) + " is before range.start " + plain(range.shownStart)
                                    + ": move it inside, or lower range.start");
                else if (n.beat >= range.end)
                    add(
                            out,
                            n.index,
                            at + " beat " + plain(n.shown) + " is outside range [" + plain(range.shownStart) + ", "
                                    + plain(range.shownEnd) + "): move it inside, or raise range.end above "
                                    + plain(n.shown));
                else if (n.pitch < range.low || n.pitch > range.high)
                    add(
                            out,
                            n.index,
                            at + " pitch " + n.pitch + " is outside range.pitches [" + range.low + ", " + range.high
                                    + "]: move it inside, or widen range.pitches");
            }
        }
        Map<Long, Listed> cells = new HashMap<>();
        for (Listed n : listed) {
            Listed first = cells.putIfAbsent(n.key(), n);
            if (first != null)
                add(
                        out,
                        n.index,
                        "notes[" + n.index + "] repeats the channel, beat and pitch of notes[" + first.index
                                + "]: remove one");
        }
        Map<Integer, Listed> holders = new HashMap<>(); // per channel and pitch, the note so far that ends last
        List<Listed> byOnset = new ArrayList<>(listed);
        byOnset.sort(Comparator.comparingLong((Listed n) -> n.beat).thenComparingInt(n -> n.index));
        for (Listed n : byOnset) {
            int row = n.channel * 128 + n.pitch;
            Listed holder = holders.get(row);
            if (holder != null && holder.end() != null && holder.beat != n.beat && holder.end() > n.beat)
                add(
                        out,
                        n.index,
                        "notes[" + n.index + "] starts at beat " + plain(n.shown) + " before notes[" + holder.index
                                + "] on pitch " + n.pitch + " ends at beat " + plain(holder.requestedEnd)
                                + ": shorten notes[" + holder.index + "] to duration <= "
                                + plain(n.shown.subtract(holder.shown)));
            if (n.end() != null && (holder == null || holder.end() == null || n.end() > holder.end()))
                holders.put(row, n);
        }
        refuse(out);
    }

    /**
     * The change that writes the listed notes over {@code observed}, after the rules that need the clip: a new note
     * has velocity and duration, a written note ends by {@code loopEnd} (ticks) and overlaps no note left on its
     * channel and pitch. Pure: the native adapter calls it inside its transaction.
     */
    RangeEdit.Change<Note> plan(List<Note> observed, long loopEnd) {
        Map<Long, Note> byKey = new HashMap<>();
        for (Note n : observed) byKey.putIfAbsent(n.key(), n);
        List<Object[]> out = new ArrayList<>();
        List<Note> notes = new ArrayList<>(listed.size());
        Map<Note, Integer> index = new IdentityHashMap<>();
        for (Listed l : listed) {
            Note existing = byKey.get(l.key());
            if (existing == null && (l.velocity == null || l.duration == null)) {
                add(
                        out,
                        l.index,
                        "notes[" + l.index + "] is a new note (none at beat " + plain(l.shown) + ", pitch " + l.pitch
                                + (l.channel == 0 ? "" : ", channel " + l.channel) + "): give velocity and duration");
                continue;
            }
            Note note = l.resolve(existing);
            notes.add(note);
            index.put(note, l.index);
        }
        refuse(out);
        RangeEdit.Change<Note> planned = RangeEdit.plan(observed, notes, range, Note.KIND);
        List<Note> written = planned.written();
        for (Note n : written) {
            int i = index.get(n);
            if (n.end() > loopEnd)
                add(
                        out,
                        i,
                        n.beat >= loopEnd
                                ? "notes[" + i + "] starts at beat " + plain(n.beat) + ", at or past the clip loop end "
                                        + plain(loopEnd)
                                : "notes[" + i + "] ends at beat " + plain(n.end()) + ", past the clip loop end "
                                        + plain(loopEnd) + ": shorten it to duration <= " + plain(loopEnd - n.beat));
        }
        overlaps(RangeEdit.apply(observed, planned), index, out);
        refuse(out);
        change = planned;
        return planned;
    }

    /** Each written note against its neighbours on its channel and pitch: none may sustain into the next onset. */
    private static void overlaps(List<Note> after, Map<Note, Integer> written, List<Object[]> out) {
        Map<Integer, List<Note>> rows = new HashMap<>();
        for (Note n : after)
            rows.computeIfAbsent(n.channel * 128 + n.pitch, k -> new ArrayList<>())
                    .add(n);
        for (List<Note> row : rows.values()) {
            row.sort(Comparator.comparingLong(n -> n.beat));
            for (int k = 0; k + 1 < row.size(); k++) {
                Note a = row.get(k), b = row.get(k + 1);
                if (a.end() <= b.beat) continue;
                Integer ia = written.get(a), ib = written.get(b);
                if (ia == null && ib == null) continue; // both left as they were
                if (ia != null)
                    add(
                            out,
                            ia,
                            "notes[" + ia + "] on pitch " + a.pitch + " ends at beat " + plain(a.end())
                                    + ", past the onset of " + (ib != null ? "notes[" + ib + "]" : "the note")
                                    + " at beat " + plain(b.beat) + ": shorten notes[" + ia + "] to duration <= "
                                    + plain(b.beat - a.beat)
                                    + (ib != null ? "" : ", or include beat " + plain(b.beat) + " in range"));
                else
                    add(
                            out,
                            ib,
                            "notes[" + ib + "] starts at beat " + plain(b.beat) + " while the note at beat "
                                    + plain(a.beat) + " on pitch " + b.pitch + " sustains to beat " + plain(a.end())
                                    + ": list that note with duration <= " + plain(b.beat - a.beat)
                                    + ", or include beat " + plain(a.beat) + " in range");
            }
        }
    }

    private static void add(List<Object[]> out, int index, String message) {
        out.add(new Object[] {index, message});
    }

    private static void refuse(List<Object[]> problems) {
        if (problems.isEmpty()) return;
        problems.sort(Comparator.comparingInt(v -> (Integer) v[0]));
        StringBuilder message = new StringBuilder("notes: " + problems.size()
                + (problems.size() == 1 ? " problem" : " problems") + ", nothing was written: ");
        for (int i = 0; i < problems.size(); i++)
            message.append(i == 0 ? "" : "; ").append(problems.get(i)[1]);
        throw Errors.invalid(message.toString());
    }
}
