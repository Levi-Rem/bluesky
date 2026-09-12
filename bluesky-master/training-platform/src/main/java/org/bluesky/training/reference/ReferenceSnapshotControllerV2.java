package org.bluesky.training.reference;

import org.bluesky.training.common.CallerContext;
import org.bluesky.training.common.CallerContextResolver;
import org.bluesky.training.common.TerminalAccessPolicy;
import org.bluesky.training.common.V2Api;
import org.bluesky.training.common.IdempotentHttpExecutor;
import org.bluesky.training.common.RevisionHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;

/** P03：v2 参考快照接口（详细设计 2.2 §9.2）。 */
@V2Api
@RestController
@RequestMapping("/api/v2")
public class ReferenceSnapshotControllerV2 {

    private final CallerContextResolver resolver;
    private final TerminalAccessPolicy terminalAccessPolicy;
    private final ReferenceSnapshotService snapshotService;
    private final IdempotentHttpExecutor idempotentHttpExecutor;

    public ReferenceSnapshotControllerV2(CallerContextResolver resolver,
                                         TerminalAccessPolicy terminalAccessPolicy,
                                         ReferenceSnapshotService snapshotService,
                                         IdempotentHttpExecutor idempotentHttpExecutor) {
        this.resolver = resolver;
        this.terminalAccessPolicy = terminalAccessPolicy;
        this.snapshotService = snapshotService;
        this.idempotentHttpExecutor = idempotentHttpExecutor;
    }

    /** 训练编排方在 READY 状态固定已发布快照。 */
    @PutMapping("/exercise-groups/{groupId}/reference-snapshot")
    public ResponseEntity<Object> pinSnapshot(
            @PathVariable("groupId") String groupId,
            @RequestBody Map<String, Object> payload,
            HttpServletRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        CallerContext caller = resolver.resolveService(request);
        String path = "/api/v2/exercise-groups/" + groupId + "/reference-snapshot";
        return idempotentHttpExecutor.execute(caller, "PUT", path, key, payload, 200,
                () -> snapshotService.pinToGroup(caller, groupId,
                        text(payload.get("referenceSnapshotId")),
                        RevisionHeaders.requireIfMatch(ifMatch)));
    }

    /** 训练编排方查询已发布快照。 */
    @GetMapping("/reference-snapshots")
    public Map<String, Object> listPublished(HttpServletRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", snapshotService.listPublished(resolver.resolveService(request)));
        return body;
    }

    /** 组内终端或编排方读取该组固定快照资源。 */
    @GetMapping("/exercise-groups/{groupId}/reference-snapshot/{resourceType}")
    public Object getResource(@PathVariable("groupId") String groupId,
                              @PathVariable("resourceType") String resourceType,
                              HttpServletRequest request) {
        CallerContext caller = resolver.resolve(request);
        if (caller != null && caller.callerType() == CallerContext.CallerType.TERMINAL) {
            terminalAccessPolicy.requireSameGroup(caller, groupId);
        } else if (caller == null
                || caller.callerType() == CallerContext.CallerType.TERMINAL) {
            throw new org.bluesky.training.common.V2DomainException(
                    "TRUSTED_IDENTITY_REJECTED", 403, "需要受信调用方身份");
        }
        return snapshotService.readResource(groupId, resourceType);
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    @org.springframework.web.bind.annotation.PostMapping("/reference-snapshots")
    public ResponseEntity<Object> publish(@RequestBody Map<String,Object> payload,HttpServletRequest request,
            @RequestHeader(value="Idempotency-Key",required=false) String key) {
        CallerContext caller=resolver.resolveService(request);
        new org.bluesky.training.common.ServiceAccessPolicy().requireOrchestrator(caller);
        String source=text(payload.get("sourceDirectory")),version=text(payload.get("versionLabel"));
        if(source==null || version==null)throw new org.bluesky.training.common.V2DomainException("INVALID_INSTRUCTION",400,"sourceDirectory 与 versionLabel 必填");
        return idempotentHttpExecutor.execute(caller,"POST","/api/v2/reference-snapshots",key,payload,201,()->snapshotService.publish(java.nio.file.Paths.get(source),version));
    }
}
