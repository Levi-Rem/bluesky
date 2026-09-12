package org.bluesky.training.reference;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.bluesky.training.common.CallerContext;
import org.bluesky.training.common.ServiceAccessPolicy;
import org.bluesky.training.common.V2DomainException;
import org.bluesky.training.persistence.ReferenceSnapshotMapper;
import org.bluesky.training.persistence.ReferenceSnapshotRow;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** P03：参考快照发布、固定、校验、差异与按组读取（详细设计 §11）。 */
@Service
public class ReferenceSnapshotService {

    private final ReferenceSnapshotMapper mapper;
    private final ReferenceManifestValidator validator;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ServiceAccessPolicy accessPolicy = new ServiceAccessPolicy();
    private final String storeRoot;

    public ReferenceSnapshotService(
            ReferenceSnapshotMapper mapper,
            @Value("${bluesky.reference-snapshot.store-dir:data/reference-snapshots}") String storeRoot) {
        this.mapper = mapper;
        this.validator = new ReferenceManifestValidator(objectMapper);
        this.storeRoot = storeRoot;
    }

    /** 校验全部通过后才复制资源并写 PUBLISHED 行（原子发布）。 */
    @Transactional
    public Map<String, Object> publish(Path sourceDir, String versionLabel) {
        Path manifestFile = sourceDir.resolve("manifest.json");
        if (!Files.isRegularFile(manifestFile)) {
            throw new V2DomainException("REFERENCE_NOT_FOUND", 422, "快照源目录缺少 manifest.json");
        }
        Map<String, Object> manifest = readJsonMap(manifestFile);
        List<String> problems = validator.validateAll(sourceDir, manifest);
        if (!problems.isEmpty()) {
            throw new V2DomainException("REFERENCE_NOT_FOUND", 422,
                    "快照校验失败: " + problems);
        }

        String snapshotId = UUID.randomUUID().toString();
        Path targetDir = Paths.get(storeRoot, snapshotId);
        try {
            ReferenceSnapshotStore.copyPublishedSnapshot(sourceDir, targetDir);
        } catch (IOException e) {
            throw new UncheckedIOException("复制快照资源失败", e);
        }

        ReferenceSnapshotRow row = new ReferenceSnapshotRow();
        row.setId(snapshotId);
        row.setVersionLabel(versionLabel);
        row.setSchemaVersion(String.valueOf(manifest.get("schemaVersion")));
        row.setManifestJson(readText(manifestFile));
        row.setManifestChecksum(ReferenceSnapshotStore.sha256Of(targetDir.resolve("manifest.json")));
        row.setSourceBatch(manifest.get("sourceBatch") == null
                ? null : String.valueOf(manifest.get("sourceBatch")));
        row.setStorePath(targetDir.toString());
        mapper.insert(row);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", snapshotId);
        body.put("versionLabel", versionLabel);
        body.put("manifestChecksum", row.getManifestChecksum());
        body.put("status", "PUBLISHED");
        return body;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listPublished(CallerContext caller) {
        accessPolicy.requireOrchestrator(caller);
        List<Map<String, Object>> items = new ArrayList<>();
        for (ReferenceSnapshotRow row : mapper.listPublished()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.getId());
            item.put("versionLabel", row.getVersionLabel());
            item.put("manifestChecksum", row.getManifestChecksum());
            item.put("publishedAt", row.getPublishedAt());
            item.put("revision", row.getRevision());
            items.add(item);
        }
        return items;
    }

    /** 固定：仅 READY 状态、仅编排方；平台副本 checksum 复核通过才落库。 */
    @Transactional
    public Map<String, Object> pinToGroup(CallerContext caller, String groupId, String snapshotId,
                                         long expectedRevision) {
        accessPolicy.requireOrchestrator(caller);
        String groupState = mapper.findGroupState(groupId);
        if (groupState == null) {
            throw new V2DomainException("TERMINAL_NOT_FOUND", 404, "训练组不存在: " + groupId);
        }
        if (!"READY".equals(groupState)) {
            throw new V2DomainException("TRAINING_STATE_INVALID", 409,
                    "快照只能在 READY 状态固定，当前状态: " + groupState);
        }
        ReferenceSnapshotRow snapshot = requirePublishedSnapshot(snapshotId);
        List<String> problems = validator.validateChecksums(
                Paths.get(snapshot.getStorePath()), readJsonMap(
                        Paths.get(snapshot.getStorePath(), "manifest.json")));
        if (!problems.isEmpty()) {
            throw new V2DomainException("REFERENCE_NOT_FOUND", 422,
                    "平台快照副本校验失败: " + problems);
        }
        Long currentRevision = mapper.findGroupRevision(groupId);
        if (currentRevision == null || currentRevision.longValue() != expectedRevision
                || mapper.pinSnapshot(groupId, snapshotId, expectedRevision) != 1) {
            Long latest = mapper.findGroupRevision(groupId);
            throw new V2DomainException("REVISION_CONFLICT", 409,
                    "训练组 revision 已改变，期望 " + expectedRevision + " 实际 " + latest,
                    Arrays.asList("revision"));
        }
        Long revision = mapper.findGroupRevision(groupId);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("groupId", groupId);
        body.put("referenceSnapshotId", snapshotId);
        body.put("manifestChecksum", snapshot.getManifestChecksum());
        body.put("groupRevision", revision);
        return body;
    }

    /** 领域层兼容入口；HTTP v2 必须通过 If-Match 显式传入 revision。 */
    @Transactional
    public Map<String, Object> pinToGroup(CallerContext caller, String groupId, String snapshotId) {
        Long revision = mapper.findGroupRevision(groupId);
        return pinToGroup(caller, groupId, snapshotId, revision == null ? -1L : revision);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> verifyPinnedSnapshot(String groupId) {
        ReferenceSnapshotRow snapshot = requirePinnedSnapshot(groupId);
        Path storeDir = Paths.get(snapshot.getStorePath());
        Map<String, Object> manifest = readJsonMap(storeDir.resolve("manifest.json"));
        List<String> problems = new ArrayList<>(validator.validateAll(storeDir, manifest));
        if (!ReferenceSnapshotStore.sha256Of(storeDir.resolve("manifest.json"))
                .equals(snapshot.getManifestChecksum())) {
            problems.add("manifest checksum 与发布记录不一致");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("valid", problems.isEmpty());
        body.put("manifestChecksum", snapshot.getManifestChecksum());
        if (!problems.isEmpty()) {
            body.put("problems", problems);
        }
        return body;
    }

    /** 预建计划允许未固定快照；已固定快照则必须先通过完整性校验。 */
    @Transactional(readOnly = true)
    public void verifyPinnedSnapshotIfPresent(String groupId) {
        if (groupId == null || mapper.findGroupSnapshotId(groupId) == null) {
            return;
        }
        Map<String, Object> verification = verifyPinnedSnapshot(groupId);
        if (!Boolean.TRUE.equals(verification.get("valid"))) {
            throw new V2DomainException("REFERENCE_NOT_FOUND", 422,
                    "固定参考快照完整性校验失败: " + verification.get("problems"));
        }
    }

    /** STARTING 发往 Adapter 的不可变参考载荷。 */
    @Transactional(readOnly = true)
    public Map<String, Object> adapterLoadPayload(String groupId) {
        ReferenceSnapshotRow snapshot = requirePinnedSnapshot(groupId);
        Path storeDir = Paths.get(snapshot.getStorePath());
        Map<String, Object> manifest = readJsonMap(storeDir.resolve("manifest.json"));
        Map<String, Object> resources = new LinkedHashMap<>();
        Object definitions = manifest.get("resources");
        if (definitions instanceof Map) {
            for (Object type : ((Map<?, ?>) definitions).keySet()) {
                resources.put(String.valueOf(type), readResource(groupId, String.valueOf(type)));
            }
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("snapshotId", snapshot.getId());
        payload.put("manifestJson", snapshot.getManifestJson());
        payload.put("manifestChecksum", snapshot.getManifestChecksum());
        payload.put("resources", resources);
        return payload;
    }

    @Transactional(readOnly = true)
    public String pinnedManifestChecksum(String groupId) {
        return requirePinnedSnapshot(groupId).getManifestChecksum();
    }

    /** 切换快照前比对既有计划引用：返回新快照中缺失的稳定 ID 全量清单。 */
    @Transactional(readOnly = true)
    public List<String> diffPlanReferences(String groupId, List<String> plannedReferenceIds) {
        ReferenceSnapshotRow snapshot = requirePinnedSnapshot(groupId);
        Path storeDir = Paths.get(snapshot.getStorePath());
        Map<String, Object> manifest = readJsonMap(storeDir.resolve("manifest.json"));
        List<String> knownIds = new ArrayList<>();
        for (String resourceType : Arrays.asList("airports", "navaids")) {
            String file = resourceFile(manifest, resourceType);
            if (file == null) {
                continue;
            }
            Path resourcePath = storeDir.resolve(file);
            if (!Files.isRegularFile(resourcePath)) {
                continue;
            }
            try {
                for (Object element : objectMapper.readValue(resourcePath.toFile(), List.class)) {
                    if (element instanceof Map) {
                        Object id = ((Map<?, ?>) element).get("id");
                        if (id == null) {
                            id = ((Map<?, ?>) element).get("code");
                        }
                        if (id != null) {
                            knownIds.add(String.valueOf(id));
                        }
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException("读取快照资源失败: " + resourceType, e);
            }
        }
        List<String> missing = new ArrayList<>();
        for (String planned : plannedReferenceIds) {
            if (!knownIds.contains(planned)) {
                missing.add(planned);
            }
        }
        return missing;
    }

    @Transactional(readOnly = true)
    public Object readResource(String groupId, String resourceType) {
        if (!ReferenceManifestValidator.ALLOWED_RESOURCE_TYPES.contains(resourceType)) {
            throw new V2DomainException("REFERENCE_NOT_FOUND", 404,
                    "未知资源类型: " + resourceType);
        }
        ReferenceSnapshotRow snapshot = requirePinnedSnapshot(groupId);
        Map<String, Object> manifest = readJsonMap(
                Paths.get(snapshot.getStorePath(), "manifest.json"));
        String file = resourceFile(manifest, resourceType);
        if (file == null) {
            throw new V2DomainException("REFERENCE_NOT_FOUND", 422,
                    "固定快照缺少资源: " + resourceType);
        }
        try {
            return objectMapper.readValue(
                    Paths.get(snapshot.getStorePath(), file).toFile(), Object.class);
        } catch (IOException e) {
            throw new UncheckedIOException("读取快照资源失败: " + resourceType, e);
        }
    }

    /** 读取可选模板资源；资源或快照不存在时返回 null，不通过异常驱动控制流。 */
    @Transactional(readOnly = true)
    public Object readOptionalResource(String groupId, String resourceType) {
        if (groupId == null || !ReferenceManifestValidator.ALLOWED_RESOURCE_TYPES.contains(resourceType)) {
            return null;
        }
        String snapshotId = mapper.findGroupSnapshotId(groupId);
        if (snapshotId == null) {
            return null;
        }
        ReferenceSnapshotRow snapshot = mapper.findById(snapshotId);
        if (snapshot == null || !"PUBLISHED".equals(snapshot.getStatus())) {
            return null;
        }
        Map<String, Object> manifest = readJsonMap(
                Paths.get(snapshot.getStorePath(), "manifest.json"));
        String file = resourceFile(manifest, resourceType);
        if (file == null) {
            return null;
        }
        Path root = Paths.get(snapshot.getStorePath()).toAbsolutePath().normalize();
        Path target = root.resolve(file).normalize();
        if (!target.startsWith(root) || !Files.isRegularFile(target)) {
            throw new V2DomainException("REFERENCE_NOT_FOUND", 422,
                    "快照资源文件不存在或路径非法: " + resourceType);
        }
        try {
            return objectMapper.readValue(target.toFile(), Object.class);
        } catch (IOException e) {
            throw new UncheckedIOException("读取快照资源失败: " + resourceType, e);
        }
    }

    private ReferenceSnapshotRow requirePublishedSnapshot(String snapshotId) {
        ReferenceSnapshotRow snapshot = snapshotId == null ? null : mapper.findById(snapshotId);
        if (snapshot == null || !"PUBLISHED".equals(snapshot.getStatus())) {
            throw new V2DomainException("REFERENCE_NOT_FOUND", 422,
                    "已发布快照不存在: " + snapshotId);
        }
        return snapshot;
    }

    private ReferenceSnapshotRow requirePinnedSnapshot(String groupId) {
        String snapshotId = groupId == null ? null : mapper.findGroupSnapshotId(groupId);
        if (snapshotId == null) {
            throw new V2DomainException("REFERENCE_NOT_FOUND", 422,
                    "训练组尚未固定参考快照: " + groupId);
        }
        return requirePublishedSnapshot(snapshotId);
    }

    @SuppressWarnings("unchecked")
    private String resourceFile(Map<String, Object> manifest, String resourceType) {
        Object resources = manifest.get("resources");
        if (!(resources instanceof Map)) {
            return null;
        }
        Object definition = ((Map<String, Object>) resources).get(resourceType);
        if (!(definition instanceof Map)) {
            return null;
        }
        Object file = ((Map<String, Object>) definition).get("file");
        return file == null ? null : String.valueOf(file);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJsonMap(Path file) {
        try {
            Object parsed = objectMapper.readValue(file.toFile(), Object.class);
            if (!(parsed instanceof Map)) {
                throw new V2DomainException("REFERENCE_NOT_FOUND", 422, "manifest 必须是对象: " + file);
            }
            return (Map<String, Object>) parsed;
        } catch (IOException e) {
            throw new UncheckedIOException("读取 manifest 失败: " + file, e);
        }
    }

    private static String readText(Path file) {
        try {
            return new String(Files.readAllBytes(file), "UTF-8");
        } catch (IOException e) {
            throw new UncheckedIOException("读取文件失败: " + file, e);
        }
    }
}
