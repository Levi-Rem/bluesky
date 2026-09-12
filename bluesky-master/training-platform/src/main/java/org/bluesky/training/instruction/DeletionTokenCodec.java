package org.bluesky.training.instruction;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.bluesky.training.common.V2DomainException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * P15：一次性删除确认 token 编解码（详细设计 2.2 §7.9）；不保存明文，只落摘要。
 * 评审 P0-5：载荷必须经 HMAC-SHA256 签名——无签名的 Base64(JSON) 任何客户端
 * 都可自造 revision/terminalId/未来过期时间绕过全部校验。
 * 格式：base64url(payload).base64url(hmac)
 */
public final class DeletionTokenCodec {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private DeletionTokenCodec() {
    }

    public static String encode(Map<String, Object> claims, byte[] secretKey) {
        try {
            byte[] payload = OBJECT_MAPPER.writeValueAsBytes(claims);
            byte[] signature = hmac(payload, secretKey);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(payload)
                    + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
        } catch (IOException e) {
            throw new IllegalStateException("token 编码失败", e);
        }
    }

    public static Map<String, Object> decodeAndVerify(String token, byte[] secretKey) {
        if (token == null || token.trim().isEmpty()) {
            throw confirmation("缺少一次性确认 token");
        }
        String trimmed = token.trim();
        int separator = trimmed.indexOf('.');
        if (separator <= 0 || separator == trimmed.length() - 1) {
            throw confirmation("token 结构非法");
        }
        try {
            byte[] payload = Base64.getUrlDecoder().decode(trimmed.substring(0, separator));
            byte[] signature = Base64.getUrlDecoder().decode(trimmed.substring(separator + 1));
            if (!MessageDigest.isEqual(signature, hmac(payload, secretKey))) {
                throw confirmation("token 签名校验失败");
            }
            Object parsed = OBJECT_MAPPER.readValue(payload, Object.class);
            if (!(parsed instanceof Map)) {
                throw confirmation("token 结构非法");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> claims = (Map<String, Object>) parsed;
            for (String required : Arrays.asList("aircraftId", "revision", "terminalId",
                    "expiresAtEpochSeconds")) {
                if (!claims.containsKey(required)) {
                    throw confirmation("token 缺少字段: " + required);
                }
            }
            return claims;
        } catch (IllegalArgumentException | IOException e) {
            throw confirmation("token 解析失败");
        }
    }

    /** 摘要落库（详细设计 7.9）：SHA-256，不保存明文。 */
    public static String hashForStorage(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return toHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 缺少 SHA-256", e);
        }
    }

    private static byte[] hmac(byte[] payload, byte[] secretKey) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secretKey, HMAC_ALGORITHM));
            return mac.doFinal(payload);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("JVM 缺少 HmacSHA256", e);
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

    private static V2DomainException confirmation(String message) {
        return new V2DomainException("CONFIRMATION_REQUIRED", 428, message,
                Arrays.asList("X-Confirmation-Token"));
    }
}
