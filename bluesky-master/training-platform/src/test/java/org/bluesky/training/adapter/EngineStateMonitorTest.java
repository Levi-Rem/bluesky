package org.bluesky.training.adapter;

import org.bluesky.training.event.EventStreamService;
import org.junit.jupiter.api.Test;
import org.bluesky.training.persistence.BootstrapMapper;
import org.bluesky.training.persistence.ExerciseGroupRow;

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
        ReferenceDataSynchronizer synchronizer = mock(ReferenceDataSynchronizer.class);
        BootstrapMapper bootstrapMapper = mock(BootstrapMapper.class);
        ExerciseGroupRow running = new ExerciseGroupRow();
        running.setId("GROUP-DEFAULT");
        running.setName("default");
        running.setState("RUNNING");
        when(bootstrapMapper.findDefaultGroup()).thenReturn(running);
        when(bootstrapMapper.transitionGroupState("GROUP-DEFAULT", "RUNNING", "PAUSED"))
                .thenReturn(1);
        when(gateway.health())
                .thenReturn(new EngineHealth(true, "CONNECTED", "OPENAP", "BlueSky 已连接"))
                .thenReturn(new EngineHealth(true, "CONNECTED", "OPENAP", "BlueSky 已连接"))
                .thenReturn(new EngineHealth(false, "DISCONNECTED", "UNKNOWN", "连接超时"));
        EngineStateMonitor monitor = new EngineStateMonitor(
                gateway, events, synchronizer, bootstrapMapper);

        monitor.poll();
        monitor.poll();
        monitor.poll();

        verify(events, times(2)).publish(eq("engine-state"), any(EngineHealth.class));
        verify(events, times(3)).publish(eq("heartbeat"), any());
        verify(synchronizer, times(2)).onConnectionState(true);
        verify(synchronizer).onConnectionState(false);
        verify(events, times(0)).publish(eq("reference-data-state"), any());
        verify(bootstrapMapper).transitionGroupState("GROUP-DEFAULT", "RUNNING", "PAUSED");
    }
}
