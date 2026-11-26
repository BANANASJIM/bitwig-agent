package dev.bitwigagent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Every domain's unresolved or in-flight write, in one place. An unresolved hold stays until the agent inspects state
 * and calls {@code ack} with its id; an in-flight hold is released only by its owner when the work settles.
 */
final class Holds {
    /** What {@code status} lists: the id {@code ack} takes, whether it accepts it and what is held. */
    static final class View {
        final String id, description;
        final boolean ackable;

        View(String id, String description, boolean ackable) {
            this.id = id;
            this.description = description;
            this.ackable = ackable;
        }
    }

    private static final class Hold {
        final String domain, id;
        final Object key;
        final Supplier<String> description;
        final boolean ackable;

        Hold(String domain, String id, Object key, Supplier<String> description, boolean ackable) {
            this.domain = domain;
            this.id = id;
            this.key = key;
            this.description = description;
            this.ackable = ackable;
        }
    }

    private final List<Hold> holds = new ArrayList<>();
    private int sequence;

    /** An unresolved hold identified by its domain alone; its id is the domain. */
    void place(String domain, String description) {
        put(domain, domain, () -> description, true);
    }

    /** An unresolved hold per {@code key}; a description that supplies null counts as released. */
    void place(String domain, Object key, Supplier<String> description) {
        put(domain, key, description, true);
    }

    /** An in-flight hold: {@code ack} refuses it, its owner releases it when the work settles. */
    void inFlight(String domain, String description) {
        put(domain, domain, () -> description, false);
    }

    void inFlight(String domain, Supplier<String> description) {
        put(domain, domain, description, false);
    }

    private synchronized void put(String domain, Object key, Supplier<String> description, boolean ackable) {
        for (int i = 0; i < holds.size(); i++) {
            Hold existing = holds.get(i);
            if (existing.domain.equals(domain) && existing.key.equals(key)) {
                holds.set(i, new Hold(domain, existing.id, key, description, ackable));
                return;
            }
        }
        String id = key.equals(domain) ? domain : domain + ":" + ++sequence;
        holds.add(new Hold(domain, id, key, description, ackable));
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

    /** The refusal a write gets while {@code domain} is held: what to inspect, then which id to ack. */
    synchronized RpcException blocked(String domain, String message, String inspect) {
        List<String> ids = new ArrayList<>();
        for (Hold hold : holds) if (hold.domain.equals(domain) && hold.description.get() != null) ids.add(hold.id);
        return Errors.notApplied(Reason.UNRESOLVED, message, inspect + ", then ack " + String.join(" / ", ids) + ".");
    }

    synchronized List<View> list() {
        List<View> out = new ArrayList<>();
        for (Hold hold : holds) {
            String text = hold.description.get();
            if (text != null) out.add(new View(hold.id, text, hold.ackable));
        }
        return out;
    }

    /** Releases the unresolved hold {@code id} and returns its description. */
    synchronized String ack(String id) {
        for (Hold hold : holds) {
            String text = hold.description.get();
            if (!hold.id.equals(id) || text == null) continue;
            if (!hold.ackable)
                throw Errors.notApplied(
                        Reason.BUSY, text, "Wait for it to settle; ack releases only unresolved holds.");
            holds.remove(hold);
            return text;
        }
        throw Errors.notApplied(Reason.UNKNOWN_HOLD, "No current hold has id " + id + ".", "status lists the holds.");
    }
}
