package org.bluesky.training.instruction;

import org.bluesky.training.common.V2DomainException;
import org.bluesky.training.persistence.InstructionV2Mapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * P09：指令派发 5 秒技术确认看门狗（评审 P0-6；详细设计 6.3.5/6.3.6）。
 * DISPATCHING 超窗口未确认 → ADAPTER_ACK_TIMEOUT 终结 TIMED_OUT 并释放占位；
 * 与 Adapter 确认路径的竞态由状态 CAS 吸收。
 */
@Component
public class InstructionDispatchWatchdog {

    private final InstructionV2Mapper mapper;
    private final InstructionDispatchService dispatchService;

    public InstructionDispatchWatchdog(InstructionV2Mapper mapper,
                                       InstructionDispatchService dispatchService) {
        this.mapper = mapper;
        this.dispatchService = dispatchService;
    }

    @Scheduled(fixedDelay = 1000)
    public void timeoutStaleDispatches() {
        java.time.LocalDateTime now = mapper.databaseNow();
        // 截止缺失兜底：创建超过 30 秒仍在 DISPATCHING 的历史指令也超时
        java.time.LocalDateTime grace = now.minusSeconds(30);
        for (String instructionId : mapper.findDispatchTimedOut(now, grace)) {
            try {
                dispatchService.timeout(instructionId);
            } catch (V2DomainException overtaken) {
                // 与确认/拒绝路径并发：CAS 失败说明 Adapter 响应先到，忽略
            }
        }
    }

    @Scheduled(fixedDelay = 500)
    public void dispatchDueInstructions() {
        for(java.util.Map<String,Object> row:mapper.scheduledInstructions()) {
            try {
                com.fasterxml.jackson.databind.JsonNode p=new com.fasterxml.jackson.databind.ObjectMapper().readTree(String.valueOf(row.get("parsed_payload")));
                if(p.path("scheduledTimeSeconds").asDouble(Double.POSITIVE_INFINITY)<=((Number)row.get("simulation_time_seconds")).doubleValue())
                    dispatchService.releaseAndDispatch(String.valueOf(row.get("id")),"SCHEDULED_TIME_NOT_REACHED");
            } catch(java.io.IOException invalid) {throw new IllegalStateException("持久化指令参数损坏",invalid);}
        }
    }
}
