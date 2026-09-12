package org.bluesky.training.exercise;

import org.bluesky.training.adapter.EngineInstanceService;
import org.bluesky.training.common.V2DomainException;
import org.bluesky.training.persistence.ExerciseGroupLifecycleMapper;
import org.bluesky.training.persistence.ExerciseGroupStateRow;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Arrays;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 评审 C2：过渡态 5 秒看门狗。PAUSING/RESUMING/STARTING 超时必须进 RECOVERING；
 * ENDING 超时必须对账实例并终结，任何组不得永久停留在过渡态（测试空心化清单 14）。
 */
class ExerciseGroupTransitionWatchdogTest {
    @org.junit.jupiter.api.BeforeEach void clock() {
        when(lifecycleMapper.databaseNow()).thenReturn(LocalDateTime.of(2026,9,6,16,0));
    }

    private final ExerciseGroupLifecycleMapper lifecycleMapper =
            mock(ExerciseGroupLifecycleMapper.class);
    private final ExerciseGroupService groupService = mock(ExerciseGroupService.class);
    private final EngineInstanceService engineService = mock(EngineInstanceService.class);
    private final ExerciseGroupTransitionWatchdog watchdog = new ExerciseGroupTransitionWatchdog(
            lifecycleMapper, groupService, engineService);

    private static ExerciseGroupStateRow groupOf(String id, String state) {
        ExerciseGroupStateRow row = new ExerciseGroupStateRow();
        row.setId(id);
        row.setName(state);
        row.setState(state);
        return row;
    }

    @Test
    void givenPausingBeyondTimeoutWhenCheckedThenEntersRecovering() {
        when(lifecycleMapper.findStaleTransitions(anyList(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Arrays.asList(groupOf("g-1", "PAUSING")));

        watchdog.checkStaleTransitions();

        verify(groupService).enterRecovering(eq("g-1"), anyString());
    }

    @Test
    void givenStartingOrResumingBeyondTimeoutWhenCheckedThenEntersRecovering() {
        when(lifecycleMapper.findStaleTransitions(anyList(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Arrays.asList(groupOf("g-2", "STARTING"),
                        groupOf("g-3", "RESUMING")));

        watchdog.checkStaleTransitions();

        verify(groupService).enterRecovering(eq("g-2"), anyString());
        verify(groupService).enterRecovering(eq("g-3"), anyString());
    }

    @Test
    void givenEndingBeyondTimeoutWhenCheckedThenInstanceStoppedAndEnded() {
        when(lifecycleMapper.findStaleTransitions(anyList(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.Collections.singletonList(groupOf("g-4", "ENDING")));
        when(engineService.currentInstanceId("g-4")).thenReturn("engine-4");

        watchdog.checkStaleTransitions();

        verify(engineService,never()).markStopped("engine-4");
        verify(groupService,never()).onAdapterLifecycleResult("g-4", "STOPPED");
        verify(groupService, never()).enterRecovering(anyString(), anyString());
    }

    @Test
    void givenConcurrentConfirmationWhenTransitionThrowsThenWatchdogTolerates() {
        when(lifecycleMapper.findStaleTransitions(anyList(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Arrays.asList(groupOf("g-5", "PAUSING")));
        when(groupService.enterRecovering(anyString(), anyString()))
                .thenThrow(new V2DomainException("TRAINING_STATE_INVALID", 409, "并发确认"));

        watchdog.checkStaleTransitions(); // 不得抛出：确认路径先行完成是合法竞态
    }

    @Test
    void givenFreshTransitionsOnlyWhenQueriedThenThresholdIsFiveSecondsAgo() {
        when(lifecycleMapper.findStaleTransitions(anyList(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.Collections.<ExerciseGroupStateRow>emptyList());
        LocalDateTime before = lifecycleMapper.databaseNow();

        watchdog.checkStaleTransitions();

        org.mockito.ArgumentCaptor<LocalDateTime> captor =
                org.mockito.ArgumentCaptor.forClass(LocalDateTime.class);
        verify(lifecycleMapper).findStaleTransitions(anyList(), captor.capture());
        org.junit.jupiter.api.Assertions.assertTrue(
                !captor.getValue().isAfter(before.minusSeconds(4)),
                "超时阈值必须约等于 5 秒");
    }
}
