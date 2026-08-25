package org.bluesky.training.adapter;

import org.bluesky.training.mapdata.MapDataClient;
import org.bluesky.training.mapdata.MapDataService;
import org.bluesky.training.mapdata.MapLayersResponse;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReferenceDataSynchronizerTest {
    @Test
    void syncsOnEachConnectionGenerationWithoutRefetchingSource() {
        MapDataClient client = mock(MapDataClient.class);
        when(client.fetch()).thenReturn(snapshot());
        MapDataService mapDataService = new MapDataService(client);
        mapDataService.initializeOnce();
        SimulationGateway gateway = mock(SimulationGateway.class);
        when(gateway.syncReferenceData(anyList()))
                .thenReturn(new ReferenceDataSyncResult(1, Collections.singletonMap("VOR", 1)));
        ReferenceDataSynchronizer synchronizer = new ReferenceDataSynchronizer(gateway, mapDataService);

        synchronizer.onConnectionState(true);
        synchronizer.onConnectionState(true);
        synchronizer.onConnectionState(false);
        synchronizer.onConnectionState(true);

        verify(gateway, times(2)).syncReferenceData(anyList());
        verify(gateway, times(2)).pause();
        verify(client, times(1)).fetch();
        assertThat(mapDataService.referenceDataState().isReady()).isTrue();
    }

    @Test
    void rejectsMismatchedAdapterAcknowledgement() {
        MapDataClient client = mock(MapDataClient.class);
        when(client.fetch()).thenReturn(snapshot());
        MapDataService mapDataService = new MapDataService(client);
        mapDataService.initializeOnce();
        SimulationGateway gateway = mock(SimulationGateway.class);
        when(gateway.syncReferenceData(anyList()))
                .thenReturn(new ReferenceDataSyncResult(0, Collections.emptyMap()));

        new ReferenceDataSynchronizer(gateway, mapDataService).onConnectionState(true);

        assertThat(mapDataService.referenceDataState().isReady()).isFalse();
        assertThat(mapDataService.referenceDataState().getStatus()).isEqualTo("SYNC_FAILED");
    }

    private static MapLayersResponse snapshot() {
        List<Map<String, Object>> layers = new ArrayList<>();
        Map<String, Object> waypoint = layer("WAYPOINT");
        Map<String, Object> point = new LinkedHashMap<>();
        point.put("featureId", "waypoint:1");
        point.put("code", "PUD");
        point.put("name", "PUD");
        point.put("pointType", "VOR");
        point.put("geometry", geometry(121.8, 31.1));
        features(waypoint).add(point);
        layers.add(waypoint);
        layers.add(layer("AIRWAY"));
        return MapLayersResponse.available(layers);
    }

    private static Map<String, Object> layer(String category) {
        Map<String, Object> layer = new LinkedHashMap<>();
        layer.put("category", category);
        layer.put("features", new ArrayList<>());
        return layer;
    }

    private static Map<String, Object> geometry(double longitude, double latitude) {
        Map<String, Object> geometry = new LinkedHashMap<>();
        geometry.put("type", "Point");
        geometry.put("coordinates", Arrays.asList(longitude, latitude));
        return geometry;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> features(Map<String, Object> layer) {
        return (List<Map<String, Object>>) layer.get("features");
    }
}
