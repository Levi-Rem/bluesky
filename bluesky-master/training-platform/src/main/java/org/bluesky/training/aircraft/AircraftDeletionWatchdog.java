package org.bluesky.training.aircraft;

import org.bluesky.training.common.TransactionalOutboxService;
import org.bluesky.training.common.V2DomainException;
import org.bluesky.training.persistence.AircraftV2Mapper;
import org.bluesky.training.persistence.OutboxEventRow;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * P15：删除确认 5 秒看门狗 + 重启对账（评审 P0-4；详细设计 7.9/13.2.3）。
 * DELETE_REQUESTED 超 5 秒未确认 → DELETE_FAILED（可重试）；
 * 平台重启后对所有 DELETE_REQUESTED 行发起 AIRCRAFT_EXISTS_GET 对账。
 */
@Component
public class AircraftDeletionWatchdog {

    static final long DELETE_CONFIRM_TIMEOUT_MILLIS = 5_000L;

    private final AircraftV2Mapper aircraftMapper;
    private final AircraftDeletionSaga deletionSaga;
    private final TransactionalOutboxService outboxService;
    private final org.bluesky.training.adapter.EngineInstanceService engineInstanceService;
    private boolean initialized;
    private final java.util.Map<String,Long> reconcilingUntil=new java.util.concurrent.ConcurrentHashMap<>();

    public AircraftDeletionWatchdog(AircraftV2Mapper aircraftMapper,
                                    AircraftDeletionSaga deletionSaga,
                                    TransactionalOutboxService outboxService,
                                    org.bluesky.training.adapter.EngineInstanceService
                                            engineInstanceService) {
        this.aircraftMapper = aircraftMapper;
        this.deletionSaga = deletionSaga;
        this.outboxService = outboxService;
        this.engineInstanceService = engineInstanceService;
    }

    @Scheduled(fixedDelay = 1000)
    @Transactional
    public void timeoutUnconfirmedDeletes() {
        if (!initialized) resumeIncompleteSagasAfterRestart();
        LocalDateTime threshold = aircraftMapper.databaseNow()
                .minusNanos(DELETE_CONFIRM_TIMEOUT_MILLIS * 1_000_000);
        List<String> stale = aircraftMapper.findDeleteRequestedSince(threshold);
        for (String aircraftId : stale) {
            if (reconcilingUntil.getOrDefault(aircraftId,0L)>System.currentTimeMillis()) continue;
            try {
                deletionSaga.markDeleteFailed(aircraftId, "DELETE_ACK_TIMEOUT",
                        "删除确认超过 " + DELETE_CONFIRM_TIMEOUT_MILLIS + "ms 未到达");
            } catch (V2DomainException overtaken) {
                // 与确认路径并发：确认已把生命周期推走，忽略
            }
        }
    }

    /**
     * 重启对账：对仍在 DELETE_REQUESTED 的行发 AIRCRAFT_EXISTS_GET，
     * 响应由 AdapterActionResultService 驱动 reconcileUnknownResult。
     */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    @Transactional
    public synchronized void resumeIncompleteSagasAfterRestart() {
        if (initialized) return;
        List<String> pending = aircraftMapper.findDeleteRequestedSince(
                aircraftMapper.databaseNow().plusYears(100));
        for (String aircraftId : pending) {
            java.util.Map<String, Object> aircraft = aircraftMapper.findById(aircraftId);
            if (aircraft == null) {
                continue;
            }
            String groupId = String.valueOf(aircraft.get("exercise_group_id"));
            String outboxId = UUID.randomUUID().toString();
            outboxService.enqueueAdapterAction(OutboxEventRow.adapterAction(
                    outboxId, groupId, "AIRCRAFT_EXISTS_GET",
                    jsonAircraftId(aircraftId, String.valueOf(aircraft.get("callsign")))).routeTo(
                    engineInstanceService.currentInstanceId(groupId),
                    "req-" + outboxId, "aircraft-delete-reconcile:" + aircraftId + ":" + outboxId));
            reconcilingUntil.put(aircraftId,System.currentTimeMillis()+5000);
        }
        initialized=true;
    }

    private static String jsonAircraftId(String aircraftId, String callsign) {
        java.util.Map<String,Object> payload=new java.util.LinkedHashMap<>();payload.put("aircraftId",aircraftId);payload.put("callsign",callsign);
        try { return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(payload); }
        catch (java.io.IOException invalid) { throw new IllegalStateException(invalid); }
    }
}
