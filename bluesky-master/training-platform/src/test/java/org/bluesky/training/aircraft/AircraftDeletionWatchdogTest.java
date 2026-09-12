package org.bluesky.training.aircraft;

import org.bluesky.training.common.TransactionalOutboxService;
import org.bluesky.training.persistence.AircraftV2Mapper;
import org.junit.jupiter.api.Test;
import org.bluesky.training.adapter.EngineInstanceService;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 评审 P0-4：删除确认 5 秒看门狗与重启对账。 */
class AircraftDeletionWatchdogTest {
    @org.junit.jupiter.api.BeforeEach void clock() {
        when(aircraftMapper.databaseNow()).thenReturn(LocalDateTime.of(2026,9,6,16,0));
    }

    private final AircraftV2Mapper aircraftMapper = mock(AircraftV2Mapper.class);
    private final AircraftDeletionSaga saga = mock(AircraftDeletionSaga.class);
    private final TransactionalOutboxService outboxService =
            mock(TransactionalOutboxService.class);
    private final EngineInstanceService engineInstanceService =
            mock(EngineInstanceService.class);
    private final AircraftDeletionWatchdog watchdog = new AircraftDeletionWatchdog(
            aircraftMapper, saga, outboxService, engineInstanceService);

    @Test
    void givenDeleteRequestedBeyondTimeoutWhenCheckedThenMarkedFailed() {
        watchdog.resumeIncompleteSagasAfterRestart();
        when(aircraftMapper.findDeleteRequestedSince(any(LocalDateTime.class)))
                .thenReturn(Arrays.asList("ac-1", "ac-2"));

        watchdog.timeoutUnconfirmedDeletes();

        verify(saga).markDeleteFailed(eq("ac-1"), eq("DELETE_ACK_TIMEOUT"), anyString());
        verify(saga).markDeleteFailed(eq("ac-2"), eq("DELETE_ACK_TIMEOUT"), anyString());
    }

    @Test
    void givenConcurrentConfirmationWhenMarkingFailedThenTolerated() {
        watchdog.resumeIncompleteSagasAfterRestart();
        when(aircraftMapper.findDeleteRequestedSince(any(LocalDateTime.class)))
                .thenReturn(Arrays.asList("ac-3"));
        when(saga.markDeleteFailed(anyString(), anyString(), anyString()))
                .thenThrow(new org.bluesky.training.common.V2DomainException(
                        "REVISION_CONFLICT", 409, "并发确认"));

        watchdog.timeoutUnconfirmedDeletes(); // 不得抛出
    }

    @Test
    void givenPendingDeleteOnStartupWhenReconciledThenExistsQueryEnqueued() {
        String aircraftId = "ac-4";
        when(aircraftMapper.findDeleteRequestedSince(any(LocalDateTime.class)))
                .thenReturn(Arrays.asList(aircraftId));
        java.util.Map<String, Object> aircraft = new java.util.LinkedHashMap<>();
        aircraft.put("exercise_group_id", "g-4");
        aircraft.put("callsign","TEST4");
        when(aircraftMapper.findById(aircraftId)).thenReturn(aircraft);
        when(engineInstanceService.currentInstanceId("g-4")).thenReturn("engine-4");

        watchdog.resumeIncompleteSagasAfterRestart();

        verify(outboxService).enqueueAdapterAction(
                org.mockito.ArgumentMatchers.argThat(row ->
                        row != null && "AIRCRAFT_EXISTS_GET".equals(row.getEventType())
                                && "g-4".equals(row.getExerciseGroupId())));
    }

    @Test
    void givenNoPendingDeleteOnStartupWhenReconciledThenNothingEnqueued() {
        when(aircraftMapper.findDeleteRequestedSince(any(LocalDateTime.class)))
                .thenReturn(java.util.Collections.<String>emptyList());

        watchdog.resumeIncompleteSagasAfterRestart();

        verify(outboxService, never()).enqueueAdapterAction(any());
    }

    private static String anyId() {
        return UUID.randomUUID().toString();
    }
}
