package org.bluesky.training.instruction;

import org.bluesky.training.persistence.InstructionV2Mapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * P09：复合指令父状态聚合与接管策略（详细设计 2.2 §5.4/§6.2）。
 * 纯聚合逻辑可脱库测试；落库路径按 composite_instruction_child 关联。
 */
@Service
public class CompositeInstructionService {

    private final InstructionV2Mapper mapper;

    public CompositeInstructionService(InstructionV2Mapper mapper) {
        this.mapper = mapper;
    }

    /** 父状态由子项聚合：全部必需完成才 COMPLETED；任一必需失败则 FAILED。 */
    public static String aggregateParentState(List<Map<String, Object>> children) {
        boolean allRequiredCompleted = true;
        for (Map<String, Object> child : children) {
            boolean required = !Boolean.FALSE.equals(child.get("required"));
            String state = String.valueOf(child.get("status"));
            if (required && ("FAILED".equals(state) || "TIMED_OUT".equals(state))) {
                return "FAILED";
            }
            if (required && !"COMPLETED".equals(state)) {
                allRequiredCompleted = false;
            }
        }
        return allRequiredCompleted ? "COMPLETED" : "EXECUTING";
    }

    /** 接管评估：全部子项被接管 → 父 REPLACED；部分 → EXECUTING + PARTIALLY_OVERRIDDEN。 */
    public static String aggregateAfterOverride(List<Map<String, Object>> children,
                                                List<String> overriddenChannels) {
        boolean allOverridden = !children.isEmpty();
        for (Map<String, Object> child : children) {
            String channel = String.valueOf(child.get("channel"));
            if (!overriddenChannels.contains(channel)) {
                allOverridden = false;
            }
        }
        return allOverridden ? "REPLACED" : "EXECUTING";
    }

    @Transactional
    public List<String> createChildren(String parentId, String aircraftId, String groupId,
                                       String sourceTerminalId, String type,
                                       InstructionCatalog catalog,
                                       InstructionQueueService queueService,
                                       String rawText, String parsedPayload) {
        List<String> childIds = new ArrayList<>();
        for (String channel : catalog.affectedChannelsFor(type)) {
            String childId = UUID.randomUUID().toString();
            String conflictKey = channel + ":" + aircraftId;
            long sequence = queueService.allocateSequence(aircraftId, conflictKey);
            mapper.insertInstruction(childId, aircraftId, groupId, sourceTerminalId,
                    type, channel, conflictKey, "REPLACE", "RECEIVED", sequence,
                    null, rawText, parsedPayload, null, null);
            mapper.insertCompositeChild(UUID.randomUUID().toString(), parentId, childId,
                    channel, true);
            childIds.add(childId);
        }
        return childIds;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> childrenOf(String parentId) {
        return mapper.childrenOf(parentId);
    }

    /** 整体终止（ILS/MISSED 被接管）：同一循环清除全部子项与引导目标。 */
    @Transactional
    public int terminateAllChildren(String parentId, String reasonCode, String message) {
        int terminated = 0;
        for (Map<String, Object> child : mapper.childrenOf(parentId)) {
            terminated += mapper.terminateWithReason(String.valueOf(child.get("childId")),
                    String.valueOf(child.get("status")), "REPLACED", reasonCode, message);
        }
        return terminated;
    }

    static List<String> channels(List<Map<String, Object>> children) {
        List<String> channels = new ArrayList<>();
        for (Map<String, Object> child : children) {
            channels.add(String.valueOf(child.get("channel")));
        }
        return channels;
    }
}
