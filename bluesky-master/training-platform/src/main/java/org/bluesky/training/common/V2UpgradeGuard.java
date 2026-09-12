package org.bluesky.training.common;

import org.springframework.stereotype.Component;

/** P01：升级前置守卫（详细设计 13.2：只允许 READY/ENDED 时升级）。 */
@Component
public class V2UpgradeGuard {

    public void assertNoActiveExercise(long activeGroupCount) {
        if (activeGroupCount > 0) {
            throw new V2DomainException("TRAINING_STATE_INVALID", 409,
                    "存在 " + activeGroupCount + " 个活动训练组，禁止执行升级迁移");
        }
    }
}
