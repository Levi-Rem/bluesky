package org.bluesky.training.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** 规范请求摘要（详细设计 9.1：method + canonicalPath + body 的 SHA-256）。 */
public final class RequestDigest {

    private RequestDigest() {
    }

    public static String sha256(String method, String canonicalPath, byte[] body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((method == null ? "" : method.toUpperCase()).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
            digest.update((canonicalPath == null ? "" : canonicalPath).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
            if (body != null) {
                digest.update(body);
            }
            return toHex(digest.digest());
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
