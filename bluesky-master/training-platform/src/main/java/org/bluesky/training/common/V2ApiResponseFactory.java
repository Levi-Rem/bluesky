package org.bluesky.training.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

/** P01：v2 响应构造（详细设计 9.1：201/202/200 与幂等重放保留首次状态）。 */
public final class V2ApiResponseFactory {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private V2ApiResponseFactory() {
    }

    public static ResponseEntity<Object> ok(Object body) {
        return ResponseEntity.ok(body);
    }

    public static ResponseEntity<Object> created(Object body, String location) {
        HttpHeaders headers = new HttpHeaders();
        if (location != null) {
            headers.add(HttpHeaders.LOCATION, location);
        }
        return ResponseEntity.status(201).headers(headers).body(body);
    }

    public static ResponseEntity<Object> accepted(Object operationEnvelope) {
        return ResponseEntity.status(202).body(operationEnvelope);
    }

    public static ResponseEntity<Object> replayed(IdempotentResult result) {
        return ResponseEntity.status(result.httpStatus()).body(parse(result.responseBody()));
    }

    private static Object parse(String json) {
        if (json == null) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readValue(json, Object.class);
        } catch (JsonProcessingException e) {
            return json;
        }
    }
}
