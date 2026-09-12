# -*- coding: utf-8 -*-
"""P04：Adapter 幂等结果缓存（详细设计 10.1.6）。"""
import unittest

from bluesky.plugins.training_adapter.protocol_v2 import (
    IdempotencyPayloadMismatch,
    IdempotencyResultCache,
)
from bluesky.plugins.training_adapter.protocol_v2 import SequenceTracker


class IdempotencyResultCacheTest(unittest.TestCase):
    def test_same_key_same_payload_replays_without_side_effect(self):
        cache = IdempotencyResultCache()
        runs = []

        def action():
            runs.append(1)
            return {"accepted": True, "resultChecksum": "abc"}

        first = cache.execute("key-1", "checksum-1", action)
        replay = cache.execute("key-1", "checksum-1", action)

        self.assertFalse(first.replayed)
        self.assertTrue(replay.replayed)
        self.assertEqual(first.result, replay.result)
        self.assertEqual(1, len(runs), "同键同载荷不得重复执行副作用")

    def test_same_key_different_payload_is_rejected_without_side_effect(self):
        cache = IdempotencyResultCache()
        runs = []

        def action():
            runs.append(1)
            return {"ok": True}

        cache.execute("key-2", "checksum-1", action)

        with self.assertRaises(IdempotencyPayloadMismatch):
            cache.execute("key-2", "checksum-DIFFERENT", action)
        self.assertEqual(1, len(runs), "异载荷拒绝必须无副作用")

    def test_purge_expired_drops_old_entries_only(self):
        clock = {"now": 1_000}
        cache = IdempotencyResultCache(now_ms=lambda: clock["now"])
        cache.execute("key-old", "c1", lambda: "old")

        clock["now"] = 23 * 3600 * 1000
        cache.execute("key-new", "c2", lambda: "new")

        # old 距今 25 小时 → 过期；new 距今 2 小时 → 保留
        expired = cache.purge_expired(now_ms=1_000 + 25 * 3600 * 1000)

        self.assertEqual(["key-old"], expired)
        self.assertNotIn("key-old", cache._results)
        self.assertIn("key-new", cache._results)

    def test_payload_checksum_is_stable_and_content_sensitive(self):
        cache = IdempotencyResultCache()
        self.assertEqual(
            cache.payload_checksum({"a": 1, "b": 2}),
            cache.payload_checksum({"b": 2, "a": 1}),
            "键序不影响校验和")
        self.assertNotEqual(
            cache.payload_checksum({"a": 1}), cache.payload_checksum({"a": 2}))


class SequenceTrackerTest(unittest.TestCase):
    def test_duplicate_inbound_is_ignored(self):
        tracker = SequenceTracker()
        self.assertEqual("IN_ORDER", tracker.accept_inbound(1))
        self.assertEqual("DUPLICATE", tracker.accept_inbound(1))
        self.assertFalse(tracker.out_of_sync)

    def test_gap_marks_out_of_sync(self):
        tracker = SequenceTracker()
        tracker.accept_inbound(1)
        self.assertEqual("GAP", tracker.accept_inbound(3))
        self.assertTrue(tracker.out_of_sync)

    def test_new_instance_restarts_sequences(self):
        tracker = SequenceTracker()
        tracker.accept_inbound(7)
        self.assertEqual(1, tracker.next_outbound())

        tracker.reset_for_instance("engine-2")
        self.assertFalse(tracker.out_of_sync)
        self.assertEqual(1, tracker.next_outbound())
        self.assertEqual("IN_ORDER", tracker.accept_inbound(1))


if __name__ == "__main__":
    unittest.main()
