package org.bluesky.training.display;

import org.bluesky.training.common.CallerContextResolver;
import org.bluesky.training.common.RevisionHeaders;
import org.bluesky.training.common.V2Api;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;

/** P18 v2 屏幕方案与标牌布局接口（详细设计 2.2 §5.7/§9.2）。 */
@V2Api
@RestController
@RequestMapping("/api/v2")
public class DisplayProfileControllerV2 {

    private final CallerContextResolver resolver;
    private final DisplayProfileService profileService;
    private final org.bluesky.training.persistence.DisplayProfileMapper mapper;
    @org.springframework.beans.factory.annotation.Autowired private org.bluesky.training.common.IdempotentHttpExecutor idempotency;

    public DisplayProfileControllerV2(CallerContextResolver resolver,
                                      DisplayProfileService profileService,
                                      org.bluesky.training.persistence.DisplayProfileMapper mapper) {
        this.resolver = resolver;
        this.profileService = profileService;
        this.mapper = mapper;
    }

    @GetMapping("/workstations/{terminalId}/display-profiles")
    public Map<String, Object> list(@PathVariable("terminalId") String terminalId,
                                    HttpServletRequest request) {
        requireOwn(resolver, terminalId, request);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", profileService.list(terminalId));
        return body;
    }

    @PostMapping("/workstations/{terminalId}/display-profiles")
    public ResponseEntity<Object> create(
            @PathVariable("terminalId") String terminalId,
            @RequestBody Map<String, Object> payload,
            HttpServletRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        requireOwn(resolver, terminalId, request);
        Object content = payload.containsKey("contentJson") ? payload.get("contentJson")
                : payload.get("content");
        String contentJson = content instanceof String ? String.valueOf(content)
                : jsonOf(content == null ? java.util.Collections.emptyMap() : content);
        return idempotency.execute(resolver.resolveTerminal(request),"POST","/api/v2/workstations/"+terminalId+"/display-profiles",key,payload,201,
                ()->profileService.create(terminalId,String.valueOf(payload.get("name")),contentJson));
    }

    @PutMapping({"/workstations/{terminalId}/display-profiles/{profileId}", "/display-profiles/{profileId}"})
    public Map<String, Object> update(
            @PathVariable(value = "terminalId", required = false) String terminalId,
            @PathVariable("profileId") String profileId,
            @RequestBody Map<String, Object> payload,
            HttpServletRequest request,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        if (terminalId != null) {
            requireOwn(resolver, terminalId, request);
        } else {
            requireProfileOwner(resolver, request, profileId);
        }
        requireProfileOwner(resolver, request, profileId);
        long expectedRevision=RevisionHeaders.requireIfMatch(ifMatch);
        Object content = payload.containsKey("contentJson") ? payload.get("contentJson")
                : payload.get("content");
        String contentJson = content instanceof String ? String.valueOf(content)
                : jsonOf(content == null ? java.util.Collections.emptyMap() : content);
        return profileService.update(profileId, contentJson, expectedRevision);
    }

    @DeleteMapping({"/workstations/{terminalId}/display-profiles/{profileId}", "/display-profiles/{profileId}"})
    public Map<String, Object> delete(
            @PathVariable(value = "terminalId", required = false) String terminalId,
            @PathVariable("profileId") String profileId,
            HttpServletRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        if (terminalId != null) {
            requireOwn(resolver, terminalId, request);
        } else {
            requireProfileOwner(resolver, request, profileId);
        }
        requireProfileOwner(resolver, request, profileId);
        profileService.delete(profileId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", profileId);
        body.put("deleted", true);
        return body;
    }

    @PutMapping("/workstations/{terminalId}/aircraft/{aircraftId}/label-layout")
    public Map<String, Object> saveLabelLayout(
            @PathVariable("terminalId") String terminalId,
            @PathVariable("aircraftId") String aircraftId,
            @RequestBody Map<String, Object> payload,
            HttpServletRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        requireOwn(resolver, terminalId, request);
        double angleDeg = ((Number) payload.getOrDefault("angleDeg", 35)).doubleValue();
        int distancePx = ((Number) payload.getOrDefault("distancePx", 20)).intValue();
        String mode = String.valueOf(payload.getOrDefault("mode", "NORMAL"));
        boolean pinned = Boolean.TRUE.equals(payload.get("pinned"));
        if (mapper.updateLabelLayout(terminalId, aircraftId, angleDeg, distancePx, mode, pinned) == 0) {
            mapper.insertLabelLayout(java.util.UUID.randomUUID().toString(), terminalId, aircraftId,
                    angleDeg, distancePx, mode, pinned);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("terminalId", terminalId);
        body.put("aircraftId", aircraftId);
        body.put("saved", true);
        return body;
    }

    @DeleteMapping("/workstations/{terminalId}/aircraft/{aircraftId}/label-layout")
    public org.springframework.http.ResponseEntity<Void> resetLabelLayout(
            @PathVariable("terminalId") String terminalId,
            @PathVariable("aircraftId") String aircraftId,
            HttpServletRequest request) {
        requireOwn(resolver, terminalId, request);
        mapper.deleteLabelLayout(terminalId, aircraftId);
        return org.springframework.http.ResponseEntity.noContent().build();
    }

    private void requireProfileOwner(CallerContextResolver resolver, HttpServletRequest request,
                                     String profileId) {
        org.bluesky.training.common.CallerContext caller = resolver.resolveTerminal(request);
        Map<String, Object> row = mapper.findById(profileId);
        if (row == null || !caller.terminalId().equals(String.valueOf(row.get("terminal_id")))) {
            throw new org.bluesky.training.common.V2DomainException(
                    "TERMINAL_NOT_IN_GROUP", 403, "只能操作自己终端的显示配置",
                    java.util.Arrays.asList("terminalId"));
        }
    }

    private static String jsonOf(Object value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (java.io.IOException e) {
            throw new org.bluesky.training.common.V2DomainException(
                    "INVALID_INSTRUCTION", 400, "方案内容序列化失败",
                    java.util.Arrays.asList("contentJson"));
        }
    }

    private static void requireOwn(CallerContextResolver resolver, String terminalId,
                                   HttpServletRequest request) {
        org.bluesky.training.common.CallerContext caller = resolver.resolveTerminal(request);
        if (!caller.terminalId().equals(terminalId)) {
            throw new org.bluesky.training.common.V2DomainException(
                    "TERMINAL_NOT_IN_GROUP", 403, "只能操作自己终端的显示配置",
                    java.util.Arrays.asList("terminalId"));
        }
    }
}
