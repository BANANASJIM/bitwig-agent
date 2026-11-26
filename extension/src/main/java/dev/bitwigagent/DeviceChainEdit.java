package dev.bitwigagent;

import static dev.bitwigagent.HostServices.failure;

import com.google.gson.*;
import java.util.Objects;

/**
 * Observed-delta verification for device-chain edits (insert, delete, move) over a window of observed slots.
 * Pure: it reads a {@link View} of host observations and never touches the host API, so one set of rules serves
 * the top-level 16-slot bank and a layer's 2-slot child window. Names and types are the only observable identity.
 */
final class DeviceChainEdit {
    static final int INSERT = 0, DELETE = 1, MOVE = 2;

    private DeviceChainEdit() {}

    /** Observations of one chain: slots 0..window-1 of a bank whose total size is count(). */
    interface View {
        int count();

        int window();

        long countSequence();

        boolean known(int slot);

        boolean exists(int slot);

        boolean ready(int slot);

        String name(int slot);

        String type(int slot);

        long nameSequence(int slot);
    }

    /** The chain as observed immediately before dispatch. */
    static final class Before {
        final int count;
        final long countSequence;
        final String[] names, types;
        final long[] nameSequences;

        Before(View v) {
            count = v.count();
            countSequence = v.countSequence();
            int seen = Math.min(count, v.window());
            names = new String[seen];
            types = new String[seen];
            for (int i = 0; i < seen; i++) {
                names[i] = v.name(i);
                types[i] = v.type(i);
            }
            nameSequences = new long[Math.min(count + 1, v.window())];
            for (int i = 0; i < nameSequences.length; i++) nameSequences[i] = v.nameSequence(i);
        }

        /** Complete, consistent window or a fail-closed error; getters are never evidence on their own. */
        static Before capture(View v, String staleMessage) {
            int count = v.count();
            if (count < 0) throw failure("not_ready", "Device count has not been observed.");
            for (int i = 0; i < v.window(); i++) {
                if (!v.known(i) || (i < count && !v.ready(i)))
                    throw failure("not_ready", "Device bank has not reported the complete window.");
                if (v.exists(i) != (i < count)) throw failure("stale_context", staleMessage);
            }
            return new Before(v);
        }
    }

    /** What was dispatched: an insertion at `index`, the deletion of `index`, or a move of `index` to `to`. */
    static final class Plan {
        final int kind, index, to;
        final Before before;
        final String[] expectedNames, expectedTypes;

        private Plan(int kind, int index, int to, Before before) {
            this.kind = kind;
            this.index = index;
            this.to = to;
            this.before = before;
            if (kind != MOVE) {
                expectedNames = null;
                expectedTypes = null;
                return;
            }
            int n = before.names.length;
            expectedNames = permuted(before.names, index, to, n);
            expectedTypes = permuted(before.types, index, to, n);
        }

        static Plan insert(int index, Before before) {
            return new Plan(INSERT, index, index, before);
        }

        static Plan delete(int index, Before before) {
            return new Plan(DELETE, index, index, before);
        }

        static Plan move(int from, int to, Before before) {
            return new Plan(MOVE, from, to, before);
        }

        /** A move whose result cannot be told apart from the chain before it, by name and type alone. */
        boolean indistinguishable() {
            return kind == MOVE
                    && java.util.Arrays.equals(expectedNames, before.names)
                    && java.util.Arrays.equals(expectedTypes, before.types);
        }

        private static String[] permuted(String[] source, int from, int to, int n) {
            String[] result = new String[n];
            int k = 0;
            for (int i = 0; i < n; i++) if (i != from) result[k++] = source[i];
            String[] out = new String[n];
            for (int i = 0, r = 0; i < n; i++) out[i] = i == to ? source[from] : result[r++];
            return out;
        }
    }

    /** pending=true means keep observing; otherwise `verification` is final and `ok` says it was observed. */
    static final class Outcome {
        final boolean pending, ok;
        final String verification;

        private Outcome(boolean pending, boolean ok, String verification) {
            this.pending = pending;
            this.ok = ok;
            this.verification = verification;
        }

        static final Outcome PENDING = new Outcome(true, false, null);

        static Outcome ok(String verification) {
            return new Outcome(false, true, verification);
        }

        static Outcome ambiguous() {
            return new Outcome(false, false, "ambiguous_chain_change");
        }
    }

    static Outcome observe(View v, Plan p) {
        Before b = p.before;
        int n = b.count, w = v.window(), c = v.count();
        boolean consistent = p.kind == INSERT ? c >= n && c <= n + 1 : p.kind == DELETE ? c >= n - 1 && c <= n : c == n;
        if (!consistent) return Outcome.ambiguous();
        // Slots before an edit point must be exactly what they were; a moved range has no fixed prefix.
        int fixed = p.kind == MOVE ? 0 : Math.min(p.index, Math.min(n, w));
        for (int i = 0; i < fixed; i++) if (!v.exists(i) || !same(v, i, b, i)) return Outcome.ambiguous();
        if (p.kind == MOVE) return observeMove(v, p, n, w);
        int expected = p.kind == INSERT ? n + 1 : n - 1;
        if (c != expected || v.countSequence() <= b.countSequence) return Outcome.PENDING;
        if (p.kind == INSERT) return observeInsert(v, p, n, w);
        for (int i = p.index; i < Math.min(n - 1, w); i++)
            if (!v.exists(i) || !v.ready(i) || !same(v, i, b, i + 1)) return Outcome.PENDING;
        for (int i = n - 1; i < w; i++) if (v.exists(i)) return Outcome.PENDING;
        return Outcome.ok("observed_count_decrement_and_shifted_suffix");
    }

    private static Outcome observeInsert(View v, Plan p, int n, int w) {
        Before b = p.before;
        int k = p.index;
        if (n < w && !(v.exists(n) && v.ready(n) && v.nameSequence(n) > b.nameSequences[n])) return Outcome.PENDING;
        if (k < w && !(v.exists(k) && v.ready(k))) return Outcome.PENDING;
        for (int i = k + 1; i < Math.min(n + 1, w); i++)
            if (!v.exists(i) || !v.ready(i) || !same(v, i, b, i - 1)) return Outcome.PENDING;
        for (int i = n + 1; i < w; i++) if (v.exists(i)) return Outcome.PENDING;
        if (k >= w) return Outcome.ok("observed_count_increment_beyond_window");
        return Outcome.ok(
                k == n ? "observed_count_increment_and_unchanged_prefix" : "observed_count_increment_shifted_suffix");
    }

    private static Outcome observeMove(View v, Plan p, int n, int w) {
        for (int i = 0; i < w; i++) {
            if (i >= n) {
                if (v.exists(i)) return Outcome.PENDING;
                continue;
            }
            if (!v.exists(i)
                    || !v.ready(i)
                    || !Objects.equals(v.name(i), p.expectedNames[i])
                    || !Objects.equals(v.type(i), p.expectedTypes[i])) return Outcome.PENDING;
        }
        return Outcome.ok("observed_expected_arrangement");
    }

    private static boolean same(View v, int slot, Before b, int old) {
        return Objects.equals(v.name(slot), b.names[old]) && Objects.equals(v.type(slot), b.types[old]);
    }

    static JsonObject summary(int index, String name, String type) {
        JsonObject result = new JsonObject();
        result.addProperty("deviceIndex", index);
        result.addProperty("name", name);
        result.addProperty("type", type);
        return result;
    }

    static JsonArray before(Before b) {
        JsonArray result = new JsonArray();
        for (int i = 0; i < b.names.length; i++) result.add(summary(i, b.names[i], b.types[i]));
        return result;
    }

    static JsonArray after(View v) {
        JsonArray result = new JsonArray();
        for (int i = 0; i < v.window(); i++) if (v.exists(i)) result.add(summary(i, v.name(i), v.type(i)));
        return result;
    }
}
