package org.bluesky.training.exercise;

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

/** P05：v2 训练组动作与管理接口（202 操作信封，详细设计 9.6）。 */
@SpringBootTest(classes = TrainingPlatformApplication.class,
        properties = "bluesky.trusted-gateway.secret=test-gateway-secret")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ExerciseGroupActionControllerV2Test {

    private static final String SECRET = "test-gateway-secret";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private String terminalId = "PP-ACT-" + UUID.randomUUID();
    private String fingerprint = "digest-act-" + UUID.randomUUID();

    private String newGroup(String state) {
        String groupId = "group-act-" + UUID.randomUUID();
        jdbc.update("INSERT INTO exercise_group (id, name, state) VALUES (?, ?, ?)",
                groupId, "动作接口组", state);
        // 为终端身份绑定证书指纹，使其属于该组（过滤器按绑定注入组上下文）
        jdbc.update("INSERT INTO trusted_caller_binding (id, terminal_id, exercise_group_id, "
                        + "certificate_fingerprint_digest) VALUES (?, ?, ?, ?)",
                UUID.randomUUID().toString(), terminalId, groupId, fingerprint);
        if ("READY".equals(state)) {
            org.bluesky.training.testsupport.V2FixtureFactory.seedPublishedSnapshot(jdbc, groupId);
            jdbc.update("INSERT INTO workstation_terminal "
                            + "(id, name, terminal_type, exercise_group_id) VALUES (?, ?, ?, ?)",
                    terminalId, "机长席", "PSEUDO_PILOT", groupId);
        }
        return groupId;
    }

    private long revisionOf(String groupId) {
        return jdbc.queryForObject("SELECT revision FROM exercise_group WHERE id = ?",
                Long.class, groupId);
    }

    @Test
    void givenTerminalOfGroupWhenStartingThenAccepted202WithOperationEnvelope() throws Exception {
        String groupId = newGroup("READY");

        mockMvc.perform(post("/api/v2/exercise-groups/{groupId}/actions/start", groupId)
                        .header("X-Gateway-Secret", SECRET)
                        .header("X-Trusted-Caller-Type", "TERMINAL")
                        .header("X-Trusted-Caller-Id", terminalId)
                        .header("X-Trusted-Terminal-Id", terminalId)
                        .header("X-Trusted-Fingerprint-Digest", fingerprint)
                        .header("Idempotency-Key", "start-" + UUID.randomUUID())
                        .contentType(APPLICATION_JSON)
                        .content("{\"groupRevision\":" + revisionOf(groupId) + "}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.state").value("STARTING"))
                .andExpect(jsonPath("$.operationId").isNotEmpty())
                .andExpect(jsonPath("$.resourceId").value(groupId));

        mockMvc.perform(get("/api/v2/exercise-groups")
                        .header("X-Gateway-Secret", SECRET)
                        .header("X-Trusted-Caller-Type", "OPERATIONS")
                        .header("X-Trusted-Caller-Id", "ops-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].state").exists());
    }

    @Test
    void givenMissingGroupRevisionWhenStartingThen428() throws Exception {
        String groupId = newGroup("READY");

        mockMvc.perform(post("/api/v2/exercise-groups/{groupId}/actions/start", groupId)
                        .header("X-Gateway-Secret", SECRET)
                        .header("X-Trusted-Caller-Type", "TERMINAL")
                        .header("X-Trusted-Caller-Id", terminalId)
                        .header("X-Trusted-Terminal-Id", terminalId)
                        .header("X-Trusted-Fingerprint-Digest", fingerprint)
                        .header("Idempotency-Key", "missing-revision-" + UUID.randomUUID())
                        .contentType(APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().is(428))
                .andExpect(jsonPath("$.code").value("REVISION_REQUIRED"));
    }

    @Test
    void givenOrchestratorWhenEndingThenAccepted() throws Exception {
        String groupId = newGroup("RUNNING");

        mockMvc.perform(post("/api/v2/exercise-groups/{groupId}/actions/end", groupId)
                        .header("X-Gateway-Secret", SECRET)
                        .header("X-Trusted-Caller-Type", "EXERCISE_ORCHESTRATOR")
                        .header("X-Trusted-Caller-Id", "orch-1")
                        .header("Idempotency-Key", "end-" + UUID.randomUUID())
                        .contentType(APPLICATION_JSON)
                        .content("{\"groupRevision\":" + revisionOf(groupId)
                                + ",\"reason\":\"训练完成\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.state").value("ENDING"));
    }

    @Test
    void givenTerminalWhenEndingThenForbidden() throws Exception {
        String groupId = newGroup("RUNNING");

        mockMvc.perform(post("/api/v2/exercise-groups/{groupId}/actions/end", groupId)
                        .header("X-Gateway-Secret", SECRET)
                        .header("X-Trusted-Caller-Type", "TERMINAL")
                        .header("X-Trusted-Caller-Id", terminalId)
                        .header("X-Trusted-Terminal-Id", terminalId)
                        .header("X-Trusted-Fingerprint-Digest", fingerprint)
                        .header("Idempotency-Key", "forbidden-end-" + UUID.randomUUID())
                        .contentType(APPLICATION_JSON)
                        .content("{\"groupRevision\":" + revisionOf(groupId) + "}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TRUSTED_IDENTITY_REJECTED"));
    }

    @Test
    void givenOpsWhenCreatingGroupViaApiThenCreated() throws Exception {
        mockMvc.perform(post("/api/v2/exercise-groups")
                        .header("X-Gateway-Secret", SECRET)
                        .header("X-Trusted-Caller-Type", "OPERATIONS")
                        .header("X-Trusted-Caller-Id", "ops-1")
                        .header("Idempotency-Key", "create-group-" + UUID.randomUUID())
                        .contentType(APPLICATION_JSON)
                        .content("{\"name\":\"接口建组\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("READY"));
    }

    @Test
    void givenMissingIdempotencyKeyWhenStartingThenBadRequestWithoutMutation() throws Exception {
        String groupId = newGroup("READY");

        mockMvc.perform(post("/api/v2/exercise-groups/{groupId}/actions/start", groupId)
                        .header("X-Gateway-Secret", SECRET)
                        .header("X-Trusted-Caller-Type", "TERMINAL")
                        .header("X-Trusted-Caller-Id", terminalId)
                        .header("X-Trusted-Terminal-Id", terminalId)
                        .header("X-Trusted-Fingerprint-Digest", fingerprint)
                        .contentType(APPLICATION_JSON)
                        .content("{\"groupRevision\":" + revisionOf(groupId) + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));

        org.junit.jupiter.api.Assertions.assertEquals("READY", jdbc.queryForObject(
                "SELECT state FROM exercise_group WHERE id = ?", String.class, groupId));
    }

    @Test
    void givenAnonymousWriteWhenStartingThenForbiddenBeforeIdempotencyInsert() throws Exception {
        String groupId = newGroup("READY");
        int recordsBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record", Integer.class);

        mockMvc.perform(post("/api/v2/exercise-groups/{groupId}/actions/start", groupId)
                        .header("Idempotency-Key", "anonymous-" + UUID.randomUUID())
                        .contentType(APPLICATION_JSON)
                        .content("{\"groupRevision\":" + revisionOf(groupId) + "}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TRUSTED_IDENTITY_REJECTED"));

        org.junit.jupiter.api.Assertions.assertEquals(recordsBefore, jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record", Integer.class));
        org.junit.jupiter.api.Assertions.assertEquals("READY", jdbc.queryForObject(
                "SELECT state FROM exercise_group WHERE id = ?", String.class, groupId));
    }
}
