package org.bluesky.training.aircraft;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 调用事务代理后的调度服务；与服务分离以避免同类自调用绕过 @Transactional。 */
@Component
public class AircraftAppearanceWorker {

    private final AircraftAppearanceScheduler scheduler;

    @Value("${bluesky.outbox.workers-enabled:true}")
    private boolean workersEnabled;

    public AircraftAppearanceWorker(AircraftAppearanceScheduler scheduler) {
        this.scheduler = scheduler;
    }

    @Scheduled(fixedDelayString = "${bluesky.aircraft.appearance-poll-millis:250}")
    public void scanRunningGroups() {
        if (!workersEnabled) {
            return;
        }
        for (String groupId : scheduler.runningGroupIds()) {
            scheduler.requestAllDuePlans(groupId);
        }
    }
}
