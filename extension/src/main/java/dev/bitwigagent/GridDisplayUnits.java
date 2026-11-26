package dev.bitwigagent;

import static dev.bitwigagent.HostServices.failure;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.*;
import java.util.function.DoubleUnaryOperator;
import java.util.regex.*;

/**
 * Display-text writes for the few numericUnit tokens whose raw-to-display relation is verified; the only unit table.
 * Evidence: percent raw*100 and decibel raw=dB (recorded grid.get rows, runs/2026-10-17-grid-authoring);
 * hertz raw=MIDI note, f=440*2^((raw-69)/12) (Low-pass CUTOFF 84 -> "1.05 kHz", 96 -> "2.09 kHz" on a 6.1.4 host).
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
    /** Natural value is what the display text measures: Hz, percent points, dB. */
    private static final Map<String, Unit> UNITS = Map.of(
            "hertz",
                    new Unit(
                            Map.of("hz", 1.0, "khz", 1000.0),
                            f -> 69 + 12 * Math.log(f / 440) / Math.log(2),
                            raw -> 440 * Math.pow(2, (raw - 69) / 12)),
            "percent", new Unit(Map.of("%", 1.0), p -> p / 100, raw -> raw * 100),
            "decibel", new Unit(Map.of("db", 1.0), d -> d, raw -> raw));

    private static final Pattern TEXT = Pattern.compile("([+-]?(?:\\d+(?:\\.(\\d*))?|\\.(\\d+)))\\s*([A-Za-z%]+)");

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

    private static boolean near(Quantity shown, double natural) {
        return Math.abs(shown.natural - natural) <= shown.halfStep * 1.000001 + 1e-12;
    }

    private static RpcException unsupported(String why) {
        return failure(
                "unsupported_unit",
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
            throw failure(
                    "invalid_params",
                    "display must be a number with an explicit unit suffix " + new TreeSet<>(unit.suffixes.keySet())
                            + ", e.g. \"1.2 kHz\".");
        double raw = unit.toRaw.applyAsDouble(wanted.natural);
        if (!Double.isFinite(raw))
            throw failure("invalid_params", "display is outside the convertible domain for '" + token + "'.");
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
