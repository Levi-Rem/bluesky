package org.bluesky.training.aircraft;

import org.bluesky.training.common.V2DomainException;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** P07：呼号/ICAO24/Squawk 规范化与校验（详细设计 5.2.2、7.6）。 */
public final class AircraftValidator {

    public static final Pattern CALLSIGN = Pattern.compile("^[A-Z0-9]{2,12}$");
    public static final Pattern ICAO24 = Pattern.compile("^[0-9A-F]{6}$");
    public static final Pattern OCTAL_SQUAWK = Pattern.compile("^[0-7]{4}$");

    private AircraftValidator() {
    }

    public static String normalizeCallsign(String raw) {
        if (raw == null) {
            throw invalid("callsign", "呼号不能为空");
        }
        String callsign = raw.trim().toUpperCase();
        if (!CALLSIGN.matcher(callsign).matches()) {
            throw invalid("callsign", "呼号必须是大写字母/数字 2–12 位: " + raw);
        }
        return callsign;
    }

    public static void validateIcao24(String icao24) {
        if (icao24 == null) {
            return; // 可空；非空时组内唯一
        }
        String value = icao24.trim().toUpperCase();
        if (!ICAO24.matcher(value).matches()) {
            throw invalid("icao24", "ICAO24 必须是 6 位十六进制: " + icao24);
        }
    }

    public static void validatePlannedSquawk(String squawk) {
        if (squawk == null || squawk.trim().isEmpty()) {
            throw invalid("plannedSquawk", "计划 Squawk 不能为空");
        }
        String value = squawk.trim();
        if (value.length() != 4) {
            throw invalid("plannedSquawk", "Squawk 必须是四位八进制: " + squawk);
        }
        if (!OCTAL_SQUAWK.matcher(value).matches()) {
            throw invalid("plannedSquawk", "Squawk 只能含 0–7 八进制数字: " + squawk);
        }
        if ("0000".equals(value)) {
            throw invalid("plannedSquawk", "Squawk 0000 明确拒绝");
        }
    }

    /** 同组重复 Squawk 允许创建但返回 DUPLICATE_SQUAWK 警告（详细设计 7.6）。 */
    public static Map<String, Object> collectWarnings(boolean duplicateSquawkInGroup) {
        Map<String, Object> warnings = new LinkedHashMap<>();
        if (duplicateSquawkInGroup) {
            warnings.put("warningCode", "DUPLICATE_SQUAWK");
            warnings.put("message", "训练组内已存在相同 Squawk");
        }
        return warnings;
    }

    public static void validateInitialState(Map<String, Object> initialState) {
        if (initialState == null) {
            throw invalid("initialState", "初始状态不能为空");
        }
        requireNumber(initialState, "latitudeDeg", -90, 90);
        requireNumber(initialState, "longitudeDeg", -180, 180);
        requireNumber(initialState, "trueHeadingDeg", 0, 360);
        // 高度/IAS 可由模板补齐；存在时做范围校验
        if (initialState.get("altitudeFtMsl") != null) {
            requireNumber(initialState, "altitudeFtMsl", -1000, 60000);
        }
        if (initialState.get("indicatedAirspeedKt") != null) {
            requireNumber(initialState, "indicatedAirspeedKt", 0, 600);
        }
    }

    private static void requireNumber(Map<String, Object> state, String field, double min, double max) {
        Object value = state.get(field);
        if (!(value instanceof Number)) {
            throw invalid("initialState." + field, field + " 必须是数值");
        }
        double number = ((Number) value).doubleValue();
        if (number < min || number > max) {
            throw invalid("initialState." + field,
                    field + " 超出范围 [" + min + ", " + max + "]: " + number);
        }
    }

    private static V2DomainException invalid(String field, String message) {
        return new V2DomainException("INVALID_INSTRUCTION", 400, message, Arrays.asList(field));
    }
}
