package org.bluesky.training.common;

import org.bluesky.training.persistence.TrustedCallerBindingRow;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/** P02：v2 终端管理接口（仅运维服务身份，详细设计 2.2 §9.2）。 */
@V2Api
@RestController
@RequestMapping("/api/v2")
public class TerminalAdminControllerV2 {

    private final CallerContextResolver resolver;
    private final TerminalProvisioningService provisioningService;
    private final IdempotentHttpExecutor idempotentHttpExecutor;

    public TerminalAdminControllerV2(CallerContextResolver resolver,
                                     TerminalProvisioningService provisioningService,
                                     IdempotentHttpExecutor idempotentHttpExecutor) {
        this.resolver = resolver;
        this.provisioningService = provisioningService;
        this.idempotentHttpExecutor = idempotentHttpExecutor;
    }

    @GetMapping("/exercise-groups/{groupId}/terminals")
    public Map<String, Object> list(@PathVariable("groupId") String groupId,
                                    HttpServletRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", provisioningService.listByGroup(resolver.resolve(request), groupId));
        return body;
    }

    @PostMapping("/exercise-groups/{groupId}/terminals")
    public org.springframework.http.ResponseEntity<Object> create(
            @PathVariable("groupId") String groupId,
            @RequestBody Map<String, Object> payload,
            HttpServletRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        CallerContext caller = resolver.resolveService(request);
        String path = "/api/v2/exercise-groups/" + groupId + "/terminals";
        return idempotentHttpExecutor.execute(caller, "POST", path, key, payload, 201,
                () -> provisioningService.createTerminal(caller, groupId,
                        text(payload.get("name")), decimal(payload.get("frequencyMhz")),
                        text(payload.get("unitMode"))));
    }

    @PatchMapping("/terminals/{terminalId}")
    public org.springframework.http.ResponseEntity<Object> update(
            @PathVariable("terminalId") String terminalId,
            @RequestBody Map<String, Object> payload,
            HttpServletRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        Boolean enabled = payload.get("enabled") == null
                ? null : Boolean.parseBoolean(String.valueOf(payload.get("enabled")));
        CallerContext caller = resolver.resolveService(request);
        String path = "/api/v2/terminals/" + terminalId;
        return idempotentHttpExecutor.execute(caller, "PATCH", path, key, payload, 200,
                () -> provisioningService.updateTerminal(caller, terminalId,
                        RevisionHeaders.requireIfMatch(ifMatch), text(payload.get("name")),
                        text(payload.get("unitMode")), enabled));
    }

    @PutMapping("/terminals/{terminalId}/trusted-binding")
    public org.springframework.http.ResponseEntity<Object> bindCertificate(
            @PathVariable("terminalId") String terminalId,
            @RequestBody Map<String, Object> payload,
            HttpServletRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        CallerContext caller = resolver.resolveService(request);
        String path = "/api/v2/terminals/" + terminalId + "/trusted-binding";
        return idempotentHttpExecutor.execute(caller, "PUT", path, key, payload, 200, () -> {
            TrustedCallerBindingRow binding = provisioningService.bindCallerCertificate(
                    caller, terminalId, RevisionHeaders.requireIfMatch(ifMatch),
                    text(payload.get("certificateFingerprintDigest")));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("terminalId", binding.getTerminalId());
            body.put("exerciseGroupId", binding.getExerciseGroupId());
            body.put("certificateFingerprintDigest", binding.getCertificateFingerprintDigest());
            body.put("enabled", binding.isEnabled());
            body.put("revision", binding.getRevision());
            return body;
        });
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static BigDecimal decimal(Object value) {
        if (value == null || String.valueOf(value).trim().isEmpty()) {
            return null;
        }
        return new BigDecimal(String.valueOf(value).trim());
    }
}
