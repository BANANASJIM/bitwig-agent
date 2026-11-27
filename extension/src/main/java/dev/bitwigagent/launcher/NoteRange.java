package dev.bitwigagent.launcher;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.util.function.Predicate;

/**
 * The notes a range covers: onset in [start, end) and pitch in [low, high]. {@link #ALL} is the whole clip, notes
 * outside the loop and play range included.
 */
public final class NoteRange implements Predicate<Note> {
    public static final NoteRange ALL = new NoteRange(Long.MIN_VALUE, Long.MAX_VALUE, 0, 127);

    final long start, end;
    final int low, high;
    /** The bounds as the request spelled them, for messages; null for {@link #ALL}. */
    final BigDecimal shownStart, shownEnd;

    NoteRange(long start, long end, int low, int high) {
        this(start, end, low, high, null, null);
    }

    private NoteRange(long start, long end, int low, int high, BigDecimal shownStart, BigDecimal shownEnd) {
        this.start = start;
        this.end = end;
        this.low = low;
        this.high = high;
        this.shownStart = shownStart != null ? shownStart : BigDecimal.valueOf(Note.beats(start));
        this.shownEnd = shownEnd != null ? shownEnd : BigDecimal.valueOf(Note.beats(end));
    }

    /** The request's {@code range}: "all" or {start, end, pitches?}; null when absent. */
    static NoteRange of(JsonObject p) {
        if (!p.has("range")) return null;
        JsonElement value = p.get("range");
        if (value.isJsonPrimitive()) return ALL;
        JsonObject r = value.getAsJsonObject();
        int low = 0, high = 127;
        if (r.has("pitches")) {
            JsonArray pitches = r.getAsJsonArray("pitches");
            low = pitches.get(0).getAsInt();
            high = pitches.get(1).getAsInt();
        }
        return new NoteRange(
                Note.ticks(r.get("start").getAsDouble()),
                Note.ticks(r.get("end").getAsDouble()),
                low,
                high,
                r.get("start").getAsBigDecimal(),
                r.get("end").getAsBigDecimal());
    }

    @Override
    public boolean test(Note n) {
        return n.beat >= start && n.beat < end && n.pitch >= low && n.pitch <= high;
    }

    boolean all() {
        return this == ALL;
    }

    /** The range as the request spelled it, echoed in results. */
    JsonElement json() {
        if (all()) return new com.google.gson.JsonPrimitive("all");
        JsonObject out = new JsonObject();
        out.addProperty("start", Note.beats(start));
        out.addProperty("end", Note.beats(end));
        if (low != 0 || high != 127) {
            JsonArray pitches = new JsonArray();
            pitches.add(low);
            pitches.add(high);
            out.add("pitches", pitches);
        }
        return out;
    }
}
