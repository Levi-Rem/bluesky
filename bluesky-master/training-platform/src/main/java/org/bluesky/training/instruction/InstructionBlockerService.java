package org.bluesky.training.instruction;

import org.bluesky.training.persistence.InstructionV2Mapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * P09：四类阻塞原因管理（详细设计 2.2 §5.4）。
 * 同一指令可同时具有多个原因；只有全部释放后才从 BLOCKED 进入 DISPATCHING。
 */
@Service
public class InstructionBlockerService {

    public static final List<String> REASONS = java.util.Collections.unmodifiableList(
            java.util.Arrays.asList("TRAINING_PAUSED", "PREDECESSOR_ACTIVE",
                    "ENGINE_RECOVERING", "SCHEDULED_TIME_NOT_REACHED"));

    private final InstructionV2Mapper mapper;

    public InstructionBlockerService(InstructionV2Mapper mapper) {
        this.mapper = mapper;
    }

    @Transactional
    public void add(String instructionId, String reason) {
        requireKnown(reason);
        mapper.insertBlocker(UUID.randomUUID().toString(), instructionId, reason);
    }

    @Transactional
    public void remove(String instructionId, String reason) {
        requireKnown(reason);
        mapper.releaseBlocker(instructionId, reason);
    }

    /** 训练暂停解除时批量释放该原因。 */
    @Transactional
    public void releaseTrainingPaused(String instructionId) {
        mapper.releaseBlocker(instructionId, "TRAINING_PAUSED");
    }

    @Transactional(readOnly = true)
    public List<String> activeReasons(String instructionId) {
        List<String> active = mapper.activeBlockers(instructionId);
        return active == null ? new ArrayList<>() : active;
    }

    /** 全部释放才允许 BLOCKED → DISPATCHING（详细设计 5.4）。 */
    @Transactional(readOnly = true)
    public boolean isClear(String instructionId) {
        return activeReasons(instructionId).isEmpty();
    }

    private static void requireKnown(String reason) {
        if (!REASONS.contains(reason)) {
            throw new IllegalArgumentException("未知阻塞原因: " + reason);
        }
    }
}
