package org.bluesky.training.instruction;

import org.bluesky.training.persistence.InstructionV2Mapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * P09：每冲突键严格串行（详细设计 2.2 §6.2）。
 * REPLACE 清空同键等待队列（CANCELLED/QUEUE_CLEARED_BY_REPLACE）；
 * AFTER_COMPLETION 前置失败/取消/被替换时后继 CANCELLED/PREDECESSOR_NOT_COMPLETED。
 */
@Service
public class InstructionQueueService {

    private final InstructionV2Mapper mapper;

    public InstructionQueueService(InstructionV2Mapper mapper) {
        this.mapper = mapper;
    }

    @Transactional
    public long allocateSequence(String aircraftId, String conflictKey) {
        return mapper.maxSequence(aircraftId, conflictKey) + 1;
    }

    /** REPLACE 生效：同冲突键仍在等待的指令整体取消（详细设计 6.2）。 */
    @Transactional
    public int clearWaitingByReplace(String aircraftId, String conflictKey,
                                     String excludingInstructionId) {
        int cleared = 0;
        for (Map<String, Object> waiting : mapper.findWaitingByConflictKey(aircraftId, conflictKey)) {
            String id = String.valueOf(waiting.get("id"));
            if (id.equals(excludingInstructionId)) {
                continue;
            }
            cleared += mapper.terminateWithReason(id,
                    String.valueOf(waiting.get("status")), "CANCELLED",
                    "QUEUE_CLEARED_BY_REPLACE", "同冲突键队列被实时替代清除");
        }
        return cleared;
    }

    /** 前置终态处理：COMPLETED 释放后继；未完成终态取消全部直接后继（详细设计 6.2）。 */
    @Transactional
    public void releaseOrCancelSuccessors(String predecessorId, String predecessorFinalState) {
        for (Map<String, Object> candidate : mapper.listSuccessorsOf(predecessorId)) {
            String successorId = String.valueOf(candidate.get("id"));
            if ("COMPLETED".equals(predecessorFinalState)) {
                mapper.releaseBlocker(successorId, "PREDECESSOR_ACTIVE");
            } else {
                mapper.terminateWithReason(successorId,
                        String.valueOf(candidate.get("status")), "CANCELLED",
                        "PREDECESSOR_NOT_COMPLETED",
                        "前置指令未正常完成: " + predecessorFinalState);
            }
        }
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> waitingFor(String aircraftId, String conflictKey) {
        return mapper.findWaitingByConflictKey(aircraftId, conflictKey);
    }

    static String uuid() {
        return UUID.randomUUID().toString();
    }
}
