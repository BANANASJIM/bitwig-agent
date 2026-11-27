package dev.bitwigagent.device;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.bitwigagent.core.Results;

/**
 * One chain's mixer strip as read in one document-thread pass: volume and pan (normalized, with the host's display),
 * mute, solo and, for a Selector's chain, whether it is the active one. A field the chain does not have is null.
 */
final class ChainStrip {
    static final String VOLUME = "volume", PAN = "pan", MUTE = "mute", SOLO = "solo", ACTIVE = "active";

    final NativeChains.Mixer mixer;
    final Double volume, pan;
    final String volumeDisplay, panDisplay;
    final Boolean mute, solo, active;

    private ChainStrip(NativeChains.Access a, NativeChains.Mixer mixer, Boolean active) {
        this.mixer = mixer;
        this.active = active;
        volume = mixer == null ? null : a.value(mixer.volume);
        pan = mixer == null ? null : a.value(mixer.pan);
        volumeDisplay = mixer == null ? null : a.display(mixer.volume);
        panDisplay = mixer == null ? null : a.display(mixer.pan);
        mute = mixer == null ? null : a.value(mixer.mute) >= .5;
        solo = mixer == null ? null : mixer.solo;
    }

    /** The strip of {@code chain} as it is now; {@code activeIndex} is the Selector's active chain, else null. */
    static ChainStrip read(NativeChains.Access a, NativeChains.Chain chain, Integer activeIndex) {
        return new ChainStrip(a, a.mixer(chain.chain), activeIndex == null ? null : activeIndex == chain.position);
    }

    /** The parameter behind a normalized field, for writing it. */
    Object parameter(String field) {
        return field.equals(VOLUME) ? mixer.volume : mixer.pan;
    }

    /** The bare value of one field, as a write takes it and expect compares it; JSON null when absent. */
    JsonElement bare(String field) {
        Object value;
        switch (field) {
            case VOLUME:
                value = volume;
                break;
            case PAN:
                value = pan;
                break;
            case MUTE:
                value = mute;
                break;
            case SOLO:
                value = solo;
                break;
            default:
                value = active;
        }
        if (value == null) return JsonNull.INSTANCE;
        return value instanceof Boolean ? new JsonPrimitive((Boolean) value) : new JsonPrimitive((Double) value);
    }

    /** A read field: volume and pan as {value, display}, the rest bare. */
    JsonElement read(String field) {
        if (field.equals(VOLUME)) return unit(volume, volumeDisplay);
        if (field.equals(PAN)) return unit(pan, panDisplay);
        return bare(field);
    }

    /** The strip's fields for a chains.list row; active only on a Selector's chains. */
    void addTo(JsonObject row) {
        for (String field : new String[] {VOLUME, PAN, MUTE, SOLO}) row.add(field, read(field));
        if (active != null) row.addProperty(ACTIVE, active);
    }

    private static JsonElement unit(Double value, String display) {
        return value == null ? JsonNull.INSTANCE : Results.unit(new JsonPrimitive(value), display, null);
    }
}
