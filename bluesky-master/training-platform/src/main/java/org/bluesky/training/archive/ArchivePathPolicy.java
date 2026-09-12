package org.bluesky.training.archive;

import org.bluesky.training.common.V2DomainException;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.UUID;

/**
 * P19：归档路径策略（详细设计 2.2 §13.1）。
 * 只在显式 BS_STATE_ARCHIVE_DIR 下生成安全路径；
 * 禁止回退当前目录/用户目录，目录结构 <groupId>/<UTC-date>/，文件名只允许 UUID。
 */
public class ArchivePathPolicy {

    public static final String ARCHIVE_DIR_PROPERTY = "BS_STATE_ARCHIVE_DIR";

    private final Path archiveRoot;

    public ArchivePathPolicy(String configuredDir) {
        if (configuredDir == null || configuredDir.trim().isEmpty()) {
            throw new V2DomainException("TRAINING_STATE_INVALID", 409,
                    "未配置 BS_STATE_ARCHIVE_DIR，禁止使用当前目录或用户主目录推断归档根",
                    Arrays.asList(ARCHIVE_DIR_PROPERTY));
        }
        this.archiveRoot = Paths.get(configuredDir.trim()).toAbsolutePath().normalize();
    }

    /** <groupId>/<UTC-date>/ 目录；groupId 必须是白名单校验过的 UUID 形态。 */
    public Path resolveGroupDay(String groupId, String utcDate) {
        validateUuidComponent(groupId);
        if (utcDate == null || !utcDate.matches("\\d{4}-\\d{2}-\\d{2}")) {
            throw new V2DomainException("TRAINING_STATE_INVALID", 409,
                    "UTC 日期必须是 yyyy-MM-dd: " + utcDate, Arrays.asList("utcDate"));
        }
        return archiveRoot.resolve(Paths.get(groupId, utcDate)).normalize();
    }

    /** 状态段文件名只使用 UUID（详细设计 13.1.1）。 */
    public Path segmentPath(String groupId, String utcDate, String segmentId) {
        validateUuidComponent(segmentId);
        return resolveGroupDay(groupId, utcDate).resolve(segmentId + ".jsonl.gz");
    }

    /** tmp 写入路径（同目录，原子 rename 前使用）。 */
    public Path temporaryPath(Path finalPath) {
        return finalPath.resolveSibling(finalPath.getFileName() + ".tmp");
    }

    /** 校验路径未逃逸归档根（防跨组路径穿越）。 */
    public void validateInsideArchive(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(archiveRoot)) {
            throw new V2DomainException("TRAINING_STATE_INVALID", 409,
                    "归档路径逃逸归档根: " + normalized, Arrays.asList("path"));
        }
    }

    private static void validateUuidComponent(String component) {
        if (component == null) {
            throw uuidError(null);
        }
        try {
            UUID.fromString(component);
        } catch (IllegalArgumentException e) {
            throw uuidError(component);
        }
    }

    private static V2DomainException uuidError(String component) {
        return new V2DomainException("TRAINING_STATE_INVALID", 409,
                "归档组件必须是白名单校验的 UUID: " + component, Arrays.asList("component"));
    }
}
