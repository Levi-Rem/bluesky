package org.bluesky.training.adapter;

public final class EngineHealth {
    private final boolean connected;
    private final String status;
    private final String performanceModel;
    private final String message;

    public EngineHealth(boolean connected, String status, String performanceModel, String message) {
        this.connected = connected;
        this.status = status;
        this.performanceModel = performanceModel;
        this.message = message;
    }

    public boolean isConnected() {
        return connected;
    }

    public String getStatus() {
        return status;
    }

    public String getPerformanceModel() {
        return performanceModel;
    }

    public String getMessage() {
        return message;
    }
}
