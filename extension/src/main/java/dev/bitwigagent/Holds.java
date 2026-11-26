package dev.bitwigagent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Supplier;

/** Every domain's unresolved or in-flight write, in one place: what {@code extension.restart} lists and refuses on. */
final class Holds {
    private static final class Hold {
        final String domain;
        final Object key;
        final Supplier<String> description;

        Hold(String domain, Object key, Supplier<String> description) {
            this.domain = domain;
            this.key = key;
            this.description = description;
        }
    }

    private final List<Hold> holds = new ArrayList<>();

    /** A hold identified by its domain alone. */
    void place(String domain, String description) {
        place(domain, domain, () -> description);
    }

    /** A hold per {@code key}; a description that supplies null counts as released. */
    synchronized void place(String domain, Object key, Supplier<String> description) {
        Hold hold = new Hold(domain, key, description);
        for (int i = 0; i < holds.size(); i++) {
            Hold existing = holds.get(i);
            if (existing.domain.equals(domain) && existing.key.equals(key)) {
                holds.set(i, hold);
                return;
            }
        }
        holds.add(hold);
    }

    void release(String domain) {
        release(domain, domain);
    }

    synchronized void release(String domain, Object key) {
        holds.removeIf(hold -> hold.domain.equals(domain) && hold.key.equals(key));
    }

    synchronized boolean held(String domain) {
        for (Hold hold : holds) if (hold.domain.equals(domain) && hold.description.get() != null) return true;
        return false;
    }

    synchronized <T> List<T> keys(String domain, Class<T> type) {
        List<T> out = new ArrayList<>();
        for (Hold hold : holds)
            if (hold.domain.equals(domain) && hold.description.get() != null) out.add(type.cast(hold.key));
        return out;
    }

    /** Distinct descriptions in placement order; one domain may place several holds that say the same thing. */
    synchronized List<String> descriptions() {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (Hold hold : holds) {
            String text = hold.description.get();
            if (text != null) out.add(text);
        }
        return new ArrayList<>(out);
    }
}
