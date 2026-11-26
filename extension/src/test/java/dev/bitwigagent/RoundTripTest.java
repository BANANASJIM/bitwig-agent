package dev.bitwigagent;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import dev.bitwigagent.core.HostModule;
import dev.bitwigagent.core.HostOperation;
import dev.bitwigagent.core.Outcome;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * The round-trip law: every value a read returns for a set field (its value, and each option of an enum) written back
 * unchanged passes the schema and matches what is there. Each domain registers its set fields in VECTORS.
 */
public final class RoundTripTest {
    /** A set field: where the read returns it and which write takes it back. */
    private static final class Vector {
        final Function<TestServices, HostModule> module;
        final String read, write, field;
        final JsonObject readParams, writeParams;

        Vector(
                Function<TestServices, HostModule> module,
                String read,
                String readParams,
                String write,
                String writeParams,
                String field) {
            this.module = module;
            this.read = read;
            this.readParams = HostFixture.json(readParams);
            this.write = write;
            this.writeParams = HostFixture.json(writeParams);
            this.field = field;
        }
    }

    private static final List<Vector> TRANSPORT = List.of(
            new Vector(TestServices::transportModule, "transport.get", "{}", "transport.set", "{}", "tempoBpm"),
            new Vector(TestServices::transportModule, "transport.get", "{}", "transport.set", "{}", "preRoll"),
            new Vector(TestServices::transportModule, "transport.get", "{}", "transport.set", "{}", "metronomeVolume"),
            new Vector(
                    TestServices::transportModule,
                    "transport.get",
                    "{}",
                    "transport.set",
                    "{}",
                    "defaultLaunchQuantization"),
            new Vector(
                    TestServices::transportModule,
                    "transport.get",
                    "{}",
                    "transport.set",
                    "{}",
                    "recordQuantizationGrid"));

    private static final String TRACK_B = "{\"target\":" + HostFixture.track(HostFixture.B) + "}";

    private static List<Vector> vectors() {
        List<Vector> out = new ArrayList<>(TRANSPORT);
        for (String field : List.of(
                "name", "volume", "pan", "mute", "solo", "arm", "color", "activated", "monitorMode", "crossFadeMode"))
            out.add(new Vector(TestServices::mix, "tracks.get", TRACK_B, "tracks.set", TRACK_B, field));
        return out;
    }

    private static final List<Vector> VECTORS = vectors();

    /** The field's options: inside its read unit, or where a read still lists them apart under options. */
    private static JsonArray options(JsonObject read, String field) {
        JsonElement unit = read.get(field);
        if (unit.isJsonObject()) return unit.getAsJsonObject().getAsJsonArray("options");
        JsonObject apart = read.getAsJsonObject("options");
        return apart == null ? null : apart.getAsJsonArray(field);
    }

    private static JsonElement value(JsonElement unit) {
        return unit.isJsonObject() ? unit.getAsJsonObject().get("value") : unit;
    }

    /** Runs the host until the write answers, polled observation included. */
    private static JsonObject write(HostFixture f, HostModule module, Vector vector, JsonElement value) {
        JsonObject params = vector.writeParams.deepCopy();
        params.add(vector.field, value);
        HostOperation op = f.submit(module, vector.write, params);
        while (!op.result.isDone()) {
            assertFalse(f.tasks.isEmpty(), vector.write + " waits with nothing scheduled");
            f.next();
        }
        return op.result.join();
    }

    private static String outcome(JsonObject result) {
        return result.get("outcome").getAsString();
    }

    @Test
    void everyReadValueWritesBackAndEveryOptionIsWritable() {
        for (Vector vector : VECTORS) {
            HostFixture f = new HostFixture();
            HostModule module = vector.module.apply(f.services);
            JsonObject read = f.call(module, vector.read, vector.readParams);
            JsonElement current = value(read.get(vector.field));
            assertEquals("already_matched", outcome(write(f, module, vector, current)), vector.field + "=" + current);
            JsonArray options = options(read, vector.field);
            for (JsonElement option : options == null ? new JsonArray() : options) {
                JsonElement choice = value(option);
                assertTrue(
                        Outcome.valueOf(outcome(write(f, module, vector, choice))
                                        .toUpperCase(java.util.Locale.ROOT))
                                .succeeded(),
                        vector.field + "=" + choice + " is an option the read offered");
                assertEquals("already_matched", outcome(write(f, module, vector, choice)), "written again");
            }
        }
    }
}
