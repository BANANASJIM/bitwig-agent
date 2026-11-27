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
    /** "1:2.00", "4:1": two plain numbers around a colon. */
    private static final Pattern RATIO =
            Pattern.compile("^\\s*(\\d+(?:[.,]\\d+)?|[.,]\\d+)\\s*:\\s*(\\d+(?:[.,]\\d+)?|[.,]\\d+)\\s*$");
    /** The unit a ratio display is read in: the value is the compression-style strength, "1:x" and "x:1" both x. */
    public static final String RATIO_UNIT = "ratio";

    private static final Pattern MINUS_INFINITY = Pattern.compile("^\\s*[\\-−]\\s*(?:inf|∞)\\w*\\s*([a-z%°µ]+)\\s*$");

    private DisplayScale() {}

    /** A number followed by a unit ("800 Hz", "-6 dB", "1.5 kHz", "-inf dB"), or null for any other text. */
    public static Quantity quantity(String text) {
        if (text == null) return null;
        String t = text.trim().toLowerCase(java.util.Locale.ROOT);
        Matcher infinite = MINUS_INFINITY.matcher(t);
        if (infinite.matches()) return new Quantity(MINUS_INF, infinite.group(1), 0.5);
        Matcher ratio = RATIO.matcher(t);
        if (ratio.matches()) return ratio(ratio.group(1), ratio.group(2));
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

    /**
     * A ratio display "a:b" is read as larger / smaller, so Bitwig's "1:8.00" and the usual "8:1" both mean 8 and the
     * value is never below 1. Its resolution is that of the displayed larger side, divided by the smaller.
     */
    private static Quantity ratio(String left, String right) {
        double a = Double.parseDouble(left.replace(',', '.')), b = Double.parseDouble(right.replace(',', '.'));
        if (a <= 0 || b <= 0) return null;
        boolean leftBig = a > b;
        double big = leftBig ? a : b, small = leftBig ? b : a;
        return new Quantity(big / small, RATIO_UNIT, tolerance(leftBig ? left : right) / small);
    }

    private static final Pattern BARE = Pattern.compile("^\\s*[+\\-−]?\\s*(\\d+(?:[.,]\\d+)?|[.,]\\d+)\\s*$");

    /** Whether two display texts are numbers in different units ("800 Hz" and "-6 dB"); false when either has none. */
    public static boolean otherUnit(String a, String b) {
        Quantity x = quantity(a), y = quantity(b);
        return x != null && y != null && !x.unit.equals(y.unit);
    }

    /**
     * Whether a requested display text reads as the one the host shows for it: numbers in the same unit, or plain
     * numbers, equal to the coarser of the two texts' resolutions; any other text only when it is the same text.
     */
    public static boolean reads(String requested, String shown) {
        Quantity x = quantity(requested), y = quantity(shown);
        if (x != null && y != null)
            return x.unit.equals(y.unit) && Math.abs(x.value - y.value) <= Math.max(x.resolution, y.resolution);
        if (BARE.matcher(requested).matches() && BARE.matcher(shown).matches())
            return Math.abs(number(requested) - number(shown)) <= Math.max(tolerance(requested), tolerance(shown));
        return requested.trim().equalsIgnoreCase(shown.trim());
    }

    private static double number(String bare) {
        Matcher m = NUMBER.matcher(bare);
        m.find();
        return signed(m);
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
