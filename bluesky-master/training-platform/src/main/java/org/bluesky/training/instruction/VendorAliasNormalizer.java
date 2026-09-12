package org.bluesky.training.instruction;

import org.bluesky.training.common.V2DomainException;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * P10：厂商快捷语法兼容（详细设计 2.2 §7.10）。
 * 别名在同一解析器中规范化；命令报告同时保存原始文本与规范化命令。
 */
public class VendorAliasNormalizer {

    /** 关键字别名：LVL→ALT、SPEED→SPD、SSRCODE→SQK；ID 绝不归一化为 IDENT。 */
    public static String normalizeKeyword(String keyword) {
        String upper = keyword == null ? "" : keyword.trim().toUpperCase();
        switch (upper) {
            case "LVL":
                return "ALT";
            case "SPEED":
                return "SPD";
            case "SSRCODE":
                return "SQK";
            default:
                return upper;
        }
    }

    /** 升降率别名：CR 值 → VS +值；DR 值 → VS -值。 */
    public static String normalizeVerticalRateAlias(String text) {
        String[] tokens = text == null ? new String[0] : text.trim().split("\\s+");
        if (tokens.length != 2) {
            return text;
        }
        String keyword = tokens[0].toUpperCase();
        if ("CR".equals(keyword)) {
            return "VS +" + tokens[1];
        }
        if ("DR".equals(keyword)) {
            return "VS -" + tokens[1];
        }
        return text;
    }

    /** OFFSET 兼容：空参数 → OFFSET CLR；R5/L5 → R 5NM/L 5NM；裸数 → 右偏置。 */
    public static String normalizeOffsetAlias(String text) {
        String trimmed = text == null ? "" : text.trim();
        String[] tokens = trimmed.isEmpty() ? new String[0] : trimmed.split("\\s+");
        if (tokens.length == 0 || !"OFFSET".equalsIgnoreCase(tokens[0])) {
            return trimmed;
        }
        if (tokens.length == 1) {
            return "OFFSET CLR";
        }
        String value = tokens[1].toUpperCase();
        // 紧凑形式 R1–R10 / L1–L10
        if (value.matches("[RL]\\d{1,2}")) {
            return "OFFSET " + value.charAt(0) + " " + value.substring(1) + "NM";
        }
        if (value.matches("\\d{1,2}")) {
            return "OFFSET R " + value + "NM"; // 裸数等同右偏置（详细设计 7.2）
        }
        return trimmed;
    }

    /** 完整规范化：关键字 + 升降率别名 + 偏置别名；未知命令拒绝。 */
    public static String normalize(String text) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty()) {
            throw new V2DomainException("INVALID_INSTRUCTION", 400, "命令不能为空",
                    Arrays.asList("text"));
        }
        String offset = normalizeOffsetAlias(trimmed);
        String[] tokens = offset.split("\\s+");
        String keyword = normalizeKeyword(tokens[0]);
        if (!KNOWN.contains(keyword)) {
            throw new V2DomainException("INVALID_INSTRUCTION", 400,
                    "未知命令（未在兼容表和第 7 节出现的厂商命令不得静默接受）: " + tokens[0],
                    Arrays.asList("text"));
        }
        tokens[0] = keyword;
        String joined = String.join(" ", tokens);
        return normalizeVerticalRateAlias(joined);
    }

    /** 已接受的关键字（规范名 + 兼容名）；与命令目录一致。 */
    public static final java.util.Set<String> KNOWN = new java.util.HashSet<>(Arrays.asList(
            "ACID", "HDG", "LEFT", "RIGHT", "ALT", "VS", "SPD", "MACH", "DCT", "RTE",
            "RESUME", "ORBIT", "HOLD", "OFFSET", "VOR", "SIDSTAR", "P_LEVEL", "P_TIME",
            "TAKEOFF", "ILS", "MISSED", "SQK", "SSRMODE", "NML", "IDENT", "NSPEED",
            "ID", "DECOMP", "FRE", "DEL", "LVL", "SPEED", "SSRCODE", "CR", "DR"));
}
