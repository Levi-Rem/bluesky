package org.bluesky.training.common;

/** 幂等执行结果：replay=true 时响应体与状态码来自首次执行的持久化记录。 */
public final class IdempotentResult {

    private final boolean replayed;
    private final int httpStatus;
    private final String responseBody;

    public IdempotentResult(boolean replayed, int httpStatus, String responseBody) {
        this.replayed = replayed;
        this.httpStatus = httpStatus;
        this.responseBody = responseBody;
    }

    public boolean replayed() {
        return replayed;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String responseBody() {
        return responseBody;
    }
}
