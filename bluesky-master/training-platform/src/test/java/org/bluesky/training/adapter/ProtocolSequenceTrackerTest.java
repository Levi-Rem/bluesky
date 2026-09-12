package org.bluesky.training.adapter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P04：双向序号去重、缺口与实例切换（详细设计 10.1.3）。 */
class ProtocolSequenceTrackerTest {

    @Test
    void givenDuplicateThenIgnored() {
        ProtocolSequenceTracker tracker = new ProtocolSequenceTracker("engine-1");

        assertEquals(ProtocolSequenceTracker.AcceptResult.IN_ORDER, tracker.acceptInbound(1));
        assertEquals(ProtocolSequenceTracker.AcceptResult.DUPLICATE, tracker.acceptInbound(1));
        assertEquals(ProtocolSequenceTracker.AcceptResult.IN_ORDER, tracker.acceptInbound(2));
        assertEquals(ProtocolSequenceTracker.AcceptResult.DUPLICATE, tracker.acceptInbound(2));
        assertFalse(tracker.isOutOfSync());
    }

    @Test
    void givenGapThenOutOfSync() {
        ProtocolSequenceTracker tracker = new ProtocolSequenceTracker("engine-1");

        assertEquals(ProtocolSequenceTracker.AcceptResult.IN_ORDER, tracker.acceptInbound(1));
        assertEquals(ProtocolSequenceTracker.AcceptResult.GAP, tracker.acceptInbound(3));
        assertTrue(tracker.isOutOfSync(), "缺口必须立即标记 OUT_OF_SYNC");
    }

    @Test
    void givenNewInstanceThenSequenceRestarts() {
        ProtocolSequenceTracker tracker = new ProtocolSequenceTracker("engine-1");
        tracker.acceptInbound(7);
        tracker.acceptInbound(8);
        assertTrue(tracker.nextOutbound() >= 1);

        tracker.resetForInstance("engine-2");

        assertFalse(tracker.isOutOfSync());
        assertEquals(1L, tracker.nextOutbound());
        assertEquals(ProtocolSequenceTracker.AcceptResult.IN_ORDER, tracker.acceptInbound(1));
    }

    @Test
    void givenOutboundSequenceWhenAllocatedThenStrictlyIncreasing() {
        ProtocolSequenceTracker tracker = new ProtocolSequenceTracker("engine-1");

        long first = tracker.nextOutbound();
        assertEquals(first + 1, tracker.nextOutbound());
        assertEquals(first + 2, tracker.nextOutbound());
    }

    @Test
    void givenFirstInboundAboveOneWhenAcceptedThenMarkedGap() {
        ProtocolSequenceTracker tracker = new ProtocolSequenceTracker("engine-1");

        assertEquals(ProtocolSequenceTracker.AcceptResult.GAP, tracker.acceptInbound(5));
        assertTrue(tracker.isOutOfSync());
    }
}
