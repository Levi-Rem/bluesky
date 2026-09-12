package org.bluesky.training.archive;

import org.bluesky.training.common.V2DomainException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P19：归档路径策略与容量门（详细设计 2.2 §13.1/§12.3/§13.2.4）。 */
class ArchivePolicyTest {

    @TempDir
    Path archiveRoot;

    private final String groupId = UUID.randomUUID().toString();

    @Test
    void givenNoConfiguredDirWhenConstructedThenRejected() {
        V2DomainException missing = assertThrows(V2DomainException.class,
                () -> new ArchivePathPolicy(null));
        assertTrue(missing.getMessage().contains("BS_STATE_ARCHIVE_DIR"),
                "绝不回退当前目录/用户目录");
        assertThrows(V2DomainException.class, () -> new ArchivePathPolicy("   "));
    }

    @Test
    void givenGroupDayWhenResolvedThenPathInsideArchive() {
        ArchivePathPolicy policy = new ArchivePathPolicy(archiveRoot.toString());
        Path day = policy.resolveGroupDay(groupId, "2026-08-31");

        assertTrue(day.startsWith(archiveRoot));
        assertTrue(day.endsWith(java.nio.file.Paths.get(groupId, "2026-08-31")));

        Path segment = policy.segmentPath(groupId, "2026-08-31",
                UUID.randomUUID().toString());
        assertTrue(segment.getFileName().toString().endsWith(".jsonl.gz"));
        policy.validateInsideArchive(segment);
    }

    @Test
    void givenPathTraversalOrNonUuidWhenResolvedThenRejected() {
        ArchivePathPolicy policy = new ArchivePathPolicy(archiveRoot.toString());

        // 非 UUID groupId
        assertThrows(V2DomainException.class,
                () -> policy.resolveGroupDay("GROUP-DEFAULT", "2026-08-31"));
        assertThrows(V2DomainException.class,
                () -> policy.segmentPath(groupId, "2026-08-31", "segment-1"));

        // 路径穿越：UUID 校验先挡
        assertThrows(V2DomainException.class,
                () -> policy.resolveGroupDay("../../etc", "2026-08-31"));
        // 非法日期
        assertThrows(V2DomainException.class,
                () -> policy.resolveGroupDay(groupId, "2026/08/31"));

        // 跨组路径：validateInsideArchive 拒绝归档根外的规范化路径
        V2DomainException escape = assertThrows(V2DomainException.class,
                () -> policy.validateInsideArchive(
                        archiveRoot.resolveSibling("other-root").resolve("x.jsonl.gz")));
        assertTrue(escape.getMessage().contains("逃逸"));

        // tmp 路径同目录
        Path tmp = policy.temporaryPath(
                policy.segmentPath(groupId, "2026-08-31", UUID.randomUUID().toString()));
        assertTrue(tmp.getFileName().toString().endsWith(".tmp"));
        assertEquals(tmp.getParent(),
                policy.resolveGroupDay(groupId, "2026-08-31"));
    }

    @Test
    void givenCapacityThresholdsWhenEvaluatedThenGatesMatchDesign() {
        assertEquals(ArchiveCapacityGuard.Action.NONE,
                ArchiveCapacityGuard.actionForUsage(0.69));
        assertEquals(ArchiveCapacityGuard.Action.WARN,
                ArchiveCapacityGuard.actionForUsage(0.70));
        assertEquals(ArchiveCapacityGuard.Action.WARN,
                ArchiveCapacityGuard.actionForUsage(0.849));
        assertEquals(ArchiveCapacityGuard.Action.BLOCK_NEW_START,
                ArchiveCapacityGuard.actionForUsage(0.85));
        assertEquals(ArchiveCapacityGuard.Action.EMERGENCY_PAUSE,
                ArchiveCapacityGuard.actionForUsage(0.95));
        assertEquals(ArchiveCapacityGuard.Action.EMERGENCY_PAUSE,
                ArchiveCapacityGuard.actionForUsage(0.99));

        // 紧急 PAUSE 确认顺序：必须先发 PAUSE 且 Adapter 确认后才进 RECOVERING
        assertEquals(false, ArchiveCapacityGuard.pauseConfirmedForEmergency(true, false));
        assertEquals(false, ArchiveCapacityGuard.pauseConfirmedForEmergency(false, true));
        assertEquals(true, ArchiveCapacityGuard.pauseConfirmedForEmergency(true, true));
    }

    @Test
    void givenRecoveryToleranceWhenCheckedThenBoundariesHold() {
        assertEquals(true, ArchiveCapacityGuard.withinRecoveryTolerance(0.1, 100, 5));
        assertEquals(true, ArchiveCapacityGuard.withinRecoveryTolerance(0.0, 0, 0));
        assertEquals(false, ArchiveCapacityGuard.withinRecoveryTolerance(0.1001, 0, 0));
        assertEquals(false, ArchiveCapacityGuard.withinRecoveryTolerance(0, 100.1, 0));
        assertEquals(false, ArchiveCapacityGuard.withinRecoveryTolerance(0, 0, 5.1));
    }
}
