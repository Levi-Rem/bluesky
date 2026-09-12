package org.bluesky.training.exercise;

import org.bluesky.training.adapter.EngineInstanceService;
import org.bluesky.training.common.V2DomainException;
import org.bluesky.training.persistence.ExerciseGroupLifecycleMapper;
import org.bluesky.training.persistence.ExerciseGroupStateRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * P05：过渡态 5 秒看门狗（详细设计 5.1；评审 C2）。
 * STARTING/PAUSING/RESUMING 超时未收到 Adapter 确认 → ENTER_RECOVERING；
 * ENDING 超时后核实实际进程退出，不能仅凭超时宣告引擎停止。
 */
@Component
public class ExerciseGroupTransitionWatchdog {

    static final long TRANSITION_TIMEOUT_MILLIS = 5_000L;

    private static final Logger log = LoggerFactory.getLogger(ExerciseGroupTransitionWatchdog.class);

    private final ExerciseGroupLifecycleMapper lifecycleMapper;
    private final ExerciseGroupService groupService;
    private final EngineInstanceService engineService;

    public ExerciseGroupTransitionWatchdog(ExerciseGroupLifecycleMapper lifecycleMapper,
                                           ExerciseGroupService groupService,
                                           EngineInstanceService engineService) {
        this.lifecycleMapper = lifecycleMapper;
        this.groupService = groupService;
        this.engineService = engineService;
    }

    @Scheduled(fixedDelay = 1000)
    public void checkStaleTransitions() {
        LocalDateTime threshold = lifecycleMapper.databaseNow()
                .minusNanos(TRANSITION_TIMEOUT_MILLIS * 1_000_000);
        List<ExerciseGroupStateRow> stale = lifecycleMapper.findStaleTransitions(
                Arrays.asList("STARTING", "PAUSING", "RESUMING", "ENDING"), threshold);
        for (ExerciseGroupStateRow group : stale) {
            if ("ENDING".equals(group.getState())) {
                reconcileStaleEnding(group);
            } else {
                recoverStaleTransition(group);
            }
        }
    }

    private void recoverStaleTransition(ExerciseGroupStateRow group) {
        try {
            groupService.enterRecovering(group.getId(),
                    "过渡态 " + group.getState() + " 超过 "
                            + TRANSITION_TIMEOUT_MILLIS + "ms 未收到 Adapter 确认");
            log.warn("过渡态超时转入恢复: groupId={} state={}", group.getId(), group.getState());
        } catch (V2DomainException overtaken) {
            // 看门狗与正常确认路径并发：乐观锁 CAS 失败说明确认已到达，忽略
        }
    }

    /** STOP 回执丢失时，只有实际进程退出证据才允许终结 ENDED。 */
    private void reconcileStaleEnding(ExerciseGroupStateRow group) {
        String instance=engineService.currentInstanceId(group.getId());
        if (instance!=null && engineService.hasProcessStopped(instance)) {
            try {
                groupService.onAdapterLifecycleResult(group.getId(),"STOPPED");
                engineService.markStopped(instance);
            } catch(V2DomainException concurrent) { /* A native STOP reply won the race. */ }
            return;
        }
        log.debug("等待真实 STOP 确认: groupId={}", group.getId());
    }
}
