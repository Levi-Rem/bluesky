package org.bluesky.training.reference;

import org.bluesky.training.TrainingPlatformApplication;
import org.bluesky.training.adapter.SimulationGateway;
import org.bluesky.training.mapdata.MapDataService;
import org.bluesky.training.mapdata.MapLayersResponse;
import org.bluesky.training.mapdata.RuntimeReferenceCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = TrainingPlatformApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReferenceApiTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SimulationGateway simulationGateway;

    @MockBean
    private MapDataService mapDataService;

    @Test
    void exposesAirportsAndWaypointsFromStartupCatalogAndAircraftTypesFromAdapter() throws Exception {
        given(mapDataService.catalog()).willReturn(catalog());
        given(simulationGateway.searchReference("AIRCRAFT_TYPE", "A32", 20))
                .willReturn(Collections.singletonList(
                        new ReferenceItem("A320", "A320", null, null)));

        mockMvc.perform(get("/api/v1/reference/airports").param("query", "zss"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("ZSSS"));
        mockMvc.perform(get("/api/v1/reference/waypoints").param("query", "cen"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("CEN"));
        mockMvc.perform(get("/api/v1/reference/aircraft-types").param("query", "a32"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("A320"));

        verify(simulationGateway, never()).searchReference("AIRPORT", "ZSS", 20);
        verify(simulationGateway, never()).searchReference("WAYPOINT", "CEN", 20);
    }

    private static RuntimeReferenceCatalog catalog() {
        List<Map<String, Object>> layers = new ArrayList<>();
        Map<String, Object> points = layer("WAYPOINT");
        features(points).add(point("airport:zsss", "ZSSS", "SHANGHAI HONGQIAO", "AIRPORT", 121.3, 31.2));
        features(points).add(point("waypoint:cen", "CEN", "CEN", "WAYPOINT", 120.0, 30.0));
        layers.add(points);
        layers.add(layer("AIRWAY"));
        return RuntimeReferenceCatalog.from(MapLayersResponse.available(layers));
    }

    private static Map<String, Object> layer(String category) {
        Map<String, Object> layer = new LinkedHashMap<>();
        layer.put("category", category);
        layer.put("features", new ArrayList<>());
        return layer;
    }

    private static Map<String, Object> point(String id, String code, String name, String type,
                                              double longitude, double latitude) {
        Map<String, Object> point = new LinkedHashMap<>();
        point.put("featureId", id);
        point.put("code", code);
        point.put("name", name);
        point.put("pointType", type);
        Map<String, Object> geometry = new LinkedHashMap<>();
        geometry.put("type", "Point");
        geometry.put("coordinates", java.util.Arrays.asList(longitude, latitude));
        point.put("geometry", geometry);
        return point;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> features(Map<String, Object> layer) {
        return (List<Map<String, Object>>) layer.get("features");
    }
}
