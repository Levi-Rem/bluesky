package org.bluesky.training.adapter;

import org.junit.jupiter.api.Test;
import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;

import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ZeroMqStateSubscriberTest {
    @Test
    void forwardsStateTopicPayloadToProjector() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        String endpoint = "tcp://127.0.0.1:" + port;
        AdapterStateProjector projector = mock(AdapterStateProjector.class);
        CountDownLatch received = new CountDownLatch(1);
        doAnswer(invocation -> {
            received.countDown();
            return null;
        }).when(projector).acceptJson(contains("\"sequence\":9"));

        try (ZContext publisherContext = new ZContext();
             ZeroMqStateSubscriber subscriber = new ZeroMqStateSubscriber(endpoint, projector, true)) {
            ZMQ.Socket publisher = publisherContext.createSocket(SocketType.PUB);
            publisher.setLinger(0);
            publisher.bind(endpoint);
            subscriber.start();

            byte[] topic = "state.GROUP-DEFAULT".getBytes(StandardCharsets.UTF_8);
            byte[] body = "{\"protocolVersion\":\"1.0\",\"sequence\":9}"
                    .getBytes(StandardCharsets.UTF_8);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (received.getCount() > 0 && System.nanoTime() < deadline) {
                publisher.sendMore(topic);
                publisher.send(body, 0);
                received.await(50, TimeUnit.MILLISECONDS);
            }

            assertThat(received.await(100, TimeUnit.MILLISECONDS)).isTrue();
            verify(projector).acceptJson(contains("\"sequence\":9"));
            publisher.close();
        }
    }
}
