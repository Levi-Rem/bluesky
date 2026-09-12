package org.bluesky.training.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** P04：2.0 信封 JSON 编解码与 1 MiB 帧限制（详细设计 10.1）。 */
public class ProtocolEnvelopeCodec {

    public static final int MAX_FRAME_BYTES = 1024 * 1024;

    private static final String PROTOCOL_VERSION = "2.0";
    private static final List<String> MESSAGE_KINDS =
            Arrays.asList("REQUEST", "RESPONSE", "EVENT");
    private static final String[] REQUIRED_FIELDS = {
            "protocolVersion", "messageKind", "messageType", "exerciseGroupId",
            "engineInstanceId", "requestId", "correlationRequestId", "idempotencyKey",
            "sequence", "systemTimeUtc", "simulationTimeSeconds", "payloadSchemaVersion", "payload"};
    private static final String[] NON_NULL_FIELDS = {
            "protocolVersion", "messageKind", "messageType", "exerciseGroupId",
            "engineInstanceId", "sequence", "systemTimeUtc", "simulationTimeSeconds", "payload"};

    private final ObjectMapper objectMapper = new ObjectMapper();

    public byte[] encode(Map<String, Object> envelope) {
        requireComplete(envelope);
        try {
            byte[] frame = objectMapper.writeValueAsBytes(envelope);
            validateSize(frame);
            return frame;
        } catch (JsonProcessingException e) {
            throw new AdapterProtocolException("ENVELOPE_ENCODE_FAILED", e.getMessage());
        }
    }

    public Map<String, Object> decode(byte[] frame) {
        if (frame == null || frame.length == 0) {
            throw new AdapterProtocolException("ENVELOPE_EMPTY", "帧内容为空");
        }
        validateSize(frame);
        Map<String, Object> envelope;
        try {
            Object parsed = objectMapper.readValue(frame, Object.class);
            if (!(parsed instanceof Map)) {
                throw new AdapterProtocolException("ENVELOPE_INVALID", "帧必须是 JSON 对象");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) parsed;
            envelope = new LinkedHashMap<>(map);
        } catch (IOException e) {
            throw new AdapterProtocolException("ENVELOPE_INVALID", e.getMessage());
        }
        requireComplete(envelope);
        if (!PROTOCOL_VERSION.equals(String.valueOf(envelope.get("protocolVersion")))) {
            throw new AdapterProtocolException("PROTOCOL_VERSION_MISMATCH",
                    "仅支持 Protocol 2.0: " + envelope.get("protocolVersion"));
        }
        return envelope;
    }

    public void validateSize(byte[] frame) {
        if (frame != null && frame.length > MAX_FRAME_BYTES) {
            throw new AdapterProtocolException("FRAME_TOO_LARGE",
                    "帧超过 1 MiB 上限: " + frame.length);
        }
    }

    public byte[] frameOf(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private static void requireComplete(Map<String, Object> envelope) {
        for (String field : REQUIRED_FIELDS) {
            if (!envelope.containsKey(field)) {
                throw new AdapterProtocolException("ENVELOPE_INCOMPLETE", "信封缺少字段: " + field);
            }
        }
        for (String field : NON_NULL_FIELDS) {
            if (envelope.get(field) == null) {
                throw new AdapterProtocolException("ENVELOPE_INCOMPLETE", "信封字段不能为空: " + field);
            }
        }
        if (!PROTOCOL_VERSION.equals(String.valueOf(envelope.get("protocolVersion")))) {
            throw new AdapterProtocolException("PROTOCOL_VERSION_MISMATCH",
                    "仅支持 Protocol 2.0: " + envelope.get("protocolVersion"));
        }
        String kind = String.valueOf(envelope.get("messageKind"));
        if (!MESSAGE_KINDS.contains(kind)) {
            throw new AdapterProtocolException("ENVELOPE_INVALID", "messageKind 非法: " + kind);
        }
        if (!AdapterProtocolContract.supportsMessageType(envelope.get("messageType"))) {
            throw new AdapterProtocolException("ENVELOPE_INVALID",
                    "messageType 不在 Protocol 2.0 枚举内: " + envelope.get("messageType"));
        }
        requireText(envelope, "exerciseGroupId");
        requireText(envelope, "engineInstanceId");
        Object sequence = envelope.get("sequence");
        if (!(sequence instanceof Byte || sequence instanceof Short
                || sequence instanceof Integer || sequence instanceof Long)
                || ((Number) sequence).longValue() < 1L) {
            throw new AdapterProtocolException("ENVELOPE_INVALID",
                    "sequence 必须为不小于 1 的整数");
        }
        requireUtcInstant(envelope.get("systemTimeUtc"));
        Object simulationTime = envelope.get("simulationTimeSeconds");
        if (!(simulationTime instanceof Number)
                || !Double.isFinite(((Number) simulationTime).doubleValue())
                || ((Number) simulationTime).doubleValue() < 0.0) {
            throw new AdapterProtocolException("ENVELOPE_INVALID",
                    "simulationTimeSeconds 必须为非负有限数值");
        }
        if (!(envelope.get("payload") instanceof Map)) {
            throw new AdapterProtocolException("ENVELOPE_INVALID", "payload 必须是对象");
        }
        if ("REQUEST".equals(kind)) {
            if (envelope.get("requestId") == null || String.valueOf(envelope.get("requestId")).trim().isEmpty()) {
                throw new AdapterProtocolException("ENVELOPE_INCOMPLETE", "REQUEST 必须携带 requestId");
            }
            if (envelope.get("correlationRequestId") != null) {
                throw new AdapterProtocolException("ENVELOPE_INVALID", "REQUEST 的 correlationRequestId 必须为空");
            }
        } else if ("RESPONSE".equals(kind)) {
            requireText(envelope, "requestId");
            requireText(envelope, "correlationRequestId");
        }
    }

    private static void requireText(Map<String, Object> envelope, String field) {
        Object value = envelope.get(field);
        if (!(value instanceof String) || ((String) value).trim().isEmpty()) {
            throw new AdapterProtocolException("ENVELOPE_INCOMPLETE", field + " 必须是非空字符串");
        }
    }

    private static void requireUtcInstant(Object value) {
        if (!(value instanceof String) || ((String) value).trim().isEmpty()) {
            throw new AdapterProtocolException("ENVELOPE_INCOMPLETE",
                    "systemTimeUtc 必须是非空 UTC 时间");
        }
        try {
            String text = (String) value;
            if (!text.endsWith("Z")) {
                throw new DateTimeParseException("not UTC", text, text.length());
            }
            Instant.parse(text);
        } catch (DateTimeParseException e) {
            throw new AdapterProtocolException("ENVELOPE_INVALID",
                    "systemTimeUtc 不是合法 ISO-8601 UTC 时间");
        }
    }
}
