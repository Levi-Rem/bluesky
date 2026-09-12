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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P04：SUB 状态接收与缺口修复（详细设计 10.1.2：缺口触发 STATE_SNAPSHOT_GET）。 */
class AdapterStateSubscriberTest {

    private final ZContext context = new ZContext();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @AfterEach
    void tearDown() {
        context.close();
    }

    private Map<String, Object> frame(String messageType, long sequence) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("protocolVersion", "2.0");
        envelope.put("messageKind", "EVENT");
        envelope.put("messageType", messageType);
        envelope.put("exerciseGroupId", "group-1");
        envelope.put("engineInstanceId", "engine-1");
        envelope.put("requestId", null);
        envelope.put("correlationRequestId", null);
        envelope.put("idempotencyKey", null);
        envelope.put("sequence", sequence);
        envelope.put("systemTimeUtc", "2026-08-31T09:00:00.000Z");
        envelope.put("simulationTimeSeconds", 0.0);
        envelope.put("payloadSchemaVersion", null);
        envelope.put("payload", new LinkedHashMap<String, Object>());
        return envelope;
    }

    @Test
    void givenInOrderFramesWhenHandledThenNoRepairRequested() {
        AdapterStateSubscriber subscriber = new AdapterStateSubscriber("group-1", "engine-1");
        List<String> repairs = new CopyOnWriteArrayList<>();

        subscriber.handleFrame(frame("INSTRUCTION_PHASE_CHANGED", 1), repairs::add);
        subscriber.handleFrame(frame("WAYPOINT_PASSED", 2), repairs::add);

        assertTrue(repairs.isEmpty(), "顺序到达不应触发修复");
    }

    @Test
    void givenDuplicateFrameWhenHandledThenIgnoredWithoutRepair() {
        AdapterStateSubscriber subscriber = new AdapterStateSubscriber("group-1", "engine-1");
        List<String> repairs = new CopyOnWriteArrayList<>();

        subscriber.handleFrame(frame("INSTRUCTION_PHASE_CHANGED", 1), repairs::add);
        subscriber.handleFrame(frame("INSTRUCTION_PHASE_CHANGED", 1), repairs::add);

        assertTrue(repairs.isEmpty(), "重复帧只忽略，不修复");
    }

    @Test
    void givenSequenceGapWhenHandledThenSnapshotRepairIsRequested() {
        AdapterStateSubscriber subscriber = new AdapterStateSubscriber("group-1", "engine-1");
        List<String> repairs = new CopyOnWriteArrayList<>();

        subscriber.handleFrame(frame("INSTRUCTION_PHASE_CHANGED", 1), repairs::add);
        subscriber.handleFrame(frame("INSTRUCTION_PHASE_CHANGED", 3), repairs::add);

        assertEquals(1, repairs.size(), "缺口必须触发一次修复请求");
        assertEquals("engine-1", repairs.get(0));
        assertTrue(subscriber.isOutOfSync());
    }

    @Test
    void givenPublisherSendsFramesWhenSubscribedThenFramesAndGapsDelivered() throws Exception {
        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        String endpoint = "tcp://127.0.0.1:" + port;
        ZMQ.Socket publisher = context.createSocket(SocketType.PUB);
        publisher.setLinger(0);
        publisher.bind(endpoint);

        List<Map<String, Object>> received = new CopyOnWriteArrayList<>();
        List<String> repairs = new CopyOnWriteArrayList<>();
        CountDownLatch sawFrames = new CountDownLatch(2);
        CountDownLatch sawRepair = new CountDownLatch(1);

        AdapterStateSubscriber subscriber = new AdapterStateSubscriber("group-1", "engine-1");
        subscriber.subscribe(endpoint, frame -> {
            received.add(frame);
            sawFrames.countDown();
        }, engineId -> {
            repairs.add(engineId);
            sawRepair.countDown();
        });

        try {
            // PUB/SUB 需要短暂时间建立订阅
            Thread.sleep(300);
            publisher.send(objectMapper.writeValueAsBytes(frame("INSTRUCTION_PHASE_CHANGED", 1)));
            publisher.send(objectMapper.writeValueAsBytes(frame("WAYPOINT_PASSED", 2)));
            publisher.send(objectMapper.writeValueAsBytes(frame("INSTRUCTION_PHASE_CHANGED", 4)));

            assertTrue(sawFrames.await(5, TimeUnit.SECONDS), "只应投影缺口前的 2 帧");
            assertTrue(sawRepair.await(5, TimeUnit.SECONDS), "缺口 2→4 必须触发修复");
            assertEquals(2, received.size(), "缺口帧必须等待快照修复，不能直接进入投影");
        } finally {
            subscriber.close();
            publisher.close();
        }
    }

    private static int unusedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
