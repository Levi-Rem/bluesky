package org.bluesky.training.common;

import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * P20：升级就绪守卫（详细设计 2.2 §13.2）。
 * 只允许所有训练组处于 READY/ENDED 时升级；活动状态全部拒绝。
 */
@Service
public class UpgradeReadinessService {

    public static final java.util.Set<String> UPGRADABLE_STATES =
            java.util.Collections.unmodifiableSet(new java.util.LinkedHashSet<>(
                    Arrays.asList("READY", "ENDED")));

    /** 活动状态集合：任一存在即拒绝升级。 */
    public static final java.util.Set<String> ACTIVE_STATES =
            java.util.Collections.unmodifiableSet(new java.util.LinkedHashSet<>(Arrays.asList(
                    "STARTING", "RUNNING", "PAUSING", "PAUSED", "RESUMING",
                    "RECOVERING", "RECOVERY_FAILED", "ENDING")));

    /** assertUpgradeAllowed：对每组状态参数化调用。 */
    public void assertUpgradeAllowed(Map<String, String> groupStates) {
        if (groupStates == null) {
            return;
        }
        Map<String, String> blocking = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : groupStates.entrySet()) {
            if (ACTIVE_STATES.contains(entry.getValue())) {
                blocking.put(entry.getKey(), entry.getValue());
            } else if (!UPGRADABLE_STATES.contains(entry.getValue())) {
                blocking.put(entry.getKey(), entry.getValue());
            }
        }
        if (!blocking.isEmpty()) {
            throw new V2DomainException("TRAINING_STATE_INVALID", 409,
                    "存在非 READY/ENDED 训练组，禁止升级: " + blocking,
                    Arrays.asList("groupStates"));
        }
    }
}
