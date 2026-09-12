package org.bluesky.training.exercise;

import org.bluesky.training.common.CallerContext;
import org.bluesky.training.common.CallerContextResolver;
import org.bluesky.training.common.IdempotentHttpExecutor;
import org.bluesky.training.common.V2Api;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.Map;

/** P05：v2 训练组动作（详细设计 2.2 §9.2/9.6：202 + 操作信封）。 */
@V2Api
@RestController
@RequestMapping("/api/v2/exercise-groups")
public class ExerciseGroupActionControllerV2 {

    private final CallerContextResolver resolver;
    private final ExerciseGroupService exerciseGroupService;
    private final IdempotentHttpExecutor idempotentHttpExecutor;

    public ExerciseGroupActionControllerV2(CallerContextResolver resolver,
                                           ExerciseGroupService exerciseGroupService,
                                           IdempotentHttpExecutor idempotentHttpExecutor) {
        this.resolver = resolver;
        this.exerciseGroupService = exerciseGroupService;
        this.idempotentHttpExecutor = idempotentHttpExecutor;
    }

    @PostMapping("/{groupId}/actions/start")
    public ResponseEntity<Object> start(@PathVariable("groupId") String groupId,
                                        @RequestBody Map<String, Object> payload,
                                        @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        CallerContext caller = resolver.resolveCurrent();
        String path = "/api/v2/exercise-groups/" + groupId + "/actions/start";
        return idempotentHttpExecutor.execute(caller, "POST", path, key, payload, 202,
                () -> exerciseGroupService.requestStart(
                        caller, groupId, requiredRevision(payload, "groupRevision")));
    }

    @PostMapping("/{groupId}/actions/pause")
    public ResponseEntity<Object> pause(@PathVariable("groupId") String groupId,
                                        @RequestBody Map<String, Object> payload,
                                        @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        CallerContext caller = resolver.resolveCurrent();
        String path = "/api/v2/exercise-groups/" + groupId + "/actions/pause";
        return idempotentHttpExecutor.execute(caller, "POST", path, key, payload, 202,
                () -> exerciseGroupService.requestPause(
                        caller, groupId, requiredRevision(payload, "groupRevision")));
    }

    @PostMapping("/{groupId}/actions/resume")
    public ResponseEntity<Object> resume(@PathVariable("groupId") String groupId,
                                         @RequestBody Map<String, Object> payload,
                                         @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        CallerContext caller = resolver.resolveCurrent();
        String path = "/api/v2/exercise-groups/" + groupId + "/actions/resume";
        return idempotentHttpExecutor.execute(caller, "POST", path, key, payload, 202,
                () -> exerciseGroupService.requestResume(
                        caller, groupId, requiredRevision(payload, "groupRevision")));
    }

    /** 仅训练编排方（详细设计 5.1/9.1）。 */
    @PostMapping("/{groupId}/actions/end")
    public ResponseEntity<Object> end(@PathVariable("groupId") String groupId,
                                      @RequestBody Map<String, Object> payload,
                                      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        CallerContext caller = resolver.resolveCurrent();
        String path = "/api/v2/exercise-groups/" + groupId + "/actions/end";
        return idempotentHttpExecutor.execute(caller, "POST", path, key, payload, 202,
                () -> exerciseGroupService.requestEnd(
                        caller, groupId, requiredRevision(payload, "groupRevision"),
                        text(payload.get("reason"))));
    }

    private static long requiredRevision(Map<String, Object> payload, String field) {
        Object value = payload.get(field);
        if (value == null || String.valueOf(value).trim().isEmpty()) {
            throw new org.bluesky.training.common.V2DomainException(
                    "REVISION_REQUIRED", 428, "缺少 " + field,
                    java.util.Collections.singletonList(field));
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            throw new org.bluesky.training.common.V2DomainException(
                    "REVISION_REQUIRED", 428, field + " 必须是数字",
                    java.util.Collections.singletonList(field));
        }
    }

    @PostMapping("/{groupId}/actions/retry-recovery")
    public ResponseEntity<Object> retryRecovery(@PathVariable("groupId") String groupId,@RequestBody Map<String,Object> payload,
            @RequestHeader(value="Idempotency-Key",required=false)String key) {
        CallerContext caller=resolver.resolveCurrent();
        return idempotentHttpExecutor.execute(caller,"POST","/api/v2/exercise-groups/"+groupId+"/actions/retry-recovery",key,payload,202,
                ()->exerciseGroupService.retryRecovery(caller,groupId,requiredRevision(payload,"groupRevision")));
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }
}
