package org.bluesky.training.aircraft;

import org.bluesky.training.common.CallerContextResolver;
import org.bluesky.training.common.V2Api;
import org.bluesky.training.event.EventStreamService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.Map;

/**
 * v1 工作台删除入口（预构建 UI 固定调用）：委托 AircraftDeletionFacade 在
 * 单请求内走完 v2 删除 Saga（token 签发/消费 → 删除请求 → 桥模式同步确认），
 * 不存在绕过预览与 Saga 的物理删行路径（评审 D5）。
 * 挂 @V2Api 仅为复用 v2 错误信封（403/404/409），路由仍是 /api/v1。
 */
@V2Api
@RestController
@RequestMapping("/api/v1/aircraft")
public class AircraftDeleteController {

    private final CallerContextResolver resolver;
    private final AircraftDeletionFacade deletionFacade;
    private final EventStreamService eventStreamService;

    public AircraftDeleteController(CallerContextResolver resolver,
                                    AircraftDeletionFacade deletionFacade,
                                    EventStreamService eventStreamService) {
        this.resolver = resolver;
        this.deletionFacade = deletionFacade;
        this.eventStreamService = eventStreamService;
    }

    @DeleteMapping("/{aircraftId}")
    @ResponseStatus(HttpStatus.OK)
    public Map<String, Object> delete(@PathVariable String aircraftId,
                                      HttpServletRequest request) {
        Map<String, Object> envelope = deletionFacade.deleteNow(
                resolver.resolveTerminal(request), aircraftId);
        // 预构建工作台监听 aircraft-deleted({id}) 同步其余会话的列表
        eventStreamService.publishAfterCommit("aircraft-deleted",
                Collections.singletonMap("id", aircraftId));
        return envelope;
    }
}
