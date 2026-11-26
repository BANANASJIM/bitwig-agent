package dev.bitwigagent;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.bitwigagent.core.SharedData;
import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleUnaryOperator;
import org.junit.jupiter.api.Test;

/** The bundled shared data loads, and this side's formulas and name rule satisfy the vectors the Python side runs too. */
public final class SharedDataTest {
    private static final JsonObject VECTORS = SharedData.read("display").getAsJsonObject("vectors");

    private static JsonArray rows(String key) {
        return VECTORS.getAsJsonArray(key);
    }

    private static JsonObject display(String unit, String text) {
        JsonObject row = new JsonObject();
        row.addProperty("numericUnit", unit);
        row.addProperty("display", text);
        return row;
    }

    private static boolean absent(JsonElement element) {
        return element == null || element.isJsonNull();
    }

    private static void close(double expected, double actual, String what) {
        assertEquals(expected, actual, Math.max(1e-9, Math.abs(expected) * 1e-9), what);
    }

    @Test
    void hostTarget() {
        JsonObject host = SharedData.read("host");
        assertEquals(host.get("version").getAsString(), Bitwig614Runtime.VERSION);
        assertEquals(host.get("jarSha256").getAsString(), Bitwig614Runtime.SHA256);
        assertEquals(host.get("apiVersion").getAsInt(), new BitwigAgentExtensionDefinition().getRequiredAPIVersion());
    }

    @Test
    void quantities() {
        for (JsonElement item : rows("quantity")) {
            JsonObject row = item.getAsJsonObject();
            double[] got = GridDisplayUnits.quantity(
                    row.get("unit").getAsString(), row.get("text").getAsString());
            if (absent(row.get("natural"))) assertNull(got, row.get("text").getAsString());
            else {
                assertNotNull(got, row.get("text").getAsString());
                close(row.get("natural").getAsDouble(), got[0], row.get("text").getAsString());
                close(row.get("halfStep").getAsDouble(), got[1], row.get("text").getAsString());
            }
        }
    }

    @Test
    void formulas() {
        for (JsonElement item : rows("convert")) {
            JsonObject row = item.getAsJsonObject();
            String formula = row.get("formula").getAsString();
            if (formula.equals("cbrt") || formula.equals("log10")) continue;
            double factor = row.has("factor") ? row.get("factor").getAsDouble() : 1.0;
            DoubleUnaryOperator[] pair = GridDisplayUnits.formula(formula, factor);
            close(
                    row.get("natural").getAsDouble(),
                    pair[1].applyAsDouble(row.get("raw").getAsDouble()),
                    formula);
            close(
                    row.get("raw").getAsDouble(),
                    pair[0].applyAsDouble(row.get("natural").getAsDouble()),
                    formula);
        }
    }

    @Test
    void toRaw() {
        for (JsonElement item : rows("toRaw")) {
            JsonObject row = item.getAsJsonObject();
            JsonObject current =
                    display(row.get("unit").getAsString(), row.get("display").getAsString());
            current.addProperty("value", row.get("value").getAsDouble());
            String requested = row.get("requested").getAsString();
            if (absent(row.get("raw")))
                assertThrows(RpcException.class, () -> GridDisplayUnits.toRaw(current, requested));
            else close(row.get("raw").getAsDouble(), GridDisplayUnits.toRaw(current, requested), requested);
        }
    }

    @Test
    void near() {
        for (JsonElement item : rows("near")) {
            JsonObject row = item.getAsJsonObject();
            JsonObject after =
                    display(row.get("unit").getAsString(), row.get("live").getAsString());
            assertEquals(
                    !row.get("near").getAsBoolean(),
                    GridDisplayUnits.mismatch(after, row.get("wanted").getAsString()),
                    row.get("wanted").getAsString() + " ~ " + row.get("live").getAsString());
        }
    }

    @Test
    void names() {
        List<String> catalog = new ArrayList<>();
        for (JsonElement name : rows("nameCatalog")) catalog.add(name.getAsString());
        for (JsonElement item : rows("names")) {
            JsonObject row = item.getAsJsonObject();
            List<String> hits = new ArrayList<>();
            for (JsonElement hit : row.getAsJsonArray("hits")) hits.add(hit.getAsString());
            assertEquals(
                    hits,
                    GridNames.resolve(catalog, name -> name, row.get("query").getAsString()));
        }
    }
}
