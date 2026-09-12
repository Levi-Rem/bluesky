package org.bluesky.training.instruction;

import org.bluesky.training.TrainingPlatformApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P0-13/E13：v2 指令 MockMvc。幂等键只认 Idempotency-Key 请求头
 * （openapi-v2.yaml：in:header；详细设计 9.1），请求体同名字段不作为幂等依据。
 */
@SpringBootTest(classes = TrainingPlatformApplication.class,
        properties = "bluesky.trusted-gateway.secret=test-gateway-secret")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InstructionApiV2Test {

    private static final String SECRET = "test-gateway-secret";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private String terminalId;
    private String fingerprint = "digest-ins-" + UUID.randomUUID();
    private String aircraftId;

    private void newGroupWithActiveAircraft() {
        String groupId = "group-ins-" + UUID.randomUUID();
        jdbc.update("INSERT INTO exercise_group (id, name, state, simulation_time_seconds) "
                + "VALUES (?, ?, 'RUNNING', 600)", groupId, "指令接口组");
        terminalId = "INS-T-" + System.nanoTime();
        jdbc.update("INSERT INTO workstation_terminal (id, name, terminal_type, "
                        + "exercise_group_id, frequency) VALUES (?, '机长席', 'PSEUDO_PILOT', ?, 118.100)",
                terminalId, groupId);
        jdbc.update("INSERT INTO trusted_caller_binding (id, terminal_id, exercise_group_id, "
                        + "certificate_fingerprint_digest) VALUES (?, ?, ?, ?)",
                UUID.randomUUID().toString(), terminalId, groupId, fingerprint);

        aircraftId = "ac-ins-" + UUID.randomUUID();
        String callsign = "IA" + System.nanoTime() % 100000;
        jdbc.update("INSERT INTO exercise_aircraft (id, exercise_group_id, assigned_terminal_id, "
                        + "callsign, aircraft_type, wake_category, origin, destination, "
                        + "heading_degrees, altitude_feet, speed_knots, lifecycle, "
                        + "active_callsign_key) VALUES (?, ?, ?, ?, 'A320', 'M', 'ZGGG', 'ZBAA', "
                        + "20, 8000, 250, 'ACTIVE', ?)",
                aircraftId, groupId, terminalId, callsign, callsign);
        jdbc.update("INSERT INTO flight_plan (id, aircraft_id, plan_version, origin, destination, "
                        + "route_text) VALUES (?, ?, 1, 'ZGGG', 'ZBAA', 'ZGGG ZBAA')",
                UUID.randomUUID().toString(), aircraftId);
        jdbc.update("INSERT INTO aircraft_assignment (id, aircraft_id, terminal_id, current_key) "
                + "VALUES (?, ?, ?, ?)", UUID.randomUUID().toString(), aircraftId,
                terminalId, aircraftId);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder submit(
            String key, String body) {
        org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder =
                post("/api/v2/aircraft/{aircraftId}/instructions", aircraftId)
                        .header("X-Gateway-Secret", SECRET)
                        .header("X-Trusted-Caller-Type", "TERMINAL")
                        .header("X-Trusted-Caller-Id", terminalId)
                        .header("X-Trusted-Terminal-Id", terminalId)
                        .header("X-Trusted-Fingerprint-Digest", fingerprint)
                        .contentType(APPLICATION_JSON)
                        .content(body);
        if (key != null) {
            builder.header("Idempotency-Key", key);
        }
        return builder;
    }

    @Test
    void givenHeaderIdempotencyKeyWhenReplayedThenOriginalInstructionReturned() throws Exception {
        newGroupWithActiveAircraft();
        String body = "{\"aircraftRevision\":1,\"scheduling\":\"REPLACE\","
                + "\"command\":{\"type\":\"HDG\",\"parameters\":{\"magneticHeadingDeg\":90}}}";
        String key = "ins-" + UUID.randomUUID();

        mockMvc.perform(submit(key, body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.status").value("DISPATCHING"));

        String replayed = mockMvc.perform(submit(key, body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.replayed").value(true))
                .andReturn().getResponse().getContentAsString();

        assertEquals(1L, (long) jdbc.queryForObject(
                "SELECT COUNT(*) FROM aircraft_instruction WHERE exercise_aircraft_id = ? "
                        + "AND idempotency_key = ?", Long.class, aircraftId, key),
                "同键重放不得创建重复指令（详细设计 9.1）");
    }

    @Test
    void givenBodyIdempotencyKeyOnlyWhenSubmittedTwiceThenNoDedupAndSlotGuards() throws Exception {
        newGroupWithActiveAircraft();
        // 契约只认请求头；body 携带同名字段不产生幂等效果（评审 P0-13）。
        // 同键若被误当作幂等依据，第二次会返回 202 重放；实际应命中
        // 同冲突键在途占位 409（详细设计 6.3.5，评审 P0-6/E6）
        String body = "{\"aircraftRevision\":1,\"scheduling\":\"REPLACE\","
                + "\"idempotencyKey\":\"body-key\",\"command\":{\"type\":\"HDG\","
                + "\"parameters\":{\"magneticHeadingDeg\":120}}}";

        mockMvc.perform(submit(null, body)).andExpect(status().isAccepted());
        mockMvc.perform(submit(null, body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHANNEL_DISPATCH_IN_PROGRESS"));

        assertEquals(1L, (long) jdbc.queryForObject(
                "SELECT COUNT(*) FROM aircraft_instruction WHERE exercise_aircraft_id = ?",
                Long.class, aircraftId), "body 幂等键不作为幂等依据");
    }

    @Test
    void givenMissingAircraftRevisionWhenSubmittedThen428() throws Exception {
        newGroupWithActiveAircraft();
        mockMvc.perform(submit("ins-" + UUID.randomUUID(),
                        "{\"scheduling\":\"REPLACE\",\"command\":{\"type\":\"HDG\"}}}"))
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.code").value("REVISION_REQUIRED"));
    }
}
