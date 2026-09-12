package org.bluesky.training.common;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * P20：Micrometer 指标统一入口（详细设计 2.2 §12.3/§25.1）。
 * 标签只允许低基数枚举（状态、原因码、通道），不得携带 ID 类高基数值。
 */
public class TrainingMetrics {

    /** 指令状态计数：标签 = 目标状态 + 终态原因码（枚举，低基数）。 */
    public static final String INSTRUCTION_TRANSITION = "training.instruction.transition";
    /** 派发技术确认耗时（毫秒）。 */
    public static final String DISPATCH_LATENCY = "training.adapter.dispatch.latency";
    /** SSE 待发送积压（条数/字节）。 */
    public static final String SSE_BACKLOG = "training.sse.backlog";
    /** 移交成功/冲突计数。 */
    public static final String HANDOVER_OUTCOME = "training.handover.outcome";
    /** 恢复流程结果计数。 */
    public static final String RECOVERY_OUTCOME = "training.recovery.outcome";

    /** 允许出现在指标标签中的指令状态（枚举封闭集，与指令状态机一致）。 */
    public static final java.util.Set<String> ALLOWED_INSTRUCTION_STATES =
            java.util.Collections.unmodifiableSet(new java.util.LinkedHashSet<>(
                    java.util.Arrays.asList(
                            "RECEIVED", "VALIDATED", "BLOCKED", "DISPATCHING", "EXECUTING",
                            "COMPLETED", "REPLACED", "FAILED", "TIMED_OUT", "CANCELLED",
                            "REJECTED")));

    /** 移交结果标签（成功/冲突两种，禁止携带终端 ID）。 */
    public static final java.util.Set<String> ALLOWED_HANDOVER_OUTCOMES =
            java.util.Collections.unmodifiableSet(new java.util.LinkedHashSet<>(
                    java.util.Arrays.asList("COMPLETED", "CONFLICT")));

    public static final java.util.Set<String> ALLOWED_RECOVERY_OUTCOMES =
            java.util.Collections.unmodifiableSet(new java.util.LinkedHashSet<>(
                    java.util.Arrays.asList("PAUSED", "RECOVERY_FAILED", "SKIPPED_NOT_RECOVERABLE")));

    /** 校验并组装指标标签：任何非白名单标签值直接抛错（防止高基数 ID 泄入指标）。 */
    public static Map<String, String> instructionTransitionTags(String toState, String reasonCode) {
        requireIn(ALLOWED_INSTRUCTION_STATES, toState, "instruction.state");
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("state", toState);
        if (reasonCode != null) {
            requireLowCardinality(reasonCode);
            tags.put("reason", reasonCode);
        }
        return tags;
    }

    public static Map<String, String> handoverTags(String outcome) {
        requireIn(ALLOWED_HANDOVER_OUTCOMES, outcome, "handover.outcome");
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("outcome", outcome);
        return tags;
    }

    public static Map<String, String> recoveryTags(String outcome) {
        requireIn(ALLOWED_RECOVERY_OUTCOMES, outcome, "recovery.outcome");
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("outcome", outcome);
        return tags;
    }

    private static void requireIn(java.util.Set<String> allowed, String value, String metric) {
        if (!allowed.contains(value)) {
            throw new IllegalArgumentException(
                    "指标标签值不在低基数白名单（" + metric + "）: " + value);
        }
    }

    /** 原因码必须是大写下划线枚举形态，拒绝 UUID/ID/路径等高基数值。 */
    private static void requireLowCardinality(String value) {
        if (!value.matches("[A-Z][A-Z0-9_]{0,63}")) {
            throw new IllegalArgumentException("指标 reason 标签必须是枚举形态: " + value);
        }
    }
}
