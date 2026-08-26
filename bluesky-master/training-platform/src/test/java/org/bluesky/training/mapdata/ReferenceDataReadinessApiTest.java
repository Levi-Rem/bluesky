package org.bluesky.training.mapdata;

import org.bluesky.training.TrainingPlatformApplication;
import org.bluesky.training.adapter.SimulationGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = TrainingPlatformApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "bluesky.reference-data.enforcement-enabled=true")
class ReferenceDataReadinessApiTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @MockBean
    private SimulationGateway simulationGateway;

    @BeforeEach
    void seedAircraftForInstructionChecks() {
        jdbc.update("DELETE FROM aircraft_instruction");
        jdbc.update("DELETE FROM exercise_aircraft");
        jdbc.update("UPDATE exercise_group SET state='READY' WHERE id='GROUP-DEFAULT'");
        jdbc.update("INSERT INTO exercise_aircraft "
                + "(id,exercise_group_id,assigned_terminal_id,callsign,aircraft_type,wake_category,"
                + "transponder_code,origin,destination,appearance_offset_minutes,latitude,longitude,"
                + "heading_degrees,altitude_feet,speed_knots,route_text) VALUES "
                + "('readiness-aircraft','GROUP-DEFAULT','PP-DEFAULT','CCA1','A320','M','1234',"
                + "'ZSSS','ZBAA',0,31.2,121.3,90,9000,250,'ZBAA')");
    }

    @Test
    void blocksPointDependentOperationsBeforeGatewayButAllowsHeading() throws Exception {
        mockMvc.perform(post("/api/v1/exercise-groups/GROUP-DEFAULT/start"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REFERENCE_DATA_NOT_READY"));
        verify(simulationGateway, never()).start();

        mockMvc.perform(post("/api/v1/exercise-groups/GROUP-DEFAULT/aircraft")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"callsign\":\"CCA2\",\"aircraftType\":\"A320\","
                                + "\"wakeCategory\":\"M\",\"origin\":\"ZSSS\",\"destination\":\"ZBAA\","
                                + "\"appearanceOffsetMinutes\":\"0000\",\"latitude\":31.2,\"longitude\":121.3,"
                                + "\"headingDegrees\":90,\"altitudeFeet\":9000,\"speedKnots\":250}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REFERENCE_DATA_NOT_READY"));
        verify(simulationGateway, never()).createAircraft(any());

        mockMvc.perform(post("/api/v1/aircraft/readiness-aircraft/instructions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"DCT PUD\",\"insertion\":\"IMMEDIATE\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REFERENCE_DATA_NOT_READY"));
        verify(simulationGateway, never()).executeInstruction(any());

        mockMvc.perform(post("/api/v1/aircraft/readiness-aircraft/instructions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"HDG 090\",\"insertion\":\"IMMEDIATE\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("EXECUTING"));
        verify(simulationGateway).executeInstruction(any());
    }
}
