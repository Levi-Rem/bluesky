package org.bluesky.training.exercise;

import org.bluesky.training.common.V2DomainException;
import org.bluesky.training.persistence.ExerciseGroupLifecycleMapper;
import org.bluesky.training.persistence.ExerciseGroupStateRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;

/** P05：持久化组仿真时钟（详细设计 4.2：只有 RUNNING 推进，时间不回退）。 */
@Service
public class SimulationClockService {

    private final ExerciseGroupLifecycleMapper lifecycleMapper;

    public SimulationClockService(ExerciseGroupLifecycleMapper lifecycleMapper) {
        this.lifecycleMapper = lifecycleMapper;
    }

    @Transactional(readOnly = true)
    public long currentTime(String groupId) {
        return requireGroup(groupId).getSimulationTimeSeconds();
    }

    @Transactional
    public void advanceFromFrame(String groupId, long simulationTimeSeconds) {
        ExerciseGroupStateRow current = requireGroup(groupId);
        if (!"RUNNING".equals(current.getState())) {
            throw new V2DomainException("TRAINING_STATE_INVALID", 409,
                    "非 RUNNING 状态不得推进仿真时间: " + current.getState(),
                    Arrays.asList("groupState"));
        }
        if (simulationTimeSeconds <= current.getSimulationTimeSeconds()) {
            return; // 旧帧/迟到帧不回退；等值帧不推进（评审 C11：revision 不与乐观锁打架）
        }
        lifecycleMapper.advanceSimulationTime(groupId, simulationTimeSeconds);
    }

    /**
     * PAUSED 确认后落库 Adapter 返回的实际暂停时刻（详细设计 4.2.7；评审 C3）。
     * 仅 PAUSED 状态接受；时间不回退、等值不写（与帧推进同一约束）。
     */
    @Transactional
    public void freezeAtPause(String groupId, double actualPauseSimulationTimeSeconds) {
        ExerciseGroupStateRow current = requireGroup(groupId);
        if (!"PAUSED".equals(current.getState())) {
            return;
        }
        if (current.getSimulationTimeSeconds() >= actualPauseSimulationTimeSeconds) {
            return;
        }
        lifecycleMapper.freezeSimulationTime(groupId,
                Math.round(actualPauseSimulationTimeSeconds));
    }

    private ExerciseGroupStateRow requireGroup(String groupId) {
        ExerciseGroupStateRow current = groupId == null ? null : lifecycleMapper.findById(groupId);
        if (current == null) {
            throw new V2DomainException("TERMINAL_NOT_FOUND", 404, "训练组不存在: " + groupId);
        }
        return current;
    }
}
