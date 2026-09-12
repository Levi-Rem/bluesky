package org.bluesky.training.common;

import java.util.Arrays;

/** P01：If-Match/revision 统一处理（详细设计 9.1：缺失 428，冲突 409）。 */
public class RevisionGuard {

    public long requireIfMatch(String header) {
        if (header == null || header.trim().isEmpty()) {
            throw new V2DomainException("REVISION_REQUIRED", 428,
                    "缺少 If-Match 或修订号", Arrays.asList("If-Match"));
        }
        String value = header.trim();
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1).trim();
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new V2DomainException("REVISION_REQUIRED", 428,
                    "If-Match 必须是带引号的修订号，如 \"12\"", Arrays.asList("If-Match"));
        }
    }

    public void requireExpected(long expected, long actual) {
        if (expected != actual) {
            throw new V2DomainException("REVISION_CONFLICT", 409,
                    "资源版本已经改变，当前修订号为 " + actual, Arrays.asList("revision"));
        }
    }
}
