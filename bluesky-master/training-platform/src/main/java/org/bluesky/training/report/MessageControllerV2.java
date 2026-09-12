package org.bluesky.training.report;

import org.bluesky.training.common.CallerContext;
import org.bluesky.training.common.CallerContextResolver;
import org.bluesky.training.common.V2Api;
import org.bluesky.training.common.V2DomainException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** P17 v2 终端消息接口（详细设计 2.2 §8.8/§9.2）：入站、已读、终端级软删除。 */
@V2Api
@RestController
@RequestMapping("/api/v2")
public class MessageControllerV2 {

    private final CallerContextResolver resolver;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    @org.springframework.beans.factory.annotation.Autowired private org.bluesky.training.common.IdempotentHttpExecutor idempotency;
    @org.springframework.beans.factory.annotation.Autowired private org.bluesky.training.event.BusinessEventService events;

    public MessageControllerV2(CallerContextResolver resolver,
                               org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.resolver = resolver;
        this.jdbc = jdbc;
    }

    @PostMapping("/exercise-groups/{groupId}/messages")
    public ResponseEntity<Object> receive(
            @PathVariable("groupId") String groupId,
            @RequestBody Map<String, Object> payload,
            HttpServletRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        CallerContext caller=resolver.resolveService(request);
        return idempotency.execute(caller,"POST","/api/v2/exercise-groups/"+groupId+"/messages",key,payload,201,()->receiveBody(groupId,payload));
    }

    private Map<String,Object> receiveBody(String groupId,Map<String,Object> payload) {
        String body_ = text(payload.get("content"));
        if (body_ == null) {
            throw new V2DomainException("INVALID_INSTRUCTION", 400, "content 不能为空",
                    Arrays.asList("content"));
        }
        Object targets = payload.get("targetTerminalIds");
        if (!(targets instanceof java.util.List) || ((java.util.List<?>) targets).isEmpty()) {
            throw new V2DomainException("INVALID_INSTRUCTION", 400, "targetTerminalIds 不能为空",
                    Arrays.asList("targetTerminalIds"));
        }
        Map<String, Object> created = null;
        for (Object terminalId : (java.util.List<?>) targets) {
            Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM workstation_terminal WHERE id=? AND exercise_group_id=? AND enabled=TRUE",Integer.class,terminalId,groupId);
            if(count==null || count!=1)throw new V2DomainException("TERMINAL_NOT_IN_GROUP",403,"消息目标终端不属于训练组");
            String id = UUID.randomUUID().toString();
            jdbc.update("INSERT INTO terminal_message (id, exercise_group_id, "
                            + "target_terminal_id, sender_source, body) VALUES (?, ?, ?, ?, ?)",
                    id, groupId, String.valueOf(terminalId),
                    text(payload.get("senderSource")) == null ? "EXERCISE_ORCHESTRATOR"
                            : String.valueOf(payload.get("senderSource")), body_);
            created = new LinkedHashMap<>();
            created.put("id", id);
            created.put("targetTerminalId", terminalId);
            created.put("content",body_);
            try {
                events.append(groupId,"message.received",new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(created),java.util.Collections.singletonList(String.valueOf(terminalId)));
            } catch(java.io.IOException invalid){throw new IllegalStateException(invalid);}
        }
        return created;
    }

    @GetMapping("/workstations/{terminalId}/messages")
    public Map<String, Object> list(@PathVariable("terminalId") String terminalId,
                                    HttpServletRequest request) {
        CallerContext caller = resolver.resolveTerminal(request);
        requireOwn(caller, terminalId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", jdbc.queryForList(
                "SELECT id, target_terminal_id, sender_source, body, status, revision, "
                        + "created_at FROM terminal_message WHERE target_terminal_id = ? "
                        + "AND deleted_at IS NULL ORDER BY created_at DESC", terminalId));
        return body;
    }

    @PostMapping("/messages/{messageId}/actions/read")
    public Map<String, Object> read(@PathVariable("messageId") String messageId,
                                    @RequestBody Map<String, Object> payload,
                                    HttpServletRequest request,
                                    @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        CallerContext caller = resolver.resolveTerminal(request);
        Map<String, Object> message = requireMessage(messageId);
        requireOwn(caller, String.valueOf(message.get("target_terminal_id")));
        jdbc.update("UPDATE terminal_message SET status = 'READ', read_at = CURRENT_TIMESTAMP(3), "
                + "revision = revision + 1 WHERE id = ? AND status = 'UNREAD'", messageId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", messageId);
        body.put("status", "READ");
        body.put("revision", jdbc.queryForObject(
                "SELECT revision FROM terminal_message WHERE id = ?", Long.class, messageId));
        return body;
    }

    @DeleteMapping("/messages/{messageId}")
    public ResponseEntity<Void> delete(@PathVariable("messageId") String messageId,
                                      HttpServletRequest request,
                                      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        CallerContext caller = resolver.resolveTerminal(request);
        Map<String, Object> message = requireMessage(messageId);
        requireOwn(caller, String.valueOf(message.get("target_terminal_id")));
        if (!"DELETED".equals(message.get("status"))) {
            jdbc.update("UPDATE terminal_message SET status = 'DELETED', "
                    + "deleted_at = CURRENT_TIMESTAMP(3), revision = revision + 1 WHERE id = ?",
                    messageId);
        }
        return ResponseEntity.noContent().build();
    }

    private Map<String, Object> requireMessage(String messageId) {
        Map<String, Object> message = jdbc.queryForMap(
                "SELECT id, target_terminal_id, status FROM terminal_message WHERE id = ?",
                messageId);
        if (message == null) {
            throw new V2DomainException("TERMINAL_NOT_FOUND", 404, "消息不存在: " + messageId);
        }
        return message;
    }

    private static void requireOwn(CallerContext caller, String terminalId) {
        if (!caller.terminalId().equals(terminalId)) {
            throw new V2DomainException("TERMINAL_NOT_IN_GROUP", 403,
                    "只能操作自己终端的消息", Arrays.asList("terminalId"));
        }
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }
}
