package dev.bitwigagent.device;

import com.google.gson.*;
import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.Reason;
import java.util.Objects;

/**
 * Observed-delta verification for device-chain edits (insert, duplicate, delete, move, replace) over a window of
 * observed slots.
 * Pure: it reads a {@link View} of host observations and never touches the host API, so one set of rules serves
 * the top-level 16-slot bank and a layer's 2-slot child window. Names and types are the only observable identity.
 */
final class DeviceChainEdit {
    static final int INSERT = 0, DELETE = 1, MOVE = 2, REPLACE = 3;

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

        /** The slot's Remote Controls page names as one string, or null when not observed: a preset's identity hint. */
        default String pages(int slot) {
            return null;
        }
    }

    /** The chain as observed immediately before dispatch. */
    static final class Before {
        final int count;
        final long countSequence;
        final String[] names, types;
        final long[] nameSequences;
        final String[] pages;

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
            pages = new String[seen];
            for (int i = 0; i < seen; i++) pages[i] = v.pages(i);
            nameSequences = new long[Math.min(count + 1, v.window())];
            for (int i = 0; i < nameSequences.length; i++) nameSequences[i] = v.nameSequence(i);
        }

        /** Complete, consistent window or a fail-closed error; getters are never evidence on their own. */
        static Before capture(View v, String staleMessage) {
            int count = v.count();
            if (count < 0) throw Errors.notApplied(Reason.NOT_READY, "Device count has not been observed.");
            for (int i = 0; i < v.window(); i++) {
                if (!v.known(i) || (i < count && !v.ready(i)))
                    throw Errors.notApplied(Reason.NOT_READY, "Device bank has not reported the complete window.");
                if (v.exists(i) != (i < count)) throw Errors.notApplied(Reason.NOT_READY, staleMessage);
            }
            return new Before(v);
        }
    }

    /**
     * What was dispatched: an insertion at `index` (a copy of `copyOf` when that is not -1), the deletion of `index`,
     * a move of `index` to `to`, or a replacement of the device at `index`.
     */
    static final class Plan {
        final int kind, index, to, copyOf;
        final Before before;
        /** A replacement's expected device name (a file's name without extension), or null when not predictable. */
        String expectedName;

        final String[] expectedNames, expectedTypes;

        private Plan(int kind, int index, int to, int copyOf, Before before) {
            this.kind = kind;
            this.index = index;
            this.to = to;
            this.copyOf = copyOf;
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
            return new Plan(INSERT, index, index, -1, before);
        }

        /** A copy of the device at `index` inserted right after it, carrying its name and type. */
        static Plan duplicate(int index, Before before) {
            return new Plan(INSERT, index + 1, index + 1, index, before);
        }

        static Plan delete(int index, Before before) {
            return new Plan(DELETE, index, index, -1, before);
        }

        static Plan move(int from, int to, Before before) {
            return new Plan(MOVE, from, to, -1, before);
        }

        static Plan replace(int index, Before before, String expectedName) {
            Plan plan = new Plan(REPLACE, index, index, -1, before);
            plan.expectedName = expectedName;
            return plan;
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

    /** pending=true means keep observing; otherwise `ok` says whether the edit was observed. */
    static final class Observation {
        final boolean pending, ok;

        private Observation(boolean pending, boolean ok) {
            this.pending = pending;
            this.ok = ok;
        }

        static final Observation PENDING = new Observation(true, false);
        static final Observation OK = new Observation(false, true);
        static final Observation AMBIGUOUS = new Observation(false, false);
    }

    static Observation observe(View v, Plan p) {
        Before b = p.before;
        int n = b.count, w = v.window(), c = v.count();
        boolean consistent = p.kind == INSERT
                ? c >= n && c <= n + 1
                : p.kind == DELETE ? c >= n - 1 && c <= n : p.kind == REPLACE ? c >= n - 1 && c <= n + 1 : c == n;
        if (!consistent) return Observation.AMBIGUOUS;
        // Slots before an edit point must be exactly what they were; a moved range has no fixed prefix.
        int fixed = p.kind == MOVE ? 0 : Math.min(p.index, Math.min(n, w));
        for (int i = 0; i < fixed; i++) if (!v.exists(i) || !same(v, i, b, i)) return Observation.AMBIGUOUS;
        if (p.kind == MOVE) return observeMove(v, p, n, w);
        if (p.kind == REPLACE) return observeReplace(v, p, n, w);
        int expected = p.kind == INSERT ? n + 1 : n - 1;
        if (c != expected || v.countSequence() <= b.countSequence) return Observation.PENDING;
        if (p.kind == INSERT) return observeInsert(v, p, n, w);
        // A device that moves up into the window from beyond it has no recorded name to compare with.
        for (int i = p.index; i < Math.min(n - 1, w); i++)
            if (!v.exists(i) || !v.ready(i) || (i + 1 < b.names.length && !same(v, i, b, i + 1)))
                return Observation.PENDING;
        for (int i = n - 1; i < w; i++) if (v.exists(i)) return Observation.PENDING;
        return Observation.OK;
    }

    private static Observation observeInsert(View v, Plan p, int n, int w) {
        Before b = p.before;
        int k = p.index;
        if (n < w && !(v.exists(n) && v.ready(n) && v.nameSequence(n) > b.nameSequences[n])) return Observation.PENDING;
        if (k < w && !(v.exists(k) && v.ready(k))) return Observation.PENDING;
        if (p.copyOf >= 0 && k < w && !same(v, k, b, p.copyOf)) return Observation.PENDING;
        for (int i = k + 1; i < Math.min(n + 1, w); i++)
            if (!v.exists(i) || !v.ready(i) || !same(v, i, b, i - 1)) return Observation.PENDING;
        for (int i = n + 1; i < w; i++) if (v.exists(i)) return Observation.PENDING;
        return Observation.OK;
    }

    private static Observation observeMove(View v, Plan p, int n, int w) {
        for (int i = 0; i < w; i++) {
            if (i >= n) {
                if (v.exists(i)) return Observation.PENDING;
                continue;
            }
            if (!v.exists(i)
                    || !v.ready(i)
                    || !Objects.equals(v.name(i), p.expectedNames[i])
                    || !Objects.equals(v.type(i), p.expectedTypes[i])) return Observation.PENDING;
        }
        return Observation.OK;
    }

    /**
     * Same count, every other device unchanged, and the slot shows the requested device: another type, other Remote
     * Controls pages, or another name (the expected one when the request names a file). A name report alone, or a
     * rename to an unrelated name, is not evidence.
     */
    private static Observation observeReplace(View v, Plan p, int n, int w) {
        Before b = p.before;
        int k = p.index;
        if (v.count() != n) return Observation.PENDING;
        for (int i = k + 1; i < Math.min(n, w); i++)
            if (!v.exists(i) || !v.ready(i) || !same(v, i, b, i)) return Observation.PENDING;
        for (int i = n; i < w; i++) if (v.exists(i)) return Observation.PENDING;
        if (!v.exists(k) || !v.ready(k)) return Observation.PENDING;
        String pages = v.pages(k);
        boolean renamed =
                !Objects.equals(v.name(k), b.names[k]) && (p.expectedName == null || p.expectedName.equals(v.name(k)));
        boolean renewed = !Objects.equals(v.type(k), b.types[k])
                || renamed
                || (pages != null && b.pages[k] != null && !pages.equals(b.pages[k]));
        return renewed ? Observation.OK : Observation.PENDING;
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
