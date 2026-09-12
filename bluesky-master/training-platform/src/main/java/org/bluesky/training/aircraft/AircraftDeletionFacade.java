package org.bluesky.training.aircraft;

import org.bluesky.training.adapter.SimulationGateway;
import org.bluesky.training.common.CallerContext;
import org.bluesky.training.common.V2DomainException;
import org.bluesky.training.instruction.DeletionPreviewService;
import org.bluesky.training.persistence.AircraftV2Mapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * v1 工作台删除按钮的桥模式门面（预构建 UI 固定调用 DELETE /api/v1/aircraft/{id}，
 * 没有预览确认界面）：DELETE 作用于明确航空器本身就是显式确认，服务端在单请求内
 * 完成 v2 删除 Saga 的 token 签发/原子消费 + 删除请求 +（桥模式）同步确认。
 * 不绕过 Saga 约束（评审 D5 指责的 current_key/活动指令残留由 confirmDeleted
 * 的确认事务清理语义保证）；demo 桥没有 Outbox worker 消费 AIRCRAFT_DELETE，
 * 若不同步确认会遗留 DELETE_REQUESTED 被 5 秒看门狗误判 DELETE_FAILED。
 */
@Service
public class AircraftDeletionFacade {

    private final AircraftDeletionSaga deletionSaga;
    private final DeletionPreviewService previewService;
    private final AircraftV2Mapper aircraftMapper;
    private final SimulationGateway simulationGateway;
    private final boolean v1BridgeEnabled;
    private final boolean outboxWorkersEnabled;

    public AircraftDeletionFacade(AircraftDeletionSaga deletionSaga,
                                  DeletionPreviewService previewService,
                                  AircraftV2Mapper aircraftMapper,
                                  SimulationGateway simulationGateway,
                                  @Value("${bluesky.adapter.v1-bridge-enabled:false}")
                                          boolean v1BridgeEnabled,
                                  @Value("${bluesky.outbox.workers-enabled:true}")
                                          boolean outboxWorkersEnabled) {
        this.deletionSaga = deletionSaga;
        this.previewService = previewService;
        this.aircraftMapper = aircraftMapper;
        this.simulationGateway = simulationGateway;
        this.v1BridgeEnabled = v1BridgeEnabled;
        this.outboxWorkersEnabled = outboxWorkersEnabled;
    }

    /** 预览 token 服务端签发并即刻原子消费：终端/版本/重放约束（评审 P0-5）全部生效。 */
    @Transactional
    public Map<String, Object> deleteNow(CallerContext caller, String aircraftId) {
        Map<String, Object> aircraft = requireAircraft(aircraftId);
        long revision = ((Number) aircraft.get("revision")).longValue();
        Map<String, Object> preview = previewService.createPreview(
                caller, aircraftId, revision, null);
        previewService.validateAndConsumeToken(
                String.valueOf(preview.get("confirmationToken")),
                aircraftId, revision, caller.terminalId());
        Map<String, Object> envelope = deletionSaga.requestDelete(caller, aircraftId, null);
        if (v1BridgeEnabled && !outboxWorkersEnabled
                && Boolean.TRUE.equals(envelope.get("adapterPending"))) {
            // 桥模式同步替代 Outbox 消费：v1 网关删实体后走 Adapter 确认回路
            // 的同一收尾（D1：取消指令、清引导、结束分配、写 DELETED 同事务）
            simulationGateway.deleteAircraft(String.valueOf(aircraft.get("callsign")));
            return deletionSaga.confirmDeleted(aircraftId);
        }
        return envelope;
    }

    private Map<String, Object> requireAircraft(String aircraftId) {
        Map<String, Object> aircraft = aircraftId == null ? null
                : aircraftMapper.findById(aircraftId);
        if (aircraft == null) {
            throw new V2DomainException("TERMINAL_NOT_FOUND", 404, "航空器不存在: " + aircraftId);
        }
        return aircraft;
    }
}
