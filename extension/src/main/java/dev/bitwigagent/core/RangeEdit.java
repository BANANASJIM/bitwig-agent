package dev.bitwigagent.core;

import java.util.*;
import java.util.function.Predicate;

/**
 * The range rule shared by time-series records (automation points, notes). Without a scope only the listed records are
 * written: each is inserted, or updates the observed record with its identity; nothing is removed. With a scope the
 * observed records inside it become exactly the listed ones. Records are canonical values with {@code equals}, so a
 * listed record that writes what is already there is unchanged.
 *
 * @param <R> observed record
 * @param <L> listed (requested) record; it may omit fields the observed record keeps
 */
public final class RangeEdit {
    private RangeEdit() {}

    /** What a record type supplies: identities, the record a listed one writes over the observed one, lane order. */
    public interface Kind<R, L> {
        Object identity(R observed);

        Object listedIdentity(L listed);

        /** The record written for {@code listed}; {@code observed} is null for a new record. */
        R written(R observed, L listed);

        Comparator<R> order();
    }

    /** An observed record replaced by the record a listed one writes. */
    public static final class Update<R> {
        public final R from, to;

        Update(R from, R to) {
            this.from = from;
            this.to = to;
        }
    }

    /** The plan; {@code remove}, {@code unchanged} and {@code Update.from} are the observed instances themselves. */
    public static final class Change<R> {
        public final List<R> insert = new ArrayList<>(), remove = new ArrayList<>(), unchanged = new ArrayList<>();
        public final List<Update<R>> update = new ArrayList<>();

        public boolean empty() {
            return insert.isEmpty() && remove.isEmpty() && update.isEmpty();
        }
    }

    /**
     * Plans {@code listed} over {@code observed}. {@code scope} is null without a range, else whether an observed record
     * lies inside it. Listed identities must be unique; an observed identity held by several records matches the first.
     */
    public static <R, L> Change<R> plan(List<R> observed, List<L> listed, Predicate<R> scope, Kind<R, L> kind) {
        Map<Object, R> byIdentity = new HashMap<>();
        for (R record : observed) byIdentity.putIfAbsent(kind.identity(record), record);
        Set<R> matched = Collections.newSetFromMap(new IdentityHashMap<>());
        Change<R> change = new Change<>();
        for (L item : listed) {
            R from = byIdentity.get(kind.listedIdentity(item));
            R to = kind.written(from, item);
            if (from == null) change.insert.add(to);
            else if (from.equals(to)) change.unchanged.add(from);
            else change.update.add(new Update<>(from, to));
            if (from != null) matched.add(from);
        }
        if (scope != null)
            for (R record : observed) if (!matched.contains(record) && scope.test(record)) change.remove.add(record);
        return change;
    }

    /** The records after {@code change}: untouched observed instances kept as they are, in {@code kind.order()}. */
    public static <R> List<R> apply(List<R> observed, Change<R> change, Kind<R, ?> kind) {
        Set<R> gone = Collections.newSetFromMap(new IdentityHashMap<>());
        gone.addAll(change.remove);
        List<R> out = new ArrayList<>();
        for (Update<R> update : change.update) {
            gone.add(update.from);
            out.add(update.to);
        }
        out.addAll(change.insert);
        for (R record : observed) if (!gone.contains(record)) out.add(record);
        out.sort(kind.order());
        return out;
    }
}
