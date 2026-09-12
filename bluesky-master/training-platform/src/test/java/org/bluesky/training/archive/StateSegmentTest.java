package org.bluesky.training.archive;

import org.bluesky.training.common.V2DomainException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P19：状态段原子写入与校验（详细设计 2.2 §13.1.2/§13.1.5）。 */
class StateSegmentTest {

    @TempDir
    Path archiveRoot;

    private final String groupId = UUID.randomUUID().toString();
    private final String engineId = UUID.randomUUID().toString();
    private final String snapshotChecksum = "sha256-snap-1";

    private StateSegmentWriter writer() {
        return new StateSegmentWriter(new ArchivePathPolicy(archiveRoot.toString()));
    }

    @Test
    void givenSegmentWhenWrittenAndReadBackThenFramesAndHeaderSurvive() throws Exception {
        StateSegmentWriter writer = writer();
        List<String> frames = Arrays.asList(
                "{\"stateFrameSequence\":1,\"aircraftId\":\"a1\",\"lat\":23.0}",
                "{\"stateFrameSequence\":2,\"aircraftId\":\"a1\",\"lat\":23.1}");

        Path path = writer.writeSegment(groupId, "2026-08-31", engineId, snapshotChecksum,
                600.0, 660.0, frames);
        assertTrue(path.getFileName().toString().endsWith(".jsonl.gz"));

        List<String> readBack = writer.readAndVerify(path, groupId, engineId,
                snapshotChecksum);
        assertEquals(frames, readBack);

        // 再写一段互不干扰（每分钟一段）
        Path second = writer.writeSegment(groupId, "2026-08-31", engineId, snapshotChecksum,
                660.0, 720.0, frames);
        assertTrue(!second.equals(path));
        assertEquals(2, java.nio.file.Files.list(
                path.getParent()).count(), "同组同日两段");
    }

    @Test
    void givenChecksumMismatchWhenReadThenCorruptedAndNoPartialSwitch() throws Exception {
        StateSegmentWriter writer = writer();
        Path path = writer.writeSegment(groupId, "2026-08-31", engineId, snapshotChecksum,
                600.0, 660.0, Arrays.asList("{\"stateFrameSequence\":1}"));

        // 篡改内容（gzip 内改写会破坏流）→ 以错误期望 checksum 模拟校验失败
        V2DomainException wrongSnapshot = assertThrows(V2DomainException.class,
                () -> writer.readAndVerify(path, groupId, engineId, "sha256-OTHER"));
        assertEquals(409, wrongSnapshot.httpStatus());
        assertTrue(wrongSnapshot.getMessage().contains("RECOVERY_FAILED"),
                "校验失败进入 RECOVERY_FAILED（详细设计 13.1.5）");

        // 组/实例不匹配同样拒绝（迟到实例防护）
        assertThrows(V2DomainException.class,
                () -> writer.readAndVerify(path, UUID.randomUUID().toString(), engineId,
                        snapshotChecksum));
        assertThrows(V2DomainException.class,
                () -> writer.readAndVerify(path, groupId, UUID.randomUUID().toString(),
                        snapshotChecksum));

        // 原文件未被读取破坏，正确参数仍可读
        assertEquals(1, writer.readAndVerify(path, groupId, engineId, snapshotChecksum).size());
    }

    @Test
    void givenPathOutsideArchiveWhenReadThenRejected() throws Exception {
        StateSegmentWriter writer = writer();
        Path path = writer.writeSegment(groupId, "2026-08-31", engineId, snapshotChecksum,
                600.0, 660.0, Arrays.asList("{}"));
        Path outside = archiveRoot.resolveSibling("outside.jsonl.gz");
        java.nio.file.Files.copy(path, outside,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        assertThrows(V2DomainException.class,
                () -> writer.readAndVerify(outside, groupId, engineId, snapshotChecksum));
    }
}
