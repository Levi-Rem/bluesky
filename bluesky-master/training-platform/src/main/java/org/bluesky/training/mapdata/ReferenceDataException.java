package org.bluesky.training.mapdata;

public final class ReferenceDataException extends RuntimeException {
    private final String code;

    public ReferenceDataException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() { return code; }
}
