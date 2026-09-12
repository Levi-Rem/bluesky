package org.bluesky.training.testsupport;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * P00：生成第二版领域测试所需的最小合法夹具。
 * 字段语义见《模拟飞行员工作台第二版详细设计》2.2 第 5 节。
 */
public final class V2FixtureFactory {

    private V2FixtureFactory() {
    }

    public static String seedPublishedSnapshot(org.springframework.jdbc.core.JdbcTemplate jdbc,
                                               String groupId) {
        try {
            java.nio.file.Path root = java.nio.file.Paths.get("target");
            java.nio.file.Files.createDirectories(root);
            java.nio.file.Path store = java.nio.file.Files.createTempDirectory(
                    root, "v2-snapshot-").toAbsolutePath();
            java.nio.file.Path airports = store.resolve("airports.json");
            java.nio.file.Files.write(airports, "[]".getBytes("UTF-8"));
            String resourceChecksum = org.bluesky.training.reference.ReferenceSnapshotStore
                    .sha256Of(airports);
            String manifest = "{\"schemaVersion\":\"reference-manifest/1\","
                    + "\"sourceBatch\":\"test\",\"resources\":{\"airports\":{"
                    + "\"file\":\"airports.json\",\"sha256\":\"" + resourceChecksum + "\"}}}";
            java.nio.file.Path manifestFile = store.resolve("manifest.json");
            java.nio.file.Files.write(manifestFile, manifest.getBytes("UTF-8"));
            String snapshotId = UUID.randomUUID().toString();
            jdbc.update("INSERT INTO reference_snapshot (id, version_label, status, schema_version, "
                            + "manifest_json, manifest_checksum, source_batch, store_path, published_at) "
                            + "VALUES (?, ?, 'PUBLISHED', 'reference-manifest/1', ?, ?, 'test', ?, CURRENT_TIMESTAMP)",
                    snapshotId, "test-" + snapshotId, manifest,
                    org.bluesky.training.reference.ReferenceSnapshotStore.sha256Of(manifestFile),
                    store.toString());
            jdbc.update("UPDATE exercise_group SET reference_snapshot_id = ? WHERE id = ?",
                    snapshotId, groupId);
            return snapshotId;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("创建测试参考快照失败", e);
        }
    }

    public static Map<String, Object> readyGroup() {
        return group("READY");
    }

    public static Map<String, Object> runningGroup() {
        return group("RUNNING");
    }

    public static Map<String, Object> group(String state) {
        Map<String, Object> group = new LinkedHashMap<>();
        group.put("id", "group-" + UUID.randomUUID());
        group.put("name", "训练组-" + state);
        group.put("state", state);
        group.put("simulationTimeSeconds", 0.0);
        group.put("revision", 1L);
        return group;
    }

    public static Map<String, Object> terminal(String terminalId, String groupId) {
        Map<String, Object> terminal = new LinkedHashMap<>();
        terminal.put("id", terminalId);
        terminal.put("exerciseGroupId", groupId);
        terminal.put("name", "机长席-" + terminalId);
        terminal.put("frequency", 123.450);
        terminal.put("terminalType", "PSEUDO_PILOT");
        terminal.put("unitMode", "IMP");
        terminal.put("enabled", true);
        terminal.put("revision", 1L);
        return terminal;
    }

    public static Map<String, Object> activeAircraft(String aircraftId, String groupId, String terminalId) {
        Map<String, Object> aircraft = new LinkedHashMap<>();
        aircraft.put("id", aircraftId);
        aircraft.put("exerciseGroupId", groupId);
        aircraft.put("callsign", "CSN" + aircraftId.hashCode());
        aircraft.put("lifecycle", "ACTIVE");
        aircraft.put("flightPhase", "CRUISE");
        aircraft.put("controlStatus", "NORMAL");
        aircraft.put("isFake", false);
        aircraft.put("responsibleTerminalId", terminalId);
        aircraft.put("currentSquawk", "0042");
        aircraft.put("ssrMode", "C");
        aircraft.put("revision", 1L);
        return aircraft;
    }

    public static Map<String, Object> instruction(String instructionId, String aircraftId) {
        Map<String, Object> instruction = new LinkedHashMap<>();
        instruction.put("id", instructionId);
        instruction.put("aircraftId", aircraftId);
        instruction.put("type", "HDG");
        instruction.put("controlChannel", "LATERAL");
        instruction.put("scheduling", "REPLACE");
        instruction.put("state", "RECEIVED");
        instruction.put("conflictKey", "LATERAL");
        instruction.put("blockingReasons", Collections.emptyList());
        instruction.put("revision", 1L);
        return instruction;
    }
}
