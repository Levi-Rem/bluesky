package org.bluesky.training.exercise;

import org.bluesky.training.common.CallerContextResolver;
import org.bluesky.training.common.CallerContext;
import org.bluesky.training.common.IdempotentHttpExecutor;
import org.bluesky.training.common.V2Api;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;

/** P05：v2 训练组管理接口（仅运维服务身份，详细设计 2.2 §9.2）。 */
@V2Api
@RestController
@RequestMapping("/api/v2/exercise-groups")
public class ExerciseGroupAdminControllerV2 {

    private final CallerContextResolver resolver;
    private final ExerciseGroupProvisioningService provisioningService;
    private final IdempotentHttpExecutor idempotentHttpExecutor;

    public ExerciseGroupAdminControllerV2(CallerContextResolver resolver,
                                         ExerciseGroupProvisioningService provisioningService,
                                         IdempotentHttpExecutor idempotentHttpExecutor) {
        this.resolver = resolver;
        this.provisioningService = provisioningService;
        this.idempotentHttpExecutor = idempotentHttpExecutor;
    }

    @GetMapping
    public Map<String, Object> list(HttpServletRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", provisioningService.listGroups(resolver.resolveService(request)));
        return body;
    }

    @PostMapping
    public ResponseEntity<Object> create(@RequestBody Map<String, Object> payload,
                                         HttpServletRequest request,
                                         @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        Object name = payload.get("name");
        CallerContext caller = resolver.resolveService(request);
        return idempotentHttpExecutor.execute(caller, "POST", "/api/v2/exercise-groups",
                key, payload, 201, () -> provisioningService.createGroup(
                        caller, name == null ? null : String.valueOf(name)));
    }
}
