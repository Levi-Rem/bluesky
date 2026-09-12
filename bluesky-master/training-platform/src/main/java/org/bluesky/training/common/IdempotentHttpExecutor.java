package org.bluesky.training.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Map;
import java.util.function.Supplier;

/** 把所有 v2 写接口统一接入持久化幂等语义，避免 Controller 各自实现。 */
@Service
public class IdempotentHttpExecutor {

    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    public IdempotentHttpExecutor(IdempotencyService idempotencyService) {
        this.idempotencyService = idempotencyService;
    }

    public ResponseEntity<Object> execute(CallerContext caller, String method,
                                          String canonicalPath, String idempotencyKey,
                                          Object requestBody, int firstStatus,
                                          Supplier<?> action) {
        if (caller == null || caller.callerType() == null
                || caller.callerId() == null || caller.callerId().trim().isEmpty()) {
            throw new V2DomainException("TRUSTED_IDENTITY_REJECTED", 403,
                    "v2 写请求必须携带受信调用方身份");
        }
        String key = requireKey(idempotencyKey);
        byte[] body = canonicalBytes(requestBody);
        IdempotencyCommand command = new IdempotencyCommand(
                caller, method, canonicalPath,
                IdempotencyScopeFactory.create(caller, method, canonicalPath, key),
                key, RequestDigest.sha256(method, canonicalPath, body),
                firstStatus, 24);
        IdempotentResult result = idempotencyService.execute(command, action);
        return V2ApiResponseFactory.replayed(result);
    }

    private static String requireKey(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new V2DomainException("IDEMPOTENCY_KEY_REQUIRED", 400,
                    "所有 v2 写请求必须携带 Idempotency-Key",
                    Collections.singletonList("Idempotency-Key"));
        }
        if (value.trim().length() > 128) {
            throw new V2DomainException("IDEMPOTENCY_KEY_INVALID", 400,
                    "Idempotency-Key 长度不能超过 128",
                    Collections.singletonList("Idempotency-Key"));
        }
        return value.trim();
    }

    private byte[] canonicalBytes(Object body) {
        try {
            return objectMapper.writeValueAsBytes(body == null
                    ? Collections.emptyMap() : body);
        } catch (JsonProcessingException e) {
            throw new V2DomainException("REQUEST_BODY_INVALID", 400, "请求体无法规范化");
        }
    }
}
