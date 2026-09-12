package org.bluesky.training.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P04：DEALER 请求关联与重试（详细设计 10.1.7：同一 requestId/幂等键最多 3 次、窗口 ≤5 秒）。 */
class AdapterControlClientTest {

    private final ZContext context = new ZContext();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<String> controlEndpoints = new ArrayList<>();

    @AfterEach
    void tearDown() {
        context.close();
    }

    private int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String newEndpoint() {
        String endpoint = "tcp://127.0.0.1:" + freePort();
        controlEndpoints.add(endpoint);
        return endpoint;
    }

    /** 起一个 ROUTER 应答器：丢弃前 dropCount 个请求，之后按 correlation 应答。 */
    private Thread routerResponder(String endpoint, int dropCount, CountDownLatch receivedFirst,
                                   List<Map<String, Object>> received) {
        Thread thread = new Thread(() -> {
            ZMQ.Socket router = context.createSocket(SocketType.ROUTER);
            router.setLinger(0);
            router.bind(endpoint);
            try {
                int dropped = 0;
                while (!Thread.currentThread().isInterrupted()) {
                    byte[] identity = router.recv();
                    byte[] payload = router.recv();
                    Map<String, Object> request = objectMapper.readValue(payload, Map.class);
                    received.add(request);
                    if (receivedFirst != null) {
                        receivedFirst.countDown();
                    }
                    if (dropped++ < dropCount) {
                        continue;
                    }
                    Map<String, Object> response = new LinkedHashMap<>();
                    response.put("protocolVersion", "2.0");
                    response.put("messageKind", "RESPONSE");
                    response.put("messageType", "HELLO_ACK");
                    response.put("exerciseGroupId", request.get("exerciseGroupId"));
                    response.put("engineInstanceId", request.get("engineInstanceId"));
                    response.put("requestId", "resp-" + dropped);
                    response.put("correlationRequestId", request.get("requestId"));
                    response.put("idempotencyKey", request.get("idempotencyKey"));
                    response.put("sequence", 1);
                    response.put("systemTimeUtc", "2026-08-31T09:00:00.000Z");
                    response.put("simulationTimeSeconds", 0.0);
                    response.put("payloadSchemaVersion", null);
                    response.put("payload", new LinkedHashMap<String, Object>());
                    router.sendMore(identity);
                    router.send(objectMapper.writeValueAsBytes(response));
                }
            } catch (Exception ignored) {
                // 测试关闭时退出
            } finally {
                router.close();
            }
        }, "router-responder");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    @Test
    void givenResponseArrivesThenCorrelatedRequestCompletes() throws Exception {
        String endpoint = newEndpoint();
        routerResponder(endpoint, 0, null, new ArrayList<>());

        try (AdapterControlClient client = new AdapterControlClient()) {
            client.connect(endpoint);
            Map<String, Object> response = client.hello("group-1", "engine-1", "hello-key");

            assertEquals("HELLO_ACK", response.get("messageType"));
            assertEquals("RESPONSE", response.get("messageKind"));
        }
    }

    @Test
    void givenAckTimeoutWhenRetriedThenRequestIdAndKeyStaySame() throws Exception {
        String endpoint = newEndpoint();
        List<Map<String, Object>> received = new ArrayList<>();
        CountDownLatch firstReceived = new CountDownLatch(1);
        routerResponder(endpoint, 2, firstReceived, received);

        try (AdapterControlClient client = new AdapterControlClient()) {
            client.connect(endpoint);
            Map<String, Object> response = client.hello("group-1", "engine-1", "retry-key");

            assertEquals("HELLO_ACK", response.get("messageType"));
        }

        assertTrue(firstReceived.await(5, TimeUnit.SECONDS));
        assertTrue(received.size() >= 3, "应发生重试，实际收到 " + received.size() + " 个请求");
        Object requestId = received.get(0).get("requestId");
        Object idempotencyKey = received.get(0).get("idempotencyKey");
        for (Map<String, Object> request : received) {
            assertEquals(requestId, request.get("requestId"), "重试必须复用同一 requestId");
            assertEquals(idempotencyKey, request.get("idempotencyKey"), "重试必须复用同一幂等键");
        }
        assertEquals("retry-key", idempotencyKey);
    }

    @Test
    void givenNoAnswerWithinWindowThenAckTimeoutRaised() throws Exception {
        String endpoint = newEndpoint();
        List<Map<String, Object>> received = new ArrayList<>();
        routerResponder(endpoint, Integer.MAX_VALUE, null, received);

        try (AdapterControlClient client = new AdapterControlClient(200, 1000, 3)) {
            client.connect(endpoint);

            AdapterProtocolException failure = assertThrows(AdapterProtocolException.class,
                    () -> client.hello("group-1", "engine-1", "timeout-key"));
            assertEquals("ADAPTER_ACK_TIMEOUT", failure.code());
        }
        assertTrue(received.size() <= 3, "最多重发 3 次，实际 " + received.size());
        assertTrue(received.size() >= 1);
    }

    @Test
    void givenInstructionStatusQueryWhenSentThenUsesDedicatedMessageType() throws Exception {
        String endpoint = newEndpoint();
        AtomicReference<Map<String, Object>> lastRequest = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            ZMQ.Socket router = context.createSocket(SocketType.ROUTER);
            router.setLinger(0);
            router.bind(endpoint);
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    byte[] identity = router.recv();
                    Map<String, Object> request = objectMapper.readValue(router.recv(), Map.class);
                    lastRequest.set(request);
                    Map<String, Object> response = new LinkedHashMap<>(request);
                    response.put("messageKind", "RESPONSE");
                    response.put("messageType", "INSTRUCTION_STATUS_RESULT");
                    response.put("requestId", "resp-x");
                    response.put("correlationRequestId", request.get("requestId"));
                    router.sendMore(identity);
                    router.send(objectMapper.writeValueAsBytes(response));
                }
            } catch (Exception ignored) {
                // 关闭退出
            } finally {
                router.close();
            }
        }, "router-status");
        thread.setDaemon(true);
        thread.start();

        try (AdapterControlClient client = new AdapterControlClient()) {
            client.connect(endpoint);
            Map<String, Object> response = client.queryInstructionStatus(
                    "group-1", "engine-1", "instruction-1", "instruction-1");

            assertEquals("INSTRUCTION_STATUS_RESULT", response.get("messageType"));
            assertTrue(lastRequest.get().get("messageType").equals("INSTRUCTION_STATUS_GET"));
            assertEquals("instruction-1", lastRequest.get().get("idempotencyKey"));
        } finally {
            thread.interrupt();
        }
    }

    @Test
    void givenTwoRequestsWhenCreatedThenSequenceIncrementsAndTimeIsCurrent() {
        try (AdapterControlClient client = new AdapterControlClient()) {
            Map<String, Object> first = client.createRequestEnvelope(
                    "HELLO", "group-1", "engine-1", "key-1", new LinkedHashMap<>());
            Map<String, Object> second = client.createRequestEnvelope(
                    "HELLO", "group-1", "engine-1", "key-2", new LinkedHashMap<>());

            assertEquals(1L, first.get("sequence"));
            assertEquals(2L, second.get("sequence"));
            assertTrue(!String.valueOf(first.get("systemTimeUtc")).startsWith("1970-"));
            assertTrue(first.containsKey("payloadSchemaVersion"));
        }
    }

    /**
     * 评审 B1 回归：超时重试成功后残留的幂等重放副本不得使下一请求
     * RESPONSE_SEQUENCE_GAP。真实 Python adapter 会因幂等缓存对重试再回一条
     * （响应序号已推进），旧实现在丢弃该副本时不消化序号，导致通道死锁。
     */
    @Test
    void givenStaleReplayAfterRetryThenNextRequestStillSucceeds() throws Exception {
        String endpoint = newEndpoint();
        Thread thread = new Thread(() -> {
            ZMQ.Socket router = context.createSocket(SocketType.ROUTER);
            router.setLinger(0);
            router.bind(endpoint);
            long responseSequence = 0;
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    byte[] identity = router.recv();
                    Map<String, Object> request =
                            objectMapper.readValue(router.recv(), Map.class);
                    String requestId = String.valueOf(request.get("requestId"));
                    if (requestId.startsWith("req-stale-second")) {
                        // 第二个逻辑请求：立即回复（响应序号已推进到 3）
                        router.sendMore(identity);
                        router.send(objectMapper.writeValueAsBytes(responseOf(
                                request, ++responseSequence)));
                        continue;
                    }
                    // 第一个逻辑请求：延迟到客户端重试后才回复两条
                    // （原响应 + 幂等重放副本），副本将残留到下一个请求窗口
                    Thread.sleep(300);
                    router.sendMore(identity);
                    router.send(objectMapper.writeValueAsBytes(responseOf(
                            request, ++responseSequence)));
                    Thread.sleep(50);
                    router.sendMore(identity);
                    router.send(objectMapper.writeValueAsBytes(responseOf(
                            request, ++responseSequence)));
                }
            } catch (Exception ignored) {
                // 关闭退出
            } finally {
                router.close();
            }
        }, "router-stale-replay");
        thread.setDaemon(true);
        thread.start();

        try (AdapterControlClient client = new AdapterControlClient(1_000, 10_000, 3)) {
            client.connect(endpoint);
            Map<String, Object> first = client.hello("group-1", "engine-1", "stale-key");
            assertEquals("HELLO_ACK", first.get("messageType"));

            // 残留副本（sequence=2）在本窗口到达并被丢弃消化；本请求响应 sequence=3 顺序成立
            Map<String, Object> second = client.hello("group-1", "engine-1", "stale-key-2");
            assertEquals("HELLO_ACK", second.get("messageType"),
                    "残留重放副本后的下一请求必须成功（评审 B1）");
        } finally {
            thread.interrupt();
        }
    }

    private Map<String, Object> responseOf(Map<String, Object> request, long sequence) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("protocolVersion", "2.0");
        response.put("messageKind", "RESPONSE");
        response.put("messageType", "HELLO_ACK");
        response.put("exerciseGroupId", request.get("exerciseGroupId"));
        response.put("engineInstanceId", request.get("engineInstanceId"));
        response.put("requestId", "resp-" + sequence);
        response.put("correlationRequestId", request.get("requestId"));
        response.put("idempotencyKey", request.get("idempotencyKey"));
        response.put("sequence", sequence);
        response.put("systemTimeUtc", "2026-08-31T09:00:00.000Z");
        response.put("simulationTimeSeconds", 0.0);
        response.put("payloadSchemaVersion", null);
        response.put("payload", new LinkedHashMap<String, Object>());
        return response;
    }
}
