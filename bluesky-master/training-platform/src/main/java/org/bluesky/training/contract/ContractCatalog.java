package org.bluesky.training.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * P00：测试期加载四类 v2 契约与共享协议向量。
 * 契约目录默认位于仓库 docs/contracts；可用系统属性 {@code v2.contracts.dir} 覆盖。
 */
public final class ContractCatalog {

    public static final String CONTRACT_DIR_PROPERTY = "v2.contracts.dir";
    static final String OPEN_API_FILE = "openapi-v2.yaml";
    static final String SSE_SCHEMA_FILE = "sse-event-v2.schema.json";
    static final String ADAPTER_SCHEMA_FILE = "adapter-protocol-v2.schema.json";
    static final String COMMAND_CATALOG_FILE = "command-catalog-v2.json";
    static final String SHARED_VECTORS_FILE = "vectors/adapter-protocol-v2-vectors.json";

    private static final List<String> DEFAULT_CANDIDATES = Arrays.asList(
            "../docs/contracts", "docs/contracts", "../bluesky-master/docs/contracts");

    private final Path contractsDir;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ContractCatalog() {
        this(resolveDefaultContractsDir());
    }

    public ContractCatalog(Path contractsDir) {
        this.contractsDir = contractsDir;
    }

    public Path contractsDir() {
        return contractsDir;
    }

    public Map<String, Object> loadOpenApi() {
        return parseYaml(OPEN_API_FILE);
    }

    public Map<String, Object> loadSseSchema() {
        return parseJson(SSE_SCHEMA_FILE);
    }

    public Map<String, Object> loadAdapterSchema() {
        return parseJson(ADAPTER_SCHEMA_FILE);
    }

    public Map<String, Object> loadCommandCatalog() {
        return parseJson(COMMAND_CATALOG_FILE);
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> loadSharedVectors() {
        Map<String, Object> document = parseJson(SHARED_VECTORS_FILE);
        Object vectors = document.get("vectors");
        if (!(vectors instanceof List)) {
            throw new IllegalStateException(SHARED_VECTORS_FILE + " 缺少 vectors 数组");
        }
        return (List<Map<String, Object>>) vectors;
    }

    private Map<String, Object> parseYaml(String name) {
        Path file = contractsDir.resolve(name);
        try (InputStream in = open(name)) {
            return asMap(new Yaml().load(in), name);
        } catch (IOException e) {
            throw new UncheckedIOException("无法读取契约文件 " + file.toAbsolutePath(), e);
        }
    }

    private Map<String, Object> parseJson(String name) {
        Path file = contractsDir.resolve(name);
        try (InputStream in = open(name)) {
            return asMap(objectMapper.readValue(in, Object.class), name);
        } catch (IOException e) {
            throw new UncheckedIOException("无法读取契约文件 " + file.toAbsolutePath(), e);
        }
    }

    private InputStream open(String name) throws IOException {
        Path file = contractsDir.resolve(name);
        if (Files.isRegularFile(file)) return Files.newInputStream(file);
        InputStream bundled = ContractCatalog.class.getResourceAsStream("/contracts/" + name);
        if (bundled != null) return bundled;
        throw new java.io.FileNotFoundException(file.toString());
    }

    private static Map<String, Object> asMap(Object parsed, String name) {
        if (!(parsed instanceof Map)) {
            throw new IllegalStateException("契约文件 " + name + " 必须是对象/映射");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) parsed;
        return map;
    }

    private static Path resolveDefaultContractsDir() {
        String configured = System.getProperty(CONTRACT_DIR_PROPERTY);
        if (configured != null && !configured.trim().isEmpty()) {
            return Paths.get(configured.trim());
        }
        for (String candidate : DEFAULT_CANDIDATES) {
            Path path = Paths.get(candidate);
            if (Files.isDirectory(path)) {
                return path;
            }
        }
        return Paths.get(DEFAULT_CANDIDATES.get(0));
    }
}
