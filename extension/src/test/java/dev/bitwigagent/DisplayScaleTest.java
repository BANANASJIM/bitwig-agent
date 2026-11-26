package dev.bitwigagent;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** Host-free reading of mixer display text; no claim about what Bitwig really prints beyond these shapes. */
public final class DisplayScaleTest {

    @Test
    void db() {
        eq(DisplayScale.parse(DisplayScale.Kind.DB, "-6.0 dB"), -6.0, "plain dB");
        eq(DisplayScale.parse(DisplayScale.Kind.DB, "-12 dB"), -12, "integer dB");
        eq(DisplayScale.parse(DisplayScale.Kind.DB, "+3.5dB"), 3.5, "explicit plus, no space");
        eq(DisplayScale.parse(DisplayScale.Kind.DB, "-0,5 dB"), -0.5, "decimal comma");
        eq(DisplayScale.parse(DisplayScale.Kind.DB, "−6.0 dB"), -6.0, "unicode minus");
        eq(DisplayScale.parse(DisplayScale.Kind.DB, "-inf dB"), DisplayScale.MINUS_INF, "minus infinity");
        eq(DisplayScale.parse(DisplayScale.Kind.DB, "-∞ dB"), DisplayScale.MINUS_INF, "minus infinity symbol");
        nan(DisplayScale.parse(DisplayScale.Kind.DB, "-6.0"), "no unit is not dB");
        nan(DisplayScale.parse(DisplayScale.Kind.DB, "loud"), "text is not understood");
        nan(DisplayScale.parse(DisplayScale.Kind.DB, ""), "empty");
        nan(DisplayScale.parse(DisplayScale.Kind.DB, null), "null");
    }

    @Test
    void pan() {
        eq(DisplayScale.parse(DisplayScale.Kind.PERCENT, "25% L"), -25, "left suffix");
        eq(DisplayScale.parse(DisplayScale.Kind.PERCENT, "L 25%"), -25, "left prefix");
        eq(DisplayScale.parse(DisplayScale.Kind.PERCENT, "40% R"), 40, "right");
        eq(DisplayScale.parse(DisplayScale.Kind.PERCENT, "50% left"), -50, "word left");
        eq(DisplayScale.parse(DisplayScale.Kind.PERCENT, "Center"), 0, "center");
        eq(DisplayScale.parse(DisplayScale.Kind.PERCENT, "C"), 0, "C");
        eq(DisplayScale.parse(DisplayScale.Kind.PERCENT, "0.00 %"), 0, "host centre");
        eq(DisplayScale.parse(DisplayScale.Kind.PERCENT, "-25.0 %"), -25, "host left");
        eq(DisplayScale.parse(DisplayScale.Kind.PERCENT, "-30%"), -30, "signed percent");
        eq(DisplayScale.parse(DisplayScale.Kind.PERCENT, "100% R"), 100, "full right");
        nan(DisplayScale.parse(DisplayScale.Kind.PERCENT, "25"), "bare number is ambiguous");
        nan(DisplayScale.parse(DisplayScale.Kind.PERCENT, "25% L R"), "both sides");
        nan(DisplayScale.parse(DisplayScale.Kind.PERCENT, "left-ish"), "unknown words");
    }

    @Test
    void tolerance() {
        near(DisplayScale.tolerance("-6.0 dB"), 0.05, "one decimal is a 0.05 half step");
        near(DisplayScale.tolerance("-6 dB"), 0.5, "integer display is a 0.5 half step");
        near(DisplayScale.tolerance("-6.25 dB"), 0.005, "two decimals");
        near(DisplayScale.tolerance("Center"), 0.5, "no digits falls back to 0.5");
    }

    @Test
    void aQuantityIsANumberInABaseUnitWithTheResolutionOfItsLastDigit() {
        DisplayScale.Quantity hertz = DisplayScale.quantity("800 Hz");
        assertEquals("hz", hertz.unit);
        eq(hertz.value, 800, "hertz");
        assertEquals(0.5, hertz.resolution, 1e-3, "an integer display tells hertz apart to half a hertz");
        DisplayScale.Quantity kilo = DisplayScale.quantity("1.20 kHz");
        assertEquals("hz", kilo.unit, "kHz is hertz");
        eq(kilo.value, 1200, "kilohertz scaled to hertz");
        assertEquals(5, kilo.resolution, 1e-3, "two decimals of a kilohertz are ten hertz, half of that is five");
        eq(DisplayScale.quantity("250 ms").value, 0.25, "milliseconds scaled to seconds");
        assertEquals("s", DisplayScale.quantity("0.25 s").unit);
        eq(DisplayScale.quantity("-6,5 dB").value, -6.5, "decimal comma and sign");
        eq(DisplayScale.quantity("−6 dB").value, -6, "unicode minus");
        eq(DisplayScale.quantity("-inf dB").value, DisplayScale.MINUS_INF, "minus infinity");
        assertEquals("%", DisplayScale.quantity("25 %").unit);
        assertEquals("%", DisplayScale.quantity("25%").unit);
    }

    @Test
    void textThatIsNotANumberWithAUnitIsNoQuantity() {
        for (String text : new String[] {"LP 24", "800", "Hz", "", "  ", "loud", "1 2 Hz", "800 Hz LP", "C#3"})
            assertNull(DisplayScale.quantity(text), text);
        assertNull(DisplayScale.quantity(null));
    }

    private static void eq(double a, double b, String m) {
        if (Math.abs(a - b) > 1e-9) throw new AssertionError(m + ": " + a + " != " + b);
    }

    private static void near(double a, double b, String m) {
        if (Math.abs(a - b) > 1e-6) throw new AssertionError(m + ": " + a + " != " + b);
    }

    private static void nan(double a, String m) {
        if (!Double.isNaN(a)) throw new AssertionError(m + ": expected NaN, got " + a);
    }
}
