package org.bluesky.training.reference;

import org.bluesky.training.TrainingPlatformApplication;
import org.bluesky.training.adapter.SimulationGateway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;

import static org.mockito.BDDMockito.given;
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

    @Test
    void exposesAirportsWaypointsAndAircraftTypesFromAdapter() throws Exception {
        given(simulationGateway.searchReference("AIRPORT", "ZSS", 20))
                .willReturn(Collections.singletonList(
                        new ReferenceItem("ZSSS", "SHANGHAI HONGQIAO", 31.2, 121.3)));
        given(simulationGateway.searchReference("WAYPOINT", "CEN", 20))
                .willReturn(Collections.singletonList(
                        new ReferenceItem("CEN", "CEN", 30.0, 120.0)));
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
    }
}
