package org.bluesky.training.testsupport;

import org.bluesky.training.contract.ContractCatalog;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * P00：Java/Python 共用协议向量与最小信封校验。
 * 校验规则来自详细设计 2.2 第 10.1 节；messageType 枚举唯一来源是
 * adapter-protocol-v2.schema.json，本类不手抄消息清单。
 */
public final class AdapterProtocolFixture {

    private static final List<String> MESSAGE_KINDS = Arrays.asList("REQUEST", "RESPONSE", "EVENT");
    private static final ContractCatalog CATALOG = new ContractCatalog();

    private AdapterProtocolFixture() {
    }

    public static Map<String, Object> request(String messageType, String exerciseGroupId,
                                              String engineInstanceId, String idempotencyKey) {
        Map<String, Object> envelope = baseEnvelope("REQUEST", messageType, exerciseGroupId, engineInstanceId);
        envelope.put("requestId", "req-" + UUID.randomUUID());
        envelope.put("correlationRequestId", null);
        envelope.put("idempotencyKey", idempotencyKey);
        return envelope;
    }

    public static Map<String, Object> response(Map<String, Object> requestEnvelope, String messageType,
                                               boolean accepted) {
        Map<String, Object> envelope = baseEnvelope("RESPONSE", messageType,
                String.valueOf(requestEnvelope.get("exerciseGroupId")),
                String.valueOf(requestEnvelope.get("engineInstanceId")));
        envelope.put("requestId", "resp-" + UUID.randomUUID());
        envelope.put("correlationRequestId", requestEnvelope.get("requestId"));
        envelope.put("idempotencyKey", requestEnvelope.get("idempotencyKey"));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("accepted", accepted);
        payload.put("code", accepted ? "OK" : "REJECTED");
        payload.put("message", accepted ? "applied" : "rejected");
        payload.put("appliedEntityRevision", 1);
        payload.put("resultChecksum", "sha256:" + messageType);
        envelope.put("payload", payload);
        return envelope;
    }

    public static Map<String, Object> event(String messageType, String exerciseGroupId,
                                            String engineInstanceId, long sequence) {
        Map<String, Object> envelope = baseEnvelope("EVENT", messageType, exerciseGroupId, engineInstanceId);
        envelope.put("requestId", null);
        envelope.put("correlationRequestId", null);
        envelope.put("idempotencyKey", null);
        envelope.put("sequence", sequence);
        return envelope;
    }

    public static Map<String, Object> withSequence(Map<String, Object> envelope, long sequence) {
        Map<String, Object> copy = new LinkedHashMap<>(envelope);
        copy.put("sequence", sequence);
        return copy;
    }

    public static List<Map<String, Object>> sharedVectors() {
        return CATALOG.loadSharedVectors();
    }

    /** 校验一个共享向量（含 RESPONSE 与配对 REQUEST 的关联检查），返回问题清单。 */
    public static List<String> validateVector(Map<String, Object> vector) {
        Object envelopeObject = vector.get("envelope");
        if (!(envelopeObject instanceof Map)) {
            return Arrays.asList("向量 " + vector.get("name") + " 缺少 envelope");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> envelope = (Map<String, Object>) envelopeObject;
        List<String> problems = new ArrayList<>(validateEnvelope(envelope));
        Object correlatesTo = vector.get("correlatesToRequestId");
        if ("RESPONSE".equals(envelope.get("messageKind")) && correlatesTo != null
                && !correlatesTo.equals(envelope.get("correlationRequestId"))) {
            problems.add("RESPONSE 的 correlationRequestId 必须等于对应 REQUEST 的 requestId");
        }
        return problems;
    }

    /** 详细设计 10.1 最小校验；返回问题清单，空清单表示合法。 */
    public static List<String> validateEnvelope(Map<String, Object> envelope) {
        List<String> problems = new ArrayList<>();
        for (String field : Arrays.asList(
                "protocolVersion", "messageKind", "messageType", "exerciseGroupId",
                "engineInstanceId", "requestId", "correlationRequestId", "idempotencyKey",
                "sequence", "systemTimeUtc", "simulationTimeSeconds",
                "payloadSchemaVersion", "payload")) {
            if (!envelope.containsKey(field)) {
                problems.add("信封缺少字段: " + field);
            }
        }
        if (!"2.0".equals(envelope.get("protocolVersion"))) {
            problems.add("protocolVersion 必须为 2.0");
        }
        String messageKind = String.valueOf(envelope.get("messageKind"));
        if (!MESSAGE_KINDS.contains(messageKind)) {
            problems.add("messageKind 非法: " + messageKind);
        }
        String messageType = String.valueOf(envelope.get("messageType"));
        if (!messageTypeSet().contains(messageType)) {
            problems.add("messageType 不在 Protocol 2.0 消息枚举内: " + messageType);
        }
        if (isBlank(String.valueOf(envelope.get("exerciseGroupId")))) {
            problems.add("exerciseGroupId 不能为空");
        }
        if (isBlank(String.valueOf(envelope.get("engineInstanceId")))) {
            problems.add("engineInstanceId 不能为空");
        }
        Object sequence = envelope.get("sequence");
        if (!(sequence instanceof Number) || ((Number) sequence).longValue() < 1) {
            problems.add("sequence 必须为不小于 1 的整数");
        }
        if (isBlank(String.valueOf(envelope.get("systemTimeUtc")))) {
            problems.add("systemTimeUtc 不能为空");
        }
        Object simulationTime = envelope.get("simulationTimeSeconds");
        if (!(simulationTime instanceof Number) || ((Number) simulationTime).doubleValue() < 0) {
            problems.add("simulationTimeSeconds 必须为不小于 0 的数值");
        }
        if (!(envelope.get("payload") instanceof Map)) {
            problems.add("payload 必须是对象");
        }
        if ("REQUEST".equals(messageKind)) {
            if (envelope.get("correlationRequestId") != null) {
                problems.add("REQUEST 的 correlationRequestId 必须为空");
            }
            if (isBlank(String.valueOf(envelope.get("requestId")))) {
                problems.add("REQUEST 必须携带 requestId");
            }
        } else if ("RESPONSE".equals(messageKind)) {
            if (isBlank(String.valueOf(envelope.get("correlationRequestId")))) {
                problems.add("RESPONSE 必须携带 correlationRequestId");
            }
            if (isBlank(String.valueOf(envelope.get("requestId")))) {
                problems.add("RESPONSE 必须携带 requestId");
            }
        }
        return problems;
    }

    public static boolean pythonAvailable() {
        for (String command : Arrays.asList("python", "python3")) {
            try {
                Process process = new ProcessBuilder(command, "--version")
                        .redirectErrorStream(true)
                        .start();
                if (!process.waitFor(15, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    continue;
                }
                if (process.exitValue() == 0) {
                    return true;
                }
            } catch (IOException ignored) {
                // 尝试下一个候选命令
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /** 调用 scripts/verify_v2_contracts.py --vectors，返回进程退出码。 */
    public static int runPythonVectorVerification() throws IOException, InterruptedException {
        Path script = CATALOG.contractsDir().getParent().getParent()
                .resolve("scripts").resolve("verify_v2_contracts.py");
        if (!Files.isRegularFile(script)) {
            throw new IllegalStateException("找不到契约校验脚本: " + script.toAbsolutePath());
        }
        List<String> command = new ArrayList<>();
        command.add("python");
        command.add(script.toAbsolutePath().toString());
        command.add("--vectors");
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().put("PYTHONIOENCODING", "utf-8");
        builder.redirectErrorStream(true);
        Process process = builder.start();
        StringBuilder output = new StringBuilder();
        try (InputStream in = process.getInputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                output.append(new String(buffer, 0, read, "UTF-8"));
            }
        }
        boolean finished = process.waitFor(60, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("Python 向量校验超时:\n" + output);
        }
        if (process.exitValue() != 0) {
            System.err.println(output);
        }
        return process.exitValue();
    }

    private static Map<String, Object> baseEnvelope(String messageKind, String messageType,
                                                    String exerciseGroupId, String engineInstanceId) {
        Map<String, Object> envelope = new HashMap<>();
        envelope.put("protocolVersion", "2.0");
        envelope.put("messageKind", messageKind);
        envelope.put("messageType", messageType);
        envelope.put("exerciseGroupId", exerciseGroupId);
        envelope.put("engineInstanceId", engineInstanceId);
        envelope.put("sequence", 1L);
        envelope.put("systemTimeUtc", "2026-08-31T09:00:00.000Z");
        envelope.put("simulationTimeSeconds", 0.0);
        envelope.put("payloadSchemaVersion", null);
        envelope.put("payload", new LinkedHashMap<String, Object>());
        return envelope;
    }

    private static Set<String> messageTypeSet() {
        Map<String, Object> adapterSchema = CATALOG.loadAdapterSchema();
        Object properties = adapterSchema.get("properties");
        Object messageType = properties instanceof Map ? ((Map<?, ?>) properties).get("messageType") : null;
        Object enumObject = messageType instanceof Map ? ((Map<?, ?>) messageType).get("enum") : null;
        Set<String> values = new TreeSet<>();
        if (enumObject instanceof List) {
            for (Object value : (List<?>) enumObject) {
                values.add(String.valueOf(value));
            }
        }
        return values;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty() || "null".equals(value);
    }
}
