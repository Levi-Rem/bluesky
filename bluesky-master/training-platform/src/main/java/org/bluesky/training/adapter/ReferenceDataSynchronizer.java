package org.bluesky.training.adapter;

import org.bluesky.training.mapdata.MapDataService;
import org.bluesky.training.mapdata.RuntimeReferenceCatalog;
import org.springframework.stereotype.Component;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;

@Component
public class ReferenceDataSynchronizer {
    private final SimulationGateway simulationGateway;
    private final MapDataService mapDataService;
    private boolean connected;
    private boolean synchronizedForConnection;

    public ReferenceDataSynchronizer(SimulationGateway simulationGateway,
                                     MapDataService mapDataService) {
        this.simulationGateway = simulationGateway;
        this.mapDataService = mapDataService;
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
            connected = false;
            synchronizedForConnection = false;
            mapDataService.markEngineDisconnected();
            return;
        }
        if (!connected) synchronizedForConnection = false;
        connected = true;
        if (synchronizedForConnection) return;
        try {
            RuntimeReferenceCatalog catalog = mapDataService.catalog();
            ReferenceDataSyncResult result = simulationGateway.syncReferenceData(catalog.points());
            if (result.getTotal() != catalog.points().size()
                    || !result.getCounts().equals(catalog.counts())) {
                throw new IllegalStateException("仿真引擎导航参考数据确认计数不一致");
            }
            simulationGateway.pause();
            synchronizedForConnection = true;
            mapDataService.markReady();
        } catch (RuntimeException error) {
            synchronizedForConnection = true;
            mapDataService.markSyncFailed(error.getMessage() == null
                    ? "导航参考数据同步失败" : error.getMessage());
        }
    }
}
