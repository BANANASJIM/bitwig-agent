package dev.bitwigagent;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.bitwigagent.core.SharedData;
import java.util.*;
import java.util.function.DoubleUnaryOperator;
import java.util.regex.*;

/**
 * Display-text writes for the few numericUnit tokens whose raw-to-display relation is verified. The unit table, quantity
 * grammar and tolerance are bitwig_agent/shared/display.json, the same file the Python side reads; only the formulas live here.
 * A token alone does not fix the scale (Detune is hertz-token but linear), so the current raw/display pair of the very
 * parameter must reproduce under the model before any display text is converted.
 */
final class GridDisplayUnits {
    private static final class Unit {
        final Map<String, Double> suffixes;
        final DoubleUnaryOperator toRaw, toNatural;

        Unit(Map<String, Double> suffixes, DoubleUnaryOperator toRaw, DoubleUnaryOperator toNatural) {
            this.suffixes = suffixes;
            this.toRaw = toRaw;
            this.toNatural = toNatural;
        }
    }

    private static final JsonObject DATA = SharedData.read("display");
    /** Natural value is what the display text measures: Hz, percent points, dB. */
    private static final Map<String, Unit> UNITS = units(DATA.getAsJsonObject("units"));

    private static final JsonObject QUANTITY = DATA.getAsJsonObject("quantity");
    private static final Pattern TEXT = Pattern.compile(QUANTITY.get("pattern").getAsString());
    private static final double TOLERANCE_RELATIVE =
            QUANTITY.get("toleranceRelative").getAsDouble();
    private static final double TOLERANCE_ABSOLUTE =
            QUANTITY.get("toleranceAbsolute").getAsDouble();

    static DoubleUnaryOperator[] formula(String name, double factor) {
        switch (name) {
            case "midi_note_hz":
                return new DoubleUnaryOperator[] {
                    f -> 69 + 12 * Math.log(f / 440) / Math.log(2), raw -> 440 * Math.pow(2, (raw - 69) / 12)
                };
            case "scale":
                return new DoubleUnaryOperator[] {p -> p / factor, raw -> raw * factor};
            case "identity":
                return new DoubleUnaryOperator[] {d -> d, raw -> raw};
            default:
                throw new IllegalStateException("Unknown display formula " + name);
        }
    }

    private static Map<String, Unit> units(JsonObject table) {
        Map<String, Unit> out = new TreeMap<>();
        for (Map.Entry<String, JsonElement> entry : table.entrySet()) {
            JsonObject row = entry.getValue().getAsJsonObject();
            Map<String, Double> suffixes = new HashMap<>();
            for (Map.Entry<String, JsonElement> suffix :
                    row.getAsJsonObject("suffixes").entrySet())
                suffixes.put(suffix.getKey(), suffix.getValue().getAsDouble());
            double factor = row.has("factor") ? row.get("factor").getAsDouble() : 1.0;
            DoubleUnaryOperator[] pair = formula(row.get("formula").getAsString(), factor);
            out.put(entry.getKey(), new Unit(suffixes, pair[0], pair[1]));
        }
        return Collections.unmodifiableMap(out);
    }

    private GridDisplayUnits() {}

    private static final class Quantity {
        final double natural, halfStep;

        Quantity(double natural, double halfStep) {
            this.natural = natural;
            this.halfStep = halfStep;
        }
    }

    private static Quantity parse(Unit unit, String text) {
        if (text == null) return null;
        Matcher m = TEXT.matcher(text.replace('−', '-').trim());
        if (!m.matches()) return null;
        Double scale = unit.suffixes.get(m.group(4).toLowerCase(Locale.ROOT));
        if (scale == null) return null;
        String fraction = m.group(2) != null ? m.group(2) : m.group(3) != null ? m.group(3) : "";
        return new Quantity(Double.parseDouble(m.group(1)) * scale, 0.5 * Math.pow(10, -fraction.length()) * scale);
    }

    /** {natural value, half display step} of {@code text} in the named unit, or null. */
    static double[] quantity(String unit, String text) {
        Quantity q = UNITS.containsKey(unit) ? parse(UNITS.get(unit), text) : null;
        return q == null ? null : new double[] {q.natural, q.halfStep};
    }

    private static boolean near(Quantity shown, double natural) {
        return Math.abs(shown.natural - natural) <= shown.halfStep * TOLERANCE_RELATIVE + TOLERANCE_ABSOLUTE;
    }

    private static RpcException unsupported(String why) {
        return Errors.notApplied(
                Reason.UNSUPPORTED_UNIT,
                why + " Supported display units: " + new TreeSet<>(UNITS.keySet()) + "; write the raw value instead.");
    }

    private static String text(JsonObject row, String key) {
        JsonElement value = row.get(key);
        return value != null
                        && value.isJsonPrimitive()
                        && value.getAsJsonPrimitive().isString()
                ? value.getAsString()
                : null;
    }

    /** Converts requested display text to the raw value, after the row's own raw/display pair confirms the model. */
    static double toRaw(JsonObject current, String requested) {
        String token = text(current, "numericUnit");
        Unit unit = token == null ? null : UNITS.get(token);
        if (unit == null)
            throw unsupported("Parameter numericUnit "
                    + (token == null ? "is unavailable" : "'" + token + "' has no verified display conversion") + ".");
        Quantity shown = parse(unit, text(current, "display"));
        if (shown == null
                || !near(
                        shown, unit.toNatural.applyAsDouble(current.get("value").getAsDouble())))
            throw unsupported("The current raw value and display do not match the verified '" + token
                    + "' scale for this parameter.");
        if (Math.abs(shown.natural) < shown.halfStep)
            throw unsupported("The current display is zero, which cannot confirm the '" + token
                    + "' scale; write once with a raw value.");
        Quantity wanted = parse(unit, requested);
        if (wanted == null)
            throw Errors.notApplied(
                    Reason.INVALID_PARAMS,
                    "display must be a number with an explicit unit suffix " + new TreeSet<>(unit.suffixes.keySet())
                            + ", e.g. \"1.2 kHz\".");
        double raw = unit.toRaw.applyAsDouble(wanted.natural);
        if (!Double.isFinite(raw))
            throw Errors.notApplied(
                    Reason.INVALID_PARAMS, "display is outside the convertible domain for '" + token + "'.");
        return raw;
    }

    /** True when the post-write display text does not read back as the requested one (to the shown precision). */
    static boolean mismatch(JsonObject after, String requested) {
        String token = text(after, "numericUnit");
        Unit unit = token == null ? null : UNITS.get(token);
        if (unit == null) return true;
        Quantity shown = parse(unit, text(after, "display")), wanted = parse(unit, requested);
        return shown == null || wanted == null || !near(shown, wanted.natural);
    }
}
