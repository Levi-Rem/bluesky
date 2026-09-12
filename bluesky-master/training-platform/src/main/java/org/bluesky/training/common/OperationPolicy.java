package org.bluesky.training.common;

import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/** P02：聚合训练态、飞行阶段与引擎可写性（详细设计 5.1 操作矩阵、7.3、10.1）。 */
@Component
public class OperationPolicy {

    private static final List<String> READ_ONLY_STATES = Arrays.asList("RECOVERING", "RECOVERY_FAILED");

    public void requireGroupStateAllows(String groupState, List<String> allowedStates) {
        if (!allowedStates.contains(groupState)) {
            if (READ_ONLY_STATES.contains(groupState)) {
                throw new V2DomainException("ENGINE_RECOVERING", 503,
                        "引擎正在恢复，只允许只读访问", Arrays.asList("groupState"));
            }
            throw new V2DomainException("TRAINING_STATE_INVALID", 409,
                    "当前训练状态不允许该操作: " + groupState, Arrays.asList("groupState"));
        }
    }

    public void requireAircraftPhaseAllows(String flightPhase, List<String> allowedPhases) {
        if (!allowedPhases.contains(flightPhase)) {
            throw new V2DomainException("PROCEDURE_STATE_INVALID", 409,
                    "当前飞行阶段不允许该程序: " + flightPhase, Arrays.asList("flightPhase"));
        }
    }

    public void requireEngineWritable(String engineState) {
        this.requireEngineWritable(engineState, null);
    }

    public void requireEngineWritable(String engineState, List<String> extraWritableStates) {
        boolean writable = "CONNECTED".equals(engineState) || "DEGRADED".equals(engineState)
                || (extraWritableStates != null && extraWritableStates.contains(engineState));
        if (!writable) {
            throw new V2DomainException("ENGINE_RECOVERING", 503,
                    "引擎当前不可写: " + engineState, Arrays.asList("engineState"));
        }
    }
}
