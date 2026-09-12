package org.bluesky.training.aircraft;

import org.bluesky.training.common.CallerContext;
import org.bluesky.training.common.CallerContextResolver;
import org.bluesky.training.common.IdempotentHttpExecutor;
import org.bluesky.training.common.RevisionHeaders;
import org.bluesky.training.common.V2Api;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/** P07：v2 航空器资源接口（详细设计 2.2 §9.2/9.3）。 */
@V2Api
@RestController
@RequestMapping("/api/v2")
public class AircraftControllerV2 {

    private final CallerContextResolver resolver;
    private final AircraftApplicationService applicationService;
    private final IdempotentHttpExecutor idempotentHttpExecutor;

    public AircraftControllerV2(CallerContextResolver resolver,
                                AircraftApplicationService applicationService,
                                IdempotentHttpExecutor idempotentHttpExecutor) {
        this.resolver = resolver;
        this.applicationService = applicationService;
        this.idempotentHttpExecutor = idempotentHttpExecutor;
    }

    @PostMapping("/exercise-groups/{groupId}/aircraft")
    public ResponseEntity<Object> create(@PathVariable("groupId") String groupId,
                                         @RequestBody Map<String, Object> payload,
                                         HttpServletRequest request,
                                         @RequestHeader(value = "Idempotency-Key", required = false)
                                         String key) {
        CallerContext caller = resolver.resolveTerminal(request);
        String path = "/api/v2/exercise-groups/" + groupId + "/aircraft";
        ResponseEntity<Object> response = idempotentHttpExecutor.execute(
                caller, "POST", path, key, payload, 201,
                () -> applicationService.createPlan(caller, groupId, payload));
        Object body = response.getBody();
        if (!(body instanceof Map) || ((Map<?, ?>) body).get("id") == null) {
            return response;
        }
        String aircraftId = String.valueOf(((Map<?, ?>) body).get("id"));
        return ResponseEntity.status(response.getStatusCode())
                .headers(response.getHeaders())
                .location(URI.create("/api/v2/aircraft/" + aircraftId))
                .body(body);
    }

    @GetMapping("/exercise-groups/{groupId}/aircraft")
    public Map<String, Object> list(@PathVariable("groupId") String groupId,
                                    HttpServletRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", applicationService.listByGroup(
                resolver.resolveTerminal(request), groupId));
        return body;
    }

    @GetMapping("/aircraft/{aircraftId}")
    public Map<String, Object> get(@PathVariable("aircraftId") String aircraftId,
                                   HttpServletRequest request) {
        return applicationService.get(resolver.resolveTerminal(request), aircraftId);
    }

    @PatchMapping("/aircraft/{aircraftId}/flight-plan")
    public ResponseEntity<Object> patchFlightPlan(
            @PathVariable("aircraftId") String aircraftId,
            @RequestBody Map<String, Object> payload,
            HttpServletRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        CallerContext caller = resolver.resolveTerminal(request);
        String path = "/api/v2/aircraft/" + aircraftId + "/flight-plan";
        return idempotentHttpExecutor.execute(caller, "PATCH", path, key, payload, 200,
                () -> applicationService.patchPlannedFlightPlan(caller, aircraftId,
                        RevisionHeaders.requireIfMatch(ifMatch), payload));
    }

    /** 取消未出现计划（本地直达 DELETED；活动航空器删除走 P15 Saga）。 */
    // DELETE 由 P15 AircraftDeleteControllerV2 在删除预览 token 校验后统一暴露；
    // P07 不得提供绕过预览与删除 Saga 的第二入口。
}
