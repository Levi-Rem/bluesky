package org.bluesky.training.mapdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bluesky.training.aircraft.AircraftCreateCommand;
import org.bluesky.training.instruction.EngineInstructionCommand;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReferenceDataResolverTest {
    @Test
    void expandsAirwayAndSerializesResolvedPointObjects() throws Exception {
        MapDataService service = readyService();
        ReferenceDataResolver resolver = new ReferenceDataResolver(service, true);
        AircraftCreateCommand raw = new AircraftCreateCommand(
                "CCA1", "A320", "M", null, "ZSSS", "ZBAA", 0,
                31.0, 121.0, null, 90, 9000, 250,
                Arrays.asList("PUD", "A1", "ZBAA"));

        AircraftCreateCommand resolved = resolver.resolve(raw);
        JsonNode json = new ObjectMapper().valueToTree(resolved);

        assertThat(resolved.getRoutePoints()).extracting(RuntimeNavigationPoint::getCode)
                .containsExactly("PUD", "CEN", "ZBAA");
        assertThat(json.path("routePoints").get(1).path("id").asText()).isEqualTo("nav-cen");
        assertThat(json.path("routePoints").get(1).path("latitude").asDouble()).isEqualTo(30.8);
        assertThat(json.path("routePoints").get(1).has("name")).isFalse();
    }

    @Test
    void resolvesDirectToAndRejectsUseBeforeEngineSync() {
        MapDataService service = readyService();
        ReferenceDataResolver resolver = new ReferenceDataResolver(service, true);
        EngineInstructionCommand dct = new EngineInstructionCommand(
                "CCA1", "DCT", null, null, null, null, null, "CEN", Collections.emptyList());

        assertThat(resolver.resolve(dct).getWaypointPoint().getId()).isEqualTo("nav-cen");
        service.markEngineDisconnected();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> resolver.resolve(dct))
                .isInstanceOf(ReferenceDataException.class)
                .hasMessageContaining("等待");
    }

    private static MapDataService readyService() {
        MapDataClient client = mock(MapDataClient.class);
        when(client.fetch()).thenReturn(snapshot());
        MapDataService service = new MapDataService(client);
        service.initializeOnce();
        service.markReady();
        return service;
    }

    private static MapLayersResponse snapshot() {
        Map<String, Object> waypoint = layer("WAYPOINT");
        features(waypoint).add(point("apt-zsss", "ZSSS", "AIRPORT", 121.3, 31.2));
        features(waypoint).add(point("apt-zbaa", "ZBAA", "AIRPORT", 116.6, 40.1));
        features(waypoint).add(point("nav-pud", "PUD", "VOR", 121.8, 31.1));
        features(waypoint).add(point("nav-cen", "CEN", "WAYPOINT", 120.8, 30.8));
        Map<String, Object> airway = layer("AIRWAY");
        Map<String, Object> a1 = new LinkedHashMap<>();
        a1.put("featureId", "airway:a1");
        a1.put("code", "A1");
        a1.put("airwayDirection", "BOTH");
        a1.put("pointCodes", Arrays.asList("PUD", "CEN", "ZBAA"));
        a1.put("segmentDirections", Arrays.asList("BOTH", "BOTH"));
        features(airway).add(a1);
        return MapLayersResponse.available(Arrays.asList(waypoint, airway));
    }

    private static Map<String, Object> point(String id, String code, String type,
                                              double longitude, double latitude) {
        Map<String, Object> point = new LinkedHashMap<>();
        point.put("featureId", id);
        point.put("code", code);
        point.put("name", code);
        point.put("pointType", type);
        Map<String, Object> geometry = new LinkedHashMap<>();
        geometry.put("type", "Point");
        geometry.put("coordinates", Arrays.asList(longitude, latitude));
        point.put("geometry", geometry);
        return point;
    }

    private static Map<String, Object> layer(String category) {
        Map<String, Object> layer = new LinkedHashMap<>();
        layer.put("category", category);
        layer.put("features", new ArrayList<>());
        return layer;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> features(Map<String, Object> layer) {
        return (List<Map<String, Object>>) layer.get("features");
    }
}
