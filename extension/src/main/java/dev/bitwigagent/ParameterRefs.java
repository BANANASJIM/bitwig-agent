package dev.bitwigagent;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.bitwigagent.core.Refs;
import java.util.Set;
import java.util.UUID;

/** The owner kinds of a parameter ref and how each owner's id names its parameter. */
final class ParameterRefs {
    static final String DEVICE = "device",
            REMOTE_PAGE = "remotePage",
            MODULATOR = "modulator",
            GRID_MODULE = "gridModule",
            TRACK = "track";
    static final Set<String> OWNERS = Set.of(DEVICE, REMOTE_PAGE, MODULATOR, GRID_MODULE, TRACK);

    private ParameterRefs() {}

    /** The owner kind of a parameter ref; anything else is invalid_params. */
    static String owner(JsonObject ref) {
        JsonElement owner = ref.get("owner");
        String kind = owner != null && owner.isJsonObject() && "parameter".equals(Refs.kind(ref))
                ? Refs.kind(owner.getAsJsonObject())
                : null;
        if (kind == null || !OWNERS.contains(kind))
            throw Errors.invalid("A parameter ref needs an owner of kind " + OWNERS + ".");
        return kind;
    }

    /** The Remote Control slot a remotePage-owned parameter names by its id. */
    static int control(JsonObject ref) {
        String id = ref.get("id").getAsString();
        for (int slot = 0; slot < HostLimits.LIMITS.remotePageSlots; slot++)
            if (id.equals(Integer.toString(slot))) return slot;
        throw Errors.invalid("A Remote Control id is its slot, 0.." + (HostLimits.LIMITS.remotePageSlots - 1) + ".");
    }

    /** The UUID a modulator or Grid module owner is located by. */
    static UUID ownerId(JsonObject ref) {
        return UUID.fromString(ref.getAsJsonObject("owner").get("id").getAsString());
    }
}
