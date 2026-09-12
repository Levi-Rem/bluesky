package org.bluesky.training.aircraft;

import org.bluesky.training.TrainingPlatformApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = TrainingPlatformApplication.class,
        properties = "bluesky.trusted-gateway.secret=test-gateway-secret")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AircraftControllerV2Test {

    private static final String SECRET = "test-gateway-secret";

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;

    private String groupId;
    private String terminalId;
    private String fingerprint;

    @BeforeEach
    void seedIdentity() {
        groupId = "group-ac-api-" + UUID.randomUUID();
        terminalId = "PP-AC-API-" + System.nanoTime();
        fingerprint = "digest-ac-api-" + UUID.randomUUID();
        jdbc.update("INSERT INTO exercise_group (id, name, state) VALUES (?, '航空器接口组', 'READY')",
                groupId);
        jdbc.update("INSERT INTO workstation_terminal (id, name, terminal_type, "
                        + "exercise_group_id, frequency) VALUES (?, '机长席', 'PSEUDO_PILOT', ?, 128.100)",
                terminalId, groupId);
        jdbc.update("INSERT INTO trusted_caller_binding (id, terminal_id, exercise_group_id, "
                        + "certificate_fingerprint_digest) VALUES (?, ?, ?, ?)",
                UUID.randomUUID().toString(), terminalId, groupId, fingerprint);
    }

    @Test
    void givenIdempotencyKeyWhenCreatingThenReplayReturnsSameResourceOnce() throws Exception {
        String key = "create-aircraft-" + UUID.randomUUID();
        String payload = payload("API" + System.nanoTime() % 100000);

        String first = mockMvc.perform(withIdentity(post(
                        "/api/v2/exercise-groups/{groupId}/aircraft", groupId))
                        .header("Idempotency-Key", key)
                        .contentType(APPLICATION_JSON).content(payload))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location",
                        org.hamcrest.Matchers.matchesPattern("/api/v2/aircraft/.+")))
                .andExpect(jsonPath("$.lifecycle").value("PLANNED"))
                .andReturn().getResponse().getContentAsString();
        mockMvc.perform(withIdentity(post("/api/v2/exercise-groups/{groupId}/aircraft", groupId))
                        .header("Idempotency-Key", key)
                        .contentType(APPLICATION_JSON).content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(
                        new com.fasterxml.jackson.databind.ObjectMapper()
                                .readTree(first).get("id").asText()));

        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM exercise_aircraft "
                + "WHERE exercise_group_id = ?", Long.class, groupId);
        org.junit.jupiter.api.Assertions.assertEquals(1L, count);
    }

    @Test
    void givenMissingIdempotencyKeyOrIdentityThenRejected() throws Exception {
        mockMvc.perform(withIdentity(post("/api/v2/exercise-groups/{groupId}/aircraft", groupId))
                        .contentType(APPLICATION_JSON).content(payload("NOKEY1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));

        mockMvc.perform(get("/api/v2/exercise-groups/{groupId}/aircraft", groupId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TRUSTED_IDENTITY_REJECTED"));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder withIdentity(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) {
        return request.header("X-Gateway-Secret", SECRET)
                .header("X-Trusted-Caller-Type", "TERMINAL")
                .header("X-Trusted-Caller-Id", terminalId)
                .header("X-Trusted-Terminal-Id", terminalId)
                .header("X-Trusted-Fingerprint-Digest", fingerprint);
    }

    private String payload(String callsign) {
        return "{\"aircraft\":{\"callsign\":\"" + callsign
                + "\",\"aircraftType\":\"A320\",\"wakeCategory\":\"M\"},"
                + "\"flightPlan\":{\"origin\":\"ZGGG\",\"destination\":\"ZBAA\","
                + "\"plannedSquawk\":\"0123\",\"ssrMode\":\"C\","
                + "\"route\":[\"ZGGG\",\"ZBAA\"]},"
                + "\"initialState\":{\"latitudeDeg\":23.4,\"longitudeDeg\":113.3,"
                + "\"trueHeadingDeg\":20,\"altitudeFtMsl\":50,"
                + "\"indicatedAirspeedKt\":0},"
                + "\"targetAppearanceSimulationTimeSeconds\":600,"
                + "\"assignedTerminalId\":\"" + terminalId + "\"}";
    }
}
