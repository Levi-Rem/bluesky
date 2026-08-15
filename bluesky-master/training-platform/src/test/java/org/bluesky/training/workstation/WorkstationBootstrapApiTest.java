package org.bluesky.training.workstation;

import org.bluesky.training.TrainingPlatformApplication;
import org.bluesky.training.adapter.EngineHealth;
import org.bluesky.training.adapter.SimulationGateway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = TrainingPlatformApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WorkstationBootstrapApiTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SimulationGateway simulationGateway;

    @Test
    void returnsResetDefaultWorkstationSnapshot() throws Exception {
        given(simulationGateway.health())
                .willReturn(new EngineHealth(true, "CONNECTED", "OPENAP", "BlueSky 已连接"));

        mockMvc.perform(get("/api/v1/workstation/bootstrap"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.terminal.id").value("PP-DEFAULT"))
                .andExpect(jsonPath("$.exerciseGroup.id").value("GROUP-DEFAULT"))
                .andExpect(jsonPath("$.exerciseGroup.state").value("READY"))
                .andExpect(jsonPath("$.exerciseGroup.simulationTimeSeconds").value(0))
                .andExpect(jsonPath("$.engine.connected").value(true))
                .andExpect(jsonPath("$.engine.performanceModel").value("OPENAP"))
                .andExpect(jsonPath("$.aircraft").isEmpty())
                .andExpect(jsonPath("$.uiParameters.trackColor").value("#58d7ff"));
    }
}
