package org.bluesky.training.instruction;

import org.bluesky.training.common.V2DomainException;
import org.bluesky.training.persistence.InstructionV2Mapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * P09：与指令终态解耦的引导目标生命周期（详细设计 2.2 §5.4）。
 * 指令 COMPLETED 后目标保持 ACTIVE；后续覆盖只把旧目标 SUPERSEDED，
 * 不得把已完成指令倒改为 REPLACED。
 */
@Service
public class GuidanceTargetService {

    private final InstructionV2Mapper mapper;

    public GuidanceTargetService(InstructionV2Mapper mapper) {
        this.mapper = mapper;
    }

    @Transactional
    public String createPending(String instructionId, String aircraftId, String channel,
                                String targetJson) {
        String id = UUID.randomUUID().toString();
        mapper.insertGuidanceTarget(id, instructionId, aircraftId, channel, targetJson);
        return id;
    }

    /** 仅在 INSTRUCTION_APPLIED 事务中激活并替代旧目标（详细设计 6.3.2）。 */
    @Transactional
    public void activate(String targetId, String aircraftId, String channel) {
        mapper.supersedeActiveGuidance(aircraftId, channel);
        int changed = mapper.transitionGuidance(targetId, "PENDING_APPLY", "ACTIVE");
        if (changed != 1) {
            throw new V2DomainException("GUIDANCE_FAILED", 502,
                    "引导目标不在 PENDING_APPLY 状态: " + targetId);
        }
    }

    @Transactional
    public void supersede(String targetId) {
        mapper.transitionGuidance(targetId, "ACTIVE", "SUPERSEDED");
    }

    @Transactional
    public void clear(String targetId) {
        mapper.transitionGuidance(targetId, "ACTIVE", "CLEARED");
    }

    @Transactional
    public void fail(String targetId) {
        mapper.transitionGuidance(targetId, "PENDING_APPLY", "FAILED");
        mapper.transitionGuidance(targetId, "ACTIVE", "FAILED");
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> targetsOf(String instructionId) {
        return mapper.guidanceOf(instructionId);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> activeTarget(String aircraftId, String channel) {
        return mapper.findActiveGuidance(aircraftId, channel);
    }
}
