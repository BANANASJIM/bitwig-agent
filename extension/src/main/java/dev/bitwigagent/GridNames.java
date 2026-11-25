package dev.bitwigagent;

import java.util.*;

/** The one name-matching rule for catalog queries and typeName lookup: case, spaces, hyphens and punctuation are ignored. */
final class GridNames {
    static final int SUGGESTIONS = 5;
    private GridNames() {}

    static String norm(String text) {
        StringBuilder out = new StringBuilder(text.length());
        text.codePoints().filter(Character::isLetterOrDigit).forEach(c -> out.appendCodePoint(Character.toLowerCase(c)));
        return out.toString();
    }

    /** Every whitespace-separated query word must occur in the normalized name; an empty query matches everything. */
    static boolean matches(String name, String query) {
        String haystack = norm(name);
        for (String word : query.split("\\s+")) {
            String needle = norm(word);
            if (!needle.isEmpty() && !haystack.contains(needle)) return false;
        }
        return true;
    }

    /**
     * typeName lookup: exact string, then exact ignoring case, then the loose normalized form (skipped for symbol-only
     * names, which only match exactly). The first step with any hit decides; several hits are ambiguous.
     */
    static <T> List<T> resolve(Collection<T> items, java.util.function.Function<T, String> name, String query) {
        List<T> hits = new ArrayList<>();
        for (T item : items) if (name.apply(item).equals(query)) hits.add(item);
        if (!hits.isEmpty()) return hits;
        for (T item : items) if (name.apply(item).equalsIgnoreCase(query)) hits.add(item);
        if (!hits.isEmpty() || norm(query).isEmpty()) return hits;
        for (T item : items) if (same(name.apply(item), query)) hits.add(item);
        return hits;
    }

    static boolean same(String name, String query) { return norm(name).equals(norm(query)); }

    /** Nearest names by edit-distance ratio on the normalized forms, best first; hopeless matches are dropped. */
    static List<String> nearest(Collection<String> names, String query, int limit) {
        String q = norm(query);
        List<Map.Entry<Double, String>> scored = new ArrayList<>();
        for (String name : names) {
            String n = norm(name);
            int longest = Math.max(q.length(), n.length());
            if (longest == 0) continue;
            double ratio = 1.0 - (double) distance(q, n) / longest;
            if (ratio >= 0.3) scored.add(Map.entry(ratio, name));
        }
        scored.sort(Map.Entry.<Double, String>comparingByKey().reversed().thenComparing(Map.Entry.comparingByValue()));
        List<String> out = new ArrayList<>();
        for (Map.Entry<Double, String> entry : scored) { if (out.size() == limit) break; out.add(entry.getValue()); }
        return out;
    }

    private static int distance(String a, String b) {
        int[] row = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) row[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            int diagonal = row[0]; row[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int up = row[j];
                row[j] = Math.min(Math.min(row[j] + 1, row[j - 1] + 1), diagonal + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
                diagonal = up;
            }
        }
        return row[b.length()];
    }
}
