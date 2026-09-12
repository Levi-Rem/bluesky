package org.bluesky.training.adapter;

import org.bluesky.training.reference.ReferenceSnapshotStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * P03 遗留契约：Java/Python REFERENCE_SNAPSHOT_LOAD checksum 一致
 * （详细设计 §11.3：STARTING 时通过 REFERENCE_SNAPSHOT_LOAD 装入 Adapter，
 * Adapter 返回 manifest 总 checksum；不相同则禁止进入 RUNNING）。
 */
class ReferenceSnapshotLoadContractTest {

    private Process adapterProcess;
    private AdapterControlClient client;

    @BeforeEach
    void requirePython() {
        Assumptions.assumeTrue(org.bluesky.training.testsupport.AdapterProtocolFixture
                .pythonAvailable(), "当前环境无 python，跳过跨语言契约");
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
        if (adapterProcess != null) {
            adapterProcess.destroy();
            try {
                adapterProcess.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Test
    void givenSameManifestWhenLoadedByAdapterThenChecksumMatchesJavaComputation() throws Exception {
        String manifestJson = "{"
                + "\"schemaVersion\":\"reference-manifest/1\","
                + "\"sourceBatch\":\"batch-contract\","
                + "\"resources\":{\"airports\":{\"file\":\"airports.json\","
                + "\"sha256\":\"" + ReferenceSnapshotStore.sha256OfText("[{\"id\":\"ZGGG\"}]")
                + "}}}";
        String expectedChecksum = ReferenceSnapshotStore.sha256OfText(manifestJson);

        startAdapterStub();
        client.connect(controlEndpoint());
        Map<String, Object> response = client.send(loadRequest(manifestJson));

        assertEquals("REFERENCE_SNAPSHOT_ACK", response.get("messageType"));
        assertEquals(expectedChecksum,
                ((Map<?, ?>) response.get("payload")).get("manifestChecksum"),
                "Java 与 Python 对同一 manifest 必须得出相同 checksum");
        assertEquals(Boolean.TRUE, ((Map<?, ?>) response.get("payload")).get("accepted"));
    }

    private Map<String, Object> loadRequest(String manifestJson) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("manifestJson", manifestJson);
        payload.put("resources", new LinkedHashMap<String, Object>());
        return client.createRequestEnvelope(
                "REFERENCE_SNAPSHOT_LOAD", "group-contract", engineInstanceId(),
                "contract-key-1", payload);
    }

    // ------------------------------------------------------------------ stub 进程管理

    private int controlPort = -1;
    private int statePort = -1;
    private String engineInstanceId = "engine-contract";

    private String controlEndpoint() {
        return "tcp://127.0.0.1:" + controlPort;
    }

    private String engineInstanceId() {
        return engineInstanceId;
    }

    private void startAdapterStub() throws Exception {
        controlPort = freePort();
        statePort = freePort();
        Path script = repoRoot().resolve("scripts").resolve("reference_snapshot_adapter_stub.py");
        ProcessBuilder builder = new ProcessBuilder(
                "python", script.toAbsolutePath().toString(),
                "--control-endpoint", controlEndpoint(),
                "--state-endpoint", "tcp://127.0.0.1:" + statePort);
        builder.environment().put("PYTHONIOENCODING", "utf-8");
        builder.redirectErrorStream(true);
        adapterProcess = builder.start();

        // 桩就绪前打印一行 JSON；随后等待 control 端口可连接
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(adapterProcess.getInputStream(), StandardCharsets.UTF_8))) {
            String ready = reader.readLine();
            if (ready != null && ready.trim().startsWith("{")) {
                com.fasterxml.jackson.databind.JsonNode node =
                        new com.fasterxml.jackson.databind.ObjectMapper().readTree(ready);
                if (node.has("engineInstanceId")) {
                    engineInstanceId = node.get("engineInstanceId").asText();
                }
            }
        }
        waitForPort(controlPort, 10_000);
        client = new AdapterControlClient();
    }

    private static void waitForPort(int port, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            try (Socket socket = new Socket("127.0.0.1", port)) {
                return;
            } catch (IOException e) {
                Thread.sleep(100);
            }
        }
        throw new IllegalStateException("Adapter 桩端口未就绪: " + port);
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Path repoRoot() {
        return Paths.get("..").toAbsolutePath().normalize();
    }
}
