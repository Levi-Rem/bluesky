package org.bluesky.training.workstation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bluesky.training.TrainingPlatformApplication;
import org.bluesky.training.adapter.EngineHealth;
import org.bluesky.training.adapter.SimulationGateway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = TrainingPlatformApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class WorkstationBootstrapApiTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

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
                .andExpect(jsonPath("$.instructions").isEmpty())
                .andExpect(jsonPath("$.uiParameters.trackColor").value("#3fae6d"))
                .andExpect(jsonPath("$.uiParameters.selectedTrackColor").value("#27e58d"));
    }

    @Test
    void includesAllInstructionQueuesInTheSnapshot() throws Exception {
        String aircraftBody = "{"
                + "\"callsign\":\"CCA3582\",\"aircraftType\":\"A320\","
                + "\"wakeCategory\":\"M\",\"transponderCode\":\"1234\","
                + "\"origin\":\"ZSSS\",\"destination\":\"ZBAA\","
                + "\"appearanceOffsetMinutes\":\"0000\",\"latitude\":31.1434,"
                + "\"longitude\":121.8052,\"headingDegrees\":360,"
                + "\"altitudeFeet\":9000,\"speedKnots\":250,"
                + "\"route\":[\"ZSSS\",\"ZBAA\"]}";
        String created = mockMvc.perform(post("/api/v1/exercise-groups/GROUP-DEFAULT/aircraft")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(aircraftBody))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode aircraft = objectMapper.readTree(created);

        mockMvc.perform(post("/api/v1/aircraft/{aircraftId}/instructions", aircraft.path("id").asText())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"HDG 090\",\"insertion\":\"AFTER_CURRENT\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/workstation/bootstrap"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.instructions[0].aircraftId").value(aircraft.path("id").asText()))
                .andExpect(jsonPath("$.instructions[0].text").value("HDG 090"))
                .andExpect(jsonPath("$.instructions[0].status").value("EXECUTING"));
    }
}
