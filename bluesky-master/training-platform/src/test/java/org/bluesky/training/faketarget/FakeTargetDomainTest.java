package org.bluesky.training.faketarget;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P16：假目标状态机与运动推演（详细设计 2.2 §5.6）。 */
class FakeTargetDomainTest {

    @Test
    void givenEveryLegalEdgeWhenTransitionedThenTargetMatches() {
        assertEquals("ACTIVE", FakeTargetDomain.transition("SCHEDULED", "ACTIVATE"));
        assertEquals("FAILED", FakeTargetDomain.transition("SCHEDULED", "FAIL"));
        assertEquals("STOPPED", FakeTargetDomain.transition("ACTIVE", "STOP"));
        assertEquals("EXPIRED", FakeTargetDomain.transition("ACTIVE", "EXPIRE"));
        assertEquals("DELETE_REQUESTED", FakeTargetDomain.transition("ACTIVE", "REQUEST_DELETE"));
        assertEquals("DELETE_REQUESTED", FakeTargetDomain.transition("STOPPED", "REQUEST_DELETE"));
        assertEquals("DELETE_REQUESTED", FakeTargetDomain.transition("EXPIRED", "REQUEST_DELETE"));
        assertEquals("DELETED", FakeTargetDomain.transition("DELETE_REQUESTED", "CONFIRM_DELETE"));
        assertEquals("FAILED", FakeTargetDomain.transition("DELETE_REQUESTED", "FAIL"));
        assertEquals("DELETED", FakeTargetDomain.transition("FAILED", "REQUEST_DELETE"));
    }

    @Test
    void givenStoppedOrTerminalWhenRestartTriedThenAlwaysRejected() {
        // STOPPED 不可重启（详细设计 5.6）
        for (String event : Arrays.asList("ACTIVATE", "RESUME", "START")) {
            assertThrows(IllegalStateException.class,
                    () -> FakeTargetDomain.transition("STOPPED", event));
        }
        assertFalse(FakeTargetDomain.restartAllowed("STOPPED"));
        for (String state : Arrays.asList("EXPIRED", "DELETED", "FAILED")) {
            assertThrows(IllegalStateException.class,
                    () -> FakeTargetDomain.transition(state, "ACTIVATE"));
            assertTrue(FakeTargetDomain.isTerminal(state));
        }
        // 全矩阵非法边计数
        int rejected = 0;
        for (String state : FakeTargetDomain.STATES) {
            for (String event : Arrays.asList("ACTIVATE", "STOP", "EXPIRE", "REQUEST_DELETE",
                    "CONFIRM_DELETE", "FAIL")) {
                try {
                    FakeTargetDomain.transition(state, event);
                } catch (IllegalStateException expected) {
                    rejected++;
                }
            }
        }
        assertEquals(FakeTargetDomain.STATES.size() * 6 - 11, rejected,
                "除 10 条合法边外全部拒绝");
    }

    @Test
    void givenMotionWhenAdvancedEverySecondThenPositionFollowsHeadingAndSpeed() {
        // 真航向 090（正东）、600 kt：每秒 1/6 NM，纬度不变
        double[] east = FakeTargetDomain.projectPosition(23.0, 113.0, 90.0, 600.0, 60.0);
        assertEquals(23.0, east[0], 1e-9);
        assertEquals(113.0 + 10.0 / 60.0 / Math.cos(Math.toRadians(23.0)), east[1], 0.001,
                "60 秒东移 10 NM（含纬度收敛因子）");

        // 真航向 000（正北）、360 kt：每秒 0.1 NM
        double[] north = FakeTargetDomain.projectPosition(23.0, 113.0, 0.0, 360.0, 60.0);
        assertEquals(23.0 + 6.0 / 60.0, north[0], 1e-9, "60 秒北移 6 NM");
        assertEquals(113.0, north[1], 1e-9);

        // 逐步推进与整体投影一致（每秒一步）
        double lat = 23.0;
        double lon = 113.0;
        for (int i = 0; i < 30; i++) {
            double[] next = FakeTargetDomain.advanceOneSecond(lat, lon, 45.0, 720.0, i);
            lat = next[0];
            lon = next[1];
        }
        double[] projected = FakeTargetDomain.projectPosition(23.0, 113.0, 45.0, 720.0, 30.0);
        assertEquals(projected[0], lat, 1e-9);
        assertEquals(projected[1], lon, 1e-9);
    }

    @Test
    void givenLongitudeWrapWhenCrossingDatelineThenWraps() {
        // 东向穿越 180°：回绕到 -180 侧
        double[] wrapped = FakeTargetDomain.projectPosition(0.0, 179.999, 90.0, 3600.0, 10.0);
        assertTrue(wrapped[1] < 179.0, "应回绕：实际 " + wrapped[1]);
        assertTrue(wrapped[1] > -180.0);
        // 跨 360° 方向
        double[] westWrapped = FakeTargetDomain.projectPosition(0.0, -179.999, 270.0,
                3600.0, 10.0);
        assertTrue(westWrapped[1] > -179.0, "西向回绕：实际 " + westWrapped[1]);
    }

    @Test
    void givenExpiryRulesWhenCheckedThenKindSpecificActions() {
        assertEquals(Arrays.asList("EXPIRE", "REMOVE_FROM_DISPLAY"),
                FakeTargetDomain.expiryActionFor("RADAR_SYNTHETIC"),
                "合成目标到期立即移除、历史保留");
        assertEquals(Arrays.asList("REQUEST_DELETE", "AWAIT_ADAPTER_CONFIRM"),
                FakeTargetDomain.expiryActionFor("SIMULATED_AIRCRAFT"),
                "假航空器到期走受审计删除");
        assertThrows(IllegalArgumentException.class,
                () -> FakeTargetDomain.expiryActionFor("WEATHER"));
    }

    @Test
    void givenInstructionPermissionsWhenCheckedThenSyntheticRejectsNormalCommands() {
        assertFalse(FakeTargetDomain.acceptsFlightInstructions("RADAR_SYNTHETIC"),
                "合成目标不接受普通飞行控制");
        assertTrue(FakeTargetDomain.acceptsFlightInstructions("SIMULATED_AIRCRAFT"),
                "假航空器完整参与飞行控制");
    }
}
