package org.bluesky.training.adapter;

import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;
import org.zeromq.ZPoller;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * P04：DEALER 请求关联与重试（详细设计 10.1.1/10.1.7）。
 * 同一 requestId + idempotencyKey 最多重发 3 次，总确认窗口 ≤5 秒；
 * 迟到响应仍可用于对账，但不覆盖已确认状态。
 */
public class AdapterControlClient implements AutoCloseable {

    public static final long DEFAULT_ACK_TIMEOUT_MILLIS = 2_000L;
    public static final long DEFAULT_TOTAL_WINDOW_MILLIS = 5_000L;
    public static final int DEFAULT_MAX_ATTEMPTS = 3;

    private final ZContext context;
    private final long ackTimeoutMillis;
    private final long totalWindowMillis;
    private final int maxAttempts;
    private final ProtocolEnvelopeCodec codec = new ProtocolEnvelopeCodec();
    private final ProtocolSequenceTracker sequenceTracker = new ProtocolSequenceTracker(null);

    private ZMQ.Socket dealer;
    private String exerciseGroupId;
    private String engineInstanceId;

    public AdapterControlClient() {
        this(DEFAULT_ACK_TIMEOUT_MILLIS, DEFAULT_TOTAL_WINDOW_MILLIS, DEFAULT_MAX_ATTEMPTS);
    }

    public AdapterControlClient(long ackTimeoutMillis, long totalWindowMillis, int maxAttempts) {
        this.ackTimeoutMillis = ackTimeoutMillis;
        this.totalWindowMillis = totalWindowMillis;
        this.maxAttempts = maxAttempts;
        this.context = new ZContext();
    }

    public void connect(String endpoint) {
        if (dealer != null) {
            throw new IllegalStateException("DEALER 已连接: " + endpoint);
        }
        dealer = context.createSocket(SocketType.DEALER);
        dealer.setLinger(0);
        dealer.connect(endpoint);
    }

    public Map<String, Object> hello(String exerciseGroupId, String engineInstanceId,
                                     String idempotencyKey) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("protocolVersion", "2.0");
        Map<String, Object> request = createRequestEnvelope(
                "HELLO", exerciseGroupId, engineInstanceId, idempotencyKey, payload);
        Map<String, Object> response = send(request);
        return expectMessageType(response, "HELLO_ACK");
    }

    public Map<String, Object> queryInstructionStatus(String exerciseGroupId,
                                                      String engineInstanceId,
                                                      String instructionId,
                                                      String idempotencyKey) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("instructionId", instructionId);
        Map<String, Object> request = createRequestEnvelope(
                "INSTRUCTION_STATUS_GET", exerciseGroupId, engineInstanceId,
                idempotencyKey, payload);
        return expectMessageType(send(request), "INSTRUCTION_STATUS_RESULT");
    }

    /** 发送并等待关联响应；窗口内同键重试，超时抛 ADAPTER_ACK_TIMEOUT。 */
    public synchronized Map<String, Object> send(Map<String, Object> requestEnvelope) {
        requireConnected();
        byte[] frame = codec.encode(requestEnvelope);
        Object requestId = requestEnvelope.get("requestId");
        long deadline = System.currentTimeMillis() + totalWindowMillis;

        AdapterProtocolException lastFailure = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            dealer.send(frame);
            long remaining = Math.min(ackTimeoutMillis, deadline - System.currentTimeMillis());
            if (remaining < 0) {
                remaining = 0;
            }
            ZPoller poller = new ZPoller(context);
            poller.register(dealer, ZPoller.POLLIN);
            try {
                while (System.currentTimeMillis() < deadline) {
                    int ready = poller.poll(Math.max(1, remaining));
                    if (ready <= 0) {
                        break;
                    }
                    byte[] reply = dealer.recv();
                    if (reply == null) {
                        break;
                    }
                    Map<String, Object> response;
                    try {
                        response = codec.decode(reply);
                    } catch (AdapterProtocolException e) {
                        lastFailure = e;
                        continue;
                    }
                    if (requestId.equals(response.get("correlationRequestId"))) {
                        validateCorrelatedResponse(requestEnvelope, response);
                        Object payload = response.get("payload");
                        // A crash may reserve a sequence before sending. The runtime's GAP
                        // response resynchronizes its tracker; resend this unchanged request.
                        if (payload instanceof Map && "SEQUENCE_GAP".equals(((Map<?,?>) payload).get("code")))
                            break;
                        return response;
                    }
                    // 迟到或无关响应：仍须消化其序号（评审 B1）。超时重试成功后
                    // 迟到的重放副本若不消化序号，残留序号将使下一请求命中
                    // RESPONSE_SEQUENCE_GAP 且旧实现无任何恢复路径。
                    skipInboundSequence(response);
                    remaining = Math.max(1, deadline - System.currentTimeMillis());
                }
            } finally {
                try {
                    poller.close();
                } catch (IOException ignored) {
                    // 轮询器关闭失败不影响重试语义
                }
            }
            if (System.currentTimeMillis() >= deadline) {
                break;
            }
        }
        throw lastFailure == null
                ? new AdapterProtocolException("ADAPTER_ACK_TIMEOUT",
                        "Adapter 技术确认超时: " + requestId)
                : new AdapterProtocolException("ADAPTER_ACK_TIMEOUT",
                        "Adapter 技术确认超时: " + requestId + " / " + lastFailure.getMessage());
    }

    @Override
    public void close() {
        context.close();
    }

    private void requireConnected() {
        if (dealer == null) {
            throw new IllegalStateException("DEALER 尚未 connect");
        }
    }

    private static Map<String, Object> expectMessageType(Map<String, Object> response,
                                                         String expected) {
        if (!expected.equals(response.get("messageType"))) {
            throw new AdapterProtocolException("UNEXPECTED_RESPONSE",
                    "期望 " + expected + " 实际 " + response.get("messageType"));
        }
        return response;
    }

    public synchronized Map<String, Object> createRequestEnvelope(
            String messageType, String exerciseGroupId, String engineInstanceId,
            String idempotencyKey, Map<String, Object> payload) {
        bindRoute(exerciseGroupId, engineInstanceId);
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("protocolVersion", "2.0");
        envelope.put("messageKind", "REQUEST");
        envelope.put("messageType", messageType);
        envelope.put("exerciseGroupId", exerciseGroupId);
        envelope.put("engineInstanceId", engineInstanceId);
        envelope.put("requestId", "req-" + UUID.randomUUID());
        envelope.put("correlationRequestId", null);
        envelope.put("idempotencyKey", idempotencyKey);
        envelope.put("sequence", sequenceTracker.nextOutbound());
        envelope.put("systemTimeUtc", Instant.now().toString());
        envelope.put("simulationTimeSeconds", 0.0);
        envelope.put("payloadSchemaVersion", null);
        envelope.put("payload", payload);
        return envelope;
    }

    /** 引擎重建后显式切换实例；新实例双向序号都必须从 1 重新开始。 */
    public synchronized void resetForInstance(String exerciseGroupId, String engineInstanceId) {
        this.exerciseGroupId = requireRoutePart("exerciseGroupId", exerciseGroupId);
        this.engineInstanceId = requireRoutePart("engineInstanceId", engineInstanceId);
        sequenceTracker.resetForInstance(engineInstanceId);
    }

    private synchronized void bindRoute(String groupId, String instanceId) {
        String checkedGroup = requireRoutePart("exerciseGroupId", groupId);
        String checkedInstance = requireRoutePart("engineInstanceId", instanceId);
        if (exerciseGroupId == null) {
            resetForInstance(checkedGroup, checkedInstance);
            return;
        }
        if (!exerciseGroupId.equals(checkedGroup) || !engineInstanceId.equals(checkedInstance)) {
            throw new AdapterProtocolException("ADAPTER_ROUTE_MISMATCH",
                    "同一客户端不得混用训练组或引擎实例；请先 resetForInstance");
        }
    }

    private void validateCorrelatedResponse(Map<String, Object> request,
                                            Map<String, Object> response) {
        if (!"RESPONSE".equals(response.get("messageKind"))) {
            throw new AdapterProtocolException("UNEXPECTED_MESSAGE_KIND",
                    "control 请求只能由 RESPONSE 确认");
        }
        if (!Objects.equals(request.get("exerciseGroupId"), response.get("exerciseGroupId"))) {
            throw new AdapterProtocolException("EXERCISE_GROUP_MISMATCH", "响应训练组与请求不一致");
        }
        if (!Objects.equals(request.get("engineInstanceId"), response.get("engineInstanceId"))) {
            throw new AdapterProtocolException("ENGINE_INSTANCE_MISMATCH", "响应引擎实例与请求不一致");
        }
        if (!Objects.equals(request.get("idempotencyKey"), response.get("idempotencyKey"))) {
            throw new AdapterProtocolException("IDEMPOTENCY_KEY_MISMATCH", "响应未复制请求幂等键");
        }
        ProtocolSequenceTracker.AcceptResult result = sequenceTracker.acceptInbound(
                ((Number) response.get("sequence")).longValue());
        if (result != ProtocolSequenceTracker.AcceptResult.IN_ORDER) {
            // 序号缺口/重复不再中断请求（评审 B1）：响应本身已通过关联、路由与幂等键
            // 校验；缺口只保留 OUT_OF_SYNC 标记（isOutOfSync 供健康监控消费），
            // 下一到达序号由 tracker 自动重新同步，通道不因一次缺口永久死锁。
            // DUPLICATE 为幂等重放的迟到副本，内容一致，直接接受。
        }
    }

    /** 消化被丢弃响应的序号：只推进基准，结果不构成拒绝条件（评审 B1）。 */
    private void skipInboundSequence(Map<String, Object> response) {
        Object sequence = response.get("sequence");
        if (sequence instanceof Number) {
            sequenceTracker.acceptInbound(((Number) sequence).longValue());
        }
    }

    private static String requireRoutePart(String name, String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return value;
    }
}
