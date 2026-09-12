package org.bluesky.training.instruction;

import org.bluesky.training.common.V2DomainException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** P10：性能包线守卫（详细设计 2.2 §7.1：超出机型/程序包线时拒绝）。 */
public final class PerformanceGuard {

    public static final double MAX_ALTITUDE_FT = 60000;
    public static final double MIN_ALTITUDE_FT = -1000;
    public static final double MAX_IAS_KT = 600;
    public static final double MIN_IAS_KT = 0;
    public static final double MAX_VERTICAL_RATE_FPM = 6000;
    public static final double MIN_MACH = 0.1;
    public static final double MAX_MACH = 0.99;

    private PerformanceGuard() {
    }

    public static void validateAltitude(double altitudeFtMsl) {
        if (altitudeFtMsl < MIN_ALTITUDE_FT || altitudeFtMsl > MAX_ALTITUDE_FT) {
            throw envelope("altitudeFtMsl", altitudeFtMsl,
                    MIN_ALTITUDE_FT + "–" + MAX_ALTITUDE_FT + " ft");
        }
    }

    public static double limitVerticalSpeed(double verticalRateFpm) {
        if (Math.abs(verticalRateFpm) > MAX_VERTICAL_RATE_FPM) {
            throw envelope("verticalRateFpm", verticalRateFpm,
                    "±" + MAX_VERTICAL_RATE_FPM + " fpm");
        }
        return verticalRateFpm;
    }

    public static void validateSpeed(double indicatedAirspeedKt) {
        if (indicatedAirspeedKt < MIN_IAS_KT || indicatedAirspeedKt > MAX_IAS_KT) {
            throw envelope("indicatedAirspeedKt", indicatedAirspeedKt,
                    MIN_IAS_KT + "–" + MAX_IAS_KT + " kt");
        }
    }

    public static void validateMach(double mach) {
        if (mach < MIN_MACH || mach > MAX_MACH) {
            throw envelope("mach", mach, MIN_MACH + "–" + MAX_MACH);
        }
    }

    /** 十进制分隔符只接受 "."（详细设计 7.1）。 */
    public static double parseDecimal(String token, String field) {
        if (token == null || !token.matches("\\d+(\\.\\d+)?")) {
            throw new V2DomainException("INVALID_INSTRUCTION", 400,
                    field + " 必须是十进制数且分隔符只能用 '.': " + token,
                    Arrays.asList(field));
        }
        try {
            return Double.parseDouble(token);
        } catch (NumberFormatException e) {
            throw new V2DomainException("INVALID_INSTRUCTION", 400,
                    field + " 解析失败: " + token, Arrays.asList(field));
        }
    }

    static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        if (text != null) {
            for (String token : text.trim().split("\\s+")) {
                if (!token.isEmpty()) {
                    tokens.add(token);
                }
            }
        }
        return tokens;
    }

    static V2DomainException envelope(String field, double value, String range) {
        return new V2DomainException("PERFORMANCE_LIMIT_EXCEEDED", 422,
                field + " 超出包线: " + value + "（允许 " + range + "）",
                Arrays.asList(field));
    }
}
