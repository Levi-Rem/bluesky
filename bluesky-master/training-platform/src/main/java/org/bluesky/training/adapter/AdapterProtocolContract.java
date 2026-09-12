package org.bluesky.training.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 从随构建产物打包的权威 JSON Schema 加载协议枚举，运行时代码不手抄消息清单。 */
final class AdapterProtocolContract {

    private static final Set<String> MESSAGE_TYPES = loadMessageTypes();

    private AdapterProtocolContract() {
    }

    static boolean supportsMessageType(Object value) {
        return value != null && MESSAGE_TYPES.contains(String.valueOf(value));
    }

    @SuppressWarnings("unchecked")
    private static Set<String> loadMessageTypes() {
        try (InputStream input = AdapterProtocolContract.class.getResourceAsStream(
                "/contracts/adapter-protocol-v2.schema.json")) {
            if (input == null) {
                throw new IllegalStateException("运行包缺少 Adapter Protocol 2.0 Schema");
            }
            Object parsed = new ObjectMapper().readValue(input, Object.class);
            Map<String, Object> root = (Map<String, Object>) parsed;
            Map<String, Object> properties = (Map<String, Object>) root.get("properties");
            Map<String, Object> messageType = (Map<String, Object>) properties.get("messageType");
            List<Object> values = (List<Object>) messageType.get("enum");
            Set<String> result = new LinkedHashSet<>();
            for (Object value : values) {
                result.add(String.valueOf(value));
            }
            if (result.isEmpty()) {
                throw new IllegalStateException("Adapter Protocol 2.0 Schema 的 messageType 枚举为空");
            }
            return Collections.unmodifiableSet(result);
        } catch (IOException | ClassCastException e) {
            throw new IllegalStateException("无法加载 Adapter Protocol 2.0 Schema", e);
        }
    }
}
