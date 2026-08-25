package org.bluesky.training.adapter;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ReferenceDataSyncResult {
    private final int total;
    private final Map<String, Integer> counts;

    public ReferenceDataSyncResult(int total, Map<String, Integer> counts) {
        this.total = total;
        this.counts = Collections.unmodifiableMap(new LinkedHashMap<>(counts));
    }

    public int getTotal() { return total; }
    public Map<String, Integer> getCounts() { return counts; }
}
