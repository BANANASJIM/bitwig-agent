package dev.bitwigagent;

import java.util.*;

/** Arranger note rows against a fake that keeps Bitwig 6.1.4's (channel, key) order; not native acceptance. */
public final class ArrangerNotesTest {
    private static int assertions;

    public static void main(String[] args) {
        writeAddressesRowsChannelFirst();
        readbackCatchesSwappedRows();
        readbackFlagsMissingAndExtraNotes();
        System.out.println("ArrangerNotesTest: " + assertions + " assertions");
    }

    /** Models YeP.TMF(channel, key, create) / fUX: the first int is the channel (0..15), the second the key (0..127). */
    static final class Native implements ArrangerNotes.Host {
        final boolean assertions;
        final Map<List<Integer>, Row> rows = new LinkedHashMap<>();

        Native(boolean assertions) {
            this.assertions = assertions;
        }

        static final class Row {
            final int channel, key;
            final List<double[]> notes = new ArrayList<>();

            Row(int c, int k) {
                channel = c;
                key = k;
            }
        }

        public Object newNote(double beat, double duration, double velocity) {
            return new double[] {beat, duration, velocity};
        }

        public void addNote(Object clip, Object note, int first, int second) {
            // Release builds run with Java assertions off, so a swapped call silently makes a row with channel 60.
            if (assertions && (first < 0 || first > 15))
                throw new AssertionError("channel " + first + " outside 0..15");
            if (assertions && (second < 0 || second > 127))
                throw new AssertionError("key " + second + " outside 0..127");
            rows.computeIfAbsent(List.of(first, second), k -> new Row(first, second))
                    .notes
                    .add((double[]) note);
        }

        public List<?> rows(Object clip) {
            return new ArrayList<>(rows.values());
        }

        public int channel(Object row) {
            return ((Row) row).channel;
        }

        public int key(Object row) {
            return ((Row) row).key;
        }

        public List<?> notes(Object row) {
            return ((Row) row).notes;
        }

        public double start(Object note) {
            return ((double[]) note)[0];
        }

        public double length(Object note) {
            return ((double[]) note)[1];
        }

        public double velocity01(Object note) {
            return ((double[]) note)[2];
        }
    }

    private static List<double[]> chord() {
        return List.of(
                new double[] {0, 60, 100, 1}, new double[] {1, 64, 100, 1}, new double[] {2, 67, 90, 1}, new double[] {
                    3, 72, 127, .5
                });
    }

    private static void writeAddressesRowsChannelFirst() {
        for (int channel : new int[] {0, 9, 15}) {
            Native host = new Native(true);
            ArrangerNotes.write(host, "clip", chord(), channel);
            check(host.rows.size() == 4, "one row per pitch");
            for (Native.Row row : host.rows.values())
                check(
                        row.channel == channel && row.key >= 60 && row.key <= 72,
                        "row carries the requested channel and the pitch as key");
            ArrangerNotes.Readback readback = ArrangerNotes.verify(host, "clip", chord(), channel);
            check(
                    readback.complete
                            && readback.invalidRows == 0
                            && readback.json.get("verified").getAsInt() == 4,
                    "channel-first write reads back complete");
        }
    }

    private static void readbackCatchesSwappedRows() {
        // The 0fcbad3 call order (pitch, channel): rows end up with channel 60 and key 0.
        Native host = new Native(false);
        for (double[] n : chord()) host.addNote("clip", host.newNote(n[0], n[3], n[2] / 127.0), (int) n[1], 0);
        ArrangerNotes.Readback readback = ArrangerNotes.verify(host, "clip", chord(), 0);
        check(
                readback.invalidRows == 4
                        && !readback.complete
                        && readback.json.get("verified").getAsInt() == 0,
                "swapped rows are invalid and never count as verified, whatever lookup order a reader uses");
    }

    private static void readbackFlagsMissingAndExtraNotes() {
        Native host = new Native(true);
        ArrangerNotes.write(host, "clip", chord().subList(0, 3), 0);
        ArrangerNotes.Readback missing = ArrangerNotes.verify(host, "clip", chord(), 0);
        check(
                !missing.complete
                        && missing.json.getAsJsonArray("unverifiedNotes").size() == 1,
                "a missing note is reported");
        Native wrongChannel = new Native(true);
        ArrangerNotes.write(wrongChannel, "clip", chord(), 1);
        check(
                !ArrangerNotes.verify(wrongChannel, "clip", chord(), 0).complete,
                "notes on another channel do not verify");
        Native extra = new Native(true);
        ArrangerNotes.write(extra, "clip", chord(), 0);
        extra.addNote("clip", extra.newNote(3.5, .5, .5), 0, 40);
        check(!ArrangerNotes.verify(extra, "clip", chord(), 0).complete, "an extra note in the clip fails the count");
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
}
