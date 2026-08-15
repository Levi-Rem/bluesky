package org.bluesky.training.adapter;

public final class AdapterUnavailableException extends RuntimeException {
    public AdapterUnavailableException(String message) {
        super(message);
    }

    public AdapterUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
