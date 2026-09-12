package org.bluesky.training.common;

import org.springframework.stereotype.Component;

/** P02：服务身份用途隔离（详细设计 9.1、14.8）：end/脚本/profile 只允许对应服务身份。 */
@Component
public class ServiceAccessPolicy {

    public void requireOrchestrator(CallerContext caller) {
        require(caller, CallerContext.CallerType.EXERCISE_ORCHESTRATOR, "训练编排服务");
    }

    public void requireOperations(CallerContext caller) {
        require(caller, CallerContext.CallerType.OPERATIONS, "运维服务");
    }

    private void require(CallerContext caller, CallerContext.CallerType expected, String description) {
        if (caller == null || caller.callerType() != expected) {
            throw new V2DomainException("TRUSTED_IDENTITY_REJECTED", 403,
                    "该操作只允许" + description + "身份执行");
        }
    }
}
