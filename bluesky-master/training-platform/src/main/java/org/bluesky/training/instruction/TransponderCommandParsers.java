package org.bluesky.training.instruction;

import org.bluesky.training.common.V2DomainException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P14：应答机与速度恢复命令解析（详细设计 2.2 §7.6/§7.7）。
 * SQK/SSRMODE/NML/IDENT 是纯 MySQL 业务字段指令，不发送 Adapter；
 * NSPEED 复用 SPEED 通道，由 Adapter 确认 managed speed 后完成。
 */
public class TransponderCommandParsers {

    public static final int IDENT_MIN_SECONDS = 5;
    public static final int IDENT_MAX_SECONDS = 30;
    public static final int IDENT_DEFAULT_SECONDS = 18;

    // ---------------------------------------------------------------- SQK

    /** SQK <四位八进制> / SQK CLR：前导零保留；CLR 置无效；0000 明确拒绝。 */
    public static Map<String, Object> parseSquawk(String text) {
        List<String> tokens = PerformanceGuard.tokenize(text);
        if (tokens.size() != 2 || !"SQK".equalsIgnoreCase(tokens.get(0))) {
            throw invalid("SQK <代码>|SQK CLR", text);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("adapterRequired", false);
        String value = tokens.get(1).toUpperCase();
        if ("CLR".equals(value)) {
            result.put("action", "CLEAR");
            result.put("squawk", null);
            return result;
        }
        if (!value.matches("[0-7]{4}")) {
            throw new V2DomainException("INVALID_INSTRUCTION", 400,
                    value.contains("8") || value.contains("9")
                            ? "Squawk 只能含 0–7 八进制数字: " + value
                            : "Squawk 必须是四位八进制: " + value,
                    Arrays.asList("squawk"));
        }
        if ("0000".equals(value)) {
            throw new V2DomainException("INVALID_INSTRUCTION", 400,
                    "Squawk 0000 明确拒绝", Arrays.asList("squawk"));
        }
        result.put("action", "SET");
        result.put("squawk", value); // 字符串保存前导零（详细设计 7.6）
        return result;
    }

    // ---------------------------------------------------------------- SSRMODE

    /** SSRMODE A|C：固定枚举业务字段。 */
    public static Map<String, Object> parseSsrMode(String text) {
        List<String> tokens = PerformanceGuard.tokenize(text);
        if (tokens.size() != 2 || !"SSRMODE".equalsIgnoreCase(tokens.get(0))) {
            throw invalid("SSRMODE A|C", text);
        }
        String mode = tokens.get(1).toUpperCase();
        if (!"A".equals(mode) && !"C".equals(mode)) {
            throw new V2DomainException("INVALID_INSTRUCTION", 400,
                    "SSR Mode 只能是 A 或 C: " + tokens.get(1), Arrays.asList("ssrMode"));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("adapterRequired", false);
        result.put("ssrMode", mode);
        return result;
    }

    // ---------------------------------------------------------------- NML

    /** NML：同事务锁定 SQUAWK/SSR_MODE 双冲突键，恢复计划值（详细设计 7.6）。 */
    public static Map<String, Object> buildNormalRestore() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("adapterRequired", false);
        result.put("action", "RESTORE_PLANNED");
        result.put("lockConflictKeys", Arrays.asList("BUSINESS_FIELD:SQK", "BUSINESS_FIELD:SSRMODE"));
        return result;
    }

    // ---------------------------------------------------------------- IDENT

    /**
     * IDENT：transponderIdentActive=true 保持配置秒数后自动清除；
     * 重复成功时重新计时（详细设计 7.6）；独立 TRANSPONDER_IDENT 冲突键。
     */
    public static Map<String, Object> buildIdent(Integer configuredDurationSeconds) {
        int duration = configuredDurationSeconds == null
                ? IDENT_DEFAULT_SECONDS : configuredDurationSeconds;
        if (duration < IDENT_MIN_SECONDS || duration > IDENT_MAX_SECONDS) {
            throw new V2DomainException("INVALID_INSTRUCTION", 400,
                    "IDENT 持续时间配置范围 5–30 秒: " + duration,
                    Arrays.asList("transponderIdentDurationSeconds"));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("adapterRequired", false);
        result.put("transponderIdentActive", true);
        result.put("durationSeconds", duration);
        result.put("restartsOnRepeat", true);
        result.put("conflictKeySuffix", "TRANSPONDER_IDENT");
        return result;
    }

    // ---------------------------------------------------------------- NSPEED

    /** NSPEED：清除人工 SPD/MACH 目标，恢复 managed speed；Adapter 确认后完成（详细设计 7.7）。 */
    public static Map<String, Object> buildNormalSpeed() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("adapterRequired", true);
        result.put("channel", "SPEED");
        result.put("action", "RESTORE_MANAGED");
        result.put("rules", Arrays.asList(
                "不得绕过失速、超速或特情导致的有效性能降级",
                "Adapter 确认 managed speed 模式和当前 managed target 已生效后完成"));
        return result;
    }

    /** IDENT 自动清除判定：按仿真秒推进（暂停冻结）。 */
    public static boolean identExpired(double activatedAtSeconds, double durationSeconds,
                                       double nowSeconds) {
        return nowSeconds - activatedAtSeconds >= durationSeconds;
    }

    private static V2DomainException invalid(String syntax, String text) {
        return new V2DomainException("INVALID_INSTRUCTION", 400,
                "语法应为 " + syntax + ": " + text, Arrays.asList("text"));
    }
}
