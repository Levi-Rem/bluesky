package org.bluesky.training.adapter;

import org.bluesky.training.reference.ReferenceSnapshotStore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * P04：分片快照重组（详细设计 10.1.4）：乱序到达可组装、重复忽略、
 * 缺片不完整、checksum 错误整组重取、超过 2 秒未完成的整组丢弃。
 */
public class SnapshotChunkAssembler {

    public static final long INCOMPLETE_EXPIRY_SECONDS = 2;

    private final Map<String, TreeMap<Integer, Map<String, Object>>> snapshots = new LinkedHashMap<>();
    private final Map<String, Integer> chunkCounts = new LinkedHashMap<>();
    private final Map<String, Instant> lastTouched = new LinkedHashMap<>();

    public synchronized void acceptChunk(Map<String, Object> chunk) {
        String snapshotId = String.valueOf(chunk.get("snapshotId"));
        int index = ((Number) chunk.get("chunkIndex")).intValue();
        int count = ((Number) chunk.get("chunkCount")).intValue();
        if (count <= 0 || index < 0 || index >= count) {
            throw new AdapterProtocolException("CHUNK_INDEX_INVALID",
                    "分片索引非法: " + index + "/" + count);
        }
        TreeMap<Integer, Map<String, Object>> pieces =
                snapshots.computeIfAbsent(snapshotId, key -> new TreeMap<>());
        chunkCounts.put(snapshotId, count);
        lastTouched.put(snapshotId, Instant.now());
        pieces.putIfAbsent(index, chunk);
    }

    public synchronized boolean isComplete(String snapshotId) {
        TreeMap<Integer, Map<String, Object>> pieces = snapshots.get(snapshotId);
        Integer count = chunkCounts.get(snapshotId);
        return pieces != null && count != null && pieces.size() == count
                && pieces.firstKey() == 0 && pieces.lastKey() == count - 1;
    }

    public synchronized String assemble(String snapshotId) {
        if (!isComplete(snapshotId)) {
            throw new AdapterProtocolException("SNAPSHOT_INCOMPLETE",
                    "快照分片不完整: " + snapshotId);
        }
        List<String> payloads = new ArrayList<>();
        for (Map<String, Object> chunk : snapshots.get(snapshotId).values()) {
            payloads.add(String.valueOf(chunk.get("payload")));
        }
        return "[" + String.join(",", payloads) + "]";
    }

    /** 组装并校验整组 checksum；失败丢弃整组等待重取。 */
    public synchronized String assembleAndVerify(String snapshotId, String expectedChecksum) {
        String assembled = assemble(snapshotId);
        if (!ReferenceSnapshotStore.sha256OfText(assembled).equals(expectedChecksum)) {
            drop(snapshotId);
            throw new AdapterProtocolException("SNAPSHOT_CHECKSUM_MISMATCH",
                    "快照 checksum 不一致，整组等待重取: " + snapshotId);
        }
        return assembled;
    }

    /** 丢弃超过 2 秒仍未完成的整组（缺片整组重取）。 */
    public synchronized void expireIncomplete(Instant now) {
        List<String> expired = new ArrayList<>();
        for (Map.Entry<String, Instant> touched : lastTouched.entrySet()) {
            if (!isComplete(touched.getKey())
                    && touched.getValue().plusSeconds(INCOMPLETE_EXPIRY_SECONDS).isBefore(now)) {
                expired.add(touched.getKey());
            }
        }
        for (String snapshotId : expired) {
            drop(snapshotId);
        }
    }

    private void drop(String snapshotId) {
        snapshots.remove(snapshotId);
        chunkCounts.remove(snapshotId);
        lastTouched.remove(snapshotId);
    }

    public synchronized void remove(String snapshotId) { drop(snapshotId); }
}
