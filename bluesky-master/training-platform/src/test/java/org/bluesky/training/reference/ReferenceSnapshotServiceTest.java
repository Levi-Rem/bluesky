package org.bluesky.training.reference;

import org.bluesky.training.TrainingPlatformApplication;
import org.bluesky.training.common.CallerContext;
import org.bluesky.training.common.V2DomainException;
import org.bluesky.training.persistence.ReferenceSnapshotMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P03：快照发布、固定、校验、差异与资源读取（详细设计 §11）。 */
@SpringBootTest(classes = TrainingPlatformApplication.class)
@ActiveProfiles("test")
class ReferenceSnapshotServiceTest {

    private static final CallerContext ORCHESTRATOR =
            CallerContext.orchestrator("orch-1", "orch-digest");
    private static final CallerContext TERMINAL =
            CallerContext.terminal("PP-01", "group-snap", "digest");

    @Autowired
    private ReferenceSnapshotMapper snapshotMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @TempDir
    Path sourceDir;

    @TempDir
    Path storeRoot;

    private ReferenceSnapshotService service;

    @BeforeEach
    void setUp() {
        service = new ReferenceSnapshotService(snapshotMapper, storeRoot.toString());
    }

    private String newGroup(String state) {
        String groupId = "group-snap-" + UUID.randomUUID();
        jdbc.update("INSERT INTO exercise_group (id, name, state) VALUES (?, ?, ?)",
                groupId, "快照验证组", state);
        return groupId;
    }

    private String publishSnapshot(String airportJson, String navaidJson) throws Exception {
        Files.write(sourceDir.resolve("airports.json"), airportJson.getBytes("UTF-8"));
        Files.write(sourceDir.resolve("navaids.json"), navaidJson.getBytes("UTF-8"));
        String airportChecksum = ReferenceSnapshotStore.sha256Of(sourceDir.resolve("airports.json"));
        String navaidChecksum = ReferenceSnapshotStore.sha256Of(sourceDir.resolve("navaids.json"));
        Map<String, Object> resources = new java.util.LinkedHashMap<>();
        resources.put("airports", entry("airports.json", airportChecksum));
        resources.put("navaids", entry("navaids.json", navaidChecksum));
        Map<String, Object> manifest = new java.util.LinkedHashMap<>();
        manifest.put("schemaVersion", "reference-manifest/1");
        manifest.put("sourceBatch", "batch-" + UUID.randomUUID());
        manifest.put("resources", resources);
        new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValue(sourceDir.resolve("manifest.json").toFile(), manifest);

        Map<String, Object> published = service.publish(sourceDir, "v-" + System.nanoTime());
        return String.valueOf(published.get("id"));
    }

    private static Map<String, Object> entry(String file, String sha256) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("file", file);
        map.put("sha256", sha256);
        return map;
    }

    @Test
    void givenReadyGroupAndPublishedSnapshotWhenPinnedThenImmutableCopyIsUsed() throws Exception {
        String groupId = newGroup("READY");
        String snapshotId = publishSnapshot(
                "[{\"id\":\"ZGGG\",\"code\":\"ZGGG\"}]",
                "[{\"id\":\"P47\",\"code\":\"P47\"}]");

        Map<String, Object> pinned = service.pinToGroup(ORCHESTRATOR, groupId, snapshotId);
        assertEquals(snapshotId, pinned.get("referenceSnapshotId"));
        assertTrue(((Number) pinned.get("groupRevision")).longValue() >= 1);

        // 固定后删除源目录内容，不影响已固定组（平台只读副本仍然可读）
        Files.deleteIfExists(sourceDir.resolve("airports.json"));
        Files.deleteIfExists(sourceDir.resolve("manifest.json"));
        Object airports = service.readResource(groupId, "airports");
        assertNotNull(airports);

        // 源内容消失也不影响固定副本校验
        Map<String, Object> again = service.verifyPinnedSnapshot(groupId);
        assertEquals(Boolean.TRUE, again.get("valid"));
    }

    @Test
    void givenRunningGroupWhenPinnedThenRejected() throws Exception {
        String groupId = newGroup("RUNNING");
        String snapshotId = publishSnapshot("[]", "[]");

        V2DomainException failure = assertThrows(V2DomainException.class,
                () -> service.pinToGroup(ORCHESTRATOR, groupId, snapshotId));
        assertEquals(409, failure.httpStatus());
        assertEquals("TRAINING_STATE_INVALID", failure.code());
    }

    @Test
    void givenTerminalCallerWhenPinnedThenForbidden() throws Exception {
        String groupId = newGroup("READY");
        String snapshotId = publishSnapshot("[]", "[]");

        V2DomainException failure = assertThrows(V2DomainException.class,
                () -> service.pinToGroup(TERMINAL, groupId, snapshotId));
        assertEquals(403, failure.httpStatus());
        assertEquals("TRUSTED_IDENTITY_REJECTED", failure.code());
    }

    @Test
    void givenOrchestratorWhenListingPublishedSnapshotsThenOnlyPublishedWithChecksumAreReturned() throws Exception {
        String snapshotId = publishSnapshot("[]", "[]");

        List<Map<String, Object>> items = service.listPublished(ORCHESTRATOR);
        assertTrue(items.stream().anyMatch(item ->
                snapshotId.equals(item.get("id")) && item.get("manifestChecksum") != null));
    }

    @Test
    void givenExistingPlanMissingInNewSnapshotWhenSwitchingThenFullDiffIsReturned() throws Exception {
        String groupId = newGroup("READY");
        String snapshotId = publishSnapshot(
                "[{\"id\":\"ZGGG\"},{\"id\":\"ZBAA\"}]",
                "[{\"id\":\"P47\"},{\"id\":\"LMN\"}]");
        service.pinToGroup(ORCHESTRATOR, groupId, snapshotId);

        List<String> missing = service.diffPlanReferences(groupId,
                Arrays.asList("ZGGG", "P47", "GONE1", "GONE2"));
        assertEquals(Arrays.asList("GONE1", "GONE2"), missing);
    }

    @Test
    void givenPinnedCopyTamperedWhenVerifiedThenInvalid() throws Exception {
        String groupId = newGroup("READY");
        String snapshotId = publishSnapshot("[{\"id\":\"ZGGG\"}]", "[]");
        service.pinToGroup(ORCHESTRATOR, groupId, snapshotId);

        // 篡改平台只读副本
        String storePath = jdbc.queryForObject(
                "SELECT store_path FROM reference_snapshot WHERE id = ?", String.class, snapshotId);
        Path airports = java.nio.file.Paths.get(storePath, "airports.json");
        Files.write(airports, "[{\"id\":\"HACKED\"}]".getBytes("UTF-8"));

        Map<String, Object> verified = service.verifyPinnedSnapshot(groupId);
        assertEquals(Boolean.FALSE, verified.get("valid"));
    }

    @Test
    void givenUnknownResourceTypeWhenReadThen404() throws Exception {
        String groupId = newGroup("READY");
        V2DomainException failure = assertThrows(V2DomainException.class,
                () -> service.readResource(groupId, "weather-radar"));
        assertEquals(404, failure.httpStatus());
    }

    @Test
    void givenGroupWithoutSnapshotWhenReadThenRejected() {
        String groupId = newGroup("READY");
        V2DomainException failure = assertThrows(V2DomainException.class,
                () -> service.readResource(groupId, "airports"));
        assertEquals(422, failure.httpStatus());
        assertEquals("REFERENCE_NOT_FOUND", failure.code());
    }
}
