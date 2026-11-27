package dev.bitwigagent.arranger;

import com.google.gson.*;
import dev.bitwigagent.core.Verification;
import dev.bitwigagent.nativehost.NativeArrangerAccess;
import java.util.*;

/**
 * Note writes into a native arranger note clip. Bitwig 6.1.4 addresses a note row as (channel, key): the clip's
 * insert-note call and the row factory both take the MIDI channel first and assert it is 0..15. Readback walks
 * every row and reads channel and key through their own getters, so it does not depend on that argument order.
 */
public final class ArrangerNotes {
    private ArrangerNotes() {}

    public interface Host {
        Object newNote(double beat, double duration, double velocity01);
        /** Native {@code AGr.MRY(note, channel, key, selection)}. */
        void addNote(Object clip, Object note, int channel, int key);

        List<?> rows(Object clip);

        int channel(Object row);

        int key(Object row);

        List<?> notes(Object row);

        double start(Object note);

        double length(Object note);

        double velocity01(Object note);
    }

    /** Notes are {beat, pitch, velocity, duration}. */
    public static void write(Host host, Object clip, List<double[]> notes, int channel) {
        for (double[] n : notes) host.addNote(clip, host.newNote(n[0], n[3], n[2] / 127.0), channel, (int) n[1]);
    }

    public static final class Readback {
        public final JsonObject json;
        /** Rows with a channel outside 0..15 or a key outside 0..127: Bitwig's editor cannot draw them. */
        public final int invalidRows;

        public final boolean complete;

        Readback(JsonObject json, int invalidRows, boolean complete) {
            this.json = json;
            this.invalidRows = invalidRows;
            this.complete = complete;
        }
    }

    public static Readback verify(Host host, Object clip, List<double[]> notes, int channel) {
        int invalid = 0, count = 0;
        List<double[]> seen = new ArrayList<>();
        for (Object row : host.rows(clip)) {
            int c = host.channel(row), k = host.key(row);
            List<?> events = host.notes(row);
            if (c < 0 || c > 15 || k < 0 || k > 127) invalid++;
            for (Object note : events) {
                count++;
                seen.add(new double[] {c, k, host.start(note), host.length(note), host.velocity01(note) * 127.0});
            }
        }
        int verified = 0;
        JsonArray missing = new JsonArray();
        for (double[] n : notes) {
            boolean ok = false;
            for (double[] s : seen)
                ok |= s[0] == channel
                        && s[1] == n[1]
                        && near(s[2], n[0])
                        && near(s[3], n[3])
                        && Math.abs(s[4] - n[2]) < 0.5;
            if (ok) verified++;
            else {
                JsonObject m = new JsonObject();
                m.addProperty("beat", n[0]);
                m.addProperty("pitch", (int) n[1]);
                missing.add(m);
            }
        }
        JsonObject out = new JsonObject();
        out.addProperty("requested", notes.size());
        out.addProperty("verified", verified);
        out.addProperty("notesInClip", count);
        out.addProperty("channel", channel);
        out.add("unverifiedNotes", missing);
        out.addProperty("invalidRows", invalid);
        Verification.NATIVE_READBACK.put(out);
        return new Readback(out, invalid, invalid == 0 && verified == notes.size() && count == notes.size());
    }

    private static boolean near(double a, double b) {
        return Math.abs(a - b) <= NativeArrangerAccess.EPS;
    }
}
