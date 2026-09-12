package org.bluesky.training.report;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P17：报告域纯逻辑（详细设计 2.2 §5.5/§9.2）。
 * 命令报告按指令状态转移唯一；飞行报告按 sourceEventId 去重；
 * 查询使用稳定倒序游标（仿真时间 + ID）。
 */
@Service
public class ReportQueryService {

    public static final List<String> FLIGHT_EVENT_TYPES = java.util.Collections.unmodifiableList(
            java.util.Arrays.asList("TARGET_REACHED", "WAYPOINT_PASSED", "PHASE_CHANGED",
                    "TAKEOFF", "LANDED", "MISSED_APPROACH", "SPECIAL_OPERATION", "ABNORMAL"));

    /** 指令状态转移的稳定终态说明（详细设计 12.2 原因码 → 人类可读）。 */
    public static String terminalDescription(String reasonCode) {
        if (reasonCode == null) {
            return "指令完成";
        }
        switch (reasonCode) {
            case "QUEUE_CLEARED_BY_REPLACE":
                return "同冲突键队列被实时替代清除";
            case "PREDECESSOR_NOT_COMPLETED":
                return "前置指令未正常完成，后继取消";
            case "TRAINING_ENDED":
                return "训练结束终止指令";
            case "CANCELLED_BY_RESPONSIBLE_TERMINAL":
                return "当前责任席主动取消";
            case "QUEUE_WAIT_TIMEOUT":
                return "等待前置完成超过业务预算";
            case "TARGET_NOT_REACHED":
                return "目标在最大执行预算内未达到";
            case "ADAPTER_ACK_TIMEOUT":
                return "Adapter 技术确认超时";
            case "GUIDANCE_FAILED":
                return "Adapter 引导状态机报告失败";
            case "ILS_TERMINATED_BY_OVERRIDE":
                return "新横向或垂直指令整体终止 ILS";
            default:
                return reasonCode;
        }
    }

    /**
     * 稳定倒序游标过滤（详细设计 9.2：按仿真时间和 ID 倒序，不使用页码）。
     * 行须含 simulationTimeSeconds/simulation_time_seconds 与 id。
     */
    public static List<Map<String, Object>> applyCursor(List<Map<String, Object>> sortedRows,
                                                        String cursor, int limit) {
        List<Map<String, Object>> page = new ArrayList<>();
        boolean emitting = cursor == null || cursor.trim().isEmpty();
        for (Map<String, Object> row : sortedRows) {
            if (!emitting) {
                if (rowKey(row).equals(cursor.trim())) {
                    emitting = true; // 游标行之后才开始输出
                }
                continue;
            }
            page.add(row);
            if (page.size() >= limit) {
                break;
            }
        }
        return page;
    }

    public static String rowKey(Map<String, Object> row) {
        return simTime(row) + ":" + row.get("id");
    }

    private static String simTime(Map<String, Object> row) {
        Object value = row.containsKey("simulationTimeSeconds")
                ? row.get("simulationTimeSeconds") : row.get("simulation_time_seconds");
        return value == null ? "0" : String.valueOf(((Number) value).doubleValue());
    }

    /** 事件类型白名单（未知拒绝写入，详细设计 5.0.4）。 */
    public static void requireKnownFlightEventType(String eventType) {
        if (!FLIGHT_EVENT_TYPES.contains(eventType)) {
            throw new org.bluesky.training.common.V2DomainException(
                    "INVALID_INSTRUCTION", 400, "未知飞行报告事件类型: " + eventType,
                    java.util.Arrays.asList("eventType"));
        }
    }

    /** 脚本重复确认返回原确认时间（详细设计 9.6）。 */
    public static Map<String, Object> acknowledgeOutcome(boolean alreadyAcknowledged,
                                                         java.sql.Timestamp acknowledgedAt,
                                                         String acknowledgedBy) {
        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("replayed", alreadyAcknowledged);
        outcome.put("acknowledgedAt", acknowledgedAt);
        outcome.put("acknowledgedBy", acknowledgedBy);
        return outcome;
    }
}
