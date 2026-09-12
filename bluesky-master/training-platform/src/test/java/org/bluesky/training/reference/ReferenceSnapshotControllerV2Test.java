package org.bluesky.training.reference;

import org.bluesky.training.TrainingPlatformApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** P03：v2 参考快照接口（编排方固定、列表与组内资源读取）。 */
@SpringBootTest(classes = TrainingPlatformApplication.class,
        properties = {"bluesky.trusted-gateway.secret=test-gateway-secret",
                "bluesky.reference-snapshot.store-dir=target/test-snapshot-store"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReferenceSnapshotControllerV2Test {

    private static final String SECRET = "test-gateway-secret";
    private static final String ORCH = "EXERCISE_ORCHESTRATOR";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ReferenceSnapshotService snapshotService;

    @Autowired
    private JdbcTemplate jdbc;

    private String newReadyGroup() {
        String groupId = "group-ctl-" + UUID.randomUUID();
        jdbc.update("INSERT INTO exercise_group (id, name, state) VALUES (?, ?, 'READY')",
                groupId, "接口验证组");
        return groupId;
    }

    private String publishSnapshot() throws Exception {
        Path sourceDir = java.nio.file.Paths.get("target", "test-snapshot-src-" + UUID.randomUUID());
        Files.createDirectories(sourceDir);
        String airports = "[{\"id\":\"ZGGG\",\"code\":\"ZGGG\",\"name\":\"广州\"}]";
        Files.write(sourceDir.resolve("airports.json"), airports.getBytes("UTF-8"));
        String checksum = ReferenceSnapshotStore.sha256Of(sourceDir.resolve("airports.json"));
        StringBuilder manifest = new StringBuilder("{");
        manifest.append("\"schemaVersion\":\"reference-manifest/1\",");
        manifest.append("\"sourceBatch\":\"batch-ctl\",");
        manifest.append("\"resources\":{\"airports\":{\"file\":\"airports.json\",\"sha256\":\"")
                .append(checksum).append("\"}}}");
        Files.write(sourceDir.resolve("manifest.json"), manifest.toString().getBytes("UTF-8"));
        return String.valueOf(snapshotService.publish(sourceDir, "v-ctl-" + System.nanoTime()).get("id"));
    }

    @Test
    void givenOrchestratorWhenPinningSnapshotThenGroupIsPinned() throws Exception {
        String groupId = newReadyGroup();
        String snapshotId = publishSnapshot();

        mockMvc.perform(put("/api/v2/exercise-groups/{groupId}/reference-snapshot", groupId)
                        .header("X-Gateway-Secret", SECRET)
                        .header("X-Trusted-Caller-Type", ORCH)
                        .header("X-Trusted-Caller-Id", "orch-1")
                        .header("Idempotency-Key", "pin-" + UUID.randomUUID())
                        .header("If-Match", "\"1\"")
                        .contentType(APPLICATION_JSON)
                        .content("{\"referenceSnapshotId\":\"" + snapshotId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groupId").value(groupId))
                .andExpect(jsonPath("$.referenceSnapshotId").value(snapshotId));

        mockMvc.perform(get("/api/v2/exercise-groups/{groupId}/reference-snapshot/airports", groupId)
                        .header("X-Gateway-Secret", SECRET)
                        .header("X-Trusted-Caller-Type", ORCH)
                        .header("X-Trusted-Caller-Id", "orch-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("ZGGG"));
    }

    @Test
    void givenTerminalIdentityWhenPinningThenForbidden() throws Exception {
        String groupId = newReadyGroup();

        mockMvc.perform(put("/api/v2/exercise-groups/{groupId}/reference-snapshot", groupId)
                        .header("X-Gateway-Secret", SECRET)
                        .header("X-Trusted-Caller-Type", "TERMINAL")
                        .header("X-Trusted-Caller-Id", "PP-01")
                        .header("X-Trusted-Terminal-Id", "PP-01")
                        .header("Idempotency-Key", "forbidden-pin-" + UUID.randomUUID())
                        .header("If-Match", "\"1\"")
                        .contentType(APPLICATION_JSON)
                        .content("{\"referenceSnapshotId\":\"any\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TRUSTED_IDENTITY_REJECTED"));
    }

    @Test
    void givenOrchestratorWhenListingPublishedThenChecksumPresent() throws Exception {
        publishSnapshot();

        mockMvc.perform(get("/api/v2/reference-snapshots")
                        .header("X-Gateway-Secret", SECRET)
                        .header("X-Trusted-Caller-Type", ORCH)
                        .header("X-Trusted-Caller-Id", "orch-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].manifestChecksum").isNotEmpty());
    }

    @Test
    void givenUnknownResourceTypeWhenReadThen404() throws Exception {
        String groupId = newReadyGroup();

        mockMvc.perform(get("/api/v2/exercise-groups/{groupId}/reference-snapshot/radar-magic", groupId)
                        .header("X-Gateway-Secret", SECRET)
                        .header("X-Trusted-Caller-Type", ORCH)
                        .header("X-Trusted-Caller-Id", "orch-1"))
                .andExpect(status().isNotFound());
    }
}
