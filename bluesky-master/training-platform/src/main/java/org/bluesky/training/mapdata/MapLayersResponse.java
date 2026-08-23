package org.bluesky.training.mapdata;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class MapLayersResponse {
    private final boolean available;
    private final Long revision;
    private final List<Map<String, Object>> layers;

    private MapLayersResponse(boolean available, Long revision, List<Map<String, Object>> layers) {
        this.available = available;
        this.revision = revision;
        this.layers = layers;
    }

    public static MapLayersResponse available(Long revision, List<Map<String, Object>> layers) {
        return new MapLayersResponse(true, revision, layers);
    }

    public static MapLayersResponse unavailable() {
        return new MapLayersResponse(false, null, new ArrayList<>());
    }

    public boolean isAvailable() { return available; }
    public Long getRevision() { return revision; }
    public List<Map<String, Object>> getLayers() { return layers; }
}
