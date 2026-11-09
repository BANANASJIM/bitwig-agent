package dev.bitwigagent;

/** Startup configuration only; no Bitwig process or ControllerHost is created. */
public final class ExtensionPortTest {
    public static void main(String[] args) {
        check(BitwigAgentExtension.configuredPort(null) == 61172, "absent value retains default");
        check(BitwigAgentExtension.configuredPort("61174") == 61174, "private instance port is retained");
        check(BitwigAgentExtension.configuredPort("1") == 1, "lower bound");
        check(BitwigAgentExtension.configuredPort("65535") == 65535, "upper bound");
        check(BitwigAgentExtension.configuredPort("00001") == 1, "leading zero remains decimal");
        String[] invalid = {"", "0", "00000", "65536", "99999", "100000", "999999999999999999",
                "-1", "+61174", " 61174", "61174 ", "61174\n", "61 174", "61174.0", "6e4",
                "0xef00", "６１１７４", "١", "127.0.0.1:61174"};
        for (String value : invalid) {
            try {
                BitwigAgentExtension.configuredPort(value);
                throw new AssertionError("Invalid port accepted: " + value);
            } catch (IllegalArgumentException expected) {
                check(expected.getMessage().contains("BITWIG_AGENT_PORT"), "actionable configuration error");
            }
        }
        System.out.println("ExtensionPortTest: " + (5 + invalid.length) + " startup configuration checks (no native host)");
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
