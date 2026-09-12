package org.bluesky.training.assignment;

import org.bluesky.training.common.CallerContextResolver;
import org.bluesky.training.common.CallerContext;
import org.bluesky.training.common.IdempotentHttpExecutor;
import org.bluesky.training.common.V2Api;
import org.bluesky.training.common.V2DomainException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;

/** P08：v2 移交与责任查询（详细设计 2.2 §9.2：POST handover / GET assignments）。 */
@V2Api
@RestController
@RequestMapping("/api/v2")
public class HandoverControllerV2 {

    private final CallerContextResolver resolver;
    private final HandoverService handoverService;
    private final IdempotentHttpExecutor idempotentHttpExecutor;

    public HandoverControllerV2(CallerContextResolver resolver,
                                HandoverService handoverService,
                                IdempotentHttpExecutor idempotentHttpExecutor) {
        this.resolver = resolver;
        this.handoverService = handoverService;
        this.idempotentHttpExecutor = idempotentHttpExecutor;
    }

    @PostMapping("/aircraft/{aircraftId}/handover")
    public ResponseEntity<Object> handover(
            @PathVariable("aircraftId") String aircraftId,
            @RequestBody Map<String, Object> payload,
            HttpServletRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        CallerContext caller = resolver.resolveTerminal(request);
        String path = "/api/v2/aircraft/" + aircraftId + "/handover";
        return idempotentHttpExecutor.execute(caller, "POST", path, key, payload, 200,
                () -> handoverService.handoverByFrequency(caller, aircraftId,
                        requiredRevision(payload.get("aircraftRevision")),
                        requiredFrequency(payload.get("targetFrequencyMhz"))));
    }

    @GetMapping("/exercise-groups/{groupId}/assignments")
    public Map<String, Object> assignments(@PathVariable("groupId") String groupId,
                                           HttpServletRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", handoverService.assignments(
                resolver.resolveTerminal(request), groupId));
        return body;
    }

    private static long requiredRevision(Object value) {
        if (value == null || String.valueOf(value).trim().isEmpty()) {
            throw new V2DomainException("REVISION_REQUIRED", 428,
                    "缺少 aircraftRevision",
                    java.util.Collections.singletonList("aircraftRevision"));
        }
        if (!(value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long || value instanceof java.math.BigInteger)) {
            throw new V2DomainException("REVISION_REQUIRED", 428,
                    "aircraftRevision 必须是非负整数",
                    java.util.Collections.singletonList("aircraftRevision"));
        }
        long revision = ((Number) value).longValue();
        if (revision < 0) {
            throw new V2DomainException("REVISION_REQUIRED", 428,
                    "aircraftRevision 必须是非负整数",
                    java.util.Collections.singletonList("aircraftRevision"));
        }
        return revision;
    }

    private static double requiredFrequency(Object value) {
        if (!(value instanceof Number)) {
            throw new V2DomainException("INVALID_INSTRUCTION", 400,
                    "targetFrequencyMhz 必须是数值",
                    java.util.Collections.singletonList("targetFrequencyMhz"));
        }
        double frequency = ((Number) value).doubleValue();
        if (!Double.isFinite(frequency) || frequency <= 0 || frequency >= 1000) {
            throw new V2DomainException("INVALID_INSTRUCTION", 400,
                    "targetFrequencyMhz 必须是 0–1000 之间的有限 MHz 数值",
                    java.util.Collections.singletonList("targetFrequencyMhz"));
        }
        return frequency;
    }
}
