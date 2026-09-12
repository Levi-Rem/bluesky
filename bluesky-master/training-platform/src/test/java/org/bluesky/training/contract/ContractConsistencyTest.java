package org.bluesky.training.contract;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P00：机器可读契约与测试基础设施。
 * 详细设计 2.2 第 17.11 条要求 OpenAPI、SSE Schema、Adapter Schema 和命令目录
 * 通过双向一致性测试；本测试是 Java 侧的定桩入口。
 */
class ContractConsistencyTest {

    private final ContractCatalog catalog = new ContractCatalog();
    private final ContractConsistencyValidator validator = new ContractConsistencyValidator();

    @Test
    void givenV2ArtifactsWhenLoadedThenAllRequiredEnumsMatch() {
        Map<String, Object> openApi = catalog.loadOpenApi();
        Map<String, Object> sseSchema = catalog.loadSseSchema();
        Map<String, Object> adapterSchema = catalog.loadAdapterSchema();
        Map<String, Object> commandCatalog = catalog.loadCommandCatalog();
        List<Map<String, Object>> vectors = catalog.loadSharedVectors();

        assertTrue(String.valueOf(openApi.get("openapi")).startsWith("3."), "openapi-v2.yaml 必须是合法 OpenAPI 3 文档");
        assertEquals("2.0", adapterSchema.get("x-protocol-version"), "Adapter Schema 必须声明 Protocol 2.0");
        assertFalse(vectors.isEmpty(), "共享协议向量不能为空");

        List<String> problems = validator.validateAll(openApi, sseSchema, adapterSchema, commandCatalog, vectors);
        assertEquals(Collections.emptyList(), problems, () -> "契约与详细设计 2.2 不一致: " + problems);
    }

    @Test
    void givenDesignEnumsWhenComparedToAdapterMessagesThenExactlyMatch() {
        Map<String, Object> adapterSchema = catalog.loadAdapterSchema();

        List<String> problems = validator.validateAdapterMessages(adapterSchema, catalog.loadSharedVectors());

        assertEquals(Collections.emptyList(), problems, () -> "Adapter 消息类型枚举不一致: " + problems);
        assertNotNull(adapterSchema.get("x-frame-max-bytes"), "Adapter Schema 必须声明 1 MiB 帧上限");
        assertEquals(Integer.valueOf(1048576), adapterSchema.get("x-frame-max-bytes"));
    }
}
