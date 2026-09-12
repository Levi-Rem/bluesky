package org.bluesky.training.event;

import org.bluesky.training.TrainingPlatformApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Collections;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** P06：v2 SSE 接口的游标过期与终端隔离。 */
@SpringBootTest(classes = TrainingPlatformApplication.class,
        properties = "bluesky.trusted-gateway.secret=test-gateway-secret")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EventStreamControllerV2Test {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private BusinessEventService eventService;

    private String newGroup() {
        String groupId = "group-ssectl-" + UUID.randomUUID();
        jdbc.update("INSERT INTO exercise_group (id, name, state) VALUES (?, ?, 'RUNNING')",
                groupId, "SSE 接口组");
        return groupId;
    }

    private void bind(String groupId, String terminalId, String digest) {
        jdbc.update("INSERT INTO trusted_caller_binding (id, terminal_id, exercise_group_id, "
                        + "certificate_fingerprint_digest) VALUES (?, ?, ?, ?)",
                UUID.randomUUID().toString(), terminalId, groupId, digest);
    }

    @Test
    void givenMalformedCursorWhenStreamingThen409() throws Exception {
        String groupId = newGroup();
        bind(groupId, "PP-X", "digest-x");

        mockMvc.perform(get("/api/v2/events")
                        .param("exerciseGroupId", groupId)
                        .param("terminalId", "PP-X")
                        .header("X-Gateway-Secret", "test-gateway-secret")
                        .header("X-Trusted-Caller-Type", "TERMINAL")
                        .header("X-Trusted-Caller-Id", "PP-X")
                        .header("X-Trusted-Terminal-Id", "PP-X")
                        .header("X-Trusted-Fingerprint-Digest", "digest-x")
                        .header("Last-Event-ID", "not-a-valid-cursor"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVENT_CURSOR_EXPIRED"));
    }

    @Test
    void givenForeignTerminalIdentityWhenStreamingThen403() throws Exception {
        String groupId = newGroup();
        jdbc.update("INSERT INTO trusted_caller_binding (id, terminal_id, exercise_group_id, "
                        + "certificate_fingerprint_digest) VALUES (?, 'PP-ME', ?, 'digest-me')",
                UUID.randomUUID().toString(), groupId);

        mockMvc.perform(get("/api/v2/events")
                        .param("exerciseGroupId", groupId)
                        .param("terminalId", "PP-OTHER")
                        .header("X-Gateway-Secret", "test-gateway-secret")
                        .header("X-Trusted-Caller-Type", "TERMINAL")
                        .header("X-Trusted-Caller-Id", "PP-ME")
                        .header("X-Trusted-Terminal-Id", "PP-ME")
                        .header("X-Trusted-Fingerprint-Digest", "digest-me"))
                .andExpect(status().isForbidden());
    }

    @Test
    void givenFreshTerminalWhenStreamingWithoutCursorThen200() throws Exception {
        String groupId = newGroup();
        String terminalId = "PP-NEW-" + System.nanoTime();
        transactionTemplate.execute(status -> eventService.append(groupId,
                "instruction.updated", "{\"x\":1}", Collections.singletonList(terminalId)));
        String digest = "digest-new-" + System.nanoTime();
        bind(groupId, terminalId, digest);

        mockMvc.perform(get("/api/v2/events")
                        .param("exerciseGroupId", groupId)
                        .param("terminalId", terminalId)
                        .header("X-Gateway-Secret", "test-gateway-secret")
                        .header("X-Trusted-Caller-Type", "TERMINAL")
                        .header("X-Trusted-Caller-Id", terminalId)
                        .header("X-Trusted-Terminal-Id", terminalId)
                        .header("X-Trusted-Fingerprint-Digest", digest))
                .andExpect(status().isOk());
    }

    @Test
    void givenAnonymousCallerWhenStreamingThen403() throws Exception {
        String groupId = newGroup();

        mockMvc.perform(get("/api/v2/events")
                        .param("exerciseGroupId", groupId)
                        .param("terminalId", "PP-ANON"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TRUSTED_IDENTITY_REJECTED"));
    }
}
