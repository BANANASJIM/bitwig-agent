package dev.bitwigagent.core;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SafetyObservationTest {
    @Test
    void theRecoveryNamesOnlyTheFieldsTransportSetAccepts() {
        SafetyObservation recording = new SafetyObservation(0, false, true, false, false);
        String notRecording =
                assertThrows(RpcException.class, recording::requireNotRecording).getMessage();
        assertTrue(
                notRecording.contains(
                        "transport.set arrangerRecordEnabled=false arrangerAutomationWriteEnabled=false, then retry"),
                notRecording);
        String stopped =
                assertThrows(RpcException.class, recording::requireStopped).getMessage();
        assertTrue(
                stopped.contains("transport.set playing=false arrangerRecordEnabled=false"
                        + " arrangerAutomationWriteEnabled=false, then retry"),
                stopped);
    }
}
