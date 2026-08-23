package org.bluesky.training.mapdata;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class MapDataService {
    private static final Logger log = LoggerFactory.getLogger(MapDataService.class);
    private final MapDataClient client;

    public MapDataService(MapDataClient client) {
        this.client = client;
    }

    public MapLayersResponse snapshot() {
        try {
            MapLayersResponse response = client.fetch();
            return response == null ? MapLayersResponse.unavailable() : response;
        } catch (RuntimeException error) {
            log.warn("飞行数据准备地图快照不可用 target=data-prep failureType={} reason={}",
                    error.getClass().getSimpleName(), error.getMessage());
            return MapLayersResponse.unavailable();
        }
    }
}
