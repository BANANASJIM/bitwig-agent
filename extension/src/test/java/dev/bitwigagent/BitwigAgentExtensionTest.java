package dev.bitwigagent;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** Startup configuration only; no Bitwig process or ControllerHost is created. */
public final class BitwigAgentExtensionTest {
    @Test
    void validPortsAreParsedAsDecimal() {
        assertTrue(BitwigAgentExtension.configuredPort(null) == 61172, "absent value retains default");
        assertTrue(BitwigAgentExtension.configuredPort("61174") == 61174, "private instance port is retained");
        assertTrue(BitwigAgentExtension.configuredPort("1") == 1, "lower bound");
        assertTrue(BitwigAgentExtension.configuredPort("65535") == 65535, "upper bound");
        assertTrue(BitwigAgentExtension.configuredPort("00001") == 1, "leading zero remains decimal");
    }

    @Test
    void invalidPortsAreRejectedWithAnActionableError() {
        String[] invalid = {
            "",
            "0",
            "00000",
            "65536",
            "99999",
            "100000",
            "999999999999999999",
            "-1",
            "+61174",
            " 61174",
            "61174 ",
            "61174\n",
            "61 174",
            "61174.0",
            "6e4",
            "0xef00",
            "６１１７４",
            "١",
            "127.0.0.1:61174"
        };
        for (String value : invalid) {
            {
                IllegalArgumentException expected =
                        assertThrows(IllegalArgumentException.class, () -> BitwigAgentExtension.configuredPort(value));
                assertTrue(expected.getMessage().contains("BITWIG_AGENT_PORT"), "actionable configuration error");
            }
        }
    }
}
