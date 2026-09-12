package org.bluesky.training.report;

import org.bluesky.training.common.CallerContext;
import org.bluesky.training.common.CallerContextResolver;
import org.bluesky.training.common.RevisionHeaders;
import org.bluesky.training.common.V2Api;
import org.bluesky.training.common.V2DomainException;
import org.springframework.http.ResponseEntity;
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

/**
 * P17 v2 脚本接口（详细设计 2.2 §8.8/§9.2）：
 * 编排方按仿真时间写入；目标终端确认（重复确认返回原时间）。
 */
@V2Api
@RestController
@RequestMapping("/api/v2")
public class ScriptControllerV2 {

    private final CallerContextResolver resolver;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    @org.springframework.beans.factory.annotation.Autowired private org.bluesky.training.common.IdempotentHttpExecutor idempotency;
    @org.springframework.beans.factory.annotation.Autowired private org.bluesky.training.common.TerminalAccessPolicy access;

    public ScriptControllerV2(CallerContextResolver resolver,
                              org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.resolver = resolver;
        this.jdbc = jdbc;
    }

    @PostMapping("/exercise-groups/{groupId}/scripts")
    public ResponseEntity<Object> create(
            @PathVariable("groupId") String groupId,
            @RequestBody Map<String, Object> payload,
            HttpServletRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        CallerContext caller=resolver.resolveService(request);
        return idempotency.execute(caller,"POST","/api/v2/exercise-groups/"+groupId+"/scripts",key,payload,201,()->createBody(groupId,payload));
    }

    private Map<String,Object> createBody(String groupId,Map<String,Object> payload) {
        java.util.List<String> enabled=jdbc.queryForList("SELECT id FROM workstation_terminal WHERE exercise_group_id=? AND enabled=TRUE",String.class,groupId);
        if(enabled.isEmpty())throw new V2DomainException("TERMINAL_NOT_FOUND",404,"训练组不存在或没有可投递终端");
        String content = text(payload.get("content"));
        if (content == null) {
            throw new V2DomainException("INVALID_INSTRUCTION", 400, "content 不能为空",
                    Arrays.asList("content"));
        }
        String id = UUID.randomUUID().toString();
        String targets = String.join(",",
                (java.util.List<String>) (payload.get("targetTerminalIds") instanceof java.util.List
                        ? payload.get("targetTerminalIds") : java.util.Collections.emptyList()));
        if(targets.isEmpty())targets=String.join(",",enabled);
        if(!enabled.containsAll(Arrays.asList(targets.split(","))))throw new V2DomainException("TERMINAL_NOT_IN_GROUP",403,"脚本目标终端不属于训练组");
        jdbc.update("INSERT INTO exercise_script_item (id, exercise_group_id, "
                        + "target_terminal_ids, trigger_simulation_time_seconds, severity, "
                        + "ack_required, content) VALUES (?, ?, ?, ?, ?, ?, ?)",
                id, groupId, targets,
                ((Number) orZero(payload.get("triggerSimulationTimeSeconds"))).doubleValue(),
                text(payload.get("severity")) == null ? "INFO"
                        : String.valueOf(payload.get("severity")).toUpperCase(),
                Boolean.TRUE.equals(payload.get("ackRequired")) ? 1 : 0,
                content);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("status", "PENDING");
        body.put("revision", 1L);
        return body;
    }

    @GetMapping("/exercise-groups/{groupId}/scripts")
    public Map<String, Object> list(@PathVariable("groupId") String groupId,
                                    HttpServletRequest request) {
        CallerContext caller = resolver.resolve(request);
        if(caller.terminalId()!=null)access.requireSameGroup(caller,groupId);
        Map<String, Object> body = new LinkedHashMap<>();
        java.util.List<Map<String,Object>> items=jdbc.queryForList(
                "SELECT id, target_terminal_ids, trigger_simulation_time_seconds, severity, "
                        + "ack_required, content, status, revision FROM exercise_script_item "
                        + "WHERE exercise_group_id = ? ORDER BY created_at DESC", groupId);
        if(caller.terminalId()!=null)items.removeIf(row->"PENDING".equals(row.get("status")) || !Arrays.asList(String.valueOf(row.get("target_terminal_ids")).split(",")).contains(caller.terminalId()));
        body.put("items",items);
        return body;
    }

    @PostMapping("/scripts/{scriptId}/actions/acknowledge")
    public Map<String, Object> acknowledge(@PathVariable("scriptId") String scriptId,
                                           @RequestBody Map<String, Object> payload,
                                           HttpServletRequest request,
                                           @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        CallerContext caller = resolver.resolveTerminal(request);
        java.util.List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM exercise_script_item WHERE id=?",scriptId);
        if (rows.isEmpty()) {
            throw new V2DomainException("TERMINAL_NOT_FOUND", 404, "脚本不存在: " + scriptId);
        }
        Map<String,Object> existing=rows.get(0);
        access.requireSameGroup(caller,String.valueOf(existing.get("exercise_group_id")));
        if(!Arrays.asList(String.valueOf(existing.get("target_terminal_ids")).split(",")).contains(caller.terminalId()))throw new V2DomainException("TERMINAL_NOT_IN_GROUP",403,"不是脚本目标终端");
        if("PENDING".equals(existing.get("status")))throw new V2DomainException("TRAINING_STATE_INVALID",409,"脚本尚未到达投递时间");
        if (!"ACKNOWLEDGED".equals(String.valueOf(existing.get("status")))) {
            jdbc.update("UPDATE exercise_script_item SET status = 'ACKNOWLEDGED', "
                            + "acknowledged_at = CURRENT_TIMESTAMP(3), acknowledged_by = ?, "
                            + "revision = revision + 1 WHERE id = ? AND status <> 'ACKNOWLEDGED'",
                    caller.terminalId(), scriptId);
            existing = jdbc.queryForMap(
                    "SELECT status, acknowledged_at, acknowledged_by FROM exercise_script_item WHERE id = ?",
                    scriptId);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", scriptId);
        body.put("status", existing.get("status"));
        body.put("acknowledgedAt", existing.get("acknowledged_at"));
        body.put("acknowledgedBy", existing.get("acknowledged_by"));
        body.put("replayed", "ACKNOWLEDGED".equals(String.valueOf(existing.get("status"))));
        return body;
    }

    private static Object orZero(Object value) {
        return value instanceof Number ? value : 0;
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }
}
