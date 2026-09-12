package org.bluesky.training.reference;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

/** P03：manifest 与资源原子校验（详细设计 §11）。 */
public class ReferenceManifestValidator {

    public static final String EXPECTED_SCHEMA_VERSION = "reference-manifest/1";

    /** 详细设计 9.2：resourceType 白名单，未知类型拒绝且不透传文件路径。 */
    public static final List<String> ALLOWED_RESOURCE_TYPES = Collections.unmodifiableList(Arrays.asList(
            "airports", "runways", "procedures", "navaids", "holding-patterns",
            "map-layers", "aircraft-performance", "initial-state-templates", "magnetic-model"));

    private final ObjectMapper objectMapper;

    public ReferenceManifestValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<String> validateSchemaVersion(Map<String, Object> manifest) {
        List<String> problems = new ArrayList<>();
        if (manifest == null
                || !EXPECTED_SCHEMA_VERSION.equals(String.valueOf(manifest.get("schemaVersion")))) {
            problems.add("manifest schemaVersion 必须是 " + EXPECTED_SCHEMA_VERSION);
        }
        return problems;
    }

    @SuppressWarnings("unchecked")
    public List<String> validateChecksums(Path directory, Map<String, Object> manifest) {
        List<String> problems = new ArrayList<>();
        if (manifest == null) {
            problems.add("manifest 缺失");
            return problems;
        }
        Object resourcesObject = manifest.get("resources");
        if (!(resourcesObject instanceof Map) || ((Map<String, Object>) resourcesObject).isEmpty()) {
            problems.add("manifest 缺少 resources 定义");
            return problems;
        }
        for (Map.Entry<String, Object> entry : ((Map<String, Object>) resourcesObject).entrySet()) {
            String resourceType = entry.getKey();
            if (!(entry.getValue() instanceof Map)) {
                problems.add(resourceType);
                continue;
            }
            Map<String, Object> resource = (Map<String, Object>) entry.getValue();
            String file = String.valueOf(resource.get("file"));
            String expected = String.valueOf(resource.get("sha256"));
            Path root = directory.toAbsolutePath().normalize();
            Path target = root.resolve(file).normalize();
            if (!target.startsWith(root) || !Files.isRegularFile(target)) {
                problems.add(resourceType);
                continue;
            }
            try {
                if (!target.toRealPath().startsWith(root.toRealPath())) {
                    problems.add(resourceType);
                    continue;
                }
            } catch (IOException e) {
                problems.add(resourceType);
                continue;
            }
            if (!expected.equals(ReferenceSnapshotStore.sha256Of(target))) {
                problems.add(resourceType);
            }
        }
        return problems;
    }

    /** 读取资源正文，校验 JSON 结构、稳定 ID、坐标范围及核心跨资源引用。 */
    @SuppressWarnings("unchecked")
    public List<String> validateResourceContents(Path directory, Map<String, Object> manifest) {
        List<String> problems = new ArrayList<>();
        Object resourcesObject = manifest == null ? null : manifest.get("resources");
        if (!(resourcesObject instanceof Map)) {
            return Collections.singletonList("manifest 缺少 resources 定义");
        }
        Map<String, List<Map<String, Object>>> recordsByType = new HashMap<>();
        Map<String, Set<String>> idsByType = new HashMap<>();
        Path root = directory.toAbsolutePath().normalize();
        for (Map.Entry<String, Object> entry : ((Map<String, Object>) resourcesObject).entrySet()) {
            if (!(entry.getValue() instanceof Map)) {
                continue;
            }
            Object fileValue = ((Map<String, Object>) entry.getValue()).get("file");
            if (isBlank(fileValue)) {
                continue;
            }
            Path file = root.resolve(String.valueOf(fileValue)).normalize();
            if (!file.startsWith(root) || !Files.isRegularFile(file)) {
                continue;
            }
            Object parsed;
            try {
                parsed = objectMapper.readValue(file.toFile(), Object.class);
            } catch (IOException e) {
                problems.add("资源不是合法 JSON: " + entry.getKey());
                continue;
            }
            if (!(parsed instanceof List)) {
                // magnetic-model 等资源允许使用对象；列表型资源才参与稳定 ID 与引用校验。
                continue;
            }
            List<Map<String, Object>> records = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            int index = 0;
            for (Object value : (List<?>) parsed) {
                if (!(value instanceof Map)) {
                    problems.add("资源记录必须是对象: " + entry.getKey() + "[" + index + "]");
                    index++;
                    continue;
                }
                Map<String, Object> record = (Map<String, Object>) value;
                records.add(record);
                String id = stableId(record);
                if (requiresStableId(entry.getKey()) && id == null) {
                    problems.add("资源记录缺少稳定 id/code: " + entry.getKey() + "[" + index + "]");
                } else if (id != null && !ids.add(id)) {
                    problems.add("资源稳定 ID 重复: " + entry.getKey() + ":" + id);
                }
                validateCoordinate(record, entry.getKey(), index, problems);
                index++;
            }
            recordsByType.put(entry.getKey(), records);
            idsByType.put(entry.getKey(), ids);
        }
        validateReferences(recordsByType, idsByType, problems);
        return problems;
    }

    @SuppressWarnings("unchecked")
    public List<String> validateReferentialIntegrity(Map<String, Object> manifest) {
        List<String> problems = new ArrayList<>();
        if (manifest == null) {
            problems.add("manifest 缺失");
            return problems;
        }
        Object resourcesObject = manifest.get("resources");
        if (!(resourcesObject instanceof Map) || ((Map<String, Object>) resourcesObject).isEmpty()) {
            problems.add("manifest 缺少 resources 定义");
            return problems;
        }
        for (Map.Entry<String, Object> entry : ((Map<String, Object>) resourcesObject).entrySet()) {
            String resourceType = entry.getKey();
            if (!ALLOWED_RESOURCE_TYPES.contains(resourceType)) {
                problems.add("未知资源类型: " + resourceType);
                continue;
            }
            if (!(entry.getValue() instanceof Map)) {
                problems.add("资源定义非法: " + resourceType);
                continue;
            }
            Map<String, Object> resource = (Map<String, Object>) entry.getValue();
            if (isBlank(resource.get("file")) || isBlank(resource.get("sha256"))) {
                problems.add("资源缺少 file/sha256: " + resourceType);
            }
        }
        if (objectMapper == null) {
            problems.add("未配置 JSON 解析器");
        }
        return problems;
    }

    public List<String> validateAll(Path directory, Map<String, Object> manifest) {
        List<String> problems = new ArrayList<>();
        problems.addAll(validateSchemaVersion(manifest));
        problems.addAll(validateReferentialIntegrity(manifest));
        problems.addAll(validateChecksums(directory, manifest));
        problems.addAll(validateResourceContents(directory, manifest));
        return problems;
    }

    private static boolean requiresStableId(String type) {
        return Arrays.asList("airports", "runways", "procedures", "navaids",
                "holding-patterns", "map-layers", "aircraft-performance",
                "initial-state-templates").contains(type);
    }

    private static String stableId(Map<String, Object> record) {
        Object value = record.get("id");
        if (isBlank(value)) {
            value = record.get("code");
        }
        return isBlank(value) ? null : String.valueOf(value);
    }

    private static void validateCoordinate(Map<String, Object> record, String type, int index,
                                           List<String> problems) {
        checkRange(record, "latitude", -90.0, 90.0, type, index, problems);
        checkRange(record, "lat", -90.0, 90.0, type, index, problems);
        checkRange(record, "longitude", -180.0, 180.0, type, index, problems);
        checkRange(record, "lon", -180.0, 180.0, type, index, problems);
    }

    private static void checkRange(Map<String, Object> record, String field,
                                   double min, double max, String type, int index,
                                   List<String> problems) {
        Object value = record.get(field);
        if (value == null) {
            return;
        }
        if (!(value instanceof Number)
                || ((Number) value).doubleValue() < min
                || ((Number) value).doubleValue() > max) {
            problems.add("坐标越界: " + type + "[" + index + "]." + field);
        }
    }

    private static void validateReferences(Map<String, List<Map<String, Object>>> recordsByType,
                                           Map<String, Set<String>> idsByType,
                                           List<String> problems) {
        for (Map<String, Object> runway : recordsByType.getOrDefault(
                "runways", Collections.emptyList())) {
            requireReference(runway, "airportId", "airports", idsByType, problems);
        }
        for (Map<String, Object> procedure : recordsByType.getOrDefault(
                "procedures", Collections.emptyList())) {
            requireReference(procedure, "airportId", "airports", idsByType, problems);
            requireReference(procedure, "runwayId", "runways", idsByType, problems);
            requireReferenceList(procedure, "navaidIds", "navaids", idsByType, problems);
        }
        for (Map<String, Object> holding : recordsByType.getOrDefault(
                "holding-patterns", Collections.emptyList())) {
            requireReference(holding, "navaidId", "navaids", idsByType, problems);
        }
    }

    private static void requireReference(Map<String, Object> record, String field, String targetType,
                                         Map<String, Set<String>> idsByType, List<String> problems) {
        Object value = record.get(field);
        if (!isBlank(value) && !idsByType.getOrDefault(targetType, Collections.emptySet())
                .contains(String.valueOf(value))) {
            problems.add("引用不存在: " + stableId(record) + "." + field + "=" + value);
        }
    }

    private static void requireReferenceList(Map<String, Object> record, String field,
                                             String targetType,
                                             Map<String, Set<String>> idsByType,
                                             List<String> problems) {
        Object values = record.get(field);
        if (!(values instanceof List)) {
            return;
        }
        for (Object value : (List<?>) values) {
            if (!idsByType.getOrDefault(targetType, Collections.emptySet())
                    .contains(String.valueOf(value))) {
                problems.add("引用不存在: " + stableId(record) + "." + field + "=" + value);
            }
        }
    }

    private static boolean isBlank(Object value) {
        return value == null || String.valueOf(value).trim().isEmpty() || "null".equals(value);
    }
}
