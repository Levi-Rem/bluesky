package org.bluesky.training.adapter;

/**
 * P04：双向序号（详细设计 10.1.3）：按训练组+实例+方向从 1 单调递增；
 * 重复忽略、缺口标记 OUT_OF_SYNC、实例切换后重置。
 */
public class ProtocolSequenceTracker {

    public enum AcceptResult {
        IN_ORDER, DUPLICATE, GAP
    }

    private String engineInstanceId;
    private long lastOutbound;
    private long lastInbound;
    private boolean outOfSync;

    public ProtocolSequenceTracker(String engineInstanceId) {
        this.engineInstanceId = engineInstanceId;
    }

    public synchronized long nextOutbound() {
        return ++lastOutbound;
    }

    public synchronized AcceptResult acceptInbound(long sequence) {
        if (sequence <= lastInbound) {
            return AcceptResult.DUPLICATE;
        }
        if (outOfSync) {
            // 恢复路径（评审 B1/A3）：缺口已发现后以最新到达序号为新基准继续，
            // 清除 OUT_OF_SYNC——通道不得因一次缺口永久死锁
            lastInbound = sequence;
            outOfSync = false;
            return AcceptResult.IN_ORDER;
        }
        if (sequence > lastInbound + 1) {
            outOfSync = true;
            return AcceptResult.GAP;
        }
        lastInbound = sequence;
        return AcceptResult.IN_ORDER;
    }

    public synchronized void resetForInstance(String newEngineInstanceId) {
        this.engineInstanceId = newEngineInstanceId;
        this.lastOutbound = 0;
        this.lastInbound = 0;
        this.outOfSync = false;
    }

    public synchronized boolean isOutOfSync() {
        return outOfSync;
    }

    public synchronized String engineInstanceId() {
        return engineInstanceId;
    }
}
