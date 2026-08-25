package org.bluesky.training.mapdata;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MapDataServiceTest {

    @Test
    void loadsRemoteSnapshotOnlyOnceAndServesTheCachedValue() {
        MapDataClient client = mock(MapDataClient.class);
        when(client.fetch()).thenReturn(snapshot());
        MapDataService service = new MapDataService(client);

        service.initializeOnce();
        service.initializeOnce();

        assertThat(service.snapshot().isAvailable()).isTrue();
        assertThat(service.catalog().points()).hasSize(2);
        verify(client, times(1)).fetch();
    }

    @Test
    void doesNotRetryAfterStartupFailure() {
        MapDataClient client = mock(MapDataClient.class);
        when(client.fetch()).thenThrow(new IllegalStateException("timeout"));
        MapDataService service = new MapDataService(client);

        service.initializeOnce();
        service.initializeOnce();

        assertThat(service.snapshot().isAvailable()).isFalse();
        assertThat(service.referenceDataState().isReady()).isFalse();
        assertThat(service.referenceDataState().getStatus()).isEqualTo("SOURCE_UNAVAILABLE");
        verify(client, times(1)).fetch();
    }

    static MapLayersResponse snapshot() {
        List<Map<String, Object>> layers = new ArrayList<>();
        Map<String, Object> waypoint = layer("WAYPOINT", "航路点");
        features(waypoint).add(point("PUD", "VOR", 121.8, 31.1));
        features(waypoint).add(point("AKOMA", "WAYPOINT", 120.8, 30.8));
        waypoint.put("count", 2);
        layers.add(waypoint);
        layers.add(layer("AIRWAY", "航线"));
        layers.add(layer("PHYSICAL_SECTOR", "扇区"));
        layers.add(layer("WEATHER", "天气"));
        return MapLayersResponse.available(layers);
    }

    private static Map<String, Object> point(String code, String type, double longitude, double latitude) {
        Map<String, Object> feature = new LinkedHashMap<>();
        feature.put("featureId", "waypoint:" + code.toLowerCase());
        feature.put("featureType", "WAYPOINT");
        feature.put("pointType", type);
        feature.put("code", code);
        feature.put("name", code);
        Map<String, Object> geometry = new LinkedHashMap<>();
        geometry.put("type", "Point");
        List<Double> coordinate = new ArrayList<>();
        coordinate.add(longitude);
        coordinate.add(latitude);
        geometry.put("coordinates", coordinate);
        feature.put("geometry", geometry);
        return feature;
    }

    private static Map<String, Object> layer(String category, String name) {
        Map<String, Object> layer = new LinkedHashMap<>();
        layer.put("category", category);
        layer.put("name", name);
        layer.put("count", 0);
        layer.put("features", new ArrayList<>());
        return layer;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> features(Map<String, Object> layer) {
        return (List<Map<String, Object>>) layer.get("features");
    }
}
