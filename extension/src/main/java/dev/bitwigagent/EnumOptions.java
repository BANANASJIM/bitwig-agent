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
        for (int i = 0; i < definition.getValueCount(); i++)
            out.add(definition.valueDefinitionAt(i).getId());
        return out;
    }

    static JsonArray json(EnumValue value) {
        JsonArray out = new JsonArray();
        for (String id : ids(value)) out.add(id);
        return out;
    }

    /** True when the host currently reports a value its own option list does not contain (e.g. a stored project value). */
    static boolean outsideOptions(EnumValue value) {
        return outsideOptions(value, value.get());
    }

    /** observed: the value the module reports (its observer's last callback), which can differ from EnumValue.get() for stored project values. */
    static boolean outsideOptions(EnumValue value, String current) {
        List<String> options = ids(value);
        return !options.isEmpty() && current != null && !current.isEmpty() && !options.contains(current);
    }

    /** Rejects (not_applied) a value the host does not list, except the value it currently reports (so a read can be written back); an unreadable list is not_ready, not permission. */
    static void require(String field, EnumValue value, String requested) {
        require(field, value, requested, value.get());
    }

    static void require(String field, EnumValue value, String requested, String observed) {
        List<String> options = ids(value);
        if (options.isEmpty())
            throw Errors.notApplied(Reason.NOT_READY, field + " options are not readable from the host yet.");
        if (!options.contains(requested) && !requested.equals(observed) && !requested.equals(value.get()))
            throw Errors.notApplied(
                    Reason.INVALID_PARAMS,
                    field + " must be one of the host's options " + options + ", not " + requested + ".");
    }
}
