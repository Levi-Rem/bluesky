package org.bluesky.training.adapter;

public final class AdapterRejectedException extends RuntimeException {
    private final String code;

    public AdapterRejectedException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
