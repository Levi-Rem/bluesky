package org.bluesky.training.instruction;

import org.bluesky.training.common.V2DomainException;
import org.bluesky.training.reference.UnitConverter;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P10：基础飞行控制命令解析（详细设计 2.2 §7.1）。
 * 平台规范值：ft/kt/Mach/磁航向；SI 换算走 UnitConverter（Adapter 边界唯一入口）。
 */
public class BasicCommandParsers {

    // ---------------------------------------------------------------- HDG

    public static Map<String, Object> parseHeading(String text) {
        List<String> tokens = PerformanceGuard.tokenize(text);
        if (tokens.isEmpty() || !"HDG".equalsIgnoreCase(tokens.get(0))) {
            throw invalid("HDG", text);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        int index = 1;
        if (tokens.size() >= 3) {
            String direction = tokens.get(1).toUpperCase();
            if (!"L".equals(direction) && !"R".equals(direction)) {
                throw new V2DomainException("INVALID_INSTRUCTION", 400,
                        "HDG 转向方向必须是 L/R", Arrays.asList("turnDirection"));
            }
            result.put("turnDirection", direction);
            index = 2;
        } else {
            result.put("turnDirection", null);
        }
        if (tokens.size() <= index) {
            throw new V2DomainException("INVALID_INSTRUCTION", 400,
                    "HDG 缺少航向值", Arrays.asList("magneticHeadingDeg"));
        }
        int heading = headingOf(tokens.get(index));
        result.put("magneticHeadingDeg", heading);
        return result;
    }

    static int headingOf(String token) {
        if (token == null || !token.matches("\\d{1,3}")) {
            throw new V2DomainException("INVALID_INSTRUCTION", 400,
                    "磁航向必须是 000–360 的三位数: " + token, Arrays.asList("magneticHeadingDeg"));
        }
        int value = Integer.parseInt(token);
        if (value > 360) {
            throw new V2DomainException("INVALID_INSTRUCTION", 400,
                    "磁航向超出 000–360: " + token, Arrays.asList("magneticHeadingDeg"));
        }
        return value % 360; // 360 规范化为 000（详细设计 7.1）
    }

    // ---------------------------------------------------------------- LEFT/RIGHT

    /** 1–2 位是相对转角 1–99°，恰好三位是绝对磁航向（详细设计 7.1）。 */
    public static Map<String, Object> parseTurn(String text) {
        List<String> tokens = PerformanceGuard.tokenize(text);
        String keyword = tokens.isEmpty() ? "" : tokens.get(0).toUpperCase();
        if (!"LEFT".equals(keyword) && !"RIGHT".equals(keyword) || tokens.size() != 2) {
            throw invalid(keyword.isEmpty() ? "LEFT/RIGHT" : keyword, text);
        }
        String value = tokens.get(1);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("turnDirection", "LEFT".equals(keyword) ? "L" : "R");
        if (value.matches("\\d{3}")) {
            result.put("mode", "ABSOLUTE");
            result.put("valueDeg", headingOf(value));
        } else if (value.matches("\\d{1,2}")) {
            int relative = Integer.parseInt(value);
            if (relative < 1) {
                throw new V2DomainException("INVALID_INSTRUCTION", 400,
                        "相对转角必须是 1–99°", Arrays.asList("valueDeg"));
            }
            result.put("mode", "RELATIVE");
            result.put("valueDeg", relative);
        } else {
            throw new V2DomainException("INVALID_INSTRUCTION", 400,
                    "LEFT/RIGHT 参数必须是 1–2 位相对角或 3 位绝对航向: " + value,
                    Arrays.asList("valueDeg"));
        }
        return result;
    }

    // ---------------------------------------------------------------- ALT

    /** ALT 9000M / ALT 30000FT VS 1000FPM / ALT FL200（内嵌无符号 VS 为绝对值）。 */
    public static Map<String, Object> parseAltitude(String text) {
        List<String> tokens = PerformanceGuard.tokenize(text);
        if (tokens.isEmpty() || !"ALT".equalsIgnoreCase(tokens.get(0)) || tokens.size() < 2) {
            throw invalid("ALT", text);
        }
        String value = tokens.get(1).toUpperCase();
        double altitudeFtMsl;
        if (value.startsWith("FL")) {
            altitudeFtMsl = parseFl(value.substring(2));
        } else if (value.endsWith("M")) {
            altitudeFtMsl = UnitConverter.metersToFeet(
                    PerformanceGuard.parseDecimal(value.substring(0, value.length() - 1), "altitude"));
        } else if (value.endsWith("FT")) {
            altitudeFtMsl = PerformanceGuard.parseDecimal(
                    value.substring(0, value.length() - 2), "altitude");
        } else if (value.matches("\\d+")) {
            altitudeFtMsl = Double.parseDouble(value); // 无后缀按当前单位模式的默认英尺
        } else {
            throw new V2DomainException("INVALID_INSTRUCTION", 400,
                    "ALT 目标非法: " + value, Arrays.asList("altitudeFtMsl"));
        }
        PerformanceGuard.validateAltitude(altitudeFtMsl);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("altitudeFtMsl", altitudeFtMsl);
        if (tokens.size() >= 4 && "VS".equalsIgnoreCase(tokens.get(2))) {
            // 内嵌无符号 VS：绝对值，方向在执行时按当前/目标高度判定
            double fpm = verticalRateOf(tokens.get(3), true);
            result.put("verticalRateFpm", PerformanceGuard.limitVerticalSpeed(fpm));
        }
        return result;
    }

    private static double parseFl(String flText) {
        double fl = PerformanceGuard.parseDecimal(flText, "flightLevel");
        return fl * 100.0;
    }

    // ---------------------------------------------------------------- VS

    /** VS +1000FPM / VS -5MPS / VS 0：必须显式 +/- 或 0（详细设计 7.1）。 */
    public static Map<String, Object> parseVerticalSpeed(String text) {
        List<String> tokens = PerformanceGuard.tokenize(text);
        if (tokens.isEmpty() || !"VS".equalsIgnoreCase(tokens.get(0)) || tokens.size() != 2) {
            throw invalid("VS", text);
        }
        double fpm = verticalRateOf(tokens.get(1), false);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("verticalRateFpm", PerformanceGuard.limitVerticalSpeed(fpm));
        return result;
    }

    private static double verticalRateOf(String token, boolean unsignedAllowed) {
        String value = token.toUpperCase();
        boolean negative = false;
        if (value.startsWith("+")) {
            value = value.substring(1);
        } else if (value.startsWith("-")) {
            negative = true;
            value = value.substring(1);
        } else if (!unsignedAllowed && !"0".equals(value)) {
            throw new V2DomainException("INVALID_INSTRUCTION", 400,
                    "单独 VS 必须使用 +/- 或 0: " + token, Arrays.asList("verticalRateFpm"));
        }
        double magnitude;
        if (value.endsWith("FPM")) {
            magnitude = PerformanceGuard.parseDecimal(
                    value.substring(0, value.length() - 3), "verticalRateFpm");
        } else if (value.endsWith("MPS")) {
            magnitude = metersPerSecondToFpm(PerformanceGuard.parseDecimal(
                    value.substring(0, value.length() - 3), "verticalRateFpm"));
        } else if (value.matches("\\d+(\\.\\d+)?")) {
            magnitude = PerformanceGuard.parseDecimal(value, "verticalRateFpm");
        } else {
            throw new V2DomainException("INVALID_INSTRUCTION", 400,
                    "升降率非法: " + token, Arrays.asList("verticalRateFpm"));
        }
        return negative ? -magnitude : magnitude;
    }

    private static double metersPerSecondToFpm(double mps) {
        return UnitConverter.metersToFeet(mps) * 60.0;
    }

    // ---------------------------------------------------------------- SPD / MACH

    public static Map<String, Object> parseSpeed(String text) {
        List<String> tokens = PerformanceGuard.tokenize(text);
        if (tokens.isEmpty() || !"SPD".equalsIgnoreCase(tokens.get(0)) || tokens.size() != 2) {
            throw invalid("SPD", text);
        }
        String value = tokens.get(1).toUpperCase();
        double kt;
        if (value.endsWith("KMH")) {
            kt = Double.parseDouble(value.substring(0, value.length() - 3)) / 1.852;
        } else if (value.endsWith("KT")) {
            kt = PerformanceGuard.parseDecimal(value.substring(0, value.length() - 2),
                    "indicatedAirspeedKt");
        } else if (value.matches("\\d+(\\.\\d+)?")) {
            kt = PerformanceGuard.parseDecimal(value, "indicatedAirspeedKt");
        } else {
            throw new V2DomainException("INVALID_INSTRUCTION", 400,
                    "SPD 目标非法（高空不依据数值自动猜 Mach）: " + value,
                    Arrays.asList("indicatedAirspeedKt"));
        }
        PerformanceGuard.validateSpeed(kt);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("indicatedAirspeedKt", kt);
        return result;
    }

    public static Map<String, Object> parseMach(String text) {
        List<String> tokens = PerformanceGuard.tokenize(text);
        if (tokens.isEmpty() || !"MACH".equalsIgnoreCase(tokens.get(0)) || tokens.size() != 2) {
            throw invalid("MACH", text);
        }
        double mach = PerformanceGuard.parseDecimal(tokens.get(1), "mach");
        PerformanceGuard.validateMach(mach);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mach", mach);
        return result;
    }

    private static V2DomainException invalid(String keyword, String text) {
        return new V2DomainException("INVALID_INSTRUCTION", 400,
                keyword + " 命令语法非法: " + text, Arrays.asList("text"));
    }
}
