package org.bluesky.training.adapter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** P04：心跳 1 秒、3 秒 DEGRADED、5 秒 DISCONNECTED（详细设计 10.1.5）。 */
class EngineHealthMonitorTest {

    private final EngineHealthMonitor monitor = new EngineHealthMonitor();

    @Test
    void givenRecentHeartbeatWhenEvaluatedThenConnected() {
        monitor.onHeartbeat(1_000L);

        assertEquals("CONNECTED", monitor.detectTimeouts(2_000L));
        assertEquals("CONNECTED", monitor.detectTimeouts(1_000L + 2_999L));
    }

    @Test
    void givenThreeSecondSilenceWhenEvaluatedThenDegraded() {
        monitor.onHeartbeat(1_000L);

        assertEquals("DEGRADED", monitor.detectTimeouts(1_000L + 3_000L));
        assertEquals("DEGRADED", monitor.detectTimeouts(1_000L + 4_999L));
    }

    @Test
    void givenFiveSecondSilenceWhenEvaluatedThenDisconnected() {
        monitor.onHeartbeat(1_000L);

        assertEquals("DISCONNECTED", monitor.detectTimeouts(1_000L + 5_000L));
        assertEquals("DISCONNECTED", monitor.detectTimeouts(60_000L));
    }

    @Test
    void givenHeartbeatAfterTimeoutWhenReceivedThenRecovers() {
        monitor.onHeartbeat(1_000L);
        assertEquals("DISCONNECTED", monitor.detectTimeouts(10_000L));

        monitor.onHeartbeat(10_100L);
        assertEquals("CONNECTED", monitor.detectTimeouts(10_200L));
    }

    @Test
    void givenNoHeartbeatYetWhenEvaluatedThenDisconnected() {
        assertEquals("DISCONNECTED", monitor.detectTimeouts(0L));
    }
}
