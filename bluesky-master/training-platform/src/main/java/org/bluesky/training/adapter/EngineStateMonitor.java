package org.bluesky.training.adapter;

import org.bluesky.training.event.EventStreamService;
import org.bluesky.training.persistence.BootstrapMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class EngineStateMonitor {
    private final SimulationGateway simulationGateway;
    private final EventStreamService eventStreamService;
    private final ReferenceDataSynchronizer referenceDataSynchronizer;
    private final BootstrapMapper bootstrapMapper;
    private Boolean lastConnected;

    public EngineStateMonitor(SimulationGateway simulationGateway, EventStreamService eventStreamService,
                              ReferenceDataSynchronizer referenceDataSynchronizer,
                              BootstrapMapper bootstrapMapper) {
        this.simulationGateway = simulationGateway;
        this.eventStreamService = eventStreamService;
        this.referenceDataSynchronizer = referenceDataSynchronizer;
        this.bootstrapMapper = bootstrapMapper;
    }

    @Scheduled(fixedDelayString = "${bluesky.adapter.health-poll-millis:3000}")
    public void poll() {
        EngineHealth health = simulationGateway.health();
        if (health == null) {
            health = new EngineHealth(false, "DISCONNECTED", "UNKNOWN", "BlueSky 状态不可用");
        }
        referenceDataSynchronizer.onConnectionState(health.isConnected());
        if (lastConnected == null || lastConnected.booleanValue() != health.isConnected()) {
            lastConnected = health.isConnected();
            // 断连不再直改组状态（评审 B3）：RUNNING→PAUSED 绕过 5.1 状态机
            // （无 PAUSING、无 Outbox、无 RECOVERING），组状态迁移只能由
            // v2 生命周期链路（健康监控→PAUSING→看门狗→RECOVERING）驱动；
            // 本监控只负责把健康事实发布给事件流。
            eventStreamService.publish("engine-state", health);
        }
        eventStreamService.publish("heartbeat", Instant.now().toString());
    }
}
