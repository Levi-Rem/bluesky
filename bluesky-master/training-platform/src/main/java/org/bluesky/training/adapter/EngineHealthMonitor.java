package org.bluesky.training.adapter;

/** P04：心跳健康转换（详细设计 10.1.5：1 秒心跳、3 秒 DEGRADED、5 秒 DISCONNECTED）。 */
public class EngineHealthMonitor {

    public static final long DEGRADED_AFTER_MILLIS = 3_000L;
    public static final long DISCONNECTED_AFTER_MILLIS = 5_000L;

    private Long lastHeartbeatMillis;

    public synchronized void onHeartbeat(long nowMillis) {
        this.lastHeartbeatMillis = nowMillis;
    }

    public synchronized String detectTimeouts(long nowMillis) {
        if (lastHeartbeatMillis == null) {
            return "DISCONNECTED";
        }
        long silence = nowMillis - lastHeartbeatMillis;
        if (silence >= DISCONNECTED_AFTER_MILLIS) {
            return "DISCONNECTED";
        }
        if (silence >= DEGRADED_AFTER_MILLIS) {
            return "DEGRADED";
        }
        return "CONNECTED";
    }

    public synchronized Long lastHeartbeatMillis() {
        return lastHeartbeatMillis;
    }
}
