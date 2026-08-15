package org.bluesky.training.instruction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bluesky.training.TrainingPlatformApplication;
import org.bluesky.training.adapter.SimulationGateway;
import org.bluesky.training.adapter.AdapterStateProjector;
import org.bluesky.training.adapter.AdapterUnavailableException;
import org.bluesky.training.adapter.AdapterRejectedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = TrainingPlatformApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class InstructionApiTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AdapterStateProjector stateProjector;

    @MockBean
    private SimulationGateway simulationGateway;

    private String aircraftId;

    @BeforeEach
    void createAircraft() throws Exception {
        String response = mockMvc.perform(post("/api/v1/exercise-groups/GROUP-DEFAULT/aircraft")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validAircraftBody()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode aircraft = objectMapper.readTree(response);
        aircraftId = aircraft.path("id").asText();
    }

    @Test
    void dispatchesHeadingInstructionImmediatelyWhenQueueIsEmpty() throws Exception {
        mockMvc.perform(post("/api/v1/aircraft/{aircraftId}/instructions", aircraftId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\" hdg   090 \",\"insertion\":\"AFTER_CURRENT\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.text").value("HDG 090"))
                .andExpect(jsonPath("$.type").value("HDG"))
                .andExpect(jsonPath("$.status").value("EXECUTING"))
                .andExpect(jsonPath("$.sequenceNumber").value(1));

        verify(simulationGateway).executeInstruction(argThat(command ->
                "CCA3582".equals(command.getCallsign())
                        && "HDG".equals(command.getType())
                        && command.getHeadingDegrees() == 90.0));
    }

    @Test
    void dispatchesAltitudeWithExplicitVerticalSpeed() throws Exception {
        mockMvc.perform(post("/api/v1/aircraft/{aircraftId}/instructions", aircraftId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"ALT 12000 VS 1000\",\"insertion\":\"AFTER_CURRENT\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("ALT"))
                .andExpect(jsonPath("$.status").value("EXECUTING"));

        verify(simulationGateway).executeInstruction(argThat(command ->
                "ALT".equals(command.getType())
                        && command.getAltitudeFeet() == 12000.0
                        && command.getVerticalSpeedFeetPerMinute() == 1000.0));
    }

    @Test
    void distinguishesIndicatedSpeedFromMach() throws Exception {
        mockMvc.perform(post("/api/v1/aircraft/{aircraftId}/instructions", aircraftId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"SPD 250\",\"insertion\":\"AFTER_CURRENT\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("SPD"));

        verify(simulationGateway).executeInstruction(argThat(command ->
                "SPD".equals(command.getType())
                        && command.getSpeedKnots() == 250.0
                        && command.getMach() == null));
    }

    @Test
    void dispatchesMachWithoutPopulatingIndicatedSpeed() throws Exception {
        mockMvc.perform(post("/api/v1/aircraft/{aircraftId}/instructions", aircraftId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"MACH 0.78\",\"insertion\":\"AFTER_CURRENT\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("MACH"));

        verify(simulationGateway).executeInstruction(argThat(command ->
                "MACH".equals(command.getType())
                        && command.getMach() == 0.78
                        && command.getSpeedKnots() == null));
    }

    @Test
    void dispatchesDirectToWaypoint() throws Exception {
        mockMvc.perform(post("/api/v1/aircraft/{aircraftId}/instructions", aircraftId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"DCT ZBAA\",\"insertion\":\"AFTER_CURRENT\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("DCT"));

        verify(simulationGateway).executeInstruction(argThat(command ->
                "DCT".equals(command.getType())
                        && "ZBAA".equals(command.getWaypoint())
                        && command.getCommandId() != null
                        && !command.getCommandId().isEmpty()));
    }

    @Test
    void directToCompletesOnlyAfterAdapterReportsWaypointPassed() throws Exception {
        String response = submit("DCT ZBAA", "AFTER_CURRENT")
                .andExpect(jsonPath("$.status").value("EXECUTING"))
                .andReturn().getResponse().getContentAsString();
        String commandId = objectMapper.readTree(response).path("id").asText();

        stateProjector.acceptJson(directToStateFrame(41, commandId, false));
        mockMvc.perform(get("/api/v1/aircraft/{aircraftId}/instructions", aircraftId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("EXECUTING"));

        stateProjector.acceptJson(directToStateFrame(42, commandId, true));
        mockMvc.perform(get("/api/v1/aircraft/{aircraftId}/instructions", aircraftId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("COMPLETED"));
    }

    @Test
    void sendsAllEnteredRoutePointsAsReplacementRoute() throws Exception {
        mockMvc.perform(post("/api/v1/aircraft/{aircraftId}/instructions", aircraftId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"RTE ZSSS ZBAA\",\"insertion\":\"AFTER_CURRENT\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("RTE"));

        verify(simulationGateway).executeInstruction(argThat(command ->
                "RTE".equals(command.getType())
                        && command.getRoute().size() == 2
                        && "ZSSS".equals(command.getRoute().get(0))
                        && "ZBAA".equals(command.getRoute().get(1))));
    }

    @Test
    void routeChangeCompletesFromMatchingAdapterActivationReceipt() throws Exception {
        String response = submit("RTE ZSSS ZBAA", "AFTER_CURRENT")
                .andExpect(jsonPath("$.status").value("EXECUTING"))
                .andReturn().getResponse().getContentAsString();
        String commandId = objectMapper.readTree(response).path("id").asText();

        stateProjector.acceptJson(routeChangeStateFrame(43, commandId, false));
        mockMvc.perform(get("/api/v1/aircraft/{aircraftId}/instructions", aircraftId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("EXECUTING"));

        stateProjector.acceptJson(routeChangeStateFrame(44, commandId, true));
        mockMvc.perform(get("/api/v1/aircraft/{aircraftId}/instructions", aircraftId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("COMPLETED"));
    }

    @Test
    void insertsAfterCurrentBeforeCommandsAlreadyAppendedToTail() throws Exception {
        submit("HDG 090", "AFTER_CURRENT")
                .andExpect(jsonPath("$.status").value("EXECUTING"));
        submit("HDG 270", "APPEND")
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.sequenceNumber").value(2));
        submit("DCT ZBAA", "AFTER_CURRENT")
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.sequenceNumber").value(2));

        mockMvc.perform(get("/api/v1/aircraft/{aircraftId}/instructions", aircraftId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].text").value("HDG 090"))
                .andExpect(jsonPath("$[1].text").value("DCT ZBAA"))
                .andExpect(jsonPath("$[2].text").value("HDG 270"));
    }

    @Test
    void completesCurrentInstructionFromActualStateAndDispatchesNext() throws Exception {
        submit("HDG 090", "AFTER_CURRENT");
        submit("HDG 180", "APPEND");
        reset(simulationGateway);

        stateProjector.acceptJson(stateFrame(21, 90, 9000, 240));

        mockMvc.perform(get("/api/v1/aircraft/{aircraftId}/instructions", aircraftId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$[1].status").value("EXECUTING"));
        verify(simulationGateway).executeInstruction(argThat(command ->
                "HDG".equals(command.getType()) && command.getHeadingDegrees() == 180.0));
    }

    @Test
    void resolvesQueuedAltitudeVerticalSpeedFromLatestAltitudeAtDispatch() throws Exception {
        submit("ALT 12000 VS 1000", "AFTER_CURRENT");
        submit("ALT 10000 VS 1000", "APPEND")
                .andExpect(jsonPath("$.status").value("PENDING"));
        reset(simulationGateway);

        stateProjector.acceptJson(stateFrame(24, 180, 12000, 240));

        verify(simulationGateway).executeInstruction(argThat(command ->
                "ALT".equals(command.getType())
                        && command.getAltitudeFeet() == 10000.0
                        && command.getVerticalSpeedFeetPerMinute() == -1000.0));
    }

    @Test
    void failedNextDispatchDoesNotRollBackStateFrameOrRetryForever() throws Exception {
        submit("HDG 090", "AFTER_CURRENT");
        submit("HDG 180", "APPEND");
        reset(simulationGateway);
        doThrow(new AdapterUnavailableException("adapter offline"))
                .when(simulationGateway)
                .executeInstruction(argThat(command -> "HDG".equals(command.getType())
                        && command.getHeadingDegrees() == 180.0));

        stateProjector.acceptJson(stateFrame(31, 90, 10123, 240));
        stateProjector.acceptJson(stateFrame(32, 91, 10124, 240));

        mockMvc.perform(get("/api/v1/exercise-groups/GROUP-DEFAULT/aircraft"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].altitudeFeet").value(10124));
        mockMvc.perform(get("/api/v1/aircraft/{aircraftId}/instructions", aircraftId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$[1].status").value("FAILED"));
        verify(simulationGateway, times(1)).executeInstruction(argThat(command ->
                "HDG".equals(command.getType()) && command.getHeadingDegrees() == 180.0));
    }

    @Test
    void recordsFailedInstructionWhenAdapterRejectsInitialDispatch() throws Exception {
        doThrow(new AdapterRejectedException("ENGINE_REJECTED", "未知航路点"))
                .when(simulationGateway).executeInstruction(argThat(command ->
                        "DCT".equals(command.getType())));

        submitExpectingError("DCT ZBAA", "IMMEDIATE")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ENGINE_REJECTED"));

        mockMvc.perform(get("/api/v1/aircraft/{aircraftId}/instructions", aircraftId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].text").value("DCT ZBAA"))
                .andExpect(jsonPath("$[0].status").value("FAILED"))
                .andExpect(jsonPath("$[0].failureCode").value("ENGINE_REJECTED"))
                .andExpect(jsonPath("$[0].failureMessage").value("未知航路点"));
    }

    @Test
    void clearsActiveInstructionWhenFinalInstructionCompletes() throws Exception {
        submit("HDG 090", "AFTER_CURRENT");

        stateProjector.acceptJson(stateFrame(22, 90, 9000, 240));

        mockMvc.perform(get("/api/v1/exercise-groups/GROUP-DEFAULT/aircraft"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].activeInstruction").doesNotExist());
    }

    @Test
    void completingOneChannelKeepsAnotherExecutingInstructionVisible() throws Exception {
        submit("ALT 12000", "AFTER_CURRENT");
        submit("SPD 250", "AFTER_CURRENT");

        stateProjector.acceptJson(stateFrame(23, 180, 12000, 200));

        mockMvc.perform(get("/api/v1/exercise-groups/GROUP-DEFAULT/aircraft"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].activeInstruction").value("SPD 250"));
    }

    @Test
    void immediateInstructionClearsOnlyTheSameChannelQueue() throws Exception {
        submit("ALT 12000", "AFTER_CURRENT");
        submit("ALT 9000", "APPEND");
        submit("SPD 250", "AFTER_CURRENT");
        reset(simulationGateway);

        submit("ALT 6000", "IMMEDIATE")
                .andExpect(jsonPath("$.status").value("EXECUTING"));

        mockMvc.perform(get("/api/v1/aircraft/{aircraftId}/instructions", aircraftId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("CANCELLED"))
                .andExpect(jsonPath("$[1].text").value("ALT 6000"))
                .andExpect(jsonPath("$[1].status").value("EXECUTING"))
                .andExpect(jsonPath("$[2].text").value("ALT 9000"))
                .andExpect(jsonPath("$[2].status").value("CANCELLED"))
                .andExpect(jsonPath("$[3].text").value("SPD 250"))
                .andExpect(jsonPath("$[3].status").value("EXECUTING"));
        verify(simulationGateway).executeInstruction(argThat(command ->
                "ALT".equals(command.getType()) && command.getAltitudeFeet() == 6000.0));
    }

    @Test
    void executesLateralVerticalAndSpeedChannelsInParallel() throws Exception {
        submit("HDG 090", "AFTER_CURRENT")
                .andExpect(jsonPath("$.status").value("EXECUTING"));
        submit("ALT 12000 VS 1000", "AFTER_CURRENT")
                .andExpect(jsonPath("$.status").value("EXECUTING"));
        submit("SPD 250", "AFTER_CURRENT")
                .andExpect(jsonPath("$.status").value("EXECUTING"));

        verify(simulationGateway).executeInstruction(argThat(command -> "HDG".equals(command.getType())));
        verify(simulationGateway).executeInstruction(argThat(command -> "ALT".equals(command.getType())));
        verify(simulationGateway).executeInstruction(argThat(command -> "SPD".equals(command.getType())));
    }

    private org.springframework.test.web.servlet.ResultActions submit(String text, String insertion)
            throws Exception {
        return mockMvc.perform(post("/api/v1/aircraft/{aircraftId}/instructions", aircraftId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"" + text + "\",\"insertion\":\"" + insertion + "\"}"))
                .andExpect(status().isCreated());
    }

    private org.springframework.test.web.servlet.ResultActions submitExpectingError(
            String text, String insertion) throws Exception {
        return mockMvc.perform(post("/api/v1/aircraft/{aircraftId}/instructions", aircraftId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"" + text + "\",\"insertion\":\"" + insertion + "\"}"));
    }

    private String stateFrame(long sequence, double heading, double altitude, double speed) {
        return "{"
                + "\"protocolVersion\":\"1.0\",\"sequence\":" + sequence + ","
                + "\"exerciseGroupId\":\"GROUP-DEFAULT\",\"simulationTimeSeconds\":10,"
                + "\"engineState\":\"RUNNING\",\"aircraft\":[{"
                + "\"callsign\":\"CCA3582\",\"latitude\":31.2,\"longitude\":121.9,"
                + "\"headingDegrees\":" + heading + ",\"altitudeFeet\":" + altitude + ","
                + "\"speedKnots\":" + speed + ",\"mach\":0.62,"
                + "\"verticalSpeedFeetPerMinute\":0,\"route\":[\"ZSSS\",\"ZBAA\"]}]}";
    }

    private String directToStateFrame(long sequence, String commandId, boolean passed) {
        return "{"
                + "\"protocolVersion\":\"1.0\",\"sequence\":" + sequence + ","
                + "\"exerciseGroupId\":\"GROUP-DEFAULT\",\"simulationTimeSeconds\":10,"
                + "\"engineState\":\"RUNNING\",\"aircraft\":[{"
                + "\"callsign\":\"CCA3582\",\"latitude\":31.2,\"longitude\":121.9,"
                + "\"headingDegrees\":90,\"altitudeFeet\":9000,"
                + "\"speedKnots\":240,\"mach\":0.62,"
                + "\"verticalSpeedFeetPerMinute\":0,\"route\":[\"ZBAA\"],"
                + "\"directTo\":{\"commandId\":\"" + commandId + "\","
                + "\"waypoint\":\"ZBAA\",\"passed\":" + passed + "}}]}";
    }

    private String routeChangeStateFrame(long sequence, String commandId, boolean activated) {
        return "{"
                + "\"protocolVersion\":\"1.0\",\"sequence\":" + sequence + ","
                + "\"exerciseGroupId\":\"GROUP-DEFAULT\",\"simulationTimeSeconds\":10,"
                + "\"engineState\":\"RUNNING\",\"aircraft\":[{"
                + "\"callsign\":\"CCA3582\",\"latitude\":31.2,\"longitude\":121.9,"
                + "\"headingDegrees\":90,\"altitudeFeet\":9000,"
                + "\"speedKnots\":240,\"mach\":0.62,"
                + "\"verticalSpeedFeetPerMinute\":0,\"route\":[\"ZBAA\"],"
                + "\"routeChange\":{\"commandId\":\"" + commandId + "\","
                + "\"activated\":" + activated + "}}]}";
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
