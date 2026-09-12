package org.bluesky.training.common;

import org.bluesky.training.persistence.TrustedCallerBindingMapper;
import org.bluesky.training.persistence.TrustedCallerBindingRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** P02：只接受反向代理注入且经过网关保护的身份属性（详细设计 14.7）。 */
class TrustedCallerFilterMvcTest {

    private static final String GATEWAY_SECRET = "test-gateway-secret";

    private TrustedCallerBindingMapper bindingMapper;
    private MockMvc mockMvc;

    @V2Api
    @RestController
    static class IdentityProbeController {
        @GetMapping("/probe/identity")
        public String identity(@RequestAttribute(value = "callerContext", required = false) CallerContext caller) {
            if (caller == null) {
                return "anonymous";
            }
            return caller.callerType() + ":" + caller.callerId() + ":" + caller.exerciseGroupId();
        }
    }

    @BeforeEach
    void setUp() {
        bindingMapper = mock(TrustedCallerBindingMapper.class);
        // 真实服务 + Mock mapper：void 校验方法不能被 Mock 吞掉
        TrustedCallerBindingService bindingService = new TrustedCallerBindingService(bindingMapper);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new IdentityProbeController())
                .addFilters(new TrustedCallerFilter(GATEWAY_SECRET, bindingService))
                .setControllerAdvice(new V2ApiExceptionHandler())
                .build();
    }

    private TrustedCallerBindingRow boundRow(String terminalId, String groupId, boolean enabled) {
        TrustedCallerBindingRow row = new TrustedCallerBindingRow();
        row.setId("binding-1");
        row.setTerminalId(terminalId);
        row.setExerciseGroupId(groupId);
        row.setCertificateFingerprintDigest("digest-pp01");
        row.setEnabled(enabled);
        return row;
    }

    @Test
    void givenSpoofedTerminalHeaderWithoutTrustedAttributeThenForbidden() throws Exception {
        mockMvc.perform(get("/probe/identity")
                        .header("X-Trusted-Caller-Type", "TERMINAL")
                        .header("X-Trusted-Caller-Id", "PP-01")
                        .header("X-Trusted-Terminal-Id", "PP-01")
                        .header("X-Trusted-Fingerprint-Digest", "digest-pp01"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TRUSTED_IDENTITY_REJECTED"));
    }

    @Test
    void givenVerifiedGatewayWhenTerminalBoundThenCallerContextAttached() throws Exception {
        when(bindingMapper.findByFingerprintDigest("digest-pp01"))
                .thenReturn(boundRow("PP-01", "group-1", true));

        mockMvc.perform(get("/probe/identity")
                        .header("X-Gateway-Secret", GATEWAY_SECRET)
                        .header("X-Trusted-Caller-Type", "TERMINAL")
                        .header("X-Trusted-Caller-Id", "PP-01")
                        .header("X-Trusted-Terminal-Id", "PP-01")
                        .header("X-Trusted-Fingerprint-Digest", "digest-pp01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value("TERMINAL:PP-01:group-1"));
    }

    @Test
    void givenBrowserTerminalHintWithoutTrustedHeadersThenRequestPassesAnonymously() throws Exception {
        mockMvc.perform(get("/probe/identity").header("X-Terminal-Id", "PP-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value("anonymous"));
    }

    @Test
    void givenDisabledBindingWhenResolvedThenForbidden() throws Exception {
        when(bindingMapper.findByFingerprintDigest("digest-pp01"))
                .thenReturn(boundRow("PP-01", "group-1", false));

        mockMvc.perform(get("/probe/identity")
                        .header("X-Gateway-Secret", GATEWAY_SECRET)
                        .header("X-Trusted-Caller-Type", "TERMINAL")
                        .header("X-Trusted-Caller-Id", "PP-01")
                        .header("X-Trusted-Terminal-Id", "PP-01")
                        .header("X-Trusted-Fingerprint-Digest", "digest-pp01"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TRUSTED_IDENTITY_REJECTED"));
    }

    @Test
    void givenMixedIdentityHeadersWhenFilteredThenForbidden() throws Exception {
        mockMvc.perform(get("/probe/identity")
                        .header("X-Gateway-Secret", GATEWAY_SECRET)
                        .header("X-Trusted-Caller-Type", "OPERATIONS")
                        .header("X-Trusted-Caller-Id", "ops-1")
                        .header("X-Trusted-Terminal-Id", "PP-01"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TRUSTED_IDENTITY_REJECTED"));
    }

    @Test
    void givenUnknownCallerTypeWhenFilteredThenForbidden() throws Exception {
        mockMvc.perform(get("/probe/identity")
                        .header("X-Gateway-Secret", GATEWAY_SECRET)
                        .header("X-Trusted-Caller-Type", "SUPERUSER")
                        .header("X-Trusted-Caller-Id", "x"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TRUSTED_IDENTITY_REJECTED"));
    }

    @Test
    void givenServiceIdentityWhenResolvedThenGroupIsNotRequired() throws Exception {
        mockMvc.perform(get("/probe/identity")
                        .header("X-Gateway-Secret", GATEWAY_SECRET)
                        .header("X-Trusted-Caller-Type", "EXERCISE_ORCHESTRATOR")
                        .header("X-Trusted-Caller-Id", "orch-1")
                        .header("X-Trusted-Fingerprint-Digest", "digest-orch"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value("EXERCISE_ORCHESTRATOR:orch-1:null"));
    }

    @Test
    void givenTerminalBindingMismatchWhenFilteredThenForbidden() throws Exception {
        // 绑定表指向 PP-02，但网关注入的终端是 PP-01：必须拒绝
        when(bindingMapper.findByFingerprintDigest(anyString()))
                .thenReturn(boundRow("PP-02", "group-1", true));

        mockMvc.perform(get("/probe/identity")
                        .header("X-Gateway-Secret", GATEWAY_SECRET)
                        .header("X-Trusted-Caller-Type", "TERMINAL")
                        .header("X-Trusted-Caller-Id", "PP-01")
                        .header("X-Trusted-Terminal-Id", "PP-01")
                        .header("X-Trusted-Fingerprint-Digest", "digest-pp01"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TRUSTED_IDENTITY_REJECTED"));
    }
}
