package org.bluesky.training.mapdata;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;

import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class MapDataService {
    private static final Logger log = LoggerFactory.getLogger(MapDataService.class);
    private final MapDataClient client;
    private final AtomicBoolean loadAttempted = new AtomicBoolean();
    private volatile MapLayersResponse cached = MapLayersResponse.unavailable();
    private volatile RuntimeReferenceCatalog catalog;
    private volatile ReferenceDataState referenceDataState = ReferenceDataState.loading();

    @Value("${bluesky.data-prep.startup-load-enabled:true}")
    private boolean startupLoadEnabled;

    public MapDataService(MapDataClient client) {
        this.client = client;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(0)
    public void initializeAtStartup() {
        if (startupLoadEnabled) initializeOnce();
    }

    public void initializeOnce() {
        if (!loadAttempted.compareAndSet(false, true)) return;
        try {
            MapLayersResponse response = client.fetch();
            RuntimeReferenceCatalog loaded = RuntimeReferenceCatalog.from(response);
            catalog = loaded;
            cached = loaded.snapshot();
            referenceDataState = ReferenceDataState.snapshotReady(loaded.counts());
        } catch (RuntimeException error) {
            log.warn("飞行数据准备地图快照不可用 target=data-prep failureType={} reason={}",
                    error.getClass().getSimpleName(), error.getMessage());
            cached = MapLayersResponse.unavailable();
            String status = error instanceof ReferenceDataException || error instanceof IllegalStateException
                    ? "VALIDATION_FAILED" : "SOURCE_UNAVAILABLE";
            if (error instanceof ResourceAccessException) status = "SOURCE_UNAVAILABLE";
            String message = "VALIDATION_FAILED".equals(status) && error.getMessage() != null
                    ? error.getMessage() : "导航参考数据未就绪";
            referenceDataState = ReferenceDataState.failed(status, message);
        }
    }

    public MapLayersResponse snapshot() { return cached; }

    public RuntimeReferenceCatalog catalog() {
        if (catalog == null) throw new ReferenceDataException("REFERENCE_DATA_NOT_READY", "导航参考数据未就绪");
        return catalog;
    }

    public ReferenceDataState referenceDataState() { return referenceDataState; }

    public void markReady() {
        if (catalog != null) referenceDataState = ReferenceDataState.ready(catalog.counts());
    }

    public void markSyncFailed(String message) {
        referenceDataState = ReferenceDataState.failed("SYNC_FAILED", message);
    }

    public void markEngineDisconnected() {
        if (catalog != null) referenceDataState = ReferenceDataState.snapshotReady(catalog.counts());
    }
}
