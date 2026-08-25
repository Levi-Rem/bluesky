package org.bluesky.dataprep.map;

/** Critical runtime navigation data is invalid and must not be partially exposed. */
public final class RuntimeMapDataException extends RuntimeException {
    public RuntimeMapDataException(String message) {
        super(message);
    }

    public RuntimeMapDataException(String message, Throwable cause) {
        super(message, cause);
    }
}
