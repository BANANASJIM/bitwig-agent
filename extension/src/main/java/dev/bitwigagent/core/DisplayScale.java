package dev.bitwigagent.core;

import java.util.regex.*;

/** Host-free reading of the display strings Bitwig shows for mixer values. No curve is assumed: only text is read. */
public final class DisplayScale {
    public enum Kind {
        DB,
        PERCENT
    }
    /** Stand-in for a displayed "-inf": finite so it orders and compares like a number. */
    public static final double MINUS_INF = -1e9;

    private static final Pattern NUMBER = Pattern.compile("([+\\-−]?)\\s*(\\d+(?:[.,]\\d+)?|[.,]\\d+)");
    private static final Pattern LEFT = Pattern.compile("(?<![a-z])(l|left)(?![a-z])");
    private static final Pattern RIGHT = Pattern.compile("(?<![a-z])(r|right)(?![a-z])");
    private static final Pattern CENTER = Pattern.compile("center|centre|(?<![a-z])c(?![a-z])");

    /** A displayed number with its unit, in the unit's base scale: "1.2 kHz" is 1200 Hz, "-6 dB" is -6 dB. */
    public static final class Quantity {
        public final double value;
        /** The base unit, lower case: hz, s, db, %, st, ... */
        final String unit;
        /** Half of the last displayed digit, in the base unit: what the host's own text can tell apart. */
        final double resolution;

        private Quantity(double value, String unit, double resolution) {
            this.value = value;
            this.unit = unit;
            this.resolution = resolution;
        }
    }

    private static final Pattern QUANTITY =
            Pattern.compile("^\\s*([+\\-−]?)\\s*(\\d+(?:[.,]\\d+)?|[.,]\\d+)\\s*([a-z%°µ]+)\\s*$");
    private static final Pattern MINUS_INFINITY = Pattern.compile("^\\s*[\\-−]\\s*(?:inf|∞)\\w*\\s*([a-z%°µ]+)\\s*$");

    private DisplayScale() {}

    /** A number followed by a unit ("800 Hz", "-6 dB", "1.5 kHz", "-inf dB"), or null for any other text. */
    public static Quantity quantity(String text) {
        if (text == null) return null;
        String t = text.trim().toLowerCase(java.util.Locale.ROOT);
        Matcher infinite = MINUS_INFINITY.matcher(t);
        if (infinite.matches()) return new Quantity(MINUS_INF, infinite.group(1), 0.5);
        Matcher m = QUANTITY.matcher(t);
        if (!m.matches()) return null;
        double number = Double.parseDouble(m.group(2).replace(',', '.'));
        if (m.group(1).equals("-") || m.group(1).equals("−")) number = -number;
        String unit = m.group(3);
        double factor = 1;
        switch (unit) {
            case "khz":
                unit = "hz";
                factor = 1000;
                break;
            case "ms":
                unit = "s";
                factor = 1e-3;
                break;
            case "µs":
            case "us":
                unit = "s";
                factor = 1e-6;
                break;
            default:
        }
        return new Quantity(number * factor, unit, tolerance(t) * factor);
    }

    /** Numeric value in dB or signed percent (negative = left); NaN when the text is not understood. */
    public static double parse(Kind kind, String text) {
        if (text == null) return Double.NaN;
        String t = text.trim().toLowerCase(java.util.Locale.ROOT);
        if (t.isEmpty()) return Double.NaN;
        Matcher m = NUMBER.matcher(t);
        boolean number = m.find();
        if (kind == Kind.DB) {
            if (t.contains("inf") || t.contains("∞"))
                return t.contains("-") || t.contains("−") ? MINUS_INF : Double.NaN;
            if (!number || !t.contains("db")) return Double.NaN;
            return signed(m);
        }
        boolean left = LEFT.matcher(t).find(), right = RIGHT.matcher(t).find();
        if (left && right) return Double.NaN;
        if (!number) return CENTER.matcher(t).find() && !left && !right ? 0 : Double.NaN;
        if (!t.contains("%") && !left && !right) return Double.NaN;
        double value = signed(m);
        if (left) return -Math.abs(value);
        if (right) return Math.abs(value);
        return value;
    }

    private static double signed(Matcher m) {
        double value = Double.parseDouble(m.group(2).replace(',', '.'));
        return m.group(1).isEmpty() || m.group(1).equals("+") ? value : -value;
    }

    /** Half of the last displayed digit: the resolution the host's own text can distinguish. */
    static double tolerance(String observed) {
        Matcher m = NUMBER.matcher(observed == null ? "" : observed);
        if (!m.find()) return 0.5;
        String digits = m.group(2).replace(',', '.');
        int dot = digits.indexOf('.');
        return 0.5 * Math.pow(10, -(dot < 0 ? 0 : digits.length() - dot - 1)) + 1e-9;
    }
}
