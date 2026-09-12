package org.bluesky.training.adapter;

import org.bluesky.training.mapdata.MapDataClient;
import org.bluesky.training.persistence.BootstrapMapper;
import org.bluesky.training.persistence.ExerciseGroupRow;
import org.bluesky.training.mapdata.MapDataService;
import org.bluesky.training.mapdata.MapLayersResponse;
import org.bluesky.training.event.EventStreamService;
import org.bluesky.training.adapter.AdapterUnavailableException;
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
import static org.mockito.Mockito.doThrow;

class ReferenceDataSynchronizerTest {
    private static BootstrapMapper readyGroupMapper() {
        BootstrapMapper mapper = mock(BootstrapMapper.class);
        ExerciseGroupRow group = new ExerciseGroupRow();
        group.setState("READY");
        org.mockito.Mockito.when(mapper.findDefaultGroup()).thenReturn(group);
        return mapper;
    }


    @Test
    void syncsOnEachConnectionGenerationWithoutRefetchingSource() {
        MapDataClient client = mock(MapDataClient.class);
        when(client.fetch()).thenReturn(snapshot());
        MapDataService mapDataService = new MapDataService(client);
        mapDataService.initializeOnce();
        SimulationGateway gateway = mock(SimulationGateway.class);
        when(gateway.syncReferenceData(anyList()))
                .thenReturn(new ReferenceDataSyncResult(1, Collections.singletonMap("VOR", 1)));
        EventStreamService events = mock(EventStreamService.class);
        ReferenceDataSynchronizer synchronizer = new ReferenceDataSynchronizer(gateway, mapDataService, events, readyGroupMapper(), 3);

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

        new ReferenceDataSynchronizer(gateway, mapDataService,
                mock(EventStreamService.class), readyGroupMapper(), 3).onConnectionState(true);

        assertThat(mapDataService.referenceDataState().isReady()).isFalse();
        assertThat(mapDataService.referenceDataState().getStatus()).isEqualTo("SYNC_FAILED");
    }

    @Test
    void retriesTransientSyncFailureOnSameConnectionWithoutRefetchingSource() {
        MapDataClient client = mock(MapDataClient.class);
        when(client.fetch()).thenReturn(snapshot());
        MapDataService mapDataService = new MapDataService(client);
        mapDataService.initializeOnce();
        SimulationGateway gateway = mock(SimulationGateway.class);
        when(gateway.syncReferenceData(anyList()))
                .thenThrow(new AdapterUnavailableException("timeout"))
                .thenReturn(new ReferenceDataSyncResult(1, Collections.singletonMap("VOR", 1)));
        ReferenceDataSynchronizer synchronizer = new ReferenceDataSynchronizer(gateway, mapDataService, mock(EventStreamService.class), readyGroupMapper(), 3);

        synchronizer.onConnectionState(true);
        assertThat(mapDataService.referenceDataState().getStatus()).isEqualTo("SYNC_FAILED");
        synchronizer.onConnectionState(true);

        assertThat(mapDataService.referenceDataState().isReady()).isTrue();
        verify(gateway, times(2)).syncReferenceData(anyList());
        verify(client, times(1)).fetch();
    }

    @Test
    void keepsSourceFailureClassificationWhenEngineIsConnected() {
        MapDataClient client = mock(MapDataClient.class);
        when(client.fetch()).thenThrow(new IllegalStateException("bad snapshot"));
        MapDataService mapDataService = new MapDataService(client);
        mapDataService.initializeOnce();
        SimulationGateway gateway = mock(SimulationGateway.class);

        new ReferenceDataSynchronizer(gateway, mapDataService,
                mock(EventStreamService.class), readyGroupMapper(), 3).onConnectionState(true);

        assertThat(mapDataService.referenceDataState().getStatus()).isEqualTo("VALIDATION_FAILED");
        verify(gateway, times(0)).syncReferenceData(anyList());
    }

    @Test
    void pauseFailureDoesNotUndoSuccessfulReferenceDataSync() {
        MapDataClient client = mock(MapDataClient.class);
        when(client.fetch()).thenReturn(snapshot());
        MapDataService mapDataService = new MapDataService(client);
        mapDataService.initializeOnce();
        SimulationGateway gateway = mock(SimulationGateway.class);
        when(gateway.syncReferenceData(anyList()))
                .thenReturn(new ReferenceDataSyncResult(1, Collections.singletonMap("VOR", 1)));
        doThrow(new AdapterUnavailableException("pause timeout")).when(gateway).pause();

        new ReferenceDataSynchronizer(gateway, mapDataService,
                mock(EventStreamService.class), readyGroupMapper(), 3).onConnectionState(true);

        assertThat(mapDataService.referenceDataState().isReady()).isTrue();
    }

    @Test
    void doesNotPauseEngineWhenTrainingAlreadyRunning() {
        // 训练中重连：同步成功后不得暂停，否则组 RUNNING 与引擎暂停态错位、仿真冻结
        MapDataClient client = mock(MapDataClient.class);
        when(client.fetch()).thenReturn(snapshot());
        MapDataService mapDataService = new MapDataService(client);
        mapDataService.initializeOnce();
        SimulationGateway gateway = mock(SimulationGateway.class);
        when(gateway.syncReferenceData(anyList()))
                .thenReturn(new ReferenceDataSyncResult(1, Collections.singletonMap("VOR", 1)));
        BootstrapMapper mapper = mock(BootstrapMapper.class);
        ExerciseGroupRow running = new ExerciseGroupRow();
        running.setState("RUNNING");
        when(mapper.findDefaultGroup()).thenReturn(running);

        new ReferenceDataSynchronizer(gateway, mapDataService,
                mock(EventStreamService.class), mapper, 3).onConnectionState(true);

        assertThat(mapDataService.referenceDataState().isReady()).isTrue();
        verify(gateway, times(0)).pause();
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
