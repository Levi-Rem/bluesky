package org.bluesky.training.common;

import org.bluesky.training.TrainingPlatformApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** P02：v2 终端管理接口端到端（过滤器→解析器→控制器→服务）。 */
@SpringBootTest(classes = TrainingPlatformApplication.class,
        properties = "bluesky.trusted-gateway.secret=test-gateway-secret")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TerminalAdminControllerV2Test {

    private static final String GATEWAY_SECRET = "test-gateway-secret";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private String newGroup() {
        String groupId = "group-admin-" + UUID.randomUUID();
        jdbc.update("INSERT INTO exercise_group (id, name, state) VALUES (?, ?, 'READY')",
                groupId, "管理接口组");
        return groupId;
    }

    @Test
    void givenOpsIdentityWhenCreatingTerminalThenCreatedAndListed() throws Exception {
        String groupId = newGroup();

        String terminalId = com.jayway.jsonpath.JsonPath.read(
                mockMvc.perform(post("/api/v2/exercise-groups/{groupId}/terminals", groupId)
                                .header("X-Gateway-Secret", GATEWAY_SECRET)
                                .header("X-Trusted-Caller-Type", "OPERATIONS")
                                .header("X-Trusted-Caller-Id", "ops-1")
                                .header("X-Trusted-Fingerprint-Digest", "ops-digest")
                                .header("Idempotency-Key", "create-terminal-" + UUID.randomUUID())
                                .contentType(APPLICATION_JSON)
                                .content("{\"name\":\"机长席-1\",\"frequencyMhz\":118.350,\"unitMode\":\"IMP\"}"))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.id").isNotEmpty())
                        .andExpect(jsonPath("$.exerciseGroupId").value(groupId))
                        .andExpect(jsonPath("$.revision").value(1))
                        .andReturn().getResponse().getContentAsString(), "$.id");

        mockMvc.perform(get("/api/v2/exercise-groups/{groupId}/terminals", groupId)
                        .header("X-Gateway-Secret", GATEWAY_SECRET)
                        .header("X-Trusted-Caller-Type", "OPERATIONS")
                        .header("X-Trusted-Caller-Id", "ops-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].name").value("机长席-1"));

        mockMvc.perform(patch("/api/v2/terminals/{terminalId}", terminalId)
                        .header("X-Gateway-Secret", GATEWAY_SECRET)
                        .header("X-Trusted-Caller-Type", "OPERATIONS")
                        .header("X-Trusted-Caller-Id", "ops-1")
                        .header("Idempotency-Key", "patch-terminal-" + UUID.randomUUID())
                        .header("If-Match", "\"1\"")
                        .contentType(APPLICATION_JSON)
                        .content("{\"name\":\"机长席-改名\",\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.name").value("机长席-改名"));

        mockMvc.perform(put("/api/v2/terminals/{terminalId}/trusted-binding", terminalId)
                        .header("X-Gateway-Secret", GATEWAY_SECRET)
                        .header("X-Trusted-Caller-Type", "OPERATIONS")
                        .header("X-Trusted-Caller-Id", "ops-1")
                        .header("Idempotency-Key", "bind-terminal-" + UUID.randomUUID())
                        .header("If-Match", "\"0\"")
                        .contentType(APPLICATION_JSON)
                        .content("{\"certificateFingerprintDigest\":\"digest-pp01\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.terminalId").value(terminalId))
                .andExpect(jsonPath("$.certificateFingerprintDigest").value("digest-pp01"));
    }

    @Test
    void givenTerminalIdentityWhenCreatingTerminalThenForbidden() throws Exception {
        String groupId = newGroup();
        // 先用运维身份建一个终端并绑定指纹，再用该终端身份尝试越权
        String terminalId = com.jayway.jsonpath.JsonPath.read(
                mockMvc.perform(post("/api/v2/exercise-groups/{groupId}/terminals", groupId)
                                .header("X-Gateway-Secret", GATEWAY_SECRET)
                                .header("X-Trusted-Caller-Type", "OPERATIONS")
                                .header("X-Trusted-Caller-Id", "ops-1")
                                .header("Idempotency-Key", "create-legal-" + UUID.randomUUID())
                                .contentType(APPLICATION_JSON)
                                .content("{\"name\":\"合法终端\",\"frequencyMhz\":119.100}"))
                        .andReturn().getResponse().getContentAsString(), "$.id");
        mockMvc.perform(put("/api/v2/terminals/{terminalId}/trusted-binding", terminalId)
                        .header("X-Gateway-Secret", GATEWAY_SECRET)
                        .header("X-Trusted-Caller-Type", "OPERATIONS")
                        .header("X-Trusted-Caller-Id", "ops-1")
                        .header("Idempotency-Key", "bind-legal-" + UUID.randomUUID())
                        .header("If-Match", "\"0\"")
                        .contentType(APPLICATION_JSON)
                        .content("{\"certificateFingerprintDigest\":\"digest-term-x\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v2/exercise-groups/{groupId}/terminals", groupId)
                        .header("X-Gateway-Secret", GATEWAY_SECRET)
                        .header("X-Trusted-Caller-Type", "TERMINAL")
                        .header("X-Trusted-Caller-Id", terminalId)
                        .header("X-Trusted-Terminal-Id", terminalId)
                        .header("X-Trusted-Fingerprint-Digest", "digest-term-x")
                        .header("Idempotency-Key", "forbidden-create-" + UUID.randomUUID())
                        .contentType(APPLICATION_JSON)
                        .content("{\"name\":\"越权终端\",\"frequencyMhz\":119.200}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TRUSTED_IDENTITY_REJECTED"));
    }

    @Test
    void givenSpoofedHeadersWithoutGatewaySecretWhenPostedThenForbidden() throws Exception {
        String groupId = newGroup();

        mockMvc.perform(post("/api/v2/exercise-groups/{groupId}/terminals", groupId)
                        .header("X-Trusted-Caller-Type", "OPERATIONS")
                        .header("X-Trusted-Caller-Id", "ops-1")
                        .header("Idempotency-Key", "spoof-create-" + UUID.randomUUID())
                        .contentType(APPLICATION_JSON)
                        .content("{\"name\":\"伪造终端\",\"frequencyMhz\":120.000}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TRUSTED_IDENTITY_REJECTED"));
    }

    @Test
    void givenDuplicateFrequencyWhenPostedThenConflict() throws Exception {
        String groupId = newGroup();

        mockMvc.perform(post("/api/v2/exercise-groups/{groupId}/terminals", groupId)
                        .header("X-Gateway-Secret", GATEWAY_SECRET)
                        .header("X-Trusted-Caller-Type", "OPERATIONS")
                        .header("X-Trusted-Caller-Id", "ops-1")
                        .header("Idempotency-Key", "frequency-first-" + UUID.randomUUID())
                        .contentType(APPLICATION_JSON)
                        .content("{\"name\":\"第一终端\",\"frequencyMhz\":120.500}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v2/exercise-groups/{groupId}/terminals", groupId)
                        .header("X-Gateway-Secret", GATEWAY_SECRET)
                        .header("X-Trusted-Caller-Type", "OPERATIONS")
                        .header("X-Trusted-Caller-Id", "ops-1")
                        .header("Idempotency-Key", "frequency-duplicate-" + UUID.randomUUID())
                        .contentType(APPLICATION_JSON)
                        .content("{\"name\":\"重复频率终端\",\"frequencyMhz\":120.500}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TERMINAL_FREQUENCY_IN_USE"));
    }
}
