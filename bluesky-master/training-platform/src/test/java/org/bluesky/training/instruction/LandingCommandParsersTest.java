package org.bluesky.training.instruction;

import org.bluesky.training.common.V2DomainException;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** P13：TAKEOFF/ILS/MISSED 解析、前置与接管策略（详细设计 2.2 §7.4/§7.5/§6.2）。 */
class LandingCommandParsersTest {

    private static final List<String> RUNWAYS = Arrays.asList("02L", "20R");
    private static final List<String> ILS_RUNWAYS = Arrays.asList("ZGGG02L");

    // ---------------------------------------------------------------- TAKEOFF

    @Test
    void givenTakeoffWhenParsedThenRunwayTimeLevelAndChannels() {
        Map<String, Object> immediate = LandingCommandParsers.parseTakeoff(
                "TAKEOFF 02L LEVEL 9000", RUNWAYS, 600);
        assertEquals("02L", immediate.get("runway"));
        assertEquals(9000.0, immediate.get("targetAltitudeFtMsl"));
        assertEquals(600.0, immediate.get("scheduledTimeSeconds"), "省略 AT 立即起飞");
        assertEquals(Arrays.asList("LATERAL", "VERTICAL", "SPEED"),
                immediate.get("affectedChannels"));

        Map<String, Object> scheduled = LandingCommandParsers.parseTakeoff(
                "TAKEOFF 02L AT D0T09:30:00 LEVEL FL120", RUNWAYS, 600);
        assertEquals(34200.0, scheduled.get("scheduledTimeSeconds"));
        assertEquals(12000.0, scheduled.get("targetAltitudeFtMsl"));

        // 阶段序列固定（详细设计 7.4）
        assertEquals(Arrays.asList("SCHEDULED", "LINE_UP", "TAKEOFF_ROLL", "ROTATION",
                "INITIAL_CLIMB", "COMPLETED"), immediate.get("phases"));
    }

    @Test
    void givenTakeoffPreconditionsWhenViolatedThenRejected() {
        // 跑道不属于起飞机场
        V2DomainException wrongRunway = assertThrows(V2DomainException.class,
                () -> LandingCommandParsers.parseTakeoff("TAKEOFF 36C LEVEL 9000",
                        RUNWAYS, 600));
        assertEquals(422, wrongRunway.httpStatus());
        assertEquals("REFERENCE_NOT_FOUND", wrongRunway.code());

        // 时刻早于当前仿真时间（复用 P12 时刻规则）
        assertThrows(V2DomainException.class, () -> LandingCommandParsers.parseTakeoff(
                "TAKEOFF 02L AT D0T09:30:00 LEVEL 9000", RUNWAYS, 99999));

        // 缺 LEVEL / 高度非法
        assertThrows(V2DomainException.class, () -> LandingCommandParsers.parseTakeoff(
                "TAKEOFF 02L", RUNWAYS, 600));
        assertThrows(V2DomainException.class, () -> LandingCommandParsers.parseTakeoff(
                "TAKEOFF 02L LEVEL 70000", RUNWAYS, 600));
        assertThrows(V2DomainException.class, () -> LandingCommandParsers.parseTakeoff(
                "TAKEOFF 9X LEVEL 9000", RUNWAYS, 600));

        // 阶段前置：仅 PRE_DEPARTURE
        LandingCommandParsers.validateTakeoffPhase("PRE_DEPARTURE");
        for (String phase : Arrays.asList("CRUISE", "INITIAL_CLIMB", "LANDED")) {
            V2DomainException failure = assertThrows(V2DomainException.class,
                    () -> LandingCommandParsers.validateTakeoffPhase(phase));
            assertEquals("PROCEDURE_STATE_INVALID", failure.code());
        }
    }

    // ---------------------------------------------------------------- ILS

    @Test
    void givenIlsWhenParsedThenAirportRunwayDirectionHeading() {
        Map<String, Object> full = LandingCommandParsers.parseIls(
                "ILS ZGGG 02L L 350", ILS_RUNWAYS, "ZBAA");
        assertEquals("ZGGG", full.get("airportCode"));
        assertEquals("02L", full.get("runway"));
        assertEquals("L", full.get("turnDirection"));
        assertEquals(350, full.get("interceptMagneticHeadingDeg"));
        assertEquals(Arrays.asList("LATERAL", "VERTICAL"), full.get("affectedChannels"));

        // 省略机场 → 计划落地机场
        Map<String, Object> shortForm = LandingCommandParsers.parseIls(
                "ILS 20R R 020", Collections.emptyList(), "ZBAA");
        assertEquals("ZBAA", shortForm.get("airportCode"));

        // ILS 跑道不可用（包线字段缺失）→ 422（详细设计 7.5）
        V2DomainException unavailable = assertThrows(V2DomainException.class,
                () -> LandingCommandParsers.parseIls("ILS ZGGG 20R L 020",
                        ILS_RUNWAYS, null));
        assertEquals("REFERENCE_NOT_FOUND", unavailable.code());

        assertThrows(V2DomainException.class, () -> LandingCommandParsers.parseIls(
                "ILS ZGGG 02L X 350", ILS_RUNWAYS, null));
        assertThrows(V2DomainException.class, () -> LandingCommandParsers.parseIls(
                "ILS ZGGG 02L L 361", ILS_RUNWAYS, null));
        assertThrows(V2DomainException.class, () -> LandingCommandParsers.parseIls(
                "ILS 02L L 350", ILS_RUNWAYS, null),
                "省略机场且无计划落地机场时拒绝");
    }

    // ---------------------------------------------------------------- MISSED

    @Test
    void givenMissedWhenBuiltThenTerminatesIlsAndActivatesProcedure() {
        Map<String, Object> missed = LandingCommandParsers.buildMissed("ZGGG21X");
        assertEquals("ZGGG21X", missed.get("missedProcedureId"));
        assertEquals(Boolean.TRUE, missed.get("terminatesIls"),
                "MISSED 必须原子终止 ILS（详细设计 7.3）");
        assertEquals(Arrays.asList("MISSED_INITIATED", "CLIMBING", "PROCEDURE_TRACK",
                "COMPLETED"), missed.get("phases"));
    }

    // ---------------------------------------------------------------- 接管策略

    @Test
    void givenIlsOverrideWhenAnyChannelTakenThenTerminateAll() {
        // 任一通道被接管 → 整体终止（详细设计 6.2 接管表）
        for (String channel : Arrays.asList("LATERAL", "VERTICAL")) {
            Map<String, Object> outcome = LandingCommandParsers.evaluateOverride("ILS",
                    Arrays.asList(channel), 3000);
            assertEquals("TERMINATE_ALL", outcome.get("action"));
            assertEquals("REPLACED", outcome.get("parentState"));
            assertEquals("ILS_TERMINATED_BY_OVERRIDE", outcome.get("reasonCode"));
            assertEquals(Arrays.asList("LATERAL", "VERTICAL"),
                    outcome.get("channelsToTerminate"));
        }
        // MISSED 默认与 ILS 相同整体终止
        Map<String, Object> missedOutcome = LandingCommandParsers.evaluateOverride("MISSED",
                Arrays.asList("LATERAL"), 2000);
        assertEquals("TERMINATE_ALL", missedOutcome.get("action"));
    }

    @Test
    void givenTakeoffOverrideWhenEvaluatedThen400ftRuleAndPartialFlag() {
        // 400 ft AGL 前：横向被接管 → 部分接管，父项继续 EXECUTING
        Map<String, Object> before400 = LandingCommandParsers.evaluateOverride("TAKEOFF",
                Arrays.asList("LATERAL"), 300);
        assertEquals("PARTIAL", before400.get("action"));
        assertEquals("EXECUTING", before400.get("parentState"));
        assertEquals("PARTIALLY_OVERRIDDEN", before400.get("reasonCode"));

        // 400 ft 后横向/速度已释放，垂直被接管 → 父项 REPLACED（唯一必需子项被接管）
        Map<String, Object> after400 = LandingCommandParsers.evaluateOverride("TAKEOFF",
                Arrays.asList("VERTICAL"), 1200);
        assertEquals("REPLACED", after400.get("parentState"));

        // 无接管 → 继续
        Map<String, Object> none = LandingCommandParsers.evaluateOverride("TAKEOFF",
                Collections.emptyList(), 300);
        assertEquals("EXECUTING", none.get("parentState"));
    }
}
