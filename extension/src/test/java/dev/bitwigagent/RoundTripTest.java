package dev.bitwigagent;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
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

    private static final List<Vector> VECTORS = List.of(
            new Vector(TestServices::transportModule, "transport.get", "{}", "transport.set", "{}", "tempoBpm"));

    /** The read's value of a field, then each option value when the field is a read unit with options. */
    private static List<JsonElement> values(JsonElement read) {
        List<JsonElement> out = new ArrayList<>();
        if (!read.isJsonObject()) {
            out.add(read);
            return out;
        }
        out.add(read.getAsJsonObject().get("value"));
        JsonArray options = read.getAsJsonObject().getAsJsonArray("options");
        if (options != null)
            for (JsonElement option : options)
                out.add(option.isJsonObject() ? option.getAsJsonObject().get("value") : option);
        return out;
    }

    @Test
    void everyReadValueWritesBackAsAlreadyMatched() {
        for (Vector vector : VECTORS) {
            HostFixture f = new HostFixture();
            HostModule module = vector.module.apply(f.services);
            JsonElement read = f.call(module, vector.read, vector.readParams).get(vector.field);
            for (JsonElement value : values(read)) {
                JsonObject write = vector.writeParams.deepCopy();
                write.add(vector.field, value);
                JsonObject result = f.call(module, vector.write, write);
                assertEquals(
                        "already_matched",
                        result.get("outcome").getAsString(),
                        vector.write + " " + vector.field + "=" + value + " is what " + vector.read + " returned");
            }
        }
    }
}
