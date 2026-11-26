package dev.bitwigagent.core;

import com.google.gson.JsonObject;
import java.util.Set;

/** A host-operation domain. Validation is host-free; dispatch starts on the controller thread. */
public interface HostModule {
    Set<String> methods();

    void validate(String method, JsonObject params);

    void dispatch(HostOperation operation, String method, JsonObject params);

    default void flush() {}

    /** Adds this domain's section to status; only with {@code full}, so the brief status stays the same. */
    default void status(HostOperation operation, JsonObject out, boolean full) {}
}
