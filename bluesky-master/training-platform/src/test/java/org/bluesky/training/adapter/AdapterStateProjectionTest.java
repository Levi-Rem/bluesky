package org.bluesky.training.adapter;

import org.bluesky.training.TrainingPlatformApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = TrainingPlatformApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext
class AdapterStateProjectionTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AdapterStateProjector stateProjector;

    @MockBean
    private SimulationGateway simulationGateway;

    @Test
    void projectsActualEngineStateToRestAndSseWithoutWritingTrackHistory() throws Exception {
        mockMvc.perform(post("/api/v1/exercise-groups/GROUP-DEFAULT/aircraft")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validAircraftBody()))
                .andExpect(status().isCreated());
        MvcResult stream = mockMvc.perform(get("/api/v1/events")
                        .param("exerciseGroupId", "GROUP-DEFAULT")
                        .header("Accept", "text/event-stream"))
                .andExpect(request().asyncStarted())
                .andReturn();

        stateProjector.acceptJson("{"
                + "\"protocolVersion\":\"1.0\","
                + "\"sequence\":7,"
                + "\"exerciseGroupId\":\"GROUP-DEFAULT\","
                + "\"simulationTimeSeconds\":42.5,"
                + "\"engineState\":\"RUNNING\","
                + "\"aircraft\":[{"
                + "\"callsign\":\"CCA3582\","
                + "\"latitude\":31.2,\"longitude\":121.9,"
                + "\"headingDegrees\":87.5,\"altitudeFeet\":10120,"
                + "\"speedKnots\":268,\"verticalSpeedFeetPerMinute\":980,"
                + "\"route\":[\"CON\",\"ZBAA\"]"
                + "}]}" );

        mockMvc.perform(get("/api/v1/exercise-groups/GROUP-DEFAULT/aircraft"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].latitude").value(31.2))
                .andExpect(jsonPath("$[0].longitude").value(121.9))
                .andExpect(jsonPath("$[0].headingDegrees").value(87.5))
                .andExpect(jsonPath("$[0].altitudeFeet").value(10120))
                .andExpect(jsonPath("$[0].speedKnots").value(268))
                .andExpect(jsonPath("$[0].verticalSpeedFeetPerMinute").value(980))
                .andExpect(jsonPath("$[0].route[0]").value("CON"));
        assertThat(stream.getResponse().getContentAsString())
                .contains("event:aircraft-upserted")
                .contains("event:exercise-state")
                .contains("\"simulationTimeSeconds\":43")
                .contains("CCA3582")
                .contains("10120");
    }

    @Test
    void acceptsSequenceRestartFromANewAdapterInstance() throws Exception {
        stateProjector.acceptJson("{\"protocolVersion\":\"1.0\","
                + "\"instanceId\":\"adapter-a\",\"sequence\":99,"
                + "\"simulationTimeSeconds\":99,\"aircraft\":[]}");
        stateProjector.acceptJson("{\"protocolVersion\":\"1.0\","
                + "\"instanceId\":\"adapter-b\",\"sequence\":1,"
                + "\"simulationTimeSeconds\":1,\"aircraft\":[]}");

        mockMvc.perform(get("/api/v1/workstation/bootstrap"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exerciseGroup.simulationTimeSeconds").value(1));
    }

    @Test
    void rejectsDelayedFrameFromAnAdapterInstanceAlreadyReplaced() throws Exception {
        stateProjector.acceptJson("{\"protocolVersion\":\"1.0\","
                + "\"instanceId\":\"adapter-a\",\"sequence\":99,"
                + "\"simulationTimeSeconds\":99,\"aircraft\":[]}");
        stateProjector.acceptJson("{\"protocolVersion\":\"1.0\","
                + "\"instanceId\":\"adapter-b\",\"sequence\":1,"
                + "\"simulationTimeSeconds\":1,\"aircraft\":[]}");
        stateProjector.acceptJson("{\"protocolVersion\":\"1.0\","
                + "\"instanceId\":\"adapter-a\",\"sequence\":100,"
                + "\"simulationTimeSeconds\":100,\"aircraft\":[]}");

        mockMvc.perform(get("/api/v1/workstation/bootstrap"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exerciseGroup.simulationTimeSeconds").value(1));
    }

    private String validAircraftBody() {
        return "{"
                + "\"callsign\":\"CCA3582\",\"aircraftType\":\"A320\","
                + "\"wakeCategory\":\"M\",\"transponderCode\":\"1234\","
                + "\"origin\":\"ZSSS\",\"destination\":\"ZBAA\","
                + "\"appearanceOffsetMinutes\":\"0000\",\"latitude\":31.1434,"
                + "\"longitude\":121.8052,\"headingDegrees\":360,"
                + "\"altitudeFeet\":9000,\"speedKnots\":250,"
                + "\"route\":[\"ZSSS\",\"ZBAA\"]}";
    }
}
