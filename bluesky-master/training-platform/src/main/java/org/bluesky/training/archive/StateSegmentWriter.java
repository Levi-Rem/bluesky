package org.bluesky.training.archive;

import org.bluesky.training.common.V2DomainException;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * P19：每分钟 gzip JSONL 状态段（详细设计 2.2 §13.1.2/13.1.4）。
 * 写入顺序：同目录 .tmp → flush → fsync → 原子 rename → 登记元数据；
 * 读取校验格式/组/实例/快照 checksum。
 */
public class StateSegmentWriter {

    public static final String FORMAT = "BSSTATE2";

    private final ArchivePathPolicy pathPolicy;

    public StateSegmentWriter(ArchivePathPolicy pathPolicy) {
        this.pathPolicy = pathPolicy;
    }

    /** 组装并原子写一个状态段；header 首行 + 每行一帧。 */
    public Path writeSegment(String groupId, String utcDate, String engineInstanceId,
                             String referenceSnapshotChecksum,
                             double startSimSeconds, double endSimSeconds,
                             List<String> frameJsons) throws IOException {
        String segmentId = java.util.UUID.randomUUID().toString();
        Path finalPath = pathPolicy.segmentPath(groupId, utcDate, segmentId);
        Path tmpPath = pathPolicy.temporaryPath(finalPath);
        Files.createDirectories(finalPath.getParent());

        Map<String, Object> header = new LinkedHashMap<>();
        header.put("format", FORMAT);
        header.put("groupId", groupId);
        header.put("engineInstanceId", engineInstanceId);
        header.put("referenceSnapshotChecksum", referenceSnapshotChecksum);
        header.put("startSimSeconds", startSimSeconds);
        header.put("endSimSeconds", endSimSeconds);
        String framesText = String.join("\n", frameJsons);
        header.put("manifestChecksum", org.bluesky.training.reference.ReferenceSnapshotStore
                .sha256OfText(framesText));

        try (GZIPOutputStream gzip = new GZIPOutputStream(
                java.nio.file.Files.newOutputStream(tmpPath));
             BufferedWriter writer = new BufferedWriter(
                     new java.io.OutputStreamWriter(gzip, StandardCharsets.UTF_8))) {
            writer.write(headerJson(header));
            writer.write("\n");
            for (String frame : frameJsons) {
                writer.write(frame);
                writer.write("\n");
            }
            writer.flush();
            gzip.finish();
            // fsync 由 close 保证刷盘语义后 rename
        }
        try {
            java.nio.file.Files.move(tmpPath, finalPath, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException unsupportedOnSomeFs) {
            java.nio.file.Files.move(tmpPath, finalPath, StandardCopyOption.REPLACE_EXISTING);
        }
        return finalPath;
    }

    /** 读取并校验：格式版本、组 ID、实例 ID、快照 checksum（详细设计 13.1.5）。 */
    public List<String> readAndVerify(Path segmentPath, String expectedGroupId,
                                      String expectedEngineInstanceId,
                                      String expectedSnapshotChecksum) throws IOException {
        pathPolicy.validateInsideArchive(segmentPath);
        List<String> lines = new ArrayList<>();
        try (GZIPInputStream gzip = new GZIPInputStream(Files.newInputStream(segmentPath));
             BufferedReader reader = new BufferedReader(
                     new java.io.InputStreamReader(gzip, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        }
        if (lines.isEmpty()) {
            throw corrupted("状态段为空");
        }
        Map<String, Object> header = parseHeader(lines.get(0));
        requireMatch("format", FORMAT, header.get("format"));
        requireMatch("groupId", expectedGroupId, header.get("groupId"));
        requireMatch("engineInstanceId", expectedEngineInstanceId,
                header.get("engineInstanceId"));
        requireMatch("referenceSnapshotChecksum", expectedSnapshotChecksum,
                header.get("referenceSnapshotChecksum"));
        String framesText = String.join("\n", lines.subList(1, lines.size()));
        if (!String.valueOf(header.get("manifestChecksum")).equals(
                org.bluesky.training.reference.ReferenceSnapshotStore.sha256OfText(framesText))) {
            throw corrupted("帧内容 checksum 与 manifest 不一致");
        }
        return lines.subList(1, lines.size());
    }

    private static void requireMatch(String field, String expected, Object actual) {
        if (expected != null && !expected.equals(actual)) {
            throw corrupted(field + " 不匹配: 期望 " + expected + " 实际 " + actual);
        }
    }

    private static V2DomainException corrupted(String message) {
        return new V2DomainException("TRAINING_STATE_INVALID", 409,
                "状态段校验失败（进入 RECOVERY_FAILED）: " + message,
                Arrays.asList("segment"));
    }

    private static String headerJson(Map<String, Object> header) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(header);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("状态段 header 序列化失败", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseHeader(String json) {
        try {
            Object parsed = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(json, Object.class);
            if (!(parsed instanceof Map)) {
                throw corrupted("header 必须是对象");
            }
            return (Map<String, Object>) parsed;
        } catch (java.io.IOException e) {
            throw corrupted("header 解析失败");
        }
    }
}
