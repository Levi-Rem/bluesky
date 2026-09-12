package org.bluesky.training.common;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * P20：v1 运行期依赖扫描（详细设计 2.2 §13.4/§17.4/§25.2）。
 * 前端 API 引用、Spring 映射、Protocol 1.0 任一残留即列出违规清单。
 */
public class V1RetirementVerifier {

    public static final String V1_API_PREFIX = "/api/v1";
    public static final String V1_FRONTEND_MODULE = "src/api.ts";
    public static final String V1_PROTOCOL_VERSION = "\"protocolVersion\": \"1.";

    /** 扫描前端源文件中的 /api/v1 调用。 */
    public List<String> scanFrontendRoutes(Path frontendSrcDir) throws IOException {
        List<String> violations = new ArrayList<>();
        if (frontendSrcDir == null || !Files.isDirectory(frontendSrcDir)) {
            return violations;
        }
        try (Stream<Path> files = Files.walk(frontendSrcDir)) {
            files.filter(path -> path.toString().endsWith(".ts") || path.toString().endsWith(".vue"))
                    .forEach(path -> {
                        String content = readQuietly(path);
                        if (content != null && content.contains(V1_API_PREFIX)) {
                            violations.add("前端引用 v1 API: " + path.getFileName());
                        }
                    });
        }
        return violations;
    }

    /** 扫描 Java Controller 映射注解中的 /api/v1。 */
    public List<String> scanSpringMappings(Path mainJavaDir) throws IOException {
        List<String> violations = new ArrayList<>();
        if (mainJavaDir == null || !Files.isDirectory(mainJavaDir)) {
            return violations;
        }
        try (Stream<Path> files = Files.walk(mainJavaDir)) {
            files.filter(path -> path.toString().endsWith(".java"))
                    .forEach(path -> {
                        String content = readQuietly(path);
                        if (content != null && content.contains(V1_API_PREFIX)
                                && (content.contains("@RestController")
                                        || content.contains("@RequestMapping"))
                                && !path.getFileName().toString()
                                        .equals("V1RetirementVerifier.java")) {
                            violations.add("Spring 映射仍指向 v1: " + path.getFileName());
                        }
                    });
        }
        return violations;
    }

    /** 扫描 Adapter 侧是否仍存在 Protocol 1.0 入口。 */
    public List<String> scanProtocolVersion(Path adapterDir) throws IOException {
        List<String> violations = new ArrayList<>();
        if (adapterDir == null || !Files.isDirectory(adapterDir)) {
            return violations;
        }
        try (Stream<Path> files = Files.walk(adapterDir)) {
            files.filter(path -> path.toString().endsWith(".py"))
                    .filter(path -> !path.getFileName().toString().startsWith("test_"))
                    .forEach(path -> {
                        String content = readQuietly(path);
                        if (content != null
                                && (content.contains(V1_PROTOCOL_VERSION)
                                        || content.contains("'1.0'"))) {
                            violations.add("Adapter 仍含 Protocol 1.0 引用: "
                                    + path.getFileName());
                        }
                    });
        }
        return violations;
    }

    /** 汇总断言：任一违规存在即失败（最终切换门禁）。 */
    public void assertNoV1Dependencies(Path frontendSrcDir, Path mainJavaDir, Path adapterDir)
            throws IOException {
        List<String> all = new ArrayList<>();
        all.addAll(scanFrontendRoutes(frontendSrcDir));
        all.addAll(scanSpringMappings(mainJavaDir));
        all.addAll(scanProtocolVersion(adapterDir));
        if (!all.isEmpty()) {
            throw new IllegalStateException(
                    "存在运行期 v1 依赖，第二版不得发布（详细设计 30.6）: " + all);
        }
    }

    private static String readQuietly(Path path) {
        try {
            return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    public static void main(String[] args) throws IOException {
        V1RetirementVerifier verifier = new V1RetirementVerifier();
        Path repoRoot = Paths.get(args.length > 0 ? args[0] : ".");
        verifier.assertNoV1Dependencies(
                repoRoot.resolve("training-platform/frontend/src"),
                repoRoot.resolve("training-platform/src/main/java"),
                repoRoot.resolve("bluesky/plugins/training_adapter"));
        System.out.println("v1 retirement OK: 无运行期 v1 依赖");
    }
}
