package dev.bitwigagent;

import java.util.regex.*;

/** Host-free reading of the display strings Bitwig shows for mixer values. No curve is assumed: only text is read. */
final class DisplayScale {
    enum Kind { DB, PERCENT }
    /** Stand-in for a displayed "-inf": finite so it orders and compares like a number. */
    static final double MINUS_INF = -1e9;
    private static final Pattern NUMBER = Pattern.compile("([+\\-−]?)\\s*(\\d+(?:[.,]\\d+)?|[.,]\\d+)");
    private static final Pattern LEFT = Pattern.compile("(?<![a-z])(l|left)(?![a-z])");
    private static final Pattern RIGHT = Pattern.compile("(?<![a-z])(r|right)(?![a-z])");
    private static final Pattern CENTER = Pattern.compile("center|centre|(?<![a-z])c(?![a-z])");
    private DisplayScale() {}

    /** Numeric value in dB or signed percent (negative = left); NaN when the text is not understood. */
    static double parse(Kind kind, String text) {
        if (text == null) return Double.NaN;
        String t = text.trim().toLowerCase(java.util.Locale.ROOT);
        if (t.isEmpty()) return Double.NaN;
        Matcher m = NUMBER.matcher(t);
        boolean number = m.find();
        if (kind == Kind.DB) {
            if (t.contains("inf") || t.contains("∞")) return t.contains("-") || t.contains("−") ? MINUS_INF : Double.NaN;
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
