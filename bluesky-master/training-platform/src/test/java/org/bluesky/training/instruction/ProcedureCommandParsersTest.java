package org.bluesky.training.instruction;

import org.bluesky.training.common.V2DomainException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** P12：SID/STAR/航段约束/复飞解析（详细设计 2.2 §7.3、4.2.5）。 */
class ProcedureCommandParsersTest {

    private static final List<String> UNFLOWN = Arrays.asList("LMN", "P47", "ZBAA");

    @Test
    void givenSidStarWhenParsedThenNameShapeAndSnapshotLookup() {
        Map<String, Object> plain = ProcedureCommandParsers.parseSidStar(
                "SIDSTAR ZGGG01A", Arrays.asList("ZGGG01A", "ZBAA21B"));
        assertEquals("ZGGG01A", plain.get("procedureId"));
        assertNull(plain.get("reportPoint"));

        Map<String, Object> withReport = ProcedureCommandParsers.parseSidStar(
                "SIDSTAR ZBAA21B P47", Arrays.asList("ZGGG01A", "ZBAA21B"));
        assertEquals("P47", withReport.get("reportPoint"));

        // 未知程序：REFERENCE_NOT_FOUND 422
        V2DomainException unknown = assertThrows(V2DomainException.class,
                () -> ProcedureCommandParsers.parseSidStar("SIDSTAR NOPE99",
                        Arrays.asList("ZGGG01A")));
        assertEquals(422, unknown.httpStatus());
        assertEquals("REFERENCE_NOT_FOUND", unknown.code());

        // 形态错误
        assertThrows(V2DomainException.class, () -> ProcedureCommandParsers.parseSidStar("SIDSTAR",
                java.util.Collections.emptyList()));
        assertThrows(V2DomainException.class,
                () -> ProcedureCommandParsers.parseSidStar("SIDSTAR 太长程序名",
                        java.util.Collections.emptyList()));
    }

    @Test
    void givenLegLevelWhenParsedThenFuturePointAltitudeAndConflictKey() {
        Map<String, Object> level = ProcedureCommandParsers.parseLegLevel(
                "P_LEVEL P47 9000M", UNFLOWN);
        assertEquals("P47", level.get("legPoint"));
        assertEquals(29527.56, (double) level.get("altitudeFtMsl"), 0.1);
        assertEquals("BUSINESS_FIELD:LEG:{legId}:LEVEL", level.get("conflictKeyTemplate"),
                "P_LEVEL 按航段独立冲突键");

        // FL 形式与包线
        Map<String, Object> fl = ProcedureCommandParsers.parseLegLevel(
                "P_LEVEL LMN FL150", UNFLOWN);
        assertEquals(15000.0, fl.get("altitudeFtMsl"));
        assertThrows(V2DomainException.class,
                () -> ProcedureCommandParsers.parseLegLevel("P_LEVEL LMN 70000FT", UNFLOWN));

        // 只能作用于未来航路点
        V2DomainException flown = assertThrows(V2DomainException.class,
                () -> ProcedureCommandParsers.parseLegLevel("P_LEVEL ZGGG 9000",
                        UNFLOWN));
        assertEquals("INVALID_INSTRUCTION", flown.code());
    }

    @Test
    void givenLegTimeWhenParsedThenThreeFormatsAndNoDayRollover() {
        // 绝对日形式：D0T09:30:00 = 34200s；当前 34100s 合法
        Map<String, Object> absolute = ProcedureCommandParsers.parseLegTime(
                "P_TIME P47 D0T09:30:00", UNFLOWN, 34100);
        assertEquals("ABSOLUTE_DAY", absolute.get("format"));
        assertEquals(34200.0, absolute.get("targetTimeSeconds"));

        // 相对形式：T+600
        Map<String, Object> relative = ProcedureCommandParsers.parseLegTime(
                "P_TIME P47 T+600", UNFLOWN, 34100);
        assertEquals("RELATIVE", relative.get("format"));
        assertEquals(34700.0, relative.get("targetTimeSeconds"));

        // HHMM：只解释为当前仿真日且必须晚于当前时刻
        Map<String, Object> hhmm = ProcedureCommandParsers.parseLegTime(
                "P_TIME LMN 1430", UNFLOWN, 50000); // 1430=52200s > 50000
        assertEquals("HHMM_SAME_DAY", hhmm.get("format"));
        assertEquals(52200.0, hhmm.get("targetTimeSeconds"));

        // 1430（52200s）早于当前 53000s → 拒绝且不跨日
        V2DomainException past = assertThrows(V2DomainException.class,
                () -> ProcedureCommandParsers.parseLegTime("P_TIME LMN 1430", UNFLOWN, 53000));
        assertEquals("INVALID_INSTRUCTION", past.code());
        assertEquals(true, past.getMessage().contains("不隐式跨日"));

        // 绝对形式过去时刻拒绝
        assertThrows(V2DomainException.class, () -> ProcedureCommandParsers.parseLegTime(
                "P_TIME P47 D0T09:30:00", UNFLOWN, 99999));
        // 非法时刻
        assertThrows(V2DomainException.class, () -> ProcedureCommandParsers.parseLegTime(
                "P_TIME P47 25:99", UNFLOWN, 0));
        assertThrows(V2DomainException.class, () -> ProcedureCommandParsers.parseLegTime(
                "P_TIME P47 someday", UNFLOWN, 0));
        // 冲突键模板
        assertEquals("BUSINESS_FIELD:LEG:{legId}:TIME",
                relative.get("conflictKeyTemplate"));
    }

    @Test
    void givenMissedWhenParsedThenProcedureLookupAndPhaseGate() {
        Map<String, Object> auto = ProcedureCommandParsers.parseMissed("MISSED",
                Arrays.asList("ZGGG21X"));
        assertNull(auto.get("missedProcedureId"), "省略程序名按跑道自动匹配");
        assertEquals("P13", auto.get("execution"), "执行阶段校验归 P13");

        Map<String, Object> named = ProcedureCommandParsers.parseMissed("MISSED ZGGG21X",
                Arrays.asList("ZGGG21X"));
        assertEquals("ZGGG21X", named.get("missedProcedureId"));

        V2DomainException unknown = assertThrows(V2DomainException.class,
                () -> ProcedureCommandParsers.parseMissed("MISSED NOPE",
                        Arrays.asList("ZGGG21X")));
        assertEquals("REFERENCE_NOT_FOUND", unknown.code());

        // 阶段前置：仅 APPROACH/FINAL/FLARE；ROLLOUT/CRUISE 拒绝（详细设计 2.2 修订）
        ProcedureCommandParsers.validateMissedPhase("APPROACH");
        ProcedureCommandParsers.validateMissedPhase("FINAL");
        ProcedureCommandParsers.validateMissedPhase("FLARE");
        for (String phase : Arrays.asList("ROLLOUT", "CRUISE", "LANDED", "PRE_DEPARTURE")) {
            V2DomainException failure = assertThrows(V2DomainException.class,
                    () -> ProcedureCommandParsers.validateMissedPhase(phase));
            assertEquals(409, failure.httpStatus());
            assertEquals("PROCEDURE_STATE_INVALID", failure.code());
        }
    }
}
