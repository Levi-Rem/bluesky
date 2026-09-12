package org.bluesky.training.instruction;

import org.bluesky.training.common.CallerContextResolver;
import org.bluesky.training.common.V2Api;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;

/** P09：v2 指令接口（详细设计 2.2 §9.2/9.4：text 与 command 二选一）。 */
@V2Api
@RestController
@RequestMapping("/api/v2")
public class InstructionControllerV2 {

    private final CallerContextResolver resolver;
    private final InstructionApplicationService applicationService;
    @org.springframework.beans.factory.annotation.Autowired private org.bluesky.training.persistence.AircraftV2Mapper aircraft;
    @org.springframework.beans.factory.annotation.Autowired private org.bluesky.training.common.TerminalAccessPolicy access;

    public InstructionControllerV2(CallerContextResolver resolver,
                                   InstructionApplicationService applicationService) {
        this.resolver = resolver;
        this.applicationService = applicationService;
    }

    @PostMapping("/aircraft/{aircraftId}/instructions")
    public ResponseEntity<Map<String, Object>> submit(@PathVariable("aircraftId") String aircraftId,
                                                      @RequestBody Map<String, Object> payload,
                                                      @RequestHeader(value = "Idempotency-Key",
                                                              required = false)
                                                              String idempotencyKey,
                                                      HttpServletRequest request) {
        Object revision = payload.get("aircraftRevision");
        if (revision == null || String.valueOf(revision).trim().isEmpty()) {
            throw new org.bluesky.training.common.V2DomainException(
                    "REVISION_REQUIRED", 428, "缺少 aircraftRevision",
                    java.util.Collections.singletonList("aircraftRevision"));
        }
        Map<String, Object> command = commandOf(payload);
        // 幂等键只认 Idempotency-Key 请求头（openapi-v2.yaml：in:header；详细设计 9.1），
        // 请求体同名字段不作为幂等依据（评审 P0-13/E5）
        command.put("idempotencyKey", normalizeKey(idempotencyKey));
        return ResponseEntity.status(202).body(applicationService.submit(
                resolver.resolveTerminal(request), aircraftId, command));
    }

    @GetMapping("/aircraft/{aircraftId}/instructions")
    public Map<String, Object> list(@PathVariable("aircraftId") String aircraftId) {
        Map<String,Object> row=aircraft.findById(aircraftId);
        if(row==null)throw new org.bluesky.training.common.V2DomainException("AIRCRAFT_NOT_FOUND",404,"航空器不存在");
        access.requireSameGroup(resolver.resolveCurrent(),String.valueOf(row.get("exercise_group_id")));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", applicationService.list(aircraftId));
        return body;
    }

    @GetMapping("/instructions/{instructionId}")
    public Map<String, Object> get(@PathVariable("instructionId") String instructionId) {
        Map<String,Object> row=applicationService.get(instructionId);
        access.requireSameGroup(resolver.resolveCurrent(),String.valueOf(row.get("exercise_group_id")));
        return row;
    }

    @PostMapping("/instructions/{instructionId}/actions/cancel")
    public Map<String, Object> cancel(@PathVariable("instructionId") String instructionId,
                                      @RequestBody Map<String, Object> payload,
                                      @RequestHeader(value = "Idempotency-Key",
                                              required = false) String idempotencyKey,
                                      HttpServletRequest request) {
        // 取消幂等键同样走请求头（详细设计 9.1）；取消的重复提交由指令状态机拒绝
        long revision=org.bluesky.training.common.RevisionHeaders.requireIfMatch(
                payload.get("instructionRevision")==null?null:String.valueOf(payload.get("instructionRevision")));
        return applicationService.cancel(resolver.resolveTerminal(request), instructionId,revision);
    }

    /** text 与 command 必须二选一（详细设计 9.4）：命令目录类型优先结构化。 */
    private static Map<String, Object> commandOf(Map<String, Object> payload) {
        if ((payload.get("text")!=null)==(payload.get("command")!=null) ||
                (payload.get("command")!=null && !(payload.get("command") instanceof Map))) {
            throw new org.bluesky.training.common.V2DomainException("INVALID_INSTRUCTION",400,"text 与 command 必须且只能提供一个");
        }
        Map<String, Object> command = new LinkedHashMap<>(payload);
        Object structured = payload.get("command");
        if (structured instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) structured;
            for (Map.Entry<String, Object> entry : typed.entrySet()) {
                command.put(entry.getKey(), entry.getValue());
            }
            command.remove("command");
        }
        return command;
    }

    private static String normalizeKey(String idempotencyKey) {
        if (idempotencyKey == null) {
            return null;
        }
        String trimmed = idempotencyKey.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
