package org.bluesky.training.instruction;

import org.bluesky.training.common.V2DomainException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P13：TAKEOFF/ILS/MISSED 解析与前置校验（详细设计 2.2 §7.4/§7.5）。
 * 语法：TAKEOFF <跑道> [AT <时刻>] LEVEL <目标高度>；ILS <机场> <跑道> L|R <截获航向>。
 */
public class LandingCommandParsers {

    // ---------------------------------------------------------------- TAKEOFF

    /**
     * TAKEOFF 复合指令：锁定横向/垂直/速度子通道（详细设计 7.4）。
     * 前置：PRE_DEPARTURE、跑道属于起飞机场、时刻不早于当前仿真时间。
     */
    public static Map<String, Object> parseTakeoff(String text, List<String> availableRunways,
                                                   double currentSimulationSeconds) {
        List<String> tokens = PerformanceGuard.tokenize(text);
        if (tokens.isEmpty() || !"TAKEOFF".equalsIgnoreCase(tokens.get(0))) {
            throw invalid("TAKEOFF <跑道> [AT <时刻>] LEVEL <目标高度>", text);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("affectedChannels", Arrays.asList("LATERAL", "VERTICAL", "SPEED"));

        int index = 1;
        if (tokens.size() <= index) {
            throw new V2DomainException("INVALID_INSTRUCTION", 400,
                    "TAKEOFF 缺少跑道", Arrays.asList("runway"));
        }
        String runway = tokens.get(index).toUpperCase();
        if (!runway.matches("\\d{2}[LRC]?")) {
            throw new V2DomainException("INVALID_INSTRUCTION", 400,
                    "跑道号非法（如 02L）: " + runway, Arrays.asList("runway"));
        }
        if (availableRunways != null && !availableRunways.isEmpty()
                && !availableRunways.contains(runway)) {
            throw new V2DomainException("REFERENCE_NOT_FOUND", 422,
                    "跑道不属于起飞机场或不可用: " + runway, Arrays.asList("runway"));
        }
        result.put("runway", runway);
        index++;

        if (tokens.size() > index + 1 && "AT".equalsIgnoreCase(tokens.get(index))) {
            Map<String, Object> time = ProcedureCommandParsers.parseSimulationTime(
                    tokens.get(index + 1), currentSimulationSeconds);
            result.put("scheduledTimeFormat", time.get("format"));
            result.put("scheduledTimeSeconds", time.get("targetTimeSeconds"));
            index += 2;
        } else {
            result.put("scheduledTimeSeconds", currentSimulationSeconds); // 立即起飞
        }

        if (tokens.size() <= index + 1
                || !"LEVEL".equalsIgnoreCase(tokens.get(index))) {
            throw new V2DomainException("INVALID_INSTRUCTION", 400,
                    "TAKEOFF 必须以 LEVEL <目标高度> 结尾", Arrays.asList("targetAltitudeFtMsl"));
        }
        double altitude = altitudeOf(tokens.get(index + 1));
        PerformanceGuard.validateAltitude(altitude);
        result.put("targetAltitudeFtMsl", altitude);
        result.put("phases", Arrays.asList("SCHEDULED", "LINE_UP", "TAKEOFF_ROLL",
                "ROTATION", "INITIAL_CLIMB", "COMPLETED"));
        return result;
    }

    /** TAKEOFF 阶段前置：航空器必须 PRE_DEPARTURE（详细设计 7.4）。 */
    public static void validateTakeoffPhase(String flightPhase) {
        if (!"PRE_DEPARTURE".equals(flightPhase)) {
            throw new V2DomainException("PROCEDURE_STATE_INVALID", 409,
                    "TAKEOFF 只允许在 PRE_DEPARTURE 阶段执行: " + flightPhase,
                    Arrays.asList("flightPhase"));
        }
    }

    // ---------------------------------------------------------------- ILS

    /** ILS <机场> <跑道> L|R <截获航向>；机场可省略用计划落地机场（详细设计 7.5）。 */
    public static Map<String, Object> parseIls(String text, List<String> ilsCapableRunways,
                                               String defaultDestination) {
        List<String> tokens = PerformanceGuard.tokenize(text);
        if (tokens.isEmpty() || !"ILS".equalsIgnoreCase(tokens.get(0)) || tokens.size() < 4) {
            throw invalid("ILS [机场] <跑道> L|R <截获航向>", text);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("affectedChannels", Arrays.asList("LATERAL", "VERTICAL"));

        // 形态一：ILS <机场> <跑道> L|R <航向>（5 段）；形态二：省略机场（4 段）
        int index = 1;
        String airport;
        String runwayToken;
        if (tokens.size() == 5) {
            airport = tokens.get(index).toUpperCase();
            if (!airport.matches("[A-Z]{4}")) {
                throw new V2DomainException("INVALID_INSTRUCTION", 400,
                        "机场代码必须是四位字母: " + airport, Arrays.asList("airportCode"));
            }
            index++;
        } else {
            airport = defaultDestination == null ? null : defaultDestination.toUpperCase();
            if (airport == null) {
                throw new V2DomainException("INVALID_INSTRUCTION", 400,
                        "省略机场时必须有飞行计划落地机场", Arrays.asList("airportCode"));
            }
        }
        runwayToken = tokens.get(index).toUpperCase();
        index++;

        String runwayKey = (airport == null ? "" : airport) + runwayToken;
        if (ilsCapableRunways != null && !ilsCapableRunways.isEmpty()
                && !ilsCapableRunways.contains(runwayKey)) {
            throw new V2DomainException("REFERENCE_NOT_FOUND", 422,
                    "该跑道 ILS 不可用（包线字段缺失或台站不存在）: " + runwayKey,
                    Arrays.asList("runway"));
        }
        result.put("airportCode", airport);
        result.put("runway", runwayToken);

        String direction = tokens.get(index).toUpperCase();
        if (!"L".equals(direction) && !"R".equals(direction)) {
            throw new V2DomainException("INVALID_INSTRUCTION", 400,
                    "ILS 转向必须是 L/R", Arrays.asList("turnDirection"));
        }
        result.put("turnDirection", direction);
        index++;

        result.put("interceptMagneticHeadingDeg",
                BasicCommandParsers.headingOf(tokens.get(index)));
        result.put("phases", Arrays.asList("INTERCEPTING_LOCALIZER", "LOCALIZER_CAPTURED",
                "GLIDESLOPE_CAPTURED", "FINAL_APPROACH", "FLARE", "ROLLOUT", "LANDED"));
        return result;
    }

    // ---------------------------------------------------------------- MISSED

    /** MISSED 触发：终止 ILS 并激活复飞程序（程序解析复用 P12）。 */
    public static Map<String, Object> buildMissed(String missedProcedureId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("affectedChannels", Arrays.asList("LATERAL", "VERTICAL"));
        result.put("missedProcedureId", missedProcedureId);
        result.put("terminatesIls", Boolean.TRUE);
        result.put("phases", Arrays.asList("MISSED_INITIATED", "CLIMBING",
                "PROCEDURE_TRACK", "COMPLETED"));
        return result;
    }

    // ---------------------------------------------------------------- 接管策略

    /**
     * ILS 任一通道被接管即整体终止（详细设计 6.2 接管策略表）；
     * TAKEOFF 400 ft AGL 前允许显式 REPLACE，被接管子项 REPLACED，父项保持 EXECUTING。
     */
    public static Map<String, Object> evaluateOverride(String parentType,
                                                       List<String> overriddenChannels,
                                                       double altitudeFtAgl) {
        List<String> over = overriddenChannels == null
                ? new ArrayList<>() : overriddenChannels;
        Map<String, Object> result = new LinkedHashMap<>();
        switch (parentType == null ? "" : parentType) {
            case "ILS":
            case "MISSED":
                result.put("action", "TERMINATE_ALL");
                result.put("parentState", "REPLACED");
                result.put("reasonCode", "ILS_TERMINATED_BY_OVERRIDE");
                result.put("channelsToTerminate", Arrays.asList("LATERAL", "VERTICAL"));
                return result;
            case "TAKEOFF":
                if (altitudeFtAgl >= 400) {
                    // 400 ft 后横向/速度子项已释放，仅垂直可被接管
                    if (over.contains("VERTICAL")) {
                        result.put("action", "PARTIAL");
                        result.put("parentState", "REPLACED");
                        result.put("reasonCode", "PARTIALLY_OVERRIDDEN");
                        return result;
                    }
                }
                if (over.isEmpty()) {
                    result.put("action", "NONE");
                    result.put("parentState", "EXECUTING");
                    return result;
                }
                result.put("action", "PARTIAL");
                result.put("parentState", "EXECUTING");
                result.put("reasonCode", "PARTIALLY_OVERRIDDEN");
                return result;
            default:
                throw new V2DomainException("INVALID_INSTRUCTION", 400,
                        "非复合指令不适用整体接管策略: " + parentType, Arrays.asList("type"));
        }
    }

    // ---------------------------------------------------------------- helpers

    private static double altitudeOf(String token) {
        String value = token.toUpperCase();
        if (value.startsWith("FL")) {
            return PerformanceGuard.parseDecimal(value.substring(2), "targetAltitudeFtMsl")
                    * 100.0;
        }
        if (value.endsWith("FT")) {
            return PerformanceGuard.parseDecimal(value.substring(0, value.length() - 2),
                    "targetAltitudeFtMsl");
        }
        if (value.matches("\\d+")) {
            return Double.parseDouble(value);
        }
        throw new V2DomainException("INVALID_INSTRUCTION", 400,
                "TAKEOFF 目标高度非法: " + token, Arrays.asList("targetAltitudeFtMsl"));
    }

    private static V2DomainException invalid(String syntax, String text) {
        return new V2DomainException("INVALID_INSTRUCTION", 400,
                "语法应为 " + syntax + ": " + text, Arrays.asList("text"));
    }
}
