package org.bluesky.training.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.bluesky.training.persistence.TrustedCallerBindingRow;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * P02：只接受反向代理注入且经过网关保护的身份属性（详细设计 14.7）。
 * 浏览器自填的 X-Terminal-Id 只作路由提示，不参与身份判定。
 */
public class TrustedCallerFilter extends OncePerRequestFilter {

    public static final String GATEWAY_SECRET_HEADER = "X-Gateway-Secret";
    public static final String CALLER_TYPE_HEADER = "X-Trusted-Caller-Type";
    public static final String CALLER_ID_HEADER = "X-Trusted-Caller-Id";
    public static final String TERMINAL_ID_HEADER = "X-Trusted-Terminal-Id";
    public static final String FINGERPRINT_HEADER = "X-Trusted-Fingerprint-Digest";
    public static final String CALLER_CONTEXT_ATTRIBUTE = "callerContext";

    private final String gatewaySecret;
    private final TrustedCallerBindingService bindingService;
    /** 仅 demo/test 配置：无网关时按终端号注入开发身份；生产不设置该属性（fail-safe）。 */
    private final String devTerminalId;
    private final org.bluesky.training.persistence.TerminalAdminMapper terminalMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public TrustedCallerFilter(String gatewaySecret, TrustedCallerBindingService bindingService) {
        this(gatewaySecret, bindingService, "", null);
    }

    public TrustedCallerFilter(String gatewaySecret, TrustedCallerBindingService bindingService,
                              String devTerminalId,
                              org.bluesky.training.persistence.TerminalAdminMapper terminalMapper) {
        this.gatewaySecret = gatewaySecret == null ? "" : gatewaySecret;
        this.bindingService = bindingService;
        this.devTerminalId = devTerminalId == null ? "" : devTerminalId.trim();
        this.terminalMapper = terminalMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String callerType = trimToNull(request.getHeader(CALLER_TYPE_HEADER));
        String callerId = trimToNull(request.getHeader(CALLER_ID_HEADER));
        String terminalId = trimToNull(request.getHeader(TERMINAL_ID_HEADER));
        String fingerprint = trimToNull(request.getHeader(FINGERPRINT_HEADER));

        boolean hasTrustedHeaders = callerType != null || callerId != null
                || terminalId != null || fingerprint != null;
        if (!hasTrustedHeaders) {
            if (!devTerminalId.isEmpty() && terminalMapper != null) {
                // demo/test 显式开发身份：按终端号回查所属训练组后注入（生产不配置该属性）
                org.bluesky.training.persistence.TerminalAdminRow terminal =
                        terminalMapper.findById(devTerminalId);
                if (terminal != null) {
                    request.setAttribute(CALLER_CONTEXT_ATTRIBUTE,
                            CallerContext.terminal(devTerminalId,
                                    terminal.getExerciseGroupId(), null));
                }
            }
            // 匿名请求：浏览器 X-Terminal-Id 仅路由提示，放行
            chain.doFilter(request, response);
            return;
        }

        if (gatewaySecret.isEmpty()
                || !gatewaySecret.equals(trimToNull(request.getHeader(GATEWAY_SECRET_HEADER)))) {
            reject(response, "缺少有效网关密钥，身份属性不可信");
            return;
        }

        CallerContext.CallerType type = parseCallerType(callerType);
        if (type == null || callerId == null) {
            reject(response, "身份属性不完整");
            return;
        }
        try {
            applyIdentity(request, type, callerId, terminalId, fingerprint);
        } catch (V2DomainException failure) {
            reject(response, failure.getMessage());
            return;
        }
        chain.doFilter(request, response);
    }

    private void applyIdentity(HttpServletRequest request, CallerContext.CallerType type,
                               String callerId, String terminalId, String fingerprint) {
        switch (type) {
            case TERMINAL:
                if (terminalId == null) {
                    throw new V2DomainException("TRUSTED_IDENTITY_REJECTED", 403,
                            "终端身份缺少终端 ID");
                }
                TrustedCallerBindingRow binding = bindingService.resolveByFingerprintDigest(fingerprint);
                bindingService.assertEnabled(binding);
                bindingService.assertMatchesTerminal(binding, terminalId);
                request.setAttribute(CALLER_CONTEXT_ATTRIBUTE, CallerContext.terminal(
                        binding.getTerminalId(), binding.getExerciseGroupId(), fingerprint));
                bindingService.touchLastSeen(binding.getId());
                break;
            case EXERCISE_ORCHESTRATOR:
            case OPERATIONS:
                if (terminalId != null) {
                    throw new V2DomainException("TRUSTED_IDENTITY_REJECTED", 403,
                            "服务身份不得携带终端属性（混合身份）");
                }
                if (type == CallerContext.CallerType.EXERCISE_ORCHESTRATOR) {
                    request.setAttribute(CALLER_CONTEXT_ATTRIBUTE,
                            CallerContext.orchestrator(callerId, fingerprint));
                } else {
                    request.setAttribute(CALLER_CONTEXT_ATTRIBUTE,
                            CallerContext.operations(callerId, fingerprint));
                }
                break;
            default:
                throw new V2DomainException("TRUSTED_IDENTITY_REJECTED", 403, "未知调用方类型");
        }
    }

    private CallerContext.CallerType parseCallerType(String value) {
        if (value == null) {
            return null;
        }
        try {
            return CallerContext.CallerType.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void reject(HttpServletResponse response, String message) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "TRUSTED_IDENTITY_REJECTED");
        body.put("message", message);
        body.put("requestId", "req-" + java.util.UUID.randomUUID());
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
