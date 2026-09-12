package org.bluesky.training.testsupport;

/**
 * P00：隔离系统时间与仿真时间的可变时钟（详细设计 4.2）。
 * 冻结期间推进仿真时间属于非法操作。
 */
public class MutableSimulationClock {

    private long seconds;
    private boolean frozen;

    public MutableSimulationClock(long startSeconds) {
        this.seconds = startSeconds;
    }

    public long nowSeconds() {
        return seconds;
    }

    public MutableSimulationClock advanceSeconds(long delta) {
        if (frozen) {
            throw new IllegalStateException("仿真时钟已冻结，禁止推进（详细设计 4.2.3）");
        }
        if (delta < 0) {
            throw new IllegalArgumentException("仿真时间只能前进");
        }
        seconds += delta;
        return this;
    }

    public void freeze() {
        this.frozen = true;
    }

    public void resume() {
        this.frozen = false;
    }

    public boolean isFrozen() {
        return frozen;
    }
}
