package org.bluesky.training.common;

import java.util.Collections;

/** v2 If-Match 解析；仅接受单个十进制 revision，可带标准双引号。 */
public final class RevisionHeaders {

    private RevisionHeaders() {
    }

    public static long requireIfMatch(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw required("缺少 If-Match");
        }
        String normalized = value.trim();
        if (normalized.startsWith("\"") && normalized.endsWith("\"")
                && normalized.length() >= 2) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        try {
            long revision = Long.parseLong(normalized);
            if (revision < 0) {
                throw required("If-Match revision 不能为负数");
            }
            return revision;
        } catch (NumberFormatException e) {
            throw required("If-Match 必须是单个 revision");
        }
    }

    private static V2DomainException required(String message) {
        return new V2DomainException("REVISION_REQUIRED", 428, message,
                Collections.singletonList("If-Match"));
    }
}
