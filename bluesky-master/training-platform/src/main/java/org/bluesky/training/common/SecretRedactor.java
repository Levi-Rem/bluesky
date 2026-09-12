package org.bluesky.training.common;

import java.util.regex.Pattern;

/**
 * P20：日志脱敏（详细设计 2.2 §12.3/§25.2）。
 * 不得记录数据库密码、CurveZMQ 密钥或完整证书指纹。
 */
public final class SecretRedactor {

    private static final Pattern PASSWORD_PATTERN =
            Pattern.compile("(?i)(password|passwd|pwd)\\s*[=:>]\\s*\\S+");
    private static final Pattern SECRET_PATTERN =
            Pattern.compile("(?i)(secret|private[-_]?key|curve[-_]?(secret|public)[-_]?key"
                    + "|client[-_]?key)\\s*[=:>]\\s*\\S+");
    private static final Pattern TOKEN_PATTERN =
            Pattern.compile("(?i)(token|authorization)\\s*[=:>]\\s*Bearer\\s+\\S+");
    private static final Pattern JDBC_PASSWORD_PATTERN =
            Pattern.compile("password=[^;&\\s]+");

    public static final int FINGERPRINT_KEEP_PREFIX = 8;
    public static final int FINGERPRINT_KEEP_SUFFIX = 4;

    private SecretRedactor() {
    }

    /** 配置行/环境变量脱敏：password=xxx → password=***REDACTED***。 */
    public static String redactConfiguration(String text) {
        if (text == null) {
            return null;
        }
        String redacted = PASSWORD_PATTERN.matcher(text).replaceAll("$1=***REDACTED***");
        redacted = SECRET_PATTERN.matcher(redacted).replaceAll("$1=***REDACTED***");
        redacted = TOKEN_PATTERN.matcher(redacted).replaceAll("$1=***REDACTED***");
        redacted = JDBC_PASSWORD_PATTERN.matcher(redacted).replaceAll("password=***REDACTED***");
        return redacted;
    }

    /** URI 脱敏：jdbc:mysql://host/db?user=x&password=y → 密码段抹除。 */
    public static String redactUri(String uri) {
        if (uri == null) {
            return null;
        }
        return uri.replaceAll("(?i)(password=)[^&\\s]+", "$1***REDACTED***");
    }

    /** 指纹脱敏：保留前 8 后 4，中间以 … 连接；过短值原样返回（与 AuditService 一致）。 */
    public static String redactFingerprint(String fingerprint) {
        if (fingerprint == null || fingerprint.length() <= 12) {
            return fingerprint;
        }
        return fingerprint.substring(0, FINGERPRINT_KEEP_PREFIX) + "…"
                + fingerprint.substring(fingerprint.length() - FINGERPRINT_KEEP_SUFFIX);
    }

    /** 判断文本是否仍含可疑明文（测试用）。 */
    public static boolean containsSecret(String text) {
        return text != null && (PASSWORD_PATTERN.matcher(text).find()
                || SECRET_PATTERN.matcher(text).find()
                || TOKEN_PATTERN.matcher(text).find()
                || JDBC_PASSWORD_PATTERN.matcher(text).find())
                && !text.contains("***REDACTED***") && !isFullyRedacted(text);
    }

    private static boolean isFullyRedacted(String text) {
        // 检出的密钥形态行若值已是占位符则视为已脱敏
        java.util.regex.Matcher matcher = SECRET_PATTERN.matcher(text);
        while (matcher.find()) {
            String matched = matcher.group();
            if (!matched.endsWith("***REDACTED***")) {
                return false;
            }
        }
        matcher = PASSWORD_PATTERN.matcher(text);
        while (matcher.find()) {
            if (!matcher.group().endsWith("***REDACTED***")) {
                return false;
            }
        }
        return true;
    }
}
