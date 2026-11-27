package dev.bitwigagent.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The range rule of time-series content (notes, automation points) as one pure function. Without a range the listed
 * entries are written in place (inserted, or updated when an entry with the same identity exists) and nothing is
 * removed. With a range, the entries inside it become exactly the listed ones. Entries are canonical records; their
 * {@link Kind} says which ones are the same entry and whether two of them are equal as the host stores them.
 */
public final class RangeEdit {
    private RangeEdit() {}

    /** Identity and equality of one kind of entry. */
    public interface Kind<R> {
        /** Entries with equal keys are the same entry (a note's channel, pitch and onset). */
        Object key(R entry);

        /** Whether two entries with the same key are equal as the host stores them. */
        boolean same(R a, R b);
    }

    /** An existing entry and what it becomes. */
    public static final class Update<R> {
        public final R before, after;

        Update(R before, R after) {
            this.before = before;
            this.after = after;
        }
    }

    /** What a write does to the observed entries; {@code unchanged} counts listed entries already equal. */
    public static final class Change<R> {
        public final List<R> insert, remove;
        public final List<Update<R>> update;
        public final int unchanged;

        Change(List<R> insert, List<Update<R>> update, List<R> remove, int unchanged) {
            this.insert = Collections.unmodifiableList(insert);
            this.update = Collections.unmodifiableList(update);
            this.remove = Collections.unmodifiableList(remove);
            this.unchanged = unchanged;
        }

        public boolean isEmpty() {
            return insert.isEmpty() && update.isEmpty() && remove.isEmpty();
        }

        /** The entries the write puts into the content: inserts, then updated values. */
        public List<R> written() {
            List<R> out = new ArrayList<>(insert);
            for (Update<R> u : update) out.add(u.after);
            return out;
        }
    }

    /**
     * The change that makes {@code observed} hold {@code listed}. {@code range} null: upsert, nothing removed. Else
     * every observed entry in the range that is not listed is removed; listed entries must lie in the range and have
     * distinct keys (the caller's rules refuse both before planning). Observed entries sharing a key (the host can
     * hold two) count as one: the first equal one stays, the others are removed inside a range and kept outside one.
     */
    public static <R> Change<R> plan(List<R> observed, List<R> listed, Predicate<R> range, Kind<R> kind) {
        Map<Object, List<R>> byKey = new HashMap<>();
        for (R entry : observed)
            byKey.computeIfAbsent(kind.key(entry), k -> new ArrayList<>()).add(entry);
        List<R> insert = new ArrayList<>(), remove = new ArrayList<>();
        List<Update<R>> update = new ArrayList<>();
        Set<Object> keys = new HashSet<>();
        int unchanged = 0;
        for (R entry : listed) {
            Object key = kind.key(entry);
            if (!keys.add(key)) throw new IllegalArgumentException("listed entries repeat a key");
            if (range != null && !range.test(entry))
                throw new IllegalArgumentException("a listed entry lies outside the range");
            List<R> existing = byKey.getOrDefault(key, List.of());
            R kept = null;
            for (R candidate : existing)
                if (kind.same(candidate, entry)) {
                    kept = candidate;
                    break;
                }
            if (kept != null) unchanged++;
            else if (existing.isEmpty()) insert.add(entry);
            else update.add(new Update<>(kept = existing.get(0), entry));
            if (range != null) for (R other : existing) if (other != kept) remove.add(other);
        }
        if (range != null)
            for (R entry : observed) if (range.test(entry) && !keys.contains(kind.key(entry))) remove.add(entry);
        return new Change<>(insert, update, remove, unchanged);
    }

    /** {@code observed} after {@code change}: removed and updated entries (by identity) out, the written ones in. */
    public static <R> List<R> apply(List<R> observed, Change<R> change) {
        Map<R, Boolean> gone = new IdentityHashMap<>();
        for (R entry : change.remove) gone.put(entry, true);
        for (Update<R> u : change.update) gone.put(u.before, true);
        List<R> out = new ArrayList<>(observed.size() + change.insert.size());
        for (R entry : observed) if (!gone.containsKey(entry)) out.add(entry);
        out.addAll(change.written());
        return out;
    }
}
