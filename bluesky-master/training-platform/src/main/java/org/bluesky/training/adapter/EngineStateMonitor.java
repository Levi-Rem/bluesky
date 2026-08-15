package org.bluesky.training.adapter;

import org.bluesky.training.event.EventStreamService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class EngineStateMonitor {
    private final SimulationGateway simulationGateway;
    private final EventStreamService eventStreamService;
    private Boolean lastConnected;

    public EngineStateMonitor(SimulationGateway simulationGateway, EventStreamService eventStreamService) {
        this.simulationGateway = simulationGateway;
        this.eventStreamService = eventStreamService;
    }

    @Scheduled(fixedDelayString = "${bluesky.adapter.health-poll-millis:3000}")
    public void poll() {
        EngineHealth health = simulationGateway.health();
        if (health == null) {
            health = new EngineHealth(false, "DISCONNECTED", "UNKNOWN", "BlueSky 状态不可用");
        }
        if (lastConnected == null || lastConnected.booleanValue() != health.isConnected()) {
            lastConnected = health.isConnected();
            eventStreamService.publish("engine-state", health);
        }
        eventStreamService.publish("heartbeat", Instant.now().toString());
    }
}
