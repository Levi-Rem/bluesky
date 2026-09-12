package org.bluesky.training.common;

/** 一次幂等执行的描述（详细设计 9.1）。 */
public final class IdempotencyCommand {

    private final CallerContext caller;
    private final String requestMethod;
    private final String canonicalPath;
    private final String scope;
    private final String idempotencyKey;
    private final String requestDigest;
    private final int firstHttpStatus;
    private final int expiresInHours;

    public IdempotencyCommand(CallerContext caller, String requestMethod, String canonicalPath,
                              String scope, String idempotencyKey, String requestDigest,
                              int firstHttpStatus, int expiresInHours) {
        this.caller = caller;
        this.requestMethod = requestMethod;
        this.canonicalPath = canonicalPath;
        this.scope = scope;
        this.idempotencyKey = idempotencyKey;
        this.requestDigest = requestDigest;
        this.firstHttpStatus = firstHttpStatus;
        this.expiresInHours = expiresInHours;
    }

    public CallerContext caller() {
        return caller;
    }

    public String requestMethod() {
        return requestMethod;
    }

    public String canonicalPath() {
        return canonicalPath;
    }

    public String scope() {
        return scope;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }

    public String requestDigest() {
        return requestDigest;
    }

    public int firstHttpStatus() {
        return firstHttpStatus;
    }

    public int expiresInHours() {
        return expiresInHours;
    }
}
