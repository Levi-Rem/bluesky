package org.bluesky.training.instruction;

import org.bluesky.training.common.V2DomainException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P12：SID/STAR/航段约束/复飞程序解析（详细设计 2.2 §7.3）。
 * 解析结果保存稳定 procedureId/legId，不只保存显示代码；
 * 过去时间与性能不可达在解析期拒绝，HHMM 不隐式跨日。
 */
public class ProcedureCommandParsers {

    // ---------------------------------------------------------------- SID/STAR

    /**
     * SIDSTAR <程序名> [报告点]：按机场、跑道、飞行方向匹配；
     * 结果保存稳定 procedureId（快照解析负责消歧，此处校验形态）。
     */
    public static Map<String, Object> parseSidStar(String text, List<String> knownProcedureIds) {
        List<String> tokens = PerformanceGuard.tokenize(text);
        if (tokens.size() < 2 || tokens.size() > 3
                || !"SIDSTAR".equalsIgnoreCase(tokens.get(0))) {
            throw invalid("SIDSTAR <程序名> [报告点]", text);
        }
        String procedureName = tokens.get(1).toUpperCase();
        if (!procedureName.matches("[A-Z0-9]{2,10}")) {
            throw new V2DomainException("INVALID_INSTRUCTION", 400,
                    "程序名非法: " + tokens.get(1), Arrays.asList("procedureName"));
        }
        if (knownProcedureIds != null && !knownProcedureIds.isEmpty()
                && !knownProcedureIds.contains(procedureName)) {
            throw new V2DomainException("REFERENCE_NOT_FOUND", 422,
                    "快照中不存在该 SID/STAR 程序: " + procedureName,
                    Arrays.asList("procedureName"));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("procedureId", procedureName); // 快照稳定 ID（消歧后由服务层覆写）
        result.put("reportPoint", tokens.size() == 3
                ? waypointOf(tokens.get(2)) : null);
        return result;
    }

    // ---------------------------------------------------------------- P_LEVEL

    /** P_LEVEL <未来航路点> <高度>：写入航段高度约束；冲突键按航段独立。 */
    public static Map<String, Object> parseLegLevel(String text, List<String> unfuelledLegPoints) {
        List<String> tokens = PerformanceGuard.tokenize(text);
        if (tokens.size() != 3 || !"P_LEVEL".equalsIgnoreCase(tokens.get(0))) {
            throw invalid("P_LEVEL <未来航路点> <高度>", text);
        }
        String legPoint = requireUnfuelledLeg(tokens.get(1), unfuelledLegPoints);
        double altitudeFtMsl = altitudeOf(tokens.get(2));
        PerformanceGuard.validateAltitude(altitudeFtMsl);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("legPoint", legPoint);
        result.put("altitudeFtMsl", altitudeFtMsl);
        result.put("conflictKeyTemplate", "BUSINESS_FIELD:LEG:{legId}:LEVEL");
        return result;
    }

    // ---------------------------------------------------------------- P_TIME

    /**
     * P_TIME <未来航路点> <D日序THH:MM:SS|T+秒|HHMM>
     * （详细设计 4.2.5/7.3：HHMM 只解释为当前仿真日且必须晚于当前时刻，不跨日）。
     */
    public static Map<String, Object> parseLegTime(String text, List<String> unfuelledLegPoints,
                                                   double currentSimulationSeconds) {
        List<String> tokens = PerformanceGuard.tokenize(text);
        if (tokens.size() != 3 || !"P_TIME".equalsIgnoreCase(tokens.get(0))) {
            throw invalid("P_TIME <未来航路点> <时刻>", text);
        }
        String legPoint = requireUnfuelledLeg(tokens.get(1), unfuelledLegPoints);
        Map<String, Object> parsed = parseSimulationTime(tokens.get(2), currentSimulationSeconds);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("legPoint", legPoint);
        result.putAll(parsed);
        result.put("conflictKeyTemplate", "BUSINESS_FIELD:LEG:{legId}:TIME");
        return result;
    }

    /** 三种时刻形式：D0T09:30:00 / T+600 / 1430（HHMM 不隐式跨日）。 */
    static Map<String, Object> parseSimulationTime(String token, double currentSimulationSeconds) {
        String value = token == null ? "" : token.trim().toUpperCase();
        Map<String, Object> result = new LinkedHashMap<>();
        if (value.matches("D\\d+T\\d{2}:\\d{2}:\\d{2}")) {
            String[] parts = value.split("T");
            int day = Integer.parseInt(parts[0].substring(1));
            String[] hms = parts[1].split(":");
            if(Integer.parseInt(hms[0])>23 || Integer.parseInt(hms[1])>59 || Integer.parseInt(hms[2])>59)throw invalid("D日序THH:MM:SS",value);
            double seconds = day * 86400.0
                    + Integer.parseInt(hms[0]) * 3600
                    + Integer.parseInt(hms[1]) * 60
                    + Integer.parseInt(hms[2]);
            requireFuture(seconds, currentSimulationSeconds, value);
            result.put("format", "ABSOLUTE_DAY");
            result.put("targetTimeSeconds", seconds);
            return result;
        }
        if (value.matches("T\\+\\d+")) {
            double seconds = currentSimulationSeconds
                    + Integer.parseInt(value.substring(2));
            requireFuture(seconds,currentSimulationSeconds,value);
            result.put("format", "RELATIVE");
            result.put("targetTimeSeconds", seconds);
            return result;
        }
        if (value.matches("\\d{4}")) {
            int hours = Integer.parseInt(value.substring(0, 2));
            int minutes = Integer.parseInt(value.substring(2));
            if (hours > 23 || minutes > 59) {
                throw new V2DomainException("INVALID_INSTRUCTION", 400,
                        "HHMM 时刻非法: " + value, Arrays.asList("targetTime"));
            }
            double seconds = Math.floor(currentSimulationSeconds/86400)*86400+hours * 3600 + minutes * 60;
            // 只解释为当前仿真日（详细设计 4.2.5：不自动滚入下一日）
            if (seconds <= currentSimulationSeconds) {
                throw new V2DomainException("INVALID_INSTRUCTION", 400,
                        "HHMM 必须晚于当前仿真时刻 " + currentSimulationSeconds
                                + "s，不隐式跨日: " + value, Arrays.asList("targetTime"));
            }
            result.put("format", "HHMM_SAME_DAY");
            result.put("targetTimeSeconds", seconds);
            return result;
        }
        throw new V2DomainException("INVALID_INSTRUCTION", 400,
                "时刻必须是 D日序THH:MM:SS / T+秒 / HHMM: " + token,
                Arrays.asList("targetTime"));
    }

    // ---------------------------------------------------------------- MISSED

    /** MISSED：只允许解析，执行阶段校验归 P13（详细设计 7.3）。 */
    public static Map<String, Object> parseMissed(String text, List<String> missedProcedureIds) {
        List<String> tokens = PerformanceGuard.tokenize(text);
        if (tokens.size() > 2 || !tokens.isEmpty() && !"MISSED".equalsIgnoreCase(tokens.get(0))) {
            throw invalid("MISSED [程序名]", text);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("execution", "P13");
        if (tokens.size() == 2) {
            String procedureName = tokens.get(1).toUpperCase();
            if (missedProcedureIds != null && !missedProcedureIds.isEmpty()
                    && !missedProcedureIds.contains(procedureName)) {
                throw new V2DomainException("REFERENCE_NOT_FOUND", 422,
                        "快照中不存在该复飞程序: " + procedureName, Arrays.asList("procedureName"));
            }
            result.put("missedProcedureId", procedureName);
        } else {
            result.put("missedProcedureId", null); // 按跑道自动匹配
        }
        return result;
    }

    /** 复飞阶段前置：仅 APPROACH/FINAL/FLARE（详细设计 2.2 §7.3，2.2 修订）。 */
    public static void validateMissedPhase(String flightPhase) {
        if (!Arrays.asList("APPROACH", "FINAL", "FLARE").contains(flightPhase)) {
            throw new V2DomainException("PROCEDURE_STATE_INVALID", 409,
                    "MISSED 只允许在 APPROACH/FINAL/FLARE 阶段执行（ROLLOUT 前）: "
                            + flightPhase, Arrays.asList("flightPhase"));
        }
    }

    // ---------------------------------------------------------------- helpers

    private static String requireUnfuelledLeg(String token, List<String> unfuelledLegPoints) {
        String point = waypointOf(token);
        if (unfuelledLegPoints != null && !unfuelledLegPoints.isEmpty()
                && !unfuelledLegPoints.contains(point)) {
            throw new V2DomainException("INVALID_INSTRUCTION", 400,
                    "约束只能作用于未来航路点: " + point, Arrays.asList("legPoint"));
        }
        return point;
    }

    private static double altitudeOf(String token) {
        String value = token.toUpperCase();
        double altitude;
        if (value.startsWith("FL")) {
            altitude = PerformanceGuard.parseDecimal(value.substring(2), "altitudeFtMsl") * 100.0;
        } else if (value.endsWith("M")) {
            altitude = org.bluesky.training.reference.UnitConverter.metersToFeet(
                    PerformanceGuard.parseDecimal(value.substring(0, value.length() - 1),
                            "altitudeFtMsl"));
        } else if (value.endsWith("FT")) {
            altitude = PerformanceGuard.parseDecimal(value.substring(0, value.length() - 2),
                    "altitudeFtMsl");
        } else if (value.matches("\\d+")) {
            altitude = Double.parseDouble(value);
        } else {
            throw new V2DomainException("INVALID_INSTRUCTION", 400,
                    "P_LEVEL 高度非法: " + token, Arrays.asList("altitudeFtMsl"));
        }
        return altitude;
    }

    private static void requireFuture(double seconds, double current, String token) {
        if (seconds <= current) {
            throw new V2DomainException("INVALID_INSTRUCTION", 400,
                    "目标时刻必须晚于当前仿真时刻 " + current + "s: " + token,
                    Arrays.asList("targetTime"));
        }
    }

    static String waypointOf(String token) {
        String value = token == null ? "" : token.trim().toUpperCase();
        if (!value.matches("[A-Z0-9]{2,8}")) {
            throw new V2DomainException("INVALID_INSTRUCTION", 400,
                    "航路点非法: " + token, Arrays.asList("point"));
        }
        return value;
    }

    private static V2DomainException invalid(String syntax, String text) {
        return new V2DomainException("INVALID_INSTRUCTION", 400,
                "语法应为 " + syntax + ": " + text, Arrays.asList("text"));
    }
}
