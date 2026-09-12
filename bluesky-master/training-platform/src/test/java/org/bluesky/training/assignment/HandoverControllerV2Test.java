package org.bluesky.training.assignment;

import org.bluesky.training.TrainingPlatformApplication;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** P08：v2 移交接口（POST handover 200 信封 / GET assignments / 428 / 403）。 */
@SpringBootTest(classes = TrainingPlatformApplication.class,
        properties = "bluesky.trusted-gateway.secret=test-gateway-secret")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HandoverControllerV2Test {

    private static final String SECRET = "test-gateway-secret";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private String sourceTerminalId;
    private String targetTerminalId;
    private String fingerprint = "digest-ho-" + UUID.randomUUID();
    private String aircraftId;

    private String newGroupWithActiveAircraft() {
        String groupId = "group-hoc-" + UUID.randomUUID();
        jdbc.update("INSERT INTO exercise_group (id, name, state, simulation_time_seconds) "
                + "VALUES (?, ?, 'RUNNING', 600)", groupId, "移交接口组");
        sourceTerminalId = "HOC-S-" + System.nanoTime();
        targetTerminalId = "HOC-T-" + System.nanoTime();
        jdbc.update("INSERT INTO workstation_terminal (id, name, terminal_type, "
                        + "exercise_group_id, frequency) VALUES (?, '源席', 'PSEUDO_PILOT', ?, 118.100)",
                sourceTerminalId, groupId);
        jdbc.update("INSERT INTO workstation_terminal (id, name, terminal_type, "
                        + "exercise_group_id, frequency) VALUES (?, '目标席', 'PSEUDO_PILOT', ?, 121.500)",
                targetTerminalId, groupId);
        jdbc.update("INSERT INTO trusted_caller_binding (id, terminal_id, exercise_group_id, "
                        + "certificate_fingerprint_digest) VALUES (?, ?, ?, ?)",
                UUID.randomUUID().toString(), sourceTerminalId, groupId, fingerprint);

        aircraftId = "ac-hoc-" + UUID.randomUUID();
        String callsign = "HC" + System.nanoTime() % 100000;
        jdbc.update("INSERT INTO exercise_aircraft (id, exercise_group_id, assigned_terminal_id, "
                        + "callsign, aircraft_type, wake_category, origin, destination, "
                        + "heading_degrees, altitude_feet, speed_knots, lifecycle, "
                        + "active_callsign_key) VALUES (?, ?, ?, ?, 'A320', 'M', 'ZGGG', 'ZBAA', "
                        + "20, 8000, 250, 'ACTIVE', ?)",
                aircraftId, groupId, sourceTerminalId, callsign, callsign);
        jdbc.update("INSERT INTO flight_plan (id, aircraft_id, plan_version, origin, destination, "
                        + "route_text) VALUES (?, ?, 1, 'ZGGG', 'ZBAA', 'ZGGG ZBAA')",
                UUID.randomUUID().toString(), aircraftId);
        jdbc.update("INSERT INTO aircraft_assignment (id, aircraft_id, terminal_id, current_key) "
                + "VALUES (?, ?, ?, ?)", UUID.randomUUID().toString(), aircraftId,
                sourceTerminalId, aircraftId);
        return groupId;
    }

    private long revisionOf() {
        return jdbc.queryForObject("SELECT revision FROM exercise_aircraft WHERE id = ?",
                Long.class, aircraftId);
    }

    @Test
    void givenSourceTerminalWhenHandingOverThenEnvelopeReturned() throws Exception {
        String groupId = newGroupWithActiveAircraft();
        long revision = revisionOf();
        String key = "handover-" + aircraftId;

        mockMvc.perform(post("/api/v2/aircraft/{aircraftId}/handover", aircraftId)
                        .header("X-Gateway-Secret", SECRET)
                        .header("X-Trusted-Caller-Type", "TERMINAL")
                        .header("X-Trusted-Caller-Id", sourceTerminalId)
                        .header("X-Trusted-Terminal-Id", sourceTerminalId)
                        .header("X-Trusted-Fingerprint-Digest", fingerprint)
                        .header("Idempotency-Key", key)
                        .contentType(APPLICATION_JSON)
                        .content("{\"aircraftRevision\":" + revision
                                + ",\"targetFrequencyMhz\":121.500}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aircraftId").value(aircraftId))
                .andExpect(jsonPath("$.targetTerminalId").value(targetTerminalId))
                .andExpect(jsonPath("$.activeInstructions").isArray())
                .andExpect(jsonPath("$.waitingInstructions").isArray());

        mockMvc.perform(post("/api/v2/aircraft/{aircraftId}/handover", aircraftId)
                        .header("X-Gateway-Secret", SECRET)
                        .header("X-Trusted-Caller-Type", "TERMINAL")
                        .header("X-Trusted-Caller-Id", sourceTerminalId)
                        .header("X-Trusted-Terminal-Id", sourceTerminalId)
                        .header("X-Trusted-Fingerprint-Digest", fingerprint)
                        .header("Idempotency-Key", key)
                        .contentType(APPLICATION_JSON)
                        .content("{\"aircraftRevision\":" + revision
                                + ",\"targetFrequencyMhz\":121.500}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetTerminalId").value(targetTerminalId));
        org.junit.jupiter.api.Assertions.assertEquals(1L, (long) jdbc.queryForObject(
                "SELECT COUNT(*) FROM aircraft_handover WHERE aircraft_id = ?",
                Long.class, aircraftId));

        mockMvc.perform(get("/api/v2/exercise-groups/{groupId}/assignments", groupId)
                        .header("X-Gateway-Secret", SECRET)
                        .header("X-Trusted-Caller-Type", "TERMINAL")
                        .header("X-Trusted-Caller-Id", sourceTerminalId)
                        .header("X-Trusted-Terminal-Id", sourceTerminalId)
                        .header("X-Trusted-Fingerprint-Digest", fingerprint))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].terminalId").value(targetTerminalId));
    }

    @Test
    void givenMissingRevisionWhenHandingOverThen428() throws Exception {
        newGroupWithActiveAircraft();

        mockMvc.perform(post("/api/v2/aircraft/{aircraftId}/handover", aircraftId)
                        .header("X-Gateway-Secret", SECRET)
                        .header("X-Trusted-Caller-Type", "TERMINAL")
                        .header("X-Trusted-Caller-Id", sourceTerminalId)
                        .header("X-Trusted-Terminal-Id", sourceTerminalId)
                        .header("X-Trusted-Fingerprint-Digest", fingerprint)
                        .header("Idempotency-Key", "missing-revision-" + aircraftId)
                        .contentType(APPLICATION_JSON)
                        .content("{\"targetFrequencyMhz\":121.500}"))
                .andExpect(status().is(428))
                .andExpect(jsonPath("$.code").value("REVISION_REQUIRED"));
    }

    @Test
    void givenTargetTerminalIdentityWhenHandingOverThen403() throws Exception {
        newGroupWithActiveAircraft();
        String targetFingerprint = "digest-hot-" + UUID.randomUUID();
        jdbc.update("INSERT INTO trusted_caller_binding (id, terminal_id, exercise_group_id, "
                        + "certificate_fingerprint_digest) VALUES (?, ?, ?, ?)",
                UUID.randomUUID().toString(), targetTerminalId,
                jdbc.queryForObject("SELECT exercise_group_id FROM exercise_aircraft WHERE id = ?",
                        String.class, aircraftId), targetFingerprint);

        mockMvc.perform(post("/api/v2/aircraft/{aircraftId}/handover", aircraftId)
                        .header("X-Gateway-Secret", SECRET)
                        .header("X-Trusted-Caller-Type", "TERMINAL")
                        .header("X-Trusted-Caller-Id", targetTerminalId)
                        .header("X-Trusted-Terminal-Id", targetTerminalId)
                        .header("X-Trusted-Fingerprint-Digest", targetFingerprint)
                        .header("Idempotency-Key", "foreign-" + aircraftId)
                        .contentType(APPLICATION_JSON)
                        .content("{\"aircraftRevision\":" + revisionOf()
                                + ",\"targetFrequencyMhz\":121.500}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AIRCRAFT_NOT_ASSIGNED"));
    }
}
