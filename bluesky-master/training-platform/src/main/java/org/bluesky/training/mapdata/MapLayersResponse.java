package org.bluesky.training.mapdata;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class MapLayersResponse {
    private final boolean available;
    private final List<Map<String, Object>> layers;

    private MapLayersResponse(boolean available, List<Map<String, Object>> layers) {
        this.available = available;
        this.layers = layers;
    }

    public static MapLayersResponse available(List<Map<String, Object>> layers) {
        return new MapLayersResponse(true, layers);
    }

    public static MapLayersResponse unavailable() {
        return new MapLayersResponse(false, new ArrayList<>());
    }

    public boolean isAvailable() { return available; }
    public List<Map<String, Object>> getLayers() { return layers; }
}
