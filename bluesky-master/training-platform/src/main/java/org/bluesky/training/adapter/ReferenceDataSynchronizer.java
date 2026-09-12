package org.bluesky.training.adapter;

import org.bluesky.training.mapdata.MapDataService;
import org.bluesky.training.mapdata.ReferenceDataException;
import org.bluesky.training.mapdata.RuntimeReferenceCatalog;
import org.bluesky.training.event.EventStreamService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;

@Component
public class ReferenceDataSynchronizer {
    private static final Logger log = LoggerFactory.getLogger(ReferenceDataSynchronizer.class);
    private final SimulationGateway simulationGateway;
    private final MapDataService mapDataService;
    private final EventStreamService eventStreamService;
    private final org.bluesky.training.persistence.BootstrapMapper bootstrapMapper;
    private final int maxAttemptsPerConnection;
    private boolean connected;
    private boolean synchronizedForConnection;
    private int attemptsForConnection;

    public ReferenceDataSynchronizer(SimulationGateway simulationGateway,
                                     MapDataService mapDataService,
                                     EventStreamService eventStreamService,
                                     org.bluesky.training.persistence.BootstrapMapper bootstrapMapper,
                                     @Value("${bluesky.reference-data.sync-max-attempts-per-connection:3}")
                                     int maxAttemptsPerConnection) {
        this.simulationGateway = simulationGateway;
        this.mapDataService = mapDataService;
        this.eventStreamService = eventStreamService;
        this.bootstrapMapper = bootstrapMapper;
        this.maxAttemptsPerConnection = Math.max(1, maxAttemptsPerConnection);
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(1)
    public synchronized void synchronizeAtStartup() {
        EngineHealth health = simulationGateway.health();
        connected = false;
        synchronizedForConnection = false;
        onConnectionState(health != null && health.isConnected());
    }

    public synchronized void onConnectionState(boolean nowConnected) {
        if (!nowConnected) {
            boolean changed = connected;
            connected = false;
            synchronizedForConnection = false;
            attemptsForConnection = 0;
            if (changed) {
                mapDataService.markEngineDisconnected();
                publishState();
            }
            return;
        }
        if (!connected) {
            synchronizedForConnection = false;
            attemptsForConnection = 0;
        }
        connected = true;
        if (synchronizedForConnection || attemptsForConnection >= maxAttemptsPerConnection) return;
        RuntimeReferenceCatalog catalog;
        try {
            catalog = mapDataService.catalog();
        } catch (ReferenceDataException error) {
            attemptsForConnection = maxAttemptsPerConnection;
            publishState();
            return;
        }
        attemptsForConnection++;
        try {
            ReferenceDataSyncResult result = simulationGateway.syncReferenceData(catalog.points());
            if (result.getTotal() != catalog.points().size()
                    || !result.getCounts().equals(catalog.counts())) {
                throw new IllegalStateException("仿真引擎导航参考数据确认计数不一致");
            }
            synchronizedForConnection = true;
            mapDataService.markReady();
            publishState();
            try {
                // 同步后停放引擎等待开训；但训练进行中（组 RUNNING）重连不得
                // 暂停，否则组状态与引擎运行态错位、仿真静默冻结
                if (!"RUNNING".equals(groupState())) {
                    simulationGateway.pause();
                }
            } catch (RuntimeException pauseError) {
                log.warn("导航参考数据已同步，但暂停仿真引擎失败 reason={}", pauseError.getMessage());
            }
        } catch (RuntimeException error) {
            mapDataService.markSyncFailed(error.getMessage() == null
                    ? "导航参考数据同步失败" : error.getMessage());
            publishState();
        }
    }

    private void publishState() {
        eventStreamService.publish("reference-data-state", mapDataService.referenceDataState());
    }

    private String groupState() {
        try {
            org.bluesky.training.persistence.ExerciseGroupRow group =
                    bootstrapMapper.findDefaultGroup();
            return group == null ? null : group.getState();
        } catch (RuntimeException error) {
            return null;
        }
    }
}
