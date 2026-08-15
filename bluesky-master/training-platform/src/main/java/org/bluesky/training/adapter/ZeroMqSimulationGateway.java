package org.bluesky.training.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.bluesky.training.aircraft.AircraftCreateCommand;
import org.bluesky.training.instruction.EngineInstructionCommand;
import org.bluesky.training.reference.ReferenceItem;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;

@Component
public class ZeroMqSimulationGateway implements SimulationGateway, AutoCloseable {
    private static final String PROTOCOL_VERSION = "1.0";

    private final String controlEndpoint;
    private final int requestTimeoutMillis;
    private final ObjectMapper objectMapper;
    private final ZContext context;

    public ZeroMqSimulationGateway(
            @Value("${bluesky.adapter.control-endpoint}") String controlEndpoint,
            @Value("${bluesky.adapter.request-timeout-millis}") int requestTimeoutMillis,
            ObjectMapper objectMapper) {
        this.controlEndpoint = controlEndpoint;
        this.requestTimeoutMillis = requestTimeoutMillis;
        this.objectMapper = objectMapper;
        this.context = new ZContext();
    }

    @Override
    public EngineHealth health() {
        try {
            JsonNode payload = request("PING", objectMapper.createObjectNode());
            return new EngineHealth(
                    payload.path("connected").asBoolean(false),
                    payload.path("status").asText("DISCONNECTED"),
                    payload.path("performanceModel").asText("UNKNOWN"),
                    payload.path("message").asText("BlueSky 状态未知"));
        } catch (RuntimeException exception) {
            return new EngineHealth(false, "DISCONNECTED", "UNKNOWN", exception.getMessage());
        }
    }

    @Override
    public void start() {
        request("START", objectMapper.createObjectNode());
    }

    @Override
    public void pause() {
        request("PAUSE", objectMapper.createObjectNode());
    }

    @Override
    public void resume() {
        request("RESUME", objectMapper.createObjectNode());
    }

    @Override
    public void reset() {
        request("RESET", objectMapper.createObjectNode());
    }

    @Override
    public void createAircraft(AircraftCreateCommand command) {
        request("AIRCRAFT_CREATE", objectMapper.valueToTree(command));
    }

    @Override
    public void deleteAircraft(String callsign) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("callsign", callsign);
        request("AIRCRAFT_DELETE", payload);
    }

    @Override
    public void executeInstruction(EngineInstructionCommand command) {
        request("INSTRUCTION_EXECUTE", objectMapper.valueToTree(command));
    }

    @Override
    public List<ReferenceItem> searchReference(String kind, String query, int limit) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("kind", kind);
        payload.put("query", query == null ? "" : query);
        payload.put("limit", limit);
        JsonNode response = request("REFERENCE_SEARCH", payload);
        List<ReferenceItem> items = new ArrayList<>();
        for (JsonNode item : response.path("items")) {
            items.add(new ReferenceItem(
                    item.path("code").asText(),
                    item.path("name").asText(),
                    item.hasNonNull("latitude") ? item.path("latitude").asDouble() : null,
                    item.hasNonNull("longitude") ? item.path("longitude").asDouble() : null));
        }
        return items;
    }

    private JsonNode request(String type, JsonNode payload) {
        ZMQ.Socket socket = context.createSocket(SocketType.REQ);
        socket.setLinger(0);
        socket.setSendTimeOut(requestTimeoutMillis);
        socket.setReceiveTimeOut(requestTimeoutMillis);
        socket.connect(controlEndpoint);
        try {
            ObjectNode request = objectMapper.createObjectNode();
            request.put("protocolVersion", PROTOCOL_VERSION);
            request.put("requestId", UUID.randomUUID().toString());
            request.put("type", type);
            request.put("exerciseGroupId", "GROUP-DEFAULT");
            request.set("payload", payload);

            boolean sent = socket.send(objectMapper.writeValueAsBytes(request), 0);
            if (!sent) {
                throw new AdapterUnavailableException("向 BlueSky Adapter 发送请求超时");
            }
            byte[] responseBytes = socket.recv(0);
            if (responseBytes == null) {
                throw new AdapterUnavailableException("等待 BlueSky Adapter 响应超时");
            }
            JsonNode response = objectMapper.readTree(new String(responseBytes, StandardCharsets.UTF_8));
            if (!PROTOCOL_VERSION.equals(response.path("protocolVersion").asText())) {
                throw new AdapterUnavailableException("BlueSky Adapter 协议版本不兼容");
            }
            if (!response.path("success").asBoolean(false)) {
                throw new AdapterRejectedException(
                        response.path("code").asText("ENGINE_REJECTED"),
                        response.path("message").asText("BlueSky Adapter 拒绝请求"));
            }
            return response.path("payload");
        } catch (IOException exception) {
            throw new AdapterUnavailableException("BlueSky Adapter JSON 处理失败", exception);
        } finally {
            socket.close();
        }
    }

    @Override
    public void close() {
        context.close();
    }

}
