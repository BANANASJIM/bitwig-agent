package dev.bitwigagent;

import com.google.gson.JsonObject;
import java.util.Set;

/** A host-operation domain. Validation is host-free; dispatch starts on the controller thread. */
interface HostModule {
    Set<String> methods();
    void validate(String method, JsonObject params);
    void dispatch(HostOperation operation, String method, JsonObject params);
    default void flush() { }
}
