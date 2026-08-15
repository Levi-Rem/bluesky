package org.bluesky.training.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;

import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ZeroMqSimulationGatewayTest {
    @Test
    void readsVersionedPingResponseFromAdapter() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        String endpoint = "tcp://127.0.0.1:" + port;

        try (ZContext serverContext = new ZContext()) {
            CompletableFuture<String> requestBody = CompletableFuture.supplyAsync(() -> {
                ZMQ.Socket server = serverContext.createSocket(SocketType.REP);
                server.setLinger(0);
                server.bind(endpoint);
                byte[] request = server.recv(0);
                server.send(("{\"protocolVersion\":\"1.0\","
                        + "\"requestId\":\"accepted\",\"success\":true,\"code\":\"OK\","
                        + "\"message\":\"\",\"payload\":{\"connected\":true,"
                        + "\"status\":\"CONNECTED\",\"performanceModel\":\"OPENAP\","
                        + "\"message\":\"BlueSky 已连接\"}}")
                        .getBytes(StandardCharsets.UTF_8), 0);
                server.close();
                return new String(request, StandardCharsets.UTF_8);
            });

            try (ZeroMqSimulationGateway gateway =
                         new ZeroMqSimulationGateway(endpoint, 2000, new ObjectMapper())) {
                EngineHealth health = gateway.health();

                assertThat(health.isConnected()).isTrue();
                assertThat(health.getStatus()).isEqualTo("CONNECTED");
                assertThat(health.getPerformanceModel()).isEqualTo("OPENAP");
            }

            assertThat(requestBody.get(2, TimeUnit.SECONDS))
                    .contains("\"protocolVersion\":\"1.0\"")
                    .contains("\"type\":\"PING\"");
        }
    }

    @Test
    void sendsResetMessageToAdapter() throws Exception {
        RecordedRequest exchange = exchangeFor(
                "{\"protocolVersion\":\"1.0\",\"requestId\":\"accepted\","
                        + "\"success\":true,\"code\":\"OK\",\"message\":\"\","
                        + "\"payload\":{\"engineState\":\"READY\"}}",
                gateway -> gateway.reset());

        assertThat(exchange.body).contains("\"type\":\"RESET\"");
    }

    @Test
    void readsReferenceItemsFromAdapter() throws Exception {
        RecordedRequest exchange = exchangeFor(
                "{\"protocolVersion\":\"1.0\",\"requestId\":\"accepted\","
                        + "\"success\":true,\"code\":\"OK\",\"message\":\"\","
                        + "\"payload\":{\"kind\":\"AIRPORT\",\"items\":[{"
                        + "\"code\":\"ZSSS\",\"name\":\"SHANGHAI HONGQIAO\","
                        + "\"latitude\":31.2,\"longitude\":121.3}]}}",
                gateway -> assertThat(gateway.searchReference("AIRPORT", "zss", 20))
                        .singleElement()
                        .satisfies(item -> assertThat(item.getCode()).isEqualTo("ZSSS")));

        assertThat(exchange.body)
                .contains("\"type\":\"REFERENCE_SEARCH\"")
                .contains("\"kind\":\"AIRPORT\"")
                .contains("\"query\":\"zss\"");
    }

    @Test
    void distinguishesAdapterRejectionFromTransportFailure() throws Exception {
        RecordedRequest exchange = exchangeFor(
                "{\"protocolVersion\":\"1.0\",\"requestId\":\"accepted\","
                        + "\"success\":false,\"code\":\"ENGINE_REJECTED\","
                        + "\"message\":\"未知航路点\",\"payload\":{}}",
                gateway -> assertThatThrownBy(() -> gateway.start())
                        .isInstanceOf(AdapterRejectedException.class)
                        .hasMessageContaining("未知航路点"));

        assertThat(exchange.body).contains("\"type\":\"START\"");
    }

    private RecordedRequest exchangeFor(String response, GatewayCall call) throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        String endpoint = "tcp://127.0.0.1:" + port;
        try (ZContext serverContext = new ZContext()) {
            CompletableFuture<String> body = CompletableFuture.supplyAsync(() -> {
                ZMQ.Socket server = serverContext.createSocket(SocketType.REP);
                server.setLinger(0);
                server.bind(endpoint);
                byte[] request = server.recv(0);
                server.send(response.getBytes(StandardCharsets.UTF_8), 0);
                server.close();
                return new String(request, StandardCharsets.UTF_8);
            });
            try (ZeroMqSimulationGateway gateway =
                         new ZeroMqSimulationGateway(endpoint, 2000, new ObjectMapper())) {
                call.invoke(gateway);
            }
            return new RecordedRequest(body.get(2, TimeUnit.SECONDS));
        }
    }

    private interface GatewayCall {
        void invoke(ZeroMqSimulationGateway gateway);
    }

    private static final class RecordedRequest {
        private final String body;

        private RecordedRequest(String body) {
            this.body = body;
        }
    }
}
