package org.bluesky.training.aircraft;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bluesky.training.TrainingPlatformApplication;
import org.bluesky.training.adapter.SimulationGateway;
import org.bluesky.training.adapter.AdapterUnavailableException;
import org.bluesky.training.adapter.AdapterRejectedException;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = TrainingPlatformApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class AircraftApiTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private SimulationGateway simulationGateway;

    @Test
    void createsAircraftInEngineAndAssignsItToDefaultTerminal() throws Exception {
        String body = validAircraftBody("1234");

        mockMvc.perform(post("/api/v1/exercise-groups/GROUP-DEFAULT/aircraft")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.callsign").value("CCA3582"))
                .andExpect(jsonPath("$.assignedTerminalId").value("PP-DEFAULT"))
                .andExpect(jsonPath("$.transponderCode").value("1234"))
                .andExpect(jsonPath("$.route[0]").value("CEN"));

        verify(simulationGateway).createAircraft(any());

        mockMvc.perform(get("/api/v1/exercise-groups/GROUP-DEFAULT/aircraft"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].callsign").value("CCA3582"));
    }

    @Test
    void rejectsInvalidTransponderCodesBeforeCallingEngine() throws Exception {
        for (String invalidCode : new String[]{"0000", "1288", "777", "ABCD"}) {
            mockMvc.perform(post("/api/v1/exercise-groups/GROUP-DEFAULT/aircraft")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validAircraftBody(invalidCode)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("transponderCode"));
        }

        verify(simulationGateway, never()).createAircraft(any());
    }

    @Test
    void acceptsMissingOptionalTransponderCodeAsInvalidBusinessValue() throws Exception {
        mockMvc.perform(post("/api/v1/exercise-groups/GROUP-DEFAULT/aircraft")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validAircraftBody("1234")
                                .replace("\"transponderCode\":\"1234\"", "\"transponderCode\":null")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.transponderCode").doesNotExist());
    }

    @Test
    void normalizesBlankOptionalTransponderCodeToMissingValue() throws Exception {
        mockMvc.perform(post("/api/v1/exercise-groups/GROUP-DEFAULT/aircraft")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validAircraftBody("")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.transponderCode").doesNotExist());

        mockMvc.perform(get("/api/v1/exercise-groups/GROUP-DEFAULT/aircraft"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].transponderCode").doesNotExist());
    }

    @Test
    void rejectsAppearanceOffsetThatIsNotExactlyFourDigits() throws Exception {
        for (String invalidValue : new String[]{"\"12\"", "\"00100\"", "12", "null"}) {
            mockMvc.perform(post("/api/v1/exercise-groups/GROUP-DEFAULT/aircraft")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validAircraftBody("1234")
                                    .replace("\"appearanceOffsetMinutes\":\"0000\"",
                                            "\"appearanceOffsetMinutes\":" + invalidValue)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[0].field")
                            .value("appearanceOffsetMinutes"));
        }
    }

    @Test
    void rejectsMissingPositionAndInvalidCoreFieldsBeforeCallingEngine() throws Exception {
        String body = validAircraftBody("1234")
                .replace("\"latitude\":31.1434,", "\"latitude\":null,")
                .replace("\"longitude\":121.8052,", "\"longitude\":null,");

        mockMvc.perform(post("/api/v1/exercise-groups/GROUP-DEFAULT/aircraft")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("position"));

        verify(simulationGateway, never()).createAircraft(any());
    }

    @Test
    void rejectsCallsignsOutsideTwoToSevenUppercaseAlphanumericCharacters() throws Exception {
        for (String invalid : new String[]{"A", "CCA-1", "CCA 1", "国航1", "ABCDEFGH"}) {
            mockMvc.perform(post("/api/v1/exercise-groups/GROUP-DEFAULT/aircraft")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validAircraftBody("1234")
                                    .replace("\"callsign\":\"cca3582\"",
                                            "\"callsign\":\"" + invalid + "\"")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("callsign"));
        }

        verify(simulationGateway, never()).createAircraft(any());
    }

    @Test
    void rejectsDuplicateCallsignBeforeSecondEngineCreate() throws Exception {
        mockMvc.perform(post("/api/v1/exercise-groups/GROUP-DEFAULT/aircraft")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validAircraftBody("1234")))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/exercise-groups/GROUP-DEFAULT/aircraft")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validAircraftBody("1234")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("callsign"));

        verify(simulationGateway, times(1)).createAircraft(any());
    }

    @Test
    void mapsEngineRejectionToStableServiceUnavailableResponse() throws Exception {
        doThrow(new AdapterUnavailableException("BlueSky Adapter 拒绝未知机型"))
                .when(simulationGateway).createAircraft(any());

        mockMvc.perform(post("/api/v1/exercise-groups/GROUP-DEFAULT/aircraft")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validAircraftBody("1234")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ENGINE_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("BlueSky Adapter 拒绝未知机型"));

        mockMvc.perform(get("/api/v1/exercise-groups/GROUP-DEFAULT/aircraft"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void mapsExplicitEngineRejectionAndReturnsCorrelatedRequestId() throws Exception {
        doThrow(new AdapterRejectedException("ENGINE_REJECTED", "未知机型"))
                .when(simulationGateway).createAircraft(any());

        MvcResult result = mockMvc.perform(post("/api/v1/exercise-groups/GROUP-DEFAULT/aircraft")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validAircraftBody("1234")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ENGINE_REJECTED"))
                .andExpect(jsonPath("$.requestId").isNotEmpty())
                .andReturn();

        JsonNode error = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(result.getResponse().getHeader("X-Request-Id"))
                .isEqualTo(error.path("requestId").asText());
    }

    @Test
    void deletesAircraftFromEngineAndKeepsRepeatedDeleteIdempotent() throws Exception {
        String responseBody = mockMvc.perform(post("/api/v1/exercise-groups/GROUP-DEFAULT/aircraft")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validAircraftBody("1234")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode created = objectMapper.readTree(responseBody);
        String aircraftId = created.path("id").asText();

        mockMvc.perform(delete("/api/v1/aircraft/{aircraftId}", aircraftId))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/v1/aircraft/{aircraftId}", aircraftId))
                .andExpect(status().isNoContent());

        verify(simulationGateway, times(1)).deleteAircraft("CCA3582");
        mockMvc.perform(get("/api/v1/exercise-groups/GROUP-DEFAULT/aircraft"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void publishesAircraftDeletedAfterSuccessfulDelete() throws Exception {
        String responseBody = mockMvc.perform(post("/api/v1/exercise-groups/GROUP-DEFAULT/aircraft")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validAircraftBody("1234")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String aircraftId = objectMapper.readTree(responseBody).path("id").asText();
        MvcResult stream = mockMvc.perform(get("/api/v1/events")
                        .param("exerciseGroupId", "GROUP-DEFAULT")
                        .header("Accept", "text/event-stream"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(delete("/api/v1/aircraft/{aircraftId}", aircraftId))
                .andExpect(status().isNoContent());

        assertThat(stream.getResponse().getContentAsString())
                .contains("event:aircraft-deleted")
                .contains(aircraftId);
    }

    @Test
    void publishesCreatedAircraftAfterItIsPersisted() throws Exception {
        MvcResult stream = mockMvc.perform(get("/api/v1/events")
                        .param("exerciseGroupId", "GROUP-DEFAULT")
                        .header("Accept", "text/event-stream"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(post("/api/v1/exercise-groups/GROUP-DEFAULT/aircraft")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validAircraftBody("1234")))
                .andExpect(status().isCreated());

        assertThat(stream.getResponse().getContentAsString())
                .contains("event:aircraft-upserted")
                .contains("CCA3582")
                .contains("PP-DEFAULT");
    }

    private String validAircraftBody(String transponderCode) {
        return "{"
                + "\"callsign\":\"cca3582\","
                + "\"aircraftType\":\"A320\","
                + "\"wakeCategory\":\"M\","
                + "\"transponderCode\":\"" + transponderCode + "\","
                + "\"origin\":\"ZSSS\","
                + "\"destination\":\"ZBAA\","
                + "\"appearanceOffsetMinutes\":\"0000\","
                + "\"latitude\":31.1434,"
                + "\"longitude\":121.8052,"
                + "\"headingDegrees\":360,"
                + "\"altitudeFeet\":9000,"
                + "\"speedKnots\":250,"
                + "\"route\":[\"CEN\",\"CON\",\"ZBAA\"]"
                + "}";
    }
}
