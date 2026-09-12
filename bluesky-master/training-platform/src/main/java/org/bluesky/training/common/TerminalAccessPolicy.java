package org.bluesky.training.common;

import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/** P02：终端通用权限（详细设计 6.4、14.7）。 */
@Component
public class TerminalAccessPolicy {

    public void requireSameGroup(CallerContext caller, String exerciseGroupId) {
        requireTerminalWrite(caller);
        if (exerciseGroupId == null || !exerciseGroupId.equals(caller.exerciseGroupId())) {
            throw new V2DomainException("TERMINAL_NOT_IN_GROUP", 403,
                    "终端与训练组不匹配", Arrays.asList("exerciseGroupId"));
        }
    }

    public void requireResponsibleTerminal(CallerContext caller, String responsibleTerminalId) {
        requireTerminalWrite(caller);
        if (responsibleTerminalId == null || !responsibleTerminalId.equals(caller.terminalId())) {
            throw new V2DomainException("AIRCRAFT_NOT_ASSIGNED", 403,
                    "非当前责任席位", Arrays.asList("terminalId"));
        }
    }

    public void requireTerminalWrite(CallerContext caller) {
        if (caller == null || caller.callerType() != CallerContext.CallerType.TERMINAL) {
            throw new V2DomainException("TRUSTED_IDENTITY_REJECTED", 403,
                    "该操作只允许受信终端执行");
        }
    }

    public void requireGroupStateAllows(String groupState, List<String> allowedStates) {
        if (!allowedStates.contains(groupState)) {
            if ("RECOVERING".equals(groupState) || "RECOVERY_FAILED".equals(groupState)) {
                throw new V2DomainException("ENGINE_RECOVERING", 503,
                        "引擎正在恢复，只允许只读访问", Arrays.asList("groupState"));
            }
            throw new V2DomainException("TRAINING_STATE_INVALID", 409,
                    "当前训练状态不允许该操作: " + groupState, Arrays.asList("groupState"));
        }
    }
}
