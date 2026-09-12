package org.bluesky.training.aircraft;

import org.bluesky.training.common.CallerContext;
import org.bluesky.training.common.CallerContextResolver;
import org.bluesky.training.common.ServiceAccessPolicy;
import org.bluesky.training.common.V2Api;
import org.bluesky.training.common.V2DomainException;
import org.bluesky.training.instruction.DeletionPreviewService;
import org.bluesky.training.persistence.AircraftV2Mapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

/**
 * P15：v2 删除入口（评审 P0-3；详细设计 9.2 接口表 / 7.9 全流程）。
 * preview → X-Confirmation-Token 原子消费 → DELETE → Adapter 确认回路；
 * retry/cancel 与取消未出现计划入口一并暴露。P07 期间不得存在绕过
 * 预览与 Saga 的第二删除入口。
 */
@V2Api
@RestController
@RequestMapping("/api/v2/aircraft")
public class AircraftDeleteControllerV2 {

    private final CallerContextResolver resolver;
    private final DeletionPreviewService previewService;
    private final AircraftDeletionSaga deletionSaga;
    private final AircraftApplicationService applicationService;
    private final ServiceAccessPolicy serviceAccessPolicy;
    private final AircraftV2Mapper aircraftMapper;

    public AircraftDeleteControllerV2(CallerContextResolver resolver,
                                      DeletionPreviewService previewService,
                                      AircraftDeletionSaga deletionSaga,
                                      AircraftApplicationService applicationService,
                                      ServiceAccessPolicy serviceAccessPolicy,
                                      AircraftV2Mapper aircraftMapper) {
        this.resolver = resolver;
        this.previewService = previewService;
        this.deletionSaga = deletionSaga;
        this.applicationService = applicationService;
        this.serviceAccessPolicy = serviceAccessPolicy;
        this.aircraftMapper = aircraftMapper;
    }

    /** 删除预览：返回摘要与 30 秒一次性确认 token（不回摘要，评审 P0-5）。 */
    @PostMapping("/{aircraftId}/deletion-preview")
    public Map<String, Object> preview(@PathVariable("aircraftId") String aircraftId,
                                       @RequestBody Map<String, Object> payload,
                                       @RequestHeader(value = "Idempotency-Key",
                                               required = false) String idempotencyKey,
                                       @RequestHeader(value = "If-Match",
                                               required = false) String ifMatch,
                                       HttpServletRequest request) {
        CallerContext caller = resolver.resolveTerminal(request);
        return previewService.createPreview(caller, aircraftId,
                requireRevision(payload, ifMatch), normalize(idempotencyKey));
    }

    /** 删除：先原子消费一次性 token，再进入 Saga。 */
    @DeleteMapping("/{aircraftId}")
    public ResponseEntity<Map<String, Object>> delete(
            @PathVariable("aircraftId") String aircraftId,
            @RequestHeader(value = "X-Confirmation-Token") String confirmationToken,
            HttpServletRequest request) {
        CallerContext caller = resolver.resolveTerminal(request);
        long currentRevision = currentRevisionOf(aircraftId);
        previewService.validateAndConsumeToken(confirmationToken, aircraftId,
                currentRevision, caller.terminalId());
        return ResponseEntity.status(202).body(
                deletionSaga.requestDelete(caller, aircraftId, null));
    }

    /** 责任席重试失败删除（复用航空器维度幂等键，评审 D4）。 */
    @PostMapping("/{aircraftId}/retry-delete")
    public Map<String, Object> retryDelete(@PathVariable("aircraftId") String aircraftId,
                                           HttpServletRequest request) {
        CallerContext caller=resolver.resolveTerminal(request);
        applicationService.get(caller,aircraftId);
        Map<String,Object> assignment=aircraftMapper.findCurrentAssignment(aircraftId);
        if(assignment==null || !caller.terminalId().equals(String.valueOf(assignment.get("terminal_id"))))
            throw new V2DomainException("AIRCRAFT_NOT_ASSIGNED",403,"只有当前责任席可以重试删除");
        return deletionSaga.retry(aircraftId);
    }

    /** 仅训练编排方取消失败删除：body.adapterEntityExists 必须显式声明。 */
    @PostMapping("/{aircraftId}/cancel-delete")
    public Map<String, Object> cancelDelete(@PathVariable("aircraftId") String aircraftId,
                                            @RequestBody Map<String, Object> payload,
                                            HttpServletRequest request) {
        CallerContext caller = resolver.resolve(request);
        serviceAccessPolicy.requireOrchestrator(caller);
        Boolean exists = payload.get("adapterEntityExists") instanceof Boolean
                ? (Boolean) payload.get("adapterEntityExists") : null;
        if (exists == null) {
            throw new V2DomainException("INVALID_INSTRUCTION", 400,
                    "必须显式声明 adapterEntityExists（对账结果）",
                    Arrays.asList("adapterEntityExists"));
        }
        return deletionSaga.cancelFailedDelete(aircraftId, exists);
    }

    /** 取消未出现计划（PLANNED 本地直达 DELETED；活动航空器删除走 preview+Saga）。 */
    @PostMapping("/{aircraftId}/cancel-plan")
    public Map<String, Object> cancelPlan(@PathVariable("aircraftId") String aircraftId,
                                          HttpServletRequest request) {
        CallerContext caller = resolver.resolveTerminal(request);
        return applicationService.cancelPlannedAircraft(caller, aircraftId);
    }

    private long currentRevisionOf(String aircraftId) {
        Map<String, Object> aircraft = aircraftId == null ? null
                : aircraftMapper.findById(aircraftId);
        if (aircraft == null) {
            throw new V2DomainException("TERMINAL_NOT_FOUND", 404,
                    "航空器不存在: " + aircraftId);
        }
        return ((Number) aircraft.get("revision")).longValue();
    }

    private static long requireRevision(Map<String, Object> payload, String ifMatch) {
        Object fromBody = payload.get("aircraftRevision");
        if (fromBody instanceof Number) {
            return ((Number) fromBody).longValue();
        }
        if (ifMatch != null && !ifMatch.trim().isEmpty()) {
            return Long.parseLong(ifMatch.trim().replace("\"", ""));
        }
        throw new V2DomainException("REVISION_REQUIRED", 428, "缺少 aircraftRevision",
                Arrays.asList("aircraftRevision"));
    }

    private static String normalize(String key) {
        if (key == null) {
            return null;
        }
        String trimmed = key.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
