package org.bluesky.training.adapter;

import org.bluesky.training.event.EventStreamService;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EngineStateMonitorTest {
    @Test
    void publishesEngineStateOnlyWhenConnectionStateChangesAndAlwaysSendsHeartbeat() {
        SimulationGateway gateway = mock(SimulationGateway.class);
        EventStreamService events = mock(EventStreamService.class);
        when(gateway.health())
                .thenReturn(new EngineHealth(true, "CONNECTED", "OPENAP", "BlueSky 已连接"))
                .thenReturn(new EngineHealth(true, "CONNECTED", "OPENAP", "BlueSky 已连接"))
                .thenReturn(new EngineHealth(false, "DISCONNECTED", "UNKNOWN", "连接超时"));
        EngineStateMonitor monitor = new EngineStateMonitor(gateway, events);

        monitor.poll();
        monitor.poll();
        monitor.poll();

        verify(events, times(2)).publish(eq("engine-state"), any(EngineHealth.class));
        verify(events, times(3)).publish(eq("heartbeat"), any());
    }
}
