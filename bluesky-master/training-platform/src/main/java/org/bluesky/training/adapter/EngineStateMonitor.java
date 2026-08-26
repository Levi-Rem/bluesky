package org.bluesky.training.adapter;

import org.bluesky.training.event.EventStreamService;
import org.bluesky.training.persistence.BootstrapMapper;
import org.bluesky.training.persistence.ExerciseGroupRow;
import org.bluesky.training.exercise.ExerciseGroupResponse;
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
            if (!health.isConnected()) pauseExerciseAfterDisconnect();
            eventStreamService.publish("engine-state", health);
        }
        eventStreamService.publish("heartbeat", Instant.now().toString());
    }

    private void pauseExerciseAfterDisconnect() {
        ExerciseGroupRow group = bootstrapMapper.findDefaultGroup();
        if (group != null && "RUNNING".equals(group.getState())
                && bootstrapMapper.transitionGroupState(group.getId(), "RUNNING", "PAUSED") == 1) {
            eventStreamService.publish("exercise-state",
                    new ExerciseGroupResponse(bootstrapMapper.findDefaultGroup()));
        }
    }
}
