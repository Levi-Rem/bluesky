package org.bluesky.training.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 幂等作用域（详细设计 9.1）：
 * 受信调用方身份 + HTTP 方法 + 规范路径 + key，输出固定 SHA-256 作用域。
 */
public final class IdempotencyScopeFactory {

    private IdempotencyScopeFactory() {
    }

    public static String create(CallerContext caller, String method, String canonicalPath, String key) {
        String material = String.join("\n",
                caller == null || caller.callerType() == null ? "" : caller.callerType().name(),
                caller == null || caller.callerId() == null ? "" : caller.callerId(),
                caller == null || caller.exerciseGroupId() == null ? "" : caller.exerciseGroupId(),
                method == null ? "" : method.toUpperCase(),
                canonicalPath == null ? "" : canonicalPath,
                key == null ? "" : key);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return toHex(digest.digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 缺少 SHA-256 实现", e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(Character.forDigit((value >> 4) & 0xF, 16));
            builder.append(Character.forDigit(value & 0xF, 16));
        }
        return builder.toString();
    }
}
