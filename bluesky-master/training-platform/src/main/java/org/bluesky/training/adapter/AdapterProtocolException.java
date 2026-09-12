package org.bluesky.training.adapter;

/** Adapter 协议层错误：code + 人类可读说明，不携带 HTTP 语义。 */
public class AdapterProtocolException extends RuntimeException {

    private final String code;

    public AdapterProtocolException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
