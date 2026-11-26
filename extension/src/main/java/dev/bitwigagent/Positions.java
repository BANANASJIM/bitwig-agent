package dev.bitwigagent;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.function.ToIntFunction;

/** A position in an ordered container: "start" | "end" | {before: ref} | {after: ref}; absent means "end". */
final class Positions {
    private Positions() {}

    /** The insertion index among {@code size} items; {@code anchor} gives the index of the item an anchor ref names. */
    static int index(JsonElement position, int size, ToIntFunction<JsonObject> anchor) {
        if (position == null || position.isJsonPrimitive())
            return position != null && "start".equals(position.getAsString()) ? 0 : size;
        JsonObject edge = position.getAsJsonObject();
        return edge.has("before")
                ? anchor.applyAsInt(edge.getAsJsonObject("before"))
                : anchor.applyAsInt(edge.getAsJsonObject("after")) + 1;
    }
}
