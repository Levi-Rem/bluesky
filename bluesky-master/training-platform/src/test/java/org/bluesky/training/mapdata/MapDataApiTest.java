package org.bluesky.training.mapdata;

import org.bluesky.training.TrainingPlatformApplication;
import org.bluesky.training.adapter.EngineHealth;
import org.bluesky.training.adapter.SimulationGateway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = TrainingPlatformApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MapDataApiTest {

    @Resource
    private MockMvc mockMvc;

    @MockBean
    private MapDataClient mapDataClient;

    @MockBean
    private SimulationGateway simulationGateway;

    @Test
    void proxiesAvailableRuntimeSnapshot() throws Exception {
        given(mapDataClient.fetch()).willReturn(snapshot());

        mockMvc.perform(get("/api/v1/workstation/map-layers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.revision").value(18))
                .andExpect(jsonPath("$.layers[0].category").value("WAYPOINT"));
    }

    @Test
    void silentlyDegradesWhenDataPrepFails() throws Exception {
        given(mapDataClient.fetch()).willThrow(new IllegalStateException("connection refused"));

        mockMvc.perform(get("/api/v1/workstation/map-layers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.revision").doesNotExist())
                .andExpect(jsonPath("$.layers").isEmpty());
    }

    @Test
    void mapFailureDoesNotAffectWorkstationBootstrap() throws Exception {
        given(mapDataClient.fetch()).willThrow(new IllegalStateException("timeout"));
        given(simulationGateway.health()).willReturn(
                new EngineHealth(true, "CONNECTED", "OPENAP", "BlueSky 已连接"));

        mockMvc.perform(get("/api/v1/workstation/map-layers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false));
        mockMvc.perform(get("/api/v1/workstation/bootstrap"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.terminal.id").value("PP-DEFAULT"));
    }

    private MapLayersResponse snapshot() {
        Map<String, Object> layer = new LinkedHashMap<>();
        layer.put("category", "WAYPOINT");
        layer.put("name", "航路点");
        layer.put("count", 0);
        layer.put("features", new ArrayList<>());
        List<Map<String, Object>> layers = new ArrayList<>();
        layers.add(layer);
        return MapLayersResponse.available(18L, layers);
    }
}
