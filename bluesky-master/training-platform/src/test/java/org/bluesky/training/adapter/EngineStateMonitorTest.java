package org.bluesky.training.adapter;

import org.bluesky.training.event.EventStreamService;
import org.junit.jupiter.api.Test;
import org.bluesky.training.persistence.BootstrapMapper;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B3：断连监控只发布健康事实，不得直改组状态（RUNNING→PAUSED 绕过 5.1 状态机：
 * 无 PAUSING、无 Outbox、无 RECOVERING）。旧测试把直改固化为预期，此处反转。
 */
class EngineStateMonitorTest {
    @Test
    void publishesEngineStateOnlyWhenConnectionStateChangesAndNeverMutatesGroupState() {
        SimulationGateway gateway = mock(SimulationGateway.class);
        EventStreamService events = mock(EventStreamService.class);
        ReferenceDataSynchronizer synchronizer = mock(ReferenceDataSynchronizer.class);
        BootstrapMapper bootstrapMapper = mock(BootstrapMapper.class);
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
        verify(bootstrapMapper, times(0)).transitionGroupState(anyString(), anyString(),
                anyString());
    }
}
