package org.bluesky.training.reference;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P03：manifest 与资源原子校验（详细设计 §11：校验全部通过后才切换）。 */
class ReferenceManifestValidatorTest {

    @TempDir
    Path storeDir;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ReferenceManifestValidator validator;

    @BeforeEach
    void setUp() {
        validator = new ReferenceManifestValidator(objectMapper);
    }

    private Path writeSnapshot(String airportJson, String navaidJson) throws Exception {
        Path airports = storeDir.resolve("airports.json");
        Path navaids = storeDir.resolve("navaids.json");
        Files.write(airports, airportJson.getBytes("UTF-8"));
        Files.write(navaids, navaidJson.getBytes("UTF-8"));
        String airportChecksum = ReferenceSnapshotStore.sha256Of(airports);
        String navaidChecksum = ReferenceSnapshotStore.sha256Of(navaids);
        Map<String, Object> manifest = new java.util.LinkedHashMap<>();
        manifest.put("schemaVersion", "reference-manifest/1");
        manifest.put("sourceBatch", "batch-1");
        manifest.put("resources", new java.util.LinkedHashMap<String, Object>());
        ((Map<String, Object>) manifest.get("resources")).put("airports",
                mapOf("file", "airports.json", "sha256", airportChecksum));
        ((Map<String, Object>) manifest.get("resources")).put("navaids",
                mapOf("file", "navaids.json", "sha256", navaidChecksum));
        Path manifestFile = storeDir.resolve("manifest.json");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(manifestFile.toFile(), manifest);
        return manifestFile;
    }

    private static Map<String, Object> mapOf(Object... pairs) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return map;
    }

    @Test
    void givenValidManifestWhenValidatedThenAllChecksPass() throws Exception {
        Path manifestFile = writeSnapshot(
                "[{\"id\":\"ZGGG\",\"code\":\"ZGGG\"}]",
                "[{\"id\":\"P47\",\"code\":\"P47\"}]");
        Map<String, Object> manifest = objectMapper.readValue(manifestFile.toFile(), Map.class);

        assertDoesNotThrow(() -> validator.validateSchemaVersion(manifest));
        assertDoesNotThrow(() -> validator.validateChecksums(storeDir, manifest));
        assertDoesNotThrow(() -> validator.validateReferentialIntegrity(manifest));
    }

    @Test
    void givenChecksumMismatchWhenSnapshotIsRejectedAtomically() throws Exception {
        Path manifestFile = writeSnapshot(
                "[{\"id\":\"ZGGG\"}]",
                "[{\"id\":\"P47\"}]");
        // 事后篡改资源：checksum 必须不匹配
        Files.write(storeDir.resolve("airports.json"),
                "[{\"id\":\"ZGGG\",\"code\":\"TAMPERED\"}]".getBytes("UTF-8"));
        Map<String, Object> manifest = objectMapper.readValue(manifestFile.toFile(), Map.class);

        assertEquals(Arrays.asList("airports"), validator.validateChecksums(storeDir, manifest),
                "checksum 不一致的资源必须被点名拒绝，且不产生部分切换");
    }

    @Test
    void givenWrongSchemaVersionWhenValidatedThenRejected() throws Exception {
        Path manifestFile = writeSnapshot("[]", "[]");
        Map<String, Object> manifest = objectMapper.readValue(manifestFile.toFile(), Map.class);
        manifest.put("schemaVersion", "reference-manifest/0");

        assertTrue(!validator.validateSchemaVersion(manifest).isEmpty());
    }

    @Test
    void givenUnknownResourceTypeWhenValidatedThenRejected() throws Exception {
        Map<String, Object> manifest = new java.util.LinkedHashMap<>();
        manifest.put("schemaVersion", "reference-manifest/1");
        manifest.put("resources", mapOf("weather-radar",
                mapOf("file", "x.json", "sha256", "00")));
        assertTrue(!validator.validateReferentialIntegrity(manifest).isEmpty(),
                "未知资源类型必须被拒绝，不得透传任意文件");
    }

    @Test
    void givenMissingResourceFileWhenValidatedThenRejected() throws Exception {
        Map<String, Object> manifest = new java.util.LinkedHashMap<>();
        manifest.put("schemaVersion", "reference-manifest/1");
        manifest.put("resources", mapOf("airports", mapOf("file", "missing.json", "sha256", "00")));
        assertEquals(Arrays.asList("airports"), validator.validateChecksums(storeDir, manifest));
    }

    @Test
    void givenDuplicateIdsInvalidCoordinatesAndBrokenReferencesThenContentIsRejected()
            throws Exception {
        Path airports = storeDir.resolve("airports.json");
        Path runways = storeDir.resolve("runways.json");
        Files.write(airports, ("[{\"id\":\"ZGGG\",\"latitude\":91},"
                + "{\"id\":\"ZGGG\"}]").getBytes("UTF-8"));
        Files.write(runways, "[{\"id\":\"02L\",\"airportId\":\"MISSING\"}]"
                .getBytes("UTF-8"));
        Map<String, Object> manifest = new java.util.LinkedHashMap<>();
        manifest.put("schemaVersion", "reference-manifest/1");
        manifest.put("resources", mapOf(
                "airports", mapOf("file", "airports.json", "sha256",
                        ReferenceSnapshotStore.sha256Of(airports)),
                "runways", mapOf("file", "runways.json", "sha256",
                        ReferenceSnapshotStore.sha256Of(runways))));

        java.util.List<String> problems = validator.validateResourceContents(storeDir, manifest);
        assertTrue(problems.stream().anyMatch(value -> value.contains("稳定 ID 重复")));
        assertTrue(problems.stream().anyMatch(value -> value.contains("坐标越界")));
        assertTrue(problems.stream().anyMatch(value -> value.contains("引用不存在")));
    }

    @Test
    void givenNestedResourcesWhenCopiedThenDirectoryTreeIsPreserved() throws Exception {
        Path source = storeDir.resolve("source");
        Path nested = source.resolve("resources/nav");
        Files.createDirectories(nested);
        Files.write(source.resolve("manifest.json"), "{}".getBytes("UTF-8"));
        Files.write(nested.resolve("navaids.json"), "[]".getBytes("UTF-8"));
        Path target = storeDir.resolve("published/snapshot-1");

        ReferenceSnapshotStore.copyPublishedSnapshot(source, target);

        assertTrue(Files.isRegularFile(target.resolve("manifest.json")));
        assertTrue(Files.isRegularFile(target.resolve("resources/nav/navaids.json")));
    }
}
