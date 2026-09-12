package org.bluesky.training.instruction;

import org.bluesky.training.common.V2DomainException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P11：航路与导航命令解析（详细设计 2.2 §7.2）。
 * 平台侧解析保证错误在下发前发生；退出锚点由 Adapter 保存（lateral_suspend_context）。
 */
public class NavigationCommandParsers {

    // ---------------------------------------------------------------- DCT

    /** DCT <命名点>：航路内点跳过前序；航路外点临时直飞（详细设计 7.2）。 */
    public static Map<String, Object> parseDirectTo(String text) {
        List<String> tokens = PerformanceGuard.tokenize(text);
        if (tokens.size() != 2 || !"DCT".equalsIgnoreCase(tokens.get(0))) {
            throw invalid("DCT <命名点>", text);
        }
        String point = waypointOf(tokens.get(1));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("targetPoint", point);
        result.put("captureExitAnchor", true);
        return result;
    }

    // ---------------------------------------------------------------- RTE

    /** RTE <完整未飞航路点序列>：原子替换全部未飞航路，最后一点必须是落地机场。 */
    public static Map<String, Object> parseRoute(String text, String destination) {
        List<String> tokens = PerformanceGuard.tokenize(text);
        if (tokens.size() < 2 || !"RTE".equalsIgnoreCase(tokens.get(0))) {
            throw invalid("RTE <航路点序列>", text);
        }
        List<String> points = new ArrayList<>();
        for (int i = 1; i < tokens.size(); i++) {
            points.add(waypointOf(tokens.get(i)));
        }
        if (destination != null) {
            String expected = destination.trim().toUpperCase();
            if (!points.get(points.size() - 1).equals(expected)) {
                throw new V2DomainException("INVALID_INSTRUCTION", 400,
                        "RTE 最后一点必须是当前落地机场 " + expected, Arrays.asList("route"));
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("route", points);
        result.put("replacementMode", "FULL_UNFLOWN");
        return result;
    }

    // ---------------------------------------------------------------- RESUME

    /** RESUME [原航路点]：省略时由 Adapter 自动选择前方截获角 ≤90° 的航段（详细设计 7.2）。 */
    public static Map<String, Object> parseResume(String text) {
        List<String> tokens = PerformanceGuard.tokenize(text);
        if (tokens.isEmpty() || !"RESUME".equalsIgnoreCase(tokens.get(0)) || tokens.size() > 2) {
            throw invalid("RESUME [原航路点]", text);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("resumePoint", tokens.size() == 2 && !"AUTO".equalsIgnoreCase(tokens.get(1)) ? waypointOf(tokens.get(1)) : null);
        result.put("mode", tokens.size() == 2 ? "EXPLICIT" : "AUTO");
        return result;
    }

    // ---------------------------------------------------------------- ORBIT

    /** ORBIT L|R [半径] / ORBIT <中心点> L|R [半径] / ORBIT EXIT；半径 1–20NM 省略 5NM。 */
    public static Map<String, Object> parseOrbit(String text) {
        List<String> tokens = PerformanceGuard.tokenize(text);
        if (tokens.isEmpty() || !"ORBIT".equalsIgnoreCase(tokens.get(0))) {
            throw invalid("ORBIT L|R [半径]", text);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        int index = 1;
        if (tokens.size() >= index + 1 && "EXIT".equalsIgnoreCase(tokens.get(index))) {
            result.put("action", "EXIT");
            return result;
        }
        // 中心点形式：ORBIT <中心点> L|R [半径]
        if (tokens.size() >= index + 2 && isDirection(tokens.get(index + 1))) {
            result.put("centerPoint", waypointOf(tokens.get(index)));
            index++;
        } else {
            result.put("centerPoint", null); // 省略中心 = 下发时位置（详细设计 7.2）
        }
        if (tokens.size() <= index || !isDirection(tokens.get(index))) {
            throw new V2DomainException("INVALID_INSTRUCTION", 400,
                    "ORBIT 必须指定 L/R 方向", Arrays.asList("turnDirection"));
        }
        result.put("turnDirection", tokens.get(index).toUpperCase());
        index++;
        if (tokens.size() > index) {
            result.put("radiusNm", radiusNm(tokens.get(index), 1, 20));
        } else {
            result.put("radiusNm", 5.0); // 省略半径 5 NM（详细设计 7.2）
        }
        result.put("action", "ENTER");
        return result;
    }

    // ---------------------------------------------------------------- HOLD

    /** HOLD <等待点> L|R <入航磁航向> <时长|距离> / HOLD <已发布程序名> / HOLD EXIT。 */
    public static Map<String, Object> parseHold(String text) {
        List<String> tokens = PerformanceGuard.tokenize(text);
        if (tokens.isEmpty() || !"HOLD".equalsIgnoreCase(tokens.get(0))) {
            throw invalid("HOLD", text);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        if (tokens.size() == 2 && "EXIT".equalsIgnoreCase(tokens.get(1))) {
            result.put("action", "EXIT");
            return result;
        }
        if (tokens.size() == 2) {
            result.put("action", "PUBLISHED_PROCEDURE");
            result.put("procedureName", tokens.get(1).toUpperCase());
            return result;
        }
        // 完整形式：等待点 方向 入航航向 时长/距离
        if (tokens.size() != 5) {
            throw new V2DomainException("INVALID_INSTRUCTION", 400,
                    "HOLD 语法：HOLD <等待点> L|R <入航磁航向> <时长秒|距离NM>",
                    Arrays.asList("text"));
        }
        result.put("action", "FIX_AND_LEG");
        result.put("fixPoint", waypointOf(tokens.get(1)));
        if (!isDirection(tokens.get(2))) {
            throw new V2DomainException("INVALID_INSTRUCTION", 400,
                    "HOLD 方向必须是 L/R", Arrays.asList("turnDirection"));
        }
        result.put("turnDirection", tokens.get(2).toUpperCase());
        result.put("inboundMagneticHeadingDeg",
                BasicCommandParsers.headingOf(tokens.get(3)));
        String last = tokens.get(4).toUpperCase();
        if (last.endsWith("MIN") || last.matches("\\d+")) {
            if (!last.matches("\\d+(MIN)?")) {
                throw legValueError(last);
            }
            int seconds = Integer.parseInt(last.replace("MIN", "")) * (last.endsWith("MIN") ? 60 : 1);
            if (seconds < 30 || seconds > 180) {
                throw new V2DomainException("INVALID_INSTRUCTION", 400,
                        "HOLD 时长范围 30–180 秒: " + seconds, Arrays.asList("legValue"));
            }
            result.put("legSeconds", seconds);
        } else if (last.endsWith("NM")) {
            double nm = radiusNm(last.substring(0, last.length() - 2), 2, 20);
            result.put("legNm", nm);
        } else {
            throw legValueError(last);
        }
        return result;
    }

    // ---------------------------------------------------------------- OFFSET

    /** OFFSET L|R <1–10NM> / OFFSET CLR（空参数与裸数别名已在 P10 归一化）。 */
    public static Map<String, Object> parseOffset(String text) {
        List<String> tokens = PerformanceGuard.tokenize(text);
        if (tokens.isEmpty() || !"OFFSET".equalsIgnoreCase(tokens.get(0))) {
            throw invalid("OFFSET L|R <NM>", text);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        if (tokens.size() == 2 && "CLR".equalsIgnoreCase(tokens.get(1))) {
            result.put("action", "CLEAR");
            return result;
        }
        if (tokens.size() != 3 || !isDirection(tokens.get(1))) {
            throw new V2DomainException("INVALID_INSTRUCTION", 400,
                    "OFFSET 语法：OFFSET L|R <1–10NM>", Arrays.asList("text"));
        }
        result.put("action", "APPLY");
        result.put("side", tokens.get(1).toUpperCase());
        result.put("distanceNm", radiusNm(tokens.get(2).replace("NM", ""), 1, 10));
        return result;
    }

    // ---------------------------------------------------------------- VOR

    /** VOR <台站> IN|OUT <径向> [DME <距离>]：台站必须是 VOR/VOR_DME（详细设计 7.2）。 */
    public static Map<String, Object> parseVor(String text, List<String> vorStationIds) {
        List<String> tokens = PerformanceGuard.tokenize(text);
        if (tokens.size() < 4 || !"VOR".equalsIgnoreCase(tokens.get(0))) {
            throw invalid("VOR <台站> IN|OUT <径向>", text);
        }
        String station = tokens.get(1).toUpperCase();
        if (vorStationIds != null && !vorStationIds.isEmpty()
                && !vorStationIds.contains(station)) {
            throw new V2DomainException("REFERENCE_NOT_FOUND", 422,
                    "台站必须是运行参考数据中的 VOR/VOR_DME: " + station,
                    Arrays.asList("station"));
        }
        String direction = tokens.get(2).toUpperCase();
        if (!"IN".equals(direction) && !"OUT".equals(direction)) {
            throw new V2DomainException("INVALID_INSTRUCTION", 400,
                    "VOR 截入/截出方向必须是 IN/OUT", Arrays.asList("direction"));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("station", station);
        result.put("direction", direction);
        result.put("radialDeg", BasicCommandParsers.headingOf(tokens.get(3)));
        if (tokens.size() == 6 && "DME".equalsIgnoreCase(tokens.get(4))) {
            result.put("dmeDistanceNm",
                    radiusNm(tokens.get(5).replace("NM", ""), 1, 50));
        }
        return result;
    }

    // ---------------------------------------------------------------- helpers

    private static boolean isDirection(String token) {
        return "L".equalsIgnoreCase(token) || "R".equalsIgnoreCase(token);
    }

    private static String waypointOf(String token) {
        String value = token == null ? "" : token.trim().toUpperCase();
        if (!value.matches("[A-Z0-9]{2,8}")) {
            throw new V2DomainException("INVALID_INSTRUCTION", 400,
                    "航路点非法: " + token, Arrays.asList("point"));
        }
        return value;
    }

    private static double radiusNm(String token, int min, int max) {
        double value = PerformanceGuard.parseDecimal(token.replace("NM", ""), "radiusNm");
        if (value < min || value > max) {
            throw new V2DomainException("INVALID_INSTRUCTION", 400,
                    "数值超出范围 " + min + "–" + max + "NM: " + token, Arrays.asList("radiusNm"));
        }
        return value;
    }

    private static V2DomainException legValueError(String token) {
        return new V2DomainException("INVALID_INSTRUCTION", 400,
                "HOLD 航段值必须是秒数（30–180）或 NM 距离（2–20）: " + token,
                Arrays.asList("legValue"));
    }

    private static V2DomainException invalid(String syntax, String text) {
        return new V2DomainException("INVALID_INSTRUCTION", 400,
                "语法应为 " + syntax + ": " + text, Arrays.asList("text"));
    }
}
