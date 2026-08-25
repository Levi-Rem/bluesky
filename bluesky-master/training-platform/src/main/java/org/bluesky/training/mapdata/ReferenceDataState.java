package org.bluesky.training.mapdata;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ReferenceDataState {
    private final boolean ready;
    private final String status;
    private final int pointCount;
    private final Map<String, Integer> counts;
    private final String message;

    private ReferenceDataState(boolean ready, String status, int pointCount,
                               Map<String, Integer> counts, String message) {
        this.ready = ready;
        this.status = status;
        this.pointCount = pointCount;
        this.counts = Collections.unmodifiableMap(new LinkedHashMap<>(counts));
        this.message = message;
    }

    public static ReferenceDataState loading() {
        return state(false, "LOADING", 0, Collections.emptyMap(), "正在加载导航参考数据");
    }

    public static ReferenceDataState snapshotReady(Map<String, Integer> counts) {
        return state(false, "SYNC_PENDING", total(counts), counts, "等待仿真引擎确认导航参考数据");
    }

    public static ReferenceDataState ready(Map<String, Integer> counts) {
        return state(true, "READY", total(counts), counts, "导航参考数据已就绪");
    }

    public static ReferenceDataState failed(String status, String message) {
        return state(false, status, 0, Collections.emptyMap(), message);
    }

    private static ReferenceDataState state(boolean ready, String status, int pointCount,
                                            Map<String, Integer> counts, String message) {
        return new ReferenceDataState(ready, status, pointCount, counts, message);
    }

    private static int total(Map<String, Integer> counts) {
        int total = 0;
        for (Integer value : counts.values()) total += value == null ? 0 : value;
        return total;
    }

    public boolean isReady() { return ready; }
    public String getStatus() { return status; }
    public int getPointCount() { return pointCount; }
    public Map<String, Integer> getCounts() { return counts; }
    public String getMessage() { return message; }
}
