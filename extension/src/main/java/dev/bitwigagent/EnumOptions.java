package dev.bitwigagent;

import com.bitwig.extension.controller.api.EnumDefinition;
import com.bitwig.extension.controller.api.EnumValue;
import com.google.gson.JsonArray;
import java.util.ArrayList;
import java.util.List;

/** Option ids a host enum reports about itself; the API's own list is the only table, never a hand-written one. */
final class EnumOptions {
    private EnumOptions() {}

    static List<String> ids(EnumValue value) {
        EnumDefinition definition = value.enumDefinition();
        List<String> out = new ArrayList<>();
        if (definition == null) return out;
        for (int i = 0; i < definition.getValueCount(); i++) out.add(definition.valueDefinitionAt(i).getId());
        return out;
    }

    static JsonArray json(EnumValue value) {
        JsonArray out = new JsonArray();
        for (String id : ids(value)) out.add(id);
        return out;
    }

    /** Rejects (not_applied) a value the host does not list; an unreadable list is not_ready, not permission. */
    static void require(String field, EnumValue value, String requested) {
        List<String> options = ids(value);
        if (options.isEmpty()) throw HostServices.failure("not_ready", field + " options are not readable from the host yet.");
        if (!options.contains(requested))
            throw HostServices.failure("invalid_params", field + " must be one of the host's options " + options + ", not " + requested + ".");
    }
}
